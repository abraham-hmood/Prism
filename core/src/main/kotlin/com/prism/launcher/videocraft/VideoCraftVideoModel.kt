package com.prism.launcher.videocraft

import com.prism.core.PrismImage
import com.prism.core.PrismPlatform
import com.prism.launcher.PrismSettings
import com.prism.launcher.modelcraft.PixelTrainer
import java.io.File
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * One clip, asked of a video model in a single request.
 *
 * A CLIP IS THE UNIT, not a frame. That is the whole point of the video family: a frame model has to be
 * asked once per frame and cannot know what the previous frame looked like, so motion is whatever
 * interpolation between stills produces. A video model is given the shot and the length and answers a
 * take.
 */
data class VideoCraftVideoRequest(
    val prompt: String,
    val width: Int,
    val height: Int,
    val frameCount: Int,
    val fps: Int,
    val seed: Long,
    val guidance: Double,
    val steps: Int,
    val previous: PrismImage? = null,
)

/**
 * A model that draws a whole clip.
 *
 * Local and cloud are the same interface because the engine's job does not change with where the pixels
 * came from: hand it the shot, get frames back in order. When a source cannot draw -- a checkpoint that
 * failed to load, an endpoint that refused -- it answers null and the engine draws the clip itself with
 * the procedural renderer, never by falling back to the image and interpolation models. Those two are
 * exactly what an active video model is supposed to replace.
 */
interface VideoCraftVideoModel {

    /** What the film's record says drew it. */
    fun label(): String

    /** False when the selection names a model that cannot be opened; the engine then draws it itself. */
    fun available(): Boolean

    /**
     * Draws the clip, handing each frame to [emit] in order as soon as it exists, and answers how many
     * it emitted -- 0 when this source could not draw at all.
     *
     * STREAMED, NOT RETURNED AS A LIST. A five-second take at 24 fps and 768 x 432 is 120 frames of
     * 1.3 MB each; collecting them before the encoder saw one held ~160 MB of pixels on top of a
     * loaded language model, which is how a phone render died without a word in the log.
     */
    fun clip(request: VideoCraftVideoRequest, emit: (PrismImage) -> Unit): Int
}

/**
 * A ModelCraft video model running on this device.
 *
 * ## A rollout, the way it was trained
 *
 * ModelCraft's video trainer learns NEXT-FRAME PREDICTION: given the [PixelTrainer.PREVIOUS_FRAMES]
 * frames before it and the clip's caption, the network answers the frame that follows. This used to be
 * sampled with the image diffusion sampler instead, which hands the network one frame and a timestep --
 * an input of the wrong width -- so every frame threw, the clip came back empty and the film was drawn
 * entirely by the procedural renderer while the record said a video model ran. Now each frame is the
 * network's own prediction from the two before it, conditioned on the shot.
 *
 * ## Where the first frames come from
 *
 * The trainer also learns each clip's opening from blank frames that stand for "before the clip", so a
 * take starts from those and the caption alone chooses the scene. When the film has a previous shot,
 * its last frame takes the place of the most recent blank, which is what makes consecutive shots
 * continuous.
 *
 * ## Native size, then an honest enlargement
 *
 * The same rule as [com.prism.launcher.modelcraft.ModelCraftImageGenerator]: predict at the side the
 * model learned and enlarge to the film's size with bilinear sampling.
 */
class LocalVideoCraftVideoModel(private val path: String) : VideoCraftVideoModel {

    override fun label(): String = "ModelCraft video (on-device)"

    override fun available(): Boolean = directory() != null

