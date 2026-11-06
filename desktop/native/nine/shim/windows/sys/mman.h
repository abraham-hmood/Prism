/* mmap and friends on VirtualAlloc, with Linux PROT_/MAP_ values. See posix_win.cpp. */
#pragma once
#include <sys/types.h>
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

#define PROT_NONE  0x0
#define PROT_READ  0x1
#define PROT_WRITE 0x2
#define PROT_EXEC  0x4

#define MAP_SHARED    0x01
#define MAP_PRIVATE   0x02
#define MAP_FIXED     0x10
#define MAP_ANONYMOUS 0x20
#define MAP_ANON      MAP_ANONYMOUS
#define MAP_NORESERVE 0x4000
#define MAP_FIXED_NOREPLACE 0x100000
#define MAP_FAILED ((void*)-1)

#define MADV_DONTNEED 4
#define MS_SYNC 4

void* mmap(void* address, size_t length, int prot, int flags, int fd, off_t offset);
int munmap(void* address, size_t length);
int mprotect(void* address, size_t length, int prot);
int madvise(void* address, size_t length, int advice);
int msync(void* address, size_t length, int flags);

#ifdef __cplusplus
}
#endif
