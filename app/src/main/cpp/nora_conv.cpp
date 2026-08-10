// Native kernels for Nora's predictive-coding convolutions.
//
// WHAT THIS IS AND IS NOT. It is a transcription of PredictiveLink.predict and
// PredictiveLink.propagateError with the loop nest rearranged so the innermost loop walks
// contiguous memory. It is not a different algorithm, a different precision, or a different
// summation order.
//
// THE ORDERING GUARANTEE, WHICH IS THE WHOLE POINT. Floating-point addition is not associative,
// so "the same sum in a different order" is a different number. Every output element here
// accumulates its terms in exactly the sequence the Kotlin version uses -- (ky, kx, ct) for
// predict, (ky, kx, cb) for propagateError -- because only the spatial loop was hoisted inward,
// and spatial position indexes distinct outputs rather than terms of one sum. Each output is
// therefore a bit-identical reproduction of the Kotlin result, and enabling this option cannot
// change what Nora learns or generates. That is a much stronger property than "close enough",
// and it is what makes the switch safe to flip mid-training.
//
// It also means no -ffast-math. Reassociation is exactly the optimization that would break the
// guarantee, so the build must not be allowed to perform it. Vectorization is still legal and
// still happens: accumulating a scalar-times-vector into a run of distinct outputs needs no
// reassociation at all, because the lanes are independent outputs rather than partial sums.
//
// ONE DOCUMENTED EXCEPTION. Kernels skip a weight that is exactly zero, which saves an entire
// row traversal and matters a great deal after a few sleep phases have pruned. Under IEEE that
// is not quite a no-op in two cases: adding +0.0 to an accumulator holding -0.0 changes its
// sign bit, and 0.0 * inf is NaN where skipping leaves the accumulator finite. Neither can
// arise here -- NoraHealth rejects non-finite activity before it reaches a link, and the sign
// of zero has no consumer downstream -- but the exception is stated rather than glossed,
// because "bit-identical except where it isn't" is only useful if the exception is named.
//
// WHERE THE WIN ACTUALLY COMES FROM. Three things, in order of size: contiguous access in the
// innermost loop instead of a stride of a whole channel plane; no per-element bounds check; and
// NEON on the stride-1 links, where consecutive outputs read consecutive inputs. Links with a
// stride above 1 gain from the first two but vectorize poorly, because their source and
// destination cannot both be contiguous. Expect a large gain on the retina<->V1 link and a
// modest one higher up the hierarchy.

#include <jni.h>
#include <cstring>
#include <cstdint>

