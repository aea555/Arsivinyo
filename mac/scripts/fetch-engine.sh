#!/usr/bin/env bash
# Fetch what the download engine needs into .build/engine, which bundle.sh copies into the
# app. Nothing here comes from Homebrew, so the app runs on a Mac without it.
#
# yt-dlp is deliberately not in the repository: it is a dependency that changes weekly, and
# the app replaces it in place anyway through the same updater the phone uses. This is the
# equivalent of what the Qt build did with a CMake custom command.
#
#   python/   a relocatable Python, pinned in engine.json. macOS ships 3.9, which a current
#             yt-dlp will not run on.
#   ffmpeg    static builds, pinned in engine.json: merging separate video and audio, and
#   ffprobe   the audio presets.
#   yt-dlp/   the extractor, unpacked from its wheel, replaceable underneath the engine
#   site/     curl_cffi, which is what lets the engine impersonate a browser. Without it
#             most sites refuse a downloader outright, and the app is the lesser one.
#
# Python and ffmpeg are fetched once. yt-dlp and curl_cffi are fetched again on every run,
# so running this is how a build picks up the newest yt-dlp.
#
#   scripts/fetch-engine.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ENGINE="$ROOT/.build/engine"
SHARED="$ROOT/../shared/engine"
mkdir -p "$ENGINE"

python3 - "$ROOT" "$ENGINE" <<'PY'
import json, os, shutil, subprocess, sys, tempfile

root, engine = sys.argv[1], sys.argv[2]
pins = json.load(open(f"{root}/scripts/engine.json"))
fetch = f"{root}/../shared/tools/fetch.py"

def get(url, sha256, into):
    subprocess.run([sys.executable, fetch, url, sha256, into], check=True)

python = pins["python"]
marker = f"{engine}/python/VERSION"
if not os.path.isfile(marker) or open(marker).read().strip() != python["version"]:
    print(f"fetching Python {python['version']}", flush=True)
    with tempfile.TemporaryDirectory() as tmp:
        get(python["url"], python["sha256"], f"{tmp}/python.tar.gz")
        subprocess.run(["tar", "-xzf", f"{tmp}/python.tar.gz", "-C", tmp], check=True)
        shutil.rmtree(f"{engine}/python", ignore_errors=True)
        shutil.move(f"{tmp}/python", f"{engine}/python")
    with open(marker, "w") as f:
        f.write(python["version"] + "\n")

ffmpeg = pins["ffmpeg"]
marker = f"{engine}/ffmpeg.version"
if not os.path.isfile(marker) or open(marker).read().strip() != ffmpeg["version"]:
    for tool in ffmpeg["tools"]:
        print(f"fetching {tool['name']} {ffmpeg['version']}", flush=True)
        with tempfile.TemporaryDirectory() as tmp:
            get(tool["url"], tool["sha256"], f"{tmp}/{tool['name']}.zip")
            subprocess.run(["unzip", "-q", f"{tmp}/{tool['name']}.zip", "-d", tmp], check=True)
            os.replace(f"{tmp}/{tool['name']}", f"{engine}/{tool['name']}")
            os.chmod(f"{engine}/{tool['name']}", 0o755)
    with open(marker, "w") as f:
        f.write(ffmpeg["version"] + "\n")
PY

PYTHON="$ENGINE/python/bin/python3"
echo "using $($PYTHON --version)"
"$PYTHON" "$SHARED/ytdlp_updater.py" "$ENGINE"

# Pinned to match the wheels the Android app ships, so the two behave the same. Installed
# afresh, so nothing built for another Python is left behind.
rm -rf "$ENGINE/site"
"$PYTHON" -m pip install --quiet --disable-pip-version-check --target "$ENGINE/site" "curl_cffi==0.14.0"

echo "engine ready in $ENGINE"
