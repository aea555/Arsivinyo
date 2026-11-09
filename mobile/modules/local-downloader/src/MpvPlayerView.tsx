import { requireNativeView } from 'expo';
import React, { forwardRef } from 'react';
import type { NativeSyntheticEvent, ViewProps } from 'react-native';

/** What is playing and where it is. */
export interface MpvProgress {
  positionMs: number;
  durationMs: number;
  paused: boolean;
  buffering: boolean;
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
  /** Into the small window, for a player with `background`. */
  enterPictureInPicture(): Promise<void>;
}

export interface MpvPlayerViewProps extends ViewProps {
  source: MpvSource | null;
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
   * Has the player write the position to the library: as it plays, when it pauses, at the
   * end and on the way out. The screen cannot: its timers stop when the app is out of sight.
   */
  record?: MpvRecord | null;
  /** Subtitles' size against the default (1), and whether they are bold. */
  subtitleScale?: number;
  subtitleBold?: boolean;
  onProgress?: (event: NativeSyntheticEvent<MpvProgress>) => void;
  onTracks?: (event: NativeSyntheticEvent<{ tracks: MpvTrack[] }>) => void;
  onEnded?: (event: NativeSyntheticEvent<object>) => void;
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
