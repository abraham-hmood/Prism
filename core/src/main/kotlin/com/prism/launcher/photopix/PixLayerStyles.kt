package com.prism.launcher.photopix

import com.prism.core.PrismImage
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The ten layer effects.
 *
 * A style is a list rather than a fixed record because several effects are stackable in the real
 * panel: two drop shadows, three strokes and a colour overlay are all legal. Each effect is drawn
 * from the layer's alpha channel, so it follows every later pixel edit without being re-authored.
 */
enum class PixEffectType(val label: String) {
    DROP_SHADOW("Drop Shadow"),
    INNER_SHADOW("Inner Shadow"),
    OUTER_GLOW("Outer Glow"),
    INNER_GLOW("Inner Glow"),
    BEVEL_EMBOSS("Bevel & Emboss"),
    SATIN("Satin"),
    COLOR_OVERLAY("Color Overlay"),
    GRADIENT_OVERLAY("Gradient Overlay"),
    PATTERN_OVERLAY("Pattern Overlay"),
    STROKE("Stroke"),
}

/**
 * One effect instance.
 *
 * The field names follow the engine's vocabulary rather than a dialog: [size] is the blur or stroke
 * radius, [spread] grows the source alpha before it is blurred, and [distance]/[angle] place an
 * offset effect. A single record with unused fields per type is deliberate -- ten sealed subclasses
 * would need ten serialisers and ten copies of the same compositing code.
 */
data class PixLayerEffect(
    val type: PixEffectType,
    var enabled: Boolean = true,
    var color: Int = 0xFF000000.toInt(),
    var blend: BlendMode = BlendMode.NORMAL,
    var opacity: Double = 0.65,
    var angle: Double = 120.0,
    var distance: Double = 8.0,
    var size: Double = 12.0,
    var spread: Double = 0.0,
    var depth: Double = 1.0,
    var invert: Boolean = false,
    var gradientFrom: Int = 0xFFFFFFFF.toInt(),
    var gradientTo: Int = 0xFF000000.toInt(),
    var gradientStyle: Int = 0,
    var patternScale: Int = 8,
    var patternKind: Int = 0,
    /** 0 outside, 1 inside, 2 centre. Only meaningful for [PixEffectType.STROKE]. */
    var strokePosition: Int = 0,
    /** 0 colour, 1 gradient, 2 pattern. Only meaningful for [PixEffectType.STROKE]. */
    var fillKind: Int = 0,
)

class PixLayerStyle(val effects: MutableList<PixLayerEffect> = mutableListOf()) {

    fun effect(type: PixEffectType): PixLayerEffect? = effects.firstOrNull { it.type == type }

    fun has(type: PixEffectType): Boolean = effects.any { it.type == type && it.enabled }

    fun add(type: PixEffectType, configure: (PixLayerEffect) -> Unit = {}): PixLayerEffect {
        val effect = default(type).also(configure)
        effects.add(effect)
        return effect
    }

    fun remove(type: PixEffectType) {
        effects.removeAll { it.type == type }
    }

    fun copyOf(): PixLayerStyle = PixLayerStyle(effects.map { it.copy() }.toMutableList())

    val isEmpty: Boolean get() = effects.none { it.enabled }

