#pragma once

#include <QString>
#include <QVector>

/**
 * Which of this machine's addresses to hand a peer.
 *
 * Picking the first non-loopback IPv4 is wrong on a machine with a VPN: a tunnel is
 * usually the *first* interface and often the default route, so a peer on the same room's
 * Wi-Fi is told to connect to an address that only exists inside the tunnel. That is not
 * hypothetical — it is what this app did, and it advertised a Tailscale address.
 *
 * Ordinary LAN addresses are preferred, then anything else routable, and a tunnel is used
 * only when there is nothing else. The scoring is separated from the enumeration so it can
 * be tested without depending on whatever interfaces the test machine happens to have.
 */
namespace localaddress {

struct Candidate {
    QString address;
    bool isLoopback = false;
    /** A tunnel: VPNs present as point-to-point interfaces. */
    bool isPointToPoint = false;
    bool isUp = false;
};

/** Higher is better. Zero means unusable. */
int Score(const Candidate &candidate);

/** The best of [candidates], or empty if none is usable. */
QString Pick(const QVector<Candidate> &candidates);

/** [Pick] over this machine's real interfaces. */
QString OfThisMachine();

}  // namespace localaddress
