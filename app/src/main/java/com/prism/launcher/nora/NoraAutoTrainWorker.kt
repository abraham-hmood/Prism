package com.prism.launcher.nora

import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.prism.launcher.PrismLogger
import com.prism.launcher.PrismSettings
import com.prism.launcher.SlotAssignment
import com.prism.launcher.SlotPreferences
import java.util.concurrent.TimeUnit

/**
 * Unattended retraining while the phone is idle.
 *
 * The gate is deliberately narrow, and every condition is checked twice -- once when scheduling,
 * once again in [doWork] -- because a periodic request survives reboots and setting changes, so
 * by the time a run fires the world may not look the way it did when it was enqueued.
 *
 * WHY IDLE, SPECIFICALLY. Training is the most expensive thing Prism does: every core busy,
 * a partial wake lock held, and a checkpoint written each epoch. Running that while the user is
 * holding the phone would be felt immediately as heat and dropped frames.
 * [Constraints.Builder.setRequiresDeviceIdle] is the system's own definition of "not in use" and
 * is the right one to defer to rather than inventing a heuristic from screen state.
 *
 * WHY IT DOES NOT TRAIN HERE. The work is handed to [NoraService] rather than run in this
 * worker. One owner for the brain is the whole reason that service exists -- the connectome is a
 * single shared object with mutable weights, and a worker training concurrently with a chat
 * generation would corrupt both. The service already refuses overlapping work, holds the wake
 * lock, and posts the progress notification, so there is nothing to gain by duplicating it here
 * and a real invariant to lose.
 *
 * THE FOREGROUND-SERVICE CAVEAT. From Android 12 an app in the background usually cannot start a
 * foreground service, and a WorkManager job does not by itself grant an exemption. Exemption
 * comes from the battery-optimization allowlist, which Nora's training screen already prompts
 * for and which overnight training needs regardless. If it has not been granted, the start
 * throws and this returns a retry rather than failing silently, so the run is not simply lost.
 */
class NoraAutoTrainWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext

        // Re-check every precondition. A periodic request outlives the settings that created it.
        if (!PrismSettings.getNoraAutoTrainEnabled()) return Result.success()
        if (!messagesPageActive(ctx)) return Result.success()

        // Nothing to learn from is not a failure, and retrying would only burn wakeups.
        if (NoraTrainer.loadDataset().isEmpty()) {
            PrismLogger.logInfo("Nora", "Autonomous training skipped: dataset is empty")
            return Result.success()
        }

        // Something already has the brain -- a generation, or a manual training run the user
        // started. Retry rather than queue: the next attempt is minutes away, and refusing is
        // better than waiting an unbounded time holding a job slot.
        if (NoraStudio.busy) {
            PrismLogger.logInfo("Nora", "Autonomous training deferred: Nora is busy")
            return Result.retry()
        }

        return try {
            NoraService.startTraining(ctx, NoraConfig.AUTO_TRAIN_EPOCHS, focus = null)
            PrismLogger.logInfo(
                "Nora",
                "Autonomous training started (${NoraConfig.AUTO_TRAIN_EPOCHS} epochs)"
            )
            Result.success()
        } catch (e: Exception) {
            PrismLogger.logError(
                "Nora",
                "Could not start autonomous training: ${e.message}. Exempting Prism from " +
                    "battery optimization allows a background foreground-service start.",
                e
            )
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "NoraAutonomousTraining"

        /**
         * (Re)schedules or cancels the cycle. Safe to call repeatedly -- on app start, when slot
         * assignments change, and whenever the toggle or interval changes.
         *
         * [ExistingPeriodicWorkPolicy.UPDATE] applies a changed interval immediately rather than
         * waiting for the current period to elapse first.
         */
        fun schedule(context: Context) {
            val enabled = PrismSettings.getNoraAutoTrainEnabled()
            if (!enabled || !messagesPageActive(context)) {
                WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
                return
            }

            val hours = PrismSettings.getNoraAutoTrainIntervalHours().toLong()
            val constraints = Constraints.Builder()
                .setRequiresDeviceIdle(true)
                .setRequiresBatteryNotLow(true)
                .build()

            val request = PeriodicWorkRequestBuilder<NoraAutoTrainWorker>(hours, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }

        /**
         * Whether the Messages page is assigned to any desktop slot.
         *
         * The condition the feature is specified around, and a sensible one: Messages is a
         * non-default page, and Nora is only reachable through it. Training a brain the user has
         * no way to talk to would be spending their battery on nothing.
         */
        fun messagesPageActive(context: Context): Boolean =
            SlotPreferences().getAssignments().any { it is SlotAssignment.Messaging }

        /**
         * Whether Prism is exempt from battery optimization.
         *
         * Surfaced in Settings rather than only logged, because without it an idle-gated
         * background start is likely to be refused outright on Android 12 and later -- and a
         * feature that silently never runs is worse than one that says why.
         */
        fun batteryExempt(context: Context): Boolean = try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                true
            } else {
                val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                pm.isIgnoringBatteryOptimizations(context.packageName)
            }
        } catch (e: Exception) {
            false
        }
    }
}
