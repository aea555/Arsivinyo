import { Ionicons } from '@expo/vector-icons';
import { Stack } from 'expo-router';
import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
    ActivityIndicator,
    KeyboardAvoidingView,
    Platform,
    Pressable,
    ScrollView,
    StyleSheet,
    View,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import {
    addBackupProgressListener,
    createBackup,
    generatePassphrase,
    getBackupJobState,
    previewBackup,
    restoreBackup,
} from '@/src/api/backup';
import { AppText as Text, AppTextInput as TextInput, ConfirmModal } from '@/src/components';
import {
    BACKUP_SECTIONS,
    DEFAULT_SEPARATOR,
    MAX_PASSPHRASE_WORDS,
    MIN_PASSPHRASE_WORDS,
    MIN_PASSWORD_LENGTH,
    PASSPHRASE_SEPARATORS,
    type BackupSectionId,
    type PassphraseSeparator,
    type SecretKind,
    type SecretProblem,
    type StrengthLevel,
    estimatePassphraseStrength,
    estimatePasswordStrength,
    formatBytes,
    joinPassphrase,
    validatePassphrase,
    validatePassword,
} from '@/src/features/backup/core';
import type { LocalBackupJob, LocalBackupPreview } from '@/src/native/localDownloader';
import { copyToClipboard } from '@/src/services/clipboard';
import { useTheme } from '@/src/theme';

type Mode = 'export' | 'import';

const SECTION_ICONS: Record<BackupSectionId, React.ComponentProps<typeof Ionicons>['name']> = {
    vault: 'lock-closed-outline',
    music: 'musical-notes-outline',
    memes: 'happy-outline',
    settings: 'options-outline',
    cookies: 'key-outline',
};

/**
 * Whole-app backup and restore.
 *
 * The screen is deliberately linear — pick what, pick a secret, go — because both
 * directions are destructive-adjacent and a user should be able to read the whole thing
 * before committing. The import side shows what a file contains *before* asking for a
 * secret, which the plaintext header makes possible.
 */
