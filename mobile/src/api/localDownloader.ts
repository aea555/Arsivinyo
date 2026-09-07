import { Platform } from 'react-native';
import LocalDownloaderModule, {
  addBackgroundStateListener,
  addDownloadProgressListener,
  addPrivateVaultMigrationProgressListener,
  addYtDlpUpdateProgressListener,
  type LocalBackgroundPermissionResult,
  type LocalStickyNotificationState,
  type LocalBackgroundState,
  type LocalBackgroundStateEvent,
  type LocalCookieProfile,
  type LocalCustomCookieImportInput,
  type LocalCustomCookieImportResult,
  type LocalCustomDomainProfile,
  type LocalCustomDomainSummary,
  type LocalDiagnostics,
  type LocalDownloadFailureLog,
  type LocalDownloadEvent,
  type LocalDownloadStartInput,
  type LocalDownloadStartResult,
  type LocalImpersonationSelfTestResult,
  type LocalPrivateAuthPurpose,
  type LocalPrivateAuthResult,
  type LocalPrivateLockResult,
  type LocalPrivateUnlockResult,
  type LocalPrivateVaultLockState,
  type LocalPrivateCopyToPublicResult,
  type LocalPrivateImportResult,
  type LocalPrivateMigrationCancelResult,
  type LocalPrivateMigrationProgress,
  type LocalPrivateMigrationStartResult,
  type LocalPrivateMigrationStatus,
  type LocalPrivateModeState,
  type LocalPrivateRenameResult,
  type LocalPrivateThumbnailUriResult,
  type LocalPrivateVideoItem,
  type LocalSaveToMediaStoreInput,
  type LocalSaveToMediaStoreResult,
  type LocalPlatform,
  type LocalQuickDownloadResult,
  type LocalTaskStatusResult,
  type LocalVaultDiagnostics,
  type PrivateVaultFolder,
  type PrivateVaultFolderDeleteResult,
  type PrivateVaultTag,
  type PrivateVaultTagDeleteResult,
  type LocalYtDlpUpdateCheckResult,
  type LocalYtDlpUpdateProgressEvent,
  type LocalYtDlpUpdateResult,
  type LocalYtDlpUpdateStatus,
} from '../native/localDownloader';

function ensureAndroid(): void {
  if (Platform.OS !== 'android') {
    throw new Error('Local downloader is Android-only in this release.');
  }
}

export function listenDownloadProgress(listener: (event: LocalDownloadEvent) => void) {
  ensureAndroid();
  return addDownloadProgressListener(listener);
}

export function listenBackgroundState(listener: (event: LocalBackgroundStateEvent) => void) {
  ensureAndroid();
  return addBackgroundStateListener(listener);
}

export function listenYtDlpUpdateProgress(listener: (event: LocalYtDlpUpdateProgressEvent) => void) {
  ensureAndroid();
  return addYtDlpUpdateProgressListener(listener);
}

export function listenLocalPrivateVaultMigration(listener: (event: LocalPrivateMigrationProgress) => void) {
  ensureAndroid();
  return addPrivateVaultMigrationProgressListener(listener);
}

export async function startLocalDownload(input: LocalDownloadStartInput): Promise<LocalDownloadStartResult> {
  ensureAndroid();
  return LocalDownloaderModule.startDownload(input);
}

export async function getLocalTaskStatus(taskId: string): Promise<LocalTaskStatusResult> {
  ensureAndroid();
  return LocalDownloaderModule.getTaskStatus(taskId);
}

export async function cancelLocalTask(taskId: string): Promise<{ success: boolean }> {
  ensureAndroid();
  return LocalDownloaderModule.cancelTask(taskId);
}

export async function getLocalBackgroundState(): Promise<LocalBackgroundState> {
  ensureAndroid();
  return LocalDownloaderModule.getBackgroundState();
}

export async function ensureLocalBackgroundPermission(): Promise<LocalBackgroundPermissionResult> {
  ensureAndroid();
  return LocalDownloaderModule.ensureBackgroundPermission();
}

