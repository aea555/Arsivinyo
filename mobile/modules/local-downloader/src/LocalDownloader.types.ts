export type LocalPlatform = 'youtube' | 'instagram' | 'facebook' | 'twitter' | 'reddit' | 'tiktok';

export interface LocalDownloadStartInput {
  url: string;
  cookiePlatform?: LocalPlatform;
  cookieProfile?: string;
  // 0 or undefined means unlimited file size.
  maxFileSizeMb?: number;
  visibility?: 'public' | 'private';
  // 'audio' downloads best-audio as M4A/AAC into the public music library (never the vault).
  mediaKind?: 'video' | 'audio';
}

/** A track in the on-device music library (public Music/Arsivinyo folder). */
export interface LocalSound {
  id: string;
  title: string;
  artist?: string | null;
  fileName: string;
  contentUri: string;
  durationSec: number;
  sizeBytes: number;
  thumbnailPath?: string | null;
  /**
   * Container the track is stored in, lowercase and without the dot (e.g. `flac`,
   * `m4a`). Derived natively from the file name, so it always reflects what is
   * actually on disk rather than what was requested at download time.
   */
  format?: string | null;
  /** True when [format] stores the audio without further loss (FLAC, ALAC, WAV...). */
  lossless: boolean;
  /**
   * Id of the preset this track was rendered with, or null for an original.
   * Together with [sourceSongId] this is what marks a track as a render.
   */
  presetId?: string | null;
  /**
   * Id of the track this one was rendered from, or null for an original. The source is
   * never modified by a render, so it remains available to re-render from.
   */
  sourceSongId?: string | null;
  createdAt: number;
  updatedAt: number;
}

/** Progress of a preset render batch. A single track is a batch of one. */
export interface LocalSoundPresetProgressEvent {
  renderId: string;
  /**
   * `PROGRESS` while a track renders, `TRACK_DONE` / `TRACK_FAILED` per track, then
   * `FINISHED` or `CANCELLED` once for the batch.
   */
  status: 'PROGRESS' | 'TRACK_DONE' | 'TRACK_FAILED' | 'FINISHED' | 'CANCELLED';
  songId?: string | null;
  /** Zero-based position of the track within the batch. */
  index: number;
  total: number;
  /** Percentage through the current track, not the batch. Null when unknown. */
  percent?: number | null;
  message?: string | null;
  /** The newly created library entry, present on `TRACK_DONE`. */
  song?: LocalSound | null;
}

export interface LocalSoundPresetStartResult {
  renderId: string;
  total: number;
}

export interface LocalAudioPresetDiagnostics {
  nativeAvailable: boolean;
  nativeVersion?: string | null;
  ffmpegPath?: string | null;
  ffprobePath?: string | null;
}

/** Container the downloader encodes audio into. FLAC is lossless and the default. */
export type LocalAudioFormat = 'flac' | 'm4a';

export interface LocalAudioFormatState {
  format: LocalAudioFormat;
  lossless: boolean;
}

/** A user-defined playlist. Many-to-many: a song may appear in several playlists. */
export interface LocalSoundPlaylist {
  id: string;
  name: string;
  songIds: string[];
  /** True for the special, non-deletable Favorites playlist (id "favorites"). */
  system?: boolean;
  createdAt: number;
  updatedAt: number;
}

export interface LocalSoundsLibrary {
  songs: LocalSound[];
  playlists: LocalSoundPlaylist[];
}

export interface LocalSoundsImportResult {
  success: boolean;
  importedCount?: number;
  failedCount?: number;
  songs?: LocalSound[];
  code?: string;
  message?: string;
  /** Per-file failure reasons (debug aid), present when failedCount > 0. */
  failures?: string[];
}

export interface LocalDownloadStartResult {
  taskId: string;
  estimatedSizeMb?: number | null;
}

export interface LocalBackgroundState {
  stickyNotificationEnabled?: boolean;
  serviceRunning: boolean;
  /** Every download running right now. Several run concurrently. */
  activeTaskIds: string[];
  /** Downloads that have started but are still waiting at a scheduler gate. */
  queueSize: number;
  /**
   * Always empty. Waiting downloads are real tasks now, reachable through
   * `activeTaskIds`, rather than urls held in a list outside the scheduler.
   *
   * @deprecated
   */
  queuedUrls: string[];
  lastQuickReason?: string | null;
  notificationPhase?: string;
  privateModeEnabled?: boolean;
  audioModeEnabled?: boolean;
  notificationPermissionRequired: boolean;
  notificationPermissionGranted: boolean;
}

