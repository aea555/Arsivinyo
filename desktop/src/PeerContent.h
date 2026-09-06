#pragma once

#include <QJsonArray>
#include <QString>

/**
 * What a peer is allowed to see and do on this device.
 *
 * The transport deliberately knows nothing about libraries, MediaStore or folders. It
 * asks this interface, and the answer is the whole of what a paired device can reach —
 * which makes the boundary reviewable in one place rather than spread through the
 * connection handling. The vault is absent from it on purpose: a vault is confined to the
 * device that made it, and a `.avsbck` backup is the only supported way to move its
 * contents.
 */
class PeerContent {
public:
    virtual ~PeerContent() = default;

    /** Items of [kind] — "music" or "backups" — as protocol `listing` entries. */
    virtual QJsonArray listing(const QString &kind) const = 0;

    /** The file behind an id from [listing], or empty if the peer may not have it. */
    virtual QString pathForItem(const QString &id) const = 0;

    /**
     * Where an incoming file should be written, given the name the sender chose.
     * Returning an empty string refuses the transfer.
     *
     * The implementation, not the sender, decides the final path — a peer must never be
     * able to steer a write by sending a name with a slash or a `..` in it.
     */
    virtual QString destinationFor(const QString &name, const QString &kind) const = 0;

    /** A completed file has landed at [path]; adopt it into the library. */
    virtual void accepted(const QString &path, const QString &kind) = 0;

    /** The peer asked this device to fetch a URL itself. */
    virtual void download(const QString &url, const QString &mediaKind) = 0;
};
