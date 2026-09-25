import AVFoundation
import Foundation
import UniformTypeIdentifiers

/// Plays a vault item straight out of its encrypted file.
///
/// AVFoundation hands any URL with a scheme it does not recognise to a resource loader, and
/// that loader answers byte-range requests from the decrypting reader. So nothing is written
/// out in the clear, and there is no loopback port or token either — the phone needs those
/// only because Android's player takes nothing but a URL.
///
/// A player opens a file, jumps to the end for the container index, and comes back; that is
/// why the reader underneath is random access rather than a stream.
public final class VaultAssetLoader: NSObject, AVAssetResourceLoaderDelegate, @unchecked Sendable {

    /// The scheme AVFoundation does not know, so it asks us. Never resolvable on its own.
    public static let scheme = "arsivinyo-vault"

    private let reader: EncryptedReader
    private let contentType: String
    private let queue = DispatchQueue(label: "arsivinyo.vault.playback")

    /// One chunk per response. Answering a whole "to the end" request at once would mean a
    /// gigabyte in memory for a player that only wanted the first frames.
    private static let chunk = 512 * 1024

    public init(reader: EncryptedReader, contentType: String) {
        self.reader = reader
        self.contentType = contentType
    }

    /// An asset that reads through this loader. Keep the loader alive as long as the asset:
    /// AVFoundation holds its delegate weakly.
    public func makeAsset(id: String) -> AVURLAsset {
        let url = URL(string: "\(Self.scheme)://\(id)")!
        let asset = AVURLAsset(url: url)
        asset.resourceLoader.setDelegate(self, queue: queue)
        return asset
    }

    public func resourceLoader(
        _ resourceLoader: AVAssetResourceLoader,
        shouldWaitForLoadingOfRequestedResource request: AVAssetResourceLoadingRequest
    ) -> Bool {
        if let info = request.contentInformationRequest {
            info.contentType = contentType
            info.contentLength = reader.size
            // Seeking is the whole reason this is random access.
            info.isByteRangeAccessSupported = true
        }

        guard let dataRequest = request.dataRequest else {
            request.finishLoading()
            return true
        }

        let start = dataRequest.currentOffset != 0
            ? dataRequest.currentOffset
            : dataRequest.requestedOffset
        let end = dataRequest.requestsAllDataToEndOfResource
            ? reader.size
            : min(reader.size, dataRequest.requestedOffset + Int64(dataRequest.requestedLength))

        var offset = start
        do {
            while offset < end {
                if request.isCancelled { return true }
                let wanted = Int(min(Int64(Self.chunk), end - offset))
                let bytes = try reader.read(offset: offset, length: wanted)
                if bytes.isEmpty { break }
                dataRequest.respond(with: bytes)
                offset += Int64(bytes.count)
            }
            request.finishLoading()
        } catch {
            request.finishLoading(with: error)
        }
        return true
    }
}
