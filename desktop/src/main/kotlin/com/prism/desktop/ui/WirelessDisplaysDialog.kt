package com.prism.desktop.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prism.desktop.DesktopDisplay
import com.prism.desktop.DesktopScreens
import com.prism.launcher.PrismSettings
import com.prism.launcher.display.WirelessDisplay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.swing.SwingUtilities

/**
 * Choosing a screen to extend to.
 *
 * ## Two kinds of screen in one list
 *
 * A PRISM DEVICE runs this application and receives a live stream of this desktop's screen. It
 * needs verification unless the two devices are already paired: the display shows a code, the
 * sender types it here. A TRUSTED device skips that entirely.
 *
 * A SYSTEM DISPLAY is a monitor or television the operating system knows about. It cannot run
 * Prism, so the page is presented on it directly by this machine. There is no code because there
 * is no remote device to verify.
 *
 * ## Click and long-press are opposites
 *
 * Clicking a Prism device mirrors this machine's screen to it. Long-pressing does the reverse: the
 * pressed device becomes the sender and this one becomes the display. The verification rule still
 * follows the initiator -- the device whose user acted types the code, the other one shows it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WirelessDisplaysDialog(onDismiss: () -> Unit) {
    val colors = LocalPrismColors.current
    val scope = rememberCoroutineScope()

    var displays by remember { mutableStateOf<List<WirelessDisplay.Display>>(emptyList()) }
    var screens by remember { mutableStateOf<List<DesktopScreens.Screen>>(emptyList()) }
    // One pairing read per refresh rather than one per row; see the Android picker for why.
    var paired by remember { mutableStateOf<Set<String>>(emptySet()) }
    var connected by remember { mutableStateOf(WirelessDisplay.isConnected()) }
    var connectedName by remember { mutableStateOf(WirelessDisplay.connectedTo()) }
    var status by remember { mutableStateOf("") }
    var extend by remember { mutableStateOf(WirelessDisplay.isExtend()) }

    fun chooseMode(wantExtend: Boolean) {
        if (extend == wantExtend) return
        extend = wantExtend
        PrismSettings.setWirelessDisplayMode(
            if (wantExtend) PrismSettings.WIRELESS_DISPLAY_EXTEND
            else PrismSettings.WIRELESS_DISPLAY_MIMIC,
        )
        // One link speaks one transport. Let the user pick a display again rather than leave a
        // capture running for a receiver that is no longer waiting for frames.
        if (WirelessDisplay.isConnected()) {
            WirelessDisplay.disconnect()
            connected = false
            connectedName = ""
            status = "Mode changed. Pick a display to connect again."
        }
    }

    // The prompt for a code this machine must type. It replaces the list rather than stacking a
    // second dialog on top of an already-modal one.
    var challengeFrom by remember { mutableStateOf<String?>(null) }
    var code by remember { mutableStateOf("") }

    DisposableEffect(Unit) {
        WirelessDisplay.onChallenge = { from ->
            SwingUtilities.invokeLater {
                challengeFrom = from
                code = ""
            }
        }
        WirelessDisplay.onRejected = { reason ->
            SwingUtilities.invokeLater {
                status = reason
                challengeFrom = null
            }
        }
        WirelessDisplay.onAccepted = {
            SwingUtilities.invokeLater {
                challengeFrom = null
                connected = WirelessDisplay.isConnected()
                connectedName = WirelessDisplay.connectedTo()
            }
        }
        onDispose {
            WirelessDisplay.onChallenge = null
            WirelessDisplay.onRejected = null
            WirelessDisplay.onAccepted = null
            // A connection waiting for a code nobody can enter any more would hold the display's
            // socket open. Disconnecting here is what makes closing the dialog mean closing it.
            if (WirelessDisplay.isAwaitingCode()) WirelessDisplay.disconnect()
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            displays = runCatching { WirelessDisplay.displays() }.getOrDefault(emptyList())
            screens = runCatching { DesktopScreens.screens() }.getOrDefault(emptyList())
            paired = runCatching {
                com.prism.launcher.trusted.TrustedDevices.all()
                    .filter { it.confirmed }
                    // Aliases included: an identity that moved is still the same paired screen.
                    .flatMap { listOf(it.fingerprint) + it.aliases }
                    .toSet()
            }.getOrDefault(emptySet())
            connected = WirelessDisplay.isConnected()
            connectedName = WirelessDisplay.connectedTo()
            delay(1_500L)
        }
    }

    /**
     * Opens a link and reports what happened.
     *
     * A reverse link (long-press) does not publish here: this machine is the display in that case,
     * and the far side starts sending as soon as the handshake completes.
     */
    val connect: suspend (WirelessDisplay.Display, Boolean) -> Unit = { display, reverse ->
        status = if (reverse) {
            if (extend) "Asking " + display.name + " for its next page…"
            else "Asking " + display.name + " for its screen…"
        } else if (extend) {
            "Extending to " + display.name + "…"
        } else {
            "Mirroring this screen to " + display.name + "…"
        }
        val message = withContext(Dispatchers.IO) { WirelessDisplay.connect(display, reverse) }
        status = message
        connected = WirelessDisplay.isConnected()
        connectedName = WirelessDisplay.connectedTo()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        title = { Text("Wireless displays") },
        text = {
            Column(Modifier.width(560.dp).heightIn(max = 560.dp)) {
                if (challengeFrom != null) {
                    Text(
                        "Type the code shown on " + challengeFrom.orEmpty().ifBlank { "the other device" },
                        fontSize = 14.sp,
                        color = colors.onSurface,
                    )
                    Text(
                        "The code appears on that screen and is never sent over the network.",
                        fontSize = 12.sp,
                        color = colors.faint,
                        modifier = Modifier.padding(top = 4.dp, bottom = 10.dp),
                    )
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it },
                        singleLine = true,
                        label = { Text("Code") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { WirelessDisplay.submitCode(code) },
                            enabled = code.isNotBlank(),
                        ) { Text("Connect") }
                        TextButton(onClick = {
                            challengeFrom = null
                            WirelessDisplay.disconnect()
                        }) { Text("Cancel") }
                    }
                    return@Column
                }

                Text(
                    "Click a Prism device to use it as a second monitor. Long-press it to bring its " +
                        "screen or next page here instead. Unpaired devices ask for a code shown on " +
                        "the other screen.",
                    fontSize = 12.sp,
                    color = colors.faint,
                )

                Spacer(Modifier.height(10.dp))
                Text("HOW TO SHOW IT", fontSize = 10.sp, letterSpacing = 1.2.sp, color = colors.faint)
                ModeRow(
                    title = "Mimic this screen",
                    detail = "Click: this screen becomes the other screen. Long-press: its screen comes here.",
                    selected = !extend,
                    colors = colors,
                    onClick = { chooseMode(false) },
                )
                ModeRow(
                    title = "Extend the next page",
                    detail = "Click: the next page becomes a monitor there. Long-press: its next page comes here.",
                    selected = extend,
                    colors = colors,
                    onClick = { chooseMode(true) },
                )

                Spacer(Modifier.height(8.dp))
                Text(
                    when {
                        connected && WirelessDisplay.isSending() ->
                            (if (extend) "Extending to " else "Mirroring with ") + connectedName + "."
                        connected && extend -> "Showing " + connectedName + "'s next page here."
                        connected -> "Showing " + connectedName + "'s screen here."
                        WirelessDisplay.isAwaitingCode() ->
                            "Waiting for the code on " + connectedName + "…"
                        displays.isEmpty() && screens.isEmpty() -> "Looking for displays…"
                        else -> displays.size.toString() + " Prism device(s) · " +
                            screens.size.toString() + " system display(s)."
                    },
                    fontSize = 12.sp,
                    color = if (connected) colors.accent else colors.faint,
                )

                if (connected) {
                    Text(
                        "Disconnect",
                        fontSize = 12.sp,
                        color = Color(0xFFFF8A8A),
                        modifier = Modifier
                            .padding(top = 4.dp, bottom = 6.dp)
                            .clickableRow {
                                WirelessDisplay.disconnect()
                                connected = false
                                connectedName = ""
                            },
                    )
                }

                Spacer(Modifier.height(8.dp))
                Text("PRISM DEVICES", fontSize = 10.sp, letterSpacing = 1.2.sp, color = colors.faint)
                Spacer(Modifier.height(6.dp))

                // THE LIST OWNS ITS OWN SCROLL. A LazyColumn inside a vertically scrolling
                // parent would be measured with infinite height, so the dialog keeps the header
                // fixed and only this list moves.
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 300.dp)) {
                    if (displays.isEmpty()) {
                        item {
                            Text(
                                "No Prism devices yet. Open Prism on the other device, and make sure " +
                                    "both are on the same network.",
                                fontSize = 12.sp,
                                color = colors.faint,
                                modifier = Modifier.padding(vertical = 10.dp),
                            )
                        }
                    }
                    items(displays, key = { it.ip }) { display ->
                        val current = connected && connectedName == display.name
                        Surface(
                            color = if (current) colors.accent.copy(alpha = 0.18f) else colors.surfaceRaised,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 6.dp)
                                .combinedClickable(
                                    onClick = { scope.launch { connect(display, false) } },
                                    onLongClick = { scope.launch { connect(display, true) } },
                                ),
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        (if (current) "● " else "") + display.name,
                                        fontSize = 14.sp,
                                        color = if (current) colors.accent else colors.onSurface,
                                    )
                                    Text(
                                        display.platform.ifBlank { "Prism device" } + " · " + display.ip +
                                            (if (display.fingerprint in paired) " · paired" else " · not paired"),
                                        fontSize = 11.sp,
                                        color = colors.faint,
                                    )
                                }
                                OutlinedButton(
                                    onClick = { scope.launch { connect(display, false) } },
                                ) {
                                    Text(
                                        when {
                                            current -> "Reconnect"
                                            extend -> "Extend there"
                                            else -> "Show there"
                                        },
                                        fontSize = 12.sp,
                                    )
                                }
                            }
                        }
                    }

                    if (screens.isNotEmpty()) {
                        item {
                            Text(
                                "SYSTEM DISPLAYS",
                                fontSize = 10.sp,
                                letterSpacing = 1.2.sp,
                                color = colors.faint,
                                modifier = Modifier.padding(top = 8.dp, bottom = 6.dp),
                            )
                        }
                        items(screens, key = { it.id }) { screen ->
                            Surface(
                                color = colors.surfaceRaised,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                            ) {
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(screen.name, fontSize = 14.sp, color = colors.onSurface)
                                        Text(
                                            "Attached display · " + screen.resolution +
                                                " · shows this desktop",
                                            fontSize = 11.sp,
                                            color = colors.faint,
                                        )
                                    }
                                    OutlinedButton(
                                        onClick = { DesktopDisplay.openSystemScreen(screen) },
                                    ) {
                                        Text("Show there", fontSize = 12.sp)
                                    }
                                }
                            }
                        }
                    }
                }

                if (status.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(status, fontSize = 12.sp, color = colors.faint)
                }
            }
        },
    )
}

/** One choice in the mimic/extend radio group. */
@Composable
private fun ModeRow(
    title: String,
    detail: String,
    selected: Boolean,
    colors: PrismColors,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickableRow(onClick).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column {
            Text(title, fontSize = 13.sp, color = colors.onSurface)
            Text(detail, fontSize = 11.sp, color = colors.faint)
        }
    }
}
