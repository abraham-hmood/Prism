package com.prism.launcher.messaging

import com.prism.core.JvmHost
import com.prism.core.PlatformHost
import com.prism.core.PrismPlatform
import com.prism.launcher.PrismSettings
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The rule the whole NPU feature rests on: the accelerator is used when it was chosen, and never
 * otherwise, and a fallback is always explained.
 *
 * The three things asserted here are the three ways this could go wrong silently:
 *
 * 1. An engine opting into the NPU on a device whose user picked CPU. That is the "quietly ran
 *    somewhere else" failure, and it is why [AiBackend.npuChosen] exists.
 * 2. A user who chose NPU getting a CPU result with no mention of it. Indistinguishable from the
 *    feature not working.
 * 3. A format being reported as NPU-capable when no runtime on any of the five vendors can take it.
 */
class AiBackendTest {

    private class FakeReporter(
        private val ready: Boolean,
        private val detail: String = "the accelerator cannot take this",
        private val line: String = "Fake NPU (test silicon)",
    ) : AiBackend.Reporter {
        override fun npuReady() = ready
        override fun npuDetail(work: AiBackend.Work) = detail
        override fun describe() = line
    }

    private lateinit var previousHost: PlatformHost

    /**
     * The stored backend is the input to every assertion here, so it is written to a temporary root
     * rather than to the developer's real settings -- a test that leaves the user's machine on "NPU"
     * has edited their configuration to prove a point.
     */
    @BeforeTest
    fun isolateSettings() {
        previousHost = PrismPlatform.host
        PrismPlatform.host = JvmHost(
            rootOverride = File(
                System.getProperty("java.io.tmpdir"),
                "prism-aibackend-${System.nanoTime()}",
            ).apply { mkdirs() },
        )
    }

    @AfterTest
    fun cleanUp() {
        AiBackend.reporter = null
        AiBackend.ggufNpuProbe = null
        PrismSettings.setAiBackend(PrismSettings.AI_BACKEND_GPU)
        PrismPlatform.host = previousHost
    }

    @Test
    fun `nothing is said when the user did not choose NPU`() {
        AiBackend.reporter = FakeReporter(ready = true)
        PrismSettings.setAiBackend(PrismSettings.AI_BACKEND_CPU)

        // The silence is the feature: an engine that mentions the NPU when the user chose the CPU is
        // telling them about a decision they did not make.
        assertEquals("", AiBackend.noteFor(AiBackend.Work.TEXT_GGUF))
        assertEquals("", AiBackend.noteFor(AiBackend.Work.IMAGE_DIFFUSION))
        assertFalse(AiBackend.npuChosen())
    }

    @Test
    fun `choosing NPU asks the platform, rather than assuming an accelerator`() {
        PrismSettings.setAiBackend(PrismSettings.AI_BACKEND_NPU)
        AiBackend.reporter = null

        assertTrue(AiBackend.npuChosen(), "the choice is the user's; whether it can run is not")
        assertFalse(AiBackend.npuReady(), "no reporter means no accelerator path Prism can name")
        assertTrue(
            AiBackend.noteFor(AiBackend.Work.TEXT_TFLITE).isNotBlank(),
            "a substitution the user cannot see is indistinguishable from the feature not working",
        )
    }

    @Test
    fun `a diffusion checkpoint is never reported as NPU-capable`() {
        AiBackend.reporter = FakeReporter(ready = true)
        AiBackend.ggufNpuProbe = { true }
        PrismSettings.setAiBackend(PrismSettings.AI_BACKEND_NPU)

        // stable-diffusion.cpp has CPU, Vulkan, OpenCL and Metal backends and no NPU one, on any
        // vendor, so this is false no matter what the device offers.
        assertFalse(AiBackend.npuRuns(AiBackend.Work.IMAGE_DIFFUSION))
        assertFalse(AiBackend.npuRuns(AiBackend.Work.IMAGE_MEDIAPIPE))
        assertTrue(AiBackend.noteFor(AiBackend.Work.IMAGE_DIFFUSION).isNotBlank())
    }

    @Test
    fun `a GGUF needs the native Hexagon backend as well as an NPU`() {
        AiBackend.reporter = FakeReporter(ready = true)
        PrismSettings.setAiBackend(PrismSettings.AI_BACKEND_NPU)

        // An accelerator that can run TFLite and ONNX graphs is not enough for a GGUF: llama.cpp's
        // NPU backend is Qualcomm-only and absent from most builds, so the two questions are asked
        // separately and a Snapdragon whose APK lacks the SDK reports the GGUF as not covered.
        AiBackend.ggufNpuProbe = { false }
        assertFalse(AiBackend.npuRuns(AiBackend.Work.TEXT_GGUF))
        assertTrue(AiBackend.npuRuns(AiBackend.Work.TEXT_TFLITE))

        AiBackend.ggufNpuProbe = { true }
        assertTrue(AiBackend.npuRuns(AiBackend.Work.TEXT_GGUF))
        assertEquals("", AiBackend.noteFor(AiBackend.Work.TEXT_GGUF))
    }

    @Test
    fun `the picker line names the device, or says there is no accelerator`() {
        PrismSettings.setAiBackend(PrismSettings.AI_BACKEND_NPU)
        AiBackend.reporter = FakeReporter(ready = true, line = "Qualcomm Hexagon v75 (Snapdragon 8 Gen 3)")
        assertEquals("Qualcomm Hexagon v75 (Snapdragon 8 Gen 3)", AiBackend.describe())

        // A desktop, or any platform with no reporter: the honest answer is that there is no path,
        // not a blank space where an explanation should be.
        AiBackend.reporter = null
        assertTrue(AiBackend.describe().contains("no neural accelerator"))
    }
}
