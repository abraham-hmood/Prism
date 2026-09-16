package com.prism.launcher.nora

import com.prism.launcher.platform.FloatStore
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The first tests Nora's numerics have ever had.
 *
 * Not a coincidence of priorities -- it was structurally impossible until this module existed.
 * Every file in the model transitively reached `android.jar`, so exercising [HubMatrix] meant an
 * emulator or Robolectric, and neither is something anyone runs while changing a learning rule.
 * Now it is a plain JVM test task, which is the entire practical argument for the port
 * independent of whether a desktop build ever ships.
 *
 * These target claims made in comments elsewhere in this codebase. A comment asserting that
 * sparse storage is exact is a promise; a test is the only thing that keeps it one.
 */
class CoreInvariantsTest {

    // ── Geometry ────────────────────────────────────────────────────────────

    /**
     * The hierarchy's halvings must be EXACT.
     *
     * [PredictiveLink] derives its stride as `botH / topH` with integer division, so a sheet that
     * does not halve cleanly three times does not crash -- it silently misaligns every
     * deconvolution, and the model trains on predictions landing in the wrong place. This is the
     * failure mode `normalized()` exists to prevent, so it is the first thing worth pinning down.
     */
    @Test
    fun `every normalized geometry halves exactly three times`() {
        for (scale in listOf(0.05f, 0.3f, 1f, 1.7f, 2f, 3.3f, 6f)) {
            val g = NoraGeometry.scaled(scale)
            assertEquals(0, g.rings % 8, "rings ${g.rings} at scale $scale")
            assertEquals(0, g.wedges % 8, "wedges ${g.wedges} at scale $scale")
            assertEquals(g.rings / 2, g.v2H)
            assertEquals(g.rings / 4, g.v4H)
            assertEquals(g.rings / 8, g.itH)
            // The property that actually matters: no halving loses a row to truncation.
            assertEquals(g.rings, g.v2H * 2, "V2 halving inexact at scale $scale")
            assertEquals(g.rings, g.v4H * 4, "V4 quartering inexact at scale $scale")
            assertEquals(g.rings, g.itH * 8, "IT eighth inexact at scale $scale")
        }
    }

    /** Normalization must be idempotent, or repeated edits would drift the geometry. */
    @Test
    fun `normalization is idempotent`() {
        for (scale in listOf(0.2f, 0.9f, 2.4f, 5f)) {
            val once = NoraGeometry.scaled(scale)
            assertEquals(once, once.normalized(), "renormalizing changed the geometry")
        }
    }

    /** Memory estimation must be monotonic in scale — the size solver binary-searches on it. */
    @Test
    fun `memory estimate is monotonic in scale`() {
        var previous = 0L
        for (scale in listOf(0.05f, 0.5f, 1f, 2f, 3f, 4f, 6f)) {
            val bytes = NoraGeometry.scaled(scale).estimateBytes()
            assertTrue(bytes >= previous, "estimate fell from $previous to $bytes at scale $scale")
            previous = bytes
        }
    }

    // ── Sparse hub exactness ────────────────────────────────────────────────

