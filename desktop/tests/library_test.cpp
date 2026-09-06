// Tests for the library store. Plain assertions over a temporary database, so they run
// without a display and without the QML engine.
#include "Library.h"

#include <QCoreApplication>
#include <QDir>
#include <QFile>
#include <QTemporaryDir>

#include <cstdio>

static int failures = 0;

#define CHECK(cond, what)                                                        \
    do {                                                                         \
        if (!(cond)) { std::printf("  FAIL  %s\n", what); ++failures; }          \
        else { std::printf("  ok    %s\n", what); }                              \
    } while (0)

int main(int argc, char **argv) {
    QCoreApplication app(argc, argv);

    QTemporaryDir music, data;
    qputenv("ARSIVINYO_MUSIC_DIR", music.path().toUtf8());
    qputenv("ARSIVINYO_DATA_DIR", data.path().toUtf8());

    // Two files that look like audio. Tags are absent, so titles fall back to the name,
    // which is itself worth checking.
    for (const char *name : {"Alpha.flac", "Beta.m4a"}) {
        QFile f(QDir(music.path()).filePath(name));
        f.open(QIODevice::WriteOnly);
        f.write("not really audio");
        f.close();
    }

    Library library;
    library.scan();
    CHECK(library.rowCount() == 2, "scan finds both audio files");

    const QString alpha = library.get(0).value("songId").toString();
    const QString beta = library.get(1).value("songId").toString();
    CHECK(!alpha.isEmpty() && alpha != beta, "each track has its own id");
    CHECK(!library.get(0).value("title").toString().isEmpty(),
          "an untagged file still gets a title");

    // ---- favourites ------------------------------------------------------
    CHECK(!library.isFavourite(alpha), "nothing is favourite to begin with");
    library.setFavourite(alpha, true);
    CHECK(library.isFavourite(alpha), "a track can be favourited");
    library.setFavourite(alpha, false);
    CHECK(!library.isFavourite(alpha), "and unfavourited");

    // ---- playlists -------------------------------------------------------
    const QVariantList initial = library.playlists();
    CHECK(initial.size() == 1 && initial.first().toMap().value("id") == "favorites",
          "Favourites exists from the start and is the only playlist");
    CHECK(initial.first().toMap().value("system").toBool(),
          "Favourites is marked as a system playlist");

    const QString mine = library.createPlaylist("  Road trip  ");
    CHECK(!mine.isEmpty(), "a playlist can be created");
    CHECK(library.playlists().size() == 2, "it shows up in the list");
    CHECK(library.createPlaylist("   ").isEmpty(), "a blank name is refused");

    library.addToPlaylist(mine, alpha);
    library.addToPlaylist(mine, alpha);   // twice on purpose
    for (const QVariant &entry : library.playlists()) {
        const QVariantMap row = entry.toMap();
        if (row.value("id").toString() != mine) continue;
        CHECK(row.value("count").toInt() == 1, "adding the same track twice counts once");
        CHECK(row.value("name").toString() == "Road trip", "the name is trimmed");
    }

    library.showPlaylist(mine);
    CHECK(library.rowCount() == 1, "showing a playlist restricts the list to its members");
    library.showPlaylist({});
    CHECK(library.rowCount() == 2, "clearing it shows everything again");

    library.removeFromPlaylist(mine, alpha);
    for (const QVariant &entry : library.playlists())
        if (entry.toMap().value("id").toString() == mine)
            CHECK(entry.toMap().value("count").toInt() == 0, "a track can be removed");

    CHECK(library.renamePlaylist(mine, "Commute"), "a playlist can be renamed");
    CHECK(!library.renamePlaylist("favorites", "Nope"), "Favourites cannot be renamed");
    CHECK(!library.deletePlaylist("favorites"), "Favourites cannot be deleted");
    CHECK(library.playlists().size() == 2, "the refusal did not delete it");
    CHECK(library.deletePlaylist(mine), "an ordinary playlist can be deleted");
    CHECK(library.playlists().size() == 1, "and is gone");

    // ---- reconcile -------------------------------------------------------
    library.addToPlaylist("favorites", beta);
    QFile::remove(QDir(music.path()).filePath("Beta.m4a"));
    library.scan();
    CHECK(library.rowCount() == 1, "a deleted file leaves the library on the next scan");
    for (const QVariant &entry : library.playlists())
        if (entry.toMap().value("id").toString() == "favorites")
            CHECK(entry.toMap().value("count").toInt() == 0,
                  "and its playlist membership goes with it");

    std::printf("\n%s\n", failures ? "FAILURES" : "all checks passed");
    return failures ? 1 : 0;
}