export interface LocalBackgroundPermissionResult {
  granted: boolean;
  canAskAgain: boolean;
}

export interface LocalStickyNotificationState {
  enabled: boolean;
}

export interface LocalQuickDownloadResult {
  accepted: boolean;
  reason?: 'NO_CLIPBOARD_URL' | 'INVALID_QUICK_URL' | 'QUEUE_FULL' | 'PERMISSION_REQUIRED' | 'ALREADY_ACTIVE' | 'QUICK_DOWNLOAD_REJECTED' | 'QUICK_CAPTURE_CANCELLED' | 'PRIVATE_MODE_UNAVAILABLE';
  taskId?: string;
  queueSize?: number;
  queueMax?: number;
  resolvedUrl?: string | null;
  visibility?: 'public' | 'private';
  captureMode?: 'clipboard' | 'manual';
}

export interface LocalDownloadFailureLog {
  id: string;
  createdAt: number;
  taskId?: string | null;
  code?: string | null;
  url?: string | null;
  normalizedUrl?: string | null;
  preflightWarning?: Record<string, unknown> | null;
  preflightStrategy?: string | null;
  downloadStrategy?: string | null;
  extractorKey?: string | null;
  formatSelector?: string | null;
  attemptTrace?: Array<Record<string, unknown>> | null;
  toolOutput?: string | null;
  preflightBudgetSec?: number | null;
  preflightElapsedMs?: number | null;
  preflightAttemptLimit?: number | null;
  staticMediaCandidateCount?: number | null;
  message: string;
}

export interface LocalSaveToMediaStoreInput {
  filePath: string;
  filename: string;
  mimeType?: string;
  dateTakenMs?: number;
}

export interface LocalSaveToMediaStoreResult {
  uri: string;
  assetId?: string;
}

export type LocalTaskStatus = 'PENDING' | 'STARTED' | 'PROGRESS' | 'SUCCESS' | 'FAILURE' | 'CANCELLED';

export interface LocalTaskStatusResult {
  taskId: string;
  status: LocalTaskStatus;
  url?: string | null;
  state?: 'starting' | 'downloading' | 'processing' | 'saving' | 'completed' | 'error';
  filename?: string;
  filePath?: string;
  isPrivate?: boolean;
  privateVideoId?: string;
  sizeMb?: number;
  progressPercent?: number;
  speedBytesPerSec?: number;
  errorCode?: string;
  errorMessage?: string;
  estimatedSizeMb?: number | null;
  timestampNormalized?: boolean;
  warningCode?: string;
}

export interface LocalCookieProfile {
  profileName: string;
  path: string;
  lastModified: number;
}

export interface LocalCustomCookieImportInput {
  uri: string;
  profileName?: string;
  domain?: string | null;
}

export interface LocalCustomCookieImportResult {
  profileId: string;
  profileName: string;
  detectedDomains: string[];
  boundDomains: string[];
}

export interface LocalCustomDomainSummary {
  domain: string;
  profileCount: number;
  defaultProfileName: string | null;
}

export interface LocalCustomDomainProfile {
  profileName: string;
  profileId: string;
  lastModified: number;
}

export interface LocalDownloadEvent {
  taskId: string;
  status: LocalTaskStatus;
  state: 'starting' | 'downloading' | 'processing' | 'saving' | 'completed' | 'error';
  message?: string;
  progressPercent?: number;
  speedBytesPerSec?: number;
}

export interface LocalBackgroundStateEvent extends LocalBackgroundState {}

export type LocalYtDlpUpdatePhase =
  | 'checking'
  | 'available'
  | 'up_to_date'
  | 'downloading'
  | 'installing'
  | 'verifying'
  | 'installed'
  | 'failed';

export interface LocalYtDlpUpdateProgressEvent {
  phase: LocalYtDlpUpdatePhase;
  version?: string | null;
  bytesDownloaded?: number | null;
  bytesTotal?: number | null;
  percent?: number | null;
  message?: string | null;
}

