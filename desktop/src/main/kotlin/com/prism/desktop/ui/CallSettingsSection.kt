package com.prism.desktop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prism.desktop.DesktopAudioDevices
import com.prism.desktop.DesktopCameraDevices
import com.prism.desktop.DesktopRinger
import com.prism.desktop.DesktopRingtones
import com.prism.launcher.PrismSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Settings → Phone and Meshnet Calls, on the desktop: the phone's section, ported.
 *
 * Microphone, speaker and camera for calls, the ringtone and the call tone (both with MP3 import), and
 * the switch that gives meshnet calls a ringtone and call tone of their own. The values live in the same `PrismSettings` keys the phone
 * uses; device choices are this machine's device names, which simply match nothing on a phone.
 *
 * A computer has no SIM, so every call it receives is a meshnet call. With the ringtones split, calls
 * from a phone ring with the "phone calls" tone and calls from anything else with the meshnet one.
 */
@Composable
fun CallSettingsSection() {
    var revision by remember { mutableStateOf(0) }
    var microphones by remember { mutableStateOf<List<DesktopAudioDevices.Device>?>(null) }
    var speakers by remember { mutableStateOf<List<DesktopAudioDevices.Device>?>(null) }
    var cameras by remember { mutableStateOf<List<DesktopCameraDevices.Device>?>(null) }
    // The open tone dialog: (meshnet?, call tone?), or null when closed.
    var toneDialog by remember { mutableStateOf<Pair<Boolean, Boolean>?>(null) }

    // Read off the UI thread, every time the section is shown: a headset or webcam plugged in since
    // appears on reopening, as on the phone. Cameras are slow on Windows (each is briefly opened).
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            microphones = DesktopAudioDevices.microphones()
            speakers = DesktopAudioDevices.speakers()
        }
        cameras = withContext(Dispatchers.IO) { DesktopCameraDevices.cameras() }
    }

    Spacer(Modifier.height(18.dp))
    SectionHeader("phone and meshnet calls")
    Card {
        DeviceRow(
            title = "Microphone",
            detail = "Used for meshnet voice and video calls (one-to-one and group) and for recording " +
                "your meshvoice greeting. A computer has no SIM, so it places no carrier calls of its own.",
            devices = microphones?.map { it.key to it.label },
            current = PrismSettings.getCallMicrophone(),
            onSelect = { PrismSettings.setCallMicrophone(it); revision++ },
        )
        Hairline()
        DeviceRow(
            title = "Speaker",
            detail = "Where meshnet voice and video calls play, one-to-one and group, and where the " +
                "ringtone rings.",
            devices = speakers?.map { it.key to it.label },
            current = PrismSettings.getCallSpeaker(),
            onSelect = { PrismSettings.setCallSpeaker(it); revision++ },
        )
        Hairline()
        DeviceRow(
            title = "Camera",
            detail = "Used for meshnet video calls, one-to-one and group.",
            devices = cameras?.map { it.key to it.label },
            current = PrismSettings.getCallCamera(),
            loadingText = "Looking for cameras…",
            onSelect = { PrismSettings.setCallCamera(it); revision++ },
        )
        Hairline()
        val separate = remember(revision) { PrismSettings.getSeparateMeshRingtone() }
        NavRow(
            title = if (separate) "Ringtone for phone calls" else "Ringtone",
            detail = (if (separate) "Calls from a phone · " else "Every meshnet call, voice and video · ") +
                remember(revision) { DesktopRingtones.nameOf(PrismSettings.getCallRingtone()) },
            onClick = { toneDialog = false to false },
        )
        Hairline()
        ToggleRow(
            title = "Separate ringtone for meshnet calls",
            detail = "Give meshnet calls from computers and other devices a ringtone and a call tone of their own",
            checked = separate,
            onChange = { enabled ->
                PrismSettings.setSeparateMeshRingtone(enabled)
                revision++
            },
        )
        if (separate) {
            Hairline()
            NavRow(
                title = "Ringtone for meshnet calls",
                detail = "Calls from computers and other devices · " +
                    remember(revision) { DesktopRingtones.nameOf(PrismSettings.getMeshRingtone()) },
                onClick = { toneDialog = true to false },
            )
        }
        Hairline()
        NavRow(
            title = if (separate) "Call tone for phone calls" else "Call tone",
            detail = (if (separate) "Heard while calling a phone · "
            else "Heard while placing any call, voice or video, one-to-one or group · ") +
                remember(revision) { DesktopRingtones.nameOf(PrismSettings.getCallTone(), callTone = true) },
            onClick = { toneDialog = false to true },
        )
        if (separate) {
            Hairline()
            NavRow(
                title = "Call tone for meshnet calls",
                detail = "Heard while calling a computer or other device, voice or video, one-to-one or group · " +
                    remember(revision) { DesktopRingtones.nameOf(PrismSettings.getMeshCallTone(), callTone = true) },
                onClick = { toneDialog = true to true },
            )
        }
    }
    SectionFooter(
        "A device that is unplugged when a call starts falls back to the system's choice, and the " +
            "call log says which device was used.",
    )

    toneDialog?.let { (mesh, callTone) ->
        val noun = if (callTone) "Call tone" else "Ringtone"
        RingtoneDialog(
            title = when {
                mesh -> "$noun for meshnet calls"
                PrismSettings.getSeparateMeshRingtone() -> "$noun for phone calls"
                else -> noun
            },
            current = when {
                callTone && mesh -> PrismSettings.getMeshCallTone()
                callTone -> PrismSettings.getCallTone()
                mesh -> PrismSettings.getMeshRingtone()
                else -> PrismSettings.getCallRingtone()
            },
            callTone = callTone,
            onChosen = { value ->
                when {
                    callTone && mesh -> PrismSettings.setMeshCallTone(value)
                    callTone -> PrismSettings.setCallTone(value)
                    mesh -> PrismSettings.setMeshRingtone(value)
                    else -> PrismSettings.setCallRingtone(value)
                }
                revision++
            },
            onDismiss = { toneDialog = null },
        )
    }
}

