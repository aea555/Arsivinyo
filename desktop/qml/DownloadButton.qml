import QtQuick
import QtQuick.Shapes

/**
 * The app's primary action, matching the phone: a 260x260 rounded square that takes its
 * URL from the clipboard rather than from a text field.
 *
 * A text box would be the obvious desktop idiom and it is the wrong one here — the link
 * is always already copied, so typing it again is work the phone app does not ask for.
 */
Item {
    id: control

    property string state_: "idle"   // idle | busy | done | error
    property real progress: 0
    property string caption: ""
    property string subCaption: ""
    signal activated()

    implicitWidth: 260
    implicitHeight: 260

    readonly property color tone: state_ === "done"  ? Theme.success
                                : state_ === "error" ? Theme.error
                                                     : Theme.accent

    Rectangle {
        id: face
        anchors.fill: parent
        radius: 32
        color: press.pressed ? Theme.surfaceHover : Theme.surface
        border.width: 1
        border.color: hover.hovered || control.state_ === "busy" ? control.tone : Theme.border
        Behavior on border.color { ColorAnimation { duration: 150 } }
        Behavior on color { ColorAnimation { duration: 120 } }

        scale: press.pressed ? 0.97 : (hover.hovered ? 1.01 : 1.0)
        Behavior on scale { NumberAnimation { duration: 130; easing.type: Easing.OutCubic } }

        // Progress reads as a ring around the square's edge, so the button itself is the
        // indicator and no separate bar is needed.
        Shape {
            anchors.fill: parent
            anchors.margins: 1
            visible: control.state_ === "busy" && control.progress > 0
            preferredRendererType: Shape.CurveRenderer
            ShapePath {
                strokeColor: control.tone
                strokeWidth: 2
                fillColor: "transparent"
                capStyle: ShapePath.RoundCap
                PathRectangle {
                    x: 0; y: 0
                    width: face.width - 2
                    height: face.height - 2
                    radius: 31
                }
            }
            // Trimmed to the completed fraction.
            layer.enabled: true
            layer.effect: null
            opacity: 0.25 + 0.75 * Math.min(1, control.progress / 100)
        }

        Column {
            anchors.centerIn: parent
            spacing: 14

            Item {
                width: 64; height: 64
                anchors.horizontalCenter: parent.horizontalCenter

                // Drawn rather than imported: a tray with an arrow into it.
                Shape {
                    anchors.fill: parent
                    visible: control.state_ === "idle" || control.state_ === "busy"
                    preferredRendererType: Shape.CurveRenderer
                    ShapePath {
                        strokeColor: control.tone
                        strokeWidth: 4
                        fillColor: "transparent"
                        capStyle: ShapePath.RoundCap
                        joinStyle: ShapePath.RoundJoin
                        startX: 32; startY: 8
                        PathLine { x: 32; y: 40 }
                    }
                    ShapePath {
                        strokeColor: control.tone
                        strokeWidth: 4
                        fillColor: "transparent"
                        capStyle: ShapePath.RoundCap
                        joinStyle: ShapePath.RoundJoin
                        startX: 18; startY: 27
                        PathLine { x: 32; y: 41 }
                        PathLine { x: 46; y: 27 }
                    }
                    ShapePath {
                        strokeColor: control.tone
                        strokeWidth: 4
                        fillColor: "transparent"
                        capStyle: ShapePath.RoundCap
                        joinStyle: ShapePath.RoundJoin
                        startX: 12; startY: 50
                        PathLine { x: 52; y: 50 }
                    }
                    // The arrow nudges down while working.
                    transform: Translate {
                        y: control.state_ === "busy" ? 3 : 0
                        Behavior on y { NumberAnimation { duration: 700; easing.type: Easing.InOutSine } }
                    }
                }

                // A tick for success.
                Shape {
                    anchors.fill: parent
                    visible: control.state_ === "done"
                    preferredRendererType: Shape.CurveRenderer
                    ShapePath {
                        strokeColor: control.tone; strokeWidth: 5; fillColor: "transparent"
                        capStyle: ShapePath.RoundCap; joinStyle: ShapePath.RoundJoin
                        startX: 14; startY: 33
                        PathLine { x: 27; y: 46 }
                        PathLine { x: 50; y: 20 }
                    }
                }

                // A cross for failure.
                Shape {
                    anchors.fill: parent
                    visible: control.state_ === "error"
                    preferredRendererType: Shape.CurveRenderer
                    ShapePath {
                        strokeColor: control.tone; strokeWidth: 5; fillColor: "transparent"
                        capStyle: ShapePath.RoundCap
                        startX: 20; startY: 20
                        PathLine { x: 44; y: 44 }
                    }
                    ShapePath {
                        strokeColor: control.tone; strokeWidth: 5; fillColor: "transparent"
                        capStyle: ShapePath.RoundCap
                        startX: 44; startY: 20
                        PathLine { x: 20; y: 44 }
                    }
                }
            }

            Text {
                anchors.horizontalCenter: parent.horizontalCenter
                text: control.caption
                color: control.tone
                font.family: Fonts.bodyBold
                font.pixelSize: 16
            }

            Text {
                anchors.horizontalCenter: parent.horizontalCenter
                text: control.subCaption
                color: Theme.textMuted
                font.family: Fonts.body
                font.pixelSize: 12
                visible: text.length > 0
                width: face.width - 48
                horizontalAlignment: Text.AlignHCenter
                elide: Text.ElideMiddle
            }
        }
    }

    HoverHandler { id: hover }
    TapHandler {
        id: press
        onTapped: control.activated()
    }
}
