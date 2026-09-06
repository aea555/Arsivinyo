import datetime
import concurrent.futures
import html
import importlib
import json
import os
import random
import re
import subprocess
import threading
import time
import uuid
from typing import Any, Dict, List, Optional, Tuple
from urllib.parse import urljoin, urlparse, urlunparse
from urllib.request import Request, urlopen

import yt_dlp

COOKIE_PLATFORMS = {
    "twitter": ["twitter.com", "x.com"],
    "instagram": ["instagram.com"],
    "facebook": ["facebook.com", "fb.watch"],
    "reddit": ["reddit.com", "v.redd.it"],
    "youtube": ["youtube.com", "youtu.be"],
    "tiktok": ["tiktok.com", "vm.tiktok.com"],
}

DEFAULT_HTTP_USER_AGENT = (
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/133.0.0.0 Mobile Safari/537.36"
)

RETRYABLE_STATUS_MARKERS = (
    "http error 403",
    "http error 429",
    "http error 500",
    "http error 502",
    "http error 503",
    "http error 504",
    "temporarily unavailable",
    "timed out",
    "connection reset",
    "network is unreachable",
    "downloaded file is empty",
    "remote end closed connection",
    "incomplete read",
    "incompleteread",
    "transporterror",
    "connection aborted",
    "connection closed",
    "tls",
    "ssl",
)

VIDEO_TIMESTAMP_EXTENSIONS = {".mp4", ".mov", ".m4v", ".3gp"}
# Audio download formats. FLAC is the default because every source we fetch is already
# lossy; see _apply_audio_postprocessing for the full reasoning. These must stay at
# module scope (not next to that function) because they are used as default argument
# values further up the file, which Python evaluates at import time.
AUDIO_FORMAT_FLAC = "flac"
AUDIO_FORMAT_M4A = "m4a"
DEFAULT_AUDIO_FORMAT = AUDIO_FORMAT_FLAC
SUPPORTED_AUDIO_FORMATS = (AUDIO_FORMAT_FLAC, AUDIO_FORMAT_M4A)
GENERIC_UNSUPPORTED_MARKERS = (
    "unsupported url",
    "no video formats found",
    "unable to extract",
)
GENERIC_MEDIA_EXTENSIONS = (".m3u8", ".mpd", ".mp4", ".m4v", ".mov", ".webm", ".mkv", ".ts")
GENERIC_URL_TOKEN_PATTERN = re.compile(r"(?:(?:https?:)?//[^\s\"'<>\\]+)", flags=re.IGNORECASE)
GENERIC_MEDIA_RELATIVE_PATTERN = re.compile(
    r"(?P<path>/(?:[^\"'<>\\\s]|\\.)+\.(?:m3u8|mpd|mp4|m4v|mov|webm|mkv|ts)(?:\?[^\"'<>\\\s]*)?)",
    flags=re.IGNORECASE,
)
MAX_GENERIC_DISCOVERY_CANDIDATES = 4
MAX_ATTEMPT_TRACE = 80
MAX_TOOL_OUTPUT_CHARS = 6000
EXTRACT_INFO_TIMEOUT_SEC = 45
KNOWN_PREFLIGHT_BUDGET_SEC = 45
GENERIC_PREFLIGHT_BUDGET_SEC = 25
GENERIC_PREFLIGHT_EXTRACT_TIMEOUT_SEC = 8
GENERIC_PREFLIGHT_ATTEMPT_LIMIT = 2
STATIC_PAGE_SAMPLE_BYTES = 768 * 1024
STATIC_PAGE_FETCH_TIMEOUT_SEC = 8
ALLOW_REDDIT_PUBLIC_FALLBACK = True
YTDLP_VERBOSE_DEV = os.getenv("ARSIVINYO_YTDLP_VERBOSE_DEV", "").strip().lower() in {"1", "true", "yes", "on"}
PROGRESS_WRITE_MIN_INTERVAL_MS = 500
PROGRESS_WRITE_MIN_DELTA_PERCENT = 0.5
YOUTUBE_HTTP_CHUNK_SIZE_PRIMARY = 10 * 1024 * 1024
YOUTUBE_HTTP_CHUNK_SIZE_FALLBACK = 4 * 1024 * 1024
YOUTUBE_THROTTLED_RATE_LIMIT = 350 * 1024
TIKTOK_APP_INFO_CANDIDATES = [
    ("musical_ly", "35.1.3", "2023501030", "0"),
    ("trill", "35.1.3", "2023501030", "1180"),
    ("aweme", "35.1.3", "2023501030", "1128"),
]
TIKTOK_API_HOSTNAME_CANDIDATES = [
    "api22-normal-c-alisg.tiktokv.com",
    "api16-normal-c-useast1a.tiktokv.com",
]
IMP_ABI_COVERAGE = ["arm64-v8a"]
IMPERSONATION_BACKEND_NAME = "curl_cffi"
IMPERSONATION_DEPENDENCY_PIN = "0.14.0"
DAILYMOTION_DOMAINS = {"dailymotion.com", "www.dailymotion.com", "dai.ly"}
IMPERSONATION_TARGET_MATRIX = {
    "dailymotion": ["firefox", "chrome", "edge"],
    "tiktok": ["chrome", "firefox"],
    "reddit": ["chrome"],
}
IMPERSONATION_UNAVAILABLE_MARKERS = (
    "impersonate target",
    "no impersonate target is available",
    "none of these impersonate targets are available",
)
IMPERSONATION_DISABLE_EXTRACTOR_ARGS: Dict[str, Dict[str, List[str]]] = {
    "generic": {"impersonate": ["false"]},
    "dailymotion": {"impersonate": ["false"]},
    "tiktok": {"impersonate": ["false"]},
}
REDDIT_DOMAINS = {"reddit.com", "v.redd.it", "redd.it"}
TIKTOK_SHORT_DOMAINS = {"vm.tiktok.com", "vt.tiktok.com"}
COOKIE_PLATFORM_DOMAINS = {
    "reddit": {"reddit.com", "redd.it", "v.redd.it"},
    "tiktok": {"tiktok.com", "vm.tiktok.com"},
}
URL_REDACTION_PATTERN = re.compile(r"/(?:data|storage|sdcard)/[^\s)]+", flags=re.IGNORECASE)
TOKEN_REDACTION_PATTERN = re.compile(r"(po_token|visitor_data|authorization)=([^,&\\s]+)", flags=re.IGNORECASE)
REDDIT_SHARE_PATH_PATTERN = re.compile(r"/r/[^/]+/s/[^/?#]+", flags=re.IGNORECASE)
GENERIC_FAILURE_CODES = {"PREFLIGHT_FAILED", "DOWNLOAD_FAILED"}
SOFT_REDDIT_SHARE_RESOLUTION_MARKERS = (
    "http error 403",
    "http error 429",
    "http error 500",
    "http error 502",
    "http error 503",
    "http error 504",
    "timed out",
    "timeout",
    "temporarily unavailable",
    "connection reset",
    "network is unreachable",
    "temporary failure in name resolution",
)
HARD_PREFLIGHT_CODES = {
    "INVALID_URL",
    "UNSUPPORTED_PLATFORM",
    "FILE_TOO_LARGE",
    "SERVER_BUSY",
    "DOWNLOAD_CANCELLED",
    "TASK_CANCELLED",
    "COOKIE_DOMAIN_MISMATCH",
    "COOKIE_EMPTY_OR_EXPIRED",
    "COOKIE_STALE_OR_INVALID",
    "REDDIT_COOKIE_REQUIRED",
    "MERGE_DEPENDENCY_MISSING",
    "FFMPEG_NATIVE_RUNTIME_UNAVAILABLE",
    "FFMPEG_MISSING",
    "FFPROBE_MISSING",
}
RETRYABLE_PREFLIGHT_MARKERS = (
    "remote end closed connection",
    "connection reset",
    "connection aborted",
    "connection closed",
    "incomplete read",
    "incompleteread",
    "transporterror",
    "timed out",
    "timeout",
    "http error 429",
    "http error 500",
    "http error 502",
    "http error 503",
    "http error 504",
    "temporarily unavailable",
    "temporary failure in name resolution",
    "network is unreachable",
    "tls",
    "ssl",
    "extractor error",
    "keyerror(",
    "static_page_no_media_candidates",
    "static_page_fetch_failed",
    "preflight_budget_exhausted",
    "preflight budget exhausted",
)
STATIC_MEDIA_MARKERS = (
    ".m3u8",
    ".mpd",
    ".mp4",
    ".m4v",
    ".webm",
    "<video",
    "<source",
    "<iframe",
    "embed",
    "player",
    "data-src",
    "data-video",
    "jwplayer",
    "videojs",
    "hls",
)
SPEED_PER_SEC_PATTERN = re.compile(r"(?P<value>\d+(?:\.\d+)?)\s*(?P<unit>[kmg]?i?b)\s*/\s*s", flags=re.IGNORECASE)

# Diagnostics that describe the process rather than any one download: whether the
# impersonation backend loaded, which wheel it came from, how old yt-dlp is. These are
# shared, because they are the same answer for every caller.
_PROCESS_DIAG_KEYS = frozenset(
    {
        "ytDlpVersionAgeDays",
        "impersonationRuntimeAvailable",
        "impersonationEnabled",
        "impersonationBackend",
        "impersonationWheelVersion",
        "impersonationBuildAbiCoverage",
        "impersonationBootstrapError",
    }
)


def _new_call_diagnostics() -> Dict[str, Any]:
    """Per-download diagnostic state, reset at the start of every entry point."""
    return {
        "normalizedUrlLast": None,
        "attemptTrace": [],
        "lastExtractorKey": None,
        "lastRawYtDlpError": None,
        "lastCookieCheck": None,
        "platformStrategyLast": None,
        "impersonationRequiredByExtractorLast": None,
        "impersonationAttemptedTargetsLast": [],
        "impersonationResolvedTargetLast": None,
        "redditShareResolutionLast": None,
        "progressWritesLast": 0,
        "youtubeChunkProfileLast": None,
        "formatSelectorLast": None,
        "toolOutputLast": None,
        "preflightBudgetSec": None,
        "preflightElapsedMs": None,
        "preflightAttemptLimit": None,
        "staticMediaCandidateCount": None,
        "knownExtractorLast": None,
    }


class _RuntimeDiagnostics:
    """A dict-shaped view over diagnostics that keeps concurrent downloads apart.

    Every value here used to live in one module-level dict, and ``_result`` folds several
    of them into the payload returned to Kotlin. With one download at a time that was
    safe. With several, the last writer would win and a download could return another
    download's normalized url, attempt trace or tool output, which Kotlin then records
    against the wrong task. Worse, starting a second download reset the trace of the first.

    So per-download values are held per thread — Chaquopy calls arrive on the calling Java
    thread, so a thread is a download. Process-wide values stay shared, and a thread that
    never ran a download (the diagnostics screen asking ``get_runtime_diagnostics``) reads
    the most recently started one, which is what that screen has always shown.

    It deliberately keeps a mapping's interface so the ~60 existing call sites need no
    change.
    """

    def __init__(self) -> None:
        self._process: Dict[str, Any] = {
            "ytDlpVersionAgeDays": None,
            "impersonationRuntimeAvailable": None,
            "impersonationEnabled": False,
            "impersonationBackend": "none",
            "impersonationWheelVersion": None,
            "impersonationBuildAbiCoverage": IMP_ABI_COVERAGE,
            "impersonationBootstrapError": None,
        }
        self._local = threading.local()
        self._last: Dict[str, Any] = _new_call_diagnostics()
        self._lock = threading.Lock()

    def begin_call(self) -> None:
        """Give the calling thread a fresh set, and make it the one the screen reports."""
        fresh = _new_call_diagnostics()
        self._local.data = fresh
        with self._lock:
            self._last = fresh

    def _call_scope(self, for_write: bool) -> Dict[str, Any]:
        current = getattr(self._local, "data", None)
        if current is not None:
            return current
        if for_write:
            current = _new_call_diagnostics()
            self._local.data = current
            with self._lock:
                self._last = current
            return current
        # No download on this thread: report the newest one rather than nothing.
        with self._lock:
            return self._last

    def get(self, key: str, default: Any = None) -> Any:
        if key in _PROCESS_DIAG_KEYS:
            return self._process.get(key, default)
        return self._call_scope(for_write=False).get(key, default)

    def __getitem__(self, key: str) -> Any:
        return self.get(key)

    def __setitem__(self, key: str, value: Any) -> None:
        if key in _PROCESS_DIAG_KEYS:
            self._process[key] = value
        else:
            self._call_scope(for_write=True)[key] = value

    def setdefault(self, key: str, default: Any) -> Any:
        if key in _PROCESS_DIAG_KEYS:
            return self._process.setdefault(key, default)
        return self._call_scope(for_write=True).setdefault(key, default)


_RUNTIME_DIAGNOSTICS = _RuntimeDiagnostics()
_IMPERSONATION_RUNTIME_AVAILABLE: Optional[bool] = None
_KNOWN_EXTRACTOR_CACHE: Dict[str, Optional[str]] = {}


def _now_ms() -> int:
    return int(time.time() * 1000)


def _monotonic_ms() -> int:
    return int(time.monotonic() * 1000)


def _redact_text(value: str) -> str:
    redacted = URL_REDACTION_PATTERN.sub("<redacted-path>", value or "")
    return TOKEN_REDACTION_PATTERN.sub(r"\1=<redacted>", redacted)


def _begin_call_diagnostics() -> None:
    """Start a download's diagnostics from scratch, isolated from any other in flight.

    Called first thing in every entry point. It used to clear a shared dict key by key,
    which meant a second download wiped the first one's trace mid-flight.
    """
    _RUNTIME_DIAGNOSTICS.begin_call()


def _push_attempt_trace(entry: Dict[str, Any]) -> None:
    trace = _RUNTIME_DIAGNOSTICS.setdefault("attemptTrace", [])
    trace.append(entry)
    if len(trace) > MAX_ATTEMPT_TRACE:
        del trace[:-MAX_ATTEMPT_TRACE]


def _set_runtime_diag(key: str, value: Any) -> None:
    _RUNTIME_DIAGNOSTICS[key] = value


def _append_diag_target(target: Optional[str]) -> None:
    if not target:
        return
    current = list(_RUNTIME_DIAGNOSTICS.get("impersonationAttemptedTargetsLast") or [])
    if target not in current:
        current.append(target)
    _set_runtime_diag("impersonationAttemptedTargetsLast", current)


def _begin_preflight_profile(platform: Optional[str]) -> Tuple[int, int, Optional[int], int]:
    if platform is None:
        budget_sec = GENERIC_PREFLIGHT_BUDGET_SEC
        attempt_limit: Optional[int] = GENERIC_PREFLIGHT_ATTEMPT_LIMIT
        per_attempt_timeout_sec = GENERIC_PREFLIGHT_EXTRACT_TIMEOUT_SEC
    else:
        budget_sec = KNOWN_PREFLIGHT_BUDGET_SEC
        attempt_limit = None
        per_attempt_timeout_sec = EXTRACT_INFO_TIMEOUT_SEC

    start_ms = _monotonic_ms()
    _set_runtime_diag("preflightBudgetSec", budget_sec)
    _set_runtime_diag("preflightAttemptLimit", attempt_limit)
    _set_runtime_diag("preflightElapsedMs", 0)
    return start_ms, start_ms + (budget_sec * 1000), attempt_limit, per_attempt_timeout_sec


def _finish_preflight_profile(start_ms: Optional[int]) -> None:
    if start_ms is None:
        return
    _set_runtime_diag("preflightElapsedMs", max(0, _monotonic_ms() - start_ms))


def _is_impersonation_unavailable_message(message: str) -> bool:
    lower = (message or "").lower()
    return any(marker in lower for marker in IMPERSONATION_UNAVAILABLE_MARKERS)


