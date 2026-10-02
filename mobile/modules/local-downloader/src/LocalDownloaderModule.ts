import { EventEmitter, type EventSubscription, requireNativeModule } from 'expo-modules-core';
import { Platform } from 'react-native';
import type {
  LocalAudioFormat,
  LocalAudioFormatState,
  LocalAudioPresetDiagnostics,
  LocalBackupCreateInput,
  LocalBackupJobState,
  LocalBackupCreateResult,
  LocalBackupPreview,
  LocalBackupRestoreInput,
  LocalBackupRestoreResult,
  LocalPairingState,
  LocalMemeFacet,
  LocalMemeFaceGroup,
  LocalMemeFilter,
  LocalMemeLabelInput,
  LocalMemeLibrary,
  LocalMemePerson,
  LocalMemeResult,
  LocalMemeTag,
  LocalSoundPresetProgressEvent,
  LocalSoundPresetStartResult,
  LocalCookieProfile,
  LocalBackgroundPermissionResult,
  LocalBackgroundState,
  LocalBackgroundStateEvent,
  LocalStickyNotificationState,
  LocalCustomCookieImportInput,
  LocalCustomCookieImportResult,
  LocalCustomDomainProfile,
  LocalCustomDomainSummary,
  LocalDiagnostics,
  LocalDownloadFailureLog,
  LocalDownloadEvent,
  LocalDownloadStartInput,
  LocalDownloadStartResult,
  LocalPrivateAuthPurpose,
  LocalPrivateAuthResult,
  LocalPrivateLockResult,
  LocalPrivateUnlockResult,
  LocalPrivateVaultLockState,
  LocalVaultKeyResult,
  LocalVaultKeyState,
  LocalPrivateModeState,
  LocalPrivateVideoItem,
  LocalImpersonationSelfTestResult,
  LocalPrivateCopyToPublicResult,
  LocalSaveToMediaStoreInput,
  LocalSaveToMediaStoreResult,
  LocalPlatform,
  LocalPrivateImportResult,
  LocalPrivateMigrationCancelResult,
  LocalPrivateMigrationProgress,
  LocalPrivateMigrationStartResult,
  LocalPrivateMigrationStatus,
  LocalPrivateRenameResult,
  LocalPrivateThumbnailUriResult,
  PrivateVaultFolder,
  PrivateVaultFolderDeleteResult,
  PrivateVaultTag,
  PrivateVaultTagDeleteResult,
  LocalQuickDownloadResult,
  LocalSound,
  LocalSoundPlaylist,
  LocalSoundsImportResult,
  LocalSoundsLibrary,
  LocalTaskStatusResult,
  LocalVaultDiagnostics,
  LocalYtDlpUpdateCheckResult,
  LocalYtDlpUpdateProgressEvent,
  LocalYtDlpUpdateResult,
  LocalYtDlpUpdateStatus,
} from './LocalDownloader.types';

