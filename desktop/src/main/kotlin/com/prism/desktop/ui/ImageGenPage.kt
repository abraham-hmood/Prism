package com.prism.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prism.core.PrismImage
import com.prism.launcher.PrismSettings
import com.prism.launcher.messaging.ImageGeneration
import com.prism.launcher.messaging.ImageGenerator
import com.prism.launcher.nora.NoraConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * PHASE 34 — generating an image from a prompt.
 *
 * ## Why the engines are listed rather than chosen silently
 *
 * Because they produce completely different things and the difference matters more than the
 * convenience of not choosing. The cloud route returns a photograph and bills for it. Nora returns
 * what Prism's own network has learned to expect, which is an impression and sometimes nothing. A page
 * that hid that behind one button would have users concluding image generation is broken when what
 * actually happened is that it ran the only engine available and that engine is honest about its
 * limits.
 *
 * The unavailable ones are shown DISABLED WITH THEIR REASON, which is the same rule the browser menu
 * and the agentic tool list follow here. "Stable Diffusion — not built" teaches something; a list with
 * two entries teaches nothing.
 *
 * ## Why generation runs on IO and the result is posted back
 *
 * A diffusion step is seconds and a cloud round trip can be longer. Compose Desktop renders on the
 * main thread like any UI toolkit, so generating there freezes the window for the duration — and a
 * frozen window during a long operation is indistinguishable from a hung one.
 */
@Composable
fun ImageGenPage() {
    val colors = LocalPrismColors.current
    val scope = rememberCoroutineScope()

    var prompt by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(PrismSettings.getImageEngineId()) }
    var busy by remember { mutableStateOf(false) }
    var stage by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<ImageBitmap?>(null) }
    var savedTo by remember { mutableStateOf<String?>(null) }

    val engines = remember { ImageGeneration.all() }

    fun run() {
        if (busy) return
        val text = prompt.trim()
        if (text.isEmpty()) {
            status = "Type something to draw."
            return
        }
        busy = true
        stage = "Starting…"
        status = null
        preview = null
        savedTo = null

        scope.launch {
            val result = withContext(Dispatchers.IO) {
                ImageGeneration.generate(text, selected.ifBlank { null }) { s ->
                    // Assigning Compose state from the IO thread is safe -- snapshot state is
                    // thread-safe and recomposition is scheduled on the main thread.
                    stage = s
                }
            }
            busy = false
            stage = ""

            val image: PrismImage? = result.image
            if (image == null) {
                status = result.error ?: "${result.engine} produced nothing."
                return@launch
            }
            preview = image.toComposeBitmap()
            val file = withContext(Dispatchers.IO) {
                ImageGeneration.save(image, NoraConfig.outputDir(), "image")
            }
            savedTo = file?.absolutePath
            status = "${result.engine} · ${image.width}x${image.height} · " +
                "%.1f s".format(result.millis / 1000.0)
        }
    }

    PageScaffold("Image generation", "A prompt, and whatever this machine can draw it with") {
        Column(Modifier.verticalScroll(rememberScrollState())) {

            SectionHeader("PROMPT")
            Card {
                Column(Modifier.padding(14.dp)) {
                    OutlinedTextField(
                        value = prompt,
                        onValueChange = { prompt = it },
                        placeholder = { Text("a red circle", fontSize = 13.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = false,
                        enabled = !busy,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = { run() }, enabled = !busy) {
                            Text(if (busy) "Generating…" else "Generate")
                        }
                        if (busy) {
                            Spacer(Modifier.width(14.dp))
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text(stage, fontSize = 12.sp, color = colors.faint)
                        }
                    }
                }
            }

            SectionHeader("ENGINE")
            Card {
                if (engines.isEmpty()) {
                    Text(
                        "No image generator is registered in this build.",
                        fontSize = 12.sp, color = colors.faint,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                engines.forEachIndexed { index, engine ->
                    if (index > 0) Hairline()
                    val availability = engine.availability()
                    val ready = availability is ImageGenerator.Availability.Ready
                    // The effective selection: what the user picked, or what preferred() would fall
                    // back to. Showing nothing selected when a generation would in fact use Nora
                    // would be lying about what the button does.
                    val active = if (selected.isNotBlank()) {
                        selected == engine.id
                    } else {
                        ImageGeneration.preferred()?.id == engine.id
                    }

                    Row(
                        Modifier.fillMaxWidth()
                            .clickableRow {
                                if (!ready) return@clickableRow
                                selected = engine.id
                                PrismSettings.setImageEngineId(engine.id)
                            }
                            .alphaIf(ready)
                            .padding(horizontal = 16.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (active) Icons.Filled.RadioButtonChecked
                            else Icons.Filled.RadioButtonUnchecked,
                            null,
                            tint = if (active) colors.accent else colors.faint,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(11.dp))
                        Column {
                            Text(
                                engine.label,
                                fontSize = 14.sp,
                                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                            )
                            Text(engine.description, fontSize = 11.sp, color = colors.faint)
                            if (availability is ImageGenerator.Availability.Unavailable) {
                                Text(
                                    availability.reason,
                                    fontSize = 11.sp,
                                    color = colors.accent,
                                    modifier = Modifier.padding(top = 3.dp),
                                )
                            }
                        }
                    }
                }
            }

            status?.let {
                SectionFooter(it)
            }

            if (preview != null) {
                SectionHeader("RESULT")
                Card {
                    Box(
                        Modifier.fillMaxWidth().heightIn(min = 240.dp).padding(14.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(
                            preview!!,
                            null,
                            // FillBounds would stretch a square generation into the card. Fit keeps
                            // the aspect ratio, which for an image of an object is the difference
                            // between a picture and a smear.
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .size(320.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(colors.background),
                        )
                    }
                    savedTo?.let {
                        Hairline()
                        InfoRow("Written to", it, mono = true)
                    }
                }
            }

            SectionFooter(
                "Generated images are written to " + NoraConfig.outputDir().absolutePath + ". " +
                    "Nora's route needs a trained connectome — train one on the Training page. The " +
                    "cloud route needs a model configured on the Cloud AI page."
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}
