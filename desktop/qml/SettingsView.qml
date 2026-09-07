import QtQuick
import QtQuick.Controls.Basic
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

    ScrollView {
        id: scroller
        anchors.fill: parent
        contentWidth: availableWidth
        clip: true
        // The scrollbar is drawn over the content, so the content has to stop short of it.
        // Sizing the column to the view's full width put the cards under the bar.
        rightPadding: 16
        ScrollBar.vertical.policy: ScrollBar.AsNeeded

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
                        { label: qsTr("Downloads"), value: settings.engine.downloadDir },
                        { label: qsTr("Music"), value: settings.library.musicDir },
                    ]
                    Rectangle {
                        required property var modelData
                        Layout.fillWidth: true
                        implicitHeight: 54
                        radius: 10
                        color: Theme.surface
                        border.color: Theme.border

                        ColumnLayout {
                            anchors.fill: parent
                            anchors.leftMargin: 14
                            anchors.rightMargin: 14
                            spacing: 2
                            Layout.alignment: Qt.AlignVCenter

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
                    }
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
                            text: settings.engine.updatingYtDlp ? qsTr("Updating…") : qsTr("Update")
                            active: !settings.engine.updatingYtDlp && settings.engine.ready
                            font.pixelSize: 12
                            onClicked: settings.engine.updateYtDlp()
                        }
                    }
                }
            }

            Item { Layout.fillHeight: true; Layout.minimumHeight: 12 }
        }
    }
}
