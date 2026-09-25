// Tests for the pure preset logic.
//
// Run with `npm run test:presets`. No test framework: Node runs TypeScript directly,
// and core.ts deliberately imports nothing from React Native so it can be exercised
// here rather than only on a device.

import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';

import {
  applyBuiltInOverrides,
  BUILT_IN_PRESETS,
  buildParamsSpec,
  configWithoutPreset,
  DEFAULT_AUTO_PRESET_CONFIG,
  DEFAULT_PARAMS,
  isBuiltInPresetId,
  isValidAutoPresetConfig,
  outputsPerDownload,
  PARAM_RANGES,
  parseAutoPresetConfig,
  parseCustomPreset,
  sanitizeParams,
  serializeAutoPresetConfig,
  type AudioPreset,
} from './core.ts';

// ---------------------------------------------------------------------------
// sanitizeParams
// ---------------------------------------------------------------------------

test('sanitizeParams fills every field from an empty input', () => {
  assert.deepEqual(sanitizeParams(undefined), DEFAULT_PARAMS);
  assert.deepEqual(sanitizeParams({}), DEFAULT_PARAMS);
});

test('sanitizeParams clamps out-of-range values instead of passing them through', () => {
  const p = sanitizeParams({ rate: 99, reverbRoom: 5, bassGainDb: -500 });
  assert.equal(p.rate, PARAM_RANGES.rate.max);
  assert.equal(p.reverbRoom, PARAM_RANGES.reverbRoom.max);
  assert.equal(p.bassGainDb, PARAM_RANGES.bassGainDb.min);
});

test('sanitizeParams rejects values that are not finite numbers', () => {
  // A hand-edited or corrupted store must not be able to put NaN into the DSP.
  const p = sanitizeParams({
    rate: NaN,
    reverbMix: Infinity,
    bassGainDb: 'loud' as unknown as number,
  });
  assert.equal(p.rate, DEFAULT_PARAMS.rate);
  assert.ok(Number.isFinite(p.reverbMix));
  assert.equal(p.bassGainDb, DEFAULT_PARAMS.bassGainDb);
});

test('sanitizeParams keeps limiterEnabled as a real boolean', () => {
  assert.equal(sanitizeParams({ limiterEnabled: false }).limiterEnabled, false);
  // Anything non-boolean falls back rather than being coerced.
  assert.equal(
    sanitizeParams({ limiterEnabled: 'yes' as unknown as boolean }).limiterEnabled,
    DEFAULT_PARAMS.limiterEnabled
  );
});

// ---------------------------------------------------------------------------
// buildParamsSpec — what actually reaches the native DSP
// ---------------------------------------------------------------------------

test('buildParamsSpec emits nothing for default parameters', () => {
  // Native keeps its own defaults for absent keys, so an inert preset sends no keys.
  assert.equal(buildParamsSpec(DEFAULT_PARAMS), '');
});

test('buildParamsSpec emits only the values that differ from the default', () => {
  const spec = buildParamsSpec({ ...DEFAULT_PARAMS, rate: 0.85, reverbMix: 0.28 });
  const keys = spec.split(';').map((part) => part.split('=')[0]).sort();
  assert.deepEqual(keys, ['rate', 'reverbMix']);
  assert.ok(spec.includes('rate=0.85'));
  assert.ok(spec.includes('reverbMix=0.28'));
});

test('buildParamsSpec writes limiterEnabled only when turned off', () => {
  assert.ok(!buildParamsSpec({ ...DEFAULT_PARAMS }).includes('limiterEnabled'));
  assert.ok(
    buildParamsSpec({ ...DEFAULT_PARAMS, limiterEnabled: false }).includes('limiterEnabled=false')
  );
});

test('buildParamsSpec output parses as the key=value; form native expects', () => {
  const spec = buildParamsSpec(BUILT_IN_PRESETS[0].params);
  assert.ok(spec.length > 0);
  for (const entry of spec.split(';')) {
    assert.match(entry, /^[A-Za-z]+=[-0-9.a-z]+$/, `malformed entry: ${entry}`);
  }
});

test('buildParamsSpec sanitizes before emitting, so a bad value never reaches native', () => {
  const spec = buildParamsSpec({ ...DEFAULT_PARAMS, rate: 99 });
  assert.ok(spec.includes(`rate=${PARAM_RANGES.rate.max}`));
});

// ---------------------------------------------------------------------------
// Built-in overrides
// ---------------------------------------------------------------------------

test('applyBuiltInOverrides leaves untouched presets unmodified', () => {
  const result = applyBuiltInOverrides({});
  assert.equal(result.length, BUILT_IN_PRESETS.length);
  assert.ok(result.every((p) => p.modified === false));
  assert.deepEqual(result[0].params, BUILT_IN_PRESETS[0].params);
});

