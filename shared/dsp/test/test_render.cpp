// Host-side integration tests for the ffmpeg pipe + preset render path.
//
// These drive the REAL render function against the system ffmpeg/ffprobe, so the
// process plumbing (fork/exec, pipes, EOF handling, stderr capture, cancellation) is
// exercised for real rather than mocked. On device the same code runs against the
// bundled libffmpeg.so / libffprobe.so instead.
//
// Skipped with a clear message if ffmpeg is not installed.

#include <sys/stat.h>
#include <unistd.h>

#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#include "../ffmpeg_pipe.h"

using arsivinyo::audio::ProbeSource;
using arsivinyo::audio::RenderPreset;
using arsivinyo::audio::RenderRequest;
using arsivinyo::audio::SourceInfo;

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
    std::printf("  FAIL  %s (got %.4f, expected %.4f +/- %.4f)\n", what.c_str(), actual, expected,
                tolerance);
  }
}

/** execv needs a real path, so resolve the tool by scanning PATH ourselves. */
std::string Which(const std::string& tool) {
  const char* pathEnv = ::getenv("PATH");
  if (pathEnv == nullptr) return {};
  std::string path(pathEnv);
  size_t start = 0;
  while (start <= path.size()) {
    size_t end = path.find(':', start);
    if (end == std::string::npos) end = path.size();
    const std::string dir = path.substr(start, end - start);
    if (!dir.empty()) {
      const std::string candidate = dir + "/" + tool;
      if (::access(candidate.c_str(), X_OK) == 0) return candidate;
    }
    start = end + 1;
  }
  return {};
}

bool FileExists(const std::string& path) {
  struct stat st {};
  return ::stat(path.c_str(), &st) == 0;
}

long FileSize(const std::string& path) {
  struct stat st {};
  if (::stat(path.c_str(), &st) != 0) return -1;
  return static_cast<long>(st.st_size);
}

std::string g_ffmpeg;
std::string g_ffprobe;
std::string g_tmp;

/** Build a test source file with ffmpeg's own signal generator. */
bool MakeSource(const std::string& path, double seconds, int sampleRate) {
  const std::string command = "\"" + g_ffmpeg + "\" -nostdin -v error -f lavfi -i " +
                              "\"sine=frequency=440:sample_rate=" + std::to_string(sampleRate) +
                              ":duration=" + std::to_string(seconds) + "\"" +
                              " -ac 2 -c:a pcm_s16le -y \"" + path + "\" 2>/dev/null";
  return std::system(command.c_str()) == 0 && FileExists(path);
}

/**
 * Build an incompressible test source. A pure tone is useless for checking a bitrate
 * setting: AAC encodes it far below any target because there is almost no information
 * in it, so the measured rate says nothing about what was requested. White noise makes
 * the encoder actually spend its budget.
 */
bool MakeNoiseSource(const std::string& path, double seconds, int sampleRate) {
  const std::string command = "\"" + g_ffmpeg + "\" -nostdin -v error -f lavfi -i " +
                              "\"anoisesrc=color=white:sample_rate=" + std::to_string(sampleRate) +
                              ":duration=" + std::to_string(seconds) + "\"" +
                              " -ac 2 -c:a pcm_s16le -y \"" + path + "\" 2>/dev/null";
  return std::system(command.c_str()) == 0 && FileExists(path);
}

/**
 * Read one ffprobe field from a file. `entry` is passed straight to -show_entries.
 *
 * Note the stream filter is applied only for stream entries: `-select_streams`
 * suppresses the format section outright, so asking for format_tags with it set
 * silently returns nothing.
 */
std::string Probe(const std::string& path, const std::string& entry) {
  const bool isStreamEntry = entry.rfind("stream", 0) == 0;
  const std::string selector = isStreamEntry ? "-select_streams a:0 " : "";
  const std::string command = "\"" + g_ffprobe + "\" -v error " + selector + "-show_entries " +
                              entry + " -of default=noprint_wrappers=1:nokey=1 \"" + path +
                              "\" 2>/dev/null";
  FILE* pipe = ::popen(command.c_str(), "r");
  if (pipe == nullptr) return {};
  char buffer[256] = {0};
  std::string out;
  while (std::fgets(buffer, sizeof(buffer), pipe) != nullptr) out += buffer;
  ::pclose(pipe);
  while (!out.empty() && (out.back() == '\n' || out.back() == '\r')) out.pop_back();
  return out;
}

