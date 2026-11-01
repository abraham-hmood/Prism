package com.prism.launcher.photopix

import com.prism.core.PrismImage
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The Filter Gallery.
 *
 * Forty-seven effects, grouped the way the gallery groups them. Several share primitives -- posterise,
 * edge detect, grain, emboss -- but each has its own composition, because the point of a gallery is
 * that Watercolor and Fresco do not produce the same picture with a different label.
 */
object PixGallery {

    data class Entry(val group: String, val name: String, val apply: (PrismImage, PixSelection?, Int) -> PrismImage)

    private fun posterize(image: PrismImage, levels: Int): PrismImage =
        PixAdjustments.posterize(image, null, levels)

    private fun edges(image: PrismImage): PrismImage = PixFilters.findEdges(image, null)

    private fun gray(image: PrismImage): PrismImage = PixAdjustments.desaturate(image, null)

    private fun blend(base: PrismImage, top: PrismImage, mode: BlendMode, opacity: Double): PrismImage =
        PixFiltersExtra.blendLayer(base, top, mode, opacity)

    private fun grain(image: PrismImage, amount: Double, mono: Boolean = true): PrismImage =
        PixFilters.addNoise(image, null, amount, mono)

    private fun soft(image: PrismImage, radius: Int): PrismImage = PixFilters.gaussianBlur(image, null, radius)

    private fun tint(image: PrismImage, color: Int, amount: Double): PrismImage {
        val out = image.copy()
        val target = Rgba.of(color)
        for (i in out.pixels.indices) {
            val px = Rgba.of(out.pixels[i])
            out.pixels[i] = Rgba(
                px.r + (target.r - px.r) * amount,
                px.g + (target.g - px.g) * amount,
                px.b + (target.b - px.b) * amount,
                px.a,
            ).packed()
        }
        return out
    }

