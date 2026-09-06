#pragma once

#include <cstddef>
#include <vector>

#include "biquad.h"

namespace arsivinyo::audio {

/**
 * Fractional-rate resampler for interleaved stereo, used to implement the preset
 * `rate` control.
 *
 * This deliberately does NOT pitch-correct: reading the input faster or slower moves
 * tempo and pitch together, which is the whole point of the slowed / nightcore effect.
 *
 * Interpolation is Catmull-Rom (4-point cubic). It is not a windowed-sinc resampler —
 * at these modest ratios its passband error sits far below the noise floor of the AAC
 * encode that follows, so the extra cost of a polyphase FIR would buy nothing audible.
 *
 * When speeding up (rate > 1) the input is decimated, so a 4th-order Butterworth
 * lowpass runs first to keep everything above the new Nyquist from folding back as
 * aliasing. Slowing down interpolates and cannot alias, so the filter is bypassed.
 *
 * Stateful and streaming: feed arbitrary block sizes, get back however many output
 * frames that block produced.
 */
class Resampler {
 public:
  void Prepare(float rate, int sampleRate);

  /**
   * Consume `inFrames` interleaved stereo frames and APPEND the resampled result to
   * `out`. The number of frames appended varies from call to call.
   */
  void Process(const float* in, size_t inFrames, std::vector<float>& out);

  /** True when `rate` is close enough to 1 that this stage is a passthrough. */
  bool IsBypassed() const { return bypass_; }

 private:
  /**
   * History frames carried between calls, so interpolation spans block boundaries.
   *
   * Four, not the three the cubic strictly needs: a block ends with the read position
   * somewhere in the last usable frame, and rebasing onto only three history frames
   * can leave it below 1.0 when rate < 1 — which sends the interpolator's `base - 1`
   * tap off the front of the buffer. The extra frame keeps the rebased position in
   * (1, 1 + rate], so every tap stays in bounds at any supported rate.
   */
  static constexpr size_t kHistoryFrames = 4;

  float rate_ = 1.0f;
  bool bypass_ = true;
  bool antiAlias_ = false;
  Biquad antiAliasLow_;
  Biquad antiAliasHigh_;

  /** [history frames][current block], interleaved stereo. Reused across calls. */
  std::vector<float> work_;
  /** Read position within `work_`, in frames. */
  double position_ = static_cast<double>(kHistoryFrames);
};

}  // namespace arsivinyo::audio