export interface LocalYtDlpUpdateStatus {
  source: 'bundled' | 'override' | string;
  bundledVersion?: string | null;
  activeVersion?: string | null;
  overrideVersion?: string | null;
  pendingVersion?: string | null;
  failedVersion?: string | null;
  failedReason?: string | null;
  effectiveInstalledVersion?: string | null;
  installedVersions?: string[];
  latestVersion?: string | null;
  latestCheckError?: string | null;
  updateAvailable?: boolean;
  requiresRestart?: boolean;
  updateRunning?: boolean;
  storageReady?: boolean;
  overridePath?: string | null;
  /** Downloads running right now; an update is refused while any exist. */
  activeTaskIds?: string[];
}

export interface LocalYtDlpUpdateCheckResult extends LocalYtDlpUpdateStatus {
  status: 'available' | 'up_to_date' | string;
}

export interface LocalYtDlpUpdateResult {
  status: 'up_to_date' | 'installed' | 'failed' | 'blocked' | 'running' | string;
  success: boolean;
  previousVersion?: string | null;
  installedVersion?: string | null;
  latestVersion?: string | null;
  pendingVersion?: string | null;
  requiresRestart: boolean;
  code?: string;
  message?: string;
}

export interface LocalPrivateModeState {
  enabled: boolean;
}

export type LocalPrivateAuthPurpose =
  | 'view'
  | 'delete'
  | 'unprivate'
  | 'import'
  | 'export'
  | 'rename'
  | 'migrate'
  | 'tag'
  | 'folder'
  | 'bundleExport'
  | 'bundleImport';

export interface LocalPrivateAuthResult {
  granted: boolean;
  reason?: string;
}

/**
 * How long the vault will stay open.
 *
 * Counts and timestamps only — never an entry id, a title, or anything about the loopback
 * server's URL, which its own documentation says to treat as a secret.
 */
export interface LocalPrivateVaultLockState {
  unlocked: boolean;
  /** Milliseconds since the epoch. Zero when locked. */
  expiresAt: number;
  idleExpiresAt: number;
  lastAuthAt: number;
  /** Long operations holding the vault open: a playing video, an export, a migration. */
  leaseCount: number;
}

export type LocalPrivateUnlockResult = LocalPrivateAuthResult & LocalPrivateVaultLockState;

/** Which key opens the vault, and whether there is a way in that is not this device. */
export interface LocalVaultKeyState {
  /** 'keystore-v2' is the original key; 'keystore-v3' requires a recent unlock. */
  activeSlot: string | null;
  authBound: boolean;
  hasRecoverySlot: boolean;
  /** True once the original, unbound key has been destroyed. */
  fullyMigrated: boolean;
}

export type LocalVaultKeyOutcome =
  | 'ALREADY_V3'
  | 'MIGRATED'
  | 'FAILED'
  | 'ROLLED_BACK'
  | 'UNRECOVERABLE';

export interface LocalVaultKeyResult extends LocalVaultKeyState {
  success: boolean;
  outcome?: LocalVaultKeyOutcome;
  code?: string | null;
}

export interface LocalPrivateLockResult extends LocalPrivateVaultLockState {
  /** False when something holds a lease, so nothing was interrupted. */
  locked: boolean;
}

export interface LocalPrivateVideoItem {
  id: string;
  title: string;
  createdAt: number;
  updatedAt: number;
  mimeType: string;
  durationSec?: number | null;
  sizeBytesEncrypted: number;
  cipherVersion: string;
  containerExt?: string | null;
  hasThumbnail?: boolean;
  thumbnailUri?: string | null;
  thumbWidth?: number | null;
  thumbHeight?: number | null;
  migrationFailed?: boolean;
  migrationFailedCode?: string | null;
  tags?: string[];
  folderId?: string | null;
}

export interface PrivateVaultTag {
  id: string;
  name: string;
  color: string;
  createdAt: number;
}

export interface PrivateVaultFolder {
  id: string;
  name: string;
  createdAt: number;
}

export interface PrivateVaultTagDeleteResult {
  success: boolean;
  removedFromCount?: number;
  code?: string;
}

export interface PrivateVaultFolderDeleteResult {
  success: boolean;
  movedToRootCount?: number;
  code?: string;
}

export interface LocalPrivateRenameResult {
  success: boolean;
  code?: string;
  entry?: LocalPrivateVideoItem;
}

export interface LocalPrivateThumbnailUriResult {
  success: boolean;
  uri?: string | null;
  hasThumbnail?: boolean;
  code?: string;
}

