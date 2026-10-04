import { Ionicons } from '@expo/vector-icons';
import { Image } from 'expo-image';
import { useFocusEffect, useRouter, type Href } from 'expo-router';
import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  ActivityIndicator,
  BackHandler,
  FlatList,
  KeyboardAvoidingView,
  Modal,
  Platform,
  Pressable,
  ScrollView,
  StyleSheet,
  TextInput,
  View,
} from 'react-native';
import { SafeAreaView, useSafeAreaInsets } from 'react-native-safe-area-context';
import TrackPlayer, { State, useActiveTrack, usePlaybackState } from 'react-native-track-player';

import {
  addLocalSoundsToPlaylists,
  createLocalSoundPlaylist,
  deleteLocalSoundPlaylist,
  deleteLocalSounds,
  FAVORITES_PLAYLIST_ID,
  importLocalSounds,
  isLocalSoundsSupported,
  listLocalSoundPlaylists,
  listLocalSounds,
  listenLocalSoundsChanged,
  removeLocalSoundsFromPlaylist,
  renameLocalSoundPlaylist,
  setLocalSoundsFavorite,
  applyLocalSoundPresets,
  cancelLocalSoundPresetRender,
  isRenderedSound,
  listenLocalSoundPresetProgress,
  type LocalSound,
  type LocalSoundPlaylist,
  type LocalSoundPresetProgressEvent,
} from '@/src/api';
import { AppText as Text, ConfirmModal, TrackMetadata, ValueSlider } from '@/src/components';
import { removePresetFromAutoApply } from '@/src/features/audioPresets/autoApply';
import {
  buildParamsSpec,
  createCustomPreset,
  DEFAULT_PARAMS,
  deleteCustomPreset,
  listAllPresets,
  PARAM_RANGES,
  renameCustomPreset,
  resetBuiltInPreset,
  sanitizeParams,
  savePresetParams,
  type AudioPreset,
  type AudioPresetParams,
} from '@/src/features/audioPresets/presets';
import { playSongs, reconcileQueueAfterReload, setupTrackPlayer } from '@/src/services/trackPlayerService';
import { useTheme } from '@/src/theme';

type SortMode = 'newest' | 'oldest' | 'titleAsc' | 'titleDesc' | 'durationDesc' | 'durationAsc';
type Tab = 'songs' | 'playlists';

type PendingAction =
  | { type: 'deleteSingle'; song: LocalSound }
  | { type: 'deleteBatch'; ids: string[] }
  | { type: 'removeFromPlaylist'; playlist: LocalSoundPlaylist; ids: string[] }
  | { type: 'deletePlaylist'; playlist: LocalSoundPlaylist }
  // Preset edits are destructive in their own way: restoring discards the user's
  // adjustments, deleting removes a preset outright. Both go through the same
  // confirmation path as track deletion rather than acting immediately.
  | { type: 'restorePreset'; preset: AudioPreset }
  | { type: 'deletePreset'; preset: AudioPreset }
  | null;

function formatDuration(seconds: number): string {
  if (!seconds || seconds < 0) return '0:00';
  const total = Math.floor(seconds);
  const m = Math.floor(total / 60);
  const s = total % 60;
  return `${m}:${s.toString().padStart(2, '0')}`;
}

/**
 * Parameters exposed in the customize view, in the order they are shown.
 *
 * The limiter is deliberately absent: it exists to stop a boosted render clipping on
 * the way into the encoder, so it is a safety net rather than a creative control.
 */
/** Horizontal inset for sheet content. `sheet` has no horizontal padding of its own. */
const SHEET_INSET = 12;

const PRESET_FIELDS: {
  key: Exclude<keyof AudioPresetParams, 'limiterEnabled' | 'limiterCeilingDb'>;
  format: (value: number) => string;
}[] = [
  { key: 'rate', format: (v) => `${v.toFixed(2)}x` },
  { key: 'reverbMix', format: (v) => `${Math.round(v * 100)}%` },
  { key: 'reverbRoom', format: (v) => `${Math.round(v * 100)}%` },
  { key: 'reverbDamp', format: (v) => `${Math.round(v * 100)}%` },
  { key: 'reverbWidth', format: (v) => `${Math.round(v * 100)}%` },
  { key: 'reverbPreDelayMs', format: (v) => `${Math.round(v)} ms` },
  { key: 'bassGainDb', format: (v) => `${v > 0 ? '+' : ''}${v} dB` },
  { key: 'bassFreqHz', format: (v) => `${Math.round(v)} Hz` },
  { key: 'trebleGainDb', format: (v) => `${v > 0 ? '+' : ''}${v} dB` },
  { key: 'trebleFreqHz', format: (v) => `${Math.round(v)} Hz` },
  { key: 'outputGainDb', format: (v) => `${v > 0 ? '+' : ''}${v} dB` },
];

function normalize(text: string): string {
  return text.trim().toLocaleLowerCase();
}

