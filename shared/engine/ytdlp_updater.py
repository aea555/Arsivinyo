"""Fetching yt-dlp, for bundling it and for updating it in place.

The phone updates yt-dlp without shipping a new app, because an extractor breaks far more
often than the app around it does. The desktop has to do the same or it is a worse product
that happens to run on a bigger screen.

The layout this writes into is the one `yt_dlp_override_bootstrap` already reads:

    yt-dlp/                      the bundled copy, replaceable
    yt-dlp-overrides/
        manifest.json            pendingVersion / activeVersion / failedVersion
        versions/<version>/      an unpacked wheel, put on sys.path ahead of the bundle

A wheel is a zip, so no build tooling is needed to unpack one — which matters because this
runs inside a frozen executable that has no pip and no compiler.
"""

from __future__ import annotations

import io
import json
import os
import shutil
import tempfile
import urllib.request
import zipfile
from typing import Callable, Dict, Optional, Tuple

PYPI_URL = "https://pypi.org/pypi/yt-dlp/json"
USER_AGENT = "Arsivinyo/1.0 (+https://github.com/aea555/Arsivinyo)"

ProgressFn = Callable[[str, int, int], None]


def _request(url: str) -> urllib.request.Request:
    return urllib.request.Request(url, headers={"User-Agent": USER_AGENT})


def _wheel_of(files: list) -> Optional[Tuple[str, int]]:
    """The pure-Python wheel: nothing to compile, works everywhere this ships."""
    for candidate in files:
        if candidate.get("packagetype") == "bdist_wheel" and str(
            candidate.get("filename", "")
        ).endswith("-py3-none-any.whl"):
            return candidate["url"], int(candidate.get("size") or 0)
    return None


def _is_stable(version: str) -> bool:
    """yt-dlp releases are YYYY.MM.DD. Anything else is a pre-release or an oddity."""
    parts = version.split(".")
    return len(parts) == 3 and all(part.isdigit() for part in parts) and len(parts[0]) == 4


def _sort_key(version: str) -> Tuple[int, int, int]:
    return tuple(int(part) for part in version.split("."))  # type: ignore[return-value]


def latest_release(timeout: float = 30.0) -> Tuple[str, str, int]:
    """@return (version, wheel url, size in bytes) of the newest yt-dlp on PyPI."""
    with urllib.request.urlopen(_request(PYPI_URL), timeout=timeout) as response:
        payload = json.load(response)

    version = payload["info"]["version"]
    wheel = _wheel_of(payload["urls"])
    if not wheel:
        raise RuntimeError("no pure-python wheel published for yt-dlp " + version)
    return version, wheel[0], wheel[1]


def list_releases(limit: int = 12, timeout: float = 30.0) -> list:
    """Recent stable releases, newest first.

    Capped because yt-dlp has hundreds of them and a list that long is not a choice, it
    is a wall of numbers.
    """
    with urllib.request.urlopen(_request(PYPI_URL), timeout=timeout) as response:
        payload = json.load(response)

    versions = [v for v in payload.get("releases", {}) if _is_stable(v)]
    versions.sort(key=_sort_key, reverse=True)

    out = []
    for version in versions[:limit]:
        wheel = _wheel_of(payload["releases"][version])
        if wheel:
            out.append({"version": version, "url": wheel[0], "size": wheel[1]})
    return out


def release(version: str, timeout: float = 30.0) -> Tuple[str, str, int]:
    """@return (version, wheel url, size) for one specific release."""
    with urllib.request.urlopen(_request(PYPI_URL), timeout=timeout) as response:
        payload = json.load(response)
    files = payload.get("releases", {}).get(version)
    if not files:
        raise RuntimeError(f"yt-dlp {version} is not on PyPI")
    wheel = _wheel_of(files)
    if not wheel:
        raise RuntimeError(f"no pure-python wheel published for yt-dlp {version}")
    return version, wheel[0], wheel[1]


def download_wheel(url: str, expected_size: int = 0, timeout: float = 60.0,
                   progress: Optional[ProgressFn] = None) -> bytes:
    chunks, read = [], 0
    with urllib.request.urlopen(_request(url), timeout=timeout) as response:
        total = int(response.headers.get("Content-Length") or expected_size or 0)
        while True:
            chunk = response.read(64 * 1024)
            if not chunk:
                break
            chunks.append(chunk)
            read += len(chunk)
            if progress:
                progress("downloading", read, total)
    return b"".join(chunks)


