package com.prism.launcher.stremio

import java.security.MessageDigest

/** One file inside a torrent. [offset] is its start within the torrent's byte stream. */
data class TorrentFile(
    val index: Int,
    val path: String,
    val length: Long,
    val offset: Long,
) {
    val name: String get() = path.substringAfterLast('/')
}

/**
 * A torrent's metadata, after the info dictionary has been found (or fetched from a peer).
 *
 * ## The identity is the hash, not the fields
 *
 * [infoHash] is the SHA-1 of the info dictionary exactly as it appeared on the wire. Every peer
 * checks it during the handshake, and a torrent-realm stream from an add-on is nothing but that
 * 20-byte value. Everything else here is derived from the same dictionary, which is why
 * [fromMetadata] re-verifies rather than trusting the peer that supplied it: metadata from a
 * stranger is untrusted input, and hashing it is the one check that makes it safe to use.
 *
 * ## Why the files carry offsets
 *
 * BitTorrent numbers pieces across the WHOLE torrent, not per file. A 900 MB episode inside a 40 GB
 * season pack occupies a slice of a piece stream that starts long before it, so a reader has to map
 * file offsets to piece indices and back. [piecesFor] is that map, and [TorrentFile.offset] is what
 * makes it possible. Getting this wrong is the difference between a working stream and one that
 * plays 900 MB of somebody else's episode.
 */
