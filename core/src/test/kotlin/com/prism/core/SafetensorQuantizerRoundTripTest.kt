package com.prism.core

import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject
import com.prism.launcher.quant.SafetensorQuantizer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The safetensors quantiser, on a file laid out the way real checkpoints are.
 *
 * ## Why this is written from the format rather than from a model
 *
 * The quantiser had only ever been compiled. Its header parsing, its streaming block loop and its
 * writer were exercised by nothing, and the things most likely to be wrong are exactly the things a
 * test file has to be BUILT to expose: mixed dtypes (F32, F16, BF16), a tensor whose element count is
 * not a multiple of the 32-weight block (a LayerNorm, a bias), an integer table, a dtype that must be
 * copied untouched, a header trailing-padded with spaces the way the reference writers pad it, and
 * tensor offsets that do not fall on 4-byte boundaries. A checkpoint downloaded from a hub would have
 * all of that and no label; this file is the same shapes, with the answers known.
 *
 * ## What a pass means
 *
 *  * Every tensor in the output is either quantised with its sibling `<name>.scale` tensor, or a
 *    BYTE-FOR-BYTE copy of the source -- nothing is dropped and nothing is silently rewritten.
 *  * Dequantising the output reproduces the source weights to the scheme's own rounding error: half a
 *    quantisation step, which is `blockMax / 254` for I8 and `blockMax / 14` for I4.
 *  * The header's offsets are contiguous from zero and account for the file's length exactly, so
 *    `estimateOutputBytes` is the size the file actually is.
 *  * The result is a valid safetensors container by the format's rules: an eight-byte little-endian
 *    header length, UTF-8 JSON, then the tensor bytes in the header's order.
 */
class SafetensorQuantizerRoundTripTest {

    private val root = File(System.getProperty("java.io.tmpdir"), "prism-safetensors-${System.nanoTime()}")

    /** One tensor to put in the source file: dtype, shape, and the floats it holds. */
    private class Spec(val name: String, val dtype: String, val shape: List<Int>, val values: FloatArray)

    private val block = SafetensorQuantizer.BLOCK

    @Test
    fun `a checkpoint of mixed dtypes round-trips at I8`() = roundTrip(SafetensorQuantizer.Level.I8)

    @Test
    fun `a checkpoint of mixed dtypes round-trips at I4`() = roundTrip(SafetensorQuantizer.Level.I4)

    @Test
    fun `a file with nothing quantisable is refused rather than emptied`() {
        val source = File(root, "small.safetensors")
        write(source, listOf(
            Spec("scale", "F32", listOf(8), floats(8, seed = 1)),
            Spec("norm", "F16", listOf(16), floats(16, seed = 2)),
        ))
        val destination = File(root, "small.out")
        val problem = SafetensorQuantizer.quantize(source, destination, SafetensorQuantizer.Level.I8)
        assertNotNull(problem, "a file of tensors shorter than one block should be refused")
        assertTrue(!destination.exists(), "nothing should have been written")
        root.deleteRecursively()
    }

    @Test
    fun `quantising twice produces the same bytes`() {
        val source = File(root, "twice.safetensors")
        write(source, checkpoint())
        val first = File(root, "twice-a.out")
        val second = File(root, "twice-b.out")
        assertEquals(null, SafetensorQuantizer.quantize(source, first, SafetensorQuantizer.Level.I8))
        assertEquals(null, SafetensorQuantizer.quantize(source, second, SafetensorQuantizer.Level.I8))
        assertTrue(
            first.readBytes().contentEquals(second.readBytes()),
            "two runs over one file differed, so the scheme is not deterministic",
        )
        root.deleteRecursively()
    }

