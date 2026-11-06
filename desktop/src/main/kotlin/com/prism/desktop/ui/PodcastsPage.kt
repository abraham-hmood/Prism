package com.prism.desktop.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prism.core.PrismPlatform
import com.prism.launcher.PrismSettings
import com.prism.launcher.characters.CharacterStore
import com.prism.launcher.podcasts.CastMember
import com.prism.launcher.podcasts.Episode
import com.prism.launcher.podcasts.Podcast
import com.prism.launcher.podcasts.PodcastCatalog
import com.prism.launcher.podcasts.PodcastPlayer
import com.prism.launcher.podcasts.PodcastStore
import com.prism.launcher.podcasts.PodcastStudio
import com.prism.launcher.speech.VoiceChoices
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Podcasts on the desktop: the same shows, the same creator and the same player as the phone, over
 * the same store -- so a show made on either, or shared between them, is one show.
 *
 * Under xOS it is Apple's Podcasts for the Mac: a grid of square artwork under a large title, the
 * Podcasts purple, a show page with its cover beside the play button, date-stamped episode rows with
 * round play buttons, and a player bar along the bottom. Pull down past the top of a show's page --
 * with a trackpad, a wheel or a drag -- to make its next episode, as on the phone.
 */
@Composable
fun PodcastsPage() {
    var screen by remember { mutableStateOf("library") }
    var openId by remember { mutableStateOf<String?>(null) }
    // The show the creator is editing, or null when it is making a new one.
    var editing by remember { mutableStateOf<Podcast?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    var player by remember { mutableStateOf(PodcastPlayer.state) }

    DisposableEffect(Unit) {
        val store: () -> Unit = { revision++ }
        val studio: () -> Unit = { revision++ }
        val playback: (PodcastPlayer.State) -> Unit = { player = it }
        PodcastStore.addListener(store)
        PodcastStudio.addListener(studio)
        PodcastPlayer.addListener(playback)
        onDispose {
            PodcastStore.removeListener(store)
            PodcastStudio.removeListener(studio)
            PodcastPlayer.removeListener(playback)
        }
    }

    val accent = podcastAccent()
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (screen) {
                "create" -> PodcastCreator(
                    accent,
                    existing = editing,
                    onCancel = { screen = if (editing != null && openId == editing?.id) "detail" else "library"; editing = null },
                    onCreated = { id -> openId = id; editing = null; screen = "detail" },
                )
                "detail" -> {
                    val show = remember(revision, openId) { openId?.let { PodcastStore.find(it) } }
                    if (show == null) screen = "library"
                    else PodcastDetail(show, accent, player, revision, onBack = { screen = "library" },
                        onEdit = { editing = show; screen = "create" })
                }
                else -> PodcastLibrary(
                    accent, revision,
                    onOpen = { openId = it; screen = "detail" },
                    onCreate = { editing = null; screen = "create" },
                    onEdit = { show -> editing = show; screen = "create" },
                )
            }
        }
        if (player.episodeId != null && screen != "create") {
            PlayerBar(player, accent) { player.podcastId?.let { openId = it; screen = "detail" } }
        }
    }
}

@Composable
private fun podcastAccent(): Color =
    if (LocalMacOsLook.current) Color(0xFF9D4EDD) else LocalPrismColors.current.accent

// ── Library ─────────────────────────────────────────────────────────────

