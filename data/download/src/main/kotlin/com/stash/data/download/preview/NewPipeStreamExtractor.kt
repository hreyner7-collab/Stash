package com.stash.data.download.preview

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamInfo
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves a direct audio stream URL for a YouTube video id using NewPipe
 * Extractor. Slotted into [PreviewUrlExtractor]'s race as the middle arm
 * between InnerTube (fast path, often null for restricted music) and
 * yt-dlp (slow correctness backstop).
 *
 * Behaviour: returns the highest-bitrate audio stream URL on success, or
 * null on any failure (extractor throw, parse error, timeout). Cancellation
 * is propagated, not swallowed — required so the race's structured
 * concurrency can tear down the in-flight call when InnerTube wins.
 *
 * NewPipe's static `NewPipe.init()` is called lazily on the first
 * extraction. Subsequent calls hit a `@Volatile` fast path.
 */
@Singleton
class NewPipeStreamExtractor @Inject constructor(
    private val downloader: OkHttpNewPipeDownloader,
    /**
     * Test seam — production code resolves audio streams via
     * [StreamInfo.getInfo]. Tests inject a lambda so they can exercise
     * the surrounding contract (timeout, rescue, cancellation, format
     * selection) without standing up NewPipe's static service registry.
     *
     * Not annotated `@Inject` — production wiring uses the default.
     */
    internal val fetcher: suspend (String) -> List<AudioStream> = defaultFetcher,
) {

    @Volatile private var initialized = false
    private val initLock = Any()

    /**
     * One-time NewPipe service registration. Cheap (registers the YouTube
     * service in a static map; no network). Double-checked-locking keeps
     * the hot path lock-free.
     */
    private fun ensureInitialized() {
        if (initialized) return
        synchronized(initLock) {
            if (initialized) return
            NewPipe.init(downloader, Localization("en", "US"))
            initialized = true
        }
    }

    /**
     * Resolves the highest-bitrate audio stream URL for [videoId].
     *
     * Bounded by [NEWPIPE_TIMEOUT_MS]. Any non-cancellation throw inside
     * the fetcher (network IO, NewPipe parse failure, missing format) is
     * rescued as null — the race in [PreviewUrlExtractor] then transparently
     * falls through to yt-dlp. [CancellationException] is rethrown so the
     * structured-concurrency teardown still works when InnerTube wins.
     */
    suspend fun extractStreamUrl(videoId: String): String? = withContext(Dispatchers.IO) {
        ensureInitialized()
        try {
            withTimeout(NEWPIPE_TIMEOUT_MS) {
                pickBestAudio(fetcher(videoId))
            }
        } catch (ce: CancellationException) {
            // TimeoutCancellationException is a subtype of CancellationException
            // but we treat it as a recoverable null rather than re-throwing —
            // the race must transparently fall through to yt-dlp on timeout,
            // not propagate the cancellation outward.
            if (ce is TimeoutCancellationException) {
                Log.w(TAG, "NewPipe extract timeout for $videoId after ${NEWPIPE_TIMEOUT_MS}ms")
                null
            } else {
                throw ce
            }
        } catch (t: Throwable) {
            Log.w(TAG, "NewPipe extract failed for $videoId: ${t.javaClass.simpleName} ${t.message}")
            null
        }
    }

    companion object {
        private const val TAG = "NewPipeStreamExtractor"
        const val NEWPIPE_TIMEOUT_MS = 15_000L

        /** Default production fetcher — calls real NewPipe. */
        private val defaultFetcher: suspend (String) -> List<AudioStream> = { videoId ->
            val url = "https://www.youtube.com/watch?v=$videoId"
            StreamInfo.getInfo(ServiceList.YouTube, url).audioStreams
        }

        /**
         * Picks the audio stream URL with the highest reported bitrate.
         *
         * Sort key prefers `averageBitrate` when reported (>0); otherwise
         * falls back to `bitrate` (peak). Some YouTube formats omit
         * averageBitrate, so naively sorting on averageBitrate would push
         * them to the bottom even when they're actually higher quality
         * than a low-but-reported alternative.
         *
         * **Tie-break:** on equal sort keys the **first** stream in
         * [streams] wins — matches [maxByOrNull]'s contract. Callers
         * that need codec preference should pre-sort the input.
         */
        internal fun pickBestAudio(streams: List<AudioStream>): String? =
            streams
                .maxByOrNull { it.averageBitrate.takeIf { b -> b > 0 } ?: it.bitrate }
                ?.content
    }
}
