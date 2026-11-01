package com.prism.core

import com.prism.launcher.stremio.Bencode
import com.prism.launcher.stremio.RangeHeader
import com.prism.launcher.stremio.MagnetLink
import com.prism.launcher.stremio.TorrentFile
import com.prism.launcher.stremio.TorrentInfo
import com.prism.launcher.stremio.TorrentPieces
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The parts of the torrent client that can be checked without a network.
 *
 * Deliberately focused on the two places where a mistake is silent rather than loud: the encoding
 * (where a byte string decoded as text changes an info hash and every peer refuses it) and the
 * file-to-piece arithmetic (where an off-by-one plays the wrong episode from a season pack). The
 * peer protocol and the DHT need real peers; these do not.
 */
class TorrentParsingTest {

    private fun hashes(count: Int): ByteArray = ByteArray(count * 20) { index ->
        ((index * 7 + count) and 0xFF).toByte()
    }

    private fun infoBytes(
        name: String = "hero.mkv",
        length: Long = 12,
        pieceLength: Long = 4,
        pieceCount: Int = 3,
        files: List<Map<String, Any>>? = null,
    ): ByteArray {
        val info = LinkedHashMap<String, Any>()
        info["name"] = name
        info["pieceLength"] = pieceLength
        info["pieces"] = hashes(pieceCount)
        if (files == null) {
            info["length"] = length
        } else {
            info["files"] = files
        }
        return Bencode.encode(info)
    }

    private fun torrent(info: ByteArray, extra: Map<String, Any> = emptyMap()): ByteArray {
        val root = LinkedHashMap<String, Any>()
        root["announce"] = "http://tracker.example/announce"
        root.putAll(extra)
        root["info"] = Bencode.decode(info)
        return Bencode.encode(root)
    }

    @Test
    fun `bencode round trips binary strings unchanged`() {
        val binary = ByteArray(20) { it.toByte() }
        val encoded = Bencode.encode(mapOf("id" to binary, "n" to 42L, "s" to "text"))
        val decoded = Bencode.decode(encoded) as Map<*, *>

        assertContentEquals(binary, decoded["id"] as ByteArray)
        assertEquals(42L, decoded["n"])
        assertEquals("text", Bencode.string(decoded["s"]))
    }

    @Test
    fun `info hash is taken from the encoded bytes, not the decoded map`() {
        val info = infoBytes()
        val parsed = assertNotNull(TorrentInfo.fromTorrentFile(torrent(info)))

        // The torrent file re-encodes the dictionary, so the hash is over those canonical bytes --
        // which is exactly the property being tested: the hash follows the encoding.
        val expected = MessageDigest.getInstance("SHA-1").digest(Bencode.encode(Bencode.decode(info)))
        assertContentEquals(expected, parsed.infoHash)
        assertEquals("hero.mkv", parsed.name)
        assertEquals(3, parsed.pieceCount)
        assertEquals(12, parsed.totalLength)
        assertEquals("http://tracker.example/announce", parsed.trackers.single())
        assertEquals("hero.mkv", parsed.files.single().path)
        assertEquals(0, parsed.files.single().offset)
    }

    @Test
    fun `last piece is short and file selection prefers the named file`() {
        val info = infoBytes(
            name = "season",
            pieceLength = 4,
            pieceCount = 3,
            files = listOf(
                mapOf("length" to 4L, "path" to listOf("sample.mkv")),
                mapOf("length" to 8L, "path" to listOf("Season 1", "episode.mkv")),
            ),
        )
        val parsed = assertNotNull(TorrentInfo.fromTorrentFile(torrent(info)))

        assertEquals(12, parsed.totalLength)
        assertEquals(4, parsed.pieceSize(0))
        assertEquals(4, parsed.pieceSize(2))

        val episode = assertNotNull(parsed.selectFile(1, null))
        assertEquals(4, episode.offset)
        assertEquals("Season 1/episode.mkv", episode.path)

        // The filename wins over a fileIdx that disagrees, because a pack can be re-packed and an
        // index then points at the sample.
        assertEquals("sample.mkv", assertNotNull(parsed.selectFile(1, "sample.mkv")).name)

        // An unusable index still resolves -- the largest file is a better answer than none.
        assertEquals("episode.mkv", assertNotNull(parsed.selectFile(99, null)).name)

        val range = parsed.piecesFor(episode)
        assertEquals(1, range.first)
        assertEquals(2, range.last)
    }

    @Test
    fun `magnet parses hex and base32 hashes`() {
        val hash = ByteArray(20) { it.toByte() }
        val hex = hash.joinToString("") { "%02x".format(it) }
        val fromHex = assertNotNull(MagnetLink.parse("magnet:?xt=urn:btih:$hex&dn=Test&tr=udp://x:1"))

        assertContentEquals(hash, fromHex.infoHash)
        assertEquals("Test", fromHex.name)
        assertEquals(listOf("udp://x:1"), fromHex.trackers)

        val fromBase32 = assertNotNull(MagnetLink.parse("magnet:?xt=urn:btih:" + base32(hash)))
        assertContentEquals(hash, fromBase32.infoHash)

        assertNull(MagnetLink.parse("magnet:?dn=nothing"))
    }

    @Test
    fun `range headers resolve every player form`() {
        val length = 1_000L

        assertEquals(0L, RangeHeader.parse("bytes=0-99", length)?.start)
        assertEquals(99L, RangeHeader.parse("bytes=0-99", length)?.endInclusive)
        assertEquals(900L, RangeHeader.parse("bytes=-100", length)?.start)
        assertEquals(999L, RangeHeader.parse("bytes=900-", length)?.endInclusive)
        assertEquals(999L, RangeHeader.parse("bytes=0-99999", length)?.endInclusive)
        assertEquals(0L, RangeHeader.parse("bytes=0-9,20-29", length)?.start)

        assertNull(RangeHeader.parse(null, length))
        assertNull(RangeHeader.parse("bytes=1000-", length))
        assertNull(RangeHeader.parse("bytes=abc-def", length))
        assertNull(RangeHeader.parse("items=0-1", length))
    }

    @Test
    fun `pieces are handed out in order and can be released`() {
        val info = infoBytes()
        val parsed = assertNotNull(TorrentInfo.fromTorrentFile(torrent(info)))
        val file = TorrentFile(0, "hero.mkv", 12, 0)
        val pieces = TorrentPieces(parsed, file)

        assertEquals(0, pieces.take())
        assertEquals(1, pieces.take())
        pieces.release(1)
        assertEquals(1, pieces.take())
        pieces.complete(1)
        assertEquals(2, pieces.take())
        pieces.complete(2)
        pieces.complete(0)

        assertTrue(pieces.isDone())
        assertEquals(3, pieces.snapshot().havePieces)
    }

    private fun base32(bytes: ByteArray): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val out = StringBuilder()
        var buffer = 0
        var bits = 0
        bytes.forEach { byte ->
            buffer = (buffer shl 8) or (byte.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                out.append(alphabet[(buffer shr bits) and 31])
            }
        }
        if (bits > 0) out.append(alphabet[(buffer shl (5 - bits)) and 31])
        return out.toString()
    }
}
