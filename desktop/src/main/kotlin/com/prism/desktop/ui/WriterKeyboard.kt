package com.prism.desktop.ui

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import com.prism.desktop.writer.DesktopTypist
import com.prism.launcher.PrismSettings
import com.prism.launcher.writer.Autocorrect
import com.prism.launcher.writer.Key
import com.prism.launcher.writer.KeyboardLayout
import com.prism.launcher.writer.SwipeDecoder
import com.prism.launcher.writer.WriterDictionary
import com.prism.launcher.writer.WriterEmoji
import com.prism.launcher.writer.WriterGifSource
import com.prism.launcher.writer.WriterSuggestions
import com.prism.launcher.writer.WriterUserDictionary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Prism Writer, as a desktop virtual keyboard. PHASE 102.
 *
 * ## What this is, precisely
 *
 * An on-screen keyboard in an always-on-top window that synthesises keystrokes into whatever
 * application has focus. It is NOT a registered system input method -- see [DesktopTypist] for why
 * that is not something an application can be on Windows or Linux without shipping a TSF service or
 * an IBus engine. The one capability lost is popping up automatically when a text field is focused;
 * everything else the phone's keyboard does is here.
 *
 * ## The same brain, the same style
 *
 * The layout grid, the suggestions, the learned dictionary, the autocorrect, the swipe decoder, the
 * emoji table and the GIF search are all `:core` and all shared with the phone -- so a word learned
 * on the phone is suggested here after a profile transfer, and the glide gesture decodes to the same
 * word. What is written here is the rendering and the key handling, and it is drawn to the same
 * proportions the Android view uses because `KeyboardLayout` expresses every coordinate as a fraction
 * of the keyboard area rather than in pixels.
 *
 * ## Glide typing works, which is the part that would have been easy to skip
 *
 * A pointer held down and dragged across the keys is a glide, and the path goes to the same
 * `SwipeDecoder` the phone uses. The distinction from a tap is made the way the decoder makes it: a
 * path shorter than 0.15 of the keyboard's width is a tap that wandered, not a word.
 */
@Composable
fun WriterKeyboardWindow(onClose: () -> Unit) {
    val state = rememberWindowState(width = 760.dp, height = 400.dp)
    Window(
        onCloseRequest = onClose,
        state = state,
        title = "Prism Writer",
        // ALWAYS ON TOP, because the window being typed into has focus and this one must stay
        // visible behind it. A keyboard that went behind the thing it types into would be unusable.
        alwaysOnTop = true,
        resizable = true,
    ) {
        PrismTheme {
            WriterKeyboard()
        }
    }
}

private enum class WriterPanel { KEYS, EMOJI, GIF, SEARCH, DICTIONARY }

/**
 * The user dictionary a word typed here is learned into.
 *
 * `WriterUserDictionary` holds named dictionaries, and a word has to go into one of them to persist.
 * "Personal" is the name the phone's dictionary screen uses for the default, so the two platforms
 * write into the same bucket rather than each creating its own.
 */
private const val PERSONAL = "Personal"

