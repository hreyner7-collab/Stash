package com.stash.core.data.sync.workers

import android.content.Context
import androidx.room.withTransaction
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.stash.core.data.blocklist.BlocklistGuard
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.dao.DownloadQueueDao
import com.stash.core.data.db.dao.PlaylistDao
import com.stash.core.data.db.dao.RemoteSnapshotDao
import com.stash.core.data.db.dao.SyncHistoryDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.DownloadQueueEntity
import com.stash.core.data.db.entity.PlaylistEntity
import com.stash.core.data.db.entity.PlaylistTrackCrossRef
import com.stash.core.data.db.entity.RemotePlaylistSnapshotEntity
import com.stash.core.data.db.entity.RemoteTrackSnapshotEntity
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.prefs.StreamingPreference
import com.stash.core.data.repository.MusicRepository
import com.stash.core.data.sync.SyncPreferencesManager
import com.stash.core.data.sync.SyncStateManager
import com.stash.core.data.sync.TrackMatcher
import com.stash.core.model.MusicSource
import com.stash.core.model.PlaylistType
import com.stash.core.model.SyncMode
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * MockK tests for [DiffWorker]'s streaming-mode branch (Task 13).
 *
 * Full DiffWorker behaviour is exercised by the existing sync-chain
 * integration tests. This class covers the narrow add-on: when
 * [StreamingPreference.current] returns `true`, new tracks must skip
 * the `download_queue` insert and the worker must enqueue a single
 * [AvailabilityCheckWorker] run at the end (gated on `newTrackCount > 0`).
 *
 * The static `WorkManager.getInstance(ctx)` call inside
 * [AvailabilityCheckWorker.enqueueSelf] isn't mockable in a plain JVM
 * test, so the worker exposes a [DiffWorker.enqueueAvailabilityCheck]
 * test seam (mirroring [AvailabilityCheckWorker.onTrackProcessed]). The
 * [TestableDiffWorker] subclass below records invocations instead of
 * calling out to WorkManager.
 *
 * `database.withTransaction` is an extension on `RoomDatabaseKt` — mocked
 * via [mockkStatic] so the lambda runs inline; same pattern as
 * [com.stash.core.data.blocklist.BlocklistGuardTest].
 */
class DiffWorkerTest {

    private val appContext: Context = mockk(relaxed = true)
    private val workerParams: WorkerParameters = mockk(relaxed = true) {
        // syncId is read off WorkerParameters.inputData at the top of doWork.
        every {
            inputData
        } returns workDataOf(PlaylistFetchWorker.KEY_SYNC_ID to SYNC_ID)
    }
    private val database: StashDatabase = mockk(relaxed = true)
    private val remoteSnapshotDao: RemoteSnapshotDao = mockk(relaxed = true)
    private val trackDao: TrackDao = mockk(relaxed = true)
    private val playlistDao: PlaylistDao = mockk(relaxed = true)
    private val downloadQueueDao: DownloadQueueDao = mockk(relaxed = true)
    private val syncHistoryDao: SyncHistoryDao = mockk(relaxed = true)
    private val trackMatcher: TrackMatcher = TrackMatcher()
    private val syncStateManager: SyncStateManager = mockk(relaxed = true)
    private val musicRepository: MusicRepository = mockk(relaxed = true) {
        coEvery { cleanOrphanedMixTracks() } returns 0
    }
    private val syncPreferencesManager: SyncPreferencesManager = mockk(relaxed = true) {
        every { spotifySyncMode } returns flowOf(SyncMode.REFRESH)
        every { youtubeSyncMode } returns flowOf(SyncMode.REFRESH)
    }
    private val blocklistGuard: BlocklistGuard = mockk {
        coEvery { isBlocked(any(), any(), any(), any()) } returns false
    }
    private val streamingPreference: StreamingPreference = mockk()

    @Before
    fun stubWithTransaction() {
        mockkStatic("androidx.room.RoomDatabaseKt")
        coEvery { database.withTransaction(any<suspend () -> Any>()) } coAnswers {
            secondArg<suspend () -> Any>().invoke()
        }
    }

    @After
    fun teardown() {
        unmockkStatic("androidx.room.RoomDatabaseKt")
    }

    private fun newWorker() = TestableDiffWorker(
        appContext, workerParams,
        database, remoteSnapshotDao, trackDao, playlistDao, downloadQueueDao,
        syncHistoryDao, trackMatcher, syncStateManager, musicRepository,
        syncPreferencesManager, blocklistGuard, streamingPreference,
    )

