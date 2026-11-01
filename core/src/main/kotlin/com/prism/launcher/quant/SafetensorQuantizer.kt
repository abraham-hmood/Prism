package com.prism.launcher.quant

import com.prism.core.PrismPlatform
import com.prism.core.json.JSONArray
import com.prism.core.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Post-training quantisation of a safetensors checkpoint.
 *
 * ## Why this is not the GGUF quantiser
 *
 * [PrismQuantizer] hands a file to `llama_model_quantize`, which is the right answer for a GGUF and
 * no answer at all for anything else: a safetensors file is a different container with a different
 * layout, and llama.cpp will not open one. The model tab offered only GGUF because that was all the
 * native quantiser could read, so half the models Prism imports -- every diffusion checkpoint, every
 * TTS checkpoint, every trainer export -- had no quantisation at all.
 *
 * This one is pure Kotlin and streams the file, so it works wherever the app runs, with the same
 * property the GGUF path has and for the same reason: PEAK MEMORY IS ONE CHUNK, NOT THE MODEL. A
 * 6 GB checkpoint is quantised on a phone without ever holding more than a few megabytes.
 *
 * ## The scheme, exactly
 *
 *   * Weights are grouped into blocks of [BLOCK] consecutive elements of the tensor's flattened
 *     data, and each block gets ONE fp32 scale -- `max|x| / 127` (or `/ 7` for I4), stored beside
 *     the weights in a sibling `<name>.scale` tensor.
 *   * `I8` writes one signed byte per weight, in the source's own shape, so the tensor stays
 *     readable as the same shape with a different dtype.
 *   * `I4` packs two signed nibbles per byte along the last dimension, so that dimension halves:
 *     a `[4096, 4096]` tensor becomes `[4096, 2048]` of U8 plus `[4096, 128]` of scales.
 *   * EVERYTHING THAT CANNOT BE QUANTISED IS COPIED, NOT DROPPED: a tensor whose element count is
 *     not a multiple of [BLOCK] (an odd-length embedding row, a bias vector), one whose last
 *     dimension is odd for I4, and integer/bool/FP8 tensors are written through unchanged with their
 *     original dtype and shape. A quantised checkpoint that quietly lost its norms and biases would
 *     load and produce nonsense; the header says how many tensors went each way.
 *   * The header's `__metadata__` records the scheme (`prism_quantization`), the block size, the
 *     scale dtype, and the counts. That is what makes the result self-describing rather than
 *     something only the code that wrote it can interpret.
 *
 * This is PRISM'S OWN LAYOUT, and it says so in the file: upstream tools read a plain safetensors
 * file, and the scales live in extra tensors they would see as ordinary weights. It is a Prism
 * artifact for Prism's own loaders and trainers, which is the same thing [PrismQuantizer]'s quinary
 * level is for the GGUF side.
 */
object SafetensorQuantizer {

    /**
     * A quantisation this can write.
     *
     * [bitsPerWeight] counts the scales: 8 bits of weight plus 4 bytes of scale per 32 weights is
     * 8 + 1 = 9 bits... quoted as 8.5 and 4.5 because the estimate the UI shows is the ratio the
     * file's size actually changes by, and the scales are not the whole of it. The numbers are
     * deliberately conservative rather than flattering.
     */
    enum class Level(
        val label: String,
        val bitsPerWeight: Double,
        val note: String = "",
    ) {
        I8(
            "I8 · 8-bit blocks", 8.5,
            "One fp32 scale per 32 weights. The safe general-purpose level.",
        ),
        I4(
            "I4 · 4-bit blocks", 4.5,
            "Two weights per byte with one scale per 32. About half of I8, with real quality loss.",
        ),
        ;

        /** What gets appended to the model's name once it is quantised. */
        val fileSuffix: String get() = label.substringBefore(' ').trim()

        /** The largest magnitude a quantised weight can carry. */
        internal val peak: Int get() = if (this == I4) 7 else 127

        /** Whether two weights share one output byte. */
        internal val packed: Boolean get() = this == I4

        /** The dtype of the quantised weights. */
        internal val weightDtype: String get() = if (packed) "U8" else "I8"
    }

