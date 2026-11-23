import { Ionicons } from '@expo/vector-icons';
import { Image } from 'expo-image';
import { useRouter, useSegments, type Href } from 'expo-router';
import React, { useEffect, useMemo, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { PanResponder, Pressable, StyleSheet, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import { getLanguages, getPlayerState, onPlayerChanged, seekPlayer, setPlayerPaused, skipPlayerTo, stopPlayer } from '@/src/api';
import { AppText as Text } from '@/src/components';
import { Player } from '@/src/features/player/Player';
import { useTheme } from '@/src/theme';

import {
  closeMedia,
  expandMedia,
  mediaAt,
  mediaPlace,
  mediaPlaceIs,
  mediaRepeatIs,
  mediaSession,
  mediaStopped,
  setMediaRepeat,
  useMediaBarLift,
  useMediaPlace,
  useMediaRepeat,
  useMediaSession,
} from './session';

const BAR_HEIGHT = 60;
const BAR_GAP = 6;
/** From the screen's sides to the bar's. */
const BAR_SIDE = 14;
/**
 * Screens that are a player themselves: the bar is not drawn over them. Two are players of
 * their own, which quiet this one as they begin; the third is the large player for a song,
 * of this same player.
 */
const PLAYER_SCREENS = ['watch-player', 'private-player', 'sound-player'];

function usePlayerScreen(): boolean {
  const segments = useSegments() as string[];
  return segments.some((segment) => PLAYER_SCREENS.includes(segment));
}

/**
 * The room the small player takes under every screen, so that it covers none of them.
 * Nothing when nothing plays, or when the player is the whole screen; and nothing under a
 * screen that keeps the room itself, above a bar of its own ([MediaBarRoom]).
 */
export function MediaBarSpace() {
  const { colors } = useTheme();
  const session = useMediaSession();
  const insets = useSafeAreaInsets();
  const other = usePlayerScreen();
  const lift = useMediaBarLift();
  if (!session || session.expanded || other || lift > 0) return null;
  // The screens' own colour: this strip is seen around the bar, as the foot of the screen above it.
  return <View style={{ height: BAR_HEIGHT + BAR_GAP * 2 + insets.bottom, backgroundColor: colors.background }} />;
}

/**
 * The same room, for a screen to put above a bar of its own at the bottom, with
 * `setMediaBarLift` given that bar's height: the small player then sits above the bar.
 */
export function MediaBarRoom() {
  const session = useMediaSession();
  if (!session || session.expanded) return null;
  return <View style={{ height: BAR_HEIGHT + BAR_GAP * 2 }} />;
}

/**
 * The app's player of its own library, songs and videos, over every screen. A video is the
 * whole screen, or small in a bar at the bottom with the picture still playing in it: one
 * player in both, only resized, so going between them does not stop or restart anything. A
 * song is always the bar, with its cover where the picture would be; its large player is a
 * screen of its own (`app/sound-player.tsx`), which a tap on the bar opens. Closing the
 * large video makes it small; the bar's own button stops what plays.
 *
 * The queue is the player's (`MpvEngine`), so it plays on with the app out of sight and
 * with this component not drawn. This component is only told which item of the list plays.
 */
export function MediaPlayer() {
  const { colors } = useTheme();
  const router = useRouter();
  const insets = useSafeAreaInsets();
  const session = useMediaSession();
  const other = usePlayerScreen();
  const lift = useMediaBarLift();
  const repeat = useMediaRepeat();
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

  // What the player says of itself, also while no view of it is on screen (under the large
  // player for a song, the view is not drawn): which item, paused or not, the repeat, and
  // that it stopped. And one found playing with no list here to match it, after a reload of
  // the screens alone, is stopped: there is nothing to show it with.
  useEffect(() => {
    const subscription = onPlayerChanged((state) => {
      if (!state.active) {
        mediaStopped();
        return;
      }
      if (state.key !== mediaSession()?.key) return;
      if (typeof state.index === 'number') mediaAt(state.index);
      if (state.repeat) mediaRepeatIs(state.repeat);
      const now = mediaPlace();
      if (typeof state.paused === 'boolean' && (state.paused !== now.paused || !!state.buffering !== now.buffering)) {
        mediaPlaceIs({ ...now, paused: state.paused, buffering: !!state.buffering });
      }
    });
    void getPlayerState()
      .then((state) => {
        if (state.active && !mediaSession()) void stopPlayer();
      })
      .catch(() => undefined);
    return () => subscription.remove();
  }, []);

  const key = session?.key;
  const items = session?.items;
  const start = session?.start ?? 0;
  const queue = useMemo(
    () =>
      key && items
        ? {
            key,
            items: items.map(({ kind, id, uri, title, subtitle, artwork }) => ({ kind, id, uri, title, subtitle, artwork })),
            index: start,
          }
        : null,
    [key, items, start],
  );

  // A song has no large player here: reached in a queue while a video was the whole screen,
  // the player becomes the bar.
  const audio = session ? (session.items[session.index] ?? session.items[0]).kind === 'audio' : false;
  useEffect(() => {
    if (audio) expandMedia(false);
  }, [audio, session?.index]);

  if (!session || !queue || other) return null;
  const { index } = session;
  const item = session.items[index] ?? session.items[0];
  const expanded = session.expanded && !audio;
  const open = () => (audio ? router.push('/sound-player' as Href) : expandMedia(true));

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
        <View style={expanded ? styles.fill : [styles.box, (audio || item.tall) && styles.boxTall]} collapsable={false}>
          <Player
            source={null}
            queue={languages ? queue : null}
            item={index}
            onItem={mediaAt}
            title={item.title}
            languages={languages ?? []}
            background
            mini={!expanded}
            repeat={repeat}
            onRepeat={setMediaRepeat}
            onProgress={mediaPlaceIs}
            onClose={() => expandMedia(false)}
          />
          {!expanded ? (
            <Pressable style={StyleSheet.absoluteFill} onPress={open} accessibilityRole="button" accessibilityLabel={item.title}>
              {/* A song's cover, over where a video's picture is. */}
              {audio ? (
                <View style={[StyleSheet.absoluteFill, styles.cover, { backgroundColor: colors.surface }]}>
                  {item.artwork ? (
                    <Image source={{ uri: `file://${item.artwork}` }} style={StyleSheet.absoluteFill} contentFit="cover" />
                  ) : (
                    <Ionicons name="musical-note" size={20} color={colors.textMuted} />
                  )}
                </View>
              ) : null}
            </Pressable>
          ) : null}
        </View>
        {!expanded ? (
          <Bar title={item.title} subtitle={item.subtitle ?? null} next={index < session.items.length - 1 ? index + 1 : null} onOpen={open} />
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
function Bar({ title, subtitle, next, onOpen }: { title: string; subtitle: string | null; next: number | null; onOpen: () => void }) {
  const { t } = useTranslation();
  const { colors } = useTheme();
  const { paused } = useMediaPlace();
  return (
    <>
      <Pressable style={styles.words} onPress={onOpen} accessibilityRole="button" accessibilityLabel={title}>
        <Text style={[styles.title, { color: colors.text }]} numberOfLines={1}>{title}</Text>
        {subtitle ? <Text style={[styles.subtitle, { color: colors.textMuted }]} numberOfLines={1}>{subtitle}</Text> : null}
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
        <Pressable onPress={() => void skipPlayerTo(next)} hitSlop={8} accessibilityRole="button" accessibilityLabel={t('player.next')}>
          <Ionicons name="play-skip-forward" size={21} color={colors.text} />
        </Pressable>
      ) : null}
      <Pressable onPress={closeMedia} hitSlop={8} accessibilityRole="button" accessibilityLabel={t('player.stop')}>
        <Ionicons name="close" size={23} color={colors.textMuted} />
      </Pressable>
    </>
  );
}

/**
 * How far what plays is, drawn as the bar's own top edge, round its corners. It is laid over
 * the bar, of the bar's own shape, and cut to that shape: so the line bends with the
 * corners and nothing of it stands above them. Dragged, it seeks there on letting go; it
 * takes touches in a strip along the top, the line itself being too thin to hold.
 */
function Scrub({ bottom }: { bottom: number }) {
  const { colors } = useTheme();
  const { positionMs, durationMs } = useMediaPlace();
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
  cover: { alignItems: 'center', justifyContent: 'center' },
  words: { flex: 1, alignSelf: 'stretch', justifyContent: 'center', gap: 1 },
  title: { fontSize: 14, fontWeight: '600' },
  subtitle: { fontSize: 12 },
  over: { position: 'absolute', left: BAR_SIDE, right: BAR_SIDE, height: BAR_HEIGHT, borderRadius: 12, overflow: 'hidden' },
  line: { position: 'absolute', left: 0, top: 0, height: 3 },
  lineHeld: { height: 5 },
  scrub: { position: 'absolute', left: 0, right: 0, top: 0, height: 10 },
});
