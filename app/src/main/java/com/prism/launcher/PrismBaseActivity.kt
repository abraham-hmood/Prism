package com.prism.launcher

import android.content.Context
import android.os.Bundle
import android.util.AttributeSet
import android.view.View
import androidx.appcompat.app.AppCompatActivity

/**
 * Base Activity for all Prism screens.
 * Automatically injects custom fonts into every TextView and Button on creation.
 */
abstract class PrismBaseActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        val mode = PrismSettings.getThemeMode()
        val desiredNightMode = when (mode) {
            PrismSettings.THEME_LIGHT -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
            PrismSettings.THEME_DARK -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
            else -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        // ONLY WHEN IT DIFFERS. setDefaultNightMode recreates every started activity, so calling it
        // unconditionally from the base onCreate meant each screen kicked off another recreation as
        // it opened -- harmless when the value already matched, and a recreation loop that ate taps
        // while the theme was mid-change.
        if (androidx.appcompat.app.AppCompatDelegate.getDefaultNightMode() != desiredNightMode) {
            androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(desiredNightMode)
        }
        
        super.onCreate(savedInstanceState)
        
        // The status bar follows what is ACTUALLY on screen rather than the stored preference.
        // Under "follow system" the preference says neither light nor dark, so reading it gave
        // those devices a black status bar sitting over a light screen.
        val nightNow = (resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        if (nightNow) {
            window.statusBarColor = android.graphics.Color.BLACK
            window.decorView.systemUiVisibility = 0
        } else {
            window.statusBarColor = android.graphics.Color.WHITE
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
    }

    protected fun resolveAttr(attr: Int): Int {
        val typedValue = android.util.TypedValue()
        theme.resolveAttribute(attr, typedValue, true)
        return typedValue.data
    }

    override fun onCreateView(name: String, context: Context, attrs: AttributeSet): View? {
        val view = super.onCreateView(name, context, attrs)
        if (view != null) {
            PrismFontEngine.applyToView(view)
        }
        return view
    }

    override fun onCreateView(parent: View?, name: String, context: Context, attrs: AttributeSet): View? {
        val view = super.onCreateView(parent, name, context, attrs)
        if (view != null) {
            PrismFontEngine.applyToView(view)
        }
        return view
    }
}
