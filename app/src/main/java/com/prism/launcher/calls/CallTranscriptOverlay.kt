package com.prism.launcher.calls

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min

/**
 * The moveable, resizable live transcript over the call screen.
 *
 * A chat transcript rather than one rolling paragraph: the two directions are separate speakers,
 * and putting this side on the right and the peer on the left is the only layout that makes it
 * obvious who said what without labels on every line.
 */
class CallTranscriptOverlay(context: Context) : FrameLayout(context) {

    private val messages: LinearLayout
    private val scroll: ScrollView
    private val status: TextView
    private val header: LinearLayout

    init {
        background = GradientDrawable().apply {
            setColor(Color.argb(242, 26, 26, 31))
            cornerRadius = d(18).toFloat()
            setStroke(d(1), Color.argb(90, 255, 255, 255))
        }
        elevation = d(14).toFloat()

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d(14), d(10), d(14), d(10))
        }
        addView(
            column,
            FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(context).apply {
            text = "Live transcript"
            textSize = 14f
            setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
        }
        status = TextView(context).apply {
            text = "Listening…"
            textSize = 11f
            setTextColor(Color.argb(150, 255, 255, 255))
            setPadding(d(10), 0, 0, 0)
        }
        val close = TextView(context).apply {
            text = "✕"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(Color.argb(210, 255, 255, 255))
            setPadding(d(10), d(2), d(4), d(2))
            setOnClickListener { visibility = GONE }
        }
        header.addView(title)
        header.addView(
            status,
            LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        header.addView(close)
        header.setPadding(0, 0, 0, d(4))
        column.addView(
            header,
            LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        scroll = ScrollView(context).apply {
            isFillViewport = true
            clipToPadding = false
        }
        messages = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, d(4), 0, d(4))
        }
        scroll.addView(
            messages,
            FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        column.addView(
            scroll,
            LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )

        val hint = TextView(context).apply {
            text = "Drag the title bar to move · drag the corner to resize"
            textSize = 10f
            setTextColor(Color.argb(90, 255, 255, 255))
        }
        column.addView(hint)

        val resize = View(context).apply {
            background = GradientDrawable().apply {
                setColor(Color.argb(120, 255, 255, 255))
                cornerRadius = d(3).toFloat()
            }
        }
        addView(resize, FrameLayout.LayoutParams(d(18), d(18), Gravity.END or Gravity.BOTTOM).apply {
            rightMargin = d(10)
            bottomMargin = d(10)
        })

        installDrag(header)
        installResize(resize)
    }

    fun setStatus(text: String) {
        post { status.text = text }
    }

    fun addMessage(fromUser: Boolean, text: String) {
        if (text.isBlank()) return
        post {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = if (fromUser) Gravity.END else Gravity.START
                setPadding(0, d(3), 0, d(3))
            }
            val bubble = TextView(context).apply {
                this.text = text
                textSize = 13f
                setTextColor(if (fromUser) Color.WHITE else Color.argb(235, 255, 255, 255))
                setPadding(d(11), d(8), d(11), d(8))
                maxWidth = d(250)
                background = GradientDrawable().apply {
                    setColor(if (fromUser) Color.rgb(10, 110, 220) else Color.rgb(52, 52, 60))
                    cornerRadius = d(13).toFloat()
                }
            }
            row.addView(bubble)
            messages.addView(
                row,
                LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            if (messages.childCount > 120) messages.removeViewAt(0)
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }
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
                    x = (baseX + event.rawX - startX).coerceIn(0f, max(0, parentWidth - width).toFloat())
                    y = (baseY + event.rawY - startY).coerceIn(0f, max(0, parentHeight - height).toFloat())
                    true
                }
                else -> false
            }
        }
    }

    private fun installResize(handle: View) {
        var startX = 0f
        var startY = 0f
        var startWidth = 0
        var startHeight = 0
        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startY = event.rawY
                    startWidth = width
                    startHeight = height
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val parentWidth = (parent as? View)?.width ?: 0
                    val parentHeight = (parent as? View)?.height ?: 0
                    val params = layoutParams ?: return@setOnTouchListener false
                    val maxWidth = if (parentWidth > 0) min(parentWidth - x.toInt(), parentWidth) else startWidth
                    val maxHeight = if (parentHeight > 0) min(parentHeight - y.toInt(), parentHeight) else startHeight
                    params.width = (startWidth + (event.rawX - startX).toInt()).coerceIn(d(220), max(d(220), maxWidth))
                    params.height = (startHeight + (event.rawY - startY).toInt()).coerceIn(d(150), max(d(150), maxHeight))
                    layoutParams = params
                    true
                }
                else -> false
            }
        }
    }

    private fun d(value: Int): Int =
        (value * resources.displayMetrics.density).toInt().coerceAtLeast(1)
}
