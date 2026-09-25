#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
WHEELS_DIR="$ROOT_DIR/modules/local-downloader/android/chaquopy-wheels"
# Support both spellings for compatibility with earlier docs.
# Preferred: CHAQUOPY_PYPI_DIR (correct spelling)
# Legacy alias: CHAQUPY_PYPI_DIR
CHAQUOPY_PYPI_DIR="${CHAQUOPY_PYPI_DIR:-${CHAQUPY_PYPI_DIR:-}}"
BUILDER_PYTHON="${CHAQUOPY_BUILDER_PYTHON:-}"
PYTHON_VERSION="${PYTHON_VERSION:-3.11}"
PACKAGE_NAME="${PACKAGE_NAME:-curl-cffi}"
ABI_LIST_RAW="${ABI_LIST:-arm64-v8a}"
read -r -a ABI_LIST_ARR <<< "$ABI_LIST_RAW"

if [[ -z "$CHAQUOPY_PYPI_DIR" ]]; then
  cat <<'EOF'
[build-impersonation-wheels] CHAQUOPY_PYPI_DIR is not set.

Set CHAQUOPY_PYPI_DIR (preferred) to Chaquopy's package build directory.
Legacy alias CHAQUPY_PYPI_DIR is also accepted.
Expected location contains build-wheel.py.

Example:
  export CHAQUOPY_PYPI_DIR="$HOME/dev/chaquopy/server/pypi"
  bash scripts/build-impersonation-wheels.sh
EOF
  exit 1
fi

if [[ ! -f "$CHAQUOPY_PYPI_DIR/build-wheel.py" ]]; then
  echo "[build-impersonation-wheels] Missing $CHAQUOPY_PYPI_DIR/build-wheel.py"
  exit 1
fi

CHAQUOPY_ROOT="$(cd "$CHAQUOPY_PYPI_DIR/../.." && pwd)"
TARGET_REPO_DIR="$CHAQUOPY_ROOT/maven/com/chaquo/python/target"
if [[ ! -d "$TARGET_REPO_DIR" ]]; then
  cat <<EOF
[build-impersonation-wheels] Missing Chaquopy target repo directory:
  $TARGET_REPO_DIR

Download at least one target version before building wheels, for example:
  cd "$CHAQUOPY_ROOT"
  target/download-target.sh "maven/com/chaquo/python/target/3.11.13-2"
EOF
  exit 1
fi

if [[ "$PACKAGE_NAME" = /* ]]; then
  if [[ ! -d "$PACKAGE_NAME" ]]; then
    echo "[build-impersonation-wheels] PACKAGE_NAME points to missing recipe dir: $PACKAGE_NAME"
    exit 1
  fi
else
  normalized_package_name="$(echo "$PACKAGE_NAME" | tr '[:upper:]' '[:lower:]' | sed 's/_/-/g')"
  recipe_dir="$CHAQUOPY_PYPI_DIR/packages/$normalized_package_name"
  if [[ ! -d "$recipe_dir" ]]; then
    cat <<EOF
[build-impersonation-wheels] Missing Chaquopy recipe: $recipe_dir

build-wheel.py can only build packages which have a recipe directory.
Options:
  1) Create/add a recipe at:
     $recipe_dir
  2) Or pass an absolute recipe path:
     PACKAGE_NAME=/abs/path/to/recipe-dir npm run build:impersonation-wheels
EOF
    exit 1
  fi
fi

if [[ -z "$BUILDER_PYTHON" && -x "$CHAQUOPY_PYPI_DIR/.venv/bin/python" ]]; then
  BUILDER_PYTHON="$CHAQUOPY_PYPI_DIR/.venv/bin/python"
fi
if [[ -z "$BUILDER_PYTHON" ]]; then
  BUILDER_PYTHON="$(command -v python3 || true)"
fi
if [[ -z "$BUILDER_PYTHON" ]]; then
  echo "[build-impersonation-wheels] Could not find a Python interpreter. Set CHAQUOPY_BUILDER_PYTHON."
  exit 1
fi
if ! "$BUILDER_PYTHON" -c "import build" >/dev/null 2>&1; then
  cat <<EOF
[build-impersonation-wheels] Python interpreter missing 'build' module:
  $BUILDER_PYTHON

Install it with:
  $BUILDER_PYTHON -m pip install -r "$CHAQUOPY_PYPI_DIR/requirements.txt"
EOF
  exit 1
fi

BUILDER_BIN_DIR="$(cd "$(dirname "$BUILDER_PYTHON")" && pwd)"
export PATH="$BUILDER_BIN_DIR:$PATH"

# macOS has shasum instead. One of the two must exist.
if command -v sha256sum >/dev/null 2>&1; then
  SHA256SUM=(sha256sum)
elif command -v shasum >/dev/null 2>&1; then
  SHA256SUM=(shasum -a 256)
else
  echo "[build-impersonation-wheels] sha256sum or shasum is required"
  exit 1
fi

mkdir -p "$WHEELS_DIR"

for abi in "${ABI_LIST_ARR[@]}"; do
  echo "[build-impersonation-wheels] Building $PACKAGE_NAME for ABI=$abi (python=$PYTHON_VERSION, builder=$BUILDER_PYTHON)"
  (
    cd "$CHAQUOPY_PYPI_DIR"
    "$BUILDER_PYTHON" ./build-wheel.py --python "$PYTHON_VERSION" --abi "$abi" "$PACKAGE_NAME"
  )
done

echo "[build-impersonation-wheels] Collecting wheels"
find "$CHAQUOPY_PYPI_DIR" -type f -name 'curl_cffi-*.whl' -print0 | while IFS= read -r -d '' wheel; do
  cp -f "$wheel" "$WHEELS_DIR/"
done

wheel_count="$(find "$WHEELS_DIR" -maxdepth 1 -type f -name 'curl_cffi-*.whl' | wc -l | tr -d ' ')"
if [[ "$wheel_count" == "0" ]]; then
  echo "[build-impersonation-wheels] No curl_cffi wheels found after build"
  exit 1
fi

(
  cd "$WHEELS_DIR"
  # -printf is GNU find and -r is GNU xargs; neither exists on macOS. The leading ./ that
  # -printf '%P' removed is stripped with sed, and the loop skips an empty list by itself.
  find . -maxdepth 1 -type f -name '*.whl' | sed 's#^\./##' | sort | while IFS= read -r wheel_file; do
    "${SHA256SUM[@]}" "$wheel_file"
  done > SHA256SUMS
)

echo "[build-impersonation-wheels] Generated SHA256SUMS with $wheel_count wheel(s)"
echo "[build-impersonation-wheels] Next: bash scripts/verify-impersonation-wheels.sh"
