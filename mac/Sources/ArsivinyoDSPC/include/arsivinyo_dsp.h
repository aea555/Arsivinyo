#pragma once

// A C boundary over shared/dsp, for the same reason as the crypto one: the C++ surface is
// structs of std::string, which Swift interop handles worse than a flat C call.

#ifdef __cplusplus
extern "C" {
#endif

/**
 * Renders `input` through a preset into `output`. Blocks until it is done, so call it off
 * the main thread.
 *
 * `spec` is the `key=value;` preset string the phone also sends. `format` is "flac" or
 * "m4a". `progressFile` and `cancelFlag` may be NULL; when given, the render rewrites a
 * JSON percentage into the first and stops once the second exists.
 *
 * Returns 1 on success. On failure returns 0 and writes a message into `error`, which is
 * always NUL-terminated when `errorCapacity` is above zero.
 */
int av_render_preset(const char *ffmpeg, const char *ffprobe, const char *input, const char *output,
                     const char *spec, const char *format, const char *title, const char *artist,
                     const char *progressFile, const char *cancelFlag,
                     char *error, unsigned long errorCapacity);

#ifdef __cplusplus
}
#endif
