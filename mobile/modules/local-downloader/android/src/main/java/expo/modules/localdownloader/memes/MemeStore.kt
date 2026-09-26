package expo.modules.localdownloader.memes

import java.io.File
import java.security.SecureRandom
import java.text.Normalizer
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * The meme collection: what is in it, what each one signifies, who is in it, and where it
 * came from. `shared/memes/CONTRACT.md` is the specification; field names and facet ids here
 * are its wire values, and the Mac's MemeLibrary is the other implementation.
 *
 * Two indexes, as on the Mac. The public one is sealed by a [Sealer] the module backs with a
 * Keystore key that needs no prompt, so searching never asks for anything and a copy of the
 * app's files reveals nothing. The private one, [PrivateHalf], is sealed under the vault's
 * key: it holds the private memes and every label that only private memes use, and it is
 * read only while the vault is unlocked.
 *
 * Nothing here touches Android, so the rules are tested on the JVM.
 */
class MemeStore(
  private val indexFile: File,
  private val sealer: Sealer,
  private val privateHalf: PrivateHalf? = null,
) {

  /** The private index, under the vault's key. */
  interface PrivateHalf {
    /** Its plaintext; an empty array when there is none yet; null while the vault is locked. */
    fun read(): ByteArray?

    /** Throws while the vault is locked. Null: no meme is private, so nothing is kept. */
    fun write(plaintext: ByteArray?)

    /**
     * Whether any meme is private, answerable while locked: the file exists only while one
     * does. Its existence was already visible on disk; this says nothing more.
     */
    fun exists(): Boolean
  }

  class LockedException : IllegalStateException("PRIVATE_VAULT_LOCKED")

  /** Encrypts the index. On a device, a Keystore key; in the tests, a plain AES key. */
  interface Sealer {
    fun seal(plaintext: ByteArray, associatedData: ByteArray): ByteArray
    fun open(sealed: ByteArray, associatedData: ByteArray): ByteArray
  }

  enum class Facet(val wire: String) {
    REACTION("reaction"), VIBE("vibe"), EMOTION("emotion"), ACTION("action"), CONTEXT("context");

    companion object {
      fun of(wire: String): Facet? = values().firstOrNull { it.wire == wire }
    }
  }

  data class Tag(val id: String, val name: String, val facets: List<Facet>)
  data class Person(val id: String, val name: String)

  data class Source(
    val platform: String? = null,
    val account: String? = null,
    val accountName: String? = null,
    val caption: String? = null,
    val url: String? = null,
    val postedAt: Long? = null,
    val savedAt: Long = System.currentTimeMillis(),
  )

  data class Item(
    val id: String,
    val kind: String,
    val isPrivate: Boolean,
    /** MediaStore URI, for one that is not private. */
    val uri: String?,
    /** Its vault entry, for one that is. */
    val vaultId: String?,
    val sha256: String,
    val source: Source?,
    val tags: List<String>,
    val people: List<String>,
    val addedAt: Long,
    /** 0 while the meme waits in the untagged inbox. */
    val taggedAt: Long,
  ) {
    val isUntagged: Boolean get() = taggedAt == 0L
  }

  data class Snapshot(val items: List<Item>, val tags: List<Tag>, val people: List<Person>)

  private data class Index(
    val tags: MutableList<Tag> = mutableListOf(),
    val people: MutableList<Person> = mutableListOf(),
    val items: MutableList<Item> = mutableListOf(),
  )

  private val lock = Any()
  private var cache: Index? = null

  // ---- reading ---------------------------------------------------------------------------

  /** Everything visible, newest first: the private memes too while the vault is open. */
  fun snapshot(): Snapshot = synchronized(lock) {
    val all = merged()
    Snapshot(all.items.sortedByDescending { it.addedAt }, all.tags, all.people)
  }

  /**
   * A private index left with nothing in it, by an older build or otherwise, is removed so
   * [hasPrivate] stops answering yes. Only while the vault is open, when it can be read.
   */
  fun tidyPrivate() {
    val half = privateHalf ?: return
    if (!half.exists() || half.read() == null) return
    if (snapshot().items.none { it.isPrivate }) synchronized(lock) { half.write(null) }
  }

  /** Whether any meme is private, locked or not. */
  fun hasPrivate(): Boolean = privateHalf?.exists() == true

  /** Whether the private half could be read just now. */
  fun privateReadable(): Boolean = privateHalf?.read() != null

  // ---- adding ----------------------------------------------------------------------------

  /** A file already saved where it stays (a MediaStore URI) joins the collection. */
  fun add(
    uri: String,
    kind: String,
    sha256: String,
    source: Source?,
    tags: List<Pair<String, List<Facet>>> = emptyList(),
    people: List<String> = emptyList(),
    tagged: Boolean = false,
  ): Item = mutate { index ->
    index.items.firstOrNull { it.sha256 == sha256 && !it.isPrivate }?.let { existing ->
      // The same file again: it only gains whatever labels it carries.
      return@mutate mergeInto(index, existing.id, tags, people)
    }
    val now = System.currentTimeMillis()
    val item = Item(
      id = newId("m"), kind = kind, isPrivate = false, uri = uri, vaultId = null, sha256 = sha256,
      source = source ?: Source(platform = "import"),
      tags = tags.map { (name, facets) -> resolveTag(name, facets, index.tags) }.distinct(),
      people = people.map { resolvePerson(it, index.people) }.distinct(),
      addedAt = now, taggedAt = if (tagged || tags.isNotEmpty() || people.isNotEmpty()) now else 0L,
    )
    index.items.add(item)
    item
  }

  fun remove(itemId: String): Item? = mutate { index ->
    val item = index.items.firstOrNull { it.id == itemId }
    index.items.removeAll { it.id == itemId }
    item
  }

  /** A vault entry joins the collection as a private meme, with labels by name. */
  fun registerPrivate(
    vaultId: String,
    kind: String,
    sha256: String,
    source: Source?,
    tags: List<Pair<String, List<Facet>>> = emptyList(),
    people: List<String> = emptyList(),
    taggedAt: Long? = null,
  ): Item = mutate { index ->
    index.items.firstOrNull { it.vaultId == vaultId }?.let { return@mutate mergeInto(index, it.id, tags, people) }
    val now = System.currentTimeMillis()
    val item = Item(
      id = newId("m"), kind = kind, isPrivate = true, uri = null, vaultId = vaultId, sha256 = sha256,
      source = source ?: Source(platform = "import"),
      tags = tags.map { (name, facets) -> resolveTag(name, facets, index.tags) }.distinct(),
      people = people.map { resolvePerson(it, index.people) }.distinct(),
      addedAt = now,
      taggedAt = taggedAt ?: if (tags.isNotEmpty() || people.isNotEmpty()) now else 0L,
    )
    index.items.add(item)
    item
  }

  /** The file has moved into the vault as [vaultId]; its labels move to the private index. */
  fun movedToVault(itemId: String, vaultId: String) = mutate { index ->
    val at = index.items.indexOfFirst { it.id == itemId }
    if (at >= 0) index.items[at] = index.items[at].copy(isPrivate = true, uri = null, vaultId = vaultId)
  }

  /** The file has come out of the vault to [uri]. */
  fun movedOutOfVault(itemId: String, uri: String) = mutate { index ->
    val at = index.items.indexOfFirst { it.id == itemId }
    if (at >= 0) index.items[at] = index.items[at].copy(isPrivate = false, uri = uri, vaultId = null)
  }

  /** The vault entry [vaultId] is gone, and the private meme that was it with it. */
  fun forgetVault(vaultId: String) {
    if (snapshot().items.none { it.vaultId == vaultId }) return
    mutate { index -> index.items.removeAll { it.vaultId == vaultId } }
  }

  /** Items whose files have gone, dropped. Returns how many. */
  fun reconcile(exists: (Item) -> Boolean): Int = mutate { index ->
    val before = index.items.size
    index.items.removeAll { !it.isPrivate && !exists(it) }
    before - index.items.size
  }

  // ---- labels ----------------------------------------------------------------------------

  fun tag(name: String, facets: List<Facet> = emptyList()): Tag = mutate { index ->
    val id = resolveTag(name, facets, index.tags)
    index.tags.first { it.id == id }
  }

  fun person(name: String): Person = mutate { index ->
    val id = resolvePerson(name, index.people)
    index.people.first { it.id == id }
  }

  fun setFacets(tagId: String, facets: List<Facet>) = mutate { index ->
    val at = index.tags.indexOfFirst { it.id == tagId }
    if (at >= 0) index.tags[at] = index.tags[at].copy(facets = facets.distinct())
  }

  fun renameTag(tagId: String, name: String) = mutate { index ->
    val trimmed = name.trim()
    val at = index.tags.indexOfFirst { it.id == tagId }
    if (at >= 0 && trimmed.isNotEmpty()) index.tags[at] = index.tags[at].copy(name = trimmed)
  }

  fun deleteTag(tagId: String) = mutate { index ->
    index.tags.removeAll { it.id == tagId }
    for (i in index.items.indices) {
      index.items[i] = index.items[i].copy(tags = index.items[i].tags - tagId)
    }
  }

  /**
   * Adds and removes labels on many memes at once. [markTagged] takes them out of the inbox:
   * "Save" on an empty sheet means done.
   */
  fun label(
    itemIds: Set<String>,
    addTags: Set<String> = emptySet(),
    removeTags: Set<String> = emptySet(),
    addPeople: Set<String> = emptySet(),
    removePeople: Set<String> = emptySet(),
    markTagged: Boolean = true,
  ) = mutate { index ->
    val tagIds = index.tags.map { it.id }.toSet()
    val personIds = index.people.map { it.id }.toSet()
    val now = System.currentTimeMillis()
    for (i in index.items.indices) {
      val item = index.items[i]
      if (item.id !in itemIds) continue
      index.items[i] = item.copy(
        tags = ((item.tags.toSet() + addTags - removeTags) intersect tagIds).toList(),
        people = ((item.people.toSet() + addPeople - removePeople) intersect personIds).toList(),
        taggedAt = if (markTagged) now else item.taggedAt,
      )
    }
  }

  /** A meme from another device or a backup: labels merged into these by name. */
  fun receive(
    uri: String,
    kind: String,
    sha256: String,
    source: Source?,
    tags: List<Pair<String, List<Facet>>>,
    people: List<String>,
  ): Item = add(uri, kind, sha256, source, tags, people)

  /** Makes sure these labels exist, with at least these facets. */
  fun ensure(tags: List<Pair<String, List<Facet>>>, people: List<String>) = mutate { index ->
    tags.forEach { (name, facets) -> resolveTag(name, facets, index.tags) }
    people.forEach { resolvePerson(it, index.people) }
  }

  /** The labels of one meme by name, for sending, a backup, or a vault entry. */
  fun labelsOf(item: Item, snapshot: Snapshot): Pair<List<Pair<String, List<Facet>>>, List<String>> {
    val tags = item.tags.mapNotNull { id -> snapshot.tags.firstOrNull { it.id == id } }.map { it.name to it.facets }
    val people = item.people.mapNotNull { id -> snapshot.people.firstOrNull { it.id == id }?.name }
    return tags to people
  }

  // ---- search ----------------------------------------------------------------------------

  data class Filter(
    val facets: Set<Facet> = emptySet(),
    val people: Set<String> = emptySet(),
    val platform: String? = null,
    val onlyPrivate: Boolean = false,
    val onlyUntagged: Boolean = false,
  )

  // ---- storage ---------------------------------------------------------------------------

  private fun <T> mutate(change: (Index) -> T): T = synchronized(lock) {
    val lockedBefore = privateHalf?.read() == null
    val all = merged()
    val result = change(all)
    // Locked, the private memes were never read, so they must not be written over.
    if (lockedBefore && all.items.any { it.isPrivate }) throw LockedException()
    save(all, writePrivate = !lockedBefore)
    result
  }

  private fun merged(): Index {
    val open = readPublic()
    val hidden = privateHalf?.read()?.takeIf { it.isNotEmpty() }?.let { decode(JSONObject(String(it, Charsets.UTF_8))) }
    val all = Index(open.tags.toMutableList(), open.people.toMutableList(), open.items.toMutableList())
    if (hidden != null) {
      all.items += hidden.items.map { it.copy(isPrivate = true) }
      val tagIds = open.tags.map { it.id }.toSet()
      all.tags += hidden.tags.filter { it.id !in tagIds }
      val personIds = open.people.map { it.id }.toSet()
      all.people += hidden.people.filter { it.id !in personIds }
    }
    return all
  }

  /**
   * Splits the merged view back into its two files. A label used only by private memes goes
   * with them, so the public index never names it.
   */
  private fun save(all: Index, writePrivate: Boolean) {
    val hiddenItems = all.items.filter { it.isPrivate }
    val openItems = all.items.filter { !it.isPrivate }
    val openTags = openItems.flatMap { it.tags }.toSet()
    val hiddenTags = hiddenItems.flatMap { it.tags }.toSet() - openTags
    val openPeople = openItems.flatMap { it.people }.toSet()
    val hiddenPeople = hiddenItems.flatMap { it.people }.toSet() - openPeople

    val open = Index(
      all.tags.filter { it.id !in hiddenTags }.toMutableList(),
      all.people.filter { it.id !in hiddenPeople }.toMutableList(),
      openItems.toMutableList(),
    )
    writePublic(open)
    if (writePrivate && privateHalf != null && hiddenItems.isEmpty()) {
      privateHalf.write(null)
    } else if (writePrivate && privateHalf != null) {
      val hidden = Index(
        all.tags.filter { it.id in hiddenTags }.toMutableList(),
        all.people.filter { it.id in hiddenPeople }.toMutableList(),
        hiddenItems.toMutableList(),
      )
      privateHalf.write(encode(hidden).toString().toByteArray(Charsets.UTF_8))
    }
  }

  private fun readPublic(): Index {
    cache?.let { return it.copyDeep() }
    val index = if (indexFile.isFile) {
      val plain = sealer.open(indexFile.readBytes(), ASSOCIATED_DATA)
      decode(JSONObject(String(unpad(plain), Charsets.UTF_8)))
    } else {
      Index()
    }
    cache = index
    return index.copyDeep()
  }

  private fun writePublic(index: Index) {
    val sealed = sealer.seal(pad(encode(index).toString().toByteArray(Charsets.UTF_8)), ASSOCIATED_DATA)
    indexFile.parentFile?.mkdirs()
    // Beside the file and renamed, so an interrupted write cannot leave half an index.
    val temp = File(indexFile.parentFile, "${indexFile.name}.tmp")
    temp.writeBytes(sealed)
    if (!temp.renameTo(indexFile)) {
      indexFile.delete()
      temp.renameTo(indexFile)
    }
    cache = index.copyDeep()
  }

  private fun Index.copyDeep() = Index(tags.toMutableList(), people.toMutableList(), items.toMutableList())

  private fun mergeInto(index: Index, itemId: String, tags: List<Pair<String, List<Facet>>>, people: List<String>): Item {
    val at = index.items.indexOfFirst { it.id == itemId }
    val item = index.items[at]
    val tagIds = tags.map { (name, facets) -> resolveTag(name, facets, index.tags) }
    val personIds = people.map { resolvePerson(it, index.people) }
    val merged = item.copy(
      tags = (item.tags + tagIds).distinct(),
      people = (item.people + personIds).distinct(),
      taggedAt = if (item.taggedAt == 0L && (tagIds.isNotEmpty() || personIds.isNotEmpty())) System.currentTimeMillis() else item.taggedAt,
    )
    index.items[at] = merged
    return merged
  }

  companion object {
    private val ASSOCIATED_DATA = "memes/index/v1".toByteArray(Charsets.UTF_8)
    private val TURKISH = Locale("tr")
    private val random = SecureRandom()

    /**
     * Lower case the Turkish way (I→ı, İ→i), then plain letters: ı→i, ş→s, ğ→g, ç→c, ö→o,
     * ü→u. "LAUBALİLİK", "laubalılık" and "laubalilik" all come out the same. The Mac's
     * MemeLibrary.fold is the same function.
     */
    fun fold(text: String): String {
      val lowered = text.lowercase(TURKISH).replace('ı', 'i')
      return Normalizer.normalize(lowered, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
    }

    fun tokens(text: String): List<String> =
      fold(text).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }

    /**
     * Every word required, each matching by prefix a tag, a person, a word of the caption,
     * or the account.
     */
    fun search(snapshot: Snapshot, query: String, filter: Filter = Filter()): List<Item> {
      val words = tokens(query)
      val tagsById = snapshot.tags.associateBy { it.id }
      val peopleById = snapshot.people.associateBy { it.id }
      return snapshot.items.filter { item ->
        if (filter.onlyPrivate && !item.isPrivate) return@filter false
        if (filter.onlyUntagged && !item.isUntagged) return@filter false
        if (filter.platform != null && item.source?.platform != filter.platform) return@filter false
        if (!item.people.containsAll(filter.people)) return@filter false
        val tags = item.tags.mapNotNull { tagsById[it] }
        if (filter.facets.isNotEmpty() && !tags.flatMap { it.facets }.toSet().containsAll(filter.facets)) {
          return@filter false
        }
        if (words.isEmpty()) return@filter true
        val haystack = tags.flatMap { tokens(it.name) } +
          item.people.mapNotNull { peopleById[it] }.flatMap { tokens(it.name) } +
          tokens(item.source?.caption.orEmpty()) + tokens(item.source?.account.orEmpty()) +
          tokens(item.source?.accountName.orEmpty())
        words.all { word -> haystack.any { it.startsWith(word) } }
      }
    }

    /** Caption words that are tags, tags from the same account's memes, recent tags. */
    fun suggestions(item: Item, snapshot: Snapshot, limit: Int = 12): List<Tag> {
      val scored = mutableMapOf<String, Double>()
      val captionWords = tokens(item.source?.caption.orEmpty()).toSet()
      for (tag in snapshot.tags) {
        val words = tokens(tag.name)
        if (words.isNotEmpty() && words.all { word -> captionWords.any { it.startsWith(word) } }) {
          scored[tag.id] = (scored[tag.id] ?: 0.0) + 10
        }
      }
      val account = item.source?.account
      if (!account.isNullOrEmpty()) {
        for (other in snapshot.items) {
          if (other.id != item.id && other.source?.account == account) {
            other.tags.forEach { scored[it] = (scored[it] ?: 0.0) + 3 }
          }
        }
      }
      snapshot.items.filter { it.taggedAt > 0 }.sortedByDescending { it.taggedAt }.take(20)
        .forEachIndexed { rank, other -> other.tags.forEach { scored[it] = (scored[it] ?: 0.0) + 1.0 / (rank + 2) } }
      val byId = snapshot.tags.associateBy { it.id }
      return scored.filterKeys { it !in item.tags }.entries
        .sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key })
        .take(limit).mapNotNull { byId[it.key] }
    }

    fun newId(prefix: String): String {
      val bytes = ByteArray(12).also { random.nextBytes(it) }
      return prefix + bytes.joinToString("") { "%02x".format(it) }
    }

    private fun resolveTag(name: String, facets: List<Facet>, tags: MutableList<Tag>): String {
      val trimmed = name.trim()
      val key = fold(trimmed)
      val at = tags.indexOfFirst { fold(it.name) == key }
      if (at >= 0) {
        // Facets only accumulate: an arriving label never takes one away.
        tags[at] = tags[at].copy(facets = (tags[at].facets + facets).distinct())
        return tags[at].id
      }
      val tag = Tag(newId("t"), trimmed, facets.distinct())
      tags.add(tag)
      return tag.id
    }

    private fun resolvePerson(name: String, people: MutableList<Person>): String {
      val trimmed = name.trim()
      val key = fold(trimmed)
      people.firstOrNull { fold(it.name) == key }?.let { return it.id }
      val person = Person(newId("p"), trimmed)
      people.add(person)
      return person.id
    }

    // ---- the meme object: pairing, backup, vault entries ------------------------------

    /** The contract's `meme` object: kind, source, and labels by name. */
    fun encodeMeme(kind: String, source: Source?, tags: List<Pair<String, List<Facet>>>, people: List<String>): JSONObject =
      JSONObject()
        .put("kind", kind)
        .put("source", encodeSource(source))
        .put("tags", JSONArray().apply {
          tags.forEach { (name, facets) ->
            put(JSONObject().put("name", name).put("facets", JSONArray(facets.map { it.wire })))
          }
        })
        .put("people", JSONArray().apply { people.forEach { put(JSONObject().put("name", it)) } })

    data class Decoded(val kind: String, val source: Source?, val tags: List<Pair<String, List<Facet>>>, val people: List<String>)

    /** Leniently: an unknown facet is dropped, a missing field is absent. */
    fun decodeMeme(json: JSONObject?): Decoded {
      val tags = mutableListOf<Pair<String, List<Facet>>>()
      val tagArray = json?.optJSONArray("tags") ?: JSONArray()
      for (i in 0 until tagArray.length()) {
        val tag = tagArray.optJSONObject(i) ?: continue
        val name = tag.optString("name").trim().take(80)
        if (name.isEmpty()) continue
        val facetArray = tag.optJSONArray("facets") ?: JSONArray()
        tags.add(name to (0 until facetArray.length()).mapNotNull { Facet.of(facetArray.optString(it)) })
      }
      val peopleArray = json?.optJSONArray("people") ?: JSONArray()
      val people = (0 until peopleArray.length()).mapNotNull {
        peopleArray.optJSONObject(it)?.optString("name")?.trim()?.take(80)?.takeIf { name -> name.isNotEmpty() }
      }
      return Decoded(json?.optString("kind")?.ifBlank { null } ?: "video", decodeSource(json?.optJSONObject("source")), tags, people)
    }

    fun encodeSource(source: Source?): JSONObject? = source?.let {
      JSONObject().apply {
        it.platform?.let { v -> put("platform", v) }
        it.account?.let { v -> put("account", v) }
        it.accountName?.let { v -> put("accountName", v) }
        it.caption?.let { v -> put("caption", v) }
        it.url?.let { v -> put("url", v) }
        it.postedAt?.let { v -> put("postedAt", v) }
        put("savedAt", it.savedAt)
      }
    }

    fun decodeSource(json: JSONObject?): Source? = json?.let {
      Source(
        platform = it.optString("platform").ifBlank { null },
        account = it.optString("account").ifBlank { null },
        accountName = it.optString("accountName").ifBlank { null },
        caption = it.optString("caption").ifBlank { null },
        url = it.optString("url").ifBlank { null },
        postedAt = if (it.has("postedAt") && !it.isNull("postedAt")) it.optDouble("postedAt").toLong() else null,
        savedAt = if (it.has("savedAt")) it.optDouble("savedAt").toLong() else System.currentTimeMillis(),
      )
    }

    // ---- index codec -----------------------------------------------------------------

    private fun encode(index: Index): JSONObject = JSONObject()
      .put("version", 1)
      .put("tags", JSONArray().apply {
        index.tags.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("facets", JSONArray(it.facets.map { f -> f.wire }))) }
      })
      .put("people", JSONArray().apply { index.people.forEach { put(JSONObject().put("id", it.id).put("name", it.name)) } })
      .put("items", JSONArray().apply {
        index.items.forEach { item ->
          put(JSONObject()
            .put("id", item.id).put("kind", item.kind).put("private", item.isPrivate)
            .put("uri", item.uri).put("vaultId", item.vaultId).put("sha256", item.sha256)
            .put("source", encodeSource(item.source))
            .put("tags", JSONArray(item.tags)).put("people", JSONArray(item.people))
            .put("addedAt", item.addedAt).put("taggedAt", item.taggedAt))
        }
      })

    private fun decode(json: JSONObject): Index {
      val index = Index()
      val tags = json.optJSONArray("tags") ?: JSONArray()
      for (i in 0 until tags.length()) {
        val t = tags.optJSONObject(i) ?: continue
        val facets = t.optJSONArray("facets") ?: JSONArray()
        index.tags.add(Tag(t.optString("id"), t.optString("name"), (0 until facets.length()).mapNotNull { Facet.of(facets.optString(it)) }))
      }
      val people = json.optJSONArray("people") ?: JSONArray()
      for (i in 0 until people.length()) {
        val p = people.optJSONObject(i) ?: continue
        index.people.add(Person(p.optString("id"), p.optString("name")))
      }
      val items = json.optJSONArray("items") ?: JSONArray()
      for (i in 0 until items.length()) {
        val m = items.optJSONObject(i) ?: continue
        fun strings(key: String) = m.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
        index.items.add(Item(
          id = m.optString("id"), kind = m.optString("kind", "video"), isPrivate = m.optBoolean("private", false),
          uri = m.optString("uri").ifBlank { null }, vaultId = m.optString("vaultId").ifBlank { null },
          sha256 = m.optString("sha256"),
          source = decodeSource(m.optJSONObject("source")), tags = strings("tags"), people = strings("people"),
          addedAt = m.optLong("addedAt"), taggedAt = m.optLong("taggedAt"),
        ))
      }
      return index
    }

    /** Padded before sealing, so the file's size does not count the memes. */
    private fun pad(content: ByteArray): ByteArray {
      val bucket = 4096
      val total = ((content.size + 4) / bucket + 1) * bucket
      val out = ByteArray(total)
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
      require(length in 0..(padded.size - 4)) { "the meme index is damaged" }
      return padded.copyOfRange(4, 4 + length)
    }
  }
}