export default function SoundsScreen() {
  const { t } = useTranslation();
  const { colors } = useTheme();
  const router = useRouter();
  const insets = useSafeAreaInsets();
  // Lift the floating bottom bars clear of the Android nav bar / gesture pill.
  const bottomBarOffset = insets.bottom + 12;

  const supported = useMemo(() => isLocalSoundsSupported(), []);

  // The currently-playing track (its id matches a song id) gets an accent outline.
  const activeTrack = useActiveTrack();
  const activeTrackId = activeTrack?.id != null ? String(activeTrack.id) : null;

  const [loading, setLoading] = useState(true);
  const [songs, setSongs] = useState<LocalSound[]>([]);
  const [playlists, setPlaylists] = useState<LocalSoundPlaylist[]>([]);

  const [tab, setTab] = useState<Tab>('songs');
  const [openPlaylistId, setOpenPlaylistId] = useState<string | null>(null);

  const [query, setQuery] = useState('');
  const [sortMode, setSortMode] = useState<SortMode>('newest');
  const [sortMenuOpen, setSortMenuOpen] = useState(false);

  const [selectionMode, setSelectionMode] = useState(false);
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set());

  const [pendingAction, setPendingAction] = useState<PendingAction>(null);
  const [actionBusy, setActionBusy] = useState(false);
  const [importBusy, setImportBusy] = useState(false);

  const [createPlaylistOpen, setCreatePlaylistOpen] = useState(false);
  const [newPlaylistName, setNewPlaylistName] = useState('');

  const [renameTarget, setRenameTarget] = useState<LocalSoundPlaylist | null>(null);
  const [renameValue, setRenameValue] = useState('');

  const [playlistPickerTarget, setPlaylistPickerTarget] = useState<{ ids: string[]; title?: string } | null>(null);

  // Preset rendering. `presetTarget` holds the tracks the sheet will act on; a null
  // `customizing` means the sheet is showing the preset list rather than the sliders.
  const [presetTarget, setPresetTarget] = useState<{ ids: string[] } | null>(null);
  const [presets, setPresets] = useState<AudioPreset[]>([]);
  /**
   * The preset currently open in the editor. `isNew` distinguishes creating from
   * editing, which is the only difference between the two flows.
   */
  const [presetEditor, setPresetEditor] = useState<{
    preset: AudioPreset;
    params: AudioPresetParams;
    name: string;
    isNew: boolean;
  } | null>(null);
  const [renderProgress, setRenderProgress] = useState<LocalSoundPresetProgressEvent | null>(null);
  /** Single track whose metadata sheet is open. Deliberately never a batch. */
  const [infoTarget, setInfoTarget] = useState<LocalSound | null>(null);
  const [toast, setToast] = useState<string | null>(null);

  const showToast = useCallback((message: string) => {
    setToast(message);
    setTimeout(() => setToast((cur) => (cur === message ? null : cur)), 2200);
  }, []);

  const reload = useCallback(async () => {
    if (!supported) {
      setLoading(false);
      return;
    }
    try {
      const [library, pls] = await Promise.all([listLocalSounds(), listLocalSoundPlaylists()]);
      setSongs(library.songs);
      const allPlaylists = library.playlists.length ? library.playlists : pls;
      setPlaylists(allPlaylists);
      // Keep the active queue current: append songs added since the last reconcile
      // (import/download) so the player's prev/next can reach them without a restart.
      await reconcileQueueAfterReload(library.songs, allPlaylists);
    } catch {
      showToast(t('sounds.loadFailed'));
    } finally {
      setLoading(false);
    }
  }, [supported, showToast, t]);

  useEffect(() => {
    setupTrackPlayer().catch(() => undefined);
  }, []);

  // Presets live in AsyncStorage (plus the frozen built-ins), so they are loaded once
  // rather than on every sheet open.
  const reloadPresets = useCallback(async () => {
    try {
      setPresets(await listAllPresets());
    } catch {
      setPresets([]);
    }
  }, []);

  useEffect(() => {
    void reloadPresets();
  }, [reloadPresets]);

  // Render progress. A finished batch reloads the library so the new tracks appear.
  useEffect(() => {
    const subscription = listenLocalSoundPresetProgress((event) => {
      setRenderProgress(event);
      if (event.status === 'TRACK_FAILED' && event.message) {
        showToast(t('sounds.presetTrackFailed', { message: event.message }));
      }
      if (event.status === 'FINISHED' || event.status === 'CANCELLED') {
        void reload();
        // Leave the banner up briefly so the outcome is readable, then clear it.
        setTimeout(() => setRenderProgress((cur) => (cur === event ? null : cur)), 2500);
      }
    });
    return () => subscription.remove();
  }, [reload, showToast, t]);

  /** Display name of a preset, localising the built-ins via their i18n key. */
  const presetLabel = useCallback(
    (preset: AudioPreset) => (preset.nameKey ? t(preset.nameKey) : preset.name),
    [t]
  );

  /** Resolve a recorded presetId to its display name, localising built-ins. */
  const presetDisplayName = useCallback(
    (presetId?: string | null) => {
      if (!presetId) return null;
      const preset = presets.find((p) => p.id === presetId);
      if (!preset) return presetId;
      return preset.nameKey ? t(preset.nameKey) : preset.name;
    },
    [presets, t]
  );

  const closePresetSheet = useCallback(() => {
    setPresetTarget(null);
    setPresetEditor(null);
  }, []);

  const startPresetRender = useCallback(
    async (preset: AudioPreset, params: AudioPresetParams) => {
      const ids = presetTarget?.ids ?? [];
      if (ids.length === 0) return;
      closePresetSheet();
      setSelectionMode(false);
      setSelectedIds(new Set());
      try {
        await applyLocalSoundPresets({
          songIds: ids,
          presetId: preset.id,
          paramsSpec: buildParamsSpec(params),
          titleSuffix: preset.titleSuffix,
        });
      } catch (error) {
        showToast(error instanceof Error ? error.message : t('errors.UNKNOWN_ERROR'));
      }
    },
    [closePresetSheet, presetTarget, showToast, t]
  );

  /** Open the editor on an existing preset. */
  const openPresetEditor = useCallback((preset: AudioPreset) => {
    setPresetEditor({
      preset,
      params: sanitizeParams(preset.params),
      name: preset.name,
      isNew: false,
    });
  }, []);

  /** Open the editor on a brand-new user preset. */
  const openNewPresetEditor = useCallback(() => {
    setPresetEditor({
      preset: {
        id: '',
        name: '',
        builtIn: false,
        titleSuffix: '',
        params: DEFAULT_PARAMS,
      },
      params: { ...DEFAULT_PARAMS },
      name: '',
      isNew: true,
    });
  }, []);

  /**
   * Persist the editor's contents.
   *
   * One path for three cases: creating a user preset, updating one, and overriding a
   * built-in. A built-in's name is fixed, so only its parameters are written.
   */
  const handleSavePreset = useCallback(async () => {
    if (!presetEditor) return;
    const { preset, params, name, isNew } = presetEditor;
    const trimmed = name.trim();
    if (!preset.builtIn && !trimmed) {
      showToast(t('sounds.presetNameRequired'));
      return;
    }
    try {
      if (isNew) {
        await createCustomPreset(trimmed, params);
      } else {
        await savePresetParams(preset, params);
        if (!preset.builtIn && trimmed !== preset.name) {
          await renameCustomPreset(preset.id, trimmed);
        }
      }
      await reloadPresets();
      setPresetEditor(null);
      showToast(t('sounds.presetSaved', { name: preset.builtIn ? presetLabel(preset) : trimmed }));
    } catch (error) {
      showToast(error instanceof Error ? error.message : t('errors.UNKNOWN_ERROR'));
    }
  }, [presetEditor, presetLabel, reloadPresets, showToast, t]);

  const handleCancelRender = useCallback(async () => {
    const renderId = renderProgress?.renderId;
    if (!renderId) return;
    await cancelLocalSoundPresetRender(renderId).catch(() => undefined);
  }, [renderProgress]);

  // Refresh on every focus so changes made elsewhere (favoriting from the player,
  // downloads finishing) are reflected when the user returns to the library.
  useFocusEffect(
    useCallback(() => {
      reload();
    }, [reload])
  );

  // And while it is open, when another device adds a track or fills a playlist: a playlist
  // arriving is many of those in a row, so they are gathered into one reload.
  useEffect(() => {
    let timer: ReturnType<typeof setTimeout> | null = null;
    const subscription = listenLocalSoundsChanged(() => {
      if (timer) clearTimeout(timer);
      timer = setTimeout(() => {
        timer = null;
        void reload();
      }, 400);
    });
    return () => {
      subscription.remove();
      if (timer) clearTimeout(timer);
    };
  }, [reload]);

  const activePlaylist = useMemo(
    () => (openPlaylistId ? playlists.find((p) => p.id === openPlaylistId) ?? null : null),
    [openPlaylistId, playlists]
  );

  // Favorites (the special system playlist) pinned to the top of the playlists list.
  const sortedPlaylists = useMemo(
    () => [...playlists].sort((a, b) => (b.system ? 1 : 0) - (a.system ? 1 : 0)),
    [playlists]
  );
  const favoriteIdSet = useMemo(() => {
    const sys = playlists.find((p) => p.system || p.id === FAVORITES_PLAYLIST_ID);
    return new Set(sys?.songIds ?? []);
  }, [playlists]);
  const playlistDisplayName = useCallback(
    (p: LocalSoundPlaylist) => (p.system ? t('sounds.favorites') : p.name),
    [t]
  );

  // We render the song list when on the Songs tab, or when a playlist is open.
  const showingSongs = tab === 'songs' || activePlaylist != null;

  const visibleSongs = useMemo(() => {
    const byId = new Map(songs.map((s) => [s.id, s]));
    let work: LocalSound[];
    if (activePlaylist) {
      work = activePlaylist.songIds.map((id) => byId.get(id)).filter((s): s is LocalSound => Boolean(s));
    } else {
      work = [...songs];
    }
    const q = normalize(query);
    if (q) {
      work = work.filter((s) => normalize(s.title).includes(q) || normalize(s.artist ?? '').includes(q));
    }
    const sorted = [...work];
    switch (sortMode) {
      case 'oldest':
        sorted.sort((a, b) => a.createdAt - b.createdAt);
        break;
      case 'titleAsc':
        sorted.sort((a, b) => a.title.localeCompare(b.title));
        break;
      case 'titleDesc':
        sorted.sort((a, b) => b.title.localeCompare(a.title));
        break;
      case 'durationDesc':
        sorted.sort((a, b) => b.durationSec - a.durationSec);
        break;
      case 'durationAsc':
        sorted.sort((a, b) => a.durationSec - b.durationSec);
        break;
      case 'newest':
      default:
        sorted.sort((a, b) => b.createdAt - a.createdAt);
        break;
    }
    return sorted;
  }, [songs, activePlaylist, query, sortMode]);

  // ---- selection ----
  const exitSelection = useCallback(() => {
    setSelectionMode(false);
    setSelectedIds(new Set());
  }, []);

  const toggleSelected = useCallback((id: string) => {
    setSelectedIds((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      if (next.size === 0) setSelectionMode(false);
      return next;
    });
  }, []);

  const enterSelectionFor = useCallback((id: string) => {
    setSelectionMode(true);
    setSelectedIds(new Set([id]));
  }, []);

  const selectAll = useCallback(() => {
    setSelectedIds(new Set(visibleSongs.map((s) => s.id)));
  }, [visibleSongs]);

  // ---- navigation ----
  const switchTab = useCallback(
    (next: Tab) => {
      exitSelection();
      setTab(next);
    },
    [exitSelection]
  );

  const openPlaylist = useCallback(
    (id: string) => {
      exitSelection();
      setOpenPlaylistId(id);
    },
    [exitSelection]
  );

  const closePlaylist = useCallback(() => {
    exitSelection();
    setOpenPlaylistId(null);
  }, [exitSelection]);

  const onHeaderBack = useCallback(() => {
    if (activePlaylist) closePlaylist();
    else router.back();
  }, [activePlaylist, closePlaylist, router]);

  // Android hardware / gesture back: inside a playlist, return to the library (not the
  // app home); in selection mode, exit selection first. Otherwise let it pop the route.
  useFocusEffect(
    useCallback(() => {
      const onBack = () => {
        if (openPlaylistId != null) {
          closePlaylist();
          return true;
        }
        if (selectionMode) {
          exitSelection();
          return true;
        }
        return false;
      };
      const sub = BackHandler.addEventListener('hardwareBackPress', onBack);
      return () => sub.remove();
    }, [openPlaylistId, selectionMode, closePlaylist, exitSelection])
  );

  // ---- playback ----
  const playFrom = useCallback(
    async (index: number) => {
      try {
        // Just start playback. The full player opens via the mini-player or the
        // notification, not by tapping a song row. The context (playlist id or 'all')
        // lets later imports/downloads be appended to this queue.
        await playSongs(visibleSongs, index, activePlaylist ? activePlaylist.id : 'all');
      } catch {
        showToast(t('sounds.loadFailed'));
      }
    },
    [visibleSongs, activePlaylist, showToast, t]
  );

  // ---- import ----
  const onImport = useCallback(async () => {
    if (importBusy) return;
    setImportBusy(true);
    if (__DEV__) console.log(`[sounds-import] onImport start (playlist=${activePlaylist?.id ?? 'none'})`);
    try {
      const result = await importLocalSounds();
      if (result.success) {
        const importedSongs = result.songs ?? [];
        const imported = result.importedCount ?? importedSongs.length;
        const failed = result.failedCount ?? 0;
        if (__DEV__ && failed > 0) console.warn('[sounds-import] per-file failures:', result.failures);
        // When importing from inside a playlist, also add the new songs to it.
        if (activePlaylist && importedSongs.length > 0) {
          if (__DEV__) console.log(`[sounds-import] adding ${importedSongs.length} song(s) to playlist ${activePlaylist.id}`);
          await addLocalSoundsToPlaylists(
            importedSongs.map((s) => s.id),
            [activePlaylist.id]
          );
        }
        await reload();
        if (failed > 0) showToast(t('sounds.importPartial', { imported, failed }));
        else if (imported > 0) showToast(t('sounds.importSuccess', { count: imported }));
        else if (__DEV__) console.log('[sounds-import] success but nothing imported (0 files)');
      } else if (result.code && !result.code.includes('CANCEL')) {
        if (__DEV__) console.warn(`[sounds-import] failed: code=${result.code} message=${result.message}`);
        showToast(t('sounds.importFailed'));
      } else if (__DEV__) {
        console.log(`[sounds-import] cancelled / no-op: code=${result.code}`);
      }
    } catch (e) {
      if (__DEV__) console.warn('[sounds-import] onImport threw:', e);
      showToast(t('sounds.importFailed'));
    } finally {
      setImportBusy(false);
    }
  }, [importBusy, activePlaylist, reload, showToast, t]);

  // ---- playlist CRUD ----
  const onCreatePlaylist = useCallback(async () => {
    const name = newPlaylistName.trim();
    if (!name) return;
    try {
      const created = await createLocalSoundPlaylist(name);
      setPlaylists((prev) => [...prev, created]);
      setNewPlaylistName('');
      setCreatePlaylistOpen(false);
    } catch {
      showToast(t('sounds.loadFailed'));
    }
  }, [newPlaylistName, showToast, t]);

  const onRenamePlaylist = useCallback(async () => {
    if (!renameTarget) return;
    const name = renameValue.trim();
    if (!name) return;
    try {
      await renameLocalSoundPlaylist(renameTarget.id, name);
      setRenameTarget(null);
      setRenameValue('');
      await reload();
    } catch {
      showToast(t('sounds.loadFailed'));
    }
  }, [renameTarget, renameValue, reload, showToast, t]);

  // ---- add to playlist (single/batch) ----
  const commitAddToPlaylists = useCallback(
    async (songIds: string[], playlistIds: string[]) => {
      if (songIds.length === 0 || playlistIds.length === 0) return;
      try {
        await addLocalSoundsToPlaylists(songIds, playlistIds);
        await reload();
        showToast(t('sounds.addToPlaylistSuccess'));
        exitSelection();
      } catch {
        showToast(t('sounds.loadFailed'));
      }
    },
    [reload, showToast, t, exitSelection]
  );

  // ---- favorites (smart toggle over the current selection) ----
  // If every selected song is already favorited we unfavorite; otherwise we favorite.
  // This means inside the Favorites playlist (all favorited) the action only ever
  // unfavorites, which is the desired behavior.
  const selectionAllFavorited = useMemo(() => {
    if (selectedIds.size === 0) return false;
    for (const id of selectedIds) if (!favoriteIdSet.has(id)) return false;
    return true;
  }, [selectedIds, favoriteIdSet]);

  const onToggleFavoriteSelected = useCallback(async () => {
    const ids = Array.from(selectedIds);
    if (ids.length === 0) return;
    const makeFavorite = !selectionAllFavorited;
    try {
      await setLocalSoundsFavorite(ids, makeFavorite);
      await reload();
      showToast(
        makeFavorite
          ? t('sounds.favoriteAdded', { count: ids.length })
          : t('sounds.favoriteRemoved', { count: ids.length })
      );
      exitSelection();
    } catch {
      showToast(t('sounds.loadFailed'));
    }
  }, [selectedIds, selectionAllFavorited, reload, showToast, t, exitSelection]);

  // ---- confirm destructive / important actions ----
  const runPendingAction = useCallback(async () => {
    if (!pendingAction) return;
    setActionBusy(true);
    try {
      if (pendingAction.type === 'deleteSingle') {
        await deleteLocalSounds([pendingAction.song.id]);
        showToast(t('sounds.deleteSuccess', { count: 1 }));
      } else if (pendingAction.type === 'deleteBatch') {
        const result = await deleteLocalSounds(pendingAction.ids);
        showToast(t('sounds.deleteSuccess', { count: result.deletedCount ?? pendingAction.ids.length }));
        exitSelection();
      } else if (pendingAction.type === 'removeFromPlaylist') {
        await removeLocalSoundsFromPlaylist(pendingAction.playlist.id, pendingAction.ids);
        exitSelection();
      } else if (pendingAction.type === 'restorePreset') {
        await resetBuiltInPreset(pendingAction.preset.id);
        await reloadPresets();
        setPresetEditor(null);
        showToast(t('sounds.presetRestored', { name: presetLabel(pendingAction.preset) }));
      } else if (pendingAction.type === 'deletePreset') {
        await deleteCustomPreset(pendingAction.preset.id);
        // A deleted preset must also leave the auto-apply set, or downloads would keep
        // rendering it from the flattened spec stored natively.
        await removePresetFromAutoApply(pendingAction.preset.id);
        await reloadPresets();
        setPresetEditor(null);
        showToast(t('sounds.presetDeleted', { name: pendingAction.preset.name }));
      } else if (pendingAction.type === 'deletePlaylist') {
        await deleteLocalSoundPlaylist(pendingAction.playlist.id);
        if (openPlaylistId === pendingAction.playlist.id) setOpenPlaylistId(null);
      }
      await reload();
    } catch {
      showToast(t('sounds.loadFailed'));
    } finally {
      setActionBusy(false);
      setPendingAction(null);
    }
  }, [pendingAction, reload, reloadPresets, presetLabel, showToast, t, exitSelection, openPlaylistId]);

  // ---- song row ----
  const renderSong = useCallback(
    ({ item, index }: { item: LocalSound; index: number }) => {
      const selected = selectedIds.has(item.id);
      const isActive = activeTrackId != null && item.id === activeTrackId;
      return (
        <View
          style={[
            styles.row,
            {
              backgroundColor: selected ? colors.accent + '22' : colors.surface,
              borderColor: selected || isActive ? colors.accent : colors.border,
              borderWidth: isActive ? 2 : 1,
            },
          ]}
        >
          <Pressable
            onPress={() => (selectionMode ? toggleSelected(item.id) : playFrom(index))}
            onLongPress={() => {
              if (!selectionMode) enterSelectionFor(item.id);
            }}
            style={styles.rowMain}
          >
            {selectionMode ? (
              <Ionicons
                name={selected ? 'checkmark-circle' : 'ellipse-outline'}
                size={24}
                color={selected ? colors.accent : colors.textMuted}
                style={styles.rowLead}
              />
            ) : null}
            <View style={[styles.thumb, { backgroundColor: colors.surfaceHover }]}>
              {item.thumbnailPath ? (
                <Image source={{ uri: `file://${item.thumbnailPath}` }} style={styles.thumbImage} contentFit="cover" />
              ) : (
                <Ionicons name="musical-note" size={22} color={colors.textMuted} />
              )}
            </View>
            <View style={styles.rowText}>
              <Text numberOfLines={1} style={[styles.rowTitle, { color: colors.text }]}>
                {item.title}
              </Text>
              <View style={styles.rowMetaLine}>
                <Text numberOfLines={1} style={[styles.rowMeta, { color: colors.textMuted }]}>
                  {(item.artist || t('sounds.unknownArtist')) + ' · ' + formatDuration(item.durationSec)}
                  {item.format ? (
                    <Text
                      style={[
                        styles.rowFormat,
                        // Lossless is called out in the accent colour so the quality tier
                        // is visible at a glance rather than inferred from the extension.
                        { color: item.lossless ? colors.accent : colors.textMuted },
                      ]}
                    >
                      {' · ' + item.format.toUpperCase()}
                    </Text>
                  ) : null}
                </Text>
                {/* Marks a track this app produced by rendering a preset. The title
                    suffix alone is only a naming convention a rename would erase; this
                    reads the recorded presetId, so it survives any retitling. */}
                {isRenderedSound(item) ? (
                  <View style={[styles.presetBadge, { borderColor: colors.accent }]}>
                    <Ionicons name="color-wand" size={9} color={colors.accent} />
                    <Text style={[styles.presetBadgeText, { color: colors.accent }]}>
                      {t('sounds.presetBadge')}
                    </Text>
                  </View>
                ) : null}
              </View>
            </View>
          </Pressable>
          {!selectionMode ? (
            <Pressable
              hitSlop={8}
              onPress={() => setPlaylistPickerTarget({ ids: [item.id], title: item.title })}
              style={styles.rowAction}
            >
              <Ionicons name="add-circle-outline" size={22} color={colors.textMuted} />
            </Pressable>
          ) : null}
        </View>
      );
    },
    [selectedIds, selectionMode, activeTrackId, colors, t, toggleSelected, playFrom, enterSelectionFor]
  );

  const keyExtractor = useCallback((item: LocalSound) => item.id, []);

  // ---- playlist row ----
  const renderPlaylist = useCallback(
    ({ item }: { item: LocalSoundPlaylist }) => (
      <View style={[styles.row, { backgroundColor: colors.surface, borderColor: colors.border }]}>
        <Pressable onPress={() => openPlaylist(item.id)} style={styles.rowMain}>
          <View style={[styles.thumb, { backgroundColor: colors.surfaceHover }]}>
            <Ionicons name={item.system ? 'heart' : 'musical-notes'} size={22} color={colors.accent} />
          </View>
          <View style={styles.rowText}>
            <Text numberOfLines={1} style={[styles.rowTitle, { color: colors.text }]}>
              {playlistDisplayName(item)}
            </Text>
            <Text numberOfLines={1} style={[styles.rowMeta, { color: colors.textMuted }]}>
              {t('sounds.playlistItemCount', { count: item.songIds.length })}
            </Text>
          </View>
        </Pressable>
        {/* The special Favorites playlist can't be renamed or deleted. */}
        {item.system ? null : (
          <>
            <Pressable
              hitSlop={6}
              onPress={() => {
                setRenameTarget(item);
                setRenameValue(item.name);
              }}
              style={[styles.playlistActionBtn, { borderColor: colors.border }]}
            >
              <Ionicons name="pencil" size={17} color={colors.text} />
            </Pressable>
            <Pressable
              hitSlop={6}
              onPress={() => setPendingAction({ type: 'deletePlaylist', playlist: item })}
              style={[styles.playlistActionBtn, { borderColor: colors.border }]}
            >
              <Ionicons name="trash-outline" size={17} color={colors.error} />
            </Pressable>
          </>
        )}
      </View>
    ),
    [colors, t, openPlaylist, playlistDisplayName]
  );

  const keyExtractorPlaylist = useCallback((item: LocalSoundPlaylist) => item.id, []);

  if (!supported) {
    return (
      <SafeAreaView style={[styles.container, { backgroundColor: colors.background }]} edges={['top']}>
        <Header title={t('sounds.title')} colors={colors} onBack={() => router.back()} />
        <View style={styles.centered}>
          <Ionicons name="musical-notes-outline" size={48} color={colors.textMuted} />
          <Text style={[styles.emptyText, { color: colors.textMuted }]}>{t('sounds.unsupported')}</Text>
        </View>
      </SafeAreaView>
    );
  }

  return (
    <SafeAreaView style={[styles.container, { backgroundColor: colors.background }]} edges={['top']}>
      <Header
        title={activePlaylist ? playlistDisplayName(activePlaylist) : t('sounds.title')}
        colors={colors}
        onBack={onHeaderBack}
        right={
          <View style={styles.headerActions}>
            {showingSongs ? (
              <Pressable hitSlop={8} onPress={() => setSortMenuOpen(true)} style={styles.headerBtn}>
                <Ionicons name="swap-vertical" size={22} color={colors.text} />
              </Pressable>
            ) : null}
            <Pressable
              hitSlop={8}
              onPress={onImport}
              disabled={importBusy}
              style={styles.headerBtn}
              accessibilityLabel={t('sounds.import')}
            >
              {importBusy ? (
                <ActivityIndicator size="small" color={colors.text} />
              ) : (
                <Ionicons name={activePlaylist ? 'add-circle-outline' : 'add'} size={activePlaylist ? 22 : 24} color={colors.text} />
              )}
            </Pressable>
            {activePlaylist && !activePlaylist.system ? (
              <>
                <Pressable
                  hitSlop={8}
                  onPress={() => {
                    setRenameTarget(activePlaylist);
                    setRenameValue(activePlaylist.name);
                  }}
                  style={styles.headerBtn}
                  accessibilityLabel={t('sounds.playlistRename')}
                >
                  <Ionicons name="pencil" size={20} color={colors.text} />
                </Pressable>
                <Pressable
                  hitSlop={8}
                  onPress={() => setPendingAction({ type: 'deletePlaylist', playlist: activePlaylist })}
                  style={styles.headerBtn}
                  accessibilityLabel={t('sounds.playlistDelete')}
                >
                  <Ionicons name="trash-outline" size={20} color={colors.error} />
                </Pressable>
              </>
            ) : null}
          </View>
        }
      />

      {/* Segmented control (top level only — hidden inside a playlist) */}
      {!activePlaylist ? (
        <View style={[styles.segment, { backgroundColor: colors.surface, borderColor: colors.border }]}>
          {(['songs', 'playlists'] as Tab[]).map((seg) => {
            const active = tab === seg;
            return (
              <Pressable
                key={seg}
                onPress={() => switchTab(seg)}
                style={[styles.segmentBtn, active ? { backgroundColor: colors.accent } : null]}
              >
                <Text style={[styles.segmentText, { color: active ? colors.background : colors.textMuted }]}>
                  {t(seg === 'songs' ? 'sounds.tabSongs' : 'sounds.tabPlaylists')}
                </Text>
              </Pressable>
            );
          })}
        </View>
      ) : null}

      {/* Search (only in song views) */}
      {showingSongs ? (
        <View style={[styles.searchBar, { backgroundColor: colors.surface, borderColor: colors.border }]}>
          <Ionicons name="search" size={18} color={colors.textMuted} />
          <TextInput
            value={query}
            onChangeText={setQuery}
            placeholder={t('sounds.searchPlaceholder')}
            placeholderTextColor={colors.textMuted}
            style={[styles.searchInput, { color: colors.text }]}
          />
          {query.length > 0 ? (
            <Pressable hitSlop={8} onPress={() => setQuery('')}>
              <Ionicons name="close-circle" size={18} color={colors.textMuted} />
            </Pressable>
          ) : null}
        </View>
      ) : null}

      {/* Body */}
      {loading ? (
        <View style={styles.centered}>
          <ActivityIndicator size="large" color={colors.accent} />
        </View>
      ) : showingSongs ? (
        visibleSongs.length === 0 ? (
          <View style={styles.centered}>
            <Ionicons name="musical-notes-outline" size={48} color={colors.textMuted} />
            <Text style={[styles.emptyText, { color: colors.text }]}>
              {activePlaylist ? t('sounds.playlistEmpty') : t('sounds.empty')}
            </Text>
            {!activePlaylist ? (
              <Text style={[styles.emptyHint, { color: colors.textMuted }]}>{t('sounds.emptyHint')}</Text>
            ) : null}
          </View>
        ) : (
          <FlatList
            data={visibleSongs}
            renderItem={renderSong}
            keyExtractor={keyExtractor}
            contentContainerStyle={[styles.listContent, { paddingBottom: 160 + insets.bottom }]}
          />
        )
      ) : (
        // Playlists tab
        <FlatList
          data={sortedPlaylists}
          renderItem={renderPlaylist}
          keyExtractor={keyExtractorPlaylist}
          contentContainerStyle={[styles.listContent, { paddingBottom: 160 + insets.bottom }]}
          ListEmptyComponent={
            <View style={styles.centered}>
              <Ionicons name="list-outline" size={48} color={colors.textMuted} />
              <Text style={[styles.emptyText, { color: colors.text }]}>{t('sounds.noPlaylistsYet')}</Text>
            </View>
          }
          ListFooterComponent={
            <Pressable
              onPress={() => setCreatePlaylistOpen(true)}
              style={[styles.createRow, { borderColor: colors.borderSubtle }]}
            >
              <Ionicons name="add" size={20} color={colors.accent} />
              <Text style={[styles.createRowText, { color: colors.accent }]}>{t('sounds.playlistCreate')}</Text>
            </Pressable>
          }
        />
      )}

      {/* Mini player (hidden while the selection bar occupies the bottom) */}
      {!selectionMode ? (
        <MiniPlayer colors={colors} bottomOffset={bottomBarOffset} onOpen={() => router.push('/sound-player' as Href)} />
      ) : null}

      {/* Selection action bar */}
      {selectionMode ? (
        <View style={[styles.selectionBar, { backgroundColor: colors.surface, borderColor: colors.border, bottom: bottomBarOffset }]}>
          <Pressable hitSlop={8} onPress={exitSelection} style={styles.selectionBtn}>
            <Ionicons name="close" size={22} color={colors.text} />
          </Pressable>
          <Text style={[styles.selectionCount, { color: colors.text }]}>
            {t('sounds.selectedCount', { count: selectedIds.size })}
          </Text>
          <View style={styles.selectionActions}>
            <Pressable hitSlop={8} onPress={selectAll} style={styles.selectionBtn}>
              <Ionicons name="checkmark-done" size={22} color={colors.text} />
            </Pressable>
            <Pressable
              hitSlop={8}
              onPress={onToggleFavoriteSelected}
              style={styles.selectionBtn}
              accessibilityLabel={t(selectionAllFavorited ? 'sounds.unfavorite' : 'sounds.favorite')}
            >
              <Ionicons
                name={selectionAllFavorited ? 'heart' : 'heart-outline'}
                size={22}
                color={selectionAllFavorited ? colors.accent : colors.text}
              />
            </Pressable>
            <Pressable
              hitSlop={8}
              onPress={() => setPlaylistPickerTarget({ ids: Array.from(selectedIds) })}
              style={styles.selectionBtn}
            >
              <Ionicons name="add-circle-outline" size={22} color={colors.text} />
            </Pressable>
            {/* Remove-from-playlist only for regular playlists; in Favorites the heart
                toggle above already does the unfavorite. */}
            {activePlaylist && !activePlaylist.system ? (
              <Pressable
                hitSlop={8}
                onPress={() =>
                  setPendingAction({ type: 'removeFromPlaylist', playlist: activePlaylist, ids: Array.from(selectedIds) })
                }
                style={styles.selectionBtn}
              >
                <Ionicons name="remove-circle-outline" size={22} color={colors.text} />
              </Pressable>
            ) : null}
            {/* Info is single-track only: the sheet describes one file, and there is
                no sensible way to show one set of metadata for a batch. */}
            {selectedIds.size === 1 ? (
              <Pressable
                hitSlop={8}
                onPress={() => {
                  const id = Array.from(selectedIds)[0];
                  const song = songs.find((s) => s.id === id) ?? null;
                  if (song) setInfoTarget(song);
                }}
                style={styles.selectionBtn}
                accessibilityLabel={t('sounds.metadata.title')}
              >
                <Ionicons name="information-circle-outline" size={22} color={colors.text} />
              </Pressable>
            ) : null}
            <Pressable
              hitSlop={8}
              onPress={() => setPresetTarget({ ids: Array.from(selectedIds) })}
              style={styles.selectionBtn}
              accessibilityLabel={t('sounds.applyPreset')}
            >
              <Ionicons name="color-wand-outline" size={22} color={colors.text} />
            </Pressable>
            <Pressable
              hitSlop={8}
              onPress={() => setPendingAction({ type: 'deleteBatch', ids: Array.from(selectedIds) })}
              style={styles.selectionBtn}
            >
              <Ionicons name="trash-outline" size={22} color={colors.error} />
            </Pressable>
          </View>
        </View>
      ) : null}

      {toast ? (
        <View style={[styles.toast, { backgroundColor: colors.surfaceHover, borderColor: colors.border, bottom: insets.bottom + 84 }]}>
          <Text style={[styles.toastText, { color: colors.text }]}>{toast}</Text>
        </View>
      ) : null}

      {/* Sort menu */}
      <SimpleSheet visible={infoTarget !== null} onClose={() => setInfoTarget(null)} colors={colors}>
        {infoTarget ? (
          <View style={styles.infoSheetBody}>
            <Text numberOfLines={2} style={[styles.infoTitle, { color: colors.text }]}>
              {infoTarget.title}
            </Text>
            <Text numberOfLines={1} style={[styles.infoArtist, { color: colors.textMuted }]}>
              {infoTarget.artist || t('sounds.unknownArtist')}
            </Text>
            <TrackMetadata
              song={infoTarget}
              presetName={presetDisplayName(infoTarget.presetId)}
              sourceTitle={songs.find((s) => s.id === infoTarget.sourceSongId)?.title ?? null}
            />
          </View>
        ) : null}
      </SimpleSheet>

      {/* Preset picker. Tapping a preset applies it; the options icon opens the editor,
          so the common two-tap path is not buried behind eleven sliders. */}
      <SimpleSheet visible={presetTarget !== null} onClose={closePresetSheet} colors={colors}>
        {presetEditor ? (
          <>
            <View style={styles.presetHeader}>
              <Pressable hitSlop={8} onPress={() => setPresetEditor(null)}>
                <Ionicons name="chevron-back" size={22} color={colors.text} />
              </Pressable>
              <Text numberOfLines={1} style={[styles.sheetTitle, { color: colors.text, marginBottom: 0 }]}>
                {presetEditor.isNew
                  ? t('sounds.presetCreate')
                  : presetLabel(presetEditor.preset)}
              </Text>
            </View>

            {/* Name sits OUTSIDE the scroll area so it stays reachable with the
                keyboard up, and so the scrollable region is purely the slider list. */}
            {!presetEditor.preset.builtIn ? (
              <View style={styles.presetFixedRow}>
                <TextInput
                  value={presetEditor.name}
                  onChangeText={(name) => setPresetEditor((cur) => (cur ? { ...cur, name } : cur))}
                  placeholder={t('sounds.presetNamePlaceholder')}
                  placeholderTextColor={colors.textMuted}
                  style={[styles.presetInput, { color: colors.text, borderColor: colors.border }]}
                />
              </View>
            ) : null}

            <ScrollView
              style={styles.presetScroll}
              contentContainerStyle={styles.presetScrollContent}
              keyboardShouldPersistTaps="handled"
            >
              {PRESET_FIELDS.map((field) => (
                <ValueSlider
                  key={field.key}
                  label={t(`sounds.presetParams.${field.key}`)}
                  value={presetEditor.params[field.key]}
                  min={PARAM_RANGES[field.key].min}
                  max={PARAM_RANGES[field.key].max}
                  step={PARAM_RANGES[field.key].step}
                  formatValue={field.format}
                  onChange={(next) =>
                    setPresetEditor((cur) =>
                      cur ? { ...cur, params: { ...cur.params, [field.key]: next } } : cur
                    )
                  }
                />
              ))}
            </ScrollView>

            {/* Fixed footer. These are the actions the user came for, so they must not
                require scrolling to the end of eleven sliders to reach. */}
            <View style={[styles.presetFooter, { borderColor: colors.border }]}>
              <View style={styles.presetEditorActions}>
                <Pressable
                  onPress={handleSavePreset}
                  style={[styles.presetSecondaryBtn, { borderColor: colors.border }]}
                >
                  <Ionicons name="save-outline" size={18} color={colors.text} />
                  <Text style={{ color: colors.text }}>{t('common.save')}</Text>
                </Pressable>

                {/* Restoring discards the user's adjustments, so it confirms first. Only
                    offered when a built-in actually differs from its shipped values. */}
                {presetEditor.preset.builtIn && presetEditor.preset.modified ? (
                  <Pressable
                    onPress={() => setPendingAction({ type: 'restorePreset', preset: presetEditor.preset })}
                    style={[styles.presetSecondaryBtn, { borderColor: colors.border }]}
                  >
                    <Ionicons name="refresh-outline" size={18} color={colors.text} />
                    <Text style={{ color: colors.text }}>{t('sounds.presetRestore')}</Text>
                  </Pressable>
                ) : null}

                {!presetEditor.preset.builtIn && !presetEditor.isNew ? (
                  <Pressable
                    onPress={() => setPendingAction({ type: 'deletePreset', preset: presetEditor.preset })}
                    style={[styles.presetSecondaryBtn, { borderColor: colors.error }]}
                  >
                    <Ionicons name="trash-outline" size={18} color={colors.error} />
                    <Text style={{ color: colors.error }}>{t('common.delete')}</Text>
                  </Pressable>
                ) : null}
              </View>

              {/* Applies the edited values whether or not they were saved, so the
                  sliders can be auditioned on a track without committing them. */}
              {!presetEditor.isNew ? (
                <Pressable
                  onPress={() => startPresetRender(presetEditor.preset, presetEditor.params)}
                  style={[styles.presetApplyBtn, { backgroundColor: colors.accent }]}
                >
                  <Text style={styles.presetApplyText}>
                    {t('sounds.presetApplyCount', { count: presetTarget?.ids.length ?? 0 })}
                  </Text>
                </Pressable>
              ) : null}
            </View>
          </>
        ) : (
          <>
            <Text style={[styles.sheetTitle, { color: colors.text }]}>{t('sounds.applyPreset')}</Text>
            <ScrollView style={styles.presetScroll} contentContainerStyle={styles.presetScrollContent}>
              {presets.map((preset) => (
                <View key={preset.id} style={[styles.presetRow, { borderColor: colors.border }]}>
                  <Pressable
                    style={styles.presetRowMain}
                    onPress={() => startPresetRender(preset, preset.params)}
                  >
                    <Ionicons name="color-wand-outline" size={20} color={colors.accent} />
                    <View style={styles.presetNameWrap}>
                      <Text numberOfLines={1} style={[styles.presetName, { color: colors.text }]}>
                        {presetLabel(preset)}
                      </Text>
                      {preset.modified ? (
                        <Text style={[styles.presetSubLabel, { color: colors.textMuted }]}>
                          {t('sounds.presetModified')}
                        </Text>
                      ) : null}
                    </View>
                  </Pressable>
                  <Pressable
                    hitSlop={8}
                    onPress={() => openPresetEditor(preset)}
                    accessibilityLabel={t('sounds.presetCustomize')}
                  >
                    <Ionicons name="options-outline" size={20} color={colors.textMuted} />
                  </Pressable>
                </View>
              ))}

              <Pressable
                onPress={openNewPresetEditor}
                style={[styles.presetRow, { borderColor: colors.border }]}
              >
                <View style={styles.presetRowMain}>
                  <Ionicons name="add-circle-outline" size={20} color={colors.textMuted} />
                  <Text style={[styles.presetName, { color: colors.textMuted }]}>
                    {t('sounds.presetCreate')}
                  </Text>
                </View>
              </Pressable>
            </ScrollView>
          </>
        )}
      </SimpleSheet>

      {/* Render progress. Sits above the list so a long batch stays visible. */}
      {renderProgress ? (
        <View style={[styles.renderBanner, { backgroundColor: colors.surface, borderColor: colors.border, bottom: bottomBarOffset }]}>
          <View style={styles.renderBannerText}>
            <Text numberOfLines={1} style={[styles.renderBannerTitle, { color: colors.text }]}>
              {renderProgress.status === 'FINISHED'
                ? t('sounds.presetFinished')
                : renderProgress.status === 'CANCELLED'
                  ? t('sounds.presetCancelled')
                  : t('sounds.presetRendering', {
                      current: Math.min(renderProgress.index + 1, renderProgress.total),
                      total: renderProgress.total,
                    })}
            </Text>
            {renderProgress.status === 'PROGRESS' && renderProgress.percent != null ? (
              <View style={[styles.renderBarTrack, { backgroundColor: colors.borderSubtle }]}>
                <View
                  style={[
                    styles.renderBarFill,
                    { backgroundColor: colors.accent, width: `${Math.round(renderProgress.percent)}%` },
                  ]}
                />
              </View>
            ) : null}
          </View>
          {renderProgress.status !== 'FINISHED' && renderProgress.status !== 'CANCELLED' ? (
            <Pressable hitSlop={8} onPress={handleCancelRender}>
              <Ionicons name="close-circle-outline" size={22} color={colors.textMuted} />
            </Pressable>
          ) : null}
        </View>
      ) : null}

      <SimpleSheet visible={sortMenuOpen} onClose={() => setSortMenuOpen(false)} colors={colors}>
        {(
          [
            ['newest', 'sortNewest'],
            ['oldest', 'sortOldest'],
            ['titleAsc', 'sortTitleAsc'],
            ['titleDesc', 'sortTitleDesc'],
            ['durationDesc', 'sortDurationDesc'],
            ['durationAsc', 'sortDurationAsc'],
          ] as [SortMode, string][]
        ).map(([mode, key]) => (
          <Pressable
            key={mode}
            onPress={() => {
              setSortMode(mode);
              setSortMenuOpen(false);
            }}
            style={[styles.sheetRow, { borderColor: colors.border }]}
          >
            <Text style={[styles.sheetRowText, { color: colors.text }]}>{t(`sounds.${key}`)}</Text>
            {sortMode === mode ? <Ionicons name="checkmark" size={20} color={colors.accent} /> : null}
          </Pressable>
        ))}
      </SimpleSheet>

      {/* Create playlist modal */}
      <InputModal
        visible={createPlaylistOpen}
        colors={colors}
        title={t('sounds.playlistCreateTitle')}
        placeholder={t('sounds.playlistNamePlaceholder')}
        value={newPlaylistName}
        onChangeText={setNewPlaylistName}
        confirmLabel={t('sounds.playlistCreateConfirm')}
        cancelLabel={t('common.cancel')}
        onConfirm={onCreatePlaylist}
        onCancel={() => {
          setCreatePlaylistOpen(false);
          setNewPlaylistName('');
        }}
      />

      {/* Rename playlist modal */}
      <InputModal
        visible={renameTarget != null}
        colors={colors}
        title={t('sounds.playlistRename')}
        placeholder={t('sounds.playlistNamePlaceholder')}
        value={renameValue}
        onChangeText={setRenameValue}
        confirmLabel={t('sounds.playlistRenameConfirm')}
        cancelLabel={t('common.cancel')}
        onConfirm={onRenamePlaylist}
        onCancel={() => {
          setRenameTarget(null);
          setRenameValue('');
        }}
      />

      {/* Add-to-playlist picker */}
      <PlaylistPickerModal
        visible={playlistPickerTarget != null}
        colors={colors}
        playlists={playlists.filter((p) => !p.system)}
        title={t('sounds.addToPlaylistTitle')}
        message={
          playlistPickerTarget?.title
            ? t('sounds.addToPlaylistMessage', { title: playlistPickerTarget.title })
            : t('sounds.batchAddToPlaylistMessage', { count: playlistPickerTarget?.ids.length ?? 0 })
        }
        emptyText={t('sounds.noPlaylistsYet')}
        confirmLabel={t('sounds.addToPlaylistConfirm')}
        cancelLabel={t('common.cancel')}
        onCancel={() => setPlaylistPickerTarget(null)}
        onConfirm={(playlistIds) => {
          const ids = playlistPickerTarget?.ids ?? [];
          setPlaylistPickerTarget(null);
          commitAddToPlaylists(ids, playlistIds);
        }}
      />

      {/* Confirmation modal */}
      <ConfirmModal
        visible={pendingAction != null}
        busy={actionBusy}
        config={confirmConfig(pendingAction, t)}
        onCancel={() => setPendingAction(null)}
        onConfirm={runPendingAction}
      />
    </SafeAreaView>
  );
}

