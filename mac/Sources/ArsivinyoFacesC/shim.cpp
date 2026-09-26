#include "arsivinyo_faces.h"

#include <algorithm>
#include <cstring>

#include "faces.h"

using namespace faces;

struct av_faces {
    std::unique_ptr<Models> models;
};

namespace {

Signature signatureAt(const float *values) {
    Signature s{};
    std::memcpy(s.data(), values, sizeof(float) * kSignatureSize);
    return s;
}

}  // namespace

extern "C" {

av_faces *av_faces_load(const uint8_t *detector, size_t detectorSize, const uint8_t *recogniser,
                        size_t recogniserSize, char *error, size_t errorCapacity) {
    std::string why;
    auto models = Models::load(std::vector<uint8_t>(detector, detector + detectorSize),
                               std::vector<uint8_t>(recogniser, recogniser + recogniserSize), &why);
    if (!models) {
        if (error && errorCapacity) {
            std::strncpy(error, why.c_str(), errorCapacity - 1);
            error[errorCapacity - 1] = 0;
        }
        return nullptr;
    }
    return new av_faces{std::move(models)};
}

void av_faces_free(av_faces *faces) { delete faces; }

int av_faces_look(av_faces *faces, const uint8_t *pixels, int width, int height, int stride, int bgra,
                  int32_t frameMs, av_face_sighting *out, int capacity) {
    if (!faces || !pixels) return 0;
    Image image{pixels, width, height, stride, bgra ? PixelOrder::BGRA : PixelOrder::RGBA};
    const auto seen = faces->models->look(image, frameMs);
    const int n = int(seen.size());
    for (int i = 0; i < std::min(n, capacity); ++i) {
        const auto &d = seen[size_t(i)].detection;
        out[i].x = d.x;
        out[i].y = d.y;
        out[i].w = d.w;
        out[i].h = d.h;
        out[i].score = d.score;
        std::memcpy(out[i].landmarks, d.landmarks.data(), sizeof(out[i].landmarks));
        std::memcpy(out[i].signature, seen[size_t(i)].signature.data(), sizeof(out[i].signature));
        out[i].frameMs = seen[size_t(i)].frameMs;
    }
    return n;
}

int av_faces_merge(const av_face_sighting *sightings, int count, av_meme_face *out, int capacity) {
    std::vector<Sighting> in;
    for (int i = 0; i < count; ++i) {
        Sighting s;
        s.detection.x = sightings[i].x;
        s.detection.y = sightings[i].y;
        s.detection.w = sightings[i].w;
        s.detection.h = sightings[i].h;
        s.detection.score = sightings[i].score;
        std::memcpy(s.detection.landmarks.data(), sightings[i].landmarks, sizeof(sightings[i].landmarks));
        s.signature = signatureAt(sightings[i].signature);
        s.frameMs = sightings[i].frameMs;
        in.push_back(s);
    }
    const auto merged = mergeSightings(in);
    const int n = int(merged.size());
    for (int i = 0; i < std::min(n, capacity); ++i) {
        const auto &f = merged[size_t(i)];
        out[i].x = f.detection.x;
        out[i].y = f.detection.y;
        out[i].w = f.detection.w;
        out[i].h = f.detection.h;
        out[i].score = f.detection.score;
        std::memcpy(out[i].signature, f.signature.data(), sizeof(out[i].signature));
        out[i].frameMs = f.frameMs;
        out[i].sightings = f.sightings;
    }
    return n;
}

float av_faces_cosine(const float *a, const float *b) { return cosine(signatureAt(a), signatureAt(b)); }

float av_faces_best(const float *signature, const float *set, int count) {
    std::vector<Signature> all;
    for (int i = 0; i < count; ++i) all.push_back(signatureAt(set + size_t(i) * kSignatureSize));
    return bestMatch(signatureAt(signature), all);
}

int av_faces_add_to_set(float *set, int count, const float *signature) {
    std::vector<Signature> all;
    for (int i = 0; i < count; ++i) all.push_back(signatureAt(set + size_t(i) * kSignatureSize));
    all = addToSet(all, signatureAt(signature));
    for (size_t i = 0; i < all.size(); ++i) std::memcpy(set + i * kSignatureSize, all[i].data(), sizeof(float) * kSignatureSize);
    return int(all.size());
}

void av_faces_group(const float *signatures, int count, float threshold, int32_t *labels) {
    std::vector<Signature> all;
    for (int i = 0; i < count; ++i) all.push_back(signatureAt(signatures + size_t(i) * kSignatureSize));
    const auto result = group(all, threshold);
    for (int i = 0; i < count; ++i) labels[i] = result[size_t(i)];
}

void av_faces_encode(const float *signature, uint8_t *out) {
    const auto bytes = encode(signatureAt(signature));
    std::memcpy(out, bytes.data(), bytes.size());
}

int av_faces_decode(const uint8_t *bytes, size_t size, float *signature) {
    Signature s{};
    if (!decode(bytes, size, &s)) return 0;
    std::memcpy(signature, s.data(), sizeof(float) * kSignatureSize);
    return 1;
}

int av_faces_sample_times(int32_t durationMs, int32_t *out, int capacity) {
    const auto times = sampleTimes(durationMs);
    const int n = int(times.size());
    for (int i = 0; i < std::min(n, capacity); ++i) out[i] = times[size_t(i)];
    return n;
}

float av_faces_sure(void) { return kSure; }
float av_faces_ask(void) { return kAsk; }
int av_faces_pipeline_version(void) { return kPipelineVersion; }

}  // extern "C"
