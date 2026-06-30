package com.stash.data.ytmusic

import com.stash.data.ytmusic.model.TrackSummary
import kotlinx.serialization.json.JsonObject

/** A duration token like "3:45" or "1:02:03" — used to recover the
 *  duration from a flex-column run when the row has no fixedColumns. */
private val DURATION_TOKEN_REGEX = Regex("""\d{1,2}:\d{2}(:\d{2})?""")

/**
 * Cross-file renderer-parsing helpers shared by [SearchResponseParser] and
 * [ArtistResponseParser].
 *
 * The search "Songs" shelf and the artist "Popular" shelf both ship their rows
 * as `musicResponsiveListItemRenderer` objects with identical flex/fixed column
 * semantics — extracting a single helper avoids copy-pasting ~40 lines of
 * column-walking code into two places.
 *
 * Kept `internal` so it's a module-private contract: parser files in
 * `data.ytmusic` may use it; no other module should need it.
 */

/**
 * Spec §8 Open Question 1: InnerTube returns artists with either `UC…`
 * (channel) or `MPLAUC…` (music channel) browseIds. Cache-key stability
 * requires a single form — we strip the `MPLA` prefix only when it is
 * immediately followed by `UC`, leaving other unknown `MPLA`-prefixed ids
 * (e.g. `MPLARZ…`) untouched to avoid truncating forms we don't recognize.
 *
 * Top-level `internal` so parser tests can exercise it directly.
 */
internal fun normalizeArtistBrowseId(browseId: String): String =
    if (browseId.startsWith("MPLAUC")) browseId.removePrefix("MPLA") else browseId

/**
 * Parses a `musicResponsiveListItemRenderer` into a [TrackSummary].
 *
 * Expected shape:
 * ```
 * {
 *   "playlistItemData": { "videoId": "..." },     // or overlay fallback
 *   "flexColumns": [                              // [title, artists, album?]
 *     { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [...] } } },
 *     ...
 *   ],
 *   "fixedColumns": [                             // [duration?]
 *     { "musicResponsiveListItemFixedColumnRenderer": { "text": { "runs": [...] } } }
 *   ],
 *   "thumbnail": { "musicThumbnailRenderer": { "thumbnail": { "thumbnails": [...] } } }
 * }
 * ```
 *
 * Returns null when a required field (videoId, title) is missing so callers
 * can `mapNotNull` over a shelf's items without filtering separately.
 *
 * @param renderer The parsed `musicResponsiveListItemRenderer` object.
 * @return A [TrackSummary], or null if the row is malformed.
 */
internal fun parseTrackSummaryFromListItem(
    renderer: JsonObject,
    fallbackArtist: String? = null,
): TrackSummary? {
    val videoId = renderer["playlistItemData"]?.asObject()
        ?.get("videoId")?.asString()
        ?: renderer.navigatePath(
            "overlay", "musicItemThumbnailOverlayRenderer", "content",
            "musicPlayButtonRenderer", "playNavigationEndpoint",
            "watchEndpoint", "videoId",
        )?.asString()
        ?: return null

    val flexColumns = renderer["flexColumns"]?.asArray() ?: return null
    val title = flexColumns.getOrNull(0)?.asObject()
        ?.navigatePath("musicResponsiveListItemFlexColumnRenderer", "text", "runs")
        ?.firstArray()?.firstOrNull()?.asObject()
        ?.get("text")?.asString()
        ?: return null

    // Album-page tracklists omit the artist column from flexColumns (the
    // artist is shown once in the album header). The result is an empty
    // artist string here, which breaks downstream lossless matching
    // (Qobuz/Kennyy score on artist+title and can't find candidates
    // without an artist). The caller — typically AlbumResponseParser —
    // passes the album header's artist as fallbackArtist so per-row
    // artists default to that when the shelf row carries none.
    val artistRuns = flexColumns.getOrNull(1)?.asObject()
        ?.navigatePath("musicResponsiveListItemFlexColumnRenderer", "text", "runs")
        ?.asArray()
    val parsedArtist = artistRuns
        ?.mapNotNull { it.asObject()?.get("text")?.asString() }
        ?.filterNot { it == " & " || it == ", " || it == " x " }
        ?.joinToString(", ")
        .orEmpty()
    val artist = parsedArtist.ifBlank { fallbackArtist.orEmpty() }

    val album = flexColumns.getOrNull(2)?.asObject()
        ?.navigatePath("musicResponsiveListItemFlexColumnRenderer", "text", "runs")
        ?.firstArray()?.firstOrNull()?.asObject()
        ?.get("text")?.asString()

    val thumbnails = renderer.navigatePath(
        "thumbnail", "musicThumbnailRenderer", "thumbnail", "thumbnails",
    )?.firstArray()
    val thumbnailUrl = com.stash.core.common.ArtUrlUpgrader.upgrade(
        thumbnails?.maxByOrNull {
            it.asObject()?.get("width")?.asString()?.toIntOrNull() ?: 0
        }?.asObject()?.get("url")?.asString(),
    )

    val durationText = renderer["fixedColumns"]?.asArray()
        ?.firstOrNull()?.asObject()
        ?.navigatePath("musicResponsiveListItemFixedColumnRenderer", "text", "runs")
        ?.firstArray()?.firstOrNull()?.asObject()
        ?.get("text")?.asString()
        // Fallback: the songs-ONLY filtered search (used by the Search tab
        // for a deep song list) carries no `fixedColumns` — the duration
        // sits as a run inside the flex columns instead (e.g. "3:45").
        // Without this, those rows showed "0:00". Scan every flex-column
        // run for a m:ss / h:mm:ss token.
        ?: flexColumns.asSequence()
            .mapNotNull {
                it.asObject()
                    ?.navigatePath("musicResponsiveListItemFlexColumnRenderer", "text", "runs")
                    ?.asArray()
            }
            .flatMap { it.asSequence() }
            .mapNotNull { it.asObject()?.get("text")?.asString() }
            .firstOrNull { DURATION_TOKEN_REGEX.matches(it) }

    return TrackSummary(
        videoId = videoId,
        title = title,
        artist = artist,
        album = album,
        durationSeconds = parseDurationToSeconds(durationText),
        thumbnailUrl = thumbnailUrl,
    )
}