    /** Weights per fp32 scale. 32 matches what ggml's k-quants and bitsandbytes both use. */
    const val BLOCK = 32

    /** What a scale tensor's name is suffixed with. */
    const val SCALE_SUFFIX = ".scale"

    private const val PREFIX_BYTES = 8
    private const val COPY_CHUNK_BYTES = 4L * 1024 * 1024
    private const val PART_ELEMENTS_DEFAULT = 1 shl 20

    /** Bytes per element for every dtype this can convert, or null when it cannot be touched. */
    private val DTYPE_BYTES: Map<String, Int> = mapOf(
        "F64" to 8, "F32" to 4, "F16" to 2, "BF16" to 2,
        "I64" to 8, "I32" to 4, "I16" to 2, "I8" to 1,
        "U64" to 8, "U32" to 4, "U16" to 2, "U8" to 1,
        "BOOL" to 1,
    )

    /**
     * The dtypes this converts, which are the floating-point ones.
     *
     * INTEGERS ARE NOT WEIGHTS. An `I64` or `I32` tensor in a checkpoint is positional ids, a token
     * type table, a cumulative offset -- indices, whose exact values are part of what the file means.
     * Block-quantising them scales each block by its own peak and rounds to 8 bits, so `position_ids`
     * comes back as approximate fractions of themselves: not a smaller copy of the data but a different
     * tensor. They are copied through unchanged, which is the contract this file's header states.
     *
     * FP8 is a real 1-byte float format, but dequantising it here would mean implementing both
     * variants' bias and exponent handling to gain nothing: a file that already holds FP8 weights is
     * already small, and a wrong conversion is worse than an untouched tensor.
     */
    private val FLOAT_DTYPES = setOf("F64", "F32", "F16", "BF16")

    /**
     * True when [file] is a safetensors container: an eight-byte little-endian header length
     * followed by a JSON header that names at least one tensor.
     *
     * Decided by the file's CONTENT rather than its extension, for the reason the GGUF check is:
     * imports keep whatever name they arrived with, and plenty of checkpoints are stored as
     * `model.safetensors.checkpoint` or with no extension at all.
     */
    fun isSafetensorFile(file: File): Boolean =
        runCatching { readHeader(file).tensors.isNotEmpty() }.getOrDefault(false)

    /** How large the result will be. Exact, because the header already says every tensor's shape. */
    fun estimateOutputBytes(source: File, level: Level): Long {
        val header = runCatching { readHeader(source) }.getOrNull() ?: return source.length()
        val planned = plan(header, level)
        val headerBytes = assembleHeader(header, planned, level)
        return PREFIX_BYTES + headerBytes.size + planned.sumOf { it.bytes }
    }

