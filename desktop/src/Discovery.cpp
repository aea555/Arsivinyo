#include "Discovery.h"

#include <QDateTime>
#include <QHostAddress>
#include <QNetworkDatagram>
#include <QNetworkInterface>

#include "DnsSd.h"

namespace {

const QHostAddress kMulticastGroup(QStringLiteral("224.0.0.251"));
constexpr quint16 kMulticastPort = 5353;

/** Records are announced with a 120 s TTL; drop a peer once it is well past that. */
constexpr qint64 kStaleSeconds = 300;

QString localAddress() {
    for (const QHostAddress &address : QNetworkInterface::allAddresses()) {
        if (address.isLoopback() || address.protocol() != QAbstractSocket::IPv4Protocol) continue;
        return address.toString();
    }
    return QStringLiteral("127.0.0.1");
}

}  // namespace

QString Discovery::serviceType() { return QStringLiteral("_arsivinyo._tcp.local"); }

Discovery::Discovery(QObject *parent) : QAbstractListModel(parent) {
    m_expiry.setInterval(30000);
    connect(&m_expiry, &QTimer::timeout, this, [this] {
        forget(QDateTime::currentSecsSinceEpoch() - kStaleSeconds);
    });
}

bool Discovery::start(const QString &fingerprint, const QString &name, quint16 port) {
    stop();

    m_fingerprint = fingerprint;
    m_name = name;
    m_port = port;
    m_address = localAddress();

    m_socket = new QUdpSocket(this);
    // Shared, because other mDNS responders — an Avahi daemon, another copy of this app —
    // are normally already on 5353 and must keep working.
    if (!m_socket->bind(QHostAddress::AnyIPv4, kMulticastPort,
                        QUdpSocket::ShareAddress | QUdpSocket::ReuseAddressHint)) {
        delete m_socket;
        m_socket = nullptr;
        return false;
    }

    bool joined = false;
    for (const QNetworkInterface &interface : QNetworkInterface::allInterfaces()) {
        if (!(interface.flags() & QNetworkInterface::CanMulticast)) continue;
        if (!(interface.flags() & QNetworkInterface::IsRunning)) continue;
        joined = m_socket->joinMulticastGroup(kMulticastGroup, interface) || joined;
    }
    if (!joined) joined = m_socket->joinMulticastGroup(kMulticastGroup);
    if (!joined) {
        // A machine with no multicast-capable interface can still be paired by address.
        delete m_socket;
        m_socket = nullptr;
        return false;
    }

    connect(m_socket, &QUdpSocket::readyRead, this, &Discovery::onDatagram);
    m_expiry.start();
    if (m_port != 0) announce();
    browse();
    emit changed();
    return true;
}

void Discovery::stop() {
    m_expiry.stop();
    if (!m_socket) return;
    m_socket->close();
    m_socket->deleteLater();
    m_socket = nullptr;
    emit changed();
}

void Discovery::browse() {
    if (!m_socket) return;
    const QByteArray query = dnssd::buildQuery(serviceType(), dnssd::kTypePtr);
    m_socket->writeDatagram(query, kMulticastGroup, kMulticastPort);
}

void Discovery::announce() {
    if (!m_socket || m_port == 0) return;

    // The instance name has to be unique on the network; the fingerprint already is.
    const QString instance = m_fingerprint.left(16);
    const QMap<QString, QString> txt{
        {QStringLiteral("v"), QStringLiteral("1")},
        {QStringLiteral("id"), m_fingerprint},
        {QStringLiteral("name"), m_name},
    };
    const QByteArray announcement = dnssd::buildAnnouncement(
        serviceType(), instance, instance + QStringLiteral(".local"), m_address, m_port, txt);
    m_socket->writeDatagram(announcement, kMulticastGroup, kMulticastPort);
}

void Discovery::onDatagram() {
    while (m_socket && m_socket->hasPendingDatagrams()) {
        const QNetworkDatagram datagram = m_socket->receiveDatagram();
        dnssd::Message message;
        if (!dnssd::parse(datagram.data(), &message)) continue;

        if (!message.isResponse()) {
            // Someone is browsing. Answer only for our own service type.
            for (const dnssd::Question &question : message.questions) {
                if (question.name.compare(serviceType(), Qt::CaseInsensitive) == 0) {
                    announce();
                    break;
                }
            }
            continue;
        }

        // Collect what a response carries about one instance. Responders scatter these
        // across answers and additional records, so both are read the same way.
        QMap<QString, QString> txt;
        QString host;
        quint16 port = 0;
        QMap<QString, QString> addresses;

        const auto records = message.answers + message.additional;
        for (const dnssd::Record &record : records) {
            if (record.type == dnssd::kTypeTxt && !record.txt.isEmpty()) {
                for (auto it = record.txt.constBegin(); it != record.txt.constEnd(); ++it)
                    txt.insert(it.key(), it.value());
            } else if (record.type == dnssd::kTypeSrv) {
                host = record.target;
                port = record.port;
            } else if (record.type == dnssd::kTypeA) {
                addresses.insert(record.name, record.address);
            }
        }

        const QString fingerprint = txt.value(QStringLiteral("id"));
        if (fingerprint.isEmpty() || port == 0) continue;
        if (fingerprint == m_fingerprint) continue;  // ourselves

        // Prefer the address the responder gave for its own host name; fall back to where
        // the datagram actually came from, which is right for a responder behind a NAT.
        QString address = addresses.value(host);
        if (address.isEmpty()) address = datagram.senderAddress().toString();

        Found found;
        found.fingerprint = fingerprint;
        found.name = txt.value(QStringLiteral("name"));
        found.host = address;
        found.port = port;
        found.seenAt = QDateTime::currentSecsSinceEpoch();

        int existing = -1;
        for (int i = 0; i < m_found.size(); ++i) {
            if (m_found.at(i).fingerprint == fingerprint) { existing = i; break; }
        }
        if (existing >= 0) {
            m_found[existing] = found;
            emit dataChanged(index(existing), index(existing));
        } else {
            beginInsertRows({}, m_found.size(), m_found.size());
            m_found.append(found);
            endInsertRows();
            emit changed();
        }
        emit peerFound(found.fingerprint, found.name, found.host, found.port);
    }
}

void Discovery::forget(qint64 olderThan) {
    for (int i = m_found.size() - 1; i >= 0; --i) {
        if (m_found.at(i).seenAt >= olderThan) continue;
        beginRemoveRows({}, i, i);
        m_found.removeAt(i);
        endRemoveRows();
        emit changed();
    }
}

int Discovery::rowCount(const QModelIndex &parent) const {
    return parent.isValid() ? 0 : m_found.size();
}

QVariant Discovery::data(const QModelIndex &index, int role) const {
    if (index.row() < 0 || index.row() >= m_found.size()) return {};
    const Found &found = m_found.at(index.row());
    switch (role) {
        case FingerprintRole: return found.fingerprint;
        case NameRole: return found.name;
        case HostRole: return found.host;
        case PortRole: return found.port;
        default: return {};
    }
}

QHash<int, QByteArray> Discovery::roleNames() const {
    return {
        {FingerprintRole, "fingerprint"},
        {NameRole, "name"},
        {HostRole, "host"},
        {PortRole, "port"},
    };
}
