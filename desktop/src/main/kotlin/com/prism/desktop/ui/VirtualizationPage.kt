package com.prism.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prism.desktop.vm.QemuVm
import com.prism.desktop.vm.VncClient
import com.prism.launcher.PrismSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * A guest operating system, running and on screen. PHASES 68, 69 and 70.
 *
 * ## The display is a canvas, not a window
 *
 * QEMU could open its own window; that window would be outside Prism, unthemeable and unlayoutable. The
 * guest's screen arrives over RFB and is drawn here, which is the same arrangement the phone uses --
 * the protocol work is shared, the drawing is not.
 *
 * ## What PHASE 70 means here, and what it does not
 *
 * On Android, "launching an app opens it inside the guest" makes sense: the launcher owns the app list
 * and can route a tap. On a desktop the equivalent is opening a program INSIDE the guest, and the only
 * general way to do that from outside is to type it -- there is no agent in a stranger's ISO to talk
 * to. So what is here is the honest version: a command box that sends keystrokes to the guest. An
 * agent inside a Prism-built guest image could do better, and that belongs with PrismOS rather than
 * with a page that has to work for any ISO somebody imports.
 */
@Composable
fun VirtualizationPage() {
    val colors = LocalPrismColors.current
    val scope = rememberCoroutineScope()

    var state by remember { mutableStateOf(QemuVm.state) }
    var note by remember { mutableStateOf("") }
    var imagePath by remember { mutableStateOf(PrismSettings.getVmImagePath()) }
    var memory by remember { mutableStateOf(PrismSettings.getVmMemoryMb()) }
    var frameRevision by remember { mutableStateOf(0) }
    val capability = remember { QemuVm.capability() }

    val vnc = remember { VncClient() }

    DisposableEffect(Unit) {
        QemuVm.onStateChange = { state = it }
        vnc.onFrame = { frameRevision++ }
        onDispose {
            QemuVm.onStateChange = null
            vnc.onFrame = null
            // The VM is NOT stopped here. Navigating away from the page should not destroy a running
            // guest -- that is somebody's unsaved work, and the Stop button is how it ends.
            vnc.disconnect()
        }
    }

    // Attach the display once QEMU is listening.
    LaunchedEffect(state) {
        if (state == QemuVm.State.RUNNING && !vnc.connected) {
            repeat(30) {
                if (vnc.connected) return@repeat
                val error = withContext(Dispatchers.IO) { vnc.connect() }
                if (error == null) return@repeat
                delay(500)
            }
        }
    }

    if (state == QemuVm.State.RUNNING && vnc.connected) {
        GuestScreen(
            vnc = vnc,
            frameRevision = frameRevision,
            onStop = {
                vnc.disconnect()
                QemuVm.stop()
            },
            onPause = { QemuVm.pause() },
        )
        return
    }

    PageScaffold("Virtualization", "A whole guest operating system, inside Prism") {
        Column(Modifier.verticalScroll(rememberScrollState())) {

            SectionHeader("this machine")
            Card {
                InfoRow("QEMU", capability.binary?.absolutePath ?: "not installed")
                Hairline()
                InfoRow("Acceleration", capability.accelerator)
                Hairline()
                InfoRow("State", state.name.lowercase())
                if (QemuVm.lastError.isNotBlank()) {
                    Hairline()
                    InfoRow("Last error", QemuVm.lastError)
                }
            }
            SectionFooter(capability.detail)

            SectionHeader("image")
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        imagePath.ifBlank { "No image chosen yet." },
                        fontSize = 13.sp,
                        color = if (imagePath.isBlank()) colors.faint else colors.onSurface,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = {
                            val chosen = chooseImage()
                            if (chosen != null) {
                                imagePath = chosen.absolutePath
                                PrismSettings.setVmImagePath(imagePath)
                            }
                        }) { Text("Choose an ISO or disk", fontSize = 13.sp) }

                        Spacer(Modifier.width(12.dp))
                        listOf(1024, 2048, 4096, 8192).forEach { mb ->
                            val active = mb == memory
                            androidx.compose.material3.Surface(
                                color = if (active) colors.accent else Color(0xFF23232B),
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                                modifier = Modifier.padding(end = 6.dp),
                            ) {
                                Text(
                                    (mb / 1024).toString() + " GB",
                                    fontSize = 12.sp,
                                    color = if (active) Color.White else Color(0xFFB9B9C4),
                                    modifier = Modifier
                                        .clickableRow {
                                            memory = mb
                                            PrismSettings.setVmMemoryMb(mb)
                                        }
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                )
                            }
                        }
                    }
                }
            }
            SectionFooter(
                "An .iso is attached as CD-ROM media and anything else as a disk. That distinction " +
                    "matters: an install ISO attached as a raw disk skips its boot catalog and boots " +
                    "nothing at all, which is a failure that looks like a broken VM rather than a " +
                    "wrong flag."
            )

            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    enabled = capability.available && imagePath.isNotBlank() &&
                        state != QemuVm.State.RUNNING && state != QemuVm.State.BOOTING,
                    onClick = {
                        scope.launch {
                            note = withContext(Dispatchers.IO) {
                                QemuVm.start(File(imagePath), memory) ?: "Booting…"
                            }
                        }
                    },
                ) { Text(if (state == QemuVm.State.BOOTING) "Booting…" else "Start") }

                Spacer(Modifier.width(10.dp))
                OutlinedButton(
                    enabled = state == QemuVm.State.PAUSED,
                    onClick = { QemuVm.resume(); state = QemuVm.state },
                ) { Text("Resume") }

                Spacer(Modifier.width(10.dp))
                OutlinedButton(onClick = { vnc.disconnect(); QemuVm.stop(); state = QemuVm.state }) {
                    Text("Stop")
                }
            }

            if (note.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(note, fontSize = 12.sp, color = colors.muted, lineHeight = 17.sp)
            }
            if (vnc.lastError.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text("Display: " + vnc.lastError, fontSize = 11.sp, color = colors.faint)
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

/**
 * The guest, full page, with the mouse and keyboard going to it.
 *
 * COORDINATES ARE SCALED, because the canvas is almost never the guest's resolution. A pointer event at
 * the wrong scale lands somewhere else entirely, which is the sort of bug that makes a remote display
 * feel broken rather than slow.
 */
@Composable
private fun GuestScreen(
    vnc: VncClient,
    frameRevision: Int,
    onStop: () -> Unit,
    onPause: () -> Unit,
) {
    val colors = LocalPrismColors.current
    var command by remember { mutableStateOf("") }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Guest · " + vnc.width + "x" + vnc.height + " · " + QemuVm.describe(),
                fontSize = 12.sp,
                color = colors.faint,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = onPause) { Text("Pause", fontSize = 12.sp) }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = onStop) { Text("Stop", fontSize = 12.sp) }
        }

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color.Black)
                .onSizeChanged { canvasSize = it }
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        if (canvasSize.width == 0 || canvasSize.height == 0) return@detectTapGestures
                        val x = (offset.x / canvasSize.width * vnc.width).toInt()
                        val y = (offset.y / canvasSize.height * vnc.height).toInt()
                        // Down then up, which is what a click is on the wire.
                        vnc.sendPointer(x, y, 1)
                        vnc.sendPointer(x, y, 0)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            // frameRevision is read so a new frame recomposes this; the bitmap itself is replaced
            // wholesale by the client rather than mutated, so there is nothing to tear.
            val bitmap = remember(frameRevision) { vnc.frame }
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = "The guest's screen",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text("Waiting for the guest's first frame…", fontSize = 13.sp, color = colors.faint)
            }
        }

        // PHASE 70, honestly. See the page comment for why this is a command box rather than a
        // launcher: there is no agent inside an arbitrary ISO to route an app launch to.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.material3.OutlinedTextField(
                value = command,
                onValueChange = { command = it },
                singleLine = true,
                placeholder = { Text("Type into the guest, then Enter", fontSize = 13.sp) },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            Button(
                enabled = command.isNotBlank(),
                onClick = {
                    command.forEach { character ->
                        // Latin-1 maps directly onto X11 keysyms, which covers everything a command
                        // line needs. Anything outside it would need a keysym table Prism does not
                        // carry, so it is skipped rather than sent as the wrong key.
                        if (character.code in 32..126) vnc.typeKey(character.code)
                    }
                    vnc.typeKey(0xFF0D)     // Return
                    command = ""
                },
            ) { Text("Send") }
        }
    }
}

/** The platform's own file dialog, so choosing an image looks like every other open box. */
private fun chooseImage(): File? {
    val dialog = java.awt.FileDialog(null as java.awt.Frame?, "Choose an ISO or disk image", java.awt.FileDialog.LOAD)
    dialog.isVisible = true
    val directory = dialog.directory ?: return null
    val name = dialog.file ?: return null
    return File(directory, name)
}
