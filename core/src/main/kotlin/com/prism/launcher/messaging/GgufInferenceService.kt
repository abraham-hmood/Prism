package com.prism.launcher.messaging

import com.prism.core.PrismPlatform
import com.prism.launcher.PrismSettings
import java.io.File

/**
 * Executes on-device inference for GGUF models via a vendored llama.cpp JNI bridge.
 * Mirrors LocalAiService's single-cached-session pattern: one loaded model/context
 * stays alive (with its KV cache and chat history) as long as the model path is
 * unchanged, giving natural multi-turn continuity across calls.
 */
object GgufInferenceService {

    private val bridgeLoaded: Boolean = loadBridge()

    /**
     * Loads the bridge and, first, the libraries it links against.
     *
     * WHY THE DEPENDENCIES ARE LOADED EXPLICITLY. On Android the whole set is unpacked into one
     * directory that is already the process's library path, so `loadLibrary("gguf_bridge")`
     * resolves llama and ggml transitively and nothing else is needed.
     *
     * Windows does not work that way. `java.library.path` is consulted for the library you NAME;
     * its own imports are resolved by the OS loader through the standard DLL search order, which
     * does not include java.library.path. So gguf_bridge.dll is found, fails to bind llama.dll,
     * and surfaces as UnsatisfiedLinkError naming gguf_bridge -- pointing at the one library that
     * was not the problem. Loading each dependency by absolute path first, in link order, avoids
     * the search entirely.
     *
     * Failures here are swallowed on purpose: a machine without the native libraries should fall
     * back to cloud inference, not fail to start.
     */
    private fun loadBridge(): Boolean {
        // Dependency order matters: ggml-base underpins the backends, llama links all of them.
        val dependencies = listOf("ggml-base", "ggml-cpu", "ggml", "llama")
        val roots = System.getProperty("java.library.path").orEmpty()
            .split(java.io.File.pathSeparator)
            .filter { it.isNotBlank() }
            .map { java.io.File(it) }

        for (name in dependencies) {
            val found = roots.asSequence()
                .flatMap { dir ->
                    sequenceOf("$name.dll", "lib$name.so", "lib$name.dylib").map { java.io.File(dir, it) }
                }
                .firstOrNull { it.isFile }
            if (found != null) {
                try {
                    System.load(found.absolutePath)
                } catch (t: Throwable) {
                    // Already loaded, or not needed on this platform. Neither is fatal.
                    com.prism.core.PrismPlatform.log.debug(
                        "Prism/gguf", "Skipped ${found.name}: ${t.javaClass.simpleName}"
                    )
                }
            }
        }

        return try {
            System.loadLibrary("gguf_bridge")
            com.prism.core.PrismPlatform.log.info("Prism/gguf", "gguf_bridge loaded")
            true
        } catch (t: Throwable) {
            com.prism.core.PrismPlatform.log.warn(
                "Prism/gguf",
                "gguf_bridge unavailable (${t.javaClass.simpleName}); local .gguf inference is off"
            )
            false
        }
    }

    /** Whether local .gguf inference can run at all on this machine. */
    fun isAvailable(): Boolean = bridgeLoaded

    private val GGUF_MAGIC = byteArrayOf(0x47, 0x47, 0x55, 0x46) // "GGUF"

    private var handle: Long = 0L
    private var currentModelPath: String? = null
    private var currentKvCacheMode: Int = -1
    private var currentGpuMode: Int = -1

    /** Delta callback for streaming generation: invoked once per generated piece of text. */
    fun interface TokenCallback {
        fun onToken(piece: String)
    }

    private external fun nativeLoadModel(modelPath: String, nCtx: Int, nThreads: Int, kvCacheMode: Int, gpuMode: Int): Long
    private external fun nativeGenerate(handle: Long, userText: String, maxTokens: Int, temperature: Float, minP: Float): String
    private external fun nativeGenerateStreaming(handle: Long, userText: String, maxTokens: Int, temperature: Float, minP: Float, callback: TokenCallback): String
    private external fun nativeFreeModel(handle: Long)
    private external fun nativeHasHexagonSupport(): Boolean

