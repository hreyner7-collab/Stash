package com.stash.core.data.sync.workers

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.prefs.ReleaseDownloadsState
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MockK tests for [ReleaseDownloadsWorker] — the Off→On "release the
 * space" path. The worker pages downloaded rows by `id > lastId`,
 * clears the DB bookkeeping, deletes the file, and stashes a resume
 * cursor in [ReleaseDownloadsState] so a mid-batch cancellation picks
 * up where it left off on the next run.
 *
 * Pattern mirrors [AvailabilityCheckWorkerTest]: MockK for the DAO +
 * state seam, a thin subclass that overrides [shouldStop] /
 * [deleteFile] / [onTrackProcessed] to substitute for `isStopped`
 * (which is `final` on ListenableWorker) and to swap the real
 * filesystem out of the loop.
 */
class ReleaseDownloadsWorkerTest {

    private val appContext: Context = mockk(relaxed = true)
    private val workerParams: WorkerParameters = mockk(relaxed = true)
    private val trackDao: TrackDao = mockk(relaxed = true)
    private val state: ReleaseDownloadsState = mockk(relaxed = true)

    private fun downloadedStub(id: Long, path: String? = "/sdcard/track_$id.opus") =
        TrackEntity(
            id = id,
            title = "Track $id",
            artist = "Artist $id",
            filePath = path,
            isDownloaded = true,
            fileSizeBytes = 1_000_000L,
        )

    /**
     * Sets up [TrackDao.downloadedAfter] to return the [rows] split into
     * id-ordered batches. The worker treats `id > lastId` as the page
     * cursor so we filter on the captured `lastId` argument and take
     * up to `limit` rows per call.
     */
    private fun stubBatches(rows: List<TrackEntity>) {
        val sorted = rows.sortedBy { it.id }
        coEvery { trackDao.downloadedAfter(any(), any()) } answers {
            val lastId = firstArg<Long>()
            val limit = secondArg<Int>()
            sorted.asSequence()
                .filter { it.id > lastId }
                .take(limit)
                .toList()
        }
    }

    private fun newWorker(
        stoppedAfter: Int = Int.MAX_VALUE,
    ) = TestableReleaseDownloadsWorker(appContext, workerParams, trackDao, state, stoppedAfter)

    @Test fun `releaseProcessesAllDownloadedRows clearsDbAndDeletesFiles`() = runTest {
        val rows = (1L..5L).map { downloadedStub(it) }
        stubBatches(rows)
        coEvery { state.lastProcessedId() } returns null
        coEvery { state.setLastProcessedId(any()) } just Runs
        coEvery { state.clear() } just Runs
        coEvery { trackDao.markAsNotDownloaded(any()) } just Runs
        // After draining, downloadedCount must read zero so the worker clears
        // its cursor — that's the "really done" signal.
        coEvery { trackDao.downloadedCount() } returns 0

        val worker = newWorker()
        val result = worker.doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        coVerify(exactly = 1) { trackDao.markAsNotDownloaded(1L) }
        coVerify(exactly = 1) { trackDao.markAsNotDownloaded(2L) }
        coVerify(exactly = 1) { trackDao.markAsNotDownloaded(3L) }
        coVerify(exactly = 1) { trackDao.markAsNotDownloaded(4L) }
        coVerify(exactly = 1) { trackDao.markAsNotDownloaded(5L) }
        assertEquals(5, worker.deleteCount)
        coVerify(exactly = 1) { state.clear() }
    }

    @Test fun `resumeAfterCancellation picksUpFromLastProcessedId`() = runTest {
        val rows = (1L..5L).map { downloadedStub(it) }
        stubBatches(rows)
        // Cursor says ids 1,2 are already done — only 3,4,5 should be touched.
        coEvery { state.lastProcessedId() } returns 2L
        coEvery { state.setLastProcessedId(any()) } just Runs
        coEvery { state.clear() } just Runs
        coEvery { trackDao.markAsNotDownloaded(any()) } just Runs
        coEvery { trackDao.downloadedCount() } returns 0

        val worker = newWorker()
        worker.doWork()

        assertEquals("only 3,4,5 should hit the filesystem", 3, worker.deleteCount)
        coVerify(exactly = 0) { trackDao.markAsNotDownloaded(1L) }
        coVerify(exactly = 0) { trackDao.markAsNotDownloaded(2L) }
        coVerify(exactly = 1) { trackDao.markAsNotDownloaded(3L) }
        coVerify(exactly = 1) { trackDao.markAsNotDownloaded(4L) }
        coVerify(exactly = 1) { trackDao.markAsNotDownloaded(5L) }
    }

