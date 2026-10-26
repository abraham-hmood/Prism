package com.prism.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prism.desktop.ui.PrismTheme
import com.prism.desktop.ui.clickableRow
import com.prism.launcher.display.WirelessDisplay
import com.prism.launcher.display.mirror.MirrorStreamClient
import com.prism.launcher.display.mirror.MirrorStreamServer
import com.prism.launcher.display.mirror.newMirrorToken
import org.jetbrains.skia.Image
import java.awt.Graphics2D
import java.awt.GraphicsEnvironment
import java.awt.RenderingHints
import java.awt.Robot
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.concurrent.thread
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * This desktop as both ends of a Prism screen mirror.
 *
 * ## Sending
 *
 * When the local machine is the sender on a display link, a [Robot] grabs the desktop, scales it
 * to a panel-sized frame and publishes JPEGs over [MirrorStreamServer]. It needs no permission
 * prompt and no platform capture API, which is the one place the desktop is simpler than Android.
 *
 * ## Receiving
 *
 * The far side sends a port and a one-session token after the pairing handshake. A reader thread
 * decodes each JPEG and hands the resulting [ImageBitmap] to Compose. Dropping a frame is the
 * correct response to a slow decode: the next one is the live edge.
 *
 * ## Why not show a page any more
 *
 * A page name showed this machine's own Files page when the sender was on Files. The point of
 * screen mirroring is that it is a picture of the other screen, so this module carries pixels.
 */
object DesktopMirror {

    /** The stream being viewed, or null when no display window should exist. */
    var session by mutableStateOf<WirelessDisplay.Mirror?>(null)
        private set

    /** The newest decoded frame. */
    var frame by mutableStateOf<ImageBitmap?>(null)
        private set

    /** A failure worth showing instead of a spinner. */
    var error by mutableStateOf<String?>(null)
        private set

    private var viewerThread: Thread? = null
    @Volatile private var viewerSerial = 0
    private var client: MirrorStreamClient? = null
    private var sender: DesktopMirrorSender? = null

    fun install() {
        WirelessDisplay.onMirror = { mirror ->
            SwingUtilities.invokeLater {
                // The code screen is a separate fullscreen window here, so it has to be cleared
                // explicitly; on Android the code and the mirror are the same activity and the
                // first frame replaces it. An extended page is the other transport and must not
                // sit under a live screen.
                DesktopDisplay.dismissPairing()
                DesktopDisplay.dismiss()
                open(mirror)
            }
        }
        WirelessDisplay.onMirrorStopped = { localWasSender ->
            SwingUtilities.invokeLater {
                if (!localWasSender) {
                    closeViewer()
                    DesktopDisplay.dismiss()
                }
                stopSender()
            }
        }
        WirelessDisplay.onStartMirror = {
            SwingUtilities.invokeLater {
                tellUser("Sharing this screen", "Sending this desktop's screen to " + who() + ".")
                startSender()
            }
        }
        // EXTEND: this desktop renders its own next page and publishes that, instead of sending a
        // page name for the display to rebuild with its own apps and files.
        WirelessDisplay.onStartExtend = {
            SwingUtilities.invokeLater {
                tellUser(
                    "Sharing the next page",
                    "Sending this desktop's next page to " + who() + ".",
                )
                DesktopPageMirror.stop(notify = false)
                DesktopPageMirror.start()
            }
        }
    }

    /**
     * A request on a machine that has no window to show it in.
     *
     * The sender side is silent by nature: it has no display window, only a capture or a page
     * renderer. Without this, a long-press that arrived while this desktop was in the background
     * looked exactly like a request that never arrived. The notifier is best-effort (no tray on
     * some machines), but it is the one non-intrusive place a background share can be announced.
     */
    private fun tellUser(title: String, body: String) {
        runCatching {
            com.prism.core.PrismPlatform.notifier.notify("display", 4201, title, body)
        }
    }

    private fun who(): String =
        WirelessDisplay.connectedTo().ifBlank { "a display" }

    /** Opens a window for [mirror] and starts reading its frames. */
    fun open(mirror: WirelessDisplay.Mirror) {
        // The sender repeats its address after a resize and may repeat it after a reconnect.
        // Replacing an identical stream would restart decoding for no visible reason.
        val current = session
        if (current != null && current.host == mirror.host && current.port == mirror.port &&
            current.token == mirror.token
        ) {
            return
        }
        closeViewer()
        session = mirror
        frame = null
        error = null

        val serial = ++viewerSerial
        val stream = MirrorStreamClient(mirror.host, mirror.port, mirror.token)
        client = stream
        viewerThread = thread(name = "prism-desktop-mirror", isDaemon = true) {
            try {
                stream.connect()
                while (serial == viewerSerial) {
                    val next = stream.readFrame() ?: break
                    // Skia owns the decode because the destination is a Compose image. A second
                    // ImageIO decode would only cost a full frame's worth of CPU.
                    val bitmap = runCatching {
                        Image.makeFromEncoded(next.jpeg).toComposeImageBitmap()
                    }.getOrNull() ?: continue
                    SwingUtilities.invokeLater {
                        if (serial == viewerSerial) {
                            frame = bitmap
                            error = null
                        }
                    }
                }
            } catch (failure: Exception) {
                SwingUtilities.invokeLater {
                    if (serial == viewerSerial) {
                        error = failure.message ?: failure.javaClass.simpleName
                    }
                }
            } finally {
                runCatching { stream.close() }
                SwingUtilities.invokeLater {
                    if (serial == viewerSerial && frame == null && error == null) {
                        error = "The screen share ended."
                    }
                }
            }
        }
    }

