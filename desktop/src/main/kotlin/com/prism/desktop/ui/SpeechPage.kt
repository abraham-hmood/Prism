package com.prism.desktop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prism.core.PrismPlatform
import com.prism.desktop.DesktopSystemTts
import com.prism.desktop.describeAudioOutput
import com.prism.launcher.PrismSettings
import com.prism.launcher.speech.KokoroInstall
import com.prism.launcher.speech.KokoroVoices
import com.prism.launcher.speech.PrismSpeaker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Speech. PHASE 100.
 *
 * ## The shape of the page follows the shape of the feature
 *
 * Kokoro is not a setting with a toggle; it is an 86 MB download, a per-voice half-megabyte tensor and
 * a speed. So the page is: what is speaking right now, the model, the voices, and a way to hear one.
 * The last of those is not decoration -- a voice can only be judged by ear, and a picker that lists
 * fifty-four names with no preview is a list of strings.
 *
 * ## Why the engine in use is named rather than assumed
 *
 * "Kokoro" and "Windows SAPI" do not sound remotely alike, and before the download finishes the second
 * one is what talks. A page that said "Kokoro-82M" regardless would be lying at exactly the moment the
 * user is most likely to be wondering what they are hearing.
 *
 * ## The one thing this page will tell you that the phone's never has to
 *
 * Whether the machine can make a sound at all. A phone has a speaker; a desktop may be a headless
 * server, may have its only output device claimed exclusively by something else, or on Linux may have
 * no system speech engine whatsoever. All three are reported with the reason, because "the voice does
 * not work" with nothing to go on is the worst possible outcome for a feature like this.
 */
