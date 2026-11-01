package com.prism.core

import com.prism.launcher.modelcraft.TextTrainer
import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull

/**
 * Opt-in decode sweep for a model saved by [TextModelProbeTest].
 *
 * Run with `PRISM_TEXT_LOAD=<folder>` and, optionally, `PRISM_TEXT_SWEEP_REPORT=<file>`. The model is
 * loaded without retraining, so this separates "the model cannot answer" from "the sampler picked a
 * bad word". It writes one row per prompt and decode setting and prints the same table.
 */
class TextModelSamplingProbeTest {

    @Test
    fun `sweep temperature and top-k on a saved model`() {
        val directory = File(System.getenv("PRISM_TEXT_LOAD") ?: return)
        if (!directory.isDirectory) return
        val trained = assertNotNull(TextTrainer.load(directory), "the saved text model did not load")
        val prompts = listOf(
            "Hello",
            "How are you?",
            "What is the weather like today?",
            "I am feeling a little tired.",
            "Good morning.",
            "I'm sorry to hear that.",
            "Can you help me plan a small dinner?",
            "Tell me something interesting.",
            "What did you do yesterday?",
            "Do you want to have dinner with me?",
            "I love you.",
        )
        val settings = listOf(
            0.1 to 1,
            0.3 to 10,
            0.5 to 10,
            0.5 to 40,
            0.8 to 40,
        )

        val report = buildString {
            appendLine("# Decode sweep")
            appendLine()
            appendLine("- model: ${trained.net.parameterCount()} parameters, " +
                "${trained.tokenizer.size} vocabulary, ${trained.config.positions} positions")
            appendLine("- generation: ${trained.config.temperature} temperature, " +
                "${trained.config.topK} top-k, ${trained.config.repetitionPenalty} repetition penalty")
            appendLine()
            for ((temperature, topK) in settings) {
                appendLine("## temperature $temperature, top-k $topK")
                appendLine()
                for (prompt in prompts) {
                    val answer = trained.respond(prompt, 64, temperature = temperature, topK = topK)
                    appendLine("- **$prompt** → ${answer.ifBlank { "(empty)" }}")
                }
                appendLine()
            }
        }
        println(report)
        val reportPath = System.getenv("PRISM_TEXT_SWEEP_REPORT")
        if (!reportPath.isNullOrBlank()) File(reportPath).writeText(report)
    }
}
