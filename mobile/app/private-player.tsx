import { useLocalSearchParams, useRouter } from 'expo-router';
import React, { useEffect, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Pressable, StyleSheet, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { clearLocalPrivatePlaybackCache, getLanguages, setLocalSecureScreen } from '@/src/api';
import { AppText as Text } from '@/src/components';
import { Player } from '@/src/features/player/Player';
import { deleteSession, getSession } from '@/src/features/privatePlayback/sessionStore';
import { useTheme } from '@/src/theme';

/**
 * A vault video in the player, streamed from the vault's loopback server: nothing decrypted
 * is written anywhere, and mpv plays what the system player could not (MKV, DTS, styled
 * subtitles), as `shared/watch/CONTRACT.md` asks of the vault too.
 */
export default function PrivatePlayerScreen() {
  const { t } = useTranslation();
  const { colors } = useTheme();
  const router = useRouter();
  const params = useLocalSearchParams<{ sid?: string | string[] }>();

  const sid = useMemo(() => {
    if (Array.isArray(params.sid)) return params.sid[0] ?? '';
    return params.sid ?? '';
  }, [params.sid]);

  const session = useMemo(() => getSession(sid), [sid]);
  const [languages, setLanguages] = useState<string[] | null>(null);

  useEffect(() => {
    let live = true;
    getLanguages()
      .then((l) => live && setLanguages(l.chosen))
      .catch(() => live && setLanguages(['tur', 'eng']));
    return () => {
      live = false;
    };
  }, []);

  useEffect(() => {
    // FLAG_SECURE is applied by the caller before navigation (see app/private-videos.tsx)
    // to close a one-frame screenshot window on the destination's recents thumbnail.
    // We only need cleanup here.
    return () => {
      void setLocalSecureScreen(false).catch(() => undefined);
      deleteSession(sid);
      void clearLocalPrivatePlaybackCache().catch(() => undefined);
    };
  }, [sid]);

  const goBack = () => {
    if (router.canGoBack()) {
      router.back();
      return;
    }
    router.replace('/private-videos');
  };

  if (!session) {
    return (
      <SafeAreaView style={[styles.container, { backgroundColor: colors.background }]}>
        <View style={styles.centered}>
          <Text style={[styles.errorText, { color: colors.error }]}>
            {t('errors.PRIVATE_PLAYER_SESSION_INVALID')}
          </Text>
          <Pressable
            onPress={goBack}
            style={({ pressed }) => [
              styles.button,
              {
                borderColor: colors.border,
                backgroundColor: pressed ? colors.surfaceHover : colors.surface,
              },
            ]}
          >
            <Text style={[styles.buttonText, { color: colors.text }]}>{t('privateVault.playerBack')}</Text>
          </Pressable>
        </View>
      </SafeAreaView>
    );
  }

  return (
    <Player
      source={languages ? { url: session.tempUri } : null}
      title={session.title}
      languages={languages ?? []}
      onClose={goBack}
    />
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
  },
  centered: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    paddingHorizontal: 20,
    gap: 12,
  },
  errorText: {
    fontSize: 14,
    fontWeight: '600',
    textAlign: 'center',
  },
  button: {
    minWidth: 120,
    minHeight: 42,
    borderRadius: 10,
    borderWidth: 1,
    alignItems: 'center',
    justifyContent: 'center',
    paddingHorizontal: 14,
  },
  buttonText: {
    fontSize: 13,
    fontWeight: '600',
  },
});
