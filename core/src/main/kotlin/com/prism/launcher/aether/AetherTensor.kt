package com.prism.launcher.aether

import java.util.Random

/**
 * Aether's activation/state primitive: a dense c x h x w population snapshot, one instant in
 * time. Dense layers (most of Aether's cortices) use h = w = 1 and treat [c] as "features";
 * convolutional/deconvolutional layers use all three axes as channels x rows x cols.
 *
 * Deliberately NOT Nora's `Tensor3` (`core/.../NoraTensor.kt`): that type bakes in log-polar
 * retinotopic sampling (`sampleWrapClamp`, wrap-x/clamp-y) that has no meaning for Aether's
 * rectangular conv feature maps, and has no time axis at all. This is a fresh primitive for a
 * different architecture, not a subtype or reuse of Nora's.
 *
 * Flat backing array, channel-major then row-major -- same layout convention as NoraTensor, for
 * the same reason: predictable cache-friendly iteration and no boxing.
 */
class SpatialFrame(val c: Int, val h: Int, val w: Int) {
    val plane = h * w
    val data = FloatArray(c * plane)

    inline fun indexOf(ci: Int, y: Int, x: Int): Int = ci * plane + y * w + x

    operator fun get(ci: Int, y: Int, x: Int): Float = data[indexOf(ci, y, x)]
    operator fun set(ci: Int, y: Int, x: Int, v: Float) { data[indexOf(ci, y, x)] = v }

    /** Dense convenience: valid only when h == w == 1. */
    operator fun get(i: Int): Float = data[i]
    operator fun set(i: Int, v: Float) { data[i] = v }

    fun zero() = java.util.Arrays.fill(data, 0f)
    fun fill(v: Float) = java.util.Arrays.fill(data, v)

    fun copyFrom(other: SpatialFrame) {
        require(other.data.size == data.size) { "shape mismatch: $c/$h/$w vs ${other.c}/${other.h}/${other.w}" }
        System.arraycopy(other.data, 0, data, 0, data.size)
    }

    fun clone(): SpatialFrame = SpatialFrame(c, h, w).also { it.copyFrom(this) }

    /** this += other * scale */
    fun addScaled(other: SpatialFrame, scale: Float) {
        for (i in data.indices) data[i] += other.data[i] * scale
    }

    /** this = this * (1 - t) + other * t -- a leaky/exponential blend toward [other]. */
    fun blendToward(other: SpatialFrame, t: Float) {
        val keep = 1f - t
        for (i in data.indices) data[i] = data[i] * keep + other.data[i] * t
    }

    fun scale(f: Float) {
        for (i in data.indices) data[i] *= f
    }

    fun addNoise(sigma: Float, rng: Random) {
        if (sigma <= 0f) return
        for (i in data.indices) data[i] += (rng.nextGaussian() * sigma).toFloat()
    }

    /** Hard rectify: max(0, x). LIF membrane potentials and spike rates are never negative. */
    fun rectify() {
        for (i in data.indices) if (data[i] < 0f) data[i] = 0f
    }

    fun mean(): Float {
        if (data.isEmpty()) return 0f
        var s = 0.0
        for (v in data) s += v
        return (s / data.size).toFloat()
    }

    /** Mean of |x|, over threshold -- the "how much is actually firing" readout used throughout AetherCortex for gain control and diagnostics. */
    fun meanAbs(): Float {
        if (data.isEmpty()) return 0f
        var s = 0.0
        for (v in data) s += kotlin.math.abs(v)
        return (s / data.size).toFloat()
    }

    fun activeFraction(threshold: Float = 0.5f): Float {
        if (data.isEmpty()) return 0f
        var n = 0
        for (v in data) if (v > threshold) n++
        return n.toFloat() / data.size
    }

    fun sameShapeAs(other: SpatialFrame) = c == other.c && h == other.h && w == other.w
}

/**
 * A sequence of [SpatialFrame]s across discrete biological timesteps -- what every LIF layer's
 * `forward()` consumes and produces. AetherCortex processes prompts as ~30-timestep spike trains
 * (`SensoryTokenizer.thalamic_routing(..., time_steps = 30)`); this is the Kotlin shape of that
 * `[time_steps, ...]` tensor, one [SpatialFrame] per step rather than one flat multi-axis buffer.
 *
 * The per-frame-object layout costs some allocation efficiency against a single flat buffer with
 * manual time-axis indexing, in exchange for code that reads like the layer math it represents --
 * worth it for a first port of an architecture this size; nothing here rules out flattening later
 * once it is correct and something has actually profiled slow.
 */
class SpikeSequence(val steps: Int, val c: Int, val h: Int, val w: Int) {
    val frames: Array<SpatialFrame> = Array(steps) { SpatialFrame(c, h, w) }

    operator fun get(t: Int): SpatialFrame = frames[t]

    fun zero() { for (f in frames) f.zero() }

    /** A single frame held constant across every timestep -- how AetherCortex encodes a still image or a fixed audio vector (rate-coded DC input, not a genuine spike train). */
    companion object {
        fun constant(steps: Int, frame: SpatialFrame): SpikeSequence {
            val seq = SpikeSequence(steps, frame.c, frame.h, frame.w)
            for (t in 0 until steps) seq.frames[t].copyFrom(frame)
            return seq
        }

        /** Feature-axis concatenation per timestep -- both sequences must have the same [steps] and be dense (h=w=1). */
        fun concatFeatures(a: SpikeSequence, b: SpikeSequence): SpikeSequence {
            val out = SpikeSequence(a.steps, a.c + b.c, 1, 1)
            for (t in 0 until a.steps) {
                System.arraycopy(a[t].data, 0, out[t].data, 0, a.c)
                System.arraycopy(b[t].data, 0, out[t].data, a.c, b.c)
            }
            return out
        }

        fun add(a: SpikeSequence, b: SpikeSequence): SpikeSequence {
            val out = SpikeSequence(a.steps, a.c, a.h, a.w)
            for (t in 0 until a.steps) for (i in out[t].data.indices) out[t].data[i] = a[t].data[i] + b[t].data[i]
            return out
        }

        fun scale(a: SpikeSequence, f: Float): SpikeSequence {
            val out = SpikeSequence(a.steps, a.c, a.h, a.w)
            for (t in 0 until a.steps) for (i in out[t].data.indices) out[t].data[i] = a[t].data[i] * f
            return out
        }

        /** Broadcasts a single per-feature vector, scaled by [gain], additively across every timestep. */
        fun broadcastAdd(a: SpikeSequence, vec: FloatArray, gain: Float): SpikeSequence {
            val out = SpikeSequence(a.steps, a.c, a.h, a.w)
            for (t in 0 until a.steps) for (i in out[t].data.indices) out[t].data[i] = a[t].data[i] + vec[i] * gain
            return out
        }

        fun mean(a: SpikeSequence): Float {
            var s = 0.0
            var n = 0L
            for (t in 0 until a.steps) for (v in a[t].data) { s += v; n++ }
            return if (n == 0L) 0f else (s / n).toFloat()
        }
    }
}
