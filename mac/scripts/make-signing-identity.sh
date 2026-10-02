#!/usr/bin/env bash
# Make "Arsivinyo Local Signing", the identity scripts/bundle.sh signs the app with, in a
# keychain of its own: .signing/signing.keychain-db, unlocked by the password beside it.
# bundle.sh runs this itself the first time; it does nothing once the identity is there.
#
# Why sign at all: an ad-hoc signature is tied to the exact binary, so macOS sees every
# rebuild as a new app, and the Keychain asks again for each of the app's items on the next
# launch, however often "Always Allow" was chosen. Signed with one identity, every build is
# the same app: "Always Allow" is asked once per item, and holds.
#
# Why its own keychain: the login keychain will not let a build run outside a logged-in
# Terminal (a script, an agent) use a key without a prompt it cannot show. This one is
# unlocked with its stored password, as build servers do. That leaves the key unprotected on
# disk, which is acceptable for what it is: a self-signed certificate nothing trusts, signing
# this Mac's builds for this Mac. Not for distribution.
#
#   scripts/make-signing-identity.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DIR="$ROOT/.signing"
KEYCHAIN="$DIR/signing.keychain-db"
NAME="Arsivinyo Local Signing"
[ -f "$KEYCHAIN" ] && [ -f "$DIR/password" ] && exit 0

mkdir -p "$DIR"
chmod 700 "$DIR"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
PASS="$(/usr/bin/openssl rand -hex 24)"
printf '%s' "$PASS" > "$DIR/password"
chmod 600 "$DIR/password"

cat > "$WORK/cert.cnf" <<CNF
[req]
distinguished_name = dn
x509_extensions = ext
prompt = no
[dn]
CN = $NAME
[ext]
basicConstraints = critical, CA:false
keyUsage = critical, digitalSignature
extendedKeyUsage = critical, codeSigning
CNF
# LibreSSL, macOS's own: its .p12 is one the keychain imports.
/usr/bin/openssl req -x509 -newkey rsa:2048 -nodes -days 7300 -config "$WORK/cert.cnf" \
  -keyout "$WORK/key.pem" -out "$WORK/cert.pem" 2>/dev/null
/usr/bin/openssl pkcs12 -export -inkey "$WORK/key.pem" -in "$WORK/cert.pem" -name "$NAME" \
  -out "$WORK/identity.p12" -passout "pass:$PASS"

security create-keychain -p "$PASS" "$KEYCHAIN"
# Stays unlocked while a build runs, however long it takes.
security set-keychain-settings "$KEYCHAIN"
security unlock-keychain -p "$PASS" "$KEYCHAIN"
security import "$WORK/identity.p12" -k "$KEYCHAIN" -P "$PASS" -T /usr/bin/codesign >/dev/null
# codesign may use the key without asking.
security set-key-partition-list -S apple-tool:,apple:,codesign: -s -k "$PASS" "$KEYCHAIN" >/dev/null
echo "made $NAME in .signing/; scripts/bundle.sh signs with it"
