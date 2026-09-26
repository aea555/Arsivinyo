package expo.modules.localdownloader.backup

import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream

/**
 * Narrow views onto the app's stores, so the collectors and restore targets below can be
 * written — and tested — without a Context, MediaStore or the Keystore.
 *
 * `LocalDownloaderModule` implements these against its own private helpers; the fakes in
 * `BackupPortsTest` implement them against maps.
 *
 * **Duplicate detection without a schema change.** None of the stores record content
 * hashes, and adding a field to every index would be a migration. Instead each port answers
 * two questions: what plaintext sizes it already holds, and — only when a size collides —
 * the hash of the specific colliding items. Sizes are cheap everywhere: music files report
 * theirs directly, and for vault-cipher v4 `plaintextLength` reads it from the stream header
 * without decrypting the body. Older vault entries cannot answer cheaply, so they report
 * `null` and are treated as possible collisions, which costs a decrypt only for items that
 * are genuinely size-ambiguous.
 */
object BackupPorts {

  // ------------------------------------------------------------------ vault

  data class VaultRecord(
    val id: String,
    val title: String,
    val mimeType: String,
    /** The index entry, carried through so a restore can put back tags and folders. */
    val meta: JSONObject,
    /** Null when the cipher version cannot report it without decrypting. */
    val plaintextSize: Long?,
  )

  interface VaultPort {
    fun list(): List<VaultRecord>

    /** Decrypt straight into [out]. Never lands plaintext on disk. */
    fun writePlaintext(record: VaultRecord, out: OutputStream)

    /** SHA-256 of the decrypted content. Only called for size collisions. */
    fun hashOf(record: VaultRecord): String

    /**
     * Store a restored video. [staged] holds plaintext; the implementation re-encrypts it
     * under this device's Keystore-backed key and regenerates the thumbnail from the video
     * itself — which is why vault thumbnails are never put in a backup.
     *
     * @return the new entry id.
     */
    fun restore(staged: java.io.File, name: String, mimeType: String, meta: JSONObject): String
  }

  // ------------------------------------------------------------------ music

  data class MusicRecord(
    val id: String,
    val fileName: String,
    val sizeBytes: Long,
    val meta: JSONObject,
    val thumbnailPath: String?,
  )

  interface MusicPort {
    fun list(): List<MusicRecord>
    fun open(record: MusicRecord): InputStream
    fun openThumbnail(record: MusicRecord): InputStream?
    fun hashOf(record: MusicRecord): String

    /** Playlists and favourites, as stored in `sounds/index.json`. */
    fun playlistsJson(): JSONObject

    /** The natively-stored auto-apply preset configuration, if any. */
    fun autoPresetConfig(): JSONObject?

    /**
     * Store a restored track. [thumbnail] is the staged cover art, which arrives *before*
     * the track precisely so it can be passed here — the sounds store only accepts artwork
     * when a track is registered.
     */
    fun restore(
      staged: java.io.File,
      name: String,
      meta: JSONObject,
      thumbnail: java.io.File?,
    ): String

    /**
     * [idMap] maps the song ids recorded in the backup to the ids they were given on this
     * device. Playlists and favourites are stored as lists of song ids, so without it every
     * restored playlist would point at ids that do not exist here.
     *
     * Safe to apply directly: the blob is the last item in the section, so every track has
     * already been committed by the time this runs.
     */
    fun restorePlaylists(json: JSONObject, idMap: Map<String, String>)
    fun restoreAutoPresetConfig(json: JSONObject)
  }

  // ------------------------------------------------------------------ memes

  data class MemeRecord(
    val id: String,
    val fileName: String,
    val sizeBytes: Long,
    /** `memeId`, `sha256`, `addedAt`, `taggedAt`, and `meme`: kind, source, labels by name. */
    val meta: JSONObject,
  )

  /** The memes that are not private. Private ones travel in the vault section. */
  interface MemesPort {
    fun list(): List<MemeRecord>
    fun open(record: MemeRecord): InputStream

    /** Tags and people by name, leaving out any that only private memes use. */
    fun vocabulary(): JSONObject

