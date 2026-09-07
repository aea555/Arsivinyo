import { useFocusEffect, useRouter } from 'expo-router';
import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  ActivityIndicator,
  Modal,
  Pressable,
  ScrollView,
  StyleSheet,
  Switch,
  View,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

import { Ionicons } from '@expo/vector-icons';

import { getErrorMessage } from '@/src/api/errors';
import {
  authenticateLocalPrivateAccess,
  cancelLocalPrivateVaultMigration,
  ensureLocalBackgroundPermission,
  getLocalAudioFormat,
  getLocalBackgroundState,
  getLocalYtDlpUpdateStatus,
  listenLocalPrivateVaultMigration,
  listenYtDlpUpdateProgress,
  setLocalAudioFormat,
  setLocalStickyNotificationEnabled,
  startLocalPrivateVaultMigration,
  updateLocalYtDlp,
} from '@/src/api';
import { AppText as Text, AppTextInput as TextInput, ConfirmModal, SettingsItem, ThemePicker } from '@/src/components';
import {
  DEFAULT_AUTO_PRESET_CONFIG,
  getAutoPresetConfig,
  isValidAutoPresetConfig,
  outputsPerDownload,
  saveAutoPresetConfig,
  type AutoPresetConfig,
} from '@/src/features/audioPresets/autoApply';
import { listAllPresets, type AudioPreset } from '@/src/features/audioPresets/presets';
import { BUILD_CONFIG } from '@/src/config';
import type {
  LocalAudioFormat,
  LocalPrivateMigrationProgress,
  LocalYtDlpUpdateProgressEvent,
  LocalYtDlpUpdateStatus,
} from '@/src/native/localDownloader';
import {
  type CookiePlatform,
  type CookieProfile,
  type CustomDomainProfile,
  type CustomDomainSummary,
  deleteCookieProfile,
  deleteCustomDomainProfile,
  getDefaultCookieProfile,
  importCookieProfile,
  importCustomCookieProfile,
  listCookieProfiles,
  listCustomDomainProfiles,
  listCustomDomains,
  LOCAL_COOKIE_PLATFORMS,
  setDefaultCookieProfile,
  setCustomDomainDefault,
} from '@/src/services';
import { useTheme } from '@/src/theme';

type BuiltInSelectorMode = 'default' | 'delete' | null;
type CustomSelectorMode = 'default' | 'delete' | null;
type PendingBuiltInDelete = { platform: CookiePlatform; profileName: string } | null;
type PendingCustomDelete = { domain: string; profileName: string } | null;
type FeedbackState = { title: string; message: string; tone: 'success' | 'error' } | null;

