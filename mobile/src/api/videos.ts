import type { EventSubscription } from 'expo-modules-core';
import { Platform } from 'react-native';

import LocalDownloaderModule, {
  addPlayerChangedListener,
  addVideosChangedListener,
  type LocalPlayerState,
  type LocalVideo,
} from '../native/localDownloader';

export type { LocalPlayerState, LocalVideo };

/**
 * Whether the video library is usable: Android 10 or newer, as the music library, since the
 * videos are found through MediaStore's owner model with no storage permission.
 */
export function isLocalVideosSupported(): boolean {
  if (Platform.OS !== 'android') return false;
  try {
    return LocalDownloaderModule.isVideosSupported();
  } catch {
    return false;
  }
}

/** Every video the app saved, newest first. */
export function listLocalVideos(): Promise<LocalVideo[]> {
  return LocalDownloaderModule.listVideos();
}

/** A picture of the video, as a file path. Made the first time it is asked for. */
export function getLocalVideoThumbnail(id: string): Promise<string | null> {
  return LocalDownloaderModule.getVideoThumbnail(id);
}

export function renameLocalVideo(id: string, title: string): Promise<LocalVideo> {
  return LocalDownloaderModule.renameVideo(id, title);
}

/** Deletes the files themselves, from the gallery too. Resolves to how many went. */
export function deleteLocalVideos(ids: string[]): Promise<number> {
  return LocalDownloaderModule.deleteVideos(ids);
}

export function onLocalVideosChanged(listener: () => void): EventSubscription {
  return addVideosChangedListener(listener);
}

/**
 * What plays with no player on screen. A video of the library plays on as sound when its
 * player is closed, and these are how the app's small player sees and drives it.
 */
export function getPlayerState(): Promise<LocalPlayerState> {
  return LocalDownloaderModule.playerState();
}

export function onPlayerChanged(listener: (state: LocalPlayerState) => void): EventSubscription {
  return addPlayerChangedListener(listener);
}

export function setPlayerPaused(paused: boolean): Promise<void> {
  return LocalDownloaderModule.playerSetPaused(paused);
}

export function seekPlayer(ms: number): Promise<void> {
  return LocalDownloaderModule.playerSeek(ms);
}

/** That far on from where it is, or back for a negative number. */
export function seekPlayerBy(ms: number): Promise<void> {
  return LocalDownloaderModule.playerSeekBy(ms);
}

export function skipPlayerTo(index: number): Promise<void> {
  return LocalDownloaderModule.playerSkipTo(index);
}

/** Nothing plays from here; the place in what played is kept. */
export function stopPlayer(): Promise<void> {
  return LocalDownloaderModule.playerStop();
}