export type LocalPrivateMigrationOutcome = 'STARTED' | 'COMPLETED' | 'CANCELLED' | 'BLOCKED_KEY_INVALIDATED';

export interface LocalPrivateMigrationStartResult {
  success: boolean;
  outcome?: LocalPrivateMigrationOutcome;
  total?: number;
  code?: string;
  freeBytes?: number;
  requiredBytes?: number;
  batteryLevel?: number;
  isCharging?: boolean;
}

export interface LocalPrivateMigrationCancelResult {
  success: boolean;
  wasRunning?: boolean;
}

export interface LocalPrivateMigrationProgress {
  total: number;
  processed: number;
  succeeded: number;
  failed: number;
  skipped: number;
  currentEntryId?: string | null;
  currentTitle?: string | null;
  lastErrorCode?: string | null;
  lastErrorDetail?: string | null;
}

export interface LocalPrivateMigrationStatus extends LocalPrivateMigrationProgress {
  running: boolean;
}

export interface LocalVaultDiagnostics {
  loopbackRunning: boolean;
  loopbackPort?: number | null;
  activeVideoSessions: number;
  evictedVideoSessions: number;
  cipherCounts: {
    v4: number;
    v3: number;
    other: number;
  };
  migration: {
    running: boolean;
    lastProcessed?: number | null;
    lastTotal?: number | null;
    lastErrorCode?: string | null;
  };
}

export interface LocalPrivateCopyToPublicResult {
  success: boolean;
  uri?: string;
  code?: string;
  message?: string;
}

export interface LocalPrivateImportResult {
  success: boolean;
  item?: LocalPrivateVideoItem;
  code?: string;
  message?: string;
}

export interface LocalDiagnostics {
  ytDlpVersion: string;
  ytDlpAvailable: boolean;
  pythonReady: boolean;
  ytDlpBundledVersion?: string | null;
  ytDlpActiveVersion?: string | null;
  ytDlpOverrideVersion?: string | null;
  ytDlpPendingVersion?: string | null;
  ytDlpFailedVersion?: string | null;
  ytDlpFailedReason?: string | null;
  ytDlpOverrideSource?: string | null;
  ytDlpOverridePath?: string | null;
  ytDlpOverrideStorageReady?: boolean;
  normalizedUrlLast?: string | null;
  attemptTraceCount?: number;
  attemptTrace?: Array<{
    timeMs?: number;
    phase?: string;
    attemptId?: string;
    strategy?: string;
    status?: string;
    platform?: string;
    cookieUsed?: boolean;
    retryIndex?: number;
    extractorKey?: string;
    errorCode?: string;
    errorMessage?: string;
    impersonate?: string;
  }>;
  lastExtractorKey?: string | null;
  lastRawYtDlpError?: string | null;
  lastCookieCheck?: {
    platform?: string;
    hasCookieFile: boolean;
    domainCoverage: string[];
    unexpiredCount: number;
  } | null;
  ytDlpVersionAgeDays?: number | null;
  platformStrategyLast?: string | null;
  impersonationRuntimeAvailable?: boolean | null;
  impersonationEnabled?: boolean;
  impersonationBackend?: 'curl_cffi' | 'none';
  impersonationRequiredByExtractorLast?: string | null;
  impersonationAttemptedTargetsLast?: string[];
  impersonationResolvedTargetLast?: string | null;
  impersonationWheelVersion?: string | null;
  impersonationBuildAbiCoverage?: string[];
  impersonationBootstrapError?: string | null;
  ffmpegPath: string | null;
  ffprobePath: string | null;
  ffmpegAbi?: string | null;
  ffmpegRuntimeSource?: 'native_library' | 'asset_fallback' | 'none';
  nativeLibraryDir?: string | null;
  nativeLibraryEntries?: string[];
  ffmpegVersion?: string | null;
  ffprobeVersion?: string | null;
  ffmpegExists: boolean;
  ffprobeExists: boolean;
  ffmpegExecutable?: boolean;
  ffprobeExecutable?: boolean;
  ffmpegProbeError?: string | null;
  ffprobeProbeError?: string | null;
  mergeCapable: boolean;
  activeHttpUserAgent: string;
  serviceRunning?: boolean;
  queuedDownloadCount?: number;
  privateModeEnabled?: boolean;
  privateVaultCount?: number;
  privateVaultCipherActive?: 'v1' | 'v2' | 'v3' | 'v4';
  privateVaultLegacyCount?: number;
  privateLastEncryptMs?: number | null;
  privateLastDecryptMs?: number | null;
  privateLastThroughputMbps?: number | null;
  lastBackgroundServiceError?: string | null;
  secureCookieStoreEnabled: boolean;
  cookieEncryptionVersion: string;
  cookieProfilesEncryptedCount: number;
  customDomainsCount?: number;
  customProfilesCount?: number;
  cookieLegacyPlaintextCount: number;
  cookieMigrationStatus: 'not_needed' | 'migrated' | 'partial' | 'failed';
  customDomainMatchLast?: {
    urlHost: string;
    matchedDomain?: string | null;
    profileName?: string | null;
  } | null;
  activeTaskIds: string[];
  lastErrors: string[];
}

