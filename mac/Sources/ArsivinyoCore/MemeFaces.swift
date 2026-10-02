import ArsivinyoFacesC
import Foundation

/// The faces pipeline's arithmetic, from shared/faces: the same C++ the phone runs, so a
/// signature and a match mean the same on both.
public enum FaceMath {
    public static let size = Int(AV_FACES_SIGNATURE)
    public static var sure: Float { av_faces_sure() }
    public static var ask: Float { av_faces_ask() }
    public static var pipelineVersion: Int { Int(av_faces_pipeline_version()) }

    public static func encode(_ signature: [Float]) -> String {
        var bytes = [UInt8](repeating: 0, count: size * 2)
        signature.withUnsafeBufferPointer { av_faces_encode($0.baseAddress, &bytes) }
        return Data(bytes).base64EncodedString()
    }

    public static func decode(_ text: String) -> [Float]? {
        guard let data = Data(base64Encoded: text) else { return nil }
        var signature = [Float](repeating: 0, count: size)
        let ok = data.withUnsafeBytes { raw in
            av_faces_decode(raw.bindMemory(to: UInt8.self).baseAddress, data.count, &signature)
        }
        return ok == 1 ? signature : nil
    }

    /// The highest cosine against any of `set`; -1 for an empty set.
    public static func best(_ signature: [Float], in set: [[Float]]) -> Float {
        guard !set.isEmpty else { return -1 }
        let flat = set.flatMap { $0 }
        return av_faces_best(signature, flat, Int32(set.count))
    }

    public static func cosine(_ a: [Float], _ b: [Float]) -> Float { av_faces_cosine(a, b) }

    /// A person's set with one more in it, at most eight, spread out.
    public static func add(_ signature: [Float], to set: [[Float]]) -> [[Float]] {
        var flat = [Float](repeating: 0, count: size * (Int(AV_FACES_MAX_SET) + 1))
        for (i, s) in set.prefix(Int(AV_FACES_MAX_SET)).enumerated() { flat.replaceSubrange(i * size..<(i + 1) * size, with: s) }
        let count = Int(av_faces_add_to_set(&flat, Int32(min(set.count, Int(AV_FACES_MAX_SET))), signature))
        return (0..<count).map { Array(flat[$0 * size..<($0 + 1) * size]) }
    }

    public static func sampleTimes(durationMs: Int) -> [Int] {
        var out = [Int32](repeating: 0, count: Int(AV_FACES_MAX_FRAMES))
        let n = Int(av_faces_sample_times(Int32(clamping: durationMs), &out, Int32(AV_FACES_MAX_FRAMES)))
        return out.prefix(n).map(Int.init)
    }
}

extension MemeLibrary {

    /// A face as a scan found it, before it is matched against anyone.
    public struct ScannedFace: Sendable {
        public var signature: [Float]
        public var box: [Double]
        public var frameMs: Int

        public init(signature: [Float], box: [Double], frameMs: Int) {
            self.signature = signature
            self.box = box
            self.frameMs = frameMs
        }
    }

    /// A face with the meme it is in, for the screens.
    public struct FaceRef: Hashable, Sendable, Identifiable {
        public var item: Item
        public var face: Face
        public var id: String { face.id }

        public init(item: Item, face: Face) {
            self.item = item
            self.face = face
        }
    }

    /// The faces showing a person: confirmed or labelled on their own.
    public static func faces(of personId: String, in snapshot: Snapshot) -> [FaceRef] {
        snapshot.items.flatMap { item in
            (item.faces ?? []).filter { $0.person == personId && ($0.state == .confirmed || $0.state == .auto) }
                .map { FaceRef(item: item, face: $0) }
        }
    }

    // MARK: - Scanning

    /// Memes still to scan, or scanned by an older pipeline.
    public static func needingScan(_ snapshot: Snapshot) -> [Item] {
        snapshot.items.filter { ($0.facesVersion ?? 0) < FaceMath.pipelineVersion }
    }

