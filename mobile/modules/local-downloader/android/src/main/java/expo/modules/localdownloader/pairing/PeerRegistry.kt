package expo.modules.localdownloader.pairing

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * The devices this phone has paired with.
 *
 * A peer is remembered by its Ed25519 public key. Nothing else stored here is trusted: the
 * name is what the peer called itself and is only ever shown, and the address is a hint
 * for reconnecting without discovery.
 *
 * Unpairing is local and one-sided — [forget] deletes the entry and no message is sent,
 * because a device that has been forgotten should not be told.
 *
 * Backed by one small JSON file rather than a database, matching how the music library
 * keeps its index. It takes a [File] instead of a `Context` so the whole class runs in the
 * JVM test harness: the decision it makes — may this key connect — is worth testing
 * directly rather than only through a paired phone.
 */
class PeerRegistry(private val file: File) {

  data class Peer(
    val publicKey: ByteArray,
    val name: String,
    val lastAddress: String,
    val pairedAt: Long,
  ) {
    val fingerprint: String get() = Ed25519Keys.fingerprint(publicKey)

    // ByteArray compares by identity, which would make two equal peers unequal.
    override fun equals(other: Any?): Boolean =
      other is Peer && publicKey.contentEquals(other.publicKey) && name == other.name &&
        lastAddress == other.lastAddress && pairedAt == other.pairedAt

    override fun hashCode(): Int = publicKey.contentHashCode()
  }

  private val lock = Any()
  private var peers: MutableList<Peer> = mutableListOf()

  init {
    synchronized(lock) { load() }
  }

  fun all(): List<Peer> = synchronized(lock) { peers.toList() }

  /** The single question the transport asks. */
  fun isPaired(publicKey: ByteArray): Boolean = peerFor(publicKey) != null

  fun peerFor(publicKey: ByteArray): Peer? = synchronized(lock) {
    if (publicKey.size != Ed25519Keys.PUBLIC_BYTES) return null
    peers.firstOrNull { it.publicKey.contentEquals(publicKey) }
  }

  /** Add or update a pairing. @return false only if the key is not 32 bytes. */
  fun remember(publicKey: ByteArray, name: String, address: String): Boolean =
    synchronized(lock) {
      if (publicKey.size != Ed25519Keys.PUBLIC_BYTES) return false
      val existing = peers.firstOrNull { it.publicKey.contentEquals(publicKey) }
      // Re-pairing keeps the original time, so the list does not reshuffle when a device
      // is paired again after a reinstall.
      val peer = Peer(publicKey, name, address, existing?.pairedAt ?: System.currentTimeMillis())
      peers.removeAll { it.publicKey.contentEquals(publicKey) }
      peers.add(peer)
      peers.sortBy { it.pairedAt }
      save()
      true
    }

  fun forget(fingerprint: String): Boolean = synchronized(lock) {
    val before = peers.size
    peers.removeAll { it.fingerprint == fingerprint }
    if (peers.size == before) return false
    save()
    true
  }

  fun noteAddress(publicKey: ByteArray, address: String) = synchronized(lock) {
    if (address.isEmpty()) return
    val existing = peers.firstOrNull { it.publicKey.contentEquals(publicKey) } ?: return
    if (existing.lastAddress == address) return
    peers[peers.indexOf(existing)] = existing.copy(lastAddress = address)
    save()
  }

  private fun load() {
    peers = mutableListOf()
    val text = runCatching { file.readText() }.getOrNull() ?: return
    val array = runCatching { JSONArray(text) }.getOrNull() ?: return
    for (i in 0 until array.length()) {
      val entry = array.optJSONObject(i) ?: continue
      val key = unhex(entry.optString("key"))
      // A malformed entry is dropped rather than failing the whole file: one bad row
      // must not cost the user every pairing they have.
      if (key.size != Ed25519Keys.PUBLIC_BYTES) continue
      peers.add(Peer(
        publicKey = key,
        name = entry.optString("name"),
        lastAddress = entry.optString("address"),
        pairedAt = entry.optLong("pairedAt"),
      ))
    }
    peers.sortBy { it.pairedAt }
  }

  private fun save() {
    val array = JSONArray()
    for (peer in peers) {
      array.put(JSONObject()
        .put("key", hex(peer.publicKey))
        .put("name", peer.name)
        .put("address", peer.lastAddress)
        .put("pairedAt", peer.pairedAt))
    }
    runCatching {
      file.parentFile?.mkdirs()
      // Write beside the file and rename, so an interrupted save cannot leave a
      // half-written list that would drop every pairing on the next start.
      val temp = File(file.parentFile, "${file.name}.tmp")
      temp.writeText(array.toString())
      if (!temp.renameTo(file)) {
        file.delete()
        temp.renameTo(file)
      }
    }
  }

  private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

  private fun unhex(text: String): ByteArray {
    if (text.length % 2 != 0) return ByteArray(0)
    return runCatching {
      ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }.getOrElse { ByteArray(0) }
  }
}
