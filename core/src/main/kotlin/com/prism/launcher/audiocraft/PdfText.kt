package com.prism.launcher.audiocraft

import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

/**
 * Text out of a PDF, without a PDF library.
 *
 * ## What it reads, and what it cannot
 *
 * A PDF is a container of objects, and the text of a page lives in a *content stream* as a sequence of
 * literal strings shown by `Tj` / `TJ` operators. This walks the bytes, inflates each stream that is
 * deflated (which is nearly all of them), and collects those strings in order, adding line breaks
 * where the layout operators say a new line started.
 *
 * It is exact for a document that embeds its text the ordinary way -- which is what a script, a report
 * or an ebook chapter does -- and it can come back empty for a scan (no text at all, only an image) or
 * for a PDF whose fonts map glyphs through a custom encoding. Both of those return what they found
 * rather than inventing characters, and the caller says "this file's text could not be read" instead
 * of showing a page of nonsense.
 */
object PdfText {

    /** A ceiling, because a malformed file could otherwise ask for an unbounded buffer. */
    private const val MAX_STREAM_BYTES = 4 shl 20

    fun extract(bytes: ByteArray): String {
        val out = StringBuilder()
        var index = 0
        while (index < bytes.size) {
            val streamAt = indexOf(bytes, STREAM, index)
            if (streamAt < 0) break
            var start = streamAt + STREAM.size
            // The keyword is followed by a line break, either CRLF or LF.
            if (start < bytes.size && bytes[start] == 0x0D.toByte()) start++
            if (start < bytes.size && bytes[start] == 0x0A.toByte()) start++
            val endAt = indexOf(bytes, ENDSTREAM, start)
            if (endAt < 0) break
            val raw = bytes.copyOfRange(start, endAt)
            val decoded = inflate(raw) ?: raw
            if (decoded.size < MAX_STREAM_BYTES) {
                val text = readContent(decoded)
                if (text.isNotBlank()) {
                    if (out.isNotEmpty()) out.append('\n')
                    out.append(text)
                }
            }
            index = endAt + ENDSTREAM.size
        }
        return out.toString().trim()
    }

    private fun inflate(raw: ByteArray): ByteArray? = runCatching {
        val inflater = Inflater()
        try {
            inflater.setInput(raw)
            val out = ByteArrayOutputStream(raw.size * 3)
            val buffer = ByteArray(16 * 1024)
            while (!inflater.finished()) {
                val n = inflater.inflate(buffer)
                if (n <= 0) break
                out.write(buffer, 0, n)
                if (out.size() > MAX_STREAM_BYTES) break
            }
            out.toByteArray().takeIf { it.isNotEmpty() }
        } finally {
            inflater.end()
        }
    }.getOrNull()

    /**
     * The strings a content stream shows, in the order it shows them.
     *
     * Only the show-text operators are honoured: `(...) Tj`, `[ ... ] TJ` (the array form, whose
     * strings are concatenated) and the quote operators. Anything else -- paths, colour, positioning
     * -- is skipped, because none of it is text, and a heuristic that guessed at it would put
     * bracketed numbers in the middle of a sentence.
     */
    private fun readContent(stream: ByteArray): String {
        val out = StringBuilder()
        var i = 0
        var lastWasNewline = true
        while (i < stream.size) {
            val ch = stream[i].toInt().toChar()
            when {
                ch == '(' -> {
                    val (text, next) = readLiteralString(stream, i)
                    out.append(text)
                    i = next
                    lastWasNewline = false
                }
                ch == '<' && stream.getOrNull(i + 1)?.toInt()?.toChar() != '<' -> {
                    val (text, next) = readHexString(stream, i)
                    out.append(text)
                    i = next
                    lastWasNewline = false
                }
                ch == 'T' && (stream.getOrNull(i + 1)?.toInt()?.toChar() == 'd' ||
                    stream.getOrNull(i + 1)?.toInt()?.toChar() == 'D' ||
                    stream.getOrNull(i + 1)?.toInt()?.toChar() == '*') -> {
                    if (!lastWasNewline) {
                        out.append('\n')
                        lastWasNewline = true
                    }
                    i += 2
                }
                ch == '\'' -> {
                    if (!lastWasNewline) {
                        out.append('\n')
                        lastWasNewline = true
                    }
                    i++
                }
                else -> i++
            }
        }
        return out.toString()
    }

    /** A literal `( ... )` string, with the escapes a PDF writer emits. */
    private fun readLiteralString(bytes: ByteArray, openAt: Int): Pair<String, Int> {
        val out = StringBuilder()
        var i = openAt + 1
        var depth = 1
        while (i < bytes.size) {
            val ch = bytes[i].toInt().toChar()
            when {
                ch == '\\' -> {
                    val next = bytes.getOrNull(i + 1)?.toInt()?.toChar() ?: break
                    when (next) {
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000C')
                        '(' -> out.append('(')
                        ')' -> out.append(')')
                        '\\' -> out.append('\\')
                        in '0'..'7' -> {
                            // Up to three octal digits, as the spec allows.
                            var value = 0
                            var digits = 0
                            var j = i + 1
                            while (digits < 3 && j < bytes.size) {
                                val digit = bytes[j].toInt().toChar()
                                if (digit !in '0'..'7') break
                                value = value * 8 + (digit - '0')
                                digits++
                                j++
                            }
                            out.append(value.toChar())
                            i = j - 1
                        }
                        else -> out.append(next)
                    }
                    i += 2
                }
                ch == '(' -> {
                    depth++
                    out.append(ch)
                    i++
                }
                ch == ')' -> {
                    depth--
                    if (depth == 0) return out.toString() to i + 1
                    out.append(ch)
                    i++
                }
                else -> {
                    out.append(ch)
                    i++
                }
            }
        }
        return out.toString() to i
    }

    /** A `<48656C6C6F>` hex string. Bytes that do not spell printable characters are dropped. */
    private fun readHexString(bytes: ByteArray, openAt: Int): Pair<String, Int> {
        val hex = StringBuilder()
        var i = openAt + 1
        while (i < bytes.size && bytes[i].toInt().toChar() != '>') {
            val ch = bytes[i].toInt().toChar()
            if (ch.isLetterOrDigit()) hex.append(ch)
            i++
        }
        val out = StringBuilder()
        var index = 0
        while (index + 1 < hex.length) {
            val value = hex.substring(index, index + 2).toIntOrNull(16) ?: 0
            if (value in 0x20..0x7E) out.append(value.toChar())
            index += 2
        }
        return out.toString() to (i + 1)
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int): Int {
        if (needle.isEmpty()) return -1
        outer@ for (i in from..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    private val STREAM = "stream".toByteArray(Charsets.US_ASCII)
    private val ENDSTREAM = "endstream".toByteArray(Charsets.US_ASCII)
}
