package com.prism.launcher.nora

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * MT / V5: spatiotemporal motion energy.
 *
 * Adelson & Bergen (1985) is the foundational result: a direction-selective unit can be built
 * from separable space-time filters by combining a quadrature pair in space with a quadrature
 * pair in time, then summing squares. The oriented-in-space-time filter that results responds
 * to motion in one direction and not the other, and the squaring makes it phase-invariant --
 * exactly the complex-cell trick, extended into time.
 *
 * Two-stage structure follows Rust, Mante, Simoncelli & Movshon (2006), who showed MT responses
 * are captured by: weighted linear pooling of V1 afferents, then a squaring nonlinearity, then
 * divisive normalization -- with COMPONENT cells having narrow pooling and PATTERN cells having
 * broad, opponent-weighted pooling. The pattern/component distinction (Movshon et al. 1985) is
 * how MT solves the aperture problem, and Nora implements both populations.
 *
 * Temporal filters are the standard biphasic pair from Adelson-Bergen, discretized over a short
 * history buffer. Only 3 frames of history are kept, which is coarse -- see NORA.md.
 */
class Mt {

    private val srcH = NoraConfig.V1_H
    private val srcW = NoraConfig.V1_W
    private val h = NoraConfig.MT_H
    private val w = NoraConfig.MT_W
    private val nDir = NoraConfig.MT_DIRECTIONS
    private val nSpeed = NoraConfig.MT_SPEEDS

    /** Ring buffer of recent V1 complex-cell energy, most recent last. */
    private val history = ArrayDeque<Tensor3>()

    /**
     * Biphasic temporal impulse responses, "fast" and "slow", sampled at MOTION_HISTORY taps.
     * The pair forms an approximate temporal quadrature, which is what makes the space-time
     * combination directional.
     */
    private val fastTemporal = FloatArray(NoraConfig.MOTION_HISTORY)
    private val slowTemporal = FloatArray(NoraConfig.MOTION_HISTORY)

    /** Last computed direction field on the MT sheet: [vx, vy] per location. */
    val flowX = FloatArray(NoraConfig.MT_H * NoraConfig.MT_W)
    val flowY = FloatArray(NoraConfig.MT_H * NoraConfig.MT_W)

    init {
        // Adelson-Bergen temporal envelopes: t^n * exp(-t/tau) with different n, which gives
        // one filter peaking earlier and going biphasic sooner than the other.
        for (i in 0 until NoraConfig.MOTION_HISTORY) {
            val t = (NoraConfig.MOTION_HISTORY - 1 - i).toFloat()
            fastTemporal[i] = temporalKernel(t, 3f, 1.2f)
            slowTemporal[i] = temporalKernel(t, 5f, 1.6f)
        }
        normalizeKernel(fastTemporal)
        normalizeKernel(slowTemporal)
    }

    private fun temporalKernel(t: Float, n: Float, tau: Float): Float {
        val a = Math.pow((t / tau).toDouble(), n.toDouble()).toFloat() * exp(-t / tau)
        val b = Math.pow((t / tau).toDouble(), (n + 2).toDouble()).toFloat() * exp(-t / tau) / 20f
        return a - b
    }

    private fun normalizeKernel(k: FloatArray) {
        var s = 0f
        for (v in k) s += v * v
        s = sqrt(s).coerceAtLeast(1e-6f)
        for (i in k.indices) k[i] /= s
    }

    fun reset() = history.clear()

