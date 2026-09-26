import {
  Monda_400Regular,
  Monda_500Medium,
  Monda_600SemiBold,
  Monda_700Bold,
  useFonts as useMondaFonts,
} from '@expo-google-fonts/monda';
import { Stack } from 'expo-router';
import * as SplashScreen from 'expo-splash-screen';
import { StatusBar } from 'expo-status-bar';
import React, { useEffect, useState } from 'react';
import { I18nextProvider } from 'react-i18next';
import { useTranslation } from 'react-i18next';
import { StyleSheet } from 'react-native';
import { GestureHandlerRootView } from 'react-native-gesture-handler';
import 'react-native-reanimated';

import '@/global.css';
import { AnimatedSplash, PeerLinkPrompt } from '@/src/components';
import i18n from '@/src/i18n';
import { appHeaderTitleFontFamily } from '@/src/components';
import { ThemeProvider, useTheme } from '@/src/theme';

// Prevent native splash from auto-hiding
SplashScreen.preventAutoHideAsync();

export const unstable_settings = {
  anchor: '(tabs)',
};

function AppContent() {
  const { isDark, colors } = useTheme();
  const { t } = useTranslation();

  return (
    <>
      <Stack
        screenOptions={{
          headerStyle: { backgroundColor: colors.background },
          headerTitleStyle: { fontFamily: appHeaderTitleFontFamily },
          headerTintColor: colors.text,
          contentStyle: { backgroundColor: colors.background },
        }}
      >
        <Stack.Screen name="(tabs)" options={{ headerShown: false }} />
        <Stack.Screen
          name="settings"
          options={{
            presentation: 'modal',
            headerShown: true,
            title: t('settings.title'),
          }}
        />
        <Stack.Screen
          name="diagnostics"
          options={{
            // Presented as a modal, matching Settings. As a card its header drew from
            // the very top under edge-to-edge and collided with the status bar, while
            // the modal presentation clears it. headerStatusBarHeight is not an option
            // on this navigator, so the presentation is the lever.
            presentation: 'modal',
            headerShown: true,
            title: t('settings.diagnostics'),
          }}
        />
        <Stack.Screen
          name="devices"
          options={{
            presentation: 'card',
            headerShown: true,
            title: t('devices.title'),
          }}
        />
        <Stack.Screen
          name="recent-failures"
          options={{
            presentation: 'card',
            headerShown: true,
            title: t('failureLogs.title'),
          }}
        />
        <Stack.Screen
          name="private-videos"
          options={{
            presentation: 'card',
            headerShown: true,
            title: '',
          }}
        />
        <Stack.Screen
          name="private-player"
          options={{
            presentation: 'card',
            headerShown: false,
          }}
        />
        <Stack.Screen
          name="sounds"
          options={{
            presentation: 'card',
            headerShown: false,
          }}
        />
        <Stack.Screen
          name="memes"
          options={{
            presentation: 'card',
            headerShown: false,
          }}
        />
        <Stack.Screen
          name="faces"
          options={{
            presentation: 'card',
            headerShown: false,
          }}
        />
        <Stack.Screen
          name="sound-player"
          options={{
            presentation: 'modal',
            headerShown: false,
          }}
        />
      </Stack>
      <PeerLinkPrompt />
      <StatusBar style={isDark ? 'light' : 'dark'} />
    </>
  );
}

/** Upper bound on waiting for fonts before starting anyway with system fonts. */
const FONT_LOAD_TIMEOUT_MS = 4000;

export default function RootLayout() {
  const [mondaLoaded] = useMondaFonts({
    Monda_400Regular,
    Monda_500Medium,
    Monda_600SemiBold,
    Monda_700Bold,
  });

  useEffect(() => {
    // Hide native splash once our layout is ready
    SplashScreen.hideAsync();
  }, []);

  // Show the splash only while the app is genuinely not ready. It used to also wait
  // out a fixed 2.5s animation, which was a floor rather than a ceiling: a warm start
  // that was ready immediately still sat on the splash, and every process restart made
  // that wait obvious.
  //
  // The timeout below is a CEILING, not a floor. If the fonts never resolve — a failed
  // asset, an odd device — the app proceeds with system fonts instead of being stuck on
  // the splash forever, which is what the old unconditional timer accidentally provided.
  const [fontTimedOut, setFontTimedOut] = useState(false);
  useEffect(() => {
    if (mondaLoaded) return;
    const timer = setTimeout(() => setFontTimedOut(true), FONT_LOAD_TIMEOUT_MS);
    return () => clearTimeout(timer);
  }, [mondaLoaded]);

  const appReady = mondaLoaded || fontTimedOut;

  if (!appReady) {
    return (
      <GestureHandlerRootView style={styles.root}>
        <AnimatedSplash />
      </GestureHandlerRootView>
    );
  }

  return (
    <GestureHandlerRootView style={styles.root}>
      <I18nextProvider i18n={i18n}>
        <ThemeProvider>
          <AppContent />
        </ThemeProvider>
      </I18nextProvider>
    </GestureHandlerRootView>
  );
}

const styles = StyleSheet.create({
  root: {
    flex: 1,
  },
});
