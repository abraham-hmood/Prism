package com.prism.launcher.photopix

import com.prism.core.PrismImage
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * One layer of a document.
 *
 * A layer owns a full-size image at an offset, which is how Photoshop models it: a layer the size of
 * the canvas at (0,0) is the ordinary case, and a small layer placed somewhere is the same object
 * with a different offset. Storing a sub-image with its own origin rather than padding it to the
 * canvas keeps a pasted logo from costing a full canvas of memory.
 */
class PixLayer(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    var image: PrismImage,
    var opacity: Double = 1.0,
    var visible: Boolean = true,
    var blend: BlendMode = BlendMode.NORMAL,
    var offsetX: Int = 0,
    var offsetY: Int = 0,
    /** A fill layer is replaced by [fillColor] on export; used by solid/gradient fills. */
    var fillColor: Int? = null,
    var locked: Boolean = false,
) {
    fun copyOf(): PixLayer = PixLayer(
        id = id,
        name = name,
        image = image.copy(),
        opacity = opacity,
        visible = visible,
        blend = blend,
        offsetX = offsetX,
        offsetY = offsetY,
        fillColor = fillColor,
        locked = locked,
    )
}

/**
 * A document: the canvas, its layers, the current selection and its history.
 *
 * ## Mutable, with snapshot history
 *
 * The alternative -- an immutable document with structural sharing -- is elegant and would allocate
 * a new layer list for every brush dab. A stroke is hundreds of dabs, so the editor mutates in place
 * and the history takes whole-document snapshots with a memory budget. [PixHistory] evicts the
 * oldest entries when the budget is reached, which is the honest way to bound an undo stack for
 * images whose size the user chose.
 */
