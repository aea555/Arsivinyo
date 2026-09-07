// What a paired peer may reach, against the real library rather than a stand-in.
//
// The transport tests use a folder-backed stub, which is the right shape for exercising
// framing and integrity but proves nothing about this class. The two functions here that
// matter are the ones a peer can push on: the id it may ask for, and the name it may send.
#include "LibraryContent.h"

#include <QCoreApplication>
#include <QDir>
#include <QFile>
#include <QJsonArray>
#include <QJsonObject>
#include <QTemporaryDir>

#include <cstdio>

static int failures = 0;
static void check(bool ok, const char *what) {
    std::printf(ok ? "  ok    %s\n" : "  FAIL  %s\n", what);
    if (!ok) ++failures;
}

int main(int argc, char **argv) {
    QCoreApplication app(argc, argv);

    QTemporaryDir music, data;
    qputenv("ARSIVINYO_MUSIC_DIR", music.path().toUtf8());
    qputenv("ARSIVINYO_DATA_DIR", data.path().toUtf8());

    for (const char *name : {"Alpha.flac", "Beta.m4a"}) {
        QFile file(QDir(music.path()).filePath(name));
        file.open(QIODevice::WriteOnly);
        file.write("not really audio");
        file.close();
    }

    Library library;
    library.scan();
    LibraryContent content(&library, nullptr);

    // ---- listing ---------------------------------------------------------------------
    const QJsonArray items = content.listing(QStringLiteral("music"));
    check(items.size() == 2, "the listing names both tracks");

    QString firstId;
    bool allIdentified = items.size() > 0;
    for (const QJsonValue &value : items) {
        const QString id = value.toObject().value("id").toString();
        if (id.isEmpty()) allIdentified = false;
        if (firstId.isEmpty()) firstId = id;
    }
    // The library model calls this key "songId" while the wire calls it "id". Getting
    // that wrong leaves a peer looking at a list of things it cannot ask for.
    check(allIdentified, "every listed item carries an id");

    check(content.listing(QStringLiteral("backups")).isEmpty(),
          "backups are not offered for browsing");
    check(content.listing(QStringLiteral("nonsense")).isEmpty(), "nor is anything else");

    // ---- resolving an id -------------------------------------------------------------
    const QString path = content.pathForItem(firstId);
    check(!path.isEmpty() && QFile::exists(path), "a listed id resolves to its file");
    check(path.startsWith(music.path()), "inside the music folder");

    check(content.pathForItem(QString()).isEmpty(), "an empty id resolves to nothing");
    check(content.pathForItem(QStringLiteral("no-such-track")).isEmpty(),
          "an unknown id resolves to nothing");
    // The library happens to use the file name as a track's id, which is why the
    // traversal cases below matter and why they are safe: an id is *matched against the
    // library's rows*, never joined onto a directory, and the only rows that exist are
    // files found by scanning the music folder.
    check(content.pathForItem(QStringLiteral("../../../etc/passwd")).isEmpty(),
          "a path dressed up as an id matches no row");
    check(content.pathForItem(QStringLiteral("/etc/passwd")).isEmpty(),
          "and neither does an absolute one");
    check(content.pathForItem(QStringLiteral("Alpha.flac")) ==
              QDir(music.path()).filePath(QStringLiteral("Alpha.flac")),
          "while a real track resolves to exactly its own file");

    // ---- where an incoming file may land ----------------------------------------------
    const QDir musicDir(music.path());
    auto landsInMusic = [&](const QString &name) {
        const QString destination = content.destinationFor(name, QStringLiteral("music"));
        return !destination.isEmpty() &&
               QDir(QFileInfo(destination).absolutePath()).canonicalPath() ==
                   musicDir.canonicalPath();
    };

    check(landsInMusic(QStringLiteral("track.m4a")), "an ordinary name lands in the library");
    check(landsInMusic(QStringLiteral("../../../etc/passwd")),
          "a name climbing out of the folder is brought back into it");
    check(landsInMusic(QStringLiteral("/etc/passwd")), "so is an absolute one");
    check(landsInMusic(QStringLiteral("sub/dir/track.m4a")), "and one with directories in it");
    check(landsInMusic(QStringLiteral("back\\slash\\track.m4a")),
          "including the separator the other platform uses");

    check(content.destinationFor(QString(), QStringLiteral("music")).isEmpty(),
          "an empty name is refused");
    check(content.destinationFor(QStringLiteral("..."), QStringLiteral("music")).isEmpty(),
          "and so is a name that is only dots");
    check(content.destinationFor(QStringLiteral("   "), QStringLiteral("music")).isEmpty(),
          "and one that is only spaces");

    // ---- an existing file is never overwritten -----------------------------------------
    {
        const QString first = content.destinationFor(QStringLiteral("Alpha.flac"),
                                                     QStringLiteral("music"));
        check(!first.isEmpty() && !QFile::exists(first),
              "a name already in the library is given a free one instead");
        check(QFileInfo(first).fileName() != QLatin1String("Alpha.flac"),
              "so the existing track is not replaced");
        check(QFileInfo(first).suffix() == QLatin1String("flac"),
              "keeping the extension, since that is how the format is read back");
    }

    std::printf("\n%s\n", failures ? "FAILURES" : "all checks passed");
    return failures ? 1 : 0;
}
