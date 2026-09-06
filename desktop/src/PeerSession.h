#pragma once

#include <QCryptographicHash>
#include <QFile>
#include <QFutureWatcher>
#include <QJsonArray>
#include <QJsonObject>
#include <QObject>
#include <QQmlEngine>
#include <QString>

#include "PeerContent.h"
#include "PeerLink.h"

/**
 * The four verbs, over one authenticated link.
 *
 * One transfer at a time per connection. The framing allows control and bulk to share a
 * connection precisely so a transfer can be cancelled mid-flight without tearing it down,
 * but interleaving two transfers would need a transfer id on every bulk frame — a cost
 * paid on every chunk to support something the app never asks for.
 *
 * **Integrity.** A received file is written to a `.part` beside its destination, hashed as
 * it arrives, and moved into place only once the declared size and SHA-256 both match. A
 * truncated transfer therefore never appears in a library, and it cannot pass by hashing
 * to its own truncated bytes because the size is checked too.
 */
class PeerSession : public QObject {
    Q_OBJECT
    QML_ELEMENT
    // A session exists only because a peer connected; QML gets one from PairingService.
    QML_UNCREATABLE("Obtained from PairingService.sessionFor()")

public:
    PeerSession(PeerLink *link, PeerContent *content, QObject *parent = nullptr);

    PeerLink *link() const { return m_link; }

    /** Ask the peer for its [kind] listing. */
    Q_INVOKABLE bool requestListing(const QString &kind);
    /** Ask the peer to send an item it listed. */
    Q_INVOKABLE bool requestItem(const QString &id);
    /** Offer a local file to the peer. */
    Q_INVOKABLE bool sendFile(const QString &path, const QString &kind);
    /** Ask the peer to fetch a URL itself. */
    Q_INVOKABLE bool requestDownload(const QString &url, const QString &mediaKind);

    Q_INVOKABLE bool isTransferring() const { return m_sending.active || m_receiving.active; }

signals:
    void listingReceived(const QString &kind, const QJsonArray &items);
    void transferStarted(const QString &name, qint64 sizeBytes);
    void transferProgress(qint64 done, qint64 total);
    /** [path] is where the file landed. Never logged: names are private. */
    void fileReceived(const QString &path, const QString &kind);
    void transferFailed(const QString &reason);
    void transferComplete();

private slots:
    void onControl(const QJsonObject &message);
    void onBulk(const QByteArray &chunk);
    void pumpSend();

private:
    // QFile and QCryptographicHash are neither copyable nor movable, so these are
    // cleared field by field rather than reassigned from a fresh instance.
    struct Sending {
        bool active = false;
        QString transferId;
        QFile file;
        qint64 sent = 0;
        qint64 total = 0;
        QByteArray digest;  ///< computed once, before the offer

        void reset() {
            file.close();
            file.setFileName(QString());
            active = false;
            transferId.clear();
            sent = total = 0;
            digest.clear();
        }
    };

    struct Receiving {
        bool active = false;
        QString transferId;
        QString kind;
        QString finalPath;
        QFile file;
        qint64 received = 0;
        qint64 total = 0;
        QByteArray expectedHash;
        QCryptographicHash hash{QCryptographicHash::Sha256};

        void reset() {
            file.close();
            file.setFileName(QString());
            active = false;
            transferId.clear();
            kind.clear();
            finalPath.clear();
            received = total = 0;
            expectedHash.clear();
            hash.reset();
        }
    };

    void handlePut(const QJsonObject &message);
    void handleGet(const QJsonObject &message);
    void handleAccept(const QJsonObject &message);
    void handleComplete(const QJsonObject &message);
    void abortReceiving(const QString &reason);
    void abortSending(const QString &reason);
    bool offer(const QString &path, const QString &kind);

    PeerLink *m_link = nullptr;
    PeerContent *m_content = nullptr;
    Sending m_sending;
    Receiving m_receiving;
};
