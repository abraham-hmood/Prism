package com.prism.launcher.imagecraft

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.prism.launcher.PrismSettings
import com.prism.launcher.messaging.ImageCraft
import com.prism.launcher.nora.IosUi
import com.prism.launcher.platform.AndroidImageCodec
import com.prism.launcher.theme.PrismThemes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * ImageCraft as a page of the launcher: one picture, one field, one button, one wand.
 *
 * ## What the page does, and what it deliberately does not
 *
 * The image is the result and the reason the page exists, so it holds the space; the field sits under
 * it, within thumb reach, with Draw directly beneath -- the arrangement every messaging app uses,
 * which is where a user's hands already are. The wand beside the field is the only writing the text
 * model still does here: it rewrites what is IN the field, on request, where the user can read it
 * before anything is drawn. Nothing is rewritten behind their back, which is why the subtitle says
 * the words in the field are the words that get drawn.
 *
 * ## Theme
 *
 * Painted from [PrismThemes] -- the same palette every other page uses -- rather than from the fixed
 * iOS surface colours this page used to take. The launcher rebuilds pages when the appearance
 * changes, so the palette read at construction is the one the user selected.
 */
class ImageCraftPageView(private val context: Context) : FrameLayout(context) {

    private val palette = PrismThemes.modeFor(IosUi.isDark(context)).palette
    private var scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var watch: Job? = null
    private var enhancing = false

