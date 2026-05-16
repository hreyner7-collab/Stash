package com.stash.core.data.repository

import android.content.Context
import com.stash.core.data.db.dao.DiscoveryQueueDao
import com.stash.core.data.db.dao.DownloadQueueDao
import com.stash.core.data.db.dao.PlaylistDao
import com.stash.core.data.db.dao.SyncHistoryDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.DownloadQueueEntity
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.prefs.StreamingPreference
import com.stash.core.data.sync.StreamingWorkScheduler
import com.stash.core.model.MusicSource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [MusicRepositoryImpl.applyStreamingMode] (Task 15) and
 * the [MusicRepositoryImpl] half of [MusicRepository.onEntitlementLost].
 *
 * The three streaming workers all dispatch via the
 * [com.stash.core.data.sync.StreamingWorkScheduler] seam — production
 * forwards to each worker's static `enqueueSelf` / `schedulePeriodic`,
 * tests bind a relaxed mockk so we can assert invocations without
 * touching the WorkManager static.
 */
class MusicRepositoryStreamingModeTest {

    @Test
    fun `applyStreamingMode enabledTrue sets preference and schedules workers`() = runTest {
        val streamingPreference = mockk<StreamingPreference>(relaxed = true)
        val scheduler = mockk<StreamingWorkScheduler>(relaxed = true)

        val repo = buildRepo(
            streamingPreference = streamingPreference,
            streamingWorkScheduler = scheduler,
        )

        repo.applyStreamingMode(enabled = true)

        coVerify(exactly = 1) { streamingPreference.setEnabled(true) }
        verify(exactly = 1) { scheduler.scheduleAvailabilityRecheckPeriodic() }
        verify(exactly = 1) { scheduler.enqueueAvailabilityCheck() }
        // releaseDownloads defaults false → not enqueued.
        verify(exactly = 0) { scheduler.enqueueReleaseDownloads() }
    }

    @Test
    fun `applyStreamingMode enabledTrue with releaseDownloads enqueues release worker`() = runTest {
        val streamingPreference = mockk<StreamingPreference>(relaxed = true)
        val scheduler = mockk<StreamingWorkScheduler>(relaxed = true)

        val repo = buildRepo(
            streamingPreference = streamingPreference,
            streamingWorkScheduler = scheduler,
        )

        repo.applyStreamingMode(enabled = true, releaseDownloads = true)

        coVerify(exactly = 1) { streamingPreference.setEnabled(true) }
        verify(exactly = 1) { scheduler.scheduleAvailabilityRecheckPeriodic() }
        verify(exactly = 1) { scheduler.enqueueAvailabilityCheck() }
        verify(exactly = 1) { scheduler.enqueueReleaseDownloads() }
    }

    @Test
    fun `applyStreamingMode enabledFalse clears preference and does not enqueue availability`() = runTest {
        val streamingPreference = mockk<StreamingPreference>(relaxed = true)
        val scheduler = mockk<StreamingWorkScheduler>(relaxed = true)
        val trackDao = mockk<TrackDao>(relaxed = true)
        val downloadQueueDao = mockk<DownloadQueueDao>(relaxed = true)

        val repo = buildRepo(
            streamingPreference = streamingPreference,
            streamingWorkScheduler = scheduler,
            trackDao = trackDao,
            downloadQueueDao = downloadQueueDao,
        )

        repo.applyStreamingMode(enabled = false)

        coVerify(exactly = 1) { streamingPreference.setEnabled(false) }
        // Disable path never touches the workers — periodic recheck is
        // intentionally left running, but we don't *re-*schedule it.
        verify(exactly = 0) { scheduler.scheduleAvailabilityRecheckPeriodic() }
        verify(exactly = 0) { scheduler.enqueueAvailabilityCheck() }
        verify(exactly = 0) { scheduler.enqueueReleaseDownloads() }
        // And without downloadAllStreamable, no DAO snapshot + bulk insert.
        coVerify(exactly = 0) { trackDao.streamableOnlyTracks() }
        coVerify(exactly = 0) { downloadQueueDao.insert(any()) }
    }