    companion object {
        fun default(type: PixEffectType): PixLayerEffect = when (type) {
            PixEffectType.DROP_SHADOW -> PixLayerEffect(
                type, color = 0xFF000000.toInt(), opacity = 0.65,
                angle = 120.0, distance = 8.0, size = 16.0,
            )
            PixEffectType.INNER_SHADOW -> PixLayerEffect(
                type, color = 0xFF000000.toInt(), opacity = 0.6,
                angle = 120.0, distance = 5.0, size = 8.0,
            )
            PixEffectType.OUTER_GLOW -> PixLayerEffect(
                type, color = 0xFFFFE9A8.toInt(), blend = BlendMode.SCREEN,
                opacity = 0.6, size = 18.0, spread = 0.2,
            )
            PixEffectType.INNER_GLOW -> PixLayerEffect(
                type, color = 0xFFFFF1C8.toInt(), blend = BlendMode.SCREEN,
                opacity = 0.55, size = 12.0,
            )
            PixEffectType.BEVEL_EMBOSS -> PixLayerEffect(
                type, color = 0xFFFFFFFF.toInt(), opacity = 0.7,
                angle = 120.0, size = 9.0, depth = 1.0,
            )
            PixEffectType.SATIN -> PixLayerEffect(
                type, color = 0xFF000000.toInt(), opacity = 0.4,
                angle = 19.0, distance = 11.0, size = 14.0,
            )
            PixEffectType.COLOR_OVERLAY -> PixLayerEffect(
                type, color = 0xFFFF3B30.toInt(), opacity = 1.0,
            )
            PixEffectType.GRADIENT_OVERLAY -> PixLayerEffect(
                type, opacity = 0.8, angle = 90.0,
                gradientFrom = 0xFFFFFFFF.toInt(), gradientTo = 0xFF202020.toInt(),
            )
            PixEffectType.PATTERN_OVERLAY -> PixLayerEffect(
                type, opacity = 0.55, patternScale = 8, patternKind = 0,
            )
            PixEffectType.STROKE -> PixLayerEffect(
                type, color = 0xFF000000.toInt(), opacity = 1.0,
                size = 3.0, strokePosition = 0, fillKind = 0,
            )
        }

        /** The presets the Styles panel exposes, named for their look. */
        fun preset(name: String): PixLayerStyle = PixLayerStyle().also { style ->
            when (name.lowercase()) {
                "drop shadow" -> style.add(PixEffectType.DROP_SHADOW)
                "glow" -> style.add(PixEffectType.OUTER_GLOW)
                "stroke" -> style.add(PixEffectType.STROKE)
                "emboss" -> style.add(PixEffectType.BEVEL_EMBOSS)
                "satin" -> style.add(PixEffectType.SATIN)
                "gradient" -> style.add(PixEffectType.GRADIENT_OVERLAY)
                "colour overlay", "color overlay" -> style.add(PixEffectType.COLOR_OVERLAY)
                "pattern" -> style.add(PixEffectType.PATTERN_OVERLAY)
                "inner shadow" -> style.add(PixEffectType.INNER_SHADOW)
                "inner glow" -> style.add(PixEffectType.INNER_GLOW)
                else -> {
                    style.add(PixEffectType.DROP_SHADOW)
                    style.add(PixEffectType.STROKE) { it.size = 2.0 }
                }
            }
        }
    }
}

/**
 * Turns a layer and its style into the image the document composites.
 *
 * Beneath-effects are rendered into a separate underlay and over-effects into an overlay; the
 * result is underlay, then the layer's own pixels, then overlay. That ordering is the whole point
 * of a style system: a drop shadow has to sit behind the pixels, an inner shadow has to sit on top
 * of them, and both have to see the same alpha.
 */
object PixLayerStyles {

