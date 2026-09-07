cmake_minimum_required(VERSION 3.21)
project(arsivinyo-desktop VERSION 0.1.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_package(Qt6 6.5 REQUIRED COMPONENTS Core Gui Quick QuickControls2 Sql Multimedia Concurrent)

# The preset signal chain, shared verbatim with the Android app. Only its JNI wrapper is
# Android's; everything here is the same C++ the phone runs.
set(ARSIVINYO_DSP ${CMAKE_CURRENT_SOURCE_DIR}/../shared/dsp)
set(ARSIVINYO_PAIRING_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../shared/pairing)
set(ARSIVINYO_CRYPTO_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../shared/crypto)

# Ed25519. Qt does not expose it, so the identity keys go straight to OpenSSL's EVP.
find_package(PkgConfig REQUIRED)
pkg_check_modules(LIBCRYPTO REQUIRED IMPORTED_TARGET libcrypto)
qt_standard_project_setup(REQUIRES 6.5)

# yt-dlp is fetched at build time into the build output, beside where the engine runs.
# It is a dependency, not source: nothing about it belongs in the repository, and the app
# updates it in place afterwards anyway.
set(ARSIVINYO_YTDLP_DIR ${CMAKE_CURRENT_BINARY_DIR}/yt-dlp)
add_custom_command(
    OUTPUT ${ARSIVINYO_YTDLP_DIR}/yt_dlp/version.py
    COMMAND ${CMAKE_COMMAND} -E echo "Fetching yt-dlp into ${CMAKE_CURRENT_BINARY_DIR}"
    COMMAND python3 ${CMAKE_CURRENT_SOURCE_DIR}/../shared/engine/ytdlp_updater.py
            ${CMAKE_CURRENT_BINARY_DIR}
    DEPENDS ${CMAKE_CURRENT_SOURCE_DIR}/../shared/engine/ytdlp_updater.py
    COMMENT "Fetching yt-dlp"
    VERBATIM)
add_custom_target(arsivinyo-ytdlp DEPENDS ${ARSIVINYO_YTDLP_DIR}/yt_dlp/version.py)

option(ARSIVINYO_DEV_TOOLS "Build the screenshot hook used while iterating on the UI" OFF)

qt_add_executable(arsivinyo-desktop src/main.cpp)
if(ARSIVINYO_DEV_TOOLS)
    target_compile_definitions(arsivinyo-desktop PRIVATE ARSIVINYO_DEV_TOOLS)
endif()

# pragma Singleton in the QML file is only half of it; the build has to agree.
set_source_files_properties(qml/Theme.qml qml/Fonts.qml PROPERTIES QT_QML_SINGLETON_TYPE TRUE)

qt_add_qml_module(arsivinyo-desktop
    URI Arsivinyo
    VERSION 1.0
    QML_FILES qml/Main.qml qml/Theme.qml qml/Fonts.qml qml/DownloadButton.qml qml/LibraryView.qml qml/PlayerBar.qml qml/DevicesView.qml qml/Pressable.qml qml/TextAction.qml qml/SettingsView.qml qml/Modal.qml qml/UnlockSheet.qml
    RESOURCES
        qml/fonts/Monda_400Regular.ttf
        qml/fonts/Monda_600SemiBold.ttf
        qml/fonts/Sixtyfour_400Regular.ttf
    SOURCES
        src/EngineClient.h src/EngineClient.cpp
        src/Library.h src/Library.cpp
        src/PresetRenderer.h src/PresetRenderer.cpp
        src/DeviceIdentity.h src/DeviceIdentity.cpp
        src/SessionCertificate.h src/SessionCertificate.cpp
        src/PeerRegistry.h src/PeerRegistry.cpp
        src/PeerLink.h src/PeerLink.cpp
        src/PeerContent.h
        src/PeerSession.h src/PeerSession.cpp
        src/PairingService.h src/PairingService.cpp
        src/CookieStore.h src/CookieStore.cpp
        src/SecretStore.h src/SecretStore.cpp
        src/DataDir.h
        ${ARSIVINYO_CRYPTO_DIR}/secret.cpp
        ${ARSIVINYO_CRYPTO_DIR}/argon2.cpp
        ${ARSIVINYO_CRYPTO_DIR}/hkdf.cpp
        ${ARSIVINYO_CRYPTO_DIR}/gcm.cpp
        ${ARSIVINYO_CRYPTO_DIR}/aead_stream.cpp
        ${ARSIVINYO_CRYPTO_DIR}/subkeys.cpp
        ${ARSIVINYO_CRYPTO_DIR}/keybox.cpp
        src/LibraryContent.h src/LibraryContent.cpp
        src/LocalAddress.h src/LocalAddress.cpp
        src/DnsSd.h src/DnsSd.cpp
        src/Discovery.h src/Discovery.cpp
        ${ARSIVINYO_PAIRING_DIR}/wire.cpp
        ${ARSIVINYO_DSP}/ffmpeg_pipe.cpp
        ${ARSIVINYO_DSP}/preset_params.cpp
        ${ARSIVINYO_DSP}/dsp/freeverb.cpp
        ${ARSIVINYO_DSP}/dsp/resampler.cpp
        ${ARSIVINYO_DSP}/dsp/limiter.cpp
        ${ARSIVINYO_DSP}/dsp/preset_chain.cpp
)

# The generated QML type registration includes headers as <EngineClient.h>, guarded by
# __has_include. Without src/ on the include path the guard quietly fails and the type is
# never declared, which surfaces much later as an unrelated template error.
target_include_directories(arsivinyo-desktop PRIVATE src ${ARSIVINYO_DSP} ${ARSIVINYO_PAIRING_DIR} ${ARSIVINYO_CRYPTO_DIR})

# The app is not usable without an extractor, so the fetch is part of building it.
add_dependencies(arsivinyo-desktop arsivinyo-ytdlp)

target_link_libraries(arsivinyo-desktop PRIVATE Qt6::Quick Qt6::QuickControls2 Qt6::Sql Qt6::Multimedia Qt6::Concurrent Qt6::Network PkgConfig::LIBCRYPTO)

# ---- tests -------------------------------------------------------------------
# The library store is plain QObject code, so it is testable without a display.
enable_testing()
qt_add_executable(library_test tests/library_test.cpp src/Library.h src/Library.cpp)
target_include_directories(library_test PRIVATE src)
target_link_libraries(library_test PRIVATE Qt6::Core Qt6::Sql Qt6::Qml)  # Qml for the QML_ELEMENT macro in the header
add_test(NAME library COMMAND library_test)

# The pairing wire format, checked against the vectors both apps are held to.
set(ARSIVINYO_PAIRING ${CMAKE_CURRENT_SOURCE_DIR}/../shared/pairing)
qt_add_executable(pairing_test tests/pairing_test.cpp ${ARSIVINYO_PAIRING}/wire.cpp)
target_include_directories(pairing_test PRIVATE ${ARSIVINYO_PAIRING})
target_compile_definitions(pairing_test PRIVATE
    ARSIVINYO_VECTORS="${ARSIVINYO_PAIRING}/VECTORS.json")
target_link_libraries(pairing_test PRIVATE Qt6::Core)
add_test(NAME pairing COMMAND pairing_test)

qt_add_executable(identity_test tests/identity_test.cpp
    src/DeviceIdentity.h src/DeviceIdentity.cpp ${ARSIVINYO_PAIRING}/wire.cpp)
target_include_directories(identity_test PRIVATE src ${ARSIVINYO_PAIRING})
target_compile_definitions(identity_test PRIVATE
    ARSIVINYO_VECTORS="${ARSIVINYO_PAIRING}/VECTORS.json")
target_link_libraries(identity_test PRIVATE Qt6::Core Qt6::Network Qt6::Qml PkgConfig::LIBCRYPTO)
add_test(NAME identity COMMAND identity_test)

# The transport end to end: two services in one process pair over real TLS and move a
# file. Nothing here is mocked except where the files live.
qt_add_executable(transport_test tests/transport_test.cpp
    src/DeviceIdentity.h src/DeviceIdentity.cpp
    src/SessionCertificate.h src/SessionCertificate.cpp
    src/PeerRegistry.h src/PeerRegistry.cpp
    src/PeerLink.h src/PeerLink.cpp
    src/PeerSession.h src/PeerSession.cpp
    src/PairingService.h src/PairingService.cpp
    src/LocalAddress.h src/LocalAddress.cpp
    ${ARSIVINYO_PAIRING}/wire.cpp)
target_include_directories(transport_test PRIVATE src ${ARSIVINYO_PAIRING})
target_link_libraries(transport_test PRIVATE Qt6::Core Qt6::Network Qt6::Sql Qt6::Qml
    Qt6::Concurrent PkgConfig::LIBCRYPTO)
add_test(NAME transport COMMAND transport_test)

# The DNS-SD codec. Its parser reads whatever is on the network, so the malformed cases
# matter as much as the well-formed ones.
qt_add_executable(dnssd_test tests/dnssd_test.cpp src/DnsSd.h src/DnsSd.cpp
    src/Discovery.h src/Discovery.cpp src/LocalAddress.h src/LocalAddress.cpp)
target_include_directories(dnssd_test PRIVATE src)
target_link_libraries(dnssd_test PRIVATE Qt6::Core Qt6::Network Qt6::Qml)
add_test(NAME dnssd COMMAND dnssd_test)

# What a peer may reach, against the real Library rather than the transport's stub.
qt_add_executable(content_test tests/content_test.cpp
    src/LibraryContent.h src/LibraryContent.cpp
    src/Library.h src/Library.cpp
    src/EngineClient.h src/EngineClient.cpp)
target_include_directories(content_test PRIVATE src)
target_link_libraries(content_test PRIVATE Qt6::Core Qt6::Sql Qt6::Qml Qt6::Gui)
add_test(NAME content COMMAND content_test)

# Which address a peer is told to connect to. Scored over made-up interfaces, so the
# result does not depend on what the build machine has plugged in.

qt_add_executable(address_test tests/address_test.cpp src/LocalAddress.h src/LocalAddress.cpp)
target_include_directories(address_test PRIVATE src)
target_link_libraries(address_test PRIVATE Qt6::Core Qt6::Network)
add_test(NAME address COMMAND address_test)

# The portable security core, against the vectors the Kotlin implementation also reads.
# A drift between the two does not mean a failed handshake here — it means a vault that will
# not open — so the ciphertext is compared byte for byte, not merely round-tripped.
# The macro is named apart from ARSIVINYO_VECTORS on purpose: a target that ever links both
# pairing and crypto would otherwise have two different files under one name.
qt_add_executable(crypto_test tests/crypto_test.cpp
    ${ARSIVINYO_CRYPTO_DIR}/secret.cpp
    ${ARSIVINYO_CRYPTO_DIR}/argon2.cpp
    ${ARSIVINYO_CRYPTO_DIR}/hkdf.cpp
    ${ARSIVINYO_CRYPTO_DIR}/gcm.cpp
    ${ARSIVINYO_CRYPTO_DIR}/aead_stream.cpp
    ${ARSIVINYO_CRYPTO_DIR}/subkeys.cpp
    ${ARSIVINYO_CRYPTO_DIR}/keybox.cpp)
target_include_directories(crypto_test PRIVATE ${ARSIVINYO_CRYPTO_DIR})
target_compile_definitions(crypto_test PRIVATE
    ARSIVINYO_CRYPTO_VECTORS="${ARSIVINYO_CRYPTO_DIR}/VECTORS.json")
target_link_libraries(crypto_test PRIVATE Qt6::Core PkgConfig::LIBCRYPTO)
add_test(NAME crypto COMMAND crypto_test)

# The keybox as the app uses it: on disk, across restarts, slots added and removed. The
# property under test is that changing a way in never touches what was encrypted.
qt_add_executable(secret_test tests/secret_test.cpp
    src/SecretStore.h src/SecretStore.cpp src/DataDir.h
    ${ARSIVINYO_CRYPTO_DIR}/secret.cpp
    ${ARSIVINYO_CRYPTO_DIR}/argon2.cpp
    ${ARSIVINYO_CRYPTO_DIR}/hkdf.cpp
    ${ARSIVINYO_CRYPTO_DIR}/gcm.cpp
    ${ARSIVINYO_CRYPTO_DIR}/aead_stream.cpp
    ${ARSIVINYO_CRYPTO_DIR}/subkeys.cpp
    ${ARSIVINYO_CRYPTO_DIR}/keybox.cpp)
target_include_directories(secret_test PRIVATE src ${ARSIVINYO_CRYPTO_DIR})
target_link_libraries(secret_test PRIVATE Qt6::Core Qt6::Qml PkgConfig::LIBCRYPTO)
add_test(NAME secret COMMAND secret_test)

# Cookie jars at rest. The point of the test is the negative: the stored file must not
# contain the session token it was made from.
qt_add_executable(cookie_test tests/cookie_test.cpp
    src/CookieStore.h src/CookieStore.cpp
    src/SecretStore.h src/SecretStore.cpp src/DataDir.h
    ${ARSIVINYO_CRYPTO_DIR}/secret.cpp
    ${ARSIVINYO_CRYPTO_DIR}/argon2.cpp
    ${ARSIVINYO_CRYPTO_DIR}/hkdf.cpp
    ${ARSIVINYO_CRYPTO_DIR}/gcm.cpp
    ${ARSIVINYO_CRYPTO_DIR}/aead_stream.cpp
    ${ARSIVINYO_CRYPTO_DIR}/subkeys.cpp
    ${ARSIVINYO_CRYPTO_DIR}/keybox.cpp)
target_include_directories(cookie_test PRIVATE src ${ARSIVINYO_CRYPTO_DIR})
target_link_libraries(cookie_test PRIVATE Qt6::Core Qt6::Qml PkgConfig::LIBCRYPTO)
add_test(NAME cookie COMMAND cookie_test)

# A hand-run tool for proving the phone and the desktop interoperate. Not part of the app
# and not a test: it needs another device on the network to talk to.
if(ARSIVINYO_DEV_TOOLS)
    qt_add_executable(pair_probe tools/pair_probe.cpp
        src/DeviceIdentity.h src/DeviceIdentity.cpp
        src/SessionCertificate.h src/SessionCertificate.cpp
        src/PeerLink.h src/PeerLink.cpp
        ${ARSIVINYO_PAIRING}/wire.cpp)
    target_include_directories(pair_probe PRIVATE src ${ARSIVINYO_PAIRING})
    target_link_libraries(pair_probe PRIVATE Qt6::Core Qt6::Network Qt6::Qml PkgConfig::LIBCRYPTO)
endif()
