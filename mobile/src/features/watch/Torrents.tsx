import { Ionicons } from '@expo/vector-icons';
import React, { useCallback, useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Modal, Pressable, StyleSheet, View } from 'react-native';

import { getTorrentLive, getTorrentSettings, setTorrentSettings, vpnAppearsActive, type WatchTorrentLive } from '@/src/api';
import { AppText as Text } from '@/src/components';
import { useTheme } from '@/src/theme';

// "Got it" holds until the app is next started; "Don't show again" holds for good.
let headsUpSeen = false;
let vpnNoticeDismissed = false;

/**
 * The heads-up before the first torrent (`shared/watch/CONTRACT.md`, "The heads-up"): that
 * everyone sharing a torrent sees this device's address, and that a VPN hides it. Returns
 * `ask`, which resolves true to go ahead, and the modal to render.
 */
export function useTorrentHeadsUp(): [() => Promise<boolean>, React.ReactElement] {
  const { t } = useTranslation();
  const { colors } = useTheme();
  const [visible, setVisible] = useState(false);
  const answer = useRef<((go: boolean) => void) | null>(null);

  const ask = useCallback(async () => {
    if (headsUpSeen) return true;
    const settings = await getTorrentSettings().catch(() => null);
    if (settings?.headsUpDismissed) {
      headsUpSeen = true;
      return true;
    }
    return new Promise<boolean>((resolve) => {
      answer.current = resolve;
      setVisible(true);
    });
  }, []);

  const close = (go: boolean, never: boolean) => {
    setVisible(false);
    if (go) headsUpSeen = true;
    if (never) void setTorrentSettings({ headsUpDismissed: true }).catch(() => undefined);
    answer.current?.(go);
    answer.current = null;
  };

  const element = (
    <Modal visible={visible} transparent animationType="fade" onRequestClose={() => close(false, false)}>
      <View style={styles.overlay}>
        <View style={[styles.card, { backgroundColor: colors.surface, borderColor: colors.border }]}>
          <Ionicons name="eye-outline" size={28} color={colors.warning} />
          <Text style={[styles.title, { color: colors.text }]}>{t('torrents.headsUp.title')}</Text>
          <Text style={[styles.body, { color: colors.textMuted }]}>{t('torrents.headsUp.body')}</Text>
          <View style={styles.buttons}>
            <Pressable onPress={() => close(true, true)} style={[styles.button, { backgroundColor: colors.surfaceHover }]}>
              <Text style={[styles.buttonText, { color: colors.text }]}>{t('torrents.headsUp.never')}</Text>
            </Pressable>
            <Pressable onPress={() => close(true, false)} style={[styles.button, { backgroundColor: colors.accent }]}>
              <Text style={[styles.buttonText, { color: colors.background }]}>{t('torrents.headsUp.ok')}</Text>
            </Pressable>
          </View>
        </View>
      </View>
    </Modal>
  );
  return [ask, element];
}

/**
 * A small notice on the torrent screens when no VPN appears to be up. A hint, not a gate:
 * detection can be wrong either way, and it says "appears".
 */
export function VpnNotice() {
  const { t } = useTranslation();
  const { colors } = useTheme();
  const [shown, setShown] = useState(false);

  useEffect(() => {
    let live = true;
    if (!vpnNoticeDismissed) {
      vpnAppearsActive()
        .then((active) => live && setShown(!active))
        .catch(() => undefined);
    }
    return () => {
      live = false;
    };
  }, []);

  if (!shown) return null;
  return (
    <View style={[styles.notice, { backgroundColor: colors.surface, borderColor: colors.warning }]}>
      <Ionicons name="shield-outline" size={18} color={colors.warning} />
      <Text style={[styles.noticeText, { color: colors.text }]}>{t('torrents.noVpn')}</Text>
      <Pressable
        onPress={() => {
          vpnNoticeDismissed = true;
          setShown(false);
        }}
        hitSlop={10}
        accessibilityRole="button"
        accessibilityLabel={t('common.close')}
      >
        <Ionicons name="close" size={18} color={colors.textMuted} />
      </Pressable>
    </View>
  );
}

const styles = StyleSheet.create({
  live: { fontSize: 12 },
  overlay: { flex: 1, backgroundColor: '#000000CC', alignItems: 'center', justifyContent: 'center', padding: 24 },
  card: { width: '100%', maxWidth: 420, borderRadius: 16, borderWidth: 1, padding: 20, gap: 12 },
  title: { fontSize: 17, fontWeight: '700' },
  body: { fontSize: 14, lineHeight: 20 },
  buttons: { flexDirection: 'row', gap: 10, marginTop: 4 },
  button: { flex: 1, alignItems: 'center', justifyContent: 'center', borderRadius: 10, paddingVertical: 12 },
  buttonText: { fontSize: 14, fontWeight: '600' },
  notice: {
    flexDirection: 'row', alignItems: 'center', gap: 10, marginHorizontal: 16, marginVertical: 8,
    paddingHorizontal: 12, paddingVertical: 10, borderRadius: 10, borderWidth: 1,
  },
  noticeText: { flex: 1, fontSize: 13 },
});

/**
 * What a torrent being opened is doing, under a spinner: finding peers while its file list
 * comes, then its peers and speed while the player waits for the first frame. Asked for once
 * a second, only while shown.
 */
export function TorrentLive({ id, color }: { id: string | null | undefined; color: string }) {
  const { t } = useTranslation();
  const [live, setLive] = useState<WatchTorrentLive | null>(null);

  useEffect(() => {
    if (!id) return;
    let on = true;
    const ask = () => void getTorrentLive(id).then((l) => on && setLive(l)).catch(() => undefined);
    ask();
    const timer = setInterval(ask, 1000);
    return () => {
      on = false;
      clearInterval(timer);
    };
  }, [id]);

  if (!id || !live) return null;
  const text = live.hasMetadata
    ? t('torrents.live.loading', { peers: live.peers, rate: rate(live.downloadRate) })
    : t('torrents.live.finding', { peers: live.peers });
  return <Text style={[styles.live, { color }]}>{text}</Text>;
}

function rate(bytesPerSecond: number): string {
  if (bytesPerSecond >= 1 << 20) return `${(bytesPerSecond / (1 << 20)).toFixed(1)} MB/s`;
  return `${Math.round(bytesPerSecond / (1 << 10))} KB/s`;
}
