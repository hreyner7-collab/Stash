package com.stash.core.media.streaming

import com.stash.core.data.db.entity.TrackEntity
import com.stash.data.download.lossless.TrackQuery
import com.stash.data.download.lossless.kennyy.KennyySource
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Result of a successful stream-URL lookup. [url] is the signed CDN URL
 * the player should fetch; [expiresAtMs] is the wall-clock instant
 * (epoch-millis, comparable to [System.currentTimeMillis]) at which the
 * signature stops being valid and the CDN starts returning 403.
 *
 * Callers cache the URL keyed by track id with this TTL — the
 * [com.stash.core.media.streaming.StreamUrlCache] (Task 4) and the mid-
 * stream refresh path in [com.stash.core.media.streaming
 * .RefreshingDataSourceFactory] (Task 6) both rely on it.
 */
data class StreamUrl(
    val url: String,
    val expiresAtMs: Long,
)

/**
 * Adapts the existing lossless-download [KennyySource] into a stream-URL
 * resolver. Given a [TrackEntity], builds the same [TrackQuery] the
 * download pipeline would, invokes Kennyy's catalog search + signed-URL
 * lookup, and pulls the `etsp` query parameter out of the resulting URL
 * to compute when the signature expires.
 *
 * `etsp` is the URL signature expiry the qobuz.kennyy.com.br proxy
 * (and qobuz.squid.wtf, same Qobuz-DL codebase) bakes into every
 * download URL. Format is a Unix epoch in *seconds* — multiply by 1000
 * to align with [System.currentTimeMillis]. Past `etsp`, the Akamai CDN
 * returns 403 and the player must re-resolve.
 *
 * Returns null when:
 *  - Kennyy has no confident match for the track (downstream pipeline
 *    should fall through to YouTube, or surface "not streamable").
 *  - The returned URL has no `etsp` parameter. Defensive: a URL we can't
 *    safely refresh is one we shouldn't cache; treat it as unresolved
 *    rather than letting the player hit a non-refreshable 403 later.
 *  - The `etsp` value isn't an integer.
 *
 * v1 note: streaming quality follows the existing lossless-download
 * preference ([com.stash.data.download.lossless.LosslessSourcePreferences])
 * because [TrackQuery] has no `preferredQuality` field yet. The
 * `streamQuality` flow on [com.stash.core.media.streaming
 * .StreamingPreference] is stored but unused here until a follow-up
 * threads quality through [KennyySource.resolve].
 */
@Singleton
class KennyyStreamResolver @Inject constructor(
    private val source: KennyySource,
) {
    suspend fun resolve(track: TrackEntity): StreamUrl? {
        val query = TrackQuery(
            artist = track.artist,
            title = track.title,
            album = track.album.takeIf { it.isNotBlank() },
            isrc = track.isrc?.takeIf { it.isNotBlank() },
            durationMs = track.durationMs,
        )
        // Use the immediate (rate-limiter-bypassing) path: streaming-tap
        // is user-initiated and must not queue behind background
        // AvailabilityCheckWorker batches that hold the limiter at 1
        // req/s. See KennyySource.resolveImmediate KDoc for rationale.
        val result = source.resolveImmediate(query) ?: return null
        val etspMs = parseEtspMs(result.downloadUrl) ?: return null
        return StreamUrl(url = result.downloadUrl, expiresAtMs = etspMs)
    }

    private fun parseEtspMs(url: String): Long? {
        val match = ETSP_REGEX.find(url) ?: return null
        val secs = match.groupValues[1].toLongOrNull() ?: return null
        return secs * 1000L
    }

    private companion object {
        val ETSP_REGEX = Regex("""[?&]etsp=(\d+)""")
    }
}
