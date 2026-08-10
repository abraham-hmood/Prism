cmake_minimum_required(VERSION 3.22.1)
project(gguf_bridge)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Keep the vendored llama.cpp build limited to the core inference library —
# common/examples/tools/tests are already skipped automatically since
# LLAMA_STANDALONE is OFF when included via add_subdirectory(), but OpenMP
# is opted out explicitly: it needs a matched libomp on NDK for no real
# benefit at the thread counts a phone runs with.
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)

# GPU offload via OpenCL — the same backend OGAM uses for Android GPU acceleration.
# Vulkan would need the full Vulkan SDK (glslc + SPIRV-Headers) installed on the build
# machine; OpenCL only needs these two small vendored Khronos repos, built from source
# in-tree just like llama.cpp itself. On-device, the loader dlopens the GPU vendor's
# real libOpenCL.so at runtime — if none exists, model load falls back to CPU (see
# nativeLoadModel's GPU->CPU retry in gguf_bridge.cpp), so this is safe on any device.
set(OPENCL_ICD_LOADER_HEADERS_DIR "${CMAKE_CURRENT_SOURCE_DIR}/opencl-headers" CACHE PATH "" FORCE)
set(OPENCL_ICD_LOADER_BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
add_subdirectory(opencl-icd-loader)

# Pre-seed the cache vars CMake's bundled FindOpenCL.cmake module searches for, so
# ggml-opencl's find_package(OpenCL REQUIRED) resolves to our in-tree loader target
# instead of searching (and failing to find anything on) the cross-compile host.
set(OpenCL_INCLUDE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/opencl-headers" CACHE PATH "" FORCE)
set(OpenCL_LIBRARY "OpenCL" CACHE STRING "" FORCE)
set(GGML_OPENCL ON CACHE BOOL "" FORCE)

# ggml-opencl's kernel-embedding step shells out to Python at configure time. The Gradle
# daemon's process environment doesn't reliably inherit an interactive shell's PATH, so
# point CMake at a known-good interpreter explicitly rather than relying on PATH search.
find_program(GGUF_BRIDGE_PYTHON3 NAMES python3 python
    PATHS "$ENV{LOCALAPPDATA}/Microsoft/WindowsApps" "$ENV{LOCALAPPDATA}/Programs/Python/Python313"
    NO_CMAKE_FIND_ROOT_PATH)
if (GGUF_BRIDGE_PYTHON3)
    set(Python3_EXECUTABLE "${GGUF_BRIDGE_PYTHON3}" CACHE FILEPATH "" FORCE)
endif()

# Optional NPU backend: llama.cpp's own ggml-hexagon (Qualcomm Hexagon HTP/DSP) — the same
# backend OGAM uses for NPU acceleration. Unlike OpenCL above, this genuinely requires a full,
# licensed Qualcomm Hexagon SDK (headers + DSP toolchain), gated behind developer registration —
# not something that can be silently vendored. When HEXAGON_SDK_ROOT isn't set (the normal case,
# including this build), this block is a complete no-op and the rest of the build is untouched.
# When it IS set, mainline ggml's own unmodified ggml-hexagon build logic takes over — see
# hexagon-htp-prebuilt/README.md for what's been pre-vendored to help a future, lighter-weight
# path that only needs SDK headers.
if (NOT DEFINED HEXAGON_SDK_ROOT AND DEFINED ENV{HEXAGON_SDK_ROOT})
    set(HEXAGON_SDK_ROOT "$ENV{HEXAGON_SDK_ROOT}")
endif()
if (DEFINED HEXAGON_SDK_ROOT AND IS_DIRECTORY "${HEXAGON_SDK_ROOT}")
    message(STATUS "HEXAGON_SDK_ROOT found (${HEXAGON_SDK_ROOT}) — building with NPU/Hexagon support")
    set(GGML_HEXAGON ON CACHE BOOL "" FORCE)
    set(HEXAGON_SDK_ROOT "${HEXAGON_SDK_ROOT}" CACHE PATH "" FORCE)
    if (NOT DEFINED HEXAGON_TOOLS_ROOT AND DEFINED ENV{HEXAGON_TOOLS_ROOT})
        set(HEXAGON_TOOLS_ROOT "$ENV{HEXAGON_TOOLS_ROOT}" CACHE PATH "" FORCE)
    endif()
else()
    message(STATUS "HEXAGON_SDK_ROOT not set — building without NPU/Hexagon support (expected/normal; see hexagon-htp-prebuilt/README.md)")
endif()

add_subdirectory(llama.cpp)

add_library(gguf_bridge SHARED gguf_bridge.cpp)

target_include_directories(gguf_bridge PRIVATE
    llama.cpp/include
    llama.cpp/ggml/include
)

find_library(log-lib log)

target_link_libraries(gguf_bridge
    llama
    ggml
    ${log-lib}
)

# Nora's predictive-coding kernels. Deliberately a separate library with no llama.cpp or ggml
# dependency: it is a few hundred lines of plain float arithmetic, and linking it against the
# inference stack would mean a failure to build llama could take Nora's accelerator down with
# it, for nothing gained.
#
# -O3 and NEON, but explicitly NOT -ffast-math. The kernels guarantee bit-identical results to
# the Kotlin implementation, and fast-math permits exactly the reassociation that would void
# that guarantee. Vectorization does not need it here — see the note at the head of the file.
add_library(nora_conv SHARED nora_conv.cpp)

target_compile_options(nora_conv PRIVATE
    -O3
    -fno-fast-math
    -fno-unsafe-math-optimizations
)

if (ANDROID_ABI STREQUAL "arm64-v8a")
    # NEON is mandatory on arm64, so this only asks the vectorizer to use what is already there.
    target_compile_options(nora_conv PRIVATE -ftree-vectorize)
endif()

target_link_libraries(nora_conv ${log-lib})
