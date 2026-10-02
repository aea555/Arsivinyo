# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

> **NEVER run Android builds yourself** — do not invoke `expo run:android`, `npm run android`, `gradlew assemble*`/`install*`, or any background Gradle build. The maintainer runs all builds locally on the device. Make the code changes, run non-build checks (`npm run lint`, `npm run test:python`, `tsc --noEmit`, `npm run verify:prebuild`), then hand the build command to the maintainer. Only build if the maintainer explicitly says to. (`expo prebuild` is fine — it does not start a Gradle daemon.)
>
> **adb can wedge (the real cause of the "stalling").** The adb server occasionally hangs — especially right after a build — and that hang (not Gradle/Expo) is what stalls `expo run:android`/Metro. When adb is unresponsive or a command hangs, revive it before doing anything else with the device:
> ```bash
> adb kill-server        # if that itself hangs: pkill -f adb
> adb start-server
> adb devices            # confirm the device is back
> ```
> `adb` is on `PATH` via `ANDROID_HOME=~/Android/Sdk`. Avoid leaving long-running `adb logcat` captures running; stop them when done, since they contribute to the wedging.

## Commands

```bash
npm install
npx expo prebuild --platform android      # regenerate native android/ from app.config.js + plugins
npm run android                            # build + run on Android (expo run:android)
npm run start                              # Metro bundler (rarely useful alone; this app needs custom native)
npm run lint                               # expo lint (eslint-config-expo flat)

# Local-downloader native pipeline verification
npm run verify:prebuild                    # MUST run after any change to app.plugin.js or ABI/manifest wiring
npm run verify:impersonation-wheels        # validates curl_cffi wheels + SHA256SUMS + VERSIONS.json
npm run build:impersonation-wheels         # requires CHAQUOPY_PYPI_DIR env var
npm run test:python                        # python3 -m unittest modules/local-downloader/tests/test_local_downloader.py

# Audio-preset DSP core (pure C++, no device needed — see "Audio presets" below)
npm run test:dsp                           # build + run the host-side DSP tests
npm run test:dsp:sanitize                  # same, plus an ASan/UBSan build

# Run a single Python test
python3 -m unittest modules.local-downloader.tests.test_local_downloader.<ClassName>.<test_method>
```

Host toolchain is Linux. `npm run verify:signing` is still a PowerShell script (`scripts/verify-signing.ps1`) and does not run here — port or replace it before relying on it.

Expo Go cannot run this project — the local-downloader native module is required. Always use `expo run:android` (or a custom dev client build).

Optional emulator support: `LOCAL_DOWNLOADER_ABIS=arm64-v8a,x86_64 npx expo prebuild --clean --platform android --no-install` — only valid when matching `x86_64` wheels and FFmpeg `.so`s are present.

## Architecture

This is an Android-first Expo app whose downloader pipeline runs **entirely on-device**. There is no backend. The flow is layered:

```
app/ (Expo Router screens)
  └─> src/api/*           TS wrappers, typed responses, error mapping
       └─> modules/local-downloader/src/   Expo native module bridge (TS)
            └─> ...android/.../LocalDownloaderModule.kt   Kotlin orchestration
                 ├─> DownloadForegroundService.kt          background service + sticky notification
                 ├─> DownloadActionReceiver.kt             notification action intents
                 ├─> QuickDownloadCaptureActivity.kt       share-sheet / clipboard entry
                 ├─> PrivateVaultImportActivity.kt         gated private-vault import (SAF)
                 ├─> SoundsImportActivity.kt               multi-select audio import (SAF)
                 ├─> sounds/SoundsStore.kt                 music library (MediaStore owner model)
                 └─> Chaquopy → python/local_downloader.py yt-dlp strategies + normalization
                      └─> FFmpeg/FFprobe (jniLibs .so, optional assets/ffmpeg fallback)
```

Cross-cutting things to know before editing:

- **`modules/local-downloader/app.plugin.js` is the source of truth for native wiring.** It injects Chaquopy gradle config, pip targets (`yt-dlp`, `curl-cffi==0.14.0`), ABI filters, the foreground service, the action receiver, and the three activities (quick-capture + the two SAF importers) into the generated `android/` project via tagged `// @generated begin/end ...` blocks. Never hand-edit files under `android/` — they are produced by prebuild and the plugin. After changing the plugin, run `npx expo prebuild --clean --platform android --no-install && npm run verify:prebuild`. Two gotchas: (1) `ensureApplicationEntry` **replaces** an entry's attribute set (not merge) so a removed attribute actually disappears on the next prebuild; (2) the SAF importer activities must **not** carry `android:noHistory` — a noHistory activity is finished the instant the full-screen document picker covers it, which cancels the pick before the user selects anything.

