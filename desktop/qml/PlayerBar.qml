import QtQuick
import QtQuick.Controls.Basic
import QtQuick.Layouts
import QtQuick.Shapes
import QtMultimedia
import Arsivinyo

/** Transport for the currently playing track. Hidden until something is loaded. */
Rectangle {
    id: bar
    property string trackTitle: ""
    property string trackArtist: ""
    property string artwork: ""

    function playFile(path, title, artist, thumb) {
        bar.trackTitle = title
        bar.trackArtist = artist
        bar.artwork = thumb
        player.source = "file://" + path
        player.play()
    }

    implicitHeight: 72
    radius: 12
    color: Theme.surface
    border.color: Theme.border
    visible: player.source.toString().length > 0

    MediaPlayer {
        id: player
        audioOutput: AudioOutput { id: out; volume: 0.9 }
    }

    RowLayout {
        anchors.fill: parent
        anchors.leftMargin: 14
        anchors.rightMargin: 18
        spacing: 14

        Rectangle {
            width: 44; height: 44
            radius: 6
            color: Theme.surfaceHover
            clip: true
            Image {
                anchors.fill: parent
                source: bar.artwork
                fillMode: Image.PreserveAspectCrop
                asynchronous: true
                visible: bar.artwork.length > 0
            }
        }

        // Play / pause, drawn.
        Item {
            width: 34; height: 34
            Shape {
                anchors.fill: parent
                visible: player.playbackState !== MediaPlayer.PlayingState
                ShapePath {
                    fillColor: Theme.accent
                    strokeColor: "transparent"
                    startX: 11; startY: 8
                    PathLine { x: 27; y: 17 }
                    PathLine { x: 11; y: 26 }
                    PathLine { x: 11; y: 8 }
                }
            }
            Row {
                anchors.centerIn: parent
                spacing: 5
                visible: player.playbackState === MediaPlayer.PlayingState
                Repeater {
                    model: 2
                    Rectangle { width: 4; height: 18; radius: 1; color: Theme.accent }
                }
            }
            HoverHandler { id: playHover; cursorShape: Qt.PointingHandCursor }
            TapHandler {
                id: playTap
                onTapped: player.playbackState === MediaPlayer.PlayingState ? player.pause() : player.play()
            }
            scale: playTap.pressed ? 0.93 : playHover.hovered ? 1.06 : 1
            Behavior on scale { NumberAnimation { duration: 110; easing.type: Easing.OutQuad } }
        }

        ColumnLayout {
            Layout.fillWidth: true
            spacing: 4
            RowLayout {
                Layout.fillWidth: true
                spacing: 8
                Text {
                    text: bar.trackTitle
                    color: Theme.text
                    font.family: Fonts.body
                    font.pixelSize: 13
                    elide: Text.ElideRight
                    Layout.maximumWidth: 320
                }
                Text {
                    visible: bar.trackArtist.length > 0
                    text: "· " + bar.trackArtist
                    color: Theme.textSubtle
                    font.family: Fonts.body
                    font.pixelSize: 12
                    elide: Text.ElideRight
                }
                Item { Layout.fillWidth: true }
                Text {
                    function clock(ms) {
                        const t = Math.max(0, Math.round(ms / 1000))
                        const m = Math.floor(t / 60), s = t % 60
                        return m + ":" + (s < 10 ? "0" : "") + s
                    }
                    text: clock(player.position) + " / " + clock(player.duration)
                    color: Theme.textSubtle
                    font.family: Fonts.body
                    font.pixelSize: 11
                }
            }

            // Seek bar. Dragging scrubs; the fill follows position otherwise.
            Rectangle {
                id: track
                Layout.fillWidth: true
                implicitHeight: seekHover.hovered ? 8 : 5
                Behavior on implicitHeight { NumberAnimation { duration: 110 } }
                radius: height / 2
                color: Theme.surfaceHover
                HoverHandler { id: seekHover; cursorShape: Qt.PointingHandCursor }

                Rectangle {
                    width: player.duration > 0 ? track.width * (player.position / player.duration) : 0
                    height: parent.height
                    radius: parent.radius
                    color: seekHover.hovered ? Theme.accentHover : Theme.accent
                    Behavior on color { ColorAnimation { duration: 110 } }
                }
                TapHandler {
                    onTapped: (point) => {
                        if (player.duration > 0)
                            player.position = player.duration * (point.position.x / track.width)
                    }
                }
                DragHandler {
                    target: null
                    onCentroidChanged: {
                        if (active && player.duration > 0)
                            player.position = player.duration *
                                Math.max(0, Math.min(1, centroid.position.x / track.width))
                    }
                }
            }
        }
    }
}
