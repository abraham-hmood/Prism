// The Linux/POSIX surface NINE's OS layer is written against, implemented on Win32.
//
// NINE runs on Linux (Android) and, there, passes a guest's syscalls straight to the host kernel:
// open() gets the guest's own O_ flags, fstat() fills a Linux struct stat, getdents64 is a raw
// syscall. This file is that host, on Windows. Flag values and structures are Linux's (the headers
// beside this file define them), and each call is carried out with the Win32 or CRT equivalent.
//
// Files are CRT descriptors opened in binary mode. Directories are not openable on Windows, so an
// open() of a directory returns a descriptor from a small table of its own, which getdents64,
// fstat and close understand.

#include <fcntl.h>
#include <unistd.h>
#include <dirent.h>
#include <poll.h>
#include <sched.h>
#include <signal.h>
#include <sys/mman.h>
#include <sys/prctl.h>
#include <sys/resource.h>
#include <sys/stat.h>
#include <sys/sysinfo.h>
#include <sys/syscall.h>
#include <sys/time.h>
#include <sys/uio.h>
#include <sys/utsname.h>

#include <windows.h>
#include <bcrypt.h>
#include <direct.h>
#include <io.h>
#include <process.h>

#include <algorithm>
#include <atomic>
#include <cerrno>
#include <cstdarg>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

