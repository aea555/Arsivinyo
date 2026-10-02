#!/usr/bin/env bash
# Fetch and unpack the torrent engine's pinned sources (VERSIONS.json) into sources/.
# A no-op for what is already there.
#
#   shared/torrent/fetch.sh
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "$HERE/sources"
python3 - "$HERE" <<'PY'
import json, os, subprocess, sys
here = sys.argv[1]
for s in json.load(open(f"{here}/VERSIONS.json"))["sources"]:
    target = f"{here}/sources/{s['directory']}"
    if os.path.isdir(target):
        continue
    archive = f"{here}/sources/{os.path.basename(s['url'])}"
    print(f"fetching {s['name']} {s['version']}", flush=True)
    subprocess.run([sys.executable, f"{here}/../tools/fetch.py", s["url"], s["sha256"], archive], check=True)
    subprocess.run(["tar", "-xzf", archive, "-C", f"{here}/sources"], check=True)
    os.remove(archive)
PY
