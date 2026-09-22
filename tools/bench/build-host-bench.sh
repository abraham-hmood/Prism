#!/usr/bin/env bash
#
# Builds prism_bench for the x86_64 sandbox, against Prism's own vendored llama.cpp.
#
# WHAT THIS CAN AND CANNOT ANSWER. It runs the same llama.cpp, the same GGUF and the same context
# setup as the phone, on a machine with no other workload on it, so it answers every question that
# is about configuration rather than about instruction sets: thread count, KV cache type, batch
# size, flash attention, mmap versus repack. Those findings carry to ARM because they are properties
# of the algorithm and the memory hierarchy, not of the ISA.
#
# It cannot answer anything about dotprod, i8mm or fp16 arithmetic, which are ARM instructions that
# do not exist here. Those have to be measured on the phone, on an idle phone, and nowhere else.
set -euo pipefail

SRC="${LLAMA_SRC:-$HOME/prismbench/src/llama.cpp}"
BENCH="${PRISM_BENCH_SRC:-$HOME/prismbench/prism_bench.cpp}"
OUT="${PRISM_BENCH_DIR:-$HOME/prismbench}"
JOBS="${PRISM_BENCH_JOBS:-4}"

[ -f "$SRC/CMakeLists.txt" ] || { echo "no llama.cpp at $SRC" >&2; exit 1; }
[ -f "$BENCH" ] || { echo "no prism_bench.cpp at $BENCH" >&2; exit 1; }

mkdir -p "$OUT/bin" "$OUT/harness"

cat > "$OUT/harness/CMakeLists.txt" <<'CMAKE'
cmake_minimum_required(VERSION 3.22)
project(prism_bench CXX C)
set(CMAKE_CXX_STANDARD 17)
add_subdirectory(${LLAMA_DIR} llama_build)
add_executable(prism_bench ${BENCH_SRC})
target_link_libraries(prism_bench PRIVATE llama)
CMAKE

build="$OUT/build-host"
rm -rf "$build"

# GGML_NATIVE is left ON here, unlike the Android build: this binary only ever runs on the machine
# that compiled it, so letting ggml detect the host's AVX level is correct rather than a portability
# hazard. It is also the point of comparison -- this is ggml with the right flags for its target.
cmake -S "$OUT/harness" -B "$build" -G Ninja \
  -DCMAKE_BUILD_TYPE=Release \
  -DLLAMA_DIR="$SRC" \
  -DBENCH_SRC="$BENCH" \
  -DGGML_NATIVE=ON \
  -DGGML_OPENMP=OFF \
  -DGGML_RPC=OFF \
  -DLLAMA_CURL=OFF \
  -DLLAMA_BUILD_TESTS=OFF \
  -DLLAMA_BUILD_EXAMPLES=OFF \
  -DLLAMA_BUILD_SERVER=OFF \
  -DLLAMA_BUILD_TOOLS=OFF \
  -DBUILD_SHARED_LIBS=OFF \
  > "$OUT/configure-host.log" 2>&1 || {
    echo "configure failed:" >&2; tail -25 "$OUT/configure-host.log" >&2; exit 1; }

cmake --build "$build" -j "$JOBS" > "$OUT/build-host.log" 2>&1 || {
  echo "build failed:" >&2; tail -30 "$OUT/build-host.log" >&2; exit 1; }

cp "$build/prism_bench" "$OUT/bin/prism_bench-host"
echo "-> $OUT/bin/prism_bench-host"
