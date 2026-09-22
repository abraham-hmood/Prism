package com.prism.launcher.minigames

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/**
 * The look: ruled paper, and everything on it drawn in pencil.
 *
 * ## Why this is a set of primitives rather than a set of images
 *
 * There are two hundred and sixty building types and they modernise every five levels. Drawing that
 * as assets is somewhere north of a thousand images, and every one of them would have to agree
 * about line weight and graphite colour. Drawing it procedurally means a building is a handful of
 * boxes and roofs, the pencil look is one function every stroke goes through, and a new building
 * type is a line in a catalogue rather than a trip to an art tool.
 *
 * ## What makes a line look like pencil
 *
 * Three things, all of them here:
 *
 * 1. **The line is not straight.** [wobble] breaks every segment into short pieces and pushes each
 *    joint off the true line by a fraction of a millimetre. A perfectly straight "pencil" line reads
 *    as vector art immediately.
 * 2. **It is drawn more than once.** A person going over an edge does not retrace it exactly, and
 *    two nearly-identical strokes are what gives a hand-drawn line its weight at the corners.
 * 3. **It is not black.** Graphite is a warm dark grey that goes slightly shiny; pure black on
 *    white is ink.
 *
 * ## Determinism
 *
 * The wobble is seeded by WHAT is being drawn, not by when. A building redrawn on the next frame
 * has to have the same wobble or the whole base vibrates — which is the single most common way this
 * effect is got wrong.
 */
object PencilStyle {

    // -- The palette ----------------------------------------------------------

    /** Paper. Not white: white paper is a screen, off-white is a page. */
    const val PAPER = 0xFFFBF8EE.toInt()
    const val PAPER_DARK = 0xFF262218.toInt()

    /** The blue rule. */
    const val RULE = 0xFFB9CEE0.toInt()
    const val RULE_DARK = 0xFF3A4A58.toInt()

    /** The red margin line down the left. */
    const val MARGIN = 0xFFE4A8A8.toInt()
    const val MARGIN_DARK = 0xFF6E4444.toInt()

    /** Graphite, from a hard pencil to a soft one. */
    const val GRAPHITE = 0xFF3A3A40.toInt()
    const val GRAPHITE_LIGHT = 0xFF7A7A85.toInt()
    const val GRAPHITE_DARK = 0xFFD8D8E0.toInt()
    const val GRAPHITE_DARK_LIGHT = 0xFF8A8A95.toInt()

    /** The one coloured pencil in the tin, for things that have to be found at a glance. */
    const val RED_PENCIL = 0xFFC0504D.toInt()
    const val BLUE_PENCIL = 0xFF3F6FA8.toInt()
    const val GREEN_PENCIL = 0xFF4E8A57.toInt()

    const val LINE_SPACING_DP = 26f

    fun paper(dark: Boolean): Int = if (dark) PAPER_DARK else PAPER
    fun rule(dark: Boolean): Int = if (dark) RULE_DARK else RULE
    fun margin(dark: Boolean): Int = if (dark) MARGIN_DARK else MARGIN
    fun graphite(dark: Boolean): Int = if (dark) GRAPHITE_DARK else GRAPHITE
    fun graphiteLight(dark: Boolean): Int = if (dark) GRAPHITE_DARK_LIGHT else GRAPHITE_LIGHT

    // -- Paints ---------------------------------------------------------------

