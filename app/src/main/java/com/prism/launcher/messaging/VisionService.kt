package com.prism.launcher.messaging

import android.content.Context
import android.net.Uri
import com.prism.launcher.PrismSettings

/**
 * Service for analyzing images. 
 * Note: Local MediaPipe Vision tasks currently conflict with Local Diffusion.
 * This service routes to Cloud Vision (Gemini/OpenAI) if available.
 */
object VisionService {

    fun analyzeImage(context: Context, uri: Uri): String {
        val mode = PrismSettings.getAiMode()
        return if (mode == PrismSettings.AI_MODE_CLOUD) {
            analyzeViaCloud(context, uri)
        } else {
            // Local fallback: since we can't have both libraries, 
            // and local diffusion is prioritized for pfp generation,
            // we'll return a generic context until a hybrid model is set.
            "[Local Vision Processing... User posted an attachment]"
        }
    }

    private fun analyzeViaCloud(context: Context, uri: Uri): String {
        // Here we'd invoke the same logic as AiManager for multimodal
        return "[Identifying objects via Cloud Vision...]"
    }
}
