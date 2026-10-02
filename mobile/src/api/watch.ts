import { Platform } from 'react-native';

import LocalDownloaderModule, {
  type WatchAddon,
  type WatchItem,
  type WatchMeta,
  type WatchOffer,
  type WatchPrepared,
  type WatchPreview,
  type WatchProgress,
  type WatchResult,
  type WatchRow,
  type WatchStream,
  type WatchStreamKind,
  type WatchSubtitle,
  type WatchLanguages,
  type WatchTitle,
  type WatchVideo,
} from '../native/localDownloader';

export type {
  WatchAddon,
  WatchItem,
  WatchMeta,
  WatchOffer,
  WatchPrepared,
  WatchPreview,
  WatchProgress,
  WatchResult,
  WatchRow,
  WatchStream,
  WatchStreamKind,
  WatchSubtitle,
  WatchLanguages,
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
export const prepareStream = (stream: Pick<WatchStream, 'kind' | 'target' | 'headers'>) =>
  M.watchPrepare(stream.kind, stream.target, stream.headers ?? {});
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
