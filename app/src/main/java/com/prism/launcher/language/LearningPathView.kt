package com.prism.launcher.language

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import com.prism.launcher.nora.IosUi
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The learning path: levels as large markers, lessons as the beads between them, on one winding
 * line from A0 to wherever the plan ends.
 *
 * ## Why a path and not a list
 *
 * Both show the same lessons. A list says "here is what is left", which for a C1 plan is four
 * hundred rows and reads as a debt. A path says "here is where you are", which is the same
 * information arranged so the part behind you is visible — and the part behind you is the only
 * thing that keeps anyone going in month four of a language.
 *
 * The winding is not decoration either. A straight column of circles is read as a list again; the
 * offset forces the eye to travel, and travelling is the metaphor doing its job.
 *
 * ## One view, drawn, no recycling
 *
 * A plan is a few hundred nodes at most and each is a circle and a glyph, so the whole path is
 * cheaper to draw than the RecyclerView that would avoid drawing it. It measures its own height and
 * lives in a ScrollView, which also means the scroll position is the learner's place in their own
 * course — something a recycled list has to be told how to restore.
 */
@SuppressLint("ViewConstructor")
class LearningPathView(
    context: Context,
    private val plan: LearningPlan,
    private var completed: Set<String>,
    private val onLesson: (PlannedLesson) -> Unit,
    private val onLevel: (PlannedLevel) -> Unit,
) : View(context) {

    /**
     * A colour per level, warm at the bottom and cool at the top.
     *
     * The progression is the point: it gives a learner a sense of altitude that a single accent
     * cannot, and after a few weeks "I'm in the green part" becomes a real way to think about where
     * you are.
     */
    private val palette = mapOf(
        Cefr.Level.A0 to 0xFFFF7BA8.toInt(),
        Cefr.Level.A1 to 0xFFFF9F43.toInt(),
        Cefr.Level.A2 to 0xFFF2B705.toInt(),
        Cefr.Level.B1 to 0xFF3FB98C.toInt(),
        Cefr.Level.B2 to 0xFF23AFC0.toInt(),
        Cefr.Level.C1 to 0xFF4C7DF0.toInt(),
        Cefr.Level.C2 to 0xFF8C5BE8.toInt(),
    )

    private enum class Kind { LEVEL, LESSON }

    private class Node(
        val kind: Kind,
        val level: PlannedLevel,
        val lesson: PlannedLesson?,
        var cx: Float = 0f,
        var cy: Float = 0f,
    )

    private val nodes: List<Node> = buildList {
        plan.levels.forEach { level ->
            add(Node(Kind.LEVEL, level, null))
            level.lessons.forEach { lesson -> add(Node(Kind.LESSON, level, lesson)) }
        }
    }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val box = RectF()
    private val connector = Path()

    private val levelRadius = dp(34f)
    private val lessonRadius = dp(21f)
    private val spacing = dp(74f)
    private val topPad = dp(28f)
    private val bottomPad = dp(120f)

    /** The lesson the learner should do next; drawn differently and nothing else is. */
    private var current: PlannedLesson? = plan.firstUnfinished(completed)

    fun refresh(done: Set<String>) {
        completed = done
        current = plan.firstUnfinished(done)
        invalidate()
    }

    /** The vertical centre of a lesson's node, so an overlay can point at the right circle. */
    fun yOf(lessonId: String): Int =
        nodes.firstOrNull { it.lesson?.id == lessonId }?.cy?.toInt() ?: 0

    /** Where the next lesson sits, so the page can scroll to it on open. */
    fun currentOffset(): Int {
        val node = nodes.firstOrNull { it.lesson?.id == current?.id } ?: return 0
        return (node.cy - height * 0.35f).toInt().coerceAtLeast(0)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = (topPad + nodes.size * spacing + bottomPad).toInt()
        setMeasuredDimension(width, height)
        place(width.toFloat())
    }

    /**
     * Lays the nodes out down a sine wave.
     *
     * The period is deliberately not a whole number of nodes: at exactly four per cycle every level
     * marker would land at the same horizontal position and the path would look like a ladder
     * rather than a route.
     */
    private fun place(width: Float) {
        if (width <= 0f) return
        val centre = width / 2f
        val amplitude = minOf(width * 0.26f, dp(104f))
        nodes.forEachIndexed { i, node ->
            node.cx = centre + amplitude * sin(i * 0.72f)
            node.cy = topPad + spacing * (i + 0.5f)
        }
    }

    override fun onDraw(canvas: Canvas) {
        // Connectors first, so every node sits on top of its own line ends.
        for (i in 0 until nodes.size - 1) {
            drawConnector(canvas, nodes[i], nodes[i + 1])
        }
        nodes.forEach { node ->
            when (node.kind) {
                Kind.LEVEL -> drawLevel(canvas, node)
                Kind.LESSON -> drawLesson(canvas, node)
            }
        }
    }

    private fun drawConnector(canvas: Canvas, from: Node, to: Node) {
        val reached = isDone(from) || isCurrent(from)
        stroke.color = if (reached) {
            colourOf(from.level)
        } else {
            TutorPortrait.withAlpha(colourOf(to.level), 52)
        }
        stroke.strokeWidth = dp(7f)
        stroke.pathEffect = null

        // A curve rather than a straight segment: the straight version makes the wave look like a
        // zig-zag, which reads as jagged rather than winding.
        connector.reset()
        connector.moveTo(from.cx, from.cy)
        val midY = (from.cy + to.cy) / 2f
        connector.cubicTo(from.cx, midY, to.cx, midY, to.cx, to.cy)
        canvas.drawPath(connector, stroke)
    }

    private fun drawLevel(canvas: Canvas, node: Node) {
        val colour = colourOf(node.level)
        val known = node.level.alreadyKnown
        val progress = node.level.lessons.count { it.id in completed } /
            node.level.lessons.size.coerceAtLeast(1).toFloat()

        // The plate behind it, which is what makes a level marker read as a milestone and not just
        // a bigger lesson.
        fill.color = TutorPortrait.withAlpha(colour, 40)
        canvas.drawCircle(node.cx, node.cy, levelRadius + dp(9f), fill)

        fill.color = if (known && progress == 0f) TutorPortrait.withAlpha(colour, 130) else colour
        canvas.drawCircle(node.cx, node.cy, levelRadius, fill)

        // Completion ring.
        if (progress > 0f) {
            stroke.color = Color.WHITE
            stroke.strokeWidth = dp(4f)
            box.set(
                node.cx - levelRadius - dp(5f), node.cy - levelRadius - dp(5f),
                node.cx + levelRadius + dp(5f), node.cy + levelRadius + dp(5f),
            )
            canvas.drawArc(box, -90f, 360f * progress, false, stroke)
        }

        label.color = Color.WHITE
        label.textSize = dp(20f)
        canvas.drawText(node.level.level.code, node.cx, node.cy + dp(3f), label)

        // The level's name, off to the side of the marker rather than under it, so the path's own
        // spacing does not have to grow to make room for a word.
        label.color = IosUi.label(context)
        label.textSize = dp(13f)
        label.textAlign = if (node.cx < width / 2f) Paint.Align.LEFT else Paint.Align.RIGHT
        val textX = if (node.cx < width / 2f) node.cx + levelRadius + dp(18f)
        else node.cx - levelRadius - dp(18f)
        canvas.drawText(node.level.level.title, textX, node.cy + dp(4f), label)
        label.textAlign = Paint.Align.CENTER
    }

    private fun drawLesson(canvas: Canvas, node: Node) {
        val lesson = node.lesson ?: return
        val colour = colourOf(node.level)
        val done = lesson.id in completed
        val isNext = isCurrent(node)

        fill.color = when {
            done -> colour
            isNext -> colour
            else -> TutorPortrait.withAlpha(colour, 46)
        }
        canvas.drawCircle(node.cx, node.cy, lessonRadius, fill)

        // The next lesson gets a halo. Exactly one node on the whole path is allowed to shout, and
        // it is the one the learner is meant to tap.
        if (isNext) {
            stroke.color = colour
            stroke.strokeWidth = dp(3f)
            canvas.drawCircle(node.cx, node.cy, lessonRadius + dp(7f), stroke)
        }

        val ink = when {
            done || isNext -> Color.WHITE
            else -> TutorPortrait.withAlpha(IosUi.label(context), 120)
        }
        if (done) {
            drawTick(canvas, node.cx, node.cy, dp(8f), ink)
        } else {
            drawGlyph(canvas, lesson.kind, node.cx, node.cy, dp(10f), ink)
        }
    }

    /**
     * The lesson-kind marks, drawn rather than set as emoji.
     *
     * Emoji were the obvious first move and were wrong on device: they arrive in full colour from
     * the system font, so a path of thirty lessons became thirty little pictures fighting the level
     * colour, and none of them could be dimmed to show a lesson was still locked. A drawn mark takes
     * the ink colour it is given, which is the whole requirement.
     *
     * Each is built from two or three primitives. At 20dp nothing more survives, and trying is how
     * icons turn to mud.
     */
    private fun drawGlyph(canvas: Canvas, kind: Cefr.LessonKind, cx: Float, cy: Float, r: Float, ink: Int) {
        fill.color = ink
        stroke.color = ink
        stroke.strokeWidth = r * 0.28f

        when (kind) {
            // A framed picture: the one mark that has to say "no words in this lesson".
            Cefr.LessonKind.PICTURE -> {
                box.set(cx - r, cy - r * 0.8f, cx + r, cy + r * 0.8f)
                canvas.drawRoundRect(box, r * 0.25f, r * 0.25f, stroke)
                fill.color = ink
                canvas.drawCircle(cx - r * 0.35f, cy - r * 0.3f, r * 0.2f, fill)
                connector.reset()
                connector.moveTo(cx - r * 0.6f, cy + r * 0.5f)
                connector.lineTo(cx + r * 0.1f, cy - r * 0.25f)
                connector.lineTo(cx + r * 0.75f, cy + r * 0.5f)
                connector.close()
                canvas.drawPath(connector, fill)
            }

            Cefr.LessonKind.VOCABULARY -> {
                box.set(cx - r * 0.85f, cy - r * 0.9f, cx + r * 0.85f, cy + r * 0.9f)
                canvas.drawRoundRect(box, r * 0.2f, r * 0.2f, stroke)
                canvas.drawLine(cx, cy - r * 0.9f, cx, cy + r * 0.9f, stroke)
            }

            Cefr.LessonKind.PATTERN -> {
                box.set(cx - r * 0.9f, cy - r * 0.9f, cx + r * 0.05f, cy + r * 0.05f)
                canvas.drawRoundRect(box, r * 0.2f, r * 0.2f, stroke)
                box.set(cx - r * 0.05f, cy - r * 0.05f, cx + r * 0.9f, cy + r * 0.9f)
                canvas.drawRoundRect(box, r * 0.2f, r * 0.2f, fill)
            }

            Cefr.LessonKind.LISTENING -> {
                box.set(cx - r * 0.85f, cy - r * 0.9f, cx + r * 0.85f, cy + r * 0.6f)
                canvas.drawArc(box, 180f, 180f, false, stroke)
                box.set(cx - r * 0.95f, cy - r * 0.1f, cx - r * 0.35f, cy + r * 0.8f)
                canvas.drawRoundRect(box, r * 0.3f, r * 0.3f, fill)
                box.set(cx + r * 0.35f, cy - r * 0.1f, cx + r * 0.95f, cy + r * 0.8f)
                canvas.drawRoundRect(box, r * 0.3f, r * 0.3f, fill)
            }

            Cefr.LessonKind.CONVERSATION -> {
                box.set(cx - r, cy - r * 0.85f, cx + r, cy + r * 0.45f)
                canvas.drawRoundRect(box, r * 0.4f, r * 0.4f, fill)
                connector.reset()
                connector.moveTo(cx - r * 0.45f, cy + r * 0.35f)
                connector.lineTo(cx - r * 0.15f, cy + r)
                connector.lineTo(cx + r * 0.1f, cy + r * 0.35f)
                connector.close()
                canvas.drawPath(connector, fill)
            }

            // Two voices, which is the whole difference from a conversation.
            Cefr.LessonKind.ROLEPLAY -> {
                box.set(cx - r, cy - r, cx + r * 0.25f, cy + r * 0.1f)
                canvas.drawRoundRect(box, r * 0.35f, r * 0.35f, stroke)
                box.set(cx - r * 0.25f, cy - r * 0.1f, cx + r, cy + r)
                canvas.drawRoundRect(box, r * 0.35f, r * 0.35f, fill)
            }

            Cefr.LessonKind.PRONUNCIATION -> {
                val bars = floatArrayOf(0.45f, 0.95f, 0.65f, 1f, 0.4f)
                bars.forEachIndexed { i, height ->
                    val x = cx + (i - 2) * r * 0.42f
                    canvas.drawLine(x, cy - r * height, x, cy + r * height, stroke)
                }
            }

            Cefr.LessonKind.REVIEW -> {
                box.set(cx - r * 0.85f, cy - r * 0.85f, cx + r * 0.85f, cy + r * 0.85f)
                canvas.drawArc(box, 40f, 280f, false, stroke)
                connector.reset()
                connector.moveTo(cx + r * 0.85f, cy - r * 0.15f)
                connector.lineTo(cx + r * 0.25f, cy - r * 0.3f)
                connector.lineTo(cx + r * 0.8f, cy - r * 0.9f)
                connector.close()
                canvas.drawPath(connector, fill)
            }

            Cefr.LessonKind.ASSESSMENT -> {
                canvas.drawCircle(cx, cy, r * 0.9f, stroke)
                canvas.drawCircle(cx, cy, r * 0.35f, fill)
            }
        }
    }

    private fun drawTick(canvas: Canvas, cx: Float, cy: Float, r: Float, ink: Int) {
        stroke.color = ink
        stroke.strokeWidth = r * 0.38f
        connector.reset()
        connector.moveTo(cx - r * 0.85f, cy + r * 0.05f)
        connector.lineTo(cx - r * 0.2f, cy + r * 0.7f)
        connector.lineTo(cx + r * 0.9f, cy - r * 0.7f)
        canvas.drawPath(connector, stroke)
    }

    private fun colourOf(level: PlannedLevel): Int = palette[level.level] ?: IosUi.accent(context)

    private fun isDone(node: Node): Boolean =
        node.lesson?.let { it.id in completed } ?: (node.level.lessons.all { it.id in completed })

    private fun isCurrent(node: Node): Boolean =
        node.lesson != null && node.lesson.id == current?.id

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Claim the gesture from the ScrollView only while the finger is actually on a
                // node; anywhere else on the path must still scroll.
                return hit(event.x, event.y) != null
            }
            MotionEvent.ACTION_UP -> {
                val node = hit(event.x, event.y) ?: return false
                performClick()
                when (node.kind) {
                    Kind.LEVEL -> onLevel(node.level)
                    Kind.LESSON -> node.lesson?.let(onLesson)
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun hit(x: Float, y: Float): Node? {
        // Nodes are spaced far enough apart that the nearest one within a generous radius is
        // unambiguous, and a generous radius is what makes a 42dp circle comfortable to tap.
        val touchable = dp(30f)
        return nodes.firstOrNull { node ->
            abs(node.cy - y) < spacing &&
                hypot(node.cx - x, node.cy - y) <= (if (node.kind == Kind.LEVEL) levelRadius else lessonRadius) + touchable
        }
    }

    private fun dp(value: Float): Float = IosUi.dp(context, value).toFloat()
}
