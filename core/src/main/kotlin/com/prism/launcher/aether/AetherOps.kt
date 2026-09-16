package com.prism.launcher.aether

import java.util.Random
import kotlin.math.abs
import kotlin.math.sign

/**
 * Surrogate-gradient spike nonlinearity -- ported from `core/functions.py::surrogate_spike`.
 *
 * Forward: Heaviside step (spike iff v_mem >= threshold). Backward: fast-sigmoid derivative,
 * `1 / (1 + gamma*|v_mem - threshold|)^2` with gamma = 2.0 (the source's own comment: "softened
 * for smoother homeostatic damping"). No gradient reaches the threshold argument -- the original
 * TF `@tf.custom_gradient` explicitly returns `None` for it, which is why every "dynamic
 * threshold" (`t_state`) term in this port is treated as a detached constant during backward:
 * that's not a simplification, it's what the source's own autodiff actually computes.
 */
object SurrogateSpike {
    const val GAMMA = 2.0f

    fun forward(vMem: Float, threshold: Float): Float = if (vMem >= threshold) 1f else 0f

    fun grad(vMem: Float, threshold: Float): Float {
        val x = abs(vMem - threshold)
        val d = 1f + GAMMA * x
        return 1f / (d * d)
    }
}

/** Dense weight matrix: flat, row-major [rows][cols]. Rows = inputs, cols = neurons, matching TF's `(input_size, num_neurons)` layout. */
class Matrix(val rows: Int, val cols: Int) {
    val data = FloatArray(rows * cols)
    inline fun idx(r: Int, c: Int) = r * cols + c
    operator fun get(r: Int, c: Int): Float = data[idx(r, c)]
    operator fun set(r: Int, c: Int, v: Float) { data[idx(r, c)] = v }
    fun fill(v: Float) = java.util.Arrays.fill(data, v)
    fun zero() = fill(0f)

    companion object {
        fun randomNormal(rows: Int, cols: Int, stddev: Float, rng: Random): Matrix {
            val m = Matrix(rows, cols)
            for (i in m.data.indices) m.data[i] = (rng.nextGaussian() * stddev).toFloat()
            return m
        }
    }
}

/**
 * Adam optimizer over a flat parameter buffer, applied in place. One instance's `m`/`v` moment
 * buffers are sized to match the parameter array they're constructed against and are meant to be
 * reused call after call (not reallocated), mirroring `tf.optimizers.Adam`'s per-variable slots.
 */
class AdamState(size: Int) {
    val m = FloatArray(size)
    val v = FloatArray(size)
    var step = 0

    fun apply(params: FloatArray, grad: FloatArray, lr: Float, beta1: Float = 0.9f, beta2: Float = 0.999f, eps: Float = 1e-7f) {
        step++
        val bc1 = 1f - Math.pow(beta1.toDouble(), step.toDouble()).toFloat()
        val bc2 = 1f - Math.pow(beta2.toDouble(), step.toDouble()).toFloat()
        for (i in params.indices) {
            val g = grad[i]
            m[i] = beta1 * m[i] + (1f - beta1) * g
            v[i] = beta2 * v[i] + (1f - beta2) * g * g
            val mHat = m[i] / bc1
            val vHat = v[i] / bc2
            params[i] -= lr * mHat / (kotlin.math.sqrt(vHat) + eps)
        }
    }
}

/**
 * Convolution primitives -- SAME-padded conv2d, its two backward passes (w.r.t. input and
 * weights), transposed conv2d (deconv), and the 3x3/stride-1 average pool used for spatial
 * lateral inhibition. Plain nested loops, not vectorized: correctness first for a first port of
 * an architecture this size; nothing here rules out a faster implementation once this one is
 * known to produce the right answer.
 */
object AetherConv {

    private fun samePad(outSize: Int, inSize: Int, stride: Int, kernel: Int): Int {
        val total = maxOf((outSize - 1) * stride + kernel - inSize, 0)
        return total / 2
    }

