import { Ionicons } from '@expo/vector-icons';
import { Image } from 'expo-image';
import React, { useEffect, useState } from 'react';
import { StyleSheet, View } from 'react-native';

import { getMemeFaceCrop } from '@/src/api';
import { useTheme } from '@/src/theme';

/**
 * Crops already fetched while the app runs. A private meme's crop is data held here in
 * memory, never a file: the native side does not write one outside the vault.
 */
const crops = new Map<string, string | null>();

/** Forgets the crops of faces that may have moved into or out of the vault. */
export function forgetFaceCrops(faceIds: string[]) {
  faceIds.forEach((id) => crops.delete(id));
}

export function FaceCrop({ itemId, faceId, size }: { itemId: string; faceId: string; size: number }) {
  const { colors } = useTheme();
  const [uri, setUri] = useState<string | null>(crops.get(faceId) ?? null);

  useEffect(() => {
    if (crops.has(faceId)) {
      setUri(crops.get(faceId) ?? null);
      return;
    }
    let live = true;
    getMemeFaceCrop(itemId, faceId)
      .then((next) => {
        crops.set(faceId, next);
        if (live) setUri(next);
      })
      .catch(() => undefined);
    return () => {
      live = false;
    };
  }, [faceId, itemId]);

  return (
    <View style={[styles.frame, { width: size, height: size, backgroundColor: colors.surface }]}>
      {uri ? (
        <Image source={{ uri }} style={StyleSheet.absoluteFill} contentFit="cover" recyclingKey={faceId} />
      ) : (
        <Ionicons name="person-outline" size={size / 3} color={colors.textMuted} />
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  frame: { borderRadius: 10, overflow: 'hidden', alignItems: 'center', justifyContent: 'center' },
});
