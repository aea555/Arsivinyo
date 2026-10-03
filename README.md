# Arsivinyo

A personal media app for Android and macOS that runs **entirely on your own devices**: no
backend, no account, no cloud. It downloads media, keeps what is private in an encrypted
vault, plays films and series from Stremio add-ons and torrents, collects memes, and lets
your own devices send files to each other directly.

Licensed under **GPL-3.0-or-later**.

## What it does

- **Download.** Video and audio from the sites yt-dlp supports, with presets for format and
  audio processing.
- **Vault.** An authenticated, AES-encrypted store. What goes in is played back without
  plaintext ever reaching the disk, and leaves the device only as an encrypted backup.
- **Music.** A library with playlists and favourites, played in the app.
- **Watch.** Stremio add-ons for catalogs, streams and subtitles, played in mpv, with
  continue watching. Recommended community add-ons install in one tap.
- **Torrents.** Streamed while they download, or downloaded with the files you choose, into
  Downloads or straight into the vault.
- **Memes.** Everything downloaded or imported, with the post it came from, searchable by
  caption, tagged, and grouped by face.
- **Devices.** Two devices you own pair on the local network and exchange files over an
  encrypted connection, with no relay in between.

## What is here

| | |
|---|---|
| [`mobile/`](mobile/) | The Android app: Expo, with a Kotlin native module over yt-dlp, FFmpeg, mpv and the shared C++. |
| [`mac/`](mac/) | The macOS app: SwiftUI over the same C++ core. Needs Xcode. |
| [`shared/`](shared/) | What both apps must agree on: the security core, the pairing protocol, the audio DSP, the download engine, the torrent engine, the faces pipeline, and the contracts for memes and Watch. |

The apps do not reimplement each other. The Mac compiles the same C++ the phone is checked
against, and test vectors in `shared/` (`crypto/VECTORS.json`, `watch/VECTORS.json`,
`faces/VECTORS.json`) bind both, so a disagreement is a failing check rather than a vault
or a library one app cannot read.

Each app is self-contained, with its own dependencies and scripts:

- **Android:** start with [`mobile/README.md`](mobile/README.md). It covers the
  architecture, the vault, the version pins and how to build from scratch. npm commands
  run from `mobile/`.
- **macOS:** start with [`mac/README.md`](mac/README.md). It covers fetching the engine and
  mpv, bundling and signing, and the checks.

## Status

Both apps are in beta, at **4.0.0-beta.1**. The changelog is
[`mobile/CHANGELOG.md`](mobile/CHANGELOG.md). Arsivinyo is for personal use and is not
published in any store.