export default function BackupScreen() {
    const { t } = useTranslation();
    const { colors } = useTheme();

    const [mode, setMode] = useState<Mode>('export');
    const [sections, setSections] = useState<Set<BackupSectionId>>(new Set(BACKUP_SECTIONS));
    const [secretKind, setSecretKind] = useState<SecretKind>('passphrase');

    const [password, setPassword] = useState('');
    const [confirmPassword, setConfirmPassword] = useState('');
    const [revealSecret, setRevealSecret] = useState(false);

    const [words, setWords] = useState<string[]>(Array(6).fill(''));
    const [separator, setSeparator] = useState<PassphraseSeparator>(DEFAULT_SEPARATOR);
    const [generated, setGenerated] = useState(false);

    const [busy, setBusy] = useState(false);
    const [status, setStatus] = useState<string | null>(null);
    const [preview, setPreview] = useState<LocalBackupPreview | null>(null);
    const [report, setReport] = useState<string | null>(null);
    const [confirmRestore, setConfirmRestore] = useState(false);

    /**
     * Progress of a job running in the module, which may have started before this screen
     * was opened. A backup holds a foreground service, so it outlives the screen; without
     * this, reopening during a 20 GB export would show an idle form and let the user start
     * a second one.
     */
    const [job, setJob] = useState<LocalBackupJob | null>(null);

    useEffect(() => {
        let cancelled = false;
        getBackupJobState()
            .then((state) => {
                if (cancelled) return;
                setJob(state.active);
                if (!state.active && state.last) {
                    setStatus(
                        t(state.last.success ? 'backup.jobFinished' : 'backup.jobFailed', {
                            summary: state.last.summary ?? '',
                        }),
                    );
                }
            })
            .catch(() => undefined);

        const subscription = addBackupProgressListener((state) => {
            setJob(state.active);
            if (!state.active && state.last) {
                setStatus(
                    t(state.last.success ? 'backup.jobFinished' : 'backup.jobFailed', {
                        summary: state.last.summary ?? '',
                    }),
                );
            }
        });
        return () => {
            cancelled = true;
            subscription.remove();
        };
    }, [t]);

    const toggleSection = useCallback((id: BackupSectionId) => {
        setSections((current) => {
            const next = new Set(current);
            if (next.has(id)) next.delete(id);
            else next.add(id);
            return next;
        });
    }, []);

    // ---------------------------------------------------------------- secret state

    const secretValue = useMemo(
        () => (secretKind === 'password' ? password : joinPassphrase(words, separator)),
        [secretKind, password, words, separator],
    );

    const strength = useMemo(
        () =>
            secretKind === 'password'
                ? estimatePasswordStrength(password)
                : estimatePassphraseStrength(words, generated),
        [secretKind, password, words, generated],
    );

    const problems = useMemo<SecretProblem[]>(() => {
        if (secretKind === 'password') {
            const base = validatePassword(password).problems;
            // Only on export: a mistyped secret on a file you cannot re-read is unrecoverable.
            if (mode === 'export' && confirmPassword.length > 0 && confirmPassword !== password) {
                return [...base, { code: 'mismatch' }];
            }
            return base;
        }
        return validatePassphrase(words, separator).problems;
    }, [secretKind, password, confirmPassword, words, separator, mode]);

    const secretReady =
        problems.length === 0 &&
        secretValue.length > 0 &&
        (mode === 'import' || secretKind === 'passphrase' || confirmPassword === password);

    /**
     * True when the method that is *not* selected also has something typed in it.
     *
     * Only the selected one is ever used, but that is invisible while the other tab is
     * hidden. On export the consequence is unrecoverable: a file protected by a password
     * you did not mean to use cannot be opened by the passphrase you wrote down.
     */
    const unusedMethodHasContent = useMemo(
        () =>
            secretKind === 'password'
                ? words.some((word) => word.trim().length > 0)
                : password.length > 0,
        [secretKind, words, password],
    );

    const describeProblem = useCallback(
        (problem: SecretProblem) => t(`backup.problem.${problem.code}`, problem.values ?? {}),
        [t],
    );

    const [copied, setCopied] = useState(false);
    const copiedTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

    // Clear the pending reset if the screen goes away mid-countdown.
    useEffect(() => () => {
        if (copiedTimer.current) clearTimeout(copiedTimer.current);
    }, []);

    const handleCopySecret = useCallback(async () => {
        if (secretValue.length === 0) return;
        const ok = await copyToClipboard(secretValue);
        if (!ok) {
            setStatus(t('backup.copyFailed'));
            return;
        }
        setCopied(true);
        setStatus(t('backup.copiedHint'));
        if (copiedTimer.current) clearTimeout(copiedTimer.current);
        copiedTimer.current = setTimeout(() => setCopied(false), 2500);
    }, [secretValue, t]);

    const handleGenerate = useCallback(async () => {
        setBusy(true);
        try {
            const next = await generatePassphrase(Math.max(words.length, MIN_PASSPHRASE_WORDS));
            setWords(next);
            setGenerated(true);
            setRevealSecret(true);
            setStatus(t('backup.generatedHint'));
        } catch {
            setStatus(t('backup.generateFailed'));
        } finally {
            setBusy(false);
        }
    }, [words.length, t]);

    const setWordAt = useCallback((index: number, value: string) => {
        // Typing over a generated phrase makes it self-chosen again, and the strength meter
        // must stop claiming the entropy of a random draw.
        setGenerated(false);
        setWords((current) => current.map((word, i) => (i === index ? value : word)));
    }, []);

    const changeWordCount = useCallback((delta: number) => {
        setGenerated(false);
        setWords((current) => {
            const next = current.length + delta;
            if (next < MIN_PASSPHRASE_WORDS || next > MAX_PASSPHRASE_WORDS) return current;
            return delta > 0 ? [...current, ''] : current.slice(0, next);
        });
    }, []);

    // ---------------------------------------------------------------- actions

    const secrets = useMemo(
        () => [{ slotId: 'default', secret: secretValue, kind: secretKind }],
        [secretValue, secretKind],
    );

    const handleExport = useCallback(async () => {
        setBusy(true);
        setStatus(t('backup.working'));
        setReport(null);
        try {
            const result = await createBackup({
                sections: [...sections],
                secrets,
                suggestedName: undefined,
            });
            if (!result.success) {
                setStatus(
                    result.code === 'BACKUP_PICK_CANCELLED'
                        ? t('backup.cancelled')
                        : t('backup.exportFailed', { message: result.message ?? result.code ?? '' }),
                );
                return;
            }
            const total = (result.sections ?? []).reduce((sum, s) => sum + s.itemCount, 0);
            const failed = result.failed ?? [];
            setStatus(t('backup.exportDone', { count: total - failed.length }));
            // Not a silent partial success: an item that could not be read is named, so the
            // user knows the backup is missing it before they rely on the file.
            if (failed.length > 0) {
                setReport(
                    t('backup.exportSkipped', { count: failed.length }) +
                        '\n' +
                        failed.map((f) => f.name).join(', '),
                );
            }
        } catch (error) {
            setStatus(t('backup.exportFailed', { message: (error as Error).message }));
        } finally {
            setBusy(false);
        }
    }, [sections, secrets, t]);

    const handlePickForImport = useCallback(async () => {
        setBusy(true);
        setStatus(null);
        setReport(null);
        try {
            const result = await previewBackup();
            if (!result.success) {
                setPreview(null);
                setStatus(
                    result.code === 'BACKUP_PICK_CANCELLED'
                        ? t('backup.cancelled')
                        : t('backup.previewFailed', { message: result.message ?? '' }),
                );
                return;
            }
            setPreview(result);
            // Default to everything the file actually holds.
            setSections(new Set((result.sections ?? []).map((s) => s.id)));
            setSecretKind(result.keySlots?.[0]?.secretKind ?? 'passphrase');
        } catch (error) {
            setStatus(t('backup.previewFailed', { message: (error as Error).message }));
        } finally {
            setBusy(false);
        }
    }, [t]);

    const handleRestore = useCallback(async () => {
        if (!preview?.uri) return;
        setConfirmRestore(false);
        setBusy(true);
        setStatus(t('backup.working'));
        try {
            const result = await restoreBackup({
                uri: preview.uri,
                sections: [...sections],
                secrets,
            });
            if (!result.success) {
                setStatus(
                    result.code === 'BACKUP_WRONG_SECRET'
                        ? t('backup.wrongSecret')
                        : t('backup.restoreFailed', { message: result.message ?? '' }),
                );
                return;
            }
            setStatus(t('backup.restoreDone'));
            // "Already here" merges duplicates-by-content with cookie profiles left
            // alone; from the user's side both mean "nothing was replaced".
            const lines = [
                t('backup.restoreReport', {
                    restored: result.restored ?? 0,
                    skipped: (result.skippedDuplicates ?? 0) + (result.skippedExisting ?? 0),
                    failed: result.failed ?? 0,
                }),
            ];
            if ((result.applied ?? 0) > 0) {
                lines.push(t('backup.restoreReportApplied'));
            }
            setReport(lines.join('\n'));
        } catch (error) {
            setStatus(t('backup.restoreFailed', { message: (error as Error).message }));
        } finally {
            setBusy(false);
        }
    }, [preview, sections, secrets, t]);

    // ---------------------------------------------------------------- render

    const strengthColor: Record<StrengthLevel, string> = {
        weak: colors.error,
        fair: colors.warning,
        strong: colors.accent,
        excellent: colors.accent,
    };
    const strengthWidth: Record<StrengthLevel, string> = {
        weak: '25%',
        fair: '50%',
        strong: '75%',
        excellent: '100%',
    };

    const canAct =
        secretReady &&
        sections.size > 0 &&
        !busy &&
        job == null &&
        (mode === 'export' || preview?.uri != null);

    return (
        <SafeAreaView style={[styles.screen, { backgroundColor: colors.background }]} edges={['bottom']}>
            <Stack.Screen options={{ title: t('backup.title') }} />
            {/* `undefined` on Android means no avoidance at all, which buried the word
                grid, the separator picker and the action button behind the keyboard.
                'height' shrinks the container so the footer rides above the keyboard and
                the ScrollView can reach the rest — the same behaviour the music library's
                preset editor uses. */}
            <KeyboardAvoidingView
                style={styles.flex}
                behavior={Platform.OS === 'ios' ? 'padding' : 'height'}
            >
                <ScrollView
                    contentContainerStyle={styles.content}
                    keyboardShouldPersistTaps="handled"
                    keyboardDismissMode="interactive"
                    automaticallyAdjustKeyboardInsets
                >
                    {/* Mode */}
                    <View style={[styles.segment, { backgroundColor: colors.surfaceHover }]}>
                        {(['export', 'import'] as const).map((value) => {
                            const active = mode === value;
                            return (
                                <Pressable
                                    key={value}
                                    accessibilityRole="radio"
                                    accessibilityState={{ checked: active }}
                                    onPress={() => {
                                        setMode(value);
                                        setStatus(null);
                                        setReport(null);
                                        setPreview(null);
                                        setSections(new Set(BACKUP_SECTIONS));
                                    }}
                                    style={[
                                        styles.segmentButton,
                                        active && { backgroundColor: colors.primary },
                                    ]}
                                >
                                    <Text
                                        style={[
                                            styles.segmentText,
                                            { color: active ? colors.primaryText : colors.textMuted },
                                        ]}
                                    >
                                        {t(`backup.mode.${value}`)}
                                    </Text>
                                </Pressable>
                            );
                        })}
                    </View>

                    <Text style={[styles.blurb, { color: colors.textMuted }]}>
                        {t(mode === 'export' ? 'backup.exportBlurb' : 'backup.importBlurb')}
                    </Text>

                    {/* Import: pick a file first, so its contents can be described. */}
                    {mode === 'import' && (
                        <Pressable
                            onPress={handlePickForImport}
                            disabled={busy}
                            style={[styles.pickButton, { borderColor: colors.border }]}
                        >
                            <Ionicons name="folder-open-outline" size={18} color={colors.accent} />
                            <Text style={[styles.pickText, { color: colors.text }]}>
                                {preview?.uri ? t('backup.pickAnother') : t('backup.pickFile')}
                            </Text>
                        </Pressable>
                    )}

                    {mode === 'import' && preview?.success && (
                        <Text style={[styles.previewMeta, { color: colors.textMuted }]}>
                            {t('backup.previewMeta', {
                                version: preview.appVersion ?? '?',
                                date: preview.createdAt
                                    ? new Date(preview.createdAt).toLocaleDateString()
                                    : '?',
                            })}
                        </Text>
                    )}

                    {/* Sections */}
                    {(mode === 'export' || preview?.success) && (
                        <View style={styles.group}>
                            <Text style={[styles.groupTitle, { color: colors.textMuted }]}>
                                {t('backup.sectionsTitle')}
                            </Text>
                            {BACKUP_SECTIONS.map((id) => {
                                const summary = preview?.sections?.find((s) => s.id === id);
                                const availableForImport = mode === 'export' || summary != null;
                                if (!availableForImport) return null;
                                const selected = sections.has(id);
                                return (
                                    <Pressable
                                        key={id}
                                        accessibilityRole="checkbox"
                                        accessibilityState={{ checked: selected }}
                                        onPress={() => toggleSection(id)}
                                        style={[
                                            styles.sectionRow,
                                            {
                                                backgroundColor: colors.surface,
                                                borderColor: selected ? colors.accent : colors.border,
                                            },
                                        ]}
                                    >
                                        <Ionicons
                                            name={SECTION_ICONS[id]}
                                            size={18}
                                            color={selected ? colors.accent : colors.textMuted}
                                        />
                                        <View style={styles.flex}>
                                            <Text style={[styles.sectionName, { color: colors.text }]}>
                                                {t(`backup.section.${id}`)}
                                            </Text>
                                            <Text style={[styles.sectionHint, { color: colors.textMuted }]}>
                                                {summary
                                                    ? t('backup.sectionSummary', {
                                                          count: summary.itemCount,
                                                          size: formatBytes(summary.plaintextBytes),
                                                      })
                                                    : t(`backup.sectionHint.${id}`)}
                                            </Text>
                                        </View>
                                        <Ionicons
                                            name={selected ? 'checkbox' : 'square-outline'}
                                            size={20}
                                            color={selected ? colors.accent : colors.textMuted}
                                        />
                                    </Pressable>
                                );
                            })}
                        </View>
                    )}

                    {/* Secret */}
                    {(mode === 'export' || preview?.success) && (
                        <View style={styles.group}>
                            <Text style={[styles.groupTitle, { color: colors.textMuted }]}>
                                {t(mode === 'export' ? 'backup.secretTitle' : 'backup.secretTitleImport')}
                            </Text>

                            <View style={[styles.segment, { backgroundColor: colors.surfaceHover }]}>
                                {(['passphrase', 'password'] as const).map((kind) => {
                                    const active = secretKind === kind;
                                    return (
                                        <Pressable
                                            key={kind}
                                            accessibilityRole="radio"
                                            accessibilityState={{ checked: active }}
                                            onPress={() => setSecretKind(kind)}
                                            style={[
                                                styles.segmentButton,
                                                active && { backgroundColor: colors.primary },
                                            ]}
                                        >
                                            <Text
                                                style={[
                                                    styles.segmentText,
                                                    { color: active ? colors.primaryText : colors.textMuted },
                                                ]}
                                            >
                                                {t(`backup.kind.${kind}`)}
                                            </Text>
                                        </Pressable>
                                    );
                                })}
                            </View>

                            {secretKind === 'password' ? (
                                <>
                                    <TextInput
                                        value={password}
                                        onChangeText={setPassword}
                                        secureTextEntry={!revealSecret}
                                        autoCapitalize="none"
                                        autoCorrect={false}
                                        placeholder={t('backup.passwordPlaceholder', {
                                            min: MIN_PASSWORD_LENGTH,
                                        })}
                                        placeholderTextColor={colors.textMuted}
                                        style={[
                                            styles.input,
                                            { backgroundColor: colors.surface, color: colors.text, borderColor: colors.border },
                                        ]}
                                    />
                                    {mode === 'export' && (
                                        <TextInput
                                            value={confirmPassword}
                                            onChangeText={setConfirmPassword}
                                            secureTextEntry={!revealSecret}
                                            autoCapitalize="none"
                                            autoCorrect={false}
                                            placeholder={t('backup.confirmPlaceholder')}
                                            placeholderTextColor={colors.textMuted}
                                            style={[
                                                styles.input,
                                                { backgroundColor: colors.surface, color: colors.text, borderColor: colors.border },
                                            ]}
                                        />
                                    )}
                                </>
                            ) : (
                                <>
                                    <View style={styles.wordGrid}>
                                        {words.map((word, index) => (
                                            <TextInput
                                                key={index}
                                                value={word}
                                                onChangeText={(value) => setWordAt(index, value)}
                                                secureTextEntry={!revealSecret}
                                                autoCapitalize="none"
                                                autoCorrect={false}
                                                placeholder={`${index + 1}`}
                                                placeholderTextColor={colors.textMuted}
                                                style={[
                                                    styles.wordInput,
                                                    { backgroundColor: colors.surface, color: colors.text, borderColor: colors.border },
                                                ]}
                                            />
                                        ))}
                                    </View>

                                    <View style={styles.wordControls}>
                                        <Pressable
                                            onPress={() => changeWordCount(-1)}
                                            disabled={words.length <= MIN_PASSPHRASE_WORDS}
                                            style={[styles.smallButton, { borderColor: colors.border }]}
                                        >
                                            <Ionicons name="remove" size={16} color={colors.text} />
                                        </Pressable>
                                        <Text style={[styles.wordCount, { color: colors.textMuted }]}>
                                            {t('backup.wordCount', { count: words.length })}
                                        </Text>
                                        <Pressable
                                            onPress={() => changeWordCount(1)}
                                            disabled={words.length >= MAX_PASSPHRASE_WORDS}
                                            style={[styles.smallButton, { borderColor: colors.border }]}
                                        >
                                            <Ionicons name="add" size={16} color={colors.text} />
                                        </Pressable>
                                        {mode === 'export' && (
                                            <Pressable
                                                onPress={handleGenerate}
                                                disabled={busy}
                                                style={[styles.smallButton, styles.generateButton, { borderColor: colors.accent }]}
                                            >
                                                <Ionicons name="dice-outline" size={16} color={colors.accent} />
                                                <Text style={[styles.generateText, { color: colors.accent }]}>
                                                    {t('backup.generate')}
                                                </Text>
                                            </Pressable>
                                        )}
                                    </View>

                                    <View style={styles.separatorRow}>
                                        <Text style={[styles.separatorLabel, { color: colors.textMuted }]}>
                                            {t('backup.separator')}
                                        </Text>
                                        {PASSPHRASE_SEPARATORS.map((value) => {
                                            const active = separator === value;
                                            return (
                                                <Pressable
                                                    key={value}
                                                    onPress={() => setSeparator(value)}
                                                    style={[
                                                        styles.separatorChip,
                                                        {
                                                            backgroundColor: active ? colors.primary : colors.surface,
                                                            borderColor: active ? colors.primary : colors.border,
                                                        },
                                                    ]}
                                                >
                                                    <Text
                                                        style={[
                                                            styles.separatorChipText,
                                                            { color: active ? colors.primaryText : colors.text },
                                                        ]}
                                                    >
                                                        {value === ' ' ? '␣' : value}
                                                    </Text>
                                                </Pressable>
                                            );
                                        })}
                                    </View>
                                </>
                            )}

                            <View style={styles.secretActions}>
                                <Pressable onPress={() => setRevealSecret((v) => !v)} style={styles.revealRow}>
                                    <Ionicons
                                        name={revealSecret ? 'eye-off-outline' : 'eye-outline'}
                                        size={16}
                                        color={colors.textMuted}
                                    />
                                    <Text style={[styles.revealText, { color: colors.textMuted }]}>
                                        {t(revealSecret ? 'backup.hide' : 'backup.reveal')}
                                    </Text>
                                </Pressable>

                                {/* Export only. On import this would just copy back what was
                                    typed to unlock the file, which is of no use. */}
                                {mode === 'export' && (
                                    <Pressable
                                        onPress={handleCopySecret}
                                        disabled={secretValue.length === 0}
                                        style={styles.revealRow}
                                    >
                                        <Ionicons
                                            name={copied ? 'checkmark' : 'copy-outline'}
                                            size={16}
                                            color={
                                                secretValue.length === 0
                                                    ? colors.textMuted
                                                    : copied
                                                      ? colors.accent
                                                      : colors.textMuted
                                            }
                                        />
                                        <Text
                                            style={[
                                                styles.revealText,
                                                { color: copied ? colors.accent : colors.textMuted },
                                            ]}
                                        >
                                            {t(copied ? 'backup.copied' : 'backup.copy')}
                                        </Text>
                                    </Pressable>
                                )}
                            </View>

                            {/* Strength is only meaningful when choosing a secret, not entering one. */}
                            {mode === 'export' && secretValue.length > 0 && (
                                <View style={styles.strengthBlock}>
                                    <View style={[styles.strengthTrack, { backgroundColor: colors.surfaceHover }]}>
                                        <View
                                            style={[
                                                styles.strengthFill,
                                                {
                                                    backgroundColor: strengthColor[strength.level],
                                                    width: strengthWidth[strength.level] as `${number}%`,
                                                },
                                            ]}
                                        />
                                    </View>
                                    <Text style={[styles.strengthText, { color: colors.textMuted }]}>
                                        {t(`backup.strength.${strength.level}`)} · {t('backup.bits', { bits: strength.bits })}
                                    </Text>
                                </View>
                            )}

                            {problems.map((problem, index) => (
                                <Text
                                    key={`${problem.code}-${index}`}
                                    style={[styles.problem, { color: colors.error }]}
                                >
                                    {describeProblem(problem)}
                                </Text>
                            ))}

                            {unusedMethodHasContent && (
                                <Text style={[styles.warning, { color: colors.warning }]}>
                                    {t(
                                        secretKind === 'password'
                                            ? 'backup.passphraseIgnored'
                                            : 'backup.passwordIgnored',
                                    )}
                                </Text>
                            )}

                            {mode === 'export' && (
                                <Text style={[styles.warning, { color: colors.textMuted }]}>
                                    {t('backup.noRecovery')}
                                </Text>
                            )}
                        </View>
                    )}

                    {job && (
                        <View style={styles.group}>
                            <Text style={[styles.status, { color: colors.text }]}>
                                {t(job.mode === 'exporting' ? 'backup.jobExporting' : 'backup.jobRestoring', {
                                    processed: job.processed,
                                    total: job.total,
                                })}
                            </Text>
                            <View style={[styles.strengthTrack, { backgroundColor: colors.surfaceHover }]}>
                                <View
                                    style={[
                                        styles.strengthFill,
                                        {
                                            backgroundColor: colors.accent,
                                            width: `${job.total > 0 ? Math.round((job.processed / job.total) * 100) : 0}%`,
                                        },
                                    ]}
                                />
                            </View>
                            <Text style={[styles.report, { color: colors.textMuted }]}>
                                {t('backup.jobKeepsRunning')}
                            </Text>
                        </View>
                    )}

                    {status && (
                        <Text style={[styles.status, { color: colors.text }]}>{status}</Text>
                    )}
                    {report && (
                        <Text style={[styles.report, { color: colors.textMuted }]}>{report}</Text>
                    )}
                </ScrollView>

                <View style={[styles.footer, { borderTopColor: colors.border, backgroundColor: colors.background }]}>
                    <Pressable
                        onPress={mode === 'export' ? handleExport : () => setConfirmRestore(true)}
                        disabled={!canAct}
                        style={[
                            styles.primaryButton,
                            { backgroundColor: canAct ? colors.primary : colors.surfaceHover },
                        ]}
                    >
                        {busy || job != null ? (
                            <ActivityIndicator size="small" color={colors.primaryText} />
                        ) : (
                            <Text
                                style={[
                                    styles.primaryButtonText,
                                    { color: canAct ? colors.primaryText : colors.textMuted },
                                ]}
                            >
                                {t(mode === 'export' ? 'backup.exportAction' : 'backup.restoreAction')}
                            </Text>
                        )}
                    </Pressable>
                </View>
            </KeyboardAvoidingView>

            <ConfirmModal
                visible={confirmRestore}
                busy={busy}
                config={{
                    title: t('backup.confirmRestoreTitle'),
                    message: t('backup.confirmRestoreMessage'),
                    confirm: t('backup.restoreAction'),
                    // Restoring writes into the vault and the music library; it adds rather
                    // than replaces, but it is still not something to undo casually.
                    destructive: true,
                }}
                onConfirm={handleRestore}
                onCancel={() => setConfirmRestore(false)}
            />
        </SafeAreaView>
    );
}