    @Test
    fun `applyStreamingMode enabledFalse with downloadAllStreamable inserts into download queue`() = runTest {
        val streamingPreference = mockk<StreamingPreference>(relaxed = true)
        val scheduler = mockk<StreamingWorkScheduler>(relaxed = true)
        val trackDao = mockk<TrackDao>(relaxed = true)
        val downloadQueueDao = mockk<DownloadQueueDao>(relaxed = true)

        val rows = listOf(
            track(id = 1L, title = "Alpha", artist = "Foo", ytId = "vid_a"),
            track(id = 2L, title = "Beta", artist = "Bar", ytId = null),
        )
        coEvery { trackDao.streamableOnlyTracks() } returns rows

        val captured = mutableListOf<DownloadQueueEntity>()
        val slot = slot<DownloadQueueEntity>()
        coEvery { downloadQueueDao.insert(capture(slot)) } answers {
            captured.add(slot.captured)
            captured.size.toLong()
        }

        val repo = buildRepo(
            streamingPreference = streamingPreference,
            streamingWorkScheduler = scheduler,
            trackDao = trackDao,
            downloadQueueDao = downloadQueueDao,
        )

        repo.applyStreamingMode(enabled = false, downloadAllStreamable = true)

        coVerify(exactly = 1) { streamingPreference.setEnabled(false) }
        coVerify(exactly = 1) { trackDao.streamableOnlyTracks() }
        assertEquals(2, captured.size)

        val first = captured[0]
        assertEquals(1L, first.trackId)
        assertEquals(null, first.syncId)
        assertEquals("Foo - Alpha", first.searchQuery)
        assertEquals("https://music.youtube.com/watch?v=vid_a", first.youtubeUrl)

        val second = captured[1]
        assertEquals(2L, second.trackId)
        assertEquals(null, second.syncId)
        assertEquals("Bar - Beta", second.searchQuery)
        // No youtubeId → no URL synthesized.
        assertEquals(null, second.youtubeUrl)
    }

    @Test
    fun `onEntitlementLost is equivalent to applyStreamingMode false defaults`() = runTest {
        val streamingPreference = mockk<StreamingPreference>(relaxed = true)
        val scheduler = mockk<StreamingWorkScheduler>(relaxed = true)
        val trackDao = mockk<TrackDao>(relaxed = true)
        val downloadQueueDao = mockk<DownloadQueueDao>(relaxed = true)

        val repo = buildRepo(
            streamingPreference = streamingPreference,
            streamingWorkScheduler = scheduler,
            trackDao = trackDao,
            downloadQueueDao = downloadQueueDao,
        )

        repo.onEntitlementLost()

        coVerify(exactly = 1) { streamingPreference.setEnabled(false) }
        verify(exactly = 0) { scheduler.scheduleAvailabilityRecheckPeriodic() }
        verify(exactly = 0) { scheduler.enqueueAvailabilityCheck() }
        verify(exactly = 0) { scheduler.enqueueReleaseDownloads() }
        // Safe-defaults wrapper — must NOT bulk-download.
        coVerify(exactly = 0) { trackDao.streamableOnlyTracks() }
        coVerify(exactly = 0) { downloadQueueDao.insert(any()) }
    }

    private fun track(id: Long, title: String, artist: String, ytId: String?) = TrackEntity(
        id = id,
        title = title,
        artist = artist,
        album = "",
        durationMs = 0,
        source = MusicSource.YOUTUBE,
        spotifyUri = null,
        youtubeId = ytId,
        albumArtUrl = null,
        canonicalTitle = title.lowercase(),
        canonicalArtist = artist.lowercase(),
        isDownloaded = false,
    )

    private fun buildRepo(
        streamingPreference: StreamingPreference = mockk(relaxed = true),
        streamingWorkScheduler: StreamingWorkScheduler = mockk(relaxed = true),
        playlistDao: PlaylistDao = mockk(relaxed = true),
        context: Context = mockk(relaxed = true),
        trackDao: TrackDao = mockk(relaxed = true),
        syncHistoryDao: SyncHistoryDao = mockk(relaxed = true),
        downloadQueueDao: DownloadQueueDao = mockk(relaxed = true),
        discoveryQueueDao: DiscoveryQueueDao = mockk(relaxed = true),
    ): MusicRepositoryImpl = MusicRepositoryImpl(
        context = context,
        trackDao = trackDao,
        playlistDao = playlistDao,
        syncHistoryDao = syncHistoryDao,
        downloadQueueDao = downloadQueueDao,
        discoveryQueueDao = discoveryQueueDao,
        blocklistGuard = mockk(relaxed = true),
        trackMatcher = mockk(relaxed = true),
        stashMixRecipeDao = mockk(relaxed = true),
        downloadNetworkPreference = mockk(relaxed = true),
        streamingPreference = streamingPreference,
        streamingWorkScheduler = streamingWorkScheduler,
    )
}
