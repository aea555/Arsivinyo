// Faces in memes: `shared/memes/CONTRACT.md`, "Faces (phase 2)".
//
// One pipeline for both apps: YuNet finds faces and five landmarks, the landmarks align
// each face onto the 112x112 ArcFace template, and SFace turns it into 128 numbers. The
// math is here, in plain C++ with no platform in it, so the phone and the Mac produce the
// same signatures from the same frame; `VECTORS.json` holds them to it.
//
// The models run through ONNX Runtime (runtime.cpp). Everything else — decoding YuNet's
// output, alignment, resizing, the signature sets — is in faces.cpp and needs
// nothing but the standard library, so it is tested on the host.

#pragma once

#include <array>
#include <cstdint>
#include <memory>
#include <string>
#include <vector>

namespace faces {

// ---- constants the contract names --------------------------------------------------------

constexpr int kSignatureSize = 128;
constexpr int kAlignedSize = 112;
constexpr int kDetectorSize = 640;

/** At or above: the same person, labelled on its own. */
constexpr float kSure = 0.50f;
/** At or above, below sure: asked about. */
constexpr float kAsk = 0.36f;

constexpr float kMinScore = 0.8f;
constexpr float kNmsIou = 0.3f;
/** A face smaller than this on its short side, in source pixels, is not looked at. */
constexpr float kMinFacePixels = 40.0f;

/** A person keeps at most this many signatures. */
constexpr int kMaxSignatures = 8;

/** A video is looked at once a second, at most this many times. */
constexpr int kMaxFrames = 12;

/** Bumped when anything here changes what a scan produces, so memes are scanned again. */
constexpr int kPipelineVersion = 1;

using Signature = std::array<float, kSignatureSize>;

// ---- images ------------------------------------------------------------------------------

enum class PixelOrder { RGBA, BGRA, RGB };

/** A frame as the platform decoded it. Not owned. */
struct Image {
    const uint8_t* pixels = nullptr;
    int width = 0;
    int height = 0;
    /** Bytes per row. */
    int stride = 0;
    PixelOrder order = PixelOrder::RGBA;
};

/** A planar float image, as the models take it: channel, row, column. */
struct Planar {
    int width = 0;
    int height = 0;
    std::vector<float> data;  // 3 * width * height
};

// ---- detection ---------------------------------------------------------------------------

struct Detection {
    float x = 0, y = 0, w = 0, h = 0;
    float score = 0;
    /** Right eye, left eye, nose, right mouth corner, left mouth corner: x, y each. */
    std::array<float, 10> landmarks{};
};

/**
 * The detector's input: the frame scaled to fit 640x640 without changing its shape, padded
 * at the right and bottom, as BGR 0..255. [scale] is what source coordinates were
 * multiplied by.
 */
Planar detectorInput(const Image& image, float* scale);

/** One stride's worth of YuNet output. */
struct YuNetLevel {
    int stride = 0;
    const float* cls = nullptr;   // rows*cols
    const float* obj = nullptr;   // rows*cols
    const float* bbox = nullptr;  // rows*cols*4
    const float* kps = nullptr;   // rows*cols*10
};

/**
 * YuNet's raw output to detections in source pixels: decoded, thresholded, merged where
 * they overlap, and the ones too small to trust dropped.
 */
std::vector<Detection> decodeYuNet(const std::vector<YuNetLevel>& levels, float scale);

/** Greedy non-maximum suppression, highest score first. */
std::vector<Detection> suppress(std::vector<Detection> detections, float iou);

// ---- alignment ---------------------------------------------------------------------------

/**
 * The similarity transform (rotation, uniform scale, translation, no reflection) taking the
 * five landmarks onto the ArcFace template, least squares (Umeyama). Row-major 2x3.
 */
std::array<float, 6> alignment(const std::array<float, 10>& landmarks);

/** The recogniser's input: the face warped to 112x112, bilinear, as RGB 0..255. */
Planar recogniserInput(const Image& image, const Detection& face);

// ---- signatures --------------------------------------------------------------------------

/** Scaled to length one. A zero vector stays zero. */
Signature normalised(const float* values);

/** Of two normalised signatures. */
float cosine(const Signature& a, const Signature& b);

/** The highest cosine against any of a set. -1 for an empty set. */
float bestMatch(const Signature& face, const std::vector<Signature>& set);

/** 128 half floats, little endian, as they are stored and sent. */
std::vector<uint8_t> encode(const Signature& signature);
bool decode(const uint8_t* bytes, size_t size, Signature* out);

/**
 * A person's set with one more signature in it, at most kMaxSignatures, as spread out as
 * possible: when full, the one most like the rest gives way, unless the new one is itself
 * the most like the rest.
 */
std::vector<Signature> addToSet(std::vector<Signature> set, const Signature& signature);

// ---- within a meme, and across the collection --------------------------------------------

struct Sighting {
    Detection detection;
    Signature signature{};
    int frameMs = 0;
};

/** One person in one meme: the sightings of them merged. */
struct MemeFace {
    Signature signature{};
    /** The best sighting, for showing. */
    Detection detection;
    int frameMs = 0;
    int sightings = 0;
};

/**
 * Sightings closer than kSure are the same face: greedy, strongest detection first, each
 * face's signature the normalised mean of its sightings.
 */
std::vector<MemeFace> mergeSightings(const std::vector<Sighting>& sightings);

/** When to look at a video of this length: once a second, at most kMaxFrames, evenly. */
std::vector<int> sampleTimes(int durationMs);

// ---- the models --------------------------------------------------------------------------

/** YuNet and SFace in ONNX Runtime. One per thread; not safe to share while running. */
class Models {
public:
    /** From the model files' bytes. Null, with [error] set, if either will not load. */
    static std::unique_ptr<Models> load(const std::vector<uint8_t>& detector,
                                        const std::vector<uint8_t>& recogniser,
                                        std::string* error);
    virtual ~Models() = default;

    virtual std::vector<Detection> detect(const Image& image) = 0;
    virtual Signature embed(const Image& image, const Detection& face) = 0;

    /** Every face in the frame worth keeping, with its signature. */
    std::vector<Sighting> look(const Image& image, int frameMs);
};

}  // namespace faces
