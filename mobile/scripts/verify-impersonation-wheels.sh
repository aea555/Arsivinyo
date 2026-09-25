#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
WHEELS_DIR="$ROOT_DIR/modules/local-downloader/android/chaquopy-wheels"
VERSIONS_FILE="$WHEELS_DIR/VERSIONS.json"
CHECKSUM_FILE="$WHEELS_DIR/SHA256SUMS"

command -v unzip >/dev/null 2>&1 || {
  echo "[verify-impersonation-wheels] unzip command is required"
  exit 1
}

if [[ ! -d "$WHEELS_DIR" ]]; then
  echo "[verify-impersonation-wheels] Missing wheels directory: $WHEELS_DIR"
  exit 1
fi

if [[ ! -f "$VERSIONS_FILE" ]]; then
  echo "[verify-impersonation-wheels] Missing versions file: $VERSIONS_FILE"
  exit 1
fi

if [[ ! -f "$CHECKSUM_FILE" ]]; then
  echo "[verify-impersonation-wheels] Missing checksum file: $CHECKSUM_FILE"
  exit 1
fi

resolve_required_abis() {
  if [[ -n "${ABI_LIST:-}" ]]; then
    echo "$ABI_LIST"
    return
  fi
  if command -v python3 >/dev/null 2>&1; then
    local parsed
    parsed="$(python3 - <<PY 2>/dev/null
import json
from pathlib import Path
path = Path("$VERSIONS_FILE")
try:
    data = json.loads(path.read_text())
except Exception:
    print("")
    raise SystemExit(0)
abis = data.get("abiCoverage") or []
print(" ".join([str(x) for x in abis]))
PY
)"
    if [[ -n "$parsed" ]]; then
      echo "$parsed"
      return
    fi
  fi
  echo "arm64-v8a"
}

ABI_LIST_RAW="$(resolve_required_abis)"
read -r -a required_abis <<< "$ABI_LIST_RAW"

# Three GNU-isms replaced at once: mapfile is bash 4, -printf is GNU find, and macOS has
# neither. Stripping the directory with sed is what -printf '%f' was doing.
wheels=()
while IFS= read -r wheel_file; do
  wheels+=("$wheel_file")
done < <(find "$WHEELS_DIR" -maxdepth 1 -type f -name '*.whl' | sed 's#.*/##' | sort)
if [[ "${#wheels[@]}" -eq 0 ]]; then
  echo "[verify-impersonation-wheels] No wheel files found in $WHEELS_DIR"
  exit 1
fi

if ! grep -Eq '^[0-9a-f]{64}\s+.+\.whl$' "$CHECKSUM_FILE"; then
  echo "[verify-impersonation-wheels] SHA256SUMS has no valid wheel checksum entries"
  exit 1
fi

(
  cd "$WHEELS_DIR"
  # macOS has shasum rather than sha256sum. Both understand -c against the same file.
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum -c SHA256SUMS
  else
    shasum -a 256 -c SHA256SUMS
  fi
)

for abi in "${required_abis[@]}"; do
  abi_token="${abi//-/_}"
  if ! printf '%s\n' "${wheels[@]}" | grep -Eq "^curl_cffi-.*android.*${abi_token}.*\\.whl$"; then
    echo "[verify-impersonation-wheels] Missing curl_cffi Android wheel for ABI token: $abi_token"
    exit 1
  fi
done

for wheel in "${wheels[@]}"; do
  if [[ "$wheel" == curl_cffi-* ]] && [[ "$wheel" =~ (manylinux|macosx|win_amd64|win32) ]]; then
    echo "[verify-impersonation-wheels] Non-Android curl_cffi wheel found: $wheel"
    exit 1
  fi
  if [[ "$wheel" == curl_cffi-* ]]; then
    # Read the listing first, then test it. Piping straight into `grep -q` under
    # `pipefail` made one condition cover two very different failures: a wheel that
    # genuinely lacks the package, and unzip failing to run at all. The second happened
    # transiently after a --clean prebuild and was reported as a corrupt wheel, which
    # sent two investigations after the wrong thing while the checksum above passed.
    if ! listing="$(unzip -l "$WHEELS_DIR/$wheel" 2>&1)"; then
      echo "[verify-impersonation-wheels] Could not read wheel (unzip failed): $wheel"
      echo "$listing"
      exit 1
    fi
    if [[ "$listing" != *"curl_cffi/__init__.py"* ]]; then
      echo "[verify-impersonation-wheels] Wheel missing curl_cffi package files: $wheel"
      exit 1
    fi
  fi
done

echo "[verify-impersonation-wheels] Wheel verification passed (${#wheels[@]} wheel files, required_abis=${ABI_LIST_RAW})"