class TorrentInfo(
    val infoHash: ByteArray,
    val name: String,
    val pieceLength: Int,
    val pieceHashes: List<ByteArray>,
    val files: List<TorrentFile>,
    val trackers: List<String>,
) {

    val pieceCount: Int get() = pieceHashes.size

    val totalLength: Long get() = files.sumOf { it.length }

    /** The byte length of one piece. The last piece of a torrent is usually short. */
    fun pieceSize(index: Int): Int {
        val start = index.toLong() * pieceLength
        val remaining = totalLength - start
        return remaining.coerceIn(0L, pieceLength.toLong()).toInt()
    }

    /** The pieces that carry [file], as global piece indices. */
    fun piecesFor(file: TorrentFile): IntRange {
        if (file.length <= 0L) return IntRange.EMPTY
        val first = (file.offset / pieceLength).toInt()
        val last = ((file.offset + file.length - 1) / pieceLength).toInt()
        return first..minOf(last, pieceCount - 1)
    }

    /**
     * The file a Stremio stream means.
     *
     * A stream names its file two ways: `fileIdx`, which is the index in the torrent's own file
     * list, and `behaviorHints.filename`, which is what the add-on says the file is called. THE
     * NAME WINS when both are present: an index is only meaningful if both ends count the same
     * files in the same order, and the filename survives that disagreement. When neither picks
     * anything sensible the largest file is the right guess -- a season pack's biggest file is an
     * episode, and a torrent with one file has only one answer.
     */
    fun selectFile(fileIdx: Int?, filename: String?): TorrentFile? {
        if (files.isEmpty()) return null

        val wanted = filename?.trim()?.substringAfterLast('/')?.lowercase().orEmpty()
        if (wanted.isNotBlank()) {
            files.firstOrNull { it.name.lowercase() == wanted }?.let { return it }
            files.firstOrNull { it.path.lowercase().endsWith(wanted) }?.let { return it }
        }

        fileIdx?.let { index ->
            files.firstOrNull { it.index == index }?.let { return it }
            if (index in files.indices) return files[index]
        }

        return files.maxByOrNull { it.length }
    }

    fun hexHash(): String = infoHash.joinToString("") { byte -> "%02x".format(byte) }

    override fun equals(other: Any?): Boolean =
        other is TorrentInfo && infoHash.contentEquals(other.infoHash)

    override fun hashCode(): Int = infoHash.contentHashCode()

    companion object {

        /** Piece hashes are fixed at 20 bytes each by the protocol. */
        private const val HASH_LENGTH = 20

        /**
         * Reads a `.torrent` file.
         *
         * The info slice is taken from the original bytes before anything is decoded, because the
         * hash covers the encoding, not the meaning -- see [Bencode.dictionaryValue].
         */
        fun fromTorrentFile(bytes: ByteArray): TorrentInfo? {
            val infoBytes = runCatching { Bencode.dictionaryValue(bytes, "info") }.getOrNull() ?: return null
            val root = runCatching { Bencode.decode(bytes) as? Map<*, *> }.getOrNull() ?: return null
            return build(infoBytes, sha1(infoBytes), trackersFrom(root))
        }

        /**
         * Turns metadata fetched from a peer into a torrent.
         *
         * [expectedHash] is checked against a fresh SHA-1. When they differ the metadata is
         * discarded: a peer that answers with somebody else's info dictionary is either broken or
         * lying, and either way the pieces it would send afterwards belong to another torrent.
         */
        fun fromMetadata(
            infoBytes: ByteArray,
            expectedHash: ByteArray,
            trackers: List<String> = emptyList(),
        ): TorrentInfo? {
            if (!sha1(infoBytes).contentEquals(expectedHash)) return null
            return build(infoBytes, expectedHash, trackers)
        }

        private fun build(infoBytes: ByteArray, hash: ByteArray, trackers: List<String>): TorrentInfo? {
            val info = runCatching { Bencode.decode(infoBytes) as? Map<*, *> }.getOrNull() ?: return null

            val pieceLength = Bencode.long(info["pieceLength"]).toInt()
            if (pieceLength <= 0) return null

            val pieces = info["pieces"] as? ByteArray ?: return null
            if (pieces.size % HASH_LENGTH != 0) return null
            val hashes = (0 until pieces.size / HASH_LENGTH).map { index ->
                pieces.copyOfRange(index * HASH_LENGTH, (index + 1) * HASH_LENGTH)
            }
            if (hashes.isEmpty()) return null

            val name = Bencode.string(info["name"]).ifBlank { "torrent" }
            val files = filesFrom(info, name)
            if (files.isEmpty()) return null

            return TorrentInfo(
                infoHash = hash,
                name = name,
                pieceLength = pieceLength,
                pieceHashes = hashes,
                files = files,
                trackers = trackers.distinct(),
            )
        }

        private fun filesFrom(info: Map<*, *>, torrentName: String): List<TorrentFile> {
            val list = info["files"] as? List<*>
                ?: run {
                    val length = Bencode.long(info["length"], -1L)
                    if (length < 0L) return emptyList()
                    return listOf(TorrentFile(0, torrentName, length, 0L))
                }

            var offset = 0L
            val out = ArrayList<TorrentFile>(list.size)
            list.forEachIndexed { index, raw ->
                val file = raw as? Map<*, *> ?: return@forEachIndexed
                val length = Bencode.long(file["length"], -1L)
                if (length < 0L) return@forEachIndexed
                val parts = (file["path"] as? List<*>).orEmpty().mapNotNull { part ->
                    when (part) {
                        is ByteArray -> String(part, Charsets.UTF_8)
                        is String -> part
                        else -> null
                    }
                }
                out.add(
                    TorrentFile(
                        index = index,
                        path = parts.joinToString("/").ifBlank { torrentName + "/" + index },
                        length = length,
                        offset = offset,
                    )
                )
                offset += length
            }
            return out
        }

        private fun trackersFrom(root: Map<*, *>): List<String> {
            val out = LinkedHashSet<String>()
            Bencode.string(root["announce"]).takeIf { it.isNotBlank() }?.let(out::add)
            (root["announce-list"] as? List<*>)?.forEach { tier ->
                (tier as? List<*>)?.forEach { entry ->
                    Bencode.string(entry).takeIf { it.isNotBlank() }?.let(out::add)
                }
            }
            return out.toList()
        }

        fun sha1(bytes: ByteArray): ByteArray =
            MessageDigest.getInstance("SHA-1").digest(bytes)
    }
}

