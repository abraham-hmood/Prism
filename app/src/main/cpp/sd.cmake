# stable-diffusion.cpp, built against the ggml that llama.cpp already defines.
#
# INCLUDED BY BOTH NATIVE PROJECTS rather than copied into each. The Android build adds it inside
# its arm64 block; the desktop build adds it to the same CMake project as gguf_bridge. One file
# describing one library is the only way the two stay the same library.
#
# WHY IT REUSES llama.cpp's ggml INSTEAD OF SD's OWN SUBMODULE. Two copies of ggml in one process
# are two copies of the same global registries, tensor allocators and backend registry -- and they
# do not merely duplicate memory, they produce tensors from one copy being fed to a graph from the
# other. The symptom is a segfault inside tensor code that names neither library. Upstream
# stable-diffusion.cpp supports exactly this arrangement (SD_USE_UPSTREAM_GGML): its handful of
# patched kernels are optional, and without them a few quantised paths fall back rather than break.
#
# ENTRY POINT: prism_add_stable_diffusion(<bridge-target> <bridge-source> [<jni includes>...]).
# The caller must have defined the `ggml` target (by adding llama.cpp) and must pass the ggml
# source tree for the private headers SD reads directly.

set(PRISM_SD_DIR "${CMAKE_CURRENT_LIST_DIR}/stable-diffusion.cpp" CACHE PATH "stable-diffusion.cpp source tree")

# Resolved at include scope, not inside the function: CMAKE_CURRENT_LIST_DIR inside a function body
# is the directory of the file that CALLED it, so the compat header would be looked for in the
# caller's directory (and, on the desktop project, not found at all).
set(PRISM_SD_COMPAT "${CMAKE_CURRENT_LIST_DIR}/sd_upstream_compat.h")

function(prism_add_stable_diffusion bridge_target bridge_source)
    if (NOT TARGET ggml)
        message(FATAL_ERROR "prism_add_stable_diffusion: add llama.cpp (which defines ggml) first.")
    endif()

    # EVERY source under src/, recursively. Upstream's own CMakeLists globs the same tree by
    # subdirectory; a recursive glob is one line and cannot fall behind a new subdirectory, which
    # matters because upstream adds them (pipeline/, runtime/, model/*) between releases.
    file(GLOB_RECURSE PRISM_SD_SOURCES CONFIGURE_DEPENDS
        "${PRISM_SD_DIR}/src/*.cpp"
    )
    list(FILTER PRISM_SD_SOURCES EXCLUDE REGEX "/src/(main|cli)\\.cpp$")

    add_library(prism_sd STATIC ${PRISM_SD_SOURCES})

    # The third-party pieces SD genuinely needs: a regex engine for prompt weighting, Unicode
    # case folding for tokenisers, and miniz for the safetensors/zip container. WebP and WebM are
    # left out -- they exist for SD's own command-line image/video output, and Prism encodes with
    # MediaCodec (Android) and JCodec (desktop) instead.
    add_subdirectory("${PRISM_SD_DIR}/thirdparty" "${CMAKE_CURRENT_BINARY_DIR}/sd_thirdparty")

    target_sources(prism_sd PRIVATE $<TARGET_OBJECTS:zip>)

    target_include_directories(prism_sd
        PUBLIC
            "${PRISM_SD_DIR}/include"
            "${PRISM_SD_DIR}/thirdparty"
        PRIVATE
            "${PRISM_SD_DIR}/src"
            "${PRISM_SD_DIR}/src/core"
            "${PRISM_SD_DIR}/thirdparty/oniguruma"
            # ggml's private headers: SD writes its own ops and schedules graphs directly, so it
            # includes ggml-impl.h, ggml-backend-impl.h and friends rather than only the public
            # API. These live under ggml/src in llama.cpp's tree.
            "${PRISM_GGML_SOURCE_DIR}/ggml/src"
    )

    target_compile_definitions(prism_sd PUBLIC SD_USE_UPSTREAM_GGML)

    # SD still calls one op that only its own ggml fork defines. The shim maps it to the stock
    # equivalent for every translation unit rather than editing the vendored sources, so pulling a
    # newer upstream revision does not conflict with a local patch.
    if (MSVC)
        target_compile_options(prism_sd PRIVATE /FI"${PRISM_SD_COMPAT}")
    else()
        target_compile_options(prism_sd PRIVATE -include "${PRISM_SD_COMPAT}")
    endif()

    target_link_libraries(prism_sd
        PUBLIC ggml
        PRIVATE onig sd-utf8proc
    )

    find_package(Threads REQUIRED)
    target_link_libraries(prism_sd PRIVATE Threads::Threads)

    if (MSVC)
        # A few translation units are template-heavy enough to exceed 64k sections, and the sources
        # are UTF-8 with no BOM.
        target_compile_options(prism_sd PRIVATE /bigobj /utf-8 /EHsc)
        target_compile_definitions(prism_sd PRIVATE _CRT_SECURE_NO_WARNINGS)
    else()
        target_compile_options(prism_sd PRIVATE -fno-strict-aliasing)
    endif()

    add_library(${bridge_target} SHARED "${bridge_source}")
    target_include_directories(${bridge_target} PRIVATE "${PRISM_SD_DIR}/include")
    foreach (jni_dir ${ARGN})
        target_include_directories(${bridge_target} PRIVATE "${jni_dir}")
    endforeach()
    target_link_libraries(${bridge_target} PRIVATE prism_sd)
    # NO PREFIX/OUTPUT_NAME OVERRIDE. CMake's platform defaults are exactly what both loaders
    # expect: Windows produces `sd_bridge.dll` (loadLibrary("sd_bridge")), Android and Linux produce
    # `libsd_bridge.so` (loadLibrary("sd_bridge") again). Forcing PREFIX "" -- which this once did,
    # copying the desktop convention -- produced `sd_bridge.so` on Android, which AGP does not
    # package, so the library compiled and shipped nowhere and every render fell back to the
    # procedural renderer.
endfunction()
