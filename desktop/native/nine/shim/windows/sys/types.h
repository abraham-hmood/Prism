/* POSIX types MSVC leaves out (or declares only under its non-standard names, which are off). */
#pragma once
#include_next <sys/types.h>
#include <stddef.h>
#include <stdint.h>

#ifndef NINE_POSIX_TYPES
#define NINE_POSIX_TYPES
typedef int64_t ssize_t;
typedef int64_t off_t;
typedef int64_t off64_t;
typedef int pid_t;
typedef unsigned int uid_t;
typedef unsigned int gid_t;
typedef unsigned int mode_t;
typedef uint64_t dev_t;
typedef uint64_t ino_t;
typedef uint64_t nlink_t;
typedef int64_t blksize_t;
typedef int64_t blkcnt_t;
#endif
