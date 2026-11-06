/* poll, for the standard streams and regular files NINE's guests use. */
#pragma once
#ifdef __cplusplus
extern "C" {
#endif
#define POLLIN 0x001
#define POLLPRI 0x002
#define POLLOUT 0x004
#define POLLERR 0x008
#define POLLHUP 0x010
#define POLLNVAL 0x020
struct pollfd { int fd; short events; short revents; };
typedef unsigned long nfds_t;
int poll(struct pollfd* fds, nfds_t count, int timeout);
#ifdef __cplusplus
}
#endif
