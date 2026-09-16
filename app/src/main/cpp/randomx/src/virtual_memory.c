/*
Copyright (c) 2018-2019, tevador <tevador@gmail.com>

All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:
	* Redistributions of source code must retain the above copyright
	  notice, this list of conditions and the following disclaimer.
	* Redistributions in binary form must reproduce the above copyright
	  notice, this list of conditions and the following disclaimer in the
	  documentation and/or other materials provided with the distribution.
	* Neither the name of the copyright holder nor the
	  names of its contributors may be used to endorse or promote products
	  derived from this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
*/

#if defined(_WIN32) || defined(__CYGWIN__)
#include <windows.h>
#else
#define _GNU_SOURCE	1	/* needed for MAP_ANONYMOUS on older platforms */
#ifdef __APPLE__
#include <mach/vm_statistics.h>
#include <TargetConditionals.h>
#include <AvailabilityMacros.h>
# if TARGET_OS_OSX
#  define USE_PTHREAD_JIT_WP	1
#  include <pthread.h>
#  include <sys/utsname.h>
#  include <stdio.h>
# endif
#endif
#include <sys/types.h>
#include <sys/mman.h>
#include <errno.h>
#ifndef MAP_ANONYMOUS
#define MAP_ANONYMOUS MAP_ANON
#endif
#define PAGE_READONLY PROT_READ
#define PAGE_READWRITE (PROT_READ | PROT_WRITE)
#define PAGE_EXECUTE_READ (PROT_READ | PROT_EXEC)
#define PAGE_EXECUTE_READWRITE (PROT_READ | PROT_WRITE | PROT_EXEC)
#endif

#include "virtual_memory.h"

#if defined(USE_PTHREAD_JIT_WP) && defined(MAC_OS_VERSION_11_0) \
	&& MAC_OS_X_VERSION_MAX_ALLOWED >= MAC_OS_VERSION_11_0
static int MacOSchecked, MacOSver;
/* This function is used implicitly by clang's __builtin_available() checker.
 * When cross-compiling, the library containing this function doesn't exist,
 * and linking will fail because the symbol is unresolved. The function here
 * is a quick and dirty hack to get close enough to identify MacOSX 11.0.
 */
static int32_t __isOSVersionAtLeast(int32_t major, int32_t minor, int32_t subminor) {
	if (!MacOSchecked) {
	    struct utsname ut;
		int mmaj, mmin;
		uname(&ut);
		sscanf(ut.release, "%d.%d", &mmaj, &mmin);
		// The utsname release version is 9 greater than the canonical OS version
		mmaj -= 9;
		MacOSver = (mmaj << 8) | mmin;
		MacOSchecked = 1;
	}
	return MacOSver >= ((major << 8) | minor);
}
#endif


#if defined(_WIN32) || defined(__CYGWIN__)
#define Fail(func)	do  {*errfunc = func; return GetLastError();} while(0)
int setPrivilege(const char* pszPrivilege, BOOL bEnable, char **errfunc) {
	HANDLE           hToken;
	TOKEN_PRIVILEGES tp;
	BOOL             status;
	DWORD            error = 0;

	*errfunc = NULL;

	if (!OpenProcessToken(GetCurrentProcess(), TOKEN_ADJUST_PRIVILEGES | TOKEN_QUERY, &hToken))
		Fail("OpenProcessToken");

	if (!LookupPrivilegeValue(NULL, pszPrivilege, &tp.Privileges[0].Luid)) {
		*errfunc = "LookupPrivilegeValue";
		error = GetLastError();
		goto out;
	}

	tp.PrivilegeCount = 1;

	if (bEnable)
		tp.Privileges[0].Attributes = SE_PRIVILEGE_ENABLED;
	else
		tp.Privileges[0].Attributes = 0;

	status = AdjustTokenPrivileges(hToken, FALSE, &tp, 0, (PTOKEN_PRIVILEGES)NULL, 0);

	error = GetLastError();
	if (!status || (error != ERROR_SUCCESS)) {
		*errfunc = "AdjustTokenPrivileges";
		goto out;
	}

out:
	if (!CloseHandle(hToken)) {
		if (*errfunc == NULL) {
			*errfunc = "CloseHandle";
			error = GetLastError();
		}
	}
	return error;
}
#else
#define Fail(func)	do  {*errfunc = func; return errno;} while(0)
#endif

