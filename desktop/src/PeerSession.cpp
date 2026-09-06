#include "PeerSession.h"

#include <QFileInfo>
#include <QJsonDocument>
#include <QUuid>
#include <QtConcurrent>

namespace {

/** Big enough that per-frame overhead is noise, small enough to stay responsive. */
constexpr qint64 kChunkBytes = 256 * 1024;

/** Stop queueing when this much is already waiting on the socket. */
constexpr qint64 kHighWaterBytes = 4 * 1024 * 1024;

QString newTransferId() { return QUuid::createUuid().toString(QUuid::Id128); }

/** Hash a file in chunks. Returns empty if it cannot be read. */
QByteArray hashFile(const QString &path) {
    QFile file(path);
    if (!file.open(QIODevice::ReadOnly)) return {};
    QCryptographicHash hash(QCryptographicHash::Sha256);
    if (!hash.addData(&file)) return {};
    return hash.result();
}

}  // namespace

PeerSession::PeerSession(PeerLink *link, PeerContent *content, QObject *parent)
    : QObject(parent), m_link(link), m_content(content) {
    connect(m_link, &PeerLink::controlReceived, this, &PeerSession::onControl);
    connect(m_link, &PeerLink::bulkReceived, this, &PeerSession::onBulk);
    connect(m_link, &PeerLink::drained, this, &PeerSession::pumpSend);
    connect(m_link, &PeerLink::failed, this, [this](const QString &reason) {
        abortReceiving(reason);
        abortSending(reason);
    });
}

// ---- outgoing requests -------------------------------------------------------------

bool PeerSession::requestListing(const QString &kind) {
    return m_link->sendControl({{"t", "list"}, {"kind", kind}});
}

bool PeerSession::requestItem(const QString &id) {
    if (isTransferring()) return false;
    return m_link->sendControl({{"t", "get"}, {"id", id}});
}

bool PeerSession::requestDownload(const QString &url, const QString &mediaKind) {
    return m_link->sendControl({{"t", "download"}, {"url", url}, {"mediaKind", mediaKind}});
}

bool PeerSession::sendFile(const QString &path, const QString &kind) {
    if (isTransferring()) return false;
    return offer(path, kind);
}

bool PeerSession::offer(const QString &path, const QString &kind) {
    const QFileInfo info(path);
    if (!info.isFile()) return false;

    m_sending.active = true;
    m_sending.total = info.size();
    m_sending.sent = 0;
    m_sending.file.setFileName(path);

    // The protocol declares the hash before the first byte, so it has to be computed up
    // front. On a multi-gigabyte file that is seconds of work, which is why it runs off
    // the event loop rather than freezing the window before the transfer even starts.
    auto *watcher = new QFutureWatcher<QByteArray>(this);
    connect(watcher, &QFutureWatcher<QByteArray>::finished, this, [this, watcher, kind, info] {
        const QByteArray digest = watcher->result();
        watcher->deleteLater();
        if (!m_sending.active) return;  // cancelled while hashing
        if (digest.isEmpty()) {
            abortSending(QStringLiteral("could not read the file"));
            return;
        }
        m_sending.digest = digest;
        m_link->sendControl({
            {"t", "put"},
            {"name", info.fileName()},
            {"kind", kind},
            {"sizeBytes", static_cast<qint64>(info.size())},
            {"sha256", QString::fromLatin1(digest.toHex())},
        });
    });
    watcher->setFuture(QtConcurrent::run(hashFile, path));
    return true;
}

// ---- incoming ----------------------------------------------------------------------

void PeerSession::onControl(const QJsonObject &message) {
    const QString type = message.value("t").toString();

    if (type == QLatin1String("list")) {
        const QString kind = message.value("kind").toString();
        m_link->sendControl({{"t", "listing"}, {"kind", kind}, {"items", m_content->listing(kind)}});
    } else if (type == QLatin1String("listing")) {
        emit listingReceived(message.value("kind").toString(), message.value("items").toArray());
    } else if (type == QLatin1String("get")) {
        handleGet(message);
    } else if (type == QLatin1String("put")) {
        handlePut(message);
    } else if (type == QLatin1String("accept")) {
        handleAccept(message);
    } else if (type == QLatin1String("reject")) {
        abortSending(message.value("reason").toString());
    } else if (type == QLatin1String("complete")) {
        handleComplete(message);
    } else if (type == QLatin1String("cancel")) {
        abortReceiving(QStringLiteral("the peer cancelled"));
        abortSending(QStringLiteral("the peer cancelled"));
    } else if (type == QLatin1String("download")) {
        m_content->download(message.value("url").toString(),
                            message.value("mediaKind").toString());
    } else if (type == QLatin1String("error")) {
        emit transferFailed(message.value("code").toString());
    }
}

void PeerSession::handleGet(const QJsonObject &message) {
    if (isTransferring()) {
        m_link->sendControl({{"t", "reject"}, {"reason", "busy"}});
        return;
    }
    const QString path = m_content->pathForItem(message.value("id").toString());
    if (path.isEmpty() || !offer(path, QStringLiteral("music"))) {
        m_link->sendControl({{"t", "error"}, {"code", "NOT_FOUND"}, {"message", "no such item"}});
    }
}

