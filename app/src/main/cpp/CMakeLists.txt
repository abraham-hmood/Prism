cmake_minimum_required(VERSION 3.22.1)
project(gguf_bridge)

# RandomX's JIT backends are hand-written .S assembly. Capital-S means
# assembly-with-preprocessor, which clang handles natively once ASM is an enabled language.
# (RandomX's own build script marks these files LANGUAGE C to placate ccache, which under the
# NDK passes -x c and makes clang try to compile assembly as C -- so that trick is not copied.)
enable_language(ASM)

find_library(log-lib log)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ── Local LLM inference: arm64-v8a only ─────────────────────────────────────
#
# llama.cpp with the OpenCL backend is a large, heavily arch-specific build, and it has only ever
# been built and tested here for arm64-v8a. Prism now ships four ABIs so RandomX can run
# everywhere (see below), and building llama.cpp four times would multiply build time for three
# ABIs that essentially no real device uses -- armeabi-v7a is legacy 32-bit, x86/x86_64 are
# emulators and a handful of Chromebooks.
#
# Failing to load is ALREADY HANDLED: GgufInferenceService.loadLibrary catches the
# UnsatisfiedLinkError and reports local .gguf inference as unavailable rather than crashing. So
# on a non-arm64 device the app runs with cloud/other inference paths and no local GGUF, which is
# an honest degradation rather than a broken install.
if (ANDROID_ABI STREQUAL "arm64-v8a")

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

    # ggml's RPC backend: what makes one model run across several phones.
    #
    # It turns a peer into a DEVICE -- tensors are allocated on it and graphs are dispatched to it
    # over a socket, exactly as they would be to a second GPU. llama.cpp then spreads a model's
    # layers across however many devices it is given, so a model too large for one phone's RAM runs
    # with its later layers living on another phone. Weights are uploaded at load time, which is
    # why a contributing peer never needs the model file.
    #
    # This is upstream ggml code, not something Prism implements: the protocol, the serialisation
    # and the graph dispatch are all already written and tested. What Prism adds is the mesh
    # negotiation that decides which peers take part (see MeshComputeRegistry).
    set(GGML_RPC ON CACHE BOOL "" FORCE)

    add_subdirectory(llama.cpp)

    add_library(gguf_bridge SHARED gguf_bridge.cpp)

    target_include_directories(gguf_bridge PRIVATE
        llama.cpp/include
        llama.cpp/ggml/include
        # ggml-backend-impl.h/ggml-cpu.h -- internal headers, not exported by the ggml/llama targets'
        # own PUBLIC include dirs. Needed only for the Prism Swap pseudo-device (Tier 2 of the RAM/
        # swap fix) -- see gguf_bridge.cpp's "Prism Swap pseudo-device" block for why a custom
        # ggml_backend_device is built from these directly rather than through the public API alone.
        llama.cpp/ggml/src
    )


    target_link_libraries(gguf_bridge
        llama
        ggml
        ${log-lib}
    )

endif()

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

# ── RandomX ─────────────────────────────────────────────────────────────────
#
# Built for EVERY ABI, which is the point of shipping more than arm64: RandomX is the one mining
# algorithm a CPU is genuinely competitive at, and it is small, self-contained C/C++ with no
# external dependencies -- the opposite of llama.cpp on both counts.
#
# THE SOURCE LIST IS DECLARED HERE rather than pulled in with add_subdirectory. RandomX's own
# CMakeLists also defines three host executables (randomx-tests, randomx-codegen,
# randomx-benchmark) which are meaningless inside an APK, and the Android Gradle plugin builds
# every target CMake declares -- EXCLUDE_FROM_ALL does not stop it. Declaring the library directly
# is a few lines and gives exact control over what ships.
set(RANDOMX_DIR ${CMAKE_CURRENT_SOURCE_DIR}/randomx)

