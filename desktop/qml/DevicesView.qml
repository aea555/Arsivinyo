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
 *
 * File names never appear here. A transfer shows a size and a percentage and which device
 * it is talking to, which is the same rule the phone's notifications follow.
 */
Item {
    id: devices

    required property var service
    required property var registry
    required property var discovery
    required property var identity

    signal message(string text, bool ok)

    /** Non-empty while looking at a peer's library instead of the device list. */
    property string browsingPeer: ""
    property string browsingName: ""
    property var browsedItems: []
    property bool awaitingListing: false

    function shortId(fingerprint) { return fingerprint.substring(0, 8) }

    function sizeLabel(bytes) {
        if (!bytes) return ""
        const mb = bytes / 1048576
        return mb >= 1024 ? (mb / 1024).toFixed(1) + " GB" : mb.toFixed(1) + " MB"
    }

    Connections {
        target: devices.service
        function onListingReceived(fingerprint, kind, items) {
            devices.awaitingListing = false
            devices.browsingPeer = fingerprint
            devices.browsedItems = items
        }
        function onTransferFinished(ok, reason) {
            devices.message(ok ? qsTr("Transfer complete")
                               : (reason.length ? reason : qsTr("Transfer failed")), ok)
        }
        function onPeerDisconnected(fingerprint) {
            if (devices.browsingPeer === fingerprint) devices.browsingPeer = ""
        }
    }

    ScrollView {
        id: scroller
        anchors.fill: parent
        contentWidth: availableWidth
        clip: true
        rightPadding: 16
        ScrollBar.vertical.policy: ScrollBar.AsNeeded

    ColumnLayout {
        width: scroller.availableWidth
        spacing: 12

        // ---- this device ---------------------------------------------------------
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

        // ---- the confirmation code ------------------------------------------------
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
                    TextAction {
                        text: qsTr("They match")
                        font.pixelSize: 13
                        onClicked: {
                            const name = devices.service.pendingName
                            if (devices.service.confirmPairing())
                                devices.message(qsTr("Paired with ") + name, true)
                        }
                    }
                    TextAction {
                        text: qsTr("They do not")
                        accentColor: Theme.error
                        font.pixelSize: 13
                        onClicked: devices.service.cancelPairing()
                    }
                }
            }
        }

        // ---- a transfer in flight ---------------------------------------------------
        Rectangle {
            Layout.fillWidth: true
            implicitHeight: 44
            radius: 10
            color: Theme.surface
            border.color: Theme.border
            visible: devices.service.transferring

            RowLayout {
                anchors.fill: parent
                anchors.leftMargin: 14
                anchors.rightMargin: 14
                spacing: 12

                Text {
                    // Which device and how much — never what the file is called.
                    text: qsTr("Transferring with ") + devices.shortId(devices.service.transferPeer)
                    color: Theme.textMuted
                    font.family: Fonts.body
                    font.pixelSize: 12
                }

                Rectangle {
                    Layout.fillWidth: true
                    implicitHeight: 4
                    radius: 2
                    color: Theme.surfaceHover
                    Rectangle {
                        width: parent.width * devices.service.transferFraction
                        height: parent.height
                        radius: parent.radius
                        color: Theme.accent
                        Behavior on width { NumberAnimation { duration: 120 } }
                    }
                }

                Text {
                    text: Math.round(devices.service.transferFraction * 100) + "%"
                    color: Theme.accent
                    font.family: Fonts.body
                    font.pixelSize: 12
                }
            }
        }

        // ---- actions ------------------------------------------------------------------
        RowLayout {
            Layout.fillWidth: true
            spacing: 8
            visible: devices.browsingPeer.length === 0

            Pressable {
                implicitHeight: 32
                implicitWidth: pairLabel.implicitWidth + 26
                radius: 8
                selected: devices.service.pairingMode
                baseColor: Theme.surface
                border.color: devices.service.pairingMode ? Theme.accent
                            : hovered ? Theme.borderSubtle : Theme.border
                Behavior on border.color { ColorAnimation { duration: 120 } }
                onClicked: devices.service.pairingMode
                           ? devices.service.cancelPairing()
                           : devices.service.beginPairing(120)
                Text {
                    id: pairLabel
                    anchors.centerIn: parent
                    text: devices.service.pairingMode ? qsTr("Waiting…") : qsTr("Add a device")
                    color: devices.service.pairingMode ? Theme.accent : Theme.text
                    font.family: Fonts.body
                    font.pixelSize: 12
                }
            }

            Pressable {
                implicitHeight: 32
                implicitWidth: scanLabel.implicitWidth + 26
                radius: 8
                baseColor: Theme.surface
                border.color: hovered ? Theme.borderSubtle : Theme.border
                Behavior on border.color { ColorAnimation { duration: 120 } }
                onClicked: devices.discovery.browse()
                Text {
                    id: scanLabel
                    anchors.centerIn: parent
                    text: qsTr("Look again")
                    color: Theme.text
                    font.family: Fonts.body
                    font.pixelSize: 12
                }
            }

            Item { Layout.fillWidth: true }
        }

        // ---- a peer's library ----------------------------------------------------------
        RowLayout {
            Layout.fillWidth: true
            spacing: 10
            visible: devices.browsingPeer.length > 0

            TextAction {
                text: qsTr("← Back")
                font.pixelSize: 12
                onClicked: devices.browsingPeer = ""
            }
            Text {
                Layout.fillWidth: true
                text: devices.browsingName + qsTr("'s library  ·  ") + devices.browsedItems.length
                color: Theme.textMuted
                font.family: Fonts.body
                font.pixelSize: 12
                elide: Text.ElideRight
            }
        }

        ListView {
            Layout.fillWidth: true
            Layout.preferredHeight: Math.min(contentHeight, 420)
            visible: devices.browsingPeer.length > 0
            interactive: false
            clip: true
            spacing: 4
            model: devices.browsedItems

            delegate: Rectangle {
                required property var modelData
                width: ListView.view.width
                implicitHeight: 46
                radius: 8
                color: Theme.surface
                border.color: Theme.border

                RowLayout {
                    anchors.fill: parent
                    anchors.leftMargin: 12
                    anchors.rightMargin: 12
                    spacing: 10

                    ColumnLayout {
                        spacing: 1
                        Layout.fillWidth: true
                        Text {
                            Layout.fillWidth: true
                            text: modelData.title
                            color: Theme.text
                            font.family: Fonts.body
                            font.pixelSize: 13
                            elide: Text.ElideRight
                        }
                        Text {
                            text: [modelData.artist, devices.sizeLabel(modelData.sizeBytes)]
                                  .filter(part => part && part.length).join("  ·  ")
                            color: Theme.textSubtle
                            font.family: Fonts.body
                            font.pixelSize: 10
                        }
                    }

                    TextAction {
                        text: qsTr("Get")
                        accentColor: devices.service.transferring ? Theme.textSubtle : Theme.accent
                        font.pixelSize: 12
                        onClicked: {
                            if (devices.service.transferring) return
                            const session = devices.service.sessionFor(devices.browsingPeer)
                            if (session && session.requestItem(modelData.id))
                                devices.message(qsTr("Requested"), true)
                        }
                    }
                }
            }
        }

        // ---- paired devices --------------------------------------------------------------
        Text {
            text: qsTr("Paired")
            color: Theme.textSubtle
            font.family: Fonts.body
            font.pixelSize: 11
            visible: devices.registry.count > 0 && devices.browsingPeer.length === 0
        }

        Repeater {
            model: devices.registry
            Rectangle {
                required property string fingerprint
                required property string name

                    Layout.fillWidth: true
                implicitHeight: 52
                radius: 10
                color: peerHover.hovered ? Theme.surfaceHover : Theme.surface
                Behavior on color { ColorAnimation { duration: 120 } }
                border.color: peerHover.hovered ? Theme.borderSubtle : Theme.border
                Behavior on border.color { ColorAnimation { duration: 120 } }
                HoverHandler { id: peerHover }
                visible: devices.browsingPeer.length === 0

                readonly property bool connected: devices.service.sessionFor(fingerprint) !== null

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
                            text: connected ? qsTr("Connected") : devices.shortId(fingerprint)
                            color: connected ? Theme.success : Theme.textSubtle
                            font.family: Fonts.body
                            font.pixelSize: 10
                        }
                    }

                    TextAction {
                        text: devices.awaitingListing ? qsTr("…") : qsTr("Browse")
                        active: connected
                        font.pixelSize: 12
                        onClicked: {
                            const session = devices.service.sessionFor(fingerprint)
                            if (!session) return
                            devices.browsingName = name
                            devices.awaitingListing = true
                            session.requestListing("music")
                        }
                    }

                    TextAction {
                        text: qsTr("Send")
                        active: connected
                        font.pixelSize: 12
                        onClicked: {
                            if (!connected) return
                            sendDialog.target = fingerprint
                            sendDialog.open()
                        }
                    }

                    TextAction {
                        text: qsTr("Link")
                        active: connected
                        font.pixelSize: 12
                        onClicked: {
                            if (!connected) return
                            linkSheet.target = fingerprint
                            linkSheet.targetName = name
                            linkField.text = ""
                            linkSheet.visible = true
                            linkField.forceActiveFocus()
                        }
                    }

                    TextAction {
                        text: qsTr("Forget")
                        accentColor: Theme.error
                        font.pixelSize: 12
                        onClicked: {
                            devices.registry.forget(fingerprint)
                            devices.message(qsTr("Device forgotten"), true)
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
            visible: devices.discovery.count > 0 && devices.browsingPeer.length === 0
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
                color: foundHover.hovered ? Theme.surface : "transparent"
                Behavior on color { ColorAnimation { duration: 120 } }
                border.color: foundHover.hovered ? Theme.borderSubtle : Theme.border
                Behavior on border.color { ColorAnimation { duration: 120 } }
                HoverHandler { id: foundHover }
                visible: devices.browsingPeer.length === 0

                RowLayout {
                    anchors.fill: parent
                    anchors.leftMargin: 14
                    anchors.rightMargin: 14
                    spacing: 10

                    Text {
                        Layout.fillWidth: true
                        text: (name.length ? name : qsTr("Unnamed device"))
                              + "  ·  " + devices.shortId(fingerprint)
                        color: Theme.textMuted
                        font.family: Fonts.body
                        font.pixelSize: 12
                        elide: Text.ElideRight
                    }

                    TextAction {
                        text: qsTr("Pair")
                        font.pixelSize: 12
                        onClicked: {
                            devices.service.beginPairing(120)
                            devices.service.connectToPeer(host, port)
                        }
                    }
                }
            }
        }

        Item { implicitHeight: 8 }
    }

    }

    // ---- send a link ---------------------------------------------------------------------
    Rectangle {
        id: linkSheet
        property string target: ""
        property string targetName: ""
        anchors.fill: parent
        color: Qt.rgba(0, 0, 0, 0.72)
        visible: false
        // Swallow taps so the list behind cannot be operated through the sheet.
        TapHandler { onTapped: linkSheet.visible = false }

        Rectangle {
            anchors.centerIn: parent
            width: Math.min(parent.width - 60, 420)
            implicitHeight: 150
            radius: 12
            color: Theme.surface
            border.color: Theme.border
            TapHandler {}  // keeps a tap inside the card from closing it

            ColumnLayout {
                anchors.fill: parent
                anchors.margins: 16
                spacing: 10

                Text {
                    text: qsTr("Send a link to ") + linkSheet.targetName
                    color: Theme.text
                    font.family: Fonts.body
                    font.pixelSize: 13
                }
                Text {
                    text: qsTr("The other device decides whether to download it.")
                    color: Theme.textSubtle
                    font.family: Fonts.body
                    font.pixelSize: 11
                }

                TextField {
                    id: linkField
                    Layout.fillWidth: true
                    placeholderText: "https://…"
                    color: Theme.text
                    font.family: Fonts.body
                    font.pixelSize: 12
                    background: Rectangle {
                        radius: 8
                        color: Theme.background
                        border.color: linkField.activeFocus ? Theme.accent : Theme.border
                    }
                }

                RowLayout {
                    Layout.alignment: Qt.AlignRight
                    spacing: 16
                    TextAction {
                        text: qsTr("Cancel")
                        accentColor: Theme.textMuted
                        font.pixelSize: 12
                        onClicked: linkSheet.visible = false
                    }
                    TextAction {
                        text: qsTr("Send")
                        active: linkField.text.length
                        font.pixelSize: 12
                        onClicked: {
                            if (!linkField.text.length) return
                            const session = devices.service.sessionFor(linkSheet.target)
                            if (session && session.requestDownload(linkField.text, "audio"))
                                devices.message(qsTr("Link sent"), true)
                            linkSheet.visible = false
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
            if (session.sendFile(devices.service.pathOf(selectedFile), "music"))
                devices.message(qsTr("Sending…"), true)
            else
                devices.message(qsTr("A transfer is already running"), false)
        }
    }
}
