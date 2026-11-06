/* A Linux-shaped struct stat (NINE copies its fields into the guest's), implemented in posix_win.cpp. */
#pragma once
/* MSVC's own first (its _stat64 is what posix_win.cpp fills this from); its struct stat is off. */
#include_next <sys/stat.h>
#include <sys/types.h>
#include <time.h>

#ifdef __cplusplus
extern "C" {
#endif

#define S_IFMT   0170000
#define S_IFSOCK 0140000
#define S_IFLNK  0120000
#define S_IFREG  0100000
#define S_IFBLK  0060000
#define S_IFDIR  0040000
#define S_IFCHR  0020000
#define S_IFIFO  0010000
#define S_ISREG(m) (((m) & S_IFMT) == S_IFREG)
#define S_ISDIR(m) (((m) & S_IFMT) == S_IFDIR)
#define S_ISLNK(m) (((m) & S_IFMT) == S_IFLNK)
#define S_ISCHR(m) (((m) & S_IFMT) == S_IFCHR)
#define S_IRUSR 0400
#define S_IWUSR 0200
#define S_IXUSR 0100
#define S_IRWXU 0700

struct stat {
    dev_t st_dev;
    ino_t st_ino;
    nlink_t st_nlink;
    mode_t st_mode;
    uid_t st_uid;
    gid_t st_gid;
    dev_t st_rdev;
    off_t st_size;
    blksize_t st_blksize;
    blkcnt_t st_blocks;
    struct timespec st_atim;
    struct timespec st_mtim;
    struct timespec st_ctim;
};

int stat(const char* path, struct stat* out);
int lstat(const char* path, struct stat* out);
int fstat(int fd, struct stat* out);
int fstatat(int dirfd, const char* path, struct stat* out, int flags);
int mkdir(const char* path, mode_t mode);
int chmod(const char* path, mode_t mode);

#ifdef __cplusplus
}
#endif