export async function setLocalStickyNotificationEnabled(enabled: boolean): Promise<LocalStickyNotificationState> {
  ensureAndroid();
  return LocalDownloaderModule.setStickyNotificationEnabled({ enabled });
}

export async function startQuickLocalDownloadFromClipboard(): Promise<LocalQuickDownloadResult> {
  ensureAndroid();
  return LocalDownloaderModule.startQuickDownloadFromClipboard();
}

export async function startQuickLocalDownloadWithUrl(url: string): Promise<LocalQuickDownloadResult> {
  ensureAndroid();
  return LocalDownloaderModule.startQuickDownloadWithUrl({ url });
}

export async function getLocalPrivateModeState(): Promise<LocalPrivateModeState> {
  ensureAndroid();
  return LocalDownloaderModule.getPrivateModeState();
}

export async function setLocalPrivateModeEnabled(enabled: boolean): Promise<LocalPrivateModeState> {
  ensureAndroid();
  return LocalDownloaderModule.setPrivateModeEnabled({ enabled });
}

export async function getLocalAudioModeState(): Promise<{ enabled: boolean }> {
  ensureAndroid();
  return LocalDownloaderModule.getAudioModeState();
}

export async function setLocalAudioModeEnabled(enabled: boolean): Promise<{ enabled: boolean }> {
  ensureAndroid();
  return LocalDownloaderModule.setAudioModeEnabled({ enabled });
}

export async function authenticateLocalPrivateAccess(purpose: LocalPrivateAuthPurpose): Promise<LocalPrivateAuthResult> {
  ensureAndroid();
  return LocalDownloaderModule.authenticatePrivateAccess({ purpose });
}

/**
 * Open the vault.
 *
 * The gate is native now: any vault call can come back with `PRIVATE_VAULT_LOCKED`, and this
 * is what answers it. `PRIVATE_STEP_UP_REQUIRED` means the session is open but the prompt is
 * stale, which happens on a delete or an export — the same call fixes both.
 */
export async function unlockLocalPrivateVault(
  purpose: LocalPrivateAuthPurpose = 'view',
): Promise<LocalPrivateUnlockResult> {
  ensureAndroid();
  return LocalDownloaderModule.unlockPrivateVault({ purpose });
}

/** Returns `locked: false` when a video, an export or a migration is still holding it open. */
export async function lockLocalPrivateVault(): Promise<LocalPrivateLockResult> {
  ensureAndroid();
  return LocalDownloaderModule.lockPrivateVault();
}

/** Answers while locked, so the lock indicator can always be drawn. */
export async function getLocalPrivateVaultLockState(): Promise<LocalPrivateVaultLockState> {
  ensureAndroid();
  return LocalDownloaderModule.getPrivateVaultLockState();
}

export async function listLocalPrivateVideos(): Promise<LocalPrivateVideoItem[]> {
  ensureAndroid();
  return LocalDownloaderModule.listPrivateVideos();
}

export async function deleteLocalPrivateVideo(id: string): Promise<{ success: boolean }> {
  ensureAndroid();
  return LocalDownloaderModule.deletePrivateVideo({ id });
}

export async function copyLocalPrivateVideoToPublicGallery(id: string): Promise<LocalPrivateCopyToPublicResult> {
  ensureAndroid();
  return LocalDownloaderModule.copyPrivateVideoToPublicGallery({ id });
}

export async function pickAndImportLocalVideoToPrivateVault(): Promise<LocalPrivateImportResult> {
  ensureAndroid();
  return LocalDownloaderModule.pickAndImportVideoToPrivateVault();
}

export async function makeLocalVideoPublic(id: string): Promise<{ success: boolean; uri?: string; code?: string; message?: string }> {
  ensureAndroid();
  return LocalDownloaderModule.makeVideoPublic({ id });
}

export async function prepareLocalPrivatePlayback(
  id: string,
  traceId?: string
): Promise<{ success: boolean; tempUri?: string; mimeType?: string; streaming?: boolean }> {
  ensureAndroid();
  return LocalDownloaderModule.preparePrivatePlayback({ id, traceId });
}

