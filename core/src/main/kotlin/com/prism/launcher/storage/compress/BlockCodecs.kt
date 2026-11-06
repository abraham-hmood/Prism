package com.prism.launcher.storage.compress

import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.apache.commons.compress.compressors.lz4.BlockLZ4CompressorInputStream
import org.apache.commons.compress.compressors.lz4.BlockLZ4CompressorOutputStream
import org.apache.commons.compress.compressors.lz4.FramedLZ4CompressorInputStream
import org.apache.commons.compress.compressors.lz4.FramedLZ4CompressorOutputStream
import org.apache.commons.compress.compressors.snappy.FramedSnappyCompressorInputStream
import org.apache.commons.compress.compressors.snappy.FramedSnappyCompressorOutputStream
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.LZMAInputStream
import org.tukaani.xz.LZMAOutputStream
import org.tukaani.xz.XZInputStream
import org.tukaani.xz.XZOutputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

/**
 * One way of compressing a file's compression units (see [CompressedFile]).
 *
 * [encode] compresses one unit; [decode] restores it, given how long it was. A unit is
 * [unitSize] bytes -- 64 KB, NTFS's compression unit, unless the codec needs more context.
 */
class Codec(
    val id: String,
    val label: String,
    val unitSize: Int = CompressedFile.DEFAULT_UNIT,
    val encode: (data: ByteArray, offset: Int, length: Int) -> ByteArray,
    val decode: (stored: ByteArray, length: Int) -> ByteArray,
    /** Why this codec cannot be used right now, or null. */
    val unavailable: () -> String? = { null },
)

/** The codecs Prism stores compressed files with: LZNT1 first, as NTFS does. */
object BlockCodecs {

    private fun encoded(wrap: (OutputStream) -> OutputStream): (ByteArray, Int, Int) -> ByteArray = { data, offset, length ->
        val out = ByteArrayOutputStream(length / 2 + 64)
        wrap(out).use { it.write(data, offset, length) }
        out.toByteArray()
    }

    private fun decoded(wrap: (InputStream) -> InputStream): (ByteArray, Int) -> ByteArray = { stored, length ->
        wrap(stored.inputStream()).use { input -> readExactly(input, length) }
    }

    internal fun readExactly(input: InputStream, length: Int): ByteArray {
        val out = ByteArray(length)
        var at = 0
        while (at < length) {
            val read = input.read(out, at, length - at)
            if (read < 0) throw EOFException("A compressed unit ended after $at of $length bytes")
            at += read
        }
        return out
    }

    private fun deflate(level: Int, strategy: Int = Deflater.DEFAULT_STRATEGY, wrapped: Boolean = false) = encoded { out ->
        val deflater = Deflater(level, !wrapped).apply { setStrategy(strategy) }
        object : DeflaterOutputStream(out, deflater, 16 * 1024) {
            override fun close() {
                super.close()
                deflater.end()
            }
        }
    }

    private val inflate: (ByteArray, Int) -> ByteArray = { stored, length ->
        val inflater = Inflater(true)
        try {
            inflater.setInput(stored)
            val out = ByteArray(length)
            var at = 0
            while (at < length) {
                val n = inflater.inflate(out, at, length - at)
                if (n == 0 && (inflater.finished() || inflater.needsInput())) break
                at += n
            }
            if (at != length) throw EOFException("A deflated unit ended after $at of $length bytes")
            out
        } finally {
            inflater.end()
        }
    }

    /** LZMA settings sized to one unit: a dictionary bigger than the data only costs memory. */
    private fun lzmaOptions(preset: Int, unit: Int, exhaustive: Boolean = false) = LZMA2Options(preset).apply {
        dictSize = maxOf(LZMA2Options.DICT_SIZE_MIN, Integer.highestOneBit(unit - 1) shl 1)
        if (exhaustive) {
            niceLen = LZMA2Options.NICE_LEN_MAX
            depthLimit = 512
        }
    }

    private fun xz(preset: Int, exhaustive: Boolean = false) = encoded { XZOutputStream(it, lzmaOptions(preset, CompressedFile.DEFAULT_UNIT, exhaustive)) }

