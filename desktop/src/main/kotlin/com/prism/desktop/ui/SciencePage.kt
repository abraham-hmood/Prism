package com.prism.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prism.core.PrismPlatform
import com.prism.desktop.DesktopMicrophone
import com.prism.desktop.science.DesktopCamera
import com.prism.desktop.science.DesktopWifi
import com.prism.launcher.science.Audiometry
import com.prism.launcher.science.CosmicDetector
import com.prism.launcher.science.LabNotebook
import com.prism.launcher.science.MeshScience
import com.prism.launcher.science.RfSample
import com.prism.launcher.science.RfSurvey
import com.prism.launcher.science.ScienceInstruments
import com.prism.launcher.science.Spirometry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Science instruments. PHASE 96.
 *
 * ## The phase asked for honesty about what does not port, and this is it
 *
 * Four instruments, in descending order of how well a desktop does them:
 *
 *  - THE LAB NOTEBOOK works completely. It is a hash chain signed with the wallet key and witnessed
 *    by mesh peers -- arithmetic and a file, portable in full, and the phase called it "the most
 *    useful half anyway" because it is the part that produces a record other people can check.
 *  - LUNG AND HEARING work completely. Both need a microphone and a speaker, which a desktop has,
 *    and the analysis moved to `:core` so a reading taken here and one taken on a phone are read by
 *    the same code.
 *  - RF SURVEYING works where the OS will answer. `netsh` on Windows, `nmcli` on Linux; a machine
 *    with neither says so with the reason, which on Linux is usually "install NetworkManager".
 *  - COSMIC RAYS NEED A CAMERA, AND ARE DISABLED WITHOUT ONE. A laptop webcam can do it; a desktop
 *    tower cannot. The instrument is greyed out with the reason rather than offered and then failing
 *    — and the two reasons it can be unavailable, no camera at all and a camera Prism cannot read
 *    frames from, are reported separately because they need different things from the user.
 */