def _extract_required_targets_from_message(message: str) -> List[str]:
    text = message or ""
    match = re.search(r"available:\s*([a-z0-9_,\-\s]+)", text, flags=re.IGNORECASE)
    if not match:
        return []
    raw = match.group(1).strip().rstrip(".")
    return [item.strip().lower() for item in raw.split(",") if item.strip()]


def _bootstrap_impersonation_runtime(debug_logging: bool = False) -> bool:
    global _IMPERSONATION_RUNTIME_AVAILABLE
    if _IMPERSONATION_RUNTIME_AVAILABLE is not None:
        return _IMPERSONATION_RUNTIME_AVAILABLE

    _set_runtime_diag("impersonationAttemptedTargetsLast", [])
    _set_runtime_diag("impersonationResolvedTargetLast", None)
    _set_runtime_diag("impersonationRequiredByExtractorLast", None)

    try:
        module = importlib.import_module(IMPERSONATION_BACKEND_NAME)
    except ModuleNotFoundError as exc:
        _IMPERSONATION_RUNTIME_AVAILABLE = False
        _set_runtime_diag("impersonationRuntimeAvailable", False)
        _set_runtime_diag("impersonationEnabled", False)
        _set_runtime_diag("impersonationBackend", "none")
        _set_runtime_diag("impersonationWheelVersion", IMPERSONATION_DEPENDENCY_PIN)
        _set_runtime_diag("impersonationBootstrapError", f"IMPERSONATION_DEPENDENCY_MISSING: {exc}")
        _debug_log(debug_logging, "IMP_BOOTSTRAP_FAIL code=IMPERSONATION_DEPENDENCY_MISSING reason=module-not-found")
        return False
    except Exception as exc:
        _IMPERSONATION_RUNTIME_AVAILABLE = False
        _set_runtime_diag("impersonationRuntimeAvailable", False)
        _set_runtime_diag("impersonationEnabled", False)
        _set_runtime_diag("impersonationBackend", "none")
        _set_runtime_diag("impersonationWheelVersion", None)
        _set_runtime_diag("impersonationBootstrapError", f"IMPERSONATION_BOOTSTRAP_FAILED: {exc}")
        _debug_log(debug_logging, f"IMP_BOOTSTRAP_FAIL code=IMPERSONATION_BOOTSTRAP_FAILED reason={exc}")
        return False

    try:
        from curl_cffi import Curl

        curl = Curl()
        curl.close()
        version = str(getattr(module, "__version__", "")).strip() or None
        _IMPERSONATION_RUNTIME_AVAILABLE = True
        _set_runtime_diag("impersonationRuntimeAvailable", True)
        _set_runtime_diag("impersonationEnabled", True)
        _set_runtime_diag("impersonationBackend", IMPERSONATION_BACKEND_NAME)
        _set_runtime_diag("impersonationWheelVersion", version)
        _set_runtime_diag("impersonationBootstrapError", None)
        _debug_log(
            debug_logging,
            f"IMP_BOOTSTRAP_OK backend={IMPERSONATION_BACKEND_NAME} version={version or 'unknown'}",
        )
        return True
    except Exception as exc:
        _IMPERSONATION_RUNTIME_AVAILABLE = False
        _set_runtime_diag("impersonationRuntimeAvailable", False)
        _set_runtime_diag("impersonationEnabled", False)
        _set_runtime_diag("impersonationBackend", "none")
        _set_runtime_diag("impersonationWheelVersion", None)
        _set_runtime_diag("impersonationBootstrapError", f"IMPERSONATION_BOOTSTRAP_FAILED: {exc}")
        _debug_log(debug_logging, f"IMP_BOOTSTRAP_FAIL code=IMPERSONATION_BOOTSTRAP_FAILED reason={exc}")
        return False


def _is_impersonation_runtime_available(debug_logging: bool = False) -> bool:
    return _bootstrap_impersonation_runtime(debug_logging)


def _merge_extractor_args(base: Optional[Dict[str, Any]], extra: Optional[Dict[str, Any]]) -> Optional[Dict[str, Any]]:
    if not base and not extra:
        return None
    merged: Dict[str, Any] = {}
    for source in (base or {}, extra or {}):
        for key, value in source.items():
            if isinstance(value, dict) and isinstance(merged.get(key), dict):
                child = dict(merged[key])  # type: ignore[index]
                child.update(value)
                merged[key] = child
            else:
                merged[key] = value
    return merged


def _extract_yt_dlp_version_age_days(version: str) -> Optional[int]:
    match = re.match(r"^(\d{4})\.(\d{1,2})\.(\d{1,2})$", (version or "").strip())
    if not match:
        return None
    try:
        year, month, day = (int(match.group(1)), int(match.group(2)), int(match.group(3)))
        release_date = datetime.date(year, month, day)
        return max(0, (datetime.date.today() - release_date).days)
    except Exception:
        return None


def get_runtime_diagnostics() -> str:
    version = getattr(getattr(yt_dlp, "version", object()), "__version__", None)
    if isinstance(version, str):
        _set_runtime_diag("ytDlpVersionAgeDays", _extract_yt_dlp_version_age_days(version))

    _bootstrap_impersonation_runtime(debug_logging=False)

    payload = {
        "normalizedUrlLast": _RUNTIME_DIAGNOSTICS.get("normalizedUrlLast"),
        "attemptTraceCount": len(_RUNTIME_DIAGNOSTICS.get("attemptTrace") or []),
        "attemptTrace": _RUNTIME_DIAGNOSTICS.get("attemptTrace") or [],
        "lastExtractorKey": _RUNTIME_DIAGNOSTICS.get("lastExtractorKey"),
        "lastRawYtDlpError": _RUNTIME_DIAGNOSTICS.get("lastRawYtDlpError"),
        "lastCookieCheck": _RUNTIME_DIAGNOSTICS.get("lastCookieCheck"),
        "platformStrategyLast": _RUNTIME_DIAGNOSTICS.get("platformStrategyLast"),
        "ytDlpVersionAgeDays": _RUNTIME_DIAGNOSTICS.get("ytDlpVersionAgeDays"),
        "impersonationRuntimeAvailable": _RUNTIME_DIAGNOSTICS.get("impersonationRuntimeAvailable"),
        "impersonationEnabled": _RUNTIME_DIAGNOSTICS.get("impersonationEnabled"),
        "impersonationBackend": _RUNTIME_DIAGNOSTICS.get("impersonationBackend"),
        "impersonationRequiredByExtractorLast": _RUNTIME_DIAGNOSTICS.get("impersonationRequiredByExtractorLast"),
        "impersonationAttemptedTargetsLast": _RUNTIME_DIAGNOSTICS.get("impersonationAttemptedTargetsLast") or [],
        "impersonationResolvedTargetLast": _RUNTIME_DIAGNOSTICS.get("impersonationResolvedTargetLast"),
        "impersonationWheelVersion": _RUNTIME_DIAGNOSTICS.get("impersonationWheelVersion"),
        "impersonationBuildAbiCoverage": _RUNTIME_DIAGNOSTICS.get("impersonationBuildAbiCoverage") or IMP_ABI_COVERAGE,
        "impersonationBootstrapError": _RUNTIME_DIAGNOSTICS.get("impersonationBootstrapError"),
        "redditShareResolutionLast": _RUNTIME_DIAGNOSTICS.get("redditShareResolutionLast"),
        "progressWritesLast": _RUNTIME_DIAGNOSTICS.get("progressWritesLast"),
        "youtubeChunkProfileLast": _RUNTIME_DIAGNOSTICS.get("youtubeChunkProfileLast"),
        "formatSelectorLast": _RUNTIME_DIAGNOSTICS.get("formatSelectorLast"),
        "toolOutputLast": _RUNTIME_DIAGNOSTICS.get("toolOutputLast"),
        "preflightBudgetSec": _RUNTIME_DIAGNOSTICS.get("preflightBudgetSec"),
        "preflightElapsedMs": _RUNTIME_DIAGNOSTICS.get("preflightElapsedMs"),
        "preflightAttemptLimit": _RUNTIME_DIAGNOSTICS.get("preflightAttemptLimit"),
        "staticMediaCandidateCount": _RUNTIME_DIAGNOSTICS.get("staticMediaCandidateCount"),
        "knownExtractorLast": _RUNTIME_DIAGNOSTICS.get("knownExtractorLast"),
    }
    return json.dumps(payload)


def run_impersonation_self_test(debug_logging: bool = False) -> str:
    enabled = _bootstrap_impersonation_runtime(debug_logging=debug_logging)
    code = "IMPERSONATION_SELF_TEST_OK" if enabled else (
        "IMPERSONATION_BOOTSTRAP_FAILED"
        if str(_RUNTIME_DIAGNOSTICS.get("impersonationBootstrapError") or "").startswith("IMPERSONATION_BOOTSTRAP_FAILED")
        else "IMPERSONATION_DEPENDENCY_MISSING"
    )
    return _result(
        enabled,
        code,
        None if enabled else str(_RUNTIME_DIAGNOSTICS.get("impersonationBootstrapError") or "Impersonation bootstrap failed"),
        impersonation_enabled=enabled,
        backend=_RUNTIME_DIAGNOSTICS.get("impersonationBackend"),
        wheel_version=_RUNTIME_DIAGNOSTICS.get("impersonationWheelVersion"),
        build_abi_coverage=_RUNTIME_DIAGNOSTICS.get("impersonationBuildAbiCoverage") or IMP_ABI_COVERAGE,
    )


def _debug_log(enabled: bool, message: str) -> None:
    if enabled:
        print(f"[LocalDownloaderPy] {message}", flush=True)


def _result(success: bool, code: str, message: Optional[str] = None, **kwargs: Any) -> str:
    payload: Dict[str, Any] = {
        "success": success,
        "code": code,
        "message": message,
    }
    normalized_url = _RUNTIME_DIAGNOSTICS.get("normalizedUrlLast")
    if normalized_url and "normalized_url" not in kwargs:
        payload["normalized_url"] = normalized_url
    attempt_trace = _RUNTIME_DIAGNOSTICS.get("attemptTrace") or []
    if attempt_trace and "attempt_trace" not in kwargs:
        payload["attempt_trace"] = attempt_trace
    format_selector = _RUNTIME_DIAGNOSTICS.get("formatSelectorLast")
    if format_selector and "format_selector" not in kwargs:
        payload["format_selector"] = format_selector
    tool_output = _RUNTIME_DIAGNOSTICS.get("toolOutputLast")
    if tool_output and "tool_output" not in kwargs:
        payload["tool_output"] = tool_output
    for diag_key, payload_key in (
        ("preflightBudgetSec", "preflight_budget_sec"),
        ("preflightElapsedMs", "preflight_elapsed_ms"),
        ("preflightAttemptLimit", "preflight_attempt_limit"),
        ("staticMediaCandidateCount", "static_media_candidate_count"),
    ):
        value = _RUNTIME_DIAGNOSTICS.get(diag_key)
        if value is not None and payload_key not in kwargs:
            payload[payload_key] = value
    payload.update(kwargs)
    return json.dumps(payload)


def _detect_cookie_platform(url: str) -> Optional[str]:
    domain = _extract_host(url)
    if not domain:
        return None

    for platform, domains in COOKIE_PLATFORMS.items():
        if any(domain == d or domain.endswith(f".{d}") for d in domains):
            return platform

    return None


def _detect_known_ytdlp_extractor(url: str, debug_logging: bool = False) -> Optional[str]:
    raw = (url or "").strip()
    if not raw:
        return None
    cached = _KNOWN_EXTRACTOR_CACHE.get(raw)
    if raw in _KNOWN_EXTRACTOR_CACHE:
        _set_runtime_diag("knownExtractorLast", cached)
        return cached

    result: Optional[str] = None
    try:
        extractor_module = importlib.import_module("yt_dlp.extractor")
        for extractor in extractor_module.gen_extractors():
            name = str(getattr(extractor, "IE_NAME", "") or "").strip()
            if not name or name.lower() == "generic":
                continue
            suitable = getattr(extractor, "suitable", None)
            if not callable(suitable):
                continue
            try:
                if suitable(raw):
                    result = name
                    break
            except Exception:
                continue
    except Exception as exc:
        _debug_log(debug_logging, f"known extractor probe unavailable: {exc}")
        result = None

    _KNOWN_EXTRACTOR_CACHE[raw] = result
    _set_runtime_diag("knownExtractorLast", result)
    return result


def _extract_host(url: str) -> str:
    raw = (url or "").strip()
    if not raw:
        return ""

    parsed = urlparse(raw if "://" in raw else f"https://{raw}")
    host = (parsed.hostname or parsed.netloc or "").lower()
    if host.startswith("www."):
        host = host[4:]
    return host


def _is_public_reddit_url(url: str) -> bool:
    host = _extract_host(url)
    if host not in REDDIT_DOMAINS:
        return False

    parsed = urlparse(url if "://" in url else f"https://{url}")
    path = (parsed.path or "").lower()
    return "/comments/" in path or host in {"v.redd.it", "redd.it"}


def _is_reddit_share_path(url: str) -> bool:
    parsed = urlparse(url if "://" in url else f"https://{url}")
    path = parsed.path or ""
    return bool(REDDIT_SHARE_PATH_PATTERN.search(path))


def _is_soft_reddit_share_resolution_error(error: str) -> bool:
    lower = (error or "").lower()
    return any(marker in lower for marker in SOFT_REDDIT_SHARE_RESOLUTION_MARKERS)


def _should_fail_reddit_generic_route(platform: Optional[str], normalized_url: str, extractor_key: str) -> bool:
    if platform != "reddit":
        return False
    if (extractor_key or "").strip().lower() != "generic":
        return False
    # If we intentionally kept a share link due soft fallback, allow extraction flow to continue.
    if _is_reddit_share_path(normalized_url):
        return False
    return True


def _build_cookie_header_from_file(cookie_file: Optional[str], target_host: str) -> Tuple[Optional[str], int]:
    if not cookie_file or not os.path.exists(cookie_file):
        return None, 0

    host = (target_host or "").strip().lower()
    if not host:
        return None, 0

    now = int(time.time())
    pairs: List[str] = []
    try:
        with open(cookie_file, "r", encoding="utf-8", errors="replace") as f:
            for raw_line in f:
                line = raw_line.strip()
                if not line or line.startswith("#"):
                    continue
                cols = raw_line.rstrip("\n").split("\t")
                if len(cols) < 7:
                    continue

                domain = (cols[0] or "").strip().lstrip(".").lower()
                if not domain:
                    continue
                if not (host == domain or host.endswith(f".{domain}")):
                    continue

                expiry_raw = (cols[4] or "").strip()
                try:
                    expiry = int(expiry_raw)
                except Exception:
                    expiry = 0
                if expiry > 0 and expiry <= now:
                    continue

                name = (cols[5] or "").strip()
                value = (cols[6] or "").strip()
                if not name:
                    continue
                pairs.append(f"{name}={value}")
    except Exception:
        return None, 0

    if not pairs:
        return None, 0
    return "; ".join(pairs), len(pairs)


def _resolve_reddit_share_url(
    url: str,
    user_agent: str,
    cookie_file: Optional[str] = None,
    debug_logging: bool = False,
) -> Tuple[Optional[str], Optional[str]]:
    try:
        host = _extract_host(url)
        cookie_header, cookie_count = _build_cookie_header_from_file(cookie_file, host)
        request = Request(
            url,
            headers={
                "User-Agent": user_agent,
                "Accept-Language": "en-US,en;q=0.9",
                "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Referer": "https://www.reddit.com/",
                "Origin": "https://www.reddit.com",
                **({"Cookie": cookie_header} if cookie_header else {}),
            },
        )
        _debug_log(
            debug_logging,
            f"reddit share resolve request host={host} cookie_file={'yes' if cookie_file else 'no'} cookie_count={cookie_count}",
        )
        with urlopen(request, timeout=12) as response:
            final_url = response.geturl()
            _debug_log(debug_logging, f"reddit share resolved url={url} final={final_url}")
            if not final_url:
                return None, "empty redirect target"
            return final_url, None
    except Exception as exc:
        _debug_log(debug_logging, f"reddit share resolve failed url={url} error={exc}")
        return None, str(exc)