    /**
     * Quantises [source] into [destination]. Blocking, and I/O bound -- the read is what takes the
     * time. [threads] bounds how many blocks are converted at once; each worker holds one part.
     *
     * @return null on success, or a message describing the failure.
     */
    fun quantize(source: File, destination: File, level: Level, threads: Int = 2): String? {
        if (!source.isFile) return "${source.name} is not a file"
        val header = try {
            readHeader(source)
        } catch (t: Throwable) {
            return "This is not a safetensors file (${t.message ?: t.javaClass.simpleName})"
        }
        if (header.tensors.isEmpty()) return "The header names no tensors"

        val planned = plan(header, level)
        val quantisable = planned.filter { it.level != null && it.scaleOf == null }
        if (quantisable.isEmpty()) {
            return "Nothing in this file can be quantised: every tensor is shorter than one " +
                "$BLOCK-weight block, has an odd last dimension for I4, or is a dtype that is copied as-is."
        }
        val scales = planned.associateBy { it.scaleOf }
        val headerBytes = assembleHeader(header, planned, level)

        destination.parentFile?.mkdirs()
        // A leftover from an abandoned run would be written over in pieces and read as a finished
        // file, exactly as the GGUF path's comment says.
        if (destination.exists()) destination.delete()

        val workers = threads.coerceIn(1, 8)
        val pool = Executors.newFixedThreadPool(workers)
        try {
            FileChannel.open(source.toPath(), StandardOpenOption.READ).use { input ->
                FileChannel.open(
                    destination.toPath(),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING,
                ).use { output ->
                    val prefix = ByteBuffer.allocate(PREFIX_BYTES).order(ByteOrder.LITTLE_ENDIAN)
                    prefix.putLong(0, headerBytes.size.toLong())
                    writeFully(output, prefix, 0)
                    writeFully(output, ByteBuffer.wrap(headerBytes), PREFIX_BYTES.toLong())
                    val dataStart = PREFIX_BYTES + headerBytes.size.toLong()

                    for (entry in planned) {
                        // Scales are written by their weights' own pass, so they are never visited.
                        if (entry.scaleOf != null) continue
                        val tensor = entry.source
                            ?: return "The plan named a weight tensor with no source"
                        if (entry.level == null) {
                            copyTensor(input, output, dataStart + entry.offset, tensor.begin, entry.bytes)
                            continue
                        }
                        val scaleEntry = scales[tensor.name]
                            ?: return "The plan has no scale tensor for ${tensor.name}"
                        quantizeTensor(
                            input, output, tensor, entry, scaleEntry, level, dataStart, pool, workers,
                        )
                    }
                }
            }
        } catch (t: Throwable) {
            runCatching { if (destination.exists()) destination.delete() }
            PrismPlatform.log.error("Prism/quant", "Quantising ${source.name} failed", t)
            return t.message ?: t.javaClass.simpleName
        } finally {
            pool.shutdown()
        }

        if (!destination.isFile || destination.length() == 0L) return "No output was written"
        PrismPlatform.log.info(
            "Prism/quant",
            "Quantised ${source.name} to ${level.fileSuffix}: ${quantisable.size} tensor(s) " +
                "quantised, ${planned.count { it.level == null }} copied as-is, " +
                "${destination.length() / (1024 * 1024)} MB",
        )
        return null
    }

    // ── The container ───────────────────────────────────────────────────────────────────────────

    /** One tensor as the source header describes it. */
    private data class Tensor(
        val name: String,
        val dtype: String,
        val shape: List<Int>,
        val begin: Long,
        val end: Long,
    ) {
        val elements: Long get() = shape.fold(1L) { acc, dim -> acc * dim }

        /** False for a scalar-shaped entry, which no block quantiser can touch. */
        val hasElements: Boolean get() = shape.isNotEmpty() && elements > 0
    }

    private class Header(val tensors: List<Tensor>, val metadata: JSONObject?)

