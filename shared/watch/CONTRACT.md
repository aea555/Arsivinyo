# Watch: the contract

Arsivinyo as a Stremio-style client, and as a torrent client in its own right. What both apps
build, and what they must agree on. Agreed with the maintainer before any code was written;
change it here first, then in code.

Arsivinyo is for personal use and will never be published to a store. Nothing here is shaped
by store policy.

## The two things it is

1. **A Stremio-style client.** Browse catalogs from Stremio add-ons, open a title, pick a
   stream, watch it. Streams can be direct links, links yt-dlp resolves, or torrents.
2. **A torrent client.** Open a `.torrent` file or a magnet link and download it, choosing
   which files, like any download in the app.

Both use the same torrent engine. A stream from a torrent is a torrent download that plays
while it arrives.

## Add-ons

Arsivinyo speaks the Stremio add-on protocol as Stremio defines it
(<https://github.com/Stremio/stremio-addon-sdk/blob/master/docs/protocol.md>), so existing
add-ons work unchanged. Nothing about it is Arsivinyo's own.

- An add-on is a **base URL**. `GET {base}/manifest.json` describes it: `id`, `version`,
  `name`, `resources`, `types`, `catalogs`, `idPrefixes`, `behaviorHints`.
- Resources are fetched as `GET {base}/{resource}/{type}/{id}.json`, and with extra
  arguments as `GET {base}/{resource}/{type}/{id}/{extra}.json`, where `{extra}` is
  `name=value` pairs joined with `&`, each URL-encoded (`search=…`, `skip=…`, `genre=…`).
- The resources used: `catalog` (lists of titles), `meta` (a title, with its episodes),
  `stream` (ways to watch a title or an episode), `subtitles`.
- A **stream** is exactly one of: `url` (play it), `ytId` (a YouTube video), `infoHash`
  with optional `fileIdx` and `sources` (a torrent), `externalUrl` (open it in the browser).
  `name`, `title`/`description` and `behaviorHints` (`bingeGroup`, `notWebReady`,
  `proxyHeaders`, `filename`, `videoSize`) are honoured where they mean something here.
- Ids are the add-on's. Titles from IMDb-based add-ons use `tt…` ids, and episodes
  `tt…:season:episode`, so streams from several add-ons line up under one title.

**Installed add-ons** are a list of base URLs with their last manifest, in order: catalogs
show in that order, and a title's streams are gathered from every add-on that offers
streams for that type and id prefix, in parallel, each with a timeout. An add-on that
fails or is slow is shown as such, never holding up the rest.

**Add-on URLs are secrets.** A configured add-on often carries an account token in its URL
(a debrid key, a personal config). Add-on URLs are stored encrypted, as cookie profiles are,
never logged, and never shown in full: the UI shows the add-on's name and host only.

**The recommended add-ons.** Both apps offer one set of public community add-ons, installed
in one tap from the empty Watch screen or the add-ons screen, so a new install plays real
films and series at once. In this order:

| Add-on | Base | Gives |
|---|---|---|
| Cinemeta | `https://v3-cinemeta.strem.io` | catalogs, meta |
| Streaming Catalogs | `https://7a82163c306e-stremio-netflix-catalog-addon.baby-beamup.club` | catalogs of streaming services |
| Torrentio | `https://torrentio.strem.fun` | torrent streams |
| TorrentsDB | `https://torrentsdb.com` | torrent streams |
| ThePirateBay+ | `https://thepiratebay-plus.strem.fun` | torrent streams |
| OpenSubtitles v3 | `https://opensubtitles-v3.strem.io` | subtitles |

Only the missing ones are installed. An installed add-on from the same host counts as there,
so one the user configured (a Torrentio with a debrid key) is never replaced by the plain
one. None needs configuring; add-ons that do, or that need an account, are not in the set.
The lists are `RECOMMENDED` in `mobile/src/api/watch.ts` and `WatchService.recommended` on
the Mac; change both.

**yt-dlp as a resolver.** A `url` stream that is a web page rather than media, an
`externalUrl` that yt-dlp can extract, and a `ytId` are all resolved through the engine the
app already ships, so they play in the app rather than in a browser.

## The torrent engine

**libtorrent** (rasterbar, 2.0.x, BSD), in `shared/torrent/`: one C++ engine compiled into
both apps, as the faces pipeline is. `shared/torrent/VERSIONS.json` pins libtorrent and its
dependencies. Both apps build them from source with the same flags (`build.sh`), and compile
against them with the defines libtorrent was built with (`build/<platform>/defines.txt`).
`torrent.h` is the C API both apps call; `test/` holds it to this section on loopback.

### Streaming

- The engine downloads in the order playback needs. Pieces just ahead of the play position
  get deadlines, the rest of the window gets high priority, everything else stays normal.
  When the player seeks, the window moves.
- The player reads the file from a **local HTTP server** on `127.0.0.1` with a random port
  and a random path token, with byte ranges. The vault's playback already works this way. A
  range not yet downloaded blocks until it arrives or the player gives up.
- Only the chosen file of a torrent is wanted; the others are set to "do not download".
  The read-ahead never goes past the file's own last piece: a deadline fetches a piece
  whatever its file's priority, so going further would fetch the files after it.
- Which file plays: the add-on's `fileIdx`, else the one its `filename` names, else the
  largest video. A stream's magnet carries the trackers among its `sources`.
- A torrent that was only streamed is **kept in a cache** with a size limit (default 10 GB
  on the Mac, 4 GB on the phone), oldest first out. Watching it again starts from the cache.
- **Keep it**: a streamed file can be kept, finishing the download, then filed like any
  download, or into the vault.

### Downloading

- From a `.torrent` file (opened, dropped, shared to the app), a magnet link (pasted, or
  opened from the browser), or a stream's "Download" action.
- The files in it are shown with sizes; the user picks which. Defaults: all of them. Until
  they are picked, the torrent fetches nothing but its file list. Once they are, it is added
  again, fresh, with the file list, its trackers and the choice: a torrent that waits wanting
  nothing loses its peers (a seed hangs up on a peer that wants nothing from it), and
  libtorrent waits before reconnecting to a peer it lost; a fresh torrent asks its trackers
  and the DHT at once. Measured on a real swarm: 0 peers after waiting, 51 peers and 637 MB
  in the first 15 seconds once added fresh.
- It joins the app's download list and its notifications, with progress, speed, peers and
  time left. It can be paused, resumed and removed, with or without its files.
- **Where it lands:** public (the Mac's download folder; the phone's `Download/Arsivinyo`
  through MediaStore, as the app's other files) or private (into the vault, encrypted as each
  file completes; the plaintext lives only in app-private storage until then). The vault's key
  exists only while it is open, so a file that finishes while it is locked waits, in
  app-private storage, until it is next open; the torrent stops serving a file once it is in
  the vault, and is removed when all of them are. A public download seeds from its own copy
  and is removed, with that copy, when it stops seeding; the filed copy stays.
- Resume data is saved every half minute for what changed, and for everything on quit, so a
  restart, or the phone killing the app, carries on where it stopped.
- The records (name, files chosen, destination, what has been filed) live in the library's
  `torrents`, encrypted with the rest.

### Seeding

The default is to seed while the app is open until the ratio reaches **1.0**, then stop.
The ratio is a setting, and so is "never seed". The phone does not seed on mobile data
unless told to. Nothing seeds in the background on the phone once the app's download
service has nothing else to do. Rate limits apply to every peer, those on the local network
too (libtorrent leaves them out by default). These settings are each device's own, kept
beside the engine's state rather than in the library, which phase 4 syncs.

### The heads-up

Torrenting shows this device's IP address to everyone sharing the same torrent. Arsivinyo
does not enforce anything about it. It **says so**:

- The first time a torrent starts, streamed or downloaded, a modal explains this and
  recommends a VPN. It has "Got it", which holds until the app is next started, and "Don't
  show again", which holds for good.
- When no VPN appears to be active (Android: no network with `TRANSPORT_VPN`; Mac: no
  `utun` interface carrying the default route), the torrent screens show a small,
  dismissable notice. It is a hint, not a gate. Detection can be wrong either way, and the
  wording says "appears".

## The player

**libmpv** in both apps. The system players cannot play what torrents and add-ons mostly
carry: AVFoundation cannot open MKV at all, and ExoPlayer lacks DTS and styled (ASS)
subtitles. mpv plays all of it.

- Embedded in each app's own UI: SwiftUI on the Mac, a native view in the React Native
  screen on the phone. The controls are Arsivinyo's: play, seek, audio track, subtitle track,
  subtitle delay, playback speed.
- Subtitles come from the file, from the stream's `subtitles`, and from add-ons with the
  `subtitles` resource, in the user's preferred languages first (a setting; Turkish, then
  English, to begin with). Languages are ISO 639-2 codes; whatever an add-on or a file
  calls one (`tr`, `TUR`, `tr-TR`) is read as its code, and only the languages offered are
  known (`VECTORS.json`, `languages`).
