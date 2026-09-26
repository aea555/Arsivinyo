package expo.modules.localdownloader.backup

import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream

/**
 * The bridge between the app's stores and the container format.
 *
 * Collectors describe what to back up as a list of [BackupItem]s — a name, some metadata,
 * and a lambda that writes the plaintext. They never touch framing, encryption or the
 * header. That keeps this layer free of Android APIs, so the section planning and the
 * duplicate logic below can be tested on the JVM with fakes.
 *
 * Item kinds are wire values recorded in every backup. Never rename one in place.
 */
object BackupSections {

  /** A video or audio file: the payload is the media itself. */
  const val KIND_MEDIA = "media"

  /** Cover art or a video thumbnail. `meta.ownerId` names the item it belongs to. */
  const val KIND_THUMBNAIL = "thumbnail"

  /**
   * A JSON blob rather than a file — playlists, preferences, the auto-apply preset config.
   * `meta.blobId` says which one, so a restore can route it without guessing from the name.
   */
  const val KIND_BLOB = "blob"

  /** A decrypted cookie profile. `meta.platform` and `meta.profileId` place it back. */
  const val KIND_COOKIE_PROFILE = "cookie-profile"

  /** Blob identifiers, also wire values. */
  const val BLOB_MUSIC_INDEX = "music-index"
  const val BLOB_AUTO_PRESETS = "auto-presets"
  const val BLOB_APP_SETTINGS = "app-settings"
  /** The memes' tags and people by name, with facets: `shared/memes/CONTRACT.md`. */
  const val BLOB_MEMES_INDEX = "memes-index"

  /**
   * One thing to put in a backup.
   *
   * [size] is advisory — used for the progress estimate and the first-stage duplicate
   * filter. [writePayload] may write a different number of bytes without breaking anything;
   * the trailer records what was actually written.
   */
  data class BackupItem(
    val name: String,
    val kind: String,
    val size: Long,
    val meta: JSONObject = JSONObject(),
    val writePayload: (OutputStream) -> Unit,
  )

  fun blobItem(blobId: String, json: JSONObject): BackupItem {
    val bytes = json.toString().toByteArray(Charsets.UTF_8)
    return BackupItem(
      name = "$blobId.json",
      kind = KIND_BLOB,
      size = bytes.size.toLong(),
      meta = JSONObject().apply { put("blobId", blobId) },
      writePayload = { out -> out.write(bytes) },
    )
  }

  /**
   * Turn collected items into a section the container can write.
   *
   * The items are produced eagerly (so the header can state a count) but their payloads are
   * written lazily, one at a time, as the section streams out.
   */
  fun plan(
    sectionId: String,
    items: List<BackupItem>,
    keySlot: String = BackupFormat.DEFAULT_KEY_SLOT,
  ): BackupContainer.PlannedSection = BackupContainer.PlannedSection(
    id = sectionId,
    keySlot = keySlot,
    itemCount = items.size,
    plaintextBytes = items.sumOf { it.size },
    writeEntries = { sink ->
      items.forEach { item ->
        sink.add(
          BackupFormat.EntryHeader(
            name = item.name,
            size = item.size,
            kind = item.kind,
            meta = item.meta,
          ),
          item.writePayload,
        )
      }
    },
  )

  // ------------------------------------------------------------------ duplicates

  /**
   * Decides whether an incoming item is already present, by content rather than by name.
   *
   * The hash only becomes available once an item's payload has streamed past, so this runs
   * in two stages. [couldCollideAt] is asked first, using nothing but the advisory size; if
   * it says no, the item is written straight to its destination and never hashed against
   * anything. Only when a size collides does the importer take the slower path of staging
   * the payload and asking [isDuplicate] afterwards.
   *
   * That ordering matters: the alternative — hashing the whole existing library up front —
   * would mean decrypting an entire vault before restoring a single file.
   */
  interface DuplicateIndex {
    /**
     * @return true if anything already stored has this size, meaning the content hash is
     * needed to tell them apart. Returning true is always safe, just slower.
     */
    fun couldCollideAt(size: Long): Boolean

    /**
     * Find the stored item with this content.
     *
     * Returns the item's own id rather than a boolean, because a skipped duplicate still
     * has to be addressable: playlists are lists of song ids, so restoring one onto a
     * device that already holds the tracks needs to map the backup's ids onto the local
     * ones. A plain "yes, duplicate" left those tracks unmapped and every playlist empty.
     *
     * [size] is the authoritative size from the trailer, so an implementation can hash only
     * the stored items that share it rather than the whole library.
     *
     * @return the local id, or null if nothing matches.
     */
    fun existingIdFor(sha256: String, size: Long): String?

    /** Nothing stored yet, so nothing can collide. */
    companion object {
      val EMPTY = object : DuplicateIndex {
        override fun couldCollideAt(size: Long) = false
        override fun existingIdFor(sha256: String, size: Long): String? = null
      }
    }
  }

