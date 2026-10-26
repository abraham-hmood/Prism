package com.prism.launcher.messaging

import android.content.Context
import android.net.Uri
import com.prism.launcher.PrismLogger
import java.io.File
import java.util.UUID

/**
 * Looking at an image on Android.
 *
 * ## What this used to be
 *
 * Two hardcoded strings — `"[Local Vision Processing... User posted an attachment]"` and
 * `"[Identifying objects via Cloud Vision...]"` — returned as if they were model output, and fed to
 * the social bots as "what they see". The comment said the real logic would go here later; in the
 * meantime anything that asked what an image contained got a confident non-answer.
 *
 * ## What it is now
 *
 * A thin Android-shaped door onto the same [Vision] registry the desktop uses. The engines are
 * already registered in `PrismApp` ([CloudVisionEngine] and [NoraVisionEngine]), both real: one asks
 * a vision-capable model over HTTP, the other reads the image through a trained connectome and ranks
 * learned words. This copies the picked image into the cache — the engines want a [File], and a
 * content:// Uri is not one — asks for a description, and reports the engine's own failure text when
 * there is nothing to say.
 *
 * The distinction that matters for every caller: a string that starts with "Could not look" is a
 * failure and must not be posted as the bot's observation.
 */
object VisionService {

    private const val TAG = "PrismVision"

    /** True when [description] is an explanation of a failure rather than something seen. */
    fun isFailure(description: String): Boolean =
        description.startsWith(FAILURE_PREFIX, ignoreCase = true)

    fun analyzeImage(context: Context, uri: Uri): String {
        val file = copyToCache(context, uri)
            ?: return "$FAILURE_PREFIX: the image could not be read."

        return try {
            val result = Vision.describe(file)
            result.sight?.text?.takeIf { it.isNotBlank() }
                ?: "$FAILURE_PREFIX: " +
                (result.error ?: "no vision engine is available on this device")
        } catch (e: Exception) {
            PrismLogger.logWarning(TAG, "Could not analyse an image: " + e.message)
            "$FAILURE_PREFIX: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            runCatching { file.delete() }
        }
    }

    /**
     * Copies the picked image somewhere the engines can open it.
     *
     * Under cache, because this is a copy that exists only for the length of one question. The
     * extension is preserved: [Vision.isSupported] refuses anything it does not recognise, and
     * decoding is chosen by the platform from the name.
     */
    private fun copyToCache(context: Context, uri: Uri): File? = runCatching {
        val extension = (context.contentResolver.getType(uri) ?: "image/jpeg")
            .substringAfterLast('/')
            .substringBefore(';')
            .ifBlank { "jpg" }
        val target = File(context.cacheDir, "vision-${UUID.randomUUID()}.$extension")
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output, 1 shl 16) }
        } ?: return null
        if (target.length() <= 0L) {
            target.delete()
            null
        } else {
            target
        }
    }.getOrElse {
        PrismLogger.logWarning(TAG, "Could not copy an image for analysis: " + it.message)
        null
    }

    private const val FAILURE_PREFIX = "Could not look at that image"
}