RenderRequest BaseRequest(const std::string& in, const std::string& out) {
  RenderRequest r;
  r.ffmpegPath = g_ffmpeg;
  r.ffprobePath = g_ffprobe;
  r.inputPath = in;
  r.outputPath = out;
  // SlowedReverb.
  r.paramsSpec = "rate=0.85;reverbMix=0.28;reverbRoom=0.72;reverbDamp=0.42;reverbWidth=1.0;"
                 "reverbPreDelayMs=20;bassGainDb=2;bassFreqHz=120";
  r.outputFormat = "flac";
  return r;
}

// ---------------------------------------------------------------------------

void TestProbe() {
  std::printf("probe\n");
  const std::string src = g_tmp + "/src48.wav";
  Check(MakeSource(src, 2.0, 48000), "built a 48 kHz test source");

  SourceInfo info;
  std::string error;
  Check(ProbeSource(g_ffprobe, src, &info, &error), "ProbeSource succeeds: " + error);
  Check(info.sampleRate == 48000, "reads the sample rate");
  Check(info.channels == 2, "reads the channel count");
  CheckNear(info.durationSeconds, 2.0, 0.1, "reads the duration");

  // A missing file must fail cleanly rather than hang or crash.
  SourceInfo missing;
  std::string missingError;
  Check(!ProbeSource(g_ffprobe, g_tmp + "/does-not-exist.wav", &missing, &missingError),
        "a missing input fails");
  Check(!missingError.empty(), "a missing input reports why");
}

void TestRenderPreservesSampleRate() {
  std::printf("render: sample rate\n");
  const std::string src = g_tmp + "/src48.wav";
  const std::string out = g_tmp + "/out48.flac";
  ::unlink(out.c_str());

  std::string error;
  Check(RenderPreset(BaseRequest(src, out), &error), "render succeeds: " + error);
  Check(FileExists(out), "output file exists");
  Check(FileSize(out) > 1000, "output file is not empty");

  Check(Probe(out, "stream=codec_name") == "flac", "output is FLAC");
  Check(Probe(out, "stream=sample_fmt") == "s16", "output is 16-bit");
  // The whole point: a 48 kHz source must NOT come back at 44.1 kHz.
  Check(Probe(out, "stream=sample_rate") == "48000", "source sample rate is preserved");

  // A 44.1 kHz source must likewise stay at 44.1 kHz.
  const std::string src441 = g_tmp + "/src441.wav";
  const std::string out441 = g_tmp + "/out441.flac";
  Check(MakeSource(src441, 1.0, 44100), "built a 44.1 kHz test source");
  Check(RenderPreset(BaseRequest(src441, out441), &error), "44.1 kHz render succeeds: " + error);
  Check(Probe(out441, "stream=sample_rate") == "44100", "44.1 kHz is preserved too");
}

void TestRenderAppliesRate() {
  std::printf("render: slowdown changes duration\n");
  const std::string src = g_tmp + "/src48.wav";
  const std::string out = g_tmp + "/slowed.flac";

  std::string error;
  Check(RenderPreset(BaseRequest(src, out), &error), "render succeeds: " + error);

  const std::string durationText = Probe(out, "format=duration");
  const double duration = durationText.empty() ? 0.0 : std::atof(durationText.c_str());
  // 2 s at rate 0.85 is ~2.35 s, plus the reverb tail that Finish() flushes.
  Check(duration > 2.0 / 0.85, "slowed output is longer than the source over the rate");
  Check(duration < 2.0 / 0.85 + 13.0, "the flushed tail is bounded, not runaway");
}

void TestM4aOutput() {
  std::printf("render: m4a branch\n");
  const std::string src = g_tmp + "/src48.wav";
  const std::string out = g_tmp + "/out.m4a";

  RenderRequest request = BaseRequest(src, out);
  request.outputFormat = "m4a";
  request.title = "Test Title";
  request.artist = "Test Artist";

  std::string error;
  Check(RenderPreset(request, &error), "m4a render succeeds: " + error);
  Check(Probe(out, "stream=codec_name") == "aac", "output is AAC");

  // 320k, not the downloader's 256k: this branch only runs when the source was
  // already lossy, so the extra headroom limits second-generation loss. Measured on
  // noise, since a tone would compress far below the target regardless of the setting.
  const std::string noiseSrc = g_tmp + "/noise.wav";
  const std::string noiseOut = g_tmp + "/noise.m4a";
  Check(MakeNoiseSource(noiseSrc, 3.0, 48000), "built a noise test source");
  RenderRequest noiseRequest = BaseRequest(noiseSrc, noiseOut);
  noiseRequest.outputFormat = "m4a";
  std::string noiseError;
  Check(RenderPreset(noiseRequest, &noiseError), "noise render succeeds: " + noiseError);
  const std::string bitrateText = Probe(noiseOut, "stream=bit_rate");
  const long bitrate = bitrateText.empty() ? 0 : std::atol(bitrateText.c_str());
  Check(bitrate > 280000, "lossy renders target a bitrate above the download default, got " +
                              std::to_string(bitrate));
  Check(Probe(out, "format_tags=title") == "Test Title", "title metadata is written");
  Check(Probe(out, "format_tags=artist") == "Test Artist", "artist metadata is written");
}

