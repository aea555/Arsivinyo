pragma Singleton
import QtQuick

/**
 * The phone app's palette, verbatim.
 *
 * Values are copied from mobile/src/theme/colors.ts (darkZinc, the default). A desktop
 * app that invents its own colours stops looking like the same product, which is the
 * whole reason a toolkit that lets us set them was chosen over one that supplies its own.
 */
QtObject {
    readonly property color background:   "#09090b"
    readonly property color surface:      "#18181b"
    readonly property color surfaceHover: "#27272a"
    readonly property color text:         "#fafafa"
    readonly property color textMuted:    "#a1a1aa"
    readonly property color textSubtle:   "#71717a"
    readonly property color accent:       "#22d3ee"
    readonly property color accentHover:  "#06b6d4"
    readonly property color border:       "#27272a"
    readonly property color error:        "#ef4444"
    readonly property color success:      "#22c55e"
}
