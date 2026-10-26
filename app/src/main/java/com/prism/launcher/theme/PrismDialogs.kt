package com.prism.launcher.theme

import android.content.Context

/**
 * The dialog builder Prism uses, so the chosen theme reaches every alert.
 *
 * ## Why a subclass rather than only a theme
 *
 * The dialog THEME already covers the shapes, the fonts, the button casing and the stock colours --
 * that is what `alertDialogTheme` does, and it reaches every builder including ones written later.
 * What it cannot reach is the USER'S theme: the accent, the surface tint and the text colours of a
 * theme the user created are chosen at runtime, and the attributes in a static style resolve to the
 * built-in values. `ThemeSkin.apply(dialog)` is what applies the runtime ones, and it needs a dialog
 * that already exists.
 *
 * That left the same gap for every hand-written `AlertDialog.Builder`: they got the shape and the
 * stock colours, not the user's. Rather than edit every call site to remember a second line -- which
 * is exactly the kind of rule that gets forgotten -- the builder itself applies the skin, so
 * `show()` is enough.
 *
 * Popups need no equivalent: they are built by the framework from `popupMenuStyle`, which Prism
 * already points at `@style/Prism.PopupMenu`, and no Prism code colours a popup's views by hand.
 */
object PrismDialogs {

    /** An `AlertDialog.Builder` that themes what it builds. */
    class Builder @JvmOverloads constructor(
        context: Context,
        themeResId: Int = com.prism.launcher.R.style.Theme_PrismSettings,
    ) : androidx.appcompat.app.AlertDialog.Builder(context, themeResId) {

        override fun create(): androidx.appcompat.app.AlertDialog {
            val dialog = super.create()
            // Applied before show as well as after: some windows accept their colours only once they
            // have a Window, and others only before they are attached. Doing both is cheap and the
            // walk is idempotent.
            runCatching { ThemeSkin.apply(dialog) }
            return dialog
        }

        override fun show(): androidx.appcompat.app.AlertDialog {
            val dialog = super.show()
            // AFTER show(), which is when the dialog has a Window for ThemeSkin to colour.
            runCatching { ThemeSkin.apply(dialog) }
            return dialog
        }
    }
}