def unpack_wheel(data: bytes, target: str) -> None:
    """Replace [target] with the wheel's contents, atomically enough to survive a crash."""
    parent = os.path.dirname(os.path.abspath(target)) or "."
    os.makedirs(parent, exist_ok=True)

    staging = tempfile.mkdtemp(prefix=".yt-dlp-", dir=parent)
    try:
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            # A zip entry can name a path outside the target; refuse rather than write it.
            for entry in archive.namelist():
                resolved = os.path.normpath(os.path.join(staging, entry))
                if not resolved.startswith(os.path.abspath(staging) + os.sep):
                    raise RuntimeError(f"refusing an archive entry outside the target: {entry}")
            archive.extractall(staging)

        if not os.path.isdir(os.path.join(staging, "yt_dlp")):
            raise RuntimeError("the wheel contained no yt_dlp package")

        previous = target + ".previous"
        shutil.rmtree(previous, ignore_errors=True)
        if os.path.exists(target):
            os.replace(target, previous)
        os.replace(staging, target)
        shutil.rmtree(previous, ignore_errors=True)
        staging = None
    finally:
        if staging:
            shutil.rmtree(staging, ignore_errors=True)


def read_manifest(path: str) -> Dict:
    try:
        with open(path, "r", encoding="utf-8") as handle:
            loaded = json.load(handle)
        return loaded if isinstance(loaded, dict) else {}
    except Exception:
        return {}


def write_manifest(path: str, manifest: Dict) -> None:
    os.makedirs(os.path.dirname(os.path.abspath(path)), exist_ok=True)
    temp = path + ".tmp"
    with open(temp, "w", encoding="utf-8") as handle:
        json.dump(manifest, handle, indent=2)
    os.replace(temp, path)


def _prune_versions(override_root: str, keep: str) -> None:
    """Keep exactly one downloaded version.

    Without this every switch leaks an unpacked copy — thirteen megabytes a time, never
    reclaimed. One slot is enough: the copy the app shipped with is still on disk as a
    floor, and any other version is a download away.
    """
    versions_dir = os.path.join(override_root, "versions")
    if not os.path.isdir(versions_dir):
        return
    for name in os.listdir(versions_dir):
        if name == keep:
            continue
        shutil.rmtree(os.path.join(versions_dir, name), ignore_errors=True)


def install_override(root: str, version: Optional[str] = None,
                     progress: Optional[ProgressFn] = None) -> Dict:
    """Download a yt-dlp and queue it for the next start.

    Queued rather than swapped in: yt-dlp is already imported by the time anyone asks for
    a change, and rebinding a live module is a good way to get a process that half works.
    The engine restarts and `yt_dlp_override_bootstrap` picks it up.

    [version] of None means the newest on PyPI.
    """
    override_root = os.path.join(root, "yt-dlp-overrides")
    manifest_path = os.path.join(override_root, "manifest.json")
    manifest = read_manifest(manifest_path)

    if progress:
        progress("checking", 0, 0)
    wanted, url, size = release(version) if version else latest_release()

    if manifest.get("activeVersion") == wanted and os.path.isdir(
        os.path.join(override_root, "versions", wanted)
    ):
        return {"status": "current", "version": wanted}

    target = os.path.join(override_root, "versions", wanted)
    if not os.path.isdir(target):
        data = download_wheel(url, size, progress=progress)
        if progress:
            progress("installing", len(data), len(data))
        unpack_wheel(data, target)

    manifest["schemaVersion"] = manifest.get("schemaVersion", 1)
    manifest["pendingVersion"] = wanted
    # A previous failure must not veto a newly chosen version.
    manifest["failedVersion"] = None
    manifest["failedReason"] = None
    write_manifest(manifest_path, manifest)
    _prune_versions(override_root, wanted)
    return {"status": "pending", "version": wanted}


def use_bundled(root: str) -> Dict:
    """Drop the override entirely and go back to the copy the app shipped with."""
    override_root = os.path.join(root, "yt-dlp-overrides")
    manifest_path = os.path.join(override_root, "manifest.json")
    manifest = read_manifest(manifest_path)
    manifest["schemaVersion"] = manifest.get("schemaVersion", 1)
    manifest["pendingVersion"] = None
    manifest["activeVersion"] = None
    manifest["failedVersion"] = None
    manifest["failedReason"] = None
    write_manifest(manifest_path, manifest)
    _prune_versions(override_root, keep="")
    return {"status": "bundled"}


def install_bundled(root: str, progress: Optional[ProgressFn] = None) -> Dict:
    """Fill in the `yt-dlp/` directory the app ships with."""
    version, url, size = latest_release()
    data = download_wheel(url, size, progress=progress)
    unpack_wheel(data, os.path.join(root, "yt-dlp"))
    return {"status": "bundled", "version": version}


if __name__ == "__main__":
    import sys

    here = os.path.dirname(os.path.abspath(__file__))
    where = sys.argv[1] if len(sys.argv) > 1 else here

    def show(stage: str, done: int, total: int) -> None:
        if total:
            sys.stderr.write(f"\r{stage}: {done * 100 // total}%   ")
        else:
            sys.stderr.write(f"\r{stage}…   ")
        sys.stderr.flush()

    result = install_bundled(where, show)
    sys.stderr.write("\n")
    print(json.dumps(result))
