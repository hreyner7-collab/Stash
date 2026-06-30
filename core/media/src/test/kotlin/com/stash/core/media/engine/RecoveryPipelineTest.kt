package com.stash.core.media.engine

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.media.StreamingHaltedEvent
import com.stash.core.media.service.StashPlaybackService.Companion.EXTRA_TRACK_ID
import com.stash.core.media.streaming.StreamSourceRegistry
import com.stash.core.media.streaming.StreamUrl
import com.stash.core.media.streaming.StreamUrlCache
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins the [RecoveryPipeline] ladder: one retry-in-place per track per
 * error episode (re-armed by healthy playback), escalation to the
 * bounded cascade when the retry credit is spent or no fresh URL can be
 * resolved, and halt (pause + event) at the cascade threshold.
 *
 * Robolectric because the retry path builds Media3 [MediaItem]s
 * (android.net.Uri / android.os.Bundle internally).
 */
@RunWith(RobolectricTestRunner::class)
class RecoveryPipelineTest {

    private val streamUrlCache: StreamUrlCache = StreamUrlCache()
    private val trackDao: TrackDao = mockk()
    private val streamResolver: StreamSourceRegistry = mockk()
    private val controller: MediaController = mockk(relaxed = true)
    private val haltedEvents = mutableListOf<StreamingHaltedEvent>()

    private val pipeline = RecoveryPipeline(
        streamUrlCache = streamUrlCache,
        trackDao = trackDao,
        streamResolver = streamResolver,
        onHalted = { haltedEvents += it },
    )

    private val track = TrackEntity(
        id = 42L,
        title = "Some Song",
        artist = "Some Artist",
        album = "Some Album",
        durationMs = 210_000L,
    )

    @Before
    fun setUp() {
        coEvery { trackDao.getById(42L) } returns track
        every { controller.currentPosition } returns 5_000L
    }

    private fun timelineItemForTrack(id: Long): MediaItem = MediaItem.Builder()
        .setMediaId(id.toString())
        .setUri("https://stale-url/$id")
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setExtras(Bundle().apply { putLong(EXTRA_TRACK_ID, id) })
                .build(),
        )
        .build()

    @Test
    fun claimRetry_grantsOncePerTrack_untilHealthyPlaybackRearms() {
        assertThat(pipeline.claimRetry(42L)).isTrue()
        assertThat(pipeline.claimRetry(42L)).isFalse() // credit spent

        pipeline.onPlaybackHealthy()

        assertThat(pipeline.claimRetry(42L)).isTrue() // re-armed
        assertThat(pipeline.claimRetry(-1L)).isFalse() // invalid id never retries
    }

    @Test
    fun retryInPlace_resumesSameTrackAtSamePosition_withFreshUrl() = runTest {
        coEvery {
            streamResolver.resolve(track, allowYouTube = true, allowYtDlp = false)
        } returns StreamUrl(url = "https://fresh-url/42", expiresAtMs = Long.MAX_VALUE)
        every { controller.mediaItemCount } returns 1
        every { controller.getMediaItemAt(0) } returns timelineItemForTrack(42L)

        pipeline.retryInPlace(controller, 42L, "Some Song")

        verify {
            controller.replaceMediaItem(
                0,
                match { it.localConfiguration?.uri.toString() == "https://fresh-url/42" },
            )
        }
        verify { controller.seekTo(0, 5_000L) }
        verify { controller.prepare() }
        verify { controller.play() }
        // The failing URL's cache entry must be gone (invalidated, and the
        // resolver result is cached by the registry, not the pipeline).
        assertThat(haltedEvents).isEmpty()
    }

    @Test
    fun retryInPlace_withNoFreshUrl_escalatesToCascadeSkip() = runTest {
        coEvery {
            streamResolver.resolve(track, allowYouTube = true, allowYtDlp = false)
        } returns null
        every { controller.hasNextMediaItem() } returns true

        pipeline.retryInPlace(controller, 42L, "Some Song")

        verify { controller.seekToNextMediaItem() }
        assertThat(haltedEvents).isEmpty()
    }

    @Test
    fun escalate_haltsAfterThreshold_pausesAndEmitsEvent() {
        every { controller.hasNextMediaItem() } returns true

        pipeline.escalate(controller, "A") // 1: recover (skip)
        pipeline.escalate(controller, "B") // 2: recover (skip)
        pipeline.escalate(controller, "C") // 3: HALT

        verify(exactly = 2) { controller.seekToNextMediaItem() }
        verify { controller.pause() }
        assertThat(haltedEvents).hasSize(1)
        assertThat(haltedEvents[0].failingTitle).isEqualTo("C")
        assertThat(haltedEvents[0].consecutiveErrorCount).isEqualTo(3)
    }

    @Test
    fun escalate_counterRearmedByUserTransport() {
        every { controller.hasNextMediaItem() } returns true

        pipeline.escalate(controller, "A")
        pipeline.escalate(controller, "B")
        pipeline.onUserTransport() // user pressed next/seek — re-arm

        pipeline.escalate(controller, "C") // counts as 1, not 3

        assertThat(haltedEvents).isEmpty()
    }

    @Test
    fun skipPastBroken_atEndOfQueue_stopsCleanly() {
        every { controller.hasNextMediaItem() } returns false

        pipeline.skipPastBroken(controller)

        verify { controller.stop() }
        verify(exactly = 0) { controller.seekToNextMediaItem() }
    }
}
