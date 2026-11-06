package com.prism.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prism.desktop.ar.DesktopArScanner
import com.prism.launcher.PrismSettings
import com.prism.launcher.SlotAssignment
import com.prism.launcher.SlotPreferences
import com.prism.launcher.spatial.ArHome
import com.prism.launcher.spatial.RoomLayout
import com.prism.launcher.spatial.Vec3
import kotlinx.coroutines.Dispatchers
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The home screen in augmented reality, on a computer: Settings → Other → "Augmented reality for
 * all of Prism", the same switch and the same behaviour as the phone's [com.prism.launcher.ar]
 * screen, seen through the webcam.
 *
 * The first time, a grid of every page sits at the bottom; dragging one onto a wall, the floor or
 * the ceiling places it, and the place is saved relative to the room. While placing, a page is
 * selected by clicking it, grown or shrunk with the scroll wheel or a pinch, moved by dragging, and
 * taken down with a right-click. After Done, a click lifts a page into a movable window -- or, with
 * "Interact live", works the page where it is.
 *
 * A placed page is the real page, rendered off screen ([ImageComposeScene]) and drawn onto its
 * surface in true perspective; live interaction sends the pointer to that scene in the page's own
 * coordinates.
 */
@Composable
fun ArHomePage(onFlat: () -> Unit) {
    val colors = LocalPrismColors.current
    DisposableEffect(Unit) {
        DesktopArScanner.start()
        onDispose { DesktopArScanner.stop() }
    }
    val room = DesktopArScanner.room
    val slots = remember { SlotPreferences().getAssignments().mapIndexed { i, s -> ArHome.pageKey(s.serialize(), i) to (s to i) } }
    val scenes = remember { HashMap<String, PageScene>() }
    DisposableEffect(Unit) { onDispose { scenes.values.forEach { it.close() }; scenes.clear() } }

    var editing by remember { mutableStateOf(!room.setupDone) }
    var selected by remember { mutableStateOf<String?>(null) }
    var hover by remember { mutableStateOf<RoomLayout.Surface?>(null) }
    var dragging by remember { mutableStateOf<Pair<String, Offset>?>(null) }
    var popup by remember { mutableStateOf<String?>(null) }
    var popupOffset by remember { mutableStateOf(IntOffset(80, 60)) }
    var boxOrigin by remember { mutableStateOf(Offset.Zero) }
    var viewSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    var tick by remember { mutableStateOf(0L) }
    var liveKey by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { while (true) withFrameNanos { tick = it } }

    // OCCLUSION: where something real is nearer than the page behind it, a mask of those pixels,
    // remade several times a second from the latest depth.
    var occluders by remember { mutableStateOf<org.jetbrains.skia.Image?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            occluders = kotlinx.coroutines.withContext(Dispatchers.Default) { occlusionMask(room.placed()) }
            kotlinx.coroutines.delay(150)
        }
    }

    val background = DesktopArScanner.frame.value
    val camera = DesktopArScanner.camera

    // ── Image ↔ view: the picture fills the view, cropped, as ContentScale.Crop draws it. ──
    fun fit(): Triple<Float, Float, Float>? {
        val k = camera?.second ?: return null
        if (viewSize.width == 0) return null
        val scale = max(viewSize.width / k.width.toFloat(), viewSize.height / k.height.toFloat())
        return Triple(scale, (viewSize.width - k.width * scale) / 2, (viewSize.height - k.height * scale) / 2)
    }

    fun toView(p: Vec3): Offset? {
        val (pose, k) = camera ?: return null
        val (scale, ox, oy) = fit() ?: return null
        val c = pose.inverseTransform(p)
        val (u, v) = k.project(c) ?: return null
        return Offset(ox + u * scale, oy + v * scale)
    }

    fun ray(at: Offset): Pair<Vec3, Vec3>? {
        val (pose, k) = camera ?: return null
        val (scale, ox, oy) = fit() ?: return null
        val u = (at.x - ox) / scale
        val v = (at.y - oy) / scale
        return pose.translation to pose.rotate(k.ray(u, v)).normalized()
    }

    fun sceneFor(key: String): PageScene? {
        val (slot, index) = slots.firstOrNull { it.first == key }?.second ?: return null
        return scenes.getOrPut(key) { PageScene(slot, index) }
    }

    fun place(key: String, at: Offset) {
        val r = ray(at)
        val hit = r?.let { (o, d) -> room.layout.raycast(o, d) }
        // Where the depth says the real surface is under the pointer: the page goes there, on the
        // surface's orientation, so it sits on the wall rather than where the model put it.
        val measured = run {
            val k = camera?.second ?: return@run null
            val (scale, ox, oy) = fit() ?: return@run null
            DesktopArScanner.measure((at.x - ox) / scale, (at.y - oy) / scale, k.width, k.height)
        }
        if (hit == null || !room.place(key, hit, r.first, PageScene.WIDTH / PageScene.HEIGHT.toFloat(), measured)) {
            notice = "Prism has not found a wall, floor or ceiling there yet. Give it a moment."
            return
        }
        selected = key
    }

    fun pageAt(at: Offset) = ray(at)?.let { (o, d) -> room.pageAt(o, d) }

    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .onGloballyPositioned { boxOrigin = it.positionInRoot(); viewSize = it.size },
    ) {
        if (background != null) {
            Image(background, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }

        // The room's surfaces while placing, and every placed page.
        Canvas(
            Modifier.fillMaxSize().pointerInput(editing) {
                awaitPointerEventScope {
                    var downAt = Offset.Zero
                    var moved = false
                    var pinch = 0f
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        val position = event.changes.first().position
                        val live = !editing && PrismSettings.getArInteractLive()
                        when (event.type) {
                            PointerEventType.Scroll -> {
                                val delta = event.changes.first().scrollDelta.y
                                if (editing) {
                                    val key = pageAt(position)?.placement?.key ?: selected
                                    if (key != null) { selected = key; room.resize(key, if (delta < 0) 1.08f else 1 / 1.08f) }
                                } else if (live) {
                                    pageAt(position)?.let { hit -> sceneFor(hit.placement.key)?.scroll(hit.x, hit.y, event.changes.first().scrollDelta) }
                                }
                            }
                            PointerEventType.Press -> {
                                downAt = position; moved = false
                                if (event.buttons.isSecondaryPressed && editing) {
                                    pageAt(position)?.let { room.remove(it.placement.key); selected = null; notice = "Took the page down" }
                                } else if (live) {
                                    pageAt(position)?.let { hit ->
                                        liveKey = hit.placement.key
                                        sceneFor(hit.placement.key)?.pointer(PointerEventType.Press, hit.x, hit.y)
                                    }
                                } else if (editing) {
                                    selected = pageAt(position)?.placement?.key ?: selected
                                }
                                if (pressed.size == 2) pinch = (pressed[0].position - pressed[1].position).getDistance()
                            }
                            PointerEventType.Move -> {
                                if (pressed.isEmpty()) continue
                                if ((position - downAt).getDistance() > 6f) moved = true
                                if (live) {
                                    val key = liveKey
                                    val placement = room.placed().firstOrNull { it.key == key }
                                    val r = ray(position)
                                    if (key != null && placement != null && r != null) {
                                        ArHome.hit(placement, r.first, r.second, margin = 3f)?.let { sceneFor(key)?.pointer(PointerEventType.Move, it.x, it.y) }
                                    }
                                } else if (editing && pressed.size == 2) {
                                    // A touchscreen pinch: the selected page grows and shrinks with it.
                                    val now = (pressed[0].position - pressed[1].position).getDistance()
                                    val key = selected
                                    if (pinch > 0f && key != null) room.resize(key, now / pinch)
                                    pinch = now
                                } else if (editing && moved) {
                                    val key = selected
                                    val r = ray(position)
                                    val hit = r?.let { (o, d) -> room.layout.raycast(o, d) }
                                    if (key != null && hit != null && r != null) room.place(key, hit, r.first, PageScene.WIDTH / PageScene.HEIGHT.toFloat())
                                }
                            }
                            PointerEventType.Release -> {
                                if (live) {
                                    val key = liveKey
                                    val placement = room.placed().firstOrNull { it.key == key }
                                    val r = ray(position)
                                    if (key != null && placement != null && r != null) {
                                        ArHome.hit(placement, r.first, r.second, margin = 3f)?.let { sceneFor(key)?.pointer(PointerEventType.Release, it.x, it.y) }
                                    }
                                    liveKey = null
                                } else if (!editing && !moved) {
                                    pageAt(position)?.let { popup = it.placement.key }
                                }
                                pinch = 0f
                            }
                        }
                    }
                }
            },
        ) {
            // Read every frame, so the canvas redraws as the camera and the pages change.
            if (tick < 0L) return@Canvas
            if (editing || dragging != null) {
                room.layout.placeable.forEach { s ->
                    val strong = hover?.let { it.center == s.center && it.normal == s.normal } == true
                    val color = when (s.kind) {
                        RoomLayout.Kind.WALL -> Color(0xFF5A9BFF)
                        RoomLayout.Kind.FLOOR -> Color(0xFF4CE38C)
                        else -> Color(0xFFFFB24C)
                    }.copy(alpha = if (strong) 0.36f else 0.13f)
                    val polygon = clip(surfaceCorners(s), camera?.first)
                    val points = polygon.mapNotNull(::toView)
                    if (points.size >= 3) drawPath(pathOf(points), color)
                }
            }
            room.placed().forEach { p ->
                val points = ArHome.corners(p).map(::toView)
                if (points.any { it == null }) return@forEach
                val quad = points.filterNotNull()
                val scene = sceneFor(p.key)
                val image = scene?.image(live = p.key == liveKey || p.key == popup)
                if (image != null && scene.failed == null) {
                    val h = homography(PageScene.WIDTH.toFloat(), PageScene.HEIGHT.toFloat(), quad)
                    if (h != null) {
                        drawIntoCanvas { c ->
                            c.nativeCanvas.save()
                            c.nativeCanvas.concat(org.jetbrains.skia.Matrix33(*h))
                            c.nativeCanvas.drawImage(image, 0f, 0f)
                            c.nativeCanvas.restore()
                        }
                    }
                } else {
                    drawPath(pathOf(quad), colors.card.copy(alpha = 0.85f))
                }
                if (p.key == popup) drawPath(pathOf(quad), Color.Black.copy(alpha = 0.55f))
                if (p.key == selected && editing) drawPath(pathOf(quad), colors.accent, style = Stroke(width = 4f))
            }
            // Real objects in front of pages: the camera picture again, only where the mask says so.
            val mask = occluders
            val picture = DesktopArScanner.picture
            val k = camera?.second
            val placement = fit()
            if (mask != null && picture != null && k != null && placement != null) {
                val (scale, ox, oy) = placement
                val dst = org.jetbrains.skia.Rect.makeXYWH(ox, oy, k.width * scale, k.height * scale)
                drawIntoCanvas { c ->
                    val canvas = c.nativeCanvas
                    canvas.saveLayer(dst, null)
                    canvas.drawImageRect(
                        picture, org.jetbrains.skia.Rect.makeWH(picture.width.toFloat(), picture.height.toFloat()), dst,
                        org.jetbrains.skia.SamplingMode.LINEAR, null, true,
                    )
                    canvas.drawImageRect(
                        mask, org.jetbrains.skia.Rect.makeWH(mask.width.toFloat(), mask.height.toFloat()), dst,
                        org.jetbrains.skia.SamplingMode.LINEAR,
                        org.jetbrains.skia.Paint().apply { blendMode = org.jetbrains.skia.BlendMode.DST_IN }, true,
                    )
                    canvas.restore()
                }
            }
        }

        // Top: back to the flat pages, what the scanner sees, Edit.
        Row(
            Modifier.fillMaxWidth().padding(12.dp).align(Alignment.TopCenter),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Pill("2D", onFlat)
            Text(
                notice ?: DesktopArScanner.status.value,
                color = Color.White, fontSize = 12.sp,
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            )
            if (!editing) Pill("Edit") { room.reopenSetup(); editing = true; notice = null }
        }

        // The setup grid: every page, and Done at its top right.
        if (editing) {
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .background(colors.surfaceRaised.copy(alpha = 0.93f), RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Drag a page onto a wall, the floor or the ceiling. Scroll or pinch to resize; right-click to take it down.",
                        color = colors.muted, fontSize = 12.sp, modifier = Modifier.weight(1f),
                    )
                    Text(
                        "Done", color = colors.accent, fontSize = 14.sp,
                        modifier = Modifier.clickableRow { room.finishSetup(); editing = false; selected = null; notice = null }.padding(8.dp),
                    )
                }
                Spacer(Modifier.height(6.dp))
                val placed = room.placedKeys()
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(slots, key = { it.first }) { (key, slotAndIndex) ->
                        var cardOrigin by remember { mutableStateOf(Offset.Zero) }
                        Column(
                            Modifier.width(96.dp)
                                .background(colors.surface, RoundedCornerShape(12.dp))
                                .onGloballyPositioned { cardOrigin = it.positionInRoot() }
                                .pointerInput(key) {
                                    detectDragGestures(
                                        onDragStart = { at -> dragging = key to (cardOrigin + at - boxOrigin) },
                                        onDrag = { change, amount ->
                                            change.consume()
                                            dragging = dragging?.let { (k, at) -> k to (at + amount) }
                                            hover = dragging?.second?.let { at -> ray(at)?.let { (o, d) -> room.layout.raycast(o, d)?.surface } }
                                        },
                                        onDragEnd = { dragging?.let { (k, at) -> place(k, at) }; dragging = null; hover = null },
                                        onDragCancel = { dragging = null; hover = null },
                                    )
                                }
                                .padding(10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Box(
                                Modifier.size(64.dp, 44.dp).background(colors.accent.copy(alpha = 0.3f), RoundedCornerShape(6.dp)),
                            )
                            Text(
                                slotLabel(slotAndIndex.first) + if (key in placed) " ✓" else "",
                                color = if (key in placed) colors.accent else colors.onSurface, fontSize = 11.sp, maxLines = 1,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                }
            }
        }

        // The page being dragged in.
        dragging?.let { (key, at) ->
            Box(
                Modifier.offset { IntOffset(at.x.roundToInt() - 48, at.y.roundToInt() - 32) }
                    .size(96.dp, 64.dp).background(colors.accent.copy(alpha = 0.55f), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) { Text(slots.firstOrNull { it.first == key }?.second?.first?.let(::slotLabel) ?: "", color = Color.White, fontSize = 11.sp) }
        }

        // A page lifted off the wall into a movable window.
        popup?.let { key ->
            val (slot, index) = slots.firstOrNull { it.first == key }?.second ?: return@let
            Column(
                Modifier.offset { popupOffset }
                    .size((viewSize.width * 0.7f / currentDensity()).dp, (viewSize.height * 0.78f / currentDensity()).dp)
                    .background(colors.surface, RoundedCornerShape(colors.cardRadius)),
            ) {
                Row(
                    Modifier.fillMaxWidth().background(colors.surfaceRaised, RoundedCornerShape(topStart = colors.cardRadius, topEnd = colors.cardRadius))
                        .pointerInput(Unit) { detectDragGestures { change, amount -> change.consume(); popupOffset += IntOffset(amount.x.roundToInt(), amount.y.roundToInt()) } }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(slotLabel(slot), color = colors.onSurface, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Text("✕", color = colors.muted, fontSize = 15.sp, modifier = Modifier.clickableRow { popup = null }.padding(horizontal = 6.dp))
                }
                Box(Modifier.weight(1f).fillMaxWidth()) { SlotPage(slot, index) }
            }
        }
    }
}

@Composable
private fun currentDensity(): Float = androidx.compose.ui.platform.LocalDensity.current.density

@Composable
private fun Pill(text: String, onClick: () -> Unit) {
    Text(
        text, color = Color.White, fontSize = 13.sp,
        modifier = Modifier.background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(16.dp))
            .clickableRow(onClick).padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

/**
 * One page, alive off screen: composed, rendered to an image now and then (often while it is being
 * used), and given pointer events in its own coordinates.
 */
private class PageScene(slot: SlotAssignment, index: Int) {
    companion object {
        const val WIDTH = 1000
        const val HEIGHT = 680
    }

    private val scene = ImageComposeScene(
        width = WIDTH, height = HEIGHT, density = Density(1f), coroutineContext = Dispatchers.Main,
    ) { PrismTheme { SlotPage(slot, index) } }

    private var last: org.jetbrains.skia.Image? = null
    private var renderedAt = 0L

    /** Why this page cannot be drawn off screen (one that hosts a native window, the browser), or null. */
    var failed: String? = null
        private set

    fun image(live: Boolean): org.jetbrains.skia.Image? {
        if (failed != null) return null
        val now = System.currentTimeMillis()
        if (last == null || now - renderedAt > if (live) 33 else 500) {
            renderedAt = now
            last = runCatching { scene.render(System.nanoTime()) }.onFailure { failed = it.message ?: "cannot be drawn here" }.getOrNull() ?: last
        }
        return last
    }

    @OptIn(ExperimentalComposeUiApi::class)
    fun pointer(type: PointerEventType, x: Float, y: Float) {
        runCatching {
            scene.sendPointerEvent(
                type, Offset(x * WIDTH, y * HEIGHT),
                buttons = if (type == PointerEventType.Release) PointerButtons() else PointerButtons(isPrimaryPressed = true),
                button = if (type == PointerEventType.Move) null else PointerButton.Primary,
            )
        }
        renderedAt = 0
    }

    @OptIn(ExperimentalComposeUiApi::class)
    fun scroll(x: Float, y: Float, delta: Offset) {
        runCatching { scene.sendPointerEvent(PointerEventType.Scroll, Offset(x * WIDTH, y * HEIGHT), scrollDelta = delta) }
        renderedAt = 0
    }

    fun close() = runCatching { scene.close() }
}

/**
 * The pixels (on the depth grid) where something real is more than 10 cm nearer than the page
 * behind it: opaque there, clear elsewhere. A page sits on its wall, so the wall itself, within
 * depth noise of the page, never hides it.
 */
private fun occlusionMask(pages: List<ArHome.Placement>): org.jetbrains.skia.Image? {
    if (pages.isEmpty()) return null
    val frame = DesktopArScanner.depth ?: return null
    val k = frame.intrinsics
    val bytes = ByteArray(k.width * k.height * 4)
    var any = false
    for (y in 0 until k.height) for (x in 0 until k.width) {
        val i = y * k.width + x
        val d = frame.depth[i]
        if (d <= 0f) continue
        val dir = frame.pose.rotate(k.ray(x.toFloat(), y.toFloat()))
        val length = dir.length()
        val hit = ArHome.pageAt(pages, frame.pose.translation, dir * (1f / length)) ?: continue
        if (d * length < hit.distance - 0.10f) {
            bytes[i * 4] = -1; bytes[i * 4 + 1] = -1; bytes[i * 4 + 2] = -1; bytes[i * 4 + 3] = -1
            any = true
        }
    }
    if (!any) return null
    return org.jetbrains.skia.Image.makeRaster(
        org.jetbrains.skia.ImageInfo(k.width, k.height, org.jetbrains.skia.ColorType.RGBA_8888, org.jetbrains.skia.ColorAlphaType.UNPREMUL),
        bytes, k.width * 4,
    )
}

private fun surfaceCorners(s: RoomLayout.Surface): List<Vec3> {
    val u = s.u * s.halfU
    val v = s.v * s.halfV
    return listOf(s.center - u + v, s.center + u + v, s.center + u - v, s.center - u - v)
}

/** Clips a world polygon to the part in front of the camera, so a floor that runs behind the webcam still draws. */
private fun clip(points: List<Vec3>, pose: com.prism.launcher.spatial.Pose?): List<Vec3> {
    if (pose == null) return emptyList()
    val near = 0.05f
    fun depth(p: Vec3) = -pose.inverseTransform(p).z - near
    val out = ArrayList<Vec3>()
    for (i in points.indices) {
        val a = points[i]
        val b = points[(i + 1) % points.size]
        val da = depth(a)
        val db = depth(b)
        if (da >= 0) out += a
        if ((da >= 0) != (db >= 0)) out += a + (b - a) * (da / (da - db))
    }
    return out
}

private fun pathOf(points: List<Offset>) = Path().apply {
    moveTo(points[0].x, points[0].y)
    points.drop(1).forEach { lineTo(it.x, it.y) }
    close()
}

/**
 * The projective transform taking the page rectangle (0,0)-(w,h) onto [quad] (top-left, top-right,
 * bottom-right, bottom-left), row-major 3x3, or null when the quad is degenerate.
 */
private fun homography(w: Float, h: Float, quad: List<Offset>): FloatArray? {
    val src = listOf(0f to 0f, w to 0f, w to h, 0f to h)
    val a = Array(8) { DoubleArray(9) }
    for (i in 0 until 4) {
        val (x, y) = src[i]
        val u = quad[i].x.toDouble()
        val v = quad[i].y.toDouble()
        a[i * 2] = doubleArrayOf(x.toDouble(), y.toDouble(), 1.0, 0.0, 0.0, 0.0, -u * x, -u * y, u)
        a[i * 2 + 1] = doubleArrayOf(0.0, 0.0, 0.0, x.toDouble(), y.toDouble(), 1.0, -v * x, -v * y, v)
    }
    // Gaussian elimination with partial pivoting.
    for (col in 0 until 8) {
        val pivot = (col until 8).maxByOrNull { kotlin.math.abs(a[it][col]) } ?: return null
        if (kotlin.math.abs(a[pivot][col]) < 1e-9) return null
        val t = a[col]; a[col] = a[pivot]; a[pivot] = t
        for (row in 0 until 8) {
            if (row == col) continue
            val f = a[row][col] / a[col][col]
            for (k in col..8) a[row][k] -= f * a[col][k]
        }
    }
    val hvals = DoubleArray(8) { a[it][8] / a[it][it] }
    if (hvals.any { !it.isFinite() }) return null
    return floatArrayOf(
        hvals[0].toFloat(), hvals[1].toFloat(), hvals[2].toFloat(),
        hvals[3].toFloat(), hvals[4].toFloat(), hvals[5].toFloat(),
        hvals[6].toFloat(), hvals[7].toFloat(), 1f,
    )
}
