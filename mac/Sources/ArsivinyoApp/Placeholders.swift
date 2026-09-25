import SwiftUI

/// Sections whose machinery is not wired in yet.
///
/// Each says what it will do rather than showing an empty pane, so the shape of the app is
/// legible while it is being built.

struct DevicesView: View {
    var body: some View {
        ContentUnavailableView(
            "No devices paired",
            systemImage: "laptopcomputer.and.iphone",
            description: Text("Pair your phone to move files between it and this Mac."))
    }
}
