package expo.modules.localdownloader.videos

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The video library: every video the app saved, with what the app knows about it.
 *
 * The files are public, in `Movies/Arsivinyo` (the ones from before that folder stay where
 * they are, in `DCIM`). Which of them are the app's is MediaStore's own answer: with no
 * storage permission a query returns only the rows this install made. What MediaStore cannot
 * hold (where a video came from, where it was stopped) is the index, `videos/index.json`.
 * The two are brought together on every listing: a video MediaStore has and the index does
 * not is taken in under its file name, and one MediaStore lost leaves the index.
 *
 * Nothing here is logged: a title is the viewer's business.
 */
class VideosStore(private val context: Context) {
  data class Origin(val uploader: String?, val url: String?, val platform: String?)

  private val lock = Any()

  fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

  private fun root(): File = File(context.filesDir, "videos").apply { mkdirs() }
  private fun indexFile(): File = File(root(), "index.json")
  private fun thumbs(): File = File(root(), "thumbs").apply { mkdirs() }

  // ---- the index -------------------------------------------------------------------------------

  private fun read(): JSONObject {
    val file = indexFile()
    val index = runCatching { JSONObject(file.readText(Charsets.UTF_8)) }.getOrNull() ?: JSONObject().put("version", 1)
    if (index.optJSONObject("videos") == null) index.put("videos", JSONObject())
    return index
  }

  private fun write(index: JSONObject) {
    val file = indexFile()
    val next = File(file.parentFile, file.name + ".tmp")
    next.writeText(index.toString(), Charsets.UTF_8)
    if (!next.renameTo(file)) {
      next.copyTo(file, overwrite = true)
      next.delete()
    }
  }

  // ---- MediaStore ------------------------------------------------------------------------------

  private class Row(
    val id: Long, val name: String, val durationMs: Long, val sizeBytes: Long, val width: Int, val height: Int,
    val addedAt: Long,
  )

