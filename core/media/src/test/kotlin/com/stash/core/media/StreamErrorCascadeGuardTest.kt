package com.stash.core.media

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class StreamErrorCascadeGuardTest {

    @Test
    fun firstError_allowsRecovery() {
        val guard = StreamErrorCascadeGuard(threshold = 3)
        val verdict = guard.onError()
        assertThat(verdict).isEqualTo(StreamErrorCascadeGuard.Verdict.Recover)
    }

    @Test
    fun thirdConsecutiveError_haltsCascade() {
        val guard = StreamErrorCascadeGuard(threshold = 3)
        guard.onError()
        guard.onError()
        val verdict = guard.onError()
        assertThat(verdict).isInstanceOf(StreamErrorCascadeGuard.Verdict.Halt::class.java)
    }

    @Test
    fun successfulPlaybackResetsCounter() {
        val guard = StreamErrorCascadeGuard(threshold = 3)
        guard.onError()
        guard.onError()
        guard.onPlaybackStarted()
        val verdict = guard.onError()
        assertThat(verdict).isEqualTo(StreamErrorCascadeGuard.Verdict.Recover)
    }

    @Test
    fun userTransportResetsCounter() {
        val guard = StreamErrorCascadeGuard(threshold = 3)
        guard.onError(); guard.onError()
        guard.onUserTransport()
        val verdict = guard.onError()
        assertThat(verdict).isEqualTo(StreamErrorCascadeGuard.Verdict.Recover)
    }

    @Test
    fun haltVerdictStaysHaltedUntilReset() {
        val guard = StreamErrorCascadeGuard(threshold = 3)
        guard.onError(); guard.onError(); guard.onError()  // Halt
        val verdict = guard.onError()
        // Still halted — 4th error after a halt also halts.
        assertThat(verdict).isInstanceOf(StreamErrorCascadeGuard.Verdict.Halt::class.java)
    }

    @Test
    fun customThreshold_halsOnSecondErrorWhenTwo() {
        val guard = StreamErrorCascadeGuard(threshold = 2)
        guard.onError()
        val verdict = guard.onError()
        assertThat(verdict).isInstanceOf(StreamErrorCascadeGuard.Verdict.Halt::class.java)
    }

    @Test
    fun thresholdZero_rejected() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            StreamErrorCascadeGuard(threshold = 0)
        }
    }

    @Test
    fun haltVerdict_carriesCount() {
        val guard = StreamErrorCascadeGuard(threshold = 3)
        guard.onError(); guard.onError()
        val verdict = guard.onError() as StreamErrorCascadeGuard.Verdict.Halt
        assertThat(verdict.consecutiveErrors).isEqualTo(3)
    }
}
