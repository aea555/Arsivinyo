import QtQuick
import QtQuick.Controls.Basic
import QtQuick.Layouts
import Arsivinyo

/** The library list: search, artwork, favourite. */
Item {
    id: view
    property Library library
    property PresetRenderer renderer
    property string selectedId: ""
    signal play(string path, string title, string artist, string thumb)

    /** Used by the screenshot run; the UI itself plays on click. */
    function playFirst() {
        if (library.count === 0) return
        const row = library.get(0)
        view.selectedId = row.songId
        view.play(row.path, row.title, row.artist, row.thumb)
    }

    property string activePlaylist: ""

    function refreshPlaylists() { playlistModel.clear();
        const rows = library.playlists()
        for (let i = 0; i < rows.length; ++i) playlistModel.append(rows[i]) }

    ListModel { id: playlistModel }
    Component.onCompleted: refreshPlaylists()
    Connections {
        target: library
        function onPlaylistsChanged() { view.refreshPlaylists() }
    }

    RowLayout {
        anchors.fill: parent
        spacing: 16

    ColumnLayout {
        Layout.preferredWidth: 168
        Layout.fillHeight: true
        spacing: 6

        Text {
            text: qsTr("Playlists")
            color: Theme.textSubtle
            font.family: Fonts.body
            font.pixelSize: 11
        }

        Repeater {
            model: [{ id: "", name: qsTr("All tracks"), count: -1, system: false }]
            Rectangle {
                required property var modelData
                Layout.fillWidth: true
                implicitHeight: 30
                radius: 8
                color: view.activePlaylist === "" ? Theme.surfaceHover : "transparent"
                Text {
                    anchors.verticalCenter: parent.verticalCenter
                    x: 10
                    text: modelData.name
                    color: view.activePlaylist === "" ? Theme.text : Theme.textSubtle
                    font.family: Fonts.body
                    font.pixelSize: 12
                }
                TapHandler { onTapped: { view.activePlaylist = ""; library.showPlaylist("") } }
            }
        }

        Repeater {
            model: playlistModel
            Rectangle {
                required property string id
                required property string name
                required property int count
                required property bool system
                Layout.fillWidth: true
                implicitHeight: 30
                radius: 8
                color: view.activePlaylist === id ? Theme.surfaceHover
                     : plHover.hovered ? Qt.darker(Theme.surface, 1.1) : "transparent"
                RowLayout {
                    anchors.fill: parent
                    anchors.leftMargin: 10
                    anchors.rightMargin: 8
                    spacing: 6
                    Text {
                        Layout.fillWidth: true
                        text: name
                        color: view.activePlaylist === id ? Theme.text : Theme.textSubtle
                        font.family: Fonts.body
                        font.pixelSize: 12
                        elide: Text.ElideRight
                    }
                    Text {
                        text: count
                        color: Theme.textSubtle
                        font.family: Fonts.body
                        font.pixelSize: 11
                    }
                }
                HoverHandler { id: plHover }
                TapHandler {
                    onTapped: { view.activePlaylist = id; library.showPlaylist(id) }
                }
                TapHandler {
                    acceptedButtons: Qt.RightButton
                    onTapped: if (!system) plMenu.popup()
                }
                Menu {
                    id: plMenu
                    MenuItem { text: qsTr("Delete"); onTriggered: library.deletePlaylist(id) }
                }
            }
        }

        Rectangle {
            Layout.fillWidth: true
            implicitHeight: 30
            radius: 8
            color: newHover.hovered ? Theme.surfaceHover : "transparent"
            Text {
                anchors.verticalCenter: parent.verticalCenter
                x: 10
                text: qsTr("+ New playlist")
                color: Theme.accent
                font.family: Fonts.body
                font.pixelSize: 12
            }
            HoverHandler { id: newHover }
            TapHandler { onTapped: newDialog.open() }
        }

        Item { Layout.fillHeight: true }
    }

    ColumnLayout {
        Layout.fillWidth: true
        Layout.fillHeight: true
        spacing: 14

        RowLayout {
            Layout.fillWidth: true
            spacing: 12

            Rectangle {
                Layout.fillWidth: true
                implicitHeight: 38
                radius: 10
                color: Theme.surface
                border.color: search.activeFocus ? Theme.accent : Theme.border
                Behavior on border.color { ColorAnimation { duration: 120 } }
                TextField {
                    id: search
                    anchors.fill: parent
                    leftPadding: 14
                    verticalAlignment: TextInput.AlignVCenter
                    placeholderText: qsTr("Search")
                    color: Theme.text
                    placeholderTextColor: Theme.textSubtle
                    font.family: Fonts.body
                    font.pixelSize: 13
                    background: null
                    selectByMouse: true
                    selectionColor: Theme.accent
                    selectedTextColor: Theme.background
                    onTextChanged: view.library.filter = text
                }
            }
            Text {
                text: view.library.count + (view.library.count === 1 ? qsTr(" track") : qsTr(" tracks"))
                color: Theme.textSubtle
                font.family: Fonts.body
                font.pixelSize: 12
            }
        }

        ListView {
            id: list
            Layout.fillWidth: true
            Layout.fillHeight: true
            clip: true
            spacing: 4
            model: view.library
            boundsBehavior: Flickable.StopAtBounds
            ScrollBar.vertical: ScrollBar { policy: ScrollBar.AsNeeded }

            delegate: Rectangle {
                id: row
                required property string songId
                required property string title
                required property string artist
                required property string path
                required property real durationSec
                required property string thumb
                required property bool favourite
                required property string presetId

                width: list.width
                height: 60
                radius: 10
                color: view.selectedId === songId ? Theme.surfaceHover
                     : rowHover.hovered ? Qt.darker(Theme.surface, 1.1) : "transparent"
                Behavior on color { ColorAnimation { duration: 100 } }

                RowLayout {
                    anchors.fill: parent
                    anchors.leftMargin: 10
                    anchors.rightMargin: 10
                    spacing: 12

                    Rectangle {
                        width: 44; height: 44
                        radius: 6
                        color: Theme.surfaceHover
                        clip: true
                        Image {
                            anchors.fill: parent
                            source: row.thumb
                            fillMode: Image.PreserveAspectCrop
                            asynchronous: true
                            visible: row.thumb.length > 0
                        }
                        // Stands in when a track has no cover, rather than a blank square.
                        Text {
                            anchors.centerIn: parent
                            visible: row.thumb.length === 0
                            text: row.title.length ? row.title.charAt(0).toUpperCase() : "?"
                            color: Theme.textSubtle
                            font.family: Fonts.bodyBold
                            font.pixelSize: 17
                        }
                    }

                    ColumnLayout {
                        Layout.fillWidth: true
                        spacing: 2
                        Text {
                            Layout.fillWidth: true
                            text: row.title
                            color: Theme.text
                            font.family: Fonts.body
                            font.pixelSize: 14
                            elide: Text.ElideRight
                        }
                        Text {
                            Layout.fillWidth: true
                            text: row.artist.length ? row.artist : qsTr("Unknown artist")
                            color: Theme.textSubtle
                            font.family: Fonts.body
                            font.pixelSize: 12
                            elide: Text.ElideRight
                        }
                    }

                    Text {
                        visible: row.presetId.length > 0
                        text: row.presetId
                        color: Theme.accent
                        font.family: Fonts.body
                        font.pixelSize: 10
                    }

                    Text {
                        text: {
                            const total = Math.round(row.durationSec)
                            const m = Math.floor(total / 60), s = total % 60
                            return m + ":" + (s < 10 ? "0" : "") + s
                        }
                        color: Theme.textSubtle
                        font.family: Fonts.body
                        font.pixelSize: 12
                    }

                    // Presets, offered per track like the phone's long-press action.
                    Rectangle {
                        implicitHeight: 24
                        implicitWidth: 24
                        radius: 6
                        color: wandHover.hovered ? Theme.surfaceHover : "transparent"
                        visible: !view.renderer.busy
                        Text {
                            anchors.centerIn: parent
                            text: "\u2726"
                            color: Theme.textSubtle
                            font.pixelSize: 14
                        }
                        HoverHandler { id: wandHover }
                        TapHandler { onTapped: presetMenu.popup() }

                        Menu {
                            id: presetMenu
                            MenuItem {
                                text: qsTr("Add to playlist…")
                                onTriggered: addMenu.popup()
                            }
                            MenuSeparator {}
                            Repeater {
                                model: view.renderer.builtInPresets()
                                MenuItem {
                                    required property var modelData
                                    text: modelData.name
                                    onTriggered: view.renderer.render(row.path, row.title,
                                                                     row.artist, modelData.id,
                                                                     view.library.musicDir)
                                }
                            }
                        }

                        Menu {
                            id: addMenu
                            Repeater {
                                model: playlistModel
                                MenuItem {
                                    required property string id
                                    required property string name
                                    text: name
                                    onTriggered: view.library.addToPlaylist(id, row.songId)
                                }
                            }
                        }
                    }

                    // Heart, drawn rather than imported.
                    Item {
                        width: 22; height: 22
                        Canvas {
                            anchors.fill: parent
                            onPaint: {
                                const ctx = getContext("2d")
                                ctx.reset()
                                ctx.beginPath()
                                ctx.moveTo(11, 19)
                                ctx.bezierCurveTo(2, 13, 2, 6, 6.5, 5)
                                ctx.bezierCurveTo(9, 4.4, 11, 6.5, 11, 8)
                                ctx.bezierCurveTo(11, 6.5, 13, 4.4, 15.5, 5)
                                ctx.bezierCurveTo(20, 6, 20, 13, 11, 19)
                                ctx.closePath()
                                if (row.favourite) { ctx.fillStyle = Theme.accent; ctx.fill() }
                                else { ctx.strokeStyle = Theme.textSubtle; ctx.lineWidth = 1.6; ctx.stroke() }
                            }
                            Connections {
                                target: row
                                function onFavouriteChanged() { parent.requestPaint() }
                            }
                        }
                        TapHandler {
                            onTapped: view.library.setFavourite(row.songId, !row.favourite)
                        }
                    }
                }

                HoverHandler { id: rowHover }
                TapHandler {
                    onTapped: {
                        view.selectedId = row.songId
                        view.play(row.path, row.title, row.artist, row.thumb)
                    }
                }
            }

            Text {
                anchors.centerIn: parent
                visible: list.count === 0
                text: view.library.filter.length ? qsTr("Nothing matches") : qsTr("Nothing here yet")
                color: Theme.textSubtle
                font.family: Fonts.body
                font.pixelSize: 13
            }
        }
    }
    }

    Dialog {
        id: newDialog
        anchors.centerIn: parent
        title: qsTr("New playlist")
        modal: true
        standardButtons: Dialog.Ok | Dialog.Cancel
        onAccepted: { library.createPlaylist(nameField.text); nameField.text = "" }
        TextField {
            id: nameField
            width: 240
            placeholderText: qsTr("Name")
            color: Theme.text
            font.family: Fonts.body
            onAccepted: newDialog.accept()
        }
    }
}
