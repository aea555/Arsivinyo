#!/usr/bin/env bash
# Check that every piece of text the app shows has a Turkish translation, and that the table
# holds nothing the app no longer shows. The compiler lists the keys, so nothing is guessed.
#
#   scripts/check-strings.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

touch "$ROOT"/Sources/ArsivinyoApp/*.swift
swift build --package-path "$ROOT" --product ArsivinyoApp \
    -Xswiftc -emit-localized-strings -Xswiftc -emit-localized-strings-path -Xswiftc "$OUT" \
    >"$OUT/build.log" 2>&1 || { cat "$OUT/build.log" >&2; exit 1; }

# A .strings file is an old-style plist, which Python cannot read; plutil can.
plutil -convert json -o "$OUT/tr.json" "$ROOT/Resources/tr.lproj/Localizable.strings"

python3 - "$OUT" "$OUT/tr.json" <<'PY'
import glob, json, sys
out, table = sys.argv[1:]
shown = {e["key"] for f in glob.glob(f"{out}/*.stringsdata")
         for entries in json.load(open(f)).get("tables", {}).values() for e in entries}
# Text that is the same in every language: nothing, a keyboard shortcut, and the app's name.
shown -= {"", "⇧⌘V", "Arsivinyo"}
translated = set(json.load(open(table)))
missing, stale = sorted(shown - translated), sorted(translated - shown)
for k in missing: print(f"not translated: {k!r}")
for k in stale: print(f"no longer shown: {k!r}")
if missing or stale: sys.exit(1)
print(f"all {len(shown)} strings have a Turkish translation")
PY
