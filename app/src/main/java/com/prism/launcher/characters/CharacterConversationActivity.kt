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

    private val exitModelStage = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = setModelStageVisible(false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val id = intent.getStringExtra(EXTRA_ID).orEmpty()
        val found = CharacterStore.find(this, id)
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
        adapter.append(Line(greeting(), fromUser = false))
        primeBackend()
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
        input.setText("")
        adapter.append(Line(text, fromUser = true))
        messages.scrollToPosition(adapter.itemCount - 1)

        // The full roleplay framing, not just the description. See CharacterPrompt for why it is
        // restated on every turn rather than established once.
        val prompt = CharacterPrompt.turn(character, text)

        // WHAT ANSWERS DECIDES WHAT IS SENT. CakeChat takes a list of utterances and no
        // instructions; every other backend takes one prompt and follows them. Checked here rather
        // than inside CharacterPrompt so the decision sits next to the call that depends on it.
        //
        // The transcript is captured BEFORE this turn's message was appended above -- it is the
        // history, and the new message is added by cakeChatDialog as the final turn.
        val historyBeforeThisTurn = adapter.transcript().dropLast(1)
        val cakeChatAnswers = character.backend == CharacterStore.Backend.SAM &&
            PrismSettings.getUseCakeChat() &&
            com.prism.launcher.cakechat.CakeChatEngine.isReady(applicationContext)
        val characterDialog =
            if (cakeChatAnswers) CharacterPrompt.cakeChatDialog(character, historyBeforeThisTurn, text)
            else emptyList()

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
                                AiManager.getResponse(applicationContext, prompt).first
                            }
                        CharacterStore.Backend.NORA -> {
                            com.prism.launcher.nora.NoraService.sendChat(applicationContext, prompt)
                            "Sent to Nora — her reply appears in her own thread. " +
                                "Per-character sessions for Nora are not built yet."
                        }
                        CharacterStore.Backend.AETHER -> {
                            com.prism.launcher.aether.AetherService.sendChat(applicationContext, prompt)
                            "Sent to Aether — her reply appears in her own thread. " +
                                "Per-character sessions for Aether are not built yet."
                        }
                    }
                }.getOrElse { "Something went wrong: ${it.message}" }
            }
            adapter.append(Line(reply, fromUser = false))
            messages.scrollToPosition(adapter.itemCount - 1)
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

    private data class Line(val text: String, val fromUser: Boolean)

    private inner class TranscriptAdapter : RecyclerView.Adapter<TranscriptAdapter.VH>() {
        private val lines = mutableListOf<Line>()

        fun append(line: Line) {
            lines.add(line)
            notifyItemInserted(lines.size - 1)
        }

        /** The transcript so far, oldest first -- both speakers, which is the shape CakeChat learnt. */
        fun transcript(): List<String> = lines.map { it.text }

        inner class VH(val bubble: TextView) : RecyclerView.ViewHolder(bubble)

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): VH {
            val ctx = parent.context
            val bubble = TextView(ctx).apply {
                textSize = 16f
                setPadding(IosUi.dp(ctx, 14f), IosUi.dp(ctx, 10f), IosUi.dp(ctx, 14f), IosUi.dp(ctx, 10f))
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.WRAP_CONTENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT,
                ).apply {
                    val margin = IosUi.dp(ctx, 4f)
                    setMargins(0, margin, 0, margin)
                }
            }
            return VH(bubble)
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
            (holder.bubble.layoutParams as RecyclerView.LayoutParams).let { lp ->
                holder.bubble.layoutParams = lp
            }
            holder.bubble.gravity = if (line.fromUser) Gravity.END else Gravity.START
        }
    }
}
