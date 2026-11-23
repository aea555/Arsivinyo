import { requireNativeView } from 'expo';
import React, { forwardRef } from 'react';
import type { NativeSyntheticEvent, ViewProps } from 'react-native';

/** What is playing and where it is. */
export interface MpvProgress {
  positionMs: number;
  durationMs: number;
  paused: boolean;
  buffering: boolean;
  /** As the player has it: it can be changed from Android's own media control. */
  repeat?: MpvRepeat;
}

/** An audio or subtitle track, from the file or added from an add-on. */
export interface MpvTrack {
  kind: 'audio' | 'subtitle';
  id: string;
  title?: string | null;
  lang?: string | null;
  codec?: string | null;
  external: boolean;
  selected: boolean;
}

export interface MpvSource {
  /** A stream's address, or a video of the phone's own (`content://`). */
  url: string;
  /** The audio as a separate file, played with the video. */
  audioUrl?: string | null;
  headers?: Record<string, string>;
  startMs?: number;
}

/** What a video is kept as in the library, so the player can write down where it is. */
export interface MpvRecord {
  title: { id: string; type: string; name: string; poster?: string | null };
  videoId: string;
  addonKey: string | null;
  bingeGroup: string | null;
}

/** One thing of the app's own library in a queue: a video or a song. */
export interface MpvQueueItem {
  kind: 'video' | 'audio';
  id: string;
  uri: string;
  title: string;
  /** Who posted the video, or the song's artist. */
  subtitle?: string | null;
  /** A picture of it as a file path, for the notification and the lock screen: a song's cover. */
  artwork?: string | null;
}

/**
 * Videos and songs of the app's own library to play one after another, from the one at
 * `index`. The player keeps the queue itself: a video resumes where it stopped and has its
 * place written down, and each item is followed by the next, also while the app is out of
 * sight. `key` names the queue: the same key with more items is the same queue, grown, and
 * what plays goes on; another key begins anew.
 */
export interface MpvQueue {
  key: string;
  items: MpvQueueItem[];
  index: number;
}

/** The screen follows the phone, or is kept one way. */
export type MpvOrientation = 'auto' | 'landscape' | 'portrait';

/** At the end: stop, play this video again, or go round the queue. */
export type MpvRepeat = 'off' | 'one' | 'all';

/** What the screen can tell the player to do. */
export interface MpvPlayerHandle {
  setPaused(paused: boolean): Promise<void>;
  seek(ms: number): Promise<void>;
  seekBy(ms: number): Promise<void>;
  /** A track's id, or 'no' to turn subtitles off. */
  setTrack(kind: 'audio' | 'subtitle', id: string): Promise<void>;
  /** Added and, when [select], shown at once. */
  addSubtitle(url: string, title: string, lang: string, select: boolean): Promise<void>;
  setSubtitleDelay(ms: number): Promise<void>;
  setSpeed(speed: number): Promise<void>;
  /** To another item of the `queue`. */
  skipTo(index: number): Promise<void>;
  /** Into the small window, for a player with `background`. */
  enterPictureInPicture(): Promise<void>;
}

export interface MpvPlayerViewProps extends ViewProps {
  /** One video, in a player of its own. Null where a `queue` plays. */
  source: MpvSource | null;
  queue?: MpvQueue | null;
  /** Subtitle and audio languages, most preferred first, as ISO 639-2 codes. */
  languages: string[];
  /**
   * Plays on out of sight (the screen off, another app in front) and in picture-in-picture,
   * with a notification to pause and seek from. Without it, out of sight is paused.
   */
  background?: boolean;
  /** What that notification calls the video. */
  title?: string;
  /**
   * How the screen is held while the player is the whole of it: turned with the phone
   * (where Android's auto-rotate is on), or kept in landscape or upright.
   */
  orientation?: MpvOrientation;
  repeat?: MpvRepeat;
  /**
   * Small, in the app's bar: the picture only. The screen is not turned or made fullscreen
   * for it, and there is no picture-in-picture from it.
   */
  mini?: boolean;
  /**
   * Has the player write the position to the library: as it plays, when it pauses, at the
   * end and on the way out. The screen cannot: its timers stop when the app is out of sight.
   */
  record?: MpvRecord | null;
  /** Subtitles' size against the default (1), and whether they are bold. */
  subtitleScale?: number;
  subtitleBold?: boolean;
  onProgress?: (event: NativeSyntheticEvent<MpvProgress>) => void;
  onTracks?: (event: NativeSyntheticEvent<{ tracks: MpvTrack[] }>) => void;
  /** The last video ended: of the queue, where there is one. */
  onEnded?: (event: NativeSyntheticEvent<object>) => void;
  /** Another item of the queue is the one playing. */
  onItem?: (event: NativeSyntheticEvent<{ index: number }>) => void;
  onFailed?: (event: NativeSyntheticEvent<{ code: string }>) => void;
}

const NativeView = requireNativeView<MpvPlayerViewProps & { ref?: React.Ref<MpvPlayerHandle> }>('LocalDownloader');

/**
 * libmpv in a native view (`shared/watch/CONTRACT.md`, "The player"). Fullscreen in landscape
 * while it is on screen; the controls are the screen's own. In picture-in-picture the picture
 * is alone in the window, the controls put aside by the view itself.
 */
export const MpvPlayerView = forwardRef<MpvPlayerHandle, MpvPlayerViewProps>(function MpvPlayerView(props, ref) {
  return <NativeView ref={ref} {...props} />;
});
