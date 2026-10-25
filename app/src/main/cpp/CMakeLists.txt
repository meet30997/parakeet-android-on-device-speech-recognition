cmake_minimum_required(VERSION 3.26)
project(nemo_speech_android LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# NDK r27 requires explicit ELF alignment for Android devices with 16-KiB pages.
# Apply to every shared library, including the vendored ggml and NeMo targets.
add_link_options("-Wl,-z,max-page-size=16384" "-Wl,-z,common-page-size=16384")

# Configure SentencePiece as a static library
set(SPM_ENABLE_SHARED OFF CACHE BOOL "" FORCE)
set(SPM_BUILD_TEST OFF CACHE BOOL "" FORCE)
set(SPM_ENABLE_TCMALLOC OFF CACHE BOOL "" FORCE)
set(SPM_ENABLE_NFKC_COMPILE OFF CACHE BOOL "" FORCE)
add_subdirectory(vendor/sentencepiece EXCLUDE_FROM_ALL)
if(ANDROID)
    target_link_libraries(sentencepiece-static PRIVATE log)
endif()

# Configure NeMo-Speech.cpp for Android CPU ASR
set(NEMO_SPEECH_BUILD_ASR ON CACHE BOOL "" FORCE)
set(NEMO_SPEECH_BUILD_CLI OFF CACHE BOOL "" FORCE)
set(NEMO_SPEECH_BUILD_HTTP OFF CACHE BOOL "" FORCE)
set(NEMO_SPEECH_BUILD_GRPC OFF CACHE BOOL "" FORCE)
set(NEMO_SPEECH_BUILD_TTS OFF CACHE BOOL "" FORCE)
set(NEMO_SPEECH_BUILD_NMT OFF CACHE BOOL "" FORCE)
set(NEMO_SPEECH_BUILD_DIAR OFF CACHE BOOL "" FORCE)
set(NEMO_SPEECH_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(NEMO_SPEECH_BUILD_MIC_CAPTURE OFF CACHE BOOL "" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(GGML_CUDA OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
set(GGML_METAL OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE ON CACHE BOOL "" FORCE)

# Direct NeMo-Speech to the built sentencepiece-static target
set(SENTENCEPIECE_STATIC_LIB sentencepiece-static CACHE STRING "SentencePiece static library target" FORCE)
set(SENTENCEPIECE_INCLUDE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/vendor/sentencepiece/src" CACHE PATH "SentencePiece include directory" FORCE)

add_subdirectory(vendor/nemo_speech)

# JNI wrapper library
add_library(nemo_speech_jni SHARED
    nemo_speech_jni.cpp
)

target_include_directories(nemo_speech_jni PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/vendor/nemo_speech/include
)

target_link_libraries(nemo_speech_jni PRIVATE
    nemo_speech_asr_c
    log
)