    @Test fun `cancellation persistsLastProcessedId`() = runTest {
        val rows = (1L..10L).map { downloadedStub(it) }
        stubBatches(rows)
        coEvery { state.lastProcessedId() } returns null
        coEvery { trackDao.markAsNotDownloaded(any()) } just Runs
        // Capture the cursor writes so we can assert the last one matches
        // where the worker actually stopped.
        val cursorSlots = mutableListOf<Long>()
        coEvery { state.setLastProcessedId(any()) } answers {
            cursorSlots.add(firstArg())
        }
        coEvery { state.clear() } just Runs
        // After cancellation, the table is NOT empty (rows still left).
        coEvery { trackDao.downloadedCount() } returns 7

        // Stop after 3 successful row writes — the worker should save id=3
        // and return retry.
        val worker = newWorker(stoppedAfter = 3)
        val result = worker.doWork()

        assertTrue("cancelled mid-batch -> Result.retry", result is ListenableWorker.Result.Retry)
        assertTrue("cursor must have been written at least once", cursorSlots.isNotEmpty())
        assertEquals(
            "last persisted cursor must be the id we stopped at",
            3L, cursorSlots.last(),
        )
        // And we must NOT have cleared the cursor — the early-return path
        // skips the drained-table check.
        coVerify(exactly = 0) { state.clear() }
    }

    @Test fun `completion clearsLastProcessedId`() = runTest {
        val rows = (1L..5L).map { downloadedStub(it) }
        stubBatches(rows)
        coEvery { state.lastProcessedId() } returns null
        coEvery { state.setLastProcessedId(any()) } just Runs
        coEvery { state.clear() } just Runs
        coEvery { trackDao.markAsNotDownloaded(any()) } just Runs
        coEvery { trackDao.downloadedCount() } returns 0

        newWorker().doWork()

        coVerify(exactly = 1) { state.clear() }
    }

    @Test fun `emptyQueue returnsSuccessWithoutTouchingRows`() = runTest {
        stubBatches(emptyList())
        coEvery { state.lastProcessedId() } returns null
        coEvery { state.clear() } just Runs
        coEvery { trackDao.downloadedCount() } returns 0

        val worker = newWorker()
        val result = worker.doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals(0, worker.deleteCount)
        coVerify(exactly = 0) { trackDao.markAsNotDownloaded(any()) }
        // Cleanup clear is fine — table is genuinely empty.
        coVerify(exactly = 1) { state.clear() }
    }

    @Test fun `nullOrBlankFilePath skipsDeleteButStillClearsRow`() = runTest {
        // A row that lost its file_path somehow (DB inconsistency, legacy
        // import) still needs is_downloaded = 0; we just don't try to
        // delete `null`.
        val rows = listOf(
            downloadedStub(1L, path = null),
            downloadedStub(2L, path = ""),
            downloadedStub(3L, path = "/sdcard/track_3.opus"),
        )
        stubBatches(rows)
        coEvery { state.lastProcessedId() } returns null
        coEvery { state.setLastProcessedId(any()) } just Runs
        coEvery { state.clear() } just Runs
        coEvery { trackDao.markAsNotDownloaded(any()) } just Runs
        coEvery { trackDao.downloadedCount() } returns 0

        val worker = newWorker()
        worker.doWork()

        coVerify(exactly = 1) { trackDao.markAsNotDownloaded(1L) }
        coVerify(exactly = 1) { trackDao.markAsNotDownloaded(2L) }
        coVerify(exactly = 1) { trackDao.markAsNotDownloaded(3L) }
        assertEquals("only the row with a real path should hit the FS seam", 1, worker.deleteCount)
    }

    @Test fun `dbWriteHappensBeforeFileDelete`() = runTest {
        // Crash-safety invariant: a mid-op death must leave an orphaned
        // file (cleaned up later) rather than a row whose file_path
        // points at a deleted file. So markAsNotDownloaded MUST be called
        // before deleteFile for the same id.
        val rows = listOf(downloadedStub(42L))
        stubBatches(rows)
        coEvery { state.lastProcessedId() } returns null
        coEvery { state.setLastProcessedId(any()) } just Runs
        coEvery { state.clear() } just Runs
        coEvery { trackDao.downloadedCount() } returns 0

        val worker = newWorker()
        // Wire the DB mock to append into the worker's shared opLog —
        // the worker's overridden deleteFile appends "fs:<id>" so the
        // resulting sequence proves ordering.
        coEvery { trackDao.markAsNotDownloaded(any()) } answers {
            worker.opLog.add("db:${firstArg<Long>()}")
        }

        worker.doWork()

        assertEquals(
            "DB write must precede file delete for crash-safety",
            listOf("db:42", "fs:42"),
            worker.opLog,
        )
    }