    /** The local meme with this content, if there is one. */
    fun existingIdFor(sha256: String): String?

    /** The same meme is already here: it only gains the labels in [meta]. */
    fun mergeLabels(existingId: String, meta: JSONObject)

    fun restore(staged: java.io.File, name: String, meta: JSONObject): String
    fun restoreVocabulary(json: JSONObject)
  }

  // ------------------------------------------------------------------ cookies

  data class CookieRecord(
    val platform: String,
    val profileName: String,
    val isDefault: Boolean,
  )

  interface CookiePort {
    fun list(): List<CookieRecord>

    /**
     * Whether a profile with this identity is already stored.
     *
     * Cookie profiles are deduplicated by *name*, not by content hash, unlike every other
     * item. A profile is a user-assigned identity — "youtube/main" — so a hash comparison
     * would let a stale backup overwrite a session that has since been signed in again,
     * silently replacing a working login with a dead one. Files are the opposite case:
     * their names collide meaninglessly, so those are compared by content.
     */
    fun exists(platform: String, profileName: String): Boolean

    /** The decrypted cookie jar. Re-encrypted under this device's key on restore. */
    fun readPlaintext(record: CookieRecord): ByteArray

    fun restore(platform: String, profileName: String, isDefault: Boolean, plaintext: ByteArray)
  }

  // ------------------------------------------------------------------ collectors

  fun collectVault(port: VaultPort): List<BackupSections.BackupItem> {
    val items = mutableListOf<BackupSections.BackupItem>()
    port.list().forEach { record ->
      items.add(
        BackupSections.BackupItem(
          name = record.title,
          kind = BackupSections.KIND_MEDIA,
          size = record.plaintextSize ?: 0L,
          meta = JSONObject(record.meta.toString()).apply {
            put("vaultId", record.id)
            put("mimeType", record.mimeType)
            // The stored blob is Keystore-encrypted and cannot travel, so these describe
            // something that no longer applies to what is in the backup.
            remove("encFileName")
            remove("cipherVersion")
            remove("sizeBytesEncrypted")
          },
          writePayload = { out -> port.writePlaintext(record, out) },
        )
      )
    }
    return items
  }

  fun collectMusic(port: MusicPort): List<BackupSections.BackupItem> {
    val items = mutableListOf<BackupSections.BackupItem>()
    port.list().forEach { record ->
      // Artwork first: the restore side can only attach it while registering the track, so
      // it has to be staged and waiting by the time the track itself arrives.
      if (record.thumbnailPath != null) {
        items.add(
          BackupSections.BackupItem(
            name = record.thumbnailPath.substringAfterLast('/'),
            kind = BackupSections.KIND_THUMBNAIL,
            size = 0L,
            meta = JSONObject().apply { put("ownerId", record.id) },
            writePayload = { out ->
              port.openThumbnail(record)?.use { BackupContainer.copy(it, out) }
            },
          )
        )
      }
      items.add(
        BackupSections.BackupItem(
          name = record.fileName,
          kind = BackupSections.KIND_MEDIA,
          size = record.sizeBytes,
          meta = JSONObject(record.meta.toString()).apply { put("songId", record.id) },
          writePayload = { out -> port.open(record).use { BackupContainer.copy(it, out) } },
        )
      )
    }
    // Playlists, favourites and the auto-apply config belong with the music they describe,
    // not with app settings.
    items.add(BackupSections.blobItem(BackupSections.BLOB_MUSIC_INDEX, port.playlistsJson()))
    port.autoPresetConfig()?.let {
      items.add(BackupSections.blobItem(BackupSections.BLOB_AUTO_PRESETS, it))
    }
    return items
  }

  fun collectMemes(port: MemesPort): List<BackupSections.BackupItem> {
    val items = port.list().map { record ->
      BackupSections.BackupItem(
        name = record.fileName,
        kind = BackupSections.KIND_MEDIA,
        size = record.sizeBytes,
        meta = record.meta,
        writePayload = { out -> port.open(record).use { BackupContainer.copy(it, out) } },
      )
    }
    // The vocabulary last, so tags no meme uses yet, and their facets, survive too.
    return items + BackupSections.blobItem(BackupSections.BLOB_MEMES_INDEX, port.vocabulary())
  }

