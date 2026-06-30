package com.stash.core.media.engine

import android.util.Log
import androidx.media3.session.MediaController
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.media.StreamErrorCascadeGuard
import com.stash.core.media.StreamingHaltedEvent
import com.stash.core.media.service.StashPlaybackService.Companion.EXTRA_TRACK_ID
import com.stash.core.media.streaming.StreamSourceRegistry
import com.stash.core.media.streaming.StreamUrlCache
import com.stash.core.media.streaming.streamCacheKey

/**
 * The playback engine's error-recovery state machine — every streamed-
 * track failure flows through here in a fixed, explicit order:
 *
 *  1. (Below this class) Media3's load-error policy absorbs transient
 *     network jitter with 6 in-loader retries + exponential backoff.
 *  2. (Below this class) the 403-refresh seam swaps expired URLs at the
 *     failing byte offset without surfacing an error at all.
 *  3. **Retry-in-place** (here): the FIRST surfaced failure of a track
 *     re-resolves a fresh URL (fast lane; cache invalidated first) and
 *     resumes the SAME track at the SAME position. Transient drops and
 *     stale URLs never cost the listener the song.
 *  4. **Bounded cascade** (here): a track that failed twice is skipped;
 *     [StreamErrorCascadeGuard] halts playback after [threshold]
 *     consecutive failures so an upstream outage can't machine-gun the
 *     queue.
 *
 * Extracted from `PlayerRepositoryImpl`'s listener as part of the engine
 * redesign so the whole ladder is one testable unit with no UI-state
 * entanglement. Construction is manual (not Hilt): the owner supplies
 * [onHalted] to bridge halt verdicts into its user-facing event stream.
 */
class RecoveryPipeline(
    private val streamUrlCache: StreamUrlCache,
    private val trackDao: TrackDao,
    private val streamResolver: StreamSourceRegistry,
    private val onHalted: (StreamingHaltedEvent) -> Unit,
) {
    private val cascadeGuard = StreamErrorCascadeGuard()

    /**
     * The track that already used its one in-place retry for the current
     * error episode. Reset on healthy playback, so a track that errors
     * again much later (e.g. its URL expiring an hour on) earns a fresh
     * retry rather than being insta-skipped.
     */
    @Volatile
    private var lastErrorRetryTrackId = -1L

    /** Claims the single retry credit for [trackId]. True exactly once
     * per track per error episode. */
    fun claimRetry(trackId: Long): Boolean {
        if (trackId <= 0L) return false
        if (trackId == lastErrorRetryTrackId) return false
        lastErrorRetryTrackId = trackId
        return true
    }

    /** Playback is audibly healthy — re-arm both the retry credit and
     * the cascade counter. */
    fun onPlaybackHealthy() {
        cascadeGuard.onPlaybackStarted()
        lastErrorRetryTrackId = -1L
    }

    /** Deliberate user transport (next/prev/seek/play) re-arms the
     * cascade counter. */
    fun onUserTransport() {
        cascadeGuard.onUserTransport()
    }

    /**
     * Stage-3 recovery: re-resolve a FRESH URL for the failing track
     * (cache invalidated first — the failing URL must not be re-served)
     * and resume it at the position where it died, without ever leaving
     * the track. Fast lane only: this races a draining playback buffer,
     * and the serialized yt-dlp slot can take 8-25s. If no fresh URL can
     * be had (or the track has no timeline slot), escalates to the
     * bounded cascade so a real outage still gets skip/halt behaviour —
     * never an unbounded retry loop.
     */
    suspend fun retryInPlace(
        controller: MediaController,
        trackId: Long,
        failingTitle: String?,
    ) {
        val positionMs = controller.currentPosition.coerceAtLeast(0L)
        streamUrlCache.invalidate(trackId)
        val entity = trackDao.getById(trackId)
        val resolved = entity?.let {
            runCatching {
                streamResolver.resolve(it, allowYouTube = true, allowYtDlp = false)
            }.getOrNull()
        }
        val idx = if (resolved != null) timelineIndexOfTrackId(controller, trackId) else -1
        if (resolved == null || idx < 0) {
            Log.w(TAG, "retry-in-place: no fresh URL for '$failingTitle' — falling back to cascade")
            escalate(controller, failingTitle)
            return
        }
        val refreshed = controller.getMediaItemAt(idx).buildUpon()
            .setUri(resolved.url)
            .setCustomCacheKey(streamCacheKey(trackId, resolved))
            .build()
        controller.replaceMediaItem(idx, refreshed)
        controller.seekTo(idx, positionMs)
        controller.prepare()
        controller.play()
        Log.i(
            TAG,
            "retry-in-place: '$failingTitle' resumed at ${positionMs}ms with fresh ${resolved.origin} URL",
        )
    }

    /** Stage-4 recovery: bounded skip — or halt (pause + notify) once the
     * cascade threshold is reached. */
    fun escalate(controller: MediaController?, failingTitle: String?) {
        when (val v = cascadeGuard.onError()) {
            StreamErrorCascadeGuard.Verdict.Recover -> controller?.let { skipPastBroken(it) }
            is StreamErrorCascadeGuard.Verdict.Halt -> {
                controller?.pause()
                onHalted(
                    StreamingHaltedEvent(
                        failingTitle = failingTitle,
                        consecutiveErrorCount = v.consecutiveErrors,
                    ),
                )
            }
        }
    }

    /** Skip past a broken item — or stop cleanly at end of queue rather
     * than looping on it. Also used directly for corrupt LOCAL files
     * (per-track failures that must not arm the streaming cascade). */
    fun skipPastBroken(controller: MediaController) {
        if (controller.hasNextMediaItem()) {
            controller.seekToNextMediaItem()
            controller.prepare()
            controller.play()
        } else {
            controller.stop()
        }
    }

    private fun timelineIndexOfTrackId(controller: MediaController, trackId: Long): Int {
        for (i in 0 until controller.mediaItemCount) {
            val item = controller.getMediaItemAt(i)
            val id = item.mediaMetadata.extras?.getLong(EXTRA_TRACK_ID)
                ?: item.mediaId.toLongOrNull()
            if (id == trackId) return i
        }
        return -1
    }

    private companion object {
        const val TAG = "StashPlayer"
    }
}