namespace {

inline int floorMod(int v, int m) {
    int r = v % m;
    return r < 0 ? r + m : r;
}

// out[cb][yb][xb] = sum over ky, kx, ct of  W[ct][cb][ky][kx] * top[ct][yt][xt]
//
// with yt = (yb + pad - ky) / strideY, defined only when that is non-negative, divisible by
// strideY and inside topH; and xt = ((xb + pad - kx) / strideX) mod topW, defined only when
// (xb + pad - kx) is divisible by strideX.
void predictKernel(const float* __restrict w,
                   const float* __restrict top,
                   float* __restrict out,
                   int topC, int topH, int topW,
                   int botC, int botH, int botW,
                   int kernel, int strideY, int strideX,
                   int cbBegin, int cbEnd) {
    const int kk = kernel * kernel;
    const int perTop = botC * kk;
    const int pad = kernel / 2;
    const int topPlane = topH * topW;
    const int botPlane = botH * botW;

    for (int cb = cbBegin; cb < cbEnd; ++cb) {
        float* outC = out + (int64_t) cb * botPlane;
        std::memset(outC, 0, sizeof(float) * (size_t) botPlane);

        for (int yb = 0; yb < botH; ++yb) {
            float* outRow = outC + (int64_t) yb * botW;

            for (int ky = 0; ky < kernel; ++ky) {
                const int num = yb + pad - ky;
                if (num < 0 || num % strideY != 0) continue;
                const int yt = num / strideY;
                if (yt >= topH) continue;

                for (int kx = 0; kx < kernel; ++kx) {
                    const int off = pad - kx;

                    for (int ct = 0; ct < topC; ++ct) {
                        const float wv = w[(int64_t) ct * perTop + (int64_t) cb * kk +
                                           ky * kernel + kx];
                        // Pruned synapses are common after a few sleep phases, and skipping
                        // them skips an entire row traversal rather than one multiply.
                        if (wv == 0.0f) continue;
                        const float* topRow = top + (int64_t) ct * topPlane +
                                              (int64_t) yt * topW;

                        if (strideX == 1) {
                            // botW == topW here, so xt = (xb + off) mod topW: two contiguous
                            // runs split at the wrap point. This is the vectorizable case --
                            // consecutive outputs read consecutive inputs.
                            const int start = floorMod(off, topW);
                            const int firstRun = topW - start;
                            const int n0 = firstRun < botW ? firstRun : botW;
                            // The unwrapped run: contiguous in both arrays, and the only part
                            // that needs to vectorize.
                            for (int i = 0; i < n0; ++i) {
                                outRow[i] += wv * topRow[start + i];
                            }
                            // The tail after the wrap. Kept modular rather than assuming a
                            // single wrap, so an unexpected sheet ratio degrades to slow
                            // rather than to reading out of bounds.
                            for (int i = n0; i < botW; ++i) {
                                outRow[i] += wv * topRow[floorMod(start + i, topW)];
                            }
                        } else {
                            // Only every strideX-th output has a source at all. Source is
                            // contiguous, destination is strided; correct, but the scatter
                            // limits what the vectorizer can do.
                            int xb = floorMod(-off, strideX);
                            for (; xb < botW; xb += strideX) {
                                const int xt = floorMod((xb + off) / strideX, topW);
                                outRow[xb] += wv * topRow[xt];
                            }
                        }
                    }
                }
            }
        }
    }
}

// out[ct][yt][xt] = gain * sum over ky, kx, cb of  W[ct][cb][ky][kx] * err[cb][yb][xb]
//
// with yb = yt * strideY + ky - pad (skipped when outside the sheet) and
// xb = (xt * strideX + kx - pad) mod botW.
void propagateKernel(const float* __restrict w,
                     const float* __restrict err,
                     float* __restrict out,
                     int topC, int topH, int topW,
                     int botC, int botH, int botW,
                     int kernel, int strideY, int strideX,
                     float gain,
                     int ctBegin, int ctEnd) {
    const int kk = kernel * kernel;
    const int perTop = botC * kk;
    const int pad = kernel / 2;
    const int topPlane = topH * topW;
    const int botPlane = botH * botW;

    for (int ct = ctBegin; ct < ctEnd; ++ct) {
        float* outC = out + (int64_t) ct * topPlane;
        std::memset(outC, 0, sizeof(float) * (size_t) topPlane);

        for (int yt = 0; yt < topH; ++yt) {
            float* outRow = outC + (int64_t) yt * topW;

            for (int ky = 0; ky < kernel; ++ky) {
                const int yb = yt * strideY + ky - pad;
                if (yb < 0 || yb >= botH) continue;

                for (int kx = 0; kx < kernel; ++kx) {
                    for (int cb = 0; cb < botC; ++cb) {
                        const float wv = w[(int64_t) ct * perTop + (int64_t) cb * kk +
                                           ky * kernel + kx];
                        if (wv == 0.0f) continue;
                        const float* errRow = err + (int64_t) cb * botPlane +
                                              (int64_t) yb * botW;

                        if (strideX == 1) {
                            const int start = floorMod(kx - pad, botW);
                            const int firstRun = botW - start;
                            const int n0 = firstRun < topW ? firstRun : topW;
                            for (int i = 0; i < n0; ++i) {
                                outRow[i] += wv * errRow[start + i];
                            }
                            for (int i = n0; i < topW; ++i) {
                                outRow[i] += wv * errRow[floorMod(start + i, botW)];
                            }
                        } else {
                            for (int xt = 0; xt < topW; ++xt) {
                                const int xb = floorMod(xt * strideX + kx - pad, botW);
                                outRow[xt] += wv * errRow[xb];
                            }
                        }
                    }
                }
            }
        }
    }
}

} // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_prism_launcher_nora_NoraNative_nativeAvailable(JNIEnv*, jobject) {
    return JNI_TRUE;
}

