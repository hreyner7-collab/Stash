package com.stash.data.download.lossless.arcod

import com.stash.data.download.lossless.TrackQuery
import kotlin.math.abs

/**
 * A scored ARCOD search candidate. [confidence] is on `[0.0, 1.0]`.
 */
data class ArcodMatch(val item: ArcodTrackItem, val confidence: Float)

/**
 * Picks the best ARCOD (Qobuz-DL proxy) `get-music` search result for a
 * [TrackQuery]. Pure — no I/O, no injection — so it is trivially unit-tested.
 *
 * The artist+title scoring deliberately mirrors `QobuzSource.confidence()`
 * (same `normalize`/`jaccard`/`artistSimilarity` shape and 0.5 accept
 * threshold) because both consume the same upstream Qobuz catalog JSON and
 * face the same Spotify-expansion-vs-canonical artist-name mismatches. The
 * helpers are re-implemented here rather than imported so this object stays
 * self-contained and dependency-free.
 *
 * Differences from `QobuzSource.confidence()`, per the ArcodSource spec:
 *  - The base score is `titleSim * artistSim` (no soft duration factor).
 *  - Duration is a HARD GUARD: a candidate whose stated duration differs from
 *    the query by more than [DURATION_TOLERANCE_MS] is rejected outright.
 *  - ISRC is a confirmation BOOST (`max(score, ISRC_CONFIDENCE)`), not a
 *    short-circuit — text scoring still runs.
 */
object ArcodMatcher {

    /** Candidates scoring below this are never returned. */
    private const val MIN_CONFIDENCE = 0.5f

    /** ISRC equality lifts confidence to at least this. */
    private const val ISRC_CONFIDENCE = 0.95f

    /** Max allowed |candidate − query| duration drift before hard reject. */
    private const val DURATION_TOLERANCE_MS = 5_000L

    /**
     * Returns the highest-confidence candidate whose confidence is at least
     * [MIN_CONFIDENCE], or null when nothing crosses the bar. Candidates
     * failing the duration guard are skipped entirely.
     */
    fun best(query: TrackQuery, items: List<ArcodTrackItem>): ArcodMatch? =
        items
            .filterNot { failsDurationGuard(query, it) }
            .map { ArcodMatch(it, confidence(query, it)) }
            .filter { it.confidence >= MIN_CONFIDENCE }
            .maxByOrNull { it.confidence }

    /**
     * True when both durations are known and differ by more than
     * [DURATION_TOLERANCE_MS] — i.e. almost certainly a different cut
     * (live/extended/edit). Unknown durations never reject.
     */
    private fun failsDurationGuard(query: TrackQuery, item: ArcodTrackItem): Boolean {
        val queryMs = query.durationMs ?: return false
        val candidateMs = item.duration?.let { it * 1000L } ?: return false
        return abs(candidateMs - queryMs) > DURATION_TOLERANCE_MS
    }

    /**
     * Confidence on `[0.0, 1.0]`: token-overlap on title times artist
     * similarity, lifted to [ISRC_CONFIDENCE] when the ISRCs confirm the
     * same recording.
     */
    private fun confidence(query: TrackQuery, item: ArcodTrackItem): Float {
        val titleSim = jaccard(normalize(query.title), normalize(item.title))
        val artistName = item.performer?.name ?: item.album?.artist?.name ?: ""
        val artistSim = artistSimilarity(normalize(query.artist), normalize(artistName))
        val textScore = titleSim * artistSim

        val queryIsrc = query.isrc
        val candidateIsrc = item.isrc
        return if (queryIsrc != null && candidateIsrc != null &&
            queryIsrc.equals(candidateIsrc, ignoreCase = true)
        ) {
            maxOf(textScore, ISRC_CONFIDENCE)
        } else {
            textScore
        }
    }

    // ── Pure-function helpers (mirrors QobuzSource) ──────────────────────────

    /**
     * Lowercase + strip parenthetical/bracket content, "feat./featuring"
     * suffixes, and punctuation; collapse whitespace. Keeps Unicode letters,
     * digits and symbols so stylized names ("¥$", "$NOT") survive.
     */
    private fun normalize(s: String): String =
        s.lowercase()
            .replace(Regex("\\([^)]*\\)"), " ")
            .replace(Regex("\\[[^]]*\\]"), " ")
            .replace(Regex("(?i)\\b(feat\\.?|ft\\.?|featuring)\\b.*"), " ")
            .replace(Regex("[''`]"), "")
            .replace(Regex("[^\\p{L}\\p{N}\\p{S}\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    /** Jaccard similarity on whitespace-tokenized strings. */
    private fun jaccard(a: String, b: String): Float {
        val setA = a.split(" ").filter { it.isNotEmpty() }.toSet()
        val setB = b.split(" ").filter { it.isNotEmpty() }.toSet()
        if (setA.isEmpty() || setB.isEmpty()) return 0f
        val intersection = setA.intersect(setB).size.toFloat()
        val union = setA.union(setB).size.toFloat()
        return intersection / union
    }

    /**
     * Artist-aware similarity: max of plain jaccard and subset-coverage.
     * Returns 1.0 when the smaller artist string is fully contained in the
     * larger AND shares a distinctive (length > 3, or symbol-bearing) token —
     * the common Spotify-expansion ("Daft Punk feat. Pharrell") vs
     * Qobuz-canonical ("Daft Punk") pattern. Falls back to plain jaccard.
     */
    private fun artistSimilarity(a: String, b: String): Float {
        val setA = a.split(" ").filter { it.isNotEmpty() }.toSet()
        val setB = b.split(" ").filter { it.isNotEmpty() }.toSet()
        if (setA.isEmpty() || setB.isEmpty()) return 0f

        val intersection = setA.intersect(setB)
        val union = setA.union(setB)
        val jaccardScore = intersection.size.toFloat() / union.size.toFloat()

        val smallerSize = minOf(setA.size, setB.size)
        val smallerFullyCovered = intersection.size == smallerSize
        val hasDistinctiveOverlap = intersection.any { token ->
            token.length > 3 || token.any { ch -> !ch.isLetterOrDigit() }
        }
        val coverageScore = if (smallerFullyCovered && hasDistinctiveOverlap) 1.0f else 0f

        return maxOf(jaccardScore, coverageScore)
    }
}
