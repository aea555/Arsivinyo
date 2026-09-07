package expo.modules.localdownloader.vault

import expo.modules.localdownloader.backup.BackupCrypto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The move to a vault key that requires your fingerprint, and every way it can go wrong.
 *
 * This is the step that can destroy a vault, so the interesting cases are all failures: the
 * new key refuses at use, the write does not read back, the process dies between steps, the
 * key is invalidated afterwards. None of them may leave the vault unopenable.
 */
class VaultKeyBoxTest {

  /** Files in a map. */
  private class FakeStore : VaultKeyBox.Store {
    val files = mutableMapOf<String, ByteArray>()
    override fun read(name: String) = files[name]
    override fun write(name: String, bytes: ByteArray) { files[name] = bytes }
    override fun delete(name: String) { files.remove(name) }
    override fun exists(name: String) = files.containsKey(name)
  }

  /**
   * A key store that wraps by XOR against a per-slot pad. Not cryptography — the point is
   * only that a slot's blob is unreadable by another slot, and that failures can be induced.
   */
  private class FakeKeys : VaultKeyBox.MasterKeyProvider {
    val present = mutableSetOf(VaultKeyBox.SLOT_KEYSTORE_V2)
    var refuseToCreate = false
    var failAtUse: String? = null
    var invalidated: String? = null
    var corruptWrapFor: String? = null

    private fun pad(slot: String) = ByteArray(32) { (slot.hashCode() + it).toByte() }

    override fun ensureSlot(slot: String): Boolean {
      if (refuseToCreate) return false
      present.add(slot)
      return true
    }
    override fun hasSlot(slot: String) = present.contains(slot)
    override fun dropSlot(slot: String) { present.remove(slot) }

    override fun wrap(plaintext: ByteArray, slot: String): ByteArray {
      if (invalidated == slot) throw VaultKeyBox.MasterKeyError.Invalidated
      if (failAtUse == slot) throw VaultKeyBox.MasterKeyError.Backend(IllegalStateException("no"))
      if (!present.contains(slot)) throw VaultKeyBox.MasterKeyError.Invalidated
      val p = pad(slot)
      val out = ByteArray(plaintext.size) { (plaintext[it].toInt() xor p[it % 32].toInt()).toByte() }
      // Induce "wrote fine, reads back wrong", which is what the self-test is for.
      if (corruptWrapFor == slot) out[0] = (out[0].toInt() xor 0x5a).toByte()
      return out
    }

    override fun unwrap(blob: ByteArray, slot: String): ByteArray {
      if (invalidated == slot) throw VaultKeyBox.MasterKeyError.Invalidated
      if (failAtUse == slot) throw VaultKeyBox.MasterKeyError.Backend(IllegalStateException("no"))
      if (!present.contains(slot)) throw VaultKeyBox.MasterKeyError.Invalidated
      val p = pad(slot)
      return ByteArray(blob.size) { (blob[it].toInt() xor p[it % 32].toInt()).toByte() }
    }
  }

  private val dek = ByteArray(32) { (it * 3 + 7).toByte() }
  // Cheap on purpose: this suite derives a passphrase key several times.
  private val fastKdf = BackupCrypto.KdfParams(memoryKiB = 8192, iterations = 1, parallelism = 4)

  private fun fixture(): Triple<FakeStore, FakeKeys, VaultKeyBox> {
    val store = FakeStore()
    val keys = FakeKeys()
    store.write(VaultKeyBox.DEK_V4_FILE, keys.wrap(dek, VaultKeyBox.SLOT_KEYSTORE_V2))
    return Triple(store, keys, VaultKeyBox(store, keys, fastKdf))
  }

  @Test
  fun aFreshVaultOpensOnTheOldKey() {
    val (_, _, box) = fixture()
    assertEquals(VaultKeyBox.SLOT_KEYSTORE_V2, box.activeSlot())
    assertArrayEquals(dek, box.dek())
  }

