package com.prism.desktop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prism.desktop.writer.DesktopTypist
import com.prism.launcher.PrismSettings
import com.prism.launcher.writer.WriterDictionary
import com.prism.launcher.writer.WriterGifSource
import com.prism.launcher.writer.WriterUserDictionary

/**
 * Prism Writer's settings, and the button that opens the keyboard. PHASE 102.
 *
 * ## Why the keyboard is a window and this page is not it
 *
 * A virtual keyboard has to be visible while another application has focus. A page inside Prism's own
 * window cannot be: the moment the user clicks into the thing they are typing into, Prism's window
 * goes behind it. So the keyboard is a separate always-on-top window and this page is where it is
 * turned on and configured.
 */
@Composable
fun WriterPage() {
    val colors = LocalPrismColors.current
    var open by remember { mutableStateOf(false) }
    var revision by remember { mutableStateOf(0) }

    val available = remember { DesktopTypist.isAvailable() }
    val learned = remember(revision) { WriterDictionary.userWordList().size }
    val dictionaries = remember(revision) {
        runCatching { WriterUserDictionary.dictionaries().size }.getOrDefault(0)
    }

    PageScaffold("Prism Writer", "An on-screen keyboard that types into whatever has focus") {
        Column(Modifier.verticalScroll(rememberScrollState())) {

            Card {
                Column(Modifier.padding(16.dp)) {
                    if (!available) {
                        Text(
                            DesktopTypist.unavailableReason(),
                            fontSize = 12.sp,
                            color = Color(0xFFFFB4B4),
                            lineHeight = 18.sp,
                        )
                    } else {
                        Text(
                            "Opens in its own always-on-top window. Click into the field you want " +
                                "to type into — in any application — and the keyboard types there.",
                            fontSize = 12.sp,
                            color = colors.muted,
                            lineHeight = 18.sp,
                        )
                        Spacer(Modifier.height(10.dp))
                        Button(onClick = { open = true }) { Text("Open the keyboard") }
                    }
                }
            }

            SectionHeader("what it is, and what it is not")
            Card {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        "IT IS NOT A SYSTEM INPUT METHOD. Windows and Linux both require an input " +
                            "method to be a separately installed system component — a TSF text " +
                            "service, or an IBus engine — not something an application can be. " +
                            "What this is instead is an on-screen keyboard: it synthesises " +
                            "keystrokes into the focused window, the way every accessibility and " +
                            "tablet keyboard does.",
                        fontSize = 12.sp,
                        color = Color(0xFFE0C060),
                        lineHeight = 18.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "The one thing lost is appearing automatically when a text field is " +
                            "focused — nothing tells Prism that happened. Everything else the " +
                            "phone's keyboard does is here, and all of it is the same code: the " +
                            "layout grid, the suggestions, the learned dictionary, autocorrect, " +
                            "the glide decoder, the emoji table and the GIF search are all in " +
                            ":core and shared.",
                        fontSize = 11.sp,
                        color = colors.faint,
                        lineHeight = 16.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Anything outside the directly typeable ASCII set — an emoji, a GIF link, " +
                            "a character on a layout you do not have — goes through the clipboard " +
                            "and Ctrl+V, because a synthesised key code that is not on the " +
                            "physical layout types the wrong character rather than failing. " +
                            "Whatever you had copied is put back afterwards.",
                        fontSize = 11.sp,
                        color = colors.faint,
                        lineHeight = 16.sp,
                    )
                }
            }

            SectionHeader("behaviour")
            Card {
                Column {
                    WriterToggle(
                        "Suggestions",
                        PrismSettings.getWriterSuggestions(),
                        "Three words above the keys, from the shared dictionary and what you have typed.",
                    ) { PrismSettings.setWriterSuggestions(it); revision++ }
                    Hairline()
                    WriterToggle(
                        "Autocorrect",
                        PrismSettings.getWriterAutocorrect(),
                        "Applied on space, so the correction is still undoable with one backspace.",
                    ) { PrismSettings.setWriterAutocorrect(it); revision++ }
                }
            }

            SectionHeader("dictionary")
            Card {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        learned.toString() + " learned word(s) across " + dictionaries +
                            " dictionar" + (if (dictionaries == 1) "y" else "ies") +
                            ". Stored under the same setting the phone writes, so a profile " +
                            "transfer carries the whole thing.",
                        fontSize = 12.sp,
                        color = colors.muted,
                        lineHeight = 18.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "GIF search: " + (
                            if (WriterGifSource.hasKey()) "a key is configured"
                            else "needs an API key, set in Prism Settings"
                            ),
                        fontSize = 11.sp,
                        color = colors.faint,
                    )
                }
            }

            SectionFooter(
                "The plan for this phase said almost certainly not to port the keyboard, because a " +
                    "desktop has a hardware one and no IME framework to plug into. That was " +
                    "overruled on instruction; the technical point still holds and is stated above " +
                    "rather than quietly contradicted."
            )
            Spacer(Modifier.height(28.dp))
        }
    }

    if (open) {
        WriterKeyboardWindow(onClose = { open = false })
    }
}

@Composable
private fun WriterToggle(
    label: String,
    value: Boolean,
    detail: String,
    onChange: (Boolean) -> Unit,
) {
    val colors = LocalPrismColors.current
    Row(
        Modifier.fillMaxWidth().clickableRow { onChange(!value) }.padding(13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 13.sp)
            Text(detail, fontSize = 10.sp, color = colors.faint, lineHeight = 15.sp)
        }
        Text(
            if (value) "on" else "off",
            fontSize = 11.sp,
            color = if (value) colors.accent else colors.faint,
        )
    }
}
