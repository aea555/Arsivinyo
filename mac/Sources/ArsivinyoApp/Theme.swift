import AppKit
import SwiftUI

/// The phone's colour themes, on the Mac: the same names and the same values as
/// mobile/src/theme/colors.ts, so a theme looks like itself on both. Change them there first.
///
/// Either or, never in between: "Mac", the default, is a regular Mac app, nothing tinted or
/// painted; a theme is opted into and then reaches everything, as on the phone: the window,
/// the sidebar, the toolbar, every list, form and table, and the accent. Half of both looked
/// worse than either.
enum Theme: String, CaseIterable, Identifiable {
    case system
    // Dark.
    case zinc, slate, crimson, emerald, midnight, ember, carbon, orchid
    // Light.
    case neutral, warm, cool, paper, frost

    var id: String { rawValue }

    static let dark: [Theme] = [.zinc, .slate, .crimson, .emerald, .midnight, .ember, .carbon, .orchid]
    static let light: [Theme] = [.neutral, .warm, .cool, .paper, .frost]

    /// The phone's names, which are the same in every language there.
    var name: String {
        self == .system ? "Mac" : rawValue.prefix(1).uppercased() + rawValue.dropFirst()
    }

    var scheme: ColorScheme? {
        switch self {
        case .system: nil
        case .neutral, .warm, .cool, .paper, .frost: .light
        default: .dark
        }
    }

    /// background, surface and accent, as the phone has them.
    private var palette: (background: UInt32, surface: UInt32, accent: UInt32)? {
        switch self {
        case .system: nil
        case .zinc: (0x09090b, 0x18181b, 0x22d3ee)
        case .slate: (0x020617, 0x0f172a, 0x60a5fa)
        case .crimson: (0x0a0a0a, 0x171717, 0xf87171)
        case .emerald: (0x022c22, 0x064e3b, 0x34d399)
        case .midnight: (0x0f1419, 0x171d25, 0x7aa2f7)
        case .ember: (0x1a1512, 0x241d18, 0xf0a35e)
        case .carbon: (0x000000, 0x0d0d0d, 0xa3e635)
        case .orchid: (0x14101a, 0x1e1828, 0xc084fc)
        case .neutral: (0xffffff, 0xf5f5f5, 0x6366f1)
        case .warm: (0xfffbeb, 0xfef3c7, 0xf97316)
        case .cool: (0xf0f9ff, 0xe0f2fe, 0x0ea5e9)
        case .paper: (0xfaf6ef, 0xf2ece1, 0xb7791f)
        case .frost: (0xf5f8fa, 0xffffff, 0x2b7fd4)
        }
    }

    var background: Color? { palette.map { Color(hex: $0.background) } }
    var surface: Color? { palette.map { Color(hex: $0.surface) } }
    var accent: Color? { palette.map { Color(hex: $0.accent) } }

    static var stored: Theme {
        UserDefaults.standard.string(forKey: "theme").flatMap(Theme.init(rawValue:)) ?? .system
    }
}

private extension Color {
    init(hex: UInt32) {
        self.init(.sRGB, red: Double((hex >> 16) & 0xff) / 255, green: Double((hex >> 8) & 0xff) / 255,
                  blue: Double(hex & 0xff) / 255, opacity: 1)
    }
}

extension View {
    /// A window in the chosen theme: its mode, its accent, and its background.
    func themed(_ theme: Theme) -> some View {
        modifier(ThemeModifier(theme: theme))
    }
}

private struct ThemeModifier: ViewModifier {
    let theme: Theme

    func body(content: Content) -> some View {
        if let background = theme.background, let surface = theme.surface {
            // Set once at a window's root and inherited: every scroll view, list, form and
            // table inside draws no background of its own, so the theme's shows through, and
            // anything added later is themed without doing anything.
            content
                .environment(\.themeSurface, surface)
                .environment(\.themeAccent, theme.accent)
                .preferredColorScheme(theme.scheme)
                .tint(theme.accent)
                .scrollContentBackground(.hidden)
                .onAppear { TableStripes.use(theme) }
                .onChange(of: theme) { _, now in TableStripes.use(now) }
                .background(background)
                .containerBackground(background, for: .window)
                .toolbarBackground(background, for: .windowToolbar)
                .toolbarBackgroundVisibility(.visible, for: .windowToolbar)
        } else {
            content
                .onAppear { TableStripes.use(theme) }
                .onChange(of: theme) { _, now in TableStripes.use(now) }
        }
    }
}

