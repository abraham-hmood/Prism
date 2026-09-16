package com.prism.launcher

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.prism.core.PrismPlatform
import kotlinx.coroutines.Dispatchers
import java.io.File

/**
 * Opens [AppDatabase] on a desktop JVM.
 *
 * DELIBERATELY ITS OWN FILE. `androidx.sqlite:sqlite-bundled` is a `compileOnly` dependency of
 * :core -- Android supplies SQLite through the framework and must not ship a second copy of it --
 * so [BundledSQLiteDriver] is absent from the Android runtime classpath. Keeping this in a
 * separate class means the Android build never loads it, and never has the chance to fail
 * verification over a class it was never going to call. Folding it into `AppDatabase` as a
 * default would put that reference inside a class every platform loads.
 *
 * The bundled driver, rather than the JDBC one, because it carries SQLite's own native library
 * for Windows, macOS and Linux. That keeps desktop on the *same* SQLite implementation Android
 * uses, which matters here: several DAO queries lean on SQLite-specific behaviour (`LIKE` with
 * `||` concatenation, `GROUP BY` picking a bare column) that other engines treat differently.
 */
object JvmDatabase {

    /** The default location: `prism_apps.db` beside every other Prism data file. */
    fun defaultFile(): File = File(PrismPlatform.host.dataDir(), AppDatabase.FILE_NAME)

    fun open(file: File = defaultFile()): AppDatabase {
        file.parentFile?.mkdirs()
        return Room.databaseBuilder<AppDatabase>(name = file.absolutePath)
            .setDriver(BundledSQLiteDriver())
            // Room refuses to run suspend DAO functions off Android without being told which
            // dispatcher to use. IO rather than Default: these block on a file, they do not compute.
            .setQueryCoroutineContext(Dispatchers.IO)
            // Matches the Android build exactly. Worth being explicit that this is a real choice
            // and not an oversight: Prism's tables are a cache of things it can rebuild -- the
            // installed-app list, launch statistics, a synthetic social feed -- so dropping them
            // on a schema change costs a resync, whereas maintaining eleven hand-written
            // migrations for a single-user launcher costs considerably more.
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
    }

    /** Installs [open] as the process-wide opener. Called from `main`. */
    fun install(file: File = defaultFile()) {
        AppDatabase.opener = { open(file) }
    }
}
