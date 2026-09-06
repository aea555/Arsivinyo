#!/usr/bin/env bash
#
# Build and run the host-side tests for the audio-preset DSP core.
#
# The DSP is deliberately free of any Android dependency so it can be verified with a
# plain compiler here, without a device build. Pass --sanitize to additionally run an
# ASan/UBSan build, which is what catches the out-of-bounds class of bug in the
# resampler's block-boundary handling.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CPP_DIR="$REPO_ROOT/../shared/dsp"
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

CORE_SOURCES=(
  "$CPP_DIR/preset_params.cpp"
  "$CPP_DIR/dsp/freeverb.cpp"
  "$CPP_DIR/dsp/resampler.cpp"
  "$CPP_DIR/dsp/limiter.cpp"
  "$CPP_DIR/dsp/preset_chain.cpp"
)

# DSP-only unit tests: pure signal processing, no external tools needed.
SOURCES=("$CPP_DIR/test/test_dsp.cpp" "${CORE_SOURCES[@]}")

# Integration tests: drive the real render through fork/exec'd ffmpeg processes.
# Skipped by the test itself if ffmpeg/ffprobe are not on PATH.
RENDER_SOURCES=("$CPP_DIR/test/test_render.cpp" "$CPP_DIR/ffmpeg_pipe.cpp" "${CORE_SOURCES[@]}")

if command -v g++ >/dev/null 2>&1; then
  CXX="${CXX:-g++}"
elif command -v clang++ >/dev/null 2>&1; then
  CXX="${CXX:-clang++}"
else
  echo "error: no C++ compiler found (need g++ or clang++)" >&2
  exit 1
fi

echo "==> building DSP tests with $CXX"
"$CXX" -std=c++17 -O2 -Wall -Wextra -Werror "${SOURCES[@]}" -o "$BUILD_DIR/test_dsp"

echo "==> running DSP tests"
"$BUILD_DIR/test_dsp"

echo "==> building render tests with $CXX"
"$CXX" -std=c++17 -O2 -Wall -Wextra -Werror "${RENDER_SOURCES[@]}" -o "$BUILD_DIR/test_render"

echo "==> running render tests"
"$BUILD_DIR/test_render"

if [[ "${1:-}" == "--sanitize" ]]; then
  # GCC ships the sanitizer headers without the runtime on some distros, so prefer
  # clang here and skip rather than fail if neither can actually link.
  SAN_CXX=""
  if command -v clang++ >/dev/null 2>&1; then
    SAN_CXX="clang++"
  elif command -v g++ >/dev/null 2>&1; then
    SAN_CXX="g++"
  fi

  if [[ -z "$SAN_CXX" ]]; then
    echo "==> skipping sanitizer run (no suitable compiler)"
    exit 0
  fi

  echo "==> building with $SAN_CXX (address, undefined)"
  if ! "$SAN_CXX" -std=c++17 -O1 -g -D_GLIBCXX_ASSERTIONS \
      -fsanitize=address,undefined -fno-sanitize-recover=all -fno-omit-frame-pointer \
      "${SOURCES[@]}" -o "$BUILD_DIR/test_dsp_san" 2>"$BUILD_DIR/san.log"; then
    echo "==> skipping sanitizer run (runtime unavailable):"
    sed 's/^/    /' "$BUILD_DIR/san.log" >&2
    exit 0
  fi

  echo "==> running sanitized"
  "$BUILD_DIR/test_dsp_san"
fi

echo "==> ok"
