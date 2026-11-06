/* prctl: the process name and dumpable flag, remembered in process. */
#pragma once
#ifdef __cplusplus
extern "C" {
#endif
#define PR_SET_DUMPABLE 4
#define PR_SET_NAME 15
#define PR_GET_NAME 16
#define PR_GET_DUMPABLE 3
int prctl(int option, ...);
#ifdef __cplusplus
}
#endif
