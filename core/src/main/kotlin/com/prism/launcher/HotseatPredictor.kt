package com.prism.launcher

import com.prism.core.PrismPlatform
import java.util.Calendar

/**
 * Picks which apps to show in the hotseat, by time of day.
 *
 * Three tiers, in order: what this user launches at this hour, what they launch overall, and --
 * for a fresh install with no history at all -- a random but *stable* selection, cached so the
 * hotseat does not reshuffle itself on every launch.
 *
 * Fully portable once [AppDatabase] was: the only Android in it was the `Context` needed to reach
 * the database and the preferences, both of which are now capabilities.
 */
object HotseatPredictor {

    const val PREFS = "PrismHotseat"
    private const val KEY_FALLBACK = "initial_fallback"

    suspend fun getPredictions(limit: Int = 4): List<String> {
        val db = AppDatabase.get()
        val dao = db.appLaunchStatDao()
        val currentHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)

        // 1. Get predictions for this specific hour
        val predictions = dao.getTopForHour(currentHour, limit).toMutableList()

        // 2. If short, fallback to overall most used apps
        if (predictions.size < limit) {
            val overall = dao.getTopOverall(limit)
            for (cn in overall) {
                if (predictions.size >= limit) break
                if (!predictions.contains(cn)) {
                    predictions.add(cn)
                }
            }
        }

        // 3. Still short? Use persistent fallback (random selection for new users)
        if (predictions.size < limit) {
            val prefs = PrismPlatform.host.prefs(PREFS)
            val cachedStr = prefs.getString(KEY_FALLBACK, null)

            val fallbackPool = if (cachedStr != null) {
                cachedStr.split(",")
            } else {
                val shuffled = db.installedAppDao().getAll()
                    .map { "${it.packageName}/${it.activityClass}" }
                    .shuffled()
                prefs.edit().putString(KEY_FALLBACK, shuffled.joinToString(",")).apply()
                shuffled
            }

            for (cn in fallbackPool) {
                if (predictions.size >= limit) break
                if (!predictions.contains(cn)) {
                    predictions.add(cn)
                }
            }
        }

        return predictions
    }
}
