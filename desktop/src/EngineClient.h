#pragma once

#include <QObject>
#include <QProcess>
#include <QQmlEngine>
#include <QString>

/**
 * Drives the download engine, which runs as a separate process.
 *
 * The engine is Python — the same `local_downloader.py` the Android app uses — frozen
 * into its own executable. It is not linked in and not embedded: embedding CPython in a
 * host process means owning the interpreter's lifecycle, the GIL and its module search
 * path, which is the problem Chaquopy exists to solve on Android and has no equivalent
 * here. A child process sidesteps all of it.
 *
 * The wire format is one JSON object per line in each direction, so this class is mostly
 * framing: write a request, read events, turn them into signals QML can bind to.
 */
class EngineClient : public QObject {
    Q_OBJECT
    QML_ELEMENT

    Q_PROPERTY(bool ready READ ready NOTIFY readyChanged)
    Q_PROPERTY(bool busy READ busy NOTIFY busyChanged)
    Q_PROPERTY(QString status READ status NOTIFY statusChanged)
    Q_PROPERTY(double progress READ progress NOTIFY progressChanged)
    Q_PROPERTY(QString ytDlpVersion READ ytDlpVersion NOTIFY ytDlpVersionChanged)
    Q_PROPERTY(QString enginePath READ enginePath CONSTANT)
    Q_PROPERTY(QString downloadDir READ downloadDir CONSTANT)
    Q_PROPERTY(bool updatingYtDlp READ updatingYtDlp NOTIFY ytDlpUpdateChanged)
    Q_PROPERTY(QString ytDlpUpdateStatus READ ytDlpUpdateStatus NOTIFY ytDlpUpdateChanged)
    Q_PROPERTY(double ytDlpUpdateProgress READ ytDlpUpdateProgress NOTIFY ytDlpUpdateChanged)

public:
    explicit EngineClient(QObject *parent = nullptr);
    ~EngineClient() override;

    bool ready() const { return m_ready; }
    bool busy() const { return !m_activeId.isEmpty(); }
    QString status() const { return m_status; }
    double progress() const { return m_progress; }
    QString ytDlpVersion() const { return m_ytDlpVersion; }
    QString enginePath() const { return m_enginePath; }
    QString downloadDir() const { return m_downloadDir; }

    Q_INVOKABLE void start();
    Q_INVOKABLE void download(const QString &url, const QString &outputDir, bool audioOnly);
    Q_INVOKABLE void cancel();
    /** Fetch the newest yt-dlp. It takes effect when the engine next starts. */
    Q_INVOKABLE void updateYtDlp();

    bool updatingYtDlp() const { return m_updatingYtDlp; }
    QString ytDlpUpdateStatus() const { return m_ytDlpUpdateStatus; }
    double ytDlpUpdateProgress() const { return m_ytDlpUpdateProgress; }

    /** What is on the clipboard, if it looks like a link. QML has no clipboard access. */
    Q_INVOKABLE QString clipboardUrl() const;
    /** Just the host, for showing the user what a click would fetch. */
    Q_INVOKABLE QString hostOf(const QString &url) const;

signals:
    void ytDlpUpdateChanged();
    void readyChanged();
    void busyChanged();
    void statusChanged();
    void progressChanged();
    void ytDlpVersionChanged();
    /** filePath and thumbnailPath are empty when the download failed. */
    void finished(bool ok, const QString &message, const QString &filePath, const QString &thumbnailPath);

private:
    void onStdout();
    void handleEvent(const QJsonObject &event);
    void send(const QJsonObject &request);
    static QString resolveEnginePath();
    /** Arguments for [resolveEnginePath], empty for a frozen sidecar. */
    static QStringList resolveEngineArgs(const QString &path);
    static QString resolveDownloadDir();

    QProcess m_process;
    QString m_enginePath;
    QStringList m_engineArgs;
    QString m_downloadDir;
    bool m_updatingYtDlp = false;
    QString m_ytDlpUpdateStatus;
    double m_ytDlpUpdateProgress = 0;
    QString m_activeId;
    QString m_status;
    QString m_ytDlpVersion;
    QByteArray m_buffer;
    double m_progress = 0.0;
    int m_nextId = 1;
    bool m_ready = false;
};
