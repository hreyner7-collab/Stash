package com.stash.core.media.engine

import com.google.common.truth.Truth.assertThat
import com.stash.core.model.Track
import org.junit.Test

/**
 * Pure-JVM tests pinning the queue model's index arithmetic — the part
 * of any queue implementation where off-by-ones breed. Every mutation
 * must keep [EngineQueue.currentIndex] pointing at the same PLAYING
 * track (or the sanest neighbour when that track itself is removed).
 */
class EngineQueueTest {

    private fun track(id: Long) = Track(id = id, title = "T$id", artist = "A")

    private fun queue(vararg ids: Long, current: Int = 0) =
        EngineQueue(ids.map(::track), current)

    @Test
    fun upcoming_returnsTracksAfterCurrent() {
        val q = queue(1, 2, 3, 4, current = 1)
        assertThat(q.upcoming(2).map { it.id }).containsExactly(3L, 4L).inOrder()
        assertThat(q.upcoming(10).map { it.id }).containsExactly(3L, 4L).inOrder()
        assertThat(queue(1, current = 0).upcoming(2)).isEmpty()
    }

    @Test
    fun withAddedNext_insertsAfterCurrent() {
        val q = queue(1, 2, 3, current = 1).withAddedNext(track(9))
        assertThat(q.tracks.map { it.id }).containsExactly(1L, 2L, 9L, 3L).inOrder()
        assertThat(q.currentIndex).isEqualTo(1)
    }

    @Test
    fun withRemoved_beforeCurrent_shiftsCurrentDown() {
        val q = queue(1, 2, 3, current = 2).withRemoved(0)
        assertThat(q.tracks.map { it.id }).containsExactly(2L, 3L).inOrder()
        assertThat(q.current?.id).isEqualTo(3L)
    }

    @Test
    fun withRemoved_currentItself_pointsAtSuccessor() {
        val q = queue(1, 2, 3, current = 1).withRemoved(1)
        assertThat(q.current?.id).isEqualTo(3L)
    }

    @Test
    fun withRemoved_lastTrackWhileCurrent_clampsToNewTail() {
        val q = queue(1, 2, current = 1).withRemoved(1)
        assertThat(q.current?.id).isEqualTo(1L)
    }

    @Test
    fun withRemoved_toEmpty_isSafe() {
        val q = queue(1, current = 0).withRemoved(0)
        assertThat(q.isEmpty).isTrue()
        assertThat(q.current).isNull()
    }

    @Test
    fun withMoved_currentTrackFollowsItsNewPosition() {
        val q = queue(1, 2, 3, current = 0).withMoved(0, 2)
        assertThat(q.tracks.map { it.id }).containsExactly(2L, 3L, 1L).inOrder()
        assertThat(q.current?.id).isEqualTo(1L)
    }

    @Test
    fun withMoved_acrossCurrent_keepsCurrentTrackStable() {
        // Move a later track to before the current one.
        val q = queue(1, 2, 3, current = 1).withMoved(2, 0)
        assertThat(q.tracks.map { it.id }).containsExactly(3L, 1L, 2L).inOrder()
        assertThat(q.current?.id).isEqualTo(2L)

        // Move an earlier track to after the current one.
        val q2 = queue(1, 2, 3, current = 1).withMoved(0, 2)
        assertThat(q2.tracks.map { it.id }).containsExactly(2L, 3L, 1L).inOrder()
        assertThat(q2.current?.id).isEqualTo(2L)
    }

    @Test
    fun withMoved_invalidIndices_noOp() {
        val q = queue(1, 2, current = 0)
        assertThat(q.withMoved(0, 5)).isEqualTo(q)
        assertThat(q.withMoved(5, 0)).isEqualTo(q)
        assertThat(q.withMoved(1, 1)).isEqualTo(q)
    }
}
