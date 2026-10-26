package com.prism.launcher.social

import android.content.Context
import androidx.work.*
import com.prism.launcher.PrismSettings
import com.prism.launcher.SlotAssignment
import com.prism.launcher.SlotPreferences
import java.util.concurrent.TimeUnit

class SocialBotWorker(context: Context, workerParams: WorkerParameters) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            val generated = NebulaSocialManager.generateNewContent(applicationContext, manual = false)
            // A skipped cycle is SUCCESS, not retry: the idle/charging gate deliberately declined
            // to spend a local model's battery right now, and retrying would fight that decision.
            // It is logged so "Nebula posted nothing for a day" has an answer in diagnostics.
            com.prism.launcher.PrismLogger.logInfo(
                "Nebula",
                if (generated) "Background cycle generated content"
                else "Background cycle skipped: device is not idle and charging",
            )
            Result.success()
        } catch (e: Exception) {
            com.prism.launcher.PrismLogger.logWarning(
                "Nebula", "Background cycle failed: ${e.message}",
            )
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "NebulaSocialSync"

        /**
         * (Re)schedules the periodic generation cycle using the user-configured interval, or
         * cancels it entirely if the Nebula Social page isn't assigned to any desktop slot.
         * Safe to call repeatedly (on app start, whenever slot assignments change, or whenever
         * the interval setting changes) — [ExistingPeriodicWorkPolicy.UPDATE] applies a changed
         * interval immediately instead of waiting for the next launch.
         */
        fun schedule(context: Context) {
            val nebulaActive = SlotPreferences().getAssignments().any { it is SlotAssignment.NebulaSocial }
            if (!nebulaActive) {
                WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
                return
            }

            val constraintsBuilder = Constraints.Builder()
            // Local models generate fully offline; only cloud mode actually needs connectivity.
            // (NebulaSocialManager separately enforces its own idle/charging gate for local.)
            if (PrismSettings.getAiMode() == PrismSettings.AI_MODE_CLOUD) {
                constraintsBuilder.setRequiredNetworkType(NetworkType.CONNECTED)
            }

            val intervalHours = PrismSettings.getNebulaGenerationIntervalHours().coerceAtLeast(1)
            val request = PeriodicWorkRequestBuilder<SocialBotWorker>(intervalHours.toLong(), TimeUnit.HOURS)
                .setConstraints(constraintsBuilder.build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }
    }
}
