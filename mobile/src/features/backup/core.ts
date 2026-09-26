/**
 * Backup secret rules and passphrase handling.
 *
 * Pure logic with no platform imports, so it runs under `node --test` — the same split as
 * `src/features/audioPresets/core.ts`. Everything here decides what the user is allowed to
 * type; the native side never validates, because there is no adversary who can bypass this
 * UI. The person choosing the secret is the person it protects.
 */

/** The container's file extension, mirrored from `BackupFormat.FILE_EXTENSION`. */
export const BACKUP_EXTENSION = 'avsbck';

/** Wire values, mirrored from `BackupFormat`. Never rename one in place. */
export const BACKUP_SECTIONS = ['vault', 'music', 'memes', 'settings', 'cookies'] as const;
export type BackupSectionId = (typeof BACKUP_SECTIONS)[number];

export type SecretKind = 'password' | 'passphrase';

/**
 * Minimum password length.
 *
 * Deliberately a length floor with no character-class rules. Composition requirements are
 * weaker than they look — `Password1!` satisfies "symbol, digit, capital" and is trivially
 * guessable, while a long ordinary phrase satisfies none and is far stronger. Length
 * dominates, so length is what is required.
 */
export const MIN_PASSWORD_LENGTH = 14;

export const MIN_PASSPHRASE_WORDS = 4;
export const MAX_PASSPHRASE_WORDS = 12;

/** Separators offered for passphrases. The choice is the user's; all are equally fine. */
export const PASSPHRASE_SEPARATORS = ['-', ' ', '.', '_', '+'] as const;
export type PassphraseSeparator = (typeof PASSPHRASE_SEPARATORS)[number];

export const DEFAULT_SEPARATOR: PassphraseSeparator = '-';

/**
 * A handful of choices common enough to be in any attacker's first thousand guesses. This
 * is not a serious blocklist — it exists to catch the "I'll just type something" case, and
 * the length floor does the real work.
 */
const OBVIOUS_SECRETS = [
    'password',
    'passphrase',
    '123456789',
    'qwertyuiop',
    'letmeinplease',
    'arsivinyo',
];

export interface SecretProblem {
    /** Stable key for translation lookup; never shown raw. */
    code:
        | 'tooShort'
        | 'obvious'
        | 'tooFewWords'
        | 'tooManyWords'
        | 'blankWord'
        | 'wordContainsSeparator'
        | 'mismatch';
    /** Values for interpolation into the translated message. */
    values?: Record<string, string | number>;
}

export interface SecretValidation {
    ok: boolean;
    problems: SecretProblem[];
}

export function validatePassword(value: string): SecretValidation {
    const problems: SecretProblem[] = [];
    if (value.length < MIN_PASSWORD_LENGTH) {
        problems.push({ code: 'tooShort', values: { min: MIN_PASSWORD_LENGTH } });
    }
    if (isObvious(value)) {
        problems.push({ code: 'obvious' });
    }
    return { ok: problems.length === 0, problems };
}

function isObvious(value: string): boolean {
    const normalized = value.toLowerCase().replace(/[^a-z0-9]/g, '');
    if (normalized.length === 0) return false;
    return OBVIOUS_SECRETS.some((entry) => normalized.includes(entry));
}

/**
 * Words are validated individually so the UI can point at the offending one.
 *
 * A word containing the separator is rejected rather than silently accepted: the passphrase
 * is joined with that separator, so `two words` split on a space would silently become two
 * entries and change the secret the user thought they chose.
 */
export function validatePassphrase(
    words: readonly string[],
    separator: string,
): SecretValidation {
    const problems: SecretProblem[] = [];
    const filled = words.filter((word) => word.trim().length > 0);

    if (filled.length < MIN_PASSPHRASE_WORDS) {
        problems.push({ code: 'tooFewWords', values: { min: MIN_PASSPHRASE_WORDS } });
    }
    if (filled.length > MAX_PASSPHRASE_WORDS) {
        problems.push({ code: 'tooManyWords', values: { max: MAX_PASSPHRASE_WORDS } });
    }
    if (words.length > 0 && filled.length !== words.length) {
        problems.push({ code: 'blankWord' });
    }
    if (separator.length > 0 && filled.some((word) => word.includes(separator))) {
        problems.push({ code: 'wordContainsSeparator', values: { separator } });
    }

    return { ok: problems.length === 0, problems };
}

/** Trailing/leading whitespace is stripped, or an invisible space would change the key. */
export function joinPassphrase(words: readonly string[], separator: string): string {
    return words
        .map((word) => word.trim())
        .filter((word) => word.length > 0)
        .join(separator);
}

export type StrengthLevel = 'weak' | 'fair' | 'strong' | 'excellent';

export interface StrengthEstimate {
    level: StrengthLevel;
    /** Rough bits of entropy. Honest about being an estimate, not a measurement. */
    bits: number;
}

