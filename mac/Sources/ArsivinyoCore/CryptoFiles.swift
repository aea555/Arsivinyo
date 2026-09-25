import ArsivinyoCryptoC
import Foundation

// The parts of the core that work on key slots and on whole files.

extension Crypto {

    // MARK: - The key box

    public enum UnwrapResult: Equatable {
        case unwrapped(Data)
        /// The verifier did not match: a wrong passphrase, not a damaged file.
        case wrongSecret
        case damaged(String)
    }

    /// HKDF over a key file's contents. The file alone is not the key-encryption key, so
    /// rotating a slot's salt kills that slot without touching the file.
    public static func keyfileKEK(keyfile: Data, salt: Data) throws -> Data {
        var out = Data(count: 32)
        let ok = out.withUnsafeMutableBytes { outRaw in
            keyfile.withUnsafeBytes { fileRaw in
                salt.withUnsafeBytes { saltRaw in
                    av_keyfile_kek(fileRaw.bindMemory(to: UInt8.self).baseAddress, keyfile.count,
                                   saltRaw.bindMemory(to: UInt8.self).baseAddress, salt.count,
                                   outRaw.bindMemory(to: UInt8.self).baseAddress)
                }
            }
        }
        guard ok == 1 else { throw Failure() }
        return out
    }

    public static func wrapMasterKey(_ master: Data, kek: Data, slotId: String) throws
        -> (verifier: Data, wrapped: Data)
    {
        precondition(master.count == 32, "a master key is 32 bytes")
        var verifier = Data(count: Int(AV_VERIFIER_BYTES))
        var wrapped = Data(count: Int(AV_WRAPPED_BYTES))
        let ok = verifier.withUnsafeMutableBytes { verifierRaw in
            wrapped.withUnsafeMutableBytes { wrappedRaw in
                kek.withUnsafeBytes { kekRaw in
                    master.withUnsafeBytes { masterRaw in
                        av_keybox_wrap(
                            kekRaw.bindMemory(to: UInt8.self).baseAddress, kek.count,
                            masterRaw.bindMemory(to: UInt8.self).baseAddress, slotId,
                            verifierRaw.bindMemory(to: UInt8.self).baseAddress,
                            wrappedRaw.bindMemory(to: UInt8.self).baseAddress)
                    }
                }
            }
        }
        guard ok == 1 else { throw Failure() }
        return (verifier, wrapped)
    }

    public static func unwrapMasterKey(kek: Data, slotId: String, verifier: Data, wrapped: Data)
        -> UnwrapResult
    {
        guard verifier.count == Int(AV_VERIFIER_BYTES), wrapped.count == Int(AV_WRAPPED_BYTES)
        else { return .damaged("a stored key is the wrong size") }
        var master = Data(count: 32)
        let result = master.withUnsafeMutableBytes { masterRaw in
            kek.withUnsafeBytes { kekRaw in
                verifier.withUnsafeBytes { verifierRaw in
                    wrapped.withUnsafeBytes { wrappedRaw in
                        av_keybox_unwrap(
                            kekRaw.bindMemory(to: UInt8.self).baseAddress, kek.count, slotId,
                            verifierRaw.bindMemory(to: UInt8.self).baseAddress,
                            wrappedRaw.bindMemory(to: UInt8.self).baseAddress,
                            masterRaw.bindMemory(to: UInt8.self).baseAddress)
                    }
                }
            }
        }
        switch result {
        case 1: return .unwrapped(master)
        case 0: return .wrongSecret
        default: return .damaged(String(cString: av_last_error()))
        }
    }

    // MARK: - Whole files

    /// Encrypts a file into another, a megabyte at a time, so a gigabyte of video never
    /// has to be resident to be sealed.
    public static func encryptFile(_ source: URL, to destination: URL, key: Data,
                                   associatedData: String) throws {
        let ok = key.withUnsafeBytes { keyRaw in
            av_encrypt_file(source.path, destination.path,
                            keyRaw.bindMemory(to: UInt8.self).baseAddress, key.count,
                            associatedData)
        }
        guard ok == 1 else { throw Failure() }
    }

    public static func decryptFile(_ source: URL, to destination: URL, key: Data,
                                   associatedData: String) throws {
        let ok = key.withUnsafeBytes { keyRaw in
            av_decrypt_file(source.path, destination.path,
                            keyRaw.bindMemory(to: UInt8.self).baseAddress, key.count,
                            associatedData)
        }
        guard ok == 1 else { throw Failure() }
    }
}

/// Random access into an encrypted file, for playback.
///
/// A player opens a file, jumps to the end for the container's index, and comes back. This
/// answers those reads without ever writing the plaintext anywhere.
public final class EncryptedReader: @unchecked Sendable {
    private let handle: OpaquePointer
    private let lock = NSLock()
    public let size: Int64

    public init(url: URL, key: Data, associatedData: String) throws {
        let opened = key.withUnsafeBytes { keyRaw in
            av_reader_open(url.path, keyRaw.bindMemory(to: UInt8.self).baseAddress, key.count,
                           associatedData)
        }
        guard let opened else { throw Crypto.Failure() }
        handle = opened
        size = av_reader_size(opened)
    }

    deinit { av_reader_close(handle) }

    /// The C++ reader caches one decrypted segment and is not safe to share across threads
    /// without this; AVFoundation asks for ranges from more than one.
    public func read(offset: Int64, length: Int) throws -> Data {
        guard offset < size else { return Data() }
        var out = Data(count: min(length, Int(size - offset)))
        let got: Int64 = lock.withLock {
            out.withUnsafeMutableBytes { outRaw in
                av_reader_read(handle, UInt64(offset),
                               outRaw.bindMemory(to: UInt8.self).baseAddress, outRaw.count)
            }
        }
        guard got >= 0 else { throw Crypto.Failure() }
        return out.prefix(Int(got))
    }
}