    /**
     * THE CLAIM UNDER TEST: sparse storage is not an approximation.
     *
     * [HubMatrix] skips rows that were never written, on the argument that an untouched row is
     * exactly zero and contributes exactly zero. That reasoning is sound but it is reasoning, and
     * the settings screen tells users the option is free. This runs the same binds and the same
     * gather through both backings and requires the outputs to be equal bit for bit -- not close,
     * equal -- because anything less means the claim in the UI is wrong.
     */
    @Test
    fun `sparse hub matches dense hub exactly`() {
        val rows = 256
        val cols = 64
        val rng = Random(90210L)

        val binds = List(12) {
            // Sparse post-activity, as k-winners-take-all produces: most units silent.
            val post = FloatArray(rows) { if (rng.nextFloat() < 0.06f) rng.nextFloat() else 0f }
            val pre = FloatArray(cols) { if (rng.nextFloat() < 0.3f) rng.nextFloat() else 0f }
            post to pre
        }
        val probe = FloatArray(cols) { if (rng.nextFloat() < 0.3f) rng.nextFloat() else 0f }

        fun runWith(sparse: Boolean): FloatArray {
            val wasSparse = NoraPerformance.hubSparse
            val wasOffHeap = NoraPerformance.offHeap
            val wasSwap = NoraPerformance.swapEnabled
            try {
                NoraPerformance.hubSparse = sparse
                NoraPerformance.offHeap = false
                NoraPerformance.swapEnabled = false
                val m = HubMatrix(rows, cols)
                for ((post, pre) in binds) m.ojaUpdate(post, pre, lr = 0.05f)
                val out = FloatArray(rows)
                m.multiply(probe, out, rectify = true)
                return out
            } finally {
                NoraPerformance.hubSparse = wasSparse
                NoraPerformance.offHeap = wasOffHeap
                NoraPerformance.swapEnabled = wasSwap
            }
        }

        val dense = runWith(sparse = false)
        val sparse = runWith(sparse = true)

        assertTrue(dense.any { it != 0f }, "the test itself produced no signal to compare")
        for (i in dense.indices) {
            assertEquals(
                dense[i].toRawBits(), sparse[i].toRawBits(),
                "row $i differs: dense=${dense[i]} sparse=${sparse[i]}"
            )
        }
    }

    /** Off-heap storage must likewise change nothing about the arithmetic. */
    @Test
    fun `off-heap hub matches heap hub exactly`() {
        val rows = 128
        val cols = 32
        val rng = Random(1234L)
        val post = FloatArray(rows) { if (rng.nextFloat() < 0.1f) rng.nextFloat() else 0f }
        val pre = FloatArray(cols) { rng.nextFloat() }
        val probe = FloatArray(cols) { rng.nextFloat() }

        fun runWith(offHeap: Boolean): FloatArray {
            val wasSparse = NoraPerformance.hubSparse
            val wasOffHeap = NoraPerformance.offHeap
            val wasMin = NoraPerformance.swapMinRegionKb
            try {
                NoraPerformance.hubSparse = false
                NoraPerformance.offHeap = offHeap
                NoraPerformance.swapEnabled = false
                NoraPerformance.swapMinRegionKb = 0
                val m = HubMatrix(rows, cols)
                m.ojaUpdate(post, pre, lr = 0.05f)
                val out = FloatArray(rows)
                m.multiply(probe, out, rectify = true)
                return out
            } finally {
                NoraPerformance.hubSparse = wasSparse
                NoraPerformance.offHeap = wasOffHeap
                NoraPerformance.swapMinRegionKb = wasMin
            }
        }

        val heap = runWith(offHeap = false)
        val direct = runWith(offHeap = true)
        for (i in heap.indices) {
            assertEquals(heap[i].toRawBits(), direct[i].toRawBits(), "row $i differs")
        }
    }

    // ── Learning stability ──────────────────────────────────────────────────

    /**
     * The spectrum constraint must actually bind.
     *
     * A link whose weight RMS is allowed to grow without limit eventually destabilizes the
     * inference loop -- documented in NORA.md as the failure that took an early run out at image
     * five. This drives a link with a constant same-signed error, which is the pathological case
     * that produced it, and requires every channel to stay inside its stated ceiling.
     */
    @Test
    fun `per-channel weight growth stays bounded under same-signed error`() {
        val link = PredictiveLink("test", topC = 4, topH = 8, topW = 8, botC = 3, botH = 8, botW = 8)
        val top = Tensor3(4, 8, 8).apply { data.fill(1f) }
        val error = Tensor3(3, 8, 8).apply { data.fill(1f) }

        repeat(200) { link.learn(error, top, rate = 0.05f) }

        assertTrue(link.isHealthy(), "weights left float range")
        for (cb in 0 until 3) {
            val strength = link.channelStrength(cb)
            assertTrue(
                strength <= 2.6f,
                "channel $cb grew to ${strength}x its initialization, past the 2.5x ceiling"
            )
        }
    }