type LocalDownloaderNativeModule = {
  startDownload(input: LocalDownloadStartInput): Promise<LocalDownloadStartResult>;
  getTaskStatus(taskId: string): Promise<LocalTaskStatusResult>;
  cancelTask(taskId: string): Promise<{ success: boolean }>;
  getBackgroundState(): Promise<LocalBackgroundState>;
  ensureBackgroundPermission(): Promise<LocalBackgroundPermissionResult>;
  setStickyNotificationEnabled(input: { enabled: boolean }): Promise<LocalStickyNotificationState>;
  startQuickDownloadFromClipboard(): Promise<LocalQuickDownloadResult>;
  startQuickDownloadWithUrl(input: { url: string; mediaKind?: 'audio' | 'video' }): Promise<LocalQuickDownloadResult>;
  getPrivateModeState(): Promise<LocalPrivateModeState>;
  setPrivateModeEnabled(input: { enabled: boolean }): Promise<LocalPrivateModeState>;
  getAudioModeState(): Promise<{ enabled: boolean }>;
  setAudioModeEnabled(input: { enabled: boolean }): Promise<{ enabled: boolean }>;
  getAudioFormat(): Promise<LocalAudioFormatState>;
  setAudioFormat(input: { format: LocalAudioFormat }): Promise<LocalAudioFormatState>;
  authenticatePrivateAccess(input: { purpose: LocalPrivateAuthPurpose }): Promise<LocalPrivateAuthResult>;
  unlockPrivateVault(input: { purpose: LocalPrivateAuthPurpose }): Promise<LocalPrivateUnlockResult>;
  lockPrivateVault(): Promise<LocalPrivateLockResult>;
  getPrivateVaultLockState(): Promise<LocalPrivateVaultLockState>;
  getVaultKeyState(): Promise<LocalVaultKeyState>;
  upgradeVaultKey(): Promise<LocalVaultKeyResult>;
  finaliseVaultKeyUpgrade(input: { force?: boolean }): Promise<LocalVaultKeyResult>;
  setVaultRecoveryPassphrase(input: { passphrase: string }): Promise<LocalVaultKeyResult>;
  unlockVaultWithRecovery(input: { passphrase: string }): Promise<LocalVaultKeyResult>;
  listPrivateVideos(): Promise<LocalPrivateVideoItem[]>;
  deletePrivateVideo(input: { id: string }): Promise<{ success: boolean }>;
  copyPrivateVideoToPublicGallery(input: { id: string }): Promise<LocalPrivateCopyToPublicResult>;
  pickAndImportVideoToPrivateVault(): Promise<LocalPrivateImportResult>;
  makeVideoPublic(input: { id: string }): Promise<{ success: boolean; uri?: string; code?: string; message?: string }>;
  preparePrivatePlayback(input: { id: string; traceId?: string }): Promise<{ success: boolean; tempUri?: string; mimeType?: string; streaming?: boolean }>;
  setSecureScreen(input: { enabled: boolean }): Promise<{ success: boolean }>;
  clearPrivatePlaybackCache(): Promise<void>;
  renamePrivateVideo(input: { id: string; title: string }): Promise<LocalPrivateRenameResult>;
  getPrivateThumbnailUri(input: { id: string }): Promise<LocalPrivateThumbnailUriResult>;
  listVaultTags(): Promise<PrivateVaultTag[]>;
  createVaultTag(input: { name: string; color?: string }): Promise<PrivateVaultTag>;
  renameVaultTag(input: { id: string; name: string }): Promise<PrivateVaultTag>;
  setVaultTagColor(input: { id: string; color: string }): Promise<PrivateVaultTag>;
  deleteVaultTag(input: { id: string }): Promise<PrivateVaultTagDeleteResult>;
  setVaultEntryTags(input: { ids: string[]; tagIds: string[] }): Promise<{ success: boolean; updatedCount?: number }>;
  listVaultFolders(): Promise<PrivateVaultFolder[]>;
  createVaultFolder(input: { name: string }): Promise<PrivateVaultFolder>;
  renameVaultFolder(input: { id: string; name: string }): Promise<PrivateVaultFolder>;
  deleteVaultFolder(input: { id: string }): Promise<PrivateVaultFolderDeleteResult>;
  setVaultEntryFolder(input: { ids: string[]; folderId: string | null }): Promise<{ success: boolean; updatedCount?: number }>;
  startPrivateVaultMigration(): Promise<LocalPrivateMigrationStartResult>;
  cancelPrivateVaultMigration(): Promise<LocalPrivateMigrationCancelResult>;
  getPrivateVaultMigrationStatus(): Promise<LocalPrivateMigrationStatus>;
  getVaultDiagnostics(): Promise<LocalVaultDiagnostics>;
  isSoundsSupported(): boolean;
  applySoundPresets(input: {
    songIds: string[];
    presetId: string;
    paramsSpec: string;
    titleSuffix: string;
  }): Promise<LocalSoundPresetStartResult>;
  cancelSoundPresetRender(input: { renderId: string }): Promise<{ success: boolean }>;
  setAutoPresetConfig(input: { config: string }): Promise<{ success: boolean }>;
  getAutoPresetConfig(): Promise<{ config: string | null }>;
  getAudioPresetDiagnostics(): Promise<LocalAudioPresetDiagnostics>;
  getSecureRandomBytes(input: { count: number }): Promise<{ bytes: number[] }>;
  createBackup(input: LocalBackupCreateInput): Promise<LocalBackupCreateResult>;
  previewBackup(): Promise<LocalBackupPreview>;
  getBackupJobState(): Promise<LocalBackupJobState>;
  restoreBackup(input: LocalBackupRestoreInput): Promise<LocalBackupRestoreResult>;
  // ---- device pairing ----
  /** Relaunch the app, so a downloaded yt-dlp is picked up. */
  restartApp(): Promise<{ restarted: boolean; reason?: string; activeTaskIds?: string[] }>;

  pairingState(): Promise<LocalPairingState>;
  pairingStart(): Promise<boolean>;
  pairingStop(): Promise<boolean>;
  pairingBeginPairing(seconds: number): Promise<boolean>;
  pairingCancelPairing(): Promise<boolean>;
  pairingConfirm(): Promise<boolean>;
  pairingConnect(host: string, port: number): Promise<boolean>;
  pairingForget(fingerprint: string): Promise<boolean>;
  pairingSetDeviceName(name: string): Promise<boolean>;
  pairingBrowse(fingerprint: string): Promise<boolean>;
  pairingFetch(fingerprint: string, id: string): Promise<boolean>;
  pairingSend(fingerprint: string, songId: string): Promise<boolean>;
  pairingSendUrl(fingerprint: string, url: string, mediaKind: string): Promise<boolean>;
  pairingCancelTransfer(fingerprint: string): Promise<boolean>;
  pairingClearPeerUrl(): Promise<boolean>;
  pairingSetAutoDownloadLinks(enabled: boolean): Promise<boolean>;
  pairingHasPeers(): Promise<boolean>;
  pairingSendMeme(fingerprint: string, id: string): Promise<boolean>;

  // ---- memes ----
  listMemes(): Promise<LocalMemeLibrary>;
  memeThumbnail(id: string): Promise<string | null>;
  memeSuggestions(id: string): Promise<string[]>;
  searchMemes(query: string, filter: LocalMemeFilter): Promise<string[]>;
  memeFaceGroups(): Promise<LocalMemeFaceGroup[]>;
  memeFaceCrop(itemId: string, faceId: string): Promise<string | null>;
  confirmMemeFace(faceId: string): Promise<LocalMemeResult>;
  rejectMemeFace(faceId: string): Promise<LocalMemeResult>;
  nameMemeFaces(faceIds: string[], name: string): Promise<LocalMemeResult>;
  renameMemePerson(personId: string, name: string): Promise<LocalMemeResult>;
  deleteMemePerson(personId: string): Promise<LocalMemeResult>;
  createMemeTag(name: string, facets: LocalMemeFacet[]): Promise<LocalMemeTag>;
  createMemePerson(name: string): Promise<LocalMemePerson>;
  setMemeTagFacets(tagId: string, facets: LocalMemeFacet[]): Promise<void>;
  renameMemeTag(tagId: string, name: string): Promise<void>;
  deleteMemeTag(tagId: string): Promise<void>;
  labelMemes(input: LocalMemeLabelInput): Promise<LocalMemeResult>;
  setMemesPrivate(ids: string[], makePrivate: boolean): Promise<LocalMemeResult>;
  removeMemes(ids: string[]): Promise<LocalMemeResult>;
  importMemes(): Promise<LocalMemeResult>;
  setMemeAskForTags(enabled: boolean): Promise<void>;
  dismissMemePrompt(id: string): Promise<void>;

  listSounds(): Promise<LocalSoundsLibrary>;
  importSounds(): Promise<LocalSoundsImportResult>;
  deleteSounds(input: { ids: string[] }): Promise<{ deletedCount: number }>;
  renameSound(input: { id: string; title: string }): Promise<LocalSound>;
  getSoundThumbnail(input: { id: string }): Promise<{ path: string | null }>;
  listSoundPlaylists(): Promise<LocalSoundPlaylist[]>;
  createSoundPlaylist(input: { name: string }): Promise<LocalSoundPlaylist>;
  renameSoundPlaylist(input: { id: string; name: string }): Promise<LocalSoundPlaylist>;
  deleteSoundPlaylist(input: { id: string }): Promise<{ success: boolean }>;
  setSoundPlaylistSongs(input: { id: string; songIds: string[] }): Promise<LocalSoundPlaylist>;
  addSoundsToPlaylists(input: { songIds: string[]; playlistIds: string[] }): Promise<{ success: boolean }>;
  removeSoundsFromPlaylist(input: { playlistId: string; songIds: string[] }): Promise<LocalSoundPlaylist>;
  setSoundsFavorite(input: { songIds: string[]; favorite: boolean }): Promise<LocalSoundPlaylist>;
  importCookie(input: { platform: LocalPlatform; uri: string; profileName: string }): Promise<{ profileName: string; path: string }>;
  listCookieProfiles(platform: LocalPlatform): Promise<LocalCookieProfile[]>;
  setCookieDefault(input: { platform: LocalPlatform; profileName: string }): Promise<{ success: boolean }>;
  deleteCookieProfile(input: { platform: LocalPlatform; profileName: string }): Promise<{ success: boolean }>;
  getCookieDefaults(): Promise<Record<LocalPlatform, string | null>>;
  importCustomCookie(input: LocalCustomCookieImportInput): Promise<LocalCustomCookieImportResult>;
  listCustomDomains(): Promise<LocalCustomDomainSummary[]>;
  listCustomDomainProfiles(domain: string): Promise<LocalCustomDomainProfile[]>;
  setCustomDomainDefault(input: { domain: string; profileName: string }): Promise<{ success: boolean }>;
  deleteCustomDomainProfile(input: { domain: string; profileName: string }): Promise<{ success: boolean }>;
  getDiagnostics(): Promise<LocalDiagnostics>;
  getDownloadFailureLogs(): Promise<LocalDownloadFailureLog[]>;
  runImpersonationSelfTest(): Promise<LocalImpersonationSelfTestResult>;
  saveToMediaStore(input: LocalSaveToMediaStoreInput): Promise<LocalSaveToMediaStoreResult>;
  getYtDlpUpdateStatus(): Promise<LocalYtDlpUpdateStatus>;
  checkYtDlpUpdate(): Promise<LocalYtDlpUpdateCheckResult>;
  /** @param version undefined for the newest stable release. */
  updateYtDlp(version?: string): Promise<LocalYtDlpUpdateResult>;
  /** Recent stable releases, newest first. */
  listYtDlpVersions(): Promise<{ versions: string[] }>;
  clearYtDlpOverride(): Promise<{ success: boolean; requiresRestart?: boolean }>;
};

