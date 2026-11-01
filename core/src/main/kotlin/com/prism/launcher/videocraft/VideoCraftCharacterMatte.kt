package com.prism.launcher.videocraft

import com.prism.core.PrismImage
import com.prism.core.PrismPlatform
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Getting a generated character into the film.
 *
 * ## The problem this exists to solve
 *
 * A story bible gives every character a description, the description goes into the prompt, and the
 * keyframes come back with the right location, the right light and the right palette -- and no
 * character in them, or a different one every shot. Text-to-image conditions weakly on "a woman in a
 * red coat" when it is also being told about the camera, the location, the style and the previous
 * frame; and a diffusion model asked for the same person twice draws two different people. Every
 * prompt-level fix for this is a wish.
 *
 * ## The two mechanisms, and when each is used
 *
 * 1. **img2img conditioning** (the experimental switch). The character's reference sheet has its
 *    background removed, is composited into the frame's composition at the position the character
 *    stands in, and that composite is handed to the image generator as the img2img INIT image
 *    alongside the prompt. A diffusion model given an init image redraws what is there: the figure
 *    survives as the same figure, and the prompt decides the light, the costume detail and the
 *    finish. This is the route that gives a recognisable recurring character.
 *
 * 2. **Compositing the cutout onto the finished keyframe.** Deterministic, no model involvement, and
 *    guaranteed to put the character in the picture. It looks like what it is -- a cut-out figure
 *    standing in a rendered scene -- which is why it is the fallback rather than the default: it is
 *    used when the switch is off, and when the engine that drew the frame cannot take an init image.
 *
 * Both start from the same cutout, and both use the same placement, so a film does not change
 * blocking depending on which route ran.
 *
 * ## The matte
 *
 * There is no segmentation model here and there should not be: a reference sheet is a subject on a
 * plain-ish background, which is exactly the case a colour-distance matte handles. The border ring
 * gives the background colour, a soft threshold gives alpha, the component containing the frame's
 * centre is kept, enclosed specks are filled, and the edge is feathered. When that finds nothing --
 * a busy generated backdrop, a subject that is mostly the background's colour -- the fallback is a
 * centred ellipse rather than a fully opaque or fully empty cutout: a rough figure in the picture
 * beats no figure at all, and the log says which happened.
 */
object VideoCraftCharacterMatte {

    /** Where one character stands in a frame, in fractions of the frame. */
    data class Placement(val x: Double, val y: Double, val heightFraction: Double)

    /**
     * The cutout stored for a character key, alongside the reference itself.
     *
     * A PREFIX RATHER THAN A SECOND MAP, so the project's save format does not change: references is
     * already a string-to-path map with a `character:name` convention, and a project written by an
     * older build simply has no cutout entry and keeps working.
     */
    fun cutoutKey(referenceKey: String): String = "cutout:$referenceKey"

    // ── The matte ──────────────────────────────────────────────────────────

    /**
     * [reference] with its background made transparent.
     *
     * The returned image is the same size as the input and carries straight (non-premultiplied)
     * alpha, which is what the PNG writer stores and what the compositor expects.
     */
    fun cutout(reference: PrismImage): PrismImage {
        // MATTED SMALL, APPLIED LARGE. The matte is a silhouette; computing it at a few hundred
        // pixels and upscaling the alpha costs a fraction of the work at full resolution and cannot
        // change the answer, because the alpha is smooth by construction.
        val work = if (max(reference.width, reference.height) > MATTE_LONG_SIDE) {
            VideoCraftImageOps.fit(
                reference,
                if (reference.width >= reference.height) MATTE_LONG_SIDE
                else max(1, reference.width * MATTE_LONG_SIDE / reference.height),
                if (reference.height > reference.width) MATTE_LONG_SIDE
                else max(1, reference.height * MATTE_LONG_SIDE / reference.width),
            )
        } else reference

        val w = work.width
        val h = work.height
        val background = backgroundColour(work)
        val spread = backgroundSpread(work, background)
        val low = max(18.0, spread * 1.1)
        val high = max(48.0, spread * 2.6)

        var mask = FloatArray(w * h) { i ->
            val p = work.pixels[i]
            val d = colourDistance(p, background)
            when {
                d <= low -> 0f
                d >= high -> 1f
                else -> ((d - low) / (high - low)).toFloat()
            }
        }

        // THE SUBJECT IS THE BLOB THE MIDDLE IS PART OF. A reference sheet has one figure and it is
        // in the centre; keeping only that component throws away texture in the corners that happens
        // to differ from the background, which is what a bare colour threshold leaves behind.
        val keep = componentContainingCentre(mask, w, h)
        if (keep != null) {
            for (i in mask.indices) if (!keep[i]) mask[i] = 0f
            fillEnclosedHoles(mask, w, h)
        } else {
            PrismPlatform.log.warn(
                "VideoCraft",
                "Character reference has no separable subject; using a centred ellipse so the " +
                    "character still appears",
            )
            mask = ellipseMask(w, h)
        }

        feather(mask, w, h, radius = 1)

        // Upscaled to the reference's own size, bilinearly, so the silhouette stays smooth.
        val alphaSmall = PrismImage(
            w, h, IntArray(w * h) { i -> (mask[i].coerceIn(0f, 1f) * 255f).roundToInt() shl 24 },
        )
        val alpha = if (w == reference.width && h == reference.height) alphaSmall
        else VideoCraftImageOps.resizeBilinear(alphaSmall, reference.width, reference.height)

        val out = IntArray(reference.width * reference.height)
        for (i in out.indices) {
            val a = (alpha.pixels[i] ushr 24) and 0xFF
            out[i] = (reference.pixels[i] and 0x00FFFFFF) or (a shl 24)
        }
        PrismPlatform.log.info(
            "VideoCraft",
            "Character cutout: ${covered(out)}% of the frame kept, background " +
                "#%06X".format(background and 0xFFFFFF),
        )
        return PrismImage(reference.width, reference.height, out)
    }