  /**
   * What a restore did with one item.
   *
   * [RESTORED] is deliberately narrow: it means a file was added to a library. Playlists,
   * preferences and cover art are [APPLIED] instead, because counting them as records
   * added made a restore that changed nothing report dozens of additions.
   */
  enum class ItemOutcome {
    /** A file was added. */
    RESTORED,

    /** Metadata was written — playlists, preferences, the auto-apply config, cover art. */
    APPLIED,

    /** The same content is already stored, identified by hash. */
    SKIPPED_DUPLICATE,

    /** Something with this identity already exists and was left alone. */
    SKIPPED_EXISTS,

    /** The target does not handle this kind of entry. */
    SKIPPED_UNWANTED,

    FAILED,
  }

  data class ItemResult(
    val sectionId: String,
    val name: String,
    val outcome: ItemOutcome,
    val error: String? = null,
  )

  /**
   * Where restored items go. Implementations own the actual writing — into the vault, into
   * MediaStore, into AsyncStorage via the TS layer.
   */
  interface RestoreTarget {
    /** Duplicate detection for this section. */
    fun duplicates(): DuplicateIndex = DuplicateIndex.EMPTY

    /**
     * Decide an entry's fate before its payload is read.
     *
     * @return null to proceed, or the outcome to record when skipping. Returning an outcome
     * rather than a boolean lets a target say *why* it declined — "already exists" and "not
     * my kind of entry" read very differently in a restore report.
     */
    fun screen(header: BackupFormat.EntryHeader): ItemOutcome? = null

    /**
     * True when this entry is metadata rather than a stored file.
     *
     * Kept separate from [store] so the report cannot claim to have added files it did not:
     * cover art belongs to another entry, and a blob is a settings write.
     */
    fun isBookkeeping(header: BackupFormat.EntryHeader): Boolean = false

    /**
     * Consume [payload] and store it. Called only once the item is known to be wanted.
     *
     * The item may still turn out to be a duplicate afterwards, in which case
     * [discard] is called with whatever token this returned. Targets that staged the data
     * somewhere should return a handle they can undo.
     */
    fun store(header: BackupFormat.EntryHeader, payload: InputStream): Any?

    /** Undo a [store] that turned out to be a duplicate. */
    fun discard(token: Any?) = Unit

    /**
     * Called instead of [commit] when the item is already stored, naming what it matched.
     * Targets that remap identities record the pairing here.
     */
    fun onDuplicate(header: BackupFormat.EntryHeader, existingId: String?) = Unit

    /**
     * Finalise a stored item now that its content hash is known. Targets that record the
     * hash for cheap duplicate detection on later imports do it here.
     */
    fun commit(token: Any?, trailer: BackupFormat.EntryTrailer) = Unit
  }

  /**
   * Route one entry to its target, applying the two-stage duplicate check.
   *
   * Kept separate from [BackupContainer] so the policy — what counts as a duplicate, what
   * happens on failure — is testable without building a real backup file.
   */
  fun restoreEntry(entry: BackupContainer.RestoredEntry, target: RestoreTarget): ItemResult {
    val header = entry.header
    target.screen(header)?.let { skipped ->
      return ItemResult(entry.sectionId, header.name, skipped)
    }

    val duplicates = target.duplicates()
    val mightBeDuplicate = duplicates.couldCollideAt(header.size)

    var token: Any? = null
    try {
      token = target.store(header, entry.payload)
      val trailer = entry.verifiedTrailer()

      // The exporter could not read this item in full. Its size and hash describe the
      // truncated bytes and so verify perfectly — only this flag distinguishes half a
      // video from a whole one, so the item is dropped rather than stored.
      if (!trailer.complete) {
        target.discard(token)
        return ItemResult(
          entry.sectionId,
          header.name,
          ItemOutcome.FAILED,
          "incomplete in the backup",
        )
      }

      // The size that mattered is the real one from the trailer, not the advisory header
      // value — but the cheap filter above could only use the header. Re-check against the
      // trailer so a wrong advisory size cannot let a duplicate through.
      if (mightBeDuplicate || duplicates.couldCollideAt(trailer.size)) {
        val existingId = duplicates.existingIdFor(trailer.sha256, trailer.size)
        if (existingId != null) {
          target.discard(token)
          target.onDuplicate(header, existingId)
          return ItemResult(entry.sectionId, header.name, ItemOutcome.SKIPPED_DUPLICATE)
        }
      }

      target.commit(token, trailer)
      return ItemResult(
        entry.sectionId,
        header.name,
        if (target.isBookkeeping(header)) ItemOutcome.APPLIED else ItemOutcome.RESTORED,
      )
    } catch (e: Exception) {
      // One unreadable item should not abandon the rest of the restore; the caller reports
      // what landed and what did not.
      runCatching { target.discard(token) }
      return ItemResult(
        entry.sectionId,
        header.name,
        ItemOutcome.FAILED,
        e.message ?: e::class.java.simpleName,
      )
    }
  }
}
