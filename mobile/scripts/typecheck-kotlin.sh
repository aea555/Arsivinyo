#!/usr/bin/env bash
#
# Typecheck the local-downloader Kotlin sources without starting a Gradle build.
#
# `run-kotlin-tests.sh` can only compile the handful of sources that touch no Android
# framework classes. This covers the rest — the module, the sounds store, the vault, the
# activities — by putting android.jar, the AAR classes Gradle has already extracted,
# Chaquopy's runtime and the generated R class on the compiler classpath.
#
# It reports syntax and type errors and produces nothing else: no dex, no APK, no Gradle
# daemon. Use it as the fast inner loop when editing Kotlin; the real build is still the
# maintainer's to run.
#
# Jars come from the Gradle cache and from previous build output, so a normal build has to
# have run at least once. Pass REFRESH_CP=1 to rebuild the cached classpath after adding a
# dependency.
#
# One known false positive: the generated R class comes from the last real build, so a
# string or layout added since then reports as an unresolved reference under `R`. That
# clears itself on the next build and does not mean the code is wrong.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODULE="$REPO_ROOT/modules/local-downloader/android"
SRC="$MODULE/src/main/java/expo/modules/localdownloader"
GRADLE_CACHE="${GRADLE_USER_HOME:-$HOME/.gradle}/caches"
WORK_DIR="${TMPDIR:-/tmp}/arsivinyo-kotlin-typecheck"
CP_CACHE="$WORK_DIR/classpath.txt"
KOTLIN_VERSION="2.1.20"

fail() { echo "error: $*" >&2; exit 1; }

find_jar() {
  local found
  found="$(find "$GRADLE_CACHE" -name "$1" -type f 2>/dev/null | grep -v sources | sort -V | tail -1)"
  [ -n "$found" ] || fail "could not find $1 in $GRADLE_CACHE — run a Gradle build once to populate it"
  echo "$found"
}

KOTLIN_COMPILER="$(find_jar "kotlin-compiler-embeddable-$KOTLIN_VERSION.jar")"
KOTLIN_DAEMON="$(find_jar "kotlin-daemon-embeddable-$KOTLIN_VERSION.jar")"
KOTLIN_STDLIB="$(find_jar "kotlin-stdlib-$KOTLIN_VERSION.jar")"
COROUTINES="$(find_jar "kotlinx-coroutines-core-jvm-*.jar")"
TROVE="$(find_jar "trove4j-*.jar")"
ANNOTATIONS="$(find_jar "annotations-13.0.jar")"

ANDROID_SDK="${ANDROID_HOME:-$HOME/Android/Sdk}"
ANDROID_JAR="$(find "$ANDROID_SDK/platforms" -maxdepth 2 -name "android.jar" 2>/dev/null | sort -V | tail -1)"
[ -n "$ANDROID_JAR" ] || fail "no android.jar under $ANDROID_SDK/platforms"

mkdir -p "$WORK_DIR"

# Assembling this list walks the whole Gradle cache, which is slow, so it is kept between
# runs and only rebuilt on request.
# Gradle removes artifacts when it cleans or upgrades, and a cached path that no longer
# exists is simply dropped from the compiler's classpath — which surfaces as dozens of
# "unresolved reference" errors in files the change had nothing to do with. Checking that
# every entry still exists costs a few milliseconds and turns that into a silent rebuild.
cache_is_stale() {
  [ -s "$CP_CACHE" ] || return 0
  while IFS= read -r entry; do
    [ -e "$entry" ] || return 0
  done < "$CP_CACHE"
  return 1
}

if cache_is_stale || [ "${REFRESH_CP:-0}" = "1" ]; then
  echo "Collecting classpath..."
  {
    echo "$ANDROID_JAR"
    echo "$KOTLIN_STDLIB"
    echo "$COROUTINES"
    # Every AAR dependency Gradle has extracted: AndroidX, React Native, Expo, Tink.
    find "$GRADLE_CACHE" -name "classes.jar" -type f 2>/dev/null
    # Plain jars that those AAR types inherit from, plus Chaquopy's runtime.
    find "$GRADLE_CACHE" -type f \
      \( -name "lifecycle-common-*.jar" -o -name "annotation-jvm-*.jar" -o -name "annotation-*.jar" \
      -o -name "kotlin-stdlib-jdk*.jar" -o -name "nanohttpd-*.jar" -o -name "chaquopy_java-*.jar" \
      -o -name "bcprov-jdk18on-*.jar" -o -name "tink-android-*.jar" \) 2>/dev/null | grep -v sources
    # Expo modules are built from node_modules rather than resolved from the cache.
    find "$REPO_ROOT/node_modules" -path "*/build/intermediates/compile_library_classes_jar/release/*/classes.jar" \
      -type f 2>/dev/null
    # The module's own generated resource class.
    find "$MODULE/build" -path "*compile_r_class_jar/release/*" -name "R.jar" -type f 2>/dev/null
  } | sort -u > "$CP_CACHE"
fi

DEPS="$(tr '\n' ':' < "$CP_CACHE")"
COMPILER_CP="$KOTLIN_COMPILER:$KOTLIN_DAEMON:$TROVE:$KOTLIN_STDLIB:$COROUTINES:$ANNOTATIONS"

# Default to the whole module; a caller may pass specific files instead.
if [ "$#" -gt 0 ]; then
  SOURCES=("$@")
else
  SOURCES=()
  while IFS= read -r -d '' file; do SOURCES+=("$file"); done \
    < <(find "$SRC" -name "*.kt" -print0)
fi

echo "Typechecking ${#SOURCES[@]} source file(s) with Kotlin $KOTLIN_VERSION..."
rm -rf "$WORK_DIR/classes"

set +e
OUTPUT="$(java -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -cp "$DEPS" -d "$WORK_DIR/classes" -jvm-target 17 -nowarn \
  -no-stdlib -no-reflect \
  "${SOURCES[@]}" 2>&1)"
set -e

echo "$OUTPUT" | grep -vE "^warning:|^info:" || true

if echo "$OUTPUT" | grep -q "error:"; then
  echo
  echo "Typecheck FAILED: $(echo "$OUTPUT" | grep -c 'error:') error(s)." >&2
  exit 1
fi

echo "Typecheck clean."
