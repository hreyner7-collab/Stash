package com.stash.core.media.engine

import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import com.stash.core.media.service.StashPlaybackService.Companion.EXTRA_TRACK_ID
import com.stash.core.media.streaming.StreamHeadWarmer
import com.stash.core.media.streaming.StreamUrlCache
import com.stash.core.model.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * The redesigned playback engine core — a single-threaded command actor.
 *
 * **Design (clean slate).** Every mutation of playback state flows
 * through [commands], processed strictly one at a time by the actor
 * loop. The engine owns exactly one piece of state — an immutable
 * [EngineQueue] snapshot — and reconciles the Media3 timeline against
 * it after every change. This replaces the previous design's epoch
 * counters, supersede guards, and racing background jobs: a newer
 * command cancels the engine's own in-flight work ([fillJob],
 * [prefetchJob]) at the top of the loop iteration, so stale work is
 * impossible by construction rather than guarded against.
 *
 * **Start policy (re-derived, not copied).** A lazy-URI timeline was
 * tried and reverted in this app's history — Media3 can't auto-advance
 * into an item without a real URI. So the engine starts the TAPPED
 * track immediately via the fast lane (sub-second even during a
 * lossless outage; a gated placeholder is a valid start because the
 * recovery ladder upgrades it), then materializes the rest of the
 * timeline outward from the start position in queue order, fast lane
 * only. Upgrades (full-power URLs for the next-up) belong to the
 * prefetch pass, never the fill.
 *
 * **Recovery.** Delegated to [RecoveryPipeline] (the ladder: loader
 * retries → refresh seam → retry-in-place → bounded cascade).
 */
