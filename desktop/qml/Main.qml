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
    Behavior on color { ColorAnimation { duration: 160 } }

    property string pendingUrl: ""
    property string resultMessage: ""
    property bool resultOk: true

    EngineClient {
        id: engine
        onFinished: (ok, message, filePath, thumbnailPath) => {
            root.resultOk = ok
            if (ok && filePath.length) {
                // Audio goes into the library; a video just stays where it landed.
                const named = library.adopt(filePath, thumbnailPath)
                root.resultMessage = named.length ? qsTr("Added to library") : qsTr("Saved")
            } else {
                root.resultMessage = ok ? qsTr("Saved") : (message.length ? message : qsTr("Download failed"))
            }
            clearResult.restart()
        }
    }
    Library { id: library }
    CookieStore { id: cookies }

    // Pairing. The identity is a singleton — one keypair per install — while the peer
    // list, the transport and what a peer may reach are ordinary objects wired together
    // here, where the Library and the engine already exist.
    PeerRegistry { id: peers }
    Discovery { id: discovery }
    LibraryContent {
        id: shared
        library: library
        engine: engine
        onDownloadRequested: (url, mediaKind) => {
            // A peer asking this device to fetch something is shown, never started
            // silently: the URL goes into the download field for the user to accept.
            root.pendingUrl = url
            root.audioMode = mediaKind === "audio"
            root.tab = 0
            root.resultOk = true
            root.resultMessage = qsTr("A device sent a link")
            clearResult.restart()
        }
    }
    PairingService {
        id: pairing
        identity: DeviceIdentity
        registry: peers
        content: shared
        onRefused: (reason) => {
            root.resultOk = false
            root.resultMessage = reason
            clearResult.restart()
        }
        onPeerConnected: (fingerprint, name) => {
            root.resultOk = true
            root.resultMessage = name + qsTr(" connected")
            clearResult.restart()
        }
    }

    PresetRenderer {
        id: renderer
        onFinished: (ok, outputPath, error) => {
            root.resultOk = ok
            root.resultMessage = ok ? qsTr("Rendered") : (error.length ? error : qsTr("Render failed"))
            if (ok) library.scan()
            clearResult.restart()
        }
    }

    Component.onCompleted: {
        // Set before the engine starts, so the first download already has them.
        engine.setCookiesDir(cookies.directory)
        engine.start()
        library.scan()
        // 0 asks the system for a free port, which is then advertised over mDNS. A fixed
        // port would collide with a second copy of the app on the same machine.
        if (pairing.listen(0))
            discovery.start(DeviceIdentity.fingerprint, DeviceIdentity.deviceName, pairing.port)
    }

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
        anchors.margins: 36
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
                visible: engine.busy || engine.ytDlpVersion.length > 0
                implicitHeight: 24
                // Capped: an engine error is a sentence, not a version string.
                implicitWidth: Math.min(badge.implicitWidth, 320) + 20
                radius: 12
                color: Theme.surface
                border.color: Theme.border
                Text {
                    id: badge
                    anchors.centerIn: parent
                    // A failure has to be visible. Reading only `ready` made a missing
                    // engine look identical to one still starting, so the app sat on
                    // "starting…" forever while knowing exactly what was wrong.
                    text: engine.busy ? engine.status
                          : engine.ytDlpVersion ? "yt-dlp " + engine.ytDlpVersion
                          : ""
                    color: Theme.textMuted
                    elide: Text.ElideRight
                    width: Math.min(implicitWidth, 320)
                    font.family: Fonts.body
                    font.pixelSize: 11
                }
            }
        }

        Item { height: 24 }

        // Two views rather than a sidebar: the app does two things, and a sidebar for
        // two entries is furniture.
        RowLayout {
            Layout.alignment: Qt.AlignHCenter
            spacing: 4
            Repeater {
                model: [qsTr("Download"), qsTr("Library"), qsTr("Devices"), qsTr("Settings")]
                Pressable {
                    required property int index
                    required property string modelData
                    implicitHeight: 30
                    implicitWidth: tabLabel.implicitWidth + 28
                    radius: 8
                    selected: root.tab === index
                    onClicked: root.tab = index
                    Text {
                        id: tabLabel
                        anchors.centerIn: parent
                        text: modelData + (index === 1 && library.count ? "  " + library.count : "")
                        color: root.tab === index ? Theme.text
                             : parent.hovered ? Theme.textMuted
                             : Theme.textSubtle
                        Behavior on color { ColorAnimation { duration: 120 } }
                        font.family: Fonts.body
                        font.pixelSize: 13
                    }
                }
            }
        }

        Item { height: 26 }

        StackLayout {
            Layout.fillWidth: true
            Layout.fillHeight: true
            currentIndex: root.tab

            // Wrapped in an Item whose layout is anchored, rather than a bare
            // ColumnLayout: a StackLayout leaves its child at its implicit width, so the
            // column was only as wide as the button and centring inside it did nothing —
            // the button sat against the left edge. LibraryView and DevicesView already
            // have this shape, which is why they fill correctly.
            Item {
                ColumnLayout {
                    anchors.fill: parent
                    spacing: 0
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
                    Pressable {
                        required property int index
                        required property string modelData
                        Layout.fillWidth: true
                        Layout.fillHeight: true
                        // Only the outer corners are round. Two fully rounded pills meeting
                        // in the middle leave a notch between them.
                        radius: 0
                        topLeftRadius: index === 0 ? 8 : 0
                        bottomLeftRadius: index === 0 ? 8 : 0
                        topRightRadius: index === 1 ? 8 : 0
                        bottomRightRadius: index === 1 ? 8 : 0
                        selected: (index === 1) === root.audioMode
                        interactive: !engine.busy
                        onClicked: root.audioMode = (index === 1)
                        Text {
                            anchors.fill: parent
                            text: modelData
                            color: (index === 1) === root.audioMode ? Theme.text
                                 : parent.hovered ? Theme.textMuted
                                 : Theme.textSubtle
                            Behavior on color { ColorAnimation { duration: 120 } }
                            font.family: Fonts.body
                            font.pixelSize: 13
                            horizontalAlignment: Text.AlignHCenter
                            verticalAlignment: Text.AlignVCenter
                        }
                    }
                }
            }
                }
                    Item { Layout.fillHeight: true }
                }
            }

            LibraryView {
                id: libraryView
                library: library
                renderer: renderer
                onPlay: (path, title, artist, thumb) => playerBar.playFile(path, title, artist, thumb)
            }

            DevicesView {
                service: pairing
                registry: peers
                discovery: discovery
                identity: DeviceIdentity
                onMessage: (text, ok) => {
                    root.resultOk = ok
                    root.resultMessage = text
                    clearResult.restart()
                }
            }

            // Last, because a StackLayout's child order is what `currentIndex` selects and
            // the tab labels above are read in the same order.
            SettingsView {
                engine: engine
                library: library
                cookies: cookies
            }
        }

        Item { height: 14 }

        Rectangle {
            Layout.fillWidth: true
            implicitHeight: 40
            radius: 10
            color: Theme.surface
            border.color: Theme.border
            visible: renderer.busy
            RowLayout {
                anchors.fill: parent
                anchors.leftMargin: 14
                anchors.rightMargin: 14
                spacing: 12
                Text {
                    text: qsTr("Rendering ") + renderer.currentTitle
                    color: Theme.textMuted
                    font.family: Fonts.body
                    font.pixelSize: 12
                    elide: Text.ElideRight
                    Layout.fillWidth: true
                }
                Text {
                    text: Math.round(renderer.progress) + "%"
                    color: Theme.accent
                    font.family: Fonts.body
                    font.pixelSize: 12
                }
                Text {
                    text: qsTr("Cancel")
                    color: Theme.error
                    font.family: Fonts.body
                    font.pixelSize: 12
                    TapHandler { onTapped: renderer.cancel() }
                }
            }
        }

        PlayerBar {
            id: playerBar
            Layout.fillWidth: true
        }

        Text {
            Layout.fillWidth: true
            text: root.tab === 0 ? engine.downloadDir
                  : root.tab === 1 ? library.musicDir
                  : root.tab === 2 ? (pairing.listening ? qsTr("Listening on port ") + pairing.port
                                                        : qsTr("Not listening"))
                  : ""
            color: Theme.textSubtle
            font.family: Fonts.body
            font.pixelSize: 11
            elide: Text.ElideMiddle
            horizontalAlignment: Text.AlignHCenter
        }
    }

    property bool audioMode: false
    property int tab: 0
}