    /** Closes the viewer window without touching the control link. */
    fun closeViewer() {
        viewerSerial++
        session = null
        frame = null
        error = null
        runCatching { client?.close() }
        client = null
        viewerThread?.interrupt()
        viewerThread = null
    }

    /** Called when the user closes the mirror window. */
    fun stopAndDisconnect() {
        closeViewer()
        runCatching { WirelessDisplay.disconnect() }
    }

    /** Starts publishing this desktop's screen. Safe to call more than once. */
    fun startSender() {
        if (sender != null) return
        if (!WirelessDisplay.isSending()) {
            WirelessDisplay.reportError("The display link ended before this screen could be shared.")
            return
        }
        val started = DesktopMirrorSender()
        sender = started
        started.start()
    }

    fun stopSender() {
        sender?.close()
        sender = null
        DesktopPageMirror.stop()
    }
}

/**
 * The desktop's screen, as a stream.
 *
 * AWT's [Robot] is used rather than a platform capture API because it is the one capture path
 * that exists on every JVM desktop. The frame is scaled once, before JPEG encoding, so a 4K
 * display does not cost a 4K encode for a phone-sized panel.
 */
private class DesktopMirrorSender {

    private val token = newMirrorToken()
    private val server = MirrorStreamServer(token)
    private var running = true
    private var worker: Thread? = null

    fun start() {
        try {
            server.start()
        } catch (failure: Exception) {
            com.prism.core.PrismPlatform.log.warn(
                "PrismDisplay",
                "Could not open the mirror stream: " + failure.message,
            )
            WirelessDisplay.reportError("The other device could not start sharing its screen.")
            close()
            return
        }

        worker = thread(name = "prism-desktop-mirror-send", isDaemon = true) {
            // The flag is a field, not a local. As a local it shadowed the one close() reads, so
            // close() always believed nothing had been announced and never told the far side the
            // stream was over -- leaving a closed port cached as this device's live mirror.
            try {
                val robot = Robot()
                val screen = GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .defaultScreenDevice
                    .defaultConfiguration
                    .bounds
                while (running) {
                    val started = System.nanoTime()
                    val captured = robot.createScreenCapture(screen)
                    val scaled = scale(captured)
                    val jpeg = encode(scaled)
                    if (jpeg == null) continue
                    server.sendFrame(scaled.width, scaled.height, jpeg)
                    if (!announced) {
                        announced = true
                        WirelessDisplay.announceMirror(
                            server.port,
                            token,
                            scaled.width,
                            scaled.height,
                        )
                    }
                    val spent = (System.nanoTime() - started) / 1_000_000L
                    val wait = FRAME_INTERVAL_MS - spent
                    if (wait > 0) Thread.sleep(wait)
                }
            } catch (failure: Exception) {
                if (running) {
                    com.prism.core.PrismPlatform.log.warn(
                        "PrismDisplay",
                        "Desktop mirror stopped: " + failure.message,
                    )
                    WirelessDisplay.reportError(
                        "The other device could not capture its screen: " +
                            (failure.message ?: failure.javaClass.simpleName),
                    )
                }
            }
        }
    }

    private fun scale(source: BufferedImage): BufferedImage = scaleForMirror(source, MAX_LONG_EDGE)

    private fun encode(frame: BufferedImage): ByteArray? = runCatching {
        val bytes = ByteArrayOutputStream(192 * 1024)
        ImageIO.write(frame, "jpg", bytes)
        bytes.toByteArray().takeIf { it.isNotEmpty() }
    }.getOrNull()

    fun close() {
        running = false
        server.close()
        worker?.interrupt()
        worker = null
        if (announced) runCatching { WirelessDisplay.stopMirror() }
    }

    @Volatile
    private var announced = false

    companion object {
        private const val MAX_LONG_EDGE = 1280
        private const val FRAME_INTERVAL_MS = 80L
    }
}

/**
 * The fullscreen window a received screen lives in.
 *
 * The image is drawn fitted rather than stretched: a phone in landscape and a desktop in 16:9 are
 * different shapes, and letterboxing is the honest way to show one inside the other.
 */