    private fun roundTrip(level: SafetensorQuantizer.Level) {
        root.mkdirs()
        try {
            val source = File(root, "model-${level.name}.safetensors")
            val specs = checkpoint()
            write(source, specs)

            val destination = File(root, "quantised-${level.name}.safetensors")
            val problem = SafetensorQuantizer.quantize(source, destination, level, threads = 2)
            assertEquals(null, problem, "quantising failed: $problem")

            // The size the UI promises is the size the file is.
            assertEquals(
                SafetensorQuantizer.estimateOutputBytes(source, level),
                destination.length(),
                "the estimated output size was not the file's size",
            )

            val output = read(destination)
            assertEquals(
                level.name,
                output.metadata?.optString("prism_quantization")?.substringBefore('_'),
                "the header does not name its own scheme",
            )
            assertEquals(block.toString(), output.metadata?.optString("prism_block_size"))

            // Byte-for-byte: every copied tensor is the source's bytes, unchanged.
            for (spec in specs) {
                val entry = output.tensors[spec.name]
                assertNotNull(entry, "${spec.name} is missing from the output")
            }

            for (spec in specs) {
                val quantisable = spec.values.size % block == 0 &&
                    spec.dtype in setOf("F32", "F16", "BF16") &&
                    !(level == SafetensorQuantizer.Level.I4 && spec.shape.last() % 2 != 0)
                val entry = output.tensors.getValue(spec.name)
                if (!quantisable) {
                    assertEquals(
                        spec.dtype, entry.dtype,
                        "${spec.name} was rewritten although it cannot be quantised",
                    )
                    assertEquals(spec.shape, entry.shape, "${spec.name}'s shape changed")
                    assertTrue(
                        entry.bytes.contentEquals(sourceBytes(source, specs, spec.name)),
                        "${spec.name} was not copied byte-for-byte",
                    )
                    continue
                }

                // The weights became the level's dtype, the shape halved for I4, and a scale tensor
                // arrived beside them.
                assertEquals(level.name.let { if (level == SafetensorQuantizer.Level.I4) "U8" else "I8" }, entry.dtype)
                val expectedShape = if (level == SafetensorQuantizer.Level.I4) {
                    spec.shape.dropLast(1) + (spec.shape.last() / 2)
                } else {
                    spec.shape
                }
                assertEquals(expectedShape, entry.shape, "${spec.name}'s quantised shape")
                val scaleEntry = output.tensors[spec.name + SafetensorQuantizer.SCALE_SUFFIX]
                assertNotNull(scaleEntry, "${spec.name} has no scale tensor")
                assertEquals("F32", scaleEntry.dtype)
                assertEquals(spec.values.size / block, scaleEntry.shape.single())

                // Dequantise and compare with what went in. The error bound is the scheme's own
                // rounding: half a step, one step being blockMax/254 (I8) or blockMax/14 (I4).
                val scales = scaleEntry.floats()
                val weights = entry.dequantise(level, scales)
                assertEquals(spec.values.size, weights.size)
                // THE REFERENCE IS WHAT THE FILE HOLDS, not what the test asked for: an F16 or BF16
                // tensor stores a rounded copy, and the quantiser reads that copy, so the block peaks
                // and the rounding steps are the stored ones. Comparing against the pre-encoding floats
                // would fail by a fraction of a step on exactly those dtypes.
                val reference = decodedSource(source, spec)
                val peak = if (level == SafetensorQuantizer.Level.I4) 7f else 127f
                for (index in reference.indices) {
                    val blockStart = index - index % block
                    val blockMax = (0 until block)
                        .map { abs(reference[blockStart + it]) }
                        .maxOrNull() ?: 0f
                    val step = if (blockMax > 0f) blockMax / peak else 0f
                    val error = abs(weights[index] - reference[index])
                    assertTrue(
                        error <= step * 0.51f + 1e-6f,
                        "${spec.name}[$index]: ${reference[index]} became ${weights[index]} " +
                            "(error $error, half-step $step)",
                    )
                    if (blockMax > 0f) {
                        val expectedScale = blockMax / peak
                        assertTrue(
                            abs(scales[index / block] - expectedScale) <= max(1e-4f, expectedScale * 1e-3f),
                            "${spec.name}'s scale at block ${index / block} is ${scales[index / block]} " +
                                "for a block peak of $blockMax (expected $expectedScale)",
                        )
                    }
                }
            }

            // Contiguous offsets from zero, ending exactly at the file's length.
            var expected = 0L
            for (entry in output.entries) {
                assertEquals(expected, entry.offset, "${entry.name} starts at ${entry.offset}, not $expected")
                expected += entry.bytes.size
            }
            assertEquals(destination.length() - 8 - output.headerBytes, expected)
        } finally {
            root.deleteRecursively()
        }
    }

    // ── The source file ─────────────────────────────────────────────────────────────────────────

    /**
     * The shapes a real transformer checkpoint has, plus the awkward cases.
     *
     * The BOOL tensor of three elements is what pushes every offset after it off a 4-byte boundary,
     * which is the alignment a parse that assumes multiples of four gets wrong.
     */
    private fun checkpoint(): List<Spec> = listOf(
        Spec("model.embed_tokens.weight", "F32", listOf(64, 128), floats(64 * 128, seed = 3)),
        Spec("attention_mask", "BOOL", listOf(3), floatArrayOf(1f, 0f, 1f)),
        Spec("model.layers.0.self_attn.q_proj.weight", "F16", listOf(64, 96), floats(64 * 96, seed = 5)),
        Spec("model.layers.0.mlp.up_proj.weight", "BF16", listOf(32, 64), floats(32 * 64, seed = 7)),
        Spec("model.norm.weight", "F32", listOf(96), floats(96, seed = 9)),
        Spec("model.layers.0.input_layernorm.bias", "F32", listOf(100), floats(100, seed = 11)),
        Spec("lm_head.bias", "F32", listOf(17), floats(17, seed = 13)),
        Spec("position_ids", "I64", listOf(8, 8), FloatArray(64) { it.toFloat() }),
        Spec("rotary.inv_freq", "F32", listOf(32, 32), floats(1024, seed = 17)),
    )