  @Test
  fun theMigrationMovesTheKeyAndNotTheContent() {
    val (store, _, box) = fixture()
    val before = store.files[VaultKeyBox.DEK_V4_FILE]!!.copyOf()

    assertEquals(VaultKeyBox.Outcome.MIGRATED, box.migrateToV3().outcome)
    assertEquals(VaultKeyBox.SLOT_KEYSTORE_V3, box.activeSlot())
    assertArrayEquals("the vault key itself must not change", dek, box.dek())

    // The old wrap is still there on purpose: it is the way back until the new one has proved
    // itself and there is a recovery passphrase.
    assertArrayEquals(before, store.files[VaultKeyBox.DEK_V4_FILE])
    assertNotNull(store.files[VaultKeyBox.DEK_V5_FILE])
    assertFalse(box.isFullyMigrated())
  }

  @Test
  fun migratingTwiceIsHarmless() {
    val (_, _, box) = fixture()
    box.migrateToV3()
    assertEquals(VaultKeyBox.Outcome.ALREADY_V3, box.migrateToV3().outcome)
    assertArrayEquals(dek, box.dek())
  }

  @Test
  fun aKeyStoreThatWillNotMakeTheKeyChangesNothing() {
    val (store, keys, box) = fixture()
    keys.refuseToCreate = true
    assertEquals(VaultKeyBox.Outcome.FAILED, box.migrateToV3().outcome)
    assertEquals(VaultKeyBox.SLOT_KEYSTORE_V2, box.activeSlot())
    assertNull(store.files[VaultKeyBox.DEK_V5_FILE])
    assertArrayEquals(dek, box.dek())
  }

  @Test
  fun aKeyThatIsAcceptedAtCreationAndRefusedAtUseIsCaughtBeforeAnythingIsWritten() {
    // The manufacturer-specific case: the specification is accepted, the key is unusable.
    val (store, keys, box) = fixture()
    keys.failAtUse = VaultKeyBox.SLOT_KEYSTORE_V3
    assertEquals(VaultKeyBox.Outcome.FAILED, box.migrateToV3().outcome)
    assertNull("nothing may be written before the new key has proved itself",
      store.files[VaultKeyBox.DEK_V5_FILE])
    assertEquals(VaultKeyBox.SLOT_KEYSTORE_V2, box.activeSlot())
    assertArrayEquals(dek, box.dek())
  }

  @Test
  fun aWrapThatDoesNotReadBackIsUndone() {
    val (store, keys, box) = fixture()
    keys.corruptWrapFor = VaultKeyBox.SLOT_KEYSTORE_V3
    assertEquals(VaultKeyBox.Outcome.FAILED, box.migrateToV3().outcome)
    assertNull(store.files[VaultKeyBox.DEK_V5_FILE])
    assertEquals(VaultKeyBox.SLOT_KEYSTORE_V2, box.activeSlot())
    assertArrayEquals(dek, box.dek())
  }

  @Test
  fun dyingBeforeTheCommitLeavesTheOldKeyInCharge() {
    // Everything the migration does before writing the marker, and then nothing.
    val (store, keys, _) = fixture()
    keys.ensureSlot(VaultKeyBox.SLOT_KEYSTORE_V3)
    store.write(VaultKeyBox.DEK_V5_FILE, keys.wrap(dek, VaultKeyBox.SLOT_KEYSTORE_V3))

    val reopened = VaultKeyBox(store, keys, fastKdf)
    assertEquals(VaultKeyBox.SLOT_KEYSTORE_V2, reopened.activeSlot())
    assertArrayEquals(dek, reopened.dek())
    // And it can simply be run again.
    assertEquals(VaultKeyBox.Outcome.MIGRATED, reopened.migrateToV3().outcome)
    assertArrayEquals(dek, reopened.dek())
  }

  @Test
  fun theOldKeyIsNotDestroyedWithoutAWayBackIn() {
    val (store, _, box) = fixture()
    box.migrateToV3()
    val result = box.finalise()
    assertEquals(VaultKeyBox.Outcome.FAILED, result.outcome)
    assertEquals("PRIVATE_RECOVERY_SLOT_MISSING", result.detail)
    assertNotNull("the way back must still exist", store.files[VaultKeyBox.DEK_V4_FILE])
  }

  @Test
  fun theOldKeyGoesOnceThereIsARecoveryPassphrase() {
    val (store, keys, box) = fixture()
    box.migrateToV3()
    box.addRecoverySlot("a correct horse battery staple".toCharArray())
    assertEquals(VaultKeyBox.Outcome.MIGRATED, box.finalise().outcome)
    assertNull(store.files[VaultKeyBox.DEK_V4_FILE])
    assertFalse(keys.hasSlot(VaultKeyBox.SLOT_KEYSTORE_V2))
    assertTrue(box.isFullyMigrated())
    assertArrayEquals(dek, box.dek())
  }

