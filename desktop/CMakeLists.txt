cmake_minimum_required(VERSION 3.21)
project(arsivinyo-desktop VERSION 0.1.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_package(Qt6 6.5 REQUIRED COMPONENTS Core Gui Quick QuickControls2 Sql Multimedia Concurrent)

# The preset signal chain, shared verbatim with the Android app. Only its JNI wrapper is
# Android's; everything here is the same C++ the phone runs.
set(ARSIVINYO_DSP ${CMAKE_CURRENT_SOURCE_DIR}/../shared/dsp)
qt_standard_project_setup(REQUIRES 6.5)

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
    QML_FILES qml/Main.qml qml/Theme.qml qml/Fonts.qml qml/DownloadButton.qml qml/LibraryView.qml qml/PlayerBar.qml
    RESOURCES
        qml/fonts/Monda_400Regular.ttf
        qml/fonts/Monda_600SemiBold.ttf
        qml/fonts/Sixtyfour_400Regular.ttf
    SOURCES
        src/EngineClient.h src/EngineClient.cpp
        src/Library.h src/Library.cpp
        src/PresetRenderer.h src/PresetRenderer.cpp
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
target_include_directories(arsivinyo-desktop PRIVATE src ${ARSIVINYO_DSP})

target_link_libraries(arsivinyo-desktop PRIVATE Qt6::Quick Qt6::QuickControls2 Qt6::Sql Qt6::Multimedia Qt6::Concurrent)

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
