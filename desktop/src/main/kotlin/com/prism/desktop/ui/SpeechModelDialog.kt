package com.prism.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
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
import com.prism.launcher.PrismSettings
import com.prism.launcher.speech.ImportedAudioEngine
import com.prism.launcher.speech.PrismSpeaker

/**
 * Picking a speech model, as the Android settings screen does it.
 *
 * The row this replaces used to be a text field where a path was typed blind -- no recognition of
 * what the file was, no way back to Kokoro that did not involve deleting a string by hand. The
 * phone's flow for this same setting is the model this follows, with the same words at every step:
 * the summary line names what is in use, the picker asks which KIND of model before asking where it
 * is, and removing an imported model asks "Use Kokoro again?" rather than just clearing the field.
 *
 * ## Where this matches the phone and where it honestly cannot
 *
 * The phone asks model type because one shared file picker serves text, image and audio imports
 * from three different rows. On a desktop those other two kinds are already first-class pages
 * (Models and Image Generation), so their part of the chooser hands off to the page that really
 * keeps them instead of duplicating an import pipeline nobody maintains twice. The audio leg -- the
 * one this row exists for -- is the full flow.
 */
@Composable
fun SpeechModelDialog(onDismiss: () -> Unit) {
    val colors = LocalPrismColors.current

    var path by remember { mutableStateOf(PrismSettings.getLocalAudioModelPath()) }
    var pickingType by remember { mutableStateOf(false) }
    var confirmingRemoval by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    var noteOk by remember { mutableStateOf(true) }

    fun afterChange(newPath: String, message: String) {
        PrismSettings.setLocalAudioModelPath(newPath)
        path = newPath
        PrismSpeaker.invalidate()
        noteOk = true
        note = message
    }

    fun openChooser() {
        val dialog = java.awt.FileDialog(null as java.awt.Frame?, "Choose a speech model", java.awt.FileDialog.LOAD)
        dialog.isVisible = true
        val name = dialog.file ?: return
        val picked = java.io.File(dialog.directory, name)
        if (!picked.isFile) return
        afterChange(
            picked.absolutePath,
            "Intelligence Acquired: " + picked.name,
        )
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = colors.surface,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.widthIn(max = 520.dp),
        ) {
            Column(Modifier.padding(20.dp)) {
                Text("Speech model", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(
                    ImportedAudioEngine.describe(path),
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = if (path.isEmpty()) colors.faint else colors.onSurface,
                )

                if (note.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Surface(
                        color = if (noteOk) Color(0xFF14301F) else Color(0xFF331A1A),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            note,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                            color = if (noteOk) Color(0xFF9BE8B4) else Color(0xFFFFB4B4),
                            modifier = Modifier.padding(10.dp),
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))

                when {
                    pickingType -> {
                        Text(
                            "Model Type",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Which of the three kinds is this file?",
                            fontSize = 12.sp,
                            color = colors.faint,
                        )
                        Spacer(Modifier.height(8.dp))
                        Column(Modifier.heightIn(max = 300.dp)) {
                            listOf(
                                ImportType(
                                    "Text AI",
                                    "A language model — .task, .gguf or .bin",
                                ) {
                                    pickingType = false
                                    onDismiss()
                                    SettingsActions.navigate?.invoke(PageId.MODELS)
                                },
                                ImportType(
                                    "Image Gen",
                                    "A diffusion model for generating pictures",
                                ) {
                                    pickingType = false
                                    onDismiss()
                                    SettingsActions.navigate?.invoke(PageId.IMAGE_GEN)
                                },
                                ImportType(
                                    "Audio",
                                    "A text-to-speech model — " +
                                        ImportedAudioEngine.ACCEPTED_EXTENSIONS.joinToString(", ") { ".$it" },
                                ) {
                                    pickingType = false
                                    openChooser()
                                },
                            ).forEachIndexed { index, entry ->
                                if (index > 0) Hairline()
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            entry.onClick()
                                        }
                                        .padding(horizontal = 4.dp, vertical = 10.dp),
                                ) {
                                    Column {
                                        Text(entry.label, fontSize = 14.sp)
                                        Text(
                                            entry.subtitle,
                                            fontSize = 11.sp,
                                            lineHeight = 15.sp,
                                            color = colors.faint,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    confirmingRemoval -> {
                        Text("Use Kokoro again?", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Removing this model does not delete the file. Prism goes back " +
                                "to speaking with Kokoro-82M.",
                            fontSize = 13.sp,
                            lineHeight = 19.sp,
                            color = colors.faint,
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Button(onClick = {
                                confirmingRemoval = false
                                afterChange("", "Kokoro-82M is the voice again.")
                            }) { Text("Remove", fontSize = 13.sp) }
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(onClick = { confirmingRemoval = false }) {
                                Text("Keep", fontSize = 13.sp)
                            }
                        }
                    }

                    else -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Button(onClick = { pickingType = true }) {
                                Text(
                                    if (path.isEmpty()) "Choose a model" else "Choose a different model",
                                    fontSize = 13.sp,
                                )
                            }
                            if (path.isNotEmpty()) {
                                Spacer(Modifier.width(8.dp))
                                OutlinedButton(onClick = { confirmingRemoval = true }) {
                                    Text("Use Kokoro again", fontSize = 13.sp)
                                }
                            }
                            Spacer(Modifier.weight(1f))
                            OutlinedButton(onClick = onDismiss) { Text("Close", fontSize = 13.sp) }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "A model of one's own overrides Kokoro-82M, the way importing does on " +
                                "the phone.",
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            color = colors.faint,
                        )
                    }
                }
            }
        }
    }
}

/** One line of the phone's "Model Type" chooser, label and its description. */
private data class ImportType(val label: String, val subtitle: String, val onClick: () -> Unit)
