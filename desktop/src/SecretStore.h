#pragma once

// The keybox on disk, and the unlocked master key for the life of the process.
//
// Nothing prompts at launch. The first operation that needs a key asks, and the answer is
// held for the run. If a key file slot exists — "remember on this device" — the unlock
// happens silently at construction and no prompt is ever shown.
//
// Every member QML touches is a Q_PROPERTY or Q_INVOKABLE. A plain C++ method is not callable
// from QML, and that exact mistake once left the engine failing to start with no error.

#include <QJsonObject>
#include <QObject>
#include <QString>
#include <QtQml/qqmlregistration.h>

#include <memory>

#include "keybox.h"
#include "secret.h"

class SecretStore : public QObject {
    Q_OBJECT
    QML_ELEMENT

    /** A keybox exists on disk. False means the user has never set a passphrase. */
    Q_PROPERTY(bool configured READ configured NOTIFY changed)
    /** The master key is in memory and subkeys can be derived. */
    Q_PROPERTY(bool unlocked READ unlocked NOTIFY changed)
    /** A key file slot exists, so this device does not ask. Convenience, not secrecy. */
    Q_PROPERTY(bool remembered READ remembered NOTIFY changed)
    /** Why the last unlock was asked for, so the prompt can say what it is protecting. */
    Q_PROPERTY(QString pendingReason READ pendingReason NOTIFY changed)

 public:
    explicit SecretStore(QObject *parent = nullptr);
    /** Test seam: cheap Argon2 parameters, so a suite is not 20 seconds of key stretching. */
    SecretStore(const arsivinyo::crypto::Argon2idParams &params, QObject *parent);
    ~SecretStore() override;

    bool configured() const;
    bool unlocked() const { return !m_masterKey.empty(); }
    bool remembered() const;
    QString pendingReason() const { return m_pendingReason; }

    /** First run. Creates the keybox with a single passphrase slot. */
    Q_INVOKABLE QString create(const QString &passphrase);
    /** Returns an empty string on success, or a message to show. */
    Q_INVOKABLE QString unlock(const QString &passphrase);
    Q_INVOKABLE void lock();
    Q_INVOKABLE QString changePassphrase(const QString &oldPassphrase, const QString &newPassphrase);
    /** Adds or removes the key file slot. Never re-encrypts anything. */
    Q_INVOKABLE QString setRemembered(bool remember);
    /** Writes a recovery key file the user can put somewhere safe. */
    Q_INVOKABLE QString exportRecoveryKey(const QString &fileUrl);
    /** Unlocks from a recovery key file when the passphrase is gone. */
    Q_INVOKABLE QString unlockWithRecoveryKey(const QString &fileUrl);

    /** Asks the UI to prompt. Callers that need a key use this instead of blocking. */
    Q_INVOKABLE void requestUnlock(const QString &reason);

    /** For C++ consumers: a 32-byte key for one purpose. False when locked. */
    bool purposeKey(const QString &purpose, arsivinyo::crypto::SecretBytes *out) const;

 signals:
    void changed();
    void unlockRequested(QString reason);

 private:
    QString keyboxPath() const;
    QString keyfilePath() const;
    bool load(QJsonObject *out) const;
    QString save(const QJsonObject &document) const;
    /** Wraps a prepared slot — salt already chosen and used — and stores it. */
    QString addSlot(arsivinyo::crypto::Slot slot, const arsivinyo::crypto::SecretBytes &kek);
    QString removeSlot(const QString &id);

    arsivinyo::crypto::Argon2idParams m_params;
    arsivinyo::crypto::SecretBytes m_masterKey;
    QString m_pendingReason;
};
