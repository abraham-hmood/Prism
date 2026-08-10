package com.prism.launcher.nora

/**
 * The bridge to Nora's native convolution kernels.
 *
 * FAILURE IS ALWAYS AN OPTION HERE, AND ALWAYS SILENT-BUT-LOGGED. A native library can be
 * missing for reasons that have nothing to do with this code: an ABI split that excluded the
 * device, a stripped APK, a build where CMake failed for the accelerator but succeeded for
 * everything else. None of those should stop Nora from working, because the Kotlin path is not
 * a degraded mode -- it is the reference implementation, and the native kernels are defined as
 * a faster way to produce its exact output. So every entry point returns a boolean saying
 * whether it ran, and every caller has a complete Kotlin implementation to fall back to.
 *
 * PARALLELISM STAYS ON THE KOTLIN SIDE. The kernels take a channel range rather than looping
 * over every channel, so [Par] slices the work exactly as it does for the Kotlin path. Spawning
 * threads inside the native layer instead would put two independent pools on the same cores,
 * which is reliably worse than either one alone.
 */
object NoraNative {

    private val loaded: Boolean = try {
        System.loadLibrary("nora_conv")
        NoraLog.info(NoraLog.Area.NATIVE, "nora_conv loaded")
        true
    } catch (t: Throwable) {
        // UnsatisfiedLinkError on a device the library was not built for is expected, not
        // exceptional. Logged at warning because it silently costs performance, and a silent
        // performance cost is the kind of thing nobody ever discovers.
        NoraLog.warn(
            NoraLog.Area.NATIVE,
            "nora_conv unavailable (${t.javaClass.simpleName}); using the Kotlin kernels"
        )
        false
    }

    /**
     * Whether the native path can be used at all.
     *
     * Distinct from [NoraPerformance.nativeConv], which is what the user asked for. This is
     * whether it is possible. The settings screen shows both, because "enabled but unavailable"
     * is a state the user is entitled to see rather than one to paper over.
     */
    fun available(): Boolean = loaded

    /** True when the switch is on AND the library is present AND the work is worth the JNI hop. */
    fun shouldUse(work: Long): Boolean =
        loaded && NoraPerformance.nativeConv && work >= NoraPerformance.nativeMinWork

    fun predict(
        weights: FloatArray, top: FloatArray, out: FloatArray,
        topC: Int, topH: Int, topW: Int,
        botC: Int, botH: Int, botW: Int,
        kernel: Int, strideY: Int, strideX: Int,
        cbBegin: Int, cbEnd: Int
    ): Boolean {
        if (!loaded) return false
        return try {
            nativePredict(
                weights, top, out,
                topC, topH, topW, botC, botH, botW,
                kernel, strideY, strideX, cbBegin, cbEnd
            )
            true
        } catch (t: Throwable) {
            NoraLog.error(NoraLog.Area.NATIVE, "nativePredict failed; falling back", t)
            false
        }
    }

    fun propagate(
        weights: FloatArray, errorBelow: FloatArray, out: FloatArray,
        topC: Int, topH: Int, topW: Int,
        botC: Int, botH: Int, botW: Int,
        kernel: Int, strideY: Int, strideX: Int,
        gain: Float,
        ctBegin: Int, ctEnd: Int
    ): Boolean {
        if (!loaded) return false
        return try {
            nativePropagate(
                weights, errorBelow, out,
                topC, topH, topW, botC, botH, botW,
                kernel, strideY, strideX, gain, ctBegin, ctEnd
            )
            true
        } catch (t: Throwable) {
            NoraLog.error(NoraLog.Area.NATIVE, "nativePropagate failed; falling back", t)
            false
        }
    }

    private external fun nativePredict(
        weights: FloatArray, top: FloatArray, out: FloatArray,
        topC: Int, topH: Int, topW: Int,
        botC: Int, botH: Int, botW: Int,
        kernel: Int, strideY: Int, strideX: Int,
        cbBegin: Int, cbEnd: Int
    )

    private external fun nativePropagate(
        weights: FloatArray, errorBelow: FloatArray, out: FloatArray,
        topC: Int, topH: Int, topW: Int,
        botC: Int, botH: Int, botW: Int,
        kernel: Int, strideY: Int, strideX: Int,
        gain: Float,
        ctBegin: Int, ctEnd: Int
    )
}