    /** weights layout: [ky][kx][inC][outC], matching TF conv2d's kernel shape (kernel,kernel,inC,filters). */
    fun conv2dSame(input: SpatialFrame, weights: FloatArray, biases: FloatArray, kernel: Int, outC: Int, stride: Int, outH: Int, outW: Int): SpatialFrame {
        val inC = input.c
        val out = SpatialFrame(outC, outH, outW)
        val padTop = samePad(outH, input.h, stride, kernel)
        val padLeft = samePad(outW, input.w, stride, kernel)
        for (oy in 0 until outH) {
            val iy0 = oy * stride - padTop
            for (ox in 0 until outW) {
                val ix0 = ox * stride - padLeft
                for (oc in 0 until outC) {
                    var sum = biases[oc]
                    for (ky in 0 until kernel) {
                        val iy = iy0 + ky
                        if (iy < 0 || iy >= input.h) continue
                        for (kx in 0 until kernel) {
                            val ix = ix0 + kx
                            if (ix < 0 || ix >= input.w) continue
                            val wBase = ((ky * kernel + kx) * inC) * outC + oc
                            for (ic in 0 until inC) {
                                sum += input[ic, iy, ix] * weights[wBase + ic * outC]
                            }
                        }
                    }
                    out[oc, oy, ox] = sum
                }
            }
        }
        return out
    }

    /** dL/dInput for [conv2dSame], given dL/dOutput. */
    fun conv2dSameInputGrad(dOut: SpatialFrame, weights: FloatArray, kernel: Int, inC: Int, stride: Int, inH: Int, inW: Int): SpatialFrame {
        val outC = dOut.c
        val dIn = SpatialFrame(inC, inH, inW)
        val padTop = samePad(dOut.h, inH, stride, kernel)
        val padLeft = samePad(dOut.w, inW, stride, kernel)
        for (oy in 0 until dOut.h) {
            val iy0 = oy * stride - padTop
            for (ox in 0 until dOut.w) {
                val ix0 = ox * stride - padLeft
                for (ky in 0 until kernel) {
                    val iy = iy0 + ky
                    if (iy < 0 || iy >= inH) continue
                    for (kx in 0 until kernel) {
                        val ix = ix0 + kx
                        if (ix < 0 || ix >= inW) continue
                        val wBase = (ky * kernel + kx) * inC * outC
                        for (ic in 0 until inC) {
                            var sum = 0f
                            val wOff = wBase + ic * outC
                            for (oc in 0 until outC) sum += dOut[oc, oy, ox] * weights[wOff + oc]
                            dIn[ic, iy, ix] = dIn[ic, iy, ix] + sum
                        }
                    }
                }
            }
        }
        return dIn
    }

    /** dL/dWeights for [conv2dSame], given dL/dOutput and the forward input, accumulated into [dWeights] (same flat layout as the forward weights). */
    fun conv2dSameWeightGrad(input: SpatialFrame, dOut: SpatialFrame, dWeights: FloatArray, dBiases: FloatArray, kernel: Int, stride: Int) {
        val inC = input.c
        val outC = dOut.c
        val padTop = samePad(dOut.h, input.h, stride, kernel)
        val padLeft = samePad(dOut.w, input.w, stride, kernel)
        for (oy in 0 until dOut.h) {
            val iy0 = oy * stride - padTop
            for (ox in 0 until dOut.w) {
                val ix0 = ox * stride - padLeft
                for (oc in 0 until outC) {
                    val g = dOut[oc, oy, ox]
                    if (g == 0f) continue
                    dBiases[oc] += g
                    for (ky in 0 until kernel) {
                        val iy = iy0 + ky
                        if (iy < 0 || iy >= input.h) continue
                        for (kx in 0 until kernel) {
                            val ix = ix0 + kx
                            if (ix < 0 || ix >= input.w) continue
                            val wBase = ((ky * kernel + kx) * inC) * outC + oc
                            for (ic in 0 until inC) {
                                dWeights[wBase + ic * outC] += input[ic, iy, ix] * g
                            }
                        }
                    }
                }
            }
        }
    }

