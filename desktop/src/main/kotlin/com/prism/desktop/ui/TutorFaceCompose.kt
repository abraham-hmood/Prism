package com.prism.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.prism.launcher.language.Gesture
import com.prism.launcher.language.Hair
import com.prism.launcher.language.PortraitSpec
import com.prism.launcher.language.TutorPalette

/**
 * The tutor portraits, drawn on a Compose canvas. PHASE 94.
 *
 * ## This is a port of the drawing, not of the cast
 *
 * The roster is [PortraitSpec] values in `:core` and is shared -- sixteen tutors, each about thirty
 * bytes. What could not be shared is the rendering: the Android version is an
 * `android.graphics.drawable.Drawable` driving `Canvas`, `Paint` and `Path`. Compose's canvas has the
 * same primitives under different names, so this is the same composition re-expressed rather than a
 * different illustration -- Mei is the same Mei on both.
 *
 * ## Fractions of the shorter side, for the same reason as before
 *
 * Every measurement below is a fraction of `min(width, height)`, so one spec draws identically in a
 * 40dp list row and a 320dp hero. The alternative -- a set of sizes per use -- is how a face ends up
 * with eyes in a slightly different place at each scale.
 *
 * ## Back to front, and that ordering is load-bearing
 *
 * Backdrop, shoulders, neck, ears, head, hair BACK, features, hair FRONT, accessories. A fringe has to
 * sit over the forehead while the same hairstyle's volume sits behind the ears, and that is one
 * hairstyle only if it is drawn in two passes. Reordering this does not produce a slightly different
 * face; it produces hair behind the eyes.
 */
@Composable
fun TutorFace(
    spec: PortraitSpec,
    size: Dp,
    /** Circular crop for grid tiles; false gives the full shoulders-up frame. */
    circular: Boolean = true,
    /** Off when the portrait sits on an already-tinted surface. */
    withBackdrop: Boolean = true,
) {
    Canvas(Modifier.size(size)) {
        drawTutorFace(spec, circular, withBackdrop)
    }
}

private fun argb(value: Int) = Color(value)

