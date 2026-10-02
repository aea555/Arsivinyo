// The torrent engine on loopback (shared/watch/CONTRACT.md, phase 3): a seeder and a
// downloader in one process, no trackers, no DHT. Run with test/run.sh.

#include "../torrent.h"

#include <libtorrent/create_torrent.hpp>
#include <libtorrent/file_storage.hpp>
#include <libtorrent/torrent_info.hpp>

#include <arpa/inet.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <unistd.h>

#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <functional>
#include <string>
#include <thread>
#include <vector>

namespace lt = libtorrent;
using namespace std::chrono_literals;

static int failures = 0;
static void check(bool ok, char const* what) {
    std::printf("  %s  %s\n", ok ? "ok  " : "FAIL", what);
    if (!ok) ++failures;
}

static bool wait_for(std::function<bool()> done, int seconds) {
    auto const until = std::chrono::steady_clock::now() + std::chrono::seconds(seconds);
    while (std::chrono::steady_clock::now() < until) {
        if (done()) return true;
        std::this_thread::sleep_for(50ms);
    }
    return done();
}

static std::vector<char> pattern(size_t size, unsigned seed) {
    std::vector<char> out(size);
    unsigned x = seed;
    for (auto& c : out) { x = x * 1103515245u + 12345u; c = static_cast<char>(x >> 16); }
    return out;
}

static void write(std::string const& path, std::vector<char> const& bytes) {
    std::ofstream(path, std::ios::binary).write(bytes.data(), static_cast<std::streamsize>(bytes.size()));
}

static std::vector<char> read_all(std::string const& path) {
    std::ifstream in(path, std::ios::binary);
    return {std::istreambuf_iterator<char>(in), std::istreambuf_iterator<char>()};
}

// A field of the first torrent in at_status's JSON, as text.
static std::string field(at_session* s, std::string const& name) {
    char* json = at_status(s);
    std::string text = json ? json : "";
    at_free(json);
    auto const at = text.find("\"" + name + "\":");
    if (at == std::string::npos) return "";
    auto const start = at + name.size() + 3;
    return text.substr(start, text.find_first_of(",}", start) - start);
}

// GET a byte range from the loopback server; the body.
static std::string http_range(std::string const& url, long long first, long long last) {
    auto const port_at = url.find(':', 7) + 1;
    int const port = std::atoi(url.c_str() + port_at);
    std::string const path = url.substr(url.find('/', port_at));
    int fd = ::socket(AF_INET, SOCK_STREAM, 0);
    sockaddr_in a{};
    a.sin_family = AF_INET;
    a.sin_port = htons(static_cast<uint16_t>(port));
    a.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    if (::connect(fd, reinterpret_cast<sockaddr*>(&a), sizeof a) != 0) return "";
    std::string const request = "GET " + path + " HTTP/1.1\r\nHost: x\r\nRange: bytes=" + std::to_string(first) + "-" +
                                std::to_string(last) + "\r\n\r\n";
    ::send(fd, request.data(), request.size(), 0);
    std::string response;
    char buffer[65536];
    for (ssize_t n; (n = ::recv(fd, buffer, sizeof buffer, 0)) > 0;) response.append(buffer, static_cast<size_t>(n));
    ::close(fd);
    auto const body = response.find("\r\n\r\n");
    return body == std::string::npos ? "" : response.substr(body + 4);
}

// fixtures/sample.torrent: show/movie.mkv (8 MiB of pattern 7) and show/extra.nfo (512 KiB of
// pattern 9), 256 KiB pieces. The phone's test makes the same payload and seeds it.
static int write_fixture(std::string const& dir) {
    std::string const payload = dir + "/payload";
    std::system(("rm -rf '" + payload + "' && mkdir -p '" + payload + "/show'").c_str());
    write(payload + "/show/movie.mkv", pattern(8u << 20, 7));
    write(payload + "/show/extra.nfo", pattern(512u << 10, 9));
    lt::file_storage fs;
    lt::add_files(fs, payload + "/show");
    lt::create_torrent ct(fs, 256 * 1024, lt::create_torrent::v1_only);
    ct.set_creator("Arsivinyo fixture");
    lt::set_piece_hashes(ct, payload);
    auto entry = ct.generate();
    entry.dict().erase("creation date");
    std::vector<char> torrent;
    lt::bencode(std::back_inserter(torrent), entry);
    write(dir + "/sample.torrent", torrent);
    std::system(("rm -rf '" + payload + "'").c_str());
    return 0;
}

