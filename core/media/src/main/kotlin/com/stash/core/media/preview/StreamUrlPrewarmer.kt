package com.stash.core.media.preview

import android.util.Log
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.prefs.StreamingPreference
import com.stash.core.media.streaming.StreamSourceRegistry
import com.stash.core.media.streaming.StreamUrlCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pre-resolves a song's **playback** stream URL (via the fast Piped/iOS path)
 * and lets the resolver cache it in `StreamUrlCache` BEFORE the user taps —
 * so the tap is a cache hit with zero network = truly instant.
 *
 * The key insight: `PlayerRepository.playFromStream` builds a transient track
 * whose id is `videoId.hashCode()`, and `StreamSourceRegistry.resolve` caches
 * every success under that id. So warming with the same id pre-fills exactly
 * the entry the tap reads.
 *
 * Used by:
 *  - search/artist row composables (warm what's on screen), and
 *  - [LibraryWarmupService] (warm the whole library on app open).
 *
 * Cheap by design: `allowYtDlp = false` so warming never spends the slow
 * yt-dlp slot — if the fast sources miss, we simply don't pre-cache and the
 * tap does a normal resolve. Concurrency-capped + deduped so it can't flood
 * the Piped pool into rate-limits.
 */
@Singleton
class StreamUrlPrewarmer @Inject constructor(
    private val streamResolver: StreamSourceRegistry,
    private val streamUrlCache: StreamUrlCache,
    private val streamingPreference: StreamingPreference,
) {
    /** Already-fresh, full (non-placeholder) cache entry → nothing to warm. */
    private fun alreadyWarm(id: Long): Boolean =
        streamUrlCache.get(id)?.let { !it.placeholder } == true
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val concurrency = Semaphore(MAX_CONCURRENT)

    /** Warm the stream URL for a search/artist row. Idempotent per videoId. */
    fun warm(
        videoId: String,
        title: String,
        artist: String,
        durationSeconds: Double = 0.0,
        thumbnailUrl: String? = null,
    ) {
        if (videoId.isBlank() || alreadyWarm(videoId.hashCode().toLong())) return
        if (!inFlight.add(videoId)) return
        scope.launch {
            try {
                if (!streamingPreference.current()) return@launch
                concurrency.withPermit {
                    val entity = TrackEntity(
                        id = videoId.hashCode().toLong(),
                        title = title,
                        artist = artist,
                        durationMs = (durationSeconds * 1000).toLong(),
                        youtubeId = videoId,
                        albumArtUrl = thumbnailUrl,
                        isStreamable = true,
                        isDownloaded = false,
                    )
                    // preferFast → Piped/iOS; allowYtDlp=false keeps it cheap.
                    // A success is cached by the registry under entity.id,
                    // which is what the tap looks up.
                    runCatching {
                        streamResolver.resolve(entity, allowYtDlp = false, preferFast = true)
                    }.onFailure { Log.w(TAG, "prewarm failed for $videoId: ${it.message}") }
                }
            } finally {
                inFlight.remove(videoId)
            }
        }
    }

    /** Warm a library [TrackEntity] (used by the on-open library warmer). */
    suspend fun warmEntity(entity: TrackEntity) {
        val ytId = entity.youtubeId?.takeIf { it.isNotBlank() } ?: return
        if (alreadyWarm(entity.id) || !inFlight.add(ytId)) return
        try {
            concurrency.withPermit {
                // Resolve against the library row's own id so a warehoused /
                // already-keyed entry caches under the same id the player uses.
                runCatching { streamResolver.resolve(entity, allowYtDlp = false, preferFast = true) }
            }
        } finally {
            inFlight.remove(ytId)
        }
    }

    private companion object {
        const val TAG = "StreamUrlPrewarmer"
        const val MAX_CONCURRENT = 6
    }
}