    /** Fraction of the cutout that survived, for the log line. */
    private fun covered(pixels: IntArray): Int {
        var n = 0
        for (p in pixels) if (((p ushr 24) and 0xFF) > 128) n++
        return (100.0 * n / pixels.size).roundToInt()
    }

    /** The typical border colour: the reference's background, sampled where a background lives. */
    private fun backgroundColour(image: PrismImage): Int {
        val w = image.width
        val h = image.height
        val bandX = max(1, w / 24)
        val bandY = max(1, h / 24)
        var r = 0L
        var g = 0L
        var b = 0L
        var n = 0L
        for (y in 0 until h) {
            val inBandY = y < bandY || y >= h - bandY
            for (x in 0 until w) {
                if (!inBandY && x >= bandX && x < w - bandX) continue
                val p = image.pixels[y * w + x]
                r += (p shr 16) and 0xFF
                g += (p shr 8) and 0xFF
                b += p and 0xFF
                n++
            }
        }
        if (n == 0L) return 0
        return PrismImage.argb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    }

    /**
     * How much the background varies, so the threshold can follow it.
     *
     * A flat studio backdrop measures near zero and gets a tight threshold; a generated reference with
     * a gradient or a vignette measures higher and gets a looser one. Without this, either the flat
     * case keeps noise or the gradient case keeps its whole background.
     */
    private fun backgroundSpread(image: PrismImage, background: Int): Double {
        val w = image.width
        val h = image.height
        val bandX = max(1, w / 24)
        val bandY = max(1, h / 24)
        var sum = 0.0
        var n = 0L
        for (y in 0 until h) {
            val inBandY = y < bandY || y >= h - bandY
            for (x in 0 until w) {
                if (!inBandY && x >= bandX && x < w - bandX) continue
                sum += colourDistance(image.pixels[y * w + x], background)
                n++
            }
        }
        return if (n == 0L) 0.0 else sum / n
    }

    private fun colourDistance(p: Int, other: Int): Double {
        val dr = ((p shr 16) and 0xFF) - ((other shr 16) and 0xFF)
        val dg = ((p shr 8) and 0xFF) - ((other shr 8) and 0xFF)
        val db = (p and 0xFF) - (other and 0xFF)
        // Weighted a little towards green, which is where the eye's luminance mostly lives, so a
        // subject that differs only in hue from the backdrop is not cut into pieces.
        return sqrt(0.30 * dr * dr + 0.55 * dg * dg + 0.15 * db * db)
    }

