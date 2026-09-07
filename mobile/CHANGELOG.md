# Changelog

All notable changes to this project are documented here. Format based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), versioning follows [SemVer](https://semver.org/spec/v2.0.0.html) with `-beta.N` prerelease suffixes.

## [Unreleased]

### Added
- **Device pairing.** Two devices you own, on the same network, exchanging files directly
  — no cloud, no account, no relay. The desktop app implements the whole protocol:
  discovery over mDNS, a pairing ceremony where both screens show the same six digits, a
  TLS connection, and the four verbs (`list`, `get`, `put`, `download`). This app carries
  the shared protocol — the framing, the code derivation, the identity key, the
  authentication handshake — along with the list of paired devices and the TLS connection
  itself. Discovery, the transfers and the screens are still to come.
- Each device now has a permanent Ed25519 identity. Its public key *is* the device; the
  SHA-256 of that key is the fingerprint shown when pairing. The vault is deliberately not
  reachable over this channel: it stays confined to the device that made it, and a
  `.avsbck` backup remains the only way to move its contents.

### Changed
- The app is named **Arsivinyo**, not "Arsivinyo Local", and its deep-link scheme is
  `arsivinyo://`. "Local" described a downloader with no backend, which stops being the
  distinguishing fact now that a desktop app and device pairing are planned. The
  `applicationId` is untouched at `com.arsivinyo.local`, so the vault, the music library
  and settings survive the upgrade.
- The repository is `Arsivinyo`, and this app now lives in `mobile/` so `desktop/` and
  `shared/` can join it. npm commands run from `mobile/`.

### Fixed
- The wheel verifier no longer reports a failure of `unzip` as a corrupt wheel. One
  condition covered both, so a transient tool failure accused the wheel while its
  checksum passed.

## [2.6.0-beta.1] — Concurrent downloads, and an audio save that finishes

Downloads no longer run one at a time, and saving a long audio track no longer takes
hours. `versionCode` → `20600`.

### Added
- **Downloads run at the same time.** A download used to hold the whole pipeline from the
  first network byte to the last write, so the CPU was idle through a ten-minute transfer
  and the network was idle through a five-minute transcode. Each stage now takes a permit
  from its own gate and gives it back before asking for the next, which lets one download
  be written to disk while others are still arriving. Waiting downloads are admitted
  shortest-first, so a three-minute song shared during a long download does not sit behind
  it, with aging so a long one cannot be starved indefinitely.
- **A Kotlin typecheck that needs no Gradle** — `npm run typecheck:kotlin`. The existing
  test script can only compile sources with no Android dependency; this covers the rest by
  putting `android.jar` and the already-extracted AAR classes on the compiler classpath.

### Changed
- **The queue limit is gone.** Sharing a fourth link was refused with "queue is full",
  because a hard-coded three bounded a list of URL strings that had nothing to do with how
  much the app could handle. There is no separate waiting list any more: a share becomes a
  download immediately and waits at a scheduler gate. What remains is a runaway guard far
  above anything reachable by hand.
- The notification reports how many downloads are running and their combined progress,
  rather than picking one to represent them all, and its stop button stops all of them.
- The home screen tracks each download separately, with its own progress bar, rate and
  cancel button, instead of one set of fields that two downloads would fight over.
- **Five downloads work at once instead of three.** Each FFmpeg process is single-threaded,
  so this is also how many of the phone's eight cores a batch of long transcodes can put to
  work; at three, a queue of four took two passes to clear while five cores sat idle.
- **An M4A download asks for a source that is already AAC**, so yt-dlp copies the stream
  into the container instead of re-encoding it. The bundled FFmpeg encodes FLAC at about
  143x realtime but AAC at only 23x, so an eight-hour track cost roughly 21 minutes of
  transcoding where a remux costs seconds. It also stops a needless quality loss: the
  previous selection took YouTube's Opus stream and re-encoded it to AAC, a second
  generation of lossy compression to arrive at a similar bitrate.

### Fixed
- **Saving a long audio track took hours.** The download was copied into the music library,
  the local copy deleted, and the preset renderer then copied the identical bytes back out
  of the library to get a file FFmpeg could open — every one of those crossings going
  through MediaProvider's FUSE layer, which charges per write syscall. Renders now read the
  downloaded file directly, and when *keep original* is off the original is never filed at
  all, since it only existed to be deleted afterwards. A track that could not be rendered
  is still filed, so a failed render never costs the audio.
- The copy into the music library uses a 1 MB buffer, matching the vault. At the previous
  64 KB a one-gigabyte track cost about 17,000 round trips into MediaProvider instead of
  about 1,100.
- The music library's lock no longer covers the file copy, only the index write. A save
  used to freeze every other library operation — including plain reads — for its whole
  duration.
- Progress is reported for every download. The watcher checked whether its task was *the*
  active one, which at most one download could be, so the others showed no progress at all.
- Two downloads can no longer be attributed each other's diagnostics. The Python worker
  kept them in one module-level dictionary that every result payload was built from, so a
  second download could overwrite the first's URL and attempt trace, and starting one wiped
  the other's trace mid-flight.
- Orphaned staged downloads are swept at startup. These are whole media files, so one left
  behind by a process death was gigabytes.
- The APK no longer carries the x86_64 FFmpeg binaries. They exist for the opt-in emulator
  build and were being packaged into every phone build, which cost 14.4 MB of a 94 MB APK.

## [2.5.0-beta.1] — Encrypted whole-app backup and restore

Export the vault, the music library, the preferences and the cookie profiles into one
encrypted `.avsbck` file, and restore them on any device. `versionCode` → `20500`.

### Added
- **Whole-app backup and restore.** A single `.avsbck` container holds four independent
  sections — private vault, music library, preferences, cookie profiles — each encrypted
  separately and each selectable on both export and import. The file goes wherever the
  system file picker points; nothing is uploaded anywhere.
- **Argon2id for the key, AES-GCM for the content.** A backup can sit on storage for years,
  which is the threat model where an attacker gets unlimited offline guesses, so the key
  derivation is memory-hard: every guess costs an attacker the same 64 MB it costs the
  device. The content uses the same streaming cipher that already protects the vault, in
  1 MB segments, so a multi-gigabyte video is never held in memory.
- **Password or passphrase.** A password needs 14 characters and no particular symbols —
  length protects far better than character classes, and `Password1!` satisfies every
  classic rule while being trivially guessable. A passphrase is 4 to 12 words with a
  separator of your choosing, and can be generated from a 256-word list at exactly 8 bits
  per word using the platform's cryptographic random source.
- **The import describes a file before asking for anything.** The header is plaintext, so
  the screen can show what a backup holds and which kind of secret it wants. Item names and
  metadata live inside the encrypted region, so a backup reveals roughly how much it holds
  and nothing about what.
- **Duplicates are skipped by content, not by name.** Restoring twice is safe, and an
  interrupted restore is finished by simply running it again.
- **A backup keeps running in the background.** It holds the foreground service like a
  download does, and the screen reattaches to a job that started before it was opened.

### Changed
- An export overlaps reading with encrypting instead of doing them in turn, which cut a
  13 GB export from 79.8 s to 47.6 s on the reference device.

### Notes
- Restoring never overwrites: a cookie profile already on the device is left alone, so a
  restore cannot replace a session you have signed into since the backup was made.
- Vault videos and cookie profiles are re-encrypted for the backup rather than copied. Their
  stored form is bound to the device keystore and would be unreadable anywhere else —
  including on the same phone after reinstalling.

## [2.4.0-beta.1] — Audio presets (native C++ DSP)

Apply Slowed + Reverb, Nightcore or Bass Boost to any track, and download audio losslessly. `versionCode` → `20400`.

### Added
- **Audio presets, rendered by a native C++ DSP module.** A new `libaudiopresets.so` owns the signal processing: a fractional resampler for the rate control, a Schroeder-Moorer reverb, RBJ shelving EQ, and a lookahead limiter. The bundled FFmpeg is used only for container and codec work — it decodes to raw float on a pipe, the DSP processes it, and a second FFmpeg encodes the result. The rate control resamples without pitch correction, so tempo and pitch move together; that is what makes "slowed" sound slowed rather than time-stretched.
- **Three built-in presets** — Slowed + Reverb, Nightcore, Bass Boost — plus **user-created presets** with a name and eleven sliders. Built-ins can be adjusted and restored to their shipped values; user presets can be renamed, changed and deleted. Every destructive action confirms first.
- **Apply to one track or many.** Long-press to select, then the wand button. A single track is just a batch of one, so both use the same path.
- **Auto-applied presets on download.** Choose in Settings what an audio download produces: the original, and/or one track per selected preset. One download can therefore create several library entries. At least one option must stay selected.
- **Batch renders survive the app being stopped.** The queue is written to disk after every job and the foreground service is held for the duration, so a swipe from recents — or a real process death — does not lose the remaining work.
- **Track metadata**, in a sheet from the library (single selection only) and as a section below the player controls. Shows format, quality tier, duration, size, date, and file name; a rendered track also names its preset and its source track.
- **A badge on rendered tracks**, read from the recorded `presetId` rather than the title, so it survives a rename.

### Changed
- **Audio downloads are now 16-bit FLAC with TPDF dither** instead of M4A/AAC 256k, with a **"Lossless downloads"** setting to switch back. Every source is already lossy, so encoding to AAC again stacked a second generation of loss for no benefit; FLAC keeps exactly what the decoder produced at roughly 3x the size. Sample rate is no longer pinned — a 48 kHz source stays at 48 kHz rather than being resampled to 44.1 kHz.
- **A preset render matches the quality tier of its source.** A lossless source gives FLAC; a lossy source gives AAC at 320k, above the downloader's 256k because that render is a second generation. FLAC recovers nothing a lossy encoder discarded, so inflating an already-lossy track would triple its size for no gain.
- The music library shows each track's format, with lossless called out in the accent colour.
- `ConfirmModal` moved into `src/components` and is now shared by the library and settings.

### Fixed
- Chaquopy could not build on a host whose default Python is 3.13 or newer: its bundled pip imports the `cgi` module, which Python removed. The plugin now finds a suitable interpreter for pip, independently of the Python the app targets.
- Sheets did not scroll and their sliders did not respond to a drag, because a `Pressable` ancestor took the touch responder. Affects every sheet, not only the preset editor.
- The dim layer behind a sheet did not cover the screen once the keyboard opened.

### Internal
- 82 host checks for the C++ DSP and render pipeline (`npm run test:dsp`), and 30 for the preset rules (`npm run test:presets`). One test compares the parameter ranges against the clamps in `preset_params.cpp`, where a mismatch would otherwise silently disable a slider's range.
- The Python tests no longer report success when the module fails to import — that previously hid a real defect behind `OK (skipped=N)`.
- The local changes that make ffmpeg-kit build the `ffmpeg`/`ffprobe` executables are saved as a patch in `modules/local-downloader/ffmpeg-build/`, with the upstream tag and commit recorded. The binaries themselves stay out of the repository.

## [2.3.0-beta.1] — Audio downloads + in-app music player

Audio downloads and a full music player with playlists and background playback. `versionCode` → `20300`.

### Added
- **Download as audio.** A new "Audio mode" toggle on the home screen routes downloads through yt-dlp's best-audio path (`bestaudio/best` + `FFmpegExtractAudio`), writes title/artist metadata tags, and saves a cover-art thumbnail. Files are saved as **M4A/AAC** and land in the public **`Music/Arsivinyo`** folder (survives uninstall, visible to other apps). Audio mode disables the vault toggle — audio is never vaulted in this release. (M4A rather than MP3 because the bundled FFmpeg ships without an MP3 encoder; AAC sources are remuxed losslessly, others re-encoded at 256k. Cover art is stored as a sidecar thumbnail rather than embedded into the file, because that same FFmpeg has no image encoder to transcode the source `.webp` cover.)
- **Human-readable audio filenames.** A dedicated audio sanitizer (`_sanitize_audio_title`) preserves spaces and Unicode (only stripping filesystem-illegal characters), so "This song is amazing" stays `This song is amazing.m4a` instead of being underscore-slugged. Same timestamp-to-now behavior as video saves.
- **In-app music player** (`react-native-track-player`) with background playback, lock-screen / notification transport controls, headset-button support, and audio-focus handling. Symmetric transport row (previous · −10s · play/pause · +10s · next), a **drag-or-tap seek bar** (Animated-value driven so scrubbing stays smooth), and repeat (off/all/one). Large artwork + title clearly shown.
- **Favorites.** A special, non-deletable **Favorites** playlist (reserved id `favorites`, pinned to the top of the Playlists tab). Bulk favorite/unfavorite from the Songs tab and any playlist via a smart heart toggle (favorites unless every selected song already is, in which case it unfavorites — so inside Favorites it only ever unfavorites), plus a heart toggle on the player screen for the current track. Fully local (an entry in `sounds/index.json`).
- **Music library screen** (`app/sounds.tsx`): a **Songs / Playlists** segmented layout — "Songs" is the full library (search, sort by newest/oldest/title/duration), "Playlists" is a vertical list of playlists you tap to open (with a back arrow). Multi-select with confirmation-gated batch delete and add-to-playlist, a persistent mini-player bar, the currently-playing track shown with an accent outline, and bulk import of existing audio files via a multi-select SAF picker.
- **Playlists** (many-to-many): create / rename / delete (via a per-playlist overflow menu), add songs by per-row **＋** button or multi-select batch, and remove from a playlist. Destructive/important actions confirm via modal.
- **Thumbnails** for every track, extracted from embedded cover art (`MediaMetadataRetriever`) and cached as sidecar JPEGs for fast display.
- Native music API on `LocalDownloaderModule` (`listSounds`, `importSounds`, `deleteSounds`, `renameSound`, playlist CRUD) backed by a new `sounds/index.json` (song cache + playlists), reconciled against MediaStore on each load so externally-deleted tracks drop out cleanly.
- New `SoundsImportActivity` (multi-select `audio/*` SAF picker), injected into the manifest by the local-downloader plugin.
- All new UI fully localized in EN + TR (other 8 locales fall back to English).

### Changed
- The music library requires **Android 10+ (API 29)** — it uses the MediaStore scoped-storage owner model to read/write `Music/Arsivinyo` with **no new permissions** (the app still blocks all `READ_MEDIA_*` / external-storage permissions). On older devices the feature degrades with a clear message.
- App entry is now `index.js` (registers the track-player playback service before expo-router boots).
- **`react-native-track-player` upgraded to the 5.x New-Architecture nightly.** The 4.1.2 stable can start playback but its commands and remote events don't reach the player under the New Architecture's bridgeless mode (controls were inert); the 5.x TurboModule build fixes this. The old `patches/react-native-track-player+4.1.2.patch` is removed.
- **Player UX:** tapping a song row now just starts playback (open the full player via the mini-player or the notification); the play button restarts a track that has finished (when not looping); **previous** restarts the current track on a single press and skips to the previous track on a double press (in-app button and notification alike).
- The notification-tap deep link (`notification.click`) is routed to the player via `app/+native-intent.ts` instead of hitting expo-router's "Unmatched Route" 404.

### Fixed
- **Audio import was immediately cancelled.** The SAF import activities had `android:noHistory="true"`, so Android finished them the moment the full-screen document picker appeared — firing their cancel path and dropping the real selection. Removed `noHistory` from both import activities (kept on the share-capture activity) and made the plugin's manifest writer replace attributes so the removal actually applies on prebuild.

### Deferred
- Encrypted vault export/import bundle (`.avbundle`) — still deferred to a later release; unchanged by this work.
- Shuffle, sleep timer, playback speed, duplicate detection, "re-adopt library after reinstall" (SAF), and in-playlist drag-reorder were scoped out of v1 as optional QoL.

## [2.2.0-beta.3]

### Added
- **GPL-3.0 license.** The project is now formally open source under GPL-3.0-or-later (`LICENSE` + `package.json` license field). Derivatives that are distributed must also be GPL with source available.
- **Release signing config plugin** (`plugins/withReleaseSigning.js`). Release builds can now be signed with a private keystore (credentials read from Gradle properties), falling back to debug signing when absent. See README "Release signing".
- **Signing verification helper** (`scripts/verify-signing.ps1`, `npm run verify:signing`). Prints the APK file SHA-256 + signing-cert SHA-256 and confirms an APK was signed with your release key (prefers `apksigner`, falls back to `keytool`).

### Removed
- **In-app ads.** Vestigial banner/interstitial scaffolding carried over from the old server-side version of the app — the `BannerAd` component, its home-screen render, and the unused download-count/interstitial helpers in `services/storage.ts`. No ad SDK was present; this clears the leftover stubs. Removing them also tightens the privacy story for an off-store, sideloaded app (nothing phones home from the RN/Java layer).

### Changed
- README expanded with vault internals, versioning, and release-signing sections; documents the loopback-cleartext gotcha.

## [2.2.0-beta.2]

### Fixed
- **Vault videos failed to play and thumbnails failed to load in release builds.** The vault streams v4 playback and thumbnails from an in-process loopback HTTP server (`http://127.0.0.1:<port>`). Android 9+ blocks cleartext traffic by default in non-debuggable builds, so every vault item failed in the release APK while working in debug (debug permits cleartext for Metro). Added `usesCleartextTraffic: true` via `expo-build-properties` so release builds permit the loopback connection. Low risk: the vault is loopback-only and the downloader's network goes through Python/curl-cffi, which isn't governed by Android's cleartext policy.

## [2.2.0-beta.1] — vault organization (tags + folders)

Tags and folders for the private vault, plus batch operations for both.

### Added
- **Tags.** User-defined labels, many per video. Auto-assigned color from a 12-color palette. Filter the vault list by tag (OR semantics — videos matching any selected tag show). Manage tags (create, rename, delete) from the "Manage" chip in the filter row. Cascade-removes from every entry when a tag is deleted.
- **Folders.** Flat hierarchy (no nesting in v1). Each video belongs to 0 or 1 folder. Folder rows appear at root with item-count badges; tap to enter, tap the back chevron in the header to exit. Long-press a folder row to delete (videos inside move to root).
- **Single-video tag + folder edits.** Per-row "Tags" and "Move" buttons open dedicated pickers. Inline "Create new" affordances inside both pickers so users don't have to context-switch to create a tag or folder.
- **Batch tag + folder operations.** Multi-select bar gains Tag and Move icons. Both go through the same confirmation modal pattern as Copy/Delete. Batch tag union-merges with each entry's existing tags (additive, not replace).
- **Per-row tag chips.** Up to 3 visible chips per row + `+N` overflow indicator.
- New `Chip` / `ChipRow` components in [src/components/Chip.tsx](src/components/Chip.tsx) — reusable pill primitive with size variants.
- Native Tag + Folder APIs in `LocalDownloaderModule.kt` with `tag` and `folder` biometric auth purposes.
- All UI strings localized in EN + TR (other 8 locales fall back to English).
- Index.json schema bumped to v3 (additive: `tagDefinitions[]`, `folders[]`). v2 indexes auto-upgrade on first write.

### Changed
- `PrivateVideoEntry` gains `tags: List<String>` and `folderId: String?` (both default to empty/null for backward compat).
- `LocalPrivateAuthPurpose` adds `'tag'`, `'folder'`, `'bundleExport'`, `'bundleImport'`.
- `pendingBatch` state in `private-videos.tsx` extends to `'delete' | 'copy' | 'tag' | 'folder' | null`.
- Filter chain composition (memoized): `folder scope → active tag set → search query → sort` — single useMemo, recomputes only when any input actually changes.

### Deferred
- **Encrypted export/import bundle** (originally in this plan) — split off into v2.3.0 to keep the release scope shippable. Stub work for that release: `bundleExport` / `bundleImport` auth purposes already defined, but no UI / native implementation yet.
- Color editing for existing tags (auto-assigned color is permanent in v1; users can delete and re-create to get a new color).
- Folder nesting (flat only in v1).

## [2.1.0-beta.1] — vault hardening (first pass)

Backwards-compatible read of existing v2/v3 vault items. New imports use cipher v4. Opt-in re-encryption migration available in Settings.

### Added
- Cipher v4: Tink `AesGcmHkdfStreaming` with 1 MB segments — per-segment GCM tags give integrity (the prior CTR mode had none) and seekable random-access reads.
- Loopback HTTP server (`127.0.0.1`, ephemeral port, per-session token) for streaming v4 playback. No plaintext temp file is written during playback of v4 items.
- Encrypted thumbnails for vault items, generated at import time via `MediaMetadataRetriever` with ffmpeg-via-Chaquopy fallback for unsupported codecs.
- Rename support for vault items. Title is now decoupled from container extension.
- "Re-encrypt vault to current security" action in Settings — biometric-gated, pause/resume across launches, disk-space and battery pre-flight.
- Diagnostics screen now shows app version, version code, channel, and a "Vault" section (cipher counts, server status, active sessions, last migration result, Tink version).
- `CHANGELOG.md` (this file).
- Versioning convention documented in `CLAUDE.md`.

### Changed
- `app.config.js` is now the single source of truth for both `version` and `android.versionCode`. `expo prebuild` propagates them to `android/app/build.gradle`.
- `LocalPrivateAuthPurpose` gains a `'rename'` value (lighter than `'view'`, which triggers playback cleanup).
- New imports default to cipher v4. v2 and v3 reads remain supported.
- `PrivateVideoEntry` gains `thumbFileName`, `thumbWidth`, `thumbHeight`, `containerExt`, `durationSecExact`, `migrationFailed`.

### Fixed
- `FLAG_SECURE` is now applied before navigation to `private-player`, closing a one-frame screenshot window present in 2.0.x.
- Playback file extension is no longer derived from `entry.title` (would break after rename) — derived from new `containerExt` field, backfilled on first access for legacy entries.
- `android/app/build.gradle` `versionCode` is no longer hardcoded to `1`. It now comes from `app.config.js` `android.versionCode`, allowing Play Store updates.
- `KeyPermanentlyInvalidatedException` from the vault master key path is now caught and surfaced as a recoverable error instead of crashing (occurs when the user changes their device lock).
- `src/config.ts` no longer claims a fake `1.1.0` version when `expoConfig.version` is missing — surfaces `'unknown'` instead.

### Security
- Vault content is now authenticated (chunked AEAD). Tampering with encrypted bytes on disk is now detected at decrypt time.
- Playback of v4 items never decrypts the full file to disk. ExoPlayer streams from the in-process loopback server.

## [2.0.1] — 2025-05-11

Prior baseline. See git history.

## [2.0.0]

Major UX changes. See git history.

## [1.1.1] / [1.0.0]

Historical releases. See git history.
