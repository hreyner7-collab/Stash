package com.stash.core.data.streaming

import com.stash.core.data.db.entity.TrackEntity

/**
 * Module-graph seam between [com.stash.core.data.sync.workers.AvailabilityCheckWorker]
 * (which lives in `core/data`, the lower leaf) and the Kennyy/Qobuz stream resolver
 * (which lives in `core/media`, above `core/data` in the dependency DAG).
 *
 * The worker can't import the resolver directly without flipping the module
 * graph, so it depends on this tiny interface instead. The implementation
 * lives in `core/media` next to the resolver and is bound via Hilt `@Binds`.
 *
 * Implementations must be side-effect-free with respect to the database —
 * the worker handles all `tracks` writes after consulting [isAvailable].
 * They should also never throw: any underlying resolver failure (network,
 * rate-limit, parse error, missing match) must be folded into `false` so
 * the worker can move on and mark the row checked.
 */
interface StreamAvailabilityChecker {
    /**
     * Returns `true` if the streaming resolver has a playable URL for
     * [track] right now, `false` otherwise (no match, or transient failure).
     *
     * The worker only writes [TrackEntity.isStreamable] from this return
     * value and stamps `is_streamable_checked_at` regardless — so a row
     * that flickers from "no" to "yes" later picks up on the next
     * recheck pass (Task 10's `AvailabilityRecheckWorker`).
     */
    suspend fun isAvailable(track: TrackEntity): Boolean
}
