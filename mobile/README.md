# Arsivinyo — Android app

Android-first Expo app that downloads media **entirely on-device** — no backend, no remote API — using a local native module (Kotlin + Python + yt-dlp), with background queueing and an encrypted private vault.

The vault is the centerpiece: downloads can be routed into an authenticated, AES-encrypted on-device store, played back without ever writing plaintext to disk, organized with tags and folders, and re-encrypted in place as the cipher format evolves — all locally.

This repo is designed for engineers who need to build, modify, and ship their own version from scratch.

Licensed under **GPL-3.0-or-later** (see [License](#license)).

## Table of Contents

1. [What This Project Is](#what-this-project-is)
2. [Feature Overview](#feature-overview)
3. [Architecture](#architecture)
4. [Private Vault Internals](#private-vault-internals)
5. [Tech Stack and Version Pins](#tech-stack-and-version-pins)
6. [Versioning](#versioning)
7. [Prerequisites](#prerequisites)
8. [Quick Start (Existing Contributors)](#quick-start-existing-contributors)
9. [Build From Scratch (New Machine)](#build-from-scratch-new-machine)
10. [Release Signing](#release-signing)
11. [Required Binary/Wheel Artifacts](#required-binarywheel-artifacts)
12. [Verification Workflow](#verification-workflow)
13. [Common Customization Paths](#common-customization-paths)
14. [Troubleshooting](#troubleshooting)
15. [Security, Privacy, and Legal Notes](#security-privacy-and-legal-notes)
16. [License](#license)

## What This Project Is

`Arsivinyo` is a React Native (Expo) mobile app where media downloading happens locally on the Android device instead of a remote API backend.

Primary goals:
- Keep UI/UX in React Native.
- Run extraction/download pipeline natively on Android.
- Support protected-platform flows (cookies, impersonation runtime, diagnostics).
- Support long-running background downloads with foreground service notifications.

Current runtime focus:
- Android local downloader path is the main supported runtime.
- iOS/web are not the primary target for downloader behavior in this repository.

## Feature Overview

**Downloader**
- On-device media extraction/download through `yt-dlp` (Python runtime via Chaquopy) — no server, nothing leaves the device except the requests yt-dlp itself makes.
- Background download foreground-service flow with sticky notification controls.
- Queueing for quick background downloads (share-sheet / clipboard capture).
- Cookie profile management (platform-bound and custom-domain) + a `curl-cffi` impersonation runtime for stricter extractors.
- Self-updating `yt-dlp` (on-device override of the bundled wheel, with rollback safety).

**Private vault** 
- Downloads can be routed straight into an **encrypted on-device vault**, gated behind biometric / device-credential auth per action.
- **Authenticated chunked encryption (cipher v4):** Google Tink `AesGcmHkdfStreaming` (1 MB segments). Per-segment GCM tags give both confidentiality *and* tamper-detection, and the format is seekable for random-access playback.
- **Zero-plaintext playback:** v4 items play through an in-process loopback HTTP server that decrypts on the fly as the player requests byte ranges — the decrypted video never touches disk. A per-session random token gates the stream.
- **Encrypted thumbnails** generated at import (MediaMetadataRetriever, ffmpeg fallback), stored encrypted, served from the same loopback server.
- **Organization:** user-defined tags (many per video, colored, filterable), flat folders (move in/out), sort (alphabetical / date / size / duration), title search, and multi-select with confirmation-gated batch delete / copy-to-gallery / tag / move.
- **Opt-in re-encryption migration** (legacy v2/v3 → v4) with pause/resume, disk-space and battery pre-flight.
- **Rename**, copy-to-gallery, and a diagnostics surface reporting cipher counts, loopback server state, and migration status.

**Music library (in-app audio player)**
- **Download as audio.** A home-screen toggle switches downloads to best-audio, saved as **M4A/AAC** (`bestaudio/best` + yt-dlp's `FFmpegExtractAudio`), with cover-art thumbnail + metadata tags and a **human-readable filename** (spaces/Unicode preserved — `This song is amazing.m4a`, not `This_song_is_amazing.m4a`). M4A rather than MP3 because the bundled FFmpeg ships without an MP3 encoder; when the source is already AAC it is remuxed losslessly, otherwise re-encoded to 256k AAC. (Cover art is stored as a sidecar thumbnail rather than embedded, since that FFmpeg also has no image encoder.)
- **Public, portable storage.** Audio files are saved to `Music/Arsivinyo` via the MediaStore **owner model** — no `READ_MEDIA_*` / `WRITE_EXTERNAL_STORAGE` permission (the privacy posture is preserved), files survive uninstall and are visible to other apps. Requires Android 10+ (scoped storage); degrades gracefully below.
- **Built-in player** (`react-native-track-player`, 5.x New-Architecture build): background playback, lock-screen / notification controls, headset buttons, audio focus. Symmetric transport row (previous · −10s · play/pause · +10s · next), a **drag-or-tap seek bar** (Animated-value driven for smooth scrubbing), and repeat (off/all/one). The play button restarts a finished track; **previous** restarts the current track on a single press and goes to the previous track on a double press (in-app and from the notification). Large artwork + title; a persistent mini-player bar.
- **Favorites** — a special, non-deletable playlist pinned to the top of the Playlists tab. Bulk favorite/unfavorite from the Songs tab or any playlist via a smart heart toggle, plus a heart on the player screen for the current track. Fully local.
- **Songs / Playlists** segmented layout: "Songs" is the full searchable/sortable library (the currently-playing track is shown with an accent outline; tapping a row just plays it — the full player opens from the mini-player or notification); "Playlists" is a vertical list you tap to open a playlist's tracks. **Playlists** (many-to-many): create/rename/delete, add by per-row button or multi-select batch, remove from playlist, and **bulk import** of existing audio via a multi-select SAF picker. Destructive/important actions confirm via modal.
- Thumbnails: downloaded tracks store the source cover as a sidecar image; imported tracks extract embedded cover art (`MediaMetadataRetriever`). Metadata + playlists tracked in `sounds/index.json` and reconciled against MediaStore on load.

**Cross-cutting**
- Diagnostics screen for runtime health (app version/channel, yt-dlp, FFmpeg, impersonation, cookies, vault).
- 10-language i18n scaffold (`i18next`); English and Turkish are fully translated and newer vault strings fall back to English in the other locales. NativeWind styling, typed Expo Router.

## Architecture

High-level data flow:

```text
React Native UI (Expo Router screens)
  -> src/api/* wrappers
  -> Expo local module bridge (modules/local-downloader/src/*)
  -> Kotlin module (task orchestration, service, notifications, storage)
  -> Python runtime via Chaquopy (yt-dlp strategies + normalization)
  -> FFmpeg/FFprobe binaries/libraries for merge/remux/probing
```

Important integration points:
- Config plugin: `modules/local-downloader/app.plugin.js`
  - Injects Chaquopy Gradle config, pip installs, ABI filters, manifest entries.
- Python downloader core:
  - `../shared/engine/local_downloader.py`
- Kotlin native module and background service/controller:
  - `modules/local-downloader/android/src/main/java/expo/modules/localdownloader/LocalDownloaderModule.kt`

The vault is its own subsystem inside the Kotlin module, under `.../localdownloader/vault/`:

```text
LocalDownloaderModule.kt   orchestration: index.json, tags/folders, auth gating, module functions
  └─ vault/
       VaultCipherV4.kt          Tink AesGcmHkdfStreaming wrapper + Keystore-wrapped DEK
       VaultLoopbackServer.kt    NanoHTTPD server (127.0.0.1) streaming decrypt for playback + thumbs
       VaultPlaybackSession.kt   per-session token + provider interface
       ThumbnailGenerator.kt     MMR primary, ffmpeg-via-Chaquopy fallback
       VaultMigrator.kt          v3→v4 re-encryption walker (pause/resume, pre-flight)
```

Crypto key hierarchy: an Android Keystore-backed AES-GCM **master key** wraps a vault-wide **DEK** (persisted at `private_vault/keys/dek.v4.bin`); Tink HKDF-derives per-segment keys from the DEK. The master key never leaves the Keystore, so a copied vault file is useless on another device.

## Private Vault Internals

This is the part of the app that's genuinely non-trivial, so it gets its own section.

### Cipher versions

The on-disk format is versioned (`cipherVersion` per entry in `private_vault/index.json`). Older versions stay readable so upgrades never strand a user's data:

| Version | Content cipher | Integrity | Status |
|---|---|---|---|
| v1 | AES-GCM (one-shot) | GCM tag | Legacy, blocked for playback (delete + re-import) |
| v2 | AES-GCM | GCM tag | Legacy, readable |
| v3 | AES-CTR + HMAC | HMAC-SHA256 (tail) | Legacy, readable — **no per-chunk integrity** |
| **v4** | **Tink AES-GCM-HKDF streaming, 1 MB segments** | **per-segment GCM tags** | **Current.** Integrity-authenticated + seekable |

New imports use v4. The opt-in "Re-encrypt vault" action (`VaultMigrator`) upgrades older items, streaming v3 → v4 with the v3 HMAC verified *before* the v4 file is atomically renamed into place (so a failed/tampered source never produces a trusted v4 file).

### Why a loopback HTTP server for playback

`expo-video` (ExoPlayer underneath) needs a *URL* to stream from. We don't want to decrypt a whole vault file to a plaintext temp file just to play it (that would defeat the encryption while the file plays). So `VaultLoopbackServer` runs a NanoHTTPD instance bound to `127.0.0.1` on an ephemeral port and hands the player a URL like `http://127.0.0.1:<port>/v/<token>/<id>`.

- The server answers HTTP `Range` requests by seeking into the v4 file (Tink's `SeekableByteChannel`) and decrypting just the requested bytes — plaintext exists only as in-flight bytes, never on disk.
- A **32-byte per-session token** in the URL path is the access control: another app on the device can connect to `127.0.0.1` but doesn't know the token, so it gets a 404. (TLS would add nothing here — loopback has no network "middle" to protect against.)
- The server lazy-starts on first playback, stops on app background or 30 s idle, and invalidates sessions with a short 410-Gone grace so in-flight ExoPlayer requests fail cleanly instead of hanging.
- Because this is cleartext HTTP, release builds need `usesCleartextTraffic: true` (set via `expo-build-properties`) — Android 9+ blocks cleartext by default in non-debuggable builds, even to localhost.

### AAD binding

Every encrypted blob is bound to its identity via Tink AAD: a video uses the entry id as AAD, a thumbnail uses `thumb:<id>`. Swapping one encrypted file for another on disk fails the GCM check at decrypt time.

### Auth model

Per-action biometric / device-credential prompts (purposes: `view`, `delete`, `export`, `unprivate`, `rename`, `migrate`, `tag`, `folder`). The vault list view itself unlocks once per screen session (so navigating to the player and back doesn't re-prompt), while destructive/mutating actions each re-authenticate.

### Tags + folders

Stored in `index.json` (`tagDefinitions[]`, `folders[]`; per-entry `tags: string[]` and `folderId`). Tags are inclusive labels (many per video, OR-filter); folders are exclusive locations (flat, one per video). The vault list composes a single memoized filter chain: `folder scope → active tags → search query → sort`.

## Tech Stack and Version Pins

- Expo SDK: `~54.0.33`
- React Native: `0.81.5`
- Expo Router: `~6.0.23`
- Video playback: `expo-video ~3.0.16`
- Audio playback: `react-native-track-player` `5.0.0-alpha…nightly` (the New-Architecture/TurboModule build; 4.1.2 stable is incompatible with bridgeless mode)
- Vault crypto: Google Tink `tink-android 1.13.0` (AES-GCM-HKDF streaming AEAD)
- Vault playback server: `org.nanohttpd:nanohttpd 2.3.1`
- Chaquopy Gradle plugin: `15.0.1`
- Python runtime target: `3.11`
- `yt-dlp`: latest stable from PyPI at Android build time
- `curl-cffi` pin (impersonation runtime): `0.14.0`
- Gradle wrapper: `8.14.3`
- Android ABI default for local-downloader pipeline: `arm64-v8a` (`x86_64` is opt-in when matching wheels are available)

Version pin sources that must stay aligned:
- `modules/local-downloader/app.plugin.js`
- `modules/local-downloader/android/chaquopy-wheels/VERSIONS.json`
- `android/app/proguard-rules.pro` + `expo-build-properties.extraProguardRules` (Tink/NanoHTTPD keep rules, if you enable R8)

## Versioning

`app.config.js` is the single source of truth for both `version` (SemVer, `-beta.N` for prereleases) and `android.versionCode` (monotonic integer). `expo prebuild` propagates both into `android/app/build.gradle` — never hand-edit the generated values. Every version bump updates `CHANGELOG.md` (Keep-a-Changelog). The diagnostics screen surfaces `version` / `versionCode` / `channel`. See `CLAUDE.md` for the full convention.

## Prerequisites

Required:
- Node.js + npm
- Android Studio + Android SDK tooling
- JDK 17+
- Python 3.11 (for local Python tests and wheel build helpers)
- Bash shell utilities (`unzip`, `sha256sum`)

Recommended device/emulator coverage:
- Physical device: `arm64-v8a`
- Emulator: `x86_64`

Notes:
- Expo Go is not enough because this project requires custom native module code.
- Native Android project (`android/`) is generated/maintained through Expo prebuild and plugin injection.

## Quick Start (Existing Contributors)

```bash
npm install
npx expo prebuild --platform android
npx expo run:android
```

If you changed local-downloader plugin/build wiring, run:

```bash
npm run verify:prebuild
```

## Build From Scratch (New Machine)

This is the reproducible path for a clean environment.

1. Install JS dependencies

```bash
npm install
```

2. Prepare required downloader artifacts (see full matrix below):
- FFmpeg/FFprobe native libs in `jniLibs`
- Optional fallback tool binaries in `assets/ffmpeg`
- Chaquopy wheels for impersonation runtime

3. Build/collect wheels (if you maintain your own Chaquopy recipe environment)

```bash
export CHAQUOPY_PYPI_DIR="/absolute/path/to/chaquopy/server/pypi"
npm run build:impersonation-wheels
```

4. Verify wheels

```bash
npm run verify:impersonation-wheels
```

5. Verify prebuild + injected Gradle/manifest + merged native libs

```bash
npm run verify:prebuild
```

6. Run Android app

```bash
npm run android
```

Minimum viable vs recommended:
- Minimum viable: app can compile without impersonation wheels.
- Recommended: keep arm64 wheel coverage complete so impersonation stays enabled on production devices.
- Optional expansion: add x86_64 wheel coverage, then set `reactNativeArchitectures=arm64-v8a,x86_64` for emulator-focused builds.
  - For prebuild-driven setup, use: `LOCAL_DOWNLOADER_ABIS=arm64-v8a,x86_64 npx expo prebuild --clean --platform android --no-install`

## Release Signing

By default the generated project signs release builds with the **debug keystore** — the public, shared key (`androiddebugkey` / password `android`) baked into every Android SDK. That's fine for running on your own device, but it gives zero authenticity to APKs you hand to other people: anyone could forge a "signed update". If you distribute the app (forums, direct APK, GitHub Releases), sign releases with your own private keystore.

The signing wiring is handled by `plugins/withReleaseSigning.js` (applied on prebuild, so it survives `prebuild --clean`). It reads credentials from Gradle properties and **falls back to debug signing if they're absent** — so contributors without your keystore can still build.

**1. Generate your keystore** (once; keep it forever — losing it means you can never ship an in-place update again):

```bash
keytool -genkeypair -v \
  -keystore arsivinyo-release.keystore \
  -alias arsivinyo \
  -keyalg RSA -keysize 4096 -validity 10000
```

Store the `.keystore` file **outside the repo** (the whole `android/` folder is regenerated and `*.keystore` is gitignored regardless). A stable spot like `~/keystores/` is ideal.

**2. Point Gradle at it via the GLOBAL `~/.gradle/gradle.properties`** (never in the repo):

```properties
AV_UPLOAD_STORE_FILE=/absolute/path/to/arsivinyo-release.keystore
AV_UPLOAD_STORE_PASSWORD=your-store-password
AV_UPLOAD_KEY_ALIAS=arsivinyo
AV_UPLOAD_KEY_PASSWORD=your-key-password
```

**3. Build a signed release:**

```bash
npx expo prebuild --platform android
cd android && ./gradlew :app:assembleRelease   # gradlew.bat on Windows
# signed APK: android/app/build/outputs/apk/release/app-release.apk
```

**4. Publish the certificate fingerprint** so people can verify updates come from you.

The repo ships a helper that does all of this in one shot — prints the APK file's SHA-256, the signing certificate's SHA-256, and (reading your keystore from `~/.gradle/gradle.properties`) confirms the APK was actually signed with your release key:

```powershell
npm run verify:signing
# or, for a non-default APK path:
powershell -ExecutionPolicy Bypass -File scripts/verify-signing.ps1 -ApkPath path\to\app.apk
```

It prefers `apksigner` (handles APK Signature Scheme v2/v3) and falls back to `keytool`. The raw commands, if you want them manually:

```bash
keytool -list -v -keystore arsivinyo-release.keystore -alias arsivinyo   # keystore cert SHA-256
apksigner verify --print-certs app-release.apk                            # APK signing cert SHA-256
sha256sum app-release.apk                                                 # APK file hash
```

Put the **signing-cert SHA-256** in your README / release notes / showcase post (it's your app's stable identity across versions), and publish the **APK file SHA-256** alongside each download (per-file integrity).

A consistent signing fingerprint across versions is what lets users trust that `v2.3` came from the same author as `v2.2`; the per-file SHA-256 lets them confirm the download wasn't tampered with in transit. Prefer attaching APKs to **GitHub Releases** tied to the `v…` tag so the binary is traceable to a commit.

### Official release fingerprint

Official builds of this app are signed with the following certificate. Verify any APK you download against it (`npm run verify:signing -- -ApkPath <file>` or `apksigner verify --print-certs <file>`):

```text
Signing cert SHA-256: 15a13fcbc5830cd47b381675e325a6f124f104a3c818ce0d9e3fb551835c1089
```

If an APK's signing-cert SHA-256 doesn't match this value, it was **not** built by this project's maintainer — do not trust it as an update.

## Required Binary/Wheel Artifacts

The downloader stack depends on external binary artifacts not fetched automatically during app build.

| Artifact | Required | ABI(s) | Expected path(s) | Notes |
|---|---|---|---|---|
| `libffmpeg.so` | Yes | `arm64-v8a`, `x86_64` | `modules/local-downloader/android/src/main/jniLibs/<abi>/libffmpeg.so` | Used as primary native runtime FFmpeg binary. |
| `libffprobe.so` | Yes | `arm64-v8a`, `x86_64` | `modules/local-downloader/android/src/main/jniLibs/<abi>/libffprobe.so` | Required for probing/merge capability checks. |
| `ffmpeg` fallback tool | Optional | `arm64-v8a`, `x86_64` | `modules/local-downloader/android/src/main/assets/ffmpeg/<abi>/ffmpeg` | Used only if native-lib runtime path is unavailable. |
| `ffprobe` fallback tool | Optional | `arm64-v8a`, `x86_64` | `modules/local-downloader/android/src/main/assets/ffmpeg/<abi>/ffprobe` | Fallback pair with `ffmpeg`. |
| `curl-cffi` Android wheel | Recommended | `arm64-v8a` (default), `x86_64` (optional) | `modules/local-downloader/android/chaquopy-wheels/curl_cffi-*.whl` | Enables impersonation runtime used by stricter extractors. |
| `SHA256SUMS` | Required (if wheels present) | N/A | `modules/local-downloader/android/chaquopy-wheels/SHA256SUMS` | Must include valid checksums for wheel files. |
| `VERSIONS.json` | Required | N/A | `modules/local-downloader/android/chaquopy-wheels/VERSIONS.json` | Source of truth for ABI coverage + pinned dependency versions. |

ABI alignment rule:
- Plugin/runtime defaults target `arm64-v8a`.
- Enable `x86_64` only when matching Android wheels are present for every impersonation dependency.
- Keep ABI coverage synchronized across:
  - plugin (`app.plugin.js`)
  - verification scripts (`scripts/*impersonation*`, `verify-local-downloader-prebuild.sh`)
  - wheel metadata (`VERSIONS.json`)
  - runtime diagnostics (`local_downloader.py` / diagnostics screen)

## Verification Workflow

Run these before release builds and after native/plugin changes:

```bash
npm run verify:impersonation-wheels
npm run verify:prebuild
npm run test:python
```

What they validate:
- `verify:impersonation-wheels`
  - Required ABI wheel coverage, checksum validity, Android wheel shape.
- `verify:prebuild`
  - Expo prebuild idempotency (runs twice), generated Gradle markers, manifest entries, ABI filters, merged native libs.
- `test:python`
  - Python-side downloader tests under `modules/local-downloader/tests/`.

Why `verify:prebuild` is mandatory after plugin/ABI changes:
- `app.plugin.js` injects generated blocks into Gradle/Manifest.
- A local build can appear to work while generated config drifts.
- The verifier catches drift and missing binary coverage early.

## Common Customization Paths

UI/UX:
- Home, private vault, settings, diagnostics:
  - `app/(tabs)/index.tsx`
  - `app/private-videos.tsx`
  - `app/private-player.tsx`
  - `app/settings.tsx`
  - `app/diagnostics.tsx`

Downloader behavior:
- Python extraction strategy and error mapping:
  - `../shared/engine/local_downloader.py`
- Kotlin orchestration, background behavior, storage, auth-gated private flows:
  - `modules/local-downloader/android/src/main/java/expo/modules/localdownloader/LocalDownloaderModule.kt`

Vault crypto / playback / organization:
- `modules/local-downloader/android/src/main/java/expo/modules/localdownloader/vault/*` (cipher, loopback server, thumbnails, migrator)
- `app/private-videos.tsx` (list, tags/folders UI, multi-select, sort, search)
- `src/components/Chip.tsx` (reusable tag pill)
- JVM tests: `modules/local-downloader/android/src/test/java/.../vault/VaultCipherV4Test.kt`

Type/API surface:
- TS bridge types:
  - `modules/local-downloader/src/LocalDownloader.types.ts`
- JS wrappers:
  - `src/api/localDownloader.ts`
  - `src/api/download.ts`

When you change version pins, update all together:
- `modules/local-downloader/app.plugin.js`
- `../shared/engine/local_downloader.py`
- `modules/local-downloader/android/chaquopy-wheels/VERSIONS.json`

## Troubleshooting

1. `verify:impersonation-wheels` fails with missing ABI wheel
- Ensure Android-tagged `curl_cffi` wheel exists for default `arm64-v8a`.
- If you enabled `x86_64`, also provide `x86_64` wheel coverage.
- Rebuild wheels and regenerate `SHA256SUMS`.

2. Prebuild verification fails on generated markers
- Run `npx expo prebuild --clean --platform android --no-install`.
- Confirm plugin-generated blocks are present exactly once.

3. FFmpeg runtime unavailable / merge failures
- Verify `libffmpeg.so` and `libffprobe.so` under required ABI directories.
- Check diagnostics screen values for runtime source, ABI, and probe errors.

4. Extraction quality differs by site
- Check cookie profile defaults in Settings.
- Check diagnostics for impersonation runtime availability and attempted targets.

5. Background actions not behaving as expected
- Confirm notification permission granted.
- Check foreground service and receiver entries in generated manifest.

6. Vault videos / thumbnails work in debug but fail in the release APK
- Almost always the cleartext policy. The vault plays back over `http://127.0.0.1` and Android 9+ blocks cleartext in non-debuggable builds.
- Confirm `android.usesCleartextTraffic: true` is set in `app.config.js` under `expo-build-properties`, re-run `npx expo prebuild --platform android`, and verify `android:usesCleartextTraffic="true"` is present on `<application>` in the generated `android/app/src/main/AndroidManifest.xml`.

7. Vault crypto crashes only in a minified release build
- If you enabled R8 (`android.enableMinifyInReleaseBuilds=true`), Tink fails with reflection errors unless its keep rules survive. They're injected via `expo-build-properties.extraProguardRules`; verify they landed in `android/app/proguard-rules.pro` after prebuild.

## Security, Privacy, and Legal Notes

- This project handles sensitive user inputs (URLs, cookies, private vault content). Avoid logging sensitive values in production builds. In particular, never log the loopback playback URL — its per-session token is the access control.
- Vault content is encrypted at rest (cipher v4: authenticated chunked AEAD) with a key wrapped by an Android Keystore master key, and auth-gated per action. Caveats:
  - The Keystore master key binds the vault to the device — a copied vault file won't decrypt elsewhere. This also means a factory reset or lock-screen credential change can invalidate the key (`KeyPermanentlyInvalidatedException`); the v4 paths handle this gracefully, legacy v2/v3 paths do not.
  - `usesCleartextTraffic` is enabled so the loopback playback server works in release. It's loopback-only traffic; the downloader's own network goes through Python/curl-cffi, which isn't governed by Android's cleartext policy.
  - Rooted / compromised devices are out of scope for full confidentiality guarantees.
- Keep any secrets out of source control (`.env`, keystores, private creds are gitignored).
- Ensure legal compliance for content downloading/distribution in your jurisdiction and target markets.
- If you ship modified FFmpeg binaries, review codec/library licensing obligations.

## License

This project is licensed under **GPL-3.0-or-later** — see the [`LICENSE`](LICENSE) file for the full text.

The gist (not legal advice):
- You may use, study, modify, and redistribute this software.
- If you **distribute** the app or a derivative (e.g. handing out APKs, publishing a fork), you must release your **complete corresponding source** under GPL-3.0-or-later as well, and preserve the license/copyright notices.
- There is **no warranty**.
- Running it privately on your own device imposes no obligation to share anything.

Bundled and depended-on third-party components keep their own licenses — notably `yt-dlp` (Unlicense), Google Tink (Apache-2.0), NanoHTTPD (BSD-3-Clause), the Expo/React Native stack (MIT), and any **FFmpeg** binaries you supply (LGPL/GPL depending on how they were built — review your build's flags before redistributing). Their terms govern those components; GPL-3.0-or-later governs this project's own code.
