# Install script for directory: F:/AndroidStudioProjects/Prism/.artifacts/nine-build/_deps/llama-src/ggml

# Set the install prefix
if(NOT DEFINED CMAKE_INSTALL_PREFIX)
  set(CMAKE_INSTALL_PREFIX "C:/Program Files (x86)/nexus_core")
endif()
string(REGEX REPLACE "/$" "" CMAKE_INSTALL_PREFIX "${CMAKE_INSTALL_PREFIX}")

# Set the install configuration name.
if(NOT DEFINED CMAKE_INSTALL_CONFIG_NAME)
  if(BUILD_TYPE)
    string(REGEX REPLACE "^[^A-Za-z0-9_]+" ""
           CMAKE_INSTALL_CONFIG_NAME "${BUILD_TYPE}")
  else()
    set(CMAKE_INSTALL_CONFIG_NAME "Release")
  endif()
  message(STATUS "Install configuration: \"${CMAKE_INSTALL_CONFIG_NAME}\"")
endif()

# Set the component getting installed.
if(NOT CMAKE_INSTALL_COMPONENT)
  if(COMPONENT)
    message(STATUS "Install component: \"${COMPONENT}\"")
    set(CMAKE_INSTALL_COMPONENT "${COMPONENT}")
  else()
    set(CMAKE_INSTALL_COMPONENT)
  endif()
endif()

# Install shared libraries without execute permission?
if(NOT DEFINED CMAKE_INSTALL_SO_NO_EXE)
  set(CMAKE_INSTALL_SO_NO_EXE "0")
endif()

# Is this installation the result of a crosscompile?
if(NOT DEFINED CMAKE_CROSSCOMPILING)
  set(CMAKE_CROSSCOMPILING "TRUE")
endif()

# Set default install directory permissions.
if(NOT DEFINED CMAKE_OBJDUMP)
  set(CMAKE_OBJDUMP "C:/Users/bremo/AppData/Local/Android/Sdk/ndk/26.1.10909125/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-objdump.exe")
endif()

if(NOT CMAKE_INSTALL_LOCAL_ONLY)
  # Include the install script for the subdirectory.
  include("F:/AndroidStudioProjects/Prism/.artifacts/nine-build/_deps/llama-build/ggml/src/cmake_install.cmake")
endif()

if("x${CMAKE_INSTALL_COMPONENT}x" STREQUAL "xUnspecifiedx" OR NOT CMAKE_INSTALL_COMPONENT)
  file(INSTALL DESTINATION "${CMAKE_INSTALL_PREFIX}/lib" TYPE STATIC_LIBRARY FILES "F:/AndroidStudioProjects/Prism/.artifacts/nine-build/_deps/llama-build/ggml/src/libggml.a")
endif()

if("x${CMAKE_INSTALL_COMPONENT}x" STREQUAL "xUnspecifiedx" OR NOT CMAKE_INSTALL_COMPONENT)
  file(INSTALL DESTINATION "${CMAKE_INSTALL_PREFIX}/include" TYPE FILE FILES
    "F:/AndroidStudioProjects/Prism/.artifacts/nine-build/_deps/llama-src/ggml/include/ggml.h"
    "F:/AndroidStudioProjects/Prism/.artifacts/nine-build/_deps/llama-src/ggml/include/ggml-cpu.h"
    "F:/AndroidStudioProjects/Prism/.artifacts/nine-build/_deps/llama-src/ggml/include/ggml-alloc.h"
    "F:/AndroidStudioProjects/Prism/.artifacts/nine-build/_deps/llama-src/ggml/include/ggml-backend.h"
    "F:/AndroidStudioProjects/Prism/.artifacts/nine-build/_deps/llama-src/ggml/include/ggml-blas.h"
    "F:/AndroidStudioProjects/Prism/.artifacts/nine-build/_deps/llama-src/ggml/include/ggml-cann.h"
    "F:/AndroidStudioProjects/Prism/.artifacts/nine-build/_deps/llama-src/ggml/include/ggml-cpp.h"
    "F:/AndroidStudioProjects/Prism/.artifacts/nine-build/_deps/llama-src/ggml/include/ggml-cuda.h"
    "F:/AndroidStudioProjects/Prism/.artifacts/nine-build/_deps/llama-src/ggml/include/ggml-opt.h"
    "F:/AndroidStudioProjects/Prism/.artifacts/nine-build/_deps/llama-src/ggml/include/ggml-metal.h"
    "F:/AndroidStudioProjects/Prism/.artifacts/nine-build/_deps/llama-src/ggml/include/ggml-rpc.h"
    "F:/AndroidStudioProjects/Prism/.artifacts/nine-build/_deps/llama-src/ggml/include/ggml-virtgpu.h"
    "F:/AndroidStudioProjects/Prism/.artifacts/nine-build/_deps/llama-src/ggml/include/ggml-sycl.h"
    "F:/AndroidStudioProjects/Prism/.artifacts/nine-build/_deps/llama-src/ggml/include/ggml-vulkan.h"
    "F:/AndroidStudioProjects/Prism/.artifacts/nine-build/_deps/llama-src/ggml/include/ggml-webgpu.h"
    "F:/AndroidStudioProjects/Prism/.artifacts/nine-build/_deps/llama-src/ggml/include/ggml-zendnn.h"
    "F:/AndroidStudioProjects/Prism/.artifacts/nine-build/_deps/llama-src/ggml/include/ggml-openvino.h"
    "F:/AndroidStudioProjects/Prism/.artifacts/nine-build/_deps/llama-src/ggml/include/gguf.h"
    )
endif()

if("x${CMAKE_INSTALL_COMPONENT}x" STREQUAL "xUnspecifiedx" OR NOT CMAKE_INSTALL_COMPONENT)
  file(INSTALL DESTINATION "${CMAKE_INSTALL_PREFIX}/lib" TYPE STATIC_LIBRARY FILES "F:/AndroidStudioProjects/Prism/.artifacts/nine-build/_deps/llama-build/ggml/src/libggml-base.a")
endif()

if("x${CMAKE_INSTALL_COMPONENT}x" STREQUAL "xUnspecifiedx" OR NOT CMAKE_INSTALL_COMPONENT)
  file(INSTALL DESTINATION "${CMAKE_INSTALL_PREFIX}/lib/cmake/ggml" TYPE FILE FILES
    "F:/AndroidStudioProjects/Prism/.artifacts/nine-build/_deps/llama-build/ggml/ggml-config.cmake"
    "F:/AndroidStudioProjects/Prism/.artifacts/nine-build/_deps/llama-build/ggml/ggml-config-version.cmake"
    )
endif()

