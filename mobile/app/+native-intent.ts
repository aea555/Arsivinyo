// expo-router native deep-link rewriter.
//
// stremio:// links go to the add-ons screen, which offers to install them; magnet links and
// .torrent files to the torrents screen.
export function redirectSystemPath({ path }: { path: string; initial: boolean }): string {
  try {
    // A Stremio add-on being installed, usually from its configure page. The link can carry
    // an account key; it goes to the add-ons screen, which asks before installing.
    if (path && /^stremio:\/\//i.test(path)) {
      return `/watch-addons?install=${encodeURIComponent(path)}`;
    }
    // A magnet link, or a .torrent file opened from elsewhere: the torrents screen adds it,
    // after the heads-up the first time.
    if (path && (/^magnet:/i.test(path) || /^content:\/\//i.test(path))) {
      return `/torrents?add=${encodeURIComponent(path)}`;
    }
  } catch {
    // Fall through and let expo-router handle the original path.
  }
  return path;
}
