package com.prism.launcher.speech

import com.prism.core.PrismPlatform
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
 * A **ModelCraft speech model** -- the folder a text-to-speech or cloning run trained -- is the other
 * runnable format. Its own engine is [com.prism.launcher.modelcraft.ModelCraftSpeechEngine]: text in,
 * a WAV out, with the reference recording used as the voice when the model is a clone.
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

        /**
         * A folder trained by ModelCraft: a voice recording chosen from the dataset, spoken by the
         * Pocket TTS neural engine, beside the compact diagnostic pipeline's weights.
         */
        PRISM_TTS,

        /** Recognised, registered, not yet runnable. */
        GGUF,

        /** Recognised, registered, not yet runnable. */
        TFLITE,

        /** PyTorch weights. Not loadable on Android at all; see KokoroInstall for why. */
        TORCH,

        UNKNOWN,
    }

    /** Extensions the picker should accept when importing an audio model. */
    val ACCEPTED_EXTENSIONS = listOf("onnx", "gguf", "tflite", "bin", "pt", "pth", "json")

    fun formatOf(path: String): Format {
        val file = File(path)
        if (file.isDirectory) return if (com.prism.launcher.modelcraft.TtsTrainer.trained(file)) {
            Format.PRISM_TTS
        } else {
            Format.UNKNOWN
        }
        if (file.name.equals(com.prism.launcher.modelcraft.TtsTrainer.METADATA_FILE, true)) {
            return Format.PRISM_TTS
        }
        return when (file.extension.lowercase()) {
        "onnx" -> Format.KOKORO_ONNX
        "gguf" -> Format.GGUF
        "tflite" -> Format.TFLITE
        "pt", "pth" -> Format.TORCH
        else -> Format.UNKNOWN
        }
    }

    /** One line for a settings row: what the file is, and whether it can speak. */
    fun describe(path: String): String {
        if (path.isEmpty()) return "None — Kokoro-82M is used"
        val name = File(path).name
        return when (formatOf(path)) {
            Format.KOKORO_ONNX -> "$name — ONNX, used for speech"
            Format.PRISM_TTS -> "$name — ModelCraft speech model, used for text-to-speech and cloning"
            Format.GGUF -> "$name — GGUF, recognised but not yet runnable for speech"
            Format.TFLITE -> "$name — TFLite, recognised but not yet runnable for speech"
            Format.TORCH ->
                "$name — PyTorch weights, which no JVM here can load; export to ONNX first"
            Format.UNKNOWN -> "$name — unrecognised format"
        }
    }

    /**
     * Opens [path] if this build can drive it, or null if it cannot.
     *
     * Null is not a failure to be reported as an error: it means "keep using what you had", and the
     * caller does exactly that.
     */
    fun openOrNull(path: String): TtsEngine? {
        if (path.isEmpty()) return null

        // A ModelCraft model first: it may be a folder, which the extension-based reader below
        // would reject before looking inside.
        com.prism.launcher.modelcraft.ModelCraftSpeechEngine.openOrNull(path)?.let { engine ->
            PrismPlatform.log.info(TAG, "Using ModelCraft speech model ${engine.displayName}")
            return engine
        }
        val pathFile = File(path)
        if (pathFile.isDirectory || pathFile.name.equals(
                com.prism.launcher.modelcraft.TtsTrainer.METADATA_FILE, true,
            )
        ) {
            PrismPlatform.log.warn(
                TAG,
                "$path is a ModelCraft speech folder without readable weights; keeping the current voice",
            )
            return null
        }
        val file = pathFile
        if (!file.isFile) {
            PrismPlatform.log.warn(TAG, "The imported audio model is missing: $path")
            return null
        }

        return when (formatOf(path)) {
            Format.KOKORO_ONNX -> {
                // Kokoro's input contract is known exactly -- phoneme ids, a style row and a speed --
                // so a compatible ONNX export runs through the same pipeline as the built-in
                // weights. Anything whose signature differs fails to open and the caller keeps the
                // engine it already had, which is the documented behaviour for every format here.
                KokoroEngine.openOrNull(path).also { engine ->
                    if (engine != null) {
                        PrismPlatform.log.info(TAG, "Using imported Kokoro-format model ${file.name}")
                    } else {
                        PrismPlatform.log.warn(
                            TAG,
                            "${file.name} is ONNX but not a compatible Kokoro export; keeping the current voice",
                        )
                    }
                }
            }
            else -> {
                PrismPlatform.log.info(TAG, "No runner for ${file.name}; keeping the current voice")
                null
            }
        }
    }
}
