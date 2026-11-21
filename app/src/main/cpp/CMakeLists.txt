cmake_minimum_required(VERSION 3.18)
project(cutlab_whisper)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

add_library(cutlab_whisper SHARED whisper_jni.cpp)

# whisper.cpp wird beim Build von GitHub Actions in diesen Ordner geladen (siehe .github/workflows/build.yml).
# Fehlt der Ordner, entsteht nur eine leere Huelle und die App nutzt automatisch Vosk.
if (EXISTS ${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp/CMakeLists.txt)
    set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)      # sonst ist die Erkennung extrem langsam
    set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
    set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
    set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
    set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
    set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
    add_subdirectory(whisper.cpp whisper_build)
    target_link_libraries(cutlab_whisper whisper)
    target_compile_definitions(cutlab_whisper PRIVATE HAVE_WHISPER=1)
endif()

target_link_libraries(cutlab_whisper log android)
target_link_options(cutlab_whisper PRIVATE -Wl,-z,max-page-size=16384)
