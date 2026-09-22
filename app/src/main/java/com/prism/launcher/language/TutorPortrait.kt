package com.prism.launcher.language

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable

/**
 * The tutor portraits, drawn rather than shipped.
 *
 * ## Why these are code and not PNGs
 *
 * A roster of sixteen tutors, each needing a grid thumbnail, a large hero, and a call-screen frame,
 * is around fifty images at three densities. That is tens of megabytes in an APK that is already
 * enormous, and every one of them would have to be commissioned or licensed — a language app that
 * borrowed another app's cast would be the most obvious thing in the world.
 *
 * Drawn from a spec instead, a tutor costs about thirty bytes, renders at any size without
 * blurring, follows the device theme, and is unmistakably Prism's own. Adding a seventeenth tutor
 * is one line in [LanguageTutors].
 *
 * ## How a face is built
 *
 * Everything is laid out in fractions of the shorter side, so the same [PortraitSpec] draws
 * identically in a 48dp list row and a 320dp hero. The order is strictly back-to-front —
 * backdrop, shoulders, neck, ears, head, hair back, features, hair front, accessories — because a
 * fringe has to sit over the forehead while the same hairstyle's volume sits behind the ears, and
 * that is only one style if it is drawn in two passes.
 *
 * The result is deliberately stylised. A flat vector face reads instantly at thumbnail size and is
 * honest about being an illustration; the alternative is a rendered human that is neither
 * convincing nor cheap.
 */
