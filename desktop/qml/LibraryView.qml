import QtQuick
import QtQuick.Controls.Basic
import QtQuick.Layouts
import Arsivinyo

/** The library list: search, artwork, favourite. */
Item {
    id: view
    property Library library
    property string selectedId: ""
    signal play(string path, string title)

    ColumnLayout {
        anchors.fill: parent
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
                        view.play(row.path, row.title)
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
