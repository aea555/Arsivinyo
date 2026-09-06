#include "limiter.h"

#include <algorithm>
#include <cmath>

namespace arsivinyo::audio {

void Limiter::Prepare(int sampleRate, float ceilingDb) {
  const int rate = sampleRate > 0 ? sampleRate : 44100;
  ceiling_ = std::pow(10.0f, ceilingDb / 20.0f);

  lookaheadFrames_ = static_cast<size_t>(std::max(1.0f, kLookaheadSeconds * rate));
  delayLine_.assign(lookaheadFrames_ * 2, 0.0f);
  delayIndex_ = 0;
  gain_ = 1.0f;

  // Reach the target gain well within the lookahead window, so the reduction is
  // complete before the peak that triggered it reaches the output.
  const float attackFrames = std::max(1.0f, static_cast<float>(lookaheadFrames_) / 3.0f);
  attackCoefficient_ = 1.0f - std::exp(-1.0f / attackFrames);
  releaseCoefficient_ = 1.0f - std::exp(-1.0f / std::max(1.0f, kReleaseSeconds * rate));
}

void Limiter::Process(float* buffer, size_t frames) {
  for (size_t frame = 0; frame < frames; ++frame) {
    float* current = buffer + frame * 2;

    const float peak = std::max(std::fabs(current[0]), std::fabs(current[1]));
    const float target = peak > ceiling_ ? ceiling_ / peak : 1.0f;

    // Duck fast, recover slow.
    const float coefficient = target < gain_ ? attackCoefficient_ : releaseCoefficient_;
    gain_ += (target - gain_) * coefficient;

    // Swap the incoming frame into the delay line and take out the one from
    // `lookaheadFrames_` ago — that older frame is what the envelope was built for.
    float* delayed = delayLine_.data() + delayIndex_ * 2;
    const float outL = delayed[0];
    const float outR = delayed[1];
    delayed[0] = current[0];
    delayed[1] = current[1];
    if (++delayIndex_ >= lookaheadFrames_) delayIndex_ = 0;

    // The envelope is smoothed, so a fast enough transient can still poke through by a
    // hair. Clamping catches that without being audible — it engages on a sample or
    // two at most, never on sustained material.
    current[0] = std::clamp(outL * gain_, -ceiling_, ceiling_);
    current[1] = std::clamp(outR * gain_, -ceiling_, ceiling_);
  }
}

}  // namespace arsivinyo::audio