    @Test fun `tableStillHasRowsAfterRun keepsCursor`() = runTest {
        // If downloadedCount > 0 at finalize time (e.g. concurrent writer
        // re-marked a row as downloaded between our scan and our recount),
        // we MUST keep the cursor so the next run picks up cleanly instead
        // of starting from id 0 and re-touching rows we already processed.
        val rows = (1L..3L).map { downloadedStub(it) }
        stubBatches(rows)
        coEvery { state.lastProcessedId() } returns null
        coEvery { state.setLastProcessedId(any()) } just Runs
        coEvery { state.clear() } just Runs
        coEvery { trackDao.markAsNotDownloaded(any()) } just Runs
        coEvery { trackDao.downloadedCount() } returns 1  // a new row landed

        newWorker().doWork()

        coVerify(exactly = 0) { state.clear() }
    }

    @Test fun `deleteFileFailure doesNotAbortBatch`() = runTest {
        // A SAF-revoked path, an unmounted SD card, or an already-gone
        // file (resume after partial crash) shouldn't fail the worker.
        // The DB row is cleared before the delete attempt, so an orphaned
        // file is the worst case — the orphan sweeper picks it up.
        val rows = (1L..3L).map { downloadedStub(it) }
        stubBatches(rows)
        coEvery { state.lastProcessedId() } returns null
        coEvery { state.setLastProcessedId(any()) } just Runs
        coEvery { state.clear() } just Runs
        coEvery { trackDao.markAsNotDownloaded(any()) } just Runs
        coEvery { trackDao.downloadedCount() } returns 0

        val worker = TestableReleaseDownloadsWorker(
            appContext, workerParams, trackDao, state,
            stoppedAfter = Int.MAX_VALUE,
            deleteResult = false,  // every delete attempt "fails"
        )
        val result = worker.doWork()

        assertTrue(
            "delete failures must NOT bubble up — they're best-effort",
            result is ListenableWorker.Result.Success,
        )
        coVerify(exactly = 3) { trackDao.markAsNotDownloaded(any()) }
    }

    @Test fun `cursorAdvancesPerBatch survivesProcessDeathBetweenBatches`() = runTest {
        // Mid-run process death between batches: the cursor must point
        // at the last id of the last completed batch so the next run
        // picks up at the right spot. We verify by checking that the
        // per-batch cursor write fires with the correct trailing id.
        val rows = (1L..3L).map { downloadedStub(it) }
        stubBatches(rows)
        coEvery { state.lastProcessedId() } returns null
        val cursorWrites = mutableListOf<Long>()
        coEvery { state.setLastProcessedId(any()) } answers {
            cursorWrites.add(firstArg())
        }
        coEvery { state.clear() } just Runs
        coEvery { trackDao.markAsNotDownloaded(any()) } just Runs
        coEvery { trackDao.downloadedCount() } returns 0

        newWorker().doWork()

        // The per-batch cursor write must have run at least once and
        // the last value must be the highest id we processed.
        assertTrue("cursor must be persisted at least once", cursorWrites.isNotEmpty())
        assertEquals(3L, cursorWrites.last())
    }

    /**
     * Thin subclass that swaps the filesystem out of [deleteFile] (so
     * tests don't need real paths), counts deletes, records the
     * DB/fs op sequence in [opLog], and flips [shouldStop] after
     * [stoppedAfter] processed-row callbacks fire.
     *
     * `onTrackProcessed` is invoked at the END of each row in the
     * production loop — after both the DB write and the file delete
     * land — so the counter ticks AFTER row N completes, and
     * `shouldStop` then trips before row N+1's DB write starts. That's
     * the WorkManager cancellation timing we want to simulate.
     */
    private class TestableReleaseDownloadsWorker(
        ctx: Context,
        params: WorkerParameters,
        trackDao: TrackDao,
        state: ReleaseDownloadsState,
        private val stoppedAfter: Int,
        private val deleteResult: Boolean = true,
    ) : ReleaseDownloadsWorker(ctx, params, trackDao, state) {
        var deleteCount: Int = 0
            private set
        val opLog: MutableList<String> = mutableListOf()
        private var processed = 0

        override fun deleteFile(path: String): Boolean {
            deleteCount++
            val id = path.substringAfterLast('_').substringBefore('.')
            opLog.add("fs:$id")
            return deleteResult
        }

        override fun onTrackProcessed() {
            processed++
        }

        override fun shouldStop(): Boolean = processed >= stoppedAfter
    }
}
