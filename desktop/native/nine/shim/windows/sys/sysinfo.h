/* sysinfo, from GlobalMemoryStatusEx and GetTickCount64. */
#pragma once
#ifdef __cplusplus
extern "C" {
#endif
struct sysinfo {
    long uptime; unsigned long loads[3]; unsigned long long totalram; unsigned long long freeram;
    unsigned long long sharedram; unsigned long long bufferram; unsigned long long totalswap;
    unsigned long long freeswap; unsigned short procs; unsigned long long totalhigh;
    unsigned long long freehigh; unsigned int mem_unit;
};
int sysinfo(struct sysinfo* out);
#ifdef __cplusplus
}
#endif
