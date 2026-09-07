import QtQuick
import QtQuick.Controls.Basic
import QtQuick.Dialogs
import QtQuick.Layouts
import QtMultimedia

/**
 * The vault: private files, encrypted at rest, listed only when unlocked.
 *
 * Playback runs through a decrypting device rather than a file on disk, so nothing here is
 * ever written out in the clear.
 */
Item {
    id: view

    required property var vault
    required property var player
    required property var secrets

    property string message: ""

    FileDialog {
        id: addDialog
        title: qsTr("Add to the vault")
        nameFilters: [qsTr("Media (*.mp4 *.mkv *.mov *.webm *.m4a *.mp3 *.flac)"), qsTr("Any file (*)")]
        onAccepted: view.message = view.vault.importFile(selectedFile, false)
    }

    FileDialog {
        id: exportDialog
        property string itemId: ""
        title: qsTr("Save a copy")
        fileMode: FileDialog.SaveFile
        onAccepted: view.message = view.vault.exportTo(itemId, selectedFile)
    }

    ColumnLayout {
        anchors.fill: parent
        anchors.leftMargin: 30
        anchors.rightMargin: 30
        spacing: 14

        // ---- locked ------------------------------------------------------------
        Item {
            visible: !view.vault.unlocked
            Layout.fillWidth: true
            Layout.fillHeight: true

            ColumnLayout {
                anchors.centerIn: parent
                width: Math.min(parent.width, 380)
                spacing: 12

                Text {
                    Layout.alignment: Qt.AlignHCenter
                    text: qsTr("Locked")
                    color: Theme.text
                    font.family: Fonts.body
                    font.pixelSize: 16
                }
                Text {
                    Layout.fillWidth: true
                    horizontalAlignment: Text.AlignHCenter
                    text: view.secrets.configured
                          ? qsTr("Everything here is encrypted. Unlock to see what is in it.")
                          : qsTr("Set a passphrase to start a vault. Files you add are encrypted with it, and so is the list of what they are.")
                    color: Theme.textSubtle
                    font.family: Fonts.body
                    font.pixelSize: 12
                    wrapMode: Text.WordWrap
                }
                TextAction {
                    Layout.alignment: Qt.AlignHCenter
                    text: view.secrets.configured ? qsTr("Unlock") : qsTr("Set a passphrase")
                    font.pixelSize: 13
                    onClicked: view.secrets.requestUnlock(qsTr("to open the vault"))
                }
            }
        }

        // ---- an index that will not open ----------------------------------------
        Item {
            visible: view.vault.unlocked && view.vault.unreadable
            Layout.fillWidth: true
            Layout.fillHeight: true

            ColumnLayout {
                anchors.centerIn: parent
                width: Math.min(parent.width, 420)
                spacing: 12

                Text {
                    Layout.alignment: Qt.AlignHCenter
                    text: qsTr("The listing could not be read")
                    color: Theme.error
                    font.family: Fonts.body
                    font.pixelSize: 16
                }
                Text {
                    Layout.fillWidth: true
                    horizontalAlignment: Text.AlignHCenter
                    // Said plainly, because the instinct on seeing an empty vault is to add
                    // to it, and that is the one thing that would make this permanent.
                    text: qsTr("Your files are still here and still encrypted. Nothing has been deleted, and nothing will be written until this can be read again.")
                    color: Theme.textSubtle
                    font.family: Fonts.body
                    font.pixelSize: 12
                    wrapMode: Text.WordWrap
                }
            }
        }

        // ---- the player ----------------------------------------------------------
        Rectangle {
            visible: view.player.active
            Layout.fillWidth: true
            Layout.preferredHeight: 300
            radius: 12
            color: "black"
            border.color: Theme.border

            VideoOutput {
                id: output
                anchors.fill: parent
                anchors.margins: 1
                Component.onCompleted: view.player.videoSink = output.videoSink
            }

            RowLayout {
                anchors.left: parent.left
                anchors.right: parent.right
                anchors.bottom: parent.bottom
                anchors.margins: 12
                spacing: 12

                TextAction {
                    text: view.player.playing ? qsTr("Pause") : qsTr("Play")
                    font.pixelSize: 12
                    onClicked: view.player.togglePause()
                }
                Slider {
                    Layout.fillWidth: true
                    from: 0
                    to: Math.max(1, view.player.duration)
                    value: view.player.position
                    onMoved: view.player.seek(value)
                }
                Text {
                    text: {
                        const seconds = Math.floor(view.player.position / 1000)
                        const total = Math.floor(view.player.duration / 1000)
                        const format = (s) => Math.floor(s / 60) + ":" + ("0" + (s % 60)).slice(-2)
                        return format(seconds) + " / " + format(total)
                    }
                    color: Theme.textMuted
                    font.family: Fonts.body
                    font.pixelSize: 11
                }
                TextAction {
                    text: qsTr("Close")
                    accentColor: Theme.textMuted
                    font.pixelSize: 12
                    onClicked: view.player.stop()
                }
            }
        }

        // ---- the list -------------------------------------------------------------
        RowLayout {
            visible: view.vault.unlocked && !view.vault.unreadable
            Layout.fillWidth: true
            spacing: 12
            Text {
                text: view.vault.count === 1 ? qsTr("1 item") : qsTr("%1 items").arg(view.vault.count)
                color: Theme.textSubtle
                font.family: Fonts.body
                font.pixelSize: 11
            }
            Item { Layout.fillWidth: true }
            TextAction {
                text: qsTr("Add a file")
                font.pixelSize: 12
                onClicked: addDialog.open()
            }
            TextAction {
                text: qsTr("Lock")
                accentColor: Theme.textMuted
                font.pixelSize: 12
                onClicked: { view.player.stop(); view.secrets.lock() }
            }
        }

        Text {
            visible: view.message.length > 0
            Layout.fillWidth: true
            text: view.message
            color: Theme.error
            font.family: Fonts.body
            font.pixelSize: 11
            wrapMode: Text.WordWrap
        }

        ListView {
            id: list
            visible: view.vault.unlocked && !view.vault.unreadable
            Layout.fillWidth: true
            Layout.fillHeight: true
            clip: true
            spacing: 6
            model: view.vault
            // No scrollbars anywhere in this app.
            boundsBehavior: Flickable.StopAtBounds

            delegate: Pressable {
                id: row
                required property string itemId
                required property string title
                required property double sizeBytes
                width: list.width
                implicitHeight: 54
                radius: 10
                baseColor: Theme.surface
                selected: view.player.currentId === itemId
                border.color: view.player.currentId === itemId ? Theme.accent
                            : hovered ? Theme.borderSubtle : Theme.border
                Behavior on border.color { ColorAnimation { duration: 120 } }
                onClicked: view.player.play(itemId)

                RowLayout {
                    anchors.fill: parent
                    anchors.leftMargin: 14
                    anchors.rightMargin: 14
                    spacing: 12

                    ColumnLayout {
                        spacing: 2
                        Text {
                            text: row.title
                            color: Theme.text
                            font.family: Fonts.body
                            font.pixelSize: 13
                            elide: Text.ElideRight
                        }
                        Text {
                            text: {
                                const bytes = row.sizeBytes
                                if (bytes >= 1024 * 1024 * 1024)
                                    return (bytes / (1024 * 1024 * 1024)).toFixed(1) + " GB"
                                if (bytes >= 1024 * 1024)
                                    return (bytes / (1024 * 1024)).toFixed(1) + " MB"
                                return Math.max(1, Math.round(bytes / 1024)) + " KB"
                            }
                            color: Theme.textSubtle
                            font.family: Fonts.body
                            font.pixelSize: 11
                        }
                    }
                    Item { Layout.fillWidth: true }
                    TextAction {
                        text: qsTr("Save a copy")
                        accentColor: Theme.textMuted
                        font.pixelSize: 12
                        onClicked: {
                            exportDialog.itemId = row.itemId
                            exportDialog.selectedFile = "file://" + row.title
                            exportDialog.open()
                        }
                    }
                    TextAction {
                        text: qsTr("Delete")
                        accentColor: Theme.error
                        font.pixelSize: 12
                        onClicked: {
                            confirmDelete.itemId = row.itemId
                            confirmDelete.itemTitle = row.title
                            confirmDelete.open()
                        }
                    }
                }
            }
        }
    }

    Modal {
        id: confirmDelete
        anchors.fill: parent
        property string itemId: ""
        property string itemTitle: ""
        title: qsTr("Delete from the vault?")
        cardWidth: 420

        Text {
            Layout.fillWidth: true
            text: qsTr("\"%1\" is removed for good. There is no copy anywhere else.").arg(confirmDelete.itemTitle)
            color: Theme.textMuted
            font.family: Fonts.body
            font.pixelSize: 12
            wrapMode: Text.WordWrap
        }
        RowLayout {
            Layout.alignment: Qt.AlignRight
            spacing: 18
            TextAction {
                text: qsTr("Cancel")
                accentColor: Theme.textMuted
                font.pixelSize: 13
                onClicked: confirmDelete.close()
            }
            TextAction {
                text: qsTr("Delete")
                accentColor: Theme.error
                font.pixelSize: 13
                onClicked: {
                    if (view.player.currentId === confirmDelete.itemId) view.player.stop()
                    view.message = view.vault.remove(confirmDelete.itemId)
                    confirmDelete.close()
                }
            }
        }
    }
}
