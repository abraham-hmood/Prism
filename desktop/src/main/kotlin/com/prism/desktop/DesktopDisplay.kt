package com.prism.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prism.desktop.ui.PrismTheme
import com.prism.desktop.ui.clickableRow
import com.prism.launcher.display.WirelessDisplay
import java.awt.GraphicsEnvironment
import javax.swing.SwingUtilities

/**
 * This desktop as a wireless display.
 *
 * ## It does not build a page any more
 *
 * A Prism display used to be sent a page SLOT and rebuilt that page locally. That was never a
 * mirror: when the phone was on Files, this machine showed its own Files page. A display now
 * receives the sender's actual screen as a stream of JPEG frames, so the only window here is the
 * one in [DesktopMirror].
 *
 * ## Pairing
 *
 * An unpaired sender is shown a code before it may send anything. The code is the same fullscreen
 * window the mirror will occupy, because it is only useful while the person holding the other
 * device can read it.
 *
 * ## System displays are local
 *
 * A monitor or television cabled to this computer is not a Prism device and has no control link.
 * It shows this machine's own screen, captured locally by [DesktopScreenMirror].
 */
object DesktopDisplay {

    /** The page an extending sender asked this machine to show, or null when none is showing. */
    var request by mutableStateOf<WirelessDisplay.Show?>(null)
        private set

    /** A pairing code this machine must show, or null when no verification is in progress. */
    var pairing by mutableStateOf<Pairing?>(null)
        private set

    /**
     * The local screen a mirror has been sent to, or null when no system display is in use.
     *
     * This is a SCREEN ID from [DesktopScreens], not a network address: a non-Prism display is
     * driven by this machine through the platform, and there is nothing on the far end to talk to.
     */
    var systemScreen by mutableStateOf<Int?>(null)
        private set

    data class Pairing(
        val code: String,
        val from: String,
        val sending: Boolean,
        val extending: Boolean,
    )

    fun dismiss() {
        request = null
    }

    /** Closes the code screen and tells the far side this pairing is abandoned. */
    fun cancelPairing() {
        pairing = null
        WirelessDisplay.disconnect()
    }

    /** Closes the code screen after a successful handshake, leaving the link connected. */
    fun dismissPairing() {
        pairing = null
    }

    fun openSystemScreen(screen: DesktopScreens.Screen) {
        systemScreen = screen.id
    }

    fun closeSystemScreen() {
        systemScreen = null
    }

    /** Installs the receive path and names this machine in the beacon. */
    fun install() {
        WirelessDisplay.deviceName = {
            runCatching { java.net.InetAddress.getLocalHost().hostName }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: "Prism desktop"
        }
        WirelessDisplay.devicePlatform = {
            System.getProperty("os.name")?.lowercase() ?: "desktop"
        }
        // EXTEND: the sender names a page. It opens in the same fullscreen window a mirror would
        // use, and only one of the two is ever visible.
        WirelessDisplay.onShow = { show ->
            SwingUtilities.invokeLater {
                DesktopMirror.closeViewer()
                pairing = null
                request = show
            }
        }
        // The code goes on THIS screen, in the window the mirror will occupy.
        WirelessDisplay.onPairingCode = { code, sender, sending, extending ->
            SwingUtilities.invokeLater {
                pairing = Pairing(code, sender, sending, extending)
            }
        }
        WirelessDisplay.onPairingFailed = {
            SwingUtilities.invokeLater { pairing = null }
        }
        // The screen stream, both directions. Installed before the beacon starts so a link that
        // completes during startup already has a handler on this side. Kept in its own failure
        // boundary: a capture subsystem that cannot initialise must not prevent the control
        // listener from starting, or every inbound request looks like it was never received.
        runCatching { DesktopMirror.install() }
            .onFailure {
                com.prism.core.PrismPlatform.log.warn(
                    "PrismDisplay",
                    "Desktop mirror callbacks did not install: " + it.message,
                )
            }
        runCatching { WirelessDisplay.install() }
            .onFailure {
                com.prism.core.PrismPlatform.log.error(
                    "PrismDisplay",
                    "Wireless display listener did not start",
                    it,
                )
            }
    }
}

/**
 * The screens this computer can present to.
 *
 * The primary screen is left out: a fullscreen window on it would cover the Prism window the user
 * is driving it from, which is not a second display. Every other screen is a candidate, including
 * one attached over HDMI or exposed by the operating system's display arrangement.
 */
object DesktopScreens {

    data class Screen(
        val id: Int,
        val name: String,
        val bounds: java.awt.Rectangle,
        val primary: Boolean,
    ) {
        val resolution: String
            get() = bounds.width.toString() + " × " + bounds.height
    }

    fun screens(): List<Screen> = runCatching {
        val environment = GraphicsEnvironment.getLocalGraphicsEnvironment()
        environment.screenDevices.mapIndexed { index, device ->
            val bounds = device.defaultConfiguration.bounds
            Screen(
                id = index,
                name = device.iDstring?.takeIf { it.isNotBlank() }
                    ?: ("Display " + (index + 1)),
                bounds = bounds,
                primary = device == environment.defaultScreenDevice,
            )
        }.filterNot { it.primary }
    }.getOrDefault(emptyList())

