import { Ionicons } from '@expo/vector-icons';
import { Image } from 'expo-image';
import { useFocusEffect, useRouter } from 'expo-router';
import React, { memo, useCallback, useEffect, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { ActivityIndicator, BackHandler, FlatList, Modal, Pressable, StyleSheet, TextInput, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import {
  deleteLocalVideos,
  getLocalVideoThumbnail,
  isLocalVideosSupported,
  listLocalVideos,
  onLocalVideosChanged,
  renameLocalVideo,
  type LocalVideo,
} from '@/src/api';
import { AppText as Text, AppTextInput, ConfirmModal } from '@/src/components';
import { openVideo } from '@/src/features/videos/open';
import { useTheme } from '@/src/theme';

function clock(ms: number): string {
  const total = Math.max(0, Math.round(ms / 1000));
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  const s = total % 60;
  return `${h > 0 ? `${h}:${String(m).padStart(2, '0')}` : m}:${String(s).padStart(2, '0')}`;
}

function size(bytes: number): string {
  if (bytes >= 1024 ** 3) return `${(bytes / 1024 ** 3).toFixed(1)} GB`;
  return `${Math.max(1, Math.round(bytes / 1024 ** 2))} MB`;
}

/**
 * The video library: every video the app saved, newest first. A tap plays one, with the
 * list as shown for its queue; a long press selects, to rename or delete.
 */
export default function VideosScreen() {
  const { t } = useTranslation();
  const { colors } = useTheme();
  const router = useRouter();
  const supported = isLocalVideosSupported();
  const [videos, setVideos] = useState<LocalVideo[] | null>(null);
  const [query, setQuery] = useState('');
  const [selected, setSelected] = useState<Set<string>>(() => new Set());
  const [deleting, setDeleting] = useState(false);
  const [busy, setBusy] = useState(false);
  const [renaming, setRenaming] = useState<LocalVideo | null>(null);
  const [name, setName] = useState('');

  const reload = useCallback(async () => {
    if (!supported) return;
    setVideos(await listLocalVideos().catch(() => []));
  }, [supported]);

  // Listened to only while this screen is the one shown: the player says so each time it
  // writes a place down, the last time as it closes, which is after this screen is back.
  useFocusEffect(
    useCallback(() => {
      void reload();
      const subscription = onLocalVideosChanged(() => void reload());
      return () => subscription.remove();
    }, [reload]),
  );

  // Back leaves the selection before it leaves the screen.
  const selecting = selected.size > 0;
  useEffect(() => {
    if (!selecting) return undefined;
    const subscription = BackHandler.addEventListener('hardwareBackPress', () => {
      setSelected(new Set());
      return true;
    });
    return () => subscription.remove();
  }, [selecting]);

  const shown = useMemo(() => {
    const words = query.trim().toLocaleLowerCase();
    if (!videos || !words) return videos ?? [];
    return videos.filter((v) => `${v.title} ${v.uploader ?? ''}`.toLocaleLowerCase().includes(words));
  }, [videos, query]);

  const toggle = useCallback((id: string) => {
    setSelected((prev) => {
      const next = new Set(prev);
      if (!next.delete(id)) next.add(id);
      return next;
    });
  }, []);

  const press = useCallback(
    (video: LocalVideo) => {
      if (selecting) toggle(video.id);
      else openVideo(shown, shown.findIndex((v) => v.id === video.id));
    },
    [selecting, toggle, shown],
  );

  const remove = async () => {
    setBusy(true);
    await deleteLocalVideos([...selected]).catch(() => 0);
    setBusy(false);
    setDeleting(false);
    setSelected(new Set());
    await reload();
  };

  const rename = async () => {
    const title = name.trim();
    if (!renaming || !title) return;
    await renameLocalVideo(renaming.id, title).catch(() => null);
    setRenaming(null);
    setSelected(new Set());
    await reload();
  };

  const only = selected.size === 1 ? videos?.find((v) => selected.has(v.id)) ?? null : null;

  return (
    <SafeAreaView style={[styles.fill, { backgroundColor: colors.background }]} edges={['top', 'left', 'right', 'bottom']}>
      {selecting ? (
        <View style={styles.header}>
          <Pressable onPress={() => setSelected(new Set())} hitSlop={10} accessibilityRole="button" accessibilityLabel={t('common.cancel')}>
            <Ionicons name="close" size={24} color={colors.text} />
          </Pressable>
          <Text style={[styles.title, styles.fill, { color: colors.text }]} numberOfLines={1}>
            {t('videos.selected', { count: selected.size })}
          </Text>
          {only ? (
            <Pressable
              onPress={() => {
                setName(only.title);
                setRenaming(only);
              }}
              hitSlop={10}
              accessibilityRole="button"
              accessibilityLabel={t('videos.rename')}
            >
              <Ionicons name="pencil-outline" size={22} color={colors.text} />
            </Pressable>
          ) : null}
          <Pressable onPress={() => setDeleting(true)} hitSlop={10} accessibilityRole="button" accessibilityLabel={t('common.delete')}>
            <Ionicons name="trash-outline" size={22} color={colors.error} />
          </Pressable>
        </View>
      ) : (
        <View style={styles.header}>
          <Pressable onPress={() => router.back()} hitSlop={10} accessibilityRole="button" accessibilityLabel={t('common.back')}>
            <Ionicons name="arrow-back" size={24} color={colors.text} />
          </Pressable>
          <Text style={[styles.title, styles.fill, { color: colors.text }]}>{t('videos.title')}</Text>
        </View>
      )}

      {!supported ? (
        <View style={[styles.fill, styles.empty]}>
          <Text style={[styles.center, { color: colors.textMuted }]}>{t('videos.unsupported')}</Text>
        </View>
      ) : videos === null ? (
        <ActivityIndicator style={styles.fill} color={colors.accent} />
      ) : videos.length === 0 ? (
        <View style={[styles.fill, styles.empty]}>
          <Ionicons name="videocam-outline" size={48} color={colors.textMuted} />
          <Text style={[styles.emptyTitle, { color: colors.text }]}>{t('videos.empty')}</Text>
          <Text style={[styles.center, { color: colors.textMuted }]}>{t('videos.emptyHint')}</Text>
        </View>
      ) : (
        <>
          <TextInput
            value={query}
            onChangeText={setQuery}
            placeholder={t('videos.search')}
            placeholderTextColor={colors.textSubtle}
            style={[styles.search, { color: colors.text, backgroundColor: colors.surface }]}
            autoCorrect={false}
            returnKeyType="search"
          />
          <FlatList
            data={shown}
            keyExtractor={(video) => video.id}
            contentContainerStyle={styles.list}
            keyboardShouldPersistTaps="handled"
            ListEmptyComponent={<Text style={[styles.center, { color: colors.textMuted }]}>{t('videos.noMatch')}</Text>}
            renderItem={({ item }) => (
              <Row video={item} chosen={selected.has(item.id)} onPress={press} onLongPress={toggle} />
            )}
          />
        </>
      )}

      <ConfirmModal
        visible={deleting}
        busy={busy}
        config={
          deleting
            ? {
                title: t('videos.deleteTitle', { count: selected.size }),
                message: t('videos.deleteMessage'),
                confirm: t('common.delete'),
                destructive: true,
              }
            : null
        }
        onCancel={() => setDeleting(false)}
        onConfirm={() => void remove()}
      />

      <Modal visible={renaming != null} transparent animationType="fade" onRequestClose={() => setRenaming(null)}>
        <View style={[styles.overlay, { backgroundColor: colors.overlay }]}>
          <View style={[styles.card, { backgroundColor: colors.surface, borderColor: colors.border }]}>
            <Text style={[styles.cardTitle, { color: colors.text }]}>{t('videos.renameTitle')}</Text>
            <AppTextInput
              value={name}
              onChangeText={setName}
              autoFocus
              selectTextOnFocus
              onSubmitEditing={() => void rename()}
              style={[styles.input, { color: colors.text, borderColor: colors.border }]}
            />
            <View style={styles.buttons}>
              <Pressable onPress={() => setRenaming(null)} style={[styles.button, { backgroundColor: colors.surfaceHover }]}>
                <Text style={{ color: colors.text }}>{t('common.cancel')}</Text>
              </Pressable>
              <Pressable onPress={() => void rename()} style={[styles.button, { backgroundColor: colors.accent }]}>
                <Text style={{ color: colors.primaryText }}>{t('common.save')}</Text>
              </Pressable>
            </View>
          </View>
        </View>
      </Modal>
    </SafeAreaView>
  );
}

const Row = memo(function Row({
  video,
  chosen,
  onPress,
  onLongPress,
}: {
  video: LocalVideo;
  chosen: boolean;
  onPress: (video: LocalVideo) => void;
  onLongPress: (id: string) => void;
}) {
  const { colors } = useTheme();
  const [thumb, setThumb] = useState<string | null>(null);
  useEffect(() => {
    let live = true;
    getLocalVideoThumbnail(video.id)
      .then((path) => live && setThumb(path))
      .catch(() => undefined);
    return () => {
      live = false;
    };
  }, [video.id]);

  const watched = video.durationMs > 0 && video.positionMs > 0 ? video.positionMs / video.durationMs : 0;
  return (
    <Pressable
      onPress={() => onPress(video)}
      onLongPress={() => onLongPress(video.id)}
      style={[styles.row, chosen && { backgroundColor: colors.surfaceHover }]}
      accessibilityRole="button"
      accessibilityLabel={video.title}
      accessibilityState={{ selected: chosen }}
    >
      <View style={[styles.thumb, { backgroundColor: colors.surface }]}>
        {thumb ? (
          <Image source={{ uri: `file://${thumb}` }} style={StyleSheet.absoluteFill} contentFit="cover" />
        ) : (
          <Ionicons name="videocam-outline" size={22} color={colors.textMuted} style={styles.thumbIcon} />
        )}
        {video.durationMs > 0 ? (
          <View style={styles.length}>
            <Text style={styles.lengthText}>{clock(video.durationMs)}</Text>
          </View>
        ) : null}
        {watched > 0 ? (
          <View style={styles.track}>
            <View style={[styles.trackFill, { width: `${Math.min(100, Math.max(3, watched * 100))}%`, backgroundColor: colors.accent }]} />
          </View>
        ) : null}
        {chosen ? (
          <View style={styles.chosen}>
            <Ionicons name="checkmark-circle" size={28} color={colors.accent} />
          </View>
        ) : null}
      </View>
      <View style={styles.words}>
        <Text style={[styles.name, { color: colors.text }]} numberOfLines={2}>{video.title}</Text>
        <Text style={[styles.detail, { color: colors.textMuted }]} numberOfLines={1}>
          {[video.uploader, size(video.sizeBytes)].filter(Boolean).join(' · ')}
        </Text>
      </View>
    </Pressable>
  );
});

const styles = StyleSheet.create({
  fill: { flex: 1 },
  header: { flexDirection: 'row', alignItems: 'center', gap: 16, paddingHorizontal: 16, paddingVertical: 10 },
  title: { fontSize: 20, fontWeight: '700' },
  empty: { alignItems: 'center', justifyContent: 'center', gap: 12, padding: 32 },
  emptyTitle: { fontSize: 18, fontWeight: '600' },
  center: { textAlign: 'center', padding: 16 },
  search: { marginHorizontal: 12, marginBottom: 6, borderRadius: 10, paddingHorizontal: 12, paddingVertical: 10, fontSize: 16 },
  list: { paddingBottom: 24 },
  row: { flexDirection: 'row', gap: 12, paddingHorizontal: 12, paddingVertical: 8, alignItems: 'center' },
  thumb: { width: 136, height: 77, borderRadius: 8, overflow: 'hidden', justifyContent: 'flex-end' },
  thumbIcon: { position: 'absolute', alignSelf: 'center', top: 27 },
  length: { position: 'absolute', right: 4, bottom: 7, backgroundColor: 'rgba(0,0,0,0.75)', borderRadius: 4, paddingHorizontal: 4, paddingVertical: 1 },
  lengthText: { color: '#fff', fontSize: 11, fontWeight: '600' },
  track: { height: 3, width: '100%', backgroundColor: 'rgba(0,0,0,0.5)' },
  trackFill: { height: 3 },
  chosen: { ...StyleSheet.absoluteFillObject, backgroundColor: 'rgba(0,0,0,0.45)', alignItems: 'center', justifyContent: 'center' },
  words: { flex: 1, gap: 3 },
  name: { fontSize: 15, fontWeight: '600' },
  detail: { fontSize: 12 },
  overlay: { flex: 1, alignItems: 'center', justifyContent: 'center', padding: 24 },
  card: { width: '100%', maxWidth: 420, borderRadius: 16, borderWidth: StyleSheet.hairlineWidth, padding: 20, gap: 14 },
  cardTitle: { fontSize: 17, fontWeight: '700' },
  input: { borderWidth: StyleSheet.hairlineWidth, borderRadius: 10, paddingHorizontal: 12, paddingVertical: 10, fontSize: 16 },
  buttons: { flexDirection: 'row', justifyContent: 'flex-end', gap: 10 },
  button: { borderRadius: 10, paddingHorizontal: 18, paddingVertical: 10 },
});
