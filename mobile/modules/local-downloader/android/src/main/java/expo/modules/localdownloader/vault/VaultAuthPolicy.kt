package expo.modules.localdownloader.vault

/**
 * What each vault operation requires before it may run.
 *
 * This is a table rather than a scattering of checks so that it can be tested directly, and
 * so that a new bridge function touching the vault fails a test instead of shipping ungated.
 * The gate used to live entirely in TypeScript: `authenticatePrivateAccessInternal` was called
 * only by the screen, and every native vault operation ran unconditionally over the bridge.
 * The backup screen never called it at all, so exporting the whole decrypted vault asked for
 * nothing.
 *
 * Deliberately free of Android imports, so it runs under `scripts/run-kotlin-tests.sh`.
 */
object VaultAuthPolicy {

  enum class Need {
    /** No unlock. Only for things that reveal nothing: counts, availability, lock state. */
    NONE,

    /** A live session. The common case: listing, playing, tagging. */
    UNLOCKED,

    /**
     * A live session *and* a recent prompt. For anything destructive or anything that moves
     * content out of the vault, which is where a borrowed unlocked phone does real damage.
     */
    UNLOCKED_FRESH,
  }

  /** Operation names, used both here and as the argument to the session's requireDek. */
  const val OP_LIST = "list"
  const val OP_THUMBNAIL = "thumbnail"
  const val OP_PLAY = "play"
  const val OP_RENAME = "rename"
  const val OP_TAG = "tag"
  const val OP_FOLDER = "folder"
  const val OP_IMPORT = "import"
  const val OP_INGEST = "ingest"
  const val OP_DELETE = "delete"
  const val OP_EXPORT_TO_GALLERY = "exportToGallery"
  const val OP_MAKE_PUBLIC = "makePublic"
  const val OP_MIGRATE = "migrate"
  const val OP_BACKUP_EXPORT = "backupExport"
  const val OP_BACKUP_RESTORE = "backupRestore"
  const val OP_DIAGNOSTICS = "diagnostics"
  const val OP_LOCK_STATE = "lockState"

  val POLICY: Map<String, Need> = mapOf(
    OP_LIST to Need.UNLOCKED,
    OP_THUMBNAIL to Need.UNLOCKED,
    OP_PLAY to Need.UNLOCKED,
    OP_RENAME to Need.UNLOCKED,
    OP_TAG to Need.UNLOCKED,
    OP_FOLDER to Need.UNLOCKED,
    OP_IMPORT to Need.UNLOCKED,
    // A download that finishes hours later still writes into the vault. It takes its
    // permission when the user asks for it, not when the bytes land.
    OP_INGEST to Need.UNLOCKED,

    OP_DELETE to Need.UNLOCKED_FRESH,
    OP_EXPORT_TO_GALLERY to Need.UNLOCKED_FRESH,
    OP_MAKE_PUBLIC to Need.UNLOCKED_FRESH,
    OP_MIGRATE to Need.UNLOCKED_FRESH,
    // The widest read of private content the app can perform: every video, decrypted, into
    // one file the user then carries somewhere else.
    OP_BACKUP_EXPORT to Need.UNLOCKED_FRESH,
    OP_BACKUP_RESTORE to Need.UNLOCKED,

    // Counts and state only. These must answer while locked, or the diagnostics screen and
    // the lock indicator break the moment the vault locks.
    OP_DIAGNOSTICS to Need.NONE,
    OP_LOCK_STATE to Need.NONE,
  )

  /** Anything not in the table is treated as needing an unlock, so an omission fails closed. */
  fun needFor(operation: String): Need = POLICY[operation] ?: Need.UNLOCKED

  /** Every operation that touches vault content, for the test that guards the table. */
  val CONTENT_OPERATIONS: Set<String> = POLICY.keys - setOf(OP_DIAGNOSTICS, OP_LOCK_STATE)
}
