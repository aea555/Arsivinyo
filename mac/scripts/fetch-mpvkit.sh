#!/usr/bin/env bash
# Fetch the player's frameworks, MPVKit's GPL build, into Vendor/MPVKit, each checked against
# the SHA-256 that scripts/mpvkit.json pins (MPVKit's own checksums).
#
# Not through SwiftPM: it fetches every framework MPVKit's package names, the LGPL build too,
# one connection each, and GitHub's release servers can hold a connection to ~100 KB/s. This
# takes only what the app links, each file over parallel ranged requests (shared/tools/fetch.py).
#
#   scripts/fetch-mpvkit.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
python3 - "$ROOT" <<'PY'
import json, os, shutil, subprocess, sys, tempfile

root = sys.argv[1]
pins = json.load(open(f"{root}/scripts/mpvkit.json"))
dest = f"{root}/Vendor/MPVKit"
os.makedirs(dest, exist_ok=True)
for f in pins["frameworks"]:
    name = f["name"]
    target = f"{dest}/{name}.xcframework"
    if os.path.isdir(target):
        continue
    print(f"fetching {name}", flush=True)
    with tempfile.TemporaryDirectory() as tmp:
        archive = f"{tmp}/{name}.zip"
        subprocess.run([sys.executable, f"{root}/../shared/tools/fetch.py", f["url"], f["sha256"], archive], check=True)
        subprocess.run(["unzip", "-q", archive, "-d", tmp], check=True)
        found = [d for d in os.listdir(tmp) if d.endswith(".xcframework")]
        if len(found) != 1:
            sys.exit(f"{name}: expected one .xcframework in the archive, found {found}")
        shutil.move(f"{tmp}/{found[0]}", target)
print(f"all {len(pins['frameworks'])} frameworks are in Vendor/MPVKit")
PY
