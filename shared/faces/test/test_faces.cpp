// Host test for the faces pipeline: the pure functions, then the real models on the
// fixtures. `--write` prints VECTORS.json, which the Mac's CoreChecks hold both apps to.
//
//   shared/faces/test/run.sh [--write]

#include "faces.h"

#include <cmath>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <iterator>
#include <sstream>
#include <string>

using namespace faces;

static int failures = 0;

static void check(bool ok, const std::string& what) {
    std::printf("  %s  %s\n", ok ? "ok  " : "FAIL", what.c_str());
    if (!ok) ++failures;
}

static std::vector<uint8_t> readFile(const std::string& path) {
    std::ifstream in(path, std::ios::binary);
    return std::vector<uint8_t>(std::istreambuf_iterator<char>(in), {});
}

struct Ppm {
    int width = 0, height = 0;
    std::vector<uint8_t> rgb;
};

static Ppm readPpm(const std::string& path) {
    Ppm p;
    std::vector<uint8_t> bytes = readFile(path);
    std::string header(bytes.begin(), bytes.begin() + std::min<size_t>(bytes.size(), 64));
    std::istringstream s(header);
    std::string magic;
    int max = 0;
    s >> magic >> p.width >> p.height >> max;
    const size_t start = size_t(s.tellg()) + 1;
    p.rgb.assign(bytes.begin() + long(start), bytes.end());
    return p;
}

static Signature unit(int axis, float lean = 0, int other = 1) {
    float v[kSignatureSize] = {};
    v[axis] = 1;
    v[other] += lean;
    return normalised(v);
}

static void pure() {
    std::printf("pure\n");
    // Half floats round-trip a signature closely enough that cosines do not move.
    float raw[kSignatureSize];
    for (int i = 0; i < kSignatureSize; ++i) raw[i] = std::sin(float(i) * 0.37f);
    const Signature s = normalised(raw);
    Signature back{};
    const auto bytes = encode(s);
    check(bytes.size() == 256 && decode(bytes.data(), bytes.size(), &back), "a signature encodes to 256 bytes and back");
    check(cosine(s, back) > 0.99999f, "and loses nothing that matters");
    check(!decode(bytes.data(), 255, &back), "a short signature is refused");

    // The template's own landmarks align to the identity.
    std::array<float, 10> lm = {38.2946f, 51.6963f, 73.5318f, 51.5014f, 56.0252f,
                                71.7366f, 41.5493f, 92.3655f, 70.7299f, 92.2041f};
    auto m = alignment(lm);
    check(std::fabs(m[0] - 1) < 1e-5 && std::fabs(m[1]) < 1e-5 && std::fabs(m[2]) < 1e-4 &&
              std::fabs(m[3]) < 1e-5 && std::fabs(m[4] - 1) < 1e-5 && std::fabs(m[5]) < 1e-4,
          "the template aligns to itself");
    // Doubled and shifted, it comes back halved and shifted back.
    for (int i = 0; i < 5; ++i) {
        lm[2 * i] = lm[2 * i] * 2 + 10;
        lm[2 * i + 1] = lm[2 * i + 1] * 2 + 20;
    }
    m = alignment(lm);
    check(std::fabs(m[0] - 0.5f) < 1e-5 && std::fabs(m[2] + 5) < 1e-3 && std::fabs(m[5] + 10) < 1e-3,
          "a face twice the size is scaled down onto it");

    Detection a{0, 0, 100, 100, 0.9f, {}}, b{10, 10, 100, 100, 0.95f, {}}, c{300, 300, 50, 50, 0.85f, {}};
    auto kept = suppress({a, b, c}, kNmsIou);
    check(kept.size() == 2 && kept[0].score == 0.95f && kept[1].score == 0.85f, "overlapping detections keep the stronger");

    // Three near one axis, two near another, one alone.
    std::vector<Signature> sigs = {unit(0, 0.1f), unit(5, 0.1f, 6), unit(0, 0.2f), unit(5, 0.2f, 6), unit(0), unit(9)};
    auto labels = group(sigs);
    check(labels[0] == 0 && labels[2] == 0 && labels[4] == 0, "the largest group comes first");
    check(labels[1] == 1 && labels[3] == 1 && labels[5] == 2, "then the next, then the one alone");

    std::vector<Signature> set;
    for (int i = 0; i < kMaxSignatures; ++i) set = addToSet(set, unit(i));
    set = addToSet(set, unit(0, 0.05f));
    check(int(set.size()) == kMaxSignatures, "a set stays at eight");
    bool spread = true;
    for (size_t i = 0; i < set.size(); ++i)
        for (size_t j = i + 1; j < set.size(); ++j) spread = spread && cosine(set[i], set[j]) < 0.9f;
    check(spread, "and a near copy of one it has does not crowd another out");

    check(sampleTimes(3000) == std::vector<int>{500, 1500, 2500}, "a short video: the middle of each second");
    check(sampleTimes(60000).size() == size_t(kMaxFrames) && sampleTimes(60000).front() == 2500,
          "a long one: twelve, evenly");
    check(sampleTimes(0) == std::vector<int>{0}, "an image: once");

    Sighting s1{{0, 0, 50, 50, 0.9f, {}}, unit(0), 0}, s2{{0, 0, 50, 50, 0.95f, {}}, unit(0, 0.1f), 1000},
        s3{{0, 0, 50, 50, 0.9f, {}}, unit(7), 2000};
    auto merged = mergeSightings({s1, s2, s3});
    check(merged.size() == 2 && merged[0].sightings == 2 && merged[0].frameMs == 1000,
          "sightings of one person in a meme become one face, shown from its best frame");
}

