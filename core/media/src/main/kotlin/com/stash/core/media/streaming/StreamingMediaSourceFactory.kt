package com.stash.core.media.streaming

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import okhttp3.OkHttpClient
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import com.stash.core.data.db.dao.TrackDao
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Composed [MediaSource.Factory] for online-streaming playback. Builds the
 * full read pipeline ExoPlayer consumes when sourcing bytes for a streamed
 * track:
 *
 * ```
 * CacheDataSource           <- writes hits into the shared [SimpleCache]
 *  └─ RefreshingDataSource  <- catches 403/410, re-resolves via Kennyy
 *      └─ DefaultHttpDataSource  <- actual HTTP GET against the signed CDN URL
 * ```
 *
 * The cache layer is on top so a partial download survives a 403 refresh
 * mid-stream — bytes already cached before the URL went stale aren't re-
 * fetched, and the refresh only kicks in for the still-missing tail.
 *
 * **Per-track factory via singleton + method.** The static deps
 * ([streamCache], [resolver], [urlCache], [trackDao]) are Hilt-injected
 * application-scope singletons; the only per-playback variable is
 * [create]'s `trackId`. Each call to [create] returns a fresh
 * [MediaSource.Factory] that closes over that id so Media3 can spawn
 * multiple [androidx.media3.datasource.DataSource] instances for the
 * same track and they'll all share the refresh policy.
 *
 * **CacheDataSource flags:**
 * - [CacheDataSource.FLAG_BLOCK_ON_CACHE]: if two readers ask for the same
 *   byte range concurrently, the second blocks until the first writes —
 *   avoids duplicate HTTP fetches for the same range.
 * - [CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR]: if cache I/O throws,
 *   fall through to the network rather than surfacing the cache error as
 *   a playback failure.
 *
 * **User-Agent** uses the same hard-coded "Stash/x.y.z" pattern as
 * [com.stash.core.media.preview.di.PreviewCacheModule] — the `core/media`
 * module doesn't generate a [BuildConfig] with `VERSION_NAME`, and adding
 * one just for this header would be scope creep beyond Task 7. Bump in
 * lock-step with `app/build.gradle.kts versionName` when releasing.
 *
 * Consumed by Task 11 ([com.stash.core.media.PlayerRepository]) and
 * Task 12 ([StashPlaybackService]) — both inject this singleton and call
 * [create] inside their per-track [androidx.media3.exoplayer.source.MediaSource]
 * construction.
 */
@OptIn(UnstableApi::class)
@Singleton
class StreamingMediaSourceFactory @Inject constructor(
    @StreamCache private val streamCache: SimpleCache,
    private val resolver: StreamSourceRegistry,
    private val urlCache: StreamUrlCache,
    private val trackDao: TrackDao,
    @StreamingHttpClient private val streamingHttpClient: OkHttpClient,
) {
    /** Off-loader-thread lane for the 403-refresh seam's slow-lane URL
     * upgrades (see [RefreshingDataSource]) — the loader thread itself
     * must never wait on the serialized yt-dlp slot. */
    private val refreshUpgradeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun create(trackId: Long): MediaSource.Factory {
        // OkHttp-backed source (was DefaultHttpDataSource/HttpURLConnection):
        // the shared @StreamingHttpClient pool keeps TCP+TLS connections to
        // recently-used CDN hosts warm across tracks, removing ~100-400 ms
        // of handshake setup from many track starts. Timeouts live on the
        // pooled client (StreamingHttpModule).
        val httpFactory = OkHttpDataSource.Factory(streamingHttpClient)
            .setUserAgent("Stash/0.9.26")
        val refreshFactory = RefreshingDataSourceFactory(
            innerFactory = httpFactory,
            resolver = resolver,
            cache = urlCache,
            trackDao = trackDao,
            trackId = trackId,
            upgradeScope = refreshUpgradeScope,
        )
        val cachedFactory = CacheDataSource.Factory()
            .setCache(streamCache)
            .setUpstreamDataSourceFactory(refreshFactory)
            .setCacheWriteDataSinkFactory(
                CacheDataSink.Factory().setCache(streamCache),
            )
            .setFlags(
                CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR or
                    CacheDataSource.FLAG_BLOCK_ON_CACHE,
            )
        return DefaultMediaSourceFactory(cachedFactory)
            // Double the loader's internal retry budget (default 3 -> 6,
            // with Media3's built-in exponential backoff between attempts:
            // min((errorCount - 1) * 1000, 5000) ms). Transient network
            // jitter mid-stream — a dropped chunk, a momentary Wi-Fi/cell
            // handover — now gets absorbed INSIDE the loader, invisibly,
            // before it can surface as onPlayerError and engage the
            // repo-level retry-in-place / cascade machinery. Hard failures
            // (dead placeholder URLs, real outages) still surface promptly:
            // the 403-refresh seam sits BELOW this policy in the stack, so
            // refreshable expiries never burn loader retries at all.
            .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(6))
    }
}
