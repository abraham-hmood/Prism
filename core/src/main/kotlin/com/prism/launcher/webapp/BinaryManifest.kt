package com.prism.launcher.webapp

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Rewrites strings inside a compiled (binary XML) AndroidManifest.xml.
 *
 * ## Why this exists
 *
 * "Install as app" turns a website into a real APK on the phone, from a template Prism ships (see
 * `:webapp-shell`). Every web app needs its own package name -- two apps with one package are one
 * app, and the second install would replace the first -- and its own label. Both live in the
 * template's compiled manifest, which is binary XML, not text, and there is no build tool on a
 * phone to recompile it.
 *
 * ## Why rewriting the string pool is enough
 *
 * In binary XML every element name, attribute name and string value is an INDEX into one string
 * pool at the front of the file. Replacing a string's contents while keeping its index changes
 * nothing anywhere else: the nodes keep pointing at the same slots. So this rebuilds only the pool
 * chunk, with the same count and order, and fixes the two sizes that grow or shrink with it. It is
 * the same edit `aapt --rename-manifest-package` makes.
 *
 * Both string encodings are handled (UTF-8 and UTF-16), and the pool is marked unsorted after an
 * edit, since a replacement can break the sort order the flag would otherwise promise.
 */
object BinaryManifest {

    private const val RES_XML_TYPE = 0x0003
    private const val RES_STRING_POOL_TYPE = 0x0001
    private const val UTF8_FLAG = 1 shl 8
    private const val SORTED_FLAG = 1

    /** Every string in the manifest's pool, in index order. */
    fun strings(axml: ByteArray): List<String> = parse(axml).strings

