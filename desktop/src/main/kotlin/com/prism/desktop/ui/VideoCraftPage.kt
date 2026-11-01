package com.prism.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prism.core.PrismPlatform
import com.prism.desktop.videocraft.AviPlayback
import com.prism.desktop.videocraft.DesktopVideoCraftRuns
import com.prism.launcher.PrismSettings
import com.prism.launcher.videocraft.VideoCraftModels
import com.prism.launcher.videocraft.VideoCraftProject
import com.prism.launcher.videocraft.VideoCraftStore
import com.prism.launcher.videocraft.VideoCraftVoices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.io.File
import kotlin.math.roundToInt

/**
 * A magic wand, drawn rather than imported.
 *
 * material-icons-core has no wand -- it ships in the multi-megabyte extended set -- and pulling
 * that in for one glyph is not a trade worth making. This is the same geometry as the app's
 * `ic_wand_24.xml`, so both platforms show the same Create button.
 */
private val WandIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Wand",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).addPath(
        // The same SVG path data as the app's ic_wand_24.xml: two stars, a sparkle and the wand.
        pathData = addPathNodes(
            "M7.5,5.6L5,7 6.4,4.5 5,2 7.5,3.4 10,2 8.6,4.5 10,7z " +
                "M19.5,15.4L22,17 19.5,18.4 18,21 16.6,18.4 14,17 16.6,15.6 18,13z " +
                "M22,4l-3,3 -3,-3 3,-3z " +
                "M3.34,20.66l14.32,-14.32 -1.98,-1.98L1.36,18.68z",
        ),
        fill = SolidColor(Color.White),
    ).build()
}

/**
 * VideoCraft on desktop: the library first, the creator second.
 *
 * The page is gated on all four model families and a speech engine, because a film needs the text
 * model to plan it, the image model to draw keyframes, the interpolator to fill between them and the
 * audio model to score it. The gate is not a modal: the library and the reason it cannot make a new
 * film are both visible, so a missing model is a settings problem the user can see rather than a
 * button that silently does nothing.
 */
@Composable
fun VideoCraftPage() {
    var projects by remember { mutableStateOf(VideoCraftStore.list()) }
    var creator by remember { mutableStateOf<VideoCraftProject?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    val run by DesktopVideoCraftRuns.state.collectAsState()

    // A render changes the project file after every clip; when it stops, or finishes, the library
    // has to show the new progress and output without the user leaving the page.
    LaunchedEffect(run.running, run.complete, run.error) {
        projects = VideoCraftStore.list()
    }

    val current = creator
    if (current == null) {
        VideoCraftLibrary(
            projects = projects,
            run = run,
            status = status,
            onCreate = {
                // No speech model is required to REACH the creator: a score-only film needs none,
                // and the creator's dialogue switch decides the rest.
                val blocker = VideoCraftModels.blockingReason(needsSpeech = false)
                if (blocker != null) {
                    status = blocker
                } else {
                    status = null
                    creator = VideoCraftProject.fromSettings()
                }
            },
            onOpen = { project ->
                val loaded = VideoCraftStore.load(project.id) ?: project
                if (loaded.complete) {
                    val opened = openInPlayer(loaded.outputPath)
                    status = if (opened) null else "The film is not where the project expects it: ${loaded.outputPath}"
                } else {
                    creator = loaded
                }
            },
        )
        return
    }

    VideoCraftCreator(
        initial = current,
        run = run,
        onBack = {
            creator = null
            projects = VideoCraftStore.list()
        },
    )
}

@Composable
private fun VideoCraftLibrary(
    projects: List<VideoCraftProject>,
    run: DesktopVideoCraftRuns.State,
    status: String?,
    onCreate: () -> Unit,
    onOpen: (VideoCraftProject) -> Unit,
) {
    val colors = LocalPrismColors.current
    val blocker = VideoCraftModels.blockingReason(needsSpeech = false)
    val subtitle = when {
        blocker != null -> blocker
        projects.isEmpty() -> "Describe a film and VideoCraft will plan, draw, score and cut it"
        else -> "${projects.size} film${if (projects.size == 1) "" else "s"} · every required model is ready"
    }

    PageScaffold(
        title = "VideoCraft",
        subtitle = subtitle,
        actions = {
            Button(
                onClick = onCreate,
                enabled = blocker == null,
            ) {
                Icon(WandIcon, null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(7.dp))
                Text("Create")
            }
        },
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (blocker != null) {
                item { VideoCraftGate(blocker) }
            }
            if (status != null) {
                item {
                    Text(status, fontSize = 12.sp, color = colors.destructive)
                }
            }
            if (run.running) {
                item { RunningBanner(run) }
            }
            if (projects.isEmpty()) {
                item { EmptyLibrary() }
            } else {
                items(projects, key = { it.id }) { project ->
                    FilmRow(
                        project = project,
                        run = run,
                        onClick = { onOpen(project) },
                    )
                }
            }
        }
    }
}

