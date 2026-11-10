import type { Href, useRouter } from 'expo-router';

import { getLibraryItem, getStreams, prepareStream, type WatchStream, type WatchSubtitle, type WatchTitle } from '@/src/api';

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
  /** A series' episodes in order, specials left out, each by the name the player shows. */
  episodes?: { id: string; name: string }[];
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

/** The episodes on either side of the one [request] plays, for a series the player knows. */
export function neighbours(request: PlayRequest): { previous: string | null; next: string | null } {
  const episodes = request.episodes ?? [];
  const at = episodes.findIndex((e) => e.id === request.videoId);
  if (at < 0) return { previous: null, next: request.nextVideoId };
  return { previous: episodes[at - 1]?.id ?? null, next: episodes[at + 1]?.id ?? null };
}

/**
 * Another episode of [request]'s series, ready to play in the same player: from the same
 * add-on, its stream of the same binge group, else its first, as the title page picks one.
 * Null when there is none to take without asking (no such stream, one that does not open
 * here, a torrent after something that was not one): the title page then shows what there is.
 */
export async function episodeRequest(request: PlayRequest, videoId: string): Promise<PlayRequest | null> {
  const episodes = request.episodes ?? [];
  const at = episodes.findIndex((e) => e.id === videoId);
  if (at < 0 || !request.addonKey) return null;
  const video = episodes[at];
  const found = await getStreams(request.addonKey, request.title.type, video.id);
  if (!found.success) return null;
  const stream = found.streams.find((s) => request.bingeGroup && s.bingeGroup === request.bingeGroup) ?? found.streams[0];
  if (!stream || stream.kind === 'external' || (stream.kind === 'torrent' && !request.torrentId)) return null;
  const prepared = await prepareStream(stream);
  if (!prepared.success) return null;
  // Where it stopped, if it was started and not finished: going to another episode and back
  // carries on.
  const item = await getLibraryItem(request.title.id).catch(() => null);
  return {
    url: prepared.url,
    audioUrl: prepared.audioUrl ?? null,
    headers: prepared.headers,
    title: request.title,
    videoId: video.id,
    videoName: video.name,
    addonKey: request.addonKey,
    bingeGroup: stream.bingeGroup ?? null,
    startMs: item?.places?.[video.id]?.positionMs ?? 0,
    nextVideoId: episodes[at + 1]?.id ?? null,
    episodes,
    record: true,
    subtitles: stream.subtitles ?? [],
    filename: stream.filename ?? null,
    torrentId: prepared.torrentId ?? null,
  };
}

/** A stream's lines for a list: the add-on's label, then what the stream says it is. */
export function streamLines(stream: WatchStream): [string, string] {
  return [stream.label || stream.kind, stream.detail];
}