@Composable
fun SpeechPage() {
    val colors = LocalPrismColors.current
    val scope = rememberCoroutineScope()

    var revision by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    var noteOk by remember { mutableStateOf(true) }
    var progress by remember { mutableStateOf(0f) }
    var progressLabel by remember { mutableStateOf("") }

    // Read once per revision rather than on every recomposition: these are file-system checks, and
    // installedVoices() lists a directory.
    var installed by remember { mutableStateOf(false) }
    var installedVoices by remember { mutableStateOf<Set<String>>(emptySet()) }
    var installedMb by remember { mutableStateOf(0L) }
    var engineLabel by remember { mutableStateOf("") }

    val audioReady = remember { PrismPlatform.audio.isAvailable() }
    val audioNote = remember { if (audioReady) describeAudioOutput() else PrismPlatform.audio.unavailableReason() }
    val systemVoice = remember { DesktopSystemTts.openOrNull()?.displayName }

    LaunchedEffect(revision) {
        withContext(Dispatchers.IO) {
            installed = runCatching { KokoroInstall.isModelInstalled() }.getOrDefault(false)
            installedVoices = runCatching { KokoroInstall.installedVoices() }.getOrDefault(emptySet())
            installedMb = runCatching { KokoroInstall.installedBytes() shr 20 }.getOrDefault(0L)
            engineLabel = runCatching { PrismSpeaker.describeEngine() }.getOrDefault("unknown")
        }
    }

    fun say(text: String, voiceId: String?) {
        note = "Speaking…"
        noteOk = true
        PrismSpeaker.speakAs(text = text, voiceId = voiceId) { error ->
            noteOk = error == null
            note = error ?: ("Spoken through " + (PrismSpeaker.activeEngineName ?: "an engine"))
        }
    }

    PageScaffold("Speech", "Kokoro-82M, its voices, and what this machine can actually play") {
        Column(Modifier.verticalScroll(rememberScrollState())) {

            if (note.isNotBlank()) {
                Surface(
                    color = if (noteOk) Color(0xFF14301F) else Color(0xFF331A1A),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                ) {
                    Text(
                        note,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = if (noteOk) Color(0xFF9BE8B4) else Color(0xFFFFB4B4),
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }

            // ── Output ─────────────────────────────────────────────────────────
            SectionHeader("audio output")
            Card {
                Column(Modifier.padding(16.dp)) {
                    StateRow(
                        ok = audioReady,
                        label = if (audioReady) "This machine can play audio" else "No usable output",
                        detail = audioNote,
                    )
                    Spacer(Modifier.height(12.dp))
                    StateRow(
                        ok = systemVoice != null,
                        label = systemVoice?.let { "System voice: " + it }
                            ?: "No system voice on this machine",
                        detail = systemVoice?.let {
                            "Used only while Kokoro is downloading, or if ONNX Runtime will not " +
                                "start here. It is the floor, not the plan."
                        } ?: DesktopSystemTts.unavailableReason(),
                    )
                }
            }

            // ── The model ──────────────────────────────────────────────────────
            SectionHeader("the model")
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text("Speaking with: " + engineLabel, fontSize = 13.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (installed) {
                            KokoroInstall.variantLabel(PrismSettings.getKokoroVariant()) +
                                " — " + installedMb + " MB on disk, " + installedVoices.size +
                                " voice(s) downloaded"
                        } else {
                            KokoroInstall.variantSizeMb(PrismSettings.getKokoroVariant()).toString() +
                                " MB to download. Prism's own voice needs nothing from the system " +
                                "once it is here."
                        },
                        fontSize = 11.sp,
                        color = colors.faint,
                        lineHeight = 16.sp,
                    )

                    if (progressLabel.isNotBlank()) {
                        Spacer(Modifier.height(10.dp))
                        Text(progressLabel, fontSize = 11.sp, color = colors.muted)
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = { progress },
                            color = colors.accent,
                            modifier = Modifier.fillMaxWidth().height(4.dp),
                        )
                    }

                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (!installed) {
                            Button(
                                enabled = !busy,
                                onClick = {
                                    busy = true
                                    note = ""
                                    progressLabel = "Starting…"
                                    scope.launch {
                                        val problem = withContext(Dispatchers.IO) {
                                            KokoroInstall.downloadModel { copied, total ->
                                                progress = if (total > 0) {
                                                    (copied.toFloat() / total).coerceIn(0f, 1f)
                                                } else 0f
                                                progressLabel = (copied shr 20).toString() + " MB" +
                                                    (if (total > 0) " of " + (total shr 20) + " MB" else "")
                                            }
                                        }
                                        // The default voice too, in the same action: a model with no
                                        // voice tensor cannot speak, and asking the user to notice
                                        // that as a second step is how the feature looks broken.
                                        val voiceProblem = if (problem != null) null else {
                                            withContext(Dispatchers.IO) {
                                                KokoroInstall.downloadVoice(
                                                    PrismSettings.getKokoroVoice(
                                                        PrismSettings.VOICE_SPEAKER_SAM,
                                                    ),
                                                )
                                            }
                                        }
                                        PrismSpeaker.invalidate()
                                        progressLabel = ""
                                        busy = false
                                        noteOk = problem == null && voiceProblem == null
                                        note = problem ?: voiceProblem ?: "Kokoro is installed."
                                        revision++
                                    }
                                },
                            ) { Text("Download Kokoro") }
                        } else {
                            OutlinedButton(
                                enabled = !busy,
                                onClick = {
                                    // Sam's voice explicitly: a null voiceId means "the preferred
                                    // engine cannot say this, use the system one", so the obvious
                                    // null here would demonstrate SAPI rather than Kokoro.
                                    say(
                                        "Hello. This is how I sound.",
                                        PrismSettings.getKokoroVoice(PrismSettings.VOICE_SPEAKER_SAM),
                                    )
                                },
                            ) { Text("Say something", fontSize = 13.sp) }
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(
                                enabled = !busy,
                                onClick = {
                                    busy = true
                                    scope.launch {
                                        withContext(Dispatchers.IO) { KokoroInstall.uninstall() }
                                        PrismSpeaker.invalidate()
                                        busy = false
                                        noteOk = true
                                        note = "Removed. The system voice stands in again."
                                        revision++
                                    }
                                },
                            ) { Text("Remove", fontSize = 13.sp) }
                        }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(enabled = !busy, onClick = { PrismSpeaker.stop() }) {
                            Text("Stop", fontSize = 13.sp)
                        }
                    }
                }
            }

            // ── Quantisation ───────────────────────────────────────────────────
            SectionHeader("precision")
            Card {
                Column {
                    KokoroInstall.VARIANTS.forEachIndexed { index, variant ->
                        if (index > 0) Hairline()
                        val active = PrismSettings.getKokoroVariant() == variant
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickableRow {
                                    if (busy) return@clickableRow
                                    PrismSettings.setKokoroVariant(variant)
                                    PrismSpeaker.invalidate()
                                    revision++
                                }
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    KokoroInstall.variantLabel(variant),
                                    fontSize = 13.sp,
                                    color = if (active) colors.accent else colors.onSurface,
                                )
                                Text(
                                    variant,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = Color(0xFF6E6E7A),
                                )
                            }
                            if (active) Text("in use", fontSize = 11.sp, color = colors.accent)
                        }
                    }
                }
            }
            SectionFooter(
                "A desktop has no reason to prefer the 86 MB quantisation the way a phone does. " +
                    "The full-precision export is 325 MB and sounds measurably better; changing " +
                    "this downloads the new one on next use rather than converting anything."
            )

            // ── Speed ──────────────────────────────────────────────────────────
            SectionHeader("speaking rate")
            Card {
                Column(Modifier.padding(16.dp)) {
                    val speed = PrismSettings.getKokoroSpeed()
                    Text(
                        String.format("%.2f", speed) + "x",
                        fontSize = 13.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        listOf(0.75f, 0.9f, 1.0f, 1.15f, 1.3f, 1.5f).forEach { candidate ->
                            OutlinedButton(
                                onClick = {
                                    PrismSettings.setKokoroSpeed(candidate)
                                    revision++
                                },
                                modifier = Modifier.padding(end = 6.dp),
                            ) {
                                Text(
                                    String.format("%.2f", candidate),
                                    fontSize = 12.sp,
                                    color = if (candidate == speed) colors.accent else colors.onSurface,
                                )
                            }
                        }
                    }
                }
            }

            // ── Voices ─────────────────────────────────────────────────────────
            SectionHeader("voices")
            Card {
                Column {
                    // Grouped by language, because fifty-four names in one list is unreadable and
                    // language is the only axis anybody actually chooses along first.
                    KokoroVoices.Language.entries.forEach { language ->
                        val voices = KokoroVoices.ALL.filter { it.language == language }
                        if (voices.isEmpty()) return@forEach
                        Row(
                            Modifier.fillMaxWidth().padding(start = 14.dp, top = 12.dp, bottom = 4.dp),
                        ) {
                            Text(
                                language.display,
                                fontSize = 11.sp,
                                color = colors.accent,
                            )
                        }
                        voices.forEach { voice ->
                            VoiceRow(
                                voice = voice,
                                downloaded = voice.id in installedVoices,
                                busy = busy,
                                assignedTo = SPEAKERS.filter {
                                    PrismSettings.getKokoroVoice(it) == voice.id
                                },
                                onPreview = {
                                    busy = true
                                    scope.launch {
                                        // Fetched on demand: half a megabyte each, and nobody wants
                                        // all fifty-four. This is the whole install step for a voice.
                                        val problem = withContext(Dispatchers.IO) {
                                            KokoroInstall.downloadVoice(voice.id)
                                        }
                                        busy = false
                                        revision++
                                        if (problem != null) {
                                            noteOk = false
                                            note = problem
                                        } else {
                                            say("Hello. This is how I sound.", voice.id)
                                        }
                                    }
                                },
                                onAssign = { speaker ->
                                    PrismSettings.setKokoroVoice(speaker, voice.id)
                                    PrismSpeaker.invalidate()
                                    revision++
                                    noteOk = true
                                    note = speaker + " will be heard as " + voice.displayName + "."
                                },
                            )
                        }
                    }
                }
            }

            SectionFooter(
                "The voices are the same fifty-four the phone has, from the same export, and a " +
                    "voice chosen here carries over on a profile transfer. Kokoro's ONNX call, " +
                    "phonemiser and style tensors are shared code — only the last step out to the " +
                    "speaker differs between the two platforms."
            )
            Spacer(Modifier.height(28.dp))
        }
    }
}

