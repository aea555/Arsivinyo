#!/usr/bin/env bash
# Package .build/Arsivinyo.app as a disk image to hand to someone else: the app beside a link
# to Applications, which is the usual drag-to-install window. Run bundle.sh release first.
#
#   scripts/dmg.sh   ->  .build/Arsivinyo-<version>.dmg
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP="$ROOT/.build/Arsivinyo.app"
[ -d "$APP" ] || { echo "no app at $APP: run scripts/bundle.sh release" >&2; exit 1; }
codesign --verify --deep --strict "$APP"

VERSION="$(/usr/libexec/PlistBuddy -c "Print CFBundleShortVersionString" "$APP/Contents/Info.plist")"
DMG="$ROOT/.build/Arsivinyo-$VERSION.dmg"
STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT

ditto "$APP" "$STAGE/Arsivinyo.app"
ln -s /Applications "$STAGE/Applications"
rm -f "$DMG"
hdiutil create -quiet -volname "Arsivinyo $VERSION" -srcfolder "$STAGE" -fs APFS -format ULFO "$DMG"
echo "$DMG"
