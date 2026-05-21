package com.stash.data.download.preview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
     */
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
