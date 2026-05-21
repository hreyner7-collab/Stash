package com.stash.data.download.preview

import org.schabi.newpipe.extractor.stream.AudioStream
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
 *
 * Skeleton-only at this task — full `extractStreamUrl` lands in Task 4.
 */
@Singleton
class NewPipeStreamExtractor @Inject constructor(
    // Held for Hilt graph construction; consumed by extractStreamUrl in
    // Task 4 when the lazy NewPipe.init() block lands. The "unused" warning
    // here is expected for the skeleton-only state of this class.
    @Suppress("unused") private val downloader: OkHttpNewPipeDownloader,
) {

    companion object {
        /**
         * Picks the audio stream URL with the highest reported bitrate.
         *
         * Sort key prefers `averageBitrate` when reported (>0); otherwise
         * falls back to `bitrate` (peak). Some YouTube formats omit
         * averageBitrate, so naively sorting on averageBitrate would
         * push them to the bottom even when they're actually higher
         * quality than a low-but-reported alternative.
         */
        internal fun pickBestAudio(streams: List<AudioStream>): String? =
            streams
                .maxByOrNull { it.averageBitrate.takeIf { b -> b > 0 } ?: it.bitrate }
                ?.content
    }
}
