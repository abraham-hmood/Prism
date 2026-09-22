package com.prism.launcher.messaging

import android.app.ActivityManager
import android.content.Context
import java.io.File

import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInference.LlmInferenceOptions
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.google.mediapipe.tasks.genai.llminference.ProgressListener
import com.prism.launcher.PrismLogger

/**
 * Executes on-device inference using MediaPipe LLM Inference API.
 * Includes safety guards to prevent native crashes on underpowered hardware.
 */
object LocalAiService {

    private var llmInference: LlmInference? = null
    private var llmSession: LlmInferenceSession? = null
    private var currentModelPath: String? = null
    private var currentBackend: Int = -1

    private fun getOrInitSession(context: Context, modelPath: String): LlmInferenceSession? {
        val backend = com.prism.launcher.PrismSettings.getAiBackend()
        if (llmInference != null && llmSession != null && currentModelPath == modelPath && currentBackend == backend) {
            return llmSession
        }

        // --- Stabilization Guard: Hardware & Format Verification ---
        val error = validateHardwareAndFormat(context, modelPath)
        if (error != null) {
            throw IllegalStateException(error)
        }

        val modelFile = java.io.File(modelPath)
        if (!modelFile.exists()) {
            PrismLogger.logError("LocalAiService", "Initialization ABORTED: Model file not found at $modelPath")
            return null
        }

        llmSession = null
        llmInference?.close()
        llmInference = null

        val preferredBackend = if (backend == com.prism.launcher.PrismSettings.AI_BACKEND_CPU) {
            LlmInference.Backend.CPU
        } else {
            LlmInference.Backend.GPU
        }

        PrismLogger.logInfo("LocalAiService", "Initializing engine for $modelPath (Size: ${modelFile.length()} bytes, Readable: ${modelFile.canRead()}, backend=$preferredBackend)")

        // This is where native crashes (SIGSEGV) occur if the model is raw TFLite.
        // GPU delegate init can also fail/perform badly on unsupported models or devices —
        // retry once on CPU rather than leaving generation broken or degraded.
        var inference = createInference(context, modelPath, preferredBackend)
        if (inference == null && preferredBackend == LlmInference.Backend.GPU) {
            PrismLogger.logError("LocalAiService", "GPU backend init failed for $modelPath, retrying on CPU")
            inference = createInference(context, modelPath, LlmInference.Backend.CPU)
        }
        if (inference == null) {
            PrismLogger.logError("LocalAiService", "FAILED to initialize AI engine for $modelPath on any backend")
            return null
        }

        return try {
            llmInference = inference

            val sessionOptions = LlmInferenceSession.LlmInferenceSessionOptions.builder()
                .setTopK(40)
                .setTemperature(0.7f)
                .build()

            val session = LlmInferenceSession.createFromOptions(inference, sessionOptions)
            llmSession = session
            currentModelPath = modelPath
            currentBackend = backend
            session
        } catch (e: Exception) {
            PrismLogger.logError("LocalAiService", "FAILED to create inference session for $modelPath", e)
            e.printStackTrace()
            inference.close()
            llmInference = null
            null
        }
    }

    private fun createInference(context: Context, modelPath: String, backend: LlmInference.Backend): LlmInference? {
        return try {
            val options = LlmInferenceOptions.builder()
                .setModelPath(modelPath)
                .setMaxTokens(2048)
                .setPreferredBackend(backend)
                .build()
            LlmInference.createFromOptions(context, options)
        } catch (e: Exception) {
            PrismLogger.logError("LocalAiService", "Failed to init LlmInference (backend=$backend) for $modelPath", e)
            null
        }
    }

    private fun validateHardwareAndFormat(context: Context, modelPath: String): String? {
        val modelFile = java.io.File(modelPath)
        if (!modelFile.exists()) return "File not found: $modelPath"

        // 1. What the file actually is, read from its bytes.
        //
        // This used to demand a ZIP magic at offset zero and describe anything else as the wrong
        // format for MediaPipe. Two things were wrong with that. A corrupt download -- a GGUF whose
        // first megabytes arrived as zeroes -- was reported as a format mismatch, sending the user
        // to look for a different model instead of downloading this one again. And a `.task` bundle
        // with a few bytes in front of its ZIP header was refused outright, even though ZIP readers
        // locate the central directory from the end of the file and handle a prefix by design.
        //
        // ModelFormat answers both questions honestly; the engine gets to decide the marginal case.
        val detection = com.prism.launcher.messaging.ModelFormat.detect(modelFile)

        when (detection.kind) {
            com.prism.launcher.messaging.ModelFormat.Kind.TASK_ZIP -> {
                if (detection.offset > 0) {
                    PrismLogger.logWarning(
                        "LocalAiService",
                        "${modelFile.name} has ${detection.offset} byte(s) before its ZIP header; " +
                            "letting MediaPipe try it anyway",
                    )
                }
            }

            com.prism.launcher.messaging.ModelFormat.Kind.GGUF -> {
                // Should have been routed to llama.cpp long before here; if it reaches this point
                // the routing is wrong, and saying so is more useful than a format complaint.
                PrismLogger.logError(
                    "LocalAiService", "A GGUF model reached the MediaPipe path: $modelPath",
                )
                return "Internal routing error: ${modelFile.name} is a GGUF model and should run " +
                    "on llama.cpp, not MediaPipe. Please report this."
            }

            else -> {
                PrismLogger.logError(
                    "LocalAiService",
                    "Unusable model ${modelFile.name}: ${detection.kind} " +
                        "(header ${detection.headerHex}, ${detection.leadingZeroes} leading zero bytes)",
                )
                return detection.explain(modelFile)
            }
        }

        // 2. Hardware/RAM Guard
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        
        val availableRamGb = memInfo.availMem / (1024.0 * 1024.0 * 1024.0)

        // Sized from the file, not from its name. The old threshold was a lookup on the filename --
        // 1.2 GB if it contained "gemma", 0.8 GB otherwise -- which let a 4 GB bundle through on a
        // phone with 1 GB free and refused a 300 MB one on a phone with 900 MB.
        val thresholdGb = com.prism.launcher.messaging.GgufInferenceService
            .requiredBytes(modelPath) / (1024.0 * 1024.0 * 1024.0)

        if (availableRamGb < thresholdGb) {
            return "Insufficient RAM: ${modelFile.name} needs about ${String.format("%.2f", thresholdGb)}GB " +
                "to load and only ${String.format("%.2f", availableRamGb)}GB is free. Close some apps " +
                "and try again, or pick a smaller quantisation."
        }

        return null
    }

