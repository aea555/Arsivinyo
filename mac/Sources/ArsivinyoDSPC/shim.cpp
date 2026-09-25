#include "arsivinyo_dsp.h"

#include <cstring>
#include <string>

#include "ffmpeg_pipe.h"

namespace {
std::string orEmpty(const char *text) { return text ? std::string(text) : std::string(); }
}  // namespace

extern "C" int av_render_preset(const char *ffmpeg, const char *ffprobe, const char *input,
                                const char *output, const char *spec, const char *format,
                                const char *title, const char *artist, const char *progressFile,
                                const char *cancelFlag, char *error, unsigned long errorCapacity) {
    arsivinyo::audio::RenderRequest request;
    request.ffmpegPath = orEmpty(ffmpeg);
    request.ffprobePath = orEmpty(ffprobe);
    request.inputPath = orEmpty(input);
    request.outputPath = orEmpty(output);
    request.paramsSpec = orEmpty(spec);
    request.outputFormat = format ? std::string(format) : std::string("flac");
    request.title = orEmpty(title);
    request.artist = orEmpty(artist);
    request.progressFilePath = orEmpty(progressFile);
    request.cancelFlagPath = orEmpty(cancelFlag);

    std::string message;
    const bool ok = arsivinyo::audio::RenderPreset(request, &message);
    if (!ok && error && errorCapacity > 0) {
        std::strncpy(error, message.c_str(), errorCapacity - 1);
        error[errorCapacity - 1] = '\0';
    }
    return ok ? 1 : 0;
}