def _resolve_redirect_url(url: str, user_agent: str, debug_logging: bool = False) -> Tuple[Optional[str], Optional[str]]:
    try:
        request = Request(url, headers={"User-Agent": user_agent})
        with urlopen(request, timeout=12) as response:
            final_url = response.geturl()
            _debug_log(debug_logging, f"redirect resolved url={url} final={final_url}")
            if not final_url:
                return None, "empty redirect target"
            return final_url, None
    except Exception as exc:
        _debug_log(debug_logging, f"redirect resolve failed url={url} error={exc}")
        return None, str(exc)


def _normalize_input_url(
    url: str,
    platform: Optional[str],
    user_agent: str,
    cookie_file: Optional[str] = None,
    debug_logging: bool = False,
) -> Tuple[Optional[str], Optional[str]]:
    parsed = urlparse(url if "://" in url else f"https://{url}")
    host = _extract_host(url)

    normalized_url = url
    if not parsed.scheme:
        normalized_url = f"https://{url.strip()}"

    if platform == "tiktok" and host in TIKTOK_SHORT_DOMAINS:
        final_url, error = _resolve_redirect_url(normalized_url, user_agent, debug_logging)
        if error:
            return None, error
        normalized_url = final_url or normalized_url

    if platform == "tiktok":
        return _canonicalize_tiktok_url(normalized_url), None

    if platform != "reddit" or host not in REDDIT_DOMAINS:
        return normalized_url, None

    path = parsed.path or ""
    if not REDDIT_SHARE_PATH_PATTERN.search(path):
        return normalized_url, None

    final_url, error = _resolve_reddit_share_url(
        normalized_url,
        user_agent,
        cookie_file=cookie_file,
        debug_logging=debug_logging,
    )
    if error:
        if _is_soft_reddit_share_resolution_error(error):
            _debug_log(debug_logging, f"reddit share resolve soft-fallback reason={error}")
            _set_runtime_diag(
                "redditShareResolutionLast",
                {"inputUrl": normalized_url, "mode": "fallback_original", "reason": error},
            )
            _debug_log(debug_logging, "reddit share normalization mode=fallback_original")
            return normalized_url, None
        _set_runtime_diag(
            "redditShareResolutionLast",
            {"inputUrl": normalized_url, "mode": "failed", "reason": error},
        )
        return None, error

    final_host = _extract_host(final_url or "")
    if final_host not in REDDIT_DOMAINS:
        reason = f"resolved host is not reddit ({final_host})"
        _set_runtime_diag(
            "redditShareResolutionLast",
            {"inputUrl": normalized_url, "mode": "failed", "reason": reason},
        )
        return None, reason

    _set_runtime_diag(
        "redditShareResolutionLast",
        {"inputUrl": normalized_url, "mode": "canonicalized"},
    )
    _debug_log(debug_logging, "reddit share normalization mode=canonicalized")

    return final_url, None


def _canonicalize_tiktok_url(url: str) -> str:
    parsed = urlparse(url if "://" in url else f"https://{url}")
    host = (parsed.hostname or "").lower()
    if host.startswith("www."):
        host = host[4:]
    if not host.endswith("tiktok.com"):
        return url

    path = (parsed.path or "/").rstrip("/") or "/"
    if not path.startswith("/"):
        path = f"/{path}"
    # Drop tracking/query params (_r/_t etc.). TikTok video URLs are stable by path.
    return urlunparse(("https", "www.tiktok.com", path, "", "", ""))


def _is_generic_unsupported_error(message: Optional[str]) -> bool:
    lower = (message or "").lower()
    return any(marker in lower for marker in GENERIC_UNSUPPORTED_MARKERS)


def _should_try_generic_discovery(code: Optional[str], message: Optional[str]) -> bool:
    lower = (message or "").lower()
    if _is_generic_unsupported_error(lower):
        return True
    if any(marker in lower for marker in ("extractor error", "keyerror(", "no media", "no video", "unable to extract")):
        return True
    return (code or "").strip() in {"PREFLIGHT_FAILED", "DOWNLOAD_FAILED"} and "generic" in lower


def _decode_embedded_web_text(text: str) -> str:
    value = html.unescape(text or "")
    value = value.replace("\\/", "/")

    def _decode_unicode(match: re.Match[str]) -> str:
        try:
            return chr(int(match.group(1), 16))
        except Exception:
            return match.group(0)

    value = re.sub(r"\\u([0-9a-fA-F]{4})", _decode_unicode, value)
    value = re.sub(r"\\x([0-9a-fA-F]{2})", _decode_unicode, value)
    return value


def _is_generic_media_candidate(url: str) -> bool:
    lower = (url or "").lower()
    return any(ext in lower for ext in GENERIC_MEDIA_EXTENSIONS)


def _normalize_candidate_url(candidate: str, page_url: str) -> Optional[str]:
    raw = (candidate or "").strip().strip("\"'()[]{}.,;")
    if not raw:
        return None

    normalized = _decode_embedded_web_text(raw).strip()
    if normalized.startswith("//"):
        base_scheme = urlparse(page_url).scheme or "https"
        normalized = f"{base_scheme}:{normalized}"
    elif normalized.startswith("/"):
        normalized = urljoin(page_url, normalized)

    if not normalized.startswith("http://") and not normalized.startswith("https://"):
        return None
    if not _is_generic_media_candidate(normalized):
        return None

    parsed = urlparse(normalized)
    if not parsed.netloc:
        return None
    return normalized


def _score_media_candidate(candidate_url: str, page_host: str) -> int:
    lower = candidate_url.lower()
    score = 0
    if ".mp4" in lower:
        score += 60
    if ".m3u8" in lower or ".mpd" in lower:
        score += 50
    if ".webm" in lower or ".mkv" in lower:
        score += 35
    if "master" in lower or "playlist" in lower:
        score += 10
    candidate_host = _extract_host(candidate_url)
    if candidate_host and page_host and (candidate_host == page_host or candidate_host.endswith(f".{page_host}")):
        score += 15
    return score


def _fetch_page_text(url: str, user_agent: str, debug_logging: bool = False) -> Tuple[Optional[str], Optional[str]]:
    headers = {
        "User-Agent": user_agent,
        "Accept-Language": "en-US,en;q=0.9",
    }
    try:
        request = Request(url, headers=headers)
        with urlopen(request, timeout=15) as response:
            content = response.read(2 * 1024 * 1024)
            charset = None
            try:
                charset = response.headers.get_content_charset()
            except Exception:
                charset = None
            encoding = charset or "utf-8"
            return content.decode(encoding, errors="replace"), None
    except Exception as exc:
        _debug_log(debug_logging, f"generic discovery fetch failed url={url} error={exc}")
        return None, str(exc)


def _fetch_page_text_sample(
    url: str,
    user_agent: str,
    debug_logging: bool = False,
    timeout_sec: int = STATIC_PAGE_FETCH_TIMEOUT_SEC,
) -> Tuple[Optional[str], Optional[str]]:
    headers = {
        "User-Agent": user_agent,
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language": "en-US,en;q=0.9",
    }
    try:
        request = Request(url, headers=headers)
        with urlopen(request, timeout=timeout_sec) as response:
            content = response.read(STATIC_PAGE_SAMPLE_BYTES)
            try:
                charset = response.headers.get_content_charset()
            except Exception:
                charset = None
            return content.decode(charset or "utf-8", errors="replace"), None
    except Exception as exc:
        _debug_log(debug_logging, f"static sample fetch failed url={url} error={exc}")
        return None, str(exc)


def _is_static_media_candidate(raw_value: str) -> bool:
    lower = (raw_value or "").lower()
    return any(marker in lower for marker in STATIC_MEDIA_MARKERS)


def _count_static_media_candidates(page_text: Optional[str]) -> int:
    if not page_text:
        return 0
    decoded = _decode_embedded_web_text(page_text)
    combined = f"{page_text}\n{decoded}".lower()
    marker_count = sum(1 for marker in STATIC_MEDIA_MARKERS if marker in combined)
    url_count = 0
    for blob in (page_text, decoded):
        url_count += sum(1 for match in GENERIC_URL_TOKEN_PATTERN.finditer(blob) if _is_static_media_candidate(match.group(0)))
        url_count += sum(1 for match in GENERIC_MEDIA_RELATIVE_PATTERN.finditer(blob) if _is_static_media_candidate(match.group("path")))
    return marker_count + url_count


def _probe_static_media_candidates(
    url: str,
    user_agent: str,
    debug_logging: bool = False,
) -> Tuple[int, Optional[str]]:
    page_text, fetch_error = _fetch_page_text_sample(url, user_agent, debug_logging)
    if fetch_error:
        _set_runtime_diag("staticMediaCandidateCount", 0)
        return 0, fetch_error
    candidate_count = _count_static_media_candidates(page_text)
    _set_runtime_diag("staticMediaCandidateCount", candidate_count)
    _debug_log(debug_logging, f"static sample candidate count={candidate_count}")
    return candidate_count, None


def _discover_generic_media_candidates(url: str, user_agent: str, debug_logging: bool = False) -> List[str]:
    page_text, fetch_error = _fetch_page_text(url, user_agent, debug_logging)
    if fetch_error or not page_text:
        return []

    page_host = _extract_host(url)
    candidates: List[str] = []
    seen: set[str] = set()

    def _maybe_add(raw_candidate: str) -> None:
        normalized = _normalize_candidate_url(raw_candidate, url)
        if not normalized:
            return
        if normalized in seen:
            return
        seen.add(normalized)
        candidates.append(normalized)

    decoded_text = _decode_embedded_web_text(page_text)
    for blob in (page_text, decoded_text):
        for match in GENERIC_URL_TOKEN_PATTERN.finditer(blob):
            _maybe_add(match.group(0))
        for match in GENERIC_MEDIA_RELATIVE_PATTERN.finditer(blob):
            _maybe_add(match.group("path"))

    if not candidates:
        return []

    ranked = sorted(candidates, key=lambda item: _score_media_candidate(item, page_host), reverse=True)
    selected = ranked[:MAX_GENERIC_DISCOVERY_CANDIDATES]
    _debug_log(debug_logging, f"generic discovery found {len(selected)} media candidate(s)")
    for index, candidate in enumerate(selected, start=1):
        _debug_log(debug_logging, f"generic discovery candidate[{index}]={candidate}")
    return selected


def _build_generic_discovery_attempts(
    source_url: str,
    selected_cookie_file: Optional[str],
    user_agent: str,
    debug_logging: bool = False,
) -> List[Dict[str, Any]]:
    candidates = _discover_generic_media_candidates(source_url, user_agent, debug_logging)
    if not candidates:
        return []

    attempts: List[Dict[str, Any]] = []
    cookie_modes = [True, False] if selected_cookie_file else [False]
    for index, candidate in enumerate(candidates, start=1):
        is_hls_like = ".m3u8" in candidate.lower() or ".mpd" in candidate.lower()
        for use_cookie in cookie_modes:
            prefix = "cookie" if use_cookie else "anon"
            attempt: Dict[str, Any] = {
                "label": f"generic-{prefix}-discovered-{index}",
                "use_cookie": use_cookie,
                "url_override": candidate,
                "referer_url": source_url,
                "format_override": "best",
            }
            if is_hls_like:
                attempt["ydl_overrides"] = {
                    "hls_prefer_native": False,
                    "external_downloader": "ffmpeg",
                    "abort_on_unavailable_fragments": True,
                    "skip_unavailable_fragments": False,
                }
            attempts.append(attempt)

    return attempts


def _inspect_cookie_file(cookie_file: Optional[str], platform: Optional[str]) -> Dict[str, Any]:
    result: Dict[str, Any] = {
        "platform": platform,
        "hasCookieFile": bool(cookie_file and os.path.exists(cookie_file)),
        "domainCoverage": [],
        "unexpiredCount": 0,
    }
    if not result["hasCookieFile"]:
        return result

    now = int(time.time())
    domains: set[str] = set()
    unexpired = 0
    try:
        with open(cookie_file or "", "r", encoding="utf-8", errors="replace") as f:
            for raw_line in f:
                line = raw_line.strip()
                if not line or line.startswith("#"):
                    continue
                cols = raw_line.rstrip("\n").split("\t")
                if len(cols) < 7:
                    continue

                domain = (cols[0] or "").strip().lstrip(".").lower()
                if domain.startswith("www."):
                    domain = domain[4:]
                if domain:
                    domains.add(domain)

                expiry_raw = (cols[4] or "").strip()
                expiry = 0
                try:
                    expiry = int(expiry_raw)
                except Exception:
                    expiry = 0
                if expiry <= 0 or expiry > now:
                    unexpired += 1
    except Exception:
        return result

    result["domainCoverage"] = sorted(domains)
    result["unexpiredCount"] = unexpired
    return result


def _cookie_integrity_error(cookie_check: Dict[str, Any]) -> Optional[Tuple[str, str]]:
    if not cookie_check.get("hasCookieFile"):
        return None

    platform = cookie_check.get("platform")
    coverage = cookie_check.get("domainCoverage") or []
    unexpired = int(cookie_check.get("unexpiredCount") or 0)
    if unexpired <= 0:
        return "COOKIE_EMPTY_OR_EXPIRED", "Cookie file has no unexpired entries."

    expected_domains = COOKIE_PLATFORM_DOMAINS.get(platform, set())
    if expected_domains:
        has_coverage = any(
            domain == expected or domain.endswith(f".{expected}")
            for domain in coverage
            for expected in expected_domains
        )
        if not has_coverage:
            return (
                "COOKIE_DOMAIN_MISMATCH",
                f"Cookie domains do not match expected platform domains: {', '.join(sorted(expected_domains))}",
            )
    return None


def _resolve_cookie_file(cookies_dir: str, platform: Optional[str], cookie_profile: Optional[str]) -> Optional[str]:
    if not platform:
        return None

    platform_dir = os.path.join(cookies_dir, platform)
    if not os.path.isdir(platform_dir):
        return None

    files = [
        os.path.join(platform_dir, f)
        for f in os.listdir(platform_dir)
        if f.endswith(".txt") or f.endswith(".json")
    ]
    if not files:
        return None

    if cookie_profile:
        normalized = cookie_profile.strip().lower()
        for path in files:
            filename = os.path.basename(path).lower()
            stem, _ = os.path.splitext(filename)
            if normalized == filename or normalized == stem:
                return path

    files.sort(key=lambda p: os.path.getmtime(p), reverse=True)
    return files[0]


def _estimate_file_size_mb(info: Dict[str, Any]) -> Tuple[float, str]:
    filesize = info.get("filesize")
    if filesize and filesize > 0:
        return filesize / (1024 * 1024), "filesize"

    filesize_approx = info.get("filesize_approx")
    if filesize_approx and filesize_approx > 0:
        return filesize_approx / (1024 * 1024), "filesize_approx"

    duration = info.get("duration")
    tbr = info.get("tbr")
    if duration and tbr:
        return (tbr * duration) / 8 / 1024, "duration_bitrate"

    if duration:
        avg_kbps = 2000 if duration < 180 else 5000
        return (avg_kbps * duration) / 8 / 1024, "duration_estimate"

    return 0, "unknown"


def _is_cancel_requested(cancel_flag_path: Optional[str]) -> bool:
    return bool(cancel_flag_path and os.path.exists(cancel_flag_path))


def _resolve_ffmpeg_location(ffmpeg_path: Optional[str]) -> Optional[str]:
    if not ffmpeg_path:
        return None

    if os.path.isdir(ffmpeg_path):
        return ffmpeg_path

    if os.path.isfile(ffmpeg_path):
        return ffmpeg_path

    return None


