package expo.modules.localdownloader.watch

import java.net.URI
import java.nio.charset.StandardCharsets
import org.json.JSONArray
import org.json.JSONObject

/**
 * The Stremio add-on protocol, as `shared/watch/CONTRACT.md` takes it: URLs, manifests and
 * the shapes add-ons answer with. No networking here, so it is tested on the JVM against
 * `shared/watch/VECTORS.json`, which the Mac's Swift is held to as well.
 */
object Addons {

  // ---- URLs --------------------------------------------------------------------------------

  /**
   * An add-on's base from whatever the user pasted: a manifest URL, a base with or without a
   * trailing slash, or a `stremio://` link. Null when it is not an add-on address at all.
   */
  fun base(input: String): String? {
    var text = input.trim()
    if (text.startsWith("stremio://", ignoreCase = true)) text = "https://" + text.substring("stremio://".length)
    val uri = runCatching { URI(text) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase()
    if ((scheme != "http" && scheme != "https") || uri.rawAuthority.isNullOrEmpty()) return null
    if (text.endsWith("/manifest.json")) text = text.removeSuffix("/manifest.json")
    return text.trimEnd('/')
  }

  /** `{base}/{resource}/{type}/{id}[/{extra}].json`, each part encoded as a URI component. */
  fun resourceUrl(base: String, resource: String, type: String, id: String, extra: List<Pair<String, String>> = emptyList()): String {
    val path = StringBuilder(base.trimEnd('/'))
      .append('/').append(component(resource))
      .append('/').append(component(type))
      .append('/').append(component(id))
    if (extra.isNotEmpty()) {
      path.append('/').append(extra.joinToString("&") { (name, value) -> component(name) + "=" + component(value) })
    }
    return path.append(".json").toString()
  }

  /** encodeURIComponent, as Stremio encodes path parts. */
  fun component(value: String): String {
    val out = StringBuilder()
    for (byte in value.toByteArray(StandardCharsets.UTF_8)) {
      val c = byte.toInt() and 0xff
      val ch = c.toChar()
      if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch in "-_.!~*'()") out.append(ch)
      else out.append('%').append("0123456789ABCDEF"[c shr 4]).append("0123456789ABCDEF"[c and 0xf])
    }
    return out.toString()
  }

  // ---- manifests ---------------------------------------------------------------------------

  data class Catalog(val type: String, val id: String, val name: String, val extra: List<String>, val required: List<String>) {
    val searchable: Boolean get() = "search" in extra
    /** A catalog that cannot be listed without an argument (only searched, say) is not a row. */
    val listable: Boolean get() = required.isEmpty()
  }

  data class Manifest(
    val id: String,
    val version: String,
    val name: String,
    val description: String,
    val logo: String?,
    val types: List<String>,
    val idPrefixes: List<String>?,
    /** Each resource: its name, and its own types and prefixes when it gives them. */
    val resources: List<Resource>,
    val catalogs: List<Catalog>,
    /** Lists of other add-ons this one publishes: Cinemeta's official and community ones. */
    val addonCatalogs: List<Catalog>,
    /** It has a page of settings at `{base}/configure`. */
    val configurable: Boolean,
    val configurationRequired: Boolean,
    val json: JSONObject,
  )

  data class Resource(val name: String, val types: List<String>?, val idPrefixes: List<String>?)

