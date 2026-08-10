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
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
            when (mode) {
                PrismSettings.THEME_LIGHT -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
                PrismSettings.THEME_DARK -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
                else -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
        
        super.onCreate(savedInstanceState)
        
        // Dynamic Status Bar logic
        if (mode == PrismSettings.THEME_LIGHT) {
            window.statusBarColor = android.graphics.Color.WHITE
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        } else {
            window.statusBarColor = android.graphics.Color.BLACK
            window.decorView.systemUiVisibility = 0
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
