package com.prism.launcher.maps

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.animation.DecelerateInterpolator
import com.prism.launcher.nora.IosUi
import com.prism.launcher.trusted.TrustedDevices
import com.prism.launcher.trusted.TrustedLocations
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The map canvas.
 *
 * It draws cached OpenStreetMap tiles synchronously and requests missing ones in the background.
 * Tiles are addressed only by zoom, column and row; no position is ever part of the request.
 *
 * Gestures are deliberately the ones a map expects: one finger pans, two fingers pinch, a double
 * tap zooms in, and a single tap selects the nearest marker. The page supplies the markers and the
 * selected route; this view only projects and draws them.
 *
 * ## Points of interest and the bubble
 *
 * A POI is a filled circle until it is touched. Touching one grows it into a small chat bubble with
 * the glyph for its kind of place; tapping that bubble opens the page's place card, and long-pressing
 * the bubble unfolds it into a card-shaped bubble carrying the name and address. The bubble is drawn
 * here rather than as a View so it can stay attached to its location while the map pans and so the
 * shadow-tinted crop overlay can sit above the whole map without a second coordinate system.
 *
 * The tail of the bubble points at the coordinate, not at the centre of the circle: a chat bubble
 * whose point drifts away from the thing it describes is worse than no bubble at all.
 */
class OsmMapView(context: Context) : View(context) {

    var fixes: List<TrustedLocations.Fix> = emptyList()
    var trusted: List<TrustedDevices.Trust> = emptyList()
    var favorites: List<MapPlace> = emptyList()
    var pois: List<MapPoi> = emptyList()
    var selectedPlace: MapPlace? = null
    var current: LatLon? = null
    var routePoints: List<LatLon> = emptyList()
    var tilesEnabled: Boolean = true

    /** Opening the place card: a bubble tap, a long-press, or a search result. */
    var onPlaceTap: ((MapPlace) -> Unit)? = null

    /** A circle was tapped, before its bubble has been opened. Used to dismiss a stale card. */
    var onPlacePreview: ((MapPlace) -> Unit)? = null
    var onDeviceTap: ((String?) -> Unit)? = null
    var onMapTap: (() -> Unit)? = null
    var onViewportChanged: (() -> Unit)? = null

    /** Whether the map should draw its night surface. The page keeps this in step with Prism. */
    var dark: Boolean = IosUi.isDark(context)
        set(value) {
            if (field == value) return
            field = value
            tilePaint.colorFilter = if (value) nightFilter() else null
            // The bubble colours were captured when it was opened. Rather than draw a light card on
            // a dark map, let it close and be recreated in the new appearance on the next touch.
            active = null
            bubbleLayout = null
            bubbleProgress = 0f
            textProgress = 0f
            invalidate()
        }

