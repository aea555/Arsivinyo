// JNI over shared/faces for FacesNative.kt: the same C++ the Mac compiles, so a face
// recognised on one device is recognised on the other.
//
// Results cross as flat float arrays, one fixed-size record per face, which is far cheaper
// than building Java objects here.

#include <jni.h>

#include <cstring>
#include <memory>
#include <string>
#include <vector>

#include "faces.h"

using namespace faces;

namespace {

// Per sighting: x, y, w, h, score, landmarks[10], frameMs, signature[128].
constexpr int kSightingFloats = 5 + 10 + 1 + kSignatureSize;
// Per meme face: x, y, w, h, score, frameMs, sightings, signature[128].
constexpr int kFaceFloats = 7 + kSignatureSize;

Signature signatureAt(const float* values) {
    Signature s{};
    std::memcpy(s.data(), values, sizeof(float) * kSignatureSize);
    return s;
}

std::vector<float> floats(JNIEnv* env, jfloatArray array) {
    std::vector<float> out(size_t(env->GetArrayLength(array)));
    env->GetFloatArrayRegion(array, 0, jsize(out.size()), out.data());
    return out;
}

jfloatArray toJava(JNIEnv* env, const std::vector<float>& values) {
    jfloatArray out = env->NewFloatArray(jsize(values.size()));
    env->SetFloatArrayRegion(out, 0, jsize(values.size()), values.data());
    return out;
}

std::vector<Signature> signatures(JNIEnv* env, jfloatArray flat) {
    const auto all = floats(env, flat);
    std::vector<Signature> out;
    for (size_t i = 0; i + kSignatureSize <= all.size(); i += kSignatureSize) out.push_back(signatureAt(&all[i]));
    return out;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_expo_modules_localdownloader_memes_FacesNative_nativeLoad(
    JNIEnv* env, jobject, jbyteArray detector, jbyteArray recogniser) {
    auto bytes = [&](jbyteArray array) {
        std::vector<uint8_t> out(size_t(env->GetArrayLength(array)));
        env->GetByteArrayRegion(array, 0, jsize(out.size()), reinterpret_cast<jbyte*>(out.data()));
        return out;
    };
    std::string error;
    auto models = Models::load(bytes(detector), bytes(recogniser), &error);
    if (!models) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), ("FACES_LOAD_FAILED: " + error).c_str());
        return 0;
    }
    return reinterpret_cast<jlong>(models.release());
}

JNIEXPORT void JNICALL Java_expo_modules_localdownloader_memes_FacesNative_nativeFree(JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<Models*>(handle);
}

/** A Bitmap's ARGB_8888 pixels, as getPixels returns them: little endian, so B, G, R, A. */
JNIEXPORT jfloatArray JNICALL Java_expo_modules_localdownloader_memes_FacesNative_nativeLook(
    JNIEnv* env, jobject, jlong handle, jintArray argb, jint width, jint height, jint frameMs) {
    auto* models = reinterpret_cast<Models*>(handle);
    if (!models) return env->NewFloatArray(0);
    std::vector<jint> pixels(size_t(env->GetArrayLength(argb)));
    env->GetIntArrayRegion(argb, 0, jsize(pixels.size()), pixels.data());
    Image image{reinterpret_cast<const uint8_t*>(pixels.data()), width, height, width * 4, PixelOrder::BGRA};
    const auto seen = models->look(image, frameMs);
    std::vector<float> out;
    for (const auto& s : seen) {
        const auto& d = s.detection;
        out.insert(out.end(), {d.x, d.y, d.w, d.h, d.score});
        out.insert(out.end(), d.landmarks.begin(), d.landmarks.end());
        out.push_back(float(s.frameMs));
        out.insert(out.end(), s.signature.begin(), s.signature.end());
    }
    return toJava(env, out);
}

