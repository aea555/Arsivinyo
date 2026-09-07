package expo.modules.localdownloader.vault

import com.google.crypto.tink.subtle.AesGcmHkdfStreaming
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * The vault listing at rest.
 *
 * The behaviour under test is mostly what must *not* happen: a listing that will not open has
 * to fail loudly, because the caller's only other option is to treat it as empty, and an empty
 * listing written back over a real one orphans every file it named.
 */
class VaultIndexCodecTest {

  private fun dek(seed: Int = 1) = ByteArray(32) { (it + seed).toByte() }

  private fun listing(items: Int): String {
    val array = JSONArray()
    repeat(items) {
      array.put(
        JSONObject().put("id", "item-$it").put("title", "A Private Recording $it")
          .put("encFileName", "$it.pv4").put("sizeBytesEncrypted", 1234567)
      )
    }
    return JSONObject().put("version", 3).put("items", array).toString()
  }

  @Test
  fun aListingRoundTrips() {
    val key = dek()
    val json = listing(3)
    assertEquals(json, VaultIndexCodec.open(key, VaultIndexCodec.seal(key, json)))
  }

  @Test
  fun anEmptyListingRoundTrips() {
    val key = dek()
    assertEquals("", VaultIndexCodec.open(key, VaultIndexCodec.seal(key, "")))
  }

  @Test
  fun theTitlesAreNotInTheSealedBytes() {
    val sealed = VaultIndexCodec.seal(dek(), listing(3))
    assertTrue(
      "a title survived into the ciphertext",
      !String(sealed, Charsets.ISO_8859_1).contains("A Private Recording")
    )
  }

  @Test
  fun theSizeDoesNotCountTheItems() {
    // One item against fifty. Both fit inside a padding boundary, so the file must be the
    // same length — otherwise `ls -l` reports roughly how much is in the vault.
    val key = dek()
    assertEquals(
      VaultIndexCodec.seal(key, listing(1)).size,
      VaultIndexCodec.seal(key, listing(20)).size
    )
  }

  @Test
  fun aWrongKeyFailsRatherThanReturningNothing() {
    val sealed = VaultIndexCodec.seal(dek(1), listing(2))
    try {
      VaultIndexCodec.open(dek(2), sealed)
      fail("a listing opened under the wrong key")
    } catch (expected: Exception) {
      // The point is that it throws. Anything that returns is a vault about to be orphaned.
    }
  }

  @Test
  fun aFlippedBitIsCaught() {
    val key = dek()
    val sealed = VaultIndexCodec.seal(key, listing(2))
    for (position in listOf(1, 35, sealed.size - 1)) {
      val bent = sealed.copyOf()
      bent[position] = (bent[position].toInt() xor 1).toByte()
      try {
        VaultIndexCodec.open(key, bent)
        fail("a listing with a flipped bit at $position still opened")
      } catch (expected: Exception) {
      }
    }
  }

  @Test
  fun aTruncatedBlobIsCaught() {
    val key = dek()
    val sealed = VaultIndexCodec.seal(key, listing(2))
    try {
      VaultIndexCodec.open(key, sealed.copyOf(sealed.size - 8))
      fail("a truncated listing still opened")
    } catch (expected: Exception) {
    }
  }

  @Test
  fun aBlobFromAnotherPurposeIsRejected() {
    // Same key, different associated data. Moving a cookie blob into the index's place must
    // not open, or the two stores are interchangeable on disk.
    val key = dek()
    val indexKey = VaultIndexCodec.indexKey(key)
    val out = ByteArrayOutputStream()
    AesGcmHkdfStreaming(indexKey, "HmacSha256", 32, 1 shl 20, 0)
      .newEncryptingStream(out, "cookies/youtube".toByteArray(Charsets.UTF_8))
      .use { it.write(VaultIndexCodec.pad(listing(1).toByteArray(Charsets.UTF_8))) }
    try {
      VaultIndexCodec.open(key, out.toByteArray())
      fail("a blob sealed for another purpose opened as a vault index")
    } catch (expected: Exception) {
    }
  }

  @Test
  fun theIndexKeyIsNotTheDek() {
    val key = dek()
    assertNotEquals(
      "the index key must be derived, not the vault key itself",
      key.toList(), VaultIndexCodec.indexKey(key).toList()
    )
  }

  @Test
  fun paddingRoundTripsAtEveryBoundary() {
    for (length in listOf(0, 1, 4091, 4092, 4093, 8188, 9000)) {
      val content = ByteArray(length) { (it % 251).toByte() }
      val padded = VaultIndexCodec.pad(content)
      assertEquals("padded to a boundary", 0, padded.size % VaultIndexCodec.PAD_BOUNDARY)
      assertTrue("padding must not shrink the content", padded.size >= length + 4)
      assertEquals("unpadded back", content.toList(), VaultIndexCodec.unpad(padded).toList())
    }
  }

  @Test
  fun aPaddedBlockThatLiesAboutItsLengthIsRejected() {
    val padded = VaultIndexCodec.pad(ByteArray(10))
    padded[0] = 0x7f
    try {
      VaultIndexCodec.unpad(padded)
      fail("a padded block claiming more content than it holds was accepted")
    } catch (expected: IllegalArgumentException) {
    }
  }
}
