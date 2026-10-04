import { Ionicons } from '@expo/vector-icons';
import { useRouter, type Href } from 'expo-router';
import React, { useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Pressable, StyleSheet, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import { isPairingSupported, subscribeToPairingState, type LocalPairingState } from '@/src/api';
import { useTheme } from '@/src/theme';

import { AppText as Text } from './AppText';

/** A batch pauses between files; the bar waits this long before it goes. */
const HIDE_AFTER_MS = 2000;

type Shown = Pick<LocalPairingState, 'transferIncoming' | 'transferPeer' | 'transferIndex' | 'transferCount'> & {
  percent: number;
};

/**
 * Music going to or coming from a paired device, shown wherever the user is: a small bar over
 * every screen while it moves, with the device, "3 of 12" for several, and how far the one
 * in flight is. Tapping it opens Devices. It used to be seen only on Devices, so a transfer
 * started from the other device ran unseen. Never what the tracks are called.
 */
export function PeerTransferBar() {
  const { t } = useTranslation();
  const { colors } = useTheme();
  const router = useRouter();
  const insets = useSafeAreaInsets();
  const [shown, setShown] = useState<Shown | null>(null);
  const hideTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    if (!isPairingSupported()) return;
    const subscription = subscribeToPairingState((state) => {
      const moving = state.transferTotal > 0 || state.batchCount > 0;
      if (moving) {
        if (hideTimer.current) clearTimeout(hideTimer.current);
        hideTimer.current = null;
        setShown({
          transferIncoming: state.transferIncoming,
          transferPeer: state.transferPeer,
          transferIndex: state.transferIndex,
          transferCount: state.transferCount,
          percent: state.transferTotal > 0 ? Math.round((state.transferDone / state.transferTotal) * 100) : 0,
        });
      } else if (!hideTimer.current) {
        hideTimer.current = setTimeout(() => {
          hideTimer.current = null;
          setShown(null);
        }, HIDE_AFTER_MS);
      }
    });
    return () => {
      subscription.remove();
      if (hideTimer.current) clearTimeout(hideTimer.current);
    };
  }, []);

  if (!shown) return null;
  const peer = shown.transferPeer || t('devices.aDevice');
  const title = shown.transferIncoming ? t('devices.receivingFrom', { name: peer }) : t('devices.sendingTo', { name: peer });
  const detail = [
    shown.transferCount > 1 ? t('devices.batch', { index: shown.transferIndex, count: shown.transferCount }) : null,
    `${shown.percent}%`,
  ].filter(Boolean).join(' · ');

  return (
    <View pointerEvents="box-none" style={[StyleSheet.absoluteFill, styles.layer]}>
      <Pressable
        onPress={() => router.push('/devices' as Href)}
        accessibilityRole="button"
        style={[styles.bar, { bottom: insets.bottom + 84, backgroundColor: colors.surface, borderColor: colors.border }]}
      >
        <Ionicons name={shown.transferIncoming ? 'arrow-down-circle' : 'arrow-up-circle'} size={20} color={colors.accent} />
        <View style={styles.text}>
          <Text style={[styles.title, { color: colors.text }]} numberOfLines={1}>{title}</Text>
          <Text style={[styles.detail, { color: colors.textMuted }]}>{detail}</Text>
        </View>
        <View style={[styles.track, { backgroundColor: colors.surfaceHover }]}>
          <View style={[styles.fill, { width: `${shown.percent}%`, backgroundColor: colors.accent }]} />
        </View>
      </Pressable>
    </View>
  );
}

const styles = StyleSheet.create({
  layer: { justifyContent: 'flex-end', alignItems: 'center' },
  bar: {
    position: 'absolute',
    flexDirection: 'row',
    alignItems: 'center',
    gap: 10,
    minWidth: 260,
    maxWidth: '92%',
    paddingHorizontal: 14,
    paddingVertical: 10,
    borderRadius: 16,
    borderWidth: StyleSheet.hairlineWidth,
    elevation: 6,
    shadowColor: '#000',
    shadowOpacity: 0.2,
    shadowRadius: 8,
    shadowOffset: { width: 0, height: 2 },
  },
  text: { flexShrink: 1 },
  title: { fontSize: 13, fontWeight: '600' },
  detail: { fontSize: 12, fontVariant: ['tabular-nums'] },
  track: { width: 48, height: 4, borderRadius: 2, overflow: 'hidden' },
  fill: { height: 4 },
});