test('applyBuiltInOverrides marks an overridden preset as modified', () => {
  const target = BUILT_IN_PRESETS[0];
  const changed = sanitizeParams({ ...target.params, rate: 0.6 });
  const result = applyBuiltInOverrides({ [target.id]: changed });
  const hit = result.find((p) => p.id === target.id);
  assert.equal(hit?.modified, true);
  assert.equal(hit?.params.rate, 0.6);
  // Other built-ins are untouched.
  assert.ok(result.filter((p) => p.id !== target.id).every((p) => p.modified === false));
});

test('applyBuiltInOverrides ignores overrides for ids that do not exist', () => {
  const result = applyBuiltInOverrides({ 'not-a-preset': DEFAULT_PARAMS });
  assert.equal(result.length, BUILT_IN_PRESETS.length);
  assert.ok(!result.some((p) => p.id === 'not-a-preset'));
});

test('the shipped presets are never mutated by applying an override', () => {
  const before = JSON.stringify(BUILT_IN_PRESETS);
  applyBuiltInOverrides({ [BUILT_IN_PRESETS[0].id]: sanitizeParams({ rate: 1.9 }) });
  assert.equal(JSON.stringify(BUILT_IN_PRESETS), before);
});

test('isBuiltInPresetId distinguishes shipped presets from user ones', () => {
  assert.equal(isBuiltInPresetId(BUILT_IN_PRESETS[0].id), true);
  assert.equal(isBuiltInPresetId('custom_123'), false);
});

// ---------------------------------------------------------------------------
// parseCustomPreset
// ---------------------------------------------------------------------------

test('parseCustomPreset rejects records without an id or a name', () => {
  assert.equal(parseCustomPreset(null), null);
  assert.equal(parseCustomPreset({ name: 'no id' }), null);
  assert.equal(parseCustomPreset({ id: 'x' }), null);
  assert.equal(parseCustomPreset({ id: '  ', name: '  ' }), null);
});

test('parseCustomPreset never marks a stored preset as built-in', () => {
  // Otherwise a crafted record could claim to be a shipped preset.
  const parsed = parseCustomPreset({ id: 'x', name: 'X', builtIn: true });
  assert.equal(parsed?.builtIn, false);
});

test('parseCustomPreset derives a title suffix when one is missing', () => {
  const parsed = parseCustomPreset({ id: 'x', name: 'Warm' });
  assert.equal(parsed?.titleSuffix, ' (Warm)');
});

// ---------------------------------------------------------------------------
// Auto-apply rules
// ---------------------------------------------------------------------------

test('a configuration selecting nothing is invalid', () => {
  assert.equal(isValidAutoPresetConfig({ keepOriginal: false, presetIds: [] }), false);
  assert.equal(isValidAutoPresetConfig({ keepOriginal: true, presetIds: [] }), true);
  assert.equal(isValidAutoPresetConfig({ keepOriginal: false, presetIds: ['a'] }), true);
});

test('outputsPerDownload counts the original plus each preset', () => {
  assert.equal(outputsPerDownload({ keepOriginal: true, presetIds: ['a', 'b', 'c'] }), 4);
  assert.equal(outputsPerDownload({ keepOriginal: false, presetIds: ['a', 'b', 'c'] }), 3);
  assert.equal(outputsPerDownload(DEFAULT_AUTO_PRESET_CONFIG), 1);
});

test('configWithoutPreset removes the preset', () => {
  const next = configWithoutPreset({ keepOriginal: true, presetIds: ['a', 'b'] }, 'a');
  assert.deepEqual(next.presetIds, ['b']);
  assert.equal(next.keepOriginal, true);
});

test('configWithoutPreset keeps the original rather than leaving nothing selected', () => {
  // Removing the only preset when the original was deselected would otherwise produce
  // a configuration that discards the download entirely.
  const next = configWithoutPreset({ keepOriginal: false, presetIds: ['a'] }, 'a');
  assert.deepEqual(next.presetIds, []);
  assert.equal(next.keepOriginal, true);
  assert.equal(isValidAutoPresetConfig(next), true);
});

test('serializeAutoPresetConfig flattens each preset into its spec', () => {
  const presets = [...BUILT_IN_PRESETS];
  const payload = serializeAutoPresetConfig(
    { keepOriginal: true, presetIds: [presets[0].id, presets[1].id] },
    presets
  );
  assert.equal(payload.keepOriginal, true);
  assert.equal(payload.presets.length, 2);
  assert.equal(payload.presets[0].id, presets[0].id);
  assert.equal(payload.presets[0].paramsSpec, buildParamsSpec(presets[0].params));
  assert.equal(payload.presets[0].titleSuffix, presets[0].titleSuffix);
});

