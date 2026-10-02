import Foundation
import Libmpv

/// A vault item for mpv, read through mpv's own stream callbacks: each read decrypts the
/// bytes asked for, in memory, so nothing in the clear is written and no port is opened.
/// A player opens a file, jumps to the end for the container index and comes back, which the
/// reader allows because it is random access.
public final class VaultStream: @unchecked Sendable {
    public static let scheme = "arsivinyo-vault"
    public static func url(id: String) -> String { "\(scheme)://\(id)" }

    private let reader: EncryptedReader

    public init(_ reader: EncryptedReader) {
        self.reader = reader
    }

    /// One open stream: where it is in the file.
    private final class Cursor {
        let reader: EncryptedReader
        var offset: Int64 = 0
        init(_ reader: EncryptedReader) { self.reader = reader }
    }

    /// Lets `handle` open `arsivinyo-vault://` URLs from this item. Keep the stream alive as
    /// long as the handle: mpv holds it unretained.
    public func register(with handle: OpaquePointer) {
        mpv_stream_cb_add_ro(handle, Self.scheme, Unmanaged.passUnretained(self).toOpaque(), Self.open)
    }

    private static let open: mpv_stream_cb_open_ro_fn = { context, _, info in
        guard let context, let info else { return MPV_ERROR_LOADING_FAILED.rawValue }
        let stream = Unmanaged<VaultStream>.fromOpaque(context).takeUnretainedValue()
        info.pointee.cookie = Unmanaged.passRetained(Cursor(stream.reader)).toOpaque()
        info.pointee.read_fn = { cookie, buffer, count in
            guard let cookie, let buffer else { return -1 }
            let cursor = Unmanaged<Cursor>.fromOpaque(cookie).takeUnretainedValue()
            let wanted = Int(min(UInt64(count), UInt64(max(0, cursor.reader.size - cursor.offset))))
            guard wanted > 0 else { return 0 }
            guard let bytes = try? cursor.reader.read(offset: cursor.offset, length: wanted) else { return -1 }
            bytes.copyBytes(to: UnsafeMutableRawBufferPointer(start: buffer, count: bytes.count).bindMemory(to: UInt8.self))
            cursor.offset += Int64(bytes.count)
            return Int64(bytes.count)
        }
        info.pointee.seek_fn = { cookie, offset in
            guard let cookie else { return Int64(MPV_ERROR_GENERIC.rawValue) }
            let cursor = Unmanaged<Cursor>.fromOpaque(cookie).takeUnretainedValue()
            guard offset >= 0, offset <= cursor.reader.size else { return Int64(MPV_ERROR_GENERIC.rawValue) }
            cursor.offset = offset
            return offset
        }
        info.pointee.size_fn = { cookie in
            guard let cookie else { return Int64(MPV_ERROR_UNSUPPORTED.rawValue) }
            return Unmanaged<Cursor>.fromOpaque(cookie).takeUnretainedValue().reader.size
        }
        info.pointee.close_fn = { cookie in
            if let cookie { Unmanaged<Cursor>.fromOpaque(cookie).release() }
        }
        return 0
    }
}