const unsupported = (): never => {
  throw new Error('Local downloader native module is available on Android development builds only.');
};

const NativeLocalDownloader: LocalDownloaderNativeModule = Platform.OS === 'android'
  ? requireNativeModule<LocalDownloaderNativeModule>('LocalDownloader')
  : {
      startDownload: async () => unsupported(),
      getTaskStatus: async () => unsupported(),
      cancelTask: async () => unsupported(),
      getBackgroundState: async () => unsupported(),
      ensureBackgroundPermission: async () => unsupported(),
      setStickyNotificationEnabled: async () => unsupported(),
      startQuickDownloadFromClipboard: async () => unsupported(),
      startQuickDownloadWithUrl: async () => unsupported(),
      getPrivateModeState: async () => unsupported(),
      setPrivateModeEnabled: async () => unsupported(),
      getAudioModeState: async () => unsupported(),
      setAudioModeEnabled: async () => unsupported(),
      getAudioFormat: async () => unsupported(),
      setAudioFormat: async () => unsupported(),
      authenticatePrivateAccess: async () => unsupported(),
      unlockPrivateVault: async () => unsupported(),
      lockPrivateVault: async () => unsupported(),
      getPrivateVaultLockState: async () => unsupported(),
      getVaultKeyState: async () => unsupported(),
      upgradeVaultKey: async () => unsupported(),
      finaliseVaultKeyUpgrade: async () => unsupported(),
      setVaultRecoveryPassphrase: async () => unsupported(),
      unlockVaultWithRecovery: async () => unsupported(),
      listPrivateVideos: async () => unsupported(),
      deletePrivateVideo: async () => unsupported(),
      copyPrivateVideoToPublicGallery: async () => unsupported(),
      pickAndImportVideoToPrivateVault: async () => unsupported(),
      makeVideoPublic: async () => unsupported(),
      preparePrivatePlayback: async () => unsupported(),
      setSecureScreen: async () => unsupported(),
      clearPrivatePlaybackCache: async () => unsupported(),
      renamePrivateVideo: async () => unsupported(),
      getPrivateThumbnailUri: async () => unsupported(),
      listVaultTags: async () => unsupported(),
      createVaultTag: async () => unsupported(),
      renameVaultTag: async () => unsupported(),
      setVaultTagColor: async () => unsupported(),
      deleteVaultTag: async () => unsupported(),
      setVaultEntryTags: async () => unsupported(),
      listVaultFolders: async () => unsupported(),
      createVaultFolder: async () => unsupported(),
      renameVaultFolder: async () => unsupported(),
      deleteVaultFolder: async () => unsupported(),
      setVaultEntryFolder: async () => unsupported(),
      startPrivateVaultMigration: async () => unsupported(),
      cancelPrivateVaultMigration: async () => unsupported(),
      getPrivateVaultMigrationStatus: async () => unsupported(),
      getVaultDiagnostics: async () => unsupported(),
      isSoundsSupported: () => false,
      applySoundPresets: async () => unsupported(),
      cancelSoundPresetRender: async () => unsupported(),
      setAutoPresetConfig: async () => unsupported(),
      getAutoPresetConfig: async () => unsupported(),
      getAudioPresetDiagnostics: async () => unsupported(),
      getSecureRandomBytes: async () => unsupported(),
      createBackup: async () => unsupported(),
      previewBackup: async () => unsupported(),
      getBackupJobState: async () => unsupported(),
      restoreBackup: async () => unsupported(),
      restartApp: async () => unsupported(),
      pairingState: async () => unsupported(),
      pairingStart: async () => unsupported(),
      pairingStop: async () => unsupported(),
      pairingBeginPairing: async () => unsupported(),
      pairingCancelPairing: async () => unsupported(),
      pairingConfirm: async () => unsupported(),
      pairingConnect: async () => unsupported(),
      pairingForget: async () => unsupported(),
      pairingSetDeviceName: async () => unsupported(),
      pairingBrowse: async () => unsupported(),
      pairingFetch: async () => unsupported(),
      pairingSend: async () => unsupported(),
      pairingSendUrl: async () => unsupported(),
      pairingCancelTransfer: async () => unsupported(),
      pairingClearPeerUrl: async () => unsupported(),
      pairingSetAutoDownloadLinks: async () => unsupported(),
      pairingHasPeers: async () => false,
      pairingSendMeme: async () => unsupported(),
      listMemes: async () => unsupported(),
      memeThumbnail: async () => unsupported(),
      memeSuggestions: async () => unsupported(),
      searchMemes: async () => unsupported(),
      memeFaceGroups: async () => [],
      memeFaceCrop: async () => null,
      confirmMemeFace: async () => unsupported(),
      rejectMemeFace: async () => unsupported(),
      nameMemeFaces: async () => unsupported(),
      renameMemePerson: async () => unsupported(),
      deleteMemePerson: async () => unsupported(),
      createMemeTag: async () => unsupported(),
      createMemePerson: async () => unsupported(),
      setMemeTagFacets: async () => unsupported(),
      renameMemeTag: async () => unsupported(),
      deleteMemeTag: async () => unsupported(),
      labelMemes: async () => unsupported(),
      setMemesPrivate: async () => unsupported(),
      removeMemes: async () => unsupported(),
      importMemes: async () => unsupported(),
      setMemeAskForTags: async () => unsupported(),
      dismissMemePrompt: async () => unsupported(),
      listSounds: async () => unsupported(),
      importSounds: async () => unsupported(),
      deleteSounds: async () => unsupported(),
      renameSound: async () => unsupported(),
      getSoundThumbnail: async () => unsupported(),
      listSoundPlaylists: async () => unsupported(),
      createSoundPlaylist: async () => unsupported(),
      renameSoundPlaylist: async () => unsupported(),
      deleteSoundPlaylist: async () => unsupported(),
      setSoundPlaylistSongs: async () => unsupported(),
      addSoundsToPlaylists: async () => unsupported(),
      removeSoundsFromPlaylist: async () => unsupported(),
      setSoundsFavorite: async () => unsupported(),
      importCookie: async () => unsupported(),
      listCookieProfiles: async () => unsupported(),
      setCookieDefault: async () => unsupported(),
      deleteCookieProfile: async () => unsupported(),
      getCookieDefaults: async () => unsupported(),
      importCustomCookie: async () => unsupported(),
      listCustomDomains: async () => unsupported(),
      listCustomDomainProfiles: async () => unsupported(),
      setCustomDomainDefault: async () => unsupported(),
      deleteCustomDomainProfile: async () => unsupported(),
      getDiagnostics: async () => unsupported(),
      getDownloadFailureLogs: async () => unsupported(),
      runImpersonationSelfTest: async () => unsupported(),
      saveToMediaStore: async () => unsupported(),
      getYtDlpUpdateStatus: async () => unsupported(),
      checkYtDlpUpdate: async () => unsupported(),
      updateYtDlp: async () => unsupported(),
      listYtDlpVersions: async () => unsupported(),
      clearYtDlpOverride: async () => unsupported(),
    };
