# CLAUDE.md

Guidance for the whole repository. Each app keeps its own `CLAUDE.md` with the detail
that applies to it — read `mobile/CLAUDE.md` before touching anything under `mobile/`.

## Layout

```
mobile/     Android app (Expo + a Kotlin native module).
mac/        macOS app (SwiftUI over the shared C++ core). Needs Xcode; see mac/README.md.
shared/     Anything the apps must agree on: the device-pairing protocol, the security
            core, the audio DSP, the yt-dlp download engine, the icon (brand/), and the
            memes contract (memes/CONTRACT.md), which both apps build to, and the faces
            pipeline (faces/): C++ over pinned ONNX models, held to faces/VECTORS.json.
            The watch contract (watch/CONTRACT.md) covers Stremio add-ons, the player and
            torrents; the torrent engine (torrent/) is libtorrent behind one C API, built
            from pinned source for both apps by torrent/build.sh.
```

`mac/` does not reimplement the security core. It compiles the same C++ through a symlink,
so `shared/crypto/VECTORS.json` binds all of it and `swift run CoreChecks` proves the Mac
reproduces what the phone recorded.

`mobile/` is a self-contained Expo project: its `package.json`, `node_modules`,
`.gitignore` and scripts all live there, so npm commands run from `mobile/`, not from the
repository root.

The subfolder is `mobile/` and not `android/` because Expo generates an `android/`
directory inside the app itself (`mobile/android/`), and the two names would collide.

## The Qt desktop app is frozen

There was a Qt/QML desktop app under `desktop/`. It is no longer on this branch: the
branch `desktop-qt` holds it, checked out at `../arsivinyo-desktop-qt`.

It worked — pairing, an encrypted vault, playback without plaintext on disk — but it never
grew past a download button and some settings, and it never felt like a native app. The
maintainer moved to a Mac as a daily driver, so the effort goes there instead. Read it for
reference if a question about the vault or pairing has already been answered there; do not
extend it.

Nothing in `shared/` or `mobile/` depends on it. The dependency only ever ran one way.

## The rule that matters most

> **NEVER run Android builds.** Do not invoke `expo run:android`, `npm run android`,
> `gradlew assemble*`/`install*`, or any background Gradle build. The maintainer runs all
> builds locally on the device. Make the changes, run the non-build checks, then hand the
> build command over. Only build if the maintainer explicitly says to. (`expo prebuild` is
> fine — it starts no Gradle daemon.)

`mobile/CLAUDE.md` explains why, along with the adb recovery steps for when the adb server
wedges.

## Versioning

`mobile/app.config.js` is the single source of truth for the Android app's `version` and
`versionCode`. A desktop app, when it exists, versions independently; only a change to the
pairing protocol in `shared/` needs the two to move together.

The app's identity on the device is `com.arsivinyo.local`. **Never change it.** The vault,
the music index, cookie profiles and settings all live under that package name, so a change
means the next install is a different app and the user's data is orphaned.
