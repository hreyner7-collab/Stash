package com.stash.core.media.streaming

import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.streaming.StreamAvailabilityChecker
import javax.inject.Inject
import javax.inject.Singleton

/**
 * `core/media`-side adapter that satisfies [StreamAvailabilityChecker]
 * by delegating to [KennyyStreamResolver]. Lives here (not in `core/data`)
 * because the resolver depends on `data/download`'s `KennyySource`, which
 * is above `core/data` in the module graph.
 *
 * `runCatching` is intentional: the resolver can throw on socket / parse
 * / rate-limit failures, but the worker contract for
 * [StreamAvailabilityChecker.isAvailable] is "never throw, just return
 * false." Folding both the null branch (no match) and the throw branch
 * (transient failure) into `false` here keeps the worker's loop dead
 * simple — it always writes the checked-at timestamp so the row doesn't
 * loop forever, and Task 10's recheck worker re-validates ~30 days later.
 */
@Singleton
class KennyyStreamAvailabilityChecker @Inject constructor(
    private val resolver: KennyyStreamResolver,
) : StreamAvailabilityChecker {
    override suspend fun isAvailable(track: TrackEntity): Boolean =
        runCatching { resolver.resolve(track) != null }.getOrElse { false }
}
