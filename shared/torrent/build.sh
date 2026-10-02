#!/usr/bin/env bash
# Build libtorrent from the pinned sources (VERSIONS.json) as a static library, with the same
# options for both apps, into build/<platform>/. A no-op when it is already built.
#
#   shared/torrent/build.sh mac       arm64, against the Mac app's OpenSSL (MPVKit's)
#   shared/torrent/build.sh android   arm64-v8a, OpenSSL built here from the pinned source
#
# CMake and Ninja are the Android SDK's, which build for the Mac as well.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
PLATFORM="${1:?mac or android}"
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
CMAKE_BIN="$SDK/cmake/3.22.1/bin"
OUT="$HERE/build/$PLATFORM"
[ -f "$OUT/defines.txt" ] && exit 0

"$HERE/fetch.sh"
LT="$HERE/sources/libtorrent-rasterbar-2.0.15"
BOOST="$HERE/sources/boost_1_88_0"

# The same everywhere: static, no deprecated API, encryption (https trackers, peer encryption).
COMMON=(
  -G Ninja -DCMAKE_MAKE_PROGRAM="$CMAKE_BIN/ninja"
  -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=OFF -DCMAKE_CXX_STANDARD=17
  -Ddeprecated-functions=OFF -Dencryption=ON -Ddht=ON -Di2p=OFF -Dlogging=OFF
  -DBOOST_ROOT="$BOOST" -DBoost_NO_SYSTEM_PATHS=ON -DBoost_INCLUDE_DIR="$BOOST"
  -DCMAKE_INSTALL_PREFIX="$OUT" -DCMAKE_POSITION_INDEPENDENT_CODE=ON
)

case "$PLATFORM" in
  mac)
    MPVKIT="$HERE/../../mac/Vendor/MPVKit"
    [ -d "$MPVKIT/Libcrypto.xcframework" ] || "$HERE/../../mac/scripts/fetch-mpvkit.sh"
    SLICE=macos-arm64_x86_64
    SSL_HEADERS="$MPVKIT/Libcrypto.xcframework/$SLICE/Libcrypto.framework/Headers"
    EXTRA=(
      -DCMAKE_OSX_ARCHITECTURES=arm64 -DCMAKE_OSX_DEPLOYMENT_TARGET=12.0
      -DOPENSSL_INCLUDE_DIR="$MPVKIT/Libcrypto.xcframework/$SLICE/Libcrypto.framework/Headers"
      -DOPENSSL_CRYPTO_LIBRARY="$MPVKIT/Libcrypto.xcframework/$SLICE/Libcrypto.framework/Libcrypto"
      -DOPENSSL_SSL_LIBRARY="$MPVKIT/Libssl.xcframework/$SLICE/Libssl.framework/Libssl"
    )
    ;;
  android)
    NDK="$SDK/ndk/27.1.12297006"
    API=26
    SSL="$OUT/openssl"
    if [ ! -f "$SSL/lib/libcrypto.a" ]; then
      rm -rf "$HERE/build/openssl-src" && cp -R "$HERE/sources/openssl-3.3.5" "$HERE/build/openssl-src"
      (
        cd "$HERE/build/openssl-src"
        export ANDROID_NDK_ROOT="$NDK"
        export PATH="$NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin:$PATH"
        ./Configure android-arm64 -D__ANDROID_API__=$API no-shared no-tests no-docs no-apps \
          --prefix="$SSL" --libdir=lib >/dev/null
        make -j"$(sysctl -n hw.ncpu)" build_libs >/dev/null
        make install_dev >/dev/null
      )
    fi
    SSL_HEADERS="$SSL/include"
    EXTRA=(
      -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake"
      -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-$API -DANDROID_STL=c++_shared
      -DOPENSSL_ROOT_DIR="$SSL" -DOPENSSL_USE_STATIC_LIBS=ON
      -DOPENSSL_INCLUDE_DIR="$SSL/include"
      -DOPENSSL_CRYPTO_LIBRARY="$SSL/lib/libcrypto.a" -DOPENSSL_SSL_LIBRARY="$SSL/lib/libssl.a"
    )
    ;;
  *) echo "mac or android" >&2; exit 2 ;;
esac

mkdir -p "$HERE/build"
WORK="$HERE/build/$PLATFORM-work"
rm -rf "$WORK"
"$CMAKE_BIN/cmake" -S "$LT" -B "$WORK" "${COMMON[@]}" "${EXTRA[@]}" >"$HERE/build/$PLATFORM-configure.log"
"$CMAKE_BIN/cmake" --build "$WORK" >"$HERE/build/$PLATFORM-build.log"
"$CMAKE_BIN/cmake" --install "$WORK" >/dev/null
# The defines libtorrent was built with, which everything compiling against it must share:
# a mismatch changes the size of its classes. One per line, from its own CMake export.
sed -n 's/.*INTERFACE_COMPILE_DEFINITIONS "\(.*\)"/\1/p' "$OUT/lib/cmake/LibtorrentRasterbar/LibtorrentRasterbarTargets.cmake" \
  | tr ';' '\n' | grep -v '^\\\$<' > "$OUT/defines.txt"
# Boost's and OpenSSL's headers travel with the library: libtorrent's own headers include them.
mkdir -p "$OUT/include" && ln -sfn "$BOOST/boost" "$OUT/include/boost" && ln -sfn "$SSL_HEADERS/openssl" "$OUT/include/openssl"
echo "built libtorrent for $PLATFORM in $OUT"
