package com.stash.data.download.search

import android.content.Context
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.SimpleCache
import com.stash.core.data.audio.AudioMetadata
import com.stash.core.data.blocklist.BlocklistGuard
import com.stash.core.data.db.dao.DownloadQueueDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.repository.MusicRepository
import com.stash.core.model.MusicSource
import com.stash.core.model.TrackItem
import com.stash.data.download.DownloadExecutor
import com.stash.data.download.DownloadResult
import com.stash.data.download.files.FileOrganizer.CommittedTrack
import com.stash.data.download.lossless.LosslessSourcePreferences
import com.stash.data.download.lossless.LosslessSourceRegistry
import com.stash.data.download.shared.TrackFinalizer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Pins the v0.9.35 metadata-embedding plan, Task 7 contract for
 * [SearchDownloadCoordinator]:
 *
 *  * After a successful [TrackFinalizer.finalizeFile] on either the
 *    lossless or yt-dlp branch, [TrackDao.setMetadataEmbeddedAt] must
 *    fire with a non-zero timestamp so the backfill worker skips the
 *    row.
 *
 * The original Task 7 plan only covered DownloadManager; this file was
 * added in Task 6 review when the second [TrackFinalizer.finalizeFile]
 * caller was discovered.
 */
class SearchDownloadCoordinatorEmbedStampTest {

    private val registry: LosslessSourceRegistry = mockk()
    private val previewCache: SimpleCache = mockk(relaxed = true)
    private val httpDataSourceFactory: HttpDataSource.Factory = mockk(relaxed = true)
    private val cacheKeyFactory: CacheKeyFactory = mockk(relaxed = true)
    private val downloadExecutor: DownloadExecutor = mockk(relaxed = true)
    private val trackFinalizer: TrackFinalizer = mockk()
    private val trackDao: TrackDao = mockk(relaxed = true)
    private val musicRepository: MusicRepository = mockk(relaxed = true)
    private val blocklistGuard: BlocklistGuard = mockk(relaxed = true)
    private val context: Context = mockk(relaxed = true)
    private val losslessPrefs: LosslessSourcePreferences = mockk(relaxed = true)
    private val downloadQueueDao: DownloadQueueDao = mockk(relaxed = true)
    private val loudnessMeasurer: com.stash.core.data.audio.LoudnessMeasurer = mockk(relaxed = true)

    private val tmpCacheDir: File = File(
        System.getProperty("java.io.tmpdir"),
        "stash-search-stamp-test-${System.nanoTime()}",
    ).also { it.mkdirs() }

    private fun newSubject(): SearchDownloadCoordinator = SearchDownloadCoordinator(
        registry = registry,
        previewCache = previewCache,
        httpDataSourceFactory = httpDataSourceFactory,
        cacheKeyFactory = cacheKeyFactory,
        downloadExecutor = downloadExecutor,
        trackFinalizer = trackFinalizer,
        trackDao = trackDao,
        musicRepository = musicRepository,
        blocklistGuard = blocklistGuard,
        context = context,
        losslessPrefs = losslessPrefs,
        downloadQueueDao = downloadQueueDao,
        loudnessMeasurer = loudnessMeasurer,
    )

    @Before
    fun setUp() {
        every { context.cacheDir } returns tmpCacheDir
    }

    private fun stubExistingTrackRow(id: Long = 7L): TrackEntity = TrackEntity(
        id = id,
        title = "Sample",
        artist = "Sample Artist",
        youtubeId = "vid42",
        canonicalTitle = "sample",
        canonicalArtist = "sample artist",
        durationMs = 200_000L,
        source = MusicSource.YOUTUBE,
        albumArtUrl = null,
    )

    private fun arrangeYtDlpSuccess() {
        coEvery { registry.resolve(any()) } returns null
        coEvery { losslessPrefs.youtubeFallbackEnabledNow() } returns true

        val tempFile = File.createTempFile("search_yt", ".opus").apply { deleteOnExit() }
        coEvery {
            downloadExecutor.download(any(), any(), any(), any(), any())
        } returns DownloadResult.Success(tempFile)

        val committed = CommittedTrack(
            filePath = "/library/Sample Artist/Sample.opus",
            sizeBytes = 4096L,
        )
        val meta = AudioMetadata(
            durationMs = 200_000L,
            bitrateKbps = 128,
            format = "opus",
            sampleRateHz = 48_000,
            bitsPerSample = 16,
        )
        coEvery {
            trackFinalizer.finalizeFile(any(), any(), any(), any())
        } returns TrackFinalizer.FinalizeResult.Success(committed, meta)

        coEvery { trackDao.findByYoutubeId("vid42") } returns stubExistingTrackRow()
    }

    @Test
    fun `yt-dlp branch stamps metadata_embedded_at with non-zero timestamp`() = runTest {
        arrangeYtDlpSuccess()
        val tsSlot = slot<Long>()
        coEvery { trackDao.setMetadataEmbeddedAt(7L, capture(tsSlot)) } answers { }

        val item = TrackItem(
            videoId = "vid42",
            title = "Sample",
            artist = "Sample Artist",
            durationSeconds = 200.0,
            thumbnailUrl = null,
        )

        val before = System.currentTimeMillis()
        newSubject().download(item).toList()
        val after = System.currentTimeMillis()

        coVerify { trackDao.setMetadataEmbeddedAt(7L, any()) }
        assertTrue(
            "stamp ${tsSlot.captured} must be in [$before, $after]",
            tsSlot.captured in before..after,
        )
    }
}
