import { Ionicons } from '@expo/vector-icons';
import { Image } from 'expo-image';
import { useRouter } from 'expo-router';
import React, { useCallback, useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Animated, AppState, PanResponder, Pressable, ScrollView, StyleSheet, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import TrackPlayer, {
  RepeatMode,
  State,
  useActiveTrack,
  usePlaybackState,
  useProgress,
} from 'react-native-track-player';

import {
  FAVORITES_PLAYLIST_ID,
  listLocalSoundPlaylists,
  listLocalSounds,
  setLocalSoundsFavorite,
  type LocalSound,
} from '@/src/api';
import { AppText as Text, TrackMetadata } from '@/src/components';
import { listAllPresets, type AudioPreset } from '@/src/features/audioPresets/presets';
import { cycleRepeatMode, handlePrevious, seekBy } from '@/src/services/trackPlayerService';
import { useTheme } from '@/src/theme';

/** How often the position is read while the screen can be seen, and "not at all" while it cannot. */
const PROGRESS_EVERY_MS = 250;
const PROGRESS_NEVER_MS = 24 * 60 * 60 * 1000;

/** Whether the app is in front: not with another app over it, nor with the screen off. */
function useAppInFront(): boolean {
  const [inFront, setInFront] = useState(AppState.currentState === 'active');
  useEffect(() => {
    const subscription = AppState.addEventListener('change', (state) => setInFront(state === 'active'));
    return () => subscription.remove();
  }, []);
  return inFront;
}

function fmt(seconds: number): string {
  if (!seconds || seconds < 0 || !isFinite(seconds)) return '0:00';
  const total = Math.floor(seconds);
  const m = Math.floor(total / 60);
  const s = total % 60;
  return `${m}:${s.toString().padStart(2, '0')}`;
}

export default function SoundPlayerScreen() {
  const { t } = useTranslation();
  const { colors } = useTheme();
  const router = useRouter();

  const track = useActiveTrack();
  const playback = usePlaybackState();
  // Only while the app is in front. Timers still run behind it while music plays, so left
  // open this screen went on reading the position four times a second and animating the bar
  // for nobody: half a processor's worth, and every one of those updates waited to be drawn,
  // so coming back replayed minutes of them before the screen caught up with the song.
  const inFront = useAppInFront();
  const progress = useProgress(inFront ? PROGRESS_EVERY_MS : PROGRESS_NEVER_MS);
  const [repeatMode, setRepeatMode] = React.useState<RepeatMode>(RepeatMode.Off);
  const [isFav, setIsFav] = React.useState(false);
  const trackId = track?.id != null ? String(track.id) : null;

  // The player only holds an RNTP track, which carries no format or preset detail.
  // Resolve the library entry so the metadata section can describe the real file.
  const [librarySong, setLibrarySong] = useState<LocalSound | null>(null);
  const [libraryById, setLibraryById] = useState<Map<string, LocalSound>>(new Map());
  const [presets, setPresets] = useState<AudioPreset[]>([]);
  /** Height of the scroll viewport, used to size the player pane to one screen. */
  const [viewportHeight, setViewportHeight] = useState(0);

  useEffect(() => {
    let mounted = true;
    void listLocalSounds()
      .then((library) => {
        if (!mounted) return;
        setLibraryById(new Map(library.songs.map((s) => [s.id, s])));
      })
      .catch(() => undefined);
    void listAllPresets()
      .then((all) => {
        if (mounted) setPresets(all);
      })
      .catch(() => undefined);
    return () => {
      mounted = false;
    };
  }, []);

  useEffect(() => {
    setLibrarySong(trackId ? libraryById.get(trackId) ?? null : null);
  }, [trackId, libraryById]);

  const barWidth = useRef(0);
  const durationRef = useRef(0);
  durationRef.current = progress.duration;

  // The fill + thumb are driven by an Animated.Value (0..1) rather than React state,
  // so the gesture updates them via direct native prop writes — no per-frame re-render
  // of the whole screen, which is what made scrubbing feel choppy.
  const progressAnim = useRef(new Animated.Value(0)).current;
  const widthInterp = React.useMemo(
    () => progressAnim.interpolate({ inputRange: [0, 1], outputRange: ['0%', '100%'], extrapolate: 'clamp' }),
    [progressAnim]
  );
  const [scrubbing, setScrubbing] = React.useState(false);
  // Seconds shown under the bar while dragging; updated only when the whole-second
  // value changes so re-renders stay rare during a drag.
  const [scrubLabelSec, setScrubLabelSec] = React.useState(0);
  const lastLabelSecRef = useRef(-1);
  // After release we keep showing the dropped position (and pause live-sync) until the
  // seek settles, otherwise the bar would glide back to the old position for a moment.
  const [pendingFraction, setPendingFraction] = React.useState<number | null>(null);
  const seekSettleTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  React.useEffect(() => {
    TrackPlayer.getRepeatMode().then(setRepeatMode).catch(() => undefined);
    return () => {
      if (seekSettleTimer.current) clearTimeout(seekSettleTimer.current);
    };
  }, []);

  const playing = playback.state === State.Playing;
  const buffering = playback.state === State.Buffering || playback.state === State.Loading;

  // Keep the bar synced to live playback while NOT dragging and no seek is settling.
  // timing() over the poll interval makes the fill glide between the 250ms polls.
  React.useEffect(() => {
    if (scrubbing || pendingFraction != null) return;
    const live = progress.duration > 0 ? progress.position / progress.duration : 0;
    Animated.timing(progressAnim, { toValue: live, duration: 240, useNativeDriver: false }).start();
  }, [progress.position, progress.duration, scrubbing, pendingFraction, progressAnim]);

  // `locationX` is relative to the touched bar, so a single PanResponder handles both
  // a tap (grant→release at one point) and a slide.
  const fractionAt = useCallback((locationX: number) => {
    const w = barWidth.current;
    if (!w) return 0;
    return Math.max(0, Math.min(locationX / w, 1));
  }, []);

  const applyScrub = useCallback(
    (locationX: number) => {
      const f = fractionAt(locationX);
      progressAnim.setValue(f); // direct native write — no React re-render
      const whole = Math.floor(f * durationRef.current);
      if (whole !== lastLabelSecRef.current) {
        lastLabelSecRef.current = whole;
        setScrubLabelSec(f * durationRef.current);
      }
      return f;
    },
    [fractionAt, progressAnim]
  );

  const panResponder = React.useMemo(
    () =>
      PanResponder.create({
        onStartShouldSetPanResponder: () => true,
        onMoveShouldSetPanResponder: () => true,
        onPanResponderTerminationRequest: () => false,
        onPanResponderGrant: (e) => {
          if (seekSettleTimer.current) {
            clearTimeout(seekSettleTimer.current);
            seekSettleTimer.current = null;
          }
          progressAnim.stopAnimation();
          setPendingFraction(null);
          setScrubbing(true);
          applyScrub(e.nativeEvent.locationX);
        },
        onPanResponderMove: (e) => {
          applyScrub(e.nativeEvent.locationX);
        },
        onPanResponderRelease: (e) => {
          const f = applyScrub(e.nativeEvent.locationX);
          const d = durationRef.current;
          setScrubbing(false);
          setPendingFraction(f); // hold the dropped position…
          if (d > 0) TrackPlayer.seekTo(f * d).catch(() => undefined);
          // …until the next couple of progress polls reflect the new position.
          if (seekSettleTimer.current) clearTimeout(seekSettleTimer.current);
          seekSettleTimer.current = setTimeout(() => setPendingFraction(null), 600);
        },
        onPanResponderTerminate: () => setScrubbing(false),
      }),
    [applyScrub, progressAnim]
  );

  // Play/pause. A plain play() won't restart a track that has already ended, so when
  // the song is finished we seek back to the start first — letting the play button
  // restart it (when not looping).
  const onTogglePlay = useCallback(async () => {
    if (playing) {
      TrackPlayer.pause().catch(() => undefined);
      return;
    }
    try {
      const { position, duration } = await TrackPlayer.getProgress();
      if (duration > 0 && position >= duration - 0.5) {
        await TrackPlayer.seekTo(0);
      }
    } catch {
      // ignore — fall through to play()
    }
    TrackPlayer.play().catch(() => undefined);
  }, [playing]);

  // Reflect the active track's favorite state.
  React.useEffect(() => {
    if (!trackId) {
      setIsFav(false);
      return;
    }
    let cancelled = false;
    listLocalSoundPlaylists()
      .then((pls) => {
        if (cancelled) return;
        const sys = pls.find((p) => p.system || p.id === FAVORITES_PLAYLIST_ID);
        setIsFav(Boolean(sys?.songIds.includes(trackId)));
      })
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, [trackId]);

  const onToggleFavorite = useCallback(async () => {
    if (!trackId) return;
    const next = !isFav;
    setIsFav(next); // optimistic
    try {
      await setLocalSoundsFavorite([trackId], next);
    } catch {
      setIsFav(!next);
    }
  }, [trackId, isFav]);

  const onToggleRepeat = useCallback(async () => {
    const next = await cycleRepeatMode(repeatMode);
    setRepeatMode(next);
  }, [repeatMode]);

  const repeatActive = repeatMode !== RepeatMode.Off;

  const displayPosition = scrubbing
    ? scrubLabelSec
    : pendingFraction != null
      ? pendingFraction * progress.duration
      : progress.position;

  if (!track) {
    return (
      <SafeAreaView style={[styles.container, { backgroundColor: colors.background }]}>
        <View style={styles.topBar}>
          <Pressable hitSlop={10} onPress={() => router.back()} style={styles.iconBtn}>
            <Ionicons name="chevron-down" size={28} color={colors.text} />
          </Pressable>
        </View>
        <View style={styles.centered}>
          <Ionicons name="musical-notes-outline" size={56} color={colors.textMuted} />
          <Text style={[styles.emptyText, { color: colors.textMuted }]}>{t('sounds.nowPlaying')}</Text>
        </View>
      </SafeAreaView>
    );
  }

  return (
    <SafeAreaView style={[styles.container, { backgroundColor: colors.background }]}>
      <View style={styles.topBar}>
        <Pressable hitSlop={10} onPress={() => router.back()} style={styles.iconBtn}>
          <Ionicons name="chevron-down" size={28} color={colors.text} />
        </Pressable>
        <Text style={[styles.topLabel, { color: colors.textMuted }]}>{t('sounds.nowPlaying')}</Text>
        <View style={styles.iconBtn} />
      </View>

      {/* The whole page scrolls; the top bar stays put. The player pane is sized to the
          scroll viewport measured at runtime, so on entry the transport fills the screen
          exactly and the details sit just below the fold — regardless of device size or
          inset. Measuring beats computing it from window height, which would have to
          guess at the top bar and safe-area insets. */}
      <ScrollView
        style={styles.pageScroll}
        contentContainerStyle={styles.pageScrollContent}
        onLayout={(event) => setViewportHeight(event.nativeEvent.layout.height)}
        showsVerticalScrollIndicator={false}
      >
        <View style={[styles.playerPane, viewportHeight > 0 ? { minHeight: viewportHeight } : null]}>

      <View style={styles.artworkWrap}>
        <View style={[styles.artwork, { backgroundColor: colors.surfaceHover }]}>
          {track.artwork ? (
            <Image source={{ uri: String(track.artwork) }} style={styles.artworkImg} contentFit="cover" />
          ) : (
            <Ionicons name="musical-note" size={96} color={colors.textMuted} />
          )}
        </View>
      </View>

      <View style={styles.meta}>
        <Text numberOfLines={2} style={[styles.title, { color: colors.text }]}>
          {track.title ?? ''}
        </Text>
        <Text numberOfLines={1} style={[styles.artist, { color: colors.textMuted }]}>
          {track.artist || t('sounds.unknownArtist')}
        </Text>
      </View>

      {/* Favorite (left) + repeat (right) — between metadata and the seek bar */}
      <View style={styles.repeatRow}>
        <Pressable hitSlop={10} onPress={onToggleFavorite} style={styles.repeatBtn}>
          <Ionicons
            name={isFav ? 'heart' : 'heart-outline'}
            size={22}
            color={isFav ? colors.accent : colors.textMuted}
          />
        </Pressable>
        <Pressable hitSlop={10} onPress={onToggleRepeat} style={styles.repeatBtn}>
          <Ionicons name="repeat" size={22} color={repeatActive ? colors.accent : colors.textMuted} />
          {repeatMode === RepeatMode.Track ? (
            <View style={[styles.repeatOneDot, { backgroundColor: colors.accent }]} />
          ) : null}
        </Pressable>
      </View>

      {/* Seek bar (tap or drag to seek) */}
      <View style={styles.seekWrap}>
        <View
          {...panResponder.panHandlers}
          onLayout={(e) => {
            barWidth.current = e.nativeEvent.layout.width;
          }}
          style={styles.seekTouch}
        >
          <View style={[styles.seekTrack, { backgroundColor: colors.surfaceHover }]}>
            <Animated.View style={[styles.seekFill, { backgroundColor: colors.accent, width: widthInterp }]} />
          </View>
          <Animated.View pointerEvents="none" style={[styles.thumbLayer, { left: widthInterp }]}>
            <View
              style={[styles.seekThumb, { backgroundColor: colors.accent, transform: [{ scale: scrubbing ? 1.3 : 1 }] }]}
            />
          </Animated.View>
        </View>
        <View style={styles.times}>
          <Text style={[styles.timeText, { color: colors.textMuted }]}>{fmt(displayPosition)}</Text>
          <Text style={[styles.timeText, { color: colors.textMuted }]}>{fmt(progress.duration)}</Text>
        </View>
      </View>

      {/* Transport controls: prev · −10 · play · +10 · next (symmetric around play) */}
      <View style={styles.controls}>
        <Pressable hitSlop={8} onPress={() => handlePrevious()} style={styles.smallBtn}>
          <Ionicons name="play-skip-back" size={30} color={colors.text} />
        </Pressable>

        <Pressable hitSlop={8} onPress={() => seekBy(-10)} style={styles.smallBtn}>
          <Ionicons name="play-back" size={28} color={colors.text} />
          <Text style={[styles.jumpLabel, { color: colors.textMuted }]}>10</Text>
        </Pressable>

        <Pressable onPress={onTogglePlay} style={[styles.playBtn, { backgroundColor: colors.accent }]}>
          <Ionicons name={playing ? 'pause' : 'play'} size={36} color={colors.background} />
        </Pressable>

        <Pressable hitSlop={8} onPress={() => seekBy(10)} style={styles.smallBtn}>
          <Ionicons name="play-forward" size={28} color={colors.text} />
          <Text style={[styles.jumpLabel, { color: colors.textMuted }]}>10</Text>
        </Pressable>

        <Pressable hitSlop={8} onPress={() => TrackPlayer.skipToNext().catch(() => undefined)} style={styles.smallBtn}>
          <Ionicons name="play-skip-forward" size={30} color={colors.text} />
        </Pressable>
      </View>

      {/* Fixed-height slot so toggling the buffering indicator never reflows the
          layout (which made the artwork resize and "jump" when seeking). */}
        <View style={styles.bufferingSlot}>
          {buffering ? <Text style={[styles.buffering, { color: colors.textMuted }]}>…</Text> : null}
        </View>
        </View>

        {/* Details for the current track, below the fold. */}
        {librarySong ? (
          <View style={styles.metaSection}>
            <View style={[styles.metaDivider, { backgroundColor: colors.border }]} />
            <TrackMetadata
              song={librarySong}
              presetName={resolvePresetName(librarySong.presetId, presets, t)}
              sourceTitle={
                librarySong.sourceSongId ? libraryById.get(librarySong.sourceSongId)?.title ?? null : null
              }
            />
          </View>
        ) : null}
      </ScrollView>
    </SafeAreaView>
  );
}

/** Resolve a recorded presetId to its display name, localising built-ins. */
function resolvePresetName(
  presetId: string | null | undefined,
  presets: AudioPreset[],
  t: (key: string) => string
): string | null {
  if (!presetId) return null;
  const preset = presets.find((p) => p.id === presetId);
  if (!preset) return presetId;
  return preset.nameKey ? t(preset.nameKey) : preset.name;
}

const styles = StyleSheet.create({
  container: { flex: 1, paddingHorizontal: 24 },
  pageScroll: { flex: 1 },
  pageScrollContent: { paddingBottom: 32 },
  // Takes over the column layout the container previously provided, so the artwork's
  // flex:1 still expands to fill one screen rather than competing with the details.
  playerPane: { justifyContent: 'center' },
  metaSection: { marginTop: 8 },
  metaDivider: { height: 1, marginBottom: 12, opacity: 0.5 },
  centered: { flex: 1, alignItems: 'center', justifyContent: 'center', gap: 12 },
  emptyText: { fontSize: 15 },
  topBar: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', paddingVertical: 10 },
  topLabel: { fontSize: 13, fontWeight: '600', textTransform: 'uppercase', letterSpacing: 1 },
  iconBtn: { width: 40, height: 40, alignItems: 'center', justifyContent: 'center' },
  artworkWrap: { flex: 1, alignItems: 'center', justifyContent: 'center', paddingVertical: 16 },
  artwork: {
    width: '88%',
    aspectRatio: 1,
    maxWidth: 360,
    borderRadius: 20,
    alignItems: 'center',
    justifyContent: 'center',
    overflow: 'hidden',
  },
  artworkImg: { width: '100%', height: '100%' },
  meta: { alignItems: 'center', gap: 6, marginBottom: 8 },
  title: { fontSize: 22, fontWeight: '700', textAlign: 'center' },
  artist: { fontSize: 15 },
  repeatRow: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginTop: 4 },
  repeatBtn: { alignItems: 'center', justifyContent: 'center', paddingVertical: 6, paddingHorizontal: 4 },
  seekWrap: { marginTop: 8, marginBottom: 8 },
  seekTouch: { height: 30, justifyContent: 'center' },
  seekTrack: { height: 4, borderRadius: 2, overflow: 'hidden' },
  seekFill: { height: '100%', borderRadius: 2 },
  // Full-height, zero-offset layer at the fill's end; centers the thumb on the track
  // both vertically (justify) and horizontally (align + negative margin) regardless
  // of the track's exact height.
  thumbLayer: { position: 'absolute', top: 0, bottom: 0, marginLeft: -8, width: 16, alignItems: 'center', justifyContent: 'center' },
  seekThumb: {
    width: 14,
    height: 14,
    borderRadius: 7,
    elevation: 3,
    shadowColor: '#000',
    shadowOpacity: 0.3,
    shadowRadius: 2,
    shadowOffset: { width: 0, height: 1 },
  },
  times: { flexDirection: 'row', justifyContent: 'space-between', marginTop: 4 },
  timeText: { fontSize: 12 },
  controls: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', paddingVertical: 24 },
  smallBtn: { alignItems: 'center', justifyContent: 'center', minWidth: 40 },
  jumpLabel: { fontSize: 9, fontWeight: '700', marginTop: -2 },
  playBtn: { width: 72, height: 72, borderRadius: 36, alignItems: 'center', justifyContent: 'center' },
  repeatOneDot: { width: 4, height: 4, borderRadius: 2, marginTop: 2 },
  bufferingSlot: { height: 20, alignItems: 'center', justifyContent: 'center' },
  buffering: { textAlign: 'center' },
});
