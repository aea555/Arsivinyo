import type { Href, useRouter } from 'expo-router';

import type { WatchStream, WatchSubtitle, WatchTitle } from '@/src/api';

type Router = ReturnType<typeof useRouter>;

/** Opens a title's page with what is already known, so it shows at once. */
export function openTitle(router: Router, title: WatchTitle, play?: { videoId: string; bingeGroup?: string | null; addonKey?: string | null }) {
  router.push({
    pathname: '/watch-title',
    params: {
      id: title.id,
      type: title.type,
      name: title.name,
      poster: title.poster ?? '',
      ...(play ? { play: play.videoId, bingeGroup: play.bingeGroup ?? '', addonKey: play.addonKey ?? '' } : {}),
    },
  } as unknown as Href);
}

/** What the player needs: where to play from, and what to remember it as. */
export interface PlayRequest {
  url: string;
  audioUrl?: string | null;
  headers: Record<string, string>;
  title: WatchTitle;
  videoId: string;
  videoName: string;
  addonKey: string | null;
  bingeGroup: string | null;
  startMs: number;
  /** The video after this one, for a series. */
  nextVideoId: string | null;
  /** False for a trailer: watching it is not watching the title. */
  record: boolean;
  /** The stream's own subtitles, and its file name, which subtitle add-ons match on. */
  subtitles?: WatchSubtitle[];
  filename?: string | null;
  /** A torrent stream's info hash, for its peers and speed while the player waits. */
  torrentId?: string | null;
}

/** Hands a request to the player screen. Kept in memory: a stream URL never goes in a route. */
let pending: PlayRequest | null = null;

export function openPlayer(router: Router, request: PlayRequest) {
  pending = request;
  router.push('/watch-player' as Href);
}

export function takePlayRequest(): PlayRequest | null {
  const request = pending;
  pending = null;
  return request;
}

/** A stream's lines for a list: the add-on's label, then what the stream says it is. */
export function streamLines(stream: WatchStream): [string, string] {
  return [stream.label || stream.kind, stream.detail];
}
