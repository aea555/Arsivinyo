package expo.modules.localdownloader.pairing

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The verbs and the transfers, over the real TLS link.
 *
 * What matters here is not that a file arrives — it is that a file which should not arrive
 * does not: a hash that does not match, a size that disagrees with it, more bytes than
 * were declared. Each of those leaves nothing behind.
 */
class PeerSessionTest {

  /** A content source backed by one folder, so the session can be tested alone. */
  private class FolderContent(private val dir: File) : PeerContent {
    var lastAccepted: String? = null
    var lastUrl: String? = null
    var lastMediaKind: String? = null

    init { dir.mkdirs() }

    override fun listing(kind: String): JSONArray {
      val items = JSONArray()
      if (kind != "music") return items
      dir.listFiles()?.sortedBy { it.name }?.forEach {
        if (it.isFile && !it.name.endsWith(".part")) {
          items.put(JSONObject().put("id", it.name).put("title", it.nameWithoutExtension)
            .put("sizeBytes", it.length()))
        }
      }
      return items
    }

    override fun openItem(id: String): ItemSource? {
      // Only a plain name in this folder, never a path the peer composed.
      if (id.contains('/') || id.contains("..")) return null
      val file = File(dir, id)
      if (!file.isFile) return null
      return ItemSource(file.name, file.length()) { file.inputStream() }
    }

    override fun destinationFor(name: String, kind: String): String =
      File(dir, File(name).name).path

    override fun accepted(path: String, kind: String, artworkPath: String?, meme: org.json.JSONObject?) { lastAccepted = path }

    override fun download(url: String, mediaKind: String) {
      lastUrl = url
      lastMediaKind = mediaKind
    }
  }

  private lateinit var root: File
  private lateinit var serverDir: File
  private lateinit var clientDir: File

  @Before fun setUp() {
    root = Files.createTempDirectory("session").toFile()
    serverDir = File(root, "server")
    clientDir = File(root, "client")
  }

  @After fun tearDown() {
    root.deleteRecursively()
  }

  /** Run [body] with a session on each end of a live link. */
  private fun sessions(
    body: (server: PeerSession, client: PeerSession,
           serverContent: FolderContent, clientContent: FolderContent) -> Unit,
  ) {
    val serverContent = FolderContent(serverDir)
    val clientContent = FolderContent(clientDir)
    LoopbackPeers.connected { serverLink, clientLink ->
      body(PeerSession(serverLink, serverContent), PeerSession(clientLink, clientContent),
        serverContent, clientContent)
    }
  }

  private fun payload(size: Int) = ByteArray(size) { ((it * 31 + it / 7) and 0xff).toByte() }

  @Test
  fun aFileCrossesAndIsVerified() {
    sessions { server, client, serverContent, _ ->
      // Larger than one chunk, so it exercises reassembly rather than a single frame.
      val bytes = payload(700_000)
      val source = File(clientDir, "track.m4a").apply { parentFile?.mkdirs() }
      source.writeBytes(bytes)

      val landed = CountDownLatch(1)
      server.onFileReceived = { _, _ -> landed.countDown() }

      assertTrue(client.sendFile(source, "music"))
      assertTrue("the file arrives", landed.await(30, TimeUnit.SECONDS))

      val received = File(serverDir, "track.m4a")
      assertTrue(received.isFile)
      assertArrayEquals("byte for byte", bytes, received.readBytes())
      assertEquals(received.path, serverContent.lastAccepted)
      assertFalse("no partial file is left behind", File(serverDir, "track.m4a.part").exists())
    }
  }

  @Test
  fun aMismatchedHashNeverLands() {
    sessions { server, client, _, _ ->
      val failed = CountDownLatch(1)
      var reason = ""
      server.onTransferFailed = { reason = it; failed.countDown() }

      // Announce one hash and send different bytes, the way a peer with modified
      // software could.
      val bytes = payload(4096)
      val lie = ByteArray(32) { 0x11 }
      client.link.sendControl(JSONObject().put("t", "put").put("name", "forged.m4a")
        .put("kind", "music").put("sizeBytes", bytes.size).put("sha256", lie.joinToString("") { "%02x".format(it) }))
      Thread.sleep(300)
      client.link.sendBulk(bytes)
      client.link.sendControl(JSONObject().put("t", "complete")
        .put("sha256", lie.joinToString("") { "%02x".format(it) }))

      assertTrue("it is refused", failed.await(20, TimeUnit.SECONDS))
      assertFalse("and never appears", File(serverDir, "forged.m4a").exists())
      assertFalse("nor does the partial", File(serverDir, "forged.m4a.part").exists())
    }
  }