- What is offered from add-ons: the stream's own, then each add-on's in the add-ons' order,
  kept only in a preferred language, grouped by preference, at most five per language, a URL
  once (`subtitleRanking`). The best is loaded by itself when the file has nothing in a
  language preferred as much; the rest wait in the subtitles menu. A trailer asks no add-on.
- mpv picks the file's own audio and subtitle tracks by the same languages.
- A page that goes through yt-dlp (YouTube, a trailer) is handed to mpv as two plain files,
  the best video up to 1080p and the best audio, opened together. Not as an HLS master
  playlist where there is a choice: FFmpeg opens every variant it lists, and a segment of
  each, before it plays anything (about nine seconds for YouTube's on a phone, against a
  quarter of a second for the two files). A live stream has only HLS, and gets it.
- The player shows that it is loading from the moment it is asked to play until the first
  frame, and while it waits for the network or a seek.
- The vault's own videos play through the same player, so a private MKV plays too: on the
  phone over the vault's loopback server, on the Mac through mpv's stream callbacks, which
  decrypt what mpv reads as it reads it. Neither writes plaintext.
- mpv is GPL/LGPL; Arsivinyo is GPL-3.0-or-later, so it ships under the GPL build.

## The library

What has been watched, and where it stopped.

```jsonc
{
  "version": 1,
  "addons": [{"url": "<encrypted with the rest>", "manifest": { … }, "enabled": true}],
  "items": [{
    "id": "tt0944947",                 // the add-on's id for the title
    "type": "series",                  // as in the manifest: movie, series, channel, tv
    "name": "…", "poster": "…",        // copied from meta, so the library shows offline
    "addedAt": 0,
    "watched": ["tt0944947:1:1"],      // finished videos
    "progress": {"videoId": "tt0944947:1:2", "positionMs": 0, "durationMs": 0, "at": 0},
    "stream": {"addon": "…", "bingeGroup": "…"}   // to pick the same source for the next one
  }],
  "languages": ["tur", "eng"],         // subtitles and audio, most preferred first
  "torrents": [{
    "infoHash": "…", "name": "…", "files": [ … ], "wanted": [0, 2],
    "destination": "public" | "private" | "cache",
    "addedAt": 0, "state": "downloading" | "seeding" | "paused" | "done"
  }]
}
```