export interface LocalImpersonationSelfTestResult {
  success: boolean;
  code: string;
  message?: string | null;
  impersonation_enabled?: boolean;
  backend?: string | null;
  wheel_version?: string | null;
  build_abi_coverage?: string[];
}

/** The sections a backup can hold. Wire values shared with `BackupFormat.kt`. */
export type LocalBackupSectionId = 'vault' | 'music' | 'memes' | 'settings' | 'cookies';

export type LocalBackupSecretKind = 'password' | 'passphrase';

/** One secret and the key slot it opens. A single entry protects the whole file. */
export interface LocalBackupSecret {
  slotId: string;
  secret: string;
  kind: LocalBackupSecretKind;
}

export interface LocalBackupSectionSummary {
  id: LocalBackupSectionId;
  keySlot?: string;
  itemCount: number;
  plaintextBytes: number;
}

export interface LocalBackupCreateInput {
  sections: LocalBackupSectionId[];
  secrets: LocalBackupSecret[];
  /** Serialised AsyncStorage blob. Native never inspects its shape. */
  settings?: string;
  suggestedName?: string;
  /** Section id -> key slot, for per-section secrets. Defaults to one shared slot. */
  sectionSlots?: Record<string, string>;
}

export interface LocalBackupCreateResult {
  success: boolean;
  uri?: string;
  sections?: LocalBackupSectionSummary[];
  /**
   * Items whose source became unreadable while the backup was written — deleted partway
   * through, most likely. The file is still valid and holds everything else; these entries
   * are marked incomplete so a restore refuses them rather than writing truncated content.
   */
  failed?: { section: LocalBackupSectionId; name: string; error: string }[];
  code?: string;
  message?: string;
}

/**
 * What the plaintext header says a backup holds. Readable without any secret, which is what
 * lets the import screen describe a file before asking for one.
 */
export interface LocalBackupPreview {
  success: boolean;
  uri?: string;
  createdAt?: number;
  appVersion?: string;
  appVersionCode?: number;
  sections?: LocalBackupSectionSummary[];
  keySlots?: { id: string; secretKind: LocalBackupSecretKind }[];
  code?: string;
  message?: string;
}

export type LocalBackupItemOutcome =
  /** A file was added to a library. */
  | 'RESTORED'
  /** Metadata was written — playlists, preferences, the auto-apply config, cover art. */
  | 'APPLIED'
  /** The same content is already stored, identified by hash. */
  | 'SKIPPED_DUPLICATE'
  /** Something with this identity already exists and was left alone (cookie profiles). */
  | 'SKIPPED_EXISTS'
  | 'SKIPPED_UNWANTED'
  | 'FAILED';

export interface LocalBackupItemResult {
  section: LocalBackupSectionId;
  name: string;
  outcome: LocalBackupItemOutcome;
  error?: string | null;
}

export interface LocalBackupRestoreInput {
  uri: string;
  sections: LocalBackupSectionId[];
  secrets: LocalBackupSecret[];
}

export interface LocalBackupRestoreResult {
  success: boolean;
  /** Returned for the TS layer to write back into AsyncStorage. */
  settings?: string | null;
  /** Files added to a library. Deliberately excludes playlists, art and preferences. */
  restored?: number;
  /** Metadata written: playlists, cover art, the preset config, preferences. */
  applied?: number;
  skippedDuplicates?: number;
  /** Cookie profiles left alone because one of that name is already signed in. */
  skippedExisting?: number;
  failed?: number;
  items?: LocalBackupItemResult[];
  code?: string;
  message?: string;
}

