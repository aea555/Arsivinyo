# PyInstaller spec for the desktop download engine.
#
# The subtlety: yt-dlp must NOT be inside the binary, or the "update yt-dlp" feature
# stops working — PyInstaller's FrozenImporter runs before sys.path, so a frozen yt_dlp
# would shadow any downloaded override.
#
# But `--exclude-module yt_dlp` is the wrong tool: it removes yt-dlp from the *analysis*,
# so the stdlib modules only yt-dlp imports (getpass and friends) never make it into the
# freeze either, and the external copy then fails to import.
#
# So: analyse normally, with yt-dlp installed, and drop only the yt_dlp package itself
# from the bundle afterwards. Its dependencies stay; the package ships as a directory.

a = Analysis(
    ['bootstrap.py'],
    pathex=[],
    hiddenimports=['getpass', 'secrets', 'sqlite3', 'xml.etree.ElementTree'],
    excludes=['tkinter'],
    noarchive=False,
)

_dropped = [n for (n, _p, _t) in a.pure if n == 'yt_dlp' or n.startswith('yt_dlp.')]
a.pure = [(n, p, t) for (n, p, t) in a.pure if not (n == 'yt_dlp' or n.startswith('yt_dlp.'))]
print(f"[spec] kept yt-dlp OUT of the binary: {len(_dropped)} modules dropped, shipped as a directory instead")

pyz = PYZ(a.pure)
exe = EXE(pyz, a.scripts, [], exclude_binaries=True, name='arsivinyo-engine',
          console=True, strip=False, upx=False)
coll = COLLECT(exe, a.binaries, a.datas, strip=False, upx=False, name='arsivinyo-engine')
