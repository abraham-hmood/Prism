/* The unistd.h surface NINE uses, with Linux semantics, implemented in posix_win.cpp. */
#pragma once
#include <sys/types.h>
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

#define F_OK 0
#define X_OK 1
#define W_OK 2
#define R_OK 4
#define STDIN_FILENO 0
#define STDOUT_FILENO 1
#define STDERR_FILENO 2
#define SEEK_SET 0
#define SEEK_CUR 1
#define SEEK_END 2
#define _SC_PAGESIZE 30
#define _SC_PAGE_SIZE _SC_PAGESIZE
#define _SC_NPROCESSORS_ONLN 84

ssize_t read(int fd, void* buffer, size_t count);
ssize_t write(int fd, const void* buffer, size_t count);
int close(int fd);
off_t lseek(int fd, off_t offset, int whence);
ssize_t pread(int fd, void* buffer, size_t count, off_t offset);
ssize_t pwrite(int fd, const void* buffer, size_t count, off_t offset);
int dup(int fd);
int dup2(int fd, int target);
int fsync(int fd);
int ftruncate(int fd, off_t length);
int isatty(int fd);
int access(const char* path, int mode);
int faccessat(int dirfd, const char* path, int mode, int flags);
char* getcwd(char* buffer, size_t size);
int chdir(const char* path);
int unlink(const char* path);
int rmdir(const char* path);
ssize_t readlink(const char* path, char* buffer, size_t size);
ssize_t readlinkat(int dirfd, const char* path, char* buffer, size_t size);
pid_t getpid(void);
pid_t getppid(void);
uid_t getuid(void);
uid_t geteuid(void);
gid_t getgid(void);
gid_t getegid(void);
long sysconf(int name);
int getpagesize(void);
int usleep(unsigned int microseconds);
unsigned int sleep(unsigned int seconds);
long syscall(long number, ...);

#ifdef __cplusplus
}
#endif
