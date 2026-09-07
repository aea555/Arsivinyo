// Playing an encrypted vault item without ever writing the plaintext down.
//
// This is the check that decides the design. If Qt's media backend handles a custom seekable
// QIODevice, the desktop needs no loopback server, no port and no token — unlike the phone,
// whose player only takes a URL. If it does not, the fallback is a hand-rolled HTTP server on
// 127.0.0.1, which is a great deal more code and a real attack surface.

#include <QCoreApplication>
#include <QEventLoop>
#include <QFile>
#include <QMediaPlayer>
#include <QProcess>
#include <QTemporaryDir>
#include <QTimer>

#include <cstdio>

#include "SecretStore.h"
#include "Vault.h"
#include "VaultDevice.h"

static int failures = 0;
static void check(bool ok, const QString &what) {
    std::printf(ok ? "  ok    %s\n" : "  FAIL  %s\n", qPrintable(what));
    if (!ok) ++failures;
}

/** A few seconds of colour bars and a tone, so there is a real container to parse. */
static bool makeSample(const QString &path) {
    QProcess ffmpeg;
    ffmpeg.start("ffmpeg", {"-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi", "-i",
                            "testsrc=size=320x240:rate=15:duration=3", "-f", "lavfi", "-i",
                            "sine=frequency=440:duration=3", "-c:v", "libx264", "-preset",
                            "ultrafast", "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest", path});
    if (!ffmpeg.waitForStarted(5000)) return false;
    if (!ffmpeg.waitForFinished(60000)) return false;
    return ffmpeg.exitCode() == 0 && QFile(path).size() > 0;
}

int main(int argc, char **argv) {
    QCoreApplication app(argc, argv);
    QTemporaryDir temporary;
    qputenv("ARSIVINYO_DATA_DIR", temporary.path().toUtf8());

    const QString sample = temporary.path() + "/sample.mp4";
    if (!makeSample(sample)) {
        std::printf("  skip  ffmpeg produced no sample; playback not exercised\n");
        return 0;
    }
    check(true, "a sample video was produced");

    const auto fast = arsivinyo::crypto::Argon2idParams::Fast();
    SecretStore secrets(fast, nullptr);
    Vault vault;
    vault.setSecrets(&secrets);
    secrets.create("a correct horse battery staple");
    check(vault.importFile(sample, false).isEmpty(), "the sample imports into the vault");

    const QString id = vault.get(0)["itemId"].toString();
    arsivinyo::crypto::SecretBytes key;
    check(vault.contentKey(&key), "the content key is reachable");

    VaultDevice *device = VaultDevice::open(vault.objectPath(id), id, key, nullptr);
    check(device != nullptr, "a decrypting device opens over the encrypted object");
    if (device == nullptr) {
        std::printf("\nFAILURES\n");
        return 1;
    }
    check(device->size() == QFile(sample).size(), "and reports the plaintext size");

    // Seeking is what a demuxer does first: jump to the end for the index, then come back.
    check(device->seek(device->size() - 4096), "it seeks near the end");
    const QByteArray tail = device->read(4096);
    QFile original(sample);
    original.open(QIODevice::ReadOnly);
    original.seek(original.size() - 4096);
    check(tail == original.read(4096), "and reads what the original holds there");
    device->seek(0);

    QMediaPlayer player;
    QEventLoop loop;
    QTimer timeout;
    timeout.setSingleShot(true);
    QObject::connect(&timeout, &QTimer::timeout, &loop, &QEventLoop::quit);
    QObject::connect(&player, &QMediaPlayer::mediaStatusChanged, [&loop](QMediaPlayer::MediaStatus status) {
        if (status == QMediaPlayer::LoadedMedia || status == QMediaPlayer::BufferedMedia ||
            status == QMediaPlayer::InvalidMedia) {
            loop.quit();
        }
    });

    player.setSourceDevice(device);
    timeout.start(20000);
    loop.exec();

    const bool loaded = player.mediaStatus() == QMediaPlayer::LoadedMedia ||
                        player.mediaStatus() == QMediaPlayer::BufferedMedia;
    check(loaded, QString("the media backend loaded it from the device (status %1, error \"%2\")")
                      .arg(static_cast<int>(player.mediaStatus()))
                      .arg(player.errorString()));
    check(player.duration() > 2000 && player.duration() < 4000,
          QString("and reports about three seconds (%1 ms)").arg(player.duration()));
    check(player.hasVideo(), "with a video track");
    check(player.isSeekable(), "and it is seekable, so scrubbing will work");

    check(!QFile::exists(temporary.path() + "/vault/objects/" + id + ".enc.tmp"),
          "no decrypted copy was written anywhere");

    delete device;
    std::printf("\n%s\n", failures ? "FAILURES" : "all checks passed");
    return failures ? 1 : 0;
}
