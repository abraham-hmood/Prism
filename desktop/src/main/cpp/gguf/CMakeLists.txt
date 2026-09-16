# Host build of llama.cpp plus Prism's gguf_bridge, for desktop JVMs.
#
# SEPARATE FROM THE ANDROID CMakeLists FOR THE SAME REASON nora_conv's host build is: that file
# turns on OpenCL and, conditionally, the Qualcomm Hexagon NPU backend, and wires both into
# llama.cpp through the NDK. Neither exists here. Threading a host build through it would mean
# guarding almost every line on ANDROID.
#
# CPU ONLY, DELIBERATELY. llama.cpp can target CUDA, Vulkan, SYCL and more, and every one of them
# is a separate SDK the person building this may not have. A CPU build works on any machine with
# a compiler, which is the right default for a first port; GPU backends are a later, opt-in
# decision rather than something that silently fails to configure here.

cmake_minimum_required(VERSION 3.22)
project(gguf_bridge_host CXX C)

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

# Keep the vendored build to the inference library. The examples and tools are a large fraction
# of llama.cpp's build time and none of them are linked into the bridge.
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS    OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL           OFF CACHE BOOL "" FORCE)

# OpenMP off, matching the Android build. ggml's own thread pool and an OpenMP runtime on the same
# cores are reliably worse than either alone -- the same reasoning that keeps nora_conv's
# parallelism in Kotlin's Par rather than inside the native layer.
set(GGML_OPENMP  OFF CACHE BOOL "" FORCE)
set(GGML_OPENCL  OFF CACHE BOOL "" FORCE)
set(GGML_HEXAGON OFF CACHE BOOL "" FORCE)

# Portable baseline, NOT -march=native. A binary built with native instructions on the build
# machine dies with SIGILL on an older CPU; ggml detects AVX2/AVX512 at runtime where it can.
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
if (MSVC)
    set(GGML_AVX2 ON CACHE BOOL "" FORCE)
endif()

add_subdirectory("${PRISM_CPP}/llama.cpp" "${CMAKE_CURRENT_BINARY_DIR}/llama.cpp")

add_library(gguf_bridge SHARED "${PRISM_CPP}/gguf_bridge.cpp")
target_include_directories(gguf_bridge PRIVATE
    ${JNI_INCLUDE_DIRS}
    # ggml-backend-impl.h/ggml-cpu.h -- internal ggml headers, not part of the llama/ggml targets'
    # own public include interface. Needed only for the Prism Swap pseudo-device (Tier 2 of the
    # RAM/swap fix) -- see gguf_bridge.cpp's "Prism Swap pseudo-device" block.
    "${PRISM_CPP}/llama.cpp/ggml/src"
)
target_link_libraries(gguf_bridge PRIVATE llama)

set_target_properties(gguf_bridge PROPERTIES OUTPUT_NAME "gguf_bridge" PREFIX "")
