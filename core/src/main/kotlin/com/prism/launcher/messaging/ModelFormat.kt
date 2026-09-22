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
        val isUsable: Boolean get() = kind == Kind.GGUF || kind == Kind.TASK_ZIP

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

            Kind.UNKNOWN ->
                "${file.name} is not a format Prism can run (header $headerHex). Prism reads GGUF " +
                    "for llama.cpp and `.task` bundles for MediaPipe."

            else -> ""
        }
    }

    private val GGUF = byteArrayOf(0x47, 0x47, 0x55, 0x46)          // "GGUF"
    private val ZIP = byteArrayOf(0x50, 0x4B, 0x03, 0x04)           // "PK\3\4"
    private val TFLITE = byteArrayOf(0x54, 0x46, 0x4C, 0x33)        // "TFL3"

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