    /// Records what a scan found, matching each face against the people known.
    public func record(faces scanned: [ScannedFace], for itemId: String) throws {
        try mutate { all in
            guard let index = all.items.firstIndex(where: { $0.id == itemId }) else { return }
            let people = Self.signatureSets(all)
            // A rescan keeps what the user said: a face at the same place in the same frame
            // with the same signature is the same face, and keeps its answers.
            let previous = all.items[index].faces ?? []
            var faces: [Face] = []
            for scan in scanned {
                if let kept = previous.first(where: { face in
                    face.frameMs == scan.frameMs && FaceMath.decode(face.signature).map { FaceMath.cosine($0, scan.signature) >= 0.99 } == true
                }), kept.state == .confirmed {
                    faces.append(kept)
                    continue
                }
                var face = Face(id: Self.newId("f"), signature: FaceMath.encode(scan.signature), frameMs: scan.frameMs,
                                box: scan.box, person: nil, state: .unnamed)
                face.rejected = previous.first { $0.frameMs == scan.frameMs && $0.rejected != nil }?.rejected
                Self.classify(&face, signature: scan.signature, against: people)
                faces.append(face)
            }
            all.items[index].faces = faces
            all.items[index].facesVersion = FaceMath.pipelineVersion
            Self.applyAutomatic(to: &all.items[index])
        }
    }

    // MARK: - Answers

    /// "Is this X?" — yes. The face joins the person's signatures.
    public func confirm(face faceId: String) throws {
        try mutate { all in
            guard let (i, f) = Self.locate(faceId, in: all), let person = all.items[i].faces?[f].person else { return }
            all.items[i].faces?[f].state = .confirmed
            Self.labelFrom(&all.items[i], face: f, person: person)
            Self.learn(all.items[i].faces?[f].signature, for: person, in: &all)
            Self.reevaluate(&all)
        }
    }

    /// Not this person. It is never asked about them again; a label it added goes.
    public func reject(face faceId: String) throws {
        try mutate { all in
            guard let (i, f) = Self.locate(faceId, in: all) else { return }
            Self.unlabel(&all.items[i], face: f)
            Self.reevaluate(&all)
        }
    }

    /// Names faces: an unnamed group, or a single face. The faces are confirmed as the
    /// person, their signatures learnt, and every other face looked at again.
    @discardableResult
    public func name(faces faceIds: Set<String>, as name: String) throws -> Person {
        try mutate { all in
            let personId = Self.resolvePerson(name, in: &all)
            for i in all.items.indices {
                guard let faces = all.items[i].faces else { continue }
                for f in faces.indices where faceIds.contains(faces[f].id) {
                    all.items[i].faces?[f].person = personId
                    all.items[i].faces?[f].state = .confirmed
                    all.items[i].faces?[f].rejected?.removeAll { $0 == personId }
                    Self.labelFrom(&all.items[i], face: f, person: personId)
                    Self.learn(faces[f].signature, for: personId, in: &all)
                }
            }
            Self.reevaluate(&all)
            return all.people.first { $0.id == personId }!
        }
    }

    /// Faces waiting for a yes or no, newest meme first.
    public static func asked(_ snapshot: Snapshot) -> [FaceRef] {
        snapshot.items.flatMap { item in
            (item.faces ?? []).filter { $0.state == .asked }.map { FaceRef(item: item, face: $0) }
        }
    }

    /// The unnamed groups as stored, largest first.
    public static func unnamedGroups(_ snapshot: Snapshot) -> [[FaceRef]] {
        var order: [String] = []
        var groups: [String: [FaceRef]] = [:]
        for item in snapshot.items {
            for face in item.faces ?? [] where face.state == .unnamed {
                guard let group = face.group else { continue }
                if groups[group] == nil { order.append(group) }
                groups[group, default: []].append(FaceRef(item: item, face: face))
            }
        }
        // Stable: equal sizes keep the order they were first seen in.
        return order.enumerated().sorted { a, b in
            let (x, y) = (groups[a.element]!.count, groups[b.element]!.count)
            return x != y ? x > y : a.offset < b.offset
        }.map { groups[$0.element]! }
    }

    /// Every unnamed face in a group, in one pass: the group whose mean signature it is most
    /// like, at or above sure, or a new one. A group already decided stays; a face that has a
    /// person leaves its group. Nothing here compares faces pairwise, so keeping the groups
    /// costs a pass over the faces, and showing them costs nothing.
    static func assignGroups(_ items: inout [Item]) {
        func needsWork(_ face: Face) -> Bool { (face.state == .unnamed) == (face.group == nil) }
        guard items.contains(where: { ($0.faces ?? []).contains(where: needsWork) }) else { return }
        var order: [String] = []
        var sums: [String: [Float]] = [:]
        for item in items {
            for face in item.faces ?? [] where face.state == .unnamed {
                guard let group = face.group, let signature = FaceMath.decode(face.signature) else { continue }
                if sums[group] == nil { order.append(group); sums[group] = [Float](repeating: 0, count: signature.count) }
                for k in signature.indices { sums[group]![k] += signature[k] }
            }
        }
        for i in items.indices {
            guard var faces = items[i].faces, faces.contains(where: needsWork) else { continue }
            for f in faces.indices {
                if faces[f].state != .unnamed { faces[f].group = nil; continue }
                guard faces[f].group == nil, let signature = FaceMath.decode(faces[f].signature) else { continue }
                var best: String?
                var bestScore = FaceMath.sure
                for id in order {
                    let score = cosineToMean(signature, sums[id]!)
                    if score >= bestScore && (best == nil || score > bestScore) { best = id; bestScore = score }
                }
                let group = best ?? newId("g")
                if sums[group] == nil { order.append(group); sums[group] = [Float](repeating: 0, count: signature.count) }
                for k in signature.indices { sums[group]![k] += signature[k] }
                faces[f].group = group
            }
            items[i].faces = faces
        }
    }