- **ABI coverage is an invariant that spans four locations.** Default ABI is `arm64-v8a`. If you add `x86_64` (or any other), you must update all of: `app.plugin.js` (`DEFAULT_REACT_NATIVE_ARCHITECTURES`), `modules/local-downloader/android/chaquopy-wheels/VERSIONS.json`, the verifier scripts under `scripts/`, and provide matching wheels + FFmpeg `.so` pairs. The verifiers will catch drift.

- **Two FFmpeg runtimes exist by design.** Primary path is `jniLibs/<abi>/libffmpeg.so` + `libffprobe.so` loaded as native libraries. Fallback is `assets/ffmpeg/<abi>/{ffmpeg,ffprobe}` extracted to private storage. The diagnostics screen (`app/diagnostics.tsx`) reports which one is active.

- **Public API surface for the downloader is `src/api/index.ts`.** Screens should not import from `modules/local-downloader/*` directly; go through `src/api/localDownloader.ts` and `src/api/download.ts`. Types live in `src/api/types.ts` and `modules/local-downloader/src/LocalDownloader.types.ts` — keep these aligned with the Kotlin module's exposed functions.

- **Routing is Expo Router with typed routes enabled** (`experiments.typedRoutes: true` in `app.config.js`). Tabs live in `app/(tabs)/`, modal/card screens at `app/*.tsx`. Root layout in `app/_layout.tsx` wires `I18nextProvider` → `ThemeProvider` → `Stack`.

- **Styling is NativeWind (Tailwind for RN).** `global.css` is the entry, configured via `metro.config.js` and `tailwind.config.js`. The path alias `@/*` maps to repo root (see `tsconfig.json`), so imports look like `@/src/components`, `@/global.css`.

- **i18n** is `i18next` + `react-i18next`, initialized in `src/i18n` and provided at root. Translation keys are used throughout screen titles and UI.

- **Private vault flow** is auth-gated inside Kotlin, and now genuinely is: `vault/VaultSession.kt`
  holds the key behind a window (15 min absolute, 3 min idle, 60 s freshness for anything
  destructive) and `vault/VaultAuthPolicy.kt` is the table of what each operation needs. Every
  vault path takes its key through `requireVaultDek(operation)`, so `PRIVATE_VAULT_LOCKED` and
  `PRIVATE_STEP_UP_REQUIRED` are real answers a bridge call can return. Long work — a playing
  video, a whole-vault export, a re-encryption run — holds a lease so the window cannot lapse
  underneath it. TS calls `unlockLocalPrivateVault()` in response; the prompts on the screen are
  UX now rather than the boundary.
  *(This line used to say the gate was in Kotlin while it was entirely in TypeScript, and named
  a function that does not exist. The backup screen never called it at all, so exporting the
  whole decrypted vault asked for nothing.)*
  The vault path is app-private storage, and its listing is encrypted — see `vault/VaultIndexCodec.kt`.

- **Faces in memes** run natively: `libfaces.so` is `../shared/faces` (the same C++ the Mac
  compiles) plus `src/main/cpp/faces_jni.cpp`, over ONNX Runtime. build.gradle takes only the
  headers and `libonnxruntime.so` out of the runtime's AAR (its Java bindings are not used)
  and copies the two pinned models into assets; `shared/faces/MODELS.json` pins the runtime
  version and the models' hashes for both apps, so change them there first. The rules for who
  a face is are Kotlin (`memes/MemeFaces.kt`, JVM-tested); that the phone's pipeline matches
  the Mac's is `FacesVectorsInstrumentedTest`, which needs the phone:
  `./gradlew :local-downloader:connectedDebugAndroidTest`.

- **The video player is libmpv** (`watch/MpvPlayerView.kt`, an Expo view, with the controls
  in `src/features/player/Player.tsx`), for add-on streams and vault videos alike; it comes
  from the `dev.jdtech.mpv:libmpv` AAR, which needs minSdk 26. That AAR's libmpv needs a
  newer `libc++_shared.so` than React Native builds with, and every native build in the app
  brings React Native's copy, which always wins the merge. So `useMpvCxxRuntime` in the
  module's build.gradle puts mpv's copy in place after each native-libs merge (the app's,
  through app.plugin.js, and the module's test APK's). If an upgrade of React Native, the NDK
  or the AAR changes either runtime, check every library in the APK against the one that
  ships, symbol by symbol, before trusting it. FFmpeg's TLS inside it is mbedTLS, given
  Android's own CA certificates as a PEM file made at first use. That the phone plays the
  shared sample (HEVC, AC3, DTS, styled ASS) is `MpvInstrumentedTest`.