// ---------------------------------------------------------------------------
// Sub-components
// ---------------------------------------------------------------------------

function Header({
  title,
  colors,
  onBack,
  right,
}: {
  title: string;
  colors: any;
  onBack: () => void;
  right?: React.ReactNode;
}) {
  return (
    <View style={[styles.header, { borderColor: colors.border }]}>
      <Pressable hitSlop={8} onPress={onBack} style={styles.headerBtn}>
        <Ionicons name="chevron-back" size={26} color={colors.text} />
      </Pressable>
      <Text numberOfLines={1} style={[styles.headerTitle, { color: colors.text }]}>
        {title}
      </Text>
      <View style={styles.headerRight}>{right}</View>
    </View>
  );
}

function MiniPlayer({ colors, bottomOffset, onOpen }: { colors: any; bottomOffset: number; onOpen: () => void }) {
  const track = useActiveTrack();
  const playback = usePlaybackState();
  const playing = playback.state === State.Playing;
  if (!track) return null;
  return (
    <Pressable onPress={onOpen} style={[styles.miniPlayer, { backgroundColor: colors.surfaceHover, borderColor: colors.border, bottom: bottomOffset }]}>
      <View style={[styles.miniThumb, { backgroundColor: colors.surface }]}>
        {track.artwork ? (
          <Image source={{ uri: String(track.artwork) }} style={styles.miniThumbImg} contentFit="cover" />
        ) : (
          <Ionicons name="musical-note" size={18} color={colors.textMuted} />
        )}
      </View>
      <View style={styles.miniText}>
        <Text numberOfLines={1} style={[styles.miniTitle, { color: colors.text }]}>
          {track.title ?? ''}
        </Text>
        <Text numberOfLines={1} style={[styles.miniArtist, { color: colors.textMuted }]}>
          {track.artist ?? ''}
        </Text>
      </View>
      <Pressable hitSlop={10} onPress={() => (playing ? TrackPlayer.pause() : TrackPlayer.play())} style={styles.miniBtn}>
        <Ionicons name={playing ? 'pause' : 'play'} size={26} color={colors.text} />
      </Pressable>
    </Pressable>
  );
}

