# Host build of whisper.cpp plus Prism's whisper_bridge, for desktop JVMs. PHASE 36.
#
# ONE ggml, SHARED WITH llama.cpp. whisper.cpp upstream vendors its own copy; building that alongside
# the llama.cpp already in this tree would put two ggml implementations in one process, which on Windows
# means two DLLs competing for the name ggml.dll and on Linux means the dynamic linker interposing one
# revision's symbols over the other's. Either way the crash happens inside tensor code and names
# neither library.
#
# So this adds llama.cpp's subdirectory -- which defines the ggml targets -- and compiles whisper's
# single translation unit against them. If whisper's revision needs a ggml function this tree does not
# have, this fails to COMPILE, which is the failure mode to want: loud, immediate, and naming the
# symbol. See whisper_bridge.cpp for the same note from the other side.
#
# Kept separate from the gguf CMakeLists rather than folded into it because they are separate builds
# with separate costs, the same reason gguf is separate from nora_conv: somebody who wants dictation
# should not have to wait for a llama.cpp build they are not using, and vice versa.

cmake_minimum_required(VERSION 3.22)
project(whisper_bridge_host CXX C)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

if (DEFINED PRISM_JAVA_HOME)
    set(JNI_INCLUDE_DIRS
        "${PRISM_JAVA_HOME}/include"
        "${PRISM_JAVA_HOME}/include/win32"
        "${PRISM_JAVA_HOME}/include/linux"
        "${PRISM_JAVA_HOME}/include/darwin")
    if (NOT EXISTS "${PRISM_JAVA_HOME}/include/jni.h")
        message(FATAL_ERROR "No jni.h under ${PRISM_JAVA_HOME}/include -- point PRISM_JAVA_HOME at a full JDK.")
    endif()
else()
    find_package(JNI REQUIRED)
endif()

set(PRISM_CPP "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../app/src/main/cpp")

# Same trimming and the same portability flags as the gguf build. Repeated rather than shared because
# these are CACHE FORCE writes that have to happen before add_subdirectory, and a file included by both
# would have to be a third file existing only to hold six lines.
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS    OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL           OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP  OFF CACHE BOOL "" FORCE)
set(GGML_OPENCL  OFF CACHE BOOL "" FORCE)
set(GGML_HEXAGON OFF CACHE BOOL "" FORCE)

# Portable baseline, not -march=native: a binary built with native instructions dies with SIGILL on an
# older CPU, and ggml detects what it can at runtime.
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
if (MSVC)
    set(GGML_AVX2 ON CACHE BOOL "" FORCE)
endif()

add_subdirectory("${PRISM_CPP}/llama.cpp" "${CMAKE_CURRENT_BINARY_DIR}/llama.cpp")

add_library(whisper_bridge SHARED
    "${PRISM_CPP}/whisper.cpp/src/whisper.cpp"
    # aneforge is included UNCONDITIONALLY by whisper.cpp -- unlike the CoreML, OpenVINO and VitisAI
    # backends, which are behind WHISPER_USE_* guards. Its implementation is guarded internally by
    # __APPLE__ && __aarch64__, so on Windows and Linux this compiles to stubs, but the declarations
    # still have to link. Leaving it out fails at LINK time with three unresolved symbols that name
    # the Apple Neural Engine on a machine that has none.
    "${PRISM_CPP}/whisper.cpp/src/aneforge/whisper-aneforge.cpp"
    "${PRISM_CPP}/whisper_bridge.cpp"
)

target_include_directories(whisper_bridge PRIVATE
    ${JNI_INCLUDE_DIRS}
    "${PRISM_CPP}"
    "${PRISM_CPP}/whisper.cpp/include"
    "${PRISM_CPP}/whisper.cpp/src"
)

# ggml rather than llama: whisper needs the tensor library, not the language-model one. Linking llama
# as well would pull a second large library into a dictation binary for nothing.
target_link_libraries(whisper_bridge PRIVATE ggml)

# WHISPER_VERSION is a macro upstream's own CMake defines from its project() version; whisper.cpp uses
# it in whisper_version(). Vendoring the source without upstream's CMakeLists means defining it here.
# Pinned to the revision actually vendored, so whisper_version() reports what the code IS rather than
# something plausible -- a version string that lies is worse than no version string.
target_compile_definitions(whisper_bridge PRIVATE WHISPER_VERSION="1.9.4")

# whisper.cpp is a 9000-line translation unit that MSVC will not compile without this -- it exceeds the
# default limit on sections in a single object file. The error names the file and not the cause.
if (MSVC)
    target_compile_options(whisper_bridge PRIVATE /bigobj)
endif()

set_target_properties(whisper_bridge PROPERTIES OUTPUT_NAME "whisper_bridge" PREFIX "")
