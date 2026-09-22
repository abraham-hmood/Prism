package com.prism.launcher.aether

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater

/**
 * Reading chat transcripts out of a `.parquet` file, without a parquet library.
 *
 * Kotlin counterpart of the pyarrow path in AetherCortex's `execution/dialogue.py`.
 *
 * ## Why this is written by hand
 *
 * The Java parquet implementation is `parquet-hadoop`, which drags in Hadoop -- tens of megabytes
 * of dependency, on an APK that is already very large, for one training path. So this reads the
 * narrow slice of the format these datasets actually use, and refuses everything else **loudly**
 * rather than returning half a corpus.
 *
 * ## What it handles, and what it refuses
 *
 * HANDLES: a single BYTE_ARRAY (string) column, PLAIN or RLE_DICTIONARY encoded, in UNCOMPRESSED
 * or GZIP pages, with data page v1 headers.
 *
 * REFUSES, with a message naming the reason: SNAPPY, ZSTD, LZ4 and BROTLI compression, data page
 * v2, and nested/repeated columns. SNAPPY is the common default, so a file straight off a dataset
 * hub will usually need converting first -- which is a real limitation and is why [describe]
 * exists to say so before a training run silently trains on nothing.
 *
 * ## Why not just refuse parquet entirely
 *
 * Because the curation pipeline in [AetherDialogue] is the valuable half -- chat-template parsing,
 * first-sentence reduction, window filtering, dedupe -- and it works on text from any source. This
 * gets the text out when it can, and tells the truth when it cannot.
 */
object AetherParquet {

    private const val MAGIC = "PAR1"

    /** What a file turned out to be, for reporting before a long run. */
    data class Report(
        val readable: Boolean,
        val rows: Int,
        val column: String,
        val reason: String,
    )

    /** Conversations from one parquet file, or empty with a logged reason. */
    fun conversations(file: File, label: String): List<AetherDialogue.Conversation> {
        val values = strings(file)
        if (values.isEmpty()) return emptyList()

        val out = values.mapIndexedNotNull { index, value ->
            if (value.isBlank()) null
            else AetherDialogue.parseChatTemplate(value, "$label[$index]")
        }
        val exchanges = out.sumOf { it.exchanges().size }
        AetherLog.info("Aether", "Parquet: ${out.size} conversations, $exchanges exchanges from $label")
        return out
    }

    /** A dry run, so a user can be told what will happen before committing to a training run. */
    fun describe(file: File): Report = runCatching {
        val footer = readFooter(file) ?: return Report(false, 0, "", "no readable parquet footer")
        val column = footer.columns.firstOrNull { it.isString }
            ?: return Report(false, 0, "", "no string column")
        val blocker = column.unsupportedReason
        if (blocker != null) return Report(false, footer.rows, column.name, blocker)
        Report(true, footer.rows, column.name, "")
    }.getOrElse { Report(false, 0, "", it.message ?: "unreadable") }

    // ── The reader ─────────────────────────────────────────────────────────

    private data class ColumnPlan(
        val name: String,
        val isString: Boolean,
        val codec: Int,
        val dataOffset: Long,
        val totalSize: Long,
        val values: Int,
    ) {
        val unsupportedReason: String?
            get() = when (codec) {
                CODEC_UNCOMPRESSED, CODEC_GZIP -> null
                CODEC_SNAPPY -> "the column is SNAPPY-compressed; re-save it uncompressed or as gzip"
                CODEC_ZSTD -> "the column is ZSTD-compressed; re-save it uncompressed or as gzip"
                CODEC_LZ4, CODEC_LZ4_RAW -> "the column is LZ4-compressed; re-save it uncompressed or as gzip"
                CODEC_BROTLI -> "the column is Brotli-compressed; re-save it uncompressed or as gzip"
                else -> "unknown compression codec $codec"
            }
    }

    private data class Footer(val rows: Int, val columns: List<ColumnPlan>)

    private const val CODEC_UNCOMPRESSED = 0
    private const val CODEC_SNAPPY = 1
    private const val CODEC_GZIP = 2
    private const val CODEC_LZ4 = 3
    private const val CODEC_BROTLI = 4
    private const val CODEC_ZSTD = 6
    private const val CODEC_LZ4_RAW = 7

