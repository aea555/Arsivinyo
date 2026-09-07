import QtQuick
import QtQuick.Controls.Basic
import QtQuick.Dialogs
import QtQuick.Layouts
import Arsivinyo

/**
 * Settings, arranged as the phone arranges them: titled sections of labelled rows.
 *
 * The desktop had none, so appearance lived as a popup wedged into the header next to a
 * permanent error badge. Everything that is a preference or a status belongs here, which
 * is also what gets the header back to a title and a row of tabs.
 */
Item {
    id: settings

    required property var engine
    required property var library
    required property var cookies
    required property var secrets

    /** Version to install once confirmed; empty string means the newest. */
    property string pendingVersion: ""
    property string pendingLabel: ""

    property string securityError: ""

    /** Which folder the dialog is choosing for: "downloads" or "music". */
    property string folderKey: ""

    function pickFolder(key, current) {
        folderKey = key
        folderDialog.currentFolder = "file://" + current
        folderDialog.open()
    }

    /** Which site an imported cookie file is for, and why the last import was refused. */
    property string cookiePlatform: ""
    property string cookieError: ""

    function pickCookies(platform) {
        cookiePlatform = platform
        cookieError = ""
        cookieDialog.open()
    }

    FileDialog {
        id: cookieDialog
        title: qsTr("Choose a cookies.txt")
        nameFilters: [qsTr("Cookie files (*.txt *.json)"), qsTr("All files (*)")]
        onAccepted: {
            // A bad file is refused here rather than at download time, where it would look
            // like the site rejecting you.
            settings.cookieError = settings.cookies.importFile(settings.cookiePlatform, selectedFile)
        }
    }

    FolderDialog {
        id: folderDialog
        title: qsTr("Choose a folder")
        onAccepted: {
            if (settings.folderKey === "downloads") settings.engine.setDownloadDir(selectedFolder)
            else settings.library.setMusicDir(selectedFolder)
        }
    }

    /** Changing the extractor needs a restart either way, so it is always confirmed. */
    function confirmYtDlp(version, label) {
        pendingVersion = version
        pendingLabel = label
        confirmDialog.visible = true
    }

    ScrollView {
        id: scroller
        anchors.fill: parent
        contentWidth: availableWidth
        clip: true
        // No visible bar: it was drawn over the content and had to be dodged with a
        // gutter, which cost width on every screen to display something the wheel
        // already does. Scrolling still works, it just leaves no mark.
        ScrollBar.vertical.policy: ScrollBar.AlwaysOff

        ColumnLayout {
            width: scroller.availableWidth
            spacing: 26

            // ---- appearance --------------------------------------------------------
            ColumnLayout {
                Layout.fillWidth: true
                spacing: 10

                Text {
                    text: qsTr("Appearance")
                    color: Theme.textSubtle
                    font.family: Fonts.body
                    font.pixelSize: 11
                }

                RowLayout {
                    Layout.fillWidth: true
                    spacing: 8
                    Repeater {
                        model: [{ id: "dark", label: qsTr("Dark") }, { id: "light", label: qsTr("Light") }]
                        Pressable {
                            required property var modelData
                            implicitHeight: 34
                            implicitWidth: 96
                            radius: 9
                            selected: Theme.mode === modelData.id
                            baseColor: Theme.surface
                            border.color: Theme.mode === modelData.id ? Theme.accent
                                        : hovered ? Theme.borderSubtle : Theme.border
                            Behavior on border.color { ColorAnimation { duration: 120 } }
                            onClicked: {
                                // Each mode has its own variants, so keep the current one
                                // only if the mode being switched to actually has it.
                                const list = modelData.id === "dark" ? Theme.darkVariants
                                                                     : Theme.lightVariants
                                const keep = list.find(v => v.id === Theme.variant)
                                Theme.apply(modelData.id, keep ? keep.id : list[0].id)
                            }
                            Text {
                                anchors.centerIn: parent
                                text: modelData.label
                                color: Theme.mode === modelData.id ? Theme.text : Theme.textMuted
                                font.family: Fonts.body
                                font.pixelSize: 13
                            }
                        }
                    }
                    Item { Layout.fillWidth: true }
                }

                // A grid rather than a list: thirteen palettes down one column is a lot of
                // scrolling for something you choose by eye.
                GridLayout {
                    Layout.fillWidth: true
                    columns: Math.max(2, Math.floor(settings.width / 190))
                    columnSpacing: 8
                    rowSpacing: 8

                    Repeater {
                        model: Theme.variants
                        Pressable {
                            required property var modelData
                            // Not `palette`: Item already has one in Qt 6, and the
                            // shadowed name silently resolved to the system's instead, so
                            // every preview drew the same colours.
                            readonly property var swatches: Theme.palettes[Theme.mode + "." + modelData.id]
                            Layout.fillWidth: true
                            implicitHeight: 46
                            radius: 10
                            selected: Theme.variant === modelData.id
                            baseColor: Theme.surface
                            border.width: Theme.variant === modelData.id ? 2 : 1
                            border.color: Theme.variant === modelData.id ? Theme.accent
                                        : hovered ? Theme.borderSubtle : Theme.border
                            Behavior on border.color { ColorAnimation { duration: 120 } }
                            onClicked: Theme.apply(Theme.mode, modelData.id)

                            RowLayout {
                                anchors.fill: parent
                                anchors.leftMargin: 12
                                anchors.rightMargin: 12
                                spacing: 10

                                // The palette previews itself, so the name is a label and
                                // not the only thing to go on.
                                Row {
                                    spacing: 4
                                    Repeater {
                                        model: [swatches.accent, swatches.text, swatches.surfaceActive]
                                        Rectangle {
                                            required property color modelData
                                            width: 12; height: 12; radius: 6
                                            color: modelData
                                            border.color: Theme.border
                                        }
                                    }
                                }
                                Text {
                                    Layout.fillWidth: true
                                    text: modelData.label
                                    color: Theme.variant === modelData.id ? Theme.text : Theme.textMuted
                                    font.family: Fonts.body
                                    font.pixelSize: 13
                                    elide: Text.ElideRight
                                }
                            }
                        }
                    }
                }
            }

            // ---- folders -----------------------------------------------------------
            ColumnLayout {
                Layout.fillWidth: true
                spacing: 10

                Text {
                    text: qsTr("Folders")
                    color: Theme.textSubtle
                    font.family: Fonts.body
                    font.pixelSize: 11
                }

                Repeater {
                    model: [
                        { key: "downloads", label: qsTr("Downloads"), value: settings.engine.downloadDir },
                        { key: "music", label: qsTr("Music"), value: settings.library.musicDir },
                    ]
                    Pressable {
                        id: folderRow
                        required property var modelData
                        Layout.fillWidth: true
                        implicitHeight: 54
                        radius: 10
                        baseColor: Theme.surface
                        border.color: hovered ? Theme.borderSubtle : Theme.border
                        Behavior on border.color { ColorAnimation { duration: 120 } }
                        onClicked: settings.pickFolder(modelData.key, modelData.value)

                        RowLayout {
                            anchors.fill: parent
                            anchors.leftMargin: 14
                            anchors.rightMargin: 14
                            spacing: 12

                            ColumnLayout {
                                Layout.fillWidth: true
                                spacing: 2
                                Text {
                                    text: modelData.label
                                    color: Theme.text
                                    font.family: Fonts.body
                                    font.pixelSize: 13
                                }
                                Text {
                                    Layout.fillWidth: true
                                    text: modelData.value
                                    color: Theme.textSubtle
                                    font.family: Fonts.body
                                    font.pixelSize: 11
                                    elide: Text.ElideMiddle
                                }
                            }

                            Text {
                                text: qsTr("Change")
                                color: folderRow.hovered ? Theme.accent : Theme.textSubtle
                                Behavior on color { ColorAnimation { duration: 120 } }
                                font.family: Fonts.body
                                font.pixelSize: 12
                            }
                        }
                    }
                }
            }

            // ---- cookies -----------------------------------------------------------
            ColumnLayout {
                Layout.fillWidth: true
                spacing: 10

                Text {
                    text: qsTr("Cookies")
                    color: Theme.textSubtle
                    font.family: Fonts.body
                    font.pixelSize: 11
                }

                Text {
                    Layout.fillWidth: true
                    // Worth saying plainly: without these most sites refuse a downloader
                    // outright, or start refusing after a handful of requests.
                    text: qsTr("Sites you are signed in to download more reliably. Export a cookies.txt from your browser and import it here.")
                    color: Theme.textSubtle
                    font.family: Fonts.body
                    font.pixelSize: 11
                    wrapMode: Text.WordWrap
                }

                Repeater {
                    model: settings.cookies
                    Pressable {
                        id: cookieRow
                        required property string platform
                        required property string label
                        required property bool hasCookies
                        Layout.fillWidth: true
                        implicitHeight: 48
                        radius: 10
                        baseColor: Theme.surface
                        border.color: hovered ? Theme.borderSubtle : Theme.border
                        Behavior on border.color { ColorAnimation { duration: 120 } }
                        onClicked: settings.pickCookies(platform)

                        RowLayout {
                            anchors.fill: parent
                            anchors.leftMargin: 14
                            anchors.rightMargin: 14
                            spacing: 12

                            Rectangle {
                                implicitWidth: 8
                                implicitHeight: 8
                                radius: 4
                                color: hasCookies ? Theme.success : Theme.surfaceActive
                            }

                            Text {
                                Layout.fillWidth: true
                                text: label
                                color: Theme.text
                                font.family: Fonts.body
                                font.pixelSize: 13
                            }

                            TextAction {
                                visible: hasCookies
                                text: qsTr("Remove")
                                accentColor: Theme.error
                                font.pixelSize: 12
                                onClicked: settings.cookies.clear(platform)
                            }

                            Text {
                                text: hasCookies ? qsTr("Replace") : qsTr("Import")
                                color: cookieRow.hovered ? Theme.accent : Theme.textSubtle
                                Behavior on color { ColorAnimation { duration: 120 } }
                                font.family: Fonts.body
                                font.pixelSize: 12
                            }
                        }
                    }
                }

                Text {
                    Layout.fillWidth: true
                    visible: settings.cookieError.length > 0
                    text: settings.cookieError
                    color: Theme.error
                    font.family: Fonts.body
                    font.pixelSize: 11
                    wrapMode: Text.WordWrap
                }
            }

            // ---- security ----------------------------------------------------------
            ColumnLayout {
                Layout.fillWidth: true
                spacing: 10

                Text {
                    text: qsTr("Security")
                    color: Theme.textSubtle
                    font.family: Fonts.body
                    font.pixelSize: 11
                }

                Text {
                    Layout.fillWidth: true
                    text: settings.secrets.configured
                          ? (settings.secrets.remembered
                             ? qsTr("Encrypted, and unlocked automatically on this computer. Anyone who can read your disk can also read the key beside it.")
                             : qsTr("Encrypted with your passphrase. It is asked for once per run, the first time something needs it."))
                          : qsTr("Cookies are stored as they came from your browser. Set a passphrase to encrypt them.")
                    color: Theme.textSubtle
                    font.family: Fonts.body
                    font.pixelSize: 11
                    wrapMode: Text.WordWrap
                }

                Pressable {
                    id: passphraseRow
                    Layout.fillWidth: true
                    implicitHeight: 48
                    radius: 10
                    baseColor: Theme.surface
                    border.color: hovered ? Theme.borderSubtle : Theme.border
                    Behavior on border.color { ColorAnimation { duration: 120 } }
                    onClicked: settings.secrets.configured ? changeSheet.ask()
                                                           : settings.secrets.requestUnlock("")

                    RowLayout {
                        anchors.fill: parent
                        anchors.leftMargin: 14
                        anchors.rightMargin: 14
                        spacing: 12
                        Text {
                            text: settings.secrets.configured ? qsTr("Change passphrase")
                                                              : qsTr("Set a passphrase")
                            color: Theme.text
                            font.family: Fonts.body
                            font.pixelSize: 13
                        }
                        Item { Layout.fillWidth: true }
                        Text {
                            text: settings.secrets.unlocked ? qsTr("unlocked") : qsTr("locked")
                            color: Theme.textSubtle
                            font.family: Fonts.body
                            font.pixelSize: 12
                        }
                    }
                }

                Pressable {
                    id: rememberRow
                    visible: settings.secrets.configured
                    Layout.fillWidth: true
                    implicitHeight: 62
                    radius: 10
                    baseColor: Theme.surface
                    border.color: hovered ? Theme.borderSubtle : Theme.border
                    Behavior on border.color { ColorAnimation { duration: 120 } }
                    onClicked: {
                        if (!settings.secrets.unlocked) {
                            settings.secrets.requestUnlock(qsTr("to change how this computer unlocks"))
                            return
                        }
                        settings.securityError = settings.secrets.setRemembered(!settings.secrets.remembered)
                    }

                    RowLayout {
                        anchors.fill: parent
                        anchors.leftMargin: 14
                        anchors.rightMargin: 14
                        spacing: 12
                        ColumnLayout {
                            spacing: 2
                            Text {
                                text: qsTr("Remember on this computer")
                                color: Theme.text
                                font.family: Fonts.body
                                font.pixelSize: 13
                            }
                            Text {
                                // Said plainly rather than dressed up: this is convenience.
                                text: qsTr("Convenience, not protection — the key is kept beside the data.")
                                color: Theme.textSubtle
                                font.family: Fonts.body
                                font.pixelSize: 11
                            }
                        }
                        Item { Layout.fillWidth: true }
                        Text {
                            text: settings.secrets.remembered ? qsTr("on") : qsTr("off")
                            color: settings.secrets.remembered ? Theme.accent : Theme.textSubtle
                            font.family: Fonts.body
                            font.pixelSize: 12
                        }
                    }
                }

                RowLayout {
                    visible: settings.secrets.configured
                    Layout.fillWidth: true
                    spacing: 18
                    TextAction {
                        text: qsTr("Export a recovery key")
                        font.pixelSize: 12
                        onClicked: {
                            if (!settings.secrets.unlocked) {
                                settings.secrets.requestUnlock(qsTr("to write a recovery key"))
                                return
                            }
                            recoveryDialog.open()
                        }
                    }
                    TextAction {
                        text: qsTr("Lock now")
                        accentColor: Theme.textMuted
                        font.pixelSize: 12
                        enabled: settings.secrets.unlocked
                        onClicked: settings.secrets.lock()
                    }
                    Item { Layout.fillWidth: true }
                }

                Text {
                    Layout.fillWidth: true
                    visible: settings.securityError.length > 0
                    text: settings.securityError
                    color: Theme.error
                    font.family: Fonts.body
                    font.pixelSize: 11
                    wrapMode: Text.WordWrap
                }
            }

            // ---- engine ------------------------------------------------------------
            ColumnLayout {
                Layout.fillWidth: true
                spacing: 10

                Text {
                    text: qsTr("Engine")
                    color: Theme.textSubtle
                    font.family: Fonts.body
                    font.pixelSize: 11
                }

                Rectangle {
                    Layout.fillWidth: true
                    implicitHeight: 58
                    radius: 10
                    color: Theme.surface
                    border.color: Theme.border

                    RowLayout {
                        anchors.fill: parent
                        anchors.leftMargin: 14
                        anchors.rightMargin: 14
                        spacing: 12

                        Rectangle {
                            implicitWidth: 8
                            implicitHeight: 8
                            radius: 4
                            color: settings.engine.ready ? Theme.success : Theme.warning
                        }

                        ColumnLayout {
                            Layout.fillWidth: true
                            spacing: 2
                            Text {
                                text: settings.engine.ready ? qsTr("Ready") : qsTr("Not running")
                                color: Theme.text
                                font.family: Fonts.body
                                font.pixelSize: 13
                            }
                            Text {
                                Layout.fillWidth: true
                                // The status belongs here, not as a permanent red badge in
                                // the header of every screen.
                                text: settings.engine.ytDlpVersion
                                      ? "yt-dlp " + settings.engine.ytDlpVersion
                                      : (settings.engine.status.length ? settings.engine.status
                                                                       : qsTr("Starting…"))
                                color: Theme.textSubtle
                                font.family: Fonts.body
                                font.pixelSize: 11
                                elide: Text.ElideRight
                            }
                        }

                        TextAction {
                            text: qsTr("Restart")
                            font.pixelSize: 12
                            onClicked: settings.engine.restart()
                        }
                    }
                }

                // yt-dlp, updatable without a new build — the same as the phone. An
                // extractor breaks far more often than the app around it.
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
                            Layout.fillWidth: true
                            spacing: 3

                            Text {
                                text: settings.engine.ytDlpVersion
                                      ? "yt-dlp " + settings.engine.ytDlpVersion
                                      : qsTr("yt-dlp is not installed")
                                color: Theme.text
                                font.family: Fonts.body
                                font.pixelSize: 13
                            }

                            Text {
                                Layout.fillWidth: true
                                visible: !settings.engine.updatingYtDlp
                                text: settings.engine.ytDlpUpdateStatus.length
                                      ? settings.engine.ytDlpUpdateStatus
                                      : qsTr("Updates on its own, without a new build")
                                color: Theme.textSubtle
                                font.family: Fonts.body
                                font.pixelSize: 11
                                elide: Text.ElideRight
                            }

                            RowLayout {
                                Layout.fillWidth: true
                                visible: settings.engine.updatingYtDlp
                                spacing: 8
                                Text {
                                    text: settings.engine.ytDlpUpdateStatus
                                    color: Theme.textSubtle
                                    font.family: Fonts.body
                                    font.pixelSize: 11
                                }
                                Rectangle {
                                    Layout.fillWidth: true
                                    implicitHeight: 4
                                    radius: 2
                                    color: Theme.surfaceHover
                                    Rectangle {
                                        width: parent.width * settings.engine.ytDlpUpdateProgress
                                        height: parent.height
                                        radius: parent.radius
                                        color: Theme.accent
                                        Behavior on width { NumberAnimation { duration: 120 } }
                                    }
                                }
                            }
                        }

                        TextAction {
                            text: qsTr("Choose")
                            active: !settings.engine.updatingYtDlp && settings.engine.ready
                            font.pixelSize: 12
                            onClicked: {
                                versionSheet.visible = true
                                settings.engine.refreshYtDlpVersions()
                            }
                        }

                        TextAction {
                            text: settings.engine.updatingYtDlp ? qsTr("Updating…") : qsTr("Latest")
                            active: !settings.engine.updatingYtDlp && settings.engine.ready
                            font.pixelSize: 12
                            onClicked: settings.confirmYtDlp("", qsTr("the newest version"))
                        }
                    }
                }

            }

            Item { Layout.fillHeight: true; Layout.minimumHeight: 12 }
        }
    }

    // ---- choosing a version ----------------------------------------------------------
    //
    // An overlay, not a panel in the column: inline it opened below the fold on a small
    // window and had to be scrolled to, which is not a chooser.
    Rectangle {
        id: versionSheet
        visible: false
        anchors.fill: parent
        color: Theme.overlay
        TapHandler { onTapped: versionSheet.visible = false }

        Rectangle {
            anchors.centerIn: parent
            width: Math.min(parent.width - 60, 380)
            implicitHeight: Math.min(sheetBody.implicitHeight + 36, parent.height - 60)
            radius: 12
            color: Theme.surface
            border.color: Theme.border
            TapHandler {}

            ColumnLayout {
                id: sheetBody
                anchors.fill: parent
                anchors.margins: 18
                spacing: 10

                Text {
                    text: qsTr("yt-dlp version")
                    color: Theme.text
                    font.family: Fonts.body
                    font.pixelSize: 14
                }

                ScrollView {
                    Layout.fillWidth: true
                    Layout.fillHeight: true
                    contentWidth: availableWidth
                    clip: true
                    ScrollBar.vertical.policy: ScrollBar.AlwaysOff

                    ColumnLayout {
                        width: parent.width
                        spacing: 2

                        Pressable {
                            Layout.fillWidth: true
                            implicitHeight: 34
                            radius: 8
                            selected: settings.engine.ytDlpSource === "bundled"
                            onClicked: settings.confirmYtDlp("bundled", qsTr("the bundled version"))
                            RowLayout {
                                anchors.fill: parent
                                anchors.leftMargin: 10
                                anchors.rightMargin: 10
                                Text {
                                    Layout.fillWidth: true
                                    text: qsTr("Bundled with the app")
                                    color: Theme.text
                                    font.family: Fonts.body
                                    font.pixelSize: 12
                                }
                                Text {
                                    visible: settings.engine.ytDlpSource === "bundled"
                                    text: qsTr("in use")
                                    color: Theme.accent
                                    font.family: Fonts.body
                                    font.pixelSize: 11
                                }
                            }
                        }

                        Text {
                            visible: settings.engine.ytDlpVersions.length === 0
                            Layout.fillWidth: true
                            Layout.margins: 8
                            text: settings.engine.ytDlpVersionsLoading
                                  ? qsTr("Checking…")
                                  : qsTr("Could not reach PyPI. A connection is needed to change version.")
                            color: Theme.textSubtle
                            font.family: Fonts.body
                            font.pixelSize: 11
                            wrapMode: Text.WordWrap
                        }

                        Repeater {
                            model: settings.engine.ytDlpVersions
                            Pressable {
                                required property string modelData
                                readonly property bool inUse:
                                    settings.engine.ytDlpSource === "override" &&
                                    settings.engine.ytDlpVersion === modelData
                                Layout.fillWidth: true
                                implicitHeight: 34
                                radius: 8
                                selected: inUse
                                onClicked: settings.confirmYtDlp(modelData, modelData)
                                RowLayout {
                                    anchors.fill: parent
                                    anchors.leftMargin: 10
                                    anchors.rightMargin: 10
                                    Text {
                                        Layout.fillWidth: true
                                        text: modelData
                                        color: Theme.text
                                        font.family: Fonts.body
                                        font.pixelSize: 12
                                    }
                                    Text {
                                        visible: inUse
                                        text: qsTr("in use")
                                        color: Theme.accent
                                        font.family: Fonts.body
                                        font.pixelSize: 11
                                    }
                                }
                            }
                        }
                    }
                }

                TextAction {
                    Layout.alignment: Qt.AlignRight
                    text: qsTr("Cancel")
                    accentColor: Theme.textMuted
                    font.pixelSize: 13
                    onClicked: versionSheet.visible = false
                }
            }
        }
    }

    // ---- confirming a change of extractor ------------------------------------------
    // Changing the passphrase re-wraps the master key and leaves every encrypted file
    // alone, so this is cheap — but the old one is still required, or anyone at an unlocked
    // window could lock the owner out.
    Modal {
        id: changeSheet
        anchors.fill: parent
        title: qsTr("Change passphrase")
        cardWidth: 460

        function ask() {
            oldField.text = ""
            newField.text = ""
            changeError.text = ""
            open()
            oldField.forceActiveFocus()
        }

        function submit() {
            changeError.text = settings.secrets.changePassphrase(oldField.text, newField.text)
            if (changeError.text.length === 0) changeSheet.close()
        }

        TextField {
            id: oldField
            Layout.fillWidth: true
            echoMode: TextInput.Password
            placeholderText: qsTr("Current passphrase")
            color: Theme.text
            font.family: Fonts.body
            font.pixelSize: 12
            selectByMouse: true
            selectionColor: Theme.accent
            selectedTextColor: Theme.background
            background: Rectangle {
                radius: 8
                color: Theme.background
                border.color: oldField.activeFocus ? Theme.accent : Theme.border
            }
            HoverHandler { cursorShape: Qt.IBeamCursor }
        }
        TextField {
            id: newField
            Layout.fillWidth: true
            echoMode: TextInput.Password
            placeholderText: qsTr("New passphrase")
            color: Theme.text
            font.family: Fonts.body
            font.pixelSize: 12
            selectByMouse: true
            selectionColor: Theme.accent
            selectedTextColor: Theme.background
            onAccepted: changeSheet.submit()
            background: Rectangle {
                radius: 8
                color: Theme.background
                border.color: newField.activeFocus ? Theme.accent : Theme.border
            }
            HoverHandler { cursorShape: Qt.IBeamCursor }
        }
        Text {
            id: changeError
            Layout.fillWidth: true
            visible: text.length > 0
            color: Theme.error
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
                onClicked: changeSheet.close()
            }
            TextAction {
                text: qsTr("Change it")
                font.pixelSize: 13
                enabled: oldField.text.length > 0 && newField.text.length > 0
                onClicked: changeSheet.submit()
            }
        }
    }

    FileDialog {
        id: recoveryDialog
        title: qsTr("Save a recovery key")
        fileMode: FileDialog.SaveFile
        nameFilters: [qsTr("Recovery key (*.key)")]
        selectedFile: "file://" + settings.engine.downloadDir + "/arsivinyo-recovery.key"
        onAccepted: settings.securityError = settings.secrets.exportRecoveryKey(selectedFile)
    }

    Rectangle {
        id: confirmDialog
        visible: false
        anchors.fill: parent
        color: Theme.overlay
        // Swallow clicks so the list behind cannot be operated through the dialog.
        TapHandler { onTapped: confirmDialog.visible = false }

        Rectangle {
            anchors.centerIn: parent
            width: Math.min(parent.width - 60, 420)
            implicitHeight: dialogBody.implicitHeight + 36
            radius: 12
            color: Theme.surface
            border.color: Theme.border
            TapHandler {}

            ColumnLayout {
                id: dialogBody
                anchors.fill: parent
                anchors.margins: 18
                spacing: 10

                Text {
                    text: qsTr("Switch yt-dlp?")
                    color: Theme.text
                    font.family: Fonts.body
                    font.pixelSize: 14
                }
                Text {
                    Layout.fillWidth: true
                    text: qsTr("This installs %1 and restarts the engine.").arg(settings.pendingLabel)
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
                        onClicked: confirmDialog.visible = false
                    }
                    TextAction {
                        text: qsTr("Switch and restart")
                        font.pixelSize: 13
                        onClicked: {
                            confirmDialog.visible = false
                            versionSheet.visible = false
                            if (settings.pendingVersion.length)
                                settings.engine.useYtDlpVersion(settings.pendingVersion)
                            else
                                settings.engine.updateYtDlp()
                        }
                    }
                }
            }
        }
    }

    // The engine has to come back for a queued version to be the live one.
    Connections {
        target: settings.engine
        function onYtDlpUpdateChanged() {
            if (!settings.engine.updatingYtDlp &&
                settings.engine.ytDlpUpdateStatus.indexOf("restart") !== -1) {
                settings.engine.restart()
            }
        }
    }
}
