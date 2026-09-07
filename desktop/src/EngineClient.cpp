#include "EngineClient.h"

#include <QCoreApplication>
#include <QDir>
#include <QFileInfo>
#include <QJsonDocument>
#include <QJsonObject>
#include <QProcessEnvironment>
#include <QClipboard>
#include <QGuiApplication>
#include <QStandardPaths>
#include <QUrl>

EngineClient::EngineClient(QObject *parent)
    : QObject(parent),
      m_enginePath(resolveEnginePath()),
      m_engineArgs(resolveEngineArgs(m_enginePath)),
      m_downloadDir(resolveDownloadDir()) {
    connect(&m_process, &QProcess::readyReadStandardOutput, this, &EngineClient::onStdout);
    connect(&m_process, &QProcess::errorOccurred, this, [this](QProcess::ProcessError) {
        m_status = QStringLiteral("engine failed to start: %1").arg(m_process.errorString());
        emit statusChanged();
    });
}

EngineClient::~EngineClient() {
    if (m_process.state() != QProcess::NotRunning) {
        // Closing stdin ends the engine's read loop; it exits on its own.
        m_process.closeWriteChannel();
        if (!m_process.waitForFinished(3000)) m_process.kill();
    }
}

QString EngineClient::resolveEnginePath() {
    // An explicit override first, so a development build can point at a freeze that
    // lives outside the tree without being installed.
    const QString fromEnv = QProcessEnvironment::systemEnvironment().value("ARSIVINYO_ENGINE");
    if (!fromEnv.isEmpty()) return fromEnv;

    // Otherwise the sidecar sits beside the application, as it does when shipped.
    const QDir dir(QCoreApplication::applicationDirPath());
    for (const QString &name : {QStringLiteral("arsivinyo-engine"), QStringLiteral("arsivinyo-engine.exe")}) {
        const QFileInfo candidate(dir.filePath(name));
        if (candidate.isExecutable()) return candidate.absoluteFilePath();
    }

    // Failing that, run the engine from source. A checkout has no frozen sidecar until
    // someone builds one, and refusing to start over that leaves a developer staring at an
    // error for a file that was never meant to exist yet.
    QDir search(QCoreApplication::applicationDirPath());
    for (int up = 0; up < 5; ++up) {
        // bootstrap.py, not host.py: the bootstrap is what puts yt-dlp on sys.path and
        // applies a downloaded override. Starting the host directly leaves no extractor.
        const QFileInfo boot(search.filePath(QStringLiteral("shared/engine/bootstrap.py")));
        if (boot.isFile()) return boot.absoluteFilePath();
        if (!search.cdUp()) break;
    }
    return {};
}

QStringList EngineClient::resolveEngineArgs(const QString &path) {
    // A frozen sidecar runs itself; the source host needs an interpreter in front of it.
    if (!path.endsWith(QStringLiteral(".py"))) return {};
    return {path};
}

QString EngineClient::resolveDownloadDir() {
    QString dir = QProcessEnvironment::systemEnvironment().value("ARSIVINYO_DOWNLOAD_DIR");
    if (dir.isEmpty())
        dir = QStandardPaths::writableLocation(QStandardPaths::DownloadLocation);
    if (dir.isEmpty())
        dir = QDir::homePath();
    QDir().mkpath(dir);
    return dir;
}

void EngineClient::restart() {
    if (m_process.state() != QProcess::NotRunning) {
        // Closing stdin ends the read loop; the engine finishes what it is doing and
        // exits, which is what lets a queued yt-dlp be picked up on the way back up.
        m_process.closeWriteChannel();
        if (!m_process.waitForFinished(3000)) m_process.kill();
        m_process.waitForFinished(1000);
    }
    m_ready = false;
    m_ytDlpVersion.clear();
    emit readyChanged();
    emit ytDlpVersionChanged();
    start();
}

void EngineClient::start() {
    if (m_process.state() != QProcess::NotRunning) return;
    if (m_enginePath.isEmpty()) {
        m_status = QStringLiteral("engine not found — set ARSIVINYO_ENGINE or ship it beside the app");
        emit statusChanged();
        return;
    }
    QProcessEnvironment env = QProcessEnvironment::systemEnvironment();
    env.insert(QStringLiteral("ARSIVINYO_ENGINE_ROOT"), QCoreApplication::applicationDirPath());
    m_process.setProcessEnvironment(env);

    if (m_engineArgs.isEmpty()) {
        m_process.start(m_enginePath, {});
    } else {
        m_process.start(QStringLiteral("python3"), m_engineArgs);
    }
}

void EngineClient::updateYtDlp() {
    if (m_updatingYtDlp || m_process.state() != QProcess::Running) return;
    m_updatingYtDlp = true;
    m_ytDlpUpdateProgress = 0;
    m_ytDlpUpdateStatus = QStringLiteral("checking");
    emit ytDlpUpdateChanged();
    send({{"id", "ytdlp"}, {"op", "updateYtDlp"}});
}

void EngineClient::send(const QJsonObject &request) {
    m_process.write(QJsonDocument(request).toJson(QJsonDocument::Compact) + '\n');
}

