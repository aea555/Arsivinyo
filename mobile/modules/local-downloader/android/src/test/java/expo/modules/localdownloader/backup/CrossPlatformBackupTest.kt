package expo.modules.localdownloader.backup

import expo.modules.localdownloader.memes.MemeStore
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * The backups the Mac writes, read by this code; and the file this code writes for the Mac.
 *
 * Both live in `shared/crypto/fixtures`, and both hold the same library: a vault clip, a
 * track with artwork and a preset render of it, a playlist and a favourite, a custom preset
 * applied to every download, and a YouTube cookie profile. The Mac's `CoreChecks` restores
 * `phone-written.avsbck` and checks it against the same content; this reads
 * `mac-written.avsbck` and checks every field the phone's restore depends on.
 *
 * To write the phone's file again: ARSIVINYO_WRITE_PHONE_FIXTURE=1 scripts/run-kotlin-tests.sh
 */
class CrossPlatformBackupTest {

  private val passphrase = "cross platform fixture passphrase"

  /** The Mac's `pattern`: byte i is i * step + 11. */
  private fun pattern(count: Int, step: Int): ByteArray =
    ByteArray(count) { ((it * step + 11) and 0xFF).toByte() }

  private fun fixtures(): File {
    var dir: File? = File(System.getProperty("user.dir")).absoluteFile
    while (dir != null) {
      val candidate = File(dir, "shared/crypto/fixtures")
      if (candidate.isDirectory) return candidate
      dir = dir.parentFile
    }
    throw IllegalStateException("shared/crypto/fixtures not found above ${System.getProperty("user.dir")}")
  }

  private class Read(val header: BackupFormat.EntryHeader, val payload: ByteArray)

