import { useRouter } from 'expo-router';
import React, { useEffect, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { StyleSheet } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { getLanguages, getLocalVideoPlace } from '@/src/api';
import { AppText as Text } from '@/src/components';
import { Player } from '@/src/features/player/Player';
import { takeVideoQueue, type VideoQueue } from '@/src/features/videos/open';

/**
 * A video of the library in the player: the same player as Watch's, so it plays on with the
 * screen off and in picture-in-picture, and the player itself writes down where it stopped.
 * The list it was opened from is its queue: the buttons go to the one before and the one
 * after, and at the end of one the next begins.
 */
export default function VideoPlayerScreen() {
  const { t } = useTranslation();
  const router = useRouter();
  const [queue] = useState<VideoQueue | null>(() => takeVideoQueue());
  const [index, setIndex] = useState(queue?.index ?? 0);
  const [languages, setLanguages] = useState<string[] | null>(null);
  const video = queue?.videos[index] ?? null;

  // The languages first: mpv picks the file's tracks by them as it opens it.
  useEffect(() => {
    let live = true;
    getLanguages()
      .then((l) => live && setLanguages(l.chosen))
      .catch(() => live && setLanguages([]));
    return () => {
      live = false;
    };
  }, []);

  // Each video resumes where it stopped. Asked of the library as the video opens: the list's
  // copy is from before the player last wrote, which it does as it leaves a video.
  const [start, setStart] = useState<{ id: string; ms: number } | null>(null);
  const id = video?.id;
  useEffect(() => {
    if (!id) return undefined;
    let live = true;
    getLocalVideoPlace(id)
      .then((ms) => live && setStart({ id, ms }))
      .catch(() => live && setStart({ id, ms: 0 }));
    return () => {
      live = false;
    };
  }, [id]);
  const source = useMemo(
    () => (video && start?.id === video.id ? { url: video.uri, startMs: start.ms } : null),
    [video, start],
  );
  const record = useMemo(() => (video ? { video: video.id, uploader: video.uploader ?? null } : null), [video]);

  if (!queue || !video) {
    return (
      <SafeAreaView style={styles.missing}>
        <Text style={styles.white}>{t('videos.playFailed')}</Text>
      </SafeAreaView>
    );
  }

  const last = queue.videos.length - 1;
  return (
    <Player
      source={languages ? source : null}
      title={video.title}
      languages={languages ?? []}
      background
      upright={video.height > video.width}
      record={record}
      onClose={() => router.back()}
      onPrevious={index > 0 ? () => setIndex(index - 1) : undefined}
      onNext={index < last ? () => setIndex(index + 1) : undefined}
      onEnded={() => {
        if (index < last) setIndex(index + 1);
      }}
    />
  );
}

const styles = StyleSheet.create({
  missing: { flex: 1, backgroundColor: '#000', alignItems: 'center', justifyContent: 'center', padding: 24 },
  white: { color: '#fff', textAlign: 'center' },
});
