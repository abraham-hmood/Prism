package com.prism.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prism.desktop.aether.DesktopAether
import com.prism.launcher.PrismSettings
import com.prism.launcher.aether.AetherConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Aether, the second on-device brain. PHASE 76.
 *
 * ## Why Aether has its own page rather than sharing Nora's
 *
 * They are different animals. Nora is a predictive-coding hierarchy that generates by imagining and
 * correcting; Aether is a spiking network that reads with a retina and answers by moving. Their
 * settings do not overlap, their training does not overlap, and folding one into the other's page
 * would mean a screen of controls half of which apply.
 *
 * ## The four routes, and why they are four
 *
 * Not four styles of the same thing. AUTO-REGRESSION answers in words. HALLUCINATION dreams a clip
 * where each frame comes from the last rather than from the prompt. DEEP EXPOSURE is one long gaze with
 * the prompt taken away partway through, so the picture is what the brain keeps rather than what it was
 * shown. SACCADIC DRAWING moves an eye and marks where it lands. They produce different kinds of output
 * from the same connectome, which is the point of a brain rather than a model.
 */
@Composable
fun AetherPage() {
    val colors = LocalPrismColors.current
    val scope = rememberCoroutineScope()

    var prompt by remember { mutableStateOf("") }
    var answer by remember { mutableStateOf("") }
    var picture by remember { mutableStateOf<ImageBitmap?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf(DesktopAether.status) }
    var epochs by remember { mutableStateOf(1) }
    var log by remember { mutableStateOf(listOf<String>()) }

    LaunchedEffect(Unit) {
        while (true) {
            status = DesktopAether.status
            busy = DesktopAether.busy
            delay(500)
        }
    }

    PageScaffold("Aether", "A spiking brain that reads with an eye") {
        Column(Modifier.verticalScroll(rememberScrollState())) {

            SectionHeader("brain")
            Card {
                InfoRow("Geometry", AetherConfig.geometry.signature())
                Hairline()
                InfoRow("State", if (DesktopAether.isTrained()) "trained" else "untrained")
                Hairline()
                InfoRow("Weights", DesktopAether.weightsFile().absolutePath, mono = true)
                Hairline()
                InfoRow("Dataset", DesktopAether.datasetDir().absolutePath, mono = true)
                Hairline()
                InfoRow("Doing", status)
            }
            SectionFooter(
                "A desktop is a better home for this than a phone, for the same reason as the crawler: " +
                    "training is hours of arithmetic and a phone spends much of that suspended. The " +
                    "connectome format is shared, so a brain trained here can be carried to the phone."
            )

            SectionHeader("training")
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text("Epochs", fontSize = 14.sp)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        listOf(1, 5, 20, 100).forEach { count ->
                            val active = count == epochs
                            androidx.compose.material3.Surface(
                                color = if (active) colors.accent else Color(0xFF23232B),
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                                modifier = Modifier.padding(end = 6.dp),
                            ) {
                                Text(
                                    count.toString(),
                                    fontSize = 13.sp,
                                    color = if (active) Color.White else Color(0xFFB9B9C4),
                                    modifier = Modifier
                                        .clickableRow { epochs = count }
                                        .padding(horizontal = 12.dp, vertical = 7.dp),
                                )
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        Button(
                            enabled = !busy,
                            onClick = {
                                log = emptyList()
                                scope.launch {
                                    val result = DesktopAether.train(epochs) { progress ->
                                        log = (log + ("epoch " + progress.epoch + " · loss " + progress.loss))
                                            .takeLast(12)
                                    }
                                    answer = result
                                }
                            },
                        ) { Text(if (busy) "Busy…" else "Train") }
                    }
                    if (log.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        log.forEach {
                            Text(it, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = colors.faint)
                        }
                    }
                }
            }
            SectionFooter(
                "Training reads whatever is in the dataset folder. Text in it is drawn and LOOKED at " +
                    "rather than tokenised — Aether has no vocabulary, so a word reaches it as light."
            )

            SectionHeader("generate")
            Card {
                Column(Modifier.padding(16.dp)) {
                    OutlinedTextField(
                        value = prompt,
                        onValueChange = { prompt = it },
                        placeholder = { Text("Something to read", fontSize = 13.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 3,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(
                            enabled = !busy && prompt.isNotBlank(),
                            onClick = {
                                scope.launch {
                                    picture = null
                                    answer = DesktopAether.autoRegression(prompt.trim())
                                }
                            },
                        ) { Text("Answer", fontSize = 13.sp) }

                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(
                            enabled = !busy && prompt.isNotBlank(),
                            onClick = {
                                scope.launch {
                                    answer = ""
                                    val image = DesktopAether.saccadicDrawing(prompt.trim())
                                    picture = image?.toComposeBitmap()
                                    if (image == null) answer = "Nothing came back."
                                }
                            },
                        ) { Text("Draw", fontSize = 13.sp) }

                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(
                            enabled = !busy && prompt.isNotBlank(),
                            onClick = {
                                scope.launch {
                                    answer = ""
                                    val image = DesktopAether.deepExposure(prompt.trim())
                                    picture = image?.toComposeBitmap()
                                    if (image == null) answer = "Nothing came back."
                                }
                            },
                        ) { Text("Dream", fontSize = 13.sp) }

                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(
                            enabled = !busy && prompt.isNotBlank(),
                            onClick = {
                                scope.launch {
                                    answer = ""
                                    val frames = DesktopAether.hallucinationVideo(prompt.trim())
                                    picture = frames.lastOrNull()?.toComposeBitmap()
                                    answer = if (frames.isEmpty()) {
                                        "Nothing came back."
                                    } else {
                                        frames.size.toString() + " frames — the last one is shown."
                                    }
                                }
                            },
                        ) { Text("Hallucinate", fontSize = 13.sp) }

                        if (busy) {
                            Spacer(Modifier.width(12.dp))
                            CircularProgressIndicator(
                                color = colors.accent,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(15.dp),
                            )
                        }
                    }
                }
            }

            picture?.let { bitmap ->
                Spacer(Modifier.height(12.dp))
                Image(
                    bitmap = bitmap,
                    contentDescription = "What Aether drew",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.widthIn(max = 420.dp).heightIn(max = 420.dp),
                )
            }

            if (answer.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Card {
                    Text(
                        answer,
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }

            SectionHeader("forgetting")
            Card {
                NavRow(
                    "Erase the connectome",
                    "Everything Aether has learned. There is no undo and no copy — the weights file " +
                        "is the brain.",
                    destructive = true,
                    onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) { DesktopAether.forget() }
                            answer = "The connectome has been erased. Training starts from infancy."
                        }
                    },
                )
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}
