import { Ionicons, MaterialCommunityIcons } from '@expo/vector-icons';
import React, { useCallback, useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { ActivityIndicator, BackHandler, PanResponder, Pressable, ScrollView, StyleSheet, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import type { WatchSubtitle } from '@/src/api';
import { AppText as Text } from '@/src/components';
import {
  MpvPlayerView,
  type MpvPlayerHandle,
  type MpvProgress,
  type MpvSource,
  type MpvTrack,
} from '@/src/native/localDownloader';

/** Controls fade after this long without a touch, while playing. */
const HIDE_AFTER_MS = 3500;
const SPEEDS = [0.5, 0.75, 1, 1.25, 1.5, 2];
const DELAY_STEPS = [-500, -100, 100, 500];
/** Two taps this close together on a side of the picture seek, as one would toggle twice. */
const DOUBLE_TAP_MS = 280;
/** How long a back swipe waits for a second one to close the player. */
const BACK_AGAIN_MS = 2000;
const SEEK_MS = 10_000;

type Panel = 'audio' | 'subtitles' | 'speed' | null;

export interface PlayerProps {
  source: MpvSource | null;
  title: string;
  /** Subtitle and audio languages, most preferred first, as ISO 639-2 codes. */
  languages: string[];
  /**
   * Subtitles from add-ons, best first. The best is loaded by itself when the file has
   * nothing in a language preferred as much; the rest wait in the subtitles menu.
   */
  subtitles?: WatchSubtitle[];
  onClose: () => void;
  onProgress?: (progress: MpvProgress) => void;
  onEnded?: () => void;
  /** Shown over the video: the next episode, say. */
  children?: React.ReactNode;
  /** Shown under the spinner while it waits: a torrent's peers and speed. */
  waiting?: React.ReactNode;
}

function clock(ms: number): string {
  const total = Math.max(0, Math.floor(ms / 1000));
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  const s = total % 60;
  const mm = h > 0 ? String(m).padStart(2, '0') : String(m);
  return `${h > 0 ? `${h}:` : ''}${mm}:${String(s).padStart(2, '0')}`;
}

/**
 * The player, for add-on streams and vault videos alike (`shared/watch/CONTRACT.md`, "The
 * player"): mpv underneath, and Arsivinyo's controls over it — play, seek, audio track,
 * subtitle track, subtitle delay and speed.
 */
export function Player({ source, title, languages, subtitles = [], onClose, onProgress, onEnded, children, waiting }: PlayerProps) {
  const { t } = useTranslation();
  const player = useRef<MpvPlayerHandle>(null);
  const [progress, setProgress] = useState<MpvProgress>({ positionMs: 0, durationMs: 0, paused: false, buffering: true });
  const [tracks, setTracks] = useState<MpvTrack[]>([]);
  const [failed, setFailed] = useState<string | null>(null);
  const [visible, setVisible] = useState(true);
  const [panel, setPanel] = useState<Panel>(null);
  const [speed, setSpeed] = useState(1);
  const [delayMs, setDelayMs] = useState(0);
  const [scrubMs, setScrubMs] = useState<number | null>(null);
  const [barWidth, setBarWidth] = useState(1);
  /** Add-on subtitles already handed to mpv, by URL, so the menu lists each once. */
  const [loaded, setLoaded] = useState<Set<string>>(() => new Set());
  const chosen = useRef(false);
  const hideTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const [width, setWidth] = useState(1);
  /** A side tapped twice: which way it seeked, shown a moment. */
  const [jumped, setJumped] = useState<-1 | 1 | null>(null);
  const lastTap = useRef<{ at: number; side: -1 | 0 | 1 }>({ at: 0, side: 0 });
  const tapTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const jumpTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const [backArmed, setBackArmed] = useState(false);
  const backTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  const languageName = useCallback(
    (lang?: string | null) => (lang ? t(`player.languages.${lang}`, { defaultValue: lang }) : ''),
    [t],
  );

  // Fades the controls after a while, unless paused or a menu is open.
  const touch = useCallback(() => {
    setVisible(true);
    if (hideTimer.current) clearTimeout(hideTimer.current);
    hideTimer.current = setTimeout(() => setVisible(false), HIDE_AFTER_MS);
  }, []);
  useEffect(() => {
    touch();
    return () => {
      if (hideTimer.current) clearTimeout(hideTimer.current);
    };
  }, [touch]);
  const shown = visible || progress.paused || panel !== null || !!failed;

  const seekBy = useCallback((ms: number) => {
    void player.current?.seekBy(ms);
    setJumped(ms < 0 ? -1 : 1);
    if (jumpTimer.current) clearTimeout(jumpTimer.current);
    jumpTimer.current = setTimeout(() => setJumped(null), 700);
  }, []);

  // A tap shows or hides the controls; two quick taps on the left or right third seek, as
  // other players do. A lone tap waits a moment to know it is not the first of two.
  const onPicture = (x: number) => {
    const side: -1 | 0 | 1 = x < width / 3 ? -1 : x > (width * 2) / 3 ? 1 : 0;
    const now = Date.now();
    const twice = side !== 0 && side === lastTap.current.side && now - lastTap.current.at < DOUBLE_TAP_MS;
    if (tapTimer.current) clearTimeout(tapTimer.current);
    if (twice) {
      lastTap.current = { at: 0, side: 0 };
      seekBy(side * SEEK_MS);
      return;
    }
    lastTap.current = { at: now, side };
    tapTimer.current = setTimeout(() => {
      if (panel) setPanel(null);
      else if (shown && !progress.paused) setVisible(false);
      else touch();
    }, side === 0 ? 0 : DOUBLE_TAP_MS);
  };

  // Android's back swipe: it closes a menu first; while playing, the first one only says that
  // a second closes the player, so a swipe meant for something else does not end the video.
  // The close button closes at once.
  useEffect(() => {
    const sub = BackHandler.addEventListener('hardwareBackPress', () => {
      if (panel) {
        setPanel(null);
        return true;
      }
      if (failed || progress.paused || backArmed) {
        onClose();
        return true;
      }
      setBackArmed(true);
      touch();
      if (backTimer.current) clearTimeout(backTimer.current);
      backTimer.current = setTimeout(() => setBackArmed(false), BACK_AGAIN_MS);
      return true;
    });
    return () => sub.remove();
  }, [backArmed, failed, onClose, panel, progress.paused, touch]);

  useEffect(
    () => () => {
      for (const timer of [tapTimer, jumpTimer, backTimer]) if (timer.current) clearTimeout(timer.current);
    },
    [],
  );

  const addonLabel = useCallback(
    (s: WatchSubtitle, index: number) => `${languageName(s.lang)} · ${t('player.fromAddon')} ${index + 1}`,
    [languageName, t],
  );

  const loadSubtitle = useCallback(
    (s: WatchSubtitle, index: number, select: boolean) => {
      setLoaded((prev) => new Set(prev).add(s.url));
      void player.current?.addSubtitle(s.url, addonLabel(s, index), s.lang, select);
    },
    [addonLabel],
  );

  const onTracks = useCallback(
    (list: MpvTrack[]) => {
      setTracks(list);
      // Once per video, when the file's tracks are known: an add-on's subtitle when the
      // file has none in a language preferred as much.
      if (chosen.current || list.length === 0 || subtitles.length === 0) return;
      chosen.current = true;
      const rank = (lang?: string | null) => {
        const at = lang ? languages.indexOf(lang) : -1;
        return at < 0 ? Number.MAX_SAFE_INTEGER : at;
      };
      const own = list.filter((x) => x.kind === 'subtitle').reduce((best, x) => Math.min(best, rank(x.lang)), Number.MAX_SAFE_INTEGER);
      const best = subtitles[0];
      if (rank(best.lang) < own) loadSubtitle(best, 0, true);
    },
    [languages, loadSubtitle, subtitles],
  );

  // A new video starts over: its own tracks, its own choice.
  useEffect(() => {
    chosen.current = false;
    setLoaded(new Set());
    setFailed(null);
    setDelayMs(0);
  }, [source?.url]);

  const seekTo = useCallback((ms: number) => void player.current?.seek(ms), []);

  // The seek bar reads the latest numbers through a ref: its handlers are made once.
  const latest = useRef({ durationMs: 0, barWidth: 1, scrubMs: null as number | null });
  useEffect(() => {
    latest.current.durationMs = progress.durationMs;
    latest.current.barWidth = barWidth;
  }, [progress.durationMs, barWidth]);

  const [scrub] = useState(() => {
    const at = (x: number) => {
      touch();
      const { durationMs, barWidth: width } = latest.current;
      if (durationMs <= 0) return;
      const ms = Math.min(1, Math.max(0, x / width)) * durationMs;
      latest.current.scrubMs = ms;
      setScrubMs(ms);
    };
    const end = () => {
      if (latest.current.scrubMs !== null) seekTo(latest.current.scrubMs);
      latest.current.scrubMs = null;
      setScrubMs(null);
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

  const position = scrubMs ?? progress.positionMs;
  const fraction = progress.durationMs > 0 ? Math.min(1, position / progress.durationMs) : 0;
  const audio = tracks.filter((x) => x.kind === 'audio');
  const subs = tracks.filter((x) => x.kind === 'subtitle');
  const offered = subtitles.map((s, i) => ({ s, i })).filter(({ s }) => !loaded.has(s.url));

  const trackLabel = (x: MpvTrack, n: number) =>
    [x.title || t('player.track', { n }), languageName(x.lang), x.codec?.toUpperCase()].filter(Boolean).join(' · ');

  return (
    <View style={styles.fill}>
      <MpvPlayerView
        ref={player}
        style={StyleSheet.absoluteFill}
        source={source}
        languages={languages}
        onProgress={(e) => {
          if (scrubMs === null) setProgress(e.nativeEvent);
          onProgress?.(e.nativeEvent);
        }}
        onTracks={(e) => onTracks(e.nativeEvent.tracks)}
        onEnded={() => onEnded?.()}
        onFailed={(e) => setFailed(e.nativeEvent.code)}
      />

      {/* Touching the picture shows or hides the controls; twice on a side, it seeks. */}
      <Pressable
        style={StyleSheet.absoluteFill}
        onLayout={(e) => setWidth(Math.max(1, e.nativeEvent.layout.width))}
        onPress={(e) => onPicture(e.nativeEvent.locationX)}
      />

      {shown ? (
        <SafeAreaView style={StyleSheet.absoluteFill} pointerEvents="box-none" edges={['left', 'right', 'top', 'bottom']}>
          <View style={styles.top}>
            <Pressable onPress={onClose} hitSlop={12} accessibilityRole="button" accessibilityLabel={t('player.close')}>
              <Ionicons name="close" size={28} color="#fff" />
            </Pressable>
            <Text style={styles.title} numberOfLines={1}>{title}</Text>
            <Pressable onPress={() => { touch(); setPanel(panel === 'audio' ? null : 'audio'); }} hitSlop={10}
              accessibilityRole="button" accessibilityLabel={t('player.audio')}>
              <Ionicons name="musical-notes-outline" size={24} color="#fff" />
            </Pressable>
            <Pressable onPress={() => { touch(); setPanel(panel === 'subtitles' ? null : 'subtitles'); }} hitSlop={10}
              accessibilityRole="button" accessibilityLabel={t('player.subtitles')}>
              <Ionicons name="text-outline" size={24} color="#fff" />
            </Pressable>
            <Pressable onPress={() => { touch(); setPanel(panel === 'speed' ? null : 'speed'); }} hitSlop={10}
              accessibilityRole="button" accessibilityLabel={t('player.speed')}>
              <Ionicons name="speedometer-outline" size={24} color="#fff" />
            </Pressable>
          </View>

          <View style={styles.flex} pointerEvents="none" />

          <View style={styles.bottom}>
            <Text style={styles.time}>{clock(position)}</Text>
            <View
              style={styles.bar}
              onLayout={(e) => setBarWidth(Math.max(1, e.nativeEvent.layout.width))}
              {...scrub.panHandlers}
            >
              <View style={styles.track} pointerEvents="none">
                <View style={[styles.done, { width: `${fraction * 100}%` }]} />
              </View>
              <View style={[styles.knob, { left: fraction * barWidth - 8 }]} pointerEvents="none" />
            </View>
            <Text style={styles.time}>{clock(progress.durationMs)}</Text>
          </View>
        </SafeAreaView>
      ) : null}

      {/* The middle of the screen, whatever the bars above and below: seek back, play or pause
          (or the spinner in its place while it waits), seek forward. */}
      <View style={[StyleSheet.absoluteFill, styles.middle]} pointerEvents="box-none">
        <View style={styles.side}>
          {shown && !failed ? (
            <Pressable onPress={() => { touch(); seekBy(-SEEK_MS); }} hitSlop={16}
              accessibilityRole="button" accessibilityLabel={t('player.back10')}>
              <MaterialCommunityIcons name="rewind-10" size={40} color="#fff" />
            </Pressable>
          ) : jumped === -1 ? <MaterialCommunityIcons name="rewind-10" size={40} color="#fff" /> : null}
        </View>
        <View style={styles.slot} pointerEvents="box-none">
          {progress.buffering && !failed ? (
            <>
              <ActivityIndicator size="large" color="#fff" />
              <View style={styles.waiting} pointerEvents="none">{waiting}</View>
            </>
          ) : shown && !failed ? (
            <Pressable
              onPress={() => { touch(); void player.current?.setPaused(!progress.paused); }}
              hitSlop={16}
              accessibilityRole="button"
              accessibilityLabel={progress.paused ? t('player.play') : t('player.pause')}
            >
              <Ionicons name={progress.paused ? 'play' : 'pause'} size={56} color="#fff" />
            </Pressable>
          ) : null}
        </View>
        <View style={styles.side}>
          {shown && !failed ? (
            <Pressable onPress={() => { touch(); seekBy(SEEK_MS); }} hitSlop={16}
              accessibilityRole="button" accessibilityLabel={t('player.forward10')}>
              <MaterialCommunityIcons name="fast-forward-10" size={40} color="#fff" />
            </Pressable>
          ) : jumped === 1 ? <MaterialCommunityIcons name="fast-forward-10" size={40} color="#fff" /> : null}
        </View>
      </View>

      {backArmed ? (
        <View style={styles.toast} pointerEvents="none">
          <Text style={styles.white}>{t('player.backAgain')}</Text>
        </View>
      ) : null}

      {panel ? (
        <SafeAreaView style={styles.panel} edges={['top', 'bottom', 'right']}>
          <View style={styles.panelHead}>
            <Text style={styles.panelHeading}>
              {panel === 'audio' ? t('player.audio') : panel === 'subtitles' ? t('player.subtitles') : t('player.speed')}
            </Text>
            <Pressable onPress={() => setPanel(null)} hitSlop={12} accessibilityRole="button" accessibilityLabel={t('player.close')}>
              <Ionicons name="close" size={24} color="#fff" />
            </Pressable>
          </View>
          <ScrollView contentContainerStyle={styles.panelBody}>
            {panel === 'audio' ? (
              <>
                {audio.length === 0 ? <Text style={styles.dim}>{t('player.noTracks')}</Text> : null}
                {audio.map((x, n) => (
                  <Option key={x.id} label={trackLabel(x, n + 1)} selected={x.selected}
                    onPress={() => void player.current?.setTrack('audio', x.id)} />
                ))}
              </>
            ) : null}

            {panel === 'subtitles' ? (
              <>
                {/* The delay first: a subtitle made for another release runs late or early. */}
                <Text style={styles.panelTitle}>{t('player.delay')}</Text>
                <Text style={[styles.dim, styles.small]}>{t('player.delayHint')}</Text>
                <View style={styles.row}>
                  {DELAY_STEPS.map((step) => (
                    <Pressable key={step} style={styles.step} onPress={() => {
                      const next = delayMs + step;
                      setDelayMs(next);
                      void player.current?.setSubtitleDelay(next);
                    }}>
                      <Text style={styles.white}>{step > 0 ? '+' : ''}{(step / 1000).toFixed(1)}</Text>
                    </Pressable>
                  ))}
                </View>
                <View style={styles.row}>
                  <Text style={styles.white}>{t('player.delayValue', { value: (delayMs / 1000).toFixed(1) })}</Text>
                  <Pressable onPress={() => { setDelayMs(0); void player.current?.setSubtitleDelay(0); }}>
                    <Text style={styles.link}>{t('player.reset')}</Text>
                  </Pressable>
                </View>
                <Text style={[styles.panelTitle, styles.gap]}>{t('player.tracks')}</Text>
                <Option label={t('player.off')} selected={!subs.some((x) => x.selected)}
                  onPress={() => void player.current?.setTrack('subtitle', 'no')} />
                {subs.map((x, n) => (
                  <Option key={x.id} label={trackLabel(x, n + 1)} detail={x.external ? undefined : t('player.fromFile')}
                    selected={x.selected} onPress={() => void player.current?.setTrack('subtitle', x.id)} />
                ))}
                {offered.map(({ s, i }) => (
                  <Option key={s.url} label={addonLabel(s, i)} selected={false} onPress={() => loadSubtitle(s, i, true)} />
                ))}
              </>
            ) : null}

            {panel === 'speed' ? (
              <>
                {SPEEDS.map((x) => (
                  <Option key={x} label={`${x}×`} selected={speed === x} onPress={() => {
                    setSpeed(x);
                    void player.current?.setSpeed(x);
                  }} />
                ))}
              </>
            ) : null}
          </ScrollView>
        </SafeAreaView>
      ) : null}

      {failed ? (
        <View style={[StyleSheet.absoluteFill, styles.center, styles.veil]}>
          <Text style={styles.white}>{t('player.failed')}</Text>
          <Text style={[styles.white, styles.small]}>{t('player.failedCode', { code: failed })}</Text>
          <Pressable onPress={onClose} style={styles.button}>
            <Text style={styles.buttonText}>{t('player.close')}</Text>
          </Pressable>
        </View>
      ) : null}

      {children}
    </View>
  );
}

function Option({ label, detail, selected, onPress }: { label: string; detail?: string; selected: boolean; onPress: () => void }) {
  return (
    <Pressable onPress={onPress} style={styles.option} accessibilityRole="button" accessibilityState={{ selected }}>
      <Ionicons name={selected ? 'checkmark' : 'ellipse-outline'} size={18} color={selected ? '#fff' : 'transparent'} />
      <View style={styles.flex}>
        <Text style={styles.white} numberOfLines={2}>{label}</Text>
        {detail ? <Text style={[styles.dim, styles.small]}>{detail}</Text> : null}
      </View>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  fill: { flex: 1, backgroundColor: '#000' },
  flex: { flex: 1 },
  center: { alignItems: 'center', justifyContent: 'center', gap: 8, padding: 24 },
  veil: { backgroundColor: 'rgba(0,0,0,0.75)' },
  white: { color: '#fff' },
  dim: { color: 'rgba(255,255,255,0.6)' },
  small: { fontSize: 12 },
  link: { color: '#fff', textDecorationLine: 'underline' },
  top: {
    flexDirection: 'row', alignItems: 'center', gap: 18, paddingHorizontal: 16, paddingVertical: 10,
    backgroundColor: 'rgba(0,0,0,0.45)',
  },
  title: { color: '#fff', flex: 1, fontSize: 15 },
  middle: { flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: 40 },
  side: { width: 56, height: 56, alignItems: 'center', justifyContent: 'center' },
  slot: { width: 72, height: 72, alignItems: 'center', justifyContent: 'center' },
  // Below the spinner without moving it off the middle.
  waiting: { position: 'absolute', top: 76, width: 260, alignItems: 'center' },
  toast: {
    position: 'absolute', bottom: 72, alignSelf: 'center', backgroundColor: 'rgba(0,0,0,0.75)',
    borderRadius: 18, paddingHorizontal: 16, paddingVertical: 8,
  },
  panelHead: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', paddingHorizontal: 16, paddingTop: 14 },
  panelHeading: { color: '#fff', fontSize: 17, fontWeight: '700' },
  bottom: {
    flexDirection: 'row', alignItems: 'center', gap: 12, paddingHorizontal: 16, paddingVertical: 10,
    backgroundColor: 'rgba(0,0,0,0.45)',
  },
  time: { color: '#fff', fontSize: 13, minWidth: 48, textAlign: 'center', fontVariant: ['tabular-nums'] },
  bar: { flex: 1, height: 32, justifyContent: 'center' },
  track: { height: 4, borderRadius: 2, backgroundColor: 'rgba(255,255,255,0.3)', overflow: 'hidden' },
  done: { height: 4, backgroundColor: '#fff' },
  knob: { position: 'absolute', width: 16, height: 16, borderRadius: 8, backgroundColor: '#fff' },
  panel: { position: 'absolute', top: 0, right: 0, bottom: 0, width: 320, backgroundColor: 'rgba(16,16,16,0.94)' },
  panelBody: { padding: 16, gap: 4 },
  panelTitle: { color: '#fff', fontSize: 16, fontWeight: '600', marginBottom: 6 },
  gap: { marginTop: 18 },
  option: { flexDirection: 'row', alignItems: 'center', gap: 10, paddingVertical: 10 },
  row: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', gap: 8, marginTop: 6 },
  step: { flex: 1, alignItems: 'center', paddingVertical: 8, borderRadius: 8, backgroundColor: 'rgba(255,255,255,0.12)' },
  button: { marginTop: 8, backgroundColor: '#fff', borderRadius: 22, paddingHorizontal: 20, paddingVertical: 10 },
  buttonText: { color: '#000', fontWeight: '600' },
});
