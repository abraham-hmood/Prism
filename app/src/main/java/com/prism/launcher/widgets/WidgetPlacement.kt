package com.prism.launcher.widgets

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Intent
import android.widget.Toast
import com.prism.launcher.DesktopItem
import com.prism.launcher.DesktopShortcutStore
import com.prism.launcher.PrismLogger

/**
 * The three-step dance that turns "the user dropped a widget here" into a widget on the screen.
 *
 * ## Why it is a state machine and not a function
 *
 * Placing a widget can need the user twice, and each time the answer arrives in `onActivityResult`
 * rather than as a return value:
 *
 *  1. ALLOCATE an id. Free, always works.
 *  2. BIND the provider to it. Immediate while Prism is the default launcher, and otherwise a system
 *     dialog the user can refuse.
 *  3. CONFIGURE, if the widget has a configuration activity. Also refusable.
 *
 * Between those steps the id exists but the widget does not, and the cell is spoken for but empty.
 * So the pending placement is held here -- which cell, which page, which id -- and picked up again
 * when the result comes back.
 *
 * EVERY ABANDONED PATH RELEASES THE ID. A user who cancels the bind dialog or backs out of a
 * configuration screen leaves an allocated id behind otherwise, and the system keeps those forever.
 */
object WidgetPlacement {

    const val REQUEST_BIND = 0x5701
    const val REQUEST_CONFIGURE = 0x5702

    private const val TAG = "PrismWidgets"

    private data class Pending(
        val appWidgetId: Int,
        val pageIndex: Int,
        val cellIndex: Int,
        val spanX: Int,
        val spanY: Int,
    )

    @Volatile
    private var pending: Pending? = null

    /** What the drawer puts in a drag, and what the desktop reads back out of one. */
    const val DRAG_LABEL = "prism_widget"

    fun providerFor(activity: Activity, flattened: String): AppWidgetProviderInfo? {
        val component = ComponentName.unflattenFromString(flattened) ?: return null
        return PrismWidgetHost.installedProviders(activity).firstOrNull { it.provider == component }
    }

    /**
     * Begins placing [provider] at [cellIndex] on [pageIndex].
     *
     * Returns false when it could not even start -- the cell is taken, or the widget does not fit
     * where it was dropped. The caller says so; nothing has been allocated at that point.
     */
    fun begin(
        activity: Activity,
        provider: AppWidgetProviderInfo,
        pageIndex: Int,
        cellIndex: Int,
        cellWidthDp: Int,
        cellHeightDp: Int,
        cellCount: Int,
        onPlaced: () -> Unit,
    ): Boolean {
        val columns = DesktopShortcutStore.COLUMNS
        val rows = cellCount / columns
        val (spanX, spanY) = PrismWidgetHost.defaultSpans(provider, cellWidthDp, cellHeightDp, columns, rows)

        val store = DesktopShortcutStore(pageIndex)
        val grid = store.readGrid(cellCount)
        val index = firstFitting(grid, cellIndex, spanX, spanY)
        if (index < 0) {
            Toast.makeText(activity, "No room for a ${spanX}x$spanY widget on this page", Toast.LENGTH_SHORT).show()
            return false
        }

        val appWidgetId = PrismWidgetHost.allocateId(activity)
        pending = Pending(appWidgetId, pageIndex, index, spanX, spanY)

        if (PrismWidgetHost.bindAllowed(activity, appWidgetId, provider)) {
            continueAfterBind(activity, onPlaced)
        } else {
            // Not the default launcher, so the system asks the user. The answer arrives at
            // REQUEST_BIND; see the class comment for why that makes this a state machine.
            runCatching {
                activity.startActivityForResult(
                    PrismWidgetHost.bindIntent(appWidgetId, provider), REQUEST_BIND
                )
            }.onFailure {
                PrismLogger.logWarning(TAG, "bind request failed: ${it.message}")
                abandon(activity)
                Toast.makeText(activity, "This device would not allow the widget to be added", Toast.LENGTH_SHORT).show()
            }
        }
        return true
    }

    /**
     * Where a widget of this size can actually go, starting from where it was dropped.
     *
     * The drop cell first, because that is what the user pointed at. Then any cell that fits, so a
     * widget dropped on a corner where it cannot fit still lands somewhere rather than being
     * refused -- being moved is a smaller surprise than nothing happening.
     */
    private fun firstFitting(grid: List<DesktopItem?>, preferred: Int, spanX: Int, spanY: Int): Int {
        if (DesktopShortcutStore.fits(grid, preferred, spanX, spanY)) return preferred
        for (index in grid.indices) {
            if (DesktopShortcutStore.fits(grid, index, spanX, spanY)) return index
        }
        return -1
    }

    /** Called from the activity's onActivityResult. Returns true when the result was ours. */
    fun onActivityResult(activity: Activity, requestCode: Int, resultCode: Int, data: Intent?, onPlaced: () -> Unit): Boolean {
        when (requestCode) {
            REQUEST_BIND -> {
                if (resultCode == Activity.RESULT_OK) continueAfterBind(activity, onPlaced) else abandon(activity)
                return true
            }
            REQUEST_CONFIGURE -> {
                if (resultCode == Activity.RESULT_OK) commit(onPlaced) else abandon(activity)
                return true
            }
        }
        return false
    }

    private fun continueAfterBind(activity: Activity, onPlaced: () -> Unit) {
        val current = pending ?: return
        val info = PrismWidgetHost.providerInfo(activity, current.appWidgetId)

        if (PrismWidgetHost.needsConfiguration(info)) {
            val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE).apply {
                component = info?.configure
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, current.appWidgetId)
            }
            runCatching { activity.startActivityForResult(intent, REQUEST_CONFIGURE) }
                .onFailure {
                    // A configuration activity that will not start is not a reason to lose the
                    // widget: most work fine with their defaults.
                    PrismLogger.logWarning(TAG, "configure activity failed: ${it.message}")
                    commit(onPlaced)
                }
        } else {
            commit(onPlaced)
        }
    }

    private fun commit(onPlaced: () -> Unit) {
        val current = pending ?: return
        pending = null

        val store = DesktopShortcutStore(current.pageIndex)
        val grid = store.readGrid(DesktopShortcutStore.GRID_SIZE).toMutableList()
        // Checked again: the user may have filled the cell while a configuration screen was open.
        if (!DesktopShortcutStore.fits(grid, current.cellIndex, current.spanX, current.spanY)) {
            val index = firstFitting(grid, current.cellIndex, current.spanX, current.spanY)
            if (index < 0) return
            DesktopShortcutStore.placeWidget(
                grid, index, DesktopItem.Widget(current.appWidgetId, current.spanX, current.spanY)
            )
        } else {
            DesktopShortcutStore.placeWidget(
                grid, current.cellIndex,
                DesktopItem.Widget(current.appWidgetId, current.spanX, current.spanY),
            )
        }
        store.writeGrid(grid)
        onPlaced()
    }

    private fun abandon(activity: Activity) {
        val current = pending ?: return
        pending = null
        PrismWidgetHost.releaseId(activity, current.appWidgetId)
    }
}
