package com.prism.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prism.core.MeshMembership
import com.prism.launcher.PrismSettings
import com.prism.launcher.mouse.MouseGlow
import com.prism.launcher.trusted.RemotePointer
import com.prism.launcher.trusted.TrustedDevices
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The Mouse page.
 *
 * A pointer for a paired machine: a trackpad for a mouse, and a flat mouse below it whose shell,
 * buttons and wheel carry the same animated RGB glow as the phone's page. The labels L and R are
 * gone because the shape already says which button is which -- two top buttons and a wheel between
 * them is the silhouette everybody recognises.
 *
 * NOT A REMOTE-DESKTOP VIEW. Nothing is rendered back -- this is a pointer, not a screen -- so a
 * LAN round trip is all a click costs and there is no frame pipeline to keep in sync.
 *
 * The page refuses to pretend. With no meshnet it says so and links to the pairing screen; with a
 * device that is paired but has gone quiet it says that instead of swallowing the click. The
 * receiving end is a desktop running Prism, which can inject with `java.awt.Robot`; a phone cannot
 * accept a cursor, and the note says so.
 *
 * ## The light comes from :core
 *
 * [MouseGlow] holds the palette, the flow program and the custom definitions, so this page and the
 * Android one draw from exactly the same palette instead of two implementations that drift.
 */