    override fun clip(request: VideoCraftVideoRequest, emit: (PrismImage) -> Unit): Int {
        val folder = directory() ?: return 0
        val trained = runCatching {
            PixelTrainer.load(folder, PixelTrainer.Task.VIDEO)
        }.onFailure {
            PrismPlatform.log.warn("VideoCraft", "Video model ${folder.name} failed to load: ${it.message}")
        }.getOrNull() ?: return 0

        val side = trained.config.side.coerceAtLeast(8)
        val count = request.frameCount.coerceAtLeast(2)
        // Guidance is the image family's 7 by default; on a regression output that extrapolates far
        // past any frame the model has seen, so it is capped where the push stays a nudge.
        val guidance = request.guidance.coerceIn(1.0, MAX_GUIDANCE)
        PrismPlatform.log.info(
            "VideoCraft",
            "clip from ${folder.name}: $count frames at ${side}x$side, enlarging to " +
                "${request.width}x${request.height}",
        )

        fun next(window: List<DoubleArray>): DoubleArray =
            trained.nextFrame(window, request.prompt, guidance).also { frame ->
                for (index in frame.indices) frame[index] = frame[index].coerceIn(-1.0, 1.0)
            }

        // BLANK FRAMES MEAN "BEFORE THE CLIP", exactly as the trainer writes its start-of-clip
        // examples: from two blanks the model draws the caption's first frame, and from a blank and
        // the previous shot's last frame it continues that shot rather than repeating it.
        val window = ArrayDeque<DoubleArray>(PixelTrainer.PREVIOUS_FRAMES)
        repeat(PixelTrainer.PREVIOUS_FRAMES) { window.addLast(DoubleArray(3 * side * side)) }
        request.previous?.let { previous ->
            window.removeFirst()
            window.addLast(pixelsOf(previous, side))
        }

        var emitted = 0
        for (index in 0 until count) {
            if (Thread.currentThread().isInterrupted) return emitted
            val frame = runCatching { next(window.toList()) }.onFailure {
                PrismPlatform.log.warn("VideoCraft", "Frame $index failed: ${it.message}")
            }.getOrNull() ?: return emitted
            window.removeFirst()
            window.addLast(frame)
            emit(enlarge(frame, side, request.width, request.height))
            emitted++
        }
        return emitted
    }

    /** A picture as the trainer's three planes of [-1, 1] at [side] square. */
    private fun pixelsOf(image: PrismImage, side: Int): DoubleArray {
        val scaled = image.scaledTo(side, side)
        val plane = side * side
        return DoubleArray(3 * plane) { index ->
            val pixel = scaled.pixels[index % plane]
            val value = when (index / plane) {
                0 -> PrismImage.red(pixel)
                1 -> PrismImage.green(pixel)
                else -> PrismImage.blue(pixel)
            }
            value / 127.5 - 1.0
        }
    }

    /** Bilinear enlargement, with the channel planes unpacked exactly as the trainer packed them. */
    private fun enlarge(pixels: DoubleArray, side: Int, width: Int, height: Int): PrismImage {
        val outWidth = width.coerceIn(64, 2048)
        val outHeight = height.coerceIn(64, 2048)
        val plane = side * side
        val out = IntArray(outWidth * outHeight)
        for (y in 0 until outHeight) {
            val sy = (y.toFloat() / outHeight) * (side - 1)
            val y0 = floor(sy).toInt().coerceIn(0, side - 1)
            val y1 = min(y0 + 1, side - 1)
            val fy = sy - y0
            for (x in 0 until outWidth) {
                val sx = (x.toFloat() / outWidth) * (side - 1)
                val x0 = floor(sx).toInt().coerceIn(0, side - 1)
                val x1 = min(x0 + 1, side - 1)
                val fx = sx - x0
                fun channel(planeIndex: Int): Float {
                    val base = planeIndex * plane
                    val a = pixels[(base + y0 * side + x0).coerceIn(0, pixels.size - 1)].toFloat()
                    val b = pixels[(base + y0 * side + x1).coerceIn(0, pixels.size - 1)].toFloat()
                    val c = pixels[(base + y1 * side + x0).coerceIn(0, pixels.size - 1)].toFloat()
                    val d = pixels[(base + y1 * side + x1).coerceIn(0, pixels.size - 1)].toFloat()
                    val top = a + (b - a) * fx
                    val bottom = c + (d - c) * fx
                    return top + (bottom - top) * fy
                }
                fun byte(value: Float): Int =
                    ((value * 0.5f + 0.5f) * 255f).toInt().coerceIn(0, 255)
                out[y * outWidth + x] = (0xFF shl 24) or
                    (byte(channel(0)) shl 16) or (byte(channel(1)) shl 8) or byte(channel(2))
            }
        }
        return PrismImage(outWidth, outHeight, out)
    }

