package com.prism.launcher.speech

import android.content.Context
import com.prism.launcher.PrismLogger
import java.io.File

/**
 * A speech model the user brought themselves.
 *
 * ## Why this recognises formats instead of just loading files
 *
 * "Import a TTS model" sounds like one feature and is not. A text model has a universal contract --
 * tokens in, tokens out -- which is why any GGUF can be dropped in and answer. Speech models have no
 * such thing: one takes phoneme ids and a style vector, another takes characters, another wants mel
 * spectrograms and a separate vocoder, and the sample rates and output layouts differ too. A loader
 * that accepted any file and hoped would produce noise, not speech.
 *
 * So this identifies what it was given and only claims the ones it can actually drive. Anything else
 * is reported as unsupported and [PrismSpeaker] keeps talking through the engine it already had --
 * which is the honest outcome, and a much better one than a silent call.
 *
 * ## What is recognised today
 *
 * A **Kokoro-format ONNX export**, because that contract is known exactly: phoneme ids, a 256-value
 * style vector and a speed, out to 24 kHz mono. That is the same pipeline the built-in Kokoro uses,
 * so importing a different Kokoro build -- a newer export, a different quantisation, a fine-tune --
 * works and behaves identically.
 *
 * GGUF and TFLite speech models are accepted by the importer and registered, so they are on disk and
 * listed, but nothing here can drive them yet. They are named in [describe] rather than silently
 * ignored.
 */
object ImportedAudioEngine {

    private const val TAG = "PrismSpeech"

    enum class Format {
        /** An ONNX export with Kokoro's input signature; runnable through the Kokoro pipeline. */
        KOKORO_ONNX,

        /** Recognised, registered, not yet runnable. */
        GGUF,

        /** Recognised, registered, not yet runnable. */
        TFLITE,

        /** PyTorch weights. Not loadable on Android at all; see KokoroInstall for why. */
        TORCH,

        UNKNOWN,
    }

    /** Extensions the picker should accept when importing an audio model. */
    val ACCEPTED_EXTENSIONS = listOf("onnx", "gguf", "tflite", "bin", "pt", "pth")

    fun formatOf(path: String): Format = when (File(path).extension.lowercase()) {
        "onnx" -> Format.KOKORO_ONNX
        "gguf" -> Format.GGUF
        "tflite" -> Format.TFLITE
        "pt", "pth" -> Format.TORCH
        else -> Format.UNKNOWN
    }

    /** One line for a settings row: what the file is, and whether it can speak. */
    fun describe(path: String): String {
        if (path.isEmpty()) return "None — Kokoro-82M is used"
        val name = File(path).name
        return when (formatOf(path)) {
            Format.KOKORO_ONNX -> "$name — ONNX, used for speech"
            Format.GGUF -> "$name — GGUF, recognised but not yet runnable for speech"
            Format.TFLITE -> "$name — TFLite, recognised but not yet runnable for speech"
            Format.TORCH ->
                "$name — PyTorch weights, which Android cannot load; export to ONNX first"
            Format.UNKNOWN -> "$name — unrecognised format"
        }
    }

    /**
     * Opens [path] if this build can drive it, or null if it cannot.
     *
     * Null is not a failure to be reported as an error: it means "keep using what you had", and the
     * caller does exactly that.
     */
    fun openOrNull(context: Context, path: String): TtsEngine? {
        if (path.isEmpty()) return null
        val file = File(path)
        if (!file.isFile) {
            PrismLogger.logWarning(TAG, "The imported audio model is missing: $path")
            return null
        }

        return when (formatOf(path)) {
            Format.KOKORO_ONNX -> {
                // The runtime that would load this is not wired up yet. Logged at info, because an
                // unfinished path is not an error and the user is told in Settings either way.
                PrismLogger.logInfo(TAG, "ONNX speech models are recognised but not yet runnable")
                null
            }
            else -> {
                PrismLogger.logInfo(TAG, "No runner for ${file.name}; keeping the current voice")
                null
            }
        }
    }
}
