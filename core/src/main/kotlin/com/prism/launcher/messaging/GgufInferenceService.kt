package com.prism.launcher.messaging

import com.prism.core.PrismPlatform
import com.prism.launcher.PrismSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
        // THE PACKAGED APP'S OWN DIRECTORY IS NOT ON java.library.path. A jpackage image keeps
        // every DLL in `app/resources` while the JVM's library path is the runtime's bin, so
        // `System.loadLibrary("gguf_bridge")` below fails with UnsatisfiedLinkError and local
        // inference is switched off in a process that clearly has working native code -- MEASURED
        // in the packaged desktop build. [PlatformHost.nativeLibraryDir] resolves it from the
        // location of Prism's own jar; it is searched first because it is the copy that shipped.
        val roots = buildList {
            com.prism.core.PrismPlatform.host.nativeLibraryDir()?.let { add(it) }
            addAll(
                System.getProperty("java.library.path").orEmpty()
                    .split(java.io.File.pathSeparator)
                    .filter { it.isNotBlank() }
                    .map { java.io.File(it) },
            )
        }

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
            // BY ABSOLUTE PATH WHEN THE DIRECTORY IS KNOWN. `System.loadLibrary` searches the
            // executable's directory, PATH and java.library.path -- none of which is where a
            // jpackage image puts the bridge -- so even after the dependencies above were loaded
            // from an explicit path this one line failed and local inference was declared off.
            val bridge = roots.asSequence()
                .map { java.io.File(it, System.mapLibraryName("gguf_bridge")) }
                .firstOrNull { it.isFile }
            if (bridge != null) {
                System.load(bridge.absolutePath)
            } else {
                System.loadLibrary("gguf_bridge")
            }

            // Before anything asks for a backend. The CPU kernels are separate libraries now (one
            // per ARM feature set, chosen by what this phone can actually execute -- see
            // CMakeLists.txt), and they are found by scanning a directory that only the platform
            // knows. Getting this wrong does not fail loudly: discovery simply finds nothing and
            // inference has no backend, so it is done here rather than at first use.
            com.prism.core.PrismPlatform.host.nativeLibraryDir()?.let { dir ->
                nativeSetBackendDir(dir.absolutePath)
            }

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

    /**
     * When the model was last loaded or used, for [releaseIfIdle]'s grace window.
     *
     * Loading a model is itself a memory spike, so Android can deliver `onLowMemory` immediately
     * after a successful load. The app used to take that as a reason to free the model it had just
     * finished loading, and the next generation would reload it -- on the S22 that produced a
     * 16s load, a 22s first reply, another 16s load, then an ANR. A just-used model is not idle.
     */
    @Volatile
    private var lastUseMillis: Long = 0L

    /** How long a loaded model is protected from idle release after it is loaded or used. */
    private const val RECENT_USE_GRACE_MS = 60_000L

    /**
     * Extra free RAM required, beyond the weights themselves, before they are copied into RAM.
     *
     * Copying wins on decode speed when it is possible at all (no page faults, no dependency on the
     * storage subsystem), but it is only possible with room to spare: the copy is anonymous memory
     * and Android answers anonymous pressure with ZRAM, not with eviction. 384 MB covers the KV
     * cache, compute buffers and allocator slack for a phone-sized context, and stops a model that
     * "just fits" from being copied into a swap file it will then spend every token faulting out
     * of.
     */
    private const val WEIGHTS_RESIDENCY_HEADROOM_BYTES = 384L shl 20

    /** Delta callback for streaming generation: invoked once per generated piece of text. */
    fun interface TokenCallback {
        fun onToken(piece: String)

        /**
         * Live counters, posted by the native engine after the prompt and every few tokens.
         *
         * A default method rather than a second abstract one so every existing `TokenCallback {
         * ... }` lambda keeps compiling. The bridge looks the method up by name and skips the
         * call when a callback does not provide it.
         */
        fun onStats(
            promptTokens: Int,
            generated: Int,
            contextUsed: Int,
            contextSize: Int,
            accepted: Int,
            proposed: Int,
        ) = Unit
    }

    /**
     * A snapshot of the running decode, for the conversation's context meter.
     *
     * Context use is what the user can act on: it is the number that explains why a long thread
     * eventually starts dropping its oldest turns. [accepted] and [proposed] are diagnostic --
     * they say how well the selected speculative mode is hitting.
     */
    data class LiveStats(
        val promptTokens: Int = 0,
        val generated: Int = 0,
        val contextUsed: Int = 0,
        val contextSize: Int = 0,
        val accepted: Int = 0,
        val proposed: Int = 0,
    ) {
        /** 0f..1f, or 0 when no model has reported a context size yet. */
        val contextFraction: Float
            get() = if (contextSize <= 0) 0f
            else (contextUsed.toFloat() / contextSize).coerceIn(0f, 1f)
    }

    @Volatile
    var liveStats: LiveStats = LiveStats()
        private set

    /** Smoothed rate for the live ticker; 0 before the first token of the turn. */
    @Volatile
    var liveTokensPerSecond: Double = 0.0
        private set

    fun resetLiveStats() {
        liveStats = LiveStats()
        liveTokensPerSecond = 0.0
    }

    /** Where the ggml backend libraries live. Call before any backend is used; see [loadBridge]. */
    private external fun nativeSetBackendDir(dir: String)

    /**
     * Restricts inference threads to these CPUs (bitmask of indices; 0 = no restriction).
     *
     * Set once per process, before a model loads: the native side builds its threadpool during the
     * load and the affinity belongs to that pool. See [com.prism.core.PrismCpu.performanceCoreMask]
     * for why counting threads without naming cores does not work.
     */
    private external fun nativeSetInferenceCpuMask(mask: Long)

    private external fun nativeLoadModel(
        modelPath: String, nCtx: Int, nThreads: Int, kvCacheMode: Int, gpuMode: Int,
        forceMmapFallback: Boolean, forceNoMmap: Boolean, swapBuffer: ByteBuffer?
    ): Long
    private external fun nativeGenerate(handle: Long, userText: String, maxTokens: Int, temperature: Float, minP: Float, mode: Int): String
    private external fun nativeGenerateStreaming(handle: Long, userText: String, maxTokens: Int, temperature: Float, minP: Float, callback: TokenCallback, mode: Int): String

    /**
     * Generation with a GBNF grammar masking the sampler.
     *
     * Empty grammar behaves exactly like [nativeGenerate]. Implemented natively (see
     * `gguf_bridge.cpp`'s `build_sampler_chain`) rather than in Kotlin because masking happens
     * between logits and sampling, which only the engine can do.
     */
    private external fun nativeGenerateJson(
        handle: Long, userText: String, maxTokens: Int, temperature: Float, minP: Float,
        grammar: String,
    ): String
    private external fun nativeResetConversation(handle: Long)
    private external fun nativeFreeModel(handle: Long)
    private external fun nativeHasHexagonSupport(): Boolean
    private external fun nativeAcceleratorMemoryBytes(): Long

    // ── Distributed inference across the mesh ──────────────────────────────
    //
    // ggml's own RPC backend. A peer runs `nativeStartRpcServer` and becomes a device other
    // devices can place tensors on; the device holding the model calls `nativeLoadModelDistributed`
    // with those peers' endpoints and llama.cpp spreads the layers across them exactly as it
    // spreads them across several GPUs in a desktop. Weights travel to the peers over the wire at
    // load time -- a peer contributes memory and arithmetic, and never needs the model file.
    private external fun nativeStartRpcServer(endpoint: String, cacheDir: String, nThreads: Int): Boolean
    private external fun nativeStopRpcServer()
    private external fun nativeLoadModelDistributed(
        modelPath: String, endpoints: Array<String>, nCtx: Int, nThreads: Int, kvCacheMode: Int
    ): Long

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

    /**
     * Memory of the best accelerator ggml found, or 0 when there is none.
     *
     * Reported by the backend itself rather than guessed from the SoC name. On the unified-memory
     * GPUs nearly every phone has, this is system RAM -- which is the truth about where those
     * tensors would live, and the compute market presents it as such instead of pretending a phone
     * has discrete video memory.
     */
    fun acceleratorMemoryBytes(): Long = try {
        if (bridgeLoaded) nativeAcceleratorMemoryBytes() else 0L
    } catch (e: Throwable) {
        0L
    }

    /**
     * Offers this device's memory and arithmetic to the mesh.
     *
     * Blocks for as long as the server runs, so callers put it on a thread of its own. Returns
     * false when the RPC backend is not compiled in, which is the one case a caller must handle
     * rather than report as a crash.
     */
    fun startRpcServer(endpoint: String, cacheDir: String, nThreads: Int): Boolean = try {
        bridgeLoaded && nativeStartRpcServer(endpoint, cacheDir, nThreads)
    } catch (e: Throwable) {
        false
    }

    fun stopRpcServer() {
        runCatching { if (bridgeLoaded) nativeStopRpcServer() }
    }

    /**
     * Loads a model with its layers spread across this device and the given peers.
     *
     * Returns a handle usable with the same generate calls as a local load, or 0 on failure. The
     * peers must already be running their RPC servers; nothing here starts them, because starting
     * one is a mesh negotiation and this is the native boundary.
     */
    fun loadDistributed(
        modelPath: String,
        endpoints: List<String>,
        nCtx: Int,
        nThreads: Int,
        kvCacheMode: Int,
    ): Long = try {
        if (!bridgeLoaded || endpoints.isEmpty()) 0L
        else nativeLoadModelDistributed(modelPath, endpoints.toTypedArray(), nCtx, nThreads, kvCacheMode)
    } catch (e: Throwable) {
        0L
    }

    /**
     * Generates from a handle the CALLER owns, bypassing the cached single-model path.
     *
     * Distributed loads cannot go through `ensureLoaded`: that caches exactly one model per process
     * and keys it on the path, and a model split across mesh peers is not interchangeable with the
     * same file loaded locally -- freeing one because the other was requested would tear down other
     * people's buffers mid-conversation. So the mesh path holds its own handle and frees it itself.
     */
    fun generateWithHandle(
        handle: Long,
        userText: String,
        onToken: ((String) -> Unit)? = null,
        maxTokens: Int = 512,
    ): String {
        if (handle == 0L) return ""
        // THE MESH PATH USES THE CHOSEN MODE TOO. This bypassed the cached load and hard-coded
        // STANDARD, so a generation split across peers silently ignored the mode the user picked.
        val mode = InferenceMode.fromId(PrismSettings.getInferenceMode())
        inFlight.incrementAndGet()
        return runCatching {
            val raw = if (onToken != null) {
                val splitter = ThinkTagSplitter(onAnswer = onToken, onReasoning = null)
                val result = nativeGenerateStreaming(
                    handle, userText, maxTokens, 0.7f, 0.05f,
                    TokenCallback { splitter.feed(it) }, mode.nativeId,
                )
                splitter.flush()
                result
            } else {
                nativeGenerate(handle, userText, maxTokens, 0.7f, 0.05f, mode.nativeId)
            }
            stripThinkTags(raw)
        }.also { inFlight.decrementAndGet() }.getOrDefault("")
    }

    /** Releases a handle from [loadDistributed]. Never pass the cached local handle to this. */
    fun freeHandle(handle: Long) {
        if (handle != 0L) runCatching { nativeFreeModel(handle) }
    }

    /**
     * Forgets the current conversation, keeping the model loaded.
     *
     * For callers that generate independent things rather than hold a conversation -- see the native
     * function's comment. A no-op when nothing is loaded, so a caller does not have to check.
     */
    /**
     * Changes whenever the native conversation changes in a way its owner did not do itself:
     * a reset, a model load or unload, or ANY generation.
     *
     * The model keeps ONE conversation in llama.cpp, shared by everything that talks to it -- Sam,
     * every character, the language tutor, a compaction summary. A chat that sends only its new
     * message is relying on that context still being ITS conversation; this is how it can tell.
     * A chat records the value right after its own turn, and if it is unchanged at the next turn
     * nothing else has touched the context in between. See AiManager's local path.
     */
    @Volatile
    var sessionEpoch: Long = 0L
        private set

    @Synchronized
    fun resetConversation() {
        sessionEpoch++
        resetLiveStats()
        if (handle == 0L) return
        runCatching { nativeResetConversation(handle) }
            .onFailure { PrismPlatform.log.warn("GgufInferenceService", "Could not reset: ${it.message}") }
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
        sessionEpoch++

        inFlight.incrementAndGet()
        val mode = InferenceMode.fromId(PrismSettings.getInferenceMode())
        return try {
            val result = nativeGenerate(handle, userText, 512, 0.7f, 0.05f, mode.nativeId)
            val stripped = stripThinkTags(result)
            if (stripped.isBlank()) "The model returned an empty response." else stripped
        } catch (e: Exception) {
            PrismPlatform.log.error("GgufInferenceService", "Generation failed for $modelPath", e)
            "Local AI Error: ${e.message}"
        } finally {
            inFlight.decrementAndGet()
        }
    }

    /**
     * Generates one independent, grammar-constrained reply.
     *
     * "INDEPENDENT" IS LOAD-BEARING. `nativeGenerate` appends every call to the handle's message
     * history, which is right for a chat and wrong for a planner: scene three's request must not
     * see scene two's JSON as prior conversation. So the history is cleared first, and the
     * grammar masks the sampler so the reply is syntactically a JSON object even from a model that
     * would otherwise wrap it in prose.
     *
     * Returns null -- not an error string -- because every caller has a deterministic fallback. A
     * planner that cannot complete still produces a film; a planner that throws loses the render.
     */
    @Synchronized
    fun generateJson(
        modelPath: String,
        userText: String,
        grammar: String,
        maxTokens: Int = 1024,
        temperature: Float = 0.7f,
    ): String? {
        if (grammar.isBlank()) return generateResponse(modelPath, userText)

        val loadError = ensureLoaded(modelPath)
        if (loadError != null) {
            PrismPlatform.log.warn("GgufInferenceService", "Cannot generate JSON: $loadError")
            return null
        }

        inFlight.incrementAndGet()
        return try {
            nativeResetConversation(handle)
            val raw = nativeGenerateJson(handle, userText, maxTokens, temperature, 0.05f, grammar)
            val stripped = stripThinkTags(raw).trim()
            when {
                stripped.isBlank() -> null
                stripped.startsWith("Error:") -> {
                    PrismPlatform.log.warn("GgufInferenceService", "JSON generation failed: $stripped")
                    null
                }
                else -> stripped
            }
        } catch (e: Exception) {
            PrismPlatform.log.error("GgufInferenceService", "JSON generation failed for $modelPath", e)
            null
        } finally {
            inFlight.decrementAndGet()
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
        onToken: (String) -> Unit, onReasoning: ((String) -> Unit)? = null,
        modeOverride: InferenceMode? = null,
    ): String {
        val mode = modeOverride ?: InferenceMode.fromId(PrismSettings.getInferenceMode())
        val loadError = ensureLoaded(modelPath, modeOverride = mode)
        if (loadError != null) {
            onToken(loadError)
            return loadError
        }
        sessionEpoch++

        val splitter = ThinkTagSplitter(onAnswer = onToken, onReasoning = onReasoning)
        inFlight.incrementAndGet()
        // Counted here rather than from the text: a token is what the engine emitted, and the
        // callback fires exactly once per token, so this is the rate the engine actually achieved
        // rather than one inferred from characters.
        var tokens = 0
        val startedAt = System.currentTimeMillis()
        liveStats = LiveStats()
        liveTokensPerSecond = 0.0
        return try {
            val callback = object : TokenCallback {
                override fun onToken(piece: String) {
                    tokens++
                    splitter.feed(piece)
                    // THE GAP BETWEEN TOKENS IS WHERE A HOT PHONE COOLS. Decode is a full sweep of
                    // the weights per token, so a short pause here lets the SoC fall back to a
                    // clock it can hold instead of running flat out until the platform forces the
                    // issue. Zero on a cool phone, so this costs nothing when nothing is wrong.
                    val pause = com.prism.core.PrismSustain.interTokenPauseMillis()
                    if (pause > 0L) {
                        runCatching { Thread.sleep(pause) }
                    }
                }

                override fun onStats(
                    promptTokens: Int, generated: Int, contextUsed: Int,
                    contextSize: Int, accepted: Int, proposed: Int,
                ) {
                    liveStats = LiveStats(
                        promptTokens = promptTokens,
                        generated = generated,
                        contextUsed = contextUsed,
                        contextSize = contextSize,
                        accepted = accepted,
                        proposed = proposed,
                    )
                    val ms = System.currentTimeMillis() - startedAt
                    if (ms > 250 && generated > 0) {
                        liveTokensPerSecond = generated * 1000.0 / ms
                    }
                }
            }
            val result = nativeGenerateStreaming(
                handle, userText, maxTokens, 0.7f, 0.05f, callback, mode.nativeId,
            )
            splitter.flush()
            val elapsed = System.currentTimeMillis() - startedAt
            if (elapsed > 0 && tokens > 0) {
                lastTokensPerSecond = tokens * 1000.0 / elapsed
                liveTokensPerSecond = lastTokensPerSecond
                PrismPlatform.log.info(
                    "GgufInferenceService",
                    "Generated $tokens tokens in $elapsed ms (${"%.1f".format(lastTokensPerSecond)} tok/s, " +
                        "mode=${mode.label})"
                )
            }
            val stripped = stripThinkTags(result)
            if (stripped.isBlank()) "The model returned an empty response." else stripped
        } catch (e: Exception) {
            splitter.flush()
            PrismPlatform.log.error("GgufInferenceService", "Streaming generation failed for $modelPath", e)
            val error = "Local AI Error: ${e.message}"
            onToken(error)
            error
        } finally {
            // MUST be in a finally. A counter that leaks on the error path never returns to zero,
            // and releaseIfIdle would then refuse forever -- the failure would look like the memory
            // fix simply not working, with nothing pointing at the cause.
            inFlight.decrementAndGet()
        }
    }

    /** Which tier (see this file's RAM-shortfall handling) the currently-loaded model actually
     * needed. [NONE] means it fit in RAM normally -- read by AiManager/ConversationActivity to
     * show a "running from Prism Swap" indicator only when one of the other two actually engaged. */
    enum class DegradedMode { NONE, MITIGATED, FULL_SWAP }

    @Volatile
    var lastLoadDegradedMode: DegradedMode = DegradedMode.NONE
        private set

    /**
     * How long the last native model load took, and how fast the last generation ran.
     *
     * Here because "the AI is slow" was, for a long time, unanswerable: the engine benchmarks at
     * ~19 tok/s generation on this hardware, so every report of slowness was actually a report
     * about something OUTSIDE the engine -- a reload that should not have happened, a degraded
     * tier, a cold page cache -- and none of it left a trace. A load time and a token rate on
     * every turn is what tells those apart without building a benchmark first.
     */
    @Volatile
    var lastLoadMillis: Long = 0L
        private set

    @Volatile
    var lastTokensPerSecond: Double = 0.0
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
    private fun ensureLoaded(
        modelPath: String,
        onStage: ((String) -> Unit)? = null,
        modeOverride: InferenceMode? = null,
    ): String? {
        val kvCacheMode = kvCacheModeFor(PrismSettings.getKvCacheQuant())
        // THE OFFLOAD BACKEND THAT PREVIOUSLY STALLED IS NOT TRIED AGAIN IN THIS PROCESS. GPU
        // (OpenCL) and NPU drivers can hang inside their own initialization, and `llama_model_load_
        // from_file` has no timeout -- so a driver that never returns leaves the load at "Loading
        // model weights..." forever with no error to report. See [reportStalledLoad]: the UI calls
        // it when a load has run for minutes, and the next attempt runs on CPU, which is what
        // actually gets the user to a working model. Changing the backend in Settings clears it.
        val requestedBackend = PrismSettings.getAiBackend()
        val offloadSuspended = requestedBackend != 0 && requestedBackend == stalledBackend
        val inferenceMode = modeOverride ?: InferenceMode.fromId(PrismSettings.getInferenceMode())
        // 3 is the bridge's "offer every device" loader. Heterogeneous execution is the one mode
        // whose whole purpose is splitting the graph, so it overrides a CPU-only backend
        // preference -- otherwise picking it with the backend set to CPU would silently run
        // exactly like Standard decoding. When no accelerator is registered the loader falls back
        // to CPU by itself, so there is nothing unsafe about asking.
        val gpuMode = when {
            offloadSuspended -> 0
            // Adreno 730 (Snapdragon 8 Gen 1) is not merely slow on ggml's OpenCL backend: it
            // aborts inside the driver on the first decode. The abort is a native SIGABRT, so
            // there is no exception to catch and no CPU retry -- the whole app dies. Treat the
            // accelerator as unavailable on this SoC rather than handing it the graph.
            isUnstableOpenClSoc() -> 0
            inferenceMode == InferenceMode.HETERO -> 3
            else -> requestedBackend
        }
        if (isUnstableOpenClSoc() && (requestedBackend != 0 || inferenceMode == InferenceMode.HETERO)) {
            PrismPlatform.log.warn(
                "GgufInferenceService",
                "GPU/NPU offload disabled on ${PrismPlatform.host.deviceName()}: its OpenCL driver aborts during decode"
            )
        }
        if (offloadSuspended) {
            PrismPlatform.log.warn(
                "GgufInferenceService",
                "GPU/NPU offload suspended after an earlier stalled load -- loading $modelPath on CPU"
            )
            onStage?.invoke("Running on the CPU — GPU offload stalled on an earlier attempt…")
        }
        if (handle != 0L && currentModelPath == modelPath && currentKvCacheMode == kvCacheMode && currentGpuMode == gpuMode) {
            lastUseMillis = System.currentTimeMillis()
            return null
        }

        if (handle != 0L) {
            onStage?.invoke("Freeing previous model...")
            nativeFreeModel(handle)
            handle = 0L
            sessionEpoch++
            currentModelPath = null
        }

        // The performance cores, not a fraction of every core. See PrismCpu for the measurement and
        // for why 0.8 x core count was the wrong shape on a big.LITTLE phone: it lands in the little
        // cluster, and ggml synchronises its threads at every graph node, so the slowest thread sets
        // the pace of the whole graph.
        //
        // ...and then through the thermal governor, which is the difference between a phone that
        // holds 4 threads all evening and one that spikes to 4 on every message, throttles, and then
        // spends minutes at 2 anyway. See PrismSustain for why trimming threads is the right lever
        // before Android trims the clocks.
        val thermalThreads = com.prism.core.PrismSustain.inferenceThreads(
            com.prism.core.PrismCpu.inferenceThreads()
        )
        val threads = thermalThreads
        if (threads < com.prism.core.PrismCpu.inferenceThreads()) {
            PrismPlatform.log.info(
                "GgufInferenceService",
                "Thermal budget: ${com.prism.core.PrismCpu.inferenceThreads()} -> $threads threads " +
                    "(${com.prism.core.PrismSustain.describe()})"
            )
        }
        // ...and then actually PUT them there. The count alone does not bind anything; the mask is
        // what stops the scheduler parking one of them on a little core.
        if (bridgeLoaded) {
            runCatching { nativeSetInferenceCpuMask(com.prism.core.PrismCpu.performanceCoreMask) }
                .onFailure { PrismPlatform.log.warn("GgufInferenceService", "Could not set CPU affinity: ${it.message}") }
        }

        onStage?.invoke("Checking available memory...")
        val deficit = ramDeficitBytes(modelPath)
        if (deficit == null) {
            lastLoadDegradedMode = DegradedMode.NONE
            onStage?.invoke("Loading model weights...")
            // DO THE WEIGHTS FIT, OR JUST THE BOOKKEEPING?
            //
            // [ramDeficitBytes] deliberately counts only what CANNOT be evicted (KV cache, compute
            // buffers, runtime structures) -- the weights are mmap-able, so a model "fits" even
            // when its file is larger than free RAM. That is the right answer for whether a load
            // will succeed, and the WRONG answer for how to load it.
            //
            // Copying the weights into anonymous memory only works if there is room for them.
            // When there is not, the copy is charged against swap: MEASURED on a Galaxy S22 with
            // 150 MB free and a 1.1 GB Q5_K_M model, the load took MINUTES instead of ten seconds,
            // with 84 MB of the process already in ZRAM before the load finished, and every
            // generated token then faulted its weights back out of a compressed swap file. Mapped
            // pages are clean and file-backed: the kernel can drop them instantly, no ZRAM write,
            // no thrash, and the load itself is a few page-table entries.
            //
            // The old measurement that made "always copy" look right ("0.1 tok/s with mmap") was
            // taken on a build compiled at -O0 -- the same 0.1 tok/s the whole engine produced, with
            // or without mmap. See APP_CFLAGS_DEBUG in app/src/main/cpp/CMakeLists.txt.
            val weights = runCatching { File(modelPath).length() }.getOrDefault(0L)
            val weightsFit = availableRamBytes() > weights + WEIGHTS_RESIDENCY_HEADROOM_BYTES
            if (!weightsFit) {
                PrismPlatform.log.info(
                    "GgufInferenceService",
                    "Weights (${formatGb(weights)}GB) exceed free RAM (${formatGb(availableRamBytes())}GB) " +
                        "-- mapping them instead of copying, so decode does not run out of swap"
                )
            }
            return attemptLoad(
                modelPath, 2048, threads, kvCacheMode, gpuMode,
                forceMmapFallback = !weightsFit, forceNoMmap = weightsFit, swapBuffer = null,
            )
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
            val err = attemptLoad(
                modelPath, degradedNCtx, threads, degradedKvCacheMode, gpuMode,
                forceMmapFallback = true, forceNoMmap = false, swapBuffer = null,
            )
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
            val err = attemptLoad(
                modelPath, 1024, threads, kvCacheMode, 0,
                forceMmapFallback = true, forceNoMmap = false, swapBuffer = swapBuffer,
            )
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

    /**
     * Loads [modelPath] on a scope that outlives the page that asked for it.
     *
     * WHY THE ENGINE OWNS THE SCOPE. Activation used to launch this on the page's own coroutine
     * scope, and the launcher offloads pages -- so a load still in flight when the page went away
     * was cancelled, its completion callback never ran, and the progress sheet sat on "Loading model
     * weights..." with nothing behind it. A native load cannot be cancelled anyway: it is one
     * blocking JNI call, so cancelling the coroutine only ever lost the answer.
     *
     * [onFinish] is invoked exactly once, on the main dispatcher, with null on success or a message
     * on failure. Nothing on this path throws -- an [Error] from native code becomes a message too,
     * because an escaping Error is what leaves a caller waiting forever.
     */
    fun preloadEager(
        modelPath: String,
        onStage: ((String) -> Unit)? = null,
        onFinish: ((String?) -> Unit)? = null,
    ): Job = loadScope.launch {
        val error = try {
            preload(modelPath, onStage)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            PrismPlatform.log.error("GgufInferenceService", "Loading $modelPath failed", t)
            "Error: ${t.javaClass.simpleName} while loading ${File(modelPath).name}" +
                (t.message?.let { ": $it" } ?: "")
        }
        withContext(Dispatchers.Main) { onFinish?.invoke(error) }
    }

    /**
     * The backend that stalled, or 0 (CPU, i.e. nothing to avoid).
     *
     * Read by [ensureLoaded] to skip the offload path that hung, and cleared by [allowGpuOffloadAgain]
     * -- which `PrismSettings.setAiBackend` calls, so choosing a backend again is the user saying
     * "try once more" rather than being permanently overruled by a stale suspicion.
     */
    @Volatile
    private var stalledBackend: Int = 0

    /** True while GPU/NPU offload is being skipped because an earlier load stalled with it on. */
    fun gpuOffloadSuspended(): Boolean =
        PrismSettings.getAiBackend() != 0 && PrismSettings.getAiBackend() == stalledBackend

    /** Clears the stall suspicion -- see [stalledBackend]. */
    fun allowGpuOffloadAgain() {
        stalledBackend = 0
    }

    /**
     * Called by the UI when a load has been running long enough that it offered to leave it in the
     * background. Records which offload backend was in play so the NEXT attempt skips it; the load
     * already in flight cannot be interrupted, so this is the only remedy that exists for a driver
     * that hangs inside `llama_model_load_from_file` with no timeout of its own.
     */
    fun reportStalledLoad(modelPath: String) {
        val backend = PrismSettings.getAiBackend()
        if (backend != 0) stalledBackend = backend
        val consequence = if (backend == 0) {
            "the load may simply be large."
        } else {
            "the next attempt will run on CPU instead."
        }
        PrismPlatform.log.warn(
            "GgufInferenceService",
            "${File(modelPath).name} has been loading for minutes with backend=$backend; $consequence",
        )
    }

    /**
     * Loads run here rather than on a page's scope. See [preloadEager].
     *
     * SupervisorJob rather than a plain one, and never cancelled: a failed load must not take the
     * engine's ability to try again with it.
     */
    private val loadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun attemptLoad(
        modelPath: String, nCtx: Int, threads: Int, kvCacheMode: Int, gpuMode: Int,
        forceMmapFallback: Boolean, forceNoMmap: Boolean, swapBuffer: ByteBuffer?
    ): String? {
        PrismPlatform.log.info(
            "GgufInferenceService",
            "Loading GGUF model $modelPath (threads=$threads, kvCacheMode=$kvCacheMode, gpuMode=$gpuMode, " +
                "forceMmapFallback=$forceMmapFallback, forceNoMmap=$forceNoMmap, swap=${swapBuffer != null})"
        )
        val startedAt = System.currentTimeMillis()
        val loaded = try {
            nativeLoadModel(modelPath, nCtx, threads, kvCacheMode, gpuMode, forceMmapFallback, forceNoMmap, swapBuffer)
        } catch (t: Throwable) {
            // AN Error IS NOT AN Exception, and this used to let one through. UnsatisfiedLinkError
            // (a backend library that did not unpack) and OutOfMemoryError (a failed mmap under
            // memory pressure) both leave here as Errors, and a caller that only caught Exception
            // never learned the load had ended -- which is exactly what a progress sheet stuck on
            // "Loading model weights..." looks like from the outside.
            PrismPlatform.log.error(
                "GgufInferenceService",
                "Native load of $modelPath threw ${t.javaClass.name}",
                t,
            )
            return "Error: loading this model failed in native code " +
                "(${t.javaClass.simpleName}${t.message?.let { ": $it" } ?: ""})."
        }
        if (loaded == 0L) {
            return "Error: Failed to load GGUF model. It may be corrupted, quantized in an unsupported way, or use an unsupported architecture."
        }
        lastLoadMillis = System.currentTimeMillis() - startedAt
        PrismPlatform.log.info(
            "GgufInferenceService",
            "Loaded $modelPath in ${lastLoadMillis} ms"
        )
        handle = loaded
        sessionEpoch++
        currentModelPath = modelPath
        currentKvCacheMode = kvCacheMode
        currentGpuMode = gpuMode
        lastUseMillis = System.currentTimeMillis()
        return null
    }

    /**
     * Snapdragon 8 Gen 1's Adreno 730 is the one accelerator this app must not use.
     *
     * MEASURED on the Galaxy S22 this was developed against: selecting GPU, or Heterogeneous
     * execution, loads the model and then aborts inside `libggml-opencl` on the first decode. The
     * abort is a native `std::out_of_range` in the OpenCL kernel cache, so no Kotlin catch and no
     * CPU retry can run -- the entire launcher dies. It is also slower than the CPU when it does
     * not abort, so there is nothing to preserve by trying.
     *
     * Matched on the SoC rather than on a driver version because Samsung does not expose an
     * OpenCL platform to apps at all on this build; the failure is the mobile driver generation,
     * not one vendor's package.
     */
    private fun isUnstableOpenClSoc(): Boolean {
        val soc = runCatching { PrismPlatform.host.socModel() }.getOrDefault("")
        return soc.equals("SM8450", ignoreCase = true)
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

    /**
     * How many generations are running right now.
     *
     * EXISTS SO MEMORY PRESSURE CANNOT FREE A MODEL MID-SENTENCE. `nativeFreeModel` releases the
     * llama context while a generate call may still be inside it, which is a use-after-free in
     * native code -- a hard crash of the launcher, from a callback whose entire purpose was to
     * avoid being killed. Counted rather than a boolean because streaming and non-streaming
     * generation can overlap on different threads.
     */
    private val inFlight = java.util.concurrent.atomic.AtomicInteger(0)

    /** True while any generation is running. */
    val isGenerating: Boolean get() = inFlight.get() > 0

    /**
     * Frees the resident model if nothing is using it.
     *
     * Returns the number of bytes it is likely to have released, for logging -- the file size,
     * which is the right order of magnitude since the weights dominate.
     *
     * The model reloads on the next request. That costs seconds of mmap and page-in, which is the
     * correct trade when the alternative is the system killing the launcher: a slow first reply is
     * recoverable, and being killed while serving as the home screen is not.
     */
    fun releaseIfIdle(): Long {
        if (isGenerating) return 0L
        synchronized(this) {
            if (handle == 0L || isGenerating) return 0L
            // A model that was loaded or used seconds ago is not idle in any sense the user
            // would recognise. Freeing it here is what turned a low-memory callback into a
            // reload loop; the grace window is long enough to cover a load plus the first
            // prompt, and short enough that a genuinely backgrounded app still gives the RAM back.
            if (System.currentTimeMillis() - lastUseMillis < RECENT_USE_GRACE_MS) return 0L
            val size = currentModelPath?.let { runCatching { File(it).length() }.getOrDefault(0L) } ?: 0L
            nativeFreeModel(handle)
            handle = 0L
            sessionEpoch++
            currentModelPath = null
            return size
        }
    }

    /** Frees the loaded native context if it currently belongs to [path] — used when a model is deleted. */
    @Synchronized
    fun unload(path: String) {
        if (handle != 0L && currentModelPath == path) {
            nativeFreeModel(handle)
            handle = 0L
            sessionEpoch++
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
    /**
     * Physical RAM free right now.
     *
     * NOT the Java heap. This used to be `min(Runtime.maxMemory() - used, deviceRam)`, and the heap
     * term dominated every time: Android caps an app's Java heap at a few hundred megabytes, while a
     * GGUF model is loaded by llama.cpp into NATIVE memory where that cap does not apply. The result
     * was that available RAM read as ~0.3 GB on an 8 GB phone, every model looked like it did not
     * fit, and every single load went down the degraded ladder -- reduced context, forced mmap, and
     * in the worst case building a multi-gigabyte swapfile before the first token. That is why small
     * models were no faster than large ones: the size of the model never entered into it.
     */
    private fun availableRamBytes(): Long {
        val reported = PrismPlatform.host.availableRamBytes()
        return if (reported > 0) reported else PrismPlatform.host.deviceRamBytes()
    }

    private fun availableRamGb(): Double = availableRamBytes() / (1024.0 * 1024.0 * 1024.0)

    /**
     * What loading [modelPath] needs RESIDENT, which is not the same as how big it is.
     *
     * Both engines mmap the weights. Mapped pages are page cache: the kernel can evict them under
     * pressure and fault them back in, so they are not an allocation that can fail. What genuinely
     * has to fit is the part that cannot be evicted -- the KV cache, the compute buffers, and the
     * runtime's own bookkeeping.
     *
     * This matters because the first version of this function counted the whole file, which made a
     * 1.6 GB model "need" 1.93 GB on a phone reporting 2.08 GB free. It passed by a hair when the
     * phone was idle and failed the moment anything else was open -- reported to the user as
     * "Insufficient RAM" for a model that would have loaded and run.
     *
     * Weights still matter, but to SPEED rather than to success: a model larger than free memory
     * pages in and out as it generates, which is slow, not fatal. [weightsExceedRam] reports that
     * separately so it can be explained rather than prevented.
     */
    fun requiredBytes(modelPath: String, nCtx: Int = 2048): Long {
        // ~1 MB per 1k of context across key and value at the quantised sizes Prism loads with.
        val kvCache = (nCtx / 1024L).coerceAtLeast(1L) * (1L shl 20)

        // Compute buffers, the tokenizer, and the runtime's own structures.
        val overhead = 320L shl 20

        // Headroom so a load does not succeed and then immediately push the app into a kill.
        val headroom = 256L shl 20

        return kvCache + overhead + headroom
    }

    /**
     * True when the weights are bigger than the RAM available to hold them.
     *
     * Not a failure -- the model still loads and answers. It is the honest explanation for why a
     * model much larger than the phone's free memory generates slowly, which otherwise looks like
     * Prism being slow rather than the device being small.
     */
    fun weightsExceedRam(modelPath: String): Boolean =
        File(modelPath).length() > availableRamBytes()

    private fun requiredGb(modelPath: String): Double =
        requiredBytes(modelPath) / (1024.0 * 1024.0 * 1024.0)

    /** Bytes short of what [modelPath] needs to load normally, or null if it already fits. Pure
     * (no side effects) -- used by [ensureLoaded]'s retry ladder and by
     * `AiManager.onLocalTextModelActivated`'s post-import/activation auto-configuration, which
     * needs the same number to size Prism Swap's slider without actually loading anything. */
    fun ramDeficitBytes(modelPath: String): Long? {
        val available = availableRamBytes()
        val needed = requiredBytes(modelPath)
        if (available >= needed) return null
        return needed - available
    }
}
