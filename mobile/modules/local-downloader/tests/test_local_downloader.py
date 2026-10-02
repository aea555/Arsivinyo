import os
import importlib
import json
import tempfile
import types
import unittest
import unittest.mock
import time

try:
    import sys
    from pathlib import Path

    python_src = Path(__file__).resolve().parents[4] / "shared" / "engine"
    sys.path.insert(0, str(python_src))
    if importlib.util.find_spec("yt_dlp") is None:
        fake_ytdlp = types.ModuleType("yt_dlp")
        fake_ytdlp.version = types.SimpleNamespace(__version__="2026.1.1")

        class FakeYoutubeDL:
            def __init__(self, opts=None):
                self.opts = opts or {}

            def __enter__(self):
                return self

            def __exit__(self, exc_type, exc, tb):
                return False

            def extract_info(self, url, download=False):
                return {}

        fake_ytdlp.YoutubeDL = FakeYoutubeDL
        sys.modules["yt_dlp"] = fake_ytdlp
    import local_downloader as ld
except Exception as exc:  # pragma: no cover
    # Do NOT swallow this. yt_dlp is already stubbed above, so the only remaining
    # reasons the module fails to import are real defects — a syntax error, or a name
    # used before it is defined at module scope. Skipping on those turned a broken
    # module into a green "OK (skipped=N)" run, which has already hidden one bug.
    raise RuntimeError(
        "local_downloader failed to import; this is a defect in the module, not a "
        f"missing test dependency: {exc!r}"
    ) from exc

try:
    import sys
    from pathlib import Path

    python_src = Path(__file__).resolve().parents[4] / "shared" / "engine"
    sys.path.insert(0, str(python_src))
    import yt_dlp_override_bootstrap as yb
except Exception as exc:  # pragma: no cover
    # Same reasoning as local_downloader above: nothing external is required to import
    # this module, so a failure here is a defect and must fail the run.
    raise RuntimeError(
        "yt_dlp_override_bootstrap failed to import; this is a defect in the module, "
        f"not a missing test dependency: {exc!r}"
    ) from exc


def _write_fake_ytdlp_package(root, version):
    package_dir = os.path.join(root, "yt_dlp")
    os.makedirs(package_dir, exist_ok=True)
    with open(os.path.join(package_dir, "__init__.py"), "w", encoding="utf-8") as f:
        f.write("")
    with open(os.path.join(package_dir, "version.py"), "w", encoding="utf-8") as f:
        f.write(f'__version__ = "{version}"\n')


class YtDlpOverrideBootstrapTests(unittest.TestCase):
    def tearDown(self):
        yb._clear_ytdlp_modules()

    def test_pending_override_promotes_to_active(self):
        with tempfile.TemporaryDirectory() as tmp:
            bundled = os.path.join(tmp, "bundled")
            override_root = os.path.join(tmp, "overrides")
            version_dir = os.path.join(override_root, "versions", "2026.3.17")
            manifest_path = os.path.join(override_root, "manifest.json")
            _write_fake_ytdlp_package(bundled, "2026.2.4")
            _write_fake_ytdlp_package(version_dir, "2026.3.17")
            os.makedirs(override_root, exist_ok=True)
            with open(manifest_path, "w", encoding="utf-8") as f:
                json.dump({"schemaVersion": 1, "pendingVersion": "2026.3.17", "installed": {}}, f)

            sys.path.insert(0, bundled)
            try:
                payload = json.loads(yb.activate(override_root, manifest_path))
            finally:
                sys.path.remove(bundled)

            self.assertEqual(payload["source"], "override")
            self.assertEqual(payload["activeVersion"], "2026.3.17")
            with open(manifest_path, "r", encoding="utf-8") as f:
                manifest = json.load(f)
            self.assertEqual(manifest["activeVersion"], "2026.3.17")
            self.assertIsNone(manifest["pendingVersion"])

    def test_pending_override_accepts_zero_padded_runtime_version(self):
        with tempfile.TemporaryDirectory() as tmp:
            bundled = os.path.join(tmp, "bundled")
            override_root = os.path.join(tmp, "overrides")
            version_dir = os.path.join(override_root, "versions", "2026.3.17")
            manifest_path = os.path.join(override_root, "manifest.json")
            _write_fake_ytdlp_package(bundled, "2026.02.04")
            _write_fake_ytdlp_package(version_dir, "2026.03.17")
            os.makedirs(override_root, exist_ok=True)
            with open(manifest_path, "w", encoding="utf-8") as f:
                json.dump({"schemaVersion": 1, "pendingVersion": "2026.3.17", "installed": {}}, f)

            sys.path.insert(0, bundled)
            try:
                payload = json.loads(yb.activate(override_root, manifest_path))
            finally:
                sys.path.remove(bundled)

            self.assertEqual(payload["source"], "override")
            self.assertEqual(payload["activeVersion"], "2026.03.17")
            with open(manifest_path, "r", encoding="utf-8") as f:
                manifest = json.load(f)
            self.assertEqual(manifest["activeVersion"], "2026.3.17")
            self.assertIsNone(manifest["pendingVersion"])
            self.assertIsNone(manifest["failedVersion"])
            self.assertIsNone(manifest["failedReason"])

    def test_failed_zero_padded_mismatch_is_retried_once(self):
        with tempfile.TemporaryDirectory() as tmp:
            bundled = os.path.join(tmp, "bundled")
            override_root = os.path.join(tmp, "overrides")
            version_dir = os.path.join(override_root, "versions", "2026.3.17")
            manifest_path = os.path.join(override_root, "manifest.json")
            _write_fake_ytdlp_package(bundled, "2026.02.04")
            _write_fake_ytdlp_package(version_dir, "2026.03.17")
            os.makedirs(override_root, exist_ok=True)
            with open(manifest_path, "w", encoding="utf-8") as f:
                json.dump(
                    {
                        "schemaVersion": 1,
                        "activeVersion": None,
                        "pendingVersion": None,
                        "failedVersion": "2026.3.17",
                        "failedReason": "OVERRIDE_VERSION_MISMATCH:2026.03.17",
                        "installed": {},
                    },
                    f,
                )

            sys.path.insert(0, bundled)
            try:
                payload = json.loads(yb.activate(override_root, manifest_path))
            finally:
                sys.path.remove(bundled)

            self.assertEqual(payload["source"], "override")
            self.assertEqual(payload["activeVersion"], "2026.03.17")
            with open(manifest_path, "r", encoding="utf-8") as f:
                manifest = json.load(f)
            self.assertEqual(manifest["activeVersion"], "2026.3.17")
            self.assertIsNone(manifest["pendingVersion"])
            self.assertIsNone(manifest["failedVersion"])
            self.assertIsNone(manifest["failedReason"])

    def test_missing_pending_override_falls_back_to_bundled(self):
        with tempfile.TemporaryDirectory() as tmp:
            bundled = os.path.join(tmp, "bundled")
            override_root = os.path.join(tmp, "overrides")
            manifest_path = os.path.join(override_root, "manifest.json")
            _write_fake_ytdlp_package(bundled, "2026.2.4")
            os.makedirs(override_root, exist_ok=True)
            with open(manifest_path, "w", encoding="utf-8") as f:
                json.dump({"schemaVersion": 1, "pendingVersion": "2026.3.17", "installed": {}}, f)

            sys.path.insert(0, bundled)
            try:
                payload = json.loads(yb.activate(override_root, manifest_path))
            finally:
                sys.path.remove(bundled)

            self.assertEqual(payload["source"], "bundled")
            self.assertEqual(payload["activeVersion"], "2026.2.4")
            with open(manifest_path, "r", encoding="utf-8") as f:
                manifest = json.load(f)
            self.assertIsNone(manifest["pendingVersion"])
            self.assertEqual(manifest["failedVersion"], "2026.3.17")


