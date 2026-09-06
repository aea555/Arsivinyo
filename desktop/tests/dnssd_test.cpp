// The DNS-SD wire format, which the desktop implements itself because Qt has no mDNS.
//
// The parsing side is what matters here: these bytes arrive from anything on the network,
// including responders that are not this app and packets that are not well formed.
#include "Discovery.h"
#include "DnsSd.h"

#include <QCoreApplication>
#include <QElapsedTimer>
#include <QEventLoop>
#include <QtEndian>

#include <cstdio>

static int failures = 0;
static void check(bool ok, const char *what) {
    std::printf(ok ? "  ok    %s\n" : "  FAIL  %s\n", what);
    if (!ok) ++failures;
}

int main(int argc, char **argv) {
    QCoreApplication app(argc, argv);

    // ---- names ----------------------------------------------------------------------
    {
        const QByteArray encoded = dnssd::encodeName(QStringLiteral("_arsivinyo._tcp.local"));
        check(encoded == QByteArray("\012_arsivinyo\004_tcp\005local\000", 23),
              "a name encodes as length-prefixed labels");

        int offset = 0;
        QString name;
        check(dnssd::readName(encoded, &offset, &name), "and reads back");
        check(name == QLatin1String("_arsivinyo._tcp.local"), "unchanged");
        check(offset == encoded.size(), "consuming exactly its own bytes");
    }

    // ---- compression pointers --------------------------------------------------------
    {
        // "local" at offset 0, then a name whose last label is a pointer back to it.
        QByteArray packet("\005local\000", 7);
        packet.append("\004host", 5);
        packet.append(char(0xc0));
        packet.append(char(0x00));

        int offset = 7;
        QString name;
        check(dnssd::readName(packet, &offset, &name), "a compressed name reads");
        check(name == QLatin1String("host.local"), "following the pointer");
        check(offset == packet.size(), "and a pointer costs two bytes however long its target");
    }

    // ---- a malformed packet must not hang or read past the end ------------------------
    {
        // A pointer to itself: the classic decompression loop.
        QByteArray loop;
        loop.append(char(0xc0));
        loop.append(char(0x00));
        int offset = 0;
        QString name;
        check(!dnssd::readName(loop, &offset, &name), "a self-referential pointer is refused");

        // A label that claims to run past the end of the packet.
        QByteArray overrun("\077short", 6);
        offset = 0;
        check(!dnssd::readName(overrun, &offset, &name), "a label past the end is refused");

        dnssd::Message message;
        check(!dnssd::parse(QByteArray("\0\0\0", 3), &message), "a runt packet is refused");
        check(!dnssd::parse(QByteArray(), &message), "and so is an empty one");

        // A header that promises more records than the packet contains.
        QByteArray lying(12, '\0');
        lying[3] = char(0x00);
        lying[5] = char(0x10);  // sixteen questions, none present
        check(!dnssd::parse(lying, &message), "a header that overpromises is refused");
    }

    // ---- a query ---------------------------------------------------------------------
    {
        const QByteArray query = dnssd::buildQuery(QStringLiteral("_arsivinyo._tcp.local"),
                                                   dnssd::kTypePtr);
        dnssd::Message message;
        check(dnssd::parse(query, &message), "a query parses");
        check(!message.isResponse(), "and is not marked a response");
        check(message.questions.size() == 1, "with one question");
        check(message.questions.at(0).name == QLatin1String("_arsivinyo._tcp.local"),
              "naming the service");
        check(message.questions.at(0).type == dnssd::kTypePtr, "as a PTR lookup");
    }

    // ---- an announcement round trips --------------------------------------------------
    {
        const QMap<QString, QString> txt{
            {QStringLiteral("v"), QStringLiteral("1")},
            {QStringLiteral("id"), QStringLiteral("abc123")},
            {QStringLiteral("name"), QStringLiteral("Desktop")},
        };
        const QByteArray packet = dnssd::buildAnnouncement(
            QStringLiteral("_arsivinyo._tcp.local"), QStringLiteral("abc123"),
            QStringLiteral("abc123.local"), QStringLiteral("192.168.1.42"), 7441, txt);

        dnssd::Message message;
        check(dnssd::parse(packet, &message), "an announcement parses");
        check(message.isResponse(), "and is marked a response");
        check(message.answers.size() == 4, "carrying PTR, SRV, TXT and A");

        bool sawSrv = false, sawTxt = false, sawA = false, sawPtr = false;
        for (const dnssd::Record &record : message.answers) {
            if (record.type == dnssd::kTypeSrv) {
                sawSrv = true;
                check(record.port == 7441, "the SRV record carries the port");
                check(record.target == QLatin1String("abc123.local"), "and the host name");
            } else if (record.type == dnssd::kTypeTxt) {
                sawTxt = true;
                check(record.txt.value(QStringLiteral("id")) == QLatin1String("abc123"),
                      "the TXT record carries the fingerprint");
                check(record.txt.value(QStringLiteral("name")) == QLatin1String("Desktop"),
                      "and the device name");
            } else if (record.type == dnssd::kTypeA) {
                sawA = true;
                check(record.address == QLatin1String("192.168.1.42"),
                      "the A record carries the address");
            } else if (record.type == dnssd::kTypePtr) {
                sawPtr = true;
                check(record.target == QLatin1String("abc123._arsivinyo._tcp.local"),
                      "the PTR record names the instance");
            }
        }
        check(sawPtr && sawSrv && sawTxt && sawA, "all four record types are present");
    }

    // ---- a TXT record with no values must not be mistaken for one with values ---------
    {
        const QByteArray packet = dnssd::buildAnnouncement(
            QStringLiteral("_arsivinyo._tcp.local"), QStringLiteral("x"),
            QStringLiteral("x.local"), QStringLiteral("10.0.0.1"), 1, {});
        dnssd::Message message;
        check(dnssd::parse(packet, &message), "an announcement with no TXT values parses");
        for (const dnssd::Record &record : message.answers) {
            if (record.type != dnssd::kTypeTxt) continue;
            check(record.txt.isEmpty(), "and yields no TXT pairs");
        }
    }

    // ---- two responders on the real network find each other ---------------------------
    //
    // Skipped rather than failed where multicast is unavailable — a build container, or a
    // machine with no multicast-capable interface. The codec above is what is always
    // checked; this is the part that can only be proved by putting packets on the wire.
    {
        Discovery a, b;
        const bool bound = a.start(QStringLiteral("aaaa1111"), QStringLiteral("DeviceA"), 7441) &&
                           b.start(QStringLiteral("bbbb2222"), QStringLiteral("DeviceB"), 7442);
        if (!bound) {
            std::printf("  skip  live discovery (multicast unavailable here)\n");
        } else {
            QElapsedTimer timer;
            timer.start();
            while (timer.elapsed() < 5000 && (a.rowCount() == 0 || b.rowCount() == 0))
                QCoreApplication::processEvents(QEventLoop::AllEvents, 20);

            check(a.rowCount() >= 1, "a responder finds the other");
            check(b.rowCount() >= 1, "and is found by it");
            if (a.rowCount() >= 1) {
                const QModelIndex row = a.index(0);
                check(a.data(row, Discovery::FingerprintRole).toString() ==
                          QLatin1String("bbbb2222"),
                      "carrying the peer's fingerprint");
                check(a.data(row, Discovery::NameRole).toString() == QLatin1String("DeviceB"),
                      "and its name");
                check(a.data(row, Discovery::PortRole).toInt() == 7442, "and its port");
            }
            // A device must not list itself, or the UI offers to pair with this machine.
            for (int i = 0; i < a.rowCount(); ++i) {
                check(a.data(a.index(i), Discovery::FingerprintRole).toString() !=
                          QLatin1String("aaaa1111"),
                      "and never lists itself");
            }
        }
    }

    std::printf("\n%s\n", failures ? "FAILURES" : "all checks passed");
    return failures ? 1 : 0;
}
