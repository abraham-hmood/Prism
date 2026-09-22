#!/usr/bin/env bash
#
# Builds PRoot for Android arm64 -- the loader that makes the whole Windows runtime possible.
#
# WHY PROOT IS THE LOAD-BEARING PIECE
#
# Android refuses to execve() anything stored in an app's writable directory (targetSdk >= 29). A
# rootfs lives in exactly such a directory, so nothing inside it can be executed directly: not
# Wine, not box64, not /bin/sh.
#
# PRoot gets around this without any privilege, and the mechanism is specific. It ptrace-intercepts
# the guest's execve and substitutes its own LOADER -- a small static ELF that ships in
# nativeLibraryDir, which IS executable -- then that loader mmaps the real binary and jumps to it.
# The kernel therefore only ever execve()s a file it is allowed to. mmap(PROT_EXEC) on app storage
# is still permitted, which is the same asymmetry that lets System.load() work on a downloaded .so.
#
# This is the identical mechanism Termux's proot-distro, UserLAnd and Winlator all use.
#
# WHAT IT PRODUCES
#
#   app/src/main/jniLibs/arm64-v8a/libproot.so          the tracer
#   app/src/main/jniLibs/arm64-v8a/libproot-loader.so   the loader it execs
#
# Both are named lib*.so because that is the only way to get a file into nativeLibraryDir, which is
# the only directory an app may execute from. They are ordinary executables, not shared libraries;
# the naming is a packaging requirement, not a description.
#
# Usage:  tools/wine/build-proot.sh [ndk-path]
set -euo pipefail

NDK="${1:-$HOME/nodebuild/android-ndk-r27d}"
WORK="${PROOT_BUILD_DIR:-$HOME/prootbuild}"
API=24

if [ ! -d "$NDK" ]; then
  echo "No NDK at $NDK -- pass its path as the first argument." >&2
  exit 1
fi

TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin"
export CC="$TOOLCHAIN/aarch64-linux-android$API-clang"
export AR="$TOOLCHAIN/llvm-ar"
export STRIP="$TOOLCHAIN/llvm-strip"

# GNU objcopy, specifically.
#
# proot embeds the built loader into the tracer with `objcopy -O elf64-little`. llvm-objcopy
# rejects that format name ("invalid output format: elf64-little") -- it wants the fully
# qualified elf64-littleaarch64. Rather than patch proot's makefile, use the binutils objcopy
# that understands the name proot actually passes.
if ! command -v aarch64-linux-gnu-objcopy >/dev/null 2>&1; then
  sudo apt-get install -y binutils-aarch64-linux-gnu
fi

mkdir -p "$WORK"
cd "$WORK"

echo "=== 1/4 sources ==="
# Termux's fork, not upstream PRoot. Upstream has not tracked Android's changes -- the fork carries
# the fixes for bionic, for newer kernels' ptrace behaviour, and for the loader placement that this
# whole approach depends on.
[ -d proot ] || git clone --depth 1 https://github.com/termux/proot.git
[ -d talloc ] || {
  curl -fL --retry 3 -o talloc.tar.gz https://download.samba.org/pub/talloc/talloc-2.4.2.tar.gz
  tar xzf talloc.tar.gz
  mv talloc-2.4.2 talloc
}

echo "=== 2/4 talloc ==="
#
# PRoot's only real dependency, built by hand rather than through talloc's waf build system --
# waf runs configure tests by executing them, which a cross-compile cannot do.
#
# talloc.c includes "replace.h", which is libreplace: Samba's portability layer, shipped inside the
# talloc tarball at lib/replace. Nearly everything it replaces, bionic already has, so the config
# header below is mostly a list of "do not substitute this" -- every HAVE_ that is claimed turns a
# libreplace shim into a passthrough to the real libc function.
cd "$WORK/talloc"
cat > config.h <<'EOF'
#define HAVE_STDINT_H 1
#define HAVE_STDLIB_H 1
#define HAVE_STRING_H 1
#define HAVE_STRINGS_H 1
#define HAVE_UNISTD_H 1
#define HAVE_SYS_TYPES_H 1
#define HAVE_SYS_STAT_H 1
#define HAVE_SYS_TIME_H 1
#define HAVE_FCNTL_H 1
#define HAVE_ERRNO_H 1
#define HAVE_LIMITS_H 1
#define HAVE_CTYPE_H 1
#define HAVE_STDARG_H 1
#define HAVE_STDBOOL_H 1
#define HAVE_INTTYPES_H 1
#define HAVE_MEMORY_H 1
#define HAVE_DIRENT_H 1
#define HAVE_SIGNAL_H 1
#define HAVE_TIME_H 1
#define HAVE_UTIME_H 1

#define HAVE_VA_COPY 1
#define HAVE__VA_ARGS__MACRO 1
#define HAVE_COMPARISON_FN_T 1

