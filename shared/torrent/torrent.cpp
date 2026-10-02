// The torrent engine; see torrent.h. One libtorrent session, a thread that handles its alerts
// (resume data, metadata, finished pieces, the seeding rule), and a loopback HTTP server
// that streams files while they download.

#include "torrent.h"

#include <libtorrent/add_torrent_params.hpp>
#include <libtorrent/alert_types.hpp>
#include <libtorrent/create_torrent.hpp>
#include <libtorrent/magnet_uri.hpp>
#include <libtorrent/read_resume_data.hpp>
#include <libtorrent/session.hpp>
#include <libtorrent/torrent_info.hpp>
#include <libtorrent/write_resume_data.hpp>

#include <arpa/inet.h>
#include <dirent.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <poll.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <unistd.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <map>
#include <memory>
#include <mutex>
#include <random>
#include <set>
#include <sstream>
#include <string>
#include <thread>
#include <vector>

namespace lt = libtorrent;
using namespace std::chrono_literals;

namespace {

std::string hex(lt::sha1_hash const& hash) {
    std::ostringstream out;
    out << hash;
    return out.str();
}

std::string json_string(std::string const& text) {
    std::string out = "\"";
    for (unsigned char c : text) {
        switch (c) {
            case '"': out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default:
                if (c < 0x20) {
                    char escaped[8];
                    std::snprintf(escaped, sizeof escaped, "\\u%04x", c);
                    out += escaped;
                } else {
                    out += static_cast<char>(c);
                }
        }
    }
    return out + "\"";
}

char* copy(std::string const& text) {
    char* out = static_cast<char*>(std::malloc(text.size() + 1));
    if (out) std::memcpy(out, text.c_str(), text.size() + 1);
    return out;
}

bool write_file(std::string const& path, std::vector<char> const& bytes) {
    // Beside the file and renamed, so an interrupted write cannot leave half of it.
    std::string const temp = path + ".tmp";
    {
        std::ofstream out(temp, std::ios::binary | std::ios::trunc);
        if (!out) return false;
        out.write(bytes.data(), static_cast<std::streamsize>(bytes.size()));
        if (!out) return false;
    }
    return std::rename(temp.c_str(), path.c_str()) == 0;
}

std::vector<char> read_file(std::string const& path) {
    std::ifstream in(path, std::ios::binary);
    return {std::istreambuf_iterator<char>(in), std::istreambuf_iterator<char>()};
}

char const* state_name(lt::torrent_status::state_t state) {
    switch (state) {
        case lt::torrent_status::checking_files: return "checking";
        case lt::torrent_status::downloading_metadata: return "metadata";
        case lt::torrent_status::downloading: return "downloading";
        case lt::torrent_status::finished: return "finished";
        case lt::torrent_status::seeding: return "seeding";
        case lt::torrent_status::checking_resume_data: return "checking";
        default: return "unknown";
    }
}

std::string content_type(std::string const& path) {
    auto const dot = path.rfind('.');
    std::string ext = dot == std::string::npos ? "" : path.substr(dot + 1);
    std::transform(ext.begin(), ext.end(), ext.begin(), [](unsigned char c) { return std::tolower(c); });
    if (ext == "mkv") return "video/x-matroska";
    if (ext == "mp4" || ext == "m4v") return "video/mp4";
    if (ext == "webm") return "video/webm";
    if (ext == "avi") return "video/x-msvideo";
    if (ext == "mov") return "video/quicktime";
    if (ext == "ts") return "video/mp2t";
    if (ext == "mp3") return "audio/mpeg";
    return "application/octet-stream";
}

}  // namespace

// The session and everything that must outlive a request being served: server threads hold
// it by shared_ptr, so destroying the session waits for none of them and frees nothing they use.
struct Core {
    std::unique_ptr<lt::session> session;
    std::string state_dir;
    std::mutex mutex;
    std::condition_variable progress;  // a piece arrived, or metadata did
    std::map<std::string, lt::torrent_handle> torrents;
    std::atomic<bool> running{true};
    double seed_ratio = 1.0;
    bool upload_allowed = true;
    // Torrents held until their files are chosen (at_hold): every file is skipped as soon as
    // the metadata is here, by the alert thread, so nothing is fetched in between.
    std::set<std::string> held;

    void hold_now(lt::torrent_handle const& h) {
        auto ti = h.torrent_file();
        if (!ti) return;
        h.prioritize_files(std::vector<lt::download_priority_t>(static_cast<size_t>(ti->num_files()), lt::dont_download));
    }

    // When each torrent in a cache was last streamed, in seconds; kept in cache.json.
    std::map<std::string, int64_t> last_used;

    std::string cache_index_path() const { return state_dir + "/cache.json"; }

    void load_cache_index() {
        // {"<id>":<seconds>,...}: written by save_cache_index, read leniently.
        auto const bytes = read_file(cache_index_path());
        std::string const text(bytes.begin(), bytes.end());
        size_t at = 0;
        while ((at = text.find('"', at)) != std::string::npos) {
            auto const end = text.find('"', at + 1);
            if (end == std::string::npos) break;
            std::string const id = text.substr(at + 1, end - at - 1);
            auto const colon = text.find(':', end);
            if (colon == std::string::npos) break;
            last_used[id] = std::strtoll(text.c_str() + colon + 1, nullptr, 10);
            at = text.find_first_of(",}", colon);
            if (at == std::string::npos) break;
        }
    }

