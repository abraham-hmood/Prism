#!/usr/bin/env bash
#
# Builds libnode.so -- a full Node.js runtime as an Android shared library -- for Prism's editor.
#
# WHY THIS EXISTS
#
# Prism's extension host started as a Web Worker running a `vscode` API shim, which is how
# vscode.dev runs extensions in a browser. That covers web extensions and nothing else: the
# majority of the marketplace declares a `main` entry point, calls `require('fs')`, `child_process`
# or a native addon, and simply cannot load without a real Node. So Prism carries one.
#
# WHAT IT BUILDS
#
# The recipe is nodejs-mobile, which is not a fork of Node but a patch series against a pinned
# upstream release (currently v24.21.0) plus the mobile build scripts. That matters for two reasons:
# the patches are small and readable, and the resulting binary is built with 16 KB page alignment,
# which Android 15 devices require and which a hand-rolled cross-compile of upstream Node does not
# give you.
#
# HOST REQUIREMENTS
#
# Linux only. Node's own build system archives static libraries as GNU thin archives and links its
# host tools with `-Wl,--start-group`; neither exists outside binutils, so a macOS or MSYS host
# fails partway through with errors that look unrelated. On Windows this means WSL.
#
# Usage:  tools/node/build-libnode.sh [arm64|arm|x86_64]   (default: arm64)
set -euo pipefail

ARCH="${1:-arm64}"
WORK="${NODE_BUILD_DIR:-$HOME/nodebuild}"
NDK_VERSION="r27d"
NDK_DIR="$WORK/android-ndk-$NDK_VERSION"
RECIPE="$WORK/nodejs-mobile"
# Android API level the runtime targets. Prism's minSdk is 26; 24 costs nothing and keeps the
# library usable if that ever drops.
SDK_VERSION=24

mkdir -p "$WORK"
cd "$WORK"

echo "=== 1/5 host packages ==="
if ! command -v python3.13 >/dev/null 2>&1 && ! command -v python3.12 >/dev/null 2>&1; then
  # gyp-next and V8's code generators need 3.12+. Ubuntu 22.04 ships 3.10, so the PPA is the
  # cheapest way to a supported interpreter -- building one from source here would dwarf the
  # actual Node build.
  sudo add-apt-repository -y ppa:deadsnakes/ppa
  sudo apt-get update -y
  sudo apt-get install -y python3.13 python3.13-venv python3.13-dev
fi
sudo apt-get install -y build-essential git gcc-multilib g++-multilib curl unzip lsb-release wget software-properties-common gnupg

# V8's host tools (torque, mksnapshot, the builtins generators) are compiled with the HOST
# compiler, and Node 24's V8 uses C++20 features -- P0634, "down with typename" -- that clang 14
# does not implement. Ubuntu 22.04 ships clang 14, so the build dies partway through host codegen
# with a wall of "missing 'typename' prior to dependent type name". Node's own CI builds on
# ubuntu-24.04 for this reason. Installing a current clang is cheaper than moving distributions.
if ! command -v clang++-18 >/dev/null 2>&1; then
  wget -qO /tmp/llvm.sh https://apt.llvm.org/llvm.sh
  chmod +x /tmp/llvm.sh
  sudo /tmp/llvm.sh 18
fi
# NOTE: exporting CC_host/CXX_host does NOT work -- node's configure detects the host compiler
# itself and writes an absolute path into the generated makefile, ignoring the environment. The
# override therefore happens after configure, below.

PY="$(command -v python3.13 || command -v python3.12)"
echo "python: $PY"

echo "=== 2/5 android ndk ==="
if [ ! -d "$NDK_DIR" ]; then
  curl -fL --retry 3 -o ndk.zip "https://dl.google.com/android/repository/android-ndk-$NDK_VERSION-linux.zip"
  unzip -q ndk.zip
  rm -f ndk.zip
fi
ls "$NDK_DIR/toolchains/llvm/prebuilt" >/dev/null

echo "=== 3/5 source tree ==="
if [ ! -d "$RECIPE" ]; then
  git clone --depth 1 https://github.com/fogtape/nodejs-mobile.git "$RECIPE"