    /**
     * Every output channel must retain a usable pathway.
     *
     * The regression this guards is specific and was expensive: a shared norm budget let the
     * high-variance contrast channels crowd out the low-variance surface channels, which decayed
     * monotonically toward zero and turned generated images black. The per-channel constraint is
     * the fix, and "no channel starves" is the property that distinguishes it from what it
     * replaced.
     */
    @Test
    fun `no output channel is starved by another channel's growth`() {
        val link = PredictiveLink("starve", topC = 4, topH = 8, topW = 8, botC = 3, botH = 8, botW = 8)
        val top = Tensor3(4, 8, 8).apply { data.fill(1f) }

        // Channel 0 driven hard, channel 2 driven weakly -- the contrast/surface asymmetry.
        val error = Tensor3(3, 8, 8)
        for (i in 0 until error.plane) {
            error.data[0 * error.plane + i] = 1f
            error.data[1 * error.plane + i] = 0.5f
            error.data[2 * error.plane + i] = 0.01f
        }

        repeat(300) { link.learn(error, top, rate = 0.05f) }

        val weak = link.channelStrength(2)
        assertTrue(
            weak > 0.5f,
            "the weakly-driven channel decayed to ${weak}x initialization — the starvation " +
                "regression that produced black images has returned"
        )
    }

    // ── Storage ─────────────────────────────────────────────────────────────

    /** Heap and direct FloatStore must be interchangeable, since callers assume they are. */
    @Test
    fun `float stores agree across backings`() {
        val n = 1024
        val heap = FloatStore.heap(n)
        val direct = FloatStore.direct(n) ?: return  // no direct memory: nothing to compare
        val rng = Random(7L)
        for (i in 0 until n) {
            val v = rng.nextFloat() * 2f - 1f
            heap[i] = v
            direct[i] = v
        }
        for (i in 0 until n) heap.add(i, 0.25f)
        for (i in 0 until n) direct.add(i, 0.25f)
        for (i in 0 until n) {
            assertEquals(heap[i].toRawBits(), direct[i].toRawBits(), "index $i")
        }
    }

    /** Tuning values must clamp to their declared range rather than accept anything typed. */
    @Test
    fun `tuning parameters clamp to their declared range`() {
        for (param in NoraTuning.PARAMS) {
            val before = param.read()
            try {
                param.apply((param.max * 1000f + 1000f).toString())
                assertTrue(
                    param.read() <= param.max + 1e-4f,
                    "${param.key} accepted ${param.read()} above its max ${param.max}"
                )
                param.apply((param.min - 1000f).toString())
                assertTrue(
                    param.read() >= param.min - 1e-4f,
                    "${param.key} accepted ${param.read()} below its min ${param.min}"
                )
                assertTrue(!param.apply("not a number"), "${param.key} accepted non-numeric input")
            } finally {
                param.write(before)
            }
        }
    }

    /** Every parameter key must be unique, or persistence silently overwrites one with another. */
    @Test
    fun `parameter keys are unique across both registries`() {
        val keys = NoraTuning.PARAMS.map { it.key } +
            NoraPerformance.PARAMS.map { it.key } +
            NoraPerformance.FLAGS.map { it.key }
        val duplicates = keys.groupingBy { it }.eachCount().filterValues { it > 1 }
        assertTrue(duplicates.isEmpty(), "duplicate setting keys: ${duplicates.keys}")
    }

    // ── Numerics ────────────────────────────────────────────────────────────

    /** k-winners-take-all must select the requested fraction and silence the rest. */
    @Test
    fun `k-winners-take-all keeps the right number of units`() {
        val n = 1000
        val rng = Random(31337L)
        val v = FloatArray(n) { rng.nextFloat() }
        Normalization.kWinnersTakeAll(v, 0.06f)
        val alive = v.count { it != 0f }
        assertTrue(
            abs(alive - 60) <= 2,
            "expected about 60 of $n units active at 6% sparsity, got $alive"
        )
    }
}
