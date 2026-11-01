package com.prism.launcher.messaging

/**
 * The phone's answer to [AiBackend.Reporter]: which accelerator is here, and what it can run.
 *
 * ## Why this is a thin wrapper
 *
 * [NpuSupport] already does the detecting -- vendor, NNAPI, vendor runtimes, per-format plan -- and
 * this only translates its vocabulary into the portable one, because :core cannot see Android's
 * `Build` and does not need to. Keeping the translation here also means a desktop can implement the
 * same interface with its own (empty) answer without touching either.
 *
 * ## Cached on purpose
 *
 * `detect()` lists vendor directories, which is filesystem I/O, and the settings row asks for the
 * description every time the page is rebuilt. The capability cannot change while the process is
 * alive -- it is the same silicon and the same vendor partition -- so it is computed once.
 */
object AndroidNpuReporter : AiBackend.Reporter {

    @Volatile
    private var cached: NpuSupport.Capability? = null

    /** The detected capability, computed on first ask. */
    fun capability(): NpuSupport.Capability {
        cached?.let { return it }
        val detected = runCatching { NpuSupport.detect() }.getOrElse {
            NpuSupport.Capability(
                vendor = NpuSupport.Vendor.UNKNOWN,
                displayName = "this device's neural accelerator",
                soc = "",
                nnapi = "The accelerator could not be inspected on this device.",
                runtimes = emptyList(),
                hexagonVersion = NpuSupport.HEXAGON_UNKNOWN,
                usable = false,
                reason = it.message ?: it::class.simpleName.orEmpty(),
                plans = emptyList(),
            )
        }
        cached = detected
        return detected
    }

    /** Dropped so a test (or a rebuild on the same device) can ask again. */
    fun reset() {
        cached = null
    }

    override fun npuReady(): Boolean = capability().usable

    /**
     * Why [work] is not on the NPU here.
     *
     * The plan's own reason when the format has one, and the summary line otherwise -- a user reading
     * this in a notification wants to know which part is missing, not the whole capability table.
     */
    override fun npuDetail(work: AiBackend.Work): String {
        val capability = capability()
        val plan = capability.planFor(workloadOf(work))
        if (plan.reason.isNotBlank()) return "${capability.displayName}: ${plan.reason}"
        return "${capability.displayName} cannot take ${work.label} on this device."
    }

    override fun describe(): String = capability().summary()

    /** The portable work kinds, mapped onto the formats that decide the answer. */
    private fun workloadOf(work: AiBackend.Work): NpuSupport.Workload = when (work) {
        AiBackend.Work.TEXT_GGUF -> NpuSupport.Workload.TEXT_GGUF
        AiBackend.Work.TEXT_TFLITE -> NpuSupport.Workload.TEXT_TFLITE
        AiBackend.Work.TEXT_MEDIAPIPE -> NpuSupport.Workload.TEXT_MEDIAPIPE
        AiBackend.Work.IMAGE_DIFFUSION -> NpuSupport.Workload.IMAGE_DIFFUSION
        AiBackend.Work.IMAGE_MEDIAPIPE -> NpuSupport.Workload.IMAGE_MEDIAPIPE
        AiBackend.Work.IMAGE_CLOUD -> NpuSupport.Workload.IMAGE_DIFFUSION
        AiBackend.Work.ONNX -> NpuSupport.Workload.ONNX_GRAPH
    }
}
