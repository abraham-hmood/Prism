package com.prism.launcher.characters

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.prism.launcher.PrismBaseActivity
import com.prism.launcher.PrismLogger
import com.prism.launcher.aether.AetherModelView
import com.prism.launcher.PrismSettings
import com.prism.launcher.messaging.AiManager
import com.prism.launcher.nora.IosUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Talking to one user-made character, in front of its own backdrop.
 *
 * Built the same way as Aether's conversation because it has the same problem to solve: a chat that
 * has to sit in front of a live 3D surface without either one obscuring the other. The model view,
 * the punch-through background and the hide-the-chat toggle are the same components, pointed at an
 * imported model instead of the bundled one.
 */
class CharacterConversationActivity : PrismBaseActivity() {

    companion object {
        const val EXTRA_ID = "character_id"
    }

    private lateinit var character: CharacterStore.Character

    private var modelView: AetherModelView? = null
    private var modelStageVisible = false
    private lateinit var messages: RecyclerView
    private lateinit var input: EditText
    private lateinit var toggle: TextView
    private val adapter = TranscriptAdapter()

    /**
     * The same context pill Sam's thread has: how full the local model's window is, the tokens in
     * it and the live decode rate, and a tap compacts the older part of this character's chat.
     */
    private lateinit var meter: com.prism.launcher.messaging.SamContextMeterView
    private var meterJob: kotlinx.coroutines.Job? = null

    /** A reply is being generated; compaction and a second send wait for it. */
    private var busy = false
    private var compacting = false

    /** This character's recap and conversation, kept apart from Sam's and every other character's. */
    private val conversationKey: String get() = "character:${character.id}"

