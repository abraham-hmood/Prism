/*
 * Android's logging API, for NINE built on a desktop.
 *
 * NINE calls __android_log_print for diagnostics that go to logcat on a phone. On a desktop there is
 * no logcat, so they go to stderr; the lines that matter to the user -- the guest program's own
 * output and NINE's log -- reach Prism through GuestLog regardless, exactly as on Android.
 */
#pragma once
#include <stdarg.h>
#include <stdio.h>

#ifdef __cplusplus
extern "C" {
#endif

enum {
    ANDROID_LOG_UNKNOWN = 0, ANDROID_LOG_DEFAULT, ANDROID_LOG_VERBOSE, ANDROID_LOG_DEBUG,
    ANDROID_LOG_INFO, ANDROID_LOG_WARN, ANDROID_LOG_ERROR, ANDROID_LOG_FATAL, ANDROID_LOG_SILENT,
};

static inline int __android_log_write(int prio, const char* tag, const char* text) {
    (void)prio;
    return fprintf(stderr, "%s: %s\n", tag ? tag : "", text ? text : "");
}

static inline int __android_log_vprint(int prio, const char* tag, const char* fmt, va_list ap) {
    (void)prio;
    fprintf(stderr, "%s: ", tag ? tag : "");
    int n = vfprintf(stderr, fmt, ap);
    fputc('\n', stderr);
    return n;
}

static inline int __android_log_print(int prio, const char* tag, const char* fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    int n = __android_log_vprint(prio, tag, fmt, ap);
    va_end(ap);
    return n;
}

#ifdef __cplusplus
}
#endif
