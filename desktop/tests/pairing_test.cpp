// The C++ wire implementation against shared/pairing/VECTORS.json.
//
// The vectors are the contract between the two apps. Kotlin will be held to the same
// file, so a disagreement surfaces here rather than as a pairing that silently never
// completes.
#include "wire.h"

#include <QCoreApplication>
#include <QCryptographicHash>
#include <QFile>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>

#include <cstdio>

using namespace arsivinyo::pairing;

static int failures = 0;

static void check(bool ok, const QString &what) {
    std::printf(ok ? "  ok    %s\n" : "  FAIL  %s\n", qPrintable(what));
    if (!ok) ++failures;
}

static std::vector<uint8_t> fromHex(const QString &hex) {
    const QByteArray raw = QByteArray::fromHex(hex.toLatin1());
    return {raw.begin(), raw.end()};
}

static QString toHex(const std::vector<uint8_t> &bytes) {
    return QByteArray(reinterpret_cast<const char *>(bytes.data()),
                      static_cast<int>(bytes.size())).toHex();
}

int main(int argc, char **argv) {
    QCoreApplication app(argc, argv);

    QFile file(QStringLiteral(ARSIVINYO_VECTORS));
    if (!file.open(QIODevice::ReadOnly)) {
        std::printf("cannot open %s\n", ARSIVINYO_VECTORS);
        return 2;
    }
    const QJsonObject vectors = QJsonDocument::fromJson(file.readAll()).object();

    for (const QJsonValue &entry : vectors.value("frames").toArray()) {
        const QJsonObject v = entry.toObject();
        const auto payload = fromHex(v.value("payload").toString());
        std::vector<uint8_t> encoded;
        const bool ok = EncodeFrame(static_cast<FrameType>(v.value("type").toInt()),
                                    payload, &encoded);
        check(ok && toHex(encoded) == v.value("encoded").toString(),
              "encode: " + v.value("why").toString());

        // And it must survive the round trip.
        FrameType type{};
        std::vector<uint8_t> decoded;
        size_t consumed = 0;
        const bool round = DecodeFrame(encoded, &type, &decoded, &consumed) == DecodeResult::Ok
                           && decoded == payload && consumed == encoded.size();
        check(round, "round trip: " + v.value("why").toString());
    }

    for (const QJsonValue &entry : vectors.value("decode_errors").toArray()) {
        const QJsonObject v = entry.toObject();
        FrameType type{};
        std::vector<uint8_t> payload;
        size_t consumed = 0;
        const DecodeResult result = DecodeFrame(fromHex(v.value("input").toString()),
                                                &type, &payload, &consumed);
        const QString expected = v.value("expect").toString();
        const QString actual = result == DecodeResult::Ok ? "Ok"
                             : result == DecodeResult::Incomplete ? "Incomplete"
                             : result == DecodeResult::TooLarge ? "TooLarge" : "BadType";
        check(actual == expected, "reject: " + v.value("why").toString());
    }

    for (const QJsonValue &entry : vectors.value("code_input").toArray()) {
        const QJsonObject v = entry.toObject();
        const auto input = CodeInput(fromHex(v.value("keyA").toString()),
                                     fromHex(v.value("keyB").toString()));
        check(toHex(input) == v.value("sorted").toString(),
              "code input: " + v.value("why").toString());
    }

    for (const QJsonValue &entry : vectors.value("pairing_code").toArray()) {
        const QJsonObject v = entry.toObject();
        const auto digest = fromHex(v.value("digest").toString());
        check(QString::fromStdString(PairingCode(digest.data(), digest.size()))
                  == v.value("code").toString(),
              "code: " + v.value("why").toString());
    }

    for (const QJsonValue &entry : vectors.value("auth_transcript").toArray()) {
        const QJsonObject v = entry.toObject();
        const AuthRole role =
            v.value("role").toString() == QLatin1String("server") ? AuthRole::Server : AuthRole::Client;
        const auto transcript = AuthTranscript(role,
                                               fromHex(v.value("serverCertSha256").toString()),
                                               fromHex(v.value("clientCertSha256").toString()));
        check(toHex(transcript) == v.value("transcript").toString(),
              "transcript: " + v.value("why").toString());
    }

    // The ordering, pinned separately. Both ends of one platform can swap it together
    // and still agree with each other; only the shared vectors catch that.
    for (const QJsonValue &entry : vectors.value("auth_transcript").toArray()) {
        const QJsonObject v = entry.toObject();
        const auto server = fromHex(v.value("serverCertSha256").toString());
        const auto client = fromHex(v.value("clientCertSha256").toString());

        const auto asServer = TranscriptOrder(AuthRole::Server, server, client);
        check(asServer.first == server && asServer.second == client,
              "a server's own certificate leads");
        const auto asClient = TranscriptOrder(AuthRole::Client, client, server);
        check(asClient.first == server && asClient.second == client,
              "and a client's own certificate follows");

        const AuthRole role =
            v.value("role").toString() == QLatin1String("server") ? AuthRole::Server : AuthRole::Client;
        const auto own = role == AuthRole::Server ? server : client;
        const auto peer = role == AuthRole::Server ? client : server;
        const auto [s, c] = TranscriptOrder(role, own, peer);
        check(toHex(AuthTranscript(role, s, c)) == v.value("transcript").toString(),
              "ordering and transcript together reproduce the vector");
    }

    // The two directions must sign different bytes, or a signature captured from one end
    // authenticates the other.
    {
        const std::vector<uint8_t> serverHash(kCertHashBytes, 0x11);
        const std::vector<uint8_t> clientHash(kCertHashBytes, 0x22);
        check(AuthTranscript(AuthRole::Server, serverHash, clientHash) !=
                  AuthTranscript(AuthRole::Client, serverHash, clientHash),
              "the two roles sign different bytes");
        check(AuthTranscript(AuthRole::Server, serverHash, clientHash) !=
                  AuthTranscript(AuthRole::Server, clientHash, serverHash),
              "swapping the certificates changes the bytes");
        check(AuthTranscript(AuthRole::Server, std::vector<uint8_t>(31, 0), clientHash).empty(),
              "a misshapen certificate hash yields no transcript");
    }

    // A property the vectors cannot express: both ends must agree for any key pair.
    for (int i = 0; i < 64; ++i) {
        std::vector<uint8_t> a(kPublicKeyBytes), b(kPublicKeyBytes);
        for (size_t j = 0; j < kPublicKeyBytes; ++j) {
            a[j] = static_cast<uint8_t>((i * 31 + j * 7) & 0xff);
            b[j] = static_cast<uint8_t>((i * 17 + j * 13) & 0xff);
        }
        const auto forward = CodeInput(a, b);
        const auto reverse = CodeInput(b, a);
        if (forward != reverse) {
            check(false, "code input is order independent");
            break;
        }
        if (i == 63) check(true, "code input is order independent over 64 key pairs");
    }

    std::printf("\n%s\n", failures ? "FAILURES" : "all checks passed");
    return failures ? 1 : 0;
}
