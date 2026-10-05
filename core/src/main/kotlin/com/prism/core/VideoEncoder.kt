package com.prism.core

import java.io.File

/**
 * Turning a sequence of frames into a playable video file. PHASE 42.
 *
 * ## Why this is a capability
 *
 * Nora's `/video` already produces the frames — [com.prism.launcher.nora.MentalImagery.generateVideo] has
 * been in :core since Phase 2 and returns a `List<PrismImage>`. What differs per platform is only the
 * muxing: Android has `MediaCodec` and `MediaMuxer` driving a hardware H.264 encoder, and a desktop JVM
 * has neither. Both produce an mp4; nothing above this line needs to know which did.
 *
 * ## Why the default refuses rather than writing something
 *
 * A [NoOpVideoEncoder] that wrote a GIF, or a folder of PNGs, or a zero-byte mp4 would satisfy the type
 * and produce a file the user cannot play. Refusing with a reason means a platform that has not installed
 * an encoder says so, which is the same disabled-not-hidden rule the image and vision capabilities follow.
 */
interface VideoEncoder {

    /** What this encoder is, for a UI that has to explain an absence. */
    val label: String

    /** Null when this encoder can be used, otherwise why not. */
    fun unavailableReason(): String?

    /**
     * Writes [frames] to [destination] as an mp4.
     *
     * Blocking, and potentially slow: a few hundred frames of software H.264 is seconds to minutes.
     *
     * FRAMES MUST ALL BE THE SAME SIZE. Every encoder here configures itself from the first frame, so a
     * differently-sized frame later in the list is either cropped or rejected depending on the
     * implementation — and Nora's generator emits a fixed size, so a mismatch means a caller has mixed
     * two runs.
     *
     * @return the file on success, or null. The reason goes to the log; [unavailableReason] covers the
     *   predictable failure and a null here is an unexpected one.
     */
    fun encode(
        frames: List<PrismImage>,
        destination: File,
        fps: Int = 12,
        onProgress: ((Int, Int) -> Unit)? = null,
    ): File?
}

/**
 * The default: no encoder.
 *
 * Installed until a platform replaces it, so `/video` on a build with no encoder says what is missing
 * instead of failing somewhere inside a muxer.
 */
object NoOpVideoEncoder : VideoEncoder {

    override val label: String = "none"

    override fun unavailableReason(): String =
        "This build has no video encoder installed, so frames can be generated but not muxed into a " +
            "playable file."

    override fun encode(
        frames: List<PrismImage>,
        destination: File,
        fps: Int,
        onProgress: ((Int, Int) -> Unit)?,
    ): File? = null
}

/**
 * Writes each frame as a numbered PNG instead of a video.
 *
 * A DELIBERATE FALLBACK AND NOT A PRETEND ENCODER. It does not claim to produce a video — [label] and
 * [unavailableReason] both say what it is — and it exists because frames on disk are genuinely useful:
 * they can be assembled by ffmpeg, opened in an editor, or flipped through. The alternative for a
 * platform with no muxer is discarding minutes of generation, which is worse.
 *
 * It writes a SIBLING DIRECTORY rather than the file it was asked for, so nothing downstream mistakes the
 * result for a video: a caller that hands the returned path to a player gets a directory and fails
 * immediately, rather than playing a file that is not one.
 */
class FrameDumpVideoEncoder : VideoEncoder {

    override val label: String = "PNG frames (no muxer)"

    override fun unavailableReason(): String? = null

    override fun encode(
        frames: List<PrismImage>,
        destination: File,
        fps: Int,
        onProgress: ((Int, Int) -> Unit)?,
    ): File? {
        if (frames.isEmpty()) return null
        val folder = File(destination.parentFile, destination.nameWithoutExtension + "-frames")
        folder.mkdirs()

        var written = 0
        frames.forEachIndexed { index, frame ->
            // Zero-padded so a shell glob and an ffmpeg pattern both order them correctly. Unpadded
            // numbers sort frame10 before frame2, which assembles the clip in the wrong order.
            val file = File(folder, "frame-%05d.png".format(index))
            if (PrismPlatform.images.encodePng(frame, file)) written++
            onProgress?.invoke(index + 1, frames.size)
        }
        if (written == 0) return null

        // A note beside the frames, because somebody finding this directory later will want to know what
        // to do with it and the filenames alone do not say.
        runCatching {
            File(folder, "README.txt").writeText(
                "Nora generated $written frames at $fps fps.\n\n" +
                    "This build had no video muxer, so the frames were written here instead of being\n" +
                    "assembled into an mp4. To make one:\n\n" +
                    "  ffmpeg -framerate $fps -i frame-%05d.png -c:v libx264 -pix_fmt yuv420p out.mp4\n"
            )
        }
        return folder
    }
}