@Composable
fun MousePage() {
    val colors = LocalPrismColors.current

    var targets by remember { mutableStateOf<List<TrustedDevices.Trust>>(emptyList()) }
    var mesh by remember { mutableStateOf(false) }
    var revision by remember { mutableStateOf(0) }
    var touchpad by remember { mutableStateOf(PrismSettings.getMouseTouchpad()) }
    var askTransport by remember { mutableStateOf(true) }
    var transport by remember { mutableStateOf(PrismSettings.getPointerTransport()) }

    // A device can come and go while this page is open. Two seconds is slow enough to cost
    // nothing and fast enough that unplugging a laptop is visible before the user clicks.
    LaunchedEffect(revision) {
        while (true) {
            mesh = runCatching { MeshMembership.isOnMeshnet() }.getOrDefault(false)
            targets = runCatching { RemotePointer.targets() }.getOrDefault(emptyList())
            delay(2_000L)
        }
    }

    val saved = PrismSettings.getPointerTargetFingerprint()
    val target = targets.firstOrNull { it.fingerprint == saved }
        ?: targets.firstOrNull { it.online }
        ?: targets.firstOrNull()

    if (target != null && askTransport) {
        val device = target
        AlertDialog(
            onDismissRequest = { askTransport = false },
            title = { Text("Control " + device.name) },
            text = {
                Text(
                    "Choose how this page reaches that device. USB uses a cable and USB " +
                        "networking; Wi-Fi is the meshnet the pairing already runs on.",
                    fontSize = 13.sp,
                    color = colors.faint,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    PrismSettings.setPointerTransport(PrismSettings.POINTER_TRANSPORT_WIFI)
                    transport = PrismSettings.POINTER_TRANSPORT_WIFI
                    askTransport = false
                }) { Text("Control over Wi-Fi") }
            },
            dismissButton = {
                TextButton(onClick = {
                    PrismSettings.setPointerTransport(PrismSettings.POINTER_TRANSPORT_USB)
                    transport = PrismSettings.POINTER_TRANSPORT_USB
                    askTransport = false
                }) { Text("Control over USB") }
            },
        )
    }

    PageScaffold("Mouse", "A pointer for a trusted device, over " +
        (if (transport == PrismSettings.POINTER_TRANSPORT_USB) "USB" else "Wi-Fi")) {
        if (target == null) {
            EmptyPointer(mesh)
            return@PageScaffold
        }

        touchpad = PrismSettings.getMouseTouchpad()

        // Which machine. Chips rather than a dropdown because a user with three paired devices
        // wants to see which one is live at a glance.
        if (targets.size > 1) {
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                targets.forEach { device ->
                    val selected = device.fingerprint == target.fingerprint
                    Surface(
                        color = if (selected) colors.accent else colors.surfaceRaised,
                        shape = RoundedCornerShape(20.dp),
                    ) {
                        Text(
                            (if (device.online) "● " else "○ ") + device.name,
                            fontSize = 12.sp,
                            color = if (selected) Color.White else colors.muted,
                            modifier = Modifier
                                .clickableRow {
                                    PrismSettings.setPointerTargetFingerprint(device.fingerprint)
                                    revision++
                                }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }

        Text(
            if (target.online) {
                "Driving ${target.name} · ${target.platform.ifBlank { "device" }}"
            } else {
                "${target.name} is paired but not answering. Open Prism on it to drive it."
            },
            fontSize = 12.sp,
            color = if (target.online) colors.faint else Color(0xFFE0A070),
            modifier = Modifier.padding(top = 12.dp, bottom = 12.dp),
        )

        if (touchpad) {
            GlowTouchpad(target, Modifier.fillMaxWidth().weight(1f))
        } else {
            PointerPad(target, Modifier.fillMaxWidth().weight(1f))
            FlatMouse(target, Modifier.fillMaxWidth().weight(1.55f))
        }

        Text(
            "Controls a device running Prism. Nothing is sent while this page is closed.",
            fontSize = 11.sp,
            color = colors.faint,
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        )
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun EmptyPointer(mesh: Boolean) {
    val colors = LocalPrismColors.current
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Nothing to point at", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text(
            if (!mesh) {
                "The Mouse page drives another machine over the meshnet. Join or serve one, then " +
                    "pair the device you want to control."
            } else {
                "Pair the device you want to control. Both devices need Prism open; the target " +
                    "must be a desktop to receive the pointer."
            },
            fontSize = 13.sp,
            color = colors.faint,
            modifier = Modifier.width(460.dp),
        )
        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = { SettingsActions.navigate?.invoke(PageId.TRUSTED) }) {
            Text("Open trusted devices")
        }
    }
}

/**
 * The pad.
 *
 * Relative movement, like a laptop: the cursor starts in the middle and a drag moves it by the
 * fraction of the pad travelled. Absolute positioning would make a 1280-pixel pad the only part of
 * a 4K screen anyone could reach.
 */
@Composable
private fun PointerPad(target: TrustedDevices.Trust, modifier: Modifier = Modifier) {
    val colors = LocalPrismColors.current
    var cursor by remember { mutableStateOf(Offset(0.5f, 0.5f)) }

    Box(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(colors.surfaceRaised)
            .border(1.dp, Color(0x22FFFFFF), RoundedCornerShape(20.dp))
            .pointerInput(target.fingerprint) {
                detectDragGestures { change, drag ->
                    change.consume()
                    val next = Offset(
                        (cursor.x + drag.x / size.width).coerceIn(0f, 1f),
                        (cursor.y + drag.y / size.height).coerceIn(0f, 1f),
                    )
                    // A drag event is already capped at the frame rate, so this is at most one
                    // packet per frame -- the same rate a local mouse reports at.
                    cursor = next
                    RemotePointer.send(
                        target,
                        RemotePointer.Event(RemotePointer.Event.Kind.MOVE, next.x, next.y),
                    )
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text("Drag to move the pointer", fontSize = 12.sp, color = colors.faint)
    }
}

/**
 * The touchpad view: the whole page, wearing the palette.
 *
 * It is not a recoloured copy of [PointerPad]. The fill is sampled from the same [MouseGlow]
 * program the mouse body uses, three slow blooms drift under it, and the border is traced around
 * the rounded rectangle with the same travelling-colour code. "Color flow" therefore means the
 * same thing on both views, rather than one animating and the other frozen.
 */
@Composable
private fun GlowTouchpad(target: TrustedDevices.Trust, modifier: Modifier = Modifier) {
    val colors = LocalPrismColors.current
    val revision = ThemeRevision.value
    val style = remember(revision) { MouseGlow.selectedStyle() }
    val flow = remember(revision) { MouseGlow.selectedFlow() }

    var seconds by remember { mutableStateOf(0.0) }
    var cursor by remember { mutableStateOf(Offset(0.5f, 0.5f)) }

    LaunchedEffect(Unit) {
        val started = System.nanoTime()
        while (true) {
            withFrameMillis { }
            seconds = (System.nanoTime() - started) / 1_000_000_000.0
        }
    }

    Box(
        modifier
            .pointerInput(target.fingerprint) {
                detectDragGestures { change, drag ->
                    change.consume()
                    val next = Offset(
                        (cursor.x + drag.x / size.width).coerceIn(0f, 1f),
                        (cursor.y + drag.y / size.height).coerceIn(0f, 1f),
                    )
                    cursor = next
                    RemotePointer.send(
                        target,
                        RemotePointer.Event(RemotePointer.Event.Kind.MOVE, next.x, next.y),
                    )
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val radius = 28.dp.toPx()
            val corner = CornerRadius(radius, radius)
            val topLeft = Offset.Zero

            val stops = List(4) { index ->
                val sample = MouseGlow.sample(style, flow, (seconds * 0.06 + index * 0.25) % 1.0, seconds)
                Color(sample.color).copy(alpha = 0.30f)
            }
            drawRoundRect(
                brush = Brush.linearGradient(
                    colors = stops,
                    start = Offset.Zero,
                    end = Offset(size.width, size.height),
                ),
                topLeft = topLeft,
                size = size,
                cornerRadius = corner,
            )

            for (index in 0 until 3) {
                val drift = seconds * (0.13 + index * 0.05) + index * 2.1
                val center = Offset(
                    size.width * (0.5f + 0.28f * cos(drift).toFloat()),
                    size.height * (0.5f + 0.30f * sin((drift * 0.8)).toFloat()),
                )
                val bloom = MouseGlow.sample(style, flow, (seconds * 0.06 + index * 0.33) % 1.0, seconds)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(bloom.color).copy(alpha = 0.22f), Color.Transparent),
                        center = center,
                        radius = maxOf(size.width, size.height) * 0.42f,
                    ),
                    radius = maxOf(size.width, size.height) * 0.42f,
                    center = center,
                )
            }

            val outline = Path().apply {
                addRoundRect(
                    RoundRect(
                        left = 0f,
                        top = 0f,
                        right = size.width,
                        bottom = size.height,
                        cornerRadius = corner,
                    ),
                )
            }
            drawGlow(outline, 2.4.dp.toPx(), 2.2f, style, flow, seconds)

            val point = Offset(cursor.x * size.width, cursor.y * size.height)
            drawCircle(Color.White.copy(alpha = 0.18f), 13.dp.toPx(), point)
            drawCircle(Color.White.copy(alpha = 0.9f), 4.dp.toPx(), point)
            drawCircle(
                Color.White.copy(alpha = 0.65f),
                9.dp.toPx(),
                point,
                style = Stroke(width = 1.4.dp.toPx()),
            )
        }

        Text(
            "Drag to move · touchpad",
            fontSize = 12.sp,
            color = colors.onSurface.copy(alpha = 0.45f),
            modifier = Modifier.align(Alignment.Center),
        )
    }
}

// ── The flat mouse ────────────────────────────────────────────────────────

/** Which part of the mouse a point falls on. */
private enum class MouseRegion { NONE, LEFT, RIGHT, WHEEL }

/**
 * The geometry both the drawing and the hit test use.
 *
 * ONE description rather than two: the glow border, the button fills and the touch targets all
 * come from these numbers, so a border can never end up a few pixels away from the button that
 * actually responds.
 */
private class FlatMouseGeometry(width: Float, height: Float, private val density: Float) {
    private fun dp(value: Float): Float = value * density

    val pad = dp(6f)
    val centerX = width / 2f
    val top = pad
    val bottom = height - pad
    val bodyWidth = min(width * 0.64f, dp(330f)).coerceAtLeast(dp(180f))
    val left = centerX - bodyWidth / 2f
    val right = centerX + bodyWidth / 2f
    val dome = bodyWidth * 0.44f
    val gap = dp(7f)
    val buttonBottom = top + (bottom - top) * 0.42f
    // A share of the BUTTON band, not of the body width: on a wide window a width-derived
    // position squeezed the wheel into a squat pill, which read as a third button.
    val wheelTop = top + (buttonBottom - top) * 0.22f
    val wheelBottom = top + (buttonBottom - top) * 0.66f
    val wheelLeft = centerX - dp(21f)
    val wheelRight = centerX + dp(21f)
    val wheelRadius = dp(20f)
    val wheelRect = Rect(wheelLeft, wheelTop, wheelRight, wheelBottom)

    val shell = Path().apply {
        moveTo(left, top + dome)
        cubicTo(left, top, right, top, right, top + dome)
        lineTo(right, bottom - dome * 0.55f)
        cubicTo(right, bottom, left, bottom, left, bottom - dome * 0.55f)
        close()
    }

    val leftButton = Path().apply {
        moveTo(left, top + dome)
        cubicTo(left, top, centerX - gap / 2f, top, centerX - gap / 2f, top + dome)
        lineTo(centerX - gap / 2f, buttonBottom)
        lineTo(left, buttonBottom)
        close()
    }

    val rightButton = Path().apply {
        moveTo(right, top + dome)
        cubicTo(right, top, centerX + gap / 2f, top, centerX + gap / 2f, top + dome)
        lineTo(centerX + gap / 2f, buttonBottom)
        lineTo(right, buttonBottom)
        close()
    }

    val wheel = Path().apply {
        addRoundRect(RoundRect(wheelRect, CornerRadius(wheelRadius, wheelRadius)))
    }

    fun regionAt(point: Offset): MouseRegion {
        if (point.x in wheelLeft..wheelRight && point.y in wheelTop..wheelBottom) {
            return MouseRegion.WHEEL
        }
        if (point.y <= buttonBottom && point.y >= top && point.x in left..right) {
            return if (point.x < centerX) MouseRegion.LEFT else MouseRegion.RIGHT
        }
        return MouseRegion.NONE
    }
}

/**
 * The mouse itself.
 *
 * The shell sits on the page in the opposite value to the backdrop -- light on the dark desktop
 * theme, dark on a light one -- so a control surface is visible instead of blending into the
 * window. Four borders are sampled independently; a single outline around the whole mouse could
 * not follow the seams between the buttons, the wheel and the shell, and those seams are what make
 * the silhouette read as a mouse.
 */
@Composable
private fun FlatMouse(target: TrustedDevices.Trust, modifier: Modifier = Modifier) {
    val colors = LocalPrismColors.current

    // Re-read on the theme revision: a palette changed in Settings while this page sits open
    // should show up when the user comes back, without restarting Prism.
    val revision = ThemeRevision.value
    val style = remember(revision) { MouseGlow.selectedStyle() }
    val flow = remember(revision) { MouseGlow.selectedFlow() }

    val darkBackdrop = colors.background.luminance() < 0.5f
    val shellColor = if (darkBackdrop) Color(0xFFE9E9EE) else Color(0xFF1C1C1E)
    val buttonColor = if (darkBackdrop) Color(0xFFF7F7FA) else Color(0xFF2C2C2E)
    val wheelColor = if (darkBackdrop) Color(0xFFD7D7DE) else Color(0xFF3A3A3C)
    val ridgeColor = if (darkBackdrop) Color(0x66000000) else Color(0x66FFFFFF)

    var seconds by remember { mutableStateOf(0.0) }
    var pressed by remember { mutableStateOf<MouseRegion?>(null) }
    var wheelOffset by remember { mutableStateOf(0f) }

    // One frame driver for the whole control: the glow and the wheel's ridges move on the same
    // clock, so the border never appears to advance in steps while the ridges glide.
    LaunchedEffect(Unit) {
        val started = System.nanoTime()
        while (true) {
            withFrameMillis { }
            seconds = (System.nanoTime() - started) / 1_000_000_000.0
        }
    }

    Canvas(
        modifier
            .padding(top = 10.dp)
            .pointerInput(target.fingerprint) {
                val notch = 26f * density
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val geometry = FlatMouseGeometry(size.width.toFloat(), size.height.toFloat(), density)
                    val region = geometry.regionAt(down.position)
                    if (region == MouseRegion.NONE) return@awaitEachGesture
                    pressed = region
                    var lastY = down.position.y
                    var carried = 0f
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            if (region != MouseRegion.WHEEL &&
                                geometry.regionAt(change.position) == region
                            ) {
                                when (region) {
                                    MouseRegion.LEFT -> RemotePointer.send(
                                        target,
                                        RemotePointer.Event(RemotePointer.Event.Kind.CLICK),
                                    )
                                    MouseRegion.RIGHT -> RemotePointer.send(
                                        target,
                                        RemotePointer.Event(
                                            RemotePointer.Event.Kind.CLICK,
                                            button = RemotePointer.Button.RIGHT,
                                        ),
                                    )
                                    else -> Unit
                                }
                            }
                            break
                        }
                        if (region == MouseRegion.WHEEL) {
                            val delta = lastY - change.position.y
                            lastY = change.position.y
                            if (delta != 0f) {
                                carried += delta
                                wheelOffset -= delta
                                // Fractions of a notch, not whole ones: the receiver carries the
                                // remainder, so a slow roll scrolls in proportion to the finger
                                // instead of jumping one detent at a time.
                                RemotePointer.send(
                                    target,
                                    RemotePointer.Event(
                                        RemotePointer.Event.Kind.SCROLL,
                                        amount = delta / notch,
                                    ),
                                )
                                if (abs(carried) >= notch) carried %= notch
                            }
                        }
                        change.consume()
                    }
                    pressed = null
                }
            },
    ) {
        val density = this.density
        val geometry = FlatMouseGeometry(size.width, size.height, density)
        fun dp(value: Float): Float = value * density

        drawPath(geometry.shell, shellColor)
        drawPath(
            geometry.leftButton,
            if (pressed == MouseRegion.LEFT) {
                if (darkBackdrop) Color(0xFFE4E4EA) else Color(0xFF3A3A3D)
            } else buttonColor,
        )
        drawPath(
            geometry.rightButton,
            if (pressed == MouseRegion.RIGHT) {
                if (darkBackdrop) Color(0xFFE4E4EA) else Color(0xFF3A3A3D)
            } else buttonColor,
        )
        drawPath(geometry.wheel, wheelColor)
        drawWheelRidges(geometry, ridgeColor, wheelOffset, density)

        drawGlow(geometry.shell, dp(2.2f), 0.8f, style, flow, seconds)
        drawGlow(geometry.leftButton, dp(2.4f), 1f, style, flow, seconds)
        drawGlow(geometry.rightButton, dp(2.4f), 1f, style, flow, seconds)
        // The button borders run down the centre gap, which the wheel covers. Repainting the
        // wheel after those borders keeps a bright seam from being drawn across its face.
        drawPath(geometry.wheel, wheelColor)
        drawWheelRidges(geometry, ridgeColor, wheelOffset, density)
        drawGlow(geometry.wheel, dp(2.2f), 0.55f, style, flow, seconds)
    }
}

