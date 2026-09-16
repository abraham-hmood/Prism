package com.prism.launcher.aether

import com.prism.core.PrismImage
import com.prism.core.PrismPlatform
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * Ported from `data_ingestion/multimedia_loader.py::MultimediaLoader`. Loads training media from
 * disk into raw (unnormalized, 0..255) flat pixel arrays for [SensoryTokenizer].
 *
 * Image decoding goes through [PrismPlatform.images] (the same cross-platform codec Nora's own
 * training pipeline uses) rather than a bundled decoder, so this needs no `android.graphics`/AWT
 * dependency here in `:core`. Video frame extraction is NOT implemented -- the source's own
 * fallback when `cv2` is unavailable is to return black frames, and full MP4 frame-by-frame
 * decoding is a genuinely platform-specific undertaking (`MediaMetadataRetriever` on Android)
 * out of scope for this pass; [loadVideoFrames] here returns the same black-frame fallback the
 * source uses, honestly, rather than a half-implementation.
 */
class AetherMultimediaLoader(val targetW: Int = 32, val targetH: Int = 32) {

    private fun blackFrame(): FloatArray = FloatArray(targetW * targetH * 3)

    private fun toFlatRgb(img: PrismImage): FloatArray {
        val plane = targetW * targetH
        val out = FloatArray(plane * 3)
        for (y in 0 until targetH) for (x in 0 until targetW) {
            val p = img.pixel(x, y)
            val base = y * targetW + x
            out[base] = PrismImage.red(p).toFloat()
            out[plane + base] = PrismImage.green(p).toFloat()
            out[2 * plane + base] = PrismImage.blue(p).toFloat()
        }
        return out
    }

    private fun crop(img: PrismImage, xOff: Int, yOff: Int, w: Int, h: Int): PrismImage {
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            val sy = (y + yOff).coerceIn(0, img.height - 1)
            for (x in 0 until w) {
                val sx = (x + xOff).coerceIn(0, img.width - 1)
                pixels[y * w + x] = img.pixel(sx, sy)
            }
        }
        return PrismImage(w, h, pixels)
    }

    /**
     * `bioTrainMode = false`: plain resize to target size. `true`: "active inference" -- resize
     * to a 2x-oversized optical field, crop a target-size foveal patch at [foveaOffsetX]/Y
     * (each in [-1,1]), then an epoch-scaled blur (`curriculum_res = max(4, min(target, epoch*4))`)
     * that progressively sharpens as training advances.
     */
    private fun biologicalProcessor(img: PrismImage, bioTrainMode: Boolean, epoch: Int, foveaOffsetX: Float, foveaOffsetY: Float): PrismImage {
        if (!bioTrainMode) return img.scaledTo(targetW, targetH)

        val fieldW = targetW * 2
        val fieldH = targetH * 2
        val field = img.scaledTo(fieldW, fieldH)

        val maxX = fieldW - targetW
        val maxY = fieldH - targetH
        val fx = foveaOffsetX.coerceIn(-1f, 1f)
        val fy = foveaOffsetY.coerceIn(-1f, 1f)
        val xOff = ((fx + 1f) / 2f * maxX).toInt()
        val yOff = ((fy + 1f) / 2f * maxY).toInt()

        val patch = crop(field, xOff, yOff, targetW, targetH)

        val curriculumRes = max(4, min(targetW, (epoch * 4)))
        val blurry = patch.scaledTo(curriculumRes, curriculumRes)
        return blurry.scaledTo(targetW, targetH)
    }

    fun loadImage(file: File, bioTrainMode: Boolean = false, epoch: Int = 1, foveaOffsetX: Float = 0f, foveaOffsetY: Float = 0f): FloatArray {
        if (!file.exists()) return blackFrame()
        val decoded = PrismPlatform.images.decode(file, maxOf(targetW, targetH) * 4) ?: return blackFrame()
        return toFlatRgb(biologicalProcessor(decoded, bioTrainMode, epoch, foveaOffsetX, foveaOffsetY))
    }

    /** See class doc -- no frame extraction implemented; returns [maxFrames] black frames, matching the source's own cv2-unavailable fallback. */
    fun loadVideoFrames(file: File, maxFrames: Int = 2, bioTrainMode: Boolean = false, epoch: Int = 1, foveaOffsetX: Float = 0f, foveaOffsetY: Float = 0f): List<FloatArray> =
        List(maxFrames) { blackFrame() }

    /** Stub, matching the source's own: `tf.random.normal((500,))`, "Simplified... for architecture sake." */
    fun loadAudio(): FloatArray = FloatArray(500) { (Math.random() * 2 - 1).toFloat() }

    fun loadText(file: File): String =
        if (file.exists()) file.readText() else "Simulated physical text read from empty file."
}
