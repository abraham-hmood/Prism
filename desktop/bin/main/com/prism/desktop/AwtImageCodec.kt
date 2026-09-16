package com.prism.desktop

import com.prism.core.ImageCodec
import com.prism.core.PrismImage
import com.prism.core.PrismPlatform
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * The desktop image codec, over ImageIO.
 *
 * Lives in the desktop module rather than the core for a specific reason: `java.awt` and
 * `javax.imageio` do not exist on Android, and a core that referenced them would compile
 * happily and then fail to dex -- or worse, dex with warnings and throw `NoClassDefFoundError`
 * on a device at the moment a user first opens a training image. Keeping the AWT dependency in
 * the module that can actually satisfy it is what makes the boundary meaningful rather than
 * decorative.
 *
 * The Android codec is the mirror image of this file, and the two together are the complete
 * platform-specific surface of image handling: roughly sixty lines each, both of which only
 * turn a file into an int array and back.
 */
object AwtImageCodec : ImageCodec {

    override fun decode(file: File, maxDimension: Int): PrismImage? = try {
        val source = ImageIO.read(file)
        if (source == null) {
            PrismPlatform.log.warn("Prism/image", "ImageIO could not read ${file.name}")
            null
        } else {
            // ImageIO has no subsampled-decode equivalent to Android's inSampleSize, so the
            // full image is read and then reduced. Acceptable on a machine with gigabytes of
            // heap; it would not be on a phone, which is exactly why the two codecs differ.
            val scale = minOf(
                1.0,
                maxDimension.toDouble() / maxOf(source.width, source.height)
            )
            val w = maxOf(1, (source.width * scale).toInt())
            val h = maxOf(1, (source.height * scale).toInt())

            // Normalized to TYPE_INT_ARGB first. ImageIO returns whatever the file happened to
            // use -- indexed colour, grayscale, 3-byte BGR -- and reading raw pixels from those
            // would need a branch per format. One conversion makes the packed layout the same
            // ARGB the Android path produces.
            val normalized = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
            val g = normalized.createGraphics()
            try {
                g.setRenderingHint(
                    java.awt.RenderingHints.KEY_INTERPOLATION,
                    java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR
                )
                g.drawImage(source, 0, 0, w, h, null)
            } finally {
                g.dispose()
            }

            val pixels = IntArray(w * h)
            normalized.getRGB(0, 0, w, h, pixels, 0, w)
            PrismImage(w, h, pixels)
        }
    } catch (t: Throwable) {
        PrismPlatform.log.error("Prism/image", "Decode failed for ${file.name}", t)
        null
    }

    override fun encodePng(image: PrismImage, file: File): Boolean = try {
        file.parentFile?.mkdirs()
        val out = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
        out.setRGB(0, 0, image.width, image.height, image.pixels, 0, image.width)
        ImageIO.write(out, "png", file)
        true
    } catch (t: Throwable) {
        PrismPlatform.log.error("Prism/image", "PNG write failed for ${file.name}", t)
        false
    }
}
