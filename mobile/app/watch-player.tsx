import { Ionicons } from '@expo/vector-icons';
import { useRouter } from 'expo-router';
import React, { useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Pressable, StyleSheet, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { getLanguages, getSubtitles, recordProgress, type WatchSubtitle } from '@/src/api';
import { AppText as Text } from '@/src/components';
import { Player } from '@/src/features/player/Player';
import { openTitle, takePlayRequest, type PlayRequest } from '@/src/features/watch/open';
import { TorrentLive } from '@/src/features/watch/Torrents';

/** How often the position is written down while playing. */
const SAVE_EVERY_MS = 10_000;

/**
 * An add-on's stream in the player (`shared/watch/CONTRACT.md`, "The player"). It resumes
 * where the library says the video stopped, writes the position down as it plays, brings in
 * subtitles from add-ons, and offers the next episode at the end.
 */
export default function WatchPlayerScreen() {
  const { t } = useTranslation();
  const router = useRouter();
  // Taken once: a stream's URL is handed over in memory, never as a route parameter.
  const [request] = useState<PlayRequest | null>(() => takePlayRequest());
  const [languages, setLanguages] = useState<string[] | null>(null);
  const [subtitles, setSubtitles] = useState<WatchSubtitle[]>([]);
  const [ended, setEnded] = useState(false);
  const where = useRef({ positionMs: 0, durationMs: 0 });

  // The languages first: mpv picks the file's tracks by them as it opens it.
  useEffect(() => {
    let live = true;
    getLanguages()
      .then((l) => live && setLanguages(l.chosen))
      .catch(() => live && setLanguages(['tur', 'eng']));
    return () => {
      live = false;
    };
  }, []);

  // Add-ons' subtitles, which arrive while the video is already opening.
  useEffect(() => {
    // A trailer is not the title: add-ons have nothing for it.
    if (!request || !request.record) return;
    let live = true;
    getSubtitles(request.title.type, request.videoId, request.filename ?? null, request.subtitles ?? [])
      .then((r) => live && r.success && setSubtitles(r.subtitles))
      .catch(() => undefined);
    return () => {
      live = false;
    };
  }, [request]);

  // Where it is, written down every few seconds and once more on the way out.
  useEffect(() => {
    if (!request || !request.record) return;
    const save = () => {
      const { positionMs, durationMs } = where.current;
      if (durationMs <= 0) return;
      void recordProgress(request.title, request.videoId, positionMs, durationMs, request.addonKey, request.bingeGroup)
        .catch(() => undefined);
    };
    const timer = setInterval(save, SAVE_EVERY_MS);
    return () => {
      clearInterval(timer);
      save();
    };
  }, [request]);

  if (!request) {
    return (
      <SafeAreaView style={[styles.fill, styles.center]}>
        <Text style={styles.white}>{t('watch.playFailed', { code: 'NO_REQUEST' })}</Text>
      </SafeAreaView>
    );
  }

  return (
    <Player
      source={languages ? { url: request.url, audioUrl: request.audioUrl, headers: request.headers, startMs: request.startMs } : null}
      title={request.videoName}
      languages={languages ?? []}
      subtitles={subtitles}
      onClose={() => router.back()}
      waiting={request.torrentId ? <TorrentLive id={request.torrentId} color="#fff" /> : null}
      onProgress={(p) => {
        where.current = { positionMs: p.positionMs, durationMs: p.durationMs };
      }}
      onEnded={() => {
        if (request.record && where.current.durationMs > 0) {
          where.current = { positionMs: where.current.durationMs, durationMs: where.current.durationMs };
          void recordProgress(request.title, request.videoId, where.current.positionMs, where.current.durationMs,
            request.addonKey, request.bingeGroup).catch(() => undefined);
        }
        setEnded(true);
      }}
    >
      {ended && request.nextVideoId ? (
        <View style={[StyleSheet.absoluteFill, styles.center, styles.dim]}>
          <Pressable
            style={styles.next}
            onPress={() => {
              router.back();
              openTitle(router, request.title, {
                videoId: request.nextVideoId!,
                bingeGroup: request.bingeGroup,
                addonKey: request.addonKey,
              });
            }}
          >
            <Ionicons name="play-skip-forward" size={22} color="#000" />
            <Text style={styles.nextText}>{t('watch.nextEpisode')}</Text>
          </Pressable>
          <Pressable onPress={() => router.back()} hitSlop={12}>
            <Text style={styles.white}>{t('player.close')}</Text>
          </Pressable>
        </View>
      ) : null}
    </Player>
  );
}

const styles = StyleSheet.create({
  fill: { flex: 1, backgroundColor: '#000' },
  center: { alignItems: 'center', justifyContent: 'center', gap: 16, padding: 24 },
  dim: { backgroundColor: 'rgba(0,0,0,0.7)' },
  white: { color: '#fff', textAlign: 'center' },
  next: { flexDirection: 'row', alignItems: 'center', gap: 10, backgroundColor: '#fff', borderRadius: 24, paddingHorizontal: 20, paddingVertical: 12 },
  nextText: { color: '#000', fontWeight: '600' },
});
