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
 * **Status (Task 3): skeleton only.** Only [pickBestAudio] is implemented.
 * Task 4 will add a suspend `extractStreamUrl(videoId)` that:
 *  - returns the highest-bitrate audio stream URL on success, or null on
 *    extractor throw / parse error / timeout,
 *  - propagates cancellation (required for the race's structured concurrency),
 *  - lazily runs `NewPipe.init()` once, then takes a `@Volatile` fast path.
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
