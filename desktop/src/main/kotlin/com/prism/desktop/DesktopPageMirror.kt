package com.prism.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.Density
import com.prism.desktop.ui.DragController
import com.prism.desktop.ui.LocalDragController
import com.prism.desktop.ui.LocalIconCache
import com.prism.desktop.ui.PageBody
import com.prism.desktop.ui.PageId
import com.prism.desktop.ui.PrismTheme
import com.prism.desktop.ui.SlotPageForDisplay
import com.prism.launcher.SlotAssignment
import com.prism.launcher.display.WirelessDisplay
import com.prism.launcher.display.mirror.MirrorStreamServer
import com.prism.launcher.display.mirror.newMirrorToken
import org.jetbrains.skia.EncodedImageFormat
import java.awt.GraphicsEnvironment
import kotlin.concurrent.thread
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * EXTEND, drawn by the machine that owns the page.
 *
 * The page a display shows has to be the SENDER's page. Sending a page name made the receiver
 * build its own drawer -- its own apps, its own files -- which looked plausible and was wrong in
 * every detail the user cared about. Android already solved this by rendering its next page and
 * publishing it as a stream; this is the desktop half of the same idea.
 *
 * The page is composed offscreen with the same `SlotPageForDisplay` the launcher and the
 * system-display window use, so an extended page cannot drift away from the page it claims to be.
 * No window is opened here: the receiver already has one, and it is showing a picture.
 *
 * ## Cost
 *
 * A page that is extended is not the page the sender is touching, so there is nothing to follow
 * frame by frame. It is redrawn once a second, and the scene is rebuilt only when the pager
 * actually moves. That keeps clocks and counters alive without a 60 fps composition on a page
 * nobody is interacting with.
 */
object DesktopPageMirror {

    private const val TAG = "PrismDisplay"
    private const val MAX_LONG_EDGE = 1280
    private const val JPEG_QUALITY = 72
    private const val REFRESH_MS = 1_000L

    /**
     * One page stream. A class rather than loose fields so a restart cannot mix the old run's
     * worker, port and "announced" flag with the new one. That race showed up as a replacement
     * stream telling the viewer it had ended, which closed the display window for a second.
     */
    private class Session(val token: String, val stream: MirrorStreamServer) {
        @Volatile var announced = false
        var worker: Thread? = null
    }

    @Volatile
    private var session: Session? = null

    fun isRunning(): Boolean = session != null

    /** Starts publishing the next page. Safe to call more than once. */
    fun start() {
        if (session != null) return
        if (!WirelessDisplay.isSending()) {
            WirelessDisplay.reportError("The display link ended before this page could be shared.")
            return
        }

        val bounds = GraphicsEnvironment.getLocalGraphicsEnvironment()
            .defaultScreenDevice
            .defaultConfiguration
            .bounds
        val factor = min(
            1.0,
            MAX_LONG_EDGE.toDouble() / maxOf(bounds.width, bounds.height).coerceAtLeast(1),
        )
        val width = (bounds.width * factor).roundToInt().coerceAtLeast(320)
        val height = (bounds.height * factor).roundToInt().coerceAtLeast(320)

        val token = newMirrorToken()
        val stream = MirrorStreamServer(token)
        try {
            stream.start()
        } catch (failure: Exception) {
            com.prism.core.PrismPlatform.log.warn(
                TAG,
                "Could not open the page stream: " + (failure.message ?: "no port"),
            )
            WirelessDisplay.reportError("The other device could not open a stream for its next page.")
            return
        }

        val current = Session(token, stream)
        session = current
        // Announce immediately rather than waiting for the first composition. A display that
        // receives this can open its window and wait for a frame; waiting in silence was the
        // "long press did nothing" experience when the sender's page was momentarily unknown.
        current.announced = true
        WirelessDisplay.announceMirror(stream.port, token, width, height)
        current.worker = thread(name = "prism-desktop-page", isDaemon = true) {
            var scene: ImageComposeScene? = null
            var sceneKey: String? = null
            try {
                while (session === current) {
                    if (!WirelessDisplay.isConnected() || !WirelessDisplay.isSending()) break
                    // A page that has not reported yet must not turn a request into silence.
                    // The desktop grid is the honest default: it is the second-monitor page, and
                    // it is replaced by the real next page as soon as the pager reports one.
                    val show = WirelessDisplay.displayPage() ?: DEFAULT_SHOW
                    val key = show.slot + ":" + show.index + ":" + show.page
                    if (scene == null || key != sceneKey) {
                        scene?.close()
                        scene = sceneFor(show, width, height)
                        sceneKey = key
                    }
                    scene?.let { rendered ->
                        val image = rendered.render(System.nanoTime())
                        val bytes = image.encodeToData(EncodedImageFormat.JPEG, JPEG_QUALITY)?.bytes
                        if (bytes != null && bytes.isNotEmpty()) {
                            stream.sendFrame(width, height, bytes)
                            if (!current.announced) {
                                current.announced = true
                                WirelessDisplay.announceMirror(stream.port, token, width, height)
                            }
                        }
                    }
                    Thread.sleep(REFRESH_MS)
                }
            } catch (failure: Exception) {
                if (session === current) {
                    com.prism.core.PrismPlatform.log.warn(
                        TAG,
                        "Desktop page stream stopped: " + failure.message,
                    )
                    WirelessDisplay.reportError(
                        "The other device could not render its next page: " +
                            (failure.message ?: failure.javaClass.simpleName),
                    )
                }
            } finally {
                scene?.close()
                // The viewer closes with the control link. Telling the far side keeps an
                // abandoned stream from being cached as this device's live page.
                runCatching { stream.close() }
                if (current.announced && session === current) {
                    runCatching { WirelessDisplay.stopMirror() }
                }
                if (session === current) session = null
            }
        }
    }

