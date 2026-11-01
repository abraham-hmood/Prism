package com.prism.desktop

import com.prism.core.PrismPlatform
import com.prism.launcher.modelcraft.ModelCraft
import java.io.File
import javax.imageio.ImageIO

/**
 * Frames out of a clip, for the desktop's ModelCraft runs.
 *
 * ## Why this exists on this platform too
 *
 * The interpolator and the video trainer read frames rather than containers: a frame is a small PNG
 * they can decode identically on every platform, and a container is a decoder per format. The phone
 * installs a frame extractor for that reason and the desktop used to say so in the UI -- its datasets
 * were written as clips with no frames, and those two kinds quietly had nothing to train on. JCodec is
 * already a desktop dependency for H.264, and it decodes as well as it encodes.
 *
 * ## What it writes
 *
 * `frame-00000.png` upward, in decode order, one per frame until [MAX_FRAMES]. The cap mirrors the
 * phone's: a long recording sampled evenly there, the first frames here, because a container reader
 * cannot seek to an arbitrary time as cheaply as MediaMetadataRetriever can. What the trainer reads is
 * a sequence, and a sequence's beginning is as good a place to cut it as any -- the metadata records
 * how many frames a run actually got.
 */
object ModelCraftFrames {

    /** Enough frames for thousands of triplets, short of filling a disk. The phone uses the same cap. */
    private const val MAX_FRAMES = 48

    /** Wires the shared code's hook to this platform. Idempotent. */
    fun install() {
        ModelCraft.frameExtractor = ::extractFrames
    }

    /**
     * Writes frames of [video] into [destination].
     *
     * @return the number of frames written. Zero means the file could not be decoded, which the
     *         manifest then reports as such rather than as an empty dataset.
     */
    private fun extractFrames(video: File, destination: File): Int {
        if (!video.isFile) return 0
        destination.mkdirs()
        var written = 0
        runCatching {
            val grab = org.jcodec.api.awt.AWTFrameGrab.createAWTFrameGrab(
                org.jcodec.common.io.NIOUtils.readableChannel(video),
            )
            while (written < MAX_FRAMES) {
                // The end of a clip is an exception in this API, not a null frame, so the loop's
                // stop is where the guard is: the next frame either exists or the read is over.
                // WITH ORIENTATION, because a phone's clip carries its rotation in the container and
                // a training set of sideways frames is a training set of the wrong problem.
                val image = runCatching { grab.frameWithOrientation }.getOrNull() ?: break
                ImageIO.write(image, "png", File(destination, "frame-%05d.png".format(written)))
                written++
            }
        }.onFailure {
            PrismPlatform.log.warn("ModelCraft", "Could not decode ${video.name}: ${it.message}")
        }
        return written
    }
}
