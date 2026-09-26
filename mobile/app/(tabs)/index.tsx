import { Sixtyfour_400Regular, useFonts } from '@expo-google-fonts/sixtyfour';
import { Ionicons } from '@expo/vector-icons';
import { LinearGradient } from 'expo-linear-gradient';
import { useFocusEffect, useRouter, type Href } from 'expo-router';
import React, { useCallback, useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  ActivityIndicator,
  BackHandler,
  Modal,
  Pressable,
  StyleSheet,
  View
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import {
  getLocalPrivateModeState,
  cancelTask,
  downloadMedia,
  DownloadProgress,
  getLocalBackgroundState,
  listenBackgroundState,
  setLocalAudioModeEnabled,
  setLocalPrivateModeEnabled,
} from '@/src/api';
import type { DownloadState } from '@/src/api/types';
import { AppText as Text, DownloadButton, DOWNLOAD_BUTTON_SIZE } from '@/src/components';
import {
  downloadAndSaveFile,
  getUrlFromClipboard,
} from '@/src/services';
import { useTheme } from '@/src/theme';

/** How long a transient status line stays before clearing itself. */
const STATUS_MESSAGE_TTL_MS = 5000;

/** One download this screen started, tracked separately from every other. */
type ScreenDownload = {
  clientId: string;
  /** Null until the module has assigned one; Cancel needs it. */
  taskId: string | null;
  state: DownloadState;
  percent: number | null;
  speedBytesPerSec: number | null;
  /** Only the host, shown to tell concurrent rows apart without printing a full url. */
  host: string | null;
};

function hostOf(url: string): string | null {
  try {
    return new URL(url).hostname.replace(/^www\./, '');
  } catch {
    return null;
  }
}

function progressOf(item: ScreenDownload): number | null {
  if (item.state === 'processing' || item.state === 'saving') return null;
  if (typeof item.percent !== 'number') return 0;
  return Math.max(0, Math.min(100, Math.round(item.percent)));
}

function speedLabelOf(item: ScreenDownload): string | null {
  if (item.state !== 'downloading') return null;
  const percent = progressOf(item);
  if (typeof percent === 'number' && percent >= 99) return null;
  if (typeof item.speedBytesPerSec !== 'number' || item.speedBytesPerSec <= 0) return null;
  const mb = item.speedBytesPerSec / (1024 * 1024);
  return `${mb >= 10 ? mb.toFixed(1) : mb.toFixed(2)} MB/s`;
}

export default function HomeScreen() {
  const { t } = useTranslation();
  const { colors } = useTheme();
  const router = useRouter();

  const [fontsLoaded] = useFonts({
    Sixtyfour_400Regular,
  });

  /**
   * Downloads this screen started, keyed by a local id.
   *
   * Downloads run concurrently, so a single set of progress fields no longer describes
   * "the" download: two of them reporting into shared state made the bar jump between
   * them, pointed Cancel at whichever reported last, and let the first one to finish
   * reset the screen to idle while the other was still running.
   */
  const [downloads, setDownloads] = useState<Record<string, ScreenDownload>>({});
  const [statusMessage, setStatusMessage] = useState<string>('');
  const [lastOutcome, setLastOutcome] = useState<'idle' | 'completed' | 'error'>('idle');
  const [cancelTarget, setCancelTarget] = useState<string | null>(null);
  const [showCancelConfirm, setShowCancelConfirm] = useState(false);
  /** Guards only the gap between the press and reading the clipboard, so a double tap
      cannot start the same url twice. */
  const [isReadingClipboard, setIsReadingClipboard] = useState(false);
  const [showExitConfirm, setShowExitConfirm] = useState(false);
  const [isCancelling, setIsCancelling] = useState(false);
  const [backgroundServiceRunning, setBackgroundServiceRunning] = useState(false);
  /** What the background work actually is. The module already reports this; the screen
      used to ignore it and call every kind of work "downloading". */
  const [backgroundPhase, setBackgroundPhase] = useState<string>('idle');
  const [backgroundQueueSize, setBackgroundQueueSize] = useState(0);
  const [privateModeEnabled, setPrivateModeEnabled] = useState(false);
  const [isPrivateToggleBusy, setIsPrivateToggleBusy] = useState(false);
  const [audioModeEnabled, setAudioModeEnabled] = useState(false);
  /** Smoothed transfer rate per download, so two of them cannot blend into one number. */
  const speedEmaBytesPerSecRef = useRef<Record<string, number>>({});
  const clientIdRef = useRef(0);

  const activeDownloads = Object.values(downloads);
  const isOngoingDownload = activeDownloads.length > 0;
  /** Kept for the parts of the screen that only care whether anything is happening. */
  const downloadState: DownloadState = isOngoingDownload
    ? (activeDownloads[0]?.state ?? 'downloading')
    : lastOutcome;

  /**
   * What the status line says. Applying a preset is not downloading, and a queue of
   * zero is not worth reporting — the line used to claim both.
   */
  const backgroundStatusLabel = (() => {
    if (!backgroundServiceRunning) return t('home.backgroundInactive');
    if (backgroundPhase === 'rendering') return t('home.backgroundRendering');
    if (backgroundQueueSize > 0) {
      return t('home.backgroundActive', { count: backgroundQueueSize });
    }
    return t('home.backgroundDownloading');
  })();


  useEffect(() => {
    let mounted = true;
    void getLocalBackgroundState()
      .then((state) => {
        if (!mounted) return;
        setBackgroundServiceRunning(state.serviceRunning);
        setBackgroundQueueSize(state.queueSize);
        setBackgroundPhase(state.notificationPhase || 'idle');
        setPrivateModeEnabled(Boolean(state.privateModeEnabled));
        setAudioModeEnabled(Boolean(state.audioModeEnabled));
      })
      .catch(() => undefined);

    void getLocalPrivateModeState()
      .then((state) => {
        if (!mounted) return;
        setPrivateModeEnabled(Boolean(state.enabled));
      })
      .catch(() => undefined);

    const sub = (() => {
      try {
        return listenBackgroundState((state) => {
          setBackgroundServiceRunning(Boolean(state.serviceRunning));
          setBackgroundQueueSize(state.queueSize || 0);
          setBackgroundPhase(state.notificationPhase || 'idle');
          if (typeof state.privateModeEnabled === 'boolean') {
            setPrivateModeEnabled(state.privateModeEnabled);
          }
          if (typeof state.audioModeEnabled === 'boolean') {
            setAudioModeEnabled(state.audioModeEnabled);
          }
        });
      } catch {
        return { remove: () => undefined };
      }
    })();

    return () => {
      mounted = false;
      sub.remove();
    };
  }, []);

  const handleDownload = useCallback(async () => {
    // Its own id, so this download's progress cannot be written over by another one
    // started while it is still running.
    clientIdRef.current += 1;
    const clientId = `d${clientIdRef.current}`;
    const dropEntry = () => {
      delete speedEmaBytesPerSecRef.current[clientId];
      setDownloads((current) => {
        const { [clientId]: _removed, ...rest } = current;
        return rest;
      });
    };

    try {
      setStatusMessage('');
      setLastOutcome('idle');

      // Get URL from clipboard
      setIsReadingClipboard(true);
      const url = await getUrlFromClipboard().finally(() => setIsReadingClipboard(false));
      if (!url) {
        setLastOutcome('error');
        setStatusMessage(t('home.noUrlInClipboard'));
        setTimeout(() => setLastOutcome('idle'), 3000);
        return;
      }

      setDownloads((current) => ({
        ...current,
        [clientId]: {
          clientId,
          taskId: null,
          state: 'starting',
          percent: 0,
          speedBytesPerSec: null,
          host: hostOf(url),
        },
      }));

      // Start download via API
      const result = await downloadMedia(url, (progress: DownloadProgress) => {
        let percent: number | null | undefined;
        let speed: number | null | undefined;

        if (progress.state === 'downloading') {
          if (typeof progress.progressPercent === 'number') {
            percent = progress.progressPercent;
            if (progress.progressPercent >= 99) speed = null;
          }
          if (typeof progress.speedBytesPerSec === 'number' && progress.speedBytesPerSec > 0) {
            if (typeof progress.progressPercent === 'number' && progress.progressPercent >= 99) {
              speed = null;
            } else {
              const alpha = 0.22;
              const prev = speedEmaBytesPerSecRef.current[clientId];
              const smoothed = prev == null
                ? progress.speedBytesPerSec
                : prev + alpha * (progress.speedBytesPerSec - prev);
              speedEmaBytesPerSecRef.current[clientId] = smoothed;
              speed = smoothed;
            }
          }
        } else if (progress.state === 'processing' || progress.state === 'saving' || progress.state === 'starting') {
          percent = progress.state === 'starting' ? 0 : null;
          speed = null;
          delete speedEmaBytesPerSecRef.current[clientId];
        }

        setDownloads((current) => {
          const existing = current[clientId];
          if (!existing) return current;
          return {
            ...current,
            [clientId]: {
              ...existing,
              state: progress.state,
              taskId: progress.taskId ?? existing.taskId,
              percent: percent === undefined ? existing.percent : percent,
              speedBytesPerSec: speed === undefined ? existing.speedBytesPerSec : speed,
            },
          };
        });

        if (progress.errorMessage) {
          setStatusMessage(progress.errorMessage);
        }
      }, audioModeEnabled ? 'public' : (privateModeEnabled ? 'private' : 'public'), audioModeEnabled ? 'audio' : 'video');

      console.info('Download response:', result);

      // Audio downloads are moved into the public music library natively, so there
      // is nothing to save to the gallery here.
      if (!result.isPrivate && !result.isAudio) {
        // Save local file to device gallery
        setDownloads((current) => {
          const existing = current[clientId];
          if (!existing) return current;
          return { ...current, [clientId]: { ...existing, state: 'saving', percent: null, speedBytesPerSec: null } };
        });
        if (!result.localPath) {
          throw new Error(t('errors.FILE_NOT_FOUND'));
        }
        console.log('[HomeScreen] localPath:', result.localPath);
        console.log('[HomeScreen] filename:', result.filename);
        const saveResult = await downloadAndSaveFile(result.localPath, result.filename);
        console.log('[HomeScreen] downloadAndSaveFile returned:', saveResult);
      }

      // Success! Only this download leaves the list; anything else still running stays.
      dropEntry();
      setLastOutcome('completed');
      setStatusMessage(result.isAudio ? t('home.savedToMusic') : result.isPrivate ? t('home.privateSaved') : result.filename);
      setTimeout(() => {
        setLastOutcome('idle');
        setStatusMessage('');
      }, 3000);
    } catch (error) {
      dropEntry();
      const cancelCode = (error as { code?: string } | null)?.code;
      const isCancelled = cancelCode === 'DOWNLOAD_CANCELLED' || cancelCode === 'TASK_CANCELLED';
      if (isCancelled) {
        setLastOutcome('idle');
        setStatusMessage(t('errors.DOWNLOAD_CANCELLED'));
        return;
      }

      console.error('Download error:', error);

      setLastOutcome('error');
      setStatusMessage(t('home.downloadFailed'));
      setTimeout(() => setLastOutcome('idle'), 3000);
    }
  }, [privateModeEnabled, audioModeEnabled, t]);

  const openCancelConfirm = useCallback((clientId: string) => {
    setCancelTarget(clientId);
    setShowCancelConfirm(true);
  }, []);

  const closeCancelConfirm = useCallback(() => {
    if (isCancelling) return;
    setShowCancelConfirm(false);
  }, [isCancelling]);

  const confirmCancelDownload = useCallback(async () => {
    const taskId = cancelTarget ? downloads[cancelTarget]?.taskId : null;
    if (!taskId) {
      setShowCancelConfirm(false);
      setCancelTarget(null);
      return;
    }
    setIsCancelling(true);
    try {
      const result = await cancelTask(taskId);
      if (!result.success) {
        setStatusMessage(t('home.cancelRequestFailed'));
      }
    } catch {
      setStatusMessage(t('home.cancelRequestFailed'));
    } finally {
      setIsCancelling(false);
      setShowCancelConfirm(false);
      setCancelTarget(null);
    }
  }, [cancelTarget, downloads, t]);

  const openSettings = useCallback(() => {
    router.push('/settings');
  }, [router]);

  const openPrivateVideos = useCallback(() => {
    router.push('/private-videos');
  }, [router]);

  const openSounds = useCallback(() => {
    // Cast: expo-router typed routes regenerate to include this screen at build time.
    router.push('/sounds' as Href);
  }, [router]);

  const openMemes = useCallback(() => {
    router.push('/memes' as Href);
  }, [router]);

  const handleSelectAudioMode = useCallback(async (next: boolean) => {
    if (next === audioModeEnabled) return;
    setAudioModeEnabled(next); // optimistic
    try {
      const result = await setLocalAudioModeEnabled(next);
      const resolved = Boolean(result.enabled);
      setAudioModeEnabled(resolved);
      // Audio mode forces public output, so enabling it clears private mode natively.
      if (resolved) setPrivateModeEnabled(false);
    } catch {
      setAudioModeEnabled(!next); // revert on failure
    }
  }, [audioModeEnabled]);

  // Intercept Android hardware / gesture back on the home screen: instead of letting
  // the app exit silently, ask for confirmation via our own modal. When the modal is
  // open it owns the back press (via onRequestClose), so this only fires from the bare
  // home screen.
  useFocusEffect(
    useCallback(() => {
      const onBackPress = () => {
        setShowExitConfirm(true);
        return true;
      };
      const sub = BackHandler.addEventListener('hardwareBackPress', onBackPress);
      return () => sub.remove();
    }, [])
  );

  // Transient feedback, so give it a lifetime. It was only ever cleared when a new
  // download started, which let a message outlive the thing it described: toggling
  // private mode on, then enabling audio mode (which clears private mode natively),
  // left "Private mode enabled" sitting under a chip that read OFF.
  useEffect(() => {
    if (!statusMessage) return;
    const timer = setTimeout(() => setStatusMessage(''), STATUS_MESSAGE_TTL_MS);
    return () => clearTimeout(timer);
  }, [statusMessage]);

  const handleTogglePrivateMode = useCallback(async () => {
    if (isPrivateToggleBusy) return;
    setIsPrivateToggleBusy(true);
    try {
      const nextEnabled = !privateModeEnabled;
      const result = await setLocalPrivateModeEnabled(nextEnabled);
      setPrivateModeEnabled(Boolean(result.enabled));
      setStatusMessage(
        result.enabled ? t('home.privateModeEnabled') : t('home.privateModeDisabled')
      );
    } catch (error) {
      const message = error instanceof Error ? error.message : t('errors.UNKNOWN_ERROR');
      setStatusMessage(message);
    } finally {
      setIsPrivateToggleBusy(false);
    }
  }, [isPrivateToggleBusy, privateModeEnabled, t]);


  return (
    <LinearGradient
      colors={[colors.background, colors.accent + '15', colors.background]}
      locations={[0, 0.5, 1]}
      style={styles.gradient}
    >
      <SafeAreaView style={styles.container}>
        {/* Header */}
        <View style={styles.header}>
          <View style={styles.headerLeft}>
            {/* Neon Title with Sixtyfour Font.
                A wordmark, not body text: it must never wrap or track the system font
                size. Sixtyfour is an unusually wide face, so at a 1.3x system font
                scale — common on Samsung — it overflowed and broke onto a second line.
                allowFontScaling stops the scale from applying, and the single line plus
                auto-shrink guarantees it fits whatever width is left after the header
                actions take theirs. */}
            <Text
              allowFontScaling={false}
              numberOfLines={1}
              adjustsFontSizeToFit
              minimumFontScale={0.7}
              style={[
                styles.title,
                {
                  color: colors.accent,
                  textShadowColor: colors.accent,
                  textShadowOffset: { width: 0, height: 0 },
                  textShadowRadius: 15,
                  fontFamily: fontsLoaded ? 'Sixtyfour_400Regular' : undefined,
                },
              ]}
            >
              {t('common.appName')}
            </Text>
          </View>
          <View style={styles.headerActions}>
            {/* Labelled, not icon-only. These go to entirely different screens,
                and an icon alone gives no way to know which before tapping it. */}
            <Pressable
              accessibilityRole="button"
              accessibilityLabel={t('home.privateVault')}
              accessibilityHint={t('home.privateVaultHint')}
              onPress={openPrivateVideos}
              style={({ pressed }) => [
                styles.headerNavButton,
                { backgroundColor: pressed ? colors.surfaceHover : colors.surface },
              ]}
              hitSlop={6}
            >
              <Ionicons name="shield-checkmark-outline" size={19} color={colors.text} />
              <Text allowFontScaling={false} numberOfLines={1} style={[styles.headerNavLabel, { color: colors.textMuted }]}>
                {t('home.navVault')}
              </Text>
            </Pressable>
            <Pressable
              accessibilityRole="button"
              accessibilityLabel={t('home.musicLibrary')}
              accessibilityHint={t('home.musicLibraryHint')}
              onPress={openSounds}
              style={({ pressed }) => [
                styles.headerNavButton,
                { backgroundColor: pressed ? colors.surfaceHover : colors.surface },
              ]}
              hitSlop={6}
            >
              <Ionicons name="musical-notes-outline" size={19} color={colors.text} />
              <Text allowFontScaling={false} numberOfLines={1} style={[styles.headerNavLabel, { color: colors.textMuted }]}>
                {t('home.navMusic')}
              </Text>
            </Pressable>
            <Pressable
              accessibilityRole="button"
              accessibilityLabel={t('home.navMemes')}
              onPress={openMemes}
              style={({ pressed }) => [
                styles.headerNavButton,
                { backgroundColor: pressed ? colors.surfaceHover : colors.surface },
              ]}
              hitSlop={6}
            >
              <Ionicons name="happy-outline" size={19} color={colors.text} />
              <Text allowFontScaling={false} numberOfLines={1} style={[styles.headerNavLabel, { color: colors.textMuted }]}>
                {t('home.navMemes')}
              </Text>
            </Pressable>
            <Pressable
              accessibilityRole="button"
              accessibilityLabel={t('home.navSettings')}
              onPress={openSettings}
              style={({ pressed }) => [
                styles.headerNavButton,
                { backgroundColor: pressed ? colors.surfaceHover : colors.surface },
              ]}
              hitSlop={6}
            >
              <Ionicons name="settings-outline" size={19} color={colors.text} />
              <Text allowFontScaling={false} numberOfLines={1} style={[styles.headerNavLabel, { color: colors.textMuted }]}>
                {t('home.navSettings')}
              </Text>
            </Pressable>
          </View>
        </View>

        {/* Main Content */}
        <View style={styles.content}>
          <DownloadButton
            onPress={handleDownload}
            state={downloadState}
            // Downloads run concurrently, so another one is welcome while others are in
            // flight. Only the moment between the press and the clipboard read is blocked.
            disabled={isReadingClipboard}
          />

          {/* Modes sit WITH the download button because they modify what it does, not
              with navigation. They stay inline rather than behind a modal: the cost of
              not noticing private mode is a file saved somewhere you did not intend,
              so the state has to be readable without opening anything. State is carried
              by the label as well as the colour, so it does not depend on seeing hue. */}
          <View style={styles.modeRow}>
            {/* A choice, not a toggle. "Audio off" never meant silent video — it meant
                video. Presenting the two as alternatives says what actually happens,
                and matches that they are mutually exclusive. */}
            <View style={[styles.modeSegment, { borderColor: colors.border, backgroundColor: colors.surface }]}>
              <Pressable
                accessibilityRole="radio"
                accessibilityState={{ checked: !audioModeEnabled }}
                accessibilityLabel={t('home.modeVideo')}
                onPress={() => handleSelectAudioMode(false)}
                style={[
                  styles.modeSegmentOption,
                  !audioModeEnabled && { backgroundColor: colors.accent + '22' },
                ]}
              >
                <Ionicons
                  name="videocam-outline"
                  size={16}
                  color={!audioModeEnabled ? colors.accent : colors.textMuted}
                />
                <Text numberOfLines={1} style={[styles.modeChipLabel, { color: !audioModeEnabled ? colors.text : colors.textMuted }]}>
                  {t('home.modeVideo')}
                </Text>
              </Pressable>
              <Pressable
                accessibilityRole="radio"
                accessibilityState={{ checked: audioModeEnabled }}
                accessibilityLabel={t('home.modeAudioOnly')}
                onPress={() => handleSelectAudioMode(true)}
                style={[
                  styles.modeSegmentOption,
                  audioModeEnabled && { backgroundColor: colors.accent + '22' },
                ]}
              >
                <Ionicons
                  name="musical-notes-outline"
                  size={16}
                  color={audioModeEnabled ? colors.accent : colors.textMuted}
                />
                <Text numberOfLines={1} style={[styles.modeChipLabel, { color: audioModeEnabled ? colors.text : colors.textMuted }]}>
                  {t('home.modeAudioOnly')}
                </Text>
              </Pressable>
            </View>
            <Pressable
              accessibilityRole="switch"
              accessibilityState={{ checked: privateModeEnabled, disabled: audioModeEnabled }}
              accessibilityLabel={privateModeEnabled ? t('home.privateModeOn') : t('home.privateModeOff')}
              accessibilityHint={privateModeEnabled ? t('home.privateModeHintOn') : t('home.privateModeHintOff')}
              onPress={handleTogglePrivateMode}
              disabled={isPrivateToggleBusy || audioModeEnabled}
              style={({ pressed }) => [
                styles.modeChip,
                {
                  backgroundColor: privateModeEnabled ? colors.accent + '1F' : (pressed ? colors.surfaceHover : colors.surface),
                  borderColor: privateModeEnabled ? colors.accent : colors.border,
                  opacity: (isPrivateToggleBusy || audioModeEnabled) ? 0.45 : 1,
                },
              ]}
            >
              {isPrivateToggleBusy ? (
                <ActivityIndicator size="small" color={colors.text} />
              ) : (
                <Ionicons
                  name={privateModeEnabled ? 'lock-closed' : 'lock-open-outline'}
                  size={17}
                  color={privateModeEnabled ? colors.accent : colors.textMuted}
                />
              )}
              <Text numberOfLines={1} style={[styles.modeChipLabel, { color: privateModeEnabled ? colors.text : colors.textMuted }]}>
                {t('home.modePrivate')}
              </Text>
              <Text numberOfLines={1} style={[styles.modeChipState, { color: privateModeEnabled ? colors.accent : colors.textMuted }]}>
                {privateModeEnabled ? t('home.modeOn') : t('home.modeOff')}
              </Text>
            </Pressable>
          </View>

          {/* Always present, so the absence of activity is stated rather than implied. */}
          <View style={styles.statusLine}>
            <Ionicons
              name={
                !backgroundServiceRunning
                  ? 'cloud-offline-outline'
                  : backgroundPhase === 'rendering'
                    ? 'color-wand-outline'
                    : 'cloud-download-outline'
              }
              size={13}
              color={backgroundServiceRunning ? colors.accent : colors.textMuted}
            />
            <Text numberOfLines={1} style={[styles.statusLineText, { color: colors.textMuted }]}>
              {backgroundStatusLabel}
            </Text>
          </View>

          {activeDownloads.map((item) => {
            const percent = progressOf(item);
            const speed = speedLabelOf(item);
            return (
              <View key={item.clientId} style={styles.progressSection}>
                <Text style={[styles.progressText, { color: colors.text }]}>
                  {item.state === 'processing'
                    ? t('home.processing')
                    : item.state === 'saving'
                      ? t('common.loading')
                      : t('home.downloadProgress', { percent: percent ?? 0 })}
                  {activeDownloads.length > 1 && item.host ? ` — ${item.host}` : ''}
                </Text>
                {percent !== null ? (
                  <View style={[styles.progressTrack, { backgroundColor: colors.surfaceHover }]}>
                    <View
                      style={[
                        styles.progressFill,
                        {
                          width: `${percent}%`,
                          backgroundColor: colors.accent,
                        },
                      ]}
                    />
                  </View>
                ) : (
                  <ActivityIndicator size="small" color={colors.accent} />
                )}
                {speed ? (
                  <Text style={[styles.speedText, { color: colors.textMuted }]}>
                    {speed}
                  </Text>
                ) : null}
                {item.taskId ? (
                  <Pressable
                    onPress={() => openCancelConfirm(item.clientId)}
                    style={({ pressed }) => [
                      styles.cancelButton,
                      {
                        backgroundColor: pressed ? colors.error + '22' : colors.error + '16',
                        borderColor: colors.error + '66',
                      },
                    ]}
                  >
                    <Ionicons name="close-circle-outline" size={18} color={colors.error} />
                    <Text style={[styles.cancelButtonText, { color: colors.error }]}>
                      {t('home.cancelDownload')}
                    </Text>
                  </Pressable>
                ) : null}
              </View>
            );
          })}

          {statusMessage ? (
            <Text
              style={[
                styles.statusMessage,
                {
                  color:
                    downloadState === 'error'
                      ? colors.error
                      : downloadState === 'completed'
                        ? colors.success
                        : colors.textMuted,
                },
              ]}
            >
              {statusMessage}
            </Text>
          ) : null}
        </View>

        <Modal
          visible={showCancelConfirm}
          transparent
          animationType="fade"
          onRequestClose={closeCancelConfirm}
        >
          <View style={styles.modalOverlay}>
            <View style={[styles.modalCard, { backgroundColor: colors.surface, borderColor: colors.border }]}>
              <Text style={[styles.modalTitle, { color: colors.text }]}>
                {t('home.cancelDownloadTitle')}
              </Text>
              <Text style={[styles.modalMessage, { color: colors.textMuted }]}>
                {t('home.cancelDownloadMessage')}
              </Text>

              <View style={styles.modalActions}>
                <Pressable
                  onPress={closeCancelConfirm}
                  disabled={isCancelling}
                  style={({ pressed }) => [
                    styles.modalButton,
                    {
                      backgroundColor: pressed ? colors.surfaceHover : colors.surface,
                      borderColor: colors.border,
                    },
                  ]}
                >
                  <Text style={[styles.modalButtonText, { color: colors.text }]}>{t('common.cancel')}</Text>
                </Pressable>

                <Pressable
                  onPress={confirmCancelDownload}
                  disabled={isCancelling}
                  style={({ pressed }) => [
                    styles.modalButton,
                    {
                      backgroundColor: pressed ? colors.error + '22' : colors.error + '16',
                      borderColor: colors.error + '66',
                    },
                  ]}
                >
                  {isCancelling ? (
                    <ActivityIndicator size="small" color={colors.error} />
                  ) : (
                    <Text style={[styles.modalButtonText, { color: colors.error }]}>
                      {t('home.confirmCancelDownload')}
                    </Text>
                  )}
                </Pressable>
              </View>
            </View>
          </View>
        </Modal>

        <Modal
          visible={showExitConfirm}
          transparent
          animationType="fade"
          onRequestClose={() => setShowExitConfirm(false)}
        >
          <View style={styles.modalOverlay}>
            <View style={[styles.modalCard, { backgroundColor: colors.surface, borderColor: colors.border }]}>
              <Text style={[styles.modalTitle, { color: colors.text }]}>{t('home.exitTitle')}</Text>
              <Text style={[styles.modalMessage, { color: colors.textMuted }]}>{t('home.exitMessage')}</Text>

              <View style={styles.modalActions}>
                <Pressable
                  onPress={() => setShowExitConfirm(false)}
                  style={({ pressed }) => [
                    styles.modalButton,
                    { backgroundColor: pressed ? colors.surfaceHover : colors.surface, borderColor: colors.border },
                  ]}
                >
                  <Text style={[styles.modalButtonText, { color: colors.text }]}>{t('common.cancel')}</Text>
                </Pressable>

                <Pressable
                  onPress={() => {
                    setShowExitConfirm(false);
                    BackHandler.exitApp();
                  }}
                  style={({ pressed }) => [
                    styles.modalButton,
                    { backgroundColor: pressed ? colors.error + '22' : colors.error + '16', borderColor: colors.error + '66' },
                  ]}
                >
                  <Text style={[styles.modalButtonText, { color: colors.error }]}>{t('home.exitConfirm')}</Text>
                </Pressable>
              </View>
            </View>
          </View>
        </Modal>
      </SafeAreaView>
    </LinearGradient>
  );
}

const styles = StyleSheet.create({
  gradient: {
    flex: 1,
  },
  container: {
    flex: 1,
  },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 20,
    paddingTop: 16,
    paddingBottom: 8,
    overflow: 'visible',
  },
  headerLeft: {
    flexDirection: 'row',
    alignItems: 'center',
    overflow: 'visible',
    flex: 1,
    marginRight: 12,
  },
  title: {
    fontSize: 22,
    fontWeight: '400',
    letterSpacing: 2,
  },
  headerActions: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
    flexShrink: 0,
  },
  headerNavButton: {
    minWidth: 52,
    paddingHorizontal: 6,
    paddingVertical: 6,
    borderRadius: 12,
    alignItems: 'center',
    justifyContent: 'center',
    gap: 2,
  },
  // Fixed size: these labels must not grow with the system font setting or they push
  // the wordmark out of the header.
  headerNavLabel: { fontSize: 9, fontWeight: '600', letterSpacing: 0.2 },
  content: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
    paddingBottom: 60,
  },
  // Content-width and centred rather than a grid of flex:1 cells. A two-across grid
  // only looks right at an even count — a third toggle would strand one on its own row
  // — and full-width cells fight a screen composed around a single centred focal point.
  // Stacked and centred rather than one wide row. The segment is a binary choice and
  // the vault pill is a single toggle, so their widths can never match — side by side
  // they read as misaligned however they are spaced. Stacking also keeps the group
  // narrow, which suits a screen built around one centred focal point, and it extends
  // to further controls without a grid that breaks at odd counts.
  // Matched to the download square so the three stack as one column of equal width.
  // Taken from the button's own exported size rather than repeated, so the two cannot
  // drift apart.
  modeRow: {
    width: DOWNLOAD_BUTTON_SIZE,
    alignItems: 'stretch',
    gap: 8,
    marginTop: 18,
  },
  modeChip: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 6,
    borderWidth: 1,
    borderRadius: 999,
    paddingVertical: 9,
    paddingHorizontal: 14,
    minHeight: 40,
  },
  modeChipLabel: { fontSize: 13, fontWeight: '600' },
  modeSegment: {
    flexDirection: 'row',
    borderWidth: 1,
    borderRadius: 999,
    overflow: 'hidden',
    minHeight: 40,
  },
  modeSegmentOption: {
    flex: 1,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 6,
    paddingVertical: 9,
    paddingHorizontal: 8,
  },
  modeChipState: { fontSize: 10, fontWeight: '700', letterSpacing: 0.4 },
  statusLine: { flexDirection: 'row', alignItems: 'center', gap: 6, marginTop: 12 },
  statusLineText: { fontSize: 11 },
  actionTextBlock: {
    flex: 1,
    minWidth: 0,
    gap: 2,
  },
  actionTitle: {
    fontSize: 14,
    fontWeight: '700',
  },
  actionSubtitle: {
    fontSize: 11.5,
    fontWeight: '500',
  },
  actionTrailing: {
    marginLeft: 10,
  },
  modeBadge: {
    minWidth: 58,
    borderWidth: 1,
    borderRadius: 999,
    paddingHorizontal: 10,
    paddingVertical: 5,
    alignItems: 'center',
    justifyContent: 'center',
  },
  statusMessage: {
    marginTop: 24,
    fontSize: 14,
    textAlign: 'center',
    maxWidth: 280,
  },
  progressSection: {
    width: 260,
    marginTop: 18,
    alignItems: 'center',
    gap: 10,
  },
  progressText: {
    fontSize: 13,
    fontWeight: '600',
  },
  progressTrack: {
    width: '100%',
    height: 8,
    borderRadius: 999,
    overflow: 'hidden',
  },
  progressFill: {
    height: '100%',
    borderRadius: 999,
  },
  speedText: {
    fontSize: 12,
    fontWeight: '600',
  },
  cancelButton: {
    marginTop: 12,
    borderWidth: 1,
    borderRadius: 12,
    paddingHorizontal: 14,
    paddingVertical: 10,
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
  },
  cancelButtonText: {
    fontSize: 13,
    fontWeight: '600',
  },
  modalOverlay: {
    flex: 1,
    backgroundColor: 'rgba(0,0,0,0.45)',
    justifyContent: 'center',
    alignItems: 'center',
    paddingHorizontal: 24,
  },
  modalCard: {
    width: '100%',
    borderWidth: 1,
    borderRadius: 16,
    padding: 18,
    gap: 12,
  },
  modalTitle: {
    fontSize: 17,
    fontWeight: '700',
  },
  modalMessage: {
    fontSize: 14,
    lineHeight: 20,
  },
  modalActions: {
    flexDirection: 'row',
    justifyContent: 'flex-end',
    gap: 10,
    marginTop: 6,
  },
  modalButton: {
    minWidth: 110,
    minHeight: 40,
    borderRadius: 10,
    borderWidth: 1,
    alignItems: 'center',
    justifyContent: 'center',
    paddingHorizontal: 10,
  },
  modalButtonText: {
    fontSize: 13,
    fontWeight: '600',
  },
});