// The channel range is passed in rather than looped over in full, so the Kotlin side can keep
// using its own worker pool for parallelism. Spawning threads here would mean two independent
// thread pools competing for the same cores, which is worse than either alone.
JNIEXPORT void JNICALL
Java_com_prism_launcher_nora_NoraNative_nativePredict(
        JNIEnv* env, jobject,
        jfloatArray jw, jfloatArray jtop, jfloatArray jout,
        jint topC, jint topH, jint topW,
        jint botC, jint botH, jint botW,
        jint kernel, jint strideY, jint strideX,
        jint cbBegin, jint cbEnd) {
    // Critical access: no copy, no GC movement for the duration. The regions below run for
    // milliseconds at most, which is what makes holding the collector off acceptable.
    void* w = env->GetPrimitiveArrayCritical(jw, nullptr);
    if (w == nullptr) return;
    void* top = env->GetPrimitiveArrayCritical(jtop, nullptr);
    if (top == nullptr) {
        env->ReleasePrimitiveArrayCritical(jw, w, JNI_ABORT);
        return;
    }
    void* out = env->GetPrimitiveArrayCritical(jout, nullptr);
    if (out == nullptr) {
        env->ReleasePrimitiveArrayCritical(jtop, top, JNI_ABORT);
        env->ReleasePrimitiveArrayCritical(jw, w, JNI_ABORT);
        return;
    }

    predictKernel((const float*) w, (const float*) top, (float*) out,
                  topC, topH, topW, botC, botH, botW,
                  kernel, strideY, strideX, cbBegin, cbEnd);

    env->ReleasePrimitiveArrayCritical(jout, out, 0);
    env->ReleasePrimitiveArrayCritical(jtop, top, JNI_ABORT);
    env->ReleasePrimitiveArrayCritical(jw, w, JNI_ABORT);
}

JNIEXPORT void JNICALL
Java_com_prism_launcher_nora_NoraNative_nativePropagate(
        JNIEnv* env, jobject,
        jfloatArray jw, jfloatArray jerr, jfloatArray jout,
        jint topC, jint topH, jint topW,
        jint botC, jint botH, jint botW,
        jint kernel, jint strideY, jint strideX,
        jfloat gain,
        jint ctBegin, jint ctEnd) {
    void* w = env->GetPrimitiveArrayCritical(jw, nullptr);
    if (w == nullptr) return;
    void* err = env->GetPrimitiveArrayCritical(jerr, nullptr);
    if (err == nullptr) {
        env->ReleasePrimitiveArrayCritical(jw, w, JNI_ABORT);
        return;
    }
    void* out = env->GetPrimitiveArrayCritical(jout, nullptr);
    if (out == nullptr) {
        env->ReleasePrimitiveArrayCritical(jerr, err, JNI_ABORT);
        env->ReleasePrimitiveArrayCritical(jw, w, JNI_ABORT);
        return;
    }

    propagateKernel((const float*) w, (const float*) err, (float*) out,
                    topC, topH, topW, botC, botH, botW,
                    kernel, strideY, strideX, gain, ctBegin, ctEnd);

    // The gain is applied as a separate pass rather than inside the accumulation, so that the
    // sum itself is identical to Kotlin's and only the final scale differs -- which is one
    // rounding step on one value, not a different summation.
    if (gain != 1.0f) {
        float* o = (float*) out;
        const int64_t base = (int64_t) ctBegin * topH * topW;
        const int64_t end = (int64_t) ctEnd * topH * topW;
        for (int64_t i = base; i < end; ++i) o[i] *= gain;
    }

    env->ReleasePrimitiveArrayCritical(jout, out, 0);
    env->ReleasePrimitiveArrayCritical(jerr, err, JNI_ABORT);
    env->ReleasePrimitiveArrayCritical(jw, w, JNI_ABORT);
}

} // extern "C"