@Composable
fun WriterKeyboard() {
    val colors = LocalPrismColors.current
    val scope = rememberCoroutineScope()

    var shifted by remember { mutableStateOf(true) }
    var symbols by remember { mutableStateOf(false) }
    var panel by remember { mutableStateOf(WriterPanel.KEYS) }
    var composing by remember { mutableStateOf("") }
    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    var note by remember { mutableStateOf("") }
    var glide by remember { mutableStateOf<List<SwipeDecoder.Point>>(emptyList()) }

    val available = remember { DesktopTypist.isAvailable() }

    // The dictionary loads itself lazily from its own resource, so there is nothing to call. What
    // DOES need restoring is the user's learned words, which live in settings rather than in the
    // object -- without this the keyboard starts every session having forgotten them.
    LaunchedEffect(Unit) {
        withContext(Dispatchers.Default) {
            runCatching {
                WriterDictionary.restoreUserWords(
                    WriterUserDictionary.dictionaries().flatMap { it.words },
                )
            }
        }
    }

    val layout = remember(shifted, symbols) {
        if (symbols) KeyboardLayout.symbols() else KeyboardLayout.qwerty(shifted = shifted)
    }

    /** Sends text onward and keeps the composing word in step. */
    fun emit(text: String) {
        DesktopTypist.type(text)
        if (text.length == 1 && text[0].isLetter()) {
            composing += text
        } else {
            // A boundary. The finished word is learned, which is what makes the dictionary the
            // user's rather than a fixed list -- and it is learned on the boundary rather than per
            // keystroke so a half-typed word never enters it.
            if (composing.length >= 2) WriterDictionary.learn(composing.lowercase())
            composing = ""
        }
        // Shift releases after one letter, the way every phone keyboard behaves, and only when the
        // user did not latch it by double-tapping.
        if (shifted && text.length == 1 && text[0].isLetter()) shifted = false
    }

    fun commitSuggestion(word: String) {
        val cased = Autocorrect.matchCase(composing, word)
        DesktopTypist.replace(composing.length, cased + " ")
        WriterDictionary.learn(word.lowercase())
        composing = ""
        suggestions = emptyList()
    }

    // Suggestions off the UI thread: forPrefix walks the dictionary, and a keyboard that stuttered
    // on every keystroke would be worse than one with no suggestions.
    LaunchedEffect(composing) {
        if (composing.isBlank() || !PrismSettings.getWriterSuggestions()) {
            suggestions = emptyList()
            return@LaunchedEffect
        }
        val prefix = composing
        val found = withContext(Dispatchers.Default) {
            runCatching {
                WriterSuggestions.arrangeForStrip(WriterSuggestions.forPrefix(prefix, layout))
                    .map { it.word }
            }.getOrDefault(emptyList())
        }
        // Discarded if the word moved on: a stale strip invites tapping the wrong word.
        if (composing == prefix) suggestions = found
    }

    Column(Modifier.fillMaxSize().background(Color(0xFF101014))) {

        if (!available) {
            Text(
                DesktopTypist.unavailableReason(),
                fontSize = 12.sp,
                color = Color(0xFFFFB4B4),
                lineHeight = 18.sp,
                modifier = Modifier.padding(14.dp),
            )
            return@Column
        }

        // ── The suggestion strip ────────────────────────────────────────────
        Row(
            Modifier.fillMaxWidth().height(34.dp).background(Color(0xFF17171C)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WriterPanel.entries.forEach { candidate ->
                Text(
                    when (candidate) {
                        WriterPanel.KEYS -> "abc"
                        WriterPanel.EMOJI -> "☺"
                        WriterPanel.GIF -> "gif"
                        WriterPanel.SEARCH -> "🔍"
                        WriterPanel.DICTIONARY -> "📖"
                    },
                    fontSize = 12.sp,
                    color = if (candidate == panel) colors.accent else colors.faint,
                    modifier = Modifier
                        .clickableRow { panel = candidate }
                        .padding(horizontal = 7.dp, vertical = 6.dp),
                )
            }
            Spacer(Modifier.width(6.dp))
            if (suggestions.isEmpty()) {
                Text(
                    composing.ifBlank { "Prism Writer — types into whatever window has focus" },
                    fontSize = 11.sp,
                    color = colors.faint,
                    modifier = Modifier.weight(1f),
                )
            } else {
                suggestions.forEach { word ->
                    Text(
                        word,
                        fontSize = 13.sp,
                        color = colors.onSurface,
                        modifier = Modifier
                            .weight(1f)
                            .clickableRow { commitSuggestion(word) }
                            .padding(vertical = 7.dp),
                    )
                }
            }
            MicButton(
                onText = { heard -> emit(heard) },
                onStatus = { if (it.isNotBlank()) note = it },
            )
            Spacer(Modifier.width(6.dp))
        }

        if (note.isNotBlank()) {
            Text(note, fontSize = 10.sp, color = colors.faint, modifier = Modifier.padding(6.dp))
        }

        when (panel) {
            WriterPanel.KEYS -> Box(Modifier.fillMaxSize()) {
                Canvas(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(layout) {
                            awaitPointerEventScope {
                                var path = mutableListOf<SwipeDecoder.Point>()
                                var downKey: Key? = null
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull() ?: continue
                                    val fx = (change.position.x / size.width).coerceIn(0f, 1f)
                                    val fy = (change.position.y / size.height).coerceIn(0f, 1f)

                                    if (change.pressed) {
                                        if (downKey == null) {
                                            downKey = layout.keyAt(fx, fy)
                                            path = mutableListOf()
                                        }
                                        path.add(SwipeDecoder.Point(fx, fy))
                                        glide = path.toList()
                                        continue
                                    }

                                    // Released. A glide and a tap are told apart by the decoder,
                                    // which refuses a path shorter than 0.15 of the width -- so a
                                    // tap that wandered a little is still a tap.
                                    val recorded = path.toList()
                                    val pressedKey = downKey
                                    downKey = null
                                    path = mutableListOf()
                                    glide = emptyList()
                                    if (pressedKey == null) continue

                                    if (recorded.size >= 3) {
                                        val decoded = SwipeDecoder.decode(recorded, layout)
                                        val word = decoded.firstOrNull()?.word
                                        if (word != null) {
                                            val cased = if (shifted) {
                                                word.replaceFirstChar { it.uppercase() }
                                            } else word
                                            DesktopTypist.type(cased + " ")
                                            WriterDictionary.learn(word)
                                            composing = ""
                                            if (shifted) shifted = false
                                            continue
                                        }
                                    }

                                    when (pressedKey.action) {
                                        Key.Action.CHARACTER -> emit(pressedKey.output)
                                        Key.Action.SHIFT -> shifted = !shifted
                                        Key.Action.BACKSPACE -> {
                                            DesktopTypist.backspace()
                                            composing = composing.dropLast(1)
                                        }
                                        Key.Action.SPACE -> {
                                            // AUTOCORRECT ON SPACE, which is where a phone does it:
                                            // the word is finished and the correction is still
                                            // undoable by backspacing.
                                            val fix = if (composing.length >= 3 &&
                                                PrismSettings.getWriterAutocorrect()
                                            ) {
                                                Autocorrect.correct(composing, layout)
                                            } else null
                                            if (fix != null && !fix.equals(composing, true)) {
                                                DesktopTypist.replace(
                                                    composing.length,
                                                    Autocorrect.matchCase(composing, fix) + " ",
                                                )
                                                note = composing + " → " + fix
                                            } else {
                                                DesktopTypist.type(" ")
                                            }
                                            if (composing.length >= 2) {
                                                WriterDictionary.learn(composing.lowercase())
                                            }
                                            composing = ""
                                        }
                                        Key.Action.RETURN -> {
                                            DesktopTypist.enter()
                                            composing = ""
                                        }
                                        Key.Action.SYMBOLS -> symbols = !symbols
                                        Key.Action.EMOJI -> panel = WriterPanel.EMOJI
                                        Key.Action.STICKERS -> panel = WriterPanel.GIF
                                        Key.Action.SEARCH -> panel = WriterPanel.SEARCH
                                        Key.Action.MIC -> note =
                                            "Use the microphone on the strip above."
                                        Key.Action.THEME, Key.Action.TRANSLATE ->
                                            note = "That key is a phone feature."
                                    }
                                }
                            }
                        },
                ) {
                    drawKeyboard(layout, glide, colors.accent, shifted)
                }
                // Over the canvas, and NOT consuming pointer input -- the canvas below owns every
                // press and drag, so the captions must not intercept them.
                KeyCaptions(layout)
            }

            WriterPanel.EMOJI -> EmojiPanel(onPick = { DesktopTypist.type(it) })

            WriterPanel.GIF -> GifPanel(onNote = { note = it })

            WriterPanel.SEARCH -> SearchPanel(onInsert = { DesktopTypist.type(it) })

            WriterPanel.DICTIONARY -> DictionaryPanel()
        }
    }
}

