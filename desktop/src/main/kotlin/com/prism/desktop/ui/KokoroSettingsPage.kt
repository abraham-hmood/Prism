package com.prism.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.prism.core.PrismPlatform
import com.prism.launcher.PrismSettings
import com.prism.launcher.speech.ClonedVoices
import com.prism.launcher.speech.KokoroInstall
import com.prism.launcher.speech.KokoroVoices
import com.prism.launcher.speech.PrismSpeaker

/**
 * Everything about Kokoro-82M, on one screen -- the phone's KokoroSettingsActivity, ported.
 *
 * The phone reaches this from Intelligence & Messaging, greyed out whenever the user has imported
 * a speech model of their own; the desktop's settings row for it says so instead of greying,
 * because a catalog Action is answered with a sentence rather than a disabled view.
 *
 * Sam's voice is set here because Sam has no settings screen of his own; Nora's and Aether's are
 * set on theirs, next to everything else about them -- that, and nothing else, is why this page's
 * voice section is titled the way it is.
 */
@Composable
fun KokoroSettingsPage(onNavigate: (PageId) -> Unit = {}) {
    val colors = LocalPrismColors.current

    // Bumped by every action that changes what the rows show: picking, downloading, removing.
    var revision by remember { mutableStateOf(0) }
    var downloading by remember { mutableStateOf(false) }
    var progressLine by remember { mutableStateOf("") }
    var noteOk by remember { mutableStateOf(true) }
    var note by remember { mutableStateOf("") }
    var panel by remember { mutableStateOf<KokoroPanel?>(null) }

    fun say(message: String, ok: Boolean = true) {
        noteOk = ok
        note = message
    }

    // The phone's fail(): same message, filed where System Diagnostics keeps it, and pointed to.
    fun fail(message: String, error: Throwable? = null) {
        PrismPlatform.log.error("KokoroSettings", message, error)
        say("$message — see System Diagnostics.", ok = false)
    }

    // Everything the rows show is computed against [revision], so a successful pick, model or
    // download repaints them instead of relying on the scroll position to re-read live files.
    val statusText = remember(revision) {
        buildString {
            append("Speaking with: ")
            append(PrismSpeaker.describeEngine())
            if (KokoroInstall.isModelInstalled()) {
                append("\nInstalled: ")
                append(KokoroInstall.installedBytes() shr 20)
                append(" MB, ")
                append(KokoroInstall.installedVoices().size)
                append(" voice(s)")
            }
        }
    }
    val voiceDetail = remember(revision) {
        kokoroVoiceSummary(PrismSettings.getKokoroVoice(PrismSettings.VOICE_SPEAKER_SAM))
    }
    val modelInstalled = remember(revision) { KokoroInstall.isModelInstalled() }

    PageScaffold("Kokoro-82M Voice & Speech", subtitle = "Everything about Kokoro on one screen") {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            NoteBar(note, noteOk)

            // ── Status ────────────────────────────────────────────────────
            SectionHeader("SPEECH")
            Card {
                Text(
                    statusText,
                    fontSize = 14.sp,
                    lineHeight = 21.sp,
                    color = colors.onSurface,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
            SectionFooter(
                "Kokoro is an 82M-parameter speech model. Prism uses the ONNX export of it, " +
                    "because the original weights are published only as PyTorch.",
            )
            Spacer(Modifier.height(18.dp))

            // ── Voice ─────────────────────────────────────────────────────
            SectionHeader("SAM'S VOICE")
            Card {
                NavRow("Voice", voiceDetail) { panel = KokoroPanel.VOICE }
                Hairline()
                NavRow("Clone a voice", "Record or upload a sample") {
                    onNavigate(PageId.VOICE_CLONING)
                }
                Hairline()
                NavRow("Speed", "${PrismSettings.getKokoroSpeed()}×") { panel = KokoroPanel.SPEED }
                Hairline()
                ToggleRow(
                    "Speak replies aloud",
                    "Read every reply out, not just the ones in a call",
                    checked = PrismSettings.getSpeakRepliesAloud(),
                    onChange = { PrismSettings.setSpeakRepliesAloud(it) },
                )
            }
            SectionFooter(
                "Nora's and Aether's voices are set on their own screens, so each of them sounds " +
                    "like itself on a call.",
            )
            Spacer(Modifier.height(18.dp))

            // ── Model ─────────────────────────────────────────────────────
            SectionHeader("MODEL")
            Card {
                NavRow(
                    if (modelInstalled) "Remove" else "Download",
                    when {
                        downloading -> progressLine.ifEmpty { "Downloading…" }
                        modelInstalled -> KokoroInstall.variantLabel(PrismSettings.getKokoroVariant())
                        else -> "${KokoroInstall.variantSizeMb(PrismSettings.getKokoroVariant())} MB — " +
                            "needed before Kokoro can speak"
                    },
                ) {
                    if (downloading) return@NavRow
                    if (modelInstalled) {
                        panel = KokoroPanel.REMOVE_MODEL
                    } else {
                        downloading = true
                        progressLine = "Downloading…"
                        val variant = PrismSettings.getKokoroVariant()
                        Thread({
                            val error = KokoroInstall.downloadModel(variant) { copied, total ->
                                if (total > 0) {
                                    val pct = (copied * 100 / total).toInt()
                                    progressLine = "Downloading Kokoro… $pct%"
                                }
                            }
                            // The chosen voice is useless without its tensor, so it comes along
                            // with the model rather than waiting for the picker to be opened again.
                            if (error == null) {
                                KokoroInstall.downloadVoice(
                                    PrismSettings.getKokoroVoice(PrismSettings.VOICE_SPEAKER_SAM),
                                )
                            }
                            downloading = false
                            if (error != null) {
                                fail(error)
                            } else {
                                PrismSpeaker.invalidate()
                                revision++
                            }
                        }, "kokoro-download").apply { isDaemon = true; start() }
                    }
                }
                Hairline()
                NavRow("Quality", "Which published quantisation to use") { panel = KokoroPanel.QUALITY }
            }
            SectionFooter(
                "Voices are fetched one at a time when you pick them — half a megabyte each, " +
                    "rather than 28 MB for all 54.",
            )
            Spacer(Modifier.height(28.dp))
        }
    }

    when (panel) {
        KokoroPanel.VOICE -> VoicePickerDialog(
            speaker = PrismSettings.VOICE_SPEAKER_SAM,
            onSelected = { voiceId ->
                say("Fetching the ${KokoroVoices.labelOf(voiceId)} voice…")
                ensureVoiceDownloaded(
                    voiceId,
                    onMissingModel = {
                        say("The model is not installed, so the voice was not fetched yet.")
                    },
                    onSilent = { say("") },
                    onDone = { revision++ },
                    onError = { say(it, ok = false) },
                )
            },
            onPreviewError = { say(it, ok = false) },
            onDismiss = { panel = null },
        )

        KokoroPanel.SPEED -> {
            val options = listOf(0.75f, 0.9f, 1.0f, 1.15f, 1.35f, 1.6f)
            RadioDialog(
                title = "Speed",
                options = options.map { "${it}×" },
                current = options.indexOfFirst { kotlin.math.abs(it - PrismSettings.getKokoroSpeed()) < 0.01f },
                onDismiss = { panel = null },
                onSelect = { which ->
                    PrismSettings.setKokoroSpeed(options[which])
                    panel = null
                },
            )
        }

        KokoroPanel.QUALITY -> RadioDialog(
            title = "Quality",
            options = KokoroInstall.VARIANTS.map { KokoroInstall.variantLabel(it) },
            current = KokoroInstall.VARIANTS.indexOf(PrismSettings.getKokoroVariant()),
            onDismiss = { panel = null },
            onSelect = { which ->
                PrismSettings.setKokoroVariant(KokoroInstall.VARIANTS[which])
                // The engine is keyed on the variant, so it has to be rebuilt to pick up the change.
                PrismSpeaker.invalidate()
                panel = null
                revision++
            },
        )

        KokoroPanel.REMOVE_MODEL -> ConfirmDialog(
            title = "Remove Kokoro?",
            message = "This frees ${KokoroInstall.installedBytes() shr 20} MB. Prism will fall " +
                "back to the system voice until it is downloaded again.",
            confirm = "Remove",
            dismiss = "Keep",
            onDismiss = { panel = null },
            onConfirm = {
                panel = null
                KokoroInstall.uninstall()
                PrismSpeaker.invalidate()
                revision++
            },
        )

        null -> {}
    }
}

/** What this page can open; local because the settings-page dialogs are a different family. */
private enum class KokoroPanel { VOICE, SPEED, QUALITY, REMOVE_MODEL }

/**
 * Fetches a voice in the background if it is not already on disk -- the phone's
 * KokoroVoicePicker.ensureDownloaded.
 *
 * Silent on success -- a voice arriving is not news -- which is what [onSilent] is for: the
 * caller clears the "Fetching…" note that promised work was underway. Failures speak up.
 */
internal fun ensureVoiceDownloaded(
    voiceId: String,
    onMissingModel: () -> Unit,
    onSilent: () -> Unit,
    onDone: () -> Unit,
    onError: (String) -> Unit,
) {
    if (KokoroInstall.isVoiceInstalled(voiceId) || ClonedVoices.isCloned(voiceId)) {
        onSilent()
        onDone()
        return
    }
    if (!KokoroInstall.isModelInstalled()) {
        onMissingModel()
        return
    }
    Thread({
        val error = KokoroInstall.downloadVoice(voiceId)
        if (error != null) onError(error) else onSilent()
        onDone()
    }, "kokoro-voice-$voiceId").apply { isDaemon = true; start() }
}

/** The one-line summary the Voice row shows for a speaker's current voice. */
internal fun kokoroVoiceSummary(id: String): String {
    ClonedVoices.find(id)?.let { entry ->
        // Speakable when any engine has what it needs: a reference recording or cached tone
        // for the new kind, the stored tensor for the old kind, and a base voice to say the
        // words through either way.
        val speakable = ClonedVoices.refFor(id) != null ||
            ClonedVoices.toneFor(id) != null ||
            KokoroInstall.isVoiceInstalled(id) ||
            (entry.baseVoiceId?.let { KokoroInstall.isVoiceInstalled(it) } == true)
        val installed = if (speakable) "" else " — missing file"
        return "${entry.displayName} · ${entry.languageDisplay} · cloned$installed"
    }
    val voice = KokoroVoices.find(id) ?: return id
    val installed = if (KokoroInstall.isVoiceInstalled(id)) "" else " — not downloaded yet"
    return "${voice.displayName} · ${voice.language.display} · ${voice.quality}$installed"
}

// ── The voice picker ─────────────────────────────────────────────────────────-----

private data class PickerRow(val id: String, val label: String)

/**
 * Chooses one of Kokoro's 54 voices -- the phone's KokoroVoicePicker, ported row for row.
 *
 * Each row carries the three things that actually distinguish a voice -- language, gender and
 * Kokoro's training grade -- and the list is flat, as on the phone, with the clones at the end
 * under a "YOUR VOICES" marker. Voices are half a megabyte each and fetched on selection, so a
 * row's trailing ✓ is what already arrived; the rest turn up as they are chosen.
 *
 * Preview never dismisses: the whole point is auditioning voices in place before committing.
 */
@Composable
private fun VoicePickerDialog(
    speaker: String,
    onSelected: (String) -> Unit,
    onPreviewError: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalPrismColors.current

    val installed = remember { KokoroInstall.installedVoices() }
    val current = remember { PrismSettings.getKokoroVoice(speaker) }

    val rows: List<PickerRow>
    val clonedFrom: Int
    run {
        val published = buildList {
            KokoroVoices.byLanguage().forEach { (language, voices) ->
                voices.forEach { voice ->
                    add(
                        PickerRow(
                            voice.id,
                            buildString {
                                append(voice.displayName)
                                append("  ·  ")
                                append(language.display)
                                append("  ·  ")
                                append(if (voice.gender == KokoroVoices.Gender.FEMALE) "female" else "male")
                                append("  ·  ")
                                append(voice.quality)
                                if (voice.id in installed) append("  ✓")
                            },
                        ),
                    )
                }
            }
        }
        val clones = ClonedVoices.all().map { entry ->
            PickerRow(
                entry.id,
                buildString {
                    append(entry.displayName)
                    append("  ·  ")
                    append(entry.languageDisplay)
                    append("  ·  ")
                    append(if (entry.gender == KokoroVoices.Gender.FEMALE) "female" else "male")
                    append("  ·  cloned")
                    if (entry.id in installed) append("  ✓")
                },
            )
        }
        rows = published + clones
        clonedFrom = if (clones.isEmpty()) -1 else published.size
    }
    var selectedIndex by remember(installed.size) {
        mutableStateOf(rows.indexOfFirst { it.id == current }.coerceAtLeast(0))
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = colors.surface,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.widthIn(max = 560.dp),
        ) {
            Column(Modifier.padding(18.dp)) {
                Text("Voice", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Column(
                    Modifier
                        .heightIn(max = 440.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    rows.forEachIndexed { index, row ->
                        if (index == clonedFrom) {
                            Text(
                                "YOUR VOICES",
                                fontSize = 10.sp,
                                letterSpacing = 1.2.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF7A7A88),
                                modifier = Modifier.padding(start = 6.dp, top = 10.dp, bottom = 2.dp),
                            )
                        }
                        val selected = index == selectedIndex
                        Text(
                            row.label,
                            fontSize = 14.sp,
                            lineHeight = 19.sp,
                            color = if (selected) colors.accent else colors.onSurface,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedIndex = index }
                                .padding(horizontal = 6.dp, vertical = 8.dp),
                        )
                    }
                }
                Hairline()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    KokoroPanelButton("Preview", bold = false) {
                        val voiceId = rows.getOrNull(selectedIndex)?.id ?: current
                        PrismSpeaker.preview(voiceId) { error ->
                            if (error != null) onPreviewError(error)
                        }
                    }
                    KokoroPanelDivider()
                    KokoroPanelButton("Cancel", bold = false) { onDismiss() }
                    KokoroPanelDivider()
                    KokoroPanelButton("Select", bold = true) {
                        val voiceId = rows[selectedIndex].id
                        onDismiss()
                        PrismSettings.setKokoroVoice(speaker, voiceId)
                        onSelected(voiceId)
                    }
                }
            }
        }
    }
}

