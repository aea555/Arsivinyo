#include "aead_stream.h"

#include <algorithm>
#include <cstring>

#include "gcm.h"
#include "hkdf.h"

namespace arsivinyo::crypto {

namespace {

bool DeriveStreamKey(const uint8_t *key, std::size_t keyLength, const uint8_t *headerSalt,
                     const std::string &associatedData, SecretBytes *out, std::string *error) {
    return HkdfSha256(key, keyLength, headerSalt, kHeaderSaltBytes,
                      reinterpret_cast<const uint8_t *>(associatedData.data()),
                      associatedData.size(), kStreamKeyBytes, out, error);
}

/** Where segment `index` begins in the file. Segment 0 sits behind the header. */
uint64_t SegmentFileStart(uint64_t index) {
    return index * kCiphertextSegmentBytes + (index == 0 ? kHeaderBytes : 0);
}

/** Plaintext offset at which segment `index` begins. */
uint64_t SegmentPlaintextStart(uint64_t index) {
    if (index == 0) return 0;
    return PlaintextSegmentBytes(0) + (index - 1) * PlaintextSegmentBytes(1);
}

}  // namespace

std::size_t PlaintextSegmentBytes(uint64_t segmentIndex) {
    return segmentIndex == 0 ? kCiphertextSegmentBytes - kHeaderBytes - kTagBytes
                             : kCiphertextSegmentBytes - kTagBytes;
}

uint64_t PlaintextSizeFor(uint64_t ciphertextSize, bool *valid) {
    const auto reject = [valid]() {
        if (valid) *valid = false;
        return uint64_t{0};
    };
    if (ciphertextSize < kHeaderBytes + kTagBytes) return reject();
    const uint64_t segments =
        (ciphertextSize + kCiphertextSegmentBytes - 1) / kCiphertextSegmentBytes;
    const uint64_t overhead = kHeaderBytes + segments * kTagBytes;
    if (ciphertextSize < overhead) return reject();
    if (valid) *valid = true;
    return ciphertextSize - overhead;
}

void SegmentNonce(const uint8_t *noncePrefix, uint64_t segmentIndex, bool last, uint8_t out[12]) {
    std::memcpy(out, noncePrefix, kNoncePrefixBytes);
    const uint32_t index = static_cast<uint32_t>(segmentIndex);
    out[7] = static_cast<uint8_t>((index >> 24) & 0xff);
    out[8] = static_cast<uint8_t>((index >> 16) & 0xff);
    out[9] = static_cast<uint8_t>((index >> 8) & 0xff);
    out[10] = static_cast<uint8_t>(index & 0xff);
    out[11] = last ? 1 : 0;
}

// ---- StreamEncryptor --------------------------------------------------------------------

StreamEncryptor::~StreamEncryptor() {
    if (!m_buffer.empty()) SecureWipe(m_buffer.data(), m_buffer.size());
}

std::unique_ptr<StreamEncryptor> StreamEncryptor::Create(const uint8_t *key,
                                                         std::size_t keyLength,
                                                         const std::string &associatedData,
                                                         Sink sink, std::string *error) {
    Bytes salt;
    Bytes prefix;
    if (!RandomBytes(kHeaderSaltBytes, &salt, error)) return nullptr;
    if (!RandomBytes(kNoncePrefixBytes, &prefix, error)) return nullptr;
    return CreateWithHeader(key, keyLength, associatedData, salt.data(), prefix.data(),
                            std::move(sink), error);
}

std::unique_ptr<StreamEncryptor> StreamEncryptor::CreateWithHeader(
    const uint8_t *key, std::size_t keyLength, const std::string &associatedData,
    const uint8_t *headerSalt, const uint8_t *noncePrefix, Sink sink, std::string *error) {
    if (sink == nullptr) {
        if (error) *error = "an encryptor needs somewhere to write";
        return nullptr;
    }
    std::unique_ptr<StreamEncryptor> encryptor(new StreamEncryptor());
    if (!DeriveStreamKey(key, keyLength, headerSalt, associatedData, &encryptor->m_streamKey,
                         error)) {
        return nullptr;
    }
    std::memcpy(encryptor->m_noncePrefix, noncePrefix, kNoncePrefixBytes);
    encryptor->m_sink = std::move(sink);

    uint8_t header[kHeaderBytes];
    header[0] = static_cast<uint8_t>(kHeaderBytes);
    std::memcpy(header + 1, headerSalt, kHeaderSaltBytes);
    std::memcpy(header + 1 + kHeaderSaltBytes, noncePrefix, kNoncePrefixBytes);
    if (!encryptor->m_sink(header, kHeaderBytes)) {
        if (error) *error = "could not write the stream header";
        return nullptr;
    }
    return encryptor;
}

bool StreamEncryptor::EmitSegment(const uint8_t *plain, std::size_t length, bool last,
                                  std::string *error) {
    Bytes segment(length + kTagBytes);
    uint8_t nonce[12];
    SegmentNonce(m_noncePrefix, m_segmentIndex, last, nonce);
    if (!GcmSeal(m_streamKey.data(), nonce, nullptr, 0, plain, length, segment.data(), error)) return false;
    if (!m_sink(segment.data(), segment.size())) {
        if (error) *error = "could not write a segment";
        return false;
    }
    ++m_segmentIndex;
    return true;
}

bool StreamEncryptor::Write(const uint8_t *data, std::size_t length, std::string *error) {
    if (m_finished) {
        if (error) *error = "this stream is already finished";
        return false;
    }
    if (length > 0 && data != nullptr) m_buffer.insert(m_buffer.end(), data, data + length);

    // Strictly greater, never equal: an exactly-full buffer stays put, because it becomes the
    // last segment unless more data arrives. Tink does the same, and the difference shows up
    // only for a plaintext that is an exact multiple of the segment capacity.
    while (m_buffer.size() > PlaintextSegmentBytes(m_segmentIndex)) {
        const std::size_t take = PlaintextSegmentBytes(m_segmentIndex);
        if (!EmitSegment(m_buffer.data(), take, false, error)) return false;
        m_buffer.erase(m_buffer.begin(), m_buffer.begin() + static_cast<long>(take));
    }
    return true;
}

bool StreamEncryptor::Finish(std::string *error) {
    if (m_finished) {
        if (error) *error = "this stream is already finished";
        return false;
    }
    m_finished = true;
    if (!EmitSegment(m_buffer.data(), m_buffer.size(), true, error)) return false;
    SecureWipe(m_buffer.data(), m_buffer.size());
    m_buffer.clear();
    return true;
}

// ---- SeekableStreamReader ---------------------------------------------------------------

std::unique_ptr<SeekableStreamReader> SeekableStreamReader::Open(
    const uint8_t *key, std::size_t keyLength, const std::string &associatedData,
    ReadCiphertext readCiphertext, uint64_t ciphertextSize, std::string *error) {
    if (readCiphertext == nullptr) {
        if (error) *error = "a reader needs a source";
        return nullptr;
    }
    bool valid = false;
    const uint64_t plaintextSize = PlaintextSizeFor(ciphertextSize, &valid);
    if (!valid) {
        if (error) *error = "this is not an encrypted stream: the size is impossible";
        return nullptr;
    }

    uint8_t header[kHeaderBytes];
    if (!readCiphertext(0, header, kHeaderBytes)) {
        if (error) *error = "could not read the stream header";
        return nullptr;
    }
    if (header[0] != static_cast<uint8_t>(kHeaderBytes)) {
        if (error) *error = "unrecognised stream header";
        return nullptr;
    }

    std::unique_ptr<SeekableStreamReader> reader(new SeekableStreamReader());
    if (!DeriveStreamKey(key, keyLength, header + 1, associatedData, &reader->m_streamKey,
                         error)) {
        return nullptr;
    }
    std::memcpy(reader->m_noncePrefix, header + 1 + kHeaderSaltBytes, kNoncePrefixBytes);
    reader->m_read = std::move(readCiphertext);
    reader->m_ciphertextSize = ciphertextSize;
    reader->m_plaintextSize = plaintextSize;
    reader->m_segmentCount =
        (ciphertextSize + kCiphertextSegmentBytes - 1) / kCiphertextSegmentBytes;
    return reader;
}

bool SeekableStreamReader::LoadSegment(uint64_t index, std::string *error) {
    if (m_cachedIndex == index) return true;
    if (index >= m_segmentCount) {
        if (error) *error = "segment out of range";
        return false;
    }
    const uint64_t start = SegmentFileStart(index);
    const uint64_t end = std::min(m_ciphertextSize, (index + 1) * kCiphertextSegmentBytes);
    if (end <= start) {
        if (error) *error = "empty segment";
        return false;
    }
    const std::size_t length = static_cast<std::size_t>(end - start);
    Bytes cipher(length);
    if (!m_read(start, cipher.data(), length)) {
        if (error) *error = "could not read a segment";
        return false;
    }
    Bytes plain(length - kTagBytes);
    uint8_t nonce[12];
    SegmentNonce(m_noncePrefix, index, index + 1 == m_segmentCount, nonce);
    if (!GcmOpen(m_streamKey.data(), nonce, nullptr, 0, cipher.data(), length, plain.data(),
                 error)) {
        return false;
    }
    if (!m_cache.empty()) SecureWipe(m_cache.data(), m_cache.size());
    m_cache = std::move(plain);
    m_cachedIndex = index;
    return true;
}

bool SeekableStreamReader::ReadAt(uint64_t offset, uint8_t *out, std::size_t length,
                                  std::size_t *got, std::string *error) {
    if (got) *got = 0;
    if (offset > m_plaintextSize) {
        if (error) *error = "read past the end of the stream";
        return false;
    }
    const uint64_t available = m_plaintextSize - offset;
    const std::size_t wanted =
        static_cast<std::size_t>(std::min<uint64_t>(length, available));
    std::size_t done = 0;
    while (done < wanted) {
        const uint64_t position = offset + done;
        // Which segment holds this byte. Segment 0 is short by the header, so the first
        // boundary is not where a uniform division would put it.
        uint64_t index = 0;
        if (position >= PlaintextSegmentBytes(0)) {
            index = 1 + (position - PlaintextSegmentBytes(0)) / PlaintextSegmentBytes(1);
        }
        if (!LoadSegment(index, error)) return false;
        const uint64_t segmentStart = SegmentPlaintextStart(index);
        const std::size_t within = static_cast<std::size_t>(position - segmentStart);
        if (within >= m_cache.size()) {
            if (error) *error = "the stream ended sooner than its size promised";
            return false;
        }
        const std::size_t take = std::min(m_cache.size() - within, wanted - done);
        std::memcpy(out + done, m_cache.data() + within, take);
        done += take;
    }
    if (got) *got = done;
    return true;
}

// ---- whole-buffer helpers ----------------------------------------------------------------

bool EncryptBuffer(const uint8_t *key, std::size_t keyLength, const std::string &associatedData,
                   const uint8_t *plaintext, std::size_t plaintextLength, Bytes *out,
                   std::string *error) {
    if (out == nullptr) return false;
    out->clear();
    auto sink = [out](const uint8_t *data, std::size_t length) {
        out->insert(out->end(), data, data + length);
        return true;
    };
    auto encryptor = StreamEncryptor::Create(key, keyLength, associatedData, sink, error);
    if (encryptor == nullptr) return false;
    if (!encryptor->Write(plaintext, plaintextLength, error)) return false;
    return encryptor->Finish(error);
}

bool DecryptBuffer(const uint8_t *key, std::size_t keyLength, const std::string &associatedData,
                   const uint8_t *ciphertext, std::size_t ciphertextLength, Bytes *out,
                   std::string *error) {
    if (out == nullptr) return false;
    auto read = [ciphertext, ciphertextLength](uint64_t offset, uint8_t *dest,
                                               std::size_t length) {
        if (offset + length > ciphertextLength) return false;
        std::memcpy(dest, ciphertext + offset, length);
        return true;
    };
    auto reader = SeekableStreamReader::Open(key, keyLength, associatedData, read,
                                             ciphertextLength, error);
    if (reader == nullptr) return false;
    out->assign(static_cast<std::size_t>(reader->PlaintextSize()), 0);
    if (out->empty()) return true;
    std::size_t got = 0;
    if (!reader->ReadAt(0, out->data(), out->size(), &got, error)) return false;
    return got == out->size();
}



// ---- StreamDecryptor ---------------------------------------------------------------------

std::unique_ptr<StreamDecryptor> StreamDecryptor::Create(const uint8_t *key,
                                                         std::size_t keyLength,
                                                         const std::string &associatedData,
                                                         Source source, std::string *error) {
    if (source == nullptr) {
        if (error) *error = "a decryptor needs a source";
        return nullptr;
    }
    uint8_t header[kHeaderBytes];
    std::size_t got = 0;
    if (!source(header, kHeaderBytes, &got) || got != kHeaderBytes) {
        if (error) *error = "the stream ended before its header";
        return nullptr;
    }
    if (header[0] != static_cast<uint8_t>(kHeaderBytes)) {
        if (error) *error = "unrecognised stream header";
        return nullptr;
    }
    std::unique_ptr<StreamDecryptor> decryptor(new StreamDecryptor());
    if (!DeriveStreamKey(key, keyLength, header + 1, associatedData, &decryptor->m_streamKey,
                         error)) {
        return nullptr;
    }
    std::memcpy(decryptor->m_noncePrefix, header + 1 + kHeaderSaltBytes, kNoncePrefixBytes);
    decryptor->m_source = std::move(source);
    return decryptor;
}

bool StreamDecryptor::FillSegment(std::string *error) {
    if (m_done) return true;

    // Segment 0 shares its 1 MiB with the header, so its ciphertext is that much shorter.
    const std::size_t want =
        (m_segmentIndex == 0 ? kCiphertextSegmentBytes - kHeaderBytes : kCiphertextSegmentBytes) -
        m_pending.size();

    Bytes chunk(want + 1);
    std::size_t got = 0;
    if (!m_source(chunk.data(), want + 1, &got)) {
        if (error) *error = "could not read the stream";
        return false;
    }
    m_pending.insert(m_pending.end(), chunk.begin(), chunk.begin() + static_cast<long>(got));

    const std::size_t segmentLength =
        m_segmentIndex == 0 ? kCiphertextSegmentBytes - kHeaderBytes : kCiphertextSegmentBytes;
    // One byte past the segment tells us whether another follows, which the nonce depends on.
    const bool last = m_pending.size() <= segmentLength;
    const std::size_t take = last ? m_pending.size() : segmentLength;
    if (take < kTagBytes) {
        if (error) *error = "the stream ended mid-segment";
        return false;
    }

    Bytes plain(take - kTagBytes);
    uint8_t nonce[12];
    SegmentNonce(m_noncePrefix, m_segmentIndex, last, nonce);
    if (!GcmOpen(m_streamKey.data(), nonce, nullptr, 0, m_pending.data(), take, plain.data(),
                 error)) {
        return false;
    }
    m_pending.erase(m_pending.begin(), m_pending.begin() + static_cast<long>(take));
    m_plain = std::move(plain);
    m_offset = 0;
    ++m_segmentIndex;
    if (last) m_done = true;
    return true;
}

bool StreamDecryptor::Read(uint8_t *out, std::size_t length, std::size_t *got,
                           std::string *error) {
    if (got) *got = 0;
    std::size_t produced = 0;
    while (produced < length) {
        if (m_offset >= m_plain.size()) {
            if (m_done) break;
            if (!FillSegment(error)) return false;
            if (m_plain.empty() && m_done) break;
        }
        const std::size_t take = std::min(m_plain.size() - m_offset, length - produced);
        std::memcpy(out + produced, m_plain.data() + m_offset, take);
        m_offset += take;
        produced += take;
    }
    if (got) *got = produced;
    return true;
}

}  // namespace arsivinyo::crypto
