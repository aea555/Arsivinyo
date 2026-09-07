// Which address a peer is told to connect to.
//
// The scoring is tested over made-up interfaces rather than the machine's real ones, so
// the result does not depend on whether the test host happens to have a VPN up — which is
// exactly the situation that made this worth writing.
#include "LocalAddress.h"

#include <QCoreApplication>

#include <cstdio>

using namespace localaddress;

static int failures = 0;
static void check(bool ok, const char *what) {
    std::printf(ok ? "  ok    %s\n" : "  FAIL  %s\n", what);
    if (!ok) ++failures;
}

static Candidate lan(const char *address) { return {address, false, false, true}; }
static Candidate tunnel(const char *address) { return {address, false, true, true}; }

int main(int argc, char **argv) {
    QCoreApplication app(argc, argv);

    // ---- unusable ---------------------------------------------------------------------
    check(Score({"127.0.0.1", true, false, true}) == 0, "loopback is not offered to a peer");
    check(Score({"192.168.1.5", false, false, false}) == 0, "nor is an interface that is down");
    check(Score({"", false, false, true}) == 0, "nor is an empty address");
    check(Score({"fe80::1", false, false, true}) == 0, "IPv6 is not offered: the wire is IPv4");
    check(Score({"not an address", false, false, true}) == 0, "nor is rubbish");

    // ---- ordering ---------------------------------------------------------------------
    check(Score(lan("192.168.1.5")) > Score(tunnel("100.119.196.44")),
          "a home LAN address beats a VPN tunnel");
    check(Score(lan("10.0.0.7")) > Score(lan("100.100.0.1")),
          "and beats a carrier-grade NAT address");
    check(Score(lan("172.16.0.9")) > Score(lan("169.254.1.1")),
          "and beats a link-local one");
    check(Score(lan("169.254.1.1")) > Score(tunnel("10.0.0.1")),
          "even link-local beats a tunnel, which only a peer inside it can reach");

    // ---- the case this was written for ------------------------------------------------
    {
        // What this machine actually looked like: a Tailscale tunnel enumerated first, and
        // the Wi-Fi address after it. Taking the first non-loopback address advertised the
        // tunnel, which a phone on the same Wi-Fi could only reach through the VPN.
        const QVector<Candidate> real{
            tunnel("100.119.196.44"),
            lan("192.168.1.42"),
        };
        check(Pick(real) == QLatin1String("192.168.1.42"),
              "the Wi-Fi address is chosen over the tunnel, whatever the order");

        const QVector<Candidate> reversed{lan("192.168.1.42"), tunnel("100.119.196.44")};
        check(Pick(reversed) == QLatin1String("192.168.1.42"), "and the same in reverse");
    }

    // ---- a machine with only a tunnel --------------------------------------------------
    check(Pick({tunnel("100.119.196.44")}) == QLatin1String("100.119.196.44"),
          "a tunnel is still used when there is nothing else");
    check(Pick({}).isEmpty(), "and nothing usable yields nothing");
    check(Pick({{"127.0.0.1", true, false, true}}).isEmpty(),
          "a machine with only loopback offers no address");

    // ---- the real machine ---------------------------------------------------------------
    const QString mine = OfThisMachine();
    check(!mine.isEmpty(), "this machine reports an address");
    std::printf("        (this machine: %s)\n", qPrintable(mine));

    std::printf("\n%s\n", failures ? "FAILURES" : "all checks passed");
    return failures ? 1 : 0;
}
