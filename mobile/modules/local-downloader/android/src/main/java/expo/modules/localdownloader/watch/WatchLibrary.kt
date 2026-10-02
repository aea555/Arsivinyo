package expo.modules.localdownloader.watch

import expo.modules.localdownloader.memes.MemeStore
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * The watch library: installed add-ons, and what has been watched and where it stopped
 * (`shared/watch/CONTRACT.md`, "The library").
 *
 * One file, sealed by a [MemeStore.Sealer] the module backs with a Keystore key that needs
 * no prompt, as the memes index is. What someone watches is as private as what they save, and
 * an add-on's URL often carries an account token, so nothing in here is ever written in the
 * clear or logged.
 */
class WatchLibrary(private val file: File, private val sealer: MemeStore.Sealer) {

  data class Addon(val base: String, val manifest: JSONObject, val enabled: Boolean = true)

  data class Progress(val videoId: String, val positionMs: Long, val durationMs: Long, val at: Long)

  data class Item(
    /** The add-on's id for the title. */
    val id: String,
    val type: String,
    val name: String,
    val poster: String?,
    val addedAt: Long,
    /** Videos finished; for a movie, its own id. */
    val watched: Set<String> = emptySet(),
    val progress: Progress? = null,
    /** The add-on and binge group last played from, to pick the same source next time. */
    val addon: String? = null,
    val bingeGroup: String? = null,
    /** Added by hand, not only by being watched. */
    val saved: Boolean = false,
  )

  private data class State(val addons: MutableList<Addon>, val items: MutableList<Item>)

  private val lock = Any()
  private var cache: State? = null

  // ---- reading -----------------------------------------------------------------------------

  fun addons(): List<Addon> = synchronized(lock) { read().addons.toList() }

  fun items(): List<Item> = synchronized(lock) { read().items.toList() }

  fun item(id: String): Item? = synchronized(lock) { read().items.firstOrNull { it.id == id } }

  /** Started and not finished, newest first. */
  fun continueWatching(): List<Item> = items().filter { it.progress != null }.sortedByDescending { it.progress!!.at }

  // ---- add-ons -----------------------------------------------------------------------------

  /** Installs an add-on, or refreshes its manifest if it is already there, keeping its place. */
  fun install(base: String, manifest: JSONObject) = write { state ->
    val at = state.addons.indexOfFirst { it.base == base }
    if (at >= 0) state.addons[at] = state.addons[at].copy(manifest = manifest)
    else state.addons.add(Addon(base, manifest))
  }

  fun uninstall(base: String) = write { state -> state.addons.removeAll { it.base == base } }

  fun setEnabled(base: String, enabled: Boolean) = write { state ->
    val at = state.addons.indexOfFirst { it.base == base }
    if (at >= 0) state.addons[at] = state.addons[at].copy(enabled = enabled)
  }

  /** Moves an add-on to [position]: catalogs show, and streams are listed, in this order. */
  fun move(base: String, position: Int) = write { state ->
    val at = state.addons.indexOfFirst { it.base == base }
    if (at < 0) return@write
    val addon = state.addons.removeAt(at)
    state.addons.add(position.coerceIn(0, state.addons.size), addon)
  }

  // ---- the library -------------------------------------------------------------------------

  /** A title as it should be remembered: from its meta or a catalog preview. */
  data class Title(val id: String, val type: String, val name: String, val poster: String?)

  /**
   * Where playback is. Near the end counts as watched: the video joins [Item.watched] and the
   * title's progress is cleared, so it leaves "continue watching" until the next one starts.
   */
  fun recordProgress(
    title: Title,
    videoId: String,
    positionMs: Long,
    durationMs: Long,
    addon: String?,
    bingeGroup: String?,
    now: Long = System.currentTimeMillis(),
  ) = write { state ->
    val existing = state.items.firstOrNull { it.id == title.id }
    val base = existing ?: Item(title.id, title.type, title.name, title.poster, now)
    val finished = durationMs > 0 && positionMs >= durationMs * FINISHED_AT
    val updated = base.copy(
      name = title.name.ifBlank { base.name },
      poster = title.poster ?: base.poster,
      watched = if (finished) base.watched + videoId else base.watched,
      progress = if (finished) null else Progress(videoId, positionMs, durationMs, now),
      addon = addon ?: base.addon,
      bingeGroup = bingeGroup ?: base.bingeGroup,
    )
    if (existing == null) state.items.add(updated) else state.items[state.items.indexOf(existing)] = updated
  }

  fun setWatched(title: Title, videoId: String, watched: Boolean, now: Long = System.currentTimeMillis()) = write { state ->
    val existing = state.items.firstOrNull { it.id == title.id }
    val base = existing ?: Item(title.id, title.type, title.name, title.poster, now)
    val updated = base.copy(
      watched = if (watched) base.watched + videoId else base.watched - videoId,
      progress = base.progress?.takeUnless { watched && it.videoId == videoId },
    )
    if (existing == null) state.items.add(updated) else state.items[state.items.indexOf(existing)] = updated
  }

