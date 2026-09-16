package com.prism.launcher.writer

import android.content.Context
import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import com.prism.launcher.PrismLogger
import com.prism.launcher.PrismSettings
import com.prism.launcher.search.PrismUrlQuery
import com.prism.launcher.messaging.LocalAi
import com.prism.launcher.writer.Autocorrect
import com.prism.launcher.writer.Key
import com.prism.launcher.writer.KeyboardLayout
import com.prism.launcher.writer.SwipeDecoder
import com.prism.launcher.writer.WriterDictionary
import java.util.Locale

/**
 * Prism Writer: the keyboard.
 *
 * ## It is a real IME, so it works everywhere
 *
 * An [InputMethodService] is registered with the system, which is what makes this usable in any
 * app rather than only inside Prism. Nothing here reaches into the launcher; text goes out through
 * the same [android.view.inputmethod.InputConnection] every keyboard uses.
 *
 * ## AI features are conditional, and honestly so
 *
 * Assisted typing and live translate need a model the user actually has. They are offered only when
 * one is active -- an imported on-device model, or an Ollama server on their own network, or a
 * configured cloud model. When none is, the translate key is absent from the layout entirely rather
 * than present and disappointing, which is why [KeyboardLayout.qwerty] takes `showTranslate`.
 *
 * ## Corrections are applied at word boundaries, never mid-word
 *
 * Rewriting text while somebody is still typing it is the behaviour that makes people disable
 * autocorrect. A word is only ever reconsidered when it is finished -- on space, punctuation or
 * return -- and only if [Autocorrect] is confident enough to act.
 */
class PrismWriterService : InputMethodService() {

    private companion object {
        /** Delay before a held backspace starts repeating at all. */
        const val FIRST_REPEAT_MS = 400L
        const val CHAR_REPEAT_MS = 55L
        /** After this much holding, deletion moves from characters to words. */
        const val WORD_DELETE_AFTER_MS = 1500L
        const val WORD_REPEAT_MS = 130L

        const val TAG = "PrismWriter"
    }

    private lateinit var keyboardView: PrismKeyboardView
    private var container: LinearLayout? = null

    private var shifted = true
    private var symbolsMode = false

    /** The word currently being typed, for autocorrect and learning. */
    private val composing = StringBuilder()

    private lateinit var searchBar: WriterSearchBar
    private lateinit var suggestionStrip: WriterSuggestionStrip
    private lateinit var emojiPanel: WriterEmojiPanel
    private lateinit var gifPanel: WriterGifPanel

    /** Which surface is showing in place of the keys, if any. */
    private enum class Panel { NONE, EMOJI, GIFS }
    private var panel = Panel.NONE

    /**
     * Whether keystrokes go to the search bar instead of the app being typed into.
     *
     * A keyboard cannot hand focus to its own view — focus belongs to the host app's field — so
     * "typing focuses the search box" is implemented by ROUTING here rather than by focus.
     */
    private var searchActive = false

    private val backspaceHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var backspaceRepeat: Runnable? = null
    private var suggestionThread: Thread? = null

    private var tts: TextToSpeech? = null
    private var speechRecognizer: SpeechRecognizer? = null

    override fun onCreate() {
        super.onCreate()
        WriterDictionary.restoreUserWords(PrismSettings.getWriterLearnedWords())
        tts = TextToSpeech(this) { }
    }

