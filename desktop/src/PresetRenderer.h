#pragma once

#include <QObject>
#include <QQmlEngine>
#include <QString>
#include <QThread>

/**
 * Applies an audio preset to a library track.
 *
 * The signal processing is `shared/dsp`, compiled straight into this binary — the same
 * resampler, Freeverb, shelving EQ and lookahead limiter the phone runs. Android reaches
 * it through `audio_presets_jni.cpp`; here it is an ordinary function call, so the two
 * apps produce the same audio from the same code rather than two implementations that
 * drift.
 *
 * The source track is never modified. A render is a new file, so it stays undoable by
 * deleting the result.
 */
class PresetRenderer : public QObject {
    Q_OBJECT
    QML_ELEMENT

    Q_PROPERTY(bool busy READ busy NOTIFY busyChanged)
    Q_PROPERTY(double progress READ progress NOTIFY progressChanged)
    Q_PROPERTY(QString currentTitle READ currentTitle NOTIFY currentTitleChanged)

public:
    explicit PresetRenderer(QObject *parent = nullptr);
    ~PresetRenderer() override;

    bool busy() const { return m_busy; }
    double progress() const { return m_progress; }
    QString currentTitle() const { return m_currentTitle; }

    /** The presets that ship, mirroring mobile/src/features/audioPresets/core.ts. */
    Q_INVOKABLE QVariantList builtInPresets() const;

    /**
     * Render `sourcePath` with `presetId` and write the result beside it.
     * @return false when a render is already running or the preset is unknown.
     */
    Q_INVOKABLE bool render(const QString &sourcePath, const QString &title,
                            const QString &artist, const QString &presetId,
                            const QString &outputDir);
    Q_INVOKABLE void cancel();

signals:
    void busyChanged();
    void progressChanged();
    void currentTitleChanged();
    void finished(bool ok, const QString &outputPath, const QString &error);

private:
    void watchProgress();

    QThread *m_worker = nullptr;
    QString m_progressFile;
    QString m_cancelFile;
    QString m_currentTitle;
    double m_progress = 0;
    bool m_busy = false;
};
