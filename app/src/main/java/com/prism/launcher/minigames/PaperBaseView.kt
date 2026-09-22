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
 * The player's base: a pencil drawing on ruled paper that can be panned, zoomed and built on.
 *
 * ## The grid is the ruling
 *
 * Buildings snap to a 32x32 cell grid, and a cell is sized so the grid lines up with the paper's
 * ruling. That is the thing that makes it read as a drawing in a book rather than as a game board
 * on a paper background — the buildings sit ON the lines the way a child's drawing does.
 *
 * ## What this view does not do
 *
 * It does not own the game. [PaperWarView] holds the base, the world and the tick; this draws what
 * it is given and reports taps. That split exists because the same renderer draws four different
 * things — your base, an AI base you are about to raid, a battle in progress, and a battle you are
 * spectating — and only one of those is editable.
 */
@SuppressLint("ViewConstructor")
class PaperBaseView(
    context: Context,
    private val onTapBuilding: (PlacedBuilding) -> Unit,
    private val onTapEmpty: (x: Int, y: Int) -> Unit,
) : View(context) {

    private val density = resources.displayMetrics.density
    private val dark: Boolean get() = PaperUi.isDark(context)

    var base: PaperBase = PaperBase("", 0)
        set(value) {
            field = value
            invalidate()
        }

    /** When non-null, the view is showing a battle rather than a base. */
    var battleFrame: Battle.Frame? = null
        set(value) {
            field = value
            invalidate()
        }

    /** The constant half of a battle: who is in it, and the order [Battle.Frame] arrays are in. */
    var battleCast: Battle.Cast? = null

    /** A building type following the finger, waiting to be put down. */
    var placing: BuildingCatalog.BuildingType? = null
        set(value) {
            field = value
            invalidate()
        }

    var placingX = Battle.FIELD / 2
    var placingY = Battle.FIELD / 2

    private var offsetX = 0f
    private var offsetY = 0f
    private var zoom = 1f
    private var fitted = false

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val before = zoom
                zoom = (zoom * detector.scaleFactor).coerceIn(0.45f, 3.5f)
                // Zoom about the pinch, not about the corner, or the base runs away from the fingers.
                val k = zoom / before
                offsetX = detector.focusX - (detector.focusX - offsetX) * k
                offsetY = detector.focusY - (detector.focusY - offsetY) * k
                invalidate()
                return true
            }
        },
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                offsetX -= dx
                offsetY -= dy
                invalidate()
                return true
            }

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val cell = cellSize()
                val gx = ((e.x - offsetX) / cell).toInt()
                val gy = ((e.y - offsetY) / cell).toInt()
                if (gx !in 0 until base.plotSize || gy !in 0 until base.plotSize) return true

                // While something is being placed a tap AIMS it and does not put it down. That is
                // the whole reason the Place button and the double tap exist: on a phone the
                // finger covers the square it is choosing, so a single tap that committed would
                // put buildings down where the player could not see, and there would be no way to
                // nudge one over by a cell without demolishing it and starting again.
                if (placing != null) {
                    moveGhost(gx, gy)
                    return true
                }

                val hit = base.buildings.firstOrNull { b ->
                    val size = b.footprint
                    gx >= b.x && gx < b.x + size && gy >= b.y && gy < b.y + size
                }
                if (hit != null) onTapBuilding(hit) else onTapEmpty(gx, gy)
                return true
            }

            /**
             * Double tap: aim there, then put it down.
             *
             * Fires on the second tap's DOWN, after the first tap's UP has already moved the ghost,
             * so "double tap over there" reads as one gesture that means "build it there" — which
             * is what a player expects and what the single tap alone was silently failing to do.
             */
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (placing == null) return false
                val cell = cellSize()
                moveGhost(
                    ((e.x - offsetX) / cell).toInt(),
                    ((e.y - offsetY) / cell).toInt(),
                )
                confirmPlacement()
                return true
            }
        },
    )

    /** Moves the ghost, clamped so a large building cannot be aimed off the edge of the paper. */
    private fun moveGhost(gx: Int, gy: Int) {
        val footprint = placing?.footprint ?: 1
        placingX = gx.coerceIn(0, base.plotSize - footprint)
        placingY = gy.coerceIn(0, base.plotSize - footprint)
        invalidate()
    }

    /** Puts the thing being placed down where the ghost is. The Place button calls this too. */
    fun confirmPlacement() {
        if (placing == null) return
        onTapEmpty(placingX, placingY)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        return true
    }

    private fun cellSize(): Float = 22f * density * zoom

    /** Centres the drawing the first time the view is measured. */
    private fun fitIfNeeded() {
        if (fitted || width == 0) return
        fitted = true
        // The country's OWN field, which is bigger than the field every country starts on once a
        // boundary has been expanded -- fitting to the fixed constant here would zoom in on only a
        // corner of an expanded base and call it centred.
        val field = base.plotSize
        val wanted = minOf(width, height).toFloat() / (field * 22f * density)
        zoom = wanted.coerceIn(0.45f, 1.6f)
        val cell = cellSize()
        offsetX = (width - field * cell) / 2f
        offsetY = (height - field * cell) / 2f
    }

    fun recentre() {
        fitted = false
        invalidate()
    }

    // -- Drawing --------------------------------------------------------------

    /**
     * ## Two layers
     *
     * The scenery -- paper, plot, grid, roads and buildings -- is rendered into a bitmap and
     * blitted. None of it moves, and a level-300 base is three hundred buildings at roughly a dozen
     * wobbled Paths each, so redrawing it was three and a half thousand Path allocations per frame
     * to produce a picture identical to the previous one. Doing that ten times a second for the
     * length of a battle is what made a big base slow to fight over; the army itself had already
     * been reduced to batched line segments and was no longer the expensive part.
     *
     * Everything that actually moves -- fires, the placement ghost, civilians and the battle -- is
     * drawn straight onto the canvas on top of it, every frame, as before.
     */
    override fun onDraw(canvas: Canvas) {
        fitIfNeeded()
        if (width <= 0 || height <= 0) return

        val cell = cellSize()
        val ink = PencilStyle.pencil(1.9f * density, PencilStyle.graphite(dark))
        val frame = battleFrame
        val cast = battleCast

        val key = sceneryKey(frame)
        if (key != sceneryVersion || layer?.width != width || layer?.height != height) {
            renderScenery(cell, ink)
            sceneryVersion = key
        }
        layer?.let { canvas.drawBitmap(it, 0f, 0f, null) }

        canvas.save()
        canvas.clipRect(0, 0, width, height)

        // Fire and smoke over the wreckage, drawn after the buildings so the plumes rise in front
        // of what is still standing rather than behind it.
        if (scorch > 0f) drawBurning(canvas, cell)

        // The thing being placed, drawn as a dashed ghost.
        placing?.let { type ->
            val r = RectF(
                offsetX + placingX * cell,
                offsetY + placingY * cell,
                offsetX + (placingX + type.footprint) * cell,
                offsetY + (placingY + type.footprint) * cell,
            )
            val ok = !overlapsAnything(placingX, placingY, type.footprint)
            val colour = if (ok) PencilStyle.GREEN_PENCIL else PencilStyle.RED_PENCIL
            val ghost = PencilStyle.pencil(2.4f * density, colour, alpha = 220)
            PencilStyle.rect(canvas, r, ghost, seed = 777, amount = 1.6f)
            PencilStyle.hatch(canvas, r, PencilStyle.pencil(1.2f * density, colour, alpha = 70), 778, cell / 3f)
        }

        // A battle in progress: the attackers, and the lines of fire.
        // The people, when nothing is exploding. A base with civilians walking about reads as
        // inhabited in a way that a base of static boxes never does.
        // ── Animation rate ───────────────────────────────────────────────
        //
        // Full framerate. This used to be capped at twelve a second, and the comment that capped it
        // gave the reason: a repaint redrew every building, and doing that sixty times a second to
        // move a handful of little people was thousands of Paths a second for nothing. The scenery
        // is a cached bitmap now, so a repaint is a blit plus the figures themselves — and twelve
        // frames a second is exactly what a walk cycle cannot afford, because a stride is about a
        // second long and twelve samples of it reads as a stutter rather than as walking.
        var animating = scorch > 0f
        if (frame == null && drawCivilians(canvas, cell, ink)) animating = true
        if (animating) postInvalidateOnAnimation()

        if (frame != null && cast != null) drawBattle(canvas, frame, cast, cell, ink)

        canvas.restore()
    }

    // -- The scenery layer ----------------------------------------------------

    private var layer: android.graphics.Bitmap? = null
    private var layerCanvas: Canvas? = null
    private var sceneryVersion: Long = Long.MIN_VALUE

    /**
     * Everything that would change the scenery, folded into one number.
     *
     * Includes a coarse damage bucket per building, so a building visibly coming apart still
     * redraws -- a few dozen times over a battle rather than a few thousand. Four buckets is enough
     * for the three states the drawing actually has (whole, damaged, rubble) plus a margin.
     */
    private fun sceneryKey(frame: Battle.Frame?): Long {
        var h = 1125899906842597L
        h = h * 31 + offsetX.toRawBits()
        h = h * 31 + offsetY.toRawBits()
        h = h * 31 + zoom.toRawBits()
        h = h * 31 + width
        h = h * 31 + height
        h = h * 31 + (if (dark) 1 else 0)
        // The plot's own size, so a boundary expansion redraws the border and grid even though the
        // set of buildings has not changed.
        h = h * 31 + base.plotSize
        h = h * 31 + drawOrderStamp()
        h = h * 31 + (if (frame == null) 0 else 1)

        val order = drawOrder()
        if (frame != null) {
            val cast = battleCast
            val index = cast?.let { buildingIndex(it) }
            order.forEach { b ->
                val hp = index?.get(b.id)?.let { frame.buildingHp.getOrNull(it) } ?: b.hitPoints
                val max = b.maxHitPoints.coerceAtLeast(1)
                h = h * 31 + (if (hp <= 0) 0L else 1L + (hp.toLong() * 3 / max))
            }
        } else {
            order.forEach { b ->
                val max = b.maxHitPoints.coerceAtLeast(1)
                h = h * 31 + (if (b.hitPoints <= 0) 0L else 1L + (b.hitPoints.toLong() * 3 / max))
                // A building site changes as it goes up, so it cannot be cached as one picture.
                if (!b.complete) h = h * 31 + (b.buildProgress(System.currentTimeMillis()) * 12).toLong()
            }
        }
        return h
    }

    private fun renderScenery(cell: Float, ink: Paint) {
        val existing = layer
        val target = if (existing != null && existing.width == width && existing.height == height) {
            existing.eraseColor(0)
            existing
        } else {
            existing?.recycle()
            android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
                .also {
                    layer = it
                    layerCanvas = Canvas(it)
                }
        }
        val canvas = layerCanvas ?: Canvas(target).also { layerCanvas = it }
        if (canvas.width != target.width) layerCanvas = Canvas(target)

        val faint = PencilStyle.pencil(1.1f * density, PencilStyle.graphiteLight(dark), alpha = 80)
        val frame = battleFrame
        val cast = battleCast

        PencilStyle.drawPaper(canvas, width, height, dark, density)
        canvas.save()
        canvas.clipRect(0, 0, width, height)
        // The plot: a wobbly border round the buildable area, the way you box off a drawing.
        val field = base.plotSize
        val plot = RectF(offsetX, offsetY, offsetX + field * cell, offsetY + field * cell)
        PencilStyle.rect(canvas, plot, ink, seed = 1, amount = 2f)

        // A light grid inside it, so a player can see where a building will land.
        if (zoom > 0.7f) {
            for (i in 1 until field) {
                val x = offsetX + i * cell
                val y = offsetY + i * cell
                if (x > -cell && x < width + cell) canvas.drawLine(x, plot.top, x, plot.bottom, faint)
                if (y > -cell && y < height + cell) canvas.drawLine(plot.left, y, plot.right, y, faint)
            }
        }

        // Buildings are addressed by position in the cast's list during a battle, and the lookup is
        // cached against the cast rather than rebuilt per frame: a level-300 base is three hundred
        // entries, and building that map ten times a second for the length of a battle is a map a
        // second thrown away for an answer that cannot change.
        val indexOf = cast?.let { buildingIndex(it) }

        val now = System.currentTimeMillis()

        // Roads first, so everything else is drawn on top of them rather than under.
        drawRoads(canvas, cell, ink)

        // Hoisted out of the loop: one Paint for the lot instead of one per building per frame.
        val fillPaint = PencilStyle.pencil(1f * density, PencilStyle.graphiteLight(dark), alpha = 70)
        ruins.clear()
        // Sorted back to front so a building overlaps the one behind it. Cached, because the order
        // only changes when something is built or destroyed, not sixty times a second.
        drawOrder().forEach { b ->
            val type = b.type ?: return@forEach
            val r = RectF(
                offsetX + b.x * cell,
                offsetY + b.y * cell,
                offsetX + (b.x + type.footprint) * cell,
                offsetY + (b.y + type.footprint) * cell,
            )
            if (r.right < 0 || r.left > width || r.bottom < 0 || r.top > height) return@forEach

            val battleHp = indexOf?.get(b.id)?.let { frame?.buildingHp?.getOrNull(it) }
            // Rubble, from either cause: knocked down in the battle being watched, or knocked down
            // before it started. The second case is what makes a bombed country look bombed —
            // WorldMap.scorchedBase leaves its casualties on the page at zero hit points rather
            // than deleting them, precisely so there is something here to draw.
            val flattened = (battleHp ?: b.hitPoints) <= 0
            if (flattened) {
                val seed = b.id.hashCode().toLong()
                PencilStyle.smudge(canvas, r.centerX(), r.centerY(), r.width() * 0.6f, dark, seed)
                drawRubble(canvas, r, seed)
                ruins.add(r.centerX() to r.bottom)
                return@forEach
            }

            val current = battleHp ?: b.hitPoints
            val damaged = current < b.maxHitPoints
            val seed = b.id.hashCode().toLong()

            inset.set(r.left + cell * 0.12f, r.top + cell * 0.12f, r.right - cell * 0.12f, r.bottom - cell * 0.1f)
            PencilStyle.building(
                canvas = canvas,
                bounds = inset,
                category = type.category,
                modernity = PencilStyle.modernityOf(type.unlockLevel),
                paint = ink,
                fillPaint = fillPaint,
                seed = seed,
                damaged = damaged,
                underConstruction = b.buildProgress(now).toFloat(),
            )

            // A health bar, but drawn: a line with the lost part rubbed out.
            if (damaged && b.complete) {
                val frac = (current.toFloat() / b.maxHitPoints).coerceIn(0f, 1f)
                val barY = r.top + cell * 0.06f
                PencilStyle.line(canvas, r.left, barY, r.left + r.width() * frac, barY,
                    PencilStyle.pencil(2.6f * density, PencilStyle.RED_PENCIL, alpha = 210), seed + 99, 0.5f)
            }

            // A stick figure or two outside the military buildings, so the base has people in it.
            // Not during a battle: the residents are a peacetime flourish, they are each a full
            // pencil stick figure, and a battle already has as many figures on the paper as the
            // frame budget allows.
            if (zoom > 0.85f && b.complete && frame == null) {
                drawResidents(canvas, r, type, b.id.hashCode().toLong(), cell, ink)
            }
        }

        canvas.restore()
    }

    /** Cheap stamp of which buildings exist and whether they are finished. See [drawOrder]. */
    private fun drawOrderStamp(): Long {
        drawOrder()
        return drawOrderVersion.toLong()
    }



    /**
     * Roads, as lines between the buildings they join.
     *
     * Drawn as a double pencil line with dashes between, which is how a road is drawn on paper and
     * — more usefully — is legible at any zoom, unlike a one-cell box.
     */
    private fun drawRoads(canvas: Canvas, cell: Float, ink: Paint) {
        val roads = base.buildings.filter { it.isRoad }
        if (roads.isEmpty()) return
        val byId = base.buildings.associateBy { it.id }
        val edge = PencilStyle.pencil(1.6f * density, PencilStyle.graphiteLight(dark), alpha = 190)
        val dash = Paint(edge).apply {
            alpha = 120
            pathEffect = android.graphics.DashPathEffect(floatArrayOf(cell * 0.18f, cell * 0.16f), 0f)
        }

        roads.forEach { road ->
            val from = byId[road.connectsFrom] ?: return@forEach
            val to = byId[road.connectsTo] ?: return@forEach
            val x1 = offsetX + from.centreX().toFloat() * cell
            val y1 = offsetY + from.centreY().toFloat() * cell
            val x2 = offsetX + to.centreX().toFloat() * cell
            val y2 = offsetY + to.centreY().toFloat() * cell

            val dx = x2 - x1
            val dy = y2 - y1
            val len = kotlin.math.hypot(dx, dy)
            if (len < 1f) return@forEach
            // The two kerbs, offset either side of the centre line.
            val nx = -dy / len * cell * 0.16f
            val ny = dx / len * cell * 0.16f
            val seed = road.id.hashCode().toLong()
            val alpha = if (road.complete) 1f else 0.45f
            val kerb = Paint(edge).apply { this.alpha = (edge.alpha * alpha).toInt() }
            PencilStyle.line(canvas, x1 + nx, y1 + ny, x2 + nx, y2 + ny, kerb, seed, 1.1f)
            PencilStyle.line(canvas, x1 - nx, y1 - ny, x2 - nx, y2 - ny, kerb, seed + 1, 1.1f)
            canvas.drawLine(x1, y1, x2, y2, dash)
        }
    }

    /**
     * Civilians, walking between the buildings they live and work in.
     *
     * Each one is a stick figure whose whole life is a loop: a home, a destination, and a walk
     * between them that takes a minute. Both ends and the timing come from the civilian's index, so
     * they are stable across frames and cost nothing to store — there is no list of people, only a
     * count, and the count is what the economy already tracks.
     *
     * Capped at what is legible. A level-300 country has thousands of civilians and drawing them
     * all would be a grey smear; forty is enough to read as a populated place.
     */
    private fun drawCivilians(canvas: Canvas, cell: Float, ink: Paint): Boolean {
        // 0.35, not 0.7. The base auto-fits at about 0.58 on a phone, so a gate at 0.7 meant the
        // civilians were never drawn at the zoom the game actually opens at — the population was
        // real, growing and completely invisible.
        if (zoom < 0.35f) return false

        // The parentheses matter: `it.type?.residents ?: 0 > 0` parses as `residents ?: (0 > 0)`,
        // because elvis binds looser than comparison. That is not the question being asked.
        val homes = base.standing.filter { (it.type?.residents ?: 0) > 0 }
        val places = base.standing.filterNot { it.isRoad }
        if (homes.isEmpty() || places.size < 2) return false

        // Families walk together, so a country of twelve people is a handful of little groups
        // rather than twelve separate dots. Capped at what stays legible.
        val shown = base.civilians.coerceAtMost(60)
        if (shown <= 0) return false
        val now = System.currentTimeMillis()
        val paint = Paint(ink).apply { strokeWidth = ink.strokeWidth * 0.62f; alpha = 165 }

        // Households, so the drawing shows families rather than a crowd of strangers. Everyone in
        // a household shares a home, a destination and a schedule, and walks a pace apart.
        val size = base.familySize().coerceAtLeast(1.0)
        for (i in 0 until shown) {
            val household = (i / size).toInt()
            val withinFamily = i - (household * size).toInt()
            val rng = Rng(Rng.seedOf("civ", household))
            val home = homes[rng.nextInt(homes.size)]
            val work = places[rng.nextInt(places.size)]
            if (work.id == home.id) continue

            // A full round trip a minute, offset per person so they are not a marching column.
            val period = 60_000L
            val phase = ((now + rng.nextInt(60_000)) % period).toDouble() / period
            // There and back: 0..0.5 out, 0.5..1 home.
            val t = if (phase < 0.5) phase * 2 else (1 - phase) * 2

            // A pace behind the head of the household, and a little to one side, so a family reads
            // as a family walking rather than as one figure drawn several times.
            val trail = withinFamily * 0.28
            val spread = ((withinFamily % 2) * 2 - 1) * 0.22 * withinFamily
            val tt = (t - trail * 0.04).coerceIn(0.0, 1.0)
            val wx = home.centreX() + (work.centreX() - home.centreX()) * tt + spread
            val wy = home.centreY() + (work.centreY() - home.centreY()) * tt + trail * 0.18
            val sx = offsetX + wx.toFloat() * cell
            val sy = offsetY + wy.toFloat() * cell
            if (sx < -cell || sx > width + cell || sy < -cell || sy > height + cell) continue

            // Inside a building for the moment they arrive, which is what "entering" looks like
            // from above: they reach the door and are gone until they come back out.
            val insideEnd = t > 0.94
            if (insideEnd) continue

            // Children are drawn smaller, which is most of what makes a group read as a family.
            val child = withinFamily >= 2
            PencilStyle.stickFigure(
                canvas,
                cx = sx,
                feetY = sy + cell * 0.22f,
                height = cell * (if (child) 0.36f else 0.52f),
                paint = paint,
                seed = 5_000L + i,
                phase = (now / 120.0).toFloat() + i,
                held = PencilStyle.HeldShape.NONE,
                facingRight = work.centreX() > home.centreX(),
            )
        }
        return true
    }

    private fun overlapsAnything(x: Int, y: Int, footprint: Int): Boolean =
        base.buildings.any { b ->
            val size = b.footprint
            x < b.x + size && x + footprint > b.x && y < b.y + size && y + footprint > b.y
        }

    fun canPlaceAt(x: Int, y: Int, footprint: Int): Boolean =
        x >= 0 && y >= 0 && x + footprint <= base.plotSize && y + footprint <= base.plotSize &&
            !overlapsAnything(x, y, footprint)

    /**
     * The people and animals that make a base look inhabited.
     *
     * Deterministic per building — seeded by its id — so they do not shuffle around between frames.
     * Farms get animals, houses get people, and everything else gets nothing, because a drawing
     * with a figure next to every box is a drawing nobody can read.
     */
    private fun drawResidents(
        canvas: Canvas,
        r: RectF,
        type: BuildingCatalog.BuildingType,
        seed: Long,
        cell: Float,
        ink: Paint,
    ) {
        val rng = Rng(seed)
        val figurePaint = Paint(ink).apply { strokeWidth = ink.strokeWidth * 0.75f; alpha = 190 }
        when (type.category) {
            BuildingCatalog.Category.RESOURCE -> {
                // Stick animals, as the design asks: nothing here is a photograph of a cow.
                repeat(1 + rng.nextInt(2)) { i ->
                    PencilStyle.stickAnimal(
                        canvas,
                        cx = r.left + cell * (0.4f + i * 0.9f),
                        feetY = r.bottom + cell * 0.42f,
                        length = cell * 0.62f,
                        paint = figurePaint,
                        seed = seed + i * 31,
                        tall = rng.nextBoolean(),
                        horns = rng.nextBoolean(),
                    )
                }
            }
            BuildingCatalog.Category.HOUSING, BuildingCatalog.Category.CIVIC,
            BuildingCatalog.Category.MILITARY,
            -> {
                repeat(1 + rng.nextInt(2)) { i ->
                    PencilStyle.stickFigure(
                        canvas,
                        cx = r.right + cell * (0.22f + i * 0.4f),
                        feetY = r.bottom + cell * 0.3f,
                        height = cell * 0.82f,
                        paint = figurePaint,
                        seed = seed + 100 + i,
                        held = if (type.category == BuildingCatalog.Category.MILITARY) {
                            PencilStyle.heldFor(base.infantryWeapon().weaponClass)
                        } else {
                            PencilStyle.HeldShape.NONE
                        },
                    )
                }
            }
            else -> Unit
        }
    }

    /**
     * How badly this country is still burning, 0 to 1. See [WorldMap.Strike].
     *
     * Drives the ruins, the fires and the smoke. Set from the strike record whenever a base is
     * shown — spying on a country, and invading one — so a place you bombed looks bombed from
     * whichever direction you come at it.
     */
    var scorch: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    // -- Caches for things redrawn every frame -------------------------------

    /** Reused so the building loop does not allocate a RectF per building per frame. */
    private val inset = RectF()

    private var drawOrderVersion: Int = -1
    private var drawOrderCache: List<PlacedBuilding> = emptyList()

    /**
     * The buildings, roads excluded, back to front.
     *
     * Recomputed when the base changes rather than per frame. Sorting three hundred buildings ten
     * times a second to get the same answer every time is the sort of cost that does not show up in
     * a profile as one big thing and adds up to a frame anyway.
     */
    private fun drawOrder(): List<PlacedBuilding> {
        // Completion is part of the key. Without it a building that finished kept its id and its
        // position, so the cache said nothing had changed and went on handing back the version of
        // the list where it was still a building site -- the finished building only appeared after
        // a restart, which is exactly what a stale cache looks like from the outside.
        val version = base.buildings.size * 31 +
            base.buildings.sumOf { it.id.hashCode() } +
            base.buildings.count { it.complete } * 7919
        if (version != drawOrderVersion) {
            drawOrderCache = base.buildings.filterNot { it.isRoad }.sortedBy { it.y }
            drawOrderVersion = version
        }
        return drawOrderCache
    }

    private var buildingIndexFor: Battle.Cast? = null
    private var buildingIndexCache: Map<String, Int> = emptyMap()

    private fun buildingIndex(cast: Battle.Cast): Map<String, Int> {
        if (buildingIndexFor !== cast) {
            buildingIndexCache = cast.buildingIds.withIndex().associate { (i, id) -> id to i }
            buildingIndexFor = cast
        }
        return buildingIndexCache
    }

    // -- Drawing a battle ----------------------------------------------------
    //
    // Reused frame to frame so a large assault allocates nothing per frame. One batch per squad
    // colour, because drawLines takes a single paint.

    private val squadStrokes = Array(SQUAD_COLOURS) { PencilStyle.StrokeBatch() }
    private val squadDots = Array(SQUAD_COLOURS) { PencilStyle.PointBatch() }
    private val shotStrokes = Array(2) { PencilStyle.StrokeBatch() }

    /**
     * Which [PencilStyle.UnitShape] each fighter in the current cast is, worked out once.
     *
     * [Battle.Cast.weaponOf] is a catalogue lookup by id through a map of eleven hundred weapons,
     * and calling it per fighter per frame is five thousand map lookups sixty times a second for an
     * answer that cannot change: a fighter carries the same weapon all battle.
     */
    private var shapeCacheFor: Battle.Cast? = null
    private var shapeCache: IntArray = IntArray(0)

    private fun shapesFor(cast: Battle.Cast): IntArray {
        if (shapeCacheFor === cast) return shapeCache
        shapeCache = IntArray(cast.size) { PencilStyle.unitShapeFor(cast.weaponOf(it)).ordinal }
        shapeCacheFor = cast
        return shapeCache
    }

    private fun squadSlot(squad: Int): Int = when (squad) {
        0, 1, 2 -> squad
        else -> 3
    }

    private fun squadColour(squad: Int): Int = when (squad) {
        0 -> PencilStyle.RED_PENCIL
        1 -> PencilStyle.BLUE_PENCIL
        2 -> PencilStyle.GREEN_PENCIL
        else -> PencilStyle.graphite(dark)
    }

    /** Where the wreckage is this frame, so the fires know what to sit on. */
    private val ruins = ArrayList<Pair<Float, Float>>()

    /**
     * What is left of a building: a broken footprint and a couple of standing walls.
     *
     * The smudge alone reads as a stain rather than as a ruin, and a ruin has to be recognisable as
     * a building that used to be there — otherwise a flattened base looks like a base somebody
     * spilled something on.
     */
    private fun drawRubble(canvas: Canvas, r: RectF, seed: Long) {
        val ink = PencilStyle.pencil(1.6f * density, PencilStyle.graphite(dark), alpha = 200)
        val rng = Rng(seed)
        val w = r.width()
        val h = r.height()

        // Two or three stubs of wall, leaning, at the edges where a wall would have been.
        repeat(2 + rng.nextInt(2)) { i ->
            val x = r.left + w * (0.15f + rng.nextDouble().toFloat() * 0.7f)
            val stub = h * (0.16f + rng.nextDouble().toFloat() * 0.26f)
            val lean = ((rng.nextDouble() - 0.5) * w * 0.18).toFloat()
            PencilStyle.line(canvas, x, r.bottom, x + lean, r.bottom - stub, ink, seed + i * 3, 1.4f)
        }
        // A broken ground line, so the footprint is still legible.
        PencilStyle.line(canvas, r.left, r.bottom, r.left + w * 0.38f, r.bottom, ink, seed + 11, 1.2f)
        PencilStyle.line(canvas, r.right - w * 0.3f, r.bottom, r.right, r.bottom, ink, seed + 12, 1.2f)
    }

    /**
     * The fires and the smoke over a country that has been hit.
     *
     * Every ruin gets a fire. On top of that the whole page gets a set of columns of smoke keyed to
     * the field rather than to any one building, because what makes a bombed city read as bombed
     * from a distance is the smoke over it, not the individual fires in it.
     */
    private fun drawBurning(canvas: Canvas, cell: Float) {
        val phase = (System.currentTimeMillis() % 100_000L) / 1000f
        val intensity = scorch

        ruins.forEachIndexed { index, (x, y) ->
            val seed = (x.toLong() * 31 + y.toLong()) * 17 + index
            PencilStyle.fire(canvas, x, y, cell * (0.55f + 0.5f * intensity), seed, phase, dark)
            // Not every ruin gets its own column, or the page is nothing but smoke.
            if (index % 2 == 0) {
                PencilStyle.smoke(canvas, x, y - cell * 0.6f, cell * 0.7f, seed + 5, phase, dark, puffs = 4)
            }
        }

        // The pall over the whole place. Anchored to the field so it does not slide about when the
        // base is panned, and scaled by how recently it was hit.
        val columns = (3 + intensity * 6).toInt()
        repeat(columns) { i ->
            val rng = Rng(Rng.seedOf("pall", i.toLong()))
            val fx = offsetX + (rng.nextDouble() * base.plotSize).toFloat() * cell
            val fy = offsetY + (rng.nextDouble() * base.plotSize).toFloat() * cell
            PencilStyle.smoke(canvas, fx, fy, cell * (1.1f + intensity), 700L + i, phase * 0.7f, dark, puffs = 6)
        }
    }

    private fun drawBattle(canvas: Canvas, frame: Battle.Frame, cast: Battle.Cast, cell: Float, ink: Paint) {
        val phase = frame.tick * 0.35f
        val shapes = shapesFor(cast)

        var alive = 0
        // The centre of the danger this frame, in field coordinates. Every fighter in a Cast is an
        // attacker -- defenders are static emplacements, not Fighters -- so this is exactly "where
        // the invaders are right now", and it is what tells a panicking household which way is out.
        var dangerX = 0.0
        var dangerY = 0.0
        for (i in 0 until cast.size) {
            if (frame.fighterHp[i] <= 0) continue
            alive++
            dangerX += frame.x(i)
            dangerY += frame.y(i)
        }
        if (alive > 0) {
            dangerX /= alive
            dangerY /= alive
        }

        // Above this the army is marked rather than drawn. See PencilStyle's crowd section: the
        // pencil treatment is about a dozen Path allocations a figure, which is fine for a raiding
        // party and fatal for an army.
        val drawInFull = alive <= DETAIL_UNITS
        // Vehicles survive the cut for longer than infantry do, because there are far fewer of them
        // and they are the units you actually look at -- a hundred tanks drawn properly among four
        // thousand marked soldiers costs almost nothing and is most of what the eye reads.
        var vehicleBudget = if (drawInFull) Int.MAX_VALUE else DETAIL_VEHICLES

        drawShots(canvas, frame, cell)

        for (index in 0 until cast.size) {
            if (frame.fighterHp[index] <= 0) continue
            val shape = PencilStyle.UnitShape.entries[shapes[index]]
            val foot = shape == PencilStyle.UnitShape.FOOT
            val fx = frame.x(index)
            val cx = offsetX + fx * cell
            val feetY = offsetY + frame.y(index) * cell + cell * 0.4f
            // Vehicles get a little more room than a soldier, which is what stops a column of
            // tanks reading as a column of very wide people.
            val size = cell * if (foot) 0.95f else 1.15f
            val facingRight = fx < base.plotSize / 2f

            val inFull = drawInFull || (!foot && vehicleBudget > 0)
            if (inFull) {
                if (!foot) vehicleBudget--
                val paint = PencilStyle.pencil(1.7f * density, squadColour(cast.squads[index]))
                if (frame.fighterHp[index] < cast.maxFighterHp / 2) paint.alpha = 140
                PencilStyle.unit(
                    canvas,
                    shape = shape,
                    cx = cx,
                    feetY = feetY,
                    size = size,
                    paint = paint,
                    seed = index.toLong(),
                    phase = phase + index,
                    facingRight = facingRight,
                    held = PencilStyle.heldFor(cast.weaponOf(index).weaponClass),
                )
            } else {
                val slot = squadSlot(cast.squads[index])
                PencilStyle.markUnit(
                    squadStrokes[slot], squadDots[slot], shape,
                    cx, feetY, size, facingRight, phase + index,
                )
            }
        }

        for (slot in 0 until SQUAD_COLOURS) {
            if (squadStrokes[slot].isEmpty && squadDots[slot].isEmpty) continue
            val colour = squadColour(if (slot == 3) -1 else slot)
            squadStrokes[slot].flush(canvas, PencilStyle.pencil(1.4f * density, colour, alpha = 225))
            squadDots[slot].flush(canvas, PencilStyle.dotPaint(colour, cell * 0.30f, alpha = 225))
        }

        drawPanic(canvas, frame, cast, cell, ink, dangerX, dangerY, alive > 0)
    }

    // -- Civilians fleeing ------------------------------------------------------

    /** One household running for it. */
    private class PanicRun(val startedAt: Long, val fleeAngle: Float, val count: Int)

    private val panicHpSeen = HashMap<String, Int>()
    private val panics = HashMap<String, PanicRun>()
    private var panicCast: Battle.Cast? = null

    /**
     * Civilians rushing out of a home the moment it is hit.
     *
     * ## Why this exists
     *
     * A base under attack drew nothing where the people were meant to be -- the peacetime civilians
     * stop being drawn the instant a battle starts (see [onDraw]'s `frame == null` gate), so a raid
     * that flattens a street of houses looked exactly like an empty street being flattened. The
     * point of a household is that something was living in it.
     *
     * ## Detecting the moment
     *
     * There is no "under attack" flag on a building; there is only its hit points. So this keeps
     * the hit points it last saw for every home in the CURRENT battle ([panicHpSeen], reset when the
     * [Battle.Cast] identity changes, i.e. a new fight has started) and treats a drop as the trigger
     * — the same frame the building takes damage is the frame its household starts running. A
     * building that reaches zero without ever being SEEN to drop a point still triggers, because the
     * comparison is against the last value seen, not against "was it ever hit".
     *
     * ## Where they run
     *
     * Away from [dangerX],[dangerY] -- the average position of every attacker alive this frame,
     * which is "where the invaders are" without having to reason about individual soldiers. A run
     * lasts [PANIC_DURATION_MS] and then the household is assumed to have reached safety; nothing
     * further is drawn for it unless it is hit again.
     */
    private fun drawPanic(
        canvas: Canvas,
        frame: Battle.Frame,
        cast: Battle.Cast,
        cell: Float,
        ink: Paint,
        dangerX: Double,
        dangerY: Double,
        dangerKnown: Boolean,
    ) {
        if (panicCast !== cast) {
            panicCast = cast
            panicHpSeen.clear()
            panics.clear()
        }
        val now = System.currentTimeMillis()
        val index = buildingIndex(cast)

        base.buildings.forEach { building ->
            val type = building.type ?: return@forEach
            if (type.residents <= 0) return@forEach
            val hp = index[building.id]?.let { frame.buildingHp.getOrNull(it) } ?: return@forEach
            val prev = panicHpSeen[building.id] ?: building.maxHitPoints
            panicHpSeen[building.id] = hp
            if (hp < prev && building.id !in panics) {
                val rng = Rng(Rng.seedOf("panic", building.id, frame.tick.toLong()))
                val bx = building.centreX()
                val by = building.centreY()
                val angle = if (dangerKnown) {
                    kotlin.math.atan2(by - dangerY, bx - dangerX).toFloat()
                } else {
                    rng.nextDouble().toFloat() * (Math.PI.toFloat() * 2f)
                }
                panics[building.id] = PanicRun(
                    startedAt = now,
                    fleeAngle = angle,
                    count = (2 + rng.nextInt(type.residents)).coerceAtMost(5),
                )
            }
        }

        if (panics.isEmpty()) return
        val paint = Paint(ink).apply { strokeWidth = ink.strokeWidth * 0.6f; alpha = 210 }
        val expired = ArrayList<String>()

        panics.forEach { (id, run) ->
            val elapsed = now - run.startedAt
            if (elapsed > PANIC_DURATION_MS) {
                expired.add(id)
                return@forEach
            }
            val building = base.buildings.firstOrNull { it.id == id } ?: run.let { expired.add(id); return@forEach }
            val bx = building.centreX()
            val by = building.centreY()
            val progress = (elapsed.toFloat() / PANIC_DURATION_MS).coerceIn(0f, 1f)
            // A burst of speed at the start that tails off, the way a scattering crowd does rather
            // than jogging at a constant pace to a fixed mark.
            val distance = cell * 3.2f * (1f - (1f - progress) * (1f - progress))

            repeat(run.count) { i ->
                val personSeed = Rng.seedOf("panicker", id, i.toLong())
                val rng = Rng(personSeed)
                val spread = ((rng.nextDouble() - 0.5) * 1.1).toFloat()
                val personAngle = run.fleeAngle + spread
                val lag = i * 0.06f
                val personDistance = (distance - lag * cell).coerceAtLeast(0f)
                val fx = offsetX + bx.toFloat() * cell + kotlin.math.cos(personAngle) * personDistance
                val fy = offsetY + by.toFloat() * cell + kotlin.math.sin(personAngle) * personDistance
                if (fx < -cell || fx > width + cell || fy < -cell || fy > height + cell) return@repeat

                val child = i >= 2
                // Running, not walking: nearly double the stride rate reads as fleeing rather than
                // strolling, which is the whole point of drawing this at all.
                PencilStyle.stickFigure(
                    canvas,
                    cx = fx,
                    feetY = fy,
                    height = cell * (if (child) 0.34f else 0.5f),
                    paint = paint,
                    seed = personSeed,
                    phase = (now / 90.0).toFloat() + i * 1.7f,
                    held = PencilStyle.HeldShape.NONE,
                    facingRight = kotlin.math.cos(personAngle) >= 0f,
                )
            }
        }
        expired.forEach { panics.remove(it) }
    }

    /**
     * The lines of fire.
     *
     * Also batched past a point, and for the same reason: every fighter that is in range and off
     * cooldown produces a shot, so a five-thousand-strong assault produces something like a
     * thousand tracer lines a frame, and a pencil line is two Paths and two Paints. A tracer is on
     * screen for a sixteenth of a second, so the wobble on it was never visible anyway.
     */
    private fun drawShots(canvas: Canvas, frame: Battle.Frame, cell: Float) {
        val shots = frame.shots
        if (shots.isEmpty()) return

        if (shots.size <= DETAIL_SHOTS) {
            shots.forEach { shot ->
                val paint = PencilStyle.pencil(
                    if (shot.splash > 0) 2.6f * density else 1.5f * density,
                    if (shot.fromAttacker) PencilStyle.RED_PENCIL else PencilStyle.BLUE_PENCIL,
                    alpha = 190,
                )
                PencilStyle.line(
                    canvas,
                    offsetX + shot.fromX.toFloat() * cell, offsetY + shot.fromY.toFloat() * cell,
                    offsetX + shot.toX.toFloat() * cell, offsetY + shot.toY.toFloat() * cell,
                    paint, seed = frame.tick.toLong() * 31 + shot.toX.toLong(), amount = 2.4f,
                )
                if (shot.splash > 0) blast(canvas, shot, cell, paint)
            }
            return
        }

        var blasts = 0
        shots.forEach { shot ->
            val slot = if (shot.fromAttacker) 0 else 1
            shotStrokes[slot].seg(
                offsetX + shot.fromX.toFloat() * cell, offsetY + shot.fromY.toFloat() * cell,
                offsetX + shot.toX.toFloat() * cell, offsetY + shot.toY.toFloat() * cell,
            )
            // Explosions are the expensive ones and they overlap each other anyway; a couple of
            // dozen across the field reads as a barrage just as well as four hundred does.
            if (shot.splash > 0 && blasts < MAX_BLASTS) {
                blasts++
                blast(
                    canvas, shot, cell,
                    PencilStyle.pencil(
                        2.6f * density,
                        if (shot.fromAttacker) PencilStyle.RED_PENCIL else PencilStyle.BLUE_PENCIL,
                        alpha = 190,
                    ),
                )
            }
        }
        shotStrokes[0].flush(canvas, PencilStyle.pencil(1.5f * density, PencilStyle.RED_PENCIL, alpha = 170))
        shotStrokes[1].flush(canvas, PencilStyle.pencil(1.5f * density, PencilStyle.BLUE_PENCIL, alpha = 170))
    }

    /** A blast: a circle of short radiating strokes, not a sprite. */
    private fun blast(canvas: Canvas, shot: Battle.Shot, cell: Float, paint: Paint) {
        val bx = offsetX + shot.toX.toFloat() * cell
        val by = offsetY + shot.toY.toFloat() * cell
        val inner = shot.splash * cell * 0.35f
        val outer = shot.splash * cell * 0.75f
        repeat(7) { i ->
            val a = i * (2.0 * Math.PI / 7)
            val c = kotlin.math.cos(a).toFloat()
            val sn = kotlin.math.sin(a).toFloat()
            canvas.drawLine(bx + inner * c, by + inner * sn, bx + outer * c, by + outer * sn, paint)
        }
    }

    companion object {
        /** Red, blue, green, and graphite for anybody else. */
        private const val SQUAD_COLOURS = 4

        /**
         * How many fighters get the full pencil treatment.
         *
         * Chosen by what the drawing costs rather than by what looks nice: a hundred and sixty
         * figures is about two thousand Paths a frame, which a phone draws inside a frame budget.
         * Everything above it is marked instead, and at that density the difference is invisible.
         */
        private const val DETAIL_UNITS = 160
        private const val DETAIL_VEHICLES = 90
        private const val DETAIL_SHOTS = 80
        private const val MAX_BLASTS = 24

        /** How long a fleeing household is drawn running before it is assumed to have got clear. */
        private const val PANIC_DURATION_MS = 2_600L
    }

}
