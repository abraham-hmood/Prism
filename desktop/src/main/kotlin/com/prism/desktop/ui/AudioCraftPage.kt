package com.prism.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prism.launcher.audiocraft.AudioClip
import com.prism.launcher.audiocraft.AudioCraft
import com.prism.launcher.audiocraft.AudioCraftStore
import com.prism.launcher.audiocraft.PdfText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.swing.JFileChooser

/**
 * AudioCraft: the library, then the player that a new clip is made in.
 *
 * THE LIBRARY IS THE PAGE. What this feature exists to hand back is a folder of audio the user keeps,
 * so the list of what has been made is the resting state and everything else is a step off it. The
 * creator is the same layout as the player, because it is the same screen: a clip being dictated and
 * a clip being listened to are both "one piece of audio, in focus".
 *
 * PLAYBACK HANDS THE FILE TO THE SYSTEM, like VideoCraft's films do. A desktop build has no wired
 * decoder here, and a transport row of buttons that did nothing would be a worse lie than a button
 * that opens the file in whatever the user already listens with.
 */
@Composable
fun AudioCraftPage() {
    val colors = LocalPrismColors.current
    var clips by remember { mutableStateOf(AudioCraft.clips()) }
    var creating by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf<AudioClip?>(null) }
    val state by AudioCraft.state.collectAsState()
    val gate = remember { AudioCraft.gate() }

    // A finished read-aloud has just appeared on disk, and nothing else tells the list that. Landing
    // back on the library with the new clip already in it is the point of the creator finishing.
    LaunchedEffect(state.complete, state.clipId) {
        if (state.complete) {
            clips = AudioCraft.clips()
            creating = false
            AudioCraft.clear()
        }
    }

    Box(Modifier.fillMaxSize()) {
        PageScaffold(
            title = "AudioCraft",
            subtitle = when {
                creating -> "Read a script aloud, or import the text of one."
                open != null -> "One clip, as it was made."
                clips.isEmpty() -> "Nothing here yet. The plus makes the first one."
                else -> "${clips.size} ${if (clips.size == 1) "clip" else "clips"}."
            },
            actions = {
                if (creating || open != null) {
                    IconButton(onClick = { creating = false; open = null }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to the library")
                    }
                }
            },
        ) {
            val playing = open
            when {
                creating -> CreatorPanel(
                    gateReason = if (gate.ready) "" else gate.reason,
                    onCancel = { creating = false },
                )
                playing != null -> PlayerPanel(
                    clip = playing,
                    onDelete = {
                        AudioCraft.delete(playing)
                        clips = AudioCraft.clips()
                        open = null
                    },
                )
                else -> LibraryList(clips = clips, onOpen = { open = it })
            }
        }

        if (!creating && open == null) {
            // The same corner Android's fab sits in: the one action a library of this shape has.
            Surface(
                color = colors.accent,
                shape = RoundedCornerShape(if (colors.pill) 999.dp else colors.buttonRadius),
                shadowElevation = (if (colors.elevation > 0) colors.elevation else 6).dp,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(24.dp)
                    .size(52.dp)
                    .clickableRow { creating = true; AudioCraft.clear() },
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Add, "Create audio", tint = colors.background)
                }
            }
        }
    }
}

/** The generated clips, newest first, as the store already sorts them. */
@Composable
private fun LibraryList(clips: List<AudioClip>, onOpen: (AudioClip) -> Unit) {
    val colors = LocalPrismColors.current
    if (clips.isEmpty()) return
    LazyColumn(Modifier.fillMaxSize()) {
        items(clips, key = { it.id }) { clip ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickableRow { onOpen(clip) }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CoverArt(clip.title, size = 54.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        clip.title,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        listOfNotNull(
                            clip.voiceLabel.ifBlank { null } ?: "Voice not recorded",
                            formatDuration(clip.durationSeconds),
                            when {
                                clip.source == "typed" -> "typed"
                                clip.source.isBlank() -> "imported"
                                else -> "from ${clip.source}"
                            },
                        ).joinToString(" · "),
                        fontSize = 12.sp,
                        color = colors.muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(clip.createdAt)),
                    fontSize = 11.sp,
                    color = colors.faint,
                )
            }
            Hairline()
        }
    }
}

