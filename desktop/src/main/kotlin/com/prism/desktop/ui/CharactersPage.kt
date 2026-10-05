package com.prism.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prism.core.PrismPlatform
import com.prism.launcher.characters.CharacterPrompt
import com.prism.launcher.characters.CharacterStore
import com.prism.launcher.messaging.SamConversation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * User-created characters, and conversations with them. PHASE 103.
 *
 * ## Why this was a small phase
 *
 * Because the store and the prompt construction were never Android. `CharacterStore` had one Android import
 * -- a `Context` for a preferences handle and a files directory -- and `CharacterPrompt` had none at all.
 * Both moved to `:core` unchanged apart from the seam described below, so a character created on either
 * platform is the same record, read by the same code, prompted the same way.
 *
 * ## The one thing that could not move
 *
 * `importAsset` took an `android.net.Uri` and opened it with a `ContentResolver`. Neither type exists on a
 * desktop, and a `File` does not exist as a picked document on Android. So the store now asks for the one
 * thing both platforms can produce -- a function returning an `InputStream` -- and each caller opens it its
 * own way. That single change is what let the file move.
 *
 * ## Conversations go through Sam, deliberately
 *
 * A character's backend choice is SAM, NORA or AETHER, and the SAM path means "whatever engine Sam is
 * currently set to" -- local, cloud, Ollama, or a model hosted by a peer on the mesh. A character page that
 * picked an engine itself would diverge from the rest of Prism the first time the user changed theirs.
 *
 * Every turn is sent `standalone`, so a character's roleplay never enters the user's own history with Sam.
 * Two characters would otherwise contaminate each other through a shared conversation.
 */
@Composable
fun CharactersPage() {
    val colors = LocalPrismColors.current
    val scope = rememberCoroutineScope()

    var characters by remember { mutableStateOf<List<CharacterStore.Character>>(emptyList()) }
    var open by remember { mutableStateOf<CharacterStore.Character?>(null) }
    var editing by remember { mutableStateOf<CharacterStore.Character?>(null) }
    var creating by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }

    fun reload() {
        characters = runCatching { CharacterStore.all() }.getOrDefault(emptyList())
    }

    LaunchedEffect(Unit) { reload() }

    val talking = open
    if (talking != null) {
        CharacterConversation(talking, onBack = { open = null })
        return
    }

    PageScaffold("Characters", "Personas with their own prompt, model and memory") {
        Column(Modifier.verticalScroll(rememberScrollState())) {

            Row(Modifier.padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { creating = true }) { Text("Create a character") }
                Spacer(Modifier.width(10.dp))
                Text(
                    characters.size.toString() + " character(s)",
                    fontSize = 12.sp,
                    color = colors.faint,
                )
            }

            if (note.isNotBlank()) {
                Card { Text(note, fontSize = 13.sp, modifier = Modifier.padding(16.dp)) }
                Spacer(Modifier.height(10.dp))
            }

            if (characters.isEmpty()) {
                Card {
                    Text(
                        "No characters yet. A character is a name, a description of who they are, and " +
                            "which engine answers as them. The description becomes their prompt, so it " +
                            "is worth writing as though describing a person rather than a task.",
                        fontSize = 13.sp,
                        color = colors.muted,
                        lineHeight = 19.sp,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            } else {
                Card {
                    characters.forEachIndexed { index, character ->
                        if (index > 0) Hairline()
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .rightClickable { editing = character }
                                .clickableRow { open = character }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Portrait(character, 40.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(character.name, fontSize = 14.sp)
                                Text(
                                    character.description.lineSequence().firstOrNull().orEmpty()
                                        .take(90)
                                        .ifBlank { "No description" },
                                    fontSize = 11.sp,
                                    color = colors.faint,
                                )
                            }
                            Text(character.backend.label, fontSize = 11.sp, color = colors.accent)
                        }
                    }
                }
                SectionFooter(
                    "Click to talk. Right-click to edit or delete. Characters are stored in the same " +
                        "place the phone stores them, so one made here appears there after a profile " +
                        "transfer or a trusted-device sync."
                )
            }

            Spacer(Modifier.height(28.dp))
        }
    }

    if (creating || editing != null) {
        CharacterEditor(
            existing = editing,
            onDismiss = { creating = false; editing = null },
            onSaved = { saved ->
                CharacterStore.save(saved)
                creating = false
                editing = null
                reload()
                note = "Saved " + saved.name + "."
            },
            onDeleted = { target ->
                CharacterStore.delete(target.id)
                creating = false
                editing = null
                reload()
                note = "Deleted " + target.name + "."
            },
        )
    }
}

