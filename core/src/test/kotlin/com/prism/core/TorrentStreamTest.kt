package com.prism.core

import com.prism.launcher.stremio.StreamServer
import com.prism.launcher.stremio.StreamSource
import com.prism.launcher.stremio.TorrentFile
import com.prism.launcher.stremio.TorrentInfo
import com.prism.launcher.stremio.TorrentStorage
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The loopback stream, exercised over a real socket.
 *
 * This is the piece the player actually talks to, so it earns a test even though the torrent
 * protocol around it does not: a server that mishandles `Range` produces a video that starts and
 * then cannot seek, which looks exactly like a broken source. A real socket rather than a mocked
 * connection because the header parsing and the blocking body write are the parts under test.
 */
class TorrentStreamTest {

    private val payload = ByteArray(100) { it.toByte() }

    private fun source(): StreamSource = object : StreamSource {
        override val length: Long = payload.size.toLong()
        override fun contentType(): String = "video/mp4"
        override fun read(offset: Long, buffer: ByteArray, bufferOffset: Int, max: Int, timeoutMs: Long): Int {
            if (offset >= payload.size) return -1
            val count = minOf(max.toLong(), payload.size - offset).toInt()
            payload.copyInto(buffer, bufferOffset, offset.toInt(), offset.toInt() + count)
            return count
        }
    }

    @Test
    fun `serves a whole file and a byte range`() {
        val server = StreamServer(source(), "test-token", "clip.mp4")
        try {
            val whole = connection(server.url, null)
            assertEquals(200, whole.responseCode)
            assertEquals("video/mp4", whole.getHeaderField("Content-Type"))
            assertEquals("bytes", whole.getHeaderField("Accept-Ranges"))
            assertEquals(payload.toList(), whole.inputStream.readBytes().toList())

            val ranged = connection(server.url, "bytes=10-19")
            assertEquals(206, ranged.responseCode)
            assertEquals("bytes 10-19/100", ranged.getHeaderField("Content-Range"))
            assertEquals(payload.copyOfRange(10, 20).toList(), ranged.inputStream.readBytes().toList())

            val openEnded = connection(server.url, "bytes=90-")
            assertEquals(206, openEnded.responseCode)
            assertEquals(payload.copyOfRange(90, 100).toList(), openEnded.inputStream.readBytes().toList())

            val wrongToken = connection(server.url.replace("test-token", "other"), null)
            assertEquals(404, wrongToken.responseCode)
        } finally {
            server.close()
        }
    }

    @Test
    fun `a source that is briefly not ready is waited for, not truncated`() {
        // The torrent storage returns 0 while a piece is still being fetched. A server that
        // treated that as the end of the file would promise 100 bytes and deliver none, which a
        // player reports as a broken source rather than a slow one.
        var firstRead = true
        val waiting = object : StreamSource {
            override val length: Long = payload.size.toLong()
            override fun contentType(): String = "video/mp4"
            override fun read(offset: Long, buffer: ByteArray, bufferOffset: Int, max: Int, timeoutMs: Long): Int {
                if (firstRead) {
                    firstRead = false
                    return 0
                }
                if (offset >= payload.size) return -1
                val count = minOf(max.toLong(), payload.size - offset).toInt()
                payload.copyInto(buffer, bufferOffset, offset.toInt(), offset.toInt() + count)
                return count
            }
        }

        val server = StreamServer(waiting, "wait-token", "clip.mp4")
        try {
            val response = connection(server.url, null)
            assertEquals(200, response.responseCode)
            assertEquals(payload.toList(), response.inputStream.readBytes().toList())
        } finally {
            server.close()
        }
    }

    @Test
    fun `storage maps a file inside a torrent and waits for pieces`() {
        val directory = Files.createTempDirectory("prism-torrent-test").toFile()
        val target = File(directory, "episode.mkv")

        val info = TorrentInfo(
            infoHash = ByteArray(20),
            name = "pack",
            pieceLength = 4,
            pieceHashes = List(3) { ByteArray(20) },
            files = listOf(
                TorrentFile(0, "sample.mkv", 4, 0),
                TorrentFile(1, "episode.mkv", 8, 4),
            ),
            trackers = emptyList(),
        )
        val file = info.files[1]
        val storage = TorrentStorage(target, info, file)

        try {
            // Piece 0 belongs entirely to the sample and must not leak into this file.
            storage.writePiece(0, byteArrayOf(9, 9, 9, 9))
            assertEquals(0, storage.read(0, ByteArray(4), 0, 4, 50))

            // Piece 1 starts at torrent offset 4, which is this file's offset 0.
            storage.writePiece(1, byteArrayOf(1, 2, 3, 4))
            val buffer = ByteArray(8)
            assertEquals(4, storage.read(0, buffer, 0, 8, 1_000))
            assertTrue(buffer.copyOfRange(0, 4).contentEquals(byteArrayOf(1, 2, 3, 4)))

            // The read is clipped to piece 2, which has not arrived.
            assertEquals(0, storage.read(4, buffer, 0, 8, 50))

            storage.writePiece(2, byteArrayOf(5, 6, 7, 8, 9, 10, 11, 12))
            assertEquals(4, storage.read(4, buffer, 0, 8, 1_000))
            assertTrue(buffer.copyOfRange(0, 4).contentEquals(byteArrayOf(5, 6, 7, 8)))
            assertEquals(8, target.length())
        } finally {
            storage.close()
            directory.deleteRecursively()
        }
    }

    private fun connection(url: String, range: String?): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5_000
            readTimeout = 5_000
            if (range != null) setRequestProperty("Range", range)
        }
}
