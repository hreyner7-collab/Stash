package com.stash.core.media

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import androidx.test.core.app.ApplicationProvider
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.prefs.StreamingPreference
import com.stash.core.data.repository.MusicRepository
import com.stash.core.media.service.StashPlaybackService.Companion.EXTRA_STREAM_ORIGIN
import com.stash.core.media.service.StashPlaybackService.Companion.EXTRA_TRACK_ID
import com.stash.core.media.streaming.ConnectivityMonitor
import com.stash.core.media.streaming.StreamSourceRegistry
import com.stash.core.media.streaming.StreamUrl
import com.stash.core.media.streaming.StreamUrlCache
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Skip should behave like a tap: when the track you skip TO is a provisional
 * lossy item (the background fill resolved it to YouTube AAC during a Qobuz
 * outage, or arcod was skipped on the speculative fill), skip must
 * foreground-resolve it (allowYtDlp = true, so arcod is tried) and swap the
 * timeline slot to the lossless URL BEFORE advancing. A slot that's already
 * lossless (e.g. the prefetched next-up) advances instantly with no resolve.
 */
@RunWith(RobolectricTestRunner::class)
class PlayerRepositorySkipUpgradeTest {

    private val playbackStateStore: PlaybackStateStore = mockk(relaxed = true)
    private val musicRepository: MusicRepository = mockk {
        every { trackDeletions } returns MutableSharedFlow()
    }
    private val streamingPreference: StreamingPreference = mockk()
    private val streamResolver: StreamSourceRegistry = mockk()
    private val streamUrlCache: StreamUrlCache = mockk(relaxUnitFun = true)
    private val connectivity: ConnectivityMonitor = mockk(relaxed = true)
    private val trackDao: TrackDao = mockk()
    private val controller: MediaController = mockk(relaxed = true)

    private lateinit var repo: PlayerRepositoryImpl

    @Before
    fun setUp() {
        repo = PlayerRepositoryImpl(
            context = ApplicationProvider.getApplicationContext(),
            playbackStateStore = playbackStateStore,
            musicRepository = musicRepository,
            streamingPreference = streamingPreference,
            streamResolver = streamResolver,
            streamUrlCache = streamUrlCache,
            connectivity = connectivity,
            trackDao = trackDao,
            playbackResumer = PlaybackResumer(playbackStateStore, trackDao),
        )
        repo.controllerDeferred = controller
        coEvery { streamingPreference.current() } returns true
    }

    private fun item(trackId: Long, uri: String, origin: String?) = MediaItem.Builder()
        .setMediaId(trackId.toString())
        .setUri(uri)
        .setMediaMetadata(
            MediaMetadata.Builder().setExtras(
                Bundle().apply {
                    putLong(EXTRA_TRACK_ID, trackId)
                    origin?.let { putString(EXTRA_STREAM_ORIGIN, it) }
                },
            ).build(),
        )
        .build()

    @Test
    fun `skipNext upgrades an AAC next slot to arcod then advances`() = runTest {
        every { controller.nextMediaItemIndex } returns 1
        every { controller.mediaItemCount } returns 2
        every { controller.getMediaItemAt(1) } returns item(42, "https://yt/42", "youtube")
        every { streamUrlCache.get(42) } returns null
        coEvery { trackDao.getById(42) } returns mockk(relaxed = true)
        coEvery { streamResolver.resolve(any(), allowYouTube = true, allowYtDlp = true) } returns
            StreamUrl(url = "https://arcod/42.flac", expiresAtMs = Long.MAX_VALUE, origin = "arcod")

        val swapped = slot<MediaItem>()
        every { controller.replaceMediaItem(eq(1), capture(swapped)) } returns Unit

        repo.skipNext()

        coVerify { streamResolver.resolve(any(), allowYouTube = true, allowYtDlp = true) }
        verify { controller.replaceMediaItem(eq(1), any()) }
        assertThat(swapped.captured.localConfiguration?.uri.toString())
            .isEqualTo("https://arcod/42.flac")
        verify { controller.seekToNextMediaItem() }
    }

    @Test
    fun `skipNext on an already-lossless next slot advances without resolving`() = runTest {
        every { controller.nextMediaItemIndex } returns 1
        every { controller.mediaItemCount } returns 2
        every { controller.getMediaItemAt(1) } returns item(42, "https://arcod/42.flac", "arcod")

        repo.skipNext()

        coVerify(exactly = 0) { streamResolver.resolve(any(), any(), any()) }
        verify(exactly = 0) { controller.replaceMediaItem(any(), any()) }
        verify { controller.seekToNextMediaItem() }
    }
}