  @Test
  fun aSizeThatDisagreesWithTheHashNeverLands() {
    sessions { server, client, _, _ ->
      // The case the size check exists for: the hash of the smaller payload actually sent,
      // with a larger size declared. The hash alone would be satisfied.
      val bytes = payload(4096)
      val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
      val hex = digest.joinToString("") { "%02x".format(it) }

      val failed = CountDownLatch(1)
      server.onTransferFailed = { failed.countDown() }

      client.link.sendControl(JSONObject().put("t", "put").put("name", "mismatch.m4a")
        .put("kind", "music").put("sizeBytes", 8192).put("sha256", hex))
      Thread.sleep(300)
      client.link.sendBulk(bytes)
      client.link.sendControl(JSONObject().put("t", "complete").put("sha256", hex))

      assertTrue("it is refused", failed.await(20, TimeUnit.SECONDS))
      assertFalse(File(serverDir, "mismatch.m4a").exists())
    }
  }

  @Test
  fun moreBytesThanDeclaredAreRefused() {
    sessions { server, client, _, _ ->
      val failed = CountDownLatch(1)
      var reason = ""
      server.onTransferFailed = { reason = it; failed.countDown() }

      client.link.sendControl(JSONObject().put("t", "put").put("name", "over.m4a")
        .put("kind", "music").put("sizeBytes", 16)
        .put("sha256", "00".repeat(32)))
      Thread.sleep(300)
      client.link.sendBulk(payload(4096))

      assertTrue("the sender is cut off", failed.await(20, TimeUnit.SECONDS))
      assertTrue(reason, reason.contains("more than it declared"))
      assertFalse(File(serverDir, "over.m4a").exists())
    }
  }

  @Test
  fun listingAndGetPullAFileTheOtherWay() {
    sessions { _, client, _, _ ->
      val bytes = payload(300_000)
      File(serverDir, "pulled.m4a").apply { parentFile?.mkdirs() }.writeBytes(bytes)

      val listed = CountDownLatch(1)
      var items = JSONArray()
      client.onListing = { _, got -> items = got; listed.countDown() }
      assertTrue(client.requestListing("music"))
      assertTrue("a listing comes back", listed.await(20, TimeUnit.SECONDS))
      assertEquals(1, items.length())
      assertEquals("pulled.m4a", items.getJSONObject(0).getString("id"))

      val landed = CountDownLatch(1)
      client.onFileReceived = { _, _ -> landed.countDown() }
      assertTrue(client.requestItem("pulled.m4a"))
      assertTrue("the item arrives", landed.await(30, TimeUnit.SECONDS))
      assertArrayEquals("byte for byte", bytes, File(clientDir, "pulled.m4a").readBytes())
    }
  }

  @Test
  fun aGetForAPathDressedUpAsAnIdFails() {
    sessions { _, client, _, _ ->
      val failed = CountDownLatch(1)
      client.onTransferFailed = { failed.countDown() }
      assertTrue(client.requestItem("../../../etc/passwd"))
      assertTrue("it resolves to nothing", failed.await(20, TimeUnit.SECONDS))
      assertNull(File(clientDir, "passwd").takeIf { it.exists() })
    }
  }

  @Test
  fun aUrlIsHandedOverUnchanged() {
    sessions { _, client, serverContent, _ ->
      assertTrue(client.requestDownload("https://example.invalid/watch?v=x", "audio"))
      val deadline = System.currentTimeMillis() + 20_000
      while (serverContent.lastUrl == null && System.currentTimeMillis() < deadline) {
        Thread.sleep(20)
      }
      assertEquals("https://example.invalid/watch?v=x", serverContent.lastUrl)
      assertEquals("audio", serverContent.lastMediaKind)
    }
  }

  @Test
  fun aSecondTransferIsRefusedWhileOneIsRunning() {
    sessions { _, client, _, _ ->
      val source = File(clientDir, "big.m4a").apply { parentFile?.mkdirs() }
      source.writeBytes(payload(2_000_000))
      assertTrue(client.sendFile(source, "music"))
      assertFalse("a second send is refused", client.sendFile(source, "music"))
      assertFalse("and so is a get", client.requestItem("anything"))
    }
  }
}
