package expo.modules.localdownloader.pairing

import java.io.InputStream
import org.json.JSONArray

/**
 * The playlist a track is sent as part of (`shared/pairing/PROTOCOL.md`, "put"). The receiver
 * files the track as it would any, then adds it to its own playlist of that name, making it
 * if it has none. Favorites is each device's own, whatever its language calls it.
 */
data class PeerPlaylist(val name: String, val favorites: Boolean = false) {
  fun offer(): org.json.JSONObject =
    if (favorites) org.json.JSONObject().put("favorites", true) else org.json.JSONObject().put("name", name)

  companion object {
    /** What a peer said, checked: a name is trimmed, kept short, and never empty. */
    fun read(value: Any?): PeerPlaylist? {
      val obj = value as? org.json.JSONObject ?: return null
      if (obj.optBoolean("favorites")) return PeerPlaylist("", favorites = true)
      val name = obj.optString("name").trim().take(200)
      return if (name.isEmpty()) null else PeerPlaylist(name)
    }
  }
}

/**
 * Something a peer may fetch: a name, a size, and a way to read the bytes.
 *
 * [open] hands back a *fresh* stream each time, because the bytes are read twice — once to
 * hash them before the offer, once to send them. A single stream would work for neither.
 */
data class ItemSource(
  val name: String,
  val sizeBytes: Long,
  /** The track's cover, sent along with it. The phone keeps covers beside the files. */
  val artwork: java.io.File? = null,
  /** A meme's kind, source and labels by name: `shared/memes/CONTRACT.md`. */
  val meme: org.json.JSONObject? = null,
  /** The playlist it is sent as part of, if any. */
  val playlist: PeerPlaylist? = null,
  /** Where it is in a batch of sends, 1-based, and of how many: the receiver shows "3 of 12". */
  val batch: Pair<Int, Int>? = null,
  /**
   * A track's title and artist as this library has them, for a receiver whose file has none
   * in its tags: a render's file, for one, may carry no artist.
   */
  val title: String? = null,
  val artist: String? = null,
  val open: () -> InputStream,
)

/**
 * What a peer is allowed to see and do on this device.
 *
 * The transport deliberately knows nothing about MediaStore, the music index or where
 * files live. It asks this interface, and the answer is the whole of what a paired device
 * can reach — which makes the boundary reviewable in one place instead of spread through
 * the connection handling. The vault is absent on purpose: it is confined to the device
 * that made it, and a `.avsbck` backup is the only supported way to move its contents.
 *
 * Reading is a stream rather than a path because the phone's music is in MediaStore, which
 * has no usable file path for an app that holds no storage permission. Writing is still a
 * path: a received file lands in app-private storage first and is only handed to the
 * library once it has verified.
 */
interface PeerContent {

  /**
   * Items of [kind] as protocol `listing` entries: "music" for tracks, "playlists" for
   * playlists (id, name, favorites, count), "backups".
   */
  fun listing(kind: String): JSONArray

  /**
   * The peer asks for a whole playlist from `listing("playlists")`: send it to
   * [toFingerprint] as this device's own Send would. False if there is no such playlist.
   */
  fun sendPlaylist(id: String, toFingerprint: String): Boolean = false

  /** The bytes behind an id from [listing], or null if the peer may not have it. */
  fun openItem(id: String): ItemSource?

  /**
   * Where an incoming file should be written, given the name the sender chose. Null
   * refuses the transfer.
   *
   * The implementation, not the sender, decides the final path — a peer must never be
   * able to steer a write by sending a name with a slash or a `..` in it.
   */
  fun destinationFor(name: String, kind: String): String?

  /**
   * A completed file has landed at [path]; take it into the library. [meme] is what the
   * sender said about a meme, unchecked: it is data to merge, never an instruction.
   * [playlist] is the playlist it was sent in; [title] and [artist] are what the sender has
   * for it, used only where the file's own tags say nothing.
   */
  fun accepted(path: String, kind: String, artworkPath: String?, meme: org.json.JSONObject?, playlist: PeerPlaylist?,
               title: String?, artist: String?)

  /** The id of an item already here with exactly these bytes, so it is not sent twice. */
  fun existing(sizeBytes: Long, sha256: ByteArray, kind: String): String? = null

  /** An item already here was offered as part of a playlist: it joins the playlist instead. */
  fun reuse(id: String, playlist: PeerPlaylist?) {}

  /** The peer asked this device to fetch a URL itself. */
  fun download(url: String, mediaKind: String)
}
