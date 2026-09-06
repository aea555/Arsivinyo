#include "DnsSd.h"

#include <QHostAddress>
#include <QtEndian>

namespace dnssd {
namespace {

void appendU16(QByteArray *out, quint16 value) {
    out->append(char((value >> 8) & 0xff));
    out->append(char(value & 0xff));
}

void appendU32(QByteArray *out, quint32 value) {
    out->append(char((value >> 24) & 0xff));
    out->append(char((value >> 16) & 0xff));
    out->append(char((value >> 8) & 0xff));
    out->append(char(value & 0xff));
}

bool readU16(const QByteArray &packet, int *offset, quint16 *out) {
    if (*offset + 2 > packet.size()) return false;
    *out = quint16((quint8(packet[*offset]) << 8) | quint8(packet[*offset + 1]));
    *offset += 2;
    return true;
}

bool readU32(const QByteArray &packet, int *offset, quint32 *out) {
    if (*offset + 4 > packet.size()) return false;
    *out = (quint32(quint8(packet[*offset])) << 24) | (quint32(quint8(packet[*offset + 1])) << 16) |
           (quint32(quint8(packet[*offset + 2])) << 8) | quint32(quint8(packet[*offset + 3]));
    *offset += 4;
    return true;
}

/** Fill in the parsed views of RDATA for the record types this app reads. */
void interpret(const QByteArray &packet, int rdataStart, Record *record) {
    switch (record->type) {
        case kTypePtr: {
            int offset = rdataStart;
            readName(packet, &offset, &record->target);
            break;
        }
        case kTypeSrv: {
            int offset = rdataStart;
            quint16 priority = 0, weight = 0;
            if (!readU16(packet, &offset, &priority)) break;
            if (!readU16(packet, &offset, &weight)) break;
            if (!readU16(packet, &offset, &record->port)) break;
            readName(packet, &offset, &record->target);
            break;
        }
        case kTypeA: {
            if (record->data.size() != 4) break;
            record->address = QHostAddress(qFromBigEndian<quint32>(
                                               reinterpret_cast<const uchar *>(record->data.constData())))
                                  .toString();
            break;
        }
        case kTypeTxt: {
            // A sequence of length-prefixed "key=value" strings.
            int offset = 0;
            while (offset < record->data.size()) {
                const int length = quint8(record->data[offset++]);
                if (length == 0 || offset + length > record->data.size()) break;
                const QString entry = QString::fromUtf8(record->data.mid(offset, length));
                offset += length;
                const int equals = entry.indexOf('=');
                if (equals > 0) record->txt.insert(entry.left(equals), entry.mid(equals + 1));
            }
            break;
        }
        default:
            break;
    }
}

bool readRecord(const QByteArray &packet, int *offset, Record *out) {
    if (!readName(packet, offset, &out->name)) return false;
    if (!readU16(packet, offset, &out->type)) return false;
    if (!readU16(packet, offset, &out->klass)) return false;
    if (!readU32(packet, offset, &out->ttl)) return false;

    quint16 length = 0;
    if (!readU16(packet, offset, &length)) return false;
    if (*offset + length > packet.size()) return false;

    const int rdataStart = *offset;
    out->data = packet.mid(rdataStart, length);
    *offset += length;
    interpret(packet, rdataStart, out);
    return true;
}

}  // namespace

QByteArray encodeName(const QString &name) {
    QByteArray out;
    for (const QString &label : name.split('.', Qt::SkipEmptyParts)) {
        const QByteArray bytes = label.toUtf8();
        if (bytes.isEmpty() || bytes.size() > 63) continue;
        out.append(char(bytes.size()));
        out.append(bytes);
    }
    out.append('\0');
    return out;
}

bool readName(const QByteArray &packet, int *offset, QString *out) {
    QStringList labels;
    int cursor = *offset;
    bool jumped = false;
    // A pointer may only ever point backwards, so bounding the hops by the packet size
    // is enough to make a crafted loop terminate.
    int hops = 0;

    while (cursor < packet.size()) {
        const quint8 length = quint8(packet[cursor]);

        if ((length & 0xc0) == 0xc0) {
            if (cursor + 1 >= packet.size()) return false;
            const int target = ((length & 0x3f) << 8) | quint8(packet[cursor + 1]);
            if (!jumped) *offset = cursor + 2;
            jumped = true;
            if (target >= packet.size() || ++hops > packet.size()) return false;
            cursor = target;
            continue;
        }

        if (length == 0) {
            if (!jumped) *offset = cursor + 1;
            if (out) *out = labels.join('.');
            return true;
        }

        if (cursor + 1 + length > packet.size()) return false;
        labels.append(QString::fromUtf8(packet.mid(cursor + 1, length)));
        cursor += 1 + length;
    }
    return false;
}

bool parse(const QByteArray &packet, Message *out) {
    if (!out || packet.size() < 12) return false;

    int offset = 0;
    quint16 questionCount = 0, answerCount = 0, authorityCount = 0, additionalCount = 0;
    if (!readU16(packet, &offset, &out->id)) return false;
    if (!readU16(packet, &offset, &out->flags)) return false;
    if (!readU16(packet, &offset, &questionCount)) return false;
    if (!readU16(packet, &offset, &answerCount)) return false;
    if (!readU16(packet, &offset, &authorityCount)) return false;
    if (!readU16(packet, &offset, &additionalCount)) return false;

    for (int i = 0; i < questionCount; ++i) {
        Question question;
        if (!readName(packet, &offset, &question.name)) return false;
        if (!readU16(packet, &offset, &question.type)) return false;
        if (!readU16(packet, &offset, &question.klass)) return false;
        out->questions.append(question);
    }

    for (int i = 0; i < answerCount; ++i) {
        Record record;
        if (!readRecord(packet, &offset, &record)) return false;
        out->answers.append(record);
    }

    // Authority records are skipped rather than kept; nothing here consults them.
    for (int i = 0; i < authorityCount; ++i) {
        Record record;
        if (!readRecord(packet, &offset, &record)) return false;
    }

    for (int i = 0; i < additionalCount; ++i) {
        Record record;
        if (!readRecord(packet, &offset, &record)) return false;
        out->additional.append(record);
    }
    return true;
}

QByteArray buildQuery(const QString &name, quint16 type) {
    QByteArray out;
    appendU16(&out, 0);  // mDNS ignores the id
    appendU16(&out, 0);  // a query
    appendU16(&out, 1);  // one question
    appendU16(&out, 0);
    appendU16(&out, 0);
    appendU16(&out, 0);
    out.append(encodeName(name));
    appendU16(&out, type);
    appendU16(&out, kClassIn);
    return out;
}

QByteArray buildAnnouncement(const QString &serviceType, const QString &instanceName,
                             const QString &hostName, const QString &address, quint16 port,
                             const QMap<QString, QString> &txt) {
    const QString instance = instanceName + '.' + serviceType;

    QByteArray out;
    appendU16(&out, 0);
    appendU16(&out, 0x8400);  // response, authoritative
    appendU16(&out, 0);
    appendU16(&out, 4);  // PTR, SRV, TXT, A
    appendU16(&out, 0);
    appendU16(&out, 0);

    auto appendRecord = [&out](const QString &name, quint16 type, const QByteArray &rdata,
                               bool flush) {
        out.append(encodeName(name));
        appendU16(&out, type);
        appendU16(&out, quint16(kClassIn | (flush ? kCacheFlush : 0)));
        appendU32(&out, 120);
        appendU16(&out, quint16(rdata.size()));
        out.append(rdata);
    };

    // PTR is shared: several instances of the service may exist, so no cache-flush bit.
    appendRecord(serviceType, kTypePtr, encodeName(instance), false);

    QByteArray srv;
    appendU16(&srv, 0);  // priority
    appendU16(&srv, 0);  // weight
    appendU16(&srv, port);
    srv.append(encodeName(hostName));
    appendRecord(instance, kTypeSrv, srv, true);

    QByteArray text;
    for (auto it = txt.constBegin(); it != txt.constEnd(); ++it) {
        const QByteArray entry = (it.key() + '=' + it.value()).toUtf8();
        if (entry.size() > 255) continue;
        text.append(char(entry.size()));
        text.append(entry);
    }
    if (text.isEmpty()) text.append('\0');
    appendRecord(instance, kTypeTxt, text, true);

    QByteArray a;
    const quint32 raw = QHostAddress(address).toIPv4Address();
    appendU32(&a, raw);
    appendRecord(hostName, kTypeA, a, true);

    return out;
}

}  // namespace dnssd