/**
 * A deliberately rough estimate, used only to colour a bar.
 *
 * For a typed password it counts the character classes actually used, which overstates
 * anything memorable — real text carries nowhere near `log2(alphabet) * length` bits. The
 * thresholds are therefore set high enough that the overstatement does not matter.
 */
export function estimatePasswordStrength(value: string): StrengthEstimate {
    if (value.length === 0) return { level: 'weak', bits: 0 };

    let alphabet = 0;
    if (/[a-z]/.test(value)) alphabet += 26;
    if (/[A-Z]/.test(value)) alphabet += 26;
    if (/[0-9]/.test(value)) alphabet += 10;
    if (/[^a-zA-Z0-9]/.test(value)) alphabet += 32;

    const raw = value.length * Math.log2(Math.max(alphabet, 2));
    // Repetition is the most common way a long password is not really long.
    const distinct = new Set(value).size;
    const variety = Math.min(1, distinct / Math.max(8, value.length * 0.5));
    const bits = Math.round(raw * variety);

    return { level: levelFor(isObvious(value) ? Math.min(bits, 20) : bits), bits };
}

/**
 * Entropy for a passphrase depends entirely on where the words came from.
 *
 * Generated words are drawn uniformly from [WORDLIST], so each contributes exactly
 * `log2(WORDLIST.length)` bits. Words a person chose carry far less — people pick lyrics,
 * names and collocations — so those are counted at a fraction of that. Reporting both the
 * same way would tell the user a self-chosen phrase is as strong as a generated one, which
 * is the misleading part worth avoiding.
 */
export function estimatePassphraseStrength(
    words: readonly string[],
    generated: boolean,
): StrengthEstimate {
    const count = words.filter((word) => word.trim().length > 0).length;
    if (count === 0) return { level: 'weak', bits: 0 };

    const perWord = generated ? Math.log2(WORDLIST.length) : SELF_CHOSEN_BITS_PER_WORD;
    const bits = Math.round(count * perWord);
    return { level: levelFor(bits), bits };
}

/**
 * Conservative allowance for a word a person thought of. Published estimates for
 * user-chosen words in a phrase land well under 10 bits once collocations are accounted
 * for; erring low here means the meter under-promises rather than over-promises.
 */
const SELF_CHOSEN_BITS_PER_WORD = 6;

function levelFor(bits: number): StrengthLevel {
    if (bits < 45) return 'weak';
    if (bits < 65) return 'fair';
    if (bits < 90) return 'strong';
    return 'excellent';
}

/**
 * 256 short, unambiguous words — exactly 8 bits each, so a generated phrase's entropy is
 * trivially `8 x words`. Chosen to be easy to retype: no homophones, no words under three
 * letters, nothing that looks like another entry with one letter changed.
 */
export const WORDLIST: readonly string[] = [
    'able', 'acid', 'acre', 'aged', 'alarm', 'album', 'alert', 'alley',
    'amber', 'anchor', 'angle', 'ankle', 'apple', 'april', 'arena', 'armor',
    'arrow', 'attic', 'autumn', 'awake', 'bacon', 'badge', 'bagel', 'baker',
    'balcony', 'bamboo', 'banjo', 'barley', 'basil', 'basket', 'battery', 'beacon',
    'beetle', 'bench', 'berry', 'bishop', 'bison', 'blanket', 'blossom', 'boiler',
    'bonus', 'border', 'bottle', 'boulder', 'bracket', 'branch', 'brass', 'bridge',
    'bronze', 'brush', 'bucket', 'buffalo', 'bundle', 'bunker', 'burden', 'butter',
    'cabin', 'cable', 'cactus', 'camel', 'candle', 'canvas', 'canyon', 'carbon',
    'cargo', 'carpet', 'castle', 'cattle', 'cedar', 'cellar', 'cement', 'census',
    'chapel', 'charm', 'cherry', 'chess', 'chimney', 'cider', 'cinema', 'circus',
    'citrus', 'clamp', 'clay', 'clever', 'cliff', 'clock', 'clover', 'cobalt',
    'cocoa', 'coffee', 'collar', 'comet', 'compass', 'copper', 'coral', 'cotton',
    'cougar', 'county', 'cousin', 'coyote', 'crane', 'crater', 'crayon', 'cricket',
    'crimson', 'crystal', 'cushion', 'cymbal', 'dagger', 'dahlia', 'daisy', 'dancer',
    'dawn', 'decoy', 'denim', 'desert', 'diamond', 'diesel', 'dolphin', 'domino',
    'donkey', 'dragon', 'drum', 'dune', 'eagle', 'echo', 'eclipse', 'elbow',
    'ember', 'emerald', 'empire', 'engine', 'envoy', 'equal', 'ethics', 'exile',
    'fabric', 'falcon', 'fancy', 'fathom', 'feather', 'fennel', 'ferry', 'fiber',
    'fiddle', 'figure', 'filter', 'flame', 'flask', 'fleet', 'flint', 'floral',
    'flute', 'forest', 'fossil', 'fountain', 'fox', 'frost', 'galaxy', 'gallery',
    'garden', 'garlic', 'gazelle', 'ginger', 'glacier', 'glass', 'globe', 'glove',
    'granite', 'grape', 'gravel', 'grove', 'guitar', 'gypsum', 'hammer', 'harbor',
    'harvest', 'hazel', 'helmet', 'heron', 'hickory', 'hollow', 'honey', 'hornet',
    'hostel', 'ivory', 'jacket', 'jaguar', 'jasmine', 'jelly', 'jewel', 'jungle',
    'kettle', 'kitten', 'koala', 'ladder', 'lagoon', 'lantern', 'laurel', 'lemon',
    'lentil', 'leopard', 'lilac', 'linen', 'lizard', 'lobster', 'locket', 'lotus',
    'lumber', 'lunar', 'magnet', 'mahogany', 'mango', 'maple', 'marble', 'marina',
    'meadow', 'melon', 'mercury', 'meteor', 'mint', 'mirror', 'monkey', 'mosaic',
    'moss', 'motor', 'muffin', 'mulberry', 'mustard', 'nectar', 'needle', 'nickel',
    'noble', 'nutmeg', 'oasis', 'oatmeal', 'ocean', 'octopus', 'olive', 'onyx',
    'opal', 'orbit', 'orchid', 'oregano', 'otter', 'oyster', 'paddle', 'palace',
    'pancake', 'panther', 'papaya', 'parrot', 'parsley', 'pastel', 'peach', 'pebble',
];

