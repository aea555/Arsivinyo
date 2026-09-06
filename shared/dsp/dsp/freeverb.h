#pragma once

#include <cstddef>
#include <vector>

namespace arsivinyo::audio {

/**
 * Freeverb — the Schroeder-Moorer reverb published by Jezar at Dreampoint and released
 * to the public domain. Eight parallel damped comb filters into four series allpasses
 * per channel, with the right channel's delay lines offset by `kStereoSpread` samples
 * to decorrelate the two sides.
 *
 * Deviations from the original: the delay-line lengths (tuned for 44.1 kHz) are scaled
 * to the actual sample rate, and this produces a WET-ONLY signal. Keeping the dry/wet
 * mix outside means `reverbMix` in the preset is an exact, predictable fraction rather
 * than Freeverb's internal (and differently scaled) wet/dry pair.
 *
 * Operates on interleaved stereo float frames.
 */
class Freeverb {
 public:
  void Prepare(int sampleRate);
  void SetParams(float room, float damp, float width);

  /** Read `frames` interleaved stereo frames from `in`, write the wet signal to `out`. */
  void ProcessWet(const float* in, float* out, size_t frames);

  /** Longest audible tail for the current room size, in seconds. */
  float TailSeconds() const;

 private:
  /** One damped comb filter: a delay line with a one-pole lowpass in the feedback path. */
  class Comb {
   public:
    void SetSize(size_t size) {
      buffer_.assign(size == 0 ? 1 : size, 0.0f);
      index_ = 0;
      filterStore_ = 0.0f;
    }
    void SetFeedback(float feedback) { feedback_ = feedback; }
    void SetDamp(float damp) {
      damp1_ = damp;
      damp2_ = 1.0f - damp;
    }
    inline float Process(float input) {
      const float output = buffer_[index_];
      filterStore_ = output * damp2_ + filterStore_ * damp1_ + kDenormalGuard;
      buffer_[index_] = input + filterStore_ * feedback_;
      if (++index_ >= buffer_.size()) index_ = 0;
      return output;
    }

   private:
    std::vector<float> buffer_;
    size_t index_ = 0;
    float filterStore_ = 0.0f;
    float feedback_ = 0.5f;
    float damp1_ = 0.5f;
    float damp2_ = 0.5f;
  };

  /** Schroeder allpass, fixed feedback of 0.5 as in the original. */
  class Allpass {
   public:
    void SetSize(size_t size) {
      buffer_.assign(size == 0 ? 1 : size, 0.0f);
      index_ = 0;
    }
    inline float Process(float input) {
      const float buffered = buffer_[index_];
      const float output = -input + buffered;
      buffer_[index_] = input + buffered * 0.5f + kDenormalGuard;
      if (++index_ >= buffer_.size()) index_ = 0;
      return output;
    }

   private:
    std::vector<float> buffer_;
    size_t index_ = 0;
  };

  static constexpr int kCombCount = 8;
  static constexpr int kAllpassCount = 4;
  /** Right-channel delay offset in samples at 44.1 kHz — what makes the tail stereo. */
  static constexpr int kStereoSpread = 23;
  /**
   * Adding a tiny constant on every feedback write keeps the tail from decaying into
   * denormal floats, which are catastrophically slow on some ARM cores. Far below the
   * 16-bit noise floor, so it is inaudible.
   */
  static constexpr float kDenormalGuard = 1e-20f;

  Comb combsL_[kCombCount];
  Comb combsR_[kCombCount];
  Allpass allpassL_[kAllpassCount];
  Allpass allpassR_[kAllpassCount];

  int sampleRate_ = 44100;
  float roomSize_ = 0.5f;
  float wet1_ = 1.0f;
  float wet2_ = 0.0f;
};

}  // namespace arsivinyo::audio
