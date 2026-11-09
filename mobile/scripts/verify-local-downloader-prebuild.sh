#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

BUILD_GRADLE="$ROOT_DIR/android/build.gradle"
APP_GRADLE="$ROOT_DIR/android/app/build.gradle"
GRADLE_PROPERTIES="$ROOT_DIR/android/gradle.properties"
ANDROID_MANIFEST="$ROOT_DIR/android/app/src/main/AndroidManifest.xml"
MERGED_NATIVE_LIBS_DIR="$ROOT_DIR/android/app/build/intermediates/merged_native_libs/debug/mergeDebugNativeLibs/out/lib"
WHEELS_DIR="$ROOT_DIR/modules/local-downloader/android/chaquopy-wheels"
WHEEL_CHECKSUMS="$WHEELS_DIR/SHA256SUMS"
WHEEL_VERSIONS="$WHEELS_DIR/VERSIONS.json"

assert_once() {
  local marker="$1"
  local file="$2"
  local count
  count=$(grep -F "$marker" "$file" | wc -l | tr -d ' ')
  if [[ "$count" != "1" ]]; then
    echo "[verify-local-downloader-prebuild] Expected marker '$marker' exactly once in $file, found $count"
    exit 1
  fi
}

resolve_required_abis() {
  if [[ -n "${ABI_LIST:-}" ]]; then
    echo "$ABI_LIST"
    return
  fi
  if command -v python3 >/dev/null 2>&1 && [[ -f "$WHEEL_VERSIONS" ]]; then
    local parsed
    parsed="$(python3 - <<PY 2>/dev/null
import json
from pathlib import Path
path = Path("$WHEEL_VERSIONS")
try:
    data = json.loads(path.read_text())
except Exception:
    print("")
    raise SystemExit(0)
abis = data.get("abiCoverage") or []
print(" ".join([str(x) for x in abis]))
PY
)"
    if [[ -n "$parsed" ]]; then
      echo "$parsed"
      return
    fi
  fi
  echo "arm64-v8a"
}

ABI_LIST_RAW="$(resolve_required_abis)"
EXPECTED_ARCH_CSV="$(echo "$ABI_LIST_RAW" | tr ' ' ',')"

