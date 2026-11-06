/* opendir/readdir on FindFirstFile. */
#pragma once
#ifdef __cplusplus
extern "C" {
#endif
#define DT_UNKNOWN 0
#define DT_DIR 4
#define DT_REG 8
struct dirent { unsigned long long d_ino; unsigned char d_type; char d_name[260]; };
typedef struct NineDir DIR;
DIR* opendir(const char* path);
struct dirent* readdir(DIR* dir);
int closedir(DIR* dir);
#ifdef __cplusplus
}
#endif