function SimpleSheet({
  visible,
  onClose,
  colors,
  children,
}: {
  visible: boolean;
  onClose: () => void;
  colors: any;
  children: React.ReactNode;
}) {
  const insets = useSafeAreaInsets();
  return (
    <Modal visible={visible} transparent animationType="fade" onRequestClose={onClose}>
      {/* The dim layer is its OWN full-screen view and never resizes. It used to be the
          KeyboardAvoidingView's own background, but that view physically shrinks to
          make room for the keyboard, so the dimming shrank with it and the real screen
          showed through along the bottom.

          It also carries the tap-to-close, as a SIBLING behind the sheet rather than a
          wrapper around it: a Pressable ancestor competes for the touch responder,
          which stopped an inner ScrollView from panning and stole drags from the
          sliders. */}
      <Pressable style={styles.modalDim} onPress={onClose} />

      <KeyboardAvoidingView
        behavior={Platform.OS === 'ios' ? 'padding' : 'height'}
        pointerEvents="box-none"
        style={[
          styles.modalOverlay,
          // Keep the sheet clear of the status bar and gesture area. Without this a
          // tall sheet slides under the clock once the keyboard shrinks the container.
          { paddingTop: insets.top + 12, paddingBottom: insets.bottom + 12 },
        ]}
      >
        <View style={[styles.sheet, { backgroundColor: colors.surface, borderColor: colors.border }]}>
          {children}
        </View>
      </KeyboardAvoidingView>
    </Modal>
  );
}

