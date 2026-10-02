package expo.modules.localdownloader.watch

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import org.json.JSONObject

/**
 * Add-ons over the network: manifests, catalogs, metas and streams, each request with firm
 * timeouts so a slow add-on is reported as slow instead of holding anything up
 * (`shared/watch/CONTRACT.md`). Add-on URLs never leave here except to the add-on itself:
 * the screens get each add-on's name, host and an opaque [key].
 */
class WatchService(val library: WatchLibrary) {

  class Failure(val code: String) : Exception(code)

  /** An add-on's handle for the screens: stable, and saying nothing about its URL. */
  fun key(base: String): String =
    MessageDigest.getInstance("SHA-256").digest(base.toByteArray()).take(8).joinToString("") { "%02x".format(it) }

  fun addon(key: String): WatchLibrary.Addon = library.addons().firstOrNull { key(it.base) == key } ?: throw Failure("WATCH_NO_ADDON")

  /** Enabled add-ons, in order, with their manifests read. */
  fun enabled(): List<Pair<WatchLibrary.Addon, Addons.Manifest>> =
    library.addons().filter { it.enabled }.mapNotNull { a -> Addons.manifest(a.manifest)?.let { a to it } }

  fun host(base: String): String = runCatching { URL(base).host }.getOrDefault("")

  // ---- requests ----------------------------------------------------------------------------

  fun getJson(url: String, timeoutMs: Int = 12_000): JSONObject {
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
      connectTimeout = 8_000
      readTimeout = timeoutMs
      instanceFollowRedirects = true
      setRequestProperty("Accept", "application/json")
      setRequestProperty("User-Agent", USER_AGENT)
    }
    try {
      val status = connection.responseCode
      if (status !in 200..299) throw Failure("WATCH_HTTP_$status")
      val body = connection.inputStream.use { input ->
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
          val read = input.read(buffer)
          if (read < 0) break
          out.write(buffer, 0, read)
          if (out.size() > MAX_RESPONSE) throw Failure("WATCH_TOO_LARGE")
        }
        out.toByteArray()
      }
      return runCatching { JSONObject(String(body, Charsets.UTF_8)) }.getOrElse { throw Failure("WATCH_NOT_JSON") }
    } catch (failure: Failure) {
      throw failure
    } catch (_: java.net.SocketTimeoutException) {
      throw Failure("WATCH_TIMEOUT")
    } catch (_: java.io.IOException) {
      throw Failure("WATCH_NETWORK")
    } finally {
      connection.disconnect()
    }
  }

  /**
   * Whether a stream's URL is a page rather than media, so it goes through yt-dlp first. Asked
   * with a HEAD request; when the server will not say, it is taken to be media and played.
   */
  fun isPage(url: String, headers: Map<String, String>): Boolean = runCatching {
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
      requestMethod = "HEAD"
      connectTimeout = 6_000
      readTimeout = 6_000
      instanceFollowRedirects = true
      setRequestProperty("User-Agent", USER_AGENT)
      headers.forEach { (k, v) -> setRequestProperty(k, v) }
    }
    try {
      connection.responseCode
      connection.contentType?.lowercase()?.startsWith("text/html") == true
    } finally {
      connection.disconnect()
    }
  }.getOrDefault(false)

  // ---- add-ons -----------------------------------------------------------------------------

  /** Installs from whatever the user pasted; the manifest's name, or a failure code. */
  fun install(input: String): Addons.Manifest {
    val base = Addons.base(input) ?: throw Failure("WATCH_BAD_URL")
    val json = getJson("$base/manifest.json")
    val manifest = Addons.manifest(json) ?: throw Failure("WATCH_BAD_MANIFEST")
    if (manifest.configurationRequired) throw Failure("WATCH_NEEDS_CONFIGURATION")
    library.install(base, json)
    return manifest
  }

  // ---- what the screens ask for ------------------------------------------------------------

  data class Row(val addonKey: String, val addonName: String, val catalog: Addons.Catalog)

  /** Every listable catalog of every enabled add-on, in the add-ons' order. */
  fun rows(): List<Row> = enabled().flatMap { (addon, manifest) ->
    manifest.catalogs.filter { it.listable }.map { Row(key(addon.base), manifest.name, it) }
  }

  /** Catalogs that can be searched. */
  fun searchable(): List<Row> = enabled().flatMap { (addon, manifest) ->
    manifest.catalogs.filter { it.searchable }.map { Row(key(addon.base), manifest.name, it) }
  }

  fun catalog(addonKey: String, type: String, id: String, extra: List<Pair<String, String>>): List<Addons.Preview> =
    Addons.previews(getJson(Addons.resourceUrl(addon(addonKey).base, "catalog", type, id, extra)))

  /** The first add-on, in order, that gives a meta for this title. */
  fun meta(type: String, id: String): Pair<String, Addons.Meta> {
    var last: Failure = Failure("WATCH_NO_META")
    for ((addon, manifest) in enabled()) {
      if (!Addons.supports(manifest, "meta", type, id)) continue
      try {
        Addons.meta(getJson(Addons.resourceUrl(addon.base, "meta", type, id)))?.let { return key(addon.base) to it }
      } catch (failure: Failure) {
        last = failure
      }
    }
    throw last
  }

  /** Add-ons that offer streams for this video, so the screen can ask each on its own. */
  fun streamSources(type: String, id: String): List<Pair<String, String>> = enabled()
    .filter { (_, manifest) -> Addons.supports(manifest, "stream", type, id) }
    .map { (addon, manifest) -> key(addon.base) to manifest.name }

  fun streams(addonKey: String, type: String, id: String): List<Addons.Stream> =
    Addons.streams(getJson(Addons.resourceUrl(addon(addonKey).base, "stream", type, id), timeoutMs = 20_000))

  /**
   * The lists of add-ons that installed add-ons publish (Cinemeta's official and community
   * ones), one per list: the "all" type where there is one, so a list is not shown per type.
   */
  fun offerLists(): List<Row> = enabled().flatMap { (addon, manifest) ->
    if (manifest.resources.none { it.name == "addon_catalog" }) return@flatMap emptyList()
    manifest.addonCatalogs.groupBy { it.id }.values.map { same -> same.firstOrNull { it.type == "all" } ?: same.first() }
      .map { Row(key(addon.base), manifest.name, it) }
  }

  fun offers(addonKey: String, type: String, id: String): List<Addons.Offer> =
    Addons.offers(getJson(Addons.resourceUrl(addon(addonKey).base, "addon_catalog", type, id), timeoutMs = 20_000))

  companion object {
    private const val USER_AGENT = "Arsivinyo"
    private const val MAX_RESPONSE = 16 * 1024 * 1024
  }
}
