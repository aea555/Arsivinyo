package expo.modules.localdownloader.pairing

import org.json.JSONArray

/**
 * What a peer is allowed to see and do on this device.
 *
 * The transport deliberately knows nothing about MediaStore, the music index or where
 * files live. It asks this interface, and the answer is the whole of what a paired device
 * can reach — which makes the boundary reviewable in one place instead of spread through
 * the connection handling. The vault is absent on purpose: it is confined to the device
 * that made it, and a `.avsbck` backup is the only supported way to move its contents.
 */
interface PeerContent {

  /** Items of [kind] — "music" or "backups" — as protocol `listing` entries. */
  fun listing(kind: String): JSONArray

  /** The file behind an id from [listing], or null if the peer may not have it. */
  fun pathForItem(id: String): String?

  /**
   * Where an incoming file should be written, given the name the sender chose. Null
   * refuses the transfer.
   *
   * The implementation, not the sender, decides the final path — a peer must never be
   * able to steer a write by sending a name with a slash or a `..` in it.
   */
  fun destinationFor(name: String, kind: String): String?

  /** A completed file has landed at [path]; take it into the library. */
  fun accepted(path: String, kind: String)

  /** The peer asked this device to fetch a URL itself. */
  fun download(url: String, mediaKind: String)
}