@Composable
private fun VideoCraftGate(blocker: String) {
    val colors = LocalPrismColors.current
    Card {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Warning, null, tint = colors.destructive, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(9.dp))
                Text("VideoCraft needs its models", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "A film is planned by the text model, drawn by the image model, smoothed by the " +
                    "interpolator and scored by the audio model. Dialogue needs Kokoro or your own " +
                    "speech model. Set them on the Models page.",
                fontSize = 12.sp, color = colors.muted, lineHeight = 17.sp,
            )
            Spacer(Modifier.height(10.dp))
            GateLine("Text", VideoCraftModels.summaryText())
            GateLine("Image", VideoCraftModels.summary(VideoCraftModels.Kind.IMAGE))
            GateLine("Frame interpolation", VideoCraftModels.summary(VideoCraftModels.Kind.INTERPOLATION))
            GateLine("Audio generator", VideoCraftModels.summary(VideoCraftModels.Kind.AUDIO_GEN))
            GateLine("Speech", VideoCraftModels.summary(VideoCraftModels.Kind.SPEECH))
            Spacer(Modifier.height(8.dp))
            Text(blocker, fontSize = 12.sp, color = colors.destructive)
        }
    }
}

@Composable
private fun GateLine(label: String, value: String) {
    val colors = LocalPrismColors.current
    val ready = value != "None selected" && value != "Not installed"
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (ready) Icons.Filled.CheckCircle else Icons.Filled.Warning,
            null,
            tint = if (ready) colors.accent else colors.faint,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(7.dp))
        Text(label, fontSize = 12.sp, color = colors.muted, modifier = Modifier.width(136.dp))
        Text(value, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun RunningBanner(run: DesktopVideoCraftRuns.State) {
    val colors = LocalPrismColors.current
    Card {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (run.stage.isBlank()) "Rendering" else run.stage,
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "${(run.progress * 100).roundToInt()}% · the page can be closed and the render continues",
                    fontSize = 11.sp, color = colors.faint,
                )
            }
        }
    }
}