void EngineClient::download(const QString &url, const QString &outputDir, bool audioOnly) {
    if (!m_ready || busy() || url.isEmpty()) return;
    m_activeId = QString::number(m_nextId++);
    m_progress = 0.0;
    m_status = QStringLiteral("starting");
    emit busyChanged();
    emit progressChanged();
    emit statusChanged();
    send({{"id", m_activeId}, {"op", "download"}, {"url", url},
          {"outputDir", outputDir}, {"audioOnly", audioOnly}});
}

void EngineClient::cancel() {
    if (m_activeId.isEmpty()) return;
    send({{"id", m_activeId}, {"op", "cancel"}});
}

QString EngineClient::clipboardUrl() const {
    const QString text = QGuiApplication::clipboard()->text().trimmed();
    if (text.isEmpty() || text.contains(QLatin1Char('\n'))) return {};
    const QUrl url(text);
    // A scheme and a host, or it is not a link worth offering to download.
    if (!url.isValid() || url.host().isEmpty()) return {};
    if (url.scheme() != QLatin1String("http") && url.scheme() != QLatin1String("https")) return {};
    return text;
}

QString EngineClient::hostOf(const QString &url) const {
    const QString host = QUrl(url).host();
    return host.startsWith(QLatin1String("www.")) ? host.mid(4) : host;
}

void EngineClient::onStdout() {
    m_buffer.append(m_process.readAllStandardOutput());
    // One JSON object per line; a partial line stays buffered until its newline arrives.
    int newline;
    while ((newline = m_buffer.indexOf('\n')) >= 0) {
        const QByteArray line = m_buffer.left(newline);
        m_buffer.remove(0, newline + 1);
        if (line.trimmed().isEmpty()) continue;
        const QJsonDocument doc = QJsonDocument::fromJson(line);
        if (doc.isObject()) handleEvent(doc.object());
    }
}

void EngineClient::handleEvent(const QJsonObject &event) {
    const QString type = event.value("type").toString();

    if (type == "bootstrap") {
        // Says whether the bundled yt-dlp or a downloaded override is in use, and why
        // an override was rejected. Surfaced rather than swallowed: a silent fall back
        // to an older extractor is exactly the failure worth seeing.
        const QJsonObject yt = event.value("ytDlp").toObject();
        const QString failed = yt.value("failedReason").toString();
        if (!failed.isEmpty()) {
            m_status = QStringLiteral("yt-dlp override rejected: %1").arg(failed);
            emit statusChanged();
        }
        return;
    }

    if (type == "ready") {
        m_ready = true;
        m_status = QStringLiteral("ready");
        emit readyChanged();
        emit statusChanged();
        send({{"id", QStringLiteral("v")}, {"op", QStringLiteral("version")}});
        return;
    }

    if (type == "progress" && event.value("id").toString() == m_activeId) {
        if (event.contains("progressPercent")) {
            m_progress = event.value("progressPercent").toDouble();
            emit progressChanged();
        }
        const QString message = event.value("status").toString();
        if (!message.isEmpty() && message != m_status) {
            m_status = message;
            emit statusChanged();
        }
        return;
    }

    if (type == "ytDlpProgress") {
        m_ytDlpUpdateStatus = event.value("stage").toString();
        const double total = event.value("total").toDouble();
        m_ytDlpUpdateProgress = total > 0 ? event.value("done").toDouble() / total : 0;
        emit ytDlpUpdateChanged();
        return;
    }

    if (type == "result" && event.value("id").toString() == QLatin1String("ytdlp")) {
        m_updatingYtDlp = false;
        m_ytDlpUpdateProgress = 0;
        if (event.value("ok").toBool()) {
            const QJsonObject result = event.value("result").toObject();
            const QString status = result.value("status").toString();
            const QString version = result.value("version").toString();
            m_ytDlpUpdateStatus = status == QLatin1String("current")
                ? QStringLiteral("already on %1").arg(version)
                // Queued, not live: yt-dlp is already imported, so it lands on restart.
                : QStringLiteral("%1 installed — restart the engine").arg(version);
        } else {
            m_ytDlpUpdateStatus = event.value("error").toString();
        }
        emit ytDlpUpdateChanged();
        return;
    }

    if (type == "result") {
        const QString id = event.value("id").toString();
        if (id == QLatin1String("v")) {
            m_ytDlpVersion = event.value("result").toObject().value("ytDlp").toString();
            emit ytDlpVersionChanged();
            return;
        }
        if (id != m_activeId) return;

        const bool ok = event.value("ok").toBool();
        // The engine reports its own failures inside a successful call, so both layers
        // have to agree before this is called a success.
        const QJsonObject result = event.value("result").toObject();
        const bool engineOk = ok && result.value("success").toBool(true);
        const QString message = ok ? result.value("message").toString()
                                   : event.value("error").toString();

        m_activeId.clear();
        m_progress = engineOk ? 100.0 : 0.0;
        m_status = engineOk ? QStringLiteral("done") : QStringLiteral("failed");
        emit busyChanged();
        emit progressChanged();
        emit statusChanged();
        emit finished(engineOk, message,
                      engineOk ? result.value("file_path").toString() : QString(),
                      engineOk ? result.value("thumbnail_path").toString() : QString());
    }
}