function InputModal({
  visible,
  colors,
  title,
  placeholder,
  value,
  onChangeText,
  confirmLabel,
  cancelLabel,
  onConfirm,
  onCancel,
}: {
  visible: boolean;
  colors: any;
  title: string;
  placeholder: string;
  value: string;
  onChangeText: (v: string) => void;
  confirmLabel: string;
  cancelLabel: string;
  onConfirm: () => void;
  onCancel: () => void;
}) {
  return (
    <Modal visible={visible} transparent animationType="fade" onRequestClose={onCancel}>
      <KeyboardAvoidingView behavior={Platform.OS === 'ios' ? 'padding' : 'height'} style={styles.modalOverlay}>
        <View style={[styles.card, { backgroundColor: colors.surface, borderColor: colors.border }]}>
          <Text style={[styles.cardTitle, { color: colors.text }]}>{title}</Text>
          <TextInput
            value={value}
            onChangeText={onChangeText}
            placeholder={placeholder}
            placeholderTextColor={colors.textMuted}
            autoFocus
            style={[styles.cardInput, { color: colors.text, borderColor: colors.border, backgroundColor: colors.surfaceHover }]}
          />
          <View style={styles.cardButtons}>
            <Pressable onPress={onCancel} style={[styles.cardButton, { backgroundColor: colors.surfaceHover }]}>
              <Text style={[styles.cardButtonText, { color: colors.text }]}>{cancelLabel}</Text>
            </Pressable>
            <Pressable onPress={onConfirm} style={[styles.cardButton, { backgroundColor: colors.accent }]}>
              <Text style={[styles.cardButtonText, { color: colors.background }]}>{confirmLabel}</Text>
            </Pressable>
          </View>
        </View>
      </KeyboardAvoidingView>
    </Modal>
  );
}

