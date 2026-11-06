/* open()/fcntl() with LINUX flag values: NINE passes the guest's flags through unchanged. */
#pragma once
/* MSVC's _O_ constants (used by posix_win.cpp); its unprefixed O_ names are off. */
#include_next <fcntl.h>
#include <sys/types.h>

#ifdef __cplusplus
extern "C" {
#endif

#define O_RDONLY    00
#define O_WRONLY    01
#define O_RDWR      02
#define O_ACCMODE   03
#define O_CREAT     0100
#define O_EXCL      0200
#define O_NOCTTY    0400
#define O_TRUNC     01000
#define O_APPEND    02000
#define O_NONBLOCK  04000
#define O_DSYNC     010000
#define O_DIRECTORY 0200000
#define O_NOFOLLOW  0400000
#define O_CLOEXEC   02000000
#define O_LARGEFILE 0

#define F_DUPFD 0
#define F_GETFD 1
#define F_SETFD 2
#define F_GETFL 3
#define F_SETFL 4
#define F_DUPFD_CLOEXEC 1030
#define FD_CLOEXEC 1

#define AT_FDCWD -100
#define AT_SYMLINK_NOFOLLOW 0x100
#define AT_REMOVEDIR 0x200
#define AT_EMPTY_PATH 0x1000

int open(const char* path, int flags, ...);
int openat(int dirfd, const char* path, int flags, ...);
int fcntl(int fd, int command, ...);

#ifdef __cplusplus
}
#endif