int main(int argc, char** argv) {
    if (argc > 2 && std::string(argv[1]) == "--fixture") return write_fixture(argv[2]);
    std::string const root = argc > 1 ? argv[1] : "/tmp/arsivinyo-torrent-test";
    std::system(("rm -rf '" + root + "'").c_str());
    for (auto dir : {"", "/seed", "/seed/show", "/get", "/get-state", "/seed-state", "/magnet", "/magnet-state"})
        ::mkdir((root + dir).c_str(), 0700);

    // The payload: a "movie" and a file nobody wants, in one torrent.
    size_t const movie_size = 24u << 20;
    auto const movie = pattern(movie_size, 7);
    write(root + "/seed/show/movie.mkv", movie);
    // Named to come after the movie, so a read-ahead spilling past its end would be seen.
    write(root + "/seed/show/trailer.nfo", pattern(1 << 20, 9));
    lt::file_storage fs;
    lt::add_files(fs, root + "/seed/show");
    lt::create_torrent ct(fs, 256 * 1024);
    lt::set_piece_hashes(ct, root + "/seed");
    std::vector<char> torrent;
    lt::bencode(std::back_inserter(torrent), ct.generate());
    lt::torrent_info info(torrent, lt::from_span);
    int const movie_index = info.files().file_path(lt::file_index_t(0)).find("movie") != std::string::npos ? 0 : 1;
    std::ostringstream hash_text;
    hash_text << info.info_hashes().get_best();

    char error[256] = {};
    std::string const seed_state = root + "/seed-state", get_state = root + "/get-state",
                      magnet_state = root + "/magnet-state";
    at_settings seed_settings{seed_state.c_str(), 0, 100.0, 1, 0, 2 << 20, 0};
    at_session* seeder = at_session_create(&seed_settings, error);
    check(seeder != nullptr, "a session starts");
    char id[65] = {};
    at_add_torrent(seeder, reinterpret_cast<uint8_t const*>(torrent.data()), torrent.size(), (root + "/seed").c_str(), 0, id);
    check(wait_for([&] { return field(seeder, "state") == "\"seeding\""; }, 20), "the seeder has the whole torrent");

    std::printf("streaming\n");
    at_settings get_settings{get_state.c_str(), 0, 0.0, 1, 0, 0, 0};
    at_session* getter = at_session_create(&get_settings, error);
    at_add_torrent(getter, reinterpret_cast<uint8_t const*>(torrent.data()), torrent.size(), (root + "/get").c_str(), 0, id);
    check(std::string(id) == hash_text.str(), "a torrent is named by its info hash");
    uint8_t priorities[2] = {0, 0};
    priorities[movie_index] = 4;
    at_set_priorities(getter, id, priorities, 2);
    at_connect_peer(getter, id, "127.0.0.1", at_session_port(seeder));

    char* base = at_server_start(getter);
    std::string const url = std::string(base) + "/" + id + "/" + std::to_string(movie_index) + "/movie.mkv";
    at_free(base);
    auto const started = std::chrono::steady_clock::now();
    std::string const tail = http_range(url, movie_size - 100000, movie_size - 1);
    double const seconds = std::chrono::duration<double>(std::chrono::steady_clock::now() - started).count();
    double const progress = std::atof(field(getter, "progress").c_str());
    check(tail.size() == 100000 && std::memcmp(tail.data(), movie.data() + movie_size - 100000, 100000) == 0,
          "the end of the file is served, byte for byte");
    std::printf("        (after %.1f s, with %.0f%% of the file here)\n", seconds, progress * 100);
    check(progress < 0.5, "before most of the file has arrived: the pieces under the read come first");
    std::string const head = http_range(url, 0, 999);
    check(head.size() == 1000 && std::memcmp(head.data(), movie.data(), 1000) == 0, "and so is its start");

    std::printf("resume\n");
    at_session_destroy(getter);
    getter = at_session_create(&get_settings, error);
    check(field(getter, "id") == "\"" + hash_text.str() + "\"", "after a restart the torrent is still there");
    at_connect_peer(getter, id, "127.0.0.1", at_session_port(seeder));
    check(wait_for([&] { return field(getter, "finished") == "true"; }, 60), "and it finishes");
    check(read_all(root + "/get/show/movie.mkv") == movie, "the wanted file is whole");
    char* progress_json = at_file_progress(getter, id);
    std::string const per_file = progress_json ? progress_json : "";
    at_free(progress_json);
    check(per_file.find("\"done\":" + std::to_string(movie_size) + ",\"size\":" + std::to_string(movie_size)) != std::string::npos,
          "each file says how much of it is here, so a finished one can be taken at once");
    auto const extra = read_all(root + "/get/show/trailer.nfo");
    check(extra.size() < (1u << 20), "the file that was not wanted was not downloaded");
    check(wait_for([&] { return field(getter, "paused") == "true"; }, 10), "with seeding off, a finished torrent stops");
    at_session_destroy(getter);

    std::printf("magnet\n");
    at_settings magnet_settings{magnet_state.c_str(), 0, 0.0, 1, 0, 0, 0};
    at_session* magnet = at_session_create(&magnet_settings, error);
    at_add_magnet(magnet, ("magnet:?xt=urn:btih:" + hash_text.str()).c_str(), (root + "/magnet").c_str(), 0, id);
    check(at_files(magnet, id) == nullptr, "a magnet has no files until its metadata comes");
    at_connect_peer(magnet, id, "127.0.0.1", at_session_port(seeder));
    char* files = nullptr;
    check(wait_for([&] { return (files = at_files(magnet, id)) != nullptr; }, 30), "the metadata comes from a peer");
    check(files && std::string(files).find("movie.mkv") != std::string::npos, "and lists the files");
    at_free(files);
    size_t size = 0;
    uint8_t* file = at_torrent_file(magnet, id, &size);
    check(file && size > 0, "and makes a .torrent that can be kept");
    at_free(file);
    at_session_destroy(magnet);

    std::printf("the cache\n");
    std::string const cache = root + "/cache", cache_state = root + "/cache-state";
    at_settings cache_settings{cache_state.c_str(), 0, 0.0, 1, 0, 0, 0};
    at_session* streamer = at_session_create(&cache_settings, error);
    std::string const link = "magnet:?xt=urn:btih:" + hash_text.str() + "&x.pe=127.0.0.1:" + std::to_string(at_session_port(seeder));
    int code = 0;
    char* stream = at_stream(streamer, link.c_str(), cache.c_str(), -1, nullptr, 30000, &code);
    std::string const streamed = stream ? stream : "";
    at_free(stream);
    check(code == AT_OK && streamed.find("movie.mkv") != std::string::npos, "a stream picks the largest video by itself");
    char* priorities_json = at_status(streamer);
    at_free(priorities_json);
    // Read its end first, as a player seeking there does: the read-ahead must not spill into
    // the file after it.
    char* stream_base = at_server_start(streamer);
    std::string const end_read = http_range(std::string(stream_base) + "/" + hash_text.str() + "/" + std::to_string(movie_index) + "/m",
                                            movie_size - 1000, movie_size - 1);
    at_free(stream_base);
    check(end_read.size() == 1000, "its end can be read first");
    check(wait_for([&] { return std::atof(field(streamer, "done").c_str()) >= double(movie_size); }, 60) &&
              std::atof(field(streamer, "wanted").c_str()) < double(movie_size + (256 << 10) + 1),
          "and it fetches only that file, the pieces it shares with the next one aside");
    check(at_cache_trim(streamer, cache.c_str(), 0, hash_text.str().c_str()) == 0, "the cache never drops what is playing");
    check(at_cache_trim(streamer, cache.c_str(), 1 << 20, nullptr) == 1, "but drops the least recently played to fit");
    check(field(streamer, "id").empty(), "so it is gone");
    check(wait_for([&] { return read_all(cache + "/show/movie.mkv").empty(); }, 10), "with its files");
    at_session_destroy(streamer);
    at_session_destroy(seeder);

    std::printf(failures ? "FAILURES\n" : "the torrent engine holds\n");
    return failures ? 1 : 0;
}