    /**
     * Pushes a new V1 frame and returns the MT sheet. Until enough history has accumulated,
     * MT reports no motion -- which is correct, not a failure mode: a single static frame
     * genuinely contains no motion information.
     */
    fun forward(v1: Tensor3): Tensor3 {
        history.addLast(v1.clone())
        while (history.size > NoraConfig.MOTION_HISTORY) history.removeFirst()

        val out = Tensor3(NoraConfig.MT_CH, h, w)
        java.util.Arrays.fill(flowX, 0f)
        java.util.Arrays.fill(flowY, 0f)
        if (history.size < NoraConfig.MOTION_HISTORY) return out

        val frames = history.toList()
        val ry = srcH / h
        val rx = srcW / w

        // ── Component motion energy ─────────────────────────────────────────
        // For each direction and speed, combine the V1 orientation channel ORTHOGONAL to the
        // direction of motion (a moving edge is seen by cells tuned to its own orientation)
        // with the temporal quadrature pair.
        Par.forRange(nDir) { d ->
            val theta = 2f * PI.toFloat() * d / nDir
            // The V1 orientation that best sees motion in direction theta.
            val edgeOri = theta + PI.toFloat() / 2f
            val oriIdx = orientationIndexFor(edgeOri)

            for (s in 0 until nSpeed) {
                val ch = d * nSpeed + s
                val scaleIdx = if (s == 0) 0 else NoraConfig.V1_SCALES - 1
                val v1ch = oriIdx * NoraConfig.V1_SCALES + scaleIdx
                // Shift per frame implementing the preferred speed, along the motion direction.
                val speedPx = if (s == 0) 1.0f else 2.2f
                val sx = cos(theta) * speedPx
                val sy = sin(theta) * speedPx

                for (by in 0 until h) {
                    for (bx in 0 until w) {
                        var accFast = 0f
                        var accSlow = 0f
                        for (dy in 0 until ry) {
                            val y = by * ry + dy
                            if (y >= srcH) continue
                            for (dx in 0 until rx) {
                                val x = bx * rx + dx
                                if (x >= srcW) continue
                                for (t in frames.indices) {
                                    // Sample each frame at the position the feature would have
                                    // occupied then, if it were moving at this speed and
                                    // direction. Aligned motion sums coherently; anything else
                                    // cancels. This is the space-time oriented filter.
                                    val lag = (frames.size - 1 - t).toFloat()
                                    val v = NoraMath.bilinearWrapClamp(
                                        frames[t], v1ch, y - sy * lag, x - sx * lag
                                    )
                                    accFast += v * fastTemporal[t]
                                    accSlow += v * slowTemporal[t]
                                }
                            }
                        }
                        // Opponent energy: the squared quadrature sum.
                        out.data[ch * out.plane + by * w + bx] =
                            sqrt(accFast * accFast + accSlow * accSlow)
                    }
                }
            }
        }

        // ── Motion opponency ────────────────────────────────────────────────
        // MT cells are suppressed by motion in their null direction (Snowden et al. 1991).
        // Without this, transparent or ambiguous motion produces implausibly high responses
        // in every direction at once.
        val raw = out.data.copyOf()
        for (d in 0 until nDir) {
            val opp = (d + nDir / 2) % nDir
            for (s in 0 until nSpeed) {
                val ch = d * nSpeed + s
                val och = opp * nSpeed + s
                for (i in 0 until out.plane) {
                    out.data[ch * out.plane + i] =
                        max(0f, raw[ch * out.plane + i] - 0.6f * raw[och * out.plane + i])
                }
            }
        }

        // Pattern-cell stage: normalization across the direction pool (Rust et al. 2006).
        Normalization.acrossChannels(out)

        // ── Read out a vector flow field ────────────────────────────────────
        for (i in 0 until out.plane) {
            var vx = 0f
            var vy = 0f
            var total = 0f
            for (d in 0 until nDir) {
                val theta = 2f * PI.toFloat() * d / nDir
                for (s in 0 until nSpeed) {
                    val a = out.data[(d * nSpeed + s) * out.plane + i]
                    val speed = if (s == 0) 1.0f else 2.2f
                    vx += a * cos(theta) * speed
                    vy += a * sin(theta) * speed
                    total += a
                }
            }
            if (total > 1e-5f) {
                flowX[i] = vx / total
                flowY[i] = vy / total
            }
        }
        return out
    }

    private fun orientationIndexFor(angle: Float): Int {
        var a = angle
        val pi = PI.toFloat()
        while (a < 0) a += pi
        while (a >= pi) a -= pi
        return ((a / pi) * NoraConfig.V1_ORIENTATIONS).toInt()
            .coerceIn(0, NoraConfig.V1_ORIENTATIONS - 1)
    }

    /**
     * Warps a sheet forward along the current flow field. This is the operation that carries
     * ventral-stream content across a frame boundary during video generation, and it is the
     * concrete reason Nora's video does not flicker the way frame-wise diffusion does: the
     * next frame starts from the previous frame's content transported by measured motion,
     * rather than from fresh noise.
     */
    fun warpForward(sheet: Tensor3, scale: Float = 1f): Tensor3 {
        val out = Tensor3(sheet.c, sheet.h, sheet.w)
        val fy = sheet.h.toFloat() / h
        val fx = sheet.w.toFloat() / w
        Par.forRange(sheet.c) { ci ->
            for (y in 0 until sheet.h) {
                for (x in 0 until sheet.w) {
                    val mi = (y / fy).toInt().coerceIn(0, h - 1) * w +
                        (x / fx).toInt().coerceIn(0, w - 1)
                    // Sample backwards along the flow: where did this location's content
                    // come from? Backward warping avoids the holes that forward scatter leaves.
                    val sy = y - flowY[mi] * scale * fy
                    val sx = x - flowX[mi] * scale * fx
                    out[ci, y, x] = NoraMath.bilinearWrapClamp(sheet, ci, sy, sx)
                }
            }
        }
        return out
    }
}