    var zoom: Int = 13
        private set
    var hasCenter: Boolean = false
        private set
    private var centre = LatLon(20.0, 0.0)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tilePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val glyphTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x30000000
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(0.6f)
    }

    private data class Marker(
        val place: MapPlace,
        val category: PoiCategory,
        val favorite: Boolean,
    )

    private var active: Marker? = null
    private var bubbleProgress = 0f
    private var textProgress = 0f
    private var bubbleLayout: BubbleLayout? = null
    private var growAnimator: ValueAnimator? = null
    private var expandAnimator: ValueAnimator? = null
    private var longPressHandled = false

    private data class BubbleLayout(
        val width: Float,
        val height: Float,
        val padding: Float,
        val name: StaticLayout,
        val address: StaticLayout?,
        val namePaint: TextPaint,
        val addressPaint: TextPaint,
    )

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            longPressHandled = false
            return true
        }

        override fun onScroll(
            e1: MotionEvent?,
            e2: MotionEvent,
            distanceX: Float,
            distanceY: Float,
        ): Boolean {
            // A drag is the user moving the map, not selecting the marker under the finger. The
            // bubble goes away so the map does not carry a card around while it is panned.
            collapseBubble()
            val x = MapGeo.lonToWorldX(centre.lon, zoom) + distanceX
            val y = MapGeo.latToWorldY(centre.lat, zoom) + distanceY
            centre = LatLon(MapGeo.worldYToLat(y, zoom), MapGeo.worldXToLon(x, zoom))
            invalidate()
            onViewportChanged?.invoke()
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (longPressHandled) {
                longPressHandled = false
                return true
            }
            handleTap(e.x, e.y)
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            longPressHandled = true
            handleLongPress(e.x, e.y)
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            collapseBubble()
            zoom = (zoom + 1).coerceAtMost(MAX_ZOOM)
            invalidate()
            onViewportChanged?.invoke()
            return true
        }
    })

    private var zoomAtScaleStart = 13
    private val scaleGesture = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            zoomAtScaleStart = zoom
            collapseBubble()
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val target = zoomAtScaleStart + (ln(detector.scaleFactor) / ln(2.0)).roundToInt()
            zoom = target.coerceIn(MIN_ZOOM, MAX_ZOOM)
            invalidate()
            onViewportChanged?.invoke()
            return true
        }
    })

    init {
        isClickable = true
        isFocusable = true
        setBackgroundColor(Color.rgb(14, 14, 18))
        tilePaint.colorFilter = if (dark) nightFilter() else null
    }

    fun centerOn(lat: Double, lon: Double, zoom: Int = this.zoom) {
        centre = LatLon(lat, lon)
        this.zoom = zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        hasCenter = true
        invalidate()
    }

    fun center(): LatLon = centre

    fun zoomIn() {
        zoom = (zoom + 1).coerceAtMost(MAX_ZOOM)
        invalidate()
        onViewportChanged?.invoke()
    }

    fun zoomOut() {
        zoom = (zoom - 1).coerceAtLeast(MIN_ZOOM)
        invalidate()
        onViewportChanged?.invoke()
    }

    /** The corner of the visible map, used by the crop overlay to compute its download area. */
    fun screenToLatLon(x: Float, y: Float): LatLon {
        val width = width.toFloat().coerceAtLeast(1f)
        val height = height.toFloat().coerceAtLeast(1f)
        val left = MapGeo.lonToWorldX(centre.lon, zoom) - width / 2.0
        val top = MapGeo.latToWorldY(centre.lat, zoom) - height / 2.0
        return LatLon(
            MapGeo.worldYToLat(top + y, zoom),
            MapGeo.worldXToLon(left + x, zoom),
        )
    }

    fun visibleBounds(marginPx: Float = 0f): MapBounds {
        val w = width.toFloat()
        val h = height.toFloat()
        val topLeft = screenToLatLon(-marginPx, -marginPx)
        val bottomRight = screenToLatLon(w + marginPx, h + marginPx)
        return MapBounds(
            south = bottomRight.lat.coerceAtMost(topLeft.lat),
            west = topLeft.lon.coerceAtMost(bottomRight.lon),
            north = topLeft.lat.coerceAtLeast(bottomRight.lat),
            east = bottomRight.lon.coerceAtLeast(topLeft.lon),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val width = width.toFloat()
        val height = height.toFloat()
        if (width <= 0f || height <= 0f) return

        val surface = if (dark) Color.rgb(14, 14, 18) else Color.rgb(233, 230, 224)
        canvas.drawColor(surface)

        fun screenX(lon: Double): Float {
            val left = MapGeo.lonToWorldX(centre.lon, zoom) - width / 2.0
            return (MapGeo.lonToWorldX(lon, zoom) - left).toFloat()
        }

        fun screenY(lat: Double): Float {
            val top = MapGeo.latToWorldY(centre.lat, zoom) - height / 2.0
            return (MapGeo.latToWorldY(lat, zoom) - top).toFloat()
        }

        if (tilesEnabled) {
            val left = MapGeo.lonToWorldX(centre.lon, zoom) - width / 2.0
            val top = MapGeo.latToWorldY(centre.lat, zoom) - height / 2.0
            val count = 1 shl zoom
            val firstX = floor(left / TILE).toInt()
            val lastX = floor((left + width) / TILE).toInt()
            val firstY = floor(top / TILE).toInt()
            val lastY = floor((top + height) / TILE).toInt()
            for (x in firstX..lastX) {
                for (y in firstY..lastY) {
                    if (x !in 0 until count || y !in 0 until count) continue
                    val dx = (x * TILE - left).toFloat()
                    val dy = (y * TILE - top).toFloat()
                    val tile = MapTiles.get(zoom, x, y) { postInvalidateOnAnimation() }
                    if (tile != null) {
                        canvas.drawBitmap(
                            tile,
                            null,
                            RectF(dx, dy, dx + TILE.toFloat(), dy + TILE.toFloat()),
                            tilePaint,
                        )
                    } else {
                        paint.color = if (dark) Color.rgb(23, 23, 28) else Color.rgb(215, 211, 204)
                        canvas.drawRect(dx, dy, dx + TILE.toFloat(), dy + TILE.toFloat(), paint)
                    }
                }
            }
        }

        if (routePoints.size > 1) {
            val path = Path()
            routePoints.forEachIndexed { index, point ->
                val x = screenX(point.lon)
                val y = screenY(point.lat)
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            routePaint.color = if (dark) Color.rgb(9, 9, 12) else Color.rgb(255, 255, 255)
            routePaint.strokeWidth = dp(11f)
            canvas.drawPath(path, routePaint)
            routePaint.color = Color.rgb(10, 132, 255)
            routePaint.strokeWidth = dp(6f)
            canvas.drawPath(path, routePaint)
            routePoints.firstOrNull()?.let { drawDot(canvas, screenX(it.lon), screenY(it.lat), Color.WHITE, 7f) }
            routePoints.lastOrNull()?.let { drawDot(canvas, screenX(it.lon), screenY(it.lat), Color.rgb(48, 209, 88), 9f) }
        }

        val bubbleAlpha = bubbleProgress.coerceIn(0f, 1f)

        // POIs first, so a saved place's gold star sits above a plain circle if both are at one spot.
        pois.forEach { poi ->
            val x = screenX(poi.lon)
            val y = screenY(poi.lat)
            if (x < -32 || y < -32 || x > width + 32 || y > height + 32) return@forEach
            val isActive = active?.place?.id == poi.id && active?.favorite == false
            if (isActive) return@forEach
            drawPoiCircle(canvas, x, y, 1f)
        }

        favorites.forEach { place ->
            val x = screenX(place.lon)
            val y = screenY(place.lat)
            if (x < -34 || y < -34 || x > width + 34 || y > height + 34) return@forEach
            val isActive = active?.place?.id == place.id && active?.favorite == true
            val alpha = if (isActive) (1f - bubbleAlpha).coerceIn(0f, 1f) else 1f
            paint.color = Color.rgb(255, 214, 10)
            paint.alpha = (alpha * 255).toInt()
            canvas.drawCircle(x, y, dp(12f), paint)
            paint.alpha = 255
            starPaint.textSize = dp(17f)
            starPaint.color = Color.rgb(35, 28, 0)
            starPaint.alpha = (alpha * 255).toInt()
            canvas.drawText("★", x, y + dp(6f), starPaint)
            starPaint.alpha = 255
        }

        selectedPlace?.let { place ->
            val x = screenX(place.lon)
            val y = screenY(place.lat)
            paint.color = Color.rgb(255, 59, 48)
            canvas.drawCircle(x, y, dp(12f), paint)
            paint.color = Color.WHITE
            canvas.drawCircle(x, y, dp(4.5f), paint)
        }

        current?.let { point ->
            val x = screenX(point.lon)
            val y = screenY(point.lat)
            paint.color = Color.WHITE
            canvas.drawCircle(x, y, dp(11f), paint)
            paint.color = Color.rgb(10, 132, 255)
            canvas.drawCircle(x, y, dp(8f), paint)
        }

        fixes.forEach { fix ->
            val x = screenX(fix.longitude)
            val y = screenY(fix.latitude)
            if (x < -24 || y < -24 || x > width + 24 || y > height + 24) return@forEach
            val device = trusted.firstOrNull { it.answersTo(fix.fingerprint) }
            val colour = if (device?.online == true) Color.rgb(48, 209, 88) else Color.rgb(255, 111, 181)
            drawDot(canvas, x, y, colour, 7f)
        }

        active?.let { marker ->
            drawActiveBubble(canvas, marker, screenX(marker.place.lon), screenY(marker.place.lat))
        }
    }

    private fun drawPoiCircle(canvas: Canvas, x: Float, y: Float, alpha: Float) {
        val colour = if (dark) Color.WHITE else Color.rgb(91, 91, 97)
        shadowPaint.alpha = (alpha * 48f).toInt()
        canvas.drawCircle(x, y + dp(2f), dp(8f), shadowPaint)
        paint.color = colour
        paint.alpha = (alpha * 255).toInt()
        canvas.drawCircle(x, y, dp(7f), paint)
        paint.alpha = 255
    }

    private fun drawActiveBubble(canvas: Canvas, marker: Marker, anchorX: Float, anchorY: Float) {
        val progress = bubbleProgress.coerceIn(0f, 1f)
        if (progress <= 0.01f) return
        val layout = bubbleLayout
        val small = dp(36f)
        val width = dp(14f) + (small - dp(14f)) * progress +
            ((layout?.width ?: small) - small) * textProgress
        val height = dp(14f) + (small - dp(14f)) * progress +
            ((layout?.height ?: small) - small) * textProgress
        val tail = dp(9f)
        val tipY = anchorY - dp(3f)
        val bodyBottom = tipY - tail
        val bodyCx = bodyCentreX(anchorX, width)
        val left = bodyCx - width / 2f
        val top = bodyBottom - height
        val right = bodyCx + width / 2f
        val radius = minOf(width / 2f, dp(20f))

        val body = Path().apply {
            addRoundRect(RectF(left, top, right, bodyBottom), radius, radius, Path.Direction.CW)
        }
        val tailPath = Path().apply {
            moveTo(bodyCx - dp(8f), bodyBottom - dp(1f))
            lineTo(anchorX, tipY)
            lineTo(bodyCx + dp(8f), bodyBottom - dp(1f))
            close()
        }
        // Union rather than two subpaths: stroking overlapping subpaths draws a seam across the
        // base of the tail, which is exactly where a chat bubble should look continuous.
        body.op(tailPath, Path.Op.UNION)
        val path = body

        canvas.save()
        canvas.translate(0f, dp(2.5f))
        shadowPaint.alpha = (progress * 90).toInt()
        canvas.drawPath(path, shadowPaint)
        canvas.restore()

        paint.color = if (dark) 0xFF1C1C1E.toInt() else Color.WHITE
        paint.alpha = (progress * 255).toInt()
        canvas.drawPath(path, paint)
        paint.alpha = 255
        borderPaint.color = if (dark) 0x33FFFFFF else 0x22000000
        borderPaint.alpha = (progress * 255).toInt()
        canvas.drawPath(path, borderPaint)
        borderPaint.alpha = 255

        val iconAlpha = ((1f - textProgress).coerceIn(0f, 1f) * progress * 255f).toInt()
        if (iconAlpha > 4) {
            val iconSize = dp(19f)
            drawPoiGlyph(
                canvas,
                bodyCx,
                (top + bodyBottom) / 2f,
                iconSize,
                marker.category,
                marker.favorite,
                if (dark) Color.WHITE else Color.rgb(42, 42, 47),
                iconAlpha,
            )
        }

        if (textProgress > 0.01f && layout != null) {
            canvas.save()
            // A rectangular clip rather than the bubble path: hardware canvases only promise
            // clipping against convex paths, and the tail makes this outline concave. The text is
            // inset by the padding, so the rounded corners are never where a glyph can reach.
            canvas.clipRect(left, top, right, bodyBottom)
            val pad = layout.padding
            layout.namePaint.alpha = (textProgress * progress * 255f).toInt()
            layout.addressPaint.alpha = (textProgress * progress * 255f).toInt()
            val textTop = top + pad
            canvas.translate(left + pad, textTop)
            layout.name.draw(canvas)
            layout.address?.let { address ->
                canvas.translate(0f, layout.name.height + dp(3f))
                address.draw(canvas)
            }
            canvas.restore()
        }

        paint.alpha = 255
    }

    private fun drawDot(canvas: Canvas, x: Float, y: Float, colour: Int, radiusDp: Float) {
        paint.color = Color.rgb(11, 11, 14)
        canvas.drawCircle(x, y, dp(radiusDp + 2f), paint)
        paint.color = colour
        canvas.drawCircle(x, y, dp(radiusDp), paint)
    }

    private fun handleTap(x: Float, y: Float) {
        val activeMarker = active
        if (activeMarker != null && insideBubble(x, y)) {
            // The card carries the name and address; leaving the bubble expanded underneath it
            // would show the same text twice. Shrink back to the icon as the card opens.
            if (textProgress > 0.01f) toggleExpanded()
            onPlaceTap?.invoke(activeMarker.place)
            return
        }

        val hit = markerAt(x, y)
        if (hit != null) {
            activate(hit)
            onPlacePreview?.invoke(hit.place)
            return
        }

        val hadBubble = active != null
        collapseBubble()
        if (activeMarker != null || hadBubble) {
            // The tap outside the bubble did its job; the page's card is cleared by onMapTap.
        }
        onDeviceTap?.invoke(null)
        selectedPlace = null
        invalidate()
        onMapTap?.invoke()
    }

    private fun handleLongPress(x: Float, y: Float) {
        val activeMarker = active
        if (activeMarker != null && insideBubble(x, y)) {
            toggleExpanded()
            return
        }
        val hit = markerAt(x, y)
        if (hit != null) {
            activate(hit)
            onPlaceTap?.invoke(hit.place)
        }
    }

    private fun markerAt(x: Float, y: Float): Marker? {
        val width = width.toFloat()
        val height = height.toFloat()
        val left = MapGeo.lonToWorldX(centre.lon, zoom) - width / 2.0
        val top = MapGeo.latToWorldY(centre.lat, zoom) - height / 2.0
        fun sx(lon: Double) = (MapGeo.lonToWorldX(lon, zoom) - left).toFloat()
        fun sy(lat: Double) = (MapGeo.latToWorldY(lat, zoom) - top).toFloat()
        fun near(px: Float, py: Float, limit: Float = dp(30f)): Boolean {
            val dx = px - x
            val dy = py - y
            return dx * dx + dy * dy <= limit * limit
        }

        favorites.firstOrNull { near(sx(it.lon), sy(it.lat)) }?.let {
            return Marker(it, PoiCategory.OTHER, true)
        }
        pois.firstOrNull { near(sx(it.lon), sy(it.lat)) }?.let {
            return Marker(it.place(), it.category, false)
        }
        return null
    }

    private fun insideBubble(x: Float, y: Float): Boolean {
        val marker = active ?: return false
        if (bubbleProgress <= 0.2f) return false
        val anchorX = screenX(marker.place.lon)
        val anchorY = screenY(marker.place.lat)
        val small = dp(36f)
        val layout = bubbleLayout
        val width = dp(14f) + (small - dp(14f)) * bubbleProgress +
            ((layout?.width ?: small) - small) * textProgress
        val height = dp(14f) + (small - dp(14f)) * bubbleProgress +
            ((layout?.height ?: small) - small) * textProgress
        val bodyBottom = anchorY - dp(3f) - dp(9f)
        val bodyCx = bodyCentreX(anchorX, width)
        val left = bodyCx - width / 2f - dp(5f)
        val top = bodyBottom - height - dp(5f)
        val right = bodyCx + width / 2f + dp(5f)
        val bottom = bodyBottom + dp(2f)
        if (x in left..right && y in top..bottom) return true
        // The tail is part of the bubble too; a tap on it is a tap on the icon.
        if (y in bottom..(anchorY - dp(1f)) &&
            abs(x - anchorX) <= dp(10f) * (1f - (y - bottom) / dp(10f))
        ) return true
        return false
    }

    /**
     * Keeps a bubble on screen while its tail continues to point at the coordinate.
     *
     * A marker near the edge would otherwise open a card-shaped bubble half outside the canvas.
     * Sliding the body inwards and letting the tail lean is what every map does here.
     */
    private fun bodyCentreX(anchorX: Float, bubbleWidth: Float): Float {
        val viewWidth = width.toFloat()
        val half = bubbleWidth / 2f
        if (half * 2f + dp(8f) >= viewWidth) return viewWidth / 2f
        return anchorX.coerceIn(half + dp(4f), viewWidth - half - dp(4f))
    }

    private fun screenX(lon: Double): Float {
        val left = MapGeo.lonToWorldX(centre.lon, zoom) - width / 2.0
        return (MapGeo.lonToWorldX(lon, zoom) - left).toFloat()
    }

    private fun screenY(lat: Double): Float {
        val top = MapGeo.latToWorldY(centre.lat, zoom) - height / 2.0
        return (MapGeo.latToWorldY(lat, zoom) - top).toFloat()
    }

    private fun activate(marker: Marker) {
        if (active?.place?.id == marker.place.id && active?.favorite == marker.favorite) {
            if (bubbleProgress < 1f) animateGrow(1f)
            return
        }
        growAnimator?.cancel()
        expandAnimator?.cancel()
        active = marker
        bubbleLayout = buildBubbleLayout(marker)
        bubbleProgress = 0f
        textProgress = 0f
        animateGrow(1f)
    }

    private fun animateGrow(target: Float) {
        growAnimator?.cancel()
        val from = bubbleProgress
        growAnimator = ValueAnimator.ofFloat(from, target).apply {
            duration = 190L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                bubbleProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun collapseBubble() {
        val marker = active ?: return
        growAnimator?.cancel()
        expandAnimator?.cancel()
        textProgress = 0f
        val from = bubbleProgress
        growAnimator = ValueAnimator.ofFloat(from, 0f).apply {
            duration = 140L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                bubbleProgress = it.animatedValue as Float
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (active === marker && bubbleProgress <= 0.01f) {
                        active = null
                        bubbleLayout = null
                        invalidate()
                    }
                }
            })
            start()
        }
    }

    private fun toggleExpanded() {
        if (active == null) return
        val target = if (textProgress > 0.5f) 0f else 1f
        expandAnimator?.cancel()
        expandAnimator = ValueAnimator.ofFloat(textProgress, target).apply {
            duration = 240L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                textProgress = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun buildBubbleLayout(marker: Marker): BubbleLayout {
        val place = marker.place
        val padding = dp(13f)
        val maxTextWidth = dp(206f).toInt()
        val namePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = sp(14.5f)
            typeface = Typeface.DEFAULT_BOLD
            color = if (dark) Color.WHITE else Color.rgb(20, 20, 24)
        }
        val addressPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = sp(11.5f)
            color = if (dark) Color.rgb(174, 174, 182) else Color.rgb(96, 96, 104)
        }
        val name = place.name.ifBlank { marker.category.label }
        val address = place.address.ifBlank { "Address not listed" }
        val desired = max(
            namePaint.measureText(name),
            addressPaint.measureText(address),
        ).toInt()
        val contentWidth = desired.coerceIn(dp(116f).toInt(), maxTextWidth)
        val nameLayout = textLayout(name, namePaint, contentWidth, 3)
        val addressLayout = textLayout(address, addressPaint, contentWidth, 3)
        val height = padding + nameLayout.height + dp(3f) + addressLayout.height + padding
        return BubbleLayout(
            width = contentWidth + padding * 2f,
            height = max(height, dp(58f)),
            padding = padding,
            name = nameLayout,
            address = addressLayout,
            namePaint = namePaint,
            addressPaint = addressPaint,
        )
    }

    private fun textLayout(text: String, paint: TextPaint, width: Int, maxLines: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .setLineSpacing(0f, 1.0f)
            .setMaxLines(maxLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        val handled = scaleGesture.onTouchEvent(event)
        val tapped = gestures.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP ||
            event.actionMasked == MotionEvent.ACTION_CANCEL
        ) {
            parent?.requestDisallowInterceptTouchEvent(false)
        }
        return handled || tapped || super.onTouchEvent(event)
    }

    override fun onDetachedFromWindow() {
        growAnimator?.cancel()
        expandAnimator?.cancel()
        super.onDetachedFromWindow()
    }

    // ── Glyphs ─────────────────────────────────────────────────────────────

    private fun drawPoiGlyph(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        size: Float,
        category: PoiCategory,
        favorite: Boolean,
        colour: Int,
        alpha: Int,
    ) {
        if (favorite) {
            val path = starPath(cx, cy, size * 0.48f, size * 0.21f)
            paint.color = Color.rgb(255, 200, 20)
            paint.alpha = alpha
            paint.style = Paint.Style.FILL
            canvas.drawPath(path, paint)
            paint.alpha = 255
            paint.style = Paint.Style.FILL
            return
        }

        iconPaint.color = colour
        iconPaint.alpha = alpha
        iconPaint.style = Paint.Style.STROKE
        iconPaint.strokeWidth = size * 0.075f
        val p = iconPaint
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
            canvas.drawLine(cx + x1 * size, cy + y1 * size, cx + x2 * size, cy + y2 * size, p)
        fun rect(left: Float, top: Float, right: Float, bottom: Float, radius: Float = 0f) {
            val r = RectF(cx + left * size, cy + top * size, cx + right * size, cy + bottom * size)
            if (radius > 0f) canvas.drawRoundRect(r, radius * size, radius * size, p)
            else canvas.drawRect(r, p)
        }

        when (category) {
            PoiCategory.PARK -> {
                line(-0.02f, 0.10f, -0.02f, 0.45f)
                val canopy = Path().apply {
                    moveTo(cx, cy - size * 0.48f)
                    lineTo(cx - size * 0.36f, cy + size * 0.12f)
                    lineTo(cx + size * 0.36f, cy + size * 0.12f)
                    close()
                }
                p.style = Paint.Style.FILL
                canvas.drawPath(canopy, p)
                p.style = Paint.Style.STROKE
            }
            PoiCategory.FOOD -> {
                line(-0.30f, -0.42f, -0.30f, 0.44f)
                line(-0.40f, -0.42f, -0.40f, -0.12f)
                line(-0.19f, -0.42f, -0.19f, -0.12f)
                line(-0.40f, -0.12f, -0.19f, -0.12f)
                line(0.30f, -0.42f, 0.30f, 0.44f)
                line(0.30f, -0.42f, 0.43f, -0.12f)
                line(0.30f, -0.12f, 0.20f, -0.03f)
            }
            PoiCategory.CAFE -> {
                rect(-0.38f, -0.18f, 0.18f, 0.34f, 0.08f)
                canvas.drawArc(
                    RectF(cx + 0.08f * size, cy - 0.06f * size, cx + 0.44f * size, cy + 0.20f * size),
                    -75f, 150f, false, p,
                )
                line(-0.18f, -0.36f, -0.12f, -0.46f)
                line(0.02f, -0.36f, 0.08f, -0.46f)
            }
            PoiCategory.HEALTH -> {
                p.strokeWidth = size * 0.19f
                line(-0.30f, 0f, 0.30f, 0f)
                line(0f, -0.30f, 0f, 0.30f)
                p.strokeWidth = size * 0.075f
            }
            PoiCategory.POLICE -> {
                val shield = Path().apply {
                    moveTo(cx, cy - size * 0.42f)
                    lineTo(cx + size * 0.34f, cy - size * 0.28f)
                    lineTo(cx + size * 0.27f, cy + size * 0.24f)
                    lineTo(cx, cy + size * 0.44f)
                    lineTo(cx - size * 0.27f, cy + size * 0.24f)
                    lineTo(cx - size * 0.34f, cy - size * 0.28f)
                    close()
                }
                canvas.drawPath(shield, p)
                canvas.drawPath(starPath(cx, cy + size * 0.02f, size * 0.13f, size * 0.055f), p)
            }
            PoiCategory.FIRE -> {
                val flame = Path().apply {
                    moveTo(cx, cy - size * 0.46f)
                    cubicTo(
                        cx + size * 0.31f, cy - size * 0.15f,
                        cx + size * 0.34f, cy + size * 0.30f,
                        cx, cy + size * 0.44f,
                    )
                    cubicTo(
                        cx - size * 0.34f, cy + size * 0.30f,
                        cx - size * 0.20f, cy - size * 0.02f,
                        cx, cy - size * 0.46f,
                    )
                    close()
                }
                canvas.drawPath(flame, p)
            }
            PoiCategory.SCHOOL, PoiCategory.LIBRARY -> {
                line(0f, -0.27f, 0f, 0.31f)
                line(0f, -0.27f, -0.38f, -0.18f)
                line(-0.38f, -0.18f, -0.38f, 0.36f)
                line(-0.38f, 0.36f, 0f, 0.27f)
                line(0f, -0.27f, 0.38f, -0.18f)
                line(0.38f, -0.18f, 0.38f, 0.36f)
                line(0.38f, 0.36f, 0f, 0.27f)
                line(-0.29f, 0.01f, -0.09f, 0.06f)
                line(0.09f, 0.06f, 0.29f, 0.01f)
            }
            PoiCategory.HOTEL -> {
                line(-0.42f, 0.36f, 0.42f, 0.36f)
                line(-0.42f, -0.30f, -0.42f, 0.36f)
                line(0.42f, -0.16f, 0.42f, 0.36f)
                rect(-0.28f, -0.10f, 0.30f, 0.24f, 0.05f)
                line(-0.16f, -0.10f, -0.16f, 0.06f)
                line(0.02f, -0.10f, 0.02f, 0.06f)
            }
            PoiCategory.CULTURE -> {
                line(-0.40f, -0.34f, 0.40f, -0.34f)
                line(-0.34f, -0.18f, 0.34f, -0.18f)
                line(-0.27f, -0.34f, -0.34f, -0.18f)
                line(0f, -0.34f, -0.07f, -0.18f)
                line(0.27f, -0.34f, 0.20f, -0.18f)
                line(-0.32f, -0.18f, -0.32f, 0.30f)
                line(0f, -0.18f, 0f, 0.30f)
                line(0.32f, -0.18f, 0.32f, 0.30f)
                line(-0.42f, 0.30f, 0.42f, 0.30f)
                line(-0.42f, 0.42f, 0.42f, 0.42f)
            }
            PoiCategory.SHOP -> {
                rect(-0.34f, -0.16f, 0.34f, 0.40f, 0.06f)
                canvas.drawArc(
                    RectF(cx - 0.20f * size, cy - 0.40f * size, cx + 0.20f * size, cy + 0.02f * size),
                    180f, 180f, false, p,
                )
                line(-0.20f, -0.16f, -0.20f, -0.30f)
                line(0.20f, -0.16f, 0.20f, -0.30f)
            }
            PoiCategory.BANK -> {
                glyphTextPaint.color = colour
                glyphTextPaint.alpha = alpha
                glyphTextPaint.textSize = size * 0.70f
                canvas.drawText("$", cx, cy + size * 0.24f, glyphTextPaint)
            }
            PoiCategory.FUEL -> {
                rect(-0.36f, -0.34f, 0.10f, 0.40f, 0.05f)
                line(-0.22f, -0.34f, -0.22f, -0.02f)
                line(0.10f, -0.12f, 0.30f, -0.12f)
                line(0.30f, -0.12f, 0.30f, 0.22f)
                line(0.30f, 0.22f, 0.40f, 0.32f)
                line(0.40f, 0.32f, 0.40f, 0.40f)
            }
            PoiCategory.PARKING -> {
                glyphTextPaint.color = colour
                glyphTextPaint.alpha = alpha
                glyphTextPaint.textSize = size * 0.66f
                canvas.drawText("P", cx, cy + size * 0.23f, glyphTextPaint)
            }
            PoiCategory.TRANSIT -> {
                rect(-0.32f, -0.34f, 0.32f, 0.26f, 0.09f)
                line(-0.24f, -0.04f, 0.24f, -0.04f)
                line(-0.20f, -0.22f, -0.04f, -0.22f)
                line(0.04f, -0.22f, 0.20f, -0.22f)
                line(-0.20f, 0.26f, -0.26f, 0.42f)
                line(0.20f, 0.26f, 0.26f, 0.42f)
                p.style = Paint.Style.FILL
                canvas.drawCircle(cx - 0.18f * size, cy + 0.36f * size, size * 0.045f, p)
                canvas.drawCircle(cx + 0.18f * size, cy + 0.36f * size, size * 0.045f, p)
                p.style = Paint.Style.STROKE
            }
            PoiCategory.WORSHIP -> {
                val roof = Path().apply {
                    moveTo(cx, cy - size * 0.45f)
                    lineTo(cx - size * 0.38f, cy - size * 0.12f)
                    lineTo(cx + size * 0.38f, cy - size * 0.12f)
                    close()
                }
                canvas.drawPath(roof, p)
                rect(-0.26f, -0.12f, 0.26f, 0.42f)
                line(0f, -0.12f, 0f, 0.42f)
                line(0f, -0.52f, 0f, -0.60f)
                line(-0.06f, -0.56f, 0.06f, -0.56f)
            }
            PoiCategory.OTHER -> {
                val pin = Path().apply {
                    moveTo(cx, cy + size * 0.46f)
                    cubicTo(
                        cx - size * 0.34f, cy + size * 0.06f,
                        cx - size * 0.28f, cy - size * 0.30f,
                        cx, cy - size * 0.36f,
                    )
                    cubicTo(
                        cx + size * 0.28f, cy - size * 0.30f,
                        cx + size * 0.34f, cy + size * 0.06f,
                        cx, cy + size * 0.46f,
                    )
                    close()
                }
                canvas.drawPath(pin, p)
                canvas.drawCircle(cx, cy - size * 0.05f, size * 0.10f, p)
            }
        }
        iconPaint.alpha = 255
        glyphTextPaint.alpha = 255
    }

    private fun starPath(cx: Float, cy: Float, outer: Float, inner: Float): Path {
        val path = Path()
        for (i in 0 until 10) {
            val radius = if (i % 2 == 0) outer else inner
            val angle = (-PI / 2.0) + i * PI / 5.0
            val x = cx + (cos(angle) * radius).toFloat()
            val y = cy + (sin(angle) * radius).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        return path
    }

    // ── Night tiles ────────────────────────────────────────────────────────

    /**
     * Turns the light OSM raster into a dark one.
     *
     * A tile server bakes its colours into the PNG, so an offline map cannot ask for a dark variant
     * later without a second provider and a second copy of every tile. Inverting, rotating the hue
     * by 180 degrees (which returns water to blue and parks to green) and lifting the shadows gives
     * readable dark roads and labels while keeping a downloaded area usable in both appearances.
     */
    private fun nightFilter(): ColorMatrixColorFilter {
        val invert = ColorMatrix(floatArrayOf(
            -1f, 0f, 0f, 0f, 255f,
            0f, -1f, 0f, 0f, 255f,
            0f, 0f, -1f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f,
        ))
        val hue = ColorMatrix(floatArrayOf(
            -0.574f, 1.430f, 0.144f, 0f, 0f,
            0.426f, 0.430f, 0.144f, 0f, 0f,
            0.426f, 1.430f, -0.856f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        ))
        val saturation = ColorMatrix().apply { setSaturation(0.62f) }
        val lift = ColorMatrix(floatArrayOf(
            1.85f, 0f, 0f, 0f, 32f,
            0f, 1.85f, 0f, 0f, 32f,
            0f, 0f, 1.85f, 0f, 32f,
            0f, 0f, 0f, 1f, 0f,
        ))
        val invertedHue = ColorMatrix().apply { setConcat(hue, invert) }
        val muted = ColorMatrix().apply { setConcat(saturation, invertedHue) }
        return ColorMatrixColorFilter(ColorMatrix().apply { setConcat(lift, muted) })
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private fun sp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    private companion object {
        const val TILE = MapGeo.TILE_SIZE
        const val MIN_ZOOM = 2
        const val MAX_ZOOM = 19
    }
}