  private fun strings(array: JSONArray?): List<String> =
    array?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).ifBlank { null } } }.orEmpty()

  /** Leniently: what is missing is absent; what cannot be read at all is null. */
  fun manifest(json: JSONObject): Manifest? {
    val id = json.optString("id").ifBlank { return null }
    val resources = mutableListOf<Resource>()
    val raw = json.optJSONArray("resources") ?: JSONArray()
    for (i in 0 until raw.length()) {
      when (val r = raw.opt(i)) {
        is String -> resources.add(Resource(r, null, null))
        is JSONObject -> r.optString("name").ifBlank { null }?.let { name ->
          resources.add(Resource(name, r.optJSONArray("types")?.let(::strings), r.optJSONArray("idPrefixes")?.let(::strings)))
        }
      }
    }
    val catalogs = mutableListOf<Catalog>()
    val rawCatalogs = json.optJSONArray("catalogs") ?: JSONArray()
    for (i in 0 until rawCatalogs.length()) {
      val c = rawCatalogs.optJSONObject(i) ?: continue
      val extras = mutableListOf<String>()
      val required = mutableListOf<String>()
      c.optJSONArray("extra")?.let { e ->
        for (j in 0 until e.length()) {
          val x = e.optJSONObject(j) ?: continue
          val name = x.optString("name").ifBlank { null } ?: continue
          extras.add(name)
          if (x.optBoolean("isRequired")) required.add(name)
        }
      }
      // The older form: names in extraSupported, the required ones in extraRequired.
      extras += strings(c.optJSONArray("extraSupported")).filter { it !in extras }
      required += strings(c.optJSONArray("extraRequired")).filter { it !in required }
      catalogs.add(Catalog(c.optString("type"), c.optString("id"), c.optString("name").ifBlank { c.optString("id") }, extras, required))
    }
    return Manifest(
      id = id,
      version = json.optString("version"),
      name = json.optString("name").ifBlank { id },
      description = json.optString("description"),
      logo = json.optString("logo").ifBlank { null },
      types = strings(json.optJSONArray("types")),
      idPrefixes = json.optJSONArray("idPrefixes")?.let(::strings),
      resources = resources,
      catalogs = catalogs,
      addonCatalogs = json.optJSONArray("addonCatalogs")?.let { a ->
        (0 until a.length()).mapNotNull { i ->
          val c = a.optJSONObject(i) ?: return@mapNotNull null
          Catalog(c.optString("type"), c.optString("id"), c.optString("name").ifBlank { c.optString("id") }, emptyList(), emptyList())
        }
      }.orEmpty(),
      configurable = json.optJSONObject("behaviorHints")?.optBoolean("configurable") ?: false,
      configurationRequired = json.optJSONObject("behaviorHints")?.optBoolean("configurationRequired") ?: false,
      json = json,
    )
  }

  /**
   * Whether an add-on answers [resource] for this type and id. A resource's own types and
   * prefixes win over the manifest's; catalogs are named by their own ids, so prefixes do not
   * apply to them.
   */
  fun supports(manifest: Manifest, resource: String, type: String, id: String): Boolean {
    val r = manifest.resources.firstOrNull { it.name == resource } ?: return false
    val types = r.types ?: manifest.types
    if (type !in types) return false
    if (resource == "catalog") return true
    val prefixes = r.idPrefixes ?: manifest.idPrefixes ?: return true
    return prefixes.any { id.startsWith(it) }
  }

  // ---- what add-ons answer with ------------------------------------------------------------

  data class Preview(
    val id: String,
    val type: String,
    val name: String,
    val poster: String?,
    val posterShape: String,
    val releaseInfo: String?,
    val description: String?,
  )

  data class Video(
    val id: String,
    val title: String,
    val season: Int?,
    val episode: Int?,
    val released: String?,
    val thumbnail: String?,
    val overview: String?,
  )

  data class Meta(
    val id: String,
    val type: String,
    val name: String,
    val poster: String?,
    val background: String?,
    val logo: String?,
    val description: String?,
    val releaseInfo: String?,
    val runtime: String?,
    val genres: List<String>,
    val imdbRating: String?,
    val videos: List<Video>,
    /** Trailers as streams: Cinemeta gives YouTube ids in trailerStreams. */
    val trailers: List<Stream> = emptyList(),
  )

  fun previews(json: JSONObject): List<Preview> {
    val metas = json.optJSONArray("metas") ?: return emptyList()
    return (0 until metas.length()).mapNotNull { i ->
      val m = metas.optJSONObject(i) ?: return@mapNotNull null
      Preview(
        id = m.optString("id").ifBlank { return@mapNotNull null },
        type = m.optString("type"),
        name = m.optString("name"),
        poster = m.optString("poster").ifBlank { null }?.takeIf(::isHttp),
        posterShape = m.optString("posterShape").ifBlank { "poster" },
        releaseInfo = m.optString("releaseInfo").ifBlank { null },
        description = m.optString("description").ifBlank { null },
      )
    }
  }

  fun meta(json: JSONObject): Meta? {
    val m = json.optJSONObject("meta") ?: return null
    val videos = m.optJSONArray("videos")?.let { v ->
      (0 until v.length()).mapNotNull { i ->
        val x = v.optJSONObject(i) ?: return@mapNotNull null
        Video(
          id = x.optString("id").ifBlank { return@mapNotNull null },
          title = x.optString("title").ifBlank { x.optString("name") },
          season = if (x.has("season")) x.optInt("season") else null,
          episode = if (x.has("episode")) x.optInt("episode") else if (x.has("number")) x.optInt("number") else null,
          released = x.optString("released").ifBlank { null },
          thumbnail = x.optString("thumbnail").ifBlank { null }?.takeIf(::isHttp),
          overview = x.optString("overview").ifBlank { x.optString("description") }.ifBlank { null },
        )
      }
    }.orEmpty()
    return Meta(
      id = m.optString("id").ifBlank { return null },
      type = m.optString("type"),
      name = m.optString("name"),
      poster = m.optString("poster").ifBlank { null }?.takeIf(::isHttp),
      background = m.optString("background").ifBlank { null }?.takeIf(::isHttp),
      logo = m.optString("logo").ifBlank { null }?.takeIf(::isHttp),
      description = m.optString("description").ifBlank { null },
      releaseInfo = m.optString("releaseInfo").ifBlank { null },
      runtime = m.optString("runtime").ifBlank { null },
      genres = strings(m.optJSONArray("genres")).ifEmpty { strings(m.optJSONArray("genre")) },
      imdbRating = m.optString("imdbRating").ifBlank { null },
      // Specials (season 0) last, the rest in order.
      videos = videos.sortedWith(compareBy<Video>({ (it.season ?: 0) == 0 }, { it.season ?: 0 }, { it.episode ?: 0 })),
      trailers = m.optJSONArray("trailerStreams")?.let { t ->
        (0 until t.length()).mapNotNull { t.optJSONObject(it)?.let(::stream) }
      }.orEmpty(),
    )
  }

  enum class Kind(val wire: String) { URL("url"), YOUTUBE("youtube"), TORRENT("torrent"), EXTERNAL("external") }

  data class Stream(
    val kind: Kind,
    /** What to open: a media or page URL, a YouTube watch URL, a magnet, an external link. */
    val target: String,
    val fileIdx: Int?,
    /** The add-on's name line and its description, each flattened to one line. */
    val label: String,
    val detail: String,
    val bingeGroup: String?,
    val notWebReady: Boolean,
    /** Request headers the add-on says the stream needs. */
    val headers: Map<String, String>,
    val filename: String?,
  )

  private val infoHash = Regex("^[0-9a-fA-F]{40}$")

  private fun oneLine(text: String) = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")

  fun isHttp(url: String): Boolean = url.startsWith("https://", true) || url.startsWith("http://", true)

  /** One stream object; null when it gives nothing this app can open. */
  fun stream(s: JSONObject): Stream? {
    val hints = s.optJSONObject("behaviorHints")
    val (kind, target) = when {
      s.optString("url").isNotBlank() -> Kind.URL to s.optString("url").takeIf(::isHttp)
      s.optString("ytId").isNotBlank() -> Kind.YOUTUBE to "https://www.youtube.com/watch?v=" + component(s.optString("ytId"))
      s.optString("infoHash").isNotBlank() -> Kind.TORRENT to s.optString("infoHash").takeIf { infoHash.matches(it) }
        ?.let { "magnet:?xt=urn:btih:" + it.lowercase() }
      s.optString("externalUrl").isNotBlank() -> Kind.EXTERNAL to s.optString("externalUrl").takeIf(::isHttp)
      else -> return null
    }
    target ?: return null
    val headers = mutableMapOf<String, String>()
    hints?.optJSONObject("proxyHeaders")?.optJSONObject("request")?.let { r ->
      r.keys().forEach { key -> r.optString(key).takeIf { it.isNotEmpty() }?.let { headers[key] = it } }
    }
    return Stream(
      kind = kind,
      target = target,
      fileIdx = if (kind == Kind.TORRENT && s.has("fileIdx")) s.optInt("fileIdx") else null,
      label = oneLine(s.optString("name")),
      detail = oneLine(s.optString("title").ifBlank { s.optString("description") }),
      bingeGroup = hints?.optString("bingeGroup")?.ifBlank { null },
      notWebReady = hints?.optBoolean("notWebReady") ?: false,
      headers = headers,
      filename = hints?.optString("filename")?.ifBlank { null },
    )
  }

  fun streams(json: JSONObject): List<Stream> {
    val list = json.optJSONArray("streams") ?: return emptyList()
    return (0 until list.length()).mapNotNull { list.optJSONObject(it)?.let(::stream) }
  }

  /** An add-on another add-on offers, ready to install from [base]. */
  data class Offer(val base: String, val manifest: Manifest)

  /**
   * An `addon_catalog` answer. Left out: add-ons that run on the device Stremio is on (its
   * local server), the old transport whose address is not a manifest URL, and anything whose
   * manifest cannot be read.
   */
  fun offers(json: JSONObject): List<Offer> {
    val list = json.optJSONArray("addons") ?: return emptyList()
    return (0 until list.length()).mapNotNull { i ->
      val entry = list.optJSONObject(i) ?: return@mapNotNull null
      val url = entry.optString("transportUrl")
      if (!url.endsWith("/manifest.json")) return@mapNotNull null
      val base = base(url) ?: return@mapNotNull null
      val host = runCatching { URI(base).host?.lowercase() }.getOrNull() ?: return@mapNotNull null
      if (host == "127.0.0.1" || host == "localhost" || host == "::1") return@mapNotNull null
      val manifest = entry.optJSONObject("manifest")?.let(::manifest) ?: return@mapNotNull null
      Offer(base, manifest)
    }
  }

  data class Subtitle(val id: String, val url: String, val lang: String)

  fun subtitles(json: JSONObject): List<Subtitle> {
    val list = json.optJSONArray("subtitles") ?: return emptyList()
    return (0 until list.length()).mapNotNull { i ->
      val s = list.optJSONObject(i) ?: return@mapNotNull null
      val url = s.optString("url").takeIf(::isHttp) ?: return@mapNotNull null
      Subtitle(s.optString("id").ifBlank { url }, url, s.optString("lang"))
    }
  }
}
