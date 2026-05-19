package com.stash.core.media.streaming

import android.util.Log
import com.stash.core.data.db.entity.TrackEntity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Walks Stash's streaming-source roster in priority order and returns
 * the first match. Each resolver internally handles its own enablement
 * (captcha cookies, circuit-breaker state for non-streaming paths, etc.)
 * — null from one resolver just means "try the next one".
 *
 * Current order:
 *   1. [KennyyStreamResolver] — `kennyy.com.br`, no captcha gate. Almost
 *      always usable, primary stream source.
 *   2. [QobuzStreamResolver]  — `qobuz.squid.wtf`. Same Qobuz catalog,
 *      requires a user-pasted `captcha_verified_at` cookie. Auto-skipped
 *      when no cookie is set or the current cookie has been marked stale.
 *
 * Exposes the same `resolve(track) -> StreamUrl?` shape as the individual
 * resolvers so callers ([PlayerRepositoryImpl], [StreamingMediaSourceFactory],
 * [RefreshingDataSourceFactory], [PrefetchOrchestrator]) can swap in by
 * type without changing call-site logic.
 *
 * Result caching is the caller's responsibility — [StreamUrlCache] sits
 * on the player side and stores the first source's success keyed by track
 * id. Subsequent plays of the same track hit the cache and bypass the
 * registry entirely until the URL's `etsp` expires.
 */
@Singleton
class StreamSourceRegistry @Inject constructor(
    private val kennyy: KennyyStreamResolver,
    private val qobuz: QobuzStreamResolver,
) {

    /**
     * Try each resolver in priority order; return the first non-null
     * [StreamUrl]. Returns null when no source produced a match — caller
     * should surface this as [StreamRoutingResult.NotAvailable].
     */
    suspend fun resolve(track: TrackEntity): StreamUrl? {
        val resolvers = listOf(
            "kennyy" to kennyy::resolve,
            "squid" to qobuz::resolve,
        )
        for ((name, fn) in resolvers) {
            val result = runCatching { fn(track) }
                .onFailure { e ->
                    // Resolvers should never throw — they catch and return
                    // null. Defensive log so an unexpected throw from one
                    // source doesn't break the chain.
                    Log.w(TAG, "$name threw on resolve for ${track.id} '${track.title}'", e)
                }
                .getOrNull()
            if (result != null) {
                if (name != "kennyy") {
                    // Diagnostic: anything other than the primary source is
                    // a fallback path worth noticing. Helps explain "this
                    // track played but slowly" reports.
                    Log.i(TAG, "$name served ${track.id} '${track.title}' (kennyy missed)")
                }
                return result
            }
        }
        return null
    }

    private companion object {
        private const val TAG = "StreamSourceRegistry"
    }
}