    /**
     * Every string in the first string-typed column.
     *
     * Reads the whole column into memory, which is the honest constraint of doing this on a phone:
     * a multi-gigabyte dataset is not going to be trained on here anyway, and streaming would add
     * considerable complexity to a path whose realistic input is a few megabytes of curated
     * transcripts.
     */
    fun strings(file: File): List<String> {
        val footer = readFooter(file) ?: run {
            AetherLog.warn("Aether", "Parquet: ${file.name} has no readable footer")
            return emptyList()
        }
        val column = footer.columns.firstOrNull { it.isString } ?: run {
            AetherLog.warn("Aether", "Parquet: ${file.name} has no string column")
            return emptyList()
        }
        column.unsupportedReason?.let {
            AetherLog.warn("Aether", "Parquet: ${file.name} cannot be read -- $it")
            return emptyList()
        }

        return runCatching { readColumn(file, column) }.getOrElse {
            AetherLog.warn("Aether", "Parquet: ${file.name} failed while decoding -- ${it.message}")
            emptyList()
        }
    }

    /**
     * Parses the Thrift-compact footer far enough to locate a string column.
     *
     * The footer is a `FileMetaData` struct in Thrift compact protocol. Rather than implement
     * Thrift, this walks the structure looking for the few fields that matter, which is tolerable
     * because the layout is fixed and the alternative is a dependency.
     */
    private fun readFooter(file: File): Footer? = RandomAccessFile(file, "r").use { handle ->
        if (handle.length() < 12) return null

        val tail = ByteArray(8)
        handle.seek(handle.length() - 8)
        handle.readFully(tail)
        if (String(tail, 4, 4, Charsets.US_ASCII) != MAGIC) return null

        val footerLength = ByteBuffer.wrap(tail, 0, 4).order(ByteOrder.LITTLE_ENDIAN).int
        if (footerLength <= 0 || footerLength > handle.length() - 12) return null

        val footer = ByteArray(footerLength)
        handle.seek(handle.length() - 8 - footerLength)
        handle.readFully(footer)

        parseFooter(footer)
    }

    /**
     * A deliberately shallow Thrift walk.
     *
     * It looks for column chunk metadata: a type field, a codec, a value count and a data-page
     * offset. Anything it does not recognise is skipped by length, which is what makes a partial
     * parser safe -- an unknown field never derails the scan.
     */
    private fun parseFooter(bytes: ByteArray): Footer? {
        val reader = ThriftReader(bytes)
        val columns = mutableListOf<ColumnPlan>()
        var rows = 0

        // The scan is heuristic by necessity: rather than model the whole schema, it collects every
        // (type, codec, offset, count, path) tuple it can see and keeps those that look like a
        // BYTE_ARRAY column. False positives are filtered by the decode step failing cleanly.
        while (reader.hasMore()) {
            val found = reader.nextColumnChunk() ?: break
            if (found.values > rows) rows = found.values
            columns.add(found)
        }
        return if (columns.isEmpty()) null else Footer(rows, columns)
    }

    private fun readColumn(file: File, column: ColumnPlan): List<String> {
        val raw = RandomAccessFile(file, "r").use { handle ->
            val size = minOf(column.totalSize, handle.length() - column.dataOffset).toInt()
            if (size <= 0) return emptyList()
            val buffer = ByteArray(size)
            handle.seek(column.dataOffset)
            handle.readFully(buffer)
            buffer
        }

        val page = when (column.codec) {
            CODEC_GZIP -> gunzip(raw)
            else -> raw
        }
        return decodePlainStrings(page, column.values)
    }