    void save_cache_index() {
        std::string out = "{";
        for (auto const& [id, when] : last_used) out += (out.size() > 1 ? "," : "") + json_string(id) + ":" + std::to_string(when);
        out += "}";
        write_file(cache_index_path(), std::vector<char>(out.begin(), out.end()));
    }

    lt::torrent_handle find(std::string const& id) {
        std::lock_guard<std::mutex> lock(mutex);
        auto it = torrents.find(id);
        return it == torrents.end() ? lt::torrent_handle() : it->second;
    }

    std::string resume_path(std::string const& id) const { return state_dir + "/" + id + ".resume"; }

    // Pauses what has finished and seeded enough, or must not seed at all.
    void apply_seeding_rule() {
        std::vector<lt::torrent_handle> handles;
        {
            std::lock_guard<std::mutex> lock(mutex);
            for (auto const& entry : torrents) handles.push_back(entry.second);
        }
        for (auto const& h : handles) {
            if (!h.is_valid()) continue;
            auto const st = h.status();
            // Finished, not only seeding: a torrent with files left out is finished, never "seeding".
            if (!st.is_finished || (st.flags & lt::torrent_flags::paused)) continue;
            double const downloaded = std::max<double>(1.0, double(std::max(st.all_time_download, st.total_wanted)));
            double const ratio = double(st.all_time_upload) / downloaded;
            if (!upload_allowed || seed_ratio <= 0 || ratio >= seed_ratio) {
                h.unset_flags(lt::torrent_flags::auto_managed);
                h.pause();
                h.save_resume_data(lt::torrent_handle::save_info_dict);
            }
        }
    }

    void handle_alerts() {
        auto last_rule = std::chrono::steady_clock::now();
        auto last_save = last_rule;
        while (running) {
            session->wait_for_alert(500ms);
            std::vector<lt::alert*> alerts;
            session->pop_alerts(&alerts);
            bool woke = false;
            for (lt::alert* a : alerts) {
                if (auto* r = lt::alert_cast<lt::save_resume_data_alert>(a)) {
                    write_file(resume_path(hex(r->params.info_hashes.get_best())), lt::write_resume_data_buf(r->params));
                } else if (auto* m = lt::alert_cast<lt::metadata_received_alert>(a)) {
                    bool hold;
                    {
                        std::lock_guard<std::mutex> lock(mutex);
                        hold = held.count(hex(m->handle.info_hashes().get_best())) > 0;
                    }
                    if (hold) hold_now(m->handle);
                    // Kept with the resume data, so a restart does not fetch it again.
                    m->handle.save_resume_data(lt::torrent_handle::save_info_dict);
                    woke = true;
                } else if (lt::alert_cast<lt::piece_finished_alert>(a)) {
                    woke = true;
                } else if (auto* f = lt::alert_cast<lt::torrent_finished_alert>(a)) {
                    f->handle.save_resume_data(lt::torrent_handle::save_info_dict);
                    woke = true;
                } else if (auto* p = lt::alert_cast<lt::torrent_paused_alert>(a)) {
                    p->handle.save_resume_data(lt::torrent_handle::save_info_dict);
                }
            }
            if (woke) progress.notify_all();
            auto const now = std::chrono::steady_clock::now();
            if (now - last_rule > 2s) {
                apply_seeding_rule();
                last_rule = now;
            }
            // Every half minute, what changed is written down: the phone may kill the app at
            // any moment, and a download then carries on from here, not from the start.
            if (now - last_save > 30s) {
                std::vector<lt::torrent_handle> handles;
                {
                    std::lock_guard<std::mutex> lock(mutex);
                    for (auto const& entry : torrents) handles.push_back(entry.second);
                }
                for (auto const& h : handles)
                    if (h.is_valid() && h.need_save_resume_data()) h.save_resume_data(lt::torrent_handle::save_info_dict);
                last_save = now;
            }
        }
    }

    // Asks for the pieces under [offset, offset + length) and a window beyond them, ahead of
    // everything else, then waits until those under the range are here.
    int64_t read(std::string const& id, int file, int64_t offset, char* buffer, int64_t length, int timeout_ms) {
        auto const deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(timeout_ms);
        lt::torrent_handle h = find(id);
        if (!h.is_valid()) return AT_NO_TORRENT;
        std::shared_ptr<const lt::torrent_info> ti;
        {
            std::unique_lock<std::mutex> lock(mutex);
            if (!progress.wait_until(lock, deadline, [&] { return (ti = h.torrent_file()) != nullptr || !running; }))
                return AT_TIMEOUT;
        }
        if (!running) return AT_FAILED;
        auto const& files = ti->files();
        if (file < 0 || file >= files.num_files()) return AT_BAD_INPUT;
        lt::file_index_t const index(file);
        int64_t const size = files.file_size(index);
        if (offset >= size) return 0;
        length = std::min(length, size - offset);
        if (length <= 0) return 0;

        int const piece_length = ti->piece_length();
        int const first = static_cast<int>((files.file_offset(index) + offset) / piece_length);
        int const last = static_cast<int>((files.file_offset(index) + offset + length - 1) / piece_length);
        // The window: the read itself, then about 16 MB past it, at least four pieces, never
        // past the file's own last piece: a deadline downloads a piece whatever its file's
        // priority, so going further would fetch the files after it that nobody wanted.
        int const window = std::max(4, (16 << 20) / piece_length);
        int const file_last = static_cast<int>((files.file_offset(index) + size - 1) / piece_length);
        int const end = std::min(file_last, last + window);
        for (int p = first; p <= end; ++p) {
            lt::piece_index_t const piece(p);
            if (!h.have_piece(piece)) h.set_piece_deadline(piece, (p - first) * 150);
        }
        {
            std::unique_lock<std::mutex> lock(mutex);
            bool const ready = progress.wait_until(lock, deadline, [&] {
                if (!running) return true;
                for (int p = first; p <= last; ++p)
                    if (!h.have_piece(lt::piece_index_t(p))) return false;
                return true;
            });
            if (!ready) return AT_TIMEOUT;
        }
        if (!running) return AT_FAILED;

        std::string const path = h.status(lt::torrent_handle::query_save_path).save_path + "/" + files.file_path(index);
        int const fd = ::open(path.c_str(), O_RDONLY);
        if (fd < 0) return AT_IO;
        int64_t done = 0;
        while (done < length) {
            ssize_t const n = ::pread(fd, buffer + done, static_cast<size_t>(length - done), offset + done);
            if (n <= 0) break;
            done += n;
        }
        ::close(fd);
        return done > 0 ? done : AT_IO;
    }
};

