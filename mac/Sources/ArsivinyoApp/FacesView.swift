import AppKit
import ArsivinyoCore
import AVFoundation
import ImageIO
import SwiftUI

/// Faces across the collection: questions to answer, groups to name, and the people known.
/// `shared/memes/CONTRACT.md`, "Faces".
struct FacesView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss

    private var asked: [MemeLibrary.FaceRef] { MemeLibrary.asked(model.memeSnapshot) }
    private var groups: [[MemeLibrary.FaceRef]] { MemeLibrary.unnamedGroups(model.memeSnapshot) }

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Text("Faces").font(.title2.bold())
                Spacer()
                if let remaining = model.facesRemaining {
                    ProgressView().controlSize(.small)
                    Text("Looking at \(remaining) memes…").foregroundStyle(.secondary)
                }
            }
            .padding(16)
            Divider()
            if let problem = model.faceProblem {
                ContentUnavailableView("Faces are not available", systemImage: "person.crop.square",
                                       description: Text(problem))
            } else if asked.isEmpty && groups.isEmpty && knownPeople.isEmpty {
                ContentUnavailableView("No faces yet", systemImage: "person.crop.square",
                                       description: Text("Faces in your memes appear here once they have been looked at. Name one, and every meme with that face is labelled."))
            } else {
                ScrollView {
                    VStack(alignment: .leading, spacing: 22) {
                        if !asked.isEmpty { askedSection }
                        if !groups.isEmpty { groupsSection }
                        if !knownPeople.isEmpty { peopleSection }
                    }
                    .padding(16)
                }
            }
            Divider()
            HStack {
                Spacer()
                Button("Done") { dismiss() }.keyboardShortcut(.defaultAction)
            }
            .padding(12)
        }
        .frame(minWidth: 720, minHeight: 520)
    }

    // MARK: Questions

    private var askedSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Is this…?").font(.headline)
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 150), spacing: 12)], spacing: 12) {
                ForEach(asked) { ref in
                    VStack(spacing: 6) {
                        FaceCrop(ref: ref, size: 120)
                        Text(personName(ref.face.person)).font(.callout).lineLimit(1)
                        HStack {
                            Button { model.confirmFace(ref.face.id) } label: { Image(systemName: "checkmark") }
                                .help("Yes, it is")
                            Button { model.rejectFace(ref.face.id) } label: { Image(systemName: "xmark") }
                                .help("No, it is someone else")
                        }
                    }
                }
            }
        }
    }

    // MARK: Unnamed

    private var groupsSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Unnamed").font(.headline)
            Text("The same person, as far as the faces can tell. Name them once.").foregroundStyle(.secondary)
            ForEach(groups, id: \.first!.id) { group in
                GroupRow(group: group)
                Divider()
            }
        }
    }

    // MARK: People

    private var knownPeople: [MemeLibrary.Person] {
        model.memeSnapshot.people.filter { !($0.signatures ?? []).isEmpty }.sorted { $0.name < $1.name }
    }

    private var peopleSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("People").font(.headline)
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 130), spacing: 12)], spacing: 12) {
                ForEach(knownPeople) { person in
                    let faces: [MemeLibrary.FaceRef] = MemeLibrary.faces(of: person.id, in: model.memeSnapshot)
                    VStack(spacing: 6) {
                        if let first = faces.first { FaceCrop(ref: first, size: 96) }
                        Text(person.name).font(.callout).lineLimit(1)
                        Text("\(Set(faces.map(\.item.id)).count) memes").font(.caption).foregroundStyle(.secondary)
                    }
                }
            }
        }
    }

    private func personName(_ id: String?) -> String {
        model.memeSnapshot.people.first { $0.id == id }?.name ?? ""
    }
}

/// One unnamed group: its faces, and a field to name them.
private struct GroupRow: View {
    @Environment(AppModel.self) private var model
    let group: [MemeLibrary.FaceRef]
    @State private var name = ""

    var body: some View {
        HStack(alignment: .center, spacing: 10) {
            ForEach(group.prefix(6)) { ref in FaceCrop(ref: ref, size: 64) }
            if group.count > 6 {
                Text("+\(group.count - 6)").foregroundStyle(.secondary)
            }
            Spacer()
            TextField("Who is this?", text: $name)
                .frame(width: 180)
                .onSubmit(save)
            Menu {
                ForEach(model.memeSnapshot.people.sorted { $0.name < $1.name }) { person in
                    Button(person.name) { name = person.name }
                }
            } label: { Image(systemName: "person.crop.circle") }
                .menuStyle(.borderlessButton).fixedSize()
                .disabled(model.memeSnapshot.people.isEmpty)
            Button("Set Name", action: save).disabled(name.trimmingCharacters(in: .whitespaces).isEmpty)
        }
    }

