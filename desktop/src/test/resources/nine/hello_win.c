/* A 64-bit Windows program with no C runtime: kernel32 only, the part of Windows NINE provides. */
typedef void* HANDLE;
__declspec(dllimport) HANDLE __stdcall GetStdHandle(unsigned long);
__declspec(dllimport) int __stdcall WriteFile(HANDLE, const void*, unsigned long, unsigned long*, void*);
__declspec(dllimport) void __stdcall ExitProcess(unsigned int);
void mainCRTStartup(void) {
    static const char text[] = "Hello from a Windows x64 program, run by NINE\n";
    unsigned long written = 0;
    WriteFile(GetStdHandle((unsigned long)-11), text, sizeof(text) - 1, &written, 0);
    ExitProcess(7);
}
