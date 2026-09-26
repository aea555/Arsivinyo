package expo.modules.localdownloader.backup

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/**
 * The `.avsbck` container format — layout, header model and entry framing.
 *
 * A backup outlives the build that wrote it, so everything a future version needs in
 * order to derive the key is stored in the clear, in a versioned header:
 *
 * ```
 *   magic          7 bytes    "AVSBCK\0"
 *   formatVersion  u16 BE
 *   headerLength   u32 BE
 *   header         JSON, UTF-8, plaintext
 *   payloads       one encrypted blob per section, in header order
 * ```
 *
 * Only the KDF parameters, salts and rough sizes are plaintext. Filenames and per-item
 * metadata live *inside* the encrypted region (see [writeEntryHeader]), so a backup on
 * cloud storage does not leak what it holds — only roughly how much.
 *
 * **Why sections carry no offsets.** A section's ciphertext length is not known until it
 * has been written, and the output here is usually a SAF stream that cannot be seeked back
 * to patch a header. Buffering a section to scratch space first would mean finding room for
 * a second copy of a vault that may be gigabytes. Instead each section is self-delimiting:
 * its ciphertext is written as length-prefixed chunks ending in a zero length (see
 * [ChunkedOutputStream]). Both reading and writing stay strictly forward-only, and a
 * section can be skipped without decrypting it. The cost is four bytes per megabyte.
 *
 * Each section is a single [com.google.crypto.tink.subtle.AesGcmHkdfStreaming] stream
 * whose plaintext is a sequence of framed entries:
 *
 * ```
 *   repeat:  u32 BE entryHeaderLength | entryHeader JSON | payload bytes
 *   end:     u32 BE 0
 * ```
 *
 * Framing inside the encrypted stream (rather than as a table in the plaintext header)
 * is what keeps names hidden, and it means the writer never needs to know an entry count
 * or a total size in advance — important when the source is a `content://` stream whose
 * length the system may not report.
 */
object BackupFormat {
  /** `AVSBCK\0`. The trailing NUL keeps the magic from matching a text file by accident. */
  val MAGIC: ByteArray = byteArrayOf(0x41, 0x56, 0x53, 0x42, 0x43, 0x4B, 0x00)

  const val FILE_EXTENSION = "avsbck"
  const val MIME_TYPE = "application/octet-stream"

  /** Bumped only for changes a previous reader could not survive. */
  const val FORMAT_VERSION = 1

  /** Refuse absurd headers rather than allocating whatever a corrupt file claims. */
  const val MAX_HEADER_BYTES = 1 shl 20
  const val MAX_ENTRY_HEADER_BYTES = 1 shl 16

  /** Section identifiers. These are wire values — never rename one in place. */
  const val SECTION_VAULT = "vault"
  const val SECTION_MUSIC = "music"
  const val SECTION_MEMES = "memes"
  const val SECTION_SETTINGS = "settings"
  const val SECTION_COOKIES = "cookies"

  val ALL_SECTIONS = listOf(SECTION_VAULT, SECTION_MUSIC, SECTION_MEMES, SECTION_SETTINGS, SECTION_COOKIES)

  /** The key slot every section points at when one secret protects the whole file. */
  const val DEFAULT_KEY_SLOT = "default"

  const val SECRET_KIND_PASSWORD = "password"
  const val SECRET_KIND_PASSPHRASE = "passphrase"

  /**
   * How a key slot's secret was derived. Stored so the importer can tell the user which
   * kind of secret a slot expects instead of making them guess.
   */
  data class KeySlot(
    val id: String,
    val salt: ByteArray,
    val verifier: ByteArray,
    val secretKind: String,
  ) {
    // Data classes compare ByteArray by identity; these are compared in tests and in
    // duplicate checks, so both halves are spelled out.
    override fun equals(other: Any?): Boolean {
      if (this === other) return true
      if (other !is KeySlot) return false
      return id == other.id &&
        salt.contentEquals(other.salt) &&
        verifier.contentEquals(other.verifier) &&
        secretKind == other.secretKind
    }

    override fun hashCode(): Int {
      var result = id.hashCode()
      result = 31 * result + salt.contentHashCode()
      result = 31 * result + verifier.contentHashCode()
      result = 31 * result + secretKind.hashCode()
      return result
    }
  }