    private static func cosineToMean(_ signature: [Float], _ sum: [Float]) -> Float {
        var dot = 0.0, length = 0.0
        for k in signature.indices {
            dot += Double(signature[k] * sum[k])
            length += Double(sum[k]) * Double(sum[k])
        }
        return length > 0 ? Float(dot / length.squareRoot()) : -1
    }

    /// A person's signatures, by name, for sending: they travel with the person.
    public static func signatures(of people: [String], in snapshot: Snapshot) -> [String: [String]] {
        var out: [String: [String]] = [:]
        for name in people {
            if let person = snapshot.people.first(where: { $0.name == name }), let set = person.signatures, !set.isEmpty {
                out[name] = set
            }
        }
        return out
    }

    // MARK: - People

    /// A person under a new name. If the name is someone else's already, the two are one
    /// person: their labels, faces and signatures come together under that one.
    public func rename(person personId: String, to name: String) throws {
        let trimmed = String(name.trimmingCharacters(in: .whitespacesAndNewlines).prefix(80))
        guard !trimmed.isEmpty else { return }
        try mutate { all in
            guard let at = all.people.firstIndex(where: { $0.id == personId }) else { return }
            if let same = all.people.first(where: { $0.id != personId && Self.fold($0.name) == Self.fold(trimmed) }) {
                Self.merge(person: personId, into: same.id, in: &all)
            } else {
                all.people[at].name = trimmed
            }
        }
    }

    /// A person gone: their label comes off every meme, and their faces are unnamed again, to
    /// be grouped and named afresh. What they were told they are not is forgotten with them.
    public func delete(person personId: String) throws {
        try mutate { all in
            all.people.removeAll { $0.id == personId }
            for i in all.items.indices {
                all.items[i].people.removeAll { $0 == personId }
                guard var faces = all.items[i].faces else { continue }
                for f in faces.indices {
                    if faces[f].person == personId {
                        faces[f].person = nil
                        faces[f].state = .unnamed
                        faces[f].added = nil
                        faces[f].group = nil
                    }
                    faces[f].rejected?.removeAll { $0 == personId }
                }
                all.items[i].faces = faces
            }
            Self.reevaluate(&all)
        }
    }

    static func merge(person from: String, into: String, in all: inout Index) {
        let signatures = all.people.first { $0.id == from }?.signatures ?? []
        for i in all.items.indices {
            all.items[i].people = Array(Set(all.items[i].people.map { $0 == from ? into : $0 }))
            guard var faces = all.items[i].faces else { continue }
            for f in faces.indices {
                if faces[f].person == from { faces[f].person = into }
                if let rejected = faces[f].rejected { faces[f].rejected = Array(Set(rejected.map { $0 == from ? into : $0 })).sorted() }
            }
            all.items[i].faces = faces
        }
        signatures.forEach { learn($0, for: into, in: &all) }
        all.people.removeAll { $0.id == from }
        reevaluate(&all)
    }

    // MARK: - Arriving from elsewhere

    /// Merges signatures that came with people by name, then looks at every face again: a
    /// person named on the other device is recognised here.
    public func absorb(signatures: [String: [String]]) throws {
        guard !signatures.isEmpty else { return }
        try mutate { all in
            Self.absorb(signatures, into: &all)
            Self.reevaluate(&all)
        }
    }

    static func absorb(_ signatures: [String: [String]], into all: inout Index) {
        for (name, set) in signatures {
            let personId = resolvePerson(name, in: &all)
            for text in set { learn(text, for: personId, in: &all) }
        }
    }

    // MARK: - The rules

