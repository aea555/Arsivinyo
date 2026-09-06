// Host-side tests for the audio-preset DSP core.
//
// This deliberately depends on nothing Android-specific, so the signal chain can be
// verified with a plain compiler on the dev machine instead of a device build. See
// run_tests.sh in this directory.

#include <cmath>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include "../dsp/preset_chain.h"
#include "../preset_params.h"

using arvy = arsivinyo::audio::PresetParams;
using arsivinyo::audio::ParsePresetParams;
using arsivinyo::audio::PresetChain;
using arsivinyo::audio::PresetParams;

namespace {

int g_failures = 0;
int g_checks = 0;

void Check(bool condition, const std::string& what) {
  ++g_checks;
  if (!condition) {
    ++g_failures;
    std::printf("  FAIL  %s\n", what.c_str());
  }
}

void CheckNear(double actual, double expected, double tolerance, const std::string& what) {
  ++g_checks;
  if (!(std::fabs(actual - expected) <= tolerance)) {
    ++g_failures;
    std::printf("  FAIL  %s (got %.6f, expected %.6f +/- %.6f)\n", what.c_str(), actual, expected,
                tolerance);
  }
}

constexpr int kSampleRate = 44100;

/** Interleaved stereo sine of `seconds` duration. */
std::vector<float> MakeSine(double freqHz, double amplitude, double seconds) {
  const size_t frames = static_cast<size_t>(kSampleRate * seconds);
  std::vector<float> out(frames * 2);
  for (size_t i = 0; i < frames; ++i) {
    const double phase = 2.0 * M_PI * freqHz * static_cast<double>(i) / kSampleRate;
    const float sample = static_cast<float>(amplitude * std::sin(phase));
    out[i * 2] = sample;
    out[i * 2 + 1] = sample;
  }
  return out;
}

/** Run a chain over `input` in fixed-size blocks. Returns output + pre-flush length. */
struct RenderResult {
  std::vector<float> samples;
  size_t framesBeforeFinish = 0;
};

RenderResult Render(const PresetParams& params, const std::vector<float>& input,
                    size_t blockFrames) {
  PresetChain chain;
  chain.Prepare(params, kSampleRate);

  RenderResult result;
  const size_t totalFrames = input.size() / 2;
  for (size_t offset = 0; offset < totalFrames; offset += blockFrames) {
    const size_t count = std::min(blockFrames, totalFrames - offset);
    chain.Process(input.data() + offset * 2, count, result.samples);
  }
  result.framesBeforeFinish = result.samples.size() / 2;
  chain.Finish(result.samples);
  return result;
}

double PeakOf(const std::vector<float>& samples, size_t fromFrame = 0) {
  double peak = 0.0;
  for (size_t i = fromFrame * 2; i < samples.size(); ++i) {
    peak = std::max(peak, static_cast<double>(std::fabs(samples[i])));
  }
  return peak;
}

double RmsOf(const std::vector<float>& samples, size_t fromFrame = 0, size_t toFrame = SIZE_MAX) {
  const size_t begin = fromFrame * 2;
  const size_t end = std::min(samples.size(), toFrame == SIZE_MAX ? samples.size() : toFrame * 2);
  if (end <= begin) return 0.0;
  double sum = 0.0;
  for (size_t i = begin; i < end; ++i) sum += static_cast<double>(samples[i]) * samples[i];
  return std::sqrt(sum / static_cast<double>(end - begin));
}

bool AllFinite(const std::vector<float>& samples) {
  for (float s : samples) {
    if (!std::isfinite(s)) return false;
  }
  return true;
}

double ToDb(double linear) { return 20.0 * std::log10(std::max(1e-12, linear)); }

// ---------------------------------------------------------------------------
// The built-in presets, mirrored from the TS definitions.
// ---------------------------------------------------------------------------

PresetParams SlowedReverb() {
  PresetParams p;
  p.rate = 0.85f;
  p.reverbMix = 0.28f;
  p.reverbRoom = 0.72f;
  p.reverbDamp = 0.42f;
  p.reverbWidth = 1.0f;
  p.reverbPreDelayMs = 20.0f;
  p.bassGainDb = 2.0f;
  p.bassFreqHz = 120.0f;
  return p;
}

PresetParams Nightcore() {
  PresetParams p;
  p.rate = 1.25f;
  p.reverbMix = 0.06f;
  p.reverbRoom = 0.40f;
  p.reverbDamp = 0.50f;
  p.trebleGainDb = 1.5f;
  return p;
}

PresetParams BassBoost() {
  PresetParams p;
  p.bassGainDb = 6.0f;
  p.bassFreqHz = 90.0f;
  p.outputGainDb = -1.0f;
  return p;
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

void TestParamParsing() {
  std::printf("params parsing\n");

  const PresetParams parsed = ParsePresetParams(
      "rate=0.85; reverbMix=0.3 ;reverbRoom=0.7;bassGainDb=-3;limiterEnabled=false");
  CheckNear(parsed.rate, 0.85, 1e-6, "rate parsed");
  CheckNear(parsed.reverbMix, 0.3, 1e-6, "reverbMix parsed despite surrounding spaces");
  CheckNear(parsed.reverbRoom, 0.7, 1e-6, "reverbRoom parsed");
  CheckNear(parsed.bassGainDb, -3.0, 1e-6, "negative gain parsed");
  Check(!parsed.limiterEnabled, "limiterEnabled=false parsed");

  const PresetParams defaults = ParsePresetParams("");
  CheckNear(defaults.rate, 1.0, 1e-6, "empty spec keeps default rate");
  Check(defaults.limiterEnabled, "limiter defaults on");

  // A preset written by a newer build must not break an older one.
  const PresetParams forward = ParsePresetParams("rate=1.1;someFutureKnob=9;;garbage");
  CheckNear(forward.rate, 1.1, 1e-6, "unknown keys and junk entries are skipped");

  // Out-of-range values from a hand-edited custom preset get clamped, not honoured.
  const PresetParams clamped = ParsePresetParams("rate=99;reverbRoom=5;bassGainDb=200");
  CheckNear(clamped.rate, 2.0, 1e-6, "rate clamped to max");
  CheckNear(clamped.reverbRoom, 0.97, 1e-6, "room clamped below self-oscillation");
  CheckNear(clamped.bassGainDb, 24.0, 1e-6, "gain clamped");

  const PresetParams malformed = ParsePresetParams("rate=abc");
  CheckNear(malformed.rate, 1.0, 1e-6, "unparseable value leaves the default");
}

void TestPassthroughIsTransparent() {
  std::printf("passthrough\n");

  PresetParams params;  // all defaults: rate 1, no reverb, no EQ, limiter on
  const std::vector<float> input = MakeSine(440.0, 0.5, 0.5);
  const RenderResult result = Render(params, input, 512);

  Check(AllFinite(result.samples), "output is finite");

  // The limiter delays by its lookahead; nothing else changes the signal.
  PresetChain probe;
  probe.Prepare(params, kSampleRate);
  const size_t latency = result.samples.size() / 2 - input.size() / 2;
  Check(latency > 0 && latency < static_cast<size_t>(kSampleRate * 0.02),
        "output is input length plus a small limiter latency");

  double maxDelta = 0.0;
  for (size_t frame = 0; frame + latency < input.size() / 2; ++frame) {
    for (int ch = 0; ch < 2; ++ch) {
      const double delta = std::fabs(result.samples[(frame + latency) * 2 + ch] -
                                     input[frame * 2 + ch]);
      maxDelta = std::max(maxDelta, delta);
    }
  }
  Check(maxDelta < 1e-5, "a below-ceiling signal passes through untouched");
}

void TestRateChangesLength() {
  std::printf("rate\n");

  const std::vector<float> input = MakeSine(440.0, 0.4, 2.0);
  const size_t inFrames = input.size() / 2;

  const RenderResult slowed = Render(SlowedReverb(), input, 1024);
  const double slowedRatio = static_cast<double>(slowed.framesBeforeFinish) / inFrames;
  CheckNear(slowedRatio, 1.0 / 0.85, 0.01, "slowed output is longer by 1/rate");

  const RenderResult fast = Render(Nightcore(), input, 1024);
  const double fastRatio = static_cast<double>(fast.framesBeforeFinish) / inFrames;
  CheckNear(fastRatio, 1.0 / 1.25, 0.01, "nightcore output is shorter by 1/rate");

  const RenderResult flat = Render(BassBoost(), input, 1024);
  CheckNear(static_cast<double>(flat.framesBeforeFinish) / inFrames, 1.0, 0.001,
            "rate 1 preserves length");
}

void TestRatePreservesPitchRelationship() {
  std::printf("rate moves pitch with tempo\n");

  // A 1 kHz tone slowed to 0.85 must come back at 850 Hz. Counting zero crossings is
  // enough to prove pitch tracked the rate rather than being corrected away.
  PresetParams params;
  params.rate = 0.85f;
  params.limiterEnabled = false;

  const std::vector<float> input = MakeSine(1000.0, 0.5, 1.0);
  const RenderResult result = Render(params, input, 4096);

  size_t crossings = 0;
  for (size_t frame = 1; frame < result.framesBeforeFinish; ++frame) {
    const float previous = result.samples[(frame - 1) * 2];
    const float current = result.samples[frame * 2];
    if (previous <= 0.0f && current > 0.0f) ++crossings;
  }
  const double seconds = static_cast<double>(result.framesBeforeFinish) / kSampleRate;
  const double measuredHz = static_cast<double>(crossings) / seconds;
  CheckNear(measuredHz, 850.0, 5.0, "1 kHz slowed to 0.85 reads as 850 Hz");
}

void TestStreamingMatchesSingleBlock() {
  std::printf("block-size invariance\n");

  // The resampler carries interpolation history across calls; if that seam is wrong,
  // the result depends on how the file happened to be chunked.
  const std::vector<float> input = MakeSine(523.25, 0.4, 0.75);

  const RenderResult whole = Render(SlowedReverb(), input, input.size() / 2);
  const RenderResult chunked = Render(SlowedReverb(), input, 997);
  const RenderResult tiny = Render(SlowedReverb(), input, 1);

  Check(whole.samples.size() == chunked.samples.size(), "chunked render has the same length");
  Check(whole.samples.size() == tiny.samples.size(), "single-frame render has the same length");

  double maxDelta = 0.0;
  const size_t common = std::min(whole.samples.size(), chunked.samples.size());
  for (size_t i = 0; i < common; ++i) {
    maxDelta = std::max(maxDelta, static_cast<double>(std::fabs(whole.samples[i] - chunked.samples[i])));
  }
  Check(maxDelta < 1e-6, "output is independent of block size");

  double maxTinyDelta = 0.0;
  const size_t commonTiny = std::min(whole.samples.size(), tiny.samples.size());
  for (size_t i = 0; i < commonTiny; ++i) {
    maxTinyDelta = std::max(maxTinyDelta, static_cast<double>(std::fabs(whole.samples[i] - tiny.samples[i])));
  }
  Check(maxTinyDelta < 1e-6, "output is correct even at one frame per call");
}

void TestReverbTail() {
  std::printf("reverb tail\n");

  const std::vector<float> input = MakeSine(440.0, 0.4, 1.0);
  const RenderResult result = Render(SlowedReverb(), input, 1024);

  Check(AllFinite(result.samples), "reverb output is finite");
  Check(result.samples.size() / 2 > result.framesBeforeFinish,
        "Finish() appends a tail beyond the input");

  const size_t tailStart = result.framesBeforeFinish;
  const size_t tailFrames = result.samples.size() / 2 - tailStart;
  Check(tailFrames > static_cast<size_t>(kSampleRate * 0.5), "tail is at least half a second");

  // The tail must be audible at the start and decayed to near-silence by the end,
  // which is what proves the flush length is matched to the actual decay.
  const double tailHeadRms = RmsOf(result.samples, tailStart, tailStart + tailFrames / 10);
  const double tailEndRms = RmsOf(result.samples, tailStart + (tailFrames * 9) / 10);
  Check(tailHeadRms > 1e-4, "reverb tail carries real signal");
  Check(tailEndRms < tailHeadRms * 0.2, "reverb tail decays");

  // With no reverb there is nothing to ring out.
  const RenderResult dry = Render(BassBoost(), input, 1024);
  const size_t dryTail = dry.samples.size() / 2 - dry.framesBeforeFinish;
  Check(dryTail < static_cast<size_t>(kSampleRate * 0.02), "a dry preset flushes only limiter latency");
}

void TestLimiterHoldsCeiling() {
  std::printf("limiter\n");

  // Deliberately abusive: a full-scale input with a big bass boost on top.
  PresetParams params;
  params.bassGainDb = 12.0f;
  params.bassFreqHz = 200.0f;
  params.reverbMix = 0.5f;
  params.reverbRoom = 0.9f;
  params.limiterEnabled = true;
  params.limiterCeilingDb = -0.3f;

  const std::vector<float> input = MakeSine(80.0, 1.0, 1.0);
  const RenderResult result = Render(params, input, 512);

  Check(AllFinite(result.samples), "limited output is finite");
  const double ceiling = std::pow(10.0, -0.3 / 20.0);
  Check(PeakOf(result.samples) <= ceiling + 1e-6, "peak never exceeds the ceiling");

  // And confirm it is actually doing work rather than the signal just being quiet.
  PresetParams unlimited = params;
  unlimited.limiterEnabled = false;
  const RenderResult hot = Render(unlimited, input, 512);
  Check(PeakOf(hot.samples) > ceiling, "the same signal would clip without the limiter");
}

void TestBassBoostRaisesLowEnd() {
  std::printf("bass shelf\n");

  // Measure the BassBoost preset's actual gain at a single frequency. A pure tone in
  // means the overall RMS change IS the response at that frequency. Amplitudes stay
  // low enough that the limiter never engages and colours the reading.
  const auto gainAtDb = [](double freqHz) {
    const std::vector<float> input = MakeSine(freqHz, 0.2, 1.0);
    const RenderResult out = Render(BassBoost(), input, 1024);
    return ToDb(RmsOf(out.samples, 0, out.framesBeforeFinish)) - ToDb(RmsOf(input));
  };

  // The preset is a +6 dB shelf at 90 Hz followed by a -1 dB output trim, so the
  // response should sweep from +5 dB deep in the bass to -1 dB above the shelf,
  // crossing the shelf's half-gain point (+3 dB, so +2 dB net) at the corner itself.
  // Checking the shape rather than one number is what catches a mis-derived filter.
  CheckNear(gainAtDb(25.0), 5.0, 0.6, "approaches the full +6 dB shelf below the corner");
  CheckNear(gainAtDb(90.0), 2.0, 0.7, "sits at half the shelf gain at the corner");
  CheckNear(gainAtDb(8000.0), -1.0, 0.5, "leaves the top end to the output trim alone");

  // And the response must be monotonic downward across the shelf.
  Check(gainAtDb(25.0) > gainAtDb(90.0), "shelf falls from the bass to the corner");
  Check(gainAtDb(90.0) > gainAtDb(8000.0), "shelf falls from the corner to the top end");
}

void TestAntiAliasingOnSpeedUp() {
  std::printf("anti-aliasing\n");

  // At rate 1.25 a 20 kHz tone lands at 25 kHz — past Nyquist. Without the pre-filter
  // it would fold back down to ~19 kHz as a clearly audible artefact.
  PresetParams params;
  params.rate = 1.25f;
  params.limiterEnabled = false;

  const std::vector<float> input = MakeSine(20000.0, 0.5, 0.5);
  const RenderResult result = Render(params, input, 1024);

  Check(AllFinite(result.samples), "resampled output is finite");
  const double attenuationDb = ToDb(RmsOf(result.samples, 0, result.framesBeforeFinish)) -
                               ToDb(RmsOf(input));
  Check(attenuationDb < -12.0, "content above the new Nyquist is filtered out, not folded back");

  // The same rate must leave a normal midrange tone alone.
  const std::vector<float> mid = MakeSine(1000.0, 0.5, 0.5);
  const RenderResult midOut = Render(params, mid, 1024);
  const double midDb = ToDb(RmsOf(midOut.samples, 0, midOut.framesBeforeFinish)) - ToDb(RmsOf(mid));
  CheckNear(midDb, 0.0, 0.5, "the anti-alias filter does not touch the midrange");
}

void TestSilenceStaysSilent() {
  std::printf("silence\n");

  const std::vector<float> input(kSampleRate * 2, 0.0f);
  const RenderResult result = Render(SlowedReverb(), input, 1024);

  Check(AllFinite(result.samples), "silence stays finite");
  // The denormal guard injects a constant far below the 16-bit noise floor; anything
  // larger would mean the reverb is generating audible noise from nothing.
  Check(PeakOf(result.samples) < 1e-9, "silence in, silence out");
}

void TestEmptyInput() {
  std::printf("degenerate input\n");

  PresetChain chain;
  chain.Prepare(SlowedReverb(), kSampleRate);
  std::vector<float> out;
  chain.Process(nullptr, 0, out);
  Check(out.empty(), "zero-frame Process produces nothing");
  chain.Finish(out);
  Check(AllFinite(out), "Finish on an empty stream is finite");

  // A file shorter than the interpolation window must not read out of bounds.
  const std::vector<float> tiny = MakeSine(440.0, 0.3, 0.0001);
  const RenderResult result = Render(SlowedReverb(), tiny, 4096);
  Check(AllFinite(result.samples), "a sub-millisecond file renders without garbage");
}

}  // namespace

int main() {
  std::printf("audio preset DSP tests\n\n");

  TestParamParsing();
  TestPassthroughIsTransparent();
  TestRateChangesLength();
  TestRatePreservesPitchRelationship();
  TestStreamingMatchesSingleBlock();
  TestReverbTail();
  TestLimiterHoldsCeiling();
  TestBassBoostRaisesLowEnd();
  TestAntiAliasingOnSpeedUp();
  TestSilenceStaysSilent();
  TestEmptyInput();

  std::printf("\n%d checks, %d failure(s)\n", g_checks, g_failures);
  return g_failures == 0 ? 0 : 1;
}
