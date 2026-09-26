import { Ionicons } from '@expo/vector-icons';
import { Image } from 'expo-image';
import { useVideoPlayer, VideoView } from 'expo-video';
import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  ActivityIndicator,
  Keyboard,
  KeyboardAvoidingView,
  Linking,
  Modal,
  Platform,
  Pressable,
  ScrollView,
  StyleSheet,
  TextInput,
  View,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import {
  confirmMemeFace,
  createMemePerson,
  createMemeTag,
  dismissMemePrompt,
  getMemeSuggestions,
  getMemeThumbnail,
  labelMemes,
  MEME_FACETS,
  nameMemeFaces,
  rejectMemeFace,
  type LocalMeme,
  type LocalMemeFacet,
  type LocalMemeLibrary,
  type LocalMemeTag,
} from '@/src/api';
import { AppText as Text, Chip } from '@/src/components';
import { useTheme } from '@/src/theme';

import { FaceCrop } from './FaceCrop';
import { foldForMatching } from './folding';

export type MemeSheetMode = 'detail' | 'batch' | 'review' | 'prompt';

interface Props {
  ids: string[];
  mode: MemeSheetMode;
  library: LocalMemeLibrary;
  onClose: () => void;
  /** Labels changed; the caller reloads the library. */
  onChanged: () => Promise<void> | void;
  /** A private video plays in the vault's own player, behind its own gate. */
  onPlayPrivate: (meme: LocalMeme) => void;
  /** Into the vault or out of it. */
  onSetPrivate: (ids: string[], makePrivate: boolean) => Promise<void>;
}

type Tri = 'all' | 'some' | 'none';

/**
 * Tagging, for one meme or many: the quick prompt after a download, the detail of one meme,
 * a batch over a selection, and review mode over the untagged inbox.
 *
 * Changes are staged and written on Save, as one call, so a batch is one write rather than
 * one per chip.
 */