@Composable
private fun EmptyLibrary() {
    val colors = LocalPrismColors.current
    Column(
        Modifier.fillMaxWidth().padding(vertical = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Filled.Movie, null, tint = colors.divider, modifier = Modifier.size(52.dp))
        Spacer(Modifier.height(14.dp))
        Text("No films yet", fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(
            "Hit Create, describe a story, and the film appears here — unfinished ones can be resumed.",
            fontSize = 12.sp, color = colors.faint,
        )
    }
}

@Composable
private fun FilmRow(
    project: VideoCraftProject,
    run: DesktopVideoCraftRuns.State,
    onClick: () -> Unit,
) {
    val colors = LocalPrismColors.current
    val running = run.running && run.projectId == project.id
    val thumb = when {
        running && run.previewPath.isNotEmpty() -> run.previewPath
        project.lastFramePath.isNotEmpty() -> project.lastFramePath
        else -> ""
    }
    val statusText = when {
        running -> run.stage.ifBlank { "Rendering" }
        project.complete -> "Complete · ${project.width}×${project.height} · ${project.durationSeconds.roundToInt()}s"
        project.incomplete -> "Incomplete · ${project.completedClips}/${project.totalClips} clips · click to finish"
        else -> project.stage
    }
    Card {
        Row(
            Modifier.fillMaxWidth().clickableRow(onClick).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FileImage(
                path = thumb,
                modifier = Modifier.width(128.dp).height(72.dp)
                    .clip(RoundedCornerShape(colors.fieldRadius)).background(colors.surfaceRaised),
                token = if (running) run.progress else 0f,
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    project.prompt.ifBlank { "Untitled film" },
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    statusText,
                    fontSize = 11.sp,
                    color = when {
                        running -> colors.accent
                        project.complete -> colors.muted
                        else -> colors.faint
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (running) {
                    Spacer(Modifier.height(7.dp))
                    LinearProgressIndicator(
                        progress = { run.progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(0.7f),
                    )
                }
            }
            if (project.complete) {
                Icon(Icons.Filled.PlayArrow, null, tint = colors.accent, modifier = Modifier.size(22.dp))
            }
        }
    }
}

@Composable
private fun VideoCraftCreator(
    initial: VideoCraftProject,
    run: DesktopVideoCraftRuns.State,
    onBack: () -> Unit,
) {
    val colors = LocalPrismColors.current
    var draft by remember(initial.id) { mutableStateOf(Draft.from(initial)) }
    var status by remember(initial.id) { mutableStateOf<String?>(null) }
    val running = run.running && run.projectId == initial.id
    // With dialogue off the speech model is never opened, so it is not part of the gate.
    val blocker = VideoCraftModels.blockingReason(needsSpeech = !draft.noDialogue)
    val output = File(run.videoPath.ifEmpty { initial.outputPath })

    fun start() {
        if (running) return
        if (blocker != null) {
            status = blocker
            return
        }
        if (draft.prompt.isBlank()) {
            status = "Describe the film before creating it."
            return
        }
        draft.applyTo(initial)
        VideoCraftStore.save(initial)
        DesktopVideoCraftRuns.start(initial)
        status = null
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 28.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to films")
            }
            Spacer(Modifier.width(4.dp))
            Column(Modifier.weight(1f)) {
                Text("Video creator", fontSize = 25.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (running) run.stage else if (initial.complete) "Edit and render again" else "New film",
                    fontSize = 12.sp, color = if (running) colors.accent else colors.faint,
                )
            }
            Button(onClick = { start() }, enabled = !running && blocker == null) {
                Icon(WandIcon, null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(7.dp))
                Text(if (running) "Rendering…" else "Create")
            }
        }
        if (status != null || blocker != null) {
            Text(
                status ?: blocker.orEmpty(),
                fontSize = 12.sp,
                color = colors.destructive,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        if (run.error != null && run.projectId == initial.id) {
            Text(run.error, fontSize = 12.sp, color = colors.destructive, modifier = Modifier.padding(bottom = 8.dp))
        }

        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val wide = maxWidth >= 880.dp
            if (wide) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    Column(
                        Modifier.weight(1f).verticalScroll(rememberScrollState())
                            .padding(bottom = 24.dp)
                    ) {
                        CreatorForm(draft, onChange = { draft = it }, running = running)
                    }
                    Column(
                        Modifier.width(340.dp).verticalScroll(rememberScrollState())
                            .padding(bottom = 24.dp)
                    ) {
                        PreviewPane(
                            draft = draft,
                            project = initial,
                            run = run,
                            running = running,
                            output = output,
                            onPlay = {
                                if (!openInPlayer(output.absolutePath)) {
                                    status = "Could not open ${output.absolutePath}"
                                }
                            },
                        )
                    }
                }
            } else {
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                        .padding(bottom = 24.dp)
                ) {
                    PreviewPane(
                        draft = draft,
                        project = initial,
                        run = run,
                        running = running,
                        output = output,
                        onPlay = {
                            if (!openInPlayer(output.absolutePath)) {
                                status = "Could not open ${output.absolutePath}"
                            }
                        },
                    )
                    Spacer(Modifier.height(10.dp))
                    CreatorForm(draft, onChange = { draft = it }, running = running)
                }
            }
        }
    }
}

