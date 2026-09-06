"""JSON-lines host around the shared download engine.

The desktop app has no Chaquopy, so it talks to the engine the way any program talks to
another: a child process, JSON requests on stdin, JSON events on stdout, one object per
line. That keeps `local_downloader.py` untouched — it is the Android app's working engine
and is not worth risking for the sake of a nicer desktop boundary.

Progress is polled from the file the engine already writes. `_write_progress` performs an
atomic `os.replace`, so it cannot stream to a pipe; the poll happens here rather than in
the UI, which is the same arrangement `LocalDownloaderModule.kt` has today.

Requests:
    {"id": "1", "op": "preflight", "url": "...", ...}
    {"id": "2", "op": "download", "url": "...", "outputDir": "...", ...}
    {"id": "2", "op": "cancel"}                     # by the id of a running download
    {"id": "3", "op": "diagnostics"}

Events:
    {"id": "2", "type": "progress", "status": "downloading", "progressPercent": 12.5}
    {"id": "2", "type": "result", "ok": true, "result": {...}}
    {"id": "2", "type": "result", "ok": false, "error": "..."}
"""

from __future__ import annotations

import json
import os
import sys
import tempfile
import threading
import time
from typing import Any, Dict, Optional

PROGRESS_POLL_SEC = 0.25

# Imported lazily. `local_downloader` imports yt_dlp at module scope, and in a frozen
# build yt-dlp is deliberately NOT inside the binary — it lives in a directory that
# `bootstrap.py` puts on sys.path first, so that the updater can replace it. Importing
# the engine at module scope here would run before that setup and fail.
engine = None


def _engine():
    global engine
    if engine is None:
        import local_downloader
        engine = local_downloader
    return engine

_stdout_lock = threading.Lock()
_cancel_flags: Dict[str, str] = {}
_cancel_lock = threading.Lock()


def emit(payload: Dict[str, Any]) -> None:
    """One JSON object per line. Locked, because downloads run concurrently."""
    line = json.dumps(payload, ensure_ascii=False)
    with _stdout_lock:
        sys.stdout.write(line + "\n")
        sys.stdout.flush()


def _default_ffmpeg() -> Optional[str]:
    """The ffmpeg shipped beside this executable, as the app bundle lays it out."""
    root = os.path.dirname(sys.executable if getattr(sys, "frozen", False) else __file__)
    for name in ("ffmpeg.exe", "ffmpeg"):
        candidate = os.path.join(root, name)
        if os.path.isfile(candidate):
            return candidate
    return None


def _watch_progress(request_id: str, path: str, done: threading.Event) -> None:
    last: Optional[str] = None
    while not done.is_set():
        try:
            with open(path, "r", encoding="utf-8") as f:
                raw = f.read()
            if raw and raw != last:
                last = raw
                payload = json.loads(raw)
                payload.update({"id": request_id, "type": "progress"})
                emit(payload)
        except Exception:
            # A missing or half-written file is normal; the next poll picks it up.
            pass
        done.wait(PROGRESS_POLL_SEC)


def _run_download(request_id: str, req: Dict[str, Any]) -> None:
    work = tempfile.mkdtemp(prefix="arsivinyo-")
    progress_path = os.path.join(work, "progress.json")
    cancel_path = os.path.join(work, "cancel.flag")
    with _cancel_lock:
        _cancel_flags[request_id] = cancel_path

    done = threading.Event()
    watcher = threading.Thread(target=_watch_progress, args=(request_id, progress_path, done), daemon=True)
    watcher.start()
    try:
        raw = _engine().run_download(
            url=req["url"],
            output_dir=req["outputDir"],
            cookies_dir=req.get("cookiesDir") or work,
            cookie_profile=req.get("cookieProfile"),
            max_file_size_mb=int(req.get("maxFileSizeMb") or 0),
            cancel_flag_path=cancel_path,
            progress_file_path=progress_path,
            ffmpeg_path=req.get("ffmpegPath") or _default_ffmpeg(),
            audio_only=bool(req.get("audioOnly")),
            audio_format=req.get("audioFormat") or _engine().DEFAULT_AUDIO_FORMAT,
            debug_logging=bool(req.get("debugLogging")),
        )
        emit({"id": request_id, "type": "result", "ok": True, "result": json.loads(raw)})
    except Exception as exc:
        emit({"id": request_id, "type": "result", "ok": False, "error": f"{type(exc).__name__}: {exc}"})
    finally:
        done.set()
        with _cancel_lock:
            _cancel_flags.pop(request_id, None)


def _run_preflight(request_id: str, req: Dict[str, Any]) -> None:
    work = tempfile.mkdtemp(prefix="arsivinyo-")
    try:
        raw = _engine().preflight(
            url=req["url"],
            cookies_dir=req.get("cookiesDir") or work,
            cookie_profile=req.get("cookieProfile"),
            max_file_size_mb=int(req.get("maxFileSizeMb") or 0),
            ffmpeg_path=req.get("ffmpegPath") or _default_ffmpeg(),
            debug_logging=bool(req.get("debugLogging")),
        )
        emit({"id": request_id, "type": "result", "ok": True, "result": json.loads(raw)})
    except Exception as exc:
        emit({"id": request_id, "type": "result", "ok": False, "error": f"{type(exc).__name__}: {exc}"})


def _handle(req: Dict[str, Any]) -> None:
    request_id = str(req.get("id", ""))
    op = req.get("op")

    if op == "download":
        threading.Thread(target=_run_download, args=(request_id, req), daemon=True).start()
    elif op == "preflight":
        threading.Thread(target=_run_preflight, args=(request_id, req), daemon=True).start()
    elif op == "cancel":
        with _cancel_lock:
            path = _cancel_flags.get(request_id)
        if path:
            try:
                open(path, "w").close()
                emit({"id": request_id, "type": "cancelling"})
            except Exception as exc:
                emit({"id": request_id, "type": "result", "ok": False, "error": str(exc)})
        else:
            emit({"id": request_id, "type": "result", "ok": False, "error": "NO_SUCH_TRANSFER"})
    elif op == "diagnostics":
        emit({"id": request_id, "type": "result", "ok": True, "result": json.loads(_engine().get_runtime_diagnostics())})
    elif op == "version":
        import yt_dlp  # resolved through sys.path, never frozen in — see freeze.py
        emit({"id": request_id, "type": "result", "ok": True,
              "result": {"ytDlp": yt_dlp.version.__version__, "frozen": bool(getattr(sys, "frozen", False))}})
    else:
        emit({"id": request_id, "type": "result", "ok": False, "error": f"UNKNOWN_OP:{op}"})


def main() -> int:
    emit({"type": "ready", "frozen": bool(getattr(sys, "frozen", False))})
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            req = json.loads(line)
        except Exception as exc:
            emit({"type": "result", "ok": False, "error": f"BAD_REQUEST: {exc}"})
            continue
        try:
            _handle(req)
        except Exception as exc:
            emit({"id": str(req.get("id", "")), "type": "result", "ok": False,
                  "error": f"{type(exc).__name__}: {exc}"})
    return 0


if __name__ == "__main__":
    sys.exit(main())
