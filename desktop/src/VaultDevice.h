#pragma once

// A read-only QIODevice over an encrypted vault object.
//
// The phone streams vault playback through a loopback HTTP server, because Android's player
// takes a URL and nothing else. Qt's does not have that constraint —
// QMediaPlayer::setSourceDevice takes a QIODevice — so the desktop needs no port, no token
// and no HTTP parser, and the plaintext never leaves this process.
//
// Seeking is the whole reason this is not a plain sequential stream: a player opens a file,
// jumps to the end for the container index, and comes back. The reader underneath maps a
// plaintext offset onto its segment, which is where the 1 MiB boundary arithmetic earns its
// tests.

#include <QFile>
#include <QIODevice>
#include <QString>

#include <memory>

#include "aead_stream.h"
#include "secret.h"

class VaultDevice : public QIODevice {
    Q_OBJECT

 public:
    /** Returns null if the object is missing or the key does not open it. */
    static VaultDevice *open(const QString &objectPath, const QString &associatedData,
                             const arsivinyo::crypto::SecretBytes &key, QObject *parent = nullptr);
    ~VaultDevice() override;

    bool isSequential() const override { return false; }
    qint64 size() const override;
    bool atEnd() const override;

 protected:
    qint64 readData(char *data, qint64 maxSize) override;
    qint64 writeData(const char *, qint64) override { return -1; }

 private:
    explicit VaultDevice(QObject *parent);

    std::unique_ptr<QFile> m_file;
    std::unique_ptr<arsivinyo::crypto::SeekableStreamReader> m_reader;
};