def _resolve_ffmpeg_binary(ffmpeg_location: Optional[str]) -> Optional[str]:
    if not ffmpeg_location:
        return None

    if os.path.isfile(ffmpeg_location):
        return ffmpeg_location

    for name in ("ffmpeg", "libffmpeg.so"):
        candidate = os.path.join(ffmpeg_location, name)
        if os.path.exists(candidate):
            return candidate

    return None


def _resolve_ffprobe_binary(ffmpeg_location: Optional[str]) -> Optional[str]:
    if not ffmpeg_location:
        return None

    if os.path.isfile(ffmpeg_location):
        base_dir = os.path.dirname(ffmpeg_location)
        base_name = os.path.basename(ffmpeg_location)
        candidates = []
        if base_name == "libffmpeg.so":
            candidates.append(os.path.join(base_dir, "libffprobe.so"))
        candidates.append(os.path.join(base_dir, "ffprobe"))
        for candidate in candidates:
            if os.path.exists(candidate):
                return candidate
        return None

    for name in ("ffprobe", "libffprobe.so"):
        candidate = os.path.join(ffmpeg_location, name)
        if os.path.exists(candidate):
            return candidate

    return None


def _probe_binary(binary_path: Optional[str], label: str, debug_logging: bool = False) -> Tuple[bool, str]:
    if not binary_path or not os.path.exists(binary_path):
        _debug_log(debug_logging, f"{label} missing path={binary_path}")
        return False, f"{label} binary missing"

    try:
        proc = subprocess.run(
            [binary_path, "-version"],
            capture_output=True,
            check=False,
            timeout=5,
            text=True,
        )
        first_line = (proc.stdout or proc.stderr or "").strip().splitlines()
        first = first_line[0] if first_line else ""
        if proc.returncode == 0:
            _debug_log(debug_logging, f"{label} probe ok path={binary_path} version={first or 'ok'}")
            return True, first or "ok"
        _debug_log(debug_logging, f"{label} probe failed path={binary_path} exit={proc.returncode} first={first or 'no output'}")
        return False, f"{label} exited {proc.returncode}: {first or 'no output'}"
    except Exception as exc:
        _debug_log(debug_logging, f"{label} probe exception path={binary_path} error={exc}")
        return False, f"{label} probe failed: {exc}"


def _resolve_merge_capability(
    ffmpeg_location: Optional[str],
    requested_merge_capable: bool,
    debug_logging: bool = False,
) -> Tuple[bool, Optional[str]]:
    if not requested_merge_capable:
        _debug_log(debug_logging, "merge capability disabled by native input")
        return False, None

    ffmpeg_binary = _resolve_ffmpeg_binary(ffmpeg_location)
    ffprobe_binary = _resolve_ffprobe_binary(ffmpeg_location)
    _debug_log(
        debug_logging,
        f"merge capability probing ffmpeg_location={ffmpeg_location} ffmpeg={ffmpeg_binary} ffprobe={ffprobe_binary}",
    )
    ffmpeg_ok, ffmpeg_reason = _probe_binary(ffmpeg_binary, "ffmpeg", debug_logging)
    ffprobe_ok, ffprobe_reason = _probe_binary(ffprobe_binary, "ffprobe", debug_logging)
    if ffmpeg_ok and ffprobe_ok:
        _debug_log(debug_logging, "merge capability ready")
        return True, None

    reason = f"{ffmpeg_reason}; {ffprobe_reason}"
    _debug_log(debug_logging, f"merge capability unavailable: {reason}")
    return False, reason


def _build_http_headers(
    url: str,
    user_agent: str,
    platform: Optional[str],
    include_reddit_context: bool = False,
    referer_url: Optional[str] = None,
) -> Dict[str, str]:
    headers = {"Accept-Language": "en-US,en;q=0.9"}
    # For TikTok, do not hard-force User-Agent; extractor/impersonation should control it.
    if platform != "tiktok":
        headers["User-Agent"] = user_agent
    host = _extract_host(url)
    parsed = urlparse(url)
    if include_reddit_context and platform == "reddit" and (host.endswith("reddit.com") or host.endswith("redd.it")):
        headers["Referer"] = "https://www.reddit.com/"
        headers["Origin"] = "https://www.reddit.com"
    elif referer_url:
        referer_parsed = urlparse(referer_url)
        if referer_parsed.scheme in {"http", "https"} and referer_parsed.netloc:
            headers["Referer"] = referer_url
            headers["Origin"] = f"{referer_parsed.scheme}://{referer_parsed.netloc}"
    elif platform is None and parsed.scheme in {"http", "https"} and parsed.netloc:
        # Generic fallback for non-built-in platforms: many tokenized HLS/CDN URLs expect same-site context.
        headers["Referer"] = f"{parsed.scheme}://{parsed.netloc}/"
        headers["Origin"] = f"{parsed.scheme}://{parsed.netloc}"
    return headers


def _is_generic_failure(code: Optional[str], message: Optional[str]) -> bool:
    normalized_code = (code or "").strip()
    normalized_msg = (message or "").strip()
    return normalized_code in GENERIC_FAILURE_CODES and (
        not normalized_msg or normalized_msg == normalized_code
    )


def _should_replace_last_error(
    prev_code: Optional[str],
    prev_message: Optional[str],
    new_code: Optional[str],
    new_message: Optional[str],
) -> bool:
    if prev_code is None:
        return True

    prev_generic = _is_generic_failure(prev_code, prev_message)
    new_generic = _is_generic_failure(new_code, new_message)
    if not prev_generic and new_generic:
        return False
    if prev_generic and not new_generic:
        return True

    # Keep the strongest TikTok classification if we already have it.
    if prev_code == "TIKTOK_API_STATUS_ZERO" and new_code != "TIKTOK_API_STATUS_ZERO":
        return False
    if new_code == "TIKTOK_API_STATUS_ZERO" and prev_code != "TIKTOK_API_STATUS_ZERO":
        return True

    if not new_message and prev_message:
        return False
    return True


class _YdlLogger:
    def __init__(self, debug_logging: bool, strategy: str, attempt_id: str):
        self.debug_logging = debug_logging
        self.strategy = strategy
        self.attempt_id = attempt_id

    def _emit(self, level: str, message: str) -> None:
        msg = _redact_text(str(message or "").strip())
        if not msg:
            return
        captured = _bounded_tool_output(msg)
        if captured:
            existing = str(_RUNTIME_DIAGNOSTICS.get("toolOutputLast") or "").strip()
            combined = f"{existing}\n{captured}".strip() if existing else captured
            _set_runtime_diag("toolOutputLast", combined[:MAX_TOOL_OUTPUT_CHARS])
        if not YTDLP_VERBOSE_DEV:
            return
        _debug_log(
            self.debug_logging,
            f"yt-dlp[{self.strategy}#{self.attempt_id}][{level}] {msg}",
        )

    def debug(self, message: str) -> None:
        self._emit("debug", message)

    def warning(self, message: str) -> None:
        self._emit("warn", message)

    def error(self, message: str) -> None:
        self._emit("error", message)


def _sanitize_opts_for_log(opts: Dict[str, Any]) -> Dict[str, Any]:
    safe = {
        "format": opts.get("format"),
        "cookie": "yes" if opts.get("cookiefile") else "no",
        "ffmpeg_location": opts.get("ffmpeg_location"),
        "extractor_args": opts.get("extractor_args"),
        "impersonate": opts.get("impersonate"),
        "force_generic_extractor": opts.get("force_generic_extractor"),
        "headers": list((opts.get("http_headers") or {}).keys()),
        "noplaylist": opts.get("noplaylist"),
        "retries": opts.get("retries"),
        "hls_prefer_native": opts.get("hls_prefer_native"),
        "external_downloader": opts.get("external_downloader"),
        "http_chunk_size": opts.get("http_chunk_size"),
        "throttledratelimit": opts.get("throttledratelimit"),
        "abort_on_unavailable_fragments": opts.get("abort_on_unavailable_fragments"),
        "skip_unavailable_fragments": opts.get("skip_unavailable_fragments"),
    }
    return safe


def _common_ydl_opts(
    normalized_url: str,
    platform: Optional[str],
    cookie_file: Optional[str],
    ffmpeg_location: Optional[str],
    user_agent: str,
    extractor_args: Optional[Dict[str, Any]] = None,
    include_reddit_context: bool = False,
    impersonate: Optional[str] = None,
    disable_impersonation: bool = False,
    referer_url: Optional[str] = None,
    debug_strategy_label: Optional[str] = None,
    debug_attempt_id: Optional[str] = None,
    debug_logging: bool = False,
) -> Dict[str, Any]:
    headers = _build_http_headers(
        normalized_url,
        user_agent,
        platform,
        include_reddit_context=include_reddit_context,
        referer_url=referer_url,
    )

    opts: Dict[str, Any] = {
        "quiet": True,
        "no_warnings": True,
        "ignoreconfig": True,
        "noplaylist": True,
        "no_cache_dir": True,
        "extractor_retries": 6,
        "retries": 10,
        "fragment_retries": 10,
        "file_access_retries": 3,
        # Avoid silently producing empty/partial outputs when a site CDN invalidates HLS fragments.
        "abort_on_unavailable_fragments": True,
        "skip_unavailable_fragments": False,
        # Keep output file timestamps at download time so gallery apps sort as latest.
        "updatetime": False,
    }
    if headers:
        opts["http_headers"] = headers

    if cookie_file:
        opts["cookiefile"] = cookie_file

    ffmpeg_binary = _resolve_ffmpeg_binary(ffmpeg_location)
    if ffmpeg_binary:
        opts["ffmpeg_location"] = ffmpeg_binary
    elif ffmpeg_location and os.path.exists(ffmpeg_location):
        opts["ffmpeg_location"] = ffmpeg_location

    if extractor_args:
        opts["extractor_args"] = extractor_args
    if disable_impersonation:
        # Equivalent of --no-impersonate for environments without optional runtime support.
        opts["impersonate"] = False
    elif impersonate:
        opts["impersonate"] = impersonate
    if debug_strategy_label and debug_attempt_id:
        opts["logger"] = _YdlLogger(debug_logging, debug_strategy_label, debug_attempt_id)

    _debug_log(debug_logging, f"ydl opts prepared url={normalized_url} opts={_sanitize_opts_for_log(opts)}")

    return opts


def _build_format_selector(max_file_size_mb: int, merge_capable: bool) -> str:
    limit_mb = int(max_file_size_mb)
    unlimited = limit_mb <= 0

    if unlimited:
        if merge_capable:
            return "bestvideo+bestaudio/best"
        return "best[acodec!=none][vcodec!=none]/best"

    if merge_capable:
        return (
            f"bestvideo[filesize<{limit_mb}M]+bestaudio[filesize<{limit_mb}M]/"
            f"bestvideo+bestaudio/"
            f"best[filesize<{limit_mb}M]/"
            f"best[filesize_approx<{limit_mb}M]/"
            "best"
        )

    return (
        f"best[acodec!=none][vcodec!=none][filesize<{limit_mb}M]/"
        f"best[acodec!=none][vcodec!=none][filesize_approx<{limit_mb}M]/"
        "best[acodec!=none][vcodec!=none]"
    )


def _has_progressive_format(info: Dict[str, Any]) -> bool:
    for fmt in info.get("formats") or []:
        vcodec = fmt.get("vcodec")
        acodec = fmt.get("acodec")
        if vcodec and vcodec != "none" and acodec and acodec != "none":
            return True
    return False


def _is_retryable_message(message: str) -> bool:
    return any(marker in message for marker in RETRYABLE_STATUS_MARKERS)


def _is_retryable_preflight_failure(code: Optional[str], message: Optional[str]) -> bool:
    normalized_code = (code or "").strip()
    lower = (message or "").lower()
    if normalized_code in HARD_PREFLIGHT_CODES:
        return False
    if normalized_code in {"SITE_BLOCKED_403", "TIKTOK_API_STATUS_ZERO", "TIKTOK_EXTRACTOR_UNSTABLE"}:
        return True
    if any(marker in lower for marker in RETRYABLE_PREFLIGHT_MARKERS):
        return True
    return False


def _bounded_tool_output(message: Optional[str]) -> Optional[str]:
    value = _redact_text(str(message or "").strip())
    if not value:
        return None
    lower = value.lower()
    if not any(marker in lower for marker in ("ffmpeg", "ffprobe", "stderr", "traceback", "error", "transporterror")):
        return None
    return value[:MAX_TOOL_OUTPUT_CHARS]


def _extract_info_with_retry(
    ydl: yt_dlp.YoutubeDL,
    url: str,
    *,
    download: bool,
    max_attempts: int = 3,
    timeout_sec: Optional[float] = None,
    deadline_ms: Optional[int] = None,
    debug_logging: bool = False,
    strategy_label: Optional[str] = None,
    attempt_id: Optional[str] = None,
) -> Dict[str, Any]:
    last_exc: Optional[Exception] = None

    for attempt in range(1, max_attempts + 1):
        try:
            effective_timeout = timeout_sec or EXTRACT_INFO_TIMEOUT_SEC
            if deadline_ms is not None:
                remaining_sec = max(0.0, (deadline_ms - _monotonic_ms()) / 1000.0)
                if remaining_sec <= 0:
                    raise TimeoutError("Preflight budget exhausted")
                effective_timeout = min(effective_timeout, remaining_sec)
            _debug_log(
                debug_logging,
                f"extract start strategy={strategy_label or 'single'} attempt={attempt}/{max_attempts} "
                f"download={download} url={url} id={attempt_id or 'n/a'}",
            )
            if download:
                info = ydl.extract_info(url, download=download)
            else:
                executor = concurrent.futures.ThreadPoolExecutor(max_workers=1)
                future = executor.submit(ydl.extract_info, url, False)
                try:
                    info = future.result(timeout=effective_timeout)
                except concurrent.futures.TimeoutError as exc:
                    future.cancel()
                    raise TimeoutError(f"Extractor timed out after {effective_timeout:.1f}s") from exc
                finally:
                    executor.shutdown(wait=False, cancel_futures=True)
            if isinstance(info, dict):
                return info
            return {}
        except Exception as exc:
            last_exc = exc
            message = str(exc).lower()
            can_retry = attempt < max_attempts and _is_retryable_message(message)
            if not can_retry:
                raise

            backoff = 0.6 * (2 ** (attempt - 1)) + random.uniform(0.0, 0.25)
            _debug_log(
                debug_logging,
                f"extract retry strategy={strategy_label or 'single'} id={attempt_id or 'n/a'} "
                f"attempt={attempt} backoff={backoff:.2f}s reason={_redact_text(str(exc))}",
            )
            time.sleep(backoff)

    if last_exc:
        raise last_exc
    raise RuntimeError("UNKNOWN_EXTRACTION_ERROR")


def _extract_error_extractor_key(message: str) -> Optional[str]:
    match = re.search(r"\[([A-Za-z0-9_.-]+)\]", message or "")
    if not match:
        return None
    key = match.group(1).strip().lower()
    return key or None