    /**
     * Seeds the snapshot DAOs so the diff loop sees exactly one Spotify
     * playlist with one brand-new track. `trackDao.findByAnyIdentity`
     * returns `null` so the worker takes the "new track" branch
     * (insert track + maybe insert download queue row).
     */
    private fun seedOneNewTrack() {
        val playlistSnapshot = RemotePlaylistSnapshotEntity(
            id = 100L,
            syncId = SYNC_ID,
            source = MusicSource.SPOTIFY,
            sourcePlaylistId = "spotify:playlist:abc",
            playlistName = "Test Playlist",
            playlistType = PlaylistType.CUSTOM,
        )
        val trackSnapshot = RemoteTrackSnapshotEntity(
            id = 1L,
            syncId = SYNC_ID,
            snapshotPlaylistId = 100L,
            title = "Bohemian Rhapsody",
            artist = "Queen",
            durationMs = 354_000L,
            spotifyUri = "spotify:track:xyz",
            youtubeId = "fJ9rUzIMcZQ",
            position = 0,
        )
        coEvery { remoteSnapshotDao.getPlaylistSnapshotsBySyncId(SYNC_ID) } returns
            listOf(playlistSnapshot)
        coEvery { remoteSnapshotDao.getTrackSnapshotsByPlaylistId(100L) } returns
            listOf(trackSnapshot)

        // findOrCreatePlaylist branch: no existing playlist → insert path.
        coEvery { playlistDao.findBySourceId("spotify:playlist:abc") } returns null
        coEvery { playlistDao.insert(any<PlaylistEntity>()) } returns LOCAL_PLAYLIST_ID
        // Skip the "syncEnabled gate" — fresh inserts default to false, but we
        // need the worker to actually process the playlist. The worker reads
        // `localPlaylist.syncEnabled` off the copy returned from
        // findOrCreatePlaylist; the simplest move is to flip the snapshot
        // source so the worker sees an existing playlist that already opted
        // in. Override findBySourceId to return such a row.
        coEvery { playlistDao.findBySourceId("spotify:playlist:abc") } returns
            PlaylistEntity(
                id = LOCAL_PLAYLIST_ID,
                name = "Test Playlist",
                source = MusicSource.SPOTIFY,
                sourceId = "spotify:playlist:abc",
                syncEnabled = true,
            )

        coEvery { playlistDao.getSnapshotId(LOCAL_PLAYLIST_ID) } returns null
        coEvery { playlistDao.getCrossRef(any(), any()) } returns null
        coEvery { playlistDao.insertCrossRef(any<PlaylistTrackCrossRef>()) } just Runs
        coEvery { playlistDao.deactivateMissingForSource(any(), any()) } returns 0

        // No existing track row → take the "insert + (maybe) queue" branch.
        coEvery {
            trackDao.findByAnyIdentity(any(), any(), any(), any(), any(), any())
        } returns null
        coEvery { trackDao.insert(any<TrackEntity>()) } returns NEW_TRACK_ID
        coEvery { downloadQueueDao.insert(any<DownloadQueueEntity>()) } returns 1L
    }

    private fun seedZeroNewTracks() {
        // Same setup, but findByAnyIdentity returns an existing track so
        // the worker takes the "already known" branch — no track insert,
        // no download-queue insert, no newTrackCount increment.
        val playlistSnapshot = RemotePlaylistSnapshotEntity(
            id = 100L,
            syncId = SYNC_ID,
            source = MusicSource.SPOTIFY,
            sourcePlaylistId = "spotify:playlist:abc",
            playlistName = "Test Playlist",
            playlistType = PlaylistType.CUSTOM,
        )
        val trackSnapshot = RemoteTrackSnapshotEntity(
            id = 1L,
            syncId = SYNC_ID,
            snapshotPlaylistId = 100L,
            title = "Bohemian Rhapsody",
            artist = "Queen",
            durationMs = 354_000L,
            spotifyUri = "spotify:track:xyz",
            youtubeId = "fJ9rUzIMcZQ",
            position = 0,
        )
        coEvery { remoteSnapshotDao.getPlaylistSnapshotsBySyncId(SYNC_ID) } returns
            listOf(playlistSnapshot)
        coEvery { remoteSnapshotDao.getTrackSnapshotsByPlaylistId(100L) } returns
            listOf(trackSnapshot)
        coEvery { playlistDao.findBySourceId("spotify:playlist:abc") } returns
            PlaylistEntity(
                id = LOCAL_PLAYLIST_ID,
                name = "Test Playlist",
                source = MusicSource.SPOTIFY,
                sourceId = "spotify:playlist:abc",
                syncEnabled = true,
            )
        coEvery { playlistDao.getSnapshotId(LOCAL_PLAYLIST_ID) } returns null
        coEvery { playlistDao.getCrossRef(any(), any()) } returns null
        coEvery { playlistDao.insertCrossRef(any<PlaylistTrackCrossRef>()) } just Runs
        coEvery { playlistDao.deactivateMissingForSource(any(), any()) } returns 0

        coEvery {
            trackDao.findByAnyIdentity(any(), any(), any(), any(), any(), any())
        } returns TrackEntity(
            id = 7L,
            title = "Bohemian Rhapsody",
            artist = "Queen",
            isDownloaded = true,
            matchDismissed = true, // suppress the reconciliation path
        )
    }

