package expo.modules.localdownloader.pairing

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Announces this device on the local network and lists the others.
 *
 * Android has DNS-SD in the platform, so unlike the desktop — which implements the wire
 * format itself in `desktop/src/DnsSd.cpp` because Qt has none — this is a wrapper around
 * `NsdManager`.
 *
 * Discovery reveals only that an Arsivinyo device exists, what it calls itself, and its
 * fingerprint. It says nothing about a library and grants nothing: a peer found here still
 * has to authenticate and is still refused unless it has been paired. The fingerprint is
 * published so a *known* peer can be recognised without opening a connection to ask.
 *
 * Nothing here is trusted. The name is chosen by the peer and is only ever displayed, and
 * the fingerprint is a claim until the transport verifies a signature against it.
 */
class Discovery(context: Context) {

  data class Found(
    val fingerprint: String,
    val name: String,
    val host: String,
    val port: Int,
  )

  var onPeerFound: ((peer: Found) -> Unit)? = null
  var onPeerLost: ((fingerprint: String) -> Unit)? = null

  private val nsd = context.applicationContext
    .getSystemService(Context.NSD_SERVICE) as? NsdManager

  private val found = CopyOnWriteArrayList<Found>()
  private val lock = Any()

  /** Resolves are serialised: NsdManager refuses a second one while the first runs. */
  private val pendingResolves = ArrayDeque<NsdServiceInfo>()
  private var resolving = false

  private var registrationListener: NsdManager.RegistrationListener? = null
  private var discoveryListener: NsdManager.DiscoveryListener? = null

  private var ownFingerprint = ""
  /** The name the system actually registered, which it may change to avoid a clash. */
  private var registeredName = ""

  fun peers(): List<Found> = found.toList()

  /**
   * Announce this device and start looking for others.
   *
   * @param port 0 announces nothing and only listens, which is what a device that is not
   *   accepting connections should do.
   */
  fun start(fingerprint: String, name: String, port: Int): Boolean {
    val manager = nsd ?: return false
    stop()
    ownFingerprint = fingerprint

    if (port != 0 && !register(manager, fingerprint, name, port)) return false
    return discover(manager)
  }

  fun stop() {
    val manager = nsd ?: return
    registrationListener?.let {
      runCatching { manager.unregisterService(it) }
      registrationListener = null
    }
    discoveryListener?.let {
      runCatching { manager.stopServiceDiscovery(it) }
      discoveryListener = null
    }
    synchronized(lock) {
      pendingResolves.clear()
      resolving = false
    }
    found.clear()
  }

  private fun register(
    manager: NsdManager,
    fingerprint: String,
    name: String,
    port: Int,
  ): Boolean = runCatching {
    val info = NsdServiceInfo().apply {
      // The instance name has to be unique on the network; the fingerprint already is.
      serviceName = fingerprint.take(INSTANCE_NAME_CHARS)
      serviceType = SERVICE_TYPE
      this.port = port
      setAttribute("v", "1")
      setAttribute("id", fingerprint)
      setAttribute("name", name)
    }

    val listener = object : NsdManager.RegistrationListener {
      override fun onServiceRegistered(info: NsdServiceInfo) {
        // The system renames on a clash, so this is the only reliable way to know what
        // this device is actually called on the network.
        registeredName = info.serviceName.orEmpty()
      }

      override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
        Log.w(TAG, "could not announce this device: $errorCode")
      }

      override fun onServiceUnregistered(info: NsdServiceInfo) {}
      override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
    }

    manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
    registrationListener = listener
    true
  }.getOrElse {
    Log.w(TAG, "could not announce this device: ${it.message}")
    false
  }

  private fun discover(manager: NsdManager): Boolean = runCatching {
    val listener = object : NsdManager.DiscoveryListener {
      override fun onServiceFound(info: NsdServiceInfo) {
        // A service carries no TXT records until it is resolved, so even skipping our own
        // has to wait until then — the name here may have been renamed by the system.
        enqueueResolve(manager, info)
      }

      override fun onServiceLost(info: NsdServiceInfo) {
        val name = info.serviceName ?: return
        val gone = found.firstOrNull { it.fingerprint.startsWith(name) } ?: return
        found.remove(gone)
        onPeerLost?.invoke(gone.fingerprint)
      }

      override fun onDiscoveryStarted(serviceType: String) {}
      override fun onDiscoveryStopped(serviceType: String) {}

      override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
        Log.w(TAG, "could not look for devices: $errorCode")
        runCatching { manager.stopServiceDiscovery(this) }
      }

      override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
    }

    manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
    discoveryListener = listener
    true
  }.getOrElse {
    Log.w(TAG, "could not look for devices: ${it.message}")
    false
  }

  private fun enqueueResolve(manager: NsdManager, info: NsdServiceInfo) {
    synchronized(lock) {
      pendingResolves.add(info)
      if (resolving) return
      resolving = true
    }
    resolveNext(manager)
  }

  private fun resolveNext(manager: NsdManager) {
    val next = synchronized(lock) {
      val head = pendingResolves.poll()
      if (head == null) resolving = false
      head
    } ?: return

    val listener = object : NsdManager.ResolveListener {
      override fun onServiceResolved(resolved: NsdServiceInfo) {
        accept(resolved)
        resolveNext(manager)
      }

      override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
        // Nothing to report to the user: a device that cannot be resolved simply is not
        // listed, and it will be found again on the next announcement.
        Log.w(TAG, "could not resolve a device: $errorCode")
        resolveNext(manager)
      }
    }

    runCatching { manager.resolveService(next, listener) }.onFailure {
      Log.w(TAG, "could not resolve a device: ${it.message}")
      resolveNext(manager)
    }
  }

  private fun accept(info: NsdServiceInfo) {
    val attributes = info.attributes ?: return
    val fingerprint = attributes["id"]?.toString(Charsets.UTF_8).orEmpty()
    val name = attributes["name"]?.toString(Charsets.UTF_8).orEmpty()
    val host = info.host?.hostAddress.orEmpty()

    if (fingerprint.isEmpty() || host.isEmpty() || info.port == 0) return
    // Never list ourselves, or the UI offers to pair the phone with itself.
    if (fingerprint == ownFingerprint) return

    val peer = Found(fingerprint, name, host, info.port)
    val existing = found.firstOrNull { it.fingerprint == fingerprint }
    if (existing != null) {
      if (existing == peer) return
      found.remove(existing)
    }
    found.add(peer)
    onPeerFound?.invoke(peer)
  }

  private companion object {
    private const val TAG = "PairingDiscovery"
    /** Trailing dot included: NsdManager wants the type fully qualified. */
    const val SERVICE_TYPE = "_arsivinyo._tcp."
    /** A DNS-SD instance name has to fit in one label. */
    const val INSTANCE_NAME_CHARS = 16
  }
}
