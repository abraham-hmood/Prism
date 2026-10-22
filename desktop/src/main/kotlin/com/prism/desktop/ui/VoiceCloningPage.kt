package com.prism.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.prism.core.PrismPlatform
import com.prism.desktop.speech.DesktopVoiceAudio
import com.prism.desktop.speech.DesktopVoiceRecorder
import com.prism.launcher.speech.ClonedVoices
import com.prism.launcher.speech.CloneEngine
import com.prism.launcher.speech.CloneEngines
import com.prism.launcher.speech.CustomCloneEngines
import com.prism.launcher.speech.KokoroMeshShare
import com.prism.launcher.speech.KokoroVoices
import com.prism.launcher.speech.VoiceCloner
import java.io.File
import java.util.Locale

/**
 * Record or upload a sample, clone it into a Kokoro voice, and name it -- the phone's
 * VoiceCloningActivity, ported.
 *
 * The differences from the page it mirrors are idioms, not features: press-and-hold becomes a
 * toggle because a mouse button is a poor hold-and-release target, and the toasts become the
 * note strip. Everything else is here -- the engine cards with their download/use/active states,
 * the analysis and save stages streamed into the status line, the save sheet with language
 * (built-in, your saved customs, or a freshly typed one) and gender, and the note naming the
 * published voice the result will speak through before conversion.
 */
