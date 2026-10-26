package com.prism.core

import com.prism.launcher.display.mirror.MirrorStreamClient
import com.prism.launcher.display.mirror.MirrorStreamServer
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The screen stream is the only thing between a sender and a display: if a frame's length or the
 * session token is wrong the receiver draws black, and a black display looks exactly like a link
 * that never connected. These tests speak the protocol from both ends.
 */
class MirrorStreamTest {

    @Test
    fun `frames arrive whole and in order`() {
        val server = MirrorStreamServer("s3cret")
        try {
            val port = server.start()
            val client = MirrorStreamClient("127.0.0.1", port, "s3cret")
            try {
                client.connect()
                awaitViewer(server)

                // One at a time: the sender deliberately keeps only the newest two frames when a
                // viewer falls behind, so writing a burst would test the drop rather than the frame.
                repeat(4) { index ->
                    val expected = ByteArray(32) { (it + index * 7).toByte() }
                    server.sendFrame(590, 1280, expected)

                    val frame = assertNotNull(client.readFrame(), "a sent frame should arrive")
                    assertEquals(590, frame.width)
                    assertEquals(1280, frame.height)
                    assertContentEquals(expected, frame.jpeg)
                }
            } finally {
                client.close()
            }
        } finally {
            server.close()
        }
    }

    @Test
    fun `a session with the wrong token is refused and the server stays usable`() {
        val server = MirrorStreamServer("s3cret")
        try {
            val port = server.start()
            val stranger = MirrorStreamClient("127.0.0.1", port, "guessed")
            assertFailsWith<IllegalStateException> { stranger.connect() }

            val viewer = MirrorStreamClient("127.0.0.1", port, "s3cret")
            try {
                viewer.connect()
                awaitViewer(server)
                server.sendFrame(10, 10, byteArrayOf(1, 2, 3))
                assertContentEquals(byteArrayOf(1, 2, 3), assertNotNull(viewer.readFrame()).jpeg)
            } finally {
                viewer.close()
            }
        } finally {
            server.close()
        }
    }

    @Test
    fun `a second viewer replaces the first rather than stacking`() {
        val server = MirrorStreamServer("s3cret")
        try {
            val port = server.start()
            val first = MirrorStreamClient("127.0.0.1", port, "s3cret")
            val second = MirrorStreamClient("127.0.0.1", port, "s3cret")
            try {
                first.connect()
                awaitViewer(server)
                second.connect()
                // The server installs the replacement before it answers, so once both connects
                // have returned the second socket owns the stream.
                Thread.sleep(120L)
                server.sendFrame(10, 10, byteArrayOf(9))

                assertEquals(1, server.viewers())
                assertContentEquals(byteArrayOf(9), assertNotNull(second.readFrame()).jpeg)

                // The replaced socket is closed by the sender; a viewer that never reads from it
                // would otherwise wait forever for a stream that is no longer its own.
                val ended = thread(name = "prism-mirror-ended") { assertNull(first.readFrame()) }
                ended.join(2_000L)
                assertTrue(!ended.isAlive, "the replaced viewer should see the stream end")
            } finally {
                first.close()
                second.close()
            }
        } finally {
            server.close()
        }
    }

    /** The greeting can be written before the server installs its writer; wait for the install. */
    private fun awaitViewer(server: MirrorStreamServer) {
        val deadline = System.currentTimeMillis() + 2_000L
        while (server.viewers() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10L)
        }
        assertEquals(1, server.viewers(), "the server should have admitted the viewer")
    }
}