const styles = StyleSheet.create({
    screen: { flex: 1 },
    flex: { flex: 1 },
    content: { padding: 16, gap: 16, paddingBottom: 32 },
    segment: { flexDirection: 'row', borderRadius: 10, padding: 3 },
    segmentButton: { flex: 1, alignItems: 'center', paddingVertical: 9, borderRadius: 8 },
    segmentText: { fontSize: 13, fontWeight: '600' },
    blurb: { fontSize: 13, lineHeight: 19 },
    pickButton: {
        flexDirection: 'row',
        alignItems: 'center',
        justifyContent: 'center',
        gap: 8,
        paddingVertical: 14,
        borderRadius: 10,
        borderWidth: 1,
        borderStyle: 'dashed',
    },
    pickText: { fontSize: 14, fontWeight: '600' },
    previewMeta: { fontSize: 12, textAlign: 'center' },
    group: { gap: 8 },
    groupTitle: { fontSize: 12, fontWeight: '700', textTransform: 'uppercase', letterSpacing: 0.5 },
    sectionRow: {
        flexDirection: 'row',
        alignItems: 'center',
        gap: 12,
        padding: 12,
        borderRadius: 10,
        borderWidth: 1,
    },
    sectionName: { fontSize: 14, fontWeight: '600' },
    sectionHint: { fontSize: 12, marginTop: 2 },
    input: { borderRadius: 10, borderWidth: 1, paddingHorizontal: 12, paddingVertical: 11, fontSize: 15 },
    wordGrid: { flexDirection: 'row', flexWrap: 'wrap', gap: 8 },
    wordInput: {
        width: '31.5%',
        borderRadius: 8,
        borderWidth: 1,
        paddingHorizontal: 10,
        paddingVertical: 9,
        fontSize: 14,
    },
    wordControls: { flexDirection: 'row', alignItems: 'center', gap: 8 },
    smallButton: {
        borderWidth: 1,
        borderRadius: 8,
        paddingHorizontal: 10,
        paddingVertical: 7,
        alignItems: 'center',
        justifyContent: 'center',
    },
    generateButton: { flexDirection: 'row', gap: 6, marginLeft: 'auto' },
    generateText: { fontSize: 13, fontWeight: '600' },
    wordCount: { fontSize: 12 },
    separatorRow: { flexDirection: 'row', alignItems: 'center', gap: 6, flexWrap: 'wrap' },
    separatorLabel: { fontSize: 12, marginRight: 2 },
    separatorChip: {
        minWidth: 34,
        alignItems: 'center',
        borderRadius: 8,
        borderWidth: 1,
        paddingVertical: 6,
        paddingHorizontal: 8,
    },
    separatorChipText: { fontSize: 14, fontWeight: '600' },
    secretActions: { flexDirection: 'row', alignItems: 'center', gap: 18 },
    revealRow: { flexDirection: 'row', alignItems: 'center', gap: 6, paddingVertical: 4 },
    revealText: { fontSize: 12 },
    strengthBlock: { gap: 6 },
    strengthTrack: { height: 6, borderRadius: 3, overflow: 'hidden' },
    strengthFill: { height: '100%', borderRadius: 3 },
    strengthText: { fontSize: 12 },
    problem: { fontSize: 12 },
    warning: { fontSize: 12, lineHeight: 17, fontStyle: 'italic' },
    status: { fontSize: 13, textAlign: 'center' },
    report: { fontSize: 12, textAlign: 'center' },
    footer: { padding: 16, borderTopWidth: StyleSheet.hairlineWidth },
    primaryButton: { borderRadius: 12, paddingVertical: 15, alignItems: 'center' },
    primaryButtonText: { fontSize: 15, fontWeight: '700' },
});
