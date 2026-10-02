import { Ionicons } from '@expo/vector-icons';
import { Image } from 'expo-image';
import { type Href, useFocusEffect, useLocalSearchParams, useRouter } from 'expo-router';
import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { ActivityIndicator, Linking, Modal, Pressable, ScrollView, StyleSheet, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import {
  getLibraryItem,
  getMeta,
  getStreams,
  getStreamSources,
  nextVideo,
  prepareStream,
  setSaved,
  setWatched,
  type WatchItem,
  type WatchMeta,
  type WatchStream,
  type WatchTitle,
  type WatchVideo,
} from '@/src/api';
import { AppText as Text, Chip } from '@/src/components';
import { openPlayer, streamLines } from '@/src/features/watch/open';
import { useTorrentHeadsUp, VpnNotice } from '@/src/features/watch/Torrents';
import { useTheme } from '@/src/theme';

type Params = { id: string; type: string; name: string; poster?: string; play?: string; bingeGroup?: string; addonKey?: string };

interface Source {
  addonKey: string;
  name: string;
  streams: WatchStream[] | null;
  failed?: string;
}

/**
 * A title: what it is, its episodes, and every way to watch the one picked, gathered from
 * every add-on that offers streams for it. Each add-on answers on its own.
 */
export default function WatchTitleScreen() {
  const { t } = useTranslation();
  const { colors } = useTheme();
  const router = useRouter();
  const params = useLocalSearchParams<Params>();
  const title: WatchTitle = useMemo(
    () => ({ id: String(params.id), type: String(params.type), name: String(params.name ?? ''), poster: params.poster || null }),
    [params.id, params.type, params.name, params.poster],
  );

  const [meta, setMeta] = useState<WatchMeta | null>(null);
  const [metaFailed, setMetaFailed] = useState<string | null>(null);
  const [item, setItem] = useState<WatchItem | null>(null);
  const [itemLoaded, setItemLoaded] = useState(false);
  const [season, setSeason] = useState<number | null>(null);
  const [video, setVideo] = useState<WatchVideo | null>(null);
  const [sources, setSources] = useState<Source[]>([]);
  const [preparing, setPreparing] = useState<string | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const autoplayed = useRef(false);

  const known: WatchTitle = useMemo(
    () => ({ ...title, name: meta?.name ?? title.name, poster: meta?.poster ?? title.poster }),
    [meta, title],
  );

  useEffect(() => {
    let live = true;
    getMeta(title.type, title.id)
      .then((result) => {
        if (!live) return;
        if (result.success) setMeta(result.meta);
        else setMetaFailed(result.code);
      })
      .catch(() => live && setMetaFailed('WATCH_FAILED'));
    return () => {
      live = false;
    };
  }, [title.id, title.type]);

  const reloadItem = useCallback(async () => {
    setItem(await getLibraryItem(title.id).catch(() => null));
    setItemLoaded(true);
  }, [title.id]);

  useFocusEffect(
    useCallback(() => {
      void reloadItem();
    }, [reloadItem]),
  );

  const isSeries = (meta?.videos.length ?? 0) > 0;
  const seasons = useMemo(() => {
    const all = new Set((meta?.videos ?? []).map((v) => v.season ?? 1));
    return [...all].sort((a, b) => (a === 0 ? 1 : b === 0 ? -1 : a - b));
  }, [meta]);

  // Where to start: the episode asked for, the one in progress, or the first not watched.
  // A film no add-on describes still has streams to offer, under its own id.
  useEffect(() => {
    if (metaFailed && !meta && title.type === 'movie' && !video) setVideo({ id: title.id, title: title.name });
  }, [meta, metaFailed, title, video]);

  // Once: closing an episode's streams must not pick one again.
  const started = useRef(false);
  useEffect(() => {
    if (!meta || !itemLoaded || video || started.current) return;
    started.current = true;
    if (!isSeries) {
      setVideo({ id: meta.id, title: meta.name });
      return;
    }
    const wanted =
      meta.videos.find((v) => v.id === params.play) ??
      meta.videos.find((v) => v.id === item?.progress?.videoId) ??
      meta.videos.find((v) => (v.season ?? 1) !== 0 && !item?.watched.includes(v.id)) ??
      meta.videos[0];
    setSeason(wanted.season ?? 1);
    if (params.play || item?.progress) setVideo(wanted);
  }, [isSeries, item, itemLoaded, meta, params.play, video]);

  // Every add-on that streams this video, each asked on its own.
  useEffect(() => {
    if (!video) return;
    let live = true;
    setSources([]);
    getStreamSources(title.type, video.id).then((list) => {
      if (!live) return;
      setSources(list.map((s) => ({ ...s, streams: null })));
      list.forEach((source) => {
        getStreams(source.addonKey, title.type, video.id)
          .then((result) => {
            if (!live) return;
            setSources((prev) =>
              prev.map((s) =>
                s.addonKey === source.addonKey
                  ? { ...s, streams: result.success ? result.streams : [], failed: result.success ? undefined : result.code }
                  : s,
              ),
            );
          })
          .catch(() => undefined);
      });
    });
    return () => {
      live = false;
    };
  }, [title.type, video]);

  const [askHeadsUp, headsUpModal] = useTorrentHeadsUp();

  const play = useCallback(
    async (source: Source, stream: WatchStream) => {
      if (!video) return;
      if (stream.kind === 'torrent' && !(await askHeadsUp())) return;
      setPreparing(stream.target);
      setMessage(null);
      const prepared = await prepareStream(stream).catch(() => null);
      setPreparing(null);
      if (!prepared?.success) {
        if (stream.kind === 'external') {
          void Linking.openURL(stream.target);
          return;
        }
        const code = prepared && !prepared.success ? prepared.code : '';
        setMessage(code === 'TORRENT_NO_METADATA' ? t('torrents.noMetadata') : t('watch.playFailed', { code }));
        return;
      }
      const startMs = item?.progress?.videoId === video.id ? item.progress.positionMs : 0;
      const next = meta && isSeries ? nextVideo(meta.videos, video.id) : null;
      openPlayer(router, {
        url: prepared.url,
        audioUrl: prepared.audioUrl ?? null,
        headers: prepared.headers,
        title: known,
        videoId: video.id,
        videoName: isSeries ? episodeName(video) : known.name,
        addonKey: source.addonKey,
        bingeGroup: stream.bingeGroup ?? null,
        startMs,
        nextVideoId: next?.id ?? null,
        record: true,
        subtitles: stream.subtitles ?? [],
        filename: stream.filename ?? null,
      });
    },
    [askHeadsUp, isSeries, item, known, meta, router, t, video],
  );

  const playTrailer = useCallback(
    async (stream: WatchStream) => {
      setPreparing(stream.target);
      const prepared = await prepareStream(stream).catch(() => null);
      setPreparing(null);
      if (!prepared?.success) {
        setMessage(t('watch.playFailed', { code: prepared && !prepared.success ? prepared.code : '' }));
        return;
      }
      openPlayer(router, {
        url: prepared.url,
        audioUrl: prepared.audioUrl ?? null,
        headers: prepared.headers,
        title: known,
        videoId: known.id,
        videoName: t('watch.trailerOf', { name: known.name }),
        addonKey: null,
        bingeGroup: null,
        startMs: 0,
        nextVideoId: null,
        record: false,
      });
    },
    [known, router, t],
  );

  // The next episode, asked for from the player: the same add-on and binge group, played at once.
  useEffect(() => {
    if (autoplayed.current || !params.play || video?.id !== params.play) return;
    const source = sources.find((s) => s.addonKey === params.addonKey) ?? sources.find((s) => s.streams?.length);
    if (!source?.streams) return;
    const stream =
      source.streams.find((s) => params.bingeGroup && s.bingeGroup === params.bingeGroup) ??
      (source.addonKey === params.addonKey ? source.streams[0] : undefined);
    if (!stream) return;
    autoplayed.current = true;
    void play(source, stream);
  }, [params.addonKey, params.bingeGroup, params.play, play, sources, video]);

  const saved = item?.saved ?? false;
  const episodes = (meta?.videos ?? []).filter((v) => (v.season ?? 1) === season);

  return (
    <SafeAreaView style={[styles.fill, { backgroundColor: colors.background }]} edges={['top', 'left', 'right']}>
      <View style={styles.header}>
        <Pressable onPress={() => router.back()} hitSlop={10} accessibilityRole="button">
          <Ionicons name="arrow-back" size={24} color={colors.text} />
        </Pressable>
        <Text style={[styles.headerTitle, styles.fill, { color: colors.text }]} numberOfLines={1}>{known.name}</Text>
        <Pressable
          onPress={async () => {
            await setSaved(known, !saved);
            await reloadItem();
          }}
          hitSlop={10}
          accessibilityRole="button"
          accessibilityLabel={saved ? t('watch.unsave') : t('watch.save')}
        >
          <Ionicons name={saved ? 'bookmark' : 'bookmark-outline'} size={22} color={saved ? colors.accent : colors.text} />
        </Pressable>
      </View>

      <ScrollView contentContainerStyle={styles.body}>
        <View style={styles.hero}>
          {meta?.background ? <Image source={{ uri: meta.background }} style={StyleSheet.absoluteFill} contentFit="cover" /> : null}
          <View style={[StyleSheet.absoluteFill, { backgroundColor: 'rgba(0,0,0,0.45)' }]} />
          {known.poster ? <Image source={{ uri: known.poster }} style={styles.heroPoster} contentFit="cover" /> : null}
          <View style={styles.heroText}>
            <Text style={styles.heroName} numberOfLines={2}>{known.name}</Text>
            <Text style={styles.heroInfo} numberOfLines={2}>
              {[meta?.releaseInfo, meta?.runtime, meta?.imdbRating ? `IMDb ${meta.imdbRating}` : null, meta?.genres.slice(0, 3).join(', ')]
                .filter(Boolean)
                .join(' · ')}
            </Text>
          </View>
        </View>

        {!isSeries && video ? (
          <>
            <Text style={[styles.sectionTitle, { color: colors.text }]}>{t('watch.streams')}</Text>
            <StreamsList sources={sources} preparing={preparing} message={message} onPlay={play} />
          </>
        ) : null}

        {metaFailed && !meta ? <Text style={[styles.pad, { color: colors.textMuted }]}>{t('watch.metaFailed', { code: metaFailed })}</Text> : null}
        {!meta && !metaFailed ? <ActivityIndicator style={styles.pad} color={colors.accent} /> : null}
        {meta?.trailers?.length ? (
          <View style={styles.pad}>
            <Pressable
              onPress={() => void playTrailer(meta.trailers![0])}
              style={[styles.trailer, { backgroundColor: colors.surface }]}
              accessibilityRole="button"
            >
              {preparing === meta.trailers[0].target ? <ActivityIndicator color={colors.accent} /> : (
                <Ionicons name="play" size={18} color={colors.text} />
              )}
              <Text style={{ color: colors.text }}>{t('watch.trailer')}</Text>
            </Pressable>
          </View>
        ) : null}
        {meta?.description ? <Text style={[styles.pad, { color: colors.text }]}>{meta.description}</Text> : null}

        {isSeries ? (
          <>
            <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={styles.chips}>
              {seasons.map((n) => (
                <Chip
                  key={n}
                  label={n === 0 ? t('watch.specials') : t('watch.season', { n })}
                  active={season === n}
                  color={colors.accent}
                  onPress={() => setSeason(n)}
                />
              ))}
            </ScrollView>
            {episodes.map((v) => {
              const watched = item?.watched.includes(v.id) ?? false;
              const inProgress = item?.progress?.videoId === v.id;
              return (
                <Pressable
                  key={v.id}
                  onPress={() => setVideo(v)}
                  onLongPress={async () => {
                    await setWatched(known, v.id, !watched);
                    await reloadItem();
                  }}
                  style={[styles.episode, { backgroundColor: video?.id === v.id ? colors.surfaceActive : 'transparent' }]}
                >
                  {v.thumbnail ? <Image source={{ uri: v.thumbnail }} style={styles.thumb} contentFit="cover" /> : null}
                  <View style={styles.fill}>
                    <Text style={{ color: colors.text }} numberOfLines={1}>{episodeName(v)}</Text>
                    {v.released ? (
                      <Text style={[styles.small, { color: colors.textMuted }]}>{v.released.slice(0, 10)}</Text>
                    ) : null}
                    {inProgress && item?.progress ? (
                      <View style={[styles.track, { backgroundColor: colors.surface }]}>
                        <View
                          style={[styles.trackFill, {
                            backgroundColor: colors.accent,
                            width: `${Math.min(100, (item.progress.positionMs / Math.max(1, item.progress.durationMs)) * 100)}%`,
                          }]}
                        />
                      </View>
                    ) : null}
                  </View>
                  {watched ? <Ionicons name="checkmark-circle" size={20} color={colors.accent} /> : null}
                </Pressable>
              );
            })}
            <Text style={[styles.pad, styles.small, { color: colors.textMuted }]}>{t('watch.longPressWatched')}</Text>
          </>
        ) : null}
      </ScrollView>

      {isSeries ? (
        <Modal visible={video != null} animationType="slide" onRequestClose={() => setVideo(null)}>
          <SafeAreaView style={[styles.fill, { backgroundColor: colors.background }]}>
            <View style={styles.header}>
              <Pressable onPress={() => setVideo(null)} hitSlop={10} accessibilityRole="button">
                <Ionicons name="close" size={24} color={colors.text} />
              </Pressable>
              <Text style={[styles.headerTitle, styles.fill, { color: colors.text }]} numberOfLines={1}>
                {video ? episodeName(video) : ''}
              </Text>
            </View>
            <ScrollView contentContainerStyle={styles.body}>
              <StreamsList sources={sources} preparing={preparing} message={message} onPlay={play} />
            </ScrollView>
          </SafeAreaView>
        </Modal>
      ) : null}
      {headsUpModal}
    </SafeAreaView>
  );
}

function episodeName(v: WatchVideo) {
  const number = v.season != null && v.episode != null ? `${v.season}×${String(v.episode).padStart(2, '0')}` : '';
  return [number, v.title].filter(Boolean).join(' · ');
}

function StreamsList({
  sources,
  preparing,
  message,
  onPlay,
}: {
  sources: Source[];
  preparing: string | null;
  message: string | null;
  onPlay: (source: Source, stream: WatchStream) => void;
}) {
  const router = useRouter();
  const { t } = useTranslation();
  const { colors } = useTheme();
  return (
    <View>
      {message ? <Text style={[styles.pad, { color: colors.warning }]}>{message}</Text> : null}
      {sources.some((s) => s.streams?.some((stream) => stream.kind === 'torrent')) ? <VpnNotice /> : null}
      {sources.length === 0 ? <Text style={[styles.pad, { color: colors.textMuted }]}>{t('watch.noSources')}</Text> : null}
      {/* Said once, when every add-on has answered and none has a stream: an add-on that
          failed or has nothing for this is left out rather than listed with an error. */}
      {sources.length > 0 && sources.every((s) => s.streams !== null || s.failed) &&
      sources.every((s) => s.failed || (s.streams?.length ?? 0) === 0) ? (
        <Text style={[styles.pad, { color: colors.textMuted }]}>{t('watch.noStreamsAnywhere')}</Text>
      ) : null}
      {sources.filter((source) => !source.failed && source.streams?.length !== 0).map((source) => (
        <View key={source.addonKey} style={styles.source}>
          <Text style={[styles.sourceName, { color: colors.textMuted }]}>{source.name}</Text>
          {source.streams === null ? (
            <ActivityIndicator color={colors.accent} style={styles.sourceLoading} />
          ) : (
            source.streams.map((stream, index) => {
              const [line, detail] = streamLines(stream);
              const torrent = stream.kind === 'torrent';
              return (
                <Pressable
                  key={`${stream.target}#${index}`}
                  onPress={() => onPlay(source, stream)}
                  // A torrent can be downloaded instead of streamed: its files chosen, kept.
                  onLongPress={torrent ? () => router.push({ pathname: '/torrents', params: { add: stream.target } } as unknown as Href) : undefined}
                  style={[styles.stream, { backgroundColor: colors.surface }]}
                >
                  <Ionicons
                    name={torrent ? 'magnet-outline' : stream.kind === 'youtube' ? 'logo-youtube' : stream.kind === 'external' ? 'open-outline' : 'play-circle-outline'}
                    size={22}
                    color={colors.text}
                  />
                  <View style={styles.fill}>
                    <Text style={{ color: colors.text }} numberOfLines={2}>{line}</Text>
                    {detail ? <Text style={[styles.small, { color: colors.textMuted }]} numberOfLines={3}>{detail}</Text> : null}
                  </View>
                  {preparing === stream.target ? <ActivityIndicator color={colors.accent} /> : null}
                </Pressable>
              );
            })
          )}
        </View>
      ))}
    </View>
  );
}

const styles = StyleSheet.create({
  fill: { flex: 1 },
  header: { flexDirection: 'row', alignItems: 'center', gap: 14, paddingHorizontal: 16, paddingVertical: 10 },
  headerTitle: { fontSize: 18, fontWeight: '600' },
  body: { paddingBottom: 48 },
  hero: { height: 220, flexDirection: 'row', alignItems: 'flex-end', padding: 16, gap: 14 },
  heroPoster: { width: 100, height: 148, borderRadius: 8 },
  heroText: { flex: 1, gap: 4 },
  heroName: { color: '#fff', fontSize: 22, fontWeight: '700' },
  heroInfo: { color: '#ddd', fontSize: 13 },
  pad: { paddingHorizontal: 16, paddingVertical: 10 },
  sectionTitle: { fontSize: 17, fontWeight: '600', paddingHorizontal: 16, paddingTop: 12 },
  chips: { gap: 8, paddingHorizontal: 16, paddingVertical: 10 },
  episode: { flexDirection: 'row', alignItems: 'center', gap: 12, paddingHorizontal: 16, paddingVertical: 10 },
  thumb: { width: 96, height: 54, borderRadius: 6 },
  small: { fontSize: 12 },
  track: { height: 3, borderRadius: 2, marginTop: 4, overflow: 'hidden' },
  trackFill: { height: 3 },
  source: { paddingHorizontal: 16, paddingTop: 14, gap: 8 },
  sourceName: { fontSize: 13, fontWeight: '600' },
  sourceLoading: { alignSelf: 'flex-start' },
  stream: { flexDirection: 'row', alignItems: 'center', gap: 12, padding: 12, borderRadius: 10 },
  trailer: { flexDirection: 'row', alignItems: 'center', gap: 8, alignSelf: 'flex-start', paddingHorizontal: 14, paddingVertical: 9, borderRadius: 20 },
});
