package com.prism.core

import com.prism.launcher.AppDatabase
import com.prism.launcher.AppSync
import com.prism.launcher.JvmDatabase
import kotlinx.coroutines.test.runTest
import java.io.File
import java.util.Calendar
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins launch counting and catalog reconciliation.
 *
 * Counting is worth a test because the obvious implementation is off by one and the error is
 * invisible: a first launch recorded as two still produces a plausible-looking hotseat, just a
 * subtly wrong one, and nothing ever reports it. The first version of `recordLaunch` seeded the
 * row with a count of 1 and then incremented it, which did exactly that.
 */
class AppSyncTest {

    private lateinit var dir: File
    private lateinit var previousHost: PlatformHost

    @BeforeTest
    fun open() {
        previousHost = PrismPlatform.host
        dir = File(System.getProperty("java.io.tmpdir"), "prism-sync-${System.nanoTime()}")
        dir.mkdirs()
        PrismPlatform.host = JvmHost(rootOverride = dir)
        AppDatabase.resetForTesting()
        AppDatabase.opener = { JvmDatabase.open(File(dir, "sync.db")) }
    }

    @AfterTest
    fun close() {
        AppDatabase.resetForTesting()
        AppDatabase.opener = null
        PrismPlatform.host = previousHost
        dir.deleteRecursively()
    }

    private fun hour() = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)

    /** The bug that motivated the test: one launch must count as one. */
    @Test
    fun `a first launch counts once`() = runTest {
        AppSync.recordLaunch("com.x/Main")

        val stats = AppDatabase.get().appLaunchStatDao().getStatsForHour(hour())
        assertEquals(1, stats.size)
        assertEquals(1, stats.single().launchCount, "a first launch must count exactly one")
    }

    @Test
    fun `repeated launches accumulate`() = runTest {
        repeat(5) { AppSync.recordLaunch("com.x/Main") }
        assertEquals(5, AppDatabase.get().appLaunchStatDao().getStatsForHour(hour()).single().launchCount)
    }

    /** Distinct apps must not share a counter. */
    @Test
    fun `counts are per app`() = runTest {
        repeat(3) { AppSync.recordLaunch("com.a/Main") }
        AppSync.recordLaunch("com.b/Main")

        val byId = AppDatabase.get().appLaunchStatDao().getStatsForHour(hour()).associateBy { it.componentName }
        assertEquals(3, byId["com.a/Main"]?.launchCount)
        assertEquals(1, byId["com.b/Main"]?.launchCount)
    }

    /** The most-launched app comes back first, which is what the taskbar consumes. */
    @Test
    fun `top overall is ordered by count`() = runTest {
        repeat(2) { AppSync.recordLaunch("com.quiet/Main") }
        repeat(9) { AppSync.recordLaunch("com.busy/Main") }

        assertEquals(
            listOf("com.busy/Main", "com.quiet/Main"),
            AppDatabase.get().appLaunchStatDao().getTopOverall(2),
        )
    }

    /** Recording must never throw, whatever the database does -- a launch outranks bookkeeping. */
    @Test
    fun `recording survives a closed database`() = runTest {
        AppDatabase.get().close()
        AppSync.recordLaunch("com.x/Main")  // must not throw
    }

    /** An id of the form "package/Class" splits back into the columns Android already writes. */
    @Test
    fun `sync splits component ids into package and class`() = runTest {
        val catalog = FakeCatalog(
            listOf(
                AppEntry(id = "com.x/com.x.Main", label = "X"),
                AppEntry(id = "com.y/com.y.Main", label = "Y"),
            )
        )
        assertEquals(2, AppSync.sync(catalog))

        val rows = AppDatabase.get().installedAppDao().getAll().associateBy { it.packageName }
        assertEquals("com.x.Main", rows["com.x"]?.activityClass)
        assertEquals("com.y.Main", rows["com.y"]?.activityClass)
    }

    /** A desktop id is a path with no component separator; it must still store. */
    @Test
    fun `sync stores path style ids`() = runTest {
        val catalog = FakeCatalog(listOf(AppEntry(id = "C:\\Apps\\Thing.lnk", label = "Thing")))
        assertEquals(1, AppSync.sync(catalog))
        assertEquals("C:\\Apps\\Thing.lnk", AppDatabase.get().installedAppDao().getAll().single().packageName)
    }

    /** Re-syncing replaces rather than duplicates. */
    @Test
    fun `sync is idempotent`() = runTest {
        val catalog = FakeCatalog(listOf(AppEntry(id = "com.x/Main", label = "X")))
        AppSync.sync(catalog)
        AppSync.sync(catalog)
        assertEquals(1, AppDatabase.get().installedAppDao().count())
    }

    /**
     * An empty scan must NOT wipe the table.
     *
     * A scan returning nothing is far more likely to be a failed scan -- a permissions problem, a
     * directory that moved -- than a machine with no applications, and clearing the table on it
     * would empty the user's drawer for no reason.
     */
    @Test
    fun `an empty catalog does not clear the table`() = runTest {
        AppSync.sync(FakeCatalog(listOf(AppEntry(id = "com.x/Main", label = "X"))))
        assertEquals(0, AppSync.sync(FakeCatalog(emptyList())))
        assertEquals(1, AppDatabase.get().installedAppDao().count(), "the table must survive")
    }

    /** Launch statistics live in another table and must survive a resync. */
    @Test
    fun `sync preserves launch statistics`() = runTest {
        AppSync.recordLaunch("com.x/Main")
        AppSync.sync(FakeCatalog(listOf(AppEntry(id = "com.x/Main", label = "X"))))
        assertEquals(1, AppDatabase.get().appLaunchStatDao().getStatsForHour(hour()).single().launchCount)
    }

    /** Two catalog entries resolving to one package must not violate the primary key. */
    @Test
    fun `duplicate packages collapse`() = runTest {
        val catalog = FakeCatalog(
            listOf(
                AppEntry(id = "com.x/One", label = "One"),
                AppEntry(id = "com.x/Two", label = "Two"),
            )
        )
        assertEquals(1, AppSync.sync(catalog))
        assertEquals(1, AppDatabase.get().installedAppDao().count())
    }

    private class FakeCatalog(private val entries: List<AppEntry>) : AppCatalog {
        override fun list(): List<AppEntry> = entries
        override fun launch(entry: AppEntry, uri: String?): Boolean = true
    }
}
