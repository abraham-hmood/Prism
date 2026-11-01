// Force-included into every stable-diffusion.cpp translation unit.
//
// WHY A COMPAT SHIM RATHER THAN A PATCHED GGML. stable-diffusion.cpp upstream can build against
// either its own patched ggml or stock upstream ggml (SD_USE_UPSTREAM_GGML). Prism takes the
// upstream route, because the ggml already in the process belongs to llama.cpp and a second ggml
// in the same address space is a segfault waiting to happen -- see sd.cmake.
//
// The patched ggml adds one op, `ggml_prec_set_acc`, which asks for a matmul's accumulation to be
// done in fp32 regardless of the MSVC/NEON default. A handful of call sites still use it in
// upstream mode. Upstream ggml expresses the same intent through `ggml_mul_mat_set_prec`, so the
// shim maps one onto the other instead of silently dropping the hint -- dropping it changes
// numeric results on quantised weights rather than failing, which is the kind of difference that
// shows up as slightly wrong pixels and no error anywhere.

#pragma once

#include "ggml.h"

/**
 * Maps SD's generic "accumulate in fp32" hint onto upstream's matmul-only one.
 *
 * THE OP CHECK IS NOT DEFENSIVE TIDINESS, IT IS THE FIX FOR A CRASH. The patched ggml stores the
 * hint on any tensor; upstream's `ggml_mul_mat_set_prec` refuses anything whose `op` is not
 * GGML_OP_MUL_MAT, with a GGML_ASSERT -- and SD calls this on attention and convolution tensors as
 * well, so a direct forward made the process die with
 * 'GGML_ASSERT(a->op == GGML_OP_MUL_MAT) failed' the moment the first frame was sampled. Where
 * upstream has no equivalent hint, the call is skipped: those ops keep the default precision, which
 * is a small numeric difference rather than an abort.
 */
static inline void ggml_prec_set_acc(struct ggml_tensor * a, enum ggml_prec prec) {
    if (a != nullptr && a->op == GGML_OP_MUL_MAT) {
        ggml_mul_mat_set_prec(a, prec);
    }
}
