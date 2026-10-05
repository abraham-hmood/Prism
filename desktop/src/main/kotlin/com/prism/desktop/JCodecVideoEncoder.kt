package com.prism.desktop

import com.prism.core.PrismImage
import com.prism.core.PrismPlatform
import com.prism.core.VideoEncoder
import org.jcodec.api.awt.AWTSequenceEncoder
import org.jcodec.common.model.Rational
import java.awt.image.BufferedImage
import java.io.File

/**
 * H.264 in an mp4, in pure Java. The desktop half of PHASE 42.
 *
 * ## Why JCodec rather than ffmpeg
 *
 * ffmpeg would be faster and would mean shipping a binary per platform, finding it at runtime, and an
 * installer step — for a feature used occasionally. JCodec is a 2 MB jar with no native component, so a
 * desktop build has video support with nothing to provision.
 *
 * THE ENCODER IS SLOW AND THAT IS THE ACCEPTED TRADE. This is software baseline H.264 against Android's
 * hardware encoder: a couple of hundred frames takes seconds rather than being instant. For generated
 * imagery on a desktop CPU that is fine, and it is the difference between `/video` existing here and not.
 *
 * ## Why the dimensions are forced even
 *
 * H.264 encodes chroma at half resolution in both axes, so an odd width or height has no valid chroma
 * plane. The Android writer masks the low bit for the same reason. An odd-sized frame does not fail
 * loudly — it produces a file whose last row or column is garbage, or one no player will open.
 */
class JCodecVideoEncoder : VideoEncoder {

    override val label: String = "H.264 (JCodec, software)"

    override fun unavailableReason(): String? = runCatching {
        // Loaded by reflection-free reference, so a build without the jar fails HERE with a clear answer
        // rather than at the moment somebody asks for a video.
        AWTSequenceEncoder::class.java.name
        null
    }.getOrElse { "JCodec is not on the classpath in this build." }

    override fun encode(
        frames: List<PrismImage>,
        destination: File,
        fps: Int,
        onProgress: ((Int, Int) -> Unit)?,
    ): File? {
        if (frames.isEmpty()) return null

        val width = frames[0].width and 0xFFFFFFFE.toInt()
        val height = frames[0].height and 0xFFFFFFFE.toInt()
        if (width <= 0 || height <= 0) return null

        destination.parentFile?.mkdirs()

        return runCatching {
            val encoder = AWTSequenceEncoder(
                org.jcodec.common.io.NIOUtils.writableChannel(destination),
                Rational.R(fps.coerceIn(1, 60), 1),
            )
            try {
                frames.forEachIndexed { index, frame ->
                    encoder.encodeImage(toBufferedImage(frame, width, height))
                    onProgress?.invoke(index + 1, frames.size)
                }
            } finally {
                // finish() writes the moov atom. WITHOUT IT THE FILE IS UNPLAYABLE -- the frames are all
                // there and no player will touch it, because an mp4 with no index is not an mp4. In a
                // finally block so a frame that throws part-way still leaves a readable prefix.
                runCatching { encoder.finish() }
            }
            destination
        }.getOrElse {
            PrismPlatform.log.error("Prism/video", "JCodec encode failed", it)
            null
        }
    }

    /**
     * A [PrismImage] as an AWT image.
     *
     * TYPE_3BYTE_BGR rather than TYPE_INT_ARGB: JCodec's encoder converts whatever it is given to YUV,
     * and handing it an image with an alpha channel makes it do that conversion through a slower generic
     * path for a channel H.264 cannot carry anyway.
     *
     * Cropped rather than scaled when the frame is bigger than the even dimensions — one row is not worth
     * a resample, and scaling would soften every frame to save a pixel.
     */
    private fun toBufferedImage(frame: PrismImage, width: Int, height: Int): BufferedImage {
        val image = BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR)
        val raster = image.raster
        val pixel = IntArray(3)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val argb = if (x < frame.width && y < frame.height) frame.pixel(x, y) else 0
                pixel[0] = (argb shr 16) and 0xFF   // R
                pixel[1] = (argb shr 8) and 0xFF    // G
                pixel[2] = argb and 0xFF            // B
                raster.setPixel(x, y, pixel)
            }
        }
        return image
    }
}