  /**
   * A section declared in the header: what it is and which slot unlocks it. Sections appear
   * in the payload area in this order, each self-delimiting, so no offset is needed.
   *
   * [itemCount] and [plaintextBytes] are advisory — they drive the import preview so the
   * user can see what a file holds before committing to a restore. They are plaintext, so
   * a reader must never trust them for allocation; the framing inside the section is the
   * authority on what is actually there.
   */
  data class SectionEntry(
    val id: String,
    val keySlot: String,
    val itemCount: Int,
    val plaintextBytes: Long,
  )

  data class Header(
    val formatVersion: Int,
    val createdAt: Long,
    val appVersion: String,
    val appVersionCode: Int,
    val kdf: BackupCrypto.KdfParams,
    val keySlots: List<KeySlot>,
    val sections: List<SectionEntry>,
  ) {
    fun slot(id: String): KeySlot? = keySlots.firstOrNull { it.id == id }

    fun section(id: String): SectionEntry? = sections.firstOrNull { it.id == id }
  }

  // ---------------------------------------------------------------- header codec

  fun encodeHeader(header: Header): ByteArray {
    val slots = JSONArray()
    header.keySlots.forEach { slot ->
      slots.put(
        JSONObject().apply {
          put("id", slot.id)
          put("salt", Base64Codec.encode(slot.salt))
          put("verifier", Base64Codec.encode(slot.verifier))
          put("secretKind", slot.secretKind)
        }
      )
    }

    val sections = JSONArray()
    header.sections.forEach { section ->
      sections.put(
        JSONObject().apply {
          put("id", section.id)
          put("keySlot", section.keySlot)
          put("itemCount", section.itemCount)
          put("plaintextBytes", section.plaintextBytes)
        }
      )
    }

    val root = JSONObject().apply {
      put("formatVersion", header.formatVersion)
      put("createdAt", header.createdAt)
      put(
        "producer",
        JSONObject().apply {
          put("app", "arsivinyo")
          put("version", header.appVersion)
          put("versionCode", header.appVersionCode)
        }
      )
      put("kdf", BackupCrypto.encodeKdfParams(header.kdf))
      put("keySlots", slots)
      put("sections", sections)
    }

    return root.toString().toByteArray(Charsets.UTF_8)
  }

  fun decodeHeader(bytes: ByteArray): Header {
    val root = JSONObject(String(bytes, Charsets.UTF_8))
    val formatVersion = root.optInt("formatVersion", -1)
    if (formatVersion <= 0) {
      throw BackupFormatException("Backup header has no usable format version")
    }
    if (formatVersion > FORMAT_VERSION) {
      throw BackupFormatException(
        "This backup was written by a newer version of the app (format $formatVersion, " +
          "this build reads up to $FORMAT_VERSION)"
      )
    }

    val producer = root.optJSONObject("producer") ?: JSONObject()

    val slots = mutableListOf<KeySlot>()
    val slotsJson = root.optJSONArray("keySlots") ?: JSONArray()
    for (i in 0 until slotsJson.length()) {
      val slot = slotsJson.optJSONObject(i) ?: continue
      val id = slot.optString("id").takeIf { it.isNotBlank() }
        ?: throw BackupFormatException("Backup header has a key slot with no id")
      slots.add(
        KeySlot(
          id = id,
          salt = Base64Codec.decode(slot.optString("salt")),
          verifier = Base64Codec.decode(slot.optString("verifier")),
          secretKind = slot.optString("secretKind").ifBlank { SECRET_KIND_PASSPHRASE },
        )
      )
    }
    if (slots.isEmpty()) {
      throw BackupFormatException("Backup header declares no key slots")
    }

    val sections = mutableListOf<SectionEntry>()
    val sectionsJson = root.optJSONArray("sections") ?: JSONArray()
    for (i in 0 until sectionsJson.length()) {
      val section = sectionsJson.optJSONObject(i) ?: continue
      val id = section.optString("id").takeIf { it.isNotBlank() } ?: continue
      val keySlot = section.optString("keySlot").ifBlank { DEFAULT_KEY_SLOT }
      if (slots.none { it.id == keySlot }) {
        throw BackupFormatException("Section '$id' points at unknown key slot '$keySlot'")
      }
      sections.add(
        SectionEntry(
          id = id,
          keySlot = keySlot,
          itemCount = section.optInt("itemCount", 0),
          plaintextBytes = section.optLong("plaintextBytes", 0L),
        )
      )
    }
    if (sections.distinctBy { it.id }.size != sections.size) {
      throw BackupFormatException("Backup header declares the same section twice")
    }

    return Header(
      formatVersion = formatVersion,
      createdAt = root.optLong("createdAt", 0L),
      appVersion = producer.optString("version"),
      appVersionCode = producer.optInt("versionCode", 0),
      kdf = BackupCrypto.decodeKdfParams(
        root.optJSONObject("kdf")
          ?: throw BackupFormatException("Backup header has no KDF parameters")
      ),
      keySlots = slots,
      sections = sections,
    )
  }

