package com.stash.core.data.onedrive

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first

/**
 * Periodic OneDrive sync (the "sync every day / 7 days / month" setting).
 * The worker itself is tiny: it verifies the schedule is still wanted and
 * hands off to [OneDriveSyncService], whose foreground notification keeps
 * the long pass alive — WorkManager's own execution window is too short
 * for a multi-hour upload, but it's the right alarm clock.
 */
@HiltWorker
class OneDriveScheduledSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val authStore: OneDriveAuthStore,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!authStore.isConnected()) return Result.success()
        if (authStore.syncScheduleDays.first() <= 0) return Result.success()
        OneDriveSyncService.start(applicationContext)
        return Result.success()
    }

    companion object {
        private const val UNIQUE_NAME = "onedrive_scheduled_sync"

        /** (Re)programs the periodic schedule; 0 cancels it. */
        fun reschedule(context: Context, days: Int) {
            val wm = WorkManager.getInstance(context)
            if (days <= 0) {
                wm.cancelUniqueWork(UNIQUE_NAME)
                return
            }
            val request = PeriodicWorkRequestBuilder<OneDriveScheduledSyncWorker>(
                days.toLong(),
                TimeUnit.DAYS,
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()
            wm.enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                // UPDATE keeps the existing cadence anchor when the user
                // re-selects the same value; a changed interval reprograms.
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }
    }
}