    /**
     * Returns a copy of [axml] where every pool string `s` is replaced by `replace(s)`.
     *
     * @throws IllegalArgumentException when [axml] is not binary XML with a string pool first.
     */
    fun rewrite(axml: ByteArray, replace: (String) -> String): ByteArray {
        val pool = parse(axml)
        val newStrings = pool.strings.map(replace)
        if (newStrings == pool.strings) return axml.copyOf()

        val encodedStrings = newStrings.map { if (pool.utf8) encodeUtf8(it) else encodeUtf16(it) }
        val stringCount = newStrings.size
        val styleCount = pool.styleOffsets.size
        val headerSize = pool.headerSize

        // Offsets are relative to the start of the string data.
        val offsets = IntArray(stringCount)
        var cursor = 0
        encodedStrings.forEachIndexed { i, bytes ->
            offsets[i] = cursor
            cursor += bytes.size
        }
        val stringData = ByteArrayOutputStream(cursor + 4).apply { encodedStrings.forEach { write(it) } }
        while (stringData.size() % 4 != 0) stringData.write(0)

        val stringsStart = headerSize + 4 * (stringCount + styleCount)
        val stylesStart = if (styleCount > 0) stringsStart + stringData.size() else 0
        val chunkSize = stringsStart + stringData.size() + pool.styleData.size

        val chunk = ByteBuffer.allocate(chunkSize).order(ByteOrder.LITTLE_ENDIAN)
        chunk.putShort(RES_STRING_POOL_TYPE.toShort())
        chunk.putShort(headerSize.toShort())
        chunk.putInt(chunkSize)
        chunk.putInt(stringCount)
        chunk.putInt(styleCount)
        chunk.putInt(pool.flags and SORTED_FLAG.inv())
        chunk.putInt(stringsStart)
        chunk.putInt(stylesStart)
        // Any header bytes beyond the 28 this format defines are carried over as they were.
        if (headerSize > 28) chunk.put(axml, pool.chunkStart + 28, headerSize - 28)
        offsets.forEach { chunk.putInt(it) }
        pool.styleOffsets.forEach { chunk.putInt(it) }
        chunk.put(stringData.toByteArray())
        chunk.put(pool.styleData)

        val before = axml.copyOfRange(0, pool.chunkStart)
        val after = axml.copyOfRange(pool.chunkStart + pool.chunkSize, axml.size)
        val out = before + chunk.array() + after
        // The document header's total size is the only other number that moves.
        ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN).putInt(4, out.size)
        return out
    }

    private class Pool(
        val chunkStart: Int,
        val chunkSize: Int,
        val headerSize: Int,
        val flags: Int,
        val utf8: Boolean,
        val strings: List<String>,
        val styleOffsets: IntArray,
        val styleData: ByteArray,
    )

    private fun parse(axml: ByteArray): Pool {
        val buf = ByteBuffer.wrap(axml).order(ByteOrder.LITTLE_ENDIAN)
        require(axml.size >= 8 && (buf.getShort(0).toInt() and 0xffff) == RES_XML_TYPE) {
            "not a binary XML document"
        }
        val docHeader = buf.getShort(2).toInt() and 0xffff
        val start = docHeader
        require(axml.size >= start + 28 && (buf.getShort(start).toInt() and 0xffff) == RES_STRING_POOL_TYPE) {
            "binary XML without a string pool first"
        }
        val headerSize = buf.getShort(start + 2).toInt() and 0xffff
        val chunkSize = buf.getInt(start + 4)
        val stringCount = buf.getInt(start + 8)
        val styleCount = buf.getInt(start + 12)
        val flags = buf.getInt(start + 16)
        val stringsStart = buf.getInt(start + 20)
        val stylesStart = buf.getInt(start + 24)
        require(chunkSize > 0 && start + chunkSize <= axml.size) { "string pool runs past the end" }
        val utf8 = flags and UTF8_FLAG != 0

        val offsetsAt = start + headerSize
        val strings = (0 until stringCount).map { i ->
            val at = start + stringsStart + buf.getInt(offsetsAt + 4 * i)
            if (utf8) decodeUtf8(axml, at) else decodeUtf16(buf, at)
        }
        val styleOffsets = IntArray(styleCount) { buf.getInt(offsetsAt + 4 * (stringCount + it)) }
        val styleData = if (styleCount > 0 && stylesStart > 0) {
            axml.copyOfRange(start + stylesStart, start + chunkSize)
        } else ByteArray(0)
        return Pool(start, chunkSize, headerSize, flags, utf8, strings, styleOffsets, styleData)
    }

    // ── UTF-16: a u16 length (two when it needs 15+ bits), the units, a u16 terminator ──

    private fun decodeUtf16(buf: ByteBuffer, at: Int): String {
        var pos = at
        var length = buf.getShort(pos).toInt() and 0xffff
        pos += 2
        if (length and 0x8000 != 0) {
            length = ((length and 0x7fff) shl 16) or (buf.getShort(pos).toInt() and 0xffff)
            pos += 2
        }
        // The buffer is little-endian, which is the pool's byte order, so getChar reads it as is.
        return String(CharArray(length) { buf.getChar(pos + 2 * it) })
    }

    private fun encodeUtf16(value: String): ByteArray {
        val out = ByteArrayOutputStream(value.length * 2 + 6)
        val length = value.length
        if (length > 0x7fff) {
            writeU16(out, ((length shr 16) and 0x7fff) or 0x8000)
            writeU16(out, length and 0xffff)
        } else {
            writeU16(out, length)
        }
        value.forEach { writeU16(out, it.code) }
        writeU16(out, 0)
        return out.toByteArray()
    }

    // ── UTF-8: char count and byte count (one byte each, two when they need 8+ bits), the bytes, 0 ──

    private fun decodeUtf8(data: ByteArray, at: Int): String {
        var pos = at
        fun readLength(): Int {
            val first = data[pos].toInt() and 0xff
            pos++
            if (first and 0x80 == 0) return first
            val second = data[pos].toInt() and 0xff
            pos++
            return ((first and 0x7f) shl 8) or second
        }
        readLength() // character count, implied by the bytes
        val bytes = readLength()
        return String(data, pos, bytes, Charsets.UTF_8)
    }

    private fun encodeUtf8(value: String): ByteArray {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream(bytes.size + 5)
        fun writeLength(n: Int) {
            require(n <= 0x7fff) { "string too long for a UTF-8 pool" }
            if (n > 0x7f) {
                out.write(((n shr 8) and 0x7f) or 0x80)
                out.write(n and 0xff)
            } else {
                out.write(n)
            }
        }
        // The character count the framework expects is in UTF-16 units.
        writeLength(value.length)
        writeLength(bytes.size)
        out.write(bytes)
        out.write(0)
        return out.toByteArray()
    }

    private fun writeU16(out: ByteArrayOutputStream, value: Int) {
        out.write(value and 0xff)
        out.write((value shr 8) and 0xff)
    }
}
