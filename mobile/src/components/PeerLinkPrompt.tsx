import React, { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';

import {
  clearPeerUrl,
  getPairingState,
  hasPairedDevices,
  isPairingSupported,
  startPairing,
  startQuickLocalDownloadWithUrl,
  subscribeToPairingState,
} from '@/src/api';

import { ConfirmModal } from './ConfirmModal';

/**
 * A link a paired device sent, offered wherever the user is.
 *
 * It used to live on the Devices screen, so a link sat unseen until that screen was
 * opened. Here it shows over anything. It also starts pairing at launch when a device is
 * already paired, so that device can reach the phone without Devices being visited first;
 * with nothing paired, nothing listens.
 *
 * The prompt hides the moment it is answered rather than waiting for the native state to
 * come back: it once stayed on screen because that state never did.
 */
export function PeerLinkPrompt() {
  const { t } = useTranslation();
  const [link, setLink] = useState<{ url: string; audio: boolean } | null>(null);

  useEffect(() => {
    if (!isPairingSupported()) return;
    let active = true;
    const subscription = subscribeToPairingState((state) => {
      if (!active) return;
      setLink(state.peerUrl ? { url: state.peerUrl, audio: state.peerMediaKind === 'audio' } : null);
    });
    void (async () => {
      if (await hasPairedDevices()) {
        await startPairing().catch(() => false);
        const state = await getPairingState();
        if (active && state.peerUrl) {
          setLink({ url: state.peerUrl, audio: state.peerMediaKind === 'audio' });
        }
      }
    })();
    return () => {
      active = false;
      subscription.remove();
    };
  }, []);

  const dismiss = () => {
    setLink(null);
    void clearPeerUrl();
  };

  return (
    <ConfirmModal
      visible={link !== null}
      config={{
        title: t('devices.peerUrlTitle'),
        message: t('devices.peerUrlBody'),
        confirm: t('devices.peerUrlAccept'),
        destructive: false,
      }}
      onConfirm={() => {
        const current = link;
        dismiss();
        if (current) void startQuickLocalDownloadWithUrl(current.url, current.audio ? 'audio' : 'video');
      }}
      onCancel={dismiss}
    />
  );
}