  @Test
  fun anInvalidatedNewKeyFallsBackToTheOldOne() {
    val (store, keys, box) = fixture()
    box.migrateToV3()
    // A new fingerprint, or the screen lock removed and set again.
    keys.invalidated = VaultKeyBox.SLOT_KEYSTORE_V3

    val result = box.recoverFromInvalidatedKey()
    assertEquals(VaultKeyBox.Outcome.ROLLED_BACK, result.outcome)
    assertEquals(VaultKeyBox.SLOT_KEYSTORE_V2, box.activeSlot())
    assertArrayEquals("the vault must still open", dek, box.dek())
    assertNull(store.files[VaultKeyBox.DEK_V5_FILE])
  }

  @Test
  fun anInvalidatedKeyAfterTheOldOneIsGoneAsksForTheRecoveryPassphrase() {
    val (_, keys, box) = fixture()
    box.migrateToV3()
    box.addRecoverySlot("a correct horse battery staple".toCharArray())
    box.finalise()
    keys.invalidated = VaultKeyBox.SLOT_KEYSTORE_V3
    keys.dropSlot(VaultKeyBox.SLOT_KEYSTORE_V3)

    val result = box.recoverFromInvalidatedKey()
    assertEquals(VaultKeyBox.Outcome.FAILED, result.outcome)
    assertEquals("PRIVATE_RECOVERY_REQUIRED", result.detail)
    assertArrayEquals(
      "the recovery passphrase is the whole point of it existing",
      dek, box.unlockWithRecovery("a correct horse battery staple".toCharArray())
    )
  }

  @Test
  fun withNoWayBackAtAllTheStateSaysSoPlainly() {
    val (_, keys, box) = fixture()
    box.migrateToV3()
    box.addRecoverySlot("a correct horse battery staple".toCharArray())
    box.finalise(force = true)
    // Force past the recovery requirement, then take that away too.
    val (store2, keys2, box2) = fixture()
    box2.migrateToV3()
    box2.finalise(force = true)
    keys2.invalidated = VaultKeyBox.SLOT_KEYSTORE_V3
    keys2.dropSlot(VaultKeyBox.SLOT_KEYSTORE_V3)
    assertEquals(VaultKeyBox.Outcome.UNRECOVERABLE, box2.recoverFromInvalidatedKey().outcome)
  }

  @Test
  fun theRecoveryPassphraseOpensTheVault() {
    val (_, _, box) = fixture()
    box.addRecoverySlot("a correct horse battery staple".toCharArray())
    assertTrue(box.hasRecoverySlot())
    assertArrayEquals(dek, box.unlockWithRecovery("a correct horse battery staple".toCharArray()))
  }

  @Test
  fun aWrongRecoveryPassphraseIsReportedAsWrong() {
    val (_, _, box) = fixture()
    box.addRecoverySlot("a correct horse battery staple".toCharArray())
    try {
      box.unlockWithRecovery("a correct horse battery stapler".toCharArray())
      fail("a wrong recovery passphrase opened the vault")
    } catch (expected: IllegalStateException) {
      assertEquals("PRIVATE_RECOVERY_WRONG_SECRET", expected.message)
    }
  }

  @Test
  fun aRecoverySlotSurvivesTheMigration() {
    val (_, _, box) = fixture()
    box.addRecoverySlot("a correct horse battery staple".toCharArray())
    box.migrateToV3()
    assertArrayEquals(
      "adding a device key must not disturb the passphrase that opens it",
      dek, box.unlockWithRecovery("a correct horse battery staple".toCharArray())
    )
  }

  @Test
  fun aDamagedKeyBoxIsRefusedRatherThanTreatedAsEmpty() {
    val (store, keys, _) = fixture()
    store.write(VaultKeyBox.STATE_FILE, "{not json".toByteArray())
    try {
      VaultKeyBox(store, keys, fastKdf).activeSlot()
      fail("a damaged key box was read as if it were a fresh one")
    } catch (expected: IllegalStateException) {
      assertEquals("PRIVATE_KEYBOX_UNREADABLE", expected.message)
    }
  }
}
