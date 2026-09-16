package com.prism.launcher.agentic

import android.content.Context
import com.prism.launcher.messaging.ImageGenManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Real Android implementation behind [AgenticBuiltinTools.generateImageHandler] -- wired up in
 * `PrismApp.onCreateMainProcess`, the same pattern [AgenticMessagingTools] and [AgenticP2pHost]
 * already use for the builtin tools `:core` cannot reach directly.
 *
 * Everything about WHICH generator runs (cloud endpoint vs. on-device MediaPipe diffusion) and
 * where the result is stored already lives in [ImageGenManager]; this is only the bridge that
 * supplies the `Context` those need. That is deliberate -- it means the tool automatically follows
 * whatever image generator the user has loaded, with no second notion of "the active model" here
 * that could disagree with the rest of the app.
 *
 * The "is a generator loaded at all" gate is [com.prism.launcher.PrismSettings.hasImageGenerator],
 * checked by [AgenticBuiltinTools] before this ever runs; nothing here re-checks it.
 */
object AgenticImageTools {

    /** Returns the saved image's content URI, or null if generation failed -- callers distinguish
     * those two cases, so a failure must never come back as an empty-but-successful string. */
    suspend fun generateImage(context: Context, prompt: String): String? = withContext(Dispatchers.IO) {
        try {
            ImageGenManager.generateImage(context, prompt)?.toString()
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
