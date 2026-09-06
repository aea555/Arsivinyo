#pragma once

#include <cstddef>
#include <cstdint>
#include <vector>

#include "../preset_params.h"
#include "biquad.h"
#include "freeverb.h"
#include "limiter.h"
#include "resampler.h"

namespace arsivinyo::audio {

/**
 * The full preset signal chain, operating on interleaved stereo float frames.
 *
 *   resample (rate) -> bass shelf -> treble shelf -> reverb (pre-delay + wet mix)
 *     -> output gain -> lookahead limiter
 *
 * The shelves sit BEFORE the reverb so the room responds to the shaped tone — a bass
 * shelf after the reverb would boost the tail's low end into mud instead of thickening
 * the source. The limiter is last so it sees the true final peak.
 *
 * Streaming and stateful. Feed arbitrary block sizes through [Process], then call
 * [Finish] exactly once to flush the reverb tail and the limiter's lookahead delay —
 * skipping it truncates the reverb mid-decay.
 */
class PresetChain {
 public:
  void Prepare(const PresetParams& params, int sampleRate);

  /** Consume `inFrames` interleaved stereo frames, APPENDING the result to `out`. */
  void Process(const float* in, size_t inFrames, std::vector<float>& out);

  /** Flush the reverb tail and limiter latency. Call once, after the last [Process]. */
  void Finish(std::vector<float>& out);

  /** Approximate output frame count for `inFrames` of input — used for progress only. */
  uint64_t EstimateOutputFrames(uint64_t inFrames) const;

 private:
  /** Everything after the resampler, applied in place to `frames` stereo frames. */
  void ProcessPostResample(float* buffer, size_t frames);

  PresetParams params_;
  int sampleRate_ = 44100;

  Resampler resampler_;
  Biquad bassShelf_;
  Biquad trebleShelf_;
  bool bassEnabled_ = false;
  bool trebleEnabled_ = false;

  Freeverb reverb_;
  bool reverbEnabled_ = false;
  /** Pre-delay ring buffer (interleaved stereo); empty when pre-delay is 0. */
  std::vector<float> preDelayLine_;
  size_t preDelayIndex_ = 0;

  float outputGain_ = 1.0f;
  Limiter limiter_;

  /** Scratch buffers reused across calls so steady-state processing does not allocate. */
  std::vector<float> resampled_;
  std::vector<float> wet_;
};

}  // namespace arsivinyo::audio