function PlaylistPickerModal({
  visible,
  colors,
  playlists,
  title,
  message,
  emptyText,
  confirmLabel,
  cancelLabel,
  onConfirm,
  onCancel,
}: {
  visible: boolean;
  colors: any;
  playlists: LocalSoundPlaylist[];
  title: string;
  message: string;
  emptyText: string;
  confirmLabel: string;
  cancelLabel: string;
  onConfirm: (playlistIds: string[]) => void;
  onCancel: () => void;
}) {
  const [selected, setSelected] = useState<Set<string>>(new Set());
  useEffect(() => {
    if (visible) setSelected(new Set());
  }, [visible]);

  return (
    <Modal visible={visible} transparent animationType="fade" onRequestClose={onCancel}>
      <View style={styles.modalOverlay}>
        <View style={[styles.card, { backgroundColor: colors.surface, borderColor: colors.border }]}>
          <Text style={[styles.cardTitle, { color: colors.text }]}>{title}</Text>
          <Text style={[styles.cardMessage, { color: colors.textMuted }]}>{message}</Text>
          {playlists.length === 0 ? (
            <Text style={[styles.cardMessage, { color: colors.textMuted }]}>{emptyText}</Text>
          ) : (
            <ScrollView style={styles.pickerList}>
              {playlists.map((pl) => {
                const checked = selected.has(pl.id);
                return (
                  <Pressable
                    key={pl.id}
                    onPress={() =>
                      setSelected((prev) => {
                        const next = new Set(prev);
                        if (next.has(pl.id)) next.delete(pl.id);
                        else next.add(pl.id);
                        return next;
                      })
                    }
                    style={[styles.pickerRow, { borderColor: colors.border }]}
                  >
                    <Ionicons
                      name={checked ? 'checkbox' : 'square-outline'}
                      size={22}
                      color={checked ? colors.accent : colors.textMuted}
                    />
                    <Text style={[styles.pickerRowText, { color: colors.text }]}>{pl.name}</Text>
                  </Pressable>
                );
              })}
            </ScrollView>
          )}
          <View style={styles.cardButtons}>
            <Pressable onPress={onCancel} style={[styles.cardButton, { backgroundColor: colors.surfaceHover }]}>
              <Text style={[styles.cardButtonText, { color: colors.text }]}>{cancelLabel}</Text>
            </Pressable>
            <Pressable
              disabled={selected.size === 0}
              onPress={() => onConfirm(Array.from(selected))}
              style={[styles.cardButton, { backgroundColor: colors.accent, opacity: selected.size === 0 ? 0.5 : 1 }]}
            >
              <Text style={[styles.cardButtonText, { color: colors.background }]}>{confirmLabel}</Text>
            </Pressable>
          </View>
        </View>
      </View>
    </Modal>
  );
}

