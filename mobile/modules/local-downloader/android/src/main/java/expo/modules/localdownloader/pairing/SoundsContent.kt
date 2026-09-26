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

  override fun listing(kind: String): JSONArray {
    val items = JSONArray()
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
    return ItemSource(name, size, artwork) {
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

  override fun accepted(path: String, kind: String, artworkPath: String?, meme: JSONObject?) {
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
      store.registerDownloadedSound(file.path, file.name, null, artworkPath)
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