class LocalDownloaderUnitTests(unittest.TestCase):
    def test_build_format_selector_uses_limit(self):
        merged_selector = ld._build_format_selector(50, True)
        progressive_selector = ld._build_format_selector(50, False)
        self.assertIn("<50M", merged_selector)
        self.assertIn("bestvideo+bestaudio", merged_selector)
        self.assertIn("acodec!=none", progressive_selector)

    def test_build_format_selector_unlimited(self):
        merged_selector = ld._build_format_selector(0, True)
        progressive_selector = ld._build_format_selector(0, False)
        self.assertEqual(merged_selector, "bestvideo+bestaudio/best")
        self.assertEqual(progressive_selector, "best[acodec!=none][vcodec!=none]/best")

    def test_sanitize_audio_title_preserves_spaces_and_unicode(self):
        # Spaces must survive (this is the whole point — no underscore slugging).
        self.assertEqual(ld._sanitize_audio_title("This song is amazing"), "This song is amazing")
        # Unicode letters preserved (matters for the app's non-English locales).
        self.assertEqual(ld._sanitize_audio_title("Şarkı çok güzel"), "Şarkı çok güzel")
        self.assertEqual(ld._sanitize_audio_title("日本語の歌"), "日本語の歌")

    def test_sanitize_audio_title_strips_only_illegal_chars(self):
        # Filesystem-illegal characters removed; the rest (incl. apostrophes,
        # parens, ampersands) kept.
        self.assertEqual(
            ld._sanitize_audio_title('AC/DC: Back in Black? (Live) <2024>'),
            "ACDC Back in Black (Live) 2024",
        )
        # Collapsed whitespace, no leading/trailing dots or spaces.
        self.assertEqual(ld._sanitize_audio_title("  spaced   out  "), "spaced out")
        self.assertEqual(ld._sanitize_audio_title("..hidden.."), "hidden")

    def test_sanitize_audio_title_empty_falls_back(self):
        self.assertEqual(ld._sanitize_audio_title(""), "audio")
        self.assertEqual(ld._sanitize_audio_title('/\\:*?"<>|'), "audio")
        # Length is capped.
        self.assertLessEqual(len(ld._sanitize_audio_title("x" * 500)), 150)

    def test_apply_audio_postprocessing_defaults_to_flac_chain(self):
        opts = {"format": "bestvideo+bestaudio/best", "merge_output_format": "mp4"}
        ld._apply_audio_postprocessing(opts)
        self.assertEqual(opts["format"], "bestaudio/best")
        self.assertNotIn("merge_output_format", opts)
        self.assertTrue(opts["writethumbnail"])
        pp_keys = [pp["key"] for pp in opts["postprocessors"]]
        # No EmbedThumbnail: the bundled FFmpeg has no image encoder, so embedding the
        # webp cover fails. We download the cover (writethumbnail) and store it as a
        # sidecar in Kotlin instead.
        self.assertEqual(pp_keys, ["FFmpegExtractAudio", "FFmpegMetadata"])
        # Lossless by default: the source is already lossy, so re-encoding to AAC would
        # stack a second generation of loss for nothing.
        self.assertEqual(opts["postprocessors"][0]["preferredcodec"], "flac")
        # A bitrate knob is meaningless for a lossless codec.
        self.assertNotIn("preferredquality", opts["postprocessors"][0])
        # 16-bit with TPDF dither, and no sample-rate override.
        extract_args = opts["postprocessor_args"]["extractaudio"]
        self.assertIn("-sample_fmt", extract_args)
        self.assertEqual(extract_args[extract_args.index("-sample_fmt") + 1], "s16")
        self.assertIn("-dither_method", extract_args)
        self.assertEqual(extract_args[extract_args.index("-dither_method") + 1], "triangular")
        self.assertNotIn("-ar", extract_args)

    def test_apply_audio_postprocessing_m4a_chain(self):
        opts = {"format": "bestvideo+bestaudio/best"}
        ld._apply_audio_postprocessing(opts, "m4a")
        pp_keys = [pp["key"] for pp in opts["postprocessors"]]
        self.assertEqual(pp_keys, ["FFmpegExtractAudio", "FFmpegMetadata"])
        # The bundled FFmpeg has no MP3 encoder, so the lossy option is M4A/AAC.
        self.assertEqual(opts["postprocessors"][0]["preferredcodec"], "m4a")
        self.assertEqual(opts["postprocessors"][0]["preferredquality"], "256")
        # The FLAC-only sample-format args must not leak into the lossy branch.
        self.assertNotIn("postprocessor_args", opts)
        # Ask for a source that is already AAC so yt-dlp stream-copies it into M4A
        # instead of re-encoding. On the maintainer's device the bundled FFmpeg encodes
        # AAC at only ~23x realtime, so an eight-hour track cost about 21 minutes of
        # transcoding; a remux costs seconds. It also avoids re-encoding Opus to AAC,
        # which is a second generation of loss for no gain.
        self.assertEqual(opts["format"], "bestaudio[acodec^=mp4a]/bestaudio/best")

    def test_apply_audio_postprocessing_flac_takes_any_source(self):
        # The mirror of the M4A case: the FLAC output is lossless whatever arrives, so
        # constraining the source codec there would only reject the best stream.
        opts = {}
        ld._apply_audio_postprocessing(opts, "flac")
        self.assertEqual(opts["format"], "bestaudio/best")

    def test_apply_audio_postprocessing_format_switches_with_target(self):
        # A reused opts dict must not keep the previous target's source preference.
        opts = {}
        ld._apply_audio_postprocessing(opts, "m4a")
        self.assertIn("acodec^=mp4a", opts["format"])
        ld._apply_audio_postprocessing(opts, "flac")
        self.assertNotIn("acodec", opts["format"])

    def test_apply_audio_postprocessing_clears_stale_flac_args(self):
        # Switching format on a reused opts dict must not leave the FLAC args behind,
        # which would hand `-sample_fmt s16` to the AAC encoder.
        opts = {}
        ld._apply_audio_postprocessing(opts, "flac")
        self.assertIn("postprocessor_args", opts)
        ld._apply_audio_postprocessing(opts, "m4a")
        self.assertNotIn("postprocessor_args", opts)

    def test_normalize_audio_format_falls_back_to_flac(self):
        self.assertEqual(ld._normalize_audio_format("flac"), "flac")
        self.assertEqual(ld._normalize_audio_format("m4a"), "m4a")
        self.assertEqual(ld._normalize_audio_format("M4A"), "m4a")
        self.assertEqual(ld._normalize_audio_format("  flac  "), "flac")
        # Anything we cannot actually encode falls back to the lossless default rather
        # than reaching FFmpeg as an unknown codec.
        self.assertEqual(ld._normalize_audio_format("mp3"), "flac")
        self.assertEqual(ld._normalize_audio_format(""), "flac")
        self.assertEqual(ld._normalize_audio_format(None), "flac")

    def test_apply_audio_postprocessing_rejects_unsupported_format(self):
        opts = {}
        ld._apply_audio_postprocessing(opts, "mp3")
        # MP3 has no encoder in the bundled FFmpeg, so it must not reach the opts.
        self.assertEqual(opts["postprocessors"][0]["preferredcodec"], "flac")

    def test_resolve_thumbnail_file_prefers_last_existing(self):
        import os
        import tempfile

        with tempfile.TemporaryDirectory() as d:
            good = os.path.join(d, "cover.webp")
            with open(good, "wb") as fh:
                fh.write(b"\x00")
            info = {
                "thumbnails": [
                    {"filepath": os.path.join(d, "missing.jpg")},
                    {"filepath": good},
                ]
            }
            self.assertEqual(ld._resolve_thumbnail_file(info), good)
            self.assertIsNone(ld._resolve_thumbnail_file({"thumbnails": []}))
            self.assertIsNone(ld._resolve_thumbnail_file({}))

    def test_build_platform_attempts_youtube_chunk_profiles(self):
        attempts = ld._build_platform_attempts(
            "youtube",
            "https://www.youtube.com/watch?v=abc123",
            has_cookie=True,
            cookie_integrity_ok=True,
            impersonation_available=False,
        )
        labels = [attempt.get("label") for attempt in attempts]
        self.assertEqual(labels[:3], ["youtube-chunk-10m", "youtube-chunk-4m", "youtube-default"])
        self.assertEqual(
            attempts[0].get("ydl_overrides", {}).get("http_chunk_size"),
            ld.YOUTUBE_HTTP_CHUNK_SIZE_PRIMARY,
        )
        self.assertEqual(
            attempts[1].get("ydl_overrides", {}).get("http_chunk_size"),
            ld.YOUTUBE_HTTP_CHUNK_SIZE_FALLBACK,
        )
        self.assertEqual(
            attempts[0].get("ydl_overrides", {}).get("throttledratelimit"),
            ld.YOUTUBE_THROTTLED_RATE_LIMIT,
        )

    def test_should_emit_progress_update_throttles_small_changes(self):
        self.assertFalse(
            ld._should_emit_progress_update(
                current_status="downloading",
                current_percent=10.2,
                now_ms=1400,
                last_status="downloading",
                last_percent=10.0,
                last_emit_ms=1000,
            )
        )
        self.assertTrue(
            ld._should_emit_progress_update(
                current_status="downloading",
                current_percent=10.6,
                now_ms=1400,
                last_status="downloading",
                last_percent=10.0,
                last_emit_ms=1000,
            )
        )
        self.assertTrue(
            ld._should_emit_progress_update(
                current_status="downloading",
                current_percent=10.2,
                now_ms=1600,
                last_status="downloading",
                last_percent=10.0,
                last_emit_ms=1000,
            )
        )
        self.assertTrue(
            ld._should_emit_progress_update(
                current_status="processing",
                current_percent=99.0,
                now_ms=1200,
                last_status="downloading",
                last_percent=98.5,
                last_emit_ms=1100,
            )
        )

    def test_coerce_monotonic_download_percent(self):
        self.assertEqual(ld._coerce_monotonic_download_percent(42.0, None), 42.0)
        self.assertEqual(ld._coerce_monotonic_download_percent(78.0, 80.0), 80.0)
        self.assertEqual(ld._coerce_monotonic_download_percent(102.0, 80.0), 100.0)
        self.assertEqual(ld._coerce_monotonic_download_percent(-3.0, 15.0), 15.0)

    def test_ytdlp_verbose_toggle_from_env(self):
        with unittest.mock.patch.dict(os.environ, {"ARSIVINYO_YTDLP_VERBOSE_DEV": "1"}, clear=False):
            mod = importlib.reload(ld)
            self.assertTrue(mod.YTDLP_VERBOSE_DEV)
        with unittest.mock.patch.dict(os.environ, {"ARSIVINYO_YTDLP_VERBOSE_DEV": "0"}, clear=False):
            mod = importlib.reload(ld)
            self.assertFalse(mod.YTDLP_VERBOSE_DEV)
        importlib.reload(ld)

    def test_failure_classification(self):
        code, _ = ld._classify_exception(Exception("HTTP Error 403: Blocked"), "DOWNLOAD_FAILED")
        self.assertEqual(code, "SITE_BLOCKED_403")

        code, _ = ld._classify_exception(Exception("Please sign in to confirm your age"), "DOWNLOAD_FAILED")
        self.assertEqual(code, "COOKIE_STALE_OR_INVALID")

        code, _ = ld._classify_exception(Exception("ffmpeg is not installed"), "DOWNLOAD_FAILED")
        self.assertEqual(code, "MERGE_DEPENDENCY_MISSING")

        code, _ = ld._classify_exception(
            Exception("ERROR: Unsupported URL: https://example.com/watch/123"),
            "PREFLIGHT_FAILED",
            platform=None,
        )
        self.assertEqual(code, "UNSUPPORTED_PLATFORM")

        code, _ = ld._classify_exception(
            Exception("ERROR: [TikTok] 123: Video not available, status code 0"),
            "PREFLIGHT_FAILED",
            platform="tiktok",
        )
        self.assertEqual(code, "TIKTOK_API_STATUS_ZERO")

        code, _ = ld._classify_exception(
            Exception("ERROR: [generic] Unable to download webpage: HTTP Error 403: Blocked"),
            "PREFLIGHT_FAILED",
            platform="reddit",
        )
        self.assertEqual(code, "REDDIT_EXTRACTOR_ROUTE_FAILED")

        code, _ = ld._classify_exception(
            Exception("ERROR: [generic] Unable to download webpage: HTTP Error 403: Blocked"),
            "PREFLIGHT_FAILED",
            platform="reddit",
            context_url="https://www.reddit.com/r/PublicFreakout/s/YG1awl66Ls",
        )
        self.assertEqual(code, "SITE_BLOCKED_403")

        code, _ = ld._classify_exception(
            Exception("The extractor is attempting impersonation, but none of these impersonate targets are available: firefox"),
            "PREFLIGHT_FAILED",
            platform=None,
        )
        self.assertEqual(code, "IMPERSONATION_TARGET_REQUIRED_UNAVAILABLE")

    def test_retryable_preflight_classifier_accepts_transient_failures(self):
        retryable_messages = [
            "Remote end closed connection without response",
            "Connection reset by peer",
            "Read timed out while opening page",
            "IncompleteRead: 0 bytes read",
            "TransportError('connection closed')",
            "HTTP Error 429: Too Many Requests",
            "HTTP Error 503: Service Unavailable",
            "An extractor error has occurred. (caused by KeyError('title'))",
        ]
        for message in retryable_messages:
            with self.subTest(message=message):
                self.assertTrue(ld._is_retryable_preflight_failure("PREFLIGHT_FAILED", message))

    def test_retryable_preflight_classifier_rejects_hard_failures(self):
        hard_failures = [
            ("INVALID_URL", "Invalid URL"),
            ("COOKIE_STALE_OR_INVALID", "Cookie required"),
            ("COOKIE_DOMAIN_MISMATCH", "Cookie domain mismatch"),
            ("FILE_TOO_LARGE", "File is too large"),
            ("DOWNLOAD_CANCELLED", "Cancellation requested"),
            ("MERGE_DEPENDENCY_MISSING", "ffmpeg is not installed"),
            ("UNSUPPORTED_PLATFORM", "Unsupported URL"),
            ("PREFLIGHT_FAILED", "A non-retryable validation failure"),
        ]
        for code, message in hard_failures:
            with self.subTest(code=code):
                self.assertFalse(ld._is_retryable_preflight_failure(code, message))

    def test_soft_preflight_proceeds_to_download_attempts(self):
        with tempfile.TemporaryDirectory() as tmp:
            output_file = os.path.join(tmp, "clip.mp4")
            with open(output_file, "wb") as f:
                f.write(b"video")

            def fake_attempts(**kwargs):
                if kwargs["phase"] == "preflight":
                    return None, "PREFLIGHT_FAILED", "Remote end closed connection without response", "default-primary"
                return {
                    "title": "clip",
                    "extractor_key": "generic",
                    "requested_downloads": [{"filepath": output_file}],
                }, None, None, "default-progressive"

            with unittest.mock.patch.object(ld, "_probe_static_media_candidates", return_value=(1, None)), \
                unittest.mock.patch.object(ld, "_perform_attempts", side_effect=fake_attempts), \
                unittest.mock.patch.object(ld, "_normalize_video_timestamp", return_value=True), \
                unittest.mock.patch.object(ld, "_is_impersonation_runtime_available", return_value=False), \
                unittest.mock.patch.object(ld, "_resolve_merge_capability", return_value=(True, None)):
                result = json.loads(
                    ld.run_download(
                        "https://example.com/video",
                        tmp,
                        tmp,
                        merge_capable=True,
                    )
                )

            self.assertTrue(result["success"])
            self.assertEqual(result["code"], "DOWNLOAD_COMPLETED")
            self.assertEqual(result["preflight_warning"]["code"], "PREFLIGHT_FAILED")
            self.assertEqual(result["preflight_strategy"], "default-primary")
            self.assertEqual(result["strategy"], "default-progressive")

    def test_generic_preflight_uses_limited_budget(self):
        with unittest.mock.patch.object(ld, "_probe_static_media_candidates", return_value=(1, None)), \
            unittest.mock.patch.object(ld, "_perform_attempts", return_value=(None, "PREFLIGHT_FAILED", "Connection reset by peer", "default-primary")) as attempts:
            result = json.loads(ld.preflight("https://example.com/page", tempfile.gettempdir()))

        self.assertFalse(result["success"])
        self.assertTrue(result["retryable_preflight"])
        self.assertEqual(result["preflight_budget_sec"], ld.GENERIC_PREFLIGHT_BUDGET_SEC)
        self.assertEqual(result["preflight_attempt_limit"], ld.GENERIC_PREFLIGHT_ATTEMPT_LIMIT)
        self.assertEqual(attempts.call_args.kwargs["attempt_limit"], ld.GENERIC_PREFLIGHT_ATTEMPT_LIMIT)
        self.assertEqual(attempts.call_args.kwargs["per_attempt_timeout_sec"], ld.GENERIC_PREFLIGHT_EXTRACT_TIMEOUT_SEC)

    def test_known_preflight_uses_broader_budget(self):
        with unittest.mock.patch.object(ld, "_perform_attempts", return_value=(None, "PREFLIGHT_FAILED", "Connection reset by peer", "youtube-default")) as attempts:
            result = json.loads(ld.preflight("https://www.youtube.com/watch?v=abc123", tempfile.gettempdir()))

        self.assertFalse(result["success"])
        self.assertEqual(result["preflight_budget_sec"], ld.KNOWN_PREFLIGHT_BUDGET_SEC)
        self.assertIsNone(result.get("preflight_attempt_limit"))
        self.assertIsNone(attempts.call_args.kwargs["attempt_limit"])

    def test_static_page_without_media_candidates_soft_fails(self):
        with unittest.mock.patch.object(ld, "_fetch_page_text_sample", return_value=("<html><a href='/next'>next</a></html>", None)), \
            unittest.mock.patch.object(ld, "_perform_attempts") as attempts:
            result = json.loads(ld.preflight("https://example.com/page", tempfile.gettempdir()))

        self.assertFalse(result["success"])
        self.assertTrue(result["retryable_preflight"])
        self.assertEqual(result["message"], "STATIC_PAGE_NO_MEDIA_CANDIDATES")
        self.assertEqual(result["static_media_candidate_count"], 0)
        attempts.assert_not_called()

    def test_static_page_with_media_candidate_reaches_probe(self):
        with unittest.mock.patch.object(ld, "_fetch_page_text_sample", return_value=("<html><iframe src='/player/1'></iframe></html>", None)), \
            unittest.mock.patch.object(ld, "_resolve_merge_capability", return_value=(True, None)), \
            unittest.mock.patch.object(ld, "_perform_attempts", return_value=({"title": "clip", "extractor_key": "generic"}, None, None, "default-primary")) as attempts:
            result = json.loads(ld.preflight("https://example.com/page", tempfile.gettempdir()))

        self.assertTrue(result["success"])
        self.assertGreater(result["static_media_candidate_count"], 0)
        attempts.assert_called_once()

    def test_static_page_fetch_error_soft_fails(self):
        with unittest.mock.patch.object(ld, "_fetch_page_text_sample", return_value=(None, "Connection reset by peer")), \
            unittest.mock.patch.object(ld, "_perform_attempts") as attempts:
            result = json.loads(ld.preflight("https://example.com/page", tempfile.gettempdir()))

        self.assertFalse(result["success"])
        self.assertTrue(result["retryable_preflight"])
        self.assertIn("STATIC_PAGE_FETCH_FAILED", result["message"])
        attempts.assert_not_called()

    def test_known_extractor_bypasses_static_generic_preflight(self):
        with unittest.mock.patch.object(ld, "_detect_known_ytdlp_extractor", return_value="sample"), \
            unittest.mock.patch.object(ld, "_probe_static_media_candidates") as static_probe, \
            unittest.mock.patch.object(ld, "_perform_attempts", return_value=({"title": "clip", "extractor_key": "sample"}, None, None, "default-primary")) as attempts, \
            unittest.mock.patch.object(ld, "_resolve_merge_capability", return_value=(True, None)):
            result = json.loads(ld.preflight("https://media.example/watch/abc", tempfile.gettempdir()))

        self.assertTrue(result["success"])
        self.assertEqual(result["preflight_budget_sec"], ld.KNOWN_PREFLIGHT_BUDGET_SEC)
        self.assertIsNone(result.get("preflight_attempt_limit"))
        static_probe.assert_not_called()
        self.assertIsNone(attempts.call_args.kwargs["attempt_limit"])

    def test_run_download_known_extractor_bypasses_static_soft_failure(self):
        with tempfile.TemporaryDirectory() as tmp:
            output_file = os.path.join(tmp, "clip.mp4")
            with open(output_file, "wb") as f:
                f.write(b"video")

            def fake_attempts(**kwargs):
                if kwargs["phase"] == "preflight":
                    return {"title": "clip", "extractor_key": "sample"}, None, None, "default-primary"
                return {
                    "title": "clip",
                    "extractor_key": "sample",
                    "requested_downloads": [{"filepath": output_file}],
                }, None, None, "default-download"

            with unittest.mock.patch.object(ld, "_detect_known_ytdlp_extractor", return_value="sample"), \
                unittest.mock.patch.object(ld, "_probe_static_media_candidates") as static_probe, \
                unittest.mock.patch.object(ld, "_perform_attempts", side_effect=fake_attempts), \
                unittest.mock.patch.object(ld, "_normalize_video_timestamp", return_value=True), \
                unittest.mock.patch.object(ld, "_is_impersonation_runtime_available", return_value=False), \
                unittest.mock.patch.object(ld, "_resolve_merge_capability", return_value=(True, None)):
                result = json.loads(ld.run_download("https://media.example/watch/abc", tmp, tmp))

        self.assertTrue(result["success"])
        self.assertEqual(result["preflight_strategy"], "default-primary")
        self.assertIsNone(result.get("preflight_warning"))
        static_probe.assert_not_called()

    def test_generic_discovery_fallback_for_extractor_error(self):
        self.assertTrue(
            ld._should_try_generic_discovery(
                "PREFLIGHT_FAILED",
                "An extractor error has occurred. (caused by KeyError('title'))",
            )
        )
        self.assertTrue(ld._should_try_generic_discovery("DOWNLOAD_FAILED", "No media formats found"))

    def test_tool_output_is_bounded(self):
        message = "ffmpeg exited with code 1\n" + ("stderr line\n" * 1000)
        output = ld._bounded_tool_output(message)
        self.assertIsNotNone(output)
        self.assertLessEqual(len(output), ld.MAX_TOOL_OUTPUT_CHARS)
        self.assertTrue(output.startswith("ffmpeg exited with code 1"))
        self.assertIsNone(ld._bounded_tool_output("plain status update"))

    def test_extract_required_impersonation_targets(self):
        targets = ld._extract_required_targets_from_message(
            "none of these impersonate targets are available: firefox, chrome."
        )
        self.assertEqual(targets, ["firefox", "chrome"])

    def test_tiktok_url_is_canonicalized_without_tracking_query(self):
        normalized, error = ld._normalize_input_url(
            "https://www.tiktok.com/@sample/video/7612119485763374344?_r=1&_t=ZS-94NO5RAMPOH",
            "tiktok",
            ld.DEFAULT_HTTP_USER_AGENT,
            debug_logging=False,
        )
        self.assertIsNone(error)
        self.assertEqual(
            normalized,
            "https://www.tiktok.com/@sample/video/7612119485763374344",
        )

    def test_reddit_share_soft_fallback_on_403(self):
        share_url = "https://www.reddit.com/r/PublicFreakout/s/YG1awl66Ls"
        with unittest.mock.patch.object(
            ld,
            "_resolve_reddit_share_url",
            return_value=(None, "HTTP Error 403: Blocked"),
        ):
            normalized, error = ld._normalize_input_url(
                share_url,
                "reddit",
                ld.DEFAULT_HTTP_USER_AGENT,
                cookie_file=None,
                debug_logging=False,
            )
        self.assertIsNone(error)
        self.assertEqual(normalized, share_url)
        diag = ld._RUNTIME_DIAGNOSTICS.get("redditShareResolutionLast")
        self.assertEqual(diag.get("mode"), "fallback_original")

    def test_reddit_share_canonicalization_success(self):
        share_url = "https://www.reddit.com/r/PublicFreakout/s/YG1awl66Ls"
        canonical_url = "https://www.reddit.com/r/PublicFreakout/comments/abc123/title/"
        with unittest.mock.patch.object(
            ld,
            "_resolve_reddit_share_url",
            return_value=(canonical_url, None),
        ):
            normalized, error = ld._normalize_input_url(
                share_url,
                "reddit",
                ld.DEFAULT_HTTP_USER_AGENT,
                cookie_file=None,
                debug_logging=False,
            )
        self.assertIsNone(error)
        self.assertEqual(normalized, canonical_url)
        diag = ld._RUNTIME_DIAGNOSTICS.get("redditShareResolutionLast")
        self.assertEqual(diag.get("mode"), "canonicalized")

    def test_reddit_share_non_reddit_redirect_fails(self):
        share_url = "https://www.reddit.com/r/PublicFreakout/s/YG1awl66Ls"
        with unittest.mock.patch.object(
            ld,
            "_resolve_reddit_share_url",
            return_value=("https://example.com/not-reddit", None),
        ):
            normalized, error = ld._normalize_input_url(
                share_url,
                "reddit",
                ld.DEFAULT_HTTP_USER_AGENT,
                cookie_file=None,
                debug_logging=False,
            )
        self.assertIsNone(normalized)
        self.assertIsNotNone(error)
        self.assertIn("resolved host is not reddit", error)

    def test_reddit_share_resolution_receives_cookie_file(self):
        share_url = "https://www.reddit.com/r/PublicFreakout/s/YG1awl66Ls"
        with unittest.mock.patch.object(
            ld,
            "_resolve_reddit_share_url",
            return_value=("https://www.reddit.com/r/PublicFreakout/comments/abc123/title/", None),
        ) as resolver:
            normalized, error = ld._normalize_input_url(
                share_url,
                "reddit",
                ld.DEFAULT_HTTP_USER_AGENT,
                cookie_file="/tmp/runtime_cookie.txt",
                debug_logging=False,
            )
        self.assertIsNone(error)
        self.assertIn("/comments/", normalized)
        self.assertEqual(resolver.call_args.kwargs.get("cookie_file"), "/tmp/runtime_cookie.txt")

    def test_reddit_generic_route_guard_respects_share_fallback(self):
        self.assertTrue(
            ld._should_fail_reddit_generic_route(
                "reddit",
                "https://www.reddit.com/r/PublicFreakout/comments/abc123/title/",
                "generic",
            )
        )
        self.assertFalse(
            ld._should_fail_reddit_generic_route(
                "reddit",
                "https://www.reddit.com/r/PublicFreakout/s/YG1awl66Ls",
                "generic",
            )
        )

    def test_error_replacement_keeps_specific_over_generic(self):
        should_replace = ld._should_replace_last_error(
            "TIKTOK_API_STATUS_ZERO",
            "ERROR: [TikTok] Video not available, status code 0",
            "PREFLIGHT_FAILED",
            "PREFLIGHT_FAILED",
        )
        self.assertFalse(should_replace)

    def test_generic_media_candidate_discovery(self):
        html_blob = """
            <script>
                const src = "https:\\/\\/cdn.example.com\\/video\\/master.m3u8?token=abc";
            </script>
            <video src="/media/fallback.mp4"></video>
        """
        with unittest.mock.patch.object(ld, "_fetch_page_text", return_value=(html_blob, None)):
            candidates = ld._discover_generic_media_candidates(
                "https://example.com/page",
                ld.DEFAULT_HTTP_USER_AGENT,
                debug_logging=False,
            )
        self.assertGreaterEqual(len(candidates), 2)
        self.assertTrue(any(".m3u8" in item for item in candidates))
        self.assertTrue(any(".mp4" in item for item in candidates))

    def test_cancel_requested_detection(self):
        self.assertFalse(ld._is_cancel_requested(None))

        with tempfile.TemporaryDirectory() as tmp:
            flag = os.path.join(tmp, "cancel.flag")
            self.assertFalse(ld._is_cancel_requested(flag))
            with open(flag, "w", encoding="utf-8") as f:
                f.write("cancel")
            self.assertTrue(ld._is_cancel_requested(flag))

    def test_merge_capability_requires_runtime_binaries(self):
        capable, reason = ld._resolve_merge_capability("/tmp/does-not-exist", True)
        self.assertFalse(capable)
        self.assertIsNotNone(reason)
        self.assertIn("missing", reason.lower())

    def test_reddit_headers_include_referer(self):
        headers = ld._build_http_headers(
            "https://www.reddit.com/r/test/comments/abc",
            ld.DEFAULT_HTTP_USER_AGENT,
            "reddit",
            include_reddit_context=True,
        )
        self.assertEqual(headers.get("Referer"), "https://www.reddit.com/")

    def test_cookie_file_integrity_checks(self):
        with tempfile.TemporaryDirectory() as tmp:
            cookie_path = os.path.join(tmp, "cookie.txt")
            now = int(time.time())
            with open(cookie_path, "w", encoding="utf-8") as f:
                f.write("# Netscape HTTP Cookie File\n")
                f.write(f".reddit.com\tTRUE\t/\tTRUE\t{now + 3600}\tsession\tabc\n")

            check = ld._inspect_cookie_file(cookie_path, "reddit")
            self.assertTrue(check["hasCookieFile"])
            self.assertGreater(check["unexpiredCount"], 0)
            self.assertTrue(any(d.endswith("reddit.com") for d in check["domainCoverage"]))
            self.assertIsNone(ld._cookie_integrity_error(check))

            mismatch = ld._inspect_cookie_file(cookie_path, "tiktok")
            code, _ = ld._cookie_integrity_error(mismatch)
            self.assertEqual(code, "COOKIE_DOMAIN_MISMATCH")

    def test_build_cookie_header_from_file(self):
        with tempfile.TemporaryDirectory() as tmp:
            cookie_path = os.path.join(tmp, "cookie.txt")
            now = int(time.time())
            with open(cookie_path, "w", encoding="utf-8") as f:
                f.write("# Netscape HTTP Cookie File\n")
                f.write(f".reddit.com\tTRUE\t/\tTRUE\t{now + 3600}\tsession\tabc\n")
                f.write(f".example.com\tTRUE\t/\tTRUE\t{now + 3600}\tother\tdef\n")

            header, count = ld._build_cookie_header_from_file(cookie_path, "www.reddit.com")
            self.assertEqual(count, 1)
            self.assertEqual(header, "session=abc")


