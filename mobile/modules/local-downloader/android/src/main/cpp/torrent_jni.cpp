// JNI over shared/torrent for TorrentNative.kt: the same engine the Mac compiles.
// Strings cross as UTF-8; JSON answers stay JSON, read in Kotlin.

#include <jni.h>

#include <string>
#include <vector>

#include "torrent.h"

namespace {

at_session* session(jlong handle) { return reinterpret_cast<at_session*>(handle); }

std::string text(JNIEnv* env, jstring value) {
    if (!value) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    std::string out = chars ? chars : "";
    if (chars) env->ReleaseStringUTFChars(value, chars);
    return out;
}

jstring owned(JNIEnv* env, char* value) {
    if (!value) return nullptr;
    jstring out = env->NewStringUTF(value);
    at_free(value);
    return out;
}

at_settings settings(const std::string& stateDir, jint port, jdouble ratio, jboolean upload, jboolean discovery,
                     jint uploadLimit, jint downloadLimit) {
    return at_settings{stateDir.c_str(), port, ratio, upload ? 1 : 0, discovery ? 1 : 0, uploadLimit, downloadLimit};
}

}  // namespace

#define FN(name) JNIEXPORT JNICALL Java_expo_modules_localdownloader_torrent_TorrentNative_##name

extern "C" {

jlong FN(nativeCreate)(JNIEnv* env, jobject, jstring stateDir, jint port, jdouble ratio, jboolean upload,
                       jboolean discovery, jint uploadLimit, jint downloadLimit) {
    std::string const dir = text(env, stateDir);
    auto s = settings(dir, port, ratio, upload, discovery, uploadLimit, downloadLimit);
    char error[256] = {};
    return reinterpret_cast<jlong>(at_session_create(&s, error));
}

void FN(nativeDestroy)(JNIEnv*, jobject, jlong handle) { at_session_destroy(session(handle)); }

void FN(nativeApply)(JNIEnv* env, jobject, jlong handle, jstring stateDir, jint port, jdouble ratio, jboolean upload,
                     jboolean discovery, jint uploadLimit, jint downloadLimit) {
    std::string const dir = text(env, stateDir);
    auto s = settings(dir, port, ratio, upload, discovery, uploadLimit, downloadLimit);
    at_session_apply(session(handle), &s);
}

jint FN(nativePort)(JNIEnv*, jobject, jlong handle) { return at_session_port(session(handle)); }

jstring FN(nativeAddMagnet)(JNIEnv* env, jobject, jlong handle, jstring magnet, jstring savePath, jboolean paused) {
    char id[65] = {};
    int const code = at_add_magnet(session(handle), text(env, magnet).c_str(), text(env, savePath).c_str(), paused, id);
    return code == AT_OK ? env->NewStringUTF(id) : nullptr;
}

jstring FN(nativeAddTorrent)(JNIEnv* env, jobject, jlong handle, jbyteArray data, jstring savePath, jboolean paused) {
    std::vector<uint8_t> bytes(size_t(env->GetArrayLength(data)));
    env->GetByteArrayRegion(data, 0, jsize(bytes.size()), reinterpret_cast<jbyte*>(bytes.data()));
    char id[65] = {};
    int const code = at_add_torrent(session(handle), bytes.data(), bytes.size(), text(env, savePath).c_str(), paused, id);
    return code == AT_OK ? env->NewStringUTF(id) : nullptr;
}

jint FN(nativeRemove)(JNIEnv* env, jobject, jlong handle, jstring id, jboolean deleteFiles) {
    return at_remove(session(handle), text(env, id).c_str(), deleteFiles);
}

jint FN(nativePause)(JNIEnv* env, jobject, jlong handle, jstring id) { return at_pause(session(handle), text(env, id).c_str()); }

jint FN(nativeHold)(JNIEnv* env, jobject, jlong handle, jstring id, jboolean on) {
    return at_hold(session(handle), text(env, id).c_str(), on ? 1 : 0);
}

jint FN(nativeResume)(JNIEnv* env, jobject, jlong handle, jstring id) { return at_resume(session(handle), text(env, id).c_str()); }

jint FN(nativeConnectPeer)(JNIEnv* env, jobject, jlong handle, jstring id, jstring ip, jint port) {
    return at_connect_peer(session(handle), text(env, id).c_str(), text(env, ip).c_str(), port);
}

jstring FN(nativeFiles)(JNIEnv* env, jobject, jlong handle, jstring id) {
    return owned(env, at_files(session(handle), text(env, id).c_str()));
}

jstring FN(nativeFileProgress)(JNIEnv* env, jobject, jlong handle, jstring id) {
    return owned(env, at_file_progress(session(handle), text(env, id).c_str()));
}

jint FN(nativeSetPriorities)(JNIEnv* env, jobject, jlong handle, jstring id, jbyteArray priorities) {
    std::vector<uint8_t> values(size_t(env->GetArrayLength(priorities)));
    env->GetByteArrayRegion(priorities, 0, jsize(values.size()), reinterpret_cast<jbyte*>(values.data()));
    return at_set_priorities(session(handle), text(env, id).c_str(), values.data(), int(values.size()));
}

jstring FN(nativeStatus)(JNIEnv* env, jobject, jlong handle) { return owned(env, at_status(session(handle))); }

jbyteArray FN(nativeTorrentFile)(JNIEnv* env, jobject, jlong handle, jstring id) {
    size_t size = 0;
    uint8_t* bytes = at_torrent_file(session(handle), text(env, id).c_str(), &size);
    if (!bytes) return nullptr;
    jbyteArray out = env->NewByteArray(jsize(size));
    env->SetByteArrayRegion(out, 0, jsize(size), reinterpret_cast<jbyte*>(bytes));
    at_free(bytes);
    return out;
}

jstring FN(nativeFilePath)(JNIEnv* env, jobject, jlong handle, jstring id, jint file) {
    return owned(env, at_file_path(session(handle), text(env, id).c_str(), file));
}

jstring FN(nativeStream)(JNIEnv* env, jobject, jlong handle, jstring magnet, jstring cacheDir, jint file, jstring nameHint,
                         jint timeoutMs, jintArray code) {
    int result = 0;
    std::string const hint = text(env, nameHint);
    jstring out = owned(env, at_stream(session(handle), text(env, magnet).c_str(), text(env, cacheDir).c_str(), file,
                                       hint.empty() ? nullptr : hint.c_str(), timeoutMs, &result));
    jint value = result;
    if (code && env->GetArrayLength(code) > 0) env->SetIntArrayRegion(code, 0, 1, &value);
    return out;
}

jint FN(nativeCacheTrim)(JNIEnv* env, jobject, jlong handle, jstring cacheDir, jlong limitBytes, jstring keepId) {
    std::string const keep = text(env, keepId);
    return at_cache_trim(session(handle), text(env, cacheDir).c_str(), limitBytes, keep.empty() ? nullptr : keep.c_str());
}

jstring FN(nativeServerStart)(JNIEnv* env, jobject, jlong handle) { return owned(env, at_server_start(session(handle))); }

}  // extern "C"
