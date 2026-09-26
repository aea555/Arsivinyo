package expo.modules.localdownloader.memes

import java.io.File
import java.nio.file.Files
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.sqrt
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The face rules (`shared/memes/CONTRACT.md`, "Faces"), the same scenarios the Mac's
 * FaceChecks run. The arithmetic here is a plain-Kotlin double of shared/faces: that C++ is
 * held to its own vectors by its host test, and what is under test here is who a face is.
 */
class MemeFacesTest {

  /** shared/faces' arithmetic, restated: cosine, spread sets, average linkage, half floats. */
  private object Math : FaceMath {
    override val sure = 0.50f
    override val ask = 0.36f
    override val pipelineVersion = 1

    override fun cosine(a: FloatArray, b: FloatArray): Float = a.indices.sumOf { (a[it] * b[it]).toDouble() }.toFloat()

    override fun best(signature: FloatArray, set: List<FloatArray>): Float =
      set.maxOfOrNull { cosine(signature, it) } ?: -1f

    override fun addToSet(set: List<FloatArray>, signature: FloatArray): List<FloatArray> {
      val all = (set + listOf(signature)).toMutableList()
      if (all.size <= 8) return all
      var drop = 0
      var worst = -1e9
      for (i in all.indices) {
        val sum = all.indices.filter { it != i }.sumOf { cosine(all[i], all[it]).toDouble() }
        if (sum >= worst) {
          worst = sum
          drop = i
        }
      }
      all.removeAt(drop)
      return all
    }

    override fun group(signatures: List<FloatArray>, threshold: Float): IntArray {
      val groups = signatures.indices.map { mutableListOf(it) }.toMutableList()
      while (true) {
        var best = threshold.toDouble()
        var pair: Pair<Int, Int>? = null
        for (i in groups.indices) for (j in i + 1 until groups.size) {
          val avg = groups[i].sumOf { a -> groups[j].sumOf { b -> cosine(signatures[a], signatures[b]).toDouble() } } /
            (groups[i].size * groups[j].size)
          if (avg > best || (avg >= best && pair == null)) {
            best = avg
            pair = i to j
          }
        }
        val (i, j) = pair ?: break
        groups[i].addAll(groups[j])
        groups.removeAt(j)
      }
      val ordered = groups.sortedWith(compareByDescending<MutableList<Int>> { it.size }.thenBy { it.minOrNull() })
      val labels = IntArray(signatures.size)
      ordered.forEachIndexed { g, members -> members.forEach { labels[it] = g } }
      return labels
    }

    override fun encode(signature: FloatArray): String {
      val bytes = ByteArray(256)
      for (i in 0 until 128) {
        val half = toHalf(signature[i])
        bytes[2 * i] = (half and 0xff).toByte()
        bytes[2 * i + 1] = (half shr 8).toByte()
      }
      return Base64.getEncoder().encodeToString(bytes)
    }

    override fun decode(text: String): FloatArray? {
      val bytes = runCatching { Base64.getDecoder().decode(text) }.getOrNull() ?: return null
      if (bytes.size != 256) return null
      return normalised(FloatArray(128) { fromHalf((bytes[2 * it].toInt() and 0xff) or ((bytes[2 * it + 1].toInt() and 0xff) shl 8)) })
    }

    private fun toHalf(v: Float): Int {
      val bits = java.lang.Float.floatToIntBits(v)
      val sign = (bits ushr 16) and 0x8000
      val exp = ((bits ushr 23) and 0xff) - 127 + 15
      val mant = bits and 0x7fffff
      return when {
        exp <= 0 -> sign
        exp >= 31 -> sign or 0x7c00
        else -> sign or (exp shl 10) or (mant ushr 13)
      }
    }

    private fun fromHalf(h: Int): Float {
      val sign = (h and 0x8000) shl 16
      val exp = (h ushr 10) and 0x1f
      val mant = h and 0x3ff
      if (exp == 0) return java.lang.Float.intBitsToFloat(sign)
      return java.lang.Float.intBitsToFloat(sign or ((exp - 15 + 127) shl 23) or (mant shl 13))
    }
  }

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

  private val folder: File = Files.createTempDirectory("faces").toFile()
  private fun store(name: String = "one") = MemeStore(File(folder, "$name.bin"), TestSealer(), null, Math)

  companion object {
    fun normalised(v: FloatArray): FloatArray {
      val length = sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
      return FloatArray(v.size) { if (length > 0) v[it] / length else 0f }
    }

    /** Mostly along [axis], leaning a little towards [other]. */
    fun face(axis: Int, lean: Float = 0f, other: Int = axis + 1) =
      normalised(FloatArray(128).also { it[axis] = 1f; it[other] += lean })

    /** A signature [target] similar to [a], leaning towards [b]. */
    fun blend(a: FloatArray, b: FloatArray, target: Float): FloatArray {
      var lo = 0f
      var hi = 1f
      var out = a
      repeat(40) {
        val t = (lo + hi) / 2
        out = normalised(FloatArray(128) { (1 - t) * a[it] + t * b[it] })
        if (Math.cosine(out, a) > target) lo = t else hi = t
      }
      return out
    }
  }

  // Armstrong from two photos, very alike; Aldrin twice; Collins once.
  private val armstrongCrew = face(0, 0.30f)
  private val armstrong = face(0, 0.35f, 2)
  private val aldrinCrew = face(10, 0.3f)
  private val aldrin = face(10, 0.35f, 12)
  private val collins = face(20)

  private fun scanned(vararg signatures: FloatArray) = signatures.map { ScannedFace(it, listOf(0.0, 0.0, 50.0, 50.0), 0) }

