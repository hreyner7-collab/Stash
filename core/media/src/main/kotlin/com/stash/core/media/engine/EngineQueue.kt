package com.stash.core.media.engine

import com.stash.core.model.Track

/**
 * The engine's single source of truth for "what the user is listening
 * to" — an immutable snapshot of the logical queue. Every mutation
 * returns a new snapshot; the engine actor swaps its reference and
 * reconciles the player timeline against it. There is no other queue
 * state anywhere in the engine, which is what makes the design
 * race-free: the actor processes one command at a time against one
 * value.
 *
 * Indices are LOGICAL (positions in [tracks]); the reconciler owns the
 * mapping to Media3 timeline slots. UI layers therefore never see
 * timeline-index aliasing, which was a recurring bug class in the
 * previous design (logical vs timeline index spaces drifting apart).
 */
data class EngineQueue(
    val tracks: List<Track>,
    val currentIndex: Int,
) {
    init {
        require(tracks.isEmpty() || currentIndex in tracks.indices) {
            "currentIndex $currentIndex out of bounds for ${tracks.size} tracks"
        }
    }

    val isEmpty: Boolean get() = tracks.isEmpty()
    val current: Track? get() = tracks.getOrNull(currentIndex)
    val next: Track? get() = tracks.getOrNull(currentIndex + 1)

    /** Tracks from the current position forward (exclusive) — the
     * prefetch planner's working set. */
    fun upcoming(count: Int): List<Track> =
        tracks.drop(currentIndex + 1).take(count)

    fun withCurrent(index: Int): EngineQueue =
        if (tracks.isEmpty()) this else copy(currentIndex = index.coerceIn(tracks.indices))

    /** Insert [track] immediately after the current track. */
    fun withAddedNext(track: Track): EngineQueue =
        copy(
            tracks = buildList {
                addAll(tracks)
                add((currentIndex + 1).coerceAtMost(tracks.size), track)
            },
        )

    /** Append [newTracks] at the tail. */
    fun withAppended(newTracks: List<Track>): EngineQueue =
        if (newTracks.isEmpty()) this else copy(tracks = tracks + newTracks)

    /** Remove the track at logical [index]; the current index shifts to
     * keep pointing at the same playing track when possible. */
    fun withRemoved(index: Int): EngineQueue {
        if (index !in tracks.indices) return this
        val newTracks = tracks.filterIndexed { i, _ -> i != index }
        if (newTracks.isEmpty()) return EngineQueue(emptyList(), 0)
        val newCurrent = when {
            index < currentIndex -> currentIndex - 1
            index == currentIndex -> currentIndex.coerceAtMost(newTracks.size - 1)
            else -> currentIndex
        }
        return EngineQueue(newTracks, newCurrent)
    }

    /** Move the track at [from] to [to]; the current index follows the
     * playing track wherever it lands. */
    fun withMoved(from: Int, to: Int): EngineQueue {
        if (from !in tracks.indices || to !in tracks.indices || from == to) return this
        val mutable = tracks.toMutableList()
        val moved = mutable.removeAt(from)
        mutable.add(to, moved)
        val newCurrent = when {
            currentIndex == from -> to
            from < currentIndex && to >= currentIndex -> currentIndex - 1
            from > currentIndex && to <= currentIndex -> currentIndex + 1
            else -> currentIndex
        }
        return EngineQueue(mutable, newCurrent)
    }

    companion object {
        val EMPTY = EngineQueue(emptyList(), 0)
    }
}