namespace {

// ── Directories as descriptors ─────────────────────────────────────────────

constexpr int kDirFdBase = 0x40000000;

struct DirHandle {
    std::string path;
    std::vector<std::pair<std::string, bool>> entries; // name, is directory
    size_t next = 0;
    bool listed = false;
};

std::mutex g_dirs_lock;
std::unordered_map<int, DirHandle> g_dirs;
int g_next_dir = kDirFdBase;

bool isDirFd(int fd) { return fd >= kDirFdBase; }

// /dev/null, /dev/zero and /dev/urandom have no Windows path, and NINE's getrandom reads urandom.
constexpr int kDevNull = 0x3FFFFF00, kDevZero = 0x3FFFFF01, kDevRandom = 0x3FFFFF02;
bool isDevFd(int fd) { return fd >= kDevNull && fd <= kDevRandom; }

int devicePath(const char* path) {
    if (!path) return -1;
    if (!std::strcmp(path, "/dev/null")) return kDevNull;
    if (!std::strcmp(path, "/dev/zero")) return kDevZero;
    if (!std::strcmp(path, "/dev/urandom") || !std::strcmp(path, "/dev/random")) return kDevRandom;
    return -1;
}

std::string joinPath(const std::string& base, const char* rest) {
    if (!rest || !*rest) return base;
    if (rest[0] == '/' || rest[0] == '\\' || (rest[0] && rest[1] == ':')) return rest;
    if (base.empty()) return rest;
    char last = base.back();
    return (last == '/' || last == '\\') ? base + rest : base + "/" + rest;
}

/** The path a dirfd + relative path names; empty when the dirfd is not valid. */
bool resolveAt(int dirfd, const char* path, std::string& out) {
    if (dirfd == AT_FDCWD || !path || path[0] == '/' || path[0] == '\\' || (path[0] && path[1] == ':')) {
        out = path ? path : "";
        return true;
    }
    std::lock_guard<std::mutex> lock(g_dirs_lock);
    auto it = g_dirs.find(dirfd);
    if (it == g_dirs.end()) { errno = EBADF; return false; }
    out = joinPath(it->second.path, path);
    return true;
}

bool isDirectoryPath(const std::string& path) {
    DWORD attributes = GetFileAttributesA(path.c_str());
    return attributes != INVALID_FILE_ATTRIBUTES && (attributes & FILE_ATTRIBUTE_DIRECTORY);
}

void listDirectory(DirHandle& dir) {
    if (dir.listed) return;
    dir.listed = true;
    WIN32_FIND_DATAA data;
    HANDLE find = FindFirstFileA(joinPath(dir.path, "*").c_str(), &data);
    if (find == INVALID_HANDLE_VALUE) return;
    do {
        dir.entries.emplace_back(data.cFileName, (data.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY) != 0);
    } while (FindNextFileA(find, &data));
    FindClose(find);
}

// ── stat ──────────────────────────────────────────────────────────────────

void fromWindowsStat(const struct _stat64& in, struct stat* out) {
    std::memset(out, 0, sizeof(*out));
    out->st_dev = static_cast<dev_t>(in.st_dev);
    out->st_ino = static_cast<ino_t>(in.st_ino);
    out->st_nlink = static_cast<nlink_t>(in.st_nlink ? in.st_nlink : 1);
    mode_t type = (in.st_mode & _S_IFDIR) ? S_IFDIR : (in.st_mode & _S_IFCHR) ? S_IFCHR : S_IFREG;
    mode_t perms = 0;
    if (in.st_mode & _S_IREAD) perms |= 0444;
    if (in.st_mode & _S_IWRITE) perms |= 0222;
    if ((in.st_mode & _S_IEXEC) || type == S_IFDIR) perms |= 0111;
    out->st_mode = type | perms;
    out->st_uid = 1000;
    out->st_gid = 1000;
    out->st_size = static_cast<off_t>(in.st_size);
    out->st_blksize = 4096;
    out->st_blocks = static_cast<blkcnt_t>((in.st_size + 511) / 512);
    out->st_atim.tv_sec = in.st_atime;
    out->st_mtim.tv_sec = in.st_mtime;
    out->st_ctim.tv_sec = in.st_ctime;
}

int statPath(const std::string& path, struct stat* out) {
    struct _stat64 st {};
    if (_stat64(path.c_str(), &st) != 0) return -1;
    fromWindowsStat(st, out);
    return 0;
}

// ── Memory ────────────────────────────────────────────────────────────────

DWORD windowsProtection(int prot) {
    bool r = prot & PROT_READ, w = prot & PROT_WRITE, x = prot & PROT_EXEC;
    if (x) return w ? PAGE_EXECUTE_READWRITE : (r ? PAGE_EXECUTE_READ : PAGE_EXECUTE);
    if (w) return PAGE_READWRITE;
    if (r) return PAGE_READONLY;
    return PAGE_NOACCESS;
}

// ── Signals ───────────────────────────────────────────────────────────────

std::atomic<void (*)(int)> g_handlers[32];
thread_local int t_pending_signal = 0;
std::once_flag g_veh_once;

extern "C" void nine_fault_trampoline() {
    int sig = t_pending_signal;
    auto handler = (sig > 0 && sig < 32) ? g_handlers[sig].load() : nullptr;
    if (handler && handler != SIG_DFL && handler != SIG_IGN) handler(sig); // Normally longjmps out.
    std::abort();
}

LONG CALLBACK onException(EXCEPTION_POINTERS* info) {
    int sig = 0;
    switch (info->ExceptionRecord->ExceptionCode) {
        case EXCEPTION_ACCESS_VIOLATION:
        case EXCEPTION_IN_PAGE_ERROR:
        case EXCEPTION_STACK_OVERFLOW: sig = SIGSEGV; break;
        case EXCEPTION_ILLEGAL_INSTRUCTION:
        case EXCEPTION_PRIV_INSTRUCTION: sig = SIGILL; break;
        case EXCEPTION_DATATYPE_MISALIGNMENT: sig = SIGBUS; break;
        default: return EXCEPTION_CONTINUE_SEARCH;
    }
    auto handler = g_handlers[sig].load();
    if (!handler || handler == SIG_DFL || handler == SIG_IGN) return EXCEPTION_CONTINUE_SEARCH;
    // Resume in an ordinary frame rather than calling the handler from inside exception dispatch:
    // the handler leaves with siglongjmp, and that must not happen from within the dispatcher.
    t_pending_signal = sig;
    CONTEXT* ctx = info->ContextRecord;
    ctx->Rsp = ((ctx->Rsp - 512) & ~static_cast<DWORD64>(15)) - 8;
    ctx->Rip = reinterpret_cast<DWORD64>(&nine_fault_trampoline);
    return EXCEPTION_CONTINUE_EXECUTION;
}

std::mutex g_prctl_lock;
char g_process_name[16] = "nine";
int g_dumpable = 1;

} // namespace

