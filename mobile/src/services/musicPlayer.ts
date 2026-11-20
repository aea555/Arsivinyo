import { useEffect, useState } from 'react';

import { getPlayerState, onPlayerChanged, type LocalPlayerState, type LocalSound, type LocalSoundPlaylist } from '../api';
import { appendMedia, mediaSession, openMedia, soundItem, useMediaSession } from '../features/player/session';

/**
 * Music on the app's one player (`MpvEngine`, through `features/player/session`): the same
 * player and the same queue as the library's videos, so a song has the bar, the lock
 * screen's controls and the notification that a video has, and the two cannot sound at once.
 */

const CONTEXT = 'music:';

/**
 * Play `songs` from `startIndex`. `contextId` records where the queue came from ('all' for
 * the Songs view, or a playlist id) so later imports/downloads can be appended to the right
 * queue. The list already playing is only gone within.
 */
export function playSongs(songs: LocalSound[], startIndex: number, contextId = 'all'): void {
  if (songs.length === 0) return;
  const index = Math.max(0, Math.min(startIndex, songs.length - 1));
  openMedia(songs.map(soundItem), index, { expanded: false, context: CONTEXT + contextId, restart: true });
}

// Library song ids seen on the previous reconcile. Module-level so it survives the
// library screen unmounting/remounting (so a song downloaded while the screen was
// away is still recognized as new on return).
let knownSongIds: Set<string> | null = null;

/**
 * Called after the library reloads. Appends songs added since the last reconcile (an
 * import or download) to the queue that plays, so the player's next can reach them: the
 * queue is otherwise the list as it was when playback started. Only songs belonging to the
 * queue's context are added: in 'all' any new song qualifies; in a playlist only ones now
 * in that playlist. The first call just records the baseline (nothing is "new" yet).
 */
export function reconcileQueueAfterReload(allSongs: LocalSound[], allPlaylists: LocalSoundPlaylist[]): void {
  const firstRun = knownSongIds == null;
  const newSongs = firstRun ? [] : allSongs.filter((s) => !knownSongIds!.has(s.id));
  knownSongIds = new Set(allSongs.map((s) => s.id));
  const context = mediaSession()?.context;
  if (newSongs.length === 0 || !context?.startsWith(CONTEXT)) return;
  let candidates = newSongs;
  const from = context.slice(CONTEXT.length);
  if (from !== 'all') {
    const playlist = allPlaylists.find((p) => p.id === from);
    if (!playlist) return;
    const inPlaylist = new Set(playlist.songIds);
    candidates = candidates.filter((s) => inPlaylist.has(s.id));
  }
  appendMedia(candidates.map(soundItem));
}

/** The song that plays, if what plays is a song. */
export interface ActiveSound {
  id: string;
  title: string;
  artist: string | null;
  /** Its cover, as a file path. */
  artwork: string | null;
}

export function useActiveSound(): ActiveSound | null {
  const session = useMediaSession();
  const item = session ? session.items[session.index] : null;
  if (!item || item.kind !== 'audio') return null;
  return { id: item.id, title: item.title, artist: item.subtitle ?? null, artwork: item.artwork ?? null };
}

/**
 * What the player is doing, asked of it every `everyMs` and whenever it says it changed.
 * `null` asks only on changes: for a screen that cannot be seen, where a timer would run
 * for nobody (and, behind the app, does not run at all).
 */
export function usePlayerClock(everyMs: number | null): LocalPlayerState {
  const [state, setState] = useState<LocalPlayerState>({ active: false });
  useEffect(() => {
    let live = true;
    const ask = () =>
      void getPlayerState()
        .then((next) => live && setState(next))
        .catch(() => undefined);
    ask();
    const subscription = onPlayerChanged((next) => live && setState(next));
    const timer = everyMs != null ? setInterval(ask, everyMs) : null;
    return () => {
      live = false;
      subscription.remove();
      if (timer) clearInterval(timer);
    };
  }, [everyMs]);
  return state;
}
