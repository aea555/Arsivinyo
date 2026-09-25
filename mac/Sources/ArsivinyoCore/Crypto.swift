import ArsivinyoCryptoC
import Foundation

/// The security core, as Swift.
///
/// Every operation here runs the same C++ the Android app is checked against, reached
/// through a thin C boundary. Nothing is reimplemented: a second implementation of these
/// formats would mean a disagreement costs an unopenable vault, and `VECTORS.json` is what
/// keeps the two honest.
public enum Crypto {

    public struct Failure: Error, CustomStringConvertible {
        public let description: String
        init() { description = String(cString: av_last_error()) }
        init(_ message: String) { description = message }
    }

    // MARK: - Randomness

    /// Throws rather than returning predictable bytes. A caller that ignores a failed RNG
    /// ships a key of zeroes and the app works perfectly.
    public static func randomBytes(_ count: Int) throws -> Data {
        var out = Data(count: count)
        let ok = out.withUnsafeMutableBytes { raw in
            av_random(raw.bindMemory(to: UInt8.self).baseAddress, count)
        }
        guard ok == 1 else { throw Failure() }
        return out
    }

    // MARK: - Key derivation

    /// The parameters live in the file that was written, never in the code, or a future
    /// build cannot open today's data.
    public struct Argon2idParams: Sendable, Equatable {
        public var memoryKiB: UInt32
        public var iterations: UInt32
        public var parallelism: UInt32
        public var version: UInt32

        /// What the phone writes. Changing these silently weakens every keybox written after.
        public static let shipped = Argon2idParams(
            memoryKiB: 65536, iterations: 3, parallelism: 4, version: 0x13)

        /// Cheap, for tests only. There is deliberately no way to reach this from the app.
        public static let fast = Argon2idParams(
            memoryKiB: 8192, iterations: 1, parallelism: 4, version: 0x13)

        public init(memoryKiB: UInt32, iterations: UInt32, parallelism: UInt32, version: UInt32) {
            self.memoryKiB = memoryKiB
            self.iterations = iterations
            self.parallelism = parallelism
            self.version = version
        }
    }

    /// `secret` is encoded UTF-8, because that is what the Android side hashes. Anything
    /// else derives a different key and shows up as "my backup will not open on my Mac".
    public static func argon2id(
        secret: Data, salt: Data, params: Argon2idParams = .shipped, outputCount: Int = 32
    ) throws -> Data {
        var out = Data(count: outputCount)
        let ok = out.withUnsafeMutableBytes { outRaw in
            secret.withUnsafeBytes { secretRaw in
                salt.withUnsafeBytes { saltRaw in
                    av_argon2id(
                        secretRaw.bindMemory(to: UInt8.self).baseAddress, secret.count,
                        saltRaw.bindMemory(to: UInt8.self).baseAddress, salt.count,
                        params.memoryKiB, params.iterations, params.parallelism, params.version,
                        outRaw.bindMemory(to: UInt8.self).baseAddress, outputCount)
                }
            }
        }
        guard ok == 1 else { throw Failure() }
        return out
    }

    /// A nil `salt` means Tink's null: a zero-filled block of the digest length.
    public static func hkdfSHA256(
        ikm: Data, salt: Data? = nil, info: Data, outputCount: Int = 32
    ) throws -> Data {
        var out = Data(count: outputCount)
        let saltBytes = salt ?? Data()
        let ok = out.withUnsafeMutableBytes { outRaw in
            ikm.withUnsafeBytes { ikmRaw in
                info.withUnsafeBytes { infoRaw in
                    saltBytes.withUnsafeBytes { saltRaw in
                        av_hkdf_sha256(
                            ikmRaw.bindMemory(to: UInt8.self).baseAddress, ikm.count,
                            salt == nil ? nil : saltRaw.bindMemory(to: UInt8.self).baseAddress,
                            salt == nil ? 0 : saltBytes.count,
                            infoRaw.bindMemory(to: UInt8.self).baseAddress, info.count,
                            outRaw.bindMemory(to: UInt8.self).baseAddress, outputCount)
                    }
                }
            }
        }
        guard ok == 1 else { throw Failure() }
        return out
    }

    /// What the master key is spent on. A leaked cookie key must not open the vault.
    public enum Purpose: String, Sendable {
        case cookies
        case vault
        case vaultIndex = "vault-index"
        case thumbs
    }

    public static func purposeKey(master: Data, purpose: Purpose) throws -> Data {
        try derive32(master: master) { masterPtr, count, out in
            av_purpose_key(masterPtr, count, purpose.rawValue, out)
        }
    }

    /// Sections of a `.avsbck` backup: vault, music, settings, cookies.
    public static func backupSectionKey(master: Data, sectionId: String) throws -> Data {
        try derive32(master: master) { masterPtr, count, out in
            av_backup_section_key(masterPtr, count, sectionId, out)
        }
    }

    public static func backupVerifier(master: Data) throws -> Data {
        try derive32(master: master) { masterPtr, count, out in
            av_backup_verifier(masterPtr, count, out)
        }
    }