    fun stop(notify: Boolean = true) {
        val current = session
        session = null
        current?.worker?.interrupt()
        runCatching { current?.stream?.close() }
        // A restart for a NEW page on the same link must not tell the viewer the stream is over:
        // the viewer is about to be handed a replacement, and closing its window between the two
        // would flash black at the exact moment the page changed.
        if (notify && current?.announced == true) {
            runCatching { WirelessDisplay.stopMirror() }
        }
    }

    /**
     * Renders one page to JPEG bytes.
     *
     * The same offscreen path the stream uses, exposed so a test can prove it produces a picture
     * without starting a link, a display window or a network listener.
     */
    internal fun render(show: WirelessDisplay.Show, width: Int, height: Int): ByteArray? {
        var scene: ImageComposeScene? = null
        return try {
            val created = sceneFor(show, width, height)
            scene = created
            created.render(System.nanoTime())
                .encodeToData(EncodedImageFormat.JPEG, JPEG_QUALITY)?.bytes
        } catch (failure: Exception) {
            com.prism.core.PrismPlatform.log.warn(
                TAG,
                "Could not render " + show.slot + ": " + failure.message,
            )
            null
        } finally {
            runCatching { scene?.close() }
        }
    }

    private val DEFAULT_SHOW = WirelessDisplay.Show(
        slot = SlotAssignment.DesktopGrid.serialize(),
        index = 0,
        from = "Prism desktop",
        page = PageId.DESKTOP.name,
    )

    private fun sceneFor(show: WirelessDisplay.Show, width: Int, height: Int): ImageComposeScene =
        ImageComposeScene(
            width = width,
            height = height,
            density = Density(1f),
        ) {
            PrismTheme {
                // A display is its own composition, so it needs the two local caches the pages
                // read. Without them a page that loads icons renders its empty state forever.
                val icons = mutableStateMapOf<String, ImageBitmap?>()
                CompositionLocalProvider(
                    LocalIconCache provides icons,
                    LocalDragController provides DragController(),
                ) {
                    DesktopExtendedPage(show)
                }
            }
        }
}

/**
 * The page an extension asks for, using the sender's own identity when it has one.
 *
 * The slot path stays for a phone (whose pages really are slots) and for an older desktop. A
 * current desktop sends [WirelessDisplay.Show.page] as well, because its rail has pages that do
 * not have a one-to-one slot: without it "the next page" could be a second copy of the desktop
 * grid instead of Nora, diagnostics or the wallet extras.
 */
@Composable
fun DesktopExtendedPage(show: WirelessDisplay.Show) {
    val page = show.page?.let { name ->
        runCatching { PageId.valueOf(name) }.getOrNull()
    }
    if (page != null) {
        PageBody(
            page = page,
            launcherMode = false,
            onLauncherModeChange = {},
        )
    } else {
        SlotPageForDisplay(SlotAssignment.deserialize(show.slot), show.index)
    }
}