/** One clip, in focus: what it says, who said it, how long it runs, and what to do with it. */
@Composable
private fun PlayerPanel(clip: AudioClip, onDelete: () -> Unit) {
    val colors = LocalPrismColors.current
    val file = AudioCraftStore.clipFile(clip.id)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(18.dp))
        CoverArt(clip.title, size = 220.dp)
        Spacer(Modifier.height(20.dp))
        Text(
            clip.title,
            fontSize = 21.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            clip.voiceLabel.ifBlank { "Voice not recorded" },
            fontSize = 13.sp,
            color = colors.muted,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "${formatDuration(clip.durationSeconds)} · made ${SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(clip.createdAt))}",
            fontSize = 12.sp,
            color = colors.faint,
        )
        Spacer(Modifier.height(26.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            ActionButton(Icons.Filled.PlayArrow, "Play") {
                if (file.isFile && java.awt.Desktop.isDesktopSupported()) {
                    runCatching { java.awt.Desktop.getDesktop().open(file) }
                }
            }
            Spacer(Modifier.width(10.dp))
            ActionButton(Icons.Filled.Folder, "Show file") {
                if (java.awt.Desktop.isDesktopSupported()) {
                    runCatching { java.awt.Desktop.getDesktop().open(file.parentFile) }
                }
            }
            Spacer(Modifier.width(10.dp))
            ActionButton(Icons.Filled.Delete, "Delete", destructive = true, onClick = onDelete)
        }
        Spacer(Modifier.height(24.dp))
        Surface(
            color = colors.surfaceRaised,
            shape = RoundedCornerShape(colors.cardRadius),
            border = if (colors.border > 0) androidx.compose.foundation.BorderStroke(colors.border.dp, colors.divider) else null,
            modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text("THE SCRIPT", fontSize = 10.sp, letterSpacing = 1.1.sp, color = colors.faint)
                Spacer(Modifier.height(8.dp))
                Text(clip.text, fontSize = 13.sp, color = colors.onSurface)
            }
        }
    }
}

/** The creator, in the player's clothes: a cover where the clip will go, and the text as the input. */
@Composable
private fun CreatorPanel(gateReason: String, onCancel: () -> Unit) {
    val colors = LocalPrismColors.current
    val scope = rememberCoroutineScope()
    val voices = remember { AudioCraft.voiceCatalogue() }
    val state by AudioCraft.state.collectAsState()

    var text by remember { mutableStateOf("") }
    var files by remember { mutableStateOf<List<File>>(emptyList()) }
    var voice by remember { mutableStateOf(voices.firstOrNull()?.id.orEmpty()) }
    var note by remember { mutableStateOf("") }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(14.dp))
        CoverArt(state.title.ifBlank { "New recording" }, size = 150.dp)
        Spacer(Modifier.height(16.dp))

        // The field is the input unless files were imported, in which case they are: two sources at
        // once would leave the button guessing which one it is reading.
        CommittingField(
            value = text,
            enabled = files.isEmpty() && !state.running,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 60.dp),
            onCommit = { text = it },
        )

        if (files.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(
                "${files.size} ${if (files.size == 1) "file" else "files"} imported: " +
                    files.joinToString(", ") { it.name },
                fontSize = 12.sp,
                color = colors.muted,
            )
        }

        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            ActionButton(Icons.Filled.UploadFile, "Import text or PDF") {
                val picked = pickDocuments()
                if (picked.isNotEmpty()) {
                    scope.launch {
                        note = "Reading ${picked.size} ${if (picked.size == 1) "file" else "files"}…"
                        val read = withContext(Dispatchers.IO) { readDocuments(picked) }
                        val (script, status) = read
                        note = status
                        if (script.isBlank()) {
                            files = emptyList()
                        } else {
                            files = picked
                            text = script
                        }
                    }
                }
            }
            if (files.isNotEmpty()) {
                Spacer(Modifier.width(10.dp))
                ActionButton(Icons.Filled.Delete, "Clear import") {
                    files = emptyList()
                    text = ""
                    note = ""
                }
            }
        }

        if (voices.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            PrismDropdown(
                label = "Voice",
                selection = voices.firstOrNull { it.id == voice }?.label ?: voices.first().label,
                options = voices.map { it.label },
                enabled = !state.running,
                modifier = Modifier.padding(horizontal = 60.dp),
            ) { label ->
                voices.firstOrNull { it.label == label }?.let { voice = it.id }
            }
        }

        Spacer(Modifier.height(18.dp))
        ActionButton(
            Icons.Filled.GraphicEq,
            if (state.running) "Cancel" else "Read aloud",
            enabled = (state.running || text.isNotBlank()) && (state.running || gateReason.isEmpty()),
        ) {
            if (state.running) {
                AudioCraft.cancel()
            } else {
                note = ""
                val source = if (files.isEmpty()) "typed" else files.joinToString(", ") { it.name }
                AudioCraft.start(text, source, voice)
            }
        }

        if (state.running || state.complete) {
            Spacer(Modifier.height(16.dp))
            Text(
                if (state.chunks > 1) "${state.stage} (${state.chunk} of ${state.chunks})" else state.stage,
                fontSize = 12.sp,
                color = colors.muted,
            )
            Spacer(Modifier.height(8.dp))
            PrismProgressBar(state.progress, Modifier.padding(horizontal = 60.dp))
        }
        state.error?.let { reason ->
            Spacer(Modifier.height(12.dp))
            Text(reason, fontSize = 12.sp, color = colors.destructive)
        }
        if (gateReason.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(gateReason, fontSize = 12.sp, color = colors.muted)
        }
        if (note.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(note, fontSize = 12.sp, color = colors.muted)
        }
        Spacer(Modifier.height(22.dp))
    }
}

