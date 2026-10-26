package com.prism.launcher

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Process
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import com.prism.launcher.nora.IosUi
import java.io.File
import java.util.WeakHashMap

/**
 * What a long press on a drawer icon opens.
 *
 * ## Why a menu, and still a drag
 *
 * A held icon is a question about that app -- back it up, remove it, open it beside what is already
 * on screen -- so it gets a menu. Moving the finger instead of lifting it is the older gesture: the
 * menu closes and the grid's drag starts, so reordering, folders and the delete zone keep working.
 * The drawer and every desktop page use the same menu and the same handoff ([MenuDragHandoff]).
 *
 * ## Shortcuts first
 *
 * The launcher owns the shortcut host permission by virtue of being the default home app, so the
 * shortcuts an app publishes are reachable here even though the app itself is not running. They are
 * listed above the actions because they are the app's own vocabulary: "New message" is more useful
 * than a second way to open the app.
 *
 * ## Popup apps
 *
 * A phone with freeform windows can run an app in a floating window. That is a platform feature, not
 * something every device has, so the row is only present when the device advertises it. Failure to
 * launch is reported rather than silently opening full screen, because a menu item that says
 * "popup" and then fills the screen is a lie.
 */
object AppContextMenu {

    private const val FREEFORM_FEATURE = "android.software.freeform_window_management"

    /**
     * Menus are views in the activity window rather than PopupWindows.
     *
     * A long press opens this while the finger is still down. A separate focusable window can
     * cancel that touch stream, which would make the follow-up drag -- the gesture this menu is
     * meant to hand off to -- impossible to detect. Staying in the activity window leaves the
     * original gesture alive for [MenuDragHandoff].
     */
    private val openMenus = WeakHashMap<Activity, MenuOverlay>()

    private class MenuOverlay(
        val scrim: FrameLayout,
        val card: View,
        val back: OnBackPressedCallback?,
    )

    fun suggestedFileName(entry: DrawerAppEntry): String {
        val safe = entry.label
            .replace(Regex("[^A-Za-z0-9 ._-]"), "_")
            .trim()
            .trimStart('.')
            .ifBlank { "app" }
        return "$safe.apk"
    }

