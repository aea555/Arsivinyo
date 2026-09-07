const { createRunOncePlugin, withAndroidManifest, withAppBuildGradle, withGradleProperties, withProjectBuildGradle } = require('expo/config-plugins');

const CHAQUOPY_VERSION = '15.0.1';
// Python that runs ON DEVICE.
const PYTHON_VERSION = '3.11';
// Host interpreters Chaquopy may use to RUN PIP at build time, best first.
//
// This is not the same thing as PYTHON_VERSION. Chaquopy 15's bundled pip imports the
// `cgi` module, which Python removed in 3.13, so the host interpreter must be 3.12 or
// older regardless of which Python the app targets. Without a match the build fails in
// `generateDebugPythonRequirements` with "No module named 'cgi'". The target version is
// listed first because Chaquopy needs the two to agree when it inspects a source
// distribution.
const BUILD_PYTHON_CANDIDATES = [`python${PYTHON_VERSION}`, 'python3.12', 'python3.10'];
const YT_DLP_PACKAGE = 'yt-dlp';
const CURL_CFFI_VERSION = '0.14.0';
const IMPERSONATION_WHEELS_DIR = 'modules/local-downloader/android/chaquopy-wheels';
const DEFAULT_REACT_NATIVE_ARCHITECTURES = process.env.LOCAL_DOWNLOADER_ABIS || 'arm64-v8a';
const DOWNLOAD_SERVICE_NAME = 'expo.modules.localdownloader.DownloadForegroundService';
const DOWNLOAD_RECEIVER_NAME = 'expo.modules.localdownloader.DownloadActionReceiver';
const QUICK_CAPTURE_ACTIVITY_NAME = 'expo.modules.localdownloader.QuickDownloadCaptureActivity';
const PRIVATE_IMPORT_ACTIVITY_NAME = 'expo.modules.localdownloader.PrivateVaultImportActivity';
const SOUNDS_IMPORT_ACTIVITY_NAME = 'expo.modules.localdownloader.SoundsImportActivity';
const BACKUP_DOCUMENT_ACTIVITY_NAME = 'expo.modules.localdownloader.BackupDocumentActivity';
const RESTART_ACTIVITY_NAME = 'expo.modules.localdownloader.RestartActivity';

const TAGS = {
  buildscriptRepo: {
    begin: '// @generated begin local-downloader-chaquopy-buildscript-repo',
    end: '// @generated end local-downloader-chaquopy-buildscript-repo',
  },
  allprojectsRepo: {
    begin: '// @generated begin local-downloader-chaquopy-allprojects-repo',
    end: '// @generated end local-downloader-chaquopy-allprojects-repo',
  },
  buildscriptClasspath: {
    begin: '// @generated begin local-downloader-chaquopy-buildscript-classpath',
    end: '// @generated end local-downloader-chaquopy-buildscript-classpath',
  },
  pythonConfig: {
    begin: '// @generated begin local-downloader-python-config',
    end: '// @generated end local-downloader-python-config',
  },
  impersonationPip: {
    begin: '// @generated begin local-downloader-impersonation-pip',
    end: '// @generated end local-downloader-impersonation-pip',
  },
  sourceSetConfig: {
    begin: '// @generated begin local-downloader-sourceset-config',
    end: '// @generated end local-downloader-sourceset-config',
  },
  packagingConfig: {
    begin: '// @generated begin local-downloader-packaging',
    end: '// @generated end local-downloader-packaging',
  },
  abiFilterConfig: {
    begin: '// @generated begin local-downloader-abi-filter-config',
    end: '// @generated end local-downloader-abi-filter-config',
  },
  assetAbiFilterConfig: {
    begin: '// @generated begin local-downloader-asset-abi-filter-config',
    end: '// @generated end local-downloader-asset-abi-filter-config',
  },
};

function escapeRegex(value) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

function stripTaggedBlock(contents, tag) {
  const pattern = new RegExp(`\\n?${escapeRegex(tag.begin)}[\\s\\S]*?${escapeRegex(tag.end)}\\n?`, 'g');
  return contents.replace(pattern, '\n');
}

