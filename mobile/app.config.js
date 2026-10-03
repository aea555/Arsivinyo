export default ({ config }) => {
  return {
    ...config,
    name: "Arsivinyo",
    slug: "arsivinyo",
    version: "4.0.0-beta.1",
    orientation: "portrait",
    icon: "./assets/images/play_store_512.png",
    scheme: "arsivinyo",
    userInterfaceStyle: "automatic",
    newArchEnabled: true,
    ios: {
      supportsTablet: true,
      bundleIdentifier: "com.arsivinyo.local",
      infoPlist: {
        // react-native-track-player background audio (iOS is not a supported runtime
        // for the downloader, but this keeps the audio player correct if ever built).
        UIBackgroundModes: ["audio"],
      },
    },
    android: {
      // stremio:// as well: a Stremio add-on's configure page installs through such a link,
      // and Arsivinyo can take it (shared/watch/CONTRACT.md). If Stremio is installed too,
      // Android asks which app should open it. magnet: for torrents, likewise.
      scheme: ["stremio", "magnet"],
      // .torrent files opened from a browser or a file manager.
      intentFilters: [
        {
          action: "VIEW",
          category: ["DEFAULT", "BROWSABLE"],
          data: [{ scheme: "content", mimeType: "application/x-bittorrent" }],
        },
      ],
      versionCode: 40000,
      adaptiveIcon: {
        backgroundColor: "#09090B",
        foregroundImage: "./assets/images/ic_launcher_foreground.png",
        backgroundImage: "./assets/images/ic_launcher_background.png",
        monochromeImage: "./assets/images/ic_launcher_monochrome.png",
      },
      blockedPermissions: [
        "android.permission.ACCESS_MEDIA_LOCATION",
        "android.permission.READ_EXTERNAL_STORAGE",
        "android.permission.WRITE_EXTERNAL_STORAGE",
        "android.permission.READ_MEDIA_AUDIO",
        "android.permission.READ_MEDIA_IMAGES",
        "android.permission.READ_MEDIA_VIDEO",
        "android.permission.READ_MEDIA_VISUAL_USER_SELECTED",
      ],
      edgeToEdgeEnabled: true,
      predictiveBackGestureEnabled: false,
      package: "com.arsivinyo.local",
    },
    web: {
      output: "static",
      favicon: "./assets/images/favicon.png",
    },
    plugins: [
      "expo-video",
      "expo-router",
      [
        "expo-splash-screen",
        {
          image: "./assets/images/ic_launcher.png",
          imageWidth: 200,
          resizeMode: "contain",
          backgroundColor: "#000000",
          dark: {
            backgroundColor: "#000000",
          },
        },
      ],
      [
        "expo-build-properties",
        {
          ios: {
            useFrameworks: "static",
          },
          android: {
            // The private vault streams v4 playback + thumbnails from an in-process
            // loopback HTTP server (http://127.0.0.1:<port>). Android 9+ blocks cleartext
            // by default in non-debuggable (release) builds, which makes every vault video
            // fail to play and thumbnails fail to load — but only in release, since debug
            // builds already permit cleartext for Metro. This flips cleartext on for release
            // too. Low risk here: the vault is loopback-only and the downloader's network
            // goes through Python/curl-cffi (Chaquopy), which isn't governed by this policy.
            usesCleartextTraffic: true,
            // Android 8.0: the player's libmpv (shared/watch/CONTRACT.md) is built for it.
            // Faces need 9 and the music library 10 already; this only drops 7.x.
            minSdkVersion: 26,
            extraProguardRules: [
              "# Tink (vault cipher v4) uses reflection on its key managers.",
              "-keep class com.google.crypto.tink.** { *; }",
              "-keepclassmembers class com.google.crypto.tink.** { *; }",
              "-dontwarn com.google.crypto.tink.**",
              "# NanoHTTPD (vault loopback playback server)",
              "-keep class fi.iki.elonen.** { *; }",
              "-dontwarn fi.iki.elonen.**",
            ].join("\n"),
          },
        },
      ],
      "./modules/local-downloader/app.plugin.js",
      "./plugins/withReleaseSigning.js",
    ],
    experiments: {
      typedRoutes: true,
      reactCompiler: true,
    },
    extra: {
      localMode: true,
    },
  };
};
