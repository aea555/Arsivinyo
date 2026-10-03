#!/usr/bin/env bash
# Assemble Arsivinyo.app around the SwiftPM binary.
#
# SwiftPM builds an executable, not an application. Without a bundle macOS gives it no dock
# icon, no menu bar and no proper activation, so a SwiftUI window opens behind everything
# and cannot be focused properly. This is the smallest thing that makes it a real app.
#
#   scripts/bundle.sh [debug|release]   ->  .build/Arsivinyo.app
set -euo pipefail

CONFIG="${1:-debug}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP="$ROOT/.build/Arsivinyo.app"

# The faces pipeline's runtime; a no-op when it is already there.
"$ROOT/../shared/faces/fetch-runtime.sh" >/dev/null
# The player's frameworks; a no-op when they are already there.
"$ROOT/scripts/fetch-mpvkit.sh" >/dev/null
# The torrent engine's libtorrent, built from pinned source; a no-op once built.
"$ROOT/../shared/torrent/build.sh" mac
swift build -c "$CONFIG" --product ArsivinyoApp

BINARY="$ROOT/.build/$CONFIG/ArsivinyoApp"
[ -x "$BINARY" ] || { echo "no binary at $BINARY" >&2; exit 1; }

rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp "$BINARY" "$APP/Contents/MacOS/Arsivinyo"
# SwiftUI looks its text up in the main bundle, which is this one, not the SwiftPM target.
cp -R "$ROOT"/Resources/*.lproj "$APP/Contents/Resources/"

# Faces: the two pinned models and what pins them, and the runtime they run in. The app
# checks each model against MODELS.json before loading it.
FACES="$ROOT/../shared/faces"
mkdir -p "$APP/Contents/Resources/faces/models" "$APP/Contents/Frameworks"
cp "$FACES/MODELS.json" "$APP/Contents/Resources/faces/"
cp "$FACES"/models/*.onnx "$FACES"/models/LICENSE-* "$APP/Contents/Resources/faces/models/"
cp "$FACES/runtime/onnxruntime-osx-arm64-1.30.0/lib/libonnxruntime.1.30.0.dylib" \
    "$APP/Contents/Frameworks/libonnxruntime.1.dylib"

# The icon is vector layers (Resources/AppIcon.icon). actool renders them into the asset
# catalog macOS draws as glass, plus an .icns for anything that reads the old format.
xcrun actool "$ROOT/Resources/AppIcon.icon" --compile "$APP/Contents/Resources" \
    --app-icon AppIcon --platform macosx --minimum-deployment-target 27.0 \
    --output-partial-info-plist "$ROOT/.build/icon-partial.plist" >/dev/null

cat > "$APP/Contents/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>CFBundleName</key><string>Arsivinyo</string>
  <key>CFBundleDisplayName</key><string>Arsivinyo</string>
  <key>CFBundleExecutable</key><string>Arsivinyo</string>
  <!-- Distinct from the Android app's com.arsivinyo.local, which must never change: this
       is a different application on a different platform, not the same one. -->
  <key>CFBundleIdentifier</key><string>com.arsivinyo.mac</string>
  <key>CFBundleDevelopmentRegion</key><string>en</string>
  <key>CFBundleLocalizations</key><array><string>en</string><string>tr</string></array>
  <key>CFBundleIconFile</key><string>AppIcon</string>
  <key>CFBundleIconName</key><string>AppIcon</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>CFBundleShortVersionString</key><string>4.0.0-beta.1</string>
  <key>CFBundleVersion</key><string>40000</string>
  <key>LSMinimumSystemVersion</key><string>27.0</string>
  <key>NSHighResolutionCapable</key><true/>
  <!-- A normal windowed app: dock icon, menu bar, the lot. -->
  <key>LSUIElement</key><false/>
  <key>NSHumanReadableCopyright</key><string>GPL-3.0-or-later</string>
  <!-- Pairing finds the phone over Bonjour and talks to it directly. Without these, macOS
       refuses both silently. -->
  <key>NSLocalNetworkUsageDescription</key>
  <string>Arsivinyo finds your phone on this network and moves music between them.</string>
  <key>NSBonjourServices</key><array><string>_arsivinyo._tcp</string></array>
  <!-- stremio:// links, which a Stremio add-on's configure page installs through: the Watch
       section offers to install them. If Stremio is installed as well, it may take them. -->
  <key>CFBundleURLTypes</key>
  <array>
    <dict>
      <key>CFBundleURLName</key><string>Stremio add-on</string>
      <key>CFBundleURLSchemes</key><array><string>stremio</string></array>
    </dict>
    <dict>
      <key>CFBundleURLName</key><string>Magnet link</string>
      <key>CFBundleURLSchemes</key><array><string>magnet</string></array>
    </dict>
  </array>
  <!-- .torrent files, opened from Finder or a browser: the torrents sheet adds them. -->
  <key>CFBundleDocumentTypes</key>
  <array>
    <dict>
      <key>CFBundleTypeName</key><string>BitTorrent file</string>
      <key>CFBundleTypeRole</key><string>Viewer</string>
      <key>LSHandlerRank</key><string>Alternate</string>
      <key>LSItemContentTypes</key><array><string>org.bittorrent.torrent</string></array>
    </dict>
  </array>
  <key>UTImportedTypeDeclarations</key>
  <array>
    <dict>
      <key>UTTypeIdentifier</key><string>org.bittorrent.torrent</string>
      <key>UTTypeDescription</key><string>BitTorrent file</string>
      <key>UTTypeConformsTo</key><array><string>public.data</string></array>
      <key>UTTypeTagSpecification</key>
      <dict>
        <key>public.filename-extension</key><array><string>torrent</string></array>
        <key>public.mime-type</key><array><string>application/x-bittorrent</string></array>
      </dict>
    </dict>
  </array>
</dict>
</plist>
PLIST

# Signed with "Arsivinyo Local Signing", from a keychain of its own that the script makes
# the first time (scripts/make-signing-identity.sh says why): one identity, so the Keychain
# sees every build as the same app and "Always Allow" holds. Not for distribution.
"$ROOT/scripts/make-signing-identity.sh"
SIGNING_KEYCHAIN="$ROOT/.signing/signing.keychain-db"
security unlock-keychain -p "$(cat "$ROOT/.signing/password")" "$SIGNING_KEYCHAIN"
# codesign builds the certificate chain from the keychains in the search list only, so this
# one joins it (after the ones already there, which stay as they are).
if ! security list-keychains -d user | grep -q "$SIGNING_KEYCHAIN"; then
  # shellcheck disable=SC2046
  security list-keychains -d user -s $(security list-keychains -d user | tr -d '"') "$SIGNING_KEYCHAIN"
fi
# An Apple Development identity once scripts/use-apple-development.sh has copied one in: its
# Team ID is what lets the Keychain's "Always Allow" hold across builds.
IDENTITY="Arsivinyo Local Signing"
[ -f "$ROOT/.signing/identity" ] && IDENTITY="$(cat "$ROOT/.signing/identity")"
sign() {
  codesign --force --keychain "$SIGNING_KEYCHAIN" --sign "$IDENTITY" "$1" >/dev/null 2>&1 && return
  # Never left unsigned: an unsigned binary does not launch on Apple silicon at all.
  echo "note: could not sign $(basename "$1"); signed ad hoc, so the Keychain will ask again" >&2
  codesign --force --sign - "$1" >/dev/null 2>&1 || true
}
sign "$APP/Contents/Frameworks/libonnxruntime.1.dylib"
sign "$APP"

echo "$APP"
