package com.prism.desktop.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prism.core.AppEntry
import com.prism.core.defaultAppCatalog
import com.prism.launcher.AppSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.atan2
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The Kinetic Halo: a circular, physics-driven app ring.
 *
 * A direct port of `KineticHaloPageView`'s model. Press and hold to summon the ring, drag in a
 * circle to spin it, release to let it coast to a stop. The physics are the interesting part and
 * are reproduced rather than approximated:
 *
 *   ANGULAR VELOCITY carries over from the drag, so a flick keeps spinning after release.
 *   FRICTION decays it each frame until it falls below a threshold and stops cleanly, rather
 *   than asymptotically approaching zero and never settling.
 *   ANGLE UNWRAPPING is what makes the drag feel right. Raw atan2 jumps from +180 to -180 as the
 *   pointer crosses the left axis; taking that difference literally spins the ring most of a
 *   turn backwards on every crossing. The delta is normalized into (-180, 180] before it is used.
 *
 * WHAT CHANGED, and why. Android buzzes the vibrator each time an icon passes the top; a desktop
 * has no haptics, so that beat is shown instead -- the icon at the top brightens and scales up.
 * The feedback survives, in the modality the platform actually has.
 */
@Composable
fun KineticHaloPage() {
    val scope = rememberCoroutineScope()
    val catalog = remember { defaultAppCatalog() }
    val icons = LocalIconCache.current
    val colors = LocalPrismColors.current

    var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    var summoned by remember { mutableStateOf(false) }
    var center by remember { mutableStateOf(Offset.Zero) }
    var rotation by remember { mutableStateOf(0f) }
    var velocity by remember { mutableStateOf(0f) }
    var lastAngle by remember { mutableStateOf<Float?>(null) }
    var size by remember { mutableStateOf(Offset.Zero) }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { catalog.list() }.take(MAX_ICONS)
    }

    val summonProgress by animateFloatAsState(
        targetValue = if (summoned) 1f else 0f,
        label = "summon",
    )

    // The coast. Runs only while there is momentum, so an idle page costs nothing.
    LaunchedEffect(velocity != 0f) {
        while (abs(velocity) > VELOCITY_FLOOR) {
            rotation += velocity
            velocity *= FRICTION
            delay(16)
        }
        // Snapped to zero rather than left as a vanishing fraction, so the loop actually exits.
        velocity = 0f
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .onGloballyPositioned {
                size = Offset(it.size.width.toFloat(), it.size.height.toFloat())
                if (center == Offset.Zero) center = Offset(size.x / 2f, size.y / 2f)
            }
            .pointerInput(apps.size) {
                detectDragGestures(
                    onDragStart = { pos ->
                        // The ring summons where you press, as on the phone.
                        center = pos
                        summoned = true
                        velocity = 0f
                        lastAngle = null
                    },
                    onDrag = { change, _ ->
                        val angle = angleOf(change.position - center)
                        lastAngle?.let { previous ->
                            val delta = normalizeDegrees(angle - previous)
                            rotation += delta
                            // Blended rather than replaced, so one jittery sample cannot fling
                            // the ring across the screen.
                            velocity = velocity * 0.6f + delta * 0.4f
                        }
                        lastAngle = angle
                    },
                    onDragEnd = {
                        summoned = false
                        lastAngle = null
                    },
                    onDragCancel = {
                        summoned = false
                        velocity = 0f
                        lastAngle = null
                    },
                )
            }
    ) {
        if (apps.isEmpty()) {
            Text(
                "Scanning applications…",
                fontSize = 13.sp,
                color = colors.faint,
                modifier = Modifier.align(Alignment.Center),
            )
            return@Box
        }

        val radius = minOf(size.x, size.y) * 0.34f * summonProgress

        // The halo itself.
        if (summonProgress > 0.01f) {
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color.Transparent, colors.accent.copy(alpha = 0.22f * summonProgress)),
                        center = center,
                        radius = radius * 1.25f,
                    ),
                    radius = radius * 1.25f,
                    center = center,
                )
                drawCircle(
                    color = colors.accent.copy(alpha = 0.45f * summonProgress),
                    radius = radius,
                    center = center,
                    style = Stroke(width = 3f),
                )
            }
        }

        apps.forEachIndexed { index, app ->
            IconLoader(app, icons)

            val step = 360f / apps.size
            val angleDeg = rotation + index * step
            val rad = Math.toRadians(angleDeg.toDouble())
            val x = center.x + radius * cos(rad).toFloat()
            val y = center.y + radius * sin(rad).toFloat()

            // How close this icon is to the top of the ring, which is the selection point. On
            // Android this is where the vibrator fires; here it drives brightness and scale.
            val fromTop = abs(normalizeDegrees(angleDeg + 90f)) / 180f
            val focus = (1f - fromTop).coerceIn(0f, 1f)
            val scale = 0.72f + 0.5f * focus * focus

            if (summonProgress > 0.02f) {
                Column(
                    Modifier
                        .offset { IntOffset((x - 34).roundToInt(), (y - 34).roundToInt()) }
                        .width(68.dp)
                        .clickableRow {
                            scope.launch(Dispatchers.IO) {
                                catalog.launch(app)
                                AppSync.recordLaunch(app.id)
                            }
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.size((34 * scale).dp), contentAlignment = Alignment.Center) {
                        val bitmap: ImageBitmap? = icons[app.id]
                        if (bitmap != null) {
                            androidx.compose.foundation.Image(
                                bitmap, null,
                                Modifier.size((32 * scale).dp).alphaOf(summonProgress),
                            )
                        } else {
                            Icon(
                                Icons.Filled.Android, null,
                                tint = colors.muted.copy(alpha = summonProgress),
                                modifier = Modifier.size((28 * scale).dp),
                            )
                        }
                    }
                    if (focus > 0.72f) {
                        Text(
                            app.label,
                            fontSize = 9.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            color = Color.White.copy(alpha = summonProgress),
                        )
                    }
                }
            }
        }

        if (summonProgress < 0.05f) {
            Column(
                Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Kinetic Halo", fontSize = 17.sp, color = colors.muted)
                Text(
                    "Press and drag in a circle to summon and spin the ring.",
                    fontSize = 12.sp, color = colors.faint,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

private const val MAX_ICONS = 28
private const val FRICTION = 0.94f
private const val VELOCITY_FLOOR = 0.05f

private fun angleOf(v: Offset): Float =
    Math.toDegrees(atan2(v.y.toDouble(), v.x.toDouble())).toFloat()

/**
 * Brings a degree difference into (-180, 180].
 *
 * Without this, a pointer crossing the left axis produces a delta near 360 and the ring lurches
 * most of a full turn in the wrong direction -- the classic rotary-input bug.
 */
private fun normalizeDegrees(deg: Float): Float {
    var d = deg % 360f
    if (d > 180f) d -= 360f
    if (d <= -180f) d += 360f
    return d
}

private fun Modifier.alphaOf(a: Float): Modifier = this.alpha(a.coerceIn(0f, 1f))
