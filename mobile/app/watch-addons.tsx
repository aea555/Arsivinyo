import { Ionicons } from '@expo/vector-icons';
import { Image } from 'expo-image';
import { useFocusEffect, useLocalSearchParams, useRouter } from 'expo-router';
import React, { useCallback, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { ActivityIndicator, Linking, Pressable, ScrollView, StyleSheet, Switch, TextInput, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import {
  CINEMETA,
  getOffers,
  installAddon,
  listAddons,
  listOfferLists,
  moveAddon,
  setAddonEnabled,
  uninstallAddon,
  type WatchAddon,
  type WatchOffer,
  type WatchRow,
} from '@/src/api';
import { AppText as Text, Chip, ConfirmModal } from '@/src/components';
import { LanguagesSection } from '@/src/features/watch/Languages';
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
  const [lists, setLists] = useState<WatchRow[]>([]);
  const [list, setList] = useState<WatchRow | null>(null);
  const [offers, setOffers] = useState<WatchOffer[] | null>(null);
  const [offersFailed, setOffersFailed] = useState<string | null>(null);
  const [filter, setFilter] = useState('');
  // A stremio:// link opened from a browser, usually a configure page's Install button.
  const params = useLocalSearchParams<{ install?: string }>();
  const [offered, setOffered] = useState<string | null>(null);

  const reload = useCallback(async () => {
    setAddons(await listAddons().catch(() => []));
    const next = await listOfferLists().catch(() => []);
    setLists(next);
    setList((current) => current ?? next[0] ?? null);
  }, []);

  useEffect(() => {
    if (params.install) setOffered(String(params.install));
  }, [params.install]);

  useEffect(() => {
    if (!list) return;
    let live = true;
    setOffers(null);
    setOffersFailed(null);
    getOffers(list)
      .then((result) => {
        if (!live) return;
        if (result.success) setOffers(result.offers);
        else setOffersFailed(result.code);
      })
      .catch(() => live && setOffersFailed('WATCH_FAILED'));
    return () => {
      live = false;
    };
  }, [list, addons]);

  const shown = (offers ?? []).filter((o) => {
    const q = filter.trim().toLocaleLowerCase('tr');
    return !q || o.name.toLocaleLowerCase('tr').includes(q) || (o.description ?? '').toLocaleLowerCase('tr').includes(q);
  });

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

        <Text style={[styles.section, { color: colors.text }]}>{t('watch.addons.installedTitle')}</Text>
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

        <LanguagesSection />

        {lists.length > 0 ? (
          <>
            <Text style={[styles.section, { color: colors.text }]}>{t('watch.addons.discover')}</Text>
            <Text style={{ color: colors.textMuted }}>{t('watch.addons.discoverHint')}</Text>
            <View style={styles.wrap}>
              {lists.map((l) => (
                <Chip
                  key={`${l.addonKey}/${l.id}`}
                  label={`${l.name} · ${l.addonName}`}
                  active={list?.addonKey === l.addonKey && list?.id === l.id}
                  color={colors.accent}
                  onPress={() => setList(l)}
                />
              ))}
            </View>
            <TextInput
              value={filter}
              onChangeText={setFilter}
              placeholder={t('watch.addons.filter')}
              placeholderTextColor={colors.textSubtle}
              autoCorrect={false}
              style={[styles.input, styles.filter, { color: colors.text, backgroundColor: colors.surface, borderColor: colors.border }]}
            />
            {offersFailed ? <Text style={{ color: colors.textMuted }}>{t('watch.addons.listFailed')}</Text> : null}
            {offers === null && !offersFailed ? <ActivityIndicator color={colors.accent} /> : null}
            {shown.map((offer) => (
              <View key={offer.url} style={[styles.addon, { backgroundColor: colors.surface }]}>
                <View style={styles.row}>
                  {offer.logo ? (
                    <Image source={{ uri: offer.logo }} style={styles.logo} contentFit="contain" />
                  ) : (
                    <Ionicons name="extension-puzzle-outline" size={28} color={colors.textMuted} />
                  )}
                  <View style={styles.fill}>
                    <Text style={{ color: colors.text, fontWeight: '600' }} numberOfLines={1}>{offer.name}</Text>
                    <Text style={[styles.small, { color: colors.textMuted }]} numberOfLines={1}>
                      {offer.resources.map((r) => t(`watch.addons.resource.${r}`, { defaultValue: r })).join(', ')}
                    </Text>
                  </View>
                </View>
                {offer.description ? (
                  <Text style={[styles.small, { color: colors.textMuted }]} numberOfLines={4}>{offer.description}</Text>
                ) : null}
                <View style={styles.actions}>
                  {offer.installed ? (
                    <Text style={[styles.small, { color: colors.success }]}>{t('watch.addons.alreadyInstalled')}</Text>
                  ) : offer.required ? (
                    <Text style={[styles.small, { color: colors.textMuted }]}>{t('watch.addons.configureFirst')}</Text>
                  ) : (
                    <Pressable
                      onPress={() => void install(offer.url)}
                      disabled={busy}
                      style={[styles.smallButton, { backgroundColor: colors.accent }]}
                    >
                      <Text style={{ color: colors.primaryText }}>{t('watch.addons.install')}</Text>
                    </Pressable>
                  )}
                  {offer.configureUrl ? (
                    <Pressable
                      onPress={() => void Linking.openURL(offer.configureUrl!)}
                      style={[styles.smallButton, { backgroundColor: colors.surfaceActive }]}
                    >
                      <Text style={{ color: colors.text }}>{t('watch.addons.configure')}</Text>
                    </Pressable>
                  ) : null}
                </View>
              </View>
            ))}
          </>
        ) : null}
      </ScrollView>

      <ConfirmModal
        visible={offered != null}
        config={offered ? {
          title: t('watch.addons.linkTitle'),
          message: t('watch.addons.linkBody', { host: hostOf(offered) }),
          confirm: t('watch.addons.install'),
          destructive: false,
        } : null}
        onCancel={() => {
          setOffered(null);
          router.setParams({ install: undefined });
        }}
        onConfirm={async () => {
          const link = offered;
          setOffered(null);
          router.setParams({ install: undefined });
          if (link) await install(link);
        }}
      />

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

/** Only the host of a link that may carry an account key: enough to recognise it by. */
function hostOf(link: string): string {
  const match = /^[a-z]+:\/\/([^/?#]+)/i.exec(link.trim());
  return match ? match[1] : link.slice(0, 40);
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
  section: { fontSize: 17, fontWeight: '600', marginTop: 10 },
  wrap: { flexDirection: 'row', flexWrap: 'wrap', gap: 8 },
  filter: { flex: 0 },
  smallButton: { borderRadius: 8, paddingHorizontal: 14, paddingVertical: 7 },
});
