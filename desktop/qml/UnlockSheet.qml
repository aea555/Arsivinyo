import QtQuick
import QtQuick.Controls
import QtQuick.Layouts

// The passphrase prompt. The first password field in the app.
//
// It appears when something needs a key and the keybox is locked, and on first run it asks
// for a passphrase to set rather than one to type.

Modal {
    id: root

    required property var store
    /** Shown under the title, so the prompt says what it is protecting. */
    property string reason: ""

    readonly property bool creating: !store.configured
    // Nothing is half-done behind this: dismissing just leaves the app locked.
    closeOnClickOutside: true
    cardWidth: 460
    title: creating ? qsTr("Set a passphrase") : qsTr("Unlock")

    function ask(why) {
        root.reason = why
        field.text = ""
        confirmField.text = ""
        error.text = ""
        root.open()
        field.forceActiveFocus()
    }

    onDismissed: { field.text = ""; confirmField.text = "" }

    function submit() {
        if (root.creating) {
            if (field.text !== confirmField.text) {
                error.text = qsTr("Those do not match.")
                return
            }
            error.text = root.store.create(field.text)
        } else {
            error.text = root.store.unlock(field.text)
        }
        if (error.text.length === 0) root.close()
    }

    Text {
        Layout.fillWidth: true
        visible: text.length > 0
        text: root.creating
              ? qsTr("Your cookies and vault are encrypted with this. There is no way to "
                     + "recover it — write it down, or export a recovery key afterwards.")
              : root.reason
        color: Theme.textMuted
        font.family: Fonts.body
        font.pixelSize: 12
        wrapMode: Text.WordWrap
    }

    TextField {
        id: field
        Layout.fillWidth: true
        echoMode: TextInput.Password
        placeholderText: root.creating ? qsTr("A passphrase you will remember") : qsTr("Passphrase")
        color: Theme.text
        font.family: Fonts.body
        font.pixelSize: 12
        selectByMouse: true
        selectionColor: Theme.accent
        selectedTextColor: Theme.background
        onAccepted: root.submit()
        background: Rectangle {
            radius: 8
            color: Theme.background
            border.color: field.activeFocus ? Theme.accent : Theme.border
        }
        HoverHandler { cursorShape: Qt.IBeamCursor }
    }

    TextField {
        id: confirmField
        visible: root.creating
        Layout.fillWidth: true
        echoMode: TextInput.Password
        placeholderText: qsTr("And again")
        color: Theme.text
        font.family: Fonts.body
        font.pixelSize: 12
        selectByMouse: true
        selectionColor: Theme.accent
        selectedTextColor: Theme.background
        onAccepted: root.submit()
        background: Rectangle {
            radius: 8
            color: Theme.background
            border.color: confirmField.activeFocus ? Theme.accent : Theme.border
        }
        HoverHandler { cursorShape: Qt.IBeamCursor }
    }

    Text {
        id: error
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
            text: qsTr("Not now")
            accentColor: Theme.textMuted
            font.pixelSize: 13
            onClicked: root.close()
        }
        TextAction {
            text: root.creating ? qsTr("Set it") : qsTr("Unlock")
            font.pixelSize: 13
            enabled: field.text.length > 0
            onClicked: root.submit()
        }
    }
}
