package com.prism.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prism.core.PrismPlatform
import com.prism.desktop.DesktopCallAudio
import com.prism.launcher.calls.MeshCalls
import com.prism.launcher.calls.MeshGreeting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine

/**
 * Setting this machine's meshvoice greeting -- the desktop half of the phone's greeting layout.
 *
 * The same two ways in as the phone: the floating record button records it from the microphone
 * (stopping itself at [MeshGreeting.MAX_SECONDS]), and when a speech model is set up a voice list, a
 * text box and a send button at the bottom have the model speak it, cloned voices included. The
 * greeting itself is [MeshGreeting]'s, shared with the phone, so trusted devices receive it and
 * play it to callers the same way whichever device recorded it. Every colour is the theme's.
 */
@Composable
fun MeshvoiceGreeting(onBack: () -> Unit) {
    val colors = LocalPrismColors.current
    val scope = rememberCoroutineScope()
    var revision by remember { mutableStateOf(0) }
    val info = remember(revision) { runCatching { MeshGreeting.info() }.getOrNull() }
    var note by remember { mutableStateOf("") }
    var playing by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(false) }
    var seconds by remember { mutableStateOf(0) }
    var speaking by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    val canSpeak = remember { MeshGreeting.canSpeak() }
    val voices = remember { if (canSpeak) MeshGreeting.voices() else emptyList() }
    var voice by remember {
        mutableStateOf(voices.firstOrNull { it.id == MeshGreeting.defaultVoice() } ?: voices.firstOrNull())
    }
    var voicesOpen by remember { mutableStateOf(false) }

    // The microphone and the player belong to this screen; leaving it releases both.
    DisposableEffect(Unit) {
        onDispose {
            DesktopGreetingRecorder.stop()
            DesktopCallAudio.stopWav()
        }
    }
    LaunchedEffect(recording) {
        while (recording) {
            delay(250)
            seconds = DesktopGreetingRecorder.seconds()
            if (!DesktopGreetingRecorder.running) {
                // It stopped by itself at the limit; keep what it heard.
                recording = false
                val pcm = DesktopGreetingRecorder.take()
                note = saveRecording(pcm)
                revision++
            }
        }
    }

    PageScaffold("Voicemail greeting", "What callers hear before they leave you a meshnet voicemail") {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to Meshvoice", tint = colors.accent)
                    }
                    Text("Meshvoice", color = colors.accent, fontSize = 13.sp, modifier = Modifier.clickable { onBack() })
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 90.dp)) {
                    Card {
                        Column(Modifier.padding(16.dp)) {
                            if (info == null) {
                                Text("No greeting yet", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = colors.onSurface)
                                Text(
                                    "Callers hear a beep and then leave their message.",
                                    fontSize = 12.sp, color = colors.muted,
                                )
                            } else {
                                val total = (info.durationMs / 1000).toInt()
                                Text(
                                    "Your greeting · %d:%02d".format(total / 60, total % 60),
                                    fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = colors.onSurface,
                                )
                                Text(
                                    info.detail.ifBlank { if (info.source == "spoken") "Spoken by your speech model" else "Recorded" },
                                    fontSize = 12.sp, color = colors.muted,
                                )
                                Spacer(Modifier.height(10.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = {
                                        if (playing) {
                                            DesktopCallAudio.stopWav()
                                            playing = false
                                        } else {
                                            val wav = MeshGreeting.current()
                                            playing = wav != null && DesktopCallAudio.playWav(wav)
                                        }
                                    }) { Text(if (playing) "Stop" else "Play") }
                                    OutlinedButton(onClick = {
                                        DesktopCallAudio.stopWav()
                                        playing = false
                                        MeshGreeting.clear()
                                        note = "Greeting removed. Your trusted devices are told too."
                                        revision++
                                    }) { Text("Remove", color = colors.destructive) }
                                }
                            }
                        }
                    }
                    if (recording) {
                        Text(
                            "● Recording  %d:%02d  ·  stops at 0:%02d".format(seconds / 60, seconds % 60, MeshGreeting.MAX_SECONDS),
                            color = colors.destructive,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(top = 16.dp),
                        )
                    }
                    Text(
                        "Your trusted devices keep a copy of this greeting and play it to anyone leaving you a " +
                            "meshnet voicemail while you are away. Use the record button" +
                            (if (canSpeak) ", or type it below and have your speech model say it." else "."),
                        fontSize = 12.sp,
                        color = colors.muted,
                        modifier = Modifier.padding(top = 14.dp, start = 4.dp, end = 4.dp),
                    )
                    if (note.isNotBlank()) {
                        Text(note, fontSize = 12.sp, color = colors.accent, modifier = Modifier.padding(top = 10.dp, start = 4.dp))
                    }
                }

                if (canSpeak) {
                    Column(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(colors.cardRadius)).background(colors.card).padding(12.dp),
                    ) {
                        Box {
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(colors.fieldRadius))
                                    .background(colors.surfaceRaised).clickable { voicesOpen = true }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("Voice: ${voice?.label ?: "default"}", color = colors.onSurface, fontSize = 13.sp, modifier = Modifier.weight(1f))
                                Icon(Icons.Filled.ArrowDropDown, null, tint = colors.muted)
                            }
                            DropdownMenu(
                                expanded = voicesOpen,
                                onDismissRequest = { voicesOpen = false },
                                modifier = Modifier.background(colors.card),
                            ) {
                                voices.forEach { option ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(option.label, color = if (option.cloned) colors.accent else colors.onSurface, fontSize = 13.sp)
                                        },
                                        onClick = {
                                            voice = option
                                            voicesOpen = false
                                        },
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = text,
                                onValueChange = { text = it.take(400) },
                                placeholder = { Text("Type your greeting", color = colors.faint) },
                                modifier = Modifier.weight(1f),
                                maxLines = 4,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = colors.onSurface,
                                    unfocusedTextColor = colors.onSurface,
                                    focusedBorderColor = colors.accent,
                                    unfocusedBorderColor = colors.divider,
                                    cursorColor = colors.accent,
                                ),
                            )
                            Spacer(Modifier.width(8.dp))
                            Box(
                                Modifier.size(44.dp).clip(CircleShape)
                                    .background(if (speaking) colors.faint else colors.accent)
                                    .clickable(enabled = !speaking) {
                                        val words = text.trim()
                                        if (words.isEmpty()) {
                                            note = "Type what the greeting should say."
                                            return@clickable
                                        }
                                        speaking = true
                                        note = "Speaking your greeting…"
                                        val chosen = voice
                                        scope.launch {
                                            val result = withContext(Dispatchers.IO) {
                                                MeshGreeting.synthesize(words, chosen?.id.orEmpty())
                                            }
                                            speaking = false
                                            result.fold(
                                                onSuccess = { wav ->
                                                    MeshGreeting.set(wav, "spoken", "Spoken in ${chosen?.label ?: "the default voice"}: “$words”")
                                                    text = ""
                                                    note = "Greeting saved."
                                                    revision++
                                                    playing = DesktopCallAudio.playWav(wav)
                                                },
                                                onFailure = { note = it.message ?: "The greeting could not be spoken." },
                                            )
                                        }
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Send, "Speak this greeting", tint = Color.White, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
            }

            // The floating record button, over the content and clear of the typing bar.
            Box(
                Modifier.align(Alignment.BottomEnd)
                    .padding(end = 8.dp, bottom = if (canSpeak) 150.dp else 12.dp)
                    .size(64.dp).clip(CircleShape)
                    .background(if (recording) colors.destructive else colors.accent)
                    .clickable {
                        if (recording) {
                            recording = false
                            DesktopGreetingRecorder.stop()
                            note = saveRecording(DesktopGreetingRecorder.take())
                            revision++
                        } else {
                            DesktopCallAudio.stopWav()
                            playing = false
                            val problem = DesktopGreetingRecorder.start()
                            if (problem == null) {
                                recording = true
                                seconds = 0
                                note = ""
                            } else {
                                note = problem
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (recording) Icons.Filled.Stop else Icons.Filled.Mic,
                    if (recording) "Stop recording" else "Record your greeting",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
    }
}

/** Stores a finished recording as the greeting, or says why not. */
private fun saveRecording(pcm: ByteArray): String {
    if (pcm.size < MeshCalls.SAMPLE_RATE) return "That was too short to be a greeting."
    return if (MeshGreeting.set(MeshGreeting.fromPcm(pcm), "recorded", "Recorded on this computer")) {
        "Greeting saved."
    } else {
        "The greeting could not be saved."
    }
}

/**
 * The microphone for a greeting: 16 kHz mono, the call engine's format, so the file plays on a
 * phone's call path without conversion. Stops itself at [MeshGreeting.MAX_SECONDS].
 */
private object DesktopGreetingRecorder {

    private val format = AudioFormat(
        AudioFormat.Encoding.PCM_SIGNED, MeshCalls.SAMPLE_RATE.toFloat(), 16, 1, 2, MeshCalls.SAMPLE_RATE.toFloat(), false,
    )
    private val buffer = ByteArrayOutputStream()
    @Volatile private var line: TargetDataLine? = null
    @Volatile var running = false
        private set
    @Volatile private var startedAt = 0L

    /** Starts recording; returns why it could not, or null. */
    fun start(): String? {
        stop()
        val info = DataLine.Info(TargetDataLine::class.java, format)
        if (!runCatching { AudioSystem.isLineSupported(info) }.getOrDefault(false)) {
            return "No microphone on this computer accepts 16 kHz recording."
        }
        val opened = runCatching {
            (AudioSystem.getLine(info) as TargetDataLine).apply { open(format, MeshCalls.FRAME_BYTES * 8); start() }
        }.getOrElse { return "The microphone could not be opened: ${it.message}" }
        synchronized(buffer) { buffer.reset() }
        line = opened
        running = true
        startedAt = System.currentTimeMillis()
        Thread({
            val frame = ByteArray(MeshCalls.FRAME_BYTES)
            val limit = MeshGreeting.MAX_SECONDS * MeshCalls.SAMPLE_RATE * 2
            while (running) {
                val read = runCatching { opened.read(frame, 0, frame.size) }.getOrDefault(-1)
                if (read <= 0) continue
                val full = synchronized(buffer) {
                    buffer.write(frame, 0, read)
                    buffer.size() >= limit
                }
                if (full) break
            }
            running = false
            runCatching { opened.stop() }
            runCatching { opened.close() }
        }, "prism-desktop-greeting").apply { isDaemon = true; start() }
        PrismPlatform.log.info("PrismGreeting", "Recording a greeting")
        return null
    }

    fun stop() {
        running = false
        runCatching { line?.stop() }
        runCatching { line?.close() }
        line = null
    }

    fun seconds(): Int = ((System.currentTimeMillis() - startedAt) / 1000).toInt()

    /** What was recorded, emptied afterwards. */
    fun take(): ByteArray = synchronized(buffer) { buffer.toByteArray().also { buffer.reset() } }
}
