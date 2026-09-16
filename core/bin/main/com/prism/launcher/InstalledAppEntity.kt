package com.prism.launcher

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Represents one launcher-visible app in the local database.
 * Only identity data is stored — labels/icons are always loaded live from PackageManager
 * so they stay accurate after icon-pack changes, locale switches, and updates.
 */
@Entity(tableName = "installed_apps")
data class InstalledAppEntity(
    /** Package name, e.g. "com.example.myapp" */
    @PrimaryKey val packageName: String,
    /** Fully-qualified launcher Activity class, e.g. "com.example.myapp.MainActivity" */
    val activityClass: String,
)