function confirmConfig(
  action: PendingAction,
  t: (k: string, opts?: any) => string
): { title: string; message: string; confirm: string; destructive: boolean } | null {
  if (!action) return null;
  switch (action.type) {
    case 'deleteSingle':
      return {
        title: t('sounds.deleteTitle'),
        message: t('sounds.deleteMessage', { title: action.song.title }),
        confirm: t('sounds.deleteConfirm'),
        destructive: true,
      };
    case 'deleteBatch':
      return {
        title: t('sounds.batchDeleteTitle', { count: action.ids.length }),
        message: t('sounds.batchDeleteMessage'),
        confirm: t('sounds.batchDeleteConfirm'),
        destructive: true,
      };
    case 'removeFromPlaylist':
      return {
        title: t('sounds.batchRemoveFromPlaylistTitle', { count: action.ids.length }),
        message: t('sounds.batchRemoveFromPlaylistMessage', { name: action.playlist.name }),
        confirm: t('sounds.removeFromPlaylistConfirm'),
        destructive: false,
      };
    case 'deletePlaylist':
      return {
        title: t('sounds.playlistDeleteTitle'),
        message: t('sounds.playlistDeleteMessage', { name: action.playlist.name }),
        confirm: t('sounds.playlistDeleteConfirm'),
        destructive: true,
      };
    case 'restorePreset':
      return {
        title: t('sounds.presetRestoreTitle'),
        message: t('sounds.presetRestoreMessage'),
        confirm: t('sounds.presetRestoreConfirm'),
        destructive: true,
      };
    case 'deletePreset':
      return {
        title: t('sounds.presetDeleteTitle'),
        message: t('sounds.presetDeleteMessage', { name: action.preset.name }),
        confirm: t('sounds.presetDeleteConfirm'),
        destructive: true,
      };
    default:
      return null;
  }
}

