package expo.modules.localdownloader.vault

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The table itself.
 *
 * The gate this replaces lived in TypeScript, and the one screen that forgot to call it —
 * backup — could export the entire decrypted vault without a prompt. A table that a test can
 * read is the difference between that being a bug and it being a failing build.
 */
class VaultAuthPolicyTest {

  @Test
  fun everyOperationThatTouchesContentNeedsAnUnlock() {
    for (operation in VaultAuthPolicy.CONTENT_OPERATIONS) {
      assertTrue(
        "$operation may run on a locked vault",
        VaultAuthPolicy.needFor(operation) != VaultAuthPolicy.Need.NONE
      )
    }
  }

  @Test
  fun anythingLeavingTheVaultNeedsARecentPrompt() {
    // Deleting, and the three ways content gets out. A borrowed unlocked phone is the case.
    for (operation in listOf(
      VaultAuthPolicy.OP_DELETE,
      VaultAuthPolicy.OP_EXPORT_TO_GALLERY,
      VaultAuthPolicy.OP_MAKE_PUBLIC,
      VaultAuthPolicy.OP_BACKUP_EXPORT,
    )) {
      assertEquals(
        "$operation should require a fresh prompt",
        VaultAuthPolicy.Need.UNLOCKED_FRESH, VaultAuthPolicy.needFor(operation)
      )
    }
  }

  @Test
  fun readingTheLockStateDoesNotRequireBeingUnlocked() {
    // Otherwise the diagnostics screen and the lock indicator break the moment it locks.
    assertEquals(VaultAuthPolicy.Need.NONE, VaultAuthPolicy.needFor(VaultAuthPolicy.OP_LOCK_STATE))
    assertEquals(VaultAuthPolicy.Need.NONE, VaultAuthPolicy.needFor(VaultAuthPolicy.OP_DIAGNOSTICS))
  }

  @Test
  fun anOperationNobodyAddedToTheTableFailsClosed() {
    assertEquals(
      "an unlisted operation must not default to running unguarded",
      VaultAuthPolicy.Need.UNLOCKED, VaultAuthPolicy.needFor("somethingAddedLater")
    )
  }

  @Test
  fun listingAndPlayingDoNotRePromptConstantly() {
    // The other half of the trade: prompting on every list would make the vault unusable and
    // train the user to authenticate reflexively.
    assertEquals(VaultAuthPolicy.Need.UNLOCKED, VaultAuthPolicy.needFor(VaultAuthPolicy.OP_LIST))
    assertEquals(VaultAuthPolicy.Need.UNLOCKED, VaultAuthPolicy.needFor(VaultAuthPolicy.OP_PLAY))
  }
}