export async function setLocalSecureScreen(enabled: boolean): Promise<{ success: boolean }> {
  ensureAndroid();
  return LocalDownloaderModule.setSecureScreen({ enabled });
}

export async function clearLocalPrivatePlaybackCache(): Promise<void> {
  ensureAndroid();
  return LocalDownloaderModule.clearPrivatePlaybackCache();
}

export async function renameLocalPrivateVideo(id: string, title: string): Promise<LocalPrivateRenameResult> {
  ensureAndroid();
  return LocalDownloaderModule.renamePrivateVideo({ id, title });
}

export async function getLocalPrivateThumbnailUri(id: string): Promise<LocalPrivateThumbnailUriResult> {
  ensureAndroid();
  return LocalDownloaderModule.getPrivateThumbnailUri({ id });
}

export async function startLocalPrivateVaultMigration(): Promise<LocalPrivateMigrationStartResult> {
  ensureAndroid();
  return LocalDownloaderModule.startPrivateVaultMigration();
}

export async function cancelLocalPrivateVaultMigration(): Promise<LocalPrivateMigrationCancelResult> {
  ensureAndroid();
  return LocalDownloaderModule.cancelPrivateVaultMigration();
}

export async function getLocalPrivateVaultMigrationStatus(): Promise<LocalPrivateMigrationStatus> {
  ensureAndroid();
  return LocalDownloaderModule.getPrivateVaultMigrationStatus();
}

export async function getLocalVaultDiagnostics(): Promise<LocalVaultDiagnostics> {
  ensureAndroid();
  return LocalDownloaderModule.getVaultDiagnostics();
}

// ----- Tags -----

export async function listLocalVaultTags(): Promise<PrivateVaultTag[]> {
  ensureAndroid();
  return LocalDownloaderModule.listVaultTags();
}

export async function createLocalVaultTag(name: string, color?: string): Promise<PrivateVaultTag> {
  ensureAndroid();
  return LocalDownloaderModule.createVaultTag({ name, color });
}

export async function renameLocalVaultTag(id: string, name: string): Promise<PrivateVaultTag> {
  ensureAndroid();
  return LocalDownloaderModule.renameVaultTag({ id, name });
}

export async function setLocalVaultTagColor(id: string, color: string): Promise<PrivateVaultTag> {
  ensureAndroid();
  return LocalDownloaderModule.setVaultTagColor({ id, color });
}

export async function deleteLocalVaultTag(id: string): Promise<PrivateVaultTagDeleteResult> {
  ensureAndroid();
  return LocalDownloaderModule.deleteVaultTag({ id });
}

export async function setLocalVaultEntryTags(
  ids: string[],
  tagIds: string[],
): Promise<{ success: boolean; updatedCount?: number }> {
  ensureAndroid();
  return LocalDownloaderModule.setVaultEntryTags({ ids, tagIds });
}

// ----- Folders -----

export async function listLocalVaultFolders(): Promise<PrivateVaultFolder[]> {
  ensureAndroid();
  return LocalDownloaderModule.listVaultFolders();
}

export async function createLocalVaultFolder(name: string): Promise<PrivateVaultFolder> {
  ensureAndroid();
  return LocalDownloaderModule.createVaultFolder({ name });
}

export async function renameLocalVaultFolder(id: string, name: string): Promise<PrivateVaultFolder> {
  ensureAndroid();
  return LocalDownloaderModule.renameVaultFolder({ id, name });
}

export async function deleteLocalVaultFolder(id: string): Promise<PrivateVaultFolderDeleteResult> {
  ensureAndroid();
  return LocalDownloaderModule.deleteVaultFolder({ id });
}

export async function setLocalVaultEntryFolder(
  ids: string[],
  folderId: string | null,
): Promise<{ success: boolean; updatedCount?: number }> {
  ensureAndroid();
  return LocalDownloaderModule.setVaultEntryFolder({ ids, folderId });
}

export async function importLocalCookie(input: {
  platform: LocalPlatform;
  uri: string;
  profileName: string;
}): Promise<{ profileName: string; path: string }> {
  ensureAndroid();
  return LocalDownloaderModule.importCookie(input);
}

