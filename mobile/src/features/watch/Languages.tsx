import React, { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { StyleSheet, View } from 'react-native';

import { getLanguages, setLanguages } from '@/src/api';
import { AppText as Text, Chip } from '@/src/components';
import { useTheme } from '@/src/theme';

/**
 * The languages subtitles and audio are picked in, most preferred first
 * (`shared/watch/CONTRACT.md`, "The player"). A chosen one is tapped to drop it; another is
 * tapped to add it last.
 */
export function LanguagesSection() {
  const { t } = useTranslation();
  const { colors } = useTheme();
  const [chosen, setChosen] = useState<string[]>([]);
  const [offered, setOffered] = useState<string[]>([]);

  useEffect(() => {
    getLanguages()
      .then((l) => {
        setChosen(l.chosen);
        setOffered(l.offered);
      })
      .catch(() => undefined);
  }, []);

  const save = (next: string[]) => {
    setChosen(next);
    void setLanguages(next).catch(() => undefined);
  };
  const name = (code: string) => t(`player.languages.${code}`, { defaultValue: code });

  return (
    <View style={styles.gap}>
      <Text style={[styles.section, { color: colors.text }]}>{t('watch.languages.title')}</Text>
      <Text style={{ color: colors.textMuted }}>{t('watch.languages.hint')}</Text>
      <View style={styles.wrap}>
        {chosen.map((code, index) => (
          <Chip
            key={code}
            label={`${index + 1}. ${name(code)}`}
            color={colors.primary}
            active
            trailingIconName="close"
            onPress={() => save(chosen.filter((c) => c !== code))}
          />
        ))}
      </View>
      {chosen.length === 0 ? <Text style={{ color: colors.textMuted }}>{t('watch.languages.none')}</Text> : null}
      <Text style={{ color: colors.textMuted }}>{t('watch.languages.add')}</Text>
      <View style={styles.wrap}>
        {offered.filter((code) => !chosen.includes(code)).map((code) => (
          <Chip key={code} label={name(code)} color={colors.textMuted} size="sm" onPress={() => save([...chosen, code])} />
        ))}
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  gap: { gap: 10 },
  section: { fontSize: 17, fontWeight: '600', marginTop: 10 },
  wrap: { flexDirection: 'row', flexWrap: 'wrap', gap: 8 },
});
