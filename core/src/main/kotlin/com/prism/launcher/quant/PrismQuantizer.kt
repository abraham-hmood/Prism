package com.prism.launcher.quant

import java.io.File

/**
 * Post-training quantisation of a local GGUF, on the device.
 *
 * ## What this actually does
 *
 * Hands the file to `llama_model_quantize`, which streams the source model tensor by tensor,
 * requantises each one and writes a new GGUF. Streaming is the reason this is possible on a phone:
 * peak memory is a few tensors, not the model, so a 7B file can be requantised on a device that
 * could never hold it twice.
 *
 * ## The low-bit levels are real ggml types, not labels
 *
 * Binary, ternary and quaternary map onto types the vendored llama.cpp already has -- `Q1_0` is one
 * bit, `TQ1_0` is ternary, `Q2_0` is two bits. Quinary had no equivalent, so `Q5_Q0` was added to
 * ggml: five levels packed three to a byte in base 5, since 5^3 = 125 fits a byte where three bits
 * per weight would waste the three unused codes. See `block_q5_q0` in `ggml-common.h`.
 *
 * That last one has a consequence worth knowing: a quinary GGUF is readable by THIS build and not by
 * upstream llama.cpp, which has never heard of the type. It is a Prism model, not a portable one.
 *
 * ## What quantising this far actually costs
 *
 * Every level below about four bits loses real accuracy, and the sub-2-bit ones (binary, ternary,
 * quinary) were designed for models TRAINED at that precision -- BitNet and its relatives. Taking an
 * ordinary model down to binary produces a file that loads, runs, and says very little worth reading.
 * That is a property of the arithmetic, not of this implementation, and it is why the UI states the
 * bits per weight next to each one rather than presenting them as interchangeable.
 */
object PrismQuantizer {

    /**
     * A quantisation the user can pick.
     *
     * [ftype] is the `llama_ftype` enum value from `llama.h` -- the numbers are the wire format of
     * the vendored library, so they are written out explicitly rather than derived from ordinals,
     * which would silently renumber if anyone reordered this list.
     */
    enum class Level(
        val label: String,
        val ftype: Int,
        /** Roughly how many bits each weight ends up costing, for the UI. */
        val bitsPerWeight: Double,
        val note: String = "",
    ) {
        Q2_K("Q2_K", 10, 2.63),
        Q3_K_S("Q3_K_S", 11, 3.44),
        Q4_0("Q4_0", 2, 4.5),
        Q4_K_S("Q4_K_S", 14, 4.58),
        Q4_K_M("Q4_K_M", 15, 4.85),
        Q5_K_M("Q5_K_M", 17, 5.69),
        Q6_K("Q6_K", 18, 6.56),
        Q8_0("Q8_0", 7, 8.5),

        // Below here the levels are experimental by nature. Each note says so in the row itself,
        // because a list that presented 1-bit next to Q8_0 with no comment would be misleading.
        BQ(
            "BQ · Binary", 40, 1.13,
            "2 levels. Built for models trained at this precision; expect heavy quality loss otherwise.",
        ),
        TQ(
            "TQ · Ternary", 36, 1.69,
            "3 levels {-1, 0, +1}. BitNet-style; expect heavy quality loss on ordinary models.",
        ),
        QQ(
            "QQ · Quaternary", 41, 2.25,
            "4 levels {-1, 0, +1, +2}.",
        ),
        QQ5(
            "5QQ · Quinary", 42, 3.0,
            "5 levels {-2..+2}, packed 3 per byte. Prism-only format — upstream llama.cpp cannot read it.",
        ),
        ;

        /** What gets appended to the model's name once it is quantised. */
        val fileSuffix: String get() = label.substringBefore(' ').trim()
    }

    @Volatile
    private var loaded = false