@Composable
fun SciencePage() {
    val colors = LocalPrismColors.current
    var selected by remember { mutableStateOf(ScienceInstruments.ALL.first().id) }

    val onMesh = remember { runCatching { MeshScience.isOnMesh() }.getOrDefault(false) }
    val peers = remember { runCatching { MeshScience.peerCount() }.getOrDefault(0) }

    PageScaffold("Science", "A signed notebook, a spirometer, an audiometer and a particle detector") {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ScienceInstruments.ALL.forEach { instrument ->
                    // Protein folding has its own page (PHASE 97); the rail entry for it links
                    // there rather than embedding a second copy.
                    if (instrument.id == "protein") return@forEach
                    val usable = instrument.id != "cosmic" || DesktopCamera.isUsable()
                    OutlinedButton(
                        onClick = { selected = instrument.id },
                        modifier = Modifier.padding(end = 6.dp),
                    ) {
                        Text(
                            instrument.glyph + "  " + instrument.title,
                            fontSize = 11.sp,
                            color = when {
                                instrument.id == selected -> colors.accent
                                !usable -> colors.faint
                                else -> colors.onSurface
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = if (onMesh) Color(0xFF3FBF6F) else Color(0xFF55555F),
                    shape = CircleShape,
                    modifier = Modifier.size(7.dp),
                ) {}
                Spacer(Modifier.width(8.dp))
                Text(
                    if (onMesh) {
                        peers.toString() + " peer(s) — witnessing, coincidence and survey merging " +
                            "are available"
                    } else {
                        "Off the mesh. Every instrument still records; what needs peers is " +
                            "witnessing, coincidence detection and merging a walked survey."
                    },
                    fontSize = 10.sp,
                    color = colors.faint,
                    lineHeight = 15.sp,
                )
            }
            Spacer(Modifier.height(10.dp))

            when (selected) {
                "notebook" -> NotebookPanel(onMesh)
                "rf" -> RfPanel()
                "clinical" -> ClinicalPanel()
                "cosmic" -> CosmicPanel()
                else -> NotebookPanel(onMesh)
            }
        }
    }
}

// ── Lab notebook ────────────────────────────────────────────────────────────

@Composable
private fun NotebookPanel(onMesh: Boolean) {
    val colors = LocalPrismColors.current
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf<List<LabNotebook.Entry>>(emptyList()) }
    var integrity by remember { mutableStateOf<LabNotebook.Integrity?>(null) }
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var revision by remember { mutableStateOf(0) }

    LaunchedEffect(revision) {
        withContext(Dispatchers.IO) {
            entries = runCatching { LabNotebook.all() }.getOrDefault(emptyList())
            integrity = runCatching { LabNotebook.verify() }.getOrNull()
        }
    }

    Column(Modifier.verticalScroll(rememberScrollState())) {
        if (note.isNotBlank()) {
            Surface(
                color = Color(0xFF1E1E26),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
            ) {
                Text(
                    note,
                    fontSize = 12.sp,
                    color = colors.muted,
                    lineHeight = 18.sp,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        SectionHeader("integrity")
        Card {
            Column(Modifier.padding(14.dp)) {
                when (val state = integrity) {
                    is LabNotebook.Integrity.Intact -> Text(
                        "The chain is intact across " + entries.size + " entr" +
                            (if (entries.size == 1) "y" else "ies") + ".",
                        fontSize = 13.sp,
                        color = Color(0xFF9BE8B4),
                    )
                    is LabNotebook.Integrity.Broken -> Text(
                        "BROKEN at entry " + (state.atIndex + 1) + ": " + state.reason +
                            "  Every entry after that one is unverifiable, which is what a hash " +
                            "chain is for — it does not stop tampering, it makes it undeniable.",
                        fontSize = 12.sp,
                        color = Color(0xFFFFB4B4),
                        lineHeight = 18.sp,
                    )
                    null -> Text("Not verified yet.", fontSize = 12.sp, color = colors.faint)
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { revision++ }) {
                        Text("Verify again", fontSize = 12.sp)
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = {
                        val target = java.io.File(
                            PrismPlatform.host.documentsDir(), "prism-lab-notebook.json",
                        )
                        val ok = LabNotebook.exportTo(target)
                        note = if (ok) {
                            "Exported to " + target.absolutePath + " — signatures and all, so " +
                                "somebody else can check it without Prism."
                        } else {
                            "The export failed."
                        }
                    }) { Text("Export", fontSize = 12.sp) }
                }
            }
        }

        SectionHeader("new entry")
        Card {
            Column(Modifier.padding(14.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    placeholder = { Text("What this is", fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it },
                    placeholder = { Text("The observation", fontSize = 12.sp) },
                    modifier = Modifier.fillMaxWidth().height(110.dp),
                    maxLines = 6,
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        enabled = title.isNotBlank() && body.isNotBlank(),
                        onClick = {
                            val recordedTitle = title.trim()
                            val recordedBody = body.trim()
                            title = ""
                            body = ""
                            scope.launch {
                                val entry = withContext(Dispatchers.IO) {
                                    runCatching { LabNotebook.append(recordedTitle, recordedBody) }
                                        .getOrNull()
                                }
                                revision++
                                note = if (entry == null) {
                                    "Could not write the entry."
                                } else {
                                    "Recorded and signed. Hash " + entry.hash.take(16) + "…" +
                                        if (onMesh) {
                                            "  Asking the mesh to witness it."
                                        } else {
                                            "  Nothing can witness it while you are off the mesh, " +
                                                "which is what a witness is for — a timestamp only " +
                                                "this device vouches for is a timestamp this device " +
                                                "could have written at any time."
                                        }
                                }
                                if (entry != null && onMesh) {
                                    withContext(Dispatchers.IO) {
                                        // The title travels with the request so a witness can
                                        // see WHAT it is countersigning rather than a bare hash.
                                        runCatching {
                                            MeshScience.requestWitness(entry.hash, recordedTitle)
                                        }
                                    }
                                }
                            }
                        },
                    ) { Text("Record") }
                    Spacer(Modifier.width(10.dp))
                    MicButton(
                        onText = { heard ->
                            body = if (body.isBlank()) heard else body.trimEnd() + " " + heard
                        },
                        onStatus = { if (it.isNotBlank()) note = it },
                    )
                }
            }
        }

        SectionHeader("entries")
        Card {
            Column {
                if (entries.isEmpty()) {
                    Text(
                        "Nothing recorded yet. Each entry carries the hash of the one before it " +
                            "and a signature from your wallet key, so the order and the authorship " +
                            "are both checkable by anybody with the file.",
                        fontSize = 12.sp,
                        color = colors.muted,
                        lineHeight = 18.sp,
                        modifier = Modifier.padding(14.dp),
                    )
                }
                entries.asReversed().forEachIndexed { index, entry ->
                    if (index > 0) Hairline()
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(entry.title, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            Text(
                                stamp(entry.atMs),
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                color = colors.faint,
                            )
                        }
                        Spacer(Modifier.height(3.dp))
                        Text(entry.body, fontSize = 12.sp, color = colors.muted, lineHeight = 17.sp)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            entry.hash.take(16) + "… · " + entry.witnesses.size + " witness(es)",
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFF6E6E7A),
                        )
                    }
                }
            }
        }
        SectionFooter(
            "The notebook is the same code the phone runs, and an exported chain verifies on " +
                "either — which is the point of signing it rather than just timestamping it."
        )
        Spacer(Modifier.height(24.dp))
    }
}

