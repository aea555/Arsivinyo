package expo.modules.localdownloader.torrent

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import expo.modules.localdownloader.watch.WatchLibrary
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Torrents on the phone (`shared/watch/CONTRACT.md`, "The torrent engine"): one engine
 * session over shared/torrent, its state and its cache in app-private storage, and the rules
 * the contract sets for this device. Nothing here logs a torrent's name or files.
 */
class TorrentService private constructor(private val context: Context) {

  companion object {
    @Volatile private var instance: TorrentService? = null

    /** One per process: downloads go on under the service while the screens come and go. */
    fun get(context: Context): TorrentService =
      instance ?: synchronized(this) { instance ?: TorrentService(context.applicationContext).also { instance = it } }
  }

  data class Settings(
    /** Stop seeding at this ratio; 0 is "never seed". */
    val seedRatio: Double = 1.0,
    /** Seed on mobile data too (off: the contract's default). */
    val seedOnMobileData: Boolean = false,
    val cacheLimitBytes: Long = 4L shl 30,
    /** "Don't show again" on the heads-up about IP addresses. */
    val headsUpDismissed: Boolean = false,
  )

  class Failure(val code: String) : Exception(code)

  private val root = File(context.noBackupFilesDir, "torrents")
  /** Where downloads are fetched to, one folder per torrent, before they are filed. */
  private val downloadsDir = File(root, "downloads")
  private val stateDir = File(root, "state")
  val cacheDir = File(root, "cache")
  private val settingsFile = File(root, "settings.json")
  private val lock = Any()
  private var handle = 0L
  private var serverBase: String? = null
  @Volatile private var onMobileData = false

  // ---- settings ------------------------------------------------------------------------------

  fun settings(): Settings = synchronized(lock) {
    val json = runCatching { JSONObject(settingsFile.readText()) }.getOrNull() ?: return Settings()
    Settings(
      seedRatio = json.optDouble("seedRatio", 1.0),
      seedOnMobileData = json.optBoolean("seedOnMobileData", false),
      cacheLimitBytes = json.optLong("cacheLimitBytes", 4L shl 30),
      headsUpDismissed = json.optBoolean("headsUpDismissed", false),
    )
  }

  fun setSettings(settings: Settings) = synchronized(lock) {
    root.mkdirs()
    settingsFile.writeText(JSONObject()
      .put("seedRatio", settings.seedRatio)
      .put("seedOnMobileData", settings.seedOnMobileData)
      .put("cacheLimitBytes", settings.cacheLimitBytes)
      .put("headsUpDismissed", settings.headsUpDismissed)
      .toString())
    if (handle != 0L) apply(settings)
  }

  /** The app's screens are out of sight. */
  @Volatile var inBackground = false
    set(value) {
      field = value
      synchronized(lock) { if (handle != 0L) apply(settings()) }
    }

  // Uploading stops on mobile data unless allowed, and once the app is in the background
  // with nothing left to download: nothing seeds there (CONTRACT.md, "Seeding").
  private fun uploadAllowed(settings: Settings): Boolean {
    if (onMobileData && !settings.seedOnMobileData) return false
    return !inBackground || summary().downloading > 0
  }

  private fun apply(settings: Settings) {
    TorrentNative.nativeApply(handle, stateDir.path, 0, settings.seedRatio, uploadAllowed(settings), true, 0, 0)
  }

  // ---- the session ---------------------------------------------------------------------------

  /** The session, started on first use; it resumes whatever was there before. */
  fun session(): Long = synchronized(lock) {
    if (handle != 0L) return handle
    if (!TorrentNative.available) throw Failure("TORRENT_UNAVAILABLE")
    stateDir.mkdirs()
    cacheDir.mkdirs()
    watchNetwork()
    val settings = settings()
    handle = TorrentNative.nativeCreate(stateDir.path, 0, settings.seedRatio, uploadAllowed(settings), true, 0, 0)
    if (handle == 0L) throw Failure("TORRENT_UNAVAILABLE")
    removeUnrecorded(handle)
    handle
  }

