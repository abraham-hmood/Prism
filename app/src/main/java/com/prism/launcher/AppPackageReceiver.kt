package com.prism.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

/**
 * Manifest-registered receiver that keeps the installed-app database current
 * whenever a package is added, updated, replaced, or removed — even when the
 * launcher is not running in the foreground.
 *
 * Registered in AndroidManifest.xml with:
 *   PACKAGE_ADDED | PACKAGE_REPLACED | PACKAGE_CHANGED → upsert
 *   PACKAGE_REMOVED | PACKAGE_FULLY_REMOVED            → delete
 */
class AppPackageReceiver : BroadcastReceiver() {

    @OptIn(DelicateCoroutinesApi::class)
    override fun onReceive(context: Context, intent: Intent) {
        val pkg = intent.data?.schemeSpecificPart ?: return
        // PACKAGE_REPLACED fires together with PACKAGE_REMOVED (removing old)
        // then PACKAGE_ADDED (adding new). We guard against deleting a freshly
        // installed update by checking the EXTRA_REPLACING flag.
        val isReplacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)

        val action = intent.action ?: return
        Log.d(TAG, "Received $action for $pkg (replacing=$isReplacing)")

        val dao = AppDatabase.get().installedAppDao()

        GlobalScope.launch(Dispatchers.IO) {
            when (action) {
                Intent.ACTION_PACKAGE_ADDED,
                Intent.ACTION_PACKAGE_REPLACED,
                Intent.ACTION_PACKAGE_CHANGED -> {
                    val entities = queryLauncherAppsForPackage(context, pkg)
                    if (entities.isNotEmpty()) {
                        dao.insertAll(entities)
                        Log.i(TAG, "Upserted ${entities.size} entry/entries for $pkg")
                    } else {
                        // Package exists but has no launcher activity (e.g. library)
                        dao.deleteByPackage(pkg)
                        Log.i(TAG, "No launcher activity for $pkg — removed from DB")
                    }
                }
                Intent.ACTION_PACKAGE_REMOVED,
                Intent.ACTION_PACKAGE_FULLY_REMOVED -> {
                    if (!isReplacing) {
                        dao.deleteByPackage(pkg)
                        Log.i(TAG, "Removed $pkg from DB")
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "AppPackageReceiver"
    }
}