// ── RF survey ───────────────────────────────────────────────────────────────

@Composable
private fun RfPanel() {
    val colors = LocalPrismColors.current
    val scope = rememberCoroutineScope()
    var samples by remember { mutableStateOf<List<RfSample>>(emptyList()) }
    var point by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }

    val blocked = remember { DesktopWifi.unavailableReason() }

    Column(Modifier.verticalScroll(rememberScrollState())) {
        if (blocked.isNotBlank()) {
            Card {
                Text(
                    blocked,
                    fontSize = 13.sp,
                    color = Color(0xFFE0C060),
                    lineHeight = 19.sp,
                    modifier = Modifier.padding(16.dp),
                )
            }
            return@Column
        }

        Card {
            Column(Modifier.padding(14.dp)) {
                Text(
                    "A survey is a set of readings each labelled with where you were standing. " +
                        "Name the spot, scan, walk, repeat.",
                    fontSize = 12.sp,
                    color = colors.muted,
                    lineHeight = 18.sp,
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = point,
                        onValueChange = { point = it },
                        placeholder = { Text("Where you are", fontSize = 12.sp) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(
                        enabled = !busy,
                        onClick = {
                            busy = true
                            val label = point.trim().ifBlank { "point " + (samples.size + 1) }
                            scope.launch {
                                val found = withContext(Dispatchers.IO) {
                                    DesktopWifi.scan(label)
                                }
                                busy = false
                                samples = samples + found
                                note = if (found.isEmpty()) {
                                    "The scan returned nothing. On Windows that usually means the " +
                                        "wireless adapter is off or not present."
                                } else {
                                    found.size.toString() + " access point(s) at " + label + "."
                                }
                                // Shared per access point, which is the granularity the mesh
                                // protocol uses -- a merged survey is built from samples, so a
                                // summary count would not merge with anything.
                                if (found.isNotEmpty()) {
                                    withContext(Dispatchers.IO) {
                                        found.forEach { sample ->
                                            runCatching {
                                                MeshScience.broadcastSample(
                                                    sample.point,
                                                    sample.ssid,
                                                    sample.rssi,
                                                    sample.frequencyMhz,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        },
                    ) { Text(if (busy) "Scanning…" else "Scan") }
                }
                if (note.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(note, fontSize = 11.sp, color = colors.faint, lineHeight = 16.sp)
                }
            }
        }

        if (samples.isNotEmpty()) {
            SectionHeader("channel congestion")
            Card {
                Column(Modifier.padding(14.dp)) {
                    val congestion = remember(samples) { RfSurvey.congestionByChannel(samples) }
                    // Summed as linear power, not averaged in dBm -- see RfSurvey. A channel with
                    // one strong neighbour is the thing a survey is run to find.
                    val worst = congestion.values.maxOrNull() ?: 0.0
                    congestion.entries.sortedByDescending { it.value }.take(12).forEach { (channel, dbm) ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "ch " + channel,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = colors.faint,
                                modifier = Modifier.width(48.dp),
                            )
                            val fraction = if (worst == 0.0) 0f else
                                ((dbm + 100) / (worst + 100)).toFloat().coerceIn(0f, 1f)
                            Canvas(Modifier.weight(1f).height(8.dp)) {
                                drawRect(color = Color(0xFF23232B), size = size)
                                drawRect(
                                    color = colors.accent,
                                    size = Size(size.width * fraction, size.height),
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Text(
                                String.format("%.0f", dbm) + " dBm",
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                color = colors.muted,
                            )
                        }
                    }
                }
            }

            SectionHeader("readings")
            Card {
                Column {
                    samples.groupBy { it.point }.forEach { (label, group) ->
                        Text(
                            label.lowercase() + " · " + group.size + " AP(s)",
                            fontSize = 10.sp,
                            color = colors.accent,
                            modifier = Modifier.padding(start = 12.dp, top = 9.dp, bottom = 3.dp),
                        )
                        group.sortedByDescending { it.rssi }.forEach { sample ->
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(sample.ssid, fontSize = 12.sp)
                                    Text(
                                        sample.bssid + " · " + RfSurvey.band(sample.frequencyMhz) +
                                            RfSurvey.channel(sample.frequencyMhz)
                                                ?.let { " ch " + it } .orEmpty(),
                                        fontSize = 9.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = Color(0xFF6E6E7A),
                                    )
                                }
                                Text(
                                    sample.rssi.toString() + " dBm · " + RfSurvey.quality(sample.rssi) +
                                        " · ~" + String.format(
                                            "%.0f",
                                            RfSurvey.approximateMetres(sample.rssi, sample.frequencyMhz),
                                        ) + " m",
                                    fontSize = 10.sp,
                                    color = colors.faint,
                                )
                            }
                        }
                    }
                }
            }
            SectionFooter(
                "The signal figures are derived from the percentage Windows and NetworkManager " +
                    "report, not read as dBm — so they are comparable between readings here and " +
                    "approximately comparable with a phone's. The distances assume free space and " +
                    "a typical transmit power; a wall costs more than ten metres of air."
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

// ── Lung and hearing ────────────────────────────────────────────────────────

@Composable
private fun ClinicalPanel() {
    val colors = LocalPrismColors.current
    val scope = rememberCoroutineScope()
    val microphone = remember { DesktopMicrophone() }

    var recording by remember { mutableStateOf(false) }
    var reading by remember { mutableStateOf<Spirometry.Result?>(null) }
    var note by remember { mutableStateOf("") }

    val audiometry = remember { Audiometry() }
    var presentation by remember { mutableStateOf(audiometry.current()) }
    var toneRunning by remember { mutableStateOf(false) }
    var audiometryRevision by remember { mutableStateOf(0) }

    Column(Modifier.verticalScroll(rememberScrollState())) {

        // ── Spirometry ─────────────────────────────────────────────────────
        SectionHeader("forced exhale")
        Card {
            Column(Modifier.padding(14.dp)) {
                Text(
                    "Take as deep a breath as you can, then blow out as hard and as long as you " +
                        "can, close to the microphone. Prism reads the envelope of the sound.",
                    fontSize = 12.sp,
                    color = colors.muted,
                    lineHeight = 18.sp,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "A microphone measures sound pressure, not air flow. The RATIO below is " +
                        "comparable between devices because the calibration cancels; the volumes " +
                        "are relative numbers and nothing here is in litres.",
                    fontSize = 11.sp,
                    color = Color(0xFFE0C060),
                    lineHeight = 16.sp,
                )
                Spacer(Modifier.height(10.dp))

                if (!microphone.isAvailable()) {
                    Text(
                        microphone.unavailableReason(),
                        fontSize = 12.sp,
                        color = Color(0xFFFFB4B4),
                        lineHeight = 17.sp,
                    )
                } else {
                    Button(
                        enabled = !recording,
                        onClick = {
                            recording = true
                            note = "Blow now…"
                            reading = null
                            scope.launch {
                                val envelope = withContext(Dispatchers.IO) {
                                    captureEnvelope(microphone)
                                }
                                recording = false
                                val result = Spirometry.analyse(envelope)
                                reading = result
                                note = when (result) {
                                    is Spirometry.Result.Unusable -> result.reason
                                    is Spirometry.Result.Reading ->
                                        "Recorded " + String.format("%.1f", result.seconds) +
                                            " s of exhale."
                                }
                            }
                        },
                    ) { Text(if (recording) "Listening…" else "Record a blow") }
                }

                if (note.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(note, fontSize = 11.sp, color = colors.faint, lineHeight = 16.sp)
                }

                (reading as? Spirometry.Result.Reading)?.let { result ->
                    Spacer(Modifier.height(10.dp))
                    Canvas(Modifier.fillMaxWidth().height(110.dp)) {
                        drawRect(color = Color(0xFF14141A), size = size)
                        val peak = result.peak.coerceAtLeast(0.0001)
                        val n = result.curve.size.coerceAtLeast(2)
                        // A flow-volume style trace: time across, amplitude up. The SHAPE is what a
                        // clinician reads -- a sharp rise and a long concave tail is obstruction --
                        // and the shape survives the lack of calibration.
                        for (i in 0 until n - 1) {
                            val x1 = size.width * i / (n - 1)
                            val x2 = size.width * (i + 1) / (n - 1)
                            val y1 = size.height * (1f - (result.curve[i] / peak).toFloat())
                            val y2 = size.height * (1f - (result.curve[i + 1] / peak).toFloat())
                            drawLine(
                                color = colors.accent,
                                start = Offset(x1, y1),
                                end = Offset(x2, y2),
                                strokeWidth = 1.6f,
                            )
                        }
                        // A one-second marker, because FEV1 is the area to the left of it.
                        val oneSecond = size.width *
                            Spirometry.FRAMES_PER_SECOND.coerceAtMost(n) / n
                        drawLine(
                            color = Color(0x66FFFFFF),
                            start = Offset(oneSecond, 0f),
                            end = Offset(oneSecond, size.height),
                            strokeWidth = 1f,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "FEV1/FVC " + String.format("%.2f", result.ratio) +
                            "   ·   peak " + String.format("%.0f", result.peak) + " (relative)" +
                            "   ·   volume " + String.format("%.0f", result.fvc) + " (relative)",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                    Spacer(Modifier.height(5.dp))
                    Text(
                        result.interpretation,
                        fontSize = 11.sp,
                        color = colors.muted,
                        lineHeight = 16.sp,
                    )
                }
            }
        }

        // ── Audiometry ─────────────────────────────────────────────────────
        SectionHeader("pure-tone audiometry")
        Card {
            Column(Modifier.padding(14.dp)) {
                Text(
                    "Headphones, somewhere quiet. A tone is played in one ear at a time; press " +
                        "\"heard it\" only when you are sure. The procedure is the Hughson-Westlake " +
                        "ladder — down in 10 dB steps, up in 5 — which is why it takes a while.",
                    fontSize = 12.sp,
                    color = colors.muted,
                    lineHeight = 18.sp,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "These are attenuations below this machine's full scale, through whatever " +
                        "headphones are plugged in — not dB HL. The shape of the curve means " +
                        "something; the absolute numbers do not.",
                    fontSize = 11.sp,
                    color = Color(0xFFE0C060),
                    lineHeight = 16.sp,
                )
                Spacer(Modifier.height(10.dp))

                val current = presentation
                if (current == null) {
                    Text(
                        "Sweep finished. " + audiometry.summary(),
                        fontSize = 12.sp,
                        color = colors.muted,
                        lineHeight = 18.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = {
                        audiometry.reset()
                        presentation = audiometry.current()
                        audiometryRevision++
                    }) { Text("Start again", fontSize = 12.sp) }
                } else {
                    Text(
                        current.frequencyHz.toString() + " Hz, " +
                            (if (current.rightEar) "right" else "left") + " ear, −" +
                            current.attenuationDb + " dB",
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            enabled = !toneRunning,
                            onClick = {
                                toneRunning = true
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        playTone(
                                            current.frequencyHz,
                                            current.attenuationDb,
                                            current.rightEar,
                                        )
                                    }
                                    toneRunning = false
                                }
                            },
                        ) { Text(if (toneRunning) "Playing…" else "Play the tone") }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(
                            enabled = !toneRunning,
                            onClick = {
                                audiometry.respond(true)
                                presentation = audiometry.current()
                                audiometryRevision++
                            },
                        ) { Text("Heard it", fontSize = 12.sp) }
                        Spacer(Modifier.width(6.dp))
                        OutlinedButton(
                            enabled = !toneRunning,
                            onClick = {
                                audiometry.respond(false)
                                presentation = audiometry.current()
                                audiometryRevision++
                            },
                        ) { Text("Nothing", fontSize = 12.sp) }
                    }
                }

                // The audiogram so far. Drawn from both ears at once, because the comparison
                // between them is half of what the test is for.
                val right = audiometry.rightThresholds
                val left = audiometry.leftThresholds
                if (right.isNotEmpty() || left.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Canvas(Modifier.fillMaxWidth().height(140.dp)) {
                        drawRect(color = Color(0xFF14141A), size = size)
                        val sweep = Audiometry.FREQUENCIES.sorted()
                        fun xOf(frequency: Int): Float =
                            size.width * sweep.indexOf(frequency) / (sweep.size - 1).coerceAtLeast(1)
                        // Attenuation runs DOWN the chart, so a bigger attenuation (better hearing)
                        // is higher -- which is inverted from a clinical audiogram on purpose: this
                        // is not dB HL and drawing it like one would invite reading it like one.
                        fun yOf(db: Int): Float =
                            size.height * (1f - (db.toFloat() / Audiometry.QUIETEST))

                        listOf(right to Color(0xFFE06060), left to Color(0xFF6090E0)).forEach { (map, colour) ->
                            val points = sweep.mapNotNull { f -> map[f]?.let { f to it } }
                            points.zipWithNext().forEach { (a, b) ->
                                drawLine(
                                    color = colour,
                                    start = Offset(xOf(a.first), yOf(a.second)),
                                    end = Offset(xOf(b.first), yOf(b.second)),
                                    strokeWidth = 1.8f,
                                )
                            }
                            points.forEach { (f, db) ->
                                drawCircle(colour, 3.5f, Offset(xOf(f), yOf(db)))
                            }
                        }
                    }
                    Spacer(Modifier.height(5.dp))
                    Text(
                        "red = right ear, blue = left · higher is better · " +
                            right.size + "/" + Audiometry.FREQUENCIES.size + " right, " +
                            left.size + "/" + Audiometry.FREQUENCIES.size + " left",
                        fontSize = 10.sp,
                        color = colors.faint,
                    )
                }
            }
        }
        SectionFooter(
            "The spirometry reading and the audiometry ladder both live in :core, so a measurement " +
                "taken here and one taken on the phone are interpreted by the same code — which " +
                "matters more for this instrument than for any other on the page."
        )
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * Records a forced exhale and reduces it to a 50 Hz envelope.
 *
 * The envelope, not the waveform: the analysis wants amplitude over time, and 50 frames a second is
 * enough to resolve the start of the manoeuvre and the one-second mark while being small enough to
 * hold and draw. Six seconds is longer than any forced exhale.
 */
private fun captureEnvelope(microphone: DesktopMicrophone): List<Double> {
    val pcm = runCatching { microphone.recordFor(6) { } }.getOrNull() ?: return emptyList()
    if (pcm.isEmpty()) return emptyList()
    // DesktopMicrophone produces 16 kHz mono float. 320 samples is one 50 Hz frame.
    val perFrame = 16_000 / Spirometry.FRAMES_PER_SECOND
    val frames = ArrayList<Double>(pcm.size / perFrame + 1)
    var i = 0
    while (i + perFrame <= pcm.size) {
        var sum = 0.0
        for (j in 0 until perFrame) {
            val value = pcm[i + j].toDouble()
            sum += value * value
        }
        // RMS, scaled so the numbers read like the phone's envelope rather than like 0.0003.
        frames.add(kotlin.math.sqrt(sum / perFrame) * 1000.0)
        i += perFrame
    }
    return frames
}

/**
 * Plays one pure tone in one ear.
 *
 * STEREO WITH ONE SILENT CHANNEL, which is the whole point: an audiometry tone has to reach one ear
 * only, and a mono sink would play it in both. The platform sink is mono by design (it exists for
 * speech), so this goes straight to `javax.sound` with a two-channel format.
 *
 * A raised-cosine ramp at each end rather than a hard start: a square-edged tone has a click, the
 * click is broadband, and a listener who hears the click reports hearing the tone.
 */
private fun playTone(frequencyHz: Int, attenuationDb: Int, rightEar: Boolean) {
    runCatching {
        val rate = 44_100
        val seconds = 1.5
        val total = (rate * seconds).toInt()
        val ramp = (rate * 0.02).toInt()
        val amplitude = Math.pow(10.0, -attenuationDb / 20.0)

        val format = javax.sound.sampled.AudioFormat(
            javax.sound.sampled.AudioFormat.Encoding.PCM_SIGNED,
            rate.toFloat(), 16, 2, 4, rate.toFloat(), false,
        )
        val line = javax.sound.sampled.AudioSystem.getLine(
            javax.sound.sampled.DataLine.Info(javax.sound.sampled.SourceDataLine::class.java, format),
        ) as javax.sound.sampled.SourceDataLine
        line.open(format, rate / 4 * 4)
        line.start()

        val bytes = ByteArray(total * 4)
        for (n in 0 until total) {
            val envelope = when {
                n < ramp -> 0.5 * (1 - Math.cos(Math.PI * n / ramp))
                n > total - ramp -> 0.5 * (1 - Math.cos(Math.PI * (total - n) / ramp))
                else -> 1.0
            }
            val sample = Math.sin(2 * Math.PI * frequencyHz * n / rate) * amplitude * envelope
            val value = (sample.coerceIn(-1.0, 1.0) * 32767).toInt()
            val leftValue = if (rightEar) 0 else value
            val rightValue = if (rightEar) value else 0
            bytes[n * 4] = (leftValue and 0xFF).toByte()
            bytes[n * 4 + 1] = ((leftValue shr 8) and 0xFF).toByte()
            bytes[n * 4 + 2] = (rightValue and 0xFF).toByte()
            bytes[n * 4 + 3] = ((rightValue shr 8) and 0xFF).toByte()
        }
        line.write(bytes, 0, bytes.size)
        line.drain()
        line.stop()
        line.close()
    }
}

// ── Cosmic rays ─────────────────────────────────────────────────────────────

/**
 * The particle detector, which needs a camera and says so when there is none.
 *
 * This is the instrument the phase singled out, and the user's instruction was explicit: the camera
 * parts are DISABLED on a machine without one. The two unavailable states are reported separately
 * because they ask different things of the user -- "this machine has no camera" is final, and "a
 * camera but nothing to read it with" is fixed by installing ffmpeg.
 */
@Composable
private fun CosmicPanel() {
    val colors = LocalPrismColors.current
    val scope = rememberCoroutineScope()

    var presence by remember { mutableStateOf(DesktopCamera.presence()) }
    val capture = remember(presence) { DesktopCamera.captureCommand() }
    var running by remember { mutableStateOf(false) }
    var detector by remember { mutableStateOf<CosmicDetector?>(null) }
    var status by remember { mutableStateOf("") }
    var recent by remember { mutableStateOf<List<String>>(emptyList()) }
    var coincidences by remember { mutableStateOf(0) }
    var startedAt by remember { mutableStateOf(0L) }

    Column(Modifier.verticalScroll(rememberScrollState())) {
        Card {
            Column(Modifier.padding(16.dp)) {
                when {
                    !presence.available -> {
                        Text("No camera on this machine", fontSize = 14.sp)
                        Spacer(Modifier.height(5.dp))
                        Text(
                            presence.reason,
                            fontSize = 12.sp,
                            color = colors.muted,
                            lineHeight = 18.sp,
                        )
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(onClick = { presence = DesktopCamera.recheck() }) {
                            Text("Check again", fontSize = 12.sp)
                        }
                    }

                    capture == null -> {
                        Text(
                            "A camera, but no way to read it",
                            fontSize = 14.sp,
                        )
                        Spacer(Modifier.height(5.dp))
                        Text(
                            "Found " + presence.devices.first() + ". Prism reads frames through " +
                                "ffmpeg on the desktop rather than binding Media Foundation or " +
                                "V4L2 directly — install ffmpeg and put it on PATH, and this " +
                                "instrument works. There is no native capture in this build, and " +
                                "a detector that silently produced zero hits would be " +
                                "indistinguishable from a quiet night.",
                            fontSize = 12.sp,
                            color = colors.muted,
                            lineHeight = 18.sp,
                        )
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(onClick = { presence = DesktopCamera.recheck() }) {
                            Text("Check again", fontSize = 12.sp)
                        }
                    }

                    else -> {
                        Text(
                            "COVER THE CAMERA COMPLETELY before starting. A particle detector is a " +
                                "camera in the dark: any light at all swamps it, and a lit frame is " +
                                "discarded rather than counted.",
                            fontSize = 12.sp,
                            color = Color(0xFFE0C060),
                            lineHeight = 18.sp,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(DesktopCamera.describe(), fontSize = 11.sp, color = colors.faint)
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Button(onClick = {
                                if (running) {
                                    running = false
                                    return@Button
                                }
                                val fresh = CosmicDetector()
                                detector = fresh
                                recent = emptyList()
                                coincidences = 0
                                startedAt = System.currentTimeMillis()
                                running = true
                                status = "Learning the sensor's own defects…"
                                scope.launch {
                                    withContext(Dispatchers.IO) {
                                        runExposure(
                                            device = presence.devices.first(),
                                            detector = fresh,
                                            keepGoing = { running },
                                            onStatus = { status = it },
                                            onHit = { line, coincident ->
                                                recent = (listOf(line) + recent).take(8)
                                                if (coincident) coincidences++
                                            },
                                        )
                                    }
                                    running = false
                                }
                            }) { Text(if (running) "Stop the exposure" else "Start the exposure") }
                            Spacer(Modifier.width(10.dp))
                            detector?.let {
                                Text(
                                    it.hits.toString() + " hit(s) · " + it.framesAnalysed +
                                        " frames · " + it.hotPixelCount + " hot pixel(s)",
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = colors.faint,
                                )
                            }
                        }
                        if (status.isNotBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Text(status, fontSize = 11.sp, color = colors.muted, lineHeight = 16.sp)
                        }
                        detector?.let {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                it.verdict(
                                    coincidences,
                                    (System.currentTimeMillis() - startedAt) / 1000.0,
                                ),
                                fontSize = 11.sp,
                                color = colors.muted,
                                lineHeight = 16.sp,
                            )
                        }
                    }
                }
            }
        }

        if (recent.isNotEmpty()) {
            SectionHeader("events")
            Card {
                Column(Modifier.padding(12.dp)) {
                    recent.forEach {
                        Text(
                            it,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = colors.muted,
                            lineHeight = 15.sp,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * Pulls greyscale frames out of ffmpeg and hands each one to the detector.
 *
 * Raw video on stdout, so the frames arrive back to back with no container and no headers: a frame is
 * exactly `width * height` bytes and the loop reads that many. The alternative -- asking for a
 * container -- would mean demuxing it, for data this already knows the shape of.
 */
private fun runExposure(
    device: String,
    detector: CosmicDetector,
    keepGoing: () -> Boolean,
    onStatus: (String) -> Unit,
    onHit: (String, Boolean) -> Unit,
) {
    val width = 640
    val height = 480
    val args = DesktopCamera.captureArgs(device, width, height, fps = 5) ?: run {
        onStatus("No capture command is available.")
        return
    }

    val process = runCatching {
        ProcessBuilder(args).redirectErrorStream(false).start()
    }.getOrElse {
        onStatus("The camera would not open: " + it.message)
        return
    }

    val frame = ByteArray(width * height)
    runCatching {
        process.inputStream.use { input ->
            while (keepGoing()) {
                var read = 0
                while (read < frame.size) {
                    val n = input.read(frame, read, frame.size - read)
                    if (n <= 0) return@use
                    read += n
                }
                when (val outcome = detector.analyse(frame, width, height)) {
                    is CosmicDetector.Outcome.Calibrating -> onStatus(
                        "Learning which pixels are simply broken — " + outcome.remaining +
                            " frame(s) to go.",
                    )
                    is CosmicDetector.Outcome.Lit -> onStatus(
                        "Light is reaching the sensor (mean level " +
                            String.format("%.1f", outcome.meanLuma) +
                            "). Cover the camera completely — frames are being discarded.",
                    )
                    CosmicDetector.Outcome.Quiet -> onStatus("Dark and quiet.")
                    is CosmicDetector.Outcome.Hit -> {
                        val at = System.currentTimeMillis()
                        // Broadcast and matched through the same mesh code the phone uses, so a
                        // coincidence between a laptop and a phone is a coincidence.
                        runCatching { MeshScience.broadcastHit(at, outcome.peak, outcome.brightPixels) }
                        val match = runCatching { MeshScience.matchCoincidence(at) }.getOrNull()
                        onStatus("Dark and quiet.")
                        onHit(
                            stamp(at) + "  " + outcome.brightPixels + "px  peak " + outcome.peak +
                                (match?.let { "  ✓ coincident with " + it.peerIp } ?: ""),
                            match != null,
                        )
                    }
                }
            }
        }
    }
    runCatching { process.destroyForcibly() }
}

private val STAMP = SimpleDateFormat("HH:mm:ss", Locale.US)

private fun stamp(at: Long): String = STAMP.format(Date(at))