    override fun onCreateInputView(): View {
        keyboardView = PrismKeyboardView(this).apply {
            onKey = { key -> handleKey(key) }
            onGesture = { path -> handleGesture(path) }
            onAlternate = { text -> handleAlternate(text) }
            onHoldStart = { key ->
                // Only backspace repeats. A held letter opens its alternates instead, and a held
                // shift is somebody resting a thumb.
                if (key.action == Key.Action.BACKSPACE) startBackspaceRepeat()
            }
            onHoldEnd = { stopBackspaceRepeat() }
        }
        searchBar = WriterSearchBar(this).apply {
            visibility = View.GONE
            onSubmit = { query -> runQuickSearch(query) }
            onDismiss = { closeSearch() }
        }

        suggestionStrip = WriterSuggestionStrip(this).apply {
            visibility = View.GONE
            onPicked = { word -> acceptSuggestion(word) }
        }

        emojiPanel = WriterEmojiPanel(this).apply {
            visibility = View.GONE
            onEmoji = { glyph -> currentInputConnection?.commitText(glyph, 1) }
            onBackspace = { currentInputConnection?.deleteSurroundingText(2, 0) }
            onBack = { showPanel(Panel.NONE) }
        }
        gifPanel = WriterGifPanel(this).apply {
            visibility = View.GONE
            onPicked = { gif -> sendGif(gif) }
            onBack = { showPanel(Panel.NONE) }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                searchBar,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                suggestionStrip,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                emojiPanel,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (resources.displayMetrics.heightPixels * 0.34f).toInt(),
                ),
            )
            addView(
                gifPanel,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (resources.displayMetrics.heightPixels * 0.34f).toInt(),
                ),
            )
            addView(
                keyboardView,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    // 0.32 of the screen for keys, plus the headroom the view reserves above them
                    // for popups. Without the extra the keys would simply be squashed into the same
                    // space to make room, which is not the trade being made.
                    (resources.displayMetrics.heightPixels * 0.32f * 1.25f).toInt(),
                ),
            )
        }
        container = root
        refreshLayout()
        return root
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        composing.setLength(0)
        // A fresh field starts capitalised, which is right far more often than not.
        shifted = true
        symbolsMode = false
        refreshLayout()
    }

    override fun onDestroy() {
        PrismSettings.setWriterLearnedWords(WriterDictionary.userWordList())
        runCatching { tts?.shutdown() }
        runCatching { speechRecognizer?.destroy() }
        super.onDestroy()
    }

    // ── Layout and theme ───────────────────────────────────────────────────

    /**
     * Whether an AI-backed feature can run.
     *
     * Accepts any backend the user actually configured, including cloud -- unlike search summaries,
     * translation is a task people reasonably want their best model for, and the text involved is
     * something they are deliberately sending somewhere.
     */
    private fun aiAvailable(): Boolean = when (PrismSettings.getAiMode()) {
        PrismSettings.AI_MODE_LOCAL ->
            PrismSettings.getLocalAiModelPath().isNotBlank() && LocalAi.available()
        PrismSettings.AI_MODE_LOCAL_CLOUD -> PrismSettings.getSelectedOllamaEndpoint() != null
        PrismSettings.AI_MODE_CLOUD -> PrismSettings.getActiveCloudModelId() != null
        else -> false
    }

    private fun refreshLayout() {
        val showTranslate = aiAvailable()
        keyboardView.layout = if (symbolsMode) {
            KeyboardLayout.symbols(showTranslate = showTranslate)
        } else {
            KeyboardLayout.qwerty(shifted = shifted, showTranslate = showTranslate)
        }
        keyboardView.applyTheme(resolveDark())
        if (::suggestionStrip.isInitialized) suggestionStrip.applyTheme(resolveDark())
    }

    /** SYSTEM follows the host app's configuration, since this keyboard appears inside other apps. */
    private fun resolveDark(): Boolean = when (PrismSettings.getWriterTheme()) {
        PrismSettings.WRITER_THEME_DARK -> true
        PrismSettings.WRITER_THEME_LIGHT -> false
        else -> (resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    // ── Keys ───────────────────────────────────────────────────────────────

    private fun handleKey(key: Key) {
        vibrate(key)

        // INTERCEPTED BEFORE THE INPUT CONNECTION IS EVEN FETCHED. While the search bar is open the
        // host app must not see any of this: the whole point is that the query is composed here and
        // only the RESULT is typed into their field.
        if (searchActive && routeToSearch(key)) return

        val ic = currentInputConnection ?: return

        when (key.action) {
            Key.Action.CHARACTER -> {
                ic.commitText(key.output, 1)
                if (key.isLetter) composing.append(key.output) else finishWord(ic, "")
                refreshSuggestions()
                // One-shot shift, the way every touch keyboard behaves.
                if (shifted) {
                    shifted = false
                    refreshLayout()
                }
            }

            Key.Action.SPACE -> {
                finishWord(ic, " ")
            }

            Key.Action.RETURN -> {
                finishWord(ic, "")
                if (!sendDefaultEditorAction(true)) ic.commitText("\n", 1)
            }

            Key.Action.BACKSPACE -> {
                if (composing.isNotEmpty()) composing.setLength(composing.length - 1)
                ic.deleteSurroundingText(1, 0)
                refreshSuggestions()
            }

            Key.Action.SEARCH -> openSearch()

            Key.Action.EMOJI -> showPanel(Panel.EMOJI)
            Key.Action.STICKERS -> showPanel(Panel.GIFS)

            Key.Action.SHIFT -> {
                shifted = !shifted
                refreshLayout()
            }

            Key.Action.SYMBOLS -> {
                symbolsMode = !symbolsMode
                refreshLayout()
            }

            Key.Action.THEME -> {
                // The switch is explicit: once tapped it stops following the system, because a user
                // who reached for it wants a specific answer rather than a preference.
                PrismSettings.setWriterTheme(
                    if (resolveDark()) PrismSettings.WRITER_THEME_LIGHT
                    else PrismSettings.WRITER_THEME_DARK
                )
                refreshLayout()
            }

            Key.Action.MIC -> startDictation(translate = false)

            // TRANSLATES WHAT IS THERE, rather than starting dictation. This key used to call
            // startDictation(translate = true), so it needed the microphone -- and with the grant
            // missing it failed silently, which is why it "did nothing". Translating existing text
            // is also what the key looks like it does, and it works with no permission at all.
            Key.Action.TRANSLATE -> translateExistingText()
        }
    }

    /**
     * Types a character chosen from a long-press popup.
     *
     * Appended to the word in progress rather than committed on its own, so "café" is still one
     * word to autocorrect and to the learner. Treating an accent as a word boundary would teach the
     * dictionary "caf" and then correct it away next time.
     */
    /**
     * Recomputes the strip for the word currently being typed.
     *
     * OFF THE MAIN THREAD, because a prefix scan touches the whole dictionary and this runs on
     * every keystroke. A keyboard that does that inline stutters under the finger, which is worse
     * than having no suggestions at all.
     */
    private fun refreshSuggestions() {
        if (!PrismSettings.getWriterSuggestions() || searchActive) {
            suggestionStrip.clear()
            return
        }
        val prefix = composing.toString()
        if (prefix.isBlank()) {
            suggestionStrip.clear()
            return
        }

        val layout = keyboardView.layout
        val dark = resolveDark()
        suggestionThread?.interrupt()
        val worker = Thread({
            val arranged = runCatching {
                WriterSuggestions.arrangeForStrip(WriterSuggestions.forPrefix(prefix, layout))
                    .map { it.word }
            }.getOrDefault(emptyList())

            android.os.Handler(android.os.Looper.getMainLooper()).post {
                // Discarded if the word moved on while this was running -- a stale strip is worse
                // than an empty one, because it invites tapping the wrong word.
                if (composing.toString() == prefix) suggestionStrip.show(arranged, dark)
            }
        }, "writer-suggest")
        suggestionThread = worker
        worker.isDaemon = true
        worker.start()
    }

    /** Replaces the word in progress with a tapped suggestion. */
    private fun acceptSuggestion(word: String) {
        val ic = currentInputConnection ?: return
        vibrate(null)
        if (composing.isNotEmpty()) ic.deleteSurroundingText(composing.length, 0)
        composing.setLength(0)
        ic.commitText("$word ", 1)
        suggestionStrip.clear()
        if (shifted) {
            shifted = false
            refreshLayout()
        }
    }

    private fun handleAlternate(text: String) {
        val ic = currentInputConnection ?: return
        vibrate(null)
        ic.commitText(text, 1)
        composing.append(text)
        if (shifted) {
            shifted = false
            refreshLayout()
        }
    }

    /**
     * Commits the word in progress, correcting it if warranted, then appends [trailing].
     *
     * This is the ONLY place a correction happens. Doing it per keystroke would mean rewriting text
     * under the user's finger.
     */
    private fun finishWord(ic: android.view.inputmethod.InputConnection, trailing: String) {
        val word = composing.toString()
        composing.setLength(0)

        // A REDEFINITION BEATS AUTOCORRECT, and is checked first for that reason. "omw" is a real
        // rewrite the user asked for; letting the corrector reach it first would turn it into
        // whatever ordinary word it most resembles and the expansion would never happen.
        if (word.isNotEmpty()) {
            WriterUserDictionary.redefine(word)?.let { replacement ->
                ic.deleteSurroundingText(word.length, 0)
                ic.commitText(replacement, 1)
                if (trailing.isNotEmpty()) ic.commitText(trailing, 1)
                suggestionStrip.clear()
                return
            }
        }

        // A word in one of the user's dictionaries is correct by definition; correcting it is the
        // exact thing adding it was meant to stop.
        if (word.isNotEmpty() && WriterUserDictionary.knowsWord(word)) {
            if (trailing.isNotEmpty()) ic.commitText(trailing, 1)
            suggestionStrip.clear()
            return
        }

        if (word.isNotEmpty() && PrismSettings.getWriterAutocorrect()) {
            val corrected = Autocorrect.correct(word, keyboardView.layout)
            if (corrected != null && corrected != word) {
                ic.deleteSurroundingText(word.length, 0)
                ic.commitText(corrected, 1)
            } else {
                // Kept as typed, so stop offering to change it next time.
                WriterDictionary.learn(word)
            }
        }
        if (trailing.isNotEmpty()) ic.commitText(trailing, 1)
        suggestionStrip.clear()

        // A sentence end re-arms capitalisation.
        if (word.isNotEmpty() && trailing == " ") {
            val before = ic.getTextBeforeCursor(3, 0)?.toString().orEmpty()
            if (before.trimEnd().endsWith(".") || before.trimEnd().endsWith("?") ||
                before.trimEnd().endsWith("!")
            ) {
                shifted = true
                refreshLayout()
            }
        }
    }

    // ── Glide ──────────────────────────────────────────────────────────────

    private fun handleGesture(path: List<SwipeDecoder.Point>) {
        val ic = currentInputConnection ?: return
        val candidates = SwipeDecoder.decode(path, keyboardView.layout)
        if (candidates.isEmpty()) return

        vibrate(null)
        // Anything already half-typed is replaced: a glide is a whole word.
        if (composing.isNotEmpty()) {
            ic.deleteSurroundingText(composing.length, 0)
            composing.setLength(0)
        }

        val word = candidates.first().word
        val cased = if (shifted) word.replaceFirstChar { it.uppercase() } else word
        ic.commitText("$cased ", 1)
        if (shifted) {
            shifted = false
            refreshLayout()
        }
    }

    // ── Panels ─────────────────────────────────────────────────────────────

    /**
     * Swaps the keys for a panel, or back.
     *
     * The keyboard itself is HIDDEN rather than removed: an InputMethodService's input view is
     * created once, and rebuilding it to switch surfaces loses the input connection's state and
     * flickers the whole window.
     */
    private fun showPanel(next: Panel) {
        panel = next
        val dark = resolveDark()

        keyboardView.visibility = if (next == Panel.NONE) View.VISIBLE else View.GONE
        suggestionStrip.visibility =
            if (next == Panel.NONE && PrismSettings.getWriterSuggestions()) View.VISIBLE else View.GONE
        emojiPanel.visibility = if (next == Panel.EMOJI) View.VISIBLE else View.GONE
        gifPanel.visibility = if (next == Panel.GIFS) View.VISIBLE else View.GONE

        when (next) {
            Panel.EMOJI -> emojiPanel.applyTheme(dark)
            Panel.GIFS -> {
                gifPanel.applyTheme(dark)
                gifPanel.open(WriterGifSource.Kind.GIF)
            }
            Panel.NONE -> Unit
        }
    }

    /**
     * Sends a chosen GIF.
     *
     * Rich content first, URL second. `commitContent` is how an image reaches a field that accepts
     * one, but most text fields do not — they advertise what they take through
     * `EditorInfoCompat.getContentMimeTypes`, and pasting a link is the only thing that works
     * everywhere else. Silently doing nothing would be the worst of the three.
     */
    private fun sendGif(gif: WriterGifSource.Gif) {
        val ic = currentInputConnection ?: return
        val editor = currentInputEditorInfo
        val accepted = editor?.let {
            androidx.core.view.inputmethod.EditorInfoCompat.getContentMimeTypes(it)
        }.orEmpty()

        val supportsGif = accepted.any { it == "image/gif" || it == "image/*" || it == "*/*" }
        if (!supportsGif) {
            ic.commitText(gif.url, 1)
            toast("This field cannot take images — pasted the link instead")
            showPanel(Panel.NONE)
            return
        }

        // Downloaded and shared through the app's own FileProvider, because a remote URL cannot be
        // granted to another app: commitContent needs a content:// URI this app can permit.
        Thread({
            val uri = runCatching { cacheGif(gif.url) }.getOrNull()
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                if (uri == null) {
                    ic.commitText(gif.url, 1)
                    toast("Could not fetch that GIF — pasted the link instead")
                } else {
                    val description = android.content.ClipDescription("GIF", arrayOf("image/gif"))
                    val info = androidx.core.view.inputmethod.InputContentInfoCompat(
                        uri, description, null
                    )
                    androidx.core.view.inputmethod.InputConnectionCompat.commitContent(
                        ic, editor!!, info,
                        androidx.core.view.inputmethod.InputConnectionCompat
                            .INPUT_CONTENT_GRANT_READ_URI_PERMISSION,
                        null,
                    )
                }
                showPanel(Panel.NONE)
            }
        }, "writer-gif-send").apply { isDaemon = true; start() }
    }

    private fun cacheGif(url: String): android.net.Uri {
        val dir = java.io.File(cacheDir, "writer-gifs").apply { mkdirs() }
        val file = java.io.File(dir, "gif-${url.hashCode()}.gif")
        if (!file.exists()) {
            java.net.URL(url).openStream().use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return androidx.core.content.FileProvider.getUriForFile(
            this, "$packageName.fileprovider", file
        )
    }

    // ── Held backspace ─────────────────────────────────────────────────────

    /**
     * Repeats backspace while it is held, escalating from characters to whole words.
     *
     * ESCALATES BECAUSE A FIXED RATE IS ALWAYS WRONG. Character-by-character is right for a typo
     * and unbearable for a sentence; word-by-word from the start makes it impossible to remove one
     * letter. Holding longer signals a bigger intent, so the unit grows with the hold.
     */
    private fun startBackspaceRepeat() {
        stopBackspaceRepeat()
        val started = System.currentTimeMillis()

        val tick = object : Runnable {
            override fun run() {
                val ic = currentInputConnection ?: return
                val held = System.currentTimeMillis() - started

                if (held < WORD_DELETE_AFTER_MS) {
                    if (composing.isNotEmpty()) composing.setLength(composing.length - 1)
                    ic.deleteSurroundingText(1, 0)
                } else {
                    // A word plus the whitespace before it, so repeated deletes keep moving instead
                    // of stalling on the space between two words.
                    val before = ic.getTextBeforeCursor(120, 0)?.toString().orEmpty()
                    if (before.isEmpty()) return
                    val trimmed = before.trimEnd()
                    val cut = trimmed.indexOfLast { it.isWhitespace() }
                    val count = if (cut >= 0) before.length - cut - 1 else before.length
                    composing.setLength(0)
                    ic.deleteSurroundingText(count.coerceAtLeast(1), 0)
                }

                vibrate(null)
                val interval = if (held < WORD_DELETE_AFTER_MS) CHAR_REPEAT_MS else WORD_REPEAT_MS
                backspaceHandler.postDelayed(this, interval)
            }
        }
        backspaceRepeat = tick
        backspaceHandler.postDelayed(tick, FIRST_REPEAT_MS)
    }

    private fun stopBackspaceRepeat() {
        backspaceRepeat?.let { backspaceHandler.removeCallbacks(it) }
        backspaceRepeat = null
    }

    // ── Quick search ───────────────────────────────────────────────────────

    private fun openSearch() {
        if (searchActive) {
            closeSearch()
            return
        }
        searchActive = true
        searchBar.applyTheme(resolveDark())
        searchBar.open()
    }

    private fun closeSearch() {
        searchActive = false
        searchBar.close()
    }

    /**
     * Sends one key to the search bar. Returns true when it was consumed.
     *
     * Returns FALSE for the keys that should still act normally while searching — shift, symbols
     * and the theme toggle change how the keyboard itself behaves, and swallowing them would make
     * it impossible to type a capital letter or a digit into the query.
     */
    private fun routeToSearch(key: Key): Boolean = when (key.action) {
        Key.Action.CHARACTER -> {
            searchBar.append(key.output)
            if (shifted) {
                shifted = false
                refreshLayout()
            }
            true
        }
        Key.Action.SPACE -> { searchBar.append(" "); true }
        Key.Action.BACKSPACE -> { searchBar.backspace(); true }
        Key.Action.RETURN -> { searchBar.submit(); true }
        Key.Action.SEARCH -> { closeSearch(); true }
        else -> false
    }

    /**
     * Runs the query and types the first result into the field the user was already in.
     *
     * OFF THE MAIN THREAD, because this can crawl. [PrismUrlQuery] starts a background crawl when
     * the query is an unindexed address, and even a plain lookup touches the index — a keyboard
     * that blocks its own thread freezes the host app, not just itself.
     */
    private fun runQuickSearch(query: String) {
        Thread({
            val outcome = runCatching { PrismUrlQuery.search(query, limit = 5) }
                .onFailure { PrismLogger.logError(TAG, "Quick search failed", it) }
                .getOrNull()

            val first = outcome?.results?.firstOrNull()
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                searchBar.finish()
                closeSearch()

                if (first == null) {
                    toast("No results for \"$query\"")
                    return@post
                }
                // THE TITLE, NOT THE LINK — unless the field being typed into is an address bar.
                // Someone searching inside a message wants the answer; someone searching inside a
                // browser wants something they can navigate to, and pasting prose into an address
                // bar is useless. See [typingIntoBrowser].
                val insert = if (typingIntoBrowser()) first.url else first.title.ifBlank { first.url }
                currentInputConnection?.commitText(insert, 1)
                if (outcome.seeded) {
                    toast("Not indexed yet — crawling it now")
                }
            }
        }, "writer-quick-search").apply { isDaemon = true; start() }
    }

    /**
     * Whether the field being typed into is a browser's address bar.
     *
     * Decided from the EDITOR, not from a list of package names. `TYPE_TEXT_VARIATION_URI` is what
     * an address bar declares, and it is declared by every browser rather than only the ones
     * somebody remembered to name — which is what "any browser, not just Prism's" requires. The
     * package check is a fallback for browsers that leave the variation unset.
     */
    private fun typingIntoBrowser(): Boolean {
        val editor = currentInputEditorInfo ?: return false

        val variation = editor.inputType and android.text.InputType.TYPE_MASK_VARIATION
        if (variation == android.text.InputType.TYPE_TEXT_VARIATION_URI) return true

        val pkg = editor.packageName.orEmpty().lowercase()
        return pkg.contains("browser") || pkg.contains("chrome") || pkg.contains("firefox") ||
            pkg.contains("opera") || pkg.contains("brave") || pkg.contains("edge") ||
            pkg.contains("duckduckgo") || pkg.contains("samsung.android.sbrowser")
    }

    // ── Speech, translation and AI ─────────────────────────────────────────

    /**
     * Dictation, optionally followed by translation.
     *
     * The recogniser is Android's, which means it works offline on devices that ship an offline
     * model and falls back to the network on those that do not -- Prism does not get a say in that.
     */
    private fun startDictation(translate: Boolean) {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            PrismLogger.logWarning(TAG, "No speech recogniser is available on this device")
            toast("No speech recogniser on this device")
            return
        }

        // CHECKED HERE, BECAUSE THE RECOGNISER'S OWN ANSWER IS USELESS. Without the runtime grant it
        // fails asynchronously with ERROR_INSUFFICIENT_PERMISSIONS (9) and no dialog, which is
        // indistinguishable from a broken key. A keyboard cannot request a permission itself -- it
        // is a Service -- so this hands off to an Activity that can.
        if (!WriterPermissionActivity.hasMicrophone(this)) {
            PrismLogger.logInfo(TAG, "Microphone not granted; asking for it")
            WriterPermissionActivity.request(this)
            return
        }
        runCatching { speechRecognizer?.destroy() }

        val recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer = recognizer
        recognizer.setRecognitionListener(object : android.speech.RecognitionListener {
            override fun onResults(results: android.os.Bundle?) {
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                if (text.isBlank()) return
                if (translate) translateAndCommit(text) else currentInputConnection?.commitText(text, 1)
            }

            override fun onError(error: Int) {
                // NAMED, NOT NUMBERED. "code 9" told nobody anything; each of these has a different
                // remedy and the user is the only one who can apply most of them.
                val reason = when (error) {
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "the network timed out"
                    SpeechRecognizer.ERROR_NETWORK -> "there is no network"
                    SpeechRecognizer.ERROR_AUDIO -> "the microphone could not be read"
                    SpeechRecognizer.ERROR_SERVER -> "the speech server refused"
                    SpeechRecognizer.ERROR_CLIENT -> "the recogniser rejected the request"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "nothing was said"
                    SpeechRecognizer.ERROR_NO_MATCH -> "nothing was recognised"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "the recogniser is busy"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "the microphone is not granted"
                    else -> "error $error"
                }
                PrismLogger.logWarning(TAG, "Speech recognition failed: $reason (code $error)")

                when (error) {
                    // Worth prompting again: the grant can be revoked while the keyboard lives.
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                        WriterPermissionActivity.request(this@PrismWriterService)
                    // Silence is not a failure worth interrupting anyone about.
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT, SpeechRecognizer.ERROR_NO_MATCH -> Unit
                    else -> toast("Dictation failed: $reason")
                }
            }

            override fun onReadyForSpeech(params: android.os.Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onPartialResults(partialResults: android.os.Bundle?) = Unit
            override fun onEvent(eventType: Int, params: android.os.Bundle?) = Unit
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
        }
        runCatching { recognizer.startListening(intent) }
            .onFailure { PrismLogger.logError(TAG, "Could not start dictation", it) }
    }

    /**
     * Translates the selection, or the sentence before the cursor if nothing is selected.
     *
     * The fallback matters more than the selection case: selecting text inside another app's field
     * is fiddly, and the common intent is "translate what I just wrote". A sentence is the unit that
     * matches that, so the search walks back to the last terminator rather than grabbing a fixed
     * number of characters and cutting a word in half.
     */
    private fun translateExistingText() {
        val ic = currentInputConnection ?: return

        val selected = ic.getSelectedText(0)?.toString().orEmpty()
        if (selected.isNotBlank()) {
            ic.commitText("", 1)              // replaces the selection with the translation below
            translateAndCommit(selected)
            return
        }

        // 512 is generous for one sentence and cheap; the walk below decides what is actually used.
        val before = ic.getTextBeforeCursor(512, 0)?.toString().orEmpty()
        if (before.isBlank()) {
            toast("Nothing to translate — type something first")
            return
        }

        val trimmed = before.trimEnd()
        val start = trimmed.dropLast(1).indexOfLast { it == '.' || it == '!' || it == '?' || it == '\n' }
        val sentence = if (start >= 0) trimmed.substring(start + 1).trim() else trimmed
        if (sentence.isBlank()) {
            toast("Nothing to translate — type something first")
            return
        }

        // The sentence plus whatever whitespace followed it, and nothing more. `sentence` sits at
        // the end of `trimmed` by construction -- trimmed was already right-trimmed, so trimming it
        // again only removed leading space -- which makes this count exact. An arithmetic slip here
        // deletes into the PREVIOUS sentence, and the user sees their text quietly eaten.
        val trailingSpace = before.length - trimmed.length
        ic.deleteSurroundingText(sentence.length + trailingSpace, 0)
        translateAndCommit(sentence)
    }

    /** A toast from a keyboard: the only feedback surface an IME reliably has. */
    private fun toast(message: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            runCatching {
                android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Translates, types the result, and optionally speaks it.
     *
     * Runs off the main thread: a local model can take seconds, and a keyboard that freezes takes
     * the whole host app down with it.
     */
    private fun translateAndCommit(text: String) {
        val target = PrismSettings.getWriterTranslateTarget()
        Thread({
            val translated = runCatching {
                LocalAi.generator.generate(
                    PrismSettings.getLocalAiModelPath(),
                    "Translate the following into $target. Reply with ONLY the translation, " +
                        "no notes and no quotation marks.\n\n$text",
                ).trim().replace(Regex("(?is)<think>.*?</think>"), "").trim()
            }.getOrNull()

            if (translated.isNullOrBlank()) {
                PrismLogger.logWarning(TAG, "Translation produced nothing; leaving the text alone")
                return@Thread
            }

            keyboardView.post {
                currentInputConnection?.commitText(translated, 1)
                if (PrismSettings.getWriterSpeakTranslation()) speak(translated, target)
            }
        }, "prism-writer-translate").start()
    }

    /**
     * Speaks the translation in the target language.
     *
     * The voice must match the language or the output is unintelligible -- an English voice reading
     * Spanish is not Spanish. When no voice for that language is installed, the text is still typed
     * and only the speech is skipped.
     */
    private fun speak(text: String, targetLanguage: String) {
        val engine = tts ?: return
        val locale = localeFor(targetLanguage)
        val supported = runCatching { engine.setLanguage(locale) }.getOrDefault(TextToSpeech.LANG_MISSING_DATA)
        if (supported == TextToSpeech.LANG_MISSING_DATA || supported == TextToSpeech.LANG_NOT_SUPPORTED) {
            PrismLogger.logWarning(TAG, "No installed voice for $targetLanguage; text typed but not spoken")
            return
        }
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "prism-writer")
    }

    /** Best-effort language name to locale. Falls back to the device's own. */
    private fun localeFor(name: String): Locale =
        Locale.getAvailableLocales().firstOrNull {
            it.getDisplayLanguage(Locale.ENGLISH).equals(name.trim(), ignoreCase = true)
        } ?: Locale.getDefault()

    // ── Haptics ────────────────────────────────────────────────────────────

    /**
     * A short tick per key, stronger for the ones that change state.
     *
     * Backspace, shift and return do something structural, and a slightly firmer tick is how a
     * finger tells them apart without looking. Zero in settings turns all of it off.
     */
    private fun vibrate(key: Key?) {
        val base = PrismSettings.getWriterHapticsMs()
        if (base <= 0) return

        val millis = when (key?.action) {
            Key.Action.BACKSPACE, Key.Action.SHIFT, Key.Action.RETURN,
            Key.Action.SYMBOLS, Key.Action.THEME -> (base * 1.6f).toInt()
            null -> (base * 2f).toInt()      // a completed glide
            else -> base
        }.coerceIn(1, 80)

        runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            if (!vibrator.hasVibrator()) return
            vibrator.vibrate(
                VibrationEffect.createOneShot(
                    millis.toLong(),
                    VibrationEffect.DEFAULT_AMPLITUDE,
                )
            )
        }
    }
}