/// Finder's striped rows in a theme's two tones, background and surface. SwiftUI's Table is an
/// NSOutlineView with the system's stripes, which no public API colours: its rows and the
/// stripes past the last one both take their colours from one private method of NSTableView,
/// so with a theme that method answers the theme's. With the Mac's look it is the system's,
/// untouched.
enum TableStripes {
    @MainActor fileprivate static var colors: [NSColor]?
    @MainActor private static var installed = false

    @MainActor static func use(_ theme: Theme) {
        install()
        let now = theme.background.flatMap { even in theme.surface.map { [NSColor(even), NSColor($0)] } }
        guard now != colors else { return }
        colors = now
        // Tables on screen keep the colours they took; striping again takes the new ones.
        for window in NSApp.windows { restripe(window.contentView) }
    }

    @MainActor private static func restripe(_ view: NSView?) {
        guard let view else { return }
        if let table = view as? NSTableView, table.usesAlternatingRowBackgroundColors {
            table.usesAlternatingRowBackgroundColors = false
            table.usesAlternatingRowBackgroundColors = true
        }
        view.subviews.forEach(restripe)
    }

    @MainActor private static func install() {
        guard !installed else { return }
        installed = true
        let original = class_getInstanceMethod(NSTableView.self, NSSelectorFromString("_alternatingRowBackgroundColors"))
        let themed = class_getInstanceMethod(NSTableView.self, #selector(NSTableView.arsivinyo_alternatingRowBackgroundColors))
        if let original, let themed { method_exchangeImplementations(original, themed) }
    }
}

extension NSTableView {
    /// Swapped with the private _alternatingRowBackgroundColors by TableStripes: calling itself
    /// here calls the system's.
    @objc func arsivinyo_alternatingRowBackgroundColors() -> [NSColor] {
        MainActor.assumeIsolated { TableStripes.colors } ?? arsivinyo_alternatingRowBackgroundColors()
    }
}

extension EnvironmentValues {
    /// The theme's surface colour, for the few places a window is in two tones (the sidebar);
    /// nil with the Mac's own look.
    @Entry var themeSurface: Color? = nil
    /// The theme's accent, for what macOS colours with the system's accent whatever the app's
    /// tint (a sidebar's icons); nil with the Mac's own look.
    @Entry var themeAccent: Color? = nil
}

extension View {
    /// The sidebar in the theme's surface colour, over its glass, and its icons in the theme's
    /// accent (macOS gives them the system's otherwise); the Mac's own sidebar without a theme.
    func themedSidebar() -> some View {
        modifier(SidebarSurface())
    }
}

private struct SidebarSurface: ViewModifier {
    @Environment(\.themeSurface) private var surface
    @Environment(\.themeAccent) private var accent

    func body(content: Content) -> some View {
        if let surface, let accent {
            content.background(surface).listItemTint(accent)
        } else {
            content
        }
    }
}

/// The themes as swatches, light and dark, each its background with its accent on it.
struct ThemePicker: View {
    @Binding var theme: Theme

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            swatches([.system])
            Text("Dark").font(.caption).foregroundStyle(.secondary)
            swatches(Theme.dark)
            Text("Light").font(.caption).foregroundStyle(.secondary)
            swatches(Theme.light)
        }
    }

    private func swatches(_ themes: [Theme]) -> some View {
        HStack(spacing: 10) {
            ForEach(themes) { item in
                Button { theme = item } label: {
                    VStack(spacing: 4) {
                        ZStack {
                            RoundedRectangle(cornerRadius: 8)
                                .fill(item.background ?? Color(nsColor: .windowBackgroundColor))
                            Circle().fill(item.accent ?? .accentColor).frame(width: 14, height: 14)
                        }
                        .frame(width: 44, height: 30)
                        .overlay {
                            RoundedRectangle(cornerRadius: 8)
                                .strokeBorder(theme == item ? Color.accentColor : Color.secondary.opacity(0.4),
                                              lineWidth: theme == item ? 2 : 1)
                        }
                        Text(item.name).font(.caption2).lineLimit(1)
                    }
                    .frame(width: 52)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(item.name)
                .accessibilityAddTraits(theme == item ? .isSelected : [])
            }
        }
    }
}
