package com.prism.launcher.messaging

import com.prism.core.PrismPlatform
import com.prism.launcher.PrismSettings
import java.io.File
import java.nio.ByteBuffer

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

    private external fun nativeLoadModel(
        modelPath: String, nCtx: Int, nThreads: Int, kvCacheMode: Int, gpuMode: Int,
        forceMmapFallback: Boolean, swapBuffer: ByteBuffer?
    ): Long
    private external fun nativeGenerate(handle: Long, userText: String, maxTokens: Int, temperature: Float, minP: Float): String
    private external fun nativeGenerateStreaming(handle: Long, userText: String, maxTokens: Int, temperature: Float, minP: Float, callback: TokenCallback): String
    private external fun nativeFreeModel(handle: Long)
    private external fun nativeHasHexagonSupport(): Boolean

    // Aether's experimental ANN-baseline-conversion feature (see AetherAnnBaseline.kt) -- a
    // separate, CPU-only, flash-attention-disabled context from the chat one above, so it never
    // shares or disturbs a live Sam conversation's KV cache/history. Packed result format for
    // nativeRunCalibrationPass is documented on the native side (gguf_bridge.cpp): index 0 is a
    // 0/1 "ok" flag, 1..3 are [nLayersObserved, logitAbsMean, logitAbsP99], index 4 onward is one
    // saliency float per byte of the input text (attention), and -- only when [wantSoftTargets]
    // is true -- one further float per byte after that (soft-target distillation: the teacher-
    // forced probability this model assigned the character the text actually continues with).
    external fun nativeLoadCalibrationModel(modelPath: String, nThreads: Int): Long
    external fun nativeRunCalibrationPass(handle: Long, text: String, wantSoftTargets: Boolean): FloatArray
    external fun nativeFreeCalibrationModel(handle: Long)

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

    /** Which tier (see this file's RAM-shortfall handling) the currently-loaded model actually
     * needed. [NONE] means it fit in RAM normally -- read by AiManager/ConversationActivity to
     * show a "running from Prism Swap" indicator only when one of the other two actually engaged. */
    enum class DegradedMode { NONE, MITIGATED, FULL_SWAP }

    @Volatile
    var lastLoadDegradedMode: DegradedMode = DegradedMode.NONE
        private set

    /** Loads (or reuses the already-loaded) model for [modelPath]. Returns an error message on
     * failure, null on success.
     *
     * THREE-TIER RAM LADDER, LIGHT TO HEAVY:
     *  1. Fits normally -- load with the user's own settings, unchanged.
     *  2. Short by no more than [PrismSettings.getPrismSwapMitigationThresholdBytes] -- force the
     *     mmap fallback (read weights straight from the GGUF file's own mapping instead of a
     *     repacked in-RAM copy, for the one quant format that otherwise forces one), upgrade the
     *     KV-cache quantization, and shrink the context. All plain parameter changes -- see
     *     gguf_bridge.cpp's nativeLoadModel.
     *  3. Short by no more than [PrismSettings.getPrismSwapFullThresholdBytes] -- route weights,
     *     KV cache, and compute buffers through a genuinely swap-backed ggml device (Prism Swap
     *     Tier 2 -- see gguf_bridge.cpp's "Prism Swap pseudo-device" block and [PrismSwap]).
     * Anything short by more than the full-swap threshold (or that Prism Swap's own free storage
     * can't cover) still fails, with the original message.
     */
    private fun ensureLoaded(modelPath: String, onStage: ((String) -> Unit)? = null): String? {
        val kvCacheMode = kvCacheModeFor(PrismSettings.getKvCacheQuant())
        val gpuMode = PrismSettings.getAiBackend()
        if (handle != 0L && currentModelPath == modelPath && currentKvCacheMode == kvCacheMode && currentGpuMode == gpuMode) return null

        if (handle != 0L) {
            onStage?.invoke("Freeing previous model...")
            nativeFreeModel(handle)
            handle = 0L
            currentModelPath = null
        }

        val cores = Runtime.getRuntime().availableProcessors()
        val threads = if (cores <= 4) cores else (cores * 0.8).toInt()

        onStage?.invoke("Checking available memory...")
        val deficit = ramDeficitBytes(modelPath)
        if (deficit == null) {
            lastLoadDegradedMode = DegradedMode.NONE
            onStage?.invoke("Loading model weights...")
            return attemptLoad(modelPath, 2048, threads, kvCacheMode, gpuMode, forceMmapFallback = false, swapBuffer = null)
        }

        val mitigationThreshold = PrismSettings.getPrismSwapMitigationThresholdBytes()
        val fullThreshold = PrismSettings.getPrismSwapFullThresholdBytes()

        if (deficit <= mitigationThreshold) {
            val degradedKvCacheMode = maxOf(kvCacheMode, 1) // at least Q8_0, whatever the user's own setting was
            val degradedNCtx = 1024
            PrismPlatform.log.warn(
                "GgufInferenceService",
                "RAM short by ${formatGb(deficit)}GB for $modelPath -- trying Tier 1 mitigation " +
                    "(mmap fallback, kvCache=$degradedKvCacheMode, nCtx=$degradedNCtx)"
            )
            onStage?.invoke("Not quite enough free RAM -- applying reduced-memory settings...")
            val err = attemptLoad(modelPath, degradedNCtx, threads, degradedKvCacheMode, gpuMode, forceMmapFallback = true, swapBuffer = null)
            if (err == null) {
                lastLoadDegradedMode = DegradedMode.MITIGATED
                return null
            }
            PrismPlatform.log.warn("GgufInferenceService", "Tier 1 mitigation failed to load $modelPath: $err")
            // falls through to Tier 2 below
        }

        if (deficit <= fullThreshold) {
            val swapNeeded = deficit + (256L shl 20) // safety margin for llama.cpp's own bookkeeping overhead
            if (PrismSwap.freeStorageBytes() < swapNeeded) {
                return "Insufficient RAM: ${formatGb(deficit)}GB short, and not enough free storage " +
                    "for Prism Swap (needs ~${formatGb(swapNeeded)}GB) to cover it."
            }
            onStage?.invoke("Setting up Prism Swap...")
            PrismSwap.open(maxOf(PrismSettings.getPrismSwapBytes(), swapNeeded))
            val swapBuffer = PrismSwap.allocateBytes(swapNeeded)
            if (swapBuffer == null) {
                return "Insufficient RAM: Prism Swap could not allocate the space this model needs."
            }
            PrismPlatform.log.warn("GgufInferenceService", "RAM short by ${formatGb(deficit)}GB for $modelPath -- trying Tier 2 (full Prism Swap)")
            // CPU only under full swap -- combining this with GPU/NPU offload isn't a case worth
            // supporting for what's already the last-resort tier.
            onStage?.invoke("Loading model weights via Prism Swap (this will be slower)...")
            val err = attemptLoad(modelPath, 1024, threads, kvCacheMode, 0, forceMmapFallback = true, swapBuffer = swapBuffer)
            if (err == null) {
                lastLoadDegradedMode = DegradedMode.FULL_SWAP
                return null
            }
            return err
        }

        lastLoadDegradedMode = DegradedMode.NONE
        return "Insufficient RAM: only ${"%.2f".format(availableRamGb())}GB free. This model requires " +
            "~${requiredGb(modelPath)}GB free to initialize safely -- that's beyond Prism Swap's configured threshold."
    }

    /**
     * Public, eager entry point for warming up a model outside of an actual generation call --
     * used right after a model is imported/activated so a progress popup can show real loading
     * stages instead of the load happening silently on whatever chat message the user sends
     * first. Shares every stage of [ensureLoaded]'s RAM-shortfall ladder, including its cache
     * check (a no-op call when [modelPath] is already loaded just reports nothing and returns
     * immediately, matching every other caller of [ensureLoaded]).
     */
    @Synchronized
    fun preload(modelPath: String, onStage: ((String) -> Unit)? = null): String? = ensureLoaded(modelPath, onStage)

    private fun attemptLoad(
        modelPath: String, nCtx: Int, threads: Int, kvCacheMode: Int, gpuMode: Int,
        forceMmapFallback: Boolean, swapBuffer: ByteBuffer?
    ): String? {
        PrismPlatform.log.info(
            "GgufInferenceService",
            "Loading GGUF model $modelPath (threads=$threads, kvCacheMode=$kvCacheMode, gpuMode=$gpuMode, " +
                "forceMmapFallback=$forceMmapFallback, swap=${swapBuffer != null})"
        )
        val loaded = nativeLoadModel(modelPath, nCtx, threads, kvCacheMode, gpuMode, forceMmapFallback, swapBuffer)
        if (loaded == 0L) {
            return "Error: Failed to load GGUF model. It may be corrupted, quantized in an unsupported way, or use an unsupported architecture."
        }
        handle = loaded
        currentModelPath = modelPath
        currentKvCacheMode = kvCacheMode
        currentGpuMode = gpuMode
        return null
    }

    private fun formatGb(bytes: Long): String = "%.2f".format(bytes / (1024.0 * 1024.0 * 1024.0))

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
     * WHY THIS GUARD EXISTS AT ALL: llama.cpp mmaps the weights and then touches them, so a model
     * that does not fit does not fail cleanly -- the OS thrashes, or the process is killed by the
     * low-memory killer on Android with no exception anywhere. Checking first turns a hang into a
     * message (or, now, a Prism Swap fallback -- see [ensureLoaded]).
     *
     * `ActivityManager.MemoryInfo.availMem` became `PlatformHost.deviceRamBytes()`. The two are
     * not identical -- Android reports memory available RIGHT NOW, the host reports physical RAM
     * -- so this is deliberately conservative on desktop: it compares against the JVM's own free
     * heap headroom as well, which is the number that actually constrains a JVM process.
     */
    private fun availableRamGb(): Double {
        val runtime = Runtime.getRuntime()
        val jvmFree = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())
        val physical = PrismPlatform.host.deviceRamBytes()
        // Whichever is scarcer is the one that will stop you.
        return minOf(jvmFree, physical) / (1024.0 * 1024.0 * 1024.0)
    }

    private fun requiredGb(modelPath: String): Double = when {
        modelPath.lowercase().contains("gemma") -> 1.2
        modelPath.lowercase().contains("phi") -> 1.2
        modelPath.lowercase().contains("qwen") -> 1.0
        else -> 0.8
    }

    /** Bytes short of what [modelPath] needs to load normally, or null if it already fits. Pure
     * (no side effects) -- used by [ensureLoaded]'s retry ladder and by
     * `AiManager.onLocalTextModelActivated`'s post-import/activation auto-configuration, which
     * needs the same number to size Prism Swap's slider without actually loading anything. */
    fun ramDeficitBytes(modelPath: String): Long? {
        val availableGb = availableRamGb()
        val neededGb = requiredGb(modelPath)
        if (availableGb >= neededGb) return null
        return ((neededGb - availableGb) * 1024.0 * 1024.0 * 1024.0).toLong()
    }
}