extern "C" {

// ── Files ─────────────────────────────────────────────────────────────────

int open(const char* path, int flags, ...) {
    mode_t mode = 0644;
    if (flags & O_CREAT) {
        va_list ap;
        va_start(ap, flags);
        mode = va_arg(ap, int);
        va_end(ap);
    }
    if (!path) { errno = EFAULT; return -1; }
    if (int device = devicePath(path); device >= 0) return device;
    std::string target = path;
    if ((flags & O_DIRECTORY) || isDirectoryPath(target)) {
        if (!isDirectoryPath(target)) { errno = ENOTDIR; return -1; }
        std::lock_guard<std::mutex> lock(g_dirs_lock);
        int fd = g_next_dir++;
        g_dirs[fd].path = target;
        return fd;
    }
    int windows = _O_BINARY | _O_NOINHERIT;
    switch (flags & O_ACCMODE) {
        case O_WRONLY: windows |= _O_WRONLY; break;
        case O_RDWR: windows |= _O_RDWR; break;
        default: windows |= _O_RDONLY; break;
    }
    if (flags & O_CREAT) windows |= _O_CREAT;
    if (flags & O_EXCL) windows |= _O_EXCL;
    if (flags & O_TRUNC) windows |= _O_TRUNC;
    if (flags & O_APPEND) windows |= _O_APPEND;
    int pmode = ((mode & 0200) ? _S_IWRITE : 0) | _S_IREAD;
    return _open(target.c_str(), windows, pmode);
}

int openat(int dirfd, const char* path, int flags, ...) {
    mode_t mode = 0644;
    if (flags & O_CREAT) {
        va_list ap;
        va_start(ap, flags);
        mode = va_arg(ap, int);
        va_end(ap);
    }
    std::string target;
    if (!resolveAt(dirfd, path, target)) return -1;
    return open(target.c_str(), flags, mode);
}

ssize_t read(int fd, void* buffer, size_t count) {
    if (isDevFd(fd)) {
        if (fd == kDevNull) return 0;
        if (fd == kDevZero) { std::memset(buffer, 0, count); return static_cast<ssize_t>(count); }
        return BCryptGenRandom(nullptr, static_cast<PUCHAR>(buffer), static_cast<ULONG>(count),
                               BCRYPT_USE_SYSTEM_PREFERRED_RNG) == 0 ? static_cast<ssize_t>(count) : (errno = EIO, -1);
    }
    if (isDirFd(fd)) { errno = EISDIR; return -1; }
    return _read(fd, buffer, static_cast<unsigned>(count > 0x7FFFFFFF ? 0x7FFFFFFF : count));
}

ssize_t write(int fd, const void* buffer, size_t count) {
    if (isDevFd(fd)) return static_cast<ssize_t>(count);
    if (isDirFd(fd)) { errno = EISDIR; return -1; }
    return _write(fd, buffer, static_cast<unsigned>(count > 0x7FFFFFFF ? 0x7FFFFFFF : count));
}

int close(int fd) {
    if (isDevFd(fd)) return 0;
    if (isDirFd(fd)) {
        std::lock_guard<std::mutex> lock(g_dirs_lock);
        return g_dirs.erase(fd) ? 0 : (errno = EBADF, -1);
    }
    return _close(fd);
}

off_t lseek(int fd, off_t offset, int whence) {
    if (isDirFd(fd)) {
        std::lock_guard<std::mutex> lock(g_dirs_lock);
        auto it = g_dirs.find(fd);
        if (it == g_dirs.end()) { errno = EBADF; return -1; }
        if (whence == SEEK_SET && offset == 0) it->second.next = 0;
        return 0;
    }
    return _lseeki64(fd, offset, whence);
}

ssize_t pread(int fd, void* buffer, size_t count, off_t offset) {
    off_t saved = _lseeki64(fd, 0, SEEK_CUR);
    if (saved < 0 || _lseeki64(fd, offset, SEEK_SET) < 0) return -1;
    ssize_t got = read(fd, buffer, count);
    _lseeki64(fd, saved, SEEK_SET);
    return got;
}

ssize_t pwrite(int fd, const void* buffer, size_t count, off_t offset) {
    off_t saved = _lseeki64(fd, 0, SEEK_CUR);
    if (saved < 0 || _lseeki64(fd, offset, SEEK_SET) < 0) return -1;
    ssize_t put = write(fd, buffer, count);
    _lseeki64(fd, saved, SEEK_SET);
    return put;
}

ssize_t readv(int fd, const struct iovec* vectors, int count) {
    ssize_t total = 0;
    for (int i = 0; i < count; ++i) {
        ssize_t got = read(fd, vectors[i].iov_base, vectors[i].iov_len);
        if (got < 0) return total ? total : -1;
        total += got;
        if (static_cast<size_t>(got) < vectors[i].iov_len) break;
    }
    return total;
}

ssize_t writev(int fd, const struct iovec* vectors, int count) {
    ssize_t total = 0;
    for (int i = 0; i < count; ++i) {
        ssize_t put = write(fd, vectors[i].iov_base, vectors[i].iov_len);
        if (put < 0) return total ? total : -1;
        total += put;
    }
    return total;
}

int dup(int fd) { return isDirFd(fd) ? (errno = EBADF, -1) : _dup(fd); }
int dup2(int fd, int target) { return _dup2(fd, target) == 0 ? target : -1; }
int fsync(int fd) { return isDirFd(fd) ? 0 : _commit(fd); }
int ftruncate(int fd, off_t length) { errno = _chsize_s(fd, length); return errno ? -1 : 0; }
int isatty(int fd) { return isDirFd(fd) ? 0 : _isatty(fd); }

int fcntl(int fd, int command, ...) {
    switch (command) {
        case F_DUPFD:
        case F_DUPFD_CLOEXEC: return dup(fd);
        case F_GETFD: return FD_CLOEXEC;
        case F_SETFD: return 0;
        case F_GETFL: return O_RDWR;
        case F_SETFL: return 0;
        default: errno = EINVAL; return -1;
    }
}

int access(const char* path, int mode) {
    // Windows has no execute bit to test; existence is the honest answer for X_OK.
    return _access(path, (mode & W_OK) ? 2 : 0);
}

int faccessat(int dirfd, const char* path, int mode, int flags) {
    (void)flags;
    std::string target;
    if (!resolveAt(dirfd, path, target)) return -1;
    return access(target.c_str(), mode);
}

char* getcwd(char* buffer, size_t size) { return _getcwd(buffer, static_cast<int>(size)); }
int chdir(const char* path) { return _chdir(path); }
int unlink(const char* path) { return _unlink(path); }
int rmdir(const char* path) { return _rmdir(path); }
int mkdir(const char* path, mode_t mode) { (void)mode; return _mkdir(path); }
int chmod(const char* path, mode_t mode) { return _chmod(path, (mode & 0200) ? (_S_IREAD | _S_IWRITE) : _S_IREAD); }

ssize_t readlink(const char* path, char* buffer, size_t size) {
    (void)path; (void)buffer; (void)size;
    errno = EINVAL; // Not a symlink: what Linux says for an ordinary file.
    return -1;
}

ssize_t readlinkat(int dirfd, const char* path, char* buffer, size_t size) {
    std::string target;
    if (!resolveAt(dirfd, path, target)) return -1;
    return readlink(target.c_str(), buffer, size);
}

int stat(const char* path, struct stat* out) { return path ? statPath(path, out) : (errno = EFAULT, -1); }
int lstat(const char* path, struct stat* out) { return stat(path, out); }

int fstat(int fd, struct stat* out) {
    if (isDirFd(fd)) {
        std::string path;
        {
            std::lock_guard<std::mutex> lock(g_dirs_lock);
            auto it = g_dirs.find(fd);
            if (it == g_dirs.end()) { errno = EBADF; return -1; }
            path = it->second.path;
        }
        return statPath(path, out);
    }
    struct _stat64 st {};
    if (_fstat64(fd, &st) != 0) return -1;
    fromWindowsStat(st, out);
    return 0;
}

int fstatat(int dirfd, const char* path, struct stat* out, int flags) {
    if ((flags & AT_EMPTY_PATH) && (!path || !*path)) return fstat(dirfd, out);
    std::string target;
    if (!resolveAt(dirfd, path, target)) return -1;
    return statPath(target, out);
}

// ── Directories ───────────────────────────────────────────────────────────

struct NineDir {
    std::string path;
    std::vector<std::pair<std::string, bool>> entries;
    size_t next = 0;
    struct dirent current {};
};

DIR* opendir(const char* path) {
    if (!path || !isDirectoryPath(path)) { errno = ENOENT; return nullptr; }
    auto* dir = new NineDir();
    dir->path = path;
    DirHandle handle;
    handle.path = path;
    listDirectory(handle);
    dir->entries = std::move(handle.entries);
    return dir;
}

struct dirent* readdir(DIR* dir) {
    if (!dir || dir->next >= dir->entries.size()) return nullptr;
    auto& entry = dir->entries[dir->next++];
    std::memset(&dir->current, 0, sizeof(dir->current));
    dir->current.d_ino = dir->next;
    dir->current.d_type = entry.second ? DT_DIR : DT_REG;
    strncpy(dir->current.d_name, entry.first.c_str(), sizeof(dir->current.d_name) - 1);
    return &dir->current;
}

int closedir(DIR* dir) { delete dir; return 0; }

/** linux_dirent64, packed into [buffer] from a directory descriptor. */
static long getdents64(int fd, void* buffer, size_t length) {
    std::lock_guard<std::mutex> lock(g_dirs_lock);
    auto it = g_dirs.find(fd);
    if (it == g_dirs.end()) { errno = isDirFd(fd) ? EBADF : ENOTDIR; return -1; }
    DirHandle& dir = it->second;
    listDirectory(dir);
    auto* out = static_cast<uint8_t*>(buffer);
    size_t used = 0;
    while (dir.next < dir.entries.size()) {
        const auto& entry = dir.entries[dir.next];
        size_t nameLength = entry.first.size() + 1;
        size_t record = (19 + nameLength + 7) & ~static_cast<size_t>(7);
        if (used + record > length) break;
        uint8_t* p = out + used;
        std::memset(p, 0, record);
        uint64_t ino = dir.next + 1;
        int64_t off = static_cast<int64_t>(dir.next + 1);
        uint16_t reclen = static_cast<uint16_t>(record);
        std::memcpy(p, &ino, 8);
        std::memcpy(p + 8, &off, 8);
        std::memcpy(p + 16, &reclen, 2);
        p[18] = entry.second ? DT_DIR : DT_REG;
        std::memcpy(p + 19, entry.first.c_str(), nameLength);
        used += record;
        dir.next++;
    }
    if (used == 0 && dir.next < dir.entries.size()) { errno = EINVAL; return -1; }
    return static_cast<long>(used);
}

long syscall(long number, ...) {
    va_list ap;
    va_start(ap, number);
    long result = -1;
    if (number == __NR_gettid) {
        result = static_cast<long>(GetCurrentThreadId());
    } else if (number == __NR_getdents64) {
        int fd = va_arg(ap, int);
        void* buffer = va_arg(ap, void*);
        size_t length = va_arg(ap, size_t);
        result = getdents64(fd, buffer, length);
    } else {
        errno = ENOSYS;
    }
    va_end(ap);
    return result;
}

// ── Process ───────────────────────────────────────────────────────────────

pid_t getpid(void) { return static_cast<pid_t>(_getpid()); }
pid_t getppid(void) { return 1; }
uid_t getuid(void) { return 1000; }
uid_t geteuid(void) { return 1000; }
gid_t getgid(void) { return 1000; }
gid_t getegid(void) { return 1000; }

long sysconf(int name) {
    if (name == _SC_PAGESIZE) return 4096;
    if (name == _SC_NPROCESSORS_ONLN) {
        SYSTEM_INFO info;
        GetSystemInfo(&info);
        return static_cast<long>(info.dwNumberOfProcessors);
    }
    errno = EINVAL;
    return -1;
}

int getpagesize(void) { return 4096; }
int usleep(unsigned int microseconds) { Sleep((microseconds + 999) / 1000); return 0; }
unsigned int sleep(unsigned int seconds) { Sleep(seconds * 1000); return 0; }
int sched_yield(void) { SwitchToThread(); return 0; }

int uname(struct utsname* out) {
    std::memset(out, 0, sizeof(*out));
    strcpy(out->sysname, "Linux");
    strcpy(out->nodename, "prism");
    strcpy(out->release, "6.1.0-nine");
    strcpy(out->version, "#1 SMP NINE");
    strcpy(out->machine, "x86_64");
    return 0;
}

int sysinfo(struct sysinfo* out) {
    std::memset(out, 0, sizeof(*out));
    MEMORYSTATUSEX memory {};
    memory.dwLength = sizeof(memory);
    GlobalMemoryStatusEx(&memory);
    out->uptime = static_cast<long>(GetTickCount64() / 1000);
    out->totalram = memory.ullTotalPhys;
    out->freeram = memory.ullAvailPhys;
    out->totalswap = memory.ullTotalPageFile;
    out->freeswap = memory.ullAvailPageFile;
    out->procs = 1;
    out->mem_unit = 1;
    return 0;
}

int getrlimit(int resource, struct rlimit* out) {
    out->rlim_cur = out->rlim_max = RLIM_INFINITY;
    if (resource == RLIMIT_STACK) out->rlim_cur = 8ull << 20;
    if (resource == RLIMIT_NOFILE) out->rlim_cur = out->rlim_max = 8192;
    return 0;
}

int setrlimit(int resource, const struct rlimit* in) { (void)resource; (void)in; return 0; }

int prctl(int option, ...) {
    va_list ap;
    va_start(ap, option);
    int result = 0;
    std::lock_guard<std::mutex> lock(g_prctl_lock);
    switch (option) {
        case PR_SET_NAME: {
            const char* name = va_arg(ap, const char*);
            if (name) { strncpy(g_process_name, name, 15); g_process_name[15] = 0; }
            break;
        }
        case PR_GET_NAME: {
            char* out = va_arg(ap, char*);
            if (out) std::memcpy(out, g_process_name, 16);
            break;
        }
        case PR_SET_DUMPABLE: g_dumpable = static_cast<int>(va_arg(ap, unsigned long long)); break;
        case PR_GET_DUMPABLE: result = g_dumpable; break;
        default: errno = EINVAL; result = -1;
    }
    va_end(ap);
    return result;
}

int poll(struct pollfd* fds, nfds_t count, int timeout) {
    (void)timeout;
    // Regular files and the standard streams are always ready; that is what the guests that call
    // this from stdio expect.
    for (nfds_t i = 0; i < count; ++i) fds[i].revents = fds[i].events & (POLLIN | POLLOUT);
    return static_cast<int>(count);
}

// ── Time ──────────────────────────────────────────────────────────────────

int clock_gettime(clockid_t clock, struct timespec* out) {
    if (clock == CLOCK_REALTIME) {
        FILETIME ft;
        GetSystemTimePreciseAsFileTime(&ft);
        uint64_t ticks = (static_cast<uint64_t>(ft.dwHighDateTime) << 32) | ft.dwLowDateTime;
        ticks -= 116444736000000000ull; // 1601 -> 1970, in 100 ns.
        out->tv_sec = static_cast<time_t>(ticks / 10000000ull);
        out->tv_nsec = static_cast<long>((ticks % 10000000ull) * 100);
        return 0;
    }
    static LARGE_INTEGER frequency = [] { LARGE_INTEGER f; QueryPerformanceFrequency(&f); return f; }();
    LARGE_INTEGER now;
    QueryPerformanceCounter(&now);
    out->tv_sec = static_cast<time_t>(now.QuadPart / frequency.QuadPart);
    out->tv_nsec = static_cast<long>((now.QuadPart % frequency.QuadPart) * 1000000000ll / frequency.QuadPart);
    return 0;
}

int nanosleep(const struct timespec* request, struct timespec* remaining) {
    if (remaining) { remaining->tv_sec = 0; remaining->tv_nsec = 0; }
    if (!request) { errno = EFAULT; return -1; }
    long long ms = static_cast<long long>(request->tv_sec) * 1000 + (request->tv_nsec + 999999) / 1000000;
    Sleep(ms > 0 ? static_cast<DWORD>(ms) : 0);
    return 0;
}

int gettimeofday(struct timeval* out, void* zone) {
    (void)zone;
    struct timespec ts;
    clock_gettime(CLOCK_REALTIME, &ts);
    out->tv_sec = ts.tv_sec;
    out->tv_usec = ts.tv_nsec / 1000;
    return 0;
}

// ── Memory ────────────────────────────────────────────────────────────────

void* mmap(void* address, size_t length, int prot, int flags, int fd, off_t offset) {
    if (length == 0) { errno = EINVAL; return MAP_FAILED; }
    void* placed = nullptr;
    // A hint or a fixed address is tried first; a fixed address that is already ours is committed
    // in place (Linux replaces the old mapping, which here means fresh zeroed pages).
    if (address) {
        placed = VirtualAlloc(address, length, MEM_RESERVE | MEM_COMMIT, PAGE_READWRITE);
        if (!placed && (flags & MAP_FIXED)) {
            placed = VirtualAlloc(address, length, MEM_COMMIT, PAGE_READWRITE);
            if (placed) std::memset(placed, 0, length);
        }
        if (!placed && (flags & (MAP_FIXED | MAP_FIXED_NOREPLACE))) { errno = ENOMEM; return MAP_FAILED; }
    }
    if (!placed) placed = VirtualAlloc(nullptr, length, MEM_RESERVE | MEM_COMMIT, PAGE_READWRITE);
    if (!placed) { errno = ENOMEM; return MAP_FAILED; }
    // A file mapping is a private copy of the file's bytes: Windows cannot map a CRT descriptor at
    // an arbitrary address, and a guest's loader only reads what it maps.
    if (!(flags & MAP_ANONYMOUS) && fd >= 0) {
        off_t saved = _lseeki64(fd, 0, SEEK_CUR);
        if (_lseeki64(fd, offset, SEEK_SET) >= 0) {
            size_t done = 0;
            while (done < length) {
                int got = _read(fd, static_cast<char*>(placed) + done, static_cast<unsigned>(std::min<size_t>(length - done, 1u << 30)));
                if (got <= 0) break;
                done += static_cast<size_t>(got);
            }
        }
        _lseeki64(fd, saved, SEEK_SET);
    }
    if (prot != (PROT_READ | PROT_WRITE)) mprotect(placed, length, prot);
    return placed;
}

int munmap(void* address, size_t length) {
    MEMORY_BASIC_INFORMATION info {};
    if (!VirtualQuery(address, &info, sizeof(info))) { errno = EINVAL; return -1; }
    // The whole allocation goes back; a part of one is decommitted, which is the closest Windows
    // has to unmapping a range out of the middle.
    if (info.AllocationBase == address && info.RegionSize >= length) {
        if (VirtualFree(address, 0, MEM_RELEASE)) return 0;
    }
    return VirtualFree(address, length, MEM_DECOMMIT) ? 0 : (errno = EINVAL, -1);
}

int mprotect(void* address, size_t length, int prot) {
    // READ-ONLY STAYS WRITABLE FOR NINE ITSELF. NINE marks a PE's .rdata read-only while mapping it
    // and then writes the import address table, which an MSVC-linked program keeps in .rdata -- a
    // real Windows loader lifts the protection for that; NINE does not, so the write faulted. Guest
    // code never runs from these pages (NINE interprets it), so host write access costs nothing a
    // program can observe; only "no access" is kept as asked, since that is what guard pages need.
    if (prot != PROT_NONE) prot |= PROT_WRITE;
    DWORD old;
    return VirtualProtect(address, length, windowsProtection(prot), &old) ? 0 : (errno = EACCES, -1);
}

int madvise(void* address, size_t length, int advice) {
    if (advice == MADV_DONTNEED) {
        // Linux zero-fills on the next touch; resetting the pages is the nearest equivalent.
        std::memset(address, 0, length);
    }
    return 0;
}

int msync(void* address, size_t length, int flags) { (void)flags; FlushViewOfFile(address, length); return 0; }

// ── Signals ───────────────────────────────────────────────────────────────

int sigemptyset(sigset_t* set) { if (set) *set = 0; return 0; }

int sigaction(int sig, const struct sigaction* action, struct sigaction* previous) {
    if (sig <= 0 || sig >= 32) { errno = EINVAL; return -1; }
    std::call_once(g_veh_once, [] { AddVectoredExceptionHandler(1, onException); });
    if (previous) {
        std::memset(previous, 0, sizeof(*previous));
        previous->sa_handler = g_handlers[sig].load();
    }
    if (action) g_handlers[sig].store(action->sa_handler);
    return 0;
}

// ── Randomness, for getrandom and /dev/urandom readers ──────────────────────

int nine_random_bytes(void* buffer, size_t length) {
    return BCryptGenRandom(nullptr, static_cast<PUCHAR>(buffer), static_cast<ULONG>(length),
                           BCRYPT_USE_SYSTEM_PREFERRED_RNG) == 0 ? 0 : -1;
}

} // extern "C"