    @Suppress("UNUSED_PARAMETER")
    fun show(
        activity: Activity,
        entry: DrawerAppEntry,
        anchor: View,
        onBackup: (DrawerAppEntry) -> Unit,
    ) {
        dismiss(activity)
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        val theme = IosUi.mode(activity)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(theme.shape.card).toFloat()
                setColor(IosUi.cardBackground(activity))
                setStroke(dp(maxOf(1, theme.shape.border)), IosUi.separator(activity))
            }
            isClickable = true
            elevation = dp(theme.shape.elevation).toFloat()
        }

        // Header. The icon comes from the row that was held, so the menu is visibly about that app.
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(2), dp(4), dp(10))
        }
        header.addView(ImageView(activity).apply {
            setImageDrawable(entry.icon)
            if (entry.icon == null) setImageResource(android.R.drawable.sym_def_app_icon)
        }, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(12) })
        header.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(activity).apply {
                text = entry.label
                textSize = 16f
                setTextColor(IosUi.label(activity))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            addView(TextView(activity).apply {
                text = entry.component.packageName
                textSize = 11f
                setTextColor(IosUi.tertiaryLabel(activity))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(header, LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

        val shortcuts = shortcutsFor(activity, entry)
        if (shortcuts.isNotEmpty()) {
            root.addView(sectionLabel(activity, "SHORTCUTS"))
            shortcuts.take(5).forEach { shortcut ->
                val label = shortcut.shortLabel?.toString()?.takeIf { it.isNotBlank() }
                    ?: shortcut.longLabel?.toString()?.takeIf { it.isNotBlank() }
                    ?: "Shortcut"
                val icon = runCatching {
                    (activity.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps)
                        .getShortcutIconDrawable(shortcut, activity.resources.displayMetrics.densityDpi)
                }.getOrNull()
                root.addView(row(activity, label, icon, IosUi.label(activity)) {
                    dismiss(activity)
                    runCatching {
                        (activity.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps)
                            .startShortcut(shortcut, null, null)
                    }.onFailure {
                        toast(activity, "That shortcut could not be opened")
                    }
                })
            }
            root.addView(divider(activity))
        }

        root.addView(row(activity, "Back up APK", activity.getDrawable(R.drawable.ic_download_24), IosUi.accent(activity)) {
            dismiss(activity)
            onBackup(entry)
        })

        if (supportsPopup(activity)) {
            root.addView(row(
                activity,
                "Open in popup",
                activity.getDrawable(R.drawable.ic_open_popup_24),
                IosUi.accent(activity),
            ) {
                dismiss(activity)
                if (!openInPopup(activity, entry)) {
                    toast(activity, "This device could not open ${entry.label} in a popup")
                }
            })
        }

        root.addView(row(
            activity,
            "Uninstall",
            activity.getDrawable(android.R.drawable.ic_menu_delete),
            IosUi.destructive(activity),
        ) {
            dismiss(activity)
            uninstall(activity, entry)
        })

        val scrim = FrameLayout(activity).apply {
            setBackgroundColor(theme.palette.overlay.toInt())
            isClickable = true
            setOnClickListener { dismiss(activity) }
        }
        scrim.addView(root, FrameLayout.LayoutParams(
            dp(304), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER
        ))

        val host = activity.findViewById<ViewGroup>(android.R.id.content)
            ?: (activity.window?.decorView as? ViewGroup)
            ?: return
        host.addView(scrim, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))

        val component = activity as? androidx.activity.ComponentActivity
        val back = component?.let { owner ->
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = dismiss(activity)
            }.also { owner.onBackPressedDispatcher.addCallback(it) }
        }
        openMenus[activity] = MenuOverlay(scrim, root, back)

        // A small entrance rather than a hard cut: the menu appears from under the held icon, and
        // the motion makes clear that it belongs to that icon and not to the page behind it.
        root.alpha = 0f
        root.scaleX = 0.94f
        root.scaleY = 0.94f
        root.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(140L).start()
    }

    /** Removes the open menu, if any. Safe to call when nothing is showing. */
    fun dismiss(activity: Activity) {
        val overlay = openMenus.remove(activity) ?: return
        overlay.back?.remove()
        overlay.card.animate().cancel()
        (overlay.scrim.parent as? ViewGroup)?.removeView(overlay.scrim)
    }

    fun isShowing(activity: Activity): Boolean = openMenus.containsKey(activity)

    fun backup(activity: Activity, entry: DrawerAppEntry, uri: Uri) {
        Thread({
            val result = runCatching {
                val info = activity.packageManager.getApplicationInfo(entry.component.packageName, 0)
                val source = File(info.sourceDir)
                require(source.isFile) { "The APK is not readable" }
                activity.contentResolver.openOutputStream(uri)?.use { output ->
                    source.inputStream().use { input -> input.copyTo(output) }
                } ?: error("The selected file could not be opened")
            }
            activity.runOnUiThread {
                Toast.makeText(
                    activity,
                    if (result.isSuccess) "Saved ${entry.label} to the selected file"
                    else "Could not back up ${entry.label}: ${result.exceptionOrNull()?.message}",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }, "prism-apk-backup").apply { isDaemon = true; start() }
    }

    /** Whether this device advertises freeform windows. */
    fun supportsPopup(context: Context): Boolean =
        context.packageManager.hasSystemFeature(FREEFORM_FEATURE)

    /**
     * Opens the app, or brings its existing task forward, in a freeform window.
     *
     * `getLaunchIntentForPackage` is the same entry point the drawer uses, but the launch options
     * are what ask the system for a window rather than the full screen. If the app is already
     * running, the NEW_TASK flag makes this a task re-use instead of a second copy.
     */
    private fun openInPopup(activity: Activity, entry: DrawerAppEntry): Boolean {
        if (!supportsPopup(activity)) return false
        val intent = activity.packageManager.getLaunchIntentForPackage(entry.component.packageName)
            ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)

        val metrics = activity.resources.displayMetrics
        val width = (metrics.widthPixels * 0.78f).toInt().coerceAtLeast((320 * metrics.density).toInt())
        val height = (metrics.heightPixels * 0.72f).toInt().coerceAtLeast((420 * metrics.density).toInt())
        val left = (metrics.widthPixels - width) / 2
        val top = (metrics.heightPixels - height) / 3

        return runCatching {
            // Bounds alone are the public way to ask for a freeform launch: on a device that
            // advertises the freeform feature, the system treats a bounded launch as a window.
            val options = ActivityOptions.makeBasic().apply {
                setLaunchBounds(Rect(left, top, left + width, top + height))
            }
            activity.startActivity(intent, options.toBundle())
            true
        }.getOrElse {
            // A device can advertise freeform and still refuse a particular app's window. Opening
            // full screen would contradict the row that was tapped, so this reports the failure.
            false
        }
    }

    private fun uninstall(activity: Activity, entry: DrawerAppEntry) {
        val info = runCatching {
            activity.packageManager.getApplicationInfo(entry.component.packageName, 0)
        }.getOrNull()
        val systemOnly = info != null &&
            info.flags and ApplicationInfo.FLAG_SYSTEM != 0 &&
            info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP == 0
        if (systemOnly) {
            Toast.makeText(activity, "${entry.label} is a system app", Toast.LENGTH_SHORT).show()
            return
        }
        runCatching {
            activity.startActivity(
                Intent(Intent.ACTION_DELETE, Uri.parse("package:${entry.component.packageName}"))
            )
        }.onFailure {
            Toast.makeText(activity, "Could not open the uninstaller", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shortcutsFor(activity: Activity, entry: DrawerAppEntry): List<ShortcutInfo> {
        val launcher = activity.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
            ?: return emptyList()
        val query = LauncherApps.ShortcutQuery().apply {
            setPackage(entry.component.packageName)
            setQueryFlags(
                LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                    LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or
                    LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED
            )
        }
        return runCatching {
            launcher.getShortcuts(query, Process.myUserHandle())
                .orEmpty()
                .filter { it.isEnabled }
        }.getOrDefault(emptyList())
    }

    private fun sectionLabel(context: Context, text: String): TextView =
        TextView(context).apply {
            this.text = text
            textSize = 10f
            setTextColor(IosUi.tertiaryLabel(context))
            letterSpacing = 0.08f
            setPadding(dp(context, 8), dp(context, 10), dp(context, 8), dp(context, 4))
        }

    private fun row(
        context: Context,
        text: String,
        icon: android.graphics.drawable.Drawable?,
        colour: Int,
        action: () -> Unit,
    ): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(context, 8), dp(context, 10), dp(context, 8), dp(context, 10))
        isClickable = true
        isFocusable = true
        background = selectableBackground(context)
        setOnClickListener { action() }

        addView(ImageView(context).apply {
            setImageDrawable(icon)
            imageTintList = android.content.res.ColorStateList.valueOf(colour)
            if (icon == null) setImageResource(android.R.drawable.ic_menu_more)
        }, LinearLayout.LayoutParams(dp(context, 22), dp(context, 22)).apply {
            marginEnd = dp(context, 14)
        })

        addView(TextView(context).apply {
            this.text = text
            textSize = 14.5f
            setTextColor(colour)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun divider(context: Context): View =
        View(context).apply {
            setBackgroundColor(IosUi.separator(context))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(context, 1),
            ).apply { topMargin = dp(context, 6); bottomMargin = dp(context, 2) }
        }

    private fun selectableBackground(context: Context): android.graphics.drawable.Drawable {
        val value = TypedValue()
        return if (context.theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)) {
            context.getDrawable(value.resourceId) ?: ColorDrawable(Color.TRANSPARENT)
        } else {
            ColorDrawable(Color.TRANSPARENT)
        }
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private fun toast(activity: Activity, text: String) {
        Toast.makeText(activity, text, Toast.LENGTH_LONG).show()
    }
}