class ResolveStreamTests(unittest.TestCase):
    """resolve_stream: a page to something a player opens, for the watch section."""

    def _resolve(self, info=None, error=None):
        seen = {}

        class Fake:
            def __init__(self, opts=None):
                seen["opts"] = opts

            def __enter__(self):
                return self

            def __exit__(self, *exc):
                return False

            def extract_info(self, url, download=False):
                seen["download"] = download
                if error:
                    raise error
                return info

        with unittest.mock.patch.object(ld.yt_dlp, "YoutubeDL", Fake):
            return json.loads(ld.resolve_stream("https://example.com/watch")), seen

    def test_a_chosen_format_gives_its_url_and_headers_without_downloading(self):
        result, seen = self._resolve({"title": "T", "duration": 61.5, "url": "https://cdn/x.mp4",
                                      "protocol": "https", "http_headers": {"Referer": "https://example.com"}})
        self.assertTrue(result["success"])
        self.assertEqual(result["url"], "https://cdn/x.mp4")
        self.assertEqual(result["headers"], {"Referer": "https://example.com"})
        self.assertEqual(result["durationMs"], 61500)
        self.assertFalse(seen["download"])
        self.assertTrue(seen["opts"]["skip_download"])
        self.assertEqual(seen["opts"]["format"], ld.STREAM_FORMAT)

    def test_a_playlist_gives_its_first_entry(self):
        result, _ = self._resolve({"_type": "playlist", "entries": [None, {"url": "https://cdn/1.mp4", "title": "one"}]})
        self.assertEqual(result["url"], "https://cdn/1.mp4")
        self.assertEqual(result["title"], "one")

    # YouTube's shape: video-only and audio-only files, and HLS formats sharing one master.
    YOUTUBE = [
        {"url": "https://v/137", "protocol": "https", "vcodec": "avc1", "acodec": "none", "height": 1080},
        {"url": "https://a/140", "protocol": "https", "vcodec": "none", "acodec": "mp4a"},
        {"url": "https://h/270.m3u8", "protocol": "m3u8_native", "vcodec": "avc1", "acodec": "none",
         "manifest_url": "https://h/master.m3u8"},
    ]

    def test_separate_video_and_audio_files_are_both_returned_rather_than_the_hls_master(self):
        result, _ = self._resolve({"formats": self.YOUTUBE, "requested_formats": [self.YOUTUBE[0], self.YOUTUBE[1]]})
        self.assertEqual(result["url"], "https://v/137")
        self.assertEqual(result["audioUrl"], "https://a/140")

    def test_a_live_stream_gets_the_hls_master(self):
        result, _ = self._resolve({"is_live": True, "formats": self.YOUTUBE,
                                   "requested_formats": [self.YOUTUBE[0], self.YOUTUBE[1]]})
        self.assertEqual(result["url"], "https://h/master.m3u8")
        self.assertIsNone(result["audioUrl"])

    def test_only_hls_gives_the_master(self):
        result, _ = self._resolve({"formats": [self.YOUTUBE[2]]})
        self.assertEqual(result["url"], "https://h/master.m3u8")

    def test_without_a_chosen_pair_the_best_file_with_both_wins(self):
        result, _ = self._resolve({"formats": [
            {"url": "https://f/360", "protocol": "https", "vcodec": "avc1", "acodec": "mp4a", "height": 360},
            {"url": "https://f/720", "protocol": "https", "vcodec": "avc1", "acodec": "mp4a", "height": 720},
            {"url": "https://f/1080v", "protocol": "https", "vcodec": "avc1", "acodec": "none", "height": 1080},
        ]})
        self.assertEqual(result["url"], "https://f/720")
        self.assertIsNone(result["audioUrl"])

    def test_nothing_to_play_and_errors_are_codes(self):
        result, _ = self._resolve({"title": "x"})
        self.assertEqual(result["code"], "RESOLVE_NO_MEDIA")
        result, _ = self._resolve(error=RuntimeError("blocked"))
        self.assertFalse(result["success"])
        self.assertEqual(result["code"], "RESOLVE_FAILED")


