package com.prism.launcher.messaging

import android.content.Context
import com.prism.core.PrismImage
import com.prism.launcher.PrismSettings
import com.prism.launcher.platform.AndroidImageCodec
import java.io.File
import kotlinx.coroutines.runBlocking

/**
 * A MediaPipe `.task` bundle as an [ImageGenerator], so ImageCraft can draw with one.
 *
 * ## Why this is not just a second diffusion route
 *
 * `.task` bundles are a different format from a Stable Diffusion checkpoint: they are MediaPipe graphs
 * with the UNet, the text encoder and the scheduler compiled into one file, and stable-diffusion.cpp
 * cannot open them. Both are in the download list, so both have to be selectable or half the users who
 * picked "an image model" would find the page dark with no explanation.
 *
 * ## What it cannot do, stated rather than discovered
 *
 * There is no size knob in MediaPipe's generator: the output is whatever the bundle was exported with,
 * usually 512 square. A resolution setting therefore cannot be honoured by this engine, and instead of
 * silently returning a 512 image the engine says so in its stage message. That is the same rule the
 * rest of Prism follows -- fall back, but never silently.
 *
 * It also cannot do img2img, which is why VideoCraft's frame continuity is applied afterwards in
 * Kotlin rather than by the engine.
 */
class MediaPipeImageGenerator(private val appContext: Context) : ImageGenerator {

    override val id = "mediapipe"
    override val label = "MediaPipe (on-device)"
    override val description =
        "A `.task` bundle -- a diffusion graph compiled by MediaPipe. Runs entirely on this device, " +
            "draws at the size the bundle was exported with, and cannot do img2img."

    override fun availability(): ImageGenerator.Availability {
        val path = PrismSettings.getLocalImageModelPath()
        if (path.isBlank()) {
            return ImageGenerator.Availability.Unavailable(
                "No local image model is active. Import a `.task` bundle on the Models page.",
            )
        }
        if (!path.endsWith(".task", ignoreCase = true)) {
            return ImageGenerator.Availability.Unavailable(
                "The active local image model (${File(path).name}) is not a MediaPipe bundle. This " +
                    "engine opens `.task` files; checkpoints go through stable-diffusion.cpp.",
            )
        }
        if (!File(path).isFile) {
            return ImageGenerator.Availability.Unavailable("$path is missing.")
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
        if (path.isBlank() || !File(path).isFile) return null

        if (size != null && size != ImageSize.DEFAULT) {
            onStage?.invoke(
                "MediaPipe draws at the bundle's own size; the ${size.label} setting does not apply " +
                    "to this engine.",
            )
        }
        onStage?.invoke("Loading ${File(path).name}…")
        // MediaPipe's create-and-generate is one opaque native call with no step callback, so no
        // progress is reported: the bar stays indeterminate rather than showing invented numbers.
        return runCatching {
            val bitmap = runBlocking {
                LocalImageService.generateImage(appContext, path, prompt)
            } ?: return null
            AndroidImageCodec.fromBitmap(bitmap)
        }.getOrNull()
    }
}
