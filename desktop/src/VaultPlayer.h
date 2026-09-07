#pragma once

// Playback for the vault.
//
// This exists as C++ rather than a QML MediaPlayer for one reason: the source is a QIODevice
// that decrypts as it is read, and QML can only hand a player a URL. Everything else — the
// transport controls, the position — is the same as any other player.
//
// The device is owned here and destroyed when playback stops, so the decrypting reader does
// not outlive the item on screen.

#include <QAudioOutput>
#include <QMediaPlayer>
#include <QObject>
#include <QQmlEngine>
#include <QString>

#include "Vault.h"
#include "VaultDevice.h"

class VaultPlayer : public QObject {
    Q_OBJECT
    QML_ELEMENT

    Q_PROPERTY(Vault *vault READ vault WRITE setVault NOTIFY changed)
    /** Bound to a QML VideoOutput's videoSink. */
    Q_PROPERTY(QObject *videoSink READ videoSink WRITE setVideoSink NOTIFY changed)
    Q_PROPERTY(QString currentId READ currentId NOTIFY changed)
    Q_PROPERTY(QString title READ title NOTIFY changed)
    Q_PROPERTY(bool active READ active NOTIFY changed)
    Q_PROPERTY(bool playing READ playing NOTIFY changed)
    Q_PROPERTY(qint64 position READ position NOTIFY positionChanged)
    Q_PROPERTY(qint64 duration READ duration NOTIFY changed)
    Q_PROPERTY(qreal volume READ volume WRITE setVolume NOTIFY changed)
    Q_PROPERTY(QString error READ error NOTIFY changed)

 public:
    explicit VaultPlayer(QObject *parent = nullptr);
    ~VaultPlayer() override;

    Vault *vault() const { return m_vault; }
    void setVault(Vault *vault);
    QObject *videoSink() const;
    void setVideoSink(QObject *sink);

    QString currentId() const { return m_currentId; }
    QString title() const { return m_title; }
    bool active() const { return !m_currentId.isEmpty(); }
    bool playing() const { return m_player->playbackState() == QMediaPlayer::PlayingState; }
    qint64 position() const { return m_player->position(); }
    qint64 duration() const { return m_player->duration(); }
    qreal volume() const;
    void setVolume(qreal volume);
    QString error() const { return m_error; }

    Q_INVOKABLE void play(const QString &id);
    Q_INVOKABLE void togglePause();
    Q_INVOKABLE void stop();
    Q_INVOKABLE void seek(qint64 milliseconds);

 signals:
    void changed();
    void positionChanged();

 private:
    void release();

    QMediaPlayer *m_player = nullptr;
    QAudioOutput *m_audio = nullptr;
    VaultDevice *m_device = nullptr;
    Vault *m_vault = nullptr;
    QString m_currentId;
    QString m_title;
    QString m_error;
};
