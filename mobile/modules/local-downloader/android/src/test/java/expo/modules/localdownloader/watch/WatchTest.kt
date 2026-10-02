package expo.modules.localdownloader.watch

import expo.modules.localdownloader.memes.MemeStore
import java.io.File
import java.nio.file.Files
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The add-on protocol against `shared/watch/VECTORS.json`, which the Mac reads as well, and
 * the library's rules (`shared/watch/CONTRACT.md`).
 */
class WatchTest {

  private fun vectors(): JSONObject {
    var dir: File? = File(System.getProperty("user.dir")).absoluteFile
    while (dir != null && !File(dir, "shared/watch/VECTORS.json").exists()) dir = dir.parentFile
    return JSONObject(File(dir!!, "shared/watch/VECTORS.json").readText())
  }

  @Test
  fun basesAreReadAsStremioReadsThem() {
    val bases = vectors().getJSONArray("bases")
    for (i in 0 until bases.length()) {
      val case = bases.getJSONObject(i)
      val expected = if (case.isNull("base")) null else case.getString("base")
      assertEquals(case.getString("why"), expected, Addons.base(case.getString("input")))
    }
  }

  @Test
  fun resourceUrlsAreBuiltAsStremioBuildsThem() {
    val cases = vectors().getJSONArray("resources")
    for (i in 0 until cases.length()) {
      val c = cases.getJSONObject(i)
      val extra = c.getJSONArray("extra").let { a -> (0 until a.length()).map { a.getJSONArray(it).let { p -> p.getString(0) to p.getString(1) } } }
      assertEquals(c.getString("url"), Addons.resourceUrl(c.getString("base"), c.getString("resource"), c.getString("type"), c.getString("id"), extra))
    }
  }

  @Test
  fun anAddonSaysWhatItAnswers() {
    val supports = vectors().getJSONObject("supports")
    val manifest = Addons.manifest(supports.getJSONObject("manifest"))!!
    val cases = supports.getJSONArray("cases")
    for (i in 0 until cases.length()) {
      val c = cases.getJSONObject(i)
      assertEquals(c.getString("why"), c.getBoolean("expect"),
        Addons.supports(manifest, c.getString("resource"), c.getString("type"), c.getString("id")))
    }
  }

  @Test
  fun addonCatalogsOfferWhatCanBeInstalledHere() {
    val v = vectors().getJSONObject("addonCatalogs")
    val manifest = Addons.manifest(vectors().getJSONObject("supports").getJSONObject("manifest"))!!
    val expectedCatalogs = v.getJSONArray("manifest_catalogs").let { a -> (0 until a.length()).map { a.getJSONArray(it).let { c -> listOf(c.getString(0), c.getString(1), c.getString(2)) } } }
    assertEquals(expectedCatalogs, manifest.addonCatalogs.map { listOf(it.type, it.id, it.name) })
    val offers = Addons.offers(v.getJSONObject("response"))
    val expected = v.getJSONArray("offers")
    assertEquals(v.getString("why"), expected.length(), offers.size)
    for (i in 0 until expected.length()) {
      val e = expected.getJSONObject(i)
      assertEquals(e.getString("base"), offers[i].base)
      assertEquals(e.getString("name"), offers[i].manifest.name)
      assertEquals(e.getBoolean("configurable"), offers[i].manifest.configurable)
      assertEquals(e.getBoolean("required"), offers[i].manifest.configurationRequired)
    }
  }

  @Test
  fun streamsAreReadOrDropped() {
    val cases = vectors().getJSONArray("streams")
    for (i in 0 until cases.length()) {
      val c = cases.getJSONObject(i)
      val stream = Addons.stream(c.getJSONObject("stream"))
      if (c.isNull("kind")) {
        assertNull(c.optString("why"), stream)
        continue
      }
      stream!!
      assertEquals(c.getString("kind"), stream.kind.wire)
      assertEquals(c.getString("target"), stream.target)
      assertEquals(c.getString("label"), stream.label)
      assertEquals(c.getString("detail"), stream.detail)
      if (c.has("fileIdx")) assertEquals(c.getInt("fileIdx"), stream.fileIdx)
      val subtitles = c.optJSONArray("subtitles")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
      assertEquals(subtitles, stream.subtitles.map { it.url })
    }
  }

  @Test
  fun languagesAreNamedByOneCode() {
    val cases = vectors().getJSONObject("languages").getJSONArray("cases")
    for (i in 0 until cases.length()) {
      val c = cases.getJSONArray(i)
      assertEquals(c.getString(0), if (c.isNull(1)) null else c.getString(1), Addons.language(c.getString(0)))
    }
  }

  @Test
  fun subtitlesAreOfferedInThePreferredLanguages() {
    val v = vectors().getJSONObject("subtitleRanking")
    fun strings(key: String) = v.getJSONArray(key).let { a -> (0 until a.length()).map { a.getString(it) } }
    val subtitles = Addons.subtitles(v)
    val ranked = Addons.rankSubtitles(subtitles, strings("preferred"), v.getInt("perLanguage"))
    assertEquals(strings("order"), ranked.map { it.id })
  }