const emitter: any = Platform.OS === 'android' ? new EventEmitter(NativeLocalDownloader as never) : null;

export function addSoundPresetProgressListener(
  listener: (event: LocalSoundPresetProgressEvent) => void
): EventSubscription {
  if (!emitter) {
    return { remove: () => undefined };
  }
  return emitter.addListener('soundPresetProgress', listener);
}

export function addDownloadProgressListener(listener: (event: LocalDownloadEvent) => void): EventSubscription {
  if (!emitter) {
    return { remove: () => undefined };
  }
  return emitter.addListener('downloadProgress', listener);
}

export function addBackgroundStateListener(listener: (event: LocalBackgroundStateEvent) => void): EventSubscription {
  if (!emitter) {
    return { remove: () => undefined };
  }
  return emitter.addListener('backgroundStateChanged', listener);
}

export function addYtDlpUpdateProgressListener(listener: (event: LocalYtDlpUpdateProgressEvent) => void): EventSubscription {
  if (!emitter) {
    return { remove: () => undefined };
  }
  return emitter.addListener('ytDlpUpdateProgress', listener);
}

/**
 * Backup and restore progress. Emitted on every item and when a job ends, so a screen can
 * show live movement and can reattach after being closed and reopened.
 */
export function addBackupProgressListener(
  listener: (state: LocalBackupJobState) => void
): EventSubscription {
  if (!emitter) {
    return { remove: () => undefined };
  }
  return emitter.addListener('backupProgress', listener);
}

export function addPrivateVaultMigrationProgressListener(
  listener: (event: LocalPrivateMigrationProgress) => void
): EventSubscription {
  if (!emitter) {
    return { remove: () => undefined };
  }
  return emitter.addListener('privateVaultMigrationProgress', listener);
}

/**
 * Pairing state, whenever anything a screen renders changes — a device found or lost, a
 * code to confirm, transfer progress, a listing coming back.
 */
/** The collection changed: a download landed, a transfer arrived, labels moved. */
export function addMemesChangedListener(listener: () => void): EventSubscription {
  if (!emitter) {
    return { remove: () => undefined };
  }
  return emitter.addListener('memesChanged', listener);
}

export function addPairingStateListener(
  listener: (state: LocalPairingState) => void
): EventSubscription {
  if (!emitter) {
    return { remove: () => undefined };
  }
  return emitter.addListener('pairingStateChanged', listener);
}

export default NativeLocalDownloader;
