#include "resampler.h"

#include <algorithm>
#include <cmath>

namespace arsivinyo::audio {
namespace {

/** Catmull-Rom cubic through p1..p2, with p0/p3 as the surrounding slope anchors. */
inline float CatmullRom(float p0, float p1, float p2, float p3, float t) {
  const float a0 = -0.5f * p0 + 1.5f * p1 - 1.5f * p2 + 0.5f * p3;
  const float a1 = p0 - 2.5f * p1 + 2.0f * p2 - 0.5f * p3;
  const float a2 = -0.5f * p0 + 0.5f * p2;
  return ((a0 * t + a1) * t + a2) * t + p1;
}

// Q values that make two cascaded biquads a 4th-order Butterworth.
constexpr float kButterworthQ1 = 0.54119610f;
constexpr float kButterworthQ2 = 1.30656296f;

}  // namespace

void Resampler::Prepare(float rate, int sampleRate) {
  rate_ = rate > 0.0f ? rate : 1.0f;
  bypass_ = std::fabs(rate_ - 1.0f) < 1e-4f;

  work_.assign(kHistoryFrames * 2, 0.0f);
  position_ = static_cast<double>(kHistoryFrames);

  // Only decimation can alias. Cut at 45% of the post-decimation Nyquist to leave the
  // filter a transition band before the fold-back point.
  antiAlias_ = !bypass_ && rate_ > 1.0f;
  if (antiAlias_) {
    const float cutoff = 0.45f * static_cast<float>(sampleRate) / rate_;
    const float safeCutoff = std::min(cutoff, static_cast<float>(sampleRate) * 0.49f);
    antiAliasLow_.SetLowPass(static_cast<float>(sampleRate), safeCutoff, kButterworthQ1);
    antiAliasHigh_.SetLowPass(static_cast<float>(sampleRate), safeCutoff, kButterworthQ2);
  } else {
    antiAliasLow_.SetBypass();
    antiAliasHigh_.SetBypass();
  }
}

void Resampler::Process(const float* in, size_t inFrames, std::vector<float>& out) {
  if (inFrames == 0) return;

  if (bypass_) {
    out.insert(out.end(), in, in + inFrames * 2);
    return;
  }

  // Rebuild the work buffer as [carried history][this block].
  work_.resize(kHistoryFrames * 2);
  work_.insert(work_.end(), in, in + inFrames * 2);

  if (antiAlias_) {
    for (size_t frame = kHistoryFrames; frame < kHistoryFrames + inFrames; ++frame) {
      for (int ch = 0; ch < 2; ++ch) {
        float sample = work_[frame * 2 + ch];
        sample = antiAliasLow_.Process(ch, sample);
        sample = antiAliasHigh_.Process(ch, sample);
        work_[frame * 2 + ch] = sample;
      }
    }
  }

  const size_t workFrames = work_.size() / 2;
  // Catmull-Rom needs one frame before and two after the read position, so the last
  // position we can safely evaluate is workFrames - 3.
  const double lastUsable = static_cast<double>(workFrames) - 3.0;

  while (position_ <= lastUsable) {
    const size_t base = static_cast<size_t>(position_);
    const float t = static_cast<float>(position_ - static_cast<double>(base));
    for (int ch = 0; ch < 2; ++ch) {
      out.push_back(CatmullRom(work_[(base - 1) * 2 + ch], work_[base * 2 + ch],
                               work_[(base + 1) * 2 + ch], work_[(base + 2) * 2 + ch], t));
    }
    position_ += rate_;
  }

  // Carry the last `kHistoryFrames` frames forward and rebase the read position onto
  // them, so the next block interpolates continuously across the seam.
  const size_t keepFrom = workFrames - kHistoryFrames;
  std::vector<float> carried(work_.begin() + static_cast<long>(keepFrom * 2), work_.end());
  work_.swap(carried);
  position_ -= static_cast<double>(keepFrom);
}

}  // namespace arsivinyo::audio