test('serializeAutoPresetConfig drops ids that no longer exist', () => {
  // This is the deleted-preset case: a stale id must not keep producing renders.
  const payload = serializeAutoPresetConfig(
    { keepOriginal: true, presetIds: ['deleted-preset', BUILT_IN_PRESETS[0].id] },
    [...BUILT_IN_PRESETS]
  );
  assert.deepEqual(payload.presets.map((p) => p.id), [BUILT_IN_PRESETS[0].id]);
});

test('serializeAutoPresetConfig refuses to emit a configuration that keeps nothing', () => {
  const payload = serializeAutoPresetConfig({ keepOriginal: false, presetIds: [] }, []);
  assert.equal(payload.keepOriginal, true);
});

test('parseAutoPresetConfig survives absent and malformed input', () => {
  assert.deepEqual(parseAutoPresetConfig(null), DEFAULT_AUTO_PRESET_CONFIG);
  assert.deepEqual(parseAutoPresetConfig(''), DEFAULT_AUTO_PRESET_CONFIG);
  assert.deepEqual(parseAutoPresetConfig('{not json'), DEFAULT_AUTO_PRESET_CONFIG);
  assert.deepEqual(parseAutoPresetConfig('[]'), DEFAULT_AUTO_PRESET_CONFIG);
});

test('parseAutoPresetConfig ignores preset entries with no usable id', () => {
  const parsed = parseAutoPresetConfig(
    JSON.stringify({ keepOriginal: false, presets: [{ id: 'a' }, {}, { id: 42 }, { id: '' }] })
  );
  assert.deepEqual(parsed.presetIds, ['a']);
  assert.equal(parsed.keepOriginal, false);
});

test('parseAutoPresetConfig and serializeAutoPresetConfig round-trip', () => {
  const presets = [...BUILT_IN_PRESETS];
  const original = { keepOriginal: false, presetIds: [presets[1].id, presets[2].id] };
  const restored = parseAutoPresetConfig(
    JSON.stringify(serializeAutoPresetConfig(original, presets))
  );
  assert.deepEqual(restored, original);
});

// ---------------------------------------------------------------------------
// Cross-language guard
// ---------------------------------------------------------------------------

test('PARAM_RANGES matches the clamps in preset_params.cpp', () => {
  // The native side re-clamps everything it receives. If these drift, a slider's end
  // silently does nothing because native quietly overrides the value — a failure with
  // no error message anywhere, which is exactly why it is asserted rather than trusted.
  // shared/, not the Android module: the DSP moved there so both apps build the same
  // clamps. This path went stale in that move and the guard quietly stopped guarding.
  const cpp = readFileSync('../shared/dsp/preset_params.cpp', 'utf8');
  const clampPattern = /(\w+)\s*=\s*ClampF\(\w+,\s*(-?[\d.]+)f,\s*(-?[\d.]+)f\)/g;

  const nativeBounds = new Map<string, { min: number; max: number }>();
  for (const match of cpp.matchAll(clampPattern)) {
    nativeBounds.set(match[1], { min: Number(match[2]), max: Number(match[3]) });
  }

  assert.ok(nativeBounds.size > 0, 'found no ClampF calls — has preset_params.cpp moved?');

  for (const [field, range] of Object.entries(PARAM_RANGES)) {
    const native = nativeBounds.get(field);
    assert.ok(native, `${field} has no clamp on the native side`);
    assert.equal(range.min, native.min, `${field} min differs from native`);
    assert.equal(range.max, native.max, `${field} max differs from native`);
  }

  // And nothing native clamps should be missing from the editable set.
  for (const field of nativeBounds.keys()) {
    assert.ok(field in PARAM_RANGES, `native clamps ${field} but the UI has no range for it`);
  }
});

test('every built-in preset produces parameters the ranges accept', () => {
  for (const preset of BUILT_IN_PRESETS) {
    const sanitized = sanitizeParams(preset.params);
    assert.deepEqual(
      sanitized,
      preset.params,
      `${preset.id} ships with a value outside its own allowed range`
    );
  }
});

test('built-in presets have distinct ids and non-empty title suffixes', () => {
  const ids = BUILT_IN_PRESETS.map((p: AudioPreset) => p.id);
  assert.equal(new Set(ids).size, ids.length, 'duplicate built-in preset id');
  for (const preset of BUILT_IN_PRESETS) {
    assert.ok(preset.titleSuffix.trim().length > 0, `${preset.id} has no title suffix`);
    assert.ok(preset.nameKey, `${preset.id} has no i18n key`);
  }
});
