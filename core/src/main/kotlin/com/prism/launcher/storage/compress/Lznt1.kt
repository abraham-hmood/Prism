package com.prism.launcher.storage.compress

import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * LZNT1, the algorithm NTFS compresses files with ([MS-XCA] section 2.5).
 *
 * Data is cut into 4 KB chunks. Each chunk starts with a two-byte header -- its stored size less
 * three in the low 12 bits, the signature 0b011 above that, and a flag saying whether it is
 * compressed. A compressed chunk is groups of eight tokens behind a flag byte: a clear bit is a
 * literal byte, a set bit a two-byte back-reference whose split between distance and length
 * depends on how far into the chunk it sits (early on, distances are short, so more bits go to the
 * length). A chunk that would not shrink is stored as it is, exactly as NTFS does.
 */
object Lznt1 {

    private const val CHUNK = 4096
    private const val HASH_BITS = 12
    private const val MAX_CHAIN = 48

    /** Bits of the token that hold the distance, less four, at [position] within the chunk. */
    private fun distanceShift(position: Int): Int {
        var lg = 0
        var k = position - 1
        while (k >= 0x10) {
            lg++
            k = k shr 1
        }
        return lg
    }

    fun compress(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): ByteArray {
        val out = ByteArrayOutputStream(length / 2 + 16)
        val buffer = ByteArray(CHUNK + CHUNK / 8 + 16)
        val head = IntArray(1 shl HASH_BITS)
        val prev = IntArray(CHUNK)
        var pos = offset
        val end = offset + length
        while (pos < end) {
            val size = minOf(CHUNK, end - pos)
            val written = compressChunk(data, pos, size, buffer, head, prev)
            if (written in 1 until size) {
                val header = 0xB000 or (written - 1)
                out.write(header and 0xFF)
                out.write(header ushr 8)
                out.write(buffer, 0, written)
            } else {
                val header = 0x3000 or (size - 1)
                out.write(header and 0xFF)
                out.write(header ushr 8)
                out.write(data, pos, size)
            }
            pos += size
        }
        return out.toByteArray()
    }

    private fun hash(src: ByteArray, at: Int): Int {
        val h = ((src[at].toInt() and 0xFF) shl 8) xor ((src[at + 1].toInt() and 0xFF) shl 4) xor (src[at + 2].toInt() and 0xFF)
        return (h * 2654435761L.toInt()) ushr (32 - HASH_BITS)
    }

    /** Compressed size of one chunk in [out], or -1 when it would not be smaller than [size]. */
    private fun compressChunk(src: ByteArray, start: Int, size: Int, out: ByteArray, head: IntArray, prev: IntArray): Int {
        head.fill(-1)
        fun insert(p: Int) {
            if (p + 3 > size) return
            val h = hash(src, start + p)
            prev[p] = head[h]
            head[h] = p
        }
        var op = 0
        var i = 0
        while (i < size) {
            val flagAt = op++
            if (op >= size) return -1
            var flags = 0
            var bit = 0
            while (bit < 8 && i < size) {
                val lg = distanceShift(i)
                val maxLength = (0xFFF ushr lg) + 3
                val maxDistance = 1 shl (4 + lg)
                var bestLength = 0
                var bestDistance = 0
                if (i >= 1 && size - i >= 3) {
                    val limit = minOf(maxLength, size - i)
                    var candidate = head[hash(src, start + i)]
                    var chain = 0
                    while (candidate >= 0 && chain < MAX_CHAIN) {
                        val distance = i - candidate
                        if (distance > maxDistance) break
                        var l = 0
                        while (l < limit && src[start + candidate + l] == src[start + i + l]) l++
                        if (l > bestLength) {
                            bestLength = l
                            bestDistance = distance
                            if (l == limit) break
                        }
                        candidate = prev[candidate]
                        chain++
                    }
                }
                if (bestLength >= 3) {
                    if (op + 2 >= size) return -1
                    val token = ((bestDistance - 1) shl (12 - lg)) or (bestLength - 3)
                    out[op++] = token.toByte()
                    out[op++] = (token ushr 8).toByte()
                    flags = flags or (1 shl bit)
                    repeat(bestLength) { insert(i); i++ }
                } else {
                    if (op + 1 >= size) return -1
                    out[op++] = src[start + i]
                    insert(i)
                    i++
                }
                bit++
            }
            out[flagAt] = flags.toByte()
        }
        return op
    }

    /** Decompresses [length] bytes of [src] from [offset] into exactly [outLength] bytes. */
    fun decompress(src: ByteArray, outLength: Int, offset: Int = 0, length: Int = src.size - offset): ByteArray {
        val out = ByteArray(outLength)
        var sp = offset
        val end = offset + length
        var op = 0
        while (sp + 2 <= end && op < outLength) {
            val header = (src[sp].toInt() and 0xFF) or ((src[sp + 1].toInt() and 0xFF) shl 8)
            sp += 2
            if (header == 0) break
            val chunkEnd = sp + (header and 0xFFF) + 1
            if (chunkEnd > end) throw IOException("LZNT1: a chunk runs past the end of the data")
            val chunkStart = op
            if (header and 0x8000 == 0) {
                val count = minOf(chunkEnd - sp, outLength - op)
                System.arraycopy(src, sp, out, op, count)
                op += count
            } else {
                while (sp < chunkEnd && op < outLength) {
                    val flags = src[sp++].toInt() and 0xFF
                    for (bit in 0 until 8) {
                        if (sp >= chunkEnd || op >= outLength) break
                        if (flags and (1 shl bit) == 0) {
                            out[op++] = src[sp++]
                        } else {
                            if (sp + 2 > chunkEnd) throw IOException("LZNT1: a back-reference is cut short")
                            val token = (src[sp].toInt() and 0xFF) or ((src[sp + 1].toInt() and 0xFF) shl 8)
                            sp += 2
                            val position = op - chunkStart
                            val lg = distanceShift(position)
                            val count = (token and (0xFFF ushr lg)) + 3
                            val distance = (token ushr (12 - lg)) + 1
                            if (distance > position) throw IOException("LZNT1: a back-reference points before the chunk")
                            repeat(count) {
                                if (op < outLength) {
                                    out[op] = out[op - distance]
                                    op++
                                }
                            }
                        }
                    }
                }
            }
            sp = chunkEnd
        }
        if (op != outLength) throw IOException("LZNT1: the data ended after $op of $outLength bytes")
        return out
    }
}
