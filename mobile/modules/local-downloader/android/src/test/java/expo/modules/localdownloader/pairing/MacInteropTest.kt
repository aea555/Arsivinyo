package expo.modules.localdownloader.pairing

import expo.modules.localdownloader.memes.MemeStore
import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone's side of a real pairing with the Mac app, over TCP on this machine.
 *
 * Only runs when `mac/scripts/check-pairing-interop.sh` asks for it: it waits for the Mac's
 * `CoreChecks --interop` to connect, pairs through the v2 ceremony, serves one track, takes
 * one track, and takes a link to download. Both sides write what they saw into a shared
 * folder, and each checks the other's.
 */
class MacInteropTest {

  private fun pattern(count: Int, step: Int): ByteArray =
    ByteArray(count) { ((it * step + 11) and 0xFF).toByte() }

  private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

  @Test
  fun pairsAndTradesWithTheMac() {
    val dir = System.getenv("ARSIVINYO_INTEROP_DIR")?.let(::File) ?: return
    val port = System.getenv("ARSIVINYO_INTEROP_PORT")?.toInt() ?: return

    val work = File(dir, "phone").apply { mkdirs() }
    val track = File(work, "Phone Track.m4a").apply { writeBytes(pattern(345_678, 31)) }
    val cover = File(work, "phone-cover.jpg").apply { writeBytes(pattern(4_000, 7)) }
    var receivedArtwork: String? = null
    var receivedMeme: String? = null
    var requestedUrl = ""
    var requestedKind = ""
    val received = mutableListOf<File>()

    val content = object : PeerContent {
      override fun listing(kind: String) = JSONArray().put(JSONObject()
        .put("id", "p1").put("title", "Phone Track").put("artist", "Phone")
        .put("durationSec", 1.0).put("sizeBytes", track.length()))
      override fun openItem(id: String) =
        if (id == "p1") ItemSource(track.name, track.length(), cover) { track.inputStream() } else null
      override fun destinationFor(name: String, kind: String) = File(work, "in-" + File(name).name).path
      override fun accepted(path: String, kind: String, artworkPath: String?, meme: org.json.JSONObject?) {
        if (kind == "meme") {
          val decoded = MemeStore.decodeMeme(meme)
          val tags = decoded.tags.joinToString(";") { (name, facets) -> name + ":" + facets.joinToString(",") { it.wire } }
          receivedMeme = "${decoded.kind}|$tags|${decoded.people.joinToString(";")}|${decoded.source?.caption}"
          return
        }
        receivedArtwork = artworkPath
        synchronized(received) { received.add(File(path)) }
      }
      override fun download(url: String, mediaKind: String) {
        requestedUrl = url
        requestedKind = mediaKind
      }
    }

    val identity = LoopbackPeers.TestIdentity("Phone")
    val service = PairingService(identity, PeerRegistry(File(work, "peers.json")), content, LoopbackPeers::sslContext)
    assertTrue(service.listen(port))
    service.beginPairing(120)
    File(dir, "phone-ready").writeText("${service.port}")

    fun waitFor(seconds: Long, condition: () -> Boolean): Boolean {
      val deadline = System.currentTimeMillis() + seconds * 1000
      while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(50)
      return condition()
    }

    try {
      assertTrue("the Mac starts the ceremony", waitFor(60) { service.pendingCode.isNotEmpty() })
      File(dir, "phone-code").writeText(service.pendingCode)
      // The person holding the phone compares the digits with the Mac's and confirms.
      assertTrue("the Mac shows the same digits",
        waitFor(30) { File(dir, "mac-code").let { it.exists() && it.readText() == service.pendingCode } })
      assertTrue(service.confirmPairing())

      assertTrue("a track from the Mac arrives", waitFor(60) { synchronized(received) { received.isNotEmpty() } })
      val got = synchronized(received) { received.first() }
      File(dir, "phone-received-sha256").writeText(hex(MessageDigest.getInstance("SHA-256").digest(got.readBytes())))
      File(dir, "phone-received-artwork-sha256").writeText(
        receivedArtwork?.let { hex(MessageDigest.getInstance("SHA-256").digest(File(it).readBytes())) } ?: "none")

      assertTrue("the Mac sends a link", waitFor(30) { requestedUrl.isNotEmpty() })
      File(dir, "phone-link").writeText("$requestedKind $requestedUrl")

      assertTrue("a meme from the Mac arrives", waitFor(60) { receivedMeme != null })
      File(dir, "phone-meme").writeText(receivedMeme!!)
      assertTrue("the Mac asks for one back", waitFor(30) { File(dir, "mac-wants-meme").exists() })
      val meme = File(work, "rahat.mp4").apply { writeBytes(pattern(12_000, 29)) }
      val labels = MemeStore.encodeMeme("video", null, listOf("rahat" to listOf(MemeStore.Facet.VIBE)), listOf("Fatih Terim"))
      assertTrue("a meme goes to the Mac",
        service.sessions().first().send(ItemSource(meme.name, meme.length(), null, labels) { meme.inputStream() }, "meme"))

      assertTrue("the Mac finishes", waitFor(60) { File(dir, "mac-done").exists() })
      assertEquals("", File(dir, "mac-failures").takeIf { it.exists() }?.readText() ?: "")
    } finally {
      service.stop()
    }
  }
}