/**
 * The wheel's ridges, clipped to its capsule.
 *
 * [offset] is in pixels because that is what the drawing needs; the caller keeps it in its own
 * units and passes the accumulated drag. The modulus keeps it bounded while a finger keeps
 * dragging, and clipping means a ridge can never be drawn outside the wheel.
 */
private fun DrawScope.drawWheelRidges(
    geometry: FlatMouseGeometry,
    color: Color,
    offset: Float,
    density: Float,
) {
    fun dp(value: Float): Float = value * density
    val ridgeGap = dp(9f)
    val shift = offset % ridgeGap
    clipPath(geometry.wheel) {
        var y = geometry.wheelTop - ridgeGap * 2f + shift
        while (y < geometry.wheelBottom + ridgeGap) {
            if (y > geometry.wheelTop && y < geometry.wheelBottom) {
                drawLine(
                    color,
                    Offset(geometry.wheelLeft + (geometry.wheelRight - geometry.wheelLeft) * 0.27f, y),
                    Offset(geometry.wheelRight - (geometry.wheelRight - geometry.wheelLeft) * 0.27f, y),
                    strokeWidth = dp(2f),
                    cap = StrokeCap.Round,
                )
            }
            y += ridgeGap
        }
    }
}

/**
 * One animated border.
 *
 * Drawn as short segments rather than a gradient stroke because the colour has to travel around
 * the perimeter with the flow: a gradient stroke would run left-to-right across the whole control
 * and ignore its shape. [bubbleScale] exists because a bubble that reads well on the shell is too
 * large inside the 42dp wheel, and the radius stays clamped to the stroke so it cannot leak into
 * the shell.
 */