    private static func derive32(
        master: Data,
        _ body: (UnsafePointer<UInt8>?, Int, UnsafeMutablePointer<UInt8>?) -> Int32
    ) throws -> Data {
        var out = Data(count: 32)
        let ok = out.withUnsafeMutableBytes { outRaw in
            master.withUnsafeBytes { masterRaw in
                body(masterRaw.bindMemory(to: UInt8.self).baseAddress, master.count,
                     outRaw.bindMemory(to: UInt8.self).baseAddress)
            }
        }
        guard ok == 1 else { throw Failure() }
        return out
    }

    /// For verifiers and tokens. Comparing those with `==` leaks by timing.
    public static func constantTimeEquals(_ a: Data, _ b: Data) -> Bool {
        a.withUnsafeBytes { aRaw in
            b.withUnsafeBytes { bRaw in
                av_constant_time_equals(
                    aRaw.bindMemory(to: UInt8.self).baseAddress, a.count,
                    bRaw.bindMemory(to: UInt8.self).baseAddress, b.count) == 1
            }
        }
    }

    // MARK: - The streaming AEAD

    /// Seals a whole buffer. For anything large, stream it instead of holding it in memory.
    public static func seal(_ plaintext: Data, key: Data, associatedData: String) throws -> Data {
        var out = Data(count: av_aead_sealed_length(plaintext.count))
        let ok = out.withUnsafeMutableBytes { outRaw in
            key.withUnsafeBytes { keyRaw in
                plaintext.withUnsafeBytes { ptRaw in
                    av_aead_seal(
                        keyRaw.bindMemory(to: UInt8.self).baseAddress, key.count, associatedData,
                        ptRaw.bindMemory(to: UInt8.self).baseAddress, plaintext.count,
                        outRaw.bindMemory(to: UInt8.self).baseAddress)
                }
            }
        }
        guard ok == 1 else { throw Failure() }
        return out
    }

    public static func open(_ ciphertext: Data, key: Data, associatedData: String) throws -> Data {
        let length = av_aead_opened_length(UInt64(ciphertext.count))
        guard length >= 0 else { throw Failure("this is not an encrypted stream") }
        var out = Data(count: Int(length))
        let ok = out.withUnsafeMutableBytes { outRaw in
            key.withUnsafeBytes { keyRaw in
                ciphertext.withUnsafeBytes { ctRaw in
                    av_aead_open(
                        keyRaw.bindMemory(to: UInt8.self).baseAddress, key.count, associatedData,
                        ctRaw.bindMemory(to: UInt8.self).baseAddress, ciphertext.count,
                        outRaw.bindMemory(to: UInt8.self).baseAddress)
                }
            }
        }
        guard ok == 1 else { throw Failure() }
        return out
    }

    /// Testing only: reproduces a recorded ciphertext byte for byte, which is the check
    /// that this implementation and Tink's agree in both directions rather than just one.
    public static func sealWithRecordedHeader(
        _ plaintext: Data, key: Data, associatedData: String, headerSalt: Data, noncePrefix: Data
    ) throws -> Data {
        precondition(headerSalt.count == 32 && noncePrefix.count == 7)
        var out = Data(count: av_aead_sealed_length(plaintext.count))
        let ok = out.withUnsafeMutableBytes { outRaw in
            key.withUnsafeBytes { keyRaw in
                headerSalt.withUnsafeBytes { saltRaw in
                    noncePrefix.withUnsafeBytes { prefixRaw in
                        plaintext.withUnsafeBytes { ptRaw in
                            av_aead_seal_with_header(
                                keyRaw.bindMemory(to: UInt8.self).baseAddress, key.count,
                                associatedData,
                                saltRaw.bindMemory(to: UInt8.self).baseAddress,
                                prefixRaw.bindMemory(to: UInt8.self).baseAddress,
                                ptRaw.bindMemory(to: UInt8.self).baseAddress, plaintext.count,
                                outRaw.bindMemory(to: UInt8.self).baseAddress)
                        }
                    }
                }
            }
        }
        guard ok == 1 else { throw Failure() }
        return out
    }

    // MARK: - Concealment padding

    /// Pads so a sealed index's size does not say roughly how much is in the vault.
    public static func pad(_ content: Data) -> Data {
        var out = Data(count: av_pad_length(content.count))
        out.withUnsafeMutableBytes { outRaw in
            content.withUnsafeBytes { contentRaw in
                av_pad(contentRaw.bindMemory(to: UInt8.self).baseAddress, content.count,
                       outRaw.bindMemory(to: UInt8.self).baseAddress)
            }
        }
        return out
    }

    public static func unpad(_ padded: Data) throws -> Data {
        var out = Data(count: padded.count)
        let length = out.withUnsafeMutableBytes { outRaw in
            padded.withUnsafeBytes { paddedRaw in
                av_unpad(paddedRaw.bindMemory(to: UInt8.self).baseAddress, padded.count,
                         outRaw.bindMemory(to: UInt8.self).baseAddress)
            }
        }
        guard length >= 0 else { throw Failure() }
        return out.prefix(Int(length))
    }
}
