package com.stash.core.media.streaming

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory TTL cache for resolved stream URLs, keyed by track id.
 *
 * Entries are valid while [StreamUrl.expiresAtMs] is strictly greater
 * than the current wall clock (`nowMsProvider()`); at or past the
 * expiry instant the entry is evicted lazily on read. There is no
 * background sweeper — stale entries linger until they're next looked
 * up or explicitly invalidated.
 *
 * Concurrency: Media3 reads URLs on a player thread while UI/ViewModel
 * code writes them on main; tests drive the clock on the calling
 * thread. [ConcurrentHashMap] gives atomic per-key reads/writes, which
 * is the only invariant we need (no compound transactions cross keys).
 *
 * The [nowMsProvider] seam exists so tests can drive time
 * deterministically without `Thread.sleep`. Production uses the
 * default [System.currentTimeMillis] reference — Dagger fills the
 * remaining constructor parameter and is happy with the Kotlin
 * default for the lambda.
 */
@Singleton
class StreamUrlCache @Inject constructor(
    private val nowMsProvider: () -> Long = System::currentTimeMillis,
) {
    private val cache = ConcurrentHashMap<Long, StreamUrl>()

    fun get(trackId: Long): StreamUrl? {
        val entry = cache[trackId] ?: return null
        return if (entry.expiresAtMs > nowMsProvider()) {
            entry
        } else {
            cache.remove(trackId)
            null
        }
    }

    fun put(trackId: Long, url: StreamUrl) {
        cache[trackId] = url
    }

    fun invalidate(trackId: Long) {
        cache.remove(trackId)
    }
}
