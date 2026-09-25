# Arsivinyo — macOS app

A SwiftPM package, built into an app by `scripts/bundle.sh`. It needs Xcode, not only the
Command Line Tools: SwiftUI's macros and the icon compiler ship with Xcode.

```
scripts/fetch-engine.sh      # once: yt-dlp and curl_cffi into .build/engine
scripts/bundle.sh            # .build/Arsivinyo.app
open .build/Arsivinyo.app
```

## Layout

```
Sources/ArsivinyoCryptoC/   a flat C boundary over the C++ security core
  include/                  the header Swift imports
  shim.cpp                  the boundary itself
  shared -> ../../../shared/crypto      a symlink, not a copy
Sources/ArsivinyoDSPC/      the audio presets: shared/dsp, the same way
Sources/ArsivinyoPairingC/  pairing: shared/pairing's wire format, Ed25519 and TLS
Sources/ArsivinyoCore/      Swift over those boundaries
Sources/ArsivinyoApp/       the app
Sources/CoreChecks/         the vectors, run as an executable
Resources/*.lproj           the string tables; scripts/check-strings.sh keeps them complete
Resources/AppIcon.icon      the icon, as vector layers; the master is shared/brand/icon.svg
```

`shared` is a symlink on purpose. The security core is not reimplemented here: the Mac
compiles the same C++ the Android app is checked against, so `shared/crypto/VECTORS.json`
keeps binding both and a disagreement stays a failing check rather than a vault that will
not open.

The C boundary exists because that core's surface is `std::function` sinks and
`unique_ptr` factories. Swift's C++ interop handles those badly; a flat C ABI is smaller to
get right and the Swift above it reads like Swift.

## Running the checks

```
swift run CoreChecks
```

Reads `shared/crypto/VECTORS.json` and reproduces what the phone recorded — Argon2id at the
shipped profile, the null-salt HKDF, the backup key hierarchy, and the streaming cipher at
every segment boundary, compared byte for byte rather than round-tripped. It also opens a
vault listing that Tink sealed.

An executable rather than a test target, so it runs without XCTest. Same shape as the C++
tests it replaces: a line per check, non-zero exit on failure. Beyond the vectors it covers
the vault, the music library, cookies, presets (with a real render), backups in both
directions against `shared/crypto/fixtures`, pairing between two Macs over real TLS, and
the engine.

Two more, which reach outside this package:

```
scripts/check-strings.sh            # every string the app shows has a Turkish translation
scripts/check-pairing-interop.sh    # pairs with the phone's own Kotlin code, on the JVM
```

## The download engine

`shared/engine/host.py` — the same engine the Android app runs — is driven as a child
process speaking JSON, one object per line. Reimplementing yt-dlp in Swift was never on the
table; this is the boundary the Qt app used and it is already proven.

Fetch what it needs once:

```
scripts/fetch-engine.sh
```

That puts yt-dlp and curl_cffi under `.build/engine`. yt-dlp is not in the repository
because it changes weekly and the app replaces it in place anyway. curl_cffi is what lets
the engine impersonate a browser; without it most sites refuse a downloader outright.

## OpenSSL

Linked statically from Homebrew's `openssl@3`. A dynamic link would tie the finished
bundle to whatever happens to be in `/opt/homebrew`, which is not something to carry into
an application. The path is in `Package.swift`.
