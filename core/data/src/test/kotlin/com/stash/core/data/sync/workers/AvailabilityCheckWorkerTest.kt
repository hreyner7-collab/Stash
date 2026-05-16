package com.stash.core.data.sync.workers

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.streaming.StreamAvailabilityChecker
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MockK tests for [AvailabilityCheckWorker] — drains the
 * `is_streamable_checked_at IS NULL AND file_path IS NULL` queue,
 * delegating per-row to [StreamAvailabilityChecker] (which itself wraps
 * the Kennyy/Qobuz resolver and folds any failure into `false`).
 *
 * Mirrors [LoudnessBackfillWorkerTest]'s constructor-injection +
 * mocked WorkerParameters pattern. The `isStopped` flag is package-private
 * on CoroutineWorker so a thin subclass overrides [shouldStop] for the
 * cancellation hook.
 *
 * Re-enqueue is suppressed by stubbing
 * [TrackDao.tracksNeedingStreamableCheckCount] to return 0 — that
 * avoids the static `WorkManager.getInstance(ctx)` call inside
 * [AvailabilityCheckWorker.Companion.enqueueSelf] which is not
 * mockable in plain JVM tests.
 */
class AvailabilityCheckWorkerTest {

    private val appContext: Context = mockk(relaxed = true)
    private val workerParams: WorkerParameters = mockk(relaxed = true)
    private val trackDao: TrackDao = mockk(relaxed = true)
    private val checker: StreamAvailabilityChecker = mockk()

    private fun stubTrack(id: Long) = TrackEntity(
        id = id,
        title = "Track $id",
        artist = "Artist $id",
        filePath = null,
    )

    private fun newWorker(stoppedAfter: Int = Int.MAX_VALUE) =
        TestableAvailabilityCheckWorker(
            appContext, workerParams,
            trackDao, checker,
            stoppedAfter,
        )

    @Test fun `emptyQueue returnsSuccessWithoutEnqueuingFollowUp`() = runTest {
        coEvery { trackDao.tracksNeedingStreamableCheck(any()) } returns emptyList()
        coEvery { trackDao.tracksNeedingStreamableCheckCount() } returns 0

        val result = newWorker().doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        val processed = (result as ListenableWorker.Result.Success).outputData
            .getBoolean(AvailabilityCheckWorker.KEY_PROCESSED, true)
        assertFalse("expected KEY_PROCESSED=false on empty queue", processed)
        coVerify(exactly = 0) { checker.isAvailable(any()) }
        coVerify(exactly = 0) { trackDao.setStreamable(any(), any(), any()) }
    }

    @Test fun `batchOfThree writesIsStreamableForEach`() = runTest {
        val tracks = (1L..3L).map { stubTrack(it) }
        // First call returns the batch; second call (next loop iteration)
        // returns empty so we exit cleanly instead of re-querying forever.
        coEvery { trackDao.tracksNeedingStreamableCheck(any()) } returnsMany listOf(
            tracks, emptyList(),
        )
        coEvery { trackDao.tracksNeedingStreamableCheckCount() } returns 0
        coEvery { checker.isAvailable(any()) } returns true
        coEvery { trackDao.setStreamable(any(), any(), any()) } just Runs

        val result = newWorker().doWork()

        assertEquals(
            ListenableWorker.Result.success(
                androidx.work.workDataOf(AvailabilityCheckWorker.KEY_PROCESSED to true),
            ),
            result,
        )
        coVerify(exactly = 3) {
            trackDao.setStreamable(id = any(), available = true, now = any())
        }
    }

    @Test fun `kennyyReturnsNull writesIsStreamableFalse`() = runTest {
        // The checker contract: null-from-resolver and throw-from-resolver both
        // collapse to `false` inside the impl, so this test exercises the
        // happy "no match" branch — checker.isAvailable returns false.
        val tracks = listOf(stubTrack(1L), stubTrack(2L))
        coEvery { trackDao.tracksNeedingStreamableCheck(any()) } returnsMany listOf(
            tracks, emptyList(),
        )
        coEvery { trackDao.tracksNeedingStreamableCheckCount() } returns 0
        coEvery { checker.isAvailable(any()) } returns false
        coEvery { trackDao.setStreamable(any(), any(), any()) } just Runs

        newWorker().doWork()

        coVerify(exactly = 2) {
            trackDao.setStreamable(id = any(), available = false, now = any())
        }
    }

    @Test fun `checkerReturnsFalse writesIsStreamableFalseAndChecked`() = runTest {
        // Mirror of the previous test framed as the "transient-failure swallowed
        // by runCatching" branch — from the worker's perspective these are
        // indistinguishable. The point: a false response still gets the row
        // stamped with is_streamable_checked_at (via the setStreamable update),
        // so the worker doesn't loop on it.
        val track = stubTrack(42L)
        coEvery { trackDao.tracksNeedingStreamableCheck(any()) } returnsMany listOf(
            listOf(track), emptyList(),
        )
        coEvery { trackDao.tracksNeedingStreamableCheckCount() } returns 0
        coEvery { checker.isAvailable(track) } returns false
        coEvery { trackDao.setStreamable(any(), any(), any()) } just Runs

        newWorker().doWork()

        // Single setStreamable call, both columns updated (available=false + now stamp).
        coVerify(exactly = 1) {
            trackDao.setStreamable(id = 42L, available = false, now = any())
        }
        // And isAvailable was actually invoked — guard against an accidental
        // short-circuit that would leave the row un-checked.
        coVerify(exactly = 1) { checker.isAvailable(track) }
    }

    /**
     * Thin subclass mirroring [LoudnessBackfillWorker]'s test seam pattern.
     * `isStopped` is `final` on ListenableWorker and can't be overridden;
     * the production worker routes its cancellation check through
     * [AvailabilityCheckWorker.shouldStop] and bumps a per-track counter
     * via [AvailabilityCheckWorker.onTrackProcessed] so the test can flip
     * the simulated stopped flag between tracks.
     */
    private class TestableAvailabilityCheckWorker(
        ctx: Context,
        params: WorkerParameters,
        trackDao: TrackDao,
        checker: StreamAvailabilityChecker,
        private val stoppedAfter: Int,
    ) : AvailabilityCheckWorker(ctx, params, trackDao, checker) {
        private var processed = 0

        override fun onTrackProcessed() {
            processed++
        }

        override fun shouldStop(): Boolean = processed >= stoppedAfter
    }
}
