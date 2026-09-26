package expo.modules.localdownloader.memes

import android.graphics.Bitmap
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * shared/faces/VECTORS.json on the phone: the same fixture frames must give the same boxes
 * and signatures here as on the Mac (`shared/memes/CONTRACT.md`, faces done criterion 1).
 *
 * On a device because the pipeline is native and ONNX Runtime ships for Android, not for the
 * JVM harness. The fixtures reach the test APK through build.gradle.
 *
 * Run with:  ./gradlew :local-downloader:connectedAndroidTest
 */
class FacesVectorsInstrumentedTest {

  private val context = InstrumentationRegistry.getInstrumentation().context

  private fun ppm(name: String): Bitmap {
    val bytes = context.assets.open("faces-test/$name").use { it.readBytes() }
    val header = String(bytes, 0, 32, Charsets.US_ASCII).split(Regex("\\s+"))
    val width = header[1].toInt()
    val height = header[2].toInt()
    val start = bytes.size - width * height * 3
    val pixels = IntArray(width * height) { i ->
      val r = bytes[start + i * 3].toInt() and 0xff
      val g = bytes[start + i * 3 + 1].toInt() and 0xff
      val b = bytes[start + i * 3 + 2].toInt() and 0xff
      (0xff shl 24) or (r shl 16) or (g shl 8) or b
    }
    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
  }

  @Test
  fun theFixturesGiveWhatTheMacGets() {
    assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
    val vectors = JSONObject(context.assets.open("faces-test/VECTORS.json").use { String(it.readBytes()) })
    FaceScanner.load(context).use { scanner ->
      val fixtures = vectors.getJSONArray("fixtures")
      for (i in 0 until fixtures.length()) {
        val fixture = fixtures.getJSONObject(i)
        val name = fixture.getString("file").substringAfterLast('/')
        val expected = fixture.getJSONArray("faces")
        // One frame: every face is a different person, so merging keeps each as it was seen.
        val found = scanner.scan(listOf(ppm(name) to 0))
        assertEquals("$name: faces", expected.length(), found.size)
        for (j in 0 until expected.length()) {
          val want = expected.getJSONObject(j)
          val box = want.getJSONArray("box").let { a -> (0 until 4).map { a.getDouble(it) } }
          // Merging may order faces differently; match each expected face to the nearest.
          val face = found.minByOrNull { f -> (0 until 4).maxOf { k -> kotlin.math.abs(f.box[k] - box[k]) } }!!
          val offBy = (0 until 4).maxOf { k -> kotlin.math.abs(face.box[k] - box[k]) }
          val signature = want.getJSONArray("signature").let { a -> FloatArray(128) { a.getDouble(it).toFloat() } }
          val cosine = face.signature.indices.sumOf { (face.signature[it] * signature[it]).toDouble() }
          assertTrue("$name: box off by $offBy px", offBy <= 1.0)
          assertTrue("$name: signature cosine $cosine", cosine >= 0.999)
        }
      }
    }
  }
}