  @Test
  fun metasAreReadAsTheVectorsSay() {
    val cases = vectors().getJSONArray("metas")
    for (i in 0 until cases.length()) {
      val c = cases.getJSONObject(i)
      val meta = Addons.meta(JSONObject().put("meta", c.getJSONObject("meta")))!!
      fun strings(key: String) = c.getJSONArray(key).let { a -> (0 until a.length()).map { a.getString(it) } }
      assertEquals(c.getString("why"), strings("order"), meta.videos.map { it.id })
      assertEquals(strings("titles"), meta.videos.map { it.title })
      assertEquals(strings("trailers"), meta.trailers.map { it.target })
    }
  }

  @Test
  fun aMetaKeepsItsEpisodesInOrderWithSpecialsLast() {
    val json = JSONObject().put("meta", JSONObject().put("id", "tt1").put("type", "series").put("name", "Show")
      .put("videos", JSONArray()
        .put(JSONObject().put("id", "tt1:2:1").put("title", "b").put("season", 2).put("episode", 1))
        .put(JSONObject().put("id", "tt1:0:1").put("title", "special").put("season", 0).put("episode", 1))
        .put(JSONObject().put("id", "tt1:1:2").put("title", "a2").put("season", 1).put("episode", 2))
        .put(JSONObject().put("id", "tt1:1:1").put("title", "a1").put("season", 1).put("episode", 1))))
    assertEquals(listOf("tt1:1:1", "tt1:1:2", "tt1:2:1", "tt1:0:1"), Addons.meta(json)!!.videos.map { it.id })
  }

  // ---- the library ------------------------------------------------------------------------

  private class TestSealer : MemeStore.Sealer {
    private val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
    override fun seal(plaintext: ByteArray, associatedData: ByteArray): ByteArray {
      val nonce = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }
      val c = Cipher.getInstance("AES/GCM/NoPadding")
      c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
      c.updateAAD(associatedData)
      return nonce + c.doFinal(plaintext)
    }
    override fun open(sealed: ByteArray, associatedData: ByteArray): ByteArray {
      val c = Cipher.getInstance("AES/GCM/NoPadding")
      c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, sealed, 0, 12))
      c.updateAAD(associatedData)
      return c.doFinal(sealed, 12, sealed.size - 12)
    }
  }

  private val folder: File = Files.createTempDirectory("watch").toFile()
  private fun library() = WatchLibrary(File(folder, "library.bin"), TestSealer())
  private val show = WatchLibrary.Title("tt0944947", "series", "Game of Thrones", "https://img/got.jpg")

  @Test
  fun progressIsKeptAndTheEndCountsAsWatched() {
    val lib = library()
    lib.recordProgress(show, "tt0944947:1:1", 600_000, 3_600_000, "https://addon", "g1", now = 1)
    assertEquals(listOf(show.id), lib.continueWatching().map { it.id })
    assertEquals(600_000, lib.item(show.id)!!.progress!!.positionMs)
    lib.recordProgress(show, "tt0944947:1:1", 3_400_000, 3_600_000, null, null, now = 2)
    assertTrue(lib.continueWatching().isEmpty())
    assertEquals(setOf("tt0944947:1:1"), lib.item(show.id)!!.watched)
    assertEquals("the source is remembered for the next episode", "g1", lib.item(show.id)!!.bingeGroup)
  }

  @Test
  fun continueWatchingIsNewestFirst() {
    val lib = library()
    val film = WatchLibrary.Title("tt0111161", "movie", "A Film", null)
    lib.recordProgress(show, "tt0944947:1:1", 1000, 10_000, null, null, now = 1)
    lib.recordProgress(film, "tt0111161", 1000, 10_000, null, null, now = 2)
    assertEquals(listOf("tt0111161", show.id), lib.continueWatching().map { it.id })
    lib.dismissProgress("tt0111161")
    assertEquals(listOf(show.id), lib.continueWatching().map { it.id })
  }

  @Test
  fun addonsKeepTheirOrderAndManifests() {
    val lib = library()
    lib.install("https://a", JSONObject().put("id", "a").put("version", "1"))
    lib.install("https://b", JSONObject().put("id", "b"))
    lib.install("https://c", JSONObject().put("id", "c"))
    lib.move("https://c", 0)
    assertEquals(listOf("https://c", "https://a", "https://b"), lib.addons().map { it.base })
    lib.install("https://a", JSONObject().put("id", "a").put("version", "2"))
    assertEquals("2", lib.addons()[1].manifest.getString("version"))
    lib.setEnabled("https://b", false)
    lib.uninstall("https://c")
    val reopened = library()
    assertEquals(listOf("https://a" to true, "https://b" to false), reopened.addons().map { it.base to it.enabled })
  }

  @Test
  fun preferredLanguagesAreKeptByTheirCodes() {
    val lib = library()
    assertEquals(WatchLibrary.DEFAULT_LANGUAGES, lib.languages())
    lib.setLanguages(listOf("EN", "deu", "klingon", "en"))
    assertEquals(listOf("eng", "ger"), library().languages())
  }

  @Test
  fun nothingIsReadableOnDiskAndItSurvivesReopening() {
    val lib = library()
    lib.install("https://torrent.example/token=SECRET123", JSONObject().put("id", "x"))
    lib.recordProgress(show, "tt0944947:1:1", 1000, 10_000, null, null)
    val bytes = String(File(folder, "library.bin").readBytes(), Charsets.ISO_8859_1)
    for (secret in listOf("SECRET123", "Game of Thrones", "tt0944947", "torrent.example")) assertFalse(secret, bytes.contains(secret))
    val reopened = library()
    assertEquals(1000, reopened.item(show.id)!!.progress!!.positionMs)
  }
}
