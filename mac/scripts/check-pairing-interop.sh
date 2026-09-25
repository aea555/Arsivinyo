#!/usr/bin/env bash
# Pairs the Mac's implementation with the phone's own Kotlin code, on this machine.
#
# The phone's PairingService runs on the JVM (MacInteropTest), the Mac's in CoreChecks, and
# they talk over TCP on loopback: the v2 ceremony, a listing, a track each way and a link.
# Needs the Gradle cache the phone's Kotlin tests need.
#
#   scripts/check-pairing-interop.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DIR="$(mktemp -d)"
trap 'rm -rf "$DIR"' EXIT
PORT=47${RANDOM:0:3}

swift build --package-path "$ROOT" --product CoreChecks >/dev/null

ARSIVINYO_INTEROP_DIR="$DIR" ARSIVINYO_INTEROP_PORT="$PORT" \
    bash "$ROOT/../mobile/scripts/run-kotlin-tests.sh" > "$DIR/phone.log" 2>&1 &
PHONE=$!

MAC=0
swift run --package-path "$ROOT" CoreChecks --interop "$DIR" || MAC=$?
PHONE_RESULT=0
wait $PHONE || PHONE_RESULT=$?

if [ $PHONE_RESULT -ne 0 ]; then
    echo "phone side:"
    grep -E "^[0-9]+\) |AssertionError|expected" "$DIR/phone.log" | head -20
fi
[ $MAC -eq 0 ] && [ $PHONE_RESULT -eq 0 ] && echo "the Mac and the phone's code pair and trade" && exit 0
exit 1
