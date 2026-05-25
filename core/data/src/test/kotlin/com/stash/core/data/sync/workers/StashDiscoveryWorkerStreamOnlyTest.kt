package com.stash.core.data.sync.workers

import android.content.Context
import androidx.work.WorkerParameters
import com.stash.core.data.blocklist.BlocklistGuard
import com.stash.core.data.db.dao.DiscoveryQueueDao
import com.stash.core.data.db.dao.DownloadQueueDao
import com.stash.core.data.db.dao.StashMixRecipeDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.DiscoveryQueueEntity
import com.stash.core.data.db.entity.DownloadQueueEntity
import com.stash.core.data.db.entity.StashMixRecipeEntity
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.prefs.DownloadNetworkPreference
import com.stash.core.data.sync.TrackMatcher
import com.stash.core.model.DownloadNetworkMode
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkObject
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * MockK end-to-end test for [StashDiscoveryWorker]'s v0.9.37 stream-only
 * seam. Every recipe in `stash_mix_recipes` is materialized as
 * `PlaylistType.STASH_MIX`, so this worker — whose only job is to drain
 * PENDING discoveries for those recipes — must:
 *   1. Create the stub [TrackEntity] with `isStreamable = true`,
 *      `isDownloaded = false`.
 *   2. **Skip** the `downloadQueueDao.insert(...)` call that the pre-v0.9.37
 *      flow used to file every discovery against [DiscoveryDownloadWorker].
 *
 * The downstream `StashMixRefreshWorker.materializeMix` (Task 3) then picks
 * the stream-only stub up via the new
 * `PlaylistDao.getStreamableOrDoneTrackIdsForRecipe` query so the new track
 * surfaces in the Mix playlist without ever touching the download pipeline.
 *
 * `DiscoveryDownloadWorker.enqueueOneTime` is mocked because the production
 * `doWork()` chains a manual-trigger download sweep at the end — irrelevant
 * here, and unsafe to call from a unit test without a real WorkManager.
 */
class StashDiscoveryWorkerStreamOnlyTest {

    private val appContext: Context = mockk(relaxed = true)
    private val discoveryQueueDao: DiscoveryQueueDao = mockk(relaxed = true)
    private val downloadQueueDao: DownloadQueueDao = mockk(relaxed = true)
    private val trackDao: TrackDao = mockk(relaxed = true)
    private val recipeDao: StashMixRecipeDao = mockk(relaxed = true)
    // TrackMatcher is a final @Singleton class with no Android deps — use the
    // real implementation so canonical title/artist normalisation flows
    // through end-to-end (mirrors how the worker uses it).
    private val trackMatcher: TrackMatcher = TrackMatcher()
    private val blocklistGuard: BlocklistGuard = mockk {
        coEvery { isBlocked(any(), any(), any(), any()) } returns false
    }
    private val downloadNetworkPreference: DownloadNetworkPreference = mockk {
        coEvery { current() } returns DownloadNetworkMode.WIFI_AND_CHARGING
    }

    @Before fun setUp() {
        // `doWork()` chains DiscoveryDownloadWorker.enqueueOneTime → WorkManager
        // .getInstance. Stubbing the companion object here keeps the test pure
        // (no Robolectric WorkManager bring-up needed).
        mockkObject(DiscoveryDownloadWorker.Companion)
        coEvery { DiscoveryDownloadWorker.enqueueOneTime(any(), any()) } returns Unit
    }

    @After fun tearDown() {
        unmockkObject(DiscoveryDownloadWorker.Companion)
    }

    private fun newWorker(): StashDiscoveryWorker {
        val params: WorkerParameters = mockk(relaxed = true)
        return StashDiscoveryWorker(
            appContext, params,
            discoveryQueueDao, downloadQueueDao, trackDao, recipeDao,
            trackMatcher, blocklistGuard, downloadNetworkPreference,
        )
    }

