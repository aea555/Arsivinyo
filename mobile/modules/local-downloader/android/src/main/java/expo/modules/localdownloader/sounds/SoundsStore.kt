package expo.modules.localdownloader.sounds

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Manages the on-device music library (M4A/AAC audio).
 *
 * Design notes:
 * - Files live in the PUBLIC `Music/Arsivinyo` folder via MediaStore. They survive
 *   uninstall and are visible to other music apps. We only ever touch entries we
 *   created ourselves, which Android grants without any READ_MEDIA_AUDIO /
 *   WRITE_EXTERNAL_STORAGE permission (the "owner" exception). This keeps the app's
 *   no-broad-storage-permission posture intact.
 * - This requires scoped storage (API 29+). On older devices [isSupported] is false
 *   and every entry point throws SOUNDS_UNSUPPORTED_OS so the UI can degrade.
 * - Thumbnails are extracted from each track's embedded cover art (ID3 APIC) via
 *   MediaMetadataRetriever and cached as sidecar JPEGs in app-private storage for
 *   fast display. The same path serves both downloaded and imported tracks.
 * - `sounds/index.json` (app-private) caches song metadata and OWNS playlists. It is
 *   reconciled against a MediaStore enumeration on every list, so a track deleted by
 *   another app drops out and its playlist references are cleaned up.
 *
 * Index shape:
 * {
 *   "version": 1,
 *   "songs":   [{ id, title, artist, fileName, contentUri, durationSec, sizeBytes,
 *                 thumbFileName, sourceUrlHash, createdAt, updatedAt }],
 *   "playlists":[{ id, name, songIds:[...ordered], createdAt, updatedAt }]
 * }
 */
class SoundsStore(private val context: Context) {

  private val lock = Any()

  /**
   * Display names claimed by saves that are still copying their bytes.
   *
   * A save reserves its name, then streams the file in **without** holding [lock] — the
   * copy is gigabytes and holding the store's single monitor across it froze every other
   * library operation, including plain reads, for the whole save. The MediaStore row is
   * created up front but stays `IS_PENDING` until the copy finishes, and a pending row is
   * not reliably returned by a normal query, so it cannot be relied on to reserve the
   * name. This set does that instead, and is the only thing keeping two concurrent saves
   * of the same title from both choosing it.
   */
  private val reservedDisplayNames = HashSet<String>()

  fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

  private fun requireSupported() {
    if (!isSupported()) {
      throw IllegalStateException(ERR_UNSUPPORTED_OS)
    }
  }

  // ---------------------------------------------------------------------------
  // Paths
  // ---------------------------------------------------------------------------

  private fun rootDir(): File = File(context.filesDir, "sounds").apply { mkdirs() }
  private fun indexFile(): File = File(rootDir(), "index.json")
  private fun thumbsDir(): File = File(context.filesDir, "sounds_thumbs").apply { mkdirs() }

