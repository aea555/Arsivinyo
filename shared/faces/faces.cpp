// The model-independent half of the faces pipeline. See faces.h.
//
// Plain float arithmetic throughout and no -ffast-math: the phone and the Mac must reach
// the same numbers from the same frame, and reassociation is exactly what would let them
// drift apart.

#include "faces.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <numeric>

namespace faces {

namespace {

// The five points of the 112x112 ArcFace template SFace was trained on.
constexpr float kTemplate[10] = {
    38.2946f, 51.6963f,  // right eye
    73.5318f, 51.5014f,  // left eye
    56.0252f, 71.7366f,  // nose
    41.5493f, 92.3655f,  // right mouth corner
    70.7299f, 92.2041f,  // left mouth corner
};

struct Rgb {
    float r, g, b;
};

inline Rgb pixel(const Image& image, int x, int y) {
    const uint8_t* p = image.pixels + static_cast<size_t>(y) * image.stride;
    switch (image.order) {
    case PixelOrder::RGBA:
        p += x * 4;
        return {float(p[0]), float(p[1]), float(p[2])};
    case PixelOrder::BGRA:
        p += x * 4;
        return {float(p[2]), float(p[1]), float(p[0])};
    case PixelOrder::RGB:
    default:
        p += x * 3;
        return {float(p[0]), float(p[1]), float(p[2])};
    }
}

/**
 * Weights for resampling one axis from [in] samples to [out], a triangle filter widened by
 * the reduction factor when shrinking so every source pixel counts (Pillow's antialiased
 * bilinear). Each output sample gets a first index and its weights.
 */
struct Taps {
    std::vector<int> first;
    std::vector<std::vector<float>> weights;
};

Taps taps(int in, int out) {
    Taps t;
    t.first.resize(out);
    t.weights.resize(out);
    const double scale = double(in) / double(out);
    const double support = std::max(1.0, scale);
    for (int o = 0; o < out; ++o) {
        const double center = (o + 0.5) * scale;
        int lo = int(std::floor(center - support));
        int hi = int(std::ceil(center + support));
        lo = std::max(lo, 0);
        hi = std::min(hi, in);
        std::vector<float> w;
        double total = 0;
        for (int i = lo; i < hi; ++i) {
            double d = std::fabs((i + 0.5 - center) / support);
            double v = d < 1.0 ? 1.0 - d : 0.0;
            w.push_back(float(v));
            total += v;
        }
        if (total > 0) {
            for (auto& v : w) v = float(v / total);
        }
        t.first[o] = lo;
        t.weights[o] = std::move(w);
    }
    return t;
}

float iouOf(const Detection& a, const Detection& b) {
    const float x1 = std::max(a.x, b.x), y1 = std::max(a.y, b.y);
    const float x2 = std::min(a.x + a.w, b.x + b.w), y2 = std::min(a.y + a.h, b.y + b.h);
    const float inter = std::max(0.0f, x2 - x1) * std::max(0.0f, y2 - y1);
    const float uni = a.w * a.h + b.w * b.h - inter;
    return uni > 0 ? inter / uni : 0.0f;
}

// IEEE 754 binary16, round to nearest even.
uint16_t toHalf(float value) {
    uint32_t bits;
    std::memcpy(&bits, &value, 4);
    const uint32_t sign = (bits >> 16) & 0x8000u;
    int32_t exponent = int32_t((bits >> 23) & 0xff) - 127 + 15;
    uint32_t mantissa = bits & 0x7fffffu;
    if (((bits >> 23) & 0xff) == 0xff) return uint16_t(sign | 0x7c00u | (mantissa ? 0x200u : 0));
    if (exponent >= 31) return uint16_t(sign | 0x7c00u);
    if (exponent <= 0) {
        if (exponent < -10) return uint16_t(sign);
        mantissa |= 0x800000u;
        const int shift = 14 - exponent;
        uint32_t half = mantissa >> shift;
        const uint32_t rest = mantissa & ((1u << shift) - 1);
        const uint32_t mid = 1u << (shift - 1);
        if (rest > mid || (rest == mid && (half & 1))) ++half;
        return uint16_t(sign | half);
    }
    uint32_t half = sign | (uint32_t(exponent) << 10) | (mantissa >> 13);
    const uint32_t rest = mantissa & 0x1fffu;
    if (rest > 0x1000u || (rest == 0x1000u && (half & 1))) ++half;
    return uint16_t(half);
}

float fromHalf(uint16_t half) {
    const uint32_t sign = uint32_t(half & 0x8000u) << 16;
    uint32_t exponent = (half >> 10) & 0x1f;
    uint32_t mantissa = half & 0x3ffu;
    uint32_t bits;
    if (exponent == 0) {
        if (mantissa == 0) {
            bits = sign;
        } else {
            exponent = 127 - 15 + 1;
            while (!(mantissa & 0x400u)) {
                mantissa <<= 1;
                --exponent;
            }
            mantissa &= 0x3ffu;
            bits = sign | (exponent << 23) | (mantissa << 13);
        }
    } else if (exponent == 31) {
        bits = sign | 0x7f800000u | (mantissa << 13);
    } else {
        bits = sign | ((exponent - 15 + 127) << 23) | (mantissa << 13);
    }
    float value;
    std::memcpy(&value, &bits, 4);
    return value;
}

}  // namespace

// ---- detection ---------------------------------------------------------------------------

Planar detectorInput(const Image& image, float* scale) {
    Planar out;
    out.width = kDetectorSize;
    out.height = kDetectorSize;
    out.data.assign(size_t(3) * kDetectorSize * kDetectorSize, 0.0f);
    if (!image.pixels || image.width <= 0 || image.height <= 0) {
        *scale = 1;
        return out;
    }
    const float s = std::min(float(kDetectorSize) / image.width, float(kDetectorSize) / image.height);
    const int w = std::clamp(int(std::lround(image.width * s)), 1, kDetectorSize);
    const int h = std::clamp(int(std::lround(image.height * s)), 1, kDetectorSize);
    *scale = s;

    // Horizontal pass into rows of the source height, then vertical into the output.
    const Taps across = taps(image.width, w);
    const Taps down = taps(image.height, h);
    std::vector<Rgb> rows(size_t(image.height) * w);
    for (int y = 0; y < image.height; ++y) {
        for (int x = 0; x < w; ++x) {
            Rgb sum{0, 0, 0};
            const auto& weights = across.weights[x];
            for (size_t k = 0; k < weights.size(); ++k) {
                const Rgb p = pixel(image, across.first[x] + int(k), y);
                sum.r += p.r * weights[k];
                sum.g += p.g * weights[k];
                sum.b += p.b * weights[k];
            }
            rows[size_t(y) * w + x] = sum;
        }
    }
    const size_t plane = size_t(kDetectorSize) * kDetectorSize;
    for (int y = 0; y < h; ++y) {
        const auto& weights = down.weights[y];
        for (int x = 0; x < w; ++x) {
            Rgb sum{0, 0, 0};
            for (size_t k = 0; k < weights.size(); ++k) {
                const Rgb p = rows[size_t(down.first[y] + int(k)) * w + x];
                sum.r += p.r * weights[k];
                sum.g += p.g * weights[k];
                sum.b += p.b * weights[k];
            }
            // YuNet was trained on OpenCV's BGR.
            const size_t at = size_t(y) * kDetectorSize + x;
            out.data[at] = std::round(sum.b);
            out.data[plane + at] = std::round(sum.g);
            out.data[2 * plane + at] = std::round(sum.r);
        }
    }
    return out;
}

std::vector<Detection> decodeYuNet(const std::vector<YuNetLevel>& levels, float scale) {
    std::vector<Detection> found;
    for (const auto& level : levels) {
        const int cols = kDetectorSize / level.stride;
        const int rows = kDetectorSize / level.stride;
        for (int r = 0; r < rows; ++r) {
            for (int c = 0; c < cols; ++c) {
                const int idx = r * cols + c;
                const float cls = std::clamp(level.cls[idx], 0.0f, 1.0f);
                const float obj = std::clamp(level.obj[idx], 0.0f, 1.0f);
                const float score = std::sqrt(cls * obj);
                if (score < kMinScore) continue;
                const float* box = level.bbox + idx * 4;
                const float cx = (c + box[0]) * level.stride;
                const float cy = (r + box[1]) * level.stride;
                const float w = std::exp(box[2]) * level.stride;
                const float h = std::exp(box[3]) * level.stride;
                Detection d;
                d.x = (cx - w / 2) / scale;
                d.y = (cy - h / 2) / scale;
                d.w = w / scale;
                d.h = h / scale;
                d.score = score;
                const float* kps = level.kps + idx * 10;
                for (int n = 0; n < 5; ++n) {
                    d.landmarks[2 * n] = (kps[2 * n] + c) * level.stride / scale;
                    d.landmarks[2 * n + 1] = (kps[2 * n + 1] + r) * level.stride / scale;
                }
                found.push_back(d);
            }
        }
    }
    found = suppress(std::move(found), kNmsIou);
    found.erase(std::remove_if(found.begin(), found.end(),
                               [](const Detection& d) { return std::min(d.w, d.h) < kMinFacePixels; }),
                found.end());
    return found;
}

std::vector<Detection> suppress(std::vector<Detection> detections, float iou) {
    std::stable_sort(detections.begin(), detections.end(),
                     [](const Detection& a, const Detection& b) { return a.score > b.score; });
    std::vector<Detection> kept;
    for (const auto& d : detections) {
        bool overlaps = false;
        for (const auto& k : kept) {
            if (iouOf(d, k) > iou) {
                overlaps = true;
                break;
            }
        }
        if (!overlaps) kept.push_back(d);
    }
    return kept;
}

// ---- alignment ---------------------------------------------------------------------------

std::array<float, 6> alignment(const std::array<float, 10>& landmarks) {
    // Least-squares similarity in closed form: with both point sets centred, the rotation
    // and scale are a = Σ(s·d)/Σ|s|² and b = Σ(s×d)/Σ|s|², which is Umeyama's answer
    // whenever no reflection is involved, and a face never needs one.
    double smx = 0, smy = 0, dmx = 0, dmy = 0;
    for (int i = 0; i < 5; ++i) {
        smx += landmarks[2 * i];
        smy += landmarks[2 * i + 1];
        dmx += kTemplate[2 * i];
        dmy += kTemplate[2 * i + 1];
    }
    smx /= 5;
    smy /= 5;
    dmx /= 5;
    dmy /= 5;
    double dot = 0, cross = 0, norm = 0;
    for (int i = 0; i < 5; ++i) {
        const double sx = landmarks[2 * i] - smx, sy = landmarks[2 * i + 1] - smy;
        const double dx = kTemplate[2 * i] - dmx, dy = kTemplate[2 * i + 1] - dmy;
        dot += sx * dx + sy * dy;
        cross += sx * dy - sy * dx;
        norm += sx * sx + sy * sy;
    }
    if (norm <= 0) return {1, 0, 0, 0, 1, 0};
    const double a = dot / norm, b = cross / norm;
    const double tx = dmx - (a * smx - b * smy);
    const double ty = dmy - (b * smx + a * smy);
    return {float(a), float(-b), float(tx), float(b), float(a), float(ty)};
}

Planar recogniserInput(const Image& image, const Detection& face) {
    Planar out;
    out.width = kAlignedSize;
    out.height = kAlignedSize;
    out.data.assign(size_t(3) * kAlignedSize * kAlignedSize, 0.0f);
    const auto m = alignment(face.landmarks);
    // Each output pixel is fetched from the source through the inverse transform.
    const double det = double(m[0]) * m[4] - double(m[1]) * m[3];
    if (det == 0) return out;
    const double i0 = m[4] / det, i1 = -m[1] / det, i3 = -m[3] / det, i4 = m[0] / det;
    const double i2 = -(i0 * m[2] + i1 * m[5]), i5 = -(i3 * m[2] + i4 * m[5]);
    const size_t plane = size_t(kAlignedSize) * kAlignedSize;
    for (int v = 0; v < kAlignedSize; ++v) {
        for (int u = 0; u < kAlignedSize; ++u) {
            const double sx = i0 * u + i1 * v + i2;
            const double sy = i3 * u + i4 * v + i5;
            const int x0 = int(std::floor(sx)), y0 = int(std::floor(sy));
            const float fx = float(sx - x0), fy = float(sy - y0);
            Rgb acc{0, 0, 0};
            for (int k = 0; k < 4; ++k) {
                const int x = x0 + (k & 1), y = y0 + (k >> 1);
                if (x < 0 || y < 0 || x >= image.width || y >= image.height) continue;  // black outside
                const float w = ((k & 1) ? fx : 1 - fx) * ((k >> 1) ? fy : 1 - fy);
                const Rgb p = pixel(image, x, y);
                acc.r += p.r * w;
                acc.g += p.g * w;
                acc.b += p.b * w;
            }
            const size_t at = size_t(v) * kAlignedSize + u;
            out.data[at] = std::round(acc.r);
            out.data[plane + at] = std::round(acc.g);
            out.data[2 * plane + at] = std::round(acc.b);
        }
    }
    return out;
}

// ---- signatures --------------------------------------------------------------------------

Signature normalised(const float* values) {
    Signature s{};
    double sum = 0;
    for (int i = 0; i < kSignatureSize; ++i) sum += double(values[i]) * values[i];
    const double length = std::sqrt(sum);
    for (int i = 0; i < kSignatureSize; ++i) s[i] = length > 0 ? float(values[i] / length) : 0.0f;
    return s;
}

float cosine(const Signature& a, const Signature& b) {
    double sum = 0;
    for (int i = 0; i < kSignatureSize; ++i) sum += double(a[i]) * b[i];
    return float(sum);
}

float bestMatch(const Signature& face, const std::vector<Signature>& set) {
    float best = -1;
    for (const auto& s : set) best = std::max(best, cosine(face, s));
    return best;
}

std::vector<uint8_t> encode(const Signature& signature) {
    std::vector<uint8_t> out(kSignatureSize * 2);
    for (int i = 0; i < kSignatureSize; ++i) {
        const uint16_t h = toHalf(signature[i]);
        out[2 * i] = uint8_t(h & 0xff);
        out[2 * i + 1] = uint8_t(h >> 8);
    }
    return out;
}

bool decode(const uint8_t* bytes, size_t size, Signature* out) {
    if (size != size_t(kSignatureSize) * 2) return false;
    float values[kSignatureSize];
    for (int i = 0; i < kSignatureSize; ++i) {
        values[i] = fromHalf(uint16_t(bytes[2 * i] | (bytes[2 * i + 1] << 8)));
        if (!std::isfinite(values[i])) return false;
    }
    // Half precision loses a little length; normalising again keeps cosines comparable.
    *out = normalised(values);
    return true;
}

std::vector<Signature> addToSet(std::vector<Signature> set, const Signature& signature) {
    set.push_back(signature);
    if (int(set.size()) <= kMaxSignatures) return set;
    // Drop the most redundant: the one whose similarities to all the others sum highest.
    // On a tie the later one goes, so the new signature is the one to give way.
    size_t drop = 0;
    double worst = -1e9;
    for (size_t i = 0; i < set.size(); ++i) {
        double sum = 0;
        for (size_t j = 0; j < set.size(); ++j) {
            if (i != j) sum += cosine(set[i], set[j]);
        }
        if (sum >= worst) {
            worst = sum;
            drop = i;
        }
    }
    set.erase(set.begin() + long(drop));
    return set;
}

// ---- within a meme, and across the collection --------------------------------------------

std::vector<MemeFace> mergeSightings(const std::vector<Sighting>& sightings) {
    std::vector<size_t> order(sightings.size());
    std::iota(order.begin(), order.end(), 0);
    std::stable_sort(order.begin(), order.end(), [&](size_t a, size_t b) {
        return sightings[a].detection.score > sightings[b].detection.score;
    });
    std::vector<MemeFace> faces;
    std::vector<std::array<double, kSignatureSize>> sums;
    for (size_t index : order) {
        const auto& s = sightings[index];
        int best = -1;
        float bestCos = kSure;
        for (size_t f = 0; f < faces.size(); ++f) {
            const float c = cosine(s.signature, faces[f].signature);
            if (c >= bestCos) {
                bestCos = c;
                best = int(f);
            }
        }
        if (best < 0) {
            MemeFace face;
            face.signature = s.signature;
            face.detection = s.detection;
            face.frameMs = s.frameMs;
            face.sightings = 1;
            faces.push_back(face);
            std::array<double, kSignatureSize> sum{};
            for (int i = 0; i < kSignatureSize; ++i) sum[i] = s.signature[i];
            sums.push_back(sum);
        } else {
            auto& sum = sums[size_t(best)];
            for (int i = 0; i < kSignatureSize; ++i) sum[i] += s.signature[i];
            float mean[kSignatureSize];
            for (int i = 0; i < kSignatureSize; ++i) mean[i] = float(sum[i]);
            faces[size_t(best)].signature = normalised(mean);
            faces[size_t(best)].sightings += 1;
        }
    }
    return faces;
}

std::vector<int> sampleTimes(int durationMs) {
    if (durationMs <= 0) return {0};
    const int seconds = std::max(1, durationMs / 1000);
    std::vector<int> times;
    if (seconds <= kMaxFrames) {
        // The middle of each second.
        for (int i = 0; i < seconds; ++i) times.push_back(std::min(i * 1000 + 500, durationMs - 1));
    } else {
        for (int i = 0; i < kMaxFrames; ++i) {
            times.push_back(int((int64_t(2 * i + 1) * durationMs) / (2 * kMaxFrames)));
        }
    }
    return times;
}

std::vector<Sighting> Models::look(const Image& image, int frameMs) {
    std::vector<Sighting> out;
    for (const auto& d : detect(image)) {
        Sighting s;
        s.detection = d;
        s.signature = embed(image, d);
        s.frameMs = frameMs;
        out.push_back(s);
    }
    return out;
}

}  // namespace faces