  fun collectCookies(port: CookiePort): List<BackupSections.BackupItem> =
    port.list().map { record ->
      val plaintext = port.readPlaintext(record)
      BackupSections.BackupItem(
        name = "${record.platform}/${record.profileName}",
        kind = BackupSections.KIND_COOKIE_PROFILE,
        size = plaintext.size.toLong(),
        meta = JSONObject().apply {
          put("platform", record.platform)
          put("profileName", record.profileName)
          put("isDefault", record.isDefault)
        },
        writePayload = { out -> out.write(plaintext) },
      )
    }

  fun collectSettings(settings: JSONObject): List<BackupSections.BackupItem> =
    listOf(BackupSections.blobItem(BackupSections.BLOB_APP_SETTINGS, settings))

  // ------------------------------------------------------------------ restore targets

  /**
   * Duplicate index built from whatever sizes a port can report cheaply.
   *
   * [unknownSizeCandidates] are items whose size could not be determined without decrypting
   * them. They are treated as always potentially colliding — the conservative answer, since
   * reporting "no collision" for them would let a genuine duplicate through.
   */
  private class PortDuplicateIndex<T>(
    private val bySize: Map<Long, List<T>>,
    private val unknownSizeCandidates: List<T>,
    private val hashOf: (T) -> String,
    private val idOf: (T) -> String,
  ) : BackupSections.DuplicateIndex {
    private val computed = mutableMapOf<Long, Map<String, String>>()
    private val unknownHashes: Map<String, String> by lazy { hashIndex(unknownSizeCandidates) }

    private fun hashIndex(items: List<T>): Map<String, String> =
      items.mapNotNull { item ->
        runCatching { hashOf(item) to idOf(item) }.getOrNull()
      }.toMap()

    override fun couldCollideAt(size: Long): Boolean =
      bySize.containsKey(size) || unknownSizeCandidates.isNotEmpty()

    override fun existingIdFor(sha256: String, size: Long): String? {
      unknownHashes[sha256]?.let { return it }
      // Hash only the stored items that share this exact size, and remember the result so a
      // backup holding many same-sized items does not rehash them for each one.
      val candidates = bySize[size] ?: return null
      return computed.getOrPut(size) { hashIndex(candidates) }[sha256]
    }
  }

  private fun <T> duplicateIndexFor(
    records: List<T>,
    sizeOf: (T) -> Long?,
    hashOf: (T) -> String,
    idOf: (T) -> String,
  ): BackupSections.DuplicateIndex {
    if (records.isEmpty()) return BackupSections.DuplicateIndex.EMPTY
    val known = records.filter { sizeOf(it) != null }.groupBy { sizeOf(it)!! }
    val unknown = records.filter { sizeOf(it) == null }
    return PortDuplicateIndex(known, unknown, hashOf, idOf)
  }

  /**
   * Stages a payload before committing it, so an item that turns out to be a duplicate can
   * be dropped without having been written into the library.
   */
  interface Staging {
    /** A scratch file the payload can be streamed into. */
    fun newStagingFile(name: String): java.io.File
  }

  /**
   * Staged media is written to a scratch file first and only handed to the store once its
   * content hash is known. Without that, a duplicate would already be in the library by the
   * time it could be recognised, and undoing it would mean deleting something just written.
   *
   * This costs nothing extra: both the vault importer and the sounds store take a *file*
   * path, so the payload had to land on disk regardless.
   */
  private class StagedMedia(val file: java.io.File, val header: BackupFormat.EntryHeader)

  private fun stage(
    staging: Staging,
    header: BackupFormat.EntryHeader,
    payload: InputStream,
  ): StagedMedia {
    val file = staging.newStagingFile(header.name)
    file.outputStream().use { BackupContainer.copy(payload, it) }
    return StagedMedia(file, header)
  }

