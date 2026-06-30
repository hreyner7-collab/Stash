package com.stash.core.media.streaming

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import com.stash.core.data.db.dao.TrackDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * HTTP response codes that signal a stale signed-URL and trigger a
 * re-resolve. 403 is what the Qobuz/Akamai CDN returns past `etsp`; 410
 * is the spec-compliant "gone" code some upstreams emit. Other non-2xx
 * codes (404, 5xx) propagate as fatal so the player surfaces them.
 */
private val REFRESH_TRIGGERS = setOf(403, 410)

/**
 * Per-track [DataSource] wrapper that transparently re-resolves the
 * upstream signed URL when the CDN returns 403/410 mid-stream. This is
 * the seam that makes pause-and-resume-hours-later work: if the user
 * pauses a streamed track and resumes past `etsp`, the next HTTP read
 * here catches the 403, hits Kennyy for a fresh URL, and re-opens at
 * the same byte offset.
 *
 * Kotlin delegation (`DataSource by inner`) forwards every member to
 * the wrapped HTTP source except [open], which we override to install
 * the refresh logic.
 *
 * `setPosition(N)` on [DataSpec.Builder] is the byte offset (used by
 * Media3 for HTTP `Range:` requests, not the time offset). The retry
 * preserves it implicitly via `spec.buildUpon().setUri(...)` so the
 * fresh URL resumes at exactly the byte we failed on.
 *
 * **Construction:** This class is NOT Hilt-injected — it's instantiated
 * per-track inside a `StreamingMediaSourceFactory` (Task 7) which IS
 * a Hilt singleton and threads through the injected `resolver` /
 * `cache` / `trackDao` here.
 *
 * **Re-entrance / runBlocking safety:** Media3 calls [open] from its
 * own loader thread (never main), so [runBlocking] is safe here. We
 * use **one** [runBlocking] for both the DAO lookup and the resolver
 * call — splitting it into two could re-enter Room's connection pool
 * or Kennyy's [com.stash.data.download.lossless.AggregatorRateLimiter]
 * and deadlock.
 */
@OptIn(UnstableApi::class)
class RefreshingDataSource(
    private val inner: HttpDataSource,
    private val resolver: StreamSourceRegistry,
    private val cache: StreamUrlCache,
    private val trackId: Long,
    private val trackDao: TrackDao,
    private val upgradeScope: CoroutineScope? = null,
) : DataSource by inner {

    override fun open(spec: DataSpec): Long {
        return try {
            inner.open(spec)
        } catch (e: HttpDataSource.InvalidResponseCodeException) {
            if (e.responseCode !in REFRESH_TRIGGERS) throw e
            // Cache first: the prefetch / placeholder-upgrade paths may
            // already have resolved a fresh full-playback URL for this
            // track. Using it makes the swap instant instead of blocking
            // the loader thread on a live resolve.
            // Guards: never a placeholder (it would 403 again ~1 MB in),
            // never the URL that just failed, and only while unexpired.
            val cachedFresh = cache.get(trackId)?.takeUnless {
                it.placeholder || it.url == spec.uri.toString()
            }
            // Live fallback is FAST LANE ONLY (no yt-dlp): this runs
            // runBlocking on Media3's loader thread, and a full-chain
            // resolve can sit 8-25s+ in the serialized yt-dlp FIFO —
            // long enough to drain the playback buffer mid-song (the
            // on-device "third song loads forever" stall, 2026-06-11:
            // a placeholder died at its 1 MB gate and the refresh queued
            // behind library-primer extractions). The fast lane answers
            // in ~1-3s; if it can only offer another placeholder, that
            // still buys ~1 MB of immediate audio at the failed offset.
            // Single runBlocking for both lookups — avoids re-entrance
            // hazards from two independent runBlocking calls sharing the
            // loader thread with Room's connection pool + Kennyy's
            // rate-limiter.
            val fresh = cachedFresh ?: runBlocking {
                val track = trackDao.getById(trackId) ?: return@runBlocking null
                resolver.resolve(track, allowYtDlp = false)
                    // The fast lane re-serving the exact URL that just
                    // died is not a recovery — treat as miss.
                    ?.takeUnless { it.url == spec.uri.toString() }
            } ?: throw e
            cache.put(trackId, fresh)
            // If the sync recovery is itself a gated placeholder, line up
            // the REAL URL in the background so the next 403 (≤1 MB away)
            // swaps instantly from cache — the slow lane never holds up
            // the loader thread. The registry's chokepoint caching stores
            // the result even if this source instance is long gone.
            if (fresh.placeholder) {
                upgradeScope?.launch {
                    runCatching {
                        trackDao.getById(trackId)?.let { resolver.resolve(it, allowYtDlp = true) }
                    }
                }
            }
            // Recompute the cache key alongside the URL: if the re-resolve
            // switched source/format (kennyy FLAC -> youtube AAC during an
            // outage), the fresh bytes must cache under their own key
            // rather than appending a different encoding to the old
            // entry. Only when the original spec carried a custom key —
            // a null key means the item predates track-keyed caching.
            val newSpec = spec.buildUpon()
                .setUri(Uri.parse(fresh.url))
                .apply { if (spec.key != null) setKey(streamCacheKey(trackId, fresh)) }
                .build()
            inner.open(newSpec)
        }
    }
}

/**
 * Per-track factory for [RefreshingDataSource]. Constructed inside
 * `StreamingMediaSourceFactory.create(trackId)` (Task 7) which captures
 * the trackId in this factory closure so every [DataSource] that
 * Media3 spawns for the same track shares the same refresh policy.
 *
 * Stateless beyond the captured trackId — the cache / resolver / DAO
 * references come from the Hilt-injected singleton above.
 */
@OptIn(UnstableApi::class)
class RefreshingDataSourceFactory(
    private val innerFactory: HttpDataSource.Factory,
    private val resolver: StreamSourceRegistry,
    private val cache: StreamUrlCache,
    private val trackDao: TrackDao,
    private val trackId: Long,
    private val upgradeScope: CoroutineScope? = null,
) : DataSource.Factory {

    override fun createDataSource(): DataSource = RefreshingDataSource(
        inner = innerFactory.createDataSource(),
        resolver = resolver,
        cache = cache,
        trackId = trackId,
        trackDao = trackDao,
        upgradeScope = upgradeScope,
    )
}
