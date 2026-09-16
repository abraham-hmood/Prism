package com.prism.launcher

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface InstalledAppDao {

    /** Returns every stored app, alphabetically by package name. */
    @Query("SELECT * FROM installed_apps ORDER BY packageName ASC")
    suspend fun getAll(): List<InstalledAppEntity>

    /**
     * Same as [getAll] but as a live Room [Flow] -- automatically re-emits whenever the table
     * changes, including writes made by [AppPackageReceiver] in the background. Lets any
     * observer (the app drawer, etc.) stay in sync without needing to be re-attached/recreated.
     */
    @Query("SELECT * FROM installed_apps ORDER BY packageName ASC")
    fun observeAll(): Flow<List<InstalledAppEntity>>

    /**
     * Upsert one or more apps. Uses REPLACE so a re-installed or updated
     * package simply overwrites its previous row.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(apps: List<InstalledAppEntity>)

    /** Remove a specific package (called when the package is uninstalled). */
    @Query("DELETE FROM installed_apps WHERE packageName = :pkg")
    suspend fun deleteByPackage(pkg: String)

    /** Wipe the whole table — used before a full re-sync. */
    @Query("DELETE FROM installed_apps")
    suspend fun clearAll()

    /** How many rows are currently stored. */
    @Query("SELECT COUNT(*) FROM installed_apps")
    suspend fun count(): Int
}
