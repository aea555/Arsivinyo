import type { Href, Router } from 'expo-router';

import type { LocalVideo } from '@/src/api';

/** What the player screen plays: a list, as the library showed it, and where in it to begin. */
export interface VideoQueue {
  videos: LocalVideo[];
  index: number;
}

/** Handed over in memory, as the Watch player's request is. */
let pending: VideoQueue | null = null;

export function openVideo(router: Router, videos: LocalVideo[], index: number) {
  pending = { videos, index };
  router.push('/video-player' as Href);
}

export function takeVideoQueue(): VideoQueue | null {
  const queue = pending;
  pending = null;
  return queue;
}