@Composable
private fun PodcastLibrary(accent: Color, revision: Int, onOpen: (String) -> Unit, onCreate: () -> Unit, onEdit: (Podcast) -> Unit) {
    val colors = LocalPrismColors.current
    val shows = remember(revision) { PodcastStore.all() }
    val missing = remember(revision) { PodcastStudio.missingRequirements() }
    var warning by remember { mutableStateOf("") }

    Box(Modifier.fillMaxSize()) {
        PageScaffold("Podcasts", if (LocalMacOsLook.current) "Library" else "Shows made by your AI, and shared by your devices") {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 90.dp)) {
                if (missing.isNotEmpty()) {
                    Card {
                        Column(Modifier.padding(16.dp)) {
                            Text("Podcasts needs a text model and a voice", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            missing.forEach { Text(it, fontSize = 13.sp, color = colors.muted, modifier = Modifier.padding(top = 6.dp)) }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                }
                if (warning.isNotBlank()) {
                    Text(warning, fontSize = 12.sp, color = colors.destructive, modifier = Modifier.padding(bottom = 8.dp))
                }
                if (shows.isEmpty()) {
                    Column(Modifier.fillMaxWidth().padding(top = 60.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("🎙", fontSize = 52.sp)
                        Text("No Podcasts Yet", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                        Text(
                            "Click + to make a show. Your AI researches the news, blogs and social media on its topic, " +
                                "and your characters discuss it in their own voices. Shows made on your trusted devices appear here too.",
                            fontSize = 14.sp, color = colors.muted, textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 8.dp).width(460.dp),
                        )
                    }
                } else {
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val columns = (maxWidth / 190.dp).toInt().coerceIn(2, 7)
                        Column {
                            shows.chunked(columns).forEach { row ->
                                Row(Modifier.fillMaxWidth().padding(bottom = 20.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                                    row.forEach { show -> ShowTile(show, revision, Modifier.weight(1f), onEdit = { onEdit(show) }) { onOpen(show.id) } }
                                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                                }
                            }
                        }
                    }
                }
            }
        }
        // The floating + button, bottom right, as on the phone.
        Surface(
            color = accent,
            shape = CircleShape,
            shadowElevation = 8.dp,
            modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp).size(56.dp).alphaIf(missing.isEmpty())
                .clickableRow {
                    if (missing.isEmpty()) onCreate() else warning = missing.joinToString(" ")
                },
        ) {
            Box(contentAlignment = Alignment.Center) { Text("+", fontSize = 30.sp, color = Color.White) }
        }
    }
}

@Composable
private fun ShowTile(show: Podcast, revision: Int, modifier: Modifier, onEdit: () -> Unit, onClick: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val colors = LocalPrismColors.current
    // Read through the revision: the show itself is unchanged while it is being made, so without
    // this the tile would never redraw its progress.
    val status = remember(revision) { PodcastStudio.status(show.id) }
    // Right-click (the desktop's long press): Edit or Delete.
    Column(modifier.rightClickable { menu = true }.clickableRow(onClick)) {
        androidx.compose.material3.DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            androidx.compose.material3.DropdownMenuItem(text = { Text("Edit", fontSize = 13.sp) }, onClick = { menu = false; onEdit() })
            androidx.compose.material3.DropdownMenuItem(text = { Text("Delete", fontSize = 13.sp) }, onClick = {
                menu = false
                PodcastStudio.cancel(show.id)
                if (PodcastPlayer.state.podcastId == show.id) PodcastPlayer.stop()
                PodcastStore.delete(show.id)
            })
        }
        Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
            Cover(show, Modifier.fillMaxSize(), radius = 10)
            if (status != null) {
                Surface(
                    color = Color(0xCC000000), shape = RoundedCornerShape(9.dp),
                    modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
                ) {
                    Text(
                        when {
                            status.error != null -> "Failed"
                            status.queued -> "Queued"
                            else -> "Making… ${(status.fraction * 100).roundToInt()}%"
                        },
                        fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }
        Text(show.title, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 7.dp))
        Text(
            when {
                show.shared -> "From " + (show.originName ?: "a trusted device")
                show.episodes.isEmpty() -> "No episodes yet"
                else -> "Updated " + relative(show.episodes.maxOf { it.createdAt })
            },
            fontSize = 12.sp, color = colors.faint, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun Cover(show: Podcast, modifier: Modifier, radius: Int) {
    val colors = LocalPrismColors.current
    val file = PodcastStore.coverOf(show.id)
    // Keyed on the file, not on the page's revision: progress updates bump the revision several
    // times a second, and decoding the artwork again on each one is wasted work.
    val stamp = file.lastModified()
    val bitmap: ImageBitmap? = remember(file.path, stamp) {
        runCatching {
            file.takeIf { it.isFile }?.let { PrismPlatform.images.decode(it.readBytes(), 600)?.toComposeBitmap() }
        }.getOrNull()
    }
    Box(modifier.clip(RoundedCornerShape(radius.dp)).background(colors.surfaceRaised)) {
        if (bitmap != null) Image(bitmap, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

// ── Creator ─────────────────────────────────────────────────────────────

@Composable
private fun PodcastCreator(accent: Color, existing: Podcast?, onCancel: () -> Unit, onCreated: (String) -> Unit) {
    val colors = LocalPrismColors.current
    // Prefilled from [existing] when editing: every choice it was made with.
    var title by remember { mutableStateOf(existing?.title.orEmpty()) }
    var description by remember { mutableStateOf(existing?.description.orEmpty()) }
    var keywords by remember { mutableStateOf(existing?.keywords?.joinToString(", ").orEmpty()) }
    var category by remember { mutableStateOf(existing?.category ?: "Technology") }
    var autoCover by remember { mutableStateOf(existing?.autoCover ?: true) }
    var minutes by remember { mutableStateOf((existing?.minutes ?: PodcastCatalog.DEFAULT_MINUTES).toFloat()) }
    val choices = remember {
        mutableStateMapOf<String, String>().apply {
            PodcastCatalog.CHOICES.forEach { put(it.key, existing?.choices?.get(it.key) ?: it.default) }
        }
    }
    val toggles = remember {
        mutableStateMapOf<String, Boolean>().apply {
            PodcastCatalog.TOGGLES.forEach { put(it.key, existing?.toggles?.get(it.key) ?: it.default) }
        }
    }
    val selected = remember { mutableStateListOf<String>().apply { existing?.cast?.mapNotNull { it.characterId }?.let { addAll(it) } } }
    var characters by remember { mutableStateOf(CharacterStore.all()) }
    var makingCharacter by remember { mutableStateOf(false) }
    var editingCharacter by remember { mutableStateOf<CharacterStore.Character?>(null) }
    var problem by remember { mutableStateOf("") }
    val hasImageModel = remember { PrismSettings.hasImageGenerator() || PrismSettings.hasImageGeneratorForVideo() }

    fun create() {
        val missing = PodcastStudio.missingRequirements()
        problem = when {
            missing.isNotEmpty() -> missing.joinToString(" ")
            title.isBlank() -> "Give the podcast a title."
            selected.isEmpty() && existing?.cast.isNullOrEmpty() -> "Choose at least one character to host."
            else -> ""
        }
        if (problem.isNotEmpty()) return
        val byId = CharacterStore.all().associateBy { it.id }
        val now = System.currentTimeMillis()
        // Hosts that are not characters on this computer -- a show shared by another device -- stay as they were.
        val keptCast = existing?.cast?.filter { it.characterId == null || it.characterId !in byId }.orEmpty()
        val podcast = Podcast(
            id = existing?.id ?: PodcastStore.newId(),
            title = title.trim(),
            description = description.trim(),
            keywords = keywords.split(',', ';', '\n').map { it.trim() }.filter { it.isNotBlank() },
            category = category,
            choices = choices.toMap(),
            toggles = toggles.toMap(),
            minutes = minutes.roundToInt().coerceIn(PodcastCatalog.MIN_MINUTES, PodcastCatalog.MAX_MINUTES),
            autoCover = autoCover,
            cast = keptCast + selected.mapNotNull { byId[it] }.map { CastMember(it.id, it.name, it.description, it.voice) },
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
        )
        if (existing != null) PodcastStudio.update(podcast) else PodcastStudio.create(podcast)
        onCreated(podcast.id)
    }

    PageScaffold(if (existing != null) "Edit Podcast" else "New Podcast", "Every episode is researched and written by your text model, and spoken by your characters", actions = {
        OutlinedButton(onClick = onCancel) { Text("Cancel", fontSize = 13.sp) }
        Spacer(Modifier.width(8.dp))
        Button(onClick = { create() }, colors = ButtonDefaults.buttonColors(containerColor = accent)) { Text(if (existing != null) "Save" else "Create", fontSize = 13.sp) }
    }) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 40.dp).width(640.dp)) {
            if (problem.isNotBlank()) Text(problem, fontSize = 13.sp, color = colors.destructive, modifier = Modifier.padding(bottom = 8.dp))

            SectionHeader("Show")
            Card {
                Column(Modifier.padding(14.dp)) {
                    Label("Title"); CommittingField(title, modifier = Modifier.fillMaxWidth()) { title = it }
                    Spacer(Modifier.height(10.dp))
                    Label("Description"); CommittingField(description, modifier = Modifier.fillMaxWidth()) { description = it }
                    Spacer(Modifier.height(10.dp))
                    Label("Keywords, separated by commas"); CommittingField(keywords, modifier = Modifier.fillMaxWidth()) { keywords = it }
                    Spacer(Modifier.height(10.dp))
                    PrismDropdown("What it's about", category, PodcastCatalog.CATEGORIES, modifier = Modifier.fillMaxWidth()) { category = it }
                }
            }
            SectionFooter("The keywords and the topic are what every episode researches, and what the cover is found or drawn from.")

            SectionHeader("Artwork")
            Card {
                Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(autoCover, { autoCover = it }, colors = CheckboxDefaults.colors(checkedColor = accent))
                    Text(if (hasImageModel) "Generate a cover with your image model" else "Find a cover on the web", fontSize = 14.sp)
                }
            }
            SectionFooter(
                if (hasImageModel) "Your active image model draws the artwork from the title, topic and keywords."
                else "No image model is active, so Prism searches with your search engine, crawls the sites it finds, and picks the best-matching picture.",
            )

            SectionHeader("Episodes")
            Card {
                Column(Modifier.padding(14.dp)) {
                    Text("Average length: ${minutes.roundToInt()} min", fontSize = 14.sp)
                    Slider(
                        value = minutes,
                        onValueChange = { minutes = it.roundToInt().toFloat() },
                        valueRange = PodcastCatalog.MIN_MINUTES.toFloat()..PodcastCatalog.MAX_MINUTES.toFloat(),
                        steps = PodcastCatalog.MAX_MINUTES - PodcastCatalog.MIN_MINUTES - 1,
                        colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent),
                    )
                }
            }

            SectionHeader("Style")
            Card {
                Column(Modifier.padding(14.dp)) {
                    PodcastCatalog.CHOICES.chunked(2).forEach { pair ->
                        Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            pair.forEach { choice ->
                                PrismDropdown(choice.label, choices[choice.key] ?: choice.default, choice.options, modifier = Modifier.weight(1f)) {
                                    choices[choice.key] = it
                                }
                            }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }

            SectionHeader("Content")
            Card {
                PodcastCatalog.TOGGLES.forEachIndexed { index, toggle ->
                    if (index > 0) Hairline()
                    ToggleRow(toggle.label, toggle.detail, toggles[toggle.key] ?: toggle.default) { toggles[toggle.key] = it }
                }
            }

            SectionHeader("Hosts")
            Card {
                BoxWithConstraints(Modifier.fillMaxWidth().padding(12.dp)) {
                    val columns = (maxWidth / 110.dp).toInt().coerceAtLeast(3)
                    val cells: List<CharacterStore.Character?> = characters + listOf(null)
                    Column {
                        cells.chunked(columns).forEach { row ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                                row.forEach { character ->
                                    Box(Modifier.weight(1f), contentAlignment = Alignment.TopCenter) {
                                        if (character == null) {
                                            NewCharacterCell(accent) { makingCharacter = true }
                                        } else {
                                            HostCell(
                                                character, character.id in selected, accent,
                                                onToggle = { if (!selected.remove(character.id)) selected += character.id },
                                                onEdit = { editingCharacter = character },
                                            )
                                        }
                                    }
                                }
                                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
            }
            SectionFooter("Choose one or more of your characters to host. Each speaks in the voice set on them and forms their own opinions from their description. Right-click one to change their voice.")

            Spacer(Modifier.height(18.dp))
            Button(onClick = { create() }, colors = ButtonDefaults.buttonColors(containerColor = accent), modifier = Modifier.fillMaxWidth()) {
                Text(if (existing != null) "Save Changes" else "Create Podcast", fontSize = 14.sp)
            }
        }
    }

    if (makingCharacter || editingCharacter != null) {
        CharacterEditor(
            existing = editingCharacter,
            onDismiss = { makingCharacter = false; editingCharacter = null },
            onSaved = { saved ->
                CharacterStore.save(saved)
                if (editingCharacter == null && saved.id !in selected) selected += saved.id
                makingCharacter = false
                editingCharacter = null
                characters = CharacterStore.all()
            },
            onDeleted = { target ->
                CharacterStore.delete(target.id)
                selected.remove(target.id)
                makingCharacter = false
                editingCharacter = null
                characters = CharacterStore.all()
            },
        )
    }
}

@Composable
private fun Label(text: String) {
    Text(text, fontSize = 12.sp, color = LocalPrismColors.current.muted, modifier = Modifier.padding(bottom = 4.dp))
}

@Composable
private fun HostCell(character: CharacterStore.Character, chosen: Boolean, accent: Color, onToggle: () -> Unit, onEdit: () -> Unit) {
    val colors = LocalPrismColors.current
    val bitmap = remember(character.imagePath) {
        character.imagePath?.let { path ->
            runCatching { File(path).takeIf { it.isFile }?.let { PrismPlatform.images.decode(it.readBytes(), 200)?.toComposeBitmap() } }.getOrNull()
        }
    }
    Column(
        Modifier.width(100.dp).clickableRow(onToggle).rightClickable(onEdit),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(64.dp)) {
            Box(
                Modifier.size(64.dp).clip(CircleShape).background(colors.surfaceRaised)
                    .border(BorderStroke(if (chosen) 3.dp else 1.dp, if (chosen) accent else colors.divider), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (bitmap != null) Image(bitmap, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                else Text(character.name.take(1).uppercase(), fontSize = 24.sp, color = colors.muted)
            }
            if (chosen) {
                Surface(color = accent, shape = CircleShape, modifier = Modifier.align(Alignment.BottomEnd).size(22.dp)) {
                    Box(contentAlignment = Alignment.Center) { Text("✓", fontSize = 12.sp, color = Color.White) }
                }
            }
        }
        Text(character.name, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        Text(
            VoiceChoices.labelOf(character.voice).substringBefore(" (").substringBefore(" —"),
            fontSize = 11.sp, color = colors.faint, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun NewCharacterCell(accent: Color, onClick: () -> Unit) {
    Column(Modifier.width(100.dp).clickableRow(onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(64.dp).clip(CircleShape).background(accent.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            Text("+", fontSize = 28.sp, color = accent)
        }
        Text("New Character", fontSize = 13.sp, color = accent, modifier = Modifier.padding(top = 6.dp))
    }
}

// ── One show ────────────────────────────────────────────────────────────

@Composable
private fun PodcastDetail(show: Podcast, accent: Color, player: PodcastPlayer.State, revision: Int, onBack: () -> Unit, onEdit: () -> Unit) {
    val colors = LocalPrismColors.current
    val status = remember(revision) { PodcastStudio.status(show.id) }
    var note by remember { mutableStateOf("") }
    var pull by remember { mutableStateOf(0f) }
    var expanded by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    fun refresh() {
        val missing = PodcastStudio.missingRequirements()
        note = when {
            missing.isNotEmpty() -> missing.joinToString(" ")
            !PodcastStudio.nextEpisode(show.id) -> "An episode of this show is already being made."
            else -> "Making the next episode — research, script and voices take a few minutes."
        }
    }

    // Pull-to-refresh: whatever scroll the page cannot use at its top -- a trackpad's two-finger pull,
    // a wheel turned up, a drag -- accumulates, and past the threshold it starts the next episode.
    val connection = remember(show.id) {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (available.y > 0f) {
                    pull += available.y
                    if (pull > PULL_THRESHOLD) { pull = 0f; refresh() }
                    return available
                }
                if (consumed.y < 0f) pull = 0f
                return Offset.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                pull = 0f
                return Velocity.Zero
            }
        }
    }

    var askingEpisode by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().nestedScroll(connection).verticalScroll(rememberScrollState()).padding(horizontal = 28.dp)) {
        Box(Modifier.fillMaxWidth().height((pull / 3f).coerceIn(0f, 60f).dp), contentAlignment = Alignment.Center) {
            if (pull > 8f) CircularProgressIndicator(progress = { (pull / PULL_THRESHOLD).coerceIn(0f, 1f) }, color = accent, modifier = Modifier.size(22.dp))
        }
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("‹ Library", fontSize = 15.sp, color = accent, modifier = Modifier.clickableRow(onBack).padding(6.dp))
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = onEdit) { Text("Edit", fontSize = 12.sp) }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { refresh() }) { Text("↻  New Episode", fontSize = 12.sp) }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { confirmDelete = true }) { Text("Delete Podcast", fontSize = 12.sp, color = colors.destructive) }
        }

        Row(Modifier.fillMaxWidth().padding(top = 18.dp)) {
            Cover(show, Modifier.size(220.dp).shadow(14.dp, RoundedCornerShape(14.dp)), radius = 14)
            Spacer(Modifier.width(24.dp))
            Column(Modifier.weight(1f)) {
                Text(show.title, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Text(show.cast.joinToString(" & ") { it.name }.ifBlank { "Prism" }, fontSize = 17.sp, color = accent, modifier = Modifier.padding(top = 2.dp))
                Text(
                    listOfNotNull(show.category, show.originName?.let { "shared from $it" }, "${show.episodes.size} episode${if (show.episodes.size == 1) "" else "s"}").joinToString(" · "),
                    fontSize = 13.sp, color = colors.faint, modifier = Modifier.padding(top = 4.dp),
                )
                if (show.description.isNotBlank()) {
                    Text(show.description, fontSize = 14.sp, color = colors.muted, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
                }
                val latest = show.episodes.maxByOrNull { it.number }
                Button(
                    onClick = { latest?.let { PodcastPlayer.play(show.id, it.id) } ?: run { note = "No episodes yet — pull down to make one." } },
                    colors = ButtonDefaults.buttonColors(containerColor = accent),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.padding(top = 14.dp),
                ) {
                    Text(
                        when {
                            player.podcastId == show.id && player.playing -> "❚❚  Pause"
                            player.podcastId == show.id && player.episodeId != null -> "▶  Resume"
                            latest == null -> "No Episodes Yet"
                            else -> "▶  Latest Episode"
                        },
                        fontSize = 14.sp,
                    )
                }
                if (show.coverCredit.startsWith("web: ")) {
                    Text(
                        "Artwork found at " + runCatching { java.net.URI(show.coverCredit.removePrefix("web: ")).host }.getOrNull().orEmpty(),
                        fontSize = 11.sp, color = colors.faint, modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }

        if (note.isNotBlank()) Text(note, fontSize = 13.sp, color = colors.muted, modifier = Modifier.padding(top = 12.dp))
        if (status != null) {
            Spacer(Modifier.height(14.dp))
            Card {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        when {
                            status.error != null -> "Could not finish: " + status.error
                            status.queued -> status.stage
                            else -> status.stage + "  " + (status.fraction * 100).roundToInt() + "%"
                        },
                        fontSize = 13.sp,
                    )
                    if (status.error == null) PrismProgressBar(if (status.queued) 0f else status.fraction, Modifier.padding(top = 8.dp))
                    Text(
                        if (status.error != null) "Dismiss" else "Stop",
                        fontSize = 13.sp, color = accent,
                        modifier = Modifier.padding(top = 8.dp).clickableRow {
                            if (status.error != null) PodcastStudio.dismiss(show.id) else PodcastStudio.cancel(show.id)
                        },
                    )
                }
            }
        }

        Text("Episodes", fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 24.dp))
        Text("Pull down, or click New Episode, to make the next one from this week's news.", fontSize = 12.sp, color = colors.faint, modifier = Modifier.padding(bottom = 8.dp))
        show.episodes.sortedByDescending { it.number }.forEach { episode ->
            Box(Modifier.fillMaxWidth().height(1.dp).background(colors.divider))
            EpisodeRow(show, episode, accent, player, expanded == episode.id, onNote = { note = it }) {
                expanded = if (expanded == episode.id) null else episode.id
            }
        }
        Spacer(Modifier.height(90.dp))
    }
    // The episode button: a title and a topic, the topic being what the episode researches.
    Surface(
        color = accent, shape = CircleShape, shadowElevation = 8.dp,
        modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp).size(56.dp).clickableRow {
            val missing = PodcastStudio.missingRequirements()
            if (missing.isNotEmpty()) note = missing.joinToString(" ") else askingEpisode = true
        },
    ) { Box(contentAlignment = Alignment.Center) { Text("+", fontSize = 30.sp, color = Color.White) } }
    }

    if (askingEpisode) {
        var episodeTitle by remember { mutableStateOf("") }
        var topic by remember { mutableStateOf("") }
        androidx.compose.ui.window.Dialog(onDismissRequest = { askingEpisode = false }) {
            Surface(color = colors.surface, shape = RoundedCornerShape(14.dp)) {
                Column(Modifier.padding(20.dp).width(420.dp)) {
                    Text("New Episode", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(12.dp))
                    // Live fields, not CommittingField: Done has to know the topic as it is typed.
                    androidx.compose.material3.OutlinedTextField(
                        value = episodeTitle, onValueChange = { episodeTitle = it }, singleLine = true,
                        label = { Text("Episode title") }, modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    androidx.compose.material3.OutlinedTextField(
                        value = topic, onValueChange = { topic = it }, minLines = 2,
                        label = { Text("Topic — what this episode is about") }, modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "Prism researches news, blog posts and social media about this topic, and the hosts discuss it.",
                        fontSize = 11.sp, color = colors.faint, modifier = Modifier.padding(top = 6.dp),
                    )
                    Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.End) {
                        OutlinedButton(onClick = { askingEpisode = false }) { Text("Cancel") }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            enabled = topic.isNotBlank(),
                            colors = ButtonDefaults.buttonColors(containerColor = accent),
                            onClick = {
                                askingEpisode = false
                                note = if (PodcastStudio.nextEpisode(show.id, episodeTitle, topic)) "Making an episode about ${topic.trim()}."
                                else "An episode of this show is already being made."
                            },
                        ) { Text("Done") }
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { confirmDelete = false }) {
            Surface(color = colors.surface, shape = RoundedCornerShape(14.dp)) {
                Column(Modifier.padding(20.dp).width(360.dp)) {
                    Text("Delete “${show.title}”?", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text("Every episode on this computer is deleted. Trusted devices keep their own copies.", fontSize = 13.sp, color = colors.muted, modifier = Modifier.padding(top = 8.dp))
                    Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.End) {
                        OutlinedButton(onClick = { confirmDelete = false }) { Text("Cancel") }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = {
                            PodcastStudio.cancel(show.id)
                            if (PodcastPlayer.state.podcastId == show.id) PodcastPlayer.stop()
                            PodcastStore.delete(show.id)
                            confirmDelete = false
                            onBack()
                        }, colors = ButtonDefaults.buttonColors(containerColor = colors.destructive)) { Text("Delete") }
                    }
                }
            }
        }
    }
}

private const val PULL_THRESHOLD = 180f

@Composable
private fun EpisodeRow(
    show: Podcast,
    episode: Episode,
    accent: Color,
    player: PodcastPlayer.State,
    open: Boolean,
    onNote: (String) -> Unit,
    onToggle: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val colors = LocalPrismColors.current
    val current = player.podcastId == show.id && player.episodeId == episode.id
    val available = PodcastStore.hasAudio(show.id, episode.id)
    val position = if (current) player.positionMs else PodcastPlayer.savedPosition(show.id, episode.id)
    // Right-click: Recreate or Delete.
    Column(Modifier.fillMaxWidth().rightClickable { menu = true }.clickableRow(onToggle).padding(vertical = 12.dp)) {
        androidx.compose.material3.DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            androidx.compose.material3.DropdownMenuItem(text = { Text("Recreate", fontSize = 13.sp) }, onClick = {
                menu = false
                val missing = PodcastStudio.missingRequirements()
                onNote(
                    when {
                        missing.isNotEmpty() -> missing.joinToString(" ")
                        !PodcastStudio.recreateEpisode(show.id, episode.id) -> "An episode of this show is already being made."
                        else -> "Recreating episode ${episode.number} from fresh research. The current one stays until the new one is ready."
                    },
                )
            })
            androidx.compose.material3.DropdownMenuItem(text = { Text("Delete", fontSize = 13.sp) }, onClick = {
                menu = false
                if (PodcastPlayer.isCurrent(show.id, episode.id)) PodcastPlayer.stop()
                PodcastStore.deleteEpisode(show.id, episode.id)
            })
        }
        Text(
            dateLabel(episode.createdAt) + (if (show.shared && episode.madeBy.isNotBlank()) " · " + episode.madeBy.uppercase() else ""),
            fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = colors.faint, letterSpacing = 0.5.sp,
        )
        Text(episode.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(vertical = 3.dp))
        Text(episode.summary, fontSize = 13.sp, color = colors.muted, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(30.dp).clip(CircleShape).background(accent.copy(alpha = 0.15f)).alphaIf(available)
                    .clickableRow { if (available) PodcastPlayer.play(show.id, episode.id) },
                contentAlignment = Alignment.Center,
            ) { Text(if (current && player.playing) "❚❚" else "▶", fontSize = 12.sp, color = accent) }
            Spacer(Modifier.width(10.dp))
            Text(
                when {
                    !available -> "Downloading from ${episode.madeBy.ifBlank { "a trusted device" }}…"
                    PodcastPlayer.isPlayed(show.id, episode.id) && position == 0L -> "Played · " + duration(episode.durationMs)
                    position > 0 -> duration((episode.durationMs - position).coerceAtLeast(0)) + " left"
                    else -> duration(episode.durationMs)
                },
                fontSize = 12.sp, color = accent,
            )
        }
        if (open) {
            val (lines, sources) = remember(episode.id) { PodcastStore.transcript(show.id, episode.id) }
            if (lines.isNotEmpty()) {
                SectionHeader("Transcript")
                lines.forEach { line ->
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(line.speaker) }
                            append("  "); append(line.text)
                        },
                        fontSize = 13.sp, lineHeight = 19.sp,
                        modifier = Modifier.padding(vertical = 2.dp).clickableRow {
                            PodcastPlayer.playAt(show.id, episode.id, line.startMs)
                        },
                    )
                }
            }
            if (sources.isNotEmpty()) {
                SectionHeader("Sources")
                sources.forEach { source ->
                    Text(
                        "• " + source.title + "  (" + source.kind + ")",
                        fontSize = 12.sp, color = accent,
                        modifier = Modifier.padding(vertical = 2.dp).clickableRow {
                            runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(source.url)) }
                        },
                    )
                }
            }
            if (lines.isEmpty() && sources.isEmpty()) Text("The transcript has not arrived yet.", fontSize = 12.sp, color = colors.faint)
        }
    }
}

