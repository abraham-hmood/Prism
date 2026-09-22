#!/usr/bin/env bash
#
# The configuration sweep: thread count, KV cache type, batch size, mmap/repack.
#
# Everything here is a property of the algorithm and the memory hierarchy rather than of an
# instruction set, which is why it is worth running in the sandbox: no other workload, repeatable,
# and the conclusions carry to ARM. The ARM-only questions (dotprod, i8mm) are not in here and
# cannot be -- those instructions do not exist on x86.
#
# Usage: sweep.sh <binary> <model.gguf> [tag]
set -euo pipefail

BIN="${1:?usage: sweep.sh <binary> <model.gguf> [tag]}"
MODEL="${2:?usage: sweep.sh <binary> <model.gguf> [tag]}"
TAG="${3:-sweep}"

run() {
  # Each run prints one RESULT line carrying its own load average, so a noisy machine is visible in
  # the data rather than remembered.
  "$BIN" -m "$MODEL" -r 2 "$@" 2>/dev/null | grep '^RESULT' || echo "RESULT label=FAILED $*"
}

echo "### threads (prompt 128, generate 64)"
for t in 1 2 4 6; do
  run -p 128 -n 64 -t "$t" --label "$TAG-t$t"
done

echo "### kv cache type, at the best thread count so far"
run -p 128 -n 64 -t 4 --kv 0 --label "$TAG-kv-f16"
run -p 128 -n 64 -t 4 --kv 1 --label "$TAG-kv-q8"
run -p 128 -n 64 -t 4 --kv 2 --label "$TAG-kv-q4"

echo "### flash attention"
run -p 128 -n 64 -t 4 --fa 0 --label "$TAG-fa-off"
run -p 128 -n 64 -t 4 --fa 1 --label "$TAG-fa-on"

echo "### mmap versus an in-memory copy (the latter is what lets ggml repack for ARM)"
run -p 128 -n 64 -t 4 --label "$TAG-mmap"
run -p 128 -n 64 -t 4 --no-mmap --label "$TAG-nommap"

echo "### prompt batch size, which is what prompt processing scales with"
run -p 512 -n 16 -t 4 -b 128 --label "$TAG-b128"
run -p 512 -n 16 -t 4 -b 512 --label "$TAG-b512"
