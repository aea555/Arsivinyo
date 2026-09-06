#pragma once

#include <QObject>
#include <QQmlEngine>
#include <QString>

#include "EngineClient.h"
#include "Library.h"
#include "PeerContent.h"

/**
 * What a paired peer may reach on this desktop: the music library, and nothing else.
 *
 * The vault is absent deliberately — it is confined to the device that made it, and a
 * `.avsbck` backup is the only supported way to move its contents. Settings, playlists and
 * library management are absent too: `download` hands over a URL and that is the whole of
 * what a peer can ask this device to *do*.
 *
 * Incoming files land in the music folder under a name this side chooses. A peer never
 * gets to compose a path.
 */
class LibraryContent : public QObject, public PeerContent {
    Q_OBJECT
    QML_ELEMENT

    Q_PROPERTY(Library *library READ library WRITE setLibrary NOTIFY wiringChanged)
    Q_PROPERTY(EngineClient *engine READ engine WRITE setEngine NOTIFY wiringChanged)

public:
    explicit LibraryContent(QObject *parent = nullptr);
    LibraryContent(Library *library, EngineClient *engine, QObject *parent = nullptr);

    Library *library() const { return m_library; }
    void setLibrary(Library *library);
    EngineClient *engine() const { return m_engine; }
    void setEngine(EngineClient *engine);

    QJsonArray listing(const QString &kind) const override;
    QString pathForItem(const QString &id) const override;
    QString destinationFor(const QString &name, const QString &kind) const override;
    void accepted(const QString &path, const QString &kind) override;
    void download(const QString &url, const QString &mediaKind) override;

signals:
    void wiringChanged();
    /** A peer asked this device to fetch a URL. The UI says so; nothing starts silently. */
    void downloadRequested(const QString &url, const QString &mediaKind);
    /** A file arrived. The path is never logged — names are private. */
    void fileAdopted(const QString &id);

private:
    Library *m_library = nullptr;
    EngineClient *m_engine = nullptr;
};