def _classify_exception(
    exc: Exception,
    default_code: str,
    platform: Optional[str] = None,
    context_url: Optional[str] = None,
) -> Tuple[str, str]:
    message = str(exc) or default_code
    lower = message.lower()

    if _is_impersonation_unavailable_message(lower):
        if "extractor is attempting impersonation" in lower or "none of these impersonate targets are available" in lower:
            return "IMPERSONATION_TARGET_REQUIRED_UNAVAILABLE", message
        if "missing dependencies required" in lower:
            return "IMPERSONATION_DEPENDENCY_MISSING", message
        return "IMPERSONATION_RUNTIME_UNAVAILABLE", message

    if platform == "reddit" and "[generic]" in lower and not (
        context_url and _is_reddit_share_path(context_url)
    ):
        return "REDDIT_EXTRACTOR_ROUTE_FAILED", message

    if "http error 403" in lower and ("blocked" in lower or "forbidden" in lower or "reddit" in lower):
        return "SITE_BLOCKED_403", message

    if platform == "tiktok" and "status code 0" in lower:
        return "TIKTOK_API_STATUS_ZERO", message

    if platform == "tiktok" and ("video not available" in lower or "empty response" in lower):
        return "TIKTOK_EXTRACTOR_UNSTABLE", message

    if (
        "cookie" in lower
        and ("stale" in lower or "expired" in lower or "invalid" in lower or "required" in lower)
    ) or "sign in" in lower or "log in" in lower:
        return "COOKIE_STALE_OR_INVALID", message

    if "ffmpeg is not installed" in lower or "ffprobe is not installed" in lower:
        return "MERGE_DEPENDENCY_MISSING", message

    if _is_generic_unsupported_error(lower):
        return "UNSUPPORTED_PLATFORM", message

    return default_code, message


def _build_platform_attempts(
    platform: Optional[str],
    normalized_url: str,
    has_cookie: bool,
    cookie_integrity_ok: bool,
    impersonation_available: bool,
) -> List[Dict[str, Any]]:
    attempts: List[Dict[str, Any]] = []
    host = _extract_host(normalized_url)
    is_dailymotion = host in DAILYMOTION_DOMAINS or host.endswith(".dailymotion.com") or host.endswith(".dai.ly")

    if platform == "reddit":
        if has_cookie and cookie_integrity_ok:
            attempts.append(
                {
                    "label": "reddit-cookie-primary",
                    "use_cookie": True,
                    "include_reddit_context": True,
                }
            )
            if impersonation_available:
                attempts.append(
                    {
                        "label": "reddit-cookie-impersonate",
                        "use_cookie": True,
                        "include_reddit_context": True,
                        "extractor_args": {"generic": {"impersonate": ["chrome"]}},
                        "impersonate": "chrome",
                    }
                )

        if ALLOW_REDDIT_PUBLIC_FALLBACK and _is_public_reddit_url(normalized_url):
            guarded_codes: Optional[set[str]] = None
            if has_cookie and cookie_integrity_ok:
                guarded_codes = {"SITE_BLOCKED_403", "COOKIE_STALE_OR_INVALID", "REDDIT_EXTRACTOR_ROUTE_FAILED"}
            anon_attempt: Dict[str, Any] = {
                "label": "reddit-anon-fallback",
                "use_cookie": False,
                "include_reddit_context": True,
                "guarded_for_codes": guarded_codes,
            }
            if impersonation_available:
                anon_attempt["extractor_args"] = {"generic": {"impersonate": ["chrome"]}}
                anon_attempt["impersonate"] = "chrome"
            attempts.append(anon_attempt)
        return attempts

    if platform == "tiktok":
        install_id = _load_or_create_tiktok_install_id()
        device_id = _load_or_create_tiktok_device_id()
        tiktok_targets = IMPERSONATION_TARGET_MATRIX.get("tiktok", [])
        if impersonation_available:
            for target in tiktok_targets:
                if has_cookie and cookie_integrity_ok:
                    attempts.append(
                        {
                            "label": f"tiktok-cookie-impersonate-{target}",
                            "use_cookie": True,
                            "impersonate": target,
                        }
                    )
                attempts.append(
                    {
                        "label": f"tiktok-anon-impersonate-{target}",
                        "use_cookie": False,
                        "impersonate": target,
                    }
                )

        if has_cookie and cookie_integrity_ok:
            attempts.append({"label": "tiktok-base-cookie", "use_cookie": True})

        attempts.append({"label": "tiktok-base-anon", "use_cookie": False})
        for idx, profile in enumerate(TIKTOK_APP_INFO_CANDIDATES):
            app_name, app_version, manifest, aid = profile
            app_info = _build_tiktok_app_info(install_id, app_name, app_version, manifest, aid)
            api_hostname = TIKTOK_API_HOSTNAME_CANDIDATES[idx % len(TIKTOK_API_HOSTNAME_CANDIDATES)]
            attempts.append(
                {
                    "label": f"tiktok-appinfo-{idx+1}",
                    "use_cookie": has_cookie and cookie_integrity_ok,
                    "extractor_args": {
                        "tiktok": {
                            "app_info": [app_info],
                            "device_id": [device_id],
                            "api_hostname": [api_hostname],
                        }
                    },
                }
            )
        attempts.append(
            {
                "label": "tiktok-deviceid",
                "use_cookie": has_cookie and cookie_integrity_ok,
                "extractor_args": {
                    "tiktok": {
                        "device_id": [device_id],
                        "api_hostname": [TIKTOK_API_HOSTNAME_CANDIDATES[0]],
                    }
                },
            }
        )
        # API-style anonymous fallback with minimal args can recover when cookie/session is noisy.
        attempts.append(
            {
                "label": "tiktok-appinfo-anon-fallback",
                "use_cookie": False,
                "extractor_args": {
                    "tiktok": {
                        "app_info": [install_id],
                        "device_id": [device_id],
                        "api_hostname": [TIKTOK_API_HOSTNAME_CANDIDATES[0]],
                    }
                },
            }
        )
        if impersonation_available:
            attempts.append(
                {
                    "label": "tiktok-generic-impersonate",
                    "use_cookie": False,
                    "impersonate": "chrome",
                    "force_generic_extractor": True,
                }
            )
        return attempts

    if platform == "youtube":
        attempts.append(
            {
                "label": "youtube-chunk-10m",
                "use_cookie": has_cookie and cookie_integrity_ok,
                "ydl_overrides": {
                    "http_chunk_size": YOUTUBE_HTTP_CHUNK_SIZE_PRIMARY,
                    "throttledratelimit": YOUTUBE_THROTTLED_RATE_LIMIT,
                },
            }
        )
        attempts.append(
            {
                "label": "youtube-chunk-4m",
                "use_cookie": has_cookie and cookie_integrity_ok,
                "ydl_overrides": {
                    "http_chunk_size": YOUTUBE_HTTP_CHUNK_SIZE_FALLBACK,
                    "throttledratelimit": YOUTUBE_THROTTLED_RATE_LIMIT,
                },
            }
        )
        attempts.append({"label": "youtube-default", "use_cookie": has_cookie and cookie_integrity_ok})
        return attempts

    if is_dailymotion:
        dailymotion_targets = IMPERSONATION_TARGET_MATRIX.get("dailymotion", [])
        if impersonation_available:
            for target in dailymotion_targets:
                attempts.append(
                    {
                        "label": f"dailymotion-impersonate-{target}",
                        "use_cookie": has_cookie and cookie_integrity_ok,
                        "impersonate": target,
                        "extractor_args": {"dailymotion": {"impersonate": [target]}},
                    }
                )
                attempts.append(
                    {
                        "label": f"dailymotion-anon-impersonate-{target}",
                        "use_cookie": False,
                        "impersonate": target,
                        "extractor_args": {"dailymotion": {"impersonate": [target]}},
                    }
                )
        attempts.append(
            {
                "label": "dailymotion-no-impersonate",
                "use_cookie": has_cookie and cookie_integrity_ok,
                "extractor_args": {"dailymotion": {"impersonate": ["false"]}},
            }
        )
        return attempts

    if platform is None:
        cookie_modes = [True, False] if has_cookie and cookie_integrity_ok else [False]
        for use_cookie in cookie_modes:
            prefix = "cookie" if use_cookie else "anon"

            attempts.append(
                {
                    "label": f"generic-{prefix}-progressive",
                    "use_cookie": use_cookie,
                    "format_override": "best[acodec!=none][vcodec!=none][protocol!*=m3u8]/best[protocol!*=m3u8]/best",
                    "ydl_overrides": {
                        "abort_on_unavailable_fragments": True,
                        "skip_unavailable_fragments": False,
                    },
                }
            )

            if impersonation_available:
                attempts.append(
                    {
                        "label": f"generic-{prefix}-progressive-impersonate-chrome",
                        "use_cookie": use_cookie,
                        "impersonate": "chrome",
                        "format_override": "best[acodec!=none][vcodec!=none][protocol!*=m3u8]/best[protocol!*=m3u8]/best",
                        "ydl_overrides": {
                            "abort_on_unavailable_fragments": True,
                            "skip_unavailable_fragments": False,
                        },
                    }
                )

            attempts.append(
                {
                    "label": f"generic-{prefix}-default",
                    "use_cookie": use_cookie,
                }
            )

            attempts.append(
                {
                    "label": f"generic-{prefix}-hls-ffmpeg-strict",
                    "use_cookie": use_cookie,
                    "ydl_overrides": {
                        "hls_prefer_native": False,
                        "external_downloader": "ffmpeg",
                        "abort_on_unavailable_fragments": True,
                        "skip_unavailable_fragments": False,
                    },
                }
            )
        return attempts

    attempts.append({"label": "default-primary", "use_cookie": has_cookie and cookie_integrity_ok})
    attempts.append(
        {
            "label": "default-same-site-context",
            "use_cookie": has_cookie and cookie_integrity_ok,
            "referer_url": normalized_url,
        }
    )
    attempts.append(
        {
            "label": "default-progressive-context",
            "use_cookie": has_cookie and cookie_integrity_ok,
            "referer_url": normalized_url,
            "format_override": "best[acodec!=none][vcodec!=none][protocol!*=m3u8]/best[protocol!*=m3u8]/best",
        }
    )
    if impersonation_available:
        attempts.append(
            {
                "label": "default-impersonate-chrome",
                "use_cookie": has_cookie and cookie_integrity_ok,
                "referer_url": normalized_url,
                "impersonate": "chrome",
            }
        )
        attempts.append(
            {
                "label": "default-generic-impersonate-chrome",
                "use_cookie": False,
                "referer_url": normalized_url,
                "impersonate": "chrome",
                "force_generic_extractor": True,
            }
        )
    if not impersonation_available:
        attempts.append(
            {
                "label": "default-no-impersonate",
                "use_cookie": has_cookie and cookie_integrity_ok,
                "extractor_args": IMPERSONATION_DISABLE_EXTRACTOR_ARGS,
            }
        )
        attempts.append(
            {
                "label": "default-generic-no-impersonate",
                "use_cookie": has_cookie and cookie_integrity_ok,
                "extractor_args": IMPERSONATION_DISABLE_EXTRACTOR_ARGS,
                "force_generic_extractor": True,
            }
        )
    attempts.append(
        {
            "label": "default-generic-context",
            "use_cookie": has_cookie and cookie_integrity_ok,
            "referer_url": normalized_url,
            "force_generic_extractor": True,
        }
    )
    return attempts


def _load_or_create_tiktok_device_id() -> str:
    runtime_dir = os.path.join(os.path.expanduser("~"), ".arsivinyo_local_runtime")
    os.makedirs(runtime_dir, exist_ok=True)
    file_path = os.path.join(runtime_dir, "tiktok_device_id.txt")
    try:
        if os.path.exists(file_path):
            with open(file_path, "r", encoding="utf-8") as f:
                existing = f.read().strip()
            if existing.isdigit() and len(existing) >= 10:
                return existing
    except Exception:
        pass

    device_id = str(random.randint(10**18, 10**19 - 1))
    try:
        with open(file_path, "w", encoding="utf-8") as f:
            f.write(device_id)
    except Exception:
        pass
    return device_id


def _load_or_create_tiktok_install_id() -> str:
    runtime_dir = os.path.join(os.path.expanduser("~"), ".arsivinyo_local_runtime")
    os.makedirs(runtime_dir, exist_ok=True)
    file_path = os.path.join(runtime_dir, "tiktok_install_id.txt")
    try:
        if os.path.exists(file_path):
            with open(file_path, "r", encoding="utf-8") as f:
                existing = f.read().strip()
            if existing.isdigit() and len(existing) >= 10:
                return existing
    except Exception:
        pass

    install_id = str(random.randint(10**18, 10**19 - 1))
    try:
        with open(file_path, "w", encoding="utf-8") as f:
            f.write(install_id)
    except Exception:
        pass
    return install_id


def _build_tiktok_app_info(install_id: str, app_name: str, app_version: str, manifest: str, aid: str) -> str:
    return f"{install_id}/{app_name}/{app_version}/{manifest}/{aid}"


