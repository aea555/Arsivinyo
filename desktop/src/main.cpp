#include <QGuiApplication>
#include <QImage>
#include <QQmlApplicationEngine>
#include <QQuickStyle>
#include <QQmlError>
#include <QQuickWindow>
#include <QTimer>

int main(int argc, char *argv[]) {
    // Ask for the desktop's own file dialog before the application is constructed, which
    // is when Qt resolves the platform theme. Left alone, Qt falls back to its GTK3
    // helper and opens the old GTK chooser: light-themed whatever the desktop is set to,
    // and not the dialog anything else on the system shows.
    //
    // Only when nothing has been chosen already, so an explicit setting still wins, and
    // only where a portal exists to answer — the theme falls back by itself if not.
    if (qEnvironmentVariableIsEmpty("QT_QPA_PLATFORMTHEME") &&
        !qEnvironmentVariableIsEmpty("XDG_CURRENT_DESKTOP")) {
        qputenv("QT_QPA_PLATFORMTHEME", "xdgdesktopportal");
    }

    QGuiApplication app(argc, argv);
    app.setApplicationName(QStringLiteral("Arsivinyo"));
    app.setOrganizationName(QStringLiteral("Arsivinyo"));

    // Basic, not the platform style: the look is ours to define, the same way the phone
    // app does not inherit a system theme.
    QQuickStyle::setStyle(QStringLiteral("Basic"));

    QQmlApplicationEngine engine;
    // Print QML errors before exiting. Without this a missing import fails the load
    // silently and the process just returns 1, which is a poor way to find a typo.
    QObject::connect(&engine, &QQmlApplicationEngine::warnings, &app,
                     [](const QList<QQmlError> &warnings) {
                         for (const QQmlError &error : warnings)
                             qWarning("QML: %s", qPrintable(error.toString()));
                     });
    QObject::connect(&engine, &QQmlApplicationEngine::objectCreationFailed, &app,
                     []() {
                         qWarning("QML: root object could not be created");
                         QCoreApplication::exit(1);
                     }, Qt::QueuedConnection);
    engine.loadFromModule("Arsivinyo", "Main");

#ifdef ARSIVINYO_DEV_TOOLS
    // Development aid, compiled out of a normal build: grab the window to a file and
    // exit. External capture tools depend on the compositor; asking the toolkit for its
    // own frame works the same under X11 and Wayland.
    const QByteArray shotPath = qgetenv("ARSIVINYO_SCREENSHOT");
    if (!shotPath.isEmpty() && !engine.rootObjects().isEmpty()) {
        if (auto *window = qobject_cast<QQuickWindow *>(engine.rootObjects().first())) {
            QTimer::singleShot(2500, window, [window, shotPath]() {
                window->grabWindow().save(QString::fromUtf8(shotPath));
                QCoreApplication::quit();
            });
        }
    }
#endif

    return app.exec();
}
