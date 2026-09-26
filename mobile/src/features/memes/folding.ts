/**
 * Turkish-aware folding for autocomplete while typing a label.
 *
 * Search itself runs natively (MemeStore.fold, held to shared/memes/VECTORS.json); this is
 * only for matching what is being typed against the names already on screen, so it does not
 * need a round trip per keystroke. It folds the same way: I→ı, İ→i, then plain letters.
 */
const PLAIN: Record<string, string> = { ı: 'i', ş: 's', ğ: 'g', ç: 'c', ö: 'o', ü: 'u', â: 'a', î: 'i', û: 'u' };

export function foldForMatching(text: string): string {
  return text
    .trim()
    .replace(/I/g, 'ı')
    .replace(/İ/g, 'i')
    .toLowerCase()
    .replace(/[ışğçöüâîû]/g, (letter) => PLAIN[letter] ?? letter);
}
