package com.example.englishreader.data.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.englishreader.EnglishReaderApp
import java.util.concurrent.TimeUnit

/** Schedules one durable network-constrained sync job at a time. */
class SyncScheduler(private val context: Context) {
    private val workManager = WorkManager.getInstance(context.applicationContext)
    private val connected = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun enqueueSoon() {
        workManager.enqueueUniqueWork(
            ONE_TIME_NAME,
            // If a reader turns a page while a sync worker is still active, queue
            // one follow-up run instead of silently dropping that fresh position.
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(connected).build(),
        )
    }

    fun ensurePeriodic() {
        workManager.enqueueUniquePeriodicWork(
            PERIODIC_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).setConstraints(connected).build(),
        )
    }

    fun cancelAll() {
        workManager.cancelUniqueWork(ONE_TIME_NAME)
        workManager.cancelUniqueWork(PERIODIC_NAME)
    }

    private companion object {
        const val ONE_TIME_NAME = "kreader-sync-now"
        const val PERIODIC_NAME = "kreader-sync-periodic"
    }
}

class SyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = when ((applicationContext as EnglishReaderApp).container.syncRepository.syncOnce()) {
        SyncRunResult.Success,
        SyncRunResult.NotConfigured,
        SyncRunResult.NotAuthenticated,
        is SyncRunResult.PermanentFailure,
        -> Result.success()
        is SyncRunResult.RetryableFailure -> Result.retry()
    }
}
