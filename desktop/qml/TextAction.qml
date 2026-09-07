import QtQuick
import Arsivinyo

/**
 * A word you can click.
 *
 * Plain Text with a TapHandler gives no sign it is clickable — no cursor, no hover, nothing
 * when it is pressed. Most of this app's actions are words, so they all come through here.
 */
Text {
    id: label

    property color accentColor: Theme.accent
    /** Shown but not clickable, for an action that needs a connected device. */
    property bool active: true

    readonly property bool hovered: hover.hovered && active
    signal clicked()

    color: !active ? Theme.textSubtle
         : tap.pressed ? Qt.darker(accentColor, 1.25)
         : hovered ? Qt.lighter(accentColor, 1.2)
         : accentColor
    Behavior on color { ColorAnimation { duration: 110 } }

    font.family: Fonts.body
    font.pixelSize: 13
    font.underline: hovered

    opacity: active ? 1 : 0.7

    HoverHandler {
        id: hover
        enabled: label.active
        cursorShape: Qt.PointingHandCursor
    }
    TapHandler {
        id: tap
        enabled: label.active
        onTapped: label.clicked()
    }
}
