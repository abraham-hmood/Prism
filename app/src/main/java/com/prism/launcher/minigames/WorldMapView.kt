package com.prism.launcher.minigames

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View

/**
 * The world, drawn as a map somebody sketched in the back of an exercise book — and it does not end.
 *
 * ## Endless, and why that is cheap
 *
 * There is no world to hold. The view knows where it is looking and asks [WorldChunks] what is
 * inside that rectangle; countries are generated from their coordinates on the way past and thrown
 * away when they leave. Panning and zooming are the same operation — they change the rectangle —
 * which is why a country found by zooming out is the same country found by scrolling to it. It has
 * to be: both are the same function of the same seed.
 *
 * Nothing outside the rectangle is generated, so the cost of a frame depends on the size of the
 * screen and not on how far the player has travelled. That is ordinary frustum culling, and it is
 * what makes "no limit on zoom-out, no limit on countries" a sentence that can actually be
 * implemented rather than a wish.
 *
 * ## What is on it depends on the mesh, honestly
 *
 * AI countries always. Player countries only when this device is on a Prism Meshnet with somebody
 * else on it — the design says so, and inventing player countries that are really AI would make
 * every alliance request a lie.
 */
@SuppressLint("ViewConstructor")
class WorldMapView(
    context: Context,
    private val onOpenCountry: (WorldMap.Country) -> Unit,
    private val onZoomInto: (WorldMap.Country) -> Unit,
) : View(context) {

    private val density = resources.displayMetrics.density
    private val dark: Boolean get() = PaperUi.isDark(context)

    /** The seed the whole world hangs off, and the player's own level. Both set by the host. */
    var worldSeed: Long = 0
    var playerLevel: Int = 1

    /** The player's own country, and any peers. Everything else is generated. */
    var fixedCountries: List<WorldMap.Country> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    /** Relations the player has with specific countries, by id. */
    var relations: Map<String, WorldMap.Relation> = emptyMap()

    /**
     * Countries that have been conquered, and by whom.
     *
     * A conquered country is no longer a country. It keeps its shape on the paper — the land does
     * not move — but it loses its own border, its name and its level, and the conqueror's territory
     * is drawn over it. That is what taking a country means on a map, and until this existed a
     * country you had annexed went on sitting there as an independent neighbour you could invade
     * again, which made twenty-eight provinces completely invisible.
     *
     * Keyed by the conquered country's id; the value is the id of whoever holds it.
     */
    var conquered: Map<String, String> = emptyMap()

    /** Countries that are still burning from a strike or a sacking. See [WorldMap.Strike]. */
    var strikes: Map<String, WorldMap.Strike> = emptyMap()

    /**
     * Where each country that can launch one actually is, in world coordinates.
     *
     * Needed because the country a warhead comes FROM may be nowhere near the screen — it is the
     * target that is being looked at — and a trail has to start somewhere real or the direction it
     * came from is a lie.
     */
    var launchSites: Map<String, Pair<Double, Double>> = emptyMap()

    /** Where each conqueror's own country sits, so its provinces can be tied back to it. */
    var capitals: Map<String, Pair<Double, Double>> = emptyMap()
        set(value) {
            field = value
            invalidate()
        }

    var caption: String = ""
        set(value) {
            field = value
            invalidate()
        }

    /** Screen pixels per world unit. Unbounded downward; there is always more world. */
    private var scale = 9.5f * density

    /** The world coordinate at the centre of the screen. */
    private var centreX = WorldChunks.CHUNK / 2
    private var centreY = WorldChunks.CHUNK / 2

    /** What was drawn last frame, for hit testing without regenerating. */
    private var lastDrawn: List<WorldMap.Country> = emptyList()

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                // The world point under the fingers stays under the fingers.
                val before = worldAt(detector.focusX, detector.focusY)
                // No floor worth speaking of: zooming out is how the player finds new countries,
                // so it is limited only by the point at which a country is a single pixel.
                scale = (scale * detector.scaleFactor).coerceIn(0.05f * density, 60f * density)
                val after = worldAt(detector.focusX, detector.focusY)
                centreX += before.first - after.first
                centreY += before.second - after.second
                invalidate()
                return true
            }
        },
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                centreX += dx / scale
                centreY += dy / scale
                invalidate()
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                countryAt(e.x, e.y)?.let { onOpenCountry(it) }
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                countryAt(e.x, e.y)?.let { onZoomInto(it) }
                return true
            }
        },
    )

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        return true
    }

    // -- Coordinates ----------------------------------------------------------

    private fun screenX(world: Double): Float = (width / 2f + (world - centreX) * scale).toFloat()
    private fun screenY(world: Double): Float = (height / 2f + (world - centreY) * scale).toFloat()

    private fun worldAt(sx: Float, sy: Float): Pair<Double, Double> =
        (centreX + (sx - width / 2f) / scale) to (centreY + (sy - height / 2f) / scale)

    /** The rectangle of world currently on screen. */
    private fun viewport(): WorldChunks.View {
        val halfW = width / 2.0 / scale
        val halfH = height / 2.0 / scale
        return WorldChunks.View(centreX - halfW, centreY - halfH, centreX + halfW, centreY + halfH)
    }

    /** Puts the player's own country back in the middle. */
    fun goHome() {
        val home = fixedCountries.firstOrNull { it.owner == WorldMap.Owner.PLAYER }
        centreX = home?.centreX ?: WorldChunks.playerChunkCentre().first
        centreY = home?.centreY ?: WorldChunks.playerChunkCentre().second
        scale = 9.5f * density
        invalidate()
    }

    private fun countryAt(sx: Float, sy: Float): WorldMap.Country? {
        val (wx, wy) = worldAt(sx, sy)
        // A generous radius in SCREEN terms, so a country stays tappable when zoomed out and does
        // not swallow the whole screen when zoomed in.
        val reach = (34f * density / scale).toDouble().coerceAtLeast(3.0)
        return lastDrawn
            .map { it to kotlin.math.hypot(it.centreX - wx, it.centreY - wy) }
            .filter { it.second < reach }
            .minByOrNull { it.second }
            ?.first
    }

    /** How many countries are on screen, for the caption. */
    fun visibleCount(): Int = lastDrawn.size

    // -- Drawing --------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        PencilStyle.drawPaper(canvas, width, height, dark, density)
        if (width == 0 || height == 0) return

        val ink = PencilStyle.pencil(2f * density, PencilStyle.graphite(dark))
        val view = viewport()

        // Generated on the way past and not kept. See the class comment.
        val generated = WorldChunks.countriesIn(worldSeed, playerLevel, view)
        val fixed = fixedCountries.filter {
            it.centreX >= view.left - WorldChunks.COUNTRY_RADIUS &&
                it.centreX <= view.right + WorldChunks.COUNTRY_RADIUS &&
                it.centreY >= view.top - WorldChunks.COUNTRY_RADIUS &&
                it.centreY <= view.bottom + WorldChunks.COUNTRY_RADIUS
        }
        val countries = (fixed + generated.filter { g -> fixed.none { it.id == g.id } })
            .map { it.copy(relation = relations[it.id] ?: it.relation) }
        lastDrawn = countries

        drawSea(canvas, view)

        // Below this the blobs are smaller than a fingertip and the map is a constellation; drawing
        // outlines and names at that size is illegible and costs the most.
        val detailed = scale > 2.2f * density
        val named = scale > 5.5f * density

        countries.forEach { country ->
            val heldBy = conquered[country.id]
            val colour = when {
                // A province is drawn in its owner's ink, not its own. The blue of the player's
                // empire spreading across the map is the point of taking countries.
                heldBy == WorldMap.PLAYER_ID -> PencilStyle.BLUE_PENCIL
                heldBy != null -> PencilStyle.graphite(dark)
                country.owner == WorldMap.Owner.PLAYER -> PencilStyle.BLUE_PENCIL
                country.relation == WorldMap.Relation.ALLIED -> PencilStyle.GREEN_PENCIL
                country.relation == WorldMap.Relation.HOSTILE -> PencilStyle.RED_PENCIL
                country.owner == WorldMap.Owner.PEER -> PencilStyle.graphite(dark)
                else -> PencilStyle.graphiteLight(dark)
            }
            val cx = screenX(country.centreX)
            val cy = screenY(country.centreY)

            if (!detailed) {
                // A dot, which is what a country is when a thousand of them are on screen.
                canvas.drawCircle(cx, cy, (2.2f * density).coerceAtMost(scale * 3f), PencilStyle.shading(colour, alpha = 190))
                return@forEach
            }

            val points = country.outline.map { screenX(it.first.toDouble()) to screenY(it.second.toDouble()) }
            if (points.isEmpty()) return@forEach

            val outlinePaint = PencilStyle.pencil(2.2f * density, colour)
            if (country.owner == WorldMap.Owner.PEER && heldBy == null) {
                PencilStyle.polygon(canvas, points, outlinePaint, country.id.hashCode().toLong() + 7, 2.6f)
            }

            if (heldBy != null) {
                // The old border is rubbed out and redrawn faintly, the way a border that no longer
                // means anything is left on a marked-up map, and the province takes the conqueror's
                // wash. A line runs back to the capital so the empire reads as one territory rather
                // than as a scatter of separately coloured blobs.
                capitals[heldBy]?.let { (capX, capY) ->
                    PencilStyle.line(
                        canvas,
                        screenX(capX), screenY(capY), cx, cy,
                        PencilStyle.pencil(1.4f * density, colour, alpha = 90),
                        seed = country.id.hashCode().toLong() + 31,
                        amount = 3.2f,
                    )
                }
                PencilStyle.fillPolygon(canvas, points, PencilStyle.shading(colour, alpha = 34))
                PencilStyle.polygon(
                    canvas, points,
                    PencilStyle.pencil(1.1f * density, PencilStyle.graphiteLight(dark), alpha = 70),
                    country.id.hashCode().toLong() + 5, 2.0f,
                )
                PencilStyle.polygon(canvas, points, outlinePaint, country.id.hashCode().toLong(), 2.6f)
            } else {
                PencilStyle.fillPolygon(canvas, points, PencilStyle.shading(colour, alpha = 26))
                PencilStyle.polygon(canvas, points, outlinePaint, country.id.hashCode().toLong(), 2.2f)
            }

            if (named && heldBy != null) {
                // Named as a possession, not as a state: no level, because a province does not have
                // one any more.
                val namePaint = PencilStyle.textPaint(10.5f * density, dark, colour).apply {
                    textAlign = Paint.Align.CENTER
                }
                PencilStyle.writtenText(canvas, country.name, cx, cy - 2f * density, namePaint, country.id.hashCode().toLong())
                val ownerPaint = PencilStyle.textPaint(9f * density, dark, PencilStyle.graphiteLight(dark)).apply {
                    textAlign = Paint.Align.CENTER
                }
                canvas.drawText(
                    if (heldBy == WorldMap.PLAYER_ID) "yours" else "annexed",
                    cx, cy + 11f * density, ownerPaint,
                )
            } else if (named) {
                val namePaint = PencilStyle.textPaint(11.5f * density, dark, colour).apply {
                    textAlign = Paint.Align.CENTER
                }
                PencilStyle.writtenText(canvas, country.name, cx, cy - 2f * density, namePaint, country.id.hashCode().toLong())
                val levelPaint = PencilStyle.textPaint(9.5f * density, dark, PencilStyle.graphiteLight(dark)).apply {
                    textAlign = Paint.Align.CENTER
                }
                canvas.drawText("lvl ${country.level}", cx, cy + 11f * density, levelPaint)
            }

            // Fire, for a country that has been hit and has not finished rebuilding. Drawn as
            // scribbled flame and a scorch wash rather than as a symbol, because the rest of the
            // map is a drawing and a hazard icon would look pasted on.
            strikes[country.id]?.let { strike ->
                val scorch = strike.scorch(System.currentTimeMillis()).toFloat()
                if (scorch > 0f) {
                    PencilStyle.fillPolygon(
                        canvas, points,
                        PencilStyle.shading(PencilStyle.RED_PENCIL, alpha = (70 * scorch).toInt()),
                    )
                    val flame = PencilStyle.pencil(1.8f * density, PencilStyle.RED_PENCIL, alpha = 220)
                    val spread = scale * 1.6f
                    val count = (3 + scorch * 5).toInt()
                    repeat(count) { i ->
                        val fx = cx + (i - count / 2f) * spread * 0.55f
                        val fy = cy - spread * 0.2f
                        val h = spread * (0.5f + 0.5f * ((i * 7 % 5) / 5f))
                        PencilStyle.line(canvas, fx, fy, fx + spread * 0.18f, fy - h, flame, 3100L + i, 2.4f)
                        PencilStyle.line(canvas, fx + spread * 0.18f, fy - h, fx + spread * 0.34f, fy, flame, 3200L + i, 2.4f)
                    }
                }
            }

            MinigameMesh.battleFor(country.id)?.let {
                val red = PencilStyle.pencil(2.2f * density, PencilStyle.RED_PENCIL)
                val s = scale * 1.4f
                PencilStyle.line(canvas, cx - s, cy - s - 14f * density, cx + s, cy + s - 14f * density, red, 900, 1f)
                PencilStyle.line(canvas, cx + s, cy - s - 14f * density, cx - s, cy + s - 14f * density, red, 901, 1f)
            }
        }

        if (caption.isNotBlank()) {
            val captionPaint = PencilStyle.textPaint(12f * density, dark, PencilStyle.graphiteLight(dark))
            PencilStyle.writtenText(
                canvas, caption, 56f * density, height - 18f * density, captionPaint, seed = 12,
            )
        }

        // Warheads in the air, over everything else: this is the one thing on the map that is
        // happening rather than merely being the case.
        drawFlights(canvas)

        drawCompass(canvas, ink)
    }

    /**
     * Warheads crossing the world.
     *
     * Drawn as the thing itself plus the trail behind it, which is what makes the direction legible
     * at a glance — a dot moving between two points reads as a dot until you have watched it for a
     * second. The trail is dashed and fades backwards, the way a contrail is drawn.
     *
     * Everything is in world coordinates and projected, so a launch crosses the map correctly at
     * any zoom, including when one end or both ends are off screen.
     */
    /**
     * Where a country is, whether or not it is on screen.
     *
     * Three sources in order of authority: the fixed countries (the player and any peers, whose
     * positions are not derivable), whatever is currently drawn, and finally the generator — an AI
     * country's id encodes its chunk, so the world can be asked where it is without visiting it.
     */
    private fun locate(id: String?): Pair<Double, Double>? {
        if (id == null) return null
        launchSites[id]?.let { return it }
        fixedCountries.firstOrNull { it.id == id }?.let { return it.centreX to it.centreY }
        lastDrawn.firstOrNull { it.id == id }?.let { return it.centreX to it.centreY }
        return WorldChunks.centreOf(worldSeed, playerLevel, id)
    }

    private fun drawFlights(canvas: Canvas) {
        if (strikes.isEmpty()) return
        val now = System.currentTimeMillis()
        var anyInFlight = false

        strikes.forEach { (targetId, strike) ->
            val progress = strike.flight(now) ?: return@forEach
            val from = locate(strike.fromId) ?: return@forEach
            val to = locate(targetId) ?: return@forEach
            anyInFlight = true

            val colour = if (strike.kind == WeaponCatalog.Wmd.ANTIMATTER) {
                PencilStyle.BLUE_PENCIL
            } else {
                PencilStyle.RED_PENCIL
            }

            val x0 = screenX(from.first)
            val y0 = screenY(from.second)
            val x1 = screenX(to.first)
            val y1 = screenY(to.second)

            // A ballistic arc rather than a straight line: it is how this is always drawn, and it
            // separates the outbound path from the border lines it would otherwise sit on top of.
            fun at(t: Double): Pair<Float, Float> {
                val x = x0 + (x1 - x0) * t.toFloat()
                val y = y0 + (y1 - y0) * t.toFloat()
                val lift = kotlin.math.sin(t * Math.PI).toFloat() *
                    kotlin.math.hypot(x1 - x0, y1 - y0) * 0.22f
                return x to (y - lift)
            }

            // The trail: short dashes behind the warhead, fading with distance travelled.
            val steps = 14
            for (i in 1..steps) {
                val t1 = progress * (i - 1) / steps
                val t2 = progress * i / steps
                if (i % 2 == 0) continue
                val (ax, ay) = at(t1)
                val (bx, by) = at(t2)
                val fade = (40 + 140 * (i.toFloat() / steps)).toInt().coerceIn(0, 200)
                canvas.drawLine(ax, ay, bx, by, PencilStyle.pencil(1.5f * density, colour, alpha = fade))
            }

            val (hx, hy) = at(progress)
            val (px, py) = at((progress - 0.02).coerceAtLeast(0.0))
            val size = (7f * density).coerceAtLeast(scale * 0.8f)
            val angle = kotlin.math.atan2((hy - py).toDouble(), (hx - px).toDouble())
            val nose = PencilStyle.pencil(2.2f * density, colour)
            val tailX = hx - (kotlin.math.cos(angle) * size).toFloat()
            val tailY = hy - (kotlin.math.sin(angle) * size).toFloat()
            PencilStyle.line(canvas, tailX, tailY, hx, hy, nose, 4400L, 1.2f)
            // Fins, so it is a missile and not a tick mark.
            val fin = size * 0.45f
            canvas.drawLine(
                tailX, tailY,
                tailX - (kotlin.math.cos(angle - 0.9) * fin).toFloat(),
                tailY - (kotlin.math.sin(angle - 0.9) * fin).toFloat(),
                nose,
            )
            canvas.drawLine(
                tailX, tailY,
                tailX - (kotlin.math.cos(angle + 0.9) * fin).toFloat(),
                tailY - (kotlin.math.sin(angle + 0.9) * fin).toFloat(),
                nose,
            )

            // The last moment: the target is ringed, so you know where to look before it lands.
            if (progress > 0.7) {
                val ring = PencilStyle.pencil(2f * density, colour, alpha = (255 * (progress - 0.7) / 0.3).toInt().coerceIn(0, 255))
                val r = (10f * density).coerceAtLeast(scale * 1.6f) * (1.6f - progress.toFloat() * 0.6f)
                canvas.drawCircle(x1, y1, r, ring)
            }
        }

        // Keep animating only while something is actually up there.
        if (anyInFlight) postInvalidateOnAnimation()
    }

    /**
     * Wave marks, anchored to the world rather than to the screen.
     *
     * Seeded by the cell they sit in, so they stay put as the map moves. Waves that were seeded by
     * screen position would swim about under the player's finger, which reads as the whole ocean
     * sliding the wrong way.
     */
    private fun drawSea(canvas: Canvas, view: WorldChunks.View) {
        if (scale < 3.2f * density) return
        val wave = PencilStyle.pencil(1.2f * density, PencilStyle.rule(dark), alpha = 150)
        val step = 11.0
        var wy = kotlin.math.floor(view.top / step) * step
        while (wy < view.bottom) {
            var wx = kotlin.math.floor(view.left / step) * step
            while (wx < view.right) {
                val rng = Rng(Rng.seedOf("wave", wx.toInt(), wy.toInt()))
                if (rng.chance(0.42)) {
                    val x = screenX(wx + rng.nextDouble() * step)
                    val y = screenY(wy + rng.nextDouble() * step)
                    val r = scale * 0.5f
                    canvas.drawArc(RectF(x - r, y - r * 0.4f, x + r, y + r * 0.8f), 200f, 140f, false, wave)
                }
                wx += step
            }
            wy += step
        }
    }

    private fun drawCompass(canvas: Canvas, ink: Paint) {
        val cx = width - 46f * density
        val cy = 56f * density
        val r = 18f * density
        canvas.drawCircle(cx, cy, r, PencilStyle.pencil(1.6f * density, PencilStyle.graphiteLight(dark), alpha = 170))
        PencilStyle.line(canvas, cx, cy + r * 0.8f, cx, cy - r * 0.9f, ink, 55, 0.8f)
        val n = PencilStyle.textPaint(11f * density, dark).apply { textAlign = Paint.Align.CENTER }
        canvas.drawText("N", cx, cy - r - 4f * density, n)
    }
}