def _perform_attempts(
    *,
    phase: str,
    normalized_url: str,
    platform: Optional[str],
    attempts: List[Dict[str, Any]],
    selected_cookie_file: Optional[str],
    ffmpeg_location: Optional[str],
    user_agent: str,
    download: bool,
    max_file_size_mb: int,
    merge_capable: bool,
    impersonation_available: bool,
    audio_only: bool = False,
    audio_format: str = DEFAULT_AUDIO_FORMAT,
    output_dir: Optional[str] = None,
    progress_hooks: Optional[List[Any]] = None,
    debug_logging: bool = False,
    allow_discovery_fallback: bool = True,
    deadline_ms: Optional[int] = None,
    attempt_limit: Optional[int] = None,
    per_attempt_timeout_sec: Optional[int] = None,
) -> Tuple[Optional[Dict[str, Any]], Optional[str], Optional[str], Optional[str]]:
    last_error_code: Optional[str] = None
    last_error_message: Optional[str] = None
    last_strategy: Optional[str] = None
    attempts_started = 0
    for index, strategy in enumerate(attempts, start=1):
        guarded_codes = strategy.get("guarded_for_codes")
        if guarded_codes and last_error_code not in guarded_codes:
            continue
        if attempt_limit is not None and attempts_started >= attempt_limit:
            break
        if deadline_ms is not None and _monotonic_ms() >= deadline_ms:
            last_error_code = "PREFLIGHT_FAILED"
            last_error_message = "Preflight budget exhausted"
            break

        attempt_id = str(uuid.uuid4())[:8]
        attempts_started += 1
        use_cookie = bool(strategy.get("use_cookie")) and bool(selected_cookie_file)
        active_cookie = selected_cookie_file if use_cookie else None
        label = strategy.get("label", f"{platform or 'generic'}-{index}")
        extractor_args = strategy.get("extractor_args")
        impersonate = strategy.get("impersonate")
        disable_impersonation = not impersonation_available
        if not impersonation_available:
            extractor_args = _merge_extractor_args(extractor_args, IMPERSONATION_DISABLE_EXTRACTOR_ARGS)
            impersonate = None
        include_reddit_context = bool(strategy.get("include_reddit_context", False))
        force_generic_extractor = bool(strategy.get("force_generic_extractor", False))
        ydl_overrides = strategy.get("ydl_overrides") or {}
        format_override = strategy.get("format_override")
        attempt_url = str(strategy.get("url_override") or normalized_url)
        referer_url = strategy.get("referer_url")
        last_strategy = label
        _set_runtime_diag("platformStrategyLast", label)
        if platform == "youtube":
            _set_runtime_diag("youtubeChunkProfileLast", ydl_overrides.get("http_chunk_size"))

        opts = _common_ydl_opts(
            attempt_url,
            platform,
            active_cookie,
            ffmpeg_location,
            user_agent,
            extractor_args=extractor_args,
            include_reddit_context=include_reddit_context,
            impersonate=impersonate,
            disable_impersonation=disable_impersonation,
            referer_url=referer_url,
            debug_strategy_label=label,
            debug_attempt_id=attempt_id,
            debug_logging=debug_logging,
        )
        opts["format"] = format_override or _build_format_selector(max_file_size_mb, merge_capable)
        _set_runtime_diag("formatSelectorLast", opts["format"])
        if merge_capable:
            opts["merge_output_format"] = "mp4"
        if ydl_overrides:
            opts.update(ydl_overrides)
        if output_dir:
            opts["outtmpl"] = os.path.join(output_dir, "%(title)s.%(ext)s")
        if progress_hooks:
            opts["progress_hooks"] = progress_hooks
        if force_generic_extractor:
            opts["force_generic_extractor"] = True
        if audio_only and download:
            _apply_audio_postprocessing(opts, audio_format)

        _append_diag_target(impersonate)

        _push_attempt_trace(
            {
                "timeMs": _now_ms(),
                "phase": phase,
                "attemptId": attempt_id,
                "strategy": label,
                "url": attempt_url,
                "platform": platform,
                "cookieUsed": bool(active_cookie),
                "retryIndex": index,
                "extractorArgs": extractor_args or {},
                "impersonate": impersonate,
                "forceGenericExtractor": force_generic_extractor,
                "format": opts.get("format"),
                "httpChunkSize": opts.get("http_chunk_size"),
                "throttledRateLimit": opts.get("throttledratelimit"),
            }
        )

        try:
            with yt_dlp.YoutubeDL(opts) as ydl:
                info = _extract_info_with_retry(
                    ydl,
                    attempt_url,
                    download=download,
                    max_attempts=1 if phase == "preflight" and per_attempt_timeout_sec is not None else 3,
                    timeout_sec=per_attempt_timeout_sec,
                    deadline_ms=deadline_ms if phase == "preflight" else None,
                    debug_logging=debug_logging,
                    strategy_label=label,
                    attempt_id=attempt_id,
                )
                if "entries" in info and info["entries"]:
                    info = info["entries"][0]
                if download:
                    downloaded_file = _resolve_downloaded_file(info, output_dir)
                    if not downloaded_file or not os.path.exists(downloaded_file):
                        raise RuntimeError("Download finished but output file could not be found")
                    if os.path.getsize(downloaded_file) <= 0:
                        raise RuntimeError("The downloaded file is empty")
                _set_runtime_diag("lastExtractorKey", info.get("extractor_key"))
                if impersonate:
                    _set_runtime_diag("impersonationResolvedTargetLast", impersonate)
                    _debug_log(debug_logging, f"IMP_TARGET_SELECTED target={impersonate} strategy={label}")
                _push_attempt_trace(
                    {
                        "timeMs": _now_ms(),
                        "phase": phase,
                        "attemptId": attempt_id,
                        "strategy": label,
                        "status": "success",
                        "extractorKey": info.get("extractor_key"),
                    }
                )
                return info, None, None, label
        except Exception as exc:
            code, message = _classify_exception(
                exc,
                "PREFLIGHT_FAILED" if phase == "preflight" else "DOWNLOAD_FAILED",
                platform=platform,
                context_url=attempt_url,
            )
            tool_output = _bounded_tool_output(message)
            if tool_output:
                _set_runtime_diag("toolOutputLast", tool_output)
            if _is_impersonation_unavailable_message(message):
                required_extractor = _extract_error_extractor_key(message)
                required_targets = _extract_required_targets_from_message(message)
                if required_extractor:
                    _set_runtime_diag("impersonationRequiredByExtractorLast", required_extractor)
                    _debug_log(debug_logging, f"IMP_TARGET_REQUIRED extractor={required_extractor} targets={required_targets}")
                if code in {"IMPERSONATION_DEPENDENCY_MISSING", "IMPERSONATION_RUNTIME_UNAVAILABLE", "IMPERSONATION_BOOTSTRAP_FAILED"}:
                    _set_runtime_diag("impersonationRuntimeAvailable", False)
                    _set_runtime_diag("impersonationEnabled", False)
                # Some extractors force impersonation (for example dailymotion: firefox).
                # Retry once with explicit per-extractor no-impersonate args before failing.
                forced_args: Dict[str, Dict[str, List[str]]] = dict(IMPERSONATION_DISABLE_EXTRACTOR_ARGS)
                extractor_key = required_extractor
                if extractor_key:
                    forced_args[extractor_key] = {"impersonate": ["false"]}

                retry_id = str(uuid.uuid4())[:8]
                retry_label = f"{label}-no-impersonate-retry"
                retry_extractor_args = _merge_extractor_args(extractor_args, forced_args)
                retry_opts = _common_ydl_opts(
                    attempt_url,
                    platform,
                    active_cookie,
                    ffmpeg_location,
                    user_agent,
                    extractor_args=retry_extractor_args,
                    include_reddit_context=include_reddit_context,
                    impersonate=None,
                    disable_impersonation=True,
                    referer_url=referer_url,
                    debug_strategy_label=retry_label,
                    debug_attempt_id=retry_id,
                    debug_logging=debug_logging,
                )
                retry_opts["format"] = format_override or _build_format_selector(max_file_size_mb, merge_capable)
                if merge_capable:
                    retry_opts["merge_output_format"] = "mp4"
                if ydl_overrides:
                    retry_opts.update(ydl_overrides)
                if output_dir:
                    retry_opts["outtmpl"] = os.path.join(output_dir, "%(title)s.%(ext)s")
                if progress_hooks:
                    retry_opts["progress_hooks"] = progress_hooks
                if force_generic_extractor:
                    retry_opts["force_generic_extractor"] = True
                if audio_only and download:
                    _apply_audio_postprocessing(retry_opts, audio_format)

                _push_attempt_trace(
                    {
                        "timeMs": _now_ms(),
                        "phase": phase,
                        "attemptId": retry_id,
                        "strategy": retry_label,
                        "url": attempt_url,
                        "platform": platform,
                        "cookieUsed": bool(active_cookie),
                        "retryIndex": index,
                        "extractorArgs": retry_extractor_args or {},
                        "impersonate": None,
                        "forceGenericExtractor": force_generic_extractor,
                    }
                )

                try:
                    with yt_dlp.YoutubeDL(retry_opts) as ydl:
                        retry_info = _extract_info_with_retry(
                            ydl,
                            attempt_url,
                            download=download,
                            max_attempts=1 if phase == "preflight" and per_attempt_timeout_sec is not None else 3,
                            timeout_sec=per_attempt_timeout_sec,
                            deadline_ms=deadline_ms if phase == "preflight" else None,
                            debug_logging=debug_logging,
                            strategy_label=retry_label,
                            attempt_id=retry_id,
                        )
                        if "entries" in retry_info and retry_info["entries"]:
                            retry_info = retry_info["entries"][0]
                        _set_runtime_diag("lastExtractorKey", retry_info.get("extractor_key"))
                        _set_runtime_diag("platformStrategyLast", retry_label)
                        _set_runtime_diag("impersonationResolvedTargetLast", None)
                        _push_attempt_trace(
                            {
                                "timeMs": _now_ms(),
                                "phase": phase,
                                "attemptId": retry_id,
                                "strategy": retry_label,
                                "status": "success",
                                "extractorKey": retry_info.get("extractor_key"),
                            }
                        )
                        return retry_info, None, None, retry_label
                except Exception as retry_exc:
                    code, message = _classify_exception(
                        retry_exc,
                        "PREFLIGHT_FAILED" if phase == "preflight" else "DOWNLOAD_FAILED",
                        platform=platform,
                        context_url=attempt_url,
                    )
                    tool_output = _bounded_tool_output(message)
                    if tool_output:
                        _set_runtime_diag("toolOutputLast", tool_output)
                    if _is_impersonation_unavailable_message(message) and required_extractor:
                        _set_runtime_diag("impersonationRequiredByExtractorLast", required_extractor)
            redacted = _redact_text(message)
            _set_runtime_diag("lastRawYtDlpError", redacted)
            _push_attempt_trace(
                {
                    "timeMs": _now_ms(),
                    "phase": phase,
                    "attemptId": attempt_id,
                    "strategy": label,
                    "status": "failure",
                    "errorCode": code,
                    "errorMessage": redacted,
                    "retryablePreflight": _is_retryable_preflight_failure(code, message) if phase == "preflight" else False,
                }
            )
            if _should_replace_last_error(last_error_code, last_error_message, code, message):
                last_error_code = code
                last_error_message = message

    if allow_discovery_fallback and platform is None and _should_try_generic_discovery(last_error_code, last_error_message):
        discovery_attempts = _build_generic_discovery_attempts(
            normalized_url,
            selected_cookie_file,
            user_agent,
            debug_logging=debug_logging,
        )
        if discovery_attempts:
            _debug_log(debug_logging, f"generic discovery retry attempts={len(discovery_attempts)}")
            info, fail_code, fail_message, strategy = _perform_attempts(
                phase=phase,
                normalized_url=normalized_url,
                platform=platform,
                attempts=discovery_attempts,
                selected_cookie_file=selected_cookie_file,
                ffmpeg_location=ffmpeg_location,
                user_agent=user_agent,
                download=download,
                max_file_size_mb=max_file_size_mb,
                merge_capable=merge_capable,
                impersonation_available=impersonation_available,
                audio_only=audio_only,
                output_dir=output_dir,
                progress_hooks=progress_hooks,
                debug_logging=debug_logging,
                allow_discovery_fallback=False,
                deadline_ms=deadline_ms,
                attempt_limit=None,
                per_attempt_timeout_sec=per_attempt_timeout_sec,
            )
            if info is not None:
                return info, None, None, strategy
            if _should_replace_last_error(last_error_code, last_error_message, fail_code, fail_message):
                last_error_code = fail_code
                last_error_message = fail_message
                last_strategy = strategy

    return None, last_error_code, last_error_message, last_strategy


def _sanitize_filename(name: str) -> str:
    name = re.sub(r"[^a-zA-Z0-9._-]", "_", name).strip("._")
    return name[:200] if name else "download"


# Characters that are illegal in filenames on Android / FAT / exFAT / SMB, plus
# ASCII control characters. Everything else (spaces, Unicode letters, most
# punctuation) is preserved.
_AUDIO_TITLE_ILLEGAL = re.compile(r'[/\\:*?"<>|\x00-\x1f]')


def _sanitize_audio_title(name: str) -> str:
    """Sanitize an audio track title into a human-readable filename stem.

    Unlike ``_sanitize_filename`` (which slugs everything down to
    ``[A-Za-z0-9._-]`` and turns spaces into underscores), this keeps spaces and
    Unicode so a song titled "This song is amazing" stays "This song is amazing"
    rather than "This_song_is_amazing". Only filesystem-illegal characters and
    control characters are stripped. Collision de-duplication is intentionally
    NOT done here — that needs a target directory and is handled by the Kotlin
    MediaStore layer when the file lands in Music/Arsivinyo.
    """
    name = (name or "").strip()
    name = _AUDIO_TITLE_ILLEGAL.sub("", name)
    name = re.sub(r"\s+", " ", name)
    # Trailing dots/spaces are hostile on Windows/SMB; a leading dot hides the file.
    name = name.strip(". ")
    if not name:
        return "audio"
    return name[:150]


def _normalize_audio_format(audio_format: Optional[str]) -> str:
    """Map an arbitrary caller-supplied format onto one we can actually encode."""
    normalized = (audio_format or "").strip().lower()
    return normalized if normalized in SUPPORTED_AUDIO_FORMATS else DEFAULT_AUDIO_FORMAT


def _apply_audio_postprocessing(
    opts: Dict[str, Any], audio_format: str = DEFAULT_AUDIO_FORMAT
) -> None:
    """Mutate yt-dlp opts in place for an audio-only download.

    Takes the best available audio stream and produces either a **FLAC** file (the
    default) or an **M4A (AAC)** file, then writes metadata into it.

    Why FLAC by default: every source we download is already lossy (YouTube serves
    Opus or AAC). Encoding that to AAC again stacks a second generation of loss on top
    of the first for no benefit. FLAC is lossless, so whatever the decoder produced is
    what gets stored — the only cost is roughly 3x the file size. The bundled FFmpeg
    has ``CONFIG_FLAC_ENCODER``, so this needs no new native dependency.

    We pin the output to 16-bit with triangular (TPDF) dither. FFmpeg's FLAC encoder
    would otherwise pick a sample format on its own, and the dither matters when the
    decoder hands us float samples: truncating float to 16-bit without it adds
    correlated quantization distortion instead of benign noise. 16 bits is the right
    depth here precisely because the source is lossy — there is nothing below that
    noise floor except the source codec's own artifacts, so 24-bit would spend 50%
    more storage encoding those more accurately.

    Why M4A and not MP3 for the lossy option: the bundled FFmpeg is built without an
    MP3 encoder (no ``libmp3lame``/``libshine``), so it physically cannot transcode to
    MP3 — attempting it fails with ``Unknown encoder 'libmp3lame'``. AAC uses FFmpeg's
    always-available native ``aac`` encoder. When the source is already AAC (the common
    YouTube case) yt-dlp losslessly remuxes it into M4A; only a non-AAC source (e.g.
    Opus) gets re-encoded, at a high 256k bitrate.

    Sample rate is deliberately NOT pinned in either branch. Resampling a 48 kHz source
    to 44.1 kHz would degrade it for no reason — the rate the decoder produces is the
    rate we keep.

    Thumbnail: we ``writethumbnail`` (downloads the cover art as a sidecar file) but
    deliberately do NOT use ``EmbedThumbnail``. The bundled FFmpeg is also built
    without image encoders (no mjpeg/png), so embedding — which transcodes the
    YouTube ``.webp`` thumbnail through FFmpeg — fails with
    ``Error selecting an encoder`` on the video (image) stream. Instead the downloaded
    thumbnail file is handed to Kotlin (see ``_resolve_thumbnail_file``), which stores
    it verbatim as a sidecar JPEG/WebP that ``expo-image`` renders directly. Track
    title/artist are still written into the file via ``FFmpegMetadata`` (stream copy,
    no encoder needed).
    """
    resolved = _normalize_audio_format(audio_format)

    opts.pop("merge_output_format", None)
    opts["writethumbnail"] = True

    if resolved == AUDIO_FORMAT_FLAC:
        # Any source will do: the output is lossless whatever arrives, so take the best
        # stream available rather than constraining the codec.
        opts["format"] = "bestaudio/best"
        extract: Dict[str, Any] = {"key": "FFmpegExtractAudio", "preferredcodec": "flac"}
        # `preferredquality` is a bitrate/VBR knob and is meaningless for a lossless
        # codec, so it is omitted rather than set to a value yt-dlp would ignore.
        # Verified on device: requesting s32 here produced a 24-bit FLAC, and s16 is
        # what FFmpeg picks by itself for this codec — so these arguments demonstrably
        # reach FFmpeg under the "extractaudio" key rather than being silently dropped.
        # That is what makes the dither below real rather than assumed.
        opts["postprocessor_args"] = {
            "extractaudio": ["-sample_fmt", "s16", "-dither_method", "triangular"],
        }
    else:
        # Ask for a source that is already AAC. yt-dlp's FFmpegExtractAudioPP stream-copies
        # when the file's codec is `aac` and the target is `m4a`, so this turns a full
        # re-encode into a remux. That matters far more than it sounds: measured on the
        # maintainer's device, the bundled FFmpeg encodes FLAC at about 143x realtime but
        # AAC at only 23x, so an eight-hour track cost roughly 21 minutes of transcoding.
        # A remux costs seconds.
        #
        # It also stops a pointless quality loss. Left alone, `bestaudio` picks YouTube's
        # Opus stream, which then has to be decoded and re-encoded to AAC — a second
        # generation of lossy compression to end up at a similar bitrate.
        #
        # Falls back to any audio stream when no AAC one exists, which re-encodes exactly
        # as before rather than failing.
        opts["format"] = "bestaudio[acodec^=mp4a]/bestaudio/best"
        extract = {"key": "FFmpegExtractAudio", "preferredcodec": "m4a", "preferredquality": "256"}
        opts.pop("postprocessor_args", None)

    opts["postprocessors"] = [
        extract,
        {"key": "FFmpegMetadata", "add_metadata": True},
    ]


