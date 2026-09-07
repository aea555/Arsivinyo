import type { EventSubscription } from 'expo-modules-core';
import { Platform } from 'react-native';
import LocalDownloaderModule, {
  addPairingStateListener,
  type LocalDiscoveredDevice,
  type LocalPairedDevice,
  type LocalPairingState,
  type LocalPeerItem,
} from '../native/localDownloader';

export type {
  LocalDiscoveredDevice,
  LocalPairedDevice,
  LocalPairingState,
  LocalPeerItem,
};

/**
 * Pairing two devices you own, on the same network.
 *
 * Nothing here starts on import. The first call creates this device's identity and puts a
 * socket on the network, so a screen that is never opened costs nothing.
 */

function ensureAndroid(): void {
  if (Platform.OS !== 'android') {
    throw new Error('Device pairing is Android-only in this release.');
  }
}

/** An empty state, for a platform or a device where pairing cannot run. */
export const EMPTY_PAIRING_STATE: LocalPairingState = {
  fingerprint: '',
  deviceName: '',
  ready: false,
  listening: false,
  port: 0,
  pairingMode: false,
  pendingCode: '',
  pendingName: '',
  message: '',
  transferDone: 0,
  transferTotal: 0,
  peers: [],
  discovered: [],
  listingFrom: '',
  listing: [],
  peerUrl: '',
  peerMediaKind: '',
};

export function isPairingSupported(): boolean {
  return Platform.OS === 'android';
}

/**
 * Fill in anything the native side left out.
 *
 * The screen reads these fields unconditionally, so a map missing one crashes the render
 * rather than degrading. That has happened once already — the event and the function built
 * the state separately and the event omitted two keys — so the boundary now guarantees the
 * shape the type promises instead of trusting it.
 */
function normalise(state: Partial<LocalPairingState> | null | undefined): LocalPairingState {
  return { ...EMPTY_PAIRING_STATE, ...(state ?? {}) };
}

export async function getPairingState(): Promise<LocalPairingState> {
  if (!isPairingSupported()) return EMPTY_PAIRING_STATE;
  try {
    return normalise(await LocalDownloaderModule.pairingState());
  } catch {
    return EMPTY_PAIRING_STATE;
  }
}

/** Listen and announce. Safe to call again; it does not restart a running service. */
export async function startPairing(): Promise<boolean> {
  ensureAndroid();
  return LocalDownloaderModule.pairingStart();
}

export async function stopPairing(): Promise<boolean> {
  if (!isPairingSupported()) return false;
  return LocalDownloaderModule.pairingStop();
}

/**
 * Open the window in which an unknown device may present itself.
 *
 * It closes on its own, so a phone left alone does not stay open to the first key that
 * asks.
 */
export async function beginPairing(seconds = 120): Promise<boolean> {
  ensureAndroid();
  return LocalDownloaderModule.pairingBeginPairing(seconds);
}

export async function cancelPairing(): Promise<boolean> {
  ensureAndroid();
  return LocalDownloaderModule.pairingCancelPairing();
}

/**
 * The user confirmed the six digits match on both screens.
 *
 * Returns false if the pairing could not be stored, which is reported rather than
 * swallowed: a device that looks paired but is forgotten on the next start is worse than
 * one that says it failed now.
 */
export async function confirmPairing(): Promise<boolean> {
  ensureAndroid();
  return LocalDownloaderModule.pairingConfirm();
}

/** Connect to a device found on the network, or one entered by address. */
export async function connectToDevice(host: string, port: number): Promise<boolean> {
  ensureAndroid();
  return LocalDownloaderModule.pairingConnect(host, port);
}

/** Forget a device. Local and one-sided: no message is sent, by design. */
export async function forgetDevice(fingerprint: string): Promise<boolean> {
  ensureAndroid();
  return LocalDownloaderModule.pairingForget(fingerprint);
}

export async function setDeviceName(name: string): Promise<boolean> {
  ensureAndroid();
  return LocalDownloaderModule.pairingSetDeviceName(name);
}

/** Ask a paired device for its music library. The answer arrives in the state. */
export async function browseDevice(fingerprint: string): Promise<boolean> {
  ensureAndroid();
  return LocalDownloaderModule.pairingBrowse(fingerprint);
}

/** Pull one track from a paired device. */
export async function fetchFromDevice(fingerprint: string, id: string): Promise<boolean> {
  ensureAndroid();
  return LocalDownloaderModule.pairingFetch(fingerprint, id);
}

/** Send one track from this phone's library to a paired device. */
export async function sendToDevice(fingerprint: string, songId: string): Promise<boolean> {
  ensureAndroid();
  return LocalDownloaderModule.pairingSend(fingerprint, songId);
}

/** Hand a URL to a paired device. It decides whether to download it. */
export async function sendUrlToDevice(
  fingerprint: string,
  url: string,
  mediaKind: 'audio' | 'video' = 'audio'
): Promise<boolean> {
  ensureAndroid();
  return LocalDownloaderModule.pairingSendUrl(fingerprint, url, mediaKind);
}

export async function cancelTransfer(fingerprint: string): Promise<boolean> {
  ensureAndroid();
  return LocalDownloaderModule.pairingCancelTransfer(fingerprint);
}

/** Dismiss a URL a peer sent, once the user has accepted or ignored it. */
export async function clearPeerUrl(): Promise<boolean> {
  if (!isPairingSupported()) return false;
  return LocalDownloaderModule.pairingClearPeerUrl();
}

/** Fires whenever anything the pairing screen renders has changed. */
export function subscribeToPairingState(
  listener: (state: LocalPairingState) => void
): EventSubscription {
  if (!isPairingSupported()) return { remove: () => undefined };
  return addPairingStateListener((state) => listener(normalise(state)));
}
