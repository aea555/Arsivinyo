cmake_minimum_required(VERSION 3.21)
project(arsivinyo-desktop VERSION 0.1.0 LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_package(Qt6 6.5 REQUIRED COMPONENTS Core Gui Quick QuickControls2 Sql Multimedia Concurrent)

# The preset signal chain, shared verbatim with the Android app. Only its JNI wrapper is
# Android's; everything here is the same C++ the phone runs.
set(ARSIVINYO_DSP ${CMAKE_CURRENT_SOURCE_DIR}/../shared/dsp)
qt_standard_project_setup(REQUIRES 6.5)

qt_add_executable(arsivinyo-desktop src/main.cpp)

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