/*
 * PRISM PATCH -- W^X workaround for Android.
 *
 * THE PROBLEM. Upstream maps the JIT buffer read/write and later mprotect()s PROT_EXEC onto it.
 * Samsung Knox (and some other hardened Android kernels) refuse that transition outright: a page
 * that has ever been writable may not become executable. The mprotect fails, upstream ignores the
 * failure, and the process dies executing a non-executable page.
 *
 * THE WORKAROUND, in order of preference:
 *
 *   1. RWX AT MMAP TIME. Some policies permit an anonymous mapping created executable while
 *      refusing to add PROT_EXEC afterwards. Costs nothing to try.
 *
 *   2. DUAL MAPPING VIA memfd. Two mappings of the same memfd: one read/write, one read/execute.
 *      Neither page is ever both, so W^X is satisfied rather than circumvented -- this is what
 *      ART's own JIT does on Android. The caller writes through the RW pointer and executes at
 *      the RX pointer, which is why randomx_prism_exec_offset() exists: the JIT keeps writing to
 *      the pointer it was given and only its two entry points are shifted.
 *
 *   3. The original behaviour, with the failure now reported rather than swallowed.
 *
 * Re-apply this if RandomX is ever updated.
 */
#if !defined(_WIN32) && !defined(__CYGWIN__) && !defined(__APPLE__)
#include <pthread.h>
#include <stdio.h>
#include <stdint.h>
#include <sys/syscall.h>
#include <unistd.h>

#ifndef MFD_CLOEXEC
#define MFD_CLOEXEC 0x0001U
#endif

#define PRISM_MAX_REGIONS 64

enum prism_mode { PRISM_PLAIN = 0, PRISM_RWX = 1, PRISM_DUAL = 2 };

struct prism_region {
	void *rw;
	void *rx;
	size_t bytes;
	int mode;
	int fd;
};

static struct prism_region prism_regions[PRISM_MAX_REGIONS];
static pthread_mutex_t prism_lock = PTHREAD_MUTEX_INITIALIZER;

static struct prism_region *prism_find(const void *ptr) {
	for (int i = 0; i < PRISM_MAX_REGIONS; i++) {
		if (prism_regions[i].rw == ptr && prism_regions[i].bytes != 0) return &prism_regions[i];
	}
	return NULL;
}

static struct prism_region *prism_slot(void) {
	for (int i = 0; i < PRISM_MAX_REGIONS; i++) {
		if (prism_regions[i].bytes == 0) return &prism_regions[i];
	}
	return NULL;
}

/* Non-zero when this pointer is one of ours, so the mprotect calls become no-ops. */
int randomx_prism_is_managed(const void *ptr) {
	pthread_mutex_lock(&prism_lock);
	int found = prism_find(ptr) != NULL;
	pthread_mutex_unlock(&prism_lock);
	return found;
}

/* Distance from the writable pointer to the executable one. Zero unless dual-mapped. */
ptrdiff_t randomx_prism_exec_offset(const void *ptr) {
	pthread_mutex_lock(&prism_lock);
	struct prism_region *r = prism_find(ptr);
	ptrdiff_t delta = (r != NULL && r->mode == PRISM_DUAL)
		? ((uint8_t *)r->rx - (uint8_t *)r->rw) : 0;
	pthread_mutex_unlock(&prism_lock);
	return delta;
}

/*
 * PRISM PATCH: is this address ACTUALLY executable?
 *
 * mmap() returning success is not proof. Hardened kernels -- Samsung Knox among them -- accept a
 * PROT_EXEC request and then hand back a mapping without the execute bit, so the first branch into
 * it dies with SEGV_ACCERR and no backtrace. /proc/self/maps reports what the kernel really did,
 * which is the only way to find out short of executing the page and hoping.
 */
static int prism_region_executable(const void *addr) {
	FILE *maps = fopen("/proc/self/maps", "re");
	if (maps == NULL) return 0;

	const uintptr_t target = (uintptr_t)addr;
	char line[512];
	int executable = 0;
	while (fgets(line, sizeof(line), maps) != NULL) {
		unsigned long long start = 0, end = 0;
		char perms[8] = {0};
		if (sscanf(line, "%llx-%llx %7s", &start, &end, perms) != 3) continue;
		if (target >= (uintptr_t)start && target < (uintptr_t)end) {
			executable = (perms[2] == 'x');
			break;
		}
	}
	fclose(maps);
	return executable;
}

