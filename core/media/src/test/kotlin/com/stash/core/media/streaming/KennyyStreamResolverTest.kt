package com.stash.core.media.streaming

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.db.entity.TrackEntity
import com.stash.data.download.lossless.AudioFormat
import com.stash.data.download.lossless.SourceResult
import com.stash.data.download.lossless.kennyy.KennyySource
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class KennyyStreamResolverTest {

    private val fakeKennyy: KennyySource = mockk()
    private val resolver = KennyyStreamResolver(fakeKennyy)

    @Test
    fun resolve_returnsNullWhenKennyyHasNoMatch() = runTest {
        coEvery { fakeKennyy.resolveImmediate(any()) } returns null

        val result = resolver.resolve(stubTrack())

        assertThat(result).isNull()
    }

    @Test
    fun resolve_parsesEtspIntoExpiresAtMs() = runTest {
        coEvery { fakeKennyy.resolveImmediate(any()) } returns stubSourceResult(
            downloadUrl = "https://streaming-qobuz-std.akamaized.net/file?uid=1&etsp=1778893323&hmac=abc",
        )

        val result = resolver.resolve(stubTrack())

        assertThat(result).isNotNull()
        assertThat(result!!.url).contains("streaming-qobuz-std")
        assertThat(result.expiresAtMs).isEqualTo(1_778_893_323_000L)
    }

    @Test
    fun resolve_returnsNullWhenEtspMissing() = runTest {
        // Defensive: a URL with no etsp can't be safely refreshed; treat as null.
        coEvery { fakeKennyy.resolveImmediate(any()) } returns stubSourceResult(
            downloadUrl = "https://streaming-qobuz-std.akamaized.net/file?uid=1&hmac=abc",
        )

        val result = resolver.resolve(stubTrack())

        assertThat(result).isNull()
    }

    @Test
    fun resolve_returnsNullWhenEtspNotInteger() = runTest {
        coEvery { fakeKennyy.resolveImmediate(any()) } returns stubSourceResult(
            downloadUrl = "https://streaming-qobuz-std.akamaized.net/file?uid=1&etsp=garbage&hmac=abc",
        )

        val result = resolver.resolve(stubTrack())

        assertThat(result).isNull()
    }

    private fun stubTrack(): TrackEntity = TrackEntity(
        id = 42L,
        title = "Some Song",
        artist = "Some Artist",
        album = "Some Album",
        durationMs = 210_000L,
        isrc = "USRC17607839",
    )

    private fun stubSourceResult(downloadUrl: String): SourceResult = SourceResult(
        sourceId = KennyySource.SOURCE_ID,
        downloadUrl = downloadUrl,
        downloadHeaders = emptyMap(),
        format = AudioFormat(codec = "flac", bitrateKbps = 0),
        confidence = 0.95f,
        sourceTrackId = "1234",
        coverArtUrl = null,
    )
}