class RuntimeDiagnosticsIsolationTests(unittest.TestCase):
    """Two downloads must not see each other's diagnostics.

    ``_result`` folds several of these values into the payload every entry point returns,
    and Kotlin records that payload against the task it just ran. Shared state here does
    not merely produce a confusing diagnostics screen — it attributes one download's url,
    attempt trace and tool output to a different task.
    """

    def test_each_thread_keeps_its_own_download_diagnostics(self):
        import threading

        started = threading.Barrier(2)
        interleaved = threading.Barrier(2)
        seen = {}

        def run(name):
            ld._begin_call_diagnostics()
            ld._set_runtime_diag("normalizedUrlLast", f"https://example.com/{name}")
            ld._push_attempt_trace({"who": name})
            started.wait(timeout=5)
            # Both threads have now written. If the state were shared, whichever wrote
            # last would have overwritten the other.
            interleaved.wait(timeout=5)
            seen[name] = (
                ld._RUNTIME_DIAGNOSTICS.get("normalizedUrlLast"),
                list(ld._RUNTIME_DIAGNOSTICS.get("attemptTrace") or []),
            )

        threads = [threading.Thread(target=run, args=(n,)) for n in ("a", "b")]
        for t in threads:
            t.start()
        for t in threads:
            t.join(timeout=10)

        self.assertEqual(seen["a"][0], "https://example.com/a")
        self.assertEqual(seen["b"][0], "https://example.com/b")
        self.assertEqual(seen["a"][1], [{"who": "a"}])
        self.assertEqual(seen["b"][1], [{"who": "b"}])

    def test_starting_a_download_does_not_reset_another_in_flight(self):
        import threading

        ld._begin_call_diagnostics()
        ld._push_attempt_trace({"who": "first"})

        def other():
            ld._begin_call_diagnostics()
            ld._push_attempt_trace({"who": "second"})

        t = threading.Thread(target=other)
        t.start()
        t.join(timeout=10)

        self.assertEqual(ld._RUNTIME_DIAGNOSTICS.get("attemptTrace"), [{"who": "first"}])

    def test_process_wide_values_stay_shared(self):
        import threading

        ld._set_runtime_diag("impersonationBackend", "curl_cffi")
        seen = []

        def reader():
            ld._begin_call_diagnostics()
            seen.append(ld._RUNTIME_DIAGNOSTICS.get("impersonationBackend"))

        t = threading.Thread(target=reader)
        t.start()
        t.join(timeout=10)

        self.assertEqual(seen, ["curl_cffi"])

    def test_a_thread_that_ran_no_download_reads_the_most_recent_one(self):
        # This is what the diagnostics screen does: it asks from its own thread and has
        # always been shown the last download rather than nothing.
        import threading

        def downloader():
            ld._begin_call_diagnostics()
            ld._set_runtime_diag("normalizedUrlLast", "https://example.com/most-recent")

        t = threading.Thread(target=downloader)
        t.start()
        t.join(timeout=10)

        observer = {}

        def screen():
            observer["url"] = ld._RUNTIME_DIAGNOSTICS.get("normalizedUrlLast")

        t2 = threading.Thread(target=screen)
        t2.start()
        t2.join(timeout=10)

        self.assertEqual(observer["url"], "https://example.com/most-recent")


if __name__ == "__main__":
    unittest.main()