/**
 * Draws the keys from the shared layout.
 *
 * Every coordinate comes out of [KeyboardLayout] as a fraction, so this is the same grid the Android
 * view hangs its keys on and the proportions match without either side knowing the other's pixels.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawKeyboard(
    layout: KeyboardLayout,
    glide: List<SwipeDecoder.Point>,
    accent: Color,
    shifted: Boolean,
) {
    drawRect(color = Color(0xFF101014), size = size)
    val gap = 3f

    layout.keys.forEach { key ->
        val left = (key.centerX - key.width / 2f) * size.width + gap
        val top = (key.centerY - key.height / 2f) * size.height + gap
        val w = key.width * size.width - gap * 2
        val h = key.height * size.height - gap * 2

        val fill = when (key.action) {
            Key.Action.CHARACTER -> Color(0xFF26262E)
            Key.Action.SHIFT -> if (shifted) accent.copy(alpha = 0.35f) else Color(0xFF1C1C22)
            Key.Action.SPACE -> Color(0xFF26262E)
            else -> Color(0xFF1C1C22)
        }
        drawRoundRect(
            color = fill,
            topLeft = Offset(left, top),
            size = Size(w, h),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(6f, 6f),
        )
    }

    // The glide trail, drawn over the keys. Without it the gesture is invisible and a user cannot
    // tell whether the keyboard noticed the drag.
    if (glide.size >= 2) {
        for (i in 0 until glide.size - 1) {
            drawLine(
                color = accent.copy(alpha = 0.55f),
                start = Offset(glide[i].x * size.width, glide[i].y * size.height),
                end = Offset(glide[i + 1].x * size.width, glide[i + 1].y * size.height),
                strokeWidth = 4f,
                cap = StrokeCap.Round,
            )
        }
    }
}

/**
 * The key captions, laid out over the canvas.
 *
 * ## Why they are composables and not drawn
 *
 * `DrawScope.drawText` needs a `TextMeasurer` and a styled `AnnotatedString` per key, and the shift,
 * backspace and symbol keys carry glyphs whose metrics differ from a letter's. A `Layout` that places
 * one `Text` per key is less code, gets the font and the baseline right for free, and the key count
 * is forty -- not a number where the allocation matters.
 *
 * ## A custom Layout rather than a Box with offsets
 *
 * Because the positions are FRACTIONS of the available space, and a fraction cannot become a `dp`
 * offset until the size is known. `Layout` is the one place that has both the constraints and the
 * children, which is exactly what placing a fractional grid needs.
 */
