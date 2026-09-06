#include "freeverb.h"

#include <algorithm>
#include <cmath>

namespace arsivinyo::audio {
namespace {

// Jezar's original delay-line lengths, in samples at 44.1 kHz. They are mutually
// near-prime so the comb resonances don't stack into a ringing pitch.
constexpr int kCombTuning[] = {1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617};
constexpr int kAllpassTuning[] = {556, 441, 341, 225};

// Freeverb maps the 0..1 room control onto comb feedback via feedback = room*scale +
// offset. The offset keeps even "room = 0" a small room rather than a dead one.
constexpr float kRoomScale = 0.28f;
constexpr float kRoomOffset = 0.7f;
// Damping is scaled down so the full 0..1 control range stays usable; at damp = 1 the
// one-pole would otherwise kill the tail almost immediately.
constexpr float kDampScale = 0.4f;
// Input attenuation. The eight parallel combs sum to a lot of gain, so the dry signal
// is scaled well down on the way in.
constexpr float kInputGain = 0.015f;

size_t ScaleTuning(int tuningAt44k, int sampleRate) {
  const double scaled = static_cast<double>(tuningAt44k) * sampleRate / 44100.0;
  return static_cast<size_t>(std::max(1.0, std::floor(scaled)));
}

}  // namespace

void Freeverb::Prepare(int sampleRate) {
  sampleRate_ = sampleRate > 0 ? sampleRate : 44100;
  const size_t spread = ScaleTuning(kStereoSpread, sampleRate_);

  for (int i = 0; i < kCombCount; ++i) {
    const size_t size = ScaleTuning(kCombTuning[i], sampleRate_);
    combsL_[i].SetSize(size);
    combsR_[i].SetSize(size + spread);
  }
  for (int i = 0; i < kAllpassCount; ++i) {
    const size_t size = ScaleTuning(kAllpassTuning[i], sampleRate_);
    allpassL_[i].SetSize(size);
    allpassR_[i].SetSize(size + spread);
  }
}

void Freeverb::SetParams(float room, float damp, float width) {
  roomSize_ = std::min(1.0f, std::max(0.0f, room));
  const float feedback = roomSize_ * kRoomScale + kRoomOffset;
  const float damping = std::min(1.0f, std::max(0.0f, damp)) * kDampScale;

  for (int i = 0; i < kCombCount; ++i) {
    combsL_[i].SetFeedback(feedback);
    combsR_[i].SetFeedback(feedback);
    combsL_[i].SetDamp(damping);
    combsR_[i].SetDamp(damping);
  }

  // Cross-feed the two wet channels to narrow the image: width 1 keeps them fully
  // separate, width 0 collapses the tail to mono.
  const float clampedWidth = std::min(1.0f, std::max(0.0f, width));
  wet1_ = clampedWidth * 0.5f + 0.5f;
  wet2_ = (1.0f - clampedWidth) * 0.5f;
}

void Freeverb::ProcessWet(const float* in, float* out, size_t frames) {
  for (size_t frame = 0; frame < frames; ++frame) {
    const float inputL = in[frame * 2];
    const float inputR = in[frame * 2 + 1];
    const float input = (inputL + inputR) * kInputGain;

    float accumL = 0.0f;
    float accumR = 0.0f;
    for (int i = 0; i < kCombCount; ++i) {
      accumL += combsL_[i].Process(input);
      accumR += combsR_[i].Process(input);
    }
    for (int i = 0; i < kAllpassCount; ++i) {
      accumL = allpassL_[i].Process(accumL);
      accumR = allpassR_[i].Process(accumR);
    }

    out[frame * 2] = accumL * wet1_ + accumR * wet2_;
    out[frame * 2 + 1] = accumR * wet1_ + accumL * wet2_;
  }
}

float Freeverb::TailSeconds() const {
  // RT60 for a comb with feedback g and delay D is D * ln(0.001) / ln(g). Estimated
  // from the longest comb, which decays slowest, then padded a little so the render
  // doesn't clip the very end of an audible tail.
  const float feedback = roomSize_ * kRoomScale + kRoomOffset;
  if (feedback <= 0.0f || feedback >= 1.0f) return 1.0f;
  const double delaySeconds = static_cast<double>(kCombTuning[kCombCount - 1]) / 44100.0;
  const double rt60 = delaySeconds * std::log(0.001) / std::log(feedback);
  return static_cast<float>(std::min(12.0, std::max(0.5, rt60 * 1.2)));
}

}  // namespace arsivinyo::audio
