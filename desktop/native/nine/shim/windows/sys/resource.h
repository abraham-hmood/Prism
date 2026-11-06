/* getrlimit: fixed, generous answers -- Windows has no per-process rlimits. */
#pragma once
#ifdef __cplusplus
extern "C" {
#endif
typedef unsigned long long rlim_t;
#define RLIM_INFINITY (~0ULL)
#define RLIMIT_STACK 3
#define RLIMIT_NOFILE 7
#define RLIMIT_AS 9
struct rlimit { rlim_t rlim_cur; rlim_t rlim_max; };
int getrlimit(int resource, struct rlimit* out);
int setrlimit(int resource, const struct rlimit* in);
#ifdef __cplusplus
}
#endif