  // ---------------------------------------------------------------- file preamble

  fun writePreamble(output: OutputStream, headerBytes: ByteArray) {
    output.write(MAGIC)
    writeU16(output, FORMAT_VERSION)
    writeU32(output, headerBytes.size.toLong())
    output.write(headerBytes)
  }

  /** Byte offset at which section payloads start, for a header of [headerLength] bytes. */
  fun payloadOrigin(headerLength: Int): Long = (MAGIC.size + 2 + 4 + headerLength).toLong()

  // ---------------------------------------------------------------- section framing

  /** Default chunk size. Four bytes of framing per megabyte is not worth tuning. */
  const val CHUNK_SIZE = 1 shl 20

  /** A single chunk header claiming more than this means the file is damaged. */
  const val MAX_CHUNK_BYTES = 64 shl 20

  /**
   * Writes whatever it is given as length-prefixed chunks, terminated by a zero length.
   * This is what lets a section be read back without the header knowing its size.
   *
   * [close] writes the terminator but deliberately does **not** close [sink] — several
   * sections share one output file, and Tink's encrypting stream closes what it wraps.
   */
  class ChunkedOutputStream(
    private val sink: OutputStream,
    private val chunkSize: Int = CHUNK_SIZE,
  ) : OutputStream() {
    private val buffer = ByteArray(chunkSize)
    private var filled = 0
    private var closed = false

    override fun write(b: Int) {
      if (filled == chunkSize) flushChunk()
      buffer[filled++] = b.toByte()
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
      var offset = off
      var remaining = len
      while (remaining > 0) {
        if (filled == chunkSize) flushChunk()
        val take = minOf(chunkSize - filled, remaining)
        System.arraycopy(b, offset, buffer, filled, take)
        filled += take
        offset += take
        remaining -= take
      }
    }

    private fun flushChunk() {
      if (filled == 0) return
      writeU32(sink, filled.toLong())
      sink.write(buffer, 0, filled)
      filled = 0
    }

    override fun flush() {
      flushChunk()
      sink.flush()
    }

    override fun close() {
      if (closed) return
      closed = true
      flushChunk()
      writeU32(sink, 0L) // section terminator
      sink.flush()
    }
  }

  /**
   * Reads back a [ChunkedOutputStream]. Reports end-of-stream at the section terminator, so
   * the wrapped decrypting stream sees a clean EOF and the caller can carry on reading the
   * next section from the same source.
   *
   * [close] does not close [source], for the same reason as above.
   */
  class ChunkedInputStream(private val source: InputStream) : InputStream() {
    private var remainingInChunk = 0
    private var finished = false

    /** @return false once the terminator has been consumed. */
    private fun advance(): Boolean {
      if (finished) return false
      if (remainingInChunk > 0) return true
      val length = readU32(source)
      if (length == 0L) {
        finished = true
        return false
      }
      if (length > MAX_CHUNK_BYTES) {
        throw BackupFormatException("Backup section framing is damaged")
      }
      remainingInChunk = length.toInt()
      return true
    }

    override fun read(): Int {
      if (!advance()) return -1
      val one = ByteArray(1)
      val read = source.read(one, 0, 1)
      if (read <= 0) throw BackupFormatException("Backup section is truncated")
      remainingInChunk--
      return one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
      if (len == 0) return 0
      if (!advance()) return -1
      val read = source.read(b, off, minOf(len, remainingInChunk))
      if (read <= 0) throw BackupFormatException("Backup section is truncated")
      remainingInChunk -= read
      return read
    }

    override fun available(): Int = remainingInChunk

    /** Consume the rest of this section without decrypting it. */
    fun skipSection() {
      while (advance()) {
        var toSkip = remainingInChunk.toLong()
        while (toSkip > 0) {
          val skipped = source.skip(toSkip)
          if (skipped <= 0) throw BackupFormatException("Backup section is truncated")
          toSkip -= skipped
        }
        remainingInChunk = 0
      }
    }
  }

