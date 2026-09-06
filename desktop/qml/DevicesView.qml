import QtQuick
import QtQuick.Controls.Basic
import QtQuick.Layouts
import QtQuick.Dialogs
import Arsivinyo

/**
 * Pairing and transfers.
 *
 * The screen is built around the one moment that matters — comparing six digits on two
 * screens — so that step is the largest thing on it and cannot be dismissed by accident.
 */
ColumnLayout {
    id: devices
    spacing: 12

    required property var service
    required property var registry
    required property var discovery
    required property var identity

    signal message(string text, bool ok)

    // ---- this device -------------------------------------------------------------
    Rectangle {
        Layout.fillWidth: true
        implicitHeight: 62
        radius: 10
        color: Theme.surface
        border.color: Theme.border

        RowLayout {
            anchors.fill: parent
            anchors.leftMargin: 14
            anchors.rightMargin: 14
            spacing: 12

            ColumnLayout {
                spacing: 2
                Layout.fillWidth: true
                Text {
                    text: devices.identity.deviceName
                    color: Theme.text
                    font.family: Fonts.body
                    font.pixelSize: 14
                }
                Text {
                    // Eight characters is enough to compare by eye and short enough to fit.
                    text: qsTr("This device · ") + devices.identity.shortFingerprint
                    color: Theme.textSubtle
                    font.family: Fonts.body
                    font.pixelSize: 11
                }
            }

            Text {
                text: devices.service.listening
                      ? qsTr("Visible on port ") + devices.service.port
                      : qsTr("Not visible")
                color: devices.service.listening ? Theme.success : Theme.textSubtle
                font.family: Fonts.body
                font.pixelSize: 11
            }
        }
    }

    // ---- the confirmation code ----------------------------------------------------
    Rectangle {
        Layout.fillWidth: true
        implicitHeight: 132
        radius: 12
        color: Theme.surface
        border.color: Theme.accent
        border.width: 2
        visible: devices.service.pendingCode.length > 0

        ColumnLayout {
            anchors.centerIn: parent
            spacing: 6

            Text {
                Layout.alignment: Qt.AlignHCenter
                text: qsTr("Does this match the other device?")
                color: Theme.textMuted
                font.family: Fonts.body
                font.pixelSize: 12
            }
            Text {
                Layout.alignment: Qt.AlignHCenter
                text: devices.service.pendingCode
                color: Theme.text
                font.family: Fonts.bodyBold
                font.pixelSize: 34
                font.letterSpacing: 6
            }
            RowLayout {
                Layout.alignment: Qt.AlignHCenter
                spacing: 18
                Text {
                    text: qsTr("They match")
                    color: Theme.accent
                    font.family: Fonts.body
                    font.pixelSize: 13
                    TapHandler {
                        onTapped: {
                            const name = devices.service.pendingName
                            if (devices.service.confirmPairing())
                                devices.message(qsTr("Paired with ") + name, true)
                        }
                    }
                }
                Text {
                    text: qsTr("They do not")
                    color: Theme.error
                    font.family: Fonts.body
                    font.pixelSize: 13
                    TapHandler { onTapped: devices.service.cancelPairing() }
                }
            }
        }
    }

    // ---- actions -------------------------------------------------------------------
    RowLayout {
        Layout.fillWidth: true
        spacing: 8

        Rectangle {
            implicitHeight: 32
            implicitWidth: pairLabel.implicitWidth + 26
            radius: 8
            color: devices.service.pairingMode ? Theme.surfaceHover : Theme.surface
            border.color: devices.service.pairingMode ? Theme.accent : Theme.border
            Text {
                id: pairLabel
                anchors.centerIn: parent
                text: devices.service.pairingMode ? qsTr("Waiting…") : qsTr("Add a device")
                color: devices.service.pairingMode ? Theme.accent : Theme.text
                font.family: Fonts.body
                font.pixelSize: 12
            }
            TapHandler {
                onTapped: devices.service.pairingMode
                          ? devices.service.cancelPairing()
                          : devices.service.beginPairing(120)
            }
        }

        Rectangle {
            implicitHeight: 32
            implicitWidth: scanLabel.implicitWidth + 26
            radius: 8
            color: Theme.surface
            border.color: Theme.border
            Text {
                id: scanLabel
                anchors.centerIn: parent
                text: qsTr("Look again")
                color: Theme.text
                font.family: Fonts.body
                font.pixelSize: 12
            }
            TapHandler { onTapped: devices.discovery.browse() }
        }

        Item { Layout.fillWidth: true }
    }

    // ---- paired devices --------------------------------------------------------------
    Text {
        text: qsTr("Paired")
        color: Theme.textSubtle
        font.family: Fonts.body
        font.pixelSize: 11
        visible: devices.registry.count > 0
    }

    Repeater {
        model: devices.registry
        Rectangle {
            required property string fingerprint
            required property string name
            required property string lastAddress

            Layout.fillWidth: true
            implicitHeight: 52
            radius: 10
            color: Theme.surface
            border.color: Theme.border

            RowLayout {
                anchors.fill: parent
                anchors.leftMargin: 14
                anchors.rightMargin: 14
                spacing: 10

                ColumnLayout {
                    spacing: 1
                    Layout.fillWidth: true
                    Text {
                        text: name.length ? name : qsTr("Unnamed device")
                        color: Theme.text
                        font.family: Fonts.body
                        font.pixelSize: 13
                    }
                    Text {
                        text: fingerprint.substring(0, 8)
                        color: Theme.textSubtle
                        font.family: Fonts.body
                        font.pixelSize: 10
                    }
                }

                Text {
                    text: devices.service.sessionFor(fingerprint) ? qsTr("Connected") : ""
                    color: Theme.success
                    font.family: Fonts.body
                    font.pixelSize: 11
                }

                Text {
                    text: qsTr("Send")
                    color: devices.service.sessionFor(fingerprint) ? Theme.accent : Theme.textSubtle
                    font.family: Fonts.body
                    font.pixelSize: 12
                    TapHandler {
                        onTapped: {
                            if (!devices.service.sessionFor(fingerprint)) return
                            sendDialog.target = fingerprint
                            sendDialog.open()
                        }
                    }
                }

                Text {
                    text: qsTr("Forget")
                    color: Theme.error
                    font.family: Fonts.body
                    font.pixelSize: 12
                    TapHandler {
                        onTapped: {
                            devices.registry.forget(fingerprint)
                            devices.message(qsTr("Device forgotten"), true)
                        }
                    }
                }
            }
        }
    }

    // ---- found on the network ---------------------------------------------------------
    Text {
        text: qsTr("On this network")
        color: Theme.textSubtle
        font.family: Fonts.body
        font.pixelSize: 11
        visible: devices.discovery.count > 0
    }

    Repeater {
        model: devices.discovery
        Rectangle {
            required property string fingerprint
            required property string name
            required property string host
            required property int port

            Layout.fillWidth: true
            implicitHeight: 46
            radius: 10
            color: "transparent"
            border.color: Theme.border

            RowLayout {
                anchors.fill: parent
                anchors.leftMargin: 14
                anchors.rightMargin: 14
                spacing: 10

                Text {
                    Layout.fillWidth: true
                    text: (name.length ? name : qsTr("Unnamed device")) + "  ·  " + fingerprint.substring(0, 8)
                    color: Theme.textMuted
                    font.family: Fonts.body
                    font.pixelSize: 12
                    elide: Text.ElideRight
                }

                Text {
                    text: qsTr("Pair")
                    color: Theme.accent
                    font.family: Fonts.body
                    font.pixelSize: 12
                    TapHandler {
                        onTapped: {
                            devices.service.beginPairing(120)
                            devices.service.connectToPeer(host, port)
                        }
                    }
                }
            }
        }
    }

    FileDialog {
        id: sendDialog
        property string target: ""
        title: qsTr("Send to device")
        onAccepted: {
            const session = devices.service.sessionFor(target)
            if (!session) return
            // A local file URL, not a path: the dialog speaks URLs.
            if (session.sendFile(devices.service.pathOf(selectedFile), "music"))
                devices.message(qsTr("Sending…"), true)
            else
                devices.message(qsTr("A transfer is already running"), false)
        }
    }

    Item { Layout.fillHeight: true }
}