  private fun uriOf(id: Long): Uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)

  /** The app's own videos in the two folders it saves to. Not a torrent's files, in Download. */
  private fun owned(): List<Row> {
    val rows = mutableListOf<Row>()
    val columns = arrayOf(
      MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.RELATIVE_PATH,
      MediaStore.Video.Media.DURATION, MediaStore.Video.Media.SIZE, MediaStore.Video.Media.WIDTH,
      MediaStore.Video.Media.HEIGHT, MediaStore.Video.Media.DATE_ADDED,
    )
    context.contentResolver.query(
      MediaStore.Video.Media.EXTERNAL_CONTENT_URI, columns,
      "${MediaStore.Video.Media.OWNER_PACKAGE_NAME} = ?", arrayOf(context.packageName), null,
    )?.use { cursor ->
      while (cursor.moveToNext()) {
        val path = cursor.getString(2).orEmpty().trimEnd('/')
        if (!path.equals(LEGACY_FOLDER, ignoreCase = true) && !path.equals(FOLDER, ignoreCase = true)) continue
        rows += Row(
          cursor.getLong(0), cursor.getString(1).orEmpty(), cursor.getLong(3), cursor.getLong(4), cursor.getInt(5),
          cursor.getInt(6), cursor.getLong(7) * 1000L,
        )
      }
    }
    return rows
  }

  /** Brings the index to what MediaStore has. True when the index changed. */
  private fun reconcile(index: JSONObject): Boolean {
    val videos = index.getJSONObject("videos")
    val rows = owned()
    var changed = false
    val present = rows.map { it.id.toString() }.toSet()
    videos.keys().asSequence().toList().filter { it !in present }.forEach {
      videos.remove(it)
      File(thumbs(), "$it.jpg").delete()
      changed = true
    }
    for (row in rows) {
      val key = row.id.toString()
      val known = videos.optJSONObject(key)
      val video = known ?: JSONObject().put("title", titleOf(row.name)).also {
        videos.put(key, it)
        changed = true
      }
      // What MediaStore learns after the file is written: its scan fills these in later.
      if (video.optLong("durationMs") <= 0L) {
        val durationMs = if (row.durationMs > 0L) row.durationMs else if (known == null) probeDuration(row.id) else 0L
        if (durationMs > 0L) {
          video.put("durationMs", durationMs)
          changed = true
        }
      }
      if (video.optLong("sizeBytes") != row.sizeBytes || video.optLong("addedAt") != row.addedAt ||
        video.optInt("width") != row.width || video.optInt("height") != row.height || video.optString("fileName") != row.name
      ) {
        video.put("sizeBytes", row.sizeBytes).put("addedAt", row.addedAt).put("width", row.width).put("height", row.height)
          .put("fileName", row.name)
        changed = true
      }
    }
    return changed
  }

  private fun probeDuration(id: Long): Long = runCatching {
    val retriever = MediaMetadataRetriever()
    try {
      retriever.setDataSource(context, uriOf(id))
      retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
    } finally {
      retriever.release()
    }
  }.getOrDefault(0L)

  private fun titleOf(fileName: String): String =
    fileName.substringBeforeLast('.', fileName).replace('_', ' ').trim().ifBlank { fileName }

  private fun map(id: String, video: JSONObject): Map<String, Any?> = mapOf(
    "id" to id,
    "uri" to uriOf(id.toLong()).toString(),
    "title" to video.optString("title"),
    "uploader" to video.optString("uploader").ifBlank { null },
    "sourceUrl" to video.optString("sourceUrl").ifBlank { null },
    "platform" to video.optString("platform").ifBlank { null },
    "durationMs" to video.optLong("durationMs").toDouble(),
    "sizeBytes" to video.optLong("sizeBytes").toDouble(),
    "width" to video.optInt("width"),
    "height" to video.optInt("height"),
    "addedAt" to video.optLong("addedAt").toDouble(),
    "positionMs" to video.optLong("positionMs").toDouble(),
    "playedAt" to video.optLong("playedAt").toDouble(),
  )

  // ---- what the app asks -----------------------------------------------------------------------

  /** A download was just saved as [uri]: it is kept with what the site said of it. */
  fun register(uri: String, fileName: String, title: String?, origin: Origin?) {
    if (!isSupported()) return
    val id = runCatching { ContentUris.parseId(Uri.parse(uri)) }.getOrNull() ?: return
    synchronized(lock) {
      val index = read()
      val videos = index.getJSONObject("videos")
      val video = videos.optJSONObject(id.toString()) ?: JSONObject().also { videos.put(id.toString(), it) }
      // The site's own title where it gave one: the file's name has lost every letter not ASCII.
      video.put("title", title?.trim()?.takeIf { it.isNotEmpty() } ?: titleOf(fileName)).put("fileName", fileName)
      origin?.uploader?.takeIf { it.isNotBlank() }?.let { video.put("uploader", it) }
      origin?.url?.takeIf { it.isNotBlank() }?.let { video.put("sourceUrl", it) }
      origin?.platform?.takeIf { it.isNotBlank() }?.let { video.put("platform", it) }
      write(index)
    }
  }

  /** Newest first. */
  fun list(): List<Map<String, Any?>> {
    if (!isSupported()) return emptyList()
    synchronized(lock) {
      val index = read()
      if (reconcile(index)) write(index)
      val videos = index.getJSONObject("videos")
      return videos.keys().asSequence().map { map(it, videos.getJSONObject(it)) }
        .sortedByDescending { it["addedAt"] as Double }.toList()
    }
  }

  /** A picture of the video, made once and kept. Null when the phone cannot make one. */
  fun thumbnail(id: String): String? {
    if (!isSupported()) return null
    val mediaId = id.toLongOrNull() ?: return null
    val file = File(thumbs(), "$mediaId.jpg")
    if (file.length() > 0L) return file.path
    return runCatching {
      val bitmap = context.contentResolver.loadThumbnail(uriOf(mediaId), Size(THUMB_WIDTH, THUMB_HEIGHT), null)
      val next = File(file.parentFile, file.name + ".tmp")
      next.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 82, it) }
      bitmap.recycle()
      if (!next.renameTo(file)) next.delete()
      file.path.takeIf { file.length() > 0L }
    }.getOrNull()
  }

  /** Where the video was stopped. At its end it starts over the next time. */
  fun setPlace(id: String, positionMs: Long, durationMs: Long) {
    synchronized(lock) {
      val index = read()
      val video = index.getJSONObject("videos").optJSONObject(id) ?: return
      val ended = durationMs > 0L && positionMs >= durationMs - END_MS
      video.put("positionMs", if (ended) 0L else positionMs.coerceAtLeast(0L)).put("playedAt", System.currentTimeMillis())
      if (durationMs > 0L) video.put("durationMs", durationMs)
      write(index)
    }
  }

  /** Where the video was stopped; 0 from the start. */
  fun place(id: String): Long = synchronized(lock) {
    read().getJSONObject("videos").optJSONObject(id)?.optLong("positionMs") ?: 0L
  }

  fun rename(id: String, title: String): Map<String, Any?> {
    val name = title.trim()
    require(name.isNotEmpty()) { "VIDEO_TITLE_EMPTY" }
    synchronized(lock) {
      val index = read()
      val video = index.getJSONObject("videos").optJSONObject(id) ?: throw IllegalArgumentException("VIDEO_NOT_FOUND")
      video.put("title", name)
      write(index)
      return map(id, video)
    }
  }

  /** The files themselves go, from the gallery too. */
  fun delete(ids: List<String>): Int {
    if (!isSupported()) return 0
    synchronized(lock) {
      val index = read()
      val videos = index.getJSONObject("videos")
      var deleted = 0
      for (id in ids) {
        val mediaId = id.toLongOrNull() ?: continue
        val gone = runCatching { context.contentResolver.delete(uriOf(mediaId), null, null) > 0 }.getOrDefault(false)
        if (!gone) continue
        videos.remove(id)
        File(thumbs(), "$mediaId.jpg").delete()
        deleted += 1
      }
      write(index)
      return deleted
    }
  }

  companion object {
    /** Where a video is saved, under the phone's shared storage. */
    const val FOLDER = "Movies/Arsivinyo"
    /** Where videos were saved before the library, and where those stay. */
    private const val LEGACY_FOLDER = "DCIM"
    private const val THUMB_WIDTH = 480
    private const val THUMB_HEIGHT = 270
    /** This close to the end is the end. */
    private const val END_MS = 5_000L
  }
}
