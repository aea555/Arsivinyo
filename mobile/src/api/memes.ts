import type { EventSubscription } from 'expo-modules-core';
import { Platform } from 'react-native';

import LocalDownloaderModule, {
  addMemesChangedListener,
  type LocalMeme,
  type LocalMemeFacet,
  type LocalMemeFilter,
  type LocalMemeLibrary,
  type LocalMemePerson,
  type LocalMemeSource,
  type LocalMemeTag,
} from '../native/localDownloader';

export type {
  LocalMeme,
  LocalMemeFacet,
  LocalMemeFilter,
  LocalMemeLibrary,
  LocalMemePerson,
  LocalMemeSource,
  LocalMemeTag,
};

/**
 * The meme collection: `shared/memes/CONTRACT.md`.
 *
 * Search runs natively, so the phone has one Turkish folding and it is the one the shared
 * vectors test. Nothing here logs a tag, a caption or an account.
 */

/** The facets in the order the screens show them. */
export const MEME_FACETS: LocalMemeFacet[] = ['reaction', 'vibe', 'emotion', 'action', 'context'];

export const EMPTY_MEME_LIBRARY: LocalMemeLibrary = {
  items: [],
  tags: [],
  people: [],
  vaultUnlocked: false,
  hasPrivate: false,
  askForTags: true,
};

export function isMemesSupported(): boolean {
  return Platform.OS === 'android';
}

export async function listMemes(): Promise<LocalMemeLibrary> {
  if (!isMemesSupported()) return EMPTY_MEME_LIBRARY;
  return { ...EMPTY_MEME_LIBRARY, ...(await LocalDownloaderModule.listMemes()) };
}

export function listenMemesChanged(listener: () => void): EventSubscription {
  return addMemesChangedListener(listener);
}

export const searchMemes = (query: string, filter: LocalMemeFilter = {}) =>
  LocalDownloaderModule.searchMemes(query, filter);
export const getMemeThumbnail = (id: string) => LocalDownloaderModule.memeThumbnail(id);
export const getMemeSuggestions = (id: string) => LocalDownloaderModule.memeSuggestions(id);
export const createMemeTag = (name: string, facets: LocalMemeFacet[] = []) =>
  LocalDownloaderModule.createMemeTag(name, facets);
export const createMemePerson = (name: string) => LocalDownloaderModule.createMemePerson(name);
export const setMemeTagFacets = (tagId: string, facets: LocalMemeFacet[]) =>
  LocalDownloaderModule.setMemeTagFacets(tagId, facets);
export const renameMemeTag = (tagId: string, name: string) => LocalDownloaderModule.renameMemeTag(tagId, name);
export const deleteMemeTag = (tagId: string) => LocalDownloaderModule.deleteMemeTag(tagId);
export const labelMemes = LocalDownloaderModule.labelMemes.bind(LocalDownloaderModule);
export const setMemesPrivate = (ids: string[], makePrivate: boolean) =>
  LocalDownloaderModule.setMemesPrivate(ids, makePrivate);
export const removeMemes = (ids: string[]) => LocalDownloaderModule.removeMemes(ids);
export const importMemes = () => LocalDownloaderModule.importMemes();
export const setMemeAskForTags = (enabled: boolean) => LocalDownloaderModule.setMemeAskForTags(enabled);
export const dismissMemePrompt = (id: string) => LocalDownloaderModule.dismissMemePrompt(id);
export const sendMemeToPeer = (fingerprint: string, id: string) =>
  LocalDownloaderModule.pairingSendMeme(fingerprint, id);
