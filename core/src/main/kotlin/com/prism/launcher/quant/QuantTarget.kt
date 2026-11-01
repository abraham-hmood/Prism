package com.prism.launcher.quant

import com.prism.core.PrismPlatform
import java.io.File

/**
 * One quantisation, whichever container the model is in.
 *
 * ## Why this exists rather than a second quantisation path
 *
 * The model tab's quantiser grew up around llama.cpp: one level list, one native call, one `.gguf`
 * output. Supporting safetensors with a parallel set of buttons and a second copy of the service's
 * progress bookkeeping would have meant two of everything -- and the first bug in either would be
 * fixed in one and not the other. So the two formats share everything that is not the arithmetic:
 * the destination naming, the space check, the foreground service, the progress estimate and the
 * import, and this interface is the seam.
 *
 * [forSource] is what a page asks: the levels that apply to THIS file, in the order they should be
 * offered. A GGUF gets the llama.cpp levels; a safetensors checkpoint gets the block levels from
 * [SafetensorQuantizer]; anything else gets none, and the page says so instead of offering a run
 * that could only fail.
 */
sealed interface QuantTarget {

    /** Shown in the level list. */
    val label: String

    /** Appended to the model's name and used for the notification, e.g. `Q4_K_M` or `I4`. */
    val fileSuffix: String

    /** Rough bits per weight, for the level list's own column. */
    val bitsPerWeight: Double

    /** A warning or an explanation, shown under the level. */
    val note: String

    /** The extension the result is written with, so a name never lies about its container. */
    val extension: String

    /** Whether the work this target describes can actually run in this build. */
    val available: Boolean

    /** Why not, when [available] is false. */
    val unavailableReason: String

    /** Roughly how large the result will be, for the progress display. */
    fun estimateOutputBytes(source: File): Long

    /**
     * Runs the quantisation. Blocking, and minutes for a real model.
     *
     * @return null on success, or a message describing the failure.
     */
    fun quantize(source: File, destination: File, threads: Int): String?

    /** A GGUF level, run by llama.cpp's own quantiser through the JNI bridge. */
    data class Gguf(val level: PrismQuantizer.Level) : QuantTarget {
        override val label = level.label
        override val fileSuffix = level.fileSuffix
        override val bitsPerWeight = level.bitsPerWeight
        override val note = level.note
        override val extension = "gguf"
        override val available get() = PrismQuantizer.isAvailable()
        override val unavailableReason =
            "gguf_bridge did not load, so llama.cpp's quantiser is not on this device."

        override fun estimateOutputBytes(source: File): Long =
            PrismQuantizer.estimateOutputBytes(source, level)

        override fun quantize(source: File, destination: File, threads: Int): String? =
            PrismQuantizer.quantize(source, destination, level, threads)
    }

    /** A safetensors level, run by [SafetensorQuantizer] in Kotlin. */
    data class Safetensors(val level: SafetensorQuantizer.Level) : QuantTarget {
        override val label = level.label
        override val fileSuffix = level.fileSuffix
        override val bitsPerWeight = level.bitsPerWeight
        override val note = level.note
        override val extension = "safetensors"
        override val available = true
        override val unavailableReason = ""

        override fun estimateOutputBytes(source: File): Long =
            SafetensorQuantizer.estimateOutputBytes(source, level)

        override fun quantize(source: File, destination: File, threads: Int): String? =
            SafetensorQuantizer.quantize(source, destination, level, threads)
    }

    companion object {

        /**
         * The levels that apply to [source], or an empty list when nothing here can quantise it.
         *
         * The container is decided by CONTENT rather than by name, for the reason the pages already
         * give: imports keep whatever name they arrived with, and a `.gguf`-shaped name is not
         * evidence of anything.
         */
        fun forSource(source: File): List<QuantTarget> = when {
            !source.isFile -> emptyList()
            ggufMagic(source) -> PrismQuantizer.Level.entries.map { Gguf(it) }
            SafetensorQuantizer.isSafetensorFile(source) ->
                SafetensorQuantizer.Level.entries.map { Safetensors(it) }
            else -> emptyList()
        }

        /** Convenience for list filtering, so a page does not build a level list it will discard. */
        fun isQuantisable(path: String): Boolean {
            val file = File(path)
            if (!file.isFile) return false
            return ggufMagic(file) || SafetensorQuantizer.isSafetensorFile(file)
        }

        /** `GGUF` in the first four bytes -- the check `GgufInferenceService.isGgufFile` makes. */
        private fun ggufMagic(file: File): Boolean = runCatching {
            file.inputStream().use { input ->
                val magic = ByteArray(4)
                input.read(magic) == 4 &&
                    magic[0] == 'G'.code.toByte() && magic[1] == 'G'.code.toByte() &&
                    magic[2] == 'U'.code.toByte() && magic[3] == 'F'.code.toByte()
            }
        }.getOrDefault(false)

        /** For a log line, not for a decision: what a level's file suffix means, spelled out. */
        fun describe(target: QuantTarget, source: File): String =
            "${source.name} -> ${target.fileSuffix} (${target.label}, " +
                "${"%.2f".format(target.bitsPerWeight)} bits/weight)"

        /** Logs a level list, once per page view, so a missing format is legible in a bug report. */
        fun logAvailability(source: File) {
            val targets = forSource(source)
            PrismPlatform.log.info(
                "Prism/quant",
                if (targets.isEmpty()) "${source.name} is neither a GGUF nor a safetensors file"
                else "${source.name}: ${targets.size} level(s) available",
            )
        }
    }
}
