#!/usr/bin/env bash
# Copy your Apple Development identity (Xcode → Settings → Apple Accounts → Manage
# Certificates) into the app's own signing keychain, .signing/, once. scripts/bundle.sh then
# signs with it instead of the self-signed identity.
#
# Why: the Keychain lets an app read its items without asking according to the app's
# partition, which for an app with an Apple Team ID is that ID (the same on every build) and
# for any other app is the hash of that one build. Self-signed, every build was a stranger and
# "Always Allow" could not hold; signed with a Team ID, it holds. A free Personal Team will do;
# nothing is published or uploaded.
#
# Run it in Terminal.app: it reads your login keychain, which macOS asks you to allow.
#
#   scripts/use-apple-development.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
"$ROOT/scripts/make-signing-identity.sh"
DIR="$ROOT/.signing"
KEYCHAIN="$DIR/signing.keychain-db"
PASS="$(cat "$DIR/password")"
LOGIN="$HOME/Library/Keychains/login.keychain-db"

NAME="$(security find-certificate -a -c "Apple Development" -p "$LOGIN" \
  | /usr/bin/openssl x509 -noout -subject -nameopt multiline 2>/dev/null \
  | sed -n 's/^ *commonName *= *//p' | head -1)"
if [ -z "$NAME" ]; then
  echo "No Apple Development certificate in the login keychain: make one in Xcode first" >&2
  exit 1
fi

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
echo "Exporting \"$NAME\"; macOS asks to allow it (your login password)."
security export -k "$LOGIN" -t identities -f pkcs12 -P "$PASS" -o "$WORK/identities.p12"
security unlock-keychain -p "$PASS" "$KEYCHAIN"
security import "$WORK/identities.p12" -k "$KEYCHAIN" -P "$PASS" -T /usr/bin/codesign >/dev/null
security set-key-partition-list -S apple-tool:,apple:,codesign: -s -k "$PASS" "$KEYCHAIN" >/dev/null
# Apple's intermediate between that certificate and its root, which codesign needs to build
# the chain; Xcode keeps it in the login keychain, out of a build's reach. Checked against the
# fingerprint Apple publishes.
curl -sfL --max-time 60 -o "$WORK/wwdr.cer" https://www.apple.com/certificateauthority/AppleWWDRCAG3.cer
FINGERPRINT="$(/usr/bin/openssl x509 -inform der -in "$WORK/wwdr.cer" -noout -fingerprint -sha256 | sed 's/.*=//; s/://g')"
[ "$FINGERPRINT" = "DCF21878C77F4198E4B4614F03D696D89C66C66008D4244E1B99161AAC91601F" ] \
  || { echo "Apple's intermediate certificate did not match its fingerprint" >&2; exit 1; }
security import "$WORK/wwdr.cer" -k "$KEYCHAIN" >/dev/null 2>&1 || true
printf '%s' "$NAME" > "$DIR/identity"
echo "scripts/bundle.sh now signs with \"$NAME\""
