#!/usr/bin/env bash
#
# Run the module's JVM unit tests without starting a Gradle build.
#
# `./gradlew :expo-local-downloader:test` is the canonical way to run these, but it
# configures the whole Expo/AGP project and spawns a daemon. This script compiles the few
# pure-JVM sources directly with the Kotlin compiler and runs JUnit over them, which takes
# seconds and touches no Android tooling.
#
# It only works for tests that need no Android framework classes — currently the vault
# cipher and the backup container. Anything touching Context, MediaStore or the Keystore
# must go through Gradle (or the device).
#
# Jars are taken from the Gradle cache, so run a normal build at least once first.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODULE="$REPO_ROOT/modules/local-downloader/android"
GRADLE_CACHE="${GRADLE_USER_HOME:-$HOME/.gradle}/caches"
WORK_DIR="${TMPDIR:-/tmp}/arsivinyo-kotlin-tests"

KOTLIN_VERSION="2.1.20"

fail() { echo "error: $*" >&2; exit 1; }

# Pick the newest jar matching a name pattern, so a version bump does not break this.
find_jar() {
  local pattern="$1"
  local found
  found="$(find "$GRADLE_CACHE" -name "$pattern" -type f 2>/dev/null | sort -V | tail -1)"
  [ -n "$found" ] || fail "could not find $pattern in $GRADLE_CACHE — run a Gradle build once to populate it"
  echo "$found"
}

KOTLIN_COMPILER="$(find_jar "kotlin-compiler-embeddable-$KOTLIN_VERSION.jar")"
KOTLIN_DAEMON="$(find_jar "kotlin-daemon-embeddable-$KOTLIN_VERSION.jar")"
KOTLIN_STDLIB="$(find_jar "kotlin-stdlib-$KOTLIN_VERSION.jar")"
COROUTINES="$(find_jar "kotlinx-coroutines-core-jvm-*.jar")"
TROVE="$(find_jar "trove4j-*.jar")"
# The Kotlin backend emits @NotNull annotations and needs the class on its own classpath.
ANNOTATIONS="$(find_jar "annotations-13.0.jar")"

TINK="$(find_jar "tink-android-1.13.0.jar")"
BOUNCY_CASTLE="$(find_jar "bcprov-jdk18on-*.jar")"
# Certificate building, for the TLS link test only — never shipped in the APK.
BOUNCY_CASTLE_PKIX="$(find_jar "bcpkix-jdk18on-1.79.jar")"
BOUNCY_CASTLE_UTIL="$(find_jar "bcutil-jdk18on-1.79.jar")"
JSON="$(find_jar "json-2*.jar")"
JUNIT="$(find_jar "junit-4.13.2.jar")"
HAMCREST="$(find_jar "hamcrest-core-1.3.jar")"

COMPILER_CP="$KOTLIN_COMPILER:$KOTLIN_DAEMON:$TROVE:$KOTLIN_STDLIB:$COROUTINES:$ANNOTATIONS"
# Coroutines is on the test classpath as well as the compiler's: the download scheduler
# is built on them, so its suite needs them at run time too.
TEST_CP="$KOTLIN_STDLIB:$COROUTINES:$TINK:$BOUNCY_CASTLE:$BOUNCY_CASTLE_PKIX:$BOUNCY_CASTLE_UTIL:$JSON:$JUNIT:$HAMCREST"

SRC_MAIN="$MODULE/src/main/java/expo/modules/localdownloader"
SRC_TEST="$MODULE/src/test/java/expo/modules/localdownloader"