#define HAVE_STRNLEN 1
#define HAVE_STRNDUP 1
#define HAVE_STRTOULL 1
#define HAVE_STRTOLL 1
#define HAVE_SETENV 1
#define HAVE_UNSETENV 1
#define HAVE_VASPRINTF 1
#define HAVE_ASPRINTF 1
#define HAVE_VSNPRINTF 1
#define HAVE_SNPRINTF 1
#define HAVE_DPRINTF 1
/* Each printf gate in replace.h is an OR: !HAVE_FOO || !HAVE_C99_VSNPRINTF. So the four
 defines above are not enough on their own -- without this one, replace.h still renames
 fprintf/vsnprintf to rep_*, and talloc then fails to link against a replace.c that is
 deliberately not built. Bionic qualifies: its vsnprintf returns the length it would have
 written rather than -1 on truncation, which is exactly what the macro asserts. */
#define HAVE_C99_VSNPRINTF 1
#define HAVE_MEMMOVE 1
#define HAVE_MKSTEMP 1
#define HAVE_MKDTEMP 1
#define HAVE_PREAD 1
#define HAVE_PWRITE 1
#define HAVE_CHOWN 1
#define HAVE_LCHOWN 1
#define HAVE_INITGROUPS 1
#define HAVE_BZERO 1
#define HAVE_DLOPEN 1
#define HAVE_SECURE_MKSTEMP 1
#define HAVE_INTPTR_T 1
#define HAVE_UINTPTR_T 1
#define HAVE_PTRDIFF_T 1
#define HAVE_BOOL 1
#define HAVE_USLEEP 1
#define HAVE_DLFCN_H 1
#define HAVE_SYS_SOCKET_H 1
#define HAVE_NETINET_IN_H 1

/* talloc stamps its own version into the allocation magic, and normally gets these from waf.
 They must match the tarball being built or the magic check rejects every block at runtime. */
#define TALLOC_BUILD_VERSION_MAJOR 2
#define TALLOC_BUILD_VERSION_MINOR 4
#define TALLOC_BUILD_VERSION_RELEASE 2
EOF
INCLUDES="-I. -Ilib/replace"

# replace.c is deliberately NOT compiled.
#
# libreplace substitutes for libc functions a platform lacks, and bionic has every one talloc
# actually calls -- so the HEADER alone is what is needed, with the HAVE_ defines above turning
# each shim into a passthrough. Compiling the .c as well dragged in its time, socket and dirent
# substitutes, which then wanted a far larger config than talloc has any use for (it failed on
# an incomplete struct tm). Anything genuinely missing would surface as an undefined symbol at
# link time rather than silently.
#
# The header must be named config.h: replace.h includes it by that exact name under
# HAVE_CONFIG_H, so force-including it as anything else leaves replace.h still looking.
# Cached on the config header rather than on mere existence: an earlier version of this guard
# was `if [ ! -f libtalloc.a ]`, which silently reused an archive built from a stale config.h
# and reproduced a link error the edited config had already fixed.
if [ ! -f libtalloc.a ] || [ config.h -nt libtalloc.a ]; then
  "$CC" -c talloc.c -o talloc.o $INCLUDES \
      -DHAVE_CONFIG_H -D__STDC_WANT_LIB_EXT1__=1 -O2 -fPIC -Wno-everything
  "$AR" rcs libtalloc.a talloc.o
fi

echo "=== 3/4 proot ==="
cd "$WORK/proot/src"
make clean >/dev/null 2>&1 || true

# The loader is built first and embedded in the tracer as a byte array, which is why the tracer
# cannot be built without it and why both fall out of one make.
make -j"$(getconf _NPROCESSORS_ONLN)" \
  CC="$CC" \
  AR="$AR" \
  STRIP="$STRIP" \
  OBJDUMP="aarch64-linux-gnu-objdump" \
  OBJCOPY="aarch64-linux-gnu-objcopy" \
  LD="$CC" \
  CROSS_COMPILE="" \
  CPPFLAGS="-I. -I$WORK/talloc -I$WORK/talloc/lib/replace -DHAVE_CONFIG_H -D_GNU_SOURCE -Wno-implicit-function-declaration -Wno-int-conversion" \
  LDFLAGS="-L$WORK/talloc -ltalloc -static-libgcc" \
  proot loader

echo "=== 4/4 staging ==="
DEST="${PRISM_JNILIBS:-/mnt/f/AndroidStudioProjects/Prism/app/src/main/jniLibs/arm64-v8a}"
mkdir -p "$DEST"

# loader.elf on some revisions, loader on others.
if [ -f loader/loader ]; then
  LOADER=loader/loader
elif [ -f loader.elf ]; then
  LOADER=loader.elf
else
  echo "Built, but no loader binary found -- look in $WORK/proot/src" >&2
  exit 1
fi

# Strip into the ext4 work tree, then copy across. llvm-strip writing its output directly onto
# /mnt/f fails with "Input/output error" -- it wants operations on the output file that DrvFs,
# WSL's Windows-drive filesystem, does not support. cp only needs sequential writes.
"$STRIP" proot -o proot.stripped
"$STRIP" "$LOADER" -o loader.stripped
cp proot.stripped "$DEST/libproot.so"
cp loader.stripped "$DEST/libproot-loader.so"

echo "=== done ==="
ls -lh "$DEST/libproot.so" "$DEST/libproot-loader.so"