def _resolve_downloaded_file(info: Dict[str, Any], output_dir: Optional[str]) -> Optional[str]:
    downloaded_file = None
    req = info.get("requested_downloads") or []
    for item in reversed(req):
        fp = item.get("filepath")
        if fp and os.path.exists(fp):
            downloaded_file = fp
            break

    if not downloaded_file:
        fallback = info.get("filepath") or info.get("_filename")
        if fallback and os.path.exists(fallback):
            downloaded_file = fallback

    if not downloaded_file and output_dir:
        candidate = os.path.join(output_dir, f"{_sanitize_filename(info.get('title') or 'download')}.{info.get('ext') or 'mp4'}")
        base, _ = os.path.splitext(candidate)
        mp4_candidate = f"{base}.mp4"
        downloaded_file = mp4_candidate if os.path.exists(mp4_candidate) else candidate

    if downloaded_file and os.path.exists(downloaded_file):
        return downloaded_file
    return None


def _resolve_thumbnail_file(info: Dict[str, Any]) -> Optional[str]:
    """Find the cover-art image yt-dlp wrote to disk via ``writethumbnail``.

    Returns the highest-quality written thumbnail's path (yt-dlp orders thumbnails
    worst→best, so we scan from the end). The file is left as-is (webp/jpg/png) for
    Kotlin to copy into the music library's sidecar thumbnail store — we never
    transcode it, since the bundled FFmpeg has no image encoder.
    """
    thumbnails = info.get("thumbnails") or []
    for thumb in reversed(thumbnails):
        if not isinstance(thumb, dict):
            continue
        fp = thumb.get("filepath")
        if fp and os.path.exists(fp):
            return fp
    return None


def _parse_speed_bytes_per_sec(raw: Any) -> Optional[float]:
    if isinstance(raw, (int, float)):
        value = float(raw)
        return value if value > 0 else None

    text = str(raw or "").strip().replace(",", "")
    if not text:
        return None

    match = SPEED_PER_SEC_PATTERN.search(text)
    if not match:
        return None

    try:
        value = float(match.group("value"))
    except (TypeError, ValueError):
        return None

    unit = str(match.group("unit") or "").lower()
    multiplier = {
        "b": 1.0,
        "kb": 1000.0,
        "kib": 1024.0,
        "mb": 1000.0 * 1000.0,
        "mib": 1024.0 * 1024.0,
        "gb": 1000.0 * 1000.0 * 1000.0,
        "gib": 1024.0 * 1024.0 * 1024.0,
    }.get(unit)
    if multiplier is None:
        return None
    speed = value * multiplier
    return speed if speed > 0 else None


def _should_emit_progress_update(
    *,
    current_status: str,
    current_percent: Optional[float],
    now_ms: int,
    last_status: Optional[str],
    last_percent: Optional[float],
    last_emit_ms: int,
) -> bool:
    if current_status != last_status:
        return True
    if current_status != "downloading":
        return True
    if last_percent is None or current_percent is None:
        return (now_ms - last_emit_ms) >= PROGRESS_WRITE_MIN_INTERVAL_MS
    if abs(float(current_percent) - float(last_percent)) >= PROGRESS_WRITE_MIN_DELTA_PERCENT:
        return True
    return (now_ms - last_emit_ms) >= PROGRESS_WRITE_MIN_INTERVAL_MS


def _coerce_monotonic_download_percent(current_percent: float, last_percent: Optional[float]) -> float:
    current = max(0.0, min(100.0, float(current_percent)))
    if last_percent is None:
        return current
    # yt-dlp can report multi-stage progress (e.g., video then audio) where percentage drops.
    # Keep progress monotonic to avoid visual restart/flicker in UI.
    return max(current, max(0.0, min(100.0, float(last_percent))))


def _write_progress(
    progress_file_path: Optional[str],
    progress_percent: Optional[float],
    message: str,
    status: str,
    speed_bytes_per_sec: Optional[float] = None,
) -> None:
    if not progress_file_path:
        return
    try:
        payload: Dict[str, Any] = {
            "message": message,
            "status": status,
        }
        if progress_percent is not None:
            payload["progressPercent"] = max(0.0, min(100.0, float(progress_percent)))
        if isinstance(speed_bytes_per_sec, (int, float)) and float(speed_bytes_per_sec) > 0:
            payload["speedBytesPerSec"] = float(speed_bytes_per_sec)

        target_dir = os.path.dirname(progress_file_path)
        if target_dir:
            os.makedirs(target_dir, exist_ok=True)
        tmp_path = f"{progress_file_path}.tmp"
        with open(tmp_path, "w", encoding="utf-8") as f:
            json.dump(payload, f, ensure_ascii=False)
        os.replace(tmp_path, progress_file_path)
    except Exception:
        # Progress reporting should never fail the download.
        return


def _normalize_video_timestamp(file_path: str, ffmpeg_location: Optional[str], debug_logging: bool = False) -> bool:
    _, ext = os.path.splitext(file_path)
    ext = ext.lower()
    if ext not in VIDEO_TIMESTAMP_EXTENSIONS:
        return False

    ffmpeg_binary = _resolve_ffmpeg_binary(ffmpeg_location)
    if not ffmpeg_binary or not os.path.exists(ffmpeg_binary):
        _debug_log(debug_logging, f"timestamp normalize skipped: ffmpeg missing path={ffmpeg_binary}")
        return False

    base, ext = os.path.splitext(file_path)
    temp_path = f"{base}.timestamp_refresh{ext}"
    creation_time = datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")

    cmd = [
        ffmpeg_binary,
        "-y",
        "-loglevel",
        "error",
        "-i",
        file_path,
        "-map",
        "0",
        "-c",
        "copy",
        "-metadata",
        f"creation_time={creation_time}",
        "-movflags",
        "+use_metadata_tags",
        temp_path,
    ]

    try:
        _debug_log(debug_logging, f"timestamp normalize start file={file_path} ffmpeg={ffmpeg_binary}")
        result = subprocess.run(cmd, capture_output=True, check=False, timeout=120)
        if result.returncode != 0 or not os.path.exists(temp_path):
            stderr = (result.stderr or b"").decode("utf-8", errors="replace").strip().splitlines()
            _debug_log(debug_logging, f"timestamp normalize failed exit={result.returncode} stderr={stderr[0] if stderr else 'n/a'}")
            return False

        if os.path.getsize(temp_path) <= 0:
            _debug_log(debug_logging, "timestamp normalize failed: empty temp output")
            return False

        os.replace(temp_path, file_path)
        os.utime(file_path, None)
        verified = _verify_video_creation_time(file_path, ffmpeg_location, debug_logging)
        if not verified:
            _debug_log(debug_logging, "timestamp normalize verification failed")
            return False
        _debug_log(debug_logging, "timestamp normalize success")
        return True
    except Exception as exc:
        _debug_log(debug_logging, f"timestamp normalize exception: {exc}")
        return False
    finally:
        if os.path.exists(temp_path):
            try:
                os.remove(temp_path)
            except OSError:
                pass


def _verify_video_creation_time(file_path: str, ffmpeg_location: Optional[str], debug_logging: bool = False) -> bool:
    ffprobe_binary = _resolve_ffprobe_binary(ffmpeg_location)
    if not ffprobe_binary or not os.path.exists(ffprobe_binary):
        _debug_log(debug_logging, f"timestamp verify skipped: ffprobe missing path={ffprobe_binary}")
        return False

    probes = [
        [
            ffprobe_binary,
            "-v",
            "error",
            "-show_entries",
            "format_tags=creation_time",
            "-of",
            "default=noprint_wrappers=1:nokey=1",
            file_path,
        ],
        [
            ffprobe_binary,
            "-v",
            "error",
            "-show_entries",
            "stream_tags=creation_time",
            "-of",
            "default=noprint_wrappers=1:nokey=1",
            file_path,
        ],
    ]

    for cmd in probes:
        try:
            result = subprocess.run(cmd, capture_output=True, check=False, timeout=10, text=True)
            output = (result.stdout or "").strip()
            if result.returncode == 0 and output:
                _debug_log(debug_logging, f"timestamp verify ok output={output.splitlines()[0]}")
                return True
        except Exception as exc:
            _debug_log(debug_logging, f"timestamp verify exception: {exc}")

    return False


def preflight(
    url: str,
    cookies_dir: str,
    cookie_profile: Optional[str] = None,
    max_file_size_mb: int = 0,
    ffmpeg_path: Optional[str] = None,
    cookie_file: Optional[str] = None,
    force_no_cookie: bool = False,
    merge_capable: bool = True,
    user_agent: str = DEFAULT_HTTP_USER_AGENT,
    debug_logging: bool = False,
) -> str:
    try:
        _begin_call_diagnostics()
        _debug_log(
            debug_logging,
            f"preflight start url={url} ffmpeg_path={ffmpeg_path} cookie_file={'yes' if cookie_file else 'no'} "
            f"force_no_cookie={force_no_cookie} merge_capable={merge_capable}",
        )
        platform = _detect_cookie_platform(url)
        selected_cookie_file = cookie_file if cookie_file and os.path.exists(cookie_file) else None
        if not selected_cookie_file and not force_no_cookie:
            selected_cookie_file = _resolve_cookie_file(cookies_dir, platform, cookie_profile)

        normalized_url, normalize_error = _normalize_input_url(
            url,
            platform,
            user_agent,
            cookie_file=selected_cookie_file,
            debug_logging=debug_logging,
        )
        if normalize_error:
            _set_runtime_diag("normalizedUrlLast", url)
            return _result(False, "REDDIT_SHARE_URL_RESOLUTION_FAILED", f"Could not resolve Reddit share URL: {normalize_error}")
        normalized_url = normalized_url or url
        _set_runtime_diag("normalizedUrlLast", normalized_url)
        platform = _detect_cookie_platform(normalized_url)
        known_extractor = _detect_known_ytdlp_extractor(normalized_url, debug_logging)
        has_known_extractor = known_extractor is not None

        if not selected_cookie_file and not force_no_cookie:
            selected_cookie_file = _resolve_cookie_file(cookies_dir, platform, cookie_profile)

        cookie_check = _inspect_cookie_file(selected_cookie_file, platform)
        _set_runtime_diag("lastCookieCheck", cookie_check)
        cookie_issue = _cookie_integrity_error(cookie_check)
        if cookie_issue:
            return _result(False, cookie_issue[0], cookie_issue[1], platform=platform)

        ffmpeg_location = _resolve_ffmpeg_location(ffmpeg_path)
        effective_merge_capable, merge_reason = _resolve_merge_capability(ffmpeg_location, merge_capable, debug_logging)
        impersonation_available = _is_impersonation_runtime_available(debug_logging)

        attempts = _build_platform_attempts(
            platform=platform,
            normalized_url=normalized_url,
            has_cookie=bool(selected_cookie_file),
            cookie_integrity_ok=cookie_issue is None,
            impersonation_available=impersonation_available,
        )
        if platform == "reddit" and not attempts:
            return _result(False, "REDDIT_COOKIE_REQUIRED", "Reddit download requires a valid Reddit cookie profile.")

        preflight_profile_platform = platform or ("known-extractor" if has_known_extractor else None)
        preflight_start_ms, preflight_deadline_ms, preflight_attempt_limit, preflight_timeout_sec = _begin_preflight_profile(preflight_profile_platform)
        if platform is None and not has_known_extractor:
            if _is_static_media_candidate(normalized_url):
                _set_runtime_diag("staticMediaCandidateCount", 1)
            else:
                static_count, static_error = _probe_static_media_candidates(normalized_url, user_agent, debug_logging)
                if static_error:
                    _finish_preflight_profile(preflight_start_ms)
                    return _result(
                        False,
                        "PREFLIGHT_FAILED",
                        f"STATIC_PAGE_FETCH_FAILED: {_redact_text(static_error)}",
                        platform=platform,
                        normalized_url=normalized_url,
                        strategy="static-sample",
                        preflight_strategy="static-sample",
                        retryable_preflight=True,
                    )
                if static_count <= 0:
                    _finish_preflight_profile(preflight_start_ms)
                    return _result(
                        False,
                        "PREFLIGHT_FAILED",
                        "STATIC_PAGE_NO_MEDIA_CANDIDATES",
                        platform=platform,
                        normalized_url=normalized_url,
                        strategy="static-sample",
                        preflight_strategy="static-sample",
                        retryable_preflight=True,
                    )

        info, fail_code, fail_message, strategy = _perform_attempts(
            phase="preflight",
            normalized_url=normalized_url,
            platform=platform,
            attempts=attempts,
            selected_cookie_file=selected_cookie_file,
            ffmpeg_location=ffmpeg_location,
            user_agent=user_agent,
            download=False,
            max_file_size_mb=max_file_size_mb,
            merge_capable=effective_merge_capable,
            impersonation_available=impersonation_available,
            debug_logging=debug_logging,
            deadline_ms=preflight_deadline_ms,
            attempt_limit=preflight_attempt_limit,
            per_attempt_timeout_sec=preflight_timeout_sec,
        )
        _finish_preflight_profile(preflight_start_ms)
        if info is None:
            code = fail_code or "PREFLIGHT_FAILED"
            message = fail_message or "Preflight failed"
            return _result(
                False,
                code,
                message,
                platform=platform,
                normalized_url=normalized_url,
                strategy=strategy,
                preflight_strategy=strategy,
                retryable_preflight=_is_retryable_preflight_failure(code, message),
            )

        if not effective_merge_capable and not _has_progressive_format(info):
            _debug_log(debug_logging, "preflight failed: no progressive format and merge unavailable")
            return _result(
                False,
                "MERGE_DEPENDENCY_MISSING",
                merge_reason
                or "This media requires stream merge but ffmpeg/ffprobe merge runtime is unavailable.",
                platform=platform,
            )

        size_mb, _ = _estimate_file_size_mb(info)
        if max_file_size_mb > 0 and size_mb > 0 and size_mb > max_file_size_mb:
            return _result(
                False,
                "FILE_TOO_LARGE",
                f"File size ({size_mb:.1f}MB) exceeds local limit ({max_file_size_mb}MB)",
                estimated_size_mb=round(size_mb, 1),
                platform=platform,
            )

        extractor_key = str(info.get("extractor_key") or "")
        if _should_fail_reddit_generic_route(platform, normalized_url, extractor_key):
            return _result(
                False,
                "REDDIT_EXTRACTOR_ROUTE_FAILED",
                "Reddit URL resolved through generic extractor and remained blocked.",
                platform=platform,
                normalized_url=normalized_url,
            )

        return _result(
            True,
            "PREFLIGHT_OK",
            "Preflight successful",
            estimated_size_mb=round(size_mb, 1) if size_mb > 0 else None,
            platform=platform,
            normalized_url=normalized_url,
            strategy=strategy,
            extractor_key=extractor_key,
        )
    except Exception as exc:
        code, message = _classify_exception(
            exc,
            "PREFLIGHT_FAILED",
            platform=_detect_cookie_platform(url),
            context_url=url,
        )
        tool_output = _bounded_tool_output(message)
        if tool_output:
            _set_runtime_diag("toolOutputLast", tool_output)
        _debug_log(debug_logging, f"preflight exception code={code} message={message}")
        return _result(False, code, message, retryable_preflight=_is_retryable_preflight_failure(code, message))


