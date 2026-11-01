package com.prism.launcher.messaging

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.widget.TextView
import com.prism.core.PrismCpu
import com.prism.launcher.PrismSettings
import java.io.File

/**
 * Debug-only probe for local GGUF throughput on the device.
 *
 * The numbers that matter -- tokens per second, accepted draft tokens, and whether an accelerator
 * is actually doing the work -- do not exist anywhere except on the phone. This runs the active
 * model through one mode or every mode and writes a line per run to logcat:
 *
 *     adb shell am start -n com.prism.launcher/.messaging.GgufBenchActivity \
 *         --es mode forced_diffusion --ei tokens 128
 *     adb shell am start -n com.prism.launcher/.messaging.GgufBenchActivity --ez all true
 *     adb shell am start -n com.prism.launcher/.messaging.GgufBenchActivity --es backend cpu
 *
 * Each mode is run twice: the first call pays for the model load (and any shader/module setup), the
 * second is the number to compare. The previous inference mode and backend are restored before
 * finishing, so a benchmark never silently changes how Sam answers afterwards.
 *
 * Debug only and exported for the same reason DiffusionBenchActivity is: it is a developer probe,
 * not a feature, and it is absent from a release build.
 */
class GgufBenchActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prompt = intent.getStringExtra("prompt")
            ?: "Write one long paragraph about the ocean. Do not stop early."
        val tokens = intent.getIntExtra("tokens", 96).coerceIn(8, 512)
        val runs = intent.getIntExtra("runs", 2).coerceIn(1, 3)
        val requestedMode = intent.getStringExtra("mode").orEmpty()
        val requestedBackend = intent.getStringExtra("backend").orEmpty()
        val requestedModel = intent.getStringExtra("model").orEmpty()
        val requestedThreads = intent.getIntExtra("threads", 0)
        val all = intent.getBooleanExtra("all", false)

        setContentView(TextView(this).apply {
            text = "Measuring local GGUF decode…"
            textSize = 14f
            setPadding(48, 96, 48, 48)
        })

        Thread({
            val previousMode = PrismSettings.getInferenceMode()
            val previousBackend = PrismSettings.getAiBackend()
            val previousThreads = PrismCpu.threadOverride
            try {
                run(prompt, tokens, runs, requestedMode, requestedBackend, requestedModel, requestedThreads, all)
            } catch (t: Throwable) {
                Log.e(TAG, "benchmark failed", t)
            } finally {
                PrismSettings.setInferenceMode(previousMode)
                PrismSettings.setAiBackend(previousBackend)
                PrismCpu.threadOverride = previousThreads
                runOnUiThread { finish() }
            }
        }, "gguf-bench").start()
    }

    private fun run(
        prompt: String,
        tokens: Int,
        runs: Int,
        requestedMode: String,
        requestedBackend: String,
        requestedModel: String,
        requestedThreads: Int,
        all: Boolean,
    ) {
        if (requestedThreads > 0) PrismCpu.threadOverride = requestedThreads
        val path = requestedModel.takeIf { it.isNotBlank() && File(it).isFile }
            ?: PrismSettings.getLocalAiModelPath()
        val backend = when (requestedBackend.lowercase()) {
            "cpu" -> PrismSettings.AI_BACKEND_CPU
            "gpu" -> PrismSettings.AI_BACKEND_GPU
            "npu" -> PrismSettings.AI_BACKEND_NPU
            else -> PrismSettings.getAiBackend()
        }
        val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else Build.HARDWARE
        Log.i(TAG, "device=${Build.MODEL} soc=$soc release=${Build.VERSION.RELEASE}")
        Log.i(TAG, "heap=${Runtime.getRuntime().maxMemory() / (1024 * 1024)}MB backend=${backendName(backend)} " +
            "threads=${PrismCpu.inferenceThreads()}")
        Log.i(TAG, "bridge=${GgufInferenceService.isAvailable()} model=$path")
        if (path.isBlank() || !GgufInferenceService.isAvailable()) {
            Log.w(TAG, "no active model or gguf bridge unavailable")
            return
        }

        PrismSettings.setAiBackend(backend)
        val modes = if (all) InferenceMode.selectable
        else listOf(InferenceMode.fromId(requestedMode.ifBlank { PrismSettings.getInferenceMode() }))

        // One short warm-up before timing anything: a cold load is seconds and would otherwise be
        // charged to whichever mode happened to run first.
        runCatching {
            GgufInferenceService.generateResponseStreaming(
                path, "Hello.", 2, onToken = {}, modeOverride = modes.first(),
            )
        }
        GgufInferenceService.resetConversation()

        for (mode in modes) {
            repeat(runs) { runIndex ->
                GgufInferenceService.resetConversation()
                val started = SystemClock.elapsedRealtime()
                val answer = runCatching {
                    GgufInferenceService.generateResponseStreaming(
                        path, prompt, tokens, onToken = {}, modeOverride = mode,
                    )
                }.getOrElse { error ->
                    Log.e(TAG, "mode=${mode.id} run=${runIndex + 1} threw ${error.message}")
                    return@repeat
                }
                val wallMs = SystemClock.elapsedRealtime() - started
                val stats = GgufInferenceService.liveStats
                val rate = GgufInferenceService.lastTokensPerSecond
                Log.i(
                    TAG,
                    "mode=${mode.id} run=${runIndex + 1} backend=${backendName(backend)} " +
                        "generated=${stats.generated} wall_ms=$wallMs " +
                        "tok_s=${"%.2f".format(rate)} " +
                        "accepted=${stats.accepted}/${stats.proposed} " +
                        "ctx=${stats.contextUsed}/${stats.contextSize} " +
                        "answer_chars=${answer.length}",
                )
            }
        }
        GgufInferenceService.resetConversation()
    }

    private fun backendName(backend: Int): String = when (backend) {
        PrismSettings.AI_BACKEND_CPU -> "cpu"
        PrismSettings.AI_BACKEND_GPU -> "gpu"
        PrismSettings.AI_BACKEND_NPU -> "npu"
        else -> backend.toString()
    }

    private companion object {
        const val TAG = "PrismBench"
    }
}
