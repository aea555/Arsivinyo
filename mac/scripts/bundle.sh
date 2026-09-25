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

swift build -c "$CONFIG" --product ArsivinyoApp

BINARY="$ROOT/.build/$CONFIG/ArsivinyoApp"
[ -x "$BINARY" ] || { echo "no binary at $BINARY" >&2; exit 1; }

rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp "$BINARY" "$APP/Contents/MacOS/Arsivinyo"
# SwiftUI looks its text up in the main bundle, which is this one, not the SwiftPM target.
cp -R "$ROOT"/Resources/*.lproj "$APP/Contents/Resources/"

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
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>CFBundleShortVersionString</key><string>0.1.0</string>
  <key>CFBundleVersion</key><string>1</string>
  <key>LSMinimumSystemVersion</key><string>27.0</string>
  <key>NSHighResolutionCapable</key><true/>
  <!-- A normal windowed app: dock icon, menu bar, the lot. -->
  <key>LSUIElement</key><false/>
  <key>NSHumanReadableCopyright</key><string>GPL-3.0-or-later</string>
</dict>
</plist>
PLIST

# Ad-hoc signature. Not for distribution — it is what stops macOS treating each rebuild as
# a brand new, unidentified binary and re-asking for every permission.
codesign --force --sign - "$APP" >/dev/null 2>&1 || echo "note: ad-hoc signing skipped"

echo "$APP"
