#pragma once

// A C boundary over shared/faces, as over the crypto and the DSP: flat calls and plain
// arrays, which Swift takes far better than C++ classes.
//
// A signature is 128 floats. Sets of them are laid out one after another.

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

enum { AV_FACES_SIGNATURE = 128, AV_FACES_MAX_SET = 8, AV_FACES_MAX_FRAMES = 12 };

typedef struct av_faces av_faces;

/** One face seen in one frame. */
typedef struct {
    float x, y, w, h, score;
    float landmarks[10];
    float signature[AV_FACES_SIGNATURE];
    int32_t frameMs;
} av_face_sighting;

/** One person in one meme: sightings merged. */
typedef struct {
    float x, y, w, h, score;
    float signature[AV_FACES_SIGNATURE];
    int32_t frameMs;
    int32_t sightings;
} av_meme_face;

/** Loads the two models from their bytes. NULL on failure, with a message in `error`. */
av_faces *av_faces_load(const uint8_t *detector, size_t detectorSize, const uint8_t *recogniser,
                        size_t recogniserSize, char *error, size_t errorCapacity);
void av_faces_free(av_faces *faces);

/**
 * Every face worth keeping in a frame of RGBA or BGRA pixels (`bgra` nonzero for the
 * latter). Writes at most `capacity`; returns how many there were.
 */
int av_faces_look(av_faces *faces, const uint8_t *pixels, int width, int height, int stride, int bgra,
                  int32_t frameMs, av_face_sighting *out, int capacity);

/** Merges one meme's sightings into faces. Returns how many, writing at most `capacity`. */
int av_faces_merge(const av_face_sighting *sightings, int count, av_meme_face *out, int capacity);

float av_faces_cosine(const float *a, const float *b);
/** Highest cosine of `signature` against `count` signatures in `set`; -1 when empty. */
float av_faces_best(const float *signature, const float *set, int count);

/**
 * Adds `signature` to a set of `count` (capacity AV_FACES_MAX_SET), keeping it spread out.
 * Returns the new count.
 */
int av_faces_add_to_set(float *set, int count, const float *signature);

/** Group numbers for `count` signatures, largest group first, into `labels`. */
void av_faces_group(const float *signatures, int count, float threshold, int32_t *labels);

/** 256 bytes of half floats. */
void av_faces_encode(const float *signature, uint8_t *out);
/** Returns 1 and fills `signature` if the bytes are a valid signature. */
int av_faces_decode(const uint8_t *bytes, size_t size, float *signature);

/** When to look at a video of this length. Returns how many times, at most `capacity`. */
int av_faces_sample_times(int32_t durationMs, int32_t *out, int capacity);

float av_faces_sure(void);
float av_faces_ask(void);
int av_faces_pipeline_version(void);

#ifdef __cplusplus
}
#endif
