package com.prism.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prism.launcher.PrismSettings
import com.prism.launcher.messaging.ImageCraft

/**
 * ImageCraft on the desktop: the picture, then the words, in one column.
 *
 * ## Why the page is this plain
 *
 * Everything that makes this work -- the image engine, the Enhance button's rewrite, the question of
 * whether the chosen backend can take the work -- is [ImageCraft]'s, shared with the phone. What is
 * left for a page to do is take a sentence, show a picture, and be honest about what happened in
 * between, which is what these rows are.
 *
 * ## The order
 *
 * Picture first, field under it. On a desktop there is room for both at once, so the image is given
 * the space and the input sits directly beneath it where the eye ends up after looking at the result.
 *
 * ## Background work
 *
 * The run lives in [ImageCraft], not in this composable, so navigating away from the page does not
 * cancel it: the flow is process-scoped and the page re-reads it on return. Progress is shown while it
 * runs and the result arrives in the same state when it finishes, on whatever page the user is on --
 * with a desktop notification posted from the completion hook, because a tray balloon is the only
 * thing that can reach the user outside the window.
 */
@Composable
fun ImageCraftPage() {
    val colors = LocalPrismColors.current
    val state by ImageCraft.state.collectAsState()
    var prompt by remember { mutableStateOf("") }
    var enhancing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val gate = remember(state.running) { ImageCraft.gate() }
    val size = PrismSettings.getImageCraftSize()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp)) {
        Text("ImageCraft", fontSize = 24.sp, color = colors.onSurface, fontWeight = FontWeight.Medium)
        Text(
            "Your words are what gets drawn. Resolution: ${size.label} · " +
                "backend: ${com.prism.launcher.messaging.AiBackend.chosenLabel()}. The wand rewrites " +
                "the prompt with your text model before you draw; change either model under " +
                "Intelligence & Messaging in Prism Settings.",
            fontSize = 12.sp,
            color = colors.faint,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )

        if (!gate.ready) {
            // THE REASON, NOT A DISABLED BUTTON. A page that refuses without saying which of the two
            // models is missing sends the user to Settings to guess.
            Box(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF3A1D1D))
                    .padding(14.dp),
            ) {
                Text(gate.reason, fontSize = 13.sp, color = Color(0xFFFFB4B4))
            }
            Spacer(Modifier.height(12.dp))
        }

        Box(
            Modifier.fillMaxWidth()
                .heightIn(min = 240.dp, max = 520.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(colors.card),
            contentAlignment = Alignment.Center,
        ) {
            val picture = state.image
            if (picture != null) {
                Image(
                    bitmap = remember(picture) { picture.toComposeBitmap() },
                    contentDescription = "The picture ImageCraft drew",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp, max = 520.dp)
                        .padding(8.dp),
                )
            } else {
                Text(
                    if (state.running) "Drawing…" else "Nothing drawn yet.",
                    fontSize = 13.sp,
                    color = colors.faint,
                )
            }
        }

        if (state.running) {
            Spacer(Modifier.height(10.dp))
            if (state.progress < 0f) {
                // Indeterminate when the engine has no steps to report -- a MediaPipe bundle has no
                // callback and a cloud render has none either. A bar that pretends to move would be
                // the one place this page lied.
                LinearProgressIndicator(Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(
                    progress = { state.progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        val status = when {
            state.running -> state.stage
            state.error != null -> "Failed: ${state.error}"
            state.complete -> buildString {
                append("Drawn in %.1fs".format(state.millis / 1000.0))
                if (state.engine.isNotBlank()) append(" with ${state.engine}")
                if (state.savedTo.isNotBlank()) append(" — saved to ${state.savedTo}")
            }
            state.stage.isNotEmpty() -> state.stage
            else -> ""
        }
        if (status.isNotEmpty()) {
            Text(status, fontSize = 13.sp, color = colors.muted, modifier = Modifier.padding(top = 8.dp))
        }
        if (state.note.isNotBlank()) {
            Text(
                state.note,
                fontSize = 12.sp,
                color = colors.faint,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        Spacer(Modifier.height(14.dp))
        // THE FIELD AND ITS WAND. The wand asks the text model to rewrite what is in the field and
        // puts the answer back in the field -- nothing reaches the image model without the user
        // seeing it first, which is the whole reason this is a button and not a step in the run.
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            OutlinedTextField(
                value = prompt,
                onValueChange = { prompt = it },
                label = { Text("Describe the picture you want") },
                minLines = 2,
                maxLines = 5,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = {
                    if (enhancing || prompt.isBlank()) return@IconButton
                    enhancing = true
                    scope.launch {
                        val rewritten = runCatching { ImageCraft.enhance(prompt) }.getOrNull()
                        enhancing = false
                        if (!rewritten.isNullOrBlank()) prompt = rewritten
                    }
                },
                enabled = !enhancing && prompt.isNotBlank() && ImageCraft.canEnhance(),
                modifier = Modifier.padding(bottom = 4.dp),
            ) {
                Icon(
                    if (enhancing) Icons.Filled.HourglassEmpty else Icons.Filled.AutoFixHigh,
                    "Rewrite the prompt with your text model",
                    tint = if (!enhancing && ImageCraft.canEnhance()) colors.accent else colors.faint,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = {
                    // Straight into the engine: there is no service to own the run on a desktop, the
                    // process is the whole machine's worth of priority already.
                    if (prompt.isNotBlank()) ImageCraft.start(prompt)
                },
                enabled = !state.running && gate.ready && prompt.isNotBlank(),
            ) { Text("Draw") }

            if (state.running) {
                OutlinedButton(onClick = { ImageCraft.cancel() }) { Text("Stop") }
            }
            if (state.complete || state.error != null) {
                OutlinedButton(onClick = { ImageCraft.clear() }) { Text("Clear") }
            }
            Spacer(Modifier.width(4.dp))
            Text(
                if (state.running) "Runs in the background — leave this page if you like."
                else "Saved under Pictures/Prism/ImageCraft.",
                fontSize = 12.sp,
                color = colors.faint,
            )
        }
    }
}
