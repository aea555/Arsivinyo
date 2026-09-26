import ArsivinyoCore
import ArsivinyoFacesC
import CoreGraphics
import Foundation

/// Faces, held to `shared/memes/CONTRACT.md`'s phase 2 "done" list and to
/// `shared/faces/VECTORS.json`, which fixes what both apps must see in the same frames.
extension CoreChecks {

    mutating func checkFaces() throws {
        print("faces")
        let folder = Self.repositoryRoot.appendingPathComponent("shared/faces")
        let scanner = try FaceScanner(folder: folder)

        // 1. The fixtures, as the pipeline must see them on both apps.
        let vectors = try JSONSerialization.jsonObject(with: Data(contentsOf: folder.appendingPathComponent("VECTORS.json")))
            as! [String: Any]
        var seen: [String: [MemeLibrary.ScannedFace]] = [:]
        for fixture in vectors["fixtures"] as! [[String: Any]] {
            let file = fixture["file"] as! String
            let expected = fixture["faces"] as! [[String: Any]]
            let found = try Self.lookAtPPM(folder.appendingPathComponent(file), scanner: scanner)
            check(found.count == expected.count, "\(file): \(expected.count) faces (\(found.count))")
            for (face, want) in zip(found, expected) {
                let box = want["box"] as! [Double]
                let offBy = zip([face.x, face.y, face.w, face.h].map(Double.init), box).map { abs($0 - $1) }.max() ?? 99
                let signature = (want["signature"] as! [Double]).map(Float.init)
                var mine = face.signature
                let same = withUnsafeBytes(of: &mine) { FaceMath.cosine(Array($0.bindMemory(to: Float.self)), signature) }
                check(offBy <= 1 && same >= 0.999,
                      String(format: "  the same box (off by %.2f px) and signature (cosine %.4f)", offBy, same))
            }
            seen[(file as NSString).lastPathComponent] = found.map { face in
                var face = face
                let signature = withUnsafeBytes(of: &face.signature) { Array($0.bindMemory(to: Float.self)) }
                return MemeLibrary.ScannedFace(signature: signature, box: [Double(face.x), Double(face.y), Double(face.w), Double(face.h)],
                                               frameMs: 0)
            }
        }
        guard let crew = seen["crew.ppm"]?.sorted(by: { $0.box[0] < $1.box[0] }), crew.count == 3,
              let armstrong = seen["armstrong.ppm"]?.first, let aldrin = seen["aldrin.ppm"]?.first else {
            check(false, "the fixtures gave what the rest needs")
            return
        }

        // A library to work in.
        let scratch = FileManager.default.temporaryDirectory.appendingPathComponent("arsivinyo-faces-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: scratch) }
        let files = scratch.appendingPathComponent("files")
        try FileManager.default.createDirectory(at: files, withIntermediateDirectories: true)
        let keybox = Keybox(directory: scratch, params: .fast, keychainService: "com.arsivinyo.mac.checks.\(UUID().uuidString)")
        try keybox.create(passphrase: "a correct horse battery staple")
        defer { keybox.deleteKeychainItem() }
        let vault = Vault(root: scratch.appendingPathComponent("vault"), keybox: keybox)
        let deviceKey = try Crypto.randomBytes(32)
        func library(_ name: String) -> MemeLibrary {
            MemeLibrary(support: scratch.appendingPathComponent(name), vault: vault, keybox: keybox, deviceKey: { deviceKey })
        }
        var seed = 40
        func meme(_ memes: MemeLibrary, _ faces: [MemeLibrary.ScannedFace]) throws -> MemeLibrary.Item {
            seed += 1
            let url = files.appendingPathComponent("m\(seed).mp4")
            try Self.pattern(10_000 + seed, seed).write(to: url)
            let item = try memes.add(url, source: nil)
            try memes.record(faces: faces, for: item.id)
            return item
        }
        let memes = library("one")
        let crewMeme = try meme(memes, crew)
        let armstrongMeme = try meme(memes, [armstrong])
        _ = try meme(memes, [aldrin])

        // 2. Nobody is known yet: the faces wait in groups, the same person together.
        var snapshot = try memes.load()
        check(MemeLibrary.needingScan(snapshot).isEmpty, "a scanned meme is not scanned again")
        let groups = MemeLibrary.unnamedGroups(snapshot)
        check(groups.map(\.count).sorted(by: >) == [2, 2, 1], "unnamed faces group by person (\(groups.map(\.count)))")
        let armstrongGroup = groups.first { group in group.contains { $0.item.id == armstrongMeme.id } } ?? []
        check(Set(armstrongGroup.map(\.item.id)) == [crewMeme.id, armstrongMeme.id], "Armstrong's two faces are one group")

        // Naming the group labels both memes.
        let neil = try memes.name(faces: Set(armstrongGroup.map(\.face.id)), as: "Neil Armstrong")
        snapshot = try memes.load()
        check(snapshot.items.filter { $0.people.contains(neil.id) }.count == 2, "naming a group labels its memes")
        check((snapshot.people.first { $0.id == neil.id }?.signatures?.count ?? 0) == 2, "and teaches the person both faces")

        // 3. A new meme with that face is labelled on its own.
        let later = try meme(memes, [armstrong])
        snapshot = try memes.load()
        let laterItem = snapshot.items.first { $0.id == later.id }!
        check(laterItem.people == [neil.id] && laterItem.faces?.first?.state == .auto, "a new meme with a known face is labelled, sure")

        // A face only somewhat like him is asked about, not labelled.
        let borderline = Self.blend(armstrong.signature, crew[1].signature, towards: 0.43)
        let unsure = try meme(memes, [MemeLibrary.ScannedFace(signature: borderline, box: [0, 0, 50, 50], frameMs: 0)])
        snapshot = try memes.load()
        let unsureItem = snapshot.items.first { $0.id == unsure.id }!
        check(unsureItem.people.isEmpty && unsureItem.faces?.first?.state == .asked
              && MemeLibrary.asked(snapshot).map(\.item.id) == [unsure.id],
              String(format: "one only somewhat like him (%.2f) is asked about instead", FaceMath.cosine(borderline, armstrong.signature)))

        // 4. Rejecting an automatic label takes it off and it is not asked again.
        try memes.reject(face: laterItem.faces!.first!.id)
        snapshot = try memes.load()
        let rejected = snapshot.items.first { $0.id == later.id }!
        check(rejected.people.isEmpty && rejected.faces?.first?.state == .unnamed
              && rejected.faces?.first?.rejected == [neil.id], "rejecting an automatic label removes it, for good")
        try memes.absorb(signatures: ["Neil Armstrong": [FaceMath.encode(armstrong.signature)]])
        snapshot = try memes.load()
        check(snapshot.items.first { $0.id == later.id }!.people.isEmpty, "even when the person is taught again")

        // A label added by hand stays when its face is rejected.
        let byHand = try meme(memes, [])
        try memes.label([byHand.id], addPeople: [neil.id])
        try memes.record(faces: [armstrong], for: byHand.id)
        snapshot = try memes.load()
        let handFace = snapshot.items.first { $0.id == byHand.id }!.faces!.first!
        try memes.reject(face: handFace.id)
        snapshot = try memes.load()
        check(snapshot.items.first { $0.id == byHand.id }!.people == [neil.id], "a label added by hand stays")

        // Taking a person off by hand is a no to the face.
        let again = try meme(memes, [armstrong])
        try memes.label([again.id], removePeople: [neil.id])
        snapshot = try memes.load()
        let againItem = snapshot.items.first { $0.id == again.id }!
        check(againItem.people.isEmpty && againItem.faces?.first?.rejected == [neil.id], "removing an automatic label by hand rejects it")

        // Confirming a question labels the meme and teaches the face.
        try memes.confirm(face: unsureItem.faces!.first!.id)
        snapshot = try memes.load()
        check(snapshot.items.first { $0.id == unsure.id }!.people == [neil.id]
              && (snapshot.people.first { $0.id == neil.id }?.signatures?.count ?? 0) == 3, "confirming labels the meme and teaches the face")

        // 5. The other device: a person travels with their signatures and is recognised there
        // without being named.
        let object = try JSONSerialization.jsonObject(with: MemeTransfer.encode(
            item: armstrongMeme, tags: [], people: ["Neil Armstrong"],
            signatures: MemeLibrary.signatures(of: ["Neil Armstrong"], in: snapshot))!) as! [String: Any]
        let other = library("two")
        let waiting = try meme(other, [crew[0]])
        check(try other.load().items.first { $0.id == waiting.id }!.people.isEmpty, "on the other device, the face is unknown")
        try other.absorb(signatures: MemeTransfer.signatures(object))
        let there = try other.load()
        let recognised = there.items.first { $0.id == waiting.id }!
        check(recognised.people.compactMap { id in there.people.first { $0.id == id }?.name } == ["Neil Armstrong"],
              "a person sent from one device is recognised on the other, unnamed there")

        // 6. Nothing about a face in plain text; the indexes survive reopening.
        let indexBytes = try Data(contentsOf: scratch.appendingPathComponent("one/index.enc"))
        let someSignature = FaceMath.encode(armstrong.signature)
        check(indexBytes.range(of: Data(someSignature.prefix(24).utf8)) == nil, "the index holds no signature in plain text")
        let reopened = try library("one").load()
        check(reopened.items.flatMap { $0.faces ?? [] }.count == snapshot.items.flatMap { $0.faces ?? [] }.count,
              "faces survive reopening the index")
    }

    /// Runs the pipeline on a PPM fixture, as the host test does.
    static func lookAtPPM(_ url: URL, scanner: FaceScanner) throws -> [av_face_sighting] {
        let data = try Data(contentsOf: url)
        let header = String(decoding: data.prefix(32), as: UTF8.self).split(whereSeparator: \.isWhitespace)
        let width = Int(header[1])!, height = Int(header[2])!
        let start = data.count - width * height * 3
        var rgba = [UInt8](repeating: 255, count: width * height * 4)
        data.withUnsafeBytes { raw in
            let rgb = raw.bindMemory(to: UInt8.self)
            for i in 0..<(width * height) {
                rgba[i * 4] = rgb[start + i * 3]
                rgba[i * 4 + 1] = rgb[start + i * 3 + 1]
                rgba[i * 4 + 2] = rgb[start + i * 3 + 2]
            }
        }
        let provider = CGDataProvider(data: Data(rgba) as CFData)!
        let image = CGImage(width: width, height: height, bitsPerComponent: 8, bitsPerPixel: 32, bytesPerRow: width * 4,
                            space: CGColorSpace(name: CGColorSpace.sRGB)!,
                            bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.noneSkipLast.rawValue),
                            provider: provider, decode: nil, shouldInterpolate: false, intent: .defaultIntent)!
        return scanner.look(image, frameMs: 0)
    }

    /// A signature `target` similar to `a`, leaning towards `b`.
    static func blend(_ a: [Float], _ b: [Float], towards target: Float) -> [Float] {
        var lo: Float = 0, hi: Float = 1, out = a
        for _ in 0..<40 {
            let t = (lo + hi) / 2
            var mix = zip(a, b).map { (1 - t) * $0 + t * $1 }
            let length = sqrt(mix.reduce(0) { $0 + $1 * $1 })
            mix = mix.map { $0 / length }
            out = mix
            if FaceMath.cosine(mix, a) > target { lo = t } else { hi = t }
        }
        return out
    }
}
