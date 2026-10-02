// The torrent engine (shared/watch/CONTRACT.md, "The torrent engine"): libtorrent behind a
// flat C API, so the phone (JNI) and the Mac (Swift) call the same few functions and get the
// same behaviour. Torrents are named by their info hash in hex.
//
// Nothing here logs a torrent's name, files or trackers: what someone downloads is private.

#ifndef ARSIVINYO_TORRENT_H
#define ARSIVINYO_TORRENT_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct at_session at_session;

typedef struct at_settings {
    /// Where resume data and fetched metadata are kept, so torrents survive a restart.
    const char* state_dir;
    /// Port to listen on; 0 for one chosen by the system.
    int listen_port;
    /// Stop seeding a torrent once it has uploaded this many times what it downloaded.
    /// 0 means never seed: a finished torrent is paused at once.
    double seed_ratio;
    /// Whether to upload at all now (the phone says no on mobile data).
    int upload_allowed;
    /// DHT, local peer discovery, UPnP and NAT-PMP. Off only for tests on loopback.
    int discovery;
    /// Bytes per second; 0 for no limit.
    int upload_limit;
    int download_limit;
} at_settings;

/// A session; resumes every torrent whose state is in `state_dir`. NULL on failure, with a
/// reason in `error` (at least 256 bytes).
at_session* at_session_create(const at_settings* settings, char* error);
/// Saves every torrent's resume data, then stops.
void at_session_destroy(at_session* session);
void at_session_apply(at_session* session, const at_settings* settings);
/// The port it listens on.
int at_session_port(at_session* session);

/// Adds a magnet link or the bytes of a .torrent file, to download into `save_path`.
/// `out_id` (65 bytes) receives the info hash. 0 on success; a negative code otherwise.
int at_add_magnet(at_session* session, const char* magnet, const char* save_path, int paused, char* out_id);
int at_add_torrent(at_session* session, const uint8_t* data, size_t size, const char* save_path, int paused,
                   char* out_id);
/// Removes a torrent, and its downloaded files with `delete_files`.
int at_remove(at_session* session, const char* id, int delete_files);
int at_pause(at_session* session, const char* id);
/// Holds a torrent (on) or lets it go (off), for while its files are being chosen. Held, it
/// wants none of its files, from the moment its metadata is here (a magnet's file list still
/// comes; before it, there is nothing a torrent could fetch anyway), and keeps its peers, so it
/// starts at once once the files are chosen. Letting go wants nothing by itself: the files
/// chosen are set with at_set_priorities.
int at_hold(at_session* session, const char* id, int on);
int at_resume(at_session* session, const char* id);
/// Connects to a peer by address, as trackers and the DHT otherwise would.
int at_connect_peer(at_session* session, const char* id, const char* ip, int port);

/// The files of a torrent as JSON, `[{"index":0,"path":"a/b.mkv","size":123}]`, once its
/// metadata is known; NULL before. Free with at_free.
char* at_files(at_session* session, const char* id);
/// Each file's progress as JSON, `[{"index":0,"done":123,"size":456,"priority":4}]`, so
/// a file can be taken (filed, or encrypted into the vault) as soon as it is complete.
/// NULL before metadata. Free with at_free.
char* at_file_progress(at_session* session, const char* id);
/// The files of a held torrent are chosen: 0 (skip) or 1..7 per file, in file order. It is
/// added again, fresh, with its metadata, its trackers and these priorities, and starts at
/// once: the torrent that waited lost its peers while it wanted nothing (a seed hangs up on a
/// peer that wants nothing from it), and libtorrent waits before reconnecting to a peer it
/// lost, which a fresh torrent does not. Nothing is lost by it: a held torrent fetched nothing.
int at_choose(at_session* session, const char* id, const uint8_t* priorities, int count);
/// 0 (skip) or 1..7, one per file, in file order.
int at_set_priorities(at_session* session, const char* id, const uint8_t* priorities, int count);
/// Every torrent's state as JSON; free with at_free.
char* at_status(at_session* session);
/// The bytes of the .torrent file once metadata is known, for keeping or handing over.
/// Free with at_free; `size` receives the length.
uint8_t* at_torrent_file(at_session* session, const char* id, size_t* size);

/// Where a file of a torrent lives on disk; free with at_free.
char* at_file_path(at_session* session, const char* id, int file);
int64_t at_file_size(at_session* session, const char* id, int file);
/// Reads `length` bytes of a file at `offset`, first asking for the pieces under them, ahead
/// of everything else, and waiting up to `timeout_ms` for them to arrive. Returns the bytes
/// read, 0 at the end of the file, AT_TIMEOUT if they did not come, or another negative code.
int64_t at_read(at_session* session, const char* id, int file, int64_t offset, void* buffer, int64_t length,
                int timeout_ms);

/// Prepares a file of a torrent for streaming from the cache: adds the magnet into
/// `cache_dir` if the torrent is not already here, waits up to `timeout_ms` for its metadata,
/// and picks the file: `file` when it is 0 or more, else the one named `name_hint` (an
/// add-on's filename), else the largest video. A torrent being streamed wants only that file;
/// one being downloaded keeps its other files. Its last use is recorded for the cache.
/// Returns JSON {"id","file","size","path"}, or NULL with `code` set. Free with at_free.
char* at_stream(at_session* session, const char* magnet, const char* cache_dir, int file, const char* name_hint,
                int timeout_ms, int* code);
/// Removes torrents streamed into `cache_dir`, least recently used first, with their files,
/// until what the cache holds is at most `limit_bytes`. Never `keep_id` (what is playing).
/// Returns how many it removed.
int at_cache_trim(at_session* session, const char* cache_dir, int64_t limit_bytes, const char* keep_id);

/// Starts the loopback server streams are read through, on 127.0.0.1 with a random port and
/// a random path token. Returns the base URL ("http://127.0.0.1:PORT/TOKEN"); a file is
/// BASE/ID/INDEX. Free with at_free. Started once; later calls give the same URL.
char* at_server_start(at_session* session);

void at_free(void* pointer);

enum {
    AT_OK = 0,
    AT_BAD_INPUT = -1,
    AT_NO_TORRENT = -2,
    AT_NO_METADATA = -3,
    AT_TIMEOUT = -4,
    AT_IO = -5,
    AT_FAILED = -6,
};

#ifdef __cplusplus
}
#endif

#endif
