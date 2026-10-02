// swift-tools-version: 6.0
import Foundation
import PackageDescription

// The macOS app.
//
// The security core is not reimplemented here. `Sources/ArsivinyoCryptoC/shared` is a
// symlink to `shared/crypto`, the same C++ the Android app is checked against, so
// `shared/crypto/VECTORS.json` keeps binding both and a drift stays a failing test rather
// than a vault that will not open.
//
// The player is libmpv from MPVKit 1.0.0's GPL build: Arsivinyo is GPL-3.0-or-later, and the
// GPL build is the one with every decoder (shared/watch/CONTRACT.md). Its frameworks are
// fetched by scripts/fetch-mpvkit.sh into Vendor/MPVKit, pinned by scripts/mpvkit.json, rather
// than through SwiftPM, which fetches the LGPL build as well over one slow connection each.
//
// OpenSSL is MPVKit's too, linked statically. mpv's FFmpeg carries it, and two static copies
// of one library in one binary is two definitions of every symbol, so the security core
// compiles against MPVKit's headers and links its library: one OpenSSL in the process.
// shared/crypto/VECTORS.json is what says it still agrees with the phone.

let packageRoot = URL(fileURLWithPath: #filePath).deletingLastPathComponent().path
let mpvKitFrameworks: [String] = {
    let pins = try! JSONSerialization.jsonObject(
        with: Data(contentsOf: URL(fileURLWithPath: "\(packageRoot)/scripts/mpvkit.json"))) as! [String: Any]
    return (pins["frameworks"] as! [[String: Any]]).map { $0["name"] as! String }
}()
let openSSLHeaders = ["Libcrypto", "Libssl"].map {
    "-I\(packageRoot)/Vendor/MPVKit/\($0).xcframework/macos-arm64_x86_64/\($0).framework/Headers"
}
let mpvKit = Target.Dependency.target(name: "MPVKit")

let onnxRuntime = "\(packageRoot)/../shared/faces/runtime/onnxruntime-osx-arm64-1.30.0"

let package = Package(
    name: "Arsivinyo",
    // The Mac this runs on. Nothing linked needs it to be this new any more (the OpenSSL is
    // MPVKit's, built for macOS 12); lowering it is a matter of the SwiftUI the app uses.
    platforms: [.macOS("27.0")],
    products: [
        .library(name: "ArsivinyoCore", targets: ["ArsivinyoCore"]),
        .executable(name: "ArsivinyoApp", targets: ["ArsivinyoApp"]),
    ],
    targets: mpvKitFrameworks.map { .binaryTarget(name: $0, path: "Vendor/MPVKit/\($0).xcframework") } + [
        // MPVKit's frameworks, linked with the system libraries they need, as MPVKit's own
        // package links them.
        .target(
            name: "MPVKit",
            dependencies: mpvKitFrameworks.map { .target(name: $0) },
            path: "Sources/MPVKit",
            linkerSettings: [
                .linkedFramework("AVFoundation"), .linkedFramework("AudioToolbox"), .linkedFramework("CoreAudio"),
                .linkedFramework("CoreFoundation"), .linkedFramework("CoreMedia"), .linkedFramework("CoreVideo"),
                .linkedFramework("Metal"), .linkedFramework("VideoToolbox"),
                .linkedLibrary("bz2"), .linkedLibrary("iconv"), .linkedLibrary("expat"), .linkedLibrary("resolv"),
                .linkedLibrary("xml2"), .linkedLibrary("z"), .linkedLibrary("c++"),
            ]
        ),
        // A C ABI over the C++. Swift can call C++ directly, but this core's surface is
        // std::function sinks and unique_ptr factories, which interop handles badly; a flat
        // C boundary is smaller to get right and far easier to test.
        .target(
            name: "ArsivinyoCryptoC",
            dependencies: [mpvKit],
            path: "Sources/ArsivinyoCryptoC",
            // FORMAT.md and VECTORS.json live beside the sources; they are documentation
            // and a fixture, not things to compile.
            exclude: ["shared/FORMAT.md", "shared/VECTORS.json", "shared/fixtures"],
            sources: ["shim.cpp", "shared"],
            cxxSettings: [
                .headerSearchPath("shared"),
                .unsafeFlags(openSSLHeaders + ["-std=c++20"]),
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
        // Pairing with the phone: shared/pairing's wire format by symlink, held to the same
        // VECTORS.json as the phone, plus Ed25519 and TLS over the static OpenSSL.
        .target(
            name: "ArsivinyoPairingC",
            dependencies: [mpvKit],
            path: "Sources/ArsivinyoPairingC",
            exclude: ["shared/PROTOCOL.md", "shared/VECTORS.json"],
            sources: ["shim.cpp", "shared"],
            cxxSettings: [
                .headerSearchPath("shared"),
                .unsafeFlags(openSSLHeaders + ["-std=c++20"]),
            ]
        ),
        // Faces in memes: shared/faces by symlink, the same C++ and models the phone runs, so a
        // face named on one device is recognised on the other.
        .target(
            name: "ArsivinyoFacesC",
            path: "Sources/ArsivinyoFacesC",
            exclude: ["shared/models", "shared/fixtures", "shared/test", "shared/runtime", "shared/MODELS.json",
                      "shared/VECTORS.json", "shared/fetch-runtime.sh", "shared/.gitignore"],
            sources: ["shim.cpp", "shared/faces.cpp", "shared/runtime.cpp"],
            cxxSettings: [
                .headerSearchPath("shared"),
                .unsafeFlags(["-I\(onnxRuntime)/include", "-std=c++17"]),
            ],
            linkerSettings: [
                .unsafeFlags(["-L\(onnxRuntime)/lib", "-lonnxruntime",
                              "-Xlinker", "-rpath", "-Xlinker", "\(onnxRuntime)/lib",
                              "-Xlinker", "-rpath", "-Xlinker", "@executable_path/../Frameworks"]),
            ]
        ),
        .target(name: "ArsivinyoCore",
                dependencies: ["ArsivinyoCryptoC", "ArsivinyoDSPC", "ArsivinyoPairingC", "ArsivinyoFacesC", mpvKit]),

        // The app. SwiftPM rather than an .xcodeproj: Xcode opens Package.swift directly,
        // and a command-line build means the app can be launched and looked at from a
        // terminal rather than only from the IDE.
        .executableTarget(name: "ArsivinyoApp", dependencies: ["ArsivinyoCore", mpvKit]),

        // Fills a vault in a scratch directory so the vault screen can be looked at with
        // something in it. A development tool, on the footing the Qt app's vault_seed had.
        .executableTarget(name: "VaultSeed", dependencies: ["ArsivinyoCore"]),

        // An executable rather than a .testTarget: XCTest ships with Xcode, and the core
        // has to be verifiable without it. This is also the harness the C++ tests already
        // use — print a line per check, exit non-zero if any failed.
        .executableTarget(name: "CoreChecks", dependencies: ["ArsivinyoCore", mpvKit]),
    ]
)
