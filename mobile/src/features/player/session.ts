import { useSyncExternalStore } from 'react';
import { Keyboard } from 'react-native';

import { playingQueueKey, seekPlayer, setPlayerPaused, setPlayerRepeat, skipPlayerTo, stopPlayer, type LocalSound, type LocalVideo } from '@/src/api';
import type { MpvQueueItem, MpvRepeat } from '@/src/native/localDownloader';

/** One thing in the queue, as the screens need it: a video also says whether it is upright. */
export interface MediaItem extends MpvQueueItem {
  /** A video taller than it is wide. */
  tall?: boolean;
}

/**
 * What the app's player plays: a list of the library, songs or videos, as it was shown when
 * one of it was tapped; and, for a video, whether the player is the whole screen or the
 * small one in the bar. Null when nothing plays.
 *
 * The queue itself is the player's, in Kotlin: `index` here is only what it last said, and
 * `start` where it was asked to begin. `key` names the queue to it; `context` is the
 * caller's own word for where the list came from (a playlist's id, say).
 */
export interface MediaSession {
  key: string;
  context: string | null;
  items: MediaItem[];
  start: number;
  index: number;
  expanded: boolean;
}

let session: MediaSession | null = null;
let made = 0;
const listeners = new Set<() => void>();

function subscribe(listener: () => void) {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

function set(next: MediaSession | null) {
  session = next;
  listeners.forEach((listener) => listener());
}

export function useMediaSession(): MediaSession | null {
  return useSyncExternalStore(subscribe, () => session);
}

export function mediaSession(): MediaSession | null {
  return session;
}

export function videoItem(video: LocalVideo): MediaItem {
  return {
    kind: 'video',
    id: video.id,
    uri: video.uri,
    title: video.title,
    subtitle: video.uploader ?? null,
    tall: video.height > video.width,
  };
}

export function soundItem(song: LocalSound): MediaItem {
  return {
    kind: 'audio',
    id: song.id,
    uri: song.contentUri,
    title: song.title,
    subtitle: song.artist ?? null,
    artwork: song.thumbnailPath ?? null,
  };
}

const same = (a: MediaItem[], b: MediaItem[]) => a.length === b.length && a.every((item, i) => item.id === b[i].id && item.kind === b[i].kind);

/**
 * Plays [items] from the one at [index]. A list already playing is only gone within, and
 * plays if it was paused. [expanded]: the player as the whole screen, for a video.
 * [restart]: asked for the item that already plays, it begins again.
 */
export function openMedia(
  items: MediaItem[],
  index: number,
  options: { expanded: boolean; context?: string | null; restart?: boolean },
) {
  if (index < 0 || index >= items.length) return;
  // A keyboard left open by a list's search would stay over the player.
  Keyboard.dismiss();
  const context = options.context ?? null;
  if (session && session.context === context && same(session.items, items)) {
    if (index !== session.index) void skipPlayerTo(index);
    else {
      // The one that plays, asked for again: a song starts over, a video only comes forward.
      if (options.restart) void seekPlayer(0);
      void setPlayerPaused(false);
    }
    set({ ...session, items, index, expanded: options.expanded });
    return;
  }
  made += 1;
  set({ key: `q${made}`, context, items, start: index, index, expanded: options.expanded });
}

export function openVideos(videos: LocalVideo[], index: number) {
  openMedia(videos.map(videoItem), index, { expanded: true, context: 'videos' });
}

/** More of the same list, at its end: the queue grows and what plays goes on. */
export function appendMedia(items: MediaItem[]) {
  if (!session || items.length === 0) return;
  const have = new Set(session.items.map((item) => `${item.kind}:${item.id}`));
  const more = items.filter((item) => !have.has(`${item.kind}:${item.id}`));
  if (more.length > 0) set({ ...session, items: [...session.items, ...more] });
}

/** The player says another of the list plays. */
export function mediaAt(index: number) {
  if (session && session.index !== index && index >= 0 && index < session.items.length) set({ ...session, index });
}

export function expandMedia(expanded: boolean) {
  if (session && session.expanded !== expanded) set({ ...session, expanded });
}

/** Nothing plays from here; the place in a video that played is kept. */
export function closeMedia() {
  void stopPlayer();
  set(null);
}

/**
 * Called once as the app's screens are made. A list kept from before that the player is not
 * playing is forgotten: the app was swiped away, which stops the player, but what is kept
 * here can outlive that, and the player's view, made again with it, would start it playing
 * the moment the app was opened. Only a tap starts anything. A list the player does still
 * play (the screens alone were made again) is kept, and goes on.
 */
export function reconcileMediaSession() {
  if (session && playingQueueKey() !== session.key) session = null;
}

/** The player stopped by other means: the app swiped away, say. */
export function mediaStopped() {
  if (session) set(null);
}

/**
 * How far above the bottom of the screen the small player sits, beyond the screen's own
 * edge: the height of a bar a screen keeps there itself, which the player goes above. The
 * home screen's navigation, while that screen is the one shown.
 */
let lift = 0;

export function setMediaBarLift(height: number) {
  if (lift === height) return;
  lift = height;
  listeners.forEach((listener) => listener());
}

export function useMediaBarLift(): number {
  return useSyncExternalStore(subscribe, () => lift);
}

/**
 * What the player does at the end of an item or of the queue. Kept here, not in the player
 * view: the view is not on screen under another player, and comes back with it. The player
 * itself is told too, for when there is no view to tell it.
 */
let repeat: MpvRepeat = 'off';

export function setMediaRepeat(next: MpvRepeat) {
  if (repeat === next) return;
  repeat = next;
  void setPlayerRepeat(next);
  listeners.forEach((listener) => listener());
}

/** The player said so itself: changed from Android's own media control. */
export function mediaRepeatIs(next: MpvRepeat) {
  if (repeat === next) return;
  repeat = next;
  listeners.forEach((listener) => listener());
}

export function useMediaRepeat(): MpvRepeat {
  return useSyncExternalStore(subscribe, () => repeat);
}

/**
 * Where what plays is, a few times a second while a player view is on screen. Kept apart
 * from the session: only what draws the position is drawn again for it.
 */
export interface MediaPlace {
  positionMs: number;
  durationMs: number;
  paused: boolean;
  buffering: boolean;
}

let place: MediaPlace = { positionMs: 0, durationMs: 0, paused: false, buffering: false };
const watchers = new Set<() => void>();

export function mediaPlaceIs(next: MediaPlace) {
  place = next;
  watchers.forEach((watcher) => watcher());
}

export function mediaPlace(): MediaPlace {
  return place;
}

export function useMediaPlace(): MediaPlace {
  return useSyncExternalStore(
    (watcher) => {
      watchers.add(watcher);
      return () => watchers.delete(watcher);
    },
    () => place,
  );
}
