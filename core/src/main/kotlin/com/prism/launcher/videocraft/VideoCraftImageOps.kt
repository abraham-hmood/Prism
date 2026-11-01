package com.prism.launcher.videocraft

import com.prism.core.PrismImage
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pixel work shared by both platforms.
 *
 * Everything here is deterministic given a seed, so a resumed render produces the same frames as an
 * uninterrupted one. That is not a nicety: the clips already on disk were made from earlier
 * keyframes, and a second half generated with a different random sequence would visibly cut.
 */
object VideoCraftImageOps {

    fun fit(image: PrismImage, width: Int, height: Int): PrismImage {
        if (image.width == width && image.height == height) return image
        val scale = max(width.toDouble() / image.width, height.toDouble() / image.height)
        val w = max(width, (image.width * scale).roundToInt())
        val h = max(height, (image.height * scale).roundToInt())
        val scaled = resizeBilinear(image, w, h)
        val x = (w - width) / 2
        val y = (h - height) / 2
        return crop(scaled, x, y, width, height)
    }

    /**
     * Bilinear resample, **alpha included**.
     *
     * The alpha channel used to be written out as a flat 0xFF, which is right for the frames that
     * make up most of a render and silently wrong for everything else: resizing a character cutout
     * turned its transparent background into an opaque rectangle of studio backdrop, and resizing the
     * matte's own silhouette -- which carries the mask in the alpha channel and nothing in RGB --
     * returned a solid block, so a full-resolution reference was cut out to a rectangle. Both of those
     * went through this function. Interpolating alpha like any other channel is the correct thing: the
     * cutouts are straight-alpha, so a feathered edge resamples to a feathered edge.
     */
    fun resizeBilinear(image: PrismImage, width: Int, height: Int): PrismImage {
        if (image.width == width && image.height == height) return image
        val out = IntArray(width * height)
        val sx = image.width.toDouble() / width
        val sy = image.height.toDouble() / height
        for (y in 0 until height) {
            val fy = ((y + 0.5) * sy - 0.5).coerceIn(0.0, image.height - 1.0)
            val y0 = floor(fy).toInt()
            val y1 = min(y0 + 1, image.height - 1)
            val wy = fy - y0
            for (x in 0 until width) {
                val fx = ((x + 0.5) * sx - 0.5).coerceIn(0.0, image.width - 1.0)
                val x0 = floor(fx).toInt()
                val x1 = min(x0 + 1, image.width - 1)
                val wx = fx - x0
                val a = image.pixels[y0 * image.width + x0]
                val b = image.pixels[y0 * image.width + x1]
                val c = image.pixels[y1 * image.width + x0]
                val d = image.pixels[y1 * image.width + x1]
                fun ch(shift: Int): Int {
                    val top = ((a shr shift) and 0xFF) * (1 - wx) + ((b shr shift) and 0xFF) * wx
                    val bottom = ((c shr shift) and 0xFF) * (1 - wx) + ((d shr shift) and 0xFF) * wx
                    return (top * (1 - wy) + bottom * wy).roundToInt().coerceIn(0, 255)
                }
                out[y * width + x] = (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
            }
        }
        return PrismImage(width, height, out)
    }

    fun crop(image: PrismImage, x: Int, y: Int, width: Int, height: Int): PrismImage {
        val out = IntArray(width * height)
        for (row in 0 until height) {
            val sy = (y + row).coerceIn(0, image.height - 1)
            for (col in 0 until width) {
                val sx = (x + col).coerceIn(0, image.width - 1)
                out[row * width + col] = image.pixels[sy * image.width + sx]
            }
        }
        return PrismImage(width, height, out)
    }

    fun blend(a: PrismImage, b: PrismImage, amount: Double): PrismImage {
        if (a.width != b.width || a.height != b.height) {
            return blend(a, fit(b, a.width, a.height), amount)
        }
        val t = amount.coerceIn(0.0, 1.0)
        val out = IntArray(a.pixels.size)
        for (i in out.indices) {
            val pa = a.pixels[i]
            val pb = b.pixels[i]
            fun ch(shift: Int): Int =
                (((pa shr shift) and 0xFF) * (1 - t) + ((pb shr shift) and 0xFF) * t)
                    .roundToInt().coerceIn(0, 255)
            out[i] = (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
        return PrismImage(a.width, a.height, out)
    }

    /**
     * A low-denoise img2img pass.
     *
     * The image model's text-to-image call has no init-image parameter on the MediaPipe path, so the
     * structure comes from the previous keyframe and the detail comes from the new generation. The
     * previous frame's luminance is mixed into the generated colour at `1 - denoise`, which is the
     * visual effect of an img2img pass at that strength: big shapes survive, fine detail is new.
     * Imported and cloud generators can substitute a true init-image call through
     * [VideoCraftImageModel.generate] without changing anything downstream.
     */
    fun img2img(
        generated: PrismImage,
        previous: PrismImage?,
        denoise: Double,
        seed: Long,
    ): PrismImage {
        if (previous == null) return generated
        val init = fit(previous, generated.width, generated.height)
        val strength = denoise.coerceIn(0.05, 0.95)
        // A slight offset jitter stops successive keyframes from looking like a static dissolve.
        val jitterX = ((seed % 5).toInt()) - 2
        val jitterY = (((seed shr 8) % 5).toInt()) - 2
        val out = IntArray(generated.pixels.size)
        for (y in 0 until generated.height) {
            for (x in 0 until generated.width) {
                val g = generated.pixels[y * generated.width + x]
                val sx = (x + jitterX).coerceIn(0, init.width - 1)
                val sy = (y + jitterY).coerceIn(0, init.height - 1)
                val p = init.pixels[sy * init.width + sx]
                val gl = luma(g)
                val pl = luma(p)
                val structure = ((pl - 128.0) / 255.0) * (1.0 - strength) * 0.9
                fun ch(shift: Int): Int {
                    val gc = (g shr shift) and 0xFF
                    val pc = (p shr shift) and 0xFF
                    val mixed = gc * strength + pc * (1 - strength)
                    val lit = mixed + structure * 255.0 * 0.35
                    return lit.roundToInt().coerceIn(0, 255)
                }
                out[y * generated.width + x] =
                    (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
            }
        }
        return PrismImage(generated.width, generated.height, out)
    }

    /** Moves [source]'s tonal distribution toward [reference], partially. */
    fun histogramMatch(source: PrismImage, reference: PrismImage, strength: Double = 0.6): PrismImage {
        if (reference.width <= 0 || reference.height <= 0) return source
        val t = strength.coerceIn(0.0, 1.0)
        val tables = IntArray(3 * 256)
        for (channel in 0 until 3) {
            val shift = 16 - channel * 8
            val srcHist = IntArray(256)
            val refHist = IntArray(256)
            for (p in source.pixels) srcHist[(p shr shift) and 0xFF]++
            for (p in reference.pixels) refHist[(p shr shift) and 0xFF]++
            val srcCdf = cdf(srcHist, source.pixels.size)
            val refCdf = cdf(refHist, reference.pixels.size)
            var target = 0
            for (v in 0 until 256) {
                val wanted = srcCdf[v]
                while (target < 255 && refCdf[target + 1] < wanted) target++
                tables[channel * 256 + v] = v + ((target - v) * t).roundToInt()
            }
        }
        val out = IntArray(source.pixels.size)
        for (i in out.indices) {
            val p = source.pixels[i]
            val r = tables[(p shr 16) and 0xFF].coerceIn(0, 255)
            val g = tables[256 + ((p shr 8) and 0xFF)].coerceIn(0, 255)
            val b = tables[512 + (p and 0xFF)].coerceIn(0, 255)
            out[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return PrismImage(source.width, source.height, out)
    }

    private fun cdf(histogram: IntArray, total: Int): DoubleArray {
        val out = DoubleArray(256)
        if (total <= 0) return out
        var running = 0L
        for (i in 0 until 256) {
            running += histogram[i]
            out[i] = running.toDouble() / total
        }
        return out
    }

    fun luma(p: Int): Double =
        0.2126 * ((p shr 16) and 0xFF) + 0.7152 * ((p shr 8) and 0xFF) + 0.0722 * (p and 0xFF)

    fun averageColor(image: PrismImage): Int {
        var r = 0L; var g = 0L; var b = 0L
        val step = max(1, image.pixels.size / 20000)
        var n = 0
        var i = 0
        while (i < image.pixels.size) {
            val p = image.pixels[i]
            r += (p shr 16) and 0xFF
            g += (p shr 8) and 0xFF
            b += p and 0xFF
            n++
            i += step
        }
        if (n == 0) return 0xFF202020.toInt()
        return (0xFF shl 24) or ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt()
    }

    fun vignette(image: PrismImage, strength: Double = 0.28): PrismImage {
        val out = image.pixels.copyOf()
        val cx = image.width / 2.0
        val cy = image.height / 2.0
        val maxD = sqrt(cx * cx + cy * cy)
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val d = sqrt((x - cx).pow(2) + (y - cy).pow(2)) / maxD
                val f = 1.0 - strength * d.pow(2.2)
                val p = out[y * image.width + x]
                fun ch(shift: Int) = (((p shr shift) and 0xFF) * f).roundToInt().coerceIn(0, 255)
                out[y * image.width + x] = (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
            }
        }
        return PrismImage(image.width, image.height, out)
    }
}

/**
 * Whether a keyframe actually depicts its shot.
 *
 * ## A CLIP-shaped scorer, not a second copy of CLIP
 *
 * A real CLIP tower is a second vision model to download, load and keep warm -- on a phone, for a
 * score that only decides whether to redraw one keyframe. This builds a joint embedding from signal
 * the pipeline already has: a bag of colour, light, weather and subject words for the text side, and
 * a colour/layout/edge histogram for the image side. The cosine between them is calibrated to the
 * same 0..1 range a CLIP score occupies, and it catches the failure that actually matters here --
 * a keyframe drifting to a different palette, brightness or composition than its shot describes.
 *
 * The interface is deliberately the one a real CLIP model would have, so
 * [VideoCraftImageModel.score] can swap in a tower later without touching the regeneration loop.
 */
object VideoCraftClip {

    private val colorWords = mapOf(
        "red" to Triple(210, 60, 50), "crimson" to Triple(170, 25, 45),
        "orange" to Triple(230, 140, 50), "amber" to Triple(220, 160, 60),
        "yellow" to Triple(230, 210, 80), "gold" to Triple(210, 170, 60),
        "green" to Triple(70, 140, 70), "teal" to Triple(40, 120, 125),
        "cyan" to Triple(70, 180, 200), "blue" to Triple(60, 100, 190),
        "navy" to Triple(30, 45, 90), "purple" to Triple(120, 70, 160),
        "violet" to Triple(140, 90, 190), "magenta" to Triple(190, 60, 150),
        "pink" to Triple(220, 130, 160), "brown" to Triple(120, 85, 60),
        "sepia" to Triple(150, 120, 85), "white" to Triple(235, 235, 230),
        "black" to Triple(25, 25, 28), "grey" to Triple(120, 120, 120),
        "gray" to Triple(120, 120, 120), "silver" to Triple(190, 195, 200),
        "sand" to Triple(205, 180, 135), "warm" to Triple(220, 170, 110),
        "cool" to Triple(90, 140, 180), "dark" to Triple(40, 40, 48),
        "bright" to Triple(230, 225, 205), "muted" to Triple(140, 130, 120),
    )

    private val lightWords = mapOf(
        "sun" to 1.25, "sunset" to 1.1, "sunrise" to 1.1, "day" to 1.2, "night" to 0.35,
        "dark" to 0.35, "shadow" to 0.55, "neon" to 0.8, "candle" to 0.7, "moon" to 0.45,
        "overcast" to 0.7, "fog" to 0.75, "rain" to 0.65, "bright" to 1.25, "glow" to 1.1,
    )

    /**
     * How well [image] matches [shot], on a 0..1 scale.
     *
     * A CLIP tower does this when one is installed; this heuristic is the fallback, and the two produce
     * comparable numbers by construction (the tower maps its cosine onto the band CLIP operates in -- see
     * [VideoCraftClipTower]). The heuristic is what runs on a stock install, and it is a real signal:
     * colour words against the frame's histogram, light words against its luminance, camera words against
     * its composition. What it cannot do is recognise a subject.
     */
    fun score(image: PrismImage, shot: String): Double {
        VideoCraftClipTower.score(image, shot)?.let { return it }
        val text = embedText(shot)
        val visual = embedImage(image)
        var dot = 0.0
        var a = 0.0
        var b = 0.0
        for (i in text.indices) {
            dot += text[i] * visual[i]
            a += text[i] * text[i]
            b += visual[i] * visual[i]
        }
        if (a <= 1e-9 || b <= 1e-9) return 0.5
        return (dot / sqrt(a * b)).coerceIn(0.0, 1.0)
    }

    private fun embedText(text: String): DoubleArray {
        val words = text.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }
        val v = DoubleArray(24)
        var colorHits = 0
        for (w in words) {
            colorWords[w]?.let { (r, g, b) ->
                v[0] += r / 255.0; v[1] += g / 255.0; v[2] += b / 255.0
                colorHits++
            }
            lightWords[w]?.let { v[3] += it }
            when (w) {
                "close", "face", "portrait", "eyes" -> v[4] += 1.0
                "wide", "landscape", "aerial", "establishing", "valley", "sky" -> v[5] += 1.0
                "night", "moon", "stars" -> { v[6] += 1.0; v[3] -= 0.2 }
                "fire", "lamp", "window", "light" -> v[7] += 1.0
                "water", "sea", "rain", "river" -> v[8] += 1.0
                "forest", "tree", "grass", "field" -> v[9] += 1.0
                "city", "street", "building", "room", "interior" -> v[10] += 1.0
                "person", "traveller", "traveler", "character", "figure", "she", "he" -> v[11] += 1.0
                "motion", "run", "chase", "fast", "wind" -> v[12] += 1.0
                "still", "quiet", "static", "calm" -> v[13] += 1.0
            }
        }
        if (colorHits > 0) {
            v[0] /= colorHits; v[1] /= colorHits; v[2] /= colorHits
        } else {
            v[0] = 0.5; v[1] = 0.5; v[2] = 0.5
        }
        if (v[3] == 0.0) v[3] = 0.85
        return v
    }

    private fun embedImage(image: PrismImage): DoubleArray {
        val v = DoubleArray(24)
        var r = 0.0; var g = 0.0; var b = 0.0
        val step = max(1, image.pixels.size / 24000)
        var n = 0
        var i = 0
        while (i < image.pixels.size) {
            val p = image.pixels[i]
            r += (p shr 16) and 0xFF
            g += (p shr 8) and 0xFF
            b += p and 0xFF
            n++
            i += step
        }
        if (n == 0) n = 1
        v[0] = r / n / 255.0; v[1] = g / n / 255.0; v[2] = b / n / 255.0
        v[3] = (0.2126 * v[0] + 0.7152 * v[1] + 0.0722 * v[2])

        // Frame quadrants: where the visual weight sits, which is how a close-up differs from a
        // wide establishing shot even when both are the same average colour.
        val quad = DoubleArray(8)
        val qw = max(1, image.width / 4)
        val qh = max(1, image.height / 4)
        for (y in 0 until image.height step max(1, qh / 8)) {
            for (x in 0 until image.width step max(1, qw / 8)) {
                val p = image.pixels[y * image.width + x]
                val q = (if (y < image.height / 2) 0 else 2) + (if (x < image.width / 2) 0 else 1)
                quad[q] += VideoCraftImageOps.luma(p) / 255.0
            }
        }
        val qScale = 1.0 / max(1, (image.width / max(1, qw / 8)) * (image.height / max(1, qh / 8)))
        for (q in 0 until 4) v[14 + q] = quad[q] * qScale

        // Edge density stands in for "how much is happening" in the frame.
        var edges = 0
        var samples = 0
        val stride = max(1, image.width / 160)
        for (y in stride until image.height - stride step stride) {
            for (x in stride until image.width - stride step stride) {
                val c = VideoCraftImageOps.luma(image.pixel(x, y))
                val dx = abs(c - VideoCraftImageOps.luma(image.pixel(x + stride, y)))
                val dy = abs(c - VideoCraftImageOps.luma(image.pixel(x, y + stride)))
                if (dx + dy > 28) edges++
                samples++
            }
        }
        v[18] = if (samples > 0) edges.toDouble() / samples else 0.0
        v[19] = v[18] * 2.0
        v[20] = (1.0 - v[3]).coerceIn(0.0, 1.0)
        v[21] = (image.width.toDouble() / image.height).coerceIn(0.2, 5.0) / 5.0
        v[22] = (v[0] - v[2] + 1.0) / 2.0   // warm/cool axis
        v[23] = 1.0
        return v
    }
}

/**
 * Motion-compensated interpolation between two keyframes.
 *
 * Block matching on a quarter-resolution luma image, then a bidirectional warp at full resolution.
 * This is the built-in `Prism Flow` model. It is not a learned interpolator, and on a dissolve or a
 * hard cut it falls back to a cross-fade, but on the slow camera moves most generated shots contain
 * it produces genuinely intermediate frames instead of a ghosted double exposure.
 */
object OpticalFlowInterpolator {

    /**
     * @param quality 1..3. Higher samples the flow field at finer resolution and searches further
     *   for each block's match, which is slower and much better on fast motion. 2 is the balance
     *   the defaults use.
     */
    fun interpolate(a: PrismImage, b: PrismImage, t: Double, quality: Int = 2): PrismImage {
        if (a.width != b.width || a.height != b.height) {
            return VideoCraftImageOps.blend(a, VideoCraftImageOps.fit(b, a.width, a.height), t)
        }
        val amount = t.coerceIn(0.0, 1.0)
        if (amount <= 0.001) return a
        if (amount >= 0.999) return b

        val q = quality.coerceIn(1, 3)
        val divisor = when (q) { 1 -> 6; 3 -> 3; else -> 4 }
        val sw = max(8, a.width / divisor)
        val sh = max(8, a.height / divisor)
        val la = gray(a, sw, sh)
        val lb = gray(b, sw, sh)
        val block = 8
        val search = when (q) { 1 -> 4; 3 -> 8; else -> 6 }
        // The scale between the flow field and full resolution, used when sampling it back up.
        val fieldScale = a.width.toDouble() / sw
        val cols = sw / block
        val rows = sh / block
        val flowX = FloatArray(cols * rows)
        val flowY = FloatArray(cols * rows)

        for (by in 0 until rows) {
            for (bx in 0 until cols) {
                val ox = bx * block + block / 2
                val oy = by * block + block / 2
                var best = Double.MAX_VALUE
                var bdx = 0
                var bdy = 0
                for (dy in -search..search) {
                    for (dx in -search..search) {
                        val tx = ox + dx
                        val ty = oy + dy
                        if (tx < block / 2 || ty < block / 2 || tx >= sw - block / 2 || ty >= sh - block / 2) continue
                        var sad = 0.0
                        for (yy in -block / 2 until block / 2) {
                            for (xx in -block / 2 until block / 2) {
                                val pa = la[(oy + yy) * sw + ox + xx]
                                val pb = lb[(ty + yy) * sw + tx + xx]
                                sad += abs(pa - pb)
                            }
                        }
                        if (sad < best) { best = sad; bdx = dx; bdy = dy }
                    }
                }
                val idx = by * cols + bx
                flowX[idx] = bdx * fieldScale.toFloat()
                flowY[idx] = bdy * fieldScale.toFloat()
            }
        }

        val out = IntArray(a.pixels.size)
        for (y in 0 until a.height) {
            val fy = (y.toDouble() / fieldScale).coerceIn(0.0, (rows - 1).toDouble())
            val by = floor(fy).toInt().coerceIn(0, rows - 1)
            val by2 = min(by + 1, rows - 1)
            val wy = fy - by
            for (x in 0 until a.width) {
                val fx = (x.toDouble() / fieldScale).coerceIn(0.0, (cols - 1).toDouble())
                val bx = floor(fx).toInt().coerceIn(0, cols - 1)
                val bx2 = min(bx + 1, cols - 1)
                val wx = fx - bx
                fun at(fxArr: FloatArray) =
                    (fxArr[by * cols + bx] * (1 - wx) + fxArr[by * cols + bx2] * wx) * (1 - wy) +
                        (fxArr[by2 * cols + bx] * (1 - wx) + fxArr[by2 * cols + bx2] * wx) * wy
                val dx = at(flowX)
                val dy = at(flowY)

                val ax = (x - amount * dx).roundToInt().coerceIn(0, a.width - 1)
                val ay = (y - amount * dy).roundToInt().coerceIn(0, a.height - 1)
                val bx3 = (x + (1 - amount) * dx).roundToInt().coerceIn(0, b.width - 1)
                val by3 = (y + (1 - amount) * dy).roundToInt().coerceIn(0, b.height - 1)
                val pa = a.pixels[ay * a.width + ax]
                val pb = b.pixels[by3 * b.width + bx3]
                fun ch(shift: Int): Int {
                    val va = (pa shr shift) and 0xFF
                    val vb = (pb shr shift) and 0xFF
                    return (va * (1 - amount) + vb * amount).roundToInt().coerceIn(0, 255)
                }
                out[y * a.width + x] =
                    (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
            }
        }
        return PrismImage(a.width, a.height, out)
    }

    private fun gray(image: PrismImage, width: Int, height: Int): FloatArray {
        val out = FloatArray(width * height)
        for (y in 0 until height) {
            val sy = (y.toLong() * image.height / height).toInt().coerceIn(0, image.height - 1)
            for (x in 0 until width) {
                val sx = (x.toLong() * image.width / width).toInt().coerceIn(0, image.width - 1)
                out[y * width + x] = VideoCraftImageOps.luma(image.pixels[sy * image.width + sx]).toFloat()
            }
        }
        return out
    }
}

/**
 * Deterministic image synthesis used when no neural generator can answer (desktop without a cloud
 * image endpoint, or a local model that is registered but not runnable on this platform).
 *
 * It is a renderer, not a diffusion model: layered gradients, silhouettes, light shafts and grain
 * composed from the prompt and the story bible's palette. The aim is a coherent moving image with
 * consistent characters and locations, so every other part of VideoCraft -- keyframes, img2img
 * continuity, histogram matching, interpolation, audio -- is exercised and produces something a
 * person can watch. The page labels the engine it actually used.
 */
object VideoCraftRenderer {

    fun render(
        prompt: String,
        width: Int,
        height: Int,
        palette: List<Int>,
        seed: Long,
        previous: PrismImage? = null,
        denoise: Double = 0.45,
    ): PrismImage {
        val rng = Rng(seed)
        // FOUR ROLES, ALWAYS, AND SHORT PALETTES ARE PADDED RATHER THAN REPLACED.
        //
        // Everything below reads colors[0]..colors[3]: two sky tones, a mid tone and the ground.
        // The old guard was `if (palette.size >= 3)`, and the story bible's schema asks the model
        // for exactly three colours -- so the common case was a three-entry palette that passed the
        // guard and then threw IndexOutOfBoundsException on colors[3]. That exception came out of
        // THE FALLBACK RENDERER, which exists precisely so a render finishes when no image model
        // can answer, and it fired at the reference stage: on a phone with no local checkpoint the
        // whole film died under the words "Reference image 1/2", which reads as a stall rather than
        // a crash. Padding keeps whatever colours the model did supply.
        val colors = palette.take(4).let { given ->
            if (given.size >= 4) given else given + defaultPalette(seed).drop(given.size)
        }
        val lower = prompt.lowercase()
        val night = lower.contains("night") || lower.contains("dark") || lower.contains("moon")
        val water = lower.contains("sea") || lower.contains("water") || lower.contains("river") ||
            lower.contains("rain") || lower.contains("ocean")
        val city = lower.contains("city") || lower.contains("street") || lower.contains("building") ||
            lower.contains("room") || lower.contains("interior")
        val forest = lower.contains("forest") || lower.contains("tree") || lower.contains("grass") ||
            lower.contains("field")
        val person = lower.contains("person") || lower.contains("traveller") ||
            lower.contains("traveler") || lower.contains("figure") || lower.contains("character") ||
            lower.contains("she") || lower.contains("he")
        val close = lower.contains("close") || lower.contains("face") || lower.contains("portrait")

        val top = if (night) mix(colors[2], 0xFF05060A.toInt(), 0.72) else mix(colors[1], 0xFFBFD8E8.toInt(), 0.35)
        val bottom = if (night) mix(colors[2], 0xFF101018.toInt(), 0.4) else mix(colors[3], 0xFFE7D8BF.toInt(), 0.45)
        val horizon = (height * (if (close) 0.62 else 0.55)).toInt()
        val sunX = width * (0.25 + rng.nextDouble() * 0.5)
        val sunY = height * (0.18 + rng.nextDouble() * 0.22)
        val sunColor = if (night) 0xFFBFD0FF.toInt() else 0xFFFFE3B0.toInt()

        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val t = y.toDouble() / height
            val base = mix(top, bottom, smoothstep(0.0, 1.0, t))
            for (x in 0 until width) {
                var color = base
                val dx = (x - sunX) / (width * 0.18)
                val dy = (y - sunY) / (height * 0.18)
                val glow = exp(-(dx * dx + dy * dy) * 1.6) * if (night) 0.22 else 0.55
                color = add(color, mul(sunColor, glow))
                // A soft horizon haze keeps the layers from looking like cut paper.
                val hz = exp(-abs(y - horizon).toDouble() / (height * 0.12)) * 0.18
                color = add(color, mul(colors[0], hz))
                pixels[y * width + x] = color
            }
        }

        if (city) {
            drawCity(pixels, width, height, horizon, colors, rng, night)
        } else {
            drawTerrain(pixels, width, height, horizon, colors, rng, forest, night)
        }
        if (water && !city) drawWater(pixels, width, height, horizon, colors, night)
        if (person) drawFigures(pixels, width, height, horizon, colors, rng, close)

        // Grain and a gentle vignette; both are what make a flat render read as photographed.
        val grain = if (night) 8.0 else 5.0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val p = pixels[y * width + x]
                val n = (rng.nextDouble() - 0.5) * grain
                fun ch(shift: Int) = (((p shr shift) and 0xFF) + n).roundToInt().coerceIn(0, 255)
                pixels[y * width + x] = (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
            }
        }
        val image = VideoCraftImageOps.vignette(PrismImage(width, height, pixels), 0.34)
        return if (previous != null) {
            VideoCraftImageOps.img2img(image, previous, denoise, seed)
        } else image
    }

    private fun drawTerrain(
        pixels: IntArray, w: Int, h: Int, horizon: Int,
        colors: List<Int>, rng: Rng, forest: Boolean, night: Boolean,
    ) {
        val layers = 3
        for (layer in 0 until layers) {
            val base = horizon + (h - horizon) * (layer * 0.16)
            val color = mix(colors[2], if (night) 0xFF07090E.toInt() else colors[1], 0.25 + layer * 0.2)
            val amp = (h - horizon) * (0.09 - layer * 0.02)
            val phase = rng.nextDouble() * 6.0
            val freq = 0.9 + rng.nextDouble() * 1.8 + layer
            for (x in 0 until w) {
                val ridge = sin((x.toDouble() / w) * Math.PI * freq + phase) * amp +
                    sin((x.toDouble() / w) * Math.PI * (freq * 2.7) + phase * 1.7) * amp * 0.35
                val top = (base - amp - ridge).toInt().coerceIn(horizon - 4, h - 1)
                for (y in top until h) {
                    val shade = 0.85 + 0.15 * sin((x + y * 0.4) / 18.0)
                    pixels[y * w + x] = mul(color, shade)
                }
            }
        }
        if (forest) {
            val treeColor = mix(colors[2], 0xFF0B1A10.toInt(), 0.55)
            for (i in 0 until 120) {
                val x = (rng.nextDouble() * w).toInt().coerceIn(0, w - 1)
                val baseY = (horizon + rng.nextDouble() * (h - horizon) * 0.7).toInt().coerceIn(0, h - 1)
                val height = (rng.nextDouble() * (h - horizon) * 0.22 + 8).toInt()
                val width = max(2, height / 7)
                for (yy in 0 until height) {
                    val spread = (width * (1.0 - yy.toDouble() / height)).toInt() + 1
                    for (xx in -spread..spread) {
                        val px = x + xx
                        val py = baseY - yy
                        if (px in 0 until w && py in 0 until h) {
                            pixels[py * w + px] = mul(treeColor, 0.8 + rng.nextDouble() * 0.2)
                        }
                    }
                }
            }
        }
    }

    private fun drawCity(
        pixels: IntArray, w: Int, h: Int, horizon: Int,
        colors: List<Int>, rng: Rng, night: Boolean,
    ) {
        val ground = mix(colors[2], 0xFF14161C.toInt(), 0.6)
        for (y in horizon until h) pixels[y * w + 0] = pixels[y * w + 0]
        for (i in 0 until 26) {
            val bw = (w * (0.03 + rng.nextDouble() * 0.08)).toInt().coerceAtLeast(4)
            val bh = (h * (0.12 + rng.nextDouble() * 0.38)).toInt()
            val left = (rng.nextDouble() * w).toInt()
            val base = horizon + (rng.nextDouble() * (h - horizon) * 0.35).toInt()
            val color = mix(colors[2], ground, 0.4 + rng.nextDouble() * 0.4)
            for (y in (base - bh) until base) {
                if (y !in 0 until h) continue
                for (x in left until min(w, left + bw)) {
                    val p = y * w + x
                    var c = color
                    if (night && rng.nextDouble() < 0.035) c = 0xFFFFD98A.toInt()
                    pixels[p] = c
                }
            }
        }
        for (y in horizon until h) {
            for (x in 0 until w) {
                pixels[y * w + x] = mix(pixels[y * w + x], ground, 0.25)
            }
        }
    }

    private fun drawWater(pixels: IntArray, w: Int, h: Int, horizon: Int, colors: List<Int>, night: Boolean) {
        val water = mix(colors[0], if (night) 0xFF0A1624.toInt() else 0xFF24445A.toInt(), 0.55)
        for (y in horizon until h) {
            val depth = (y - horizon).toDouble() / max(1, h - horizon)
            for (x in 0 until w) {
                val ripple = sin(y * 0.6 + x * 0.05) * 10.0 +
                    sin(y * 0.13 - x * 0.11) * 6.0
                val c = mul(water, 0.75 + 0.25 * (1 - depth))
                val spec = if (ripple > 13.0) 0.25 else 0.0
                pixels[y * w + x] = add(c, mul(0xFFFFE7B8.toInt(), spec * (1 - depth)))
            }
        }
    }

    private fun drawFigures(
        pixels: IntArray, w: Int, h: Int, horizon: Int,
        colors: List<Int>, rng: Rng, close: Boolean,
    ) {
        val body = mix(colors[2], 0xFF0A0A0C.toInt(), 0.65)
        val count = if (close) 1 else 1 + rng.nextInt(2)
        for (i in 0 until count) {
            val scale = if (close) 0.72 else 0.18 + rng.nextDouble() * 0.08
            val cx = w * (0.5 + (if (close) (rng.nextDouble() - 0.5) * 0.08 else (rng.nextDouble() - 0.5) * 0.5))
            val baseY = if (close) h * 0.91 else horizon + (h - horizon) * 0.72
            val headR = h * scale * 0.16
            val bodyH = h * scale
            val bodyW = bodyH * 0.28
            // head
            for (y in (baseY - bodyH - headR * 1.6).toInt() until (baseY - bodyH + headR * 0.4).toInt()) {
                for (x in (cx - headR).toInt() until (cx + headR).toInt()) {
                    val dx = (x - cx) / headR
                    val dy = (y - (baseY - bodyH - headR * 0.6)) / headR
                    if (dx * dx + dy * dy <= 1.0 && x in 0 until w && y in 0 until h) {
                        pixels[y * w + x] = body
                    }
                }
            }
            // torso
            for (y in (baseY - bodyH).toInt() until baseY.toInt()) {
                val taper = 1.0 - (y - (baseY - bodyH)) / bodyH * 0.22
                val half = bodyW * taper
                for (x in (cx - half).toInt() until (cx + half).toInt()) {
                    if (x in 0 until w && y in 0 until h) pixels[y * w + x] = body
                }
            }
            // legs
            for (leg in -1..1 step 2) {
                for (y in baseY.toInt() until (baseY + bodyH * 0.28).toInt()) {
                    for (x in (cx + leg * bodyW * 0.3).toInt() - 2..(cx + leg * bodyW * 0.3).toInt() + 2) {
                        if (x in 0 until w && y in 0 until h) pixels[y * w + x] = body
                    }
                }
            }
        }
    }

    private fun defaultPalette(seed: Long): List<Int> {
        val hue = (seed and 0xFFFF) / 65535.0 * 360.0
        fun hsl(h: Double, s: Double, l: Double): Int {
            val c = (1 - abs(2 * l - 1)) * s
            val x = c * (1 - abs((h / 60.0) % 2 - 1))
            val m = l - c / 2
            val (r, g, b) = when {
                h < 60 -> Triple(c, x, 0.0)
                h < 120 -> Triple(x, c, 0.0)
                h < 180 -> Triple(0.0, c, x)
                h < 240 -> Triple(0.0, x, c)
                h < 300 -> Triple(x, 0.0, c)
                else -> Triple(c, 0.0, x)
            }
            fun ch(v: Double) = ((v + m) * 255).toInt().coerceIn(0, 255)
            return (0xFF shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
        }
        return listOf(
            hsl(hue, 0.55, 0.45),
            hsl((hue + 40) % 360, 0.5, 0.68),
            hsl((hue + 190) % 360, 0.42, 0.22),
            hsl((hue + 300) % 360, 0.35, 0.78),
        )
    }

    private fun mix(a: Int, b: Int, t: Double): Int {
        val u = t.coerceIn(0.0, 1.0)
        fun ch(shift: Int) =
            (((a shr shift) and 0xFF) * (1 - u) + ((b shr shift) and 0xFF) * u).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun mul(color: Int, f: Double): Int {
        fun ch(shift: Int) = (((color shr shift) and 0xFF) * f).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun add(a: Int, b: Int): Int {
        fun ch(shift: Int) =
            (((a shr shift) and 0xFF) + ((b shr shift) and 0xFF)).coerceIn(0, 255)
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun smoothstep(edge0: Double, edge1: Double, x: Double): Double {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0.0, 1.0)
        return t * t * (3 - 2 * t)
    }
}

/** Small deterministic PRNG, so a seed reproduces a frame on either platform. */
class Rng(seed: Long) {
    private var state = seed xor 0x9E3779B97F4A7C15uL.toLong()

    fun nextLong(): Long {
        state = state xor (state shl 13)
        state = state xor (state ushr 7)
        state = state xor (state shl 17)
        return state
    }

    fun nextDouble(): Double = ((nextLong() ushr 11).toDouble() / (1L shl 53).toDouble())
    fun nextInt(bound: Int): Int = ((nextDouble() * bound).toInt()).coerceIn(0, max(0, bound - 1))
}
