import { useRouter } from 'expo-router';
import React, { useEffect, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { StyleSheet } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { getLanguages } from '@/src/api';
import { AppText as Text } from '@/src/components';
import { Player } from '@/src/features/player/Player';
import { takeVideoQueue, type VideoQueue } from '@/src/features/videos/open';

/**
 * Videos of the library in the player: the same player as Watch's, so it plays on with the
 * screen off and in picture-in-picture. The list it was opened from is its queue, and the
 * queue is the player's own, not this screen's: each video resumes where it stopped, and at
 * the end of one the next begins, out of sight too. This screen is only told which plays.
 */
export default function VideoPlayerScreen() {
  const { t } = useTranslation();
  const router = useRouter();
  const [opened] = useState<VideoQueue | null>(() => takeVideoQueue());
  const [index, setIndex] = useState(opened?.index ?? 0);
  const [languages, setLanguages] = useState<string[] | null>(null);
  const video = opened?.videos[index] ?? null;

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

  const queue = useMemo(
    () =>
      opened
        ? {
            items: opened.videos.map((v) => ({ id: v.id, uri: v.uri, title: v.title, uploader: v.uploader ?? null })),
            index: opened.index,
          }
        : null,
    [opened],
  );

  if (!queue || !video) {
    return (
      <SafeAreaView style={styles.missing}>
        <Text style={styles.white}>{t('videos.playFailed')}</Text>
      </SafeAreaView>
    );
  }

  return (
    <Player
      source={null}
      queue={languages ? queue : null}
      item={index}
      onItem={setIndex}
      title={video.title}
      languages={languages ?? []}
      background
      upright={video.height > video.width}
      onClose={() => router.back()}
    />
  );
}

const styles = StyleSheet.create({
  missing: { flex: 1, backgroundColor: '#000', alignItems: 'center', justifyContent: 'center', padding: 24 },
  white: { color: '#fff', textAlign: 'center' },
});
