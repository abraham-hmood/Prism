package com.prism.desktop.videocraft

import com.prism.core.PrismImage
import com.prism.core.PrismPlatform
import com.prism.launcher.PrismSettings
import com.prism.launcher.videocraft.KokoroVideoCraftSpeech
import com.prism.launcher.videocraft.OpticalFlowInterpolator
import com.prism.launcher.videocraft.SpeechClip
import com.prism.launcher.videocraft.VideoCraftCloudInterpolator
import com.prism.launcher.videocraft.VideoCraftInterpolator
import com.prism.launcher.videocraft.VideoCraftSpeech
import java.io.File

// THE IMAGE ENGINE IS NO LONGER HERE. It used to ask ImageGeneration for "nora" whenever a local
// checkpoint was selected, which is why a desktop render made with SD-Turbo was actually made of
// Nora's predictive-coding imagery. The shared VideoCraftImageRouter in :core now runs the selected
// checkpoint through stable-diffusion.cpp, and the two platforms cannot diverge again.

/** Kokoro dialogue, with the quiet formant fallback when an imported voice cannot be opened. */
class DesktopVideoCraftSpeech : VideoCraftSpeech {

    override fun say(text: String, speaker: String, voiceId: String?): SpeechClip? {
        val clip = KokoroVideoCraftSpeech.say(text, speaker, voiceId)
        if (clip != null) return clip
        if (PrismSettings.hasSpeechModel()) {
            return com.prism.launcher.videocraft.FallbackVoice.say(text)
        }
        return null
    }
}

// THE CHOICE ITSELF IS IN :core NOW. Both platforms used to build this list separately, and the cloud
// branch of it was a label that admitted the frames were made by Prism Flow -- two copies of that
// admission, and neither could be turned into a real route without editing both. VideoCraftCloudInterpolator
// picks the model and, when a cloud profile is active, actually calls it.
object DesktopVideoCraftInterpolators {

    fun create(): VideoCraftInterpolator = VideoCraftCloudInterpolator.create()
}

object DesktopVideoCraftPlatform {
    fun logEngine(label: String) {
        PrismPlatform.log.info("VideoCraft", "Image engine: $label")
    }
}
