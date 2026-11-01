package com.prism.launcher.calls

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The dialer shown over a live call.
 *
 * The buttons are not a second dialer: every press goes back into the call as a DTMF digit, which
 * is what reaches an IVR or a phone menu. The number is displayed above them so a person can see
 * what they typed even when the far end is a recording saying nothing useful.
 */
class CallDialpadOverlay(context: Context) : FrameLayout(context) {

    var onDigit: ((String) -> Unit)? = null

    private val entered = TextView(context)

    init {
        background = GradientDrawable().apply {
            setColor(Color.argb(246, 26, 26, 31))
            cornerRadius = d(22).toFloat()
            setStroke(d(1), Color.argb(90, 255, 255, 255))
        }
        elevation = d(16).toFloat()
        layoutParams = LayoutParams(d(300), LayoutParams.WRAP_CONTENT, Gravity.CENTER)

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d(16), d(10), d(16), d(16))
        }
        addView(column)

        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(context).apply {
            text = "Keypad"
            textSize = 14f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
        }
        val close = TextView(context).apply {
            text = "✕"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(Color.argb(210, 255, 255, 255))
            setPadding(d(10), d(2), d(4), d(2))
            setOnClickListener { visibility = GONE }
        }
        bar.addView(title)
        bar.addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        bar.addView(close)
        column.addView(bar, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        entered.text = ""
        entered.textSize = 22f
        entered.gravity = Gravity.CENTER
        entered.setPadding(0, d(10), 0, d(10))
        entered.setTextColor(Color.WHITE)
        column.addView(entered, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        val keys = listOf(
            listOf("1" to "", "2" to "ABC", "3" to "DEF"),
            listOf("4" to "GHI", "5" to "JKL", "6" to "MNO"),
            listOf("7" to "PQRS", "8" to "TUV", "9" to "WXYZ"),
            listOf("*" to "", "0" to "+", "#" to ""),
        )
        keys.forEach { rowKeys ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            rowKeys.forEach { (digit, letters) ->
                row.addView(key(digit, letters), LinearLayout.LayoutParams(0, d(60), 1f))
            }
            column.addView(row, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }

        installDrag(bar)
    }

    private fun key(digit: String, letters: String): View {
        val cell = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            setOnClickListener {
                entered.append(digit)
                onDigit?.invoke(digit)
            }
        }
        val button = TextView(context).apply {
            text = digit
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.rgb(48, 48, 56))
            }
            layoutParams = LinearLayout.LayoutParams(d(48), d(48))
        }
        cell.addView(button)
        if (letters.isNotBlank()) {
            cell.addView(TextView(context).apply {
                text = letters
                textSize = 8f
                gravity = Gravity.CENTER
                setTextColor(Color.argb(150, 255, 255, 255))
            })
        }
        return cell
    }

    private fun installDrag(handle: View) {
        var startX = 0f
        var startY = 0f
        var baseX = 0f
        var baseY = 0f
        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startY = event.rawY
                    baseX = x
                    baseY = y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val parentWidth = (parent as? View)?.width ?: width
                    val parentHeight = (parent as? View)?.height ?: height
                    x = (baseX + event.rawX - startX).coerceIn(0f, maxOf(0, parentWidth - width).toFloat())
                    y = (baseY + event.rawY - startY).coerceIn(0f, maxOf(0, parentHeight - height).toFloat())
                    true
                }
                else -> false
            }
        }
    }

    private fun d(value: Int): Int =
        (value * resources.displayMetrics.density).toInt().coerceAtLeast(1)
}
