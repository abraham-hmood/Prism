package com.prism.launcher

import com.prism.core.AppCatalog
import com.prism.core.PrismPlatform
import com.prism.core.TaskScheduler
import java.util.Calendar

/**
 * Keeps the installed-app table in step with what is actually installed, and records launches.
 *
 * WHY THIS EXISTS SEPARATELY FROM THE CATALOG. [AppCatalog] answers "what is installed right
 * now" by scanning the filesystem, which takes a noticeable moment on a machine with a few
 * hundred applications. The database is what the hotseat prediction joins against, and it also
 * has to remember apps across restarts so statistics survive. So the catalog is the source and
 * the table is the cache, and this is the thing that reconciles them.
 *
 * DESKTOP HAS NO PACKAGE_ADDED BROADCAST, which is the one structural difference from Android.
 * There is no signal when an application is installed, so the reconcile runs on a schedule and at
 * startup. A polling interval is a worse mechanism than a broadcast and it is the only one
 * available; five minutes is short enough that a newly installed app appears without the user
 * thinking about it, and long enough that the scan is not constantly running.
 */
object AppSync {

    const val JOB_NAME = "prism.app-sync"

    private const val INTERVAL_MILLIS = 5 * 60 * 1000L

    /**
     * Reconciles the table against the catalog.
     *
     * Full replace rather than a diff: the table is a cache of identity only -- package name and
     * launcher class -- so rewriting it is cheap and cannot drift, whereas a diff has to get
     * both the additions and the removals right to stay correct. Launch statistics live in a
     * separate table and are untouched by this, which is the property that makes replacing safe.
     */
    suspend fun sync(catalog: AppCatalog): Int {
        val entries = catalog.list()
        if (entries.isEmpty()) {
            // A scan that returns nothing is far more likely to be a failed scan than a machine
            // with no applications, and wiping the table on it would throw away the drawer.
            PrismPlatform.log.warn("Prism/appsync", "Catalog returned nothing; keeping the table")
            return 0
        }

        val dao = AppDatabase.get().installedAppDao()
        val rows = entries.map { entry ->
            // AppEntry.id is a flattened ComponentName on Android and a path elsewhere. Splitting
            // on the separator keeps the Android rows exactly as AppSyncWorker wrote them, so
            // existing launch statistics still join.
            val slash = entry.id.lastIndexOf('/')
            if (slash > 0) {
                InstalledAppEntity(entry.id.substring(0, slash), entry.id.substring(slash + 1))
            } else {
                InstalledAppEntity(entry.id, entry.label)
            }
        }.distinctBy { it.packageName }

        dao.clearAll()
        dao.insertAll(rows)
        PrismPlatform.log.info("Prism/appsync", "Synced ${rows.size} applications")
        return rows.size
    }

    /**
     * Records that an app was launched, in this hour.
     *
     * INCREMENT FIRST, AND THE SEED ROW HOLDS ZERO. `increment` returns how many rows it touched;
     * zero means there is no row for this component at this hour yet, so one is seeded and then
     * incremented.
     *
     * The seed count is 0 rather than 1, which looks wrong and is not. Seeding 1 and then
     * incrementing would record a brand new app's first launch as two. Seeding 1 and NOT
     * incrementing would be correct alone but breaks under a race: two simultaneous launches both
     * see increment return 0, both try to insert, the second is dropped by IGNORE, and one launch
     * vanishes. Seeding 0 and always incrementing is correct in both cases -- the insert
     * establishes the row, the increment counts the launch, and a lost race still gets its
     * increment.
     */
    suspend fun recordLaunch(appId: String) {
        try {
            val dao = AppDatabase.get().appLaunchStatDao()
            val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            if (dao.increment(appId, hour) == 0) {
                dao.insertOrIgnore(AppLaunchStatEntity(appId, hour, 0))
                dao.increment(appId, hour)
            }
        } catch (e: Exception) {
            // A launch must never fail because bookkeeping did.
            PrismPlatform.log.error("Prism/appsync", "Could not record a launch of $appId", e)
        }
    }

    /** Registers the periodic reconcile. Safe to call unconditionally; it replaces by name. */
    fun schedule(catalog: AppCatalog) {
        PrismPlatform.scheduler.schedule(
            TaskScheduler.Job(
                name = JOB_NAME,
                intervalMillis = INTERVAL_MILLIS,
                // Run once promptly at startup: a fresh install has an empty table, and waiting
                // five minutes for the first drawer to populate would read as broken.
                initialDelayMillis = 2_000,
                block = { sync(catalog) },
            )
        )
    }
}