/** An export or restore currently running. */
export interface LocalBackupJob {
  mode: 'exporting' | 'restoring';
  processed: number;
  total: number;
  /**
   * Which section is being handled — a category, never an item name.
   *
   * The name of the file in flight is dropped inside the module and never crosses the
   * bridge. This app holds a private vault, and a filename is the thing it exists to keep
   * out of sight; a count conveys progress just as well and reveals nothing.
   */
  section?: string | null;
  startedAt: number;
}

export interface LocalBackupJobOutcome {
  mode: 'exporting' | 'restoring';
  success: boolean;
  summary?: string | null;
  finishedAt: number;
}

/**
 * Enough for a screen to reattach to work that started before it was opened, or that
 * outlived it. The promise from the original call resolves into a component that may no
 * longer exist, so the module keeps the last outcome too.
 */
export interface LocalBackupJobState {
  active: LocalBackupJob | null;
  last: LocalBackupJobOutcome | null;
}

// ---- device pairing ----------------------------------------------------------------

/** A device this phone has paired with. */
export interface LocalPairedDevice {
  /** Hex SHA-256 of the peer's Ed25519 public key. Its identity. */
  fingerprint: string;
  /** Chosen by the peer. Shown, never matched against. */
  name: string;
  lastAddress: string;
  connected: boolean;
}

/** A device seen on the network that this phone has not paired with. */
export interface LocalDiscoveredDevice {
  fingerprint: string;
  name: string;
  host: string;
  port: number;
}

/** One track on a peer, from its `listing`. */
export interface LocalPeerItem {
  id: string;
  title: string;
  artist?: string | null;
  durationSec: number;
  sizeBytes: number;
}

/**
 * Everything the pairing screen renders.
 *
 * Deliberately absent: the name of any file in flight. A transfer reports a size and a
 * count, which shows progress just as well and keeps a track title off a screen that may
 * be shown to whoever is standing there.
 */
export interface LocalPairingState {
  /** This device's own fingerprint and name. */
  fingerprint: string;
  deviceName: string;
  /** False if the identity key could not be created; pairing is impossible. */
  ready: boolean;
  listening: boolean;
  port: number;
  /** True while the user has opened the window in which an unknown device may present itself. */
  pairingMode: boolean;
  /** Six digits when a device is waiting to be confirmed, empty otherwise. */
  pendingCode: string;
  pendingName: string;
  /** The last thing worth telling the user. Never a file name. */
  message: string;
  transferDone: number;
  transferTotal: number;
  peers: LocalPairedDevice[];
  discovered: LocalDiscoveredDevice[];
  /** Whose listing `listing` holds. */
  listingFrom: string;
  listing: LocalPeerItem[];
  /** A URL a peer asked this phone to fetch. Shown for the user to accept, never started. */
  peerUrl: string;
  peerMediaKind: string;
  /** Links from paired devices are downloaded without asking. Opt-in. */
  autoDownloadLinks: boolean;
}

// ---- memes: shared/memes/CONTRACT.md ----------------------------------------------------

/** What a tag says about a meme. Wire values: never renamed in place. */
export type LocalMemeFacet = 'reaction' | 'vibe' | 'emotion' | 'action' | 'context';

export interface LocalMemeTag {
  id: string;
  name: string;
  facets: LocalMemeFacet[];
}

export interface LocalMemePerson {
  id: string;
  name: string;
  /** Whether the faces know what this person looks like. */
  known?: boolean;
}

/** A face in a meme. Its signature stays native. */
export interface LocalMemeFace {
  id: string;
  person?: string | null;
  state: 'auto' | 'confirmed' | 'asked' | 'unnamed';
}

/** Unnamed faces of one person, as far as the faces can tell: the first twelve, and how many. */
export interface LocalMemeFaceGroup {
  count: number;
  faces: { itemId: string; faceId: string }[];
}

/** Where a meme came from, captured at download time. Never logged, never in a notification. */
export interface LocalMemeSource {
  platform?: string | null;
  account?: string | null;
  accountName?: string | null;
  caption?: string | null;
  url?: string | null;
  postedAt?: number | null;
  savedAt: number;
}

