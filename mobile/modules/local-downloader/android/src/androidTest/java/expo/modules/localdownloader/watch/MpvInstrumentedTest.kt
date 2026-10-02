package expo.modules.localdownloader.watch

import androidx.test.platform.app.InstrumentationRegistry
import dev.jdtech.mpv.MPVLib
import java.io.File
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The player's done criterion on the phone (`shared/watch/CONTRACT.md`, phase 2): an MKV with
 * HEVC video, AC3 and DTS audio and styled ASS subtitles plays, with the preferred language's
 * tracks picked by themselves.
 *
 * Without a picture (vo=null): what is checked is that libmpv loads with the runtime the app
 * ships, demuxes and decodes all of it, and keeps time. The sample is shared/watch/fixtures,
 * which the Mac plays in its checks too.
 *
 * Run with:  ./gradlew :local-downloader:connectedAndroidTest
 */
class MpvInstrumentedTest {

  private val context = InstrumentationRegistry.getInstrumentation().targetContext

  @Test
  fun anMkvWithHevcAc3DtsAndAssPlays() {
    val sample = File(context.cacheDir, "hevc-ac3-dts-ass.mkv")
    InstrumentationRegistry.getInstrumentation().context.assets.open("watch-test/hevc-ac3-dts-ass.mkv").use { input ->
      sample.outputStream().use { input.copyTo(it) }
    }
    val mpv = checkNotNull(MPVLib.create(context))
    try {
      mpv.setOptionString("vo", "null")
      mpv.setOptionString("ao", "null")
      mpv.setOptionString("slang", "tr,tur,en,eng")
      mpv.setOptionString("alang", "tr,tur,en,eng")
      mpv.setOptionString("keep-open", "yes")
      mpv.init()
      mpv.command(arrayOf("loadfile", sample.path))

      // Until a second and a half has played, or ten seconds have passed.
      val deadline = System.currentTimeMillis() + 10_000
      while ((mpv.getPropertyDouble("time-pos") ?: 0.0) < 1.5 && System.currentTimeMillis() < deadline) Thread.sleep(50)
      assertTrue("it keeps time", (mpv.getPropertyDouble("time-pos") ?: 0.0) >= 1.5)

      val tracks = JSONArray(mpv.getPropertyString("track-list"))
      val byType = (0 until tracks.length()).map { tracks.getJSONObject(it) }.groupBy { it.getString("type") }
      assertEquals("hevc", byType.getValue("video").single().getString("codec"))
      assertEquals(setOf("ac3", "dts"), byType.getValue("audio").map { it.getString("codec") }.toSet())
      val audio = byType.getValue("audio").single { it.optBoolean("selected") }
      assertEquals("the Turkish audio, by preference", "dts", audio.getString("codec"))
      val subtitle = byType.getValue("sub").single()
      assertEquals("ass", subtitle.getString("codec"))
      assertTrue("the Turkish subtitle, by preference", subtitle.optBoolean("selected"))

      // The styled text itself, decoded by libass: the style tags are in the raw ASS.
      assertNotNull(mpv.getPropertyString("sub-text"))
      assertEquals("Merhaba — altyazı", mpv.getPropertyString("sub-text"))
      assertTrue((mpv.getPropertyString("sub-text-ass") ?: "").contains("\\c&H00FF00&"))
    } finally {
      mpv.destroy()
      sample.delete()
    }
  }
}