export async function listLocalCookieProfiles(platform: LocalPlatform): Promise<LocalCookieProfile[]> {
  ensureAndroid();
  return LocalDownloaderModule.listCookieProfiles(platform);
}

export async function setLocalCookieDefault(input: {
  platform: LocalPlatform;
  profileName: string;
}): Promise<{ success: boolean }> {
  ensureAndroid();
  return LocalDownloaderModule.setCookieDefault(input);
}

export async function getLocalCookieDefaults(): Promise<Record<LocalPlatform, string | null>> {
  ensureAndroid();
  return LocalDownloaderModule.getCookieDefaults();
}

export async function importLocalCustomCookie(input: LocalCustomCookieImportInput): Promise<LocalCustomCookieImportResult> {
  ensureAndroid();
  return LocalDownloaderModule.importCustomCookie(input);
}

export async function listLocalCustomDomains(): Promise<LocalCustomDomainSummary[]> {
  ensureAndroid();
  return LocalDownloaderModule.listCustomDomains();
}

export async function listLocalCustomDomainProfiles(domain: string): Promise<LocalCustomDomainProfile[]> {
  ensureAndroid();
  return LocalDownloaderModule.listCustomDomainProfiles(domain);
}

export async function setLocalCustomDomainDefault(input: { domain: string; profileName: string }): Promise<{ success: boolean }> {
  ensureAndroid();
  return LocalDownloaderModule.setCustomDomainDefault(input);
}

export async function deleteLocalCustomDomainProfile(input: { domain: string; profileName: string }): Promise<{ success: boolean }> {
  ensureAndroid();
  return LocalDownloaderModule.deleteCustomDomainProfile(input);
}

export async function getLocalDiagnostics(): Promise<LocalDiagnostics> {
  ensureAndroid();
  return LocalDownloaderModule.getDiagnostics();
}

export async function getLocalDownloadFailureLogs(): Promise<LocalDownloadFailureLog[]> {
  ensureAndroid();
  return LocalDownloaderModule.getDownloadFailureLogs();
}

export async function getLocalYtDlpUpdateStatus(): Promise<LocalYtDlpUpdateStatus> {
  ensureAndroid();
  return LocalDownloaderModule.getYtDlpUpdateStatus();
}

export async function checkLocalYtDlpUpdate(): Promise<LocalYtDlpUpdateCheckResult> {
  ensureAndroid();
  return LocalDownloaderModule.checkYtDlpUpdate();
}

/** @param version omit for the newest stable release; pass one to switch to it. */
export async function updateLocalYtDlp(version?: string): Promise<LocalYtDlpUpdateResult> {
  ensureAndroid();
  return LocalDownloaderModule.updateYtDlp(version);
}

/**
 * Relaunch the app.
 *
 * A downloaded yt-dlp only becomes active when Python next starts, and Python starts with
 * the process. Without this the only way to finish an update was to force-stop the app
 * from Android's own settings.
 *
 * Refused while a download is running, since the process is killed to do it.
 */
export async function restartLocalApp(): Promise<{
  restarted: boolean;
  reason?: string;
  activeTaskIds?: string[];
}> {
  return LocalDownloaderModule.restartApp();
}

/** Recent stable yt-dlp releases, newest first. Needs a connection. */
export async function listLocalYtDlpVersions(): Promise<string[]> {
  const result = await LocalDownloaderModule.listYtDlpVersions();
  return result.versions ?? [];
}

export async function clearLocalYtDlpOverride(): Promise<{ success: boolean; requiresRestart?: boolean }> {
  ensureAndroid();
  return LocalDownloaderModule.clearYtDlpOverride();
}

export async function runLocalImpersonationSelfTest(): Promise<LocalImpersonationSelfTestResult> {
  ensureAndroid();
  return LocalDownloaderModule.runImpersonationSelfTest();
}

export async function saveLocalFileToMediaStore(input: LocalSaveToMediaStoreInput): Promise<LocalSaveToMediaStoreResult> {
  ensureAndroid();
  return LocalDownloaderModule.saveToMediaStore(input);
}