  /** Saves everything's resume data and stops. */
  fun stop() = synchronized(lock) {
    if (handle != 0L) TorrentNative.nativeDestroy(handle)
    handle = 0L
    serverBase = null
  }

  private var networkWatched = false

  // Mobile data is told apart from Wi-Fi so seeding can stop on it.
  private fun watchNetwork() {
    if (networkWatched) return
    networkWatched = true
    val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return
    connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
      override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
        val cellular = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        if (cellular != onMobileData) {
          onMobileData = cellular
          synchronized(lock) { if (handle != 0L) apply(settings()) }
        }
      }
    })
  }

  /** Whether a VPN appears to be up: any network with the VPN transport (CONTRACT.md). */
  fun vpnAppearsActive(): Boolean {
    val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return false
    @Suppress("DEPRECATION")
    return connectivity.allNetworks.any { network ->
      connectivity.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    }
  }

  // ---- streaming -----------------------------------------------------------------------------

  data class Stream(val id: String, val file: Int, val url: String, val size: Long)

  /**
   * A torrent's file, ready for the player: fetched into the cache (or played from a download
   * already here), its metadata waited for, the cache trimmed to its limit around it.
   */
  fun stream(magnet: String, fileIdx: Int?, filename: String?): Stream {
    val session = session()
    val code = IntArray(1)
    val json = TorrentNative.nativeStream(session, magnet, cacheDir.path, fileIdx ?: -1, filename, 90_000, code)
      ?: throw Failure(if (code[0] == TorrentNative.NO_METADATA) "TORRENT_NO_METADATA" else "TORRENT_FAILED")
    val result = JSONObject(json)
    val id = result.getString("id")
    TorrentNative.nativeCacheTrim(session, cacheDir.path, settings().cacheLimitBytes, id)
    val base = synchronized(lock) {
      serverBase ?: TorrentNative.nativeServerStart(session)?.also { serverBase = it }
    } ?: throw Failure("TORRENT_FAILED")
    val file = result.getInt("file")
    // The file's name at the end, so the player sees its extension.
    val name = result.getString("path").substringAfterLast('/')
    return Stream(id, file, "$base/$id/$file/${android.net.Uri.encode(name)}", result.getLong("size"))
  }

  fun status(): JSONArray = JSONArray(TorrentNative.nativeStatus(session()) ?: "[]")

  // ---- downloads -----------------------------------------------------------------------------

  /**
   * Files a finished file where it belongs. Implemented by the module, which owns MediaStore
   * and the vault. Returns false to try again later (the vault is locked, say).
   */
  interface Taker {
    fun filePublic(file: File, relativePath: String): Boolean
    fun intoVault(file: File, name: String): Boolean
    /** Something changed that the screens and the notification show. */
    fun changed()
  }

  @Volatile var taker: Taker? = null
  @Volatile private var library: WatchLibrary? = null
  private var worker: Thread? = null

  /** The encrypted library the records live in; set once by the module. */
  fun attach(library: WatchLibrary) {
    this.library = library
  }

  private fun records(): WatchLibrary = library ?: throw Failure("TORRENT_UNAVAILABLE")

  /**
   * Starts a download from a magnet or a .torrent's bytes. It fetches its metadata and waits
   * for the files to be chosen; until then nothing else is downloaded.
   */
  fun add(magnet: String?, torrent: ByteArray?): String {
    val session = session()
    val id = (if (magnet != null) TorrentNative.nativeAddMagnet(session, magnet, downloadsDir.path, false)
              else TorrentNative.nativeAddTorrent(session, torrent ?: throw Failure("TORRENT_BAD_INPUT"), downloadsDir.path, false))
      ?: throw Failure("TORRENT_BAD_INPUT")
    try {
      if (records().torrent(id) == null) {
        // Held from the start: nothing but its file list until the files are chosen.
        TorrentNative.nativeHold(session, id, true)
        records().putTorrent(WatchLibrary.Torrent(id, "", emptyList(), "public", System.currentTimeMillis()))
        holdUntilChosen.add(id)
      }
    } catch (error: Throwable) {
      // Never a torrent in the engine without its record: it would download unseen.
      TorrentNative.nativeRemove(session, id, true)
      throw error
    }
    startWorker()
    return id
  }

  private val holdUntilChosen = java.util.Collections.synchronizedSet(mutableSetOf<String>())

  /** The files of a torrent, once known: [{index, path, size}]; null while fetching metadata. */
  fun files(id: String): JSONArray? = TorrentNative.nativeFiles(session(), id)?.let(::JSONArray)

  /** The user's choice: which files, and where they land. */
  fun choose(id: String, wanted: List<Int>, destination: String) {
    val session = session()
    val files = files(id) ?: throw Failure("TORRENT_NO_METADATA")
    val count = (0 until files.length()).maxOf { files.getJSONObject(it).getInt("index") } + 1
    holdUntilChosen.remove(id)
    TorrentNative.nativeSetPriorities(session, id, ByteArray(count) { if (it in wanted) 4 else 0 })
    TorrentNative.nativeHold(session, id, false)
    TorrentNative.nativeResume(session, id)
    val record = records().torrent(id) ?: WatchLibrary.Torrent(id, "", emptyList(), destination, System.currentTimeMillis())
    records().putTorrent(record.copy(name = nameOf(id) ?: record.name, wanted = wanted, destination = destination,
      state = "downloading"))
    taker?.changed()
  }

  fun pause(id: String) = TorrentNative.nativePause(session(), id).also { taker?.changed() }

  fun resume(id: String) = TorrentNative.nativeResume(session(), id).also { taker?.changed() }

  /** Removes a download; with `deleteFiles`, what it fetched too (filed copies stay). */
  fun remove(id: String, deleteFiles: Boolean) {
    TorrentNative.nativeRemove(session(), id, deleteFiles)
    records().removeTorrent(id)
    taker?.changed()
  }

  private fun engineState(id: String): JSONObject? {
    val all = status()
    return (0 until all.length()).map { all.getJSONObject(it) }.firstOrNull { it.optString("id") == id }
  }

  private fun nameOf(id: String): String? = engineState(id)?.optString("name")?.ifBlank { null }

  /** Downloads with their records and the engine's state, for the screens. */
  fun downloads(): List<Pair<WatchLibrary.Torrent, JSONObject?>> {
    val engine = if (handle == 0L && records().torrents().isEmpty()) JSONArray() else status()
    val byId = (0 until engine.length()).associate { engine.getJSONObject(it).let { o -> o.optString("id") to o } }
    return records().torrents().sortedByDescending { it.addedAt }.map { it to byId[it.infoHash] }
  }

  /** How many are downloading and how far along they are together, for the notification. */
  data class Summary(val downloading: Int, val percent: Double?)

  fun summary(): Summary {
    if (handle == 0L) return Summary(0, null)
    val active = downloads().filter { (record, engine) ->
      record.state == "downloading" && engine != null && !engine.optBoolean("paused") && !engine.optBoolean("finished")
    }
    if (active.isEmpty()) return Summary(0, null)
    val done = active.sumOf { it.second!!.optDouble("done") }
    val wanted = active.sumOf { it.second!!.optDouble("wanted") }
    return Summary(active.size, if (wanted > 0) done * 100 / wanted else null)
  }

  /** Starts the worker if there is anything to do; downloads resume as soon as it runs. */
  fun startWorker() = synchronized(lock) {
    if (worker?.isAlive == true || library == null) return
    if (records().torrents().isEmpty()) return
    session()
    worker = Thread({ work() }, "torrents").apply {
      priority = Thread.NORM_PRIORITY - 1
      isDaemon = true
      start()
    }
  }

  // Every two seconds: hold back what is still to be chosen, file what has finished, and let
  // go of torrents that are done.
  private fun work() {
    while (true) {
      val records = runCatching { records().torrents() }.getOrDefault(emptyList())
      if (records.isEmpty()) break
      val session = runCatching { session() }.getOrNull() ?: break
      for (record in records) runCatching { step(session, record) }
      // A download finishing in the background is when seeding has to stop there.
      if (inBackground) synchronized(lock) { if (handle != 0L) apply(settings()) }
      taker?.changed()
      Thread.sleep(2_000)
    }
    synchronized(lock) { worker = null }
  }

  /**
   * A torrent in the downloads folder with no record (an add that failed halfway, a crash
   * between the two) would download unseen: it goes, with what it fetched. Run as a session
   * starts, which is when such a torrent comes back from its resume data.
   */
  private fun removeUnrecorded(session: Long) {
    val records = library?.let { runCatching { it.torrents() }.getOrNull() } ?: return
    val known = records.map { it.infoHash }.toSet()
    val all = JSONArray(TorrentNative.nativeStatus(session) ?: "[]")
    for (i in 0 until all.length()) {
      val torrent = all.getJSONObject(i)
      val id = torrent.optString("id")
      if (id !in known && torrent.optString("savePath") == downloadsDir.path) TorrentNative.nativeRemove(session, id, true)
    }
  }

  /**
   * Held once the files are known, until they are chosen; whether they are known. Held, not
   * paused, nor every file skipped: either of those drops the peers that just sent the file
   * list, and the download would wait for them to be found again.
   */
  private fun holdBack(session: Long, id: String): Boolean {
    files(id) ?: return false
    TorrentNative.nativeHold(session, id, true)
    return true
  }

  private fun step(session: Long, record: WatchLibrary.Torrent) {
    val id = record.infoHash
    if (record.state == "choosing") {
      if (id in holdUntilChosen && holdBack(session, id) && record.name.isEmpty()) {
        nameOf(id)?.let { records().putTorrent(record.copy(name = it)) }
      }
      return
    }
    val progress = TorrentNative.nativeFiles(session, id)?.let { TorrentNative.nativeFileProgress(session, id) }
      ?.let(::JSONArray) ?: return
    var taken = record.taken
    for (i in 0 until progress.length()) {
      val file = progress.getJSONObject(i)
      val index = file.getInt("index")
      if (index !in record.wanted || index in taken || file.getLong("done") < file.getLong("size")) continue
      val path = TorrentNative.nativeFilePath(session, id, index) ?: continue
      val source = File(path)
      val relative = path.removePrefix(downloadsDir.path).trimStart('/')
      val filed = if (record.destination == "private") {
        taker?.intoVault(source, source.name) == true
      } else {
        taker?.filePublic(source, relative) == true
      }
      if (!filed) continue
      taken = taken + index
      if (record.destination == "private") {
        // In the vault now: the plaintext goes, and the torrent stops serving it.
        val count = (0 until progress.length()).maxOf { progress.getJSONObject(it).getInt("index") } + 1
        TorrentNative.nativeSetPriorities(session, id, ByteArray(count) { if (it in record.wanted && it !in taken) 4 else 0 })
        source.delete()
      }
      records().putTorrent(record.copy(taken = taken))
    }
    val engine = engineState(id)
    val allTaken = record.wanted.isNotEmpty() && taken.containsAll(record.wanted)
    if (allTaken && record.state != "done") records().putTorrent(record.copy(taken = taken, state = "done"))
    // Done and no longer seeding: what was fetched goes; the filed copies stay where they are.
    if (allTaken && (record.destination == "private" || engine?.optBoolean("paused") == true)) {
      TorrentNative.nativeRemove(session, id, true)
      records().putTorrent(record.copy(taken = taken, state = "done"))
    }
  }
}
