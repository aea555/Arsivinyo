#include "preset_chain.h"

#include <algorithm>
#include <cmath>

namespace arsivinyo::audio {
namespace {

/** A shelf within this much of 0 dB is inaudible; skip the filter entirely. */
constexpr float kMinShelfGainDb = 0.01f;
constexpr float kMinReverbMix = 0.001f;
/** Block size used when pushing silence through the chain during [Finish]. */
constexpr size_t kFlushBlockFrames = 1024;

}  // namespace

void PresetChain::Prepare(const PresetParams& params, int sampleRate) {
  params_ = params;
  params_.Clamp();
  sampleRate_ = sampleRate > 0 ? sampleRate : 44100;

  resampler_.Prepare(params_.rate, sampleRate_);

  bassEnabled_ = std::fabs(params_.bassGainDb) > kMinShelfGainDb;
  if (bassEnabled_) {
    bassShelf_.SetLowShelf(static_cast<float>(sampleRate_), params_.bassFreqHz, params_.bassGainDb);
  }

  trebleEnabled_ = std::fabs(params_.trebleGainDb) > kMinShelfGainDb;
  if (trebleEnabled_) {
    trebleShelf_.SetHighShelf(static_cast<float>(sampleRate_), params_.trebleFreqHz,
                              params_.trebleGainDb);
  }

  reverbEnabled_ = params_.reverbMix > kMinReverbMix;
  if (reverbEnabled_) {
    reverb_.Prepare(sampleRate_);
    reverb_.SetParams(params_.reverbRoom, params_.reverbDamp, params_.reverbWidth);

    const size_t preDelayFrames =
        static_cast<size_t>(params_.reverbPreDelayMs * 0.001f * static_cast<float>(sampleRate_));
    preDelayLine_.assign(preDelayFrames * 2, 0.0f);
    preDelayIndex_ = 0;
  } else {
    preDelayLine_.clear();
    preDelayIndex_ = 0;
  }

  outputGain_ = std::pow(10.0f, params_.outputGainDb / 20.0f);
  limiter_.Prepare(sampleRate_, params_.limiterCeilingDb);
}

void PresetChain::ProcessPostResample(float* buffer, size_t frames) {
  if (frames == 0) return;

  if (bassEnabled_ || trebleEnabled_) {
    for (size_t frame = 0; frame < frames; ++frame) {
      for (int ch = 0; ch < 2; ++ch) {
        float sample = buffer[frame * 2 + ch];
        if (bassEnabled_) sample = bassShelf_.Process(ch, sample);
        if (trebleEnabled_) sample = trebleShelf_.Process(ch, sample);
        buffer[frame * 2 + ch] = sample;
      }
    }
  }

  if (reverbEnabled_) {
    wet_.assign(frames * 2, 0.0f);

    if (!preDelayLine_.empty()) {
      // Feed the reverb a delayed copy while the dry path stays aligned, so the room
      // answers a beat after the source instead of on top of it.
      const size_t preDelayFrames = preDelayLine_.size() / 2;
      for (size_t frame = 0; frame < frames; ++frame) {
        float* slot = preDelayLine_.data() + preDelayIndex_ * 2;
        const float delayedL = slot[0];
        const float delayedR = slot[1];
        slot[0] = buffer[frame * 2];
        slot[1] = buffer[frame * 2 + 1];
        if (++preDelayIndex_ >= preDelayFrames) preDelayIndex_ = 0;
        wet_[frame * 2] = delayedL;
        wet_[frame * 2 + 1] = delayedR;
      }
      reverb_.ProcessWet(wet_.data(), wet_.data(), frames);
    } else {
      reverb_.ProcessWet(buffer, wet_.data(), frames);
    }

    const float wetGain = params_.reverbMix;
    // Constant-power-ish crossfade: hold the dry back only as far as the wet comes up,
    // so raising the mix doesn't collapse the level of the source.
    const float dryGain = 1.0f - wetGain * 0.5f;
    for (size_t i = 0; i < frames * 2; ++i) {
      buffer[i] = buffer[i] * dryGain + wet_[i] * wetGain;
    }
  }

  if (outputGain_ != 1.0f) {
    for (size_t i = 0; i < frames * 2; ++i) buffer[i] *= outputGain_;
  }

  if (params_.limiterEnabled) {
    limiter_.Process(buffer, frames);
  }
}

void PresetChain::Process(const float* in, size_t inFrames, std::vector<float>& out) {
  if (inFrames == 0) return;

  resampled_.clear();
  resampler_.Process(in, inFrames, resampled_);
  if (resampled_.empty()) return;

  const size_t frames = resampled_.size() / 2;
  ProcessPostResample(resampled_.data(), frames);
  out.insert(out.end(), resampled_.begin(), resampled_.end());
}

void PresetChain::Finish(std::vector<float>& out) {
  size_t flushFrames = 0;
  if (reverbEnabled_) {
    flushFrames += static_cast<size_t>(reverb_.TailSeconds() * static_cast<float>(sampleRate_));
  }
  if (params_.limiterEnabled) {
    flushFrames += limiter_.LatencyFrames();
  }
  if (flushFrames == 0) return;

  std::vector<float> silence(kFlushBlockFrames * 2, 0.0f);
  size_t remaining = flushFrames;
  while (remaining > 0) {
    const size_t block = std::min(remaining, kFlushBlockFrames);
    std::fill(silence.begin(), silence.begin() + static_cast<long>(block * 2), 0.0f);
    ProcessPostResample(silence.data(), block);
    out.insert(out.end(), silence.begin(), silence.begin() + static_cast<long>(block * 2));
    remaining -= block;
  }
}

uint64_t PresetChain::EstimateOutputFrames(uint64_t inFrames) const {
  const double rate = params_.rate > 0.0f ? params_.rate : 1.0f;
  return static_cast<uint64_t>(static_cast<double>(inFrames) / rate);
}

}  // namespace arsivinyo::audio