    fun render(layer: PrismImage, style: PixLayerStyle?): PrismImage {
        if (style == null || style.isEmpty) return layer
        val alpha = alphaOf(layer)
        val width = layer.width
        val height = layer.height
        val under = PrismImage(width, height, IntArray(width * height))
        val over = PrismImage(width, height, IntArray(width * height))

        // Beneath effects, back to front.
        style.effects.filter { it.enabled }.forEach { effect ->
            when (effect.type) {
                PixEffectType.DROP_SHADOW -> {
                    val grown = spread(alpha, width, height, (effect.spread / 100.0 * effect.size).toInt())
                    val shifted = offset(grown, width, height, polarX(effect), polarY(effect))
                    val blurred = blurAlpha(shifted, width, height, effect.size)
                    draw(under, coloured(blurred, width, height, effect.color, effect.opacity), effect.blend)
                }
                PixEffectType.OUTER_GLOW -> {
                    val grown = spread(alpha, width, height, (effect.spread / 100.0 * effect.size).toInt())
                    val blurred = blurAlpha(grown, width, height, effect.size)
                    val outside = DoubleArray(blurred.size) { max(0.0, blurred[it] - alpha[it]) }
                    draw(over, coloured(outside, width, height, effect.color, effect.opacity), effect.blend)
                }
                else -> Unit
            }
        }

        // The layer itself.
        PixImageOps.draw(under, layer)

        // Above effects, in the order the panel lists them.
        style.effects.filter { it.enabled }.forEach { effect ->
            when (effect.type) {
                PixEffectType.INNER_SHADOW -> {
                    val inverse = DoubleArray(alpha.size) { 1.0 - alpha[it] }
                    val shifted = offset(inverse, width, height, polarX(effect), polarY(effect))
                    val blurred = blurAlpha(shifted, width, height, effect.size)
                    val clipped = DoubleArray(alpha.size) { blurred[it] * alpha[it] }
                    draw(over, coloured(clipped, width, height, effect.color, effect.opacity), effect.blend)
                }
                PixEffectType.INNER_GLOW -> {
                    val inverse = DoubleArray(alpha.size) { 1.0 - alpha[it] }
                    val blurred = blurAlpha(inverse, width, height, effect.size)
                    val clipped = DoubleArray(alpha.size) { blurred[it] * alpha[it] }
                    draw(over, coloured(clipped, width, height, effect.color, effect.opacity), effect.blend)
                }
                PixEffectType.BEVEL_EMBOSS -> drawBevel(over, alpha, width, height, effect)
                PixEffectType.SATIN -> drawSatin(over, alpha, width, height, effect)
                PixEffectType.COLOR_OVERLAY -> {
                    val mask = if (effect.invert) DoubleArray(alpha.size) { 1.0 - alpha[it] } else alpha
                    draw(over, coloured(mask, width, height, effect.color, effect.opacity), effect.blend)
                }
                PixEffectType.GRADIENT_OVERLAY -> drawGradient(over, alpha, width, height, effect)
                PixEffectType.PATTERN_OVERLAY -> drawPattern(over, alpha, width, height, effect)
                PixEffectType.STROKE -> drawStroke(over, alpha, width, height, effect)
                else -> Unit
            }
        }
        PixImageOps.draw(under, over)
        return under
    }

    // ── Alpha plumbing ────────────────────────────────────────────────────

    fun alphaOf(image: PrismImage): DoubleArray {
        val out = DoubleArray(image.pixels.size)
        for (i in out.indices) out[i] = ((image.pixels[i] ushr 24) and 0xFF) / 255.0
        return out
    }

    private fun coloured(
        alpha: DoubleArray,
        width: Int,
        height: Int,
        color: Int,
        opacity: Double,
    ): PrismImage {
        val pixels = IntArray(width * height)
        val a = opacity.coerceIn(0.0, 1.0)
        for (i in pixels.indices) {
            val coverage = (alpha[i] * a).coerceIn(0.0, 1.0)
            if (coverage <= 0.0) continue
            pixels[i] = (color and 0x00FFFFFF) or (((coverage * 255).toInt().coerceIn(0, 255)) shl 24)
        }
        return PrismImage(width, height, pixels)
    }

    private fun draw(target: PrismImage, source: PrismImage, mode: BlendMode) {
        PixImageOps.draw(target, source, 0, 0, mode, 1.0)
    }

    private fun blurAlpha(alpha: DoubleArray, width: Int, height: Int, size: Double): DoubleArray {
        val radius = size.toInt().coerceIn(1, 100)
        val image = coloured(alpha, width, height, 0xFFFFFFFF.toInt(), 1.0)
        val blurred = PixFilters.gaussianBlur(image, null, radius)
        return alphaOf(blurred)
    }