    /**
     * The connected foreground blob that reaches the middle column of the image, or null.
     *
     * The centre is used rather than "the largest blob" because a reference sheet sometimes has a
     * second figure in a corner or a wide shadow under the subject; the largest blob is often the
     * shadow, and the one the middle belongs to is the subject.
     */
    private fun componentContainingCentre(mask: FloatArray, w: Int, h: Int): BooleanArray? {
        val visited = BooleanArray(mask.size)
        var best: BooleanArray? = null
        var bestSize = 0

        val seeds = ArrayList<Int>(3)
        val centreX = w / 2
        for (y in h / 4 until (3 * h) / 4) {
            val i = y * w + centreX
            if (mask[i] > 0.5f) seeds.add(i)
        }
        if (seeds.isEmpty()) return null

        val stack = ArrayList<Int>(1024)
        for (seed in seeds) {
            if (visited[seed]) continue
            val blob = BooleanArray(mask.size)
            var size = 0
            stack.clear()
            stack.add(seed)
            visited[seed] = true
            while (stack.isNotEmpty()) {
                val i = stack.removeAt(stack.size - 1)
                blob[i] = true
                size++
                val x = i % w
                val y = i / w
                if (x > 0) push(mask, visited, stack, i - 1)
                if (x < w - 1) push(mask, visited, stack, i + 1)
                if (y > 0) push(mask, visited, stack, i - w)
                if (y < h - 1) push(mask, visited, stack, i + w)
            }
            if (size > bestSize) {
                bestSize = size
                best = blob
            }
        }
        // A subject smaller than a fiftieth of the frame is not a subject: that is a speck, and the
        // cutout of it would put nothing in the film.
        return if (best != null && bestSize >= mask.size / 50) best else null
    }

    private fun push(mask: FloatArray, visited: BooleanArray, stack: ArrayList<Int>, i: Int) {
        if (!visited[i] && mask[i] > 0.5f) {
            visited[i] = true
            stack.add(i)
        }
    }

    /**
     * Background-coloured pixels fully enclosed by the subject become subject.
     *
     * A face has a bright spot under the brow and a coat has a dark button; both come back from the
     * matte as holes, and a hole in a composited figure is a window onto the scene behind it.
     */
    private fun fillEnclosedHoles(mask: FloatArray, w: Int, h: Int) {
        val background = BooleanArray(mask.size) { mask[it] <= 0.5f }
        val reachable = BooleanArray(mask.size)
        val stack = ArrayList<Int>(1024)
        for (x in 0 until w) {
            if (background[x]) { reachable[x] = true; stack.add(x) }
            val bottom = (h - 1) * w + x
            if (background[bottom]) { reachable[bottom] = true; stack.add(bottom) }
        }
        for (y in 0 until h) {
            val left = y * w
            if (background[left]) { reachable[left] = true; stack.add(left) }
            val right = y * w + w - 1
            if (background[right]) { reachable[right] = true; stack.add(right) }
        }
        while (stack.isNotEmpty()) {
            val i = stack.removeAt(stack.size - 1)
            val x = i % w
            val y = i / w
            if (x > 0) step(background, reachable, stack, i - 1)
            if (x < w - 1) step(background, reachable, stack, i + 1)
            if (y > 0) step(background, reachable, stack, i - w)
            if (y < h - 1) step(background, reachable, stack, i + w)
        }
        for (i in mask.indices) if (background[i] && !reachable[i]) mask[i] = 1f
    }

    private fun step(background: BooleanArray, reachable: BooleanArray, stack: ArrayList<Int>, i: Int) {
        if (background[i] && !reachable[i]) {
            reachable[i] = true
            stack.add(i)
        }
    }

    /** The last-resort silhouette: the middle of the frame, soft-edged. */
    private fun ellipseMask(w: Int, h: Int): FloatArray {
        val cx = w / 2.0
        val cy = h * 0.58
        val rx = w * 0.34
        val ry = h * 0.42
        return FloatArray(w * h) { i ->
            val x = (i % w - cx) / rx
            val y = (i / w - cy) / ry
            val d = sqrt(x * x + y * y)
            when {
                d <= 0.85 -> 1f
                d >= 1.05 -> 0f
                else -> ((1.05 - d) / 0.2).toFloat()
            }
        }
    }

    /** A one-pass box blur on the alpha, so a composited edge has a pixel of softness. */
    private fun feather(mask: FloatArray, w: Int, h: Int, radius: Int) {
        if (radius <= 0) return
        val copy = mask.copyOf()
        for (y in 0 until h) {
            for (x in 0 until w) {
                var sum = 0f
                var n = 0
                for (dy in -radius..radius) {
                    val yy = y + dy
                    if (yy < 0 || yy >= h) continue
                    for (dx in -radius..radius) {
                        val xx = x + dx
                        if (xx < 0 || xx >= w) continue
                        sum += copy[yy * w + xx]
                        n++
                    }
                }
                mask[y * w + x] = sum / max(1, n)
            }
        }
    }

