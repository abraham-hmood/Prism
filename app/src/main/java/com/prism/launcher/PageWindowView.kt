package com.prism.launcher

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.prism.launcher.nora.IosUi
import com.prism.launcher.theme.PrismThemes
import kotlin.math.roundToInt

/**
 * A launcher page drawn as a real, movable window.
 *
 * The old "windowed pages" frame was a full-bleed card with padding: nothing could be moved,
 * resized or put away, so the switch changed the decoration and not the behaviour. This owns the
 * page's geometry instead: the title bar drags it, the corner grip resizes it, and the minus
 * minimises it while the activity keeps the page alive on a hotseat.
 *
 * A MINIMISED PAGE IS HIDDEN, NOT DESTROYED. The content view is still the same instance, so a
 * browser keeps its tabs and a game keeps its board. Views that run their own animation should
 * pause in `onWindowVisibilityChanged`, which is what the Android lifecycle already tells them.
 */
class PageWindowView(
    context: Context,
    private val title: String,
    private val number: Int,
    val content: View,
    private val onMinimize: (PageWindowView) -> Unit,
) : FrameLayout(context) {

    private val density = resources.displayMetrics.density
    private fun dp(value: Float): Int = (value * density).roundToInt()

    private val theme = PrismThemes.active()
    private val mode = if (IosUi.isDark(context)) theme.dark else theme.light
    private val palette = mode.palette

    private val window = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        clipToOutline = true
        elevation = dp(10f).toFloat()
        background = GradientDrawable().apply {
            setColor(palette.card.toInt())
            cornerRadius = dp(mode.shape.card.toFloat()).toFloat()
            setStroke(dp(1f), palette.divider.toInt())
        }
    }

    private val bar = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14f), 0, dp(8f), 0)
    }

    private val minimize = TextView(context).apply {
        text = "—"
        textSize = 17f
        setTextColor(palette.muted.toInt())
        gravity = Gravity.CENTER
        contentDescription = "Minimise $title"
        setOnClickListener {
            window.visibility = View.GONE
            setBackgroundColor(Color.TRANSPARENT)
            onMinimize(this@PageWindowView)
        }
    }

    private val grip = View(context).apply { contentDescription = "Resize $title" }

    private var placed = false
    private var dragging = false
    private var resizing = false
    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0f
    private var startY = 0f
    private var startW = 0
    private var startH = 0

    val isMinimized: Boolean get() = window.visibility != View.VISIBLE

    init {
        // The scrim is what makes a floating card legible over a wallpaper. It is transparent
        // while minimised so the desktop underneath is the desktop, not a grey rectangle.
        setBackgroundColor(0x55000000)
        setTag(R.id.tag_prism_page_content, content)

        bar.addView(View(context).apply {
            background = GradientDrawable().apply {
                setColor(palette.accent.toInt())
                cornerRadius = dp(3f).toFloat()
            }
        }, LinearLayout.LayoutParams(dp(7f), dp(7f)))
        bar.addView(TextView(context).apply {
            text = title
            textSize = 11f
            paint.isFakeBoldText = true
            setTextColor(palette.text.toInt())
            setPadding(dp(9f), 0, 0, 0)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(TextView(context).apply {
            text = number.toString()
            textSize = 10f
            setTextColor(palette.faint.toInt())
            setPadding(0, 0, dp(6f), 0)
        })
        bar.addView(minimize, LinearLayout.LayoutParams(dp(34f), LinearLayout.LayoutParams.MATCH_PARENT))

        window.addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(34f)))
        window.addView(content, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        window.addView(grip, LinearLayout.LayoutParams(dp(26f), dp(26f)).apply {
            gravity = Gravity.END
        })

        addView(window, LayoutParams(dp(320f), dp(420f)))

        bar.setOnTouchListener { _, event -> drag(event) }
        grip.setOnTouchListener { _, event -> resize(event) }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        val params = window.layoutParams as LayoutParams
        if (!placed) {
            params.width = (w * 0.86f).toInt().coerceAtLeast(dp(260f)).coerceAtMost(w - dp(20f))
            params.height = (h * 0.76f).toInt().coerceAtLeast(dp(240f)).coerceAtMost(h - dp(24f))
            window.layoutParams = params
            placed = true
            window.post {
                window.x = ((w - window.width) / 2f).coerceAtLeast(0f)
                window.y = (dp(22f).toFloat() + (h - window.height) * 0.08f).coerceAtLeast(0f)
            }
        } else {
            params.width = params.width.coerceAtMost(w - dp(8f))
            params.height = params.height.coerceAtMost(h - dp(8f))
            window.layoutParams = params
            clamp()
        }
    }

    private fun drag(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = true
                downRawX = event.rawX
                downRawY = event.rawY
                startX = window.x
                startY = window.y
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return false
                window.x = startX + (event.rawX - downRawX)
                window.y = startY + (event.rawY - downRawY)
                clamp()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                return true
            }
        }
        return false
    }

    private fun resize(event: MotionEvent): Boolean {
        val params = window.layoutParams as LayoutParams
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                resizing = true
                downRawX = event.rawX
                downRawY = event.rawY
                startW = params.width
                startH = params.height
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!resizing) return false
                params.width = (startW + (event.rawX - downRawX).toInt())
                    .coerceIn(dp(260f), (width - window.x).toInt().coerceAtLeast(dp(260f)))
                params.height = (startH + (event.rawY - downRawY).toInt())
                    .coerceIn(dp(240f), (height - window.y).toInt().coerceAtLeast(dp(240f)))
                window.layoutParams = params
                clamp()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                resizing = false
                return true
            }
        }
        return false
    }

    private fun clamp() {
        val maxX = (width - window.width).coerceAtLeast(0)
        val maxY = (height - window.height).coerceAtLeast(0)
        window.x = window.x.coerceIn(0f, maxX.toFloat())
        window.y = window.y.coerceIn(0f, maxY.toFloat())
    }

    /** Puts the window back where it was, at the size it had. */
    fun restore() {
        window.visibility = View.VISIBLE
        setBackgroundColor(0x55000000)
        clamp()
    }
}
