import ArsivinyoFacesC
import AVFoundation
import CoreGraphics
import CryptoKit
import Foundation
import ImageIO

/// Finds and recognises the faces in a meme: the shared/faces pipeline over its frames.
///
/// Frames are taken the same way for scanning and for showing a face afterwards
/// (`FaceScanner.frame`), so a stored box lands on the face it was found at.
public final class FaceScanner: @unchecked Sendable {

    public enum Failure: Error, CustomStringConvertible {
        case modelsMissing
        case modelMismatch(String)
        case load(String)

        public var description: String {
            switch self {
            case .modelsMissing: return String(localized: "The face models are missing.")
            case .modelMismatch(let file): return String(localized: "The face model \(file) is not the one this version expects.")
            case .load(let why): return why
            }
        }
    }

    /// The longest side a frame is taken at. Faces smaller than the pipeline's minimum at
    /// this size were too small to recognise anyway.
    public static let frameLimit = 1280

    private let handle: OpaquePointer
    private let lock = NSLock()

    /// The folder holding MODELS.json and models/: the app's resources when bundled, the
    /// repository's shared/faces while working from source.
    public static func modelsFolder(bundle: Bundle = .main, source: String = #filePath) -> URL? {
        if let bundled = bundle.resourceURL?.appendingPathComponent("faces"),
           FileManager.default.fileExists(atPath: bundled.appendingPathComponent("MODELS.json").path) {
            return bundled
        }
        var dir = URL(fileURLWithPath: source).deletingLastPathComponent()
        while true {
            let candidate = dir.appendingPathComponent("shared/faces")
            if FileManager.default.fileExists(atPath: candidate.appendingPathComponent("MODELS.json").path) { return candidate }
            let parent = dir.deletingLastPathComponent()
            if parent == dir { return nil }
            dir = parent
        }
    }

    /// Loads both models, refusing either if it is not the file MODELS.json pins: another
    /// model's signatures would match nothing already stored.
    public init(folder: URL) throws {
        guard let manifest = try? JSONSerialization.jsonObject(with: Data(contentsOf: folder.appendingPathComponent("MODELS.json")))
            as? [String: Any] else { throw Failure.modelsMissing }
        func model(_ key: String) throws -> Data {
            guard let entry = manifest[key] as? [String: Any], let file = entry["file"] as? String,
                  let expected = entry["sha256"] as? String,
                  let data = try? Data(contentsOf: folder.appendingPathComponent(file)) else { throw Failure.modelsMissing }
            let actual = SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
            guard actual == expected else { throw Failure.modelMismatch((file as NSString).lastPathComponent) }
            return data
        }
        let detector = try model("detector")
        let recogniser = try model("recogniser")
        var error = [CChar](repeating: 0, count: 512)
        let loaded = detector.withUnsafeBytes { d in
            recogniser.withUnsafeBytes { r in
                av_faces_load(d.bindMemory(to: UInt8.self).baseAddress, detector.count,
                              r.bindMemory(to: UInt8.self).baseAddress, recogniser.count, &error, error.count)
            }
        }
        guard let loaded else { throw Failure.load(String(cString: error)) }
        handle = loaded
    }

    deinit { av_faces_free(handle) }

    // MARK: - Looking

    /// Every face worth keeping in one frame.
    public func look(_ image: CGImage, frameMs: Int) -> [av_face_sighting] {
        guard let (pixels, width, height) = Self.rgba(image) else { return [] }
        var out = [av_face_sighting](repeating: av_face_sighting(), count: 32)
        let count = pixels.withUnsafeBufferPointer { buffer in
            lock.withLock {
                Int(av_faces_look(handle, buffer.baseAddress, Int32(width), Int32(height), Int32(width * 4), 0,
                                  Int32(frameMs), &out, Int32(out.count)))
            }
        }
        return Array(out.prefix(min(count, out.count)))
    }

