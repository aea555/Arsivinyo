import { Ionicons } from '@expo/vector-icons';
import { Image } from 'expo-image';
import { useFocusEffect, useRouter, type Href } from 'expo-router';
import React, { memo, useCallback, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { ActivityIndicator, FlatList, Pressable, ScrollView, StyleSheet, TextInput, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import {
  getCatalog,
  getLibrary,
  installRecommended,
  listAddons,
  listRows,
  type WatchItem,
  type WatchPreview,
  type WatchRow,
} from '@/src/api';
import { AppText as Text } from '@/src/components';
import { openTitle } from '@/src/features/watch/open';
import { useTheme } from '@/src/theme';

/**
 * Watch: continue watching, then every add-on's catalogs as rows, or search across them
 * (`shared/watch/CONTRACT.md`, phase 1). Each row loads on its own, so a slow add-on only
 * leaves its own row waiting.
 */
export default function WatchScreen() {
  const { t } = useTranslation();
  const { colors } = useTheme();
  const router = useRouter();
  const [hasAddons, setHasAddons] = useState<boolean | null>(null);
  const [rows, setRows] = useState<WatchRow[]>([]);
  const [library, setLibrary] = useState<{ continue: WatchItem[]; saved: WatchItem[] }>({ continue: [], saved: [] });
  const [query, setQuery] = useState('');
  const [submitted, setSubmitted] = useState('');
  const [installing, setInstalling] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  /** How each row ended, by its key: whether it found anything. */
  const [settled, setSettled] = useState<Record<string, boolean>>({});
  const settle = useCallback((key: string, found: boolean) => {
    setSettled((prev) => (prev[key] === found ? prev : { ...prev, [key]: found }));
  }, []);
  const keys = rows.map((row) => `${row.addonKey}/${row.type}/${row.id}/${submitted}`);
  const nothingCame =
    (submitted.length > 0 && rows.length === 0) ||
    (rows.length > 0 && keys.every((key) => key in settled) && keys.every((key) => !settled[key]));

  const reload = useCallback(async () => {
    const addons = await listAddons().catch(() => []);
    setHasAddons(addons.length > 0);
    setRows(await listRows(submitted.length > 0).catch(() => []));
    setLibrary(await getLibrary().catch(() => ({ continue: [], saved: [] })));
  }, [submitted]);

  useFocusEffect(
    useCallback(() => {
      void reload();
    }, [reload]),
  );

  const startWithRecommended = async () => {
    setInstalling(true);
    setProblem(null);
    const result = await installRecommended().catch(() => null);
    setInstalling(false);
    if (!result) setProblem(t('watch.installFailed', { code: '' }));
    else if (result.failed.length > 0) setProblem(t('watch.recommended.failed', { names: result.failed.join(', ') }));
    await reload();
  };

  return (
    <SafeAreaView style={[styles.fill, { backgroundColor: colors.background }]} edges={['top', 'left', 'right']}>
      <View style={styles.header}>
        <Pressable onPress={() => router.back()} hitSlop={10} accessibilityRole="button">
          <Ionicons name="arrow-back" size={24} color={colors.text} />
        </Pressable>
        <Text style={[styles.title, styles.fill, { color: colors.text }]}>{t('watch.title')}</Text>
        <Pressable
          onPress={() => router.push('/watch-addons' as Href)}
          hitSlop={10}
          accessibilityRole="button"
          accessibilityLabel={t('watch.addons.title')}
        >
          <Ionicons name="extension-puzzle-outline" size={22} color={colors.text} />
        </Pressable>
      </View>

      {hasAddons === null ? (
        <ActivityIndicator style={styles.fill} color={colors.accent} />
      ) : !hasAddons ? (
        <View style={[styles.fill, styles.empty]}>
          <Ionicons name="film-outline" size={48} color={colors.textMuted} />
          <Text style={[styles.emptyTitle, { color: colors.text }]}>{t('watch.emptyTitle')}</Text>
          <Text style={[styles.center, { color: colors.textMuted }]}>{t('watch.emptyHint')}</Text>
          <Pressable onPress={startWithRecommended} disabled={installing} style={[styles.button, { backgroundColor: colors.accent }]}>
            {installing ? <ActivityIndicator color={colors.primaryText} /> : (
              <Text style={{ color: colors.primaryText }}>{t('watch.recommended.setUp')}</Text>
            )}
          </Pressable>
          <Pressable onPress={() => router.push('/watch-addons' as Href)}>
            <Text style={{ color: colors.accent }}>{t('watch.addOther')}</Text>
          </Pressable>
          {problem ? <Text style={{ color: colors.error }}>{problem}</Text> : null}
        </View>
      ) : (
        <>
          <TextInput
            value={query}
            onChangeText={(text) => {
              setQuery(text);
              if (!text.trim()) setSubmitted('');
            }}
            onSubmitEditing={() => setSubmitted(query.trim())}
            returnKeyType="search"
            placeholder={t('watch.searchPlaceholder')}
            placeholderTextColor={colors.textSubtle}
            style={[styles.search, { color: colors.text, backgroundColor: colors.surface }]}
            autoCorrect={false}
          />
          <ScrollView contentContainerStyle={styles.body}>
            {!submitted && library.continue.length > 0 ? (
              <Shelf title={t('watch.continue')}>
                {library.continue.map((item) => (
                  <Poster
                    key={item.id}
                    name={item.name}
                    poster={item.poster}
                    progress={item.progress ? item.progress.positionMs / Math.max(1, item.progress.durationMs) : undefined}
                    onPress={() => openTitle(router, item)}
                  />
                ))}
              </Shelf>
            ) : null}
            {!submitted && library.saved.length > 0 ? (
              <Shelf title={t('watch.saved')}>
                {library.saved.map((item) => (
                  <Poster key={item.id} name={item.name} poster={item.poster} onPress={() => openTitle(router, item)} />
                ))}
              </Shelf>
            ) : null}
            {rows.map((row) => {
              const key = `${row.addonKey}/${row.type}/${row.id}/${submitted}`;
              return <CatalogRow key={key} row={row} search={submitted} onSettled={(found) => settle(key, found)} />;
            })}
            {/* Said once, and only when nothing at all came back: a row that failed or found
                nothing is left out rather than shown as an error. */}
            {nothingCame ? (
              <Text style={[styles.center, { color: colors.textMuted }]}>
                {submitted ? t('watch.noSearch') : t('watch.nothingAnswered')}
              </Text>
            ) : null}
          </ScrollView>
        </>
      )}
    </SafeAreaView>
  );
}

function Shelf({ title, children }: { title: string; children: React.ReactNode }) {
  const { colors } = useTheme();
  return (
    <View style={styles.shelf}>
      <Text style={[styles.shelfTitle, { color: colors.text }]}>{title}</Text>
      <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={styles.shelfRow}>
        {children}
      </ScrollView>
    </View>
  );
}

/** One catalog of one add-on, fetched on its own. */
/**
 * One catalog of one add-on, fetched on its own. A catalog that fails or finds nothing is not
 * shown at all: an add-on's server error is not something to read row by row. It says how it
 * ended through `onSettled`, so the screen can say so once if nothing came back anywhere.
 */
const CatalogRow = memo(function CatalogRow({
  row,
  search,
  onSettled,
}: {
  row: WatchRow;
  search: string;
  onSettled: (found: boolean) => void;
}) {
  const { t } = useTranslation();
  const { colors } = useTheme();
  const router = useRouter();
  const [items, setItems] = useState<WatchPreview[] | null>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let live = true;
    getCatalog(row, search ? { search } : {})
      .then((result) => {
        if (!live) return;
        if (result.success) setItems(result.items);
        else setFailed(true);
      })
      .catch(() => live && setFailed(true));
    return () => {
      live = false;
    };
  }, [row, search]);

  useEffect(() => {
    if (failed) onSettled(false);
    else if (items) onSettled(items.length > 0);
  }, [failed, items, onSettled]);

  if (failed || (items && items.length === 0)) return null;
  return (
    <View style={styles.shelf}>
      <Text style={[styles.shelfTitle, { color: colors.text }]} numberOfLines={1}>
        {row.name} <Text style={{ color: colors.textMuted }}>· {t(`watch.type.${row.type}`, { defaultValue: row.type })} · {row.addonName}</Text>
      </Text>
      {items === null ? (
        <ActivityIndicator color={colors.accent} style={styles.rowLoading} />
      ) : (
        <FlatList
          horizontal
          data={items}
          keyExtractor={(item) => item.id}
          showsHorizontalScrollIndicator={false}
          contentContainerStyle={styles.shelfRow}
          renderItem={({ item }) => <Poster name={item.name} poster={item.poster} onPress={() => openTitle(router, item)} />}
        />
      )}
    </View>
  );
});