function upsertTaggedInSection(contents, sectionRegex, block, tag, sectionName) {
  const cleaned = stripTaggedBlock(contents, tag);
  let replaced = false;
  const next = cleaned.replace(sectionRegex, (_match, sectionStart, sectionBody, sectionEnd) => {
    replaced = true;
    const trimmedBody = sectionBody.replace(/\s+$/, '');
    return `${sectionStart}${trimmedBody}\n\n${block}\n${sectionEnd}`;
  });

  if (!replaced) {
    throw new Error(`[local-downloader plugin] Could not find ${sectionName} in Gradle file`);
  }

  return next;
}

function ensureChaquopyApplyPlugin(contents) {
  if (
    contents.includes('apply plugin: "com.chaquo.python"') ||
    contents.includes("apply plugin: 'com.chaquo.python'") ||
    contents.includes('id("com.chaquo.python")') ||
    contents.includes("id 'com.chaquo.python'")
  ) {
    return contents;
  }

  let replaced = contents.replace(
    /apply plugin:\s*["']com\.android\.application["']/,
    (match) => `${match}\napply plugin: "com.chaquo.python"`
  );

  if (replaced !== contents) {
    return replaced;
  }

  replaced = contents.replace(
    /(plugins\s*\{[\s\S]*?id\s*\(?["']com\.android\.application["']\)?[\s\S]*?)(\n\})/m,
    (_match, beforeEnd, closing) => `${beforeEnd}\n    id("com.chaquo.python")${closing}`
  );

  if (replaced === contents) {
    throw new Error('[local-downloader plugin] Could not find a supported android application plugin anchor in app/build.gradle');
  }

  return replaced;
}

function cleanLegacyInjectedBlocks(contents) {
  let next = contents;

  next = next.replace(/\npython\s*\{[\s\S]*?install\(["']yt-dlp(?:==\d{4}\.\d{1,2}\.\d{1,2})?["']\)[\s\S]*?\n\}\n?/g, '\n');
  next = next.replace(/\nchaquopy\s*\{[\s\S]*?install\(["']yt-dlp(?:==\d{4}\.\d{1,2}\.\d{1,2})?["']\)[\s\S]*?\n\}\n?/g, '\n');
  next = next.replace(/\nandroid\s*\{\s*\n\s*sourceSets\s*\{[\s\S]*?python\.srcDir\s+\(?["']\.\.\/\.\.\/modules\/local-downloader\/android\/src\/main\/python["']\)?[\s\S]*?\n\s*\}\s*\n\}\n?/g, '\n');

  return next;
}

function addProjectGradleChanges(contents) {
  let next = contents;

  const buildscriptRepoBlock = `${TAGS.buildscriptRepo.begin}\n        maven { url "https://chaquo.com/maven" }\n${TAGS.buildscriptRepo.end}`;
  const allprojectsRepoBlock = `${TAGS.allprojectsRepo.begin}\n    maven { url "https://chaquo.com/maven" }\n${TAGS.allprojectsRepo.end}`;
  const classpathBlock = `${TAGS.buildscriptClasspath.begin}\n    classpath("com.chaquo.python:gradle:${CHAQUOPY_VERSION}")\n${TAGS.buildscriptClasspath.end}`;

  next = upsertTaggedInSection(
    next,
    /(buildscript\s*\{[\s\S]*?repositories\s*\{)([\s\S]*?)(\n\s*\})/m,
    buildscriptRepoBlock,
    TAGS.buildscriptRepo,
    'buildscript.repositories'
  );

  next = upsertTaggedInSection(
    next,
    /(allprojects\s*\{[\s\S]*?repositories\s*\{)([\s\S]*?)(\n\s*\})/m,
    allprojectsRepoBlock,
    TAGS.allprojectsRepo,
    'allprojects.repositories'
  );

  next = upsertTaggedInSection(
    next,
    /(buildscript\s*\{[\s\S]*?dependencies\s*\{)([\s\S]*?)(\n\s*\})/m,
    classpathBlock,
    TAGS.buildscriptClasspath,
    'buildscript.dependencies'
  );

  return next;
}

function addAppGradleChanges(contents) {
  let next = cleanLegacyInjectedBlocks(contents);

  next = ensureChaquopyApplyPlugin(next);

  const wheelsDirNormalized = IMPERSONATION_WHEELS_DIR.replace(/\\/g, '/');
  const impersonationPipBlock = `${TAGS.impersonationPip.begin}\n            def impersonationWheelsDir = file("../../${wheelsDirNormalized}")\n            if (!impersonationWheelsDir.exists()) {\n                println("[local-downloader] Impersonation wheels not found at ${wheelsDirNormalized}; continuing without curl-cffi")\n            } else {\n                def archProp = (findProperty("reactNativeArchitectures") ?: "${DEFAULT_REACT_NATIVE_ARCHITECTURES}").toString()\n                def requiredAbis = archProp.split(",").collect { it.trim() }.findAll { !it.isEmpty() }\n                def wheelFiles = impersonationWheelsDir.listFiles()?.collect { it.name } ?: []\n                def missingAbis = requiredAbis.findAll { abi ->\n                    def abiToken = abi.replace("-", "_")\n                    !wheelFiles.any { name -> name ==~ /curl_cffi-.*android.*\${abiToken}.*\\.whl/ }\n                }\n                if (missingAbis.isEmpty()) {\n                    options("--find-links", impersonationWheelsDir.absolutePath)\n                    install("curl-cffi==${CURL_CFFI_VERSION}")\n                } else {\n                    println("[local-downloader] Skipping curl-cffi install: missing Android wheel(s) for ABIs: \${missingAbis.join(', ')} at ${wheelsDirNormalized}")\n                }\n            }\n${TAGS.impersonationPip.end}`;
  const buildPythonCandidatesGroovy = BUILD_PYTHON_CANDIDATES.map((c) => `"${c}"`).join(', ');
  const buildPythonBlock = `        def buildPythonOverride = System.getenv("LOCAL_DOWNLOADER_BUILD_PYTHON")\n        def buildPythonCandidates = (buildPythonOverride != null && !buildPythonOverride.trim().isEmpty())\n            ? [buildPythonOverride.trim()]\n            : [${buildPythonCandidatesGroovy}]\n        def resolvedBuildPython = buildPythonCandidates.find { candidate ->\n            try {\n                new ProcessBuilder(candidate, "--version").redirectErrorStream(true).start().waitFor() == 0\n            } catch (Exception ignored) {\n                false\n            }\n        }\n        if (resolvedBuildPython != null) {\n            println("[local-downloader] Chaquopy buildPython: " + resolvedBuildPython)\n            buildPython(resolvedBuildPython)\n        } else {\n            println("[local-downloader] Chaquopy buildPython: none of ${BUILD_PYTHON_CANDIDATES.join(', ')} found on PATH; using the default interpreter. If the build fails in generateDebugPythonRequirements with a missing 'cgi' module, install python${PYTHON_VERSION} or set LOCAL_DOWNLOADER_BUILD_PYTHON.")\n        }`;
  const pythonBlock = `${TAGS.pythonConfig.begin}\nchaquopy {\n    defaultConfig {\n        version = "${PYTHON_VERSION}"\n${buildPythonBlock}\n        pip {\n            install("${YT_DLP_PACKAGE}")\n            install("tenacity==9.0.0")\n${impersonationPipBlock}\n        }\n    }\n    sourceSets {\n        getByName("main") {\n            srcDir("../../../shared/engine")\n        }\n    }\n}\n${TAGS.pythonConfig.end}`;

  const sourceSetBlock = `${TAGS.sourceSetConfig.begin}\nandroid {\n    sourceSets {\n        main {\n            assets.srcDirs += ["../../modules/local-downloader/android/src/main/assets"]\n        }\n    }\n}\n${TAGS.sourceSetConfig.end}`;
  // BouncyCastle (Argon2id for backups) and jspecify both ship a multi-release OSGi
  // manifest at the same path, which AGP's Java-resource merge refuses to resolve on its
  // own. Neither file is used at runtime; excluding the path is the documented fix.
  const packagingBlock = `${TAGS.packagingConfig.begin}\nandroid {\n    packaging {\n        resources {\n            excludes += [\n                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",\n                "META-INF/DEPENDENCIES",\n                "META-INF/LICENSE.md",\n                "META-INF/NOTICE.md",\n            ]\n        }\n    }\n}\n${TAGS.packagingConfig.end}`;

  const abiFilterBlock = `${TAGS.abiFilterConfig.begin}\nandroid {\n    defaultConfig {\n        ndk {\n            def localDownloaderAbis = (findProperty("reactNativeArchitectures") ?: "${DEFAULT_REACT_NATIVE_ARCHITECTURES}").toString().split(",").collect { it.trim() }.findAll { !it.isEmpty() }\n            abiFilters(*localDownloaderAbis)\n        }\n    }\n}\n${TAGS.abiFilterConfig.end}`;

  // The bundled ffmpeg/ffprobe fallback ships one pair of executables per ABI as assets,
  // roughly 33 MB uncompressed each. They are only read when the jniLibs copy will not
  // load, so a pair for an ABI this build cannot run on is dead weight in every APK —
  // an arm64 phone build was carrying the whole x86_64 pair. Reads the same Gradle
  // property as the abiFilters above, so opting an ABI in with LOCAL_DOWNLOADER_ABIS
  // brings its assets back and the two cannot drift.
  //
  // Applied at the app rather than in the library module: assets are merged from the
  // library and packaged here, so this is where leaving them out actually takes effect.
  const assetAbiFilterBlock = `${TAGS.assetAbiFilterConfig.begin}\nandroid {\n    androidResources {\n        def shippedAbis = (findProperty("reactNativeArchitectures") ?: "${DEFAULT_REACT_NATIVE_ARCHITECTURES}").toString().split(",").collect { it.trim() }.findAll { !it.isEmpty() }\n        def ffmpegAssetsDir = file("../../modules/local-downloader/android/src/main/assets/ffmpeg")\n        def bundledAbis = ffmpegAssetsDir.exists() ? (ffmpegAssetsDir.listFiles() ?: []).findAll { it.directory }.collect { it.name } : []\n        bundledAbis.findAll { !shippedAbis.contains(it) }.each { unusedAbi ->\n            println("[local-downloader] Leaving ffmpeg assets for unused ABI out of the package: " + unusedAbi)\n            ignoreAssetsPatterns.add("!" + unusedAbi)\n        }\n    }\n}\n${TAGS.assetAbiFilterConfig.end}`;

  next = stripTaggedBlock(next, TAGS.pythonConfig);
  next = stripTaggedBlock(next, TAGS.sourceSetConfig);
  next = stripTaggedBlock(next, TAGS.abiFilterConfig);
  next = stripTaggedBlock(next, TAGS.assetAbiFilterConfig);
  next = stripTaggedBlock(next, TAGS.packagingConfig);

  next = `${next.trimEnd()}\n\n${pythonBlock}\n\n${sourceSetBlock}\n\n${abiFilterBlock}\n\n${assetAbiFilterBlock}\n\n${packagingBlock}\n`;

  return next;
}

function ensureManifestPermission(manifest, name) {
  const existing = manifest['uses-permission'] || [];
  if (!existing.some((item) => item.$?.['android:name'] === name)) {
    existing.push({ $: { 'android:name': name } });
  }
  manifest['uses-permission'] = existing;
}

function ensureApplicationEntry(mainApplication, key, androidName, extra = {}) {
  const existing = mainApplication[key] || [];
  const found = existing.find((item) => item.$?.['android:name'] === androidName);
  if (found) {
    // Replace the attribute set rather than merge: these entries are entirely owned
    // by this plugin, so a removed attribute (e.g. android:noHistory) must actually
    // disappear on the next prebuild instead of lingering from a previous run.
    found.$ = { 'android:name': androidName, ...extra };
  } else {
    existing.push({
      $: {
        'android:name': androidName,
        ...extra,
      },
    });
  }
  mainApplication[key] = existing;
}

function addAndroidManifestChanges(config) {
  return withAndroidManifest(config, (modConfig) => {
    const manifest = modConfig.modResults.manifest;
    ensureManifestPermission(manifest, 'android.permission.FOREGROUND_SERVICE');
    ensureManifestPermission(manifest, 'android.permission.FOREGROUND_SERVICE_DATA_SYNC');
    ensureManifestPermission(manifest, 'android.permission.POST_NOTIFICATIONS');
    ensureManifestPermission(manifest, 'android.permission.USE_BIOMETRIC');

    const mainApplication = manifest.application?.[0];
    if (!mainApplication) {
      throw new Error('[local-downloader plugin] Could not find Android application block in AndroidManifest.xml');
    }
    if (mainApplication.$ && Object.prototype.hasOwnProperty.call(mainApplication.$, 'android:requestLegacyExternalStorage')) {
      delete mainApplication.$['android:requestLegacyExternalStorage'];
    }

    ensureApplicationEntry(mainApplication, 'service', DOWNLOAD_SERVICE_NAME, {
      'android:exported': 'false',
      'android:foregroundServiceType': 'dataSync',
      'android:stopWithTask': 'false',
    });

    ensureApplicationEntry(mainApplication, 'receiver', DOWNLOAD_RECEIVER_NAME, {
      'android:exported': 'false',
    });

    ensureApplicationEntry(mainApplication, 'activity', QUICK_CAPTURE_ACTIVITY_NAME, {
      'android:exported': 'false',
      'android:excludeFromRecents': 'true',
      'android:noHistory': 'true',
      'android:launchMode': 'singleTask',
      'android:taskAffinity': '',
      'android:theme': '@android:style/Theme.DeviceDefault.Dialog.NoActionBar',
    });

    // NOTE: no `android:noHistory` here. These activities open a full-screen SAF
    // picker on top of themselves; with noHistory the OS finishes them the instant
    // the picker appears (they're "no longer visible"), firing their cancel path and
    // dropping the real result. They finish themselves in their result callback, so
    // noHistory is unnecessary as well as harmful.
    ensureApplicationEntry(mainApplication, 'activity', PRIVATE_IMPORT_ACTIVITY_NAME, {
      'android:exported': 'false',
      'android:excludeFromRecents': 'true',
      'android:launchMode': 'singleTask',
      'android:taskAffinity': '',
      'android:theme': '@android:style/Theme.Translucent.NoTitleBar',
    });

    ensureApplicationEntry(mainApplication, 'activity', SOUNDS_IMPORT_ACTIVITY_NAME, {
      'android:exported': 'false',
      'android:excludeFromRecents': 'true',
      'android:launchMode': 'singleTask',
      'android:taskAffinity': '',
      'android:theme': '@android:style/Theme.Translucent.NoTitleBar',
    });

    // Backup export/import document picker. Same attribute set as the other SAF pickers —
    // in particular NO android:noHistory, which would finish this activity the moment the
    // full-screen picker covers it and cancel the pick.
    ensureApplicationEntry(mainApplication, 'activity', BACKUP_DOCUMENT_ACTIVITY_NAME, {
      'android:exported': 'false',
      'android:excludeFromRecents': 'true',
      'android:launchMode': 'singleTask',
      'android:taskAffinity': '',
      'android:theme': '@android:style/Theme.Translucent.NoTitleBar',
    });

    // Restarting the app after a yt-dlp update. It runs in its own process on purpose:
    // whatever kills the main one has to still be alive afterwards to start it again.
    ensureApplicationEntry(mainApplication, 'activity', RESTART_ACTIVITY_NAME, {
      'android:exported': 'false',
      'android:excludeFromRecents': 'true',
      'android:process': ':restart',
      'android:launchMode': 'singleInstance',
      'android:taskAffinity': '',
      'android:theme': '@android:style/Theme.Translucent.NoTitleBar',
    });

    return modConfig;
  });
}

const withLocalDownloader = (config) => {
  config = addAndroidManifestChanges(config);

  config = withGradleProperties(config, (config) => {
    const upsertProperty = (key, value) => {
      const existing = config.modResults.find((item) => item.type === 'property' && item.key === key);
      if (existing) {
        existing.value = value;
      } else {
        config.modResults.push({
          type: 'property',
          key,
          value,
        });
      }
    };

    upsertProperty('expo.useLegacyPackaging', 'true');
    // Default to arm64 for reliable impersonation builds; x86_64 can be enabled explicitly.
    upsertProperty('reactNativeArchitectures', DEFAULT_REACT_NATIVE_ARCHITECTURES);
    return config;
  });

  config = withProjectBuildGradle(config, (config) => {
    config.modResults.contents = addProjectGradleChanges(config.modResults.contents);
    return config;
  });

  config = withAppBuildGradle(config, (config) => {
    config.modResults.contents = addAppGradleChanges(config.modResults.contents);
    return config;
  });

  return config;
};

module.exports = createRunOncePlugin(withLocalDownloader, 'local-downloader', '1.0.0');
