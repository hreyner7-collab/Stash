package com.stash.data.download.preview

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.services.youtube.ItagItem
import org.schabi.newpipe.extractor.stream.AudioStream

/**
 * Unit tests for [NewPipeStreamExtractor]'s pure helpers and its
 * suspend-fun behavioural contract.
 *
 * `pickBestAudio` is exercised in isolation so we don't have to boot
 * NewPipe's static `init()` (which expects a real Downloader). The
 * `extractStreamUrl` cases use a fake [OkHttpNewPipeDownloader] /
 * forced exceptions to drive the null / timeout / cancellation
 * paths. Real NewPipe parsing is not under test here — the spike's
 * answer comes from on-device LATDIAG, not from JVM round-trips.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NewPipeStreamExtractorTest {

    @Test
    fun `pickBestAudio returns highest averageBitrate URL`() {
        val streams = listOf(
            fakeAudioStream(url = "https://a/low",  averageBitrate = 64_000,  bitrate = 64_000),
            fakeAudioStream(url = "https://a/mid",  averageBitrate = 128_000, bitrate = 128_000),
            fakeAudioStream(url = "https://a/high", averageBitrate = 256_000, bitrate = 256_000),
        )
        assertEquals("https://a/high", NewPipeStreamExtractor.pickBestAudio(streams))
    }

    @Test
    fun `pickBestAudio falls back to bitrate when averageBitrate is 0`() {
        // Some YouTube formats don't report averageBitrate but do report
        // a peak bitrate. We use bitrate as the tiebreaker rather than
        // sorting the 0-averageBitrate entries to the bottom.
        val streams = listOf(
            fakeAudioStream(url = "https://a/avgKnown", averageBitrate = 128_000, bitrate = 128_000),
            fakeAudioStream(url = "https://a/peakOnly", averageBitrate = 0,       bitrate = 192_000),
        )
        assertEquals("https://a/peakOnly", NewPipeStreamExtractor.pickBestAudio(streams))
    }

    @Test
    fun `pickBestAudio returns null on empty list`() {
        assertNull(NewPipeStreamExtractor.pickBestAudio(emptyList()))
    }

    @Test
    fun `pickBestAudio returns the first stream on a tie`() {
        // Locks in maxByOrNull's "first equal key wins" semantics. Future
        // YouTube responses regularly include two streams at identical
        // averageBitrate (Opus vs AAC at 160k, etc.); the race must not
        // start non-deterministically picking a different URL across runs.
        val streams = listOf(
            fakeAudioStream(url = "https://a/first",  averageBitrate = 160_000, bitrate = 160_000),
            fakeAudioStream(url = "https://a/second", averageBitrate = 160_000, bitrate = 160_000),
        )
        assertEquals("https://a/first", NewPipeStreamExtractor.pickBestAudio(streams))
    }

    /**
     * Helper that constructs a minimal [AudioStream] suitable for the
     * pure-function tests.
     *
     * NewPipe's `AudioStream.Builder` (verified against v0.24.6) exposes
     * `setAverageBitrate(int)` directly, but the peak `bitrate` field is
     * populated from an attached [ItagItem] inside the private
     * constructor — there is no `Builder.setBitrate(int)` setter. To
     * drive the `averageBitrate==0 → bitrate` fallback branch of
     * `pickBestAudio`, we attach a minimal [ItagItem] whose `bitrate`
     * is set explicitly. `id`, `content`, and (transitively defaulted)
     * `deliveryMethod` are the build()-required fields.
     *
     * **Upgrade path:** if a future NewPipe release introduces
     * `Builder.setBitrate(int)`, prefer that and drop the [ItagItem]
     * indirection. If `ItagItem`'s constructor starts rejecting
     * arbitrary itag ids, switch from `140` to whatever `ItagItem.getItag`
     * exposes as a stable AUDIO/M4A constant.
     */
    // ----- extractStreamUrl behavioural contract -----

    @Test
    fun `extractStreamUrl returns null when fetcher throws`() = kotlinx.coroutines.test.runTest {
        val sut = NewPipeStreamExtractor(
            downloader = fakeDownloader(),
            fetcher = { throw java.io.IOException("simulated NewPipe failure") },
        )
        assertNull(sut.extractStreamUrl("anyVideoId"))
    }

    @Test
    fun `extractStreamUrl returns null when fetcher returns empty stream list`() = kotlinx.coroutines.test.runTest {
        val sut = NewPipeStreamExtractor(
            downloader = fakeDownloader(),
            fetcher = { emptyList() },
        )
        assertNull(sut.extractStreamUrl("anyVideoId"))
    }

    @Test
    fun `extractStreamUrl returns highest-bitrate URL on success`() = kotlinx.coroutines.test.runTest {
        val streams = listOf(
            fakeAudioStream(url = "https://x/low",  averageBitrate = 64_000,  bitrate = 64_000),
            fakeAudioStream(url = "https://x/high", averageBitrate = 256_000, bitrate = 256_000),
        )
        val sut = NewPipeStreamExtractor(
            downloader = fakeDownloader(),
            fetcher = { streams },
        )
        assertEquals("https://x/high", sut.extractStreamUrl("anyVideoId"))
    }

    @Test
    fun `extractStreamUrl returns null on timeout`() = kotlinx.coroutines.test.runTest {
        val sut = NewPipeStreamExtractor(
            downloader = fakeDownloader(),
            // Stretches well past NEWPIPE_TIMEOUT_MS. Injecting the test
            // scheduler keeps withContext on virtual time so `delay(60_000)`
            // and the inner `withTimeout(15_000)` both skip wall-clock —
            // the test completes in milliseconds instead of waiting 15 s.
            fetcher = { kotlinx.coroutines.delay(60_000); emptyList() },
            dispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler),
        )
        assertNull(sut.extractStreamUrl("anyVideoId"))
    }

    @Test
    fun `extractStreamUrl propagates cancellation`() = kotlinx.coroutines.test.runTest {
        // Use supervisorScope so a child cancellation doesn't propagate up
        // and fail the test harness. We want to observe that the suspend
        // call ends in cancellation, not normal completion (which would
        // mean extractStreamUrl swallowed CancellationException).
        kotlinx.coroutines.supervisorScope {
            val sut = NewPipeStreamExtractor(
                downloader = fakeDownloader(),
                // Never returns — only outer cancellation should end this call.
                fetcher = { kotlinx.coroutines.delay(Long.MAX_VALUE); emptyList() },
                dispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler),
            )
            val completed = java.util.concurrent.atomic.AtomicBoolean(false)
            val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
            val job = launch {
                try {
                    sut.extractStreamUrl("anyVideoId")
                    completed.set(true)
                } catch (ce: kotlinx.coroutines.CancellationException) {
                    cancelled.set(true)
                }
            }
            // runCurrent() is MANDATORY here, not defensive: without it the
            // child's withContext(dispatcher) hasn't yet reached delay() when
            // job.cancel fires, so the CancellationException would be thrown
            // before reaching extractStreamUrl's own try/catch — bypassing
            // the contract under test.
            runCurrent()
            job.cancel(kotlinx.coroutines.CancellationException("outer cancel"))
            job.join()
            assertTrue("extractStreamUrl must propagate cancellation, not return null", cancelled.get())
            org.junit.Assert.assertFalse(completed.get())
        }
    }

    private fun fakeDownloader(): OkHttpNewPipeDownloader =
        OkHttpNewPipeDownloader(client = okhttp3.OkHttpClient())

    private fun fakeAudioStream(url: String, averageBitrate: Int, bitrate: Int): AudioStream {
        // Minimal ItagItem — `id` is arbitrary (test-only), `ItagType.AUDIO`
        // matches the AudioStream context, and MediaFormat is duplicated
        // here because the ItagItem constructor requires it.
        val itag = ItagItem(140, ItagItem.ItagType.AUDIO, MediaFormat.M4A, "audio/mp4")
        itag.bitrate = bitrate
        return AudioStream.Builder()
            .setId("test")
            .setContent(url, /* isUrl = */ true)
            .setMediaFormat(MediaFormat.M4A)
            .setAverageBitrate(averageBitrate)
            .setItagItem(itag)
            .build()
    }
}