    private fun readHeader(file: File): Header {
        require(file.isFile) { "${file.name} is not a file" }
        FileChannel.open(file.toPath(), StandardOpenOption.READ).use { channel ->
            val prefix = ByteBuffer.allocate(PREFIX_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            readFully(channel, prefix, 0)
            val length = prefix.getLong(0)
            require(length > 0) { "the header length is $length" }
            require(length < INT_CEILING) { "the header claims to be ${length / (1024 * 1024)} MB" }
            val headerBytes = ByteBuffer.allocate(length.toInt())
            readFully(channel, headerBytes, PREFIX_BYTES.toLong())
            val root = JSONObject(String(headerBytes.array(), Charsets.UTF_8))
            val metadata = root.optJSONObject("__metadata__")
            // A tensor's data_offsets are relative to the first byte AFTER the header, and every
            // read below is a positional one against the file itself -- so the base is folded in
            // here, once, rather than remembered by each caller.
            val dataStart = PREFIX_BYTES + length
            val tensors = mutableListOf<Tensor>()
            val keys = root.keys()
            while (keys.hasNext()) {
                val name = keys.next()
                if (name == "__metadata__") continue
                val entry = root.optJSONObject(name) ?: continue
                val dtype = entry.optString("dtype")
                val shapeArray = entry.optJSONArray("shape")
                val shape = if (shapeArray == null) {
                    emptyList()
                } else {
                    (0 until shapeArray.length()).map { shapeArray.optInt(it) }
                }
                val offsets = entry.optJSONArray("data_offsets") ?: continue
                val begin = offsets.optLong(0) + dataStart
                val end = offsets.optLong(1) + dataStart
                if (dtype.isBlank() || end < begin) continue
                tensors += Tensor(name, dtype, shape, begin, end)
            }
            return Header(tensors, metadata)
        }
    }

    /** A tensor as the OUTPUT header will describe it, with the byte length it will occupy. */
    private class Planned(
        val name: String,
        val dtype: String,
        val shape: List<Int>,
        val bytes: Long,
        /** Where the bytes come from; null for a scale, which is computed. */
        val source: Tensor?,
        /** The level when this entry holds quantised weights, null when it is copied. */
        val level: Level?,
        /** For a scale entry, the weights tensor it belongs to. */
        val scaleOf: String? = null,
        var offset: Long = 0L,
    )

    private fun quantisable(tensor: Tensor, level: Level): Boolean {
        if (!tensor.hasElements) return false
        // Floats only: integer tables are indices, and FP8 is refused rather than mis-converted.
        if (tensor.dtype !in FLOAT_DTYPES) return false
        if (DTYPE_BYTES[tensor.dtype] == null) return false
        if (tensor.elements % BLOCK != 0L) return false
        // Two nibbles per byte along the last dimension: an odd one cannot be halved without
        // changing what the tensor means.
        if (level.packed && tensor.shape.last() % 2 != 0) return false
        return true
    }

    private fun plan(header: Header, level: Level): List<Planned> {
        val out = mutableListOf<Planned>()
        for (tensor in header.tensors) {
            if (!quantisable(tensor, level)) {
                out += Planned(tensor.name, tensor.dtype, tensor.shape, tensor.end - tensor.begin, tensor, null)
                continue
            }
            val weightBytes = if (level.packed) tensor.elements / 2 else tensor.elements
            val weightShape = if (level.packed) {
                tensor.shape.dropLast(1) + (tensor.shape.last() / 2)
            } else {
                tensor.shape
            }
            out += Planned(tensor.name, level.weightDtype, weightShape, weightBytes, tensor, level)
            val blocks = tensor.elements / BLOCK
            out += Planned(
                tensor.name + SCALE_SUFFIX, "F32", listOf(blocks.toInt()), blocks * 4, null, level,
                scaleOf = tensor.name,
            )
        }
        return out
    }

    private fun assembleHeader(header: Header, planned: List<Planned>, level: Level): ByteArray {
        val root = JSONObject()

        // THE METADATA COMES FIRST. safetensors' own writers put `__metadata__` at the head of the
        // object, and a reader that stops at the first non-metadata key is not hypothetical.
        val metadata = JSONObject()
        header.metadata?.let { source ->
            val keys = source.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                if (key.startsWith("prism_")) continue
                // Values must be strings per the format; anything else in the source metadata is
                // dropped rather than written in a shape a reader would reject.
                val value = source.opt(key)
                if (value is String) metadata.put(key, value)
            }
        }
        metadata.put("prism_quantization", schemeName(level))
        metadata.put("prism_block_size", BLOCK.toString())
        metadata.put("prism_scale_dtype", "F32")
        metadata.put("prism_scale_tensors", "one <tensor>$SCALE_SUFFIX per quantised tensor, " +
            "one f32 per $BLOCK consecutive elements of the flattened tensor")
        metadata.put("prism_weight_dtype", level.weightDtype)
        metadata.put("prism_quantised_tensors", planned.count { it.level != null && it.scaleOf == null }.toString())
        metadata.put("prism_copied_tensors", planned.count { it.level == null }.toString())
        root.put("__metadata__", metadata)

        var offset = 0L
        for (entry in planned) {
            val dataOffsets = JSONArray(listOf(offset, offset + entry.bytes))
            root.put(
                entry.name,
                JSONObject().apply {
                    put("dtype", entry.dtype)
                    put("shape", JSONArray(entry.shape.map { dim -> dim.toLong() }))
                    put("data_offsets", dataOffsets)
                },
            )
            entry.offset = offset
            offset += entry.bytes
        }
        return root.toString().toByteArray(Charsets.UTF_8)
    }

    private fun schemeName(level: Level): String =
        if (level.packed) "I4_BLOCK_$BLOCK" else "I8_BLOCK_$BLOCK"