export interface LocalMeme {
  id: string;
  kind: 'video' | 'image';
  isPrivate: boolean;
  /** MediaStore URI of one that is not private. */
  uri?: string | null;
  /** The vault entry of one that is. */
  vaultId?: string | null;
  tags: string[];
  people: string[];
  addedAt: number;
  /** 0 while the meme waits in the untagged inbox. */
  taggedAt: number;
  source?: LocalMemeSource | null;
  faces?: LocalMemeFace[];
}

export interface LocalMemeLibrary {
  items: LocalMeme[];
  tags: LocalMemeTag[];
  people: LocalMemePerson[];
  /** Private memes are in `items` only while this is true. */
  vaultUnlocked: boolean;
  /** Whether any meme is private, known while locked too. */
  hasPrivate: boolean;
  askForTags: boolean;
  /** Android 9 and later, with the faces libraries loaded. */
  facesSupported?: boolean;
  /** Memes left to look at while a scan runs; null when idle. */
  facesRemaining?: number | null;
}

export interface LocalMemeFilter {
  /** All of these, across the meme's tags. */
  facets?: LocalMemeFacet[];
  /** Person ids, all required. */
  people?: string[];
  platform?: string | null;
  onlyPrivate?: boolean;
  onlyUntagged?: boolean;
}

export interface LocalMemeLabelInput {
  ids: string[];
  addTags?: string[];
  removeTags?: string[];
  addPeople?: string[];
  removePeople?: string[];
  /** Takes the memes out of the untagged inbox. Defaults to true. */
  markTagged?: boolean;
}

export interface LocalMemeResult {
  success: boolean;
  code?: string;
  failed?: number;
  imported?: number;
}

// ---- watch: shared/watch/CONTRACT.md -------------------------------------------------------

/** An installed add-on as the screens see it: its URL stays native, behind [key]. */
export interface WatchAddon {
  key: string;
  name: string;
  host: string;
  version?: string | null;
  description?: string | null;
  logo?: string | null;
  types: string[];
  resources: string[];
  enabled: boolean;
}

/** An add-on an installed add-on offers. Its address is public, from the catalog. */
export interface WatchOffer {
  url: string;
  configureUrl?: string | null;
  name: string;
  description?: string | null;
  logo?: string | null;
  types: string[];
  resources: string[];
  configurable: boolean;
  /** It cannot be installed without configuring it first. */
  required: boolean;
  installed: boolean;
}

export interface WatchRow {
  addonKey: string;
  addonName: string;
  type: string;
  id: string;
  name: string;
}

export interface WatchPreview {
  id: string;
  type: string;
  name: string;
  poster?: string | null;
  posterShape: string;
  releaseInfo?: string | null;
  description?: string | null;
}

export interface WatchVideo {
  id: string;
  title: string;
  season?: number | null;
  episode?: number | null;
  released?: string | null;
  thumbnail?: string | null;
  overview?: string | null;
}

export interface WatchMeta {
  id: string;
  type: string;
  name: string;
  poster?: string | null;
  background?: string | null;
  logo?: string | null;
  description?: string | null;
  releaseInfo?: string | null;
  runtime?: string | null;
  genres: string[];
  imdbRating?: string | null;
  videos: WatchVideo[];
  /** Trailers, as YouTube streams. */
  trailers?: WatchStream[];
}

export type WatchStreamKind = 'url' | 'youtube' | 'torrent' | 'external';

export interface WatchStream {
  kind: WatchStreamKind;
  target: string;
  fileIdx?: number | null;
  label: string;
  detail: string;
  bingeGroup?: string | null;
  headers: Record<string, string>;
  filename?: string | null;
}

export interface WatchProgress {
  videoId: string;
  positionMs: number;
  durationMs: number;
  at: number;
}

export interface WatchItem {
  id: string;
  type: string;
  name: string;
  poster?: string | null;
  addedAt: number;
  watched: string[];
  saved: boolean;
  addonKey?: string | null;
  bingeGroup?: string | null;
  progress?: WatchProgress | null;
}

/** A title as the library remembers it. */
export interface WatchTitle {
  id: string;
  type: string;
  name: string;
  poster?: string | null;
}

/** A failure: one of the WATCH_* or RESOLVE_* codes. */
export interface WatchFailure {
  success: false;
  code: string;
}

export type WatchResult<T> = ({ success: true } & T) | WatchFailure;

export interface WatchPrepared {
  url: string;
  headers: Record<string, string>;
  title?: string | null;
  isLive?: boolean;
}
