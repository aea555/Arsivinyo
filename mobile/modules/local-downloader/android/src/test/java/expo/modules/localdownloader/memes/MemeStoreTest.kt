package expo.modules.localdownloader.memes

import expo.modules.localdownloader.memes.MemeStore.Facet
import java.io.File
import java.nio.file.Files
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The meme collection, held to `shared/memes/CONTRACT.md`'s "done" list and to
 * `shared/memes/VECTORS.json`, which the Mac's CoreChecks read as well.
 */
class MemeStoreTest {

  /** AES-GCM with a fixed key: what the Keystore key does on a device. */
  private class TestSealer(private val key: ByteArray = ByteArray(32) { it.toByte() }) : MemeStore.Sealer {
    override fun seal(plaintext: ByteArray, associatedData: ByteArray): ByteArray {
      val nonce = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }
      val cipher = Cipher.getInstance("AES/GCM/NoPadding")
      cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
      cipher.updateAAD(associatedData)
      return nonce + cipher.doFinal(plaintext)
    }

    override fun open(sealed: ByteArray, associatedData: ByteArray): ByteArray {
      val cipher = Cipher.getInstance("AES/GCM/NoPadding")
      cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, sealed, 0, 12))
      cipher.updateAAD(associatedData)
      return cipher.doFinal(sealed, 12, sealed.size - 12)
    }
  }

  private val folder: File = Files.createTempDirectory("memes").toFile()
  private fun store(sealer: MemeStore.Sealer = TestSealer()) = MemeStore(File(folder, "index.bin"), sealer)

  private val arda = MemeStore.Source(platform = "twitter", account = "futbolcaps", caption = "bizim laubalilik seviyesi")

  @Test
  fun aDownloadIsFoundByItsCaptionUntagged() {
    val memes = store()
    val item = memes.add("content://media/1", "video", "aa", arda)
    val snapshot = memes.snapshot()
    assertTrue(item.isUntagged)
    assertEquals(listOf(item.id), MemeStore.search(snapshot, "laubali").map { it.id })
    for (query in listOf("laubalilik", "LAUBALİLİK", "laubalılık", "Laubalılık", "bizim seviye")) {
      assertEquals(query, listOf(item.id), MemeStore.search(snapshot, query).map { it.id })
    }
    assertTrue(MemeStore.search(snapshot, "bizim yok").isEmpty())
  }

  @Test
  fun foldingMatchesTheSharedVectors() {
    var dir: File? = File(System.getProperty("user.dir")).absoluteFile
    while (dir != null && !File(dir, "shared/memes/VECTORS.json").exists()) dir = dir.parentFile
    val vectors = JSONObject(File(dir ?: fail("shared/memes/VECTORS.json not found") as Nothing, "shared/memes/VECTORS.json").readText())
    val fold = vectors.getJSONArray("fold")
    for (i in 0 until fold.length()) {
      val pair = fold.getJSONArray(i)
      assertEquals(pair.getString(0), pair.getString(1), MemeStore.fold(pair.getString(0)))
    }
    val tokens = vectors.getJSONArray("tokens")
    for (i in 0 until tokens.length()) {
      val pair = tokens.getJSONArray(i)
      val expected = pair.getJSONArray(1).let { a -> (0 until a.length()).map { a.getString(it) } }
      assertEquals(expected, MemeStore.tokens(pair.getString(0)))
    }
  }

  @Test
  fun aTagCarriesAnyNumberOfFacetsAndFiltersByThem() {
    val memes = store()
    val item = memes.add("content://media/1", "video", "aa", arda)
    val tag = memes.tag("laubalilik", listOf(Facet.VIBE, Facet.REACTION))
    val plain = memes.tag("futbol")
    memes.label(setOf(item.id), addTags = setOf(tag.id, plain.id))
    val snapshot = memes.snapshot()
    assertFalse(snapshot.items.single().isUntagged)
    assertEquals(1, MemeStore.search(snapshot, "", MemeStore.Filter(facets = setOf(Facet.VIBE, Facet.REACTION))).size)
    assertEquals(0, MemeStore.search(snapshot, "", MemeStore.Filter(facets = setOf(Facet.ACTION))).size)
    assertEquals(1, MemeStore.search(snapshot, "futb").size)
  }

  @Test
  fun labelsResolveByFoldedNameAndFacetsOnlyAccumulate() {
    val memes = store()
    val first = memes.tag("Laubalılık", listOf(Facet.VIBE))
    val again = memes.tag("LAUBALİLİK", listOf(Facet.EMOTION))
    assertEquals(first.id, again.id)
    assertEquals(listOf(Facet.VIBE, Facet.EMOTION), again.facets)
    memes.ensure(listOf("laubalilik" to emptyList()), listOf("Arda Turan"))
    val snapshot = memes.snapshot()
    assertEquals(1, snapshot.tags.size)
    assertEquals(listOf(Facet.VIBE, Facet.EMOTION), snapshot.tags.single().facets)
    assertEquals(memes.person("arda turan").id, snapshot.people.single().id)
  }

  @Test
  fun batchTaggingAddsAndRemovesAcrossMany() {
    val memes = store()
    val a = memes.add("content://media/1", "video", "aa", null)
    val b = memes.add("content://media/2", "image", "bb", null)
    val tag = memes.tag("tepki")
    val person = memes.person("Arda Turan")
    memes.label(setOf(a.id, b.id), addTags = setOf(tag.id), addPeople = setOf(person.id))
    memes.label(setOf(b.id), removeTags = setOf(tag.id))
    val byId = memes.snapshot().items.associateBy { it.id }
    assertEquals(listOf(tag.id), byId.getValue(a.id).tags)
    assertEquals(emptyList<String>(), byId.getValue(b.id).tags)
    assertEquals(listOf(person.id), byId.getValue(b.id).people)
    assertEquals(setOf(a.id), MemeStore.search(memes.snapshot(), "tepki").map { it.id }.toSet())
    assertEquals(2, MemeStore.search(memes.snapshot(), "arda").size)
  }

  @Test
  fun deletingATagTakesItOffEveryMeme() {
    val memes = store()
    val a = memes.add("content://media/1", "video", "aa", null)
    val tag = memes.tag("gone")
    memes.label(setOf(a.id), addTags = setOf(tag.id))
    memes.deleteTag(tag.id)
    val snapshot = memes.snapshot()
    assertTrue(snapshot.tags.isEmpty())
    assertTrue(snapshot.items.single().tags.isEmpty())
  }

  @Test
  fun theSameFileTwiceMergesLabelsInsteadOfDuplicating() {
    val memes = store()
    val first = memes.add("content://media/1", "video", "aa", arda)
    val second = memes.receive("content://media/9", "video", "aa", arda, listOf("tepki" to listOf(Facet.REACTION)), listOf("Arda"))
    assertEquals(first.id, second.id)
    val item = memes.snapshot().items.single()
    assertEquals(1, item.tags.size)
    assertFalse(item.isUntagged)
  }

  @Test
  fun theIndexSurvivesReopeningAndNeedsItsKey() {
    store().apply {
      val item = add("content://media/1", "video", "aa", arda)
      label(setOf(item.id), addTags = setOf(tag("laubalilik", listOf(Facet.VIBE)).id))
    }
    val reopened = store().snapshot()
    assertEquals(1, reopened.items.size)
    assertEquals("laubalilik", reopened.tags.single().name)
    try {
      store(TestSealer(ByteArray(32) { 7 })).snapshot()
      fail("the index opened under another key")
    } catch (_: Exception) {
    }
  }

  @Test
  fun theIndexHoldsNothingInPlainTextAndHidesItsSize() {
    val memes = store()
    memes.add("content://media/1", "video", "aa", arda)
    memes.tag("laubalilik")
    memes.person("Arda Turan")
    val bytes = File(folder, "index.bin").readBytes()
    val text = String(bytes, Charsets.ISO_8859_1)
    for (secret in listOf("laubalilik", "Arda", "futbolcaps", "bizim", "twitter")) {
      assertFalse(secret, text.contains(secret))
    }
    val small = bytes.size
    memes.add("content://media/2", "video", "bb", null)
    assertEquals("one more meme does not change the file's size", small, File(folder, "index.bin").readBytes().size)
  }

  /** The vault's half: readable only while [unlocked]. */
  private class FakeVault : MemeStore.PrivateHalf {
    var unlocked = true
    var stored: ByteArray? = null
    override fun read(): ByteArray? = if (unlocked) stored ?: ByteArray(0) else null
    override fun write(plaintext: ByteArray?) {
      check(unlocked) { "locked" }
      stored = plaintext
    }
    override fun exists() = stored != null
  }

  @Test
  fun aPrivateMemeIsAbsentWhileLockedAndItsLabelsStayPrivate() {
    val vault = FakeVault()
    val memes = MemeStore(File(folder, "index.bin"), TestSealer(), vault)
    val shared = memes.tag("tepki", listOf(Facet.REACTION))
    memes.label(setOf(memes.add("content://media/1", "video", "aa", null).id), addTags = setOf(shared.id))
    val hidden = memes.registerPrivate(
      "vault-1", "video", "cc", MemeStore.Source(caption = "gizli yazı"),
      tags = listOf("tepki" to emptyList(), "sadece gizli" to listOf(Facet.CONTEXT)), people = listOf("Biri"),
    )
    assertTrue(hidden.isPrivate)
    assertEquals(1, MemeStore.search(memes.snapshot(), "sadece").size)
    assertEquals(1, MemeStore.search(memes.snapshot(), "gizli").size)
    assertEquals(1, MemeStore.search(memes.snapshot(), "", MemeStore.Filter(onlyPrivate = true)).size)

    // The public file names none of it: not the private-only tag, the person, or the caption.
    val publicText = String(File(folder, "index.bin").readBytes(), Charsets.ISO_8859_1)
    assertFalse(publicText.contains("gizli"))
    val reopenedPublic = MemeStore(File(folder, "index.bin"), TestSealer()).snapshot()
    assertEquals(listOf("tepki"), reopenedPublic.tags.map { it.name })
    assertTrue(reopenedPublic.people.isEmpty())
    assertEquals(1, reopenedPublic.items.size)

    vault.unlocked = false
    val locked = memes.snapshot()
    assertTrue(MemeStore.search(locked, "gizli").isEmpty())
    assertEquals(1, locked.items.size)
    // Public work still goes on while locked, and does not disturb the private half.
    memes.tag("yeni")
    vault.unlocked = true
    assertEquals(2, memes.snapshot().items.size)
    assertEquals(1, MemeStore.search(memes.snapshot(), "sadece").size)
  }

  @Test
  fun makingAMemePrivateMovesItsOnlyLabelsWithIt() {
    val vault = FakeVault()
    val memes = MemeStore(File(folder, "index.bin"), TestSealer(), vault)
    val item = memes.add("content://media/1", "video", "aa", arda)
    memes.label(setOf(item.id), addTags = setOf(memes.tag("utanç").id))
    memes.movedToVault(item.id, "vault-9")
    val reopenedPublic = MemeStore(File(folder, "index.bin"), TestSealer()).snapshot()
    assertTrue(reopenedPublic.items.isEmpty())
    assertTrue(reopenedPublic.tags.isEmpty())
    val moved = memes.snapshot().items.single()
    assertEquals("vault-9", moved.vaultId)
    assertEquals(1, MemeStore.search(memes.snapshot(), "utanc").size)

    memes.movedOutOfVault(moved.id, "content://media/5")
    assertEquals(listOf("utanç"), MemeStore(File(folder, "index.bin"), TestSealer()).snapshot().tags.map { it.name })
  }

  @Test
  fun deletingTheVaultEntryDropsItsMeme() {
    val vault = FakeVault()
    val memes = MemeStore(File(folder, "index.bin"), TestSealer(), vault)
    memes.registerPrivate("vault-1", "video", "cc", null, tags = listOf("gizli" to emptyList()))
    val kept = memes.add("content://media/1", "video", "aa", null)
    memes.forgetVault("vault-1")
    assertEquals(listOf(kept.id), memes.snapshot().items.map { it.id })
    assertTrue(MemeStore.search(memes.snapshot(), "gizli").isEmpty())
  }

  @Test
  fun whetherAnyMemeIsPrivateIsKnownWhileLockedAndClearsWithTheLast() {
    val vault = FakeVault()
    val memes = MemeStore(File(folder, "index.bin"), TestSealer(), vault)
    val item = memes.add("content://media/1", "video", "aa", null)
    memes.tag("yalnız")
    assertFalse("public work leaves no private file behind", memes.hasPrivate())
    memes.movedToVault(item.id, "vault-1")
    vault.unlocked = false
    assertTrue(memes.hasPrivate())
    vault.unlocked = true
    memes.movedOutOfVault(item.id, "content://media/2")
    assertFalse(memes.hasPrivate())
  }

  @Test
  fun anEmptyPrivateIndexIsTidiedAway() {
    val vault = FakeVault()
    vault.stored = """{"version":1,"tags":[],"people":[],"items":[]}""".toByteArray()
    val memes = MemeStore(File(folder, "index.bin"), TestSealer(), vault)
    assertTrue(memes.hasPrivate())
    vault.unlocked = false
    memes.tidyPrivate()
    assertTrue("locked, it cannot be read, so it is left alone", memes.hasPrivate())
    vault.unlocked = true
    memes.tidyPrivate()
    assertFalse(memes.hasPrivate())
  }

  @Test
  fun aPrivateMemeCannotBeRelabelledWhileLocked() {
    val vault = FakeVault()
    val memes = MemeStore(File(folder, "index.bin"), TestSealer(), vault)
    val hidden = memes.registerPrivate("vault-1", "video", "cc", null)
    vault.unlocked = false
    try {
      memes.registerPrivate("vault-2", "video", "dd", null)
      fail("a private meme was added while locked")
    } catch (_: MemeStore.LockedException) {
    }
    vault.unlocked = true
    assertEquals(listOf(hidden.id), memes.snapshot().items.map { it.id })
  }

  @Test
  fun theMemeObjectRoundTripsAndToleratesStrangers() {
    val json = MemeStore.encodeMeme("video", arda, listOf("tepki" to listOf(Facet.REACTION, Facet.VIBE)), listOf("Arda Turan"))
    val decoded = MemeStore.decodeMeme(JSONObject(json.toString()))
    assertEquals("video", decoded.kind)
    assertEquals("bizim laubalilik seviyesi", decoded.source?.caption)
    assertEquals(listOf("tepki" to listOf(Facet.REACTION, Facet.VIBE)), decoded.tags)
    assertEquals(listOf("Arda Turan"), decoded.people)

    val strange = JSONObject("""{"tags":[{"name":"x","facets":["reaction","telepathy"]},{"name":"  "}],"people":[{}]}""")
    val lenient = MemeStore.decodeMeme(strange)
    assertEquals(listOf("x" to listOf(Facet.REACTION)), lenient.tags)
    assertTrue(lenient.people.isEmpty())
    assertEquals("video", lenient.kind)
  }

  @Test
  fun suggestionsComeFromTheCaptionThenTheAccount() {
    val memes = store()
    val older = memes.add("content://media/1", "video", "aa", MemeStore.Source(account = "futbolcaps", caption = "başka"))
    val fromAccount = memes.tag("futbol")
    memes.label(setOf(older.id), addTags = setOf(fromAccount.id))
    val captionTag = memes.tag("laubalılık")
    memes.tag("unrelated")
    val item = memes.add("content://media/2", "video", "bb", arda)
    val suggested = MemeStore.suggestions(item, memes.snapshot()).map { it.name }
    assertEquals(listOf(captionTag.name, fromAccount.name), suggested.take(2))
    assertFalse(suggested.contains("unrelated"))
  }

  @Test
  fun reconcileDropsMemesWhoseFilesAreGone() {
    val memes = store()
    memes.add("content://media/1", "video", "aa", null)
    val kept = memes.add("content://media/2", "video", "bb", null)
    assertEquals(1, memes.reconcile { it.id == kept.id })
    assertEquals(listOf(kept.id), memes.snapshot().items.map { it.id })
  }
}
