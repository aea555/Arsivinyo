#pragma once

// Where the app keeps its own files.
//
// This was copied verbatim into four translation units before the security work needed a
// fifth. ARSIVINYO_DATA_DIR is honoured first so a test — or a second instance — can be
// pointed somewhere harmless.

#include <QDir>
#include <QStandardPaths>
#include <QString>

namespace arsivinyo {

inline QString dataDirPath(bool create = true) {
    QString dir = qEnvironmentVariable("ARSIVINYO_DATA_DIR");
    if (dir.isEmpty()) dir = QStandardPaths::writableLocation(QStandardPaths::AppDataLocation);
    if (dir.isEmpty()) dir = QDir::homePath() + "/.local/share/Arsivinyo";
    if (create) QDir().mkpath(dir);
    return dir;
}

}  // namespace arsivinyo
