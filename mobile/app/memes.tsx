import { Ionicons } from '@expo/vector-icons';
import { Image } from 'expo-image';
import { useFocusEffect, useLocalSearchParams, useRouter } from 'expo-router';
import React, { memo, useCallback, useEffect, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  ActivityIndicator,
  BackHandler,
  FlatList,
  Modal,
  Pressable,
  ScrollView,
  StyleSheet,
  TextInput,
  useWindowDimensions,
  View,
} from 'react-native';
import { SafeAreaView, useSafeAreaInsets } from 'react-native-safe-area-context';

import {
  authenticateLocalPrivateAccess,
  EMPTY_MEME_LIBRARY,
  getMemeThumbnail,
  getPairingState,
  importMemes,
  listenMemesChanged,
  listMemes,
  MEME_FACETS,
  prepareLocalPrivatePlayback,
  removeMemes,
  searchMemes,
  sendMemeToPeer,
  setLocalSecureScreen,
  setMemesPrivate,
  type LocalMeme,
  type LocalMemeFacet,
  type LocalMemeLibrary,
  type LocalPairedDevice,
} from '@/src/api';
import { AppText as Text, Chip, ConfirmModal } from '@/src/components';
import { MemeSheet, type MemeSheetMode } from '@/src/features/memes/MemeSheet';
import { TagManager } from '@/src/features/memes/TagManager';
import { createSession } from '@/src/features/privatePlayback/sessionStore';
import { useTheme } from '@/src/theme';

const COLUMNS = 3;
const GAP = 4;

/** Thumbnails are made once natively; this only saves asking again while the screen lives. */
const thumbnails = new Map<string, string | null>();

/**
 * The meme collection: `shared/memes/CONTRACT.md`.
 *
 * Search, the chips and the grid are one view. A long press starts a selection, which is how
 * batch tagging, moving into or out of the vault, sending and deleting are reached.
 */
