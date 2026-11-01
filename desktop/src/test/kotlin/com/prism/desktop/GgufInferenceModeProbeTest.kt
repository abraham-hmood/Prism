package com.prism.desktop

import com.prism.core.JvmHost
import com.prism.core.PrismPlatform
import com.prism.launcher.messaging.GgufInferenceService
import com.prism.launcher.messaging.InferenceMode
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.test.assertTrue

/**
 * Runs every inference mode against a real GGUF.
 *
 * ## Why this is opt-in
 *
 * A model is hundreds of megabytes and lives on the developer's machine, not in the repository.
 * The test therefore skips loudly unless `PRISM_GGUF_MODEL` (or `-Dprism.ggufModel`) names one,
 * and `PRISM_GGUF_PROBE=1` turns it on. Skipped is reported as skipped, not as a green pass.
 *
 * ## What it is for
 *
 * The batch verifier, the KV rollback and the block refinement are the parts of the inference
 * modes that compile happily and can still be wrong. This exercises each of them on the same
 * prompt, prints the measured rate, and fails if a mode returns an error or produces no tokens.
 * It is not a quality benchmark; it is a "does this path run at all" probe, which is exactly the
 * failure the original sequential loop never had to worry about.
 */
class GgufInferenceModeProbeTest {

    private fun model(): File? =
        (System.getenv("PRISM_GGUF_MODEL")
            ?: System.getProperty("prism.ggufModel"))
            ?.let { File(it) }
            ?.takeIf { it.isFile }

    @Test
    fun `every inference mode decodes without an error`() {
        assumeTrue("No GGUF configured; set PRISM_GGUF_MODEL to run this probe.", model() != null)
        assumeTrue(
            "Set PRISM_GGUF_PROBE=1 (or -Pprism.ggufProbe=1) to run this probe.",
            System.getenv("PRISM_GGUF_PROBE") == "1" ||
                System.getProperty("prism.ggufProbe") == "1",
        )

        val root = File(System.getProperty("java.io.tmpdir"), "prism-gguf-probe-${System.nanoTime()}")
            .apply { mkdirs() }
        val previousHost = PrismPlatform.host
        PrismPlatform.host = JvmHost(rootOverride = root)
        try {
            val path = model()!!.absolutePath
            assertTrue(GgufInferenceService.isAvailable(), "gguf_bridge did not load")

            val results = mutableListOf<String>()
            for (mode in InferenceMode.selectable) {
                GgufInferenceService.resetConversation()
                val answer = GgufInferenceService.generateResponseStreaming(
                    path,
                    "Reply with one short sentence about the sea.",
                    24,
                    onToken = {},
                    modeOverride = mode,
                )
                val stats = GgufInferenceService.liveStats
                println(
                    "mode=${mode.id} rate=${"%.1f".format(GgufInferenceService.lastTokensPerSecond)} tok/s " +
                        "generated=${stats.generated} accepted=${stats.accepted}/${stats.proposed} " +
                        "ctx=${stats.contextUsed}/${stats.contextSize} answer=${answer.take(60).replace("\n", " ")}"
                )
                assertTrue(
                    !answer.startsWith("Error:") && !answer.startsWith("Local AI Error:"),
                    "mode ${mode.id} reported: ${answer.take(200)}",
                )
                assertTrue(stats.generated > 0, "mode ${mode.id} produced no tokens")
                results += mode.id
            }
            assertTrue(results.size == InferenceMode.selectable.size)
        } finally {
            GgufInferenceService.resetConversation()
            PrismPlatform.host = previousHost
            root.deleteRecursively()
        }
    }
}
