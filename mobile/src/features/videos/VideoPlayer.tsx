import { Ionicons } from '@expo/vector-icons';
import { useSegments } from 'expo-router';
import React, { useEffect, useMemo, useRef, useState, useSyncExternalStore } from 'react';
import { useTranslation } from 'react-i18next';
import { PanResponder, Pressable, StyleSheet, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import {
  getLanguages,
  getPlayerState,
  listLocalVideos,
  onPlayerChanged,
  seekPlayer,
  setPlayerPaused,
  skipPlayerTo,
} from '@/src/api';
import { AppText as Text } from '@/src/components';
import { Player } from '@/src/features/player/Player';
import { useTheme } from '@/src/theme';

import {
  adoptVideos,
  closeVideo,
  expandVideo,
  setVideoRepeat,
  useVideoBarLift,
  useVideoRepeat,
  useVideoSession,
  videoAt,
  videoStopped,
} from './open';

const BAR_HEIGHT = 60;
/** From the screen's sides to the bar's. */
const BAR_SIDE = 14;
const BAR_GAP = 6;
/** Screens that are a player of their own: this one is not drawn over them, and is quiet. */
const OTHER_PLAYERS = ['watch-player', 'private-player', 'sound-player'];

function useOtherPlayer(): boolean {
  const segments = useSegments() as string[];
  return segments.some((segment) => OTHER_PLAYERS.includes(segment));
}

// Where the video is, a few times a second. Kept apart from the player's own state: only
// the bar's buttons and its line are drawn again for it, not the player.
interface Place {
  positionMs: number;
  durationMs: number;
  paused: boolean;
}
let place: Place = { positionMs: 0, durationMs: 0, paused: false };
const watchers = new Set<() => void>();
function tell(next: Place) {
  place = next;
  watchers.forEach((watcher) => watcher());
}
function usePlace(): Place {
  return useSyncExternalStore(
    (watcher) => {
      watchers.add(watcher);
      return () => watchers.delete(watcher);
    },
    () => place,
  );
}

/**
 * The room the small player takes under every screen, so that it covers none of them.
 * Nothing when nothing plays, or when the player is the whole screen; and nothing under a
 * screen that keeps the room itself, above a bar of its own ([VideoBarRoom]).
 */
export function VideoBarSpace() {
  const { colors } = useTheme();
  const session = useVideoSession();
  const insets = useSafeAreaInsets();
  const other = useOtherPlayer();
  const lift = useVideoBarLift();
  if (!session || session.expanded || other || lift > 0) return null;
  // The screens' own colour: this strip is seen around the bar, as the foot of the screen above it.
  return <View style={{ height: BAR_HEIGHT + BAR_GAP * 2 + insets.bottom, backgroundColor: colors.background }} />;
}

/**
 * The same room, for a screen to put above a bar of its own at the bottom, with
 * `setVideoBarLift` given that bar's height: the small player then sits above the bar.
 */
export function VideoBarRoom() {
  const session = useVideoSession();
  if (!session || session.expanded) return null;
  return <View style={{ height: BAR_HEIGHT + BAR_GAP * 2 }} />;
}

/**
 * The app's video player, over every screen: the whole screen, or small in a bar at the
 * bottom with the video still playing in it. It is one player in both, only resized, so
 * going between them does not stop or restart anything. Closing the large one makes it
 * small; the bar's own button stops it.
 *
 * The queue is the player's (`MpvEngine`), so it plays on with the app out of sight. This
 * component is only told which video of the list plays.
 */
export function VideoPlayer() {
  const { colors } = useTheme();
  const insets = useSafeAreaInsets();
  const session = useVideoSession();
  const other = useOtherPlayer();
  const lift = useVideoBarLift();
  const repeat = useVideoRepeat();
  const [languages, setLanguages] = useState<string[] | null>(null);

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

  // Stopped from outside this component (the app swiped away): the bar goes. And a queue
  // found playing with nothing here to match it, after a reload: taken up, small.
  useEffect(() => {
    const subscription = onPlayerChanged((state) => {
      if (!state.active) videoStopped();
      else if (typeof state.paused === 'boolean' && state.paused !== place.paused) tell({ ...place, paused: state.paused });
    });
    void getPlayerState()
      .then(async (state) => {
        if (!state.active || !state.ids?.length) return;
        const byId = new Map((await listLocalVideos()).map((v) => [v.id, v]));
        const videos = state.ids.map((id) => byId.get(id)).filter((v) => v != null);
        if (videos.length === state.ids.length) adoptVideos(videos, state.index ?? 0);
      })
      .catch(() => undefined);
    return () => subscription.remove();
  }, []);

  const videos = session?.videos;
  const start = session?.start ?? 0;
  const queue = useMemo(
    () =>
      videos
        ? { items: videos.map((v) => ({ id: v.id, uri: v.uri, title: v.title, uploader: v.uploader ?? null })), index: start }
        : null,
    [videos, start],
  );

  if (!session || !queue || other) return null;
  const { index, expanded } = session;
  const video = session.videos[index] ?? session.videos[0];
  // Small, the picture fills its box, cut to it; a video taller than wide gets a box less
  // wide, so less of it is cut away.
  const tall = video.height > video.width;

  // The same three views around the player in both sizes, only styled apart: were it put
  // elsewhere in the tree, React would make the player again, and the video with it. And
  // none of them collapsable, so the native tree is the same in both sizes too (see Player).
  return (
    <View style={StyleSheet.absoluteFill} pointerEvents="box-none" collapsable={false}>
      <View
        collapsable={false}
        style={
          expanded
            ? styles.large
            : [styles.bar, { bottom: insets.bottom + lift + BAR_GAP, backgroundColor: colors.surfaceHover, borderColor: colors.border }]
        }
      >
        <View style={expanded ? styles.fill : [styles.box, tall && styles.boxTall]} collapsable={false}>
          <Player
            source={null}
            queue={languages ? queue : null}
            item={index}
            onItem={videoAt}
            title={video.title}
            languages={languages ?? []}
            background
            upright={tall}
            mini={!expanded}
            repeat={repeat}
            onRepeat={setVideoRepeat}
            onProgress={tell}
            onClose={() => expandVideo(false)}
          />
          {!expanded ? (
            <Pressable
              style={StyleSheet.absoluteFill}
              onPress={() => expandVideo(true)}
              accessibilityRole="button"
              accessibilityLabel={video.title}
            />
          ) : null}
        </View>
        {!expanded ? (
          <>
            <Bar title={video.title} uploader={video.uploader ?? null} next={index < session.videos.length - 1 ? index + 1 : null} />
          </>
        ) : null}
      </View>
      {!expanded ? <Scrub bottom={insets.bottom + lift + BAR_GAP} /> : null}
    </View>
  );
}

/**
 * What is beside the small picture: what plays, and three buttons, to pause it, go to the
 * next and stop it. No more than that: the rest is a tap away, in the large player.
 */
function Bar({ title, uploader, next }: { title: string; uploader: string | null; next: number | null }) {
  const { t } = useTranslation();
  const { colors } = useTheme();
  const { paused } = usePlace();
  return (
    <>
      <Pressable style={styles.words} onPress={() => expandVideo(true)} accessibilityRole="button" accessibilityLabel={title}>
        <Text style={[styles.title, { color: colors.text }]} numberOfLines={1}>{title}</Text>
        {uploader ? <Text style={[styles.subtitle, { color: colors.textMuted }]} numberOfLines={1}>{uploader}</Text> : null}
      </Pressable>
      <Pressable
        onPress={() => void setPlayerPaused(!paused)}
        hitSlop={8}
        accessibilityRole="button"
        accessibilityLabel={paused ? t('player.play') : t('player.pause')}
      >
        <Ionicons name={paused ? 'play' : 'pause'} size={24} color={colors.text} />
      </Pressable>
      {next != null ? (
        <Pressable onPress={() => void skipPlayerTo(next)} hitSlop={8} accessibilityRole="button" accessibilityLabel={t('videos.next')}>
          <Ionicons name="play-skip-forward" size={21} color={colors.text} />
        </Pressable>
      ) : null}
      <Pressable onPress={closeVideo} hitSlop={8} accessibilityRole="button" accessibilityLabel={t('videos.stop')}>
        <Ionicons name="close" size={23} color={colors.textMuted} />
      </Pressable>
    </>
  );
}

/**
 * How far the video is, drawn as the bar's own top edge, round its corners. It is laid over
 * the bar, of the bar's own shape, and cut to that shape: so the line bends with the
 * corners and nothing of it stands above them. Dragged, it seeks there on letting go; it
 * takes touches in a strip along the top, the line itself being too thin to hold.
 */
function Scrub({ bottom }: { bottom: number }) {
  const { colors } = useTheme();
  const { positionMs, durationMs } = usePlace();
  const [dragMs, setDragMs] = useState<number | null>(null);
  const latest = useRef({ durationMs: 0, width: 1, dragMs: null as number | null });
  useEffect(() => {
    latest.current.durationMs = durationMs;
  }, [durationMs]);

  const [responder] = useState(() => {
    const at = (x: number) => {
      const { durationMs: length, width } = latest.current;
      if (length <= 0) return;
      const ms = Math.min(1, Math.max(0, x / width)) * length;
      latest.current.dragMs = ms;
      setDragMs(ms);
    };
    const end = () => {
      if (latest.current.dragMs !== null) void seekPlayer(latest.current.dragMs);
      latest.current.dragMs = null;
      setDragMs(null);
    };
    return PanResponder.create({
      onStartShouldSetPanResponder: () => true,
      onMoveShouldSetPanResponder: () => true,
      onPanResponderGrant: (e) => at(e.nativeEvent.locationX),
      onPanResponderMove: (e) => at(e.nativeEvent.locationX),
      onPanResponderRelease: end,
      onPanResponderTerminate: end,
    });
  });

  const fraction = durationMs > 0 ? Math.min(1, (dragMs ?? positionMs) / durationMs) : 0;
  return (
    <View style={[styles.over, { bottom }]} pointerEvents="box-none">
      <View
        style={[styles.line, dragMs !== null && styles.lineHeld, { width: `${fraction * 100}%`, backgroundColor: colors.accent }]}
        pointerEvents="none"
      />
      <View
        style={styles.scrub}
        onLayout={(e) => {
          latest.current.width = Math.max(1, e.nativeEvent.layout.width);
        }}
        {...responder.panHandlers}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  fill: { flex: 1 },
  large: { ...StyleSheet.absoluteFillObject, backgroundColor: '#000' },
  bar: {
    position: 'absolute',
    left: BAR_SIDE,
    right: BAR_SIDE,
    height: BAR_HEIGHT,
    flexDirection: 'row',
    alignItems: 'center',
    gap: 14,
    borderRadius: 12,
    borderWidth: StyleSheet.hairlineWidth,
    paddingLeft: 6,
    paddingRight: 14,
  },
  box: { width: 85, height: 48, borderRadius: 7, overflow: 'hidden', backgroundColor: '#000' },
  boxTall: { width: 48 },
  words: { flex: 1, alignSelf: 'stretch', justifyContent: 'center', gap: 1 },
  title: { fontSize: 14, fontWeight: '600' },
  subtitle: { fontSize: 12 },
  over: { position: 'absolute', left: BAR_SIDE, right: BAR_SIDE, height: BAR_HEIGHT, borderRadius: 12, overflow: 'hidden' },
  line: { position: 'absolute', left: 0, top: 0, height: 3 },
  lineHeld: { height: 5 },
  scrub: { position: 'absolute', left: 0, right: 0, top: 0, height: 10 },
});