    /** The folder holding a ModelCraft video artifact, or null when [path] does not name one. */
    private fun directory(): File? {
        if (path.isBlank()) return null
        val file = File(path)
        val task = PixelTrainer.Task.VIDEO
        val directory = when {
            file.isDirectory -> file
            file.name == task.metadataFile || file.name == task.weightsFile -> file.parentFile
            else -> null
        } ?: return null
        return directory.takeIf {
            File(it, task.metadataFile).isFile && File(it, task.weightsFile).isFile
        }
    }

    companion object {
        /** The most a clip request's guidance may push a predicted frame. */
        private const val MAX_GUIDANCE = 2.0
    }
}

/**
 * A cloud video model.
 *
 * ## The contract
 *
 * `POST {baseUrl}/video/generate` with `{model, prompt, width, height, frames, fps, guidance}`, answering
 * `{frames: ["<base64 PNG>", ...]}`. Two shorthands are accepted because endpoints differ: a single
 * `image`, and the OpenAI-shaped `data[0].b64_json`. Frames come back as PNGs rather than as an MP4
 * because the encoder already accepts frames, and decoding a video here would add a container parser to
 * the core for no gain.
 *
 * ## A refusal is remembered
 *
 * One refusal means the rest of the film is drawn on-device, and the label says so. Asking again for
 * every clip would spend a minute per refusal on a phone, and the film has to finish.
 */