@Composable
private fun KokoroPanelButton(label: String, bold: Boolean, onClick: () -> Unit) {
    Text(
        label,
        fontSize = 15.sp,
        fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
        color = LocalPrismColors.current.accent,
        modifier = Modifier
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 10.dp),
    )
}

@Composable
private fun KokoroPanelDivider() {
    Box(
        Modifier
            .width(1.dp)
            .height(30.dp)
            .background(Color(0xFF26262E)),
    )
}

// ── Small dialogs shared with the cloning page ─────────────────────────────────--

/** The phone's setSingleChoiceItems, as a card with rows and a Cancel. */
@Composable
internal fun RadioDialog(
    title: String,
    options: List<String>,
    current: Int,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit,
) {
    val colors = LocalPrismColors.current
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = colors.surface,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.widthIn(max = 480.dp),
        ) {
            Column(Modifier.padding(18.dp)) {
                Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                options.forEachIndexed { which, option ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(which) }
                            .padding(horizontal = 2.dp, vertical = 9.dp),
                    ) {
                        Text(option, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        if (which == current) {
                            Text("✓", color = colors.accent, fontSize = 14.sp)
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                KokoroPanelButton("Cancel", bold = false) { onDismiss() }
            }
        }
    }
}

/** The phone's AlertDialog with a message and two buttons: used for the model's removal. */
@Composable
internal fun ConfirmDialog(
    title: String,
    message: String,
    confirm: String,
    dismiss: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val colors = LocalPrismColors.current
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = colors.surface,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.widthIn(max = 480.dp),
        ) {
            Column(Modifier.padding(18.dp)) {
                Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(message, fontSize = 13.sp, lineHeight = 19.sp, color = colors.onSurface)
                Spacer(Modifier.height(12.dp))
                Row {
                    KokoroPanelButton(dismiss, bold = false) { onDismiss() }
                    KokoroPanelButton(confirm, bold = true) { onConfirm() }
                }
            }
        }
    }
}

/** Green-or-red strip standing where a phone would show a Toast. Shared by both pages. */
@Composable
internal fun NoteBar(text: String, ok: Boolean) {
    if (text.isBlank()) return
    Surface(
        color = if (ok) Color(0xFF14301F) else Color(0xFF331A1A),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    ) {
        Text(
            text,
            fontSize = 12.sp,
            lineHeight = 17.sp,
            color = if (ok) Color(0xFF9BE8B4) else Color(0xFFFFB4B4),
            modifier = Modifier.padding(10.dp),
        )
    }
}