/** A character's portrait, or their initial when they have no image. */
@Composable
private fun Portrait(character: CharacterStore.Character, size: androidx.compose.ui.unit.Dp) {
    val bitmap = remember(character.imagePath) {
        character.imagePath?.takeIf { it.isNotBlank() }?.let { path ->
            runCatching {
                File(path).takeIf { it.isFile }?.let {
                    PrismPlatform.images.decode(it.readBytes())?.toComposeBitmap()
                }
            }.getOrNull()
        }
    }
    Surface(color = Color(0xFF24242C), shape = CircleShape, modifier = Modifier.size(size)) {
        if (bitmap != null) {
            Image(bitmap, null, contentScale = ContentScale.Crop, modifier = Modifier.size(size))
        } else {
            Box(Modifier.size(size), contentAlignment = Alignment.Center) {
                Text(
                    character.name.take(1).uppercase(),
                    fontSize = (size.value / 2.4f).sp,
                    color = Color(0xFF9A9AA6),
                )
            }
        }
    }
}

/**
 * A conversation with one character.
 *
 * ## The transcript is kept per character, on disk
 *
 * In the character's own asset directory, which is what `CharacterStore.assetDir` is for. Holding it only
 * in memory would mean closing the page forgot who you had been talking to, and a shared transcript would
 * mean two characters reading each other's lines.
 */