class PixDocument(
    var width: Int,
    var height: Int,
    val layers: MutableList<PixLayer> = mutableListOf(),
    var activeIndex: Int = 0,
    var selection: PixSelection? = null,
    /** Working title, shown in the editor's title bar and on export. */
    var title: String = "Untitled",
    /** Where the document came from, for Save. Null for a new document. */
    var sourcePath: String? = null,
) {

    val history = PixHistory()

    init {
        require(width > 0 && height > 0)
        if (layers.isEmpty()) {
            layers.add(PixLayer(name = "Background", image = PrismImage.blank(width, height, 0xFFFFFFFF.toInt())))
        }
        activeIndex = activeIndex.coerceIn(0, layers.size - 1)
    }

    val activeLayer: PixLayer get() = layers[activeIndex.coerceIn(0, layers.size - 1)]

    fun layerById(id: String): PixLayer? = layers.firstOrNull { it.id == id }

    /** A selection the size of the canvas, made on demand, for callers that always need one. */
    fun selectionOrAll(): PixSelection = selection ?: PixSelection.all(width, height)

    // ── Rendering ─────────────────────────────────────────────────────────

    /**
     * Flattens every visible layer into one image.
     *
     * The base is transparent, not white: a document with a hidden background must export with an
     * alpha channel rather than a white rectangle where the user expected nothing.
     */
    fun flatten(): PrismImage {
        val canvas = PrismImage(width, height, IntArray(width * height) { 0 })
        for (layer in layers) {
            if (!layer.visible || layer.opacity <= 0.0) continue
            val fill = layer.fillColor
            if (fill != null) {
                val solid = PrismImage(layer.image.width, layer.image.height, IntArray(layer.image.pixels.size) { fill })
                PixImageOps.draw(canvas, solid, layer.offsetX, layer.offsetY, layer.blend, layer.opacity)
            } else {
                PixImageOps.draw(canvas, layer.image, layer.offsetX, layer.offsetY, layer.blend, layer.opacity)
            }
        }
        return canvas
    }

    /** The flattened image composited over white, for formats without alpha. */
    fun flattenOnWhite(): PrismImage {
        val flat = flatten()
        val white = PrismImage(width, height, IntArray(width * height) { 0xFFFFFFFF.toInt() })
        PixImageOps.draw(white, flat)
        return white
    }

    // ── Structure ─────────────────────────────────────────────────────────

    fun addLayer(name: String = "Layer ${layers.size}", image: PrismImage? = null): PixLayer {
        val layer = PixLayer(name = name, image = image?.copy() ?: PrismImage(width, height, IntArray(width * height) { 0 }))
        layers.add(layer)
        activeIndex = layers.size - 1
        return layer
    }

    fun duplicateActive(): PixLayer {
        val duplicate = activeLayer.copyOf().also { it.name = activeLayer.name + " copy" }
        layers.add(activeIndex + 1, duplicate)
        activeIndex += 1
        return duplicate
    }

    fun deleteActive(): Boolean {
        if (layers.size <= 1) return false
        layers.removeAt(activeIndex)
        activeIndex = activeIndex.coerceAtMost(layers.size - 1)
        return true
    }

    /** Merges the active layer into the one below it, respecting blend and opacity. */
    fun mergeDown(): Boolean {
        if (activeIndex <= 0) return false
        val upper = layers[activeIndex]
        val lower = layers[activeIndex - 1]
        if (!upper.visible) {
            layers.removeAt(activeIndex)
            activeIndex -= 1
            return true
        }
        val merged = lower.image.copy()
        PixImageOps.draw(
            merged, upper.image,
            upper.offsetX - lower.offsetX, upper.offsetY - lower.offsetY,
            upper.blend, upper.opacity,
        )
        lower.image = merged
        layers.removeAt(activeIndex)
        activeIndex -= 1
        return true
    }

    fun flattenLayers() {
        val flat = flatten()
        layers.clear()
        layers.add(PixLayer(name = "Background", image = flat))
        activeIndex = 0
    }

    fun moveLayer(from: Int, to: Int) {
        if (from !in layers.indices) return
        val target = to.coerceIn(0, layers.size - 1)
        if (from == target) return
        val layer = layers.removeAt(from)
        layers.add(target, layer)
        activeIndex = target
    }

    // ── Canvas ────────────────────────────────────────────────────────────

    /** Image Size: resamples every layer and the selection to a new pixel size. */
    fun resizeImage(newWidth: Int, newHeight: Int) {
        val w = newWidth.coerceIn(1, 8192)
        val h = newHeight.coerceIn(1, 8192)
        if (w == width && h == height) return
        val sx = w.toDouble() / width
        val sy = h.toDouble() / height
        for (layer in layers) {
            layer.image = PixImageOps.scaled(
                layer.image,
                max(1, ceil(layer.image.width * sx).toInt()),
                max(1, ceil(layer.image.height * sy).toInt()),
            )
            layer.offsetX = (layer.offsetX * sx).toInt()
            layer.offsetY = (layer.offsetY * sy).toInt()
        }
        selection = selection?.scaled(w, h)
        width = w
        height = h
    }

    /** Canvas Size: keeps the pixels, changes the frame, anchored by [anchor] (0..8, top-left first). */
    fun resizeCanvas(newWidth: Int, newHeight: Int, anchor: Int = 4, fill: Int = 0) {
        val w = newWidth.coerceIn(1, 8192)
        val h = newHeight.coerceIn(1, 8192)
        val anchorX = (anchor % 3) / 2.0
        val anchorY = (anchor / 3) / 2.0
        val dx = ((w - width) * anchorX).toInt()
        val dy = ((h - height) * anchorY).toInt()
        for (layer in layers) {
            layer.image = PixImageOps.padded(layer.image, w, h, layer.offsetX + dx, layer.offsetY + dy, fill)
            layer.offsetX = 0
            layer.offsetY = 0
        }
        selection = selection?.padded(w, h, dx, dy)
        width = w
        height = h
    }

    /** Crop: every layer is cropped and its offset shifted with it. */
    fun crop(x: Int, y: Int, w: Int, h: Int) {
        val x0 = x.coerceIn(0, width - 1)
        val y0 = y.coerceIn(0, height - 1)
        val cw = w.coerceIn(1, width - x0)
        val ch = h.coerceIn(1, height - y0)
        for (layer in layers) {
            val layerX = layer.offsetX - x0
            val layerY = layer.offsetY - y0
            val combined = PrismImage.blank(cw, ch, 0)
            PixImageOps.draw(combined, layer.image, layerX, layerY)
            layer.image = combined
            layer.offsetX = 0
            layer.offsetY = 0
        }
        selection = selection?.cropped(x0, y0, cw, ch)
        width = cw
        height = ch
    }

    /** Rotates the whole document a quarter turn clockwise or anticlockwise. */
    fun rotateCanvas(clockwise: Boolean) {
        for (layer in layers) {
            layer.image = PixImageOps.rotate90(layer.image, clockwise)
            layer.offsetX = 0
            layer.offsetY = 0
        }
        selection = selection?.rotate90(clockwise)
        val oldWidth = width
        width = height
        height = oldWidth
    }

    fun flipCanvas(horizontal: Boolean) {
        for (layer in layers) {
            layer.image = if (horizontal) PixImageOps.flipHorizontal(layer.image) else PixImageOps.flipVertical(layer.image)
        }
        selection = selection?.let { if (horizontal) it.flipHorizontal() else it.flipVertical() }
    }

    // ── Editing the active layer ──────────────────────────────────────────

    /**
     * Replaces the active layer's pixels with [transform]'s result, clipped by the selection.
     *
     * This is the one path every adjustment and filter takes, which is what makes them all undoable
     * in the same way and all respect a feathered selection without knowing they are selected.
     */
    fun applyToActive(transform: (PrismImage) -> PrismImage) {
        val layer = activeLayer
        val selectionForLayer = selectionFor(layer)
        layer.image = transform(layer.image).let { result ->
            if (selectionForLayer == null) result
            else blendThroughSelection(layer.image, result, selectionForLayer)
        }
    }

    /** The document's selection expressed in [layer]'s own coordinates. */
    fun selectionFor(layer: PixLayer): PixSelection? {
        val sel = selection ?: return null
        if (layer.offsetX == 0 && layer.offsetY == 0 && layer.image.width == width && layer.image.height == height) return sel
        return sel.cropped(-layer.offsetX, -layer.offsetY, layer.image.width, layer.image.height)
    }

    private fun blendThroughSelection(original: PrismImage, changed: PrismImage, selection: PixSelection): PrismImage {
        val out = original.copy()
        for (i in out.pixels.indices) {
            val coverage = selection.coverage(i % out.width, i / out.width)
            if (coverage <= 0.0) continue
            if (coverage >= 1.0) {
                out.pixels[i] = changed.pixels[i]
            } else {
                val a = Rgba.of(original.pixels[i])
                val b = Rgba.of(changed.pixels[i])
                out.pixels[i] = Rgba(
                    a.r + (b.r - a.r) * coverage,
                    a.g + (b.g - a.g) * coverage,
                    a.b + (b.b - a.b) * coverage,
                    a.a + (b.a - a.a) * coverage,
                ).packed()
            }
        }
        return out
    }

    /** Fills the selection (or the whole layer) with a colour. */
    fun fill(color: Int, preserveTransparency: Boolean = false) {
        val layer = activeLayer
        val sel = selectionFor(layer)
        val changed = layer.image.copy()
        for (y in 0 until changed.height) for (x in 0 until changed.width) {
            val coverage = sel?.coverage(x, y) ?: 1.0
            if (coverage <= 0.0) continue
            val index = y * changed.width + x
            if (preserveTransparency && (changed.pixels[index] ushr 24) == 0) continue
            val existing = Rgba.of(changed.pixels[index])
            val fill = Rgba.of(color)
            val alpha = (fill.a / 255.0 * coverage)
            changed.pixels[index] = Rgba(
                existing.r + (fill.r - existing.r) * alpha,
                existing.g + (fill.g - existing.g) * alpha,
                existing.b + (fill.b - existing.b) * alpha,
                max(existing.a, fill.a * coverage),
            ).packed()
        }
        layer.image = changed
    }

    /**
     * A linear gradient across the drag, from [from] to [to].
     *
     * Straight interpolation in RGB rather than in a perceptual space. A perceptual blend is nicer
     * between complementary hues and is also what makes a black-to-white ramp pick up a colour cast;
     * for this editor's purpose the plain ramp is the correct default.
     */
    fun gradient(x0: Int, y0: Int, x1: Int, y1: Int, from: Int, to: Int, radial: Boolean = false) {
        val layer = activeLayer
        val sel = selectionFor(layer)
        val changed = layer.image.copy()
        val dx = (x1 - x0).toDouble()
        val dy = (y1 - y0).toDouble()
        val lengthSquared = dx * dx + dy * dy
        if (lengthSquared <= 0.0001) return
        val length = java.lang.Math.sqrt(lengthSquared)
        for (y in 0 until changed.height) for (x in 0 until changed.width) {
            val coverage = sel?.coverage(x, y) ?: 1.0
            if (coverage <= 0.0) continue
            val t = if (radial) {
                (java.lang.Math.sqrt(((x - x0) * (x - x0) + (y - y0) * (y - y0)).toDouble()) / length).coerceIn(0.0, 1.0)
            } else {
                (((x - x0) * dx + (y - y0) * dy) / lengthSquared).coerceIn(0.0, 1.0)
            }
            val index = y * changed.width + x
            val existing = Rgba.of(changed.pixels[index])
            val target = Rgba.of(mixColors(from, to, t))
            val alpha = coverage
            changed.pixels[index] = Rgba(
                existing.r + (target.r - existing.r) * alpha,
                existing.g + (target.g - existing.g) * alpha,
                existing.b + (target.b - existing.b) * alpha,
                max(existing.a, target.a * coverage),
            ).packed()
        }
        layer.image = changed
    }

    /**
     * Paints a run of text through the platform rasteriser.
     *
     * Returns false when no rasteriser is installed, which is the honest answer on a platform with
     * no font stack rather than silently doing nothing.
     */
    fun placeText(text: String, x: Int, y: Int, sizePx: Int, color: Int, bold: Boolean = false, italic: Boolean = false): Boolean {
        val rasteriser = textRasterizer ?: return false
        val rendered = rasteriser.render(text, sizePx.coerceIn(6, 512), color, bold, italic) ?: return false
        val layer = activeLayer
        PixImageOps.blit(layer.image, rendered, x - layer.offsetX, y - layer.offsetY)
        return true
    }

    /** A document-sized copy of the flattened image, for Copy Merged and Export. */
    fun copyFlattened(): PrismImage = flatten()

    /** Snapshot and restore, for the history. */
    fun snapshot(): Snapshot = Snapshot(
        width = width,
        height = height,
        activeIndex = activeIndex,
        selection = selection?.copy(),
        layers = layers.map { it.copyOf() },
        title = title,
    )

    fun restore(snapshot: Snapshot) {
        width = snapshot.width
        height = snapshot.height
        layers.clear()
        layers.addAll(snapshot.layers.map { it.copyOf() })
        activeIndex = snapshot.activeIndex.coerceIn(0, layers.size - 1)
        selection = snapshot.selection?.copy()
        title = snapshot.title
    }

    data class Snapshot(
        val width: Int,
        val height: Int,
        val activeIndex: Int,
        val selection: PixSelection?,
        val layers: List<PixLayer>,
        val title: String,
    ) {
        /** Roughly how much heap this snapshot holds, for the history's budget. */
        fun bytes(): Long = layers.sumOf { (it.image.pixels.size * 4).toLong() } +
            (selection?.mask?.size?.toLong() ?: 0L)
    }

    companion object {
        /**
         * The platform's text renderer.
         *
         * A hook rather than an implementation because core is a plain JVM module with no font
         * stack: `Bitmap` and `Canvas` on Android, `Graphics2D` on desktop. A document without one
         * reports that it cannot place text.
         */
        @Volatile
        var textRasterizer: PixTextRasterizer? = null

        fun fromImage(image: PrismImage, title: String = "Untitled", sourcePath: String? = null): PixDocument =
            PixDocument(
                width = image.width,
                height = image.height,
                layers = mutableListOf(PixLayer(name = "Background", image = image)),
                title = title,
                sourcePath = sourcePath,
            )
    }
}

