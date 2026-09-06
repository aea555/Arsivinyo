#include "preset_params.h"

#include <algorithm>
#include <cmath>
#include <cstdlib>

namespace arsivinyo::audio {
namespace {

float ClampF(float value, float lo, float hi) {
  if (!std::isfinite(value)) return lo;
  return std::min(hi, std::max(lo, value));
}

std::string Trim(const std::string& s) {
  size_t begin = s.find_first_not_of(" \t\r\n");
  if (begin == std::string::npos) return {};
  size_t end = s.find_last_not_of(" \t\r\n");
  return s.substr(begin, end - begin + 1);
}

/** Parse a decimal number. Returns false (leaving `out` untouched) if unparseable. */
bool ParseFloat(const std::string& text, float* out) {
  if (text.empty()) return false;
  char* endPtr = nullptr;
  const double parsed = std::strtod(text.c_str(), &endPtr);
  if (endPtr == text.c_str() || !std::isfinite(parsed)) return false;
  *out = static_cast<float>(parsed);
  return true;
}

bool ParseBool(const std::string& text, bool* out) {
  if (text == "1" || text == "true") {
    *out = true;
    return true;
  }
  if (text == "0" || text == "false") {
    *out = false;
    return true;
  }
  return false;
}

}  // namespace

void PresetParams::Clamp() {
  // Rate is bounded well inside what the resampler stays clean over. Beyond 2x the
  // anti-alias filter starts eating the top octave anyway.
  rate = ClampF(rate, 0.5f, 2.0f);

  reverbMix = ClampF(reverbMix, 0.0f, 1.0f);
  // Freeverb's comb feedback approaches 1 as room approaches 1; capping below that
  // keeps the tail finite instead of self-oscillating.
  reverbRoom = ClampF(reverbRoom, 0.0f, 0.97f);
  reverbDamp = ClampF(reverbDamp, 0.0f, 1.0f);
  reverbWidth = ClampF(reverbWidth, 0.0f, 1.0f);
  reverbPreDelayMs = ClampF(reverbPreDelayMs, 0.0f, 200.0f);

  bassGainDb = ClampF(bassGainDb, -24.0f, 24.0f);
  bassFreqHz = ClampF(bassFreqHz, 20.0f, 1000.0f);
  trebleGainDb = ClampF(trebleGainDb, -24.0f, 24.0f);
  trebleFreqHz = ClampF(trebleFreqHz, 1000.0f, 16000.0f);

  outputGainDb = ClampF(outputGainDb, -24.0f, 24.0f);
  limiterCeilingDb = ClampF(limiterCeilingDb, -12.0f, 0.0f);
}

PresetParams ParsePresetParams(const std::string& spec) {
  PresetParams params;

  size_t cursor = 0;
  while (cursor <= spec.size()) {
    const size_t separator = spec.find(';', cursor);
    const std::string entry =
        spec.substr(cursor, separator == std::string::npos ? std::string::npos : separator - cursor);
    cursor = separator == std::string::npos ? spec.size() + 1 : separator + 1;

    const size_t equals = entry.find('=');
    if (equals == std::string::npos) continue;
    const std::string key = Trim(entry.substr(0, equals));
    const std::string value = Trim(entry.substr(equals + 1));
    if (key.empty() || value.empty()) continue;

    if (key == "rate") {
      ParseFloat(value, &params.rate);
    } else if (key == "reverbMix") {
      ParseFloat(value, &params.reverbMix);
    } else if (key == "reverbRoom") {
      ParseFloat(value, &params.reverbRoom);
    } else if (key == "reverbDamp") {
      ParseFloat(value, &params.reverbDamp);
    } else if (key == "reverbWidth") {
      ParseFloat(value, &params.reverbWidth);
    } else if (key == "reverbPreDelayMs") {
      ParseFloat(value, &params.reverbPreDelayMs);
    } else if (key == "bassGainDb") {
      ParseFloat(value, &params.bassGainDb);
    } else if (key == "bassFreqHz") {
      ParseFloat(value, &params.bassFreqHz);
    } else if (key == "trebleGainDb") {
      ParseFloat(value, &params.trebleGainDb);
    } else if (key == "trebleFreqHz") {
      ParseFloat(value, &params.trebleFreqHz);
    } else if (key == "outputGainDb") {
      ParseFloat(value, &params.outputGainDb);
    } else if (key == "limiterEnabled") {
      ParseBool(value, &params.limiterEnabled);
    } else if (key == "limiterCeilingDb") {
      ParseFloat(value, &params.limiterCeilingDb);
    }
    // Unknown keys are intentionally ignored — see the header.
  }

  params.Clamp();
  return params;
}

}  // namespace arsivinyo::audio