    fun pencil(width: Float, colour: Int, alpha: Int = 235): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = width
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = colour
        this.alpha = alpha
    }

    fun shading(colour: Int, alpha: Int = 48): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = colour
        this.alpha = alpha
    }

    // -- The page -------------------------------------------------------------

    /**
     * Draws the ruled paper itself.
     *
     * [scrollY] lets a scrolling surface keep the ruling fixed to the CONTENT rather than to the
     * screen, which is the difference between a page that moves and a screen with lines on it.
     */
    fun drawPaper(canvas: Canvas, width: Int, height: Int, dark: Boolean, density: Float, scrollY: Float = 0f) {
        canvas.drawColor(paper(dark))

        val spacing = LINE_SPACING_DP * density
        val rulePaint = pencil(1f * density, rule(dark), alpha = 190)
        var y = -(scrollY % spacing)
        while (y < height) {
            if (y >= 0) canvas.drawLine(0f, y, width.toFloat(), y, rulePaint)
            y += spacing
        }

        val marginX = 46f * density
        canvas.drawLine(marginX, 0f, marginX, height.toFloat(), pencil(1.4f * density, margin(dark), alpha = 170))
        // The punch holes, which is the detail that makes people say "that's my school book".
        val holePaint = shading(rule(dark), alpha = 90)
        listOf(0.22f, 0.5f, 0.78f).forEach { at ->
            canvas.drawCircle(marginX / 2.2f, height * at, 5.5f * density, holePaint)
        }
    }

    // -- Strokes --------------------------------------------------------------

    /**
     * A hand-drawn line from one point to another.
     *
     * Seeded: pass something that identifies the thing being drawn, and it wobbles the same way
     * every frame. Pass a changing value and the drawing shakes.
     */
    fun line(canvas: Canvas, x1: Float, y1: Float, x2: Float, y2: Float, paint: Paint, seed: Long, amount: Float = 1.2f) {
        canvas.drawPath(wobble(x1, y1, x2, y2, seed, amount), paint)
        // The second pass: lighter, offset, and only over part of the line.
        val ghost = Paint(paint).apply { alpha = (paint.alpha * 0.4f).toInt() }
        canvas.drawPath(wobble(x1, y1, x2, y2, seed * 31 + 7, amount * 1.4f), ghost)
    }

    fun wobble(x1: Float, y1: Float, x2: Float, y2: Float, seed: Long, amount: Float = 1.2f): Path {
        val rng = Rng(seed)
        val path = Path()
        val dx = x2 - x1
        val dy = y2 - y1
        val length = kotlin.math.hypot(dx, dy)
        val steps = (length / 14f).toInt().coerceIn(1, 24)

        // Perpendicular, for pushing the joints sideways off the true line.
        val nx = if (length > 0) -dy / length else 0f
        val ny = if (length > 0) dx / length else 0f

        path.moveTo(x1, y1)
        for (i in 1..steps) {
            val t = i.toFloat() / steps
            // The ends stay put and the middle moves most: a pencil line is anchored where it
            // starts and stops, and wanders in between.
            val ease = kotlin.math.sin(t * Math.PI).toFloat()
            val off = ((rng.nextDouble() - 0.5) * 2).toFloat() * amount * ease
            path.lineTo(x1 + dx * t + nx * off, y1 + dy * t + ny * off)
        }
        return path
    }

    /** A hand-drawn rectangle: four wobbled lines that overshoot at the corners, as a hand does. */
    fun rect(canvas: Canvas, r: RectF, paint: Paint, seed: Long, amount: Float = 1.2f) {
        val over = amount * 1.6f
        line(canvas, r.left - over, r.top, r.right + over * 0.4f, r.top, paint, seed, amount)
        line(canvas, r.right, r.top - over * 0.4f, r.right, r.bottom + over, paint, seed + 1, amount)
        line(canvas, r.right + over, r.bottom, r.left - over * 0.4f, r.bottom, paint, seed + 2, amount)
        line(canvas, r.left, r.bottom + over * 0.4f, r.left, r.top - over, paint, seed + 3, amount)
    }

    /** A closed wobbled polygon, for country outlines and roofs. */
    fun polygon(canvas: Canvas, points: List<Pair<Float, Float>>, paint: Paint, seed: Long, amount: Float = 1.6f) {
        if (points.size < 2) return
        points.indices.forEach { i ->
            val (x1, y1) = points[i]
            val (x2, y2) = points[(i + 1) % points.size]
            line(canvas, x1, y1, x2, y2, paint, seed + i * 17L, amount)
        }
    }

    fun fillPolygon(canvas: Canvas, points: List<Pair<Float, Float>>, paint: Paint) {
        if (points.size < 3) return
        val path = Path()
        path.moveTo(points[0].first, points[0].second)
        points.drop(1).forEach { path.lineTo(it.first, it.second) }
        path.close()
        canvas.drawPath(path, paint)
    }

    /**
     * Cross-hatching: the way a pencil fills an area.
     *
     * Parallel diagonals of varying length, never a solid fill. A solid fill is a paint bucket and
     * reads as a different medium entirely — it is the single change that would break the whole
     * illusion.
     */
    fun hatch(canvas: Canvas, r: RectF, paint: Paint, seed: Long, spacing: Float, doubleHatch: Boolean = false) {
        val rng = Rng(seed)
        canvas.save()
        canvas.clipRect(r)
        var x = r.left - r.height()
        while (x < r.right + r.height()) {
            val jitter = ((rng.nextDouble() - 0.5) * spacing * 0.5).toFloat()
            val startY = r.top + (rng.nextDouble() * spacing * 0.6).toFloat()
            val endY = r.bottom - (rng.nextDouble() * spacing * 0.6).toFloat()
            canvas.drawPath(
                wobble(x + jitter, startY, x + jitter + (endY - startY), endY, rng.nextLong(), 0.9f),
                paint,
            )
            x += spacing
        }
        if (doubleHatch) {
            var y = r.top - r.width()
            while (y < r.bottom + r.width()) {
                val jitter = ((rng.nextDouble() - 0.5) * spacing * 0.5).toFloat()
                canvas.drawPath(
                    wobble(r.left, y + jitter, r.right, y + jitter + r.width(), rng.nextLong(), 0.9f),
                    paint,
                )
                y += spacing * 1.3f
            }
        }
        canvas.restore()
    }

    /** A smudge, for a building that has been rubbed out. */
    fun smudge(canvas: Canvas, cx: Float, cy: Float, radius: Float, dark: Boolean, seed: Long) {
        val rng = Rng(seed)
        val paint = shading(graphiteLight(dark), alpha = 26)
        repeat(9) {
            val r = radius * (0.4f + rng.nextDouble().toFloat() * 0.7f)
            canvas.drawCircle(
                cx + ((rng.nextDouble() - 0.5) * radius).toFloat(),
                cy + ((rng.nextDouble() - 0.5) * radius * 0.7).toFloat(),
                r, paint,
            )
        }
    }

    // -- Burning --------------------------------------------------------------

    /** The orange in the tin. Only ever used for fire, so fire is the one thing that reads as hot. */
    const val EMBER_PENCIL = 0xFFD98634.toInt()

    /**
     * Flames, scribbled.
     *
     * A flame drawn as a smooth tongue looks like a logo; a flame drawn as three or four hurried
     * strokes that lean the same way looks like somebody drew a fire. [phase] leans and stretches
     * them, so a still base still flickers.
     *
     * [seed] anchors WHERE they are, so a fire stays on the building it is burning rather than
     * dancing around the page between frames — the same rule the rest of the drawing follows.
     */
    fun fire(canvas: Canvas, cx: Float, baseY: Float, size: Float, seed: Long, phase: Float, dark: Boolean) {
        val rng = Rng(seed)
        val tongues = 3 + rng.nextInt(3)
        repeat(tongues) { i ->
            val offset = ((rng.nextDouble() - 0.5) * size * 0.8).toFloat()
            val height = size * (0.7f + rng.nextDouble().toFloat() * 0.8f)
            // Each tongue flickers on its own clock, or the whole fire pulses like one object.
            val flicker = kotlin.math.sin((phase * 2.4f + i * 1.7f).toDouble()).toFloat()
            val lean = flicker * size * 0.22f
            val tip = height * (0.85f + flicker * 0.15f)

            val hot = pencil(1.7f, EMBER_PENCIL, alpha = 235)
            val core = pencil(1.5f, RED_PENCIL, alpha = 215)
            val x = cx + offset
            line(canvas, x - size * 0.22f, baseY, x + lean, baseY - tip, hot, seed + i * 7, 2.2f)
            line(canvas, x + size * 0.22f, baseY, x + lean, baseY - tip, hot, seed + i * 7 + 1, 2.2f)
            line(canvas, x, baseY, x + lean * 0.6f, baseY - tip * 0.55f, core, seed + i * 7 + 2, 1.8f)
        }
    }

    /**
     * Smoke: curls that rise, drift and thin out.
     *
     * Drawn as open arcs rather than as filled grey, because a filled cloud on ruled paper stops
     * looking drawn immediately. Each curl carries its own height up the column so the plume reads
     * as one thing moving rather than as several puffs at the same altitude.
     */
    fun smoke(
        canvas: Canvas,
        cx: Float,
        baseY: Float,
        size: Float,
        seed: Long,
        phase: Float,
        dark: Boolean,
        puffs: Int = 5,
    ) {
        val rng = Rng(seed)
        repeat(puffs) { i ->
            // Where this puff is in its own life, 0 at the fire and 1 at the top of the column.
            val life = ((phase * 0.16f + i.toFloat() / puffs) % 1f)
            val rise = life * size * 4.2f
            val drift = (kotlin.math.sin((life * 3.0 + seed % 7).toDouble()).toFloat()) * size * 0.9f
            val radius = size * (0.28f + life * 0.75f)
            val fade = ((1f - life) * 120f).toInt().coerceIn(0, 120)
            if (fade <= 6) return@repeat

            val puff = pencil(1.3f, graphiteLight(dark), alpha = fade)
            val x = cx + drift + ((rng.nextDouble() - 0.5) * size * 0.4).toFloat()
            val y = baseY - rise
            // Two open arcs facing each other: the shorthand everybody uses for a puff of smoke.
            canvas.drawArc(
                x - radius, y - radius * 0.7f, x + radius, y + radius * 0.7f,
                200f, 220f, false, puff,
            )
            canvas.drawArc(
                x - radius * 0.6f, y - radius * 0.9f, x + radius * 0.9f, y + radius * 0.3f,
                160f, 200f, false, puff,
            )
        }
    }

    // -- Stick figures --------------------------------------------------------

    /**
     * A person, the way everybody draws a person.
     *
     * [phase] animates the legs and arms; pass the battle tick and they walk. [held] is a weapon
     * class, drawn as the simplest recognisable shape for it — a stick figure carrying a railgun is
     * still a stick figure carrying something obviously long and heavy.
     */
    fun stickFigure(
        canvas: Canvas,
        cx: Float,
        feetY: Float,
        height: Float,
        paint: Paint,
        seed: Long,
        phase: Float = 0f,
        held: HeldShape = HeldShape.NONE,
        facingRight: Boolean = true,
        /**
         * Whether this figure gets a jointed, two-segment gait or the cheap single-stroke one.
         *
         * ## Why there are two
         *
         * The first version of the jointed gait (knee, elbow, four two-segment limbs) roughly
         * TRIPLED the Path allocation of a figure -- about nineteen wobbled strokes against the
         * original six -- and it was applied to every figure everywhere, including the up-to-160
         * soldiers a battle draws at full detail. That is the same class of problem the crowd
         * batching in this file exists to solve: a detail that is exactly right for a couple of
         * dozen large, close-up civilians is a GC-churning liability at battle scale, where "more
         * than 200 soldiers" was reported to start slowing the game down heavily.
         *
         * So the jointed gait is now opt-in, used only where the count is always small and capped
         * (residents, the peacetime population, a household fleeing in a panic -- at most a few
         * dozen figures, drawn large enough that a knee is worth having) and OFF by default, which
         * is what every battle figure gets: the same opposed-swing, lifted-rear-foot walk, drawn as
         * a single stroke per limb, at the Path cost the renderer was actually tuned for.
         */
        detailed: Boolean = true,
    ) {
        // ── The gait ─────────────────────────────────────────────────────
        //
        // The old version swung both legs and one arm off a single sine and drew every limb as one
        // straight stick from joint to foot, with no knee, so the leading foot slid along the
        // ground rather than lifting, and the arms did not counter-swing. What both branches below
        // share is the fix for that at the COORDINATE level -- opposed phase and a lifted rear foot
        // -- which costs nothing extra to draw. What [detailed] controls is only whether a limb is
        // one stroke or two.
        val dir = if (facingRight) 1f else -1f
        val swing = kotlin.math.sin(phase.toDouble()).toFloat()
        val counter = kotlin.math.sin((phase + Math.PI).toDouble()).toFloat()
        // Twice the stride frequency: the body rises on each foot, not once per full cycle.
        val bob = kotlin.math.abs(kotlin.math.cos(phase.toDouble())).toFloat() * height * 0.022f

        val headR = height * 0.17f
        val headY = feetY - height + headR - bob
        val neckY = headY + headR
        val hipY = feetY - height * 0.38f - bob

        canvas.drawCircle(cx, headY, headR, paint)
        line(canvas, cx, neckY, cx, hipY, paint, seed, 0.7f)

        val stride = height * 0.20f
        val legLift = height * 0.13f
        val frontLift = if (swing > 0f) 0f else -legLift * (-swing)
        val backLift = if (counter > 0f) 0f else -legLift * (-counter)
        val frontX = cx + stride * swing * dir
        val frontY = feetY + frontLift
        val backX = cx + stride * counter * dir
        val backY = feetY + backLift

        val shoulderY = neckY + height * 0.10f
        val armSpread = height * 0.20f
        val armY = shoulderY + height * 0.20f
        val freeX = cx + armSpread * counter * dir * 0.9f
        val carries = held != HeldShape.NONE
        val handX = if (carries) cx + armSpread * dir else cx + armSpread * swing * dir * 0.9f
        val handY = if (carries) armY - height * 0.02f else armY

        if (detailed) {
            /** One limb, bent at the middle: hip to knee to foot, or shoulder to elbow to hand. */
            fun limb(fromX: Float, fromY: Float, toX: Float, toY: Float, bend: Float, limbSeed: Long) {
                val midX = (fromX + toX) / 2f + bend
                val midY = (fromY + toY) / 2f
                line(canvas, fromX, fromY, midX, midY, paint, limbSeed, 0.6f)
                line(canvas, midX, midY, toX, toY, paint, limbSeed + 1, 0.6f)
            }
            // A knee bends forward, so the bend follows the direction of travel.
            limb(cx, hipY, frontX, frontY, bend = dir * height * 0.05f * (1f + swing), limbSeed = seed + 1)
            limb(cx, hipY, backX, backY, bend = dir * height * 0.05f * (1f + counter), limbSeed = seed + 3)
            // The free arm counter-swings against the leading leg.
            limb(cx, shoulderY, freeX, armY, bend = -dir * height * 0.04f, limbSeed = seed + 5)
            // The carrying arm holds whatever it is holding out front and moves much less: a rifle
            // does not swing, which is itself a cue that this figure is armed and civilians are not.
            limb(cx, shoulderY, handX, handY, bend = dir * height * 0.03f, limbSeed = seed + 7)
        } else {
            // The cheap gait: one stroke per limb, straight to the same opposed-and-lifted
            // endpoints the detailed version uses. No knee, no elbow, no local closure -- this is
            // what every soldier in a battle is actually drawn with.
            line(canvas, cx, hipY, frontX, frontY, paint, seed + 1, 0.7f)
            line(canvas, cx, hipY, backX, backY, paint, seed + 3, 0.7f)
            line(canvas, cx, shoulderY, freeX, armY, paint, seed + 5, 0.7f)
            line(canvas, cx, shoulderY, handX, handY, paint, seed + 7, 0.7f)
        }
        drawHeld(canvas, handX, handY, height, dir, paint, seed + 9, held)
    }

        /** What the little figure is holding. Shapes, not models. */
    enum class HeldShape { NONE, STICK, BLADE, BOW, LONG_GUN, LAUNCHER, BEAM, SHIELD }

    private fun drawHeld(
        canvas: Canvas,
        x: Float,
        y: Float,
        height: Float,
        dir: Float,
        paint: Paint,
        seed: Long,
        shape: HeldShape,
    ) {
        val u = height * 0.1f
        when (shape) {
            HeldShape.NONE -> Unit
            HeldShape.STICK -> line(canvas, x, y + u, x + dir * u * 0.4f, y - u * 3f, paint, seed, 0.6f)
            HeldShape.BLADE -> {
                line(canvas, x, y + u * 0.4f, x + dir * u * 0.8f, y - u * 2.6f, paint, seed, 0.6f)
                line(canvas, x - dir * u * 0.3f, y, x + dir * u * 0.4f, y + u * 0.3f, paint, seed + 1, 0.5f)
            }
            HeldShape.BOW -> {
                val r = u * 1.6f
                val oval = RectF(x - r, y - r, x + r, y + r)
                canvas.drawArc(oval, if (dir > 0) -80f else 100f, 160f, false, paint)
                line(canvas, x + dir * r * 0.1f, y - r * 0.9f, x + dir * r * 0.1f, y + r * 0.9f, paint, seed, 0.4f)
            }
            HeldShape.LONG_GUN -> {
                line(canvas, x - dir * u, y + u * 0.5f, x + dir * u * 3.2f, y - u * 0.4f, paint, seed, 0.5f)
                line(canvas, x - dir * u, y + u * 0.5f, x - dir * u * 1.4f, y + u * 1.3f, paint, seed + 1, 0.5f)
            }
            HeldShape.LAUNCHER -> {
                line(canvas, x - dir * u * 1.4f, y + u * 0.8f, x + dir * u * 3f, y - u * 0.8f, paint, seed, 0.6f)
                canvas.drawCircle(x + dir * u * 3f, y - u * 0.8f, u * 0.7f, paint)
            }
            HeldShape.BEAM -> {
                line(canvas, x - dir * u * 0.6f, y + u * 0.4f, x + dir * u * 2.4f, y - u * 0.2f, paint, seed, 0.5f)
                val tip = Paint(paint).apply { alpha = 120 }
                line(canvas, x + dir * u * 2.4f, y - u * 0.2f, x + dir * u * 5f, y - u * 0.6f, tip, seed + 1, 2.2f)
            }
            HeldShape.SHIELD -> {
                val r = u * 1.5f
                rect(canvas, RectF(x - r * 0.6f, y - r, x + r * 0.6f, y + r), paint, seed, 0.7f)
            }
        }
    }

    // -- Crowds -------------------------------------------------------------
    //
    // Everything above draws in pencil, and pencil is expensive on purpose: every stroke is a fresh
    // Path of up to two dozen segments, plus a second lighter Path laid over the top, plus a Paint
    // for that second pass and an Rng to shake it. A stick figure is six strokes, so around a dozen
    // Paths and a dozen Rngs and a dozen Paints.
    //
    // That is exactly the right cost for a dozen figures and exactly the wrong cost for five
    // thousand. Five thousand figures is sixty thousand Paths and getting on for a million and a
    // half lineTo calls PER FRAME, all of it allocated and thrown away, and the UI thread simply
    // stops. The simulation was never the problem here; it gets through five thousand fighters in
    // sixty-eight milliseconds. The drawing was.
    //
    // So past a few hundred units the army stops being drawn and starts being MARKED. A unit
    // becomes four or five dead-straight segments appended to a FloatArray that is reused frame to
    // frame, and a whole squad reaches the canvas in one drawLines call: no Path, no Paint, no Rng,
    // no allocation at all, one native call instead of thirty thousand. The honest justification is
    // that at the size a unit occupies when five thousand of them are on a phone screen -- a few
    // pixels across -- a wobbled drawing and a straight mark are the same picture, so nothing is
    // actually lost. The pencil detail is kept for the case where you can see it.

    /**
     * A growable array of line segments, drawn in one go.
     *
     * Held by the view and reused, so a frame of five thousand units allocates nothing: the array
     * reaches its high-water mark on the first big battle and stays there.
     */
    class StrokeBatch(segments: Int = 1024) {
        private var data = FloatArray(segments * 4)
        private var used = 0

        fun seg(x1: Float, y1: Float, x2: Float, y2: Float) {
            if (used + 4 > data.size) data = data.copyOf(data.size * 2)
            data[used] = x1
            data[used + 1] = y1
            data[used + 2] = x2
            data[used + 3] = y2
            used += 4
        }

        /** Draws everything collected and empties the batch. */
        fun flush(canvas: Canvas, paint: Paint) {
            if (used > 0) canvas.drawLines(data, 0, used, paint)
            used = 0
        }

        val isEmpty: Boolean get() = used == 0
    }

    /** The same idea for dots -- heads, wheels, rotor hubs. One drawPoints per colour. */
    class PointBatch(points: Int = 1024) {
        private var data = FloatArray(points * 2)
        private var used = 0

        fun at(x: Float, y: Float) {
            if (used + 2 > data.size) data = data.copyOf(data.size * 2)
            data[used] = x
            data[used + 1] = y
            used += 2
        }

        fun flush(canvas: Canvas, paint: Paint) {
            if (used > 0) canvas.drawPoints(data, 0, used, paint)
            used = 0
        }

        val isEmpty: Boolean get() = used == 0
    }

    /**
     * One unit as a mark rather than a drawing: a few straight segments, and a dot for the head.
     *
     * The silhouettes are deliberately the ones [unit] draws, reduced to their minimum -- a soldier
     * is a spine and splayed legs, a tank is a box with a barrel, a jet is a chevron, a helicopter
     * is a body with a rotor bar over it. That matters because the whole point of drawing tanks and
     * jets and helicopters as their own units is being able to tell a column of armour from a column
     * of infantry, and losing that at scale would lose the feature at exactly the scale where the
     * formation is big enough to have armour in it.
     *
     * Nothing here allocates.
     */
    fun markUnit(
        strokes: StrokeBatch,
        dots: PointBatch,
        shape: UnitShape,
        cx: Float,
        feetY: Float,
        size: Float,
        facingRight: Boolean,
        phase: Float,
    ) {
        val dir = if (facingRight) 1f else -1f
        // Cheap and good enough for a gait: a triangle wave off the phase, no trigonometry per unit.
        val t = phase * 0.159f
        val f = t - kotlin.math.floor(t)
        val swing = if (f < 0.5f) f * 4f - 1f else 3f - f * 4f

        when (shape) {
            UnitShape.FOOT -> {
                // The same walk the drawn figure has, at the cost the batch can afford: opposed
                // legs, a lifted rear foot and a counter-swinging arm. No knee -- at the size a
                // marked figure occupies a knee is a sub-pixel detail -- but the foot lift and the
                // opposition are what the eye actually reads as walking, and both are free here.
                val counter = -swing
                val bob = kotlin.math.abs(swing) * size * 0.02f
                val headR = size * 0.17f
                val headY = feetY - size + headR - bob
                val hipY = feetY - size * 0.38f - bob
                dots.at(cx, headY)
                strokes.seg(cx, headY + headR, cx, hipY)

                val stride = size * 0.20f
                val lift = size * 0.11f
                val frontY = if (swing > 0f) feetY else feetY + swing * lift
                val backY = if (counter > 0f) feetY else feetY + counter * lift
                strokes.seg(cx, hipY, cx + stride * swing * dir, frontY)
                strokes.seg(cx, hipY, cx + stride * counter * dir, backY)

                val shoulderY = headY + headR + size * 0.10f
                strokes.seg(cx, shoulderY, cx + size * 0.28f * dir, shoulderY + size * 0.20f)
                strokes.seg(cx, shoulderY, cx + size * 0.22f * counter * dir, shoulderY + size * 0.20f)
            }

            UnitShape.TANK -> {
                val w = size * 0.95f
                val h = size * 0.42f
                val l = cx - w / 2
                val r = cx + w / 2
                val top = feetY - h
                strokes.seg(l, top, r, top)
                strokes.seg(r, top, r, feetY)
                strokes.seg(r, feetY, l, feetY)
                strokes.seg(l, feetY, l, top)
                strokes.seg(cx + dir * w * 0.15f, top - size * 0.14f, cx + dir * w * 0.7f, top - size * 0.14f)
                dots.at(cx, top - size * 0.1f)
            }

            UnitShape.JET -> {
                val y = feetY - size * 0.75f
                val l = size * 0.9f
                val nose = cx + dir * l * 0.5f
                val tail = cx - dir * l * 0.5f
                strokes.seg(nose, y, tail, y - size * 0.14f)
                strokes.seg(nose, y, tail, y + size * 0.14f)
                strokes.seg(cx, y, cx - dir * l * 0.3f, y - size * 0.32f)
                strokes.seg(cx, y, cx - dir * l * 0.3f, y + size * 0.32f)
            }

            UnitShape.HELICOPTER -> {
                val y = feetY - size * 0.62f
                strokes.seg(cx - size * 0.3f, y, cx + size * 0.3f, y)
                strokes.seg(cx - dir * size * 0.3f, y, cx - dir * size * 0.78f, y - size * 0.06f)
                // The rotor sweeps, which is what stops a field of them looking like parked boxes.
                val sweep = size * (0.24f + 0.26f * kotlin.math.abs(swing))
                strokes.seg(cx - sweep, y - size * 0.3f, cx + sweep, y - size * 0.3f)
                strokes.seg(cx, y - size * 0.3f, cx, y)
            }

            UnitShape.MISSILE -> {
                val w = size * 0.78f
                val baseY = feetY - size * 0.16f
                strokes.seg(cx - w / 2, baseY, cx + w / 2, baseY)
                strokes.seg(cx - dir * w * 0.2f, baseY, cx + dir * w * 0.46f, feetY - size * 0.78f)
                dots.at(cx - w * 0.28f, feetY)
                dots.at(cx + w * 0.28f, feetY)
            }

            UnitShape.ARTILLERY_PIECE -> {
                strokes.seg(cx, feetY - size * 0.12f, cx + dir * size * 0.6f, feetY - size * 0.52f)
                strokes.seg(cx, feetY - size * 0.1f, cx - dir * size * 0.42f, feetY)
                dots.at(cx, feetY - size * 0.1f)
            }

            UnitShape.MECH -> {
                val y = feetY - size * 0.5f
                strokes.seg(cx - size * 0.26f, y - size * 0.3f, cx + size * 0.26f, y - size * 0.3f)
                strokes.seg(cx - size * 0.26f, y - size * 0.3f, cx - size * 0.26f, y)
                strokes.seg(cx + size * 0.26f, y - size * 0.3f, cx + size * 0.26f, y)
                val stride = swing * size * 0.16f
                strokes.seg(cx - size * 0.14f, y, cx - size * 0.2f + stride, feetY)
                strokes.seg(cx + size * 0.14f, y, cx + size * 0.2f - stride, feetY)
            }

            UnitShape.DRONE_CRAFT -> {
                val y = feetY - size * 0.7f
                strokes.seg(cx - size * 0.34f, y - size * 0.22f, cx + size * 0.34f, y + size * 0.22f)
                strokes.seg(cx + size * 0.34f, y - size * 0.22f, cx - size * 0.34f, y + size * 0.22f)
                dots.at(cx, y)
            }
        }
    }

    /** The dot paint that goes with [markUnit]: a round cap of the right size is a pencil dot. */
    fun dotPaint(colour: Int, diameter: Float, alpha: Int = 235): Paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = colour
            this.alpha = alpha
            strokeWidth = diameter
            strokeCap = Paint.Cap.ROUND
            style = Paint.Style.STROKE
        }

    /**
     * What a unit IS, rather than what it is holding.
     *
     * Some weapons are not carried -- they are driven, flown or fired from a launcher -- and
     * drawing a stick figure holding a tank was the wrong picture in a way that got worse as the
     * eras advanced. From the modern era on a formation is tanks, jets, helicopters and missile
     * batteries, and each of those is its own drawing.
     */
    enum class UnitShape { FOOT, TANK, JET, HELICOPTER, MISSILE, ARTILLERY_PIECE, MECH, DRONE_CRAFT }

    /**
     * What a weapon goes to war as.
     *
     * Takes the weapon rather than only its class, because the class cannot tell a jet from a
     * helicopter -- the catalogue files both under AIRCRAFT and says which is which in the name.
     * Going through the class alone meant every helicopter in the game was drawn as a fixed wing.
     */
    fun unitShapeFor(weapon: WeaponCatalog.Weapon): UnitShape =
        if (weapon.isRotaryWing) UnitShape.HELICOPTER else unitShapeFor(weapon.weaponClass)

    fun unitShapeFor(weaponClass: WeaponCatalog.WeaponClass): UnitShape = when (weaponClass) {
        WeaponCatalog.WeaponClass.ARMOUR -> UnitShape.TANK
        WeaponCatalog.WeaponClass.AIRCRAFT -> UnitShape.JET
        WeaponCatalog.WeaponClass.MISSILE -> UnitShape.MISSILE
        WeaponCatalog.WeaponClass.ARTILLERY, WeaponCatalog.WeaponClass.SIEGE -> UnitShape.ARTILLERY_PIECE
        WeaponCatalog.WeaponClass.DRONE -> UnitShape.DRONE_CRAFT
        else -> UnitShape.FOOT
    }

    /**
     * A unit of whatever kind, standing at [cx],[feetY].
     *
     * Everything is drawn to roughly the same footprint so a mixed formation reads as one army
     * rather than as a scale drawing: a real tank is not four times a soldier, but a tank drawn
     * four times the size of one would swamp the paper.
     */
    fun unit(
        canvas: Canvas,
        shape: UnitShape,
        cx: Float,
        feetY: Float,
        size: Float,
        paint: Paint,
        seed: Long,
        phase: Float,
        facingRight: Boolean,
        held: HeldShape = HeldShape.NONE,
    ) {
        val dir = if (facingRight) 1f else -1f
        when (shape) {
            UnitShape.FOOT ->
                // Battle scale: the cheap single-stroke gait. See stickFigure's `detailed` doc.
                stickFigure(canvas, cx, feetY, size, paint, seed, phase, held, facingRight, detailed = false)

            UnitShape.TANK -> {
                val w = size * 0.95f
                val h = size * 0.42f
                val body = RectF(cx - w / 2, feetY - h, cx + w / 2, feetY)
                rect(canvas, body, paint, seed, 0.8f)
                hatch(canvas, body, Paint(paint).apply { alpha = 90; strokeWidth = paint.strokeWidth * 0.6f }, seed + 1, w / 5f)
                val turret = RectF(cx - w * 0.22f, feetY - h - size * 0.22f, cx + w * 0.22f, feetY - h)
                rect(canvas, turret, paint, seed + 2, 0.7f)
                // A barrel pointing the way it is going.
                line(canvas, cx + dir * w * 0.2f, feetY - h - size * 0.10f,
                    cx + dir * w * 0.72f, feetY - h - size * 0.12f, paint, seed + 3, 0.6f)
                // Road wheels, which is what says "tracked" at this size.
                repeat(4) { i ->
                    canvas.drawCircle(cx - w * 0.36f + i * w * 0.24f, feetY, size * 0.07f, paint)
                }
            }

            UnitShape.JET -> {
                // Drawn in flight, above the ground line, with a shadow beneath it.
                val y = feetY - size * 0.75f
                val l = size * 0.9f
                polygon(
                    canvas,
                    listOf(
                        (cx + dir * l * 0.5f) to y,
                        (cx - dir * l * 0.3f) to (y - size * 0.16f),
                        (cx - dir * l * 0.5f) to y,
                        (cx - dir * l * 0.3f) to (y + size * 0.16f),
                    ),
                    paint, seed, 0.9f,
                )
                line(canvas, cx, y, cx - dir * l * 0.32f, y - size * 0.34f, paint, seed + 1, 0.7f)
                line(canvas, cx, y, cx - dir * l * 0.32f, y + size * 0.34f, paint, seed + 2, 0.7f)
                canvas.drawOval(
                    RectF(cx - l * 0.28f, feetY - size * 0.04f, cx + l * 0.28f, feetY + size * 0.06f),
                    shading(paint.color, alpha = 40),
                )
            }

            UnitShape.HELICOPTER -> {
                val y = feetY - size * 0.62f
                val body = RectF(cx - size * 0.32f, y - size * 0.16f, cx + size * 0.32f, y + size * 0.16f)
                rect(canvas, body, paint, seed, 0.8f)
                // Tail boom and rotor: the two things that make it not a plane.
                line(canvas, cx - dir * size * 0.3f, y, cx - dir * size * 0.78f, y - size * 0.06f, paint, seed + 1, 0.6f)
                val spin = kotlin.math.sin(phase.toDouble()).toFloat() * size * 0.5f
                line(canvas, cx - size * 0.5f + spin * 0.2f, y - size * 0.3f,
                    cx + size * 0.5f + spin * 0.2f, y - size * 0.3f, paint, seed + 2, 0.5f)
                line(canvas, cx, y - size * 0.3f, cx, y - size * 0.16f, paint, seed + 3, 0.5f)
            }

            UnitShape.MISSILE -> {
                // A launcher box on wheels with a missile angled off it.
                val w = size * 0.78f
                val body = RectF(cx - w / 2, feetY - size * 0.3f, cx + w / 2, feetY - size * 0.05f)
                rect(canvas, body, paint, seed, 0.8f)
                canvas.drawCircle(cx - w * 0.28f, feetY, size * 0.07f, paint)
                canvas.drawCircle(cx + w * 0.28f, feetY, size * 0.07f, paint)
                val tipX = cx + dir * w * 0.46f
                val tipY = feetY - size * 0.78f
                line(canvas, cx - dir * w * 0.2f, feetY - size * 0.28f, tipX, tipY, paint, seed + 1, 0.6f)
                polygon(
                    canvas,
                    listOf(
                        tipX to tipY,
                        (tipX - dir * size * 0.12f) to (tipY + size * 0.1f),
                        (tipX - dir * size * 0.02f) to (tipY + size * 0.14f),
                    ),
                    paint, seed + 2, 0.6f,
                )
            }

            UnitShape.ARTILLERY_PIECE -> {
                // A trail, a wheel and a barrel up at an angle.
                canvas.drawCircle(cx, feetY - size * 0.1f, size * 0.14f, paint)
                line(canvas, cx, feetY - size * 0.1f, cx - dir * size * 0.42f, feetY, paint, seed, 0.6f)
                line(canvas, cx, feetY - size * 0.12f, cx + dir * size * 0.6f, feetY - size * 0.52f, paint, seed + 1, 0.7f)
                line(canvas, cx - dir * size * 0.1f, feetY - size * 0.12f,
                    cx + dir * size * 0.16f, feetY - size * 0.26f, paint, seed + 2, 0.5f)
            }

            UnitShape.MECH -> {
                val y = feetY - size * 0.5f
                rect(canvas, RectF(cx - size * 0.26f, y - size * 0.3f, cx + size * 0.26f, y), paint, seed, 0.8f)
                val stride = kotlin.math.sin(phase.toDouble()).toFloat() * size * 0.16f
                line(canvas, cx - size * 0.14f, y, cx - size * 0.2f + stride, feetY, paint, seed + 1, 0.6f)
                line(canvas, cx + size * 0.14f, y, cx + size * 0.2f - stride, feetY, paint, seed + 2, 0.6f)
                line(canvas, cx + dir * size * 0.26f, y - size * 0.2f,
                    cx + dir * size * 0.62f, y - size * 0.24f, paint, seed + 3, 0.6f)
            }

            UnitShape.DRONE_CRAFT -> {
                val y = feetY - size * 0.7f
                canvas.drawCircle(cx, y, size * 0.18f, paint)
                val bob = kotlin.math.sin(phase.toDouble()).toFloat() * size * 0.04f
                listOf(-1f to -1f, 1f to -1f, -1f to 1f, 1f to 1f).forEachIndexed { i, arm ->
                    val ex = cx + arm.first * size * 0.34f
                    val ey = y + arm.second * size * 0.22f + bob
                    line(canvas, cx, y, ex, ey, paint, seed + i, 0.5f)
                    canvas.drawCircle(ex, ey, size * 0.07f, Paint(paint).apply { alpha = 150 })
                }
            }
        }
    }

    /** What a weapon class looks like in a stick figure's hand. */
    fun heldFor(weaponClass: WeaponCatalog.WeaponClass): HeldShape = when (weaponClass) {
        WeaponCatalog.WeaponClass.BLADE -> HeldShape.BLADE
        WeaponCatalog.WeaponClass.BLUNT, WeaponCatalog.WeaponClass.POLEARM -> HeldShape.STICK
        WeaponCatalog.WeaponClass.BOW, WeaponCatalog.WeaponClass.CROSSBOW -> HeldShape.BOW
        WeaponCatalog.WeaponClass.THROWN -> HeldShape.STICK
        WeaponCatalog.WeaponClass.FIREARM, WeaponCatalog.WeaponClass.AUTOMATIC,
        WeaponCatalog.WeaponClass.RAIL,
        -> HeldShape.LONG_GUN
        WeaponCatalog.WeaponClass.EXPLOSIVE, WeaponCatalog.WeaponClass.MISSILE,
        WeaponCatalog.WeaponClass.ARTILLERY, WeaponCatalog.WeaponClass.SIEGE,
        -> HeldShape.LAUNCHER
        WeaponCatalog.WeaponClass.BEAM, WeaponCatalog.WeaponClass.PLASMA,
        WeaponCatalog.WeaponClass.EXOTIC,
        -> HeldShape.BEAM
        WeaponCatalog.WeaponClass.SUPPORT -> HeldShape.SHIELD
        else -> HeldShape.LONG_GUN
    }

    /**
     * A stick animal: four legs, a body line, a head and a tail.
     *
     * The design asks for stick animals rather than people where animals belong, and the honest
     * reading of "stick animal" is the one every child draws — a horizontal line with legs coming
     * off it. Which animal it is comes from the proportions, not from detail.
     */
    fun stickAnimal(
        canvas: Canvas,
        cx: Float,
        feetY: Float,
        length: Float,
        paint: Paint,
        seed: Long,
        tall: Boolean = false,
        horns: Boolean = false,
        phase: Float = 0f,
    ) {
        val bodyY = feetY - length * (if (tall) 0.62f else 0.42f)
        val half = length / 2f
        line(canvas, cx - half, bodyY, cx + half, bodyY, paint, seed, 0.8f)

        val swing = kotlin.math.sin(phase.toDouble()).toFloat() * length * 0.06f
        listOf(-half * 0.7f to swing, -half * 0.4f to -swing, half * 0.4f to -swing, half * 0.7f to swing)
            .forEachIndexed { i, (offset, kick) ->
                line(canvas, cx + offset, bodyY, cx + offset + kick, feetY, paint, seed + i + 1, 0.6f)
            }

        val headR = length * 0.14f
        val headX = cx + half + headR * 0.5f
        val headY = bodyY - (if (tall) length * 0.18f else length * 0.06f)
        line(canvas, cx + half, bodyY, headX, headY, paint, seed + 9, 0.6f)
        canvas.drawCircle(headX, headY, headR, paint)

        if (horns) {
            line(canvas, headX, headY - headR, headX - headR * 0.6f, headY - headR * 2f, paint, seed + 10, 0.5f)
            line(canvas, headX, headY - headR, headX + headR * 0.6f, headY - headR * 2f, paint, seed + 11, 0.5f)
        }
        line(canvas, cx - half, bodyY, cx - half - length * 0.18f, bodyY - length * 0.12f, paint, seed + 12, 0.7f)
    }

    // -- Buildings ------------------------------------------------------------

    /**
     * A building, drawn from its category and how modern it is.
     *
     * [modernity] is 0 for the first medieval hut and 1 for the last futuristic spire, and it is
     * what turns a pitched roof into a flat one, adds chimneys and then aerials, and finally lifts
     * the whole thing off the ground. That single scalar is why two hundred and sixty building
     * types do not need two hundred and sixty drawings.
     */
    fun building(
        canvas: Canvas,
        bounds: RectF,
        category: BuildingCatalog.Category,
        modernity: Float,
        paint: Paint,
        fillPaint: Paint,
        seed: Long,
        damaged: Boolean = false,
        underConstruction: Float = 1f,
    ) {
        val rng = Rng(seed)
        val w = bounds.width()
        val h = bounds.height()

        if (underConstruction < 1f) {
            // Scaffolding: the outline plus diagonals, which is what a plan looks like on paper.
            val ghost = Paint(paint).apply { alpha = 90; pathEffect = android.graphics.DashPathEffect(floatArrayOf(6f, 7f), 0f) }
            rect(canvas, bounds, ghost, seed)
            line(canvas, bounds.left, bounds.bottom, bounds.right, bounds.top, ghost, seed + 40, 1f)
            line(canvas, bounds.left, bounds.top, bounds.right, bounds.bottom, ghost, seed + 41, 1f)
            val doneH = h * underConstruction
            val built = RectF(bounds.left, bounds.bottom - doneH, bounds.right, bounds.bottom)
            if (doneH > 3f) hatch(canvas, built, Paint(paint).apply { alpha = 70; strokeWidth = paint.strokeWidth * 0.7f }, seed + 3, w / 5f)
            return
        }

        val futuristic = modernity > 0.62f
        val modern = modernity > 0.30f

        val bodyTop = bounds.top + h * (if (futuristic) 0.10f else if (modern) 0.20f else 0.34f)
        val body = RectF(bounds.left, bodyTop, bounds.right, bounds.bottom)

        // The body.
        rect(canvas, body, paint, seed)
        hatch(canvas, RectF(body.left + 1, body.top + 1, body.right - 1, body.bottom - 1), fillPaint, seed + 1, w / 6f)

        // The roof, which is where the era shows most.
        when {
            futuristic -> {
                // Flat, with a mast and a floating ring: unmistakably not a house.
                line(canvas, bounds.left, bodyTop, bounds.right, bodyTop, paint, seed + 2, 1f)
                val mastX = bounds.centerX()
                line(canvas, mastX, bodyTop, mastX, bounds.top, paint, seed + 3, 0.8f)
                canvas.drawOval(
                    RectF(mastX - w * 0.30f, bounds.top - h * 0.03f, mastX + w * 0.30f, bounds.top + h * 0.05f),
                    Paint(paint).apply { alpha = 150 },
                )
            }
            modern -> {
                // Flat roof, parapet, and a box of plant on top.
                line(canvas, bounds.left, bodyTop, bounds.right, bodyTop, paint, seed + 2, 1f)
                val plant = RectF(
                    bounds.left + w * 0.55f, bodyTop - h * 0.12f,
                    bounds.left + w * 0.85f, bodyTop,
                )
                rect(canvas, plant, paint, seed + 4, 0.9f)
            }
            else -> {
                // A pitched roof, drawn as a triangle that overshoots the walls.
                val peak = bounds.centerX() + ((rng.nextDouble() - 0.5) * w * 0.08).toFloat()
                polygon(
                    canvas,
                    listOf(
                        bounds.left - w * 0.06f to bodyTop,
                        peak to bounds.top,
                        bounds.right + w * 0.06f to bodyTop,
                    ),
                    paint, seed + 2, 1.3f,
                )
                if (category == BuildingCatalog.Category.INDUSTRY ||
                    category == BuildingCatalog.Category.RESOURCE
                ) {
                    val chimX = bounds.left + w * 0.72f
                    rect(
                        canvas,
                        RectF(chimX, bounds.top + h * 0.02f, chimX + w * 0.12f, bodyTop),
                        paint, seed + 5, 0.8f,
                    )
                }
            }
        }

        // A door and a window or two, which is what makes a box read as a building at all.
        val doorW = w * 0.22f
        val doorH = h * 0.30f
        rect(
            canvas,
            RectF(body.centerX() - doorW / 2, body.bottom - doorH, body.centerX() + doorW / 2, body.bottom),
            paint, seed + 6, 0.7f,
        )
        if (w > 26f) {
            val winW = w * 0.16f
            listOf(0.18f, 0.72f).forEach { at ->
                val wx = body.left + w * at
                rect(
                    canvas,
                    RectF(wx, body.top + h * 0.12f, wx + winW, body.top + h * 0.12f + winW),
                    paint, seed + 7 + (at * 100).toLong(), 0.6f,
                )
            }
        }

        // Category marks, so a player can read a base at a glance without labels.
        when (category) {
            BuildingCatalog.Category.DEFENCE -> {
                // Crenellations along the top of the body.
                var x = body.left
                val step = w / 5f
                while (x < body.right - step * 0.5f) {
                    rect(canvas, RectF(x, body.top - step * 0.5f, x + step * 0.5f, body.top), paint, seed + x.toLong(), 0.5f)
                    x += step
                }
            }
            BuildingCatalog.Category.MILITARY -> {
                // A flag.
                val fx = bounds.right - w * 0.1f
                line(canvas, fx, bounds.bottom, fx, bounds.top - h * 0.1f, paint, seed + 20, 0.6f)
                polygon(
                    canvas,
                    listOf(
                        fx to bounds.top - h * 0.1f,
                        fx + w * 0.22f to bounds.top - h * 0.04f,
                        fx to bounds.top + h * 0.02f,
                    ),
                    paint, seed + 21, 0.8f,
                )
            }
            BuildingCatalog.Category.RESEARCH -> {
                // An open book on the roof line.
                val bx = body.centerX()
                val by = bodyTop - h * 0.06f
                line(canvas, bx - w * 0.18f, by, bx, by - h * 0.05f, paint, seed + 22, 0.5f)
                line(canvas, bx, by - h * 0.05f, bx + w * 0.18f, by, paint, seed + 23, 0.5f)
            }
            BuildingCatalog.Category.RESOURCE -> {
                // Furrows in front, which is the universal drawing for "this is a farm".
                repeat(3) { i ->
                    val y = body.bottom + (i + 1) * 2.5f
                    line(canvas, body.left, y, body.right, y, Paint(paint).apply { alpha = 120 }, seed + 30 + i, 0.8f)
                }
            }
            else -> Unit
        }

        if (damaged) {
            // Cracks, and a rubbed-out corner.
            val crack = Paint(paint).apply { alpha = 200; strokeWidth = paint.strokeWidth * 0.8f }
            line(canvas, body.left + w * 0.3f, body.top, body.left + w * 0.45f, body.bottom, crack, seed + 50, 2.4f)
            line(canvas, body.left + w * 0.45f, body.centerY(), body.right, body.centerY() + h * 0.1f, crack, seed + 51, 2.4f)
        }
    }

    /** How modern a building of this tier looks, 0..1 across the whole game. */
    fun modernityOf(unlockLevel: Int): Float =
        ((unlockLevel - 1).toFloat() / (Era.MAX_LEVEL - 1)).coerceIn(0f, 1f)

    // -- Handwriting ----------------------------------------------------------

    /**
     * Text in pencil.
     *
     * Not a font: Prism already ships a handwriting-ish typeface for the rest of the UI, and this
     * only has to make text look like it belongs on the page. Slight rotation and graphite colour
     * does that; a "handwriting font" scaled up would fight the drawn lines rather than match them.
     */
    fun textPaint(sizePx: Float, dark: Boolean, colour: Int? = null): Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sizePx
        color = colour ?: graphite(dark)
        isFakeBoldText = false
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.SERIF, android.graphics.Typeface.NORMAL)
    }

    fun writtenText(canvas: Canvas, text: String, x: Float, y: Float, paint: Paint, seed: Long) {
        val rng = Rng(seed)
        canvas.save()
        canvas.rotate(((rng.nextDouble() - 0.5) * 1.6).toFloat(), x, y)
        canvas.drawText(text, x, y, paint)
        canvas.restore()
    }

    /** A box drawn round something, the way you circle an answer. */
    fun circleAround(canvas: Canvas, r: RectF, paint: Paint, seed: Long) {
        val rng = Rng(seed)
        val path = Path()
        val steps = 26
        val cx = r.centerX()
        val cy = r.centerY()
        val rx = r.width() / 2f * 1.14f
        val ry = r.height() / 2f * 1.25f
        // Overshoot the start, the way a circled word on paper always does.
        for (i in 0..steps + 3) {
            val t = i.toFloat() / steps * 2.0 * Math.PI
            val jitter = 1f + ((rng.nextDouble() - 0.5) * 0.09).toFloat()
            val px = cx + (rx * jitter * kotlin.math.cos(t)).toFloat()
            val py = cy + (ry * jitter * kotlin.math.sin(t)).toFloat()
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        canvas.drawPath(path, paint)
    }

    /** A tick, for accepting things. Drawn, not an emoji: the page has no emoji on it. */
    fun tick(canvas: Canvas, r: RectF, paint: Paint, seed: Long) {
        line(canvas, r.left, r.centerY(), r.centerX() - r.width() * 0.08f, r.bottom - r.height() * 0.15f, paint, seed, 0.8f)
        line(canvas, r.centerX() - r.width() * 0.08f, r.bottom - r.height() * 0.15f, r.right, r.top + r.height() * 0.12f, paint, seed + 1, 0.8f)
    }

    /** A cross, for refusing them. */
    fun cross(canvas: Canvas, r: RectF, paint: Paint, seed: Long) {
        line(canvas, r.left, r.top, r.right, r.bottom, paint, seed, 0.9f)
        line(canvas, r.right, r.top, r.left, r.bottom, paint, seed + 1, 0.9f)
    }

    /** A right-facing arrow. Flipped for the left-facing one. */
    fun arrow(canvas: Canvas, r: RectF, paint: Paint, seed: Long, pointingRight: Boolean) {
        val tipX = if (pointingRight) r.right else r.left
        val tailX = if (pointingRight) r.left else r.right
        line(canvas, tailX, r.centerY(), tipX, r.centerY(), paint, seed, 0.7f)
        val back = if (pointingRight) -r.width() * 0.34f else r.width() * 0.34f
        line(canvas, tipX, r.centerY(), tipX + back, r.top + r.height() * 0.18f, paint, seed + 1, 0.7f)
        line(canvas, tipX, r.centerY(), tipX + back, r.bottom - r.height() * 0.18f, paint, seed + 2, 0.7f)
    }

    /** A pencil-drawn map pin, for the map button. */
    fun mapGlyph(canvas: Canvas, r: RectF, paint: Paint, seed: Long) {
        // A folded map: a rectangle with two vertical creases and a wavy route across it.
        rect(canvas, r, paint, seed, 0.9f)
        val third = r.width() / 3f
        line(canvas, r.left + third, r.top - 2f, r.left + third, r.bottom + 2f, paint, seed + 1, 0.9f)
        line(canvas, r.left + third * 2, r.top + 2f, r.left + third * 2, r.bottom - 2f, paint, seed + 2, 0.9f)
        val route = Paint(paint).apply { alpha = 150; pathEffect = android.graphics.DashPathEffect(floatArrayOf(4f, 5f), 0f) }
        line(canvas, r.left + 4f, r.bottom - 5f, r.right - 4f, r.top + 5f, route, seed + 3, 2.2f)
    }

    /** An envelope, for the inbox button. */
    fun envelopeGlyph(canvas: Canvas, r: RectF, paint: Paint, seed: Long) {
        rect(canvas, r, paint, seed, 0.9f)
        line(canvas, r.left, r.top, r.centerX(), r.centerY(), paint, seed + 1, 0.9f)
        line(canvas, r.centerX(), r.centerY(), r.right, r.top, paint, seed + 2, 0.9f)
    }

    /**
     * A hammer, for Build.
     *
     * The head is drawn as a filled-ish box rather than an outline because at forty-four density
     * pixels an outlined head reads as a smudge — the silhouette is the only thing that survives,
     * so the silhouette is what these glyphs are.
     */
    fun hammerGlyph(canvas: Canvas, r: RectF, paint: Paint, seed: Long) {
        val w = r.width()
        val h = r.height()
        // Handle, corner to corner, leaning the way a hammer is held.
        line(
            canvas,
            r.left + w * 0.24f, r.bottom - h * 0.06f,
            r.left + w * 0.62f, r.top + h * 0.34f,
            paint, seed, 0.7f,
        )
        val head = RectF(
            r.left + w * 0.46f, r.top + h * 0.06f,
            r.right - w * 0.02f, r.top + h * 0.34f,
        )
        rect(canvas, head, paint, seed + 1, 0.8f)
        hatch(canvas, head, Paint(paint).apply { alpha = 110; strokeWidth = paint.strokeWidth * 0.6f },
            seed + 2, w * 0.11f)
    }

    /** Crossed swords, for Army. */
    fun swordsGlyph(canvas: Canvas, r: RectF, paint: Paint, seed: Long) {
        val w = r.width()
        val h = r.height()
        // Two blades, crossing near the middle.
        line(canvas, r.left + w * 0.08f, r.bottom - h * 0.06f, r.right - w * 0.14f, r.top + h * 0.04f, paint, seed, 0.7f)
        line(canvas, r.right - w * 0.08f, r.bottom - h * 0.06f, r.left + w * 0.14f, r.top + h * 0.04f, paint, seed + 1, 0.7f)
        // Crossguards, low on each blade, which is what makes them swords and not sticks.
        line(canvas, r.left + w * 0.06f, r.bottom - h * 0.34f, r.left + w * 0.34f, r.bottom - h * 0.18f, paint, seed + 2, 0.6f)
        line(canvas, r.right - w * 0.06f, r.bottom - h * 0.34f, r.right - w * 0.34f, r.bottom - h * 0.18f, paint, seed + 3, 0.6f)
    }

    /** An anvil, for weapons research. */
    fun anvilGlyph(canvas: Canvas, r: RectF, paint: Paint, seed: Long) {
        val w = r.width()
        val h = r.height()
        val topY = r.top + h * 0.26f
        val waistY = r.top + h * 0.48f
        // The face, the horn, the waist and the foot — an anvil's outline in four strokes.
        polygon(
            canvas,
            listOf(
                (r.left + w * 0.06f) to topY,
                (r.right - w * 0.02f) to (topY + h * 0.06f),
                (r.right - w * 0.22f) to (topY + h * 0.12f),
                (r.left + w * 0.62f) to waistY,
                (r.left + w * 0.30f) to waistY,
                (r.left + w * 0.06f) to (topY + h * 0.12f),
            ),
            paint, seed, 1.0f,
        )
        line(canvas, r.left + w * 0.36f, waistY, r.left + w * 0.34f, r.bottom - h * 0.16f, paint, seed + 1, 0.6f)
        line(canvas, r.left + w * 0.58f, waistY, r.left + w * 0.60f, r.bottom - h * 0.16f, paint, seed + 2, 0.6f)
        line(canvas, r.left + w * 0.18f, r.bottom - h * 0.12f, r.right - w * 0.26f, r.bottom - h * 0.12f, paint, seed + 3, 0.8f)
    }

    /** A scroll, for the state and its laws. */
    fun scrollGlyph(canvas: Canvas, r: RectF, paint: Paint, seed: Long) {
        val w = r.width()
        val h = r.height()
        val body = RectF(r.left + w * 0.16f, r.top + h * 0.10f, r.right - w * 0.16f, r.bottom - h * 0.10f)
        rect(canvas, body, paint, seed, 0.9f)
        // The rolled ends, which is the whole difference between a scroll and a sheet of paper.
        canvas.drawArc(
            RectF(r.left + w * 0.04f, r.top + h * 0.06f, r.left + w * 0.28f, r.top + h * 0.30f),
            90f, 260f, false, paint,
        )
        canvas.drawArc(
            RectF(r.right - w * 0.28f, r.bottom - h * 0.30f, r.right - w * 0.04f, r.bottom - h * 0.06f),
            270f, 260f, false, paint,
        )
        // Two lines of writing, because a blank scroll is an envelope.
        val text = Paint(paint).apply { alpha = 140; strokeWidth = paint.strokeWidth * 0.6f }
        line(canvas, body.left + w * 0.10f, body.top + h * 0.26f, body.right - w * 0.10f, body.top + h * 0.26f, text, seed + 1, 0.7f)
        line(canvas, body.left + w * 0.10f, body.centerY() + h * 0.02f, body.right - w * 0.20f, body.centerY() + h * 0.02f, text, seed + 2, 0.7f)
    }
}