class StashEngine(
    private val scope: CoroutineScope,
    private val controllerProvider: suspend () -> MediaController?,
    private val router: PlayabilityRouter,
    private val recovery: RecoveryPipeline,
    private val streamUrlCache: StreamUrlCache,
    private val streamHeadWarmer: StreamHeadWarmer,
    private val onQueueChanged: (EngineQueue) -> Unit,
    private val onRefused: (PlayabilityRouter.Playable.Refused.Reason) -> Unit,
) {
    sealed class Command {
        data class SetQueue(val tracks: List<Track>, val startIndex: Int) : Command()
        data class AddNext(val track: Track) : Command()
        data class Append(val tracks: List<Track>) : Command()
        data class Remove(val index: Int) : Command()
        data class Move(val from: Int, val to: Int) : Command()
        data class JumpTo(val index: Int) : Command()
        data class TransitionedTo(val trackId: Long) : Command()
    }

    @Volatile
    var queue: EngineQueue = EngineQueue.EMPTY
        private set

    private val commands = Channel<Command>(capacity = Channel.UNLIMITED)
    private var fillJob: Job? = null
    private var prefetchJob: Job? = null

    init {
        scope.launch {
            for (command in commands) {
                try {
                    handle(command)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    Log.w(TAG, "engine command ${command::class.simpleName} failed: ${e.message}", e)
                }
            }
        }
    }

    fun submit(command: Command) {
        commands.trySend(command)
    }

    private suspend fun handle(command: Command) {
        val controller = controllerProvider() ?: return
        when (command) {
            is Command.SetQueue -> handleSetQueue(controller, command)
            is Command.AddNext -> {
                queue = queue.withAddedNext(command.track)
                onQueueChanged(queue)
                resolveAndInsert(controller, command.track, afterCurrent = true)
            }
            is Command.Append -> {
                queue = queue.withAppended(command.tracks)
                onQueueChanged(queue)
                command.tracks.forEach { resolveAndInsert(controller, it, afterCurrent = false) }
            }
            is Command.Remove -> {
                val target = queue.tracks.getOrNull(command.index) ?: return
                queue = queue.withRemoved(command.index)
                onQueueChanged(queue)
                timelineIndexOf(controller, target.id)?.let { controller.removeMediaItem(it) }
            }
            is Command.Move -> {
                val moved = queue.tracks.getOrNull(command.from) ?: return
                queue = queue.withMoved(command.from, command.to)
                onQueueChanged(queue)
                reconcileMove(controller, moved.id)
            }
            is Command.JumpTo -> {
                val target = queue.tracks.getOrNull(command.index) ?: return
                val slot = timelineIndexOf(controller, target.id)
                if (slot != null) {
                    queue = queue.withCurrent(command.index)
                    onQueueChanged(queue)
                    controller.seekToDefaultPosition(slot)
                    controller.play()
                } else {
                    // No timeline slot (unresolved straggler) — restart the
                    // queue anchored at it so the full machinery applies.
                    handleSetQueue(controller, Command.SetQueue(queue.tracks, command.index))
                }
                schedulePrefetch(controller)
            }
            is Command.TransitionedTo -> {
                val idx = queue.tracks.indexOfFirst { it.id == command.trackId }
                if (idx >= 0 && idx != queue.currentIndex) {
                    queue = queue.withCurrent(idx)
                    onQueueChanged(queue)
                }
                schedulePrefetch(controller)
            }
        }
    }

    /**
     * Start policy: route the tapped track FAST-lane first (instant or
     * ~1s), start audio, then fill the rest of the timeline outward and
     * kick the prefetch upgrade pass. Superseding work is cancelled up
     * front — a rapid second tap never queues behind the first.
     */
    private suspend fun handleSetQueue(controller: MediaController, cmd: Command.SetQueue) {
        fillJob?.cancel()
        prefetchJob?.cancel()

        val tracks = cmd.tracks
        val start = cmd.startIndex.coerceIn(0, (tracks.size - 1).coerceAtLeast(0))
        val tapped = tracks.getOrNull(start) ?: return

        val verdict = router.route(tapped, PlayabilityRouter.Lane.FAST)
            .let { v ->
                if (v is PlayabilityRouter.Playable.Refused &&
                    v.reason == PlayabilityRouter.Playable.Refused.Reason.NOT_AVAILABLE
                ) {
                    // Fast lane found nothing — one full-power attempt
                    // before declaring the tap dead.
                    router.route(tapped, PlayabilityRouter.Lane.FULL)
                } else {
                    v
                }
            }

        val startItem = when (verdict) {
            is PlayabilityRouter.Playable.Local -> verdict.mediaItem
            is PlayabilityRouter.Playable.Stream -> verdict.mediaItem
            is PlayabilityRouter.Playable.Refused -> {
                onRefused(verdict.reason)
                return
            }
        }

        queue = EngineQueue(tracks, start)
        onQueueChanged(queue)
        recovery.onUserTransport()
        controller.setMediaItems(listOf(startItem), 0, 0L)
        controller.prepare()
        controller.play()

        // Placeholder start -> line up the real URL in the background so
        // the recovery seam swaps it from cache before the gate bites.
        if ((verdict as? PlayabilityRouter.Playable.Stream)?.url?.placeholder == true) {
            scope.launch {
                runCatching { router.route(tapped, PlayabilityRouter.Lane.FULL) }
            }
        }

        // Materialize the rest of the timeline outward from the start.
        fillJob = scope.launch { fillTimeline(controller, tracks, start) }
        schedulePrefetch(controller)
    }

    private suspend fun fillTimeline(controller: MediaController, tracks: List<Track>, start: Int) {
        var resolvedCount = 0
        // Forward from the start anchor, then backward — forward tracks
        // are what auto-advance needs first.
        val forward = tracks.drop(start + 1)
        val backward = tracks.take(start).asReversed()
        for (track in forward) {
            val item = fastItemFor(track) ?: continue
            controller.addMediaItem(controller.mediaItemCount, item)
            resolvedCount++
        }
        for (track in backward) {
            val item = fastItemFor(track) ?: continue
            controller.addMediaItem(0, item)
            resolvedCount++
        }
        Log.i(TAG, "fill complete: $resolvedCount of ${tracks.size - 1} non-anchor tracks materialized")
        // Heads of the next few tracks onto disk so skips land local.
        queue.upcoming(POST_FILL_WARM_COUNT).forEach { track ->
            streamUrlCache.get(track.id)?.let { cached ->
                scope.launch { streamHeadWarmer.warm(track.id, cached) }
            }
        }
    }

    private suspend fun fastItemFor(track: Track): MediaItem? =
        when (val v = router.route(track, PlayabilityRouter.Lane.FAST)) {
            is PlayabilityRouter.Playable.Local -> v.mediaItem
            is PlayabilityRouter.Playable.Stream -> v.mediaItem
            is PlayabilityRouter.Playable.Refused -> null
        }

    /**
     * Prefetch pass: full-power upgrade for the immediate next-up (its
     * cost is spent either way at the transition), fast-lane seed for the
     * one after (covers the quick double-skip), deep head-warm for both.
     * Cancel-previous: only the latest position's prefetch ever runs.
     */
    private fun schedulePrefetch(controller: MediaController) {
        prefetchJob?.cancel()
        prefetchJob = scope.launch {
            queue.upcoming(2).forEachIndexed { offset, track ->
                if (track.isDownloaded) return@forEachIndexed
                val cached = streamUrlCache.get(track.id)
                if (cached != null && !cached.placeholder) {
                    streamHeadWarmer.warm(track.id, cached, StreamHeadWarmer.NEXT_TRACK_WARM_BYTES)
                    return@forEachIndexed
                }
                val lane = if (offset == 0) PlayabilityRouter.Lane.FULL else PlayabilityRouter.Lane.FAST
                val verdict = router.route(track, lane)
                if (verdict is PlayabilityRouter.Playable.Stream) {
                    swapOrInsert(controller, track, verdict.mediaItem)
                    if (!verdict.url.placeholder) {
                        streamHeadWarmer.warm(track.id, verdict.url, StreamHeadWarmer.NEXT_TRACK_WARM_BYTES)
                    }
                }
            }
        }
    }

    private suspend fun resolveAndInsert(controller: MediaController, track: Track, afterCurrent: Boolean) {
        val item = fastItemFor(track) ?: return
        val insertAt = if (afterCurrent) {
            controller.currentMediaItemIndex + 1
        } else {
            controller.mediaItemCount
        }
        controller.addMediaItem(insertAt.coerceIn(0, controller.mediaItemCount), item)
    }

    /** Replace [track]'s timeline slot with [item], or insert it right
     * after its logical predecessor's slot when it has none. */
    private fun swapOrInsert(controller: MediaController, track: Track, item: MediaItem) {
        val existing = timelineIndexOf(controller, track.id)
        if (existing != null) {
            controller.replaceMediaItem(existing, item)
            return
        }
        val logicalIdx = queue.tracks.indexOfFirst { it.id == track.id }
        val anchorId = queue.tracks.getOrNull(logicalIdx - 1)?.id
        val anchorSlot = anchorId?.let { timelineIndexOf(controller, it) }
        val insertAt = (anchorSlot?.plus(1) ?: (controller.currentMediaItemIndex + 1))
            .coerceIn(0, controller.mediaItemCount)
        controller.addMediaItem(insertAt, item)
    }

    private fun reconcileMove(controller: MediaController, movedTrackId: Long) {
        val slot = timelineIndexOf(controller, movedTrackId) ?: return
        // Desired timeline position: count how many of the moved track's
        // logical predecessors hold timeline slots.
        val logicalIdx = queue.tracks.indexOfFirst { it.id == movedTrackId }
        if (logicalIdx < 0) return
        var desired = 0
        for (i in 0 until logicalIdx) {
            if (timelineIndexOf(controller, queue.tracks[i].id) != null) desired++
        }
        if (slot != desired) controller.moveMediaItem(slot, desired)
    }

    private fun timelineIndexOf(controller: MediaController, trackId: Long): Int? {
        for (i in 0 until controller.mediaItemCount) {
            val item = controller.getMediaItemAt(i)
            val id = item.mediaMetadata.extras?.getLong(EXTRA_TRACK_ID)
                ?: item.mediaId.toLongOrNull()
            if (id == trackId) return i
        }
        return null
    }

    private companion object {
        const val TAG = "StashEngine"
        const val POST_FILL_WARM_COUNT = 8
    }
}
