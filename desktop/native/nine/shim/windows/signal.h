/*
 * sigaction() on Windows, for NINE's crash guard: a guest that faults must end its own run, not
 * Prism. Faults arrive as structured exceptions; posix_win.cpp turns access violations, illegal
 * instructions and misalignment into SIGSEGV/SIGILL/SIGBUS and calls the registered handler from an
 * ordinary stack frame, so the handler's siglongjmp works as it does on Linux.
 */
#pragma once
#include_next <signal.h>

#ifdef __cplusplus
extern "C" {
#endif
#ifndef SIGBUS
#define SIGBUS 10
#endif
typedef unsigned long long sigset_t;
struct sigaction {
    void (*sa_handler)(int);
    sigset_t sa_mask;
    int sa_flags;
};
int sigemptyset(sigset_t* set);
int sigaction(int signal, const struct sigaction* action, struct sigaction* previous);
#ifdef __cplusplus
}
#endif