void TestCancellation() {
  std::printf("render: cancellation\n");
  // A long source so the cancel flag is seen mid-render rather than after it finishes.
  const std::string src = g_tmp + "/long.wav";
  const std::string out = g_tmp + "/cancelled.flac";
  const std::string flag = g_tmp + "/cancel.flag";
  Check(MakeSource(src, 60.0, 48000), "built a long test source");

  // Pre-create the flag: the loop checks before its first read, so this is
  // deterministic rather than a race against the render.
  FILE* f = std::fopen(flag.c_str(), "wb");
  if (f) std::fclose(f);

  RenderRequest request = BaseRequest(src, out);
  request.cancelFlagPath = flag;
  ::unlink(out.c_str());

  std::string error;
  Check(!RenderPreset(request, &error), "a cancelled render reports failure");
  Check(error == "RENDER_CANCELLED", "cancellation is reported as such, got: " + error);
  Check(!FileExists(out), "a cancelled render leaves no partial output file");
  ::unlink(flag.c_str());
}

void TestProgressFile() {
  std::printf("render: progress file\n");
  const std::string src = g_tmp + "/long.wav";
  const std::string out = g_tmp + "/progress.flac";
  const std::string progress = g_tmp + "/progress.json";
  ::unlink(progress.c_str());

  RenderRequest request = BaseRequest(src, out);
  request.progressFilePath = progress;

  std::string error;
  Check(RenderPreset(request, &error), "render with progress succeeds: " + error);
  Check(FileExists(progress), "progress file was written");

  FILE* f = std::fopen(progress.c_str(), "rb");
  std::string content;
  if (f) {
    char buffer[256] = {0};
    const size_t got = std::fread(buffer, 1, sizeof(buffer) - 1, f);
    content.assign(buffer, got);
    std::fclose(f);
  }
  Check(content.find("100.00") != std::string::npos,
        "progress ends at 100, got: " + content);
}

void TestBadInput() {
  std::printf("render: bad input\n");
  const std::string out = g_tmp + "/never.flac";
  ::unlink(out.c_str());

  std::string error;
  Check(!RenderPreset(BaseRequest(g_tmp + "/nope.wav", out), &error),
        "a missing input fails the render");
  Check(!error.empty(), "the failure explains itself");
  Check(!FileExists(out), "no output file is left behind");

  // A file that exists but holds no audio must also fail cleanly.
  const std::string junk = g_tmp + "/junk.bin";
  FILE* f = std::fopen(junk.c_str(), "wb");
  if (f) {
    std::fputs("this is not audio", f);
    std::fclose(f);
  }
  std::string junkError;
  Check(!RenderPreset(BaseRequest(junk, out), &junkError), "a non-audio file fails the render");
}

}  // namespace

int main() {
  g_ffmpeg = Which("ffmpeg");
  g_ffprobe = Which("ffprobe");
  if (g_ffmpeg.empty() || g_ffprobe.empty()) {
    std::printf("SKIP: ffmpeg/ffprobe not found on PATH; render tests need them.\n");
    return 0;
  }

  const char* tmpEnv = ::getenv("TMPDIR");
  g_tmp = std::string(tmpEnv && *tmpEnv ? tmpEnv : "/tmp") + "/arsivinyo-render-test";
  ::mkdir(g_tmp.c_str(), 0700);

  std::printf("audio preset render tests\nffmpeg: %s\n\n", g_ffmpeg.c_str());

  TestProbe();
  TestRenderPreservesSampleRate();
  TestRenderAppliesRate();
  TestM4aOutput();
  TestCancellation();
  TestProgressFile();
  TestBadInput();

  std::printf("\n%d checks, %d failure(s)\n", g_checks, g_failures);
  return g_failures == 0 ? 0 : 1;
}
