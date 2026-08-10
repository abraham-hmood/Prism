package com.prism.desktop

import com.prism.core.AppEntry
import com.prism.core.PrismImage
import com.prism.core.PrismPlatform
import java.awt.image.BufferedImage
import java.io.File
import javax.swing.filechooser.FileSystemView

/**
 * Icons for applications the catalog could not find an image file for.
 *
 * WINDOWS NEEDED THIS AND THE PLAN SAID IT NEEDED NATIVE CODE. It does not. Windows Start Menu
 * entries are `.lnk` shortcuts and the icon is compiled into the target `.exe` as a resource, so
 * `XdgAppCatalog`'s approach -- find an image file on disk -- has nothing to find, and every
 * Windows app fell back to a generic glyph.
 *
 * The plan proposed JNA's `ExtractIconEx` or a small native helper. Neither is necessary:
 * `javax.swing.filechooser.FileSystemView.getSystemIcon(File, width, height)` has been public
 * API since Java 9, is implemented on the Windows shell exactly as ExtractIconEx would be, and
 * costs no dependency at all. Worth recording, because "this needs native code" was stated
 * confidently in the plan and was simply wrong.
 *
 * LINUX DOES NOT NEED THIS. XDG desktop entries name an icon that the icon-theme spec resolves to
 * a real file, which the catalog already does. This is Windows-shaped by necessity, not by
 * preference, and returns null everywhere else rather than pretending otherwise.
 */
object SystemIcons {

    private val isWindows =
        System.getProperty("os.name").orEmpty().lowercase().contains("win")

    private val view: FileSystemView? by lazy {
        if (isWindows) {
            try {
                FileSystemView.getFileSystemView()
            } catch (e: Throwable) {
                PrismPlatform.log.debug("Prism/icons", "No FileSystemView: ${e.message}")
                null
            }
        } else null
    }

    /**
     * The shell icon for an app, or null if there is not one.
     *
     * [AppEntry.id] on Windows is the path to the `.lnk` or `.exe`, which is exactly what the
     * shell wants to be asked about.
     */
    fun forApp(entry: AppEntry, size: Int = 48): PrismImage? {
        if (!isWindows) return null
        val file = File(entry.id)
        if (!file.exists()) return null
        return forFile(file, size)
    }

    /** The shell icon for any file or directory. Used by the file explorer as well. */
    fun forFile(file: File, size: Int = 48): PrismImage? {
        val fsv = view ?: return null
        return try {
            // The two-argument overload returns a 16x16 icon that looks terrible scaled up to a
            // desktop cell; the sized overload asks the shell for the large variant.
            val icon = fsv.getSystemIcon(file, size, size) ?: return null
            val image = BufferedImage(
                icon.iconWidth.coerceAtLeast(1),
                icon.iconHeight.coerceAtLeast(1),
                BufferedImage.TYPE_INT_ARGB,
            )
            val g = image.createGraphics()
            try {
                icon.paintIcon(null, g, 0, 0)
            } finally {
                g.dispose()
            }
            image.toPrismImage()
        } catch (e: Throwable) {
            // Throwable rather than Exception: a locked-down or headless shell can throw an
            // Error here, and an icon is never worth taking the page down for.
            PrismPlatform.log.debug("Prism/icons", "No shell icon for ${file.name}: ${e.message}")
            null
        }
    }

    private fun BufferedImage.toPrismImage(): PrismImage {
        val pixels = IntArray(width * height)
        getRGB(0, 0, width, height, pixels, 0, width)
        return PrismImage(width, height, pixels)
    }
}