fi
cd "$RECIPE"
# prepare.sh clones the pinned upstream tag, applies patches/series, overlays mobile-src, and
# verifies the tree hash. Re-running it on an existing ./out is wasteful, so only do it once.
if [ ! -d out ]; then
  scripts/prepare.sh
fi

echo "=== 4/5 python venv ==="
if [ ! -d "$WORK/.venv" ]; then
  "$PY" -m venv "$WORK/.venv"
fi
# shellcheck disable=SC1091
. "$WORK/.venv/bin/activate"
# gyp-next declares setuptools as a build dependency and a bare venv does not bundle it.
pip -q install setuptools

echo "=== 5/5 build ($ARCH) ==="
cd "$RECIPE/out"

# tools/android_build.sh would do configure-then-make in one step, which leaves no point at which
# the host compiler can be corrected -- so its two halves are run separately here instead.
if [ ! -f out/Makefile ]; then
  ./android-configure "$NDK_DIR" "$SDK_VERSION" "$ARCH"
fi

# Redirect the HOST half of the build at clang 18. node's configure hard-codes whatever `clang` it
# found, and the `?=` in the generated makefile means an environment variable cannot override it
# (make variable names cannot contain a dot). Editing the generated file is the only lever, and it
# survives an incremental rebuild, which matters because a failed host link should not cost the
# hours of target compilation that already succeeded.
sed -i 's|^CC.host ?= .*|CC.host ?= /usr/bin/clang-18|; s|^CXX.host ?= .*|CXX.host ?= /usr/bin/clang++-18|' out/Makefile

# Parallelism is derived from MEMORY, not core count.
#
# Several of V8's translation units -- src/builtins/builtins.cc worst of all -- peak around 8 GB in
# clang. Running as many of those as there are cores gets the compiler OOM-killed, which surfaces
# as a bare "Killed" and an exit code of 137 and looks nothing like a memory problem; it also
# happens deep into an hours-long build. Roughly 8 GB per job is what actually survives.
#
# Note the machine's swap, not just its RAM: a .wslconfig that declares swap is not proof there is
# any (`free -h` is), and a build relying on swap that was never mounted dies the same way.
# Swap first, because without it a single memory spike is fatal rather than slow.
#
# A .wslconfig that declares swap is NOT proof there is any -- this machine's declared 40 GB never
# mounted, and the build died three times before anyone checked `free`. An in-distro swapfile is
# self-contained, needs no host configuration, and turns an OOM kill into a slow minute.
if [ "$(awk '/SwapTotal/ {print $2}' /proc/meminfo)" -lt 1048576 ]; then
  echo "no swap; creating one so a memory spike does not kill the build"
  sudo fallocate -l 16G /swapfile
  sudo chmod 600 /swapfile
  sudo mkswap /swapfile >/dev/null
  sudo swapon /swapfile
fi

TOTAL_MB="$(awk '/MemTotal/ {print int($2/1024)}' /proc/meminfo)"
BY_MEMORY=$(( TOTAL_MB / 8192 ))
[ "$BY_MEMORY" -lt 1 ] && BY_MEMORY=1
BY_CORES="$(getconf _NPROCESSORS_ONLN)"
JOBS="${JOBS:-$(( BY_MEMORY < BY_CORES ? BY_MEMORY : BY_CORES ))}"
echo "building with -j$JOBS (${TOTAL_MB} MB RAM, ${BY_CORES} cores)"
make -j "$JOBS"

OUT_DIR="out_android/$( [ "$ARCH" = "arm64" ] && echo arm64-v8a || { [ "$ARCH" = "arm" ] && echo armeabi-v7a || echo "$ARCH"; } )"
mkdir -p "$OUT_DIR"
cp out/Release/lib.target/libnode.so "$OUT_DIR/libnode.so" 2>/dev/null ||
  cp out/Release/obj.target/libnode.so "$OUT_DIR/libnode.so"

echo "=== done ==="
find "$RECIPE/out/out_android" -name 'libnode.so' -print -exec ls -lh {} \;
