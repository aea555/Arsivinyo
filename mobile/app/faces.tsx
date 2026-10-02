import { Ionicons } from '@expo/vector-icons';
import { useFocusEffect, useRouter } from 'expo-router';
import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  ActivityIndicator,
  KeyboardAvoidingView,
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
  askedFaces,
  confirmMemeFace,
  deleteMemePerson,
  EMPTY_MEME_LIBRARY,
  getMemeFaceGroups,
  listenMemesChanged,
  listMemes,
  nameMemeFaces,
  rejectMemeFace,
  renameMemePerson,
  type LocalMemeFaceGroup,
  type LocalMemeLibrary,
  type LocalMemePerson,
} from '@/src/api';
import { AppText as Text, Chip, ConfirmModal } from '@/src/components';
import { FaceCrop } from '@/src/features/memes/FaceCrop';
import { foldForMatching } from '@/src/features/memes/folding';
import { useTheme } from '@/src/theme';

/**
 * Faces across the collection: questions to answer, groups to name, and the people known.
 * `shared/memes/CONTRACT.md`, "Faces".
 */
export default function FacesScreen() {
  const { t } = useTranslation();
  const { colors } = useTheme();
  const router = useRouter();
  const [library, setLibrary] = useState<LocalMemeLibrary>(EMPTY_MEME_LIBRARY);
  const [groups, setGroups] = useState<LocalMemeFaceGroup[]>([]);
  const [loading, setLoading] = useState(true);

  const reload = useCallback(async () => {
    try {
      const [next, nextGroups] = await Promise.all([listMemes(), getMemeFaceGroups()]);
      setLibrary(next);
      setGroups(nextGroups);
    } finally {
      setLoading(false);
    }
  }, []);

  useFocusEffect(
    useCallback(() => {
      void reload();
    }, [reload]),
  );

  useEffect(() => {
    const subscription = listenMemesChanged(() => void reload());
    return () => subscription.remove();
  }, [reload]);

  const asked = useMemo(() => askedFaces(library), [library]);
  const peopleById = useMemo(() => new Map(library.people.map((p) => [p.id, p])), [library.people]);
  const [editing, setEditing] = useState<LocalMemePerson | null>(null);
  const [editName, setEditName] = useState('');
  const [confirmDelete, setConfirmDelete] = useState(false);

  // Everyone, known by face or only labelled by hand: either can be renamed or removed.
  const known = useMemo(
    () =>
      library.people
        .map((person) => {
          const labelled = library.items.filter((meme) => meme.people.includes(person.id)).length;
          const refs = library.items.flatMap((meme) =>
            (meme.faces ?? [])
              .filter((f) => f.person === person.id && (f.state === 'auto' || f.state === 'confirmed'))
              .map((face) => ({ meme, face })),
          );
          return { person, refs, memes: labelled };
        })
        .sort((a, b) => a.person.name.localeCompare(b.person.name, 'tr')),
    [library],
  );

  const empty = asked.length === 0 && groups.length === 0 && known.length === 0;

  return (
    <SafeAreaView style={[styles.fill, { backgroundColor: colors.background }]} edges={['top', 'left', 'right']}>
      <View style={styles.header}>
        <Pressable onPress={() => router.back()} hitSlop={10} accessibilityRole="button">
          <Ionicons name="arrow-back" size={24} color={colors.text} />
        </Pressable>
        <View style={styles.fill}>
          <Text style={[styles.title, { color: colors.text }]}>{t('memes.faces.title')}</Text>
          {library.facesRemaining != null ? (
            <Text style={[styles.subtitle, { color: colors.textMuted }]}>
              {t('memes.faces.scanning', { count: library.facesRemaining })}
            </Text>
          ) : null}
        </View>
      </View>

      {loading ? (
        <ActivityIndicator style={styles.fill} color={colors.accent} />
      ) : !library.facesSupported ? (
        <View style={[styles.fill, styles.empty]}>
          <Text style={{ color: colors.textMuted, textAlign: 'center' }}>{t('memes.faces.unsupported')}</Text>
        </View>
      ) : empty ? (
        <View style={[styles.fill, styles.empty]}>
          <Ionicons name="people-outline" size={48} color={colors.textMuted} />
          <Text style={[styles.emptyTitle, { color: colors.text }]}>{t('memes.faces.empty')}</Text>
          <Text style={{ color: colors.textMuted, textAlign: 'center' }}>{t('memes.faces.emptyHint')}</Text>
        </View>
      ) : (
        <ScrollView contentContainerStyle={styles.body} keyboardShouldPersistTaps="handled">
          {asked.length > 0 ? (
            <>
              <Text style={[styles.section, { color: colors.text }]}>{t('memes.faces.asked')}</Text>
              <View style={styles.wrap}>
                {asked.map(({ meme, face }) => (
                  <View key={face.id} style={[styles.card, { backgroundColor: colors.surface }]}>
                    <FaceCrop itemId={meme.id} faceId={face.id} size={96} />
                    <Text style={{ color: colors.text }} numberOfLines={1}>
                      {peopleById.get(face.person ?? '')?.name ?? ''}
                    </Text>
                    <View style={styles.row}>
                      <Pressable
                        accessibilityLabel={t('memes.faces.yes')}
                        onPress={() => void confirmMemeFace(face.id).then(reload)}
                        style={[styles.answer, { backgroundColor: colors.accent }]}
                      >
                        <Ionicons name="checkmark" size={18} color={colors.primaryText} />
                      </Pressable>
                      <Pressable
                        accessibilityLabel={t('memes.faces.no')}
                        onPress={() => void rejectMemeFace(face.id).then(reload)}
                        style={[styles.answer, { backgroundColor: colors.surfaceActive }]}
                      >
                        <Ionicons name="close" size={18} color={colors.text} />
                      </Pressable>
                    </View>
                  </View>
                ))}
              </View>
            </>
          ) : null}

          {groups.length > 0 ? (
            <>
              <Text style={[styles.section, { color: colors.text }]}>{t('memes.faces.unnamed')}</Text>
              <Text style={{ color: colors.textMuted }}>{t('memes.faces.unnamedHint')}</Text>
              {groups.map((group) => (
                <GroupRow key={group.faces[0]?.faceId} group={group} library={library} onNamed={reload} />
              ))}
            </>
          ) : null}

          {known.length > 0 ? (
            <>
              <Text style={[styles.section, { color: colors.text }]}>{t('memes.faces.people')}</Text>
              <View style={styles.wrap}>
                {known.map(({ person, refs, memes }) => (
                  <Pressable
                    key={person.id}
                    style={styles.person}
                    onPress={() => {
                      setEditing(person);
                      setEditName(person.name);
                    }}
                    accessibilityRole="button"
                    accessibilityHint={t('memes.faces.editHint')}
                  >
                    {refs[0] ? (
                      <FaceCrop itemId={refs[0].meme.id} faceId={refs[0].face.id} size={72} />
                    ) : (
                      <View style={{ width: 72, height: 72 }} />
                    )}
                    <Text style={{ color: colors.text }} numberOfLines={1}>{person.name}</Text>
                    <Text style={[styles.caption, { color: colors.textMuted }]}>
                      {t('memes.faces.memeCount', { count: memes })}
                    </Text>
                  </Pressable>
                ))}
              </View>
            </>
          ) : null}
        </ScrollView>
      )}

      <Modal visible={editing != null && !confirmDelete} transparent animationType="fade" onRequestClose={() => setEditing(null)}>
        <KeyboardAvoidingView style={styles.fill} behavior={Platform.OS === 'ios' ? 'padding' : 'height'}>
          <Pressable style={[styles.scrim, { backgroundColor: colors.overlay }]} onPress={() => setEditing(null)}>
            <Pressable style={[styles.editor, { backgroundColor: colors.surface }]} onPress={() => undefined}>
              <Text style={[styles.section, { color: colors.text }]}>{t('memes.faces.editTitle')}</Text>
              <TextInput
                value={editName}
                onChangeText={setEditName}
                autoFocus
                autoCorrect={false}
                style={[styles.input, styles.editInput, { color: colors.text, backgroundColor: colors.background, borderColor: colors.border }]}
              />
              <Text style={{ color: colors.textMuted }}>{t('memes.faces.renameHint')}</Text>
              <View style={styles.row}>
                <Pressable onPress={() => setConfirmDelete(true)} style={[styles.nameButton, { backgroundColor: colors.surfaceActive }]}>
                  <Text style={{ color: colors.error }}>{t('memes.faces.deletePerson')}</Text>
                </Pressable>
                <View style={styles.fill} />
                <Pressable
                  disabled={!editName.trim()}
                  onPress={async () => {
                    if (editing) await renameMemePerson(editing.id, editName.trim());
                    setEditing(null);
                    await reload();
                  }}
                  style={[styles.nameButton, { backgroundColor: editName.trim() ? colors.accent : colors.surfaceActive }]}
                >
                  <Text style={{ color: editName.trim() ? colors.primaryText : colors.textMuted }}>{t('memes.save')}</Text>
                </Pressable>
              </View>
            </Pressable>
          </Pressable>
        </KeyboardAvoidingView>
      </Modal>

      <ConfirmModal
        visible={confirmDelete}
        config={editing ? {
          title: t('memes.faces.deleteTitle', { name: editing.name }),
          message: t('memes.faces.deleteBody'),
          confirm: t('memes.faces.deletePerson'),
          destructive: true,
        } : null}
        onCancel={() => setConfirmDelete(false)}
        onConfirm={async () => {
          if (editing) await deleteMemePerson(editing.id);
          setConfirmDelete(false);
          setEditing(null);
          await reload();
        }}
      />
    </SafeAreaView>
  );
}

