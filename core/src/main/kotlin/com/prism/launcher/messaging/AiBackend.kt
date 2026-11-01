package com.prism.launcher.messaging

import com.prism.launcher.PrismSettings

/**
 * What the user's chosen AI backend means for a specific piece of work.
 *
 * ## Why this is a question and not a switch
 *
 * `PrismSettings.getAiBackend()` is one of three values and it is honest about all of them: the
 * user picked the processor they want their models to run on. What it cannot say is whether the
 * work in front of the engine CAN run there -- a diffusion checkpoint has no NPU path on any
 * silicon, an ONNX graph has one on all five phone vendors, a GGUF has one on Qualcomm and only
 * when Prism was built against Qualcomm's SDK. Answering "yes" regardless would mean an option
 * that quietly ran somewhere else, which is the failure this exists to prevent.
 *
 * ## The rule it encodes
 *
 * NPU IS USED ONLY WHEN NPU IS WHAT THE USER CHOSE. Nothing here opts into an accelerator the user
 * did not select -- [PrismSettings.getAiBackend] is read, never assumed -- and when the chosen
 * backend cannot run the work, the engine says so in a sentence rather than silently substituting.
 * "Silently" is the operative word: falling back is right, doing it without telling anyone is not.
 */
object AiBackend {

    /** The kinds of work a backend choice can apply to. */
    enum class Work(val label: String) {
        /** llama.cpp: GGUF text, the main local model. */
        TEXT_GGUF("a GGUF text model"),

        /** A TFLite graph, e.g. CakeChat's encoder/decoder pair. */
        TEXT_TFLITE("a TFLite text model"),

        /** MediaPipe's LLM bundle: `.task` files. */
        TEXT_MEDIAPIPE("a MediaPipe text bundle"),

        /** stable-diffusion.cpp: GGUF or safetensors checkpoints. */
        IMAGE_DIFFUSION("a diffusion checkpoint"),

        /** MediaPipe's image generator: `.task` bundles again. */
        IMAGE_MEDIAPIPE("a MediaPipe image bundle"),

        /** A cloud endpoint. The accelerator question does not reach it at all. */
        IMAGE_CLOUD("a cloud image endpoint"),

        /** ONNX Runtime (voice cloning and the other graph work). */
        ONNX("an ONNX graph"),
    }

    /**
     * The platform's knowledge of its own neural accelerator.
     *
     * Android installs the real implementation at startup, because only it can see the SoC, the
     * NNAPI driver and the vendor runtimes. Desktop leaves it null, where the honest answer is
     * "this build has no NPU path" rather than a fabricated one.
     */
    interface Reporter {

        /** Whether a genuine NPU execution path exists on this device and build. */
        fun npuReady(): Boolean

        /**
         * One sentence about what NPU would do for [work] here.
         *
         * Called only when the user chose NPU and the work cannot use it, so this is the explanation
         * shown instead of a silent fallback: what the accelerator is, why it cannot take this work,
         * and what it would take to change that.
         */
        fun npuDetail(work: Work): String

        /**
         * One line naming this device's accelerator and what NPU can run on it.
         *
         * Shown as the subtitle of the AI backend row, so the choice is made with the device's real
         * capability in front of the user rather than as an unexplained third option.
         */
        fun describe(): String
    }

    @Volatile
    var reporter: Reporter? = null

    /** The chosen backend, as the label the settings screen uses. */
    fun chosenLabel(): String = when (PrismSettings.getAiBackend()) {
        PrismSettings.AI_BACKEND_CPU -> "CPU"
        PrismSettings.AI_BACKEND_NPU -> "NPU"
        else -> "GPU"
    }

    /** Whether NPU is the user's selection. The one gate every engine checks before considering it. */
    fun npuChosen(): Boolean = PrismSettings.getAiBackend() == PrismSettings.AI_BACKEND_NPU

    /**
     * Whether an NPU can actually be used for anything on this device.
     *
     * False without a reporter: an unknown platform has no accelerator path Prism can name, and
     * claiming otherwise would put an option in the picker that does nothing.
     */
    fun npuReady(): Boolean = reporter?.npuReady() == true

    /**
     * What this device's accelerator is, for the picker's subtitle.
     *
     * The desktop has no reporter because it genuinely has no NPU path Prism can name, and saying so
     * is better than leaving NPU as an unexplained option that quietly does nothing.
     */
    fun describe(): String = reporter?.describe()
        ?: "This platform has no neural accelerator Prism can address, so a model can only run on " +
            "the CPU or GPU here."

    /**
     * What to say about the accelerator for [work], or empty when there is nothing to say.
     *
     * Empty in the two ordinary cases: the user did not choose NPU (so no substitution is happening
     * and no excuse is owed), or the work runs on the NPU as asked. A sentence otherwise, because
     * a fallback the user cannot see is indistinguishable from the feature not working.
     */
    fun noteFor(work: Work): String {
        if (!npuChosen()) return ""
        if (npuRuns(work)) return ""
        val detail = reporter?.npuDetail(work)
            ?: "This build has no NPU path for ${work.label}, so it ran on the accelerator the " +
                "engine chose for itself."
        return detail
    }

    /** Whether [work] has a real NPU execution path here. */
    fun npuRuns(work: Work): Boolean = when (work) {
        // The two platform NPU routes: NNAPI takes TFLite and ONNX graphs on every phone vendor,
        // and Qualcomm's Hexagon backend takes GGUF. Each is asked about separately, because a
        // Snapdragon can run a GGUF on its DSP and a rebuild without the Hexagon SDK cannot -- and
        // that difference has to show up in the note rather than being averaged away.
        Work.TEXT_TFLITE, Work.ONNX -> npuReady()
        Work.TEXT_GGUF -> ggufNpu()
        // No ggml or MediaPipe path reaches an NPU for these at all.
        Work.TEXT_MEDIAPIPE, Work.IMAGE_DIFFUSION, Work.IMAGE_MEDIAPIPE -> false
        // A cloud endpoint runs on somebody else's hardware; the local backend is irrelevant.
        Work.IMAGE_CLOUD -> false
    }

    /** Whether the native build carries an NPU backend for llama.cpp (Qualcomm Hexagon today). */
    @Volatile
    var ggufNpuProbe: (() -> Boolean)? = null

    private fun ggufNpu(): Boolean = ggufNpuProbe?.invoke() ?: false
}
