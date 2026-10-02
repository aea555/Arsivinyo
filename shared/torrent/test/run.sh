#!/usr/bin/env bash
# Build the torrent engine for the Mac and run its loopback test.
set -euo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd)"
"$HERE/build.sh" mac
OUT="$HERE/build/mac"
MPVKIT="$HERE/../../mac/Vendor/MPVKit"
SLICE=macos-arm64_x86_64
mkdir -p "$HERE/build/test"
DEFINES=$(sed 's/^/-D/' "$OUT/defines.txt" | tr '\n' ' ')
# shellcheck disable=SC2086
clang++ -std=c++17 -O1 -g -I"$OUT/include" $DEFINES \
  "$HERE/torrent.cpp" "$HERE/test/test_torrent.cpp" "$OUT/lib/libtorrent-rasterbar.a" \
  "$MPVKIT/Libssl.xcframework/$SLICE/Libssl.framework/Libssl" \
  "$MPVKIT/Libcrypto.xcframework/$SLICE/Libcrypto.framework/Libcrypto" \
  -framework SystemConfiguration -framework CoreFoundation \
  -o "$HERE/build/test/test_torrent"
"$HERE/build/test/test_torrent" "${1:-$HERE/build/test/run}" ${2:+"$2"}
