package com.prism.launcher.writer

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.prism.launcher.PrismSettings

/**
 * The quick-search field that slides out above the keys.
 *
 * ## Why it is a TextView and not an EditText
 *
 * An IME cannot sensibly type into its own EditText. Focus belongs to the field in the host app —
 * that is the entire relationship — and stealing it would either fail or detach the keyboard from
 * the thing it is meant to be typing into. So this DISPLAYS the query while
 * [PrismWriterService] routes its own keystrokes here instead of committing them, which is exactly
 * what "typing focuses the search box" needs to mean for a keyboard. The caret is drawn rather
 * than real, for the same reason.
 */
class WriterSearchBar(context: Context) : LinearLayout(context) {

    /** Called when the user commits the query, by return or by the send button. */
    var onSubmit: ((String) -> Unit)? = null

    /** Called when the user dismisses without searching. */
    var onDismiss: (() -> Unit)? = null

    private val field = TextView(context)
    private val send = TextView(context)
    private val query = StringBuilder()

    /** Set while a search is running, so the bar can say so instead of looking frozen. */
    private var busy = false

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val pad = dp(8)
        setPadding(pad, pad, pad, pad)

        field.apply {
            textSize = 16f
            setSingleLine()
            val inner = dp(12)
            setPadding(inner, dp(10), inner, dp(10))
        }
        addView(field, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        send.apply {
            textSize = 15f
            text = "Search"
            gravity = Gravity.CENTER
            val inner = dp(14)
            setPadding(inner, dp(10), inner, dp(10))
            setOnClickListener { submit() }
        }
        addView(send, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(8)
        })

        setOnClickListener { /* swallow taps so they do not fall through to the keys */ }
    }

    fun applyTheme(isDark: Boolean) {
        val keyColor = PrismSettings.getWriterKeyColor().takeIf { it != 0 }
            ?: if (isDark) 0xFF4A4A4E.toInt() else Color.WHITE
        val textColor = PrismSettings.getWriterKeyTextColor().takeIf { it != 0 }
            ?: if (isDark) Color.WHITE else Color.BLACK
        val accent = PrismSettings.getWriterAccentColor().takeIf { it != 0 }
            ?: if (isDark) 0xFF0A84FF.toInt() else 0xFF007AFF.toInt()
        val panel = PrismSettings.getWriterPanelColor().takeIf { it != 0 }
            ?: if (isDark) 0xFF1C1C1E.toInt() else 0xFFD1D4DA.toInt()

        setBackgroundColor(panel)

        // The rounded rectangle the field sits in.
        field.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(18).toFloat()
            setColor(keyColor)
        }
        field.setTextColor(textColor)

        send.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(18).toFloat()
            setColor(accent)
        }
        send.setTextColor(if (isDark) Color.WHITE else Color.WHITE)
        render()
    }

    // ── Text, driven by the keyboard rather than by focus ──────────────────

    fun open() {
        query.setLength(0)
        busy = false
        render()
        visibility = View.VISIBLE

        // RISES OUT OF THE KEYBOARD rather than dropping in from above. It belongs to the
        // keyboard, so it should look like it came from there — starting below its resting place
        // and moving up reads as the keyboard extending, which is what it is.
        translationY = height.toFloat().coerceAtLeast(dp(56).toFloat())
        alpha = 0f
        animate().translationY(0f).alpha(1f).setDuration(180).start()
    }

    fun close() {
        // Back down the way it came.
        animate().translationY(height.toFloat()).alpha(0f).setDuration(140)
            .withEndAction { visibility = View.GONE }
            .start()
    }

    fun append(text: String) {
        if (busy) return
        query.append(text)
        render()
    }

    fun backspace() {
        if (busy) return
        if (query.isNotEmpty()) query.setLength(query.length - 1)
        render()
    }

    fun submit() {
        val text = query.toString().trim()
        if (text.isEmpty()) {
            onDismiss?.invoke()
            return
        }
        busy = true
        send.text = "…"
        field.text = "Searching…"
        onSubmit?.invoke(text)
    }

    /** Puts the bar back to an idle, empty state after a search finishes. */
    fun finish() {
        busy = false
        query.setLength(0)
        send.text = "Search"
        render()
    }

    val isBusy: Boolean get() = busy

    private fun render() {
        if (busy) return
        field.text = if (query.isEmpty()) "Search the web…" else "$query|"
        field.alpha = if (query.isEmpty()) 0.5f else 1f
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
