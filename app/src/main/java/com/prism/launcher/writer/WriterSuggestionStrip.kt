package com.prism.launcher.writer

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.prism.launcher.PrismSettings

/**
 * Three candidate words above the keys, the most likely one in the middle.
 *
 * CENTRE IS THE STRONGEST POSITION, not the left. The thumb rests under the middle of the keyboard
 * and the eye lands there first, so putting the best guess where a list would naturally put it —
 * first, on the left — makes the most likely word the furthest to reach. [WriterSuggestions.
 * arrangeForStrip] does the reordering; this view only draws what it is handed.
 */
class WriterSuggestionStrip(context: Context) : LinearLayout(context) {

    var onPicked: ((String) -> Unit)? = null

    private val slots = List(3) { TextView(context) }
    private var words: List<String> = emptyList()

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        for ((index, slot) in slots.withIndex()) {
            slot.apply {
                gravity = Gravity.CENTER
                textSize = 16f
                setSingleLine()
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(6), dp(10), dp(6), dp(10))
                setOnClickListener {
                    words.getOrNull(index)?.takeIf { it.isNotBlank() }?.let { onPicked?.invoke(it) }
                }
            }
            addView(slot, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    fun applyTheme(isDark: Boolean) {
        setBackgroundColor(
            PrismSettings.getWriterPanelColor().takeIf { it != 0 }
                ?: if (isDark) 0xFF1B1B1D.toInt() else 0xFFD1D5DB.toInt()
        )
        render(isDark)
    }

    /** Shows [suggestions] already arranged for the strip, or hides when there is nothing to offer. */
    fun show(suggestions: List<String>, isDark: Boolean) {
        words = suggestions
        visibility = if (suggestions.isEmpty()) View.GONE else View.VISIBLE
        render(isDark)
    }

    fun clear() {
        words = emptyList()
        visibility = View.GONE
    }

    private fun render(isDark: Boolean) {
        val text = PrismSettings.getWriterKeyTextColor().takeIf { it != 0 }
            ?: if (isDark) Color.WHITE else Color.BLACK

        for ((index, slot) in slots.withIndex()) {
            val word = words.getOrNull(index).orEmpty()
            slot.text = word
            // The middle slot is the pick, so it is the one that looks like a choice rather than
            // an option. Weight rather than colour, which survives any background the user sets.
            val isCentre = index == 1 && words.size >= 2
            slot.setTextColor(if (word.isBlank()) Color.TRANSPARENT else text)
            slot.alpha = if (isCentre) 1f else 0.72f
            slot.typeface = android.graphics.Typeface.create(
                android.graphics.Typeface.SANS_SERIF,
                if (isCentre) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL,
            )
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
