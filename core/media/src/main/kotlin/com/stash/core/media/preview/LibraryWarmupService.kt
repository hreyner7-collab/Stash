package com.stash.core.media.preview

import android.util.Log
import com.stash.core.data.db.dao.ListeningEventDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.prefs.StreamingPreference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Everything ready the moment you open the app." On launch this walks the
 * streamable library and pre-resolves each song's Piped URL into the stream
 * cache (via [StreamUrlPrewarmer]) so tapping is instant.
 *
 * Done sustainably, NOT all-at-once:
 *  - **Priority order:** recently-played first, then the rest of the library,
 *    so what you're most likely to play is ready soonest.
 *  - **Throttled:** the prewarmer's own concurrency cap (6) + the Piped health
 *    ranking mean it never floods the servers into rate-limiting (which would
 *    make everything slower).
 *  - **Kept fresh:** Piped URLs age out in a few hours, so it re-warms on an
 *    interval — what's cached stays playable.
 *
 * Downloaded tracks are skipped (they already play locally); the permanent
 * "instant forever" store is still the OneDrive warehouse.
 */
@Singleton
class LibraryWarmupService @Inject constructor(
    private val trackDao: TrackDao,
    private val listeningEventDao: ListeningEventDao,
    private val prewarmer: StreamUrlPrewarmer,
    private val streamingPreference: StreamingPreference,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean(false)

    /** Call once from `StashApplication.onCreate`. Idempotent. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            runCatching { warmLoop() }.onFailure { Log.w(TAG, "library warmup failed: ${it.message}") }
        }
    }

    private suspend fun warmLoop() {
        while (currentCoroutineContext().isActive) {
            if (streamingPreference.current()) {
                val ordered = priorityOrderedLibrary()
                if (ordered.isNotEmpty()) {
                    Log.d(TAG, "library warmup: pre-resolving ${ordered.size} streamable tracks")
                    for (entity in ordered) {
                        currentCoroutineContext().ensureActive()
                        // Launch so the prewarmer's 6-permit semaphore sets the
                        // real parallelism; hundreds of cheap queued jobs are fine.
                        scope.launch { prewarmer.warmEntity(entity) }
                    }
                }
            }
            delay(REWARM_INTERVAL_MS)
        }
    }

    private suspend fun priorityOrderedLibrary(): List<TrackEntity> {
        val all = trackDao.getAllByDateAdded().first()
            .filter { !it.isDownloaded && !it.youtubeId.isNullOrBlank() }
        if (all.isEmpty()) return emptyList()
        val byId = all.associateBy { it.id }
        val recentIds = runCatching {
            listeningEventDao.getTrackIdsPlayedSince(System.currentTimeMillis() - RECENT_WINDOW_MS)
        }.getOrDefault(emptyList())
        val recent = recentIds.mapNotNull { byId[it] }
        return (recent + all).distinctBy { it.id }.take(MAX_WARM)
    }

    private companion object {
        const val TAG = "LibraryWarmup"

        /** Re-warm before Piped URLs age out (they last a few hours). */
        const val REWARM_INTERVAL_MS = 45L * 60 * 1000

        /** Look-back for "recently played" priority seeds. */
        const val RECENT_WINDOW_MS = 30L * 24 * 60 * 60 * 1000

        /** Upper bound per pass so a huge library doesn't warm endlessly /
         *  burn data — recent + most of a typical library fits comfortably. */
        const val MAX_WARM = 250
    }
}
