/* A static 64-bit Linux program with no libc: two raw system calls. */
static long sys3(long n, long a, long b, long c) {
    long r;
    __asm__ volatile ("syscall" : "=a"(r) : "a"(n), "D"(a), "S"(b), "d"(c) : "rcx", "r11", "memory");
    return r;
}
void _start(void) {
    static const char text[] = "Hello from a Linux x86-64 program, run by NINE\n";
    sys3(1, 1, (long)text, sizeof(text) - 1);
    sys3(60, 5, 0, 0);
    for (;;) {}
}
