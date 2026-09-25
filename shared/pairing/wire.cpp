#include "wire.h"

#include <algorithm>
#include <cstdio>

namespace arsivinyo::pairing {

namespace {
constexpr size_t kHeaderBytes = 5;  // uint32 length + uint8 type
}

bool EncodeFrame(FrameType type, const std::vector<uint8_t>& payload,
                 std::vector<uint8_t>* out) {
    if (!out) return false;
    // The length covers the type byte and the payload, which is what the decoder reads.
    const uint64_t bodyLen = static_cast<uint64_t>(payload.size()) + 1;
    if (bodyLen > kMaxFrameBytes) return false;

    out->clear();
    out->reserve(kHeaderBytes + payload.size());
    const auto len = static_cast<uint32_t>(bodyLen);
    out->push_back(static_cast<uint8_t>((len >> 24) & 0xff));
    out->push_back(static_cast<uint8_t>((len >> 16) & 0xff));
    out->push_back(static_cast<uint8_t>((len >> 8) & 0xff));
    out->push_back(static_cast<uint8_t>(len & 0xff));
    out->push_back(static_cast<uint8_t>(type));
    out->insert(out->end(), payload.begin(), payload.end());
    return true;
}

DecodeResult DecodeFrame(const std::vector<uint8_t>& buffer, FrameType* type,
                         std::vector<uint8_t>* payload, size_t* consumed) {
    if (buffer.size() < 4) return DecodeResult::Incomplete;

    const uint32_t bodyLen = (static_cast<uint32_t>(buffer[0]) << 24) |
                             (static_cast<uint32_t>(buffer[1]) << 16) |
                             (static_cast<uint32_t>(buffer[2]) << 8) |
                             static_cast<uint32_t>(buffer[3]);

    // Checked before waiting for the body, so an absurd length is refused immediately
    // rather than after the peer has streamed it.
    if (bodyLen > kMaxFrameBytes || bodyLen == 0) return DecodeResult::TooLarge;
    if (buffer.size() < 4 + bodyLen) return DecodeResult::Incomplete;

    const uint8_t rawType = buffer[4];
    if (rawType != static_cast<uint8_t>(FrameType::Control) &&
        rawType != static_cast<uint8_t>(FrameType::Bulk)) {
        return DecodeResult::BadType;
    }

    if (type) *type = static_cast<FrameType>(rawType);
    if (payload) payload->assign(buffer.begin() + kHeaderBytes, buffer.begin() + 4 + bodyLen);
    if (consumed) *consumed = 4 + bodyLen;
    return DecodeResult::Ok;
}

std::vector<uint8_t> CodeInput(const std::vector<uint8_t>& keyA,
                               const std::vector<uint8_t>& keyB) {
    const bool aFirst = std::lexicographical_compare(keyA.begin(), keyA.end(),
                                                     keyB.begin(), keyB.end());
    const std::vector<uint8_t>& first = aFirst ? keyA : keyB;
    const std::vector<uint8_t>& second = aFirst ? keyB : keyA;

    std::vector<uint8_t> out;
    out.reserve(first.size() + second.size());
    out.insert(out.end(), first.begin(), first.end());
    out.insert(out.end(), second.begin(), second.end());
    return out;
}

std::pair<std::vector<uint8_t>, std::vector<uint8_t>> TranscriptOrder(
    AuthRole role, const std::vector<uint8_t>& ownCertSha256,
    const std::vector<uint8_t>& peerCertSha256) {
    if (role == AuthRole::Server) return {ownCertSha256, peerCertSha256};
    return {peerCertSha256, ownCertSha256};
}

std::vector<uint8_t> AuthTranscript(AuthRole role,
                                    const std::vector<uint8_t>& serverCertSha256,
                                    const std::vector<uint8_t>& clientCertSha256) {
    if (serverCertSha256.size() != kCertHashBytes) return {};
    if (clientCertSha256.size() != kCertHashBytes) return {};

    // A fixed-length layout with a domain-separating label. Every field has a known size,
    // so no two different inputs can produce the same bytes.
    static constexpr char kLabel[] = "arsivinyo-pairing-auth-v1";
    std::vector<uint8_t> out;
    out.reserve(sizeof(kLabel) + 2 * kCertHashBytes + 1);
    out.insert(out.end(), kLabel, kLabel + sizeof(kLabel));  // includes the NUL
    out.insert(out.end(), serverCertSha256.begin(), serverCertSha256.end());
    out.insert(out.end(), clientCertSha256.begin(), clientCertSha256.end());
    out.push_back(static_cast<uint8_t>(role));
    return out;
}

std::string PairingCode(const uint8_t* digest, size_t digestLen) {
    if (!digest || digestLen < 4) return {};
    const uint32_t value = (static_cast<uint32_t>(digest[0]) << 24) |
                           (static_cast<uint32_t>(digest[1]) << 16) |
                           (static_cast<uint32_t>(digest[2]) << 8) |
                           static_cast<uint32_t>(digest[3]);
    char buffer[8];
    std::snprintf(buffer, sizeof(buffer), "%06u", value % 1000000u);
    return std::string(buffer);
}

std::vector<uint8_t> CommitmentInput(const std::vector<uint8_t>& clientNonce) {
    static const char kLabel[] = "arsivinyo-pairing-commit-v2";
    std::vector<uint8_t> out(kLabel, kLabel + sizeof(kLabel));  // includes the NUL
    out.insert(out.end(), clientNonce.begin(), clientNonce.end());
    return out;
}

std::vector<uint8_t> CodeInputV2(const std::vector<uint8_t>& keyA, const std::vector<uint8_t>& keyB,
                                 const std::vector<uint8_t>& clientNonce,
                                 const std::vector<uint8_t>& serverNonce) {
    static const char kLabel[] = "arsivinyo-pairing-code-v2";
    std::vector<uint8_t> out(kLabel, kLabel + sizeof(kLabel));  // includes the NUL
    const std::vector<uint8_t> keys = CodeInput(keyA, keyB);
    out.insert(out.end(), keys.begin(), keys.end());
    out.insert(out.end(), clientNonce.begin(), clientNonce.end());
    out.insert(out.end(), serverNonce.begin(), serverNonce.end());
    return out;
}

}  // namespace arsivinyo::pairing
