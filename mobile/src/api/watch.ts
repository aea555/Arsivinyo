import { Platform } from 'react-native';

import LocalDownloaderModule, {
  type WatchAddon,
  type WatchItem,
  type WatchMeta,
  type WatchOffer,
  type WatchPrepared,
  type WatchTorrentLive,
  type WatchPreview,
  type WatchProgress,
  type WatchResult,
  type WatchRow,
  type WatchStream,
  type WatchStreamKind,
  type WatchSubtitle,
  type WatchLanguages,
  type TorrentSettings,
  type WatchTitle,
  type WatchVideo,
} from '../native/localDownloader';

export type {
  WatchAddon,
  WatchItem,
  WatchMeta,
  WatchOffer,
  WatchPrepared,
  WatchTorrentLive,
  WatchPreview,
  WatchProgress,
  WatchResult,
  WatchRow,
  WatchStream,
  WatchStreamKind,
  WatchSubtitle,
  WatchLanguages,
  TorrentSettings,
  WatchTitle,
  WatchVideo,
};

/**
 * Watch: Stremio add-ons and what they stream (`shared/watch/CONTRACT.md`). Add-on URLs stay
 * native; the screens handle each add-on by an opaque key.
 */

export function isWatchSupported(): boolean {
  return Platform.OS === 'android';
}

/** Cinemeta, Stremio's own catalog and metadata add-on: the one to start with. */
export const CINEMETA = 'https://v3-cinemeta.strem.io/manifest.json';

/**
 * The recommended add-ons (`shared/watch/CONTRACT.md`, "The recommended add-ons"): enough to
 * watch real films and series with one tap. The Mac has the same list; change both.
 */
export const RECOMMENDED: { name: string; url: string }[] = [
  { name: 'Cinemeta', url: CINEMETA },
  { name: 'Streaming Catalogs', url: 'https://7a82163c306e-stremio-netflix-catalog-addon.baby-beamup.club/manifest.json' },
  { name: 'Torrentio', url: 'https://torrentio.strem.fun/manifest.json' },
  { name: 'TorrentsDB', url: 'https://torrentsdb.com/manifest.json' },
  { name: 'ThePirateBay+', url: 'https://thepiratebay-plus.strem.fun/manifest.json' },
  { name: 'OpenSubtitles v3', url: 'https://opensubtitles-v3.strem.io/manifest.json' },
];

const hostOf = (url: string) => /^[a-z]+:\/\/([^/?#]+)/i.exec(url)?.[1] ?? url;

/** The recommended add-ons not installed yet. One from the same host counts as installed, so a
 *  configured Torrentio is kept as it is. */
export function missingRecommended(installed: WatchAddon[]) {
  const hosts = new Set(installed.map((a) => a.host));
  return RECOMMENDED.filter((r) => !hosts.has(hostOf(r.url)));
}

/** Installs the missing recommended add-ons, in the list's order. Gives the names of those
 *  installed and of those that failed. */
export async function installRecommended(): Promise<{ installed: string[]; failed: string[] }> {
  const installed: string[] = [];
  const failed: string[] = [];
  for (const addon of missingRecommended(await listAddons())) {
    const result = await installAddon(addon.url).catch(() => null);
    (result?.success ? installed : failed).push(addon.name);
  }
  return { installed, failed };
}

const M = LocalDownloaderModule;

export const listAddons = () => M.watchAddons();
export const installAddon = (url: string) => M.watchInstallAddon(url);
export const listOfferLists = () => M.watchOfferLists();
export const getOffers = (row: WatchRow) => M.watchOffers(row.addonKey, row.type, row.id);
export const uninstallAddon = (key: string) => M.watchUninstallAddon(key);
export const setAddonEnabled = (key: string, enabled: boolean) => M.watchSetAddonEnabled(key, enabled);
export const moveAddon = (key: string, position: number) => M.watchMoveAddon(key, position);
export const listRows = (search = false) => M.watchRows(search);
export const getCatalog = (row: WatchRow, extra: Record<string, string> = {}) =>
  M.watchCatalog(row.addonKey, row.type, row.id, extra);
export const getMeta = (type: string, id: string) => M.watchMeta(type, id);
export const getStreamSources = (type: string, id: string) => M.watchStreamSources(type, id);
export const getStreams = (addonKey: string, type: string, id: string) => M.watchStreams(addonKey, type, id);
export const prepareStream = (stream: Pick<WatchStream, 'kind' | 'target' | 'headers' | 'fileIdx' | 'filename'>) =>
  M.watchPrepare(stream.kind, stream.target, stream.headers ?? {}, stream.fileIdx ?? null, stream.filename ?? null);
export const getTorrentSettings = () => M.watchTorrentSettings();
export const setTorrentSettings = (values: Partial<TorrentSettings>) => M.watchSetTorrentSettings(values);
export const vpnAppearsActive = () => M.watchVpnActive();
export const getTorrentLive = (id: string) => M.watchTorrentLive(id);

/** The engine's id for a magnet: its info hash, in lower-case hex. Null for anything else. */
export function torrentIdOf(magnet: string): string | null {
  const match = /xt=urn:btih:([0-9a-f]{40})/i.exec(magnet);
  return match ? match[1].toLowerCase() : null;
}
/** Subtitles for a video in the preferred languages, best first: the stream's own, then add-ons'. */
export const getSubtitles = (type: string, id: string, filename: string | null, own: WatchSubtitle[]) =>
  M.watchSubtitles(type, id, filename, own);
export const getLanguages = () => M.watchLanguages();
export const setLanguages = (codes: string[]) => M.watchSetLanguages(codes);
export const getLibrary = () => M.watchLibrary();
export const getLibraryItem = (id: string) => M.watchItem(id);
export const recordProgress = (
  title: WatchTitle,
  videoId: string,
  positionMs: number,
  durationMs: number,
  addonKey: string | null,
  bingeGroup: string | null,
) => M.watchRecordProgress(title, videoId, positionMs, durationMs, addonKey, bingeGroup);
export const setWatched = (title: WatchTitle, videoId: string, watched: boolean) => M.watchSetWatched(title, videoId, watched);
export const setSaved = (title: WatchTitle, saved: boolean) => M.watchSetSaved(title, saved);
export const dismissProgress = (id: string) => M.watchDismiss(id);
export const removeFromLibrary = (id: string) => M.watchRemove(id);

/** The next video after [videoId] in a series' order, skipping specials. */
export function nextVideo(videos: WatchVideo[], videoId: string): WatchVideo | null {
  const regular = videos.filter((v) => (v.season ?? 1) !== 0);
  const at = regular.findIndex((v) => v.id === videoId);
  return at >= 0 && at + 1 < regular.length ? regular[at + 1] : null;
}
