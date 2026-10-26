package com.prism.core

import com.prism.launcher.PrismSettings
import com.prism.launcher.display.WirelessDisplay
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A TAP starts a sender, on the side that was tapped.
 *
 * This is the part of the display protocol a UI cannot prove: the accepting device answers
 * "accepted", and the initiating device must choose between publishing a screen and publishing a
 * page from the mode the user picked. A wrong choice is not an error message; it is a display that
 * never receives anything, which is exactly what a long-press looked like before the direction
 * was made explicit.
 */
class DisplayTapDirectionTest {

    private lateinit var root: File
    private var previousHost: PlatformHost? = null
    private var previousMode: String = ""

    @BeforeTest
    fun setUp() {
        root = kotlin.io.path.createTempDirectory("prism-display-tap").toFile()
        previousHost = PrismPlatform.host
        PrismPlatform.host = JvmHost(rootOverride = root)
        previousMode = PrismSettings.getWirelessDisplayMode()
    }

    @AfterTest
    fun tearDown() {
        WirelessDisplay.disconnect()
        WirelessDisplay.onStartExtend = null
        WirelessDisplay.onStartMirror = null
        PrismSettings.setWirelessDisplayMode(previousMode)
        previousHost?.let { PrismPlatform.host = it }
        root.deleteRecursively()
    }

    @Test
    fun `a tapped display in extend mode receives a page stream, not a screen`() {
        PrismSettings.setWirelessDisplayMode(PrismSettings.WIRELESS_DISPLAY_EXTEND)
        val pageStarted = CountDownLatch(1)
        val screenStarted = AtomicBoolean(false)
        WirelessDisplay.onStartExtend = { pageStarted.countDown() }
        WirelessDisplay.onStartMirror = { screenStarted.set(true) }

        val message = connectToFakeDisplay()

        assertTrue(pageStarted.await(3, TimeUnit.SECONDS), "the page renderer should start")
        assertFalse(screenStarted.get(), "extend must not start a screen capture")
        assertTrue(
            message.contains("next page"),
            "the caller should be told what was asked for, got: " + message,
        )
    }

    @Test
    fun `a tapped display in mimic mode receives the screen stream`() {
        PrismSettings.setWirelessDisplayMode(PrismSettings.WIRELESS_DISPLAY_MIMIC)
        val screenStarted = CountDownLatch(1)
        val pageStarted = AtomicBoolean(false)
        WirelessDisplay.onStartMirror = { screenStarted.countDown() }
        WirelessDisplay.onStartExtend = { pageStarted.set(true) }

        val message = connectToFakeDisplay()

        assertTrue(screenStarted.await(3, TimeUnit.SECONDS), "the screen capture should start")
        assertFalse(pageStarted.get(), "mimic must not render a page")
        assertTrue(
            message.contains("this screen"),
            "the caller should be told what was asked for, got: " + message,
        )
    }

    /**
     * A display that answers the handshake and then sits on the socket.
     *
     * The initiating half is what is being tested; it must start its side as soon as "accepted"
     * arrives, without waiting for a frame or for the other device to say anything else.
     */
    private fun connectToFakeDisplay(): String {
        val listener = ServerSocket(0)
        val answered = CountDownLatch(1)
        val server = thread(name = "fake-prism-display", isDaemon = true) {
            try {
                listener.accept().use { socket ->
                    val reader: BufferedReader = BufferedReader(
                        InputStreamReader(socket.getInputStream(), Charsets.UTF_8)
                    )
                    // Consume the hello; a real display would verify it here.
                    if (reader.readLine() == null) return@use
                    val writer: BufferedWriter = BufferedWriter(
                        OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8)
                    )
                    writer.write("{\"t\":\"accepted\"}\n")
                    writer.flush()
                    answered.countDown()
                    Thread.sleep(1_500L)
                }
            } catch (_: Exception) {
                // The test closes the listener when it is done with it.
            } finally {
                runCatching { listener.close() }
            }
        }

        return try {
            val message = WirelessDisplay.connect(
                WirelessDisplay.Display(
                    name = "Fake display",
                    ip = "127.0.0.1",
                    platform = "test",
                    port = listener.localPort,
                    lastSeen = System.currentTimeMillis(),
                ),
            )
            assertTrue(answered.await(3, TimeUnit.SECONDS), "the fake display should answer")
            message
        } finally {
            runCatching { listener.close() }
            server.join(2_000L)
        }
    }
}
