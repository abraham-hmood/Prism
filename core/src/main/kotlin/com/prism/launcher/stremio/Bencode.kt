package com.prism.launcher.stremio

import java.io.ByteArrayOutputStream

/**
 * Bencode, the encoding BitTorrent uses for `.torrent` files, magnets' resolved metadata and every
 * DHT message.
 *
 * ## Why this is here rather than a dependency
 *
 * The format is four cases and about a hundred lines: an integer, a length-prefixed string, a list
 * and a dictionary. A library would add a dependency to `:core` -- whose build file argues about
 * every one -- to save a morning. The one thing that is genuinely easy to get wrong is that STRINGS
 * ARE BYTES, not text: an info hash, a piece hash and a peer id are binary and will not survive a
 * round trip through a `String` on every platform. So values decode as [ByteArray], and callers
 * that know a field is text ask [string] for it.
 *
 * ## What this deliberately does not do
 *
 * No streaming decoder, no error recovery, no sanitising of absurd lengths. A torrent is either
 * well-formed or it is not, and [decode] throwing is a better outcome than a half-decoded structure
 * that fails later somewhere with less context.
 */
object Bencode {

    /**
     * Decodes one value.
     *
     * Integers come back as [Long], strings as [ByteArray], lists as `List<Any>`, dictionaries as
     * `Map<String, Any>` with insertion order preserved -- which matters for diagnostics, even
     * though the encoding itself is key-sorted.
     */
    fun decode(bytes: ByteArray): Any {
        val reader = Reader(bytes)
        return reader.value()
    }

    fun decode(text: String): Any = decode(text.toByteArray(Charsets.ISO_8859_1))

    /**
     * Decodes one value and reports how many bytes it occupied.
     *
     * Needed for BitTorrent's extension messages, where a bencoded dictionary is followed by raw
     * bytes with no length prefix of their own -- the end of the dictionary is the only boundary
     * there is. Reverse-engineering it by re-encoding the dictionary would be wrong for the same
     * reason the info hash is taken from the wire: the bytes are the contract.
     */
    fun decodePrefix(bytes: ByteArray): Pair<Any, Int> {
        val reader = Reader(bytes)
        val value = reader.value()
        return value to reader.position
    }

    /** Encodes a value. Dictionary keys are sorted, as the format requires. */
    fun encode(value: Any?): ByteArray {
        val out = ByteArrayOutputStream()
        write(out, value)
        return out.toByteArray()
    }

    /**
     * The raw bytes of one value inside a top-level dictionary.
     *
     * THIS EXISTS FOR THE INFO DICTIONARY, and nothing else would justify it. A torrent's identity
     * is the SHA-1 of the info dictionary's ENCODED BYTES, not of its decoded contents: re-encoding
     * a decoded map can produce a different byte sequence (key order, integer spelling) and then
     * every peer rejects the hash. So the slice is taken before decoding, and [TorrentInfo] keeps
     * it.
     */
    fun dictionaryValue(bytes: ByteArray, key: String): ByteArray? {
        val reader = Reader(bytes)
        if (reader.peek() != 'd') return null
        reader.skip()
        while (true) {
            if (reader.peek() == 'e') return null
            val name = String(reader.string(), Charsets.UTF_8)
            val start = reader.position
            reader.value()
            if (name == key) return bytes.copyOfRange(start, reader.position)
        }
    }

    /** Reads a decoded value as text. Empty when it is not a string at all. */
    fun string(value: Any?): String = when (value) {
        is ByteArray -> String(value, Charsets.UTF_8)
        is String -> value
        else -> ""
    }

    /** Reads a decoded value as a number, accepting the integer encodings in the wild. */
    fun long(value: Any?, fallback: Long = 0L): Long = when (value) {
        is Long -> value
        is Int -> value.toLong()
        is ByteArray -> String(value, Charsets.ISO_8859_1).toLongOrNull() ?: fallback
        else -> fallback
    }

    private fun write(out: ByteArrayOutputStream, value: Any?) {
        when (value) {
            null -> out.ascii("0:")
            is ByteArray -> {
                out.ascii(value.size.toString() + ":")
                out.write(value)
            }
            is String -> write(out, value.toByteArray(Charsets.UTF_8))
            is Boolean -> write(out, if (value) 1L else 0L)
            is Int -> write(out, value.toLong())
            is Long -> out.ascii("i" + value + "e")
            is Map<*, *> -> {
                out.ascii("d")
                value.entries
                    .mapNotNull { entry -> (entry.key as? String)?.let { it to entry.value } }
                    .sortedBy { it.first }
                    .forEach { (key, item) ->
                        write(out, key)
                        write(out, item)
                    }
                out.ascii("e")
            }
            is List<*> -> {
                out.ascii("l")
                value.forEach { write(out, it) }
                out.ascii("e")
            }
            else -> throw IllegalArgumentException("Cannot bencode " + value::class.java.name)
        }
    }

    private fun ByteArrayOutputStream.ascii(text: String) {
        write(text.toByteArray(Charsets.ISO_8859_1))
    }

    private const val MAX_DEPTH = 64

    private class Reader(private val bytes: ByteArray) {

        var position: Int = 0
            private set

        fun peek(): Char {
            require(position < bytes.size) { "bencode ended early" }
            return bytes[position].toInt().toChar()
        }

        fun skip() {
            require(position < bytes.size) { "bencode ended early" }
            position++
        }

        fun value(): Any = value(0)

        /**
         * A nesting limit, because a peer controls this input.
         *
         * One extension message can carry four megabytes, and a few megabytes of `l` is enough to
         * overflow the stack of the thread that decodes it. No legitimate torrent nests anywhere
         * near this deep; refusing deeper structures turns a remote crash into a rejected peer.
         */
        private fun value(depth: Int): Any {
            require(depth < MAX_DEPTH) { "bencode nested too deeply" }
            return when (val marker = peek()) {
                'i' -> {
                    skip()
                    readInteger()
                }
                'l' -> {
                    skip()
                    val items = ArrayList<Any>()
                    while (peek() != 'e') items.add(value(depth + 1))
                    skip()
                    items
                }
                'd' -> {
                    skip()
                    val items = LinkedHashMap<String, Any>()
                    while (peek() != 'e') {
                        val key = String(string(), Charsets.UTF_8)
                        items[key] = value(depth + 1)
                    }
                    skip()
                    items
                }
                in '0'..'9' -> string()
                else -> throw IllegalArgumentException("Unexpected '$marker' at byte $position")
            }
        }

        fun string(): ByteArray {
            val start = position
            while (true) {
                require(position < bytes.size) { "bencode string length ended early" }
                if (bytes[position].toInt().toChar() == ':') break
                position++
            }
            val length = String(bytes, start, position - start, Charsets.ISO_8859_1).toIntOrNull()
                ?: throw IllegalArgumentException("Bad bencode string length")
            require(length >= 0 && position + 1 + length <= bytes.size) {
                "bencode string runs past the end"
            }
            position++
            val out = bytes.copyOfRange(position, position + length)
            position += length
            return out
        }

        private fun readInteger(): Long {
            val start = position
            while (true) {
                require(position < bytes.size) { "bencode integer ended early" }
                if (bytes[position].toInt().toChar() == 'e') break
                position++
            }
            val text = String(bytes, start, position - start, Charsets.ISO_8859_1)
            position++
            return text.toLongOrNull() ?: throw IllegalArgumentException("Bad bencode integer '$text'")
        }
    }
}