export default function SettingsScreen() {
  const { t } = useTranslation();
  const { colors } = useTheme();
  const router = useRouter();

  const [cookieSummary, setCookieSummary] = useState<Record<CookiePlatform, { count: number; defaultProfile: string | null }>>(
    Object.fromEntries(
      LOCAL_COOKIE_PLATFORMS.map((platform) => [platform, { count: 0, defaultProfile: null }])
    ) as Record<CookiePlatform, { count: number; defaultProfile: string | null }>
  );
  const [customDomains, setCustomDomains] = useState<CustomDomainSummary[]>([]);
  const [selectorPlatform, setSelectorPlatform] = useState<CookiePlatform | null>(null);
  const [selectorMode, setSelectorMode] = useState<BuiltInSelectorMode>(null);
  const [selectorProfiles, setSelectorProfiles] = useState<CookieProfile[]>([]);
  const [selectorDefaultProfile, setSelectorDefaultProfile] = useState<string | null>(null);

  const [customSelectorMode, setCustomSelectorMode] = useState<CustomSelectorMode>(null);
  const [customSelectorDomain, setCustomSelectorDomain] = useState<string | null>(null);
  const [customSelectorProfiles, setCustomSelectorProfiles] = useState<CustomDomainProfile[]>([]);
  const [customSelectorDefaultProfile, setCustomSelectorDefaultProfile] = useState<string | null>(null);
  const [cookieActionPlatform, setCookieActionPlatform] = useState<CookiePlatform | null>(null);
  const [customDomainAction, setCustomDomainAction] = useState<string | null>(null);
  const [pendingBuiltInDelete, setPendingBuiltInDelete] = useState<PendingBuiltInDelete>(null);
  const [pendingCustomDelete, setPendingCustomDelete] = useState<PendingCustomDelete>(null);

  const [showCustomImportModal, setShowCustomImportModal] = useState(false);
  const [customImportDomain, setCustomImportDomain] = useState('');
  const [diagnosticUnlockTaps, setDiagnosticUnlockTaps] = useState(0);
  const [feedback, setFeedback] = useState<FeedbackState>(null);
  const [ytDlpUpdateStatus, setYtDlpUpdateStatus] = useState<LocalYtDlpUpdateStatus | null>(null);
  const [ytDlpUpdateProgress, setYtDlpUpdateProgress] = useState<LocalYtDlpUpdateProgressEvent | null>(null);
  const [ytDlpUpdating, setYtDlpUpdating] = useState(false);
  const [vaultMigrationVisible, setVaultMigrationVisible] = useState(false);
  const [vaultMigrationProgress, setVaultMigrationProgress] = useState<LocalPrivateMigrationProgress | null>(null);
  const [vaultMigrationStarting, setVaultMigrationStarting] = useState(false);
  const [vaultMigrationFinished, setVaultMigrationFinished] = useState(false);

  const [stickyNotificationEnabled, setStickyNotificationEnabled] = useState(false);
  const [stickyToggleBusy, setStickyToggleBusy] = useState(false);
  const [audioFormat, setAudioFormat] = useState<LocalAudioFormat>('flac');
  const [audioFormatBusy, setAudioFormatBusy] = useState(false);
  const [autoPresets, setAutoPresets] = useState<AutoPresetConfig>(DEFAULT_AUTO_PRESET_CONFIG);
  const [autoPresetSheetOpen, setAutoPresetSheetOpen] = useState(false);
  const [availablePresets, setAvailablePresets] = useState<AudioPreset[]>([]);

  const showError = useCallback((message: string) => {
    setFeedback({ title: t('common.error'), message, tone: 'error' });
  }, [t]);

  const showSuccess = useCallback((message: string) => {
    setFeedback({ title: t('common.success'), message, tone: 'success' });
  }, [t]);

  const closeFeedback = useCallback(() => {
    setFeedback(null);
  }, []);

  // Background-service toggles (relocated here from the home screen to keep that
  // screen focused on downloading).
  useEffect(() => {
    let mounted = true;
    void getLocalBackgroundState()
      .then((state) => {
        if (!mounted) return;
        setStickyNotificationEnabled(Boolean(state.stickyNotificationEnabled));
      })
      .catch(() => undefined);
    void getLocalAudioFormat()
      .then((state) => {
        if (!mounted) return;
        setAudioFormat(state.format);
      })
      .catch(() => undefined);
    void getAutoPresetConfig()
      .then((config) => {
        if (mounted) setAutoPresets(config);
      })
      .catch(() => undefined);
    void listAllPresets()
      .then((all) => {
        if (mounted) setAvailablePresets(all);
      })
      .catch(() => undefined);
    return () => {
      mounted = false;
    };
  }, []);

  // Lossless is the default. Every source we download is already lossy, so encoding
  // it to AAC again would stack a second generation of loss; FLAC keeps exactly what
  // the decoder produced, at roughly 3x the size. Only affects NEW downloads.
  const handleToggleAudioFormat = useCallback(async () => {
    if (audioFormatBusy) return;
    const nextFormat: LocalAudioFormat = audioFormat === 'flac' ? 'm4a' : 'flac';
    setAudioFormatBusy(true);
    try {
      const result = await setLocalAudioFormat(nextFormat);
      setAudioFormat(result.format);
    } catch (error) {
      showError(error instanceof Error ? error.message : t('errors.UNKNOWN_ERROR'));
    } finally {
      setAudioFormatBusy(false);
    }
  }, [audioFormat, audioFormatBusy, showError, t]);

  /**
   * Toggle one entry of the auto-apply set.
   *
   * A selection of nothing would throw the download away entirely, so the last
   * remaining entry cannot be turned off.
   */
  const toggleAutoPreset = useCallback(
    async (key: 'original' | string) => {
      const next: AutoPresetConfig =
        key === 'original'
          ? { ...autoPresets, keepOriginal: !autoPresets.keepOriginal }
          : {
              ...autoPresets,
              presetIds: autoPresets.presetIds.includes(key)
                ? autoPresets.presetIds.filter((id) => id !== key)
                : [...autoPresets.presetIds, key],
            };
      if (!isValidAutoPresetConfig(next)) {
        showError(t('settings.autoPresetAtLeastOne'));
        return;
      }
      setAutoPresets(next);
      try {
        setAutoPresets(await saveAutoPresetConfig(next));
      } catch (error) {
        showError(error instanceof Error ? error.message : t('errors.UNKNOWN_ERROR'));
      }
    },
    [autoPresets, showError, t]
  );

  const handleToggleStickyNotification = useCallback(async () => {
    if (stickyToggleBusy) return;
    const nextEnabled = !stickyNotificationEnabled;
    setStickyToggleBusy(true);
    try {
      if (nextEnabled) {
        const permission = await ensureLocalBackgroundPermission();
        if (!permission.granted) {
          showError(t('errors.BACKGROUND_PERMISSION_REQUIRED'));
          return;
        }
      }
      const result = await setLocalStickyNotificationEnabled(nextEnabled);
      setStickyNotificationEnabled(Boolean(result.enabled));
    } catch (error) {
      showError(error instanceof Error ? error.message : t('errors.UNKNOWN_ERROR'));
    } finally {
      setStickyToggleBusy(false);
    }
  }, [stickyNotificationEnabled, stickyToggleBusy, showError, t]);

  const refreshCookieData = useCallback(async () => {
    try {
      const [summaryEntries, customDomainList] = await Promise.all([
        Promise.all(
          LOCAL_COOKIE_PLATFORMS.map(async (platform) => {
            const [profiles, defaultProfile] = await Promise.all([
              listCookieProfiles(platform),
              getDefaultCookieProfile(platform),
            ]);

            return [platform, { count: profiles.length, defaultProfile }] as const;
          })
        ),
        listCustomDomains(),
      ]);

      setCookieSummary(Object.fromEntries(summaryEntries) as Record<CookiePlatform, { count: number; defaultProfile: string | null }>);
      setCustomDomains(customDomainList);
    } catch (error) {
      const code = error instanceof Error ? error.message : 'UNKNOWN_ERROR';
      showError(getErrorMessage(code));
    }
  }, [showError, t]);

  useEffect(() => {
    void refreshCookieData();
  }, [refreshCookieData]);

  const refreshYtDlpUpdateStatus = useCallback(async () => {
    try {
      const status = await getLocalYtDlpUpdateStatus();
      setYtDlpUpdateStatus(status);
    } catch {
      setYtDlpUpdateStatus(null);
    }
  }, []);

  useEffect(() => {
    void refreshYtDlpUpdateStatus();
    const subscription = listenYtDlpUpdateProgress((event) => {
      setYtDlpUpdateProgress(event);
      setYtDlpUpdating(!['available', 'up_to_date', 'installed', 'failed'].includes(event.phase));
    });
    return () => subscription.remove();
  }, [refreshYtDlpUpdateStatus]);

  useFocusEffect(
    useCallback(() => {
      void refreshYtDlpUpdateStatus();
    }, [refreshYtDlpUpdateStatus])
  );

  const closeSelector = useCallback(() => {
    setSelectorPlatform(null);
    setSelectorMode(null);
    setSelectorProfiles([]);
    setSelectorDefaultProfile(null);
  }, []);

  const customDefaultsMap = useMemo(() => {
    return Object.fromEntries(customDomains.map((item) => [item.domain, item.defaultProfileName])) as Record<string, string | null>;
  }, [customDomains]);

  const handleImportCookie = useCallback(async (platform: CookiePlatform) => {
    try {
      const result = await importCookieProfile(platform);
      if (!result.imported) {
        return;
      }

      if (result.profileName) {
        await setDefaultCookieProfile(platform, result.profileName);
      }

      await refreshCookieData();
      showSuccess(t('settings.cookieImportSuccess', { platform, profile: result.profileName ?? 'default' }));
    } catch (error) {
      const code = error instanceof Error ? error.message : 'UNKNOWN_ERROR';
      showError(getErrorMessage(code));
    }
  }, [refreshCookieData, showError, showSuccess, t]);

  const openCookieSelector = useCallback(async (platform: CookiePlatform, mode: Exclude<BuiltInSelectorMode, null>) => {
    const profiles = await listCookieProfiles(platform);
    if (profiles.length === 0) {
      showError(t('settings.noCookieProfiles'));
      return;
    }

    const defaultProfile = await getDefaultCookieProfile(platform);
    setSelectorMode(mode);
    setSelectorProfiles(profiles);
    setSelectorDefaultProfile(defaultProfile);
    setSelectorPlatform(platform);
  }, [showError, t]);

  const handleCookiePlatformPress = useCallback((platform: CookiePlatform) => {
    setCookieActionPlatform(platform);
  }, []);

  const closeCookieActionModal = useCallback(() => {
    setCookieActionPlatform(null);
  }, []);

  const handleCookieActionImport = useCallback(() => {
    if (!cookieActionPlatform) return;
    const selected = cookieActionPlatform;
    closeCookieActionModal();
    void handleImportCookie(selected);
  }, [closeCookieActionModal, cookieActionPlatform, handleImportCookie]);

  const handleCookieActionSelectDefault = useCallback(() => {
    if (!cookieActionPlatform) return;
    const selected = cookieActionPlatform;
    closeCookieActionModal();
    void openCookieSelector(selected, 'default');
  }, [closeCookieActionModal, cookieActionPlatform, openCookieSelector]);

  const handleCookieActionDelete = useCallback(() => {
    if (!cookieActionPlatform) return;
    const selected = cookieActionPlatform;
    closeCookieActionModal();
    void openCookieSelector(selected, 'delete');
  }, [closeCookieActionModal, cookieActionPlatform, openCookieSelector]);

  const handleSetDefaultProfile = useCallback(async (platform: CookiePlatform, profileName: string) => {
    try {
      await setDefaultCookieProfile(platform, profileName);
      await refreshCookieData();
      setSelectorDefaultProfile(profileName);
    } catch (error) {
      const code = error instanceof Error ? error.message : 'UNKNOWN_ERROR';
      showError(getErrorMessage(code));
    }
  }, [refreshCookieData, showError, t]);

  const requestDeleteCookieProfile = useCallback((platform: CookiePlatform, profileName: string) => {
    setPendingBuiltInDelete({ platform, profileName });
  }, []);

  const closeBuiltInDeleteConfirmModal = useCallback(() => {
    setPendingBuiltInDelete(null);
  }, []);

  const handleDeleteCookieProfile = useCallback(async () => {
    if (!pendingBuiltInDelete) return;
    const { platform, profileName } = pendingBuiltInDelete;
    closeBuiltInDeleteConfirmModal();
    try {
      await deleteCookieProfile(platform, profileName);
      await refreshCookieData();
      const remaining = await listCookieProfiles(platform);
      if (remaining.length === 0) {
        closeSelector();
      } else {
        setSelectorProfiles(remaining);
        setSelectorDefaultProfile(await getDefaultCookieProfile(platform));
      }
    } catch (error) {
      const code = error instanceof Error ? error.message : 'UNKNOWN_ERROR';
      showError(getErrorMessage(code));
    }
  }, [closeBuiltInDeleteConfirmModal, closeSelector, pendingBuiltInDelete, refreshCookieData, showError]);

  const openCustomImportModal = useCallback((presetDomain?: string) => {
    setCustomImportDomain(presetDomain ?? '');
    setShowCustomImportModal(true);
  }, []);

  const closeCustomImportModal = useCallback(() => {
    setShowCustomImportModal(false);
    setCustomImportDomain('');
  }, []);

  const handleImportCustomCookie = useCallback(async () => {
    try {
      const result = await importCustomCookieProfile({
        domain: customImportDomain.trim() || null,
      });
      if (!result.imported || !result.result) {
        closeCustomImportModal();
        return;
      }

      await refreshCookieData();
      closeCustomImportModal();
      showSuccess(t('settings.customCookieImportSuccess', {
        profile: result.result.profileName,
        domains: result.result.boundDomains.length,
      }));
    } catch (error) {
      const code = error instanceof Error ? error.message : 'UNKNOWN_ERROR';
      showError(getErrorMessage(code));
    }
  }, [closeCustomImportModal, customImportDomain, refreshCookieData, showError, showSuccess, t]);

  const closeCustomSelector = useCallback(() => {
    setCustomSelectorMode(null);
    setCustomSelectorDomain(null);
    setCustomSelectorProfiles([]);
    setCustomSelectorDefaultProfile(null);
  }, []);

  const openCustomSelector = useCallback(async (domain: string, mode: Exclude<CustomSelectorMode, null>) => {
    try {
      const profiles = await listCustomDomainProfiles(domain);
      if (profiles.length === 0) {
        showError(t('settings.noCustomDomainProfiles'));
        return;
      }
      setCustomSelectorMode(mode);
      setCustomSelectorDomain(domain);
      setCustomSelectorProfiles(profiles);
      setCustomSelectorDefaultProfile(customDefaultsMap[domain] ?? null);
    } catch (error) {
      const code = error instanceof Error ? error.message : 'UNKNOWN_ERROR';
      showError(getErrorMessage(code));
    }
  }, [customDefaultsMap, showError, t]);

  const handleCustomDomainPress = useCallback((domain: string) => {
    setCustomDomainAction(domain);
  }, []);

  const closeCustomActionModal = useCallback(() => {
    setCustomDomainAction(null);
  }, []);

  const handleCustomActionImport = useCallback(() => {
    if (!customDomainAction) return;
    const selected = customDomainAction;
    closeCustomActionModal();
    openCustomImportModal(selected);
  }, [closeCustomActionModal, customDomainAction, openCustomImportModal]);

  const handleCustomActionSelectDefault = useCallback(() => {
    if (!customDomainAction) return;
    const selected = customDomainAction;
    closeCustomActionModal();
    void openCustomSelector(selected, 'default');
  }, [closeCustomActionModal, customDomainAction, openCustomSelector]);

  const handleCustomActionDelete = useCallback(() => {
    if (!customDomainAction) return;
    const selected = customDomainAction;
    closeCustomActionModal();
    void openCustomSelector(selected, 'delete');
  }, [closeCustomActionModal, customDomainAction, openCustomSelector]);

  const handleSetCustomDefaultProfile = useCallback(async (domain: string, profileName: string) => {
    try {
      await setCustomDomainDefault(domain, profileName);
      await refreshCookieData();
      setCustomSelectorDefaultProfile(profileName);
    } catch (error) {
      const code = error instanceof Error ? error.message : 'UNKNOWN_ERROR';
      showError(getErrorMessage(code));
    }
  }, [refreshCookieData, showError, t]);

  const requestDeleteCustomProfile = useCallback((domain: string, profileName: string) => {
    setPendingCustomDelete({ domain, profileName });
  }, []);

  const closeDeleteConfirmModal = useCallback(() => {
    setPendingCustomDelete(null);
  }, []);

  const handleDeleteCustomProfile = useCallback(async () => {
    if (!pendingCustomDelete) return;
    const { domain, profileName } = pendingCustomDelete;
    closeDeleteConfirmModal();
    try {
      await deleteCustomDomainProfile(domain, profileName);
      await refreshCookieData();
      const remaining = await listCustomDomainProfiles(domain);
      if (remaining.length === 0) {
        closeCustomSelector();
      } else {
        setCustomSelectorProfiles(remaining);
        const currentDefault = customDefaultsMap[domain];
        const nextDefault = remaining.some((entry) => entry.profileName === currentDefault)
          ? currentDefault
          : remaining[0]?.profileName ?? null;
        setCustomSelectorDefaultProfile(nextDefault);
      }
    } catch (error) {
      const code = error instanceof Error ? error.message : 'UNKNOWN_ERROR';
      showError(getErrorMessage(code));
    }
  }, [closeCustomSelector, closeDeleteConfirmModal, customDefaultsMap, pendingCustomDelete, refreshCookieData, showError]);

  const handleVersionPress = useCallback(() => {
    setDiagnosticUnlockTaps((prev) => prev + 1);
  }, []);

  const openDiagnostics = useCallback(() => {
    router.push('/diagnostics' as never);
  }, [router]);

  const openDevices = useCallback(() => {
    router.push('/devices' as never);
  }, [router]);

  const openBackup = useCallback(() => {
    router.push('/backup');
  }, [router]);

  const openRecentFailures = useCallback(() => {
    router.push('/recent-failures' as never);
  }, [router]);

  const ytDlpUpdateSubtitle = useMemo(() => {
    const progress = ytDlpUpdateProgress;
    if (ytDlpUpdating && progress) {
      if (progress.phase === 'downloading' && typeof progress.percent === 'number') {
        return t('settings.ytDlpUpdateDownloading', { percent: Math.round(progress.percent) });
      }
      return t(`settings.ytDlpUpdatePhase.${progress.phase}`, { defaultValue: t('settings.ytDlpUpdateWorking') });
    }

    if (ytDlpUpdateStatus?.pendingVersion || ytDlpUpdateStatus?.requiresRestart) {
      return t('settings.ytDlpUpdateRestartRequired', {
        version: ytDlpUpdateStatus.pendingVersion ?? ytDlpUpdateStatus.effectiveInstalledVersion ?? 'unknown',
      });
    }

    if (ytDlpUpdateStatus?.failedReason) {
      return t('settings.ytDlpUpdateFailedHint', { reason: ytDlpUpdateStatus.failedReason });
    }

    const active = ytDlpUpdateStatus?.activeVersion ?? ytDlpUpdateStatus?.effectiveInstalledVersion ?? 'unknown';
    const source = ytDlpUpdateStatus?.source === 'override' ? t('settings.ytDlpUpdateSourceOverride') : t('settings.ytDlpUpdateSourceBundled');
    return t('settings.ytDlpUpdateHint', { version: active, source });
  }, [t, ytDlpUpdateProgress, ytDlpUpdateStatus, ytDlpUpdating]);

  const ytDlpUpdateDisabled =
    ytDlpUpdating ||
    (ytDlpUpdateStatus?.activeTaskIds?.length ?? 0) > 0 ||
    ytDlpUpdateStatus?.storageReady === false;

  useEffect(() => {
    if (!vaultMigrationVisible) return;
    const subscription = listenLocalPrivateVaultMigration((event) => {
      setVaultMigrationProgress(event);
      if (event.currentEntryId == null && event.processed > 0 && event.processed === event.total) {
        setVaultMigrationFinished(true);
      }
    });
    return () => subscription.remove();
  }, [vaultMigrationVisible]);

  const handleStartVaultMigration = useCallback(async () => {
    if (vaultMigrationStarting || vaultMigrationVisible) return;
    setVaultMigrationStarting(true);
    try {
      const auth = await authenticateLocalPrivateAccess('migrate');
      if (!auth.granted) {
        showError(t(`errors.${auth.reason ?? 'PRIVATE_AUTH_FAILED'}`, { defaultValue: t('errors.PRIVATE_AUTH_FAILED') }));
        return;
      }
      const start = await startLocalPrivateVaultMigration();
      if (!start.success) {
        const codeKey = `errors.${start.code ?? 'PRIVATE_MIGRATION_BLOCKED'}`;
        const localized = t(codeKey, { defaultValue: t('errors.UNKNOWN_ERROR') });
        showError(localized);
        return;
      }
      if (start.outcome === 'COMPLETED') {
        showSuccess(t('settings.vaultMigrationAlreadyCurrent'));
        return;
      }
      setVaultMigrationProgress({
        total: start.total ?? 0,
        processed: 0,
        succeeded: 0,
        failed: 0,
        skipped: 0,
      });
      setVaultMigrationFinished(false);
      setVaultMigrationVisible(true);
    } catch (e) {
      const code = e instanceof Error ? e.message : 'UNKNOWN_ERROR';
      showError(getErrorMessage(code));
    } finally {
      setVaultMigrationStarting(false);
    }
  }, [showError, showSuccess, t, vaultMigrationStarting, vaultMigrationVisible]);

  const handleCancelVaultMigration = useCallback(async () => {
    try {
      await cancelLocalPrivateVaultMigration();
    } catch (e) {
      const code = e instanceof Error ? e.message : 'UNKNOWN_ERROR';
      showError(getErrorMessage(code));
    }
  }, [showError]);

  const handleCloseVaultMigration = useCallback(() => {
    setVaultMigrationVisible(false);
    setVaultMigrationProgress(null);
    setVaultMigrationFinished(false);
  }, []);

  const handleYtDlpUpdate = useCallback(async () => {
    if (ytDlpUpdateDisabled) {
      if ((ytDlpUpdateStatus?.activeTaskIds?.length ?? 0) > 0) {
        showError(t('settings.ytDlpUpdateDownloadActive'));
      }
      return;
    }

    setYtDlpUpdating(true);
    setYtDlpUpdateProgress({ phase: 'checking' });
    try {
      const result = await updateLocalYtDlp();
      await refreshYtDlpUpdateStatus();
      if (result.status === 'installed' && result.installedVersion) {
        showSuccess(t('settings.ytDlpUpdateInstalled', { version: result.installedVersion }));
      } else if (result.status === 'up_to_date') {
        showSuccess(t('settings.ytDlpUpdateAlreadyCurrent'));
      } else if (result.status === 'blocked' || result.code === 'DOWNLOAD_ACTIVE') {
        showError(t('settings.ytDlpUpdateDownloadActive'));
      } else {
        showError(result.message ?? result.code ?? t('settings.ytDlpUpdateFailedGeneric'));
      }
    } catch (error) {
      const code = error instanceof Error ? error.message : 'UNKNOWN_ERROR';
      showError(getErrorMessage(code));
    } finally {
      setYtDlpUpdating(false);
    }
  }, [refreshYtDlpUpdateStatus, showError, showSuccess, t, ytDlpUpdateDisabled, ytDlpUpdateStatus?.activeTaskIds]);

  return (
    <SafeAreaView style={[styles.container, { backgroundColor: colors.background }]}>
      <ScrollView
        style={styles.content}
        contentContainerStyle={styles.contentContainer}
      >
        <View style={styles.section}>
          <Text style={[styles.sectionTitle, { color: colors.textMuted }]}>
            {t('settings.appearance')}
          </Text>
          <View style={[styles.sectionContent, { backgroundColor: colors.surface }]}>
            <ThemePicker />
          </View>
        </View>

        <View style={styles.section}>
          <Text style={[styles.sectionTitle, { color: colors.textMuted }]}>
            {t('settings.backgroundSection')}
          </Text>
          <View style={[styles.sectionContent, { backgroundColor: colors.surface }]}>
            <SettingsItem
              icon="notifications-outline"
              title={t('settings.stickyNotification')}
              subtitle={t('settings.stickyNotificationHint')}
              showArrow={false}
              onPress={handleToggleStickyNotification}
              rightElement={
                <Switch
                  value={stickyNotificationEnabled}
                  onValueChange={handleToggleStickyNotification}
                  disabled={stickyToggleBusy}
                  trackColor={{ false: colors.borderSubtle, true: colors.accent }}
                  thumbColor="#f4f4f5"
                  ios_backgroundColor={colors.borderSubtle}
                />
              }
            />
            <SettingsItem
              icon="color-wand-outline"
              title={t('settings.autoPresets')}
              subtitle={t('settings.autoPresetsHint', {
                count: outputsPerDownload(autoPresets),
              })}
              onPress={() => setAutoPresetSheetOpen(true)}
            />
            <SettingsItem
              icon="musical-notes-outline"
              title={t('settings.losslessAudio')}
              subtitle={
                audioFormat === 'flac'
                  ? t('settings.losslessAudioOnHint')
                  : t('settings.losslessAudioOffHint')
              }
              showArrow={false}
              onPress={handleToggleAudioFormat}
              rightElement={
                <Switch
                  value={audioFormat === 'flac'}
                  onValueChange={handleToggleAudioFormat}
                  disabled={audioFormatBusy}
                  trackColor={{ false: colors.borderSubtle, true: colors.accent }}
                  thumbColor="#f4f4f5"
                  ios_backgroundColor={colors.borderSubtle}
                />
              }
            />
          </View>
        </View>

        <View style={styles.section}>
          <Text style={[styles.sectionTitle, { color: colors.textMuted }]}>
            {t('settings.cookieProfiles')}
          </Text>
          <View style={[styles.sectionContent, { backgroundColor: colors.surface }]}>
            {LOCAL_COOKIE_PLATFORMS.map((platform) => (
              <SettingsItem
                key={platform}
                icon="document-attach-outline"
                title={t(`settings.cookiePlatform.${platform}`)}
                subtitle={t('settings.cookiePlatformHint', {
                  count: cookieSummary[platform].count,
                  defaultProfile: cookieSummary[platform].defaultProfile ?? t('settings.noDefaultCookie'),
                })}
                onPress={() => handleCookiePlatformPress(platform)}
              />
            ))}
          </View>
        </View>

        <View style={styles.section}>
          <Text style={[styles.sectionTitle, { color: colors.textMuted }]}>
            {t('settings.customCookieDomains')}
          </Text>
          <View style={[styles.sectionContent, { backgroundColor: colors.surface }]}>
            <SettingsItem
              icon="add-circle-outline"
              title={t('settings.addCustomCookie')}
              subtitle={t('settings.customDomainOptionalHint')}
              onPress={() => openCustomImportModal()}
            />
            {customDomains.map((entry) => (
              <SettingsItem
                key={entry.domain}
                icon="globe-outline"
                title={entry.domain}
                subtitle={t('settings.customDomainHint', {
                  count: entry.profileCount,
                  defaultProfile: entry.defaultProfileName ?? t('settings.noDefaultCookie'),
                })}
                onPress={() => handleCustomDomainPress(entry.domain)}
              />
            ))}
          </View>
        </View>

        <View style={styles.section}>
          <Text style={[styles.sectionTitle, { color: colors.textMuted }]}>
            {t('settings.privateVault')}
          </Text>
          <View style={[styles.sectionContent, { backgroundColor: colors.surface }]}>
            <SettingsItem
              icon="shield-checkmark-outline"
              title={t('settings.vaultMigrate')}
              subtitle={t('settings.vaultMigrateHint')}
              onPress={handleStartVaultMigration}
              rightElement={
                vaultMigrationStarting ? <ActivityIndicator size="small" color={colors.accent} /> : undefined
              }
              showArrow={!vaultMigrationStarting}
            />
          </View>
        </View>

        <View style={styles.section}>
          <Text style={[styles.sectionTitle, { color: colors.textMuted }]}>
            {t('settings.about')}
          </Text>
          <View style={[styles.sectionContent, { backgroundColor: colors.surface }]}>
            <SettingsItem
              icon="information-circle-outline"
              title={t('settings.version')}
              value={BUILD_CONFIG.APP_VERSION}
              onPress={handleVersionPress}
              showArrow={false}
            />
            <SettingsItem
              icon="cloud-download-outline"
              title={t('settings.ytDlpUpdate')}
              subtitle={ytDlpUpdateSubtitle}
              onPress={handleYtDlpUpdate}
              rightElement={ytDlpUpdating ? <ActivityIndicator size="small" color={colors.accent} /> : undefined}
              showArrow={!ytDlpUpdateDisabled}
            />
            <SettingsItem
              icon="phone-portrait-outline"
              title={t('settings.devices')}
              subtitle={t('settings.devicesHint')}
              onPress={openDevices}
            />
            <SettingsItem
              icon="archive-outline"
              title={t('settings.backup')}
              subtitle={t('settings.backupHint')}
              onPress={openBackup}
            />
            <SettingsItem
              icon="warning-outline"
              title={t('settings.recentFailures')}
              subtitle={t('settings.recentFailuresHint')}
              onPress={openRecentFailures}
            />
            {(diagnosticUnlockTaps >= 7 || __DEV__) && (
              <SettingsItem
                icon="bug-outline"
                title={t('settings.diagnostics')}
                subtitle={t('settings.diagnosticsHint')}
                onPress={openDiagnostics}
              />
            )}
          </View>
        </View>
      </ScrollView>

      {/* Which entries one audio download produces. At least one must stay selected,
          since selecting nothing would discard the download outright. */}
      <Modal
        visible={autoPresetSheetOpen}
        transparent
        animationType="fade"
        onRequestClose={() => setAutoPresetSheetOpen(false)}
      >
        <View style={styles.modalBackdrop}>
          <View style={[styles.modalCard, { backgroundColor: colors.surface, borderColor: colors.border }]}>
            <Text style={[styles.modalTitle, { color: colors.text }]}>{t('settings.autoPresets')}</Text>
            <Text style={[styles.modalDescription, { color: colors.textMuted }]}>
              {t('settings.autoPresetsDescription')}
            </Text>

            <Pressable
              onPress={() => toggleAutoPreset('original')}
              style={[styles.autoPresetRow, { borderColor: colors.border }]}
            >
              <Ionicons
                name={autoPresets.keepOriginal ? 'checkbox' : 'square-outline'}
                size={22}
                color={autoPresets.keepOriginal ? colors.accent : colors.textMuted}
              />
              <Text style={[styles.autoPresetLabel, { color: colors.text }]}>
                {t('settings.autoPresetOriginal')}
              </Text>
            </Pressable>

            {availablePresets.map((preset) => {
              const selected = autoPresets.presetIds.includes(preset.id);
              return (
                <Pressable
                  key={preset.id}
                  onPress={() => toggleAutoPreset(preset.id)}
                  style={[styles.autoPresetRow, { borderColor: colors.border }]}
                >
                  <Ionicons
                    name={selected ? 'checkbox' : 'square-outline'}
                    size={22}
                    color={selected ? colors.accent : colors.textMuted}
                  />
                  <Text style={[styles.autoPresetLabel, { color: colors.text }]}>
                    {preset.nameKey ? t(preset.nameKey) : preset.name}
                  </Text>
                </Pressable>
              );
            })}

            <Text style={[styles.modalDescription, { color: colors.textMuted, marginTop: 12 }]}>
              {t('settings.autoPresetsSummary', { count: outputsPerDownload(autoPresets) })}
            </Text>

            <Pressable
              onPress={() => setAutoPresetSheetOpen(false)}
              style={[styles.modalCloseButton, { backgroundColor: colors.surfaceHover }]}
            >
              <Text style={[styles.modalCloseText, { color: colors.text }]}>{t('common.close')}</Text>
            </Pressable>
          </View>
        </View>
      </Modal>

      <Modal
        visible={selectorPlatform !== null && selectorMode !== null}
        transparent
        animationType="fade"
        onRequestClose={closeSelector}
      >
        <View style={styles.modalBackdrop}>
          <View style={[styles.modalCard, { backgroundColor: colors.surface, borderColor: colors.border }]}>
            <Text style={[styles.modalTitle, { color: colors.text }]}>
              {selectorPlatform && selectorMode === 'default'
                ? t('settings.selectDefaultForPlatform', { platform: t(`settings.cookiePlatform.${selectorPlatform}`) })
                : selectorPlatform
                  ? t('settings.selectDeleteCookieForPlatform', { platform: t(`settings.cookiePlatform.${selectorPlatform}`) })
                  : ''}
            </Text>
            <ScrollView style={styles.modalList}>
              {selectorPlatform && selectorProfiles.map((profile) => {
                const selected = selectorDefaultProfile === profile.profileName;
                return (
                  <Pressable
                    key={`${selectorPlatform}-${profile.profileName}`}
                    onPress={() => {
                      if (selectorMode === 'delete') {
                        requestDeleteCookieProfile(selectorPlatform, profile.profileName);
                        return;
                      }
                      void handleSetDefaultProfile(selectorPlatform, profile.profileName);
                    }}
                    style={({ pressed }) => [
                      styles.modalItem,
                      {
                        backgroundColor: pressed ? colors.surfaceHover : 'transparent',
                        borderColor: selectorMode === 'default' && selected ? colors.accent : colors.border,
                      },
                    ]}
                  >
                    <Text style={[
                      styles.modalItemTitle,
                      {
                        color: selectorMode === 'delete'
                          ? colors.error
                          : (selected ? colors.accent : colors.text),
                      },
                    ]}>
                      {profile.profileName}
                    </Text>
                  </Pressable>
                );
              })}
            </ScrollView>
            <Pressable
              onPress={closeSelector}
              style={({ pressed }) => [
                styles.modalCloseButton,
                { backgroundColor: pressed ? colors.surfaceHover : colors.background },
              ]}
            >
              <Text style={[styles.modalCloseText, { color: colors.text }]}>{t('common.cancel')}</Text>
            </Pressable>
          </View>
        </View>
      </Modal>

      <Modal
        visible={customSelectorDomain !== null && customSelectorMode !== null}
        transparent
        animationType="fade"
        onRequestClose={closeCustomSelector}
      >
        <View style={styles.modalBackdrop}>
          <View style={[styles.modalCard, { backgroundColor: colors.surface, borderColor: colors.border }]}>
            <Text style={[styles.modalTitle, { color: colors.text }]}>
              {customSelectorDomain && customSelectorMode === 'default'
                ? t('settings.selectDefaultForDomain', { domain: customSelectorDomain })
                : customSelectorDomain
                  ? t('settings.selectDeleteProfileForDomain', { domain: customSelectorDomain })
                  : ''}
            </Text>
            <ScrollView style={styles.modalList}>
              {customSelectorDomain && customSelectorProfiles.map((profile) => {
                const selected = customSelectorDefaultProfile === profile.profileName;
                return (
                  <Pressable
                    key={`${customSelectorDomain}-${profile.profileId}`}
                    onPress={() => {
                      if (!customSelectorDomain) return;
                      if (customSelectorMode === 'delete') {
                        requestDeleteCustomProfile(customSelectorDomain, profile.profileName);
                        return;
                      }
                      void handleSetCustomDefaultProfile(customSelectorDomain, profile.profileName);
                    }}
                    style={({ pressed }) => [
                      styles.modalItem,
                      {
                        backgroundColor: pressed ? colors.surfaceHover : 'transparent',
                        borderColor: customSelectorMode === 'default' && selected ? colors.accent : colors.border,
                      },
                    ]}
                  >
                    <Text style={[
                      styles.modalItemTitle,
                      {
                        color: customSelectorMode === 'delete'
                          ? colors.error
                          : (customSelectorMode === 'default' && selected ? colors.accent : colors.text),
                      },
                    ]}>
                      {profile.profileName}
                    </Text>
                  </Pressable>
                );
              })}
            </ScrollView>
            <Pressable
              onPress={closeCustomSelector}
              style={({ pressed }) => [
                styles.modalCloseButton,
                { backgroundColor: pressed ? colors.surfaceHover : colors.background },
              ]}
            >
              <Text style={[styles.modalCloseText, { color: colors.text }]}>{t('common.cancel')}</Text>
            </Pressable>
          </View>
        </View>
      </Modal>

      <Modal
        visible={cookieActionPlatform !== null}
        transparent
        animationType="fade"
        onRequestClose={closeCookieActionModal}
      >
        <View style={styles.modalBackdrop}>
          <View style={[styles.modalCard, { backgroundColor: colors.surface, borderColor: colors.border }]}>
            <Text style={[styles.modalTitle, { color: colors.text }]}>
              {cookieActionPlatform ? t(`settings.cookiePlatform.${cookieActionPlatform}`) : ''}
            </Text>
            <Text style={[styles.modalDescription, { color: colors.textMuted }]}>
              {t('settings.cookieActionPrompt')}
            </Text>
            <View style={styles.modalActionsColumn}>
              <Pressable
                onPress={handleCookieActionImport}
                style={({ pressed }) => [
                  styles.modalActionRow,
                  { backgroundColor: pressed ? colors.surfaceHover : colors.background, borderColor: colors.border },
                ]}
              >
                <Text style={[styles.modalActionText, { color: colors.text }]}>{t('settings.importCookieAction')}</Text>
              </Pressable>
              <Pressable
                onPress={handleCookieActionSelectDefault}
                style={({ pressed }) => [
                  styles.modalActionRow,
                  { backgroundColor: pressed ? colors.surfaceHover : colors.background, borderColor: colors.border },
                ]}
              >
                <Text style={[styles.modalActionText, { color: colors.text }]}>{t('settings.selectDefaultCookieAction')}</Text>
              </Pressable>
              <Pressable
                onPress={handleCookieActionDelete}
                style={({ pressed }) => [
                  styles.modalActionRow,
                  { backgroundColor: pressed ? colors.surfaceHover : colors.background, borderColor: colors.error },
                ]}
              >
                <Text style={[styles.modalActionText, { color: colors.error }]}>{t('settings.deleteCookieProfileAction')}</Text>
              </Pressable>
            </View>
            <Pressable
              onPress={closeCookieActionModal}
              style={({ pressed }) => [
                styles.modalCloseButton,
                { backgroundColor: pressed ? colors.surfaceHover : colors.background },
              ]}
            >
              <Text style={[styles.modalCloseText, { color: colors.text }]}>{t('common.cancel')}</Text>
            </Pressable>
          </View>
        </View>
      </Modal>

      <ConfirmModal
        visible={pendingBuiltInDelete !== null}
        config={
          pendingBuiltInDelete
            ? {
                title: t('settings.deleteCookieProfileConfirmTitle'),
                message: t('settings.deleteCookieProfileConfirmMessage', {
                  profile: pendingBuiltInDelete.profileName,
                }),
                confirm: t('settings.deleteCookieProfileAction'),
                destructive: true,
              }
            : null
        }
        onCancel={closeBuiltInDeleteConfirmModal}
        onConfirm={() => {
          void handleDeleteCookieProfile();
        }}
      />

      <Modal
        visible={customDomainAction !== null}
        transparent
        animationType="fade"
        onRequestClose={closeCustomActionModal}
      >
        <View style={styles.modalBackdrop}>
          <View style={[styles.modalCard, { backgroundColor: colors.surface, borderColor: colors.border }]}>
            <Text style={[styles.modalTitle, { color: colors.text }]}>
              {customDomainAction ?? ''}
            </Text>
            <Text style={[styles.modalDescription, { color: colors.textMuted }]}>
              {t('settings.customDomainActionPrompt')}
            </Text>
            <View style={styles.modalActionsColumn}>
              <Pressable
                onPress={handleCustomActionImport}
                style={({ pressed }) => [
                  styles.modalActionRow,
                  { backgroundColor: pressed ? colors.surfaceHover : colors.background, borderColor: colors.border },
                ]}
              >
                <Text style={[styles.modalActionText, { color: colors.text }]}>{t('settings.importCustomCookieAction')}</Text>
              </Pressable>
              <Pressable
                onPress={handleCustomActionSelectDefault}
                style={({ pressed }) => [
                  styles.modalActionRow,
                  { backgroundColor: pressed ? colors.surfaceHover : colors.background, borderColor: colors.border },
                ]}
              >
                <Text style={[styles.modalActionText, { color: colors.text }]}>{t('settings.selectCustomDefaultAction')}</Text>
              </Pressable>
              <Pressable
                onPress={handleCustomActionDelete}
                style={({ pressed }) => [
                  styles.modalActionRow,
                  { backgroundColor: pressed ? colors.surfaceHover : colors.background, borderColor: colors.error },
                ]}
              >
                <Text style={[styles.modalActionText, { color: colors.error }]}>{t('settings.deleteCustomProfileAction')}</Text>
              </Pressable>
            </View>
            <Pressable
              onPress={closeCustomActionModal}
              style={({ pressed }) => [
                styles.modalCloseButton,
                { backgroundColor: pressed ? colors.surfaceHover : colors.background },
              ]}
            >
              <Text style={[styles.modalCloseText, { color: colors.text }]}>{t('common.cancel')}</Text>
            </Pressable>
          </View>
        </View>
      </Modal>

      <ConfirmModal
        visible={pendingCustomDelete !== null}
        config={
          pendingCustomDelete
            ? {
                title: t('settings.deleteCustomProfileConfirmTitle'),
                message: t('settings.deleteCustomProfileConfirmMessage', {
                  profile: pendingCustomDelete.profileName,
                }),
                confirm: t('settings.deleteCustomProfileAction'),
                destructive: true,
              }
            : null
        }
        onCancel={closeDeleteConfirmModal}
        onConfirm={() => {
          void handleDeleteCustomProfile();
        }}
      />

      <Modal
        visible={showCustomImportModal}
        transparent
        animationType="fade"
        onRequestClose={closeCustomImportModal}
      >
        <View style={styles.modalBackdrop}>
          <View style={[styles.modalCard, { backgroundColor: colors.surface, borderColor: colors.border }]}>
            <Text style={[styles.modalTitle, { color: colors.text }]}>
              {t('settings.addCustomCookie')}
            </Text>
            <Text style={[styles.inputLabel, { color: colors.textMuted }]}>
              {t('settings.customDomainOptionalInputLabel')}
            </Text>
            <TextInput
              value={customImportDomain}
              onChangeText={setCustomImportDomain}
              autoCapitalize="none"
              autoCorrect={false}
              placeholder={t('settings.customDomainOptionalPlaceholder')}
              placeholderTextColor={colors.textMuted}
              style={[
                styles.input,
                {
                  color: colors.text,
                  borderColor: colors.border,
                  backgroundColor: colors.background,
                },
              ]}
            />
            <View style={styles.modalActions}>
              <Pressable
                onPress={closeCustomImportModal}
                style={({ pressed }) => [
                  styles.modalActionButton,
                  { backgroundColor: pressed ? colors.surfaceHover : colors.background },
                ]}
              >
                <Text style={[styles.modalCloseText, { color: colors.text }]}>{t('common.cancel')}</Text>
              </Pressable>
              <Pressable
                onPress={() => {
                  void handleImportCustomCookie();
                }}
                style={({ pressed }) => [
                  styles.modalActionButton,
                  { backgroundColor: pressed ? colors.accent + 'bb' : colors.accent },
                ]}
              >
                <Text style={[styles.modalCloseText, { color: colors.background }]}>{t('settings.importCustomCookieAction')}</Text>
              </Pressable>
            </View>
          </View>
        </View>
      </Modal>

      <Modal
        visible={vaultMigrationVisible}
        transparent
        animationType="fade"
        onRequestClose={vaultMigrationFinished ? handleCloseVaultMigration : undefined}
      >
        <View style={styles.modalBackdrop}>
          <View style={[styles.modalCard, { backgroundColor: colors.surface, borderColor: colors.border }]}>
            <Text style={[styles.modalTitle, { color: colors.text }]}>
              {t('settings.vaultMigrate')}
            </Text>
            <Text style={[styles.modalDescription, { color: colors.textMuted }]}>
              {vaultMigrationProgress
                ? t('settings.vaultMigrationProgress', {
                    processed: vaultMigrationProgress.processed,
                    total: vaultMigrationProgress.total,
                  })
                : t('settings.vaultMigrationStarting')}
            </Text>
            {vaultMigrationProgress?.currentTitle ? (
              <Text style={[styles.modalDescription, { color: colors.textMuted }]} numberOfLines={1}>
                {vaultMigrationProgress.currentTitle}
              </Text>
            ) : null}
            {vaultMigrationProgress?.lastErrorCode ? (
              <>
                <Text style={[styles.modalDescription, { color: colors.error }]} numberOfLines={2}>
                  {vaultMigrationProgress.lastErrorCode}
                </Text>
                {vaultMigrationProgress.lastErrorDetail ? (
                  <Text style={[styles.modalDescription, { color: colors.error, fontSize: 11 }]} numberOfLines={4} selectable>
                    {vaultMigrationProgress.lastErrorDetail}
                  </Text>
                ) : null}
                {vaultMigrationProgress.failed > 0 ? (
                  <Text style={[styles.modalDescription, { color: colors.error }]}>
                    {`${vaultMigrationProgress.failed} failed, ${vaultMigrationProgress.succeeded} succeeded`}
                  </Text>
                ) : null}
              </>
            ) : null}
            {!vaultMigrationFinished ? (
              <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8, marginTop: 6 }}>
                <ActivityIndicator size="small" color={colors.accent} />
                <Text style={[styles.modalDescription, { color: colors.textMuted, marginTop: 0 }]}>
                  {t('settings.vaultMigrationKeepOpen')}
                </Text>
              </View>
            ) : null}
            <Pressable
              onPress={vaultMigrationFinished ? handleCloseVaultMigration : () => void handleCancelVaultMigration()}
              style={({ pressed }) => [
                styles.modalCloseButton,
                { backgroundColor: pressed ? colors.surfaceHover : colors.background },
              ]}
            >
              <Text style={[styles.modalCloseText, { color: colors.text }]}>
                {vaultMigrationFinished ? t('common.close') : t('common.cancel')}
              </Text>
            </Pressable>
          </View>
        </View>
      </Modal>

      <Modal
        visible={feedback !== null}
        transparent
        animationType="fade"
        onRequestClose={closeFeedback}
      >
        <View style={styles.modalBackdrop}>
          <View style={[styles.modalCard, { backgroundColor: colors.surface, borderColor: colors.border }]}>
            <Text style={[
              styles.modalTitle,
              { color: feedback?.tone === 'error' ? colors.error : colors.accent },
            ]}>
              {feedback?.title ?? ''}
            </Text>
            <Text style={[styles.modalDescription, { color: colors.text }]}>
              {feedback?.message ?? ''}
            </Text>
            <Pressable
              onPress={closeFeedback}
              style={({ pressed }) => [
                styles.modalCloseButton,
                { backgroundColor: pressed ? colors.surfaceHover : colors.background },
              ]}
            >
              <Text style={[styles.modalCloseText, { color: colors.text }]}>{t('common.ok')}</Text>
            </Pressable>
          </View>
        </View>
      </Modal>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
  },
  content: {
    flex: 1,
  },
  contentContainer: {
    padding: 16,
    paddingBottom: 40,
  },
  section: {
    marginBottom: 18,
  },
  sectionTitle: {
    fontSize: 13,
    fontWeight: '600',
    textTransform: 'uppercase',
    letterSpacing: 0.5,
    marginBottom: 8,
    marginLeft: 4,
  },
  // No horizontal padding here. SettingsItem already carries its own, so the two
  // stacked to push every label 52px from the screen edge — screen 20, card 16, row 16.
  // Children that are not rows supply their own inset instead.
  sectionContent: {
    borderRadius: 16,
    overflow: 'hidden',
    paddingVertical: 4,
    gap: 2,
  },
  autoPresetRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
    borderWidth: 1,
    borderRadius: 10,
    paddingHorizontal: 12,
    paddingVertical: 12,
    marginTop: 8,
  },
  autoPresetLabel: { fontSize: 15, flex: 1 },
  modalBackdrop: {
    flex: 1,
    backgroundColor: 'rgba(0,0,0,0.45)',
    justifyContent: 'center',
    padding: 20,
  },
  modalCard: {
    borderRadius: 16,
    borderWidth: 1,
    maxHeight: '75%',
    padding: 16,
  },
  modalTitle: {
    fontSize: 16,
    fontWeight: '700',
    marginBottom: 12,
  },
  modalList: {
    maxHeight: 260,
  },
  modalItem: {
    borderWidth: 1,
    borderRadius: 10,
    paddingVertical: 10,
    paddingHorizontal: 12,
    marginBottom: 8,
  },
  modalItemTitle: {
    fontSize: 14,
    fontWeight: '500',
  },
  modalDescription: {
    fontSize: 13,
    marginBottom: 12,
  },
  modalActionsColumn: {
    gap: 8,
  },
  modalActionRow: {
    borderWidth: 1,
    borderRadius: 10,
    paddingVertical: 12,
    paddingHorizontal: 12,
  },
  modalActionText: {
    fontSize: 14,
    fontWeight: '600',
  },
  modalCloseButton: {
    marginTop: 8,
    borderRadius: 10,
    paddingVertical: 10,
    alignItems: 'center',
  },
  modalCloseText: {
    fontSize: 14,
    fontWeight: '600',
  },
  inputLabel: {
    fontSize: 12,
    marginBottom: 8,
  },
  input: {
    borderWidth: 1,
    borderRadius: 10,
    paddingHorizontal: 12,
    paddingVertical: 10,
    fontSize: 14,
  },
  modalActions: {
    marginTop: 12,
    flexDirection: 'row',
    gap: 8,
  },
  modalActionButton: {
    flex: 1,
    borderRadius: 10,
    paddingVertical: 10,
    alignItems: 'center',
  },
});