    /**
     * Whether NPU (Qualcomm Hexagon) acceleration is actually available — true only if this
     * native library was built with a Hexagon SDK configured (see CMakeLists.txt/HEXAGON_SDK_ROOT)
     * AND a Hexagon device is registered on this specific device at runtime. Never assume NPU is
     * available just because the setting exists — this is the single source of truth the UI uses
     * to decide whether to offer it at all.
     */
    fun hasHexagonSupport(): Boolean = try {
        nativeHasHexagonSupport()
    } catch (e: Throwable) {
        false
    }

    fun isGgufFile(path: String): Boolean {
        return try {
            File(path).inputStream().use { input ->
                val head = ByteArray(4)
                if (input.read(head) != 4) return false
                head.contentEquals(GGUF_MAGIC)
            }
        } catch (e: Exception) {
            false
        }
    }

    @Synchronized
    fun generateResponse(modelPath: String, userText: String): String {
        val loadError = ensureLoaded(modelPath)
        if (loadError != null) return loadError

        return try {
            val result = nativeGenerate(handle, userText, 512, 0.7f, 0.05f)
            val stripped = stripThinkTags(result)
            if (stripped.isBlank()) "The model returned an empty response." else stripped
        } catch (e: Exception) {
            PrismPlatform.log.error("GgufInferenceService", "Generation failed for $modelPath", e)
            "Local AI Error: ${e.message}"
        }
    }

    /**
     * @param onToken invoked with each answer delta (reasoning-trace deltas, if any, are
     * routed to [onReasoning] instead — or dropped silently if [onReasoning] is null).
     * @param onReasoning invoked with each `<think>...</think>` delta as the model reasons,
     * before its final answer. Used to drive a live "thinking" indicator instead of dumping
     * the raw reasoning trace into the chat.
     */
    @Synchronized
    fun generateResponseStreaming(
        modelPath: String, userText: String, maxTokens: Int,
        onToken: (String) -> Unit, onReasoning: ((String) -> Unit)? = null
    ): String {
        val loadError = ensureLoaded(modelPath)
        if (loadError != null) {
            onToken(loadError)
            return loadError
        }

        val splitter = ThinkTagSplitter(onAnswer = onToken, onReasoning = onReasoning)
        return try {
            val result = nativeGenerateStreaming(handle, userText, maxTokens, 0.7f, 0.05f, TokenCallback { splitter.feed(it) })
            splitter.flush()
            val stripped = stripThinkTags(result)
            if (stripped.isBlank()) "The model returned an empty response." else stripped
        } catch (e: Exception) {
            splitter.flush()
            PrismPlatform.log.error("GgufInferenceService", "Streaming generation failed for $modelPath", e)
            val error = "Local AI Error: ${e.message}"
            onToken(error)
            error
        }
    }

    /** Loads (or reuses the already-loaded) model for [modelPath]. Returns an error message on failure, null on success. */
    private fun ensureLoaded(modelPath: String): String? {
        val kvCacheMode = kvCacheModeFor(PrismSettings.getKvCacheQuant())
        val gpuMode = PrismSettings.getAiBackend()
        if (handle != 0L && currentModelPath == modelPath && currentKvCacheMode == kvCacheMode && currentGpuMode == gpuMode) return null

        if (handle != 0L) {
            nativeFreeModel(handle)
            handle = 0L
            currentModelPath = null
        }

        val ramError = checkAvailableRam(modelPath)
        if (ramError != null) return ramError

        val cores = Runtime.getRuntime().availableProcessors()
        val threads = if (cores <= 4) cores else (cores * 0.8).toInt()
        PrismPlatform.log.info("GgufInferenceService", "Loading GGUF model $modelPath (threads=$threads, kvCacheMode=$kvCacheMode, gpuMode=$gpuMode)")

        val loaded = nativeLoadModel(modelPath, 2048, threads, kvCacheMode, gpuMode)
        if (loaded == 0L) {
            return "Error: Failed to load GGUF model. It may be corrupted, quantized in an unsupported way, or use an unsupported architecture."
        }
        handle = loaded
        currentModelPath = modelPath
        currentKvCacheMode = kvCacheMode
        currentGpuMode = gpuMode
        return null
    }

