/* uname: NINE reports x86_64 Linux to the guest regardless of the host. */
#pragma once
#ifdef __cplusplus
extern "C" {
#endif
struct utsname { char sysname[65]; char nodename[65]; char release[65]; char version[65]; char machine[65]; char domainname[65]; };
int uname(struct utsname* out);
#ifdef __cplusplus
}
#endif
