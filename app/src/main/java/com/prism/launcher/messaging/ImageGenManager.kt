package com.prism.launcher.messaging

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.prism.launcher.PrismSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * High-level manager for image generation requests.
 * Handles storage of the resulting AI images and engine selection.
 */
object ImageGenManager {

    suspend fun generateImage(context: Context, prompt: String): Uri? = withContext(Dispatchers.IO) {
        val mode = PrismSettings.getAiMode()
        
        return@withContext if (mode == PrismSettings.AI_MODE_CLOUD) {
            val cloudModel = PrismSettings.getActiveCloudModel() ?: return@withContext null

            // CloudAiService returns the portable PrismImage now that it lives in :core.
            // Converting here is the right place: this is the boundary where the image stops
            // being data and becomes something Android has to store.
            val image = CloudAiService.fetchImage(cloudModel.baseUrl, cloudModel.apiKey, prompt)
            image?.let {
                saveToPublicStore(context, com.prism.launcher.platform.AndroidImageCodec.toBitmap(it))
            }
        } else {
            val modelPath = PrismSettings.getLocalImageModelPath()
            if (modelPath.isBlank()) return@withContext null
            
            val bitmap = LocalImageService.generateImage(context, modelPath, prompt)
            bitmap?.let { saveToPublicStore(context, it) }
        }
    }

    private fun saveToPublicStore(context: Context, bitmap: Bitmap): Uri? {
        val fileName = "gen_${UUID.randomUUID()}.jpg"
        val relativePath = "DCIM/Prism/Nebula"
        
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
        }
        
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        return try {
            uri?.let {
                context.contentResolver.openOutputStream(it).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out!!)
                }
            }
            uri
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Extracts a descriptive image prompt from an LLM response if it contains a [VISUAL] tag.
     * Use this to bridge LLM text generation with Diffusion image generation.
     */
    fun extractVisualPrompt(text: String): String? {
        val tag = "[VISUAL]"
        if (text.contains(tag)) {
            return text.substringAfter(tag).trim()
        }
        return null
    }

    /**
     * Recognizes "draw/generate me a picture of X" style requests in a user's own message and
     * returns just the X, or null if this isn't an image request.
     *
     * Returning the SUBJECT rather than the whole message is the point: the previous behaviour fed
     * the entire raw text to the generator, so "generate image of a red fox" asked a diffusion
     * model to render the words "generate image of" as part of the picture. The leading phrase is
     * an instruction to Prism, not part of what the user wants to see.
     *
     * Only used on the non-agentic path. With agentic tools enabled the model calls
     * `generate_image` and writes a proper prompt itself, which is strictly better than any
     * keyword match -- this exists so that Sam can still make pictures without tool-calling turned
     * on, not as the preferred route.
     */
    fun parseImageRequest(text: String): String? {
        val triggers = listOf(
            "generate an image of", "generate an image", "generate image of", "generate image",
            "make me a picture of", "make a picture of", "make a picture",
            "draw me a picture of", "draw me a picture", "draw a picture of", "draw a picture",
            "draw me an image of", "create an image of", "create an image"
        )
        val lower = text.lowercase()
        val hit = triggers.firstOrNull { lower.contains(it) } ?: return null

        val index = lower.indexOf(hit)
        val subject = text.substring(index + hit.length)
            .trimStart(' ', ':', ',', '-', '—')
            .removePrefix("of ")
            .trim()
        // "generate an image" with nothing after it is still an image request; fall back to
        // whatever else the user said rather than handing the generator an empty prompt.
        return subject.ifBlank { text.replace(Regex("(?i)" + Regex.escape(hit)), "").trim() }
            .ifBlank { null }
    }
}