/**
 * Places text as pixels.
 *
 * [render] returns an image with transparent padding around the glyphs, sized to the text's own
 * bounds, so placing it on a layer does not paint a box of background over the photo.
 */
interface PixTextRasterizer {
    fun render(text: String, sizePx: Int, color: Int, bold: Boolean, italic: Boolean): PrismImage?
}

/**
 * Undo and redo.
 *
 * ## A byte budget, not a step count
 *
 * Twenty steps of a 4000-pixel canvas is several gigabytes; twenty steps of a thumbnail is nothing.
 * A fixed step count is therefore either unusable at one size or wastefully small at the other. The
 * budget evicts from the bottom until the stack fits, and always keeps the most recent step even if
 * a single snapshot exceeds it -- an undo that does nothing is worse than one that briefly uses more
 * memory than advertised.
 */
class PixHistory(private val budgetBytes: Long = 96L * 1024 * 1024, private val maxSteps: Int = 40) {

    private data class Step(val label: String, val snapshot: PixDocument.Snapshot)

    private val undoStack = ArrayDeque<Step>()
    private val redoStack = ArrayDeque<Step>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()
    val undoLabel: String? get() = undoStack.lastOrNull()?.label
    val redoLabel: String? get() = redoStack.lastOrNull()?.label

    /** Records the state BEFORE an edit. Any redo branch is discarded, as in every editor. */
    fun push(label: String, document: PixDocument) {
        undoStack.addLast(Step(label, document.snapshot()))
        redoStack.clear()
        trim()
    }

