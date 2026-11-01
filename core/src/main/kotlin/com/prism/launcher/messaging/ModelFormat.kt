package com.prism.launcher.messaging

import java.io.File
import java.io.RandomAccessFile

/**
 * What a model file actually is, decided by reading it rather than by trusting its name.
 *
 * ## Why this exists
 *
 * An extension is a claim, not a fact. A `.gguf` that failed to download is still called `.gguf`,
 * and the engine that opens it reports "incompatible format" -- which is a true statement about the
 * bytes and a misleading one about the problem. A user told their file is the wrong format goes
 * looking for a different model; a user told their download is corrupt downloads it again.
 *
 * Both failures were live in Prism: a Falcon GGUF whose first eight megabytes were zeroes was
 * reported as "Unknown/Binary (Hex: 00000000), but MediaPipe requires a .task ZIP bundle", naming
 * neither the real problem nor the engine the file was actually for.
 *
 * ## The offset matters, and differently per format
 *
 * ZIP tolerates a prefix by design -- that is how a self-extracting archive works, and readers find
 * the central directory from the END of the file. A `.task` bundle with a few stray bytes in front
 * of it is very likely still loadable, so this reports the offset and lets the engine decide.
 *
 * GGUF does not. llama.cpp reads the header at offset zero, so a GGUF whose magic is anywhere else
 * is not a GGUF that happens to be shifted -- it is a broken file.
 */
object ModelFormat {

    enum class Kind {
        /** llama.cpp weights. Only valid at offset 0. */
        GGUF,

        /**
         * Hugging Face's tensor container, which stable-diffusion.cpp reads directly.
         *
         * An eight-byte little-endian header length followed by that many bytes of JSON, so unlike
         * every other format here the magic is a number rather than a string. Detected structurally
         * for that reason: "{` first" would be a guess, "a sane length then a JSON object" is the
         * format.
         */
        SAFETENSORS,

        /** A MediaPipe `.task` bundle, which is a ZIP. A small prefix is survivable. */
        TASK_ZIP,

        /** A bare TFLite model, which MediaPipe's LLM API cannot load on its own. */
        TFLITE,

        /** Zero bytes on disk. */
        EMPTY,

        /** Real size, but the leading megabyte is zeroes -- an interrupted or holed download. */
        INCOMPLETE,

        /** Readable, and none of the above. */
        UNKNOWN,
    }

    /**
     * @param offset where the magic was found; 0 for a well-formed file.
     * @param leadingZeroes how much of the start is zero-filled, which is what distinguishes a
     *        truncated download from a file in an unexpected format.
     */
    data class Detection(
        val kind: Kind,
        val offset: Long,
        val leadingZeroes: Long,
        val headerHex: String,
    ) {
        val isUsable: Boolean
            get() = kind == Kind.GGUF || kind == Kind.TASK_ZIP || kind == Kind.SAFETENSORS

        /** What to tell the user, phrased as the thing they can act on. */
        fun explain(file: File): String = when (kind) {
            Kind.EMPTY ->
                "${file.name} is empty. The download or import wrote nothing — delete it in the " +
                    "Models page and fetch it again."

            Kind.INCOMPLETE ->
                // Says "or the published file is broken", because that is what it turned out to be
                // the first time this fired: tiiuae's Falcon3-1B-Instruct-q2_k.gguf is served by
                // Hugging Face beginning with zeroes, while q4_k_m from the same repo is a healthy
                // GGUF. Telling someone to retry a download that will never succeed is worse than
                // telling them nothing.
                "${file.name} has no header: the first ${leadingZeroes / 1024} KB are blank, even " +
                    "though the file is ${file.length() / (1024 * 1024)} MB. Either the download " +
                    "broke, or the file is published that way. Try downloading it again, and if it " +
                    "fails the same way, pick a different quantisation — the fault is upstream."

            Kind.TFLITE ->
                "${file.name} is a bare TFLite model. MediaPipe's LLM API needs a `.task` bundle, " +
                    "which packages the TFLite model together with its tokenizer and metadata."

            Kind.SAFETENSORS ->
                "${file.name} is a safetensors checkpoint. Image generation reads it directly; " +
                    "the text engine needs the same weights converted to GGUF."

            Kind.UNKNOWN ->
                "${file.name} is not a format Prism can run (header $headerHex). Prism reads GGUF " +
                    "for llama.cpp, safetensors for stable-diffusion.cpp and `.task` bundles for " +
                    "MediaPipe."

            else -> ""
        }
    }

    private val GGUF = byteArrayOf(0x47, 0x47, 0x55, 0x46)          // "GGUF"
    private val ZIP = byteArrayOf(0x50, 0x4B, 0x03, 0x04)           // "PK\3\4"
    private val TFLITE = byteArrayOf(0x54, 0x46, 0x4C, 0x33)        // "TFL3"