    /** Deterministic values with a realistic spread: mostly small, a few large. */
    private fun floats(count: Int, seed: Int): FloatArray {
        val random = java.util.Random(seed.toLong())
        return FloatArray(count) { index ->
            val value = random.nextGaussian().toFloat() * 0.05f
            // One in a hundred values is large, which is what makes the block scale matter: an
            // outlier that a block shares with twenty-nine small weights is the whole difficulty of
            // block quantisation.
            if (index % 97 == 0) value * 40f else value
        }
    }

    private class WriteTensor(val spec: Spec, val bytes: ByteArray)

    private fun write(file: File, specs: List<Spec>) {
        val encoded = specs.map { WriteTensor(it, encode(it)) }
        val header = JSONObject().apply {
            put("__metadata__", JSONObject().apply {
                put("format", "pt")
                put("note", "written by the round-trip test")
            })
            var offset = 0L
            for (tensor in encoded) {
                put(tensor.spec.name, JSONObject().apply {
                    put("dtype", tensor.spec.dtype)
                    put("shape", JSONArray(tensor.spec.shape.map { it.toLong() }))
                    put("data_offsets", JSONArray(listOf(offset, offset + tensor.bytes.size)))
                })
                offset += tensor.bytes.size
            }
        }
        val text = StringBuilder(header.toString())
        // Real writers pad the header with spaces to an eight-byte multiple, and a reader that trims
        // nothing has to cope with trailing whitespace inside the JSON.
        while ((text.length + 8) % 8 != 0) text.append(' ')
        val headerBytes = text.toString().toByteArray(Charsets.UTF_8)
        file.parentFile?.mkdirs()
        file.outputStream().use { out ->
            out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(headerBytes.size.toLong()).array())
            out.write(headerBytes)
            encoded.forEach { out.write(it.bytes) }
        }
    }

    private fun encode(spec: Spec): ByteArray {
        val buffer = ByteBuffer.allocate(spec.values.size * bytesPer(spec.dtype)).order(ByteOrder.LITTLE_ENDIAN)
        when (spec.dtype) {
            "F32" -> spec.values.forEach { buffer.putFloat(it) }
            "F16" -> spec.values.forEach { buffer.putShort(toHalf(it)) }
            "BF16" -> spec.values.forEach { buffer.putShort((it.toRawBits() ushr 16).toShort()) }
            "I64" -> spec.values.forEach { buffer.putLong(it.toLong()) }
            "BOOL" -> spec.values.forEach { buffer.put(if (it != 0f) 1 else 0) }
            "F8_E4M3" -> spec.values.forEach { buffer.put(0) }
            else -> throw IllegalArgumentException("the test does not write ${spec.dtype}")
        }
        return buffer.array()
    }

    private fun bytesPer(dtype: String): Int = when (dtype) {
        "F64", "I64" -> 8
        "F32", "I32" -> 4
        "F16", "BF16", "I16" -> 2
        else -> 1
    }

    // ── The output file ─────────────────────────────────────────────────────────────────────────

    private class Entry(
        val name: String,
        val dtype: String,
        val shape: List<Int>,
        val offset: Long,
        val bytes: ByteArray,
    ) {
        fun floats(): FloatArray {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            return FloatArray(bytes.size / 4) { buffer.float }
        }

        /**
         * The weights back as floats: the stored integer times its block's scale.
         *
         * I8 is one signed byte per weight; I4 two nibbles per byte, the low one first (the order
         * [SafetensorQuantizer] packs in). The scale of block `i` covers elements `i*32 .. i*32+31`.
         */
        fun dequantise(level: SafetensorQuantizer.Level, scales: FloatArray): FloatArray {
            val block = 32
            return FloatArray(bytes.size * if (level == SafetensorQuantizer.Level.I4) 2 else 1) { index ->
                val stored = when (level) {
                    SafetensorQuantizer.Level.I8 -> bytes[index].toFloat()
                    SafetensorQuantizer.Level.I4 -> {
                        val byte = bytes[index / 2].toInt()
                        val nibble = if (index % 2 == 0) byte and 0x0F else (byte shr 4) and 0x0F
                        (if (nibble >= 8) nibble - 16 else nibble).toFloat()
                    }
                }
                stored * scales[index / block]
            }
        }
    }

    private class Output(
        val metadata: JSONObject?,
        val entries: List<Entry>,
        val headerBytes: Long,
    ) {
        val tensors: Map<String, Entry> get() = entries.associateBy { it.name }
    }

    /**
     * Reads a safetensors file from the format's own rules, without the production reader: a test
     * that parsed the output with the code under test would agree with itself about a broken layout.
     */
    private fun read(file: File): Output {
        val bytes = file.readBytes()
        val headerLength = ByteBuffer.wrap(bytes, 0, 8).order(ByteOrder.LITTLE_ENDIAN).long
        val headerText = String(bytes, 8, headerLength.toInt(), Charsets.UTF_8)
        val root = JSONObject(headerText)
        val dataStart = 8 + headerLength
        val entries = mutableListOf<Entry>()
        val keys = root.keys()
        while (keys.hasNext()) {
            val name = keys.next()
            if (name == "__metadata__") continue
            val entry = root.optJSONObject(name) ?: continue
            val shape = entry.optJSONArray("shape")?.let { array ->
                (0 until array.length()).map { array.optInt(it) }
            }.orEmpty()
            val offsets = entry.optJSONArray("data_offsets")
            val begin = offsets?.optLong(0) ?: 0L
            val end = offsets?.optLong(1) ?: 0L
            val slice = bytes.copyOfRange(dataStart.toInt() + begin.toInt(), dataStart.toInt() + end.toInt())
            entries += Entry(name, entry.optString("dtype"), shape, begin, slice)
        }
        return Output(root.optJSONObject("__metadata__"), entries, headerLength)
    }

    /** A source tensor's values as the file stores them, decoded back to floats. */
    private fun decodedSource(file: File, spec: Spec): FloatArray {
        val bytes = sourceBytes(file, listOf(spec), spec.name)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return when (spec.dtype) {
            "F32" -> FloatArray(spec.values.size) { buffer.float }
            "F16" -> FloatArray(spec.values.size) { halfToFloat(buffer.short.toInt() and 0xFFFF) }
            "BF16" -> FloatArray(spec.values.size) { Float.fromBits(buffer.short.toInt() shl 16) }
            else -> throw IllegalArgumentException("${spec.dtype} is not a quantisable dtype here")
        }
    }

    private fun halfToFloat(bits: Int): Float {
        val sign = bits and 0x8000
        val exponent = (bits shr 10) and 0x1F
        val mantissa = bits and 0x03FF
        if (exponent == 0) return Float.fromBits(sign shl 16) * (mantissa / 16777216f)
        if (exponent == 0x1F) {
            val infinite = sign shl 16 or 0x7F800000
            return if (mantissa == 0) Float.fromBits(infinite) else Float.fromBits(infinite or 0x400000)
        }
        return Float.fromBits((sign shl 16) or ((exponent - 15 + 127) shl 23) or (mantissa shl 13))
    }

    /** A tensor's bytes as they sit in the SOURCE file, for the copy comparison. */
    private fun sourceBytes(file: File, specs: List<Spec>, name: String): ByteArray {
        val bytes = file.readBytes()
        val headerLength = ByteBuffer.wrap(bytes, 0, 8).order(ByteOrder.LITTLE_ENDIAN).long
        val root = JSONObject(String(bytes, 8, headerLength.toInt(), Charsets.UTF_8))
        val entry = root.optJSONObject(name) ?: return ByteArray(0)
        val offsets = entry.optJSONArray("data_offsets")!!
        val begin = offsets.optLong(0).toInt()
        val end = offsets.optLong(1).toInt()
        val dataStart = (8 + headerLength).toInt()
        return bytes.copyOfRange(dataStart + begin, dataStart + end)
    }

    /** IEEE 754 binary16, rounded to nearest. */
    private fun toHalf(value: Float): Short {
        val bits = value.toRawBits()
        val sign = (bits ushr 16) and 0x8000
        var exponent = ((bits ushr 23) and 0xFF) - 127 + 15
        var mantissa = bits and 0x7FFFFF
        if (exponent <= 0) {
            if (exponent < -10) return sign.toShort()
            mantissa = (mantissa or 0x800000) ushr (1 - exponent)
            return (sign or (mantissa ushr 13)).toShort()
        }
        if (exponent >= 0x1F) return (sign or 0x7C00).toShort()
        mantissa += 0x1000
        if (mantissa and 0x800000 != 0) {
            mantissa = 0
            exponent++
            if (exponent >= 0x1F) return (sign or 0x7C00).toShort()
        }
        return (sign or (exponent shl 10) or (mantissa ushr 13)).toShort()
    }
}
