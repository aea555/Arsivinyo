#pragma once

#include <string>

namespace arsivinyo::audio {

/**
 * Parameters for one audio-preset render.
 *
 * Every field defaults to something inert, so a params string only has to carry what
 * it actually changes. The wire format between Kotlin and here is a flat
 * `key=value;key=value` string (see [ParsePresetParams]) rather than JSON: it needs no
 * third-party dependency, unknown keys are ignored, and a missing key keeps its
 * default. That means adding a parameter for a user-defined preset can never break an
 * older stored preset, and a preset saved by a newer build degrades gracefully on an
 * older one.
 */
struct PresetParams {
  /**
   * Playback rate ratio. Implemented as plain resampling with NO pitch correction, so
   * tempo and pitch move together — that is precisely the "slowed" / "nightcore"
   * sound. A pitch-preserving time stretch would be the wrong effect here.
   * < 1 slows down (and lowers pitch), > 1 speeds up.
   */
  float rate = 1.0f;

  // Freeverb (Schroeder-Moorer). A mix of 0 bypasses the whole reverb stage.
  float reverbMix = 0.0f;         ///< wet fraction, 0..1
  float reverbRoom = 0.5f;        ///< 0..1, larger = longer decay
  float reverbDamp = 0.5f;        ///< 0..1, larger = darker tail
  float reverbWidth = 1.0f;       ///< 0..1 stereo spread of the wet signal
  float reverbPreDelayMs = 0.0f;  ///< 0..200, gap before the room responds

  // Shelving EQ (RBJ cookbook). A gain of 0 dB bypasses that shelf.
  float bassGainDb = 0.0f;
  float bassFreqHz = 100.0f;
  float trebleGainDb = 0.0f;
  float trebleFreqHz = 6000.0f;

  float outputGainDb = 0.0f;

  /**
   * Lookahead peak limiter. On by default because both reverb and a bass shelf add
   * energy: without it a boosted track clips on the way into the AAC encoder, which
   * is audible and unfixable after the fact.
   */
  bool limiterEnabled = true;
  float limiterCeilingDb = -0.3f;

  /**
   * Clamp every field to a range the DSP is known to be stable over. Always applied
   * after parsing, so a hand-edited or corrupted custom preset can't produce NaNs,
   * a runaway reverb tail, or a multi-hour render.
   */
  void Clamp();
};

/**
 * Parse a `key=value;key=value` spec into [PresetParams].
 *
 * Whitespace around keys and values is ignored, keys are case-sensitive, unknown keys
 * are skipped, and malformed values leave the field at its default. The result is
 * always [PresetParams::Clamp]ed before being returned.
 */
PresetParams ParsePresetParams(const std::string& spec);

}  // namespace arsivinyo::audio