class TutorPortrait(
    private val spec: PortraitSpec,
    /** Circular crop for grid tiles and call screens; false gives the full shoulders-up frame. */
    private val circular: Boolean = false,
    /** Draws the backdrop. Off when the portrait sits on an already-tinted surface. */
    private val withBackdrop: Boolean = true,
) : Drawable() {

    enum class Hair { LONG_WAVY, LONG_STRAIGHT, BOB, SHORT_CROP, CURLS, BUN, UNDERCUT, SHAGGY, PONYTAIL, FADE }

    enum class Gesture { NONE, WAVE }

    /**
     * Everything that makes one tutor look like themselves.
     *
     * Plain values rather than a random seed: a seed makes faces that are different but
     * uncontrollable, and "the Japanese tutor has ginger hair this build" is not a bug anyone can
     * fix. Naming each choice means the roster is art-directed and stable across versions.
     */
    data class PortraitSpec(
        val skin: Int,
        val hairColor: Int,
        val hair: Hair,
        val clothing: Int,
        val backdrop: Int,
        val glasses: Boolean = false,
        val earrings: Boolean = false,
        val eyesClosed: Boolean = false,
        val freckles: Boolean = false,
        val gesture: Gesture = Gesture.NONE,
    )

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val box = RectF()
    private val path = Path()

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.width() <= 0 || b.height() <= 0) return

        val s = minOf(b.width(), b.height()).toFloat()
        val cx = b.exactCenterX()
        val top = b.top.toFloat()
        val left = b.left.toFloat()
        val w = b.width().toFloat()
        val h = b.height().toFloat()

        val saved = canvas.save()

        val headCyForFraming = top + h * 0.44f

        if (circular) {
            path.reset()
            path.addCircle(cx, b.exactCenterY(), s / 2f, Path.Direction.CW)
            canvas.clipPath(path)
            // Zoom on the head. The full frame is composed for a rectangle, so cropping it to a
            // circle leaves a ring of empty backdrop above the hair and reads as a small face in a
            // big disc. Scaling about the head puts the face where a profile picture puts it, and
            // the shoulders are cropped away, which is what a crop is for.
            canvas.scale(1.22f, 1.22f, cx, headCyForFraming)
        }

        if (withBackdrop) {
            paint.shader = LinearGradient(
                left, top, left, top + h,
                lighten(spec.backdrop, 0.55f), spec.backdrop,
                Shader.TileMode.CLAMP,
            )
            paint.style = Paint.Style.FILL
            canvas.drawRect(left, top, left + w, top + h, paint)
            paint.shader = null
        }

        // The head sits high and the shoulders run off the bottom edge, which is what makes a
        // portrait read as a portrait rather than a floating head.
        val headW = s * 0.44f
        val headH = s * 0.52f
        val headCy = top + h * 0.44f
        val headTop = headCy - headH / 2f
        val headBottom = headCy + headH / 2f

        drawShoulders(canvas, cx, top + h, s)
        drawNeck(canvas, cx, headBottom, s)
        // Hair BEFORE the head. The back of a hairstyle is behind the skull, and drawing it after
        // meant the mass of a long style covered the whole face -- leaving eyes and a mouth
        // floating on a coloured dome, which is exactly how it looked on device.
        drawHairBack(canvas, cx, headCy, headW, headH, s)
        drawEars(canvas, cx, headCy, headW, s)
        drawHead(canvas, cx, headCy, headW, headH)
        drawFace(canvas, cx, headCy, headW, headH, s)
        drawHairFront(canvas, cx, headTop, headCy, headW, headH, s)
        if (spec.glasses) drawGlasses(canvas, cx, headCy, headW, s)
        if (spec.earrings) drawEarrings(canvas, cx, headCy, headW, s)
        if (spec.gesture == Gesture.WAVE) drawWave(canvas, cx, top + h, s)

        canvas.restoreToCount(saved)
    }

    // -- Body -----------------------------------------------------------------

    private fun drawShoulders(canvas: Canvas, cx: Float, bottom: Float, s: Float) {
        paint.style = Paint.Style.FILL
        paint.color = spec.clothing
        val halfW = s * 0.46f
        box.set(cx - halfW, bottom - s * 0.30f, cx + halfW, bottom + s * 0.30f)
        canvas.drawRoundRect(box, s * 0.24f, s * 0.24f, paint)

        // A collar, purely so the torso is not one flat slab of colour at thumbnail size.
        paint.color = lighten(spec.clothing, 0.22f)
        path.reset()
        path.moveTo(cx - s * 0.11f, bottom - s * 0.30f)
        path.lineTo(cx, bottom - s * 0.17f)
        path.lineTo(cx + s * 0.11f, bottom - s * 0.30f)
        path.close()
        canvas.drawPath(path, paint)
    }

    private fun drawNeck(canvas: Canvas, cx: Float, headBottom: Float, s: Float) {
        paint.style = Paint.Style.FILL
        paint.color = darken(spec.skin, 0.12f)
        val halfW = s * 0.085f
        box.set(cx - halfW, headBottom - s * 0.06f, cx + halfW, headBottom + s * 0.16f)
        canvas.drawRoundRect(box, s * 0.05f, s * 0.05f, paint)
    }

    private fun drawEars(canvas: Canvas, cx: Float, headCy: Float, headW: Float, s: Float) {
        paint.style = Paint.Style.FILL
        paint.color = darken(spec.skin, 0.06f)
        val r = s * 0.045f
        canvas.drawCircle(cx - headW / 2f + r * 0.25f, headCy + s * 0.02f, r, paint)
        canvas.drawCircle(cx + headW / 2f - r * 0.25f, headCy + s * 0.02f, r, paint)
    }

    private fun drawHead(canvas: Canvas, cx: Float, headCy: Float, headW: Float, headH: Float) {
        paint.style = Paint.Style.FILL
        paint.color = spec.skin
        box.set(cx - headW / 2f, headCy - headH / 2f, cx + headW / 2f, headCy + headH / 2f)
        // A large corner radius on a tall rect gives a soft oval with a defined jaw, which a plain
        // ellipse does not: an ellipse reads as an egg and loses the chin entirely below ~64dp.
        canvas.drawRoundRect(box, headW * 0.46f, headW * 0.52f, paint)
    }

    // -- Face -----------------------------------------------------------------

    private fun drawFace(canvas: Canvas, cx: Float, headCy: Float, headW: Float, headH: Float, s: Float) {
        val eyeY = headCy - headH * 0.04f
        val eyeDx = headW * 0.21f
        val eyeR = s * 0.026f

        // Brows, set well above the eyes: the gap is most of what makes a face look calm rather
        // than alarmed, and it is the first thing that goes wrong when features are packed.
        stroke.color = darken(spec.hairColor, 0.15f)
        stroke.strokeWidth = s * 0.016f
        val browY = eyeY - s * 0.055f
        canvas.drawLine(cx - eyeDx - eyeR, browY + s * 0.006f, cx - eyeDx + eyeR, browY - s * 0.004f, stroke)
        canvas.drawLine(cx + eyeDx - eyeR, browY - s * 0.004f, cx + eyeDx + eyeR, browY + s * 0.006f, stroke)

        if (spec.eyesClosed) {
            stroke.color = 0xFF3A3340.toInt()
            stroke.strokeWidth = s * 0.014f
            drawArc(canvas, cx - eyeDx, eyeY, eyeR * 1.3f, upward = false)
            drawArc(canvas, cx + eyeDx, eyeY, eyeR * 1.3f, upward = false)
        } else {
            paint.style = Paint.Style.FILL
            paint.color = Color.WHITE
            canvas.drawCircle(cx - eyeDx, eyeY, eyeR, paint)
            canvas.drawCircle(cx + eyeDx, eyeY, eyeR, paint)

            paint.color = 0xFF3A3340.toInt()
            val iris = eyeR * 0.62f
            canvas.drawCircle(cx - eyeDx, eyeY + eyeR * 0.08f, iris, paint)
            canvas.drawCircle(cx + eyeDx, eyeY + eyeR * 0.08f, iris, paint)

            // One catchlight each. Without it the eyes are two dots and the face is dead.
            paint.color = Color.WHITE
            val gleam = eyeR * 0.22f
            canvas.drawCircle(cx - eyeDx + iris * 0.35f, eyeY - iris * 0.3f, gleam, paint)
            canvas.drawCircle(cx + eyeDx + iris * 0.35f, eyeY - iris * 0.3f, gleam, paint)
        }

        // Nose: a single stroke, because a drawn nostril at this scale looks like dirt.
        stroke.color = darken(spec.skin, 0.28f)
        stroke.strokeWidth = s * 0.012f
        stroke.alpha = 150
        drawArc(canvas, cx, headCy + headH * 0.10f, s * 0.022f, upward = false)
        stroke.alpha = 255

        // Mouth.
        stroke.color = 0xFF9E5B57.toInt()
        stroke.strokeWidth = s * 0.017f
        drawArc(canvas, cx, headCy + headH * 0.24f, s * 0.045f, upward = false)

        paint.style = Paint.Style.FILL
        paint.color = withAlpha(0xFFE8757A.toInt(), 46)
        canvas.drawCircle(cx - headW * 0.30f, headCy + headH * 0.12f, s * 0.035f, paint)
        canvas.drawCircle(cx + headW * 0.30f, headCy + headH * 0.12f, s * 0.035f, paint)

        if (spec.freckles) {
            paint.color = withAlpha(darken(spec.skin, 0.35f), 120)
            val r = s * 0.006f
            for (i in -2..2) {
                if (i == 0) continue
                val dx = headW * 0.10f * i
                canvas.drawCircle(cx + dx, headCy + headH * 0.13f, r, paint)
                canvas.drawCircle(cx + dx * 0.7f, headCy + headH * 0.17f, r, paint)
            }
        }
    }

    /** A smile or a closed eye is the same shape at different sizes: a shallow arc. */
    private fun drawArc(canvas: Canvas, cx: Float, cy: Float, r: Float, upward: Boolean) {
        box.set(cx - r, cy - r, cx + r, cy + r)
        canvas.drawArc(box, if (upward) 180f else 20f, 140f, false, stroke)
    }

    // -- Hair -----------------------------------------------------------------

    /** The volume BEHIND the head: length, bulk, a bun, a ponytail. */
    private fun drawHairBack(canvas: Canvas, cx: Float, headCy: Float, headW: Float, headH: Float, s: Float) {
        paint.style = Paint.Style.FILL
        paint.color = spec.hairColor
        val headTop = headCy - headH / 2f

        when (spec.hair) {
            Hair.LONG_WAVY, Hair.LONG_STRAIGHT -> {
                val halfW = headW * 0.80f
                val drop = if (spec.hair == Hair.LONG_WAVY) headH * 0.92f else headH * 0.80f
                box.set(cx - halfW, headTop - headH * 0.10f, cx + halfW, headCy + drop)
                canvas.drawRoundRect(box, halfW * 0.85f, halfW * 0.7f, paint)
            }
            Hair.CURLS -> {
                // A ring of overlapping circles: the one hairstyle where the silhouette IS the
                // texture, so drawing it as a smooth blob would lose the whole point.
                val r = headW * 0.21f
                val ring = headW * 0.62f
                for (i in 0 until 10) {
                    val a = Math.toRadians(-200.0 + i * 24.0)
                    canvas.drawCircle(
                        cx + (ring * Math.cos(a)).toFloat(),
                        headCy - headH * 0.10f + (ring * 0.86f * Math.sin(a)).toFloat(),
                        r, paint,
                    )
                }
            }
            Hair.BUN -> {
                canvas.drawCircle(cx, headTop - headH * 0.16f, headW * 0.24f, paint)
                box.set(cx - headW * 0.58f, headTop - headH * 0.06f, cx + headW * 0.58f, headCy + headH * 0.12f)
                canvas.drawRoundRect(box, headW * 0.5f, headW * 0.5f, paint)
            }
            Hair.PONYTAIL -> {
                box.set(cx + headW * 0.30f, headCy - headH * 0.20f, cx + headW * 0.86f, headCy + headH * 0.62f)
                canvas.drawRoundRect(box, headW * 0.28f, headW * 0.28f, paint)
                box.set(cx - headW * 0.58f, headTop - headH * 0.08f, cx + headW * 0.58f, headCy + headH * 0.10f)
                canvas.drawRoundRect(box, headW * 0.5f, headW * 0.5f, paint)
            }
            Hair.BOB -> {
                val halfW = headW * 0.72f
                box.set(cx - halfW, headTop - headH * 0.08f, cx + halfW, headCy + headH * 0.30f)
                canvas.drawRoundRect(box, halfW * 0.8f, halfW * 0.8f, paint)
            }
            Hair.SHAGGY -> {
                val halfW = headW * 0.70f
                box.set(cx - halfW, headTop - headH * 0.12f, cx + halfW, headCy + headH * 0.18f)
                canvas.drawRoundRect(box, halfW * 0.7f, halfW * 0.7f, paint)
            }
            Hair.SHORT_CROP, Hair.UNDERCUT, Hair.FADE -> Unit // silhouette is entirely in front
        }
    }

    /** The part that sits OVER the forehead: the fringe, the hairline, the shape of the cut. */
    private fun drawHairFront(
        canvas: Canvas, cx: Float, headTop: Float, headCy: Float, headW: Float, headH: Float, s: Float,
    ) {
        paint.style = Paint.Style.FILL
        paint.color = spec.hairColor

        when (spec.hair) {
            Hair.SHORT_CROP -> {
                box.set(cx - headW * 0.53f, headTop - headH * 0.06f, cx + headW * 0.53f, headCy - headH * 0.12f)
                canvas.drawRoundRect(box, headW * 0.45f, headW * 0.45f, paint)
            }
            Hair.FADE -> {
                box.set(cx - headW * 0.50f, headTop - headH * 0.02f, cx + headW * 0.50f, headCy - headH * 0.16f)
                canvas.drawRoundRect(box, headW * 0.42f, headW * 0.42f, paint)
            }
            Hair.UNDERCUT -> {
                // Swept to one side, which is the whole character of the cut.
                path.reset()
                path.moveTo(cx - headW * 0.52f, headCy - headH * 0.14f)
                path.quadTo(cx - headW * 0.40f, headTop - headH * 0.12f, cx + headW * 0.18f, headTop - headH * 0.04f)
                path.quadTo(cx + headW * 0.56f, headTop + headH * 0.02f, cx + headW * 0.50f, headCy - headH * 0.18f)
                path.quadTo(cx, headCy - headH * 0.30f, cx - headW * 0.52f, headCy - headH * 0.14f)
                path.close()
                canvas.drawPath(path, paint)
            }
            Hair.SHAGGY -> {
                path.reset()
                path.moveTo(cx - headW * 0.56f, headCy - headH * 0.08f)
                var x = -0.56f
                var up = true
                while (x < 0.56f) {
                    val nx = x + 0.14f
                    path.quadTo(
                        cx + headW * (x + 0.07f), headTop + headH * (if (up) -0.10f else 0.02f),
                        cx + headW * nx, headCy - headH * 0.10f,
                    )
                    up = !up
                    x = nx
                }
                path.lineTo(cx + headW * 0.56f, headTop - headH * 0.10f)
                path.lineTo(cx - headW * 0.56f, headTop - headH * 0.10f)
                path.close()
                canvas.drawPath(path, paint)
            }
            Hair.BOB, Hair.LONG_STRAIGHT -> {
                // A blunt fringe with a small gap at one side, so it does not read as a helmet.
                box.set(cx - headW * 0.54f, headTop - headH * 0.06f, cx + headW * 0.54f, headCy - headH * 0.16f)
                canvas.drawRoundRect(box, headW * 0.30f, headW * 0.30f, paint)
            }
            Hair.LONG_WAVY, Hair.BUN, Hair.PONYTAIL, Hair.CURLS -> {
                // A soft side-parted hairline.
                path.reset()
                path.moveTo(cx - headW * 0.54f, headCy - headH * 0.10f)
                path.quadTo(cx - headW * 0.30f, headTop - headH * 0.14f, cx + headW * 0.10f, headTop - headH * 0.06f)
                path.quadTo(cx + headW * 0.50f, headTop + headH * 0.04f, cx + headW * 0.54f, headCy - headH * 0.12f)
                path.quadTo(cx, headCy - headH * 0.34f, cx - headW * 0.54f, headCy - headH * 0.10f)
                path.close()
                canvas.drawPath(path, paint)
            }
        }
    }

    // -- Accessories ----------------------------------------------------------

    private fun drawGlasses(canvas: Canvas, cx: Float, headCy: Float, headW: Float, s: Float) {
        stroke.color = 0xFF3A3340.toInt()
        stroke.strokeWidth = s * 0.013f
        val eyeY = headCy - s * 0.02f
        val eyeDx = headW * 0.21f
        val r = s * 0.052f
        box.set(cx - eyeDx - r, eyeY - r * 0.82f, cx - eyeDx + r, eyeY + r * 0.82f)
        canvas.drawRoundRect(box, r * 0.5f, r * 0.5f, stroke)
        box.set(cx + eyeDx - r, eyeY - r * 0.82f, cx + eyeDx + r, eyeY + r * 0.82f)
        canvas.drawRoundRect(box, r * 0.5f, r * 0.5f, stroke)
        canvas.drawLine(cx - eyeDx + r, eyeY, cx + eyeDx - r, eyeY, stroke)
    }

    private fun drawEarrings(canvas: Canvas, cx: Float, headCy: Float, headW: Float, s: Float) {
        paint.style = Paint.Style.FILL
        paint.color = 0xFFE9B949.toInt()
        val y = headCy + s * 0.055f
        canvas.drawCircle(cx - headW / 2f, y, s * 0.016f, paint)
        canvas.drawCircle(cx + headW / 2f, y, s * 0.016f, paint)
    }

    private fun drawWave(canvas: Canvas, cx: Float, bottom: Float, s: Float) {
        paint.style = Paint.Style.FILL
        val handX = cx - s * 0.40f
        val handY = bottom - s * 0.34f

        // Sleeve first, so the palm overlaps it rather than floating.
        paint.color = spec.clothing
        box.set(handX - s * 0.06f, handY + s * 0.04f, handX + s * 0.06f, handY + s * 0.24f)
        canvas.drawRoundRect(box, s * 0.06f, s * 0.06f, paint)

        paint.color = spec.skin
        canvas.drawCircle(handX, handY, s * 0.075f, paint)
        val fingerW = s * 0.030f
        for (i in 0 until 4) {
            val fx = handX - s * 0.048f + i * s * 0.032f
            box.set(fx - fingerW / 2f, handY - s * 0.135f, fx + fingerW / 2f, handY - s * 0.02f)
            canvas.drawRoundRect(box, fingerW / 2f, fingerW / 2f, paint)
        }
        box.set(handX + s * 0.045f, handY - s * 0.02f, handX + s * 0.105f, handY + s * 0.040f)
        canvas.drawRoundRect(box, s * 0.030f, s * 0.030f, paint)
    }

    // -- Drawable plumbing ----------------------------------------------------

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        stroke.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        stroke.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java", ReplaceWith("PixelFormat.TRANSLUCENT"))
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    companion object {

        fun lighten(color: Int, amount: Float): Int = blend(color, Color.WHITE, amount)

        fun darken(color: Int, amount: Float): Int = blend(color, Color.BLACK, amount)

        fun withAlpha(color: Int, alpha: Int): Int =
            Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

        private fun blend(a: Int, b: Int, t: Float): Int {
            val k = t.coerceIn(0f, 1f)
            return Color.argb(
                Color.alpha(a),
                (Color.red(a) + (Color.red(b) - Color.red(a)) * k).toInt(),
                (Color.green(a) + (Color.green(b) - Color.green(a)) * k).toInt(),
                (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * k).toInt(),
            )
        }
    }
}
