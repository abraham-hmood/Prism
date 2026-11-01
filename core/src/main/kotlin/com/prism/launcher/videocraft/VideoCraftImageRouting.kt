package com.prism.launcher.videocraft

import com.prism.core.PrismImage
import com.prism.core.PrismPlatform
import com.prism.launcher.PrismSettings
import com.prism.launcher.diffusion.DiffusionVideoCraftImageModel
import com.prism.launcher.messaging.CloudAiService
import java.io.File

/**
 * Chooses which image engine draws a VideoCraft frame: a cloud image endpoint, a local diffusion
 * checkpoint, or the procedural renderer when neither can answer.
 *
 * ## Why this is in :core and not in each platform's services file
 *
 * It was in both, and the two copies had already diverged in exactly the way that matters: Android
 * asked MediaPipe for pixels and desktop asked **Nora for its mental imagery**. That is why a
 * desktop user who selected SD-Turbo got a predictive-coding impression of the prompt and was told
 * the local model had drawn it. One implementation means one answer to "which engine ran", and the
 * label that reports it cannot disagree with the pixels.
 *
 * ## Order of preference
 *
 *  1. **A cloud IMAGE profile**, when one is active. It is the only route that produces a
 *     photographic frame with no model on disk, and the user chose it deliberately.
 *  2. **The active local checkpoint**, through stable-diffusion.cpp. This is the route the creator
 *     page is really about: img2img from the previous keyframe at low denoise.
 *  3. **The procedural renderer**, which always works and is always labelled as itself. It exists so
 *     a render with a damaged model file finishes rather than stopping at frame one, and its label
 *     says "Prism Renderer" so nobody mistakes it for the checkpoint they selected.
 */
class VideoCraftImageRouter : VideoCraftImageModel {

    private val local = DiffusionVideoCraftImageModel()

    @Volatile private var usedCloud = false
    @Volatile private var usedFallback = false
    @Volatile private var lastReason: String? = null

    /**
     * Set when the cloud endpoint DEFINITIVELY refuses to draw -- a 404 for a model it does not
     * serve, a rejected key, a malformed answer.
     *
     * A slow endpoint is not a refusal. Diffusion behind a cloud queue can take minutes, and the
     * first version treated any missing frame as "this endpoint will never answer", so one cold
     * start or one read timeout sent the entire rest of the film to the procedural renderer. Now a
     * transient failure costs that frame and the next keyframe asks again; only a refusal that
     * cannot change stops the route.
     */
    @Volatile private var cloudRefused = false

    /**
     * Set by [cancel], read by a cloud request between its attempts.
     *
     * A blocking HTTP read cannot be interrupted from here, so a cloud keyframe finishes its own
     * request. This cannot do anything about that one, and does not try: what it prevents is the second
     * one. The cloud route retries once and sleeps three seconds between attempts, so without it a
     * Cancel pressed during a slow diffusion endpoint bought another full timeout. Cleared when a frame
     * is asked for, because a frame is only asked for by a render that is running.
     */
    @Volatile private var cancelRequested = false

