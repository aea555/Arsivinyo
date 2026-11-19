import type { EventSubscription } from 'expo-modules-core';
import { Platform } from 'react-native';

import LocalDownloaderModule, { addVideosChangedListener, type LocalVideo } from '../native/localDownloader';

export type { LocalVideo };

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

/** Where the video was stopped, in milliseconds, as of now: the list's copy can be older. */
export function getLocalVideoPlace(id: string): Promise<number> {
  return LocalDownloaderModule.getVideoPlace(id);
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
