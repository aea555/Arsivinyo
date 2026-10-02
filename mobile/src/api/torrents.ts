import LocalDownloaderModule, { type TorrentDownload, type TorrentFile } from '../native/localDownloader';

export type { TorrentDownload, TorrentFile };

/**
 * Torrent downloads (`shared/watch/CONTRACT.md`, "Downloading"): magnets and .torrent files,
 * files chosen, public or into the vault. Streaming goes through the watch API instead.
 */
const M = LocalDownloaderModule;

/** A magnet link, or a content:// URI of a .torrent file. */
export const addTorrent = (input: string) => M.torrentAdd(input);
export const pickTorrentFile = () => M.torrentPickFile();
export const getTorrentFiles = (id: string) => M.torrentFiles(id);
export const chooseTorrentFiles = (id: string, wanted: number[], destination: 'public' | 'private') =>
  M.torrentChoose(id, wanted, destination);
export const listTorrents = () => M.torrentList();
export const pauseTorrent = (id: string) => M.torrentPause(id);
export const resumeTorrent = (id: string) => M.torrentResume(id);
export const removeTorrent = (id: string, deleteFiles: boolean) => M.torrentRemove(id, deleteFiles);
