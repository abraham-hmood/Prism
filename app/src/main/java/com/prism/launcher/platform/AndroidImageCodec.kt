package com.prism.launcher.platform

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.prism.core.ImageCodec
import com.prism.core.PrismImage
import com.prism.core.PrismPlatform
import java.io.File
import java.io.FileOutputStream

/**
 * Android's image codec: `BitmapFactory` in, `Bitmap.compress` out.
 *
 * The bitmap does not survive this class. It is decoded, its pixels are copied into a
 * [PrismImage], and it is recycled immediately -- so nothing downstream holds a `Bitmap`, and
 * the platform type stops at this file rather than propagating into the model.
 *
 * That copy is not a cost, it is the point. The previous code sampled the retina by calling
 * `Bitmap.getPixel` four times per bilinear tap, thousands of taps per fixation, and every one
 * of those was a JNI transition. One `getPixels` call followed by array reads is the same
 * arithmetic with the boundary crossed once.
 */
object AndroidImageCodec : ImageCodec {

    override fun decode(file: File, maxDimension: Int): PrismImage? = try {
        // Two-pass decode: bounds first, then a subsampled read. `inSampleSize` decodes at
        // reduced size rather than decoding fully and shrinking, which is the difference
        // between a 200 MB transient allocation and a 3 MB one on a modern camera photo.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)

        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            PrismPlatform.log.warn("Prism/image", "Not a decodable image: ${file.name}")
            null
        } else {
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= maxDimension ||
                bounds.outHeight / (sample * 2) >= maxDimension
            ) {
                sample *= 2
            }

            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val bmp = BitmapFactory.decodeFile(file.absolutePath, opts)
            if (bmp == null) {
                PrismPlatform.log.warn("Prism/image", "Decode returned nothing for ${file.name}")
                null
            } else {
                try {
                    val pixels = IntArray(bmp.width * bmp.height)
                    bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
                    PrismImage(bmp.width, bmp.height, pixels)
                } finally {
                    bmp.recycle()
                }
            }
        }
    } catch (t: Throwable) {
        PrismPlatform.log.error("Prism/image", "Decode failed for ${file.name}", t)
        null
    }

    override fun encodePng(image: PrismImage, file: File): Boolean = try {
        file.parentFile?.mkdirs()
        val bmp = Bitmap.createBitmap(image.pixels, image.width, image.height, Bitmap.Config.ARGB_8888)
        try {
            FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            bmp.recycle()
        }
        true
    } catch (t: Throwable) {
        PrismPlatform.log.error("Prism/image", "PNG write failed for ${file.name}", t)
        false
    }

    /** Converts a generated image back to a Bitmap, for the parts of the UI that draw one. */
    fun toBitmap(image: PrismImage): Bitmap =
        Bitmap.createBitmap(image.pixels, image.width, image.height, Bitmap.Config.ARGB_8888)

    /** Converts an incoming Bitmap, for UI paths that already hold one. */
    fun fromBitmap(bmp: Bitmap): PrismImage {
        val pixels = IntArray(bmp.width * bmp.height)
        bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        return PrismImage(bmp.width, bmp.height, pixels)
    }
}
