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
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.prefs.ReleaseDownloadsState
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Off→On "release the space" path. Triggered by the Task 17 streaming-mode
 * prompt when the user enables streaming and elects to reclaim disk space:
 * walks every `is_downloaded = 1` row, clears the row's download
 * bookkeeping, and deletes the file from disk.
 *
 * ## Ordering — DB write **before** file delete
 *
 * The opposite ordering from `MusicRepositoryImpl.deleteTrack`. There,
 * `deleteTrackFile()` runs first and the DAO clear runs second; if the
 * process dies in between, the next launch sees an `is_downloaded = 1`
 * row pointing at a missing file (the user notices: their library shows
 * the track but tapping it errors). For *this* worker the failure mode
 * we care about is different — we're bulk-clearing under user intent
 * and a partial run that leaves an orphaned file is fine (the existing
 * orphan-cleanup sweep picks it up). A row pointing at a deleted file
 * is NOT fine. So we flip the order: DB row cleared first, file deleted
 * second. A mid-op crash leaves a stale file, not a stale row.
 *
 * ## Resumability
 *
 * The worker paginates `tracks` by primary key ASC and stashes the
 * last fully-processed id in [ReleaseDownloadsState] after every batch.
 * On restart it picks up at `id > lastProcessedId` so cancellation,
 * process death, or WorkManager's 10-minute per-run cap don't restart
 * the scan from the top. The cursor is cleared only once a final
 * [TrackDao.downloadedCount] reads zero — until then we keep paging
 * across runs.
 *
 * ## File delete is best-effort
 *
 * Wrapped in [runCatching] so a SAF-revoked path, an unmounted SD card,
 * or an already-deleted file (resume after partial crash) doesn't fail
 * the worker. The DB row is already cleared by the time we attempt the
 * delete; an orphaned file gets reaped by the existing orphan sweeper.
 *
 * ## Cancellation
 *
 * [shouldStop] wraps `isStopped` so the test subclass can simulate
 * mid-batch cancellation. When WorkManager calls `onStopped`, we save
 * the cursor and return [Result.retry] so WorkManager re-runs us with
 * the exponential backoff policy below.
 */
@HiltWorker
open class ReleaseDownloadsWorker @AssistedInject constructor(
    @Assisted ctx: Context,
    @Assisted params: WorkerParameters,
    private val trackDao: TrackDao,
    private val releaseDownloadsState: ReleaseDownloadsState,
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val deadline = System.currentTimeMillis() + MAX_RUN_MS
        var lastId = releaseDownloadsState.lastProcessedId() ?: 0L
        var processed = 0
        while (!shouldStop() && System.currentTimeMillis() < deadline) {
            val batch = trackDao.downloadedAfter(lastId, BATCH_SIZE)
            if (batch.isEmpty()) break
            for (track in batch) {
                if (shouldStop()) {
                    releaseDownloadsState.setLastProcessedId(lastId)
                    Log.i(TAG, "ReleaseDownloads: stopped at id=$lastId, processed=$processed")
                    return Result.retry()
                }
                val path = track.filePath
                // DB write FIRST so a mid-op crash doesn't leave a row pointing at a deleted file.
                trackDao.markAsNotDownloaded(track.id)
                if (!path.isNullOrBlank()) {
                    deleteFile(path)
                }
                lastId = track.id
                processed++
                onTrackProcessed()
            }
            releaseDownloadsState.setLastProcessedId(lastId)
        }
        // Clear the cursor only if the table really is drained — a stopped-
        // at-batch-boundary run still has rows to process on the next pass.
        if (trackDao.downloadedCount() == 0) {
            releaseDownloadsState.clear()
            Log.i(TAG, "ReleaseDownloads: drained, processed=$processed this run")
        } else {
            Log.i(TAG, "ReleaseDownloads: paused at id=$lastId, processed=$processed this run")
        }
        return Result.success()
    }

    /**
     * File-delete seam. Production attempts the delete and swallows any
     * IOException via [runCatching] (see class kdoc — best-effort by
     * design). Tests override to assert delete calls without touching
     * the real filesystem.
     */
    protected open fun deleteFile(path: String): Boolean =
        runCatching { File(path).delete() }.getOrElse { false }

    /**
     * Test seam — invoked once per row after the DB write + file delete
     * pair completes. Production builds do nothing; the test subclass
     * uses it to flip [shouldStop] after N iterations.
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
        private const val TAG = "ReleaseDownloads"

        /** Unique work name — KEEP-policy so re-enqueues coalesce. */
        const val WORK_NAME = "release_downloads"

        /** Max rows pulled per `downloadedAfter` SELECT. */
        private const val BATCH_SIZE = 100

        /**
         * Soft per-run budget. WorkManager hard-caps at 10 minutes; we
         * exit a hair early so the final cursor write + status check
         * land cleanly instead of getting killed mid-statement.
         */
        private const val MAX_RUN_MS = 9L * 60_000

        /**
         * Enqueues a one-shot run. KEEP policy because a user who taps
         * the toggle twice doesn't want a second worker racing the
         * first — the in-flight one is already doing the work.
         */
        fun enqueueSelf(context: Context) {
            val request = OneTimeWorkRequestBuilder<ReleaseDownloadsWorker>()
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    60,
                    TimeUnit.SECONDS,
                )
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request,
            )
        }
    }
}
