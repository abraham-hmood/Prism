#!/usr/bin/env bash
#
# Builds prism_bench for Android arm64 against Prism's own vendored llama.cpp, once per CPU
# configuration, so the effect of the ARM feature flags can be measured on a real device.
#
# The configurations:
#   base - what Prism builds today. Cross-compiling leaves GGML_NATIVE off, and with neither
#          GGML_CPU_ARM_ARCH nor GGML_CPU_ALL_VARIANTS set, ggml-cpu's ARCH_FLAGS ends up empty:
#          the Q4_K kernels compile for baseline ARMv8, with no dotprod, no i8mm, no fp16 arithmetic.
#   v82  - +dotprod +fp16. The floor for essentially any arm64 phone from 2019 on (Cortex-A76 and up).
#   v86  - +dotprod +fp16 +i8mm. What a Snapdragon 8 Gen 1 can run.
#
# The source is copied into ext4 rather than built from /mnt/f: large parts of that tree return
# "Input/output error" when read through WSL's Windows-drive mount (the PrismOS checkout has
# case-sensitivity attributes set), and compiling across the boundary is slow anyway.
set -euo pipefail

NDK="${ANDROID_NDK:-$HOME/nodebuild/android-ndk-r27d}"
SRC="${LLAMA_SRC:-$HOME/prismbench/src/llama.cpp}"
BENCH="${PRISM_BENCH_SRC:-$HOME/prismbench/prism_bench.cpp}"
OUT="${PRISM_BENCH_DIR:-$HOME/prismbench}"

[ -d "$NDK" ]   || { echo "no NDK at $NDK" >&2; exit 1; }
[ -f "$SRC/CMakeLists.txt" ] || { echo "no llama.cpp at $SRC" >&2; exit 1; }
[ -f "$BENCH" ] || { echo "no prism_bench.cpp at $BENCH" >&2; exit 1; }

mkdir -p "$OUT/bin" "$OUT/harness"

# A tiny project that pulls in the vendored llama.cpp and links the benchmark against it. Kept here
# rather than in the repo's own CMakeLists so that building the benchmark can never affect the APK.
cat > "$OUT/harness/CMakeLists.txt" <<'CMAKE'
cmake_minimum_required(VERSION 3.22)
project(prism_bench CXX C)
set(CMAKE_CXX_STANDARD 17)
add_subdirectory(${LLAMA_DIR} llama_build)
add_executable(prism_bench ${BENCH_SRC})
target_link_libraries(prism_bench PRIVATE llama)
CMAKE

configure_flags() {
  case "$1" in
    base) echo "" ;;
    v82)  echo "-DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16" ;;
    v86)  echo "-DGGML_CPU_ARM_ARCH=armv8.6-a+dotprod+fp16+i8mm" ;;
    # What the APK now builds: one CPU backend per feature set, chosen at runtime from the CPU's own
    # HWCAP bits. Discovery scans the executable's directory, so the variant .so files have to sit
    # next to the binary on the device.
    dl)   echo "-DGGML_BACKEND_DL=ON -DGGML_CPU_ALL_VARIANTS=ON -DBUILD_SHARED_LIBS=ON" ;;
    *)    echo "unknown config: $1" >&2; exit 1 ;;
  esac
}

for config in "${@:-base v82 v86}"; do
  echo "=== building $config ==="
  build="$OUT/build-$config"
  rm -rf "$build"

  cmake -S "$OUT/harness" -B "$build" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a \
    -DANDROID_PLATFORM=android-26 \
    -DCMAKE_BUILD_TYPE=Release \
    -DLLAMA_DIR="$SRC" \
    -DBENCH_SRC="$BENCH" \
    -DGGML_OPENMP=OFF \
    -DGGML_OPENCL=OFF \
    -DGGML_RPC=OFF \
    -DLLAMA_CURL=OFF \
    -DLLAMA_BUILD_TESTS=OFF \
    -DLLAMA_BUILD_EXAMPLES=OFF \
    -DLLAMA_BUILD_SERVER=OFF \
    -DLLAMA_BUILD_TOOLS=OFF \
    -DBUILD_SHARED_LIBS=OFF \
    $(configure_flags "$config") \
    > "$OUT/configure-$config.log" 2>&1 || {
      echo "configure failed:" >&2; tail -25 "$OUT/configure-$config.log" >&2; exit 1; }

  grep -A4 'Checking for ARM features using flags' "$OUT/configure-$config.log" || true

  # Parallelism is capped rather than $(nproc): this WSL instance is configured for 20 GB and a
  # full-width llama.cpp compile alongside a Gradle daemon exhausted the host's paging file, which
  # takes WSL down mid-build and leaves nothing to show for it.
  jobs="${PRISM_BENCH_JOBS:-4}"
  cmake --build "$build" -j "$jobs" \
    > "$OUT/build-$config.log" 2>&1 || {
      echo "build failed:" >&2; tail -30 "$OUT/build-$config.log" >&2; exit 1; }

  cp "$build/prism_bench" "$OUT/bin/prism_bench-$config"

  # A dispatch build is a binary plus a directory of backends; the binary alone does nothing.
  if [ "$config" = "dl" ]; then
    mkdir -p "$OUT/bin/dl"
    cp "$build/prism_bench" "$OUT/bin/dl/"
    find "$build" -name '*.so' -exec cp {} "$OUT/bin/dl/" ';'
    echo "  -> $OUT/bin/dl/ ($(find "$OUT/bin/dl" -name '*.so' | wc -l) backend libraries)"
  fi

  # Proof rather than intent: either the instructions are in the binary or they are not.
  objdump="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-objdump"
  sdot=$("$objdump" -d "$OUT/bin/prism_bench-$config" | grep -cw sdot  || true)
  smmla=$("$objdump" -d "$OUT/bin/prism_bench-$config" | grep -cw smmla || true)
  echo "  -> prism_bench-$config   sdot=$sdot smmla=$smmla"
done

ls -lh "$OUT/bin/"