// The loopback server: one thread accepting, one per request. A request is
// GET /TOKEN/ID/INDEX with an optional byte range; anything else is a 404.
struct Server {
    std::shared_ptr<Core> core;
    int listener = -1;
    int port = 0;
    std::string token;
    std::thread acceptor;
    std::mutex mutex;
    std::condition_variable idle;
    std::set<int> clients;

    static void send_all(int fd, char const* data, size_t size, bool& ok) {
        while (ok && size > 0) {
#ifdef MSG_NOSIGNAL
            ssize_t const n = ::send(fd, data, size, MSG_NOSIGNAL);
#else
            ssize_t const n = ::send(fd, data, size, 0);
#endif
            if (n <= 0) {
                ok = false;
                return;
            }
            data += n;
            size -= static_cast<size_t>(n);
        }
    }

    // Whether the player has hung up, without waiting.
    static bool gone(int fd) {
        pollfd p{fd, POLLIN, 0};
        if (::poll(&p, 1, 0) <= 0) return false;
        if (p.revents & (POLLHUP | POLLERR)) return true;
        char c;
        return ::recv(fd, &c, 1, MSG_PEEK | MSG_DONTWAIT) == 0;
    }

    void serve(int fd) {
        std::string request;
        char chunk[2048];
        while (request.find("\r\n\r\n") == std::string::npos && request.size() < 16384) {
            ssize_t const n = ::recv(fd, chunk, sizeof chunk, 0);
            if (n <= 0) return;
            request.append(chunk, static_cast<size_t>(n));
        }
        bool ok = true;
        auto reply = [&](std::string const& status) {
            std::string const head = "HTTP/1.1 " + status + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
            send_all(fd, head.data(), head.size(), ok);
        };
        std::istringstream lines(request);
        std::string method, target;
        lines >> method >> target;
        bool const head_only = method == "HEAD";
        if (method != "GET" && !head_only) return reply("405 Method Not Allowed");

        // /TOKEN/ID/INDEX, anything after it ignored (a file name, for the player's sake).
        std::vector<std::string> parts;
        std::stringstream path(target);
        for (std::string part; std::getline(path, part, '/');)
            if (!part.empty()) parts.push_back(part);
        if (parts.size() < 3 || parts[0] != token) return reply("404 Not Found");
        std::string const id = parts[1];
        int const file = std::atoi(parts[2].c_str());
        lt::torrent_handle h = core->find(id);
        if (!h.is_valid()) return reply("404 Not Found");

        // Metadata first: a magnet may still be fetching it.
        std::shared_ptr<const lt::torrent_info> ti;
        for (int waited = 0; !(ti = h.torrent_file()) && waited < 120 && core->running && !gone(fd); ++waited) {
            std::unique_lock<std::mutex> lock(core->mutex);
            core->progress.wait_for(lock, 500ms);
        }
        if (!ti || file < 0 || file >= ti->files().num_files()) return reply("404 Not Found");
        int64_t const size = ti->files().file_size(lt::file_index_t(file));

        int64_t start = 0, end = size - 1;
        bool ranged = false;
        auto const range = request.find("\nRange: bytes=") != std::string::npos ? request.find("\nRange: bytes=")
                                                                                 : request.find("\nrange: bytes=");
        if (range != std::string::npos) {
            char const* spec = request.c_str() + range + std::strlen("\nRange: bytes=");
            char* rest = nullptr;
            long long const a = std::strtoll(spec, &rest, 10);
            if (rest != spec) {
                start = a;
                if (*rest == '-' && rest[1] >= '0' && rest[1] <= '9') end = std::strtoll(rest + 1, nullptr, 10);
            } else if (*spec == '-') {
                start = std::max<int64_t>(0, size - std::strtoll(spec + 1, nullptr, 10));
            }
            ranged = true;
        }
        end = std::min(end, size - 1);
        if (start > end || start >= size) {
            std::string const head = "HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */" +
                                     std::to_string(size) + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
            return send_all(fd, head.data(), head.size(), ok);
        }

        std::string head = ranged ? "HTTP/1.1 206 Partial Content\r\n" : "HTTP/1.1 200 OK\r\n";
        head += "Content-Type: " + content_type(ti->files().file_path(lt::file_index_t(file))) + "\r\n";
        head += "Accept-Ranges: bytes\r\nContent-Length: " + std::to_string(end - start + 1) + "\r\n";
        if (ranged)
            head += "Content-Range: bytes " + std::to_string(start) + "-" + std::to_string(end) + "/" +
                    std::to_string(size) + "\r\n";
        head += "Connection: close\r\n\r\n";
        send_all(fd, head.data(), head.size(), ok);
        if (head_only) return;

        std::vector<char> buffer(512 * 1024);
        int64_t position = start;
        while (ok && position <= end && core->running) {
            int64_t const want = std::min<int64_t>(static_cast<int64_t>(buffer.size()), end - position + 1);
            // Waits a few seconds at a time, so a player that gave up is noticed.
            int64_t const n = core->read(id, file, position, buffer.data(), want, 3000);
            if (n == AT_TIMEOUT) {
                if (gone(fd)) return;
                continue;
            }
            if (n <= 0) return;
            send_all(fd, buffer.data(), static_cast<size_t>(n), ok);
            position += n;
        }
    }

