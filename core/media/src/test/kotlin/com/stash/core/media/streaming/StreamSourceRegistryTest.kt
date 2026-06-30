package com.stash.core.media.streaming

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.prefs.StreamingPreference
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StreamSourceRegistryTest {

    private val kennyy: KennyyStreamResolver = mockk()
    private val qobuz: QobuzStreamResolver = mockk()
    private val antra: AntraStreamResolver = mockk()
    private val youtube: YouTubeStreamResolver = mockk()
    private val streamingPreference: StreamingPreference = mockk()

    // OneDrive sits first in every chain but self-gates to null when not
    // connected — that's the baseline state for all existing tests.
    private val oneDrive: OneDriveStreamResolver = mockk {
        coEvery { resolve(any()) } returns null
    }

    // Real (in-memory, no persistence file) StreamUrlCache: the registry's
    // chokepoint caching writes resolved URLs into it as a side effect,
    // which is harmless to every assertion here.
    private fun registry() = StreamSourceRegistry(kennyy, qobuz, antra, youtube, oneDrive, streamingPreference, StreamUrlCache())

    /**
     * The forceAntraOnly toggle makes antra the PREFERRED first source:
     * when antra serves, no other resolver is consulted.
     */
    @Test
    fun resolve_forceAntraOnly_serves_antra_first() = runTest {
        coEvery { streamingPreference.isForceAntraOnly() } returns true
        // kennyy/qobuz/youtube must never be consulted — intentionally unstubbed.
        coEvery { antra.resolve(any()) } returns antraStreamUrl()
        val track = stubTrack()

        val result = registry().resolve(track, allowYouTube = true)

        assertThat(result?.origin).isEqualTo("antra")
        coVerify(exactly = 0) { youtube.resolve(any(), any()) }
    }

    /**
     * Under forceAntraOnly an antra miss falls through to the normal chain
     * (lossless proxies, then youtube). The old hard-exclusive semantics
     * silently disabled ALL playback when the toggle was on without a
     * connected antra account (on-device 2026-06-11) — a user-facing
     * toggle must never be able to brick the whole app.
     */
    @Test
    fun resolve_forceAntraOnly_falls_back_to_normal_chain_when_antra_misses() = runTest {
        coEvery { streamingPreference.isForceAntraOnly() } returns true
        coEvery { antra.resolve(any()) } returns null
        coEvery { kennyy.resolve(any()) } returns null
        coEvery { qobuz.resolve(any()) } returns null
        coEvery { youtube.resolve(any(), any()) } returns null
        val track = stubTrack()

        val result = registry().resolve(track, allowYouTube = true)

        assertThat(result).isNull()
        coVerify { antra.resolve(track) }
        coVerify { youtube.resolve(track, allowYtDlp = true) }
    }

    /**
     * The background-fill path passes `allowYtDlp = false` so the YouTube
     * fallback resolves via the fast InnerTube engine only. Verify the flag
     * is forwarded to [YouTubeStreamResolver.resolve].
     */
    @Test
    fun resolve_passes_allowYtDlp_to_youtube_resolver() = runTest {
        coEvery { streamingPreference.isForceAntraOnly() } returns false
        coEvery { streamingPreference.isForceYouTubeFallback() } returns false
        coEvery { kennyy.resolve(any()) } returns null
        coEvery { qobuz.resolve(any()) } returns null
        coEvery { antra.resolve(any()) } returns null
        coEvery { youtube.resolve(any(), any()) } returns null
        val track = stubTrack()

        registry().resolve(track, allowYouTube = true, allowYtDlp = false)

        coVerify { youtube.resolve(track, allowYtDlp = false) }
    }

    /**
     * Foreground (user-tap) callers leave `allowYtDlp` at its default of
     * `true`, so the slower yt-dlp path stays available.
     */
    @Test
    fun resolve_defaults_allowYtDlp_true() = runTest {
        coEvery { streamingPreference.isForceAntraOnly() } returns false
        coEvery { streamingPreference.isForceYouTubeFallback() } returns false
        coEvery { kennyy.resolve(any()) } returns null
        coEvery { qobuz.resolve(any()) } returns null
        coEvery { antra.resolve(any()) } returns null
        coEvery { youtube.resolve(any(), any()) } returns null
        val track = stubTrack()

        registry().resolve(track, allowYouTube = true)

        coVerify { youtube.resolve(track, allowYtDlp = true) }
    }

    /**
     * antra sits AFTER squid and BEFORE youtube: when both Qobuz proxies
     * miss but antra resolves, antra serves and youtube is never consulted.
     */
    @Test
    fun resolve_antra_served_before_youtube_when_qobuz_misses() = runTest {
        coEvery { streamingPreference.isForceAntraOnly() } returns false
        coEvery { streamingPreference.isForceYouTubeFallback() } returns false
        coEvery { kennyy.resolve(any()) } returns null
        coEvery { qobuz.resolve(any()) } returns null
        coEvery { antra.resolve(any()) } returns antraStreamUrl()
        val track = stubTrack()

        val result = registry().resolve(track, allowYouTube = true)

        assertThat(result?.origin).isEqualTo("antra")
        coVerify(exactly = 0) { youtube.resolve(any(), any()) }
    }

    /**
     * antra self-gates by returning null (not connected / out of quota);
     * the registry then falls through to youtube.
     */
    @Test
    fun resolve_falls_to_youtube_when_antra_returns_null() = runTest {
        coEvery { streamingPreference.isForceAntraOnly() } returns false
        coEvery { streamingPreference.isForceYouTubeFallback() } returns false
        coEvery { kennyy.resolve(any()) } returns null
        coEvery { qobuz.resolve(any()) } returns null
        coEvery { antra.resolve(any()) } returns null
        coEvery { youtube.resolve(any(), any()) } returns null
        val track = stubTrack()

        registry().resolve(track, allowYouTube = true)

        coVerify { antra.resolve(track) }
        coVerify { youtube.resolve(track, allowYtDlp = true) }
    }

    /**
     * The forceYt test toggle skips kennyy/qobuz entirely and routes through
     * the YouTube resolver only. Verify that branch still forwards
     * `allowYtDlp` to [YouTubeStreamResolver.resolve].
     */
    @Test
    fun resolve_forceYt_branch_passes_allowYtDlp_to_youtube() = runTest {
        coEvery { streamingPreference.isForceAntraOnly() } returns false
        coEvery { streamingPreference.isForceYouTubeFallback() } returns true
        // kennyy/qobuz are skipped in the forceYt branch — intentionally unstubbed.
        coEvery { youtube.resolve(any(), any()) } returns null
        val track = stubTrack()

        registry().resolve(track, allowYouTube = true, allowYtDlp = false)

        coVerify { youtube.resolve(track, allowYtDlp = false) }
    }

    /**
     * Speculative resolution (setQueue's background fill, both next-track
     * prefetchers) passes `allowAntra = false`: an antra resolve costs one
     * single from a finite quota plus a 60-120s exclusive job slot, so it
     * is reserved for tracks the user is actually playing. A 50-track
     * playlist tap during a kennyy outage must NOT burn 50 singles
     * (observed on-device 2026-06-09: 5 singles drained by one tap).
     */
    @Test
    fun resolve_allowAntra_false_skips_antra_in_normal_chain() = runTest {
        coEvery { streamingPreference.isForceAntraOnly() } returns false
        coEvery { streamingPreference.isForceYouTubeFallback() } returns false
        coEvery { kennyy.resolve(any()) } returns null
        coEvery { qobuz.resolve(any()) } returns null
        // antra must never be consulted — intentionally unstubbed.
        coEvery { youtube.resolve(any(), any()) } returns null
        val track = stubTrack()

        registry().resolve(track, allowYouTube = true, allowYtDlp = false, allowAntra = false)

        coVerify(exactly = 0) { antra.resolve(any()) }
        coVerify { youtube.resolve(track, allowYtDlp = false) }
    }

    /**
     * Under the forceAntraOnly toggle, a speculative resolve
     * (`allowAntra = false`) skips antra (quota protection) but still uses
     * the normal fallback chain — the background fill keeps working so the
     * queue stays playable.
     */
    @Test
    fun resolve_forceAntraOnly_with_allowAntra_false_uses_fallback_chain_without_antra() = runTest {
        coEvery { streamingPreference.isForceAntraOnly() } returns true
        coEvery { streamingPreference.isForceYouTubeFallback() } returns false
        coEvery { kennyy.resolve(any()) } returns null
        coEvery { qobuz.resolve(any()) } returns null
        coEvery { youtube.resolve(any(), any()) } returns null
        // antra must never be consulted — intentionally unstubbed.
        val track = stubTrack()

        val result = registry().resolve(track, allowYouTube = true, allowAntra = false)

        assertThat(result).isNull()
        coVerify(exactly = 0) { antra.resolve(any()) }
        coVerify { youtube.resolve(track, allowYtDlp = true) }
    }

    private fun stubTrack(): TrackEntity = TrackEntity(
        id = 1L,
        title = "Title",
        artist = "Artist",
        album = "Album",
        durationMs = 200_000L,
        youtubeId = "abc123",
    )

    private fun antraStreamUrl(): StreamUrl = StreamUrl(
        url = "file:///cache/antra/1.flac",
        expiresAtMs = Long.MAX_VALUE,
        codec = "flac",
        bitsPerSample = 24,
        origin = "antra",
    )
}
