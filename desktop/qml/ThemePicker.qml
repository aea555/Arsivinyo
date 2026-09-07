import QtQuick
import QtQuick.Layouts
import Arsivinyo

/**
 * Choosing a palette, the way the phone's settings screen does.
 *
 * The phone ships thirteen of these and lets you pick; the desktop shipped one, hard-coded,
 * which is the sort of difference that stops two apps feeling like one product.
 */
Item {
    id: picker
    implicitWidth: trigger.width
    implicitHeight: trigger.height

    Pressable {
        id: trigger
        implicitHeight: 24
        implicitWidth: swatches.implicitWidth + 20
        radius: 12
        baseColor: Theme.surface
        hoverColor: Theme.surfaceHover
        border.color: menu.visible ? Theme.accent : Theme.border
        Behavior on border.color { ColorAnimation { duration: 120 } }
        onClicked: menu.visible = !menu.visible

        RowLayout {
            id: swatches
            anchors.centerIn: parent
            spacing: 5
            Repeater {
                model: [Theme.accent, Theme.text, Theme.surfaceActive]
                Rectangle {
                    required property color modelData
                    implicitWidth: 8
                    implicitHeight: 8
                    radius: 4
                    color: modelData
                    border.color: Theme.border
                }
            }
            Text {
                text: qsTr("Theme")
                color: Theme.textMuted
                font.family: Fonts.body
                font.pixelSize: 11
            }
        }
    }

    // Anchored to the trigger rather than placed in a Popup: a Popup in Basic style brings
    // its own background that ignores the palette, which is the problem being fixed here.
    Rectangle {
        id: menu
        visible: false
        z: 100
        width: 190
        implicitHeight: content.implicitHeight + 20
        height: implicitHeight
        anchors.top: trigger.bottom
        anchors.right: trigger.right
        anchors.topMargin: 8
        radius: 12
        color: Theme.surface
        border.color: Theme.border

        ColumnLayout {
            id: content
            anchors.fill: parent
            anchors.margins: 10
            spacing: 8

            RowLayout {
                Layout.fillWidth: true
                spacing: 6
                Repeater {
                    model: [{ id: "dark", label: qsTr("Dark") }, { id: "light", label: qsTr("Light") }]
                    Pressable {
                        required property var modelData
                        Layout.fillWidth: true
                        implicitHeight: 26
                        radius: 8
                        selected: Theme.mode === modelData.id
                        baseColor: Theme.background
                        onClicked: {
                            // Each mode has its own variants, so switching needs one that exists.
                            const list = modelData.id === "dark" ? Theme.darkVariants : Theme.lightVariants
                            const keep = list.find(v => v.id === Theme.variant)
                            Theme.apply(modelData.id, keep ? keep.id : list[0].id)
                        }
                        Text {
                            anchors.centerIn: parent
                            text: modelData.label
                            color: Theme.mode === modelData.id ? Theme.text : Theme.textSubtle
                            font.family: Fonts.body
                            font.pixelSize: 12
                        }
                    }
                }
            }

            Rectangle { Layout.fillWidth: true; implicitHeight: 1; color: Theme.border }

            Repeater {
                model: Theme.variants
                Pressable {
                    required property var modelData
                    Layout.fillWidth: true
                    implicitHeight: 28
                    radius: 8
                    selected: Theme.variant === modelData.id
                    onClicked: Theme.apply(Theme.mode, modelData.id)

                    RowLayout {
                        anchors.fill: parent
                        anchors.leftMargin: 10
                        anchors.rightMargin: 10
                        spacing: 8
                        Rectangle {
                            implicitWidth: 10
                            implicitHeight: 10
                            radius: 5
                            // The palette's own accent, so the list previews itself.
                            color: Theme.palettes[Theme.mode + "." + modelData.id].accent
                            border.color: Theme.border
                        }
                        Text {
                            Layout.fillWidth: true
                            text: modelData.label
                            color: Theme.variant === modelData.id ? Theme.text : Theme.textMuted
                            font.family: Fonts.body
                            font.pixelSize: 12
                        }
                    }
                }
            }
        }
    }

    /** Click anywhere else to dismiss. */
    TapHandler {
        enabled: menu.visible
        grabPermissions: PointerHandler.CanTakeOverFromAnything
        onTapped: menu.visible = false
    }
}