void PeerSession::handlePut(const QJsonObject &message) {
    if (isTransferring()) {
        m_link->sendControl({{"t", "reject"}, {"reason", "busy"}});
        return;
    }

    const qint64 size = message.value("sizeBytes").toInteger();
    const QByteArray expected =
        QByteArray::fromHex(message.value("sha256").toString().toLatin1());
    if (size < 0 || expected.size() != QCryptographicHash::hashLength(QCryptographicHash::Sha256)) {
        m_link->sendControl({{"t", "reject"}, {"reason", "malformed"}});
        return;
    }

    // The receiver picks the path. A name from the peer is never joined onto a directory,
    // so a `../` or an absolute path in it cannot steer this write.
    const QString kind = message.value("kind").toString();
    const QString destination = m_content->destinationFor(message.value("name").toString(), kind);
    if (destination.isEmpty()) {
        m_link->sendControl({{"t", "reject"}, {"reason", "refused"}});
        return;
    }

    m_receiving.reset();
    m_receiving.file.setFileName(destination + QLatin1String(".part"));
    if (!m_receiving.file.open(QIODevice::WriteOnly | QIODevice::Truncate)) {
        m_link->sendControl({{"t", "reject"}, {"reason", "unwritable"}});
        return;
    }

    m_receiving.active = true;
    m_receiving.transferId = newTransferId();
    m_receiving.kind = kind;
    m_receiving.finalPath = destination;
    m_receiving.total = size;
    m_receiving.expectedHash = expected;

    m_link->sendControl({{"t", "accept"}, {"transferId", m_receiving.transferId}});
    emit transferStarted(QFileInfo(destination).fileName(), size);
}

void PeerSession::handleAccept(const QJsonObject &message) {
    if (!m_sending.active) return;
    if (!m_sending.file.open(QIODevice::ReadOnly)) {
        abortSending(QStringLiteral("could not open the file"));
        return;
    }
    m_sending.transferId = message.value("transferId").toString();
    pumpSend();
}

void PeerSession::onBulk(const QByteArray &chunk) {
    if (!m_receiving.active) {
        abortSending(QStringLiteral("unexpected data"));
        return;
    }

    // Refuse to write past the declared size rather than trusting the sender to stop.
    if (m_receiving.received + chunk.size() > m_receiving.total) {
        abortReceiving(QStringLiteral("the peer sent more than it declared"));
        return;
    }

    m_receiving.file.write(chunk);
    m_receiving.hash.addData(chunk);
    m_receiving.received += chunk.size();
    emit transferProgress(m_receiving.received, m_receiving.total);
}

void PeerSession::handleComplete(const QJsonObject &message) {
    if (!m_receiving.active) return;

    const QByteArray declared =
        QByteArray::fromHex(message.value("sha256").toString().toLatin1());
    m_receiving.file.close();

    // Size and hash both, deliberately. A truncated file hashes correctly to its own
    // truncated bytes, so the hash alone would accept it.
    const bool whole = m_receiving.received == m_receiving.total;
    const QByteArray actual = m_receiving.hash.result();
    if (!whole || actual != m_receiving.expectedHash || actual != declared) {
        abortReceiving(QStringLiteral("the transfer did not verify"));
        return;
    }

    const QString path = m_receiving.finalPath;
    const QString kind = m_receiving.kind;
    QFile::remove(path);
    if (!m_receiving.file.rename(path)) {
        abortReceiving(QStringLiteral("could not store the file"));
        return;
    }

    m_receiving.reset();
    m_content->accepted(path, kind);
    emit fileReceived(path, kind);
    emit transferComplete();
}

void PeerSession::pumpSend() {
    if (!m_sending.active || !m_sending.file.isOpen()) return;

    while (m_sending.sent < m_sending.total && m_link->bytesToWrite() < kHighWaterBytes) {
        const QByteArray chunk = m_sending.file.read(kChunkBytes);
        if (chunk.isEmpty()) {
            abortSending(QStringLiteral("the file ended early"));
            return;
        }
        if (!m_link->sendBulk(chunk)) {
            abortSending(QStringLiteral("the connection went away"));
            return;
        }
        m_sending.sent += chunk.size();
        emit transferProgress(m_sending.sent, m_sending.total);
    }

    if (m_sending.sent < m_sending.total) return;  // resumes on drained()

    // The digest computed before the offer, not a second pass over the file.
    m_link->sendControl({
        {"t", "complete"},
        {"transferId", m_sending.transferId},
        {"sha256", QString::fromLatin1(m_sending.digest.toHex())},
    });
    m_sending.reset();
    emit transferComplete();
}

void PeerSession::abortReceiving(const QString &reason) {
    if (!m_receiving.active) return;
    m_receiving.file.close();
    // The partial file goes away rather than being left for someone to find later.
    m_receiving.file.remove();
    m_receiving.reset();
    emit transferFailed(reason);
}

void PeerSession::abortSending(const QString &reason) {
    if (!m_sending.active) return;
    m_sending.reset();
    emit transferFailed(reason);
}