    // ── Placement ──────────────────────────────────────────────────────────

    /**
     * Where each character stands, deterministically.
     *
     * Deterministic from the seed and the character's index because a figure that wanders between
     * keyframes reads as a glitch rather than as movement, and because a resumed render has to put
     * the same person in the same place as the frames already written.
     */
    fun placements(count: Int, seed: Long): List<Placement> {
        if (count <= 0) return emptyList()
        val random = VideoCraftRandom(seed)
        return (0 until count).map { index ->
            val spacing = when {
                count == 1 -> 0.0
                count == 2 -> 0.16
                else -> 0.19
            }
            val centre = 0.5 + (index - (count - 1) / 2.0) * spacing
            // A small per-character jitter, so three people do not stand at exactly even intervals,
            // plus a distance falloff: the figure closest to the camera stands lower and larger.
            val jitter = (random.nextDouble() - 0.5) * 0.05
            val depth = random.nextDouble()
            val height = when {
                count == 1 -> 0.62
                count == 2 -> 0.58
                else -> 0.52
            } * (0.92 + depth * 0.16)
            Placement(
                x = (centre + jitter).coerceIn(0.12, 0.88),
                y = (0.94 - height / 2).coerceIn(0.4, 0.86),
                heightFraction = height,
            )
        }
    }

    /** Where one placed figure lands in a frame's pixels, as a half-open rect. */
    data class FigureBox(val x: Int, val y: Int, val width: Int, val height: Int) {
        val centreX: Double get() = x + width / 2.0
        val bottom: Int get() = y + height
    }

    /**
     * The rect a figure with [placement] occupies in a frame of [frameWidth] x [frameHeight].
     *
     * Shared by the compositor and by anything that has to touch a figure already in the frame --
     * the lip-sync pass moves a jaw inside this box, and it must land on the same pixels the figure
     * was drawn into rather than on its own second guess at the placement maths.
     */
    fun boxFor(
        frameWidth: Int,
        frameHeight: Int,
        cutoutWidth: Int,
        cutoutHeight: Int,
        placement: Placement,
    ): FigureBox {
        val targetHeight = max(16, (frameHeight * placement.heightFraction).roundToInt())
        val targetWidth = max(
            16,
            (cutoutWidth.toDouble() * targetHeight / max(1, cutoutHeight)).roundToInt(),
        )
        return FigureBox(
            x = (frameWidth * placement.x).roundToInt() - targetWidth / 2,
            // placement.y is the figure's centre vertically.
            y = (frameHeight * placement.y).roundToInt() - targetHeight / 2,
            width = targetWidth,
            height = targetHeight,
        )
    }

    /**
     * Composites [cutout] onto [frame] at [placement].
     *
     * Four things stand between a cutout and a figure that looks like it is standing there, and all
     * four are here: the figure is scaled to its standing height, a **colour transfer** matches its
     * colour distribution to the frame's light in that region (Reinhard's mean/standard-deviation
     * transfer, per channel, clamped), a gain pass pins its luminance to the region's, and a soft
     * contact shadow is laid under its feet. The colour transfer is what stops a reference sheet lit
     * by a neutral studio light from sitting in a blue night scene as a grey patch: a luma gain alone
     * fixes brightness and leaves the hue wrong.
     */
    fun composite(frame: PrismImage, cutout: PrismImage, placement: Placement): PrismImage {
        val box = boxFor(frame.width, frame.height, cutout.width, cutout.height, placement)
        val targetWidth = box.width
        val targetHeight = box.height
        val figure = VideoCraftImageOps.resizeBilinear(cutout, targetWidth, targetHeight)

        val originX = box.x
        // placement.y is the figure's centre vertically.
        val originY = box.y

        val out = frame.pixels.copyOf()

        // THE LIGHT, MEASURED WHERE THE FIGURE WILL BE. A cutout from a bright reference sheet
        // composited into a dusk frame is the single most obvious tell that a figure was pasted in,
        // so the figure's colours are mapped onto the colours of the region it lands in -- brightness
        // and hue together, which is what a luminance gain alone cannot do.
        val tint = colourTransfer(frame, figure, box)

        drawShadow(out, frame.width, frame.height, originX, originY, targetWidth, targetHeight)

        for (y in 0 until targetHeight) {
            val fy = originY + y
            if (fy < 0 || fy >= frame.height) continue
            for (x in 0 until targetWidth) {
                val fx = originX + x
                if (fx < 0 || fx >= frame.width) continue
                val p = figure.pixels[y * targetWidth + x]
                val alpha = ((p ushr 24) and 0xFF) / 255.0
                if (alpha <= 0.004) continue
                val fr = (p shr 16) and 0xFF
                val fg = (p shr 8) and 0xFF
                val fb = p and 0xFF
                val di = fy * frame.width + fx
                val under = out[di]
                val ur = (under shr 16) and 0xFF
                val ug = (under shr 8) and 0xFF
                val ub = under and 0xFF
                out[di] = PrismImage.argb(
                    (ur + (tint.red(fr) - ur) * alpha).roundToInt(),
                    (ug + (tint.green(fg) - ug) * alpha).roundToInt(),
                    (ub + (tint.blue(fb) - ub) * alpha).roundToInt(),
                )
            }
        }
        return PrismImage(frame.width, frame.height, out)
    }