    private func save() {
        model.nameFaces(Set(group.map(\.face.id)), as: name)
        name = ""
    }
}

/// The faces in one meme, under its preview: who each is, and a way to say otherwise.
struct MemeFacesRow: View {
    @Environment(AppModel.self) private var model
    let item: MemeLibrary.Item
    @State private var naming: String?
    @State private var name = ""

    var body: some View {
        let faces = item.faces ?? []
        if !faces.isEmpty {
            VStack(alignment: .leading, spacing: 6) {
                Text("Faces").font(.caption).foregroundStyle(.secondary)
                HStack(spacing: 10) {
                    ForEach(faces) { face in
                        let ref = MemeLibrary.FaceRef(item: item, face: face)
                        VStack(spacing: 3) {
                            FaceCrop(ref: ref, size: 56)
                            Text(label(face)).font(.caption2).lineLimit(1).frame(maxWidth: 70)
                        }
                        .contextMenu {
                            if face.person != nil {
                                Button("Not \(personName(face.person))") { model.rejectFace(face.id) }
                            }
                            if face.state == .asked {
                                Button("Yes, \(personName(face.person))") { model.confirmFace(face.id) }
                            }
                            Button("Name…") { naming = face.id; name = "" }
                        }
                        .popover(isPresented: Binding(get: { naming == face.id }, set: { if !$0 { naming = nil } })) {
                            HStack {
                                TextField("Who is this?", text: $name).frame(width: 180).onSubmit(saveName)
                                Button("Set Name", action: saveName)
                            }
                            .padding(12)
                        }
                    }
                }
            }
        }
    }

    private func label(_ face: MemeLibrary.Face) -> String {
        switch face.state {
        case .asked: return String(localized: "\(personName(face.person))?")
        case .unnamed: return String(localized: "Unknown")
        default: return personName(face.person)
        }
    }

    private func personName(_ id: String?) -> String {
        model.memeSnapshot.people.first { $0.id == id }?.name ?? ""
    }

    private func saveName() {
        if let naming { model.nameFaces([naming], as: name) }
        naming = nil
    }
}

/// A face cut from its meme's frame. Kept in memory only: a private meme's face is never
/// written anywhere outside the vault.
struct FaceCrop: View {
    @Environment(AppModel.self) private var model
    let ref: MemeLibrary.FaceRef
    let size: CGFloat
    @State private var image: NSImage?

    private static let cache = NSCache<NSString, NSImage>()

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 8).fill(.quaternary)
            if let image {
                Image(nsImage: image).resizable().scaledToFill()
            } else {
                Image(systemName: "person.crop.square").foregroundStyle(.secondary)
            }
        }
        .frame(width: size, height: size)
        .clipShape(RoundedRectangle(cornerRadius: 8))
        .task(id: ref.face.id) { image = await load() }
    }

    private func load() async -> NSImage? {
        if let cached = Self.cache.object(forKey: ref.face.id as NSString) { return cached }
        let item = ref.item, face = ref.face
        let vault = model.vault
        let contentType = model.vaultItems.first { $0.id == item.vaultId }?.contentType
        let made: CGImage? = await Task.detached(priority: .userInitiated) {
            let frame: CGImage?
            if let file = item.fileURL {
                frame = item.isVideo
                    ? try? await FaceScanner.frame(of: AVURLAsset(url: file), at: face.frameMs)
                    : FaceScanner.image(from: CGImageSourceCreateWithURL(file as CFURL, nil))
            } else if let vaultId = item.vaultId, let reader = try? vault.reader(for: vaultId) {
                if item.isVideo {
                    let loader = VaultAssetLoader(reader: reader, contentType: contentType ?? "public.mpeg-4")
                    frame = try? await FaceScanner.frame(of: loader.makeAsset(id: vaultId), at: face.frameMs)
                } else {
                    frame = (try? reader.read(offset: 0, length: Int(reader.size)))
                        .flatMap { FaceScanner.image(from: CGImageSourceCreateWithData($0 as CFData, nil)) }
                }
            } else {
                frame = nil
            }
            return frame.flatMap { FaceScanner.crop($0, box: face.box) }
        }.value
        guard let made else { return nil }
        let image = NSImage(cgImage: made, size: .zero)
        Self.cache.setObject(image, forKey: face.id as NSString)
        return image
    }
}
