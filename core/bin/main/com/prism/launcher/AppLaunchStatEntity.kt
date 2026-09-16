package com.prism.launcher

import androidx.room.Entity

@Entity(
    tableName = "app_launch_stats",
    primaryKeys = ["componentName", "hourOfDay"]
)
data class AppLaunchStatEntity(
    val componentName: String,
    val hourOfDay: Int,
    val launchCount: Int
)
