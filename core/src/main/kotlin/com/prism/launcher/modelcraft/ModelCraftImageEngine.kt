package com.prism.launcher.modelcraft

import com.prism.core.PrismImage
import com.prism.core.PrismPlatform
import com.prism.launcher.PrismSettings
import com.prism.launcher.messaging.ImageGenerator
import com.prism.launcher.messaging.ImageSize
import java.io.File
import java.util.Random
import kotlin.math.floor

/**
 * A ModelCraft image model as an [ImageGenerator], so a model the user trained here can draw.
 *
 * ## Why this lives in :core
 *
 * ModelCraft's image trainer is portable: it writes `image_model.json` and `image_model.weights` on
 * the phone and on the desktop. The page, ImageCraft, VideoCraft and GameCraft all run on both, so
 * the engine that reads those two files must be portable as well. Only registration differs per
 * platform, exactly like the other image engines.
 *
 * ## Native size, then an honest enlargement
 *
 * PixelTrainer trains a 32 x 32 model; that is the resolution a phone can train and sample. Asking
 * it for 1024 would be asking for pixels the network never learned. This engine therefore samples
 * at the model's own `side`, says so on the progress line, and enlarges the result to the requested
 * size with bilinear sampling plus a small Prism-owned detail pass. The content is the model's; the
 * enlargement is stated rather than hidden. A blurry upscale would be the worse alternative: it is
 * honest about the source but useless as a game texture.
 */
class ModelCraftImageGenerator : ImageGenerator {

    override val id = ID
    override val label = "ModelCraft (on-device)"
    override val description =
        "An image model trained in ModelCraft. Runs on this device, samples at the resolution it was " +
            "trained at, and enlarges to the page's requested size with Prism's own detail pass."

    override fun availability(): ImageGenerator.Availability {
        val path = PrismSettings.getLocalImageModelPath()
        if (path.isBlank()) {
            return ImageGenerator.Availability.Unavailable(
                "No local image model is active. Train one in ModelCraft's Image tab and it is " +
                    "registered automatically, then activate it on the Models page.",
            )
        }
        val directory = directoryOf(path)
            ?: return ImageGenerator.Availability.Unavailable(
                "${File(path).name} is not a ModelCraft image model: the folder needs " +
                    "${PixelTrainer.Task.IMAGE.metadataFile} and ${PixelTrainer.Task.IMAGE.weightsFile}.",
            )
        if (PixelTrainer.load(directory, PixelTrainer.Task.IMAGE) == null) {
            return ImageGenerator.Availability.Unavailable(
                "The ModelCraft image model in ${directory.name} could not be read.",
            )
        }
        return ImageGenerator.Availability.Ready
    }

    override fun generate(
        prompt: String,
        onStage: ((String) -> Unit)?,
        onProgress: ((Float) -> Unit)?,
        size: ImageSize?,
    ): PrismImage? {
        val path = PrismSettings.getLocalImageModelPath()
        val directory = directoryOf(path) ?: return null
        onStage?.invoke("Loading ${directory.name}…")
        val trained = PixelTrainer.load(directory, PixelTrainer.Task.IMAGE) ?: return null
        val side = trained.config.side.coerceAtLeast(8)
        val native = ImageSize(side, side)
        val target = size ?: ImageSize(512, 512)
        if (target != native) {
            onStage?.invoke(
                "Sampling at ${native.label}, the resolution this model learned, then enlarging " +
                    "to ${target.label}.",
            )
        } else {
            onStage?.invoke("Sampling at ${native.label}…")
        }
        val seed = prompt.fold(System.currentTimeMillis()) { value, character ->
            value * 31 + character.code
        }
        val pixels = runCatching {
            // THE USER'S INFERENCE MODE, not a fixed schedule. A model trained here is the one
            // image surface that runs entirely on this device, so it is the one where the mode's
            // pass budget is actually spendable.
            trained.generate(
                prompt, Random(seed),
                steps = PixelTrainer.SAMPLE_STEPS,
                plan = com.prism.launcher.messaging.InferencePlan.current(),
            )
        }.onFailure {
            PrismPlatform.log.warn("ModelCraft", "Image sampling failed: ${it.message}")
        }.getOrNull() ?: return null
        onProgress?.invoke(0.92f)
        val image = compose(pixels, side, target, seed)
        onProgress?.invoke(1f)
        return image
    }