verify_generated_files() {
  if [[ ! -f "$BUILD_GRADLE" || ! -f "$APP_GRADLE" || ! -f "$GRADLE_PROPERTIES" || ! -f "$ANDROID_MANIFEST" ]]; then
    echo "[verify-local-downloader-prebuild] Android Gradle files were not generated"
    exit 1
  fi

  assert_once "// @generated begin local-downloader-chaquopy-buildscript-repo" "$BUILD_GRADLE"
  assert_once "// @generated begin local-downloader-chaquopy-allprojects-repo" "$BUILD_GRADLE"
  assert_once "// @generated begin local-downloader-chaquopy-buildscript-classpath" "$BUILD_GRADLE"
  assert_once "// @generated begin local-downloader-python-config" "$APP_GRADLE"
  assert_once "// @generated begin local-downloader-impersonation-pip" "$APP_GRADLE"
  assert_once "// @generated begin local-downloader-sourceset-config" "$APP_GRADLE"
  assert_once "// @generated begin local-downloader-abi-filter-config" "$APP_GRADLE"

  if ! grep -Fq 'apply plugin: "com.chaquo.python"' "$APP_GRADLE"; then
    echo "[verify-local-downloader-prebuild] Missing Chaquopy apply plugin in app/build.gradle"
    exit 1
  fi

  if ! grep -Fq 'expo.useLegacyPackaging=true' "$GRADLE_PROPERTIES"; then
    echo "[verify-local-downloader-prebuild] expo.useLegacyPackaging must be true in android/gradle.properties"
    exit 1
  fi
  if ! grep -Fq "reactNativeArchitectures=$EXPECTED_ARCH_CSV" "$GRADLE_PROPERTIES"; then
    echo "[verify-local-downloader-prebuild] reactNativeArchitectures must be $EXPECTED_ARCH_CSV"
    exit 1
  fi

  if ! grep -Fq 'install("curl-cffi==' "$APP_GRADLE"; then
    echo "[verify-local-downloader-prebuild] Missing curl-cffi install line in app/build.gradle"
    exit 1
  fi
  if ! grep -Fq 'abiFilters(*localDownloaderAbis)' "$APP_GRADLE"; then
    echo "[verify-local-downloader-prebuild] Missing dynamic local-downloader abi filter wiring"
    exit 1
  fi

  for perm in \
    'android.permission.FOREGROUND_SERVICE' \
    'android.permission.FOREGROUND_SERVICE_DATA_SYNC' \
    'android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK' \
    'android.permission.POST_NOTIFICATIONS' \
    'android.permission.USE_BIOMETRIC'; do
    if ! grep -Fq "$perm" "$ANDROID_MANIFEST"; then
      echo "[verify-local-downloader-prebuild] Missing required permission in manifest: $perm"
      exit 1
    fi
  done

  if [[ "$(grep -F 'expo.modules.localdownloader.DownloadForegroundService' "$ANDROID_MANIFEST" | wc -l | tr -d ' ')" != "1" ]]; then
    echo "[verify-local-downloader-prebuild] DownloadForegroundService must exist exactly once in AndroidManifest.xml"
    exit 1
  fi
  if [[ "$(grep -F 'expo.modules.localdownloader.DownloadActionReceiver' "$ANDROID_MANIFEST" | wc -l | tr -d ' ')" != "1" ]]; then
    echo "[verify-local-downloader-prebuild] DownloadActionReceiver must exist exactly once in AndroidManifest.xml"
    exit 1
  fi
  if [[ "$(grep -F 'expo.modules.localdownloader.QuickDownloadCaptureActivity' "$ANDROID_MANIFEST" | wc -l | tr -d ' ')" != "1" ]]; then
    echo "[verify-local-downloader-prebuild] QuickDownloadCaptureActivity must exist exactly once in AndroidManifest.xml"
    exit 1
  fi
  if [[ "$(grep -F 'expo.modules.localdownloader.PrivateVaultImportActivity' "$ANDROID_MANIFEST" | wc -l | tr -d ' ')" != "1" ]]; then
    echo "[verify-local-downloader-prebuild] PrivateVaultImportActivity must exist exactly once in AndroidManifest.xml"
    exit 1
  fi
  if [[ "$(grep -F 'expo.modules.localdownloader.SoundsImportActivity' "$ANDROID_MANIFEST" | wc -l | tr -d ' ')" != "1" ]]; then
    echo "[verify-local-downloader-prebuild] SoundsImportActivity must exist exactly once in AndroidManifest.xml"
    exit 1
  fi
  if [[ "$(grep -F 'expo.modules.localdownloader.BackupDocumentActivity' "$ANDROID_MANIFEST" | wc -l | tr -d ' ')" != "1" ]]; then
    echo "[verify-local-downloader-prebuild] BackupDocumentActivity must exist exactly once in AndroidManifest.xml"
    exit 1
  fi
  # A noHistory picker is finished the instant the document UI covers it, which cancels the
  # pick before the user chooses anything. Guard the whole activity block, not just one line.
  if awk '/BackupDocumentActivity/,/\/>/' "$ANDROID_MANIFEST" | grep -Fq 'android:noHistory'; then
    echo "[verify-local-downloader-prebuild] BackupDocumentActivity must not set android:noHistory"
    exit 1
  fi
  if grep -Fq 'expo.modules.localdownloader.PrivateVideoPlayerActivity' "$ANDROID_MANIFEST"; then
    echo "[verify-local-downloader-prebuild] PrivateVideoPlayerActivity must not exist in AndroidManifest.xml"
    exit 1
  fi
  if ! grep -Fq 'android:foregroundServiceType="dataSync"' "$ANDROID_MANIFEST"; then
    echo "[verify-local-downloader-prebuild] Foreground service type dataSync is required"
    exit 1
  fi
  # Without it a headless task is never over, and React Native's timers run behind the app
  # for good (HeadlessTaskSupport.kt).
  assert_once 'add(expo.modules.localdownloader.HeadlessTaskSupportPackage())' "$ROOT_DIR/android/app/src/main/java/com/arsivinyo/local/MainApplication.kt"
  # The Watch player out of sight and in picture-in-picture (watch/Playback.kt).
  if [[ "$(grep -F 'expo.modules.localdownloader.watch.PlaybackService' "$ANDROID_MANIFEST" | wc -l | tr -d ' ')" != "1" ]]; then
    echo "[verify-local-downloader-prebuild] PlaybackService must exist exactly once in AndroidManifest.xml"
    exit 1
  fi
  if ! awk '/watch.PlaybackService/,/\/>/' "$ANDROID_MANIFEST" | grep -Fq 'android:foregroundServiceType="mediaPlayback"'; then
    echo "[verify-local-downloader-prebuild] PlaybackService must be a mediaPlayback foreground service"
    exit 1
  fi
  # Without smallestScreenSize the activity is made again as it shrinks, and the video with it.
  if ! awk '/\.MainActivity/,/>/' "$ANDROID_MANIFEST" | grep -F 'android:supportsPictureInPicture="true"' | grep -Fq 'smallestScreenSize'; then
    echo "[verify-local-downloader-prebuild] MainActivity must support picture-in-picture and handle smallestScreenSize itself"
    exit 1
  fi

  for perm in \
    'android.permission.ACCESS_MEDIA_LOCATION' \
    'android.permission.READ_EXTERNAL_STORAGE' \
    'android.permission.WRITE_EXTERNAL_STORAGE' \
    'android.permission.READ_MEDIA_AUDIO' \
    'android.permission.READ_MEDIA_IMAGES' \
    'android.permission.READ_MEDIA_VIDEO' \
    'android.permission.READ_MEDIA_VISUAL_USER_SELECTED'; do
    local matched
    matched="$(grep -F "$perm" "$ANDROID_MANIFEST" || true)"
    if [[ -n "$matched" ]] && [[ "$matched" != *'tools:node="remove"'* ]]; then
      echo "[verify-local-downloader-prebuild] Legacy/broad media permission must be blocked with tools:node=remove: $perm"
      exit 1
    fi
  done

  if grep -Fq 'android:requestLegacyExternalStorage="true"' "$ANDROID_MANIFEST"; then
    echo "[verify-local-downloader-prebuild] requestLegacyExternalStorage=true must not be present"
    exit 1
  fi
}