/**
 * A green or red dot, a claim and the reason behind it.
 *
 * Its own composable rather than SystemPages' CheckRow, which is private there and shaped around the
 * search diagnostics' own Check type -- reusing it would mean widening a type for a caller that has
 * nothing to do with search.
 */
@Composable
private fun StateRow(ok: Boolean, label: String, detail: String) {
    val colors = LocalPrismColors.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Surface(
            color = if (ok) Color(0xFF3FBF6F) else Color(0xFFE06060),
            shape = RoundedCornerShape(4.dp),
            modifier = Modifier.padding(top = 5.dp).width(7.dp).height(7.dp),
        ) {}
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 13.sp)
            if (detail.isNotBlank()) {
                Text(detail, fontSize = 11.sp, color = colors.faint, lineHeight = 16.sp)
            }
        }
    }
}

/** Who owns a voice. The same three speakers the phone stores one against. */
private val SPEAKERS = listOf(
    PrismSettings.VOICE_SPEAKER_SAM,
    PrismSettings.VOICE_SPEAKER_NORA,
    PrismSettings.VOICE_SPEAKER_AETHER,
)

@Composable
private fun VoiceRow(
    voice: KokoroVoices.Voice,
    downloaded: Boolean,
    busy: Boolean,
    assignedTo: List<String>,
    onPreview: () -> Unit,
    onAssign: (String) -> Unit,
) {
    val colors = LocalPrismColors.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(voice.label, fontSize = 13.sp)
            Text(
                voice.id + (if (downloaded) "" else " — not downloaded yet"),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = Color(0xFF6E6E7A),
            )
            if (assignedTo.isNotEmpty()) {
                Text(
                    "used by " + assignedTo.joinToString(", "),
                    fontSize = 10.sp,
                    color = colors.accent,
                )
            }
        }
        // Preview downloads the voice first when it has to. Pressing "hear it" and getting an error
        // about a missing file would be a pointless extra step for the user to perform by hand.
        Text(
            if (downloaded) "hear it" else "get & hear",
            fontSize = 12.sp,
            color = if (busy) colors.faint else colors.accent,
            modifier = Modifier.clickableRow { if (!busy) onPreview() }.padding(6.dp),
        )
        SPEAKERS.forEach { speaker ->
            Text(
                speaker.lowercase(),
                fontSize = 11.sp,
                color = if (speaker in assignedTo) colors.accent else colors.faint,
                modifier = Modifier.clickableRow { if (!busy) onAssign(speaker) }.padding(5.dp),
            )
        }
    }
}