  private var seed = 0
  private fun meme(memes: MemeStore, vararg signatures: FloatArray): String {
    seed++
    val item = memes.add("content://media/$seed", "video", "sha$seed", null)
    memes.record(item.id, scanned(*signatures))
    return item.id
  }

  private fun MemeStore.item(id: String) = snapshot().items.first { it.id == id }

  @Test
  fun unnamedFacesGroupByPersonAndNamingOneLabelsItsMemes() {
    val memes = store()
    val crew = meme(memes, armstrongCrew, aldrinCrew, collins)
    val portrait = meme(memes, armstrong)
    meme(memes, aldrin)
    assertTrue(MemeStore.needingScan(memes.snapshot(), 1).isEmpty())

    val groups = memes.unnamedGroups(memes.snapshot())
    assertEquals(listOf(2, 2, 1), groups.map { it.size }.sortedDescending())
    val neilGroup = groups.first { group -> group.any { it.first.id == portrait } }
    assertEquals(setOf(crew, portrait), neilGroup.map { it.first.id }.toSet())

    val neil = memes.nameFaces(neilGroup.map { it.second.id }.toSet(), "Neil Armstrong")
    assertEquals(2, memes.snapshot().items.count { neil.id in it.people })
    assertEquals(2, memes.snapshot().people.first { it.id == neil.id }.signatures.size)
  }

  @Test
  fun aSureMatchLabelsOnItsOwnAndACloseOneIsAsked() {
    val memes = store()
    val portrait = meme(memes, armstrong)
    val neil = memes.nameFaces(memes.item(portrait).faces.map { it.id }.toSet(), "Neil Armstrong")

    val later = meme(memes, armstrongCrew)
    assertEquals(listOf(neil.id), memes.item(later).people)
    assertEquals(FaceState.AUTO, memes.item(later).faces.single().state)

    val borderline = blend(armstrong, collins, 0.43f)
    val unsure = meme(memes, borderline)
    assertTrue(memes.item(unsure).people.isEmpty())
    assertEquals(FaceState.ASKED, memes.item(unsure).faces.single().state)
    assertEquals(listOf(unsure), MemeStore.asked(memes.snapshot()).map { it.first.id })

    memes.confirmFace(memes.item(unsure).faces.single().id)
    assertEquals(listOf(neil.id), memes.item(unsure).people)
    assertEquals(2, memes.snapshot().people.first { it.id == neil.id }.signatures.size)
  }

  @Test
  fun rejectingAnAutomaticLabelRemovesItForGoodButNotOneAddedByHand() {
    val memes = store()
    val portrait = meme(memes, armstrong)
    val neil = memes.nameFaces(memes.item(portrait).faces.map { it.id }.toSet(), "Neil Armstrong")

    val later = meme(memes, armstrongCrew)
    memes.rejectFace(memes.item(later).faces.single().id)
    assertTrue(memes.item(later).people.isEmpty())
    assertEquals(listOf(neil.id), memes.item(later).faces.single().rejected)
    memes.absorb(mapOf("Neil Armstrong" to listOf(Math.encode(armstrong))))
    assertTrue("even when the person is taught again", memes.item(later).people.isEmpty())

    val byHand = meme(memes)
    memes.label(setOf(byHand), addPeople = setOf(neil.id))
    memes.record(byHand, scanned(armstrongCrew))
    memes.rejectFace(memes.item(byHand).faces.single().id)
    assertEquals("a label added by hand stays", listOf(neil.id), memes.item(byHand).people)

    val again = meme(memes, armstrongCrew)
    memes.label(setOf(again), removePeople = setOf(neil.id))
    assertTrue(memes.item(again).people.isEmpty())
    assertEquals("removing it by hand rejects the face", listOf(neil.id), memes.item(again).faces.single().rejected)
  }

  @Test
  fun aPersonTravelsWithTheirSignaturesAndIsRecognisedUnnamed() {
    val here = store("here")
    val portrait = meme(here, armstrong)
    here.nameFaces(here.item(portrait).faces.map { it.id }.toSet(), "Neil Armstrong")
    val sent = MemeStore.encodeMeme("video", null, emptyList(), listOf("Neil Armstrong"),
      MemeStore.signaturesOf(listOf("Neil Armstrong"), here.snapshot()))
    val decoded = MemeStore.decodeMeme(JSONObject(sent.toString()))
    assertEquals(1, decoded.signatures.getValue("Neil Armstrong").size)

    val there = store("there")
    val waiting = meme(there, armstrongCrew)
    assertTrue(there.item(waiting).people.isEmpty())
    there.absorb(decoded.signatures)
    val snapshot = there.snapshot()
    assertEquals(listOf("Neil Armstrong"),
      there.item(waiting).people.map { id -> snapshot.people.first { it.id == id }.name })
  }

  @Test
  fun aStrangerSignatureIsDroppedOnArrival() {
    val decoded = MemeStore.decodeMeme(JSONObject("""{"people":[{"name":"X","signatures":["not a signature","AAAA"]}]}"""))
    assertTrue(decoded.signatures.isEmpty())
    assertEquals(listOf("X"), decoded.people)
  }

  @Test
  fun facesSurviveReopeningAndNothingOfThemIsInPlainText() {
    val memes = store()
    val portrait = meme(memes, armstrong)
    memes.nameFaces(memes.item(portrait).faces.map { it.id }.toSet(), "Neil Armstrong")
    val reopened = store().snapshot()
    assertEquals(1, reopened.items.single().faces.size)
    assertEquals(FaceState.CONFIRMED, reopened.items.single().faces.single().state)
    val bytes = String(File(folder, "one.bin").readBytes(), Charsets.ISO_8859_1)
    assertFalse(bytes.contains(Math.encode(armstrong).take(24)))
    assertFalse(bytes.contains("Armstrong"))
  }
}
