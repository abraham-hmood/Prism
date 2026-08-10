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

        // 1. Signature Guard: Magic Bytes
        // MediaPipe .task files ARE ZIP archives (starting with PK\03\04)
        // Raw TFLite files start with TFL3.
        try {
            val raf = java.io.RandomAccessFile(modelFile, "r")
            val head = ByteArray(4)
            val bytesRead = raf.read(head)
            raf.close()

            // An empty/truncated file (0-3 readable header bytes) is a corrupt import, not a
            // format mismatch — the download/copy step failed to write real content. Diagnose
            // that honestly instead of blaming "wrong format" (a zero-filled buffer would
            // otherwise misreport as Hex: 00000000, "Unknown/Binary").
            if (bytesRead < 4) {
                PrismLogger.logError("LocalAiService", "Corrupt import: only read $bytesRead of 4 header bytes from $modelPath (file size ${modelFile.length()} bytes)")
                return "Model file is empty or corrupted (could only read $bytesRead of 4 header bytes). Please delete it in the Models page and re-download or re-import it."
            }

            val hex = head.joinToString("") { String.format("%02X", it) }

            // ZIP Magic: 50 4B 03 04
            if (hex != "504B0304") {
                val type = when (hex) {
                    "54464C33" -> "Raw TFLite File (TFL3)"
                    else -> "Unknown/Binary (Hex: $hex)"
                }
                PrismLogger.logError("LocalAiService", "Format Mismatch: File is not a ZIP/Task bundle. Type detected: $type")
                return "Incompatible Model Format: This file is a $type, but MediaPipe Android requires a wrapped '.task' ZIP bundle. Please ensure you are using a model exported specifically for the MediaPipe Android LLM Inference API."
            }
        } catch (e: Exception) {
            return "Read Error: Unable to verify model header: ${e.message}"
        }

        // 2. Hardware/RAM Guard
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        
        val availableRamGb = memInfo.availMem / (1024.0 * 1024.0 * 1024.0)
        
        val thresholdGb = when {
            modelPath.lowercase().contains("gemma") -> 1.2
            modelPath.lowercase().contains("phi") -> 1.2
            modelPath.lowercase().contains("qwen") -> 1.0
            else -> 0.8
        }
        
        if (availableRamGb < thresholdGb) {
            return "Insufficient RAM: Your device only has ${String.format("%.2f", availableRamGb)}GB free. This model requires ~${thresholdGb}GB free to initialize safely."
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
            val listener = ProgressListener<String> { partial: String, _: Boolean ->
                accumulator.append(partial)
                onToken(partial)
                if (maxTokens > 0 && session.sizeInTokens(accumulator.toString()) >= maxTokens) {
                    session.cancelGenerateResponseAsync()
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
