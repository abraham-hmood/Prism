package com.prism.launcher

import com.prism.core.PrismPlatform
import java.io.File
import java.net.URL

/**
 * Resolves the user's chosen typeface to a font FILE, on any platform.
 *
 * CORRECTING AN EARLIER CLAIM IN THIS PORT. I recorded that the Nasalization option could not be
 * ported because the face was "an Android asset not on the desktop classpath". Reading
 * `PrismFontEngine` properly shows that is not what it does at all -- there is no bundled asset.
 * It DOWNLOADS the font, once, from a public URL into the app's private storage, and loads it
 * from there. That mechanism is entirely portable, so this phase is not blocked on packaging and
 * never was. The lesson is the same one MeshUtils taught: check what the Android code actually
 * does before declaring the dependency.
 *
 * This resolves to a [File] rather than a platform font object, because Android wants a
 * `Typeface` and Compose Desktop wants a `java.io.File`, and the only thing they agree on is
 * where the bytes are. Each platform builds its own font object from [resolve].
 */
object FontEngine {

    private const val TAG = "Prism/font"

    /** The same URL the Android build downloads from, so both platforms get the same face. */
    private const val NASALIZATION_URL =
        "https://github.com/tyrel/nasalization-font/raw/master/nasalization-rg.otf"

    private const val NASALIZATION_FILE = "nasalization.otf"

    @Volatile
    private var downloading = false

    /**
     * The font file to use, or null for the platform default.
     *
     * Returns null rather than blocking when Nasalization has been chosen but not yet
     * downloaded; the download runs in the background and the next call picks it up. Android
     * behaves identically -- a first-run font choice takes effect on the following screen rather
     * than stalling the current one.
     */
    fun resolve(): File? = when (PrismSettings.getFontStyle()) {
        PrismSettings.FONT_STYLE_NASALIZATION -> nasalization()
        PrismSettings.FONT_STYLE_CUSTOM -> {
            val path = PrismSettings.getCustomFontPath()
            File(path).takeIf { path.isNotBlank() && it.isFile }
        }
        else -> null
    }

    /** Where the downloaded face lives. */
    fun nasalizationFile(): File = File(PrismPlatform.host.dataDir(), NASALIZATION_FILE)

    private fun nasalization(): File? {
        val file = nasalizationFile()
        if (file.isFile && file.length() > 0) return file
        download(file)
        return null
    }

    /**
     * Fetches the face in the background, once.
     *
     * Downloads to a temporary file and renames on success. Writing straight to the destination
     * would leave a truncated font there if the connection dropped, and a truncated font does not
     * fail to load -- it loads and renders garbage, which is far harder to diagnose than a
     * missing file.
     */
    private fun download(target: File) {
        if (downloading) return
        synchronized(this) {
            if (downloading) return
            downloading = true
        }

        Thread({
            try {
                target.parentFile?.mkdirs()
                val temp = File(target.parentFile, "${target.name}.part")
                URL(NASALIZATION_URL).openConnection().apply {
                    connectTimeout = 12_000
                    readTimeout = 30_000
                }.getInputStream().use { input ->
                    temp.outputStream().use { output -> input.copyTo(output) }
                }
                if (temp.length() > 0 && temp.renameTo(target)) {
                    PrismPlatform.log.info(TAG, "Nasalization downloaded")
                } else {
                    temp.delete()
                    PrismPlatform.log.warn(TAG, "Nasalization download was empty")
                }
            } catch (e: Exception) {
                PrismPlatform.log.warn(TAG, "Nasalization download failed: ${e.message}")
            } finally {
                downloading = false
            }
        }, "prism-font-download").apply { isDaemon = true }.start()
    }

    /**
     * CSS that applies the chosen font to web content, as `getWebViewCss` does on Android.
     *
     * Empty when the default font is selected, so callers can skip injection entirely rather
     * than injecting a stylesheet that says nothing.
     */
    fun webCss(): String {
        val file = resolve() ?: return ""
        val uri = file.toURI().toString()
        return """
            @font-face { font-family: 'PrismFont'; src: url('$uri'); }
            * { font-family: 'PrismFont' !important; }
        """.trimIndent()
    }
}
