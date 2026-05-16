package com.stash.core.data.sync.workers

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.streaming.StreamAvailabilityChecker
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

/**
 * Drains the `is_streamable_checked_at IS NULL` queue produced by sync —
 * for every undownloaded track Stash has seen via Spotify/YouTube but
 * never confirmed against the streaming provider, ask
 * [StreamAvailabilityChecker] whether Kennyy/Qobuz can serve audio for
 * it, then write the boolean + timestamp back via
 * [TrackDao.setStreamable].
 *
 * ## Scheduling
 *
 * One-time worker with self-re-enqueue, not periodic. Triggered by the
 * sync finalize step (after a diff produces new stub rows) and re-enqueues
 * itself if the queue isn't empty when the per-run deadline expires. The
 * one-shot pattern keeps WorkManager's quota happy when the queue is
 * already drained — no wakeup, no batch SQL — and the
 * `setBackoffCriteria(EXPONENTIAL, 60s)` policy means a Kennyy outage
 * decays cleanly instead of hammering the proxy every minute.
 *
 * ## Pacing
 *
 * 1-second delay between checks — Kennyy/Qobuz proxy has documented
 * rate limits and we'd rather a 1000-track library take 17 minutes
 * across a couple of worker runs than get every account-key 429'd in
 * the first batch.
 *
 * ## Failure model
 *
 * The checker is contractually non-throwing — its `runCatching` swallows
 * resolver exceptions and returns `false`. The worker therefore writes
 * `is_streamable = false, is_streamable_checked_at = now` for both the
 * "no match" and "transient failure" cases. Task 10's
 * `AvailabilityRecheckWorker` re-validates these rows ~30 days later;
 * before then, a row that genuinely came up as `false` won't bounce in
 * and out of the Library every sync.
 *
 * ## Cancellation
 *
 * [shouldStop] wraps `isStopped` for test override (mirrors
 * [LoudnessBackfillWorker]). A track caught mid-check when WorkManager
 * cancels is **not** marked checked — the row stays NULL and gets
 * re-picked on the next run. Idempotent.
 */
@HiltWorker
open class AvailabilityCheckWorker @AssistedInject constructor(
    @Assisted ctx: Context,
    @Assisted params: WorkerParameters,
    private val trackDao: TrackDao,
    private val checker: StreamAvailabilityChecker,
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val deadline = System.currentTimeMillis() + MAX_RUN_MS
        var anyProcessed = false
        while (true) {
            if (shouldStop() || System.currentTimeMillis() > deadline) break
            val batch = trackDao.tracksNeedingStreamableCheck(limit = BATCH_SIZE)
            if (batch.isEmpty()) break
            for (track in batch) {
                if (shouldStop() || System.currentTimeMillis() > deadline) break
                val available = checker.isAvailable(track)
                trackDao.setStreamable(
                    id = track.id,
                    available = available,
                    now = System.currentTimeMillis(),
                )
                anyProcessed = true
                onTrackProcessed()
                delay(THROTTLE_MS)
            }
        }
        if (!shouldStop() && trackDao.tracksNeedingStreamableCheckCount() > 0) {
            Log.i(TAG, "AvailabilityCheck: queue not empty after run, re-enqueuing")
            enqueueSelf(applicationContext)
        }
        return Result.success(workDataOf(KEY_PROCESSED to anyProcessed))
    }

    /**
     * Test seam — invoked once per row after [TrackDao.setStreamable]
     * writes. Production builds do nothing; the test subclass uses it
     * to flip [shouldStop] after N iterations.
     */
    protected open fun onTrackProcessed() {
        // no-op in production
    }

    /**
     * Cancellation predicate. Wraps WorkManager's `isStopped` (a `final`
     * accessor on [androidx.work.ListenableWorker]) so the test subclass
     * can simulate mid-batch cancellation.
     */
    protected open fun shouldStop(): Boolean = isStopped

    companion object {
        private const val TAG = "AvailabilityCheck"

        /** Unique work name — REPLACE-policy enqueue cancels any in-flight run. */
        const val WORK_NAME = "availability_check"

        /** Output-data key signalling whether at least one row was processed. */
        const val KEY_PROCESSED = "processed"

        /** Max rows checked per batch SELECT. */
        private const val BATCH_SIZE = 50

        /**
         * Soft per-run budget. WorkManager hard-caps at 10 minutes; we
         * exit a hair early so the re-enqueue check + status write lands
         * cleanly instead of getting killed mid-statement.
         */
        private const val MAX_RUN_MS = 9L * 60_000

        /**
         * Per-row delay between Kennyy lookups. See class kdoc — gentle
         * pacing keeps the public proxy from rate-limiting the whole
         * batch.
         */
        private const val THROTTLE_MS = 1_000L

        /**
         * Enqueues a one-shot run, replacing any existing instance.
         * Used both as the initial trigger from `SyncFinalizeWorker` and
         * as the self-loop when a run finishes with rows still pending.
         */
        fun enqueueSelf(context: Context) {
            val request = OneTimeWorkRequestBuilder<AvailabilityCheckWorker>()
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    60,
                    TimeUnit.SECONDS,
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }
}
