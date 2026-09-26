#!/usr/bin/env bash
# Fetches the macOS ONNX Runtime that MODELS.json pins into shared/faces/runtime/, for the
# Mac app and the host test. The phone takes the same version from Maven instead.
#
#   shared/faces/fetch-runtime.sh
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
read -r URL SHA < <(python3 -c "import json; m=json.load(open('$HERE/MODELS.json'))['runtime']['macos']; print(m['url'], m['sha256'])")
DEST="$HERE/runtime"
NAME="$(basename "$URL" .tgz)"

if [ -f "$DEST/$NAME/lib/libonnxruntime.dylib" ]; then
    echo "$DEST/$NAME"
    exit 0
fi

mkdir -p "$DEST"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
curl -fsSL -o "$TMP/runtime.tgz" "$URL"
ACTUAL="$(shasum -a 256 "$TMP/runtime.tgz" | cut -d' ' -f1)"
if [ "$ACTUAL" != "$SHA" ]; then
    echo "error: $URL has SHA-256 $ACTUAL, MODELS.json pins $SHA" >&2
    exit 1
fi
tar -xzf "$TMP/runtime.tgz" -C "$DEST"
echo "$DEST/$NAME"
