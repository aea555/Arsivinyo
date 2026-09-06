#include <QGuiApplication>
#include <QImage>
#include <QQmlApplicationEngine>
#include <QQuickStyle>
#include <QQuickWindow>
#include <QTimer>

int main(int argc, char *argv[]) {
    QGuiApplication app(argc, argv);
    app.setApplicationName(QStringLiteral("Arsivinyo"));
    app.setOrganizationName(QStringLiteral("Arsivinyo"));

    // Basic, not the platform style: the look is ours to define, the same way the phone
    // app does not inherit a system theme.
    QQuickStyle::setStyle(QStringLiteral("Basic"));

    QQmlApplicationEngine engine;
    QObject::connect(&engine, &QQmlApplicationEngine::objectCreationFailed, &app,
                     []() { QCoreApplication::exit(1); }, Qt::QueuedConnection);
    engine.loadFromModule("Arsivinyo", "Main");

    // Development aid, off unless asked for: grab the window to a file and exit. External
    // capture tools depend on the compositor, and asking the toolkit for its own frame
    // works the same under X11 and Wayland.
    const QByteArray shotPath = qgetenv("ARSIVINYO_SCREENSHOT");
    if (!shotPath.isEmpty() && !engine.rootObjects().isEmpty()) {
        if (auto *window = qobject_cast<QQuickWindow *>(engine.rootObjects().first())) {
            QTimer::singleShot(2500, window, [window, shotPath]() {
                window->grabWindow().save(QString::fromUtf8(shotPath));
                QCoreApplication::quit();
            });
        }
    }

    return app.exec();
}
