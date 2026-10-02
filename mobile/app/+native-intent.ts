// expo-router native deep-link rewriter.
//
// react-native-track-player's Android playback notification opens the app with a
// `notification.click` deep link (e.g. `arsivinyo://notification.click`).
// There is no route by that name, so expo-router renders its "Unmatched Route"
// 404 screen. Rewrite that path to the full-screen player instead, which is the
// natural destination when a user taps the now-playing notification.
//
// stremio:// links go to the add-ons screen, which offers to install them.
export function redirectSystemPath({ path }: { path: string; initial: boolean }): string {
  try {
    if (path && path.includes('notification.click')) {
      return '/sound-player';
    }
    // A Stremio add-on being installed, usually from its configure page. The link can carry
    // an account key; it goes to the add-ons screen, which asks before installing.
    if (path && /^stremio:\/\//i.test(path)) {
      return `/watch-addons?install=${encodeURIComponent(path)}`;
    }
  } catch {
    // Fall through and let expo-router handle the original path.
  }
  return path;
}
