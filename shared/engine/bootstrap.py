"""Entry point for the frozen desktop engine.

The whole reason this file exists is the "update yt-dlp" feature.

PyInstaller installs a `FrozenImporter` in `sys.meta_path`, and meta-path finders run
*before* `sys.path` is consulted. So if yt-dlp were frozen into the binary, every
`import yt_dlp` would resolve to the frozen copy and `yt_dlp_override_bootstrap.activate()`
— which works by prepending a directory to `sys.path` — would silently have no effect.

So yt-dlp is deliberately excluded from the freeze and shipped as a plain directory beside
the executable. This runs first, puts that directory on `sys.path`, applies any downloaded
override, and only then lets the engine be imported.

Layout beside the executable:

    arsivinyo-engine(.exe)      this, frozen
    yt-dlp/                     the bundled yt-dlp, importable, replaceable
    yt-dlp-overrides/           downloaded versions + manifest.json, as on Android
    ffmpeg(.exe) ffprobe(.exe)
"""

from __future__ import annotations

import json
import os
import sys


def bundle_root() -> str:
    """Where yt-dlp and the overrides live.

    A frozen build has them beside the executable. A source run has them wherever the
    build put them, which is not next to this file — so the app says where, and only the
    last resort is this file's own directory.
    """
    told = os.environ.get("ARSIVINYO_ENGINE_ROOT")
    if told and os.path.isdir(told):
        return os.path.abspath(told)
    if getattr(sys, "frozen", False):
        return os.path.dirname(os.path.abspath(sys.executable))
    return os.path.dirname(os.path.abspath(__file__))


def setup_yt_dlp_path(root: str) -> dict:
    """Put the bundled yt-dlp on sys.path, then apply any downloaded override.

    Returns what happened, so the host can report it and a failure is visible rather
    than a silent fall back to an older extractor.
    """
    bundled = os.path.join(root, "yt-dlp")
    if os.path.isdir(bundled):
        # Appended, not prepended: an override must be able to win, and
        # yt_dlp_override_bootstrap inserts itself at position 0.
        if bundled not in sys.path:
            sys.path.append(bundled)

    override_root = os.path.join(root, "yt-dlp-overrides")
    manifest = os.path.join(override_root, "manifest.json")
    if not os.path.isdir(override_root):
        return {"source": "bundled", "overrideRoot": None}

    try:
        sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
        import yt_dlp_override_bootstrap
        return json.loads(yt_dlp_override_bootstrap.activate(override_root, manifest))
    except Exception as exc:
        return {"source": "bundled", "activateError": f"{type(exc).__name__}: {exc}"}


def main() -> int:
    root = bundle_root()
    status = setup_yt_dlp_path(root)

    import host
    host.emit({"type": "bootstrap", "root": root, "ytDlp": status})
    return host.main()


if __name__ == "__main__":
    sys.exit(main())