private fun DrawScope.drawTutorFace(spec: PortraitSpec, circular: Boolean, withBackdrop: Boolean) {
    val w = this.size.width
    val h = this.size.height
    if (w <= 0f || h <= 0f) return
    val s = minOf(w, h)
    val cx = w / 2f
    val headCy = h * 0.44f

    val clip = Path().apply {
        if (circular) {
            addOval(Rect(Offset(cx - s / 2f, h / 2f - s / 2f), Size(s, s)))
        } else {
            addRect(Rect(0f, 0f, w, h))
        }
    }

    clipPath(clip) {
        // Zoom on the head when cropped to a circle. The full frame is composed for a rectangle, so
        // a plain circular crop leaves a ring of empty backdrop above the hair and reads as a small
        // face in a big disc.
        val zoom = if (circular) 1.22f else 1f
        scale(zoom, zoom, Offset(cx, headCy)) {

            if (withBackdrop) {
                drawRect(
                    brush = Brush.verticalGradient(
                        listOf(
                            argb(TutorPalette.lighten(spec.backdrop, 0.55f)),
                            argb(spec.backdrop),
                        ),
                        startY = 0f,
                        endY = h,
                    ),
                    size = Size(w, h),
                )
            }

            val headW = s * 0.44f
            val headH = s * 0.54f
            val skin = argb(spec.skin)
            val shade = argb(TutorPalette.darken(spec.skin, 0.16f))
            val hairBack = argb(TutorPalette.darken(spec.hairColor, 0.18f))
            val hair = argb(spec.hairColor)

            // ── Shoulders, running off the bottom edge, which is what makes it a portrait ──
            val shoulderTop = headCy + headH * 0.66f
            drawPath(
                path = Path().apply {
                    moveTo(cx - s * 0.46f, h)
                    cubicTo(
                        cx - s * 0.42f, shoulderTop + s * 0.02f,
                        cx - s * 0.16f, shoulderTop - s * 0.03f,
                        cx, shoulderTop - s * 0.03f,
                    )
                    cubicTo(
                        cx + s * 0.16f, shoulderTop - s * 0.03f,
                        cx + s * 0.42f, shoulderTop + s * 0.02f,
                        cx + s * 0.46f, h,
                    )
                    close()
                },
                color = argb(spec.clothing),
            )
            // A collar, because a flat block of colour reads as a wall rather than a garment.
            drawPath(
                path = Path().apply {
                    moveTo(cx - s * 0.10f, shoulderTop - s * 0.02f)
                    quadraticBezierTo(cx, shoulderTop + s * 0.09f, cx + s * 0.10f, shoulderTop - s * 0.02f)
                },
                color = argb(TutorPalette.darken(spec.clothing, 0.22f)),
                style = Stroke(width = s * 0.018f, cap = StrokeCap.Round),
            )

            // ── Neck ──
            drawRoundRectCompat(
                left = cx - headW * 0.26f,
                top = headCy + headH * 0.30f,
                right = cx + headW * 0.26f,
                bottom = shoulderTop + s * 0.02f,
                radius = headW * 0.20f,
                color = shade,
            )

            // ── Ears, before the head so the head's edge overlaps them ──
            listOf(-1f, 1f).forEach { side ->
                drawOvalCompat(
                    cx + side * headW * 0.50f - headW * 0.09f,
                    headCy - headH * 0.04f,
                    headW * 0.18f,
                    headH * 0.20f,
                    shade,
                )
            }
            if (spec.earrings) {
                listOf(-1f, 1f).forEach { side ->
                    drawCircle(
                        color = argb(0xFFE8C46A.toInt()),
                        radius = s * 0.016f,
                        center = Offset(cx + side * headW * 0.50f, headCy + headH * 0.14f),
                    )
                }
            }

            // ── Head ──
            drawOvalCompat(cx - headW / 2f, headCy - headH / 2f, headW, headH, skin)
            // Jaw shading along the lower third, so the face is not a flat disc.
            drawOvalCompat(
                cx - headW * 0.44f,
                headCy + headH * 0.06f,
                headW * 0.88f,
                headH * 0.40f,
                argb(TutorPalette.withAlpha(TutorPalette.darken(spec.skin, 0.10f), 90)),
            )

            // ── Hair, back pass ──
            drawHairBack(spec, cx, headCy, headW, headH, s, hairBack)

            // ── Features ──
            val eyeY = headCy - headH * 0.05f
            val eyeDx = headW * 0.20f
            val eyeR = headW * 0.062f

            listOf(-1f, 1f).forEach { side ->
                val ex = cx + side * eyeDx
                if (spec.eyesClosed) {
                    // A closed eye is a curve, not a line: a straight segment reads as a scar.
                    drawPath(
                        path = Path().apply {
                            moveTo(ex - eyeR, eyeY)
                            quadraticBezierTo(ex, eyeY + eyeR * 0.9f, ex + eyeR, eyeY)
                        },
                        color = argb(TutorPalette.darken(spec.hairColor, 0.30f)),
                        style = Stroke(width = s * 0.012f, cap = StrokeCap.Round),
                    )
                } else {
                    drawOvalCompat(ex - eyeR, eyeY - eyeR * 0.82f, eyeR * 2f, eyeR * 1.64f, Color.White)
                    drawCircle(
                        color = argb(TutorPalette.darken(spec.hairColor, 0.45f)),
                        radius = eyeR * 0.56f,
                        center = Offset(ex, eyeY),
                    )
                    drawCircle(
                        color = Color.White.copy(alpha = 0.85f),
                        radius = eyeR * 0.18f,
                        center = Offset(ex - eyeR * 0.20f, eyeY - eyeR * 0.24f),
                    )
                }
                // Brow
                drawPath(
                    path = Path().apply {
                        moveTo(ex - eyeR * 1.15f, eyeY - eyeR * 1.7f)
                        quadraticBezierTo(
                            ex, eyeY - eyeR * 2.25f,
                            ex + eyeR * 1.15f, eyeY - eyeR * 1.7f,
                        )
                    },
                    color = argb(TutorPalette.darken(spec.hairColor, 0.20f)),
                    style = Stroke(width = s * 0.013f, cap = StrokeCap.Round),
                )
            }

            // Nose: one short stroke. A modelled nose at 40dp is a smudge.
            drawPath(
                path = Path().apply {
                    moveTo(cx, eyeY + headH * 0.06f)
                    quadraticBezierTo(
                        cx + headW * 0.045f, eyeY + headH * 0.13f,
                        cx, eyeY + headH * 0.14f,
                    )
                },
                color = argb(TutorPalette.darken(spec.skin, 0.22f)),
                style = Stroke(width = s * 0.011f, cap = StrokeCap.Round),
            )

            // Mouth: a smile, because every tutor in the roster is meant to look approachable.
            drawPath(
                path = Path().apply {
                    val my = headCy + headH * 0.22f
                    moveTo(cx - headW * 0.13f, my)
                    quadraticBezierTo(cx, my + headH * 0.075f, cx + headW * 0.13f, my)
                },
                color = argb(0xFFB4626A.toInt()),
                style = Stroke(width = s * 0.016f, cap = StrokeCap.Round),
            )

            if (spec.freckles) {
                val freckle = argb(TutorPalette.withAlpha(TutorPalette.darken(spec.skin, 0.28f), 150))
                listOf(-1f, 1f).forEach { side ->
                    for (i in 0 until 3) {
                        drawCircle(
                            color = freckle,
                            radius = s * 0.006f,
                            center = Offset(
                                cx + side * (headW * 0.26f + i * headW * 0.055f),
                                eyeY + headH * 0.10f + (i % 2) * headH * 0.025f,
                            ),
                        )
                    }
                }
            }

            // ── Hair, front pass ──
            drawHairFront(spec, cx, headCy, headW, headH, s, hair)

            if (spec.glasses) {
                val lens = headW * 0.15f
                val frame = argb(0xFF2A2A31.toInt())
                listOf(-1f, 1f).forEach { side ->
                    drawRoundRectStroke(
                        left = cx + side * eyeDx - lens,
                        top = eyeY - lens * 0.78f,
                        right = cx + side * eyeDx + lens,
                        bottom = eyeY + lens * 0.78f,
                        radius = lens * 0.45f,
                        color = frame,
                        width = s * 0.013f,
                    )
                }
                drawLine(
                    color = frame,
                    start = Offset(cx - eyeDx + lens, eyeY),
                    end = Offset(cx + eyeDx - lens, eyeY),
                    strokeWidth = s * 0.011f,
                )
                // Arms, out to the ears. Without them the lenses float.
                listOf(-1f, 1f).forEach { side ->
                    drawLine(
                        color = frame,
                        start = Offset(cx + side * (eyeDx + lens), eyeY - lens * 0.2f),
                        end = Offset(cx + side * headW * 0.50f, headCy + headH * 0.01f),
                        strokeWidth = s * 0.011f,
                    )
                }
            }

            if (spec.gesture == Gesture.WAVE) {
                // A raised hand at the right shoulder. Drawn last so it sits over the garment.
                val hx = cx + s * 0.30f
                val hy = shoulderTop + s * 0.06f
                drawCircle(color = skin, radius = s * 0.055f, center = Offset(hx, hy))
                for (i in 0 until 4) {
                    drawRoundRectCompat(
                        left = hx - s * 0.045f + i * s * 0.026f,
                        top = hy - s * 0.105f,
                        right = hx - s * 0.045f + i * s * 0.026f + s * 0.018f,
                        bottom = hy - s * 0.02f,
                        radius = s * 0.009f,
                        color = skin,
                    )
                }
            }
        }
    }
}