- **Music library (in-app audio player)** is a separate subsystem from the vault. Audio downloads (`mediaKind: 'audio'`) go through yt-dlp's `bestaudio` + `FFmpegExtractAudio` path (Python) and are saved by `sounds/SoundsStore.kt` into the **public `Music/Arsivinyo`** folder via the MediaStore **owner model** — readable/writable with **no `READ_MEDIA_*` / `WRITE_EXTERNAL_STORAGE` permission** (those stay blocked), but this requires **API 29+** (`SoundsStore.isSupported()` gates it). **Output format is M4A/AAC, not MP3:** the bundled FFmpeg (ffmpeg-kit 6.0) has no MP3 encoder (`libmp3lame`), so `_apply_audio_postprocessing` targets `preferredcodec="m4a"` (lossless remux when the source is already AAC; 256k AAC otherwise). **Cover art is NOT embedded** — that same FFmpeg also has no image encoder, so `EmbedThumbnail` (which transcodes the YouTube `.webp` cover) fails with `Error selecting an encoder`. Instead `writethumbnail` downloads the cover file, Python returns its path (`_resolve_thumbnail_file` → `thumbnail_path`), and `SoundsStore.storeThumbFromFile` copies it verbatim into the sidecar thumbnail store (`expo-image` renders WebP/JPEG/PNG from a path directly). Track title/artist tags are written into the file via `FFmpegMetadata` (stream copy, no encoder). Imports still pull art from the file's embedded cover via `MediaMetadataRetriever`. Tracks are plaintext (no vault/encryption). `sounds/index.json` caches metadata and owns playlists; it is reconciled against MediaStore on every list. **Favorites** is a reserved system playlist (id `favorites`, `system: true`) — `ensureFavoritesLocked` lazily creates it so it always exists, `deletePlaylist`/`renamePlaylist` reject it, and `setSoundsFavorite(ids, favorite)` adds/removes membership; the UI pins it to the top and exposes a smart heart toggle (Songs tab + player). Playback uses **`react-native-track-player` 5.x (the New-Architecture/TurboModule nightly)** — the 4.1.2 stable starts audio but its commands/remote events don't reach the player under bridgeless mode, so it's effectively uncontrollable; the old `patches/react-native-track-player+4.1.2.patch` is gone. The service is registered in the custom `index.js` entry before expo-router. RNTP's Android notification opens the app with a `notification.click` deep link; `app/+native-intent.ts` rewrites that to `/sound-player` so it doesn't 404. UI: `app/sounds.tsx` (library) + `app/sound-player.tsx` (the currently-playing row gets an accent outline; tapping a row just plays — the full player opens via the mini-player or notification). Public API via `src/api/sounds.ts` → re-exported from `src/api/index.ts`. Do not auth-gate music — it is intentionally non-private. Heads-up: a JS-only reload (Fast Refresh) leaves RNTP's notification controls inert until a fresh app launch (`registerPlaybackService` only runs at startup) — a dev-only quirk, fine in release.

## Versioning

`app.config.js` is the **single source of truth** for both the app's `version` (string) and `android.versionCode` (integer). `expo prebuild` propagates both into `android/app/build.gradle`. Do not hand-edit the generated `versionCode`/`versionName` in `build.gradle`.

- **Scheme.** SemVer: `MAJOR.MINOR.PATCH`. Prereleases use a `-beta.N` suffix (e.g. `2.1.0-beta.1` → `2.1.0-beta.2` → `2.1.0`).
- **versionCode** is a monotonic integer; bump on every Play Store upload (not every dev build). The initial recalibrated value is `20100`. The scheme is documentation-only — just keep it monotonic.
- **CHANGELOG.md** at the repo root is updated together with every version bump (Keep-a-Changelog format).
- **Git tags** use the `v` prefix (`v2.1.0-beta.1`, `v2.1.0`). Stable releases also get a GitHub release with the changelog entry copy-pasted.
- **In-app surface.** The diagnostics screen displays `version`, `versionCode`, and `channel` (derived: `'beta'` if version contains `-beta.`, else `'stable'`). Read these via `BUILD_CONFIG` from `src/config.ts`.
- **package.json `version`** is kept in sync cosmetically (Expo does not consume it).

## Version-pin coupling

When bumping any of these, update all together (the verifier will not catch every drift):

- `modules/local-downloader/app.plugin.js` — Chaquopy version, Python version, `curl-cffi` pin
- `../shared/engine/local_downloader.py` — yt-dlp call sites
- `modules/local-downloader/android/chaquopy-wheels/VERSIONS.json` — pinned dependency versions + ABI matrix
- `modules/local-downloader/android/chaquopy-wheels/SHA256SUMS` — regenerate after wheel changes

## Notes from README worth keeping in mind

- `newArchEnabled: true` and `reactCompiler: true` are on (see `app.config.js`).
- A set of Android storage permissions are explicitly **blocked** in `app.config.js` (`READ_EXTERNAL_STORAGE`, `READ_MEDIA_*`, etc.) — the app uses scoped/private storage and `expo-media-library`'s explicit-save flow instead. Don't re-enable these without understanding the privacy posture.
- iOS and web are not supported runtimes for the downloader pipeline. iOS scaffolding exists but the native module is Android-only.
