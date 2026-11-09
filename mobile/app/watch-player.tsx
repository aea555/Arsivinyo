import { Ionicons } from '@expo/vector-icons';
import { useRouter } from 'expo-router';
import React, { useEffect, useMemo, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { ActivityIndicator, Pressable, StyleSheet, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { getLanguages, getSubtitles, type WatchSubtitle } from '@/src/api';
import { AppText as Text } from '@/src/components';
import { Player } from '@/src/features/player/Player';
import { episodeRequest, neighbours, openTitle, takePlayRequest, type PlayRequest } from '@/src/features/watch/open';
import { TorrentLive } from '@/src/features/watch/Torrents';

/**
 * An add-on's stream in the player (`shared/watch/CONTRACT.md`, "The player"). It resumes
 * where the library says the video stopped, brings in subtitles from add-ons, and offers the
 * next episode at the end. It plays on with the screen off or in picture-in-picture, so the
 * player itself writes the position down: nothing here runs while the app is out of sight.
 */
export default function WatchPlayerScreen() {
  const { t } = useTranslation();
  const router = useRouter();
  // Taken once: a stream's URL is handed over in memory, never as a route parameter. The
  // next episode replaces it here, in the same player.
  const [request, setRequest] = useState<PlayRequest | null>(() => takePlayRequest());
  const [advancing, setAdvancing] = useState(false);
  const live = useRef(true);
  useEffect(() => () => {
    live.current = false;
  }, []);
  const [languages, setLanguages] = useState<string[] | null>(null);
  const [subtitles, setSubtitles] = useState<WatchSubtitle[]>([]);
  const [ended, setEnded] = useState(false);
  // A trailer is not the title: watching it is not kept.
  const record = useMemo(
    () => (request && request.record
      ? { title: request.title, videoId: request.videoId, addonKey: request.addonKey, bingeGroup: request.bingeGroup }
      : null),
    [request],
  );

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
    setSubtitles([]);
    if (!request || !request.record) return;
    let live = true;
    getSubtitles(request.title.type, request.videoId, request.filename ?? null, request.subtitles ?? [])
      .then((r) => live && r.success && setSubtitles(r.subtitles))
      .catch(() => undefined);
    return () => {
      live = false;
    };
  }, [request]);

  if (!request) {
    return (
      <SafeAreaView style={[styles.fill, styles.center]}>
        <Text style={styles.white}>{t('watch.playFailed', { code: 'NO_REQUEST' })}</Text>
      </SafeAreaView>
    );
  }

  // Another episode in this same player: nothing of the app shows in between, and the screen
  // stays as it is. This one plays on until the other is found. When it cannot be found from
  // here, the title page finds it, as before.
  const { previous, next } = neighbours(request);
  const play = async (videoId: string) => {
    if (advancing) return;
    setAdvancing(true);
    const found = await episodeRequest(request, videoId).catch(() => null);
    if (!live.current) return;
    setAdvancing(false);
    if (found) {
      setEnded(false);
      setRequest(found);
      return;
    }
    router.back();
    openTitle(router, request.title, { videoId, bingeGroup: request.bingeGroup, addonKey: request.addonKey });
  };

  return (
    <Player
      source={languages ? { url: request.url, audioUrl: request.audioUrl, headers: request.headers, startMs: request.startMs } : null}
      title={request.videoName}
      languages={languages ?? []}
      subtitles={subtitles}
      background
      record={record}
      onClose={() => router.back()}
      onPrevious={previous ? () => void play(previous) : undefined}
      onNext={next ? () => void play(next) : undefined}
      switching={advancing}
      waiting={request.torrentId ? <TorrentLive id={request.torrentId} color="#fff" /> : null}
      onEnded={() => setEnded(true)}
    >
      {ended && next ? (
        <View style={[StyleSheet.absoluteFill, styles.center, styles.dim]}>
          <Pressable style={styles.next} onPress={() => void play(next)} disabled={advancing}>
            {advancing ? <ActivityIndicator size="small" color="#000" /> : <Ionicons name="play-skip-forward" size={22} color="#000" />}
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