    fun byId(id: Int): Screen? = screens().firstOrNull { it.id == id }
}

/**
 * EXTEND's local fallback: a sender that could only name a page.
 *
 * Current Android and desktop senders render their own next page and stream it, which is the
 * only way a display can show the SENDER's files and icons; [DesktopMirrorWindow] is what those
 * links open. This window remains for a page message from a build with no offscreen renderer, and
 * for a system display attached to this machine, where rendering the page locally IS the sender's
 * page. It is not the normal network path.
 */
@Composable
fun DesktopDisplayWindow(show: WirelessDisplay.Show, onClose: () -> Unit) {
    PrismTheme {
        // A display is its own window and therefore its own composition: the icon cache the main
        // window provides does not cross into it.
        val icons = remember { mutableStateMapOf<String, ImageBitmap?>() }
        CompositionLocalProvider(
            com.prism.desktop.ui.LocalIconCache provides icons,
            com.prism.desktop.ui.LocalDragController provides remember { com.prism.desktop.ui.DragController() },
        ) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                DesktopExtendedPage(show)

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
                        "Close the display",
                        tint = Color(0xCCFFFFFF),
                        modifier = Modifier.padding(8.dp).fillMaxSize(),
                    )
                }

                Text(
                    text = if (show.from.isBlank()) "Prism display" else "Extended from " + show.from,
                    color = Color(0x88FFFFFF),
                    fontSize = 11.sp,
                    modifier = Modifier.align(Alignment.BottomStart).padding(14.dp),
                )
            }
        }
    }
}

/**
 * The pairing code, as large as the window allows.
 *
 * This is the device that was asked to show something, so the code is its to display. The other
 * device prompts its user to type it. The code is never sent over the network.
 */
@Composable
fun DesktopPairingWindow(pairing: DesktopDisplay.Pairing, onClose: () -> Unit) {
    // On a reverse link this machine is the SENDER. Once the proof is accepted there is no mirror
    // coming here, so without this the fullscreen code would sit over the app that has to drive
    // the screen. A normal display keeps its code until the first frame replaces it.
    LaunchedEffect(Unit) {
        while (true) {
            if (WirelessDisplay.isConnected() && WirelessDisplay.isSending()) {
                DesktopDisplay.dismissPairing()
                break
            }
            kotlinx.coroutines.delay(600L)
        }
    }

    PrismTheme {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            Column(
                Modifier.fillMaxSize().padding(48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
            ) {
                Text(
                    text = if (pairing.sending) {
                        if (pairing.extending) {
                            "Sharing this device's next page with " + pairing.from.ifBlank { "the other device" }
                        } else {
                            "Sending this screen to " + pairing.from.ifBlank { "the other device" }
                        }
                    } else if (pairing.extending) {
                        "Let " + pairing.from.ifBlank { "the other device" } + " show its next page here"
                    } else {
                        "Let " + pairing.from.ifBlank { "the other device" } + " show its screen here"
                    },
                    color = Color(0xAAFFFFFF),
                    fontSize = 16.sp,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = pairing.code,
                    color = Color.White,
                    // Six characters fill the window; a longer code shrinks instead of running off
                    // the edge. The pairing length is a setting and allows up to 32.
                    fontSize = (432f / pairing.code.length.coerceAtLeast(6)).coerceIn(18f, 72f).sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 8.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(vertical = 28.dp),
                )
                Text(
                    text = "Type this code on " + pairing.from.ifBlank { "the other device" } +
                        " to allow it. It never leaves this screen.",
                    color = Color(0x77FFFFFF),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
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
                    "Cancel pairing",
                    tint = Color(0xCCFFFFFF),
                    modifier = Modifier.padding(8.dp).fillMaxSize(),
                )
            }
        }
    }
}

/**
 * This desktop's screen, fullscreen on a monitor or television attached to it.
 *
 * The same rule as a Prism receiver: it shows pixels, not a page. [DesktopScreenMirror] captures
 * the primary screen; this window draws it on the other screen. The caption stays available but
 * faint so a television used as a display does not look like an app that failed to load.
 */
@Composable
fun DesktopSystemDisplayWindow(
    screen: DesktopScreens.Screen,
    onClose: () -> Unit,
) {
    DisposableEffect(screen.id) {
        DesktopScreenMirror.start()
        onDispose { DesktopScreenMirror.stop() }
    }

    PrismTheme {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            val bitmap = DesktopScreenMirror.frame
            if (bitmap != null) {
                FittedScreen(bitmap)
            } else {
                Text(
                    text = DesktopScreenMirror.error ?: "Starting…",
                    color = Color(0x88FFFFFF),
                    fontSize = 14.sp,
                    modifier = Modifier.align(Alignment.Center),
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
                    "Stop showing on this display",
                    tint = Color(0xCCFFFFFF),
                    modifier = Modifier.padding(8.dp).fillMaxSize(),
                )
            }

            Text(
                text = screen.name,
                color = Color(0x66FFFFFF),
                fontSize = 11.sp,
                modifier = Modifier.align(Alignment.BottomStart).padding(14.dp),
            )
        }
    }
}
