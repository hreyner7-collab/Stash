package com.stash.core.media.streaming

import android.util.Log
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.prefs.StreamingPreference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Walks Stash's streaming-source roster in priority order and returns
 * the first match. Each resolver internally handles its own enablement
 * (captcha cookies, circuit-breaker state for non-streaming paths, etc.)
 * — null from one resolver just means "try the next one".
 *
 * Current order:
 *   1. [KennyyStreamResolver]  — `kennyy.com.br`, Qobuz lossless. No
 *      captcha gate; almost always usable. Primary source.
 *   2. [QobuzStreamResolver]   — `qobuz.squid.wtf`. Same Qobuz catalog,
 *      requires a user-pasted `captcha_verified_at` cookie. Auto-skipped
 *      when no cookie is set or the current cookie has been marked stale.
 *      Hedged against kennyy rather than strictly sequential — see
 *      [resolveLosslessHedged] — so a kennyy timeout doesn't delay the
 *      failover by its full 3s budget.
 *   3. [AntraStreamResolver]   — `antra.hoshi.cfd`, independent per-user
 *      lossless (own multi-source backend). Self-gates: returns null when
 *      not connected / out of quota, so it only engages when both Qobuz
 *      proxies miss. Plays a locally-cached FLAC (no signed CDN URL).
 *   4. [YouTubeStreamResolver] — yt-dlp / InnerTube extraction. Last
 *      resort, reached only when the track genuinely isn't in the Qobuz
 *      catalog (Bandcamp re-uploads, region-exclusive, underground
 *      releases). Lossy quality (AAC/Opus ~128-160 kbps), surfaced as a
 *      "via YT" badge in Now Playing so the user knows.
 *
 * Exposes the same `resolve(track) -> StreamUrl?` shape as the individual
 * resolvers so callers ([PlayerRepositoryImpl], [StreamingMediaSourceFactory],
 * [RefreshingDataSourceFactory], [PrefetchOrchestrator]) can swap in by
 * type without changing call-site logic.
 *
 * Result caching is the caller's responsibility — [StreamUrlCache] sits
 * on the player side and stores the first source's success keyed by track
 * id. Subsequent plays of the same track hit the cache and bypass the
 * registry entirely until the URL's `etsp` expires.
 *
 * Test toggles (both off for normal use):
 *  - [StreamingPreference.isForceYouTubeFallback]: [resolve] skips Kennyy
 *    and Squid entirely and routes every track through the YouTube resolver
 *    only — reproduces the lossless-down fallback path on demand.
 *  - [StreamingPreference.isForceAntraOnly]: antra becomes the PREFERRED
 *    first source, with the normal chain as fallback when antra misses.
 *    (Previously hard-exclusive, which silently disabled all playback for
 *    users without a connected antra account.) Takes precedence over
 *    forceYt for the antra slot.
 */
