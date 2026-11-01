package com.prism.launcher.messaging

import android.content.Context
import com.prism.core.PrismImage
import com.prism.launcher.platform.AndroidImageCodec

/**
 * Where ImageCraft's finished picture goes on Android: the gallery, indexed by MediaStore.
 *
 * ## Why not a file in the app's own directory
 *
 * Because the point of a picture is that the user can find it. A file under
 * `/data/data/com.prism.launcher/files/` is invisible to every gallery and to the Photos page, which
 * reads MediaStore -- so the feature would look like it produced nothing. The MesiaStore path is the
 * same one the chat's own image generation uses ([ImageGenManager.saveToPublicStore]), including its
 * pre-API-29 branch, which is why this delegates rather than reimplementing it: two copies of that
 * branch is how one of them ends up wrong on Android 9.
 */
object AndroidImageCraftSink {

    /**
     * Saves [image] and returns where it landed, phrased for the notification and the page.
     *
     * Empty string when it could not be saved, which the caller reports as a failure rather than
     * pretending the image exists somewhere.
     */
    fun save(context: Context, image: PrismImage): String {
        val bitmap = runCatching { AndroidImageCodec.toBitmap(image) }.getOrNull() ?: return ""
        val uri = runCatching {
            ImageGenManager.saveToPublicStore(context.applicationContext, bitmap)
        }.getOrNull() ?: return ""
        return "DCIM/Prism/Nebula (${uri.lastPathSegment ?: "gallery"})"
    }
}