    fun generateResponse(context: Context, modelPath: String, userText: String): String {
        if (modelPath.isEmpty()) return "No local model selected. Please check Settings."

        val modelFile = File(modelPath)
        if (!modelFile.exists()) {
            return "Error: Local model file not found at $modelPath. Please re-select it in Settings."
        }

        if (GgufInferenceService.isGgufFile(modelPath)) {
            return GgufInferenceService.generateResponse(modelPath, userText)
        }

        return try {
            val session = getOrInitSession(context, modelPath)
            if (session == null) {
                return "Error: Failed to initialize AI engine. The model may be incompatible with your device's GPU, or requires more RAM. Check System Diagnostics for details."
            }
            
            session.addQueryChunk(userText)
            val result = session.generateResponse()
            if (result.isNullOrBlank()) "The model returned an empty response." else result
        } catch (e: IllegalStateException) {
            "Safety Guard: ${e.message}"
        } catch (e: Exception) {
            e.printStackTrace()
            "Local AI Error: ${e.message}"
        }
    }

    /**
     * Streams a response, invoking [onToken] with each incremental piece of text as it's
     * generated. [maxTokens] <= 0 means unlimited (generate until the model stops on its own).
     * Still returns the full response text once generation completes, same as [generateResponse].
     * [onReasoning], GGUF-only: reasoning-trace deltas from a `<think>` block, kept separate
     * from [onToken] so a live "thinking" indicator can be driven off them.
     */
    fun generateResponseStreaming(context: Context, modelPath: String, userText: String, maxTokens: Int, onToken: (String) -> Unit, onReasoning: ((String) -> Unit)? = null): String {
        if (modelPath.isEmpty()) {
            val error = "No local model selected. Please check Settings."
            onToken(error)
            return error
        }

        val modelFile = File(modelPath)
        if (!modelFile.exists()) {
            val error = "Error: Local model file not found at $modelPath. Please re-select it in Settings."
            onToken(error)
            return error
        }

        if (GgufInferenceService.isGgufFile(modelPath)) {
            return GgufInferenceService.generateResponseStreaming(modelPath, userText, maxTokens, onToken, onReasoning)
        }

        return try {
            val session = getOrInitSession(context, modelPath)
            if (session == null) {
                val error = "Error: Failed to initialize AI engine. The model may be incompatible with your device's GPU, or requires more RAM. Check System Diagnostics for details."
                onToken(error)
                return error
            }

            session.addQueryChunk(userText)
            val accumulator = StringBuilder()
            // COUNTED INCREMENTALLY, one chunk at a time.
            //
            // This used to be `session.sizeInTokens(accumulator.toString())` on every callback:
            // a full copy of the reply so far, handed to the tokenizer across JNI, for every
            // token. Quadratic in the length of the answer, and the tokenizer is not cheap -- a
            // long reply spent most of its time re-counting text it had already counted, which
            // looked exactly like the model being slow.
            //
            // Summing each chunk's own count can drift by a token or two where a chunk boundary
            // splits a token, which does not matter: this is a stop threshold, not a ledger.
            var tokensSoFar = 0
            val listener = ProgressListener<String> { partial: String, _: Boolean ->
                accumulator.append(partial)
                onToken(partial)
                if (maxTokens > 0) {
                    tokensSoFar += runCatching { session.sizeInTokens(partial) }.getOrDefault(1)
                    if (tokensSoFar >= maxTokens) {
                        session.cancelGenerateResponseAsync()
                    }
                }
            }
            val future = session.generateResponseAsync(listener)

            val result = try {
                future.get()
            } catch (e: Exception) {
                accumulator.toString()
            }

            if (result.isNullOrBlank()) {
                if (accumulator.isEmpty()) "The model returned an empty response." else accumulator.toString()
            } else result
        } catch (e: IllegalStateException) {
            val error = "Safety Guard: ${e.message}"
            onToken(error)
            error
        } catch (e: Exception) {
            e.printStackTrace()
            val error = "Local AI Error: ${e.message}"
            onToken(error)
            error
        }
    }
}
