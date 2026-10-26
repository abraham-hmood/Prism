package com.prism.launcher.mouse

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.os.SystemClock
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import com.prism.launcher.nora.IosUi
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A flat, top-down mouse.
 *
 * ## Why this is one custom view
 *
 * The earlier page was two circles labelled L and R with a third capsule between them. That is a
 * remote, not a mouse, and the labels were doing the work the shape should have done. This draws
 * the silhouette people recognise -- two top buttons, a wheel between them, and a shell around all
 * of it -- and puts the touch targets where the drawing says they are.
 *
 * ## The light is four borders, not a background
 *
 * The shell, the left button, the right button and the wheel each get their own sampled border,
 * because a single stroked outline around a mouse with separate buttons cannot follow the seams
 * that make it read as a mouse. Every border samples the same palette and flow at its own position
 * around its own perimeter, so the colours travel independently and meet at the gaps.
 *
 * ## Lava-lamp bubbles live inside the stroke
 *
 * A bubble is a small filled circle whose centre is constrained to the border's stroke and whose
 * radius is capped below the stroke width, so it cannot leak into the shell. It travels along the
 * path and oscillates across the stroke with a sine, which is what a lava lamp blob does: it does
 * not leave the glass.
 */
class FlatMouseView(context: Context) : View(context) {

    var onButtonClick: ((right: Boolean) -> Unit)? = null

    /** Called once for every whole notch, for the tick sound and the detent feel. */
    var onScrollNotch: ((direction: Int) -> Unit)? = null

    /**
     * Called continuously while the wheel is dragged, in fractional notches.
     *
     * A whole notch per event is what made scrolling feel stepped: a slow drag produced nothing
     * and then a jump. The receiver keeps the remainder, so fractions add up there and the wheel
     * answers the finger in proportion instead of in detents.
     */
    var onScroll: ((notches: Float) -> Unit)? = null

    private var style: MouseGlow.Style = MouseGlow.selectedStyle()
    private var flow: MouseGlow.Flow = MouseGlow.selectedFlow()

    private val dark = IosUi.isDark(context)
    private val startedAt = SystemClock.elapsedRealtime()

    private val bodyPath = Path()
    private val leftPath = Path()
    private val rightPath = Path()
    private val wheelPath = Path()
    private val bodyMeasure = PathMeasure()
    private val leftMeasure = PathMeasure()
    private val rightMeasure = PathMeasure()
    private val wheelMeasure = PathMeasure()
    private val wheelClip = Path()
    private val wheelRect = RectF()

    private val shellPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val buttonPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val wheelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ridgePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val core = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val bubbleCore = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bubbleHalo = Paint(Paint.ANTI_ALIAS_FLAG)
    private val positions = FloatArray(2)

    private var widthPx = 0
    private var heightPx = 0
    private var centerX = 0f
    private var buttonBottom = 0f
    private var wheelTop = 0f
    private var wheelBottom = 0f
    private var wheelLeft = 0f
    private var wheelRight = 0f
    private var bodyWidth = 0f

    private var pressed: Region? = null
    private var wheelLastY = 0f
    private var wheelCarried = 0f
    private var wheelTarget = 0f
    private var wheelVisual = 0f

    private var running = false
    private var lastFrame = 0L

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            if (!isShown) {
                // The page slid out of view. Stop rather than burn frames behind another page;
                // [ensureAnimating] restarts the loop when it is visible again.
                running = false
                return
            }
            lastFrame = frameTimeNanos
            // The wheel is a spring rather than a snap: a notch turns the surface a little further
            // each frame, so the ridges roll instead of teleporting by one gap.
            wheelVisual += (wheelTarget - wheelVisual) * 0.22f
            invalidate()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    init {
        isClickable = true
        contentDescription = "Mouse. Left side is the left button, right side is the right button; " +
            "drag the centre wheel to scroll."
        shellPaint.color = if (dark) 0xFFE9E9EE.toInt() else 0xFF1C1C1E.toInt()
        buttonPaint.color = if (dark) 0xFFF7F7FA.toInt() else 0xFF2C2C2E.toInt()
        wheelPaint.color = if (dark) 0xFFD7D7DE.toInt() else 0xFF3A3A3C.toInt()
        ridgePaint.color = if (dark) 0x66000000 else 0x66FFFFFF
        bubbleHalo.setColor(Color.WHITE)
        bubbleCore.setColor(Color.WHITE)
        bubbleHalo.alpha = 60
    }

