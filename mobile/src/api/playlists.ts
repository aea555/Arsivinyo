import LocalDownloaderModule, { type LocalPlaylist } from '../native/localDownloader';

export type { LocalPlaylist };

/**
 * Whether an address is a playlist's or an album's own, and not one video's: only such a
 * one is downloaded whole. A video that happens to be opened from a playlist
 * (`watch?v=…&list=…`) is that video. A channel is left out too: it can hold thousands.
 * The same rule as Kotlin's `isPlaylistUrl`, for links that come by the notification: keep
 * the two alike.
 */
export function isPlaylistUrl(raw: string): boolean {
  let url: URL;
  try {
    url = new URL(raw);
  } catch {
    return false;
  }
  const host = url.hostname.replace(/^www\.|^m\./, '').toLowerCase();
  const path = url.pathname;
  if (host === 'youtube.com' || host === 'music.youtube.com') {
    if (url.searchParams.has('v')) return false;
    if (path.startsWith('/playlist')) return url.searchParams.has('list');
    // YouTube Music's own address for an album.
    return host === 'music.youtube.com' && path.startsWith('/browse/');
  }
  if (host === 'soundcloud.com') return /\/sets\//.test(path);
  if (host.endsWith('bandcamp.com')) return path.startsWith('/album/');
  return false;
}

/**
 * Each entry becomes a download of its own, in the background queue; as songs, gathered into
 * a music playlist of the playlist's name, in its order. Resolves to how many were taken,
 * and how many were left alone because that playlist has their songs already.
 */
export async function queueLocalPlaylist(playlist: LocalPlaylist, address: string): Promise<{ queued: number; skipped: number }> {
  const result = await LocalDownloaderModule.queuePlaylist({
    title: playlist.title ?? null,
    urls: playlist.entries.map((entry) => entry.url),
    // The playlist's own address: downloaded again, it fills the music playlist it made before.
    address,
  });
  return { queued: result.queued, skipped: result.skipped ?? 0 };
}

/** What the playlist holds: each entry's page. Asks the site; nothing is downloaded. */
export function expandLocalPlaylist(url: string): Promise<LocalPlaylist> {
  return LocalDownloaderModule.expandPlaylist(url);
}
