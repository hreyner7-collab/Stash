package com.stash.core.data.repository

import android.content.Context
import com.stash.core.data.db.dao.DiscoveryQueueDao
import com.stash.core.data.db.dao.DownloadQueueDao
import com.stash.core.data.db.dao.PlaylistDao
import com.stash.core.data.db.dao.SyncHistoryDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.DownloadQueueEntity
import com.stash.core.model.MusicSource
import com.stash.core.model.Track
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for [MusicRepositoryImpl.removeDownload] and
 * [MusicRepositoryImpl.enqueueDownload] (Task 19).
 *
 * Both methods are the data-layer half of the Track-options long-press
 * sheet's two new streaming-engine actions. Visibility/UI behaviour
 * (covered by the conditional rendering rules in `TrackOptionsSheet`)
 * is exercised manually — `core:ui` has no test infrastructure set up
 * (no `src/test`, no Robolectric / Compose-test deps), so the visibility
 * checks live as a tested predicate at the call site rather than via
 * Compose UI test.
 */
class MusicRepositoryStreamingTrackActionsTest {

    @Test
    fun `enqueueDownload inserts a queue entry with manual-download shape`() = runTest {
        val downloadQueueDao = mockk<DownloadQueueDao>(relaxed = true)
        val captured = slot<DownloadQueueEntity>()
        coEvery { downloadQueueDao.insert(capture(captured)) } returns 1L

        val repo = buildRepo(downloadQueueDao = downloadQueueDao)
        repo.enqueueDownload(
            track(id = 42L, title = "Title", artist = "Artist", ytId = "abc123"),
        )

        coVerify(exactly = 1) { downloadQueueDao.insert(any()) }
        val entry = captured.captured
        assertEquals(42L, entry.trackId)
        // Manual download — no associated sync session.
        assertNull(entry.syncId)
        assertEquals("Artist - Title", entry.searchQuery)
        assertEquals("https://music.youtube.com/watch?v=abc123", entry.youtubeUrl)
    }

    @Test
    fun `enqueueDownload omits youtubeUrl for tracks with no youtube id`() = runTest {
        val downloadQueueDao = mockk<DownloadQueueDao>(relaxed = true)
        val captured = slot<DownloadQueueEntity>()
        coEvery { downloadQueueDao.insert(capture(captured)) } returns 1L

        val repo = buildRepo(downloadQueueDao = downloadQueueDao)
        repo.enqueueDownload(
            track(id = 7L, title = "Spotify-only", artist = "Foo", ytId = null),
        )

        assertEquals(7L, captured.captured.trackId)
        assertNull(captured.captured.youtubeUrl)
    }

    @Test
    fun `removeDownload marks track as not downloaded without deleting the row`() = runTest {
        val trackDao = mockk<TrackDao>(relaxed = true)

        val repo = buildRepo(trackDao = trackDao)
        repo.removeDownload(
            track(id = 99L, title = "T", artist = "A", ytId = null),
        )

        // Library row stays alive — only the on-disk file is cleared and
        // the metadata flagged "not downloaded". A track-delete would
        // call trackDao.delete(...) instead, which the contract says we
        // must NOT do here.
        coVerify(exactly = 1) { trackDao.markAsNotDownloaded(99L) }
        coVerify(exactly = 0) { trackDao.delete(any()) }
    }

    private fun track(id: Long, title: String, artist: String, ytId: String?) = Track(
        id = id,
        title = title,
        artist = artist,
        source = MusicSource.YOUTUBE,
        youtubeId = ytId,
        isDownloaded = false,
        // No filePath — removeDownload's file-delete branch is exercised
        // by a separate integration-style test if needed; here we cover
        // the DAO interactions which are the load-bearing assertions.
        filePath = null,
    )

    private fun buildRepo(
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
        streamingPreference = mockk(relaxed = true),
        streamingWorkScheduler = mockk(relaxed = true),
    )
}
