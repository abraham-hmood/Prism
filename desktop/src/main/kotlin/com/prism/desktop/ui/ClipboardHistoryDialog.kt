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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.prism.desktop.DesktopClipboard
import com.prism.launcher.clipboard.ClipboardHistory
import com.prism.launcher.trusted.TrustedDevices
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The clipboard history, from the desktop's settings page.
 *
 * ## Why a dialog rather than a page
 *
 * The screen exists to pick one thing and get back to where you were; a page would be a navigation
 * away from Settings and another one back. The phone's version is an activity because Android has
 * no other way to show a full-screen list, and the content is deliberately the same.
 *
 * ## Why remote entries can be picked
 *
 * Choosing one marks it active. Pasting it in the file explorer is what downloads it, so a user can
 * copy a folder on their phone, walk to the desktop, and only then pay for the transfer.
 */
@Composable
fun ClipboardHistoryDialog(onDismiss: () -> Unit) {
    val colors = LocalPrismColors.current
    var revision by remember { mutableStateOf(0) }
    var note by remember { mutableStateOf("") }

    val entries = remember(revision) { ClipboardHistory.all() }
    val active = remember(revision) { ClipboardHistory.active() }
    val paired = remember(revision) {
        TrustedDevices.recipientsFor(TrustedDevices.Kind.CLIPBOARD)
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = colors.surface,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.widthIn(max = 620.dp),
        ) {
            Column(Modifier.padding(20.dp)) {
                Text("Clipboard history", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(
                    "Everything copied here, and everything a trusted device has shared. " +
                        "Tap an item to make it active: text pastes anywhere, and a file or " +
                        "folder pastes in the file explorer.",
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = colors.faint,
                )

                if (note.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(note, fontSize = 12.sp, color = colors.accent)
                }

                Spacer(Modifier.height(10.dp))

                if (entries.isEmpty()) {
                    Text("Nothing copied yet.", fontSize = 13.sp, color = colors.faint)
                } else {
                    Column(
                        Modifier
                            .heightIn(max = 380.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        entries.forEachIndexed { index, entry ->
                            if (index > 0) Hairline()
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        note = DesktopClipboard.use(entry)
                                        revision++
                                    }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        (if (entry.id == active?.id) "✓ " else "") + entry.title(),
                                        fontSize = 13.sp,
                                        color = if (entry.id == active?.id) colors.accent else colors.onSurface,
                                    )
                                    Text(
                                        entry.summary() + " · " + time(entry.at),
                                        fontSize = 11.sp,
                                        color = colors.faint,
                                        lineHeight = 15.sp,
                                    )
                                }
                                TextButton(onClick = {
                                    ClipboardHistory.remove(entry.id)
                                    revision++
                                }) {
                                    Text("Remove", fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                Text(
                    if (paired.isEmpty()) {
                        "No device is paired for the clipboard yet. Pair one under Trusted devices " +
                            "to send and receive copies."
                    } else {
                        "Sending to: " + paired.joinToString(", ") { it.name }
                    },
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    color = colors.faint,
                )

                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = {
                        ClipboardHistory.clear()
                        revision++
                    }) { Text("Clear history", fontSize = 12.sp) }
                    Spacer(Modifier.width(8.dp))
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(onClick = onDismiss) { Text("Close", fontSize = 12.sp) }
                }
            }
        }
    }
}

private fun time(at: Long): String =
    SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(at))