    @Test
    fun `download mode still inserts download queue rows`() = runTest {
        coEvery { streamingPreference.current() } returns false
        seedOneNewTrack()

        val worker = newWorker()
        worker.doWork()

        // The track was new → download_queue.insert fires exactly once.
        coVerify(exactly = 1) { downloadQueueDao.insert(any<DownloadQueueEntity>()) }
        // And AvailabilityCheckWorker is NOT touched in download mode.
        assertEquals(0, worker.availabilityEnqueueCount)
    }

    @Test
    fun `streaming mode skips download queue and enqueues availability`() = runTest {
        coEvery { streamingPreference.current() } returns true
        seedOneNewTrack()

        val worker = newWorker()
        worker.doWork()

        // Streaming mode: track row still gets inserted, but the
        // download_queue insert is the call that must be skipped.
        coVerify(exactly = 1) { trackDao.insert(any<TrackEntity>()) }
        coVerify(exactly = 0) { downloadQueueDao.insert(any<DownloadQueueEntity>()) }
        // AvailabilityCheckWorker enqueued exactly once at the end of the run.
        assertEquals(1, worker.availabilityEnqueueCount)
    }

    @Test
    fun `streaming mode with zero new tracks does not enqueue availability`() = runTest {
        coEvery { streamingPreference.current() } returns true
        seedZeroNewTracks()

        val worker = newWorker()
        worker.doWork()

        // Existing-track branch: no new track, no download queue insert,
        // and AvailabilityCheckWorker must NOT be enqueued (guarded on
        // newTrackCount > 0).
        coVerify(exactly = 0) { trackDao.insert(any<TrackEntity>()) }
        coVerify(exactly = 0) { downloadQueueDao.insert(any<DownloadQueueEntity>()) }
        assertEquals(0, worker.availabilityEnqueueCount)
    }

    companion object {
        private const val SYNC_ID = 42L
        private const val LOCAL_PLAYLIST_ID = 9L
        private const val NEW_TRACK_ID = 123L
    }

    /**
     * Thin subclass mirroring [AvailabilityCheckWorker]'s test seam pattern.
     * Overrides [enqueueAvailabilityCheck] so the test can count invocations
     * without hitting the static `WorkManager.getInstance(ctx)` lookup
     * inside [AvailabilityCheckWorker.enqueueSelf].
     */
    private class TestableDiffWorker(
        ctx: Context,
        params: WorkerParameters,
        database: StashDatabase,
        remoteSnapshotDao: RemoteSnapshotDao,
        trackDao: TrackDao,
        playlistDao: PlaylistDao,
        downloadQueueDao: DownloadQueueDao,
        syncHistoryDao: SyncHistoryDao,
        trackMatcher: TrackMatcher,
        syncStateManager: SyncStateManager,
        musicRepository: MusicRepository,
        syncPreferencesManager: SyncPreferencesManager,
        blocklistGuard: BlocklistGuard,
        streamingPreference: StreamingPreference,
    ) : DiffWorker(
        ctx, params,
        database, remoteSnapshotDao, trackDao, playlistDao, downloadQueueDao,
        syncHistoryDao, trackMatcher, syncStateManager, musicRepository,
        syncPreferencesManager, blocklistGuard, streamingPreference,
    ) {
        var availabilityEnqueueCount = 0
            private set

        override fun enqueueAvailabilityCheck() {
            availabilityEnqueueCount++
        }
    }
}