  /**
   * Read and validate the preamble, leaving [input] positioned at the first payload byte.
   * Throws [BackupFormatException] with a message worth showing the user — this is the
   * first thing that runs when someone picks the wrong file.
   */
  fun readHeader(input: InputStream): Header {
    val magic = readExactly(input, MAGIC.size) { "This file is too short to be a backup" }
    if (!magic.contentEquals(MAGIC)) {
      throw BackupFormatException("This is not an Arsivinyo backup file")
    }
    val version = readU16(input)
    if (version > FORMAT_VERSION) {
      throw BackupFormatException(
        "This backup was written by a newer version of the app (format $version)"
      )
    }
    val headerLength = readU32(input)
    if (headerLength <= 0L || headerLength > MAX_HEADER_BYTES) {
      throw BackupFormatException("Backup header is missing or damaged")
    }
    val headerBytes = readExactly(input, headerLength.toInt()) { "Backup header is truncated" }
    return decodeHeader(headerBytes)
  }

  // ---------------------------------------------------------------- entry framing

  /**
   * Metadata for one item inside a section.
   *
   * [size] is **advisory** — it drives progress reporting and the cheap duplicate
   * pre-filter, but the payload is chunk-framed and therefore self-delimiting, so a wrong
   * size cannot desynchronise the reader. That matters because plaintext size is not
   * cheaply knowable for every vault cipher version; the authoritative size and content
   * hash arrive in the [EntryTrailer] once the payload has streamed past.
   */
  data class EntryHeader(
    val name: String,
    val size: Long,
    val kind: String,
    val meta: JSONObject = JSONObject(),
  )

  /**
   * Written after an entry's payload. The hash is over the *plaintext*, so the same file
   * backed up under two different secrets still deduplicates on import.
   *
   * It lives here rather than in the header so that exporting never has to read a file
   * twice — hashing happens as the bytes stream past. The import side pays for that with a
   * two-stage duplicate check: filter on size first, and only when a size collides does it
   * need the hash, which is known by the time the payload has been consumed.
   */
  data class EntryTrailer(
    val size: Long,
    val sha256: String,
    /**
     * False when the exporter could not read the whole item — the source file was deleted
     * or became unreadable partway through.
     *
     * This flag is why a failed item cannot simply be left out: by the time a payload
     * fails, its header is already written, so the entry must still be closed. The size and
     * hash below describe what was actually written, so they would *verify* against a
     * truncated file and a restore would store half a video believing it intact. The reader
     * checks this flag and refuses the item.
     *
     * Absent in a trailer means complete, so files written before this existed still read.
     */
    val complete: Boolean = true,
  )

  fun writeEntryHeader(output: OutputStream, entry: EntryHeader) {
    val json = JSONObject().apply {
      put("name", entry.name)
      put("size", entry.size)
      put("kind", entry.kind)
      put("meta", entry.meta)
    }.toString().toByteArray(Charsets.UTF_8)
    if (json.size > MAX_ENTRY_HEADER_BYTES) {
      throw BackupFormatException("Entry metadata for '${entry.name}' is too large")
    }
    writeU32(output, json.size.toLong())
    output.write(json)
  }

  fun writeSectionTerminator(output: OutputStream) = writeU32(output, 0L)

  /** Returns null at the section terminator. */
  fun readEntryHeader(input: InputStream): EntryHeader? {
    val length = try {
      readU32(input)
    } catch (e: EOFException) {
      // A section that ends without its terminator is truncated. The AEAD tag would also
      // fail, but this gives the clearer message.
      throw BackupFormatException("Backup section ended unexpectedly", e)
    }
    if (length == 0L) return null
    if (length > MAX_ENTRY_HEADER_BYTES) {
      throw BackupFormatException("Backup entry metadata is damaged")
    }
    val bytes = readExactly(input, length.toInt()) { "Backup entry metadata is truncated" }
    val json = JSONObject(String(bytes, Charsets.UTF_8))
    return EntryHeader(
      name = json.optString("name"),
      size = json.optLong("size", -1L),
      kind = json.optString("kind"),
      meta = json.optJSONObject("meta") ?: JSONObject(),
    )
  }

  fun writeEntryTrailer(output: OutputStream, trailer: EntryTrailer) {
    val json = JSONObject().apply {
      put("size", trailer.size)
      put("sha256", trailer.sha256)
      // Only written when false, so a normal backup carries no extra bytes per entry.
      if (!trailer.complete) put("incomplete", true)
    }.toString().toByteArray(Charsets.UTF_8)
    writeU32(output, json.size.toLong())
    output.write(json)
  }