set(randomx_sources
    ${RANDOMX_DIR}/src/aes_hash.cpp
    ${RANDOMX_DIR}/src/argon2_ref.c
    ${RANDOMX_DIR}/src/argon2_ssse3.c
    ${RANDOMX_DIR}/src/argon2_avx2.c
    ${RANDOMX_DIR}/src/bytecode_machine.cpp
    ${RANDOMX_DIR}/src/cpu.cpp
    ${RANDOMX_DIR}/src/dataset.cpp
    ${RANDOMX_DIR}/src/soft_aes.cpp
    ${RANDOMX_DIR}/src/virtual_memory.c
    ${RANDOMX_DIR}/src/vm_interpreted.cpp
    ${RANDOMX_DIR}/src/allocator.cpp
    ${RANDOMX_DIR}/src/assembly_generator_x86.cpp
    ${RANDOMX_DIR}/src/instruction.cpp
    ${RANDOMX_DIR}/src/randomx.cpp
    ${RANDOMX_DIR}/src/superscalar.cpp
    ${RANDOMX_DIR}/src/vm_compiled.cpp
    ${RANDOMX_DIR}/src/vm_interpreted_light.cpp
    ${RANDOMX_DIR}/src/argon2_core.c
    ${RANDOMX_DIR}/src/blake2_generator.cpp
    ${RANDOMX_DIR}/src/instructions_portable.cpp
    ${RANDOMX_DIR}/src/reciprocal.c
    ${RANDOMX_DIR}/src/virtual_machine.cpp
    ${RANDOMX_DIR}/src/vm_compiled_light.cpp
    ${RANDOMX_DIR}/src/blake2/blake2b.c
)

# The JIT is per-architecture, and only two of the four Android ABIs have one. The others fall
# back to RandomX's portable interpreter -- several times slower, but correct and always available.
# This mirrors the arch detection in RandomX's own build script.
if (ANDROID_ABI STREQUAL "arm64-v8a")
    list(APPEND randomx_sources
        ${RANDOMX_DIR}/src/jit_compiler_a64_static.S
        ${RANDOMX_DIR}/src/jit_compiler_a64.cpp)
    # Hardware AES. Mandatory on armv8 with the crypto extension; RandomX falls back to its
    # software AES at runtime on the rare chip without it.
    set(RANDOMX_ARCH_FLAGS -march=armv8-a+crypto)
elseif (ANDROID_ABI STREQUAL "x86_64")
    list(APPEND randomx_sources
        ${RANDOMX_DIR}/src/jit_compiler_x86_static.S
        ${RANDOMX_DIR}/src/jit_compiler_x86.cpp)
    set(RANDOMX_ARCH_FLAGS -maes -mssse3)
    set_source_files_properties(${RANDOMX_DIR}/src/argon2_ssse3.c PROPERTIES COMPILE_FLAGS -mssse3)
    set_source_files_properties(${RANDOMX_DIR}/src/argon2_avx2.c PROPERTIES COMPILE_FLAGS -mavx2)
else()
    # armeabi-v7a and x86: interpreter only, no JIT backend exists for either.
    set(RANDOMX_ARCH_FLAGS "")
endif()

add_library(randomx STATIC ${randomx_sources})

target_include_directories(randomx PUBLIC ${RANDOMX_DIR}/src)

target_compile_options(randomx PRIVATE -O3 ${RANDOMX_ARCH_FLAGS})

set_property(TARGET randomx PROPERTY POSITION_INDEPENDENT_CODE ON)
set_property(TARGET randomx PROPERTY CXX_STANDARD 11)
set_property(TARGET randomx PROPERTY CXX_STANDARD_REQUIRED ON)

add_library(randomx_jni SHARED randomx_jni.cpp)

target_link_libraries(randomx_jni randomx ${log-lib})

# ── Node.js runtime for the editor's extension host ─────────────────────────
#
# libnode.so is not built here -- it is a 2-hour cross-compile of Node.js itself, produced by
# tools/node/build-libnode.sh and dropped into src/main/jniLibs/<abi>/. This only builds the JNI
# shim that starts it.
#
# BUILT ONLY WHEN THE LIBRARY IS PRESENT. A checkout without libnode.so still compiles and the app
# still runs; NodeRuntime catches the missing library and reports that Node extensions are
# unavailable, which is the same honest degradation the llama.cpp build above takes for non-arm64
# devices. Making the whole app un-buildable without a 90 MB binary in the tree would be worse.
set(PRISM_LIBNODE "${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libnode.so")
if (EXISTS ${PRISM_LIBNODE})
    message(STATUS "Prism: building the Node.js bridge against ${PRISM_LIBNODE}")

    add_library(libnode SHARED IMPORTED)
    set_target_properties(libnode PROPERTIES IMPORTED_LOCATION ${PRISM_LIBNODE})

    add_library(prism_node SHARED prism_node.cpp)
    target_link_libraries(prism_node libnode ${log-lib})
else()
    message(STATUS "Prism: no libnode.so for ${ANDROID_ABI}; Node extensions will be unavailable")
endif()
