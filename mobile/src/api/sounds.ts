import type { EventSubscription } from 'expo-modules-core';
import { Platform } from 'react-native';
import LocalDownloaderModule, {
  addSoundPresetProgressListener,
  addSoundsChangedListener,
  type LocalAudioFormat,
  type LocalAudioFormatState,
  type LocalAudioPresetDiagnostics,
  type LocalSoundPresetProgressEvent,
  type LocalSoundPresetStartResult,
  type LocalSound,
  type LocalSoundPlaylist,
  type LocalSoundsImportResult,
  type LocalSoundsLibrary,
} from '../native/localDownloader';

function ensureAndroid(): void {
  if (Platform.OS !== 'android') {
    throw new Error('The music library is Android-only in this release.');
  }
}

/**
 * Whether the on-device music library is usable. Requires scoped storage
 * (Android 10 / API 29+), since tracks live in the public Music/Arsivinyo folder
 * and are accessed via the MediaStore owner model (no broad storage permission).
 */
export function isLocalSoundsSupported(): boolean {
  if (Platform.OS !== 'android') return false;
  try {
    return LocalDownloaderModule.isSoundsSupported();
  } catch {
    return false;
  }
}

export async function listLocalSounds(): Promise<LocalSoundsLibrary> {
  ensureAndroid();
  return LocalDownloaderModule.listSounds();
}

/** Opens a multi-select audio picker (SAF) and imports the chosen files. */
export async function importLocalSounds(): Promise<LocalSoundsImportResult> {
  ensureAndroid();
  if (__DEV__) console.log('[sounds-import] → native importSounds()');
  try {
    const result = await LocalDownloaderModule.importSounds();
    if (__DEV__) {
      console.log('[sounds-import] ← native result', {
        success: result.success,
        importedCount: result.importedCount,
        failedCount: result.failedCount,
        songs: result.songs?.length,
        code: result.code,
        message: result.message,
        failures: result.failures,
      });
    }
    return result;
  } catch (e) {
    if (__DEV__) console.warn('[sounds-import] native importSounds() threw', e);
    throw e;
  }
}

export async function deleteLocalSounds(ids: string[]): Promise<{ deletedCount: number }> {
  ensureAndroid();
  return LocalDownloaderModule.deleteSounds({ ids });
}

export async function renameLocalSound(id: string, title: string): Promise<LocalSound> {
  ensureAndroid();
  return LocalDownloaderModule.renameSound({ id, title });
}

export async function getLocalSoundThumbnail(id: string): Promise<string | null> {
  ensureAndroid();
  const result = await LocalDownloaderModule.getSoundThumbnail({ id });
  return result.path ?? null;
}

export async function listLocalSoundPlaylists(): Promise<LocalSoundPlaylist[]> {
  ensureAndroid();
  return LocalDownloaderModule.listSoundPlaylists();
}

export async function createLocalSoundPlaylist(name: string): Promise<LocalSoundPlaylist> {
  ensureAndroid();
  return LocalDownloaderModule.createSoundPlaylist({ name });
}

export async function renameLocalSoundPlaylist(id: string, name: string): Promise<LocalSoundPlaylist> {
  ensureAndroid();
  return LocalDownloaderModule.renameSoundPlaylist({ id, name });
}

export async function deleteLocalSoundPlaylist(id: string): Promise<{ success: boolean }> {
  ensureAndroid();
  return LocalDownloaderModule.deleteSoundPlaylist({ id });
}

/** Replace a playlist's ordered membership (used for in-playlist reordering). */
export async function setLocalSoundPlaylistSongs(id: string, songIds: string[]): Promise<LocalSoundPlaylist> {
  ensureAndroid();
  return LocalDownloaderModule.setSoundPlaylistSongs({ id, songIds });
}

/** Add songs to one or more playlists (union-merge, preserves existing order). */
export async function addLocalSoundsToPlaylists(
  songIds: string[],
  playlistIds: string[]
): Promise<{ success: boolean }> {
  ensureAndroid();
  return LocalDownloaderModule.addSoundsToPlaylists({ songIds, playlistIds });
}

export async function removeLocalSoundsFromPlaylist(
  playlistId: string,
  songIds: string[]
): Promise<LocalSoundPlaylist> {
  ensureAndroid();
  return LocalDownloaderModule.removeSoundsFromPlaylist({ playlistId, songIds });
}

/** Add or remove songs from the special, non-deletable Favorites playlist. */
export async function setLocalSoundsFavorite(
  songIds: string[],
  favorite: boolean
): Promise<LocalSoundPlaylist> {
  ensureAndroid();
  return LocalDownloaderModule.setSoundsFavorite({ songIds, favorite });
}

/** Reserved id of the Favorites playlist (mirrors the native constant). */
export const FAVORITES_PLAYLIST_ID = 'favorites';

/**
 * Container audio downloads are encoded into.
 *
 * FLAC is the default. Every source we download is already lossy, so encoding it to
 * AAC again would stack a second generation of loss for no benefit; FLAC keeps exactly
 * what the decoder produced, at roughly 3x the file size. `m4a` trades that back for
 * space. Existing tracks are not affected — this only applies to new downloads.
 */
export async function getLocalAudioFormat(): Promise<LocalAudioFormatState> {
  ensureAndroid();
  return LocalDownloaderModule.getAudioFormat();
}

export async function setLocalAudioFormat(format: LocalAudioFormat): Promise<LocalAudioFormatState> {
  ensureAndroid();
  return LocalDownloaderModule.setAudioFormat({ format });
}

/** Whether a track is stored without further loss. Mirrors `LocalSound.lossless`. */
export function isLosslessSound(sound: Pick<LocalSound, 'lossless'>): boolean {
  return sound.lossless === true;
}

/** Whether a track was produced by applying a preset to another track. */
export function isRenderedSound(sound: Pick<LocalSound, 'presetId'>): boolean {
  return typeof sound.presetId === 'string' && sound.presetId.length > 0;
}

/**
 * Apply a preset to one or more tracks.
 *
 * Each render creates a NEW library entry and leaves the source untouched, so applying
 * a preset is always undoable by deleting the result. Returns as soon as the batch is
 * queued; subscribe with {@link listenLocalSoundPresetProgress} for per-track outcomes.
 *
 * `paramsSpec` is the flat `key=value;` form the native DSP reads — preset identity
 * never crosses the bridge, which is why user-defined presets need no native support.
 */
export async function applyLocalSoundPresets(input: {
  songIds: string[];
  presetId: string;
  paramsSpec: string;
  titleSuffix: string;
}): Promise<LocalSoundPresetStartResult> {
  ensureAndroid();
  return LocalDownloaderModule.applySoundPresets(input);
}

export async function cancelLocalSoundPresetRender(renderId: string): Promise<{ success: boolean }> {
  ensureAndroid();
  return LocalDownloaderModule.cancelSoundPresetRender({ renderId });
}

export function listenLocalSoundPresetProgress(
  listener: (event: LocalSoundPresetProgressEvent) => void
): EventSubscription {
  return addSoundPresetProgressListener(listener);
}

/** The library changed from another device: a track arrived, or joined a playlist. */
export function listenLocalSoundsChanged(listener: () => void): EventSubscription {
  return addSoundsChangedListener(listener);
}

/** Native-renderer availability and resolved tool paths, for the diagnostics screen. */
export async function getLocalAudioPresetDiagnostics(): Promise<LocalAudioPresetDiagnostics> {
  ensureAndroid();
  return LocalDownloaderModule.getAudioPresetDiagnostics();
}
