/* The two raw syscalls NINE makes directly (see syscall() in posix_win.cpp). */
#pragma once
#define __NR_gettid 186
#define __NR_getdents64 217
#define SYS_gettid __NR_gettid