@Composable
private fun PreviewPane(
    draft: Draft,
    project: VideoCraftProject,
    run: DesktopVideoCraftRuns.State,
    running: Boolean,
    output: File,
    onPlay: () -> Unit,
) {
    val colors = LocalPrismColors.current
    val previewPath = when {
        running && run.previewPath.isNotEmpty() -> run.previewPath
        project.lastFramePath.isNotEmpty() -> project.lastFramePath
        else -> ""
    }

    // ── INLINE PLAYBACK ────────────────────────────────────────────────────
    //
    // The film plays in this box. The container is a Moving-JPEG AVI, so the frames are JPEGs and the
    // soundtrack is 16-bit PCM: both are formats the JVM reads already, and the alternative -- Compose
    // Desktop has no video surface, so playing the file would mean either a JavaFX media module or
    // handing it to the system player and losing the picture from the page -- is a dependency and a
    // worse experience than a container reader.
    val filmKey = output.absolutePath + "@" + output.length()
    var playing by remember(filmKey) { mutableStateOf(false) }
    var playback by remember(filmKey) { mutableStateOf<AviPlayback?>(null) }
    var frameIndex by remember(filmKey) { mutableStateOf(0) }
    var frameBitmap by remember(filmKey) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(filmKey) {
        playback = if (output.isFile && output.length() > 0) {
            withContext(Dispatchers.IO) { AviPlayback.open(output) }
        } else null
    }

    LaunchedEffect(playing, playback) {
        val film = playback ?: return@LaunchedEffect
        if (!playing) return@LaunchedEffect
        film.startAudio()
        var index = frameIndex.coerceIn(0, film.frameCount - 1)
        // A monotonic clock rather than `delay(frameInterval)`: a frame that takes longer to decode than
        // its slot drops instead of pushing every later frame -- and the sound -- further behind.
        var next = System.nanoTime()
        val intervalMicros = 1_000_000L / film.fps
        try {
            while (true) {
                val image = withContext(Dispatchers.IO) { film.frame(index) }
                if (image != null) frameBitmap = image.toComposeBitmap()
                frameIndex = index
                index = if (index + 1 >= film.frameCount) 0 else index + 1
                next += intervalMicros
                val waitMicros = next - System.nanoTime()
                if (waitMicros > 0) delay(waitMicros / 1000) else next = System.nanoTime()
            }
        } finally {
            film.stopAudio()
        }
    }

    DisposableEffect(filmKey) {
        onDispose {
            playing = false
            playback?.close()
            playback = null
        }
    }

    Card {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Preview", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(
                    "${draft.width.toIntOrNull() ?: project.width}×${draft.height.toIntOrNull() ?: project.height}",
                    fontSize = 11.sp, color = colors.faint,
                )
            }
            Spacer(Modifier.height(9.dp))
            Box(
                Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(colors.fieldRadius))
                    .background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                val displayed = if (playing) frameBitmap else null
                if (displayed != null) {
                    Image(
                        bitmap = displayed,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                } else if (previewPath.isNotEmpty()) {
                    FileImage(
                        path = previewPath,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        token = if (running) run.progress else 0f,
                    )
                } else {
                    Text("The last generated frame appears here", fontSize = 11.sp, color = colors.faint)
                }
                if (running) {
                    Column(
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                            .background(Color.Black.copy(alpha = 0.62f)).padding(10.dp)
                    ) {
                        Text(
                            run.stage.ifBlank { "Rendering" },
                            fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(5.dp))
                        LinearProgressIndicator(
                            progress = { run.progress.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            if (output.isFile && output.length() > 0) {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Movie, null, tint = colors.accent, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Film ready", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            output.name + " · ${"%.1f".format(output.length() / 1048576.0)} MB",
                            fontSize = 11.sp, color = colors.faint,
                        )
                    }
                    if (playback != null) {
                        Button(onClick = { playing = !playing }) {
                            Icon(Icons.Filled.PlayArrow, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            val film = playback
                            Text(if (playing) "Pause" else "Play")
                            if (film != null && !playing) {
                                Text(
                                    " · ${(frameIndex + 1).coerceAtMost(film.frameCount)}/${film.frameCount}",
                                    fontSize = 11.sp,
                                )
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                    TextButton(onClick = onPlay) { Text("Open in player") }
                }
            }

            Spacer(Modifier.height(12.dp))
            Hairline()
            Spacer(Modifier.height(10.dp))
            Text("MODELS THIS FILM USES", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = colors.faint)
            Spacer(Modifier.height(5.dp))
            PreviewModelLine("Text", VideoCraftModels.summaryText())
            PreviewModelLine("Image", VideoCraftModels.summary(VideoCraftModels.Kind.IMAGE))
            PreviewModelLine("Interpolation", VideoCraftModels.summary(VideoCraftModels.Kind.INTERPOLATION))
            PreviewModelLine("Audio", VideoCraftModels.summary(VideoCraftModels.Kind.AUDIO_GEN))
            PreviewModelLine("Speech", VideoCraftModels.summary(VideoCraftModels.Kind.SPEECH))
        }
    }
}

@Composable
private fun PreviewModelLine(label: String, value: String) {
    val colors = LocalPrismColors.current
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, fontSize = 11.sp, color = colors.faint, modifier = Modifier.width(84.dp))
        Text(value, fontSize = 11.sp, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun CreatorForm(draft: Draft, onChange: (Draft) -> Unit, running: Boolean) {
    var showVoices by remember { mutableStateOf(false) }
    if (showVoices) {
        VoicePickerDialog(
            selected = draft.voices,
            onDismiss = { showVoices = false },
            onUse = { picked ->
                // Everything ticked is stored as nothing ticked: same meaning, and a voice added
                // to the catalogue later stays eligible without another visit here.
                val all = VideoCraftVoices.catalogue().map { it.id }
                onChange(draft.copy(voices = if (picked.size == all.size) emptyList() else picked))
                showVoices = false
            },
        )
    }
    val colors = LocalPrismColors.current

    SectionHeader("STORY")
    Card {
        Column(Modifier.padding(14.dp)) {
            OutlinedTextField(
                value = draft.prompt,
                onValueChange = { onChange(draft.copy(prompt = it)) },
                label = { Text("Prompt") },
                placeholder = { Text("A traveller crosses a flooded valley at dusk…") },
                minLines = 3,
                maxLines = 7,
                enabled = !running,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = draft.style,
                onValueChange = { onChange(draft.copy(style = it)) },
                label = { Text("Style (optional)") },
                placeholder = { Text("35mm, desaturated teal and amber, long lenses") },
                enabled = !running,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    SectionHeader("FILM")
    Card {
        Column(Modifier.padding(14.dp)) {
            FieldRow("Duration (seconds)", draft.duration) { onChange(draft.copy(duration = it)) }
            FieldRow("Width", draft.width) { onChange(draft.copy(width = it)) }
            FieldRow("Height", draft.height) { onChange(draft.copy(height = it)) }
            FieldRow("Frame rate", draft.fps) { onChange(draft.copy(fps = it)) }
            FieldRow("Seed", draft.seed) { onChange(draft.copy(seed = it)) }
            Text(
                "Width and height are the resolution of every generated keyframe and frame.",
                fontSize = 11.sp, color = colors.faint, modifier = Modifier.padding(top = 4.dp),
            )
        }
    }

    SectionHeader("IMAGE GENERATOR")
    Card {
        Column(Modifier.padding(14.dp)) {
            FieldRow("Keyframes per second", draft.keyframes) { onChange(draft.copy(keyframes = it)) }
            FieldRow("Img2img denoise", draft.denoise) { onChange(draft.copy(denoise = it)) }
            FieldRow("CLIP match threshold", draft.threshold) { onChange(draft.copy(threshold = it)) }
            FieldRow("Diffusion steps", draft.steps) { onChange(draft.copy(steps = it)) }
            FieldRow("Guidance", draft.guidance) { onChange(draft.copy(guidance = it)) }
            Spacer(Modifier.height(8.dp))
            ToggleRow(
                title = "Experimental — cut characters into keyframes",
                detail = "On: each cast member's reference is matted to a cutout and only the characters " +
                    "a shot calls for are fed to the image model through img2img, so they keep their look. " +
                    "Off: characters are named in the prompt and cast as reference stills the shot " +
                    "interpolates between.",
                checked = draft.characterInjection,
                enabled = !running,
                onChange = { onChange(draft.copy(characterInjection = it)) },
            )
            Spacer(Modifier.height(8.dp))
            ToggleRow(
                title = "Adaptive keyframes and clips",
                detail = "Static shots get 1 keyframe/sec, slow 2, moderate 4, fast 7. " +
                    "Turning this off enables the two manual inputs below.",
                checked = draft.adaptive,
                enabled = !running,
                onChange = { onChange(draft.copy(adaptive = it)) },
            )
            FieldRow("Manual keyframes per clip", draft.manualKeyframes, enabled = !running && !draft.adaptive) {
                onChange(draft.copy(manualKeyframes = it))
            }
            FieldRow("Manual clips per scene", draft.manualClips, enabled = !running && !draft.adaptive) {
                onChange(draft.copy(manualClips = it))
            }
        }
    }

    SectionHeader("TEXT MODEL")
    Card {
        Column(Modifier.padding(14.dp)) {
            FieldRow("Scenes (maximum)", draft.maxScenes) { onChange(draft.copy(maxScenes = it)) }
            FieldRow("Temperature", draft.temperature) { onChange(draft.copy(temperature = it)) }
            Text(
                "The text model writes the story bible once, then each scene's shot list as it is reached. " +
                    "Replies are parsed as JSON; a malformed reply is repaired once, then replaced by a " +
                    "deterministic plan.",
                fontSize = 11.sp, color = colors.faint, lineHeight = 15.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }

    SectionHeader("AUDIO")
    Card {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Dialogue grouping", fontSize = 14.sp)
                    Text("Per shot is the default; per scene batches the lines into one utterance.",
                        fontSize = 11.sp, color = colors.faint)
                }
                var open by remember { mutableStateOf(false) }
                Box {
                    OutlinedButton(onClick = { open = true }, enabled = !running) {
                        Text(if (draft.audioScope == PrismSettings.AUDIO_SCOPE_SCENE) "Per scene" else "Per shot")
                    }
                    DropdownMenu(open, onDismissRequest = { open = false }) {
                        DropdownMenuItem(
                            text = { Text("Per shot") },
                            onClick = {
                                open = false
                                onChange(draft.copy(audioScope = PrismSettings.AUDIO_SCOPE_SHOT))
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Per scene") },
                            onClick = {
                                open = false
                                onChange(draft.copy(audioScope = PrismSettings.AUDIO_SCOPE_SCENE))
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            VolumeSlider("Dialogue", draft.dialogue) { onChange(draft.copy(dialogue = it)) }
            VolumeSlider("Music", draft.music) { onChange(draft.copy(music = it)) }
            VolumeSlider("Ambience", draft.ambience) { onChange(draft.copy(ambience = it)) }
            Spacer(Modifier.height(6.dp))
            ToggleRow(
                title = "No dialogue — score only",
                detail = "The script is written without spoken lines and the speech model is never " +
                    "loaded. Music and ambience carry the film.",
                checked = draft.noDialogue,
                enabled = !running,
                onChange = { onChange(draft.copy(noDialogue = it)) },
            )
            ToggleRow(
                title = "Pick voices automatically",
                detail = if (draft.noDialogue) {
                    "Unavailable while dialogue is off."
                } else if (VideoCraftVoices.kokoroActive()) {
                    "Each character is cast from their gender, nationality, language and ethnicity."
                } else {
                    "This speech model casts by gender only, using its own voice or a cloned one."
                },
                checked = draft.autoVoices,
                enabled = !running && !draft.noDialogue,
                onChange = { onChange(draft.copy(autoVoices = it)) },
            )
            // The picker belongs to manual casting, so it only appears once automatic casting is
            // off. With dialogue off it stays visible but greyed, because the reason it cannot be
            // used is the switch above it, not a missing feature.
            if (!draft.autoVoices || draft.noDialogue) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
                        .alphaIf(!draft.noDialogue && !running),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        onClick = { showVoices = true },
                        enabled = !running && !draft.noDialogue,
                    ) { Text("Choose voices…") }
                }
            }
            Text(
                when {
                    draft.noDialogue -> "No speech is generated."
                    draft.autoVoices -> "Casting is automatic; tick the box above to choose the pool."
                    draft.voices.isEmpty() -> "Every voice is eligible."
                    else -> "${draft.voices.size} voice(s) eligible."
                },
                fontSize = 11.sp, color = colors.faint,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
            Text(
                "Dialogue is spoken by Kokoro or your imported speech model. Ambience is per scene; " +
                    "music continues from the previous scene's tail; layers crossfade at shot boundaries.",
                fontSize = 11.sp, color = colors.faint, lineHeight = 15.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }

    SectionHeader("FRAME INTERPOLATION")
    Card {
        Column(Modifier.padding(16.dp)) {
            Text(
                "${VideoCraftModels.summary(VideoCraftModels.Kind.INTERPOLATION)} fills the gaps " +
                    "between keyframes. Imported ONNX interpolators are used when their input " +
                    "signature can be understood; otherwise Prism Flow's motion-compensated " +
                    "interpolation is used and the render says so.",
                fontSize = 12.sp, color = colors.muted, lineHeight = 17.sp,
            )
            Spacer(Modifier.height(6.dp))
            FieldRow(
                label = "Flow quality (1–3)",
                value = draft.flowQuality,
                enabled = !running,
            ) { onChange(draft.copy(flowQuality = it.filter { c -> c.isDigit() }.take(1))) }
            Text(
                "1 searches a coarse flow field quickly; 3 samples finer and searches further, " +
                    "which holds up better on fast motion and costs more per frame.",
                fontSize = 11.sp, color = colors.faint, lineHeight = 15.sp,
            )
        }
    }
}

/**
 * The voice picker.
 *
 * A CHECKBOX PER VOICE, mirroring the phone: a film has several characters, so the dialog says
 * which voices this cast may draw from rather than picking one voice for the whole film.
 */
@Composable
private fun VoicePickerDialog(
    selected: List<String>,
    onDismiss: () -> Unit,
    onUse: (List<String>) -> Unit,
) {
    val colors = LocalPrismColors.current
    val choices = remember { VideoCraftVoices.catalogue() }
    val everything = selected.isEmpty()
    val picked = remember(selected) {
        mutableStateListOf<String>().apply {
            addAll(choices.filter { everything || it.id in selected }.map { it.id })
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        // Material's dialog defaults do not follow this app's palette; a user theme's card,
        // accent and text colours are applied explicitly so the popup does not look like it
        // belongs to a different application.
        containerColor = colors.surface,
        titleContentColor = colors.onSurface,
        textContentColor = colors.muted,
        iconContentColor = colors.accent,
        title = { Text("Voices", color = colors.onSurface, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                choices.forEach { choice ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = choice.id in picked,
                            colors = CheckboxDefaults.colors(
                                checkedColor = colors.accent,
                                checkmarkColor = colors.background,
                                uncheckedColor = colors.muted,
                            ),
                            onCheckedChange = { on ->
                                if (on) picked.add(choice.id) else picked.remove(choice.id)
                            },
                        )
                        Text(choice.label, fontSize = 13.sp, color = colors.onSurface)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (picked.isNotEmpty()) onUse(picked.toList()) },
                enabled = picked.isNotEmpty(),
                colors = ButtonDefaults.textButtonColors(
                    contentColor = colors.accent,
                    disabledContentColor = colors.faint,
                ),
            ) { Text("Use voices") }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = colors.muted),
            ) { Text("Cancel") }
        },
    )
}

@Composable
private fun FieldRow(
    label: String,
    value: String,
    enabled: Boolean = true,
    onChange: (String) -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            fontSize = 13.sp,
            color = LocalPrismColors.current.muted,
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            enabled = enabled,
            modifier = Modifier.width(118.dp),
        )
    }
}

@Composable
private fun VolumeSlider(label: String, value: Float, onChange: (Float) -> Unit) {
    val colors = LocalPrismColors.current
    Row(Modifier.fillMaxWidth().padding(top = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 13.sp, color = colors.muted, modifier = Modifier.width(82.dp))
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = 0f..1f,
            modifier = Modifier.weight(1f),
        )
        Text("%.2f".format(value), fontSize = 11.sp, color = colors.faint, modifier = Modifier.width(38.dp))
    }
}

@Composable
private fun FileImage(
    path: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    token: Float = 0f,
) {
    var bitmap by remember(path, token) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(path, token) {
        bitmap = if (path.isBlank()) null else withContext(Dispatchers.IO) {
            runCatching {
                val file = File(path)
                if (!file.isFile) null
                else PrismPlatform.images.decode(file, 960)?.toComposeBitmap()
            }.getOrNull()
        }
    }
    val image = bitmap
    if (image != null) {
        Image(bitmap = image, contentDescription = null, modifier = modifier, contentScale = contentScale)
    } else {
        Box(modifier, contentAlignment = Alignment.Center) {
            Icon(
                Icons.Filled.Movie,
                null,
                tint = LocalPrismColors.current.divider,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

private fun openInPlayer(path: String): Boolean {
    if (path.isBlank()) return false
    val file = File(path)
    if (!file.isFile) return false
    return runCatching {
        if (!Desktop.isDesktopSupported()) return false
        val desktop = Desktop.getDesktop()
        if (!desktop.isSupported(Desktop.Action.OPEN)) return false
        desktop.open(file)
        true
    }.getOrDefault(false)
}

/**
 * The creator's editable inputs.
 *
 * Strings rather than numbers because an [OutlinedTextField] must be allowed to hold a half-typed
 * value; the conversion happens once, when Create is pressed.
 */
private data class Draft(
    val prompt: String,
    val style: String,
    val duration: String,
    val width: String,
    val height: String,
    val fps: String,
    val keyframes: String,
    val denoise: String,
    val threshold: String,
    val adaptive: Boolean,
    val manualKeyframes: String,
    val manualClips: String,
    val steps: String,
    val guidance: String,
    val flowQuality: String,
    val seed: String,
    val temperature: String,
    val maxScenes: String,
    val audioScope: String,
    val dialogue: Float,
    val music: Float,
    val ambience: Float,
    val noDialogue: Boolean = false,
    val autoVoices: Boolean = true,
    val characterInjection: Boolean = false,
    val voices: List<String> = emptyList(),
) {
    fun applyTo(project: VideoCraftProject) {
        project.prompt = prompt.trim()
        project.style = style.trim()
        project.durationSeconds = duration.toFloatOrNull()?.coerceIn(2f, 600f) ?: project.durationSeconds
        project.width = width.toIntOrNull()?.coerceIn(128, 2160) ?: project.width
        project.height = height.toIntOrNull()?.coerceIn(128, 2160) ?: project.height
        project.fps = fps.toIntOrNull()?.coerceIn(8, 60) ?: project.fps
        project.keyframesPerSecond = keyframes.toFloatOrNull()?.coerceIn(0.5f, 12f)
            ?: project.keyframesPerSecond
        project.denoise = denoise.toFloatOrNull()?.coerceIn(0.05f, 0.95f) ?: project.denoise
        project.clipThreshold = threshold.toFloatOrNull()?.coerceIn(0f, 1f) ?: project.clipThreshold
        project.adaptive = adaptive
        project.manualKeyframesPerClip = manualKeyframes.toIntOrNull()?.coerceIn(1, 12)
            ?: project.manualKeyframesPerClip
        project.manualClipsPerScene = manualClips.toIntOrNull()?.coerceIn(1, 12)
            ?: project.manualClipsPerScene
        project.imageSteps = steps.toIntOrNull()?.coerceIn(1, 150) ?: project.imageSteps
        project.imageGuidance = guidance.toFloatOrNull()?.coerceIn(0f, 30f) ?: project.imageGuidance
        project.interpolationQuality = flowQuality.toIntOrNull()?.coerceIn(1, 3)
            ?: project.interpolationQuality
        project.seed = seed.toLongOrNull() ?: project.seed
        project.temperature = temperature.toFloatOrNull()?.coerceIn(0f, 2f) ?: project.temperature
        project.maxScenes = maxScenes.toIntOrNull()?.coerceIn(1, 24) ?: project.maxScenes
        project.audioScope = audioScope
        project.noDialogue = noDialogue
        project.autoVoices = autoVoices
        PrismSettings.setVideoCraftCharacterInjection(characterInjection)
        project.voiceIds.clear()
        project.voiceIds.addAll(voices)
        project.dialogueVolume = dialogue
        project.musicVolume = music
        project.ambienceVolume = ambience

        PrismSettings.setVideoCraftWidth(project.width)
        PrismSettings.setVideoCraftHeight(project.height)
        PrismSettings.setVideoCraftDurationSeconds(project.durationSeconds.roundToInt())
        PrismSettings.setVideoCraftFps(project.fps)
        PrismSettings.setVideoCraftKeyframesPerSecond(project.keyframesPerSecond)
        PrismSettings.setVideoCraftDenoise(project.denoise)
        PrismSettings.setVideoCraftClipThreshold(project.clipThreshold)
        PrismSettings.setVideoCraftAdaptive(project.adaptive)
        PrismSettings.setVideoCraftManualKeyframes(project.manualKeyframesPerClip)
        PrismSettings.setVideoCraftManualClips(project.manualClipsPerScene)
        PrismSettings.setVideoCraftAudioScope(project.audioScope)
        PrismSettings.setVideoCraftSeed(project.seed)
        PrismSettings.setVideoCraftInterpolationQuality(project.interpolationQuality)
        PrismSettings.setVideoCraftTemperature(project.temperature)
        PrismSettings.setVideoCraftMaxScenes(project.maxScenes)
    }

    companion object {
        fun from(project: VideoCraftProject) = Draft(
            prompt = project.prompt,
            style = project.style,
            duration = project.durationSeconds.roundToInt().toString(),
            width = project.width.toString(),
            height = project.height.toString(),
            fps = project.fps.toString(),
            keyframes = project.keyframesPerSecond.toString(),
            denoise = project.denoise.toString(),
            threshold = project.clipThreshold.toString(),
            adaptive = project.adaptive,
            manualKeyframes = project.manualKeyframesPerClip.toString(),
            manualClips = project.manualClipsPerScene.toString(),
            steps = project.imageSteps.toString(),
            guidance = project.imageGuidance.toString(),
            flowQuality = project.interpolationQuality.toString(),
            seed = project.seed.toString(),
            temperature = project.temperature.toString(),
            maxScenes = project.maxScenes.toString(),
            audioScope = project.audioScope,
            dialogue = project.dialogueVolume,
            music = project.musicVolume,
            ambience = project.ambienceVolume,
            noDialogue = project.noDialogue,
            autoVoices = project.autoVoices,
            characterInjection = PrismSettings.getVideoCraftCharacterInjection(),
            voices = project.voiceIds.toList(),
        )
    }
}