const styles = StyleSheet.create({
  container: { flex: 1 },
  centered: { flex: 1, alignItems: 'center', justifyContent: 'center', gap: 12, padding: 24 },
  emptyText: { fontSize: 16, fontWeight: '600', textAlign: 'center' },
  emptyHint: { fontSize: 13, textAlign: 'center' },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 12,
    paddingVertical: 10,
    borderBottomWidth: StyleSheet.hairlineWidth,
    gap: 8,
  },
  headerTitle: { flex: 1, fontSize: 18, fontWeight: '700' },
  headerRight: { flexDirection: 'row', alignItems: 'center' },
  headerActions: { flexDirection: 'row', alignItems: 'center', gap: 4 },
  headerBtn: { padding: 6 },
  segment: {
    flexDirection: 'row',
    marginHorizontal: 12,
    marginTop: 12,
    padding: 4,
    borderRadius: 14,
    borderWidth: 1,
    gap: 4,
  },
  segmentBtn: { flex: 1, alignItems: 'center', justifyContent: 'center', paddingVertical: 9, borderRadius: 10 },
  segmentText: { fontSize: 14, lineHeight: 19, fontWeight: '700', includeFontPadding: false, textAlignVertical: 'center' },
  searchBar: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
    marginHorizontal: 12,
    marginTop: 10,
    paddingHorizontal: 12,
    paddingVertical: 8,
    borderRadius: 12,
    borderWidth: 1,
  },
  searchInput: { flex: 1, fontSize: 15, padding: 0 },
  listContent: { paddingHorizontal: 12, paddingTop: 10, paddingBottom: 160 },
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
    padding: 10,
    borderRadius: 14,
    borderWidth: 1,
    marginBottom: 8,
  },
  rowMain: { flex: 1, flexDirection: 'row', alignItems: 'center', gap: 12 },
  rowLead: { marginRight: 2 },
  thumb: { width: 48, height: 48, borderRadius: 8, alignItems: 'center', justifyContent: 'center', overflow: 'hidden' },
  thumbImage: { width: '100%', height: '100%' },
  rowText: { flex: 1 },
  rowTitle: { fontSize: 15, fontWeight: '600' },
  rowMeta: { fontSize: 12, flexShrink: 1 },
  rowFormat: { fontSize: 12, fontWeight: '600' },
  // The artist/duration text shrinks so a long title never pushes the badge off-screen.
  rowMetaLine: { flexDirection: 'row', alignItems: 'center', marginTop: 2 },
  presetBadge: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 3,
    marginLeft: 6,
    paddingHorizontal: 5,
    paddingVertical: 1,
    borderRadius: 4,
    borderWidth: 1,
  },
  presetBadgeText: { fontSize: 9, fontWeight: '700', letterSpacing: 0.4 },
  infoSheetBody: { paddingHorizontal: SHEET_INSET, paddingVertical: 4 },
  infoTitle: { fontSize: 16, fontWeight: '600' },
  infoArtist: { fontSize: 13, marginTop: 2, marginBottom: 10 },
  sheetTitle: { fontSize: 16, fontWeight: '600', marginBottom: 8, paddingHorizontal: SHEET_INSET },
  presetHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 8,
    marginBottom: 8,
    paddingHorizontal: SHEET_INSET,
  },
  // flexShrink lets the slider list give up height when the keyboard shrinks the
  // sheet; a fixed maxHeight alone would push the footer off the screen instead.
  presetScroll: { flexShrink: 1, maxHeight: 380 },
  presetScrollContent: { paddingHorizontal: SHEET_INSET },
  presetRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    borderWidth: 1,
    borderRadius: 10,
    paddingHorizontal: 12,
    paddingVertical: 12,
    marginBottom: 8,
  },
  presetRowMain: { flexDirection: 'row', alignItems: 'center', gap: 10, flex: 1 },
  presetName: { fontSize: 15 },
  presetNameWrap: { flex: 1 },
  presetSubLabel: { fontSize: 11, marginTop: 1 },
  presetFixedRow: { paddingHorizontal: SHEET_INSET, paddingBottom: 8 },
  presetFooter: {
    paddingHorizontal: SHEET_INSET,
    paddingTop: 10,
    borderTopWidth: StyleSheet.hairlineWidth,
  },
  presetEditorActions: { flexDirection: 'row', flexWrap: 'wrap', gap: 8 },
  presetSecondaryBtn: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
    borderWidth: 1,
    borderRadius: 10,
    paddingHorizontal: 12,
    paddingVertical: 10,
  },
  presetSaveRow: { flexDirection: 'row', alignItems: 'center', gap: 8, marginTop: 12 },
  // No flex here. It previously shared a row with a Save button; standing alone in a
  // column it would shrink to nothing when the keyboard squeezed the sheet, leaving a
  // sliver with the typed text invisible inside it.
  presetInput: {
    borderWidth: 1,
    borderRadius: 10,
    paddingHorizontal: 12,
    paddingVertical: 10,
    minHeight: 44,
  },
  presetSaveBtn: { borderWidth: 1, borderRadius: 10, paddingHorizontal: 14, paddingVertical: 10 },
  presetApplyBtn: { borderRadius: 12, paddingVertical: 14, alignItems: 'center', marginTop: 10 },
  presetApplyText: { color: '#0b0b0d', fontWeight: '700', fontSize: 15 },
  renderBanner: {
    position: 'absolute',
    left: 12,
    right: 12,
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
    borderWidth: 1,
    borderRadius: 12,
    paddingHorizontal: 14,
    paddingVertical: 12,
  },
  renderBannerText: { flex: 1 },
  renderBannerTitle: { fontSize: 13 },
  renderBarTrack: { height: 4, borderRadius: 2, marginTop: 6, overflow: 'hidden' },
  renderBarFill: { height: '100%' },
  rowAction: { padding: 4 },
  playlistActionBtn: {
    width: 34,
    height: 34,
    borderRadius: 9,
    borderWidth: 1,
    alignItems: 'center',
    justifyContent: 'center',
  },
  createRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 6,
    paddingVertical: 14,
    borderRadius: 14,
    borderWidth: 1,
    borderStyle: 'dashed',
    marginTop: 4,
  },
  createRowText: { fontSize: 14, fontWeight: '600' },
  selectionBar: {
    position: 'absolute',
    left: 12,
    right: 12,
    bottom: 18,
    flexDirection: 'row',
    alignItems: 'center',
    borderRadius: 16,
    borderWidth: 1,
    paddingHorizontal: 12,
    paddingVertical: 10,
    gap: 10,
  },
  selectionCount: { flex: 1, fontSize: 15, fontWeight: '600' },
  selectionActions: { flexDirection: 'row', alignItems: 'center', gap: 8 },
  selectionBtn: { padding: 6 },
  miniPlayer: {
    position: 'absolute',
    left: 12,
    right: 12,
    bottom: 18,
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
    borderRadius: 16,
    borderWidth: 1,
    padding: 8,
  },
  miniThumb: { width: 44, height: 44, borderRadius: 8, alignItems: 'center', justifyContent: 'center', overflow: 'hidden' },
  miniThumbImg: { width: '100%', height: '100%' },
  miniText: { flex: 1 },
  miniTitle: { fontSize: 14, fontWeight: '600' },
  miniArtist: { fontSize: 12, marginTop: 1 },
  miniBtn: { padding: 6 },
  toast: {
    position: 'absolute',
    left: 24,
    right: 24,
    bottom: 90,
    borderRadius: 12,
    borderWidth: 1,
    paddingHorizontal: 16,
    paddingVertical: 12,
    alignItems: 'center',
  },
  toastText: { fontSize: 13, fontWeight: '600' },
  modalOverlay: { flex: 1, alignItems: 'center', justifyContent: 'center', paddingHorizontal: 24 },
  modalDim: { ...StyleSheet.absoluteFillObject, backgroundColor: '#000000CC' },
  // maxHeight keeps a tall sheet inside the screen instead of overflowing past the
  // status bar when the keyboard shrinks the available space.
  sheet: {
    width: '100%',
    maxWidth: 420,
    maxHeight: '100%',
    borderRadius: 16,
    borderWidth: 1,
    paddingVertical: 8,
  },
  sheetRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 18,
    paddingVertical: 14,
    borderBottomWidth: StyleSheet.hairlineWidth,
  },
  sheetRowText: { fontSize: 15 },
  card: { width: '100%', maxWidth: 420, borderRadius: 16, borderWidth: 1, padding: 20, gap: 12 },
  cardTitle: { fontSize: 17, fontWeight: '700' },
  cardMessage: { fontSize: 14, lineHeight: 20 },
  cardInput: { borderRadius: 10, borderWidth: 1, paddingHorizontal: 12, paddingVertical: 10, fontSize: 15 },
  pickerList: { maxHeight: 260 },
  pickerRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 10,
    paddingVertical: 12,
    borderBottomWidth: StyleSheet.hairlineWidth,
  },
  pickerRowText: { fontSize: 15, flex: 1 },
  cardButtons: { flexDirection: 'row', gap: 10, marginTop: 4 },
  cardButton: { flex: 1, alignItems: 'center', justifyContent: 'center', borderRadius: 10, paddingVertical: 12, minHeight: 44 },
  cardButtonText: { fontSize: 15, fontWeight: '700' },
});
