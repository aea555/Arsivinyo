import { Ionicons } from '@expo/vector-icons';
import { useRouter } from 'expo-router';
import { useEvent } from 'expo';
import { useVideoPlayer, VideoView } from 'expo-video';
import React, { useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Pressable, StyleSheet, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { recordProgress } from '@/src/api';
import { AppText as Text } from '@/src/components';
import { openTitle, takePlayRequest, type PlayRequest } from '@/src/features/watch/open';

/** How often the position is written down while playing. */
const SAVE_EVERY_MS = 10_000;

/**
 * The phase 1 player: the system's, through expo-video, fullscreen in landscape. Phase 2
 * replaces it with mpv (`shared/watch/CONTRACT.md`). It resumes where the library says the
 * video stopped, writes the position down as it plays, and offers the next episode at the end.
 */
export default function WatchPlayerScreen() {
  const { t } = useTranslation();
  const router = useRouter();
  // Taken once: a stream's URL is handed over in memory, never as a route parameter.
  const [request] = useState<PlayRequest | null>(() => takePlayRequest());
  const view = useRef<VideoView>(null);
  const [ended, setEnded] = useState(false);
  const [failed, setFailed] = useState<string | null>(null);
  const resumed = useRef(false);

  const player = useVideoPlayer(request ? { uri: request.url, headers: request.headers } : null, (p) => {
    p.play();
  });

  const status = useEvent(player, 'statusChange', { status: player.status });

  useEffect(() => {
    if (status.status === 'error') setFailed(status.error?.message ?? 'PLAYBACK_FAILED');
    if (status.status === 'readyToPlay' && !resumed.current) {
      // Once, when the stream has loaded: before that a seek can be dropped.
      resumed.current = true;
      if (request && request.startMs > 0) player.currentTime = request.startMs / 1000;
      void view.current?.enterFullscreen().catch(() => undefined);
    }
  }, [player, request, status]);

  // Where it is, written down every few seconds and once more on the way out.
  useEffect(() => {
    if (!request || !request.record) return;
    const save = () => {
      const duration = player.duration;
      if (!duration || duration <= 0) return;
      void recordProgress(request.title, request.videoId, player.currentTime * 1000, duration * 1000, request.addonKey, request.bingeGroup)
        .catch(() => undefined);
    };
    const timer = setInterval(save, SAVE_EVERY_MS);
    const end = player.addListener('playToEnd', () => {
      save();
      setEnded(true);
    });
    return () => {
      clearInterval(timer);
      end.remove();
      save();
    };
  }, [player, request]);

  if (!request) {
    return (
      <SafeAreaView style={[styles.fill, styles.center]}>
        <Text style={styles.white}>{t('watch.playFailed', { code: 'NO_REQUEST' })}</Text>
      </SafeAreaView>
    );
  }

  return (
    <View style={styles.fill}>
      <VideoView
        ref={view}
        player={player}
        style={styles.fill}
        contentFit="contain"
        nativeControls
        fullscreenOptions={{ enable: true, orientation: 'landscape', autoExitOnRotate: false }}
        onFullscreenExit={() => {
          // Leaving fullscreen is leaving the film, unless it has just ended and offers the next.
          if (!ended) router.back();
        }}
      />
      <SafeAreaView style={styles.overlay} pointerEvents="box-none">
        <Pressable onPress={() => router.back()} hitSlop={12} style={styles.close} accessibilityRole="button">
          <Ionicons name="close" size={26} color="#fff" />
        </Pressable>
        <Text style={styles.name} numberOfLines={1}>{request.videoName}</Text>
      </SafeAreaView>
      {failed ? (
        <View style={[StyleSheet.absoluteFill, styles.center, styles.dim]}>
          <Text style={styles.white}>{t('watch.playFailed', { code: '' })}</Text>
          <Text style={[styles.white, styles.small]}>{failed}</Text>
        </View>
      ) : null}
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
        </View>
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  fill: { flex: 1, backgroundColor: '#000' },
  center: { alignItems: 'center', justifyContent: 'center', gap: 8, padding: 24 },
  dim: { backgroundColor: 'rgba(0,0,0,0.7)' },
  white: { color: '#fff', textAlign: 'center' },
  small: { fontSize: 12, opacity: 0.8 },
  overlay: { position: 'absolute', top: 0, left: 0, right: 0, flexDirection: 'row', alignItems: 'center', gap: 12, padding: 12 },
  close: { padding: 4 },
  name: { color: '#fff', flex: 1, fontSize: 15 },
  next: { flexDirection: 'row', alignItems: 'center', gap: 10, backgroundColor: '#fff', borderRadius: 24, paddingHorizontal: 20, paddingVertical: 12 },
  nextText: { color: '#000', fontWeight: '600' },
});