    private val image = ImageView(context).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
        setBackgroundColor(palette.raised.toInt())
        contentDescription = "The picture ImageCraft drew"
    }
    private val placeholder = TextView(context).apply {
        text = "Nothing drawn yet."
        setTextColor(palette.faint.toInt())
        textSize = 14f
        gravity = Gravity.CENTER
    }
    private val progress = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 100
        visibility = View.GONE
        progressTintList = android.content.res.ColorStateList.valueOf(palette.accent.toInt())
        progressBackgroundTintList =
            android.content.res.ColorStateList.valueOf(palette.divider.toInt())
    }
    private val stage = TextView(context).apply {
        setTextColor(palette.muted.toInt())
        textSize = 13f
        setPadding(0, dp(8f), 0, 0)
    }
    private val note = TextView(context).apply {
        setTextColor(palette.faint.toInt())
        textSize = 12f
        setPadding(0, dp(4f), 0, 0)
    }
    private val gateView = TextView(context).apply {
        setTextColor(palette.destructive.toInt())
        textSize = 13f
        visibility = View.GONE
    }
    private val prompt = EditText(context).apply {
        hint = "Describe the picture you want"
        setTextColor(palette.text.toInt())
        setHintTextColor(palette.faint.toInt())
        textSize = 16f
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        minLines = 2
        maxLines = 5
        background = rounded(palette.raised.toInt(), 12f)
        setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
    }
    private val action = pill("Draw", accent = true)
    private val secondary = pill("Stop")
    private val enhance = TextView(context).apply {
        text = ""
        gravity = Gravity.CENTER
        background = rounded(palette.raised.toInt(), 12f)
        setPadding(dp(10f), dp(10f), dp(10f), dp(10f))
        val icon = androidx.core.content.ContextCompat.getDrawable(
            context, com.prism.launcher.R.drawable.ic_wand_24,
        )
        setCompoundDrawablesWithIntrinsicBounds(null, icon, null, null)
        compoundDrawableTintList =
            android.content.res.ColorStateList.valueOf(palette.accent.toInt())
        contentDescription = "Rewrite the prompt with your text model"
    }

    init {
        setBackgroundColor(palette.background.toInt())

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(14f), dp(16f), dp(16f))
        }

        column.addView(
            TextView(context).apply {
                text = "ImageCraft"
                setTextColor(palette.text.toInt())
                textSize = 26f
                setPadding(0, 0, 0, dp(2f))
            },
        )
        column.addView(
            TextView(context).apply {
                text = "The words in the field are what gets drawn. Resolution: " +
                    "${PrismSettings.getImageCraftSize().label} · the wand rewrites them with " +
                    "your text model before you draw."
                setTextColor(palette.faint.toInt())
                textSize = 12f
                setPadding(0, 0, 0, dp(12f))
            },
        )

        // THE PICTURE, IN A SQUARE THAT SHRINKS TO FIT. A fixed-aspect container keeps the image from
        // pushing the field off the bottom of a short screen, which is what a plain ImageView with
        // wrap_content does as soon as a portrait render arrives.
        val frame = FrameLayout(context).apply {
            background = rounded(palette.card.toInt(), 16f)
            addView(
                image,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT).apply {
                    setMargins(dp(8f), dp(8f), dp(8f), dp(8f))
                },
            )
            addView(
                placeholder,
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER),
            )
        }
        column.addView(
            frame,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(360f),
            ),
        )

        column.addView(progress)
        column.addView(stage)
        column.addView(note)
        column.addView(gateView)

        // THE FIELD AND ITS WAND, side by side. The wand is an icon button rather than a labelled one
        // because it acts on the field it sits beside, and a sentence-long button next to a text box
        // reads as a second action rather than as a tool for the first.
        val inputRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
        }
        inputRow.addView(
            prompt,
            LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f,
            ),
        )
        inputRow.addView(
            enhance,
            LinearLayout.LayoutParams(dp(52f), dp(52f)).apply { leftMargin = dp(8f) },
        )
        column.addView(
            inputRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12f) },
        )

        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        buttons.addView(action, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        buttons.addView(
            secondary,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { leftMargin = dp(8f) },
        )
        column.addView(
            buttons,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10f) },
        )

        val scroll = ScrollView(context).apply { addView(column) }
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        action.setOnClickListener { start() }
        secondary.setOnClickListener { ImageCraftService.cancel(context) }
        secondary.visibility = View.GONE
        enhance.setOnClickListener { enhancePrompt() }

        render(ImageCraft.state.value)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // THE SCOPE IS REBUILT ON EVERY ATTACH. Detaching cancels it so a page that scrolled away
        // stops collecting; without putting a fresh one in its place, a page that came back never saw
        // another render update and the wand stopped answering. Then: a run that started before this
        // page existed -- the notification's tap target opens the launcher fresh -- has to be visible
        // immediately, so the current state is rendered first and only then followed.
        if (scope.coroutineContext[Job]?.isActive != true) {
            scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        }
        render(ImageCraft.state.value)
        watch?.cancel()
        watch = scope.launch {
            ImageCraft.state.collect { state -> render(state) }
        }
    }

    override fun onDetachedFromWindow() {
        watch?.cancel()
        watch = null
        scope.cancel()
        super.onDetachedFromWindow()
    }

    private fun start() {
        val text = prompt.text.toString().trim()
        if (text.isEmpty()) {
            stage.text = "Type what you want drawn first."
            return
        }
        val gate = ImageCraft.gate()
        if (!gate.ready) {
            gateView.text = gate.reason
            gateView.visibility = View.VISIBLE
            return
        }
        gateView.visibility = View.GONE
        // THE SERVICE OWNS THE RUN, not this page: it is what keeps the process alive and the
        // notification on screen when the page is swiped away, and it is the single entry point so a
        // run cannot be started twice from two places.
        ImageCraftService.start(context, text)
    }

    /**
     * Asks the text model to rewrite the field's contents, in place.
     *
     * REPLACES THE FIELD, not the render: the user asked for better words, so the words are what
     * changes, and Draw still has to be pressed -- which is the entire point of the button existing
     * separately from the run. Nothing usable came back (no text model, an empty reply) leaves the
     * field untouched and says so, because quietly drawing the old words would look like it worked.
     */
    private fun enhancePrompt() {
        if (enhancing) return
        val typed = prompt.text.toString().trim()
        if (typed.isEmpty()) {
            stage.text = "Type a prompt, then press the wand to have it rewritten."
            return
        }
        if (!ImageCraft.canEnhance()) {
            Toast.makeText(
                context,
                "No text model is active, so there is nothing to enhance with",
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        enhancing = true
        enhance.alpha = 0.4f
        stage.text = "Rewriting your prompt…"
        scope.launch {
            val rewritten = runCatching { ImageCraft.enhance(typed) }.getOrNull()
            enhancing = false
            enhance.alpha = 1f
            if (rewritten.isNullOrBlank()) {
                stage.text = "The text model had nothing to add — your words are unchanged."
                return@launch
            }
            prompt.setText(rewritten)
            prompt.setSelection(prompt.text.length)
            stage.text = "Rewritten. Edit it, or press Draw."
        }
    }

    /** Paints one [ImageCraft.State]. Idempotent, because it is called on every emission. */
    private fun render(state: ImageCraft.State) {
        val gate = ImageCraft.gate()
        action.isEnabled = !state.running && gate.ready
        action.alpha = if (action.isEnabled) 1f else 0.4f
        secondary.visibility = if (state.running) View.VISIBLE else View.GONE
        val canEnhance = ImageCraft.canEnhance() && !enhancing
        enhance.isEnabled = canEnhance
        enhance.alpha = if (canEnhance) 1f else 0.4f

        gateView.text = if (gate.ready) "" else gate.reason
        gateView.visibility = if (gate.ready) View.GONE else View.VISIBLE

        progress.visibility = if (state.running) View.VISIBLE else View.GONE
        if (state.running) {
            if (state.progress < 0f) {
                progress.isIndeterminate = true
            } else {
                progress.isIndeterminate = false
                progress.progress = (state.progress.coerceIn(0f, 1f) * 100).toInt()
            }
        }

        stage.text = when {
            state.running -> state.stage
            state.error != null -> "Failed: ${state.error}"
            state.complete -> buildString {
                append("Drawn in ")
                append("%.1fs".format(state.millis / 1000.0))
                if (state.engine.isNotBlank()) append(" with ${state.engine}")
                if (state.savedTo.isNotBlank()) append(" — saved to ${state.savedTo}")
            }
            state.stage.isNotEmpty() -> state.stage
            else -> ""
        }

        note.text = state.note
        note.visibility = if (state.note.isBlank()) View.GONE else View.VISIBLE

        val picture = state.image
        if (picture != null) {
            val bitmap = runCatching { AndroidImageCodec.toBitmap(picture) }.getOrNull()
            if (bitmap != null) {
                image.setImageBitmap(bitmap)
                image.visibility = View.VISIBLE
                placeholder.visibility = View.GONE
            }
        } else if (!state.running) {
            image.visibility = View.GONE
            placeholder.visibility = View.VISIBLE
        }
    }

    /** A rounded pill button in the active palette, the same shape the other pages use. */
    private fun pill(text: String, accent: Boolean = false): TextView = TextView(context).apply {
        this.text = text
        textSize = 14f
        gravity = Gravity.CENTER
        setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
        setTextColor(if (accent) palette.onAccent.toInt() else palette.text.toInt())
        background = rounded(
            if (accent) palette.accent.toInt() else palette.raised.toInt(), 12f,
        )
        isClickable = true
        isFocusable = true
    }

    private fun rounded(color: Int, radiusDp: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }

    private fun dp(value: Float): Int = IosUi.dp(context, value)
}