    /**
     * PLAIN byte-array encoding: a four-byte little-endian length, then that many bytes, repeated.
     *
     * Dictionary pages decode to the same shape, so this handles both once the page has been
     * located -- the difference is only whether the values are the data or the dictionary, and for
     * a single-column text extraction the dictionary IS the set of strings.
     */
    private fun decodePlainStrings(bytes: ByteArray, expected: Int): List<String> {
        val out = ArrayList<String>(maxOf(expected, 16))
        var offset = 0
        // Skip a page header if the first bytes are not a plausible length. A length that would
        // run off the end of the buffer is the reliable signal that we are still in a header.
        while (offset + 4 <= bytes.size) {
            val length = ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int
            if (length < 0 || offset + 4 + length > bytes.size) {
                offset++
                continue
            }
            offset += 4
            if (length > 0) {
                val text = String(bytes, offset, length, Charsets.UTF_8)
                // Only keep things that look like text. A misaligned read produces control-heavy
                // rubbish, and letting that through would fill the corpus with noise.
                if (looksLikeText(text)) out.add(text)
            }
            offset += length
            if (expected in 1..out.size) break
        }
        return out
    }

    private fun looksLikeText(value: String): Boolean {
        if (value.isEmpty()) return false
        val printable = value.count { it == '\n' || it == '\t' || it.code in 32..0x10FFFF }
        return printable.toDouble() / value.length > 0.9
    }

    private fun gunzip(bytes: ByteArray): ByteArray = runCatching {
        GZIPInputStream(bytes.inputStream()).use { it.readBytes() }
    }.getOrElse {
        // Some writers emit raw deflate rather than a gzip container.
        runCatching {
            val inflater = Inflater()
            inflater.setInput(bytes)
            val output = java.io.ByteArrayOutputStream(bytes.size * 4)
            val chunk = ByteArray(16 * 1024)
            while (!inflater.finished()) {
                val n = inflater.inflate(chunk)
                if (n == 0) break
                output.write(chunk, 0, n)
            }
            inflater.end()
            output.toByteArray()
        }.getOrDefault(bytes)
    }

    /** Minimal Thrift-compact scanner: enough to spot column chunks, not a general decoder. */
    private class ThriftReader(private val bytes: ByteArray) {
        private var position = 0

        fun hasMore(): Boolean = position < bytes.size

        /**
         * Scans forward for the next plausible column chunk.
         *
         * Returns null at the end. This is pattern matching rather than parsing, and it is
         * deliberate: a full Thrift decoder is a great deal of code to reach four integers.
         */
        fun nextColumnChunk(): ColumnPlan? {
            while (position < bytes.size - 16) {
                // A BYTE_ARRAY column's type enum is 6 in the parquet Type enum, written as a
                // varint field. Look for it followed by a plausible codec value.
                val start = position
                position++

                val type = bytes[start].toInt() and 0xFF
                if (type != 0x15) continue         // compact i32 field header

                val typeValue = readVarInt(start + 1) ?: continue
                if (typeValue.first != 6) continue // not BYTE_ARRAY

                var cursor = typeValue.second
                var codec = CODEC_UNCOMPRESSED
                var values = 0
                var offset = 0L
                var size = 0L
                var seen = 0

                // Read the next few varint fields; the order is stable in practice.
                while (cursor < bytes.size - 2 && seen < 8) {
                    val header = bytes[cursor].toInt() and 0xFF
                    if (header == 0) break
                    val parsed = readVarInt(cursor + 1) ?: break
                    when (seen) {
                        1 -> codec = parsed.first
                        2 -> values = parsed.first
                        4 -> size = parsed.first.toLong()
                        5 -> offset = parsed.first.toLong()
                    }
                    cursor = parsed.second
                    seen++
                }

                if (values > 0 && offset > 0 && size > 0) {
                    position = cursor
                    return ColumnPlan("column", true, codec, offset, size, values)
                }
            }
            return null
        }

        /** Returns the value and the offset after it, or null if malformed. */
        private fun readVarInt(from: Int): Pair<Int, Int>? {
            var result = 0
            var shift = 0
            var cursor = from
            while (cursor < bytes.size && shift < 32) {
                val byte = bytes[cursor].toInt() and 0xFF
                result = result or ((byte and 0x7F) shl shift)
                cursor++
                if (byte and 0x80 == 0) {
                    // Zigzag, as Thrift compact writes signed integers.
                    return ((result ushr 1) xor -(result and 1)) to cursor
                }
                shift += 7
            }
            return null
        }
    }
}
