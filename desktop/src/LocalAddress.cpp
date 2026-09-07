#include "LocalAddress.h"

#include <QHostAddress>
#include <QNetworkInterface>

namespace localaddress {

int Score(const Candidate &candidate) {
    if (candidate.address.isEmpty() || !candidate.isUp || candidate.isLoopback) return 0;

    const QHostAddress address(candidate.address);
    if (address.isNull() || address.protocol() != QAbstractSocket::IPv4Protocol) return 0;

    // A tunnel is a last resort: reachable only for a peer inside the same VPN, which is
    // not what "on the same network" means to someone pairing two devices in a room.
    if (candidate.isPointToPoint) return 1;

    const quint32 raw = address.toIPv4Address();
    const quint8 first = (raw >> 24) & 0xff;
    const quint8 second = (raw >> 16) & 0xff;

    // Link-local. Better than a tunnel, worse than a real lease.
    if (first == 169 && second == 254) return 2;

    // Carrier-grade NAT, which is also what Tailscale hands out. Routable inside that
    // network only, so it ranks above a tunnel interface but below a home LAN.
    if (first == 100 && second >= 64 && second <= 127) return 3;

    // The ordinary private ranges: what a phone and a desktop on the same Wi-Fi have.
    const bool isPrivate = first == 10 ||
                           (first == 192 && second == 168) ||
                           (first == 172 && second >= 16 && second <= 31);
    if (isPrivate) return 5;

    // A public address. Unusual for a home machine but routable.
    return 4;
}

QString Pick(const QVector<Candidate> &candidates) {
    QString best;
    int bestScore = 0;
    for (const Candidate &candidate : candidates) {
        const int score = Score(candidate);
        if (score > bestScore) {
            bestScore = score;
            best = candidate.address;
        }
    }
    return best;
}

QString OfThisMachine() {
    QVector<Candidate> candidates;
    for (const QNetworkInterface &interface : QNetworkInterface::allInterfaces()) {
        const auto flags = interface.flags();
        for (const QNetworkAddressEntry &entry : interface.addressEntries()) {
            candidates.append(Candidate{
                entry.ip().toString(),
                flags.testFlag(QNetworkInterface::IsLoopBack),
                flags.testFlag(QNetworkInterface::IsPointToPoint),
                flags.testFlag(QNetworkInterface::IsUp) &&
                    flags.testFlag(QNetworkInterface::IsRunning),
            });
        }
    }
    const QString picked = Pick(candidates);
    // Loopback is better than nothing: two copies of the app on one machine still pair.
    return picked.isEmpty() ? QStringLiteral("127.0.0.1") : picked;
}

}  // namespace localaddress
