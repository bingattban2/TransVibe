cmake_minimum_required(VERSION 3.22.1)

# Produce a self-contained JNI library. The previously bundled prebuilt .so depended on
# libc++_shared.so, which was not packaged in the APK and therefore failed at dlopen().
set(ANDROID_STL c++_static CACHE STRING "Android C++ runtime" FORCE)
set(CMAKE_ANDROID_STL_TYPE c++_static CACHE STRING "Android C++ runtime" FORCE)
project(transvibe_native LANGUAGES C CXX)

# Code On the Go builds from shared storage (/storage/emulated/0).
# Its timestamp semantics can make Ninja think build.ninja is perpetually stale.
# Gradle already reconfigures the external native build when project inputs change.
set(CMAKE_SUPPRESS_REGENERATION ON CACHE BOOL "" FORCE)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_IS_DEV OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
# arm64-v8a uses the stable ARMv8-A baseline; Advanced SIMD/NEON is mandatory on AArch64.
set(GGML_CPU_ARM_ARCH "armv8-a" CACHE STRING "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
set(GGML_CCACHE OFF CACHE BOOL "" FORCE)

# Vendored whisper.cpp v1.9.4, commit 927cfce34f31707e17f2bff35c349632fb9e2c3a.
# Kept inside the project so Code On the Go's CMake does not need HTTPS/libcurl.
set(WHISPERCPP_DIR "${CMAKE_CURRENT_SOURCE_DIR}/third_party/whisper.cpp")
if(NOT EXISTS "${WHISPERCPP_DIR}/CMakeLists.txt")
    message(FATAL_ERROR "Vendored whisper.cpp source is missing: ${WHISPERCPP_DIR}")
endif()
# Reproducibility guard for the vendored official whisper.cpp release.
file(READ "${WHISPERCPP_DIR}/CMakeLists.txt" WHISPER_TOPLEVEL_CMAKE)
string(FIND "${WHISPER_TOPLEVEL_CMAKE}" "set(WHISPER_VERSION_MAJOR 1)" WHISPER_MAJOR_POS)
string(FIND "${WHISPER_TOPLEVEL_CMAKE}" "set(WHISPER_VERSION_MINOR 9)" WHISPER_MINOR_POS)
string(FIND "${WHISPER_TOPLEVEL_CMAKE}" "set(WHISPER_VERSION_PATCH 4)" WHISPER_PATCH_POS)
if(WHISPER_MAJOR_POS EQUAL -1 OR WHISPER_MINOR_POS EQUAL -1 OR WHISPER_PATCH_POS EQUAL -1)
    message(FATAL_ERROR "Unexpected whisper.cpp version. Read WHISPER_VERSION.md before updating the vendored source.")
endif()
add_subdirectory("${WHISPERCPP_DIR}" "${CMAKE_CURRENT_BINARY_DIR}/whispercpp")

add_library(transvibe_native SHARED native-lib.cpp)
target_include_directories(transvibe_native PRIVATE "${WHISPERCPP_DIR}/include")
target_link_libraries(transvibe_native PRIVATE whisper log android)
target_compile_options(transvibe_native PRIVATE
    $<$<CONFIG:Debug>:-O0>
    $<$<NOT:$<CONFIG:Debug>>:-O2;-fvisibility=hidden;-fvisibility-inlines-hidden;-ffunction-sections;-fdata-sections>
)
target_link_options(transvibe_native PRIVATE $<$<NOT:$<CONFIG:Debug>>:-Wl,--gc-sections>)
set_target_properties(transvibe_native PROPERTIES OUTPUT_NAME "transvibe_native")

# Keep Ninja from adding the automatic CMake regeneration target on shared storage.
set(CMAKE_SUPPRESS_REGENERATION ON CACHE BOOL "" FORCE)
