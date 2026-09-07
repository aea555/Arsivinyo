import QtQuick
import Arsivinyo

/**
 * A clickable surface with the feedback a desktop expects.
 *
 * The phone gets away with none of this: a finger covers the target and the touch itself is
 * the feedback. A pointer needs telling what is live before it clicks and needs to feel the
 * click land — so this changes the cursor, tints on hover, and dips slightly when pressed.
 */
Rectangle {
    id: control

    /** Drawn as selected regardless of the pointer. */
    property bool selected: false
    property color baseColor: "transparent"
    property color hoverColor: Theme.surfaceHover
    property color selectedColor: Theme.surfaceHover
    /** Set false for a row that is only hoverable when something else is true. */
    property bool interactive: true

    readonly property bool hovered: hover.hovered && interactive
    readonly property bool pressed: tap.pressed && interactive

    signal clicked()

    color: selected ? selectedColor
         : hovered ? hoverColor
         : baseColor
    Behavior on color { ColorAnimation { duration: 110 } }

    // Small enough to feel rather than see. A larger dip reads as the whole layout moving.
    scale: pressed ? 0.985 : 1
    Behavior on scale { NumberAnimation { duration: 90; easing.type: Easing.OutQuad } }

    HoverHandler {
        id: hover
        enabled: control.interactive
        cursorShape: Qt.PointingHandCursor
    }
    TapHandler {
        id: tap
        enabled: control.interactive
        onTapped: control.clicked()
    }
}
