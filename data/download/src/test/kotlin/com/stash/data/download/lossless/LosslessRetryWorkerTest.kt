package com.stash.data.download.lossless

import android.content.Context
import androidx.work.WorkerParameters
import com.stash.core.data.db.dao.DownloadQueueDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.DownloadQueueEntity
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.model.DownloadStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v0.9.17 strict-FLAC: tests the one-shot sweep worker that re-resolves
 * [DownloadStatus.WAITING_FOR_LOSSLESS] rows.
 *
 * Behavior under test:
 *  - Resolved row → row flipped to [DownloadStatus.PENDING] so the
 *    standard download chain picks it up.
 *  - Still-null row → no DB write; row stays in WAITING_FOR_LOSSLESS for
 *    the next trigger.
 *  - Empty deferred set → success, no DB writes.
 *
 * The worker does not download — it only re-resolves and re-queues.
 *
 * Mocks the constructor deps directly (no Hilt), since [LosslessRetryWorker]
 * is a plain class that can be instantiated with stub [WorkerParameters].
 * MockK matches the precedent set by [DownloadManagerDeferTest].
 */
class LosslessRetryWorkerTest {

    private val appContext: Context = mockk(relaxed = true)
    private val workerParams: WorkerParameters = mockk(relaxed = true)
    private val downloadQueueDao: DownloadQueueDao = mockk(relaxed = true)
    private val trackDao: TrackDao = mockk(relaxed = true)
    private val registry: LosslessSourceRegistry = mockk()

    private fun newWorker(): LosslessRetryWorker = LosslessRetryWorker(
        appContext = appContext,
        params = workerParams,
        downloadQueueDao = downloadQueueDao,
        trackDao = trackDao,
        registry = registry,
    )

    @Test
    fun `resolved row flips to PENDING`() = runTest {
        coEvery { downloadQueueDao.waitingForLosslessTracks() } returns listOf(
            entry(id = 100L, trackId = 1L),
        )
        coEvery { trackDao.getById(1L) } returns stubTrackEntity(1L)
        coEvery { registry.resolve(any()) } returns stubSourceResult()

        val result = newWorker().doWork()

        assertEquals(androidx.work.ListenableWorker.Result.success(), result)
        coVerify(exactly = 1) {
            downloadQueueDao.updateStatus(
                id = 100L,
                status = DownloadStatus.PENDING,
            )
        }
    }

    @Test
    fun `still-null row stays WAITING_FOR_LOSSLESS`() = runTest {
        coEvery { downloadQueueDao.waitingForLosslessTracks() } returns listOf(
            entry(id = 100L, trackId = 1L),
        )
        coEvery { trackDao.getById(1L) } returns stubTrackEntity(1L)
        coEvery { registry.resolve(any()) } returns null

        newWorker().doWork()

        coVerify(exactly = 0) {
            downloadQueueDao.updateStatus(any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `empty deferred set returns success without DB writes`() = runTest {
        coEvery { downloadQueueDao.waitingForLosslessTracks() } returns emptyList()

        val result = newWorker().doWork()

        assertEquals(androidx.work.ListenableWorker.Result.success(), result)
        coVerify(exactly = 0) {
            downloadQueueDao.updateStatus(any(), any(), any(), any(), any(), any())
        }
    }

    private fun entry(id: Long, trackId: Long) = DownloadQueueEntity(
        id = id,
        trackId = trackId,
        status = DownloadStatus.WAITING_FOR_LOSSLESS,
        searchQuery = "test query",
    )

    private fun stubTrackEntity(id: Long) = TrackEntity(
        id = id,
        title = "Track $id",
        artist = "Artist $id",
        album = "Album $id",
        durationMs = 200_000L,
        canonicalTitle = "track $id",
        canonicalArtist = "artist $id",
        isrc = "USRC1700000$id",
    )

    private fun stubSourceResult(): SourceResult = SourceResult(
        sourceId = "squid_qobuz",
        downloadUrl = "https://example.test/file.flac",
        format = AudioFormat(codec = "flac", bitrateKbps = 0),
        confidence = 0.99f,
    )
}