class CloudVideoCraftVideoModel(
    private val profile: PrismSettings.CloudModelProfile,
) : VideoCraftVideoModel {

    @Volatile private var refused: Boolean = false
    @Volatile private var lastReason: String? = null
    @Volatile private var usedCloud: Boolean = false

    override fun label(): String = when {
        refused -> "on-device (cloud video refused: ${lastReason ?: "no answer"})"
        else -> "cloud video model (${profile.modelId})"
    }

    override fun available(): Boolean =
        !refused && profile.baseUrl.isNotBlank() && profile.apiKey.isNotBlank()

    override fun clip(request: VideoCraftVideoRequest, emit: (PrismImage) -> Unit): Int {
        if (!available()) return 0
        // WRITTEN BY HAND, not through a JSON library: this object is seven flat fields of known
        // types, and the core has no JSON dependency it can rely on for a request body on both
        // targets.
        val body = "{" +
            "\"model\":\"" + escape(profile.modelId) + "\"," +
            "\"prompt\":\"" + escape(request.prompt) + "\"," +
            "\"width\":" + request.width + "," +
            "\"height\":" + request.height + "," +
            "\"frames\":" + request.frameCount + "," +
            "\"fps\":" + request.fps + "," +
            "\"guidance\":" + request.guidance +
            "}"
        val answer = post(body.toString()) ?: return 0
        val frames = decode(answer, max(request.width, request.height)) ?: run {
            refused = true
            lastReason = "the answer held no frames"
            return 0
        }
        usedCloud = true
        for (frame in frames) {
            emit(
                if (frame.width == request.width && frame.height == request.height) frame
                else VideoCraftImageOps.fit(frame, request.width, request.height),
            )
        }
        return frames.size
    }

    private fun post(body: String): String? {
        val url = java.net.URL(profile.baseUrl.trimEnd('/') + "/video/generate")
        val connection = runCatching { url.openConnection() as java.net.HttpURLConnection }
            .getOrNull() ?: return null
        return runCatching {
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 240_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer ${profile.apiKey}")
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            connection.disconnect()
            if (code !in 200..299) {
                // A refusal and a slow answer are different: only the first is remembered.
                if (code in 400..499) {
                    refused = true
                    lastReason = "HTTP $code"
                }
                PrismPlatform.log.warn(
                    "VideoCraft",
                    "cloud video endpoint '${profile.modelId}' answered HTTP $code",
                )
                return null
            }
            text
        }.onFailure {
            PrismPlatform.log.warn("VideoCraft", "cloud video request failed: ${it.message}")
        }.getOrNull()
    }

    /**
     * Base64 PNGs to images.
     *
     * Each one goes through a temporary file rather than a new decoder: the platform codec is the one
     * that exists on both targets, and a base64 decoder in the core would be a second implementation of
     * PNG to keep correct.
     */
    private fun decode(answer: String, maxSide: Int): List<PrismImage>? {
        // A LIST OF FRAMES WHEN THE ENDPOINT HAS ONE, otherwise the single-frame shorthands. The base64
        // alphabet has no quotes, so finding the string literals inside the array is unambiguous.
        val block = FRAMES_KEYS.firstNotNullOfOrNull { key ->
            Regex("\"" + key + "\"\\s*:\\s*\\[(.*?)]", RegexOption.DOT_MATCHES_ALL)
                .find(answer)?.groupValues?.getOrNull(1)
        }
        val encoded = if (block != null) {
            Regex("\"([A-Za-z0-9+/=\\s]+)\"").findAll(block)
                .map { it.groupValues[1].filterNot(Char::isWhitespace) }
                .filter { it.isNotEmpty() }
                .toList()
        } else {
            SINGLE_KEYS.mapNotNull { key ->
                Regex("\"" + key + "\"\\s*:\\s*\"([^\"]+)\"").find(answer)?.groupValues?.getOrNull(1)
            }
        }
        if (encoded.isEmpty()) return null
        val frames = ArrayList<PrismImage>(encoded.size)
        for (entry in encoded) {
            val bytes = runCatching { java.util.Base64.getDecoder().decode(entry) }.getOrNull() ?: continue
            val file = runCatching {
                File.createTempFile("prism-videocraft-", ".png").apply { writeBytes(bytes) }
            }.getOrNull() ?: continue
            val image = runCatching {
                PrismPlatform.images.decode(file, maxSide)
            }.getOrNull()
            file.delete()
            if (image != null) frames.add(image)
        }
        return frames.takeIf { it.isNotEmpty() }
    }

    /** JSON string escaping for the two fields that can hold quotes. */
    private fun escape(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", " ")

    companion object {
        private val FRAMES_KEYS = listOf("frames", "images")
        private val SINGLE_KEYS = listOf("image", "b64_json")
    }
}

/**
 * The video model for the current selection, cloud or local.
 *
 * Mirrors [VideoCraftCloudInterpolator.create]: one place decides, both platforms ask it, and a cloud
 * selection with no active profile is a missing model rather than a silent local run.
 */
object VideoCraftVideoRouter {

    /** Null when no video model is selected, which is what keeps the frame route in charge. */
    fun create(): VideoCraftVideoModel? {
        val path = PrismSettings.getLocalVideoModelPath()
        val cloud = PrismSettings.getActiveCloudModel(PrismSettings.KIND_VIDEO)
        return when {
            path == PrismSettings.CLOUD_MODEL_SENTINEL -> cloud?.let(::CloudVideoCraftVideoModel)
            path.startsWith("cloud:") -> cloud?.let(::CloudVideoCraftVideoModel)
            path.isNotEmpty() -> LocalVideoCraftVideoModel(path)
            else -> null
        }
    }

    /** True when the user has chosen a video model at all, which changes what the page requires. */
    fun selected(): Boolean = PrismSettings.hasVideoModelForVideo()
}
