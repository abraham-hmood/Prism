package com.prism.launcher.science

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import com.prism.launcher.nora.IosUi
import com.prism.launcher.protein.FoldingPrediction
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A predicted structure, drawn.
 *
 * ## Why a hand-rolled projection and not OpenGL
 *
 * What is being drawn is a few hundred connected line segments. A GL surface for that costs a context,
 * a surface, shader compilation and a lifecycle that fights with being inside a ScrollView inside a
 * pager — and buys nothing, because the scene has no lighting model worth the name and no textures.
 * Painter's algorithm over depth-sorted quads on a Canvas is both simpler and, at this triangle count,
 * not slower.
 *
 * ## The colouring is the point of the picture
 *
 * A folded backbone shown in one colour looks equally convincing whether the model was sure of it or
 * guessing, and this model is often guessing. So the default is pLDDT colouring, in the bands the EBI
 * publishes with every AlphaFold structure — blue confident, yellow uncertain, orange wrong — because
 * the confidence is more informative than the shape when the shape may be nonsense. Rainbow N-to-C is
 * the alternative, for following the chain rather than trusting it.
 *
 * ## What this is not
 *
 * Not a molecular viewer. There are no side chains (the model does not predict them), no secondary
 * structure assignment (DSSP needs hydrogen bonds, and hydrogens are not predicted either), and no
 * surfaces. It is a Cα trace with a ribbon drawn through it, which is exactly as much as the
 * underlying prediction supports. Drawing a cartoon with helices and sheets would be inventing
 * secondary structure the model never asserted.
 */
class StructureView(context: Context) : View(context) {

    enum class Colouring { CONFIDENCE, RAINBOW }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    private var prediction: FoldingPrediction? = null

    /** Smoothed Cα trace. Interpolated once per structure, not per frame. */
    private var points: Array<FloatArray> = emptyArray()

    /** pLDDT carried through the interpolation, so a ribbon segment knows its own confidence. */
    private var confidences: FloatArray = FloatArray(0)

    private var centre = floatArrayOf(0f, 0f, 0f)
    private var radius = 1f

    private var yaw = 0.6f
    private var pitch = -0.3f
    private var zoom = 1f

    var colouring: Colouring = Colouring.CONFIDENCE
        set(value) {
            field = value
            invalidate()
        }

    var spinning: Boolean = false
        set(value) {
            field = value
            if (value) postSpin() else removeCallbacks(spinner)
        }

    private var lastX = 0f
    private var lastY = 0f

    init {
        setBackgroundColor(IosUi.cardBackground(context))
    }

    fun show(prediction: FoldingPrediction?) {
        this.prediction = prediction
        rebuild()
        invalidate()
    }

    fun reset() {
        yaw = 0.6f
        pitch = -0.3f
        zoom = 1f
        invalidate()
    }

    // ── Geometry ───────────────────────────────────────────────────────────

    /**
     * Interpolates the Cα trace and works out the bounding sphere.
     *
     * A Catmull-Rom spline through the α-carbons, because a polyline through atoms 3.8 Å apart looks
     * like a zigzag at any zoom where the whole chain fits — the kinks are real bond geometry but they
     * are not what anybody is looking at. The spline passes through every atom, so nothing is moved;
     * only the space between them is filled in.
     */
    private fun rebuild() {
        val trace = prediction?.caTrace()
        if (trace == null || trace.size < 2) {
            points = emptyArray()
            confidences = FloatArray(0)
            return
        }

        val plddt = prediction?.plddt ?: FloatArray(trace.size)
        val out = ArrayList<FloatArray>(trace.size * SUBDIVISIONS)
        val conf = ArrayList<Float>(trace.size * SUBDIVISIONS)

        for (i in 0 until trace.size - 1) {
            val p0 = trace[max(0, i - 1)]
            val p1 = trace[i]
            val p2 = trace[i + 1]
            val p3 = trace[min(trace.size - 1, i + 2)]
            val c1 = plddt.getOrElse(i) { 0f }
            val c2 = plddt.getOrElse(i + 1) { 0f }

            for (s in 0 until SUBDIVISIONS) {
                val t = s.toFloat() / SUBDIVISIONS
                out.add(catmullRom(p0, p1, p2, p3, t))
                conf.add(c1 + (c2 - c1) * t)
            }
        }
        out.add(trace.last())
        conf.add(plddt.lastOrNull() ?: 0f)

        points = out.toTypedArray()
        confidences = FloatArray(conf.size) { conf[it] }

        // Bounding sphere around the centroid. Not the axis-aligned box: the structure is going to be
        // rotated, and a box that fits in one orientation does not fit in the next, so the view would
        // rescale as the user dragged it.
        val c = floatArrayOf(0f, 0f, 0f)
        points.forEach { for (k in 0 until 3) c[k] += it[k] }
        for (k in 0 until 3) c[k] /= points.size
        centre = c
        radius = sqrt(points.maxOf { p ->
            var sum = 0f
            for (k in 0 until 3) {
                val d = p[k] - c[k]
                sum += d * d
            }
            sum
        }).coerceAtLeast(1f)
    }