// ── Hair ────────────────────────────────────────────────────────────────────

/**
 * The volume that sits BEHIND the head: length, bulk, a bun's mass.
 *
 * Split from the front pass rather than drawn as one shape because a hairstyle is two things at once
 * -- what falls behind the ears and what falls over the forehead -- and one path cannot be both.
 */
private fun DrawScope.drawHairBack(
    spec: PortraitSpec,
    cx: Float,
    headCy: Float,
    headW: Float,
    headH: Float,
    s: Float,
    color: Color,
) {
    when (spec.hair) {
        Hair.LONG_WAVY, Hair.LONG_STRAIGHT -> {
            val length = if (spec.hair == Hair.LONG_WAVY) headH * 0.95f else headH * 0.85f
            drawPath(
                path = Path().apply {
                    moveTo(cx - headW * 0.56f, headCy - headH * 0.10f)
                    cubicTo(
                        cx - headW * 0.66f, headCy + length * 0.45f,
                        cx - headW * 0.40f, headCy + length * 0.85f,
                        cx - headW * 0.30f, headCy + length,
                    )
                    lineTo(cx + headW * 0.30f, headCy + length)
                    cubicTo(
                        cx + headW * 0.40f, headCy + length * 0.85f,
                        cx + headW * 0.66f, headCy + length * 0.45f,
                        cx + headW * 0.56f, headCy - headH * 0.10f,
                    )
                    close()
                },
                color = color,
            )
        }
        Hair.BOB -> drawOvalCompat(
            cx - headW * 0.58f, headCy - headH * 0.56f, headW * 1.16f, headH * 1.10f, color,
        )
        Hair.PONYTAIL -> {
            drawOvalCompat(cx - headW * 0.54f, headCy - headH * 0.54f, headW * 1.08f, headH * 0.80f, color)
            drawOvalCompat(
                cx + headW * 0.36f, headCy - headH * 0.10f, headW * 0.30f, headH * 0.70f, color,
            )
        }
        Hair.BUN -> {
            drawOvalCompat(cx - headW * 0.54f, headCy - headH * 0.54f, headW * 1.08f, headH * 0.78f, color)
            drawCircle(color = color, radius = headW * 0.22f, center = Offset(cx, headCy - headH * 0.62f))
        }
        Hair.CURLS -> {
            // Seven overlapping discs rather than a noise function: a fixed arrangement is
            // reproducible, and the roster has to look the same in every build.
            for (i in 0 until 7) {
                val t = i / 6f
                drawCircle(
                    color = color,
                    radius = headW * 0.20f,
                    center = Offset(
                        cx - headW * 0.52f + t * headW * 1.04f,
                        headCy - headH * 0.34f - kotlin.math.sin(t * Math.PI).toFloat() * headH * 0.12f,
                    ),
                )
            }
        }
        Hair.SHAGGY -> drawOvalCompat(
            cx - headW * 0.56f, headCy - headH * 0.56f, headW * 1.12f, headH * 0.94f, color,
        )
        Hair.SHORT_CROP, Hair.UNDERCUT, Hair.FADE -> drawOvalCompat(
            cx - headW * 0.52f, headCy - headH * 0.52f, headW * 1.04f, headH * 0.66f, color,
        )
    }
}

