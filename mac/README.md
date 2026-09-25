# Arsivinyo — macOS app

## Why there is no Xcode project yet

SwiftUI needs Xcode: its property wrappers (`@State`, `@Observable`) are macros whose
plugins ship with Xcode, not with the Command Line Tools. XCTest is the same. So the app
target and its tests wait for a full Xcode install.

Everything below the UI does not, which is why it exists first and is already verified.

## Layout

```
Sources/ArsivinyoCryptoC/   a flat C boundary over the C++ security core
  include/                  the header Swift imports
  shim.cpp                  the boundary itself
  shared -> ../../../shared/crypto      a symlink, not a copy
Sources/ArsivinyoCore/      Swift over that boundary
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

An executable rather than a test target, because XCTest needs Xcode. Same shape as the C++
tests it replaces: a line per check, non-zero exit on failure.

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
