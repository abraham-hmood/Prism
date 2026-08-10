package com.prism.core

import java.io.File

/**
 * The desktop background image.
 *
 * WHY THIS READS THE OS WALLPAPER RATHER THAN KEEPING ITS OWN. On Android, Prism's desktop page
 * shows the user's wallpaper because a launcher window is transparent and the system wallpaper is
 * literally behind it -- Prism never loads the image at all. There is no equivalent trick on
 * Windows or Linux: an ordinary application window has an opaque background and nothing shows
 * through it. So to be the same feature rather than a different one, Prism has to find the file
 * the OS is using and draw it itself.
 *
 * Both platforms store it somewhere readable, which is what makes this a port rather than a
 * substitute:
 *
 *   WINDOWS  HKCU\Control Panel\Desktop\WallPaper holds the path the user selected. Windows also
 *            keeps a re-encoded copy at %APPDATA%\Microsoft\Windows\Themes\TranscodedWallpaper,
 *            which is the fallback when the registry path points at something since deleted --
 *            common, because the registry keeps the original path after the file moves.
 *
 *   LINUX    gsettings on GNOME/Cinnamon/MATE, each under its own schema. KDE stores it inside a
 *            JavaScript config blob that is genuinely not worth parsing, so KDE falls through to
 *            the user override.
 *
 * A user override always wins, so anyone whose desktop environment is not covered -- or who wants
 * a different image in Prism than on their desktop -- sets one and is done.
 */
interface Wallpaper {
    /** The image to draw, or null if none could be determined. */
    fun current(): File?
}

/** Reports nothing. Prism draws its own background instead, which is a fine outcome. */
object NoWallpaper : Wallpaper {
    override fun current(): File? = null
}

/**
 * Reads the wallpaper the operating system is currently using.
 *
 * Everything is wrapped in a catch: this shells out to `reg` and `gsettings`, and a machine
 * without them, or a locked-down one that refuses, should fall back to no wallpaper rather than
 * take down the desktop page.
 */
class SystemWallpaper : Wallpaper {

    override fun current(): File? {
        // An explicit choice always beats the detected one.
        val override = PrismSettings().wallpaperOverride()
        if (override != null && override.isFile) return override

        return try {
            val os = System.getProperty("os.name").orEmpty().lowercase()
            when {
                os.contains("win") -> windows()
                else -> linux()
            }
        } catch (e: Exception) {
            PrismPlatform.log.debug("Prism/wallpaper", "Could not detect: ${e.message}")
            null
        }
    }

    private fun windows(): File? {
        val path = runCommand(
            listOf("reg", "query", "HKCU\\Control Panel\\Desktop", "/v", "WallPaper")
        )?.lineSequence()
            ?.firstOrNull { it.contains("WallPaper", ignoreCase = true) }
            ?.substringAfter("REG_SZ")
            ?.trim()

        if (!path.isNullOrBlank()) {
            val file = File(path)
            if (file.isFile) return file
        }

        // The registry keeps the ORIGINAL path, which goes stale the moment the image is moved or
        // deleted, or when the wallpaper came from a theme or a slideshow. Windows always keeps a
        // working copy here, so this is the reliable answer rather than the fallback it looks like.
        val transcoded = File(
            System.getenv("APPDATA").orEmpty(),
            "Microsoft\\Windows\\Themes\\TranscodedWallpaper"
        )
        return transcoded.takeIf { it.isFile }
    }

    private fun linux(): File? {
        val schemas = listOf(
            "org.gnome.desktop.background" to "picture-uri",
            "org.gnome.desktop.background" to "picture-uri-dark",
            "org.cinnamon.desktop.background" to "picture-uri",
            "org.mate.background" to "picture-filename",
        )
        for ((schema, key) in schemas) {
            val raw = runCommand(listOf("gsettings", "get", schema, key))
                ?.trim()
                ?.trim('\'', '"')
                ?: continue
            if (raw.isBlank() || raw == "none") continue
            // gsettings returns a file:// URI on GNOME and a bare path on MATE.
            val path = if (raw.startsWith("file://")) {
                java.net.URLDecoder.decode(raw.removePrefix("file://"), "UTF-8")
            } else raw
            val file = File(path)
            if (file.isFile) return file
        }
        return null
    }

    private fun runCommand(command: List<String>): String? = try {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        // Bounded: a hung `gsettings` must not hang the desktop page's first frame.
        if (!process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly()
            null
        } else if (process.exitValue() == 0) output else null
    } catch (e: Exception) {
        null
    }

    /** Reads the override without :core depending on the settings object's package. */
    private class PrismSettings {
        fun wallpaperOverride(): File? =
            PrismPlatform.host.prefs("prism_settings")
                .getString("wallpaper_path", null)
                ?.takeIf { it.isNotBlank() }
                ?.let { File(it) }
    }
}