@Singleton
class StreamSourceRegistry @Inject constructor(
    private val kennyy: KennyyStreamResolver,
    private val qobuz: QobuzStreamResolver,
    private val antra: AntraStreamResolver,
    private val youtube: YouTubeStreamResolver,
    private val oneDrive: OneDriveStreamResolver,
    private val piped: PipedStreamResolver,
    private val streamingPreference: StreamingPreference,
    private val streamUrlCache: StreamUrlCache,
) {

    /**
     * Try each resolver in priority order; return the first non-null
     * [StreamUrl]. Returns null when no source produced a match — caller
     * should surface this as [StreamRoutingResult.NotAvailable].
     *
     * @param allowYouTube pass `false` to skip the YouTube fallback
     *   resolver, leaving only the two Qobuz operators. Used by
     *   [PlayerRepositoryImpl.setQueue]'s background-fill path so
     *   yt-dlp's limited 2-slot extraction semaphore stays available
     *   for the foreground user-tap critical path. Foreground (tapped
     *   track) calls leave this true.
     * @param allowYtDlp pass `false` to make the YouTube fallback resolve
     *   via the fast InnerTube engine only (no slow yt-dlp). Used by the
     *   background-fill path so a 15-35s yt-dlp invocation never sits on
     *   the queue's critical path. Foreground calls leave this true.
     * @param allowAntra pass `false` to keep antra out of the chain. An
     *   antra resolve is EXPENSIVE: it spends one single from a finite
     *   per-account quota and occupies antra's exclusive job slot for
     *   60-120s. setQueue's queue-wide background fill passes `false` —
     *   without that, one playlist tap during a kennyy outage drains the
     *   quota at ~1 single/90s (observed on-device 2026-06-09). The
     *   tapped track and the single NEXT-UP prefetch keep the default
     *   `true`: both are spent the moment they actually play, and the
     *   next-up prefetch is what keeps auto-advance seamless across
     *   antra's 60-120s job latency.
     */
    suspend fun resolve(
        track: TrackEntity,
        allowYouTube: Boolean = true,
        allowYtDlp: Boolean = true,
        allowAntra: Boolean = true,
        preferFast: Boolean = false,
    ): StreamUrl? {
        // Negative-cache gate for FULL-POWER resolves only. A track that
        // just burned the serialized yt-dlp slot's 35s timeout and came up
        // empty will come up empty again seconds later — re-asking on every
        // transition starved the playable tracks' upgrades behind 35s
        // no-hopers (measured on-device 2026-06-12). Fast-lane calls
        // (allowYtDlp=false) are exempt: they're cheap, and InnerTube can
        // serve a placeholder for a track yt-dlp can't extract.
        if (allowYtDlp && track.id != 0L && streamUrlCache.isUnresolvable(track.id)) {
            Log.d(TAG, "skip full resolve for ${track.id} '${track.title}' — marked unresolvable")
            return null
        }
        val resolvers = buildList<Pair<String, suspend (TrackEntity) -> StreamUrl?>> {
            // The user's own OneDrive warehouse outranks every third-party
            // source in ALL modes (including the test toggles below): no
            // catalog lookup, no extraction, no rate limits — a synced
            // track streams from the user's own storage in one Graph call.
            // Self-gates to null instantly when not connected/synced.
            add("onedrive" to oneDrive::resolve)
            if (streamingPreference.isForceAntraOnly()) {
                // "Prefer antra" toggle. antra is consulted FIRST, but a miss
                // (not connected, out of quota, no match) falls through to the
                // normal chain instead of returning null. The original
                // hard-exclusive drill semantics bricked ALL playback when the
                // toggle was flipped without a connected antra account —
                // observed on-device 2026-06-11: every playlist tap failed in
                // ~1s with zero resolver traffic, queued placeholder items
                // 403'd at their 1 MB gate with no upgrade path, and the
                // cascade guard halted the player. A user-facing toggle must
                // never be able to silence the whole app; preference, not
                // exclusivity. Takes precedence over forceYt for the antra
                // slot; the fallback chain below honours allowAntra=false
                // (speculative callers skip antra entirely, as before).
                if (allowAntra) add("antra" to antra::resolve)
                add("lossless" to { t: TrackEntity -> resolveLosslessHedged(t) })
                if (allowYouTube) add("youtube" to { t: TrackEntity -> youtube.resolve(t, allowYtDlp) })
            } else if (streamingPreference.isForceYouTubeFallback()) {
                // Test toggle: skip the lossless sources, forcing the
                // YouTube fallback path. Still gated by allowYouTube so the
                // background-fill keeps resolving nothing (matching a genuine
                // both-sources-down outage).
                if (allowYouTube) add("youtube" to { t: TrackEntity -> youtube.resolve(t, allowYtDlp) })
            } else {
                if (preferFast) {
                    // MEGA-RACE: Piped server pool + Kennyy (lossless) + Squid (lossless)
                    // all run in parallel — first real full-playable URL wins, losers
                    // cancelled. Previously these were sequential (Piped up to 4 s → then
                    // kennyy), so a slow Piped pool blocked kennyy from starting for up
                    // to 4 s. Now kennyy and Piped compete directly: if kennyy answers
                    // in ~300 ms the song starts in ~300 ms regardless of Piped's state.
                    //
                    // youtube-fast (InnerTube placeholder, allowYtDlp=false) is intentionally
                    // excluded: those URLs are PO-token-gated to ~1 MB and 403 on full
                    // playback, giving a fake instant start that disrupts playback ~30 s in.
                    add("fast-race" to { t: TrackEntity -> resolveFast(t) })
                } else {
                    // Background fill: lossless hedge only (no Piped — saves the pool
                    // for foreground taps that actually need instant response).
                    add("lossless" to { t: TrackEntity -> resolveLosslessHedged(t) })
                }
                // antra self-gates (null when not connected / out of quota),
                // so it only serves when both Qobuz proxies miss.
                if (allowAntra) add("antra" to antra::resolve)
                if (allowYouTube) add("youtube" to { t: TrackEntity -> youtube.resolve(t, allowYtDlp) })
            }
        }
        for ((name, fn) in resolvers) {
            val result = runCatching { fn(track) }
                .onFailure { e ->
                    // A cancelled caller must stop consulting sources, not
                    // log a scary stack and walk the rest of the chain.
                    if (e is CancellationException) throw e
                    // Resolvers should never throw — they catch and return
                    // null. Defensive log so an unexpected throw from one
                    // source doesn't break the chain.
                    Log.w(TAG, "$name threw on resolve for ${track.id} '${track.title}'", e)
                }
                .getOrNull()
            if (result != null) {
                if (result.origin != "kennyy") {
                    // Diagnostic: anything other than the primary source is
                    // a fallback path worth noticing. Helps explain "this
                    // track played but at lower quality" reports.
                    Log.i(TAG, "${result.origin} served ${track.id} '${track.title}' (kennyy missed)")
                }
                // Chokepoint caching: EVERY successful resolve is cached
                // here, keyed by track id, no matter which subsystem asked.
                // On-device 2026-06-11 logs showed the same track paying the
                // serialized 8-25s yt-dlp extraction twice in a row: a
                // cancelled prefetch's result evaporated before its caller's
                // own cache-put could run, and the next caller started over.
                // The chokepoint put makes resolver work durable even when
                // the requester died waiting. (id 0 = legacy/unkeyed row —
                // nothing meaningful to key the cache on.)
                if (track.id != 0L) streamUrlCache.put(track.id, result)
                return result
            }
        }
        // A FULL-POWER attempt (every source allowed, yt-dlp included)
        // found nothing — remember that so the next 15 minutes of
        // transitions don't re-burn the 35s extractor timeout on a track
        // nothing can serve. Partial-power misses (fast lane, no-YouTube)
        // prove nothing and are not recorded.
        if (allowYtDlp && allowYouTube && track.id != 0L) {
            streamUrlCache.markUnresolvable(track.id)
            Log.i(TAG, "marked ${track.id} '${track.title}' unresolvable for 15min (full resolve missed)")
        }
        return null
    }

    /**
     * Hedged-request resolve across the two Qobuz proxies. Kennyy fires
     * immediately; squid starts after [SQUID_HEDGE_DELAY_MS] — or the
     * moment kennyy completes with a miss, whichever comes first. When
     * kennyy answers within the hedge window (the healthy common case,
     * typically well under 1s) the squid coroutine is cancelled before its
     * delay elapses, so the proxy sees no extra traffic. The hedge only
     * costs squid a request when kennyy is genuinely slow or down — which
     * is exactly when the old strictly-sequential chain made the user wait
     * kennyy's full 3s timeout before the failover even began.
     *
     * Both proxies serve the same Qobuz catalog at the same quality, so
     * "kennyy preferred, squid acceptable" is a latency policy, not a
     * quality one.
     */
    private suspend fun resolveLosslessHedged(track: TrackEntity): StreamUrl? = coroutineScope {
        val squidStartNow = CompletableDeferred<Unit>()
        val squidHedge = async {
            // Wait out the hedge window unless kennyy misses first.
            withTimeoutOrNull(SQUID_HEDGE_DELAY_MS) { squidStartNow.await() }
            runCatching { qobuz.resolve(track) }
                .onFailure { e ->
                    if (e is CancellationException) throw e
                    Log.w(TAG, "squid threw on resolve for ${track.id} '${track.title}'", e)
                }
                .getOrNull()
        }
        val fromKennyy = runCatching { kennyy.resolve(track) }
            .onFailure { e ->
                if (e is CancellationException) throw e
                Log.w(TAG, "kennyy threw on resolve for ${track.id} '${track.title}'", e)
            }
            .getOrNull()
        if (fromKennyy != null) {
            squidHedge.cancel()
            return@coroutineScope fromKennyy
        }
        // Kennyy missed — release the hedge immediately rather than letting
        // the remaining delay tick down.
        squidStartNow.complete(Unit)
        squidHedge.await()
    }

    /**
     * Parallel race of all fast, full-playable resolvers for the foreground
     * tap path. First non-null URL wins; all others are cancelled immediately.
     *
     *  - **Piped**: races its own internal pool of YouTube-proxy servers
     *    simultaneously, returning a full-playable googlevideo URL in ~200 ms
     *    when a healthy server answers first.
     *  - **Kennyy**: lossless Qobuz proxy — typically ~200–400 ms when healthy.
     *    Runs in parallel with Piped so a fast kennyy can win even if the whole
     *    Piped pool is slow.
     *  - **Squid**: second Qobuz proxy, hedged [SQUID_HEDGE_DELAY_MS] behind
     *    kennyy. When kennyy answers promptly the squid coroutine is cancelled
     *    before its request fires (zero extra traffic). When kennyy is slow /
     *    unhealthy the hedge expires and squid joins the race.
     *
     * youtube-fast (InnerTube placeholder, allowYtDlp=false) is NOT included
     * because those URLs are PO-token-gated to ~1 MB and 403 on any full-file
     * read — serving one produces a fake "instant start" that disrupts playback
     * ~30 s in. Only real, full-playable URLs belong in this race.
     *
     * Falls back to null (→ caller tries antra / yt-dlp) if every probe misses
     * within [FAST_RACE_TIMEOUT_MS].
     */
    private suspend fun resolveFast(track: TrackEntity): StreamUrl? =
        withTimeoutOrNull(FAST_RACE_TIMEOUT_MS) {
            coroutineScope {
                val winner = CompletableDeferred<StreamUrl?>()

                val jobs = buildList {
                    // Piped: internal pool race, full-playable URL in ~200 ms.
                    add(launch {
                        val r = runCatching { piped.resolve(track) }
                            .onFailure { if (it is CancellationException) throw it }
                            .getOrNull()
                        if (r != null) winner.complete(r)
                    })
                    // Kennyy: lossless Qobuz proxy.
                    add(launch {
                        val r = runCatching { kennyy.resolve(track) }
                            .onFailure { if (it is CancellationException) throw it }
                            .getOrNull()
                        if (r != null) winner.complete(r)
                    })
                    // Squid: hedged backup Qobuz proxy — fires only when kennyy is slow.
                    add(launch {
                        delay(SQUID_HEDGE_DELAY_MS)
                        if (!winner.isCompleted) {
                            val r = runCatching { qobuz.resolve(track) }
                                .onFailure { if (it is CancellationException) throw it }
                                .getOrNull()
                            if (r != null) winner.complete(r)
                        }
                    })
                }

                // Resolve winner to null once every probe finishes without a hit.
                launch { jobs.joinAll(); winner.complete(null) }

                val result = winner.await()
                jobs.forEach { it.cancel() }
                result
            }
        }

    private companion object {
        private const val TAG = "StreamSourceRegistry"

        /**
         * How long the squid hedge waits before firing alongside kennyy.
         * Kennyy answers well under this when healthy, so squid normally
         * sees zero speculative traffic; past it, the resolve is already
         * slow enough that a parallel attempt is worth the extra request.
         */
        private const val SQUID_HEDGE_DELAY_MS = 500L

        /**
         * Safety-cap for [resolveFast]. Each inner resolver has its own
         * tighter timeout (Kennyy 3 s, Piped pool 4 s), so this only
         * fires if a resolver somehow exceeds its own budget — prevents
         * the foreground tap from hanging indefinitely if a resolver's
         * internal guard misfires.
         */
        private const val FAST_RACE_TIMEOUT_MS = 5_000L
    }
}
