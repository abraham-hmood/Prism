package com.prism.launcher.modelcraft

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import com.prism.core.PrismPlatform
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

/**
 * The Android half of ModelCraft: turning a video into the frames a frame-interpolator model trains on.
 *
 * A frame-interpolation dataset is a set of TRIPLETS -- frames one and three as input, frame two as
 * the answer -- so the only thing the trainer needs from a video is its frames, in order, named so
 * their sequence survives. MediaMetadataRetriever is the platform's own decoder and the only one
 * guaranteed to exist on every device, which is why the extraction is here rather than in the shared
 * code: the desktop build does the same job with its own decoder.
 */
object AndroidModelCraft {

    /** Frames pulled from one video. Enough for thousands of triplets, short of filling a disk. */
    private const val MAX_FRAMES_PER_VIDEO = 48

    /** One frame per this many milliseconds, so a long clip is sampled rather than truncated. */
    private const val STEP_MS = 500L

    /** Wires the shared code's hooks to this platform. Idempotent. */
    fun install() {
        ModelCraft.frameExtractor = ::extractFrames
        // The audio trainer is shared code and runs on the device, so it only has to be plugged in.
        ModelCraft.installDefaultTrainer()
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
        val retriever = MediaMetadataRetriever()
        var written = 0
        try {
            retriever.setDataSource(video.absolutePath)
            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            if (durationMs <= 0) return 0
            val step = max(STEP_MS, durationMs / MAX_FRAMES_PER_VIDEO)
            var at = 0L
            var index = 0
            while (at < durationMs && index < MAX_FRAMES_PER_VIDEO) {
                val frame: Bitmap? = retriever.getFrameAtTime(
                    at * 1000L,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                )
                if (frame != null) {
                    val name = "frame-%05d.png".format(index)
                    val ok = runCatching {
                        FileOutputStream(File(destination, name)).use { out ->
                            frame.compress(Bitmap.CompressFormat.PNG, 100, out)
                        }
                    }.isSuccess
                    if (ok) written++
                    if (!frame.isRecycled) frame.recycle()
                }
                at += step
                index++
            }
        } catch (t: Throwable) {
            PrismPlatform.log.warn("ModelCraft", "Could not decode ${video.name}: ${t.message}")
        } finally {
            runCatching { retriever.release() }
        }
        return written
    }
}
