#pragma once

#include <cstddef>
#include <vector>

namespace arsivinyo::audio {

/**
 * Lookahead peak limiter for interleaved stereo.
 *
 * The gain envelope is computed from the incoming signal but applied to a delayed copy
 * of it, so the gain is already down by the time a transient arrives — no overshoot,
 * and no audible pumping on the attack. Release is slow enough not to modulate bass.
 *
 * Last stage in the chain, and the reason reverb and bass boost can be pushed hard
 * without the AAC encoder clipping.
 */
class Limiter {
 public:
  void Prepare(int sampleRate, float ceilingDb);

  /** Limit `frames` interleaved stereo frames in place. */
  void Process(float* buffer, size_t frames);

  /** Frames of lookahead delay still holding audio; flush this many at end of stream. */
  size_t LatencyFrames() const { return lookaheadFrames_; }

 private:
  /** 5 ms is long enough to catch a transient, short enough to be inaudible. */
  static constexpr float kLookaheadSeconds = 0.005f;
  static constexpr float kReleaseSeconds = 0.100f;

  float ceiling_ = 1.0f;
  size_t lookaheadFrames_ = 0;
  size_t delayIndex_ = 0;
  float attackCoefficient_ = 1.0f;
  float releaseCoefficient_ = 1.0f;
  float gain_ = 1.0f;
  std::vector<float> delayLine_;
};

}  // namespace arsivinyo::audio
