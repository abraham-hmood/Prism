package com.prism.launcher.videocraft

import com.prism.core.PrismImage

/**
 * The platform hook for checkpoint formats only one platform can run in-process.
 *
 * ## Why a hook and not a `when (platform)`
 *
 * Prism has two local image formats now, and they need different engines:
 *
 *  - **`.task` bundles** are MediaPipe's single-file diffusion format. Only Android can load them,
 *    through `LocalImageService`, and it cannot do img2img -- so VideoCraft keeps its own low-denoise
 *    blend for the keyframe chain, exactly as before.
 *  - **`.safetensors` / `.ckpt` checkpoints** are stable-diffusion.cpp's format, on both platforms,
 *    with real img2img through the sampler.
 *
 * The router in :core must not name MediaPipe -- :core cannot see it, and a desktop build that
 * referenced the class would not link. So the platform that can installs the handler at startup,
 * and everything else falls through to the shared diffusion engine. A null handler on desktop
 * simply means a `.task` file is a format that build cannot run, and the router says so in its
 * label rather than failing silently.
 */
object VideoCraftLocalImages {

    /** Installed by Android. Renders one request through MediaPipe, or null if it cannot. */
    @Volatile
    var mediaPipe: ((VideoCraftImageRequest) -> PrismImage?)? = null

    /** True when [path] is a MediaPipe bundle rather than a stable-diffusion checkpoint. */
    fun isMediaPipeBundle(path: String): Boolean = path.endsWith(".task", ignoreCase = true)

    /** Whether this build can run [path] at all, for the creator page's hint line. */
    fun canRun(path: String): Boolean = when {
        path.isEmpty() -> false
        path == com.prism.launcher.PrismSettings.CLOUD_MODEL_SENTINEL -> true
        isMediaPipeBundle(path) -> mediaPipe != null
        else -> com.prism.launcher.diffusion.StableDiffusion.isAvailable()
    }
}
