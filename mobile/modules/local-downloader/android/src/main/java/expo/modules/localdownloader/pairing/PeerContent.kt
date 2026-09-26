package expo.modules.localdownloader.pairing

import java.io.InputStream
import org.json.JSONArray

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

  /** Items of [kind] — "music" or "backups" — as protocol `listing` entries. */
  fun listing(kind: String): JSONArray

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
   */
  fun accepted(path: String, kind: String, artworkPath: String?, meme: org.json.JSONObject?)

  /** The peer asked this device to fetch a URL itself. */
  fun download(url: String, mediaKind: String)
}
