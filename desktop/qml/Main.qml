import QtQuick
import QtQuick.Controls.Basic
import QtQuick.Layouts
import Arsivinyo

ApplicationWindow {
    id: root
    width: 760
    height: 620
    minimumWidth: 520
    minimumHeight: 560
    visible: true
    title: qsTr("Arsivinyo")
    color: Theme.background

    property string pendingUrl: ""
    property string resultMessage: ""
    property bool resultOk: true

    EngineClient {
        id: engine
        onFinished: (ok, message) => {
            root.resultOk = ok
            root.resultMessage = ok ? qsTr("Saved") : (message.length ? message : qsTr("Download failed"))
            clearResult.restart()
        }
    }
    Library { id: library }

    Component.onCompleted: { engine.start(); library.scan() }

    Timer { id: clearResult; interval: 4000; onTriggered: root.resultMessage = "" }

    // The clipboard is the input, so it is polled while idle — the same way the phone
    // reads it on tap, except the desktop can show what it found before you commit.
    Timer {
        interval: 700
        running: engine.ready && !engine.busy
        repeat: true
        triggeredOnStart: true
        onTriggered: root.pendingUrl = engine.clipboardUrl()
    }

    ColumnLayout {
        anchors.fill: parent
        anchors.margins: 32
        spacing: 0

        RowLayout {
            Layout.fillWidth: true
            Text {
                text: "ARSIVINYO"
                color: Theme.text
                font.family: Fonts.brand
                font.pixelSize: 20
            }
            Item { Layout.fillWidth: true }
            Rectangle {
                implicitHeight: 24
                implicitWidth: badge.implicitWidth + 20
                radius: 12
                color: Theme.surface
                border.color: Theme.border
                Text {
                    id: badge
                    anchors.centerIn: parent
                    text: engine.ytDlpVersion ? "yt-dlp " + engine.ytDlpVersion
                                              : (engine.ready ? qsTr("engine ready") : qsTr("starting…"))
                    color: engine.ready ? Theme.textMuted : Theme.textSubtle
                    font.family: Fonts.body
                    font.pixelSize: 11
                }
            }
        }

        Item { Layout.fillHeight: true }

        DownloadButton {
            Layout.alignment: Qt.AlignHCenter
            state_: engine.busy ? "busy"
                                : (root.resultMessage.length ? (root.resultOk ? "done" : "error") : "idle")
            progress: engine.progress
            caption: engine.busy ? qsTr("Cancel")
                                 : (root.resultMessage.length ? root.resultMessage : qsTr("Download"))
            subCaption: engine.busy
                ? engine.status + (engine.progress > 0 ? " · " + Math.round(engine.progress) + "%" : "")
                : (root.pendingUrl.length ? engine.hostOf(root.pendingUrl)
                                          : qsTr("Copy a link, then click"))
            enabled: engine.ready
            opacity: engine.ready ? 1 : 0.5
            onActivated: {
                if (engine.busy) { engine.cancel(); return }
                if (!root.pendingUrl.length) return
                root.resultMessage = ""
                engine.download(root.pendingUrl, engine.downloadDir, root.audioMode)
            }
        }

        Item { height: 22 }

        // Secondary to the button, the way it is on the phone.
        Rectangle {
            Layout.alignment: Qt.AlignHCenter
            implicitHeight: 36
            implicitWidth: 190
            radius: 10
            color: Theme.surface
            border.color: Theme.border
            opacity: engine.busy ? 0.4 : 1

            Rectangle {
                width: parent.width / 2 - 4
                height: parent.height - 8
                x: root.audioMode ? parent.width / 2 + 2 : 4
                y: 4
                radius: 8
                color: Theme.surfaceHover
                Behavior on x { NumberAnimation { duration: 140; easing.type: Easing.OutCubic } }
            }
            RowLayout {
                anchors.fill: parent
                spacing: 0
                Repeater {
                    model: [qsTr("Video"), qsTr("Audio")]
                    Text {
                        required property int index
                        required property string modelData
                        Layout.fillWidth: true
                        Layout.fillHeight: true
                        text: modelData
                        color: (index === 1) === root.audioMode ? Theme.text : Theme.textSubtle
                        font.family: Fonts.body
                        font.pixelSize: 13
                        horizontalAlignment: Text.AlignHCenter
                        verticalAlignment: Text.AlignVCenter
                        TapHandler { onTapped: if (!engine.busy) root.audioMode = (index === 1) }
                    }
                }
            }
        }

        Item { Layout.fillHeight: true }

        Text {
            Layout.fillWidth: true
            text: engine.downloadDir
            color: Theme.textSubtle
            font.family: Fonts.body
            font.pixelSize: 11
            elide: Text.ElideMiddle
            horizontalAlignment: Text.AlignHCenter
        }
    }

    property bool audioMode: false
}