@Composable
private fun CharacterConversation(character: CharacterStore.Character, onBack: () -> Unit) {
    val colors = LocalPrismColors.current
    val scope = rememberCoroutineScope()

    val transcript = remember(character.id) { File(CharacterStore.assetDir(character.id), "transcript.txt") }
    var lines by remember(character.id) { mutableStateOf<List<String>>(emptyList()) }
    var draft by remember(character.id) { mutableStateOf("") }
    var busy by remember(character.id) { mutableStateOf(false) }

    LaunchedEffect(character.id) {
        lines = withContext(Dispatchers.IO) {
            runCatching { transcript.takeIf { it.isFile }?.readLines() }.getOrNull().orEmpty()
        }
    }

    fun append(line: String) {
        lines = lines + line
        // Appended immediately rather than on leaving the page: a crash or a close mid-conversation should
        // not lose the exchange that was already had.
        runCatching { transcript.appendText(line + System.lineSeparator()) }
    }

    PageScaffold(character.name, character.backend.label + " · " + character.backend.description) {
        Row(Modifier.padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onBack) { Text("Back", fontSize = 13.sp) }
            Spacer(Modifier.width(10.dp))
            Portrait(character, 28.dp)
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = {
                lines = emptyList()
                runCatching { transcript.delete() }
            }) { Text("Forget the conversation", fontSize = 12.sp) }
        }

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            if (lines.isEmpty()) {
                Card {
                    Text(
                        character.description.ifBlank { "This character has no description." },
                        fontSize = 13.sp,
                        color = colors.muted,
                        lineHeight = 19.sp,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            lines.forEach { line ->
                val fromUser = line.startsWith("you: ")
                Surface(
                    color = if (fromUser) Color(0xFF1F2A3A) else Color(0xFF1E1E26),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                ) {
                    Text(
                        line.substringAfter(": ", line),
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            CommittingField(value = draft, modifier = Modifier.weight(1f)) { draft = it }
            Spacer(Modifier.width(8.dp))
            Button(
                enabled = !busy && draft.isNotBlank(),
                onClick = {
                    val text = draft.trim()
                    draft = ""
                    append("you: " + text)
                    busy = true
                    scope.launch {
                        val reply = withContext(Dispatchers.IO) {
                            runCatching {
                                // The shared prompt builder, so the character behaves identically on both
                                // platforms. standalone, so roleplay stays out of the user's own history.
                                val turn = SamConversation.send(
                                    CharacterPrompt.turn(character, text),
                                    standalone = true,
                                )
                                if (turn.error != null || turn.text.isBlank()) null else turn.text
                            }.getOrNull()
                        }
                        append(
                            character.name.lowercase() + ": " +
                                (reply ?: "(no engine is configured, so nobody answered)")
                        )
                        busy = false
                    }
                },
            ) { Text(if (busy) "…" else "Send") }
        }
    }
}

/** Create or edit. One composable for both, so a field cannot exist only when creating. */
@Composable
private fun CharacterEditor(
    existing: CharacterStore.Character?,
    onDismiss: () -> Unit,
    onSaved: (CharacterStore.Character) -> Unit,
    onDeleted: (CharacterStore.Character) -> Unit,
) {
    val colors = LocalPrismColors.current
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var description by remember { mutableStateOf(existing?.description.orEmpty()) }
    var backend by remember { mutableStateOf(existing?.backend ?: CharacterStore.Backend.SAM) }
    var imagePath by remember { mutableStateOf(existing?.imagePath.orEmpty()) }
    val id = remember { existing?.id ?: CharacterStore.newId() }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(color = colors.surface, shape = RoundedCornerShape(14.dp)) {
            Column(
                Modifier.padding(20.dp).heightIn(max = 620.dp).verticalScroll(rememberScrollState()),
            ) {
                Text(
                    if (existing == null) "Create a character" else "Edit " + existing.name,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(14.dp))

                Text("Name", fontSize = 13.sp)
                Spacer(Modifier.height(4.dp))
                CommittingField(value = name, modifier = Modifier.fillMaxWidth()) { name = it }
                Spacer(Modifier.height(12.dp))

                Text("Who they are", fontSize = 13.sp)
                Text(
                    "This becomes their prompt. Describe a person, not a task.",
                    fontSize = 11.sp,
                    color = colors.faint,
                )
                Spacer(Modifier.height(4.dp))
                CommittingField(value = description, modifier = Modifier.fillMaxWidth()) { description = it }
                Spacer(Modifier.height(12.dp))

                Text("Who answers", fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                CharacterStore.Backend.entries.forEach { candidate ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickableRow { backend = candidate }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Surface(
                            color = if (candidate == backend) colors.accent else Color(0xFF2A2A33),
                            shape = CircleShape,
                            modifier = Modifier.padding(top = 4.dp).size(9.dp),
                        ) {}
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(candidate.label, fontSize = 13.sp)
                            Text(
                                candidate.description,
                                fontSize = 11.sp,
                                color = colors.faint,
                                lineHeight = 15.sp,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                Text("Portrait", fontSize = 13.sp)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = {
                        val dialog = java.awt.FileDialog(
                            null as java.awt.Frame?, "Choose a portrait", java.awt.FileDialog.LOAD,
                        )
                        dialog.isVisible = true
                        val chosen = dialog.file
                        if (chosen != null) {
                            val source = File(dialog.directory, chosen)
                            // The same store call Android makes, with the desktop's own way of opening the
                            // file -- which is the whole point of importAsset taking a stream.
                            imagePath = CharacterStore.importAsset(id, chosen) {
                                source.takeIf { it.isFile }?.inputStream()
                            }.orEmpty()
                        }
                    }) { Text("Choose a file", fontSize = 12.sp) }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        imagePath.substringAfterLast(File.separatorChar).ifBlank { "none" },
                        fontSize = 11.sp,
                        color = colors.faint,
                    )
                }

                Spacer(Modifier.height(18.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (existing != null) {
                        OutlinedButton(onClick = { onDeleted(existing) }) {
                            Text("Delete", fontSize = 13.sp, color = Color(0xFFFF6B6B))
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(onClick = onDismiss) { Text("Cancel", fontSize = 13.sp) }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        enabled = name.isNotBlank(),
                        onClick = {
                            onSaved(
                                CharacterStore.Character(
                                    id = id,
                                    name = name.trim(),
                                    description = description.trim(),
                                    backend = backend,
                                    modelPath = existing?.modelPath,
                                    imagePath = imagePath.takeIf { it.isNotBlank() },
                                )
                            )
                        },
                    ) { Text(if (existing == null) "Create" else "Save") }
                }
            }
        }
    }
}
