package com.prism.launcher

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface AppLaunchStatDao {
    
    @Query("""
        UPDATE app_launch_stats 
        SET launchCount = launchCount + 1 
        WHERE componentName = :componentName AND hourOfDay = :hourOfDay
    """)
    suspend fun increment(componentName: String, hourOfDay: Int): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOrIgnore(entity: AppLaunchStatEntity): Long

    @Query("""
        SELECT componentName 
        FROM app_launch_stats 
        WHERE hourOfDay = :hourOfDay 
        ORDER BY launchCount DESC 
        LIMIT :limit
    """)
    suspend fun getTopForHour(hourOfDay: Int, limit: Int): List<String>

    @Query("SELECT * FROM app_launch_stats WHERE hourOfDay = :hourOfDay")
    suspend fun getStatsForHour(hourOfDay: Int): List<AppLaunchStatEntity>

    @Query("""
        SELECT componentName 
        FROM app_launch_stats 
        ORDER BY launchCount DESC 
        LIMIT :limit
    """)
    suspend fun getTopOverall(limit: Int): List<String>
}
