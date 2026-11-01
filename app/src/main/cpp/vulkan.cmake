# ── GPU offload via Vulkan ───────────────────────────────────────────────────
#
# WHY IT EXISTS, AND WHY IT IS OFF BY DEFAULT.
#
# This module was written believing the GPU was reachable only through Vulkan, because OpenCL reported
# `CL_PLATFORM_NOT_FOUND` (-1001) on the reference Galaxy S22. THAT READING WAS WRONG, and the fix is
# elsewhere: Prism was packaging its own Khronos ICD loader as `libOpenCL.so`, which shadowed the
# phone's vendor driver and found no ICD files. With that library excluded from the APK, the vendor
# driver loads (MEASURED: Adreno 730, OpenCL 3.0) and ggml-opencl registers the "GPUOpenCL" device.
#
# Vulkan, meanwhile, does not work on this hardware: this ggml revision requires Vulkan 1.2 buffer
# device address and the S22's driver reports 1.1, so it crashes the process rather than declining —
# see the comment in CMakeLists.txt for the backtrace-level detail. The module is kept for drivers
# that do expose Vulkan 1.2 (-Pprism.vulkan=true), not built by default.
#
# WHAT IT NEEDS, AND WHY NOTHING HAS TO BE INSTALLED. ggml-vulkan wants a SPIR-V compiler (glslc),
# the Vulkan headers, a loader to link against, and the SPIRV-Headers CMake package. All four ship
# inside the NDK: `shader-tools/<host>/glslc`, `sysroot/usr/include/vulkan`,
# `sysroot/usr/lib/<triple>/<api>/libvulkan.so`, and a full spirv-headers checkout under
# `sources/third_party/shaderc`. The LunarG Vulkan SDK is what upstream's CMake assumes; it is
# deliberately not required here, so this build works on a machine that has only Android Studio.
#
# THE ONE BUILD-MACHINE TOOL, AND WHY IT IS GATED. ggml-vulkan generates its shaders with a small
# host program (`vulkan-shaders-gen`) that it builds with the *host* compiler — found by looking for
# cl/gcc/clang on PATH, which Android Studio's Gradle daemon does not have, even on a machine with
# LLVM and Visual Studio installed. So the toolchain file below names the host compiler explicitly,
# and this module then *proves that toolchain works* by configuring and building a throwaway
# project with it. That preflight is not paranoia: the real sub-build needs a resource compiler
# (rc) that only a Visual Studio developer prompt puts on PATH, and without the gate the first
# symptom is a failed external project with a stack trace through Windows-Clang.cmake. With it, the
# outcome is either a working GPU backend or one line saying why it was skipped.
#
# ENTRY POINT: prism_enable_vulkan_backend(). Must be called BEFORE add_subdirectory(llama.cpp):
# the backend reads its cache variables the moment ggml's CMakeLists is processed.
#
# WHAT IT COSTS. One more shared library in the APK (`libggml-vulkan.so`: the backend and its
# shaders). Diffusion and llama.cpp both discover it through the same directory scan that already
# finds the CPU variants (GGML_BACKEND_DL), so nothing in Kotlin or the bridges has to change for it
# to be picked up — and if a device's driver turns out to be broken, the paths that fall back to CPU
# are the ones already there.

# Resolved at include scope, not inside the function: CMAKE_CURRENT_LIST_DIR inside a function body
# is the directory of the file that CALLED it (same reason sd.cmake does this).
set(PRISM_VULKAN_DIR "${CMAKE_CURRENT_LIST_DIR}")