    private val exitModelStage = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = setModelStageVisible(false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val id = intent.getStringExtra(EXTRA_ID).orEmpty()
        val found = CharacterStore.find(id)
        if (found == null) {
            setContentView(TextView(this).apply {
                text = "That character no longer exists."
                textSize = 16f
                gravity = Gravity.CENTER
            })
            return
        }
        character = found

        val stack = FrameLayout(this)

        // ── Backdrop, behind everything ────────────────────────────────────
        if (character.hasImage) {
            stack.addView(
                ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    runCatching {
                        setImageBitmap(
                            android.graphics.BitmapFactory.decodeFile(character.imagePath)
                        )
                    }.onFailure {
                        PrismLogger.logError("Characters", "Could not read the backdrop image", it)
                    }
                },
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                )
            )
        } else if (character.hasModel) {
            val stage = AetherModelView(this).apply {
                setBackgroundTint(IosUi.groupedBackground(this@CharacterConversationActivity))
                setAccent(IosUi.accent(this@CharacterConversationActivity))
                setModelFile(File(character.modelPath!!))
                activate()
            }
            modelView = stage
            stack.addView(
                stage,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                )
            )
        }

        // ── Chat column, in front ──────────────────────────────────────────
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // TRANSPARENT ON PURPOSE when there is a backdrop: a column painting the page
            // background is drawn after the surface behind it and would hide it completely.
            setBackgroundColor(
                if (character.hasModel || character.hasImage) Color.TRANSPARENT
                else IosUi.groupedBackground(this@CharacterConversationActivity)
            )
            fitsSystemWindows = true
        }
        column.addView(buildHeader())

        messages = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@CharacterConversationActivity).apply {
                stackFromEnd = true
            }
            adapter = this@CharacterConversationActivity.adapter
            setPadding(IosUi.dp(this@CharacterConversationActivity, 12f), 0, IosUi.dp(this@CharacterConversationActivity, 12f), 0)
            clipToPadding = false
        }
        column.addView(
            messages,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        // Where Sam's thread puts it: just above the input, at the end of the row.
        meter = com.prism.launcher.messaging.SamContextMeterView(this).apply {
            visibility = android.view.View.GONE
            setOnClickListener { compactNow() }
        }
        column.addView(
            meter,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.END
                marginEnd = IosUi.dp(this@CharacterConversationActivity, 12f)
                bottomMargin = IosUi.dp(this@CharacterConversationActivity, 2f)
            }
        )
        column.addView(buildComposeRow())

        stack.addView(
            column,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        )
        setContentView(stack)

        onBackPressedDispatcher.addCallback(this, exitModelStage)
        loadTranscript()
        primeBackend()
        // A previous chat's numbers would be wrong here; an empty ring that fills as the first
        // reply streams is the honest starting state, as in Sam's thread.
        com.prism.launcher.messaging.GgufInferenceService.resetLiveStats()
        showMeter()
    }

    // ── The context pill ───────────────────────────────────────────────────

    /**
     * Shown on exactly the condition Sam's is: a character that answers through Sam's engine, on a
     * local model. Nora and Aether keep their own conversations, CakeChat has no context window of
     * that kind, and a cloud model's window is not on this device to measure.
     */
    private fun meterApplies(): Boolean =
        character.backend == CharacterStore.Backend.SAM &&
            com.prism.launcher.messaging.ChatContext.isLocal() &&
            !cakeChatAnswers()

    private fun cakeChatAnswers(): Boolean =
        character.backend == CharacterStore.Backend.SAM &&
            PrismSettings.getUseCakeChat() &&
            com.prism.launcher.cakechat.CakeChatEngine.isReady(applicationContext)

    private fun showMeter() {
        meter.visibility = if (meterApplies()) android.view.View.VISIBLE else android.view.View.GONE
        refreshMeter()
    }

    private fun refreshMeter() {
        if (meter.visibility != android.view.View.VISIBLE) return
        val stats = com.prism.launcher.messaging.GgufInferenceService.liveStats
        meter.update(
            stats.contextFraction,
            stats.contextUsed,
            com.prism.launcher.messaging.GgufInferenceService.liveTokensPerSecond,
            stats.contextSize,
        )
    }

    private fun startMeter() {
        if (meter.visibility != android.view.View.VISIBLE) return
        com.prism.launcher.messaging.GgufInferenceService.resetLiveStats()
        meterJob?.cancel()
        meterJob = lifecycleScope.launch(Dispatchers.Main) {
            while (true) {
                refreshMeter()
                kotlinx.coroutines.delay(250)
            }
        }
    }

    private fun stopMeter() {
        meterJob?.cancel()
        meterJob = null
        refreshMeter()
    }

    /** Tapping the pill: compact now, exactly as Sam's does. */
    private fun compactNow() {
        if (busy || compacting) {
            android.widget.Toast.makeText(this, "Wait for this reply to finish first.", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch { compact(manual = true) }
    }

    /**
     * Folds the older part of this character's chat into a recap stored under its own key.
     *
     * The lines are named by speaker -- "User:" and the character's name -- so the briefing knows
     * which side said what. They are the same non-blank lines the turn sends as history, so the
     * count the recap covers lines up with what is skipped on the next turn.
     */
    private suspend fun compact(manual: Boolean): Boolean {
        if (compacting) return false
        compacting = true
        return try {
            val named = adapter.conversation().map { (text, fromUser) ->
                if (fromUser) "User: $text" else "${character.name}: $text"
            }
            val outcome = withContext(Dispatchers.IO) {
                com.prism.launcher.messaging.ChatContext.compact(applicationContext, conversationKey, named)
            }
            withContext(Dispatchers.Main) {
                refreshMeter()
                when (outcome) {
                    is com.prism.launcher.messaging.ChatContext.Outcome.Compacted -> android.widget.Toast.makeText(
                        this@CharacterConversationActivity,
                        com.prism.launcher.messaging.SamCompaction.describe(outcome.folded),
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                    is com.prism.launcher.messaging.ChatContext.Outcome.TooShort -> if (manual) {
                        android.widget.Toast.makeText(
                            this@CharacterConversationActivity,
                            "This chat is too short to compact yet.",
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    }
                    is com.prism.launcher.messaging.ChatContext.Outcome.Failed -> android.widget.Toast.makeText(
                        this@CharacterConversationActivity,
                        "Could not compact the conversation: ${outcome.reason}".take(220),
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
            }
            outcome is com.prism.launcher.messaging.ChatContext.Outcome.Compacted
        } finally {
            compacting = false
        }
    }

    /**
     * Restores this character's transcript, or inserts the greeting for a new one.
     *
     * The adapter used to be filled with the greeting on every open and nothing else was ever
     * read back, so leaving the screen erased the conversation. The store is keyed by character
     * id, so two characters on the same backend keep separate histories.
     */
    private fun loadTranscript() {
        lifecycleScope.launch {
            val stored = withContext(Dispatchers.IO) {
                CharacterChatStore.load(character.id)
            }
            if (stored.isEmpty()) {
                // Shown, never sent: it is a line ABOUT the character, and handing it to the model
                // as something the character said would put words in its mouth.
                adapter.append(Line(greeting(), fromUser = false, greeting = true))
            } else {
                stored.forEach { adapter.append(Line(it.text, it.fromUser)) }
            }
            if (adapter.itemCount > 0) messages.scrollToPosition(adapter.itemCount - 1)
        }
    }

    /**
     * Tells a session-keeping backend who it is, every time this chat is opened.
     *
     * ONLY for Nora and Aether. Each holds a single conversation that persists between screens, so
     * the framing sent here actually sticks for the session -- and it can be changed out from under
     * this screen by their own threads or by a different character on the same backend, which is
     * exactly why it is re-sent on every open rather than once ever.
     *
     * Sam is skipped because it keeps no session: priming it would be a wasted generation, and its
     * framing rides on every turn instead.
     */
    private fun primeBackend() {
        if (character.backend == CharacterStore.Backend.SAM) return
        val opening = CharacterPrompt.opening(character)
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                when (character.backend) {
                    CharacterStore.Backend.NORA ->
                        com.prism.launcher.nora.NoraService.sendChat(applicationContext, opening)
                    CharacterStore.Backend.AETHER ->
                        com.prism.launcher.aether.AetherService.sendChat(applicationContext, opening)
                    else -> Unit
                }
            }.onFailure {
                PrismLogger.logWarning(
                    "Characters", "Could not prime ${character.backend.label}: ${it.message}"
                )
            }
        }
    }

    private fun buildHeader(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(IosUi.cardBackground(this@CharacterConversationActivity))
        setPadding(IosUi.dp(this@CharacterConversationActivity, 16f), IosUi.dp(this@CharacterConversationActivity, 14f), IosUi.dp(this@CharacterConversationActivity, 16f), IosUi.dp(this@CharacterConversationActivity, 14f))

        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply {
                    text = character.name
                    textSize = 17f
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                    setTextColor(IosUi.label(context))
                })
                addView(TextView(context).apply {
                    text = character.backend.label
                    textSize = 12f
                    setTextColor(IosUi.secondaryLabel(context))
                })
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )

        // ONLY WITH A 3D MODEL. There is nothing to rotate behind a still image, and a button that
        // hides the conversation to reveal a static picture is a button that does nothing useful.
        toggle = IosUi.tintedButton(this@CharacterConversationActivity, "View").apply {
            visibility = if (character.hasModel) android.view.View.VISIBLE else android.view.View.GONE
            setPadding(IosUi.dp(this@CharacterConversationActivity, 14f), IosUi.dp(this@CharacterConversationActivity, 8f), IosUi.dp(this@CharacterConversationActivity, 14f), IosUi.dp(this@CharacterConversationActivity, 8f))
            setOnClickListener { setModelStageVisible(!modelStageVisible) }
        }
        addView(toggle)
    }

    private fun buildComposeRow(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(IosUi.cardBackground(this@CharacterConversationActivity))
        setPadding(IosUi.dp(this@CharacterConversationActivity, 12f), IosUi.dp(this@CharacterConversationActivity, 10f), IosUi.dp(this@CharacterConversationActivity, 12f), IosUi.dp(this@CharacterConversationActivity, 10f))

        input = EditText(context).apply {
            hint = "Message ${character.name}"
            textSize = 16f
            maxLines = 4
            background = IosUi.fieldBackground(context)
            setTextColor(IosUi.label(context))
            setHintTextColor(IosUi.tertiaryLabel(context))
            setPadding(IosUi.dp(context, 12f), IosUi.dp(context, 10f), IosUi.dp(context, 12f), IosUi.dp(context, 10f))
        }
        addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        addView(IosUi.filledButton(context, "Send").apply {
            setPadding(IosUi.dp(context, 18f), IosUi.dp(context, 10f), IosUi.dp(context, 18f), IosUi.dp(context, 10f))
            setOnClickListener { send() }
        })
    }

    /**
     * Swaps between the conversation and the character alone.
     *
     * The list goes INVISIBLE rather than GONE: it carries the layout weight, so removing it would
     * collapse the column and pull the compose row -- which holds the way back -- under the header.
     */
    private fun setModelStageVisible(visible: Boolean) {
        modelStageVisible = visible
        messages.visibility =
            if (visible) android.view.View.INVISIBLE else android.view.View.VISIBLE
        modelView?.setInteractive(visible)
        exitModelStage.isEnabled = visible
        toggle.text = if (visible) "Chat" else "View"
        if (visible) {
            (getSystemService(INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager)
                ?.hideSoftInputFromWindow(input.windowToken, 0)
        }
    }

    /** The opening line, which is about the character rather than from it. */
    private fun greeting(): String {
        val persona = character.description.trim()
        return if (persona.isEmpty()) "Say something to ${character.name}."
        else "${character.name} — " + persona.lines().joinToString(" ") { it.trim() }
    }

    /**
     * Sends one turn.
     *
     * THE PERSONA IS PREPENDED PER TURN rather than held as session state, because the backends do
     * not have per-character sessions -- Sam, Nora and Aether each keep one conversation of their
     * own. Restating who the character is on every turn is what keeps two characters on the same
     * backend from bleeding into each other.
     */
    private fun send() {
        val text = input.text.toString().trim()
        if (text.isEmpty()) return
        if (busy || compacting) {
            android.widget.Toast.makeText(this, "Wait for this reply to finish first.", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        // THE CONVERSATION BEFORE THIS TURN, both speakers, in order. Captured before the new
        // message is shown, because it is the history and the new message is this turn.
        val conversation = adapter.conversation()
        input.setText("")
        adapter.append(Line(text, fromUser = true))
        persist(text, fromUser = true)
        messages.scrollToPosition(adapter.itemCount - 1)

        // The full roleplay framing (see CharacterPrompt) leads every rebuilt context as the
        // persona, rather than being glued to each message: the whole conversation now goes with
        // every turn, and a framing repeated inside each line of it would be read back as dialogue.
        val persona = CharacterPrompt.preamble(character)

        // WHAT ANSWERS DECIDES WHAT IS SENT. CakeChat takes a list of utterances and no
        // instructions; every other backend takes one prompt and follows them. Checked here rather
        // than inside CharacterPrompt so the decision sits next to the call that depends on it.
        val cakeChatAnswers = cakeChatAnswers()
        val historyBeforeThisTurn = conversation.map { it.first }
        val characterDialog =
            if (cakeChatAnswers) CharacterPrompt.cakeChatDialog(character, historyBeforeThisTurn, text)
            else emptyList()

        // The reply streams into this bubble token by token when streaming is on in Settings, and
        // is filled in once at the end when it is not. Either way it is one row, updated in place.
        adapter.append(Line("…", fromUser = false, live = true))
        messages.scrollToPosition(adapter.itemCount - 1)
        val streamed = StringBuilder()
        busy = true
        startMeter()

        lifecycleScope.launch {
            val reply = withContext(Dispatchers.IO) {
                runCatching {
                    when (character.backend) {
                        CharacterStore.Backend.SAM ->
                            if (cakeChatAnswers) {
                                // The description sits first and last in [characterDialog]; the
                                // message it should reply to is the second-to-last entry, so it is
                                // passed as `userText` with the rest as history.
                                AiManager.getResponse(
                                    applicationContext,
                                    characterDialog.last(),
                                    history = characterDialog.dropLast(1),
                                ).first
                            } else {
                                // Compact first when the whole conversation would not fit, or the
                                // last turn ended with the window nearly full -- the same rule as
                                // Sam's thread, under this character's own recap.
                                val covered = com.prism.launcher.messaging.SamCompaction.foldedTurns(conversationKey)
                                val recapNow = com.prism.launcher.messaging.SamCompaction.recap(conversationKey)
                                if (com.prism.launcher.messaging.ChatContext.shouldCompact(
                                        historyBeforeThisTurn.drop(covered), recapNow, persona,
                                    )
                                ) {
                                    compact(manual = false)
                                }
                                AiManager.getResponse(
                                    applicationContext,
                                    text,
                                    onToken = { delta ->
                                        streamed.append(delta)
                                        val sofar = streamed.toString()
                                        runOnUiThread { adapter.updateLive(sofar) }
                                    },
                                    history = historyBeforeThisTurn,
                                    recap = com.prism.launcher.messaging.SamCompaction.recap(conversationKey),
                                    conversationKey = conversationKey,
                                    speakers = conversation.map { it.second },
                                    assistantName = character.name,
                                    persona = persona,
                                    recapCovers = com.prism.launcher.messaging.SamCompaction.foldedTurns(conversationKey),
                                ).first
                            }
                        CharacterStore.Backend.NORA -> {
                            com.prism.launcher.nora.NoraService.sendChat(applicationContext, CharacterPrompt.turn(character, text))
                            "Sent to Nora — her reply appears in her own thread. " +
                                "Per-character sessions for Nora are not built yet."
                        }
                        CharacterStore.Backend.AETHER -> {
                            com.prism.launcher.aether.AetherService.sendChat(applicationContext, CharacterPrompt.turn(character, text))
                            "Sent to Aether — her reply appears in her own thread. " +
                                "Per-character sessions for Aether are not built yet."
                        }
                    }
                }.getOrElse { "Error: ${it.message}" }
            }
            busy = false
            stopMeter()
            // A FAILURE IS SHOWN, NOT KEPT. Stored as the character's reply it would look like the
            // character said it, and the next turn would feed the error back in as dialogue --
            // the same rule Sam's thread follows.
            val trimmed = reply.trim()
            val failed = trimmed.startsWith("Error:") || trimmed.startsWith("P2P Model Error:") ||
                trimmed.startsWith("Local AI Error:")
            if (failed) {
                adapter.removeLive()
                android.widget.Toast.makeText(this@CharacterConversationActivity, trimmed.take(220), android.widget.Toast.LENGTH_LONG).show()
                return@launch
            }
            adapter.finishLive(reply)
            persist(reply, fromUser = false)
            messages.scrollToPosition(adapter.itemCount - 1)
        }
    }

    /** Writes one line outside the UI thread; a transcript must survive leaving the screen. */
    private fun persist(text: String, fromUser: Boolean) {
        val id = character.id
        lifecycleScope.launch(Dispatchers.IO) {
            CharacterChatStore.append(id, CharacterChatStore.Line(text, fromUser = fromUser))
        }
    }

    override fun onResume() {
        super.onResume()
        modelView?.onResume()
    }

    override fun onPause() {
        super.onPause()
        modelView?.onPause()
    }

    /**
     * One row of the transcript. [greeting] marks the opening line, which is shown but is not part
     * of the conversation; [live] marks the reply still being written.
     */
    private data class Line(
        val text: String,
        val fromUser: Boolean,
        val greeting: Boolean = false,
        val live: Boolean = false,
    )

    private inner class TranscriptAdapter : RecyclerView.Adapter<TranscriptAdapter.VH>() {
        private val lines = mutableListOf<Line>()

        fun append(line: Line) {
            lines.add(line)
            notifyItemInserted(lines.size - 1)
        }

        /**
         * The conversation so far, oldest first, as (text, said by the user): both speakers, without
         * the greeting or a half-written reply, and without blank lines -- the same lines the
         * compaction plan counts, so a recap's coverage lines up with what is skipped.
         */
        fun conversation(): List<Pair<String, Boolean>> = lines
            .filter { !it.greeting && !it.live && it.text.isNotBlank() }
            .map { it.text.trim() to it.fromUser }

        /** Replaces the live reply's text as tokens arrive; the row is updated in place. */
        fun updateLive(text: String) {
            val index = lines.indexOfLast { it.live }
            if (index < 0) return
            lines[index] = lines[index].copy(text = text.ifEmpty { "…" })
            notifyItemChanged(index)
            messages.scrollToPosition(lines.size - 1)
        }

        /** The finished reply takes the live row's place. */
        fun finishLive(text: String) {
            val index = lines.indexOfLast { it.live }
            if (index < 0) {
                append(Line(text, fromUser = false))
                return
            }
            lines[index] = Line(text, fromUser = false)
            notifyItemChanged(index)
        }

        fun removeLive() {
            val index = lines.indexOfLast { it.live }
            if (index < 0) return
            lines.removeAt(index)
            notifyItemRemoved(index)
        }

        /**
         * A full-width row holding one bubble.
         *
         * THE ROW, NOT THE BUBBLE, IS THE LIST ITEM. The bubble used to be the item itself at wrap
         * width, so every bubble sat at the left edge whoever sent it -- `gravity = END` only moved
         * the text inside it. In a full-width row the bubble's layout gravity is what puts the
         * user's messages on the right and the character's on the left.
         */
        inner class VH(val row: FrameLayout, val bubble: TextView) : RecyclerView.ViewHolder(row)

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
            val ctx = parent.context
            val row = FrameLayout(ctx).apply {
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT,
                ).apply {
                    val margin = IosUi.dp(ctx, 4f)
                    setMargins(0, margin, 0, margin)
                }
            }
            val bubble = TextView(ctx).apply {
                textSize = 16f
                setPadding(IosUi.dp(ctx, 14f), IosUi.dp(ctx, 10f), IosUi.dp(ctx, 14f), IosUi.dp(ctx, 10f))
                // A bubble never spans the screen: the gap on the far side is what makes the side
                // it sits on readable at a glance.
                maxWidth = (ctx.resources.displayMetrics.widthPixels * 0.78f).toInt()
            }
            row.addView(
                bubble,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
            return VH(row, bubble)
        }

        override fun getItemCount(): Int = lines.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val line = lines[position]
            val ctx = holder.bubble.context
            holder.bubble.text = line.text
            holder.bubble.setTextColor(
                if (line.fromUser) Color.WHITE else IosUi.label(ctx)
            )
            holder.bubble.background = IosUi.filledBackground(
                ctx, if (line.fromUser) IosUi.accent(ctx) else IosUi.cardBackground(ctx)
            )
            (holder.bubble.layoutParams as FrameLayout.LayoutParams).let { lp ->
                lp.gravity = if (line.fromUser) Gravity.END else Gravity.START
                holder.bubble.layoutParams = lp
            }
            // The character's own voice: long-press one of their replies to hear it in the voice
            // chosen in the character creator.
            if (line.fromUser || line.live) {
                holder.bubble.setOnLongClickListener(null)
            } else {
                holder.bubble.setOnLongClickListener {
                    com.prism.launcher.speech.VoiceChoices.speak(line.text, character.voice)
                    android.widget.Toast.makeText(ctx, "Reading aloud as ${character.name}", android.widget.Toast.LENGTH_SHORT).show()
                    true
                }
            }
        }
    }
}
