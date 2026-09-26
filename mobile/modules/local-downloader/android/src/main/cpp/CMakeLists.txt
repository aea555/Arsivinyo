# Native audio-preset renderer.
#
# Builds libaudiopresets.so: the DSP core plus the ffmpeg pipe plumbing and a small
# JNI surface. Deliberately does NOT link FFmpeg — the bundled ffmpeg/ffprobe are
# executed as separate processes for container and codec work only, so this library
# stays small and owns nothing but the signal processing.
#
# The same sources build on the host with scripts/test-audio-presets.sh, which is
# where the DSP and render behaviour are actually verified.

cmake_minimum_required(VERSION 3.22.1)
project(audiopresets LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# The signal processing lives in shared/dsp because the desktop app renders the same
# presets from the same code. Only the JNI bridge below is Android's.
set(ARSIVINYO_DSP ${CMAKE_CURRENT_SOURCE_DIR}/../../../../../../../shared/dsp)

add_library(audiopresets SHARED
    audio_presets_jni.cpp
    ${ARSIVINYO_DSP}/ffmpeg_pipe.cpp
    ${ARSIVINYO_DSP}/preset_params.cpp
    ${ARSIVINYO_DSP}/dsp/freeverb.cpp
    ${ARSIVINYO_DSP}/dsp/resampler.cpp
    ${ARSIVINYO_DSP}/dsp/limiter.cpp
    ${ARSIVINYO_DSP}/dsp/preset_chain.cpp
)

target_include_directories(audiopresets PRIVATE ${CMAKE_CURRENT_SOURCE_DIR} ${ARSIVINYO_DSP})

target_compile_options(audiopresets PRIVATE
    -Wall
    -Wextra
    -ffast-math
    -fvisibility=hidden
)

# Only the JNI entry points are exported; everything else stays internal.
set_target_properties(audiopresets PROPERTIES
    CXX_VISIBILITY_PRESET hidden
    VISIBILITY_INLINES_HIDDEN ON
)

# log is for __android_log_* if this ever needs to trace; the JNI headers come from
# the NDK toolchain automatically.
find_library(log-lib log)
target_link_libraries(audiopresets ${log-lib})

# Faces in memes: shared/faces, the same C++ the Mac compiles, over ONNX Runtime. build.gradle
# unpacks the runtime's headers and library from its AAR into ONNXRUNTIME_DIR, and packages
# that library itself; this only links against it.
#
# No -ffast-math here, unlike the DSP: the phone and the Mac must reach the same signatures
# from the same frame, and reassociation is what would let them drift.
set(ARSIVINYO_FACES ${CMAKE_CURRENT_SOURCE_DIR}/../../../../../../../shared/faces)

add_library(onnxruntime SHARED IMPORTED)
set_target_properties(onnxruntime PROPERTIES
    IMPORTED_LOCATION ${ONNXRUNTIME_DIR}/jni/${ANDROID_ABI}/libonnxruntime.so
)

add_library(faces SHARED
    faces_jni.cpp
    ${ARSIVINYO_FACES}/faces.cpp
    ${ARSIVINYO_FACES}/runtime.cpp
)
target_include_directories(faces PRIVATE ${ARSIVINYO_FACES} ${ONNXRUNTIME_DIR}/headers)
target_compile_options(faces PRIVATE -Wall -Wextra)
set_target_properties(faces PROPERTIES
    CXX_VISIBILITY_PRESET hidden
    VISIBILITY_INLINES_HIDDEN ON
)
target_link_libraries(faces onnxruntime ${log-lib})
