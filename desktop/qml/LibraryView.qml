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
        // preferredWidth alone left the sidebar taking half the window. Pinning the
        // maximum as well is what actually holds it, since the column's children ask to
        // fill and a RowLayout will grow a child to satisfy that.
        Layout.preferredWidth: 168
        Layout.maximumWidth: 168
        Layout.fillWidth: false
        Layout.fillHeight: true
        spacing: 6

        Item {
            Layout.fillWidth: true
            // Matches the search field opposite, so both columns start on the same line.
            implicitHeight: 38
            Text {
                anchors.left: parent.left
                anchors.verticalCenter: parent.verticalCenter
                text: qsTr("Playlists")
                color: Theme.textSubtle
                font.family: Fonts.body
                font.pixelSize: 11
            }
        }

        Repeater {
            model: [{ id: "", name: qsTr("All tracks"), count: -1, system: false }]
            Pressable {
                required property var modelData
                Layout.fillWidth: true
                implicitHeight: 30
                radius: 8
                selected: view.activePlaylist === ""
                onClicked: { view.activePlaylist = ""; library.showPlaylist("") }
                Text {
                    anchors.verticalCenter: parent.verticalCenter
                    x: 10
                    text: modelData.name
                    color: view.activePlaylist === "" ? Theme.text : Theme.textSubtle
                    font.family: Fonts.body
                    font.pixelSize: 12
                }
            }
        }

        Repeater {
            model: playlistModel
            Pressable {
                required property string id
                required property string name
                required property int count
                required property bool system
                Layout.fillWidth: true
                implicitHeight: 30
                radius: 8
                selected: view.activePlaylist === id
                hoverColor: Qt.alpha(Theme.surfaceHover, 0.6)
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
                onClicked: { view.activePlaylist = id; library.showPlaylist(id) }
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

        Pressable {
            Layout.fillWidth: true
            implicitHeight: 30
            radius: 8
            onClicked: newDialog.open()
            Text {
                anchors.verticalCenter: parent.verticalCenter
                x: 10
                text: qsTr("+ New playlist")
                color: parent.hovered ? Qt.lighter(Theme.accent, 1.2) : Theme.accent
                Behavior on color { ColorAnimation { duration: 110 } }
                font.family: Fonts.body
                font.pixelSize: 12
            }
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
                    HoverHandler { cursorShape: Qt.IBeamCursor }
                    selectionColor: Theme.accent
                    selectedTextColor: Theme.background
                    onTextChanged: view.library.filter = text
                }
            }
            Text {
                // Without this the RowLayout stretches the label to the search field's
                // height and the text draws at the top of it, sitting a row above centre.
                Layout.alignment: Qt.AlignVCenter
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

            delegate: Pressable {
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
                selected: view.selectedId === songId
                hoverColor: Qt.alpha(Theme.surfaceHover, 0.55)

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
                        HoverHandler { cursorShape: Qt.PointingHandCursor }
                        TapHandler {
                            onTapped: view.library.setFavourite(row.songId, !row.favourite)
                        }
                    }
                }

                onClicked: {
                    view.selectedId = row.songId
                    view.play(row.path, row.title, row.artist, row.thumb)
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
