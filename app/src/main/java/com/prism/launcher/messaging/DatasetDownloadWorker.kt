package com.prism.launcher.messaging

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.prism.launcher.PrismLogger
import com.prism.launcher.PrismSettings
import java.util.concurrent.TimeUnit

/**
 * Unattended dataset discovery + download, on the same [NoraAutoTrainWorker]/`SocialBotWorker`
 * pattern (a periodic, durable-across-reboot [CoroutineWorker], gated by a settings toggle and
 * interval that are re-checked in [doWork] itself, since a periodic request outlives the settings
 * that created it).
 *
 * Unlike training, a dataset download is network-bound rather than CPU/thermal-heavy, so this is
 * NOT idle-gated -- it's fine to run while the phone is in normal use, same as any other
 * background download. It IS gated to unmetered networks, since a dataset can legitimately be
 * hundreds of megabytes and nothing here asked the user's permission to spend mobile data on it.
 */
class DatasetDownloadWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!PrismSettings.getDatasetAutoDownloadEnabled()) return Result.success()

        val minBytes = PrismSettings.getDatasetMinSizeBytes()
        val maxBytes = PrismSettings.getDatasetMaxSizeBytes()
        val alreadyDownloaded = PrismSettings.getDownloadedDatasetRepoIds()

        val results = try {
            DatasetDiscoveryService.discoverAll(minSizeBytes = minBytes, maxSizeBytes = maxBytes)
        } catch (e: Exception) {
            PrismLogger.logError("DatasetDownload", "Discovery failed: ${e.message}", e)
            return Result.retry()
        }

        val candidates = results.filter { it.repoId !in alreadyDownloaded }
        if (candidates.isEmpty()) {
            PrismLogger.logInfo("DatasetDownload", "No new datasets found within the size cap.")
            return Result.success()
        }

        for (dataset in candidates) {
            val (files, bytes) = DatasetDownloader.download(dataset, maxBytes)
            if (files > 0) {
                PrismSettings.addDownloadedDatasetRepoId(dataset.repoId)
                PrismLogger.logInfo(
                    "DatasetDownload",
                    "Downloaded ${dataset.repoId}: $files file(s), " +
                        "${DatasetDiscoveryService.formatSize(bytes)} -- into Nora's/Aether's dataset director" +
                        "${if (files == 1) "y" else "ies"}."
                )
            }
        }
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "PrismDatasetAutoDownload"

        /**
         * (Re)schedules or cancels the cycle. Safe to call repeatedly -- on app start and
         * whenever the toggle or interval changes. [ExistingPeriodicWorkPolicy.UPDATE] applies a
         * changed interval immediately rather than waiting for the current period to elapse.
         */
        fun schedule(context: Context) {
            if (!PrismSettings.getDatasetAutoDownloadEnabled()) {
                WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
                return
            }

            val hours = PrismSettings.getDatasetAutoDownloadIntervalHours().toLong()
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED)
                .setRequiresBatteryNotLow(true)
                .build()

            val request = PeriodicWorkRequestBuilder<DatasetDownloadWorker>(hours, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }
    }
}