    // ── The conversion ──────────────────────────────────────────────────────────────────────────

    private fun quantizeTensor(
        input: FileChannel,
        output: FileChannel,
        tensor: Tensor,
        weights: Planned,
        scales: Planned,
        level: Level,
        dataStart: Long,
        pool: ExecutorService,
        workers: Int,
    ) {
        val elementBytes = DTYPE_BYTES.getValue(tensor.dtype)
        // A part is a whole number of blocks, so no block is ever split between two workers -- the
        // scales would otherwise be computed from half a block each.
        val partElements = (PART_ELEMENTS_DEFAULT / workers).coerceAtLeast(BLOCK).let { it - (it % BLOCK) }
        val weightOffset = dataStart + weights.offset
        val scaleOffset = dataStart + scales.offset

        var element = 0L
        while (element < tensor.elements) {
            val pending = mutableListOf<java.util.concurrent.Future<*>>()
            var submitted = 0
            while (submitted < workers && element < tensor.elements) {
                val count = min(partElements.toLong(), tensor.elements - element).toInt()
                val start = element
                pending += pool.submit {
                    convertPart(
                        input, output, tensor, start, count, elementBytes, level,
                        weightOffset, scaleOffset,
                    )
                }
                element += count
                submitted++
            }
            // Waited on before the next batch: unbounded submission would hold every part's buffers
            // at once, which is the memory this whole design exists to avoid.
            pending.forEach { it.get() }
        }
    }

    private fun convertPart(
        input: FileChannel,
        output: FileChannel,
        tensor: Tensor,
        start: Long,
        count: Int,
        elementBytes: Int,
        level: Level,
        weightOffset: Long,
        scaleOffset: Long,
    ) {
        val bytes = ByteBuffer.allocate(count * elementBytes).order(ByteOrder.LITTLE_ENDIAN)
        readFully(input, bytes, tensor.begin + start * elementBytes)
        val values = toFloats(bytes.array(), tensor.dtype, count)

        val blocks = count / BLOCK
        val scaleBytes = ByteBuffer.allocate(blocks * 4).order(ByteOrder.LITTLE_ENDIAN)
        val quantised = ByteArray(if (level.packed) count / 2 else count)
        val peak = level.peak.toFloat()

        for (block in 0 until blocks) {
            val base = block * BLOCK
            var largest = 0f
            for (i in 0 until BLOCK) {
                val value = values[base + i]
                if (value.isFinite()) {
                    val magnitude = abs(value)
                    if (magnitude > largest) largest = magnitude
                }
            }
            // A block of zeros (a padding row, an unused embedding) would otherwise divide by zero.
            val scale = if (largest > 0f) largest / peak else 1f
            scaleBytes.putFloat(scale)
            val inverse = 1f / scale
            for (i in 0 until BLOCK) {
                val value = values[base + i]
                val rounded = if (value.isFinite()) (value * inverse).roundToInt() else 0
                val quantisedValue = rounded.coerceIn(-level.peak, level.peak)
                if (level.packed) {
                    val index = base + i
                    val byteIndex = index / 2
                    val nibble = quantisedValue and 0xF
                    quantised[byteIndex] = if (index % 2 == 0) {
                        ((quantised[byteIndex].toInt() and 0xF0) or nibble).toByte()
                    } else {
                        ((quantised[byteIndex].toInt() and 0x0F) or (nibble shl 4)).toByte()
                    }
                } else {
                    quantised[base + i] = quantisedValue.toByte()
                }
            }
        }

        // Positional writes, so two workers writing different parts of one tensor cannot race the
        // file pointer -- and the scale tensor, which was planned as a sibling entry, lands in its
        // own region of the file.
        val valueByteOffset = if (level.packed) start / 2 else start
        writeFully(output, ByteBuffer.wrap(quantised), weightOffset + valueByteOffset)
        writeFully(output, scaleBytes, scaleOffset + (start / BLOCK) * 4)
    }

