#pragma once

#include <cmath>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

namespace arsivinyo::audio {

/**
 * Transposed direct-form II biquad with per-channel state (stereo).
 *
 * Coefficients follow the RBJ audio EQ cookbook. Shelf filters use S = 1 (the
 * gentlest, least resonant slope), which is what you want for tone shaping — a
 * steeper shelf rings on transients.
 */
class Biquad {
 public:
  void Reset() {
    for (int ch = 0; ch < 2; ++ch) {
      z1_[ch] = 0.0f;
      z2_[ch] = 0.0f;
    }
  }

  /** Unity-gain passthrough. */
  void SetBypass() {
    b0_ = 1.0f;
    b1_ = b2_ = a1_ = a2_ = 0.0f;
    Reset();
  }

  void SetLowShelf(float sampleRate, float freqHz, float gainDb) {
    const double A = std::pow(10.0, gainDb / 40.0);
    const double w0 = 2.0 * M_PI * freqHz / sampleRate;
    const double cs = std::cos(w0);
    const double alpha = std::sin(w0) * 0.5 * std::sqrt(2.0);
    const double twoSqrtAAlpha = 2.0 * std::sqrt(A) * alpha;

    const double b0 = A * ((A + 1.0) - (A - 1.0) * cs + twoSqrtAAlpha);
    const double b1 = 2.0 * A * ((A - 1.0) - (A + 1.0) * cs);
    const double b2 = A * ((A + 1.0) - (A - 1.0) * cs - twoSqrtAAlpha);
    const double a0 = (A + 1.0) + (A - 1.0) * cs + twoSqrtAAlpha;
    const double a1 = -2.0 * ((A - 1.0) + (A + 1.0) * cs);
    const double a2 = (A + 1.0) + (A - 1.0) * cs - twoSqrtAAlpha;

    Normalize(b0, b1, b2, a0, a1, a2);
  }

  void SetHighShelf(float sampleRate, float freqHz, float gainDb) {
    const double A = std::pow(10.0, gainDb / 40.0);
    const double w0 = 2.0 * M_PI * freqHz / sampleRate;
    const double cs = std::cos(w0);
    const double alpha = std::sin(w0) * 0.5 * std::sqrt(2.0);
    const double twoSqrtAAlpha = 2.0 * std::sqrt(A) * alpha;

    const double b0 = A * ((A + 1.0) + (A - 1.0) * cs + twoSqrtAAlpha);
    const double b1 = -2.0 * A * ((A - 1.0) + (A + 1.0) * cs);
    const double b2 = A * ((A + 1.0) + (A - 1.0) * cs - twoSqrtAAlpha);
    const double a0 = (A + 1.0) - (A - 1.0) * cs + twoSqrtAAlpha;
    const double a1 = 2.0 * ((A - 1.0) - (A + 1.0) * cs);
    const double a2 = (A + 1.0) - (A - 1.0) * cs - twoSqrtAAlpha;

    Normalize(b0, b1, b2, a0, a1, a2);
  }

  void SetLowPass(float sampleRate, float freqHz, float q) {
    const double w0 = 2.0 * M_PI * freqHz / sampleRate;
    const double cs = std::cos(w0);
    const double alpha = std::sin(w0) / (2.0 * q);

    const double b0 = (1.0 - cs) * 0.5;
    const double b1 = 1.0 - cs;
    const double b2 = (1.0 - cs) * 0.5;
    const double a0 = 1.0 + alpha;
    const double a1 = -2.0 * cs;
    const double a2 = 1.0 - alpha;

    Normalize(b0, b1, b2, a0, a1, a2);
  }

  inline float Process(int channel, float x) {
    const float y = b0_ * x + z1_[channel];
    z1_[channel] = b1_ * x - a1_ * y + z2_[channel];
    z2_[channel] = b2_ * x - a2_ * y;
    return y;
  }

 private:
  void Normalize(double b0, double b1, double b2, double a0, double a1, double a2) {
    if (a0 == 0.0 || !std::isfinite(a0)) {
      SetBypass();
      return;
    }
    b0_ = static_cast<float>(b0 / a0);
    b1_ = static_cast<float>(b1 / a0);
    b2_ = static_cast<float>(b2 / a0);
    a1_ = static_cast<float>(a1 / a0);
    a2_ = static_cast<float>(a2 / a0);
    Reset();
  }

  float b0_ = 1.0f, b1_ = 0.0f, b2_ = 0.0f, a1_ = 0.0f, a2_ = 0.0f;
  float z1_[2] = {0.0f, 0.0f};
  float z2_[2] = {0.0f, 0.0f};
};

}  // namespace arsivinyo::audio
