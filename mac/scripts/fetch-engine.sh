#!/usr/bin/env bash
# Fetch what the download engine needs into .build/engine.
#
# yt-dlp is deliberately not in the repository: it is a dependency that changes weekly, and
# the app replaces it in place anyway through the same updater the phone uses. This is the
# equivalent of what the Qt build did with a CMake custom command.
#
#   yt-dlp/   the extractor, unpacked from its wheel, replaceable underneath the engine
#   site/     curl_cffi, which is what lets the engine impersonate a browser. Without it
#             most sites refuse a downloader outright, and the app is the lesser one.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ENGINE="$ROOT/.build/engine"
SHARED="$ROOT/../shared/engine"

# macOS ships Python 3.9, which a current yt-dlp will not run on. Newest first.
PYTHON=""
for candidate in \
    /opt/homebrew/opt/python@3.13/bin/python3.13 \
    /opt/homebrew/opt/python@3.12/bin/python3.12 \
    /opt/homebrew/bin/python3
do
    [ -x "$candidate" ] && { PYTHON="$candidate"; break; }
done
[ -n "$PYTHON" ] || {
    echo "need a Homebrew Python 3.12 or newer: brew install python@3.12" >&2
    exit 1
}
echo "using $($PYTHON --version) at $PYTHON"

mkdir -p "$ENGINE"
"$PYTHON" "$SHARED/ytdlp_updater.py" "$ENGINE"

# Pinned to match the wheels the Android app ships, so the two behave the same.
"$PYTHON" -m pip install --quiet --upgrade --target "$ENGINE/site" "curl_cffi==0.14.0"

echo "engine ready in $ENGINE"
