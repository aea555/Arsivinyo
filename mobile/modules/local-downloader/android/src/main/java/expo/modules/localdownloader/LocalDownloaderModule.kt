package expo.modules.localdownloader

import android.Manifest
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.app.KeyguardManager
import android.net.Uri
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.os.Looper
import android.os.StatFs
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import expo.modules.localdownloader.audio.AudioPresetRenderer
import expo.modules.localdownloader.pairing.PairingCoordinator
import expo.modules.localdownloader.sounds.SoundsStore
import expo.modules.localdownloader.vault.ThumbnailGenerator
import expo.modules.localdownloader.vault.VaultLoopbackProvider
import expo.modules.localdownloader.vault.VaultLoopbackServer
import expo.modules.localdownloader.vault.VaultMigrator
import expo.modules.localdownloader.vault.VaultThumbnailResource
import expo.modules.localdownloader.vault.VaultVideoResource
import expo.modules.localdownloader.vault.VaultVideoSession
import expo.modules.localdownloader.scheduler.DownloadStages
import expo.modules.localdownloader.scheduler.PriorityGate
import expo.modules.localdownloader.scheduler.withPermit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import android.provider.DocumentsContract
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import expo.modules.localdownloader.backup.BackupContainer
import expo.modules.localdownloader.backup.BackupCrypto
import expo.modules.localdownloader.backup.BackupFormat
import expo.modules.localdownloader.backup.BackupPorts
import expo.modules.localdownloader.backup.BackupSecretException
import expo.modules.localdownloader.backup.BackupSections
import expo.modules.localdownloader.vault.VaultCipherV4
import expo.modules.localdownloader.vault.VaultAuthPolicy
import expo.modules.localdownloader.vault.VaultIndexCodec
import expo.modules.localdownloader.vault.VaultKeyBox
import expo.modules.localdownloader.vault.VaultKeystoreKeys
import expo.modules.localdownloader.vault.VaultSession
import java.security.KeyStore
import java.security.SecureRandom
import java.time.Instant
import java.util.ArrayDeque
import java.util.LinkedHashMap
import java.util.UUID
import expo.modules.kotlin.Promise
import expo.modules.localdownloader.memes.Face
import expo.modules.localdownloader.memes.FaceScanner
import expo.modules.localdownloader.memes.FacesNative
import expo.modules.localdownloader.memes.KeystoreSealer
import expo.modules.localdownloader.memes.MemeCollection
import expo.modules.localdownloader.torrent.TorrentService
import expo.modules.localdownloader.watch.Addons
import expo.modules.localdownloader.watch.MpvPlayerView
import expo.modules.localdownloader.watch.WatchLibrary
import expo.modules.localdownloader.watch.WatchService
import expo.modules.localdownloader.memes.ScannedFace
import expo.modules.localdownloader.pairing.ItemSource
import expo.modules.localdownloader.memes.MemeStore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.max

data class TaskState(
  var taskId: String,
  var status: String,
  var url: String? = null,
  var state: String? = null,
  var filename: String? = null,
  var filePath: String? = null,
  var isPrivate: Boolean? = null,
  var privateVideoId: String? = null,
  var sizeMb: Double? = null,
  var progressPercent: Double? = null,
  var speedBytesPerSec: Double? = null,
  var errorCode: String? = null,
  var errorMessage: String? = null,
  var normalizedUrl: String? = null,
  var preflightWarning: Map<String, Any?>? = null,
  var preflightStrategy: String? = null,
  var downloadStrategy: String? = null,
  var extractorKey: String? = null,
  var formatSelector: String? = null,
  var attemptTrace: List<Map<String, Any?>>? = null,
  var toolOutput: String? = null,
  var preflightBudgetSec: Int? = null,
  var preflightElapsedMs: Long? = null,
  var preflightAttemptLimit: Int? = null,
  var staticMediaCandidateCount: Int? = null,
  var estimatedSizeMb: Double? = null,
  var timestampNormalized: Boolean? = null,
  var warningCode: String? = null
)

data class FfmpegInfo(
  val path: String? = null,
  val ffprobePath: String? = null,
  val location: String? = null,
  val abi: String? = null,
  val runtimeSource: String = "none",
  val nativeLibraryDir: String? = null,
  val nativeLibraryEntries: List<String> = emptyList(),
  val exists: Boolean = false,
  val ffprobeExists: Boolean = false,
  val executable: Boolean = false,
  val ffprobeExecutable: Boolean = false,
  val version: String? = null,
  val ffprobeVersion: String? = null,
  val ffmpegProbeError: String? = null,
  val ffprobeProbeError: String? = null,
  val mergeCapable: Boolean = false
)

data class BinaryProbeResult(
  val runnable: Boolean,
  val version: String? = null,
  val error: String? = null
)

data class PreflightPythonInput(
  val url: String,
  val cookiesDir: String,
  val cookieProfile: String?,
  val maxFileSizeMb: Int,
  val ffmpegPath: String?,
  val cookieFilePath: String?,
  val forceNoCookie: Boolean = false,
  val mergeCapable: Boolean = true,
  val userAgent: String,
  val debugLogging: Boolean = false
)

// Audio download formats. Must stay in sync with SUPPORTED_AUDIO_FORMATS in
// local_downloader.py — Python re-validates what it receives, but a mismatch here
// would silently downgrade the user's choice before it ever gets there.
// File scope rather than the companion object: DownloadPythonInput below is a
// top-level class and uses DEFAULT_AUDIO_FORMAT as a default argument.
const val AUDIO_FORMAT_FLAC = "flac"
const val AUDIO_FORMAT_M4A = "m4a"
const val DEFAULT_AUDIO_FORMAT = AUDIO_FORMAT_FLAC
val SUPPORTED_AUDIO_FORMATS = setOf(AUDIO_FORMAT_FLAC, AUDIO_FORMAT_M4A)

data class DownloadPythonInput(
  val url: String,
  val outputDir: String,
  val cookiesDir: String,
  val cookieProfile: String?,
  val maxFileSizeMb: Int,
  val cancelFlagPath: String?,
  val progressFilePath: String?,
  val ffmpegPath: String?,
  val cookieFilePath: String?,
  val forceNoCookie: Boolean = false,
  val mergeCapable: Boolean = true,
  val audioOnly: Boolean = false,
  val audioFormat: String = DEFAULT_AUDIO_FORMAT,
  val userAgent: String,
  val debugLogging: Boolean = false
)

data class CustomDomainMatch(
  val urlHost: String,
  val matchedDomain: String? = null,
  val profileName: String? = null,
)

data class PendingQuickRequest(
  val url: String,
  val captureMode: String,
  val visibility: String,
  val createdAtMs: Long
)

data class PrivateVideoEntry(
  val id: String,
  val title: String,
  val createdAt: Long,
  val updatedAt: Long,
  val sourceUrlHash: String,
  val mimeType: String,
  val durationSec: Double? = null,
  val sizeBytesEncrypted: Long,
  val cipherVersion: String,
  val encFileName: String,
  val containerExt: String? = null,
  val thumbFileName: String? = null,
  val thumbWidth: Int? = null,
  val thumbHeight: Int? = null,
  val migrationFailed: Boolean = false,
  val migrationFailedCode: String? = null,
  val migrationFailedDetail: String? = null,
  val tags: List<String> = emptyList(),
  val folderId: String? = null,
)

data class TagDefinition(
  val id: String,
  val name: String,
  val color: String,
  val createdAt: Long,
)

data class FolderDefinition(
  val id: String,
  val name: String,
  val createdAt: Long,
)

data class YtDlpReleaseAsset(
  val version: String,
  val filename: String,
  val url: String,
  val sha256: String,
  val sizeBytes: Long
)

class LocalDownloaderModule : Module() {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val tasks = ConcurrentHashMap<String, TaskState>()
  private val cancelFlags = ConcurrentHashMap<String, File>()
  /** renderId -> cancel flag file for an in-flight preset render batch. */
  private val presetRenderCancelFlags = ConcurrentHashMap<String, File>()

  /** renderId of the batch currently rendering, or null. Holds the service foreground. */
  @Volatile
  private var presetRenderActive: String? = null

  /**
   * The export or restore in flight, if any. Read by the background-state derivation so a
   * backup pins the foreground service exactly like a download or a render batch does —
   * without it, Android is free to kill the process partway through a 20 GB job.
   */
  private var backupJobActive: JSONObject? = null

  /**
   * The outcome of the last backup job, kept so a screen that was closed while the job ran
   * can still show what happened. The promise from the original call resolves into a
   * component that no longer exists.
   */
  private var backupJobLastOutcome: JSONObject? = null

  /**
   * When the backup notification was last posted, and at what whole percent.
   *
   * Android silently drops notification updates past roughly ten per second. A restore of a
   * few dozen small items outruns that easily, and the update that gets dropped is the one
   * that matters: the final "idle" post arriving straight after the burst. The notification
   * then keeps the last frame that got through — which is why it always looked stuck on the
   * final item. Progress posts are throttled so the burst never forms; the in-app event is
   * not, because it does not go through the notification manager.
   */
  private var backupNotificationLastPostAt = 0L
  private var backupNotificationLastPercent = -1
  private val ignoredTaskResults = ConcurrentHashMap.newKeySet<String>()
  private val lastErrors = ArrayDeque<String>()
  private val failureLogLock = Any()
  private val customCookieIndexLock = Any()
  private val queueLock = Any()
  private val privateVaultLock = Any()
  private val privateVaultIoLock = Any()
  private val soundsStore: SoundsStore by lazy {
    SoundsStore(requireNotNull(appContext.reactContext).applicationContext)
  }

  /**
   * Created on first use, not at startup. An install that never opens the pairing screen
   * should not generate an identity key or put a listening socket on the network.
   */
  private var pairingCoordinator: PairingCoordinator? = null

  private fun pairing(): PairingCoordinator {
    pairingCoordinator?.let { return it }
    val context = requireNotNull(appContext.reactContext).applicationContext
    val created = PairingCoordinator(
      context = context,
      store = soundsStore,
      onChanged = { runCatching { sendEvent("pairingStateChanged", pairingStateMap()) } },
      onDownloadRequested = { url, mediaKind ->
        if (pairingAutoDownloadLinks) {
          // The user opted in: a link from a paired device goes straight into the queue,
          // as the kind the other device asked for.
          runCatching { startQuickDownloadWithUrl(url, "peer", mediaKindOverride = mediaKind) }
        } else {
          // Otherwise surfaced only. The user decides whether to download what a peer sent.
          lastPeerUrl = url
          lastPeerMediaKind = mediaKind
          runCatching { sendEvent("pairingStateChanged", pairingStateMap()) }
        }
      },
      onMemeReceived = { file, meme -> receiveMeme(file, meme) },
    )
    pairingCoordinator = created
    return created
  }

  private val pairingPrefs by lazy {
    requireNotNull(appContext.reactContext).applicationContext
      .getSharedPreferences("pairing", android.content.Context.MODE_PRIVATE)
  }

  /** Opt-in: download links from paired devices without asking. Off unless turned on. */
  private var pairingAutoDownloadLinks: Boolean
    get() = pairingPrefs.getBoolean("autoDownloadLinks", false)
    set(value) { pairingPrefs.edit().putBoolean("autoDownloadLinks", value).apply() }

  // ---- memes -------------------------------------------------------------------

  private val memes: MemeCollection by lazy {
    MemeCollection(requireNotNull(appContext.reactContext).applicationContext, memePrivateHalf)
  }

  /**
   * Where a download saved by the screen came from, held by its file path until the screen
   * saves it. The engine reports the source with the result; the save comes later, from TS.
   */
  private val pendingMemeSources = ConcurrentHashMap<String, MemeStore.Source>()

  private val memePrefs by lazy {
    requireNotNull(appContext.reactContext).applicationContext
      .getSharedPreferences("memes", android.content.Context.MODE_PRIVATE)
  }

  /** The quick prompt after a meme download. On unless turned off. */
  private var memeAskForTags: Boolean
    get() = memePrefs.getBoolean("askForTags", true)
    set(value) { memePrefs.edit().putBoolean("askForTags", value).apply() }

  /**
   * The private memes and their labels, under a key derived from the vault's own, so they
   * are readable exactly when the vault is.
   */
  private val memePrivateHalf = object : MemeStore.PrivateHalf {
    private fun file() = File(privateVaultRoot(create = true), MEME_PRIVATE_INDEX_FILENAME)
    private fun key(dek: ByteArray): ByteArray =
      com.google.crypto.tink.subtle.Hkdf.computeHkdf("HMACSHA256", dek, null, MEME_PRIVATE_INFO, 32)

    override fun read(): ByteArray? {
      if (!PRIVATE_VAULT_FEATURE_FLAG) return null
      val dek = vaultSession.peekDek() ?: return null
      val sealed = file().takeIf { it.isFile } ?: return ByteArray(0)
      return VaultIndexCodec.open(key(dek), sealed.readBytes()).toByteArray(Charsets.UTF_8)
    }

    override fun write(plaintext: ByteArray?) {
      if (plaintext == null) {
        file().delete()
        return
      }
      val dek = requireVaultDek(VaultAuthPolicy.OP_TAG)
      atomicWriteBytes(file(), VaultIndexCodec.seal(key(dek), String(plaintext, Charsets.UTF_8)))
    }

    override fun isOpen(): Boolean = PRIVATE_VAULT_FEATURE_FLAG && vaultSession.peekDek() != null

    override fun exists(): Boolean =
      PRIVATE_VAULT_FEATURE_FLAG && File(privateVaultRoot(create = false), MEME_PRIVATE_INDEX_FILENAME).isFile
  }

  private fun memeSourceOf(result: JSONObject, url: String): MemeStore.Source {
    val decoded = MemeStore.decodeSource(result.optJSONObject("source")) ?: MemeStore.Source()
    return decoded.copy(url = decoded.url ?: url.takeIf { it.isNotBlank() })
  }

  private fun memesChanged() {
    runCatching { sendEvent("memesChanged", mapOf<String, Any?>()) }
  }

  /** A download or an import has been saved to MediaStore: it joins the collection. */
  private fun adoptMeme(uri: String, mimeType: String, source: MemeStore.Source?, ask: Boolean) {
    runCatching {
      val item = memes.adopt(uri, mimeType, source) ?: return
      if (ask && memeAskForTags && item.isUntagged) memes.prompt(item)
      memesChanged()
      scanFaces()
    }.onFailure { addError("MEME_ADD_FAILED: ${it.javaClass.simpleName}") }
  }

  /** A private download: its vault entry joins the collection as a private meme. */
  private fun adoptPrivateMeme(vaultId: String, plaintext: File, mimeType: String, source: MemeStore.Source?) {
    val kind = MemeCollection.kindOf(mimeType) ?: return
    runCatching {
      val sha = MemeCollection.hashOf { plaintext.inputStream() } ?: return
      val item = memes.store.registerPrivate(vaultId, kind, sha, source)
      if (memeAskForTags && item.isUntagged) memes.prompt(item)
      memesChanged()
    }.onFailure { addError("MEME_ADD_FAILED: ${it.javaClass.simpleName}") }
  }

  private fun memeTagMap(tag: MemeStore.Tag) =
    mapOf("id" to tag.id, "name" to tag.name, "facets" to tag.facets.map { it.wire })

  private fun memeItemMap(item: MemeStore.Item): Map<String, Any?> = mapOf(
    "id" to item.id,
    "kind" to item.kind,
    "isPrivate" to item.isPrivate,
    "uri" to item.uri,
    "vaultId" to item.vaultId,
    "tags" to item.tags,
    "people" to item.people,
    "addedAt" to item.addedAt,
    "taggedAt" to item.taggedAt,
    "faces" to item.faces.map(::faceMap),
    "source" to item.source?.let {
      mapOf(
        "platform" to it.platform, "account" to it.account, "accountName" to it.accountName,
        "caption" to it.caption, "url" to it.url, "postedAt" to it.postedAt, "savedAt" to it.savedAt,
      )
    },
  )

  private fun listMemesInternal(): Map<String, Any?> {
    val store = memes.store
    if (System.currentTimeMillis() - memesReconciledAt > 60_000) {
      memesReconciledAt = System.currentTimeMillis()
      memeMedia.execute { if (runCatching { memes.reconcile() }.getOrDefault(0) > 0) memesChanged() }
    }
    // Private memes are not reconciled here: reading the vault's listing counts as using the
    // vault, and browsing memes must not hold it open. A vault delete drops its meme instead.
    val unlocked = store.privateReadable()
    if (unlocked) runCatching { store.tidyPrivate() }
    val snapshot = store.snapshot()
    snapshot.items.filter { it.isPrivate }.forEach {
      memes.forgetThumbnail(it.id)
      memes.forgetFaceCrops(it)
    }
    scanFaces()
    return mapOf(
      "items" to snapshot.items.map(::memeItemMap),
      "tags" to snapshot.tags.map(::memeTagMap),
      "people" to snapshot.people.map { mapOf("id" to it.id, "name" to it.name, "known" to it.signatures.isNotEmpty()) },
      "facesSupported" to facesSupported(),
      "facesRemaining" to facesRemaining,
      "vaultUnlocked" to unlocked,
      "hasPrivate" to store.hasPrivate(),
      "askForTags" to memeAskForTags,
    )
  }

  private fun findMeme(id: String): MemeStore.Item? = memes.store.snapshot().items.firstOrNull { it.id == id }

  /** Public memes into the vault, or private ones out of it. The labels move with them. */
  private fun setMemesPrivateInternal(ids: List<String>, makePrivate: Boolean): Map<String, Any?> {
    if (!memes.store.privateReadable()) return mapOf("success" to false, "code" to "PRIVATE_VAULT_LOCKED")
    var failed = 0
    for (id in ids) {
      val item = findMeme(id) ?: continue
      runCatching {
        if (makePrivate && !item.isPrivate) {
          val uri = item.uri ?: throw IllegalStateException("MEME_NOT_FOUND")
          val name = memes.displayName(item)
          val mime = requireNotNull(appContext.reactContext).contentResolver.getType(Uri.parse(uri))
            ?: guessMimeType(name)
          // Copied out of MediaStore only as far as the vault's own import, then removed.
          val temp = File(privateImportCacheDir(create = true), "${UUID.randomUUID()}")
          try {
            memes.open(item)?.use { input -> temp.outputStream().use { input.copyTo(it, PRIVATE_STREAM_BUFFER_BYTES) } }
              ?: throw IllegalStateException("MEME_NOT_FOUND")
            val entry = importFileToPrivateVault(temp.absolutePath, name, uri, mime)
            memes.store.movedToVault(item.id, entry.id)
            memes.forgetThumbnail(item.id)
            memes.forgetFaceCrops(item)
            requireNotNull(appContext.reactContext).contentResolver.delete(Uri.parse(uri), null, null)
          } finally {
            temp.delete()
          }
        } else if (!makePrivate && item.isPrivate) {
          val vaultId = item.vaultId ?: throw IllegalStateException("MEME_NOT_FOUND")
          val copied = copyPrivateVideoToPublicGalleryInternal(vaultId)
          val uri = copied["uri"] as? String ?: throw IllegalStateException(copied["code"] as? String ?: "MEME_MOVE_FAILED")
          memes.store.movedOutOfVault(item.id, uri)
          deletePrivateVideoInternal(vaultId)
        }
      }.onFailure {
        failed++
        addError("MEME_MOVE_FAILED: ${it.javaClass.simpleName}")
      }
    }
    memesChanged()
    return mapOf("success" to (failed == 0), "failed" to failed)
  }

  private fun removeMemesInternal(ids: List<String>): Map<String, Any?> {
    var failed = 0
    for (id in ids) {
      val item = findMeme(id) ?: continue
      runCatching {
        if (item.isPrivate) {
          item.vaultId?.let { deletePrivateVideoInternal(it) }
          memes.store.remove(item.id)
        } else {
          memes.delete(item)
        }
        memes.dismissPrompt(item.id)
      }.onFailure {
        failed++
        addError("MEME_REMOVE_FAILED: ${it.javaClass.simpleName}")
      }
    }
    memesChanged()
    return mapOf("success" to (failed == 0), "failed" to failed)
  }

  /** The pick in progress: its launcher and the promise waiting on it. Main thread only. */
  private var memePickLauncher: androidx.activity.result.ActivityResultLauncher<androidx.activity.result.PickVisualMediaRequest>? = null
  private var memePickPromise: Promise? = null

  /**
   * The system photo picker, launched from the app's own activity rather than a separate
   * one. A separate activity put the picker in a task of its own, and backing out of it
   * brought the app forward with the picker still open behind it: no result ever came, and
   * the import waited on one. From the app's activity, every way out delivers a result.
   *
   * Nothing blocks while the picker is open, so the rest of the module keeps answering.
   */
  private fun startMemeImport(promise: Promise) {
    val activity = appContext.currentActivity as? androidx.activity.ComponentActivity
    if (activity == null) {
      promise.resolve(mapOf("success" to false, "code" to MEME_IMPORT_FAILED))
      return
    }
    activity.runOnUiThread {
      // A pick still open from before is abandoned: its result has nowhere to go now.
      finishMemeImport(emptyList())
      memePickPromise = promise
      val launcher = activity.activityResultRegistry.register(
        "arsivinyo-meme-import",
        androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia(MEME_PICK_LIMIT),
      ) { uris -> finishMemeImport(uris) }
      memePickLauncher = launcher
      runCatching {
        launcher.launch(androidx.activity.result.PickVisualMediaRequest(
          androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageAndVideo))
      }.onFailure {
        addError("MEME_IMPORT_FAILED: ${it.javaClass.simpleName}")
        finishMemeImport(emptyList())
      }
    }
  }

  private var torrentPickLauncher: androidx.activity.result.ActivityResultLauncher<Array<String>>? = null
  private var torrentPickPromise: Promise? = null

  /**
   * A .torrent file chosen with the system's file picker, from the app's own activity as the
   * meme import is. Any type is offered: many file managers do not know .torrent's.
   */
  private fun pickTorrentFile(promise: Promise) {
    val activity = appContext.currentActivity as? androidx.activity.ComponentActivity
    if (activity == null) {
      promise.resolve(mapOf("success" to false, "code" to "TORRENT_PICK_FAILED"))
      return
    }
    activity.runOnUiThread {
      finishTorrentPick(null)
      torrentPickPromise = promise
      val launcher = activity.activityResultRegistry.register(
        "arsivinyo-torrent-pick",
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
      ) { uri -> finishTorrentPick(uri) }
      torrentPickLauncher = launcher
      runCatching { launcher.launch(arrayOf("application/x-bittorrent", "application/octet-stream", "*/*")) }
        .onFailure { finishTorrentPick(null) }
    }
  }

  private fun finishTorrentPick(uri: Uri?) {
    val promise = torrentPickPromise ?: return
    torrentPickPromise = null
    torrentPickLauncher?.unregister()
    torrentPickLauncher = null
    promise.resolve(if (uri == null) mapOf("success" to false, "code" to "TORRENT_PICK_CANCELLED")
                    else mapOf("success" to true, "uri" to uri.toString()))
  }

  private fun finishMemeImport(uris: List<Uri>) {
    val promise = memePickPromise ?: return
    memePickPromise = null
    memePickLauncher?.unregister()
    memePickLauncher = null
    if (uris.isEmpty()) {
      promise.resolve(mapOf("success" to false, "code" to MEME_IMPORT_CANCELLED))
      return
    }
    // Copying can take a while for videos; off the main thread, and off the module's queue.
    Thread {
      val result = runCatching { copyPickedMemes(uris) }.getOrElse {
        addError("MEME_IMPORT_FAILED: ${it.javaClass.simpleName}")
        mapOf("success" to false, "code" to MEME_IMPORT_FAILED)
      }
      promise.resolve(result)
    }.start()
  }

  /** Each picked file copied into MediaStore: a picker grant does not outlast the process. */
  private fun copyPickedMemes(picked: List<Uri>): Map<String, Any?> {
    val resolver = requireNotNull(appContext.reactContext).contentResolver
    var imported = 0
    var failed = 0
    for (uri in picked) {
      runCatching {
        val mime = resolver.getType(uri)?.takeIf { MemeCollection.kindOf(it) != null }
          ?: throw IllegalStateException("MEME_IMPORT_UNSUPPORTED_TYPE")
        val name = queryDisplayName(resolver, uri) ?: "meme_${System.currentTimeMillis()}.${extensionForMimeType(mime)}"
        val saved = saveToMediaStoreWithWriter(name, mime, System.currentTimeMillis()) { output ->
          resolver.openInputStream(uri)?.use { it.copyTo(output, PRIVATE_STREAM_BUFFER_BYTES) }
            ?: throw IllegalStateException(MEME_IMPORT_FAILED)
        }
        memes.adopt(saved["uri"] as String, mime, MemeStore.Source(platform = "import"))
          ?: throw IllegalStateException(MEME_IMPORT_FAILED)
      }.onSuccess { imported++ }.onFailure {
        failed++
        addError("MEME_IMPORT_FAILED: ${it.javaClass.simpleName}")
      }
    }
    memesChanged()
    return mapOf("success" to true, "imported" to imported, "failed" to failed)
  }

  /** A meme from a paired device: into MediaStore, labels merged by name. */
  private fun receiveMeme(file: File, meme: JSONObject?) {
    try {
      val decoded = MemeStore.decodeMeme(meme)
      val mime = guessMimeType(file.name).let { guessed ->
        if (MemeCollection.kindOf(guessed) != null) guessed
        else if (decoded.kind == "image") "image/jpeg" else "video/mp4"
      }
      val sha = MemeCollection.hashOf { file.inputStream() } ?: return
      val existing = memes.store.snapshot().items.firstOrNull { it.sha256 == sha && !it.isPrivate }
      if (existing != null) {
        memes.store.receive(existing.uri ?: return, decoded.kind, sha, decoded.source, decoded.tags, decoded.people,
          decoded.signatures)
      } else {
        val saved = saveToMediaStoreInternal(file.path, file.name, mime, System.currentTimeMillis())
        memes.store.receive(saved["uri"] as String, MemeCollection.kindOf(mime) ?: decoded.kind, sha,
          decoded.source, decoded.tags, decoded.people, decoded.signatures)
      }
      memesChanged()
    } finally {
      file.delete()
    }
  }

  // ---- faces --------------------------------------------------------------------------

  /** Loaded on the first scan; null until then, or where faces cannot run. */
  @Volatile private var faceScanner: FaceScanner? = null
  private val faceScanRunning = java.util.concurrent.atomic.AtomicBoolean(false)
  /**
   * Memes whose scan could not be written down, left alone until the app restarts: the
   * listing that follows a scan starts another, and that must not become a loop.
   */
  private val facesUnrecordable = ConcurrentHashMap.newKeySet<String>()

  /**
   * Pictures for the screens — thumbnails and face crops — off the module's queue. Expo runs
   * every AsyncFunction on one thread, so a grid asking for fifty pictures, each a video
   * frame to decode, held up search, the listing and every tap behind them.
   */
  private val memeMedia = java.util.concurrent.Executors.newFixedThreadPool(2) { runnable ->
    Thread(runnable, "meme-media").apply { priority = Thread.NORM_PRIORITY - 1 }
  }

  /** Checking every meme against MediaStore is a query each; once a minute is plenty. */
  @Volatile private var memesReconciledAt = 0L

  /** A scan changes the collection a meme at a time; the screens hear of it every few seconds. */
  @Volatile private var scanAnnouncedAt = 0L

  /** Memes the running scan has written down. */
  @Volatile private var scanRecorded = 0
  /** How many memes are left while a scan runs; null when idle. */
  @Volatile private var facesRemaining: Int? = null

  private fun facesSupported() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && FacesNative.available

  /**
   * Scans whatever has not been scanned, one meme at a time, on a thread of its own. It
   * waits while a download runs, so it never competes with one, and private memes are
   * scanned only while the vault is open.
   */
  private fun scanFaces() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) startFaceScan()
  }

  @androidx.annotation.RequiresApi(Build.VERSION_CODES.P)
  private fun startFaceScan() {
    if (!facesSupported() || !faceScanRunning.compareAndSet(false, true)) return
    Thread {
      try {
        val scanner = faceScanner
          ?: FaceScanner.load(requireNotNull(appContext.reactContext)).also { faceScanner = it }
        // Tried once per run: one that cannot be recorded must not hold the rest up.
        val tried = mutableSetOf<String>()
        scanRecorded = 0
        while (true) {
          val version = FacesNative.pipelineVersion
          val pending = MemeStore.needingScan(memes.store.snapshot(), version)
            .filter { it.id !in tried && it.id !in facesUnrecordable }
          facesRemaining = pending.size
          val next = pending.firstOrNull() ?: break
          tried.add(next.id)
          while (activeDownloads.isNotEmpty()) Thread.sleep(2000)
          val scanned = runCatching { scanAndRecord(next, scanner) }
          scanned.exceptionOrNull()?.let { addError("FACES_SCAN_FAILED: ${it.javaClass.simpleName}") }
          // Locked part way through: the private ones wait for the next unlock.
          if (next.isPrivate && !memes.store.privateReadable()) break
          // A file that cannot be read counts as scanned with nothing found, rather than
          // being tried again on every listing.
          if (scanned.isFailure) {
            runCatching { memes.store.record(next.id, emptyList()) }.onFailure { facesUnrecordable.add(next.id) }
          }
          scanRecorded++
          if (System.currentTimeMillis() - scanAnnouncedAt > 3000) {
            scanAnnouncedAt = System.currentTimeMillis()
            memesChanged()
          }
        }
      } catch (error: Throwable) {
        addError("FACES_SCAN_FAILED: ${error.javaClass.simpleName}")
      } finally {
        facesRemaining = null
        faceScanRunning.set(false)
        // Only when something changed. Every listing starts a scan in case something is new;
        // a scan that found nothing to do announcing a change made the screen list again,
        // which started another scan: a loop of a thousand listings a second.
        if (scanRecorded > 0) memesChanged()
      }
    }.apply { name = "meme-faces"; priority = Thread.MIN_PRIORITY }.start()
  }

  @androidx.annotation.RequiresApi(Build.VERSION_CODES.P)
  private fun scanAndRecord(item: MemeStore.Item, scanner: FaceScanner) {
    if (!item.isPrivate) {
      var recorded = false
      memes.scan(item, scanner) { found, frames ->
        memes.store.record(item.id, found)
        recorded = true
        // The frames are in hand now; cutting the faces out later would decode them again.
        findMeme(item.id)?.let { memes.cacheFaceCrops(it, frames) }
      }
      // Nothing to look at: scanned, with nothing found.
      if (!recorded) memes.store.record(item.id, emptyList())
      return
    }
    memes.store.record(item.id, scanPrivate(item, scanner))
  }

  @androidx.annotation.RequiresApi(Build.VERSION_CODES.P)
  private fun scanPrivate(item: MemeStore.Item, scanner: FaceScanner): List<ScannedFace> {
    val vaultId = item.vaultId ?: return emptyList()
    return if (item.kind == "video") {
      withVaultVideo(vaultId) { url ->
        val retriever = android.media.MediaMetadataRetriever()
        try {
          retriever.setDataSource(url, emptyMap())
          scanner.scan(retriever)
        } finally {
          runCatching { retriever.release() }
        }
      }
    } else {
      val image = FaceScanner.image(decryptVaultBytes(vaultId)) ?: return emptyList()
      scanner.scan(listOf(image to 0))
    }
  }

  /**
   * A private video streamed from the vault's loopback server for as long as [use] runs,
   * as playback does: nothing decrypted is written anywhere. Only the current cipher can
   * stream; an older entry is left alone rather than decrypted to a file.
   */
  private fun <T> withVaultVideo(vaultId: String, use: (String) -> T): T {
    val entry = findPrivateVideoById(vaultId) ?: throw IllegalStateException("PRIVATE_VIDEO_NOT_FOUND")
    check(entry.cipherVersion == PRIVATE_STORE_VERSION_V4) { "PRIVATE_LEGACY_VAULT_UNSUPPORTED" }
    val server = ensureVaultLoopbackServer()
    val session = server.registerVideoSession(entry.id)
    try {
      return use(server.videoUrl(session) ?: throw IllegalStateException("PRIVATE_VIDEO_NOT_FOUND"))
    } finally {
      server.invalidateVideoSession(session.token)
    }
  }

  /** A private image, decrypted into memory only. */
  private fun decryptVaultBytes(vaultId: String): ByteArray {
    val entry = findPrivateVideoById(vaultId) ?: throw IllegalStateException("PRIVATE_VIDEO_NOT_FOUND")
    val encrypted = File(privateVaultObjectsDir(create = true), entry.encFileName)
    val out = java.io.ByteArrayOutputStream()
    decryptPrivateVaultFileToOutput(encrypted, out, detectPrivateCipherVersion(encrypted, entry.cipherVersion),
      traceId = "faces", entryId = entry.id)
    return out.toByteArray()
  }

  /**
   * A face cut from its meme. A public meme's crop is cached like its thumbnail; a private
   * one's is made in memory and handed over as data, never written outside the vault.
   */
  @androidx.annotation.RequiresApi(Build.VERSION_CODES.P)
  private fun faceCropInternal(itemId: String, faceId: String): String? {
    val item = findMeme(itemId) ?: return null
    val face = item.faces.firstOrNull { it.id == faceId } ?: return null
    if (!item.isPrivate) return memes.faceCrop(item, face)
    val vaultId = item.vaultId ?: return null
    val frame = if (item.kind == "video") {
      withVaultVideo(vaultId) { url ->
        val retriever = android.media.MediaMetadataRetriever()
        try {
          retriever.setDataSource(url, emptyMap())
          FaceScanner.frameOfVideo(retriever, face.frameMs)
        } finally {
          runCatching { retriever.release() }
        }
      }
    } else {
      FaceScanner.image(decryptVaultBytes(vaultId))
    } ?: return null
    val crop = FaceScanner.crop(frame, face.box) ?: return null
    val bytes = java.io.ByteArrayOutputStream().also { crop.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
    return "data:image/jpeg;base64," + android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
  }

  private fun facesAction(action: () -> Unit): Map<String, Any?> {
    try {
      action()
    } catch (_: MemeStore.LockedException) {
      return mapOf("success" to false, "code" to "PRIVATE_VAULT_LOCKED")
    }
    memesChanged()
    return mapOf("success" to true)
  }

  private fun faceMap(face: Face) = mapOf("id" to face.id, "person" to face.person, "state" to face.state.wire)

  // ---- watch (shared/watch/CONTRACT.md) ----------------------------------------------------

  private val watch: WatchService by lazy {
    val context = requireNotNull(appContext.reactContext).applicationContext
    WatchService(WatchLibrary(File(context.filesDir, "watch/library.bin"), KeystoreSealer("arsivinyo_watch_library_v1")))
  }

  private val torrents: TorrentService by lazy { TorrentService.get(requireNotNull(appContext.reactContext)) }

  /** The filing step for torrent downloads, and the notification that follows them. */
  private fun startTorrents() {
    torrents.attach(watch.library)
    torrents.taker = object : TorrentService.Taker {
      override fun filePublic(file: File, relativePath: String): Boolean = runCatching {
        filePublicDownload(file, relativePath)
        true
      }.getOrDefault(false)

      // A locked vault answers PRIVATE_VAULT_LOCKED; the file waits, in app-private storage,
      // until it is next open.
      override fun intoVault(file: File, name: String): Boolean = runCatching {
        val mime = android.webkit.MimeTypeMap.getSingleton()
          .getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"
        importFileToPrivateVault(file.path, name, "torrent", mime)
        true
      }.getOrDefault(false)

      private var last: TorrentService.Summary? = null

      override fun changed() {
        val summary = torrents.summary()
        if (summary != last) {
          last = summary
          syncForegroundNotification(notificationPhase, null)
        }
      }
    }
    torrents.startWorker()
  }

  /**
   * A finished torrent file into the phone's Download/Arsivinyo, through MediaStore as the
   * app's other files are; below Android 10, the app's own download folder.
   */
  private fun filePublicDownload(file: File, relativePath: String) {
    val context = requireNotNull(appContext.reactContext)
    val folder = relativePath.substringBeforeLast('/', "").trim('/')
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
      val target = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), relativePath)
      target.parentFile?.mkdirs()
      file.copyTo(target, overwrite = true)
      return
    }
    val values = ContentValues().apply {
      put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
      put(MediaStore.MediaColumns.RELATIVE_PATH,
        listOf(Environment.DIRECTORY_DOWNLOADS, "Arsivinyo", folder).filter { it.isNotEmpty() }.joinToString("/"))
      put(MediaStore.MediaColumns.IS_PENDING, 1)
    }
    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IOException("MEDIASTORE_INSERT_FAILED")
    try {
      resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out, 1 shl 20) } }
        ?: throw IOException("MEDIASTORE_OUTPUT_STREAM_FAILED")
      resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
    } catch (error: Throwable) {
      runCatching { resolver.delete(uri, null, null) }
      throw error
    }
  }

  /** Add-on requests and stream resolving: network work, never on the module's own queue. */
  private val watchWork = java.util.concurrent.Executors.newFixedThreadPool(4) { runnable ->
    Thread(runnable, "watch").apply { priority = Thread.NORM_PRIORITY - 1 }
  }

  /** Runs [work] off the module's queue and answers [promise] with its map, or a failure code. */
  private fun watchAsync(promise: Promise, work: () -> Any?) {
    watchWork.execute {
      promise.resolve(try {
        work()
      } catch (failure: WatchService.Failure) {
        mapOf("success" to false, "code" to failure.code)
      } catch (error: Throwable) {
        addError("WATCH_FAILED: ${error.javaClass.simpleName}")
        mapOf("success" to false, "code" to "WATCH_FAILED")
      })
    }
  }

  private fun torrentAsync(promise: Promise, work: () -> Any?) {
    watchWork.execute {
      promise.resolve(try {
        work()
      } catch (failure: TorrentService.Failure) {
        mapOf("success" to false, "code" to failure.code)
      } catch (error: Throwable) {
        addError("TORRENT_FAILED: ${error.javaClass.simpleName}")
        mapOf("success" to false, "code" to "TORRENT_FAILED")
      })
    }
  }

  private fun watchAddonMap(addon: WatchLibrary.Addon): Map<String, Any?> {
    val manifest = Addons.manifest(addon.manifest)
    return mapOf(
      "key" to watch.key(addon.base),
      "name" to (manifest?.name ?: watch.host(addon.base)),
      "host" to watch.host(addon.base),
      "version" to manifest?.version,
      "description" to manifest?.description,
      "logo" to manifest?.logo,
      "types" to manifest?.types.orEmpty(),
      "resources" to manifest?.resources.orEmpty().map { it.name },
      "enabled" to addon.enabled,
    )
  }

  private fun previewMap(p: Addons.Preview) = mapOf(
    "id" to p.id, "type" to p.type, "name" to p.name, "poster" to p.poster, "posterShape" to p.posterShape,
    "releaseInfo" to p.releaseInfo, "description" to p.description,
  )

  private fun streamMap(s: Addons.Stream) = mapOf(
    "kind" to s.kind.wire, "target" to s.target, "fileIdx" to s.fileIdx, "label" to s.label, "detail" to s.detail,
    "bingeGroup" to s.bingeGroup, "headers" to s.headers, "filename" to s.filename,
    "subtitles" to s.subtitles.map(::subtitleMap),
  )

  private fun subtitleMap(s: Addons.Subtitle) = mapOf("id" to s.id, "url" to s.url, "lang" to s.lang)

  private fun watchItemMap(item: WatchLibrary.Item) = mapOf(
    "id" to item.id, "type" to item.type, "name" to item.name, "poster" to item.poster, "addedAt" to item.addedAt,
    "watched" to item.watched.toList(), "saved" to item.saved, "addonKey" to item.addon, "bingeGroup" to item.bingeGroup,
    "progress" to item.progress?.let {
      mapOf("videoId" to it.videoId, "positionMs" to it.positionMs, "durationMs" to it.durationMs, "at" to it.at)
    },
  )

  private fun watchTitle(map: Map<String, Any?>) = WatchLibrary.Title(
    id = map["id"] as? String ?: throw WatchService.Failure("WATCH_BAD_TITLE"),
    type = map["type"] as? String ?: "movie",
    name = map["name"] as? String ?: "",
    poster = map["poster"] as? String,
  )

  /**
   * Something a player can open: a media URL plays as it is; a page, a YouTube video or an
   * external link goes through yt-dlp first, with the cookies the app keeps for that site.
   */
  private fun prepareStream(kind: String, target: String, headers: Map<String, String>, fileIdx: Int?,
                            filename: String?): Map<String, Any?> {
    if (kind == "torrent") {
      // Through the engine's loopback server, fetched into the cache as it plays.
      val stream = try {
        torrents.stream(target, fileIdx, filename)
      } catch (failure: TorrentService.Failure) {
        throw WatchService.Failure(failure.code)
      }
      return mapOf("success" to true, "url" to stream.url, "headers" to emptyMap<String, String>(), "torrentId" to stream.id)
    }
    if (!Addons.isHttp(target)) throw WatchService.Failure("WATCH_BAD_URL")
    if (kind == "url" && !watch.isPage(target, headers)) {
      return mapOf("success" to true, "url" to target, "headers" to headers)
    }
    ensurePythonReady()
    val taskId = "watch-${UUID.randomUUID()}"
    try {
      val cookieFile = runCatching { prepareRuntimeCookiePath(taskId, target, null, detectCookiePlatform(target)) }.getOrNull()
      val result = JSONObject(Python.getInstance().getModule("local_downloader")
        .callAttr("resolve_stream", target, cookieFile, DEFAULT_HTTP_USER_AGENT, debugLoggingEnabled).toString())
      if (!result.optBoolean("success")) throw WatchService.Failure(result.optString("code", "RESOLVE_FAILED"))
      val resolvedHeaders = result.optJSONObject("headers")?.let { h -> h.keys().asSequence().associateWith { h.optString(it) } }.orEmpty()
      return mapOf(
        "success" to true,
        "url" to result.getString("url"),
        "audioUrl" to result.optString("audioUrl").ifBlank { null }?.takeIf { it != "null" },
        "headers" to resolvedHeaders,
        "title" to result.optString("title").ifBlank { null },
        "isLive" to result.optBoolean("isLive"),
      )
    } finally {
      cleanupRuntimeCookieTemp(taskId)
    }
  }

  /** The most recent URL a peer asked this phone to fetch, for the screen to offer. */
  @Volatile private var lastPeerUrl: String = ""
  @Volatile private var lastPeerMediaKind: String = ""

  /**
   * The whole state the pairing screen renders.
   *
   * Built in one place and used by both the function and the event. Building it twice is
   * what let the event omit the URL fields, which the screen reads unconditionally: the
   * first render worked and the first event after it crashed.
   */
  private fun pairingStateMap(): Map<String, Any?> =
    (pairingCoordinator?.state().orEmpty()) + mapOf(
      "peerUrl" to lastPeerUrl,
      "peerMediaKind" to lastPeerMediaKind,
      "autoDownloadLinks" to pairingAutoDownloadLinks,
    )

  private val vaultLoopbackLock = Any()
  @Volatile private var vaultLoopbackServer: VaultLoopbackServer? = null
  /**
   * How long the vault stays open, and who is holding it that way.
   *
   * This replaces a plain cached key. The key used to live for the whole process and the
   * biometric prompt was a screen in front of it — one that anything with bridge access could
   * simply decline to ask for.
   */
  private val vaultSession = VaultSession()

  /** Keyed by loopback session token, so a dropped session releases exactly its own lease. */
  private val playbackLeases = java.util.concurrent.ConcurrentHashMap<String, VaultSession.Lease>()
  @Volatile private var activeMigrationCancel: VaultMigrator.CancelToken? = null
  @Volatile private var lastMigrationProgress: VaultMigrator.Progress? = null
  private val ytDlpUpdateLock = Any()
  private val recentQuickUrls = LinkedHashMap<String, Long>()
  private val tag = "LocalDownloader"
  private val debugLoggingEnabled = BuildConfig.DEBUG

  /**
   * Downloads that are running right now, keyed by task id.
   *
   * This used to be three separate fields — `activeTaskId`, `activeJob`, `activeTaskUrl`
   * — which encoded the assumption that exactly one download exists. Being separate
   * fields they could also disagree with one another. Everything that used to ask "is
   * *the* download this one?" now asks this table instead.
   */
  private val activeDownloads = ConcurrentHashMap<String, ActiveDownload>()

  /**
   * The gates that decide how many downloads may be in each stage at once. See
   * [DownloadStages]; this is the whole of the scheduler's configuration.
   */
  private val stages = DownloadStages()

  @Volatile
  private var cachedFfmpegInfo: FfmpegInfo? = null

  @Volatile
  private var cookieMigrationStatus: String = "not_needed"

  @Volatile
  private var lastCustomDomainMatch: CustomDomainMatch? = null

  @Volatile
  private var lastQuickReason: String? = null

  @Volatile
  private var notificationPhase: String = "idle"

  @Volatile
  private var privateModeEnabled: Boolean = false

  @Volatile
  private var audioModeEnabled: Boolean = false

  /**
   * Container/codec used for audio downloads. FLAC (lossless) by default so a download
   * does not stack a second generation of lossy encoding onto an already-lossy source;
   * see _apply_audio_postprocessing in local_downloader.py for the full reasoning.
   */
  @Volatile
  private var audioFormat: String = DEFAULT_AUDIO_FORMAT

  @Volatile
  private var stickyNotificationEnabled: Boolean = false

  @Volatile
  private var privateLastEncryptMs: Long? = null

  @Volatile
  private var privateLastDecryptMs: Long? = null

  @Volatile
  private var privateLastThroughputMbps: Double? = null

  @Volatile
  private var ytDlpUpdateRunning: Boolean = false

  @Volatile
  private var lastYtDlpBootstrapStatus: JSONObject? = null

  override fun definition() = ModuleDefinition {
    Name("LocalDownloader")
    Events(
      "downloadProgress",
      "backgroundStateChanged",
      "ytDlpUpdateProgress",
      "privateVaultMigrationProgress",
      "soundPresetProgress",
      "backupProgress",
      "pairingStateChanged",
      "memesChanged",
    )

    OnCreate {
      activeModule = this@LocalDownloaderModule
      lastQuickReason = lastQuickReasonFallback
      val context = requireNotNull(appContext.reactContext)
      privateModeEnabled = isPrivateModeEnabledPersisted(context)
      audioModeEnabled = isAudioModeEnabledPersisted(context)
      audioFormat = audioFormatPersisted(context)
      // Before the resume, not after: a finished batch deletes the persisted queue, and
      // the queue is what says which staged file is still needed. Sweeping afterwards
      // would race a resumed batch and delete the audio out from under it.
      runCatching { cleanupOrphanedStaging() }
      resumePresetRenderIfAny()
      // Torrent downloads carry on where they stopped, the app having been closed or killed.
      runCatching { startTorrents() }
      // An export that the system killed leaves a partial document behind. It has a valid
      // header, so it opens and lists its sections and only fails once a restore is under
      // way — worse than no file at all.
      runCatching { cleanupInterruptedBackupExport() }
      stickyNotificationEnabled = isStickyNotificationEnabledPersisted(context)
      debug("Module OnCreate started. supportedAbis=${Build.SUPPORTED_ABIS?.joinToString()}")
      // Bring the notification in line with reality — AFTER every persisted flag it reads
      // has been loaded. Placed any earlier it saw stickyNotificationEnabled at its
      // uninitialised default of false, decided nothing should be showing, and cancelled
      // the user's sticky notification on every launch.
      //
      // The service is START_STICKY, so Android restarts it after a process kill and it can
      // be left showing whatever the dead process last rendered. Nothing else corrects
      // that, because every other call site fires only when work starts or finishes.
      runCatching { reconcileForegroundNotification() }
      cleanupRuntimeCookieTemp()
      cleanupPrivatePlaybackCacheInternal()
      cleanupPrivateVaultPartials()
      migrateLegacyCookieStoreIfNeeded()
      loadTaskSnapshot()
      val ffmpegInfo = resolveBundledFfmpegPath()
      debug("Initial ffmpeg info: ${summarizeFfmpegInfo(ffmpegInfo)}")
      if (ffmpegInfo.runtimeSource != "native_library") {
        addError(
          "FFMPEG_NATIVE_RUNTIME_UNAVAILABLE: source=${ffmpegInfo.runtimeSource} " +
            "nativeDir=${ffmpegInfo.nativeLibraryDir ?: "n/a"}"
        )
      } else if (!ffmpegInfo.exists) {
        addError("FFMPEG_MISSING: bundled ffmpeg binary not found for device ABI")
      } else if (!ffmpegInfo.ffprobeExists) {
        addError("FFPROBE_MISSING: bundled ffprobe binary not found for device ABI")
      } else if (!ffmpegInfo.mergeCapable) {
        addError("MERGE_DEPENDENCY_MISSING: ffmpeg/ffprobe not executable")
      }
      cachedFfmpegInfo = ffmpegInfo
      if (stickyNotificationEnabled) {
        syncForegroundNotification("idle", "Ready for quick downloads")
      } else {
        stopForegroundNotificationIfIdle()
      }
      consumePendingQuickRequests()
      emitBackgroundStateChanged()
    }

    /**
     * Lock when the app goes away, unless something is holding the key.
     *
     * The vault used to re-lock only when its screen unmounted, so leaving the app with it
     * open left the key live indefinitely. A running export or a playing video keeps its
     * lease and is not interrupted.
     */
    OnActivityEntersBackground {
      runCatching {
        if (vaultSession.lock("background")) {
          // Any thumbnail URL already handed out stops working, so a screenshot of the
          // recents list cannot be used to fetch one afterwards.
          runCatching {
            vaultLoopbackServer?.rotateThumbnailToken()
            vaultLoopbackServer?.invalidateAllVideoSessions()
          }
        }
      }
      // Out of sight, finished torrents stop seeding once nothing is left to download.
      runCatching { torrents.inBackground = true }
    }

    OnActivityEntersForeground {
      runCatching { torrents.inBackground = false }
    }

    OnDestroy {
      if (activeModule === this@LocalDownloaderModule) {
        activeModule = null
      }
      runCatching { activeMigrationCancel?.cancel() }
      runCatching { stopVaultLoopbackServer() }
      runCatching {
        playbackLeases.values.forEach { vaultSession.endLease(it) }
        playbackLeases.clear()
        vaultSession.forceLock()
      }
      syncForegroundNotification("idle", "Stopping background notification")
      appContext.reactContext?.let { DownloadNotificationController.stop(it) }
      emitBackgroundStateChanged()
    }

    AsyncFunction("startDownload") { input: Map<String, Any?> ->
      val url = (input["url"] as? String)?.trim().orEmpty()
      val cookiePlatform = (input["cookiePlatform"] as? String)?.trim()?.lowercase()?.takeIf { SUPPORTED_PLATFORMS.contains(it) }
      val cookieProfile = (input["cookieProfile"] as? String)?.trim().orEmpty().ifEmpty { null }
      val maxFileSizeMb = (input["maxFileSizeMb"] as? Number)?.toInt()?.coerceAtLeast(0) ?: DEFAULT_MAX_FILE_SIZE_MB
      val audioOnly = (input["mediaKind"] as? String)?.lowercase() == "audio"
      // Audio downloads always go to the public music library — no vault.
      val visibility = if (audioOnly) "public" else normalizeVisibility((input["visibility"] as? String), defaultPrivate = privateModeEnabled)
      startDownloadInternal(
        url = url,
        cookiePlatform = cookiePlatform,
        cookieProfile = cookieProfile,
        maxFileSizeMb = maxFileSizeMb,
        visibility = visibility,
        source = "manual",
        audioOnly = audioOnly,
      )
    }

    AsyncFunction("getTaskStatus") { taskId: String ->
      val task = tasks[taskId]
      if (task == null) {
        mapOf(
          "taskId" to taskId,
          "status" to "PENDING"
        )
      } else {
        task.toMap()
      }
    }

    AsyncFunction("cancelTask") { taskId: String ->
      // Used to refuse anything that was not the single active download, which left a
      // waiting download with no way to be cancelled at all. A download waiting at a gate
      // is a live task, and cancelling its job also removes it from the gate's waiters.
      if (!isTaskLive(taskId)) {
        return@AsyncFunction mapOf("success" to false)
      }

      markCancelRequested(taskId)
      ignoredTaskResults.add(taskId)
      if (!isTerminalStatus(tasks[taskId]?.status)) {
        markCancelled(taskId, "Cancellation requested")
      }
      debug("Task[$taskId] cancellation requested; task marked cancelled immediately")
      syncForegroundNotification("downloading", "Cancellation requested")
      emitBackgroundStateChanged()

      mapOf(
        "success" to true,
        "confirmed" to true,
        "pending" to true
      )
    }

    AsyncFunction("getBackgroundState") {
      backgroundStateMap()
    }

    AsyncFunction("ensureBackgroundPermission") {
      val context = requireNotNull(appContext.reactContext)
      val granted = isNotificationPermissionGranted(context)
      if (!granted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        appContext.currentActivity?.let { activity ->
          runCatching {
            ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_CODE_NOTIFICATIONS)
          }
        }
      }
      val refreshedGranted = isNotificationPermissionGranted(context)
      mapOf(
        "granted" to refreshedGranted,
        "canAskAgain" to canAskForNotificationPermission()
      )
    }

    AsyncFunction("setStickyNotificationEnabled") { input: Map<String, Any?> ->
      val requested = (input["enabled"] as? Boolean) ?: false
      val resolved = setStickyNotificationEnabledInternal(requested)
      mapOf("enabled" to resolved)
    }

    AsyncFunction("startQuickDownloadFromClipboard") {
      startQuickDownloadFromClipboard()
    }

    AsyncFunction("startQuickDownloadWithUrl") { input: Map<String, Any?> ->
      val url = (input["url"] as? String)?.trim().orEmpty()
      // Given when the request came from a paired device, which said what it wanted.
      val mediaKind = (input["mediaKind"] as? String)?.trim()?.ifEmpty { null }
      startQuickDownloadWithUrl(url, "manual", mediaKindOverride = mediaKind)
    }

    AsyncFunction("getPrivateModeState") {
      mapOf("enabled" to privateModeEnabled)
    }

    AsyncFunction("setPrivateModeEnabled") { input: Map<String, Any?> ->
      val requested = (input["enabled"] as? Boolean) ?: false
      val resolved = setPrivateModeEnabledInternal(requested)
      mapOf("enabled" to resolved)
    }

    AsyncFunction("getAudioModeState") {
      mapOf("enabled" to audioModeEnabled)
    }

    AsyncFunction("setAudioModeEnabled") { input: Map<String, Any?> ->
      val requested = (input["enabled"] as? Boolean) ?: false
      val resolved = setAudioModeEnabledInternal(requested)
      mapOf("enabled" to resolved)
    }

    AsyncFunction("getAudioFormat") {
      mapOf("format" to audioFormat, "lossless" to (audioFormat == AUDIO_FORMAT_FLAC))
    }

    AsyncFunction("setAudioFormat") { input: Map<String, Any?> ->
      val resolved = setAudioFormatInternal(input["format"] as? String)
      mapOf("format" to resolved, "lossless" to (resolved == AUDIO_FORMAT_FLAC))
    }

    AsyncFunction("authenticatePrivateAccess") { input: Map<String, Any?> ->
      val purpose = (input["purpose"] as? String)?.trim().orEmpty().ifBlank { "view" }
      val auth = authenticatePrivateAccessInternal(purpose)
      mapOf(
        "granted" to auth.first,
        "reason" to auth.second
      )
    }

    /** Opens the vault. The screen calls this when native reports PRIVATE_VAULT_LOCKED. */
    AsyncFunction("unlockPrivateVault") { input: Map<String, Any?> ->
      val purpose = (input["purpose"] as? String)?.trim().orEmpty().ifBlank { "view" }
      unlockPrivateVaultInternal(purpose)
    }

    /** Refused while an export, a migration or a video is holding the key. */
    AsyncFunction("lockPrivateVault") {
      val locked = vaultSession.lock("requested")
      mapOf("locked" to locked) + privateVaultLockStateInternal()
    }

    /** Answers while locked, or the lock indicator could never be drawn. */
    AsyncFunction("getPrivateVaultLockState") { privateVaultLockStateInternal() }

    /** Which key opens the vault, and whether there is a way in that is not this device. */
    AsyncFunction("getVaultKeyState") { vaultKeyStateInternal() }

    /**
     * Move the vault onto a key the Keystore will not use without a recent unlock.
     *
     * Roughly sixty bytes move; the videos are never re-encrypted. The old key is kept until
     * the new one has been used and a recovery passphrase exists.
     */
    AsyncFunction("upgradeVaultKey") { upgradeVaultKeyInternal() }

    /** Destroy the old key. Refused without a recovery passphrase unless forced. */
    AsyncFunction("finaliseVaultKeyUpgrade") { input: Map<String, Any?> ->
      finaliseVaultKeyInternal(force = (input["force"] as? Boolean) == true)
    }

    /**
     * Set a passphrase that opens the vault when the device key cannot.
     *
     * The only defence against the screen lock being removed, which deletes a hardware-bound
     * key permanently and cannot be prevented by any setting.
     */
    AsyncFunction("setVaultRecoveryPassphrase") { input: Map<String, Any?> ->
      val passphrase = (input["passphrase"] as? String).orEmpty()
      if (passphrase.length < 14) {
        return@AsyncFunction mapOf("success" to false, "code" to "PRIVATE_RECOVERY_TOO_SHORT")
      }
      val (granted, reason) = authenticatePrivateAccessInternal("migrate")
      if (!granted) {
        return@AsyncFunction mapOf("success" to false, "code" to (reason ?: "PRIVATE_AUTH_FAILED"))
      }
      val characters = passphrase.toCharArray()
      try {
        vaultKeyBox().addRecoverySlot(characters)
        mapOf("success" to true) + vaultKeyStateInternal()
      } catch (error: Throwable) {
        mapOf("success" to false, "code" to (error.message ?: "PRIVATE_RECOVERY_FAILED"))
      } finally {
        characters.fill('\u0000')
      }
    }

    /** Open the vault with the recovery passphrase, when the device key is gone. */
    AsyncFunction("unlockVaultWithRecovery") { input: Map<String, Any?> ->
      val passphrase = (input["passphrase"] as? String).orEmpty()
      val characters = passphrase.toCharArray()
      try {
        val dek = vaultKeyBox().unlockWithRecovery(characters)
        try {
          vaultSession.unlock(dek, System.currentTimeMillis())
        } finally {
          dek.fill(0)
        }
        mapOf("success" to true) + privateVaultLockStateInternal() + vaultKeyStateInternal()
      } catch (error: Throwable) {
        mapOf("success" to false, "code" to (error.message ?: "PRIVATE_RECOVERY_FAILED"))
      } finally {
        characters.fill('\u0000')
      }
    }

    AsyncFunction("listPrivateVideos") {
      listPrivateVideosInternal()
    }

    AsyncFunction("deletePrivateVideo") { input: Map<String, Any?> ->
      val id = (input["id"] as? String)?.trim().orEmpty()
      if (id.isBlank()) {
        return@AsyncFunction mapOf("success" to false)
      }
      mapOf("success" to deletePrivateVideoInternal(id))
    }

    AsyncFunction("copyPrivateVideoToPublicGallery") { input: Map<String, Any?> ->
      val id = (input["id"] as? String)?.trim().orEmpty()
      if (id.isBlank()) {
        return@AsyncFunction mapOf(
          "success" to false,
          "code" to "PRIVATE_VIDEO_NOT_FOUND",
          "message" to "PRIVATE_VIDEO_NOT_FOUND"
        )
      }
      copyPrivateVideoToPublicGalleryInternal(id)
    }

    AsyncFunction("pickAndImportVideoToPrivateVault") {
      pickAndImportVideoToPrivateVaultInternal()
    }

    // ---- watch: add-ons, catalogs, streams, the library ---------------------------------

    AsyncFunction("watchAddons") { watch.library.addons().map(::watchAddonMap) }

    AsyncFunction("watchInstallAddon") { url: String, promise: Promise ->
      watchAsync(promise) { mapOf("success" to true, "name" to watch.install(url).name) }
    }

    /** Lists of add-ons to offer, from the add-ons installed. */
    AsyncFunction("watchOfferLists") {
      watch.offerLists().map {
        mapOf("addonKey" to it.addonKey, "addonName" to it.addonName, "type" to it.catalog.type,
          "id" to it.catalog.id, "name" to it.catalog.name)
      }
    }

    AsyncFunction("watchOffers") { addonKey: String, type: String, id: String, promise: Promise ->
      watchAsync(promise) {
        val installed = watch.library.addons().map { it.base }.toSet()
        mapOf("success" to true, "offers" to watch.offers(addonKey, type, id).map { offer ->
          // An offered add-on's address is public, from the catalog; only installed ones are kept secret.
          mapOf(
            "url" to offer.base + "/manifest.json",
            "configureUrl" to if (offer.manifest.configurable) offer.base + "/configure" else null,
            "name" to offer.manifest.name,
            "description" to offer.manifest.description,
            "logo" to offer.manifest.logo,
            "types" to offer.manifest.types,
            "resources" to offer.manifest.resources.map { it.name },
            "configurable" to offer.manifest.configurable,
            "required" to offer.manifest.configurationRequired,
            "installed" to (offer.base in installed),
          )
        })
      }
    }

    AsyncFunction("watchUninstallAddon") { key: String -> watch.library.uninstall(watch.addon(key).base) }

    AsyncFunction("watchSetAddonEnabled") { key: String, enabled: Boolean ->
      watch.library.setEnabled(watch.addon(key).base, enabled)
    }

    AsyncFunction("watchMoveAddon") { key: String, position: Int -> watch.library.move(watch.addon(key).base, position) }

    /** The board's rows, from the manifests alone; each row is then filled on its own. */
    AsyncFunction("watchRows") { search: Boolean ->
      (if (search) watch.searchable() else watch.rows()).map {
        mapOf("addonKey" to it.addonKey, "addonName" to it.addonName, "type" to it.catalog.type,
          "id" to it.catalog.id, "name" to it.catalog.name)
      }
    }

    AsyncFunction("watchCatalog") { addonKey: String, type: String, id: String, extra: Map<String, String>, promise: Promise ->
      watchAsync(promise) {
        mapOf("success" to true, "items" to watch.catalog(addonKey, type, id, extra.toList()).map(::previewMap))
      }
    }

    AsyncFunction("watchMeta") { type: String, id: String, promise: Promise ->
      watchAsync(promise) {
        val (addonKey, meta) = watch.meta(type, id)
        mapOf(
          "success" to true, "addonKey" to addonKey,
          "meta" to mapOf(
            "id" to meta.id, "type" to meta.type, "name" to meta.name, "poster" to meta.poster,
            "background" to meta.background, "logo" to meta.logo, "description" to meta.description,
            "releaseInfo" to meta.releaseInfo, "runtime" to meta.runtime, "genres" to meta.genres,
            "imdbRating" to meta.imdbRating,
            "trailers" to meta.trailers.map(::streamMap),
            "videos" to meta.videos.map {
              mapOf("id" to it.id, "title" to it.title, "season" to it.season, "episode" to it.episode,
                "released" to it.released, "thumbnail" to it.thumbnail, "overview" to it.overview)
            },
          ),
        )
      }
    }

    AsyncFunction("watchStreamSources") { type: String, id: String ->
      watch.streamSources(type, id).map { (key, name) -> mapOf("addonKey" to key, "name" to name) }
    }

    AsyncFunction("watchStreams") { addonKey: String, type: String, id: String, promise: Promise ->
      watchAsync(promise) { mapOf("success" to true, "streams" to watch.streams(addonKey, type, id).map(::streamMap)) }
    }

    AsyncFunction("watchPrepare") { kind: String, target: String, headers: Map<String, String>, fileIdx: Int?,
                                    filename: String?, promise: Promise ->
      watchAsync(promise) { prepareStream(kind, target, headers, fileIdx, filename) }
    }

    AsyncFunction("watchTorrentSettings") {
      val s = torrents.settings()
      mapOf("seedRatio" to s.seedRatio, "seedOnMobileData" to s.seedOnMobileData,
        "cacheLimitBytes" to s.cacheLimitBytes.toDouble(), "headsUpDismissed" to s.headsUpDismissed)
    }

    AsyncFunction("watchSetTorrentSettings") { values: Map<String, Any?> ->
      val s = torrents.settings()
      torrents.setSettings(s.copy(
        seedRatio = (values["seedRatio"] as? Number)?.toDouble() ?: s.seedRatio,
        seedOnMobileData = values["seedOnMobileData"] as? Boolean ?: s.seedOnMobileData,
        cacheLimitBytes = (values["cacheLimitBytes"] as? Number)?.toLong() ?: s.cacheLimitBytes,
        headsUpDismissed = values["headsUpDismissed"] as? Boolean ?: s.headsUpDismissed,
      ))
    }

    AsyncFunction("watchVpnActive") { torrents.vpnAppearsActive() }

    /** A streamed torrent's peers and speed, shown while the player waits on it; null if not here. */
    AsyncFunction("watchTorrentLive") { id: String ->
      runCatching { torrents.live(id) }.getOrNull()?.let {
        mapOf("hasMetadata" to it.optBoolean("hasMetadata"), "peers" to it.optInt("peers"),
          "downloadRate" to it.optDouble("downloadRate"))
      }
    }

    // ---- torrent downloads (shared/watch/CONTRACT.md, "Downloading") ------------------------

    /** A magnet link, or a content:// URI of a .torrent file; the new download's info hash. */
    AsyncFunction("torrentAdd") { input: String, promise: Promise ->
      torrentAsync(promise) {
        startTorrents()
        val text = input.trim()
        val id = if (text.startsWith("magnet:", ignoreCase = true)) {
          torrents.add(text, null)
        } else {
          val bytes = requireNotNull(appContext.reactContext).contentResolver.openInputStream(Uri.parse(text))
            ?.use { it.readBytes() } ?: throw TorrentService.Failure("TORRENT_BAD_INPUT")
          torrents.add(null, bytes)
        }
        mapOf("success" to true, "id" to id)
      }
    }

    AsyncFunction("torrentPickFile") { promise: Promise -> pickTorrentFile(promise) }

    AsyncFunction("torrentFiles") { id: String, promise: Promise ->
      torrentAsync(promise) {
        val files = torrents.files(id)
        mapOf("success" to true, "files" to files?.let { list ->
          (0 until list.length()).map { list.getJSONObject(it) }.map {
            mapOf("index" to it.getInt("index"), "path" to it.getString("path"), "size" to it.getLong("size").toDouble())
          }
        })
      }
    }

    AsyncFunction("torrentChoose") { id: String, wanted: List<Int>, destination: String, promise: Promise ->
      torrentAsync(promise) {
        torrents.choose(id, wanted, if (destination == "private") "private" else "public")
        mapOf("success" to true)
      }
    }

    AsyncFunction("torrentList") { promise: Promise ->
      torrentAsync(promise) {
        // Whether a finished private file is being encrypted now, or waits for the vault.
        mapOf("success" to true, "vaultOpen" to vaultSession.snapshot().unlocked,
          "torrents" to runCatching { torrents.downloads() }.getOrDefault(emptyList()).map { (record, engine) ->
          val done = engine?.optDouble("done") ?: 0.0
          val wanted = engine?.optDouble("wanted") ?: 0.0
          val rate = engine?.optDouble("downloadRate") ?: 0.0
          mapOf(
            "id" to record.infoHash, "name" to record.name, "destination" to record.destination, "state" to record.state,
            "wanted" to record.wanted.size, "taken" to record.taken.size, "addedAt" to record.addedAt.toDouble(),
            "engine" to engine?.let {
              mapOf(
                "state" to it.optString("state"), "paused" to it.optBoolean("paused"), "finished" to it.optBoolean("finished"),
                "progress" to it.optDouble("progress"), "done" to done, "size" to wanted,
                "downloadRate" to rate, "uploadRate" to it.optDouble("uploadRate"),
                "peers" to it.optInt("peers"), "seeds" to it.optInt("seeds"),
                "etaSeconds" to if (rate > 0 && wanted > done) (wanted - done) / rate else null,
              )
            },
          )
        })
      }
    }

    AsyncFunction("torrentPause") { id: String, promise: Promise -> torrentAsync(promise) { torrents.pause(id); mapOf("success" to true) } }

    AsyncFunction("torrentResume") { id: String, promise: Promise -> torrentAsync(promise) { torrents.resume(id); mapOf("success" to true) } }

    AsyncFunction("torrentRemove") { id: String, deleteFiles: Boolean, promise: Promise ->
      torrentAsync(promise) { torrents.remove(id, deleteFiles); mapOf("success" to true) }
    }

    /**
     * Subtitles to offer for a video, best first: the stream's own, then the add-ons', in the
     * preferred languages only (CONTRACT.md, "The player").
     */
    AsyncFunction("watchSubtitles") { type: String, id: String, filename: String?, own: List<Map<String, Any?>>, promise: Promise ->
      watchAsync(promise) {
        val mine = own.mapNotNull { m ->
          val url = (m["url"] as? String)?.takeIf(Addons::isHttp) ?: return@mapNotNull null
          Addons.Subtitle(m["id"] as? String ?: url, url, m["lang"] as? String ?: "")
        }
        val extra = listOfNotNull(filename?.ifBlank { null }?.let { "filename" to it })
        val ranked = Addons.rankSubtitles(mine + watch.subtitles(type, id, extra), watch.library.languages())
        mapOf("success" to true, "subtitles" to ranked.map { subtitleMap(it) + ("lang" to Addons.language(it.lang)) })
      }
    }

    AsyncFunction("watchLanguages") {
      mapOf("chosen" to watch.library.languages(), "offered" to Addons.languages.map { it.code })
    }

    AsyncFunction("watchSetLanguages") { codes: List<String> -> watch.library.setLanguages(codes) }

    View(MpvPlayerView::class) {
      Events("onProgress", "onTracks", "onEnded", "onFailed")

      OnViewDestroys { view: MpvPlayerView -> view.release() }

      Prop("source") { view: MpvPlayerView, source: Map<String, Any?>? ->
        val url = source?.get("url") as? String
        @Suppress("UNCHECKED_CAST")
        view.setSource(url?.let {
          MpvPlayerView.Source(it, (source["headers"] as? Map<String, String>).orEmpty(), (source["startMs"] as? Number)?.toLong() ?: 0L,
            source["audioUrl"] as? String)
        })
      }

      Prop("languages") { view: MpvPlayerView, languages: List<String>? -> view.languages = languages.orEmpty() }

      AsyncFunction("setPaused") { view: MpvPlayerView, paused: Boolean -> view.setPaused(paused) }
      AsyncFunction("seek") { view: MpvPlayerView, ms: Double -> view.seek(ms) }
      AsyncFunction("seekBy") { view: MpvPlayerView, ms: Double -> view.seekBy(ms) }
      AsyncFunction("setTrack") { view: MpvPlayerView, kind: String, id: String -> view.setTrack(kind, id) }
      AsyncFunction("addSubtitle") { view: MpvPlayerView, url: String, title: String, lang: String, select: Boolean ->
        view.addSubtitle(url, title, lang, select)
      }
      AsyncFunction("setSubtitleDelay") { view: MpvPlayerView, ms: Double -> view.setSubtitleDelay(ms) }
      AsyncFunction("setSpeed") { view: MpvPlayerView, speed: Double -> view.setSpeed(speed) }
    }

    AsyncFunction("watchLibrary") {
      mapOf(
        "continue" to watch.library.continueWatching().map(::watchItemMap),
        "saved" to watch.library.items().filter { it.saved }.sortedByDescending { it.addedAt }.map(::watchItemMap),
      )
    }

    AsyncFunction("watchItem") { id: String -> watch.library.item(id)?.let(::watchItemMap) }

    AsyncFunction("watchRecordProgress") { title: Map<String, Any?>, videoId: String, positionMs: Double, durationMs: Double,
                                          addonKey: String?, bingeGroup: String? ->
      watch.library.recordProgress(watchTitle(title), videoId, positionMs.toLong(), durationMs.toLong(), addonKey, bingeGroup)
    }

    AsyncFunction("watchSetWatched") { title: Map<String, Any?>, videoId: String, watched: Boolean ->
      watch.library.setWatched(watchTitle(title), videoId, watched)
    }

    AsyncFunction("watchSetSaved") { title: Map<String, Any?>, saved: Boolean -> watch.library.setSaved(watchTitle(title), saved) }

    AsyncFunction("watchDismiss") { id: String -> watch.library.dismissProgress(id) }

    AsyncFunction("watchRemove") { id: String -> watch.library.remove(id) }

    // ---- memes ----------------------------------------------------------------

    AsyncFunction("listMemes") { listMemesInternal() }

    AsyncFunction("memeThumbnail") { id: String, promise: Promise ->
      memeMedia.execute {
        promise.resolve(runCatching {
          val item = findMeme(id)
          when {
            item == null -> null
            item.isPrivate -> item.vaultId?.let { getPrivateThumbnailUriInternal(it)["uri"] as? String }
            else -> memes.thumbnail(item)
          }
        }.getOrNull())
      }
    }

    /** Search is here rather than in TS, so there is one Turkish folding, held to the vectors. */
    AsyncFunction("searchMemes") { query: String, filter: Map<String, Any?> ->
      @Suppress("UNCHECKED_CAST")
      fun strings(key: String) = (filter[key] as? List<String>).orEmpty()
      MemeStore.search(memes.store.snapshot(), query, MemeStore.Filter(
        facets = strings("facets").mapNotNull { MemeStore.Facet.of(it) }.toSet(),
        people = strings("people").toSet(),
        platform = (filter["platform"] as? String)?.ifBlank { null },
        onlyPrivate = filter["onlyPrivate"] as? Boolean ?: false,
        onlyUntagged = filter["onlyUntagged"] as? Boolean ?: false,
      )).map { it.id }
    }

    /** Unnamed faces in groups of the same person, largest first: (meme, face) ids. */
    AsyncFunction("memeFaceGroups") {
      memes.store.unnamedGroups(memes.store.snapshot()).map { group ->
        mapOf("count" to group.size, "faces" to group.take(12).map { (item, face) -> mapOf("itemId" to item.id, "faceId" to face.id) })
      }
    }

    AsyncFunction("memeFaceCrop") { itemId: String, faceId: String, promise: Promise ->
      memeMedia.execute {
        promise.resolve(
          if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) null
          else runCatching { faceCropInternal(itemId, faceId) }.getOrNull()
        )
      }
    }

    AsyncFunction("confirmMemeFace") { faceId: String ->
      facesAction { memes.store.confirmFace(faceId) }
    }

    AsyncFunction("rejectMemeFace") { faceId: String ->
      facesAction { memes.store.rejectFace(faceId) }
    }

    AsyncFunction("nameMemeFaces") { faceIds: List<String>, name: String ->
      facesAction { memes.store.nameFaces(faceIds.toSet(), name.trim().take(80)) }
    }

    AsyncFunction("renameMemePerson") { personId: String, name: String ->
      facesAction { memes.store.renamePerson(personId, name) }
    }

    AsyncFunction("deleteMemePerson") { personId: String ->
      facesAction { memes.store.deletePerson(personId) }
    }

    AsyncFunction("memeSuggestions") { id: String ->
      val snapshot = memes.store.snapshot()
      val item = snapshot.items.firstOrNull { it.id == id } ?: return@AsyncFunction emptyList<String>()
      MemeStore.suggestions(item, snapshot).map { it.id }
    }

    AsyncFunction("createMemeTag") { name: String, facets: List<String> ->
      val tag = memes.store.tag(name, facets.mapNotNull { MemeStore.Facet.of(it) })
      memesChanged()
      memeTagMap(tag)
    }

    AsyncFunction("createMemePerson") { name: String ->
      val person = memes.store.person(name)
      memesChanged()
      mapOf("id" to person.id, "name" to person.name)
    }

    AsyncFunction("setMemeTagFacets") { tagId: String, facets: List<String> ->
      memes.store.setFacets(tagId, facets.mapNotNull { MemeStore.Facet.of(it) })
      memesChanged()
    }

    AsyncFunction("renameMemeTag") { tagId: String, name: String ->
      memes.store.renameTag(tagId, name)
      memesChanged()
    }

    AsyncFunction("deleteMemeTag") { tagId: String ->
      memes.store.deleteTag(tagId)
      memesChanged()
    }

    AsyncFunction("labelMemes") { input: Map<String, Any?> ->
      @Suppress("UNCHECKED_CAST")
      fun set(key: String) = (input[key] as? List<String>).orEmpty().toSet()
      val ids = set("ids")
      try {
        memes.store.label(ids, set("addTags"), set("removeTags"), set("addPeople"), set("removePeople"),
          markTagged = input["markTagged"] as? Boolean ?: true)
      } catch (_: MemeStore.LockedException) {
        return@AsyncFunction mapOf("success" to false, "code" to "PRIVATE_VAULT_LOCKED")
      }
      ids.forEach { memes.dismissPrompt(it) }
      memesChanged()
      mapOf("success" to true)
    }

    AsyncFunction("setMemesPrivate") { ids: List<String>, makePrivate: Boolean ->
      setMemesPrivateInternal(ids, makePrivate)
    }

    AsyncFunction("removeMemes") { ids: List<String> -> removeMemesInternal(ids) }

    AsyncFunction("importMemes") { promise: Promise -> startMemeImport(promise) }

    AsyncFunction("setMemeAskForTags") { enabled: Boolean ->
      memeAskForTags = enabled
    }

    AsyncFunction("dismissMemePrompt") { id: String -> memes.dismissPrompt(id) }

    /** Sends a meme with its labels. A private one does not travel: the vault stays here. */
    AsyncFunction("pairingSendMeme") { fingerprint: String, id: String ->
      val item = findMeme(id)?.takeIf { !it.isPrivate } ?: return@AsyncFunction false
      val source = ItemSource(memes.displayName(item), memes.sizeOf(item), null, memes.meme(item)) {
        memes.open(item) ?: throw java.io.IOException("the meme could not be opened")
      }
      pairing().sendMemeToPeer(fingerprint, source)
    }

    // ---- Music library (in-app audio player) ----

    Function("isSoundsSupported") {
      soundsStore.isSupported()
    }

    // ---- device pairing --------------------------------------------------------
    AsyncFunction("pairingState") {
      pairing()
      pairingStateMap()
    }

    AsyncFunction("pairingStart") {
      pairing().start()
    }

    AsyncFunction("pairingStop") {
      pairingCoordinator?.stop()
      true
    }

    AsyncFunction("pairingBeginPairing") { seconds: Int ->
      pairing().beginPairing(seconds)
      true
    }

    AsyncFunction("pairingCancelPairing") {
      pairingCoordinator?.cancelPairing()
      true
    }

    AsyncFunction("pairingConfirm") {
      pairing().confirmPairing()
    }

    AsyncFunction("pairingConnect") { host: String, port: Int ->
      pairing().connectToPeer(host, port)
      true
    }

    AsyncFunction("pairingForget") { fingerprint: String ->
      pairing().forgetPeer(fingerprint)
    }

    AsyncFunction("pairingSetDeviceName") { name: String ->
      pairing().setDeviceName(name)
      true
    }

    AsyncFunction("pairingBrowse") { fingerprint: String ->
      pairing().browsePeer(fingerprint)
    }

    AsyncFunction("pairingFetch") { fingerprint: String, id: String ->
      pairing().fetchItem(fingerprint, id)
    }

    AsyncFunction("pairingSend") { fingerprint: String, songId: String ->
      pairing().sendItemToPeer(fingerprint, songId)
    }

    AsyncFunction("pairingSendUrl") { fingerprint: String, url: String, mediaKind: String ->
      pairing().sendUrlToPeer(fingerprint, url, mediaKind)
    }

    AsyncFunction("pairingCancelTransfer") { fingerprint: String ->
      pairingCoordinator?.cancelTransfer(fingerprint)
      true
    }

    AsyncFunction("pairingClearPeerUrl") {
      lastPeerUrl = ""
      lastPeerMediaKind = ""
      // The screen renders from events. Clearing without one left the prompt drawn from
      // the old state, with both of its buttons calling this again and nothing changing:
      // it looked frozen.
      runCatching { sendEvent("pairingStateChanged", pairingStateMap()) }
      true
    }

    AsyncFunction("pairingSetAutoDownloadLinks") { enabled: Boolean ->
      pairingAutoDownloadLinks = enabled
      runCatching { sendEvent("pairingStateChanged", pairingStateMap()) }
      enabled
    }

    /**
     * Whether any device is paired, answered from the file alone: no identity is made and no
     * socket opened to find out. The app starts pairing at launch only when this is true, so
     * a paired device can reach the phone without the Devices screen being visited first.
     */
    AsyncFunction("pairingHasPeers") {
      val file = java.io.File(requireNotNull(appContext.reactContext).filesDir, "pairing/peers.json")
      file.isFile && runCatching { org.json.JSONArray(file.readText()).length() > 0 }.getOrDefault(false)
    }

    AsyncFunction("listSounds") {
      soundsStore.listLibrary()
    }

    AsyncFunction("importSounds") {
      pickAndImportSoundsInternal()
    }

    AsyncFunction("deleteSounds") { input: Map<String, Any?> ->
      val ids = (input["ids"] as? List<*>)?.mapNotNull { it as? String }.orEmpty()
      soundsStore.deleteSounds(ids)
    }

    AsyncFunction("renameSound") { input: Map<String, Any?> ->
      val id = (input["id"] as? String)?.trim().orEmpty()
      val title = (input["title"] as? String)?.trim().orEmpty()
      soundsStore.renameSound(id, title)
    }

    AsyncFunction("getSoundThumbnail") { input: Map<String, Any?> ->
      val id = (input["id"] as? String)?.trim().orEmpty()
      mapOf("path" to soundsStore.getThumbnailPath(id))
    }

    AsyncFunction("listSoundPlaylists") {
      soundsStore.listPlaylists()
    }

    AsyncFunction("createSoundPlaylist") { input: Map<String, Any?> ->
      val name = (input["name"] as? String)?.trim().orEmpty()
      soundsStore.createPlaylist(name)
    }

    AsyncFunction("renameSoundPlaylist") { input: Map<String, Any?> ->
      val id = (input["id"] as? String)?.trim().orEmpty()
      val name = (input["name"] as? String)?.trim().orEmpty()
      soundsStore.renamePlaylist(id, name)
    }

    AsyncFunction("deleteSoundPlaylist") { input: Map<String, Any?> ->
      val id = (input["id"] as? String)?.trim().orEmpty()
      soundsStore.deletePlaylist(id)
    }

    AsyncFunction("setSoundPlaylistSongs") { input: Map<String, Any?> ->
      val id = (input["id"] as? String)?.trim().orEmpty()
      val songIds = (input["songIds"] as? List<*>)?.mapNotNull { it as? String }.orEmpty()
      soundsStore.setPlaylistSongs(id, songIds)
    }

    AsyncFunction("addSoundsToPlaylists") { input: Map<String, Any?> ->
      val songIds = (input["songIds"] as? List<*>)?.mapNotNull { it as? String }.orEmpty()
      val playlistIds = (input["playlistIds"] as? List<*>)?.mapNotNull { it as? String }.orEmpty()
      soundsStore.addSongsToPlaylists(songIds, playlistIds)
    }

    AsyncFunction("removeSoundsFromPlaylist") { input: Map<String, Any?> ->
      val playlistId = (input["playlistId"] as? String)?.trim().orEmpty()
      val songIds = (input["songIds"] as? List<*>)?.mapNotNull { it as? String }.orEmpty()
      soundsStore.removeSongsFromPlaylist(playlistId, songIds)
    }

    /**
     * Apply a preset to one or more library tracks. A single track is just a batch of
     * one, so the UI has a single path for both. Returns immediately with a renderId;
     * follow `soundPresetProgress` events for the outcome of each track.
     */
    AsyncFunction("applySoundPresets") { input: Map<String, Any?> ->
      val songIds = (input["songIds"] as? List<*>)?.mapNotNull { it as? String }.orEmpty()
      val presetId = (input["presetId"] as? String)?.trim().orEmpty()
      val paramsSpec = (input["paramsSpec"] as? String).orEmpty()
      val titleSuffix = (input["titleSuffix"] as? String).orEmpty()
      if (songIds.isEmpty()) throw IllegalArgumentException("NO_SONGS_SELECTED")
      if (presetId.isBlank()) throw IllegalArgumentException("NO_PRESET")
      startPresetRender(songIds, presetId, paramsSpec, titleSuffix)
    }

    /**
     * Store which presets are applied automatically to a new audio download.
     *
     * TypeScript serialises its preset definitions into this blob; native only replays
     * them. Kept on this side because a download can complete with no JS running.
     */
    /**
     * Write a `.avsbck` backup to a location the user picks.
     *
     * `secrets` is a list of `{ slotId, secret, kind }`. One entry means a single passphrase
     * protects the whole file; several entries give a section its own. `settings` is the
     * TypeScript layer's AsyncStorage blob — the shape of it is deliberately unknown here,
     * so adding a preference never needs a native change.
     *
     * Argon2id runs once per slot and takes around a second, so this must not be called on
     * the main thread; `AsyncFunction` already guarantees that.
     */
    AsyncFunction("createBackup") { input: Map<String, Any?> ->
      val context = requireNotNull(appContext.reactContext)
      @Suppress("UNCHECKED_CAST")
      val wanted = (input["sections"] as? List<String>)?.toSet()
        ?: BackupFormat.ALL_SECTIONS.toSet()
      val secrets = backupSecretsFrom(input["secrets"])
      if (secrets.isEmpty()) {
        return@AsyncFunction mapOf("success" to false, "code" to "BACKUP_NO_SECRET")
      }

      // A backup carrying the vault decrypts every video in it — the widest read of private
      // content the app can perform. It is gated here, in native code, because the screen
      // that starts it asks for nothing at all.
      var exportLease: VaultSession.Lease? = null
      if (wanted.contains(BackupFormat.SECTION_VAULT)) {
        val (granted, reason) = authenticatePrivateAccessInternal("bundleExport")
        if (!granted) {
          return@AsyncFunction mapOf("success" to false, "code" to (reason ?: "PRIVATE_AUTH_FAILED"))
        }
        // A whole-vault export is minutes of decrypting. The lease keeps the key available
        // for the length of it, so the window lapsing part way through cannot abandon it.
        exportLease = runCatching { vaultSession.beginLease(VaultAuthPolicy.OP_BACKUP_EXPORT) }
          .getOrElse {
            return@AsyncFunction mapOf("success" to false, "code" to "PRIVATE_VAULT_LOCKED")
          }
      }

      val picked = pickBackupDocument(
        BackupDocumentActivity.MODE_CREATE,
        (input["suggestedName"] as? String)?.trim()?.ifBlank { null }
          ?: defaultBackupFileName(),
      )
      val uri = picked.uri
        ?: return@AsyncFunction mapOf(
          "success" to false,
          "code" to (picked.code ?: BackupDocumentActivity.CODE_CANCELLED),
        )

      val settingsBlob = (input["settings"] as? String)?.takeIf { it.isNotBlank() }
      val slotFor = { section: String ->
        (input["sectionSlots"] as? Map<*, *>)?.get(section) as? String
          ?: BackupFormat.DEFAULT_KEY_SLOT
      }

      val sections = mutableListOf<BackupContainer.PlannedSection>()
      if (wanted.contains(BackupFormat.SECTION_VAULT)) {
        sections.add(
          BackupSections.plan(
            BackupFormat.SECTION_VAULT,
            BackupPorts.collectVault(backupVaultPort()),
            slotFor(BackupFormat.SECTION_VAULT),
          )
        )
      }
      if (wanted.contains(BackupFormat.SECTION_MUSIC) && soundsStore.isSupported()) {
        sections.add(
          BackupSections.plan(
            BackupFormat.SECTION_MUSIC,
            BackupPorts.collectMusic(backupMusicPort()),
            slotFor(BackupFormat.SECTION_MUSIC),
          )
        )
      }
      if (wanted.contains(BackupFormat.SECTION_MEMES)) {
        sections.add(
          BackupSections.plan(
            BackupFormat.SECTION_MEMES,
            BackupPorts.collectMemes(backupMemesPort()),
            slotFor(BackupFormat.SECTION_MEMES),
          )
        )
      }
      if (wanted.contains(BackupFormat.SECTION_SETTINGS) && settingsBlob != null) {
        sections.add(
          BackupSections.plan(
            BackupFormat.SECTION_SETTINGS,
            BackupPorts.collectSettings(JSONObject(settingsBlob)),
            slotFor(BackupFormat.SECTION_SETTINGS),
          )
        )
      }
      if (wanted.contains(BackupFormat.SECTION_COOKIES)) {
        sections.add(
          BackupSections.plan(
            BackupFormat.SECTION_COOKIES,
            BackupPorts.collectCookies(backupCookiePort()),
            slotFor(BackupFormat.SECTION_COOKIES),
          )
        )
      }

      beginBackupJob(BACKUP_MODE_EXPORT, uri)
      val stats = BackupContainer.Stats()
      try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val failures = context.contentResolver.openOutputStream(Uri.parse(uri))?.use { raw ->
          BufferedOutputStream(raw, PRIVATE_STREAM_BUFFER_BYTES).use { out ->
            BackupContainer.write(
              output = out,
              secrets = secrets,
              sections = sections,
              appVersion = info.versionName ?: "",
              appVersionCode = info.longVersionCode.toInt(),
              createdAt = System.currentTimeMillis(),
              progress = { sectionId, name, index, total ->
                updateBackupJob(sectionId, name, index, total)
              },
              stats = stats,
            )
          }
        } ?: throw IllegalStateException("BACKUP_WRITE_FAILED")

        // Counts and timings only — no names. Always logged, so a slow run can be
        // diagnosed from a single line without asking the user to reproduce anything.
        Log.i(tag, stats.summary("export"))
        failures.forEach {
          addError("BACKUP_EXPORT_ITEM_FAILED: ${it.sectionId}/${it.name}: ${it.error}")
        }
        endBackupJob(
          JSONObject().apply {
            put("mode", BACKUP_MODE_EXPORT)
            put("success", true)
            put("summary", "${sections.sumOf { s -> s.itemCount } - failures.size} items")
          }
        )
        mapOf(
          "success" to true,
          "uri" to uri,
          "sections" to sections.map {
            mapOf("id" to it.id, "itemCount" to it.itemCount, "plaintextBytes" to it.plaintextBytes)
          },
          // Items whose source vanished or became unreadable while the backup was being
          // written. The file is still valid; these entries are marked and a restore skips
          // them rather than writing truncated content.
          "failed" to failures.map {
            mapOf("section" to it.sectionId, "name" to it.name, "error" to it.error)
          },
          "perf" to backupPerfMap(stats),
        )
      } catch (error: Throwable) {
        // A half-written backup is worse than none — it looks restorable and is not.
        runCatching { DocumentsContract.deleteDocument(context.contentResolver, Uri.parse(uri)) }
        val message = error.message ?: "BACKUP_WRITE_FAILED"
        endBackupJob(
          JSONObject().apply {
            put("mode", BACKUP_MODE_EXPORT)
            put("success", false)
            put("summary", message)
          }
        )
        Log.e(tag, "Backup export failed: ${error.javaClass.simpleName}: $message", error)
        addError("BACKUP_EXPORT_FAILED: ${error.javaClass.simpleName}: $message")
        mapOf("success" to false, "code" to "BACKUP_WRITE_FAILED", "message" to message)
      } finally {
        secrets.forEach { BackupCrypto.wipe(it.secret) }
        vaultSession.endLease(exportLease)
      }
    }

    /**
     * Let the user pick a backup and read only its plaintext header.
     *
     * No secret is involved: the header states what the file holds and what kind of secret
     * each slot expects, which is what the import screen needs before it can ask for one.
     */
    /** Lets a screen reattach to a job that started before it was opened. */
    AsyncFunction("getBackupJobState") {
      backupJobStateMap()
    }

    AsyncFunction("previewBackup") {
      val context = requireNotNull(appContext.reactContext)
      val picked = pickBackupDocument(BackupDocumentActivity.MODE_OPEN, null)
      val uri = picked.uri
        ?: return@AsyncFunction mapOf(
          "success" to false,
          "code" to (picked.code ?: BackupDocumentActivity.CODE_CANCELLED),
        )

      try {
        val header = context.contentResolver.openInputStream(Uri.parse(uri))?.use {
          BackupContainer.peek(BufferedInputStream(it, PRIVATE_STREAM_BUFFER_BYTES))
        } ?: throw IllegalStateException("BACKUP_READ_FAILED")

        mapOf(
          "success" to true,
          "uri" to uri,
          "createdAt" to header.createdAt,
          "appVersion" to header.appVersion,
          "appVersionCode" to header.appVersionCode,
          "sections" to header.sections.map {
            mapOf(
              "id" to it.id,
              "keySlot" to it.keySlot,
              "itemCount" to it.itemCount,
              "plaintextBytes" to it.plaintextBytes,
            )
          },
          "keySlots" to header.keySlots.map {
            mapOf("id" to it.id, "secretKind" to it.secretKind)
          },
        )
      } catch (error: Throwable) {
        mapOf(
          "success" to false,
          "code" to "BACKUP_READ_FAILED",
          "message" to (error.message ?: "BACKUP_READ_FAILED"),
        )
      }
    }

    /**
     * Restore from a previewed backup.
     *
     * Import is incremental: an item that cannot be written is reported and the rest carry
     * on, so one bad file never abandons a restore. Settings come back in the result for the
     * TypeScript layer to write into AsyncStorage — nothing here knows their shape.
     */
    AsyncFunction("restoreBackup") { input: Map<String, Any?> ->
      val context = requireNotNull(appContext.reactContext)
      val uri = (input["uri"] as? String)?.trim().orEmpty()
      if (uri.isBlank()) {
        return@AsyncFunction mapOf("success" to false, "code" to "BACKUP_NO_FILE")
      }
      @Suppress("UNCHECKED_CAST")
      val wanted = (input["sections"] as? List<String>)?.toSet()
        ?: BackupFormat.ALL_SECTIONS.toSet()
      val secrets = backupSecretsFrom(input["secrets"])
      if (secrets.isEmpty()) {
        return@AsyncFunction mapOf("success" to false, "code" to "BACKUP_NO_SECRET")
      }

      // Restoring a vault section writes into the vault. Narrower than an export, but it is
      // still the private store being changed by whoever holds the phone.
      if (wanted.contains(BackupFormat.SECTION_VAULT)) {
        val (granted, reason) = authenticatePrivateAccessInternal("bundleImport")
        if (!granted) {
          return@AsyncFunction mapOf("success" to false, "code" to (reason ?: "PRIVATE_AUTH_FAILED"))
        }
      }

      val results = mutableListOf<BackupSections.ItemResult>()
      val settingsTarget = BackupPorts.SettingsTarget()

      // Nothing may sit between this and the try. Anything that throws in the gap leaves
      // the job marked active with no matching end, so the foreground notification stays
      // pinned on a phase that finished — which is precisely the symptom this chases.
      beginBackupJob(BACKUP_MODE_RESTORE, null)
      val stats = BackupContainer.Stats()
      try {
        clearBackupStaging()
        val staging = backupStaging()
        val targets = mapOf(
          BackupFormat.SECTION_VAULT to BackupPorts.vaultTarget(backupVaultPort(), staging),
          BackupFormat.SECTION_MUSIC to BackupPorts.musicTarget(backupMusicPort(), staging),
          BackupFormat.SECTION_MEMES to BackupPorts.memesTarget(backupMemesPort(), staging),
          BackupFormat.SECTION_SETTINGS to settingsTarget,
          BackupFormat.SECTION_COOKIES to BackupPorts.cookieTarget(backupCookiePort()),
        )
        context.contentResolver.openInputStream(Uri.parse(uri))?.use { raw ->
          val stream = BufferedInputStream(raw, PRIVATE_STREAM_BUFFER_BYTES)
          val header = BackupContainer.peek(stream)
          val restorable = wanted.filter { targets.containsKey(it) }.toSet()
          BackupContainer.read(
            stream,
            header,
            secrets,
            restorable,
            progress = { sectionId, name, index, total ->
              updateBackupJob(sectionId, name, index, total)
            },
            stats = stats,
          ) { entry ->
            val target = targets[entry.sectionId]
            if (target != null) {
              results.add(BackupSections.restoreEntry(entry, target))
            }
          }
        } ?: throw IllegalStateException("BACKUP_READ_FAILED")

        Log.i(tag, stats.summary("restore"))
        mapOf(
          "success" to true,
          "perf" to backupPerfMap(stats),
          "settings" to settingsTarget.settings?.toString(),
          // "restored" counts files added to a library and nothing else. Playlists,
          // preferences and cover art are reported as "applied", because a restore that
          // changed nothing used to claim it had added dozens of records.
          "restored" to results.count { it.outcome == BackupSections.ItemOutcome.RESTORED },
          "applied" to results.count { it.outcome == BackupSections.ItemOutcome.APPLIED },
          "skippedDuplicates" to
            results.count { it.outcome == BackupSections.ItemOutcome.SKIPPED_DUPLICATE },
          "skippedExisting" to
            results.count { it.outcome == BackupSections.ItemOutcome.SKIPPED_EXISTS },
          "failed" to results.count { it.outcome == BackupSections.ItemOutcome.FAILED },
          "items" to results.map {
            mapOf(
              "section" to it.sectionId,
              "name" to it.name,
              "outcome" to it.outcome.name,
              "error" to it.error,
            )
          },
        )
      } catch (error: BackupSecretException) {
        mapOf(
          "success" to false,
          "code" to "BACKUP_WRONG_SECRET",
          "message" to (error.message ?: "BACKUP_WRONG_SECRET"),
        )
      } catch (error: Throwable) {
        val message = error.message ?: "BACKUP_READ_FAILED"
        Log.e(tag, "Backup restore failed: ${error.javaClass.simpleName}: $message", error)
        addError("BACKUP_RESTORE_FAILED: ${error.javaClass.simpleName}: $message")
        mapOf(
          "success" to false,
          "code" to "BACKUP_READ_FAILED",
          "message" to message,
          // Whatever landed before the failure stays landed; the report says what that was.
          "items" to results.map {
            mapOf("section" to it.sectionId, "name" to it.name, "outcome" to it.outcome.name)
          },
        )
      } finally {
        endBackupJob(
          JSONObject().apply {
            put("mode", BACKUP_MODE_RESTORE)
            put("success", results.none { it.outcome == BackupSections.ItemOutcome.FAILED })
            put(
              "summary",
              "${results.count { it.outcome == BackupSections.ItemOutcome.RESTORED }} added",
            )
          }
        )
        secrets.forEach { BackupCrypto.wipe(it.secret) }
        clearBackupStaging()
      }
    }

    /**
     * Bytes from the platform CSPRNG, for generating a backup passphrase.
     *
     * Lives here because there is no `crypto.getRandomValues` in this runtime and no
     * crypto dependency on the JS side. `Math.random` is not an option: a passphrase drawn
     * from it is only as unguessable as its seed, which would quietly undo the point of
     * generating one.
     */
    AsyncFunction("getSecureRandomBytes") { input: Map<String, Any?> ->
      val count = ((input["count"] as? Number)?.toInt() ?: 0).coerceIn(0, 4096)
      val bytes = ByteArray(count).also { java.security.SecureRandom().nextBytes(it) }
      // Unsigned, so the JS side does not have to undo Kotlin's signed bytes.
      mapOf("bytes" to bytes.map { it.toInt() and 0xFF })
    }

    AsyncFunction("setAutoPresetConfig") { input: Map<String, Any?> ->
      val context = requireNotNull(appContext.reactContext)
      val json = (input["config"] as? String).orEmpty()
      context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putString(PREF_AUTO_PRESETS, json.ifBlank { null })
        .apply()
      mapOf("success" to true)
    }

    AsyncFunction("getAutoPresetConfig") {
      val context = requireNotNull(appContext.reactContext)
      mapOf(
        "config" to context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
          .getString(PREF_AUTO_PRESETS, null)
      )
    }

    AsyncFunction("cancelSoundPresetRender") { input: Map<String, Any?> ->
      val renderId = (input["renderId"] as? String)?.trim().orEmpty()
      mapOf("success" to cancelPresetRender(renderId))
    }

    AsyncFunction("getAudioPresetDiagnostics") {
      val ffmpegInfo = getOrResolveFfmpegInfo()
      mapOf(
        "nativeAvailable" to expo.modules.localdownloader.audio.AudioPresets.isAvailable,
        "nativeVersion" to expo.modules.localdownloader.audio.AudioPresets.version(),
        "ffmpegPath" to ffmpegInfo.path,
        "ffprobePath" to ffmpegInfo.ffprobePath,
      )
    }

    AsyncFunction("setSoundsFavorite") { input: Map<String, Any?> ->
      val songIds = (input["songIds"] as? List<*>)?.mapNotNull { it as? String }.orEmpty()
      val favorite = (input["favorite"] as? Boolean) ?: true
      soundsStore.setSoundsFavorite(songIds, favorite)
    }

    AsyncFunction("makeVideoPublic") { input: Map<String, Any?> ->
      val id = (input["id"] as? String)?.trim().orEmpty()
      if (id.isBlank()) {
        return@AsyncFunction mapOf(
          "success" to false,
          "code" to "PRIVATE_EXPORT_DISABLED",
          "message" to "PRIVATE_EXPORT_DISABLED"
        )
      }
      makeVideoPublicInternal(id)
    }

    AsyncFunction("preparePrivatePlayback") { input: Map<String, Any?> ->
      val id = (input["id"] as? String)?.trim().orEmpty()
      val traceId = (input["traceId"] as? String)?.trim().orEmpty().ifBlank { "n/a" }
      if (id.isBlank()) {
        privateTrace(traceId, "prepare bridge rejected blank id")
        return@AsyncFunction mapOf("success" to false)
      }
      val startedAt = System.currentTimeMillis()
      privateTrace(traceId, "prepare bridge start id=$id")
      try {
        val result = preparePrivatePlaybackInternal(id, traceId)
        privateTrace(
          traceId,
          "prepare bridge success id=$id elapsedMs=${System.currentTimeMillis() - startedAt} tempUri=${result["tempUri"] ?: "n/a"}"
        )
        result
      } catch (error: Throwable) {
        privateTrace(
          traceId,
          "prepare bridge failed id=$id elapsedMs=${System.currentTimeMillis() - startedAt} error=${error.javaClass.simpleName}:${error.message}"
        )
        throw error
      }
    }

    AsyncFunction("setSecureScreen") { input: Map<String, Any?> ->
      val enabled = (input["enabled"] as? Boolean) ?: true
      setSecureScreenInternal(enabled)
      mapOf("success" to true)
    }

    AsyncFunction("clearPrivatePlaybackCache") {
      cleanupPrivatePlaybackCacheInternal()
    }

    AsyncFunction("renamePrivateVideo") { input: Map<String, Any?> ->
      val id = (input["id"] as? String)?.trim().orEmpty()
      val title = (input["title"] as? String).orEmpty()
      renamePrivateVideoInternal(id, title)
    }

    AsyncFunction("listVaultTags") {
      listTagsInternal()
    }

    AsyncFunction("createVaultTag") { input: Map<String, Any?> ->
      val name = (input["name"] as? String).orEmpty()
      val color = (input["color"] as? String)?.trim()
      createTagInternal(name, color)
    }

    AsyncFunction("renameVaultTag") { input: Map<String, Any?> ->
      val id = (input["id"] as? String)?.trim().orEmpty()
      val name = (input["name"] as? String).orEmpty()
      renameTagInternal(id, name)
    }

    AsyncFunction("setVaultTagColor") { input: Map<String, Any?> ->
      val id = (input["id"] as? String)?.trim().orEmpty()
      val color = (input["color"] as? String).orEmpty()
      setTagColorInternal(id, color)
    }

    AsyncFunction("deleteVaultTag") { input: Map<String, Any?> ->
      val id = (input["id"] as? String)?.trim().orEmpty()
      deleteTagInternal(id)
    }

    AsyncFunction("setVaultEntryTags") { input: Map<String, Any?> ->
      @Suppress("UNCHECKED_CAST")
      val entryIds = (input["ids"] as? List<String>) ?: emptyList()
      @Suppress("UNCHECKED_CAST")
      val tagIds = (input["tagIds"] as? List<String>) ?: emptyList()
      setEntryTagsInternal(entryIds, tagIds)
    }

    AsyncFunction("listVaultFolders") {
      listFoldersInternal()
    }

    AsyncFunction("createVaultFolder") { input: Map<String, Any?> ->
      val name = (input["name"] as? String).orEmpty()
      createFolderInternal(name)
    }

    AsyncFunction("renameVaultFolder") { input: Map<String, Any?> ->
      val id = (input["id"] as? String)?.trim().orEmpty()
      val name = (input["name"] as? String).orEmpty()
      renameFolderInternal(id, name)
    }

    AsyncFunction("deleteVaultFolder") { input: Map<String, Any?> ->
      val id = (input["id"] as? String)?.trim().orEmpty()
      deleteFolderInternal(id)
    }

    AsyncFunction("setVaultEntryFolder") { input: Map<String, Any?> ->
      @Suppress("UNCHECKED_CAST")
      val entryIds = (input["ids"] as? List<String>) ?: emptyList()
      val folderId = (input["folderId"] as? String)?.trim()?.ifBlank { null }
      setEntryFolderInternal(entryIds, folderId)
    }

    AsyncFunction("getPrivateThumbnailUri") { input: Map<String, Any?> ->
      val id = (input["id"] as? String)?.trim().orEmpty()
      if (id.isBlank()) {
        return@AsyncFunction mapOf("success" to false, "code" to "PRIVATE_VIDEO_NOT_FOUND")
      }
      getPrivateThumbnailUriInternal(id)
    }

    AsyncFunction("startPrivateVaultMigration") {
      startPrivateVaultMigrationInternal()
    }

    AsyncFunction("cancelPrivateVaultMigration") {
      cancelPrivateVaultMigrationInternal()
    }

    AsyncFunction("getPrivateVaultMigrationStatus") {
      val progress = lastMigrationProgress
      val running = activeMigrationCancel?.let { !it.isCancelled() } ?: false
      mapOf(
        "running" to running,
        "total" to (progress?.total ?: 0),
        "processed" to (progress?.processed ?: 0),
        "succeeded" to (progress?.succeeded ?: 0),
        "failed" to (progress?.failed ?: 0),
        "skipped" to (progress?.skipped ?: 0),
        "currentEntryId" to progress?.currentEntryId,
        "currentTitle" to progress?.currentTitle,
        "lastErrorCode" to progress?.lastError?.code,
        "lastErrorDetail" to progress?.lastError?.detail,
      )
    }

    AsyncFunction("getVaultDiagnostics") {
      val server = vaultLoopbackServer
      val snapshot = server?.snapshot()
      // Counting needs the key now that the listing is encrypted. Nulls rather than zeros
      // when locked: a zero here would read as an empty vault.
      val counts: Triple<Int, Int, Int>? = synchronized(privateVaultLock) {
        runCatching {
          val index = readPrivateVaultIndex()
          val items = index.optJSONArray("items") ?: JSONArray()
          var v3 = 0; var v4 = 0; var other = 0
          for (i in 0 until items.length()) {
            val entry = privateVideoEntryFromJson(items.optJSONObject(i)) ?: continue
            when (entry.cipherVersion) {
              PRIVATE_STORE_VERSION_V4 -> v4 += 1
              PRIVATE_STORE_VERSION_V3 -> v3 += 1
              else -> other += 1
            }
          }
          Triple(v3, v4, other)
        }.getOrNull()
      }
      val v3Count = counts?.first
      val v4Count = counts?.second
      val otherCount = counts?.third
      mapOf(
        "loopbackRunning" to (snapshot?.isRunning == true),
        "loopbackPort" to snapshot?.port,
        "activeVideoSessions" to (snapshot?.activeVideoSessions ?: 0),
        "evictedVideoSessions" to (snapshot?.evictedVideoSessions ?: 0),
        "cipherCounts" to mapOf(
          "v4" to v4Count,
          "v3" to v3Count,
          "other" to otherCount,
        ),
        "lock" to privateVaultLockStateInternal(),
        "migration" to mapOf(
          "running" to (activeMigrationCancel?.let { !it.isCancelled() } ?: false),
          "lastProcessed" to lastMigrationProgress?.processed,
          "lastTotal" to lastMigrationProgress?.total,
          "lastErrorCode" to lastMigrationProgress?.lastError?.code,
        ),
      )
    }

    AsyncFunction("importCookie") { input: Map<String, String> ->
      val platform = input["platform"]?.trim().orEmpty().lowercase()
      val uri = input["uri"] ?: throw IllegalArgumentException("Missing uri")
      val profileNameRaw = input["profileName"] ?: throw IllegalArgumentException("Missing profileName")

      if (!SUPPORTED_PLATFORMS.contains(platform)) {
        throw IllegalArgumentException("Unsupported platform")
      }

      val profileName = sanitizeProfileName(profileNameRaw)
      val platformDir = secureCookiePlatformDir(platform, create = true)
      val dest = File(platformDir, "$profileName.enc")

      val sourceUri = Uri.parse(uri)
      val resolver = requireNotNull(appContext.reactContext).contentResolver
      val rawContent = resolver.openInputStream(sourceUri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
        ?: throw IllegalArgumentException("Could not open cookie file")
      val normalizedCookieText = normalizeCookieContent(rawContent)

      writeEncryptedCookieFile(dest, normalizedCookieText.toByteArray(Charsets.UTF_8))

      if (readDefaultProfile(platformDir) == null) {
        writeDefaultProfile(platformDir, profileName)
      }

      mapOf(
        "profileName" to profileName,
        "path" to dest.absolutePath
      )
    }

    AsyncFunction("listCookieProfiles") { platform: String ->
      val normalized = platform.lowercase()
      if (!SUPPORTED_PLATFORMS.contains(normalized)) {
        return@AsyncFunction emptyList<Map<String, Any>>()
      }

      val platformDir = secureCookiePlatformDir(normalized, create = false)
      if (!platformDir.exists()) {
        return@AsyncFunction emptyList<Map<String, Any>>()
      }

      platformDir.listFiles()
        ?.filter { it.isFile && it.extension == "enc" }
        ?.sortedByDescending { it.lastModified() }
        ?.map {
          mapOf(
            "profileName" to it.nameWithoutExtension,
            "path" to it.absolutePath,
            "lastModified" to it.lastModified()
          )
        } ?: emptyList()
    }

    AsyncFunction("setCookieDefault") { input: Map<String, String> ->
      val platform = input["platform"]?.trim().orEmpty().lowercase()
      val profileName = input["profileName"]?.trim().orEmpty()

      if (!SUPPORTED_PLATFORMS.contains(platform) || profileName.isBlank()) {
        return@AsyncFunction mapOf("success" to false)
      }

      val platformDir = secureCookiePlatformDir(platform, create = false)
      if (!platformDir.exists()) {
        return@AsyncFunction mapOf("success" to false)
      }

      val profileExists = platformDir.listFiles()
        ?.any { it.isFile && it.nameWithoutExtension == profileName && it.extension == "enc" }
        ?: false

      if (!profileExists) {
        return@AsyncFunction mapOf("success" to false)
      }

      writeDefaultProfile(platformDir, profileName)
      mapOf("success" to true)
    }

    AsyncFunction("deleteCookieProfile") { input: Map<String, String> ->
      val platform = input["platform"]?.trim().orEmpty().lowercase()
      val profileName = sanitizeProfileName(input["profileName"].orEmpty())

      if (!SUPPORTED_PLATFORMS.contains(platform) || profileName.isBlank()) {
        return@AsyncFunction mapOf("success" to false)
      }

      val platformDir = secureCookiePlatformDir(platform, create = false)
      if (!platformDir.exists()) {
        return@AsyncFunction mapOf("success" to false)
      }

      val targetFile = File(platformDir, "$profileName.enc")
      if (!targetFile.exists() || !targetFile.isFile) {
        return@AsyncFunction mapOf("success" to false)
      }

      if (!targetFile.delete()) {
        return@AsyncFunction mapOf("success" to false)
      }

      val remaining = platformDir.listFiles()
        ?.filter { it.isFile && it.extension == "enc" }
        ?.sortedByDescending { it.lastModified() }
        ?: emptyList()

      if (remaining.isEmpty()) {
        clearDefaultProfile(platformDir)
        return@AsyncFunction mapOf("success" to true)
      }

      val defaultProfile = readDefaultProfile(platformDir)
      if (defaultProfile == null || defaultProfile == profileName) {
        writeDefaultProfile(platformDir, remaining.first().nameWithoutExtension)
      }

      mapOf("success" to true)
    }

    AsyncFunction("getCookieDefaults") {
      val cookiesRoot = secureCookiesRoot(create = false)
      SUPPORTED_PLATFORMS.associateWith { platform ->
        val platformDir = File(cookiesRoot, platform)
        if (!platformDir.exists()) {
          null
        } else {
          readDefaultProfile(platformDir)
        }
      }
    }

    AsyncFunction("importCustomCookie") { input: Map<String, Any?> ->
      val uri = (input["uri"] as? String)?.trim().orEmpty()
      val profileNameRaw = (input["profileName"] as? String)?.trim()
      val manualDomainRaw = (input["domain"] as? String)?.trim()

      if (uri.isBlank()) {
        throw IllegalArgumentException("INVALID_URL")
      }

      val manualDomain = if (manualDomainRaw.isNullOrBlank()) {
        null
      } else {
        canonicalizeDomain(manualDomainRaw) ?: throw IllegalArgumentException("INVALID_CUSTOM_DOMAIN")
      }

      val sourceUri = Uri.parse(uri)
      val resolver = requireNotNull(appContext.reactContext).contentResolver
      val rawContent = resolver.openInputStream(sourceUri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
        ?: throw IllegalArgumentException("Could not open cookie file")
      val normalizedCookieText = normalizeCookieContent(rawContent)

      val detectedDomains = extractDomainsFromCookieText(normalizedCookieText).toMutableSet()
      if (manualDomain != null) {
        detectedDomains.add(manualDomain)
      }
      if (detectedDomains.isEmpty()) {
        throw IllegalStateException("CUSTOM_COOKIE_NO_DOMAIN_DETECTED")
      }

      val profileNameSeed = if (profileNameRaw.isNullOrBlank()) {
        "custom_${System.currentTimeMillis()}"
      } else {
        profileNameRaw
      }
      val profileId = UUID.randomUUID().toString()
      val payload = normalizedCookieText.toByteArray(Charsets.UTF_8)
      val boundDomains = detectedDomains.toList().sorted()

      val finalProfileName = synchronized(customCookieIndexLock) {
        val index = readCustomCookieIndex()
        val uniqueProfileName = ensureUniqueCustomProfileName(index, boundDomains, sanitizeProfileName(profileNameSeed))
        val profileFile = customProfileFile(profileId)
        writeEncryptedCookieFile(profileFile, payload)

        val now = System.currentTimeMillis()
        val profilesObj = index.getJSONObject("profiles")
        profilesObj.put(
          profileId,
          JSONObject().apply {
            put("profileName", uniqueProfileName)
            put("createdAt", now)
            put("updatedAt", now)
            put("domains", JSONArray(boundDomains))
          }
        )

        val domainsObj = index.getJSONObject("domains")
        boundDomains.forEach { domain ->
          val domainEntry = domainsObj.optJSONObject(domain) ?: JSONObject()
          val profileIds = domainEntry.optJSONArray("profileIds") ?: JSONArray()
          if (!jsonArrayContains(profileIds, profileId)) {
            profileIds.put(profileId)
          }
          domainEntry.put("profileIds", profileIds)
          domainsObj.put(domain, domainEntry)

          val domainDir = customDomainDir(domain, create = true)
          if (readDefaultProfile(domainDir) == null) {
            writeDefaultProfile(domainDir, uniqueProfileName)
          }
        }

        writeCustomCookieIndex(index)
        uniqueProfileName
      }

      mapOf(
        "profileId" to profileId,
        "profileName" to finalProfileName,
        "detectedDomains" to detectedDomains.toList().sorted(),
        "boundDomains" to boundDomains,
      )
    }

    AsyncFunction("listCustomDomains") {
      synchronized(customCookieIndexLock) {
        val index = readCustomCookieIndex()
        val domainsObj = index.getJSONObject("domains")
        val profilesObj = index.getJSONObject("profiles")
        val result = mutableListOf<Map<String, Any?>>()
        val domainKeys = domainsObj.keys()
        while (domainKeys.hasNext()) {
          val domain = domainKeys.next()
          val domainEntry = domainsObj.optJSONObject(domain) ?: JSONObject()
          val ids = jsonArrayToStringList(domainEntry.optJSONArray("profileIds"))
          val validProfileIds = ids.filter { profilesObj.has(it) }
          val defaultProfileName = readDefaultProfile(customDomainDir(domain, create = false))
            ?.takeIf { defaultName -> validProfileIds.any { id -> profilesObj.optJSONObject(id)?.optString("profileName") == defaultName } }

          result.add(
            mapOf(
              "domain" to domain,
              "profileCount" to validProfileIds.size,
              "defaultProfileName" to defaultProfileName
            )
          )
        }

        result.sortedBy { it["domain"] as String }
      }
    }

    AsyncFunction("listCustomDomainProfiles") { domain: String ->
      val normalizedDomain = canonicalizeDomain(domain) ?: return@AsyncFunction emptyList<Map<String, Any?>>()
      synchronized(customCookieIndexLock) {
        val index = readCustomCookieIndex()
        val domainsObj = index.getJSONObject("domains")
        val profilesObj = index.getJSONObject("profiles")
        val domainEntry = domainsObj.optJSONObject(normalizedDomain) ?: return@synchronized emptyList<Map<String, Any?>>()
        val profileIds = jsonArrayToStringList(domainEntry.optJSONArray("profileIds"))

        profileIds.mapNotNull { profileId ->
          val profile = profilesObj.optJSONObject(profileId) ?: return@mapNotNull null
          val profileName = sanitizeProfileName(profile.optString("profileName"))
          if (profileName.isBlank()) {
            return@mapNotNull null
          }
          val lastModified = profile.optLong("updatedAt", 0L).takeIf { it > 0L } ?: customProfileFile(profileId).lastModified()
          mapOf(
            "profileName" to profileName,
            "profileId" to profileId,
            "lastModified" to lastModified,
          )
        }.sortedByDescending { it["lastModified"] as Long }
      }
    }

    AsyncFunction("setCustomDomainDefault") { input: Map<String, String> ->
      val domain = canonicalizeDomain(input["domain"].orEmpty())
      val profileName = sanitizeProfileName(input["profileName"].orEmpty())
      if (domain == null || profileName.isBlank()) {
        return@AsyncFunction mapOf("success" to false)
      }

      synchronized(customCookieIndexLock) {
        val index = readCustomCookieIndex()
        val domainsObj = index.getJSONObject("domains")
        val profilesObj = index.getJSONObject("profiles")
        val domainEntry = domainsObj.optJSONObject(domain) ?: return@synchronized mapOf("success" to false)
        val profileIds = jsonArrayToStringList(domainEntry.optJSONArray("profileIds"))

        val exists = profileIds.any { profileId ->
          profilesObj.optJSONObject(profileId)?.optString("profileName") == profileName
        }
        if (!exists) {
          return@synchronized mapOf("success" to false)
        }

        writeDefaultProfile(customDomainDir(domain, create = true), profileName)
        mapOf("success" to true)
      }
    }

    AsyncFunction("deleteCustomDomainProfile") { input: Map<String, String> ->
      val domain = canonicalizeDomain(input["domain"].orEmpty())
      val profileName = sanitizeProfileName(input["profileName"].orEmpty())
      if (domain == null || profileName.isBlank()) {
        return@AsyncFunction mapOf("success" to false)
      }

      synchronized(customCookieIndexLock) {
        val index = readCustomCookieIndex()
        val domainsObj = index.getJSONObject("domains")
        val profilesObj = index.getJSONObject("profiles")
        val domainEntry = domainsObj.optJSONObject(domain) ?: return@synchronized mapOf("success" to false)
        val domainProfileIds = jsonArrayToStringList(domainEntry.optJSONArray("profileIds"))
        val targetProfileId = domainProfileIds.firstOrNull { profileId ->
          profilesObj.optJSONObject(profileId)?.optString("profileName") == profileName
        } ?: return@synchronized mapOf("success" to false)

        val targetProfileObj = profilesObj.optJSONObject(targetProfileId)
        val boundDomains = jsonArrayToStringList(targetProfileObj?.optJSONArray("domains"))

        boundDomains.forEach { boundDomain ->
          val boundEntry = domainsObj.optJSONObject(boundDomain) ?: return@forEach
          val remainingIds = jsonArrayToStringList(boundEntry.optJSONArray("profileIds"))
            .filter { it != targetProfileId }
          if (remainingIds.isEmpty()) {
            domainsObj.remove(boundDomain)
            customDomainDir(boundDomain, create = false).deleteRecursively()
          } else {
            boundEntry.put("profileIds", JSONArray(remainingIds))
            domainsObj.put(boundDomain, boundEntry)
            ensureCustomDomainDefault(boundDomain, index)
          }
        }

        profilesObj.remove(targetProfileId)
        runCatching { customProfileFile(targetProfileId).delete() }
        writeCustomCookieIndex(index)
        mapOf("success" to true)
      }
    }

    AsyncFunction("saveToMediaStore") { input: Map<String, Any?> ->
      val filePath = (input["filePath"] as? String)?.trim().orEmpty()
      val filename = (input["filename"] as? String)?.trim().orEmpty()
      if (filePath.isBlank() || filename.isBlank()) {
        throw IllegalArgumentException("FILE_NOT_FOUND")
      }
      val mimeType = (input["mimeType"] as? String)?.trim()?.takeIf { it.isNotBlank() } ?: guessMimeType(filename)
      val dateTakenMs = (input["dateTakenMs"] as? Number)?.toLong() ?: System.currentTimeMillis()
      val saved = saveToMediaStoreInternal(filePath, filename, mimeType, dateTakenMs)
      // Only downloads come through here, so a video or an image is a meme.
      (saved["uri"] as? String)?.let { adoptMeme(it, mimeType, pendingMemeSources.remove(filePath), ask = true) }
      saved
    }

    AsyncFunction("getYtDlpUpdateStatus") {
      getYtDlpUpdateStatusInternal(includeLatest = false)
    }

    AsyncFunction("getDownloadFailureLogs") {
      readDownloadFailureLogsInternal()
    }

    AsyncFunction("checkYtDlpUpdate") {
      checkYtDlpUpdateInternal()
    }

    AsyncFunction("updateYtDlp") { version: String? ->
      updateYtDlpInternal(version?.takeIf { it.isNotBlank() })
    }

    AsyncFunction("listYtDlpVersions") {
      listYtDlpVersionsInternal()
    }

    AsyncFunction("restartApp") {
      restartAppInternal()
    }

    AsyncFunction("clearYtDlpOverride") {
      clearYtDlpOverrideInternal()
    }

    AsyncFunction("getDiagnostics") {
      val ffmpegInfo = getOrResolveFfmpegInfo(forceRefresh = true)

      var ytDlpVersion = "unknown"
      var ytDlpAvailable = false
      var pythonReady = Python.isStarted()
      var normalizedUrlLast: String? = null
      var attemptTraceCount = 0
      var attemptTrace: List<Map<String, Any?>> = emptyList()
      var lastExtractorKey: String? = null
      var lastRawYtDlpError: String? = null
      var platformStrategyLast: String? = null
      var ytDlpVersionAgeDays: Int? = null
      var lastCookieCheck: Map<String, Any?>? = null
      var impersonationRuntimeAvailable: Boolean? = null
      var impersonationEnabled: Boolean = false
      var impersonationBackend: String = "none"
      var impersonationRequiredByExtractorLast: String? = null
      var impersonationAttemptedTargetsLast: List<String> = emptyList()
      var impersonationResolvedTargetLast: String? = null
      var impersonationWheelVersion: String? = null
      var impersonationBuildAbiCoverage: List<String> = emptyList()
      var impersonationBootstrapError: String? = null
      var ytDlpBundledVersion: String? = null
      var ytDlpActiveVersion: String? = null
      var ytDlpOverrideVersion: String? = null
      var ytDlpPendingVersion: String? = null
      var ytDlpFailedVersion: String? = null
      var ytDlpFailedReason: String? = null
      var ytDlpOverrideSource: String = "bundled"
      var ytDlpOverridePath: String? = null
      var ytDlpOverrideStorageReady: Boolean = false

      runCatching {
        ensurePythonReady()
        pythonReady = true
        val py = Python.getInstance()
        ytDlpVersion = py.getModule("yt_dlp.version").get("__version__").toString()
        ytDlpAvailable = true
        val updateStatus = buildYtDlpUpdateStatusMap(fetchActiveFromPython = false)
        ytDlpBundledVersion = updateStatus["bundledVersion"] as? String
        ytDlpActiveVersion = updateStatus["activeVersion"] as? String
        ytDlpOverrideVersion = updateStatus["overrideVersion"] as? String
        ytDlpPendingVersion = updateStatus["pendingVersion"] as? String
        ytDlpFailedVersion = updateStatus["failedVersion"] as? String
        ytDlpFailedReason = updateStatus["failedReason"] as? String
        ytDlpOverrideSource = (updateStatus["source"] as? String) ?: "bundled"
        ytDlpOverridePath = updateStatus["overridePath"] as? String
        ytDlpOverrideStorageReady = updateStatus["storageReady"] as? Boolean ?: false

        val runtimeDiagRaw = py.getModule("local_downloader").callAttr("get_runtime_diagnostics").toString()
        val runtimeDiag = JSONObject(runtimeDiagRaw)
        normalizedUrlLast = runtimeDiag.optString("normalizedUrlLast").takeIf { it.isNotBlank() && it != "null" }
        attemptTraceCount = runtimeDiag.optInt("attemptTraceCount", 0)
        lastExtractorKey = runtimeDiag.optString("lastExtractorKey").takeIf { it.isNotBlank() && it != "null" }
        lastRawYtDlpError = runtimeDiag.optString("lastRawYtDlpError").takeIf { it.isNotBlank() && it != "null" }
        platformStrategyLast = runtimeDiag.optString("platformStrategyLast").takeIf { it.isNotBlank() && it != "null" }
        ytDlpVersionAgeDays = runtimeDiag.opt("ytDlpVersionAgeDays")?.toString()?.toIntOrNull()
        if (runtimeDiag.has("impersonationRuntimeAvailable") && !runtimeDiag.isNull("impersonationRuntimeAvailable")) {
          impersonationRuntimeAvailable = runtimeDiag.optBoolean("impersonationRuntimeAvailable")
        }
        if (runtimeDiag.has("impersonationEnabled") && !runtimeDiag.isNull("impersonationEnabled")) {
          impersonationEnabled = runtimeDiag.optBoolean("impersonationEnabled")
        }
        impersonationBackend = runtimeDiag.optString("impersonationBackend", "none").ifBlank { "none" }
        impersonationRequiredByExtractorLast = runtimeDiag.optString("impersonationRequiredByExtractorLast")
          .takeIf { it.isNotBlank() && it != "null" }
        impersonationResolvedTargetLast = runtimeDiag.optString("impersonationResolvedTargetLast")
          .takeIf { it.isNotBlank() && it != "null" }
        impersonationWheelVersion = runtimeDiag.optString("impersonationWheelVersion")
          .takeIf { it.isNotBlank() && it != "null" }
        impersonationBootstrapError = runtimeDiag.optString("impersonationBootstrapError")
          .takeIf { it.isNotBlank() && it != "null" }

        val attemptedTargets = runtimeDiag.optJSONArray("impersonationAttemptedTargetsLast")
        impersonationAttemptedTargetsLast = if (attemptedTargets == null) {
          emptyList()
        } else {
          (0 until attemptedTargets.length()).mapNotNull { i ->
            attemptedTargets.optString(i).takeIf { v -> v.isNotBlank() }
          }
        }
        val abiCoverage = runtimeDiag.optJSONArray("impersonationBuildAbiCoverage")
        impersonationBuildAbiCoverage = if (abiCoverage == null) {
          emptyList()
        } else {
          (0 until abiCoverage.length()).mapNotNull { i ->
            abiCoverage.optString(i).takeIf { v -> v.isNotBlank() }
          }
        }

        val traceArray = runtimeDiag.optJSONArray("attemptTrace")
        attemptTrace = if (traceArray == null) {
          emptyList()
        } else {
          (0 until traceArray.length()).mapNotNull { idx ->
            val item = traceArray.optJSONObject(idx) ?: return@mapNotNull null
            mapOf(
              "timeMs" to item.opt("timeMs"),
              "phase" to item.optString("phase", ""),
              "attemptId" to item.optString("attemptId", ""),
              "strategy" to item.optString("strategy", ""),
              "status" to item.optString("status", ""),
              "platform" to item.optString("platform", ""),
              "cookieUsed" to item.opt("cookieUsed"),
              "retryIndex" to item.opt("retryIndex"),
              "extractorKey" to item.optString("extractorKey", ""),
              "errorCode" to item.optString("errorCode", ""),
              "errorMessage" to item.optString("errorMessage", ""),
              "impersonate" to item.optString("impersonate", ""),
            )
          }
        }

        val cookieCheck = runtimeDiag.optJSONObject("lastCookieCheck")
        lastCookieCheck = cookieCheck?.let {
          mapOf(
            "platform" to it.optString("platform", ""),
            "hasCookieFile" to it.optBoolean("hasCookieFile", false),
            "domainCoverage" to (it.optJSONArray("domainCoverage")?.let { arr ->
              (0 until arr.length()).mapNotNull { i -> arr.optString(i).takeIf { value -> value.isNotBlank() } }
            } ?: emptyList<String>()),
            "unexpiredCount" to it.optInt("unexpiredCount", 0),
          )
        }
      }.onFailure {
        addError("YT_DLP_IMPORT_ERROR: ${it.message}")
      }

      mapOf(
        "ytDlpVersion" to ytDlpVersion,
        "ytDlpAvailable" to ytDlpAvailable,
        "pythonReady" to pythonReady,
        "ytDlpBundledVersion" to ytDlpBundledVersion,
        "ytDlpActiveVersion" to ytDlpActiveVersion,
        "ytDlpOverrideVersion" to ytDlpOverrideVersion,
        "ytDlpPendingVersion" to ytDlpPendingVersion,
        "ytDlpFailedVersion" to ytDlpFailedVersion,
        "ytDlpFailedReason" to ytDlpFailedReason,
        "ytDlpOverrideSource" to ytDlpOverrideSource,
        "ytDlpOverridePath" to ytDlpOverridePath,
        "ytDlpOverrideStorageReady" to ytDlpOverrideStorageReady,
        "ffmpegPath" to ffmpegInfo.path,
        "ffprobePath" to ffmpegInfo.ffprobePath,
        "ffmpegAbi" to ffmpegInfo.abi,
        "ffmpegRuntimeSource" to ffmpegInfo.runtimeSource,
        "nativeLibraryDir" to ffmpegInfo.nativeLibraryDir,
        "nativeLibraryEntries" to ffmpegInfo.nativeLibraryEntries,
        "ffmpegVersion" to ffmpegInfo.version,
        "ffprobeVersion" to ffmpegInfo.ffprobeVersion,
        "ffmpegExists" to ffmpegInfo.exists,
        "ffprobeExists" to ffmpegInfo.ffprobeExists,
        "ffmpegExecutable" to ffmpegInfo.executable,
        "ffprobeExecutable" to ffmpegInfo.ffprobeExecutable,
        "ffmpegProbeError" to ffmpegInfo.ffmpegProbeError,
        "ffprobeProbeError" to ffmpegInfo.ffprobeProbeError,
        "mergeCapable" to ffmpegInfo.mergeCapable,
        "activeHttpUserAgent" to DEFAULT_HTTP_USER_AGENT,
        "secureCookieStoreEnabled" to isSecureCookieStoreEnabled(),
        "cookieEncryptionVersion" to COOKIE_STORE_VERSION,
        "cookieProfilesEncryptedCount" to countSecureCookieProfiles(),
        "customDomainsCount" to countCustomDomains(),
        "customProfilesCount" to countCustomProfiles(),
        "cookieLegacyPlaintextCount" to countLegacyCookieProfiles(),
        "cookieMigrationStatus" to cookieMigrationStatus,
        "normalizedUrlLast" to normalizedUrlLast,
        "attemptTraceCount" to attemptTraceCount,
        "attemptTrace" to attemptTrace,
        "lastExtractorKey" to lastExtractorKey,
        "lastRawYtDlpError" to lastRawYtDlpError,
        "lastCookieCheck" to lastCookieCheck,
        "ytDlpVersionAgeDays" to ytDlpVersionAgeDays,
        "platformStrategyLast" to platformStrategyLast,
        "impersonationRuntimeAvailable" to impersonationRuntimeAvailable,
        "impersonationEnabled" to impersonationEnabled,
        "impersonationBackend" to impersonationBackend,
        "impersonationRequiredByExtractorLast" to impersonationRequiredByExtractorLast,
        "impersonationAttemptedTargetsLast" to impersonationAttemptedTargetsLast,
        "impersonationResolvedTargetLast" to impersonationResolvedTargetLast,
        "impersonationWheelVersion" to impersonationWheelVersion,
        "impersonationBuildAbiCoverage" to impersonationBuildAbiCoverage,
        "impersonationBootstrapError" to impersonationBootstrapError,
        "privateModeEnabled" to privateModeEnabled,
        "privateVaultCount" to countPrivateVaultItems(),
        "privateVaultCipherActive" to PRIVATE_DEFAULT_CIPHER_VERSION,
        "privateVaultLegacyCount" to countPrivateVaultLegacyItems(),
        "privateLastEncryptMs" to privateLastEncryptMs,
        "privateLastDecryptMs" to privateLastDecryptMs,
        "privateLastThroughputMbps" to privateLastThroughputMbps,
        "customDomainMatchLast" to lastCustomDomainMatch?.let {
          mapOf(
            "urlHost" to it.urlHost,
            "matchedDomain" to it.matchedDomain,
            "profileName" to it.profileName
          )
        },
        "activeTaskIds" to activeDownloads.keys.toList(),
        "serviceRunning" to DownloadForegroundService.isRunning,
        "queuedDownloadCount" to queueSize(),
        "lastBackgroundServiceError" to lastBackgroundServiceError,
        "lastErrors" to lastErrors.toList()
      )
    }

    AsyncFunction("runCapabilityCheck") {
      val ffmpegInfo = getOrResolveFfmpegInfo(forceRefresh = true)
      val pythonReady = runCatching {
        ensurePythonReady()
        true
      }.getOrDefault(false)

      mapOf(
        "pythonReady" to pythonReady,
        "ffmpegRuntimeSource" to ffmpegInfo.runtimeSource,
        "ffmpegExists" to ffmpegInfo.exists,
        "ffprobeExists" to ffmpegInfo.ffprobeExists,
        "mergeCapable" to ffmpegInfo.mergeCapable,
        "activeHttpUserAgent" to DEFAULT_HTTP_USER_AGENT
      )
    }

    AsyncFunction("runImpersonationSelfTest") {
      ensurePythonReady()
      val py = Python.getInstance()
      val result = py.getModule("local_downloader").callAttr("run_impersonation_self_test", debugLoggingEnabled)
      val json = JSONObject(result.toString())
      mapOf(
        "success" to json.optBoolean("success", false),
        "code" to json.optString("code", "INTERNAL_ERROR"),
        "message" to json.optString("message").ifBlank { null },
        "impersonation_enabled" to json.optBoolean("impersonation_enabled", false),
        "backend" to json.optString("backend").ifBlank { null },
        "wheel_version" to json.optString("wheel_version").ifBlank { null },
        "build_abi_coverage" to (json.optJSONArray("build_abi_coverage")?.let { arr ->
          (0 until arr.length()).mapNotNull { idx -> arr.optString(idx).takeIf { v -> v.isNotBlank() } }
        } ?: emptyList<String>())
      )
    }
  }

  private fun startDownloadInternal(
    url: String,
    cookiePlatform: String?,
    cookieProfile: String?,
    maxFileSizeMb: Int,
    visibility: String,
    source: String,
    audioOnly: Boolean = false,
  ): Map<String, Any?> {
    if (url.isBlank()) {
      throw IllegalArgumentException("INVALID_URL")
    }

    val reactContext = requireNotNull(appContext.reactContext)

    // Downloading does NOT require notification permission. Posting a notification does.
    // These are separate on Android 13+: with POST_NOTIFICATIONS denied, startForeground()
    // still succeeds and the service runs — the system simply suppresses the notification.
    // Refusing the download here made the app's primary function depend on a permission it
    // does not need, and left no way to decline notifications and still use the app.

    // No longer a "one at a time" gate — several downloads run together and the stage
    // gates decide how many may be in each phase. What is still refused is the same URL
    // twice at once, which is a mistake rather than a request.
    if (activeDownloads.values.any { it.url == url && it.job.isActive }) {
      throw IllegalStateException("DOWNLOAD_ALREADY_IN_PROGRESS")
    }

    val taskId = UUID.randomUUID().toString()
    ignoredTaskResults.remove(taskId)

    val task = TaskState(taskId = taskId, status = "PENDING", url = url)
    tasks[taskId] = task
    persistTaskSnapshot()
    emitProgress(taskId, "PENDING", "starting", "Task created")

    val outputDir = File(reactContext.cacheDir, "local_downloads").apply { mkdirs() }
    val cookiesDir = File(reactContext.filesDir, LEGACY_COOKIES_DIRNAME).apply { mkdirs() }
    val disabledCookiesDir = File(reactContext.filesDir, DISABLED_COOKIES_DIRNAME)
    val cancelFlag = createCancelFlag(taskId)
    val progressFile = createProgressFile(taskId)
    val effectivePlatform = cookiePlatform ?: detectCookiePlatform(url)

    syncForegroundNotification("starting", "Preparing download")
    emitBackgroundStateChanged()

    val job = scope.launch(start = CoroutineStart.LAZY) {
      var runtimeCookiePath: String? = null
      var progressWatcher: Job? = null
      runCatching {
        debug("Task[$taskId] START source=$source visibility=$visibility url=$url platform=$effectivePlatform profile=$cookieProfile maxMb=$maxFileSizeMb")
        updateStatus(taskId, "STARTED", null, null, null, null, null)
        emitProgress(taskId, "STARTED", "starting", "Preflight")

        runtimeCookiePath = prepareRuntimeCookiePath(taskId, url, cookieProfile, effectivePlatform)
        var effectiveCookiePath = runtimeCookiePath
        debug("Task[$taskId] runtimeCookiePath=${runtimeCookiePath ?: "none"}")

        val ffmpegInfo = getOrResolveFfmpegInfo(forceRefresh = true)
        debug("Task[$taskId] ffmpeg info before preflight: ${summarizeFfmpegInfo(ffmpegInfo)}")
        // Resolving a URL is short and CPU-bound Python, so it gets a narrow gate of its
        // own rather than sharing the one that guards the transfer. It also produces the
        // size estimate the transfer gate orders by.
        var preflightResult = stages.preflight.withPermit(PriorityGate.UNKNOWN_PRIORITY) {
          var attempt = callPythonPreflight(
            PreflightPythonInput(
              url = url,
              cookiesDir = cookiesDir.absolutePath,
              cookieProfile = cookieProfile,
              maxFileSizeMb = maxFileSizeMb,
              ffmpegPath = ffmpegInfo.path ?: ffmpegInfo.location,
              cookieFilePath = effectiveCookiePath,
              forceNoCookie = false,
              mergeCapable = ffmpegInfo.mergeCapable,
              userAgent = DEFAULT_HTTP_USER_AGENT,
              debugLogging = debugLoggingEnabled,
            )
          )
          debug("Task[$taskId] preflight result=${redactedForLog(attempt)}")

          if (!attempt.optBoolean("success", false) && shouldRetryWithoutCookies(attempt, effectiveCookiePath, effectivePlatform)) {
            addError("COOKIE_RETRY_PREFLIGHT: task=$taskId")
            cleanupRuntimeCookieTemp(taskId)
            effectiveCookiePath = null
            attempt = callPythonPreflight(
              PreflightPythonInput(
                url = url,
                cookiesDir = disabledCookiesDir.absolutePath,
                cookieProfile = null,
                maxFileSizeMb = maxFileSizeMb,
                ffmpegPath = ffmpegInfo.path ?: ffmpegInfo.location,
                cookieFilePath = null,
                forceNoCookie = true,
                mergeCapable = ffmpegInfo.mergeCapable,
                userAgent = DEFAULT_HTTP_USER_AGENT,
                debugLogging = debugLoggingEnabled,
              )
            )
            debug("Task[$taskId] preflight retry(no-cookie) result=${redactedForLog(attempt)}")
          }
          attempt
        }
        preflightResult = normalizeRuntimeError(preflightResult, ffmpegInfo)
        applyRuntimeDiagnostics(taskId, preflightResult, "preflight")

        if (shouldIgnoreTaskResult(taskId)) {
          return@runCatching
        }

        val estimatedSizeMb = preflightResult.optDouble("estimated_size_mb", Double.NaN)
          .takeIf { !it.isNaN() && it > 0.0 }
        if (estimatedSizeMb != null) {
          tasks[taskId]?.estimatedSizeMb = estimatedSizeMb
          persistTaskSnapshot()
        }

        if (isCancelRequested(taskId)) {
          markCancelled(taskId, "Cancellation confirmed before download start")
          return@runCatching
        }

        if (!preflightResult.optBoolean("success", false) && !preflightResult.optBoolean("retryable_preflight", false)) {
          val code = preflightResult.optString("code", "INTERNAL_ERROR")
          val msg = preflightResult.optString("message", "Preflight failed")
          debug("Task[$taskId] preflight failed code=$code message=$msg")

          if (code == "DOWNLOAD_CANCELLED") {
            markCancelled(taskId, msg)
            return@runCatching
          }

          updateStatus(taskId, "FAILURE", null, null, null, code, msg)
          emitProgress(taskId, "FAILURE", "error", msg)
          addError("$code: $msg")
          return@runCatching
        } else if (!preflightResult.optBoolean("success", false)) {
          val code = preflightResult.optString("code", "PREFLIGHT_FAILED")
          val msg = preflightResult.optString("message", "Preflight warning")
          tasks[taskId]?.preflightWarning = mapOf(
            "code" to code,
            "message" to msg,
            "strategy" to tasks[taskId]?.preflightStrategy
          )
          debug("Task[$taskId] soft preflight failure code=$code message=$msg")
        }

        val freeMb = getFreeSpaceMb(outputDir)
        val requiredFreeMb = if (estimatedSizeMb != null) {
          max(1024.0, estimatedSizeMb * 2.5)
        } else {
          1024.0
        }

        if (freeMb < requiredFreeMb) {
          val msg = "Not enough free storage. Free=${"%.1f".format(freeMb)}MB, Required=${"%.1f".format(requiredFreeMb)}MB"
          debug("Task[$taskId] storage check failed: $msg")
          updateStatus(taskId, "FAILURE", null, null, null, "SERVER_BUSY", msg)
          emitProgress(taskId, "FAILURE", "error", msg)
          addError("SERVER_BUSY: $msg")
          return@runCatching
        }

        // Everything from here to the end of the Python call is one gate: yt-dlp runs its
        // FFmpeg postprocessors at the end of the same call that fetches the bytes, so a
        // transfer and the transcode that follows it cannot be gated separately without
        // taking the postprocessing away from yt-dlp.
        //
        // Ordered by the size estimate the preflight just produced. Size is a proxy for
        // how long the job will take — transfer scales with it, and so does the transcode
        // for a given format — so a short share is admitted ahead of a long download that
        // is already waiting.
        var result = stages.fetch.withPermit(costOf(estimatedSizeMb)) {
          tasks[taskId]?.progressPercent = 0.0
          emitProgress(taskId, "PROGRESS", "downloading", "Downloading media", 0.0)
          updateStatus(taskId, "PROGRESS", null, null, null, null, null)
          progressWatcher = launch {
            observeProgressFile(taskId, progressFile)
          }

          var attempt = callPythonDownload(
            DownloadPythonInput(
              url = url,
              outputDir = outputDir.absolutePath,
              cookiesDir = cookiesDir.absolutePath,
              cookieProfile = cookieProfile,
              maxFileSizeMb = maxFileSizeMb,
              cancelFlagPath = cancelFlag.absolutePath,
              progressFilePath = progressFile.absolutePath,
              ffmpegPath = ffmpegInfo.path ?: ffmpegInfo.location,
              cookieFilePath = effectiveCookiePath,
              forceNoCookie = false,
              mergeCapable = ffmpegInfo.mergeCapable,
              audioOnly = audioOnly,
              audioFormat = audioFormat,
              userAgent = DEFAULT_HTTP_USER_AGENT,
              debugLogging = debugLoggingEnabled,
            )
          )
          debug("Task[$taskId] download result=${redactedForLog(attempt)}")

          if (!attempt.optBoolean("success", false) && shouldRetryWithoutCookies(attempt, effectiveCookiePath, effectivePlatform)) {
            addError("COOKIE_RETRY_DOWNLOAD: task=$taskId")
            emitProgress(taskId, "PROGRESS", "downloading", "Retrying without cookies")
            cleanupRuntimeCookieTemp(taskId)
            effectiveCookiePath = null
            clearProgressFile(progressFile)
            tasks[taskId]?.progressPercent = 0.0
            attempt = callPythonDownload(
              DownloadPythonInput(
                url = url,
                outputDir = outputDir.absolutePath,
                cookiesDir = disabledCookiesDir.absolutePath,
                cookieProfile = null,
                maxFileSizeMb = maxFileSizeMb,
                cancelFlagPath = cancelFlag.absolutePath,
                progressFilePath = progressFile.absolutePath,
                ffmpegPath = ffmpegInfo.path ?: ffmpegInfo.location,
                cookieFilePath = null,
                forceNoCookie = true,
                mergeCapable = ffmpegInfo.mergeCapable,
                audioOnly = audioOnly,
                audioFormat = audioFormat,
                userAgent = DEFAULT_HTTP_USER_AGENT,
                debugLogging = debugLoggingEnabled,
              )
            )
            debug("Task[$taskId] download retry(no-cookie) result=${redactedForLog(attempt)}")
          }
          progressWatcher?.cancel()
          progressWatcher = null
          attempt
        }
        result = normalizeRuntimeError(result, ffmpegInfo)
        applyRuntimeDiagnostics(taskId, result, "download")

        if (shouldIgnoreTaskResult(taskId)) {
          return@runCatching
        }

        if (result.optBoolean("success", false)) {
          if (isCancelRequested(taskId)) {
            markCancelled(taskId, "Cancellation confirmed after worker completion")
            return@runCatching
          }

          val filename = result.optString("filename").ifBlank { null }
          val filePath = result.optString("file_path").ifBlank { null }
          val sizeMb = result.optDouble("size_mb", Double.NaN).takeIf { !it.isNaN() }
          val timestampNormalized = if (result.has("timestamp_normalized")) {
            result.optBoolean("timestamp_normalized")
          } else {
            null
          }
          val warningCode = result.optString("warning_code").ifBlank { null }
          debug("Task[$taskId] success file=$filePath sizeMb=$sizeMb timestampNormalized=$timestampNormalized warning=$warningCode")

          var finalFilePath = filePath
          var privateVideoId: String? = null
          var finalIsPrivate = false
          // Writing the result out is its own gate, so one download can be landing on
          // disk while others are still being fetched. It is also the point where the
          // vault and the music library differ: the vault writes a plain file in
          // app-private storage, the library writes through MediaProvider.
          stages.store.withPermit(costOf(sizeMb)) {
            if (filename != null && filePath != null && audioOnly) {
              emitProgress(taskId, "PROGRESS", "saving", "Saving to music library", 99.0)
              val thumbnailPath = result.optString("thumbnail_path").ifBlank { null }
              runCatching {
                val plan = readAutoPresetPlan()
                if (plan == null) {
                  // Nothing to render: file it and drop the download, as before.
                  soundsStore.registerDownloadedSound(
                    sourceFilePath = filePath,
                    displayName = filename,
                    sourceUrl = url,
                    thumbnailPath = thumbnailPath,
                  )
                  finalFilePath = null
                  runCatching { File(filePath).delete() }
                  thumbnailPath?.let { runCatching { File(it).delete() } }
                  debug("Task[$taskId] audio saved to music library")
                } else {
                  // Presets are configured, so the renders need this audio as a plain file.
                  // Keep it instead of filing it and copying it straight back out of the
                  // library, which is the same bytes across the slowest boundary twice.
                  val stagedFile = moveIntoStaging(File(filePath), "audio")
                    ?: throw IllegalStateException("AUDIO_STAGING_FAILED")
                  val stagedThumb = thumbnailPath?.let { moveIntoStaging(File(it), "cover")?.absolutePath }
                  finalFilePath = null

                  var filedId: String? = null
                  if (plan.keepOriginal) {
                    filedId = soundsStore.registerDownloadedSound(
                      sourceFilePath = stagedFile.absolutePath,
                      displayName = filename,
                      sourceUrl = url,
                      thumbnailPath = stagedThumb,
                    )["id"] as? String
                    debug("Task[$taskId] audio saved to music library")
                  }

                  val staged = StagedOriginal(
                    path = stagedFile.absolutePath,
                    displayName = filename,
                    title = filename.substringBeforeLast('.'),
                    artist = null,
                    // The download already produced the configured tier, and a render
                    // should not drop from lossless to lossy.
                    outputFormat = audioFormat,
                    thumbnailPath = stagedThumb,
                    sourceUrl = url,
                    // Only the un-filed original has no other copy to fall back on.
                    registerOnFailure = !plan.keepOriginal,
                  )
                  runCatching { startAutoPresetBatch(plan, filedId, staged) }
                    .onFailure { startError ->
                      addError("AUTO_PRESET_START_FAILED: ${startError.message}")
                      // The batch never began, so nothing will hand the audio back. File it
                      // now rather than leave it stranded in staging.
                      if (staged.registerOnFailure) registerStagedOriginal(staged)
                      discardStaged(staged)
                    }
                }
              }.onFailure { saveError ->
                val saveMessage = saveError.message ?: "SOUNDS_SAVE_FAILED"
                updateStatus(taskId, "FAILURE", filename, filePath, sizeMb, "SOUNDS_SAVE_FAILED", saveMessage)
                emitProgress(taskId, "FAILURE", "error", saveMessage)
                addError("SOUNDS_SAVE_FAILED: task=$taskId message=$saveMessage")
                return@runCatching
              }
            } else if (filename != null && filePath != null && visibility == "private") {
              emitProgress(taskId, "PROGRESS", "saving", "Saving to private vault", 99.0)
              runCatching {
                val privateEntry = importFileToPrivateVault(
                  sourceFilePath = filePath,
                  filename = filename,
                  sourceUrl = url,
                  mimeType = guessMimeType(filename)
                )
                privateVideoId = privateEntry.id
                finalIsPrivate = true
                finalFilePath = null
                adoptPrivateMeme(privateEntry.id, File(filePath), guessMimeType(filename), memeSourceOf(result, url))
                runCatching { File(filePath).delete() }
              }.onFailure { privateError ->
                val privateMessage = privateError.message ?: "PRIVATE_STORAGE_WRITE_FAILED"
                // The vault would not take it, so the only copy left is the plaintext one
                // in the cache. The user asked for this to be private; leaving it readable
                // because the encrypted write failed is the wrong way round. A FAILURE is
                // terminal, so nothing downstream still needs the file.
                runCatching { File(filePath).delete() }
                updateStatus(taskId, "FAILURE", filename, filePath, sizeMb, "PRIVATE_STORAGE_WRITE_FAILED", privateMessage)
                emitProgress(taskId, "FAILURE", "error", privateMessage)
                addError("PRIVATE_STORAGE_WRITE_FAILED: task=$taskId message=$privateMessage")
                return@runCatching
              }
            } else if (source != "manual" && filename != null && filePath != null) {
              emitProgress(taskId, "PROGRESS", "saving", "Saving to gallery", 99.0)
              runCatching {
                val saveResult = saveToMediaStoreInternal(
                  filePath = filePath,
                  filename = filename,
                  mimeType = guessMimeType(filename),
                  dateTakenMs = System.currentTimeMillis(),
                )
                debug("Task[$taskId] background save success uri=${saveResult["uri"]}")
                (saveResult["uri"] as? String)?.let {
                  adoptMeme(it, guessMimeType(filename), memeSourceOf(result, url), ask = true)
                }
              }.onFailure { saveError ->
                val saveMessage = "Failed to save media to gallery: ${saveError.message ?: "unknown error"}"
                updateStatus(taskId, "FAILURE", filename, filePath, sizeMb, "INTERNAL_ERROR", saveMessage)
                emitProgress(taskId, "FAILURE", "error", saveMessage)
                addError("BACKGROUND_SAVE_FAILED: task=$taskId message=$saveMessage")
                return@runCatching
              }
            } else if (filename != null && filePath != null) {
              // The screen saves this one itself, later; the source waits for it.
              pendingMemeSources[filePath] = memeSourceOf(result, url)
            }
          }

          updateStatus(taskId, "SUCCESS", filename, finalFilePath, sizeMb, null, null, finalIsPrivate, privateVideoId)
          tasks[taskId]?.progressPercent = 100.0
          tasks[taskId]?.timestampNormalized = timestampNormalized
          tasks[taskId]?.warningCode = warningCode
          persistTaskSnapshot()
          if (warningCode != null) {
            addError("$warningCode: task=$taskId")
          }
          emitProgress(taskId, "SUCCESS", "completed", filename ?: "Download completed", 100.0)
        } else {
          val code = result.optString("code", "INTERNAL_ERROR")
          val message = result.optString("message", "Download failed")
          debug("Task[$taskId] download failed code=$code message=$message")

          if (isCancelRequested(taskId) || code == "DOWNLOAD_CANCELLED") {
            markCancelled(taskId, message)
            return@runCatching
          }

          updateStatus(taskId, "FAILURE", null, null, null, code, message)
          emitProgress(taskId, "FAILURE", "error", message)
          addError("$code: $message")
        }
      }.onFailure {
        progressWatcher?.cancel()
        if (shouldIgnoreTaskResult(taskId)) {
          return@onFailure
        }

        if (isCancelRequested(taskId)) {
          markCancelled(taskId, "Cancellation requested")
          return@onFailure
        }

        Log.e(tag, "Task failed", it)
        val message = it.message ?: "Unexpected error"
        val code = extractKnownErrorCode(message) ?: "INTERNAL_ERROR"
        debug("Task[$taskId] exception code=$code message=$message")
        updateStatus(taskId, "FAILURE", null, null, null, code, message)
        emitProgress(taskId, "FAILURE", "error", message)
        addError("$code: $message")
      }

      debug("Task[$taskId] cleanup runtime cookie + cancel flag")
      cleanupRuntimeCookieTemp(taskId)
      clearCancelFlag(taskId)
      clearProgressFile(progressFile)
      onTaskFinished(taskId)
    }

    // Registered before it is allowed to run: the body asks the table whether its own
    // task is still live, and a job that started first would not find itself there.
    activeDownloads[taskId] = ActiveDownload(taskId, url, job)
    job.start()

    return mapOf(
      "taskId" to taskId,
      "estimatedSizeMb" to task.estimatedSizeMb
    )
  }

  private fun onTaskFinished(taskId: String) {
    activeDownloads.remove(taskId)
    // Nothing to drain: every accepted download already exists as a job waiting at a
    // gate, and releasing this job's permit is what wakes the next one.
    if (!hasLiveDownloads()) {
      syncForegroundNotification("idle", "Ready for quick downloads")
    }
    emitBackgroundStateChanged()
  }

  /** A download that has been started and has not finished. */
  private class ActiveDownload(val taskId: String, val url: String, val job: Job)

  /**
   * The scheduler's ordering key for a job, from the size the preflight estimated.
   *
   * Size rather than duration because that is what the preflight actually reports, and it
   * is a fair proxy for cost: the transfer scales with it, and so does the transcode for a
   * given output format. Lower sorts first, so a small share overtakes a large download
   * that is already waiting.
   */
  private fun costOf(estimatedSizeMb: Double?): Long =
    estimatedSizeMb?.takeIf { it > 0.0 }?.toLong() ?: PriorityGate.UNKNOWN_PRIORITY

  private fun hasLiveDownloads(): Boolean =
    activeDownloads.values.any { it.job.isActive } || queueSize() > 0

  private fun activeDownloadCount(): Int = activeDownloads.size

  /** True while this task is one the module is still running. */
  private fun isTaskLive(taskId: String): Boolean = activeDownloads.containsKey(taskId)

  /**
   * Combined progress across everything in flight, for the single notification a
   * foreground service is allowed to post.
   */
  private fun aggregateProgressPercent(): Double? {
    val values = activeDownloads.keys.mapNotNull { tasks[it]?.progressPercent }
    if (values.isEmpty()) return null
    return values.sum() / values.size
  }

  private fun consumePendingQuickRequests() {
    val pending = synchronized(pendingQuickRequests) {
      if (pendingQuickRequests.isEmpty()) {
        emptyList()
      } else {
        val copy = pendingQuickRequests.toList()
        pendingQuickRequests.clear()
        copy
      }
    }
    if (pending.isEmpty()) {
      return
    }
    pending.forEach { request ->
      runCatching {
        startQuickDownloadWithUrl(request.url, request.captureMode, request.visibility)
      }.onFailure {
        addError("PENDING_QUICK_REQUEST_FAILED: ${it.message}")
      }
    }
  }

  private fun startQuickDownloadFromClipboard(): Map<String, Any?> {
    val context = requireNotNull(appContext.reactContext)
    // No notification-permission gate: see startDownloadInternal.
    val url = readUrlFromClipboard(context)
      ?: run {
        reportQuickActionReason("NO_CLIPBOARD_URL")
        return mapOf("accepted" to false, "reason" to "NO_CLIPBOARD_URL")
      }
    return startQuickDownloadWithUrl(url, "clipboard")
  }

  private fun startQuickDownloadWithUrl(
    rawUrl: String,
    captureMode: String,
    visibilityOverride: String? = null,
    mediaKindOverride: String? = null,
  ): Map<String, Any?> {
    requireNotNull(appContext.reactContext)
    // No notification-permission gate: see startDownloadInternal.
    val normalizedUrl = normalizeClipboardUrl(rawUrl)
      ?: run {
        reportQuickActionReason("INVALID_QUICK_URL")
        return mapOf("accepted" to false, "reason" to "INVALID_QUICK_URL")
      }
    // Audio mode (persisted, toggleable from the notification) forces audio-only + public,
    // unless the request named a kind itself.
    val audioOnly = when (mediaKindOverride) {
      "audio" -> true
      "video" -> false
      else -> audioModeEnabled
    }
    val selectedVisibility = if (audioOnly) "public" else normalizeVisibility(visibilityOverride, defaultPrivate = privateModeEnabled)

    val admission = admitQuickUrl(normalizedUrl)
    if (!admission.accepted) {
      return mapOf("accepted" to false, "reason" to admission.reason)
    }

    return runCatching {
      val result = startDownloadInternal(
        url = normalizedUrl,
        cookiePlatform = detectCookiePlatform(normalizedUrl),
        cookieProfile = null,
        maxFileSizeMb = DEFAULT_MAX_FILE_SIZE_MB,
        visibility = selectedVisibility,
        source = "quick",
        audioOnly = audioOnly,
      )
      reportQuickActionReason(null)
      mapOf(
        "accepted" to true,
        "taskId" to result["taskId"],
        "queueSize" to queueSize(),
        "queueMax" to MAX_QUEUED_DOWNLOADS,
        "resolvedUrl" to normalizedUrl,
        "visibility" to selectedVisibility,
        "captureMode" to captureMode
      )
    }.getOrElse {
      val reason = when {
        it.message?.contains("BACKGROUND_PERMISSION_REQUIRED") == true -> "PERMISSION_REQUIRED"
        it.message?.contains("DOWNLOAD_ALREADY_IN_PROGRESS") == true -> "ALREADY_ACTIVE"
        else -> "QUICK_DOWNLOAD_REJECTED"
      }
      reportQuickActionReason(reason)
      mapOf(
        "accepted" to false,
        "reason" to reason,
        "resolvedUrl" to normalizedUrl,
        "visibility" to selectedVisibility,
        "captureMode" to captureMode
      )
    }
  }

  private data class QueueAttemptResult(
    val accepted: Boolean,
    val reason: String? = null,
  )

  /**
   * Decide whether a shared URL is worth starting.
   *
   * There is no separate waiting list any more. A share becomes a download immediately
   * and parks at the first stage gate, which is where the scheduler decides the order —
   * keeping a second queue outside the scheduler would have split that decision across
   * two places, and it is what used to reject a fourth shared link.
   *
   * What is still refused is a repeat of something already running or just handled, which
   * is a double tap rather than a request, and an absurd number of live downloads, which
   * would mean a share-sheet loop rather than a person.
   */
  private fun admitQuickUrl(url: String): QueueAttemptResult {
    synchronized(queueLock) {
      val now = System.currentTimeMillis()
      pruneRecentQuickUrls(now)
      val isDuplicate = activeDownloads.values.any { it.url == url } || recentQuickUrls.containsKey(url)
      if (isDuplicate) {
        reportQuickActionReason("QUICK_DOWNLOAD_REJECTED")
        return QueueAttemptResult(accepted = false, reason = "QUICK_DOWNLOAD_REJECTED")
      }
      if (activeDownloads.size >= MAX_QUEUED_DOWNLOADS) {
        reportQuickActionReason("QUEUE_FULL")
        return QueueAttemptResult(accepted = false, reason = "QUEUE_FULL")
      }
      recentQuickUrls[url] = now
      return QueueAttemptResult(accepted = true)
    }
  }

  private fun pruneRecentQuickUrls(nowMs: Long) {
    val iterator = recentQuickUrls.entries.iterator()
    while (iterator.hasNext()) {
      val entry = iterator.next()
      if (nowMs - entry.value > QUICK_DEDUP_WINDOW_MS) {
        iterator.remove()
      }
    }
  }

  private fun readUrlFromClipboard(context: android.content.Context): String? {
    val manager = context.getSystemService(ClipboardManager::class.java) ?: return null
    val item = manager.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0) ?: return null

    val uriValue = item.uri?.toString()?.trim()?.takeIf { it.isNotBlank() }
    if (!uriValue.isNullOrBlank()) {
      normalizeClipboardUrl(uriValue)?.let { return it }
    }

    val htmlText = item.htmlText?.toString()?.trim()?.takeIf { it.isNotBlank() }
    if (!htmlText.isNullOrBlank()) {
      normalizeClipboardUrl(htmlText)?.let { return it }
    }

    val text = item.coerceToText(context)?.toString()?.trim() ?: return null
    if (text.isBlank()) {
      return null
    }
    return normalizeClipboardUrl(text)
  }

  private fun normalizeClipboardUrl(raw: String?): String? {
    return normalizeQuickUrl(raw)
  }

  private fun normalizeVisibility(rawVisibility: String?, defaultPrivate: Boolean): String {
    val normalized = rawVisibility?.trim()?.lowercase()
    return when (normalized) {
      "private" -> "private"
      "public" -> "public"
      else -> if (defaultPrivate) "private" else "public"
    }
  }

  private fun isNotificationPermissionGranted(context: android.content.Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
      return true
    }
    return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
  }

  private fun canAskForNotificationPermission(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
      return true
    }
    val activity = appContext.currentActivity ?: return false
    val granted = isNotificationPermissionGranted(requireNotNull(appContext.reactContext))
    return !granted || ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.POST_NOTIFICATIONS)
  }

  /**
   * Downloads that have been accepted but are still waiting at a gate.
   *
   * Reported rather than stored: the scheduler's waiter lists are the queue now, so this
   * cannot drift from what is actually waiting the way a second list would.
   */
  private fun queueSize(): Int = stages.preflight.queued + stages.fetch.queued

  private fun backgroundStateMap(): Map<String, Any?> {
    val context = appContext.reactContext
    val granted = context?.let { isNotificationPermissionGranted(it) } ?: false
    // Derived from the work this module knows about, NOT from the service's own flag.
    // That flag is set by the service in its lifecycle callbacks, so at the moment a
    // download finishes it is still true: the module sends ACTION_STOP, emits state
    // immediately, and only later does the service actually stop. Nothing emitted
    // again afterwards, so the UI latched "downloading" forever. This value is correct
    // at the instant it is read and needs no callback from the service.
    val hasBackgroundWork =
      hasLiveDownloads() || presetRenderActive != null || backupJobActive != null
    // Derived for the same reason as the flag above. `notificationPhase` is a side
    // effect of whoever last touched the notification, so ordering decides its value:
    // a render batch sets "rendering", then the finishing download's own cleanup sets
    // "idle" and emits last. The UI then saw work in progress with a phase of "idle"
    // and fell back to calling everything a download. This reports what is actually
    // running, whatever order the calls happened in.
    val workPhase = when {
      // Ahead of the others: a backup blocks on their data, so if one is running it is the
      // thing the user is waiting for.
      backupJobActive != null -> backupJobActive?.optString("mode").orEmpty().ifBlank { "backup" }
      presetRenderActive != null -> "rendering"
      activeDownloadCount() > 0 -> notificationPhase.takeIf { it != "idle" } ?: "downloading"
      queueSize() > 0 -> "downloading"
      else -> "idle"
    }
    return mapOf(
      "serviceRunning" to hasBackgroundWork,
      "activeTaskIds" to activeDownloads.keys.toList(),
      "queueSize" to queueSize(),
      "maxQueueSize" to MAX_QUEUED_DOWNLOADS,
      "queuedUrls" to emptyList<String>(),
      "lastQuickReason" to lastQuickReason,
      "notificationPhase" to workPhase,
      "stickyNotificationEnabled" to stickyNotificationEnabled,
      "privateModeEnabled" to privateModeEnabled,
      "audioModeEnabled" to audioModeEnabled,
      "notificationPermissionRequired" to (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU),
      "notificationPermissionGranted" to granted
    )
  }

  private fun setStickyNotificationEnabledInternal(enabled: Boolean): Boolean {
    val context = requireNotNull(appContext.reactContext)
    if (enabled && !isNotificationPermissionGranted(context)) {
      throw IllegalStateException("BACKGROUND_PERMISSION_REQUIRED")
    }
    stickyNotificationEnabled = enabled
    persistStickyNotificationEnabled(context, enabled)
    if (enabled) {
      syncForegroundNotification("idle", "Sticky notification enabled")
    } else if (!hasLiveDownloads()) {
      DownloadNotificationController.stop(context)
    } else {
      syncForegroundNotification(notificationPhase, "Sticky notification disabled")
    }
    emitBackgroundStateChanged()
    return enabled
  }

  private fun setPrivateModeEnabledInternal(enabled: Boolean): Boolean {
    val context = requireNotNull(appContext.reactContext)
    if (enabled && !isPrivateAuthAvailable(context)) {
      throw IllegalStateException("PRIVATE_MODE_UNAVAILABLE")
    }
    val resolved = if (PRIVATE_VAULT_FEATURE_FLAG) enabled else false
    privateModeEnabled = resolved
    persistPrivateModeEnabled(context, resolved)
    // Private and Audio modes are mutually exclusive (audio is always public).
    if (resolved && audioModeEnabled) {
      audioModeEnabled = false
      persistAudioModeEnabled(context, false)
    }
    syncForegroundNotification(notificationPhase, if (resolved) "Private mode enabled" else "Private mode disabled")
    emitBackgroundStateChanged()
    return resolved
  }

  private fun setAudioModeEnabledInternal(enabled: Boolean): Boolean {
    val context = requireNotNull(appContext.reactContext)
    audioModeEnabled = enabled
    persistAudioModeEnabled(context, enabled)
    // Audio mode forces public output, so turning it on clears private mode.
    if (enabled && privateModeEnabled) {
      privateModeEnabled = false
      persistPrivateModeEnabled(context, false)
    }
    syncForegroundNotification(notificationPhase, null)
    emitBackgroundStateChanged()
    return enabled
  }

  // ---------------------------------------------------------------------------
  // Audio preset rendering
  // ---------------------------------------------------------------------------

  /**
   * Kick off a preset render over [songIds] and return its renderId.
   *
   * Runs on the IO scope rather than blocking the module queue: a render is seconds of
   * work per track, and a batch is minutes. Tracks are rendered SEQUENTIALLY — each one
   * already saturates a core through ffmpeg plus the DSP, so running them in parallel
   * would not finish sooner and would multiply peak memory and cache use.
   *
   * Caveat: this is tied to the module's lifetime. A batch does not currently survive
   * the app being killed; wiring it to DownloadForegroundService is the next step.
   */
  /**
   * One unit of render work: apply one preset to one track.
   *
   * A flat job list rather than "one preset over many tracks" because both shapes are
   * needed — the library applies one preset to a selection, while an auto-applied
   * download produces several presets from a single downloaded track.
   */
  private data class PresetJob(
    val songId: String,
    val presetId: String,
    val paramsSpec: String,
    val titleSuffix: String,
  )

  private fun presetJobFromJson(obj: JSONObject): PresetJob = PresetJob(
    songId = obj.optString("songId"),
    presetId = obj.optString("presetId"),
    paramsSpec = obj.optString("paramsSpec"),
    titleSuffix = obj.optString("titleSuffix"),
  )

  private fun presetJobToJson(job: PresetJob): JSONObject = JSONObject().apply {
    put("songId", job.songId)
    put("presetId", job.presetId)
    put("paramsSpec", job.paramsSpec)
    put("titleSuffix", job.titleSuffix)
  }

  /**
   * The downloaded audio a batch renders from, held on disk for the batch's lifetime.
   *
   * Before this existed, a download was copied into the music library, its local copy was
   * deleted, and the renderer then copied the identical bytes back out of the library to
   * get a plain file ffmpeg could open. Keeping the download instead removes that round
   * trip, and every byte of it crossed MediaProvider's FUSE boundary twice.
   *
   * [registerOnFailure] is what preserves "a render failure never costs the audio". When
   * the user asked to keep only the preset versions the original is never filed at all,
   * so if the batch does not finish cleanly this file is filed rather than discarded.
   */
  private data class StagedOriginal(
    val path: String,
    val displayName: String,
    val title: String,
    val artist: String?,
    val outputFormat: String,
    val thumbnailPath: String?,
    val sourceUrl: String?,
    val registerOnFailure: Boolean,
  )

  private fun stagedOriginalToJson(staged: StagedOriginal): JSONObject = JSONObject().apply {
    put("path", staged.path)
    put("displayName", staged.displayName)
    put("title", staged.title)
    put("artist", staged.artist ?: JSONObject.NULL)
    put("outputFormat", staged.outputFormat)
    put("thumbnailPath", staged.thumbnailPath ?: JSONObject.NULL)
    put("sourceUrl", staged.sourceUrl ?: JSONObject.NULL)
    put("registerOnFailure", staged.registerOnFailure)
  }

  private fun stagedOriginalFromJson(obj: JSONObject?): StagedOriginal? {
    if (obj == null) return null
    val path = obj.optString("path").ifBlank { return null }
    return StagedOriginal(
      path = path,
      displayName = obj.optString("displayName").ifBlank { File(path).name },
      title = obj.optString("title").ifBlank { File(path).nameWithoutExtension },
      artist = obj.optString("artist").ifBlank { null },
      outputFormat = obj.optString("outputFormat").ifBlank { DEFAULT_AUDIO_FORMAT },
      thumbnailPath = obj.optString("thumbnailPath").ifBlank { null },
      sourceUrl = obj.optString("sourceUrl").ifBlank { null },
      registerOnFailure = obj.optBoolean("registerOnFailure", false),
    )
  }

  private fun audioStagingDir(): File? {
    val context = appContext.reactContext ?: return null
    return File(context.filesDir, AUDIO_STAGING_DIRNAME).apply { mkdirs() }
  }

  /**
   * Move a finished download into staging. A rename, because `cacheDir` and `filesDir`
   * are on the same filesystem — copying a multi-gigabyte file here would undo the point
   * of the change. Falls back to a copy if the rename is refused.
   */
  private fun moveIntoStaging(source: File, prefix: String): File? {
    val dir = audioStagingDir() ?: return null
    val target = File(dir, "${prefix}_${UUID.randomUUID()}.${source.extension.ifBlank { "bin" }}")
    if (source.renameTo(target)) return target
    return runCatching {
      source.copyTo(target, overwrite = true)
      source.delete()
      target
    }.getOrNull()
  }

  private fun discardStaged(staged: StagedOriginal?) {
    if (staged == null) return
    runCatching { File(staged.path).delete() }
    staged.thumbnailPath?.let { runCatching { File(it).delete() } }
  }

  /**
   * File a staged original into the music library. Used when a batch could not produce
   * the preset versions the download was going to be replaced by.
   */
  private fun registerStagedOriginal(staged: StagedOriginal) {
    val file = File(staged.path)
    if (!file.isFile || file.length() <= 0L) return
    runCatching {
      soundsStore.registerDownloadedSound(
        sourceFilePath = staged.path,
        displayName = staged.displayName,
        sourceUrl = staged.sourceUrl,
        thumbnailPath = staged.thumbnailPath,
      )
      debug("Filed the staged original after an incomplete preset batch")
    }.onFailure { addError("PRESET_ORIGINAL_RECOVERY_FAILED: ${it.message}") }
  }

  /**
   * Delete staged files that no live batch refers to.
   *
   * These are whole downloads, so an orphan left by a process death is gigabytes. Runs at
   * module start, once the persisted queue has been read.
   */
  private fun cleanupOrphanedStaging() {
    val dir = audioStagingDir() ?: return
    val keep = HashSet<String>()
    stagedOriginalFromJson(readPersistedPresetQueue()?.optJSONObject("stagedOriginal"))?.let {
      keep.add(File(it.path).name)
      it.thumbnailPath?.let { thumb -> keep.add(File(thumb).name) }
    }
    runCatching {
      dir.listFiles()?.forEach { file ->
        if (file.name !in keep) {
          val bytes = file.length()
          if (file.delete()) debug("Removed an orphaned staged file ($bytes bytes)")
        }
      }
    }.onFailure { Log.w(tag, "cleanupOrphanedStaging failed: ${it.message}") }
  }

  /** Apply one preset across a selection of tracks. */
  private fun startPresetRender(
    songIds: List<String>,
    presetId: String,
    paramsSpec: String,
    titleSuffix: String,
  ): Map<String, Any?> {
    val jobs = songIds.map { PresetJob(it, presetId, paramsSpec, titleSuffix) }
    return startPresetJobs(jobs, deleteSourceWhenDone = null, staged = null)
  }

  /**
   * Queue an arbitrary job list and return its renderId.
   *
   * The list is persisted before any work starts and rewritten after every job, so a
   * process death leaves an accurate record of what remains. The foreground service is
   * held for the duration, which stops Android reclaiming the process when the task is
   * swiped away (the service is declared stopWithTask false).
   *
   * [deleteSourceWhenDone] removes that track once every job succeeds. It applies to a
   * batch whose source was filed in the library — which now only happens for a batch
   * persisted by an older build, since the auto-apply flow renders from [staged] instead.
   *
   * [staged] is the downloaded file the auto-apply flow renders from; see [StagedOriginal].
   */
  private fun startPresetJobs(
    jobs: List<PresetJob>,
    deleteSourceWhenDone: String?,
    staged: StagedOriginal?,
  ): Map<String, Any?> {
    val renderId = "preset_${UUID.randomUUID()}"
    persistPresetQueue(renderId, jobs, jobs.size, 0, 0, deleteSourceWhenDone, staged)
    runPresetBatch(renderId, jobs, jobs.size, 0, 0, deleteSourceWhenDone, staged)
    return mapOf("renderId" to renderId, "total" to jobs.size)
  }

  /**
   * Run (or continue) [pending]. `completedSoFar` / `failedSoFar` carry the totals from
   * before an interruption so a resumed batch reports honest numbers.
   */
  private fun runPresetBatch(
    renderId: String,
    pending: List<PresetJob>,
    total: Int,
    completedSoFar: Int,
    failedSoFar: Int,
    deleteSourceWhenDone: String?,
    staged: StagedOriginal?,
  ) {
    val context = requireNotNull(appContext.reactContext)

    val cancelDir = File(context.cacheDir, PRESET_CANCEL_DIRNAME).apply { mkdirs() }
    val cancelFlag = File(cancelDir, "$renderId.cancel")
    runCatching { if (cancelFlag.exists()) cancelFlag.delete() }
    presetRenderCancelFlags[renderId] = cancelFlag

    val progressDir = File(context.cacheDir, PRESET_PROGRESS_DIRNAME).apply { mkdirs() }
    val renderer = AudioPresetRenderer(context, soundsStore)

    presetRenderActive = renderId
    syncForegroundNotification("rendering", null)
    emitBackgroundStateChanged()

    scope.launch {
      var completed = completedSoFar
      var failed = failedSoFar
      val remaining = ArrayDeque(pending)
      try {
        while (remaining.isNotEmpty()) {
          if (cancelFlag.exists()) break
          val job = remaining.removeFirst()
          val index = total - remaining.size - 1

          val progressFile = File(progressDir, "$renderId-$index.json")
          runCatching { progressFile.delete() }

          // Watch the file the native side rewrites so a long track reports movement
          // rather than sitting at 0% until it finishes.
          val watcher = launch { watchPresetProgress(renderId, job.songId, index, total, progressFile) }

          val ffmpegInfo = getOrResolveFfmpegInfo()
          // Renders take a permit of their own so several batches cannot all decode and
          // encode at once. Derived work, so it yields to the downloads that produced it.
          val outcome = stages.render.withPermit(PriorityGate.UNKNOWN_PRIORITY) {
            runCatching {
              renderer.render(
                AudioPresetRenderer.Request(
                  songId = job.songId,
                  presetId = job.presetId,
                  paramsSpec = job.paramsSpec,
                  titleSuffix = job.titleSuffix,
                  ffmpegPath = ffmpegInfo.path ?: ffmpegInfo.location.orEmpty(),
                  ffprobePath = ffmpegInfo.ffprobePath.orEmpty(),
                  progressFilePath = progressFile.absolutePath,
                  cancelFlagPath = cancelFlag.absolutePath,
                  source = staged?.let {
                    AudioPresetRenderer.StagedSource(
                      path = it.path,
                      title = it.title,
                      artist = it.artist,
                      outputFormat = it.outputFormat,
                      thumbnailPath = it.thumbnailPath,
                    )
                  },
                )
              )
            }
          }
          watcher.cancel()
          runCatching { progressFile.delete() }

          outcome.onSuccess { song ->
            completed += 1
            emitPresetProgress(renderId, "TRACK_DONE", job.songId, index, total, 100.0, song = song)
          }.onFailure { error ->
            if (cancelFlag.exists()) return@onFailure
            failed += 1
            val message = error.message ?: "PRESET_RENDER_FAILED"
            addError("PRESET_RENDER_FAILED: song=${job.songId} message=$message")
            emitPresetProgress(renderId, "TRACK_FAILED", job.songId, index, total, null, message)
          }

          // Rewrite AFTER each job: if the process dies now, the finished render is
          // already in the library and must not be produced a second time on resume.
          persistPresetQueue(renderId, remaining.toList(), total, completed, failed, deleteSourceWhenDone, staged)
          syncForegroundNotification("rendering", null)
        }

        val cancelled = cancelFlag.exists()
        val clean = !cancelled && failed == 0
        // Only discard the source if every render actually succeeded. Dropping it after
        // a partial failure would destroy the only copy of the audio.
        if (clean && deleteSourceWhenDone != null) {
          runCatching { soundsStore.deleteSounds(listOf(deleteSourceWhenDone)) }
            .onFailure { addError("PRESET_SOURCE_CLEANUP_FAILED: ${it.message}") }
        }
        if (staged != null) {
          // The staged download is the only copy of the audio when the original was
          // never filed. A batch that did not finish cleanly must hand it back rather
          // than delete it, or the download is lost.
          if (!clean && staged.registerOnFailure) registerStagedOriginal(staged)
          discardStaged(staged)
        }

        emitPresetProgress(
          renderId,
          if (cancelled) "CANCELLED" else "FINISHED",
          null,
          total,
          total,
          100.0,
          message = "completed=$completed failed=$failed",
        )
      } finally {
        presetRenderCancelFlags.remove(renderId)
        runCatching { cancelFlag.delete() }
        clearPersistedPresetQueue()
        presetRenderActive = null
        // Hand the notification back to whatever the downloader is doing; if nothing
        // is, shouldRunForeground goes false and the service stops.
        syncForegroundNotification(notificationPhase, null)
        // Tell the UI too. Updating only the notification left the home screen showing
        // work forever, because a render batch is the last thing to finish after an
        // auto-applied download and nothing emitted once it cleared.
        emitBackgroundStateChanged()
      }
    }
  }

  /**
   * Continue a batch that a process death interrupted. Called once at module start.
   *
   * Only jobs still listed as pending are run — the file is rewritten after every job
   * precisely so a resume cannot duplicate work already in the library.
   */
  private fun resumePresetRenderIfAny() {
    val queue = readPersistedPresetQueue() ?: return
    val jobsArray = queue.optJSONArray("jobs")
    if (jobsArray == null || jobsArray.length() == 0) {
      clearPersistedPresetQueue()
      return
    }
    val jobs = ArrayList<PresetJob>(jobsArray.length())
    for (i in 0 until jobsArray.length()) jobs.add(presetJobFromJson(jobsArray.getJSONObject(i)))
    // Absent for a batch persisted before staging existed; those resume on the old path,
    // resolving their source through the library entry the renderer falls back to.
    val staged = stagedOriginalFromJson(queue.optJSONObject("stagedOriginal"))
    debug("Resuming interrupted preset batch: ${jobs.size} job(s) left, staged=${staged != null}")
    runPresetBatch(
      renderId = queue.optString("renderId").ifBlank { "preset_${UUID.randomUUID()}" },
      pending = jobs,
      total = queue.optInt("total", jobs.size),
      completedSoFar = queue.optInt("completed", 0),
      failedSoFar = queue.optInt("failed", 0),
      deleteSourceWhenDone = queue.optString("deleteSourceWhenDone").ifBlank { null },
      staged = staged,
    )
  }

  private fun presetQueueFile(): File? {
    val context = appContext.reactContext ?: return null
    return File(context.filesDir, PRESET_QUEUE_FILENAME)
  }

  private fun persistPresetQueue(
    renderId: String,
    jobs: List<PresetJob>,
    total: Int,
    completed: Int,
    failed: Int,
    deleteSourceWhenDone: String?,
    staged: StagedOriginal?,
  ) {
    val file = presetQueueFile() ?: return
    runCatching {
      val payload = JSONObject().apply {
        put("renderId", renderId)
        put("jobs", JSONArray(jobs.map { presetJobToJson(it) }))
        put("total", total)
        put("completed", completed)
        put("failed", failed)
        put("deleteSourceWhenDone", deleteSourceWhenDone ?: JSONObject.NULL)
        put("stagedOriginal", staged?.let { stagedOriginalToJson(it) } ?: JSONObject.NULL)
      }
      val tmp = File(file.parentFile, file.name + ".tmp")
      tmp.writeText(payload.toString(), Charsets.UTF_8)
      if (!tmp.renameTo(file)) {
        tmp.copyTo(file, overwrite = true)
        tmp.delete()
      }
    }.onFailure { Log.w(tag, "persistPresetQueue failed: ${it.message}") }
  }

  private fun readPersistedPresetQueue(): JSONObject? {
    val file = presetQueueFile() ?: return null
    if (!file.exists()) return null
    return runCatching { JSONObject(file.readText(Charsets.UTF_8)) }.getOrNull()
  }

  private fun clearPersistedPresetQueue() {
    runCatching { presetQueueFile()?.delete() }
  }

  // ---------------------------------------------------------------------------
  // Auto-applied presets for downloads
  // ---------------------------------------------------------------------------

  /**
   * Read the auto-apply configuration.
   *
   * Stored NATIVELY rather than in TypeScript because a download can finish with no JS
   * running at all — the share-sheet capture path starts one without the UI. The preset
   * definitions still live in TypeScript; it serialises them here, and this side only
   * replays what it was given.
   */
  // ==================================================================== backup ports
  //
  // Concrete implementations of the interfaces in `backup/BackupPorts.kt`. They live here
  // rather than in that package because every one of them needs private helpers of this
  // class — vault decryption, the Keystore-backed cookie cipher, the sounds store. The
  // logic they feed (collectors, duplicate policy, id remapping) is all in `backup/` and is
  // covered by JVM tests against fakes; these adapters only translate.

  private fun backupStaging(): BackupPorts.Staging = object : BackupPorts.Staging {
    override fun newStagingFile(name: String): File {
      val dir = File(requireNotNull(appContext.reactContext).cacheDir, BACKUP_STAGING_DIRNAME)
        .apply { mkdirs() }
      return File(dir, "${UUID.randomUUID()}.part")
    }
  }

  /** Delete anything a previous run left behind — a crash mid-restore strands scratch files. */
  private fun clearBackupStaging() {
    val dir = File(requireNotNull(appContext.reactContext).cacheDir, BACKUP_STAGING_DIRNAME)
    runCatching { dir.listFiles()?.forEach { it.delete() } }
  }

  private fun backupVaultPort(): BackupPorts.VaultPort = object : BackupPorts.VaultPort {
    override fun list(): List<BackupPorts.VaultRecord> {
      // A private meme is a vault entry with labels: they travel in its entry.
      val snapshot = runCatching { memes.store.snapshot() }.getOrNull()
      val privateMemes = snapshot?.items.orEmpty().filter { it.isPrivate && it.vaultId != null }.associateBy { it.vaultId!! }
      return synchronized(privateVaultLock) { listVaultRecords(privateMemes, snapshot) }
    }

    private fun listVaultRecords(
      privateMemes: Map<String, MemeStore.Item>,
      snapshot: MemeStore.Snapshot?,
    ): List<BackupPorts.VaultRecord> {
      val index = readPrivateVaultIndex()
      val items = index.optJSONArray("items") ?: JSONArray()
      return (0 until items.length()).mapNotNull { i ->
        val json = items.optJSONObject(i) ?: return@mapNotNull null
        val entry = privateVideoEntryFromJson(json) ?: return@mapNotNull null
        val meme = privateMemes[entry.id]
        if (meme != null && snapshot != null) {
          val (tags, people) = memes.store.labelsOf(meme, snapshot)
          json.put("meme", MemeStore.encodeMeme(meme.kind, meme.source, tags, people, MemeStore.signaturesOf(people, snapshot)))
        }
        BackupPorts.VaultRecord(
          id = entry.id,
          title = entry.title,
          mimeType = entry.mimeType,
          meta = json,
          plaintextSize = vaultPlaintextSize(entry),
        )
      }
    }

    override fun writePlaintext(record: BackupPorts.VaultRecord, out: OutputStream) {
      val entry = findPrivateVideoById(record.id)
        ?: throw IllegalStateException("PRIVATE_VIDEO_NOT_FOUND")
      val encrypted = File(privateVaultObjectsDir(create = true), entry.encFileName)
      if (!encrypted.exists()) throw IllegalStateException("PRIVATE_VIDEO_NOT_FOUND")
      decryptPrivateVaultFileToOutput(
        source = encrypted,
        output = out,
        effectiveVersion = detectPrivateCipherVersion(encrypted, entry.cipherVersion),
        traceId = "backup",
        entryId = entry.id,
      )
    }

    override fun hashOf(record: BackupPorts.VaultRecord): String {
      // Only reached when a plaintext size collides, so the decrypt cost is bounded to
      // genuinely ambiguous entries.
      val digest = MessageDigest.getInstance("SHA-256")
      val sink = object : OutputStream() {
        override fun write(b: Int) = digest.update(b.toByte())
        override fun write(b: ByteArray, off: Int, len: Int) = digest.update(b, off, len)
      }
      writePlaintext(record, sink)
      return digest.digest().joinToString("") { "%02x".format(it) }
    }

    override fun restore(
      staged: File,
      name: String,
      mimeType: String,
      meta: JSONObject,
    ): String {
      val id = importFileToPrivateVault(
        sourceFilePath = staged.absolutePath,
        filename = sanitizePrivateTitle(name),
        sourceUrl = meta.optString("sourceUrl").ifBlank { "backup://restore" },
        mimeType = mimeType.ifBlank { "video/mp4" },
      ).id
      // A private meme: its labels come back with it, into the private index.
      meta.optJSONObject("meme")?.let { memeObject ->
        runCatching {
          val decoded = MemeStore.decodeMeme(memeObject)
          val sha = MemeCollection.hashOf { staged.inputStream() } ?: return@runCatching
          memes.store.registerPrivate(id, decoded.kind, sha, decoded.source, decoded.tags, decoded.people,
            signatures = decoded.signatures)
        }.onFailure { addError("MEME_RESTORE_FAILED: ${it.javaClass.simpleName}") }
      }
      return id
    }
  }

  private fun backupMemesPort(): BackupPorts.MemesPort = object : BackupPorts.MemesPort {
    override fun list(): List<BackupPorts.MemeRecord> {
      memes.reconcile()
      val snapshot = memes.store.snapshot()
      return snapshot.items.filter { !it.isPrivate }.map { item ->
        val (tags, people) = memes.store.labelsOf(item, snapshot)
        BackupPorts.MemeRecord(
          id = item.id,
          fileName = memes.displayName(item),
          sizeBytes = memes.sizeOf(item),
          meta = JSONObject()
            .put("memeId", item.id).put("sha256", item.sha256)
            .put("addedAt", item.addedAt).put("taggedAt", item.taggedAt)
            .put("meme", MemeStore.encodeMeme(item.kind, item.source, tags, people, MemeStore.signaturesOf(people, snapshot))),
        )
      }
    }

    override fun open(record: BackupPorts.MemeRecord): InputStream {
      val item = findMeme(record.id) ?: throw IllegalStateException("MEME_NOT_FOUND")
      return memes.open(item) ?: throw IllegalStateException("MEME_NOT_FOUND")
    }

    override fun vocabulary(): JSONObject {
      val snapshot = memes.store.snapshot()
      val open = snapshot.items.filter { !it.isPrivate }
      val hidden = snapshot.items.filter { it.isPrivate }
      val hiddenTags = hidden.flatMap { it.tags }.toSet() - open.flatMap { it.tags }.toSet()
      val hiddenPeople = hidden.flatMap { it.people }.toSet() - open.flatMap { it.people }.toSet()
      return JSONObject()
        .put("tags", JSONArray().apply {
          snapshot.tags.filter { it.id !in hiddenTags }.forEach {
            put(JSONObject().put("name", it.name).put("facets", JSONArray(it.facets.map { f -> f.wire })))
          }
        })
        .put("people", JSONArray().apply {
          // A person's face signatures travel with them (CONTRACT.md, "Faces").
          snapshot.people.filter { it.id !in hiddenPeople }.forEach { person ->
            put(JSONObject().put("name", person.name).apply {
              if (person.signatures.isNotEmpty()) put("signatures", JSONArray(person.signatures))
            })
          }
        })
    }

    override fun existingIdFor(sha256: String): String? =
      memes.store.snapshot().items.firstOrNull { it.sha256 == sha256 && !it.isPrivate }?.id

    override fun mergeLabels(existingId: String, meta: JSONObject) {
      val item = findMeme(existingId) ?: return
      val decoded = MemeStore.decodeMeme(meta.optJSONObject("meme"))
      memes.store.receive(item.uri ?: return, item.kind, item.sha256, item.source, decoded.tags, decoded.people,
        decoded.signatures)
    }

    override fun restore(staged: File, name: String, meta: JSONObject): String {
      val decoded = MemeStore.decodeMeme(meta.optJSONObject("meme"))
      val guessed = guessMimeType(name)
      val mime = if (MemeCollection.kindOf(guessed) != null) guessed else if (decoded.kind == "image") "image/jpeg" else "video/mp4"
      val saved = saveToMediaStoreInternal(staged.path, name.substringAfterLast('/'), mime, System.currentTimeMillis())
      val item = memes.store.receive(saved["uri"] as String, decoded.kind, meta.optString("sha256"), decoded.source,
        decoded.tags, decoded.people, decoded.signatures)
      if (meta.optLong("taggedAt") > 0 && item.isUntagged) memes.store.label(setOf(item.id))
      return item.id
    }

    override fun restoreVocabulary(json: JSONObject) {
      val decoded = MemeStore.decodeMeme(JSONObject().put("tags", json.optJSONArray("tags") ?: JSONArray())
        .put("people", json.optJSONArray("people") ?: JSONArray()))
      memes.store.ensure(decoded.tags, decoded.people, decoded.signatures)
    }
  }

  /**
   * Plaintext size without decrypting the body. Cipher v4 records it in the stream header,
   * which Tink can read from a seekable channel; older versions cannot answer cheaply and
   * report null, which the duplicate index treats as "might collide".
   */
  private fun vaultPlaintextSize(entry: PrivateVideoEntry): Long? {
    if (entry.cipherVersion != PRIVATE_STORE_VERSION_V4) return null
    val encrypted = File(privateVaultObjectsDir(create = false), entry.encFileName)
    if (!encrypted.exists()) return null
    return runCatching {
      VaultCipherV4.plaintextLength(encrypted, entry.id, requireVaultDek(VaultAuthPolicy.OP_LIST))
    }.getOrNull()
  }

  private fun backupMusicPort(): BackupPorts.MusicPort = object : BackupPorts.MusicPort {
    private fun songs(): List<Map<String, Any?>> {
      @Suppress("UNCHECKED_CAST")
      return (soundsStore.listLibrary()["songs"] as? List<Map<String, Any?>>).orEmpty()
    }

    override fun list(): List<BackupPorts.MusicRecord> = songs().map { song ->
      BackupPorts.MusicRecord(
        id = song["id"] as? String ?: "",
        fileName = song["fileName"] as? String ?: "",
        sizeBytes = (song["sizeBytes"] as? Number)?.toLong() ?: 0L,
        meta = JSONObject().apply {
          put("title", song["title"] as? String)
          put("artist", song["artist"] as? String)
          put("durationSec", (song["durationSec"] as? Number)?.toDouble() ?: 0.0)
          put("presetId", song["presetId"] as? String)
          put("sourceSongId", song["sourceSongId"] as? String)
          put("createdAt", (song["createdAt"] as? Number)?.toLong() ?: 0L)
        },
        thumbnailPath = song["thumbnailPath"] as? String,
      )
    }

    override fun open(record: BackupPorts.MusicRecord): InputStream {
      val song = songs().firstOrNull { it["id"] == record.id }
        ?: throw IllegalStateException("SOUND_NOT_FOUND")
      val uri = (song["contentUri"] as? String)?.takeIf { it.isNotBlank() }
        ?: throw IllegalStateException("SOUND_NOT_FOUND")
      return requireNotNull(appContext.reactContext).contentResolver
        .openInputStream(Uri.parse(uri))
        ?: throw IllegalStateException("SOUND_NOT_READABLE")
    }

    override fun openThumbnail(record: BackupPorts.MusicRecord): InputStream? =
      record.thumbnailPath
        ?.let { File(it) }
        ?.takeIf { it.exists() }
        ?.inputStream()

    override fun hashOf(record: BackupPorts.MusicRecord): String =
      open(record).use { BackupContainer.sha256(it) }

    override fun playlistsJson(): JSONObject {
      @Suppress("UNCHECKED_CAST")
      val playlists = (soundsStore.listLibrary()["playlists"] as? List<Map<String, Any?>>).orEmpty()
      val array = JSONArray()
      playlists.forEach { playlist ->
        @Suppress("UNCHECKED_CAST")
        val songIds = (playlist["songIds"] as? List<String>).orEmpty()
        array.put(
          JSONObject().apply {
            put("id", playlist["id"] as? String)
            put("name", playlist["name"] as? String)
            put("system", playlist["system"] as? Boolean ?: false)
            put("songIds", JSONArray().also { ids -> songIds.forEach(ids::put) })
          }
        )
      }
      return JSONObject().apply { put("playlists", array) }
    }

    override fun autoPresetConfig(): JSONObject? = readAutoPresetConfig()

    override fun restore(
      staged: File,
      name: String,
      meta: JSONObject,
      thumbnail: File?,
    ): String {
      val song = soundsStore.registerDownloadedSound(
        sourceFilePath = staged.absolutePath,
        displayName = name,
        sourceUrl = null,
        thumbnailPath = thumbnail?.absolutePath,
      )
      return song["id"] as? String ?: ""
    }

    override fun restorePlaylists(json: JSONObject, idMap: Map<String, String>) {
      val playlists = json.optJSONArray("playlists") ?: return
      for (i in 0 until playlists.length()) {
        val playlist = playlists.optJSONObject(i) ?: continue
        val name = playlist.optString("name").trim()
        val backedUpIds = playlist.optJSONArray("songIds") ?: JSONArray()
        // Songs skipped as duplicates have no mapping, so they simply drop out of the
        // playlist rather than leaving an id that points at nothing.
        val mapped = (0 until backedUpIds.length())
          .mapNotNull { idMap[backedUpIds.optString(it)] }
        if (mapped.isEmpty()) continue

        if (playlist.optBoolean("system", false) ||
          playlist.optString("id") == SoundsStore.FAVORITES_PLAYLIST_ID
        ) {
          // Favourites is a reserved playlist that always exists; it is populated by
          // toggling membership rather than by being recreated.
          runCatching { soundsStore.setSoundsFavorite(mapped, true) }
          continue
        }
        if (name.isBlank()) continue
        runCatching {
          // Reuse a playlist of the same name rather than making another one. A restore is
          // re-runnable by design — that is how an interrupted one is finished — so creating
          // unconditionally left a duplicate of every playlist on the second pass.
          @Suppress("UNCHECKED_CAST")
          val existing = (soundsStore.listPlaylists() as List<Map<String, Any?>>)
            .firstOrNull { (it["name"] as? String)?.trim().equals(name, ignoreCase = true) }

          val playlistId = (existing?.get("id") as? String)
            ?: (soundsStore.createPlaylist(name)["id"] as? String)
            ?: return@runCatching

          // Union rather than replace: a playlist the user has added to since the backup
          // must not lose those tracks to a restore.
          @Suppress("UNCHECKED_CAST")
          val current = (existing?.get("songIds") as? List<String>).orEmpty()
          soundsStore.setPlaylistSongs(playlistId, (current + mapped).distinct())
        }
      }
    }

    override fun restoreAutoPresetConfig(json: JSONObject) {
      requireNotNull(appContext.reactContext)
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putString(PREF_AUTO_PRESETS, json.toString())
        .apply()
    }
  }

  private fun backupCookiePort(): BackupPorts.CookiePort = object : BackupPorts.CookiePort {
    override fun list(): List<BackupPorts.CookieRecord> = SUPPORTED_PLATFORMS.flatMap { platform ->
      val dir = secureCookiePlatformDir(platform, create = false)
      if (!dir.exists()) return@flatMap emptyList()
      val default = readDefaultProfile(dir)
      dir.listFiles()
        ?.filter { it.isFile && it.extension == "enc" }
        ?.map {
          BackupPorts.CookieRecord(
            platform = platform,
            profileName = it.nameWithoutExtension,
            isDefault = it.nameWithoutExtension == default,
          )
        }
        .orEmpty()
    }

    override fun exists(platform: String, profileName: String): Boolean {
      val normalized = platform.trim().lowercase()
      if (!SUPPORTED_PLATFORMS.contains(normalized) || profileName.isBlank()) return false
      return File(secureCookiePlatformDir(normalized, create = false), "$profileName.enc").exists()
    }

    override fun readPlaintext(record: BackupPorts.CookieRecord): ByteArray {
      val file = File(
        secureCookiePlatformDir(record.platform, create = false),
        "${record.profileName}.enc"
      )
      // Decrypted here and re-encrypted into the backup under the user's passphrase. The
      // stored form is bound to this device's Keystore key and would be unreadable anywhere
      // else — including on this phone after a reinstall.
      return decryptCookieBytes(file.readBytes())
    }

    override fun restore(
      platform: String,
      profileName: String,
      isDefault: Boolean,
      plaintext: ByteArray,
    ) {
      val normalized = platform.trim().lowercase()
      if (!SUPPORTED_PLATFORMS.contains(normalized) || profileName.isBlank()) return
      val dir = secureCookiePlatformDir(normalized, create = true)
      File(dir, "${profileName}.enc").writeBytes(encryptCookieBytes(plaintext))
      if (isDefault) {
        writeDefaultProfile(dir, profileName)
      }
    }
  }

  /**
   * Launch the SAF picker and block until it answers, mirroring the sounds/vault importers.
   * The latch timeout is the safety net for a picker that never returns a result.
   */
  private fun pickBackupDocument(mode: String, suggestedName: String?): BackupDocumentActivity.Result {
    val context = appContext.reactContext
      ?: return BackupDocumentActivity.Result(code = BackupDocumentActivity.CODE_FAILED)

    val resultRef = AtomicReference<BackupDocumentActivity.Result?>()
    val latch = CountDownLatch(1)
    val launched = BackupDocumentActivity.launch(context, mode, suggestedName) { result ->
      resultRef.set(result)
      latch.countDown()
    }
    if (!launched) {
      return BackupDocumentActivity.Result(code = BackupDocumentActivity.CODE_FAILED)
    }
    val completed = runCatching {
      latch.await(PRIVATE_IMPORT_PICK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }.getOrDefault(false)
    if (!completed) {
      BackupDocumentActivity.cancelPendingWith(BackupDocumentActivity.CODE_CANCELLED)
      return BackupDocumentActivity.Result(code = BackupDocumentActivity.CODE_CANCELLED)
    }
    return resultRef.get()
      ?: BackupDocumentActivity.Result(code = BackupDocumentActivity.CODE_FAILED)
  }

  /**
   * Convert the JS `secrets` payload into slot secrets.
   *
   * The secret is copied into a CharArray so the caller can wipe it after use; the String
   * that arrived from JS still lingers until GC, which is unavoidable across the bridge.
   */
  private fun backupSecretsFrom(raw: Any?): List<BackupContainer.SlotSecret> {
    val list = raw as? List<*> ?: return emptyList()
    return list.mapNotNull { item ->
      val map = item as? Map<*, *> ?: return@mapNotNull null
      val secret = (map["secret"] as? String)?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
      BackupContainer.SlotSecret(
        slotId = (map["slotId"] as? String)?.takeIf { it.isNotBlank() }
          ?: BackupFormat.DEFAULT_KEY_SLOT,
        secret = secret.toCharArray(),
        secretKind = (map["kind"] as? String)?.takeIf { it.isNotBlank() }
          ?: BackupFormat.SECRET_KIND_PASSPHRASE,
      )
    }
  }

  /** `arsivinyo-backup-2026-08-11.avsbck` — dated so successive exports do not collide. */
  private fun defaultBackupFileName(): String {
    val stamp = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
      .format(java.util.Date())
    return "arsivinyo-backup-$stamp.${BackupFormat.FILE_EXTENSION}"
  }

  private fun readAutoPresetConfig(): JSONObject? {
    val context = appContext.reactContext ?: return null
    val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
      .getString(PREF_AUTO_PRESETS, null) ?: return null
    return runCatching { JSONObject(raw) }.getOrNull()
  }

  /** What the auto-apply configuration asks for, resolved before a download is filed. */
  private data class AutoPresetPlan(
    val presets: List<JSONObject>,
    val keepOriginal: Boolean,
  )

  /**
   * Read the auto-apply configuration, or null when nothing is configured.
   *
   * Resolved *before* the download is filed, because whether the original is filed at all
   * now depends on the answer.
   */
  private fun readAutoPresetPlan(): AutoPresetPlan? {
    val config = readAutoPresetConfig() ?: return null
    val presets = config.optJSONArray("presets") ?: return null
    val entries = ArrayList<JSONObject>(presets.length())
    for (i in 0 until presets.length()) {
      val entry = presets.optJSONObject(i) ?: continue
      // Plain `if` rather than `ifBlank { continue }`: jumping out of an inline lambda
      // needs Kotlin 2.2 and this project builds with 2.1.
      if (entry.optString("id").isBlank()) continue
      entries.add(entry)
    }
    if (entries.isEmpty()) return null
    return AutoPresetPlan(entries, config.optBoolean("keepOriginal", true))
  }

  /**
   * Queue the configured presets against a freshly downloaded track.
   *
   * [songId] is the library entry when the original was filed, and null when it was not —
   * the renders then read from [staged] and nothing about the original is in the library.
   */
  private fun startAutoPresetBatch(plan: AutoPresetPlan, songId: String?, staged: StagedOriginal) {
    val jobs = plan.presets.map { entry ->
      PresetJob(
        songId = songId.orEmpty(),
        presetId = entry.optString("id"),
        paramsSpec = entry.optString("paramsSpec"),
        titleSuffix = entry.optString("titleSuffix"),
      )
    }
    debug("Auto-applying ${jobs.size} preset(s) (keepOriginal=${plan.keepOriginal}, filed=${songId != null})")
    // deleteSourceWhenDone stays null: with the original never filed there is no library
    // entry to remove, and when it is filed the user asked to keep it.
    startPresetJobs(jobs, deleteSourceWhenDone = null, staged = staged)
  }

  /** Poll the native progress file and forward it as events until cancelled. */
  private suspend fun watchPresetProgress(
    renderId: String,
    songId: String,
    index: Int,
    total: Int,
    progressFile: File,
  ) {
    var lastPercent = -1.0
    while (currentCoroutineContext().isActive) {
      val percent = readPresetPercent(progressFile)
      if (percent != null && percent - lastPercent >= 1.0) {
        lastPercent = percent
        emitPresetProgress(renderId, "PROGRESS", songId, index, total, percent)
      }
      delay(PRESET_PROGRESS_POLL_MS)
    }
  }

  private fun readPresetPercent(progressFile: File): Double? {
    if (!progressFile.exists()) return null
    return runCatching {
      JSONObject(progressFile.readText(Charsets.UTF_8)).optDouble("percent", Double.NaN)
        .takeUnless { it.isNaN() }
    }.getOrNull()
  }

  private fun emitPresetProgress(
    renderId: String,
    status: String,
    songId: String?,
    index: Int,
    total: Int,
    percent: Double?,
    message: String? = null,
    song: Map<String, Any?>? = null,
  ) {
    runCatching {
      sendEvent(
        "soundPresetProgress",
        mapOf(
          "renderId" to renderId,
          "status" to status,
          "songId" to songId,
          "index" to index,
          "total" to total,
          "percent" to percent,
          "message" to message,
          "song" to song,
        )
      )
    }
  }

  /** Create the cancel flag the native render polls. Returns false if unknown. */
  private fun cancelPresetRender(renderId: String): Boolean {
    val flag = presetRenderCancelFlags[renderId] ?: return false
    return runCatching { flag.createNewFile() || flag.exists() }.getOrDefault(false)
  }

  private fun setAudioFormatInternal(format: String?): String {
    val context = requireNotNull(appContext.reactContext)
    val resolved = normalizeAudioFormat(format)
    audioFormat = resolved
    persistAudioFormat(context, resolved)
    return resolved
  }

  private fun reportQuickActionReason(reason: String?) {
    lastQuickReason = reason
    lastQuickReasonFallback = reason
    emitBackgroundStateChanged()
  }

  // ------------------------------------------------------------------ backup job state

  /**
   * Mark a backup job as running: pin the foreground service and tell any listening screen.
   *
   * The pin is the point. Without it the process is an ordinary background candidate, and
   * Android reclaims it partway through a long export — which for an export is
   * unrecoverable, because the container is one continuous stream.
   */
  private fun beginBackupJob(mode: String, destinationUri: String?) {
    backupJobActive = JSONObject().apply {
      put("mode", mode)
      put("processed", 0)
      put("total", 0)
      put("startedAt", System.currentTimeMillis())
    }
    backupJobLastOutcome = null
    if (mode == BACKUP_MODE_EXPORT && destinationUri != null) {
      // Written before a single byte goes out. If the process dies mid-export this file is
      // still here on the next launch, and the half-written document it names gets deleted.
      persistBackupExportMarker(destinationUri)
    }
    syncForegroundNotification(mode, null)
    emitBackgroundStateChanged()
    emitBackupProgress()
  }

  /**
   * The container reports which item it is handling; the name is deliberately dropped here.
   *
   * Only the section and the counts travel onward. This app holds a private vault, and a
   * filename is exactly the thing it exists to keep out of sight — on the lock screen, on
   * the screen, or anywhere a shoulder can reach. A count says just as much about progress
   * and reveals nothing about what is stored.
   */
  private fun updateBackupJob(sectionId: String, @Suppress("UNUSED_PARAMETER") name: String, index: Int, total: Int) {
    val job = backupJobActive ?: return
    job.put("processed", index)
    job.put("total", total)
    job.put("section", sectionId)
    val percent = if (total > 0) ((index * 100) / total) else 0
    val now = System.currentTimeMillis()
    val movedEnough = percent != backupNotificationLastPercent
    val waitedEnough = now - backupNotificationLastPostAt >= BACKUP_NOTIFICATION_MIN_INTERVAL_MS
    if (movedEnough && waitedEnough) {
      backupNotificationLastPostAt = now
      backupNotificationLastPercent = percent
      syncForegroundNotification(job.optString("mode"), null, percent.toDouble())
    }
    // Always emitted: the in-app screen is not rate limited and wants every item.
    emitBackupProgress()
  }

  private fun endBackupJob(outcome: JSONObject) {
    debug("BACKUP_NOTIF endBackupJob mode=${outcome.optString("mode")}")
    backupJobActive = null
    // Let the final post through unconditionally — it is the one that must not be dropped.
    backupNotificationLastPostAt = 0L
    backupNotificationLastPercent = -1
    backupJobLastOutcome = outcome.apply { put("finishedAt", System.currentTimeMillis()) }
    clearBackupExportMarker()
    // Hand the notification back to whatever else is running, using the same derivation as
    // startup so the two cannot disagree.
    reconcileForegroundNotification()
    emitBackgroundStateChanged()
    emitBackupProgress()
  }

  /**
   * Force the notification to match the module's actual state.
   *
   * Called at startup, where "actual state" is normally "nothing is running". Any work that
   * genuinely survived is represented in the fields this reads, so a restored download or a
   * resumed render batch still keeps its notification.
   */
  private fun reconcileForegroundNotification() {
    val phase = when {
      backupJobActive != null -> backupJobActive?.optString("mode").orEmpty().ifBlank { "idle" }
      presetRenderActive != null -> "rendering"
      hasLiveDownloads() -> "downloading"
      else -> "idle"
    }
    // Phase and a flag only — never an item name.
    debug("BACKUP_NOTIF reconcile phase=$phase pinned=$stickyNotificationEnabled")
    syncForegroundNotification(phase, null)
  }

  /** Timings for the result, so the screen can show a rate without another round trip. */
  private fun backupPerfMap(stats: BackupContainer.Stats): Map<String, Any?> = mapOf(
    "totalMs" to stats.totalNanos / 1_000_000,
    "kdfMs" to stats.kdfNanos / 1_000_000,
    "sections" to stats.sections.map { (id, stat) ->
      val payloadMs = stat.payloadNanos / 1_000_000
      mapOf(
        "id" to id,
        "items" to stat.items,
        "bytes" to stat.bytes,
        "payloadMs" to payloadMs,
        "containerMs" to stat.containerNanos / 1_000_000,
        "appMs" to payloadMs - (stat.containerNanos / 1_000_000),
      )
    },
  )

  private fun emitBackupProgress() {
    sendEvent("backupProgress", backupJobStateMap())
  }

  /** The shape both the event and `getBackupJobState` return, so a screen can reattach. */
  private fun backupJobStateMap(): Map<String, Any?> = mapOf(
    "active" to backupJobActive?.let {
      mapOf(
        "mode" to it.optString("mode"),
        "processed" to it.optInt("processed", 0),
        "total" to it.optInt("total", 0),
        "section" to it.optString("section").ifBlank { null },
        "startedAt" to it.optLong("startedAt", 0L),
      )
    },
    "last" to backupJobLastOutcome?.let {
      mapOf(
        "mode" to it.optString("mode"),
        "success" to it.optBoolean("success", false),
        "summary" to it.optString("summary").ifBlank { null },
        "finishedAt" to it.optLong("finishedAt", 0L),
      )
    },
  )

  private fun backupExportMarkerFile(): File =
    File(requireNotNull(appContext.reactContext).filesDir, BACKUP_EXPORT_MARKER_FILENAME)

  private fun persistBackupExportMarker(uri: String) {
    runCatching { backupExportMarkerFile().writeText(uri) }
  }

  private fun clearBackupExportMarker() {
    runCatching { backupExportMarkerFile().delete() }
  }

  /**
   * Delete a document left behind by an export that the system killed.
   *
   * An interrupted export cannot be resumed — the container is a single stream — and the
   * partial file is the dangerous kind of broken: it has a valid header, so it opens, lists
   * its sections, and only fails once a restore is already underway. Removing it is safer
   * than leaving something that looks like a backup and is not.
   *
   * A restore needs no equivalent. It is incremental and deduplicates by content hash, so
   * running it again simply skips whatever already landed.
   */
  private fun cleanupInterruptedBackupExport() {
    val marker = backupExportMarkerFile()
    if (!marker.exists()) return
    val uri = runCatching { marker.readText().trim() }.getOrNull()
    if (!uri.isNullOrBlank()) {
      val context = appContext.reactContext
      if (context != null) {
        runCatching {
          DocumentsContract.deleteDocument(context.contentResolver, Uri.parse(uri))
        }.onFailure {
          addError("BACKUP_PARTIAL_CLEANUP_FAILED: $uri: ${it.message}")
        }
      }
      addError("BACKUP_EXPORT_INTERRUPTED: removed the partial file at $uri")
    }
    clearBackupExportMarker()
  }

  private fun emitBackgroundStateChanged() {
    sendEvent("backgroundStateChanged", backgroundStateMap())
  }

  private fun syncForegroundNotification(phase: String, message: String?, explicitProgress: Double? = null) {
    val context = appContext.reactContext ?: return
    // Deliberately does NOT return early when notification permission is missing.
    // startForeground() still succeeds without POST_NOTIFICATIONS on Android 13+; the
    // system suppresses the notification but keeps the service alive. Returning early
    // meant the service never started, so a backgrounded download — and a preset render
    // batch — could be reclaimed mid-work. The service already reports its own start
    // failures, so an unexpected refusal surfaces rather than passing silently.
    notificationPhase = phase
    // A foreground service owns exactly one notification, so with several downloads
    // running it reports the set rather than picking one: the count, and their combined
    // progress. Showing a single task's bar would have made it jump between downloads.
    val running = activeDownloadCount()
    val torrentSummary = runCatching { torrents.summary() }.getOrNull()
    val progress = explicitProgress ?: aggregateProgressPercent()
    val state = BackgroundNotificationState(
      activeTaskId = activeDownloads.keys.firstOrNull(),
      activeCount = running,
      phase = phase,
      message = message,
      progressPercent = progress,
      queueSize = queueSize(),
      privateModeEnabled = privateModeEnabled,
      audioModeEnabled = audioModeEnabled,
      // A render batch pins the notification the same way the sticky setting does.
      // Without this the service would stop as soon as no download was active, and
      // Android would be free to reclaim the process mid-batch.
      // A backup pins it too. Without this the service only survived when the user
      // happened to have the sticky notification switched on, so an export or restore
      // depended on an unrelated setting to avoid being reclaimed.
      pinned = stickyNotificationEnabled || presetRenderActive != null || backupJobActive != null,
      torrentCount = torrentSummary?.downloading ?: 0,
      torrentPercent = torrentSummary?.percent,
    )
    if (state.shouldRunForeground) {
      DownloadNotificationController.startOrUpdate(context, state)
    } else {
      DownloadNotificationController.stop(context)
    }
  }

  private fun stopForegroundNotificationIfIdle() {
    if (!hasLiveDownloads()) {
      appContext.reactContext?.let { DownloadNotificationController.stop(it) }
    }
  }

  private fun cancelFromNotificationAction() {
    // The notification has one stop button and may now represent several downloads, so it
    // stops all of them — picking one to cancel would be a guess at which the user meant.
    activeDownloads.keys.toList().forEach { cancelDownloadTask(it) }
  }

  private fun cancelDownloadTask(taskId: String) {
    markCancelRequested(taskId)
    ignoredTaskResults.add(taskId)
    if (!isTerminalStatus(tasks[taskId]?.status)) {
      markCancelled(taskId, "Cancellation requested from notification")
    }
    syncForegroundNotification("downloading", "Cancellation requested")
    emitBackgroundStateChanged()
  }

  private fun quickFromNotificationAction() {
    val result = startQuickDownloadFromClipboard()
    if (result["accepted"] == true) {
      val queueSize = (result["queueSize"] as? Number)?.toInt()
      if (queueSize != null && queueSize > 0) {
        syncForegroundNotification("downloading", null)
      } else {
        syncForegroundNotification("starting", "Quick download started")
      }
      return
    }
    val reason = result["reason"]?.toString().orEmpty()
    syncForegroundNotification("error", quickReasonToMessage(reason))
  }

  private fun emitProgress(
    taskId: String,
    status: String,
    state: String,
    message: String?,
    progressPercent: Double? = null,
    speedBytesPerSec: Double? = null
  ) {
    val normalizedState = normalizeProgressEventState(state)
    tasks[taskId]?.state = normalizedState
    if (progressPercent != null) {
      tasks[taskId]?.progressPercent = progressPercent.coerceIn(0.0, 100.0)
    }
    if (normalizedState != "downloading") {
      tasks[taskId]?.speedBytesPerSec = null
    } else if (speedBytesPerSec != null && speedBytesPerSec > 0) {
      tasks[taskId]?.speedBytesPerSec = speedBytesPerSec
    }
    val eventSpeedBytesPerSec = if (normalizedState == "downloading") {
      (if (speedBytesPerSec != null && speedBytesPerSec > 0) speedBytesPerSec else tasks[taskId]?.speedBytesPerSec)
    } else {
      null
    }
    sendEvent(
      "downloadProgress",
      mapOf(
        "taskId" to taskId,
        "status" to status,
        "state" to normalizedState,
        "message" to message,
        "progressPercent" to progressPercent?.coerceIn(0.0, 100.0),
        "speedBytesPerSec" to eventSpeedBytesPerSec
      )
    )
    // Any live download may drive the notification. Gating this on a single "active"
    // task silently dropped every other download's progress.
    if (isTaskLive(taskId)) {
      syncForegroundNotification(normalizedState, message)
    }
  }

  private fun updateStatus(
    taskId: String,
    status: String,
    filename: String?,
    filePath: String?,
    sizeMb: Double?,
    errorCode: String?,
    errorMessage: String?,
    isPrivate: Boolean? = null,
    privateVideoId: String? = null
  ) {
    val task = tasks[taskId] ?: TaskState(taskId, status)
    val wasFailure = task.status == "FAILURE"
    task.status = status
    if (filename != null) task.filename = filename
    if (filePath != null) task.filePath = filePath
    if (sizeMb != null) task.sizeMb = sizeMb
    if (isPrivate != null) task.isPrivate = isPrivate
    if (privateVideoId != null || isPrivate == false) task.privateVideoId = privateVideoId
    if (task.state == null) {
      task.state = when (status) {
        "PENDING", "STARTED" -> "starting"
        "PROGRESS" -> "downloading"
        "SUCCESS" -> "completed"
        "FAILURE", "CANCELLED" -> "error"
        else -> null
      }
    }
    task.errorCode = errorCode
    task.errorMessage = errorMessage
    tasks[taskId] = task
    persistTaskSnapshot()
    if (status == "FAILURE" && !wasFailure) {
      recordDownloadFailure(taskId, errorCode, errorMessage)
    }
  }

  private fun normalizeProgressEventState(rawState: String?): String {
    return when (rawState?.trim()?.lowercase()) {
      "starting" -> "starting"
      "processing" -> "processing"
      "saving" -> "saving"
      "completed" -> "completed"
      "error" -> "error"
      else -> "downloading"
    }
  }

  private fun callPythonPreflight(input: PreflightPythonInput): JSONObject {
    ensurePythonReady()
    debug(
      "Python preflight call url=${input.url} ffmpegPath=${input.ffmpegPath} " +
        "cookieFile=${input.cookieFilePath ?: "none"} mergeCapable=${input.mergeCapable} forceNoCookie=${input.forceNoCookie}"
    )
    val py = Python.getInstance()
    val module = py.getModule("local_downloader")
    val result = module.callAttr(
      "preflight",
      input.url,
      input.cookiesDir,
      input.cookieProfile,
      input.maxFileSizeMb,
      input.ffmpegPath,
      input.cookieFilePath,
      input.forceNoCookie,
      input.mergeCapable,
      input.userAgent,
      input.debugLogging
    )
    val json = JSONObject(result.toString())
    debug("Python preflight response code=${json.optString("code")} success=${json.optBoolean("success")} msg=${json.optString("message")}")
    return json
  }

  private fun callPythonDownload(input: DownloadPythonInput): JSONObject {
    ensurePythonReady()
    debug(
      "Python download call url=${input.url} ffmpegPath=${input.ffmpegPath} " +
        "cookieFile=${input.cookieFilePath ?: "none"} mergeCapable=${input.mergeCapable} forceNoCookie=${input.forceNoCookie}"
    )
    val py = Python.getInstance()
    val module = py.getModule("local_downloader")
    val result = module.callAttr(
      "run_download",
      input.url,
      input.outputDir,
      input.cookiesDir,
      input.cookieProfile,
      input.maxFileSizeMb,
      input.cancelFlagPath,
      input.progressFilePath,
      input.ffmpegPath,
      input.cookieFilePath,
      input.forceNoCookie,
      input.mergeCapable,
      input.audioOnly,
      input.audioFormat,
      input.userAgent,
      input.debugLogging
    )
    val json = JSONObject(result.toString())
    debug("Python download response code=${json.optString("code")} success=${json.optBoolean("success")} msg=${json.optString("message")}")
    return json
  }

  private fun applyRuntimeDiagnostics(taskId: String, result: JSONObject, phase: String) {
    val task = tasks[taskId] ?: return
    task.normalizedUrl = result.optString("normalized_url")
      .ifBlank { result.optString("normalizedUrl") }
      .ifBlank { task.normalizedUrl }
    task.preflightStrategy = result.optString("preflight_strategy")
      .ifBlank { result.optString("preflightStrategy") }
      .ifBlank { if (phase == "preflight") result.optString("strategy") else "" }
      .ifBlank { task.preflightStrategy }
    task.downloadStrategy = result.optString("download_strategy")
      .ifBlank { result.optString("downloadStrategy") }
      .ifBlank { if (phase == "download") result.optString("strategy") else "" }
      .ifBlank { task.downloadStrategy }
    task.extractorKey = result.optString("extractor_key")
      .ifBlank { result.optString("extractorKey") }
      .ifBlank { task.extractorKey }
    task.formatSelector = result.optString("format_selector")
      .ifBlank { result.optString("formatSelector") }
      .ifBlank { task.formatSelector }
    task.toolOutput = result.optString("tool_output")
      .ifBlank { result.optString("toolOutput") }
      .ifBlank { task.toolOutput }
    task.preflightBudgetSec = result.optInt("preflight_budget_sec", Int.MIN_VALUE)
      .takeIf { it != Int.MIN_VALUE }
      ?: result.optInt("preflightBudgetSec", Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }
      ?: task.preflightBudgetSec
    task.preflightElapsedMs = result.optLong("preflight_elapsed_ms", Long.MIN_VALUE)
      .takeIf { it != Long.MIN_VALUE }
      ?: result.optLong("preflightElapsedMs", Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE }
      ?: task.preflightElapsedMs
    task.preflightAttemptLimit = result.optInt("preflight_attempt_limit", Int.MIN_VALUE)
      .takeIf { it != Int.MIN_VALUE }
      ?: result.optInt("preflightAttemptLimit", Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }
      ?: task.preflightAttemptLimit
    task.staticMediaCandidateCount = result.optInt("static_media_candidate_count", Int.MIN_VALUE)
      .takeIf { it != Int.MIN_VALUE }
      ?: result.optInt("staticMediaCandidateCount", Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }
      ?: task.staticMediaCandidateCount

    result.optJSONObject("preflight_warning")?.let {
      task.preflightWarning = jsonObjectToMap(it)
    }
    result.optJSONObject("preflightWarning")?.let {
      task.preflightWarning = jsonObjectToMap(it)
    }
    val traceArray = result.optJSONArray("attempt_trace") ?: result.optJSONArray("attemptTrace")
    if (traceArray != null) {
      task.attemptTrace = jsonArrayToMapList(traceArray, MAX_FAILURE_LOG_ATTEMPTS)
    }
    tasks[taskId] = task
    persistTaskSnapshot()
  }

  private fun jsonArrayToMapList(array: JSONArray, maxItems: Int): List<Map<String, Any?>> {
    return (0 until minOf(array.length(), maxItems)).mapNotNull { index ->
      val item = array.optJSONObject(index) ?: return@mapNotNull null
      jsonObjectToMap(item)
    }
  }

  private fun jsonObjectToMap(obj: JSONObject): Map<String, Any?> {
    val result = mutableMapOf<String, Any?>()
    val keys = obj.keys()
    while (keys.hasNext()) {
      val key = keys.next()
      result[key] = jsonValueToAny(obj.opt(key))
    }
    return result
  }

  private fun jsonValueToAny(value: Any?): Any? {
    return when (value) {
      null, JSONObject.NULL -> null
      is JSONObject -> jsonObjectToMap(value)
      is JSONArray -> (0 until value.length()).map { index -> jsonValueToAny(value.opt(index)) }
      else -> value
    }
  }

  private fun getYtDlpUpdateStatusInternal(includeLatest: Boolean): Map<String, Any?> {
    val status = buildYtDlpUpdateStatusMap(fetchActiveFromPython = true).toMutableMap()
    if (includeLatest) {
      runCatching {
        val latest = fetchLatestYtDlpRelease()
        status["latestVersion"] = latest.version
        status["updateAvailable"] = isNewerYtDlpVersion(latest.version, status["effectiveInstalledVersion"] as? String)
      }.onFailure {
        status["latestCheckError"] = it.message ?: it::class.java.simpleName
      }
    }
    return status
  }

  private fun checkYtDlpUpdateInternal(): Map<String, Any?> {
    emitYtDlpUpdateProgress("checking")
    val status = getYtDlpUpdateStatusInternal(includeLatest = true).toMutableMap()
    val latest = status["latestVersion"] as? String
    val current = status["effectiveInstalledVersion"] as? String
    val updateAvailable = latest != null && isNewerYtDlpVersion(latest, current)
    status["updateAvailable"] = updateAvailable
    status["status"] = if (updateAvailable) "available" else "up_to_date"
    emitYtDlpUpdateProgress(if (updateAvailable) "available" else "up_to_date", version = latest)
    return status
  }

  private fun updateYtDlpInternal(wanted: String? = null): Map<String, Any?> {
    synchronized(ytDlpUpdateLock) {
      if (ytDlpUpdateRunning) {
        return mapOf("status" to "running", "success" to false, "code" to "UPDATE_ALREADY_RUNNING", "requiresRestart" to false)
      }
      ytDlpUpdateRunning = true
    }

    try {
      if (hasLiveDownloads()) {
        return mapOf(
          "status" to "blocked",
          "success" to false,
          "code" to "DOWNLOAD_ACTIVE",
          "message" to "A download is active. Finish or cancel it before updating yt-dlp.",
          "requiresRestart" to false
        )
      }

      emitYtDlpUpdateProgress("checking")
      cleanupYtDlpUpdateScratch()
      val before = buildYtDlpUpdateStatusMap(fetchActiveFromPython = true)
      val release = fetchYtDlpRelease(wanted)
      val current = before["effectiveInstalledVersion"] as? String
      // Only the "give me the newest" path can be a no-op. Asking for a specific version
      // is a choice, including an older one, so it is never refused as not newer.
      if (wanted == null && !isNewerYtDlpVersion(release.version, current)) {
        emitYtDlpUpdateProgress("up_to_date", version = release.version)
        return mapOf(
          "status" to "up_to_date",
          "success" to true,
          "previousVersion" to current,
          "installedVersion" to current,
          "latestVersion" to release.version,
          "requiresRestart" to false
        )
      }

      val context = requireNotNull(appContext.reactContext).applicationContext
      val updateCache = ytDlpUpdateCacheDir(context).apply { mkdirs() }
      val wheelTmp = File(updateCache, "${release.version}.whl.tmp")
      val wheelFile = File(updateCache, "${release.version}.whl")
      requireSufficientYtDlpUpdateSpace(updateCache, release.sizeBytes)
      downloadYtDlpWheel(release, wheelTmp)
      if (wheelFile.exists()) wheelFile.delete()
      if (!wheelTmp.renameTo(wheelFile)) {
        throw IOException("WHEEL_RENAME_FAILED")
      }

      emitYtDlpUpdateProgress("installing", version = release.version)
      val versionDir = installYtDlpWheel(context, release, wheelFile)
      val manifest = readYtDlpManifest(context)
      val installed = manifest.optJSONObject("installed") ?: JSONObject()
      installed.put(
        release.version,
        JSONObject()
          .put("sha256", release.sha256)
          .put("installedAt", System.currentTimeMillis())
          .put("source", "pypi")
          .put("filename", release.filename)
      )
      // One downloaded version is kept. Without this every switch left an unpacked copy
      // behind — megabytes a time, in app storage, never reclaimed.
      pruneYtDlpVersions(context, release.version, installed)
      manifest.put("schemaVersion", 1)
      manifest.put("installed", installed)
      manifest.put("pendingVersion", release.version)
      manifest.put("failedVersion", JSONObject.NULL)
      manifest.put("failedReason", JSONObject.NULL)
      writeYtDlpManifest(context, manifest)

      emitYtDlpUpdateProgress("verifying", version = release.version)
      verifyInstalledYtDlpPackage(versionDir, release.version)
      emitYtDlpUpdateProgress("installed", version = release.version)
      return mapOf(
        "status" to "installed",
        "success" to true,
        "previousVersion" to current,
        "installedVersion" to release.version,
        "latestVersion" to release.version,
        "requiresRestart" to true,
        "pendingVersion" to release.version
      )
    } catch (error: Throwable) {
      val code = error.message?.takeIf { it.isNotBlank() } ?: error::class.java.simpleName
      addError("YT_DLP_UPDATE_FAILED: $code")
      emitYtDlpUpdateProgress("failed", message = code)
      return mapOf("status" to "failed", "success" to false, "code" to code, "message" to code, "requiresRestart" to false)
    } finally {
      synchronized(ytDlpUpdateLock) {
        ytDlpUpdateRunning = false
      }
    }
  }

  /**
   * Relaunch the app.
   *
   * A downloaded yt-dlp is activated by the bootstrap when Python next starts, and Python
   * starts with the process. Until now the app said "restart to activate it" and offered no
   * way to do so, which left force-stopping it from Android's settings as the only route.
   *
   * Refused while a download is running: the process dies here, and a transfer that has not
   * finished writing would be lost with it.
   */
  private fun restartAppInternal(): Map<String, Any?> {
    if (activeDownloads.isNotEmpty()) {
      return mapOf(
        "restarted" to false,
        "reason" to "DOWNLOAD_ACTIVE",
        "activeTaskIds" to activeDownloads.keys.toList(),
      )
    }

    val context = appContext.reactContext
      ?: return mapOf("restarted" to false, "reason" to "NO_CONTEXT")

    // Handed to a helper in its own process. Starting the launcher from here and then
    // exiting only closed the app: the process died before the system finished bringing
    // the activity up, and a dead process cannot start one either.
    val restart = Intent(context, RestartActivity::class.java).apply {
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
      putExtra(RestartActivity.EXTRA_PID, android.os.Process.myPid())
    }
    context.startActivity(restart)
    return mapOf("restarted" to true)
  }

  private fun clearYtDlpOverrideInternal(): Map<String, Any?> {
    val context = requireNotNull(appContext.reactContext).applicationContext
    val manifest = readYtDlpManifest(context)
    manifest.put("schemaVersion", 1)
    manifest.put("activeVersion", JSONObject.NULL)
    manifest.put("pendingVersion", JSONObject.NULL)
    manifest.put("failedVersion", JSONObject.NULL)
    manifest.put("failedReason", JSONObject.NULL)
    writeYtDlpManifest(context, manifest)
    return mapOf("success" to true, "requiresRestart" to Python.isStarted())
  }

  private fun buildYtDlpUpdateStatusMap(fetchActiveFromPython: Boolean): Map<String, Any?> {
    val context = requireNotNull(appContext.reactContext).applicationContext
    var manifest = readYtDlpManifest(context)
    var bootstrap = lastYtDlpBootstrapStatus
    var activeVersion = bootstrap?.optString("activeVersion")?.takeIf { it.isNotBlank() && it != "null" }
    if (activeVersion == null && fetchActiveFromPython) {
      runCatching {
        ensurePythonReady()
        bootstrap = lastYtDlpBootstrapStatus
        manifest = readYtDlpManifest(context)
        activeVersion = Python.getInstance().getModule("yt_dlp.version").get("__version__").toString()
      }
    }
    val pendingVersion = manifest.optString("pendingVersion").takeIf { it.isNotBlank() && it != "null" }
    val manifestActiveVersion = manifest.optString("activeVersion").takeIf { it.isNotBlank() && it != "null" }
    val overrideVersion = bootstrap?.optString("overrideVersion")?.takeIf { it.isNotBlank() && it != "null" } ?: manifestActiveVersion
    val bundledVersion = bootstrap?.optString("bundledVersion")?.takeIf { it.isNotBlank() && it != "null" }
      ?: if (overrideVersion == null) activeVersion else null
    val effectiveInstalledVersion = pendingVersion ?: overrideVersion ?: activeVersion ?: bundledVersion
    val root = ytDlpOverrideRoot(context)
    val versionsDir = File(root, "versions")
    val installedVersions = manifest.optJSONObject("installed")?.let { obj ->
      obj.keys().asSequence().toList().sortedWith(Comparator { a, b -> compareYtDlpVersions(a, b) })
    } ?: emptyList()
    return mapOf(
      "source" to (bootstrap?.optString("source")?.takeIf { it.isNotBlank() } ?: if (overrideVersion != null) "override" else "bundled"),
      "bundledVersion" to bundledVersion,
      "activeVersion" to activeVersion,
      "overrideVersion" to overrideVersion,
      "pendingVersion" to pendingVersion,
      "failedVersion" to manifest.optString("failedVersion").takeIf { it.isNotBlank() && it != "null" },
      "failedReason" to manifest.optString("failedReason").takeIf { it.isNotBlank() && it != "null" },
      "effectiveInstalledVersion" to effectiveInstalledVersion,
      "installedVersions" to installedVersions,
      "requiresRestart" to (pendingVersion != null),
      "updateRunning" to ytDlpUpdateRunning,
      "storageReady" to ((root.exists() || root.mkdirs()) && (versionsDir.exists() || versionsDir.mkdirs())),
      "overridePath" to bootstrap?.optString("overridePath")?.takeIf { it.isNotBlank() && it != "null" },
      "activeTaskIds" to activeDownloads.keys.toList()
    )
  }

  private fun applyYtDlpOverrideBootstrap(context: Context) {
    val root = ytDlpOverrideRoot(context).apply { mkdirs() }
    File(root, "versions").mkdirs()
    File(root, ".staging").mkdirs()
    val manifest = ytDlpManifestFile(context)
    runCatching {
      val result = Python.getInstance()
        .getModule("yt_dlp_override_bootstrap")
        .callAttr("activate", root.absolutePath, manifest.absolutePath)
        .toString()
      lastYtDlpBootstrapStatus = JSONObject(result)
      debug("yt-dlp override bootstrap: $result")
    }.onFailure {
      addError("YT_DLP_OVERRIDE_BOOTSTRAP_FAILED: ${it.message}")
      lastYtDlpBootstrapStatus = JSONObject()
        .put("source", "bundled")
        .put("failedReason", it.message ?: it::class.java.simpleName)
    }
  }

  private fun fetchLatestYtDlpRelease(): YtDlpReleaseAsset = fetchYtDlpRelease(null)

  /** Recent stable releases, newest first. Capped: yt-dlp has hundreds of them. */
  private fun listYtDlpVersionsInternal(limit: Int = 12): Map<String, Any?> {
    val json = httpGetJson(YT_DLP_PYPI_JSON_URL)
    val releases = json.optJSONObject("releases") ?: return mapOf("versions" to emptyList<String>())
    // A list of ints is not Comparable, so sort on the tuple explicitly: yt-dlp versions
    // are YYYY.M.D and "2026.7.4" sorts before "2026.10.1" as a string.
    fun parts(version: String): Triple<Int, Int, Int> {
      val bits = version.split(".").map { it.toIntOrNull() ?: 0 }
      return Triple(bits.getOrElse(0) { 0 }, bits.getOrElse(1) { 0 }, bits.getOrElse(2) { 0 })
    }

    val versions = releases.keys().asSequence()
      .filter { isStableYtDlpVersion(it) }
      .sortedWith(compareByDescending<String> { parts(it).first }
        .thenByDescending { parts(it).second }
        .thenByDescending { parts(it).third })
      .take(limit)
      .toList()
    return mapOf("versions" to versions)
  }

  /** @param wanted null for the newest stable release. */
  private fun fetchYtDlpRelease(wanted: String?): YtDlpReleaseAsset {
    val json = httpGetJson(YT_DLP_PYPI_JSON_URL)
    val version = (wanted ?: json.optJSONObject("info")?.optString("version"))
      ?.takeIf { isStableYtDlpVersion(it) }
      ?: throw IllegalStateException("LATEST_VERSION_NOT_STABLE")
    val releases = json.optJSONObject("releases")?.optJSONArray(version)
      ?: throw IllegalStateException("LATEST_RELEASE_FILES_MISSING")
    for (i in 0 until releases.length()) {
      val file = releases.optJSONObject(i) ?: continue
      val filename = file.optString("filename")
      val packagetype = file.optString("packagetype")
      val pythonVersion = file.optString("python_version")
      if (packagetype != "bdist_wheel" || pythonVersion != "py3" || !filename.endsWith("-py3-none-any.whl")) {
        continue
      }
      val url = file.optString("url").takeIf { it.startsWith("https://") } ?: continue
      val sha256 = file.optJSONObject("digests")?.optString("sha256")?.takeIf { it.matches(Regex("^[a-fA-F0-9]{64}$")) } ?: continue
      val sizeBytes = file.optLong("size", -1L)
      if (sizeBytes <= 0 || sizeBytes > YT_DLP_MAX_WHEEL_BYTES) {
        continue
      }
      return YtDlpReleaseAsset(version, filename, url, sha256.lowercase(), sizeBytes)
    }
    throw IllegalStateException("COMPATIBLE_WHEEL_NOT_FOUND")
  }

  private fun httpGetJson(url: String): JSONObject {
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
      connectTimeout = YT_DLP_UPDATE_CONNECT_TIMEOUT_MS
      readTimeout = YT_DLP_UPDATE_READ_TIMEOUT_MS
      requestMethod = "GET"
      setRequestProperty("Accept", "application/json")
      setRequestProperty("User-Agent", DEFAULT_HTTP_USER_AGENT)
    }
    try {
      val code = connection.responseCode
      if (code !in 200..299) {
        throw IOException("PYPI_HTTP_$code")
      }
      val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
      return JSONObject(body)
    } finally {
      connection.disconnect()
    }
  }

  private fun downloadYtDlpWheel(release: YtDlpReleaseAsset, target: File) {
    emitYtDlpUpdateProgress("downloading", version = release.version, bytesDownloaded = 0L, bytesTotal = release.sizeBytes)
    val connection = (URL(release.url).openConnection() as HttpURLConnection).apply {
      connectTimeout = YT_DLP_UPDATE_CONNECT_TIMEOUT_MS
      readTimeout = YT_DLP_UPDATE_READ_TIMEOUT_MS
      requestMethod = "GET"
      setRequestProperty("Accept", "application/octet-stream")
      setRequestProperty("User-Agent", DEFAULT_HTTP_USER_AGENT)
    }
    val digest = MessageDigest.getInstance("SHA-256")
    var downloaded = 0L
    var lastEmitMs = 0L
    try {
      val code = connection.responseCode
      if (code !in 200..299) {
        throw IOException("WHEEL_HTTP_$code")
      }
      val total = connection.contentLengthLong.takeIf { it > 0 } ?: release.sizeBytes
      if (total > YT_DLP_MAX_WHEEL_BYTES) {
        throw IOException("WHEEL_TOO_LARGE")
      }
      target.parentFile?.mkdirs()
      connection.inputStream.use { input ->
        FileOutputStream(target).use { output ->
          val buffer = ByteArray(64 * 1024)
          while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            downloaded += read
            if (downloaded > YT_DLP_MAX_WHEEL_BYTES) {
              throw IOException("WHEEL_TOO_LARGE")
            }
            digest.update(buffer, 0, read)
            output.write(buffer, 0, read)
            val now = System.currentTimeMillis()
            if (now - lastEmitMs >= 500L) {
              emitYtDlpUpdateProgress("downloading", version = release.version, bytesDownloaded = downloaded, bytesTotal = total)
              lastEmitMs = now
            }
          }
          output.fd.sync()
        }
      }
      val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
      if (actualHash != release.sha256) {
        target.delete()
        throw IOException("WHEEL_HASH_MISMATCH")
      }
      emitYtDlpUpdateProgress("downloading", version = release.version, bytesDownloaded = downloaded, bytesTotal = total)
    } finally {
      connection.disconnect()
    }
  }

  private fun installYtDlpWheel(context: Context, release: YtDlpReleaseAsset, wheelFile: File): File {
    val root = ytDlpOverrideRoot(context).apply { mkdirs() }
    val stagingRoot = File(root, ".staging").apply { mkdirs() }
    val versionsRoot = File(root, "versions").apply { mkdirs() }
    val staging = File(stagingRoot, "${release.version}-${UUID.randomUUID()}")
    val versionDir = File(versionsRoot, release.version)
    safeDeleteYtDlpPath(context, staging)
    staging.mkdirs()
    try {
      extractWheelSafely(context, wheelFile, staging)
      verifyInstalledYtDlpPackage(staging, release.version)
      if (versionDir.exists()) {
        safeDeleteYtDlpPath(context, versionDir)
      }
      if (!staging.renameTo(versionDir)) {
        throw IOException("INSTALL_RENAME_FAILED")
      }
      return versionDir
    } catch (error: Throwable) {
      safeDeleteYtDlpPath(context, staging)
      throw error
    }
  }

  private fun extractWheelSafely(context: Context, wheelFile: File, staging: File) {
    val stagingCanonical = staging.canonicalFile
    var extractedBytes = 0L
    ZipInputStream(FileInputStream(wheelFile)).use { zip ->
      while (true) {
        val entry = zip.nextEntry ?: break
        validateYtDlpZipEntry(entry)
        val target = File(stagingCanonical, entry.name.replace('\\', '/')).canonicalFile
        ensureDescendant(stagingCanonical, target, "ZIP_ENTRY_ESCAPE")
        if (entry.isDirectory) {
          target.mkdirs()
        } else {
          target.parentFile?.mkdirs()
          FileOutputStream(target).use { output ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
              val read = zip.read(buffer)
              if (read < 0) break
              extractedBytes += read
              if (extractedBytes > YT_DLP_MAX_WHEEL_BYTES * 4) {
                throw IOException("WHEEL_EXTRACTED_TOO_LARGE")
              }
              output.write(buffer, 0, read)
            }
          }
        }
        zip.closeEntry()
      }
    }
    ensureDescendant(ytDlpOverrideRoot(context).canonicalFile, stagingCanonical, "STAGING_OUTSIDE_OVERRIDE_ROOT")
  }

  private fun validateYtDlpZipEntry(entry: ZipEntry) {
    val name = entry.name.replace('\\', '/')
    if (name.isBlank() || name.startsWith("/") || name.contains("../") || name == ".." || name.startsWith("../") || name.matches(Regex("^[A-Za-z]:.*"))) {
      throw IOException("UNSAFE_WHEEL_ENTRY")
    }
  }

  private fun verifyInstalledYtDlpPackage(packageRoot: File, expectedVersion: String) {
    if (!File(packageRoot, "yt_dlp").isDirectory) {
      throw IOException("YT_DLP_PACKAGE_DIR_MISSING")
    }
    val distInfo = packageRoot.listFiles()?.firstOrNull {
      it.isDirectory && it.name.startsWith("yt_dlp-") && it.name.endsWith(".dist-info")
    } ?: throw IOException("YT_DLP_DIST_INFO_MISSING")
    if (!File(distInfo, "METADATA").exists() && !File(distInfo, "WHEEL").exists()) {
      throw IOException("YT_DLP_METADATA_MISSING")
    }
    val metadata = File(distInfo, "METADATA")
    if (metadata.exists()) {
      val versionLine = metadata.readLines().firstOrNull { it.startsWith("Version:", ignoreCase = true) }
      val metadataVersion = versionLine?.substringAfter(":")?.trim()
      if (!ytDlpVersionsEqual(metadataVersion, expectedVersion)) {
        throw IOException("YT_DLP_METADATA_VERSION_MISMATCH")
      }
    }
  }

  private fun cleanupYtDlpUpdateScratch() {
    val context = requireNotNull(appContext.reactContext).applicationContext
    safeDeleteYtDlpPath(context, ytDlpUpdateCacheDir(context))
    val staging = File(ytDlpOverrideRoot(context), ".staging")
    if (staging.exists()) {
      staging.listFiles()?.forEach { safeDeleteYtDlpPath(context, it) }
    }
  }

  private fun requireSufficientYtDlpUpdateSpace(directory: File, wheelSizeBytes: Long) {
    directory.mkdirs()
    val required = max(YT_DLP_MIN_FREE_SPACE_BYTES, wheelSizeBytes * 4)
    val available = StatFs(directory.absolutePath).availableBytes
    if (available < required) {
      throw IOException("LOW_STORAGE")
    }
  }

  private fun readYtDlpManifest(context: Context): JSONObject {
    val file = ytDlpManifestFile(context)
    if (!file.exists()) {
      return JSONObject().put("schemaVersion", 1).put("installed", JSONObject())
    }
    return runCatching {
      JSONObject(file.readText())
    }.getOrElse {
      JSONObject().put("schemaVersion", 1).put("installed", JSONObject())
    }
  }

  private fun writeYtDlpManifest(context: Context, manifest: JSONObject) {
    val file = ytDlpManifestFile(context)
    val parent = file.parentFile ?: throw IOException("MANIFEST_PARENT_MISSING")
    parent.mkdirs()
    val tmp = File(parent, "${file.name}.tmp")
    manifest.put("schemaVersion", 1)
    FileOutputStream(tmp).use { output ->
      output.write(manifest.toString().toByteArray(Charsets.UTF_8))
      output.fd.sync()
    }
    if (!tmp.renameTo(file)) {
      if (file.exists() && !file.delete()) {
        throw IOException("MANIFEST_REPLACE_FAILED")
      }
      if (!tmp.renameTo(file)) {
        throw IOException("MANIFEST_RENAME_FAILED")
      }
    }
  }

  private fun safeDeleteYtDlpPath(context: Context, target: File) {
    val allowedRoots = listOf(ytDlpOverrideRoot(context).canonicalFile, ytDlpUpdateCacheDir(context).canonicalFile)
    val canonical = target.canonicalFile
    if (allowedRoots.none { isDescendantOrSelf(it, canonical) }) {
      throw IOException("UNSAFE_DELETE_PATH")
    }
    if (canonical.exists()) {
      canonical.deleteRecursively()
    }
  }

  private fun ensureDescendant(root: File, candidate: File, code: String) {
    if (!isDescendantOrSelf(root.canonicalFile, candidate.canonicalFile)) {
      throw IOException(code)
    }
  }

  private fun isDescendantOrSelf(root: File, candidate: File): Boolean {
    val rootPath = root.canonicalPath
    val candidatePath = candidate.canonicalPath
    return candidatePath == rootPath || candidatePath.startsWith(rootPath + File.separator)
  }

  /** Delete every downloaded yt-dlp except [keep], and forget them in the manifest. */
  private fun pruneYtDlpVersions(context: Context, keep: String, installed: JSONObject) {
    val versionsRoot = File(ytDlpOverrideRoot(context), "versions")
    versionsRoot.listFiles()?.forEach { dir ->
      if (dir.isDirectory && dir.name != keep) {
        safeDeleteYtDlpPath(context, dir)
      }
    }
    for (name in installed.keys().asSequence().toList()) {
      if (name != keep) installed.remove(name)
    }
  }

  private fun ytDlpOverrideRoot(context: Context): File = File(context.filesDir, YT_DLP_OVERRIDE_DIRNAME)

  private fun ytDlpManifestFile(context: Context): File = File(ytDlpOverrideRoot(context), YT_DLP_MANIFEST_FILENAME)

  private fun ytDlpUpdateCacheDir(context: Context): File = File(context.cacheDir, YT_DLP_UPDATE_CACHE_DIRNAME)

  private fun isStableYtDlpVersion(version: String?): Boolean {
    return version?.trim()?.matches(Regex("^\\d{4}\\.\\d{1,2}\\.\\d{1,2}$")) == true
  }

  private fun isNewerYtDlpVersion(candidate: String?, current: String?): Boolean {
    if (!isStableYtDlpVersion(candidate)) return false
    if (!isStableYtDlpVersion(current)) return true
    return compareYtDlpVersions(candidate!!, current!!) > 0
  }

  private fun ytDlpVersionsEqual(left: String?, right: String?): Boolean {
    if (left == null || right == null) return false
    val leftParts = parseYtDlpVersionParts(left)
    val rightParts = parseYtDlpVersionParts(right)
    if (leftParts != null && rightParts != null) {
      return leftParts == rightParts
    }
    return left.trim() == right.trim()
  }

  private fun compareYtDlpVersions(left: String, right: String): Int {
    val l = parseYtDlpVersionParts(left) ?: emptyList()
    val r = parseYtDlpVersionParts(right) ?: emptyList()
    for (i in 0 until 3) {
      val diff = (l.getOrNull(i) ?: 0) - (r.getOrNull(i) ?: 0)
      if (diff != 0) return diff
    }
    return 0
  }

  private fun parseYtDlpVersionParts(version: String?): List<Int>? {
    if (!isStableYtDlpVersion(version)) return null
    val parts = version!!.trim().split(".").map { it.toIntOrNull() ?: return null }
    if (parts.size != 3 || parts[0] < 1000 || parts[1] < 1 || parts[2] < 1) return null
    return parts
  }

  private fun emitYtDlpUpdateProgress(
    phase: String,
    version: String? = null,
    bytesDownloaded: Long? = null,
    bytesTotal: Long? = null,
    message: String? = null
  ) {
    val percent = if (bytesDownloaded != null && bytesTotal != null && bytesTotal > 0) {
      (bytesDownloaded.toDouble() / bytesTotal.toDouble() * 100.0).coerceIn(0.0, 100.0)
    } else {
      null
    }
    sendEvent(
      "ytDlpUpdateProgress",
      mapOf(
        "phase" to phase,
        "version" to version,
        "bytesDownloaded" to bytesDownloaded,
        "bytesTotal" to bytesTotal,
        "percent" to percent,
        "message" to message
      )
    )
  }

  private fun ensurePythonReady() {
    val context = requireNotNull(appContext.reactContext).applicationContext
    if (!Python.isStarted()) {
      Python.start(AndroidPlatform(context))
      applyYtDlpOverrideBootstrap(context)
      debug("Python runtime started")
    } else {
      debug("Python runtime already started")
    }
  }

  private fun getFreeSpaceMb(directory: File): Double {
    val stat = StatFs(directory.absolutePath)
    return stat.availableBytes.toDouble() / MB_IN_BYTES
  }

  private fun normalizeRuntimeError(result: JSONObject, ffmpegInfo: FfmpegInfo): JSONObject {
    if (result.optBoolean("success", false)) {
      return result
    }

    val code = result.optString("code", "")
    if (code != "MERGE_DEPENDENCY_MISSING" || ffmpegInfo.runtimeSource == "native_library") {
      return result
    }

    val reason = buildString {
      append("Native FFmpeg runtime unavailable")
      if (!ffmpegInfo.nativeLibraryDir.isNullOrBlank()) {
        append(" (nativeLibraryDir=")
        append(ffmpegInfo.nativeLibraryDir)
        append(")")
      }
      if (!ffmpegInfo.ffmpegProbeError.isNullOrBlank()) {
        append(". ffmpeg: ")
        append(ffmpegInfo.ffmpegProbeError)
      }
      if (!ffmpegInfo.ffprobeProbeError.isNullOrBlank()) {
        append(". ffprobe: ")
        append(ffmpegInfo.ffprobeProbeError)
      }
    }

    return JSONObject(result.toString()).apply {
      put("code", "FFMPEG_NATIVE_RUNTIME_UNAVAILABLE")
      put("message", reason)
    }
  }

  private fun guessMimeType(filename: String): String {
    return when (filename.substringAfterLast('.', "").lowercase()) {
      "mp4", "m4v", "mov", "3gp" -> "video/mp4"
      "webm" -> "video/webm"
      "mkv" -> "video/x-matroska"
      "avi" -> "video/x-msvideo"
      "jpg", "jpeg" -> "image/jpeg"
      "png" -> "image/png"
      "gif" -> "image/gif"
      else -> "video/mp4"
    }
  }

  private fun saveToMediaStoreInternal(
    filePath: String,
    filename: String,
    mimeType: String,
    dateTakenMs: Long,
    relativePath: String? = null
  ): Map<String, Any?> {
    val sourceFile = File(filePath)
    if (!sourceFile.exists() || !sourceFile.isFile) {
      throw IllegalArgumentException("FILE_NOT_FOUND")
    }
    return saveToMediaStoreWithWriter(filename, mimeType, dateTakenMs, relativePath) { output ->
      sourceFile.inputStream().use { input ->
        input.copyTo(output, PRIVATE_STREAM_BUFFER_BYTES)
      }
    }
  }

  private fun saveToMediaStoreWithWriter(
    filename: String,
    mimeType: String,
    dateTakenMs: Long,
    relativePath: String? = null,
    writer: (OutputStream) -> Unit
  ): Map<String, Any?> {
    val startedAtMs = System.currentTimeMillis()
    val resolvedMimeType = if (mimeType.isBlank()) guessMimeType(filename) else mimeType
    debug("[PRIVATE] MediaStore write start filename=$filename mimeType=$resolvedMimeType")

    val isVideo = resolvedMimeType.startsWith("video/")
    val isAudio = resolvedMimeType.startsWith("audio/")
    // MediaStore.Audio has no DATE_TAKEN column; only video/image do.
    val dateTakenColumn = if (isVideo) {
      MediaStore.Video.VideoColumns.DATE_TAKEN
    } else {
      MediaStore.Images.ImageColumns.DATE_TAKEN
    }
    val nowSeconds = System.currentTimeMillis() / 1000L

    val contentValues = ContentValues().apply {
      put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
      put(MediaStore.MediaColumns.MIME_TYPE, resolvedMimeType)
      put(MediaStore.MediaColumns.DATE_ADDED, nowSeconds)
      put(MediaStore.MediaColumns.DATE_MODIFIED, nowSeconds)
      if (!isAudio) {
        put(dateTakenColumn, dateTakenMs)
      }
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val targetRelativePath = relativePath?.trim().takeUnless { it.isNullOrBlank() }
          ?: when {
            isVideo -> Environment.DIRECTORY_DCIM
            isAudio -> Environment.DIRECTORY_MUSIC
            else -> Environment.DIRECTORY_PICTURES
          }
        put(
          MediaStore.MediaColumns.RELATIVE_PATH,
          targetRelativePath
        )
        put(MediaStore.MediaColumns.IS_PENDING, 1)
      }
    }

    val resolver = requireNotNull(appContext.reactContext).contentResolver
    val collection = when {
      isVideo -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
      isAudio -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
      else -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    }
    val uri = resolver.insert(collection, contentValues) ?: throw IOException("MEDIASTORE_INSERT_FAILED")
    debug("[PRIVATE] MediaStore insert success uri=$uri")

    runCatching {
      resolver.openOutputStream(uri)?.use { output ->
        debug("[PRIVATE] MediaStore output stream opened uri=$uri")
        BufferedOutputStream(output, PRIVATE_STREAM_BUFFER_BYTES).use { bufferedOutput ->
          writer(bufferedOutput)
          bufferedOutput.flush()
        }
      } ?: throw IOException("MEDIASTORE_OUTPUT_STREAM_FAILED")
    }.onFailure { error ->
      debug("[PRIVATE] MediaStore write failed uri=$uri error=${error.javaClass.simpleName}:${error.message}")
      runCatching { resolver.delete(uri, null, null) }
      throw error
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      val finalizeValues = ContentValues().apply {
        put(MediaStore.MediaColumns.IS_PENDING, 0)
        put(MediaStore.MediaColumns.DATE_MODIFIED, System.currentTimeMillis() / 1000L)
        if (!isAudio) {
          put(dateTakenColumn, dateTakenMs)
        }
      }
      resolver.update(uri, finalizeValues, null, null)
    }
    debug("[PRIVATE] MediaStore write completed uri=$uri elapsedMs=${System.currentTimeMillis() - startedAtMs}")

    return mapOf(
      "uri" to uri.toString(),
      "assetId" to uri.lastPathSegment
    )
  }

  private fun importFileToPrivateVault(
    sourceFilePath: String,
    filename: String,
    sourceUrl: String,
    mimeType: String
  ): PrivateVideoEntry {
    if (!PRIVATE_VAULT_FEATURE_FLAG) {
      throw IllegalStateException("PRIVATE_MODE_UNAVAILABLE")
    }
    val sourceFile = File(sourceFilePath)
    if (!sourceFile.exists() || !sourceFile.isFile || sourceFile.length() <= 0L) {
      throw IllegalStateException("PRIVATE_STORAGE_WRITE_FAILED")
    }

    val now = System.currentTimeMillis()
    val id = UUID.randomUUID().toString()
    val encFileName = "$id.pv4"
    val objectsDir = privateVaultObjectsDir(create = true)
    val encryptedTarget = File(objectsDir, encFileName)
    val encryptedTemp = File(objectsDir, ".$encFileName.partial")
    val sourceHash = sha256Base64(sourceUrl)
    val safeTitle = sanitizePrivateTitle(filename)
    val containerExt = run {
      val fromMime = extensionForMimeType(mimeType).takeIf { it.isNotBlank() }
      val fromName = filename.substringAfterLast('.', "").lowercase().takeIf { it.isNotBlank() && it.length <= 5 }
      (fromMime ?: fromName ?: "mp4").lowercase()
    }

    // Remove stale partials from previously interrupted operations before space checks.
    cleanupPrivateVaultPartials()

    val sourceBytes = sourceFile.length()
    val requiredBytes = sourceBytes + PRIVATE_MIN_FREE_SPACE_MARGIN_BYTES
    val availableBytes = objectsDir.usableSpace

    debug(
      "[PRIVATE] import start source=$sourceFilePath sourceBytes=$sourceBytes " +
        "availableBytes=$availableBytes requiredBytes=$requiredBytes target=${encryptedTarget.absolutePath}"
    )

    if (availableBytes in 1 until requiredBytes) {
      throw IllegalStateException(
        "PRIVATE_STORAGE_WRITE_FAILED: INSUFFICIENT_SPACE available_bytes=$availableBytes required_bytes=$requiredBytes"
      )
    }

    // Probe duration first via MMR metadata (fast, succeeds in many codec cases where
    // a full frame decode would fail). The result feeds both the thumbnail's seek
    // offset and the persisted PrivateVideoEntry.durationSec field (which the vault
    // list uses for the "sort by duration" mode).
    val extractedDurationSec: Double? = runCatching {
      ThumbnailGenerator.extractDuration(sourceFile)
    }.getOrNull()

    val thumbnailResult: ThumbnailGenerator.Result? = runCatching {
      val ffmpegPath = cachedFfmpegInfo?.takeIf { it.exists && it.runtimeSource == "native_library" }?.path
      ThumbnailGenerator.generate(
        plaintextSource = sourceFile,
        ffmpegPath = ffmpegPath,
        durationSec = extractedDurationSec,
      )
    }.getOrNull()

    runCatching {
      synchronized(privateVaultIoLock) {
        encryptFileForPrivateVaultV4(sourceFile, encryptedTemp, id)
      }
      if (!encryptedTemp.renameTo(encryptedTarget)) {
        encryptedTemp.copyTo(encryptedTarget, overwrite = true)
        encryptedTemp.delete()
      }
    }.onFailure {
      runCatching { encryptedTemp.delete() }
      runCatching { encryptedTarget.delete() }
      val cause = it.message ?: it.javaClass.simpleName
      throw IllegalStateException("PRIVATE_STORAGE_WRITE_FAILED: $cause", it)
    }

    val thumbFileName = if (thumbnailResult != null) {
      runCatching {
        synchronized(privateVaultIoLock) {
          encryptThumbnailBytesV4(thumbnailResult.data, id, "$id.t4")
        }
      }.getOrNull()
    } else null

    val entry = PrivateVideoEntry(
      id = id,
      title = safeTitle,
      createdAt = now,
      updatedAt = now,
      sourceUrlHash = sourceHash,
      mimeType = mimeType,
      durationSec = extractedDurationSec,
      sizeBytesEncrypted = encryptedTarget.length(),
      cipherVersion = PRIVATE_STORE_VERSION_V4,
      encFileName = encFileName,
      containerExt = containerExt,
      thumbFileName = thumbFileName,
      thumbWidth = thumbnailResult?.width?.takeIf { it > 0 },
      thumbHeight = thumbnailResult?.height?.takeIf { it > 0 },
    )

    synchronized(privateVaultLock) {
      val index = readPrivateVaultIndex()
      val items = index.optJSONArray("items") ?: JSONArray()
      items.put(privateVideoEntryToJson(entry))
      index.put("items", items)
      writePrivateVaultIndex(index)
    }
    debug(
      "[PRIVATE] import success id=${entry.id} cipher=${entry.cipherVersion} " +
        "encryptedBytes=${entry.sizeBytesEncrypted} sourceDeletedPending=true"
    )
    return entry
  }

  private fun listPrivateVideosInternal(): List<Map<String, Any?>> {
    val parsed = synchronized(privateVaultLock) {
      val index = readPrivateVaultIndex()
      val items = index.optJSONArray("items") ?: JSONArray()
      val out = mutableListOf<PrivateVideoEntry>()
      for (i in 0 until items.length()) {
        privateVideoEntryFromJson(items.optJSONObject(i))?.let { out.add(it) }
      }
      out.sortedByDescending { it.updatedAt }
    }
    if (parsed.any { it.thumbFileName != null }) {
      runCatching { ensureVaultLoopbackServer() }
    }
    return parsed.map { entry -> privateVideoEntryToMap(entry) }
  }

  private fun privateVideoEntryToMap(entry: PrivateVideoEntry): Map<String, Any?> {
    val thumbnailUri = if (entry.thumbFileName != null) {
      runCatching { vaultLoopbackServer?.thumbnailUrl(entry.id) }.getOrNull()
    } else null
    return mapOf(
      "id" to entry.id,
      "title" to entry.title,
      "createdAt" to entry.createdAt,
      "updatedAt" to entry.updatedAt,
      "mimeType" to entry.mimeType,
      "durationSec" to entry.durationSec,
      "sizeBytesEncrypted" to entry.sizeBytesEncrypted,
      "cipherVersion" to entry.cipherVersion,
      "containerExt" to entry.containerExt,
      "hasThumbnail" to (entry.thumbFileName != null),
      "thumbnailUri" to thumbnailUri,
      "thumbWidth" to entry.thumbWidth,
      "thumbHeight" to entry.thumbHeight,
      "migrationFailed" to entry.migrationFailed,
      "migrationFailedCode" to entry.migrationFailedCode,
      "tags" to entry.tags,
      "folderId" to entry.folderId,
    )
  }

  private fun tagDefinitionToMap(def: TagDefinition): Map<String, Any?> = mapOf(
    "id" to def.id,
    "name" to def.name,
    "color" to def.color,
    "createdAt" to def.createdAt,
  )

  private fun folderDefinitionToMap(def: FolderDefinition): Map<String, Any?> = mapOf(
    "id" to def.id,
    "name" to def.name,
    "createdAt" to def.createdAt,
  )

  private fun deletePrivateVideoInternal(id: String): Boolean {
    if (id.isBlank()) return false
    synchronized(privateVaultIoLock) {
      synchronized(privateVaultLock) {
        val index = readPrivateVaultIndex()
        val items = index.optJSONArray("items") ?: JSONArray()
        val remaining = JSONArray()
        var removed: PrivateVideoEntry? = null
        for (i in 0 until items.length()) {
          val entry = privateVideoEntryFromJson(items.optJSONObject(i))
          if (entry == null) continue
          if (entry.id == id) {
            removed = entry
            continue
          }
          remaining.put(privateVideoEntryToJson(entry))
        }
        if (removed == null) {
          return false
        }
        index.put("items", remaining)
        writePrivateVaultIndex(index)
        runCatching { File(privateVaultObjectsDir(create = true), removed.encFileName).delete() }
        deleteThumbnailFile(removed.thumbFileName)
        runCatching { File(privatePlaybackCacheDir(create = true), "${removed.id}.mp4").delete() }
        // A private meme is this vault entry, so it goes with it.
        runCatching { memes.store.forgetVault(removed.id) }
        runCatching {
          synchronized(vaultLoopbackLock) {
            // Invalidate any in-flight playback sessions for the deleted entry.
            vaultLoopbackServer?.invalidateAllVideoSessions()
          }
        }
        return true
      }
    }
  }

  private fun makeVideoPublicInternal(id: String): Map<String, Any?> {
    return mapOf(
      "success" to false,
      "code" to "PRIVATE_EXPORT_DISABLED",
      "message" to "PRIVATE_EXPORT_DISABLED"
    )
  }

  // ----- Tag CRUD + tagging entries -----

  private fun listTagsInternal(): List<Map<String, Any?>> {
    return synchronized(privateVaultLock) {
      val index = readPrivateVaultIndex()
      val arr = index.optJSONArray("tagDefinitions") ?: JSONArray()
      val out = mutableListOf<Map<String, Any?>>()
      for (i in 0 until arr.length()) {
        tagDefinitionFromJson(arr.optJSONObject(i))?.let { out.add(tagDefinitionToMap(it)) }
      }
      out.sortedBy { (it["createdAt"] as? Long) ?: 0L }
    }
  }

  private fun createTagInternal(rawName: String, colorHint: String?): Map<String, Any?> {
    val sanitized = rawName.trim()
    if (sanitized.isBlank()) {
      throw IllegalStateException("PRIVATE_TAG_INVALID_NAME")
    }
    if (sanitized.length > TAG_NAME_MAX_LENGTH) {
      throw IllegalStateException("PRIVATE_TAG_NAME_TOO_LONG")
    }
    return synchronized(privateVaultLock) {
      val index = readPrivateVaultIndex()
      val arr = index.optJSONArray("tagDefinitions") ?: JSONArray()
      for (i in 0 until arr.length()) {
        val existing = tagDefinitionFromJson(arr.optJSONObject(i)) ?: continue
        if (existing.name.equals(sanitized, ignoreCase = true)) {
          throw IllegalStateException("PRIVATE_TAG_NAME_TAKEN")
        }
      }
      val resolvedColor = colorHint?.takeIf { HEX_COLOR_REGEX.matches(it) }
        ?: TAG_COLOR_PALETTE[arr.length() % TAG_COLOR_PALETTE.size]
      val tag = TagDefinition(
        id = "tg_${UUID.randomUUID().toString().replace("-", "")}",
        name = sanitized,
        color = resolvedColor,
        createdAt = System.currentTimeMillis(),
      )
      arr.put(tagDefinitionToJson(tag))
      index.put("tagDefinitions", arr)
      writePrivateVaultIndex(index)
      tagDefinitionToMap(tag)
    }
  }

  private fun renameTagInternal(id: String, rawName: String): Map<String, Any?> {
    if (id.isBlank()) throw IllegalStateException("PRIVATE_TAG_NOT_FOUND")
    val sanitized = rawName.trim()
    if (sanitized.isBlank()) throw IllegalStateException("PRIVATE_TAG_INVALID_NAME")
    if (sanitized.length > TAG_NAME_MAX_LENGTH) throw IllegalStateException("PRIVATE_TAG_NAME_TOO_LONG")
    return synchronized(privateVaultLock) {
      val index = readPrivateVaultIndex()
      val arr = index.optJSONArray("tagDefinitions") ?: JSONArray()
      var foundIndex = -1
      var found: TagDefinition? = null
      for (i in 0 until arr.length()) {
        val existing = tagDefinitionFromJson(arr.optJSONObject(i)) ?: continue
        if (existing.id == id) {
          found = existing
          foundIndex = i
        } else if (existing.name.equals(sanitized, ignoreCase = true)) {
          throw IllegalStateException("PRIVATE_TAG_NAME_TAKEN")
        }
      }
      val current = found ?: throw IllegalStateException("PRIVATE_TAG_NOT_FOUND")
      val updated = current.copy(name = sanitized)
      arr.put(foundIndex, tagDefinitionToJson(updated))
      index.put("tagDefinitions", arr)
      writePrivateVaultIndex(index)
      tagDefinitionToMap(updated)
    }
  }

  private fun setTagColorInternal(id: String, color: String): Map<String, Any?> {
    if (id.isBlank()) throw IllegalStateException("PRIVATE_TAG_NOT_FOUND")
    if (!HEX_COLOR_REGEX.matches(color)) throw IllegalStateException("PRIVATE_TAG_INVALID_COLOR")
    return synchronized(privateVaultLock) {
      val index = readPrivateVaultIndex()
      val arr = index.optJSONArray("tagDefinitions") ?: JSONArray()
      var foundIndex = -1
      var found: TagDefinition? = null
      for (i in 0 until arr.length()) {
        val tag = tagDefinitionFromJson(arr.optJSONObject(i)) ?: continue
        if (tag.id == id) {
          found = tag
          foundIndex = i
          break
        }
      }
      val current = found ?: throw IllegalStateException("PRIVATE_TAG_NOT_FOUND")
      val updated = current.copy(color = color)
      arr.put(foundIndex, tagDefinitionToJson(updated))
      index.put("tagDefinitions", arr)
      writePrivateVaultIndex(index)
      tagDefinitionToMap(updated)
    }
  }

  private fun deleteTagInternal(id: String): Map<String, Any?> {
    if (id.isBlank()) return mapOf("success" to false, "code" to "PRIVATE_TAG_NOT_FOUND")
    return synchronized(privateVaultLock) {
      val index = readPrivateVaultIndex()
      val arr = index.optJSONArray("tagDefinitions") ?: JSONArray()
      var existed = false
      val remaining = JSONArray()
      for (i in 0 until arr.length()) {
        val tag = tagDefinitionFromJson(arr.optJSONObject(i)) ?: continue
        if (tag.id == id) {
          existed = true
          continue
        }
        remaining.put(tagDefinitionToJson(tag))
      }
      if (!existed) {
        return@synchronized mapOf("success" to false, "code" to "PRIVATE_TAG_NOT_FOUND")
      }
      index.put("tagDefinitions", remaining)
      // Cascade-remove the deleted tag id from every entry's tags[].
      val items = index.optJSONArray("items") ?: JSONArray()
      var removedFromCount = 0
      val now = System.currentTimeMillis()
      for (i in 0 until items.length()) {
        val entry = privateVideoEntryFromJson(items.optJSONObject(i)) ?: continue
        if (entry.tags.contains(id)) {
          val updatedEntry = entry.copy(tags = entry.tags - id, updatedAt = now)
          items.put(i, privateVideoEntryToJson(updatedEntry))
          removedFromCount += 1
        }
      }
      index.put("items", items)
      writePrivateVaultIndex(index)
      mapOf("success" to true, "removedFromCount" to removedFromCount)
    }
  }

  private fun setEntryTagsInternal(entryIds: List<String>, tagIds: List<String>): Map<String, Any?> {
    if (entryIds.isEmpty()) return mapOf("success" to true, "updatedCount" to 0)
    return synchronized(privateVaultLock) {
      val index = readPrivateVaultIndex()
      val defsArr = index.optJSONArray("tagDefinitions") ?: JSONArray()
      val validTagIds = HashSet<String>(defsArr.length())
      for (i in 0 until defsArr.length()) {
        val tag = tagDefinitionFromJson(defsArr.optJSONObject(i)) ?: continue
        validTagIds.add(tag.id)
      }
      // Drop unknown tag ids defensively. Preserve caller's order on the validated set.
      val filteredTags = tagIds.asSequence().distinct().filter { validTagIds.contains(it) }.toList()
      val items = index.optJSONArray("items") ?: JSONArray()
      val targetIdSet = entryIds.toHashSet()
      val now = System.currentTimeMillis()
      var updatedCount = 0
      for (i in 0 until items.length()) {
        val entry = privateVideoEntryFromJson(items.optJSONObject(i)) ?: continue
        if (!targetIdSet.contains(entry.id)) continue
        val updatedEntry = entry.copy(tags = filteredTags, updatedAt = now)
        items.put(i, privateVideoEntryToJson(updatedEntry))
        updatedCount += 1
      }
      index.put("items", items)
      writePrivateVaultIndex(index)
      mapOf("success" to true, "updatedCount" to updatedCount)
    }
  }

  // ----- Folder CRUD + moving entries -----

  private fun listFoldersInternal(): List<Map<String, Any?>> {
    return synchronized(privateVaultLock) {
      val index = readPrivateVaultIndex()
      val arr = index.optJSONArray("folders") ?: JSONArray()
      val out = mutableListOf<Map<String, Any?>>()
      for (i in 0 until arr.length()) {
        folderDefinitionFromJson(arr.optJSONObject(i))?.let { out.add(folderDefinitionToMap(it)) }
      }
      out.sortedBy { (it["createdAt"] as? Long) ?: 0L }
    }
  }

  private fun createFolderInternal(rawName: String): Map<String, Any?> {
    val sanitized = rawName.trim()
    if (sanitized.isBlank()) throw IllegalStateException("PRIVATE_FOLDER_INVALID_NAME")
    if (sanitized.length > FOLDER_NAME_MAX_LENGTH) throw IllegalStateException("PRIVATE_FOLDER_NAME_TOO_LONG")
    return synchronized(privateVaultLock) {
      val index = readPrivateVaultIndex()
      val arr = index.optJSONArray("folders") ?: JSONArray()
      for (i in 0 until arr.length()) {
        val existing = folderDefinitionFromJson(arr.optJSONObject(i)) ?: continue
        if (existing.name.equals(sanitized, ignoreCase = true)) {
          throw IllegalStateException("PRIVATE_FOLDER_NAME_TAKEN")
        }
      }
      val folder = FolderDefinition(
        id = "fl_${UUID.randomUUID().toString().replace("-", "")}",
        name = sanitized,
        createdAt = System.currentTimeMillis(),
      )
      arr.put(folderDefinitionToJson(folder))
      index.put("folders", arr)
      writePrivateVaultIndex(index)
      folderDefinitionToMap(folder)
    }
  }

  private fun renameFolderInternal(id: String, rawName: String): Map<String, Any?> {
    if (id.isBlank()) throw IllegalStateException("PRIVATE_FOLDER_NOT_FOUND")
    val sanitized = rawName.trim()
    if (sanitized.isBlank()) throw IllegalStateException("PRIVATE_FOLDER_INVALID_NAME")
    if (sanitized.length > FOLDER_NAME_MAX_LENGTH) throw IllegalStateException("PRIVATE_FOLDER_NAME_TOO_LONG")
    return synchronized(privateVaultLock) {
      val index = readPrivateVaultIndex()
      val arr = index.optJSONArray("folders") ?: JSONArray()
      var foundIndex = -1
      var found: FolderDefinition? = null
      for (i in 0 until arr.length()) {
        val existing = folderDefinitionFromJson(arr.optJSONObject(i)) ?: continue
        if (existing.id == id) {
          found = existing
          foundIndex = i
        } else if (existing.name.equals(sanitized, ignoreCase = true)) {
          throw IllegalStateException("PRIVATE_FOLDER_NAME_TAKEN")
        }
      }
      val current = found ?: throw IllegalStateException("PRIVATE_FOLDER_NOT_FOUND")
      val updated = current.copy(name = sanitized)
      arr.put(foundIndex, folderDefinitionToJson(updated))
      index.put("folders", arr)
      writePrivateVaultIndex(index)
      folderDefinitionToMap(updated)
    }
  }

  private fun deleteFolderInternal(id: String): Map<String, Any?> {
    if (id.isBlank()) return mapOf("success" to false, "code" to "PRIVATE_FOLDER_NOT_FOUND")
    return synchronized(privateVaultLock) {
      val index = readPrivateVaultIndex()
      val arr = index.optJSONArray("folders") ?: JSONArray()
      var existed = false
      val remaining = JSONArray()
      for (i in 0 until arr.length()) {
        val folder = folderDefinitionFromJson(arr.optJSONObject(i)) ?: continue
        if (folder.id == id) {
          existed = true
          continue
        }
        remaining.put(folderDefinitionToJson(folder))
      }
      if (!existed) {
        return@synchronized mapOf("success" to false, "code" to "PRIVATE_FOLDER_NOT_FOUND")
      }
      index.put("folders", remaining)
      // Cascade-move contained entries to root.
      val items = index.optJSONArray("items") ?: JSONArray()
      val now = System.currentTimeMillis()
      var movedToRootCount = 0
      for (i in 0 until items.length()) {
        val entry = privateVideoEntryFromJson(items.optJSONObject(i)) ?: continue
        if (entry.folderId == id) {
          val updatedEntry = entry.copy(folderId = null, updatedAt = now)
          items.put(i, privateVideoEntryToJson(updatedEntry))
          movedToRootCount += 1
        }
      }
      index.put("items", items)
      writePrivateVaultIndex(index)
      mapOf("success" to true, "movedToRootCount" to movedToRootCount)
    }
  }

  private fun setEntryFolderInternal(entryIds: List<String>, folderId: String?): Map<String, Any?> {
    if (entryIds.isEmpty()) return mapOf("success" to true, "updatedCount" to 0)
    return synchronized(privateVaultLock) {
      val index = readPrivateVaultIndex()
      // Validate the folder id (null is fine = root).
      if (folderId != null) {
        val arr = index.optJSONArray("folders") ?: JSONArray()
        var exists = false
        for (i in 0 until arr.length()) {
          val folder = folderDefinitionFromJson(arr.optJSONObject(i)) ?: continue
          if (folder.id == folderId) {
            exists = true
            break
          }
        }
        if (!exists) throw IllegalStateException("PRIVATE_FOLDER_NOT_FOUND")
      }
      val items = index.optJSONArray("items") ?: JSONArray()
      val targetIdSet = entryIds.toHashSet()
      val now = System.currentTimeMillis()
      var updatedCount = 0
      for (i in 0 until items.length()) {
        val entry = privateVideoEntryFromJson(items.optJSONObject(i)) ?: continue
        if (!targetIdSet.contains(entry.id)) continue
        val updatedEntry = entry.copy(folderId = folderId, updatedAt = now)
        items.put(i, privateVideoEntryToJson(updatedEntry))
        updatedCount += 1
      }
      index.put("items", items)
      writePrivateVaultIndex(index)
      mapOf("success" to true, "updatedCount" to updatedCount)
    }
  }

  private fun renamePrivateVideoInternal(id: String, newTitle: String): Map<String, Any?> {
    if (id.isBlank()) {
      return mapOf("success" to false, "code" to "PRIVATE_VIDEO_NOT_FOUND")
    }
    val sanitized = sanitizePrivateTitle(newTitle)
    if (sanitized.isBlank()) {
      return mapOf("success" to false, "code" to "PRIVATE_INVALID_TITLE")
    }
    val nowMs = System.currentTimeMillis()
    val updated = synchronized(privateVaultLock) {
      val index = readPrivateVaultIndex()
      val items = index.optJSONArray("items") ?: JSONArray()
      var found: PrivateVideoEntry? = null
      var replaceIndex = -1
      for (i in 0 until items.length()) {
        val entry = privateVideoEntryFromJson(items.optJSONObject(i)) ?: continue
        if (entry.id == id) {
          found = entry
          replaceIndex = i
          break
        }
      }
      val current = found ?: return@synchronized null
      val backfilledContainer = current.containerExt ?: resolveContainerExt(current)
      val next = current.copy(
        title = sanitized,
        updatedAt = nowMs,
        containerExt = backfilledContainer,
      )
      items.put(replaceIndex, privateVideoEntryToJson(next))
      index.put("items", items)
      writePrivateVaultIndex(index)
      next
    } ?: return mapOf("success" to false, "code" to "PRIVATE_VIDEO_NOT_FOUND")
    return mapOf("success" to true, "entry" to privateVideoEntryToMap(updated))
  }

  private fun getPrivateThumbnailUriInternal(id: String): Map<String, Any?> {
    val entry = findPrivateVideoById(id) ?: return mapOf("success" to false, "code" to "PRIVATE_VIDEO_NOT_FOUND")
    if (entry.thumbFileName == null) {
      return mapOf("success" to true, "uri" to null, "hasThumbnail" to false)
    }
    val server = try { ensureVaultLoopbackServer() } catch (t: Throwable) {
      return mapOf("success" to false, "code" to (t.message?.substringBefore(':') ?: "PRIVATE_VIDEO_NOT_FOUND"))
    }
    val uri = server.thumbnailUrl(entry.id)
    return mapOf("success" to true, "uri" to uri, "hasThumbnail" to (uri != null))
  }

  private val vaultMigratorHost = object : VaultMigrator.Host {
    override fun loadMigrationCandidates(): List<VaultMigrator.Candidate> {
      return synchronized(privateVaultLock) {
        val index = readPrivateVaultIndex()
        val items = index.optJSONArray("items") ?: JSONArray()
        val out = mutableListOf<VaultMigrator.Candidate>()
        for (i in 0 until items.length()) {
          val entry = privateVideoEntryFromJson(items.optJSONObject(i)) ?: continue
          if (entry.cipherVersion == PRIVATE_STORE_VERSION_V4) continue
          if (entry.cipherVersion == PRIVATE_STORE_VERSION_V1) continue // legacy v1 already blocked
          // Note: previously-failed entries (migrationFailed=true) are intentionally retried
          // on each invocation. The flag is informational — it surfaces "last attempt failed"
          // to the UI, but should not exclude the entry from retry. The flag is cleared on
          // successful migration by commitMigratedEntry.
          out.add(
            VaultMigrator.Candidate(
              id = entry.id,
              encFileName = entry.encFileName,
              cipherVersion = entry.cipherVersion,
              title = entry.title,
              sizeBytesEncrypted = entry.sizeBytesEncrypted,
            )
          )
        }
        out.sortedBy { it.id }
      }
    }

    override fun loadMigrationCursor(): String? {
      return synchronized(privateVaultLock) {
        val index = readPrivateVaultIndex()
        index.optString("migrationCursor").trim().ifBlank { null }
      }
    }

    override fun storeMigrationCursor(entryId: String?) {
      synchronized(privateVaultLock) {
        val index = readPrivateVaultIndex()
        if (entryId == null) index.remove("migrationCursor") else index.put("migrationCursor", entryId)
        writePrivateVaultIndex(index)
      }
    }

    override fun decryptLegacyToStream(encryptedFile: File, sink: OutputStream, cipherVersion: String) {
      when (cipherVersion) {
        PRIVATE_STORE_VERSION_V3 -> decryptPrivateVaultFileV3ToStream(encryptedFile, sink, traceId = "migrate")
        PRIVATE_STORE_VERSION_V2 -> decryptPrivateVaultFileV2ToStream(encryptedFile, sink, traceId = "migrate")
        else -> throw IllegalStateException("PRIVATE_MIGRATION_UNSUPPORTED_VERSION: $cipherVersion")
      }
    }

    override fun openV4EncryptingStream(output: OutputStream, entryId: String): OutputStream {
      val dek = requireVaultDek(VaultAuthPolicy.OP_MIGRATE)
      return VaultCipherV4.openEncryptingStream(output, entryId, dek)
    }

    override fun commitMigratedEntry(entryId: String, newEncFileName: String, newCipherSize: Long) {
      synchronized(privateVaultLock) {
        val index = readPrivateVaultIndex()
        val items = index.optJSONArray("items") ?: JSONArray()
        for (i in 0 until items.length()) {
          val entry = privateVideoEntryFromJson(items.optJSONObject(i)) ?: continue
          if (entry.id != entryId) continue
          val containerBackfilled = entry.containerExt ?: resolveContainerExt(entry)
          val next = entry.copy(
            cipherVersion = PRIVATE_STORE_VERSION_V4,
            encFileName = newEncFileName,
            sizeBytesEncrypted = newCipherSize,
            updatedAt = System.currentTimeMillis(),
            containerExt = containerBackfilled,
            migrationFailed = false,
            migrationFailedCode = null,
            migrationFailedDetail = null,
          )
          items.put(i, privateVideoEntryToJson(next))
          break
        }
        index.put("items", items)
        writePrivateVaultIndex(index)
      }
    }

    override fun markEntryMigrationFailed(entryId: String, code: String, detail: String?) {
      synchronized(privateVaultLock) {
        val index = readPrivateVaultIndex()
        val items = index.optJSONArray("items") ?: JSONArray()
        for (i in 0 until items.length()) {
          val entry = privateVideoEntryFromJson(items.optJSONObject(i)) ?: continue
          if (entry.id != entryId) continue
          val next = entry.copy(
            migrationFailed = true,
            migrationFailedCode = code,
            migrationFailedDetail = detail,
            updatedAt = System.currentTimeMillis(),
          )
          items.put(i, privateVideoEntryToJson(next))
          break
        }
        index.put("items", items)
        writePrivateVaultIndex(index)
      }
    }

    override fun objectsDir(): File = privateVaultObjectsDir(create = true)
  }

  private fun startPrivateVaultMigrationInternal(): Map<String, Any?> = vaultSession.withLease(
    VaultAuthPolicy.OP_MIGRATE
  ) { runPrivateVaultMigration() }

  /** Re-encrypts every v3 item. Minutes to hours, so it runs inside a lease. */
  private fun runPrivateVaultMigration(): Map<String, Any?> {
    val context = appContext.reactContext ?: return mapOf("success" to false, "code" to "PRIVATE_MODE_UNAVAILABLE")
    val candidates = vaultMigratorHost.loadMigrationCandidates()
    if (candidates.isEmpty()) {
      return mapOf("success" to true, "total" to 0, "processed" to 0, "succeeded" to 0, "failed" to 0, "outcome" to "COMPLETED")
    }
    val vaultUsedBytes = candidates.sumOf { it.sizeBytesEncrypted }
    val preflight = VaultMigrator.checkPreflight(context, vaultUsedBytes)
    if (!preflight.ok) {
      return mapOf(
        "success" to false,
        "code" to (preflight.blockingCode ?: "PRIVATE_MIGRATION_BLOCKED"),
        "freeBytes" to preflight.freeBytes,
        "requiredBytes" to preflight.requiredBytes,
        "batteryLevel" to preflight.batteryLevel,
        "isCharging" to preflight.isCharging,
      )
    }
    val existing = activeMigrationCancel
    if (existing != null && !existing.isCancelled()) {
      return mapOf("success" to false, "code" to "PRIVATE_MIGRATION_ALREADY_RUNNING")
    }
    val cancelToken = VaultMigrator.CancelToken()
    activeMigrationCancel = cancelToken
    val migrator = VaultMigrator(vaultMigratorHost)
    scope.launch {
      try {
        migrator.migrate(cancelToken) { progress ->
          lastMigrationProgress = progress
          emitMigrationProgress(progress)
        }
      } catch (t: Throwable) {
        debug("[PRIVATE] migration crashed: ${t.javaClass.simpleName}:${t.message}")
      } finally {
        if (activeMigrationCancel === cancelToken) {
          activeMigrationCancel = null
        }
      }
    }
    return mapOf(
      "success" to true,
      "total" to candidates.size,
      "outcome" to "STARTED",
    )
  }

  private fun cancelPrivateVaultMigrationInternal(): Map<String, Any?> {
    val token = activeMigrationCancel
    if (token == null) {
      return mapOf("success" to true, "wasRunning" to false)
    }
    token.cancel()
    return mapOf("success" to true, "wasRunning" to true)
  }

  private fun emitMigrationProgress(progress: VaultMigrator.Progress) {
    val payload = mapOf(
      "total" to progress.total,
      "processed" to progress.processed,
      "succeeded" to progress.succeeded,
      "failed" to progress.failed,
      "skipped" to progress.skipped,
      "currentEntryId" to progress.currentEntryId,
      "currentTitle" to progress.currentTitle,
      "lastErrorCode" to progress.lastError?.code,
      "lastErrorDetail" to progress.lastError?.detail,
    )
    runCatching { sendEvent("privateVaultMigrationProgress", payload) }
  }

  private fun copyPrivateVideoToPublicGalleryInternal(id: String): Map<String, Any?> {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
      return mapOf(
        "success" to false,
        "code" to "PRIVATE_PUBLIC_COPY_LEGACY_UNSUPPORTED",
        "message" to "PRIVATE_PUBLIC_COPY_LEGACY_UNSUPPORTED"
      )
    }
    if (id.isBlank()) {
      return mapOf(
        "success" to false,
        "code" to "PRIVATE_VIDEO_NOT_FOUND",
        "message" to "PRIVATE_VIDEO_NOT_FOUND"
      )
    }

    val entry = synchronized(privateVaultLock) {
      val index = readPrivateVaultIndex()
      val items = index.optJSONArray("items") ?: JSONArray()
      var found: PrivateVideoEntry? = null
      for (i in 0 until items.length()) {
        val parsed = privateVideoEntryFromJson(items.optJSONObject(i))
        if (parsed?.id == id) {
          found = parsed
          break
        }
      }
      found
    } ?: return mapOf(
      "success" to false,
      "code" to "PRIVATE_VIDEO_NOT_FOUND",
      "message" to "PRIVATE_VIDEO_NOT_FOUND"
    )

    val encryptedFile = File(privateVaultObjectsDir(create = true), entry.encFileName)
    if (!encryptedFile.exists() || !encryptedFile.isFile) {
      return mapOf(
        "success" to false,
        "code" to "PRIVATE_VIDEO_NOT_FOUND",
        "message" to "PRIVATE_VIDEO_NOT_FOUND"
      )
    }

    return runCatching {
      val effectiveVersion = detectPrivateCipherVersion(encryptedFile, entry.cipherVersion)
      if (effectiveVersion == PRIVATE_STORE_VERSION_V1) {
        throw IllegalStateException("PRIVATE_LEGACY_VAULT_UNSUPPORTED")
      }
      val filename = sanitizePrivateTitle(entry.title)
      synchronized(privateVaultIoLock) {
        saveToMediaStoreWithWriter(
          filename = filename,
          mimeType = entry.mimeType.ifBlank { guessMimeType(filename) },
          dateTakenMs = entry.updatedAt.takeIf { it > 0L } ?: System.currentTimeMillis(),
          relativePath = PRIVATE_PUBLIC_COPY_RELATIVE_PATH
        ) { output ->
          decryptPrivateVaultFileToOutput(encryptedFile, output, effectiveVersion, traceId = "copy_${entry.id.take(8)}", entryId = entry.id)
        }
      }
    }.map { saved ->
      mapOf(
        "success" to true,
        "uri" to saved["uri"]
      )
    }.getOrElse { error ->
      val code = when (error.message) {
        "PRIVATE_LEGACY_VAULT_UNSUPPORTED" -> "PRIVATE_LEGACY_VAULT_UNSUPPORTED"
        else -> "PRIVATE_PUBLIC_COPY_FAILED"
      }
      debug("[PRIVATE] copyPrivateVideoToPublicGallery failed id=$id error=${error.javaClass.simpleName}:${error.message}")
      mapOf(
        "success" to false,
        "code" to code,
        "message" to code
      )
    }
  }

  private fun pickAndImportVideoToPrivateVaultInternal(): Map<String, Any?> {
    if (!PRIVATE_VAULT_FEATURE_FLAG) {
      return mapOf("success" to false, "code" to "PRIVATE_MODE_UNAVAILABLE", "message" to "PRIVATE_MODE_UNAVAILABLE")
    }

    val context = appContext.reactContext
      ?: return mapOf("success" to false, "code" to "PRIVATE_IMPORT_FAILED", "message" to "PRIVATE_IMPORT_FAILED")

    val resultRef = AtomicReference<PrivateVaultImportActivity.Result?>()
    val latch = CountDownLatch(1)
    val launched = PrivateVaultImportActivity.launch(context) { result ->
      resultRef.set(result)
      latch.countDown()
    }
    if (!launched) {
      return mapOf("success" to false, "code" to "PRIVATE_IMPORT_FAILED", "message" to "PRIVATE_IMPORT_FAILED")
    }

    val completed = runCatching { latch.await(PRIVATE_IMPORT_PICK_TIMEOUT_SECONDS, TimeUnit.SECONDS) }.getOrDefault(false)
    if (!completed) {
      PrivateVaultImportActivity.cancelPendingWith("PRIVATE_IMPORT_PICK_CANCELLED")
      return mapOf(
        "success" to false,
        "code" to "PRIVATE_IMPORT_PICK_CANCELLED",
        "message" to "PRIVATE_IMPORT_PICK_CANCELLED"
      )
    }

    val pickerResult = resultRef.get()
      ?: return mapOf("success" to false, "code" to "PRIVATE_IMPORT_FAILED", "message" to "PRIVATE_IMPORT_FAILED")
    if (!pickerResult.uri.isNullOrBlank()) {
      val imported = runCatching {
        importVideoFromContentUriToPrivateVault(Uri.parse(pickerResult.uri))
      }.getOrElse { error ->
        val code = when (error.message) {
          "PRIVATE_IMPORT_UNSUPPORTED_TYPE" -> "PRIVATE_IMPORT_UNSUPPORTED_TYPE"
          "PRIVATE_STORAGE_WRITE_FAILED" -> "PRIVATE_STORAGE_WRITE_FAILED"
          "PRIVATE_MODE_UNAVAILABLE" -> "PRIVATE_MODE_UNAVAILABLE"
          else -> "PRIVATE_IMPORT_FAILED"
        }
        return mapOf("success" to false, "code" to code, "message" to code)
      }
      return mapOf(
        "success" to true,
        "item" to privateVideoEntryToMap(imported)
      )
    }

    val code = pickerResult.code?.ifBlank { "PRIVATE_IMPORT_PICK_CANCELLED" } ?: "PRIVATE_IMPORT_PICK_CANCELLED"
    return mapOf(
      "success" to false,
      "code" to code,
      "message" to (pickerResult.message ?: code)
    )
  }

  private fun pickAndImportSoundsInternal(): Map<String, Any?> {
    if (!soundsStore.isSupported()) {
      return mapOf("success" to false, "code" to SoundsStore.ERR_UNSUPPORTED_OS, "message" to SoundsStore.ERR_UNSUPPORTED_OS)
    }
    val context = appContext.reactContext
      ?: return mapOf("success" to false, "code" to SoundsImportActivity.CODE_IMPORT_FAILED, "message" to SoundsImportActivity.CODE_IMPORT_FAILED)

    val resultRef = AtomicReference<SoundsImportActivity.Result?>()
    val latch = CountDownLatch(1)
    val launched = SoundsImportActivity.launch(context) { result ->
      resultRef.set(result)
      latch.countDown()
    }
    if (!launched) {
      return mapOf("success" to false, "code" to SoundsImportActivity.CODE_IMPORT_FAILED, "message" to SoundsImportActivity.CODE_IMPORT_FAILED)
    }

    val completed = runCatching { latch.await(PRIVATE_IMPORT_PICK_TIMEOUT_SECONDS, TimeUnit.SECONDS) }.getOrDefault(false)
    if (!completed) {
      SoundsImportActivity.cancelPendingWith(SoundsImportActivity.CODE_PICK_CANCELLED)
      return mapOf("success" to false, "code" to SoundsImportActivity.CODE_PICK_CANCELLED, "message" to SoundsImportActivity.CODE_PICK_CANCELLED)
    }

    val pickerResult = resultRef.get()
      ?: return mapOf("success" to false, "code" to SoundsImportActivity.CODE_IMPORT_FAILED, "message" to SoundsImportActivity.CODE_IMPORT_FAILED)

    if (pickerResult.uris.isEmpty()) {
      val code = pickerResult.code?.ifBlank { SoundsImportActivity.CODE_PICK_CANCELLED } ?: SoundsImportActivity.CODE_PICK_CANCELLED
      return mapOf("success" to false, "code" to code, "message" to (pickerResult.message ?: code))
    }

    return runCatching {
      val uris = pickerResult.uris.map { Uri.parse(it) }
      val importResult = soundsStore.importFromUris(uris)
      val failedCount = (importResult["failedCount"] as? Int) ?: 0
      val importedCount = (importResult["importedCount"] as? Int) ?: 0
      if (failedCount > 0) {
        addError("SOUNDS_IMPORT_PARTIAL: imported=$importedCount failed=$failedCount (see SoundsStore log for per-file cause)")
      }
      mapOf("success" to true) + importResult
    }.getOrElse { error ->
      val message = error.message ?: SoundsImportActivity.CODE_IMPORT_FAILED
      // Always surface this — without it, a whole-batch import failure is swallowed
      // into the JS result and never reaches logcat or the in-app failure log.
      Log.e(tag, "Sound import failed: ${error.javaClass.simpleName}: $message", error)
      addError("SOUNDS_IMPORT_FAILED: ${error.javaClass.simpleName}: $message")
      mapOf("success" to false, "code" to SoundsImportActivity.CODE_IMPORT_FAILED, "message" to message)
    }
  }

  private fun importVideoFromContentUriToPrivateVault(sourceUri: Uri): PrivateVideoEntry {
    val context = requireNotNull(appContext.reactContext)
    val resolver = context.contentResolver
    val resolvedMimeType = resolver.getType(sourceUri)?.trim().orEmpty().ifBlank { "video/mp4" }
    if (!resolvedMimeType.startsWith("video/")) {
      throw IllegalStateException("PRIVATE_IMPORT_UNSUPPORTED_TYPE")
    }

    val sourceName = queryDisplayName(resolver, sourceUri)
      ?: "imported_${System.currentTimeMillis()}.${extensionForMimeType(resolvedMimeType)}"
    val filename = sanitizePrivateTitle(sourceName)
    val tempDir = privateImportCacheDir(create = true)
    val tempFile = File(tempDir, "${UUID.randomUUID()}_${filename.take(80)}")

    try {
      resolver.openInputStream(sourceUri)?.use { input ->
        FileOutputStream(tempFile).use { output ->
          input.copyTo(output, PRIVATE_STREAM_BUFFER_BYTES)
          output.flush()
        }
      } ?: throw IllegalStateException("PRIVATE_IMPORT_FAILED")

      if (!tempFile.exists() || tempFile.length() <= 0L) {
        throw IllegalStateException("PRIVATE_IMPORT_FAILED")
      }
      return importFileToPrivateVault(
        sourceFilePath = tempFile.absolutePath,
        filename = filename,
        sourceUrl = sourceUri.toString(),
        mimeType = resolvedMimeType
      )
    } catch (error: Throwable) {
      throw IllegalStateException(error.message ?: "PRIVATE_IMPORT_FAILED")
    } finally {
      runCatching { tempFile.delete() }
    }
  }

  private fun preparePrivatePlaybackInternal(id: String, traceId: String = "n/a"): Map<String, Any?> {
    if (id.isBlank()) {
      throw IllegalStateException("PRIVATE_VIDEO_NOT_FOUND")
    }
    privateTrace(traceId, "prepare internal start id=$id thread=${Thread.currentThread().name}")
    val lookupStartedAt = System.currentTimeMillis()
    val entry = synchronized(privateVaultLock) {
      val index = readPrivateVaultIndex()
      val items = index.optJSONArray("items") ?: JSONArray()
      var found: PrivateVideoEntry? = null
      for (i in 0 until items.length()) {
        val parsed = privateVideoEntryFromJson(items.optJSONObject(i))
        if (parsed?.id == id) {
          found = parsed
          break
        }
      }
      found
    } ?: throw IllegalStateException("PRIVATE_VIDEO_NOT_FOUND")
    privateTrace(
      traceId,
      "prepare internal index hit id=${entry.id} elapsedMs=${System.currentTimeMillis() - lookupStartedAt} cipherHint=${entry.cipherVersion}"
    )

    val encryptedFile = File(privateVaultObjectsDir(create = true), entry.encFileName)
    if (!encryptedFile.exists() || !encryptedFile.isFile) {
      privateTrace(traceId, "prepare internal encrypted file missing path=${encryptedFile.absolutePath}")
      throw IllegalStateException("PRIVATE_VIDEO_NOT_FOUND")
    }
    privateTrace(
      traceId,
      "prepare internal encrypted file ready name=${encryptedFile.name} size=${encryptedFile.length()} path=${encryptedFile.absolutePath}"
    )

    privateTrace(traceId, "prepare internal cleanup playback cache start")
    cleanupPrivatePlaybackCacheInternal()
    privateTrace(traceId, "prepare internal cleanup playback cache done")
    val effectiveVersion = detectPrivateCipherVersion(encryptedFile, entry.cipherVersion)
    privateTrace(traceId, "prepare internal decrypt plan version=$effectiveVersion")
    if (effectiveVersion == PRIVATE_STORE_VERSION_V1) {
      privateTrace(
        traceId,
        "prepare internal legacy v1 blocked for playback id=${entry.id}; requires delete + re-download in v2"
      )
      throw IllegalStateException("PRIVATE_LEGACY_VAULT_UNSUPPORTED")
    }

    if (effectiveVersion == PRIVATE_STORE_VERSION_V4) {
      return try {
        val server = ensureVaultLoopbackServer()
        val session = server.registerVideoSession(entry.id)
        // A film longer than the unlock window must not stall part way through. The lease is
        // released when the loopback server drops the session, or when the module shuts down.
        playbackLeases[session.token] = vaultSession.beginLease("playback")
        val url = server.videoUrl(session)
          ?: throw IllegalStateException("PRIVATE_VIDEO_NOT_FOUND")
        privateTrace(traceId, "prepare internal v4 streaming uri assigned session=${session.token.take(6)}…")
        mapOf(
          "success" to true,
          "tempUri" to url,
          "mimeType" to entry.mimeType.ifBlank { guessMimeType(entry.title) },
          "streaming" to true,
        )
      } catch (t: Throwable) {
        privateTrace(traceId, "prepare internal v4 streaming failed id=${entry.id} error=${t.javaClass.simpleName}:${t.message}")
        throw IllegalStateException("PRIVATE_VIDEO_NOT_FOUND", t)
      }
    }

    val playbackDir = privatePlaybackCacheDir(create = true)
    val suffix = resolveContainerExt(entry)
    val output = File(playbackDir, "${entry.id}.$suffix")
    privateTrace(traceId, "prepare internal decrypt output=${output.absolutePath} outputExists=${output.exists()}")

    runCatching {
      val lockWaitStartedAt = System.currentTimeMillis()
      privateTrace(traceId, "prepare internal waiting io-lock")
      synchronized(privateVaultIoLock) {
        val lockAcquiredAt = System.currentTimeMillis()
        privateTrace(traceId, "prepare internal io-lock acquired waitMs=${lockAcquiredAt - lockWaitStartedAt}")
        val decryptStartedAt = System.currentTimeMillis()
        decryptPrivateVaultFile(encryptedFile, output, effectiveVersion, traceId)
        privateTrace(
          traceId,
          "prepare internal decrypt done elapsedMs=${System.currentTimeMillis() - decryptStartedAt} outputExists=${output.exists()} outputSize=${output.length()}"
        )
      }
    }.onFailure {
      privateTrace(traceId, "prepare internal failed id=${entry.id} error=${it.javaClass.simpleName}:${it.message}")
      runCatching { output.delete() }
      if (it is IllegalStateException && it.message == "PRIVATE_LEGACY_VAULT_UNSUPPORTED") {
        throw it
      }
      throw IllegalStateException("PRIVATE_VIDEO_NOT_FOUND")
    }
    privateTrace(traceId, "prepare internal success id=${entry.id} tempUri=${Uri.fromFile(output)}")
    return mapOf(
      "success" to true,
      "tempUri" to Uri.fromFile(output).toString(),
      "mimeType" to entry.mimeType.ifBlank { guessMimeType(entry.title) },
      "streaming" to false,
    )
  }

  private fun cleanupPrivatePlaybackCacheInternal() {
    runCatching { privatePlaybackCacheDir(create = false).deleteRecursively() }
    runCatching { privateExportCacheDir(create = false).deleteRecursively() }
    runCatching { privateImportCacheDir(create = false).deleteRecursively() }
    runCatching {
      synchronized(vaultLoopbackLock) {
        vaultLoopbackServer?.invalidateAllVideoSessions()
      }
    }
  }

  private fun setSecureScreenInternal(enabled: Boolean) {
    val activity = appContext.currentActivity ?: return
    val latch = CountDownLatch(1)
    activity.runOnUiThread {
      if (enabled) {
        activity.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
      } else {
        activity.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
      }
      latch.countDown()
    }
    runCatching { latch.await(2, TimeUnit.SECONDS) }
  }

  private fun cleanupPrivateVaultPartials() {
    runCatching {
      val objectsDir = privateVaultObjectsDir(create = false)
      if (!objectsDir.exists()) return@runCatching
      objectsDir.listFiles()
        ?.filter { it.isFile && it.name.startsWith(".") && it.name.endsWith(".partial") }
        ?.forEach { it.delete() }
    }
  }

  /**
   * Null when the vault is locked, never zero.
   *
   * Counting the listing needs the key now that it is encrypted, and the diagnostics screen
   * has to render regardless. Reporting zero would read as an empty vault.
   */
  private fun countPrivateVaultItems(): Int? {
    return synchronized(privateVaultLock) {
      runCatching {
        val index = readPrivateVaultIndex()
        (index.optJSONArray("items") ?: JSONArray()).length()
      }.getOrNull()
    }
  }

  private fun countPrivateVaultLegacyItems(): Int? {
    return synchronized(privateVaultLock) {
      runCatching {
        val index = readPrivateVaultIndex()
        val items = index.optJSONArray("items") ?: JSONArray()
        var legacy = 0
        for (i in 0 until items.length()) {
          val entry = privateVideoEntryFromJson(items.optJSONObject(i)) ?: continue
          if (entry.cipherVersion == PRIVATE_STORE_VERSION_V1) {
            legacy += 1
          }
        }
        legacy
      }.getOrNull()
    }
  }

  private fun privateVaultRoot(create: Boolean): File {
    val dir = File(requireNotNull(appContext.reactContext).filesDir, PRIVATE_VAULT_DIRNAME)
    if (create) {
      dir.mkdirs()
    }
    return dir
  }

  private fun privateVaultObjectsDir(create: Boolean): File {
    val dir = File(privateVaultRoot(create), PRIVATE_VAULT_OBJECTS_DIRNAME)
    if (create) {
      dir.mkdirs()
    }
    return dir
  }

  private fun privateVaultIndexFile(createParent: Boolean = true): File {
    val root = privateVaultRoot(createParent)
    return File(root, PRIVATE_VAULT_INDEX_FILENAME)
  }

  private fun privateVaultThumbsDir(create: Boolean): File {
    val dir = File(privateVaultRoot(create), PRIVATE_VAULT_THUMBS_DIRNAME)
    if (create) dir.mkdirs()
    return dir
  }

  private fun privateVaultKeysDir(create: Boolean): File {
    val dir = File(privateVaultRoot(create), PRIVATE_VAULT_KEYS_DIRNAME)
    if (create) dir.mkdirs()
    return dir
  }

  private fun findPrivateVideoById(id: String): PrivateVideoEntry? {
    if (id.isBlank()) return null
    return synchronized(privateVaultLock) {
      val index = readPrivateVaultIndex()
      val items = index.optJSONArray("items") ?: JSONArray()
      for (i in 0 until items.length()) {
        val entry = privateVideoEntryFromJson(items.optJSONObject(i))
        if (entry?.id == id) return@synchronized entry
      }
      null
    }
  }

  private val vaultKeystoreKeys by lazy { VaultKeystoreKeys() }

  /** Files under `private_vault/keys/`, which is all the key box owns. */
  private fun vaultKeyBox(): VaultKeyBox {
    val dir = File(privateVaultRoot(create = true), PRIVATE_VAULT_KEYS_DIRNAME).apply { mkdirs() }
    val store = object : VaultKeyBox.Store {
      override fun read(name: String): ByteArray? =
        File(dir, name).takeIf { it.isFile }?.readBytes()
      override fun write(name: String, bytes: ByteArray) = atomicWriteBytes(File(dir, name), bytes)
      override fun delete(name: String) { File(dir, name).delete() }
      override fun exists(name: String): Boolean = File(dir, name).isFile
    }
    return VaultKeyBox(store, vaultKeystoreKeys)
  }

  /**
   * Unwraps the vault key from the Keystore.
   *
   * The only place the master key is touched. Everything else works from the session's copy,
   * which is what makes a window on the master key affordable: no hot path ever comes back
   * here, so a lapsed window cannot stall a video part way through.
   */
  private fun unwrapVaultDekFromKeystore(): ByteArray {
    synchronized(privateVaultIoLock) {
      val vaultRoot = privateVaultRoot(create = true)
      val box = vaultKeyBox()
      // A vault with no wrapped key yet is a new one: the old path creates it, under the
      // unbound key, which is exactly the state the key box's defaults describe.
      val existing = File(File(vaultRoot, PRIVATE_VAULT_KEYS_DIRNAME), VaultKeyBox.DEK_V4_FILE)
      if (!existing.isFile && !File(File(vaultRoot, PRIVATE_VAULT_KEYS_DIRNAME), VaultKeyBox.STATE_FILE).isFile) {
        try {
          return VaultCipherV4.getOrCreateVaultDek(vaultRoot) { getOrCreatePrivateVaultMasterKeyV2() }
        } catch (kpe: KeyPermanentlyInvalidatedException) {
          throw IllegalStateException("PRIVATE_KEY_INVALIDATED: ${kpe.message}", kpe)
        }
      }
      return try {
        box.dek()
      } catch (invalidated: VaultKeyBox.MasterKeyError.Invalidated) {
        // The key is gone: a new fingerprint, or the screen lock removed. While the old wrap
        // is still there this costs nothing but a fallback.
        val recovery = box.recoverFromInvalidatedKey()
        debug("[PRIVATE] key invalidated, recovery=${recovery.outcome} ${recovery.detail ?: ""}")
        if (recovery.outcome == VaultKeyBox.Outcome.ROLLED_BACK) {
          box.dek()
        } else {
          throw IllegalStateException(recovery.detail ?: "PRIVATE_KEY_INVALIDATED")
        }
      } catch (needsAuth: VaultKeyBox.MasterKeyError.AuthRequired) {
        throw IllegalStateException("PRIVATE_AUTH_REQUIRED")
      }
    }
  }

  /** Moves the vault onto a key the Keystore will not use without a recent unlock. */
  private fun upgradeVaultKeyInternal(): Map<String, Any?> {
    val (granted, reason) = authenticatePrivateAccessInternal("migrate")
    if (!granted) return mapOf("success" to false, "code" to (reason ?: "PRIVATE_AUTH_FAILED"))
    val box = vaultKeyBox()
    val result = runCatching { box.migrateToV3() }.getOrElse {
      return mapOf("success" to false, "code" to (it.message ?: "PRIVATE_KEY_UPGRADE_FAILED"))
    }
    // The session still holds the old key; it opens the same vault either way, but reopening
    // keeps the two in step.
    if (result.outcome == VaultKeyBox.Outcome.MIGRATED) runCatching { openVaultSession() }
    return mapOf(
      "success" to (result.outcome != VaultKeyBox.Outcome.FAILED),
      "outcome" to result.outcome.name,
      "code" to result.detail,
    ) + vaultKeyStateInternal()
  }

  /** Destroys the old unbound key. Refused until there is a way back that is not the device. */
  private fun finaliseVaultKeyInternal(force: Boolean): Map<String, Any?> {
    val result = runCatching { vaultKeyBox().finalise(force) }.getOrElse {
      return mapOf("success" to false, "code" to (it.message ?: "PRIVATE_KEY_UPGRADE_FAILED"))
    }
    return mapOf(
      "success" to (result.outcome != VaultKeyBox.Outcome.FAILED),
      "outcome" to result.outcome.name,
      "code" to result.detail,
    ) + vaultKeyStateInternal()
  }

  private fun vaultKeyStateInternal(): Map<String, Any?> {
    val box = runCatching { vaultKeyBox() }.getOrNull()
      ?: return mapOf("activeSlot" to null, "hasRecoverySlot" to false, "fullyMigrated" to false)
    return runCatching {
      mapOf(
        "activeSlot" to box.activeSlot(),
        "authBound" to (box.activeSlot() == VaultKeyBox.SLOT_KEYSTORE_V3),
        "hasRecoverySlot" to box.hasRecoverySlot(),
        "fullyMigrated" to box.isFullyMigrated(),
      )
    }.getOrElse { mapOf("activeSlot" to null, "hasRecoverySlot" to false, "fullyMigrated" to false) }
  }

  /** Opens the session. Called once per unlock, after the user has actually authenticated. */
  private fun openVaultSession(authAt: Long = System.currentTimeMillis()) {
    val dek = unwrapVaultDekFromKeystore()
    try {
      vaultSession.unlock(dek, authAt)
    } finally {
      dek.fill(0)
    }
  }

  /**
   * The vault key for one operation, subject to the session window and the policy table.
   *
   * Callers get their own copy and should wipe it. Handing out the session's array and then
   * wiping it on lock would truncate whatever was mid-stream, with no error to explain it.
   */
  private fun requireVaultDek(operation: String): ByteArray {
    try {
      return vaultSession.requireDek(operation)
    } catch (locked: VaultSession.VaultLocked) {
      throw IllegalStateException("PRIVATE_VAULT_LOCKED")
    } catch (stale: VaultSession.StepUpRequired) {
      throw IllegalStateException("PRIVATE_STEP_UP_REQUIRED")
    }
  }

  private fun encryptFileForPrivateVaultV4(source: File, output: File, entryId: String) {
    val dek = requireVaultDek(VaultAuthPolicy.OP_IMPORT)
    source.inputStream().use { input ->
      output.outputStream().use { fileOut ->
        VaultCipherV4.encryptStream(input, fileOut, entryId, dek)
      }
    }
  }

  private fun decryptPrivateVaultFileV4(source: File, output: File, entryId: String) {
    output.outputStream().use { out ->
      decryptPrivateVaultFileV4ToStream(source, out, entryId)
    }
  }

  private fun decryptPrivateVaultFileV4ToStream(source: File, output: OutputStream, entryId: String) {
    val dek = requireVaultDek(VaultAuthPolicy.OP_PLAY)
    source.inputStream().use { input ->
      VaultCipherV4.decryptStream(input, output, entryId, dek)
    }
  }

  private fun encryptThumbnailBytesV4(jpegBytes: ByteArray, entryId: String, thumbName: String): String? {
    val dek = requireVaultDek(VaultAuthPolicy.OP_IMPORT)
    val target = File(privateVaultThumbsDir(create = true), thumbName)
    val tmp = File(target.parentFile, "$thumbName.tmp")
    return try {
      java.io.ByteArrayInputStream(jpegBytes).use { input ->
        tmp.outputStream().use { fileOut ->
          VaultCipherV4.encryptStream(input, fileOut, thumbnailAad(entryId), dek)
        }
      }
      if (target.exists() && !target.delete()) {
        tmp.delete()
        return null
      }
      if (tmp.renameTo(target)) thumbName else { tmp.delete(); null }
    } catch (t: Throwable) {
      runCatching { tmp.delete() }
      null
    }
  }

  private fun loadThumbnailBytesV4(entry: PrivateVideoEntry): ByteArray? {
    val name = entry.thumbFileName ?: return null
    val file = File(privateVaultThumbsDir(create = false), name)
    if (!file.exists() || !file.isFile) return null
    val out = ByteArrayOutputStream(64 * 1024)
    return try {
      decryptPrivateVaultFileV4ToStream(file, out, thumbnailAad(entry.id))
      out.toByteArray()
    } catch (t: Throwable) {
      null
    }
  }

  private fun deleteThumbnailFile(thumbFileName: String?) {
    if (thumbFileName.isNullOrBlank()) return
    runCatching { File(privateVaultThumbsDir(create = false), thumbFileName).delete() }
  }

  private fun thumbnailAad(entryId: String): String = "thumb:$entryId"

  private fun resolveContainerExt(entry: PrivateVideoEntry): String {
    entry.containerExt?.takeIf { it.isNotBlank() }?.let { return it.lowercase() }
    val fromMime = extensionForMimeType(entry.mimeType).takeIf { it.isNotBlank() }
    val fromTitle = entry.title.substringAfterLast('.', "").lowercase().takeIf { it.isNotBlank() && it.length <= 5 }
    return (fromMime ?: fromTitle ?: "mp4").lowercase()
  }

  private fun ensureVaultLoopbackServer(): VaultLoopbackServer {
    val existing = vaultLoopbackServer
    if (existing != null && existing.isAlive) {
      existing.ensureStarted()
      return existing
    }
    return synchronized(vaultLoopbackLock) {
      var server = vaultLoopbackServer
      if (server == null || !server.isAlive) {
        val captured = arrayOfNulls<VaultLoopbackServer>(1)
        server = VaultLoopbackServer(
          provider = vaultLoopbackProvider,
          onAutoStopped = {
            synchronized(vaultLoopbackLock) {
              if (vaultLoopbackServer === captured[0]) {
                vaultLoopbackServer = null
              }
            }
          },
        )
        captured[0] = server
        vaultLoopbackServer = server
      }
      server.ensureStarted()
      server
    }
  }

  private fun stopVaultLoopbackServer() {
    synchronized(vaultLoopbackLock) {
      runCatching { vaultLoopbackServer?.stop() }
      vaultLoopbackServer = null
    }
    // Every playback session died with it, so nothing is holding the key open any more.
    playbackLeases.values.forEach { vaultSession.endLease(it) }
    playbackLeases.clear()
  }

  private val vaultLoopbackProvider = object : VaultLoopbackProvider {
    override fun openVideoResource(entryId: String): VaultVideoResource? {
      val entry = findPrivateVideoById(entryId) ?: return null
      if (entry.cipherVersion != PRIVATE_STORE_VERSION_V4) return null
      val file = File(privateVaultObjectsDir(create = false), entry.encFileName)
      if (!file.exists() || !file.isFile) return null
      return try {
        val dek = requireVaultDek(VaultAuthPolicy.OP_PLAY)
        val channel = VaultCipherV4.openDecryptingChannel(file, entryId, dek)
        val plaintextLength = channel.size()
        val contentType = entry.mimeType.ifBlank { guessMimeType(entry.title) }
        VaultVideoResource(channel, contentType, plaintextLength)
      } catch (t: Throwable) {
        null
      }
    }

    override fun loadThumbnailResource(entryId: String): VaultThumbnailResource? {
      val entry = findPrivateVideoById(entryId) ?: return null
      val bytes = loadThumbnailBytesV4(entry) ?: return null
      return VaultThumbnailResource(bytes, VaultLoopbackServer.MIME_JPEG)
    }
  }

  private fun privatePlaybackCacheDir(create: Boolean): File {
    val dir = File(requireNotNull(appContext.reactContext).cacheDir, PRIVATE_PLAYBACK_CACHE_DIRNAME)
    if (create) {
      dir.mkdirs()
    }
    return dir
  }

  private fun privateExportCacheDir(create: Boolean): File {
    val dir = File(requireNotNull(appContext.reactContext).cacheDir, PRIVATE_EXPORT_CACHE_DIRNAME)
    if (create) {
      dir.mkdirs()
    }
    return dir
  }

  private fun privateImportCacheDir(create: Boolean): File {
    val dir = File(requireNotNull(appContext.reactContext).cacheDir, PRIVATE_IMPORT_CACHE_DIRNAME)
    if (create) {
      dir.mkdirs()
    }
    return dir
  }

  private fun queryDisplayName(resolver: android.content.ContentResolver, sourceUri: Uri): String? {
    return runCatching {
      resolver.query(sourceUri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
          val columnIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
          if (columnIndex >= 0) {
            cursor.getString(columnIndex)?.trim()?.takeIf { it.isNotBlank() }
          } else null
        } else null
      }
    }.getOrNull()
  }

  private fun extensionForMimeType(mimeType: String): String {
    return when {
      mimeType.equals("video/mp4", ignoreCase = true) -> "mp4"
      mimeType.equals("video/webm", ignoreCase = true) -> "webm"
      mimeType.equals("video/x-matroska", ignoreCase = true) -> "mkv"
      mimeType.equals("video/quicktime", ignoreCase = true) -> "mov"
      mimeType.equals("video/3gpp", ignoreCase = true) -> "3gp"
      else -> "mp4"
    }
  }

  private fun defaultPrivateVaultIndex(): JSONObject {
    return JSONObject().apply {
      put("version", 3)
      put("items", JSONArray())
      put("tagDefinitions", JSONArray())
      put("folders", JSONArray())
    }
  }

  private fun privateVaultIndexFileV2(createParent: Boolean = true): File {
    return File(privateVaultRoot(createParent), PRIVATE_VAULT_INDEX_V2_FILENAME)
  }

  /**
   * Encrypt a listing left in the clear by an older build.
   *
   * Order matters: seal, read back and compare, and only then destroy the plain text. A crash
   * at any point leaves a readable vault.
   */
  private fun migratePrivateVaultIndexToEncrypted(legacy: File): JSONObject {
    val parsed = runCatching { normalisePrivateVaultIndex(JSONObject(legacy.readText(Charsets.UTF_8))) }
      .getOrElse {
        // A plain-text index that will not parse is a problem to hand to the user, not one to
        // convert into an encrypted index that will not parse.
        throw IllegalStateException("PRIVATE_INDEX_MIGRATION_FAILED")
      }

    writePrivateVaultIndex(parsed)

    val verified = runCatching {
      JSONObject(VaultIndexCodec.open(requireVaultDek(VaultAuthPolicy.OP_LIST), privateVaultIndexFileV2().readBytes()))
    }.getOrNull()
    val sameItems = verified?.optJSONArray("items")?.length() == parsed.optJSONArray("items")?.length()
    if (verified == null || !sameItems) {
      privateVaultIndexFileV2().delete()
      throw IllegalStateException("PRIVATE_INDEX_MIGRATION_FAILED")
    }

    // Overwrite before unlinking. A plain delete on a journalling filesystem leaves the
    // titles recoverable, which is most of what was being hidden.
    runCatching {
      java.io.RandomAccessFile(legacy, "rws").use { handle ->
        handle.write(ByteArray(handle.length().toInt().coerceAtMost(1 shl 20)))
      }
    }
    legacy.delete()
    debug("[PRIVATE] index migrated to an encrypted listing")
    return parsed
  }

  private fun normalisePrivateVaultIndex(parsed: JSONObject): JSONObject {
    if (!parsed.has("items")) parsed.put("items", JSONArray())
    if (!parsed.has("tagDefinitions")) parsed.put("tagDefinitions", JSONArray())
    if (!parsed.has("folders")) parsed.put("folders", JSONArray())
    return parsed
  }

  private fun readPrivateVaultIndex(): JSONObject {
    val encrypted = privateVaultIndexFileV2(createParent = true)
    val legacy = privateVaultIndexFile(createParent = true)
    if (!encrypted.exists()) {
      if (legacy.exists()) return migratePrivateVaultIndexToEncrypted(legacy)
      val initial = defaultPrivateVaultIndex()
      writePrivateVaultIndex(initial)
      return initial
    }
    return runCatching {
      val parsed = JSONObject(VaultIndexCodec.open(requireVaultDek(VaultAuthPolicy.OP_LIST), encrypted.readBytes()))
      if (!parsed.has("items")) {
        parsed.put("items", JSONArray())
      }
      // v2.2.0: backwards-compatible additive fields. Older index.json (version 2)
      // lacks these; treat missing as empty. Don't bump the on-disk version field
      // here — that happens implicitly on the next write via writePrivateVaultIndex
      // when callers do their own mutations.
      if (!parsed.has("tagDefinitions")) {
        parsed.put("tagDefinitions", JSONArray())
      }
      if (!parsed.has("folders")) {
        parsed.put("folders", JSONArray())
      }
      parsed
    }.getOrElse {
      // An index that will not parse must never quietly become an empty one. The videos
      // are still encrypted in objects/, and the next mutation calls writePrivateVaultIndex
      // — so returning a default here would persist the emptiness and orphan every file in
      // the vault. Refuse instead, and let the caller surface it.
      throw IllegalStateException("PRIVATE_INDEX_UNREADABLE")
    }
  }

  private fun writePrivateVaultIndex(index: JSONObject) {
    // Sealed under a key derived from the vault's own DEK, so it inherits whatever protects
    // that — including, later, an auth-bound master key — with no second key to migrate.
    val sealed = VaultIndexCodec.seal(requireVaultDek(VaultAuthPolicy.OP_LIST), index.toString())
    atomicWriteBytes(privateVaultIndexFileV2(createParent = true), sealed)
  }

  private fun privateVideoEntryFromJson(obj: JSONObject?): PrivateVideoEntry? {
    if (obj == null) return null
    val id = obj.optString("id").trim()
    val title = obj.optString("title").trim()
    val createdAt = obj.optLong("createdAt", 0L)
    val updatedAt = obj.optLong("updatedAt", createdAt)
    val sourceUrlHash = obj.optString("sourceUrlHash").trim()
    val mimeType = obj.optString("mimeType").trim()
    val sizeBytesEncrypted = obj.optLong("sizeBytesEncrypted", 0L)
    val cipherVersion = obj.optString("cipherVersion").trim().ifBlank { PRIVATE_STORE_VERSION_V1 }
    val encFileName = obj.optString("encFileName").trim()
    if (id.isBlank() || title.isBlank() || encFileName.isBlank()) {
      return null
    }
    val tagsArr = obj.optJSONArray("tags")
    val tagsList = if (tagsArr != null) {
      val out = ArrayList<String>(tagsArr.length())
      for (i in 0 until tagsArr.length()) {
        val tagId = tagsArr.optString(i).trim()
        if (tagId.isNotBlank()) out.add(tagId)
      }
      out
    } else emptyList()
    return PrivateVideoEntry(
      id = id,
      title = title,
      createdAt = createdAt,
      updatedAt = updatedAt,
      sourceUrlHash = sourceUrlHash,
      mimeType = mimeType,
      durationSec = obj.optDouble("durationSec", Double.NaN).takeIf { !it.isNaN() },
      sizeBytesEncrypted = sizeBytesEncrypted,
      cipherVersion = cipherVersion,
      encFileName = encFileName,
      containerExt = obj.optString("containerExt").trim().ifBlank { null },
      thumbFileName = obj.optString("thumbFileName").trim().ifBlank { null },
      thumbWidth = obj.optInt("thumbWidth", -1).takeIf { it > 0 },
      thumbHeight = obj.optInt("thumbHeight", -1).takeIf { it > 0 },
      migrationFailed = obj.optBoolean("migrationFailed", false),
      migrationFailedCode = obj.optString("migrationFailedCode").trim().ifBlank { null },
      migrationFailedDetail = obj.optString("migrationFailedDetail").trim().ifBlank { null },
      tags = tagsList,
      folderId = obj.optString("folderId").trim().ifBlank { null },
    )
  }

  private fun privateVideoEntryToJson(entry: PrivateVideoEntry): JSONObject {
    return JSONObject().apply {
      put("id", entry.id)
      put("title", entry.title)
      put("createdAt", entry.createdAt)
      put("updatedAt", entry.updatedAt)
      put("sourceUrlHash", entry.sourceUrlHash)
      put("mimeType", entry.mimeType)
      put("durationSec", entry.durationSec)
      put("sizeBytesEncrypted", entry.sizeBytesEncrypted)
      put("cipherVersion", entry.cipherVersion)
      put("encFileName", entry.encFileName)
      if (entry.containerExt != null) put("containerExt", entry.containerExt)
      if (entry.thumbFileName != null) put("thumbFileName", entry.thumbFileName)
      if (entry.thumbWidth != null) put("thumbWidth", entry.thumbWidth)
      if (entry.thumbHeight != null) put("thumbHeight", entry.thumbHeight)
      if (entry.migrationFailed) put("migrationFailed", true)
      if (entry.migrationFailedCode != null) put("migrationFailedCode", entry.migrationFailedCode)
      if (entry.migrationFailedDetail != null) put("migrationFailedDetail", entry.migrationFailedDetail)
      if (entry.tags.isNotEmpty()) {
        put("tags", JSONArray().also { arr -> entry.tags.forEach { arr.put(it) } })
      }
      if (entry.folderId != null) put("folderId", entry.folderId)
    }
  }

  private fun tagDefinitionFromJson(obj: JSONObject?): TagDefinition? {
    if (obj == null) return null
    val id = obj.optString("id").trim()
    val name = obj.optString("name").trim()
    val color = obj.optString("color").trim()
    if (id.isBlank() || name.isBlank() || color.isBlank()) return null
    return TagDefinition(
      id = id,
      name = name,
      color = color,
      createdAt = obj.optLong("createdAt", 0L),
    )
  }

  private fun tagDefinitionToJson(def: TagDefinition): JSONObject = JSONObject().apply {
    put("id", def.id)
    put("name", def.name)
    put("color", def.color)
    put("createdAt", def.createdAt)
  }

  private fun folderDefinitionFromJson(obj: JSONObject?): FolderDefinition? {
    if (obj == null) return null
    val id = obj.optString("id").trim()
    val name = obj.optString("name").trim()
    if (id.isBlank() || name.isBlank()) return null
    return FolderDefinition(
      id = id,
      name = name,
      createdAt = obj.optLong("createdAt", 0L),
    )
  }

  private fun folderDefinitionToJson(def: FolderDefinition): JSONObject = JSONObject().apply {
    put("id", def.id)
    put("name", def.name)
    put("createdAt", def.createdAt)
  }

  private fun sanitizePrivateTitle(value: String): String {
    val clean = value.trim()
      .replace(Regex("""[\\/:*?"<>|]"""), "_")
      .replace(Regex("""\s+"""), " ")
      .take(180)
    return if (clean.isBlank()) "private_video.mp4" else clean
  }

  private fun sha256Base64(value: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    return Base64.encodeToString(digest, Base64.NO_WRAP or Base64.URL_SAFE)
  }

  private fun encryptFileForPrivateVaultV3(source: File, output: File) {
    val startedAtMs = System.currentTimeMillis()
    val random = SecureRandom()

    val keyMaterial = ByteArray(PRIVATE_KEY_MATERIAL_BYTES)
    random.nextBytes(keyMaterial)
    val encKey = SecretKeySpec(keyMaterial.copyOfRange(0, PRIVATE_DEK_BYTES), "AES")
    val macKey = SecretKeySpec(keyMaterial.copyOfRange(PRIVATE_DEK_BYTES, PRIVATE_KEY_MATERIAL_BYTES), "HmacSHA256")

    val contentCipher = Cipher.getInstance("AES/CTR/NoPadding")
    val contentIv = ByteArray(PRIVATE_CTR_IV_BYTES)
    random.nextBytes(contentIv)
    contentCipher.init(Cipher.ENCRYPT_MODE, encKey, javax.crypto.spec.IvParameterSpec(contentIv))

    val wrapCipher = Cipher.getInstance("AES/GCM/NoPadding")
    wrapCipher.init(Cipher.ENCRYPT_MODE, getOrCreatePrivateVaultMasterKeyV2())
    val wrappedKeyMaterial = wrapCipher.doFinal(keyMaterial)
    val wrapIv = wrapCipher.iv

    if (wrapIv.isEmpty() || wrapIv.size > 255 || contentIv.size > 255 || wrappedKeyMaterial.size > PRIVATE_MAX_WRAPPED_DEK_BYTES) {
      throw IllegalStateException("PRIVATE_STORAGE_WRITE_FAILED")
    }

    val header = ByteArrayOutputStream().apply {
      write(PRIVATE_VAULT_V3_MAGIC)
      write(PRIVATE_VAULT_FORMAT_VERSION_V3.toInt())
      write(PRIVATE_VAULT_ALG_AES_CTR.toInt())
      write(PRIVATE_VAULT_ALG_AES_GCM.toInt())
      write(PRIVATE_VAULT_ALG_HMAC_SHA256.toInt())
      write(wrapIv.size)
      write(contentIv.size)
      write((wrappedKeyMaterial.size ushr 24) and 0xFF)
      write((wrappedKeyMaterial.size ushr 16) and 0xFF)
      write((wrappedKeyMaterial.size ushr 8) and 0xFF)
      write(wrappedKeyMaterial.size and 0xFF)
      write(PRIVATE_HMAC_TAG_BYTES)
      write(wrapIv)
      write(contentIv)
      write(wrappedKeyMaterial)
    }.toByteArray()

    val hmac = Mac.getInstance("HmacSHA256").apply {
      init(macKey)
      update(header)
    }

    output.parentFile?.mkdirs()
    FileInputStream(source).use { input ->
      FileOutputStream(output).use { rawOutput ->
        rawOutput.write(header)

        debug("[PRIVATE] encrypt-v3 stream-encrypt start source=${source.name} bufferBytes=$PRIVATE_STREAM_BUFFER_BYTES")
        val inBuffer = ByteArray(PRIVATE_STREAM_BUFFER_BYTES)
        var totalInputBytes = 0L
        var totalOutputBytes = 0L
        var nextLogAtBytes = PRIVATE_LOG_PROGRESS_STEP_BYTES
        while (true) {
          val read = input.read(inBuffer)
          if (read < 0) break
          totalInputBytes += read
          val outChunk = contentCipher.update(inBuffer, 0, read)
          if (outChunk != null && outChunk.isNotEmpty()) {
            rawOutput.write(outChunk)
            hmac.update(outChunk)
            totalOutputBytes += outChunk.size
          }
          if (debugLoggingEnabled && totalInputBytes >= nextLogAtBytes) {
            debug("[PRIVATE] encrypt-v3 progress source=${source.name} inputBytes=$totalInputBytes outputBytes=$totalOutputBytes")
            nextLogAtBytes += PRIVATE_LOG_PROGRESS_STEP_BYTES
          }
        }

        val finalChunk = contentCipher.doFinal()
        if (finalChunk != null && finalChunk.isNotEmpty()) {
          rawOutput.write(finalChunk)
          hmac.update(finalChunk)
          totalOutputBytes += finalChunk.size
        }

        val tag = hmac.doFinal()
        if (tag.size < PRIVATE_HMAC_TAG_BYTES) {
          throw IllegalStateException("PRIVATE_STORAGE_WRITE_FAILED")
        }
        rawOutput.write(tag, 0, PRIVATE_HMAC_TAG_BYTES)
        rawOutput.flush()

        debug(
          "[PRIVATE] encrypt-v3 stream-encrypt done source=${source.name} inputBytes=$totalInputBytes " +
            "outputBytes=$totalOutputBytes elapsedMs=${System.currentTimeMillis() - startedAtMs}"
        )
      }
    }

    recordPrivateCryptoMetric(
      encrypt = true,
      inputBytes = source.length(),
      elapsedMs = System.currentTimeMillis() - startedAtMs
    )
  }

  private fun encryptFileForPrivateVaultV2(source: File, output: File) {
    val startedAtMs = System.currentTimeMillis()
    val random = SecureRandom()
    val dekBytes = ByteArray(PRIVATE_DEK_BYTES)
    random.nextBytes(dekBytes)
    val dek = SecretKeySpec(dekBytes, "AES")

    val contentCipher = Cipher.getInstance("AES/GCM/NoPadding")
    val contentIv = ByteArray(PRIVATE_GCM_IV_BYTES)
    random.nextBytes(contentIv)
    contentCipher.init(Cipher.ENCRYPT_MODE, dek, GCMParameterSpec(PRIVATE_GCM_TAG_BITS, contentIv))

    val wrapCipher = Cipher.getInstance("AES/GCM/NoPadding")
    wrapCipher.init(Cipher.ENCRYPT_MODE, getOrCreatePrivateVaultMasterKeyV2())
    val wrappedDek = wrapCipher.doFinal(dekBytes)
    val wrapIv = wrapCipher.iv

    if (wrapIv.isEmpty() || wrapIv.size > 255 || contentIv.size > 255) {
      throw IllegalStateException("PRIVATE_STORAGE_WRITE_FAILED")
    }

    output.parentFile?.mkdirs()
    FileInputStream(source).use { input ->
      FileOutputStream(output).use { rawOutput ->
        rawOutput.write(PRIVATE_VAULT_V2_MAGIC)
        rawOutput.write(PRIVATE_VAULT_FORMAT_VERSION_V2.toInt())
        rawOutput.write(PRIVATE_VAULT_ALG_AES_GCM.toInt())
        rawOutput.write(PRIVATE_VAULT_ALG_AES_GCM.toInt())
        rawOutput.write(wrapIv.size)
        rawOutput.write(contentIv.size)
        rawOutput.write((wrappedDek.size ushr 24) and 0xFF)
        rawOutput.write((wrappedDek.size ushr 16) and 0xFF)
        rawOutput.write((wrappedDek.size ushr 8) and 0xFF)
        rawOutput.write(wrappedDek.size and 0xFF)
        rawOutput.write(wrapIv)
        rawOutput.write(contentIv)
        rawOutput.write(wrappedDek)

        encryptStreamWithMetrics(input, rawOutput, contentCipher, "encrypt", source.name)
      }
    }
    recordPrivateCryptoMetric(
      encrypt = true,
      inputBytes = source.length(),
      elapsedMs = System.currentTimeMillis() - startedAtMs
    )
  }

  private fun decryptPrivateVaultFile(
    source: File,
    output: File,
    effectiveVersion: String,
    traceId: String = "n/a",
    entryId: String? = null,
  ) {
    privateTrace(
      traceId,
      "decrypt dispatch source=${source.name} version=$effectiveVersion sourceBytes=${source.length()} output=${output.absolutePath}"
    )
    when (effectiveVersion) {
      PRIVATE_STORE_VERSION_V4 -> {
        val id = entryId ?: throw IllegalStateException("PRIVATE_VIDEO_NOT_FOUND")
        decryptPrivateVaultFileV4(source, output, id)
      }
      PRIVATE_STORE_VERSION_V3 -> decryptPrivateVaultFileV3(source, output, traceId)
      PRIVATE_STORE_VERSION_V2 -> decryptPrivateVaultFileV2(source, output, traceId)
      PRIVATE_STORE_VERSION_V1 -> decryptPrivateVaultFileV1Legacy(source, output, traceId)
      else -> throw IllegalStateException("PRIVATE_VIDEO_NOT_FOUND")
    }
  }

  private fun decryptPrivateVaultFileToOutput(
    source: File,
    output: OutputStream,
    effectiveVersion: String,
    traceId: String = "n/a",
    entryId: String? = null,
  ) {
    privateTrace(
      traceId,
      "decrypt dispatch (stream) source=${source.name} version=$effectiveVersion sourceBytes=${source.length()}"
    )
    when (effectiveVersion) {
      PRIVATE_STORE_VERSION_V4 -> {
        val id = entryId ?: throw IllegalStateException("PRIVATE_VIDEO_NOT_FOUND")
        decryptPrivateVaultFileV4ToStream(source, output, id)
      }
      PRIVATE_STORE_VERSION_V3 -> decryptPrivateVaultFileV3ToStream(source, output, traceId)
      PRIVATE_STORE_VERSION_V2 -> decryptPrivateVaultFileV2ToStream(source, output, traceId)
      PRIVATE_STORE_VERSION_V1 -> decryptPrivateVaultFileV1ToStream(source, output, traceId)
      else -> throw IllegalStateException("PRIVATE_VIDEO_NOT_FOUND")
    }
  }

  private fun decryptPrivateVaultFileV1ToStream(source: File, output: OutputStream, traceId: String = "n/a") {
    privateTrace(traceId, "decrypt-v1(stream) start source=${source.name} size=${source.length()}")
    val startedAtMs = System.currentTimeMillis()
    FileInputStream(source).use { rawInput ->
      val ivLength = rawInput.read()
      if (ivLength <= 0 || ivLength > 64) {
        throw IllegalStateException("PRIVATE_VIDEO_NOT_FOUND")
      }
      val iv = ByteArray(ivLength)
      readFullyOrThrow(rawInput, iv)
      val cipher = Cipher.getInstance("AES/GCM/NoPadding")
      cipher.init(Cipher.DECRYPT_MODE, getOrCreatePrivateVaultLegacyKeyV1(), GCMParameterSpec(PRIVATE_GCM_TAG_BITS, iv))
      decryptStreamWithMetrics(rawInput, output, cipher, "decrypt-v1(stream)", source.name, traceId)
    }
    recordPrivateCryptoMetric(
      encrypt = false,
      inputBytes = source.length(),
      elapsedMs = System.currentTimeMillis() - startedAtMs
    )
  }

  private fun decryptPrivateVaultFileV2ToStream(source: File, output: OutputStream, traceId: String = "n/a") {
    privateTrace(traceId, "decrypt-v2(stream) start source=${source.name} size=${source.length()}")
    val startedAtMs = System.currentTimeMillis()
    FileInputStream(source).use { rawInput ->
      val magic = ByteArray(PRIVATE_VAULT_V2_MAGIC.size)
      if (rawInput.read(magic) != magic.size || !magic.contentEquals(PRIVATE_VAULT_V2_MAGIC)) {
        throw IllegalStateException("PRIVATE_VIDEO_NOT_FOUND")
      }
      val version = rawInput.read()
      val contentAlg = rawInput.read()
      val wrapAlg = rawInput.read()
      if (
        version != PRIVATE_VAULT_FORMAT_VERSION_V2.toInt() ||
        contentAlg != PRIVATE_VAULT_ALG_AES_GCM.toInt() ||
        wrapAlg != PRIVATE_VAULT_ALG_AES_GCM.toInt()
      ) {
        throw IllegalStateException("PRIVATE_VIDEO_NOT_FOUND")
      }

      val wrapIvLen = rawInput.read()
      val contentIvLen = rawInput.read()
      if (wrapIvLen <= 0 || contentIvLen <= 0 || wrapIvLen > 64 || contentIvLen > 64) {
        throw IllegalStateException("PRIVATE_VIDEO_NOT_FOUND")
      }
      val wrappedLen = (
        (rawInput.read() shl 24) or
          (rawInput.read() shl 16) or
          (rawInput.read() shl 8) or
          rawInput.read()
        )
      if (wrappedLen <= 0 || wrappedLen > PRIVATE_MAX_WRAPPED_DEK_BYTES) {
        throw IllegalStateException("PRIVATE_VIDEO_NOT_FOUND")
      }

      val wrapIv = ByteArray(wrapIvLen)
      val contentIv = ByteArray(contentIvLen)
      val wrappedDek = ByteArray(wrappedLen)
      readFullyOrThrow(rawInput, wrapIv)
      readFullyOrThrow(rawInput, contentIv)
      readFullyOrThrow(rawInput, wrappedDek)

      val unwrapCipher = Cipher.getInstance("AES/GCM/NoPadding")
      unwrapCipher.init(Cipher.DECRYPT_MODE, getOrCreatePrivateVaultMasterKeyV2(), GCMParameterSpec(PRIVATE_GCM_TAG_BITS, wrapIv))
      val dekBytes = unwrapCipher.doFinal(wrappedDek)
      if (dekBytes.size != PRIVATE_DEK_BYTES) {
        throw IllegalStateException("PRIVATE_VIDEO_NOT_FOUND")
      }

      val dek = SecretKeySpec(dekBytes, "AES")
      val contentCipher = Cipher.getInstance("AES/GCM/NoPadding")
      contentCipher.init(Cipher.DECRYPT_MODE, dek, GCMParameterSpec(PRIVATE_GCM_TAG_BITS, contentIv))
      decryptStreamWithMetrics(rawInput, output, contentCipher, "decrypt-v2(stream)", source.name, traceId)
    }
    recordPrivateCryptoMetric(
      encrypt = false,
      inputBytes = source.length(),
      elapsedMs = System.currentTimeMillis() - startedAtMs
    )
  }

  private fun decryptPrivateVaultFileV3ToStream(source: File, output: OutputStream, traceId: String = "n/a") {
    privateTrace(traceId, "decrypt-v3(stream) start source=${source.name} size=${source.length()}")
    val startedAtMs = System.currentTimeMillis()
    FileInputStream(source).use { rawInput ->
      val magic = ByteArray(PRIVATE_VAULT_V3_MAGIC.size)
      if (rawInput.read(magic) != magic.size || !magic.contentEquals(PRIVATE_VAULT_V3_MAGIC)) {
        throw IllegalStateException("PRIVATE_VIDEO_NOT_FOUND")
      }

      val version = rawInput.read()
      val contentAlg = rawInput.read()
      val wrapAlg = rawInput.read()
      val macAlg = rawInput.read()
      if (
        version != PRIVATE_VAULT_FORMAT_VERSION_V3.toInt() ||
        contentAlg != PRIVATE_VAULT_ALG_AES_CTR.toInt() ||
        wrapAlg != PRIVATE_VAULT_ALG_AES_GCM.toInt() ||
        macAlg != PRIVATE_VAULT_ALG_HMAC_SHA256.toInt()
      ) {
        throw IllegalStateException("PRIVATE_VIDEO_NOT_FOUND")
      }

      val wrapIvLen = rawInput.read()
      val contentIvLen = rawInput.read()
      if (wrapIvLen <= 0 || contentIvLen <= 0 || wrapIvLen > 64 || contentIvLen > 64) {
        throw IllegalStateException("PRIVATE_VIDEO_NOT_FOUND")
      }
      val wrappedLen = (
        (rawInput.read() shl 24) or
          (rawInput.read() shl 16) or
          (rawInput.read() shl 8) or
          rawInput.read()
        )
      val macLen = rawInput.read()
      if (wrappedLen <= 0 || wrappedLen > PRIVATE_MAX_WRAPPED_DEK_BYTES || macLen != PRIVATE_HMAC_TAG_BYTES) {
        throw IllegalStateException("PRIVATE_VIDEO_NOT_FOUND")
      }

      val wrapIv = ByteArray(wrapIvLen)
      val contentIv = ByteArray(contentIvLen)
      val wrappedKeyMaterial = ByteArray(wrappedLen)
      readFullyOrThrow(rawInput, wrapIv)
      readFullyOrThrow(rawInput, contentIv)
      readFullyOrThrow(rawInput, wrappedKeyMaterial)

      val header = ByteArrayOutputStream().apply {
        write(PRIVATE_VAULT_V3_MAGIC)
        write(version)
        write(contentAlg)
        write(wrapAlg)
        write(macAlg)
        write(wrapIvLen)
        write(contentIvLen)
        write((wrappedLen ushr 24) and 0xFF)
        write((wrappedLen ushr 16) and 0xFF)
        write((wrappedLen ushr 8) and 0xFF)
        write(wrappedLen and 0xFF)
        write(macLen)
        write(wrapIv)
        write(contentIv)
        write(wrappedKeyMaterial)
      }.toByteArray()

      val unwrapCipher = Cipher.getInstance("AES/GCM/NoPadding")
      unwrapCipher.init(Cipher.DECRYPT_MODE, getOrCreatePrivateVaultMasterKeyV2(), GCMParameterSpec(PRIVATE_GCM_TAG_BITS, wrapIv))
      val keyMaterial = unwrapCipher.doFinal(wrappedKeyMaterial)
      if (keyMaterial.size != PRIVATE_KEY_MATERIAL_BYTES) {
        throw IllegalStateException("PRIVATE_VIDEO_NOT_FOUND")
      }
      val encKey = SecretKeySpec(keyMaterial.copyOfRange(0, PRIVATE_DEK_BYTES), "AES")
      val macKey = SecretKeySpec(keyMaterial.copyOfRange(PRIVATE_DEK_BYTES, PRIVATE_KEY_MATERIAL_BYTES), "HmacSHA256")

      val contentCipher = Cipher.getInstance("AES/CTR/NoPadding")
      contentCipher.init(Cipher.DECRYPT_MODE, encKey, javax.crypto.spec.IvParameterSpec(contentIv))
      val hmac = Mac.getInstance("HmacSHA256").apply {
        init(macKey)
        update(header)
      }

      val ciphertextBytes = source.length() - header.size - macLen
      if (ciphertextBytes < 0) {
        throw IllegalStateException("PRIVATE_VIDEO_NOT_FOUND")
      }

      val inBuffer = ByteArray(PRIVATE_STREAM_BUFFER_BYTES)
      var remaining = ciphertextBytes
      var totalInputBytes = 0L
      var totalOutputBytes = 0L
      while (remaining > 0) {
        val request = minOf(inBuffer.size.toLong(), remaining).toInt()
        val read = rawInput.read(inBuffer, 0, request)
        if (read <= 0) {
          throw IllegalStateException("PRIVATE_VIDEO_NOT_FOUND")
        }
        remaining -= read
        totalInputBytes += read
        hmac.update(inBuffer, 0, read)
        val outChunk = contentCipher.update(inBuffer, 0, read)
        if (outChunk != null && outChunk.isNotEmpty()) {
          output.write(outChunk)
          totalOutputBytes += outChunk.size
        }
      }

      val expectedTag = ByteArray(macLen)
      readFullyOrThrow(rawInput, expectedTag)
      val finalChunk = contentCipher.doFinal()
      if (finalChunk != null && finalChunk.isNotEmpty()) {
        output.write(finalChunk)
        totalOutputBytes += finalChunk.size
      }
      output.flush()

      val actualTag = hmac.doFinal()
      val expectedTagTrimmed = if (expectedTag.size == actualTag.size) expectedTag else expectedTag.copyOf(actualTag.size)
      if (!MessageDigest.isEqual(actualTag, expectedTagTrimmed)) {
        throw IllegalStateException("PRIVATE_VIDEO_NOT_FOUND")
      }
      privateTrace(
        traceId,
        "decrypt-v3(stream) done source=${source.name} inputBytes=$totalInputBytes outputBytes=$totalOutputBytes elapsedMs=${System.currentTimeMillis() - startedAtMs}"
      )
    }
    recordPrivateCryptoMetric(
      encrypt = false,
      inputBytes = source.length(),
      elapsedMs = System.currentTimeMillis() - startedAtMs
    )
  }

  private fun decryptPrivateVaultFileV1Legacy(source: File, output: File, traceId: String = "n/a") {
    privateTrace(traceId, "decrypt-v1 start source=${source.name} size=${source.length()}")
    val startedAtMs = System.currentTimeMillis()
    FileInputStream(source).use { rawInput ->
      val ivLength = rawInput.read()
      if (ivLength <= 0 || ivLength > 64) {
        throw IllegalStateException("PRIVATE_VIDEO_NOT_FOUND")
      }
      val iv = ByteArray(ivLength)
      readFullyOrThrow(rawInput, iv)
      val cipher = Cipher.getInstance("AES/GCM/NoPadding")
      cipher.init(Cipher.DECRYPT_MODE, getOrCreatePrivateVaultLegacyKeyV1(), GCMParameterSpec(PRIVATE_GCM_TAG_BITS, iv))
      output.parentFile?.mkdirs()
      FileOutputStream(output).use { rawOutput ->
        decryptStreamWithMetrics(rawInput, rawOutput, cipher, "decrypt-v1", source.name, traceId)
      }
    }
    recordPrivateCryptoMetric(
      encrypt = false,
      inputBytes = source.length(),
      elapsedMs = System.currentTimeMillis() - startedAtMs
    )
    privateTrace(
      traceId,
      "decrypt-v1 complete source=${source.name} outputBytes=${output.length()} elapsedMs=${System.currentTimeMillis() - startedAtMs}"
    )
  }

  private fun decryptPrivateVaultFileV2(source: File, output: File, traceId: String = "n/a") {
    privateTrace(traceId, "decrypt-v2 start source=${source.name} size=${source.length()}")
    val startedAtMs = System.currentTimeMillis()
    FileInputStream(source).use { rawInput ->
      val magic = ByteArray(PRIVATE_VAULT_V2_MAGIC.size)
      if (rawInput.read(magic) != magic.size || !magic.contentEquals(PRIVATE_VAULT_V2_MAGIC)) {
        throw IllegalStateException("PRIVATE_VIDEO_NOT_FOUND")
      }
      val version = rawInput.read()
      val contentAlg = rawInput.read()
      val wrapAlg = rawInput.read()
      if (
        version != PRIVATE_VAULT_FORMAT_VERSION_V2.toInt() ||
        contentAlg != PRIVATE_VAULT_ALG_AES_GCM.toInt() ||
        wrapAlg != PRIVATE_VAULT_ALG_AES_GCM.toInt()
      ) {
        throw IllegalStateException("PRIVATE_VIDEO_NOT_FOUND")
      }

      val wrapIvLen = rawInput.read()
      val contentIvLen = rawInput.read()
      if (wrapIvLen <= 0 || contentIvLen <= 0 || wrapIvLen > 64 || contentIvLen > 64) {
        throw IllegalStateException("PRIVATE_VIDEO_NOT_FOUND")
      }
      val wrappedLen = (
        (rawInput.read() shl 24) or
          (rawInput.read() shl 16) or
          (rawInput.read() shl 8) or
          rawInput.read()
        )
      if (wrappedLen <= 0 || wrappedLen > PRIVATE_MAX_WRAPPED_DEK_BYTES) {
        throw IllegalStateException("PRIVATE_VIDEO_NOT_FOUND")
      }

      val wrapIv = ByteArray(wrapIvLen)
      val contentIv = ByteArray(contentIvLen)
      val wrappedDek = ByteArray(wrappedLen)
      readFullyOrThrow(rawInput, wrapIv)
      readFullyOrThrow(rawInput, contentIv)
      readFullyOrThrow(rawInput, wrappedDek)
      privateTrace(
        traceId,
        "decrypt-v2 header parsed source=${source.name} wrapIvLen=$wrapIvLen contentIvLen=$contentIvLen wrappedLen=$wrappedLen"
      )

      val unwrapCipher = Cipher.getInstance("AES/GCM/NoPadding")
      unwrapCipher.init(Cipher.DECRYPT_MODE, getOrCreatePrivateVaultMasterKeyV2(), GCMParameterSpec(PRIVATE_GCM_TAG_BITS, wrapIv))
      val dekBytes = unwrapCipher.doFinal(wrappedDek)
      if (dekBytes.size != PRIVATE_DEK_BYTES) {
        throw IllegalStateException("PRIVATE_VIDEO_NOT_FOUND")
      }
      val dek = SecretKeySpec(dekBytes, "AES")
      val contentCipher = Cipher.getInstance("AES/GCM/NoPadding")
      contentCipher.init(Cipher.DECRYPT_MODE, dek, GCMParameterSpec(PRIVATE_GCM_TAG_BITS, contentIv))

      output.parentFile?.mkdirs()
      FileOutputStream(output).use { rawOutput ->
        decryptStreamWithMetrics(rawInput, rawOutput, contentCipher, "decrypt-v2", source.name, traceId)
      }
    }
    recordPrivateCryptoMetric(
      encrypt = false,
      inputBytes = source.length(),
      elapsedMs = System.currentTimeMillis() - startedAtMs
    )
    privateTrace(
      traceId,
      "decrypt-v2 complete source=${source.name} outputBytes=${output.length()} elapsedMs=${System.currentTimeMillis() - startedAtMs}"
    )
  }

  private fun decryptPrivateVaultFileV3(source: File, output: File, traceId: String = "n/a") {
    privateTrace(traceId, "decrypt-v3 start source=${source.name} size=${source.length()}")
    val startedAtMs = System.currentTimeMillis()
    FileInputStream(source).use { rawInput ->
      val magic = ByteArray(PRIVATE_VAULT_V3_MAGIC.size)
      if (rawInput.read(magic) != magic.size || !magic.contentEquals(PRIVATE_VAULT_V3_MAGIC)) {
        throw IllegalStateException("PRIVATE_VIDEO_NOT_FOUND")
      }

      val version = rawInput.read()
      val contentAlg = rawInput.read()
      val wrapAlg = rawInput.read()
      val macAlg = rawInput.read()
      if (
        version != PRIVATE_VAULT_FORMAT_VERSION_V3.toInt() ||
        contentAlg != PRIVATE_VAULT_ALG_AES_CTR.toInt() ||
        wrapAlg != PRIVATE_VAULT_ALG_AES_GCM.toInt() ||
        macAlg != PRIVATE_VAULT_ALG_HMAC_SHA256.toInt()
      ) {
        throw IllegalStateException("PRIVATE_VIDEO_NOT_FOUND")
      }

      val wrapIvLen = rawInput.read()
      val contentIvLen = rawInput.read()
      if (wrapIvLen <= 0 || contentIvLen <= 0 || wrapIvLen > 64 || contentIvLen > 64) {
        throw IllegalStateException("PRIVATE_VIDEO_NOT_FOUND")
      }
      val wrappedLen = (
        (rawInput.read() shl 24) or
          (rawInput.read() shl 16) or
          (rawInput.read() shl 8) or
          rawInput.read()
        )
      val macLen = rawInput.read()
      if (wrappedLen <= 0 || wrappedLen > PRIVATE_MAX_WRAPPED_DEK_BYTES || macLen != PRIVATE_HMAC_TAG_BYTES) {
        throw IllegalStateException("PRIVATE_VIDEO_NOT_FOUND")
      }

      val wrapIv = ByteArray(wrapIvLen)
      val contentIv = ByteArray(contentIvLen)
      val wrappedKeyMaterial = ByteArray(wrappedLen)
      readFullyOrThrow(rawInput, wrapIv)
      readFullyOrThrow(rawInput, contentIv)
      readFullyOrThrow(rawInput, wrappedKeyMaterial)
      privateTrace(
        traceId,
        "decrypt-v3 header parsed source=${source.name} wrapIvLen=$wrapIvLen contentIvLen=$contentIvLen wrappedLen=$wrappedLen macLen=$macLen"
      )

      val header = ByteArrayOutputStream().apply {
        write(PRIVATE_VAULT_V3_MAGIC)
        write(version)
        write(contentAlg)
        write(wrapAlg)
        write(macAlg)
        write(wrapIvLen)
        write(contentIvLen)
        write((wrappedLen ushr 24) and 0xFF)
        write((wrappedLen ushr 16) and 0xFF)
        write((wrappedLen ushr 8) and 0xFF)
        write(wrappedLen and 0xFF)
        write(macLen)
        write(wrapIv)
        write(contentIv)
        write(wrappedKeyMaterial)
      }.toByteArray()

      val unwrapCipher = Cipher.getInstance("AES/GCM/NoPadding")
      unwrapCipher.init(Cipher.DECRYPT_MODE, getOrCreatePrivateVaultMasterKeyV2(), GCMParameterSpec(PRIVATE_GCM_TAG_BITS, wrapIv))
      val keyMaterial = unwrapCipher.doFinal(wrappedKeyMaterial)
      if (keyMaterial.size != PRIVATE_KEY_MATERIAL_BYTES) {
        throw IllegalStateException("PRIVATE_VIDEO_NOT_FOUND")
      }
      val encKey = SecretKeySpec(keyMaterial.copyOfRange(0, PRIVATE_DEK_BYTES), "AES")
      val macKey = SecretKeySpec(keyMaterial.copyOfRange(PRIVATE_DEK_BYTES, PRIVATE_KEY_MATERIAL_BYTES), "HmacSHA256")

      val contentCipher = Cipher.getInstance("AES/CTR/NoPadding")
      contentCipher.init(Cipher.DECRYPT_MODE, encKey, javax.crypto.spec.IvParameterSpec(contentIv))
      val hmac = Mac.getInstance("HmacSHA256").apply {
        init(macKey)
        update(header)
      }

      val ciphertextBytes = source.length() - header.size - macLen
      if (ciphertextBytes < 0) {
        throw IllegalStateException("PRIVATE_VIDEO_NOT_FOUND")
      }

      output.parentFile?.mkdirs()
      FileOutputStream(output).use { rawOutput ->
        val inBuffer = ByteArray(PRIVATE_STREAM_BUFFER_BYTES)
        var remaining = ciphertextBytes
        var totalInputBytes = 0L
        var totalOutputBytes = 0L
        var nextLogAtBytes = PRIVATE_LOG_PROGRESS_STEP_BYTES
        while (remaining > 0) {
          val request = minOf(inBuffer.size.toLong(), remaining).toInt()
          val read = rawInput.read(inBuffer, 0, request)
          if (read <= 0) {
            throw IllegalStateException("PRIVATE_VIDEO_NOT_FOUND")
          }
          remaining -= read
          totalInputBytes += read
          hmac.update(inBuffer, 0, read)
          val outChunk = contentCipher.update(inBuffer, 0, read)
          if (outChunk != null && outChunk.isNotEmpty()) {
            rawOutput.write(outChunk)
            totalOutputBytes += outChunk.size
          }
          if (debugLoggingEnabled && totalInputBytes >= nextLogAtBytes) {
            privateTrace(
              traceId,
              "decrypt-v3 progress source=${source.name} inputBytes=$totalInputBytes outputBytes=$totalOutputBytes"
            )
            nextLogAtBytes += PRIVATE_LOG_PROGRESS_STEP_BYTES
          }
        }

        val expectedTag = ByteArray(macLen)
        readFullyOrThrow(rawInput, expectedTag)
        val finalChunk = contentCipher.doFinal()
        if (finalChunk != null && finalChunk.isNotEmpty()) {
          rawOutput.write(finalChunk)
          totalOutputBytes += finalChunk.size
        }
        rawOutput.flush()

        val actualTag = hmac.doFinal()
        val expectedTagTrimmed = if (expectedTag.size == actualTag.size) expectedTag else expectedTag.copyOf(actualTag.size)
        if (!MessageDigest.isEqual(actualTag, expectedTagTrimmed)) {
          throw IllegalStateException("PRIVATE_VIDEO_NOT_FOUND")
        }
        privateTrace(
          traceId,
          "decrypt-v3 stream-decrypt done source=${source.name} inputBytes=$totalInputBytes outputBytes=$totalOutputBytes elapsedMs=${System.currentTimeMillis() - startedAtMs}"
        )
      }
    }
    recordPrivateCryptoMetric(
      encrypt = false,
      inputBytes = source.length(),
      elapsedMs = System.currentTimeMillis() - startedAtMs
    )
    privateTrace(
      traceId,
      "decrypt-v3 complete source=${source.name} outputBytes=${output.length()} elapsedMs=${System.currentTimeMillis() - startedAtMs}"
    )
  }

  private fun detectPrivateCipherVersion(source: File, entryCipherVersion: String): String {
    if (!source.exists() || !source.isFile) {
      return entryCipherVersion.ifBlank { PRIVATE_STORE_VERSION_V1 }
    }
    // v4 uses Tink's StreamingAead format which has no project-specific magic byte.
    // The entry's recorded cipherVersion is the authoritative source for v4 items; we
    // only sniff bytes to recover when the entry's tag is missing/wrong for legacy items.
    if (entryCipherVersion == PRIVATE_STORE_VERSION_V4) {
      return PRIVATE_STORE_VERSION_V4
    }
    return runCatching {
      FileInputStream(source).use { input ->
        val magic = ByteArray(PRIVATE_VAULT_V2_MAGIC.size)
        val read = input.read(magic)
        if (read == magic.size && magic.contentEquals(PRIVATE_VAULT_V3_MAGIC)) {
          PRIVATE_STORE_VERSION_V3
        } else if (read == magic.size && magic.contentEquals(PRIVATE_VAULT_V2_MAGIC)) {
          PRIVATE_STORE_VERSION_V2
        } else {
          PRIVATE_STORE_VERSION_V1
        }
      }
    }.getOrElse {
      entryCipherVersion.ifBlank { PRIVATE_STORE_VERSION_V1 }
    }
  }

  private fun migratePrivateVaultEntryToV2(entry: PrivateVideoEntry, decryptedSource: File) {
    if (entry.cipherVersion == PRIVATE_STORE_VERSION_V2) {
      return
    }
    val objectsDir = privateVaultObjectsDir(create = true)
    val target = File(objectsDir, entry.encFileName)
    val temp = File(objectsDir, ".${entry.encFileName}.v2.partial")
    val now = System.currentTimeMillis()
    encryptFileForPrivateVaultV2(decryptedSource, temp)
    if (!temp.renameTo(target)) {
      temp.copyTo(target, overwrite = true)
      temp.delete()
    }
    synchronized(privateVaultLock) {
      val index = readPrivateVaultIndex()
      val items = index.optJSONArray("items") ?: JSONArray()
      for (i in 0 until items.length()) {
        val obj = items.optJSONObject(i) ?: continue
        if (obj.optString("id") == entry.id) {
          obj.put("cipherVersion", PRIVATE_STORE_VERSION_V2)
          obj.put("updatedAt", now)
          obj.put("sizeBytesEncrypted", target.length())
          break
        }
      }
      writePrivateVaultIndex(index)
    }
    debug("[PRIVATE] lazy migration completed id=${entry.id} version=$PRIVATE_STORE_VERSION_V2")
  }

  private fun copyStreamWithMetrics(
    input: java.io.InputStream,
    output: java.io.OutputStream,
    phase: String,
    sourceName: String,
    traceId: String = "n/a"
  ): Long {
    val startedAt = System.currentTimeMillis()
    privateTrace(traceId, "$phase stream-copy start source=$sourceName bufferBytes=$PRIVATE_STREAM_BUFFER_BYTES")
    val buffer = ByteArray(PRIVATE_STREAM_BUFFER_BYTES)
    var totalBytes = 0L
    var nextLogAtBytes = PRIVATE_LOG_PROGRESS_STEP_BYTES
    while (true) {
      val read = input.read(buffer)
      if (read < 0) break
      output.write(buffer, 0, read)
      totalBytes += read
      if (debugLoggingEnabled && totalBytes >= nextLogAtBytes) {
        privateTrace(traceId, "$phase progress source=$sourceName bytes=$totalBytes")
        nextLogAtBytes += PRIVATE_LOG_PROGRESS_STEP_BYTES
      }
    }
    privateTrace(
      traceId,
      "$phase stream-copy done source=$sourceName bytes=$totalBytes elapsedMs=${System.currentTimeMillis() - startedAt}"
    )
    return totalBytes
  }

  private fun encryptStreamWithMetrics(
    input: java.io.InputStream,
    output: java.io.OutputStream,
    cipher: Cipher,
    phase: String,
    sourceName: String
  ): Long {
    val startedAt = System.currentTimeMillis()
    debug("[PRIVATE] $phase stream-encrypt start source=$sourceName bufferBytes=$PRIVATE_STREAM_BUFFER_BYTES")
    val inBuffer = ByteArray(PRIVATE_STREAM_BUFFER_BYTES)
    var totalInputBytes = 0L
    var totalOutputBytes = 0L
    var nextLogAtBytes = PRIVATE_LOG_PROGRESS_STEP_BYTES
    while (true) {
      val read = input.read(inBuffer)
      if (read < 0) break
      totalInputBytes += read
      val outChunk = cipher.update(inBuffer, 0, read)
      if (outChunk != null && outChunk.isNotEmpty()) {
        output.write(outChunk)
        totalOutputBytes += outChunk.size
      }
      if (debugLoggingEnabled && totalInputBytes >= nextLogAtBytes) {
        debug(
          "[PRIVATE] $phase progress source=$sourceName inputBytes=$totalInputBytes outputBytes=$totalOutputBytes"
        )
        nextLogAtBytes += PRIVATE_LOG_PROGRESS_STEP_BYTES
      }
    }
    val finalChunk = cipher.doFinal()
    if (finalChunk != null && finalChunk.isNotEmpty()) {
      output.write(finalChunk)
      totalOutputBytes += finalChunk.size
    }
    output.flush()
    debug(
      "[PRIVATE] $phase stream-encrypt done source=$sourceName inputBytes=$totalInputBytes outputBytes=$totalOutputBytes elapsedMs=${System.currentTimeMillis() - startedAt}"
    )
    return totalOutputBytes
  }

  private fun decryptStreamWithMetrics(
    input: java.io.InputStream,
    output: java.io.OutputStream,
    cipher: Cipher,
    phase: String,
    sourceName: String,
    traceId: String = "n/a"
  ): Long {
    val startedAt = System.currentTimeMillis()
    privateTrace(traceId, "$phase stream-decrypt start source=$sourceName bufferBytes=$PRIVATE_STREAM_BUFFER_BYTES")
    val inBuffer = ByteArray(PRIVATE_STREAM_BUFFER_BYTES)
    var totalInputBytes = 0L
    var totalOutputBytes = 0L
    var nextLogAtBytes = PRIVATE_LOG_PROGRESS_STEP_BYTES
    while (true) {
      val read = input.read(inBuffer)
      if (read < 0) break
      totalInputBytes += read
      val outChunk = cipher.update(inBuffer, 0, read)
      if (outChunk != null && outChunk.isNotEmpty()) {
        output.write(outChunk)
        totalOutputBytes += outChunk.size
      }
      if (debugLoggingEnabled && totalInputBytes >= nextLogAtBytes) {
        privateTrace(
          traceId,
          "$phase progress source=$sourceName inputBytes=$totalInputBytes outputBytes=$totalOutputBytes"
        )
        nextLogAtBytes += PRIVATE_LOG_PROGRESS_STEP_BYTES
      }
    }
    val finalChunk = cipher.doFinal()
    if (finalChunk != null && finalChunk.isNotEmpty()) {
      output.write(finalChunk)
      totalOutputBytes += finalChunk.size
    }
    output.flush()
    privateTrace(
      traceId,
      "$phase stream-decrypt done source=$sourceName inputBytes=$totalInputBytes outputBytes=$totalOutputBytes elapsedMs=${System.currentTimeMillis() - startedAt}"
    )
    return totalOutputBytes
  }

  private fun readFullyOrThrow(input: java.io.InputStream, buffer: ByteArray) {
    var offset = 0
    while (offset < buffer.size) {
      val read = input.read(buffer, offset, buffer.size - offset)
      if (read < 0) {
        throw IllegalStateException("PRIVATE_VIDEO_NOT_FOUND")
      }
      offset += read
    }
  }

  private fun recordPrivateCryptoMetric(encrypt: Boolean, inputBytes: Long, elapsedMs: Long) {
    val normalizedMs = max(1L, elapsedMs)
    val throughputMbps = (inputBytes.toDouble() * 8.0 / (1024.0 * 1024.0)) / (normalizedMs / 1000.0)
    privateLastThroughputMbps = throughputMbps
    if (encrypt) {
      privateLastEncryptMs = normalizedMs
    } else {
      privateLastDecryptMs = normalizedMs
    }
    if (debugLoggingEnabled) {
      debug(
        "[PRIVATE] crypto metric mode=${if (encrypt) "encrypt" else "decrypt"} " +
          "bytes=$inputBytes elapsedMs=$normalizedMs throughputMbps=${"%.2f".format(throughputMbps)}"
      )
    }
  }

  private fun getOrCreatePrivateVaultLegacyKeyV1(): SecretKey {
    val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    val existing = keyStore.getKey(PRIVATE_VAULT_KEY_ALIAS_V1, null) as? SecretKey
    if (existing != null) {
      return existing
    }
    val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
    val spec = KeyGenParameterSpec.Builder(
      PRIVATE_VAULT_KEY_ALIAS_V1,
      KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
    )
      .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
      .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
      .setRandomizedEncryptionRequired(true)
      .build()
    keyGenerator.init(spec)
    return keyGenerator.generateKey()
  }

  private fun getOrCreatePrivateVaultMasterKeyV2(): SecretKey {
    val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    val existing = keyStore.getKey(PRIVATE_VAULT_MASTER_KEY_ALIAS_V2, null) as? SecretKey
    if (existing != null) {
      return existing
    }
    val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
    val spec = KeyGenParameterSpec.Builder(
      PRIVATE_VAULT_MASTER_KEY_ALIAS_V2,
      KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
    )
      .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
      .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
      .setRandomizedEncryptionRequired(true)
      .build()
    keyGenerator.init(spec)
    return keyGenerator.generateKey()
  }

  private fun authenticatePrivateAccessInternal(purpose: String): Pair<Boolean, String?> {
    debug("[PRIVATE] auth start purpose=$purpose thread=${Thread.currentThread().name}")
    val context = appContext.reactContext ?: return false to "PRIVATE_AUTH_REQUIRED"
    if (!PRIVATE_VAULT_FEATURE_FLAG) {
      debug("[PRIVATE] auth unavailable feature-flag disabled")
      return false to "PRIVATE_MODE_UNAVAILABLE"
    }
    if (!isPrivateAuthAvailable(context)) {
      debug("[PRIVATE] auth unavailable device not secure/biometric unavailable")
      return false to "PRIVATE_MODE_UNAVAILABLE"
    }
    val activity = appContext.currentActivity as? FragmentActivity
      ?: run {
        debug("[PRIVATE] auth failed no current FragmentActivity")
        return false to "PRIVATE_AUTH_REQUIRED"
      }
    if (Looper.myLooper() == Looper.getMainLooper()) {
      debug("[PRIVATE] auth failed called on main thread")
      return false to "PRIVATE_AUTH_FAILED"
    }

    val result = java.util.concurrent.atomic.AtomicBoolean(false)
    val reason = arrayOfNulls<String>(1)
    val latch = CountDownLatch(1)
    activity.runOnUiThread {
      val executor = ContextCompat.getMainExecutor(activity)
      val prompt = BiometricPrompt(
        activity,
        executor,
        object : BiometricPrompt.AuthenticationCallback() {
          override fun onAuthenticationSucceeded(authResult: BiometricPrompt.AuthenticationResult) {
            debug("[PRIVATE] auth callback success purpose=$purpose")
            result.set(true)
            reason[0] = null
            latch.countDown()
          }

          override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
            debug("[PRIVATE] auth callback error purpose=$purpose code=$errorCode msg=$errString")
            result.set(false)
            reason[0] = "PRIVATE_AUTH_FAILED"
            latch.countDown()
          }

          override fun onAuthenticationFailed() {
            debug("[PRIVATE] auth callback failed (non-terminal) purpose=$purpose")
          }
        }
      )
      val promptBuilder = BiometricPrompt.PromptInfo.Builder()
        .setTitle(
          when (purpose) {
            "delete" -> "Confirm delete"
            "import" -> "Confirm import"
            "export" -> "Confirm copy"
            "unprivate" -> "Confirm export"
            "rename" -> "Confirm rename"
            "migrate" -> "Re-encrypt vault"
            "tag" -> "Confirm tag change"
            "folder" -> "Confirm move"
            "bundleExport" -> "Export vault"
            "bundleImport" -> "Import vault"
            else -> "Unlock private vault"
          }
        )
        .setSubtitle("Verify identity to continue")
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        promptBuilder.setAllowedAuthenticators(
          BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        )
      } else {
        promptBuilder.setDeviceCredentialAllowed(true)
      }
      prompt.authenticate(promptBuilder.build())
    }

    val completed = runCatching { latch.await(15, TimeUnit.SECONDS) }.getOrDefault(false)
    if (!completed) {
      debug("[PRIVATE] auth timeout purpose=$purpose")
      return false to "PRIVATE_AUTH_FAILED"
    }
    if (!result.get()) {
      debug("[PRIVATE] auth denied purpose=$purpose reason=${reason[0] ?: "PRIVATE_AUTH_FAILED"}")
      return false to (reason[0] ?: "PRIVATE_AUTH_FAILED")
    }
    debug("[PRIVATE] auth success purpose=$purpose")
    // The prompt is now what opens the session, rather than a screen in front of a key that
    // was already cached. A prompt for a destructive action also refreshes the step-up clock.
    return runCatching {
      if (vaultSession.snapshot().unlocked) {
        vaultSession.noteFreshAuth()
      } else {
        openVaultSession()
      }
      true to null
    }.getOrElse { failure ->
      debug("[PRIVATE] auth succeeded but the vault would not open: ${failure.message}")
      val code = failure.message?.substringBefore(":") ?: "PRIVATE_STORAGE_UNAVAILABLE"
      false to code
    }
  }

  /** Opens the vault, prompting if it is not already open. */
  private fun unlockPrivateVaultInternal(purpose: String): Map<String, Any?> {
    val (granted, reason) = authenticatePrivateAccessInternal(purpose)
    return mapOf("granted" to granted, "reason" to reason) + privateVaultLockStateInternal()
  }

  /**
   * Counts and timestamps only. Never entry ids, never titles, and never the loopback token or
   * its URL — the loopback server's own documentation is explicit that the URL is a secret.
   */
  private fun privateVaultLockStateInternal(): Map<String, Any?> {
    val state = vaultSession.snapshot()
    return mapOf(
      "unlocked" to state.unlocked,
      "expiresAt" to state.expiresAt,
      "idleExpiresAt" to state.idleExpiresAt,
      "lastAuthAt" to state.lastAuthAt,
      "leaseCount" to state.leaseCount,
    )
  }

  private fun isPrivateAuthAvailable(context: Context): Boolean {
    return isPrivateAuthAvailableStatic(context)
  }

  private fun prepareRuntimeCookiePath(
    taskId: String,
    url: String,
    requestedProfile: String?,
    preferredPlatform: String?
  ): String? {
    val builtInPlatform = preferredPlatform ?: detectCookiePlatform(url)
    val selectedFile = if (builtInPlatform != null) {
      lastCustomDomainMatch = null
      val platformFile = selectSecureCookieFile(builtInPlatform, requestedProfile)
      if (requestedProfile != null && platformFile == null) {
        throw IllegalStateException("COOKIE_PROFILE_NOT_FOUND")
      }
      platformFile
    } else {
      selectCustomCookieFileForUrl(url, requestedProfile)
    } ?: return null

    val runtimeDir = runtimeCookieTaskDir(taskId).apply { mkdirs() }
    val runtimeFile = File(runtimeDir, "cookie.txt")
    val plaintext = readEncryptedCookieFile(selectedFile)
    atomicWriteBytes(runtimeFile, plaintext)
    debug("Task[$taskId] prepared runtime cookie file from profile=${selectedFile.nameWithoutExtension} platform=${builtInPlatform ?: "custom"}")
    return runtimeFile.absolutePath
  }

  private fun shouldRetryWithoutCookies(result: JSONObject, usedCookiePath: String?, platform: String?): Boolean {
    if (usedCookiePath.isNullOrBlank()) {
      debug("Retry-without-cookies=false reason=no-cookie")
      return false
    }
    if (platform != null && STRICT_COOKIE_PLATFORMS.contains(platform)) {
      debug("Retry-without-cookies=false reason=strict-platform platform=$platform")
      return false
    }

    val code = result.optString("code", "")
    if (code == "DOWNLOAD_CANCELLED" || code == "FILE_TOO_LARGE") {
      debug("Retry-without-cookies=false reason=terminal-code code=$code")
      return false
    }

    if (code == "COOKIE_STALE_OR_INVALID") {
      debug("Retry-without-cookies=false reason=cookie-invalid")
      return false
    }

    if (code in RETRYABLE_COOKIE_FAILURE_CODES) {
      debug("Retry-without-cookies=true reason=retryable-code code=$code")
      return true
    }

    val message = result.optString("message", "").lowercase()
    val decision = message.contains("cookie") || message.contains("sign in") || message.contains("login")
    debug("Retry-without-cookies=$decision reason=message-match")
    return decision
  }

  private fun detectCookiePlatform(url: String): String? {
    val host = extractCanonicalHostFromUrl(url) ?: return null

    for ((platform, hosts) in PLATFORM_HOSTS) {
      if (hosts.any { host == it || host.endsWith(".$it") }) {
        return platform
      }
    }
    return null
  }

  private fun selectSecureCookieFile(platform: String, requestedProfile: String?): File? {
    val platformDir = secureCookiePlatformDir(platform, create = false)
    if (!platformDir.exists()) {
      return null
    }

    val files = platformDir.listFiles()
      ?.filter { it.isFile && it.extension == "enc" }
      ?.sortedByDescending { it.lastModified() }
      ?: emptyList()
    if (files.isEmpty()) {
      return null
    }

    if (!requestedProfile.isNullOrBlank()) {
      val normalized = sanitizeProfileName(requestedProfile)
      return files.firstOrNull { it.nameWithoutExtension == normalized }
    }

    val defaultProfile = readDefaultProfile(platformDir)
    if (!defaultProfile.isNullOrBlank()) {
      val defaultMatch = files.firstOrNull { it.nameWithoutExtension == defaultProfile }
      if (defaultMatch != null) {
        return defaultMatch
      }
    }

    return files.firstOrNull()
  }

  private fun selectCustomCookieFileForUrl(url: String, requestedProfile: String?): File? {
    val host = extractCanonicalHostFromUrl(url)
    if (host == null) {
      lastCustomDomainMatch = null
      return null
    }

    return synchronized(customCookieIndexLock) {
      val index = readCustomCookieIndex()
      val domainsObj = index.getJSONObject("domains")
      val profilesObj = index.getJSONObject("profiles")
      var matchedDomain: String? = null
      val keys = domainsObj.keys()
      while (keys.hasNext()) {
        val rawDomain = keys.next()
        val candidate = canonicalizeDomain(rawDomain) ?: continue
        if (host != candidate && !host.endsWith(".$candidate")) {
          continue
        }
        if (matchedDomain == null || candidate.length > (matchedDomain?.length ?: -1)) {
          matchedDomain = candidate
        }
      }

      if (matchedDomain == null) {
        lastCustomDomainMatch = CustomDomainMatch(urlHost = host)
        return@synchronized null
      }

      val selectedProfile = resolveCustomProfileForDomain(
        index = index,
        domain = matchedDomain,
        requestedProfile = requestedProfile
      )

      if (selectedProfile == null) {
        lastCustomDomainMatch = CustomDomainMatch(urlHost = host, matchedDomain = matchedDomain)
        return@synchronized null
      }

      val profileObj = profilesObj.optJSONObject(selectedProfile.first)
      val profileName = sanitizeProfileName(profileObj?.optString("profileName").orEmpty())
      lastCustomDomainMatch = CustomDomainMatch(
        urlHost = host,
        matchedDomain = matchedDomain,
        profileName = profileName
      )
      selectedProfile.second
    }
  }

  private fun resolveCustomProfileForDomain(
    index: JSONObject,
    domain: String,
    requestedProfile: String?
  ): Pair<String, File>? {
    val domainsObj = index.getJSONObject("domains")
    val profilesObj = index.getJSONObject("profiles")
    val domainEntry = domainsObj.optJSONObject(domain) ?: return null
    val profileIds = jsonArrayToStringList(domainEntry.optJSONArray("profileIds"))
    if (profileIds.isEmpty()) {
      return null
    }

    val candidates = profileIds.mapNotNull { profileId ->
      val profileObj = profilesObj.optJSONObject(profileId) ?: return@mapNotNull null
      val profileName = sanitizeProfileName(profileObj.optString("profileName"))
      if (profileName.isBlank()) {
        return@mapNotNull null
      }
      val profileFile = customProfileFile(profileId)
      if (!profileFile.exists()) {
        return@mapNotNull null
      }
      Triple(profileId, profileName, profileObj.optLong("updatedAt", 0L))
    }
    if (candidates.isEmpty()) {
      return null
    }

    if (!requestedProfile.isNullOrBlank()) {
      val normalizedRequested = sanitizeProfileName(requestedProfile)
      val matched = candidates.firstOrNull { it.second == normalizedRequested }
        ?: throw IllegalStateException("CUSTOM_COOKIE_PROFILE_NOT_FOUND")
      return matched.first to customProfileFile(matched.first)
    }

    val defaultProfileName = readDefaultProfile(customDomainDir(domain, create = false))
    val defaultCandidate = defaultProfileName?.let { defaultName ->
      candidates.firstOrNull { it.second == defaultName }
    }
    val chosen = defaultCandidate ?: candidates.maxByOrNull { it.third }
    return chosen?.let { it.first to customProfileFile(it.first) }
  }

  private fun secureCookiesRoot(create: Boolean): File {
    val root = File(requireNotNull(appContext.reactContext).filesDir, "$SECURE_COOKIES_DIRNAME/$COOKIE_STORE_VERSION")
    if (create) {
      root.mkdirs()
    }
    return root
  }

  private fun customCookiesRoot(create: Boolean): File {
    val root = File(secureCookiesRoot(create = create), CUSTOM_COOKIES_DIRNAME)
    if (create) {
      root.mkdirs()
    }
    return root
  }

  private fun customProfilesDir(create: Boolean): File {
    val dir = File(customCookiesRoot(create = create), CUSTOM_PROFILES_DIRNAME)
    if (create) {
      dir.mkdirs()
    }
    return dir
  }

  private fun customDomainsRoot(create: Boolean): File {
    val dir = File(customCookiesRoot(create = create), CUSTOM_DOMAINS_DIRNAME)
    if (create) {
      dir.mkdirs()
    }
    return dir
  }

  private fun customDomainDir(domain: String, create: Boolean): File {
    val canonical = canonicalizeDomain(domain) ?: domain
    val dir = File(customDomainsRoot(create = create), canonical)
    if (create) {
      dir.mkdirs()
    }
    return dir
  }

  private fun customProfileFile(profileId: String): File {
    return File(customProfilesDir(create = true), "$profileId.enc")
  }

  private fun customCookieIndexFile(createParent: Boolean): File {
    val root = customCookiesRoot(create = createParent)
    return File(root, CUSTOM_INDEX_FILENAME)
  }

  private fun readCustomCookieIndex(): JSONObject {
    val file = customCookieIndexFile(createParent = true)
    val emptyIndex = JSONObject().apply {
      put("profiles", JSONObject())
      put("domains", JSONObject())
    }
    if (!file.exists()) {
      writeCustomCookieIndex(emptyIndex)
      return emptyIndex
    }

    return runCatching { JSONObject(file.readText(Charsets.UTF_8)) }
      .map { parsed ->
        if (!parsed.has("profiles") || parsed.optJSONObject("profiles") == null) {
          parsed.put("profiles", JSONObject())
        }
        if (!parsed.has("domains") || parsed.optJSONObject("domains") == null) {
          parsed.put("domains", JSONObject())
        }
        parsed
      }
      .getOrElse {
        // Same reasoning as the vault index: an unreadable index that reads as empty is
        // written back as empty by the next import, losing every profile-to-domain binding.
        addError("CUSTOM_COOKIE_INDEX_READ_FAILED: ${it.message}")
        throw IllegalStateException("COOKIE_INDEX_UNREADABLE")
      }
  }

  private fun writeCustomCookieIndex(index: JSONObject) {
    val file = customCookieIndexFile(createParent = true)
    atomicWriteBytes(file, index.toString().toByteArray(Charsets.UTF_8))
  }

  private fun ensureUniqueCustomProfileName(index: JSONObject, domains: List<String>, baseName: String): String {
    val profilesObj = index.getJSONObject("profiles")
    val domainsObj = index.getJSONObject("domains")
    var candidate = baseName
    var suffix = 2
    while (true) {
      val conflict = domains.any { domain ->
        val domainEntry = domainsObj.optJSONObject(domain) ?: return@any false
        val ids = jsonArrayToStringList(domainEntry.optJSONArray("profileIds"))
        ids.any { profileId ->
          sanitizeProfileName(profilesObj.optJSONObject(profileId)?.optString("profileName").orEmpty()) == candidate
        }
      }
      if (!conflict) {
        return candidate
      }
      candidate = "${baseName}_${suffix++}"
    }
  }

  private fun ensureCustomDomainDefault(domain: String, index: JSONObject) {
    val canonicalDomain = canonicalizeDomain(domain) ?: return
    val currentDefault = readDefaultProfile(customDomainDir(canonicalDomain, create = true))
    val domainsObj = index.getJSONObject("domains")
    val profilesObj = index.getJSONObject("profiles")
    val domainEntry = domainsObj.optJSONObject(canonicalDomain)
    val candidates = jsonArrayToStringList(domainEntry?.optJSONArray("profileIds"))
      .mapNotNull { profileId ->
        val profileObj = profilesObj.optJSONObject(profileId) ?: return@mapNotNull null
        val name = sanitizeProfileName(profileObj.optString("profileName"))
        val updatedAt = profileObj.optLong("updatedAt", 0L)
        if (name.isBlank()) null else name to updatedAt
      }
    if (candidates.isEmpty()) {
      customDomainDir(canonicalDomain, create = false).deleteRecursively()
      return
    }
    if (currentDefault != null && candidates.any { it.first == currentDefault }) {
      return
    }
    val nextDefault = candidates.maxByOrNull { it.second }?.first ?: return
    writeDefaultProfile(customDomainDir(canonicalDomain, create = true), nextDefault)
  }

  private fun jsonArrayToStringList(array: JSONArray?): List<String> {
    if (array == null) {
      return emptyList()
    }
    val result = mutableListOf<String>()
    for (i in 0 until array.length()) {
      val value = array.optString(i).trim()
      if (value.isNotBlank()) {
        result.add(value)
      }
    }
    return result
  }

  private fun jsonArrayContains(array: JSONArray, value: String): Boolean {
    for (i in 0 until array.length()) {
      if (array.optString(i) == value) {
        return true
      }
    }
    return false
  }

  private fun extractCanonicalHostFromUrl(url: String): String? {
    val trimmed = url.trim()
    if (trimmed.isBlank()) {
      return null
    }

    val candidates = if (trimmed.contains("://")) {
      listOf(trimmed)
    } else {
      listOf(trimmed, "https://$trimmed")
    }

    val rawHost = candidates.asSequence()
      .mapNotNull { candidate ->
        runCatching { URI(candidate).host?.lowercase() }.getOrNull()
      }
      .firstOrNull()
      ?: return null

    return canonicalizeDomain(rawHost)
  }

  private fun canonicalizeDomain(value: String): String? {
    val trimmed = value.trim().lowercase().removePrefix(".")
    if (trimmed.isBlank()) {
      return null
    }
    if (trimmed.contains("://") || trimmed.contains('/') || trimmed.contains('?') || trimmed.contains('#')) {
      return null
    }

    val host = runCatching {
      URI("https://$trimmed").host?.lowercase()
    }.getOrNull() ?: return null
    val normalized = host.removePrefix("www.").trim('.')
    if (normalized.isBlank() || normalized.contains("..")) {
      return null
    }
    if (!normalized.matches(Regex("^[a-z0-9.-]+$"))) {
      return null
    }
    return normalized
  }

  private fun extractDomainsFromCookieText(cookieText: String): Set<String> {
    val domains = mutableSetOf<String>()
    cookieText.lineSequence().forEach { line ->
      val trimmed = line.trim()
      if (trimmed.isBlank() || trimmed.startsWith("#")) {
        return@forEach
      }
      val columns = line.split('\t')
      if (columns.size < 7) {
        return@forEach
      }
      canonicalizeDomain(columns[0])?.let { domains.add(it) }
    }
    return domains
  }

  private fun secureCookiePlatformDir(platform: String, create: Boolean): File {
    val dir = File(secureCookiesRoot(create = create), platform)
    if (create) {
      dir.mkdirs()
    }
    return dir
  }

  private fun legacyCookiesRoot(): File {
    return File(requireNotNull(appContext.reactContext).filesDir, LEGACY_COOKIES_DIRNAME)
  }

  private fun runtimeCookieRoot(): File {
    return File(requireNotNull(appContext.reactContext).cacheDir, RUNTIME_COOKIE_DIRNAME)
  }

  private fun runtimeCookieTaskDir(taskId: String): File {
    return File(runtimeCookieRoot(), taskId)
  }

  private fun cleanupRuntimeCookieTemp(taskId: String? = null) {
    if (taskId == null) {
      runCatching {
        runtimeCookieRoot().deleteRecursively()
      }
      return
    }

    runCatching {
      runtimeCookieTaskDir(taskId).deleteRecursively()
    }
  }

  private fun writeEncryptedCookieFile(target: File, plaintext: ByteArray) {
    val encrypted = encryptCookieBytes(plaintext)
    atomicWriteBytes(target, encrypted)
  }

  private fun readEncryptedCookieFile(source: File): ByteArray {
    val payload = runCatching { source.readBytes() }.getOrElse {
      throw IllegalStateException("COOKIE_STORE_DECRYPT_FAILED")
    }
    return decryptCookieBytes(payload)
  }

  private fun encryptCookieBytes(plaintext: ByteArray): ByteArray {
    return runCatching {
      val cipher = Cipher.getInstance("AES/GCM/NoPadding")
      cipher.init(Cipher.ENCRYPT_MODE, getOrCreateCookieKey())
      val iv = cipher.iv
      val encrypted = cipher.doFinal(plaintext)
      if (iv.isEmpty() || iv.size > 255) {
        throw IllegalStateException("Invalid IV length")
      }

      ByteArray(1 + iv.size + encrypted.size).also { out ->
        out[0] = iv.size.toByte()
        System.arraycopy(iv, 0, out, 1, iv.size)
        System.arraycopy(encrypted, 0, out, 1 + iv.size, encrypted.size)
      }
    }.getOrElse {
      throw IllegalStateException("COOKIE_STORE_ENCRYPT_FAILED")
    }
  }

  private fun decryptCookieBytes(payload: ByteArray): ByteArray {
    try {
      if (payload.size < 2) {
        throw IllegalStateException("Invalid encrypted payload")
      }

      val ivLength = payload[0].toInt() and 0xff
      if (ivLength <= 0 || payload.size <= 1 + ivLength) {
        throw IllegalStateException("Invalid encrypted payload")
      }

      val iv = payload.copyOfRange(1, 1 + ivLength)
      val ciphertext = payload.copyOfRange(1 + ivLength, payload.size)

      val cipher = Cipher.getInstance("AES/GCM/NoPadding")
      cipher.init(Cipher.DECRYPT_MODE, getOrCreateCookieKey(), GCMParameterSpec(128, iv))
      return cipher.doFinal(ciphertext)
    } catch (_: AEADBadTagException) {
      throw IllegalStateException("COOKIE_STORE_DECRYPT_FAILED")
    } catch (_: Exception) {
      throw IllegalStateException("COOKIE_STORE_DECRYPT_FAILED")
    }
  }

  private fun getOrCreateCookieKey(): SecretKey {
    val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    val existing = keyStore.getKey(COOKIE_KEY_ALIAS, null) as? SecretKey
    if (existing != null) {
      return existing
    }

    val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
    val spec = KeyGenParameterSpec.Builder(
      COOKIE_KEY_ALIAS,
      KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
    )
      .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
      .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
      .setRandomizedEncryptionRequired(true)
      .build()

    keyGenerator.init(spec)
    return keyGenerator.generateKey()
  }

  private fun atomicWriteBytes(target: File, data: ByteArray) {
    target.parentFile?.mkdirs()
    val temp = File(target.parentFile, ".${target.name}.${UUID.randomUUID()}.tmp")
    temp.outputStream().use { stream ->
      stream.write(data)
      stream.flush()
      // Without the sync the rename can reach the disk before the bytes do, so a power loss
      // leaves an empty file where a valid one used to be.
      stream.fd.sync()
    }
    if (!temp.renameTo(target)) {
      // Never rewrite the target in place as a fallback. That turns a failed rename into a
      // half-written file, and for an index a half-written file is unrecoverable. The old
      // one is still intact, so leave it alone and report the failure.
      temp.delete()
      throw IllegalStateException("ATOMIC_WRITE_FAILED: ${target.name}")
    }
  }

  private fun migrateLegacyCookieStoreIfNeeded() {
    val legacyRoot = legacyCookiesRoot()
    val secureRoot = secureCookiesRoot(create = true)
    val marker = File(secureRoot, COOKIE_MIGRATION_MARKER_FILENAME)

    val legacyCount = countLegacyCookieProfiles()
    if (marker.exists()) {
      cookieMigrationStatus = if (legacyCount > 0) "partial" else "migrated"
      return
    }
    if (legacyCount == 0) {
      cookieMigrationStatus = "not_needed"
      return
    }

    var hadFailures = false
    var migratedAny = false

    SUPPORTED_PLATFORMS.forEach { platform ->
      val legacyPlatformDir = File(legacyRoot, platform)
      if (!legacyPlatformDir.exists()) {
        return@forEach
      }

      val securePlatformDir = secureCookiePlatformDir(platform, create = true)
      val legacyFiles = legacyPlatformDir.listFiles()
        ?.filter { it.isFile && (it.extension == "txt" || it.extension == "json") }
        ?: emptyList()

      legacyFiles.forEach { file ->
        val profileName = sanitizeProfileName(file.nameWithoutExtension)
        val secureFile = File(securePlatformDir, "$profileName.enc")
        val migrated = runCatching {
          val normalized = normalizeCookieContent(file.readText(Charsets.UTF_8))
          writeEncryptedCookieFile(secureFile, normalized.toByteArray(Charsets.UTF_8))
          val roundTrip = readEncryptedCookieFile(secureFile).toString(Charsets.UTF_8)
          if (roundTrip.isBlank()) {
            throw IllegalStateException("Round-trip validation failed")
          }
          file.delete()
        }.onFailure {
          hadFailures = true
          addError("COOKIE_MIGRATION_FAILED: platform=$platform profile=${file.nameWithoutExtension}")
        }.isSuccess

        if (migrated) {
          migratedAny = true
        }
      }

      val legacyDefault = readDefaultProfileLegacy(legacyPlatformDir)
      if (!legacyDefault.isNullOrBlank()) {
        val normalizedDefault = sanitizeProfileName(legacyDefault)
        val existsInSecure = File(securePlatformDir, "$normalizedDefault.enc").exists()
        if (existsInSecure) {
          writeDefaultProfile(securePlatformDir, normalizedDefault)
        }
      }
      File(legacyPlatformDir, DEFAULT_COOKIE_PROFILE_FILENAME).delete()
      if (legacyPlatformDir.listFiles().isNullOrEmpty()) {
        legacyPlatformDir.delete()
      }
    }

    cookieMigrationStatus = when {
      hadFailures && migratedAny -> "partial"
      hadFailures -> "failed"
      migratedAny -> "migrated"
      else -> "failed"
    }

    if (!hadFailures) {
      marker.writeText("ok")
    }
  }

  private fun readDefaultProfileLegacy(platformDir: File): String? {
    val file = File(platformDir, DEFAULT_COOKIE_PROFILE_FILENAME)
    if (!file.exists()) {
      return null
    }

    val value = sanitizeProfileName(file.readText().trim())
    if (value.isBlank()) {
      return null
    }

    val profileExists = platformDir.listFiles()
      ?.any { it.isFile && it.nameWithoutExtension == value && (it.extension == "txt" || it.extension == "json") }
      ?: false
    return if (profileExists) value else null
  }

  private fun countSecureCookieProfiles(): Int {
    val root = secureCookiesRoot(create = false)
    if (!root.exists()) {
      return 0
    }

    val builtInCount = SUPPORTED_PLATFORMS.sumOf { platform ->
      File(root, platform).listFiles()?.count { it.isFile && it.extension == "enc" } ?: 0
    }
    return builtInCount + countCustomProfiles()
  }

  private fun countCustomProfiles(): Int {
    val dir = customProfilesDir(create = false)
    if (!dir.exists()) {
      return 0
    }
    return dir.listFiles()?.count { it.isFile && it.extension == "enc" } ?: 0
  }

  private fun countCustomDomains(): Int {
    synchronized(customCookieIndexLock) {
      val index = readCustomCookieIndex()
      return index.getJSONObject("domains").length()
    }
  }

  private fun countLegacyCookieProfiles(): Int {
    val root = legacyCookiesRoot()
    if (!root.exists()) {
      return 0
    }

    return SUPPORTED_PLATFORMS.sumOf { platform ->
      File(root, platform).listFiles()?.count { it.isFile && (it.extension == "txt" || it.extension == "json") } ?: 0
    }
  }

  private fun isSecureCookieStoreEnabled(): Boolean {
    return runCatching {
      getOrCreateCookieKey()
      true
    }.getOrDefault(false)
  }

  private fun extractKnownErrorCode(message: String?): String? {
    if (message.isNullOrBlank()) {
      return null
    }

    val knownCodes = listOf(
      "COOKIE_STORE_ENCRYPT_FAILED",
      "COOKIE_STORE_DECRYPT_FAILED",
      "COOKIE_MIGRATION_FAILED",
      "COOKIE_PROFILE_NOT_FOUND",
      "INVALID_CUSTOM_DOMAIN",
      "CUSTOM_COOKIE_NO_DOMAIN_DETECTED",
      "CUSTOM_COOKIE_DOMAIN_NOT_FOUND",
      "CUSTOM_COOKIE_PROFILE_NOT_FOUND",
      "REDDIT_COOKIE_REQUIRED",
      "FFMPEG_NATIVE_RUNTIME_UNAVAILABLE",
      "FFMPEG_MISSING",
      "FFPROBE_MISSING",
      "MERGE_DEPENDENCY_MISSING",
      "SITE_BLOCKED_403",
      "COOKIE_STALE_OR_INVALID",
      "REDDIT_SHARE_URL_RESOLUTION_FAILED",
      "REDDIT_EXTRACTOR_ROUTE_FAILED",
      "TIKTOK_API_STATUS_ZERO",
      "TIKTOK_EXTRACTOR_UNSTABLE",
      "IMPERSONATION_BOOTSTRAP_FAILED",
      "IMPERSONATION_TARGET_REQUIRED_UNAVAILABLE",
      "IMPERSONATION_DEPENDENCY_MISSING",
      "IMPERSONATION_RUNTIME_UNAVAILABLE",
      "BACKGROUND_PERMISSION_REQUIRED",
      "NO_CLIPBOARD_URL",
      "DOWNLOAD_QUEUE_FULL",
      "BACKGROUND_SERVICE_START_FAILED",
      "QUICK_DOWNLOAD_REJECTED",
      "PRIVATE_AUTH_REQUIRED",
      "PRIVATE_AUTH_FAILED",
      "PRIVATE_STORAGE_WRITE_FAILED",
      "PRIVATE_VIDEO_NOT_FOUND",
      "PRIVATE_EXPORT_FAILED",
      "PRIVATE_EXPORT_DISABLED",
      "PRIVATE_MODE_UNAVAILABLE",
      "COOKIE_DOMAIN_MISMATCH",
      "COOKIE_EMPTY_OR_EXPIRED",
      "TIMESTAMP_POSTPROCESS_FAILED",
      "INVALID_URL",
      "UNSUPPORTED_PLATFORM",
      "DOWNLOAD_ALREADY_IN_PROGRESS",
      "FILE_TOO_LARGE",
      "DOWNLOAD_CANCELLED",
      "TASK_CANCEL_TIMEOUT",
      "PROCESS_RESTARTED",
      "PREFLIGHT_FAILED",
      "INTERNAL_ERROR",
      "FILE_NOT_FOUND"
    )

    return knownCodes.firstOrNull { code -> message.contains(code) }
  }

  private fun sanitizeProfileName(value: String): String {
    return value
      .trim()
      .lowercase()
      .replace(Regex("[^a-z0-9._-]"), "_")
      .removeSuffix(".txt")
      .ifBlank { "default" }
  }

  private fun normalizeCookieContent(rawContent: String): String {
    val trimmed = rawContent.trim()
    if (trimmed.isBlank()) {
      throw IllegalArgumentException("Cookie file is empty")
    }

    return if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
      convertJsonCookiesToNetscape(trimmed)
    } else {
      validateNetscapeCookieText(rawContent)
    }
  }

  private fun validateNetscapeCookieText(rawContent: String): String {
    var cookieLines = 0
    rawContent.lineSequence().forEach { line ->
      val trimmed = line.trim()
      if (trimmed.isBlank() || trimmed.startsWith("#")) {
        return@forEach
      }

      val columns = line.split('\t')
      if (columns.size < 7) {
        throw IllegalArgumentException("Unsupported cookie format. Expected Netscape cookie file.")
      }
      cookieLines += 1
    }

    if (cookieLines == 0) {
      throw IllegalArgumentException("No valid cookie entries found")
    }

    return if (rawContent.endsWith("\n")) rawContent else "$rawContent\n"
  }

  private fun convertJsonCookiesToNetscape(jsonText: String): String {
    val cookies = extractCookieArray(jsonText)
    val output = StringBuilder()
    output.append("# Netscape HTTP Cookie File\n")
    output.append("# Generated by Arsivinyo Local\n")
    output.append("# This file is used by yt-dlp\n\n")

    var written = 0
    for (i in 0 until cookies.length()) {
      val cookie = cookies.optJSONObject(i) ?: continue
      val name = cookie.optString("name").sanitizeCookieField()
      val value = cookie.optString("value").sanitizeCookieField()
      if (name.isBlank()) continue

      val rawDomain = (
        cookie.optString("domain")
          .ifBlank { cookie.optString("host") }
      ).sanitizeCookieField()
      if (rawDomain.isBlank()) continue

      val hostOnly = cookie.optBoolean("hostOnly", false)
      val includeSubdomains = if (hostOnly) "FALSE" else "TRUE"
      val domain = when {
        hostOnly -> rawDomain.removePrefix(".")
        rawDomain.startsWith(".") -> rawDomain
        else -> ".$rawDomain"
      }

      val path = cookie.optString("path", "/").ifBlank { "/" }.sanitizeCookieField()
      val secure = if (cookie.optBoolean("secure", false)) "TRUE" else "FALSE"
      val expiry = parseCookieExpiry(cookie).coerceAtLeast(0L)

      output
        .append(domain)
        .append('\t')
        .append(includeSubdomains)
        .append('\t')
        .append(path)
        .append('\t')
        .append(secure)
        .append('\t')
        .append(expiry)
        .append('\t')
        .append(name)
        .append('\t')
        .append(value)
        .append('\n')

      written += 1
    }

    if (written == 0) {
      throw IllegalArgumentException("No valid cookies found in JSON file")
    }

    return output.toString()
  }

  private fun extractCookieArray(jsonText: String): JSONArray {
    if (jsonText.trimStart().startsWith("[")) {
      return JSONArray(jsonText)
    }

    val root = JSONObject(jsonText)
    if (root.has("cookies") && root.optJSONArray("cookies") != null) {
      return root.getJSONArray("cookies")
    }
    if (root.has("items") && root.optJSONArray("items") != null) {
      return root.getJSONArray("items")
    }
    if (root.has("data") && root.optJSONArray("data") != null) {
      return root.getJSONArray("data")
    }

    return JSONArray().put(root)
  }

  private fun parseCookieExpiry(cookie: JSONObject): Long {
    val candidate = when {
      cookie.has("expirationDate") -> cookie.opt("expirationDate")
      cookie.has("expires") -> cookie.opt("expires")
      cookie.has("expiry") -> cookie.opt("expiry")
      else -> null
    } ?: return 0L

    return when (candidate) {
      is Number -> normalizeEpoch(candidate.toLong())
      is String -> {
        candidate.toLongOrNull()?.let { normalizeEpoch(it) }
          ?: runCatching { Instant.parse(candidate).epochSecond }.getOrDefault(0L)
      }
      else -> 0L
    }
  }

  private fun normalizeEpoch(value: Long): Long {
    return if (value > 9_999_999_999L) value / 1000L else value
  }

  private fun String.sanitizeCookieField(): String {
    return this.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim()
  }

  private fun getOrResolveFfmpegInfo(forceRefresh: Boolean = false): FfmpegInfo {
    if (!forceRefresh) {
      val cached = cachedFfmpegInfo
      if (cached != null) {
        debug("Using cached ffmpeg info: ${summarizeFfmpegInfo(cached)}")
        return cached
      }
    }

    val resolved = resolveBundledFfmpegPath()
    debug("Resolved ffmpeg info: ${summarizeFfmpegInfo(resolved)}")
    cachedFfmpegInfo = resolved
    return resolved
  }

  private fun resolveBundledFfmpegPath(): FfmpegInfo {
    val context = requireNotNull(appContext.reactContext)
    debug("Resolving ffmpeg runtime (native libs first)")
    val nativeSnapshot = readNativeLibrarySnapshot(context)
    resolveNativeLibraryFfmpeg(nativeSnapshot)?.let { nativeInfo ->
      debug("Using native lib dir ffmpeg runtime: ${summarizeFfmpegInfo(nativeInfo)}")
      return nativeInfo
    }

    val fallback = inspectAssetRuntimeFallback(context, nativeSnapshot)
    addError(
      "FFMPEG_NATIVE_RUNTIME_UNAVAILABLE: nativeDir=${fallback.nativeLibraryDir ?: "n/a"} " +
        "entries=${fallback.nativeLibraryEntries.joinToString()} " +
        "ffmpeg=${fallback.ffmpegProbeError ?: "n/a"} ffprobe=${fallback.ffprobeProbeError ?: "n/a"}"
    )
    return fallback
  }

  private fun readNativeLibrarySnapshot(context: android.content.Context): Pair<String?, List<String>> {
    val nativeDirPath = context.applicationInfo.nativeLibraryDir
    if (nativeDirPath.isNullOrBlank()) {
      return null to emptyList()
    }

    val nativeDir = File(nativeDirPath)
    if (!nativeDir.exists() || !nativeDir.isDirectory) {
      return nativeDirPath to emptyList()
    }

    val entries = nativeDir.listFiles()
      ?.map { it.name }
      ?.sorted()
      ?: emptyList()
    return nativeDirPath to entries
  }

  private fun resolveNativeLibraryFfmpeg(nativeSnapshot: Pair<String?, List<String>>): FfmpegInfo? {
    val nativeDirPath = nativeSnapshot.first ?: return null
    val nativeDir = File(nativeDirPath)
    if (!nativeDir.exists() || !nativeDir.isDirectory) {
      debug("Native library dir unavailable: $nativeDirPath")
      return null
    }
    debug("Checking native library dir for ffmpeg: ${nativeDir.absolutePath}")
    debug("Native library dir entries: ${nativeSnapshot.second.joinToString()}")

    val binaryNamePairs = listOf(
      "ffmpeg" to "ffprobe",
      "libffmpeg.so" to "libffprobe.so",
    )

    for ((ffmpegName, ffprobeName) in binaryNamePairs) {
      val ffmpegFile = File(nativeDir, ffmpegName)
      val ffprobeFile = File(nativeDir, ffprobeName)
      if (!ffmpegFile.exists() || !ffprobeFile.exists()) {
        debug("Native pair missing ffmpeg=${ffmpegFile.exists()} ffprobe=${ffprobeFile.exists()} names=$ffmpegName/$ffprobeName")
        continue
      }

      val ffmpegProbe = probeBinary(ffmpegFile.absolutePath, "ffmpeg")
      val ffprobeProbe = probeBinary(ffprobeFile.absolutePath, "ffprobe")

      val ffmpegRunnable = ffmpegProbe.runnable
      val ffprobeRunnable = ffprobeProbe.runnable
      val mergeCapable = ffmpegRunnable && ffprobeRunnable
      debug(
        "Native pair probe names=$ffmpegName/$ffprobeName runnable=$mergeCapable " +
          "ffmpeg=${ffmpegProbe.version ?: ffmpegProbe.error} ffprobe=${ffprobeProbe.version ?: ffprobeProbe.error}"
      )
      if (!mergeCapable) {
        addError(
          "FFMPEG_NATIVE_RUNTIME_NOT_READY: ffmpeg=${ffmpegProbe.error ?: "not runnable"} " +
            "ffprobe=${ffprobeProbe.error ?: "not runnable"} dir=${nativeDir.absolutePath}"
        )
      }

      return FfmpegInfo(
        path = ffmpegFile.absolutePath,
        ffprobePath = ffprobeFile.absolutePath,
        location = nativeDir.absolutePath,
        abi = Build.SUPPORTED_ABIS?.firstOrNull(),
        runtimeSource = "native_library",
        nativeLibraryDir = nativeDir.absolutePath,
        nativeLibraryEntries = nativeSnapshot.second,
        exists = true,
        ffprobeExists = true,
        executable = ffmpegRunnable,
        ffprobeExecutable = ffprobeRunnable,
        version = ffmpegProbe.version,
        ffprobeVersion = ffprobeProbe.version,
        ffmpegProbeError = ffmpegProbe.error,
        ffprobeProbeError = ffprobeProbe.error,
        mergeCapable = mergeCapable,
      )
    }

    return null
  }

  private fun inspectAssetRuntimeFallback(
    context: android.content.Context,
    nativeSnapshot: Pair<String?, List<String>>
  ): FfmpegInfo {
    val candidateAbis = Build.SUPPORTED_ABIS?.toList()?.ifEmpty { SUPPORTED_FFMPEG_ABIS } ?: SUPPORTED_FFMPEG_ABIS
    debug("Inspecting ffmpeg assets ABIs: ${candidateAbis.joinToString()}")
    for (abi in candidateAbis) {
      if (!SUPPORTED_FFMPEG_ABIS.contains(abi)) {
        continue
      }

      val ffmpegAssetExists = assetExists(context, "ffmpeg/$abi/ffmpeg")
      val ffprobeAssetExists = assetExists(context, "ffmpeg/$abi/ffprobe")
      if (!ffmpegAssetExists && !ffprobeAssetExists) {
        continue
      }

      return FfmpegInfo(
        abi = abi,
        runtimeSource = "asset_fallback",
        nativeLibraryDir = nativeSnapshot.first,
        nativeLibraryEntries = nativeSnapshot.second,
        exists = ffmpegAssetExists,
        ffprobeExists = ffprobeAssetExists,
        executable = false,
        ffprobeExecutable = false,
        ffmpegProbeError = if (ffmpegAssetExists) {
          "Asset fallback binaries are non-executable on this device; enable native library runtime extraction."
        } else {
          "ffmpeg asset missing"
        },
        ffprobeProbeError = if (ffprobeAssetExists) {
          "Asset fallback binaries are non-executable on this device; enable native library runtime extraction."
        } else {
          "ffprobe asset missing"
        },
        mergeCapable = false,
      )
    }

    return FfmpegInfo(
      runtimeSource = "none",
      nativeLibraryDir = nativeSnapshot.first,
      nativeLibraryEntries = nativeSnapshot.second,
      exists = false,
      ffprobeExists = false,
      executable = false,
      ffprobeExecutable = false,
      ffmpegProbeError = "No compatible native runtime or bundled ffmpeg assets found for device ABI.",
      ffprobeProbeError = "No compatible native runtime or bundled ffprobe assets found for device ABI.",
      mergeCapable = false,
    )
  }

  private fun assetExists(context: android.content.Context, assetPath: String): Boolean {
    return runCatching {
      context.assets.open(assetPath).use { _ -> }
      true
    }.getOrDefault(false)
  }

  private fun probeBinary(binaryPath: String, label: String): BinaryProbeResult {
    debug("Probing $label binary at $binaryPath")
    return runCatching {
      val process = ProcessBuilder(binaryPath, "-version")
        .redirectErrorStream(true)
        .start()

      val finished = process.waitFor(2, TimeUnit.SECONDS)
      if (!finished) {
        process.destroyForcibly()
        return@runCatching BinaryProbeResult(
          runnable = false,
          error = "$label probe timed out"
        )
      }

      val output = process.inputStream.bufferedReader().use { reader ->
        reader.readText()
      }
      val firstLine = output.lineSequence().firstOrNull()?.trim()
      val exitCode = process.exitValue()
      if (exitCode == 0 && !firstLine.isNullOrBlank()) {
        debug("$label probe success version=$firstLine")
        BinaryProbeResult(
          runnable = true,
          version = firstLine
        )
      } else {
        val snippet = output
          .lineSequence()
          .take(2)
          .joinToString(" | ")
          .ifBlank { "no output" }
        debug("$label probe failure exit=$exitCode snippet=$snippet")
        BinaryProbeResult(
          runnable = false,
          error = "$label exited $exitCode: $snippet"
        )
      }
    }.getOrElse {
      debug("$label probe exception: ${it.message ?: it::class.java.simpleName}")
      BinaryProbeResult(
        runnable = false,
        error = "$label probe failed: ${it.message ?: it::class.java.simpleName}"
      )
    }
  }

  private fun createCancelFlag(taskId: String): File {
    val context = requireNotNull(appContext.reactContext)
    val cancelDir = File(context.cacheDir, "local_download_cancel_flags").apply { mkdirs() }
    val flagFile = File(cancelDir, "$taskId.cancel")
    if (flagFile.exists()) {
      flagFile.delete()
    }
    cancelFlags[taskId] = flagFile
    return flagFile
  }

  private fun createProgressFile(taskId: String): File {
    val context = requireNotNull(appContext.reactContext)
    val progressDir = File(context.cacheDir, DOWNLOAD_PROGRESS_DIRNAME).apply { mkdirs() }
    val progressFile = File(progressDir, "$taskId.json")
    clearProgressFile(progressFile)
    return progressFile
  }

  private fun clearProgressFile(progressFile: File?) {
    if (progressFile == null) return
    runCatching {
      if (progressFile.exists()) {
        progressFile.delete()
      }
      val tmp = File("${progressFile.absolutePath}.tmp")
      if (tmp.exists()) {
        tmp.delete()
      }
    }
  }

  private suspend fun observeProgressFile(taskId: String, progressFile: File) {
    var lastProgressBucket = -1
    var lastProgressState: String? = null
    var lastSpeedBucket = -1
    while (currentCoroutineContext().isActive) {
      // "still mine to watch", not "am I the one active download". Under concurrency the
      // latter was true for at most one watcher, so every other download reported no
      // progress at all for its entire life.
      if (!isTaskLive(taskId) || shouldIgnoreTaskResult(taskId) || isTerminalStatus(tasks[taskId]?.status)) {
        return
      }

      runCatching {
        if (!progressFile.exists()) {
          return@runCatching
        }
        val raw = progressFile.readText()
        if (raw.isBlank()) {
          return@runCatching
        }
        val json = JSONObject(raw)
        val percent = json.optDouble("progressPercent", Double.NaN)
          .takeIf { !it.isNaN() }
          ?.coerceIn(0.0, 100.0)
          ?: return@runCatching
        val speedBytesPerSec = json.optDouble("speedBytesPerSec", Double.NaN)
          .takeIf { !it.isNaN() && it > 0.0 }
        val progressState = normalizeProgressEventState(json.optString("status").ifBlank { "downloading" })
        val bucket = percent.toInt()
        val speedBucket = speedBytesPerSec?.let { (it / 1024.0).toInt() } ?: -1
        if (bucket == lastProgressBucket && progressState == lastProgressState && speedBucket == lastSpeedBucket) {
          return@runCatching
        }

        lastProgressBucket = bucket
        lastProgressState = progressState
        lastSpeedBucket = speedBucket
        tasks[taskId]?.progressPercent = percent
        tasks[taskId]?.speedBytesPerSec = speedBytesPerSec
        val message = json.optString("message").ifBlank { "Downloading media" }
        emitProgress(taskId, "PROGRESS", progressState, message, percent, speedBytesPerSec)
      }.onFailure {
        debug("Task[$taskId] progress file parse failed: ${it.message}")
      }

      delay(DOWNLOAD_PROGRESS_POLL_MS)
    }
  }

  private fun markCancelRequested(taskId: String) {
    val flag = cancelFlags[taskId] ?: return
    runCatching {
      if (!flag.exists()) {
        flag.writeText("cancel")
      }
    }.onFailure {
      addError("CANCEL_FLAG_WRITE_FAILED: ${it.message}")
    }
  }

  private fun isCancelRequested(taskId: String): Boolean {
    return cancelFlags[taskId]?.exists() == true
  }

  private fun clearCancelFlag(taskId: String) {
    val flag = cancelFlags.remove(taskId) ?: return
    runCatching {
      if (flag.exists()) {
        flag.delete()
      }
    }
  }

  private fun markCancelled(taskId: String, message: String) {
    updateStatus(taskId, "CANCELLED", null, null, null, "TASK_CANCELLED", message)
    emitProgress(taskId, "CANCELLED", "error", message)
  }

  private fun isTerminalStatus(status: String?): Boolean {
    return status == "SUCCESS" || status == "FAILURE" || status == "CANCELLED"
  }

  private fun shouldIgnoreTaskResult(taskId: String): Boolean {
    return ignoredTaskResults.contains(taskId)
  }

  private fun readDefaultProfile(platformDir: File): String? {
    val file = File(platformDir, DEFAULT_COOKIE_PROFILE_FILENAME)
    if (!file.exists()) {
      return null
    }

    val value = file.readText().trim()
    if (value.isBlank()) {
      return null
    }

    val profileExists = platformDir.listFiles()
      ?.any { it.isFile && it.nameWithoutExtension == value && it.extension == "enc" }
      ?: false

    return if (profileExists) value else null
  }

  private fun writeDefaultProfile(platformDir: File, profileName: String) {
    val file = File(platformDir, DEFAULT_COOKIE_PROFILE_FILENAME)
    file.writeText(profileName)
  }

  private fun clearDefaultProfile(platformDir: File) {
    val file = File(platformDir, DEFAULT_COOKIE_PROFILE_FILENAME)
    if (file.exists()) {
      file.delete()
    }
  }

  private fun addError(message: String) {
    val timestamped = "${Instant.now()}: $message"
    if (debugLoggingEnabled) {
      Log.e(tag, message)
    }
    lastErrors.addFirst(timestamped)
    while (lastErrors.size > MAX_ERROR_LOGS) {
      lastErrors.removeLast()
    }
  }

  private fun failureLogFile(context: Context): File {
    return File(context.filesDir, FAILURE_LOG_FILENAME)
  }

  private fun readDownloadFailureLogArray(context: Context): JSONArray {
    val file = failureLogFile(context)
    if (!file.exists()) {
      return JSONArray()
    }

    return runCatching {
      JSONArray(file.readText(Charsets.UTF_8))
    }.getOrElse {
      Log.w(tag, "Failed to read download failure log", it)
      JSONArray()
    }
  }

  private fun writeDownloadFailureLogArray(context: Context, array: JSONArray) {
    val file = failureLogFile(context)
    val tmp = File("${file.absolutePath}.tmp")
    file.parentFile?.mkdirs()
    FileOutputStream(tmp).use { stream ->
      stream.write(array.toString().toByteArray(Charsets.UTF_8))
      stream.fd.sync()
    }
    if (file.exists() && !file.delete()) {
      throw IOException("Could not replace failure log")
    }
    if (!tmp.renameTo(file)) {
      throw IOException("Could not commit failure log")
    }
  }

  private fun recordDownloadFailure(taskId: String, code: String?, message: String?) {
    val context = appContext.reactContext ?: return
    val safeCode = code?.takeIf { it.isNotBlank() } ?: "UNKNOWN_ERROR"
    val safeMessage = message?.takeIf { it.isNotBlank() } ?: safeCode
    val task = tasks[taskId]
    val sourceUrl = task?.url?.takeIf { it.isNotBlank() }

    synchronized(failureLogLock) {
      runCatching {
        val existing = readDownloadFailureLogArray(context)
        val next = JSONArray()
        next.put(
          JSONObject().apply {
            put("id", UUID.randomUUID().toString())
            put("createdAt", System.currentTimeMillis())
            put("taskId", taskId)
            put("code", safeCode)
            put("url", sourceUrl)
            put("normalizedUrl", task?.normalizedUrl)
            put("preflightWarning", task?.preflightWarning?.let { JSONObject(it) })
            put("preflightStrategy", task?.preflightStrategy)
            put("downloadStrategy", task?.downloadStrategy)
            put("extractorKey", task?.extractorKey)
            put("formatSelector", task?.formatSelector)
            put("attemptTrace", task?.attemptTrace?.let { JSONArray(it.map { item -> JSONObject(item) }) })
            put("toolOutput", task?.toolOutput?.take(MAX_FAILURE_LOG_TOOL_OUTPUT_CHARS))
            put("preflightBudgetSec", task?.preflightBudgetSec)
            put("preflightElapsedMs", task?.preflightElapsedMs)
            put("preflightAttemptLimit", task?.preflightAttemptLimit)
            put("staticMediaCandidateCount", task?.staticMediaCandidateCount)
            put("message", safeMessage.take(MAX_FAILURE_LOG_MESSAGE_CHARS))
          }
        )
        for (i in 0 until minOf(existing.length(), MAX_DOWNLOAD_FAILURE_LOGS - 1)) {
          val item = existing.optJSONObject(i) ?: continue
          next.put(item)
        }
        writeDownloadFailureLogArray(context, next)
      }.onFailure {
        Log.w(tag, "Failed to persist download failure log", it)
      }
    }
  }

  private fun readDownloadFailureLogsInternal(): List<Map<String, Any?>> {
    val context = requireNotNull(appContext.reactContext)
    return synchronized(failureLogLock) {
      val array = readDownloadFailureLogArray(context)
      (0 until minOf(array.length(), MAX_DOWNLOAD_FAILURE_LOGS)).mapNotNull { index ->
        val item = array.optJSONObject(index) ?: return@mapNotNull null
        val message = item.optString("message").takeIf { it.isNotBlank() } ?: return@mapNotNull null
        mapOf(
          "id" to item.optString("id").ifBlank { "${item.optLong("createdAt", 0L)}-$index" },
          "createdAt" to item.optLong("createdAt", 0L),
          "taskId" to item.optString("taskId").ifBlank { null },
          "code" to item.optString("code").ifBlank { null },
          "url" to item.optString("url").ifBlank { null },
          "normalizedUrl" to item.optString("normalizedUrl").ifBlank { null },
          "preflightWarning" to item.optJSONObject("preflightWarning")?.let { jsonObjectToMap(it) },
          "preflightStrategy" to item.optString("preflightStrategy").ifBlank { null },
          "downloadStrategy" to item.optString("downloadStrategy").ifBlank { null },
          "extractorKey" to item.optString("extractorKey").ifBlank { null },
          "formatSelector" to item.optString("formatSelector").ifBlank { null },
          "attemptTrace" to item.optJSONArray("attemptTrace")?.let { jsonArrayToMapList(it, MAX_FAILURE_LOG_ATTEMPTS) },
          "toolOutput" to item.optString("toolOutput").ifBlank { null },
          "preflightBudgetSec" to item.optInt("preflightBudgetSec", Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE },
          "preflightElapsedMs" to item.optLong("preflightElapsedMs", Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE },
          "preflightAttemptLimit" to item.optInt("preflightAttemptLimit", Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE },
          "staticMediaCandidateCount" to item.optInt("staticMediaCandidateCount", Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE },
          "message" to message
        )
      }
    }
  }

  /**
   * A result as it may be logged: what happened, never what it was. The file name carries
   * the title, and the source carries the post's caption and account, so all of them stay
   * out of the log even with debug logging on.
   */
  private fun redactedForLog(result: JSONObject): String {
    val copy = JSONObject(result.toString())
    for (key in listOf("file_path", "filename", "thumbnail_path", "source", "normalized_url", "title")) {
      if (copy.has(key)) copy.put(key, "<redacted>")
    }
    return copy.toString()
  }

  private fun debug(message: String) {
    if (debugLoggingEnabled) {
      Log.d(tag, message)
    }
  }

  private fun privateTrace(traceId: String, message: String) {
    debug("[PRIVATE][trace=$traceId] $message")
  }

  private fun summarizeFfmpegInfo(info: FfmpegInfo): String {
    return "source=${info.runtimeSource} abi=${info.abi} exists=${info.exists} ffmpeg=${info.path} ffprobe=${info.ffprobePath} " +
      "ffmpegExec=${info.executable} ffprobeExec=${info.ffprobeExecutable} mergeCapable=${info.mergeCapable} " +
      "ffmpegVersion=${info.version ?: "n/a"} ffprobeVersion=${info.ffprobeVersion ?: "n/a"} " +
      "ffmpegProbeError=${info.ffmpegProbeError ?: "n/a"} ffprobeProbeError=${info.ffprobeProbeError ?: "n/a"}"
  }

  private fun persistTaskSnapshot() {
    runCatching {
      val context = requireNotNull(appContext.reactContext)
      val file = File(context.filesDir, TASK_SNAPSHOT_FILENAME)
      val array = JSONArray()
      tasks.values.forEach { task ->
        array.put(JSONObject(task.toMap()))
      }
      file.writeText(array.toString())
    }.onFailure {
      Log.w(tag, "Failed to persist task snapshot", it)
    }
  }

  private fun loadTaskSnapshot() {
    runCatching {
      val context = requireNotNull(appContext.reactContext)
      val file = File(context.filesDir, TASK_SNAPSHOT_FILENAME)
      if (!file.exists()) return

      var hadRestartedInFlightTask = false
      val array = JSONArray(file.readText())
      for (i in 0 until array.length()) {
        val obj = array.getJSONObject(i)
        val taskId = obj.optString("taskId")
        if (taskId.isBlank()) continue

        val originalStatus = obj.optString("status", "PENDING")
        val wasInFlight = originalStatus in IN_FLIGHT_STATUSES

        tasks[taskId] = TaskState(
          taskId = taskId,
          status = if (wasInFlight) "FAILURE" else originalStatus,
          url = obj.optString("url").ifBlank { null },
          state = obj.optString("state").ifBlank { if (wasInFlight) "error" else null },
          filename = obj.optString("filename").ifBlank { null },
          filePath = obj.optString("filePath").ifBlank { null },
          isPrivate = if (obj.has("isPrivate")) obj.optBoolean("isPrivate") else null,
          privateVideoId = obj.optString("privateVideoId").ifBlank { null },
          sizeMb = obj.optDouble("sizeMb", Double.NaN).takeIf { !it.isNaN() },
          progressPercent = obj.optDouble("progressPercent", Double.NaN).takeIf { !it.isNaN() },
          speedBytesPerSec = obj.optDouble("speedBytesPerSec", Double.NaN).takeIf { !it.isNaN() && it > 0.0 },
          errorCode = if (wasInFlight) "PROCESS_RESTARTED" else obj.optString("errorCode").ifBlank { null },
          errorMessage = if (wasInFlight) {
            "Download was interrupted because app process restarted."
          } else {
            obj.optString("errorMessage").ifBlank { null }
          },
          normalizedUrl = obj.optString("normalizedUrl").ifBlank { null },
          preflightWarning = obj.optJSONObject("preflightWarning")?.let { jsonObjectToMap(it) },
          preflightStrategy = obj.optString("preflightStrategy").ifBlank { null },
          downloadStrategy = obj.optString("downloadStrategy").ifBlank { null },
          extractorKey = obj.optString("extractorKey").ifBlank { null },
          formatSelector = obj.optString("formatSelector").ifBlank { null },
          attemptTrace = obj.optJSONArray("attemptTrace")?.let { jsonArrayToMapList(it, MAX_FAILURE_LOG_ATTEMPTS) },
          toolOutput = obj.optString("toolOutput").ifBlank { null },
          preflightBudgetSec = obj.optInt("preflightBudgetSec", Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE },
          preflightElapsedMs = obj.optLong("preflightElapsedMs", Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE },
          preflightAttemptLimit = obj.optInt("preflightAttemptLimit", Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE },
          staticMediaCandidateCount = obj.optInt("staticMediaCandidateCount", Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE },
          estimatedSizeMb = obj.optDouble("estimatedSizeMb", Double.NaN).takeIf { !it.isNaN() },
          timestampNormalized = if (obj.has("timestampNormalized")) obj.optBoolean("timestampNormalized") else null,
          warningCode = obj.optString("warningCode").ifBlank { null }
        )

        if (wasInFlight) {
          hadRestartedInFlightTask = true
        }
      }

      if (hadRestartedInFlightTask) {
        persistTaskSnapshot()
      }
    }.onFailure {
      Log.w(tag, "Failed to load task snapshot", it)
    }
  }

  private fun TaskState.toMap(): Map<String, Any?> {
    return mapOf(
      "taskId" to taskId,
      "status" to status,
      "url" to url,
      "state" to state,
      "filename" to filename,
      "filePath" to filePath,
      "isPrivate" to isPrivate,
      "privateVideoId" to privateVideoId,
      "sizeMb" to sizeMb,
      "progressPercent" to progressPercent,
      "speedBytesPerSec" to speedBytesPerSec,
      "errorCode" to errorCode,
      "errorMessage" to errorMessage,
      "normalizedUrl" to normalizedUrl,
      "preflightWarning" to preflightWarning,
      "preflightStrategy" to preflightStrategy,
      "downloadStrategy" to downloadStrategy,
      "extractorKey" to extractorKey,
      "formatSelector" to formatSelector,
      "attemptTrace" to attemptTrace,
      "toolOutput" to toolOutput,
      "preflightBudgetSec" to preflightBudgetSec,
      "preflightElapsedMs" to preflightElapsedMs,
      "preflightAttemptLimit" to preflightAttemptLimit,
      "staticMediaCandidateCount" to staticMediaCandidateCount,
      "estimatedSizeMb" to estimatedSizeMb,
      "timestampNormalized" to timestampNormalized,
      "warningCode" to warningCode
    )
  }

  companion object {
    @Volatile
    private var activeModule: LocalDownloaderModule? = null

    @Volatile
    private var lastBackgroundServiceError: String? = null

    @Volatile
    private var lastQuickReasonFallback: String? = null

    private val pendingQuickRequests: ArrayDeque<PendingQuickRequest> = ArrayDeque()

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"

    private const val COOKIE_KEY_ALIAS = "arsivinyo.local.cookies.v1"
    private const val PRIVATE_VAULT_KEY_ALIAS_V1 = "arsivinyo.local.private.v1"
    private const val PRIVATE_VAULT_MASTER_KEY_ALIAS_V2 = "arsivinyo.local.private.master.v2"
    private const val COOKIE_STORE_VERSION = "v1"
    private const val PRIVATE_STORE_VERSION_V1 = "v1"
    private const val PRIVATE_STORE_VERSION_V2 = "v2"
    private const val PRIVATE_STORE_VERSION_V3 = "v3"
    private const val PRIVATE_STORE_VERSION_V4 = "v4"
    private const val PRIVATE_DEFAULT_CIPHER_VERSION = PRIVATE_STORE_VERSION_V4
    private const val PRIVATE_VAULT_THUMBS_DIRNAME = "thumbs"
    // 12-color Material-derived palette used for auto-assigning tag colors round-robin.
    // Picked for sufficient contrast on both light and dark surfaces.
    private val TAG_COLOR_PALETTE: List<String> = listOf(
      "#EF5350", "#EC407A", "#AB47BC", "#5C6BC0",
      "#42A5F5", "#26C6DA", "#26A69A", "#66BB6A",
      "#9CCC65", "#FFCA28", "#FFA726", "#8D6E63",
    )
    private val HEX_COLOR_REGEX = Regex("^#[A-Fa-f0-9]{6}$")
    private const val TAG_NAME_MAX_LENGTH = 64
    private const val FOLDER_NAME_MAX_LENGTH = 64
    private const val PRIVATE_VAULT_KEYS_DIRNAME = "keys"
    private const val COOKIE_MIGRATION_MARKER_FILENAME = ".migration_complete"
    private const val SECURE_COOKIES_DIRNAME = "cookies_secure"
    private const val PREFS_NAME = "local_downloader_prefs"
    private const val PREF_PRIVATE_MODE_ENABLED = "private_mode_enabled"
    private const val PREF_AUDIO_MODE_ENABLED = "audio_mode_enabled"
    private const val PREF_AUDIO_FORMAT = "audio_format"
    private const val PREF_AUTO_PRESETS = "auto_presets"
    private const val PRESET_CANCEL_DIRNAME = "audio_preset_cancel_flags"
    private const val PRESET_QUEUE_FILENAME = "audio_preset_queue.json"
    private const val PRESET_PROGRESS_DIRNAME = "audio_preset_progress"

    /**
     * Where a downloaded audio file waits while presets are applied to it.
     *
     * Under `filesDir`, not the cache: a preset batch survives process death and resumes
     * from a persisted queue, and the OS may evict the cache in between. A batch that
     * came back to find its source gone would fail every job.
     */
    private const val AUDIO_STAGING_DIRNAME = "audio_staging"
    private const val PRESET_PROGRESS_POLL_MS = 400L
    private const val PREF_STICKY_NOTIFICATION_ENABLED = "sticky_notification_enabled"
    private const val PRIVATE_VAULT_DIRNAME = "private_vault"
    private const val PRIVATE_VAULT_OBJECTS_DIRNAME = "objects"
    private const val PRIVATE_VAULT_INDEX_FILENAME = "index.json"

    /**
     * The encrypted listing. A new name rather than ciphertext written over index.json: an
     * older build running against this data directory finds no index.json, makes its own
     * empty one, and leaves this untouched — so a downgrade is confusing rather than fatal.
     */
    private const val PRIVATE_VAULT_INDEX_V2_FILENAME = "index.v2.enc"
    /** Beside the vault's listing, so whatever removes the vault removes this with it. */
    private const val MEME_IMPORT_FAILED = "MEME_IMPORT_FAILED"
    private const val MEME_IMPORT_CANCELLED = "MEME_IMPORT_PICK_CANCELLED"
    /** Below the platform's own ceiling (MediaStore.getPickImagesMaxLimit, 100 on stock builds). */
    private const val MEME_PICK_LIMIT = 50
    private const val MEME_PRIVATE_INDEX_FILENAME = "memes.v1.enc"
    private val MEME_PRIVATE_INFO = "arsivinyo/key/v1/memes-private-index".toByteArray(Charsets.UTF_8)
    private const val PRIVATE_PLAYBACK_CACHE_DIRNAME = "private_playback"
    private const val PRIVATE_EXPORT_CACHE_DIRNAME = "private_export"
    private const val PRIVATE_IMPORT_CACHE_DIRNAME = "private_import"
    // Scratch space for restores. Lives in cacheDir so the OS can reclaim it if a
    // crash strands anything; the importer also clears it either side of a run.
    private const val BACKUP_STAGING_DIRNAME = "backup_staging"
    private const val BACKUP_EXPORT_MARKER_FILENAME = "backup_export_in_flight.txt"
    /** Comfortably under Android's notification update limit of roughly ten per second. */
    private const val BACKUP_NOTIFICATION_MIN_INTERVAL_MS = 500L
    private const val BACKUP_MODE_EXPORT = "exporting"
    private const val BACKUP_MODE_RESTORE = "restoring"
    private const val PRIVATE_VAULT_FEATURE_FLAG = true
    private const val PRIVATE_STREAM_BUFFER_BYTES = 1024 * 1024
    private const val PRIVATE_LOG_PROGRESS_STEP_BYTES = 25L * 1024L * 1024L
    private const val PRIVATE_MIN_FREE_SPACE_MARGIN_BYTES = 32L * 1024L * 1024L
    private const val PRIVATE_DEK_BYTES = 32
    private const val PRIVATE_MAC_KEY_BYTES = 32
    private const val PRIVATE_KEY_MATERIAL_BYTES = PRIVATE_DEK_BYTES + PRIVATE_MAC_KEY_BYTES
    private const val PRIVATE_HMAC_TAG_BYTES = 32
    private const val PRIVATE_CTR_IV_BYTES = 16
    private const val PRIVATE_GCM_IV_BYTES = 12
    private const val PRIVATE_GCM_TAG_BITS = 128
    private const val PRIVATE_MAX_WRAPPED_DEK_BYTES = 4096
    private val PRIVATE_VAULT_V3_MAGIC = byteArrayOf('P'.code.toByte(), 'V'.code.toByte(), 'L'.code.toByte(), '3'.code.toByte())
    private val PRIVATE_VAULT_V2_MAGIC = byteArrayOf('P'.code.toByte(), 'V'.code.toByte(), 'L'.code.toByte(), 'T'.code.toByte())
    private const val PRIVATE_VAULT_FORMAT_VERSION_V3: Byte = 3
    private const val PRIVATE_VAULT_FORMAT_VERSION_V2: Byte = 2
    private const val PRIVATE_VAULT_ALG_AES_CTR: Byte = 2
    private const val PRIVATE_VAULT_ALG_AES_GCM: Byte = 1
    private const val PRIVATE_VAULT_ALG_HMAC_SHA256: Byte = 3
    private const val CUSTOM_COOKIES_DIRNAME = "custom"
    private const val CUSTOM_PROFILES_DIRNAME = "profiles"
    private const val CUSTOM_DOMAINS_DIRNAME = "domains"
    private const val CUSTOM_INDEX_FILENAME = "index.json"
    private const val LEGACY_COOKIES_DIRNAME = "cookies"
    private const val RUNTIME_COOKIE_DIRNAME = "cookie_runtime"
    private const val DISABLED_COOKIES_DIRNAME = "cookies_disabled"
    private const val DEFAULT_HTTP_USER_AGENT =
      "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Mobile Safari/537.36"
    private const val DEFAULT_MAX_FILE_SIZE_MB = 0
    private const val TASK_SNAPSHOT_FILENAME = "local_downloader_tasks.json"
    private const val FAILURE_LOG_FILENAME = "download_failure_logs.json"
    private const val DOWNLOAD_PROGRESS_DIRNAME = "local_download_progress"
    private const val YT_DLP_OVERRIDE_DIRNAME = "yt-dlp-overrides"
    private const val YT_DLP_UPDATE_CACHE_DIRNAME = "yt-dlp-update"
    private const val YT_DLP_MANIFEST_FILENAME = "manifest.json"
    private const val YT_DLP_PYPI_JSON_URL = "https://pypi.org/pypi/yt-dlp/json"
    private const val YT_DLP_MAX_WHEEL_BYTES = 50L * 1024L * 1024L
    private const val YT_DLP_MIN_FREE_SPACE_BYTES = 100L * 1024L * 1024L
    private const val YT_DLP_UPDATE_CONNECT_TIMEOUT_MS = 15_000
    private const val YT_DLP_UPDATE_READ_TIMEOUT_MS = 45_000
    private const val DOWNLOAD_PROGRESS_POLL_MS = 400L
    private const val DEFAULT_COOKIE_PROFILE_FILENAME = ".default_profile"
    private const val REQUEST_CODE_NOTIFICATIONS = 4491
    /**
     * The runaway guard on how many downloads may exist at once.
     *
     * Not a capacity limit: the stage gates decide how much actually runs, and a download
     * over the limit simply waits at one. This only stops a share-sheet or clipboard loop
     * from creating jobs without bound, and sits far above anything a person would start
     * by hand. It replaced a limit of three, which rejected a fourth shared link for no
     * reason the user could act on.
     */
    internal const val MAX_QUEUED_DOWNLOADS = DownloadStages.MAX_QUEUED

    private const val MAX_PENDING_QUICK_REQUESTS = MAX_QUEUED_DOWNLOADS
    private const val MAX_ERROR_LOGS = 20
    private const val MAX_DOWNLOAD_FAILURE_LOGS = 50
    private const val MAX_FAILURE_LOG_MESSAGE_CHARS = 30_000
    private const val MAX_FAILURE_LOG_TOOL_OUTPUT_CHARS = 12_000
    private const val MAX_FAILURE_LOG_ATTEMPTS = 80
    private const val QUICK_DEDUP_WINDOW_MS = 20_000L
    private const val PRIVATE_IMPORT_PICK_TIMEOUT_SECONDS = 180L
    private const val PRIVATE_PUBLIC_COPY_RELATIVE_PATH = "DCIM/Arsivinyo"
    private const val MB_IN_BYTES = 1024.0 * 1024.0
    private val SUPPORTED_PLATFORMS = setOf("youtube", "instagram", "facebook", "twitter", "reddit", "tiktok")
    private val PLATFORM_HOSTS = mapOf(
      "youtube" to listOf("youtube.com", "youtu.be"),
      "instagram" to listOf("instagram.com"),
      "facebook" to listOf("facebook.com", "fb.watch"),
      "twitter" to listOf("twitter.com", "x.com"),
      "reddit" to listOf("reddit.com", "v.redd.it"),
      "tiktok" to listOf("tiktok.com", "vm.tiktok.com")
    )
    private val RETRYABLE_COOKIE_FAILURE_CODES = setOf("PREFLIGHT_FAILED", "DOWNLOAD_FAILED", "INTERNAL_ERROR")
    private val STRICT_COOKIE_PLATFORMS = setOf("instagram", "facebook", "tiktok", "reddit")
    private val IN_FLIGHT_STATUSES = setOf("PENDING", "STARTED", "PROGRESS")
    private val SUPPORTED_FFMPEG_ABIS = listOf("arm64-v8a", "x86_64")

    fun onNotificationCancelAction(context: Context) {
      activeModule?.cancelFromNotificationAction() ?: run {
        DownloadNotificationController.stop(context)
      }
    }

    fun onNotificationQuickAction(context: Context) {
      launchQuickCaptureActivity(context)
    }

    fun onNotificationTogglePrivateMode(context: Context) {
      val module = activeModule
      if (module != null) {
        runCatching {
          module.setPrivateModeEnabledInternal(!module.privateModeEnabled)
        }.onFailure {
          reportQuickActionReason("PRIVATE_MODE_UNAVAILABLE")
        }
        return
      }

      if (!PRIVATE_VAULT_FEATURE_FLAG) {
        reportQuickActionReason("PRIVATE_MODE_UNAVAILABLE")
        return
      }

      val current = isPrivateModeEnabledPersisted(context)
      val next = !current
      if (next && !isPrivateAuthAvailableStatic(context)) {
        reportQuickActionReason("PRIVATE_MODE_UNAVAILABLE")
        return
      }
      persistPrivateModeEnabled(context, next)
      // Private and audio modes are mutually exclusive.
      if (next) persistAudioModeEnabled(context, false)
      DownloadNotificationController.startOrUpdate(
        context,
        BackgroundNotificationState(
          activeTaskId = null,
          phase = "idle",
          message = context.getString(if (next) R.string.ldl_msg_private_enabled else R.string.ldl_msg_private_disabled),
          progressPercent = null,
          queueSize = pendingQuickRequestsSnapshot().size,
          privateModeEnabled = next,
          audioModeEnabled = if (next) false else isAudioModeEnabledPersisted(context),
          pinned = isStickyNotificationEnabledPersisted(context)
        )
      )
    }

    fun onNotificationToggleAudioMode(context: Context) {
      val module = activeModule
      if (module != null) {
        runCatching { module.setAudioModeEnabledInternal(!module.audioModeEnabled) }
        return
      }

      val next = !isAudioModeEnabledPersisted(context)
      persistAudioModeEnabled(context, next)
      // Audio mode forces public output, so it clears private mode.
      if (next) persistPrivateModeEnabled(context, false)
      DownloadNotificationController.startOrUpdate(
        context,
        BackgroundNotificationState(
          activeTaskId = null,
          phase = "idle",
          message = context.getString(if (next) R.string.ldl_msg_audio_enabled else R.string.ldl_msg_audio_disabled),
          progressPercent = null,
          queueSize = pendingQuickRequestsSnapshot().size,
          privateModeEnabled = if (next) false else isPrivateModeEnabledPersisted(context),
          audioModeEnabled = next,
          pinned = isStickyNotificationEnabledPersisted(context)
        )
      )
    }

    fun launchQuickCaptureActivity(context: Context) {
      val intent = Intent(context, QuickDownloadCaptureActivity::class.java).apply {
        putExtra(QuickDownloadCaptureActivity.EXTRA_AUTOSTART, true)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
      }
      runCatching {
        context.startActivity(intent)
      }.onFailure {
        reportQuickActionReason("QUICK_DOWNLOAD_REJECTED")
      }
    }

    fun onQuickUrlCaptured(context: Context, rawUrl: String, captureMode: String): Map<String, Any?> {
      if (!hasNotificationPermission(context)) {
        reportQuickActionReason("PERMISSION_REQUIRED")
        return mapOf("accepted" to false, "reason" to "PERMISSION_REQUIRED", "captureMode" to captureMode)
      }

      val audioModePersisted = isAudioModeEnabledPersisted(context)
      val selectedVisibility = when {
        audioModePersisted -> "public"
        isPrivateModeEnabledPersisted(context) -> "private"
        else -> "public"
      }
      val module = activeModule
      if (module != null) {
        return runCatching {
          module.startQuickDownloadWithUrl(rawUrl, captureMode, selectedVisibility)
        }.getOrElse {
          reportQuickActionReason("QUICK_DOWNLOAD_REJECTED")
          mapOf("accepted" to false, "reason" to "QUICK_DOWNLOAD_REJECTED", "captureMode" to captureMode)
        }
      }

      val normalized = normalizeQuickUrl(rawUrl)
        ?: return mapOf("accepted" to false, "reason" to "INVALID_QUICK_URL", "captureMode" to captureMode)

      val queueState = synchronized(pendingQuickRequests) {
        val duplicate = pendingQuickRequests.any { it.url == normalized }
        if (duplicate) {
          return@synchronized Pair(false, pendingQuickRequests.size)
        }
        if (pendingQuickRequests.size >= MAX_PENDING_QUICK_REQUESTS) {
          return@synchronized Pair(false, pendingQuickRequests.size)
        }

        pendingQuickRequests.addLast(PendingQuickRequest(normalized, captureMode, selectedVisibility, System.currentTimeMillis()))
        Pair(true, pendingQuickRequests.size)
      }

      val accepted = queueState.first
      val queueSize = queueState.second
      if (!accepted) {
        val reason = if (queueSize >= MAX_PENDING_QUICK_REQUESTS) "QUEUE_FULL" else "QUICK_DOWNLOAD_REJECTED"
        reportQuickActionReason(reason)
        return mapOf(
          "accepted" to false,
          "reason" to reason,
          "captureMode" to captureMode,
          "visibility" to selectedVisibility,
          "queueSize" to queueSize,
          "queueMax" to MAX_PENDING_QUICK_REQUESTS
        )
      }

      reportQuickActionReason(null)
      DownloadNotificationController.startOrUpdate(
        context,
        BackgroundNotificationState(
          activeTaskId = null,
          phase = "starting",
          message = if (queueSize > 1) "Queued ($queueSize/$MAX_PENDING_QUICK_REQUESTS)" else "Preparing quick download",
          progressPercent = null,
          queueSize = queueSize,
          privateModeEnabled = selectedVisibility == "private",
          audioModeEnabled = audioModePersisted,
          pinned = isStickyNotificationEnabledPersisted(context)
        )
      )
      return mapOf(
        "accepted" to true,
        "queueSize" to queueSize,
        "queueMax" to MAX_PENDING_QUICK_REQUESTS,
        "resolvedUrl" to normalized,
        "visibility" to selectedVisibility,
        "captureMode" to captureMode
      )
    }

    fun reportQuickActionReason(reason: String?) {
      lastQuickReasonFallback = reason
      activeModule?.reportQuickActionReason(reason)
    }

    fun quickReasonToMessage(reason: String?): String {
      return when (reason) {
        "PERMISSION_REQUIRED" -> "Notification permission required"
        "NO_CLIPBOARD_URL" -> "Clipboard URL not found"
        "INVALID_QUICK_URL" -> "URL is invalid"
        "QUEUE_FULL" -> "Queue full"
        "QUICK_CAPTURE_CANCELLED" -> "Quick capture cancelled"
        "QUICK_DOWNLOAD_REJECTED" -> "Quick download rejected"
        "PRIVATE_MODE_UNAVAILABLE" -> "Private mode unavailable on this device"
        else -> "Try another URL"
      }
    }

    fun peekClipboardUrl(context: Context): String? {
      return activeModule?.readUrlFromClipboard(context) ?: run {
        val manager = context.getSystemService(ClipboardManager::class.java) ?: return null
        val item = manager.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0) ?: return null
        val uriValue = item.uri?.toString()?.trim()?.takeIf { it.isNotBlank() }
        if (!uriValue.isNullOrBlank()) {
          normalizeQuickUrl(uriValue)?.let { return it }
        }
        val htmlText = item.htmlText?.toString()?.trim()?.takeIf { it.isNotBlank() }
        if (!htmlText.isNullOrBlank()) {
          normalizeQuickUrl(htmlText)?.let { return it }
        }
        val text = item.coerceToText(context)?.toString()?.trim() ?: return null
        normalizeQuickUrl(text)
      }
    }

    private val explicitHttpUrlRegex = Regex("""(?i)\bhttps?://[^\s<>"']+""")
    private val domainLikeUrlRegex = Regex(
      """(?i)\b(?:www\.)?[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+(?:/[^\s<>"']*)?"""
    )
    private val invisibleCharsRegex = Regex("""[\u200B\u200C\u200D\u2060\uFEFF\u00A0]""")

    private fun trimUrlCandidate(raw: String): String {
      var value = raw.trim()
      if (value.isEmpty()) return value
      value = value.trim('"', '\'', '`', '(', ')', '[', ']', '{', '}', '<', '>')
      while (value.isNotEmpty() && value.last() in listOf('.', ',', ';', ':', '!', '?', ')', ']', '}', '>')) {
        value = value.dropLast(1)
      }
      return value.trim()
    }

    private fun cleanClipboardText(raw: String?): String? {
      val value = raw ?: return null
      val cleaned = value
        .replace(invisibleCharsRegex, "")
        .replace("\u0000", "")
        .trim()
      return cleaned.ifBlank { null }
    }

    private fun parseHttpCandidate(candidate: String): String? {
      val cleaned = trimUrlCandidate(candidate)
      if (cleaned.isBlank()) return null
      val withScheme = if (cleaned.contains("://")) cleaned else "https://$cleaned"
      return runCatching {
        val parsed = URI(withScheme)
        val scheme = parsed.scheme?.lowercase() ?: return@runCatching null
        if (scheme != "http" && scheme != "https") {
          return@runCatching null
        }
        val host = parsed.host?.trim()
        if (host.isNullOrBlank()) {
          return@runCatching null
        }
        parsed.toString()
      }.getOrNull()
    }

    private fun normalizeQuickUrl(raw: String?): String? {
      val value = cleanClipboardText(raw) ?: return null

      parseHttpCandidate(value)?.let { return it }

      explicitHttpUrlRegex.find(value)?.value?.let { found ->
        parseHttpCandidate(found)?.let { return it }
      }

      domainLikeUrlRegex.find(value)?.value?.let { found ->
        parseHttpCandidate(found)?.let { return it }
      }

      return null
    }

    private fun hasNotificationPermission(context: Context): Boolean {
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        return true
      }
      return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    private fun isPrivateModeEnabledPersisted(context: Context): Boolean {
      if (!PRIVATE_VAULT_FEATURE_FLAG) {
        return false
      }
      return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getBoolean(PREF_PRIVATE_MODE_ENABLED, false)
    }

    private fun isAudioModeEnabledPersisted(context: Context): Boolean {
      return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getBoolean(PREF_AUDIO_MODE_ENABLED, false)
    }

    private fun audioFormatPersisted(context: Context): String {
      return normalizeAudioFormat(
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
          .getString(PREF_AUDIO_FORMAT, null)
      )
    }

    /** Anything we cannot actually encode falls back to the lossless default. */
    fun normalizeAudioFormat(format: String?): String {
      val normalized = format?.trim()?.lowercase().orEmpty()
      return if (normalized in SUPPORTED_AUDIO_FORMATS) normalized else DEFAULT_AUDIO_FORMAT
    }


    private fun isStickyNotificationEnabledPersisted(context: Context): Boolean {
      return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getBoolean(PREF_STICKY_NOTIFICATION_ENABLED, false)
    }

    private fun persistPrivateModeEnabled(context: Context, enabled: Boolean) {
      context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(PREF_PRIVATE_MODE_ENABLED, enabled && PRIVATE_VAULT_FEATURE_FLAG)
        .apply()
    }

    private fun persistAudioModeEnabled(context: Context, enabled: Boolean) {
      context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(PREF_AUDIO_MODE_ENABLED, enabled)
        .apply()
    }

    private fun persistAudioFormat(context: Context, format: String) {
      context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putString(PREF_AUDIO_FORMAT, format)
        .apply()
    }


    private fun persistStickyNotificationEnabled(context: Context, enabled: Boolean) {
      context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(PREF_STICKY_NOTIFICATION_ENABLED, enabled)
        .apply()
    }

    private fun isPrivateAuthAvailableStatic(context: Context): Boolean {
      val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
      if (keyguard?.isDeviceSecure != true) {
        return false
      }
      val manager = BiometricManager.from(context)
      val canAuth = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
      } else {
        manager.canAuthenticate()
      }
      return canAuth == BiometricManager.BIOMETRIC_SUCCESS || Build.VERSION.SDK_INT < Build.VERSION_CODES.R
    }

    fun pendingQuickRequestsSnapshot(): List<PendingQuickRequest> {
      return synchronized(pendingQuickRequests) { pendingQuickRequests.toList() }
    }

    fun clearPendingQuickRequests() {
      synchronized(pendingQuickRequests) {
        pendingQuickRequests.clear()
      }
    }

    fun dequeuePendingQuickRequest(): PendingQuickRequest? {
      return synchronized(pendingQuickRequests) {
        if (pendingQuickRequests.isEmpty()) null else pendingQuickRequests.removeFirst()
      }
    }

    fun queuePendingQuickRequest(url: String, captureMode: String, visibility: String): Boolean {
      return synchronized(pendingQuickRequests) {
        if (pendingQuickRequests.size >= MAX_PENDING_QUICK_REQUESTS) {
          false
        } else {
          pendingQuickRequests.addLast(PendingQuickRequest(url, captureMode, visibility, System.currentTimeMillis()))
          true
        }
      }
    }

    fun onNotificationRemoteUrl(context: Context, rawUrl: String): Map<String, Any?> {
      return onQuickUrlCaptured(context, rawUrl, "manual")
    }

    fun onNotificationQuickActionFallback(context: Context) {
      activeModule?.quickFromNotificationAction() ?: run {
        DownloadNotificationController.startOrUpdate(
          context,
          BackgroundNotificationState(
            activeTaskId = null,
            phase = "error",
            message = "App is not ready",
            progressPercent = null,
            queueSize = 0,
            privateModeEnabled = isPrivateModeEnabledPersisted(context),
            audioModeEnabled = isAudioModeEnabledPersisted(context),
            pinned = isStickyNotificationEnabledPersisted(context)
          )
        )
      }
    }

    fun reportBackgroundServiceStartFailure(message: String) {
      lastBackgroundServiceError = message
      activeModule?.addError("BACKGROUND_SERVICE_START_FAILED: $message")
      activeModule?.emitBackgroundStateChanged()
    }
  }
}
