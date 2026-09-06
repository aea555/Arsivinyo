import importlib
import json
import os
import sys
import time
from typing import Any, Dict, Optional, Tuple


SCHEMA_VERSION = 1


def _clear_ytdlp_modules() -> None:
    for name in list(sys.modules.keys()):
        if name == "yt_dlp" or name.startswith("yt_dlp."):
            del sys.modules[name]


def _version_from_current_path() -> Optional[str]:
    module = importlib.import_module("yt_dlp.version")
    version = getattr(module, "__version__", None)
    return version if isinstance(version, str) and version else None


def _parse_stable_yt_dlp_version(version: Optional[str]) -> Optional[Tuple[int, int, int]]:
    if not isinstance(version, str):
        return None
    parts = version.strip().split(".")
    if len(parts) != 3:
        return None
    try:
        parsed = tuple(int(part) for part in parts)
    except ValueError:
        return None
    if parsed[0] < 1000 or parsed[1] < 1 or parsed[2] < 1:
        return None
    return parsed


def _versions_match(left: Optional[str], right: Optional[str]) -> bool:
    left_tuple = _parse_stable_yt_dlp_version(left)
    right_tuple = _parse_stable_yt_dlp_version(right)
    if left_tuple is not None and right_tuple is not None:
        return left_tuple == right_tuple
    if not isinstance(left, str) or not isinstance(right, str):
        return False
    return left.strip() == right.strip()


def _read_manifest(path: str) -> Dict[str, Any]:
    try:
        with open(path, "r", encoding="utf-8") as f:
            data = json.load(f)
        return data if isinstance(data, dict) else {}
    except Exception:
        return {}


def _write_manifest(path: str, manifest: Dict[str, Any]) -> None:
    parent = os.path.dirname(path)
    os.makedirs(parent, exist_ok=True)
    manifest["schemaVersion"] = SCHEMA_VERSION
    tmp_path = f"{path}.tmp"
    with open(tmp_path, "w", encoding="utf-8") as f:
        json.dump(manifest, f, sort_keys=True, separators=(",", ":"))
        f.flush()
        os.fsync(f.fileno())
    os.replace(tmp_path, path)


def _prepend_once(path: str) -> None:
    sys.path[:] = [item for item in sys.path if os.path.abspath(item) != os.path.abspath(path)]
    sys.path.insert(0, path)


def _remove_path(path: str) -> None:
    sys.path[:] = [item for item in sys.path if os.path.abspath(item) != os.path.abspath(path)]


def _version_dir(override_root: str, version: str) -> str:
    return os.path.join(override_root, "versions", version)


def activate(override_root: str, manifest_path: str) -> str:
    manifest = _read_manifest(manifest_path)
    manifest.setdefault("schemaVersion", SCHEMA_VERSION)
    manifest.setdefault("installed", {})

    _clear_ytdlp_modules()
    bundled_version = None
    try:
        bundled_version = _version_from_current_path()
    except Exception:
        bundled_version = None
    finally:
        _clear_ytdlp_modules()

    pending = manifest.get("pendingVersion")
    active = manifest.get("activeVersion")
    failed_version = manifest.get("failedVersion")
    failed_reason = manifest.get("failedReason")
    retry_failed = (
        not pending
        and not active
        and isinstance(failed_version, str)
        and failed_version
        and isinstance(failed_reason, str)
        and failed_reason.startswith("OVERRIDE_VERSION_MISMATCH:")
    )
    candidate = pending or active or (failed_version if retry_failed else None)
    source = "bundled"
    activated_version = bundled_version
    override_path = None

    if isinstance(candidate, str) and candidate:
        candidate_path = _version_dir(override_root, candidate)
        override_path = candidate_path
        try:
            if not os.path.isdir(candidate_path):
                raise RuntimeError("OVERRIDE_VERSION_DIR_MISSING")
            _prepend_once(candidate_path)
            _clear_ytdlp_modules()
            imported_version = _version_from_current_path()
            if not _versions_match(imported_version, candidate):
                raise RuntimeError(f"OVERRIDE_VERSION_MISMATCH:{imported_version or 'unknown'}")

            activated_version = imported_version
            source = "override"
            if pending == candidate or retry_failed:
                manifest["activeVersion"] = candidate
                manifest["pendingVersion"] = None
                manifest["failedVersion"] = None
                manifest["failedReason"] = None
                manifest["lastActivatedAt"] = int(time.time() * 1000)
                _write_manifest(manifest_path, manifest)
                failed_version = None
                failed_reason = None
        except Exception as exc:
            _remove_path(candidate_path)
            _clear_ytdlp_modules()
            failed_version = candidate
            failed_reason = str(exc)
            manifest["failedVersion"] = failed_version
            manifest["failedReason"] = failed_reason
            if pending == candidate:
                manifest["pendingVersion"] = None
            elif active == candidate:
                manifest["activeVersion"] = None
            _write_manifest(manifest_path, manifest)
            activated_version = bundled_version
            source = "bundled"
            override_path = None

    payload = {
        "source": source,
        "bundledVersion": bundled_version,
        "activeVersion": activated_version,
        "overrideVersion": activated_version if source == "override" else None,
        "manifestActiveVersion": manifest.get("activeVersion"),
        "pendingVersion": manifest.get("pendingVersion"),
        "failedVersion": failed_version,
        "failedReason": failed_reason,
        "overridePath": override_path,
    }
    return json.dumps(payload)