@Composable
fun VoiceCloningPage(onNavigate: (PageId) -> Unit = {}) {
    val colors = LocalPrismColors.current

    var revision by remember { mutableStateOf(0) }
    var working by remember { mutableStateOf(false) }
    var recordingActive by remember { mutableStateOf(false) }
    var stage by remember {
        mutableStateOf("Press the microphone to record; press again to stop. Or upload an audio file.")
    }
    var noteOk by remember { mutableStateOf(true) }
    var note by remember { mutableStateOf("") }
    var pendingProfile by remember { mutableStateOf<VoiceCloner.SpeakerProfile?>(null) }
    var sheet by remember { mutableStateOf(SaveSheet.HIDDEN) }

    // The save form. Defaults are what the phone sets them to: the base American language for a
    // voice anyone can pick, and the gender the recording's pitch suggests.
    var language by remember {
        mutableStateOf<KokoroVoices.Language?>(KokoroVoices.Language.AMERICAN_ENGLISH)
    }
    var customLanguage by remember { mutableStateOf<String?>(null) }
    var gender by remember { mutableStateOf(KokoroVoices.Gender.FEMALE) }
    var saveStage by remember { mutableStateOf("") }

    /**
     * Which machine on the mesh should run the encoder, or null for this one.
     *
     * Deliberately not persisted: a desktop that has been switched off since yesterday must not
     * be silently selected the next time a voice is saved.
     */
    var meshClonePeer by remember { mutableStateOf<String?>(null) }
    var meshTick by remember { mutableStateOf(0) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(2_500)
            meshTick++
        }
    }
    val meshClonePeers = remember(meshTick) { KokoroMeshShare.withVoiceCloner() }
    // Resolve rather than mutate during composition: a selection whose machine has left the mesh
    // simply stops resolving, and Save falls back to this device.
    val activeMeshClonePeer = meshClonePeers.firstOrNull { it.peerIp == meshClonePeer }

    val recorder = remember { DesktopVoiceRecorder() }

    fun say(message: String, ok: Boolean = true) {
        noteOk = ok
        note = message
    }

    fun fail(message: String, error: Throwable? = null) {
        PrismPlatform.log.error("VoiceCloning", message, error)
        say("$message — see System Diagnostics.", ok = false)
        stage = "Press the microphone to record; press again to stop. Or upload an audio file."
    }

    fun openSaveSheet(profile: VoiceCloner.SpeakerProfile) {
        language = KokoroVoices.Language.AMERICAN_ENGLISH
        customLanguage = null
        gender = VoiceCloner.guessGender(profile)
        pendingProfile = profile
        sheet = SaveSheet.FORM
    }

    fun cloneSamples(samples: FloatArray, rate: Int) {
        working = true
        stage = "Analysing…"
        Thread({
            val analysed = runCatching {
                VoiceCloner.profile(samples, rate) { line -> stage = line }
            }
            working = false
            val profile = analysed.getOrNull()
            if (profile == null) {
                fail("Could not analyse the recording", analysed.exceptionOrNull())
            } else {
                openSaveSheet(profile)
            }
        }, "voice-clone-run").apply { isDaemon = true; start() }
    }

    fun onMicButton() {
        if (working) return
        if (recordingActive) {
            recordingActive = false
            val take = recorder.stop()
            if (take == null) {
                say("That was too short; record a few seconds of talking.", ok = false)
                stage = "Press the microphone to record; press again to stop. Or upload an audio file."
                return
            }
            cloneSamples(take.samples, take.rate)
        } else {
            val error = recorder.start()
            if (error != null) {
                fail(error)
            } else {
                recordingActive = true
                stage = "Recording… press again when you are done."
            }
        }
    }

    fun onUploadFile() {
        if (working || recorder.isRecording) return
        val dialog = java.awt.FileDialog(
            null as java.awt.Frame?, "Choose a recording", java.awt.FileDialog.LOAD,
        )
        dialog.isVisible = true
        val name = dialog.file ?: return
        val file = File(dialog.directory, name)
        if (!file.isFile) return
        working = true
        stage = "Reading that file…"
        Thread({
            val decoded = runCatching { DesktopVoiceAudio.decodeFile(file) }
            working = false
            val pair = decoded.getOrNull()
            if (pair == null) {
                fail("Could not read audio", decoded.exceptionOrNull())
            } else {
                cloneSamples(pair.samples, pair.rate)
            }
        }, "voice-clone-file").apply { isDaemon = true; start() }
    }

    fun commitSave(name: String) {
        val profile = pendingProfile ?: return
        // Captured now: the user pressed Save on the machine that was selected at that moment.
        val remote = activeMeshClonePeer
        working = true
        Thread({
            try {
                if (remote != null) {
                    // Analysis already happened here; only the encoder is the desktop's.
                    val artifacts = KokoroMeshShare.prepareCloneOn(remote.peerIp, profile.region)
                        ?: throw IllegalStateException(
                            "${remote.deviceName} did not answer. Save on this computer instead.",
                        )
                    ClonedVoices.importPrepared(
                        displayName = name,
                        language = language,
                        customLanguage = customLanguage,
                        gender = gender,
                        region = profile.region,
                        pitchHz = profile.pitchHz,
                        artifacts = artifacts,
                    ) { line -> saveStage = line }
                } else {
                    ClonedVoices.save(
                        displayName = name,
                        language = language,
                        customLanguage = customLanguage,
                        gender = gender,
                        region = profile.region,
                        sampleRate = VoiceCloner.WORK_RATE,
                        pitchHz = profile.pitchHz,
                    ) { line -> saveStage = line }
                }
                pendingProfile = null
                sheet = SaveSheet.HIDDEN
                working = false
                say("Saved as $name" + if (remote != null) " (prepared on ${remote.deviceName})" else "")
                onNavigate(PageId.KOKORO)
            } catch (error: Throwable) {
                working = false
                saveStage = ""
                fail("Could not save the voice", error)
            }
        }, "voice-clone-save").apply { isDaemon = true; start() }
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            PageScaffold("Clone a voice", subtitle = null) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        "‹ Kokoro settings",
                        fontSize = 14.sp,
                        color = colors.accent,
                        modifier = Modifier
                            .clickable { onNavigate(PageId.KOKORO) }
                            .padding(vertical = 2.dp),
                    )

                    SectionHeader("CLONE ENGINES")
                    SectionFooter(
                        "Kokoro speaks the words; the engine in use re-voices them toward your " +
                            "recording, with pitch matched to your voice. Each engine downloads " +
                            "once and every saved voice works through all of them.",
                    )
                    Spacer(Modifier.height(8.dp))
                    CloneEngines.all().forEach { engine ->
                        EngineCard(
                            engine = engine,
                            // [freshness] forces a fresh status read: downloads and switches
                            // change what the card says.
                            freshness = revision,
                            working = working,
                            onUse = { chosen ->
                                CloneEngines.setActive(chosen)
                                revision++
                                say("Using ${chosen.label}")
                            },
                            onDownload = { chosen ->
                                working = true
                                stage = "Downloading ${chosen.label}…"
                                Thread({
                                    val error = chosen.ensureInstalled { model, copied, total ->
                                        if (total > 0) {
                                            val pct = (copied * 100 / total).toInt()
                                            stage = "Fetching ${model.label}… $pct%"
                                        }
                                    }
                                    working = false
                                    if (error != null) {
                                        fail(error)
                                        stage =
                                            "Press the microphone to record; press again to " +
                                                "stop. Or upload an audio file."
                                    } else {
                                        CloneEngines.setActive(chosen)
                                        revision++
                                        say("${chosen.label} ready and in use")
                                        stage = "Record or upload a sample to clone."
                                    }
                                }, "voice-clone-dl").apply { isDaemon = true; start() }
                            },
                            onRemove = if (engine.isCustom) {
                                { chosen ->
                                    CustomCloneEngines.unregister(chosen.id)
                                    if (CloneEngines.active().id == chosen.id) {
                                        CloneEngines.setActive(CloneEngines.OPENVOICE)
                                    }
                                    working = false
                                    revision++
                                    say("Removed ${chosen.label}; its folder was left untouched")
                                }
                            } else {
                                null
                            },
                        )
                        Spacer(Modifier.height(8.dp))
                    }

                    // ── The mesh ─────────────────────────────────────────────
                    //
                    // Rendered only when a machine is actually offering an encoder. The whole
                    // block disappears on a box that has never joined a meshnet.
                    if (meshClonePeers.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        SectionHeader("CLONE ON YOUR MESHNET")
                        Card {
                            if (activeMeshClonePeer != null) {
                                NavRow("This device", "Run the encoder here instead") {
                                    meshClonePeer = null
                                    say("Cloning on this computer")
                                }
                                Hairline()
                            }
                            meshClonePeers.forEachIndexed { index, peer ->
                                if (index > 0) Hairline()
                                val chosen = peer.peerIp == activeMeshClonePeer?.peerIp
                                NavRow(
                                    if (chosen) "✓ ${peer.deviceName}" else peer.deviceName,
                                    if (chosen) "IN USE · this machine will prepare the clone"
                                    else "Tap to run the encoder on ${peer.deviceName}",
                                ) {
                                    meshClonePeer = peer.peerIp
                                    say("Cloning will run on ${peer.deviceName}")
                                }
                            }
                        }
                        SectionFooter(
                            "Recording and analysis still happen here; only the model work goes " +
                                "to the machine you pick, and the finished clone is stored on " +
                                "this device.",
                        )
                    }

                    Text(
                        "＋ Use a model on this computer…",
                        fontSize = 14.sp,
                        color = colors.accent,
                        modifier = Modifier
                            .alphaIf(!working)
                            .clickable(enabled = !working) {
                                pickModelFolder { folder ->
                                    if (folder == null) return@pickModelFolder
                                    working = true
                                    stage = "Reading ${folder.name}…"
                                    Thread({
                                        try {
                                            val engine = CustomCloneEngines.register(folder)
                                            working = false
                                            revision++
                                            say("Connected ${engine.label}")
                                            stage = "Record or upload a sample to clone."
                                        } catch (error: Throwable) {
                                            working = false
                                            PrismPlatform.log.error(
                                                "VoiceCloning",
                                                "Could not wire ${folder.absolutePath}",
                                                error,
                                            )
                                            say(error.message ?: "Could not wire that folder", ok = false)
                                            stage = error.message
                                                ?: "Record or upload a sample to clone."
                                        }
                                    }, "voice-clone-register").apply { isDaemon = true; start() }
                                }
                            }
                            .padding(vertical = 4.dp, horizontal = 4.dp),
                    )
                    Text(
                        "Kokoro can also run your own desktop-class voice-conversion model: a " +
                            "folder of ONNX files, either shaped like a converter or like a " +
                            "tone encoder in front of one. A prism.engine.json beside them " +
                            "spells the wiring out exactly.",
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        color = colors.faint,
                        modifier = Modifier.padding(start = 4.dp, end = 4.dp),
                    )

                    if (working) {
                        LinearProgressIndicator(
                            Modifier.fillMaxWidth().height(4.dp),
                            color = colors.accent,
                            trackColor = colors.surfaceRaised,
                        )
                    }
                    Spacer(Modifier.height(18.dp))

                    SectionHeader("VOICE SAMPLE")
                    Text(
                        stage,
                        fontSize = 14.sp,
                        lineHeight = 19.sp,
                        color = colors.onSurface,
                        modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 14.dp),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircleChip(
                            icon = Icons.Filled.Mic,
                            contentName = "Record",
                            filled = recordingActive,
                            disabled = working,
                            onClick = { onMicButton() },
                        )
                        Spacer(Modifier.width(14.dp))
                        CircleChip(
                            icon = Icons.Filled.Upload,
                            contentName = "Upload a recording",
                            filled = false,
                            disabled = working,
                            onClick = { onUploadFile() },
                        )
                    }
                    Spacer(Modifier.height(28.dp))
                }
            }
        }

        if (note.isNotBlank()) {
            Box(Modifier.align(Alignment.BottomStart).padding(28.dp).widthIn(max = 560.dp)) {
                NoteBar(note, noteOk)
            }
        }
    }

    when (sheet) {
        SaveSheet.FORM -> pendingProfile?.let { profile ->
            SaveClonedVoiceSheet(
                profile = profile,
                language = language,
                customLanguage = customLanguage,
                gender = gender,
                saveStageText = saveStage,
                busy = working,
                noNameYet = { say("Please enter a voice name", ok = false) },
                onCommit = { name -> commitSave(name) },
                onPickLanguage = { sheet = SaveSheet.LANGUAGE },
                onGender = { gender = it },
                onCancelled = {
                    pendingProfile = null
                    sheet = SaveSheet.HIDDEN
                    stage = "Press the microphone to record; press again to stop. Or upload an audio file."
                },
            )
        }

        SaveSheet.LANGUAGE -> LanguageSheet(
            language = language,
            customLanguage = customLanguage,
            onPick = { pickedLanguage, pickedCustom ->
                language = pickedLanguage
                customLanguage = pickedCustom
                sheet = SaveSheet.FORM
            },
            onAdd = { sheet = SaveSheet.ADD_LANGUAGE },
            onCancelled = { sheet = SaveSheet.FORM },
        )

        SaveSheet.ADD_LANGUAGE -> AddLanguageSheet(
            onAdded = { added ->
                language = null
                customLanguage = added
                sheet = SaveSheet.FORM
            },
            onCancelled = { sheet = SaveSheet.FORM },
        )

        SaveSheet.HIDDEN -> {}
    }
}

