package expo.modules.localdownloader.pairing

import org.json.JSONObject

/**
 * The `auth` message: the first thing sent in each direction, and the only thing read
 * until it verifies.
 *
 * The counterpart of the auth handling in `desktop/src/PeerLink.cpp`. It is separate from
 * the socket code on purpose — everything here is a pure function of the two certificate
 * hashes and the identity key, so it can be tested against the same
 * `shared/pairing/VECTORS.json` the desktop is held to, rather than only by pairing two
 * real devices and seeing whether it worked.
 *
 * The certificate hashes come from the TLS session: this side's own certificate and the
 * one the peer presented. Signing over both is what binds an identity to *this*
 * connection, so a signature relayed from another one does not verify. See
 * [PairingWire.authTranscript].
 */
object PairingAuth {

  /** What a verified peer turns out to be. */
  data class Peer(val publicKey: ByteArray, val name: String) {
    // ByteArray uses identity equality, which would make two equal peers compare unequal.
    override fun equals(other: Any?): Boolean =
      other is Peer && publicKey.contentEquals(other.publicKey) && name == other.name

    override fun hashCode(): Int = publicKey.contentHashCode() * 31 + name.hashCode()
  }

  /**
   * The message this side sends, as compact JSON.
   *
   * @param role this device's own role, [PairingWire.ROLE_SERVER] or `ROLE_CLIENT`.
   * @return null if the identity cannot sign or the hashes are the wrong length.
   */
  fun build(
    seed: ByteArray,
    publicKey: ByteArray,
    deviceName: String,
    role: Byte,
    serverCertSha256: ByteArray,
    clientCertSha256: ByteArray,
  ): String? = build(publicKey, deviceName, role, serverCertSha256, clientCertSha256) {
    Ed25519Keys.sign(seed, it)
  }

  /**
   * The same, for a device whose key it cannot hand out. This is what the transport uses,
   * so the private seed stays inside [DeviceIdentity] and only signatures leave it.
   */
  fun build(
    identity: PairingIdentity,
    role: Byte,
    serverCertSha256: ByteArray,
    clientCertSha256: ByteArray,
  ): String? = build(identity.publicKey, identity.deviceName, role,
                     serverCertSha256, clientCertSha256, identity::sign)

  private fun build(
    publicKey: ByteArray,
    deviceName: String,
    role: Byte,
    serverCertSha256: ByteArray,
    clientCertSha256: ByteArray,
    sign: (ByteArray) -> ByteArray,
  ): String? {
    val transcript = PairingWire.authTranscript(role, serverCertSha256, clientCertSha256)
    if (transcript.isEmpty()) return null

    val signature = sign(transcript)
    if (signature.size != Ed25519Keys.SIGNATURE_BYTES) return null

    return JSONObject()
      .put("t", "auth")
      .put("v", 1)
      .put("key", hex(publicKey))
      .put("name", deviceName)
      .put("sig", hex(signature))
      .toString()
  }

  /**
   * Check the peer's message.
   *
   * @param peerRole the *peer's* role, which is the opposite of this device's.
   * @return the peer, or null if anything at all is wrong. A caller must treat null as
   *   "close the connection" — there is no partial success to salvage here.
   */
  fun verify(
    message: String,
    peerRole: Byte,
    serverCertSha256: ByteArray,
    clientCertSha256: ByteArray,
  ): Peer? = runCatching {
    val json = JSONObject(message)
    if (json.optString("t") != "auth") return null

    val key = unhex(json.optString("key"))
    val signature = unhex(json.optString("sig"))
    if (key.size != Ed25519Keys.PUBLIC_BYTES) return null

    val transcript = PairingWire.authTranscript(peerRole, serverCertSha256, clientCertSha256)
    if (transcript.isEmpty()) return null
    if (!Ed25519Keys.verify(key, transcript, signature)) return null

    // The name is the peer's to choose and is only ever displayed, never matched against.
    Peer(key, json.optString("name"))
  }.getOrNull()

  private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

  private fun unhex(text: String): ByteArray {
    if (text.length % 2 != 0) return ByteArray(0)
    return runCatching {
      ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }.getOrElse { ByteArray(0) }
  }
}
