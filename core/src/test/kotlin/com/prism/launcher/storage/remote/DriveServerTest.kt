package com.prism.launcher.storage.remote

import java.io.File
import java.io.IOException
import java.net.Socket
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DriveServerTest {

    private lateinit var root: File
    /** What trusted devices reach: encrypted with the wallet key. */
    private lateinit var secure: DriveServer
    /** What the desktop's gateway is: loopback-only plain WebDAV for the OS's file explorer. */
    private lateinit var loopback: DriveServer
    private val token = "0123456789abcdef0123456789abcdef"
    private val walletKey = ByteArray(32) { (it * 7 + 3).toByte() }
    private val otherDeviceKey = ByteArray(32) { (it * 11 + 5).toByte() }
    private var securePort = -1
    private var loopbackPort = -1

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("prism-drive").toFile()
        File(root, "docs").mkdirs()
        File(root, "docs/hello world.txt").writeText("Hello from a drive")
        val drive = DeviceDrive("C", "Local Disk (C:)", DriveKind.INTERNAL, 100, 50, false)
        val access = FileDriveAccess(root)
        // Two trusted devices' pair keys: either may connect, and nothing else can.
        secure = DriveServer(
            { token }, { listOf(drive) }, { id -> if (id == "C") access else null }, "secure",
            { listOf(otherDeviceKey, walletKey) },
        )
        securePort = secure.start("127.0.0.1", 18390..18410)
        loopback = DriveServer({ token }, { listOf(drive) }, { id -> if (id == "C") access else null }, "loopback")
        loopbackPort = loopback.start("127.0.0.1", 18411..18430)
    }

    @AfterTest
    fun tearDown() {
        secure.stop()
        loopback.stop()
        root.deleteRecursively()
    }

    private fun client(key: ByteArray = walletKey) =
        RemoteDriveAccess({ "127.0.0.1" to securePort }, { key }, token, "C", readOnly = false)

    @Test
    fun clientListsReadsWritesMovesAndDeletesThroughTheEncryptedChannel() {
        val drive = client()
        assertEquals(listOf("docs"), drive.list("/").map { it.name })
        assertEquals("Hello from a drive", drive.open("/docs/hello world.txt").use { it.readBytes().decodeToString() })
        assertEquals("from a drive", drive.open("/docs/hello world.txt", 6).use { it.readBytes().decodeToString() })

        val payload = ByteArray(700_000) { (it % 251).toByte() }
        drive.write("/docs/new/blob.bin", payload.inputStream(), payload.size.toLong())
        assertTrue(File(root, "docs/new/blob.bin").readBytes().contentEquals(payload))
        assertTrue(drive.open("/docs/new/blob.bin").use { it.readBytes() }.contentEquals(payload))
        // Unknown length goes chunked.
        drive.write("/chunked.bin", payload.inputStream(), -1)
        assertTrue(File(root, "chunked.bin").readBytes().contentEquals(payload))

        drive.mkdir("/made")
        drive.move("/chunked.bin", "/made/moved.bin")
        assertTrue(File(root, "made/moved.bin").isFile)
        drive.delete("/made")
        assertNull(drive.stat("/made"))
        assertEquals(payload.size.toLong(), drive.stat("/docs/new/blob.bin")?.size)
    }

    @Test
    fun eachTrustedDeviceConnectsWithItsOwnPairKey() {
        assertEquals(listOf("docs"), client(otherDeviceKey).list("/").map { it.name })
        assertEquals(listOf("docs"), client(walletKey).list("/").map { it.name })
    }

    @Test
    fun aDeviceWithoutTheWalletKeyGetsNothing() {
        val stranger = client(ByteArray(32) { 1 })
        assertFailsWith<IOException> { stranger.list("/") }
        assertFalse(File(root, "x.bin").exists())
        assertFailsWith<IOException> { stranger.write("/x.bin", ByteArray(10).inputStream(), 10) }
        assertFalse(File(root, "x.bin").exists())

        // Plain HTTP, even with the right token, is not answered at all.
        val plain = raw(securePort, "GET /$token/C/docs/hello%20world.txt HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n")
        assertFalse(plain.contains("Hello from a drive"), plain)
        assertFalse(plain.startsWith("HTTP/"), plain)
    }

    @Test
    fun aServerWithoutAKeyRefusesToListenOnTheNetwork() {
        val unsafe = DriveServer({ token }, { emptyList() }, { null }, "unsafe")
        assertEquals(-1, unsafe.start("0.0.0.0", 18431..18440))
        unsafe.stop()
    }

    @Test
    fun webDavListingAndTokenGate() {
        val listing = raw(loopbackPort, "PROPFIND /$token/C/docs/ HTTP/1.1\r\nHost: x\r\nDepth: 1\r\nConnection: close\r\n\r\n")
        assertTrue(listing.startsWith("HTTP/1.1 207"), listing)
        assertTrue(listing.contains("<D:href>/$token/C/docs/hello%20world.txt</D:href>"), listing)
        assertTrue(listing.contains("<D:getcontentlength>18</D:getcontentlength>"), listing)

        val drives = raw(loopbackPort, "PROPFIND /$token/ HTTP/1.1\r\nHost: x\r\nDepth: 1\r\nConnection: close\r\n\r\n")
        assertTrue(drives.contains("<D:href>/$token/C/</D:href>"), drives)

        val outsider = raw(loopbackPort, "GET /wrong-token/C/docs/hello%20world.txt HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n")
        assertTrue(outsider.startsWith("HTTP/1.1 404"), outsider)
        val escape = raw(loopbackPort, "GET /$token/C/../../etc/passwd HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n")
        assertTrue(escape.startsWith("HTTP/1.1 404"), escape)
    }

    @Test
    fun keepAliveServesSeveralRequestsOnOneConnection() {
        val both = raw(
            loopbackPort,
            "HEAD /$token/C/docs/hello%20world.txt HTTP/1.1\r\nHost: x\r\n\r\n" +
                "GET /$token/C/docs/hello%20world.txt HTTP/1.1\r\nHost: x\r\nRange: bytes=0-4\r\nConnection: close\r\n\r\n",
        )
        assertTrue(both.startsWith("HTTP/1.1 200"), both)
        assertTrue(both.contains("HTTP/1.1 206"), both)
        assertTrue(both.endsWith("Hello"), both)
    }

    private fun raw(port: Int, request: String): String = Socket("127.0.0.1", port).use { socket ->
        socket.soTimeout = 5_000
        socket.getOutputStream().write(request.toByteArray())
        socket.getOutputStream().flush()
        runCatching { socket.getInputStream().readBytes().decodeToString() }.getOrDefault("")
    }
}
