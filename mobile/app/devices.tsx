import { Ionicons } from '@expo/vector-icons';
import { useFocusEffect } from 'expo-router';
import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  ActivityIndicator,
  KeyboardAvoidingView,
  Modal,
  Platform,
  Pressable,
  ScrollView,
  StyleSheet,
  TextInput,
  View,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import {
  beginPairing,
  browseDevice,
  cancelPairing,
  clearPeerUrl,
  confirmPairing,
  connectToDevice,
  EMPTY_PAIRING_STATE,
  fetchFromDevice,
  forgetDevice,
  getPairingState,
  isPairingSupported,
  listLocalSounds,
  sendToDevice,
  sendUrlToDevice,
  setDeviceName,
  startPairing,
  subscribeToPairingState,
  type LocalPairingState,
  type LocalSound,
} from '@/src/api';
import { AppText as Text, ConfirmModal, type ConfirmConfig } from '@/src/components';
import { useTheme } from '@/src/theme';

/** Eight characters is enough to compare by eye and short enough to fit a row. */
function shortId(fingerprint: string): string {
  return fingerprint.slice(0, 8);
}

function sizeLabel(bytes: number): string {
  if (!bytes) return '';
  const mb = bytes / 1048576;
  return mb >= 1024 ? `${(mb / 1024).toFixed(1)} GB` : `${mb.toFixed(1)} MB`;
}

/**
 * Pairing and transfers.
 *
 * Built around the one moment that matters — comparing six digits on two screens — so that
 * step is the largest thing here and cannot be dismissed by accident.
 *
 * No file name appears anywhere on this screen. A transfer shows a size and a percentage,
 * which is the same rule the download notifications follow: this app holds a private
 * vault, and a title on a screen someone else can see defeats the point.
 */