    /**
     * The per-channel affine map that takes the figure's colours to the frame's, in that region.
     *
     * Reinhard's transfer, applied per channel: `out = (in - meanIn) * (sdOut / sdIn) + meanOut`, with
     * both scale and offset clamped. The clamps are the whole reason this is safe to run unattended:
     * an unbounded transfer on a figure standing against a near-uniform backdrop has an sd near zero
     * and produces a saturated smear, and on a figure in a fog bank it produces a black silhouette.
     * Matching a *region* of the frame rather than the whole frame is also deliberate: a night scene
     * with one lamp is not a night scene where the figure stands.
     */
    private class ColourTint(val scale: DoubleArray, val offset: DoubleArray) {
        fun red(v: Int) = (scale[0] * v + offset[0]).roundToInt().coerceIn(0, 255)
        fun green(v: Int) = (scale[1] * v + offset[1]).roundToInt().coerceIn(0, 255)
        fun blue(v: Int) = (scale[2] * v + offset[2]).roundToInt().coerceIn(0, 255)

        companion object {
            val NONE = ColourTint(DoubleArray(3) { 1.0 }, DoubleArray(3))
        }
    }

    private fun colourTransfer(frame: PrismImage, figure: PrismImage, box: FigureBox): ColourTint {
        val figureStats = channelStats(figure, null)
        val frameStats = channelStats(frame, box)
        val scale = DoubleArray(3)
        val offset = DoubleArray(3)
        for (c in 0..2) {
            val sdIn = figureStats[1][c]
            val sdOut = frameStats[1][c]
            // A REGION WITH NO SPREAD KEEPS THE FIGURE'S OWN. When either side is flat -- a cutout with
            // no shading, a fog bank, a frame that is one colour where the figure lands -- a contrast
            // ratio is meaningless, and scaling by a ratio derived from noise crushes the figure. Those
            // cases get a pure mean shift; only two structured regions exchange contrast.
            val s = if (sdIn <= 2.0 || sdOut <= 2.0) 1.0 else (sdOut / sdIn).coerceIn(0.75, 1.35)
            val meanIn = figureStats[0][c]
            val meanOut = frameStats[0][c]
            scale[c] = s
            // The offset carries the mean shift the scale cannot, clamped to a third of the range so
            // a figure in a very dark region darkens but never inverts.
            offset[c] = (meanOut - s * meanIn).coerceIn(-96.0, 96.0)
        }
        return ColourTint(scale, offset)
    }