    private fun copyTensor(
        input: FileChannel,
        output: FileChannel,
        destinationOffset: Long,
        sourceOffset: Long,
        bytes: Long,
    ) {
        var done = 0L
        while (done < bytes) {
            val size = min(COPY_CHUNK_BYTES, bytes - done).toInt()
            val buffer = ByteBuffer.allocate(size)
            readFully(input, buffer, sourceOffset + done)
            writeFully(output, buffer, destinationOffset + done)
            done += size
        }
    }

    // ── The dtypes ──────────────────────────────────────────────────────────────────────────────

    /**
     * [bytes] as [count] floats.
     *
     * The integer dtypes are converted rather than refused: a quantiser pointed at a checkpoint with
     * `I64` positional tables should leave them alone, which the caller already does, and one that
     * finds them mixed into a tensor it can quantise should read them correctly rather than emit
     * noise.
     */
    internal fun toFloats(bytes: ByteArray, dtype: String, count: Int): FloatArray {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val out = FloatArray(count)
        when (dtype) {
            "F32" -> for (i in 0 until count) out[i] = buffer.float
            "F64" -> for (i in 0 until count) out[i] = buffer.double.toFloat()
            "F16" -> for (i in 0 until count) out[i] = halfToFloat(buffer.short.toInt() and 0xFFFF)
            "BF16" -> for (i in 0 until count) out[i] = Float.fromBits(buffer.short.toInt() shl 16)
            "I64", "U64" -> for (i in 0 until count) out[i] = buffer.long.toFloat()
            "I32", "U32" -> for (i in 0 until count) out[i] = buffer.int.toFloat()
            "I16", "U16" -> for (i in 0 until count) out[i] = buffer.short.toFloat()
            "I8" -> for (i in 0 until count) out[i] = buffer.get().toFloat()
            "U8" -> for (i in 0 until count) out[i] = (buffer.get().toInt() and 0xFF).toFloat()
            "BOOL" -> for (i in 0 until count) out[i] = if (buffer.get().toInt() != 0) 1f else 0f
            else -> throw IllegalArgumentException("$dtype cannot be converted to floats")
        }
        return out
    }

    /** IEEE 754 binary16 to float, exponent and mantissa handled explicitly. */
    private fun halfToFloat(bits: Int): Float {
        val sign = bits and 0x8000
        val exponent = (bits shr 10) and 0x1F
        val mantissa = bits and 0x03FF
        if (exponent == 0) {
            // Subnormal: value = mantissa / 2^24. Zero falls out of this as 0.
            return Float.fromBits(sign shl 16) * (mantissa / 16777216f)
        }
        if (exponent == 0x1F) {
            val infinite = sign shl 16 or 0x7F800000
            return if (mantissa == 0) Float.fromBits(infinite) else Float.fromBits(infinite or 0x400000)
        }
        return Float.fromBits(
            (sign shl 16) or ((exponent - 15 + 127) shl 23) or (mantissa shl 13),
        )
    }

    // ── Positional I/O ──────────────────────────────────────────────────────────────────────────

    private fun readFully(channel: FileChannel, buffer: ByteBuffer, position: Long) {
        var at = position
        while (buffer.hasRemaining()) {
            val read = channel.read(buffer, at)
            if (read < 0) throw IllegalStateException("The file ended inside a tensor")
            at += read
        }
        buffer.flip()
    }

    /**
     * Writes the WHOLE of [buffer] at [position].
     *
     * The rewind is the point. A buffer that was filled with relative `put`s -- which is how every
     * block of scales and every quantised block is built -- ends with its position at its capacity and
     * NOTHING remaining, so writing from the current position writes nothing at all. That is not a
     * hypothesis: before the round-trip test existed, every `<tensor>.scale` region in a quantised file
     * was a hole of zeros, so the weights decoded to zero and the file was silently unusable while the
     * run reported success. Every caller here fills its buffer completely, so rewinding to zero and
     * writing to the limit is exactly "the whole buffer".
     */
    private fun writeFully(channel: FileChannel, buffer: ByteBuffer, position: Long) {
        var at = position
        val view = buffer.duplicate()
        view.rewind()
        while (view.hasRemaining()) {
            at += channel.write(view, at)
        }
    }

    private const val INT_CEILING = (1L shl 31) - 1L
}