  @Test
  fun theBackupTheMacWroteReadsHere() {
    val file = File(fixtures(), "mac-written.avsbck")
    val entries = mutableMapOf<String, MutableList<Read>>()
    file.inputStream().buffered().use { input ->
      val header = BackupContainer.peek(input)
      assertEquals(listOf("vault", "music", "memes", "settings", "cookies"), header.sections.map { it.id })
      BackupContainer.read(
        input,
        header,
        listOf(BackupContainer.SlotSecret(BackupFormat.DEFAULT_KEY_SLOT, passphrase.toCharArray(),
          BackupFormat.SECRET_KIND_PASSPHRASE)),
        header.sections.map { it.id }.toSet(),
      ) { entry ->
        val bytes = entry.payload.readBytes()
        // Throws unless the size and hash the Mac recorded match what was read.
        entry.verifiedTrailer()
        entries.getOrPut(entry.sectionId) { mutableListOf() }.add(Read(entry.header, bytes))
      }
    }

    // The vault: a title, a type the importer understands, and the whole file.
    val clip = entries["vault"]!!.single { it.header.name == "Holiday clip" }
    assertEquals("media", clip.header.kind)
    assertEquals("Holiday clip", clip.header.name)
    assertEquals("video/mp4", clip.header.meta.optString("mimeType"))
    assertArrayEquals(pattern(300_000, 7), clip.payload)

    // A private meme is a vault entry whose labels ride in its meta.
    val secret = entries["vault"]!!.single { it.header.meta.has("meme") }
    assertArrayEquals(pattern(15_000, 23), secret.payload)
    val secretMeme = secret.header.meta.getJSONObject("meme")
    assertEquals("gizli-etiket", secretMeme.getJSONArray("tags").getJSONObject(0).getString("name"))
    assertEquals("emotion", secretMeme.getJSONArray("tags").getJSONObject(0).getJSONArray("facets").getString(0))

    // Memes: the file with its source and labels by name, then the vocabulary.
    val memes = entries["memes"]!!
    val arda = memes.single { it.header.kind == "media" }
    assertArrayEquals(pattern(20_000, 19), arda.payload)
    assertTrue(arda.header.meta.optDouble("taggedAt") > 0)
    val ardaMeme = arda.header.meta.getJSONObject("meme")
    assertEquals("bizim laubalilik seviyesi", ardaMeme.getJSONObject("source").getString("caption"))
    assertEquals("Arda Turan", ardaMeme.getJSONArray("people").getJSONObject(0).getString("name"))
    assertEquals("action", ardaMeme.getJSONArray("tags").getJSONObject(0).getJSONArray("facets").getString(0))
    val vocabulary = JSONObject(String(memes.single { it.header.meta.optString("blobId") == "memes-index" }.payload))
    val tagNames = vocabulary.getJSONArray("tags").let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("name") } }
    assertTrue(tagNames.containsAll(listOf("laubalilik", "rahat")))
    // Private-only labels are not in it: they travel with their private memes.
    assertFalse(tagNames.contains("gizli-etiket"))

    // Music: artwork just before its track, both tracks, the render pointing at its source.
    val music = entries["music"]!!
    val one = music.single { it.header.kind == "media" && it.header.meta.optString("title") == "Song One" }
    val songId = one.header.meta.optString("songId")
    assertTrue(songId.isNotBlank())
    assertArrayEquals(pattern(50_000, 13), one.payload)
    val art = music[music.indexOf(one) - 1]
    assertEquals("thumbnail", art.header.kind)
    assertEquals(songId, art.header.meta.optString("ownerId"))
    assertArrayEquals(pattern(1_000, 3), art.payload)
    val render = music.single { it.header.meta.optString("title") == "Song One (Slowed)" }
    assertEquals("slowed-reverb", render.header.meta.optString("presetId"))
    assertEquals(songId, render.header.meta.optString("sourceSongId"))

    // Playlists as sounds/index.json holds them, by song id.
    val index = JSONObject(String(music.single { it.header.meta.optString("blobId") == "music-index" }.payload))
    val playlists = index.getJSONArray("playlists")
    val lists = (0 until playlists.length()).map { playlists.getJSONObject(it) }
    assertEquals(songId, lists.single { it.optString("name") == "Mix" }.getJSONArray("songIds").getString(0))
    assertEquals(songId, lists.single { it.optBoolean("system") }.getJSONArray("songIds").getString(0))

    // The auto-apply blob names a preset that arrives in the settings section.
    val auto = JSONObject(String(music.single { it.header.meta.optString("blobId") == "auto-presets" }.payload))
    val autoPreset = auto.getJSONArray("presets").getJSONObject(0)
    assertEquals("rate=0.9", autoPreset.optString("paramsSpec"))

    // Settings: only the keys the phone's allow-list would apply, as strings.
    val settings = JSONObject(String(entries["settings"]!!.single().payload))
    val custom = JSONArray(settings.getString("@arsivinyo_audio_presets_custom_v1"))
    val mine = custom.getJSONObject(0)
    assertEquals("Mine", mine.optString("name"))
    assertEquals(autoPreset.optString("id"), mine.optString("id"))
    assertEquals(0.9, mine.getJSONObject("params").optDouble("rate"), 1e-9)
    assertTrue(settings.keys().asSequence().all { it.startsWith("@arsivinyo_audio_presets_") })

    // Cookies: the site, the profile's name and whether it is the default.
    val cookie = entries["cookies"]!!.single()
    assertEquals("cookie-profile", cookie.header.kind)
    assertEquals("youtube", cookie.header.meta.optString("platform"))
    assertEquals("main", cookie.header.meta.optString("profileName"))
    assertTrue(cookie.header.meta.optBoolean("isDefault"))
    assertTrue(String(cookie.payload).contains("fixture-session"))
  }

  /**
   * Writes `phone-written.avsbck` through the phone's own collectors, from fakes holding the
   * shared content. Only when asked: the file is committed, and rewriting it on every run
   * would churn it, since every backup has a fresh salt.
   */
  @Test
  fun writeThePhonesFixtureWhenAsked() {
    if (System.getenv("ARSIVINYO_WRITE_PHONE_FIXTURE") == null) return

    val vault = object : BackupPorts.VaultPort {
      val record = BackupPorts.VaultRecord("v1", "Holiday clip", "video/mp4",
        JSONObject().apply { put("title", "Holiday clip"); put("createdAt", 1_754_870_400_000L) }, 300_000L)
      val secret = BackupPorts.VaultRecord("v2", "gizli", "video/mp4",
        JSONObject().apply {
          put("title", "gizli"); put("createdAt", 1_754_870_400_000L)
          put("meme", MemeStore.encodeMeme("video", null, listOf("gizli-etiket" to listOf(MemeStore.Facet.EMOTION)), emptyList()))
        }, 15_000L)
      override fun list() = listOf(record, secret)
      private fun bytes(record: BackupPorts.VaultRecord) = if (record.id == "v1") pattern(300_000, 7) else pattern(15_000, 23)
      override fun writePlaintext(record: BackupPorts.VaultRecord, out: OutputStream) = out.write(bytes(record))
      override fun hashOf(record: BackupPorts.VaultRecord) = BackupContainer.sha256(ByteArrayInputStream(bytes(record)))
      override fun restore(staged: File, name: String, mimeType: String, meta: JSONObject) = error("not restoring")
    }

    val art = File.createTempFile("cover", ".jpg").apply { writeBytes(pattern(1_000, 3)); deleteOnExit() }
    val music = object : BackupPorts.MusicPort {
      val one = BackupPorts.MusicRecord("s1", "Song One.m4a", 50_000L,
        JSONObject().apply { put("title", "Song One"); put("artist", "Artist"); put("durationSec", 1.0) },
        art.absolutePath)
      val slowed = BackupPorts.MusicRecord("s2", "Song One (Slowed).flac", 60_000L,
        JSONObject().apply {
          put("title", "Song One (Slowed)"); put("artist", "Artist"); put("durationSec", 1.2)
          put("presetId", "slowed-reverb"); put("sourceSongId", "s1")
        }, null)
      override fun list() = listOf(one, slowed)
      override fun open(record: BackupPorts.MusicRecord): InputStream =
        ByteArrayInputStream(if (record.id == "s1") pattern(50_000, 13) else pattern(60_000, 17))
      override fun openThumbnail(record: BackupPorts.MusicRecord): InputStream? =
        record.thumbnailPath?.let { File(it).inputStream() }
      override fun hashOf(record: BackupPorts.MusicRecord) = BackupContainer.sha256(open(record))
      override fun playlistsJson() = JSONObject().put("playlists", JSONArray()
        .put(JSONObject().put("id", "favorites").put("name", "Favorites").put("system", true)
          .put("songIds", JSONArray().put("s1")))
        .put(JSONObject().put("id", "p1").put("name", "Mix").put("system", false)
          .put("songIds", JSONArray().put("s1"))))
      override fun autoPresetConfig() = JSONObject().put("keepOriginal", true).put("presets", JSONArray()
        .put(JSONObject().put("id", "custom-mine").put("paramsSpec", "rate=0.9").put("titleSuffix", " (Mine)")))
      override fun restore(staged: File, name: String, meta: JSONObject, thumbnail: File?) = error("not restoring")
      override fun restorePlaylists(json: JSONObject, idMap: Map<String, String>) = Unit
      override fun restoreAutoPresetConfig(json: JSONObject) = Unit
    }

    val cookies = object : BackupPorts.CookiePort {
      override fun list() = listOf(BackupPorts.CookieRecord("youtube", "main", true))
      override fun exists(platform: String, profileName: String) = false
      override fun readPlaintext(record: BackupPorts.CookieRecord) =
        ".youtube.com\tTRUE\t/\tTRUE\t2000000000\tSID\tfixture-session\n".toByteArray()
      override fun restore(platform: String, profileName: String, isDefault: Boolean, plaintext: ByteArray) = Unit
    }

    val memes = object : BackupPorts.MemesPort {
      val arda = BackupPorts.MemeRecord("m1", "arda.mp4", 20_000L, JSONObject()
        .put("memeId", "m1").put("sha256", BackupContainer.sha256(ByteArrayInputStream(pattern(20_000, 19))))
        .put("addedAt", 1_754_870_400_000L).put("taggedAt", 1_754_870_400_000L)
        .put("meme", MemeStore.encodeMeme("video",
          MemeStore.Source(platform = "twitter", account = "futbolcaps", caption = "bizim laubalilik seviyesi", savedAt = 1_754_870_400_000L),
          listOf("laubalilik" to listOf(MemeStore.Facet.ACTION)), listOf("Arda Turan"))))
      override fun list() = listOf(arda)
      override fun open(record: BackupPorts.MemeRecord): InputStream = ByteArrayInputStream(pattern(20_000, 19))
      override fun vocabulary() = JSONObject()
        .put("tags", JSONArray()
          .put(JSONObject().put("name", "laubalilik").put("facets", JSONArray().put("action")))
          .put(JSONObject().put("name", "rahat").put("facets", JSONArray().put("vibe"))))
        .put("people", JSONArray().put(JSONObject().put("name", "Arda Turan")))
      override fun existingIdFor(sha256: String): String? = null
      override fun mergeLabels(existingId: String, meta: JSONObject) = Unit
      override fun restore(staged: File, name: String, meta: JSONObject) = error("not restoring")
      override fun restoreVocabulary(json: JSONObject) = Unit
    }

    // What the settings screen collects from AsyncStorage: each value a JSON string.
    val settings = JSONObject()
      .put("@arsivinyo_audio_presets_custom_v1", JSONArray().put(JSONObject()
        .put("id", "custom-mine").put("name", "Mine").put("builtIn", false).put("titleSuffix", " (Mine)")
        .put("params", JSONObject().put("rate", 0.9))).toString())
      .put("@arsivinyo_theme", "{\"mode\":\"dark\",\"variant\":\"zinc\"}")

    val out = File(fixtures(), "phone-written.avsbck")
    out.outputStream().buffered().use { stream ->
      BackupContainer.write(
        output = stream,
        secrets = listOf(BackupContainer.SlotSecret(BackupFormat.DEFAULT_KEY_SLOT, passphrase.toCharArray(),
          BackupFormat.SECRET_KIND_PASSPHRASE)),
        sections = listOf(
          BackupSections.plan(BackupFormat.SECTION_VAULT, BackupPorts.collectVault(vault)),
          BackupSections.plan(BackupFormat.SECTION_MUSIC, BackupPorts.collectMusic(music)),
          BackupSections.plan(BackupFormat.SECTION_MEMES, BackupPorts.collectMemes(memes)),
          BackupSections.plan(BackupFormat.SECTION_SETTINGS, BackupPorts.collectSettings(settings)),
          BackupSections.plan(BackupFormat.SECTION_COOKIES, BackupPorts.collectCookies(cookies)),
        ),
        appVersion = "fixture",
        appVersionCode = 1,
        createdAt = 1_754_870_400_000L,
      )
    }
    assertNotNull(out.length())
  }
}