function Poster({ name, poster, progress, onPress }: { name: string; poster?: string | null; progress?: number; onPress: () => void }) {
  const { colors } = useTheme();
  return (
    <Pressable onPress={onPress} style={styles.poster} accessibilityRole="button" accessibilityLabel={name}>
      <View style={[styles.posterImage, { backgroundColor: colors.surface }]}>
        {poster ? (
          <Image source={{ uri: poster }} style={StyleSheet.absoluteFill} contentFit="cover" transition={150} />
        ) : (
          <Text style={[styles.posterFallback, { color: colors.textMuted }]} numberOfLines={3}>{name}</Text>
        )}
        {progress != null ? (
          <View style={[styles.progressTrack, { backgroundColor: 'rgba(0,0,0,0.5)' }]}>
            <View style={[styles.progressFill, { width: `${Math.min(100, Math.max(2, progress * 100))}%`, backgroundColor: colors.accent }]} />
          </View>
        ) : null}
      </View>
      <Text style={[styles.posterName, { color: colors.text }]} numberOfLines={1}>{name}</Text>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  fill: { flex: 1 },
  header: { flexDirection: 'row', alignItems: 'center', gap: 14, paddingHorizontal: 16, paddingVertical: 10 },
  title: { fontSize: 20, fontWeight: '700' },
  empty: { alignItems: 'center', justifyContent: 'center', gap: 12, padding: 32 },
  emptyTitle: { fontSize: 18, fontWeight: '600' },
  center: { textAlign: 'center', padding: 16 },
  button: { borderRadius: 12, paddingHorizontal: 24, paddingVertical: 12, minWidth: 200, alignItems: 'center' },
  search: { marginHorizontal: 12, marginBottom: 6, borderRadius: 10, paddingHorizontal: 12, paddingVertical: 10, fontSize: 16 },
  body: { paddingBottom: 40 },
  shelf: { paddingTop: 14, gap: 8 },
  shelfTitle: { fontSize: 16, fontWeight: '600', paddingHorizontal: 16 },
  shelfRow: { gap: 10, paddingHorizontal: 16 },
  rowLoading: { alignSelf: 'flex-start', marginLeft: 16, height: 150 },
  poster: { width: 110, gap: 4 },
  posterImage: { width: 110, height: 163, borderRadius: 8, overflow: 'hidden', justifyContent: 'flex-end' },
  posterFallback: { padding: 8, fontSize: 13 },
  posterName: { fontSize: 12 },
  progressTrack: { height: 4, width: '100%' },
  progressFill: { height: 4 },
});
