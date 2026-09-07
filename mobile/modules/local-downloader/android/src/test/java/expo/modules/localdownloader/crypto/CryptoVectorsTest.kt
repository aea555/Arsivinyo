package expo.modules.localdownloader.crypto

import com.google.crypto.tink.subtle.AesGcmHkdfStreaming
import com.google.crypto.tink.subtle.Hkdf
import expo.modules.localdownloader.backup.BackupContainer
import expo.modules.localdownloader.backup.BackupCrypto
import expo.modules.localdownloader.backup.BackupFormat
import expo.modules.localdownloader.backup.BackupSections
import expo.modules.localdownloader.vault.VaultIndexCodec
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.Random

/**
 * Kotlin against `shared/crypto/VECTORS.json` — the same file the C++ implementation reads.
 *
 * The desktop reimplements in C++ what the phone gets from Tink and BouncyCastle. Two
 * implementations of one format drift unless something forces them not to, and here a drift
 * does not mean a failed handshake: it means a vault that cannot be opened. Prose does not
 * prevent that. A file both test suites read does.
 *
 * Regenerate with `-Darsivinyo.vectors.write=1`, and review the diff — the file is a contract,
 * not an output. Every randomised input is fixed so a rerun produces no churn.
 *
 * The AEAD entries are asymmetric on purpose. Tink will not let a caller choose the header
 * salt or the nonce prefix, so the generator encrypts with stock Tink and records whatever
 * header Tink produced. Kotlin can then only check that it decrypts; C++ can inject the same
 * header and must reproduce the ciphertext byte for byte, which is the stronger check and the
 * direction that matters — C++ output has to be readable by the phone.
 */
class CryptoVectorsTest {

  private val repoRoot: File by lazy {
    var dir = File(System.getProperty("user.dir") ?: ".").absoluteFile
    while (!File(dir, "shared").isDirectory) {
      dir = dir.parentFile ?: error("no shared/ above ${System.getProperty("user.dir")}")
    }
    dir
  }

  private val vectorsFile: File get() = File(repoRoot, "shared/crypto/VECTORS.json")