@Composable
fun DesktopMirrorWindow(session: WirelessDisplay.Mirror, onClose: () -> Unit) {
    PrismTheme {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            val bitmap = DesktopMirror.frame
            if (bitmap != null) {
                FittedScreen(bitmap)
            } else if (DesktopMirror.error == null) {
                CircularProgressIndicator(
                    color = Color(0x88FFFFFF),
                    modifier = Modifier.align(Alignment.Center).size(30.dp),
                )
            }

            if (bitmap == null) {
                Text(
                    text = DesktopMirror.error
                        ?: ("Connecting to " + session.from.ifBlank { "the other device" } + "…"),
                    color = Color(0xAAFFFFFF),
                    fontSize = 13.sp,
                    modifier = Modifier.align(Alignment.Center).padding(top = 72.dp),
                )
            }

            Surface(
                color = Color(0x66000000),
                shape = CircleShape,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(14.dp)
                    .size(34.dp)
                    .clickableRow { onClose() },
            ) {
                Icon(
                    Icons.Filled.Close,
                    "Stop mirroring",
                    tint = Color(0xCCFFFFFF),
                    modifier = Modifier.padding(8.dp).fillMaxSize(),
                )
            }

            Text(
                text = if (session.from.isBlank()) "Prism display" else "From " + session.from,
                color = Color(0x88FFFFFF),
                fontSize = 11.sp,
                modifier = Modifier.align(Alignment.BottomStart).padding(14.dp),
            )
        }
    }
}

/** The picture, fitted inside whatever window is showing it. */
@Composable
fun FittedScreen(bitmap: ImageBitmap) {
    Canvas(Modifier.fillMaxSize()) {
        val scale = min(size.width / bitmap.width, size.height / bitmap.height)
        val width = bitmap.width * scale
        val height = bitmap.height * scale
        drawImage(
            image = bitmap,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(bitmap.width, bitmap.height),
            dstOffset = IntOffset(
                ((size.width - width) / 2f).roundToInt(),
                ((size.height - height) / 2f).roundToInt(),
            ),
            dstSize = IntSize(width.roundToInt(), height.roundToInt()),
            filterQuality = FilterQuality.Medium,
        )
    }
}

/**
 * This machine's own screen, for a display attached to it.
 *
 * A monitor or television cabled to the desktop is not a remote Prism device: there is no control
 * link and no second process. It still wants a picture of this machine's screen, so the primary
 * screen is captured and drawn into the window on that monitor. Capturing only the default screen
 * is also what stops the display from mirroring itself into infinity.
 */
object DesktopScreenMirror {

    var frame by mutableStateOf<ImageBitmap?>(null)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    @Volatile
    private var running = false

    private var worker: Thread? = null

    fun start() {
        if (running) return
        running = true
        error = null

        worker = thread(name = "prism-desktop-screen", isDaemon = true) {
            val robot = try {
                Robot()
            } catch (failure: Exception) {
                SwingUtilities.invokeLater {
                    if (running) error = failure.message ?: failure.javaClass.simpleName
                }
                running = false
                return@thread
            }
            val bounds = GraphicsEnvironment.getLocalGraphicsEnvironment()
                .defaultScreenDevice
                .defaultConfiguration
                .bounds

            while (running) {
                val started = System.nanoTime()
                val captured = runCatching { robot.createScreenCapture(bounds) }.getOrNull()
                if (captured != null) {
                    val bitmap = scaleForMirror(captured, LOCAL_LONG_EDGE).toComposeImageBitmap()
                    SwingUtilities.invokeLater {
                        if (running) frame = bitmap
                    }
                }
                val spent = (System.nanoTime() - started) / 1_000_000L
                val wait = LOCAL_FRAME_INTERVAL_MS - spent
                if (wait > 0) runCatching { Thread.sleep(wait) }
            }
        }
    }

    fun stop() {
        running = false
        worker?.interrupt()
        worker = null
        frame = null
        error = null
    }

    private const val LOCAL_LONG_EDGE = 1920

    /** Eleven frames a second: a display on a cable is for watching, not for twitch input. */
    private const val LOCAL_FRAME_INTERVAL_MS = 90L
}

/** Scales a capture down so a 4K screen does not cost a 4K frame to publish. */
private fun scaleForMirror(source: BufferedImage, maxLongEdge: Int): BufferedImage {
    val longEdge = maxOf(source.width, source.height)
    val factor = min(1.0, maxLongEdge.toDouble() / longEdge.coerceAtLeast(1))
    val width = (source.width * factor).roundToInt().coerceAtLeast(2)
    val height = (source.height * factor).roundToInt().coerceAtLeast(2)
    val target = BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR)
    val graphics: Graphics2D = target.createGraphics()
    try {
        graphics.setRenderingHint(
            RenderingHints.KEY_INTERPOLATION,
            RenderingHints.VALUE_INTERPOLATION_BILINEAR,
        )
        graphics.drawImage(source, 0, 0, width, height, null)
    } finally {
        graphics.dispose()
    }
    return target
}
