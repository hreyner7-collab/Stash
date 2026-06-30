package com.stash.core.media.streaming

import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import okhttp3.OkHttpClient
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Downloads the first [WARM_BYTES] of a resolved stream into the shared
 * @[StreamCache] [SimpleCache] ahead of playback — the same trick Spotify
 * uses to make tap-to-play feel instant: by the time the player asks for
 * the track, its opening seconds are already on disk, so audio starts
 * from local bytes while the rest streams in behind it.
 *
 * Invoked by the next-track prefetch watcher right after a stream URL
 * resolves. Uses the same track-identity cache key
 * ([streamCacheKey]) as playback's [StreamingMediaSourceFactory] read
 * path, so warmed bytes are found regardless of later URL rotation.
 *
 * Failures are logged at DEBUG and swallowed — warming is purely an
 * optimisation; playback works identically (just less instantly) without
 * it. The in-flight set deduplicates concurrent warms of the same key so
 * a rapid skip-around can't fire duplicate range requests.
 */
@OptIn(UnstableApi::class)
@Singleton
class StreamHeadWarmer @Inject constructor(
    @StreamCache private val streamCache: SimpleCache,
    @StreamingHttpClient private val streamingHttpClient: OkHttpClient,
) {
    private val inFlight: MutableSet<String> =
        Collections.newSetFromMap(ConcurrentHashMap())

    /**
     * Global cap on concurrent warm downloads. The warmer is fired from
     * many places (next-track prefetch, post-fill, all primer phases) —
     * without a cap, a long session can accumulate enough parallel
     * background transfers to compete with the LIVE stream for
     * bandwidth, degrading playback the longer the app runs. Two slots
     * keep warming brisk while the foreground stream always has
     * bandwidth headroom; callers queue here invisibly (fire-and-forget
     * coroutines), never blocking playback machinery.
     */
    private val warmSlots = Semaphore(WARM_CONCURRENCY)

    // Shares the pooled @StreamingHttpClient with live playback reads, so
    // a warm immediately before a track start leaves a hot connection the
    // player then reuses (timeouts live on the pooled client).
    private val cacheFactory = CacheDataSource.Factory()
        .setCache(streamCache)
        .setUpstreamDataSourceFactory(
            OkHttpDataSource.Factory(streamingHttpClient)
                .setUserAgent("Stash/0.9.26"),
        )
        .setCacheWriteDataSinkFactory(
            CacheDataSink.Factory().setCache(streamCache),
        )

    suspend fun warm(trackId: Long, stream: StreamUrl, bytes: Long = WARM_BYTES) {
        if (stream.placeholder) return // ~1 MB-gated fast-lane URL — not worth caching
        val scheme = Uri.parse(stream.url).scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return // antra file:// etc.
        val key = streamCacheKey(trackId, stream)
        if (!inFlight.add(key)) return
        try {
            warmSlots.withPermit { withContext(Dispatchers.IO) {
                // FULL_TRACK warms always run: CacheWriter skips spans that
                // are already on disk and only fetches the gaps, so a
                // repeat full-warm of a cached track costs no network.
                if (bytes != FULL_TRACK && streamCache.isCached(key, 0, bytes)) return@withContext
                val spec = DataSpec.Builder()
                    .setUri(Uri.parse(stream.url))
                    .setPosition(0)
                    .setLength(if (bytes == FULL_TRACK) C.LENGTH_UNSET.toLong() else bytes)
                    .setKey(key)
                    .build()
                CacheWriter(
                    cacheFactory.createDataSource(),
                    spec,
                    /* temporaryBuffer = */ null,
                    /* progressListener = */ null,
                ).cache()
                Log.d(TAG, "warmed head of track $trackId ($key)")
            } }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Log.d(TAG, "head-warm failed for track $trackId: ${e.message}")
        } finally {
            inFlight.remove(key)
        }
    }

    companion object {
        private const val TAG = "StreamHeadWarmer"

        /**
         * Default head size to pre-cache. 1 MB is ~6s of 24/96 FLAC or
         * ~50s of 160 kbps Opus — far past the player's start threshold,
         * so a warmed track begins playing entirely from disk. Used by the
         * broad warmers (library primer, post-fill) where hundreds of
         * tracks may be warmed per run.
         */
        const val WARM_BYTES = 1024L * 1024

        /**
         * Deep warm for the IMMEDIATE next-up track: 5 MB is an entire
         * 3-4 minute song at YouTube quality (~160 kbps Opus) or ~30s of
         * 24/96 FLAC — the whole next track is usually on disk before the
         * transition, making auto-advance and skip-next play entirely from
         * local bytes. Only ever in flight for 1-2 tracks at a time, so
         * the bandwidth cost stays modest.
         */
        const val NEXT_TRACK_WARM_BYTES = 5L * 1024 * 1024

        /**
         * Sentinel: cache the ENTIRE track (length-unset range request to
         * EOF). Used for the sync-to-cache pipeline — playlist-opener
         * tracks are silently downloaded in full right after a sync, so
         * tapping them reads purely from disk. The @[StreamCache] LRU
         * evictor bounds total footprint; no separate bookkeeping needed.
         */
        const val FULL_TRACK = -1L

        /** See [warmSlots]. */
        private const val WARM_CONCURRENCY = 2
    }
}