void *randomx_prism_alloc_exec(size_t bytes) {
	pthread_mutex_lock(&prism_lock);
	struct prism_region *slot = prism_slot();
	pthread_mutex_unlock(&prism_lock);
	if (slot == NULL) return NULL;

	/*
	 * 1. DUAL MAPPING FIRST, not second.
	 *
	 * This is the arrangement a hardened kernel is most likely to allow, because it never asks for
	 * a page that is writable and executable at once -- it satisfies W^X rather than asking for an
	 * exception to it. It is what ART's own JIT does on Android. Trying RWX first meant a kernel
	 * that "succeeds" at RWX while quietly dropping the execute bit was taken at its word, and the
	 * safer path was never reached.
	 */
	int fd = (int)syscall(__NR_memfd_create, "randomx-jit", MFD_CLOEXEC);
	if (fd >= 0) {
		if (ftruncate(fd, (off_t)bytes) == 0) {
			void *rw = mmap(NULL, bytes, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
			if (rw != MAP_FAILED) {
				void *rx = mmap(NULL, bytes, PROT_READ | PROT_EXEC, MAP_SHARED, fd, 0);
				if (rx != MAP_FAILED) {
					if (prism_region_executable(rx)) {
						pthread_mutex_lock(&prism_lock);
						slot->rw = rw; slot->rx = rx; slot->bytes = bytes;
						slot->mode = PRISM_DUAL; slot->fd = fd;
						pthread_mutex_unlock(&prism_lock);
						return rw;
					}
					munmap(rx, bytes);
				}
				munmap(rw, bytes);
			}
		}
		close(fd);
	}

	/* 2. Executable from the start, verified the same way. */
	void *mem = mmap(NULL, bytes, PROT_READ | PROT_WRITE | PROT_EXEC,
	                 MAP_ANONYMOUS | MAP_PRIVATE, -1, 0);
	if (mem != MAP_FAILED) {
		if (prism_region_executable(mem)) {
			pthread_mutex_lock(&prism_lock);
			slot->rw = mem; slot->rx = mem; slot->bytes = bytes;
			slot->mode = PRISM_RWX; slot->fd = -1;
			pthread_mutex_unlock(&prism_lock);
			return mem;
		}
		munmap(mem, bytes);
	}

	/*
	 * 3. Nothing on this device will execute app-allocated memory. Reported rather than papered
	 * over, so the caller uses the interpreter instead of crashing on the first branch.
	 */
	return NULL;
}

/* Whether any executable mapping could be obtained at all, for honest reporting up the stack. */
int randomx_prism_exec_supported(void) {
	const size_t probe = 4096;
	void *mem = randomx_prism_alloc_exec(probe);
	if (mem == NULL) return 0;
	randomx_prism_free_exec(mem, probe);
	return 1;
}

void randomx_prism_free_exec(void *ptr, size_t bytes) {
	pthread_mutex_lock(&prism_lock);
	struct prism_region *r = prism_find(ptr);
	if (r != NULL) {
		if (r->mode == PRISM_DUAL) {
			munmap(r->rx, r->bytes);
			munmap(r->rw, r->bytes);
			if (r->fd >= 0) close(r->fd);
		} else {
			munmap(r->rw, r->bytes);
		}
		r->rw = NULL; r->rx = NULL; r->bytes = 0; r->mode = PRISM_PLAIN; r->fd = -1;
	}
	pthread_mutex_unlock(&prism_lock);
	(void)bytes;
}
#else
int randomx_prism_is_managed(const void *ptr) { (void)ptr; return 0; }
ptrdiff_t randomx_prism_exec_offset(const void *ptr) { (void)ptr; return 0; }
void *randomx_prism_alloc_exec(size_t bytes) { (void)bytes; return NULL; }
void randomx_prism_free_exec(void *ptr, size_t bytes) { (void)ptr; (void)bytes; }
int randomx_prism_exec_supported(void) { return 0; }
#endif

void* allocMemoryPages(size_t bytes) {
	void* mem;
#if defined(_WIN32) || defined(__CYGWIN__)
	mem = VirtualAlloc(NULL, bytes, MEM_COMMIT, PAGE_READWRITE);
#else
	#if defined(__NetBSD__)
		#define RESERVED_FLAGS PROT_MPROTECT(PROT_EXEC)
	#else
		#define RESERVED_FLAGS 0
	#endif
	#ifdef USE_PTHREAD_JIT_WP
		#define MEXTRA MAP_JIT
		#define PEXTRA	PROT_EXEC
	#else
		#define MEXTRA 0
		#define PEXTRA	0
	#endif
	mem = mmap(NULL, bytes, PAGE_READWRITE | RESERVED_FLAGS | PEXTRA, MAP_ANONYMOUS | MAP_PRIVATE | MEXTRA, -1, 0);
	if (mem == MAP_FAILED)
		mem = NULL;
#if defined(USE_PTHREAD_JIT_WP) && defined(MAC_OS_VERSION_11_0) \
	&& MAC_OS_X_VERSION_MAX_ALLOWED >= MAC_OS_VERSION_11_0
	if (__builtin_available(macOS 11.0, *)) {
		pthread_jit_write_protect_np(0);
	}
#endif
#endif
	return mem;
}

/*
 * PRISM PATCH -- see also virtual_memory.h and randomx_jni.cpp.
 *
 * Upstream calls pageProtect() from setPagesRX()/setPagesRWX() and DISCARDS THE RETURN VALUE. On
 * Android that is fatal rather than untidy: SELinux denies mprotect(PROT_EXEC) on anonymous memory
 * for ordinary apps on some devices (observed on a Samsung S22, Android 14), so the page stays
 * non-executable, RandomX jumps into it anyway, and the process dies with SIGSEGV/SEGV_ACCERR and
 * no usable backtrace -- it is executing memory it is not allowed to execute.
 *
 * There is no return path to report this through: setPagesRX returns void and is called deep inside
 * the JIT compiler's constructor. So the failure is recorded in a flag the embedder can check after
 * creating a VM, and fall back to the interpreter.
 *
 * Re-apply this if RandomX is ever updated.
 */
static volatile int prism_protect_failed = 0;

int randomx_prism_protect_failed(void) {
	return prism_protect_failed;
}

void randomx_prism_reset_protect_failed(void) {
	prism_protect_failed = 0;
}

static inline int pageProtect(void* ptr, size_t bytes, int rules, char **errfunc) {
#if defined(_WIN32) || defined(__CYGWIN__)
	DWORD oldp;
	if (!VirtualProtect(ptr, bytes, (DWORD)rules, &oldp)) {
		Fail("VirtualProtect");
	}
#else
	if (-1 == mprotect(ptr, bytes, rules)) {
		/* PRISM PATCH: record it, because every caller below ignores this return value. */
		prism_protect_failed = 1;
		Fail("mprotect");
	}
#endif
	return 0;
}

void setPagesRW(void* ptr, size_t bytes) {
	char *errfunc;
	/* PRISM PATCH: already correct by construction; mprotect would only be refused. */
	if (randomx_prism_is_managed(ptr)) return;
#if defined(USE_PTHREAD_JIT_WP) && defined(MAC_OS_VERSION_11_0) \
	&& MAC_OS_X_VERSION_MAX_ALLOWED >= MAC_OS_VERSION_11_0
	if (__builtin_available(macOS 11.0, *)) {
		pthread_jit_write_protect_np(0);
	} else {
		pageProtect(ptr, bytes, PAGE_READWRITE, &errfunc);
	}
#else
	pageProtect(ptr, bytes, PAGE_READWRITE, &errfunc);
#endif
}

void setPagesRX(void* ptr, size_t bytes) {
	char *errfunc;
	/* PRISM PATCH: already correct by construction; mprotect would only be refused. */
	if (randomx_prism_is_managed(ptr)) return;
#if defined(USE_PTHREAD_JIT_WP) && defined(MAC_OS_VERSION_11_0) \
	&& MAC_OS_X_VERSION_MAX_ALLOWED >= MAC_OS_VERSION_11_0
	if (__builtin_available(macOS 11.0, *)) {
		pthread_jit_write_protect_np(1);
		__builtin___clear_cache((char*)ptr, ((char*)ptr) + bytes);
	} else {
		pageProtect(ptr, bytes, PAGE_EXECUTE_READ, &errfunc);
	}
#else
	pageProtect(ptr, bytes, PAGE_EXECUTE_READ, &errfunc);
#endif
}

void setPagesRWX(void* ptr, size_t bytes) {
	char *errfunc;
	/* PRISM PATCH: already correct by construction; mprotect would only be refused. */
	if (randomx_prism_is_managed(ptr)) return;
	pageProtect(ptr, bytes, PAGE_EXECUTE_READWRITE, &errfunc);
}

void* allocLargePagesMemory(size_t bytes) {
	void* mem;
	char *errfunc;
#if defined(_WIN32) || defined(__CYGWIN__)
	if (setPrivilege("SeLockMemoryPrivilege", 1, &errfunc))
		return NULL;
	size_t pageMinimum = GetLargePageMinimum();
	if (!pageMinimum) {
		errfunc = "No large pages";
		return NULL;
	}
	mem = VirtualAlloc(NULL, alignSize(bytes, pageMinimum), MEM_COMMIT | MEM_RESERVE | MEM_LARGE_PAGES, PAGE_READWRITE);
#else
#ifdef __APPLE__
	mem = mmap(NULL, bytes, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, VM_FLAGS_SUPERPAGE_SIZE_2MB, 0);
#elif defined(__FreeBSD__)
	mem = mmap(NULL, bytes, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS | MAP_ALIGNED_SUPER, -1, 0);
#elif defined(__OpenBSD__) || defined(__NetBSD__)
	mem = MAP_FAILED; // OpenBSD does not support huge pages
#else
	mem = mmap(NULL, bytes, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS | MAP_HUGETLB | MAP_POPULATE, -1, 0);
#endif
	if (mem == MAP_FAILED)
		mem = NULL;
#endif
	return mem;
}

void freePagedMemory(void* ptr, size_t bytes) {
#if defined(_WIN32) || defined(__CYGWIN__)
	VirtualFree(ptr, 0, MEM_RELEASE);
#else
	munmap(ptr, bytes);
#endif
}
