package expo.modules.localdownloader.pairing

/**
 * What the transport needs from this device's identity.
 *
 * [DeviceIdentity] is the real one and needs a `Context` to find its key file. Naming the
 * three things a connection actually uses lets the link be tested without one, and keeps
 * the private seed from being passed around: a caller gets signatures, never the key.
 */
interface PairingIdentity {
  /** 32 raw bytes. The device's identity. */
  val publicKey: ByteArray

  /** Shown to the peer and never matched against — the peer chooses its own. */
  val deviceName: String

  /** Empty on failure, which callers treat as "cannot prove who I am". */
  fun sign(message: ByteArray): ByteArray
}