JNIEXPORT jfloatArray JNICALL Java_expo_modules_localdownloader_memes_FacesNative_nativeMerge(
    JNIEnv* env, jobject, jfloatArray sightings) {
    const auto flat = floats(env, sightings);
    std::vector<Sighting> in;
    for (size_t i = 0; i + kSightingFloats <= flat.size(); i += kSightingFloats) {
        Sighting s;
        s.detection.x = flat[i];
        s.detection.y = flat[i + 1];
        s.detection.w = flat[i + 2];
        s.detection.h = flat[i + 3];
        s.detection.score = flat[i + 4];
        std::memcpy(s.detection.landmarks.data(), &flat[i + 5], sizeof(float) * 10);
        s.frameMs = int(flat[i + 15]);
        s.signature = signatureAt(&flat[i + 16]);
        in.push_back(s);
    }
    std::vector<float> out;
    for (const auto& f : mergeSightings(in)) {
        out.insert(out.end(), {f.detection.x, f.detection.y, f.detection.w, f.detection.h, f.detection.score,
                               float(f.frameMs), float(f.sightings)});
        out.insert(out.end(), f.signature.begin(), f.signature.end());
    }
    return toJava(env, out);
}

JNIEXPORT jfloat JNICALL Java_expo_modules_localdownloader_memes_FacesNative_nativeBest(
    JNIEnv* env, jobject, jfloatArray signature, jfloatArray set) {
    const auto s = floats(env, signature);
    if (s.size() != size_t(kSignatureSize)) return -1;
    return bestMatch(signatureAt(s.data()), signatures(env, set));
}

JNIEXPORT jfloatArray JNICALL Java_expo_modules_localdownloader_memes_FacesNative_nativeAddToSet(
    JNIEnv* env, jobject, jfloatArray set, jfloatArray signature) {
    const auto s = floats(env, signature);
    auto all = addToSet(signatures(env, set), signatureAt(s.data()));
    std::vector<float> out;
    for (const auto& one : all) out.insert(out.end(), one.begin(), one.end());
    return toJava(env, out);
}

JNIEXPORT jintArray JNICALL Java_expo_modules_localdownloader_memes_FacesNative_nativeGroup(
    JNIEnv* env, jobject, jfloatArray flat, jfloat threshold) {
    const auto labels = group(signatures(env, flat), threshold);
    std::vector<jint> out(labels.begin(), labels.end());
    jintArray result = env->NewIntArray(jsize(out.size()));
    env->SetIntArrayRegion(result, 0, jsize(out.size()), out.data());
    return result;
}

JNIEXPORT jbyteArray JNICALL Java_expo_modules_localdownloader_memes_FacesNative_nativeEncode(
    JNIEnv* env, jobject, jfloatArray signature) {
    const auto s = floats(env, signature);
    const auto bytes = encode(signatureAt(s.data()));
    jbyteArray out = env->NewByteArray(jsize(bytes.size()));
    env->SetByteArrayRegion(out, 0, jsize(bytes.size()), reinterpret_cast<const jbyte*>(bytes.data()));
    return out;
}

JNIEXPORT jfloatArray JNICALL Java_expo_modules_localdownloader_memes_FacesNative_nativeDecode(
    JNIEnv* env, jobject, jbyteArray bytes) {
    std::vector<uint8_t> in(size_t(env->GetArrayLength(bytes)));
    env->GetByteArrayRegion(bytes, 0, jsize(in.size()), reinterpret_cast<jbyte*>(in.data()));
    Signature s{};
    if (!decode(in.data(), in.size(), &s)) return nullptr;
    return toJava(env, std::vector<float>(s.begin(), s.end()));
}

JNIEXPORT jintArray JNICALL Java_expo_modules_localdownloader_memes_FacesNative_nativeSampleTimes(
    JNIEnv* env, jobject, jint durationMs) {
    const auto times = sampleTimes(durationMs);
    std::vector<jint> out(times.begin(), times.end());
    jintArray result = env->NewIntArray(jsize(out.size()));
    env->SetIntArrayRegion(result, 0, jsize(out.size()), out.data());
    return result;
}

JNIEXPORT jfloat JNICALL Java_expo_modules_localdownloader_memes_FacesNative_nativeSure(JNIEnv*, jobject) { return kSure; }
JNIEXPORT jfloat JNICALL Java_expo_modules_localdownloader_memes_FacesNative_nativeAsk(JNIEnv*, jobject) { return kAsk; }
JNIEXPORT jint JNICALL Java_expo_modules_localdownloader_memes_FacesNative_nativeVersion(JNIEnv*, jobject) {
    return kPipelineVersion;
}

}  // extern "C"
