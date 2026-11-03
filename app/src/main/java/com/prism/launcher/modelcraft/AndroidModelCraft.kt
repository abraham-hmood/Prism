package com.prism.launcher.modelcraft

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import com.prism.core.PrismPlatform
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min

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
    private fun extractFrames(
        video: File,
        destination: File,
        wholeClip: Boolean,
        onFrame: (done: Int, total: Int) -> Unit,
    ): Int {
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
            // WHOLE CLIP means every frame the decoder will give, evenly spaced from the first
            // to the last millisecond. The cap is lifted rather than the sampling rate raised,
            // because "the entire video" is about coverage, not density.
            val limit = if (wholeClip) Int.MAX_VALUE else MAX_FRAMES_PER_VIDEO
            val step = if (wholeClip) max(1L, durationMs / 4_000L) else max(STEP_MS, durationMs / MAX_FRAMES_PER_VIDEO)
            // HOW MANY THIS CLIP WILL YIELD, before the first decode: the loop walks the duration in
            // [step] increments, so the count is arithmetic rather than a guess, capped by whatever
            // limit applies. That is what lets the page say how many frames are still to come.
            val total = min(((durationMs + step - 1) / step).toInt(), limit).coerceAtLeast(1)
            var at = 0L
            var index = 0
            while (at < durationMs && index < limit) {
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
                onFrame(index, total)
            }
        } catch (t: Throwable) {
            PrismPlatform.log.warn("ModelCraft", "Could not decode ${video.name}: ${t.message}")
        } finally {
            runCatching { retriever.release() }
        }
        return written
    }
}
