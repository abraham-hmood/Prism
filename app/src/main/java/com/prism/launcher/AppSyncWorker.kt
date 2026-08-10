package com.prism.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * One-time WorkManager job that does a full PackageManager scan and populates the
 * installed-apps database from scratch.
 *
 * Enqueued by [PrismApp] with [androidx.work.ExistingWorkPolicy.KEEP] so it only
 * runs on the very first launch (or after the app's data is cleared).
 */
class AppSyncWorker(
    private val ctx: Context,
    params: WorkerParameters,
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        return try {
            val dao = AppDatabase.get().installedAppDao()
            val entities = queryLauncherApps(ctx)
            dao.clearAll()
            dao.insertAll(entities)
            Log.i(TAG, "Full sync complete — ${entities.size} launcher apps stored.")
            Result.success()
        } catch (t: Throwable) {
            Log.e(TAG, "Full sync failed", t)
            Result.retry()
        }
    }

    companion object {
        const val TAG = "AppSyncWorker"
        const val WORK_NAME = "prism_full_app_sync"
    }
}

/**
 * Returns [InstalledAppEntity] for every activity that responds to
 * ACTION_MAIN / CATEGORY_LAUNCHER on this device.
 */
internal fun queryLauncherApps(ctx: Context): List<InstalledAppEntity> {
    val pm = ctx.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val list = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        pm.queryIntentActivities(intent, 0)
    }
    return list.map { ri ->
        InstalledAppEntity(
            packageName = ri.activityInfo.packageName,
            activityClass = ri.activityInfo.name,
        )
    }
}

/**
 * Returns [InstalledAppEntity] list for a single package, or empty if the package
 * has no launcher activities (e.g. libraries, services-only packages).
 */
internal fun queryLauncherAppsForPackage(ctx: Context, packageName: String): List<InstalledAppEntity> {
    val pm = ctx.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).apply {
        setPackage(packageName)
    }
    val list = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
    } else {
        @Suppress("DEPRECATION")
        pm.queryIntentActivities(intent, 0)
    }
    return list.map { ri ->
        InstalledAppEntity(
            packageName = ri.activityInfo.packageName,
            activityClass = ri.activityInfo.name,
        )
    }
}