    /** Mean and standard deviation per RGB channel: of the figure's visible pixels, or of [box]. */
    private fun channelStats(image: PrismImage, box: FigureBox?): Array<DoubleArray> {
        val sums = DoubleArray(3)
        val squares = DoubleArray(3)
        var n = 0L
        val startX = max(0, box?.x ?: 0)
        val endX = min(image.width, box?.let { it.x + it.width } ?: image.width)
        val startY = max(0, box?.y ?: 0)
        val endY = min(image.height, box?.let { it.y + it.height } ?: image.height)
        // A stride keeps this at a few thousand samples on a full-size frame; the statistics of a
        // figure do not change between one pixel and its neighbour.
        val stepX = max(1, (endX - startX) / 64)
        val stepY = max(1, (endY - startY) / 64)
        var y = startY
        while (y < endY) {
            var x = startX
            while (x < endX) {
                val p = image.pixels[y * image.width + x]
                val visible = box == null && ((p ushr 24) and 0xFF) > 128 || box != null
                if (visible) {
                    val c = intArrayOf((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF)
                    for (i in 0..2) {
                        sums[i] += c[i]
                        squares[i] += c[i].toDouble() * c[i]
                    }
                    n++
                }
                x += stepX
            }
            y += stepY
        }
        if (n == 0L) return arrayOf(DoubleArray(3), DoubleArray(3) { 1.0 })
        val means = DoubleArray(3) { sums[it] / n }
        val sds = DoubleArray(3) {
            val variance = squares[it] / n - means[it] * means[it]
            if (variance <= 0.0) 0.0 else sqrt(variance)
        }
        return arrayOf(means, sds)
    }

    /**
     * A cutout laid into the composition as an init image.
     *
     * This is the img2img route: the figure is placed on [backdrop] (the previous frame when there is
     * one, a palette wash when there is not) and the result is handed to the generator as its init.
     * The figure is composited at a *lower* opacity than in the fallback path, because here it is a
     * suggestion for the sampler rather than the final pixels -- a fully opaque cutout makes a
     * low-denoise pass return it almost unchanged, which is the pasted-in look again with extra
     * steps.
     */
    fun starterFrame(
        backdrop: PrismImage?,
        cutouts: List<Pair<PrismImage, Placement>>,
        width: Int,
        height: Int,
        palette: List<Int>,
        seed: Long,
    ): PrismImage {
        val base = backdrop?.let { VideoCraftImageOps.fit(it, width, height) }
            ?: VideoCraftRenderer.render("scene", width, height, palette, seed)
        var frame = base
        for ((index, entry) in cutouts.withIndex()) {
            val (cutout, placement) = entry
            val softened = fade(cutout, if (index == 0) 0.82 else 0.78)
            frame = composite(frame, softened, placement)
        }
        return frame
    }

    /** Scales a cutout's alpha, leaving its colours alone. */
    fun fade(cutout: PrismImage, amount: Double): PrismImage {
        if (amount >= 1.0) return cutout
        val f = amount.coerceIn(0.0, 1.0)
        val out = IntArray(cutout.pixels.size)
        for (i in out.indices) {
            val p = cutout.pixels[i]
            val a = (((p ushr 24) and 0xFF) * f).roundToInt().coerceIn(0, 255)
            out[i] = (p and 0x00FFFFFF) or (a shl 24)
        }
        return PrismImage(cutout.width, cutout.height, out)
    }

    /** A soft dark ellipse where the figure's feet are, so it is standing on something. */
    private fun drawShadow(
        out: IntArray,
        width: Int,
        height: Int,
        x0: Int,
        y0: Int,
        figureWidth: Int,
        figureHeight: Int,
    ) {
        val cx = x0 + figureWidth / 2.0
        val cy = y0 + figureHeight * 0.97
        val rx = figureWidth * 0.58
        val ry = max(2.0, figureHeight * 0.045)
        val startX = max(0, (cx - rx).toInt())
        val endX = min(width - 1, (cx + rx).toInt())
        val startY = max(0, (cy - ry * 2).toInt())
        val endY = min(height - 1, (cy + ry * 2).toInt())
        for (y in startY..endY) {
            for (x in startX..endX) {
                val dx = (x - cx) / rx
                val dy = (y - cy) / ry
                val d = dx * dx + dy * dy
                if (d >= 1.0) continue
                val strength = (1.0 - d) * 0.42
                val i = y * width + x
                val p = out[i]
                val f = 1.0 - strength
                out[i] = PrismImage.argb(
                    (((p shr 16) and 0xFF) * f).roundToInt(),
                    (((p shr 8) and 0xFF) * f).roundToInt(),
                    ((p and 0xFF) * f).roundToInt(),
                )
            }
        }
    }

    private const val MATTE_LONG_SIDE = 512

    /** True when [pixels] look like they came from a cutout rather than a full frame. */
    fun looksCutOut(image: PrismImage): Boolean {
        var transparent = 0
        var i = 0
        while (i < image.pixels.size) {
            if (((image.pixels[i] ushr 24) and 0xFF) < 200) transparent++
            i++
        }
        return transparent > image.pixels.size / 20
    }

    /** A tiny deterministic generator. Not a general-purpose RNG: placements only. */
    private class VideoCraftRandom(seed: Long) {
        private var state = if (seed == 0L) 0x9E3779B97F4A7C15uL.toLong() else seed

        fun nextDouble(): Double {
            state = state * 6364136223846793005L + 1442695040888963407L
            val x = state xor (state ushr 33)
            return ((x ushr 11).toDouble() / (1L shl 53).toDouble()).let { abs(it) % 1.0 }
        }
    }
}
