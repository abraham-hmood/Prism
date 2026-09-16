package com.prism.launcher.messaging

import com.prism.core.PrismPlatform

/**
 * On-device text generation, whatever runtime provides it.
 *
 * TWO RUNTIMES, NOT ONE, WHICH IS WHY THIS INTERFACE EXISTS. Android runs `.task` models through
 * MediaPipe's LLM Inference API and `.gguf` models through the vendored llama.cpp bridge. Desktop
 * has no MediaPipe -- it is an Android library -- so it runs llama.cpp only. The agentic engine
 * does not care which; it wants text back.
 *
 * Defaults to [GgufLocalGenerator], so :core and the desktop build work with no wiring, and
 * Android replaces it with one that dispatches on file extension.
 */
interface LocalTextGenerator {

    /** Whether local generation can run at all right now. */
    fun isAvailable(): Boolean

    fun generate(modelPath: String, userText: String): String

    /**
     * @param onReasoning receives `<think>` deltas separately from the answer, so a UI can show a
     *   "thinking" indicator instead of dumping the reasoning trace into the transcript.
     */
    fun generateStreaming(
        modelPath: String,
        userText: String,
        maxTokens: Int,
        onToken: (String) -> Unit,
        onReasoning: ((String) -> Unit)? = null,
    ): String
}

/**
 * llama.cpp, via the JNI bridge. Works on every platform the bridge is built for.
 *
 * Refuses non-GGUF files by inspecting the magic bytes rather than the extension -- a `.task`
 * model handed to llama.cpp does not fail cleanly, it reads garbage as a header.
 */
object GgufLocalGenerator : LocalTextGenerator {

    override fun isAvailable(): Boolean = GgufInferenceService.isAvailable()

    override fun generate(modelPath: String, userText: String): String {
        reject(modelPath)?.let { return it }
        return GgufInferenceService.generateResponse(modelPath, userText)
    }

    override fun generateStreaming(
        modelPath: String,
        userText: String,
        maxTokens: Int,
        onToken: (String) -> Unit,
        onReasoning: ((String) -> Unit)?,
    ): String {
        reject(modelPath)?.let { onToken(it); return it }
        return GgufInferenceService.generateResponseStreaming(
            modelPath, userText, maxTokens, onToken, onReasoning
        )
    }

    private fun reject(modelPath: String): String? = when {
        !GgufInferenceService.isAvailable() ->
            "Local inference is unavailable: the llama.cpp bridge did not load. " +
                "Build it with :desktop:buildAllNative, or use a cloud model."
        !GgufInferenceService.isGgufFile(modelPath) ->
            "That model is not a GGUF file. This platform can only run GGUF models locally; " +
                "MediaPipe .task models are Android-only."
        else -> null
    }
}

/** Reports that nothing is available, without pretending otherwise. */
object NoLocalTextGenerator : LocalTextGenerator {
    override fun isAvailable(): Boolean = false
    override fun generate(modelPath: String, userText: String): String =
        "No local inference runtime is installed on this platform."

    override fun generateStreaming(
        modelPath: String, userText: String, maxTokens: Int,
        onToken: (String) -> Unit, onReasoning: ((String) -> Unit)?,
    ): String = generate(modelPath, userText).also(onToken)
}

/** The installed generator. Set by the platform; defaults to llama.cpp. */
object LocalAi {
    @Volatile
    var generator: LocalTextGenerator = GgufLocalGenerator

    fun available(): Boolean = try {
        generator.isAvailable()
    } catch (t: Throwable) {
        PrismPlatform.log.debug("Prism/localai", "Availability check failed: ${t.message}")
        false
    }
}
