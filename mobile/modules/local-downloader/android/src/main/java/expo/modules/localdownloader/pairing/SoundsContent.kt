package expo.modules.localdownloader.pairing

import android.content.Context
import android.net.Uri
import android.util.Log
import expo.modules.localdownloader.sounds.SoundsStore
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * What a paired device may reach on this phone: the music library, and nothing else. A peer
 * may also hand over a meme, which goes to the meme collection; it cannot list or fetch them.
 *
 * The vault is absent deliberately — it is confined to the device that made it, and a
 * `.avsbck` backup is the only supported way to move its contents. Settings, playlists and
 * library management are absent too: `download` hands over a URL, and that is the whole of
 * what a peer can ask this phone to *do*.
 *
 * Reading goes through the content resolver rather than a file path. The library lives in
 * MediaStore under the owner model, with no storage permission held, so the app can open
 * its own entries by URI and has no usable path to give.
 *
 * Writing lands in app-private storage first. Only once a transfer has verified is the
 * file handed to [SoundsStore], so a partial or corrupted transfer never becomes a track.
 */
class SoundsContent(
  private val context: Context,
  private val store: SoundsStore,
  /** Called when a peer asks this phone to fetch a URL. Never starts on its own. */
  private val onDownloadRequested: (url: String, mediaKind: String) -> Unit,
  /** Takes a received meme into the collection; the staged file is its to keep or delete. */
  private val onMemeReceived: (file: File, meme: JSONObject?) -> Unit = { file, _ -> file.delete() },
) : PeerContent {

  /** Told after a track is added or joins a playlist, so the Music screen can reload. */
  var onLibraryChanged: (() -> Unit)? = null

  /** Set by the coordinator: sends a playlist through its queue, as Send does. */
  var onPlaylistRequested: ((id: String, fingerprint: String) -> Unit)? = null

  override fun sendPlaylist(id: String, toFingerprint: String): Boolean {
    val exists = runCatching { store.listPlaylists() }.getOrNull().orEmpty()
      .any { it["id"] == id && (it["songIds"] as? List<*>).orEmpty().isNotEmpty() }
    if (exists) onPlaylistRequested?.invoke(id, toFingerprint)
    return exists
  }

  override fun listing(kind: String): JSONArray {
    val items = JSONArray()
    if (kind == "playlists" && store.isSupported()) {
      // Only ones with something in them: an empty playlist has nothing to send.
      runCatching { store.listPlaylists() }.getOrNull().orEmpty().forEach { playlist ->
        val count = (playlist["songIds"] as? List<*>)?.size ?: 0
        if (count == 0) return@forEach
        val favorites = playlist["system"] == true
        items.put(JSONObject()
          .put("id", playlist["id"] as? String ?: return@forEach)
          .put("name", if (favorites) "" else playlist["name"] as? String ?: "")
          .put("favorites", favorites)
          .put("count", count))
      }
      return items
    }
    // Only music is listed. "backups" is accepted as a `put` kind, but this device does
    // not offer its own backups for browsing: a backup is a deliberate export, not
    // something a peer helps itself to.
    if (kind != "music" || !store.isSupported()) return items

    val songs = runCatching {
      @Suppress("UNCHECKED_CAST")
      store.listLibrary()["songs"] as? List<Map<String, Any?>>
    }.getOrNull().orEmpty()

    for (song in songs) {
      items.put(JSONObject()
        .put("id", song["id"] as? String ?: continue)
        .put("title", song["title"] as? String ?: "")
        .put("artist", song["artist"] as? String ?: JSONObject.NULL)
        .put("durationSec", (song["durationSec"] as? Double) ?: 0.0)
        .put("sizeBytes", (song["sizeBytes"] as? Long) ?: 0L))
    }
    return items
  }

  override fun openItem(id: String): ItemSource? {
    if (id.isEmpty() || !store.isSupported()) return null
    // The id is matched against the library's own entries, never turned into a path, so a
    // peer cannot name something the library does not hold.
    val song = runCatching { store.findSong(id) }.getOrNull() ?: return null
    val uri = (song["contentUri"] as? String)?.takeIf { it.isNotEmpty() } ?: return null
    val name = (song["fileName"] as? String)?.takeIf { it.isNotEmpty() }
      ?: return null
    val size = (song["sizeBytes"] as? Long) ?: 0L
    if (size <= 0L) return null

    // The cover lives beside the file, not inside it, so it is sent with the track or lost.
    val artwork = (song["thumbnailPath"] as? String)?.let(::File)?.takeIf { it.isFile }
    return ItemSource(name, size, artwork,
      title = song["title"] as? String, artist = (song["artist"] as? String)?.takeUnless { it == android.provider.MediaStore.UNKNOWN_STRING }) {
      context.contentResolver.openInputStream(Uri.parse(uri))
        ?: throw java.io.IOException("the library entry could not be opened")
    }
  }

  override fun destinationFor(name: String, kind: String): String? {
    val bare = safeName(name)
    if (bare.isEmpty()) return null

    val dir = File(context.filesDir, INCOMING_DIRNAME)
    if (!dir.isDirectory && !dir.mkdirs()) return null

    // Never overwrite. A second file of the same name gets its own, and the staging
    // directory is app-private so nothing here is visible to another app.
    var candidate = File(dir, bare)
    var attempt = 2
    while (candidate.exists() && attempt < 1000) {
      val base = bare.substringBeforeLast('.', bare)
      val extension = bare.substringAfterLast('.', "")
      candidate = File(dir, if (extension.isEmpty()) "$base ($attempt)"
                            else "$base ($attempt).$extension")
      attempt++
    }
    return candidate.path
  }

  override fun existing(sizeBytes: Long, sha256: ByteArray, kind: String): String? {
    if (kind != "music" || !store.isSupported()) return null
    @Suppress("UNCHECKED_CAST")
    val songs = runCatching { store.listLibrary()["songs"] as? List<Map<String, Any?>> }.getOrNull().orEmpty()
    // Only songs of the same size are read and hashed, so a library is not hashed whole for
    // each offer.
    for (song in songs) {
      if ((song["sizeBytes"] as? Number)?.toLong() != sizeBytes) continue
      val uri = (song["contentUri"] as? String)?.takeIf { it.isNotEmpty() } ?: continue
      val digest = runCatching {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        context.contentResolver.openInputStream(Uri.parse(uri))?.use { input ->
          val buffer = ByteArray(1 shl 16)
          while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            md.update(buffer, 0, read)
          }
        } ?: return@runCatching null
        md.digest()
      }.getOrNull() ?: continue
      if (digest.contentEquals(sha256)) return song["id"] as? String
    }
    return null
  }

  override fun reuse(id: String, playlist: PeerPlaylist?) {
    playlist?.let {
      join(id, it)
      onLibraryChanged?.invoke()
    }
  }

  /**
   * A track joins the playlist a peer sent it in: the playlist of that name, made if there
   * is none, or Favorites. At the end, in the order the tracks come.
   */
  private fun join(songId: String, playlist: PeerPlaylist) {
    runCatching {
      if (playlist.favorites) {
        store.setSoundsFavorite(listOf(songId), true)
        return
      }
      val id = store.listPlaylists()
        .firstOrNull { it["system"] != true && it["name"] == playlist.name }?.get("id") as? String
        ?: store.createPlaylist(playlist.name)["id"] as? String
        ?: return
      store.addSongsToPlaylists(listOf(songId), listOf(id))
    }.onFailure { Log.w(TAG, "a received track could not join its playlist: ${it.javaClass.simpleName}") }
  }

  override fun accepted(path: String, kind: String, artworkPath: String?, meme: JSONObject?, playlist: PeerPlaylist?,
                        title: String?, artist: String?) {
    val file = File(path)
    if (!file.isFile) return

    if (kind == "meme") {
      // A meme is not a track. It goes to the collection, its labels merged by name.
      artworkPath?.let { File(it).delete() }
      runCatching { onMemeReceived(file, meme) }.onFailure {
        Log.w(TAG, "a received meme could not be added: ${it.javaClass.simpleName}")
        file.delete()
      }
      return
    }

    if (kind == "backups") {
      // A backup is not a library item. It stays where it landed for the user to import
      // deliberately, with its password, which is the only way vault contents move.
      return
    }

    runCatching {
      // sourceUrl is null: this came from a device, not a download.
      val song = store.registerDownloadedSound(file.path, file.name, null, artworkPath)
      val id = song["id"] as? String
      // The file's own artist first; the sender's where the file has none.
      if (id != null && artist != null) store.fillArtist(id, artist)
      if (playlist != null && id != null) join(id, playlist)
      onLibraryChanged?.invoke()
    }.onFailure {
      Log.w(TAG, "a received track could not be added to the library: ${it.javaClass.simpleName}")
    }
    // Registering copies the bytes into MediaStore, so the staged copy is now a duplicate;
    // the cover has been copied into the sidecar store the same way.
    file.delete()
    artworkPath?.let { File(it).delete() }
  }

  override fun download(url: String, mediaKind: String) {
    // Surfaced, not started. A peer asking this phone to fetch something is a request the
    // user sees, not an instruction the app obeys.
    onDownloadRequested(url, mediaKind)
  }

  /**
   * Reduce whatever the peer called the file to a bare name this side will write.
   *
   * A name is data, not a path. Taking only the last segment handles `../../etc/passwd`
   * and `/etc/passwd` alike, and the separators some platforms accept are removed too.
   */
  private fun safeName(name: String): String {
    var bare = name.substringAfterLast('/').substringAfterLast('\\')
    bare = bare.replace('/', '_').replace('\\', '_')
    // A leading dot hides the file; a name that is only dots is not a name.
    while (bare.startsWith('.')) bare = bare.drop(1)
    return bare.trim()
  }

  private companion object {
    const val TAG = "PairingContent"
    /** App-private, so a transfer in flight is never visible to another app. */
    const val INCOMING_DIRNAME = "pairing_incoming"
  }
}