    /** Grows the alpha when [radius] is positive and erodes it when negative. */
    private fun spread(alpha: DoubleArray, width: Int, height: Int, radius: Int): DoubleArray {
        if (radius == 0) return alpha
        val dilate = radius > 0
        val r = kotlin.math.abs(radius).coerceIn(0, 60)
        val horizontal = DoubleArray(alpha.size)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                var best = if (dilate) 0.0 else 1.0
                for (dx in -r..r) {
                    val sx = (x + dx).coerceIn(0, width - 1)
                    best = if (dilate) max(best, alpha[row + sx]) else min(best, alpha[row + sx])
                }
                horizontal[row + x] = best
            }
        }
        val out = DoubleArray(alpha.size)
        for (y in 0 until height) for (x in 0 until width) {
            var best = if (dilate) 0.0 else 1.0
            for (dy in -r..r) {
                val sy = (y + dy).coerceIn(0, height - 1)
                val value = horizontal[sy * width + x]
                best = if (dilate) max(best, value) else min(best, value)
            }
            out[y * width + x] = best
        }
        return out
    }

    private fun offset(alpha: DoubleArray, width: Int, height: Int, dx: Int, dy: Int): DoubleArray {
        if (dx == 0 && dy == 0) return alpha
        val out = DoubleArray(alpha.size)
        for (y in 0 until height) {
            val ty = y + dy
            if (ty < 0 || ty >= height) continue
            for (x in 0 until width) {
                val tx = x + dx
                if (tx < 0 || tx >= width) continue
                out[ty * width + tx] = alpha[y * width + x]
            }
        }
        return out
    }

    // The angle names the LIGHT direction: 120 degrees means upper-left, so the shadow falls to
    // the lower-right. Negating the light vector is what makes that convention work.
    private fun polarX(effect: PixLayerEffect): Int =
        (-cos(Math.toRadians(effect.angle)) * effect.distance).toInt()

    private fun polarY(effect: PixLayerEffect): Int =
        (sin(Math.toRadians(effect.angle)) * effect.distance).toInt()

    // ── The effects that need their own paint ─────────────────────────────

    private fun drawBevel(
        target: PrismImage,
        alpha: DoubleArray,
        width: Int,
        height: Int,
        effect: PixLayerEffect,
    ) {
        val radius = effect.size.toInt().coerceIn(2, 60)
        val soft = blurAlpha(alpha, width, height, effect.size)
        val lx = cos(Math.toRadians(effect.angle)).toFloat()
        val ly = sin(Math.toRadians(effect.angle)).toFloat()
        val pixels = IntArray(width * height)
        val depth = effect.depth.coerceIn(0.1, 3.0)
        for (y in 0 until height) for (x in 0 until width) {
            val index = y * width + x
            if (alpha[index] <= 0.0) continue
            val left = soft[y * width + max(0, x - radius)]
            val right = soft[y * width + min(width - 1, x + radius)]
            val up = soft[max(0, y - radius) * width + x]
            val down = soft[min(height - 1, y + radius) * width + x]
            // Positive where the surface faces the light, negative where it turns away.
            val light = ((right - left) * lx + (down - up) * ly) * depth * 1.5
            val highlight = light.coerceAtLeast(0.0) * effect.opacity
            val shadow = (-light).coerceAtLeast(0.0) * effect.opacity
            if (highlight <= 0.001 && shadow <= 0.001) continue
            val base = if (highlight > 0) 0xFFFFFF else 0x000000
            val strength = (highlight + shadow).coerceIn(0.0, 1.0)
            pixels[index] = (base and 0x00FFFFFF) or ((strength * 255).toInt().coerceIn(0, 255) shl 24)
        }
        draw(target, PrismImage(width, height, pixels), effect.blend)
    }

    private fun drawSatin(
        target: PrismImage,
        alpha: DoubleArray,
        width: Int,
        height: Int,
        effect: PixLayerEffect,
    ) {
        val dx = polarX(effect)
        val dy = polarY(effect)
        val shifted = offset(alpha, width, height, dx, dy)
        val blurred = blurAlpha(shifted, width, height, effect.size)
        val mask = DoubleArray(alpha.size) { i ->
            val difference = Math.abs(blurred[i] - alpha[i])
            (difference * 2.0 * effect.opacity).coerceIn(0.0, alpha[i])
        }
        draw(target, coloured(mask, width, height, effect.color, 1.0), effect.blend)
    }

    private fun drawGradient(
        target: PrismImage,
        alpha: DoubleArray,
        width: Int,
        height: Int,
        effect: PixLayerEffect,
    ) {
        var minX = width
        var minY = height
        var maxX = 0
        var maxY = 0
        for (y in 0 until height) for (x in 0 until width) {
            if (alpha[y * width + x] > 0.0) {
                if (x < minX) minX = x
                if (y < minY) minY = y
                if (x > maxX) maxX = x
                if (y > maxY) maxY = y
            }
        }
        if (maxX < minX || maxY < minY) return
        val radians = Math.toRadians(effect.angle)
        val dx = cos(radians)
        val dy = sin(radians)
        val pixels = IntArray(width * height)
        for (y in 0 until height) for (x in 0 until width) {
            val index = y * width + x
            if (alpha[index] <= 0.0) continue
            val nx = (x - minX) / (maxX - minX + 1).toDouble()
            val ny = (y - minY) / (maxY - minY + 1).toDouble()
            val t = when (effect.gradientStyle) {
                1 -> Math.hypot(nx - 0.5, ny - 0.5) * 1.4
                2 -> Math.atan2(ny - 0.5, nx - 0.5) / (2 * Math.PI) + 0.5
                else -> ((nx - 0.5) * dx + (ny - 0.5) * dy + 0.5).coerceIn(0.0, 1.0)
            }
            val color = mixColors(effect.gradientFrom, effect.gradientTo, t.coerceIn(0.0, 1.0))
            val coverage = alpha[index] * effect.opacity
            pixels[index] = (color and 0x00FFFFFF) or ((coverage * 255).toInt().coerceIn(0, 255) shl 24)
        }
        draw(target, PrismImage(width, height, pixels), effect.blend)
    }

    private fun drawPattern(
        target: PrismImage,
        alpha: DoubleArray,
        width: Int,
        height: Int,
        effect: PixLayerEffect,
    ) {
        val scale = effect.patternScale.coerceIn(2, 64)
        val pixels = IntArray(width * height)
        for (y in 0 until height) for (x in 0 until width) {
            val index = y * width + x
            if (alpha[index] <= 0.0) continue
            val on = when (effect.patternKind) {
                1 -> ((x + y) / scale) % 2 == 0
                2 -> (x % scale - scale / 2) * (x % scale - scale / 2) +
                    (y % scale - scale / 2) * (y % scale - scale / 2) < (scale / 2) * (scale / 2)
                else -> (x / scale + y / scale) % 2 == 0
            }
            val color = if (on) 0xFFFFFFFF.toInt() else 0xFF303030.toInt()
            val coverage = alpha[index] * effect.opacity
            pixels[index] = (color and 0x00FFFFFF) or ((coverage * 255).toInt().coerceIn(0, 255) shl 24)
        }
        draw(target, PrismImage(width, height, pixels), effect.blend)
    }

    private fun drawStroke(
        target: PrismImage,
        alpha: DoubleArray,
        width: Int,
        height: Int,
        effect: PixLayerEffect,
    ) {
        val r = effect.size.toInt().coerceIn(1, 60)
        val grown = spread(alpha, width, height, r)
        val shrunk = spread(alpha, width, height, -r)
        val pixels = IntArray(width * height)
        val radians = Math.toRadians(effect.angle)
        val dx = cos(radians)
        val dy = sin(radians)
        for (y in 0 until height) for (x in 0 until width) {
            val index = y * width + x
            val coverage = when (effect.strokePosition) {
                1 -> (alpha[index] - shrunk[index]).coerceIn(0.0, 1.0)
                2 -> {
                    val outer = (grown[index] - alpha[index]).coerceAtLeast(0.0)
                    val inner = (alpha[index] - shrunk[index]).coerceAtLeast(0.0)
                    (outer + inner).coerceIn(0.0, 1.0)
                }
                else -> (grown[index] - alpha[index]).coerceIn(0.0, 1.0)
            }
            if (coverage <= 0.0) continue
            val color = when (effect.fillKind) {
                1 -> {
                    val t = (((x / width.toDouble()) - 0.5) * dx + ((y / height.toDouble()) - 0.5) * dy + 0.5)
                        .coerceIn(0.0, 1.0)
                    mixColors(effect.gradientFrom, effect.gradientTo, t)
                }
                2 -> if ((x / effect.patternScale.coerceAtLeast(2) + y / effect.patternScale.coerceAtLeast(2)) % 2 == 0) {
                    0xFFFFFFFF.toInt()
                } else {
                    0xFF303030.toInt()
                }
                else -> effect.color
            }
            val strength = (coverage * effect.opacity).coerceIn(0.0, 1.0)
            pixels[index] = (color and 0x00FFFFFF) or ((strength * 255).toInt().coerceIn(0, 255) shl 24)
        }
        draw(target, PrismImage(width, height, pixels), effect.blend)
    }
}
