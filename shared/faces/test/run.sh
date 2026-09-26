#!/usr/bin/env bash
# Builds and runs the faces host test against the pinned macOS ONNX Runtime.
#
#   shared/faces/test/run.sh            the checks
#   shared/faces/test/run.sh --write    print VECTORS.json
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
RUNTIME="$("$ROOT/fetch-runtime.sh")"
OUT="${TMPDIR:-/tmp}/arsivinyo-faces-test"
clang++ -std=c++17 -O2 -Wall -Wextra -DFACES_ROOT="\"$ROOT\"" \
    -I"$ROOT" -I"$RUNTIME/include" \
    "$ROOT/faces.cpp" "$ROOT/runtime.cpp" "$ROOT/test/test_faces.cpp" \
    -L"$RUNTIME/lib" -lonnxruntime -Wl,-rpath,"$RUNTIME/lib" -o "$OUT"
"$OUT" "$@"
