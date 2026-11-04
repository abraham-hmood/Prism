package com.prism.launcher.browser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.prism.launcher.DesktopItem
import com.prism.launcher.DesktopShortcutStore
import java.io.File

/**
 * Website shortcuts on the desktop, the browser's "Add as shortcut".
 *
 * The shortcut itself is a [DesktopItem.WebShortcut] in the desktop grid; this keeps its icon -- the
 * page's favicon at the moment it was saved -- in Prism's files, named after the URL so the grid
 * item does not have to carry it.
 */
object WebShortcuts {

    private fun dir(context: Context) = File(context.filesDir, "web_shortcuts").apply { mkdirs() }

    fun iconFile(context: Context, url: String): File {
        val digest = java.security.MessageDigest.getInstance("SHA-1")
            .digest(url.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(dir(context), "$digest.png")
    }

    /** The saved favicon, or null when the page had none. */
    fun icon(context: Context, url: String): Bitmap? =
        iconFile(context, url).takeIf { it.isFile }?.let { BitmapFactory.decodeFile(it.path) }

    /**
     * Saves the shortcut on a random desktop page and returns the page's slot index, or -1 when
     * no desktop page has room.
     */
    fun add(context: Context, url: String, title: String, favicon: Bitmap?): Int {
        if (favicon != null) {
            runCatching {
                iconFile(context, url).outputStream().use { favicon.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
        val name = title.trim().ifBlank { runCatching { android.net.Uri.parse(url).host }.getOrNull().orEmpty() }
        return DesktopShortcutStore.addToRandomDesktopPage(DesktopItem.WebShortcut(url, name.ifBlank { url }))
    }
}
