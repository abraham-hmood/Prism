/* readv/writev. */
#pragma once
#include <sys/types.h>
#include <stddef.h>
#ifdef __cplusplus
extern "C" {
#endif
struct iovec { void* iov_base; size_t iov_len; };
ssize_t readv(int fd, const struct iovec* vectors, int count);
ssize_t writev(int fd, const struct iovec* vectors, int count);
#ifdef __cplusplus
}
#endif