def run_download(
    url: str,
    output_dir: str,
    cookies_dir: str,
    cookie_profile: Optional[str] = None,
    max_file_size_mb: int = 0,
    cancel_flag_path: Optional[str] = None,
    progress_file_path: Optional[str] = None,
    ffmpeg_path: Optional[str] = None,
    cookie_file: Optional[str] = None,
    force_no_cookie: bool = False,
    merge_capable: bool = True,
    audio_only: bool = False,
    audio_format: str = DEFAULT_AUDIO_FORMAT,
    user_agent: str = DEFAULT_HTTP_USER_AGENT,
    debug_logging: bool = False,
) -> str:
    try:
        _begin_call_diagnostics()
        _debug_log(
            debug_logging,
            f"download start url={url} output_dir={output_dir} ffmpeg_path={ffmpeg_path} "
            f"cookie_file={'yes' if cookie_file else 'no'} force_no_cookie={force_no_cookie} merge_capable={merge_capable}",
        )
        _write_progress(progress_file_path, 0.0, "Preparing download", "starting")
        if _is_cancel_requested(cancel_flag_path):
            return _result(False, "DOWNLOAD_CANCELLED", "Cancellation requested")

        platform = _detect_cookie_platform(url)
        selected_cookie_file = cookie_file if cookie_file and os.path.exists(cookie_file) else None
        if not selected_cookie_file and not force_no_cookie:
            selected_cookie_file = _resolve_cookie_file(cookies_dir, platform, cookie_profile)

        normalized_url, normalize_error = _normalize_input_url(
            url,
            platform,
            user_agent,
            cookie_file=selected_cookie_file,
            debug_logging=debug_logging,
        )
        if normalize_error:
            _set_runtime_diag("normalizedUrlLast", url)
            return _result(False, "REDDIT_SHARE_URL_RESOLUTION_FAILED", f"Could not resolve Reddit share URL: {normalize_error}")
        normalized_url = normalized_url or url
        _set_runtime_diag("normalizedUrlLast", normalized_url)
        platform = _detect_cookie_platform(normalized_url)
        known_extractor = _detect_known_ytdlp_extractor(normalized_url, debug_logging)
        has_known_extractor = known_extractor is not None

        if not selected_cookie_file and not force_no_cookie:
            selected_cookie_file = _resolve_cookie_file(cookies_dir, platform, cookie_profile)

        cookie_check = _inspect_cookie_file(selected_cookie_file, platform)
        _set_runtime_diag("lastCookieCheck", cookie_check)
        cookie_issue = _cookie_integrity_error(cookie_check)
        if cookie_issue:
            return _result(False, cookie_issue[0], cookie_issue[1], platform=platform)

        os.makedirs(output_dir, exist_ok=True)

        ffmpeg_location = _resolve_ffmpeg_location(ffmpeg_path)
        effective_merge_capable, merge_reason = _resolve_merge_capability(ffmpeg_location, merge_capable, debug_logging)
        impersonation_available = _is_impersonation_runtime_available(debug_logging)

        attempts = _build_platform_attempts(
            platform=platform,
            normalized_url=normalized_url,
            has_cookie=bool(selected_cookie_file),
            cookie_integrity_ok=cookie_issue is None,
            impersonation_available=impersonation_available,
        )
        if platform == "reddit" and not attempts:
            return _result(False, "REDDIT_COOKIE_REQUIRED", "Reddit download requires a valid Reddit cookie profile.")

        preflight_profile_platform = platform or ("known-extractor" if has_known_extractor else None)
        preflight_start_ms, preflight_deadline_ms, preflight_attempt_limit, preflight_timeout_sec = _begin_preflight_profile(preflight_profile_platform)

        def _progress_hook(progress: Dict[str, Any]) -> None:
            if _is_cancel_requested(cancel_flag_path):
                raise RuntimeError("DOWNLOAD_CANCELLED")
            nonlocal progress_write_count, last_progress_emit_ms, last_progress_percent, last_progress_status, last_download_percent_emitted
            status = str(progress.get("status") or "").lower()
            if status == "downloading":
                percent: Optional[float] = None
                downloaded = progress.get("downloaded_bytes")
                total = progress.get("total_bytes") or progress.get("total_bytes_estimate")
                try:
                    if isinstance(downloaded, (int, float)) and isinstance(total, (int, float)) and float(total) > 0:
                        percent = (float(downloaded) / float(total)) * 100.0
                    else:
                        percent_str = str(progress.get("_percent_str") or "").strip().replace("%", "")
                        if percent_str:
                            percent = float(percent_str)
                except (TypeError, ValueError):
                    percent = None
                speed_bytes_per_sec = _parse_speed_bytes_per_sec(progress.get("speed"))
                if speed_bytes_per_sec is None:
                    speed_bytes_per_sec = _parse_speed_bytes_per_sec(progress.get("_speed_str"))
                if percent is not None:
                    raw_percent = percent
                    percent = _coerce_monotonic_download_percent(percent, last_download_percent_emitted)
                    if debug_logging and raw_percent + 0.01 < percent:
                        _debug_log(
                            debug_logging,
                            f"progress clamped raw={raw_percent:.2f} monotonic={percent:.2f}",
                        )
                    now_ms = _now_ms()
                    if _should_emit_progress_update(
                        current_status="downloading",
                        current_percent=percent,
                        now_ms=now_ms,
                        last_status=last_progress_status,
                        last_percent=last_progress_percent,
                        last_emit_ms=last_progress_emit_ms,
                    ):
                        _write_progress(
                            progress_file_path,
                            percent,
                            "Downloading media",
                            "downloading",
                            speed_bytes_per_sec=speed_bytes_per_sec,
                        )
                        progress_write_count += 1
                        last_progress_emit_ms = now_ms
                        last_progress_percent = percent
                        last_download_percent_emitted = percent
                        last_progress_status = "downloading"
                        _set_runtime_diag("progressWritesLast", progress_write_count)
            elif status == "finished":
                _write_progress(progress_file_path, 99.0, "Processing media", "processing")
                progress_write_count += 1
                last_progress_emit_ms = _now_ms()
                last_progress_percent = 99.0
                last_download_percent_emitted = max(last_download_percent_emitted or 0.0, 99.0)
                last_progress_status = "processing"
                _set_runtime_diag("progressWritesLast", progress_write_count)

        progress_write_count = 0
        last_progress_emit_ms = 0
        last_progress_percent: Optional[float] = None
        last_download_percent_emitted: Optional[float] = None
        last_progress_status: Optional[str] = None

        if platform is None and not has_known_extractor:
            if _is_static_media_candidate(normalized_url):
                _set_runtime_diag("staticMediaCandidateCount", 1)
                static_count = 1
                static_error = None
            else:
                static_count, static_error = _probe_static_media_candidates(normalized_url, user_agent, debug_logging)

            if static_error:
                preflight_info = None
                preflight_fail_code = "PREFLIGHT_FAILED"
                preflight_fail_message = f"STATIC_PAGE_FETCH_FAILED: {_redact_text(static_error)}"
                preflight_strategy = "static-sample"
            elif static_count <= 0:
                preflight_info = None
                preflight_fail_code = "PREFLIGHT_FAILED"
                preflight_fail_message = "STATIC_PAGE_NO_MEDIA_CANDIDATES"
                preflight_strategy = "static-sample"
            else:
                preflight_info, preflight_fail_code, preflight_fail_message, preflight_strategy = _perform_attempts(
                    phase="preflight",
                    normalized_url=normalized_url,
                    platform=platform,
                    attempts=attempts,
                    selected_cookie_file=selected_cookie_file,
                    ffmpeg_location=ffmpeg_location,
                    user_agent=user_agent,
                    download=False,
                    max_file_size_mb=max_file_size_mb,
                    merge_capable=effective_merge_capable,
                    impersonation_available=impersonation_available,
                    debug_logging=debug_logging,
                    deadline_ms=preflight_deadline_ms,
                    attempt_limit=preflight_attempt_limit,
                    per_attempt_timeout_sec=preflight_timeout_sec,
                )
        else:
            preflight_info, preflight_fail_code, preflight_fail_message, preflight_strategy = _perform_attempts(
                phase="preflight",
                normalized_url=normalized_url,
                platform=platform,
                attempts=attempts,
                selected_cookie_file=selected_cookie_file,
                ffmpeg_location=ffmpeg_location,
                user_agent=user_agent,
                download=False,
                max_file_size_mb=max_file_size_mb,
                merge_capable=effective_merge_capable,
                impersonation_available=impersonation_available,
                debug_logging=debug_logging,
                deadline_ms=preflight_deadline_ms,
                attempt_limit=preflight_attempt_limit,
                per_attempt_timeout_sec=preflight_timeout_sec,
            )
        _finish_preflight_profile(preflight_start_ms)
        preflight_warning: Optional[Dict[str, Any]] = None

        if _is_cancel_requested(cancel_flag_path):
            return _result(False, "DOWNLOAD_CANCELLED", "Cancellation requested")
        if preflight_info is None:
            soft_code = preflight_fail_code or "DOWNLOAD_FAILED"
            soft_message = preflight_fail_message or "Download preflight failed"
            if _is_retryable_preflight_failure(soft_code, soft_message):
                preflight_warning = {
                    "code": soft_code,
                    "message": _redact_text(soft_message),
                    "strategy": preflight_strategy,
                }
                _push_attempt_trace(
                    {
                        "timeMs": _now_ms(),
                        "phase": "preflight",
                        "strategy": preflight_strategy,
                        "status": "soft-failure",
                        "errorCode": soft_code,
                        "errorMessage": _redact_text(soft_message),
                    }
                )
            else:
                return _result(
                    False,
                    soft_code,
                    soft_message,
                    platform=platform,
                    normalized_url=normalized_url,
                    preflight_strategy=preflight_strategy,
                    retryable_preflight=False,
                )

        info = preflight_info or {}

        if preflight_info is not None and not effective_merge_capable and not _has_progressive_format(info):
            _debug_log(debug_logging, "download failed: no progressive format and merge unavailable")
            return _result(
                False,
                "MERGE_DEPENDENCY_MISSING",
                merge_reason
                or "This media requires stream merge but ffmpeg/ffprobe merge runtime is unavailable.",
            )

        estimated_mb, _ = _estimate_file_size_mb(info) if preflight_info is not None else (0.0, None)
        if max_file_size_mb > 0 and estimated_mb > 0 and estimated_mb > max_file_size_mb:
            return _result(
                False,
                "FILE_TOO_LARGE",
                f"File size ({estimated_mb:.1f}MB) exceeds local limit ({max_file_size_mb}MB)",
                estimated_size_mb=round(estimated_mb, 1),
            )

        if _is_cancel_requested(cancel_flag_path):
            return _result(False, "DOWNLOAD_CANCELLED", "Cancellation requested")

        info, download_fail_code, download_fail_message, download_strategy = _perform_attempts(
            phase="download",
            normalized_url=normalized_url,
            platform=platform,
            attempts=attempts,
            selected_cookie_file=selected_cookie_file,
            ffmpeg_location=ffmpeg_location,
            user_agent=user_agent,
            download=True,
            max_file_size_mb=max_file_size_mb,
            merge_capable=effective_merge_capable,
            impersonation_available=impersonation_available,
            audio_only=audio_only,
            audio_format=audio_format,
            output_dir=output_dir,
            progress_hooks=[_progress_hook],
            debug_logging=debug_logging,
        )
        if _is_cancel_requested(cancel_flag_path):
            return _result(False, "DOWNLOAD_CANCELLED", "Cancellation requested")
        if info is None:
            return _result(
                False,
                download_fail_code or "DOWNLOAD_FAILED",
                download_fail_message or "Download failed",
                platform=platform,
                normalized_url=normalized_url,
                preflight_warning=preflight_warning,
                preflight_strategy=preflight_strategy,
                strategy=download_strategy,
            )

        if _is_cancel_requested(cancel_flag_path):
            return _result(False, "DOWNLOAD_CANCELLED", "Cancellation requested")

        downloaded_file = _resolve_downloaded_file(info, output_dir)
        if not downloaded_file:
            return _result(False, "FILE_NOT_FOUND", "Download finished but output file could not be found")

        source_basename = os.path.basename(downloaded_file)
        thumbnail_path = _resolve_thumbnail_file(info) if audio_only else None
        if audio_only:
            stem, ext = os.path.splitext(source_basename)
            # The extension normally comes from the file the postprocessor produced;
            # the fallback tracks the requested format so a missing extension does not
            # silently mislabel a FLAC file as M4A.
            fallback_ext = "." + _normalize_audio_format(audio_format)
            basename = _sanitize_audio_title(stem) + (ext.lower() or fallback_ext)
        else:
            basename = _sanitize_filename(source_basename)
        final_path = os.path.join(output_dir, basename)

        if downloaded_file != final_path:
            os.replace(downloaded_file, final_path)

        timestamp_normalized = _normalize_video_timestamp(final_path, ffmpeg_location, debug_logging)
        warning_code = None

        if not timestamp_normalized:
            try:
                os.utime(final_path, None)
            except OSError:
                pass

            _, ext = os.path.splitext(final_path)
            if ext.lower() in VIDEO_TIMESTAMP_EXTENSIONS and ffmpeg_location:
                warning_code = "TIMESTAMP_POSTPROCESS_FAILED"
                _debug_log(debug_logging, f"timestamp warning for file={final_path}")

        size_mb = os.path.getsize(final_path) / (1024 * 1024)
        _write_progress(progress_file_path, 100.0, "Completed", "completed")
        progress_write_count += 1
        _set_runtime_diag("progressWritesLast", progress_write_count)
        _debug_log(
            debug_logging,
            f"download progress-writes={progress_write_count} strategy={download_strategy} youtubeChunk={_RUNTIME_DIAGNOSTICS.get('youtubeChunkProfileLast')}",
        )
        return _result(
            True,
            "DOWNLOAD_COMPLETED",
            "Download completed",
            file_path=final_path,
            filename=basename,
            size_mb=round(size_mb, 2),
            timestamp_normalized=timestamp_normalized,
            warning_code=warning_code,
            thumbnail_path=thumbnail_path,
            media_kind="audio" if audio_only else "video",
            format_mode="merged" if effective_merge_capable else "progressive",
            normalized_url=normalized_url,
            preflight_warning=preflight_warning,
            preflight_strategy=preflight_strategy,
            strategy=download_strategy,
            extractor_key=info.get("extractor_key"),
        )
    except Exception as exc:
        if "DOWNLOAD_CANCELLED" in str(exc):
            return _result(False, "DOWNLOAD_CANCELLED", "Cancellation requested")

        code, message = _classify_exception(
            exc,
            "DOWNLOAD_FAILED",
            platform=_detect_cookie_platform(url),
            context_url=url,
        )
        tool_output = _bounded_tool_output(message)
        if tool_output:
            _set_runtime_diag("toolOutputLast", tool_output)
        _debug_log(debug_logging, f"download exception code={code} message={message}")
        return _result(False, code, message)


def generate_video_thumbnail(
    input_path: str,
    ffmpeg_path: str,
    offset_sec: float,
    width: int,
    height: int,
    quality: int = 5,
    timeout_sec: float = 30.0,
) -> bytes:
    """Decode a single frame and return JPEG bytes.

    Used as the fallback path when Android's MediaMetadataRetriever can't decode the
    container (commonly: VP9/WebM on older OEMs, AV1 on pre-Q devices, some HEVC-in-MKV).
    Output is piped on stdout — no plaintext frame is ever written to disk.

    ffmpeg `-q:v` is inverted: lower number = higher quality. Quality 5 maps roughly to
    JPEG q=80. Width/height set a `scale` filter that preserves aspect ratio if either
    dimension is -1.
    """
    if not input_path or not os.path.exists(input_path):
        return b""
    if not ffmpeg_path or not os.path.exists(ffmpeg_path):
        return b""

    safe_offset = max(0.0, float(offset_sec))
    scale_filter = f"scale={int(width)}:{int(height)}:force_original_aspect_ratio=decrease"
    cmd = [
        ffmpeg_path,
        "-hide_banner",
        "-loglevel", "error",
        "-ss", f"{safe_offset:.3f}",
        "-i", input_path,
        "-frames:v", "1",
        "-vf", scale_filter,
        "-q:v", str(int(quality)),
        "-f", "image2pipe",
        "-vcodec", "mjpeg",
        "-",
    ]
    try:
        proc = subprocess.run(
            cmd,
            check=False,
            capture_output=True,
            timeout=max(1.0, float(timeout_sec)),
        )
    except (subprocess.TimeoutExpired, OSError):
        return b""
    if proc.returncode != 0:
        return b""
    return proc.stdout or b""
