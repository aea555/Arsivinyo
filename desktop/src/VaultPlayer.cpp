#include "VaultPlayer.h"

#include <QVideoSink>

using namespace arsivinyo::crypto;

VaultPlayer::VaultPlayer(QObject *parent) : QObject(parent) {
    m_player = new QMediaPlayer(this);
    m_audio = new QAudioOutput(this);
    m_audio->setVolume(0.9);
    m_player->setAudioOutput(m_audio);

    connect(m_player, &QMediaPlayer::playbackStateChanged, this, &VaultPlayer::changed);
    connect(m_player, &QMediaPlayer::durationChanged, this, &VaultPlayer::changed);
    connect(m_player, &QMediaPlayer::positionChanged, this, &VaultPlayer::positionChanged);
    connect(m_player, &QMediaPlayer::errorOccurred, this,
            [this](QMediaPlayer::Error, const QString &message) {
                m_error = message;
                emit changed();
            });
}

VaultPlayer::~VaultPlayer() { release(); }

void VaultPlayer::setVault(Vault *vault) {
    if (m_vault == vault) return;
    m_vault = vault;
    if (m_vault != nullptr) {
        // Locking mid-playback has to stop it; the key is gone and the next read would fail.
        connect(m_vault, &Vault::changed, this, [this]() {
            if (!m_vault->unlocked() && active()) stop();
        });
    }
    emit changed();
}

QObject *VaultPlayer::videoSink() const { return m_player->videoSink(); }

void VaultPlayer::setVideoSink(QObject *sink) {
    m_player->setVideoSink(qobject_cast<QVideoSink *>(sink));
    emit changed();
}

qreal VaultPlayer::volume() const { return m_audio->volume(); }

void VaultPlayer::setVolume(qreal volume) {
    m_audio->setVolume(static_cast<float>(volume));
    emit changed();
}

void VaultPlayer::release() {
    m_player->stop();
    m_player->setSourceDevice(nullptr);
    delete m_device;
    m_device = nullptr;
}

void VaultPlayer::play(const QString &id) {
    if (m_vault == nullptr) return;
    m_error.clear();

    if (id == m_currentId && m_device != nullptr) {
        togglePause();
        return;
    }

    release();
    SecretBytes key;
    if (!m_vault->contentKey(&key)) {
        m_error = tr("Unlock first.");
        emit changed();
        return;
    }
    m_device = VaultDevice::open(m_vault->objectPath(id), id, key, this);
    if (m_device == nullptr) {
        m_error = tr("That item could not be opened.");
        m_currentId.clear();
        emit changed();
        return;
    }

    m_currentId = id;
    m_title.clear();
    for (int row = 0; row < m_vault->rowCount(); ++row) {
        if (m_vault->get(row)["itemId"].toString() == id) {
            m_title = m_vault->get(row)["title"].toString();
            break;
        }
    }
    m_player->setSourceDevice(m_device);
    m_player->play();
    emit changed();
}

void VaultPlayer::togglePause() {
    if (!active()) return;
    if (playing()) {
        m_player->pause();
    } else {
        m_player->play();
    }
    emit changed();
}

void VaultPlayer::stop() {
    release();
    m_currentId.clear();
    m_title.clear();
    emit changed();
}

void VaultPlayer::seek(qint64 milliseconds) {
    if (active()) m_player->setPosition(milliseconds);
}
