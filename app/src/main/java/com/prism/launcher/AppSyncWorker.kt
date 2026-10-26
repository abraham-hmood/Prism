package com.prism.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.prism.launcher.PrismLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
            val entities = withContext(Dispatchers.IO) { queryLauncherApps(ctx) }

            // AN EMPTY SCAN IS NOT AN EMPTY DEVICE. `queryLauncherApps` answering with nothing is
            // how a package-manager hiccup or a locked-down profile looks, and clearing the table
            // on that answer would empty the drawer until the next successful sync. Leaving the
            // existing rows alone and retrying with backoff keeps a stale listing instead of no
            // listing.
            if (entities.isEmpty()) {
                PrismLogger.logWarning(TAG, "Scan returned no launcher apps; keeping the existing list")
                return Result.retry()
            }

            val dao = AppDatabase.get().installedAppDao()
            dao.clearAll()
            dao.insertAll(entities)
            lastSyncAt = System.currentTimeMillis()
            PrismLogger.logSuccess(
                TAG,
                "Full sync complete — ${entities.size} launcher apps stored."
            )
            Result.success()
        } catch (t: Throwable) {
            PrismLogger.logError(TAG, "Full sync failed; retrying with backoff", t)
            Result.retry()
        }
    }

    companion object {
        const val TAG = "AppSyncWorker"
        const val WORK_NAME = "prism_full_app_sync"

        /**
         * Unique name for the daily refresh.
         *
         * The one-time worker is enqueued with KEEP, which means it runs exactly once in the
         * lifetime of an install. Without this second job the table only ever changes when a
         * package broadcast arrives -- and a broadcast missed while the app was force-stopped, or
         * an app installed before the receiver was registered, stayed wrong forever, with nothing
         * logged to say why.
         */
        const val PERIODIC_WORK_NAME = "prism_app_sync_periodic"

        /** Wall-clock of the last successful scan, for diagnostics. */
        @Volatile
        var lastSyncAt: Long = 0L
            private set
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