export default function DevicesScreen() {
  const { colors } = useTheme();
  const { t } = useTranslation();

  const [state, setState] = useState<LocalPairingState>(EMPTY_PAIRING_STATE);
  const [starting, setStarting] = useState(true);
  const [browsing, setBrowsing] = useState<{ fingerprint: string; name: string } | null>(null);
  const [linkTarget, setLinkTarget] = useState<{ fingerprint: string; name: string } | null>(null);
  const [linkText, setLinkText] = useState('');
  const [sendTarget, setSendTarget] = useState<{ fingerprint: string; name: string } | null>(null);
  const [tracks, setTracks] = useState<LocalSound[]>([]);
  const [renaming, setRenaming] = useState(false);
  const [nameText, setNameText] = useState('');
  const [confirm, setConfirm] = useState<{ config: ConfirmConfig; onConfirm: () => void } | null>(
    null
  );
  const supported = isPairingSupported();
  const mounted = useRef(true);

  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
    };
  }, []);

  useEffect(() => {
    if (!supported) {
      setStarting(false);
      return;
    }
    let cancelled = false;
    // Starting is what creates the identity and opens the socket, so it happens when the
    // screen is opened and not before.
    void (async () => {
      try {
        await startPairing();
      } catch {
        // A failure here leaves `ready` false in the state, which the screen reports.
      }
      const next = await getPairingState();
      if (!cancelled && mounted.current) {
        setState(next);
        setStarting(false);
      }
    })();
    const subscription = subscribeToPairingState((next) => {
      if (mounted.current) setState(next);
    });
    return () => {
      cancelled = true;
      subscription.remove();
    };
  }, [supported]);

  // Refresh on focus: a peer may have connected or gone while the screen was away.
  useFocusEffect(
    useCallback(() => {
      if (!supported) return;
      void getPairingState().then((next) => {
        if (mounted.current) setState(next);
      });
    }, [supported])
  );

  const listing = useMemo(
    () => (browsing && state.listingFrom === browsing.fingerprint ? state.listing : []),
    [browsing, state.listing, state.listingFrom]
  );

  const openBrowse = useCallback(async (fingerprint: string, name: string) => {
    setBrowsing({ fingerprint, name });
    await browseDevice(fingerprint);
  }, []);

  const openSend = useCallback(async (fingerprint: string, name: string) => {
    const library = await listLocalSounds();
    setTracks(library.songs);
    setSendTarget({ fingerprint, name });
  }, []);

  if (!supported) {
    return (
      <SafeAreaView style={[styles.screen, { backgroundColor: colors.background }]} edges={['bottom']}>
        <Text style={[styles.blurb, { color: colors.textMuted }]}>{t('devices.unsupported')}</Text>
      </SafeAreaView>
    );
  }

  const transferring = state.transferTotal > 0;
  const percent = transferring ? Math.round((state.transferDone / state.transferTotal) * 100) : 0;

  return (
    <SafeAreaView style={[styles.screen, { backgroundColor: colors.background }]} edges={['bottom']}>
      <ScrollView contentContainerStyle={styles.content}>
        {/* ---- this device ---- */}
        <Pressable
          onPress={() => {
            setNameText(state.deviceName);
            setRenaming(true);
          }}
          style={[styles.card, { backgroundColor: colors.surface, borderColor: colors.border }]}
        >
          <View style={styles.rowBetween}>
            <View style={styles.flex}>
              <Text style={[styles.cardTitle, { color: colors.text }]}>{state.deviceName}</Text>
              <Text style={[styles.cardSub, { color: colors.textSubtle }]}>
                {t('devices.thisDevice')} · {shortId(state.fingerprint)}
              </Text>
            </View>
            <Text
              style={[
                styles.status,
                { color: state.listening ? colors.success : colors.textSubtle },
              ]}
            >
              {state.listening
                ? t('devices.visibleOnPort', { port: state.port })
                : t('devices.notVisible')}
            </Text>
          </View>
        </Pressable>

        {starting ? <ActivityIndicator color={colors.accent} style={styles.spinner} /> : null}

        {!starting && !state.ready ? (
          <Text style={[styles.blurb, { color: colors.error }]}>{t('devices.notReady')}</Text>
        ) : null}

        {/* ---- the six digits ---- */}
        {state.pendingCode.length > 0 ? (
          <View
            style={[
              styles.codeCard,
              { backgroundColor: colors.surface, borderColor: colors.accent },
            ]}
          >
            <Text style={[styles.cardSub, { color: colors.textMuted }]}>
              {t('devices.confirmTitle')}
            </Text>
            <Text style={[styles.code, { color: colors.text }]}>{state.pendingCode}</Text>
            <View style={styles.rowCentre}>
              <Pressable onPress={() => void confirmPairing()} hitSlop={8}>
                <Text style={[styles.action, { color: colors.accent }]}>
                  {t('devices.confirmYes')}
                </Text>
              </Pressable>
              <Pressable onPress={() => void cancelPairing()} hitSlop={8}>
                <Text style={[styles.action, { color: colors.error }]}>
                  {t('devices.confirmNo')}
                </Text>
              </Pressable>
            </View>
          </View>
        ) : null}

        {/* ---- a transfer in flight ---- */}
        {transferring ? (
          <View style={[styles.card, { backgroundColor: colors.surface, borderColor: colors.border }]}>
            <View style={styles.rowBetween}>
              <Text style={[styles.cardSub, { color: colors.textMuted }]}>
                {sizeLabel(state.transferDone)} / {sizeLabel(state.transferTotal)}
              </Text>
              <Text style={[styles.cardSub, { color: colors.accent }]}>{percent}%</Text>
            </View>
            <View style={[styles.track, { backgroundColor: colors.surfaceHover }]}>
              <View
                style={[styles.fill, { width: `${percent}%`, backgroundColor: colors.accent }]}
              />
            </View>
          </View>
        ) : null}

        {state.message.length > 0 ? (
          <Text style={[styles.blurb, { color: colors.textMuted }]}>{state.message}</Text>
        ) : null}

        {browsing ? (
          <>
            <View style={styles.rowBetween}>
              <Pressable onPress={() => setBrowsing(null)} hitSlop={8}>
                <Text style={[styles.action, { color: colors.accent }]}>
                  ← {t('devices.backToDevices')}
                </Text>
              </Pressable>
              <Text style={[styles.cardSub, { color: colors.textMuted }]}>
                {t('devices.libraryOf', { name: browsing.name })}
              </Text>
            </View>
            {listing.map((item) => (
              <View
                key={item.id}
                style={[styles.row, { backgroundColor: colors.surface, borderColor: colors.border }]}
              >
                <View style={styles.flex}>
                  <Text style={[styles.rowTitle, { color: colors.text }]} numberOfLines={1}>
                    {item.title}
                  </Text>
                  <Text style={[styles.rowSub, { color: colors.textSubtle }]}>
                    {[item.artist, sizeLabel(item.sizeBytes)].filter(Boolean).join('  ·  ')}
                  </Text>
                </View>
                <Pressable
                  onPress={() => void fetchFromDevice(browsing.fingerprint, item.id)}
                  disabled={transferring}
                  hitSlop={8}
                >
                  <Text
                    style={[
                      styles.action,
                      { color: transferring ? colors.textSubtle : colors.accent },
                    ]}
                  >
                    {t('devices.get')}
                  </Text>
                </Pressable>
              </View>
            ))}
          </>
        ) : (
          <>
            {/* ---- actions ---- */}
            <View style={styles.rowStart}>
              <Pressable
                onPress={() => void (state.pairingMode ? cancelPairing() : beginPairing(120))}
                style={[
                  styles.button,
                  {
                    backgroundColor: state.pairingMode ? colors.surfaceHover : colors.surface,
                    borderColor: state.pairingMode ? colors.accent : colors.border,
                  },
                ]}
              >
                <Text
                  style={[
                    styles.buttonLabel,
                    { color: state.pairingMode ? colors.accent : colors.text },
                  ]}
                >
                  {state.pairingMode ? t('devices.waiting') : t('devices.addDevice')}
                </Text>
              </Pressable>
            </View>

            {/* ---- paired ---- */}
            {state.peers.length > 0 ? (
              <Text style={[styles.section, { color: colors.textSubtle }]}>
                {t('devices.paired')}
              </Text>
            ) : null}
            {state.peers.map((peer) => (
              <View
                key={peer.fingerprint}
                style={[styles.row, { backgroundColor: colors.surface, borderColor: colors.border }]}
              >
                <View style={styles.flex}>
                  <Text style={[styles.rowTitle, { color: colors.text }]} numberOfLines={1}>
                    {peer.name || t('devices.unnamed')}
                  </Text>
                  <Text
                    style={[
                      styles.rowSub,
                      { color: peer.connected ? colors.success : colors.textSubtle },
                    ]}
                  >
                    {peer.connected ? t('devices.connected') : shortId(peer.fingerprint)}
                  </Text>
                </View>
                <Pressable
                  onPress={() => void openBrowse(peer.fingerprint, peer.name)}
                  disabled={!peer.connected}
                  hitSlop={6}
                >
                  <Text
                    style={[
                      styles.action,
                      { color: peer.connected ? colors.accent : colors.textSubtle },
                    ]}
                  >
                    {t('devices.browse')}
                  </Text>
                </Pressable>
                <Pressable
                  onPress={() => void openSend(peer.fingerprint, peer.name)}
                  disabled={!peer.connected}
                  hitSlop={6}
                >
                  <Text
                    style={[
                      styles.action,
                      { color: peer.connected ? colors.accent : colors.textSubtle },
                    ]}
                  >
                    {t('devices.send')}
                  </Text>
                </Pressable>
                <Pressable
                  onPress={() => {
                    setLinkTarget({ fingerprint: peer.fingerprint, name: peer.name });
                    setLinkText('');
                  }}
                  disabled={!peer.connected}
                  hitSlop={6}
                >
                  <Text
                    style={[
                      styles.action,
                      { color: peer.connected ? colors.accent : colors.textSubtle },
                    ]}
                  >
                    {t('devices.link')}
                  </Text>
                </Pressable>
                <Pressable
                  onPress={() =>
                    setConfirm({
                      config: {
                        title: t('devices.forgetTitle'),
                        message: t('devices.forgetBody'),
                        confirm: t('devices.forget'),
                        destructive: true,
                      },
                      onConfirm: () => void forgetDevice(peer.fingerprint),
                    })
                  }
                  hitSlop={6}
                >
                  <Ionicons name="close-circle-outline" size={20} color={colors.error} />
                </Pressable>
              </View>
            ))}

            {/* ---- found on the network ---- */}
            <Text style={[styles.section, { color: colors.textSubtle }]}>
              {t('devices.onThisNetwork')}
            </Text>
            {state.discovered.length === 0 ? (
              <Text style={[styles.blurb, { color: colors.textSubtle }]}>
                {t('devices.noDevices')}
              </Text>
            ) : null}
            {state.discovered.map((device) => (
              <View
                key={device.fingerprint}
                style={[styles.row, { borderColor: colors.border }]}
              >
                <Text style={[styles.rowTitle, styles.flex, { color: colors.textMuted }]} numberOfLines={1}>
                  {(device.name || t('devices.unnamed')) + '  ·  ' + shortId(device.fingerprint)}
                </Text>
                <Pressable
                  onPress={() => {
                    void beginPairing(120);
                    void connectToDevice(device.host, device.port);
                  }}
                  hitSlop={8}
                >
                  <Text style={[styles.action, { color: colors.accent }]}>{t('devices.pair')}</Text>
                </Pressable>
              </View>
            ))}
          </>
        )}
      </ScrollView>

      {/* ---- a link a peer sent ---- */}
      <ConfirmModal
        visible={state.peerUrl.length > 0}
        config={{
          title: t('devices.peerUrlTitle'),
          message: t('devices.peerUrlBody'),
          confirm: t('devices.peerUrlAccept'),
          destructive: false,
        }}
        onConfirm={() => void clearPeerUrl()}
        onCancel={() => void clearPeerUrl()}
      />

      <ConfirmModal
        visible={confirm !== null}
        config={confirm?.config ?? null}
        onConfirm={() => {
          confirm?.onConfirm();
          setConfirm(null);
        }}
        onCancel={() => setConfirm(null)}
      />

      {/* ---- send a link ---- */}
      <Modal visible={linkTarget !== null} transparent animationType="fade">
        <KeyboardAvoidingView
          behavior={Platform.OS === 'ios' ? 'padding' : undefined}
          style={styles.backdrop}
        >
          <View style={[styles.sheet, { backgroundColor: colors.surface, borderColor: colors.border }]}>
            <Text style={[styles.cardTitle, { color: colors.text }]}>
              {t('devices.sendLinkTitle', { name: linkTarget?.name ?? '' })}
            </Text>
            <Text style={[styles.cardSub, { color: colors.textSubtle }]}>
              {t('devices.sendLinkBody')}
            </Text>
            <TextInput
              value={linkText}
              onChangeText={setLinkText}
              placeholder="https://…"
              placeholderTextColor={colors.textSubtle}
              autoCapitalize="none"
              autoCorrect={false}
              style={[
                styles.input,
                { color: colors.text, backgroundColor: colors.background, borderColor: colors.border },
              ]}
            />
            <View style={styles.rowEnd}>
              <Pressable onPress={() => setLinkTarget(null)} hitSlop={8}>
                <Text style={[styles.action, { color: colors.textMuted }]}>
                  {t('common.cancel')}
                </Text>
              </Pressable>
              <Pressable
                onPress={() => {
                  if (linkTarget && linkText.trim()) {
                    void sendUrlToDevice(linkTarget.fingerprint, linkText.trim(), 'audio');
                  }
                  setLinkTarget(null);
                  setLinkText('');
                }}
                hitSlop={8}
              >
                <Text
                  style={[
                    styles.action,
                    { color: linkText.trim() ? colors.accent : colors.textSubtle },
                  ]}
                >
                  {t('devices.send')}
                </Text>
              </Pressable>
            </View>
          </View>
        </KeyboardAvoidingView>
      </Modal>

      {/* ---- pick a track to send ---- */}
      <Modal visible={sendTarget !== null} transparent animationType="fade">
        <View style={styles.backdrop}>
          <View style={[styles.sheet, { backgroundColor: colors.surface, borderColor: colors.border }]}>
            <Text style={[styles.cardTitle, { color: colors.text }]}>
              {t('devices.sendPickTitle', { name: sendTarget?.name ?? '' })}
            </Text>
            <ScrollView style={styles.pickList}>
              {tracks.length === 0 ? (
                <Text style={[styles.blurb, { color: colors.textSubtle }]}>
                  {t('devices.empty')}
                </Text>
              ) : null}
              {tracks.map((track) => (
                <Pressable
                  key={track.id}
                  onPress={() => {
                    if (sendTarget) void sendToDevice(sendTarget.fingerprint, track.id);
                    setSendTarget(null);
                  }}
                  style={styles.pickRow}
                >
                  <Text style={[styles.rowTitle, { color: colors.text }]} numberOfLines={1}>
                    {track.title}
                  </Text>
                  <Text style={[styles.rowSub, { color: colors.textSubtle }]}>
                    {sizeLabel(track.sizeBytes)}
                  </Text>
                </Pressable>
              ))}
            </ScrollView>
            <View style={styles.rowEnd}>
              <Pressable onPress={() => setSendTarget(null)} hitSlop={8}>
                <Text style={[styles.action, { color: colors.textMuted }]}>
                  {t('common.cancel')}
                </Text>
              </Pressable>
            </View>
          </View>
        </View>
      </Modal>

      {/* ---- rename this device ---- */}
      <Modal visible={renaming} transparent animationType="fade">
        <KeyboardAvoidingView
          behavior={Platform.OS === 'ios' ? 'padding' : undefined}
          style={styles.backdrop}
        >
          <View style={[styles.sheet, { backgroundColor: colors.surface, borderColor: colors.border }]}>
            <Text style={[styles.cardTitle, { color: colors.text }]}>
              {t('devices.renameTitle')}
            </Text>
            <Text style={[styles.cardSub, { color: colors.textSubtle }]}>
              {t('devices.renameHint')}
            </Text>
            <TextInput
              value={nameText}
              onChangeText={setNameText}
              autoCapitalize="words"
              style={[
                styles.input,
                { color: colors.text, backgroundColor: colors.background, borderColor: colors.border },
              ]}
            />
            <View style={styles.rowEnd}>
              <Pressable onPress={() => setRenaming(false)} hitSlop={8}>
                <Text style={[styles.action, { color: colors.textMuted }]}>
                  {t('common.cancel')}
                </Text>
              </Pressable>
              <Pressable
                onPress={() => {
                  const trimmed = nameText.trim();
                  if (trimmed) void setDeviceName(trimmed);
                  setRenaming(false);
                }}
                hitSlop={8}
              >
                <Text style={[styles.action, { color: colors.accent }]}>{t('common.save')}</Text>
              </Pressable>
            </View>
          </View>
        </KeyboardAvoidingView>
      </Modal>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1 },
  content: { padding: 16, gap: 12 },
  flex: { flex: 1 },
  card: { borderRadius: 12, borderWidth: 1, padding: 14, gap: 8 },
  codeCard: { borderRadius: 12, borderWidth: 2, padding: 18, gap: 8, alignItems: 'center' },
  cardTitle: { fontSize: 15 },
  cardSub: { fontSize: 12 },
  code: { fontSize: 34, letterSpacing: 6 },
  status: { fontSize: 11 },
  section: { fontSize: 11, marginTop: 6 },
  blurb: { fontSize: 12, paddingHorizontal: 4 },
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
    borderRadius: 10,
    borderWidth: 1,
    paddingHorizontal: 14,
    paddingVertical: 12,
  },
  rowBetween: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: 12 },
  rowStart: { flexDirection: 'row', alignItems: 'center', gap: 8 },
  rowCentre: { flexDirection: 'row', justifyContent: 'center', gap: 20, marginTop: 4 },
  rowEnd: { flexDirection: 'row', justifyContent: 'flex-end', gap: 18, marginTop: 4 },
  rowTitle: { fontSize: 14 },
  rowSub: { fontSize: 11 },
  action: { fontSize: 13 },
  button: { borderRadius: 8, borderWidth: 1, paddingHorizontal: 14, paddingVertical: 8 },
  buttonLabel: { fontSize: 13 },
  track: { height: 4, borderRadius: 2, overflow: 'hidden' },
  fill: { height: 4, borderRadius: 2 },
  spinner: { marginTop: 12 },
  backdrop: { flex: 1, backgroundColor: 'rgba(0,0,0,0.72)', justifyContent: 'center', padding: 24 },
  sheet: { borderRadius: 14, borderWidth: 1, padding: 18, gap: 10 },
  input: { borderRadius: 8, borderWidth: 1, paddingHorizontal: 12, paddingVertical: 10, fontSize: 13 },
  pickList: { maxHeight: 280 },
  pickRow: { paddingVertical: 10, gap: 2 },
});
