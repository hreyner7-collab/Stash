package com.stash.core.data.sync.workers

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.stash.core.data.db.dao.TrackDao
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MockK tests for [AvailabilityRecheckWorker] — the periodic counter-pull
 * to [AvailabilityCheckWorker]'s push. Every 7 days this worker flips
 * `is_streamable_checked_at` back to NULL on rows older than 30 days,
 * then re-enqueues the one-shot check worker to drain the new queue.
 *
 * The "did we enqueue the check worker" assertion has to dodge
 * [androidx.work.WorkManager.getInstance], which is a `final` static
 * accessor that can't be intercepted from plain JVM tests. The worker
 * therefore routes the enqueue call through a [protected open]
 * `enqueueCheck()` seam (same pattern as [AvailabilityCheckWorker]'s
 * `onTrackProcessed` / `shouldStop` overrides); the test subclass below
 * overrides it to count calls without touching WorkManager statics.
 */
class AvailabilityRecheckWorkerTest {

    private val appContext: Context = mockk(relaxed = true)
    private val workerParams: WorkerParameters = mockk(relaxed = true)
    private val trackDao: TrackDao = mockk(relaxed = true)

    private fun newWorker() =
        TestableAvailabilityRecheckWorker(appContext, workerParams, trackDao)

    @Test fun `noOldRows returnsSuccessWithoutEnqueueingCheckWorker`() = runTest {
        coEvery { trackDao.invalidateOldStreamableChecks(any()) } returns 0

        val worker = newWorker()
        val result = worker.doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals(
            "no rows aged past the 30-day cutoff -> check worker stays asleep",
            0, worker.enqueueCount,
        )
    }

    @Test fun `oldRows invalidatesAndEnqueuesCheckWorker`() = runTest {
        coEvery { trackDao.invalidateOldStreamableChecks(any()) } returns 17

        val worker = newWorker()
        val result = worker.doWork()

        assertTrue(result is ListenableWorker.Result.Success)
        assertEquals(
            "17 stale rows invalidated -> recheck must wake the check worker",
            1, worker.enqueueCount,
        )
    }

    @Test fun `cutoffPassedToDao isThirtyDaysBeforeNow`() = runTest {
        // Verify the cutoff arithmetic — the DAO must receive a timestamp
        // that's exactly 30 days before "now" so today's freshly-checked
        // rows aren't accidentally invalidated.
        val cutoffSlot = slot<Long>()
        coEvery { trackDao.invalidateOldStreamableChecks(capture(cutoffSlot)) } returns 0

        val before = System.currentTimeMillis()
        newWorker().doWork()
        val after = System.currentTimeMillis()

        val thirtyDaysMs = 30L * 24 * 3600 * 1000
        val captured = cutoffSlot.captured
        assertTrue(
            "cutoff $captured must sit in [before-30d, after-30d]",
            captured in (before - thirtyDaysMs)..(after - thirtyDaysMs),
        )
    }

    @Test fun `daoInvalidateCalledExactlyOnce`() = runTest {
        // Guard against a refactor that turns the bulk UPDATE into a
        // batched loop. The whole point of this worker is one cheap
        // SQL statement, not a row-by-row sweep.
        coEvery { trackDao.invalidateOldStreamableChecks(any()) } returns 5

        newWorker().doWork()

        coVerify(exactly = 1) { trackDao.invalidateOldStreamableChecks(any()) }
    }

    /**
     * Thin subclass mirroring [AvailabilityCheckWorker]'s test-seam pattern.
     * Overrides [AvailabilityRecheckWorker.enqueueCheck] to count invocations
     * instead of dispatching to [androidx.work.WorkManager], which would
     * NPE in a plain JVM test environment.
     */
    private class TestableAvailabilityRecheckWorker(
        ctx: Context,
        params: WorkerParameters,
        trackDao: TrackDao,
    ) : AvailabilityRecheckWorker(ctx, params, trackDao) {
        var enqueueCount: Int = 0
            private set

        override fun enqueueCheck() {
            enqueueCount++
        }
    }
}