Encrypted at rest under a device key, as the memes index is: what someone watches is as
private as what they save. Nothing in it reaches a log or a notification. A notification
says "Downloading 2 torrents, 45%", never a name.

**Continue watching** is items with progress, newest first. When a video ends, the next
episode is offered from the same add-on and `bingeGroup`, so a series keeps its source.

## Between devices

Over pairing, in a later phase:

- **Play there.** Send what is playing (title, video id, stream, position) to the other
  device, which opens it at the same place. A torrent stream is sent as its info hash and
  file index, so the other device fetches it itself.
- **Library sync.** Installed add-ons, watched marks and progress merge between the two by
  item id, keeping the latest progress. Add-on URLs travel only over the paired, encrypted
  connection.
- **Hand a torrent over.** A `.torrent` or magnet goes to the other device to download there.

**Backup:** a `watch` section in the `.avsbck` holds the library with its add-ons. Torrent
payloads are not backed up: they can be fetched again.

## Phases, and what "done" means

**Phase 1: add-ons and streams that are links.** Both apps.

- Install an add-on from its manifest URL; list, reorder, disable and remove them.
- Board: each add-on's catalogs as rows; search across add-ons that support it.
- A title's page with its episodes, and its streams gathered from every add-on.
- `url`, `ytId` and yt-dlp-resolvable streams play, in the system player for now.
- The library, with continue watching.

Done when: a public add-on (Cinemeta for catalogs and metadata, plus a link-based stream
add-on) browses, searches and plays on both apps; a slow add-on never blocks the page; no
add-on URL appears in a log; the library survives a restart and is unreadable on disk.

**Phase 2: the player.** libmpv in both apps, replacing the system player everywhere it
plays add-on streams and vault videos.

Done when: an MKV with HEVC video, AC3/DTS audio and ASS subtitles plays with its styling on
both apps; add-on subtitles load; the vault plays a private MKV without writing plaintext. The
sample is `fixtures/hevc-ac3-dts-ass.mkv`; the phone plays it in `MpvInstrumentedTest`, the Mac
in CoreChecks (from a file and out of a vault).

**Phase 3: torrents.** The engine in `shared/torrent`, built for both apps; streaming with
the local server; downloading `.torrent` files and magnets with file selection, public or
private destinations, pause and resume; the cache; seeding rules; the heads-up.

Done when: a well-seeded torrent stream starts playing before it is complete and seeks to
its end; a magnet download picks files, survives the app being killed, and lands in the vault
when private, with no plaintext left behind; the cache stays under its limit; the
modal shows once and the VPN notice appears when no VPN is up. Held by `shared/torrent/test`
(streaming the end first, resume, file selection, seeding, magnets, the cache), its phone
counterpart `TorrentInstrumentedTest`, and the Mac's `CoreChecks` torrents section (a magnet
download picked, stopped halfway, carried on by a new engine, into a vault, no plaintext left).

**Phase 4: between devices.** Play there, library sync, torrent hand-over, and the backup
section.

Done when: what plays on the phone continues on the Mac at the same position; a title
watched on one is marked on the other; a restored backup brings the add-ons and the library
back.

**Not planned:** a catalog of Arsivinyo's own, an account or any server of Arsivinyo's, and
casting to TVs (it may come later as its own contract).