    private fun trim() {
        while (undoStack.size > maxSteps) undoStack.removeFirst()
        var total = undoStack.sumOf { it.snapshot.bytes() }
        while (total > budgetBytes && undoStack.size > 1) {
            val removed = undoStack.removeFirst()
            total -= removed.snapshot.bytes()
        }
    }

    fun undo(document: PixDocument): Boolean {
        val step = undoStack.removeLastOrNull() ?: return false
        redoStack.addLast(Step(step.label, document.snapshot()))
        document.restore(step.snapshot)
        return true
    }

    fun redo(document: PixDocument): Boolean {
        val step = redoStack.removeLastOrNull() ?: return false
        undoStack.addLast(Step(step.label, document.snapshot()))
        document.restore(step.snapshot)
        return true
    }

    fun clear() {
        undoStack.clear()
        redoStack.clear()
    }

    fun depth(): Int = undoStack.size
}

/** Geometry helpers on selections, kept next to the document because they are only used together. */
fun PixSelection.cropped(x: Int, y: Int, w: Int, h: Int): PixSelection {
    val out = ByteArray(w * h)
    for (row in 0 until h) for (col in 0 until w) {
        val sx = col + x
        val sy = row + y
        out[row * w + col] = if (sx in 0 until width && sy in 0 until height) mask[sy * width + sx] else 0
    }
    return PixSelection(w, h, out)
}

