package com.stash.core.data.sync

import android.content.Context
import com.stash.core.data.sync.workers.AvailabilityCheckWorker
import com.stash.core.data.sync.workers.AvailabilityRecheckWorker
import com.stash.core.data.sync.workers.ReleaseDownloadsWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin façade over the three streaming-engine WorkManager entry points
 * driven by [com.stash.core.data.repository.MusicRepository.applyStreamingMode].
 *
 * Why this exists: the worker companions all call
 * [androidx.work.WorkManager.getInstance], which is statically dispatched
 * and not mockable from a plain JVM test. The repository's orchestrator
 * is pure enough to unit-test if we route the three side effects through
 * an interface — the production binding forwards to the static
 * `enqueueSelf` / `schedulePeriodic` calls, while the test rig binds a
 * relaxed mockk in their place and asserts invocation counts.
 *
 * Mirrors the `enqueueAvailabilityCheck` test seam on
 * [com.stash.core.data.sync.workers.DiffWorker] — same problem, slightly
 * different solution because [com.stash.core.data.repository.MusicRepositoryImpl]
 * is a final class with multiple injected collaborators (subclassing for
 * a single seam would force the whole class `open`, which is overkill).
 */
interface StreamingWorkScheduler {
    /** Enqueue [AvailabilityCheckWorker] once — drains pending checks. */
    fun enqueueAvailabilityCheck()

    /** Schedule the weekly [AvailabilityRecheckWorker] (KEEP-policy, idempotent). */
    fun scheduleAvailabilityRecheckPeriodic()

    /** Enqueue [ReleaseDownloadsWorker] — bulk-removes downloaded files. */
    fun enqueueReleaseDownloads()
}

/** Default implementation — forwards to each worker's static enqueue helper. */
@Singleton
class DefaultStreamingWorkScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) : StreamingWorkScheduler {
    override fun enqueueAvailabilityCheck() {
        AvailabilityCheckWorker.enqueueSelf(context)
    }

    override fun scheduleAvailabilityRecheckPeriodic() {
        AvailabilityRecheckWorker.schedulePeriodic(context)
    }

    override fun enqueueReleaseDownloads() {
        ReleaseDownloadsWorker.enqueueSelf(context)
    }
}
