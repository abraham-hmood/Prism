package com.prism.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prism.desktop.ui.LocalPrismColors
import com.prism.desktop.ui.LocalPrismTheme
import com.prism.desktop.ui.PrismTheme
import com.prism.launcher.pdb.Pdb
import kotlinx.coroutines.delay

/**
 * The code a desktop shows when another device asks to open its debug bridge.
 *
 * A separate fullscreen window, like the wireless-display pairing code: it has to cover whatever
 * page the user is on, and a twelve-character code read across a room cannot live in a toast.
 *
 * IT USES THE SELECTED THEME. This screen used to be hardcoded near-black, which meant a user on
 * the light or the paper theme was shown a different application at the one moment they have to
 * trust what is in front of them. Everything here now comes from [LocalPrismColors] and
 * [LocalPrismTheme], so the verification screen is the same Prism they were already looking at.
 */
object PdbUi {
    var prompt by mutableStateOf<Pdb.Prompt?>(null)
        internal set
}

@Composable
fun PdbPhraseWindow(prompt: Pdb.Prompt, onCancel: () -> Unit) {
    LaunchedEffect(prompt.id) {
        delay(120_000L)
        Pdb.cancelPrompt(prompt.id)
        onCancel()
    }

    PrismTheme {
        val colors = LocalPrismColors.current
        val spec = LocalPrismTheme.current
        Box(Modifier.fillMaxSize().background(colors.background)) {
            Column(
                Modifier.fillMaxSize().padding(spec.density.page.dp.coerceAtLeast(28.dp)),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    "PRISM DEBUG BRIDGE",
                    color = colors.accent,
                    fontSize = 12.sp,
                    letterSpacing = 2.sp,
                )
                Text(
                    "Type this code on the other device",
                    color = colors.onSurface,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 18.dp),
                )
                Text(
                    "Waiting for " + prompt.from.ifBlank { "the other device" } + " to type it.",
                    color = colors.muted,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(top = 8.dp, bottom = 28.dp),
                )
                Surface(
                    color = colors.card,
                    shape = RoundedCornerShape(spec.shape.card.dp.coerceAtLeast(12.dp)),
                    modifier = Modifier.widthIn(max = 820.dp),
                ) {
                    Text(
                        prompt.phrase,
                        color = colors.onSurface,
                        fontSize = 40.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        letterSpacing = 4.sp,
                        modifier = Modifier.padding(horizontal = 40.dp, vertical = 36.dp),
                    )
                }
                Text(
                    "Upper case, lower case, digits and symbols. Case matters; the spaces are only " +
                        "there to group the characters. The code is shown only here, is used once, " +
                        "and is never sent over the network.",
                    color = colors.faint,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 24.dp).widthIn(max = 620.dp),
                )
                Button(
                    onClick = {
                        Pdb.cancelPrompt(prompt.id)
                        onCancel()
                    },
                    modifier = Modifier.padding(top = 28.dp),
                ) { Text("Cancel") }
            }
        }
    }
}
