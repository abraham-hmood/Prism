package com.prism.launcher.platform

import android.content.Context
import com.prism.launcher.messaging.GgufInferenceService
import com.prism.launcher.messaging.LocalAiService
import com.prism.launcher.messaging.LocalTextGenerator

/**
 * Android's local generator: MediaPipe for `.task`, llama.cpp for `.gguf`.
 *
 * WHY THIS EXISTS RATHER THAN :core CALLING LocalAiService DIRECTLY. MediaPipe's LLM Inference
 * API is an Android library -- there is no desktop build of it -- so `LocalAiService` cannot move
 * to :core, and the agentic engine cannot name it. The engine asks [LocalTextGenerator] instead
 * and Android installs this.
 *
 * The dispatch itself is already inside `LocalAiService`, which checks the GGUF magic bytes
 * rather than the extension and routes accordingly. This adapter only supplies the `Context` that
 * MediaPipe needs and that :core has no way to hold.
 */
class AndroidLocalAi(context: Context) : LocalTextGenerator {

    private val appContext = context.applicationContext

    /**
     * True whenever a runtime exists at all.
     *
     * MediaPipe is always present in the Android build, so this is not conditional on the
     * llama.cpp bridge the way the desktop generator's is -- a `.task` model runs even if
     * nora_conv and gguf_bridge failed to load.
     */
    override fun isAvailable(): Boolean = true

    override fun generate(modelPath: String, userText: String): String =
        LocalAiService.generateResponse(appContext, modelPath, userText)

    override fun generateStreaming(
        modelPath: String,
        userText: String,
        maxTokens: Int,
        onToken: (String) -> Unit,
        onReasoning: ((String) -> Unit)?,
    ): String = LocalAiService.generateResponseStreaming(
        appContext, modelPath, userText, maxTokens, onToken, onReasoning
    )

    /** Whether THIS model can run, which is a different question from [isAvailable]. */
    fun canRun(modelPath: String): Boolean =
        GgufInferenceService.isGgufFile(modelPath) || modelPath.endsWith(".task", ignoreCase = true)
}