    /**
     * What family of model a GGUF holds.
     *
     * ## Why a GGUF is not automatically a text model
     *
     * It used to be: `install` filed every GGUF as TEXT unless something said otherwise. But
     * stable-diffusion.cpp reads GGUF too, and a diffusion checkpoint is not a language model --
     * SD-Turbo downloaded from the store's Image tab landed in the Text list and could draw
     * nothing there, while the image slot it was meant for stayed empty. hf.co hosts both shapes
     * under one extension, so the extension cannot decide.
     *
     * ## How the family is decided
     *
     * By the names inside the file, which are the tensor names for a converted checkpoint and the
     * metadata keys for a llama.cpp model:
     *
     *  - llama.cpp writes `general.architecture`, and every text family has it.
     *  - stable-diffusion.cpp's converted checkpoints have no metadata at all and name their
     *    tensors `conditioner.*`, `model.diffusion_model.*`, `first_stage_model.*`, or the Flux
     *    `double_blocks.*` / `single_blocks.*`. SDXL and SD 1.5 both look like that.
     *
     * A GGUF with neither is UNKNOWN and the caller keeps its old answer, rather than this
     * guessing at a file it does not recognise.
     */
    enum class GgufFamily { DIFFUSION, TEXT, UNKNOWN }

    /** Tensor-name prefixes that only a diffusion checkpoint has. */
    private val DIFFUSION_MARKERS = listOf(
        "conditioner.",
        "model.diffusion_model.",
        "first_stage_model.",
        "cond_stage_model.",
        "alphas_cumprod",
        "double_blocks.",
        "single_blocks.",
        "vae.decoder",
    )

    /** How much of a GGUF is scanned for those names. They sit in the first table. */
    private val FAMILY_SCAN_BYTES = 256 * 1024

    fun ggufFamily(file: File): GgufFamily {
        if (!file.isFile || file.length() <= 0L) return GgufFamily.UNKNOWN
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val size = minOf(FAMILY_SCAN_BYTES.toLong(), file.length()).toInt()
                val bytes = ByteArray(size)
                raf.readFully(bytes)
                if (!startsWith(bytes, GGUF, 0)) return@use GgufFamily.UNKNOWN
                // Latin-1 because the body is binary: this asks whether a name is present, and
                // any byte-per-char decoding answers that without failing on invalid UTF-8.
                val text = String(bytes, Charsets.ISO_8859_1)
                val diffusion = text.contains("stable-diffusion") ||
                    DIFFUSION_MARKERS.any { text.contains(it) }
                when {
                    diffusion -> GgufFamily.DIFFUSION
                    text.contains("general.architecture") -> GgufFamily.TEXT
                    else -> GgufFamily.UNKNOWN
                }
            }
        }.getOrDefault(GgufFamily.UNKNOWN)
    }

    /**
     * Whether the window is a safetensors header: a plausible length, then a JSON object.
     *
     * The length has to describe the JSON that follows it, which is what separates a real header
     * from a file that happens to begin with small numbers. A header larger than the file is not
     * a header; neither is one longer than the spec's own practical limit for a model of this size.
     */
    private fun isSafetensors(window: ByteArray, fileLength: Long): Boolean {
        if (window.size < 10) return false
        var headerLength = 0L
        for (i in 0 until 8) {
            headerLength = headerLength or ((window[i].toLong() and 0xFF) shl (8 * i))
        }
        if (headerLength < 2 || headerLength > fileLength - 8) return false
        return window[8] == '{'.code.toByte()
    }

    /**
     * How far in to look for a ZIP's magic.
     *
     * Generous enough to survive a stray header, small enough that scanning is instant on a
     * multi-gigabyte file. A magic further in than this is not a prefix, it is a coincidence.
     */
    private const val SCAN_BYTES = 64 * 1024

    /**
     * How much leading blankness makes a file "incomplete" rather than merely odd.
     *
     * A real model never begins with a kilobyte of zeroes; a download that lost its first chunk
     * always does.
     */
    private const val BLANK_THRESHOLD = 1024

    fun detect(path: String): Detection = detect(File(path))

    fun detect(file: File): Detection {
        if (!file.isFile || file.length() <= 0L) {
            return Detection(Kind.EMPTY, 0, 0, "")
        }

        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val window = ByteArray(minOf(SCAN_BYTES.toLong(), file.length()).toInt())
                raf.readFully(window)

                val hex = window.take(4).joinToString("") { "%02X".format(it) }
                val blank = window.indexOfFirst { it != 0.toByte() }.let {
                    if (it < 0) window.size.toLong() else it.toLong()
                }

                // GGUF is checked only at zero: llama.cpp reads its header there and nowhere else,
                // so a shifted one is broken rather than relocatable.
                if (startsWith(window, GGUF, 0)) {
                    return@use Detection(Kind.GGUF, 0, blank, hex)
                }
                if (startsWith(window, TFLITE, 0)) {
                    return@use Detection(Kind.TFLITE, 0, blank, hex)
                }
                if (isSafetensors(window, file.length())) {
                    return@use Detection(Kind.SAFETENSORS, 0, blank, hex)
                }

                val zipAt = indexOf(window, ZIP)
                if (zipAt >= 0) {
                    return@use Detection(Kind.TASK_ZIP, zipAt.toLong(), blank, hex)
                }

                if (blank >= BLANK_THRESHOLD) {
                    Detection(Kind.INCOMPLETE, -1, blank, hex)
                } else {
                    Detection(Kind.UNKNOWN, -1, blank, hex)
                }
            }
        }.getOrElse {
            Detection(Kind.UNKNOWN, -1, 0, "")
        }
    }

    private fun startsWith(haystack: ByteArray, needle: ByteArray, at: Int): Boolean {
        if (at + needle.size > haystack.size) return false
        for (i in needle.indices) if (haystack[at + i] != needle[i]) return false
        return true
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        for (start in 0..haystack.size - needle.size) {
            if (startsWith(haystack, needle, start)) return start
        }
        return -1
    }
}