export default function MemesScreen() {
  const { t } = useTranslation();
  const { colors } = useTheme();
  const router = useRouter();
  const insets = useSafeAreaInsets();
  const { width } = useWindowDimensions();
  const params = useLocalSearchParams<{ prompt?: string }>();

  const [library, setLibrary] = useState<LocalMemeLibrary>(EMPTY_MEME_LIBRARY);
  const [loading, setLoading] = useState(true);
  const [query, setQuery] = useState('');
  const [facets, setFacets] = useState<Set<LocalMemeFacet>>(new Set());
  const [people, setPeople] = useState<Set<string>>(new Set());
  const [onlyUntagged, setOnlyUntagged] = useState(false);
  const [onlyPrivate, setOnlyPrivate] = useState(false);
  const [visibleIds, setVisibleIds] = useState<string[] | null>(null);
  const [selection, setSelection] = useState<Set<string>>(new Set());
  const [sheet, setSheet] = useState<{ ids: string[]; mode: MemeSheetMode } | null>(null);
  const [managingTags, setManagingTags] = useState(false);
  const [pickingPeople, setPickingPeople] = useState(false);
  const [sendingTo, setSendingTo] = useState<LocalPairedDevice[] | null>(null);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [message, setMessage] = useState<string | null>(null);

  const reload = useCallback(async () => {
    try {
      setLibrary(await listMemes());
    } catch (e) {
      setMessage(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
    }
  }, []);

  useFocusEffect(
    useCallback(() => {
      void reload();
    }, [reload]),
  );

  useEffect(() => {
    const subscription = listenMemesChanged(() => void reload());
    return () => subscription.remove();
  }, [reload]);

  // The quick prompt's notification opens this screen on the new meme.
  useEffect(() => {
    if (params.prompt) setSheet({ ids: [String(params.prompt)], mode: 'prompt' });
  }, [params.prompt]);

  useEffect(() => {
    let live = true;
    const filter = {
      facets: [...facets],
      people: [...people],
      onlyUntagged,
      onlyPrivate,
    };
    searchMemes(query, filter)
      .then((ids) => live && setVisibleIds(ids))
      .catch(() => live && setVisibleIds(null));
    return () => {
      live = false;
    };
  }, [facets, library, onlyPrivate, onlyUntagged, people, query]);

  const byId = useMemo(() => new Map(library.items.map((item) => [item.id, item])), [library.items]);
  const visible = useMemo(
    () => (visibleIds ?? library.items.map((item) => item.id)).map((id) => byId.get(id)).filter(Boolean) as LocalMeme[],
    [byId, library.items, visibleIds],
  );
  const untagged = useMemo(() => library.items.filter((item) => item.taggedAt === 0), [library.items]);
  const selecting = selection.size > 0;
  const chosen = useMemo(() => library.items.filter((item) => selection.has(item.id)), [library.items, selection]);

  useFocusEffect(
    useCallback(() => {
      if (!selecting) return undefined;
      const sub = BackHandler.addEventListener('hardwareBackPress', () => {
        setSelection(new Set());
        return true;
      });
      return () => sub.remove();
    }, [selecting]),
  );

  const toggleSelected = useCallback((id: string) => {
    setSelection((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }, []);

  const onImport = useCallback(async () => {
    const result = await importMemes().catch(() => null);
    if (result?.success) {
      setMessage(
        result.failed
          ? t('memes.importFailed', { count: result.failed })
          : t('memes.importDone', { count: result.imported ?? 0 }),
      );
    }
    await reload();
  }, [reload, t]);

  const setPrivate = useCallback(
    async (ids: string[], makePrivate: boolean) => {
      let result = await setMemesPrivate(ids, makePrivate);
      if (!result.success && result.code === 'PRIVATE_VAULT_LOCKED') {
        const auth = await authenticateLocalPrivateAccess('view').catch(() => null);
        if (!auth?.granted) return;
        result = await setMemesPrivate(ids, makePrivate);
      }
      if (!result.success) setMessage(t('memes.moveFailed', { count: result.failed ?? ids.length }));
      setSelection(new Set());
      await reload();
    },
    [reload, t],
  );

  const startSend = useCallback(async () => {
    if (chosen.some((item) => item.isPrivate)) {
      setMessage(t('memes.privateNotSent'));
      return;
    }
    const state = await getPairingState();
    setSendingTo(state.peers.filter((peer) => peer.connected));
  }, [chosen, t]);

  const send = useCallback(
    async (peer: LocalPairedDevice) => {
      setSendingTo(null);
      // One at a time, as the protocol has it: the first of a selection.
      const first = chosen[0];
      if (!first) return;
      const ok = await sendMemeToPeer(peer.fingerprint, first.id).catch(() => false);
      setMessage(ok ? t('memes.sent', { name: peer.name }) : t('memes.sendFailed'));
      setSelection(new Set());
    },
    [chosen, t],
  );

  const playPrivate = useCallback(
    async (meme: LocalMeme) => {
      if (!meme.vaultId) return;
      const auth = await authenticateLocalPrivateAccess('view').catch(() => null);
      if (!auth?.granted) return;
      const prepared = await prepareLocalPrivatePlayback(meme.vaultId).catch(() => null);
      if (!prepared?.success || !prepared.tempUri) return;
      const session = createSession({ itemId: meme.vaultId, title: '', tempUri: prepared.tempUri, mimeType: prepared.mimeType });
      await setLocalSecureScreen(true).catch(() => undefined);
      setSheet(null);
      router.push({ pathname: '/private-player', params: { sid: session.sid } });
    },
    [router],
  );

  useEffect(() => {
    if (!message) return;
    const timer = setTimeout(() => setMessage(null), 4000);
    return () => clearTimeout(timer);
  }, [message]);

  const tile = Math.floor((width - GAP * (COLUMNS + 1)) / COLUMNS);

  const toggleFacet = (facet: LocalMemeFacet) =>
    setFacets((prev) => {
      const next = new Set(prev);
      if (next.has(facet)) next.delete(facet);
      else next.add(facet);
      return next;
    });

  return (
    <SafeAreaView style={[styles.fill, { backgroundColor: colors.background }]} edges={['top', 'left', 'right']}>
      <View style={styles.header}>
        {selecting ? (
          <>
            <Pressable onPress={() => setSelection(new Set())} hitSlop={10} accessibilityRole="button">
              <Ionicons name="close" size={24} color={colors.text} />
            </Pressable>
            <Text style={[styles.title, { color: colors.text }]}>{t('memes.selected', { count: selection.size })}</Text>
          </>
        ) : (
          <>
            <Pressable onPress={() => router.back()} hitSlop={10} accessibilityRole="button">
              <Ionicons name="arrow-back" size={24} color={colors.text} />
            </Pressable>
            <View style={styles.fill}>
              <Text style={[styles.title, { color: colors.text }]}>{t('memes.title')}</Text>
              {untagged.length > 0 ? (
                <Text style={[styles.subtitle, { color: colors.textMuted }]}>
                  {t('memes.untaggedCount', { count: untagged.length })}
                </Text>
              ) : null}
            </View>
            {untagged.length > 0 ? (
              <HeaderButton icon="albums-outline" label={t('memes.review')}
                onPress={() => setSheet({ ids: untagged.map((item) => item.id), mode: 'review' })} />
            ) : null}
            <HeaderButton icon="pricetags-outline" label={t('memes.tags')} onPress={() => setManagingTags(true)} />
            <HeaderButton icon="add" label={t('memes.import')} onPress={onImport} />
          </>
        )}
      </View>

      <TextInput
        value={query}
        onChangeText={setQuery}
        placeholder={t('memes.searchPlaceholder')}
        placeholderTextColor={colors.textSubtle}
        style={[styles.search, { color: colors.text, backgroundColor: colors.surface }]}
        autoCorrect={false}
        clearButtonMode="while-editing"
      />

      <View>
        <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={styles.chips}>
          {MEME_FACETS.map((facet) => (
            <Chip key={facet} label={t(`memes.facet.${facet}`)} active={facets.has(facet)} color={colors.accent}
              onPress={() => toggleFacet(facet)} />
          ))}
          <Chip label={t('memes.untagged')} active={onlyUntagged} color={colors.warning}
            onPress={() => setOnlyUntagged((v) => !v)} />
          {library.hasPrivate ? (
            // Locked, the private memes are not in the list at all; the chip is the way in.
            <Chip
              label={t('memes.private')}
              iconName={library.vaultUnlocked ? 'lock-open-outline' : 'lock-closed-outline'}
              active={onlyPrivate && library.vaultUnlocked}
              color={colors.info}
              onPress={async () => {
                if (!library.vaultUnlocked) {
                  const auth = await authenticateLocalPrivateAccess('view').catch(() => null);
                  if (!auth?.granted) return;
                  setOnlyPrivate(true);
                  await reload();
                  return;
                }
                setOnlyPrivate((v) => !v);
              }}
            />
          ) : null}
          {library.people.length > 0 ? (
            <Chip
              label={people.size ? t('memes.peopleCount', { count: people.size }) : t('memes.people')}
              iconName="people-outline"
              active={people.size > 0}
              color={colors.info}
              onPress={() => setPickingPeople(true)}
            />
          ) : null}
        </ScrollView>
      </View>


      {loading ? (
        <ActivityIndicator style={styles.fill} color={colors.accent} />
      ) : library.items.length === 0 ? (
        <View style={[styles.fill, styles.empty]}>
          <Ionicons name="happy-outline" size={48} color={colors.textMuted} />
          <Text style={[styles.emptyTitle, { color: colors.text }]}>{t('memes.empty')}</Text>
          <Text style={[styles.emptyHint, { color: colors.textMuted }]}>{t('memes.emptyHint')}</Text>
          <Pressable onPress={onImport} style={[styles.emptyButton, { backgroundColor: colors.accent }]}>
            <Text style={{ color: colors.primaryText }}>{t('memes.import')}</Text>
          </Pressable>
        </View>
      ) : (
        <FlatList
          data={visible}
          keyExtractor={(item) => item.id}
          numColumns={COLUMNS}
          contentContainerStyle={{ padding: GAP / 2, paddingBottom: insets.bottom + (selecting ? 90 : 16) }}
          ListEmptyComponent={<Text style={[styles.noResults, { color: colors.textMuted }]}>{t('memes.noResults')}</Text>}
          renderItem={({ item }) => (
            <MemeTile
              meme={item}
              size={tile}
              selected={selection.has(item.id)}
              onPress={() => (selecting ? toggleSelected(item.id) : setSheet({ ids: [item.id], mode: 'detail' }))}
              onLongPress={() => toggleSelected(item.id)}
            />
          )}
        />
      )}

      {selecting ? (
        <View style={[styles.bar, { backgroundColor: colors.surface, bottom: insets.bottom + 12 }]}>
          <BarButton icon="pricetag-outline" label={t('memes.tag')}
            onPress={() => setSheet({ ids: [...selection], mode: 'batch' })} />
          {chosen.every((item) => item.isPrivate) ? (
            <BarButton icon="lock-open-outline" label={t('memes.makePublic')} onPress={() => setPrivate([...selection], false)} />
          ) : chosen.every((item) => !item.isPrivate) ? (
            <BarButton icon="lock-closed-outline" label={t('memes.makePrivate')} onPress={() => setPrivate([...selection], true)} />
          ) : null}
          {selection.size === 1 ? <BarButton icon="send-outline" label={t('memes.send')} onPress={startSend} /> : null}
          <BarButton icon="trash-outline" label={t('memes.delete')} onPress={() => setConfirmDelete(true)} danger />
        </View>
      ) : null}

      {message ? (
        <View style={[styles.toast, { backgroundColor: colors.surfaceActive, bottom: insets.bottom + (selecting ? 90 : 20) }]}>
          <Text style={{ color: colors.text }}>{message}</Text>
        </View>
      ) : null}

      {sheet ? (
        <MemeSheet
          ids={sheet.ids}
          mode={sheet.mode}
          library={library}
          onClose={() => {
            setSheet(null);
            setSelection(new Set());
            if (params.prompt) router.setParams({ prompt: undefined });
          }}
          onChanged={reload}
          onPlayPrivate={playPrivate}
          onSetPrivate={setPrivate}
        />
      ) : null}

      {managingTags ? <TagManager library={library} onClose={() => setManagingTags(false)} onChanged={reload} /> : null}

      <Modal visible={pickingPeople} transparent animationType="fade" onRequestClose={() => setPickingPeople(false)}>
        <Pressable style={[styles.scrim, { backgroundColor: colors.overlay }]} onPress={() => setPickingPeople(false)}>
          <View style={[styles.picker, { backgroundColor: colors.surface }]}>
            <ScrollView contentContainerStyle={styles.pickerList}>
              {[...library.people].sort((a, b) => a.name.localeCompare(b.name, 'tr')).map((person) => (
                <Chip
                  key={person.id}
                  label={person.name}
                  active={people.has(person.id)}
                  color={colors.info}
                  onPress={() => setPeople((prev) => {
                    const next = new Set(prev);
                    if (next.has(person.id)) next.delete(person.id);
                    else next.add(person.id);
                    return next;
                  })}
                />
              ))}
            </ScrollView>
          </View>
        </Pressable>
      </Modal>

      <Modal visible={sendingTo != null} transparent animationType="fade" onRequestClose={() => setSendingTo(null)}>
        <Pressable style={[styles.scrim, { backgroundColor: colors.overlay }]} onPress={() => setSendingTo(null)}>
          <View style={[styles.picker, { backgroundColor: colors.surface }]}>
            <Text style={[styles.pickerTitle, { color: colors.text }]}>{t('memes.sendTo')}</Text>
            {(sendingTo ?? []).length === 0 ? (
              <Text style={{ color: colors.textMuted }}>{t('memes.sendFailed')}</Text>
            ) : (
              (sendingTo ?? []).map((peer) => (
                <Pressable key={peer.fingerprint} onPress={() => send(peer)} style={styles.peer}>
                  <Ionicons name="laptop-outline" size={20} color={colors.text} />
                  <Text style={{ color: colors.text }}>{peer.name}</Text>
                </Pressable>
              ))
            )}
          </View>
        </Pressable>
      </Modal>

      <ConfirmModal
        visible={confirmDelete}
        config={{
          title: t('memes.deleteTitle', { count: selection.size }),
          message: t('memes.deleteBody'),
          confirm: t('memes.delete'),
          destructive: true,
        }}
        onCancel={() => setConfirmDelete(false)}
        onConfirm={async () => {
          setConfirmDelete(false);
          await removeMemes([...selection]);
          setSelection(new Set());
          await reload();
        }}
      />
    </SafeAreaView>
  );
}

const MemeTile = memo(function MemeTile({
  meme,
  size,
  selected,
  onPress,
  onLongPress,
}: {
  meme: LocalMeme;
  size: number;
  selected: boolean;
  onPress: () => void;
  onLongPress: () => void;
}) {
  const { colors } = useTheme();
  const key = `${meme.id}:${meme.isPrivate ? 'p' : 'o'}`;
  const [uri, setUri] = useState<string | null>(thumbnails.get(key) ?? null);

  useEffect(() => {
    // Keyed by privacy too: a meme moved into the vault must not keep its old picture.
    thumbnails.delete(`${meme.id}:${meme.isPrivate ? 'o' : 'p'}`);
    if (thumbnails.has(key)) {
      setUri(thumbnails.get(key) ?? null);
      return;
    }
    setUri(null);
    let live = true;
    getMemeThumbnail(meme.id)
      .then((next) => {
        thumbnails.set(key, next);
        if (live) setUri(next);
      })
      .catch(() => undefined);
    return () => {
      live = false;
    };
  }, [key, meme.id, meme.isPrivate]);

  return (
    <Pressable
      onPress={onPress}
      onLongPress={onLongPress}
      style={[styles.tile, { width: size, height: size, backgroundColor: colors.surface, borderColor: selected ? colors.accent : 'transparent' }]}
    >
      {uri ? (
        <Image source={{ uri }} style={StyleSheet.absoluteFill} contentFit="cover" recyclingKey={key} />
      ) : (
        <Ionicons name={meme.isPrivate ? 'lock-closed' : meme.kind === 'video' ? 'film-outline' : 'image-outline'}
          size={26} color={colors.textMuted} />
      )}
      <View style={styles.badges}>
        {meme.kind === 'video' ? <Ionicons name="play" size={12} color="#fff" /> : null}
        {meme.isPrivate ? <Ionicons name="lock-closed" size={12} color="#fff" /> : null}
        {meme.taggedAt === 0 ? <View style={[styles.dot, { backgroundColor: colors.accent }]} /> : null}
      </View>
      {selected ? (
        <View style={styles.check}>
          <Ionicons name="checkmark-circle" size={22} color={colors.accent} />
        </View>
      ) : null}
    </Pressable>
  );
});

function HeaderButton({ icon, label, onPress }: { icon: keyof typeof Ionicons.glyphMap; label: string; onPress: () => void }) {
  const { colors } = useTheme();
  return (
    <Pressable onPress={onPress} hitSlop={8} accessibilityRole="button" accessibilityLabel={label} style={styles.headerButton}>
      <Ionicons name={icon} size={22} color={colors.text} />
    </Pressable>
  );
}

function BarButton({
  icon,
  label,
  onPress,
  danger,
}: {
  icon: keyof typeof Ionicons.glyphMap;
  label: string;
  onPress: () => void;
  danger?: boolean;
}) {
  const { colors } = useTheme();
  const tint = danger ? colors.error : colors.text;
  return (
    <Pressable onPress={onPress} style={styles.barButton} accessibilityRole="button">
      <Ionicons name={icon} size={20} color={tint} />
      <Text style={[styles.barLabel, { color: tint }]} numberOfLines={1}>{label}</Text>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  fill: { flex: 1 },
  header: { flexDirection: 'row', alignItems: 'center', gap: 14, paddingHorizontal: 16, paddingVertical: 10 },
  title: { fontSize: 20, fontWeight: '700' },
  subtitle: { fontSize: 13 },
  headerButton: { padding: 4 },
  search: { marginHorizontal: 12, borderRadius: 10, paddingHorizontal: 12, paddingVertical: 10, fontSize: 16 },
  chips: { gap: 8, paddingHorizontal: 12, paddingVertical: 10 },
  unlock: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
    marginHorizontal: 12,
    marginBottom: 8,
    borderRadius: 10,
    padding: 10,
  },
  empty: { alignItems: 'center', justifyContent: 'center', gap: 10, padding: 32 },
  emptyTitle: { fontSize: 18, fontWeight: '600' },
  emptyHint: { textAlign: 'center' },
  emptyButton: { marginTop: 8, borderRadius: 12, paddingHorizontal: 24, paddingVertical: 12 },
  noResults: { textAlign: 'center', padding: 32 },
  tile: {
    margin: GAP / 2,
    borderRadius: 6,
    overflow: 'hidden',
    alignItems: 'center',
    justifyContent: 'center',
    borderWidth: 2,
  },
  badges: {
    position: 'absolute',
    left: 4,
    bottom: 4,
    flexDirection: 'row',
    alignItems: 'center',
    gap: 4,
    backgroundColor: 'rgba(0,0,0,0.45)',
    borderRadius: 8,
    paddingHorizontal: 5,
    paddingVertical: 2,
  },
  dot: { width: 7, height: 7, borderRadius: 4 },
  check: { position: 'absolute', top: 4, right: 4 },
  bar: {
    position: 'absolute',
    left: 12,
    right: 12,
    flexDirection: 'row',
    justifyContent: 'space-around',
    borderRadius: 16,
    paddingVertical: 10,
    elevation: 6,
  },
  barButton: { alignItems: 'center', gap: 2, minWidth: 64 },
  barLabel: { fontSize: 12 },
  toast: { position: 'absolute', alignSelf: 'center', borderRadius: 12, paddingHorizontal: 16, paddingVertical: 10 },
  scrim: { flex: 1, justifyContent: 'center', padding: 24 },
  picker: { borderRadius: 16, padding: 16, gap: 10, maxHeight: '70%' },
  pickerTitle: { fontSize: 17, fontWeight: '600' },
  pickerList: { flexDirection: 'row', flexWrap: 'wrap', gap: 8 },
  peer: { flexDirection: 'row', alignItems: 'center', gap: 10, paddingVertical: 10 },
});