  fun vaultTarget(port: VaultPort, staging: Staging): BackupSections.RestoreTarget =
    object : BackupSections.RestoreTarget {
      private val existing by lazy {
        duplicateIndexFor(port.list(), { it.plaintextSize }, { port.hashOf(it) }, { it.id })
      }

      override fun duplicates() = existing

      override fun screen(header: BackupFormat.EntryHeader) =
        if (header.kind == BackupSections.KIND_MEDIA) {
          null
        } else {
          BackupSections.ItemOutcome.SKIPPED_UNWANTED
        }

      override fun store(header: BackupFormat.EntryHeader, payload: InputStream): Any? =
        stage(staging, header, payload)

      override fun discard(token: Any?) {
        (token as? StagedMedia)?.file?.delete()
      }

      override fun commit(token: Any?, trailer: BackupFormat.EntryTrailer) {
        val staged = token as? StagedMedia ?: return
        try {
          port.restore(
            staged.file,
            staged.header.name,
            staged.header.meta.optString("mimeType").ifBlank { "video/mp4" },
            staged.header.meta,
          )
        } finally {
          staged.file.delete()
        }
      }
    }

  fun musicTarget(port: MusicPort, staging: Staging): BackupSections.RestoreTarget =
    object : BackupSections.RestoreTarget {
      private val existing by lazy {
        duplicateIndexFor(port.list(), { it.sizeBytes }, { port.hashOf(it) }, { it.id })
      }

      /**
       * Cover art staged by the id of the track it belongs to. Artwork is emitted before its
       * track so it is waiting here when the track arrives — the sounds store only accepts
       * artwork at registration time, so it cannot be attached afterwards.
       */
      private val pendingArt = mutableMapOf<String, java.io.File>()

      /** Backup song id -> the id this device assigned, for remapping playlists. */
      private val restoredIds = mutableMapOf<String, String>()

      override fun duplicates() = existing

      // Cover art belongs to a track, and a blob is a settings write. Neither is a file
      // added to the library, so neither should be counted as one.
      override fun isBookkeeping(header: BackupFormat.EntryHeader) =
        header.kind == BackupSections.KIND_THUMBNAIL || header.kind == BackupSections.KIND_BLOB

      override fun store(header: BackupFormat.EntryHeader, payload: InputStream): Any? =
        when (header.kind) {
          BackupSections.KIND_THUMBNAIL -> {
            val owner = header.meta.optString("ownerId")
            if (owner.isNotBlank()) {
              pendingArt[owner] = stage(staging, header, payload).file
            }
            null
          }
          BackupSections.KIND_BLOB -> {
            val json = JSONObject(String(payload.readBytes(), Charsets.UTF_8))
            when (header.meta.optString("blobId")) {
              BackupSections.BLOB_MUSIC_INDEX -> port.restorePlaylists(json, restoredIds)
              BackupSections.BLOB_AUTO_PRESETS -> port.restoreAutoPresetConfig(json)
            }
            null
          }
          else -> stage(staging, header, payload)
        }

      override fun discard(token: Any?) {
        (token as? StagedMedia)?.file?.delete()
      }

      /**
       * A track already on the device still has to appear in restored playlists, so its
       * backup id is mapped onto the local one. Without this, restoring a second time — or
       * after an interrupted run — rebuilt every playlist empty and then dropped it.
       */
      override fun onDuplicate(header: BackupFormat.EntryHeader, existingId: String?) {
        val songId = header.meta.optString("songId")
        if (songId.isNotBlank() && existingId != null) restoredIds[songId] = existingId
      }

      override fun commit(token: Any?, trailer: BackupFormat.EntryTrailer) {
        val staged = token as? StagedMedia ?: return
        val songId = staged.header.meta.optString("songId")
        val art = pendingArt.remove(songId)
        try {
          val newId = port.restore(staged.file, staged.header.name, staged.header.meta, art)
          if (songId.isNotBlank()) restoredIds[songId] = newId
        } finally {
          staged.file.delete()
          art?.delete()
        }
      }
    }

