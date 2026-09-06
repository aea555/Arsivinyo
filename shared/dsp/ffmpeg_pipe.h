#pragma once

#include <sys/types.h>

#include <string>
#include <vector>

namespace arsivinyo::audio {

/** Parameters of the first audio stream in a source file, as reported by ffprobe. */
struct SourceInfo {
  int sampleRate = 0;
  int channels = 0;
  double durationSeconds = 0.0;
};

/**
 * Run ffprobe and read the first audio stream's parameters.
 *
 * The sample rate matters: the render keeps the SOURCE rate rather than forcing a
 * fixed one, because resampling a 48 kHz source to 44.1 kHz would degrade it for no
 * reason. The duration is used only to report progress.
 */
bool ProbeSource(const std::string& ffprobePath, const std::string& inputPath, SourceInfo* out,
                 std::string* error);

/**
 * A child ffmpeg process with a single pipe to or from us, and stderr captured to a
 * temp file.
 *
 * stderr goes to a FILE rather than a pipe on purpose. ffmpeg is chatty, and a stderr
 * pipe nobody drains fills its buffer and blocks the child forever — a deadlock that
 * only shows up on longer inputs. A file has no such limit and still lets us report
 * what ffmpeg actually said when it fails.
 */
class FfmpegProcess {
 public:
  ~FfmpegProcess();

  FfmpegProcess() = default;
  FfmpegProcess(const FfmpegProcess&) = delete;
  FfmpegProcess& operator=(const FfmpegProcess&) = delete;

  /**
   * Fork and exec `argv`. When `weRead` the child's stdout is piped to us; otherwise
   * our writes go to the child's stdin.
   */
  bool Start(const std::vector<std::string>& argv, bool weRead, std::string* error);

  /** Read up to `bytes`. Returns 0 at end of stream, negative on error. */
  ssize_t Read(void* buffer, size_t bytes);

  /** Write everything or fail. Returns false if the child went away (EPIPE). */
  bool WriteAll(const void* buffer, size_t bytes);

  /**
   * Close our pipe end and reap the child. Closing first matters for the encoder:
   * without EOF on its stdin it waits forever. Returns the exit status, or -1.
   * `stderrTail` receives the last of what the child printed, if requested.
   */
  int Finish(std::string* stderrTail);

  /** Terminate the child without waiting for it to finish its work. */
  void Kill();

  bool IsRunning() const { return pid_ > 0; }

 private:
  void CleanupStderrFile();

  pid_t pid_ = -1;
  int fd_ = -1;
  std::string stderrPath_;
};

/** Everything one preset render needs. */
struct RenderRequest {
  std::string ffmpegPath;
  std::string ffprobePath;
  std::string inputPath;
  std::string outputPath;
  /** `key=value;` preset spec — see ParsePresetParams. */
  std::string paramsSpec;
  /** "flac" (default, lossless) or "m4a". */
  std::string outputFormat = "flac";
  std::string title;
  std::string artist;
  /** Optional. A JSON percentage is rewritten here as the render proceeds. */
  std::string progressFilePath;
  /** Optional. If this file appears, the render aborts and both children are killed. */
  std::string cancelFlagPath;
};

/**
 * Render `inputPath` through the preset chain into `outputPath`.
 *
 * Decodes with ffmpeg to raw float PCM on a pipe, runs the DSP in this process, and
 * pipes the result into a second ffmpeg that encodes the final file. The DSP owns the
 * sound; ffmpeg is used only for the container and codec work it is already good at.
 *
 * Returns false and fills `error` on any failure, including cancellation.
 */
bool RenderPreset(const RenderRequest& request, std::string* error);

}  // namespace arsivinyo::audio
