package com.prism.launcher.widgets

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.os.Build
import android.os.Bundle
import com.prism.launcher.PrismLogger

/**
 * The launcher's connection to every widget on its home screens.
 *
 * ## Why this is a singleton with an explicit start and stop
 *
 * An AppWidgetHost is how the system knows something is displaying widgets and where to send their
 * updates. Two things follow, and both are easy to get wrong:
 *
 * The HOST ID identifies this launcher to the system permanently. Widget ids are allocated against
 * it and stored on the home screen, so changing the id orphans every widget a user has placed --
 * they keep running, consume battery, and can never be reached again. It is a constant for that
 * reason.
 *
 * [startListening] has to be called while widgets are on screen and [stopListening] when they are
 * not. Without the first, widgets are drawn once and then freeze, which looks exactly like a widget
 * that is broken; without the second, the process keeps receiving updates for views nobody is
 * looking at. LauncherActivity does both from onStart/onStop.
 *
 * ## Binding needs the user, unless the system trusts us
 *
 * Allocating an id is free. Actually attaching a provider to it needs permission, and an app that
 * is not the default launcher does not have it -- [bindAllowed] reports which case applies, and
 * [bindIntent] builds the request that asks the user when it does not.
 */
object PrismWidgetHost {

    /**
     * Identifies this host to the system. NEVER CHANGE IT -- see the class comment. The value is
     * arbitrary; that it is stable is the whole point.
     */
    private const val HOST_ID = 0x5052 // 'PR'

    private const val TAG = "PrismWidgets"

    @Volatile
    private var host: AppWidgetHost? = null

    @Volatile
    private var listening = false

    fun manager(context: Context): AppWidgetManager =
        AppWidgetManager.getInstance(context.applicationContext)

    private fun host(context: Context): AppWidgetHost =
        host ?: synchronized(this) {
            host ?: AppWidgetHost(context.applicationContext, HOST_ID).also { host = it }
        }

    /** Starts receiving widget updates. Safe to call repeatedly. */
    fun startListening(context: Context) {
        synchronized(this) {
            if (listening) return
            runCatching { host(context).startListening() }
                .onFailure { PrismLogger.logWarning(TAG, "startListening failed: ${it.message}") }
                .onSuccess { listening = true }
        }
    }

    /** Stops receiving widget updates. Safe to call repeatedly. */
    fun stopListening(context: Context) {
        synchronized(this) {
            if (!listening) return
            runCatching { host(context).stopListening() }
            listening = false
        }
    }

    /** Reserves an id for a widget that is about to be placed. */
    fun allocateId(context: Context): Int = host(context).allocateAppWidgetId()

    /**
     * Releases an id.
     *
     * Called on every path that abandons a widget -- removed from the desktop, but also a bind the
     * user declined or a configuration activity they cancelled. An id that is allocated and then
     * dropped is a leak the system keeps forever.
     */
    fun releaseId(context: Context, appWidgetId: Int) {
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return
        runCatching { host(context).deleteAppWidgetId(appWidgetId) }
    }

    /** Builds the view that draws a widget. */
    fun createView(context: Context, appWidgetId: Int, info: AppWidgetProviderInfo): AppWidgetHostView =
        host(context).createView(context.applicationContext, appWidgetId, info)

    fun providerInfo(context: Context, appWidgetId: Int): AppWidgetProviderInfo? =
        runCatching { manager(context).getAppWidgetInfo(appWidgetId) }.getOrNull()

    /** Every widget installed on the device, for the picker. */
    fun installedProviders(context: Context): List<AppWidgetProviderInfo> =
        runCatching { manager(context).installedProviders }.getOrDefault(emptyList())

    /**
     * Attaches [provider] to [appWidgetId] without asking, when the system allows it.
     *
     * True only while Prism is the default launcher (the system grants BIND_APPWIDGET to whichever
     * app holds HOME). Otherwise this returns false and the caller has to send [bindIntent].
     */
    fun bindAllowed(context: Context, appWidgetId: Int, provider: AppWidgetProviderInfo): Boolean =
        runCatching {
            manager(context).bindAppWidgetIdIfAllowed(appWidgetId, provider.profile, provider.provider, null)
        }.getOrDefault(false)

    /** The request that asks the user to allow one widget to be bound. */
    fun bindIntent(appWidgetId: Int, provider: AppWidgetProviderInfo): android.content.Intent =
        android.content.Intent(AppWidgetManager.ACTION_APPWIDGET_BIND).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, provider.provider)
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, provider.profile)
        }

    /** Whether this widget insists on being configured before it can be shown. */
    fun needsConfiguration(info: AppWidgetProviderInfo?): Boolean = info?.configure != null

    /**
     * Tells a widget how much room it has, in dp.
     *
     * Widgets lay themselves out from this rather than from the view's measured size, so a resize
     * that does not call it leaves the widget drawing at its old size inside a new frame.
     */
    fun applySize(context: Context, view: AppWidgetHostView, appWidgetId: Int, widthDp: Int, heightDp: Int) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val sizes = listOf(android.util.SizeF(widthDp.toFloat(), heightDp.toFloat()))
                view.updateAppWidgetSize(Bundle(), sizes)
            } else {
                @Suppress("DEPRECATION")
                view.updateAppWidgetSize(Bundle(), widthDp, heightDp, widthDp, heightDp)
            }
            manager(context).updateAppWidgetOptions(
                appWidgetId,
                Bundle().apply {
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, widthDp)
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, heightDp)
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, widthDp)
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, heightDp)
                },
            )
        }
    }

    /**
     * How many cells a provider wants, given a cell size in dp.
     *
     * Rounded up, then clamped: a widget asking for more columns than the grid has would never fit
     * anywhere and is better shown squeezed than not at all.
     */
    fun defaultSpans(info: AppWidgetProviderInfo, cellWidthDp: Int, cellHeightDp: Int, columns: Int, rows: Int): Pair<Int, Int> {
        val wantedWidth = if (info.minWidth > 0) info.minWidth else cellWidthDp
        val wantedHeight = if (info.minHeight > 0) info.minHeight else cellHeightDp
        val spanX = ((wantedWidth + cellWidthDp - 1) / cellWidthDp).coerceIn(1, columns)
        val spanY = ((wantedHeight + cellHeightDp - 1) / cellHeightDp).coerceIn(1, rows)
        return spanX to spanY
    }
}
