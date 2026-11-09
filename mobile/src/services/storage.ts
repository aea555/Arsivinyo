import AsyncStorage from '@react-native-async-storage/async-storage';

// Storage keys
const KEYS = {
    DOWNLOAD_LOCATION: '@arsivinyo_download_location',
    PRIVATE_VAULT_SORT: '@arsivinyo_private_vault_sort_v1',
    PRIVATE_VAULT_SHOW_TAGS: '@arsivinyo_private_vault_show_tags_v1',
    SUBTITLE_STYLE: '@arsivinyo_subtitle_style_v1',
} as const;

/** How the player draws subtitles: their size against the default, and whether in bold. */
export interface SubtitleStyle {
    scale: number;
    bold: boolean;
}

/**
 * The sizes offered, smallest first: small, normal, large, largest. 1 is the size subtitles
 * had before there was a choice, which is the smallest now.
 */
export const SUBTITLE_SCALES = [1, 1.3, 1.6, 2] as const;
const DEFAULT_SUBTITLE_SIZE = 1;
export const DEFAULT_SUBTITLE_STYLE: SubtitleStyle = { scale: SUBTITLE_SCALES[DEFAULT_SUBTITLE_SIZE], bold: false };

/** Kept as which of the sizes, not as a scale, so the sizes themselves can change. */
export async function getSubtitleStyle(): Promise<SubtitleStyle> {
    const stored = await getItem<{ size?: number; bold?: boolean } | null>(KEYS.SUBTITLE_STYLE, null);
    return {
        scale: SUBTITLE_SCALES[stored?.size as number] ?? DEFAULT_SUBTITLE_STYLE.scale,
        bold: stored?.bold === true,
    };
}

export async function setSubtitleStyle(style: SubtitleStyle): Promise<boolean> {
    const size = (SUBTITLE_SCALES as readonly number[]).indexOf(style.scale);
    return setItem(KEYS.SUBTITLE_STYLE, { size: size < 0 ? DEFAULT_SUBTITLE_SIZE : size, bold: style.bold });
}

export type PrivateVaultSortMode = 'alpha' | 'time' | 'size' | 'duration';
export type PrivateVaultSortDirection = 'asc' | 'desc';
export interface PrivateVaultSortPreference {
    mode: PrivateVaultSortMode;
    direction: PrivateVaultSortDirection;
}

const DEFAULT_PRIVATE_VAULT_SORT: PrivateVaultSortPreference = {
    mode: 'alpha',
    direction: 'asc',
};

const PRIVATE_VAULT_SORT_MODES: readonly PrivateVaultSortMode[] = ['alpha', 'time', 'size', 'duration'];
const PRIVATE_VAULT_SORT_DIRECTIONS: readonly PrivateVaultSortDirection[] = ['asc', 'desc'];

function sanitizePrivateVaultSort(value: unknown): PrivateVaultSortPreference {
    if (!value || typeof value !== 'object') return DEFAULT_PRIVATE_VAULT_SORT;
    const v = value as Partial<PrivateVaultSortPreference>;
    const mode = PRIVATE_VAULT_SORT_MODES.includes(v.mode as PrivateVaultSortMode)
        ? (v.mode as PrivateVaultSortMode)
        : DEFAULT_PRIVATE_VAULT_SORT.mode;
    const direction = PRIVATE_VAULT_SORT_DIRECTIONS.includes(v.direction as PrivateVaultSortDirection)
        ? (v.direction as PrivateVaultSortDirection)
        : DEFAULT_PRIVATE_VAULT_SORT.direction;
    return { mode, direction };
}

/**
 * Generic get function with type safety
 */
async function getItem<T>(key: string, defaultValue: T): Promise<T> {
    try {
        const value = await AsyncStorage.getItem(key);
        if (value === null) {
            return defaultValue;
        }
        return JSON.parse(value) as T;
    } catch (error) {
        console.error(`Failed to get ${key}:`, error);
        return defaultValue;
    }
}

/**
 * Generic set function
 */
async function setItem<T>(key: string, value: T): Promise<boolean> {
    try {
        await AsyncStorage.setItem(key, JSON.stringify(value));
        return true;
    } catch (error) {
        console.error(`Failed to set ${key}:`, error);
        return false;
    }
}

// ============================================
// Download Location
// ============================================

/**
 * Get the saved download location path
 */
export async function getDownloadLocation(): Promise<string | null> {
    return getItem<string | null>(KEYS.DOWNLOAD_LOCATION, null);
}

/**
 * Save the download location path
 */
export async function setDownloadLocation(path: string): Promise<boolean> {
    return setItem(KEYS.DOWNLOAD_LOCATION, path);
}

// ============================================
// Private Vault Sort Preference
// ============================================

export async function getPrivateVaultSort(): Promise<PrivateVaultSortPreference> {
    const raw = await getItem<unknown>(KEYS.PRIVATE_VAULT_SORT, null);
    return sanitizePrivateVaultSort(raw);
}

export async function setPrivateVaultSort(value: PrivateVaultSortPreference): Promise<boolean> {
    return setItem(KEYS.PRIVATE_VAULT_SORT, sanitizePrivateVaultSort(value));
}

export async function getPrivateVaultShowTags(): Promise<boolean> {
    const raw = await getItem<unknown>(KEYS.PRIVATE_VAULT_SHOW_TAGS, true);
    return typeof raw === 'boolean' ? raw : true;
}

export async function setPrivateVaultShowTags(value: boolean): Promise<boolean> {
    return setItem(KEYS.PRIVATE_VAULT_SHOW_TAGS, value);
}

/**
 * Clear all app storage (for debugging/reset)
 */
export async function clearAllStorage(): Promise<boolean> {
    try {
        const allKeys = Object.values(KEYS);
        await AsyncStorage.multiRemove(allKeys);
        return true;
    } catch (error) {
        console.error('Failed to clear storage:', error);
        return false;
    }
}
