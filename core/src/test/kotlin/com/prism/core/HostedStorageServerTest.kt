package com.prism.core

import com.prism.launcher.storage.HostedStorage
import com.prism.launcher.storage.HostedStorageServer
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HostedStorageServerTest {

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun request(port: Int, raw: String): String {
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5_000
            socket.getOutputStream().write(raw.toByteArray(Charsets.ISO_8859_1))
            socket.getOutputStream().flush()
            return socket.getInputStream().readBytes().toString(Charsets.UTF_8)
        }
    }

    @Test
    fun servesListingsFilesUploadsAndPropfind() {
        val root = File.createTempFile("prism-host", "").let {
            it.delete()
            it.mkdirs()
            it
        }
        File(root, "hello.txt").writeText("hello from prism")
        val port = freePort()
        val storage = HostedStorage(
            name = "Test share",
            typeId = "http",
            folderPath = root.absolutePath,
            port = port,
        )
        assertNull(HostedStorageServer.start(storage))
        try {
            val listing = request(port, "GET / HTTP/1.1\r\nHost: localhost\r\n\r\n")
            assertTrue(listing.startsWith("HTTP/1.1 200"), listing.take(80))
            assertTrue(listing.contains("hello.txt"), "listing should name the file")

            val file = request(port, "GET /hello.txt HTTP/1.1\r\nHost: localhost\r\n\r\n")
            assertTrue(file.contains("hello from prism"), "file body missing")

            val put = request(
                port,
                "PUT /uploaded.txt HTTP/1.1\r\nHost: localhost\r\nContent-Length: 5\r\n\r\nadded",
            )
            assertTrue(put.startsWith("HTTP/1.1 201") || put.startsWith("HTTP/1.1 204"), put.take(60))
            assertEquals("added", File(root, "uploaded.txt").readText())

            val propfind = request(
                port,
                "PROPFIND / HTTP/1.1\r\nHost: localhost\r\nDepth: 1\r\n\r\n",
            )
            assertTrue(propfind.startsWith("HTTP/1.1 207"), propfind.take(60))
            assertTrue(propfind.contains("<D:multistatus"), "WebDAV body missing")
        } finally {
            HostedStorageServer.stop(storage.id)
            root.deleteRecursively()
        }
    }

    @Test
    fun refusesToEscapeTheSharedFolder() {
        val parent = File.createTempFile("prism-host-parent", "").let {
            it.delete()
            it.mkdirs()
            it
        }
        val root = File(parent, "share").apply { mkdirs() }
        File(parent, "secret.txt").writeText("do not serve")
        val port = freePort()
        val storage = HostedStorage(
            name = "Escape test",
            typeId = "http",
            folderPath = root.absolutePath,
            port = port,
        )
        assertNull(HostedStorageServer.start(storage))
        try {
            val escape = request(port, "GET /../secret.txt HTTP/1.1\r\nHost: localhost\r\n\r\n")
            assertTrue(
                escape.startsWith("HTTP/1.1 403") || escape.startsWith("HTTP/1.1 404"),
                "path traversal should be refused: ${escape.take(60)}",
            )
            assertTrue(!escape.contains("do not serve"), "the secret leaked out of the share")
        } finally {
            HostedStorageServer.stop(storage.id)
            parent.deleteRecursively()
        }
    }
}