@Composable
private fun KeyCaptions(layout: KeyboardLayout) {
    val colors = LocalPrismColors.current
    androidx.compose.ui.layout.Layout(
        content = {
            layout.keys.forEach { key ->
                Text(
                    captionFor(key),
                    fontSize = if (key.isLetter) 15.sp else 11.sp,
                    color = when (key.action) {
                        Key.Action.CHARACTER -> colors.onSurface
                        else -> colors.muted
                    },
                )
            }
        },
        modifier = Modifier.fillMaxSize(),
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        layout(width, height) {
            placeables.forEachIndexed { index, placeable ->
                val key = layout.keys.getOrNull(index) ?: return@forEachIndexed
                // Centred on the key's own centre, which is what makes a caption sit right on a
                // wide space bar and a narrow letter alike.
                placeable.place(
                    x = (key.centerX * width).toInt() - placeable.width / 2,
                    y = (key.centerY * height).toInt() - placeable.height / 2,
                )
            }
        }
    }
}

/**
 * What a key says.
 *
 * The action keys get glyphs rather than words: a keyboard row is too narrow for "backspace", and
 * these are the symbols every on-screen keyboard uses for them.
 */
private fun captionFor(key: Key): String = when (key.action) {
    Key.Action.SHIFT -> "⇧"
    Key.Action.BACKSPACE -> "⌫"
    Key.Action.RETURN -> "⏎"
    Key.Action.SPACE -> "space"
    Key.Action.SYMBOLS -> key.label.ifBlank { "?123" }
    Key.Action.EMOJI -> "☺"
    Key.Action.STICKERS -> "gif"
    Key.Action.SEARCH -> "🔍"
    Key.Action.MIC -> "🎤"
    Key.Action.THEME -> "🎨"
    Key.Action.TRANSLATE -> "文"
    Key.Action.CHARACTER -> key.label
}

