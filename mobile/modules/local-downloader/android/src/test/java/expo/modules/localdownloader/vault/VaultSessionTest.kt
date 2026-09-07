package expo.modules.localdownloader.vault

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * How long the vault stays open.
 *
 * Every rule here is a wall-clock rule, so the clock is injected and nothing waits. The one
 * that is easiest to get wrong and worst to get wrong is the defensive copy: hand out the
 * session's own array and a lock mid-playback truncates a video with no error anywhere.
 */
class VaultSessionTest {

  private var clock = 1_000_000L
  private fun session() = VaultSession { clock }
  private fun key() = ByteArray(32) { (it + 1).toByte() }

  @Test
  fun anUnlockedSessionHandsOutTheKey() {
    val session = session()
    session.unlock(key())
    assertArrayEquals(key(), session.requireDek(VaultAuthPolicy.OP_LIST))
  }

  @Test
  fun aLockedSessionRefuses() {
    try {
      session().requireDek(VaultAuthPolicy.OP_LIST)
      fail("a locked session handed out a key")
    } catch (expected: VaultSession.VaultLocked) {
      assertEquals(VaultAuthPolicy.OP_LIST, expected.operation)
    }
  }

  @Test
  fun theCallerGetsACopy() {
    val session = session()
    session.unlock(key())
    val first = session.requireDek(VaultAuthPolicy.OP_LIST)
    first.fill(0)
    // If this returned the session's own array, the caller wiping its copy above would have
    // emptied the vault key for everything still streaming.
    assertArrayEquals(key(), session.requireDek(VaultAuthPolicy.OP_LIST))
  }

  @Test
  fun unlockingCopiesTheCallersArray() {
    val session = session()
    val supplied = key()
    session.unlock(supplied)
    supplied.fill(0)
    assertArrayEquals(key(), session.requireDek(VaultAuthPolicy.OP_LIST))
  }

  @Test
  fun theAbsoluteWindowExpiresEvenWhileInUse() {
    val session = session()
    session.unlock(key())
    // Kept busy the whole time, so only the absolute ceiling can end it.
    var elapsed = 0L
    while (elapsed + 60_000 < VaultSession.ABSOLUTE_TTL_MS) {
      clock += 60_000
      elapsed += 60_000
      session.requireDek(VaultAuthPolicy.OP_LIST)
    }
    clock += 60_000
    try {
      session.requireDek(VaultAuthPolicy.OP_LIST)
      fail("the absolute window did not end")
    } catch (expected: VaultSession.VaultLocked) {
    }
  }

  @Test
  fun theIdleWindowExpires() {
    val session = session()
    session.unlock(key())
    clock += VaultSession.IDLE_TTL_MS + 1
    try {
      session.requireDek(VaultAuthPolicy.OP_LIST)
      fail("an idle session stayed open")
    } catch (expected: VaultSession.VaultLocked) {
    }
  }

  @Test
  fun useKeepsTheIdleWindowOpen() {
    val session = session()
    session.unlock(key())
    repeat(5) {
      clock += VaultSession.IDLE_TTL_MS - 1000
      session.requireDek(VaultAuthPolicy.OP_LIST)
    }
  }

  @Test
  fun aLeaseHoldsThroughBothWindows() {
    val session = session()
    session.unlock(key())
    val lease = session.beginLease("playback")
    // A film longer than either timeout must not stall part way through.
    clock += VaultSession.ABSOLUTE_TTL_MS + VaultSession.IDLE_TTL_MS + 60_000
    session.requireDek(VaultAuthPolicy.OP_PLAY)
    session.endLease(lease)
  }

  @Test
  fun theWindowClosesOnceTheLeaseIsReleased() {
    val session = session()
    session.unlock(key())
    val lease = session.beginLease("playback")
    clock += VaultSession.ABSOLUTE_TTL_MS + 60_000
    session.endLease(lease)
    try {
      session.requireDek(VaultAuthPolicy.OP_LIST)
      fail("releasing the lease left the window open")
    } catch (expected: VaultSession.VaultLocked) {
    }
  }

  @Test
  fun aLeakedLeaseDoesNotPinTheKeyForever() {
    val session = session()
    session.unlock(key())
    session.beginLease("playback")
    clock += VaultSession.LEASE_CEILING_MS + 1000
    try {
      session.requireDek(VaultAuthPolicy.OP_LIST)
      fail("a lease nobody released kept the vault open")
    } catch (expected: VaultSession.VaultLocked) {
    }
  }

  @Test
  fun destructiveWorkNeedsARecentPrompt() {
    val session = session()
    session.unlock(key())
    session.requireDek(VaultAuthPolicy.OP_DELETE)  // fresh right after unlocking

    clock += VaultSession.STEP_UP_FRESHNESS_MS + 1
    session.requireDek(VaultAuthPolicy.OP_LIST)    // still fine for ordinary work
    try {
      session.requireDek(VaultAuthPolicy.OP_DELETE)
      fail("a delete went through on a stale prompt")
    } catch (expected: VaultSession.StepUpRequired) {
      assertEquals(VaultAuthPolicy.OP_DELETE, expected.operation)
    }

    session.noteFreshAuth()
    session.requireDek(VaultAuthPolicy.OP_DELETE)
  }

  @Test
  fun exportingTheWholeVaultCountsAsDestructiveWork() {
    val session = session()
    session.unlock(key())
    clock += VaultSession.STEP_UP_FRESHNESS_MS + 1
    try {
      session.requireDek(VaultAuthPolicy.OP_BACKUP_EXPORT)
      fail("a whole-vault export went through on a stale prompt")
    } catch (expected: VaultSession.StepUpRequired) {
    }
  }

  @Test
  fun lockingIsRefusedWhileWorkIsHoldingTheKey() {
    val session = session()
    session.unlock(key())
    val lease = session.beginLease("backupExport")
    assertFalse("locking interrupted a running export", session.lock("background"))
    session.requireDek(VaultAuthPolicy.OP_LIST)
    session.endLease(lease)
    assertTrue(session.lock("background"))
  }

  @Test
  fun forceLockAlwaysWins() {
    val session = session()
    session.unlock(key())
    session.beginLease("playback")
    session.forceLock()
    assertFalse(session.snapshot().unlocked)
  }

  @Test
  fun theSnapshotReportsStateAndNeverContent() {
    val session = session()
    assertFalse(session.snapshot().unlocked)
    session.unlock(key())
    val state = session.snapshot()
    assertTrue(state.unlocked)
    assertEquals(clock + VaultSession.ABSOLUTE_TTL_MS, state.expiresAt)
    assertEquals(0, state.leaseCount)
    val lease = session.beginLease("playback")
    assertEquals(1, session.snapshot().leaseCount)
    session.endLease(lease)
  }

  @Test
  fun sweepingClosesAnIdleSession() {
    val session = session()
    session.unlock(key())
    clock += VaultSession.IDLE_TTL_MS + 1
    session.sweep()
    assertFalse("the key sat in memory with nothing asking for it", session.snapshot().unlocked)
  }

  @Test
  fun aLeaseCannotBeTakenOnALockedVault() {
    try {
      session().beginLease("playback")
      fail("a lease was taken on a locked vault")
    } catch (expected: VaultSession.VaultLocked) {
    }
  }

  @Test
  fun withLeaseReleasesEvenWhenTheBodyThrows() {
    val session = session()
    session.unlock(key())
    try {
      session.withLease("playback") { throw IllegalStateException("boom") }
    } catch (expected: IllegalStateException) {
    }
    assertEquals(0, session.snapshot().leaseCount)
  }
}