export function MemeSheet({ ids, mode, library, onClose, onChanged, onPlayPrivate, onSetPrivate }: Props) {
  const { t } = useTranslation();
  const { colors } = useTheme();

  const [index, setIndex] = useState(0);
  const [addTags, setAddTags] = useState<Set<string>>(new Set());
  const [removeTags, setRemoveTags] = useState<Set<string>>(new Set());
  const [addPeople, setAddPeople] = useState<Set<string>>(new Set());
  const [removePeople, setRemovePeople] = useState<Set<string>>(new Set());
  const [text, setText] = useState('');
  const [newTagFacets, setNewTagFacets] = useState<Set<LocalMemeFacet>>(new Set());
  const [suggested, setSuggested] = useState<string[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // While typing, the meme shrinks so the field and what it matches stay in view.
  const [typing, setTyping] = useState(false);

  useEffect(() => {
    const shown = Keyboard.addListener('keyboardDidShow', () => setTyping(true));
    const hidden = Keyboard.addListener('keyboardDidHide', () => setTyping(false));
    return () => {
      shown.remove();
      hidden.remove();
    };
  }, []);

  const targetIds = useMemo(() => (mode === 'review' ? ids.slice(index, index + 1) : ids), [ids, index, mode]);
  const targets = useMemo(
    () => library.items.filter((item) => targetIds.includes(item.id)),
    [library.items, targetIds],
  );
  const single = targets.length === 1 ? targets[0] : null;

  const resetStage = useCallback(() => {
    setAddTags(new Set());
    setRemoveTags(new Set());
    setAddPeople(new Set());
    setRemovePeople(new Set());
    setText('');
    setNewTagFacets(new Set());
    setError(null);
  }, []);

  useEffect(() => {
    if (!single) {
      setSuggested([]);
      return;
    }
    let live = true;
    getMemeSuggestions(single.id).then((next) => live && setSuggested(next)).catch(() => undefined);
    return () => {
      live = false;
    };
  }, [single]);

  const tagsById = useMemo(() => new Map(library.tags.map((tag) => [tag.id, tag])), [library.tags]);
  const peopleById = useMemo(() => new Map(library.people.map((p) => [p.id, p])), [library.people]);

  const stateOf = useCallback(
    (id: string, kind: 'tag' | 'person'): Tri => {
      const adding = kind === 'tag' ? addTags : addPeople;
      const removing = kind === 'tag' ? removeTags : removePeople;
      if (adding.has(id)) return 'all';
      if (removing.has(id)) return 'none';
      const count = targets.filter((item) => (kind === 'tag' ? item.tags : item.people).includes(id)).length;
      if (count === 0) return 'none';
      return count === targets.length ? 'all' : 'some';
    },
    [addPeople, addTags, removePeople, removeTags, targets],
  );

  const setLabel = useCallback((id: string, kind: 'tag' | 'person', on: boolean) => {
    const [setAdd, setRemove] = kind === 'tag' ? [setAddTags, setRemoveTags] : [setAddPeople, setRemovePeople];
    setAdd((prev) => {
      const next = new Set(prev);
      if (on) next.add(id);
      else next.delete(id);
      return next;
    });
    setRemove((prev) => {
      const next = new Set(prev);
      if (on) next.delete(id);
      else next.add(id);
      return next;
    });
  }, []);

  /** On all → off all; on some or none → on all. */
  const toggle = useCallback(
    (id: string, kind: 'tag' | 'person') => setLabel(id, kind, stateOf(id, kind) !== 'all'),
    [setLabel, stateOf],
  );

  const shownTags = useMemo(() => {
    const ids_ = new Set<string>();
    targets.forEach((item) => item.tags.forEach((id) => ids_.add(id)));
    addTags.forEach((id) => ids_.add(id));
    return [...ids_].filter((id) => tagsById.has(id));
  }, [addTags, tagsById, targets]);

  const shownPeople = useMemo(() => {
    const ids_ = new Set<string>();
    targets.forEach((item) => item.people.forEach((id) => ids_.add(id)));
    addPeople.forEach((id) => ids_.add(id));
    return [...ids_].filter((id) => peopleById.has(id));
  }, [addPeople, peopleById, targets]);

  const visibleSuggestions = useMemo(
    () => suggested.filter((id) => tagsById.has(id) && stateOf(id, 'tag') === 'none').slice(0, 10),
    [stateOf, suggested, tagsById],
  );

  const query = foldForMatching(text);
  const matches = useMemo(() => {
    if (!query) return { tags: [], people: [] };
    const starts = (name: string) => foldForMatching(name).split(/\s+/).some((word) => word.startsWith(query))
      || foldForMatching(name).startsWith(query);
    return {
      tags: library.tags.filter((tag) => starts(tag.name) && stateOf(tag.id, 'tag') !== 'all').slice(0, 8),
      people: library.people.filter((p) => starts(p.name) && stateOf(p.id, 'person') !== 'all').slice(0, 5),
    };
  }, [library.people, library.tags, query, stateOf]);
  const exactTag = library.tags.some((tag) => foldForMatching(tag.name) === query);
  const exactPerson = library.people.some((p) => foldForMatching(p.name) === query);

  /**
   * The tag in the field, with the facets picked for it. Creating by name finds a tag of the
   * same folded name and adds the facets to it, so this serves an existing tag as well.
   */
  const commitTyped = useCallback(
    async (existing?: LocalMemeTag): Promise<string | null> => {
      const name = existing?.name ?? text.trim();
      if (!name) return null;
      const id =
        existing && newTagFacets.size === 0 ? existing.id : (await createMemeTag(name, [...newTagFacets])).id;
      setText('');
      setNewTagFacets(new Set());
      return id;
    },
    [newTagFacets, text],
  );

  const addTag = useCallback(
    async (existing?: LocalMemeTag) => {
      try {
        const id = await commitTyped(existing);
        if (!id) return;
        await onChanged();
        setLabel(id, 'tag', true);
      } catch (e) {
        setError(e instanceof Error ? e.message : String(e));
      }
    },
    [commitTyped, onChanged, setLabel],
  );

  const addNewPerson = useCallback(async () => {
    const name = text.trim();
    if (!name) return;
    try {
      const person = await createMemePerson(name);
      await onChanged();
      setLabel(person.id, 'person', true);
      setText('');
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }, [onChanged, setLabel, text]);

  const advance = useCallback(() => {
    resetStage();
    if (mode === 'review' && index + 1 < ids.length) {
      setIndex(index + 1);
    } else {
      onClose();
    }
  }, [ids.length, index, mode, onClose, resetStage]);

  const save = useCallback(async () => {
    if (targets.length === 0) return advance();
    setBusy(true);
    try {
      // What is still in the field counts: Save is the obvious way to finish typing it.
      const pendingTags = new Set(addTags);
      const pendingPeople = new Set(addPeople);
      const typedPerson = library.people.find((p) => foldForMatching(p.name) === foldForMatching(text));
      if (text.trim() && typedPerson && !exactTag) {
        pendingPeople.add(typedPerson.id);
        setText('');
      } else if (text.trim()) {
        const typed = await commitTyped(library.tags.find((tag) => foldForMatching(tag.name) === foldForMatching(text)));
        if (typed) pendingTags.add(typed);
      }
      const result = await labelMemes({
        ids: targets.map((item) => item.id),
        addTags: [...pendingTags],
        removeTags: [...removeTags].filter((id) => !pendingTags.has(id)),
        addPeople: [...pendingPeople],
        removePeople: [...removePeople],
        markTagged: true,
      });
      if (!result.success) {
        setError(result.code === 'PRIVATE_VAULT_LOCKED' ? t('memes.lockedError') : (result.code ?? ''));
        return;
      }
      await onChanged();
      advance();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }, [addPeople, addTags, advance, commitTyped, exactTag, library.people, library.tags, onChanged, removePeople,
    removeTags, t, targets, text]);

  const later = useCallback(() => {
    targets.forEach((item) => void dismissMemePrompt(item.id).catch(() => undefined));
    onClose();
  }, [onClose, targets]);

  const title =
    mode === 'review'
      ? t('memes.sheet.review', { index: index + 1, total: ids.length })
      : mode === 'prompt'
        ? t('memes.sheet.prompt')
        : targets.length > 1
          ? t('memes.sheet.many', { count: targets.length })
          : t('memes.sheet.one');

  const chip = (id: string, kind: 'tag' | 'person') => {
    const state = stateOf(id, kind);
    const tag = kind === 'tag' ? tagsById.get(id) : undefined;
    const facets = tag?.facets.map((facet) => t(`memes.facet.${facet}`)) ?? [];
    const label = [tag?.name ?? peopleById.get(id)?.name, ...facets].join(' · ');
    return (
      <Chip
        key={`${kind}-${id}`}
        label={state === 'some' ? `${label} · ${t('memes.onSome')}` : (label ?? '')}
        color={kind === 'tag' ? colors.accent : colors.info}
        active={state === 'all'}
        iconName={kind === 'person' ? 'person-outline' : undefined}
        onPress={() => toggle(id, kind)}
      />
    );
  };

  return (
    <Modal visible animationType="slide" onRequestClose={mode === 'prompt' ? later : onClose}>
      <SafeAreaView style={[styles.fill, { backgroundColor: colors.background }]}>
        {/* On Android too: edge to edge, the window no longer resizes for the keyboard. */}
        <KeyboardAvoidingView style={styles.fill} behavior={Platform.OS === 'ios' ? 'padding' : 'height'}>
          <View style={[styles.header, { borderBottomColor: colors.border }]}>
            <Pressable onPress={mode === 'prompt' ? later : onClose} hitSlop={10} accessibilityRole="button">
              <Ionicons name="close" size={24} color={colors.text} />
            </Pressable>
            <Text style={[styles.title, { color: colors.text }]} numberOfLines={1}>
              {title}
            </Text>
            {single ? (
              <Pressable
                onPress={() => void onSetPrivate([single.id], !single.isPrivate)}
                hitSlop={10}
                accessibilityRole="button"
                accessibilityLabel={single.isPrivate ? t('memes.makePublic') : t('memes.makePrivate')}
                style={styles.lockButton}
              >
                <Ionicons name={single.isPrivate ? 'lock-open-outline' : 'lock-closed-outline'} size={18} color={colors.text} />
                <Text style={[styles.lockLabel, { color: colors.text }]}>
                  {single.isPrivate ? t('memes.makePublic') : t('memes.makePrivate')}
                </Text>
              </Pressable>
            ) : (
              <View style={styles.headerSpacer} />
            )}
          </View>

          <ScrollView contentContainerStyle={styles.body} keyboardShouldPersistTaps="handled">
            {targets.length === 0 ? (
              <Text style={{ color: colors.textMuted }}>{t('memes.unlockToShowPrivate')}</Text>
            ) : null}
            {single ? <MemePreview key={single.id} meme={single} compact={typing} onPlayPrivate={onPlayPrivate} /> : null}
            {single ? <SourceView meme={single} compact={typing} /> : null}
            {single && !typing ? <FacesRow meme={single} library={library} onChanged={onChanged} /> : null}

            <TextInput
              value={text}
              onChangeText={setText}
              placeholder={t('memes.addPlaceholder')}
              placeholderTextColor={colors.textSubtle}
              style={[styles.input, { color: colors.text, backgroundColor: colors.surface, borderColor: colors.border }]}
              autoCorrect={false}
              returnKeyType="done"
              onSubmitEditing={() => void addTag(exactTag ? undefined : matches.tags[0])}
            />
            {query ? (
              <View style={styles.wrap}>
                {matches.tags.map((tag) => (
                  <Chip key={`m-${tag.id}`} label={tag.name} color={colors.accent} onPress={() => void addTag(tag)} />
                ))}
                {matches.people.map((p) => (
                  <Chip key={`mp-${p.id}`} label={p.name} color={colors.info} iconName="person-outline" onPress={() => {
                    setLabel(p.id, 'person', true);
                    setText('');
                  }} />
                ))}
                {!exactTag ? (
                  <Chip label={t('memes.addTag', { name: text.trim() })} iconName="add" color={colors.accent} onPress={() => void addTag()} />
                ) : null}
                {!exactPerson ? (
                  <Chip label={t('memes.addPerson', { name: text.trim() })} iconName="person-add-outline" color={colors.info}
                    onPress={addNewPerson} />
                ) : null}
              </View>
            ) : null}
            {query ? (
              <>
                <Text style={[styles.label, { color: colors.textMuted }]}>{t('memes.newTagFacets')}</Text>
                <View style={styles.wrap}>
                  {MEME_FACETS.map((facet) => (
                    <Chip
                      key={facet}
                      size="sm"
                      label={t(`memes.facet.${facet}`)}
                      active={newTagFacets.has(facet)}
                      color={colors.warning}
                      onPress={() => setNewTagFacets((prev) => {
                        const next = new Set(prev);
                        if (next.has(facet)) next.delete(facet);
                        else next.add(facet);
                        return next;
                      })}
                    />
                  ))}
                </View>
              </>
            ) : null}

            {visibleSuggestions.length > 0 ? (
              <>
                <Text style={[styles.label, { color: colors.textMuted }]}>{t('memes.suggestions')}</Text>
                <View style={styles.wrap}>{visibleSuggestions.map((id) => chip(id, 'tag'))}</View>
              </>
            ) : null}

            {shownTags.length > 0 ? (
              <>
                <Text style={[styles.label, { color: colors.textMuted }]}>{t('memes.tagsLabel')}</Text>
                <View style={styles.wrap}>{shownTags.map((id) => chip(id, 'tag'))}</View>
              </>
            ) : null}

            {shownPeople.length > 0 ? (
              <>
                <Text style={[styles.label, { color: colors.textMuted }]}>{t('memes.peopleLabel')}</Text>
                <View style={styles.wrap}>{shownPeople.map((id) => chip(id, 'person'))}</View>
              </>
            ) : null}

            {error ? <Text style={[styles.error, { color: colors.error }]}>{error}</Text> : null}
          </ScrollView>

          <View style={[styles.footer, { borderTopColor: colors.border }]}>
            {mode === 'prompt' ? (
              <Pressable style={[styles.button, { backgroundColor: colors.surface }]} onPress={later}>
                <Text style={{ color: colors.text }}>{t('memes.later')}</Text>
              </Pressable>
            ) : null}
            {mode === 'review' ? (
              <Pressable style={[styles.button, { backgroundColor: colors.surface }]} onPress={advance}>
                <Text style={{ color: colors.text }}>{t('memes.skip')}</Text>
              </Pressable>
            ) : null}
            <Pressable
              style={[styles.button, styles.primary, { backgroundColor: colors.accent }]}
              onPress={save}
              disabled={busy}
            >
              {busy ? <ActivityIndicator color={colors.primaryText} /> : (
                <Text style={{ color: colors.primaryText }}>{t('memes.save')}</Text>
              )}
            </Pressable>
          </View>
        </KeyboardAvoidingView>
      </SafeAreaView>
    </Modal>
  );
}

/** The meme itself: a looping video, an image, or for a private one its thumbnail and Play. */
function MemePreview({
  meme,
  compact,
  onPlayPrivate,
}: {
  meme: LocalMeme;
  compact: boolean;
  onPlayPrivate: (meme: LocalMeme) => void;
}) {
  const { t } = useTranslation();
  const { colors } = useTheme();
  const [thumb, setThumb] = useState<string | null>(null);
  const playable = !meme.isPrivate && meme.kind === 'video' && meme.uri ? meme.uri : null;
  const player = useVideoPlayer(playable ? { uri: playable } : null, (instance) => {
    instance.loop = true;
    instance.play();
  });

  useEffect(() => {
    if (!meme.isPrivate) return;
    let live = true;
    getMemeThumbnail(meme.id).then((uri) => live && setThumb(uri)).catch(() => undefined);
    return () => {
      live = false;
    };
  }, [meme.id, meme.isPrivate]);

  const frame = [styles.preview, compact && styles.previewCompact];
  if (playable) {
    return <VideoView player={player} style={frame} contentFit="contain" nativeControls={!compact} />;
  }
  if (!meme.isPrivate && meme.uri) {
    return <Image source={{ uri: meme.uri }} style={frame} contentFit="contain" />;
  }
  return (
    <View style={[frame, styles.center, { backgroundColor: colors.surface }]}>
      {thumb ? <Image source={{ uri: thumb }} style={StyleSheet.absoluteFill} contentFit="contain" /> : null}
      {meme.kind === 'video' ? (
        <Pressable
          onPress={() => onPlayPrivate(meme)}
          style={[styles.play, { backgroundColor: colors.overlay }]}
          accessibilityRole="button"
          accessibilityLabel={t('memes.play')}
        >
          <Ionicons name="play" size={28} color="#fff" />
        </Pressable>
      ) : (
        <Ionicons name="lock-closed" size={28} color={colors.textMuted} />
      )}
    </View>
  );
}

/**
 * The faces in one meme: who each is, and a way to say otherwise. A tap picks a face; what
 * can be said about it appears under the row.
 */
function FacesRow({
  meme,
  library,
  onChanged,
}: {
  meme: LocalMeme;
  library: LocalMemeLibrary;
  onChanged: () => Promise<void> | void;
}) {
  const { t } = useTranslation();
  const { colors } = useTheme();
  const [picked, setPicked] = useState<string | null>(null);
  const [name, setName] = useState('');
  const faces = meme.faces ?? [];
  if (faces.length === 0) return null;
  const nameOf = (id?: string | null) => library.people.find((p) => p.id === id)?.name ?? '';
  const face = faces.find((f) => f.id === picked);

  const act = async (action: () => Promise<unknown>) => {
    await action();
    setPicked(null);
    setName('');
    await onChanged();
  };

  return (
    <View style={styles.faces}>
      <Text style={[styles.label, { color: colors.textMuted }]}>{t('memes.faces.title')}</Text>
      <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={styles.faceRow}>
        {faces.map((f) => (
          <Pressable key={f.id} onPress={() => setPicked(picked === f.id ? null : f.id)} style={styles.faceItem}>
            <View style={{ borderRadius: 12, borderWidth: 2, borderColor: picked === f.id ? colors.accent : 'transparent' }}>
              <FaceCrop itemId={meme.id} faceId={f.id} size={56} />
            </View>
            <Text style={[styles.faceName, { color: colors.textMuted }]} numberOfLines={1}>
              {f.state === 'unnamed' ? t('memes.faces.unknown') : f.state === 'asked' ? `${nameOf(f.person)}?` : nameOf(f.person)}
            </Text>
          </Pressable>
        ))}
      </ScrollView>
      {face ? (
        <View style={styles.wrap}>
          {face.state === 'asked' ? (
            <Chip label={t('memes.faces.yesIs', { name: nameOf(face.person) })} iconName="checkmark" color={colors.accent}
              onPress={() => void act(() => confirmMemeFace(face.id))} />
          ) : null}
          {face.person ? (
            <Chip label={t('memes.faces.notIs', { name: nameOf(face.person) })} iconName="close" color={colors.error}
              onPress={() => void act(() => rejectMemeFace(face.id))} />
          ) : null}
          <View style={styles.nameRow}>
            <TextInput
              value={name}
              onChangeText={setName}
              placeholder={t('memes.faces.whoIsThis')}
              placeholderTextColor={colors.textSubtle}
              style={[styles.input, styles.nameInput, { color: colors.text, backgroundColor: colors.surface, borderColor: colors.border }]}
              autoCorrect={false}
              onSubmitEditing={() => name.trim() && void act(() => nameMemeFaces([face.id], name.trim()))}
            />
            <Chip label={t('memes.faces.setName')} color={colors.accent}
              onPress={() => name.trim() && void act(() => nameMemeFaces([face.id], name.trim()))} />
          </View>
        </View>
      ) : null}
    </View>
  );
}

/** Where it came from: the account, the caption, and a way back to the post. */
function SourceView({ meme, compact }: { meme: LocalMeme; compact: boolean }) {
  const { t } = useTranslation();
  const { colors } = useTheme();
  const source = meme.source;
  if (!source) return null;
  const imported = source.platform === 'import';
  const who = source.account ? t('memes.source.account', { account: source.account }) : null;
  const where = imported ? t('memes.source.imported') : source.platform;
  return (
    <View style={[styles.source, { backgroundColor: colors.surface }]}>
      <Text style={[styles.sourceLine, { color: colors.textMuted }]} numberOfLines={1}>
        {[where, who].filter(Boolean).join(' · ')}
      </Text>
      {source.caption ? (
        <Text style={{ color: colors.text }} numberOfLines={compact ? 2 : 6} selectable>
          {source.caption}
        </Text>
      ) : null}
      {!compact && source.url && /^https?:\/\//.test(source.url) ? (
        <Pressable onPress={() => void Linking.openURL(source.url!)} hitSlop={6}>
          <Text style={{ color: colors.accent }}>{t('memes.source.openPost')}</Text>
        </Pressable>
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  fill: { flex: 1 },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
    paddingHorizontal: 16,
    paddingVertical: 12,
    borderBottomWidth: StyleSheet.hairlineWidth,
  },
  title: { flex: 1, fontSize: 17, fontWeight: '600' },
  headerSpacer: { width: 24 },
  lockButton: { flexDirection: 'row', alignItems: 'center', gap: 4 },
  lockLabel: { fontSize: 14 },
  body: { padding: 16, gap: 10 },
  preview: { width: '100%', aspectRatio: 1, borderRadius: 12, overflow: 'hidden', backgroundColor: '#000' },
  previewCompact: { aspectRatio: undefined, height: 140 },
  center: { alignItems: 'center', justifyContent: 'center' },
  play: { width: 64, height: 64, borderRadius: 32, alignItems: 'center', justifyContent: 'center' },
  source: { borderRadius: 12, padding: 12, gap: 6 },
  sourceLine: { fontSize: 13 },
  input: { borderWidth: 1, borderRadius: 10, paddingHorizontal: 12, paddingVertical: 10, fontSize: 16 },
  label: { fontSize: 13, marginTop: 6 },
  wrap: { flexDirection: 'row', flexWrap: 'wrap', gap: 8 },
  error: { fontSize: 14 },
  faces: { gap: 6 },
  faceRow: { gap: 10 },
  faceItem: { alignItems: 'center', gap: 3, width: 64 },
  faceName: { fontSize: 11 },
  nameRow: { flexDirection: 'row', alignItems: 'center', gap: 8, flexBasis: '100%' },
  nameInput: { flex: 1, paddingVertical: 6 },
  footer: {
    flexDirection: 'row',
    gap: 10,
    padding: 12,
    borderTopWidth: StyleSheet.hairlineWidth,
  },
  button: { flex: 1, borderRadius: 12, paddingVertical: 14, alignItems: 'center' },
  primary: {},
});