  fun setSaved(title: Title, saved: Boolean, now: Long = System.currentTimeMillis()) = write { state ->
    val existing = state.items.firstOrNull { it.id == title.id }
    when {
      existing != null -> state.items[state.items.indexOf(existing)] = existing.copy(saved = saved)
      saved -> state.items.add(Item(title.id, title.type, title.name, title.poster, now, saved = true))
    }
  }

  /** Off "continue watching", without marking anything watched. */
  fun dismissProgress(id: String) = write { state ->
    val at = state.items.indexOfFirst { it.id == id }
    if (at >= 0) state.items[at] = state.items[at].copy(progress = null)
  }

  fun remove(id: String) = write { state -> state.items.removeAll { it.id == id } }

  // ---- storage -----------------------------------------------------------------------------

  private fun <T> write(change: (State) -> T): T = synchronized(lock) {
    val state = read()
    val result = change(state)
    val sealed = sealer.seal(pad(encode(state).toString().toByteArray(Charsets.UTF_8)), ASSOCIATED_DATA)
    file.parentFile?.mkdirs()
    // Beside the file and renamed, so an interrupted write cannot leave half a library.
    val temp = File(file.parentFile, "${file.name}.tmp")
    temp.writeBytes(sealed)
    if (!temp.renameTo(file)) {
      file.delete()
      temp.renameTo(file)
    }
    cache = state
    result
  }

  private fun read(): State {
    cache?.let { return it }
    val state = if (file.isFile) {
      decode(JSONObject(String(unpad(sealer.open(file.readBytes(), ASSOCIATED_DATA)), Charsets.UTF_8)))
    } else {
      State(mutableListOf(), mutableListOf())
    }
    cache = state
    return state
  }

  companion object {
    private val ASSOCIATED_DATA = "watch/library/v1".toByteArray(Charsets.UTF_8)

    /** At or past this share of a video, it counts as watched. */
    const val FINISHED_AT = 0.92

    private fun encode(state: State): JSONObject = JSONObject()
      .put("version", 1)
      .put("addons", JSONArray().apply {
        state.addons.forEach { put(JSONObject().put("url", it.base).put("manifest", it.manifest).put("enabled", it.enabled)) }
      })
      .put("items", JSONArray().apply {
        state.items.forEach { item ->
          put(JSONObject()
            .put("id", item.id).put("type", item.type).put("name", item.name).put("poster", item.poster ?: JSONObject.NULL)
            .put("addedAt", item.addedAt).put("watched", JSONArray(item.watched.sorted()))
            .put("progress", item.progress?.let {
              JSONObject().put("videoId", it.videoId).put("positionMs", it.positionMs).put("durationMs", it.durationMs).put("at", it.at)
            } ?: JSONObject.NULL)
            .put("stream", JSONObject().put("addon", item.addon ?: JSONObject.NULL).put("bingeGroup", item.bingeGroup ?: JSONObject.NULL))
            .put("saved", item.saved))
        }
      })

    private fun decode(json: JSONObject): State {
      val addons = mutableListOf<Addon>()
      val a = json.optJSONArray("addons") ?: JSONArray()
      for (i in 0 until a.length()) {
        val o = a.optJSONObject(i) ?: continue
        addons.add(Addon(o.optString("url"), o.optJSONObject("manifest") ?: JSONObject(), o.optBoolean("enabled", true)))
      }
      val items = mutableListOf<Item>()
      val list = json.optJSONArray("items") ?: JSONArray()
      for (i in 0 until list.length()) {
        val o = list.optJSONObject(i) ?: continue
        val p = o.optJSONObject("progress")
        val s = o.optJSONObject("stream")
        items.add(Item(
          id = o.optString("id"), type = o.optString("type"), name = o.optString("name"),
          poster = if (o.isNull("poster")) null else o.optString("poster").ifBlank { null },
          addedAt = o.optLong("addedAt"),
          watched = o.optJSONArray("watched")?.let { w -> (0 until w.length()).map { w.optString(it) }.toSet() }.orEmpty(),
          progress = p?.let { Progress(it.optString("videoId"), it.optLong("positionMs"), it.optLong("durationMs"), it.optLong("at")) },
          addon = s?.takeUnless { it.isNull("addon") }?.optString("addon")?.ifBlank { null },
          bingeGroup = s?.takeUnless { it.isNull("bingeGroup") }?.optString("bingeGroup")?.ifBlank { null },
          saved = o.optBoolean("saved"),
        ))
      }
      return State(addons, items)
    }

    /** Padded before sealing, so the file's size does not count what was watched. */
    private fun pad(content: ByteArray): ByteArray {
      val bucket = 4096
      val out = ByteArray(((content.size + 4) / bucket + 1) * bucket)
      out[0] = (content.size ushr 24).toByte()
      out[1] = (content.size ushr 16).toByte()
      out[2] = (content.size ushr 8).toByte()
      out[3] = content.size.toByte()
      content.copyInto(out, 4)
      return out
    }

    private fun unpad(padded: ByteArray): ByteArray {
      val length = ((padded[0].toInt() and 0xff) shl 24) or ((padded[1].toInt() and 0xff) shl 16) or
        ((padded[2].toInt() and 0xff) shl 8) or (padded[3].toInt() and 0xff)
      require(length in 0..(padded.size - 4)) { "the watch library is damaged" }
      return padded.copyOfRange(4, 4 + length)
    }
  }
}