fun PixSelection.scaled(w: Int, h: Int): PixSelection {
    val out = ByteArray(w * h)
    for (y in 0 until h) for (x in 0 until w) {
        val sx = (x.toLong() * width / w).toInt().coerceIn(0, width - 1)
        val sy = (y.toLong() * height / h).toInt().coerceIn(0, height - 1)
        out[y * w + x] = mask[sy * width + sx]
    }
    return PixSelection(w, h, out)
}

fun PixSelection.padded(w: Int, h: Int, dx: Int, dy: Int): PixSelection {
    val out = ByteArray(w * h)
    for (y in 0 until height) {
        val ty = y + dy
        if (ty < 0 || ty >= h) continue
        for (x in 0 until width) {
            val tx = x + dx
            if (tx < 0 || tx >= w) continue
            out[ty * w + tx] = mask[y * width + x]
        }
    }
    return PixSelection(w, h, out)
}

fun PixSelection.rotate90(clockwise: Boolean): PixSelection {
    val w = height
    val h = width
    val out = ByteArray(w * h)
    for (y in 0 until height) for (x in 0 until width) {
        val nx = if (clockwise) height - 1 - y else y
        val ny = if (clockwise) x else width - 1 - x
        out[ny * w + nx] = mask[y * width + x]
    }
    return PixSelection(w, h, out)
}

fun PixSelection.flipHorizontal(): PixSelection {
    val out = ByteArray(mask.size)
    for (y in 0 until height) for (x in 0 until width) {
        out[y * width + x] = mask[y * width + (width - 1 - x)]
    }
    return PixSelection(width, height, out)
}

fun PixSelection.flipVertical(): PixSelection {
    val out = ByteArray(mask.size)
    for (y in 0 until height) {
        System.arraycopy(mask, (height - 1 - y) * width, out, y * width, width)
    }
    return PixSelection(width, height, out)
}
