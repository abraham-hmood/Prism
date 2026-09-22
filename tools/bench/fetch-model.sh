#!/usr/bin/env bash
#
# Fetches the benchmark model: DeepSeek-R1-Distill-Qwen-1.5B, Q4_K_M.
#
# 1.5B parameters at roughly 4.5 bits each, so about 1.1 GB on disk. That size is the whole point of
# it as a benchmark: token generation is memory-bandwidth-bound, so 1.1 GB of weights read once per
# token sets a hard ceiling of (memory bandwidth / 1.1 GB) tokens per second, and how close a build
# gets to that ceiling is the only meaningful measure of the inference path.
set -euo pipefail

REPO="unsloth/DeepSeek-R1-Distill-Qwen-1.5B-GGUF"
FILE="DeepSeek-R1-Distill-Qwen-1.5B-Q4_K_M.gguf"
DEST="${1:-$HOME/prismbench}"

mkdir -p "$DEST"
cd "$DEST"

if [ -f "$FILE" ]; then
  echo "already have $FILE ($(du -h "$FILE" | cut -f1))"
  exit 0
fi

echo "listing $REPO"
curl -sL "https://huggingface.co/api/models/$REPO" |
  tr ',' '\n' | grep -o '"rfilename":"[^"]*"' | cut -d'"' -f4 || true

echo "downloading $FILE"
curl -L --fail --retry 3 -o "$FILE.part" \
  "https://huggingface.co/$REPO/resolve/main/$FILE?download=true"
mv "$FILE.part" "$FILE"

ls -lh "$FILE"
