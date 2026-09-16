/*
Copyright (c) 2018-2019, tevador <tevador@gmail.com>

All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:
	* Redistributions of source code must retain the above copyright
	  notice, this list of conditions and the following disclaimer.
	* Redistributions in binary form must reproduce the above copyright
	  notice, this list of conditions and the following disclaimer in the
	  documentation and/or other materials provided with the distribution.
	* Neither the name of the copyright holder nor the
	  names of its contributors may be used to endorse or promote products
	  derived from this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
*/

#pragma once

#ifdef __cplusplus
extern "C" {
#endif

#include <stddef.h>

#define alignSize(pos, align) (((pos - 1) / align + 1) * align)

void* allocMemoryPages(size_t);
/*
 * PRISM PATCH: whether an mprotect() call has failed since the last reset. Upstream discards those
 * failures, which on Android turns a denied PROT_EXEC into a SIGSEGV instead of a clean fallback.
 */
/* PRISM PATCH: W^X-safe executable memory. See virtual_memory.c for why. */
void *randomx_prism_alloc_exec(size_t bytes);
void randomx_prism_free_exec(void *ptr, size_t bytes);
int randomx_prism_is_managed(const void *ptr);
ptrdiff_t randomx_prism_exec_offset(const void *ptr);
int randomx_prism_exec_supported(void);

int randomx_prism_protect_failed(void);
void randomx_prism_reset_protect_failed(void);

void setPagesRW(void*, size_t);
void setPagesRX(void*, size_t);
void setPagesRWX(void*, size_t);
void* allocLargePagesMemory(size_t);
void freePagedMemory(void*, size_t);

#ifdef __cplusplus
}
#endif
