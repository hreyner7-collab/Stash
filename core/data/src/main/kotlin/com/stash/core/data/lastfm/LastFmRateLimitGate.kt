package com.stash.core.data.lastfm

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.pow

/**
 * Process-wide circuit breaker for Last.fm rate-limit responses (error 29 /
 * HTTP 429). The Last.fm API key is shared across every install, so when it
 * gets throttled, continuing to fire requests only prolongs the block. This
 * gate makes the client fail fast — skipping the network entirely — for an
 * exponentially-growing cooldown after each rate-limit hit, then reopens
 * automatically once the cooldown elapses. A successful response resets it.
 *
 * Time is passed in (`nowMs`) rather than read from the clock so the logic is
 * deterministic and unit-testable.
 */
@Singleton
class LastFmRateLimitGate @Inject constructor() {

    @Volatile
    private var openUntilMs: Long = 0L

    @Volatile
    private var consecutiveHits: Int = 0

    /** True while the breaker is open — callers should skip the network. */
    fun isOpen(nowMs: Long): Boolean = nowMs < openUntilMs

    /**
     * Record a rate-limit response. Opens the breaker for a cooldown that
     * doubles with each consecutive hit (base, 2×, 4×, …), capped at
     * [MAX_COOLDOWN_MS].
     */
    @Synchronized
    fun recordRateLimited(nowMs: Long) {
        consecutiveHits++
        val cooldown = (BASE_COOLDOWN_MS.toDouble() * 2.0.pow(consecutiveHits - 1))
            .toLong()
            .coerceIn(BASE_COOLDOWN_MS, MAX_COOLDOWN_MS)
        openUntilMs = nowMs + cooldown
    }

    /** Record a successful response: closes the breaker and resets backoff. */
    @Synchronized
    fun recordSuccess() {
        consecutiveHits = 0
        openUntilMs = 0L
    }

    companion object {
        /** Cooldown after the first rate-limit hit. */
        const val BASE_COOLDOWN_MS = 60_000L

        /** Upper bound on the exponential backoff. */
        const val MAX_COOLDOWN_MS = 60L * 60_000L
    }
}