    /// A meme's faces: its frames looked at, then the sightings of each person merged.
    public func scan(frames: [(image: CGImage, frameMs: Int)]) -> [MemeLibrary.ScannedFace] {
        let sightings = frames.flatMap { look($0.image, frameMs: $0.frameMs) }
        guard !sightings.isEmpty else { return [] }
        var merged = [av_meme_face](repeating: av_meme_face(), count: sightings.count)
        let n = Int(av_faces_merge(sightings, Int32(sightings.count), &merged, Int32(merged.count)))
        return merged.prefix(n).map { face in
            var face = face
            let signature = withUnsafeBytes(of: &face.signature) { Array($0.bindMemory(to: Float.self)) }
            return MemeLibrary.ScannedFace(signature: signature,
                                           box: [Double(face.x), Double(face.y), Double(face.w), Double(face.h)],
                                           frameMs: Int(face.frameMs))
        }
    }

    /// A file on disk.
    public func scan(file: URL, kind: String) async throws -> [MemeLibrary.ScannedFace] {
        if kind == "video" {
            return try await scan(asset: AVURLAsset(url: file))
        }
        guard let image = Self.image(from: CGImageSourceCreateWithURL(file as CFURL, nil)) else { return [] }
        return scan(frames: [(image, 0)])
    }

    /// An image held in memory, as a private one is read out of the vault.
    public func scan(imageData: Data) -> [MemeLibrary.ScannedFace] {
        guard let image = Self.image(from: CGImageSourceCreateWithData(imageData as CFData, nil)) else { return [] }
        return scan(frames: [(image, 0)])
    }

    public func scan(asset: AVAsset) async throws -> [MemeLibrary.ScannedFace] {
        let duration = try await asset.load(.duration)
        let ms = duration.isNumeric ? Int(duration.seconds * 1000) : 0
        var frames: [(CGImage, Int)] = []
        for time in FaceMath.sampleTimes(durationMs: ms) {
            if let image = try? await Self.frame(of: asset, at: time) { frames.append((image, time)) }
        }
        return scan(frames: frames)
    }

    // MARK: - Frames, the same way every time

    /// The frame of a video at a time, upright, at most frameLimit on its long side.
    public static func frame(of asset: AVAsset, at ms: Int) async throws -> CGImage {
        let generator = AVAssetImageGenerator(asset: asset)
        generator.appliesPreferredTrackTransform = true
        generator.maximumSize = CGSize(width: frameLimit, height: frameLimit)
        let tolerance = CMTime(value: 250, timescale: 1000)
        generator.requestedTimeToleranceBefore = tolerance
        generator.requestedTimeToleranceAfter = tolerance
        return try await generator.image(at: CMTime(value: CMTimeValue(ms), timescale: 1000)).image
    }

    /// An image upright, at most frameLimit on its long side.
    public static func image(from source: CGImageSource?) -> CGImage? {
        guard let source else { return nil }
        return CGImageSourceCreateThumbnailAtIndex(source, 0, [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceThumbnailMaxPixelSize: frameLimit,
            kCGImageSourceCreateThumbnailWithTransform: true,
        ] as CFDictionary)
    }

    static func rgba(_ image: CGImage) -> ([UInt8], Int, Int)? {
        let width = image.width, height = image.height
        guard width > 0, height > 0 else { return nil }
        var pixels = [UInt8](repeating: 0, count: width * height * 4)
        let drawn = pixels.withUnsafeMutableBytes { raw -> Bool in
            guard let context = CGContext(data: raw.baseAddress, width: width, height: height, bitsPerComponent: 8,
                                          bytesPerRow: width * 4, space: CGColorSpace(name: CGColorSpace.sRGB)!,
                                          bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue) else { return false }
            context.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
            return true
        }
        return drawn ? (pixels, width, height) : nil
    }

    /// A face cut out of its frame with some margin, for showing.
    public static func crop(_ image: CGImage, box: [Double], margin: Double = 0.35) -> CGImage? {
        guard box.count == 4 else { return nil }
        let side = max(box[2], box[3]) * (1 + margin * 2)
        let cx = box[0] + box[2] / 2, cy = box[1] + box[3] / 2
        let rect = CGRect(x: cx - side / 2, y: cy - side / 2, width: side, height: side)
            .intersection(CGRect(x: 0, y: 0, width: image.width, height: image.height))
        return rect.isEmpty ? nil : image.cropping(to: rect.integral)
    }
}