    private fun ensureLoaded(): Boolean {
        if (loaded) return true
        return try {
            System.loadLibrary("gguf_bridge")
            loaded = true
            true
        } catch (t: Throwable) {
            com.prism.core.PrismPlatform.log.warn(
                "Prism/quant", "gguf_bridge unavailable (${t.javaClass.simpleName}); quantisation is off"
            )
            false
        }
    }

    fun isAvailable(): Boolean = ensureLoaded()

    /**
     * How large the result will be, roughly.
     *
     * Used only to turn a growing output file into a percentage, so an estimate is the right kind of
     * answer -- and it has to be derived from the SOURCE's bits per weight, not from its byte count
     * alone: requantising a Q4_K_M to Q2_K roughly halves it, while the same target applied to an F16
     * cuts it by six. Guessing from size alone would show a progress bar that finishes at 40% or runs
     * past 100%.
     */
    fun estimateOutputBytes(source: File, level: Level): Long {
        val sourceBits = sourceBitsPerWeight(source.name)
        if (sourceBits <= 0.0) return source.length()
        val ratio = level.bitsPerWeight / sourceBits
        return (source.length() * ratio).toLong().coerceAtLeast(1L)
    }

    /**
     * Bits per weight of the model already on disk, read off its filename.
     *
     * The filename is where this information actually lives for a downloaded GGUF -- they are named
     * `...Q4_K_M.gguf` by convention, and every model store in the app relies on that already. Reading
     * the GGUF header would be more rigorous, but this figure only feeds a progress estimate, and an
     * unrecognised name falls back to treating the file as F16.
     */
    private fun sourceBitsPerWeight(fileName: String): Double {
        val upper = fileName.uppercase()
        // Longest labels first: "Q4_K_M" contains "Q4_K", which contains "Q4_0"'s prefix.
        val known = listOf(
            "Q8_0" to 8.5, "Q6_K" to 6.56, "Q5_K_M" to 5.69, "Q5_K_S" to 5.53,
            "Q5_1" to 6.0, "Q5_0" to 5.5, "Q4_K_M" to 4.85, "Q4_K_S" to 4.58,
            "Q4_1" to 5.0, "Q4_0" to 4.5, "Q3_K_L" to 3.9, "Q3_K_M" to 3.74,
            "Q3_K_S" to 3.44, "Q2_K" to 2.63, "IQ4_XS" to 4.25, "IQ3_S" to 3.44,
            "IQ2_M" to 2.7, "TQ2_0" to 2.06, "TQ1_0" to 1.69,
            "BF16" to 16.0, "F16" to 16.0, "FP16" to 16.0, "F32" to 32.0,
        )
        for ((name, bits) in known) {
            if (upper.contains(name)) return bits
        }
        return 16.0
    }

    /**
     * Quantises [source] into [destination]. Blocking, and slow -- minutes for a small model.
     *
     * @return null on success, or a message describing the failure.
     */
    fun quantize(source: File, destination: File, level: Level, threads: Int): String? {
        if (!ensureLoaded()) return "The native quantiser is not available on this device"
        if (!source.isFile) return "${source.name} is not a file"

        destination.parentFile?.mkdirs()
        // A leftover from an abandoned run would otherwise be appended to or half-overwritten, and
        // the result would be a file that looks finished and cannot be opened.
        if (destination.exists()) destination.delete()

        val rc = try {
            nativeQuantize(source.absolutePath, destination.absolutePath, level.ftype, threads)
        } catch (t: Throwable) {
            return t.message ?: t.javaClass.simpleName
        }

        if (rc != 0) {
            // Nothing usable was produced, and leaving it would offer the user a model that can only
            // fail to load.
            runCatching { if (destination.exists()) destination.delete() }
            return "The quantiser returned error $rc"
        }
        if (!destination.isFile || destination.length() == 0L) {
            return "No output was written"
        }
        return null
    }

    private external fun nativeQuantize(
        inPath: String,
        outPath: String,
        ftype: Int,
        threads: Int,
    ): Int
}