// ── Player bar ──────────────────────────────────────────────────────────

@Composable
private fun PlayerBar(state: PodcastPlayer.State, accent: Color, onOpen: () -> Unit) {
    val colors = LocalPrismColors.current
    val show = state.podcastId?.let { PodcastStore.find(it) } ?: return
    val episode = show.episodes.firstOrNull { it.id == state.episodeId } ?: return
    Surface(color = colors.surface, border = BorderStroke(1.dp, colors.divider)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.clickableRow(onOpen)) { Cover(show, Modifier.size(44.dp), radius = 6) }
            Column(Modifier.width(240.dp).padding(start = 10.dp).clickableRow(onOpen)) {
                Text(episode.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(show.title, fontSize = 11.sp, color = colors.faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text("↺15", fontSize = 13.sp, modifier = Modifier.clickableRow { PodcastPlayer.skip(-15_000) }.padding(8.dp))
            Text(if (state.playing) "❚❚" else "▶", fontSize = 20.sp, modifier = Modifier.clickableRow { PodcastPlayer.toggle() }.padding(8.dp))
            Text("30↻", fontSize = 13.sp, modifier = Modifier.clickableRow { PodcastPlayer.skip(30_000) }.padding(8.dp))
            Text(clock(state.positionMs), fontSize = 11.sp, color = colors.faint, modifier = Modifier.padding(horizontal = 8.dp))
            Slider(
                value = if (state.durationMs > 0) state.positionMs.toFloat() / state.durationMs else 0f,
                onValueChange = { PodcastPlayer.seekTo((it * state.durationMs).toLong()) },
                colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = accent),
                modifier = Modifier.weight(1f),
            )
            Text("-" + clock((state.durationMs - state.positionMs).coerceAtLeast(0)), fontSize = 11.sp, color = colors.faint, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

// ── Formatting ──────────────────────────────────────────────────────────

private fun relative(time: Long): String {
    val days = ((System.currentTimeMillis() - time) / 86_400_000L).toInt()
    return when {
        days <= 0 -> "today"
        days == 1 -> "yesterday"
        days < 7 -> "$days days ago"
        else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(time))
    }
}

private fun dateLabel(time: Long): String {
    val days = ((System.currentTimeMillis() - time) / 86_400_000L).toInt()
    return when {
        days <= 0 -> "TODAY"
        days == 1 -> "YESTERDAY"
        days < 7 -> SimpleDateFormat("EEEE", Locale.getDefault()).format(Date(time)).uppercase()
        else -> SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(time)).uppercase()
    }
}

private fun duration(ms: Long): String {
    val minutes = (ms / 60_000).toInt()
    return when {
        ms < 60_000 -> "${(ms / 1000).coerceAtLeast(1)} sec"
        minutes < 60 -> "$minutes min"
        else -> "${minutes / 60} hr ${minutes % 60} min"
    }
}

private fun clock(ms: Long): String {
    val total = ms / 1000
    return if (total >= 3600) "%d:%02d:%02d".format(total / 3600, (total / 60) % 60, total % 60)
    else "%d:%02d".format(total / 60, total % 60)
}
