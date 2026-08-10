package com.prism.launcher

import android.content.Context
import android.graphics.Typeface
import android.util.Log
import android.view.View
import android.widget.TextView
import android.widget.Button
import android.webkit.WebView
import java.io.File
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Modern High-Fidelity Font Engine for Prism.
 * Handles system-wide typeface injection and WebView font-bleeding.
 */
object PrismFontEngine {
    private const val TAG = "PrismFontEngine"
    private var cachedTypeface: Typeface? = null
    private var cachedStyle: String? = null
    private var cachedPath: String? = null

    // Default URL for the built-in Nasalization option
    private const val NASALIZATION_URL = "https://github.com/tyrel/nasalization-font/raw/master/nasalization-rg.otf"

    fun getTypeface(ctx: Context): Typeface? {
        val style = PrismSettings.getFontStyle()
        val path = PrismSettings.getCustomFontPath()

        if (style == cachedStyle && path == cachedPath && cachedTypeface != null) {
            return cachedTypeface
        }

        cachedStyle = style
        cachedPath = path

        cachedTypeface = when (style) {
            PrismSettings.FONT_STYLE_NASALIZATION -> loadNasalization(ctx)
            PrismSettings.FONT_STYLE_CUSTOM -> loadCustom(path)
            else -> null
        }

        return cachedTypeface
    }

    private fun loadNasalization(ctx: Context): Typeface? {
        val file = File(ctx.filesDir, "nasalization.otf")
        if (!file.exists()) {
            downloadNasalization(ctx, file)
            return null // Will load on next call/refresh
        }
        return try {
            Typeface.createFromFile(file)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load Nasalization", e)
            null
        }
    }

    private fun loadCustom(path: String): Typeface? {
        if (path.isEmpty()) return null
        return try {
            val file = File(path)
            if (file.exists()) Typeface.createFromFile(file) else null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load custom font from $path", e)
            null
        }
    }

    private fun downloadNasalization(ctx: Context, target: File) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (target.exists()) return@launch // Double check
                
                PrismLogger.logInfo(TAG, "Nasalization font not found. Downloading from mirror...")
                URL(NASALIZATION_URL).openStream().use { input ->
                    target.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                
                PrismLogger.logSuccess(TAG, "Nasalization font downloaded successfully. Applying to UI...")
                
                // Reset cache to force reload
                cachedTypeface = null 
                cachedStyle = null 
                
                // Small trick: we can't easily force all activities to refresh immediately 
                // without recreate(), but on next screen change or app restart it will be perfect.
            } catch (e: Exception) {
                PrismLogger.logError(TAG, "Failed to download Nasalization font: ${e.message}", e)
            }
        }
    }

    fun applyToView(view: View) {
        val tf = getTypeface(view.context) ?: return
        if (view is TextView) {
            view.typeface = tf
        } else if (view is Button) {
            view.typeface = tf
        }
    }

    /**
     * Generates a CSS string to be injected into WebViews to force the custom font.
     */
    fun getWebViewCss(ctx: Context): String {
        val style = PrismSettings.getFontStyle()
        if (style == PrismSettings.FONT_STYLE_DEFAULT) return ""
        
        // For WebViews, we use Base64 to ensure the font loads regardless of CSP or file permissions.
        val fontFile = when (style) {
            PrismSettings.FONT_STYLE_NASALIZATION -> File(ctx.filesDir, "nasalization.otf")
            PrismSettings.FONT_STYLE_CUSTOM -> File(PrismSettings.getCustomFontPath())
            else -> null
        }

        if (fontFile == null || !fontFile.exists()) return ""

        return try {
            val bytes = fontFile.readBytes()
            val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            val format = if (fontFile.name.endsWith(".otf")) "opentype" else "truetype"
            
            """
            @font-face {
                font-family: 'PrismCustomFont';
                src: url('data:font/$format;base64,$base64');
            }
            * {
                font-family: 'PrismCustomFont', sans-serif !important;
            }
            """.trimIndent()
        } catch (e: Exception) {
            ""
        }
    }
}
