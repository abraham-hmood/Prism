# Host build of RandomX plus Prism's JNI bridge, for desktop JVMs. PHASE 89.
#
# ## Why this exists, and why the on-device COMPILER does not
#
# Phase 89 asked whether Android's on-device compiler should be ported. It should not, and this file is the
# reason it does not have to. That compiler exists solely because Android refuses to execute a binary from
# app storage: the toolchain has to be a dlopen'd library, every invocation is spelled out by hand, and make
# and cmake cannot run at all. A desktop has none of those constraints -- it has cmake, a real compiler and
# permission to run them -- so the correct answer is to build the library the ordinary way, which is this.
#
# ## The same vendored source as Android
#
# ../../../../app/src/main/cpp/randomx, not a second copy. Two copies of a hashing implementation is two
# revisions of a consensus-critical algorithm, and a mesh where one device's RandomX disagrees with
# another's produces shares that are silently rejected.
#
# ## The host executables are deliberately not built
#
# Upstream's CMakeLists also defines randomx-tests, randomx-codegen and randomx-benchmark. They are not
# needed here and each is a link step; the sources are listed explicitly instead, exactly as the Android
# build does, so the two stay comparable.

cmake_minimum_required(VERSION 3.22)
project(randomx_jni_host CXX C ASM)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

if(NOT DEFINED PRISM_JAVA_HOME)
    message(FATAL_ERROR "PRISM_JAVA_HOME must be set: the JNI headers live in its include/ directory")
endif()

set(PRISM_CPP ${CMAKE_CURRENT_SOURCE_DIR}/../../../../../app/src/main/cpp)
set(RANDOMX_DIR ${PRISM_CPP}/randomx)

if(NOT EXISTS ${RANDOMX_DIR}/src/randomx.cpp)
    message(FATAL_ERROR "RandomX source not found at ${RANDOMX_DIR} -- the vendored copy is missing")
endif()

set(randomx_sources
    ${RANDOMX_DIR}/src/aes_hash.cpp
    ${RANDOMX_DIR}/src/argon2_ref.c
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
    ${RANDOMX_DIR}/src/blake2_generator.cpp
    ${RANDOMX_DIR}/src/instructions_portable.cpp
    ${RANDOMX_DIR}/src/reciprocal.c
    ${RANDOMX_DIR}/src/virtual_machine.cpp
    ${RANDOMX_DIR}/src/vm_compiled_light.cpp
    ${RANDOMX_DIR}/src/blake2/blake2b.c
    ${RANDOMX_DIR}/src/bytecode_machine.cpp
    ${RANDOMX_DIR}/src/vm_interpreted_light.cpp
    ${RANDOMX_DIR}/src/argon2_core.c
    # THE ANDROID BUILD DOES NOT LIST THESE AND THE LINKER HERE DEMANDED THEM. cpu.cpp provides
    # randomx::Cpu, which randomx_get_flags calls to decide which implementation to select at runtime, and
    # the two argon2 SIMD variants are what that selection chooses between. On Android the NDK build got
    # them from upstream's own CMakeLists; this file lists sources explicitly, so they have to be here.
    ${RANDOMX_DIR}/src/cpu.cpp
    ${RANDOMX_DIR}/src/argon2_ssse3.c
    ${RANDOMX_DIR}/src/argon2_avx2.c
)

# THE JIT IS ARCHITECTURE-SPECIFIC AND IS WHERE THE SPEED IS. RandomX interprets when it cannot compile,
# which is roughly an order of magnitude slower -- correct, and not worth mining with. The x86-64 path needs
# its assembly stubs; anything else falls back to the interpreter, which is why the arm64 branch is here too
# even though a desktop is overwhelmingly x86.
if(CMAKE_SYSTEM_PROCESSOR MATCHES "(x86_64|AMD64|amd64)")
    list(APPEND randomx_sources ${RANDOMX_DIR}/src/jit_compiler_x86.cpp)

    # TWO ASSEMBLERS, TWO FILES, AND THE VENDORED TREE HAS BOTH. The JIT's static stubs --
    # randomx_program_prologue and friends -- are hand-written assembly, and MSVC cannot read gas syntax:
    # upstream ships jit_compiler_x86_static.S for gas and .asm for MASM. Handing the .S to MSVC produced a
    # link full of unresolved stubs, because the file assembled to nothing rather than failing loudly.
    if(MSVC)
        enable_language(ASM_MASM)
        list(APPEND randomx_sources ${RANDOMX_DIR}/src/jit_compiler_x86_static.asm)
        set_source_files_properties(
            ${RANDOMX_DIR}/src/jit_compiler_x86_static.asm PROPERTIES LANGUAGE ASM_MASM
        )
    else()
        enable_language(ASM)
        list(APPEND randomx_sources ${RANDOMX_DIR}/src/jit_compiler_x86_static.S)
        set_source_files_properties(
            ${RANDOMX_DIR}/src/jit_compiler_x86_static.S PROPERTIES LANGUAGE ASM
        )
    endif()
elseif(CMAKE_SYSTEM_PROCESSOR MATCHES "(aarch64|arm64|ARM64)")
    list(APPEND randomx_sources
        ${RANDOMX_DIR}/src/jit_compiler_a64.cpp
        ${RANDOMX_DIR}/src/jit_compiler_a64_static.S
    )
    set_source_files_properties(${RANDOMX_DIR}/src/jit_compiler_a64_static.S PROPERTIES LANGUAGE ASM)
else()
    message(WARNING "No RandomX JIT for ${CMAKE_SYSTEM_PROCESSOR}; the interpreter will be used and will be slow")
endif()

add_library(randomx STATIC ${randomx_sources})
target_include_directories(randomx PUBLIC ${RANDOMX_DIR}/src)
target_compile_options(randomx PRIVATE -O3)

# The same JNI bridge Android uses, so the Kotlin side is identical on both platforms.
add_library(randomx_jni SHARED ${PRISM_CPP}/randomx_jni.cpp)
target_include_directories(randomx_jni PRIVATE
    ${RANDOMX_DIR}/src
    ${PRISM_JAVA_HOME}/include
)
if(WIN32)
    target_include_directories(randomx_jni PRIVATE ${PRISM_JAVA_HOME}/include/win32)
elseif(APPLE)
    target_include_directories(randomx_jni PRIVATE ${PRISM_JAVA_HOME}/include/darwin)
else()
    target_include_directories(randomx_jni PRIVATE ${PRISM_JAVA_HOME}/include/linux)
endif()

target_link_libraries(randomx_jni PRIVATE randomx)

# Android links against the log library; a host has no such thing, and randomx_jni.cpp guards its logging
# behind __ANDROID__ for exactly this reason.
if(NOT WIN32)
    find_package(Threads REQUIRED)
    target_link_libraries(randomx_jni PRIVATE Threads::Threads)
endif()
