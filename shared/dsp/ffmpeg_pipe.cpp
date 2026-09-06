#include "ffmpeg_pipe.h"

#include <fcntl.h>
#include <signal.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <unistd.h>

#include <cerrno>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <vector>

#include "dsp/preset_chain.h"
#include "preset_params.h"

namespace arsivinyo::audio {
namespace {

/** Frames per DSP block. 8k frames is ~64 KB of float stereo — one pipe buffer's worth. */
constexpr size_t kBlockFrames = 8192;
constexpr int kChannels = 2;
/** How often the progress file is rewritten. Matches the Python downloader's cadence. */
constexpr long kProgressIntervalMs = 500;

long NowMs() {
  return static_cast<long>(std::chrono::duration_cast<std::chrono::milliseconds>(
                               std::chrono::steady_clock::now().time_since_epoch())
                               .count());
}

bool FileExists(const std::string& path) {
  if (path.empty()) return false;
  struct stat st {};
  return ::stat(path.c_str(), &st) == 0;
}

std::string ReadTail(const std::string& path, size_t maxBytes) {
  if (path.empty()) return {};
  FILE* f = std::fopen(path.c_str(), "rb");
  if (f == nullptr) return {};
  std::fseek(f, 0, SEEK_END);
  const long size = std::ftell(f);
  const long offset = size > static_cast<long>(maxBytes) ? size - static_cast<long>(maxBytes) : 0;
  std::fseek(f, offset, SEEK_SET);
  std::string out;
  out.resize(static_cast<size_t>(size - offset));
  const size_t got = std::fread(out.data(), 1, out.size(), f);
  out.resize(got);
  std::fclose(f);
  // Trim trailing newlines so the message reads as one line.
  while (!out.empty() && (out.back() == '\n' || out.back() == '\r')) out.pop_back();
  return out;
}

void WriteProgress(const std::string& path, double percent) {
  if (path.empty()) return;
  const std::string tmp = path + ".tmp";
  FILE* f = std::fopen(tmp.c_str(), "wb");
  if (f == nullptr) return;
  std::fprintf(f, "{\"percent\":%.2f}", percent);
  std::fclose(f);
  // Rename so a reader never observes a half-written file.
  ::rename(tmp.c_str(), path.c_str());
}

std::string UniqueTempPath(const char* tag) {
  static int counter = 0;
  const char* base = ::getenv("TMPDIR");
  if (base == nullptr || *base == '\0') base = "/data/local/tmp";
  struct stat st {};
  if (::stat(base, &st) != 0) base = "/tmp";
  return std::string(base) + "/arsivinyo-" + tag + "-" + std::to_string(::getpid()) + "-" +
         std::to_string(counter++);
}

/** Parse `key=value` lines from ffprobe's default output format. */
std::string FindValue(const std::string& text, const std::string& key) {
  const std::string needle = key + "=";
  size_t pos = 0;
  while ((pos = text.find(needle, pos)) != std::string::npos) {
    // Must be at the start of a line.
    if (pos == 0 || text[pos - 1] == '\n' || text[pos - 1] == '\r') {
      const size_t start = pos + needle.size();
      size_t end = text.find_first_of("\r\n", start);
      if (end == std::string::npos) end = text.size();
      return text.substr(start, end - start);
    }
    pos += needle.size();
  }
  return {};
}

}  // namespace

// ---------------------------------------------------------------------------
// FfmpegProcess
// ---------------------------------------------------------------------------

FfmpegProcess::~FfmpegProcess() {
  if (pid_ > 0) {
    Kill();
    Finish(nullptr);
  }
  if (fd_ >= 0) ::close(fd_);
  CleanupStderrFile();
}

void FfmpegProcess::CleanupStderrFile() {
  if (!stderrPath_.empty()) {
    ::unlink(stderrPath_.c_str());
    stderrPath_.clear();
  }
}

bool FfmpegProcess::Start(const std::vector<std::string>& argv, bool weRead, std::string* error) {
  if (argv.empty()) {
    if (error) *error = "EMPTY_ARGV";
    return false;
  }

  int pipeFds[2];
  if (::pipe(pipeFds) != 0) {
    if (error) *error = std::string("PIPE_FAILED: ") + std::strerror(errno);
    return false;
  }

  stderrPath_ = UniqueTempPath("ffstderr");

  // Build the argv array before forking: the child may only call async-signal-safe
  // functions between fork and exec, and allocating is not one of them.
  std::vector<char*> raw;
  raw.reserve(argv.size() + 1);
  for (const std::string& arg : argv) raw.push_back(const_cast<char*>(arg.c_str()));
  raw.push_back(nullptr);

  const pid_t pid = ::fork();
  if (pid < 0) {
    ::close(pipeFds[0]);
    ::close(pipeFds[1]);
    if (error) *error = std::string("FORK_FAILED: ") + std::strerror(errno);
    return false;
  }

  if (pid == 0) {
    // Child. Only async-signal-safe calls from here to execv.
    if (weRead) {
      ::close(pipeFds[0]);
      ::dup2(pipeFds[1], STDOUT_FILENO);
      ::close(pipeFds[1]);
    } else {
      ::close(pipeFds[1]);
      ::dup2(pipeFds[0], STDIN_FILENO);
      ::close(pipeFds[0]);
    }
    const int errFd = ::open(stderrPath_.c_str(), O_WRONLY | O_CREAT | O_TRUNC, 0600);
    if (errFd >= 0) {
      ::dup2(errFd, STDERR_FILENO);
      ::close(errFd);
    }
    ::execv(raw[0], raw.data());
    ::_exit(127);  // exec failed
  }

  // Parent.
  if (weRead) {
    ::close(pipeFds[1]);
    fd_ = pipeFds[0];
  } else {
    ::close(pipeFds[0]);
    fd_ = pipeFds[1];
  }
  pid_ = pid;
  return true;
}

ssize_t FfmpegProcess::Read(void* buffer, size_t bytes) {
  if (fd_ < 0) return -1;
  size_t total = 0;
  auto* out = static_cast<uint8_t*>(buffer);
  while (total < bytes) {
    const ssize_t got = ::read(fd_, out + total, bytes - total);
    if (got < 0) {
      if (errno == EINTR) continue;
      return -1;
    }
    if (got == 0) break;  // end of stream
    total += static_cast<size_t>(got);
  }
  return static_cast<ssize_t>(total);
}

bool FfmpegProcess::WriteAll(const void* buffer, size_t bytes) {
  if (fd_ < 0) return false;
  const auto* in = static_cast<const uint8_t*>(buffer);
  size_t total = 0;
  while (total < bytes) {
    const ssize_t put = ::write(fd_, in + total, bytes - total);
    if (put < 0) {
      if (errno == EINTR) continue;
      return false;  // EPIPE means the encoder died; the caller reports its stderr
    }
    total += static_cast<size_t>(put);
  }
  return true;
}

int FfmpegProcess::Finish(std::string* stderrTail) {
  // Close our end FIRST. The encoder is reading until EOF; without this it never
  // finishes and waitpid blocks forever.
  if (fd_ >= 0) {
    ::close(fd_);
    fd_ = -1;
  }

  int status = -1;
  if (pid_ > 0) {
    while (::waitpid(pid_, &status, 0) < 0) {
      if (errno != EINTR) {
        status = -1;
        break;
      }
    }
    pid_ = -1;
  }

  if (stderrTail != nullptr) *stderrTail = ReadTail(stderrPath_, 2048);
  CleanupStderrFile();

  if (status < 0) return -1;
  if (WIFEXITED(status)) return WEXITSTATUS(status);
  return -1;
}

void FfmpegProcess::Kill() {
  if (pid_ > 0) ::kill(pid_, SIGKILL);
}

// ---------------------------------------------------------------------------
// ProbeSource
// ---------------------------------------------------------------------------

bool ProbeSource(const std::string& ffprobePath, const std::string& inputPath, SourceInfo* out,
                 std::string* error) {
  const std::vector<std::string> argv = {
      ffprobePath, "-v",   "error",           "-select_streams", "a:0",
      "-show_entries", "stream=sample_rate,channels", "-show_entries", "format=duration",
      "-of",       "default=noprint_wrappers=1", inputPath,
  };

  FfmpegProcess probe;
  if (!probe.Start(argv, /*weRead=*/true, error)) return false;

  std::string text;
  char buffer[4096];
  while (true) {
    const ssize_t got = probe.Read(buffer, sizeof(buffer));
    if (got <= 0) break;
    text.append(buffer, static_cast<size_t>(got));
  }

  std::string stderrTail;
  const int status = probe.Finish(&stderrTail);
  if (status != 0) {
    if (error) {
      *error = "FFPROBE_FAILED(" + std::to_string(status) + ")" +
               (stderrTail.empty() ? "" : ": " + stderrTail);
    }
    return false;
  }

  const std::string rate = FindValue(text, "sample_rate");
  const std::string channels = FindValue(text, "channels");
  const std::string duration = FindValue(text, "duration");

  out->sampleRate = rate.empty() ? 0 : std::atoi(rate.c_str());
  out->channels = channels.empty() ? 0 : std::atoi(channels.c_str());
  out->durationSeconds = duration.empty() ? 0.0 : std::atof(duration.c_str());

  if (out->sampleRate <= 0) {
    if (error) *error = "NO_AUDIO_STREAM";
    return false;
  }
  return true;
}

// ---------------------------------------------------------------------------
// RenderPreset
// ---------------------------------------------------------------------------

bool RenderPreset(const RenderRequest& request, std::string* error) {
  SourceInfo source;
  if (!ProbeSource(request.ffprobePath, request.inputPath, &source, error)) return false;

  const int sampleRate = source.sampleRate;
  const std::string rateText = std::to_string(sampleRate);

  // Decode to raw float PCM. Forced to stereo because the DSP chain is stereo; ffmpeg
  // upmixes a mono source, which is cheaper and better tested than doing it ourselves.
  const std::vector<std::string> decodeArgv = {
      request.ffmpegPath, "-nostdin", "-v",   "error", "-i", request.inputPath,
      "-vn",              "-map",     "0:a:0",
      "-f",               "f32le",    "-acodec", "pcm_f32le",
      "-ac",              "2",        "-ar",  rateText,
      "-",
  };

  std::vector<std::string> encodeArgv = {
      request.ffmpegPath, "-nostdin", "-v", "error",
      "-f", "f32le", "-ar", rateText, "-ac", "2", "-i", "-",
  };
  if (request.outputFormat == "m4a") {
    // 320k, deliberately above the 256k the downloader uses. This branch only runs
    // when the SOURCE was already lossy, so the render is a second generation of AAC.
    // Generation loss is worst when re-encoding at the same bitrate; the extra
    // headroom absorbs most of it and still costs far less than inflating an
    // already-lossy track to FLAC.
    encodeArgv.insert(encodeArgv.end(), {"-c:a", "aac", "-b:a", "320k"});
  } else {
    // 16-bit FLAC with TPDF dither. The DSP works in float, so without dither the
    // truncation to 16 bits would add correlated distortion rather than benign noise.
    encodeArgv.insert(encodeArgv.end(),
                      {"-c:a", "flac", "-sample_fmt", "s16", "-dither_method", "triangular"});
  }
  if (!request.title.empty()) {
    encodeArgv.push_back("-metadata");
    encodeArgv.push_back("title=" + request.title);
  }
  if (!request.artist.empty()) {
    encodeArgv.push_back("-metadata");
    encodeArgv.push_back("artist=" + request.artist);
  }
  encodeArgv.push_back("-y");
  encodeArgv.push_back(request.outputPath);

  // A dead child would otherwise deliver SIGPIPE on the next write and take the whole
  // app down. Ignore it for the duration and let write() report EPIPE instead.
  struct sigaction ignoreAction {};
  struct sigaction previousAction {};
  ignoreAction.sa_handler = SIG_IGN;
  ::sigemptyset(&ignoreAction.sa_mask);
  ::sigaction(SIGPIPE, &ignoreAction, &previousAction);
  struct SigpipeRestore {
    struct sigaction* previous;
    ~SigpipeRestore() { ::sigaction(SIGPIPE, previous, nullptr); }
  } sigpipeRestore{&previousAction};

  FfmpegProcess decoder;
  FfmpegProcess encoder;
  if (!decoder.Start(decodeArgv, /*weRead=*/true, error)) return false;
  if (!encoder.Start(encodeArgv, /*weRead=*/false, error)) {
    decoder.Kill();
    decoder.Finish(nullptr);
    return false;
  }

  PresetChain chain;
  chain.Prepare(ParsePresetParams(request.paramsSpec), sampleRate);

  std::vector<float> input(kBlockFrames * kChannels);
  std::vector<float> output;
  output.reserve(kBlockFrames * kChannels * 2);

  const double expectedFrames =
      source.durationSeconds > 0.0 ? source.durationSeconds * sampleRate : 0.0;
  uint64_t framesRead = 0;
  long lastProgressMs = 0;
  bool cancelled = false;
  bool writeFailed = false;

  WriteProgress(request.progressFilePath, 0.0);

  while (true) {
    if (FileExists(request.cancelFlagPath)) {
      cancelled = true;
      break;
    }

    const ssize_t got = decoder.Read(input.data(), input.size() * sizeof(float));
    if (got < 0) {
      if (error) *error = "DECODE_READ_FAILED";
      writeFailed = true;
      break;
    }
    if (got == 0) break;  // decoder finished

    // A partial block at end of stream is normal; a partial FRAME is not.
    const size_t frames = static_cast<size_t>(got) / (sizeof(float) * kChannels);
    if (frames == 0) break;
    framesRead += frames;

    output.clear();
    chain.Process(input.data(), frames, output);
    if (!output.empty() &&
        !encoder.WriteAll(output.data(), output.size() * sizeof(float))) {
      writeFailed = true;
      break;
    }

    const long now = NowMs();
    if (expectedFrames > 0.0 && now - lastProgressMs >= kProgressIntervalMs) {
      lastProgressMs = now;
      const double percent = 100.0 * static_cast<double>(framesRead) / expectedFrames;
      WriteProgress(request.progressFilePath, percent < 99.0 ? percent : 99.0);
    }
  }

  // Flush the reverb tail and the limiter's lookahead. Skipping this would cut the
  // reverb off mid-decay.
  if (!cancelled && !writeFailed) {
    output.clear();
    chain.Finish(output);
    if (!output.empty()) {
      writeFailed = !encoder.WriteAll(output.data(), output.size() * sizeof(float));
    }
  }

  if (cancelled) {
    decoder.Kill();
    encoder.Kill();
    decoder.Finish(nullptr);
    encoder.Finish(nullptr);
    ::unlink(request.outputPath.c_str());
    if (error) *error = "RENDER_CANCELLED";
    return false;
  }

  std::string decoderStderr;
  std::string encoderStderr;
  decoder.Kill();  // it has already hit EOF; this only matters if we broke out early
  const int decodeStatus = decoder.Finish(&decoderStderr);
  const int encodeStatus = encoder.Finish(&encoderStderr);

  if (writeFailed || encodeStatus != 0) {
    ::unlink(request.outputPath.c_str());
    if (error) {
      *error = "ENCODE_FAILED(" + std::to_string(encodeStatus) + ")" +
               (encoderStderr.empty() ? "" : ": " + encoderStderr);
    }
    return false;
  }
  // The decoder is killed above once we have all its output, so a signal death is
  // expected and only a non-zero *exit* matters — and only if it produced nothing.
  if (framesRead == 0) {
    if (error) {
      *error = "DECODE_FAILED(" + std::to_string(decodeStatus) + ")" +
               (decoderStderr.empty() ? "" : ": " + decoderStderr);
    }
    ::unlink(request.outputPath.c_str());
    return false;
  }

  WriteProgress(request.progressFilePath, 100.0);
  return true;
}

}  // namespace arsivinyo::audio