    @Test fun `mix recipe discovery inserts streamable stub and skips download_queue`() = runTest {
        val recipe = StashMixRecipeEntity(
            id = 1L,
            name = "Daily Discover",
            playlistId = 100L,  // recipe is materialized: PlaylistType.STASH_MIX
            isBuiltin = true,
            isActive = true,
        )
        // Recipe.playlistId points at the materialized STASH_MIX playlist;
        // mock it so callers that look up the playlist's type can resolve.
        val pending = DiscoveryQueueEntity(
            id = 42L,
            recipeId = 1L,
            artist = "Test Artist",
            title = "Test Track",
            seedArtist = "Some Seed",
            status = DiscoveryQueueEntity.STATUS_PENDING,
        )

        coEvery { discoveryQueueDao.deleteStalePending(any()) } returns 0
        coEvery {
            discoveryQueueDao.findRecipesAtWeeklyCap(any(), any())
        } returns emptyList()
        coEvery { discoveryQueueDao.getPending(any()) } returns listOf(pending)
        coEvery {
            discoveryQueueDao.countRecentCompletedForRecipe(1L, any())
        } returns 0
        coEvery { recipeDao.getById(1L) } returns recipe
        // No existing track collision — forces the new-stub branch.
        coEvery {
            trackDao.findDownloadedByCanonical(any(), any())
        } returns null

        val insertedTrack = slot<TrackEntity>()
        coEvery { trackDao.insert(capture(insertedTrack)) } returns 999L

        newWorker().doWork()

        // The stub MUST land as a streamable, non-downloaded row so the new
        // PlaylistDao.getStreamableOrDoneTrackIdsForRecipe query picks it up
        // for the Mix playlist on the next materializeMix pass.
        assertTrue(
            "stub track must be flagged isStreamable=true",
            insertedTrack.captured.isStreamable,
        )
        assertFalse(
            "stub track must NOT be flagged isDownloaded=true at insert time",
            insertedTrack.captured.isDownloaded,
        )

        // Critical assertion: no download_queue row for the new stub. This is
        // what diverts the new discovery from the v0.9.20 download pipeline
        // onto the v0.9.30 streaming engine.
        coVerify(exactly = 0) { downloadQueueDao.insert(any<DownloadQueueEntity>()) }

        // Discovery row was still marked DONE with the new trackId so the
        // materializer can re-link it on the next mix refresh.
        coVerify(exactly = 1) {
            discoveryQueueDao.updateStatus(
                id = 42L,
                status = DiscoveryQueueEntity.STATUS_DONE,
                trackId = 999L,
                completedAt = any(),
                errorMessage = null,
            )
        }
    }

    @Test fun `existing downloaded track is reused without re-insert or enqueue`() = runTest {
        // Sanity guard: the dedup branch (matched a track already on disk) must
        // continue to skip BOTH the stub-insert AND the download-queue insert.
        // No regression from the stream-only seam.
        val recipe = StashMixRecipeEntity(
            id = 1L,
            name = "Daily Discover",
            playlistId = 100L,
            isBuiltin = true,
            isActive = true,
        )
        val pending = DiscoveryQueueEntity(
            id = 7L,
            recipeId = 1L,
            artist = "Library Artist",
            title = "Library Track",
            seedArtist = "Some Seed",
        )
        val existing = TrackEntity(
            id = 555L,
            title = "Library Track",
            artist = "Library Artist",
            canonicalTitle = "library track",
            canonicalArtist = "library artist",
            isDownloaded = true,
        )

        coEvery { discoveryQueueDao.deleteStalePending(any()) } returns 0
        coEvery {
            discoveryQueueDao.findRecipesAtWeeklyCap(any(), any())
        } returns emptyList()
        coEvery { discoveryQueueDao.getPending(any()) } returns listOf(pending)
        coEvery {
            discoveryQueueDao.countRecentCompletedForRecipe(1L, any())
        } returns 0
        coEvery { recipeDao.getById(1L) } returns recipe
        coEvery {
            trackDao.findDownloadedByCanonical(any(), any())
        } returns existing

        newWorker().doWork()

        coVerify(exactly = 0) { trackDao.insert(any<TrackEntity>()) }
        coVerify(exactly = 0) { downloadQueueDao.insert(any<DownloadQueueEntity>()) }
        coVerify(exactly = 1) {
            discoveryQueueDao.updateStatus(
                id = 7L,
                status = DiscoveryQueueEntity.STATUS_DONE,
                trackId = 555L,
                completedAt = any(),
                errorMessage = null,
            )
        }
    }

}
