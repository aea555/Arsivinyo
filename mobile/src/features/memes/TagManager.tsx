import { Ionicons } from '@expo/vector-icons';
import React, { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { FlatList, Modal, Pressable, StyleSheet, Switch, TextInput, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import {
  deleteMemeTag,
  MEME_FACETS,
  renameMemeTag,
  setMemeAskForTags,
  setMemeTagFacets,
  type LocalMemeLibrary,
  type LocalMemeTag,
} from '@/src/api';
import { AppText as Text, Chip, ConfirmModal } from '@/src/components';
import { useTheme } from '@/src/theme';

interface Props {
  library: LocalMemeLibrary;
  onClose: () => void;
  onChanged: () => Promise<void> | void;
}

/** Every tag: its facets, its name, or gone. And whether a download asks for tags. */
export function TagManager({ library, onClose, onChanged }: Props) {
  const { t } = useTranslation();
  const { colors } = useTheme();
  const [renaming, setRenaming] = useState<string | null>(null);
  const [name, setName] = useState('');
  const [deleting, setDeleting] = useState<LocalMemeTag | null>(null);

  const tags = [...library.tags].sort((a, b) => a.name.localeCompare(b.name, 'tr'));

  const toggleFacet = async (tag: LocalMemeTag, facet: (typeof MEME_FACETS)[number]) => {
    const next = tag.facets.includes(facet) ? tag.facets.filter((f) => f !== facet) : [...tag.facets, facet];
    await setMemeTagFacets(tag.id, next);
    await onChanged();
  };

  return (
    <Modal visible animationType="slide" onRequestClose={onClose}>
      <SafeAreaView style={[styles.fill, { backgroundColor: colors.background }]}>
        <View style={[styles.header, { borderBottomColor: colors.border }]}>
          <Pressable onPress={onClose} hitSlop={10} accessibilityRole="button">
            <Ionicons name="close" size={24} color={colors.text} />
          </Pressable>
          <Text style={[styles.title, { color: colors.text }]}>{t('memes.manager.title')}</Text>
        </View>

        <View style={[styles.row, { borderBottomColor: colors.border }]}>
          <View style={styles.fill}>
            <Text style={{ color: colors.text }}>{t('memes.manager.askForTags')}</Text>
            <Text style={[styles.hint, { color: colors.textMuted }]}>{t('memes.manager.askForTagsHint')}</Text>
          </View>
          <Switch
            value={library.askForTags}
            onValueChange={async (value) => {
              await setMemeAskForTags(value);
              await onChanged();
            }}
            trackColor={{ true: colors.accent }}
          />
        </View>

        <Text style={[styles.hint, styles.padded, { color: colors.textMuted }]}>{t('memes.manager.facetsHint')}</Text>

        <FlatList
          data={tags}
          keyExtractor={(tag) => tag.id}
          ListEmptyComponent={
            <Text style={[styles.padded, { color: colors.textMuted }]}>{t('memes.manager.empty')}</Text>
          }
          renderItem={({ item: tag }) => (
            <View style={[styles.tag, { borderBottomColor: colors.border }]}>
              <View style={styles.tagTop}>
                {renaming === tag.id ? (
                  <TextInput
                    value={name}
                    onChangeText={setName}
                    autoFocus
                    style={[styles.input, { color: colors.text, borderColor: colors.border }]}
                    onSubmitEditing={async () => {
                      if (name.trim()) await renameMemeTag(tag.id, name.trim());
                      setRenaming(null);
                      await onChanged();
                    }}
                    onBlur={() => setRenaming(null)}
                  />
                ) : (
                  <Text style={[styles.tagName, { color: colors.text }]}>{tag.name}</Text>
                )}
                <Pressable
                  hitSlop={8}
                  accessibilityLabel={t('memes.manager.rename')}
                  onPress={() => {
                    setRenaming(tag.id);
                    setName(tag.name);
                  }}
                >
                  <Ionicons name="create-outline" size={20} color={colors.textMuted} />
                </Pressable>
                <Pressable hitSlop={8} accessibilityLabel={t('memes.manager.delete')} onPress={() => setDeleting(tag)}>
                  <Ionicons name="trash-outline" size={20} color={colors.error} />
                </Pressable>
              </View>
              <View style={styles.wrap}>
                {MEME_FACETS.map((facet) => (
                  <Chip
                    key={facet}
                    size="sm"
                    label={t(`memes.facet.${facet}`)}
                    active={tag.facets.includes(facet)}
                    color={colors.warning}
                    onPress={() => void toggleFacet(tag, facet)}
                  />
                ))}
              </View>
            </View>
          )}
        />

        <ConfirmModal
          visible={deleting != null}
          config={deleting ? {
            title: t('memes.manager.deleteTitle', { name: deleting.name }),
            message: t('memes.manager.deleteBody'),
            confirm: t('memes.manager.delete'),
            destructive: true,
          } : null}
          onCancel={() => setDeleting(null)}
          onConfirm={async () => {
            if (deleting) await deleteMemeTag(deleting.id);
            setDeleting(null);
            await onChanged();
          }}
        />
      </SafeAreaView>
    </Modal>
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
  title: { fontSize: 17, fontWeight: '600' },
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
    padding: 16,
    borderBottomWidth: StyleSheet.hairlineWidth,
  },
  hint: { fontSize: 13, marginTop: 2 },
  padded: { paddingHorizontal: 16, paddingVertical: 10 },
  tag: { paddingHorizontal: 16, paddingVertical: 12, gap: 8, borderBottomWidth: StyleSheet.hairlineWidth },
  tagTop: { flexDirection: 'row', alignItems: 'center', gap: 14 },
  tagName: { flex: 1, fontSize: 16 },
  input: { flex: 1, borderWidth: 1, borderRadius: 8, paddingHorizontal: 10, paddingVertical: 6, fontSize: 16 },
  wrap: { flexDirection: 'row', flexWrap: 'wrap', gap: 6 },
});