/** One unnamed group: some of its faces, and a field to name them. */
function GroupRow({
  group,
  library,
  onNamed,
}: {
  group: LocalMemeFaceGroup;
  library: LocalMemeLibrary;
  onNamed: () => Promise<void>;
}) {
  const { t } = useTranslation();
  const { colors } = useTheme();
  const [name, setName] = useState('');
  const query = foldForMatching(name);
  const matches = query
    ? library.people.filter((p) => foldForMatching(p.name).split(/\s+/).some((w) => w.startsWith(query))).slice(0, 5)
    : [];

  const save = async (value: string) => {
    if (!value.trim()) return;
    await nameMemeFaces(group.faces.map((f) => f.faceId), value.trim());
    setName('');
    await onNamed();
  };

  return (
    <View style={[styles.group, { borderColor: colors.border }]}>
      <ScrollView horizontal showsHorizontalScrollIndicator={false} contentContainerStyle={styles.row}>
        {group.faces.slice(0, 8).map((f) => (
          <FaceCrop key={f.faceId} itemId={f.itemId} faceId={f.faceId} size={64} />
        ))}
        {group.count > 8 ? <Text style={{ color: colors.textMuted }}>+{group.count - 8}</Text> : null}
      </ScrollView>
      <View style={styles.row}>
        <TextInput
          value={name}
          onChangeText={setName}
          placeholder={t('memes.faces.whoIsThis')}
          placeholderTextColor={colors.textSubtle}
          style={[styles.input, { color: colors.text, backgroundColor: colors.surface, borderColor: colors.border }]}
          autoCorrect={false}
          onSubmitEditing={() => void save(name)}
        />
        <Pressable
          onPress={() => void save(name)}
          disabled={!name.trim()}
          style={[styles.nameButton, { backgroundColor: name.trim() ? colors.accent : colors.surfaceActive }]}
        >
          <Text style={{ color: name.trim() ? colors.primaryText : colors.textMuted }}>{t('memes.faces.setName')}</Text>
        </Pressable>
      </View>
      {matches.length > 0 ? (
        <View style={styles.wrap}>
          {matches.map((p) => (
            <Chip key={p.id} label={p.name} iconName="person-outline" color={colors.info} onPress={() => void save(p.name)} />
          ))}
        </View>
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  fill: { flex: 1 },
  header: { flexDirection: 'row', alignItems: 'center', gap: 14, paddingHorizontal: 16, paddingVertical: 10 },
  title: { fontSize: 20, fontWeight: '700' },
  subtitle: { fontSize: 13 },
  empty: { alignItems: 'center', justifyContent: 'center', gap: 10, padding: 32 },
  emptyTitle: { fontSize: 18, fontWeight: '600' },
  body: { padding: 16, gap: 12, paddingBottom: 48 },
  section: { fontSize: 17, fontWeight: '600', marginTop: 8 },
  wrap: { flexDirection: 'row', flexWrap: 'wrap', gap: 12 },
  row: { flexDirection: 'row', alignItems: 'center', gap: 8 },
  card: { alignItems: 'center', gap: 6, padding: 10, borderRadius: 14, width: 120 },
  answer: { width: 40, height: 34, borderRadius: 10, alignItems: 'center', justifyContent: 'center' },
  group: { gap: 10, paddingVertical: 12, borderBottomWidth: StyleSheet.hairlineWidth },
  input: { flex: 1, borderWidth: 1, borderRadius: 10, paddingHorizontal: 12, paddingVertical: 8, fontSize: 16 },
  nameButton: { borderRadius: 10, paddingHorizontal: 14, paddingVertical: 10 },
  person: { alignItems: 'center', gap: 4, width: 88 },
  caption: { fontSize: 12 },
  scrim: { flex: 1, justifyContent: 'center', padding: 24 },
  editor: { borderRadius: 16, padding: 16, gap: 12 },
  editInput: { flex: 0 },
});
