/*
 * Force-included before every NINE source file on Windows (see CMakeLists.txt).
 *
 * NINE's OS layer is written for Linux, and on Linux it hands the guest's own flag values and
 * structures straight to the host -- the host IS Linux there. The headers in this folder give it a
 * Linux-shaped API instead of MSVC's POSIX-named functions (which take Windows flag values and fill
 * Windows-shaped structs), and posix_win.cpp implements that API on Win32.
 *
 * MSVC's own POSIX names are switched off (_CRT_DECLARE_NONSTDC_NAMES=0, set in CMakeLists) so the
 * two never collide.
 */
#pragma once

#include <stdio.h>
// MSVC's <stdio.h> defines SYS_OPEN as a macro, which turns NINE's enum value of the same name into
// a syntax error.
#undef SYS_OPEN

#include <sys/types.h>
#include <time.h>

#ifdef __cplusplus
extern "C" {
#endif

#ifndef CLOCK_REALTIME
#define CLOCK_REALTIME 0
#define CLOCK_MONOTONIC 1
#define CLOCK_PROCESS_CPUTIME_ID 2
#define CLOCK_THREAD_CPUTIME_ID 3
#define CLOCK_MONOTONIC_RAW 4
#define CLOCK_BOOTTIME 7
typedef int clockid_t;
#endif

int clock_gettime(clockid_t clock, struct timespec* out);

/* The reentrant time conversions POSIX has and the CRT names differently. */
static inline struct tm* gmtime_r(const time_t* time, struct tm* out) { return gmtime_s(out, time) == 0 ? out : 0; }
static inline struct tm* localtime_r(const time_t* time, struct tm* out) { return localtime_s(out, time) == 0 ? out : 0; }
int nanosleep(const struct timespec* request, struct timespec* remaining);

#ifdef __cplusplus
}
#endif