    static func signatureSets(_ all: Index) -> [String: [[Float]]] {
        var out: [String: [[Float]]] = [:]
        for person in all.people {
            let set = (person.signatures ?? []).compactMap(FaceMath.decode)
            if !set.isEmpty { out[person.id] = set }
        }
        return out
    }

    /// Sure: auto. Close: asked. Otherwise unnamed. Never someone it was said not to be.
    static func classify(_ face: inout Face, signature: [Float], against people: [String: [[Float]]]) {
        let excluded = Set(face.rejected ?? [])
        var best: (id: String, score: Float)?
        for (id, set) in people where !excluded.contains(id) {
            let score = FaceMath.best(signature, in: set)
            // Ties by id, so both apps pick the same person from the same state.
            if best == nil || score > best!.score || (score == best!.score && id < best!.id) { best = (id, score) }
        }
        if let best, best.score >= FaceMath.sure {
            face.person = best.id
            face.state = .auto
        } else if let best, best.score >= FaceMath.ask {
            face.person = best.id
            face.state = .asked
        } else {
            face.person = nil
            face.state = .unnamed
        }
    }

    /// Every face not already settled by the user, looked at again against the people now
    /// known. Automatic labels that no longer hold are taken back.
    static func reevaluate(_ all: inout Index) {
        let people = signatureSets(all)
        for i in all.items.indices {
            guard var faces = all.items[i].faces, !faces.isEmpty else { continue }
            for f in faces.indices where faces[f].state != .confirmed {
                guard let signature = FaceMath.decode(faces[f].signature) else { continue }
                let before = faces[f]
                classify(&faces[f], signature: signature, against: people)
                if before.state == .auto, before.added == true, faces[f].person != before.person || faces[f].state != .auto {
                    faces[f].added = nil
                    let stillShown = faces.enumerated().contains { $0.offset != f && $0.element.person == before.person
                        && ($0.element.state == .auto || $0.element.state == .confirmed) }
                    if !stillShown, let person = before.person { all.items[i].people.removeAll { $0 == person } }
                }
            }
            all.items[i].faces = faces
            applyAutomatic(to: &all.items[i])
        }
    }

    /// Puts the person of every automatic face on the meme, remembering which it added.
    static func applyAutomatic(to item: inout Item) {
        guard let faces = item.faces else { return }
        for f in faces.indices where faces[f].state == .auto {
            if let person = faces[f].person { labelFrom(&item, face: f, person: person) }
        }
    }

    static func labelFrom(_ item: inout Item, face f: Int, person: String) {
        if !item.people.contains(person) {
            item.people.append(person)
            item.faces?[f].added = true
        }
    }

    /// Not this person: remembered, and the label goes if this face put it there and no
    /// other face of theirs holds it.
    static func unlabel(_ item: inout Item, face f: Int) {
        guard var face = item.faces?[f], let person = face.person else { return }
        face.rejected = Array(Set((face.rejected ?? []) + [person])).sorted()
        let added = face.added == true
        face.person = nil
        face.state = .unnamed
        face.added = nil
        item.faces?[f] = face
        let stillShown = (item.faces ?? []).contains { $0.person == person && ($0.state == .auto || $0.state == .confirmed) }
        if added && !stillShown { item.people.removeAll { $0 == person } }
    }

    /// A label taken off by hand is a "no" to every face that gave it.
    static func rejectFaces(of removed: Set<String>, in item: inout Item) {
        guard let faces = item.faces else { return }
        for f in faces.indices {
            if let person = faces[f].person, removed.contains(person), faces[f].state != .unnamed {
                var face = faces[f]
                face.rejected = Array(Set((face.rejected ?? []) + [person])).sorted()
                face.person = nil
                face.state = .unnamed
                face.added = nil
                item.faces?[f] = face
            }
        }
    }

    static func learn(_ signature: String?, for personId: String, in all: inout Index) {
        guard let signature, let decoded = FaceMath.decode(signature),
              let p = all.people.firstIndex(where: { $0.id == personId }) else { return }
        let set = (all.people[p].signatures ?? []).compactMap(FaceMath.decode)
        // The same signature twice would crowd the set without teaching it anything.
        if set.contains(where: { FaceMath.cosine($0, decoded) > 0.999 }) { return }
        all.people[p].signatures = FaceMath.add(decoded, to: set).map(FaceMath.encode)
    }

    static func locate(_ faceId: String, in all: Index) -> (Int, Int)? {
        for i in all.items.indices {
            if let f = all.items[i].faces?.firstIndex(where: { $0.id == faceId }) { return (i, f) }
        }
        return nil
    }
}
