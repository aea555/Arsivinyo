pragma Singleton
import QtQuick

/** The phone app's typefaces, loaded once. */
QtObject {
    readonly property FontLoader _brand: FontLoader { source: "fonts/Sixtyfour_400Regular.ttf" }
    readonly property FontLoader _body:  FontLoader { source: "fonts/Monda_400Regular.ttf" }
    readonly property FontLoader _bold:  FontLoader { source: "fonts/Monda_600SemiBold.ttf" }

    readonly property string brand:    _brand.name
    readonly property string body:     _body.name
    readonly property string bodyBold: _bold.name
}
