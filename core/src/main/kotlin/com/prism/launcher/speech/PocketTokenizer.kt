package com.prism.launcher.speech

import java.io.File

/**
 * The SentencePiece unigram tokenizer of a Pocket TTS bundle, reimplemented on-device.
 *
 * The bundles ship a sentencepiece model; rather than parsing that protobuf, the export step
 * dumps the piece table straight out of it (piece, full-precision score, type per line) and this
 * class implements the encode algorithm against that table. Parity with the python tokenizer is
 * locked by fixture tests covering the edges: dummy prefix, whitespace escaping (bundles of this
 * model keep runs), byte fallback for vocabulary misses, and code points rather than UTF-16 units.
 *
 * The bundle it is written for normalises with NO unicode folding (the "identity" rule set) and no
 * whitespace collapsing, and encodes with byte fallback, so: normalise = one leading space, then
 * every space becomes U+2581; unmatched characters fall back to their per-byte pieces.
 */
class PocketTokenizer private constructor(
    private val pieces: Array<String>,
    private val scores: FloatArray,
    types: ByteArray,
) {
    /** Only NORMAL and USER_DEFINED pieces are lattice candidates; CONTROL/UNUSED are not. */
    private val pieceIds = ArrayList<Int>(types.size)
    private val pieceBytes = ArrayList<ByteArray>(types.size)
    private val pieceScores = ArrayList<Float>(types.size)
    private val byFirstByte = Array<MutableList<Int>?>(256) { null }
    private val bytePieces = IntArray(256) { -1 }
    private val byteScores = FloatArray(256) { java.lang.Float.NEGATIVE_INFINITY }
    private var maxPieceBytes = 0

    init {
        for (i in types.indices) {
            when (types[i].toInt()) {
                TYPE_NORMAL, TYPE_USER_DEFINED -> {
                    val bytes = pieces[i].toByteArray(Charsets.UTF_8)
                    if (bytes.size > maxPieceBytes) maxPieceBytes = bytes.size
                    pieceIds.add(i)
                    pieceBytes.add(bytes)
                    pieceScores.add(scores[i])
                    val first = bytes[0].toInt() and 0xFF
                    (byFirstByte[first] ?: ArrayList<Int>().also { byFirstByte[first] = it }).add(
                        pieceIds.lastIndex
                    )
                }
                TYPE_BYTE -> {
                    // "<0x41>" is exactly how the model spells each of the 256 byte values.
                    val piece = pieces[i]
                    if (piece.length != 6 || piece[0] != '<' || piece[5] != '>') continue
                    val value = piece.substring(3, 5).toInt(16)
                    bytePieces[value] = i
                    // The dumped proto scores byte pieces 0.0, but sentencepiece keeps them OUT
                    // of the lattice: they carry an unk-level penalty when they appear at all.
                    // Storing 0.0 would let raw bytes beat every trained piece.
                    byteScores[value] = BYTE_FALLBACK_SCORE
                }
            }
        }
    }

    /**
     * No unicode folding: the bundle's normalisation is "identity" plus the two standard moves --
     * a leading space (dummy prefix) and spaces escaped to U+2581. Whitespace runs are preserved;
     * tabs and newlines are content, not separators, on this bundle.
     */
    private fun normalize(text: String): String =
        (" $text").replace(" ", WHITESPACE_GLUE)

    /** The ids of [text], the same lattice sentencepiece's encoder runs. */
    fun encode(text: String): IntArray {
        val bytes = normalize(text).toByteArray(Charsets.UTF_8)
        return when {
            bytes.isEmpty() -> IntArray(0)
            else -> viterbi(bytes)
        }
    }

    /**
     * Unigram Viterbi over the UTF-8 bytes of the normalised text. SentencePiece matches pieces
     * over code points, which is byte-equivalent here: no piece boundary can split a code point
     * and still reproduce the python encoder's lattice, because every multi-byte code point is
     * only reachable through pieces that carry the whole sequence plus a byte fallback that walks
     * its individual bytes when nothing larger matches.
     */
    private fun viterbi(bytes: ByteArray): IntArray {
        val n = bytes.size
        // best[i]: best total score reaching byte i; back[i]: piece id; backLen[i]: its length.
        val best = FloatArray(n + 1) { java.lang.Float.NEGATIVE_INFINITY }
        val back = IntArray(n + 1)
        val backLen = IntArray(n + 1)
        best[0] = 0f

        for (start in 0 until n) {
            val here = best[start]
            if (here == java.lang.Float.NEGATIVE_INFINITY) continue
            val limit = minOf(n, start + maxPieceBytes)
            byFirstByte[bytes[start].toInt() and 0xFF]?.forEach { pi ->
                val bytesOfPiece = pieceBytes[pi]
                val pieceLen = bytesOfPiece.size
                if (start + pieceLen <= limit) {
                    var matches = true
                    for (k in 0 until pieceLen) {
                        if (bytes[start + k] != bytesOfPiece[k]) {
                            matches = false
                            break
                        }
                    }
                    if (matches) {
                        val score = here + pieceScores[pi]
                        if (score > best[start + pieceLen]) {
                            best[start + pieceLen] = score
                            back[start + pieceLen] = pieceIds[pi]
                            backLen[start + pieceLen] = pieceLen
                        }
                    }
                }
            }
            // Byte fallback, one per available byte piece, matching sentencepiece's behaviour.
            val fallback = bytePieces[bytes[start].toInt() and 0xFF]
            if (fallback >= 0) {
                val score = here + byteScores[bytes[start].toInt() and 0xFF]
                if (score > best[start + 1]) {
                    best[start + 1] = score
                    back[start + 1] = fallback
                    backLen[start + 1] = 1
                }
            }
        }

        // Walk the lattice backwards and forward the ids.
        val ids = ArrayList<Int>(n / 3 + 1)
        var at = n
        while (at > 0) {
            val length = backLen[at]
            if (length == 0) {
                // Unreached position: stand in with the unknown piece, as the model's unk does.
                ids.add(unknown)
                at--
            } else {
                ids.add(back[at])
                at -= length
            }
        }
        ids.reverse()
        return ids.toIntArray()
    }

    /** Reverses ids into text, gluing pieces and re-expanding the whitespace marker. */
    fun decode(ids: IntArray): String {
        val sb = StringBuilder()
        for (id in ids) {
            val piece = pieces[id]
            var from = 0
            var match = piece.indexOf(WHITESPACE_GLUE, from)
            while (match >= 0) {
                sb.append(piece, from, match).append(' ')
                from = match + WHITESPACE_GLUE.length
                match = piece.indexOf(WHITESPACE_GLUE, from)
            }
            sb.append(piece, from, piece.length)
        }
        return sb.toString()
    }

    companion object {
        /** U+2581, the marker sentencepiece escapes spaces to. */
        private const val WHITESPACE_GLUE = "▁"

        // SentencePiece proto piece types.
        private const val TYPE_NORMAL = 1
        private const val TYPE_UNKNOWN = 2
        private const val TYPE_CONTROL = 3
        private const val TYPE_USER_DEFINED = 4
        private const val TYPE_UNUSED = 5
        private const val TYPE_BYTE = 6

        /** SentencePiece's unk-level penalty, what byte-fallback pieces cost when they appear. */
        private const val BYTE_FALLBACK_SCORE = -10f

        private const val unknown = 0

        /**
         * Reads the export-time dump (TSV: piece, full-precision score, type). Null when the file
         * is missing or malformed -- a corrupt tokenizer falls back to the system voice rather
         * than crash, exactly like the other engine model loads.
         */
        fun load(file: File): PocketTokenizer? = runCatching {
            val lines = file.readLines()
            if (lines.isEmpty() || lines[0] != "piece\tscore\ttype") return null
            val pieces = ArrayList<String>(lines.size - 1)
            val scores = ArrayList<Float>(lines.size - 1)
            val types = ArrayList<Byte>(lines.size - 1)
            for (i in 1 until lines.size) {
                val row = lines[i].split('\t')
                if (row.size != 3) continue
                pieces.add(row[0])
                scores.add(row[1].toFloat())
                types.add(row[2].toInt().toByte())
            }
            PocketTokenizer(pieces.toTypedArray(), scores.toFloatArray(), types.toByteArray())
        }.getOrElse {
            com.prism.core.PrismPlatform.log.error("PrismSpeech", "PocketTokenizer.load failed: $it")
            null
        }
    }
}