/** Which layer of the save flow the sheet is on; the phone stacks dialogs, this switches views. */
private enum class SaveSheet { HIDDEN, FORM, LANGUAGE, ADD_LANGUAGE }

// ── Engine cards ─────────────────────────────────────────────────────────────────-

/**
 * One card per engine: name + status + a Download or Use action (or an "In use" badge). A
 * custom engine, which wraps a folder of the user's own model files, also carries a Remove
 * action -- removal forgets the folder without touching anything in it.
 */
@Composable
private fun EngineCard(
    engine: CloneEngine,
    freshness: Int,
    working: Boolean,
    onUse: (CloneEngine) -> Unit,
    onDownload: (CloneEngine) -> Unit,
    onRemove: ((CloneEngine) -> Unit)? = null,
) {
    val colors = LocalPrismColors.current
    val active = engine.id == CloneEngines.active().id
    val installed = engine.isInstalled()
    Card {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(engine.label, fontSize = 15.sp, modifier = Modifier.weight(1f))
                if (active) {
                    Text("IN USE", fontSize = 10.sp, letterSpacing = 1.sp, color = colors.accent)
                }
            }
            Text(
                engine.statusLine(),
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = Color(0xFF83838F),
                modifier = Modifier.padding(top = 4.dp),
            )
            engine.attribution?.let {
                Text(
                    it,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = colors.faint,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (!active) {
                Text(
                    if (installed) "Use" else "Download",
                    fontSize = 14.sp,
                    color = colors.accent,
                    modifier = Modifier
                        .alphaIf(!working)
                        .clickable(enabled = !working) {
                            if (installed) onUse(engine) else onDownload(engine)
                        }
                        .padding(top = 6.dp),
                )
            }
            if (onRemove != null) {
                Text(
                    "Remove",
                    fontSize = 14.sp,
                    color = colors.accent,
                    modifier = Modifier
                        .alphaIf(!working && !active)
                        .clickable(enabled = !working && !active) { onRemove(engine) }
                        .padding(top = 6.dp),
                )
            }
        }
    }
}

