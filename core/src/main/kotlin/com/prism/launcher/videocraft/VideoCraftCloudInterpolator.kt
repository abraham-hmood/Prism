package com.prism.launcher.videocraft

import com.prism.core.PrismImage
import com.prism.core.PrismPlatform
import com.prism.launcher.PrismSettings
import com.prism.launcher.messaging.CloudAiService

/**
 * Frame interpolation on a cloud model.
 *
 * ## What the label used to say, and why it was honest
 *
 * A cloud interpolation profile satisfied the creator's gate and the frames were then made by Prism
 * Flow on-device, with the label admitting it: "Prism Flow (no cloud interpolation route)". That was
 * correct -- there was no route -- and it meant the one option a phone with no local model could use was
 * a profile that did nothing. This is the route.
 *
 * ## The contract
 *
 * `POST {baseUrl}/video/interpolate` with `{model, t, first, second}`, where the two frames are base64
 * PNGs and `t` is the position between them, answering `{image: "<base64 PNG>"}` (or the same under
 * `data[0].b64_json`, which is what an OpenAI-shaped endpoint returns). The full-resolution frames are
 * sent rather than downscaled ones: an interpolated frame's whole value is its detail, and the endpoint
 * is the one that decided what it can accept.
 *
 * ## The two frames are sent once
 *
 * A clip interpolates between the same pair of keyframes many times -- every frame between them -- so the
 * encodings are cached per pair. Without that, a 48-frame clip with 6 keyframes would send 48 pairs of
 * full-resolution PNGs to draw frames the endpoint could answer from the same two images.
 *
 * ## When it fails
 *
 * A refusal (a 404, a key the endpoint rejects, an answer that is not an image) is remembered, and the
 * rest of the film is interpolated by Prism Flow -- visibly, because [label] says so by then, and the
 * project's record of the engine is the label. A slow answer is not a refusal: that frame falls back and
 * the next one asks again.
 */
class VideoCraftCloudInterpolator(
    private val profile: PrismSettings.CloudModelProfile,
) : VideoCraftInterpolator {

    @Volatile private var refused = false
    @Volatile private var usedCloud = false
    @Volatile private var lastReason: String? = null

    /** The pair the last request was built from, and its encodings. One pair is all a clip needs. */
    private var cachedFrom: PrismImage? = null
    private var cachedTo: PrismImage? = null
    private var cachedFromPng = ""
    private var cachedToPng = ""

    override fun interpolate(from: PrismImage, to: PrismImage, t: Double): PrismImage =
        interpolate(from, to, t, 2)

    override fun interpolate(from: PrismImage, to: PrismImage, t: Double, quality: Int): PrismImage {
        if (refused) return flow(from, to, t, quality)
        val images = encodePair(from, to)
        if (images == null) {
            lastReason = "the frames could not be encoded"
            refused = true
            return flow(from, to, t, quality)
        }
        val result = CloudAiService.fetchInterpolation(
            baseUrl = profile.baseUrl,
            apiKey = profile.apiKey,
            first = images.first,
            second = images.second,
            t = t,
            modelId = profile.modelId,
            timeoutMs = 120_000,
        )
        val frame = result.image
        if (frame != null) {
            usedCloud = true
            // The endpoint returns whatever size it likes; the film is the project's size.
            return if (frame.width == from.width && frame.height == from.height) frame
            else VideoCraftImageOps.fit(frame, from.width, from.height)
        }
        if (!result.retryable) {
            refused = true
            lastReason = "the cloud interpolator refused (${result.detail})"
            PrismPlatform.log.warn(
                "VideoCraft",
                "Cloud interpolation endpoint '${profile.modelId}' refused (${result.detail}); " +
                    "the rest of this film is interpolated by Prism Flow",
            )
        } else {
            lastReason = "the cloud interpolator did not finish this frame (${result.detail})"
            PrismPlatform.log.warn(
                "VideoCraft",
                "Cloud interpolation endpoint '${profile.modelId}' did not finish a frame " +
                    "(${result.detail}); drawing it with Prism Flow and asking again on the next",
            )
        }
        return flow(from, to, t, quality)
    }

    override fun label(): String = when {
        usedCloud && !refused -> "cloud interpolator (${profile.modelId})"
        usedCloud -> "Prism Flow (cloud refused: ${lastReason ?: "no answer"})"
        refused -> "Prism Flow (${lastReason ?: "the cloud endpoint refused"})"
        else -> "cloud interpolator (${profile.modelId}) · Prism Flow until it answers"
    }

    private fun flow(from: PrismImage, to: PrismImage, t: Double, quality: Int): PrismImage =
        OpticalFlowInterpolator.interpolate(from, to, t, quality)

    private class Pair(val first: String, val second: String)

    private fun encodePair(from: PrismImage, to: PrismImage): Pair? {
        if (from === cachedFrom && to === cachedTo) return Pair(cachedFromPng, cachedToPng)
        return runCatching {
            // RasterCodec, not the platform codec: the PNG writer is in the core, it is the one that
            // answers on every target, and the request body needs bytes rather than a file either way.
            val first = java.util.Base64.getEncoder()
                .encodeToString(com.prism.core.RasterCodec.encodePngBytes(from))
            val second = java.util.Base64.getEncoder()
                .encodeToString(com.prism.core.RasterCodec.encodePngBytes(to))
            cachedFrom = from
            cachedTo = to
            cachedFromPng = first
            cachedToPng = second
            Pair(first, second)
        }.getOrNull()
    }

    companion object {

        /**
         * The interpolator for the selected model, cloud or otherwise.
         *
         * Shared by both platforms because the choice is the same on both: a cloud profile when one is
         * active, an imported ONNX model when the selection names a file, Prism Flow otherwise -- and a
         * `cloud:` selection with no active profile says so in its label rather than silently claiming a
         * model ran.
         */
        fun create(): VideoCraftInterpolator {
            val path = PrismSettings.getLocalInterpolationModelPath()
            return when {
                path == PrismSettings.BUILTIN_INTERPOLATION || path.isEmpty() -> prismFlow("Prism Flow")
                path.startsWith("cloud:") -> {
                    val profile = PrismSettings.getActiveCloudModel(PrismSettings.KIND_INTERPOLATION)
                    if (profile == null) {
                        prismFlow("Prism Flow (no cloud interpolation profile is active)")
                    } else {
                        VideoCraftCloudInterpolator(profile)
                    }
                }
                java.io.File(path).extension.equals("onnx", true) -> VideoCraftOnnxInterpolator(path)
                else -> prismFlow(java.io.File(path).name + " (Prism Flow fallback)")
            }
        }

        private fun prismFlow(label: String): VideoCraftInterpolator = object : VideoCraftInterpolator {
            override fun interpolate(from: PrismImage, to: PrismImage, t: Double) = interpolate(from, to, t, 2)
            override fun interpolate(from: PrismImage, to: PrismImage, t: Double, quality: Int) =
                OpticalFlowInterpolator.interpolate(from, to, t, quality)
            override fun label() = label
        }
    }
}