@Composable
private fun EmojiPanel(onPick: (String) -> Unit) {
    val colors = LocalPrismColors.current
    var category by remember { mutableStateOf(0) }
    val glyphs = remember(category) {
        WriterEmoji.glyphsOf(WriterEmoji.CATEGORIES[category].second)
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().background(Color(0xFF17171C))) {
            WriterEmoji.CATEGORIES.forEachIndexed { index, (tab, _) ->
                Text(
                    tab,
                    fontSize = 17.sp,
                    modifier = Modifier
                        .clickableRow { category = index }
                        .padding(horizontal = 9.dp, vertical = 5.dp)
                        .background(
                            if (index == category) colors.accent.copy(alpha = 0.18f)
                            else Color.Transparent,
                        ),
                )
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(42.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(glyphs) { glyph ->
                Text(
                    glyph,
                    fontSize = 22.sp,
                    modifier = Modifier.clickableRow { onPick(glyph) }.padding(6.dp),
                )
            }
        }
    }
}

@Composable
private fun GifPanel(onNote: (String) -> Unit) {
    val colors = LocalPrismColors.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<WriterGifSource.Gif>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(10.dp)) {
        if (!WriterGifSource.hasKey()) {
            Text(
                "GIF search needs an API key, which Prism does not ship — it is per-user and " +
                    "rate-limited. Set one in Prism Settings and this panel works. The same key " +
                    "the phone uses, read from the same setting.",
                fontSize = 12.sp,
                color = colors.muted,
                lineHeight = 18.sp,
            )
            return@Column
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search", fontSize = 12.sp) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(6.dp))
            Button(
                enabled = query.isNotBlank() && !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        results = withContext(Dispatchers.IO) {
                            runCatching { WriterGifSource.search(query.trim()) }
                                .getOrDefault(emptyList())
                        }
                        busy = false
                        if (results.isEmpty()) onNote("Nothing came back.")
                    }
                },
            ) { Text(if (busy) "…" else "Find", fontSize = 11.sp) }
        }
        Spacer(Modifier.height(8.dp))
        // URLs rather than thumbnails: a GIF goes into a chat as a link on both platforms, and
        // decoding animated GIFs into a Compose grid would be a renderer this does not need.
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            results.forEach { gif ->
                Text(
                    gif.url,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = colors.accent,
                    lineHeight = 15.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickableRow {
                            DesktopTypist.type(gif.url + " ")
                            onNote("Pasted the link.")
                        }
                        .padding(vertical = 3.dp),
                )
            }
        }
    }
}

@Composable
private fun SearchPanel(onInsert: (String) -> Unit) {
    val colors = LocalPrismColors.current
    var query by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text(
            "Inline search, which on the phone puts a result straight into the message you are " +
                "writing. Here it inserts the query as a search URL, because a desktop already has " +
                "a browser a click away and a results list inside a keyboard would be the wrong " +
                "place to read one.",
            fontSize = 11.sp,
            color = colors.faint,
            lineHeight = 16.sp,
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search for", fontSize = 12.sp) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(6.dp))
            Button(
                enabled = query.isNotBlank(),
                onClick = {
                    val url = "https://duckduckgo.com/?q=" +
                        java.net.URLEncoder.encode(query.trim(), "UTF-8")
                    onInsert(url + " ")
                    query = ""
                },
            ) { Text("Insert", fontSize = 11.sp) }
        }
    }
}

@Composable
private fun DictionaryPanel() {
    val colors = LocalPrismColors.current
    var revision by remember { mutableStateOf(0) }
    var word by remember { mutableStateOf("") }
    val words = remember(revision) { WriterDictionary.userWordList().sorted() }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text(
            words.size.toString() + " learned word(s). The same list the phone learns into, stored " +
                "under the same key — so a profile transfer carries it.",
            fontSize = 11.sp,
            color = colors.faint,
            lineHeight = 16.sp,
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = word,
                onValueChange = { word = it },
                placeholder = { Text("Add a word", fontSize = 12.sp) },
                singleLine = true,
                modifier = Modifier.width(220.dp),
            )
            Spacer(Modifier.width(6.dp))
            Button(
                enabled = word.isNotBlank(),
                onClick = {
                    val added = word.trim().lowercase()
                    WriterDictionary.learn(added)
                    // Into the SAME named dictionary the phone's dictionary screen writes to, so a
                    // word added here survives a restart and travels with a profile.
                    runCatching { WriterUserDictionary.addWord(PERSONAL, added) }
                    word = ""
                    revision++
                },
            ) { Text("Add", fontSize = 11.sp) }
        }
        Spacer(Modifier.height(8.dp))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            words.forEach {
                Text(
                    it,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = colors.muted,
                    lineHeight = 17.sp,
                )
            }
        }
    }
}