    /** weights layout: [ky][kx][outC][inC], matching TF conv2d_transpose's kernel shape (kernel,kernel,filters,inC). */
    fun conv2dTransposeSame(input: SpatialFrame, weights: FloatArray, biases: FloatArray, kernel: Int, outC: Int, stride: Int, outH: Int, outW: Int): SpatialFrame {
        val inC = input.c
        val out = SpatialFrame(outC, outH, outW)
        for (oc in 0 until outC) for (y in 0 until outH) for (x in 0 until outW) out[oc, y, x] = biases[oc]
        val padTop = samePad(outH, input.h, stride, kernel).let { maxOf(kernel - stride, 0) / 2 }
        val padLeft = samePad(outW, input.w, stride, kernel).let { maxOf(kernel - stride, 0) / 2 }
        for (iy in 0 until input.h) {
            for (ix in 0 until input.w) {
                for (ky in 0 until kernel) {
                    val oy = iy * stride + ky - padTop
                    if (oy < 0 || oy >= outH) continue
                    for (kx in 0 until kernel) {
                        val ox = ix * stride + kx - padLeft
                        if (ox < 0 || ox >= outW) continue
                        val wBase = (ky * kernel + kx) * outC * inC
                        for (ic in 0 until inC) {
                            val v = input[ic, iy, ix]
                            if (v == 0f) continue
                            val wOff = wBase + ic
                            for (oc in 0 until outC) {
                                out[oc, oy, ox] = out[oc, oy, ox] + v * weights[wOff + oc * inC]
                            }
                        }
                    }
                }
            }
        }
        return out
    }

    /** dL/dInput for [conv2dTransposeSame] -- the transpose of a transpose is the forward conv, so this reuses conv2dSame's gather form with roles of in/out channels swapped. */
    fun conv2dTransposeInputGrad(dOut: SpatialFrame, weights: FloatArray, kernel: Int, inC: Int, stride: Int, inH: Int, inW: Int): SpatialFrame {
        val outC = dOut.c
        val dIn = SpatialFrame(inC, inH, inW)
        val padTop = maxOf(kernel - stride, 0) / 2
        val padLeft = maxOf(kernel - stride, 0) / 2
        for (iy in 0 until inH) {
            for (ix in 0 until inW) {
                for (ic in 0 until inC) {
                    var sum = 0f
                    for (ky in 0 until kernel) {
                        val oy = iy * stride + ky - padTop
                        if (oy < 0 || oy >= dOut.h) continue
                        for (kx in 0 until kernel) {
                            val ox = ix * stride + kx - padLeft
                            if (ox < 0 || ox >= dOut.w) continue
                            val wBase = (ky * kernel + kx) * outC * inC + ic
                            for (oc in 0 until outC) sum += dOut[oc, oy, ox] * weights[wBase + oc * inC]
                        }
                    }
                    dIn[ic, iy, ix] = sum
                }
            }
        }
        return dIn
    }

    fun conv2dTransposeWeightGrad(input: SpatialFrame, dOut: SpatialFrame, dWeights: FloatArray, dBiases: FloatArray, kernel: Int, stride: Int) {
        val inC = input.c
        val outC = dOut.c
        val padTop = maxOf(kernel - stride, 0) / 2
        val padLeft = maxOf(kernel - stride, 0) / 2
        for (oc in 0 until outC) {
            var b = 0f
            for (y in 0 until dOut.h) for (x in 0 until dOut.w) b += dOut[oc, y, x]
            dBiases[oc] += b
        }
        for (iy in 0 until input.h) {
            for (ix in 0 until input.w) {
                for (ky in 0 until kernel) {
                    val oy = iy * stride + ky - padTop
                    if (oy < 0 || oy >= dOut.h) continue
                    for (kx in 0 until kernel) {
                        val ox = ix * stride + kx - padLeft
                        if (ox < 0 || ox >= dOut.w) continue
                        val wBase = (ky * kernel + kx) * outC * inC
                        for (ic in 0 until inC) {
                            val v = input[ic, iy, ix]
                            if (v == 0f) continue
                            for (oc in 0 until outC) dWeights[wBase + oc * inC + ic] += v * dOut[oc, oy, ox]
                        }
                    }
                }
            }
        }
    }

    fun avgPool3x3Same(input: SpatialFrame): SpatialFrame {
        val out = SpatialFrame(input.c, input.h, input.w)
        for (c in 0 until input.c) {
            for (y in 0 until input.h) {
                for (x in 0 until input.w) {
                    var sum = 0f; var n = 0
                    for (dy in -1..1) {
                        val yy = y + dy
                        if (yy < 0 || yy >= input.h) continue
                        for (dx in -1..1) {
                            val xx = x + dx
                            if (xx < 0 || xx >= input.w) continue
                            sum += input[c, yy, xx]; n++
                        }
                    }
                    out[c, y, x] = if (n > 0) sum / n else 0f
                }
            }
        }
        return out
    }
}

/** `tf.sign` semantics: -1, 0, or 1 -- Kotlin's `sign()` on Float agrees, kept here just to name the STDP usage. */
internal fun signf(x: Float): Float = sign(x)