/** What a magnet link says, before any peer has answered. */
data class MagnetLink(
    val infoHash: ByteArray,
    val name: String,
    val trackers: List<String>,
) {
    fun toMagnet(): String = buildString {
        append("magnet:?xt=urn:btih:")
        append(infoHash.joinToString("") { byte -> "%02x".format(byte) })
        if (name.isNotBlank()) append("&dn=").append(percentEncode(name))
        trackers.forEach { append("&tr=").append(percentEncode(it)) }
    }

    companion object {

        /**
         * Parses `magnet:?xt=urn:btih:...`.
         *
         * Two hash spellings exist and both are common: 40 hex characters, and 32 base32 ones --
         * the latter because some add-ons quote an old link. `tr` may repeat, which is why this is
         * not a map lookup.
         */
        fun parse(url: String): MagnetLink? {
            if (!url.startsWith("magnet:", ignoreCase = true)) return null
            val query = url.substringAfter('?', "")
            val params = query.split('&').filter { it.isNotBlank() }
            var hash: ByteArray? = null
            var name = ""
            val trackers = LinkedHashSet<String>()

            params.forEach { param ->
                val key = param.substringBefore('=', "")
                val value = param.substringAfter('=', "")
                when (key.lowercase()) {
                    "xt" -> {
                        val raw = value.substringAfter("urn:btih:", "")
                        if (raw.isNotBlank()) hash = decodeHash(raw)
                    }
                    "dn" -> name = percentDecode(value)
                    "tr" -> percentDecode(value).takeIf { it.isNotBlank() }?.let(trackers::add)
                }
            }

            val infoHash = hash ?: return null
            if (infoHash.size != 20) return null
            return MagnetLink(infoHash, name, trackers.toList())
        }

        private fun decodeHash(raw: String): ByteArray? {
            val text = raw.trim()
            return when (text.length) {
                40 -> ByteArray(20) { index ->
                    text.substring(index * 2, index * 2 + 2).toIntOrNull(16)?.toByte()
                        ?: return null
                }
                32 -> decodeBase32(text)
                else -> null
            }
        }

        private fun decodeBase32(text: String): ByteArray? {
            var buffer = 0L
            var bits = 0
            val out = ArrayList<Byte>(20)
            for (character in text.uppercase()) {
                val value = when (character) {
                    in 'A'..'Z' -> character - 'A'
                    in '2'..'7' -> character - '2' + 26
                    else -> return null
                }
                buffer = (buffer shl 5) or value.toLong()
                bits += 5
                if (bits >= 8) {
                    bits -= 8
                    out.add(((buffer shr bits) and 0xFF).toByte())
                }
            }
            return if (out.size == 20) out.toByteArray() else null
        }

        private fun percentEncode(text: String): String = buildString {
            text.toByteArray(Charsets.UTF_8).forEach { byte ->
                val value = byte.toInt() and 0xFF
                if (value in 'a'.code..'z'.code || value in 'A'.code..'Z'.code ||
                    value in '0'.code..'9'.code || value == '-'.code || value == '_'.code ||
                    value == '.'.code || value == '~'.code
                ) {
                    append(value.toChar())
                } else {
                    append('%').append("%02X".format(value))
                }
            }
        }

        private fun percentDecode(text: String): String {
            val out = java.io.ByteArrayOutputStream()
            var index = 0
            while (index < text.length) {
                val character = text[index]
                when {
                    character == '%' && index + 2 < text.length -> {
                        val value = text.substring(index + 1, index + 3).toIntOrNull(16)
                        if (value == null) {
                            out.write(character.code)
                        } else {
                            out.write(value)
                            index += 2
                        }
                    }
                    character == '+' -> out.write(' '.code)
                    else -> out.write(character.code)
                }
                index++
            }
            return String(out.toByteArray(), Charsets.UTF_8)
        }
    }
}