/** A theme-coloured control, since the desktop pages carry no material buttons of their own. */
@Composable
private fun ActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean = true,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = LocalPrismColors.current
    Surface(
        color = when {
            !enabled -> colors.surface
            destructive -> colors.surfaceRaised
            else -> colors.surfaceRaised
        },
        shape = RoundedCornerShape(if (colors.pill) 999.dp else colors.buttonRadius),
        border = if (colors.border > 0) androidx.compose.foundation.BorderStroke(colors.border.dp, colors.divider) else null,
        modifier = Modifier.alphaIf(enabled).clickableRow { if (enabled) onClick() },
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                icon, null,
                tint = when {
                    !enabled -> colors.faint
                    destructive -> colors.destructive
                    else -> colors.accent
                },
                modifier = Modifier.size(17.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(label, fontSize = 13.sp, color = if (enabled) colors.onSurface else colors.faint)
        }
    }
}

/** A square cover, the way an audiobook is drawn: a ground with the title's first letter on it. */
@Composable
private fun CoverArt(title: String, size: androidx.compose.ui.unit.Dp) {
    val colors = LocalPrismColors.current
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(if (colors.pill) size else colors.cardRadius))
            .background(
                Brush.linearGradient(
                    listOf(colors.accent.copy(alpha = 0.55f), colors.surfaceRaised),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        val letter = title.trim().firstOrNull()?.uppercaseChar()?.toString().orEmpty()
        if (letter.isEmpty()) {
            Icon(Icons.Filled.GraphicEq, null, tint = colors.muted, modifier = Modifier.size(size / 3))
        } else {
            Text(letter, fontSize = (size.value / 2.4f).sp, fontWeight = FontWeight.Bold, color = colors.onSurface)
        }
    }
}

private fun formatDuration(seconds: Float): String {
    val total = seconds.toInt().coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}

/** Documents only: this page reads text, it does not transcribe sound. */
private fun pickDocuments(): List<File> {
    val chooser = JFileChooser().apply {
        dialogTitle = "Import text or PDF"
        isMultiSelectionEnabled = true
        fileSelectionMode = JFileChooser.FILES_ONLY
    }
    if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return emptyList()
    return chooser.selectedFiles.toList().filter { it.isFile }
}

/**
 * Reads the picked files into one script.
 *
 * Plain text is taken as it is; a PDF goes through the extractor's text passives. Unreadable files
 * are named in the message rather than silently dropped, because a scanned PDF that yields no text
 * is the common case and the user would otherwise think the import did nothing.
 */
private fun readDocuments(files: List<File>): Pair<String, String> {
    val parts = mutableListOf<String>()
    val failures = mutableListOf<String>()
    for (file in files) {
        val text = runCatching {
            when (file.extension.lowercase(Locale.ROOT)) {
                "pdf" -> PdfText.extract(file.readBytes())
                else -> file.readText()
            }
        }.getOrElse { failures.add("${file.name} (${it.message ?: "unreadable"})"); "" }
        if (text.isNotBlank()) parts.add(text.trim()) else failures.add(file.name)
    }
    if (parts.isEmpty()) {
        return "" to (
            "Nothing readable came out of ${failures.joinToString(", ")}. A PDF with no text layer " +
                "has to be read by an app that can OCR it."
            )
    }
    val script = parts.joinToString("\n\n")
    val read = "${script.length} characters lifted out of " +
        "${if (parts.size == 1) "the file" else "${parts.size} files"}"
    val status = if (failures.isEmpty()) {
        "$read. The field is off while these are the input."
    } else {
        "$read; skipped ${failures.joinToString(", ")}."
    }
    return script to status
}