    override fun generate(request: VideoCraftImageRequest): PrismImage? {
        cancelRequested = false
        val path = PrismSettings.getLocalImageModelPath()
        val cloudImage = PrismSettings.getActiveCloudModel(PrismSettings.KIND_IMAGE)

        val wantsCloud = path == PrismSettings.CLOUD_MODEL_SENTINEL ||
            (path.isEmpty() && cloudImage != null)

        if (wantsCloud && cloudImage != null && !cloudRefused) {
            // WAIT FOR THE FRAME UNTIL DIFFUSION FINISHES. A keyframe is the one image in this app
            // where arriving late is strictly better than arriving wrong: the alternative is the
            // procedural renderer drawing something that has nothing to do with the model the user
            // selected. Ten minutes is not arbitrary -- it is longer than any diffusion endpoint
            // this app talks to takes to answer or fail, including a cold queue -- and the second
            // attempt gives a provider that dropped the connection another full allowance rather
            // than throwing away the frame it was already computing.
            val result = CloudAiService.fetchImageResult(
                cloudImage.baseUrl,
                cloudImage.apiKey,
                request.prompt,
                modelId = cloudImage.modelId,
                timeoutMs = 600_000,
                attempts = 2,
                cancel = { cancelRequested },
            )
            val generated = result.image
            if (generated != null) {
                // Only a frame that actually came back is labelled as the cloud's.
                usedCloud = true
                // The endpoint picks its own size (usually 1024x1024 or 512x512), and the video is
                // the project's size, so the frame is fitted rather than merely returned: an
                // encoder handed the wrong dimensions writes a corrupt track.
                val fitted = VideoCraftImageOps.fit(generated, request.width, request.height)
                return VideoCraftImageOps.img2img(fitted, request.init, request.denoise, request.seed)
            }
            if (result.detail == "cancelled") {
                // A cancelled request is not the endpoint's judgement on anything, so it must not be
                // remembered as a refusal: the next run of this film asks the same endpoint again.
                lastReason = "the cloud image request was cancelled"
            } else if (!result.retryable) {
                cloudRefused = true
                lastReason = "the cloud endpoint refused an image (${result.detail})"
                PrismPlatform.log.warn(
                    "VideoCraft",
                    "Cloud image endpoint '${cloudImage.modelId}' refused (${result.detail}); the " +
                        "rest of this film is drawn locally",
                )
            } else {
                // This frame falls back, but the endpoint is still working and will be asked again
                // for the next keyframe. That is what "wait for diffusion" means per frame: one
                // slow shot does not condemn the rest of the film.
                lastReason = "the cloud endpoint was still diffusing this keyframe (${result.detail})"
                PrismPlatform.log.warn(
                    "VideoCraft",
                    "Cloud image endpoint '${cloudImage.modelId}' did not finish this keyframe " +
                        "(${result.detail}); drawing it locally and asking again on the next",
                )
            }
        } else if (wantsCloud) {
            lastReason = "the cloud endpoint had already refused an image"
        }

        // A MediaPipe `.task` bundle is not a checkpoint stable-diffusion.cpp can open, so it goes to
        // the platform handler Android installs. Everything else is a checkpoint for the shared
        // diffusion engine.
        if (VideoCraftLocalImages.isMediaPipeBundle(path) && File(path).isFile) {
            val handler = VideoCraftLocalImages.mediaPipe
            if (handler != null) {
                val image = handler(request)
                if (image != null) return image
                lastReason = "${File(path).name} produced no frame"
            } else {
                lastReason = "${File(path).name} needs MediaPipe, which this build does not have"
            }
        } else if (path.isNotEmpty() && path != PrismSettings.CLOUD_MODEL_SENTINEL && File(path).isFile) {
            val image = local.generate(request)
            if (image != null) return image
            lastReason = "the local checkpoint produced no frame"
        } else if (path.isNotEmpty() && path != PrismSettings.CLOUD_MODEL_SENTINEL) {
            lastReason = "${File(path).name} is not on disk"
        }

        usedFallback = true
        PrismPlatform.log.warn(
            "VideoCraft",
            "Falling back to the procedural renderer: ${lastReason ?: "no image model is active"}",
        )
        return VideoCraftRenderer.render(
            request.prompt, request.width, request.height, request.palette,
            request.seed, request.init, request.denoise,
        )
    }

    /**
     * Stops the frame in flight.
     *
     * The local route goes all the way into the sampler. The cloud route cannot be interrupted inside a
     * blocking HTTP read, so a cancel during a cloud keyframe takes effect when that request returns --
     * bounded by its own timeout -- and the engine throws before using whatever came back. The
     * procedural renderer finishes the frame it is drawing, which takes milliseconds.
     */
    override fun cancel() {
        cancelRequested = true
        local.cancel()
    }

    /** One line naming what actually drew the frames, for the project record and the page. */
    override fun label(): String = when {
        usedCloud -> "cloud image endpoint"
        local.let { it.label() } != "Prism Renderer" -> local.label()
        usedFallback -> lastReason?.let { "Prism Renderer ($it)" } ?: "Prism Renderer (procedural)"
        else -> local.label()
    }
}
