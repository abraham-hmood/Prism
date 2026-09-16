package com.prism.launcher.nora

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Checks the native kernels against the Kotlin ones, bit for bit.
 *
 * THIS IS THE TEST THE WHOLE NATIVE PATH RESTS ON. `nora_conv` claims to be bit-identical to the
 * Kotlin reference -- not "close", identical -- and that claim is the reason the switch can be
 * turned on without invalidating a trained connectome. A kernel that is merely *nearly* right
 * produces a model that drifts from the one the same weights produce on another machine, and
 * nothing reports it.
 *
 * The guarantee rests entirely on accumulation ORDER: the loop nest in nora_conv.cpp was written
 * so every output sums its terms in the same sequence the Kotlin loop does, and the build
 * disables fast-math because reassociation is exactly what would void it.
 *
 * SKIPS RATHER THAN FAILS WHEN THE LIBRARY IS ABSENT. Most machines working on Prism have no C++
 * toolchain, and a red test on every one of them would train people to ignore it. The skip is
 * reported so it cannot be mistaken for a pass.
 */
class NativeParityTest {

    private fun report(skipReason: String) {
        println("NativeParityTest SKIPPED: $skipReason")
    }

    @Test
    fun `native predict is bit-identical to the kotlin reference`() {
        if (!NoraNative.available()) {
            report("nora_conv is not loaded (build it with :desktop:buildNativeKernels)")
            return
        }

        // Several shapes, because the kernel has separate paths for stride and kernel size and a
        // single geometry could pass while another is wrong. Stride is DERIVED from the height
        // and width ratio inside PredictiveLink, so it is varied here by varying the shapes:
        // 24/12 gives stride 2, 8/8 gives stride 1.
        val cases = listOf(
            Geometry(topC = 8, topH = 12, topW = 12, botC = 6, botH = 24, botW = 24, kernel = 5),
            Geometry(topC = 4, topH = 8, topW = 8, botC = 4, botH = 8, botW = 8, kernel = 3),
            Geometry(topC = 16, topH = 6, topW = 6, botC = 3, botH = 12, botW = 12, kernel = 5),
        )

        for (g in cases) {
            val link = PredictiveLink(
                name = "parity",
                topC = g.topC, topH = g.topH, topW = g.topW,
                botC = g.botC, botH = g.botH, botW = g.botW,
                kernel = g.kernel,
            )

            // Deterministic input, so a failure is reproducible rather than a one-off.
            val random = Random(20260805)
            val top = Tensor3(g.topC, g.topH, g.topW)
            for (i in top.data.indices) top.data[i] = random.nextFloat() * 2f - 1f
            for (i in link.weights.indices) link.weights[i] = random.nextFloat() * 0.4f - 0.2f

            val error = link.nativeParityError(top)
            assertTrue(error >= 0f, "the native call refused for $g")
            // EXACTLY zero. Not a tolerance -- a tolerance here would silently accept the
            // reassociation the build goes out of its way to prevent.
            assertEquals(0f, error, "native/Kotlin divergence for $g")
        }
    }

    /**
     * The library either loads or it does not, and [NoraNative.available] must say which.
     *
     * Trivial-looking, but it is what stopped `info` from reporting "native kernels are
     * Android-only" for weeks after they were not.
     */
    @Test
    fun `availability is reported honestly`() {
        val available = NoraNative.available()
        // Whichever it is, asking twice must agree -- it is a val, and this pins that it stays one.
        assertEquals(available, NoraNative.available())
        if (!available) report("nora_conv is not loaded")
    }

    private data class Geometry(
        val topC: Int, val topH: Int, val topW: Int,
        val botC: Int, val botH: Int, val botW: Int,
        val kernel: Int,
    )
}
