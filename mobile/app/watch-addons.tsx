import { Ionicons } from '@expo/vector-icons';
import { Image } from 'expo-image';
import { useFocusEffect, useRouter } from 'expo-router';
import React, { useCallback, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { ActivityIndicator, Pressable, ScrollView, StyleSheet, Switch, TextInput, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import {
  CINEMETA,
  installAddon,
  listAddons,
  moveAddon,
  setAddonEnabled,
  uninstallAddon,
  type WatchAddon,
} from '@/src/api';
import { AppText as Text, ConfirmModal } from '@/src/components';
import { useTheme } from '@/src/theme';

/**
 * Installed add-ons: install one from its URL, turn it off, move it, remove it. An add-on's
 * URL can hold an account token, so after installing only its name and host are shown.
 */
export default function WatchAddonsScreen() {
  const { t } = useTranslation();
  const { colors } = useTheme();
  const router = useRouter();
  const [addons, setAddons] = useState<WatchAddon[]>([]);
  const [url, setUrl] = useState('');
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<{ text: string; error: boolean } | null>(null);
  const [removing, setRemoving] = useState<WatchAddon | null>(null);

  const reload = useCallback(async () => {
    setAddons(await listAddons().catch(() => []));
  }, []);

  useFocusEffect(
    useCallback(() => {
      void reload();
    }, [reload]),
  );

  const install = async (address: string) => {
    if (!address.trim()) return;
    setBusy(true);
    setMessage(null);
    const result = await installAddon(address.trim()).catch(() => null);
    setBusy(false);
    if (result?.success) {
      setUrl('');
      setMessage({ text: t('watch.addons.installed', { name: result.name }), error: false });
    } else {
      const code = result && !result.success ? result.code : 'WATCH_FAILED';
      setMessage({ text: t(`watch.errors.${code}`, { defaultValue: t('watch.installFailed', { code }) }), error: true });
    }
    await reload();
  };

  const hasCinemeta = addons.some((a) => a.host === 'v3-cinemeta.strem.io');

  return (
    <SafeAreaView style={[styles.fill, { backgroundColor: colors.background }]} edges={['top', 'left', 'right']}>
      <View style={styles.header}>
        <Pressable onPress={() => router.back()} hitSlop={10} accessibilityRole="button">
          <Ionicons name="arrow-back" size={24} color={colors.text} />
        </Pressable>
        <Text style={[styles.title, { color: colors.text }]}>{t('watch.addons.title')}</Text>
      </View>
      <ScrollView contentContainerStyle={styles.body} keyboardShouldPersistTaps="handled">
        <Text style={{ color: colors.textMuted }}>{t('watch.addons.hint')}</Text>
        <View style={styles.row}>
          <TextInput
            value={url}
            onChangeText={setUrl}
            placeholder={t('watch.addons.placeholder')}
            placeholderTextColor={colors.textSubtle}
            autoCapitalize="none"
            autoCorrect={false}
            keyboardType="url"
            onSubmitEditing={() => void install(url)}
            style={[styles.input, { color: colors.text, backgroundColor: colors.surface, borderColor: colors.border }]}
          />
          <Pressable
            onPress={() => void install(url)}
            disabled={busy || !url.trim()}
            style={[styles.button, { backgroundColor: url.trim() ? colors.accent : colors.surfaceActive }]}
          >
            {busy ? <ActivityIndicator color={colors.primaryText} /> : (
              <Text style={{ color: url.trim() ? colors.primaryText : colors.textMuted }}>{t('watch.addons.install')}</Text>
            )}
          </Pressable>
        </View>
        {!hasCinemeta ? (
          <Pressable onPress={() => void install(CINEMETA)} disabled={busy}>
            <Text style={{ color: colors.accent }}>{t('watch.installCinemeta')}</Text>
          </Pressable>
        ) : null}
        {message ? <Text style={{ color: message.error ? colors.error : colors.success }}>{message.text}</Text> : null}

        {addons.map((addon, index) => (
          <View key={addon.key} style={[styles.addon, { backgroundColor: colors.surface }]}>
            <View style={styles.row}>
              {addon.logo ? (
                <Image source={{ uri: addon.logo }} style={styles.logo} contentFit="contain" />
              ) : (
                <Ionicons name="extension-puzzle-outline" size={28} color={colors.textMuted} />
              )}
              <View style={styles.fill}>
                <Text style={{ color: colors.text, fontWeight: '600' }} numberOfLines={1}>{addon.name}</Text>
                <Text style={[styles.small, { color: colors.textMuted }]} numberOfLines={1}>
                  {[addon.host, addon.version ? `v${addon.version}` : null].filter(Boolean).join(' · ')}
                </Text>
              </View>
              <Switch
                value={addon.enabled}
                onValueChange={async (value) => {
                  await setAddonEnabled(addon.key, value);
                  await reload();
                }}
                trackColor={{ true: colors.accent }}
              />
            </View>
            {addon.description ? (
              <Text style={[styles.small, { color: colors.textMuted }]} numberOfLines={3}>{addon.description}</Text>
            ) : null}
            <Text style={[styles.small, { color: colors.textSubtle }]}>
              {[addon.types.join(', '), addon.resources.join(', ')].filter(Boolean).join(' — ')}
            </Text>
            <View style={styles.actions}>
              <Pressable
                disabled={index === 0}
                onPress={async () => {
                  await moveAddon(addon.key, index - 1);
                  await reload();
                }}
                hitSlop={8}
                accessibilityLabel={t('watch.addons.up')}
              >
                <Ionicons name="arrow-up" size={20} color={index === 0 ? colors.textSubtle : colors.text} />
              </Pressable>
              <Pressable
                disabled={index === addons.length - 1}
                onPress={async () => {
                  await moveAddon(addon.key, index + 1);
                  await reload();
                }}
                hitSlop={8}
                accessibilityLabel={t('watch.addons.down')}
              >
                <Ionicons name="arrow-down" size={20} color={index === addons.length - 1 ? colors.textSubtle : colors.text} />
              </Pressable>
              <View style={styles.fill} />
              <Pressable onPress={() => setRemoving(addon)} hitSlop={8} accessibilityLabel={t('watch.addons.remove')}>
                <Ionicons name="trash-outline" size={20} color={colors.error} />
              </Pressable>
            </View>
          </View>
        ))}
      </ScrollView>

      <ConfirmModal
        visible={removing != null}
        config={removing ? {
          title: t('watch.addons.removeTitle', { name: removing.name }),
          message: t('watch.addons.removeBody'),
          confirm: t('watch.addons.remove'),
          destructive: true,
        } : null}
        onCancel={() => setRemoving(null)}
        onConfirm={async () => {
          if (removing) await uninstallAddon(removing.key);
          setRemoving(null);
          await reload();
        }}
      />
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  fill: { flex: 1 },
  header: { flexDirection: 'row', alignItems: 'center', gap: 14, paddingHorizontal: 16, paddingVertical: 10 },
  title: { fontSize: 20, fontWeight: '700' },
  body: { padding: 16, gap: 12, paddingBottom: 48 },
  row: { flexDirection: 'row', alignItems: 'center', gap: 10 },
  input: { flex: 1, borderWidth: 1, borderRadius: 10, paddingHorizontal: 12, paddingVertical: 10, fontSize: 15 },
  button: { borderRadius: 10, paddingHorizontal: 16, paddingVertical: 11 },
  addon: { borderRadius: 12, padding: 12, gap: 8 },
  logo: { width: 32, height: 32, borderRadius: 6 },
  small: { fontSize: 12 },
  actions: { flexDirection: 'row', alignItems: 'center', gap: 18, paddingTop: 4 },
});
