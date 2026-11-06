/* gettimeofday; struct timeval as Linux has it. */
#pragma once
#include <sys/types.h>
#include <time.h>
#ifdef __cplusplus
extern "C" {
#endif
#ifndef NINE_TIMEVAL
#define NINE_TIMEVAL
struct timeval { long long tv_sec; long long tv_usec; };
struct timezone { int tz_minuteswest; int tz_dsttime; };
#endif
int gettimeofday(struct timeval* out, void* zone);
#ifdef __cplusplus
}
#endif
