#include "PeerRegistry.h"

#include <QCryptographicHash>
#include <QDateTime>
#include <QDir>
#include <QSqlError>
#include <QSqlQuery>
#include <QStandardPaths>
#include <QUuid>

namespace {

constexpr int kPublicKeyBytes = 32;

QString dataDir() {
    QString dir = qEnvironmentVariable("ARSIVINYO_DATA_DIR");
    if (dir.isEmpty()) dir = QStandardPaths::writableLocation(QStandardPaths::AppDataLocation);
    if (dir.isEmpty()) dir = QDir::homePath() + "/.local/share/Arsivinyo";
    QDir().mkpath(dir);
    return dir;
}

QString hexFingerprint(const QByteArray &publicKey) {
    return QString::fromLatin1(
        QCryptographicHash::hash(publicKey, QCryptographicHash::Sha256).toHex());
}

}  // namespace

PeerRegistry::PeerRegistry(QObject *parent) : QAbstractListModel(parent) {
    // A unique connection name so two registries in one process — which is exactly what
    // the transport test needs — do not collide on Qt's global connection table.
    m_connectionName = QStringLiteral("peers-") + QUuid::createUuid().toString(QUuid::Id128);
    m_db = QSqlDatabase::addDatabase("QSQLITE", m_connectionName);
    m_db.setDatabaseName(dataDir() + "/peers.db");
    if (!m_db.open()) return;

    QSqlQuery q(m_db);
    q.exec(R"(CREATE TABLE IF NOT EXISTS peers (
                  public_key   BLOB PRIMARY KEY,
                  name         TEXT NOT NULL DEFAULT '',
                  last_address TEXT NOT NULL DEFAULT '',
                  paired_at    INTEGER NOT NULL DEFAULT 0
              ))");
    load();
}

PeerRegistry::~PeerRegistry() {
    m_db.close();
    m_db = QSqlDatabase();
    QSqlDatabase::removeDatabase(m_connectionName);
}

void PeerRegistry::load() {
    beginResetModel();
    m_peers.clear();
    QSqlQuery q(m_db);
    q.exec("SELECT public_key, name, last_address, paired_at FROM peers ORDER BY paired_at");
    while (q.next()) {
        Peer peer;
        peer.publicKey = q.value(0).toByteArray();
        peer.name = q.value(1).toString();
        peer.lastAddress = q.value(2).toString();
        peer.pairedAt = q.value(3).toLongLong();
        peer.fingerprint = hexFingerprint(peer.publicKey);
        m_peers.append(peer);
    }
    endResetModel();
    emit changed();
}

int PeerRegistry::rowCount(const QModelIndex &parent) const {
    return parent.isValid() ? 0 : m_peers.size();
}

QVariant PeerRegistry::data(const QModelIndex &index, int role) const {
    if (index.row() < 0 || index.row() >= m_peers.size()) return {};
    const Peer &peer = m_peers.at(index.row());
    switch (role) {
        case FingerprintRole: return peer.fingerprint;
        case NameRole: return peer.name;
        case LastAddressRole: return peer.lastAddress;
        case PairedAtRole: return peer.pairedAt;
        default: return {};
    }
}

QHash<int, QByteArray> PeerRegistry::roleNames() const {
    return {
        {FingerprintRole, "fingerprint"},
        {NameRole, "name"},
        {LastAddressRole, "lastAddress"},
        {PairedAtRole, "pairedAt"},
    };
}

bool PeerRegistry::remember(const QByteArray &publicKey, const QString &name,
                            const QString &address) {
    if (publicKey.size() != kPublicKeyBytes) return false;

    QSqlQuery q(m_db);
    // Re-pairing an existing device keeps its original pairedAt, so the list does not
    // reshuffle when a peer is paired again after a reinstall.
    q.prepare(R"(INSERT INTO peers (public_key, name, last_address, paired_at)
                 VALUES (?, ?, ?, ?)
                 ON CONFLICT(public_key) DO UPDATE SET name = excluded.name,
                                                       last_address = excluded.last_address)");
    q.addBindValue(publicKey);
    q.addBindValue(name);
    q.addBindValue(address);
    q.addBindValue(QDateTime::currentSecsSinceEpoch());
    if (!q.exec()) return false;
    load();
    return true;
}

bool PeerRegistry::forget(const QString &fingerprint) {
    QSqlQuery q(m_db);
    for (const Peer &peer : m_peers) {
        if (peer.fingerprint != fingerprint) continue;
        q.prepare("DELETE FROM peers WHERE public_key = ?");
        q.addBindValue(peer.publicKey);
        const bool ok = q.exec();
        if (ok) load();
        return ok;
    }
    return false;
}

bool PeerRegistry::isPaired(const QByteArray &publicKey) const {
    return peerFor(publicKey, nullptr);
}

bool PeerRegistry::peerFor(const QByteArray &publicKey, Peer *out) const {
    if (publicKey.size() != kPublicKeyBytes) return false;
    for (const Peer &peer : m_peers) {
        if (peer.publicKey != publicKey) continue;
        if (out) *out = peer;
        return true;
    }
    return false;
}

void PeerRegistry::noteAddress(const QByteArray &publicKey, const QString &address) {
    if (address.isEmpty() || !isPaired(publicKey)) return;
    QSqlQuery q(m_db);
    q.prepare("UPDATE peers SET last_address = ? WHERE public_key = ?");
    q.addBindValue(address);
    q.addBindValue(publicKey);
    if (q.exec()) load();
}