/**
 * Turn raw random bytes into uniform indices in `[0, max)`.
 *
 * Naively taking `byte % max` biases the result whenever `max` does not divide 256: with
 * `max = 200`, the values 0–55 would each be reachable from two bytes and 56–199 from only
 * one, making the first quarter of the list ~2x likelier. Rejection sampling removes that —
 * bytes at or above the largest multiple of `max` are discarded rather than folded.
 *
 * With the shipped 256-word list nothing is ever rejected, because 256 divides 256 exactly.
 * The guard exists so that changing the list size cannot silently introduce bias.
 *
 * @returns the indices, or `null` if [bytes] ran out before [count] were accepted — the
 * caller must then fetch more entropy rather than reuse or pad what it has.
 */
export function uniformIndicesFromBytes(
    bytes: readonly number[],
    count: number,
    max: number,
): number[] | null {
    if (max <= 0 || max > 256) {
        throw new RangeError(`max must be within 1..256, got ${max}`);
    }
    const limit = Math.floor(256 / max) * max;
    const indices: number[] = [];
    for (const byte of bytes) {
        if (indices.length === count) break;
        // Anything outside 0..255 is not a byte and must not be folded into range.
        if (!Number.isInteger(byte) || byte < 0 || byte > 255) {
            throw new RangeError(`not a byte: ${byte}`);
        }
        if (byte < limit) indices.push(byte % max);
    }
    return indices.length === count ? indices : null;
}

/**
 * Cryptographically random words, when a platform RNG is available.
 *
 * [random] is injected so this file stays platform-free and the tests can be deterministic.
 * Callers must pass a real CSPRNG — `Math.random` is not one, and a passphrase generated
 * from it is only as unguessable as the seed.
 */
export function generatePassphraseWords(
    count: number,
    random: (max: number) => number,
): string[] {
    const clamped = Math.max(MIN_PASSPHRASE_WORDS, Math.min(MAX_PASSPHRASE_WORDS, count));
    const words: string[] = [];
    for (let i = 0; i < clamped; i += 1) {
        words.push(WORDLIST[random(WORDLIST.length)]);
    }
    return words;
}

/** Bits per generated word, exposed so the UI can explain the number it shows. */
export function bitsPerGeneratedWord(): number {
    return Math.log2(WORDLIST.length);
}

/** `arsivinyo-backup-2026-08-11.avsbck` */
export function defaultBackupFileName(now: Date): string {
    const pad = (value: number) => String(value).padStart(2, '0');
    const stamp = `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`;
    return `arsivinyo-backup-${stamp}.${BACKUP_EXTENSION}`;
}

/** Human-readable byte size for the import preview. */
export function formatBytes(bytes: number): string {
    if (!Number.isFinite(bytes) || bytes <= 0) return '0 B';
    const units = ['B', 'KB', 'MB', 'GB', 'TB'];
    const index = Math.min(units.length - 1, Math.floor(Math.log(bytes) / Math.log(1024)));
    const value = bytes / 1024 ** index;
    return `${value >= 10 || index === 0 ? Math.round(value) : value.toFixed(1)} ${units[index]}`;
}