/** A labelled dropdown of devices, showing a loading line until the list is read. */
@Composable
private fun DeviceRow(
    title: String,
    detail: String,
    devices: List<Pair<String, String>>?,
    current: String,
    onSelect: (String) -> Unit,
    loadingText: String = "Looking for devices…",
) {
    val colors = LocalPrismColors.current
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(title, fontSize = 14.sp)
        Text(detail, fontSize = 12.sp, color = colors.faint)
        Spacer(Modifier.height(8.dp))
        if (devices == null) {
            Text(loadingText, fontSize = 12.sp, color = colors.faint)
        } else {
            val labels = devices.map { it.second }
            val selected = devices.firstOrNull { it.first == current }?.second
                ?: if (current.isBlank()) labels.firstOrNull().orEmpty()
                else labels.firstOrNull().orEmpty() + " (the chosen one is not connected)"
            PrismDropdown(
                label = "",
                selection = selected,
                options = labels,
                modifier = Modifier.fillMaxWidth(),
                onSelect = { label ->
                    devices.firstOrNull { it.second == label }?.let { onSelect(it.first) }
                },
            )
        }
    }
}

/**
 * The ringtone list: built-ins and imported MP3s as radio rows. Selecting one plays a few seconds
 * of it, as on the phone; "Import MP3…" copies a file in and selects it.
 */
@Composable
private fun RingtoneDialog(
    title: String,
    current: String,
    callTone: Boolean,
    onChosen: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalPrismColors.current
    val scope = rememberCoroutineScope()
    var tones by remember { mutableStateOf(DesktopRingtones.tones(callTone)) }
    var selected by remember { mutableStateOf(tones.firstOrNull { it.value == current }?.value ?: "") }
    var note by remember { mutableStateOf("") }

    DisposableEffect(Unit) { onDispose { DesktopRinger.stopIf("preview") } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontSize = 17.sp) },
        text = {
            Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                tones.forEach { tone ->
                    Row(
                        Modifier.fillMaxWidth().clickableRow {
                            selected = tone.value
                            DesktopRinger.preview(tone.value, callTone)
                        },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = selected == tone.value,
                            onClick = {
                                selected = tone.value
                                DesktopRinger.preview(tone.value, callTone)
                            },
                        )
                        Text(tone.label, fontSize = 14.sp)
                    }
                }
                if (note.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(note, fontSize = 12.sp, color = colors.faint)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                DesktopRinger.stopIf("preview")
                onChosen(selected)
                onDismiss()
            }) { Text("Use this", color = colors.accent) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    DesktopRinger.stopIf("preview")
                    val picked = pickRingtoneFile() ?: return@TextButton
                    note = "Importing ${picked.name}…"
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { DesktopRingtones.import(picked) }
                        result.onSuccess { path ->
                            tones = DesktopRingtones.tones(callTone)
                            // Imported AND chosen: picking a file to ring with is choosing it.
                            selected = path
                            note = "Imported. Press Use this to keep it."
                        }.onFailure { note = it.message ?: "That file could not be imported." }
                    }
                }) { Text("Import MP3…") }
                TextButton(onClick = {
                    DesktopRinger.stopIf("preview")
                    onDismiss()
                }) { Text("Cancel") }
            }
        },
    )
}

/** The native file dialog, filtered to MP3s. AWT, as elsewhere: Compose Desktop has no chooser. */
private fun pickRingtoneFile(): File? {
    val dialog = java.awt.FileDialog(null as java.awt.Frame?, "Import a ringtone", java.awt.FileDialog.LOAD)
    // Windows honours the wildcard; Linux and macOS honour the filter.
    dialog.file = "*.mp3"
    dialog.setFilenameFilter { _, name -> name.lowercase().endsWith(".mp3") }
    dialog.isVisible = true
    val name = dialog.file ?: return null
    return File(dialog.directory, name)
}
