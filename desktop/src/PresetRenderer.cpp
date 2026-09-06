#include "PresetRenderer.h"

#include <QCoreApplication>
#include <QDir>
#include <QFile>
#include <QFileInfo>
#include <QJsonDocument>
#include <QJsonObject>
#include <QStandardPaths>
#include <QTimer>
#include <QVariantMap>
#include <QtConcurrent>

#include "ffmpeg_pipe.h"

namespace {

struct Preset {
    const char *id;
    const char *name;
    const char *suffix;
    const char *spec;
};

// Copied from mobile/src/features/audioPresets/core.ts. The values are the contract
// between the two apps: the same preset must sound the same on both.
const Preset kPresets[] = {
    {"slowed-reverb", "Slowed + Reverb", " (Slowed + Reverb)",
     "rate=0.85;reverbMix=0.28;reverbRoom=0.72;reverbDamp=0.42;reverbWidth=1;"
     "reverbPreDelayMs=20;bassGainDb=2;bassFreqHz=120"},
    {"nightcore", "Nightcore", " (Nightcore)",
     "rate=1.25;reverbMix=0.06;reverbRoom=0.4;reverbDamp=0.5;trebleGainDb=1.5"},
    {"bass-boost", "Bass Boost", " (Bass Boost)",
     "bassGainDb=6;bassFreqHz=90;outputGainDb=-1"},
};

QString toolPath(const QString &name) {
    const QDir dir(QCoreApplication::applicationDirPath());
    const QFileInfo local(dir.filePath(name));
    if (local.isExecutable()) return local.absoluteFilePath();
    return QStandardPaths::findExecutable(name);
}

QString sanitise(QString name) {
    return name.replace(QRegularExpression(R"([/\\:*?"<>|\x00-\x1f])"), "_").trimmed();
}

}  // namespace

PresetRenderer::PresetRenderer(QObject *parent) : QObject(parent) {}

PresetRenderer::~PresetRenderer() {
    cancel();
    if (m_worker) m_worker->wait(5000);
}

QVariantList PresetRenderer::builtInPresets() const {
    QVariantList out;
    for (const Preset &preset : kPresets) {
        out.append(QVariantMap{{"id", QString::fromUtf8(preset.id)},
                               {"name", QString::fromUtf8(preset.name)},
                               {"suffix", QString::fromUtf8(preset.suffix)}});
    }
    return out;
}

bool PresetRenderer::render(const QString &sourcePath, const QString &title,
                            const QString &artist, const QString &presetId,
                            const QString &outputDir) {
    if (m_busy) return false;

    const Preset *chosen = nullptr;
    for (const Preset &preset : kPresets)
        if (presetId == QLatin1String(preset.id)) { chosen = &preset; break; }
    if (!chosen) return false;

    const QString ffmpeg = toolPath("ffmpeg");
    const QString ffprobe = toolPath("ffprobe");
    if (ffmpeg.isEmpty() || ffprobe.isEmpty()) {
        emit finished(false, {}, tr("ffmpeg is not available"));
        return false;
    }

    // A lossless source keeps a lossless result; a lossy one would only lose more.
    const QString suffix = QFileInfo(sourcePath).suffix().toLower();
    const bool lossless = suffix == "flac" || suffix == "wav" || suffix == "aiff" || suffix == "alac";
    const QString outFormat = lossless ? "flac" : "m4a";

    const QString renderedTitle = title + QString::fromUtf8(chosen->suffix);
    QString outPath = QDir(outputDir).filePath(sanitise(renderedTitle) + "." + outFormat);
    for (int n = 1; QFileInfo::exists(outPath); ++n)
        outPath = QDir(outputDir).filePath(sanitise(renderedTitle) + QStringLiteral(" (%1).").arg(n) + outFormat);

    const QString work = QDir::tempPath() + "/arsivinyo-render-" +
                         QString::number(QDateTime::currentMSecsSinceEpoch());
    QDir().mkpath(work);
    m_progressFile = work + "/progress.json";
    m_cancelFile = work + "/cancel.flag";

    m_busy = true;
    m_progress = 0;
    m_currentTitle = renderedTitle;
    emit busyChanged();
    emit progressChanged();
    emit currentTitleChanged();

    arsivinyo::audio::RenderRequest request;
    request.ffmpegPath = ffmpeg.toStdString();
    request.ffprobePath = ffprobe.toStdString();
    request.inputPath = sourcePath.toStdString();
    request.outputPath = outPath.toStdString();
    request.paramsSpec = chosen->spec;
    request.outputFormat = outFormat.toStdString();
    request.title = renderedTitle.toStdString();
    request.artist = artist.toStdString();
    request.progressFilePath = m_progressFile.toStdString();
    request.cancelFlagPath = m_cancelFile.toStdString();

    watchProgress();

    // Off the GUI thread: RenderPreset blocks for the length of the render, driving two
    // ffmpeg children through pipes.
    auto *watcher = new QFutureWatcher<QPair<bool, QString>>(this);
    connect(watcher, &QFutureWatcherBase::finished, this, [this, watcher, outPath, work]() {
        const auto result = watcher->result();
        m_busy = false;
        m_progress = result.first ? 100.0 : 0.0;
        emit busyChanged();
        emit progressChanged();
        QDir(work).removeRecursively();
        emit finished(result.first, result.first ? outPath : QString(), result.second);
        watcher->deleteLater();
    });
    watcher->setFuture(QtConcurrent::run([request]() {
        std::string error;
        const bool ok = arsivinyo::audio::RenderPreset(request, &error);
        return QPair<bool, QString>{ok, QString::fromStdString(error)};
    }));
    return true;
}

void PresetRenderer::watchProgress() {
    auto *timer = new QTimer(this);
    timer->setInterval(250);
    connect(timer, &QTimer::timeout, this, [this, timer]() {
        if (!m_busy) { timer->stop(); timer->deleteLater(); return; }
        QFile file(m_progressFile);
        if (!file.open(QIODevice::ReadOnly)) return;
        const QJsonObject object = QJsonDocument::fromJson(file.readAll()).object();
        const double percent = object.value("percent").toDouble(-1);
        if (percent >= 0 && !qFuzzyCompare(percent, m_progress)) {
            m_progress = percent;
            emit progressChanged();
        }
    });
    timer->start();
}

void PresetRenderer::cancel() {
    if (!m_busy || m_cancelFile.isEmpty()) return;
    // The render polls for this file and kills both ffmpeg children when it appears.
    QFile flag(m_cancelFile);
    flag.open(QIODevice::WriteOnly);
    flag.close();
}