  private fun audioCollection(): Uri =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    } else {
      MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    }

  // ---------------------------------------------------------------------------
  // Index read / write (callers must hold `lock`)
  // ---------------------------------------------------------------------------

  private fun emptyIndex(): JSONObject = JSONObject().apply {
    put("version", 1)
    put("songs", JSONArray())
    put("playlists", JSONArray())
  }

  private fun readIndexLocked(): JSONObject {
    val f = indexFile()
    if (!f.exists()) return emptyIndex()
    return try {
      val obj = JSONObject(f.readText(Charsets.UTF_8))
      if (!obj.has("songs")) obj.put("songs", JSONArray())
      if (!obj.has("playlists")) obj.put("playlists", JSONArray())
      obj
    } catch (e: Exception) {
      Log.w(TAG, "sounds index unreadable, starting fresh: ${e.message}")
      emptyIndex()
    }
  }

  private fun writeIndexLocked(obj: JSONObject) {
    val f = indexFile()
    val tmp = File(f.parentFile, f.name + ".tmp")
    tmp.writeText(obj.toString(), Charsets.UTF_8)
    f.delete()
    if (!tmp.renameTo(f)) {
      // Fallback for filesystems where rename-over fails.
      tmp.copyTo(f, overwrite = true)
      tmp.delete()
    }
  }

  // ---------------------------------------------------------------------------
  // Public API — library
  // ---------------------------------------------------------------------------

  /** Reconcile the index against MediaStore and return { songs, playlists }. */
  fun listLibrary(): Map<String, Any?> {
    requireSupported()
    synchronized(lock) {
      val index = reconcileLocked()
      return mapOf(
        "songs" to jsonArrayToSongMaps(index.getJSONArray("songs")),
        "playlists" to jsonArrayToPlaylistMaps(index.getJSONArray("playlists")),
      )
    }
  }

  /**
   * Register a freshly downloaded audio file (still sitting in the app cache) into the
   * library: copy it into Music/Arsivinyo, extract its thumbnail, index it.
   * Returns the new song map. The caller is responsible for deleting the source.
   */
  fun registerDownloadedSound(
    sourceFilePath: String,
    displayName: String,
    sourceUrl: String?,
    thumbnailPath: String? = null,
  ): Map<String, Any?> {
    requireSupported()
    val source = File(sourceFilePath)
    if (!source.exists() || !source.isFile || source.length() <= 0L) {
      throw IllegalStateException(ERR_SAVE_FAILED)
    }
    val safeName = ensureAudioExtension(displayName.ifBlank { source.nameWithoutExtension })
    val (uri, uniqueName) = reserveAudioEntry(safeName)
    val song = try {
      fillAudioEntry(uri, source)
      buildSongFromUri(uri, uniqueName, sourceUrl, thumbnailPath)
    } catch (e: Exception) {
      abandonAudioEntry(uri, uniqueName)
      throw e
    }
    appendSongsLocked(listOf(song))
    releaseDisplayName(uniqueName)
    return jsonToSongMap(song)
  }

  /**
   * Register a rendered preset output as a NEW library entry, leaving the source track
   * untouched. A render is therefore always undoable by deleting the result, and the
   * original stays available to re-render from — which is why renders never need to be
   * chained on top of one another.
   *
   * [presetId] and [sourceSongId] are recorded so the UI can show where a track came
   * from and so a re-render can find its source. The caller supplies the already
   * rendered file; this only moves it into the library.
   */
  fun registerProcessedSound(
    sourceFilePath: String,
    displayName: String,
    sourceSongId: String?,
    presetId: String,
    fallbackThumbPath: String? = null,
    fallbackArtist: String? = null,
  ): Map<String, Any?> {
    requireSupported()
    val rendered = File(sourceFilePath)
    if (!rendered.exists() || !rendered.isFile || rendered.length() <= 0L) {
      throw IllegalStateException(ERR_SAVE_FAILED)
    }

    // Cover art and artist normally come from the source track's library entry. A render
    // produced straight from a download has no such entry — the original is never filed
    // when the user asked to keep only the preset versions — so the caller supplies them.
    val original = sourceSongId?.let { id -> synchronized(lock) { findSongLocked(readIndexLocked(), id) } }
    val inheritedThumb = original
      ?.optString("thumbFileName")
      ?.takeUnless { it.isBlank() }
      ?.let { File(thumbsDir(), it) }
      ?.takeIf { it.exists() }
      ?.absolutePath
      ?: fallbackThumbPath?.takeIf { File(it).exists() }
    val inheritedArtist = original?.optString("artist")?.takeUnless { it.isBlank() } ?: fallbackArtist

    val safeName = ensureAudioExtension(displayName.ifBlank { rendered.nameWithoutExtension })
    val (uri, uniqueName) = reserveAudioEntry(safeName)
    val song = try {
      fillAudioEntry(uri, rendered)
      // The rendered file carries no embedded image — the bundled FFmpeg has no image
      // encoder — so re-extracting art from it would find nothing and the render would
      // show up in the library with a blank thumbnail.
      buildSongFromUri(uri, uniqueName, null, inheritedThumb)
    } catch (e: Exception) {
      abandonAudioEntry(uri, uniqueName)
      throw e
    }

    // A render is the source's track, processed: its artist is the source's, whatever the
    // rendered file's tags say or fail to say.
    knownArtist(inheritedArtist)?.let { song.put("artist", it) }
    song.put("presetId", presetId)
    if (sourceSongId != null) song.put("sourceSongId", sourceSongId)

    appendSongsLocked(listOf(song))
    releaseDisplayName(uniqueName)
    return jsonToSongMap(song)
  }

  /** Sets a song's artist where it has none (or only MediaStore's "<unknown>"); never replaces one. */
  fun fillArtist(songId: String, artist: String) {
    val known = knownArtist(artist) ?: return
    synchronized(lock) {
      val index = readIndexLocked()
      val song = findSongLocked(index, songId) ?: return
      if (knownArtist(song.optString("artist").takeUnless { song.isNull("artist") }) != null) return
      song.put("artist", known)
      writeIndexLocked(index)
    }
  }

  /** Look up a single song by id, or null. Used to resolve a render's source track. */
  fun findSong(id: String): Map<String, Any?>? {
    requireSupported()
    synchronized(lock) {
      val song = findSongLocked(readIndexLocked(), id) ?: return null
      return jsonToSongMap(song)
    }
  }

  /** Import existing audio files chosen via SAF. Returns { importedCount, failedCount, songs }. */
  fun importFromUris(uris: List<Uri>): Map<String, Any?> {
    requireSupported()
    val imported = mutableListOf<JSONObject>()
    val failures = mutableListOf<String>()
    Log.d(TAG, "importFromUris: ${uris.size} uri(s)")
    for (src in uris) {
      // Each phase is logged so a failing import points at the exact step.
      var phase = "query"
      try {
        val srcMime = runCatching { context.contentResolver.getType(src) }.getOrNull()
        val rawName = querySourceDisplayName(src)
        val withExt = ensureAudioExtension(rawName, srcMime)
        phase = "insert"
        val (uri, name) = reserveAudioEntry(withExt)
        Log.d(TAG, "import: src=$src mime=$srcMime -> reserved")
        val song = try {
          phase = "open-source"
          val input = context.contentResolver.openInputStream(src)
            ?: throw IOException("SOURCE_STREAM_NULL")
          input.use { ins ->
            phase = "open-dest"
            val out = context.contentResolver.openOutputStream(uri)
              ?: throw IOException("MEDIASTORE_OUTPUT_STREAM_NULL")
            phase = "copy"
            out.use { os -> BufferedOutputStream(os, STREAM_BUFFER).use { ins.copyTo(it, STREAM_BUFFER) } }
          }
          phase = "finalize"
          finalizeAudioEntry(uri)
          phase = "index"
          buildSongFromUri(uri, name, null)
        } catch (e: Exception) {
          abandonAudioEntry(uri, name)
          throw e
        }
        imported.add(song)
        releaseDisplayName(name)
        Log.d(TAG, "import OK: ${song.optString("id")}")
      } catch (e: Exception) {
        val reason = "phase=$phase ${e.javaClass.simpleName}: ${e.message}"
        Log.w(TAG, "sound import failed for $src: $reason", e)
        failures.add(reason)
      }
    }
    // One index write for the whole batch, as before.
    appendSongsLocked(imported)
    Log.d(TAG, "importFromUris done: imported=${imported.size} failed=${failures.size}")
    return mapOf(
      "importedCount" to imported.size,
      "failedCount" to failures.size,
      "songs" to imported.map { jsonToSongMap(it) },
      "failures" to failures,
    )
  }

  /** Permanently delete songs: removes MediaStore entries + thumbs + index rows + playlist refs. */
  fun deleteSounds(ids: List<String>): Map<String, Any?> {
    requireSupported()
    synchronized(lock) {
      val index = readIndexLocked()
      val songs = index.getJSONArray("songs")
      val idSet = ids.toHashSet()
      var deleted = 0
      val kept = JSONArray()
      for (i in 0 until songs.length()) {
        val s = songs.getJSONObject(i)
        val id = s.optString("id")
        if (id in idSet) {
          deleteMediaStoreEntry(id)
          deleteThumb(s.optString("thumbFileName"))
          deleted += 1
        } else {
          kept.put(s)
        }
      }
      index.put("songs", kept)
      removeSongsFromAllPlaylists(index, idSet)
      writeIndexLocked(index)
      return mapOf("deletedCount" to deleted)
    }
  }

  /** Rename a song (updates MediaStore DISPLAY_NAME/TITLE + the index title). */
  fun renameSound(id: String, title: String): Map<String, Any?> {
    requireSupported()
    val newTitle = title.trim()
    if (newTitle.isEmpty()) throw IllegalArgumentException("INVALID_TITLE")
    synchronized(lock) {
      val index = readIndexLocked()
      val song = findSongLocked(index, id) ?: throw IllegalArgumentException(ERR_NOT_FOUND)
      val uri = ContentUris.withAppendedId(audioCollection(), id.toLong())
      val values = ContentValues().apply {
        put(MediaStore.Audio.Media.TITLE, newTitle)
        put(MediaStore.MediaColumns.DATE_MODIFIED, System.currentTimeMillis() / 1000L)
      }
      runCatching { context.contentResolver.update(uri, values, null, null) }
      song.put("title", newTitle)
      song.put("updatedAt", System.currentTimeMillis())
      writeIndexLocked(index)
      return jsonToSongMap(song)
    }
  }

  fun getThumbnailPath(id: String): String? {
    synchronized(lock) {
      val index = readIndexLocked()
      val song = findSongLocked(index, id) ?: return null
      val thumbName = song.optString("thumbFileName").ifBlank { return null }
      val f = File(thumbsDir(), thumbName)
      return if (f.exists()) f.absolutePath else null
    }
  }

  // ---------------------------------------------------------------------------
  // Public API — playlists
  // ---------------------------------------------------------------------------

  fun listPlaylists(): List<Map<String, Any?>> {
    requireSupported()
    synchronized(lock) {
      val index = readIndexLocked()
      ensureFavoritesLocked(index)
      writeIndexLocked(index)
      return jsonArrayToPlaylistMaps(index.getJSONArray("playlists"))
    }
  }

  /** Add/remove songs from the special Favorites playlist. Returns the favorites map. */
  fun setSoundsFavorite(songIds: List<String>, favorite: Boolean): Map<String, Any?> {
    requireSupported()
    synchronized(lock) {
      val index = readIndexLocked()
      val fav = ensureFavoritesLocked(index)
      val existing = fav.getJSONArray("songIds")
      if (favorite) {
        val valid = validSongIdSet(index)
        val present = HashSet<String>()
        for (j in 0 until existing.length()) present.add(existing.getString(j))
        for (sid in songIds) {
          if (sid in valid && present.add(sid)) existing.put(sid)
        }
      } else {
        val remove = songIds.toHashSet()
        val kept = JSONArray()
        for (j in 0 until existing.length()) {
          val sid = existing.getString(j)
          if (sid !in remove) kept.put(sid)
        }
        fav.put("songIds", kept)
      }
      fav.put("updatedAt", System.currentTimeMillis())
      writeIndexLocked(index)
      return jsonToPlaylistMap(fav)
    }
  }

  fun createPlaylist(name: String): Map<String, Any?> {
    requireSupported()
    val trimmed = name.trim()
    if (trimmed.isEmpty()) throw IllegalArgumentException("INVALID_NAME")
    synchronized(lock) {
      val index = readIndexLocked()
      val playlists = index.getJSONArray("playlists")
      val now = System.currentTimeMillis()
      val pl = JSONObject().apply {
        put("id", "pl_${UUID.randomUUID()}")
        put("name", trimmed)
        put("songIds", JSONArray())
        put("createdAt", now)
        put("updatedAt", now)
      }
      playlists.put(pl)
      writeIndexLocked(index)
      return jsonToPlaylistMap(pl)
    }
  }

  fun renamePlaylist(id: String, name: String): Map<String, Any?> {
    requireSupported()
    if (id == FAVORITES_PLAYLIST_ID) throw IllegalArgumentException("FAVORITES_PROTECTED")
    val trimmed = name.trim()
    if (trimmed.isEmpty()) throw IllegalArgumentException("INVALID_NAME")
    synchronized(lock) {
      val index = readIndexLocked()
      val pl = findPlaylistLocked(index, id) ?: throw IllegalArgumentException(ERR_NOT_FOUND)
      pl.put("name", trimmed)
      pl.put("updatedAt", System.currentTimeMillis())
      writeIndexLocked(index)
      return jsonToPlaylistMap(pl)
    }
  }

  /** Delete a playlist. Songs themselves are NOT removed (they stay in the library). */
  fun deletePlaylist(id: String): Map<String, Any?> {
    requireSupported()
    if (id == FAVORITES_PLAYLIST_ID) return mapOf("success" to false) // protected
    synchronized(lock) {
      val index = readIndexLocked()
      val playlists = index.getJSONArray("playlists")
      val kept = JSONArray()
      var removed = false
      for (i in 0 until playlists.length()) {
        val p = playlists.getJSONObject(i)
        if (p.optString("id") == id) removed = true else kept.put(p)
      }
      index.put("playlists", kept)
      writeIndexLocked(index)
      return mapOf("success" to removed)
    }
  }

  /** Replace a playlist's ordered membership (used for in-playlist reordering). */
  fun setPlaylistSongs(id: String, songIds: List<String>): Map<String, Any?> {
    requireSupported()
    synchronized(lock) {
      val index = readIndexLocked()
      val pl = findPlaylistLocked(index, id) ?: throw IllegalArgumentException(ERR_NOT_FOUND)
      val valid = validSongIdSet(index)
      val arr = JSONArray()
      val seen = HashSet<String>()
      for (sid in songIds) {
        if (sid in valid && seen.add(sid)) arr.put(sid)
      }
      pl.put("songIds", arr)
      pl.put("updatedAt", System.currentTimeMillis())
      writeIndexLocked(index)
      return jsonToPlaylistMap(pl)
    }
  }

  /** Add songs to one or more playlists (union-merge; preserves existing order). */
  fun addSongsToPlaylists(songIds: List<String>, playlistIds: List<String>): Map<String, Any?> {
    requireSupported()
    synchronized(lock) {
      val index = readIndexLocked()
      val valid = validSongIdSet(index)
      val toAdd = songIds.filter { it in valid }
      val plIdSet = playlistIds.toHashSet()
      val playlists = index.getJSONArray("playlists")
      for (i in 0 until playlists.length()) {
        val pl = playlists.getJSONObject(i)
        if (pl.optString("id") !in plIdSet) continue
        val existing = pl.getJSONArray("songIds")
        val present = HashSet<String>()
        for (j in 0 until existing.length()) present.add(existing.getString(j))
        for (sid in toAdd) {
          if (present.add(sid)) existing.put(sid)
        }
        pl.put("updatedAt", System.currentTimeMillis())
      }
      writeIndexLocked(index)
      return mapOf("success" to true)
    }
  }

  fun removeSongsFromPlaylist(playlistId: String, songIds: List<String>): Map<String, Any?> {
    requireSupported()
    synchronized(lock) {
      val index = readIndexLocked()
      val pl = findPlaylistLocked(index, playlistId) ?: throw IllegalArgumentException(ERR_NOT_FOUND)
      val remove = songIds.toHashSet()
      val existing = pl.getJSONArray("songIds")
      val kept = JSONArray()
      for (j in 0 until existing.length()) {
        val sid = existing.getString(j)
        if (sid !in remove) kept.put(sid)
      }
      pl.put("songIds", kept)
      pl.put("updatedAt", System.currentTimeMillis())
      writeIndexLocked(index)
      return jsonToPlaylistMap(pl)
    }
  }

  // ---------------------------------------------------------------------------
  // Reconciliation
  // ---------------------------------------------------------------------------

  private data class AudioRow(
    val id: String,
    val uri: Uri,
    val displayName: String,
    val durationSec: Double,
    val sizeBytes: Long,
    val title: String?,
    val artist: String?,
  )

  private fun reconcileLocked(): JSONObject {
    val index = readIndexLocked()
    val present = queryOwnedAudio()
    val oldSongs = index.getJSONArray("songs")

    val byId = HashMap<String, JSONObject>()
    for (i in 0 until oldSongs.length()) {
      val s = oldSongs.getJSONObject(i)
      byId[s.optString("id")] = s
    }

    val newSongs = JSONArray()
    for ((id, row) in present) {
      val existing = byId[id]
      if (existing != null) {
        // Refresh volatile fields from MediaStore, keep user-owned ones.
        existing.put("contentUri", row.uri.toString())
        existing.put("fileName", row.displayName)
        existing.put("durationSec", row.durationSec)
        existing.put("sizeBytes", row.sizeBytes)
        // Only to fill a blank: an artist the library already has is kept. MediaStore answers
        // "<unknown>" for a file whose tags it did not read, and taking that over the stored
        // artist is what left every rendered track, and some received ones, as unknown.
        val known = knownArtist(row.artist)
        if (known != null && knownArtist(existing.optString("artist").takeUnless { existing.isNull("artist") }) == null) {
          existing.put("artist", known)
        }
        if (!existing.has("thumbFileName") || existing.optString("thumbFileName").isBlank()) {
          extractAndStoreThumb(id, row.uri)?.let { existing.put("thumbFileName", it) }
        }
        newSongs.put(existing)
      } else {
        // Re-adopted / externally created entry under our folder.
        newSongs.put(buildSongFromRow(row))
      }
    }
    index.put("songs", newSongs)
    inheritArtistsLocked(newSongs)

    ensureFavoritesLocked(index)

    // Drop orphaned playlist references.
    val valid = HashSet<String>()
    for (i in 0 until newSongs.length()) valid.add(newSongs.getJSONObject(i).optString("id"))
    val playlists = index.getJSONArray("playlists")
    for (i in 0 until playlists.length()) {
      val pl = playlists.getJSONObject(i)
      val songIds = pl.getJSONArray("songIds")
      val kept = JSONArray()
      for (j in 0 until songIds.length()) {
        val sid = songIds.getString(j)
        if (sid in valid) kept.put(sid)
      }
      pl.put("songIds", kept)
    }

    writeIndexLocked(index)
    return index
  }

  private fun queryOwnedAudio(): LinkedHashMap<String, AudioRow> {
    val result = LinkedHashMap<String, AudioRow>()
    val projection = arrayOf(
      MediaStore.Audio.Media._ID,
      MediaStore.Audio.Media.DISPLAY_NAME,
      MediaStore.Audio.Media.DURATION,
      MediaStore.Audio.Media.SIZE,
      MediaStore.Audio.Media.TITLE,
      MediaStore.Audio.Media.ARTIST,
    )
    val selection = "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?"
    val args = arrayOf("$RELATIVE_PATH/%")
    val sort = "${MediaStore.Audio.Media.DATE_ADDED} DESC"
    runCatching {
      context.contentResolver.query(audioCollection(), projection, selection, args, sort)?.use { c ->
        val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
        val nameCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
        val durCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
        val sizeCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
        val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
        val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
        while (c.moveToNext()) {
          val rawId = c.getLong(idCol)
          val id = rawId.toString()
          val uri = ContentUris.withAppendedId(audioCollection(), rawId)
          result[id] = AudioRow(
            id = id,
            uri = uri,
            displayName = c.getString(nameCol) ?: "audio.m4a",
            durationSec = if (c.isNull(durCol)) 0.0 else c.getLong(durCol) / 1000.0,
            sizeBytes = if (c.isNull(sizeCol)) 0L else c.getLong(sizeCol),
            title = if (c.isNull(titleCol)) null else c.getString(titleCol),
            artist = if (c.isNull(artistCol)) null else c.getString(artistCol),
          )
        }
      }
    }.onFailure { Log.w(TAG, "queryOwnedAudio failed: ${it.message}") }
    return result
  }

  // ---------------------------------------------------------------------------
  // MediaStore write helpers
  // ---------------------------------------------------------------------------

  /**
   * Claim a unique display name and create its still-pending MediaStore row.
   *
   * [lock] is held only for the claim, never for the copy that follows.
   *
   * @return the new row's uri and the name it was actually given.
   */
  private fun reserveAudioEntry(desiredName: String): Pair<Uri, String> = synchronized(lock) {
    val uniqueName = uniqueDisplayNameLocked(desiredName)
    reservedDisplayNames.add(uniqueName.lowercase())
    val uri = try {
      insertAudioEntry(uniqueName)
    } catch (e: Exception) {
      reservedDisplayNames.remove(uniqueName.lowercase())
      throw e
    }
    uri to uniqueName
  }

  private fun releaseDisplayName(name: String) {
    synchronized(lock) { reservedDisplayNames.remove(name.lowercase()) }
  }

  /**
   * Stream [source] into a reserved row and publish it. Deliberately takes no lock: this
   * is the multi-gigabyte part of a save.
   */
  private fun fillAudioEntry(uri: Uri, source: File) {
    source.inputStream().use { input ->
      context.contentResolver.openOutputStream(uri)?.use { out ->
        BufferedOutputStream(out, STREAM_BUFFER).use { input.copyTo(it, STREAM_BUFFER) }
      } ?: throw IOException("MEDIASTORE_OUTPUT_STREAM_FAILED")
    }
    finalizeAudioEntry(uri)
  }

  /** Roll back a reservation whose copy failed. */
  private fun abandonAudioEntry(uri: Uri, name: String) {
    runCatching { context.contentResolver.delete(uri, null, null) }
    releaseDisplayName(name)
  }

  private fun appendSongsLocked(newSongs: List<JSONObject>) {
    if (newSongs.isEmpty()) return
    synchronized(lock) {
      val index = readIndexLocked()
      val songs = index.getJSONArray("songs")
      newSongs.forEach { songs.put(it) }
      writeIndexLocked(index)
    }
  }

  private fun insertAudioEntry(displayName: String): Uri {
    val resolver = context.contentResolver
    val now = System.currentTimeMillis() / 1000L
    val values = ContentValues().apply {
      put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
      put(MediaStore.MediaColumns.MIME_TYPE, mimeForName(displayName))
      put(MediaStore.MediaColumns.DATE_ADDED, now)
      put(MediaStore.MediaColumns.DATE_MODIFIED, now)
      put(MediaStore.Audio.Media.IS_MUSIC, 1)
      put(MediaStore.Audio.Media.TITLE, titleFromFileName(displayName))
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_PATH)
        put(MediaStore.MediaColumns.IS_PENDING, 1)
      }
    }
    return resolver.insert(audioCollection(), values) ?: throw IOException("MEDIASTORE_INSERT_FAILED")
  }

  private fun finalizeAudioEntry(uri: Uri) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      val values = ContentValues().apply {
        put(MediaStore.MediaColumns.IS_PENDING, 0)
        put(MediaStore.MediaColumns.DATE_MODIFIED, System.currentTimeMillis() / 1000L)
      }
      context.contentResolver.update(uri, values, null, null)
    }
  }

  private fun deleteMediaStoreEntry(id: String) {
    runCatching {
      val uri = ContentUris.withAppendedId(audioCollection(), id.toLong())
      context.contentResolver.delete(uri, null, null)
    }.onFailure { Log.w(TAG, "deleteMediaStoreEntry($id) failed: ${it.message}") }
  }

  // ---------------------------------------------------------------------------
  // Song construction + thumbnails
  // ---------------------------------------------------------------------------

  private fun buildSongFromUri(
    uri: Uri,
    fileName: String,
    sourceUrl: String?,
    externalThumbPath: String? = null,
  ): JSONObject {
    val id = uri.lastPathSegment ?: UUID.randomUUID().toString()
    val now = System.currentTimeMillis()
    val meta = extractMetadata(uri)
    // Prefer the cover art yt-dlp downloaded alongside an audio download (stored
    // verbatim, no transcode). Fall back to art embedded in the file (imports).
    val thumb = externalThumbPath?.let { storeThumbFromFile(id, it) } ?: extractAndStoreThumb(id, uri)
    return JSONObject().apply {
      put("id", id)
      put("title", meta.title?.takeUnless { it.isBlank() } ?: titleFromFileName(fileName))
      put("artist", knownArtist(meta.artist) ?: JSONObject.NULL)
      put("fileName", fileName)
      put("contentUri", uri.toString())
      put("durationSec", meta.durationSec)
      put("sizeBytes", meta.sizeBytes)
      put("thumbFileName", thumb ?: JSONObject.NULL)
      put("sourceUrlHash", sourceUrl?.let { sha256Hex(it) } ?: JSONObject.NULL)
      put("createdAt", now)
      put("updatedAt", now)
    }
  }

  private fun buildSongFromRow(row: AudioRow): JSONObject {
    val now = System.currentTimeMillis()
    val thumb = extractAndStoreThumb(row.id, row.uri)
    return JSONObject().apply {
      put("id", row.id)
      put("title", row.title?.takeUnless { it.isBlank() } ?: titleFromFileName(row.displayName))
      put("artist", knownArtist(row.artist) ?: JSONObject.NULL)
      put("fileName", row.displayName)
      put("contentUri", row.uri.toString())
      put("durationSec", row.durationSec)
      put("sizeBytes", row.sizeBytes)
      put("thumbFileName", thumb ?: JSONObject.NULL)
      put("sourceUrlHash", JSONObject.NULL)
      put("createdAt", now)
      put("updatedAt", now)
    }
  }

  private data class TrackMeta(val title: String?, val artist: String?, val durationSec: Double, val sizeBytes: Long)

  /**
   * A render whose artist is missing or "<unknown>" takes its source's, going back through a
   * render of a render. Repairs the renders stored before MediaStore's "<unknown>" stopped
   * overwriting them.
   */
  private fun inheritArtistsLocked(songs: JSONArray) {
    val byId = LinkedHashMap<String, JSONObject>()
    for (i in 0 until songs.length()) songs.optJSONObject(i)?.let { byId[it.optString("id")] = it }
    fun artistOf(song: JSONObject) = knownArtist(song.optString("artist").takeUnless { song.isNull("artist") })
    fun sourceOf(song: JSONObject) = song.optString("sourceSongId").takeUnless { it.isBlank() }

    // Originals first, so a render below finds its source repaired. One whose artist
    // MediaStore overwrote still has it in its file: read once, then left alone.
    for (song in byId.values) {
      if (sourceOf(song) != null || artistOf(song) != null || song.optBoolean("artistRead")) continue
      val uri = song.optString("contentUri").takeUnless { it.isBlank() }?.let(Uri::parse)
      song.put("artist", uri?.let { knownArtist(extractMetadata(it).artist) } ?: JSONObject.NULL)
      song.put("artistRead", true)
    }
    // Then renders, through a render of a render to the first that has one.
    for (song in byId.values) {
      if (artistOf(song) != null) continue
      var source = sourceOf(song)
      var hops = 0
      while (source != null && hops < 8) {
        val from = byId[source] ?: break
        val artist = artistOf(from)
        if (artist != null) {
          song.put("artist", artist)
          break
        }
        source = sourceOf(from)
        hops++
      }
    }
  }

  /**
   * An artist worth keeping: not blank, and not MediaStore's "<unknown>", which it gives for a
   * file whose tags it did not read and which shows to the user as if it were a name.
   */
  private fun knownArtist(value: String?): String? =
    value?.trim()?.takeUnless { it.isEmpty() || it == MediaStore.UNKNOWN_STRING }

  private fun extractMetadata(uri: Uri): TrackMeta {
    val mmr = MediaMetadataRetriever()
    return try {
      mmr.setDataSource(context, uri)
      val title = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
      val artist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
      val durMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
      val size = querySize(uri)
      TrackMeta(title, artist, durMs / 1000.0, size)
    } catch (e: Exception) {
      TrackMeta(null, null, 0.0, querySize(uri))
    } finally {
      runCatching { mmr.release() }
    }
  }

  private fun extractAndStoreThumb(id: String, uri: Uri): String? {
    val mmr = MediaMetadataRetriever()
    return try {
      mmr.setDataSource(context, uri)
      val art = mmr.embeddedPicture ?: return null
      val thumbFile = File(thumbsDir(), "$id.jpg")
      thumbFile.outputStream().use { it.write(art) }
      thumbFile.name
    } catch (e: Exception) {
      Log.w(TAG, "thumb extract failed for $id: ${e.message}")
      null
    } finally {
      runCatching { mmr.release() }
    }
  }

  /**
   * Copy an already-decoded image file (e.g. the cover art yt-dlp downloaded next to
   * an audio file) into the sidecar thumbnail store, keeping its original format.
   * `expo-image` renders JPEG/PNG/WebP from a file path directly, so no transcode is
   * needed (and the bundled FFmpeg couldn't transcode images anyway).
   */
  private fun storeThumbFromFile(id: String, sourcePath: String): String? {
    return runCatching {
      val src = File(sourcePath)
      if (!src.exists() || !src.isFile || src.length() <= 0L) return null
      val ext = extensionOf(src.name).takeIf { it in IMAGE_EXTENSIONS } ?: "jpg"
      val thumbFile = File(thumbsDir(), "$id.$ext")
      src.copyTo(thumbFile, overwrite = true)
      thumbFile.name
    }.getOrElse {
      Log.w(TAG, "storeThumbFromFile failed for $id: ${it.message}")
      null
    }
  }

  private fun deleteThumb(thumbFileName: String?) {
    if (thumbFileName.isNullOrBlank()) return
    runCatching { File(thumbsDir(), thumbFileName).delete() }
  }

  // ---------------------------------------------------------------------------
  // Small helpers
  // ---------------------------------------------------------------------------

  private fun querySize(uri: Uri): Long {
    return runCatching {
      context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.SIZE), null, null, null)?.use { c ->
        if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else 0L
      } ?: 0L
    }.getOrDefault(0L)
  }

  private fun querySourceDisplayName(uri: Uri): String {
    val fallback = "audio.m4a"
    return runCatching {
      context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst() && !c.isNull(0)) c.getString(0) ?: fallback else fallback
      } ?: fallback
    }.getOrDefault(fallback)
  }

  /** Make a display name unique within the library by appending " (n)" before the extension. */
  private fun uniqueDisplayNameLocked(name: String): String {
    val existing = HashSet<String>()
    val present = queryOwnedAudio()
    for ((_, row) in present) existing.add(row.displayName.lowercase())
    // Saves that are mid-copy own a name but are not in MediaStore yet.
    existing.addAll(reservedDisplayNames)
    if (name.lowercase() !in existing) return name
    val dot = name.lastIndexOf('.')
    val stem = if (dot > 0) name.substring(0, dot) else name
    val ext = if (dot > 0) name.substring(dot) else ""
    var n = 1
    while (true) {
      val candidate = "$stem ($n)$ext"
      if (candidate.lowercase() !in existing) return candidate
      n += 1
    }
  }

  private fun extensionOf(name: String): String {
    val dot = name.lastIndexOf('.')
    if (dot <= 0 || dot >= name.length - 1) return ""
    return name.substring(dot + 1).lowercase()
  }

  /**
   * Ensure the display name carries a recognized audio extension. Downloaded files
   * already arrive as ".m4a"; imported files keep their real extension. A name with
   * no usable audio extension gets one inferred from [fallbackMime], else ".m4a".
   */
  private fun ensureAudioExtension(name: String, fallbackMime: String? = null): String {
    val trimmed = name.trim().ifBlank { "audio" }
    if (extensionOf(trimmed) in AUDIO_EXTENSIONS) return trimmed
    val mimeExt = fallbackMime
      ?.let { android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
      ?.lowercase()
      ?.takeIf { it in AUDIO_EXTENSIONS }
    return "$trimmed.${mimeExt ?: "m4a"}"
  }

  /**
   * Canonical audio MIME for a display name's extension. Kept consistent with the
   * extension so the MediaStore display-name/MIME validation on Android 11+ never
   * rejects the inserted row.
   */
  private fun mimeForName(name: String): String {
    val ext = extensionOf(name)
    val mapped = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
    if (mapped != null && mapped.startsWith("audio/")) return mapped
    return when (ext) {
      "mp3" -> "audio/mpeg"
      "m4a", "aac", "alac" -> "audio/mp4"
      "opus", "ogg", "oga" -> "audio/ogg"
      "flac" -> "audio/flac"
      "wav" -> "audio/x-wav"
      "weba", "webm", "mka" -> "audio/webm"
      else -> "audio/mp4"
    }
  }

  private fun titleFromFileName(name: String): String {
    val dot = name.lastIndexOf('.')
    return if (dot > 0) name.substring(0, dot) else name
  }

  private fun findSongLocked(index: JSONObject, id: String): JSONObject? {
    val songs = index.getJSONArray("songs")
    for (i in 0 until songs.length()) {
      val s = songs.getJSONObject(i)
      if (s.optString("id") == id) return s
    }
    return null
  }

  private fun findPlaylistLocked(index: JSONObject, id: String): JSONObject? {
    val playlists = index.getJSONArray("playlists")
    for (i in 0 until playlists.length()) {
      val p = playlists.getJSONObject(i)
      if (p.optString("id") == id) return p
    }
    return null
  }

  /**
   * Guarantee the special Favorites playlist exists and return it. Created lazily so
   * it always shows (even empty) and can never be deleted. Caller holds `lock`.
   */
  private fun ensureFavoritesLocked(index: JSONObject): JSONObject {
    val playlists = index.getJSONArray("playlists")
    for (i in 0 until playlists.length()) {
      val p = playlists.getJSONObject(i)
      if (p.optString("id") == FAVORITES_PLAYLIST_ID) {
        if (!p.optBoolean("system", false)) p.put("system", true)
        return p
      }
    }
    val now = System.currentTimeMillis()
    val fav = JSONObject().apply {
      put("id", FAVORITES_PLAYLIST_ID)
      put("name", "Favorites")
      put("system", true)
      put("songIds", JSONArray())
      put("createdAt", now)
      put("updatedAt", now)
    }
    playlists.put(fav)
    return fav
  }

  private fun validSongIdSet(index: JSONObject): HashSet<String> {
    val set = HashSet<String>()
    val songs = index.getJSONArray("songs")
    for (i in 0 until songs.length()) set.add(songs.getJSONObject(i).optString("id"))
    return set
  }

  private fun removeSongsFromAllPlaylists(index: JSONObject, ids: Set<String>) {
    val playlists = index.getJSONArray("playlists")
    for (i in 0 until playlists.length()) {
      val pl = playlists.getJSONObject(i)
      val songIds = pl.getJSONArray("songIds")
      val kept = JSONArray()
      for (j in 0 until songIds.length()) {
        val sid = songIds.getString(j)
        if (sid !in ids) kept.put(sid)
      }
      pl.put("songIds", kept)
    }
  }

  // ---------------------------------------------------------------------------
  // JSON -> Map conversion for the Expo bridge
  // ---------------------------------------------------------------------------

  private fun jsonToSongMap(s: JSONObject): Map<String, Any?> {
    val thumbName = s.optString("thumbFileName").takeUnless { it.isBlank() || s.isNull("thumbFileName") }
    val thumbPath = thumbName?.let { File(thumbsDir(), it).takeIf { f -> f.exists() }?.absolutePath }
    // Format is DERIVED from the file name rather than stored in the index. That keeps
    // it correct for every entry with no schema version bump and no migration: imports,
    // downloads, and entries re-adopted from MediaStore all report the real container
    // on disk, and it can never drift from the actual file.
    val format = extensionOf(s.optString("fileName"))
    return mapOf(
      "id" to s.optString("id"),
      "title" to s.optString("title"),
      "artist" to if (s.isNull("artist")) null else s.optString("artist").ifBlank { null },
      "fileName" to s.optString("fileName"),
      "contentUri" to s.optString("contentUri"),
      "durationSec" to s.optDouble("durationSec", 0.0),
      "sizeBytes" to s.optLong("sizeBytes", 0L),
      "thumbnailPath" to thumbPath,
      "format" to format.ifBlank { null },
      "lossless" to (format in LOSSLESS_EXTENSIONS),
      // Present only on tracks produced by a preset render. Null everywhere else, so
      // the UI can distinguish a rendered track from an original without a schema bump.
      "presetId" to s.optString("presetId").ifBlank { null },
      "sourceSongId" to s.optString("sourceSongId").ifBlank { null },
      "createdAt" to s.optLong("createdAt", 0L),
      "updatedAt" to s.optLong("updatedAt", 0L),
    )
  }

  private fun jsonArrayToSongMaps(arr: JSONArray): List<Map<String, Any?>> {
    val out = ArrayList<Map<String, Any?>>(arr.length())
    for (i in 0 until arr.length()) out.add(jsonToSongMap(arr.getJSONObject(i)))
    return out
  }

  private fun jsonToPlaylistMap(p: JSONObject): Map<String, Any?> {
    val songIds = p.getJSONArray("songIds")
    val ids = ArrayList<String>(songIds.length())
    for (i in 0 until songIds.length()) ids.add(songIds.getString(i))
    return mapOf(
      "id" to p.optString("id"),
      "name" to p.optString("name"),
      "songIds" to ids,
      "system" to (p.optString("id") == FAVORITES_PLAYLIST_ID || p.optBoolean("system", false)),
      "createdAt" to p.optLong("createdAt", 0L),
      "updatedAt" to p.optLong("updatedAt", 0L),
    )
  }

  private fun jsonArrayToPlaylistMaps(arr: JSONArray): List<Map<String, Any?>> {
    val out = ArrayList<Map<String, Any?>>(arr.length())
    for (i in 0 until arr.length()) out.add(jsonToPlaylistMap(arr.getJSONObject(i)))
    return out
  }

  private fun sha256Hex(input: String): String {
    return runCatching {
      val digest = java.security.MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
      digest.joinToString("") { "%02x".format(it) }
    }.getOrDefault("")
  }

  companion object {
    private const val TAG = "SoundsStore"
    const val RELATIVE_PATH = "Music/Arsivinyo"
    const val ERR_UNSUPPORTED_OS = "SOUNDS_UNSUPPORTED_OS"
    const val ERR_SAVE_FAILED = "SOUNDS_SAVE_FAILED"
    const val ERR_NOT_FOUND = "SOUNDS_NOT_FOUND"
    // Reserved id for the special, non-deletable "Favorites" playlist. Display name is
    // localized in the UI; the stored name is just a fallback.
    const val FAVORITES_PLAYLIST_ID = "favorites"
    /**
     * 1 MB, matching the vault's `PRIVATE_STREAM_BUFFER_BYTES`.
     *
     * Every byte written here crosses into MediaProvider's FUSE daemon, which charges
     * per write syscall rather than per byte — unlike the vault, which writes a plain
     * file in app-private storage. At the previous 64 KB a one-gigabyte track cost
     * about 17,000 round trips into MediaProvider; at 1 MB it costs about 1,100.
     */
    private const val STREAM_BUFFER = 1 shl 20
    // Recognized audio extensions. Downloads land as m4a; imports keep their own.
    private val AUDIO_EXTENSIONS = setOf(
      "mp3", "m4a", "aac", "alac", "opus", "ogg", "oga", "flac", "wav", "weba", "webm", "mka",
    )
    // Sidecar thumbnail formats expo-image renders directly (no transcode).
    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
    // Containers that hold the audio without further loss. Downloads default to FLAC;
    // the rest can appear here through SAF imports. Reported to the UI so a track's
    // quality tier is visible rather than something the user has to infer.
    private val LOSSLESS_EXTENSIONS = setOf("flac", "alac", "wav", "aiff", "aif")
  }
}
