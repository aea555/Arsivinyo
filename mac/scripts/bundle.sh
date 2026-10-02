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
  <key>CFBundleShortVersionString</key><string>0.1.0</string>
  <key>CFBundleVersion</key><string>1</string>
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
  </array>
</dict>
</plist>
PLIST

# Ad-hoc signature. Not for distribution — it is what stops macOS treating each rebuild as
# a brand new, unidentified binary and re-asking for every permission.
codesign --force --sign - "$APP/Contents/Frameworks/libonnxruntime.1.dylib" >/dev/null 2>&1 || true
codesign --force --sign - "$APP" >/dev/null 2>&1 || echo "note: ad-hoc signing skipped"

echo "$APP"