  fun readEntryTrailer(input: InputStream): EntryTrailer {
    val length = readU32(input)
    if (length <= 0L || length > MAX_ENTRY_HEADER_BYTES) {
      throw BackupFormatException("Backup entry trailer is missing or damaged")
    }
    val bytes = readExactly(input, length.toInt()) { "Backup entry trailer is truncated" }
    val json = JSONObject(String(bytes, Charsets.UTF_8))
    return EntryTrailer(
      size = json.optLong("size", -1L).also {
        if (it < 0L) throw BackupFormatException("Backup entry trailer has no size")
      },
      sha256 = json.optString("sha256"),
      complete = !json.optBoolean("incomplete", false),
    )
  }

  // ---------------------------------------------------------------- primitives

  private fun writeU16(output: OutputStream, value: Int) {
    output.write((value ushr 8) and 0xFF)
    output.write(value and 0xFF)
  }

  private fun writeU32(output: OutputStream, value: Long) {
    output.write(((value ushr 24) and 0xFF).toInt())
    output.write(((value ushr 16) and 0xFF).toInt())
    output.write(((value ushr 8) and 0xFF).toInt())
    output.write((value and 0xFF).toInt())
  }

  private fun readU16(input: InputStream): Int {
    val bytes = readExactly(input, 2) { "Backup file is truncated" }
    return ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
  }

  private fun readU32(input: InputStream): Long {
    val bytes = readExactly(input, 4) { "Backup file is truncated" }
    return ((bytes[0].toLong() and 0xFF) shl 24) or
      ((bytes[1].toLong() and 0xFF) shl 16) or
      ((bytes[2].toLong() and 0xFF) shl 8) or
      (bytes[3].toLong() and 0xFF)
  }

  private inline fun readExactly(
    input: InputStream,
    count: Int,
    message: () -> String,
  ): ByteArray {
    val out = ByteArray(count)
    var filled = 0
    while (filled < count) {
      val read = input.read(out, filled, count - filled)
      if (read < 0) {
        if (filled == 0 && count == 4) throw EOFException("end of stream")
        throw BackupFormatException(message())
      }
      filled += read
    }
    return out
  }

  /**
   * Base64 without the Android `android.util.Base64` dependency, so the format layer stays
   * runnable under plain JVM unit tests.
   */
  internal object Base64Codec {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    fun encode(bytes: ByteArray): String {
      val out = StringBuilder(((bytes.size + 2) / 3) * 4)
      var i = 0
      while (i + 2 < bytes.size) {
        val n = ((bytes[i].toInt() and 0xFF) shl 16) or
          ((bytes[i + 1].toInt() and 0xFF) shl 8) or
          (bytes[i + 2].toInt() and 0xFF)
        out.append(ALPHABET[(n ushr 18) and 0x3F])
        out.append(ALPHABET[(n ushr 12) and 0x3F])
        out.append(ALPHABET[(n ushr 6) and 0x3F])
        out.append(ALPHABET[n and 0x3F])
        i += 3
      }
      when (bytes.size - i) {
        1 -> {
          val n = (bytes[i].toInt() and 0xFF) shl 16
          out.append(ALPHABET[(n ushr 18) and 0x3F])
          out.append(ALPHABET[(n ushr 12) and 0x3F])
          out.append("==")
        }
        2 -> {
          val n = ((bytes[i].toInt() and 0xFF) shl 16) or ((bytes[i + 1].toInt() and 0xFF) shl 8)
          out.append(ALPHABET[(n ushr 18) and 0x3F])
          out.append(ALPHABET[(n ushr 12) and 0x3F])
          out.append(ALPHABET[(n ushr 6) and 0x3F])
          out.append('=')
        }
      }
      return out.toString()
    }

    fun decode(text: String): ByteArray {
      val clean = text.filter { it != '\n' && it != '\r' && it != '=' }
      val out = ByteArrayOutputStream(clean.length * 3 / 4)
      var buffer = 0
      var bits = 0
      clean.forEach { ch ->
        val value = ALPHABET.indexOf(ch)
        if (value < 0) throw BackupFormatException("Backup header contains invalid base64")
        buffer = (buffer shl 6) or value
        bits += 6
        if (bits >= 8) {
          bits -= 8
          out.write((buffer ushr bits) and 0xFF)
        }
      }
      return out.toByteArray()
    }
  }
}

/** Anything wrong with the container itself, as opposed to a wrong secret. */
class BackupFormatException(message: String, cause: Throwable? = null) :
  Exception(message, cause)

/** The supplied password or passphrase did not open a key slot. */
class BackupSecretException(message: String) : Exception(message)
