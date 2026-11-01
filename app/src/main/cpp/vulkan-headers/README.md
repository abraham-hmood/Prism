# Vulkan headers (vendored)

Upstream: [KhronosGroup/Vulkan-Headers](https://github.com/KhronosGroup/Vulkan-Headers), release
**v1.3.237** — `include/vulkan/*.h` (every platform's C headers) and the five C++ headers
`vulkan.hpp` needs (`vulkan.hpp`, `vulkan_enums.hpp`, `vulkan_handles.hpp`, `vulkan_structs.hpp`,
`vulkan_funcs.hpp`). `LICENSE.txt` is upstream's (Apache-2.0 OR MIT); the generator `vulkan_raii.hpp`
and the optional `vulkan_to_string.hpp` / `vulkan_hash.hpp` / `vulkan_format_traits.hpp` are not
included — nothing in this repository's include chain uses them.

## Why these are here instead of coming from the NDK

The build needs a SPIR-V compiler, a loader to link, and the headers. The first two come from the
NDK (see `../vulkan.cmake`). The headers cannot:

- **The sysroot has half of them.** `<ndk>/toolchains/llvm/prebuilt/<host>/sysroot/usr/include/vulkan`
  ships `vulkan.h`, `vulkan_core.h`, `vk_platform.h` and the platform headers, but no C++ ones —
  and `ggml-vulkan.cpp` includes `<vulkan/vulkan.hpp>`.
- **The NDK's complete copy is broken.** `<ndk>/sources/third_party/vulkan/src/include/vulkan`
  does contain a `vulkan.hpp`, and it does not compile: `class DispatchLoaderBase`, the
  `link(void*, void const*, VkBaseOutStructure*, VkBaseInStructure const*)` helper and
  `#define VULKAN_HPP_NAMESPACE` each appear **twice** in that file, which clang reports as
  redefinitions. It looks like a merge artifact of two revisions, and it is not something this
  repository should patch around.

So the tree is vendored, and the version is chosen deliberately: `VK_HEADER_VERSION` is **237** in
both `vulkan_core.h` and `vulkan.hpp` here, and 237 is also what the NDK 26.1 sysroot's
`vulkan_core.h` says — but because this directory carries its own C headers, the pair stays
consistent no matter which NDK a future build uses. `Vulkan-Hpp` refuses to compile against a C
header from a different release (`static_assert( VK_HEADER_VERSION == 237 )`), so mixing the two
halves is a build error rather than a subtle miscompile.

## Updating

Take a whole release: copy the same file set from a newer tag **and** leave `vulkan.h` /
`vulkan_core.h` alongside the C++ headers, so the static assertion above keeps holding. The Vulkan
loader stub the backend links against is the NDK's (`libvulkan.so`, API-level dependent) — the
loader's ABI is stable, so a header version newer than the device's driver is the normal, supported
case.