    private fun diagonalPattern(image: PrismImage, spacing: Int, thickness: Int, dark: Boolean): PrismImage {
        val out = image.copy()
        val step = spacing.coerceIn(2, 40)
        val ink = thickness.coerceIn(1, step)
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val band = (x + y) % step
            if (band >= ink) continue
            val index = y * image.width + x
            val px = Rgba.of(image.pixels[index])
            val factor = if (dark) 0.55 else 1.35
            out.pixels[index] = Rgba(
                (px.r * factor).coerceIn(0.0, 255.0),
                (px.g * factor).coerceIn(0.0, 255.0),
                (px.b * factor).coerceIn(0.0, 255.0),
                px.a,
            ).packed()
        }
        return out
    }

    private fun hash(seed: Long): Double {
        var state = seed * 6364136223846793005L + 1442695040888963407L
        state = state xor (state ushr 33)
        return (state ushr 11).toDouble() / (1L shl 53).toDouble()
    }

    // ── Artistic ──────────────────────────────────────────────────────────

    private fun coloredPencil(image: PrismImage, selection: PixSelection?, strength: Int): PrismImage {
        val detail = 1.0 + strength / 100.0
        val line = edges(image)
        val out = image.copy()
        for (i in out.pixels.indices) {
            val a = Rgba.of(image.pixels[i])
            val e = Rgba.of(line.pixels[i])
            val paper = 1.0 - (e.luma / 255.0).pow(0.6)
            out.pixels[i] = Rgba(
                (255 - (255 - a.r) * paper * detail).coerceIn(0.0, 255.0),
                (255 - (255 - a.g) * paper * detail).coerceIn(0.0, 255.0),
                (255 - (255 - a.b) * paper * detail).coerceIn(0.0, 255.0),
                a.a,
            ).packed()
        }
        return out
    }

    private fun cutout(image: PrismImage, selection: PixSelection?, strength: Int): PrismImage {
        val levels = (8 - strength / 15).coerceIn(3, 8)
        return posterize(soft(image, 1), levels)
    }

    private fun dryBrush(image: PrismImage, selection: PixSelection?, strength: Int): PrismImage {
        val painted = PixFilters.oilPaint(image, null, 2, 10)
        val poster = posterize(painted, (6 - strength / 25).coerceIn(2, 6))
        return grain(poster, 6.0)
    }

    private fun filmGrain(image: PrismImage, selection: PixSelection?, strength: Int): PrismImage {
        val mono = PixAdjustments.desaturate(image, null)
        return grain(blend(image, mono, BlendMode.LUMINOSITY, 1.0), strength.coerceIn(4, 60).toDouble())
    }

    private fun fresco(image: PrismImage, selection: PixSelection?, strength: Int): PrismImage {
        val base = PixFilters.median(image, null, 2)
        val textured = PixFilters.emboss(base, null, 135.0, 2)
        return blend(posterize(base, 5), textured, BlendMode.OVERLAY, 0.35 + strength / 300.0)
    }

    private fun neonGlow(image: PrismImage, selection: PixSelection?, strength: Int): PrismImage {
        val line = edges(image)
        val glow = soft(line, (2 + strength / 20).coerceIn(2, 12))
        val coloured = blend(image, glow, BlendMode.SCREEN, 0.9)
        return blend(coloured, line, BlendMode.SCREEN, 0.6)
    }

    private fun paintDaubs(image: PrismImage, selection: PixSelection?, strength: Int): PrismImage =
        posterize(PixFilters.oilPaint(image, null, (1 + strength / 20).coerceIn(1, 6), 18), 10)

    private fun paletteKnife(image: PrismImage, selection: PixSelection?, strength: Int): PrismImage {
        val smeared = PixFilters.oilPaint(image, null, (6 - strength / 25).coerceIn(2, 8), 12)
        return blend(smeared, PixFilters.highPass(image, null, 8), BlendMode.OVERLAY, 0.35)
    }

    private fun plasticWrap(image: PrismImage, selection: PixSelection?, strength: Int): PrismImage {
        val high = PixFilters.highPass(image, null, 12)
        val lit = PixFiltersExtra.lightingEffects(high, null, 60, 120, 70, 10)
        return blend(image, lit, BlendMode.SOFT_LIGHT, 0.5 + strength / 400.0)
    }

    private fun posterEdges(image: PrismImage, selection: PixSelection?, strength: Int): PrismImage {
        val poster = posterize(image, 6)
        val line = edges(image)
        val darkened = blend(poster, line, BlendMode.MULTIPLY, 0.4)
        return darkened
    }

    private fun roughPastels(image: PrismImage, selection: PixSelection?, strength: Int): PrismImage {
        val base = grain(soft(image, 1), 12.0, mono = false)
        return blend(base, edges(image), BlendMode.MULTIPLY, 0.25 + strength / 300.0)
    }

    private fun smudgeStick(image: PrismImage, selection: PixSelection?, strength: Int): PrismImage {
        val smudged = PixFilters.motionBlur(image, null, 45.0, (2 + strength / 10).coerceIn(2, 12))
        return tint(smudged, 0xFF3A2A20.toInt(), 0.08)
    }

    private fun sponge(image: PrismImage, selection: PixSelection?, strength: Int): PrismImage {
        val contrast = PixAdjustments.brightnessContrast(image, null, 6.0, 20.0)
        val noisy = grain(contrast, 18.0, mono = false)
        return PixAdjustments.desaturate(noisy, null).let { blend(it, noisy, BlendMode.COLOR, 0.45) }
    }

    private fun underpainting(image: PrismImage, selection: PixSelection?, strength: Int): PrismImage {
        val base = posterize(PixFilters.median(image, null, 3), 6)
        return blend(base, soft(image, 3), BlendMode.SOFT_LIGHT, 0.6)
    }

    private fun watercolor(image: PrismImage, selection: PixSelection?, strength: Int): PrismImage {
        val washed = posterize(PixFilters.median(image, null, 2), 7)
        val pooled = soft(washed, 1)
        val line = edges(image)
        val lined = blend(pooled, line, BlendMode.MULTIPLY, 0.18)
        return blend(lined, PixFilters.diffuseGlow(pooled, null, 6, 30.0), BlendMode.SCREEN, 0.25)
    }

    // ── Brush Strokes ─────────────────────────────────────────────────────

    private fun accentedEdges(image: PrismImage, selection: PixSelection?, strength: Int): PrismImage =
        blend(soft(image, 1), edges(image), BlendMode.SCREEN, 0.45)

    private fun angledStrokes(image: PrismImage, selection: PixSelection?, strength: Int): PrismImage {
        val blurred = PixFilters.motionBlur(image, null, 45.0, (3 + strength / 15).coerceIn(3, 18))
        return PixFilters.unsharpMask(blurred, null, 1, 60.0, 0)
    }

    private fun crosshatch(image: PrismImage, selection: PixSelection?, strength: Int): PrismImage {
        val first = diagonalPattern(image, (6 + strength / 20).coerceIn(4, 14), 1, dark = true)
        return diagonalPattern(first, (9 + strength / 15).coerceIn(6, 18), 1, dark = true)
    }

    private fun darkStrokes(image: PrismImage, selection: PixSelection?, strength: Int): PrismImage {
        val dark = PixAdjustments.brightnessContrast(image, null, -18.0, 30.0)
        val lines = PixFilters.motionBlur(edges(image), null, 30.0, 3)
        return blend(dark, lines, BlendMode.MULTIPLY, 0.5)
    }

    private fun inkOutlines(image: PrismImage, selection: PixSelection?, strength: Int): PrismImage {
        val thick = soft(edges(image), 1)
        return blend(image, thick, BlendMode.MULTIPLY, 0.7)
    }

    private fun spatter(image: PrismImage, selection: PixSelection?, strength: Int): PrismImage {
        val out = image.copy()
        var state = 7717L
        fun next(): Double {
            state = state * 6364136223846793005L + 1442695040888963407L
            return (state ushr 11).toDouble() / (1L shl 53).toDouble()
        }
        val count = (strength.coerceIn(1, 100) * 40)
        repeat(count) {
            val x = (next() * image.width).toInt().coerceIn(0, image.width - 1)
            val y = (next() * image.height).toInt().coerceIn(0, image.height - 1)
            val radius = (1 + next() * 2).toInt()
            val colour = image.pixels[y * image.width + x]
            for (dy in -radius..radius) for (dx in -radius..radius) {
                if (dx * dx + dy * dy > radius * radius) continue
                val px = x + dx
                val py = y + dy
                if (px in 0 until image.width && py in 0 until image.height) {
                    out.pixels[py * image.width + px] = colour
                }
            }
        }
        return out
    }

    private fun sprayedStrokes(image: PrismImage, selection: PixSelection?, strength: Int): PrismImage {
        val smeared = PixFilters.motionBlur(image, null, 0.0, (4 + strength / 12).coerceIn(4, 20))
        return grain(smeared, 10.0, mono = false)
    }

    private fun sumiE(image: PrismImage, selection: PixSelection?, strength: Int): PrismImage {
        val ink = posterize(PixAdjustments.threshold(image, null, 96), 3)
        return soft(ink, (1 + strength / 30).coerceIn(1, 5))
    }

    // ── Distort ───────────────────────────────────────────────────────────

    private fun glass(image: PrismImage, selection: PixSelection?, strength: Int): PrismImage {
        val map = PrismImage(image.width, image.height, IntArray(image.pixels.size) { i ->
            val x = i % image.width
            val y = i / image.width
            val n = ((hash((x * 73856093L) xor (y * 19349663L)) * 255).toInt() and 0xFF)
            (255 shl 24) or (n shl 16) or (((n * 3) % 255) shl 8) or ((255 - n))
        })
        val warped = PixFiltersExtra.displace(image, map, strength / 2, strength / 2)
        return blend(warped, edges(image), BlendMode.SCREEN, 0.2)
    }

    private fun oceanRipple(image: PrismImage, selection: PixSelection?, strength: Int): PrismImage {
        var current = PixFiltersExtra.ripple(image, null, strength / 3, 8)
        current = PixFiltersExtra.ripple(current, null, strength / 5, 5)
        return blend(current, soft(image, 1), BlendMode.SOFT_LIGHT, 0.25)
    }

    // ── Sketch ────────────────────────────────────────────────────────────

    private fun basRelief(image: PrismImage, selection: PixSelection?, strength: Int): PrismImage =
        PixFilters.emboss(gray(image), null, 135.0, (2 + strength / 30).coerceIn(2, 8))

    private fun chalkCharcoal(image: PrismImage, selection: PixSelection?, strength: Int): PrismImage {
        val grainy = grain(gray(image), 22.0)
        return blend(grainy, edges(image), BlendMode.MULTIPLY, 0.45)
    }

    private fun charcoal(image: PrismImage, selection: PixSelection?, strength: Int): PrismImage {
        val dark = PixAdjustments.threshold(gray(image), null, 90)
        return PixFilters.motionBlur(dark, null, 60.0, (2 + strength / 25).coerceIn(2, 8))
    }

    private fun chrome(image: PrismImage, selection: PixSelection?, strength: Int): PrismImage {
        val high = PixFilters.highPass(gray(image), null, (4 + strength / 15).coerceIn(4, 14))
        return PixAdjustments.levels(high, null, 70, 210, 1.1, 0, 255)
    }

    private fun conteCrayon(image: PrismImage, selection: PixSelection?, strength: Int): PrismImage {
        val paper = tint(gray(image), 0xFFE8D8C0.toInt(), 0.25)
        return blend(paper, edges(image), BlendMode.MULTIPLY, 0.4)
    }

    private fun graphicPen(image: PrismImage, selection: PixSelection?, strength: Int): PrismImage {
        val lines = diagonalPattern(gray(image), (4 + strength / 20).coerceIn(3, 10), 1, dark = true)
        return PixAdjustments.threshold(lines, null, 150)
    }

    private fun halftonePattern(image: PrismImage, selection: PixSelection?, strength: Int): PrismImage {
        val halftone = PixFiltersExtra.colorHalftone(gray(image), null, (2 + strength / 25).coerceIn(2, 6))
        return blend(halftone, image, BlendMode.LUMINOSITY, 0.4)
    }

    private fun notePaper(image: PrismImage, selection: PixSelection?, strength: Int): PrismImage {
        val embossed = PixFilters.emboss(gray(image), null, 135.0, 2)
        val paper = tint(embossed, 0xFFF2E8D5.toInt(), 0.35)
        return grain(paper, 8.0)
    }

    private fun photocopy(image: PrismImage, selection: PixSelection?, strength: Int): PrismImage {
        val contrast = PixAdjustments.brightnessContrast(gray(image), null, 10.0, 45.0)
        return grain(PixAdjustments.threshold(contrast, null, 150 - strength / 4), 12.0)
    }

    private fun plaster(image: PrismImage, selection: PixSelection?, strength: Int): PrismImage {
        val base = soft(gray(image), 1)
        val texture = PixFilters.emboss(base, null, 45.0, 3)
        return blend(base, texture, BlendMode.SOFT_LIGHT, 0.7)
    }

    private fun reticulation(image: PrismImage, selection: PixSelection?, strength: Int): PrismImage {
        val noisy = grain(gray(image), 30.0)
        return PixAdjustments.brightnessContrast(noisy, null, 10.0, 40.0)
    }

    private fun stamp(image: PrismImage, selection: PixSelection?, strength: Int): PrismImage {
        val threshold = PixAdjustments.threshold(gray(image), null, 128)
        return soft(threshold, 1)
    }

    private fun tornEdges(image: PrismImage, selection: PixSelection?, strength: Int): PrismImage {
        val threshold = PixAdjustments.threshold(gray(image), null, 120)
        val out = threshold.copy()
        for (y in 0 until out.height) for (x in 0 until out.width) {
            val edge = min(min(x, y), min(out.width - 1 - x, out.height - 1 - y))
            val tear = (hash((x * 31 + y * 17).toLong()) * 12).toInt()
            if (edge < tear) out.pixels[y * out.width + x] = 0xFFFFFFFF.toInt()
        }
        return out
    }

    private fun waterPaper(image: PrismImage, selection: PixSelection?, strength: Int): PrismImage {
        val washed = soft(image, 2)
        val texture = grain(gray(image), 16.0)
        return blend(washed, texture, BlendMode.SOFT_LIGHT, 0.35)
    }

    // ── Stylize ───────────────────────────────────────────────────────────

    private fun galleryGlowingEdges(image: PrismImage, selection: PixSelection?, strength: Int): PrismImage =
        PixFiltersExtra.glowingEdges(image, null, (1 + strength / 25).coerceIn(1, 8))

    // ── Texture ───────────────────────────────────────────────────────────

    private fun craquelure(image: PrismImage, selection: PixSelection?, strength: Int): PrismImage {
        val out = image.copy()
        val spacing = (12 + strength / 5).coerceIn(8, 40)
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val crack = (x % spacing == 0 || y % spacing == 0 ||
                (x + y) % (spacing * 2) == 0)
            if (!crack) continue
            val index = y * image.width + x
            val px = Rgba.of(image.pixels[index])
            out.pixels[index] = Rgba(px.r * 0.72, px.g * 0.72, px.b * 0.72, px.a).packed()
        }
        return out
    }

    private fun galleryGrain(image: PrismImage, selection: PixSelection?, strength: Int): PrismImage {
        val stretched = PixFilters.motionBlur(image, null, 90.0, 2)
        return grain(stretched, strength.coerceIn(5, 50).toDouble())
    }

    private fun mosaicTiles(image: PrismImage, selection: PixSelection?, strength: Int): PrismImage {
        val block = (6 + strength / 12).coerceIn(6, 30)
        val tiled = PixFilters.mosaic(image, null, block)
        val out = tiled.copy()
        for (y in 0 until out.height) for (x in 0 until out.width) {
            if (x % block == 0 || y % block == 0) {
                val index = y * out.width + x
                val px = Rgba.of(out.pixels[index])
                out.pixels[index] = Rgba(px.r * 0.65, px.g * 0.65, px.b * 0.65, px.a).packed()
            }
        }
        return out
    }

    private fun patchwork(image: PrismImage, selection: PixSelection?, strength: Int): PrismImage {
        val block = (8 + strength / 12).coerceIn(8, 32)
        val out = image.copy()
        for (by in 0 until image.height step block) for (bx in 0 until image.width step block) {
            val x = (bx + block / 2).coerceIn(0, image.width - 1)
            val y = (by + block / 2).coerceIn(0, image.height - 1)
            val colour = image.pixels[y * image.width + x]
            for (yy in by until min(by + block, image.height)) {
                for (xx in bx until min(bx + block, image.width)) {
                    out.pixels[yy * image.width + xx] =
                        if (xx - bx < 2 || yy - by < 2) 0xFF202020.toInt() else colour
                }
            }
        }
        return out
    }

    private fun stainedGlass(image: PrismImage, selection: PixSelection?, strength: Int): PrismImage {
        val crystallized = PixFiltersExtra.crystallize(image, null, (4 + strength / 12).coerceIn(4, 18))
        return blend(crystallized, edges(image), BlendMode.MULTIPLY, 0.6)
    }

    private fun texturizer(image: PrismImage, selection: PixSelection?, strength: Int): PrismImage {
        val canvas = diagonalPattern(image, 4, 1, dark = false)
        val crossed = diagonalPattern(canvas, 5, 1, dark = true)
        return blend(image, crossed, BlendMode.SOFT_LIGHT, 0.4 + strength / 300.0)
    }

    val entries: List<Entry> = listOf(
        Entry("Artistic", "Colored Pencil", ::coloredPencil),
        Entry("Artistic", "Cutout", ::cutout),
        Entry("Artistic", "Dry Brush", ::dryBrush),
        Entry("Artistic", "Film Grain", ::filmGrain),
        Entry("Artistic", "Fresco", ::fresco),
        Entry("Artistic", "Neon Glow", ::neonGlow),
        Entry("Artistic", "Paint Daubs", ::paintDaubs),
        Entry("Artistic", "Palette Knife", ::paletteKnife),
        Entry("Artistic", "Plastic Wrap", ::plasticWrap),
        Entry("Artistic", "Poster Edges", ::posterEdges),
        Entry("Artistic", "Rough Pastels", ::roughPastels),
        Entry("Artistic", "Smudge Stick", ::smudgeStick),
        Entry("Artistic", "Sponge", ::sponge),
        Entry("Artistic", "Underpainting", ::underpainting),
        Entry("Artistic", "Watercolor", ::watercolor),
        Entry("Brush Strokes", "Accented Edges", ::accentedEdges),
        Entry("Brush Strokes", "Angled Strokes", ::angledStrokes),
        Entry("Brush Strokes", "Crosshatch", ::crosshatch),
        Entry("Brush Strokes", "Dark Strokes", ::darkStrokes),
        Entry("Brush Strokes", "Ink Outlines", ::inkOutlines),
        Entry("Brush Strokes", "Spatter", ::spatter),
        Entry("Brush Strokes", "Sprayed Strokes", ::sprayedStrokes),
        Entry("Brush Strokes", "Sumi-e", ::sumiE),
        Entry("Distort", "Diffuse Glow", { image, selection, strength ->
            PixFilters.diffuseGlow(image, selection, 8, strength.toDouble())
        }),
        Entry("Distort", "Glass", ::glass),
        Entry("Distort", "Ocean Ripple", ::oceanRipple),
        Entry("Sketch", "Bas Relief", ::basRelief),
        Entry("Sketch", "Chalk & Charcoal", ::chalkCharcoal),
        Entry("Sketch", "Charcoal", ::charcoal),
        Entry("Sketch", "Chrome", ::chrome),
        Entry("Sketch", "Conte Crayon", ::conteCrayon),
        Entry("Sketch", "Graphic Pen", ::graphicPen),
        Entry("Sketch", "Halftone Pattern", ::halftonePattern),
        Entry("Sketch", "Note Paper", ::notePaper),
        Entry("Sketch", "Photocopy", ::photocopy),
        Entry("Sketch", "Plaster", ::plaster),
        Entry("Sketch", "Reticulation", ::reticulation),
        Entry("Sketch", "Stamp", ::stamp),
        Entry("Sketch", "Torn Edges", ::tornEdges),
        Entry("Sketch", "Water Paper", ::waterPaper),
        Entry("Stylize", "Glowing Edges", ::galleryGlowingEdges),
        Entry("Texture", "Craquelure", ::craquelure),
        Entry("Texture", "Grain", ::galleryGrain),
        Entry("Texture", "Mosaic Tiles", ::mosaicTiles),
        Entry("Texture", "Patchwork", ::patchwork),
        Entry("Texture", "Stained Glass", ::stainedGlass),
        Entry("Texture", "Texturizer", ::texturizer),
    )

    val groups: List<String> get() = entries.map { it.group }.distinct()

    fun entriesIn(group: String): List<Entry> = entries.filter { it.group == group }

    fun find(name: String): Entry? = entries.firstOrNull { it.name == name }
}