/**
 * The one thing the desktop studio gains over the phone's: an open folder, chosen in the
 * system's own picker. Java's [javax.swing.JFileChooser] is used rather than the AWT
 * FileDialog because folder *selection* -- as opposed to file selection -- is only wired into
 * the native dialogs with a mac-only system property, and this has to work on Windows first.
 */
private fun pickModelFolder(onPick: (File?) -> Unit) {
    Thread({
        val chooser = javax.swing.JFileChooser().apply {
            fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY
            isAcceptAllFileFilterUsed = false
            dialogTitle = "Choose the folder holding your voice model"
        }
        val result = java.util.concurrent.atomic.AtomicReference<File?>()
        val latch = java.util.concurrent.CountDownLatch(1)
        javax.swing.SwingUtilities.invokeLater {
            if (chooser.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) {
                result.set(chooser.selectedFile)
            }
            latch.countDown()
        }
        latch.await()
        onPick(result.get())
    }, "voice-clone-folder").apply { isDaemon = true; start() }
}

/** The round record / upload button the phone shows at the bottom of the studio. */
@Composable
private fun CircleChip(
    icon: ImageVector,
    contentName: String,
    filled: Boolean,
    disabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalPrismColors.current
    Surface(
        onClick = onClick,
        enabled = !disabled,
        shape = CircleShape,
        color = if (filled) colors.accent else colors.surfaceRaised,
        modifier = Modifier
            .size(56.dp)
            .alphaIf(!disabled),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                icon,
                contentDescription = contentName,
                tint = if (filled) Color.White else colors.accent,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

// ── The save flow ─────────────────────────────────────────────────────────-------

/**
 * The phone's "Save Cloned Voice" card: the take's length and the voice it will speak through,
 * a name, a language (any of them, even ones Kokoro does not publish), and the gender the base
 * voice follows. Save runs the real work off-thread and shows its stages in the Save button.
 */
@Composable
private fun SaveClonedVoiceSheet(
    profile: VoiceCloner.SpeakerProfile,
    language: KokoroVoices.Language?,
    customLanguage: String?,
    gender: KokoroVoices.Gender,
    saveStageText: String,
    busy: Boolean,
    noNameYet: () -> Unit,
    onCommit: (String) -> Unit,
    onPickLanguage: () -> Unit,
    onGender: (KokoroVoices.Gender) -> Unit,
    onCancelled: () -> Unit,
) {
    val colors = LocalPrismColors.current
    var name by remember(profile) { mutableStateOf("") }

    // Which published voice Kokoro speaks through before conversion, so a language with no
    // Kokoro voice is not a surprise: the words still come out, in an American base.
    val base = ClonedVoices.baseVoiceFor(
        if (customLanguage == null) language else null,
        gender,
    )
    val chosen = customLanguage ?: language?.display ?: ""
    val baseNote = when {
        base == null -> "No published voice is available"
        language != null && customLanguage == null && base.language == language ->
            "Kokoro speaks through ${base.displayName} · ${base.language.display}"
        else ->
            "No published $chosen voice — Kokoro speaks through " +
                "${base.displayName} · ${base.language.display}"
    }

    Dialog(onDismissRequest = {}) {
        Surface(
            color = colors.surface,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.widthIn(max = 520.dp),
        ) {
            Column(Modifier.padding(20.dp)) {
                Text(
                    "Save Cloned Voice",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    String.format(
                        Locale.US,
                        "%.1f s recorded · Kokoro speaks, your voice carries it",
                        profile.seconds,
                    ),
                    fontSize = 13.sp,
                    color = Color(0xFF83838F),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    baseNote,
                    fontSize = 12.sp,
                    color = colors.faint,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(14.dp))

                Text(
                    "VOICE NAME",
                    fontSize = 10.sp,
                    letterSpacing = 1.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF7A7A88),
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = { Text("e.g. My Voice", fontSize = 14.sp) },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))

                Text(
                    "LANGUAGE",
                    fontSize = 10.sp,
                    letterSpacing = 1.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF7A7A88),
                )
                Surface(
                    color = colors.surfaceRaised,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !busy) { onPickLanguage() },
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
                    ) {
                        Text(
                            customLanguage ?: language?.display ?: "",
                            fontSize = 14.sp,
                            modifier = Modifier.weight(1f),
                        )
                        Text("▾", fontSize = 13.sp, color = Color(0xFF83838F))
                    }
                }
                Spacer(Modifier.height(12.dp))

                Text(
                    "GENDER",
                    fontSize = 10.sp,
                    letterSpacing = 1.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF7A7A88),
                )
                Surface(color = colors.surfaceRaised, shape = RoundedCornerShape(8.dp)) {
                    Row(Modifier.padding(3.dp)) {
                        listOf(
                            KokoroVoices.Gender.FEMALE to "Female",
                            KokoroVoices.Gender.MALE to "Male",
                        ).forEach { (option, label) ->
                            val selected = option == gender
                            Box(
                                Modifier
                                    .weight(1f)
                                    .clickable(enabled = !busy) { onGender(option) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    label,
                                    fontSize = 14.sp,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (selected) colors.onSurface else Color(0xFF83838F),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 8.dp)
                                        .then(
                                            SubstituteCellStyle(selected, colors),
                                        ),
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                Hairline()
                Row {
                    SheetButton("Cancel", bold = false) { if (!busy) onCancelled() }
                    SheetDivider()
                    SheetButton(
                        label = if (busy) saveStageText.ifEmpty { "Saving…" } else "Save",
                        bold = true,
                        onClick = {
                            val trimmed = name.trim()
                            if (trimmed.isEmpty()) noNameYet() else onCommit(trimmed)
                        },
                    )
                }
            }
        }
    }
}