    bool start() {
        listener = ::socket(AF_INET, SOCK_STREAM, 0);
        if (listener < 0) return false;
        int yes = 1;
        ::setsockopt(listener, SOL_SOCKET, SO_REUSEADDR, &yes, sizeof yes);
        sockaddr_in address{};
        address.sin_family = AF_INET;
        address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
        address.sin_port = 0;
        if (::bind(listener, reinterpret_cast<sockaddr*>(&address), sizeof address) != 0 || ::listen(listener, 16) != 0) {
            ::close(listener);
            listener = -1;
            return false;
        }
        socklen_t length = sizeof address;
        ::getsockname(listener, reinterpret_cast<sockaddr*>(&address), &length);
        port = ntohs(address.sin_port);

        std::random_device random;
        static char const digits[] = "0123456789abcdef";
        for (int i = 0; i < 32; ++i) token += digits[random() % 16];

        acceptor = std::thread([this] {
            while (core->running) {
                int const fd = ::accept(listener, nullptr, nullptr);
                if (fd < 0) {
                    if (!core->running) break;
                    continue;
                }
#ifdef SO_NOSIGPIPE
                int one = 1;
                ::setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, &one, sizeof one);
#endif
                {
                    std::lock_guard<std::mutex> lock(mutex);
                    clients.insert(fd);
                }
                std::thread([this, fd] {
                    serve(fd);
                    ::shutdown(fd, SHUT_RDWR);
                    ::close(fd);
                    std::lock_guard<std::mutex> lock(mutex);
                    clients.erase(fd);
                    idle.notify_all();
                }).detach();
            }
        });
        return true;
    }

    void stop() {
        if (listener >= 0) {
            ::shutdown(listener, SHUT_RDWR);
            ::close(listener);
            listener = -1;
        }
        if (acceptor.joinable()) acceptor.join();
        std::unique_lock<std::mutex> lock(mutex);
        for (int fd : clients) ::shutdown(fd, SHUT_RDWR);
        idle.wait_for(lock, 5s, [&] { return clients.empty(); });
    }
};

struct at_session {
    std::shared_ptr<Core> core;
    std::thread alerts;
    std::unique_ptr<Server> server;
};

namespace {

lt::settings_pack pack(at_settings const& s) {
    lt::settings_pack p;
    std::string const port = std::to_string(std::max(0, s.listen_port));
    p.set_str(lt::settings_pack::listen_interfaces,
              s.discovery ? "0.0.0.0:" + port + ",[::]:" + port : "127.0.0.1:" + port);
    p.set_bool(lt::settings_pack::enable_dht, s.discovery != 0);
    p.set_bool(lt::settings_pack::enable_lsd, s.discovery != 0);
    p.set_bool(lt::settings_pack::enable_upnp, s.discovery != 0);
    p.set_bool(lt::settings_pack::enable_natpmp, s.discovery != 0);
    p.set_int(lt::settings_pack::alert_mask, lt::alert_category::status | lt::alert_category::storage |
                                                  lt::alert_category::error | lt::alert_category::piece_progress);
    p.set_str(lt::settings_pack::user_agent, "Arsivinyo libtorrent/" LIBTORRENT_VERSION);
    // Streaming asks for pieces by deadline; let it have peers' attention quickly.
    p.set_int(lt::settings_pack::request_timeout, 10);
    p.set_bool(lt::settings_pack::allow_multiple_connections_per_ip, s.discovery == 0);
    // A torrent waiting for its files to be chosen wants nothing, which libtorrent counts as
    // finished, and it would close its connections to seeds as redundant: the peers that just
    // sent the file list, and that should start the download the moment the files are chosen.
    p.set_bool(lt::settings_pack::close_redundant_connections, false);
    p.set_int(lt::settings_pack::upload_rate_limit, std::max(0, s.upload_limit));
    p.set_int(lt::settings_pack::download_rate_limit, std::max(0, s.download_limit));
    return p;
}

// libtorrent leaves peers on the local network out of the rate limits; a limit the user sets
// is meant for every peer, so the local class gets the same one.
void limit_local_peers(lt::session& session, at_settings const& s) {
    lt::peer_class_info info = session.get_peer_class(lt::session::local_peer_class_id);
    info.upload_limit = std::max(0, s.upload_limit);
    info.download_limit = std::max(0, s.download_limit);
    session.set_peer_class(lt::session::local_peer_class_id, info);
}

int add(at_session* session, lt::add_torrent_params atp, char const* save_path, int paused, char* out_id) {
    if (!save_path || !*save_path) return AT_BAD_INPUT;
    atp.save_path = save_path;
    atp.flags &= ~lt::torrent_flags::auto_managed;
    if (paused) atp.flags |= lt::torrent_flags::paused;
    else atp.flags &= ~lt::torrent_flags::paused;
    std::string const id = hex(atp.ti ? atp.ti->info_hashes().get_best() : atp.info_hashes.get_best());
    lt::error_code ec;
    lt::torrent_handle h = session->core->session->add_torrent(std::move(atp), ec);
    if (ec || !h.is_valid()) return AT_FAILED;
    {
        std::lock_guard<std::mutex> lock(session->core->mutex);
        session->core->torrents[id] = h;
    }
    h.save_resume_data(lt::torrent_handle::save_info_dict);
    if (out_id) std::snprintf(out_id, 65, "%s", id.c_str());
    return AT_OK;
}

}  // namespace