  private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
  private fun unhex(s: String) = ByteArray(s.length / 2) {
    ((Character.digit(s[it * 2], 16) shl 4) or Character.digit(s[it * 2 + 1], 16)).toByte()
  }
  private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)

  /** The plaintext for an AEAD vector, so neither side has to store a megabyte of it. */
  private fun patternBytes(length: Int, seed: Long): ByteArray =
    ByteArray(length).also { Random(seed).nextBytes(it) }

  private fun argon2id(
    password: String,
    salt: ByteArray,
    memoryKiB: Int,
    iterations: Int,
    parallelism: Int,
    outLength: Int,
  ): ByteArray {
    val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
      .withVersion(Argon2Parameters.ARGON2_VERSION_13)
      .withMemoryAsKB(memoryKiB)
      .withIterations(iterations)
      .withParallelism(parallelism)
      .withSalt(salt)
      .build()
    val generator = Argon2BytesGenerator().apply { init(params) }
    return ByteArray(outLength).also { generator.generateBytes(password.toCharArray(), it) }
  }

  private fun tinkEncrypt(key: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray {
    val streaming = AesGcmHkdfStreaming(key, "HmacSha256", 32, SEGMENT, 0)
    val out = ByteArrayOutputStream()
    streaming.newEncryptingStream(out, aad).use { it.write(plaintext) }
    return out.toByteArray()
  }

  // ---- generation ------------------------------------------------------------------------

  private fun build(): JSONObject {
    val root = JSONObject()
    root.put(
      "_comment",
      JSONArray(
        listOf(
          "Pinned inputs and outputs for the portable security core.",
          "Kotlin (Tink + BouncyCastle) and C++ (OpenSSL) are both held to this file.",
          "Kotlin checks that it can reproduce or decrypt each entry; C++ additionally",
          "reproduces every ciphertext byte for byte, using the recorded header.",
          "Regenerate with -Darsivinyo.vectors.write=1 and review the diff.",
        )
      )
    )

    // --- Argon2id -------------------------------------------------------------------------
    val salt16 = ByteArray(16) { it.toByte() }
    val argon = JSONArray()
    fun argonCase(why: String, password: String, salt: ByteArray, m: Int, t: Int, p: Int) {
      argon.put(
        JSONObject()
          .put("why", why)
          .put("password", password)
          .put("passwordUtf8", hex(password.toByteArray(Charsets.UTF_8)))
          .put("salt", hex(salt))
          .put("memoryKiB", m).put("iterations", t).put("parallelism", p)
          .put("version", 19).put("outLength", 32)
          .put("out", hex(argon2id(password, salt, m, t, p, 32)))
      )
    }
    argonCase("the shipped profile; lowering it silently would weaken every keybox",
      "correct-horse-battery-staple", salt16, 65536, 3, 4)
    argonCase("the cheap profile the tests use, pinned so it cannot drift either",
      "correct-horse-battery-staple", salt16, 8192, 1, 4)
    argonCase("one lane against four, otherwise identical: proves parallelism is honoured " +
      "rather than silently ignored, which is the classic Argon2 porting bug",
      "correct-horse-battery-staple", salt16, 8192, 1, 1)
    argonCase("an empty password", "", salt16, 8192, 1, 4)
    argonCase("non-ASCII: Kotlin encodes a CharArray as UTF-8 and C++ must match, or a " +
      "backup written on one device will not open on the other",
      "pässwört 🔐 çğş", salt16, 8192, 1, 4)
    argonCase("a trailing space, which is easy to trim by accident",
      "trailing space ", salt16, 8192, 1, 4)
    root.put("argon2id", argon)

    // --- HKDF -----------------------------------------------------------------------------
    val ikm = ByteArray(32) { (it + 0x40).toByte() }
    val hkdf = JSONArray()
    fun hkdfCase(why: String, salt: ByteArray?, info: String, length: Int) {
      hkdf.put(
        JSONObject()
          .put("why", why)
          .put("ikm", hex(ikm))
          .put("salt", if (salt == null) JSONObject.NULL else hex(salt))
          .put("info", info)
          .put("outLength", length)
          .put("out", hex(Hkdf.computeHkdf("HMACSHA256", ikm, salt, info.toByteArray(Charsets.UTF_8), length)))
      )
    }
    hkdfCase("a null salt, which is what every subkey derivation uses", null, "avsbck/verify/v1", 32)
    hkdfCase("32 zero bytes must equal the null salt above, or the two libraries disagree " +
      "about a default and every subkey diverges", ByteArray(32), "avsbck/verify/v1", 32)
    hkdfCase("a real salt", ByteArray(32) { (it * 3 + 1).toByte() }, "avsbck/verify/v1", 32)
    hkdfCase("an empty info string", null, "", 32)
    hkdfCase("an output that is not 32 bytes", null, "avsbck/verify/v1", 64)
    root.put("hkdf_sha256", hkdf)

    // --- the .avsbck key hierarchy ---------------------------------------------------------
    val sections = JSONObject()
    for (id in listOf("vault", "music", "settings", "cookies")) {
      sections.put(id, hex(Hkdf.computeHkdf("HMACSHA256", ikm, null,
        "avsbck/section/v1/$id".toByteArray(Charsets.UTF_8), 32)))
    }
    root.put(
      "avsbck_subkeys",
      JSONObject()
        .put("why", "pins the literal labels, including the trailing slash on the section " +
          "prefix — a missing one is invisible inside either implementation")
        .put("masterKey", hex(ikm))
        .put("verifier", hex(Hkdf.computeHkdf("HMACSHA256", ikm, null,
          "avsbck/verify/v1".toByteArray(Charsets.UTF_8), 32)))
        .put("sections", sections)
    )

    // --- segment nonces ---------------------------------------------------------------------
    val prefix = ByteArray(7) { (0xA0 + it).toByte() }
    val nonces = JSONArray()
    for (index in listOf(0L, 1L, 255L, 256L, 65535L, 65536L, 16777216L)) {
      for (last in listOf(false, true)) {
        val nonce = ByteArray(12)
        System.arraycopy(prefix, 0, nonce, 0, 7)
        nonce[7] = ((index shr 24) and 0xff).toByte()
        nonce[8] = ((index shr 16) and 0xff).toByte()
        nonce[9] = ((index shr 8) and 0xff).toByte()
        nonce[10] = (index and 0xff).toByte()
        nonce[11] = if (last) 1 else 0
        nonces.put(JSONObject().put("noncePrefix", hex(prefix)).put("segmentIndex", index)
          .put("last", last).put("nonce", hex(nonce)))
      }
    }
    root.put("segment_nonce", JSONObject()
      .put("why", "big-endian segment index and the last-segment flag. Cheap to pin here; " +
        "through a whole stream the endianness bug only shows after 256 MB")
      .put("cases", nonces))

    // --- the streaming AEAD ------------------------------------------------------------------
    val streamKey = ByteArray(32) { it.toByte() }
    val aead = JSONArray()
    val cap0 = SEGMENT - 40 - 16
    val capN = SEGMENT - 16
    for ((length, why) in listOf(
      0 to "empty input still costs a header and one empty final segment",
      1 to "one byte",
      100 to "a short single segment",
      (cap0 - 1) to "one byte short of filling segment 0",
      cap0 to "segment 0 exactly full: this must stay ONE segment, so the last-segment flag " +
        "is set on it rather than on an empty segment after it",
      (cap0 + 1) to "one byte past segment 0, which forces a second segment",
      (cap0 + capN) to "two segments exactly full",
      (cap0 + capN + 1) to "three segments, the last holding one byte",
    )) {
      val plaintext = patternBytes(length, length.toLong())
      val ciphertext = tinkEncrypt(streamKey, AAD.toByteArray(Charsets.UTF_8), plaintext)
      aead.put(
        JSONObject()
          .put("why", why)
          .put("key", hex(streamKey))
          .put("associatedData", AAD)
          .put("plaintext", JSONObject().put("pattern", "java-util-random")
            .put("seed", length.toLong()).put("length", length))
          .put("headerSalt", hex(ciphertext.copyOfRange(1, 33)))
          .put("noncePrefix", hex(ciphertext.copyOfRange(33, 40)))
          .put("ciphertextLength", ciphertext.size)
          .put("ciphertextSha256", hex(sha256(ciphertext)))
      )
    }
    root.put("aead_stream", aead)

    // --- the vault index -----------------------------------------------------------------
    // Both apps seal their vault listing the same way. The phone's was plain JSON until now,
    // so this label and this padding are new on both sides at once, which is exactly when a
    // pinned vector is worth having.
    val dek = ByteArray(32) { (0x10 + it).toByte() }
    val listing = JSONObject().put("formatVersion", 1).put("items", JSONArray()).toString()
    val padSamples = JSONArray()
    for (length in listOf(0, 1, 4091, 4092, 4093, 9000)) {
      val content = patternBytes(length, length.toLong())
      val padded = VaultIndexCodec.pad(content)
      padSamples.put(
        JSONObject().put("length", length).put("paddedLength", padded.size)
          .put("paddedSha256", hex(sha256(padded)))
      )
    }
    root.put(
      "vault_index",
      JSONObject()
        .put("why", "the index key label and the padding, which must agree or one app cannot " +
          "read a listing the other wrote")
        .put("dek", hex(dek))
        .put("indexKey", hex(VaultIndexCodec.indexKey(dek)))
        .put("associatedData", VaultIndexCodec.AAD)
        .put("padBoundary", VaultIndexCodec.PAD_BOUNDARY)
        .put("padding", padSamples)
        .put("listing", listing)
        .put("sealed", base64(VaultIndexCodec.seal(dek, listing)))
    )

    // --- a whole .avsbck container -----------------------------------------------------------
    // The desktop has to open backups the phone wrote: the pairing protocol names them as the
    // only route for vault contents between devices, and the desktop cannot read one today.
    // Nothing short of a real file from the real writer proves that it can.
    val container = buildContainer()
    val entries = JSONArray()
    readContainer(container) { sectionId, entryHeader, digest ->
      entries.put(
        JSONObject().put("section", sectionId).put("name", entryHeader.name)
          .put("kind", entryHeader.kind).put("size", entryHeader.size).put("sha256", digest)
      )
    }
    root.put(
      "container",
      JSONObject()
        .put("why", "a complete backup written by the shipping writer, so the C++ reader is " +
          "held to a real file rather than to its own idea of the format")
        .put("passphrase", CONTAINER_SECRET)
        .put("secretKind", BackupFormat.SECRET_KIND_PASSPHRASE)
        .put("base64", base64(container))
        .put("entries", entries)
    )

    return root
  }

  /** Standard base64, so neither side needs android.util.Base64. */
  private fun base64(bytes: ByteArray): String = java.util.Base64.getEncoder().encodeToString(bytes)

  private fun unbase64(text: String): ByteArray = java.util.Base64.getDecoder().decode(text)

  /** A small backup with two sections, so section ordering and keying are both exercised. */
  private fun buildContainer(): ByteArray {
    val settings = JSONObject().put("theme", "dark").put("variant", "zinc").toString()
      .toByteArray(Charsets.UTF_8)
    val track = patternBytes(9000, 7L)
    val cover = patternBytes(300, 8L)

    val out = ByteArrayOutputStream()
    BackupContainer.write(
      output = out,
      secrets = listOf(containerSecret()),
      sections = listOf(
        BackupContainer.PlannedSection(
          id = BackupFormat.SECTION_SETTINGS,
          itemCount = 1,
          plaintextBytes = settings.size.toLong(),
        ) { sink ->
          sink.add(
            BackupFormat.EntryHeader(
              BackupSections.BLOB_APP_SETTINGS, settings.size.toLong(),
              BackupSections.KIND_BLOB,
              JSONObject().put("blobId", BackupSections.BLOB_APP_SETTINGS),
            )
          ) { it.write(settings) }
        },
        BackupContainer.PlannedSection(
          id = BackupFormat.SECTION_MUSIC,
          itemCount = 2,
          plaintextBytes = (track.size + cover.size).toLong(),
        ) { sink ->
          sink.add(
            BackupFormat.EntryHeader(
              "cover.jpg", cover.size.toLong(), BackupSections.KIND_THUMBNAIL,
              JSONObject().put("ownerId", "track-1"),
            )
          ) { it.write(cover) }
          sink.add(
            BackupFormat.EntryHeader(
              "A Track.flac", track.size.toLong(), BackupSections.KIND_MEDIA,
              JSONObject().put("songId", "track-1").put("title", "A Track"),
            )
          ) { it.write(track) }
        },
      ),
      appVersion = "0.0.0-vectors",
      appVersionCode = 1,
      createdAt = 1700000000000L,
      // Cheap on purpose: this file is opened by every run of two test suites.
      kdf = BackupCrypto.KdfParams(memoryKiB = 8192, iterations = 1, parallelism = 4),
    )
    return out.toByteArray()
  }

  private fun containerSecret() = BackupContainer.SlotSecret(
    BackupFormat.DEFAULT_KEY_SLOT,
    CONTAINER_SECRET.toCharArray(),
    BackupFormat.SECRET_KIND_PASSPHRASE,
  )

  private fun readContainer(
    bytes: ByteArray,
    onEntry: (String, BackupFormat.EntryHeader, String) -> Unit,
  ) {
    val input = bytes.inputStream()
    val containerHeader = BackupContainer.peek(input)
    BackupContainer.read(
      input = input,
      header = containerHeader,
      secrets = listOf(containerSecret()),
      sectionsToRestore = containerHeader.sections.map { it.id }.toSet(),
    ) { entry ->
      val payload = entry.payload.readBytes()
      entry.verifiedTrailer()
      onEntry(entry.sectionId, entry.header, hex(sha256(payload)))
    }
  }

  // ---- the tests -------------------------------------------------------------------------

  @Test
  fun vectorsAreCurrent() {
    val built = build()
    if (System.getProperty("arsivinyo.vectors.write") == "1") {
      vectorsFile.parentFile?.mkdirs()
      vectorsFile.writeText(built.toString(2) + "\n")
      println("wrote ${vectorsFile.absolutePath}")
      return
    }
    assertTrue(
      "shared/crypto/VECTORS.json is missing. Generate it with " +
        "-Darsivinyo.vectors.write=1 and commit it.",
      vectorsFile.exists()
    )
    val onDisk = JSONObject(vectorsFile.readText())

    // Compare the parts that are derived, not the whole document: a reordered key or a
    // reflowed comment is not a drift, but a changed byte anywhere below is.
    assertEquals("argon2id vectors have drifted",
      built.getJSONArray("argon2id").toString(), onDisk.getJSONArray("argon2id").toString())
    assertEquals("hkdf vectors have drifted",
      built.getJSONArray("hkdf_sha256").toString(), onDisk.getJSONArray("hkdf_sha256").toString())
    assertEquals("avsbck subkey labels have drifted",
      built.getJSONObject("avsbck_subkeys").toString(),
      onDisk.getJSONObject("avsbck_subkeys").toString())
    assertEquals("segment nonce layout has drifted",
      built.getJSONObject("segment_nonce").toString(),
      onDisk.getJSONObject("segment_nonce").toString())
  }

  @Test
  fun theRecordedVaultIndexStillOpens() {
    if (!vectorsFile.exists()) return
    val spec = JSONObject(vectorsFile.readText()).getJSONObject("vault_index")
    val dek = unhex(spec.getString("dek"))
    assertEquals("the index key label has drifted",
      spec.getString("indexKey"), hex(VaultIndexCodec.indexKey(dek)))
    assertEquals("a sealed listing no longer opens",
      spec.getString("listing"), VaultIndexCodec.open(dek, unbase64(spec.getString("sealed"))))
    val padding = spec.getJSONArray("padding")
    for (i in 0 until padding.length()) {
      val case = padding.getJSONObject(i)
      val padded = VaultIndexCodec.pad(patternBytes(case.getInt("length"), case.getLong("length")))
      assertEquals("padded length for ${case.getInt("length")}",
        case.getInt("paddedLength"), padded.size)
      assertEquals("padded bytes for ${case.getInt("length")}",
        case.getString("paddedSha256"), hex(sha256(padded)))
    }
  }

  @Test
  fun theRecordedContainerStillOpens() {
    if (!vectorsFile.exists()) return
    val spec = JSONObject(vectorsFile.readText()).getJSONObject("container")
    val expected = spec.getJSONArray("entries")
    val seen = mutableListOf<String>()
    readContainer(unbase64(spec.getString("base64"))) { sectionId, entryHeader, digest ->
      seen.add(sectionId + "/" + entryHeader.name + "/" + entryHeader.kind + "/" +
        entryHeader.size + "/" + digest)
    }
    val want = (0 until expected.length()).map {
      val e = expected.getJSONObject(it)
      e.getString("section") + "/" + e.getString("name") + "/" + e.getString("kind") + "/" +
        e.getLong("size") + "/" + e.getString("sha256")
    }
    assertEquals("the recorded container no longer reads back the same", want, seen)
  }

  @Test
  fun theRecordedCiphertextsStillDecrypt() {
    if (!vectorsFile.exists()) return
    val cases = JSONObject(vectorsFile.readText()).getJSONArray("aead_stream")
    for (i in 0 until cases.length()) {
      val case = cases.getJSONObject(i)
      val plainSpec = case.getJSONObject("plaintext")
      val expected = patternBytes(plainSpec.getInt("length"), plainSpec.getLong("seed"))
      // Re-encrypting gives a different header every time, so the check that Kotlin can make
      // is a round trip at the recorded length, plus the recorded overhead being right.
      val key = unhex(case.getString("key"))
      val aad = case.getString("associatedData").toByteArray(Charsets.UTF_8)
      val produced = tinkEncrypt(key, aad, expected)
      assertEquals(
        "ciphertext length for ${plainSpec.getInt("length")} bytes",
        case.getInt("ciphertextLength"), produced.size
      )
      val streaming = AesGcmHkdfStreaming(key, "HmacSha256", 32, SEGMENT, 0)
      val back = streaming.newDecryptingStream(produced.inputStream(), aad).readBytes()
      assertTrue("round trip at ${plainSpec.getInt("length")} bytes", expected.contentEquals(back))
    }
  }

  private companion object {
    const val SEGMENT = 1 shl 20
    const val AAD = "vault"
    const val CONTAINER_SECRET = "a correct horse battery staple"
  }
}