/** The phone's selected-cell fill, expressed as a small inner surface the text draws over. */
@Composable
private fun SubstituteCellStyle(selected: Boolean, colors: PrismColors): Modifier =
    if (selected) {
        Modifier.background(colors.surface, RoundedCornerShape(6.dp))
    } else {
        Modifier
    }

@Composable
private fun RowScope.SheetButton(label: String, bold: Boolean, onClick: () -> Unit) {
    Box(Modifier.weight(1f).clickable { onClick() }) {
        Text(
            label,
            fontSize = 15.sp,
            fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
            color = LocalPrismColors.current.accent,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun SheetDivider() {
    Box(
        Modifier
            .width(1.dp)
            .height(44.dp)
            .background(Color(0xFF26262E)),
    )
}

/**
 * The phone's language picker for a clone's language: built-ins first, then whatever the user
 * has typed in before, then a row that opens the add-one sheet.
 */
@Composable
private fun LanguageSheet(
    language: KokoroVoices.Language?,
    customLanguage: String?,
    onPick: (KokoroVoices.Language?, String?) -> Unit,
    onAdd: () -> Unit,
    onCancelled: () -> Unit,
) {
    val colors = LocalPrismColors.current
    val builtins = remember { KokoroVoices.Language.entries.toList() }
    val customs = remember { ClonedVoices.customLanguages() }
    val selected: Int = when {
        customLanguage != null ->
            builtins.size + customs.indexOfFirst { it.equals(customLanguage, ignoreCase = true) }
        else -> builtins.indexOf(language ?: builtins.first())
    }

    Dialog(onDismissRequest = onCancelled) {
        Surface(
            color = colors.surface,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.widthIn(max = 480.dp),
        ) {
            Column(Modifier.padding(18.dp).heightIn(max = 460.dp)) {
                Text("Language", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    builtins.forEachIndexed { which, entry ->
                        OptionRow(key = entry.display, current = which == selected) {
                            onPick(entry, null)
                        }
                    }
                    customs.forEachIndexed { index, custom ->
                        if (builtins.size + index == selected) {
                            CustomRow(custom, true) { onPick(null, custom) }
                        } else {
                            CustomRow(custom, false) { onPick(null, custom) }
                        }
                    }
                    OptionRow("＋ Add custom language…", false) { onAdd() }
                }
                Spacer(Modifier.height(4.dp))
                Row {
                    SheetButton("Cancel", bold = false) { onCancelled() }
                    SheetDivider()
                    SheetButton("Done", bold = true) { onCancelled() }
                }
            }
        }
    }
}

@Composable
private fun OptionRow(key: String, current: Boolean, onPick: () -> Unit) {
    val colors = LocalPrismColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onPick() }
            .padding(horizontal = 4.dp, vertical = 9.dp),
    ) {
        Text(key, fontSize = 14.sp, modifier = Modifier.weight(1f))
        if (current) {
            Text("✓", color = colors.accent, fontSize = 14.sp)
        }
    }
}

@Composable
private fun CustomRow(label: String, current: Boolean, onPick: () -> Unit) = OptionRow(label, current, onPick)

/**
 * The popup behind "＋ Add custom language…": one text field, Cancel and Add. The typed name is
 * canonicalised by [ClonedVoices.addCustomLanguage], so "korean" becomes "Korean" the way one
 * expects a saved language list to read.
 */
@Composable
private fun AddLanguageSheet(
    onAdded: (String) -> Unit,
    onCancelled: () -> Unit,
) {
    val colors = LocalPrismColors.current
    var typed by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onCancelled) {
        Surface(
            color = colors.surface,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.widthIn(max = 480.dp),
        ) {
            Column(Modifier.padding(20.dp)) {
                Text("Add a language", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    placeholder = { Text("e.g. Klingon", fontSize = 14.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Hairline()
                Row {
                    SheetButton("Cancel", bold = false) { onCancelled() }
                    SheetDivider()
                    SheetButton("Add", bold = true) {
                        val trimmed = typed.trim()
                        if (trimmed.isEmpty()) return@SheetButton
                        val added = ClonedVoices.addCustomLanguage(trimmed)
                        if (added != null) onAdded(added)
                    }
                }
            }
        }
    }
}