# Each entry is "<test class>|<source files...>". Add a line to cover a new suite.
BACKUP_SOURCES="$SRC_MAIN/backup/BackupFormat.kt $SRC_MAIN/backup/BackupCrypto.kt $SRC_MAIN/backup/BackupContainer.kt $SRC_MAIN/backup/BackupSections.kt $SRC_MAIN/backup/BackupPorts.kt $SRC_MAIN/backup/BackupPipeline.kt"
VAULT_SOURCES="$SRC_MAIN/vault/VaultIndexCodec.kt $SRC_MAIN/vault/VaultSession.kt $SRC_MAIN/vault/VaultAuthPolicy.kt $SRC_MAIN/vault/VaultKeyBox.kt"
SCHEDULER_SOURCES="$SRC_MAIN/scheduler/PriorityGate.kt $SRC_MAIN/scheduler/DownloadStages.kt"
PAIRING_SOURCES="$SRC_MAIN/pairing/PairingWire.kt $SRC_MAIN/pairing/Ed25519Keys.kt $SRC_MAIN/pairing/PairingAuth.kt $SRC_MAIN/pairing/PeerRegistry.kt $SRC_MAIN/pairing/PairingIdentity.kt $SRC_MAIN/pairing/PeerLink.kt $SRC_MAIN/pairing/PeerContent.kt $SRC_MAIN/pairing/PeerSession.kt $SRC_MAIN/pairing/PairingService.kt"
SUITES=(
  "expo.modules.localdownloader.pairing.PairingWireTest|$PAIRING_SOURCES $SRC_TEST/pairing/PairingWireTest.kt"
  "expo.modules.localdownloader.pairing.PairingAuthTest|$PAIRING_SOURCES $SRC_TEST/pairing/PairingAuthTest.kt"
  "expo.modules.localdownloader.pairing.PeerRegistryTest|$PAIRING_SOURCES $SRC_TEST/pairing/PeerRegistryTest.kt"
  "expo.modules.localdownloader.pairing.PeerLinkTest|$PAIRING_SOURCES $SRC_TEST/pairing/LoopbackPeers.kt $SRC_TEST/pairing/PeerLinkTest.kt"
  "expo.modules.localdownloader.pairing.PeerSessionTest|$PAIRING_SOURCES $SRC_TEST/pairing/LoopbackPeers.kt $SRC_TEST/pairing/PeerSessionTest.kt"
  "expo.modules.localdownloader.pairing.PairingServiceTest|$PAIRING_SOURCES $SRC_TEST/pairing/LoopbackPeers.kt $SRC_TEST/pairing/PairingServiceTest.kt"
  "expo.modules.localdownloader.scheduler.PriorityGateTest|$SCHEDULER_SOURCES $SRC_TEST/scheduler/PriorityGateTest.kt"
  "expo.modules.localdownloader.backup.BackupFormatTest|$BACKUP_SOURCES $SRC_TEST/backup/BackupFormatTest.kt"
  "expo.modules.localdownloader.backup.BackupContainerTest|$SRC_TEST/backup/BackupContainerTest.kt"
  "expo.modules.localdownloader.backup.BackupSectionsTest|$SRC_TEST/backup/BackupSectionsTest.kt"
  "expo.modules.localdownloader.backup.BackupPortsTest|$SRC_TEST/backup/BackupPortsTest.kt"
  "expo.modules.localdownloader.backup.BackupPipelineTest|$SRC_TEST/backup/BackupPipelineTest.kt"
  "expo.modules.localdownloader.vault.VaultKeyBoxTest|$BACKUP_SOURCES $VAULT_SOURCES $SRC_TEST/vault/VaultKeyBoxTest.kt"
  "expo.modules.localdownloader.vault.VaultSessionTest|$VAULT_SOURCES $SRC_TEST/vault/VaultSessionTest.kt"
  "expo.modules.localdownloader.vault.VaultAuthPolicyTest|$VAULT_SOURCES $SRC_TEST/vault/VaultAuthPolicyTest.kt"
  "expo.modules.localdownloader.vault.VaultIndexCodecTest|$VAULT_SOURCES $SRC_TEST/vault/VaultIndexCodecTest.kt"
  "expo.modules.localdownloader.crypto.CryptoVectorsTest|$BACKUP_SOURCES $VAULT_SOURCES $SRC_TEST/crypto/CryptoVectorsTest.kt"
)

rm -rf "$WORK_DIR"
mkdir -p "$WORK_DIR/classes"

SOURCES=()
TEST_CLASSES=()
for suite in "${SUITES[@]}"; do
  TEST_CLASSES+=("${suite%%|*}")
  read -r -a suite_sources <<< "${suite#*|}"
  SOURCES+=("${suite_sources[@]}")
done

# A helper shared by two suites appears in both lists; the compiler is given one copy.
# Read in a loop rather than with mapfile: that is bash 4, and macOS ships bash 3.2.
DEDUPED=()
while IFS= read -r source_file; do
  DEDUPED+=("$source_file")
done < <(printf '%s\n' "${SOURCES[@]}" | awk '!seen[$0]++')
SOURCES=("${DEDUPED[@]}")

echo "Compiling ${#SOURCES[@]} source file(s) with Kotlin $KOTLIN_VERSION..."
# -no-stdlib/-no-reflect: the compiler looks for a Kotlin *home* directory that does not
# exist here and warns about it. The stdlib is supplied explicitly on TEST_CP instead.
java -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -cp "$TEST_CP" -d "$WORK_DIR/classes" -jvm-target 17 -nowarn \
  -no-stdlib -no-reflect \
  "${SOURCES[@]}"

echo "Running ${#TEST_CLASSES[@]} suite(s)..."
java ${ARSIVINYO_TEST_JVM_ARGS:-} -cp "$TEST_CP:$WORK_DIR/classes" \
  org.junit.runner.JUnitCore "${TEST_CLASSES[@]}"