int main(int argc, char** argv) {
    const bool write = argc > 1 && std::strcmp(argv[1], "--write") == 0;
    const std::string root = FACES_ROOT;
    if (!write) pure();

    std::string error;
    auto models = Models::load(readFile(root + "/models/yunet_2023mar.onnx"),
                               readFile(root + "/models/sface_2021dec_int8.onnx"), &error);
    if (!models) {
        std::printf("FAIL  the models load: %s\n", error.c_str());
        return 1;
    }
    struct Seen {
        std::string name;
        std::vector<Sighting> faces;
    };
    std::vector<Seen> seen;
    for (const char* name : {"crew", "armstrong", "aldrin"}) {
        Ppm p = readPpm(root + "/fixtures/" + name + ".ppm");
        Image image{p.rgb.data(), p.width, p.height, p.width * 3, PixelOrder::RGB};
        seen.push_back({name, models->look(image, 0)});
    }

    if (write) {
        std::printf("{\n  \"comment\": \"The faces pipeline on shared/faces/fixtures, as shared/faces/test writes it. Both apps must find the same boxes (within 1 px) and signatures (cosine >= 0.999).\",\n  \"fixtures\": [\n");
        for (size_t f = 0; f < seen.size(); ++f) {
            std::printf("    {\"file\": \"fixtures/%s.ppm\", \"faces\": [\n", seen[f].name.c_str());
            for (size_t i = 0; i < seen[f].faces.size(); ++i) {
                const auto& d = seen[f].faces[i].detection;
                std::printf("      {\"box\": [%.2f, %.2f, %.2f, %.2f], \"score\": %.4f, \"signature\": [", d.x, d.y, d.w,
                            d.h, d.score);
                for (int k = 0; k < kSignatureSize; ++k)
                    std::printf("%s%.6f", k ? ", " : "", seen[f].faces[i].signature[k]);
                std::printf("]}%s\n", i + 1 < seen[f].faces.size() ? "," : "");
            }
            std::printf("    ]}%s\n", f + 1 < seen.size() ? "," : "");
        }
        std::printf("  ]\n}\n");
        return 0;
    }

    std::printf("models\n");
    check(seen[0].faces.size() == 3, "three faces in the crew photo (" + std::to_string(seen[0].faces.size()) + ")");
    check(seen[1].faces.size() == 1 && seen[2].faces.size() == 1, "one in each portrait");
    if (seen[0].faces.size() != 3 || seen[1].faces.size() != 1 || seen[2].faces.size() != 1) return 1;
    const Signature& armstrong = seen[1].faces[0].signature;
    const Signature& aldrin = seen[2].faces[0].signature;
    std::vector<Signature> crew;
    for (const auto& s : seen[0].faces) crew.push_back(s.signature);
    // Left to right in the photo: Armstrong, Collins, Aldrin.
    std::vector<size_t> byX = {0, 1, 2};
    std::sort(byX.begin(), byX.end(), [&](size_t a, size_t b) { return seen[0].faces[a].detection.x < seen[0].faces[b].detection.x; });
    const float sameArmstrong = cosine(armstrong, crew[byX[0]]);
    const float sameAldrin = cosine(aldrin, crew[byX[2]]);
    char line[160];
    std::snprintf(line, sizeof line, "Armstrong is recognised in the crew photo, sure (%.3f)", sameArmstrong);
    check(sameArmstrong >= kSure, line);
    std::snprintf(line, sizeof line, "and so is Aldrin (%.3f)", sameAldrin);
    check(sameAldrin >= kSure, line);
    float worst = -1;
    for (size_t i : {byX[1], byX[2]}) worst = std::max(worst, cosine(armstrong, crew[i]));
    for (size_t i : {byX[0], byX[1]}) worst = std::max(worst, cosine(aldrin, crew[i]));
    worst = std::max(worst, cosine(armstrong, aldrin));
    std::snprintf(line, sizeof line, "and nobody is taken for somebody else (at most %.3f)", worst);
    check(worst < kAsk, line);

    std::printf(failures ? "%d failed\n" : "all faces checks pass\n", failures);
    return failures ? 1 : 0;
}
