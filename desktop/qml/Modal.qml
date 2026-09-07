import QtQuick
import QtQuick.Layouts

// The overlay card the app uses for every dialog.
//
// This shape was copy-pasted three times — the yt-dlp version sheet, the switch
// confirmation, and the peer link sheet — with one of them hardcoding its own overlay colour
// instead of the theme's. Put the content in `body` and give it a `title`.

Rectangle {
    id: root

    property alias title: heading.text
    /** Put the dialog's own controls here. */
    default property alias body: content.data
    /** How wide the card is allowed to get. */
    property int cardWidth: 420
    /** Closing on a click outside is right for a chooser, wrong for something mid-flight. */
    property bool closeOnClickOutside: true

    signal dismissed()

    function open() { root.visible = true }
    function close() { root.visible = false; root.dismissed() }

    visible: false
    anchors.fill: parent
    color: Theme.overlay

    // Swallow clicks so whatever is behind cannot be operated through the dialog.
    TapHandler { onTapped: if (root.closeOnClickOutside) root.close() }

    Rectangle {
        anchors.centerIn: parent
        width: Math.min(parent.width - 60, root.cardWidth)
        implicitHeight: card.implicitHeight + 36
        radius: 12
        color: Theme.surface
        border.color: Theme.border
        // Keeps a tap inside the card from reaching the dismissing handler above.
        TapHandler {}

        ColumnLayout {
            id: card
            anchors.fill: parent
            anchors.margins: 18
            spacing: 10

            Text {
                id: heading
                visible: text.length > 0
                color: Theme.text
                font.family: Fonts.body
                font.pixelSize: 14
            }

            ColumnLayout {
                id: content
                Layout.fillWidth: true
                spacing: 10
            }
        }
    }
}