function(prism_enable_vulkan_backend)
    # OFF UNLESS ASKED FOR. See the block in CMakeLists.txt that calls this for the measurements:
    # this ggml-vulkan needs Vulkan 1.2 buffer device address, and a 1.1 driver does not just fail --
    # it jumps through a null dispatch entry and takes the process with it.
    if (NOT PRISM_VULKAN_BACKEND)
        message(STATUS
            "Vulkan backend: skipped (PRISM_VULKAN_BACKEND=OFF; build with -Pprism.vulkan=true to "
            "enable it for a driver that exposes Vulkan 1.2)")
        return()
    endif()

    # Same ABI restriction as llama.cpp itself, for the same reason: this backend links against the
    # ggml built above, which only exists for arm64-v8a.
    if (NOT ANDROID_ABI STREQUAL "arm64-v8a")
        return()
    endif()

    if (NOT DEFINED CMAKE_ANDROID_NDK OR CMAKE_ANDROID_NDK STREQUAL "")
        message(STATUS "Vulkan backend: CMAKE_ANDROID_NDK is not set — building without GPU offload")
        return()
    endif()

    if (CMAKE_HOST_WIN32)
        set(vk_host_tag "windows-x86_64")
        set(vk_exe_suffix ".exe")
    elseif (CMAKE_HOST_APPLE)
        set(vk_host_tag "darwin-x86_64")
        set(vk_exe_suffix "")
    else()
        set(vk_host_tag "linux-x86_64")
        set(vk_exe_suffix "")
    endif()

    set(vk_ndk "${CMAKE_ANDROID_NDK}")
    set(vk_sysroot "${vk_ndk}/toolchains/llvm/prebuilt/${vk_host_tag}/sysroot")

    # ── glslc ────────────────────────────────────────────────────────────────
    # The NDK's copy first; a Vulkan SDK's second, for a machine that has one.
    find_program(PRISM_VULKAN_GLSLC
        NAMES glslc glslc${vk_exe_suffix}
        PATHS "${vk_ndk}/shader-tools/${vk_host_tag}" "$ENV{VULKAN_SDK}/bin"
        NO_CMAKE_FIND_ROOT_PATH)
    if (NOT PRISM_VULKAN_GLSLC)
        message(STATUS
            "Vulkan backend: no glslc (neither ${vk_ndk}/shader-tools nor a Vulkan SDK) — "
            "building without GPU offload")
        return()
    endif()

    # ── Vulkan headers ───────────────────────────────────────────────────────
    # VENDORED, and not only because a machine with only Android Studio has no Vulkan SDK. The
    # NDK sysroot ships the C headers but no C++ ones, and the NDK's own complete copy under
    # sources/third_party/vulkan cannot be used: its vulkan.hpp is a merge artifact whose
    # DispatchLoaderBase, chain-link helper and namespace define each appear twice, so clang rejects
    # it as redefinitions before it gets anywhere near ggml's code. See vulkan-headers/README.md for
    # the provenance and for why the C and C++ halves are kept in one tree.
    set(vk_vulkan_include "${PRISM_VULKAN_DIR}/vulkan-headers")
    if (NOT EXISTS "${vk_vulkan_include}/vulkan/vulkan.hpp")
        message(STATUS
            "Vulkan backend: ${vk_vulkan_include}/vulkan/vulkan.hpp is missing — building without "
            "GPU offload")
        return()
    endif()

    # ── Loader stub ──────────────────────────────────────────────────────────
    if (NOT IS_DIRECTORY "${vk_sysroot}/usr/include/vulkan")
        message(STATUS "Vulkan backend: no Vulkan headers under ${vk_sysroot} — building without GPU offload")
        return()
    endif()

    if (ANDROID_ABI STREQUAL "arm64-v8a")
        set(vk_abi_triple "aarch64-linux-android")
    elseif (ANDROID_ABI STREQUAL "x86_64")
        set(vk_abi_triple "x86_64-linux-android")
    else()
        set(vk_abi_triple "arm-linux-androideabi")
    endif()

    # THE STUB IS DELIBERATELY OLDER-THAN-NEEDED EXCEPT WHERE IT CANNOT BE. The NDK ships one
    # libvulkan.so per API level, and the API-26 one exports only Vulkan 1.0 symbols — but
    # ggml-vulkan calls vkGetPhysicalDeviceFeatures2 (Vulkan 1.1) as a plain function, so linking
    # against it fails outright with `undefined symbol`. Vulkan 1.1 arrived with Android 10, so the
    # API-29 stub is the floor here regardless of what the app supports.
    #
    # NOTHING RUNTIME-VISIBLE DEPENDS ON THE STUB'S VERSION: it is a list of symbol names, and the
    # symbols at runtime come from the device's own loader. A device whose loader predates Vulkan
    # 1.1 fails to load this ONE shared library, which ggml's backend loader logs and skips — (the
    # CPU paths that already exist keep working.
    math(EXPR vk_stub_floor "29")
    set(vk_stub_target "${vk_stub_floor}")
    if (DEFINED ANDROID_PLATFORM_LEVEL AND ANDROID_PLATFORM_LEVEL GREATER vk_stub_floor)
        set(vk_stub_target "${ANDROID_PLATFORM_LEVEL}")
    endif()

    file(GLOB vk_stub_candidates "${vk_sysroot}/usr/lib/${vk_abi_triple}/*/libvulkan.so")
    if (NOT vk_stub_candidates)
        message(STATUS
            "Vulkan backend: no libvulkan.so stub under ${vk_sysroot}/usr/lib/${vk_abi_triple} — "
            "building without GPU offload")
        return()
    endif()
    list(SORT vk_stub_candidates COMPARE NATURAL)
    set(vk_stub "")
    foreach (candidate ${vk_stub_candidates})
        get_filename_component(vk_api_level "${candidate}" DIRECTORY)
        get_filename_component(vk_api_level "${vk_api_level}" NAME)
        if (NOT vk_api_level MATCHES "^[0-9]+$")
            continue()
        endif()
        if (vk_api_level GREATER vk_stub_target)
            continue()
        endif()
        set(vk_stub "${candidate}")
    endforeach()
    if (NOT vk_stub)
        list(POP_BACK vk_stub_candidates vk_stub)
    endif()

    # ── SPIRV-Headers ────────────────────────────────────────────────────────
    # ggml-vulkan.cpp includes <spirv/unified1/spirv.hpp> directly, and upstream's CMakeLists also
    # calls find_package(SPIRV-Headers CONFIG REQUIRED) — which the Vulkan SDK satisfies and the
    # NDK does not, even though it has the headers. The shim named below supplies that package; the
    # include directory it is pointed at is the NDK's own copy.
    set(vk_spirv_include "")
    file(GLOB vk_spirv_candidates
        "${vk_ndk}/sources/third_party/shaderc/third_party/*/external/spirv-headers/include")
    foreach (candidate ${vk_spirv_candidates})
        if (EXISTS "${candidate}/spirv/unified1/spirv.hpp")
            set(vk_spirv_include "${candidate}")
            break()
        endif()
    endforeach()
    if (NOT vk_spirv_include)
        message(STATUS
            "Vulkan backend: no SPIRV-Headers under ${vk_ndk}/sources/third_party/shaderc — "
            "building without GPU offload")
        return()
    endif()

    # ── The shader generator's host toolchain ────────────────────────────────
    # The NDK'S OWN clang IS THE TRAP HERE. It appears in every search that walks PATH or
    # CMAKE_PROGRAM_PATH during an Android configure first, it is called `clang++`, and it cannot
    # link anything for the host — so a naive find_program() "succeeds" and the real failure shows up
    # much later, inside a sub-build for a tool nobody asked about. Anything found under the NDK's
    # `prebuilt/` directory is therefore discarded.
    #
    # $ENV{ProgramFiles} is empty in some shells that launch Gradle (and it is not a synonym for
    # ProgramW6432 there), so both spellings are listed rather than betting on one.
    if (PRISM_VULKAN_HOST_CXX MATCHES "/prebuilt/")
        # A build directory configured before this check existed would otherwise keep handing the
        # cross-clang back forever.
        unset(PRISM_VULKAN_HOST_CXX CACHE)
    endif()
    if (PRISM_VULKAN_HOST_CC MATCHES "/prebuilt/")
        unset(PRISM_VULKAN_HOST_CC CACHE)
    endif()

    set(vk_host_dirs
        "$ENV{ProgramW6432}/LLVM/bin"
        "$ENV{ProgramFiles}/LLVM/bin"
        # (The 32-bit Program Files env var cannot be spelled here at all: CMake variable
        # references reject the parentheses in its name. LLVM installs to the 64-bit one.)
        "/usr/local/bin"
        "/usr/bin"
        "/opt/homebrew/bin")

    if (NOT PRISM_VULKAN_HOST_CC)
        find_program(PRISM_VULKAN_HOST_CC NAMES clang cl gcc PATHS ${vk_host_dirs} NO_DEFAULT_PATH)
    endif()
    if (NOT PRISM_VULKAN_HOST_CXX)
        find_program(PRISM_VULKAN_HOST_CXX NAMES clang++ cl g++ PATHS ${vk_host_dirs} NO_DEFAULT_PATH)
    endif()
    if (NOT PRISM_VULKAN_HOST_CXX)
        find_program(PRISM_VULKAN_HOST_CXX NAMES clang++ cl g++)
        if (PRISM_VULKAN_HOST_CXX MATCHES "/prebuilt/")
            unset(PRISM_VULKAN_HOST_CXX CACHE)
        endif()
    endif()
    if (NOT PRISM_VULKAN_HOST_CC)
        find_program(PRISM_VULKAN_HOST_CC NAMES clang cl gcc)
        if (PRISM_VULKAN_HOST_CC MATCHES "/prebuilt/")
            unset(PRISM_VULKAN_HOST_CC CACHE)
        endif()
    endif()

    # CMake's Windows-Clang support enables the RC language, and the tools that embed a manifest
    # live in the Windows SDK — on PATH only inside a developer prompt, which Gradle is not. LLVM
    # ships llvm-rc beside clang++, so a standalone LLVM install covers it; a dev prompt is covered
    # by the plain name.
    if (NOT PRISM_VULKAN_HOST_RC AND PRISM_VULKAN_HOST_CXX)
        get_filename_component(vk_host_bin "${PRISM_VULKAN_HOST_CXX}" DIRECTORY)
        find_program(PRISM_VULKAN_HOST_RC NAMES llvm-rc rc PATHS "${vk_host_bin}" NO_DEFAULT_PATH)
    endif()
    if (NOT PRISM_VULKAN_HOST_RC)
        find_program(PRISM_VULKAN_HOST_RC NAMES llvm-rc rc)
    endif()

    if (NOT PRISM_VULKAN_HOST_CC OR NOT PRISM_VULKAN_HOST_CXX)
        message(STATUS
            "Vulkan backend: no host C compiler for ggml-vulkan's shader generator — building "
            "without GPU offload")
        message(STATUS
            "Vulkan backend:   looked for clang/cl/gcc and clang++/cl/g++ on PATH and in LLVM's "
            "install directory; set -DPRISM_VULKAN_HOST_CC=<cc> -DPRISM_VULKAN_HOST_CXX=<cxx> to "
            "override (a standalone LLVM install works; Visual Studio's cl only works from a "
            "developer prompt, where CMake would have found it by itself)")
        return()
    endif()

    # CMAKE_RC_COMPILER is left unset when there is no rc anywhere; the preflight below then says
    # so, which is more useful than this file guessing at a path that does not exist.
    configure_file(
        "${PRISM_VULKAN_DIR}/vulkan-host-toolchain.cmake.in"
        "${CMAKE_BINARY_DIR}/vulkan-host-toolchain.cmake"
        @ONLY)

    # ── Preflight: does that toolchain actually build a host program? ────────
    # A throwaway project, configured and built with the very toolchain file the shader generator's
    # sub-build will use. Cheap (one small compile) and it exercises the parts that fail for
    # environmental reasons — compiler discovery, the resource compiler, linking against the
    # platform's own libraries — instead of discovering them halfway through a build.
    set(vk_probe_dir "${CMAKE_BINARY_DIR}/vulkan-host-probe")
    file(MAKE_DIRECTORY "${vk_probe_dir}/src")
    file(WRITE "${vk_probe_dir}/src/CMakeLists.txt"
        "cmake_minimum_required(VERSION 3.22)\nproject(prism_vulkan_host_probe C CXX)\nadd_executable(hostprobe main.cpp)\n")
    file(WRITE "${vk_probe_dir}/src/main.cpp" "int main() { return 0; }\n")

    # Each list element is one argv entry, so paths with spaces need no quoting — and MUST not have
    # any: quotes inside an unquoted CMake argument are literal characters, which would make CMake
    # search for a toolchain file whose name begins with one ("Could not find toolchain file:
    # \"F:/…\"" is what that looks like).
    set(vk_probe_args
        -S ${vk_probe_dir}/src
        -B ${vk_probe_dir}/build
        -G Ninja
        -DCMAKE_BUILD_TYPE=Release
        -DCMAKE_TOOLCHAIN_FILE=${CMAKE_BINARY_DIR}/vulkan-host-toolchain.cmake)
    if (CMAKE_MAKE_PROGRAM)
        list(APPEND vk_probe_args -DCMAKE_MAKE_PROGRAM=${CMAKE_MAKE_PROGRAM})
    endif()

    execute_process(
        COMMAND "${CMAKE_COMMAND}" ${vk_probe_args}
        RESULT_VARIABLE vk_probe_configure_result
        OUTPUT_VARIABLE vk_probe_configure_out
        ERROR_VARIABLE vk_probe_configure_err
        TIMEOUT 180)

    set(vk_probe_build_result 1)
    if (vk_probe_configure_result EQUAL 0)
        execute_process(
            COMMAND "${CMAKE_COMMAND}" --build "${vk_probe_dir}/build"
            RESULT_VARIABLE vk_probe_build_result
            OUTPUT_VARIABLE vk_probe_build_out
            ERROR_VARIABLE vk_probe_build_err
            TIMEOUT 300)
    endif()

    if (NOT vk_probe_configure_result EQUAL 0 OR NOT vk_probe_build_result EQUAL 0)
        # The reason is printed, not swallowed: "offload is off" with no explanation is the kind of
        # build message that costs an afternoon.
        message(STATUS
            "Vulkan backend: the host toolchain cannot build a program — building without GPU offload")
        message(STATUS
            "Vulkan backend:   CC='${PRISM_VULKAN_HOST_CC}' CXX='${PRISM_VULKAN_HOST_CXX}' "
            "RC='${PRISM_VULKAN_HOST_RC}'")
        set(vk_probe_note "${vk_probe_configure_err}")
        if (NOT vk_probe_note)
            set(vk_probe_note "${vk_probe_build_err}")
        endif()
        if (vk_probe_note)
            string(SUBSTRING "${vk_probe_note}" 0 700 vk_probe_note_short)
            message(STATUS "Vulkan backend:   ${vk_probe_note_short}")
        endif()
        message(STATUS
            "Vulkan backend:   hint: a standalone LLVM install covers the compiler and the resource "
            "compiler; -DPRISM_VULKAN_HOST_CC/-DPRISM_VULKAN_HOST_CXX/-DPRISM_VULKAN_HOST_RC override "
            "any of the three")
        return()
    endif()

    # ── Hand it all to ggml ─────────────────────────────────────────────────
    # Cache variables rather than plain ones: ggml-vulkan's CMakeLists is processed from inside
    # llama.cpp's, in a directory scope this function's locals do not reach.
    set(Vulkan_GLSLC_EXECUTABLE "${PRISM_VULKAN_GLSLC}" CACHE FILEPATH "SPIR-V compiler" FORCE)
    set(Vulkan_INCLUDE_DIR "${vk_vulkan_include}" CACHE PATH "Vulkan headers" FORCE)
    set(Vulkan_LIBRARY "${vk_stub}" CACHE FILEPATH "Vulkan loader stub" FORCE)
    set(Vulkan_LIBRARIES "${vk_stub}" CACHE STRING "Vulkan loader stub" FORCE)
    set(SPIRV-Headers_DIR "${PRISM_VULKAN_DIR}/vulkan-spirv-headers" CACHE PATH "SPIRV-Headers shim" FORCE)
    set(PRISM_SPIRV_HEADERS_INCLUDE "${vk_spirv_include}" CACHE PATH "SPIRV-Headers includes" FORCE)
    set(GGML_VULKAN_SHADERS_GEN_TOOLCHAIN "${CMAKE_BINARY_DIR}/vulkan-host-toolchain.cmake"
        CACHE FILEPATH "Host toolchain for vulkan-shaders-gen" FORCE)
    set(GGML_VULKAN ON CACHE BOOL "" FORCE)

    message(STATUS
        "Vulkan backend: enabled — glslc ${PRISM_VULKAN_GLSLC}, loader ${vk_stub}, "
        "host compiler ${PRISM_VULKAN_HOST_CXX}")
endfunction()
