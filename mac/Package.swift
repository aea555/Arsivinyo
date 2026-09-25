// swift-tools-version: 6.0
import PackageDescription

// The macOS app.
//
// The security core is not reimplemented here. `Sources/ArsivinyoCryptoC/shared` is a
// symlink to `shared/crypto`, the same C++ the Android app is checked against, so
// `shared/crypto/VECTORS.json` keeps binding both and a drift stays a failing test rather
// than a vault that will not open.
//
// OpenSSL is linked statically. A dynamic link would tie the finished .app to whatever
// Homebrew happens to have in /opt/homebrew, which is not something to carry into an
// application bundle.

let openSSLRoot = "/opt/homebrew/opt/openssl@3"

let package = Package(
    name: "Arsivinyo",
    // Matches the OpenSSL that is linked. Homebrew builds its bottles for the running
    // macOS, so targeting anything older produces a linker warning per object file — about
    // a thousand of them, which would bury a real one. If this ever has to run on an older
    // Mac, the answer is a static OpenSSL built for that target, not a lower number here.
    platforms: [.macOS("27.0")],
    products: [
        .library(name: "ArsivinyoCore", targets: ["ArsivinyoCore"]),
        .executable(name: "ArsivinyoApp", targets: ["ArsivinyoApp"]),
    ],
    targets: [
        // A C ABI over the C++. Swift can call C++ directly, but this core's surface is
        // std::function sinks and unique_ptr factories, which interop handles badly; a flat
        // C boundary is smaller to get right and far easier to test.
        .target(
            name: "ArsivinyoCryptoC",
            path: "Sources/ArsivinyoCryptoC",
            // FORMAT.md and VECTORS.json live beside the sources; they are documentation
            // and a fixture, not things to compile.
            exclude: ["shared/FORMAT.md", "shared/VECTORS.json"],
            sources: ["shim.cpp", "shared"],
            cxxSettings: [
                .headerSearchPath("shared"),
                .unsafeFlags(["-I\(openSSLRoot)/include", "-std=c++20"]),
            ],
            linkerSettings: [
                .unsafeFlags(["\(openSSLRoot)/lib/libcrypto.a"]),
            ]
        ),
        // The audio presets, over shared/dsp by symlink, as the crypto is over shared/crypto:
        // the phone renders with this same C++, so a preset sounds the same on both.
        .target(
            name: "ArsivinyoDSPC",
            path: "Sources/ArsivinyoDSPC",
            exclude: ["shared/test"],
            sources: ["shim.cpp", "shared"],
            cxxSettings: [
                .headerSearchPath("shared"),
                .unsafeFlags(["-std=c++17"]),
            ]
        ),
        .target(name: "ArsivinyoCore", dependencies: ["ArsivinyoCryptoC", "ArsivinyoDSPC"]),

        // The app. SwiftPM rather than an .xcodeproj: Xcode opens Package.swift directly,
        // and a command-line build means the app can be launched and looked at from a
        // terminal rather than only from the IDE.
        .executableTarget(name: "ArsivinyoApp", dependencies: ["ArsivinyoCore"]),

        // Fills a vault in a scratch directory so the vault screen can be looked at with
        // something in it. A development tool, on the footing the Qt app's vault_seed had.
        .executableTarget(name: "VaultSeed", dependencies: ["ArsivinyoCore"]),

        // An executable rather than a .testTarget: XCTest ships with Xcode, and the core
        // has to be verifiable without it. This is also the harness the C++ tests already
        // use — print a line per check, exit non-zero if any failed.
        .executableTarget(name: "CoreChecks", dependencies: ["ArsivinyoCore"]),
    ]
)