/** What falls over the forehead. Drawn after the eyes so a fringe can cover a brow. */
private fun DrawScope.drawHairFront(
    spec: PortraitSpec,
    cx: Float,
    headCy: Float,
    headW: Float,
    headH: Float,
    s: Float,
    color: Color,
) {
    val crownTop = headCy - headH * 0.52f
    // Clipped to the head, so a fringe cannot spill onto the backdrop.
    val head = Path().apply {
        addOval(Rect(Offset(cx - headW / 2f, headCy - headH / 2f), Size(headW, headH)))
    }

    clipPath(head) {
        when (spec.hair) {
            Hair.LONG_STRAIGHT, Hair.BOB, Hair.PONYTAIL, Hair.BUN -> drawPath(
                // A centre-parted sweep.
                path = Path().apply {
                    moveTo(cx - headW * 0.52f, crownTop + headH * 0.30f)
                    quadraticBezierTo(cx - headW * 0.10f, crownTop - headH * 0.04f, cx, crownTop + headH * 0.12f)
                    quadraticBezierTo(cx + headW * 0.10f, crownTop - headH * 0.04f, cx + headW * 0.52f, crownTop + headH * 0.30f)
                    lineTo(cx + headW * 0.52f, crownTop)
                    lineTo(cx - headW * 0.52f, crownTop)
                    close()
                },
                color = color,
            )
            Hair.LONG_WAVY, Hair.SHAGGY -> drawPath(
                // A full fringe with a notch, which is what reads as "wavy" at thumbnail size.
                path = Path().apply {
                    moveTo(cx - headW * 0.52f, crownTop + headH * 0.34f)
                    quadraticBezierTo(cx - headW * 0.24f, crownTop + headH * 0.16f, cx - headW * 0.06f, crownTop + headH * 0.30f)
                    quadraticBezierTo(cx + headW * 0.14f, crownTop + headH * 0.12f, cx + headW * 0.52f, crownTop + headH * 0.32f)
                    lineTo(cx + headW * 0.52f, crownTop)
                    lineTo(cx - headW * 0.52f, crownTop)
                    close()
                },
                color = color,
            )
            Hair.CURLS -> {
                for (i in 0 until 5) {
                    val t = i / 4f
                    drawCircle(
                        color = color,
                        radius = headW * 0.15f,
                        center = Offset(
                            cx - headW * 0.40f + t * headW * 0.80f,
                            crownTop + headH * 0.18f,
                        ),
                    )
                }
            }
            Hair.SHORT_CROP -> drawPath(
                path = Path().apply {
                    moveTo(cx - headW * 0.52f, crownTop + headH * 0.26f)
                    quadraticBezierTo(cx, crownTop + headH * 0.02f, cx + headW * 0.52f, crownTop + headH * 0.26f)
                    lineTo(cx + headW * 0.52f, crownTop)
                    lineTo(cx - headW * 0.52f, crownTop)
                    close()
                },
                color = color,
            )
            Hair.UNDERCUT, Hair.FADE -> {
                // A hard line rather than a curve: that flatness IS the haircut, and softening it
                // makes an undercut indistinguishable from a short crop.
                drawRect(
                    color = color,
                    topLeft = Offset(cx - headW * 0.52f, crownTop),
                    size = Size(headW * 1.04f, headH * 0.22f),
                )
                if (spec.hair == Hair.FADE) {
                    drawRect(
                        color = color.copy(alpha = 0.45f),
                        topLeft = Offset(cx - headW * 0.52f, crownTop + headH * 0.22f),
                        size = Size(headW * 1.04f, headH * 0.09f),
                    )
                }
            }
        }
    }
}

// ── Small helpers, because Compose's DrawScope has no left/top/right/bottom forms ──

private fun DrawScope.drawOvalCompat(
    left: Float, top: Float, width: Float, height: Float, color: Color,
) = drawOval(color = color, topLeft = Offset(left, top), size = Size(width, height))

private fun DrawScope.drawRoundRectCompat(
    left: Float, top: Float, right: Float, bottom: Float, radius: Float, color: Color,
) = drawRoundRect(
    color = color,
    topLeft = Offset(left, top),
    size = Size(right - left, bottom - top),
    cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius),
)

private fun DrawScope.drawRoundRectStroke(
    left: Float, top: Float, right: Float, bottom: Float,
    radius: Float, color: Color, width: Float,
) = drawRoundRect(
    color = color,
    topLeft = Offset(left, top),
    size = Size(right - left, bottom - top),
    cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius, radius),
    style = Stroke(width = width),
)