    private fun kvCacheModeFor(setting: String): Int = when (setting) {
        PrismSettings.KV_CACHE_Q8_0 -> 1
        PrismSettings.KV_CACHE_Q4_0 -> 2
        else -> 0
    }

    private val THINK_TAG_REGEX = Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL)

    private fun stripThinkTags(text: String): String = text.replace(THINK_TAG_REGEX, "").trim()

    /**
     * Splits a stream of raw token deltas into answer vs. `<think>...</think>` reasoning
     * deltas. Tags can arrive split across multiple deltas (each delta is often a single
     * token), so this buffers a small tail rather than matching per-delta.
     */
    private class ThinkTagSplitter(
        private val onAnswer: (String) -> Unit,
        private val onReasoning: ((String) -> Unit)?
    ) {
        companion object {
            private const val OPEN_TAG = "<think>"
            private const val CLOSE_TAG = "</think>"
            private val MAX_TAG_LEN = maxOf(OPEN_TAG.length, CLOSE_TAG.length)
        }

        private val buffer = StringBuilder()
        private var inThink = false

        fun feed(piece: String) {
            buffer.append(piece)
            process()
        }

        private fun process() {
            while (true) {
                val tag = if (inThink) CLOSE_TAG else OPEN_TAG
                val idx = buffer.indexOf(tag)
                if (idx == -1) {
                    // Hold back a tail that could be the start of a tag split across deltas.
                    val safeLen = (buffer.length - MAX_TAG_LEN + 1).coerceAtLeast(0)
                    if (safeLen > 0) {
                        emit(buffer.substring(0, safeLen))
                        buffer.delete(0, safeLen)
                    }
                    return
                }
                if (idx > 0) emit(buffer.substring(0, idx))
                buffer.delete(0, idx + tag.length)
                inThink = !inThink
            }
        }

        private fun emit(chunk: String) {
            if (chunk.isEmpty()) return
            if (inThink) onReasoning?.invoke(chunk) else onAnswer(chunk)
        }

        fun flush() {
            if (buffer.isNotEmpty()) {
                emit(buffer.toString())
                buffer.clear()
            }
        }
    }

    /** Frees the loaded native context if it currently belongs to [path] — used when a model is deleted. */
    @Synchronized
    fun unload(path: String) {
        if (handle != 0L && currentModelPath == path) {
            nativeFreeModel(handle)
            handle = 0L
            currentModelPath = null
            currentKvCacheMode = -1
            currentGpuMode = -1
        }
    }

    /**
     * Refuses to load a model that will not fit.
     *
     * WHY THIS GUARD EXISTS AT ALL: llama.cpp mmaps the weights and then touches them, so a model
     * that does not fit does not fail cleanly -- the OS thrashes, or the process is killed by the
     * low-memory killer on Android with no exception anywhere. Checking first turns a hang into a
     * message.
     *
     * `ActivityManager.MemoryInfo.availMem` became `PlatformHost.deviceRamBytes()`. The two are
     * not identical -- Android reports memory available RIGHT NOW, the host reports physical RAM
     * -- so this is deliberately conservative on desktop: it compares against the JVM's own free
     * heap headroom as well, which is the number that actually constrains a JVM process.
     */
    private fun checkAvailableRam(modelPath: String): String? {
        val runtime = Runtime.getRuntime()
        val jvmFree = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())
        val physical = PrismPlatform.host.deviceRamBytes()
        // Whichever is scarcer is the one that will stop you.
        val availableRamGb = minOf(jvmFree, physical) / (1024.0 * 1024.0 * 1024.0)

        val thresholdGb = when {
            modelPath.lowercase().contains("gemma") -> 1.2
            modelPath.lowercase().contains("phi") -> 1.2
            modelPath.lowercase().contains("qwen") -> 1.0
            else -> 0.8
        }

        if (availableRamGb < thresholdGb) {
            return "Insufficient RAM: only ${String.format("%.2f", availableRamGb)}GB free. This model requires ~${thresholdGb}GB free to initialize safely."
        }

        return null
    }
}
