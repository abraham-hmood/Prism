# NINE for the desktop: the same x86-64 translator Prism ships on Android, built for this computer.
#
# NINE's sources are used exactly as synced from its repository (third_party/nine, refreshed by the
# syncNineSources task) -- nothing in them is edited, because the next sync would overwrite it.
# What differs on a desktop is supplied from outside:
#
#   shim/common   Android's logging header (stderr instead of logcat)
#   shim/windows  the POSIX/Linux surface NINE's OS layer is written against, implemented on Win32
#
# NINE's run path is its interpreter (RuntimeBridge sets the translator off), so no AArch64 code is
# ever executed and the library runs on an x86-64 PC. llama.cpp and NINE's on-device test harness are
# left out: Prism has its own model runtime, and the tests drive Android.
cmake_minimum_required(VERSION 3.20)
project(nine_desktop CXX C)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(NINE "${CMAKE_CURRENT_SOURCE_DIR}/../../../third_party/nine/app/src/main/cpp")
get_filename_component(NINE "${NINE}" ABSOLUTE)
if(NOT EXISTS "${NINE}/CMakeLists.txt")
    message(FATAL_ERROR "NINE's sources are not at ${NINE}; run the syncNineSources task first.")
endif()

# The source list comes from NINE's own CMakeLists, so a file added there is built here too.
file(READ "${NINE}/CMakeLists.txt" nine_cmake)
string(REGEX MATCH "add_library\\(nexus_core SHARED[^)]*\\)" nine_block "${nine_cmake}")
string(REGEX MATCHALL "[A-Za-z0-9_/]+\\.cpp" nine_files "${nine_block}")
set(NINE_SOURCES "")
foreach(f IN LISTS nine_files)
    if(f MATCHES "^llama/" OR f MATCHES "^tests/")
        continue()
    endif()
    list(APPEND NINE_SOURCES "${NINE}/${f}")
endforeach()

find_package(JNI REQUIRED)

add_library(nexus_core SHARED ${NINE_SOURCES})
target_include_directories(nexus_core PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}/shim/common"
    "${NINE}"
    ${JNI_INCLUDE_DIRS}
)
target_compile_definitions(nexus_core PRIVATE NINE_HAS_LLAMA=0 NINE_DESKTOP=1)

if(WIN32)
    target_sources(nexus_core PRIVATE shim/windows/posix_win.cpp)
    target_include_directories(nexus_core BEFORE PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/shim/windows")
    target_compile_definitions(nexus_core PRIVATE _CRT_SECURE_NO_WARNINGS NOMINMAX WIN32_LEAN_AND_MEAN _CRT_DECLARE_NONSTDC_NAMES=0)
    # Applied to every NINE file without editing any of them; see the header for why.
    target_compile_options(nexus_core PRIVATE "/FI${CMAKE_CURRENT_SOURCE_DIR}/shim/windows/nine_prelude.h" -Wno-everything -ferror-limit=0)
    target_link_libraries(nexus_core PRIVATE bcrypt synchronization)
    # NINE divides 128-bit integers (its x86 DIV/IDIV); the helpers for that live in clang's own
    # runtime, not MSVC's. Found next to the compiler, whichever LLVM version it is.
    get_filename_component(llvm_bin "${CMAKE_CXX_COMPILER}" DIRECTORY)
    file(GLOB clang_builtins "${llvm_bin}/../lib/clang/*/lib/windows/clang_rt.builtins-x86_64.lib")
    if(clang_builtins)
        list(GET clang_builtins 0 clang_builtins)
        target_link_libraries(nexus_core PRIVATE "${clang_builtins}")
    else()
        message(WARNING "clang_rt.builtins-x86_64.lib not found; 128-bit division will not link")
    endif()
else()
    target_compile_options(nexus_core PRIVATE -w -fPIC)
    target_link_libraries(nexus_core PRIVATE pthread dl)
endif()
