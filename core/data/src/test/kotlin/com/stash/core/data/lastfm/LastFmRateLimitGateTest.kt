package com.stash.core.data.lastfm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The breaker that stops the app from hammering a Last.fm key that has
 * already returned error 29. Once throttled, more requests only prolong the
 * block, so we fail fast for an exponentially-growing cooldown and reopen
 * automatically once it elapses.
 */
class LastFmRateLimitGateTest {

    private val base = LastFmRateLimitGate.BASE_COOLDOWN_MS
    private val max = LastFmRateLimitGate.MAX_COOLDOWN_MS

    @Test
    fun `a fresh gate is closed`() {
        val gate = LastFmRateLimitGate()
        assertFalse(gate.isOpen(nowMs = 0L))
    }

    @Test
    fun `a rate-limit hit opens the gate for the base cooldown`() {
        val gate = LastFmRateLimitGate()
        gate.recordRateLimited(nowMs = 1_000L)

        assertTrue(gate.isOpen(nowMs = 1_000L))
        assertTrue(gate.isOpen(nowMs = 1_000L + base - 1))
        // Cooldown elapsed -> reopen automatically.
        assertFalse(gate.isOpen(nowMs = 1_000L + base))
    }

    @Test
    fun `consecutive hits back off exponentially`() {
        val gate = LastFmRateLimitGate()
        gate.recordRateLimited(nowMs = 0L) // 1st -> base
        gate.recordRateLimited(nowMs = 0L) // 2nd -> base*2
        assertTrue(gate.isOpen(nowMs = base * 2 - 1))
        assertFalse(gate.isOpen(nowMs = base * 2))

        gate.recordRateLimited(nowMs = 0L) // 3rd -> base*4
        assertTrue(gate.isOpen(nowMs = base * 4 - 1))
        assertFalse(gate.isOpen(nowMs = base * 4))
    }

    @Test
    fun `backoff is capped at the maximum cooldown`() {
        val gate = LastFmRateLimitGate()
        repeat(20) { gate.recordRateLimited(nowMs = 0L) }
        assertTrue(gate.isOpen(nowMs = max - 1))
        assertFalse(gate.isOpen(nowMs = max))
    }

    @Test
    fun `a success closes the gate and resets the backoff`() {
        val gate = LastFmRateLimitGate()
        gate.recordRateLimited(nowMs = 0L)
        gate.recordRateLimited(nowMs = 0L) // escalate to base*2

        gate.recordSuccess()
        assertFalse(gate.isOpen(nowMs = 0L))

        // After reset, the next single hit is back to the base cooldown,
        // not the escalated value.
        gate.recordRateLimited(nowMs = 0L)
        assertTrue(gate.isOpen(nowMs = base - 1))
        assertFalse(gate.isOpen(nowMs = base))
    }
}