    /**
     * Bilinear enlargement plus one deterministic detail octave.
     *
     * The detail lives in the target's high frequencies, not in the model's latent: it is Prism's
     * texture pass, which is exactly what GameCraft already does when no image model exists. The
     * difference is that here the model supplies the palette and composition, so a prompt still
     * changes the result.
     */
    private fun compose(pixels: DoubleArray, side: Int, target: ImageSize, seed: Long): PrismImage {
        val width = target.width.coerceIn(64, 2048)
        val height = target.height.coerceIn(64, 2048)
        val plane = side * side
        val out = IntArray(width * height)
        val detailScale = 1f + minOf(3f, width.toFloat() / side)
        for (y in 0 until height) {
            val sy = (y.toFloat() / height) * (side - 1)
            val y0 = floor(sy).toInt().coerceIn(0, side - 1)
            val y1 = (y0 + 1).coerceAtMost(side - 1)
            val fy = sy - y0
            for (x in 0 until width) {
                val sx = (x.toFloat() / width) * (side - 1)
                val x0 = floor(sx).toInt().coerceIn(0, side - 1)
                val x1 = (x0 + 1).coerceAtMost(side - 1)
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
                val noise = valueNoise(x * detailScale / width * 18f, y * detailScale / height * 18f, seed) -
                    0.5f
                fun byte(value: Float): Int =
                    ((value * 0.5f + 0.5f + noise * 0.055f) * 255f).toInt().coerceIn(0, 255)
                val r = byte(channel(0))
                val g = byte(channel(1))
                val b = byte(channel(2))
                out[y * width + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return PrismImage(width, height, out)
    }

    private fun valueNoise(x: Float, y: Float, seed: Long): Float {
        val xi = floor(x).toInt()
        val yi = floor(y).toInt()
        val xf = x - xi
        val yf = y - yi
        fun hash(px: Int, py: Int): Float {
            var h = px.toLong() * 374761393L + py * 668265263L + seed * 1442695040888963407L
            h = (h xor (h shr 13)) * 1274126177L
            h = h xor (h shr 16)
            return ((h and 0xFFFF).toFloat() / 0xFFFF.toFloat())
        }
        fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
        val sx = xf * xf * (3f - 2f * xf)
        val sy = yf * yf * (3f - 2f * yf)
        return lerp(
            lerp(hash(xi, yi), hash(xi + 1, yi), sx),
            lerp(hash(xi, yi + 1), hash(xi + 1, yi + 1), sx),
            sy,
        )
    }

    companion object {
        const val ID = "modelcraft"

        /** The folder holding a ModelCraft image artifact, or null when [path] does not name one. */
        fun directoryOf(path: String): File? {
            if (path.isBlank()) return null
            val file = File(path)
            val directory = when {
                file.isDirectory -> file
                file.name == PixelTrainer.Task.IMAGE.metadataFile ||
                    file.name == PixelTrainer.Task.IMAGE.weightsFile -> file.parentFile
                else -> null
            } ?: return null
            val metadata = File(directory, PixelTrainer.Task.IMAGE.metadataFile)
            val weights = File(directory, PixelTrainer.Task.IMAGE.weightsFile)
            return if (metadata.isFile && weights.isFile) directory else null
        }

        /** A display name for the Models page: the run's name when its metadata has one. */
        fun displayName(path: String): String {
            val directory = directoryOf(path) ?: return File(path).name
            return runCatching {
                val text = File(directory, PixelTrainer.Task.IMAGE.metadataFile).readText()
                Regex("\"name\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.getOrNull(1)
            }.getOrNull()?.takeIf { it.isNotBlank() } ?: directory.name
        }
    }
}