    /** PackBits run-length coding: the best of all for flat data, and nearly free. */
    private val packBits: (ByteArray, Int, Int) -> ByteArray = { data, offset, length ->
        val output = ByteArrayOutputStream(length + length / 64 + 8)
        var i = offset
        val end = offset + length
        while (i < end) {
            var run = 1
            while (i + run < end && run < 128 && data[i + run] == data[i]) run++
            if (run > 1) {
                output.write(1 - run)
                output.write(data[i].toInt())
                i += run
            } else {
                var literal = 1
                while (i + literal < end && literal < 128 &&
                    (i + literal + 1 >= end || data[i + literal] != data[i + literal + 1])
                ) literal++
                output.write(literal - 1)
                output.write(data, i, literal)
                i += literal
            }
        }
        output.toByteArray()
    }

    private val unpackBits: (ByteArray, Int) -> ByteArray = { stored, length ->
        val out = ByteArray(length)
        var sp = 0
        var op = 0
        while (sp < stored.size && op < length) {
            val signed = stored[sp++].toInt()
            if (signed >= 0) {
                val count = minOf(signed + 1, length - op, stored.size - sp)
                System.arraycopy(stored, sp, out, op, count)
                sp += count
                op += count
            } else if (signed != -128) {
                val value = stored[sp++]
                repeat(minOf(1 - signed, length - op)) { out[op++] = value }
            }
        }
        if (op != length) throw EOFException("A run-length unit ended after $op of $length bytes")
        out
    }

    /** Every codec that does not need a model. Twenty-three of them; every one round-trips. */
    val standard: List<Codec> = listOf(
        Codec("lznt1", "LZNT1 -- the NTFS algorithm (Windows' default)", encode = { d, o, l -> Lznt1.compress(d, o, l) }, decode = { s, l -> Lznt1.decompress(s, l) }),
        Codec("lz4-block", "LZ4 (block) -- fastest", encode = encoded { BlockLZ4CompressorOutputStream(it) }, decode = decoded { BlockLZ4CompressorInputStream(it) }),
        Codec("lz4-frame", "LZ4 (frame)", encode = encoded { FramedLZ4CompressorOutputStream(it) }, decode = decoded { FramedLZ4CompressorInputStream(it) }),
        Codec("snappy", "Snappy", encode = encoded { FramedSnappyCompressorOutputStream(it) }, decode = decoded { FramedSnappyCompressorInputStream(it) }),
        Codec("rle", "Run-length encoding (PackBits)", encode = packBits, decode = unpackBits),
    ) + (1..9).map { level ->
        val name = when (level) {
            1 -> "fastest"
            6 -> "balanced"
            9 -> "smallest"
            else -> "level $level"
        }
        Codec("deflate-$level", "Deflate -- $name (level $level)", encode = deflate(level), decode = inflate)
    } + listOf(
        Codec("deflate-huffman", "Huffman only (Deflate)", encode = deflate(9, Deflater.HUFFMAN_ONLY), decode = inflate),
        Codec("deflate-filtered", "Deflate, filtered (for image-like data)", encode = deflate(9, Deflater.FILTERED), decode = inflate),
        Codec("zlib", "zlib", encode = deflate(Deflater.DEFAULT_COMPRESSION, wrapped = true), decode = decoded { InflaterInputStream(it) }),
        Codec("gzip", "GZIP", encode = encoded { GZIPOutputStream(it, 16 * 1024) }, decode = decoded { GZIPInputStream(it) }),
        Codec("bzip2", "BZip2 (Burrows-Wheeler)", encode = encoded { BZip2CompressorOutputStream(it, 1) }, decode = decoded { BZip2CompressorInputStream(it) }),
        Codec(
            "lzma", "LZMA",
            encode = { data, offset, length ->
                val out = ByteArrayOutputStream(length / 2 + 64)
                LZMAOutputStream(out, lzmaOptions(6, CompressedFile.DEFAULT_UNIT), length.toLong()).use { it.write(data, offset, length) }
                out.toByteArray()
            },
            decode = decoded { LZMAInputStream(it) },
        ),
        Codec("xz-1", "XZ / LZMA2 -- fast (preset 1)", encode = xz(1), decode = decoded { XZInputStream(it) }),
        Codec("xz-6", "XZ / LZMA2 -- balanced (preset 6)", encode = xz(6), decode = decoded { XZInputStream(it) }),
        Codec("xz-9e", "XZ / LZMA2 -- smallest (exhaustive search)", encode = xz(9, exhaustive = true), decode = decoded { XZInputStream(it) }),
    )

    /** Every codec: the standard ones, then each compression network built in ModelCraft. */
    fun all(): List<Codec> = standard + NeuralCompressors.codecs()

    fun byId(id: String): Codec? = standard.firstOrNull { it.id == id } ?: NeuralCompressors.codecs().firstOrNull { it.id == id }
}