verify_impersonation_wheels() {
  if [[ ! -d "$WHEELS_DIR" ]]; then
    echo "[verify-local-downloader-prebuild] Missing wheels directory: $WHEELS_DIR"
    exit 1
  fi
  if [[ ! -f "$WHEEL_CHECKSUMS" ]]; then
    echo "[verify-local-downloader-prebuild] Missing wheels checksum file: $WHEEL_CHECKSUMS"
    exit 1
  fi
  bash "$ROOT_DIR/scripts/verify-impersonation-wheels.sh"
}

verify_merged_native_libs() {
  local gradle_log="/tmp/local-downloader-gradle-merge.log"
  (
    cd "$ROOT_DIR/android"
    ./gradlew :app:mergeDebugNativeLibs -x lint -x test --configure-on-demand >"$gradle_log" 2>&1
  ) || {
    cat "$gradle_log"
    exit 1
  }

  local abi_list_raw
  abi_list_raw="$ABI_LIST_RAW"
  local required_abis=()
  read -r -a required_abis <<< "$abi_list_raw"
  for abi in "${required_abis[@]}"; do
    local ffmpeg_lib="$MERGED_NATIVE_LIBS_DIR/$abi/libffmpeg.so"
    local ffprobe_lib="$MERGED_NATIVE_LIBS_DIR/$abi/libffprobe.so"
    if [[ ! -f "$ffmpeg_lib" ]]; then
      echo "[verify-local-downloader-prebuild] Missing merged native lib: $ffmpeg_lib"
      exit 1
    fi
    if [[ ! -f "$ffprobe_lib" ]]; then
      echo "[verify-local-downloader-prebuild] Missing merged native lib: $ffprobe_lib"
      exit 1
    fi
  done
  echo "[verify-local-downloader-prebuild] merged native libs verified for ABIs: $abi_list_raw"
}

for run in 1 2; do
  echo "[verify-local-downloader-prebuild] prebuild run $run"
  verify_impersonation_wheels
  CI=1 npx expo prebuild --clean --platform android --no-install >/tmp/local-downloader-prebuild-$run.log 2>&1 || {
    cat /tmp/local-downloader-prebuild-$run.log
    exit 1
  }
  verify_generated_files
  verify_merged_native_libs
  echo "[verify-local-downloader-prebuild] run $run passed"
done

echo "[verify-local-downloader-prebuild] all checks passed"
