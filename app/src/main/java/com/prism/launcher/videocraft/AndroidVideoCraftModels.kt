package com.prism.launcher.videocraft

import android.graphics.Bitmap
import android.content.Context
import com.prism.core.PrismImage
import com.prism.launcher.PrismSettings
import com.prism.launcher.messaging.LocalImageService
import com.prism.launcher.platform.AndroidImageCodec
import com.prism.launcher.speech.KokoroInstall
import com.prism.launcher.videocraft.VideoCraftLocalImages
import com.prism.launcher.videocraft.VideoCraftOnnxInterpolator
import com.prism.launcher.videocraft.VideoCraftImageOps
import java.io.File

// THE IMAGE ENGINE IS NO LONGER HERE. It lives in :core as VideoCraftImageRouter, because this file
// and its desktop twin had already drifted: Android asked MediaPipe, desktop asked Nora's mental
// imagery, and neither was the Stable Diffusion checkpoint the user had selected. One shared
// router means the platform cannot decide that question differently.

/**
 * Speech for VideoCraft.
 *
 * Kokoro first because it is the only speech engine that can hand back samples on both platforms in
 * this build. An imported model satisfies the page's gate; when it cannot be opened, Kokoro is used
 * rather than silence, matching what PrismSpeaker does for a call. With neither, a quiet formant
 * voice keeps the soundtrack's timing intact.
 */
class AndroidVideoCraftSpeech : VideoCraftSpeech {
    override fun say(text: String, speaker: String, voiceId: String?): SpeechClip? {
        val clip = KokoroVideoCraftSpeech.say(text, speaker, voiceId)
        if (clip != null) return clip
        if (PrismSettings.hasSpeechModel()) return FallbackVoice.say(text)
        return null
    }

    fun describe(): String = when {
        PrismSettings.getLocalAudioModelPath().isNotEmpty() ->
            File(PrismSettings.getLocalAudioModelPath()).name
        KokoroInstall.isModelInstalled() -> "Kokoro-82M"
        else -> "no speech engine"
    }
}

// SHARED WITH DESKTOP, in :core. The cloud branch here used to read "Prism Flow (cloud model
// unavailable on-device)" -- which was true, and is now a real call to the configured cloud
// interpolation profile. VideoCraftCloudInterpolator holds the one copy of that decision.
object AndroidVideoCraftInterpolators {
    fun create(): VideoCraftInterpolator = VideoCraftCloudInterpolator.create()
}

fun Bitmap.toPrismImage(): PrismImage = AndroidImageCodec.fromBitmap(this)

/**
 * Teaches the shared VideoCraft router how to run a MediaPipe `.task` bundle.
 *
 * MEDIAPIPE STILL HAS A JOB HERE. stable-diffusion.cpp loads `.safetensors` and `.ckpt`
 * checkpoints, not `.task` bundles, and plenty of Prism users already have a bundle from the
 * Models page's image-model downloads. Without this hook a `.task` selection would fall through to
 * the procedural renderer -- which is what happened on the first attempt at this change -- and the
 * user would see their film drawn by the fallback while the label blamed the checkpoint.
 *
 * MediaPipe cannot do img2img, so the keyframe chain's low-denoise step is applied afterwards by
 * [VideoCraftImageOps.img2img], which is how this platform has always made frames continuous.
 */
object AndroidVideoCraftMediaPipe {

    fun install(context: Context) {
        val app = context.applicationContext
        VideoCraftLocalImages.mediaPipe = { request ->
            val path = PrismSettings.getLocalImageModelPath()
            if (path.isEmpty() || !File(path).isFile) {
                null
            } else {
                val bitmap = runCatching {
                    kotlinx.coroutines.runBlocking {
                        LocalImageService.generateImage(app, path, request.prompt)
                    }
                }.getOrNull()
                val image = bitmap?.let(AndroidImageCodec::fromBitmap)
                if (image == null) {
                    null
                } else {
                    val fitted = VideoCraftImageOps.fit(image, request.width, request.height)
                    VideoCraftImageOps.img2img(fitted, request.init, request.denoise, request.seed)
                }
            }
        }
    }
}
