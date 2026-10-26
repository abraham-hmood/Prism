package com.prism.launcher

import android.content.Context
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity

/**
 * Base Activity for all Prism screens.
 * Automatically injects custom fonts into every TextView and Button on creation.
 */
abstract class PrismBaseActivity : AppCompatActivity() {

    /**
     * The appearance choices in force when this screen was last built.
     *
     * Used to notice a change made on Settings while this screen sat behind it. Views capture
     * their colours and typeface when they are constructed, so the accent applied only to pages
     * opened after the change -- an already-built desktop kept the old one until the app was
     * killed. Recreating once on resume is the smallest fix that actually reaches those views.
     */
    private var appearanceKey: String? = null

    /**
     * Whether this screen was built for a dark appearance.
     *
     * Separate from [appearanceKey] because following the SYSTEM has no stored setting to
     * change: light-to-dark at sunset changes `uiMode` and nothing in [PrismSettings]. The
     * manifest lists `uiMode` as a configuration this app handles, so Android does not recreate
     * the screen by itself -- that is the bug where a mode switch left the labels the old colour
     * until the user pressed Back enough times for every screen to be resumed again.
     */
    private var nightMode: Boolean = false

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
        appearanceKey = currentAppearanceKey()
        nightMode = isNight(resources.configuration)
        
        // The status bar follows what is ACTUALLY on screen rather than the stored preference.
        // The launcher keeps its transparent bar so the wallpaper shows through; every other
        // screen takes the selected theme's background, which is what makes a theme switch visible
        // before any content has been built.
        val launcher = javaClass.simpleName == "LauncherActivity"
        val background = com.prism.launcher.nora.IosUi.groupedBackground(this)
        if (!launcher) {
            window.setBackgroundDrawable(ColorDrawable(background))
            applyStatusBarColor(background)
        } else {
            applyStatusBarColor(android.graphics.Color.TRANSPARENT)
        }
        val lightBar = androidx.core.graphics.ColorUtils.calculateLuminance(background) > 0.55
        androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
            .isAppearanceLightStatusBars = lightBar
    }

    /**
     * Colours the status bar.
     *
     * SUPPRESSED RATHER THAN MIGRATED, deliberately. `statusBarColor` was deprecated in API 35 to push
     * applications towards edge-to-edge, and the replacement is not another setter -- it is drawing the
     * content behind the bar and insetting it by hand, on every screen. That is a change to how Prism
     * lays out, worth doing on its own terms, and not something to slip in while clearing a compiler
     * warning. The setter still works on every version this app supports.
     */
    @Suppress("DEPRECATION")
    private fun applyStatusBarColor(color: Int) {
        window.statusBarColor = color
    }

    protected fun resolveAttr(attr: Int): Int {
        com.prism.launcher.theme.ThemeSkin.resolveAttr(this, attr)?.let { return it }
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

    /**
     * Applies the chosen typeface to the whole tree once the content exists.
     *
     * The onCreateView overrides above only see views the PLATFORM inflater creates. AppCompat
     * installs its own factory and turns `<TextView>` into an AppCompatTextView itself, so those
     * overrides were never called for the screens Prism actually has -- which is why the font
     * setting appeared to do nothing. Walking the decor view is not elegant, but it is the one
     * hook that reaches every TextView regardless of which inflater made it.
     */
    override fun onContentChanged() {
        super.onContentChanged()
        applyFonts(window.decorView)
        // After the fonts, so a family chosen by the theme does not get overwritten by the
        // user's font preference on the very next frame.
        com.prism.launcher.theme.ThemeSkin.apply(this)
    }

    override fun onResume() {
        super.onResume()
        applyFonts(window.decorView)
        com.prism.launcher.theme.ThemeSkin.apply(this)

        val key = currentAppearanceKey()
        val previous = appearanceKey
        appearanceKey = key
        val dark = isNight(resources.configuration)
        val nightChanged = dark != nightMode
        nightMode = dark
        if (previous != null && (previous != key || nightChanged)) {
            // Posted, not called inline: this is inside onResume, and recreating there on some
            // versions leaves the activity half-started.
            window.decorView.post { recreate() }
        }
    }

    /**
     * Handles the appearance change Android will not recreate this screen for.
     *
     * Every Prism activity declares `uiMode` in `configChanges` (so rotating or a system font
     * change does not tear a page down). The cost is that switching light/dark mode leaves the
     * CURRENT screen built with the old configuration: its labels keep the old colours until it
     * is resumed again, which is why the fix used to require pressing Back through the whole
     * stack. Recreating here makes the screen the user is looking at switch at once; screens
     * behind it notice the new mode in [onResume] and recreate themselves when they are shown.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val dark = isNight(newConfig)
        if (dark == nightMode) return
        if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
            nightMode = dark
            window.decorView.post { recreate() }
        }
        // A stopped screen is left alone on purpose: it is not visible, and recreating it in the
        // background would restart whatever it was doing. [onResume] sees the changed mode then.
    }

    /**
     * Rebuilds this screen now when the appearance it is showing no longer matches the settings.
     *
     * For a screen that changes the mode ITSELF. `setDefaultNightMode` updates the configuration
     * but, with `uiMode` in `configChanges`, is not guaranteed to recreate the activity that made
     * the call -- so Settings could write the new mode and keep drawing the old one until it was
     * left and reopened. Calling this after the write makes the screen switch on the same tap.
     */
    protected fun refreshAppearanceNow() {
        if (isFinishing || isDestroyed) return
        val dark = isNight(resources.configuration)
        if (dark == nightMode) return
        nightMode = dark
        window.decorView.post { if (!isFinishing && !isDestroyed) recreate() }
    }

    private fun isNight(configuration: Configuration): Boolean =
        (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    private fun currentAppearanceKey(): String = listOf(
        PrismSettings.getThemeMode(),
        PrismSettings.getThemeId(),
        PrismSettings.getThemeRevision(),
        PrismSettings.getGlowColor(),
        PrismSettings.getFontStyle(),
        PrismSettings.getCustomFontPath(),
        PrismSettings.getIconPackPackage(),
        com.prism.launcher.theme.PrismThemes.revision,
    ).joinToString("|")

    private fun applyFonts(view: View) {
        PrismFontEngine.applyToView(view)
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) applyFonts(view.getChildAt(index))
        }
    }
}
