#pragma once

#include <QByteArray>
#include <QList>
#include <QMap>
#include <QString>

/**
 * Just enough DNS-SD to announce and find `_arsivinyo._tcp` on the local network.
 *
 * Qt has no mDNS, and the alternatives are per-platform: Avahi on Linux, Bonjour on
 * Windows. Both would need wiring separately and neither ships with the app, so the wire
 * format — which is small, frozen since RFC 1035, and the same everywhere — is built here
 * instead. The phone does *not* use this: Android has `NsdManager` in the platform, and
 * reimplementing it there would be work with nothing to show for it.
 *
 * Message encoding never uses compression pointers, which is allowed. Parsing must follow
 * them, because other responders on the network do use them.
 */
namespace dnssd {

constexpr quint16 kTypeA = 1;
constexpr quint16 kTypePtr = 12;
constexpr quint16 kTypeTxt = 16;
constexpr quint16 kTypeSrv = 33;
constexpr quint16 kClassIn = 1;
/** Set on a response record to tell listeners to replace what they had cached. */
constexpr quint16 kCacheFlush = 0x8000;

struct Question {
    QString name;
    quint16 type = 0;
    quint16 klass = 0;
};

struct Record {
    QString name;
    quint16 type = 0;
    quint16 klass = 0;
    quint32 ttl = 0;
    QByteArray data;   ///< raw RDATA, still in wire form

    // Parsed views of RDATA, filled in for the types this app cares about.
    QString target;              ///< PTR target, or SRV target
    quint16 port = 0;            ///< SRV
    QString address;             ///< A
    QMap<QString, QString> txt;  ///< TXT key=value pairs
};

struct Message {
    quint16 id = 0;
    quint16 flags = 0;
    bool isResponse() const { return flags & 0x8000; }
    QList<Question> questions;
    QList<Record> answers;
    QList<Record> additional;
};

/** Encode a name as length-prefixed labels. */
QByteArray encodeName(const QString &name);

/**
 * Read a name at [offset], following compression pointers.
 *
 * [offset] advances past the name *as written here* — a pointer is two bytes however long
 * the name it points at. Returns false on a malformed or looping name; a packet from the
 * network must never be able to spin this forever.
 */
bool readName(const QByteArray &packet, int *offset, QString *out);

/** Parse a whole message. Returns false if it is malformed. */
bool parse(const QByteArray &packet, Message *out);

/** A query for one name and type. */
QByteArray buildQuery(const QString &name, quint16 type);

/** The four records that answer a browse: PTR, SRV, TXT and A. */
QByteArray buildAnnouncement(const QString &serviceType, const QString &instanceName,
                             const QString &hostName, const QString &address, quint16 port,
                             const QMap<QString, QString> &txt);

}  // namespace dnssd
