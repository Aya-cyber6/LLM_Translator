cmake_minimum_required(VERSION 3.10)
project(whisper.cpp)

set(CMAKE_CXX_STANDARD 17)

# Path to whisper.cpp
set(WHISPER_LIB_DIR "D:/Aya/projects/whisper.cpp" CACHE PATH "Path to whisper.cpp project")

# Get whisper.cpp version
file(READ "${WHISPER_LIB_DIR}/CMakeLists.txt" MAIN_CMAKE_CONTENT)
string(REGEX MATCH "project\\(\"whisper\\.cpp\" VERSION ([0-9]+\\.[0-9]+\\.[0-9]+)\\)" VERSION_MATCH "${MAIN_CMAKE_CONTENT}")
if(CMAKE_MATCH_1)
    set(WHISPER_VERSION ${CMAKE_MATCH_1})
else()
    set(WHISPER_VERSION "unknown")
endif()
message(STATUS "Whisper version: ${WHISPER_VERSION}")

# Source files
set(SOURCE_FILES
        ${WHISPER_LIB_DIR}/src/whisper.cpp
        ${CMAKE_SOURCE_DIR}/jni.c
)

find_library(LOG_LIB log)

include(FetchContent)

# Function to build target
function(build_library target_name ggml_compile_flags)
    add_library(${target_name} SHARED ${SOURCE_FILES})

    target_compile_definitions(${target_name} PUBLIC GGML_USE_CPU)
    target_compile_definitions(${target_name} PRIVATE WHISPER_VERSION="${WHISPER_VERSION}")

    if(NOT CMAKE_BUILD_TYPE STREQUAL "Debug")
        target_compile_options(${target_name} PRIVATE -O3 -fvisibility=hidden -fvisibility-inlines-hidden -ffunction-sections -fdata-sections)
        target_link_options(${target_name} PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL -flto)
    endif()

    # Fetch GGML
    if(GGML_HOME)
        set(GGML_PATH ${GGML_HOME})
    else()
        set(GGML_PATH ${WHISPER_LIB_DIR}/ggml)
    endif()
    FetchContent_Declare(ggml SOURCE_DIR ${GGML_PATH})
    FetchContent_MakeAvailable(ggml)

    # Link library
    target_link_libraries(${target_name} ${LOG_LIB} android ggml)

    # Apply GGML compile flags
    if(ggml_compile_flags)
        target_compile_options(${target_name} PRIVATE ${ggml_compile_flags})
    endif()
endfunction()

# Build ABI-specific targets
if(${ANDROID_ABI} STREQUAL "arm64-v8a")
    build_library("whisper_v8fp16_va" "-march=armv8.2-a+fp16")
elseif(${ANDROID_ABI} STREQUAL "armeabi-v7a")
    build_library("whisper_vfpv4" "-mfpu=neon-vfpv4")
endif()

# Optional: default target
build_library("whisper" "")

# Include directories
include_directories(
        ${WHISPER_LIB_DIR}
        ${WHISPER_LIB_DIR}/src
        ${WHISPER_LIB_DIR}/include
        ${WHISPER_LIB_DIR}/ggml/include
        ${WHISPER_LIB_DIR}/ggml/src
        ${WHISPER_LIB_DIR}/ggml/src/ggml-cpu
)