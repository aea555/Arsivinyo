#include "subkeys.h"

#include "hkdf.h"

namespace arsivinyo::crypto {

namespace {
constexpr std::size_t kSubkeyBytes = 32;
constexpr char kPurposePrefix[] = "arsivinyo/key/v1/";
}  // namespace

bool BackupVerifier(const SecretBytes &masterKey, SecretBytes *out, std::string *error) {
    return HkdfSha256Label(masterKey, kInfoBackupVerify, kSubkeyBytes, out, error);
}

bool BackupSectionKey(const SecretBytes &masterKey, const std::string &sectionId,
                      SecretBytes *out, std::string *error) {
    return HkdfSha256Label(masterKey, std::string(kInfoBackupSectionPrefix) + sectionId,
                           kSubkeyBytes, out, error);
}

bool PurposeKey(const SecretBytes &masterKey, const std::string &purpose, SecretBytes *out,
                std::string *error) {
    return HkdfSha256Label(masterKey, std::string(kPurposePrefix) + purpose, kSubkeyBytes, out,
                           error);
}

}  // namespace arsivinyo::crypto
