package expo.modules.localdownloader.vault

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * How long the vault stays open, and who is holding it that way.
 *
 * Before this, the vault key was cached for the life of the process and the biometric prompt
 * was a screen in front of it — a prompt that anything with bridge access could simply not
 * ask for. The key now lives here, behind a window that expires.
 *
 * Three rules, because one is not enough:
 *   - an absolute ceiling, so a session cannot be kept alive forever by poking the screen
 *   - an idle timeout, which is the case that actually happens: the phone is put down
 *   - a freshness requirement for destructive work, so deleting still asks even mid-session
 *
 * And leases, because a three-hour film, a whole-vault export and a re-encryption run all
 * outlive any sensible window. A lease pins the key for as long as the work holds it.
 *
 * The clock is injected so every one of those rules is testable without waiting. Deliberately
 * free of Android imports, so it runs under `scripts/run-kotlin-tests.sh`.
 */
class VaultSession(private val now: () -> Long = System::currentTimeMillis) {

  class VaultLocked(val operation: String) : Exception("PRIVATE_VAULT_LOCKED")
  class StepUpRequired(val operation: String) : Exception("PRIVATE_STEP_UP_REQUIRED")

  /** Held for the length of a long operation. Releasing it is the caller's responsibility. */
  class Lease internal constructor(val purpose: String, val id: String, val ceilingAt: Long)

  data class State(
    val unlocked: Boolean,
    val expiresAt: Long,
    val idleExpiresAt: Long,
    val lastAuthAt: Long,
    val leaseCount: Int,
  )

  private val lock = Any()
  private var dek: ByteArray? = null
  private var unlockedAt = 0L
  private var lastUseAt = 0L
  private var lastAuthAt = 0L
  private val leases = ConcurrentHashMap<String, Lease>()
  private val leaseCounter = AtomicLong(0)

  fun unlock(key: ByteArray, authAt: Long = now()) {
    synchronized(lock) {
      wipeLocked()
      // A copy, so the caller may wipe its own array without emptying the session's.
      dek = key.copyOf()
      unlockedAt = now()
      lastUseAt = unlockedAt
      lastAuthAt = authAt
    }
  }

  /** Records that the user authenticated again, which is what satisfies a step-up. */
  fun noteFreshAuth(at: Long = now()) {
    synchronized(lock) { lastAuthAt = at }
  }

  /**
   * The vault key, for one operation.
   *
   * Returns a copy every time. Handing out the session's own array and then wiping it on lock
   * would corrupt an in-flight stream mid-file — a silently truncated video, or a tag failure
   * with no cause. Callers wipe their copy when done.
   */
  fun requireDek(operation: String): ByteArray {
    synchronized(lock) {
      expireLocked()
      val key = dek ?: throw VaultLocked(operation)
      if (VaultAuthPolicy.needFor(operation) == VaultAuthPolicy.Need.UNLOCKED_FRESH &&
        now() - lastAuthAt > STEP_UP_FRESHNESS_MS
      ) {
        throw StepUpRequired(operation)
      }
      lastUseAt = now()
      return key.copyOf()
    }
  }

  /**
   * The key while the vault is open, without counting as use. For showing what is already on
   * screen, such as the private memes among the rest: browsing them must not hold the vault
   * open past its idle window.
   */
  fun peekDek(): ByteArray? = synchronized(lock) {
    expireLocked()
    dek?.copyOf()
  }

  fun beginLease(purpose: String, ceilingMs: Long = LEASE_CEILING_MS): Lease {
    synchronized(lock) {
      expireLocked()
      if (dek == null) throw VaultLocked(purpose)
      lastUseAt = now()
    }
    val lease = Lease(purpose, "lease-" + leaseCounter.incrementAndGet(), now() + ceilingMs)
    leases[lease.id] = lease
    return lease
  }

  fun endLease(lease: Lease?) {
    if (lease == null) return
    leases.remove(lease.id)
    synchronized(lock) { lastUseAt = now() }
  }

  /** Convenience so a caller cannot forget to release one. */
  inline fun <T> withLease(purpose: String, body: (Lease) -> T): T {
    val lease = beginLease(purpose)
    try {
      return body(lease)
    } finally {
      endLease(lease)
    }
  }

  /** Forgets the key. Refused while a lease is held, or a running export would break. */
  fun lock(reason: String): Boolean {
    synchronized(lock) {
      dropExpiredLeases()
      if (leases.isNotEmpty()) return false
      wipeLocked()
      return true
    }
  }

  /** Locks whether or not anything holds a lease. For shutdown. */
  fun forceLock() {
    synchronized(lock) {
      leases.clear()
      wipeLocked()
    }
  }

  /** Called on a timer, so the key does not sit in memory when nothing is asking for it. */
  fun sweep() {
    synchronized(lock) { expireLocked() }
  }

  fun snapshot(): State {
    synchronized(lock) {
      dropExpiredLeases()
      return State(
        unlocked = dek != null,
        expiresAt = if (dek == null) 0L else unlockedAt + ABSOLUTE_TTL_MS,
        idleExpiresAt = if (dek == null) 0L else lastUseAt + IDLE_TTL_MS,
        lastAuthAt = lastAuthAt,
        leaseCount = leases.size,
      )
    }
  }

  private fun dropExpiredLeases() {
    val cutoff = now()
    leases.entries.removeIf { it.value.ceilingAt <= cutoff }
  }

  private fun expireLocked() {
    if (dek == null) return
    dropExpiredLeases()
    // A lease holds the window open. A film longer than the timeout must not stall.
    if (leases.isNotEmpty()) return
    val moment = now()
    if (moment - unlockedAt >= ABSOLUTE_TTL_MS || moment - lastUseAt >= IDLE_TTL_MS) {
      wipeLocked()
    }
  }

  private fun wipeLocked() {
    dek?.fill(0)
    dek = null
    unlockedAt = 0L
    lastUseAt = 0L
  }

  companion object {
    /** A bounded window even if the screen is being poked. */
    const val ABSOLUTE_TTL_MS = 15L * 60L * 1000L

    /** The case that actually happens: the phone is put down. */
    const val IDLE_TTL_MS = 3L * 60L * 1000L

    /** How recent a prompt must be for a delete or an export. */
    const val STEP_UP_FRESHNESS_MS = 60L * 1000L

    /** A leaked lease cannot pin the key forever. */
    const val LEASE_CEILING_MS = 6L * 60L * 60L * 1000L
  }
}
