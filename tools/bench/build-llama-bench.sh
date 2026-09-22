#!/usr/bin/env bash
#
# Builds llama-bench for Android arm64 in several CPU configurations, so the effect of the ARM
# feature flags can be measured on a real device instead of argued about.
#
# WHY A STANDALONE BINARY AND NOT THE APK: this isolates the kernels. An APK measurement also
# contains the JNI boundary, the tokenizer, the sampler and Kotlin string handling, and when the
# number moves you cannot say which of those moved. Build llama-bench, get the ceiling the kernels
# can reach, then hold the app to it.
#
# Configurations:
#   base   - exactly what Prism builds today: cross-compiling with GGML_NATIVE off and neither
#            GGML_CPU_ARM_ARCH nor GGML_CPU_ALL_VARIANTS set, which leaves ARCH_FLAGS empty and
#            compiles the Q4_K kernels for baseline ARMv8 -- no dotprod, no i8mm, no fp16 arithmetic.
#   v82    - +dotprod +fp16. The floor for essentially any arm64 phone from 2019 on.
#   v86    - +dotprod +fp16 +i8mm. What a Snapdragon 8 Gen 1 (and any Cortex-X2/A710 core) can run.
#
# Usage: build-llama-bench.sh [config ...]     (default: all three)
set -euo pipefail

NDK="${ANDROID_NDK:-$HOME/nodebuild/android-ndk-r27d}"
SRC="${LLAMA_SRC:-/mnt/f/AndroidStudioProjects/Prism/app/src/main/cpp/llama.cpp}"
OUT="${PRISM_BENCH_DIR:-$HOME/prismbench}"

[ -d "$NDK" ] || { echo "no NDK at $NDK" >&2; exit 1; }
[ -f "$SRC/CMakeLists.txt" ] || { echo "no llama.cpp at $SRC" >&2; exit 1; }

mkdir -p "$OUT/bin"

configure_flags() {
  case "$1" in
    base) echo "" ;;
    v82)  echo "-DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16" ;;
    v86)  echo "-DGGML_CPU_ARM_ARCH=armv8.6-a+dotprod+fp16+i8mm" ;;
    *)    echo "unknown config: $1" >&2; exit 1 ;;
  esac
}

for config in "${@:-base v82 v86}"; do
  echo "=== building $config ==="
  build="$OUT/build-$config"

  # The build tree lives on ext4, not under /mnt/f: compiling a few hundred files across the
  # Windows filesystem boundary is several times slower for no benefit.
  cmake -S "$SRC" -B "$build" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a \
    -DANDROID_PLATFORM=android-26 \
    -DCMAKE_BUILD_TYPE=Release \
    -DGGML_OPENMP=OFF \
    -DGGML_OPENCL=OFF \
    -DGGML_RPC=OFF \
    -DLLAMA_CURL=OFF \
    -DLLAMA_BUILD_TESTS=OFF \
    -DLLAMA_BUILD_EXAMPLES=OFF \
    -DLLAMA_BUILD_SERVER=OFF \
    -DBUILD_SHARED_LIBS=OFF \
    $(configure_flags "$config") \
    > "$OUT/configure-$config.log" 2>&1 || {
      echo "configure failed; tail of log:" >&2
      tail -25 "$OUT/configure-$config.log" >&2
      exit 1
    }

  # What the configure step decided about ARM features -- the line that matters.
  grep -A6 'Checking for ARM features using flags' "$OUT/configure-$config.log" || true

  cmake --build "$build" --target llama-bench -j "$(nproc)" \
    > "$OUT/build-$config.log" 2>&1 || {
      echo "build failed; tail of log:" >&2
      tail -30 "$OUT/build-$config.log" >&2
      exit 1
    }

  found=$(find "$build" -name 'llama-bench' -type f | head -1)
  cp "$found" "$OUT/bin/llama-bench-$config"
  echo "  -> $OUT/bin/llama-bench-$config"

  # Proof, not intent: the instructions either made it into the binary or they did not.
  objdump="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-objdump"
  sdot=$("$objdump" -d "$OUT/bin/llama-bench-$config" | grep -c -w sdot || true)
  smmla=$("$objdump" -d "$OUT/bin/llama-bench-$config" | grep -c -w smmla || true)
  fmlal=$("$objdump" -d "$OUT/bin/llama-bench-$config" | grep -c -wE 'fmlal|fmlal2' || true)
  echo "  sdot=$sdot smmla=$smmla fmlal=$fmlal"
done

ls -lh "$OUT/bin/"
