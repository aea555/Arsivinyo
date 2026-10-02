import { Ionicons } from '@expo/vector-icons';
import { useFocusEffect, useLocalSearchParams, useRouter } from 'expo-router';
import React, { useCallback, useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { ActivityIndicator, Modal, Pressable, ScrollView, StyleSheet, Switch, TextInput, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import {
  getTorrentSettings,
  setTorrentSettings,
  type TorrentSettings,
  addTorrent,
  chooseTorrentFiles,
  getTorrentFiles,
  listTorrents,
  pauseTorrent,
  pickTorrentFile,
  removeTorrent,
  resumeTorrent,
  type TorrentDownload,
  type TorrentFile,
} from '@/src/api';
import { AppText as Text } from '@/src/components';
import { useTorrentHeadsUp, VpnNotice } from '@/src/features/watch/Torrents';
import { useTheme } from '@/src/theme';

function bytes(n: number): string {
  if (n >= 1 << 30) return `${(n / (1 << 30)).toFixed(2)} GB`;
  if (n >= 1 << 20) return `${(n / (1 << 20)).toFixed(1)} MB`;
  if (n >= 1 << 10) return `${Math.round(n / (1 << 10))} KB`;
  return `${n} B`;
}

function duration(seconds: number): string {
  const s = Math.round(seconds);
  if (s >= 3600) return `${Math.floor(s / 3600)} h ${Math.floor((s % 3600) / 60)} min`;
  if (s >= 60) return `${Math.floor(s / 60)} min`;
  return `${s} s`;
}

/**
 * Torrent downloads (`shared/watch/CONTRACT.md`, "Downloading"): add a magnet or a .torrent,
 * pick its files and where they land, and follow them. Public files go to Download/Arsivinyo;
 * private ones into the vault, each as soon as it is complete.
 */
export default function TorrentsScreen() {
  const { t } = useTranslation();
  const { colors } = useTheme();
  const router = useRouter();
  const params = useLocalSearchParams<{ add?: string }>();
  const [askHeadsUp, headsUpModal] = useTorrentHeadsUp();
  const [list, setList] = useState<TorrentDownload[]>([]);
  const [vaultOpen, setVaultOpen] = useState(false);
  const [link, setLink] = useState('');
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [choosing, setChoosing] = useState<string | null>(null);
  const [removing, setRemoving] = useState<TorrentDownload | null>(null);
  const handled = useRef<string | null>(null);

  const refresh = useCallback(async () => {
    const result = await listTorrents().catch(() => null);
    if (result?.success) {
      setList(result.torrents);
      setVaultOpen(result.vaultOpen);
    }
  }, []);

  // Once a second while the screen is open.
  useFocusEffect(
    useCallback(() => {
      void refresh();
      const timer = setInterval(() => void refresh(), 1000);
      return () => clearInterval(timer);
    }, [refresh]),
  );

  const add = useCallback(
    async (input: string) => {
      if (!input.trim() || !(await askHeadsUp())) return;
      setBusy(true);
      setMessage(null);
      const result = await addTorrent(input.trim()).catch(() => null);
      setBusy(false);
      if (!result?.success) {
        setMessage(t('torrents.addFailed', { code: result && !result.success ? result.code : '' }));
        return;
      }
      setLink('');
      setChoosing(result.id);
      void refresh();
    },
    [askHeadsUp, refresh, t],
  );

  // A magnet or a .torrent opened from elsewhere.
  useEffect(() => {
    if (params.add && handled.current !== params.add) {
      handled.current = params.add;
      void add(String(params.add));
    }
  }, [add, params.add]);

  const openFile = async () => {
    const picked = await pickTorrentFile().catch(() => null);
    if (picked?.success) void add(picked.uri);
  };

  return (
    <SafeAreaView style={[styles.fill, { backgroundColor: colors.background }]}>
      <View style={styles.header}>
        <Pressable onPress={() => router.back()} hitSlop={10} accessibilityRole="button">
          <Ionicons name="chevron-back" size={24} color={colors.text} />
        </Pressable>
        <Text style={[styles.title, { color: colors.text }]}>{t('torrents.title')}</Text>
      </View>
      <ScrollView contentContainerStyle={styles.body} keyboardShouldPersistTaps="handled">
        <VpnNotice />
        <View style={styles.addRow}>
          <TextInput
            value={link}
            onChangeText={setLink}
            placeholder={t('torrents.magnetPlaceholder')}
            placeholderTextColor={colors.textMuted}
            autoCapitalize="none"
            autoCorrect={false}
            style={[styles.input, { color: colors.text, backgroundColor: colors.surface, borderColor: colors.border }]}
            onSubmitEditing={() => void add(link)}
          />
          <Pressable
            onPress={() => void add(link)}
            disabled={busy || !link.trim()}
            style={[styles.button, { backgroundColor: colors.accent, opacity: busy || !link.trim() ? 0.5 : 1 }]}
          >
            {busy ? <ActivityIndicator color={colors.background} /> : <Text style={{ color: colors.background }}>{t('torrents.add')}</Text>}
          </Pressable>
        </View>
        <Pressable onPress={() => void openFile()} style={[styles.fileButton, { borderColor: colors.border }]}>
          <Ionicons name="document-outline" size={18} color={colors.text} />
          <Text style={{ color: colors.text }}>{t('torrents.openFile')}</Text>
        </Pressable>
        {message ? <Text style={{ color: colors.warning }}>{message}</Text> : null}

        {list.length === 0 ? <Text style={[styles.empty, { color: colors.textMuted }]}>{t('torrents.empty')}</Text> : null}
        {list.map((item) => (
          <TorrentRow
            key={item.id}
            item={item}
            vaultOpen={vaultOpen}
            onChoose={() => setChoosing(item.id)}
            onPause={() => void pauseTorrent(item.id).then(refresh)}
            onResume={() => void resumeTorrent(item.id).then(refresh)}
            onRemove={() => setRemoving(item)}
          />
        ))}
        <SeedingSettings />
      </ScrollView>

      {choosing ? <ChooseFiles id={choosing} onClose={() => { setChoosing(null); void refresh(); }} /> : null}

      <Modal visible={removing != null} transparent animationType="fade" onRequestClose={() => setRemoving(null)}>
        <View style={styles.overlay}>
          <View style={[styles.card, { backgroundColor: colors.surface, borderColor: colors.border }]}>
            <Text style={[styles.cardTitle, { color: colors.text }]}>{t('torrents.removeTitle')}</Text>
            <Text style={{ color: colors.textMuted }}>{t('torrents.removeBody')}</Text>
            {[
              { label: t('torrents.removeKeep'), files: false },
              { label: t('torrents.removeWithFiles'), files: true },
            ].map((choice) => (
              <Pressable
                key={choice.label}
                onPress={() => {
                  const target = removing;
                  setRemoving(null);
                  if (target) void removeTorrent(target.id, choice.files).then(refresh);
                }}
                style={[styles.wide, { backgroundColor: choice.files ? colors.error : colors.surfaceHover }]}
              >
                <Text style={{ color: choice.files ? colors.background : colors.text, fontWeight: '600' }}>{choice.label}</Text>
              </Pressable>
            ))}
            <Pressable onPress={() => setRemoving(null)} style={styles.wide}>
              <Text style={{ color: colors.textMuted }}>{t('common.cancel')}</Text>
            </Pressable>
          </View>
        </View>
      </Modal>
      {headsUpModal}
    </SafeAreaView>
  );
}

function TorrentRow({
  item,
  vaultOpen,
  onChoose,
  onPause,
  onResume,
  onRemove,
}: {
  item: TorrentDownload;
  vaultOpen: boolean;
  onChoose: () => void;
  onPause: () => void;
  onResume: () => void;
  onRemove: () => void;
}) {
  const { t } = useTranslation();
  const { colors } = useTheme();
  const e = item.engine;
  const paused = e?.paused ?? false;
  const percent = e ? Math.floor((e.size > 0 ? e.done / e.size : e.progress) * 100) : 0;

  let line: string;
  if (item.state === 'choosing') line = e?.state === 'metadata' ? t('torrents.fetchingDetails') : t('torrents.chooseFiles');
  else if (item.state === 'done') line = item.destination === 'private' ? t('torrents.inVault') : t('torrents.inDownloads');
  else if (!e) line = t('torrents.waiting');
  else if (paused) line = t('torrents.paused', { percent });
  // Private and finished: being encrypted now if the vault is open, else waiting for it.
  else if (e.finished)
    line = item.destination === 'private'
      ? vaultOpen ? t('torrents.encrypting') : t('torrents.intoVault')
      : t('torrents.seeding', { up: bytes(e.uploadRate) });
  else
    line = [
      `${percent}%`,
      `↓ ${bytes(e.downloadRate)}/s`,
      t('torrents.peers', { count: e.peers }),
      e.etaSeconds != null ? t('torrents.left', { time: duration(e.etaSeconds) }) : null,
    ].filter(Boolean).join(' · ');

  return (
    <View style={[styles.row, { backgroundColor: colors.surface }]}>
      <Ionicons name={item.destination === 'private' ? 'lock-closed-outline' : 'download-outline'} size={20} color={colors.textMuted} />
      <View style={styles.fill}>
        <Text style={{ color: colors.text }} numberOfLines={2}>{item.name || t('torrents.unnamed')}</Text>
        <Text style={[styles.small, { color: colors.textMuted }]}>{line}</Text>
        {item.state === 'downloading' && e ? (
          <View style={[styles.bar, { backgroundColor: colors.border }]}>
            <View style={[styles.barDone, { width: `${percent}%`, backgroundColor: colors.accent }]} />
          </View>
        ) : null}
      </View>
      {item.state === 'choosing' ? (
        <Pressable onPress={onChoose} hitSlop={8}><Ionicons name="list-outline" size={22} color={colors.accent} /></Pressable>
      ) : item.state === 'downloading' ? (
        <Pressable onPress={paused ? onResume : onPause} hitSlop={8}>
          <Ionicons name={paused ? 'play' : 'pause'} size={22} color={colors.text} />
        </Pressable>
      ) : null}
      <Pressable onPress={onRemove} hitSlop={8}><Ionicons name="trash-outline" size={20} color={colors.error} /></Pressable>
    </View>
  );
}

const RATIOS = [0, 0.5, 1, 2];
const CACHE_GB = [2, 4, 8, 16];

/** This phone's seeding rules and cache size (`shared/watch/CONTRACT.md`, "Seeding"). */
function SeedingSettings() {
  const { t } = useTranslation();
  const { colors } = useTheme();
  const [settings, setSettings] = useState<TorrentSettings | null>(null);

  useEffect(() => {
    void getTorrentSettings().then(setSettings).catch(() => undefined);
  }, []);
  if (!settings) return null;

  const change = (values: Partial<TorrentSettings>) => {
    setSettings({ ...settings, ...values });
    void setTorrentSettings(values).catch(() => undefined);
  };
  const chip = (label: string, active: boolean, onPress: () => void) => (
    <Pressable
      key={label}
      onPress={onPress}
      style={[styles.chip, { borderColor: active ? colors.accent : colors.border, backgroundColor: active ? colors.surfaceHover : 'transparent' }]}
    >
      <Text style={{ color: colors.text }}>{label}</Text>
    </Pressable>
  );

  return (
    <View style={styles.settings}>
      <Text style={[styles.sectionTitle, { color: colors.text }]}>{t('torrents.seeding.title')}</Text>
      <Text style={[styles.small, { color: colors.textMuted }]}>{t('torrents.seeding.hint')}</Text>
      <View style={styles.chips}>
        {RATIOS.map((r) => chip(r === 0 ? t('torrents.seeding.never') : `${r}×`, settings.seedRatio === r, () => change({ seedRatio: r })))}
      </View>
      <View style={styles.vaultRow}>
        <Text style={[styles.fill, { color: colors.text }]}>{t('torrents.seeding.mobileData')}</Text>
        <Switch value={settings.seedOnMobileData} onValueChange={(v) => change({ seedOnMobileData: v })} />
      </View>
      <Text style={[styles.sectionTitle, { color: colors.text }]}>{t('torrents.cache.title')}</Text>
      <Text style={[styles.small, { color: colors.textMuted }]}>{t('torrents.cache.hint')}</Text>
      <View style={styles.chips}>
        {CACHE_GB.map((gb) =>
          chip(`${gb} GB`, settings.cacheLimitBytes === gb * 2 ** 30, () => change({ cacheLimitBytes: gb * 2 ** 30 })),
        )}
      </View>
    </View>
  );
}

/** A torrent's files with their sizes, all of them chosen to begin with, and where they land. */
function ChooseFiles({ id, onClose }: { id: string; onClose: () => void }) {
  const { t } = useTranslation();
  const { colors } = useTheme();
  const [files, setFiles] = useState<TorrentFile[] | null>(null);
  const [chosen, setChosen] = useState<Set<number>>(new Set());
  const [vault, setVault] = useState(false);

  // A magnet's files are known once a peer has sent its metadata.
  useEffect(() => {
    let live = true;
    const look = async () => {
      const result = await getTorrentFiles(id).catch(() => null);
      if (!live) return;
      if (result?.success && result.files) {
        setFiles(result.files);
        setChosen(new Set(result.files.map((f) => f.index)));
      } else {
        setTimeout(() => void look(), 1000);
      }
    };
    void look();
    return () => {
      live = false;
    };
  }, [id]);

  const total = (files ?? []).filter((f) => chosen.has(f.index)).reduce((sum, f) => sum + f.size, 0);
  const toggle = (index: number) =>
    setChosen((prev) => {
      const next = new Set(prev);
      if (next.has(index)) next.delete(index);
      else next.add(index);
      return next;
    });

  return (
    <Modal visible animationType="slide" onRequestClose={onClose}>
      <SafeAreaView style={[styles.fill, { backgroundColor: colors.background }]}>
        <View style={styles.header}>
          <Pressable onPress={onClose} hitSlop={10}><Ionicons name="close" size={24} color={colors.text} /></Pressable>
          <Text style={[styles.title, { color: colors.text }]}>{t('torrents.chooseTitle')}</Text>
        </View>
        {files == null ? (
          <View style={styles.center}>
            <ActivityIndicator color={colors.accent} />
            <Text style={{ color: colors.textMuted }}>{t('torrents.fetchingDetails')}</Text>
          </View>
        ) : (
          <>
            <ScrollView contentContainerStyle={styles.body}>
              <Pressable
                onPress={() => setChosen(chosen.size === files.length ? new Set() : new Set(files.map((f) => f.index)))}
                style={styles.fileRow}
              >
                <Ionicons name={chosen.size === files.length ? 'checkbox' : 'square-outline'} size={20} color={colors.accent} />
                <Text style={{ color: colors.text, fontWeight: '600' }}>{t('torrents.all', { count: files.length })}</Text>
              </Pressable>
              {files.map((f) => (
                <Pressable key={f.index} onPress={() => toggle(f.index)} style={styles.fileRow}>
                  <Ionicons name={chosen.has(f.index) ? 'checkbox' : 'square-outline'} size={20} color={colors.accent} />
                  <Text style={[styles.fill, { color: colors.text }]} numberOfLines={2}>{f.path}</Text>
                  <Text style={[styles.small, { color: colors.textMuted }]}>{bytes(f.size)}</Text>
                </Pressable>
              ))}
            </ScrollView>
            <View style={[styles.footer, { borderColor: colors.border }]}>
              <View style={styles.vaultRow}>
                <Ionicons name="lock-closed-outline" size={18} color={colors.text} />
                <View style={styles.fill}>
                  <Text style={{ color: colors.text }}>{t('torrents.intoTheVault')}</Text>
                  <Text style={[styles.small, { color: colors.textMuted }]}>
                    {vault ? t('torrents.vaultHint') : t('torrents.publicHint')}
                  </Text>
                </View>
                <Switch value={vault} onValueChange={setVault} />
              </View>
              <Pressable
                disabled={chosen.size === 0}
                onPress={() =>
                  void chooseTorrentFiles(id, [...chosen], vault ? 'private' : 'public').then(onClose)
                }
                style={[styles.wide, { backgroundColor: colors.accent, opacity: chosen.size === 0 ? 0.5 : 1 }]}
              >
                <Text style={{ color: colors.background, fontWeight: '600' }}>
                  {t('torrents.start', { size: bytes(total) })}
                </Text>
              </Pressable>
            </View>
          </>
        )}
      </SafeAreaView>
    </Modal>
  );
}

const styles = StyleSheet.create({
  fill: { flex: 1 },
  header: { flexDirection: 'row', alignItems: 'center', gap: 14, paddingHorizontal: 16, paddingVertical: 10 },
  title: { fontSize: 18, fontWeight: '600' },
  body: { padding: 16, gap: 10, paddingBottom: 48 },
  addRow: { flexDirection: 'row', gap: 8 },
  input: { flex: 1, borderWidth: 1, borderRadius: 10, paddingHorizontal: 12, paddingVertical: 10 },
  button: { borderRadius: 10, paddingHorizontal: 16, justifyContent: 'center' },
  fileButton: { flexDirection: 'row', alignItems: 'center', gap: 8, borderWidth: 1, borderRadius: 10, padding: 12 },
  empty: { textAlign: 'center', marginTop: 24 },
  row: { flexDirection: 'row', alignItems: 'center', gap: 12, padding: 12, borderRadius: 12 },
  small: { fontSize: 12 },
  bar: { height: 4, borderRadius: 2, marginTop: 6, overflow: 'hidden' },
  barDone: { height: 4 },
  center: { flex: 1, alignItems: 'center', justifyContent: 'center', gap: 10 },
  fileRow: { flexDirection: 'row', alignItems: 'center', gap: 10, paddingVertical: 8 },
  footer: { borderTopWidth: 1, padding: 16, gap: 12 },
  vaultRow: { flexDirection: 'row', alignItems: 'center', gap: 10 },
  wide: { alignItems: 'center', justifyContent: 'center', borderRadius: 10, paddingVertical: 12 },
  overlay: { flex: 1, backgroundColor: '#000000CC', alignItems: 'center', justifyContent: 'center', padding: 24 },
  card: { width: '100%', maxWidth: 420, borderRadius: 16, borderWidth: 1, padding: 20, gap: 10 },
  cardTitle: { fontSize: 17, fontWeight: '700' },
  settings: { gap: 8, marginTop: 24 },
  sectionTitle: { fontSize: 16, fontWeight: '600', marginTop: 8 },
  chips: { flexDirection: 'row', flexWrap: 'wrap', gap: 8 },
  chip: { borderWidth: 1, borderRadius: 16, paddingHorizontal: 14, paddingVertical: 6 },
});
