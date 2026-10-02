#!/usr/bin/env bash
# Fetch the player's frameworks, MPVKit's GPL build, into Vendor/MPVKit, each checked against
# the SHA-256 that scripts/mpvkit.json pins (MPVKit's own checksums).
#
# Not through SwiftPM: it fetches every framework MPVKit's package names, the LGPL build too,
# one connection each, and GitHub's release servers can hold a connection to ~100 KB/s. This
# takes only what the app links, each file over parallel ranged requests.
#
#   scripts/fetch-mpvkit.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
python3 - "$ROOT" <<'PY'
import concurrent.futures, hashlib, json, os, shutil, subprocess, sys, tempfile, urllib.request

root = sys.argv[1]
pins = json.load(open(f"{root}/scripts/mpvkit.json"))
dest = f"{root}/Vendor/MPVKit"
os.makedirs(dest, exist_ok=True)
PART = 2 * 1024 * 1024

def fetch(url, path):
    # The release URL redirects to a signed one, which takes ranges.
    with urllib.request.urlopen(urllib.request.Request(url, method="HEAD")) as r:
        final, size = r.url, int(r.headers["Content-Length"])
    def part(start):
        end = min(size, start + PART) - 1
        for attempt in range(5):
            try:
                req = urllib.request.Request(final, headers={"Range": f"bytes={start}-{end}"})
                with urllib.request.urlopen(req, timeout=60) as r:
                    data = r.read()
                if len(data) == end - start + 1:
                    return start, data
            except OSError:
                pass
        raise RuntimeError(f"could not fetch bytes {start}-{end} of {url}")
    with open(path, "wb") as out, concurrent.futures.ThreadPoolExecutor(16) as pool:
        out.truncate(size)
        for start, data in pool.map(part, range(0, size, PART)):
            out.seek(start)
            out.write(data)

for f in pins["frameworks"]:
    name = f["name"]
    target = f"{dest}/{name}.xcframework"
    if os.path.isdir(target):
        continue
    print(f"fetching {name}", flush=True)
    with tempfile.TemporaryDirectory() as tmp:
        archive = f"{tmp}/{name}.zip"
        fetch(f["url"], archive)
        digest = hashlib.sha256(open(archive, "rb").read()).hexdigest()
        if digest != f["sha256"]:
            sys.exit(f"{name}: checksum {digest} is not the pinned {f['sha256']}")
        subprocess.run(["unzip", "-q", archive, "-d", tmp], check=True)
        found = [d for d in os.listdir(tmp) if d.endswith(".xcframework")]
        if len(found) != 1:
            sys.exit(f"{name}: expected one .xcframework in the archive, found {found}")
        shutil.move(f"{tmp}/{found[0]}", target)
print(f"all {len(pins['frameworks'])} frameworks are in Vendor/MPVKit")
PY