    fun setGlow(style: MouseGlow.Style, flow: MouseGlow.Flow) {
        this.style = style
        this.flow = flow
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ensureAnimating()
    }

    override fun onDetachedFromWindow() {
        stopAnimating()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) ensureAnimating() else stopAnimating()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) ensureAnimating() else stopAnimating()
    }

    /**
     * Starts the frame loop if this view is actually on screen.
     *
     * A ViewPager keeps neighbouring pages attached. Starting in onAttachedToWindow alone meant
     * the loop ran once, found the page hidden, and stopped for good -- so the glow was frozen the
     * first time somebody swiped to the Mouse page instead of already moving.
     */
    private fun ensureAnimating() {
        if (running || !isAttachedToWindow || !isShown) return
        running = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun stopAnimating() {
        if (!running) return
        running = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        widthPx = w
        heightPx = h
        rebuild()
    }

    private fun rebuild() {
        if (widthPx <= 0 || heightPx <= 0) return
        val pad = dp(5f)
        val top = pad.toFloat()
        val bottom = (heightPx - pad).toFloat()
        centerX = widthPx / 2f
        bodyWidth = min(widthPx * 0.64f, dp(330f).toFloat()).coerceAtLeast(dp(180f).toFloat())
        val left = centerX - bodyWidth / 2f
        val right = centerX + bodyWidth / 2f
        val dome = bodyWidth * 0.44f
        val gap = dp(7f)
        buttonBottom = top + (bottom - top) * 0.42f
        // A share of the BUTTON band, not of the body width: on a wide page a width-derived
        // position squeezed the wheel into a squat pill, which read as a third button.
        wheelTop = top + (buttonBottom - top) * 0.22f
        wheelBottom = top + (buttonBottom - top) * 0.66f
        wheelLeft = centerX - dp(21f)
        wheelRight = centerX + dp(21f)
        wheelRect.set(wheelLeft, wheelTop, wheelRight, wheelBottom)

        bodyPath.reset()
        bodyPath.moveTo(left, top + dome)
        bodyPath.cubicTo(left, top, right, top, right, top + dome)
        bodyPath.lineTo(right, bottom - dome * 0.55f)
        bodyPath.cubicTo(right, bottom, left, bottom, left, bottom - dome * 0.55f)
        bodyPath.close()

        leftPath.reset()
        leftPath.moveTo(left, top + dome)
        leftPath.cubicTo(left, top, centerX - gap / 2f, top, centerX - gap / 2f, top + dome)
        leftPath.lineTo(centerX - gap / 2f, buttonBottom)
        leftPath.lineTo(left, buttonBottom)
        leftPath.close()

        rightPath.reset()
        rightPath.moveTo(right, top + dome)
        rightPath.cubicTo(right, top, centerX + gap / 2f, top, centerX + gap / 2f, top + dome)
        rightPath.lineTo(centerX + gap / 2f, buttonBottom)
        rightPath.lineTo(right, buttonBottom)
        rightPath.close()

        wheelPath.reset()
        wheelPath.addRoundRect(wheelRect, dp(20f).toFloat(), dp(20f).toFloat(), Path.Direction.CW)
        wheelClip.set(wheelPath)

        bodyMeasure.setPath(bodyPath, false)
        leftMeasure.setPath(leftPath, false)
        rightMeasure.setPath(rightPath, false)
        wheelMeasure.setPath(wheelPath, false)
    }

    override fun onDraw(canvas: Canvas) {
        if (widthPx == 0) return
        // Drawing at all means the view is on screen, which is the one condition the frame loop
        // needs. Restarting here closes the last gap: a transition that hides and shows a page
        // without delivering a visibility callback still animates on its next draw.
        ensureAnimating()
        val seconds = (SystemClock.elapsedRealtime() - startedAt) / 1000.0

        canvas.drawPath(bodyPath, shellPaint)
        canvas.drawPath(leftPath, buttonPaint)
        canvas.drawPath(rightPath, buttonPaint)

        // Pressed buttons darken/lighten just enough to feel like a physical surface, without
        // turning the whole control into a colour block.
        pressed?.let { region ->
            val tint = if (dark) 0x14000000 else 0x18FFFFFF
            when (region) {
                Region.LEFT -> {
                    buttonPaint.color = if (dark) 0xFFE4E4EA.toInt() else 0xFF3A3A3D.toInt()
                    canvas.drawPath(leftPath, buttonPaint)
                    buttonPaint.color = if (dark) 0xFFF7F7FA.toInt() else 0xFF2C2C2E.toInt()
                }
                Region.RIGHT -> {
                    buttonPaint.color = if (dark) 0xFFE4E4EA.toInt() else 0xFF3A3A3D.toInt()
                    canvas.drawPath(rightPath, buttonPaint)
                    buttonPaint.color = if (dark) 0xFFF7F7FA.toInt() else 0xFF2C2C2E.toInt()
                }
                Region.WHEEL, Region.NONE -> Unit
            }
            // A faint wash inside the pressed half, so the state survives a screenshot as well as
            // a finger.
            val wash = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tint }
            when (region) {
                Region.LEFT -> canvas.drawPath(leftPath, wash)
                Region.RIGHT -> canvas.drawPath(rightPath, wash)
                Region.WHEEL, Region.NONE -> Unit
            }
        }

        drawBorder(canvas, bodyPath, bodyMeasure, seconds, dp(2.2f).toFloat(), bubbleScale = 0.8f)
        drawBorder(canvas, leftPath, leftMeasure, seconds, dp(2.4f).toFloat(), bubbleScale = 1f)
        drawBorder(canvas, rightPath, rightMeasure, seconds, dp(2.4f).toFloat(), bubbleScale = 1f)
        // The button borders run down the centre gap, which the wheel covers. Painting the wheel
        // AFTER those borders is what keeps a bright seam from being drawn across its face.
        drawWheel(canvas)
        drawBorder(canvas, wheelPath, wheelMeasure, seconds, dp(2.2f).toFloat(), bubbleScale = 0.55f)
    }

    private fun drawWheel(canvas: Canvas) {
        canvas.drawPath(wheelPath, wheelPaint)
        canvas.save()
        canvas.clipPath(wheelClip)
        val gap = dp(9f).toFloat()
        val offset = (wheelVisual * gap) % gap
        var y = wheelTop - gap * 2f + offset
        ridgePaint.strokeWidth = dp(2f).toFloat()
        while (y < wheelBottom + gap) {
            if (y > wheelTop && y < wheelBottom) {
                canvas.drawLine(
                    wheelLeft + (wheelRight - wheelLeft) * 0.27f,
                    y,
                    wheelRight - (wheelRight - wheelLeft) * 0.27f,
                    y,
                    ridgePaint,
                )
            }
            y += gap
        }
        canvas.restore()
    }

    /**
     * One animated border.
     *
     * [bubbleScale] exists because a bubble that reads well on the 330dp shell is too big inside
     * the 42dp wheel; it scales both the count and the radius, and the radius is still clamped to
     * the stroke so the smallest border cannot be overrun.
     */
    private fun drawBorder(
        canvas: Canvas,
        path: Path,
        measure: PathMeasure,
        seconds: Double,
        stroke: Float,
        bubbleScale: Float,
    ) {
        val length = measure.length
        if (length <= 0f) return
        val steps = (length / dp(7f).toFloat()).toInt().coerceIn(28, 140)

        core.strokeWidth = stroke
        halo.strokeWidth = stroke * 3.4f
        for (index in 0 until steps) {
            val u = index.toDouble() / steps
            val next = (index + 0.9) / steps
            val sample = MouseGlow.sample(style, flow, u, seconds)
            core.color = sample.color
            halo.color = Color.argb(
                (Color.alpha(sample.color) * 0.18f).toInt(),
                Color.red(sample.color),
                Color.green(sample.color),
                Color.blue(sample.color),
            )
            measure.getPosTan((u * length).toFloat(), positions, null)
            val x0 = positions[0]
            val y0 = positions[1]
            measure.getPosTan((next * length).toFloat(), positions, null)
            val x1 = positions[0]
            val y1 = positions[1]
            canvas.drawLine(x0, y0, x1, y1, halo)
            canvas.drawLine(x0, y0, x1, y1, core)
        }

        drawBubbles(canvas, measure, length, seconds, stroke, bubbleScale)
    }

    private fun drawBubbles(
        canvas: Canvas,
        measure: PathMeasure,
        length: Float,
        seconds: Double,
        stroke: Float,
        scale: Float,
    ) {
        val sample = MouseGlow.sample(style, flow, 0.25, seconds)
        val count = floor(sample.bubbles * 7 * scale).toInt()
        if (count <= 0) return
        val radius = (dp(2.2f) * sample.bubbleSize * scale).toFloat()
            .coerceAtLeast(dp(1.1f).toFloat())
            .coerceAtMost(stroke * 0.82f)
        val maxDrift = (stroke * 0.5f - radius).coerceAtLeast(0f)

        for (index in 0 until count) {
            val seed = (index + 1) * 0.173
            val speed = 0.035 + sample.bubbleSpeed * 0.05
            val u = ((seed + seconds * speed * (0.6 + (index % 3) * 0.16)) % 1.0)
            val current = MouseGlow.sample(style, flow, u, seconds)
            measure.getPosTan((u * length).toFloat(), positions, null)
            val x = positions[0]
            val y = positions[1]
            val drift = if (maxDrift <= 0f) 0f else
                (sin(seconds * (0.8 + index * 0.07) * (sample.bubbleSpeed + 0.2) + index) * maxDrift)
                    .toFloat()
            // The normal of a path segment is what "across the stroke" means; deriving it from the
            // tangent keeps the bubble inside the border on curves as well as straight runs.
            measure.getPosTan(((u + 0.002) % 1.0 * length).toFloat(), positions, null)
            val tx = positions[0] - x
            val ty = positions[1] - y
            val tangent = max(0.0001f, kotlin.math.sqrt(tx * tx + ty * ty))
            val nx = -ty / tangent
            val ny = tx / tangent
            val bx = x + nx * drift
            val by = y + ny * drift
            bubbleHalo.color = current.color
            bubbleHalo.alpha = 55
            bubbleCore.color = current.color
            bubbleCore.alpha = 220
            canvas.drawCircle(bx, by, radius * 1.8f, bubbleHalo)
            canvas.drawCircle(bx, by, radius, bubbleCore)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val region = regionAt(event.x, event.y)
                if (region == Region.NONE) return false
                pressed = region
                if (region == Region.WHEEL) {
                    wheelLastY = event.y
                    wheelCarried = 0f
                }
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (pressed != Region.WHEEL) return true
                val notch = dp(26f).toFloat()
                val delta = wheelLastY - event.y
                wheelLastY = event.y
                if (delta == 0f) return true
                wheelCarried += delta
                var fired = false
                while (abs(wheelCarried) >= notch) {
                    val direction = if (wheelCarried > 0) 1 else -1
                    wheelCarried -= direction * notch
                    onScrollNotch?.invoke(direction)
                    fired = true
                }
                // The ridges follow the finger continuously rather than jumping one detent at a
                // time: the wheel is a rolling surface, and the sound is what marks the detents.
                wheelTarget -= delta / notch
                onScroll?.invoke(delta / notch)
                if (fired) invalidate()
                return true
            }

            MotionEvent.ACTION_UP -> {
                val region = pressed
                pressed = null
                invalidate()
                if (region != null && region != Region.NONE && region == regionAt(event.x, event.y)) {
                    when (region) {
                        Region.LEFT -> performClick()
                        Region.RIGHT -> onButtonClick?.invoke(true)
                        Region.WHEEL -> Unit
                        Region.NONE -> Unit
                    }
                }
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                pressed = null
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        // The left button's job, exposed here as well as through a finger so an accessibility
        // click is the same action rather than an event with nowhere to go.
        onButtonClick?.invoke(false)
        return true
    }

    private fun regionAt(x: Float, y: Float): Region {
        if (y < wheelTop || y > wheelBottom) {
            if (y < dp(5f) || y > heightPx - dp(5f)) return Region.NONE
        }
        if (x in wheelLeft..wheelRight && y in wheelTop..wheelBottom) return Region.WHEEL
        if (y <= buttonBottom && x < centerX) return Region.LEFT
        if (y <= buttonBottom && x >= centerX) return Region.RIGHT
        return Region.NONE
    }

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).roundToInt()

    private enum class Region { NONE, LEFT, RIGHT, WHEEL }
}
