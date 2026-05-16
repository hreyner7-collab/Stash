package com.stash.core.data.sync.workers

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.stash.core.data.db.dao.TrackDao
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * Periodic counter-pull to [AvailabilityCheckWorker]'s push. Resets
 * `is_streamable_checked_at = NULL` on every row whose last check is
 * older than 30 days, then triggers the one-shot
 * [AvailabilityCheckWorker] to drain the freshly-NULL queue against
 * Kennyy/Qobuz.
 *
 * ## Why this exists
 *
 * The Kennyy/Qobuz catalog churns continuously — operator delistings
 * remove rows that were previously streamable, and new releases (or
 * fresh ingests) start matching tracks that came up as "no" the first
 * time we asked. Without a re-check pass, the boolean we stamped on
 * day one becomes increasingly stale: the user sees library tracks
 * marked "stream-only" that no longer stream, and never sees newly-
 * available stream tracks that came online after sync.
 *
 * ## Scheduling
 *
 * Weekly periodic (no constraints — the work is one cheap UPDATE
 * followed by at most a single WorkManager enqueue). 30-day staleness
 * cutoff inside a 7-day cadence means a given row is re-checked
 * roughly every 4-5 weeks; longer than the cutoff so we don't churn
 * the same rows every Sunday, short enough that the long tail catches
 * catalog moves within the same monthly billing window most users
 * notice them.
 *
 * ## Cost ceiling
 *
 * The bulk UPDATE is a single statement; even on a 50k-track library
 * it's milliseconds. The re-enqueue path then hands off to
 * [AvailabilityCheckWorker] which has its own rate-limiting / batch
 * sizing — so the periodic schedule here is bounded entirely by the
 * downstream worker's pacing, not by anything we do in [doWork].
 */
@HiltWorker
open class AvailabilityRecheckWorker @AssistedInject constructor(
    @Assisted ctx: Context,
    @Assisted params: WorkerParameters,
    private val trackDao: TrackDao,
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val cutoff = System.currentTimeMillis() - RECHECK_AGE_MS
        val invalidated = trackDao.invalidateOldStreamableChecks(cutoff)
        Log.i(TAG, "AvailabilityRecheck: invalidated $invalidated stale row(s)")
        if (invalidated > 0) {
            enqueueCheck()
        }
        return Result.success()
    }

    /**
     * Test seam — invoked only when the bulk invalidate touched at least
     * one row. Production calls [AvailabilityCheckWorker.enqueueSelf];
     * the test subclass swaps in a counter so we don't need to mock the
     * static [WorkManager.getInstance] (which is `final` and unreachable
     * from plain JVM tests).
     */
    protected open fun enqueueCheck() {
        AvailabilityCheckWorker.enqueueSelf(applicationContext)
    }

    companion object {
        private const val TAG = "AvailabilityRecheck"

        /** Unique work name — KEEP-policy schedule de-dupes across cold starts. */
        const val WORK_NAME = "availability_recheck"

        /**
         * Staleness window. 30 days picks up monthly catalog churn while
         * leaving fresh checks alone — paired with the 7-day cadence
         * below, a given row is re-checked roughly every 4-5 weeks.
         */
        private const val RECHECK_AGE_MS = 30L * 24 * 3600 * 1000

        /**
         * Schedules the weekly recheck. Idempotent — uses
         * [ExistingPeriodicWorkPolicy.KEEP] so re-calls (e.g. on every
         * cold start from `StashApplication.onCreate`) don't reset the
         * next-run timer.
         */
        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<AvailabilityRecheckWorker>(
                repeatInterval = 7,
                repeatIntervalTimeUnit = TimeUnit.DAYS,
            ).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
