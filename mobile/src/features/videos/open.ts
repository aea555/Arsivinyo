import { useSyncExternalStore } from 'react';
import { Keyboard } from 'react-native';

import { skipPlayerTo, stopPlayer, type LocalVideo } from '@/src/api';
import type { MpvRepeat } from '@/src/native/localDownloader';

/**
 * What the app's video player plays: a list of the library, as it was shown when one of it
 * was tapped, and whether the player is the whole screen or the small one in the bar. Null
 * when nothing plays.
 *
 * The queue itself is the player's, in Kotlin: `index` here is only what it last said, and
 * `start` where it was asked to begin.
 */
export interface VideoSession {
  videos: LocalVideo[];
  start: number;
  index: number;
  expanded: boolean;
}

let session: VideoSession | null = null;
const listeners = new Set<() => void>();

function set(next: VideoSession | null) {
  session = next;
  listeners.forEach((listener) => listener());
}

export function useVideoSession(): VideoSession | null {
  return useSyncExternalStore(
    (listener) => {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
    () => session,
  );
}

/** Plays [videos] from the one at [index], large. A list already playing is only gone within. */
export function openVideo(videos: LocalVideo[], index: number) {
  if (index < 0 || index >= videos.length) return;
  // A keyboard left open by the list's search would stay over the player.
  Keyboard.dismiss();
  const same =
    session != null && session.videos.length === videos.length && session.videos.every((v, i) => v.id === videos[i].id);
  if (session && same) {
    if (index !== session.index) void skipPlayerTo(index);
    set({ ...session, videos, index, expanded: true });
    return;
  }
  set({ videos, start: index, index, expanded: true });
}

/** A queue found already playing, with no session here to match: shown small. */
export function adoptVideos(videos: LocalVideo[], index: number) {
  if (!session) set({ videos, start: index, index, expanded: false });
}

/** The player says another of the list plays. */
export function videoAt(index: number) {
  if (session && session.index !== index) set({ ...session, index });
}

export function expandVideo(expanded: boolean) {
  if (session && session.expanded !== expanded) set({ ...session, expanded });
}

/**
 * How far above the bottom of the screen the small player sits, beyond the screen's own
 * edge: the height of a bar a screen keeps there itself, which the player goes above. The
 * home screen's navigation, while that screen is the one shown.
 */
let lift = 0;

export function setVideoBarLift(height: number) {
  if (lift === height) return;
  lift = height;
  listeners.forEach((listener) => listener());
}

export function useVideoBarLift(): number {
  return useSyncExternalStore(
    (listener) => {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
    () => lift,
  );
}

/** Nothing plays from here; the place in what played is kept. */
export function closeVideo() {
  void stopPlayer();
  set(null);
}

/** The player stopped by other means: the app swiped away, say. */
export function videoStopped() {
  if (session) set(null);
}

/**
 * What the library's player does at the end of a video or of the queue. Kept here, not in
 * the player: the player is not on screen under another player, and comes back with it.
 */
let repeat: MpvRepeat = 'off';

export function setVideoRepeat(next: MpvRepeat) {
  if (repeat === next) return;
  repeat = next;
  listeners.forEach((listener) => listener());
}

export function useVideoRepeat(): MpvRepeat {
  return useSyncExternalStore(
    (listener) => {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
    () => repeat,
  );
}
