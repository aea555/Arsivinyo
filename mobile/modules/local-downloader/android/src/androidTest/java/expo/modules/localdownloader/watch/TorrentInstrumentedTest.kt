package expo.modules.localdownloader.watch

import androidx.test.platform.app.InstrumentationRegistry
import expo.modules.localdownloader.torrent.TorrentNative
import java.io.File
import java.net.Socket
import java.net.URL
import org.json.JSONArray
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The torrent engine on the phone (`shared/watch/CONTRACT.md`, phase 3), the same scenario
 * shared/torrent/test runs on the Mac: a seeder and a downloader on loopback, no trackers.
 * The seeder is held to 1 MB/s so the 8 MiB movie takes a while; the end of it is read
 * through the streaming server first and must come before most of the file has.
 *
 * Run with:  ./gradlew :local-downloader:connectedAndroidTest
 */
class TorrentInstrumentedTest {

  private val context = InstrumentationRegistry.getInstrumentation().targetContext
  private val root = File(context.cacheDir, "torrent-test")

  /** shared/torrent/test's pattern: an LCG, one byte from bits 16..23 of each step. */
  private fun pattern(size: Int, seed: Int): ByteArray {
    var x = seed
    return ByteArray(size) {
      x = x * 1103515245 + 12345
      (x ushr 16).toByte()
    }
  }

  private fun status(handle: Long) = JSONArray(TorrentNative.nativeStatus(handle) ?: "[]").optJSONObject(0)

  private fun waitFor(seconds: Int, done: () -> Boolean): Boolean {
    val until = System.currentTimeMillis() + seconds * 1000L
    while (System.currentTimeMillis() < until) {
      if (done()) return true
      Thread.sleep(50)
    }
    return done()
  }

  /** A ranged GET over a plain socket, as mpv's own HTTP does: the test APK may not use cleartext. */
  private fun range(url: String, first: Long, last: Long): ByteArray {
    val address = URL(url)
    Socket("127.0.0.1", address.port).use { socket ->
      socket.soTimeout = 60_000
      socket.getOutputStream().write("GET ${address.path} HTTP/1.1\r\nHost: x\r\nRange: bytes=$first-$last\r\n\r\n".toByteArray())
      val response = socket.getInputStream().readBytes()
      val head = String(response, 0, minOf(response.size, 512), Charsets.ISO_8859_1)
      assertTrue("a partial response", head.startsWith("HTTP/1.1 206"))
      val body = head.indexOf("\r\n\r\n") + 4
      return response.copyOfRange(body, response.size)
    }
  }

  @Test
  fun streamsTheEndFirstResumesAndStopsSeeding() {
    assertTrue("the library loads", TorrentNative.available)
    root.deleteRecursively()
    val movie = pattern(8 shl 20, 7)
    File(root, "seed/show").mkdirs()
    File(root, "seed/show/movie.mkv").writeBytes(movie)
    File(root, "seed/show/extra.nfo").writeBytes(pattern(512 shl 10, 9))
    val torrent = InstrumentationRegistry.getInstrumentation().context.assets.open("watch-test/sample.torrent").use { it.readBytes() }

    val seeder = TorrentNative.nativeCreate(File(root, "seed-state").path, 0, 100.0, true, false, 1 shl 20, 0)
    assertTrue(seeder != 0L)
    val id = checkNotNull(TorrentNative.nativeAddTorrent(seeder, torrent, File(root, "seed").path, false))
    assertTrue("the seeder has the whole torrent", waitFor(20) { status(seeder)?.optString("state") == "seeding" })

    var getter = TorrentNative.nativeCreate(File(root, "get-state").path, 0, 0.0, true, false, 0, 0)
    TorrentNative.nativeAddTorrent(getter, torrent, File(root, "get").path, false)
    val files = JSONArray(TorrentNative.nativeFiles(getter, id))
    val movieIndex = (0 until files.length()).first { files.getJSONObject(it).getString("path").endsWith("movie.mkv") }
    TorrentNative.nativeSetPriorities(getter, id, ByteArray(files.length()) { if (it == movieIndex) 4 else 0 })
    TorrentNative.nativeConnectPeer(getter, id, "127.0.0.1", TorrentNative.nativePort(seeder))

    val url = "${TorrentNative.nativeServerStart(getter)}/$id/$movieIndex/movie.mkv"
    val tail = range(url, movie.size - 100_000L, movie.size - 1L)
    val progress = status(getter)!!.getDouble("progress")
    assertArrayEquals("the end of the file is served, byte for byte", movie.copyOfRange(movie.size - 100_000, movie.size), tail)
    assertTrue("before most of the file has arrived ($progress)", progress < 0.5)

    TorrentNative.nativeDestroy(getter)
    getter = TorrentNative.nativeCreate(File(root, "get-state").path, 0, 0.0, true, false, 0, 0)
    assertEquals("after a restart the torrent is still there", id, status(getter)?.optString("id"))
    TorrentNative.nativeConnectPeer(getter, id, "127.0.0.1", TorrentNative.nativePort(seeder))
    assertTrue("and it finishes", waitFor(60) { status(getter)?.optBoolean("finished") == true })
    assertArrayEquals("the wanted file is whole", movie, File(root, "get/show/movie.mkv").readBytes())
    assertTrue("the file that was not wanted was not downloaded",
      File(root, "get/show/extra.nfo").let { !it.exists() || it.length() < (512 shl 10) })
    assertTrue("with seeding off, a finished torrent stops", waitFor(10) { status(getter)?.optBoolean("paused") == true })

    TorrentNative.nativeDestroy(getter)
    TorrentNative.nativeDestroy(seeder)
    root.deleteRecursively()
  }
}