    private fun catmullRom(p0: FloatArray, p1: FloatArray, p2: FloatArray, p3: FloatArray, t: Float): FloatArray {
        val t2 = t * t
        val t3 = t2 * t
        return FloatArray(3) { k ->
            0.5f * (
                2f * p1[k] +
                    (-p0[k] + p2[k]) * t +
                    (2f * p0[k] - 5f * p1[k] + 4f * p2[k] - p3[k]) * t2 +
                    (-p0[k] + 3f * p1[k] - 3f * p2[k] + p3[k]) * t3
                )
        }
    }

    // ── Drawing ────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        if (points.size < 2) {
            drawEmpty(canvas)
            return
        }

        val w = width.toFloat()
        val h = height.toFloat()
        // Fits the bounding sphere with a margin, so the structure never touches the edge and the
        // scale does not change when it is rotated.
        val scale = (min(w, h) / (2.4f * radius)) * zoom

        val cosYaw = cos(yaw); val sinYaw = sin(yaw)
        val cosPitch = cos(pitch); val sinPitch = sin(pitch)

        // Projected once per frame into a flat array: allocating a point object per residue per frame
        // is how a spinning view becomes a GC pause.
        val screenX = FloatArray(points.size)
        val screenY = FloatArray(points.size)
        val depth = FloatArray(points.size)

        for (i in points.indices) {
            val x = points[i][0] - centre[0]
            val y = points[i][1] - centre[1]
            val z = points[i][2] - centre[2]

            val x1 = x * cosYaw + z * sinYaw
            val z1 = -x * sinYaw + z * cosYaw
            val y1 = y * cosPitch - z1 * sinPitch
            val z2 = y * sinPitch + z1 * cosPitch

            screenX[i] = w / 2f + x1 * scale
            screenY[i] = h / 2f - y1 * scale
            depth[i] = z2
        }

        // Painter's algorithm: segments drawn back to front, so a near loop occludes a far one and the
        // chain reads as three-dimensional rather than as a flat tangle.
        val order = (0 until points.size - 1).sortedBy { (depth[it] + depth[it + 1]) / 2f }

        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND

        val nearest = depth.max()
        val farthest = depth.min()
        val span = (nearest - farthest).coerceAtLeast(1e-3f)

        order.forEach { i ->
            val midDepth = (depth[i] + depth[i + 1]) / 2f
            val front = (midDepth - farthest) / span

            // Depth cueing: near segments thicker and fully saturated, far ones thinner and faded
            // toward the background. This is the only depth information a flat drawing without
            // lighting has, and without it a Cα trace is unreadable.
            val thickness = IosUi.dp(context, 2.2f + 3.4f * front).toFloat()
            val colour = colourAt(i)
            paint.strokeWidth = thickness
            paint.color = fade(colour, 0.35f + 0.65f * front)

            path.reset()
            path.moveTo(screenX[i], screenY[i])
            path.lineTo(screenX[i + 1], screenY[i + 1])
            canvas.drawPath(path, paint)
        }

        drawTermini(canvas, screenX, screenY)
        drawLegend(canvas)
    }

    /** N and C labelled, because a trace with no ends drawn does not say which way it runs. */
    private fun drawTermini(canvas: Canvas, screenX: FloatArray, screenY: FloatArray) {
        paint.style = Paint.Style.FILL
        paint.textSize = IosUi.dp(context, 11f).toFloat()

        paint.color = IosUi.secondaryLabel(context)
        canvas.drawText("N", screenX.first() + IosUi.dp(context, 4f), screenY.first(), paint)
        canvas.drawText("C", screenX.last() + IosUi.dp(context, 4f), screenY.last(), paint)
    }

    private fun drawLegend(canvas: Canvas) {
        val text = when (colouring) {
            Colouring.CONFIDENCE -> "blue = confident · orange = low pLDDT"
            Colouring.RAINBOW -> "blue = N terminus · red = C terminus"
        }
        paint.style = Paint.Style.FILL
        paint.color = IosUi.tertiaryLabel(context)
        paint.textSize = IosUi.dp(context, 10f).toFloat()
        canvas.drawText(
            text,
            IosUi.dp(context, 10f).toFloat(),
            height - IosUi.dp(context, 10f).toFloat(),
            paint,
        )
    }

    private fun drawEmpty(canvas: Canvas) {
        paint.style = Paint.Style.FILL
        paint.color = IosUi.tertiaryLabel(context)
        paint.textSize = IosUi.dp(context, 13f).toFloat()
        val message = "No structure yet"
        val textWidth = paint.measureText(message)
        canvas.drawText(message, (width - textWidth) / 2f, height / 2f, paint)
    }

    /**
     * The EBI's own pLDDT bands, not a continuous gradient.
     *
     * Bands rather than a smooth ramp because that is how every AlphaFold structure anybody has seen
     * is coloured, and a user who can read one of those can read this without being told. A smooth
     * gradient would also imply the score is more precise than it is.
     */
    private fun colourAt(segment: Int): Int = when (colouring) {
        Colouring.CONFIDENCE -> {
            when (val score = confidences.getOrElse(segment) { 0f }) {
                in 90f..100f -> Color.rgb(0, 83, 214)       // very high
                in 70f..90f -> Color.rgb(101, 203, 243)     // confident
                in 50f..70f -> Color.rgb(255, 219, 19)      // low
                else -> {
                    // Below 50 the model is saying it does not know. Orange, and deliberately the
                    // most visually alarming colour on the card.
                    if (score.isNaN()) Color.GRAY else Color.rgb(255, 125, 69)
                }
            }
        }

        Colouring.RAINBOW -> {
            val fraction = segment.toFloat() / (points.size - 1).coerceAtLeast(1)
            // 240 (blue) down to 0 (red): N to C, the convention in every structure paper.
            Color.HSVToColor(floatArrayOf(240f * (1f - fraction), 0.8f, 0.95f))
        }
    }

    private fun fade(colour: Int, amount: Float): Int {
        val background = IosUi.cardBackground(context)
        val a = amount.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(colour) * a + Color.red(background) * (1 - a)).toInt(),
            (Color.green(colour) * a + Color.green(background) * (1 - a)).toInt(),
            (Color.blue(colour) * a + Color.blue(background) * (1 - a)).toInt(),
        )
    }

    // ── Interaction ────────────────────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                // The panel lives in a ScrollView; without this, a vertical drag to tilt the
                // structure scrolls the page instead and the model never rotates.
                parent?.requestDisallowInterceptTouchEvent(true)
                spinning = false
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount >= 2) {
                    // Two fingers: pinch to zoom, using the span between them.
                    val span = distance(event)
                    if (pinchStart > 0f) {
                        zoom = (pinchZoom * span / pinchStart).coerceIn(0.4f, 6f)
                    } else {
                        pinchStart = span
                        pinchZoom = zoom
                    }
                    invalidate()
                    return true
                }
                yaw += (event.x - lastX) * 0.01f
                pitch += (event.y - lastY) * 0.01f
                // Pitch clamped just short of straight up. Past ±90° the rotation flips handedness
                // and the structure appears to invert, which reads as a glitch rather than a view.
                pitch = pitch.coerceIn(-1.5f, 1.5f)
                lastX = event.x
                lastY = event.y
                invalidate()
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                pinchStart = distance(event)
                pinchZoom = zoom
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                pinchStart = 0f
                // Reset the single-finger anchor to the remaining finger, or the next move jumps by
                // however far apart the two fingers were.
                val remaining = if (event.actionIndex == 0) 1 else 0
                if (remaining < event.pointerCount) {
                    lastX = event.getX(remaining)
                    lastY = event.getY(remaining)
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                pinchStart = 0f
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private var pinchStart = 0f
    private var pinchZoom = 1f

    private fun distance(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 0f
        val dx = event.getX(0) - event.getX(1)
        val dy = event.getY(0) - event.getY(1)
        return sqrt(dx * dx + dy * dy)
    }

    private val spinner = object : Runnable {
        override fun run() {
            if (!spinning) return
            yaw += 0.012f
            invalidate()
            postSpin()
        }
    }

    private fun postSpin() {
        removeCallbacks(spinner)
        postDelayed(spinner, 32)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // Otherwise the handler keeps the view, and the view keeps the structure, after the page has
        // gone.
        removeCallbacks(spinner)
    }

    private companion object {
        /** Points inserted between each pair of Cα atoms. Six is smooth at phone sizes. */
        const val SUBDIVISIONS = 6
    }
}