extern "C" {

at_session* at_session_create(const at_settings* settings, char* error) {
    try {
        if (!settings || !settings->state_dir) throw std::runtime_error("no state directory");
        auto core = std::make_shared<Core>();
        core->state_dir = settings->state_dir;
        core->seed_ratio = settings->seed_ratio;
        core->upload_allowed = settings->upload_allowed != 0;
        ::mkdir(core->state_dir.c_str(), 0700);
        core->load_cache_index();
        lt::session_params params(pack(*settings));
        core->session = std::make_unique<lt::session>(std::move(params));
        limit_local_peers(*core->session, *settings);

        auto* session = new at_session{core, {}, nullptr};
        // Everything that was here before: resume data, with metadata where it was known.
        if (DIR* dir = ::opendir(core->state_dir.c_str())) {
            while (dirent* entry = ::readdir(dir)) {
                std::string const name = entry->d_name;
                if (name.size() < 8 || name.compare(name.size() - 7, 7, ".resume") != 0) continue;
                auto const bytes = read_file(core->state_dir + "/" + name);
                lt::error_code ec;
                auto atp = lt::read_resume_data(bytes, ec);
                if (ec) continue;
                std::string const save = atp.save_path;
                bool const paused = static_cast<bool>(atp.flags & lt::torrent_flags::paused);
                add(session, std::move(atp), save.c_str(), paused, nullptr);
            }
            ::closedir(dir);
        }
        session->alerts = std::thread([core] { core->handle_alerts(); });
        return session;
    } catch (std::exception const& e) {
        if (error) std::snprintf(error, 256, "%s", e.what());
        return nullptr;
    }
}

void at_session_destroy(at_session* session) {
    if (!session) return;
    auto core = session->core;
    // Resume data for everything, waited for, so a restart carries on where this stopped.
    std::vector<lt::torrent_handle> handles;
    {
        std::lock_guard<std::mutex> lock(core->mutex);
        for (auto const& entry : core->torrents) handles.push_back(entry.second);
    }
    core->running = false;
    core->progress.notify_all();
    if (session->alerts.joinable()) session->alerts.join();
    if (session->server) session->server->stop();
    core->session->pause();
    int outstanding = 0;
    for (auto const& h : handles) {
        if (!h.is_valid()) continue;
        h.save_resume_data(lt::torrent_handle::save_info_dict);
        ++outstanding;
    }
    auto const until = std::chrono::steady_clock::now() + 10s;
    while (outstanding > 0 && std::chrono::steady_clock::now() < until) {
        core->session->wait_for_alert(500ms);
        std::vector<lt::alert*> alerts;
        core->session->pop_alerts(&alerts);
        for (lt::alert* a : alerts) {
            if (auto* r = lt::alert_cast<lt::save_resume_data_alert>(a)) {
                write_file(core->resume_path(hex(r->params.info_hashes.get_best())), lt::write_resume_data_buf(r->params));
                --outstanding;
            } else if (lt::alert_cast<lt::save_resume_data_failed_alert>(a)) {
                --outstanding;
            }
        }
    }
    delete session;
}

void at_session_apply(at_session* session, const at_settings* settings) {
    if (!session || !settings) return;
    session->core->seed_ratio = settings->seed_ratio;
    session->core->upload_allowed = settings->upload_allowed != 0;
    session->core->session->apply_settings(pack(*settings));
    limit_local_peers(*session->core->session, *settings);
}

int at_session_port(at_session* session) { return session ? session->core->session->listen_port() : 0; }

int at_add_magnet(at_session* session, const char* magnet, const char* save_path, int paused, char* out_id) {
    if (!session || !magnet) return AT_BAD_INPUT;
    lt::error_code ec;
    auto atp = lt::parse_magnet_uri(magnet, ec);
    if (ec) return AT_BAD_INPUT;
    return add(session, std::move(atp), save_path, paused, out_id);
}

int at_add_torrent(at_session* session, const uint8_t* data, size_t size, const char* save_path, int paused,
                   char* out_id) {
    if (!session || !data || size == 0) return AT_BAD_INPUT;
    lt::error_code ec;
    auto ti = std::make_shared<lt::torrent_info>(lt::span<char const>(reinterpret_cast<char const*>(data),
                                                                      static_cast<std::ptrdiff_t>(size)),
                                                 ec, lt::from_span);
    if (ec) return AT_BAD_INPUT;
    lt::add_torrent_params atp;
    atp.ti = ti;
    return add(session, std::move(atp), save_path, paused, out_id);
}

int at_remove(at_session* session, const char* id, int delete_files) {
    if (!session || !id) return AT_BAD_INPUT;
    lt::torrent_handle h = session->core->find(id);
    if (!h.is_valid()) return AT_NO_TORRENT;
    {
        std::lock_guard<std::mutex> lock(session->core->mutex);
        session->core->torrents.erase(id);
    }
    session->core->session->remove_torrent(h, delete_files ? lt::session::delete_files : lt::remove_flags_t{});
    std::remove(session->core->resume_path(id).c_str());
    return AT_OK;
}

int at_pause(at_session* session, const char* id) {
    lt::torrent_handle h = session && id ? session->core->find(id) : lt::torrent_handle();
    if (!h.is_valid()) return AT_NO_TORRENT;
    h.unset_flags(lt::torrent_flags::auto_managed);
    h.pause();
    return AT_OK;
}

int at_hold(at_session* session, const char* id, int on) {
    lt::torrent_handle h = session && id ? session->core->find(id) : lt::torrent_handle();
    if (!h.is_valid()) return AT_NO_TORRENT;
    {
        std::lock_guard<std::mutex> lock(session->core->mutex);
        if (on) session->core->held.insert(id);
        else session->core->held.erase(id);
    }
    // Already here (a .torrent file, or a magnet whose list came): skipped now.
    if (on) session->core->hold_now(h);
    return AT_OK;
}

int at_resume(at_session* session, const char* id) {
    lt::torrent_handle h = session && id ? session->core->find(id) : lt::torrent_handle();
    if (!h.is_valid()) return AT_NO_TORRENT;
    h.resume();
    // Peers now, not at the next scheduled announce, which can be many minutes away.
    h.force_reannounce();
    h.force_dht_announce();
    return AT_OK;
}

int at_connect_peer(at_session* session, const char* id, const char* ip, int port) {
    lt::torrent_handle h = session && id ? session->core->find(id) : lt::torrent_handle();
    if (!h.is_valid() || !ip) return AT_NO_TORRENT;
    lt::error_code ec;
    auto const address = lt::make_address(ip, ec);
    if (ec) return AT_BAD_INPUT;
    h.connect_peer(lt::tcp::endpoint(address, static_cast<std::uint16_t>(port)));
    return AT_OK;
}

int at_choose(at_session* session, const char* id, const uint8_t* priorities, int count) {
    if (!session || !id || !priorities || count < 0) return AT_BAD_INPUT;
    auto core = session->core;
    lt::torrent_handle h = core->find(id);
    if (!h.is_valid()) return AT_NO_TORRENT;
    auto ti = h.torrent_file();
    if (!ti) return AT_NO_METADATA;

    lt::add_torrent_params atp;
    atp.ti = std::make_shared<lt::torrent_info>(*ti);
    for (auto const& tracker : h.trackers()) atp.trackers.push_back(tracker.url);
    atp.save_path = h.status(lt::torrent_handle::query_save_path).save_path;
    for (int i = 0; i < ti->num_files(); ++i)
        atp.file_priorities.emplace_back(i < count ? std::min<std::uint8_t>(priorities[i], 7) : 0);
    atp.flags &= ~(lt::torrent_flags::auto_managed | lt::torrent_flags::paused);

    {
        std::lock_guard<std::mutex> lock(core->mutex);
        core->held.erase(id);
        core->torrents.erase(id);
    }
    core->session->remove_torrent(h);
    // The removal is asynchronous; adding the same torrent before it is done is refused.
    lt::error_code ec;
    lt::torrent_handle fresh;
    for (int attempt = 0; attempt < 50 && !fresh.is_valid(); ++attempt) {
        fresh = core->session->add_torrent(atp, ec);
        if (!fresh.is_valid()) std::this_thread::sleep_for(100ms);
    }
    if (!fresh.is_valid()) return AT_FAILED;
    {
        std::lock_guard<std::mutex> lock(core->mutex);
        core->torrents[id] = fresh;
    }
    fresh.save_resume_data(lt::torrent_handle::save_info_dict);
    return AT_OK;
}

char* at_files(at_session* session, const char* id) {
    lt::torrent_handle h = session && id ? session->core->find(id) : lt::torrent_handle();
    if (!h.is_valid()) return nullptr;
    auto ti = h.torrent_file();
    if (!ti) return nullptr;
    auto const& files = ti->files();
    std::string out = "[";
    for (auto const index : files.file_range()) {
        if (files.pad_file_at(index)) continue;
        if (out.size() > 1) out += ",";
        out += "{\"index\":" + std::to_string(static_cast<int>(index)) + ",\"path\":" +
               json_string(files.file_path(index)) + ",\"size\":" + std::to_string(files.file_size(index)) + "}";
    }
    return copy(out + "]");
}

char* at_file_progress(at_session* session, const char* id) {
    lt::torrent_handle h = session && id ? session->core->find(id) : lt::torrent_handle();
    if (!h.is_valid()) return nullptr;
    auto ti = h.torrent_file();
    if (!ti) return nullptr;
    // Piece granularity: a file counts as done only once every piece under it is verified.
    std::vector<std::int64_t> done;
    h.file_progress(done, lt::torrent_handle::piece_granularity);
    auto const priorities = h.get_file_priorities();
    auto const& files = ti->files();
    std::string out = "[";
    for (auto const index : files.file_range()) {
        if (files.pad_file_at(index)) continue;
        auto const i = static_cast<size_t>(static_cast<int>(index));
        if (out.size() > 1) out += ",";
        out += "{\"index\":" + std::to_string(i) + ",\"done\":" + std::to_string(i < done.size() ? done[i] : 0) +
               ",\"size\":" + std::to_string(files.file_size(index)) + ",\"priority\":" +
               std::to_string(i < priorities.size() ? static_cast<int>(static_cast<std::uint8_t>(priorities[i])) : 4) + "}";
    }
    return copy(out + "]");
}

int at_set_priorities(at_session* session, const char* id, const uint8_t* priorities, int count) {
    lt::torrent_handle h = session && id ? session->core->find(id) : lt::torrent_handle();
    if (!h.is_valid()) return AT_NO_TORRENT;
    if (!priorities || count < 0) return AT_BAD_INPUT;
    std::vector<lt::download_priority_t> list;
    for (int i = 0; i < count; ++i) list.emplace_back(std::min<std::uint8_t>(priorities[i], 7));
    h.prioritize_files(list);
    return AT_OK;
}

char* at_status(at_session* session) {
    if (!session) return nullptr;
    std::vector<std::pair<std::string, lt::torrent_handle>> handles;
    {
        std::lock_guard<std::mutex> lock(session->core->mutex);
        for (auto const& entry : session->core->torrents) handles.emplace_back(entry);
    }
    std::string out = "[";
    for (auto const& [id, h] : handles) {
        if (!h.is_valid()) continue;
        auto const st = h.status(lt::torrent_handle::query_name | lt::torrent_handle::query_save_path);
        if (out.size() > 1) out += ",";
        out += "{\"id\":" + json_string(id) + ",\"name\":" + json_string(st.name) +
               ",\"state\":" + json_string(state_name(st.state)) +
               ",\"paused\":" + ((st.flags & lt::torrent_flags::paused) ? "true" : "false") +
               ",\"finished\":" + (st.is_finished ? "true" : "false") +
               ",\"hasMetadata\":" + (st.has_metadata ? "true" : "false") +
               ",\"progress\":" + std::to_string(st.progress) +
               ",\"done\":" + std::to_string(st.total_wanted_done) +
               ",\"wanted\":" + std::to_string(st.total_wanted) +
               ",\"downloadRate\":" + std::to_string(st.download_payload_rate) +
               ",\"uploadRate\":" + std::to_string(st.upload_payload_rate) +
               ",\"uploaded\":" + std::to_string(st.all_time_upload) +
               ",\"downloaded\":" + std::to_string(st.all_time_download) +
               ",\"peers\":" + std::to_string(st.num_peers) + ",\"seeds\":" + std::to_string(st.num_seeds) +
               ",\"savePath\":" + json_string(st.save_path) +
               ",\"error\":" + (st.errc ? json_string(st.errc.message()) : std::string("null")) + "}";
    }
    return copy(out + "]");
}

uint8_t* at_torrent_file(at_session* session, const char* id, size_t* size) {
    lt::torrent_handle h = session && id ? session->core->find(id) : lt::torrent_handle();
    if (!h.is_valid() || !size) return nullptr;
    auto ti = h.torrent_file();
    if (!ti) return nullptr;
    std::vector<char> bytes;
    lt::bencode(std::back_inserter(bytes), lt::create_torrent(*ti).generate());
    auto* out = static_cast<uint8_t*>(std::malloc(bytes.size()));
    if (!out) return nullptr;
    std::memcpy(out, bytes.data(), bytes.size());
    *size = bytes.size();
    return out;
}

char* at_file_path(at_session* session, const char* id, int file) {
    lt::torrent_handle h = session && id ? session->core->find(id) : lt::torrent_handle();
    if (!h.is_valid()) return nullptr;
    auto ti = h.torrent_file();
    if (!ti || file < 0 || file >= ti->files().num_files()) return nullptr;
    return copy(h.status(lt::torrent_handle::query_save_path).save_path + "/" +
                ti->files().file_path(lt::file_index_t(file)));
}

int64_t at_file_size(at_session* session, const char* id, int file) {
    lt::torrent_handle h = session && id ? session->core->find(id) : lt::torrent_handle();
    if (!h.is_valid()) return AT_NO_TORRENT;
    auto ti = h.torrent_file();
    if (!ti) return AT_NO_METADATA;
    if (file < 0 || file >= ti->files().num_files()) return AT_BAD_INPUT;
    return ti->files().file_size(lt::file_index_t(file));
}

int64_t at_read(at_session* session, const char* id, int file, int64_t offset, void* buffer, int64_t length,
                int timeout_ms) {
    if (!session || !id || !buffer || offset < 0 || length < 0) return AT_BAD_INPUT;
    return session->core->read(id, file, offset, static_cast<char*>(buffer), length, timeout_ms);
}

namespace {

bool is_video(std::string const& path) {
    static char const* const kinds[] = {".mkv", ".mp4", ".m4v", ".avi", ".mov", ".webm", ".ts", ".m2ts", ".wmv", ".mpg"};
    std::string lower = path;
    std::transform(lower.begin(), lower.end(), lower.begin(), [](unsigned char c) { return std::tolower(c); });
    for (auto kind : kinds)
        if (lower.size() > std::strlen(kind) && lower.compare(lower.size() - std::strlen(kind), std::strlen(kind), kind) == 0)
            return true;
    return false;
}

std::string base_name(std::string const& path) {
    auto const slash = path.find_last_of("/\\");
    return slash == std::string::npos ? path : path.substr(slash + 1);
}

int64_t now_seconds() {
    return std::chrono::duration_cast<std::chrono::seconds>(std::chrono::system_clock::now().time_since_epoch()).count();
}

}  // namespace

char* at_stream(at_session* session, const char* magnet, const char* cache_dir, int file, const char* name_hint,
                int timeout_ms, int* code) {
    auto fail = [&](int c) -> char* {
        if (code) *code = c;
        return nullptr;
    };
    if (!session || !magnet || !cache_dir) return fail(AT_BAD_INPUT);
    auto core = session->core;
    lt::error_code ec;
    auto atp = lt::parse_magnet_uri(magnet, ec);
    if (ec) return fail(AT_BAD_INPUT);
    std::string const id = hex(atp.info_hashes.get_best());
    lt::torrent_handle h = core->find(id);
    bool const streamed = !h.is_valid() || h.status(lt::torrent_handle::query_save_path).save_path == cache_dir;
    if (!h.is_valid()) {
        ::mkdir(cache_dir, 0700);
        int const added = add(session, std::move(atp), cache_dir, 0, nullptr);
        if (added != AT_OK) return fail(added);
        h = core->find(id);
    } else {
        h.resume();
    }

    auto const deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(timeout_ms);
    std::shared_ptr<const lt::torrent_info> ti;
    {
        std::unique_lock<std::mutex> lock(core->mutex);
        if (!core->progress.wait_until(lock, deadline, [&] { return (ti = h.torrent_file()) != nullptr || !core->running; }) || !ti)
            return fail(AT_NO_METADATA);
    }
    auto const& files = ti->files();
    int chosen = file >= 0 && file < files.num_files() ? file : -1;
    if (chosen < 0 && name_hint && *name_hint) {
        for (auto const index : files.file_range())
            if (base_name(files.file_path(index)) == name_hint) chosen = static_cast<int>(index);
    }
    if (chosen < 0) {
        int64_t largest = -1;
        for (auto const index : files.file_range()) {
            if (files.pad_file_at(index) || !is_video(files.file_path(index))) continue;
            if (files.file_size(index) > largest) {
                largest = files.file_size(index);
                chosen = static_cast<int>(index);
            }
        }
    }
    if (chosen < 0) return fail(AT_NO_TORRENT);

    std::vector<lt::download_priority_t> priorities = h.get_file_priorities();
    priorities.resize(static_cast<size_t>(files.num_files()), lt::dont_download);
    if (streamed) std::fill(priorities.begin(), priorities.end(), lt::dont_download);
    if (priorities[static_cast<size_t>(chosen)] == lt::dont_download) priorities[static_cast<size_t>(chosen)] = lt::default_priority;
    h.prioritize_files(priorities);

    if (streamed) {
        std::lock_guard<std::mutex> lock(core->mutex);
        core->last_used[id] = now_seconds();
        core->save_cache_index();
    }
    lt::file_index_t const index(chosen);
    if (code) *code = AT_OK;
    return copy("{\"id\":" + json_string(id) + ",\"file\":" + std::to_string(chosen) + ",\"size\":" +
                std::to_string(files.file_size(index)) + ",\"path\":" + json_string(files.file_path(index)) + "}");
}

int at_cache_trim(at_session* session, const char* cache_dir, int64_t limit_bytes, const char* keep_id) {
    if (!session || !cache_dir) return 0;
    auto core = session->core;
    struct Entry {
        std::string id;
        lt::torrent_handle handle;
        int64_t bytes;
        int64_t used;
    };
    std::vector<Entry> cached;
    int64_t total = 0;
    {
        std::lock_guard<std::mutex> lock(core->mutex);
        for (auto const& [id, h] : core->torrents) {
            if (!h.is_valid()) continue;
            auto const st = h.status(lt::torrent_handle::query_save_path);
            if (st.save_path != cache_dir) continue;
            int64_t const bytes = st.total_wanted_done;
            auto const used = core->last_used.find(id);
            cached.push_back({id, h, bytes, used == core->last_used.end() ? 0 : used->second});
            total += bytes;
        }
    }
    std::sort(cached.begin(), cached.end(), [](Entry const& a, Entry const& b) { return a.used < b.used; });
    int removed = 0;
    for (auto const& entry : cached) {
        if (total <= limit_bytes) break;
        if (keep_id && entry.id == keep_id) continue;
        at_remove(session, entry.id.c_str(), 1);
        {
            std::lock_guard<std::mutex> lock(core->mutex);
            core->last_used.erase(entry.id);
            core->save_cache_index();
        }
        total -= entry.bytes;
        ++removed;
    }
    return removed;
}

char* at_server_start(at_session* session) {
    if (!session) return nullptr;
    if (!session->server) {
        auto server = std::make_unique<Server>();
        server->core = session->core;
        if (!server->start()) return nullptr;
        session->server = std::move(server);
    }
    return copy("http://127.0.0.1:" + std::to_string(session->server->port) + "/" + session->server->token);
}

void at_free(void* pointer) { std::free(pointer); }

}  // extern "C"