  /**
   * Memes by content: one already here only gains the labels, whatever its name. Every size
   * could collide, because the collection keeps hashes and not sizes; the payload is staged
   * anyway, so the only cost is a lookup.
   */
  fun memesTarget(port: MemesPort, staging: Staging): BackupSections.RestoreTarget =
    object : BackupSections.RestoreTarget {
      private val index = object : BackupSections.DuplicateIndex {
        override fun couldCollideAt(size: Long) = true
        override fun existingIdFor(sha256: String, size: Long) = port.existingIdFor(sha256)
      }

      override fun duplicates() = index

      override fun screen(header: BackupFormat.EntryHeader) =
        if (header.kind == BackupSections.KIND_MEDIA || header.kind == BackupSections.KIND_BLOB) null
        else BackupSections.ItemOutcome.SKIPPED_UNWANTED

      override fun isBookkeeping(header: BackupFormat.EntryHeader) = header.kind == BackupSections.KIND_BLOB

      override fun store(header: BackupFormat.EntryHeader, payload: InputStream): Any? {
        if (header.kind == BackupSections.KIND_BLOB) {
          if (header.meta.optString("blobId") == BackupSections.BLOB_MEMES_INDEX) {
            port.restoreVocabulary(JSONObject(String(payload.readBytes(), Charsets.UTF_8)))
          }
          return null
        }
        return stage(staging, header, payload)
      }

      override fun discard(token: Any?) {
        (token as? StagedMedia)?.file?.delete()
      }

      override fun onDuplicate(header: BackupFormat.EntryHeader, existingId: String?) {
        if (existingId != null && header.kind == BackupSections.KIND_MEDIA) port.mergeLabels(existingId, header.meta)
      }

      override fun commit(token: Any?, trailer: BackupFormat.EntryTrailer) {
        val staged = token as? StagedMedia ?: return
        try {
          port.restore(staged.file, staged.header.name, JSONObject(staged.header.meta.toString()).put("sha256", trailer.sha256))
        } finally {
          staged.file.delete()
        }
      }
    }

  fun cookieTarget(port: CookiePort): BackupSections.RestoreTarget =
    object : BackupSections.RestoreTarget {
      override fun screen(header: BackupFormat.EntryHeader): BackupSections.ItemOutcome? {
        if (header.kind != BackupSections.KIND_COOKIE_PROFILE) {
          return BackupSections.ItemOutcome.SKIPPED_UNWANTED
        }
        // Restoring is additive everywhere else in the app; cookies must be no different.
        // Overwriting here would replace a session the user is currently signed in to with
        // whatever was captured when the backup was made.
        val platform = header.meta.optString("platform")
        val profile = header.meta.optString("profileName")
        return if (port.exists(platform, profile)) {
          BackupSections.ItemOutcome.SKIPPED_EXISTS
        } else {
          null
        }
      }

      override fun store(header: BackupFormat.EntryHeader, payload: InputStream): Any? {
        port.restore(
          header.meta.optString("platform"),
          header.meta.optString("profileName"),
          header.meta.optBoolean("isDefault", false),
          payload.readBytes(),
        )
        return null
      }
    }

  /**
   * Settings live in AsyncStorage on the TS side, so this only captures the blob; the module
   * hands it back and the TS layer writes it. Keeping AsyncStorage's shape out of Kotlin
   * means adding a preference never needs a native change.
   */
  class SettingsTarget : BackupSections.RestoreTarget {
    var settings: JSONObject? = null
      private set

    override fun screen(header: BackupFormat.EntryHeader) =
      if (header.kind == BackupSections.KIND_BLOB &&
        header.meta.optString("blobId") == BackupSections.BLOB_APP_SETTINGS
      ) {
        null
      } else {
        BackupSections.ItemOutcome.SKIPPED_UNWANTED
      }

    override fun isBookkeeping(header: BackupFormat.EntryHeader) = true

    override fun store(header: BackupFormat.EntryHeader, payload: InputStream): Any? {
      settings = JSONObject(String(payload.readBytes(), Charsets.UTF_8))
      return null
    }
  }
}