private fun DrawScope.drawGlow(
    path: Path,
    stroke: Float,
    bubbleScale: Float,
    style: MouseGlow.Style,
    flow: MouseGlow.Flow,
    seconds: Double,
) {
    fun dp(value: Float): Float = value * density
    val measure = PathMeasure()
    measure.setPath(path, false)
    val length = measure.length
    if (length <= 0f) return

    val steps = (length / dp(7f)).toInt().coerceIn(28, 140)
    for (index in 0 until steps) {
        val u = index.toDouble() / steps
        val next = (index + 0.9) / steps
        val sample = MouseGlow.sample(style, flow, u, seconds)
        val color = Color(sample.color)
        val start = measure.getPosition((u * length).toFloat())
        val end = measure.getPosition((next * length).toFloat())
        drawLine(
            color.copy(alpha = color.alpha * 0.18f),
            start,
            end,
            strokeWidth = stroke * 3.4f,
            cap = StrokeCap.Round,
        )
        drawLine(color, start, end, strokeWidth = stroke, cap = StrokeCap.Round)
    }

    val sample = MouseGlow.sample(style, flow, 0.25, seconds)
    val count = floor(sample.bubbles * 7 * bubbleScale).toInt()
    if (count <= 0) return
    val radius = (dp(2.2f) * sample.bubbleSize * bubbleScale).toFloat()
        .coerceAtLeast(dp(1.1f))
        .coerceAtMost(stroke * 0.82f)
    val maxDrift = (stroke * 0.5f - radius).coerceAtLeast(0f)

    for (index in 0 until count) {
        val seed = (index + 1) * 0.173
        val speed = 0.035 + sample.bubbleSpeed * 0.05
        val u = (seed + seconds * speed * (0.6 + (index % 3) * 0.16)) % 1.0
        val current = MouseGlow.sample(style, flow, u, seconds)
        val color = Color(current.color)
        val x = measure.getPosition((u * length).toFloat())
        val y = measure.getPosition(((u + 0.002) % 1.0 * length).toFloat())
        val tx = y.x - x.x
        val ty = y.y - x.y
        val tangent = max(0.0001f, sqrt(tx * tx + ty * ty))
        val nx = -ty / tangent
        val ny = tx / tangent
        val drift = if (maxDrift <= 0f) 0f else
            (sin(seconds * (0.8 + index * 0.07) * (sample.bubbleSpeed + 0.2) + index) * maxDrift)
                .toFloat()
        val center = Offset(x.x + nx * drift, x.y + ny * drift)
        drawCircle(color.copy(alpha = 0.22f), radius * 1.8f, center)
        drawCircle(color, radius, center)
    }
}
