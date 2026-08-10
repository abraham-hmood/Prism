package com.prism.launcher

import androidx.room.Database
import androidx.room.RoomDatabase
import com.prism.launcher.messaging.AiMessageDao
import com.prism.launcher.messaging.AiMessageEntity

/**
 * Prism's local database.
 *
 * THE SCHEMA IS UNCHANGED BY THE MOVE TO :core. Same table names, same columns, same
 * `version = 11`, same file name `prism_apps.db`. That is not incidental tidiness -- an existing
 * Android install has this database on disk, and Room compares the schema it finds against the
 * one it expects. A renamed column or a bumped version would meet
 * `fallbackToDestructiveMigration` and silently delete the user's app statistics, agentic tools
 * and entire Nebula feed on upgrade. Nothing would report it; the app would simply come up empty.
 *
 * WHY ROOM 2.7 RATHER THAN A REWRITE. 2.7 is the first release whose runtime is not Android-only:
 * the same `androidx.room:room-runtime` coordinate publishes a `jvm` variant alongside the
 * `android` one, and Gradle picks the right one per module. So the annotations, the DAOs and the
 * ~40 hand-written SQL queries moved here unchanged. Rewriting them in SQLDelight or raw JDBC
 * would have meant re-deriving every query by hand with no way to prove the new results matched
 * the old ones, against a schema real users already have.
 *
 * WHAT DID CHANGE is how the database is opened. Android hands Room a `Context`; a desktop JVM
 * hands it a file path and an explicit SQLite driver. Neither can be expressed in the other's
 * terms, so [opener] is a hook the platform installs -- the same pattern as
 * `PrismPlatform.install`, for the same reason.
 */
@Database(
    entities = [
        InstalledAppEntity::class,
        AppLaunchStatEntity::class,
        AiMessageEntity::class,
        com.prism.launcher.social.SocialPostEntity::class,
        com.prism.launcher.social.SocialBotEntity::class,
        com.prism.launcher.social.SocialMessageEntity::class,
        com.prism.launcher.social.SocialCommentEntity::class,
        com.prism.launcher.social.SocialFollowEntity::class,
        com.prism.launcher.social.SocialInteractionEntity::class,
        com.prism.launcher.agentic.AgenticToolEntity::class,
        com.prism.launcher.agentic.AgenticSyntaxEntity::class,
    ],
    version = 11,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun installedAppDao(): InstalledAppDao
    abstract fun appLaunchStatDao(): AppLaunchStatDao
    abstract fun aiMessageDao(): AiMessageDao
    abstract fun socialDao(): com.prism.launcher.social.SocialDao
    abstract fun agenticDao(): com.prism.launcher.agentic.AgenticDao

    companion object {

        /** The on-disk file name. Part of the contract with every existing install. */
        const val FILE_NAME = "prism_apps.db"

        /**
         * How this platform opens the database.
         *
         * Installed once at startup: from `PrismApplication.onCreate` on Android, from `main` on
         * desktop. Left unset it throws rather than guessing, because the two plausible guesses --
         * an Android context that does not exist, or a bundled SQLite driver that is not on the
         * classpath -- both fail later and less clearly than this does.
         */
        @Volatile
        @JvmStatic
        var opener: (() -> AppDatabase)? = null

        @Volatile
        private var instance: AppDatabase? = null

        /**
         * The singleton. Double-checked locking, exactly as before -- several page views build
         * themselves on the main thread and would otherwise each open their own connection.
         */
        @JvmStatic
        fun get(): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: run {
                    val open = opener ?: error(
                        "No database opener installed. Set AppDatabase.opener during startup -- " +
                            "PrismApplication.onCreate on Android, main() on desktop."
                    )
                    open().also { instance = it }
                }
            }

        /**
         * Drops the cached handle. For tests, which open a fresh database per case and would
         * otherwise all share whichever one happened to be created first.
         */
        @JvmStatic
        fun resetForTesting() {
            synchronized(this) {
                instance?.close()
                instance = null
            }
        }
    }
}
