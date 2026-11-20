import type { EventSubscription } from 'expo-modules-core';

import LocalDownloaderModule, { addPlayerChangedListener, type LocalPlayerState } from '../native/localDownloader';

export type { LocalPlayerState };

/**
 * The app's one player of its own library, songs and videos, as it is driven with no player
 * view on screen: from the bar's buttons, and from the music screens. What it plays is set
 * through the player view's `queue` (`src/features/player/session.ts`).
 */
export function getPlayerState(): Promise<LocalPlayerState> {
  return LocalDownloaderModule.playerState();
}

/** Another item, paused or playing, stopped, the repeat changed. Not the position moving on. */
export function onPlayerChanged(listener: (state: LocalPlayerState) => void): EventSubscription {
  return addPlayerChangedListener(listener);
}

/** The key of the queue that plays now, or null when nothing does. Answered at once. */
export function playingQueueKey(): string | null {
  try {
    return LocalDownloaderModule.playerKey();
  } catch {
    return null;
  }
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

/** To the next of the queue; from its last to its first, where the queue repeats. */
export function skipPlayerNext(): Promise<void> {
  return LocalDownloaderModule.playerNext();
}

/** A little way into an item, to its beginning; at its beginning, to the one before it. */
export function skipPlayerPrevious(): Promise<void> {
  return LocalDownloaderModule.playerPrevious();
}

export function setPlayerRepeat(repeat: 'off' | 'one' | 'all'): Promise<void> {
  return LocalDownloaderModule.playerSetRepeat(repeat);
}

/** Nothing plays from here; the place in a video that played is kept. */
export function stopPlayer(): Promise<void> {
  return LocalDownloaderModule.playerStop();
}
