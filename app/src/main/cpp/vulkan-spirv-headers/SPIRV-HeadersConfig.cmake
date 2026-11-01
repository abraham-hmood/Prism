# A stand-in for the SPIRV-Headers CMake package, satisfied by the copy the NDK already ships.
#
# WHY THIS EXISTS AT ALL. ggml-vulkan's CMakeLists begins with
#     find_package(SPIRV-Headers CONFIG REQUIRED)
# because upstream's documented requirement is the LunarG Vulkan SDK, whose install tree carries
# both this package and the <spirv/unified1/spirv.hpp> header that ggml-vulkan.cpp includes. The
# NDK has the headers -- under sources/third_party/shaderc, where shaderc keeps its own checkout of
# them -- but no CMake package, so the check fails on a machine that has the NDK and no SDK. This
# file is the missing package: find_package(SPIRV-Headers DIR ...) loads it, it declares the
# headers found, and it puts them on the include path of the directory that asked.
#
# IT ALSO SETS AN INCLUDE DIRECTORY rather than only exporting a target, which would be the tidier
# CMake. Nothing in ggml-vulkan's build links a SPIRV-Headers target -- the only reference in the
# whole tree is that one find_package line and the __has_include() in ggml-vulkan.cpp -- so a target
# would just sit there unlinked while the header stayed unfindable.

if (NOT PRISM_SPIRV_HEADERS_INCLUDE OR NOT IS_DIRECTORY "${PRISM_SPIRV_HEADERS_INCLUDE}")
    message(FATAL_ERROR
        "SPIRV-HeadersConfig: PRISM_SPIRV_HEADERS_INCLUDE is not a directory "
        "('${PRISM_SPIRV_HEADERS_INCLUDE}'). It should point at the NDK's shaderc copy of "
        "spirv-headers/include; see the Vulkan block in app/src/main/cpp/vulkan.cmake.")
endif()

if (NOT EXISTS "${PRISM_SPIRV_HEADERS_INCLUDE}/spirv/unified1/spirv.hpp")
    message(FATAL_ERROR
        "SPIRV-HeadersConfig: ${PRISM_SPIRV_HEADERS_INCLUDE} has no spirv/unified1/spirv.hpp, so it "
        "is not a SPIRV-Headers tree.")
endif()

set(SPIRV-Headers_FOUND TRUE)
set(SPIRV_HEADERS_FOUND TRUE)

if (NOT TARGET SPIRV-Headers::SPIRV-Headers)
    add_library(SPIRV-Headers::SPIRV-Headers INTERFACE IMPORTED)
    set_target_properties(SPIRV-Headers::SPIRV-Headers PROPERTIES
        INTERFACE_INCLUDE_DIRECTORIES "${PRISM_SPIRV_HEADERS_INCLUDE}")
endif()

include_directories("${PRISM_SPIRV_HEADERS_INCLUDE}")
