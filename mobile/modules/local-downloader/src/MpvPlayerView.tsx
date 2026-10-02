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
}

export interface MpvPlayerViewProps extends ViewProps {
  source: MpvSource | null;
  /** Subtitle and audio languages, most preferred first, as ISO 639-2 codes. */
  languages: string[];
  onProgress?: (event: NativeSyntheticEvent<MpvProgress>) => void;
  onTracks?: (event: NativeSyntheticEvent<{ tracks: MpvTrack[] }>) => void;
  onEnded?: (event: NativeSyntheticEvent<object>) => void;
  onFailed?: (event: NativeSyntheticEvent<{ code: string }>) => void;
}

const NativeView = requireNativeView<MpvPlayerViewProps & { ref?: React.Ref<MpvPlayerHandle> }>('LocalDownloader');

/**
 * libmpv in a native view (`shared/watch/CONTRACT.md`, "The player"). Fullscreen in landscape
 * while it is on screen; the controls are the screen's own.
 */
export const MpvPlayerView = forwardRef<MpvPlayerHandle, MpvPlayerViewProps>(function MpvPlayerView(props, ref) {
  return <NativeView ref={ref} {...props} />;
});
