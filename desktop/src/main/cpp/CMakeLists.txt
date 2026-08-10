# Host build of nora_conv, for desktop JVMs.
#
# SEPARATE FROM app/src/main/cpp/CMakeLists.txt ON PURPOSE. That file is driven by the Android
# Gradle plugin, which supplies the NDK toolchain, ANDROID_ABI, and the Android log library. None
# of those exist here, and threading a host build through the same file would mean guarding
# almost every line on whether ANDROID is defined. This builds the same nora_conv.cpp against the
# host compiler instead.
#
# THE BIT-IDENTITY GUARANTEE IS THE REASON FOR THE FLAGS. nora_conv's kernels are required to
# produce results identical to the Kotlin implementation, bit for bit, and that guarantee rests
# entirely on preserving accumulation ORDER. -ffast-math permits reassociation, which is exactly
# what breaks it, so it is disabled explicitly rather than merely not requested -- some
# distributions default CMake's Release flags to include it.
#
# On x86-64, AVX2 replaces NEON as the vector unit. It is requested rather than assumed
# ("-mavx2" not "-march=native") because a binary built with -march=native on the build machine
# crashes with SIGILL on any older CPU, and a launcher that crashes on start for some users is a
# far worse outcome than one that runs slightly slower for all of them.

cmake_minimum_required(VERSION 3.22)
project(nora_conv_host CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# JNI headers.
#
# PRISM_JAVA_HOME is passed by the Gradle task, which provisions a JDK that actually HAS an
# include/ directory. FindJNI is the fallback for building this by hand, but it happily locates a
# JRE without headers and then fails confusingly at #include <jni.h>, so the explicit path wins
# when it is given.
if (DEFINED PRISM_JAVA_HOME)
    set(JNI_INCLUDE_DIRS
        "${PRISM_JAVA_HOME}/include"
        "${PRISM_JAVA_HOME}/include/win32"
        "${PRISM_JAVA_HOME}/include/linux"
        "${PRISM_JAVA_HOME}/include/darwin")
    if (NOT EXISTS "${PRISM_JAVA_HOME}/include/jni.h")
        message(FATAL_ERROR
            "No jni.h under ${PRISM_JAVA_HOME}/include. That JDK ships without headers -- the "
            "JetBrains Runtime does. Point PRISM_JAVA_HOME at a full JDK.")
    endif()
else()
    find_package(JNI REQUIRED)
endif()

add_library(nora_conv SHARED ${CMAKE_CURRENT_SOURCE_DIR}/../../../../app/src/main/cpp/nora_conv.cpp)

target_include_directories(nora_conv PRIVATE ${JNI_INCLUDE_DIRS})

# The Android build links Android's log library for __android_log_print. There is no such library
# here, and the source guards those calls on __ANDROID__, so nothing needs substituting.
target_compile_definitions(nora_conv PRIVATE PRISM_HOST_BUILD=1)

if (MSVC)
    # /fp:precise is MSVC's spelling of "do not reassociate floating point".
    target_compile_options(nora_conv PRIVATE /O2 /fp:precise /arch:AVX2)
else()
    target_compile_options(nora_conv PRIVATE
        -O3
        -fno-fast-math
        -fno-unsafe-math-optimizations
        -ftree-vectorize
    )
    # AVX2 only where the target actually is x86-64. An arm64 Mac or Linux box gets NEON, which
    # is mandatory on arm64 and therefore needs no flag.
    if (CMAKE_SYSTEM_PROCESSOR MATCHES "x86_64|AMD64|amd64")
        target_compile_options(nora_conv PRIVATE -mavx2 -mfma)
    endif()
endif()

set_target_properties(nora_conv PROPERTIES
    OUTPUT_NAME "nora_conv"
    PREFIX ""
)
