/* sigsetjmp/siglongjmp: plain setjmp/longjmp, which on Windows x64 also unwind correctly. */
#pragma once
#include_next <setjmp.h>
#ifndef sigsetjmp
typedef jmp_buf sigjmp_buf;
#define sigsetjmp(buffer, save) setjmp(buffer)
#define siglongjmp(buffer, value) longjmp(buffer, value)
#endif
