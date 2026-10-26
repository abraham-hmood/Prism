package com.prism.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.thread

/**
 * Manifest-registered receiver that keeps the installed-app database current
 * whenever a package is added, updated, replaced, or removed — even when the
 * launcher is not running in the foreground.
 *
 * Registered in AndroidManifest.xml with:
 *   PACKAGE_ADDED | PACKAGE_REPLACED | PACKAGE_CHANGED → upsert
 *   PACKAGE_REMOVED | PACKAGE_FULLY_REMOVED            → delete
 *
 * ## Why it uses `goAsync` instead of a fire-and-forget coroutine
 *
 * `onReceive` is allowed to return immediately, and the process is then free to be killed. The
 * database write used to be launched on `GlobalScope`, which has exactly that lifetime — so an
 * install that arrived while the launcher was in the background could have its write silently
 * dropped, and the app would not appear until the next full sync. `goAsync` tells the system there
 * is still work in flight and gives it a bounded window (ten seconds) to finish.
 *
 * ## An empty answer is retried before it is believed
 *
 * During `PACKAGE_ADDED` the launcher activity is usually resolvable, but not always: an app that
 * registers its icon later, or a package manager still committing the install, can answer with no
 * activities for a moment. Treating that as "this package has no launcher icon" deleted the row
 * that a previous scan had written, which is why a freshly installed app sometimes needed a manual
 * reload. Three short retries, then an independent check with `getLaunchIntentForPackage`, make the
 * difference between "not yet" and "genuinely has no icon".
 */
class AppPackageReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pkg = intent.data?.schemeSpecificPart ?: return
        val action = intent.action ?: return
        val isReplacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)

        Log.d(TAG, "Received $action for $pkg (replacing=$isReplacing)")

        // The label and icon may both have changed, and the drawer may be showing the old ones.
        AppIconCache.invalidate(pkg)

        val appContext = context.applicationContext
        val pending = goAsync()
        thread(name = "prism-package-$pkg") {
            try {
                handle(appContext, action, pkg, isReplacing)
            } catch (t: Throwable) {
                Log.w(TAG, "Could not process $action for $pkg", t)
            } finally {
                // Always released: leaving it held would keep the process alive for the system's
                // full timeout on every broadcast, which is how a launcher becomes a battery note.
                pending.finish()
            }
        }
    }

    private fun handle(context: Context, action: String, pkg: String, isReplacing: Boolean) {
        val dao = AppDatabase.get().installedAppDao()
        when (action) {
            Intent.ACTION_PACKAGE_ADDED,
            Intent.ACTION_PACKAGE_REPLACED,
            Intent.ACTION_PACKAGE_CHANGED -> {
                var entities = queryLauncherAppsForPackage(context, pkg)
                var attempts = 0
                while (entities.isEmpty() && attempts < 3) {
                    attempts++
                    Thread.sleep(250)
                    entities = queryLauncherAppsForPackage(context, pkg)
                }

                if (entities.isNotEmpty()) {
                    runBlocking { dao.insertAll(entities) }
                    Log.i(TAG, "Upserted ${entities.size} entry/entries for $pkg")
                } else if (!hasLauncherIntent(context, pkg)) {
                    // Both answers agree that this package has nothing to launch. That is normal
                    // for a library, and it is also what an update that removed the launcher
                    // activity looks like.
                    runBlocking { dao.deleteByPackage(pkg) }
                    Log.i(TAG, "No launcher activity for $pkg — removed from DB")
                } else {
                    // Contradictory: the package resolves a launcher intent but not through a
                    // query. Almost always package-visibility or a moment mid-install. Keeping a
                    // stale row is better than deleting a working app.
                    Log.i(TAG, "No queryable launcher activity for $pkg yet; keeping existing rows")
                }
            }
            Intent.ACTION_PACKAGE_REMOVED,
            Intent.ACTION_PACKAGE_FULLY_REMOVED -> {
                if (!isReplacing) {
                    runBlocking { dao.deleteByPackage(pkg) }
                    Log.i(TAG, "Removed $pkg from DB")
                }
            }
        }
    }

    private fun hasLauncherIntent(context: Context, pkg: String): Boolean = runCatching {
        context.packageManager.getLaunchIntentForPackage(pkg) != null
    }.getOrElse { e ->
        // A NameNotFound here means the package really is gone, which is also a "no".
        e !is PackageManager.NameNotFoundException && e.message?.contains("not found") != true
    }

    companion object {
        private const val TAG = "AppPackageReceiver"
    }
}
