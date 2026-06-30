package com.stash.core.media.engine

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.stash.core.common.constants.StashConstants
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.prefs.StreamingPreference
import com.stash.core.media.service.StashPlaybackService.Companion.EXTRA_STREAM_BITRATE
import com.stash.core.media.service.StashPlaybackService.Companion.EXTRA_STREAM_BIT_DEPTH
import com.stash.core.media.service.StashPlaybackService.Companion.EXTRA_STREAM_CODEC
import com.stash.core.media.service.StashPlaybackService.Companion.EXTRA_STREAM_ORIGIN
import com.stash.core.media.service.StashPlaybackService.Companion.EXTRA_STREAM_SAMPLE_RATE
import com.stash.core.media.service.StashPlaybackService.Companion.EXTRA_TRACK_DURATION_MS
import com.stash.core.media.service.StashPlaybackService.Companion.EXTRA_TRACK_ID
import com.stash.core.media.service.StashPlaybackService.Companion.EXTRA_TRACK_IS_STREAMABLE
import com.stash.core.media.service.StashPlaybackService.Companion.EXTRA_TRACK_YOUTUBE_ID
import com.stash.core.media.streaming.ConnectivityMonitor
import com.stash.core.media.streaming.StreamSourceRegistry
import com.stash.core.media.streaming.StreamUrl
import com.stash.core.media.streaming.StreamUrlCache
import com.stash.core.media.streaming.streamCacheKey
import com.stash.core.model.Track
import com.stash.core.model.isUnavailableForDisplay
import java.io.File
import kotlinx.coroutines.flow.first

/**
 * The engine's playability decision tree — one stateless component that
 * answers a single question: *how do we play this track right now?*
 *
 * Verdicts, in evaluation order:
 *  1. [Playable.Local] — a real audio file on disk (size-validated; junk
 *     download stubs fall through to streaming).
 *  2. Refusals ([Playable.Refused]) — streaming off / no network /
 *     cellular gate / confirmed-unstreamable. Surfaced untouched so the
 *     UI can explain WHY.
 *  3. [Playable.Stream] — a resolved stream URL, cache-first. The
 *     [lane] parameter picks the resolution depth:
 *      - [Lane.FAST]: hedged lossless + InnerTube only — sub-second
 *        worst case; may return a ~1 MB-gated placeholder, which is a
 *        valid way to START playback (the recovery seam upgrades it).
 *      - [Lane.FULL]: everything, including the serialized yt-dlp slot
 *        and antra.
 *
 * Fresh implementation for the engine redesign; the data-layer ports it
 * consumes (registry, caches, prefs, connectivity) are the app's data
 * infrastructure, not the old playing system.
 */
class PlayabilityRouter(
    private val streamingPreference: StreamingPreference,
    private val connectivity: ConnectivityMonitor,
    private val streamResolver: StreamSourceRegistry,
    private val streamUrlCache: StreamUrlCache,
    private val trackDao: TrackDao,
) {
    enum class Lane { FAST, FULL }

    sealed class Playable {
        data class Local(val mediaItem: MediaItem) : Playable()
        data class Stream(val mediaItem: MediaItem, val url: StreamUrl) : Playable()
        data class Refused(val reason: Reason) : Playable() {
            enum class Reason { NOT_AVAILABLE, OFFLINE_MODE, CELLULAR, NO_CONNECTIVITY }
        }
    }

    /** Test seam mirroring the old engine's disk-check indirection. */
    internal var fileIsPlayable: (String) -> Boolean = { path ->
        val f = File(if (path.startsWith("file://")) Uri.parse(path).path ?: path else path)
        f.exists() && f.length() >= StashConstants.MIN_PLAYABLE_LOCAL_BYTES
    }

    suspend fun route(track: Track, lane: Lane): Playable {
        // 1. Local wins always — instant, free, highest quality available.
        val localPath = track.filePath
        if (track.isDownloaded && !localPath.isNullOrBlank() && fileIsPlayable(localPath)) {
            return Playable.Local(localMediaItem(track, localPath))
        }

        // 2. Refusal gates, cheapest first.
        if (track.isUnavailableForDisplay) return Playable.Refused(Playable.Refused.Reason.NOT_AVAILABLE)
        if (!streamingPreference.current()) return Playable.Refused(Playable.Refused.Reason.OFFLINE_MODE)
        if (!connectivity.isConnected()) return Playable.Refused(Playable.Refused.Reason.NO_CONNECTIVITY)
        if (connectivity.isCellular() && !streamingPreference.streamOnCellular.first()) {
            return Playable.Refused(Playable.Refused.Reason.CELLULAR)
        }

        // 3. Cache-first stream resolution. A placeholder hit satisfies
        // the FAST lane (it's how instant starts survive a lossless
        // outage) but never the FULL lane.
        val cached = streamUrlCache.get(track.id)
            ?.takeUnless { it.placeholder && lane == Lane.FULL }
        val stream = cached ?: resolve(track, lane)
            ?: return Playable.Refused(Playable.Refused.Reason.NOT_AVAILABLE)
        return Playable.Stream(streamMediaItem(track, stream), stream)
    }

    private suspend fun resolve(track: Track, lane: Lane): StreamUrl? {
        val entity = trackDao.getById(track.id) ?: track.toEntity()
        return runCatching {
            streamResolver.resolve(
                entity,
                allowYouTube = true,
                allowYtDlp = lane == Lane.FULL,
                allowAntra = lane == Lane.FULL,
            )
        }.getOrNull()
    }

    private fun localMediaItem(track: Track, path: String): MediaItem =
        MediaItem.Builder()
            .setMediaId(track.id.toString())
            .setUri(if (path.startsWith("file://") || path.startsWith("content://")) Uri.parse(path) else Uri.fromFile(File(path)))
            .setMediaMetadata(metadataFor(track, stream = null))
            .build()

    private fun streamMediaItem(track: Track, stream: StreamUrl): MediaItem =
        MediaItem.Builder()
            .setMediaId(track.id.toString())
            .setUri(Uri.parse(stream.url))
            // Track-identity cache key: bytes survive URL rotation, so
            // replays serve from disk. See [streamCacheKey].
            .setCustomCacheKey(streamCacheKey(track.id, stream))
            .setMediaMetadata(metadataFor(track, stream))
            .build()

    /** The metadata-extras contract consumed by the service, the UI and
     * the recovery pipeline — kept bit-compatible with the pre-redesign
     * items so every existing consumer keeps working. */
    private fun metadataFor(track: Track, stream: StreamUrl?): MediaMetadata =
        MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist)
            .setAlbumTitle(track.album)
            .setArtworkUri(
                (stream?.coverArtUrl ?: track.albumArtUrl)?.let { Uri.parse(it) },
            )
            .setExtras(
                Bundle().apply {
                    putLong(EXTRA_TRACK_ID, track.id)
                    putLong(EXTRA_TRACK_DURATION_MS, track.durationMs)
                    putBoolean(EXTRA_TRACK_IS_STREAMABLE, track.isStreamable)
                    track.youtubeId?.let { putString(EXTRA_TRACK_YOUTUBE_ID, it) }
                    stream?.origin?.let { putString(EXTRA_STREAM_ORIGIN, it) }
                    stream?.codec?.let { putString(EXTRA_STREAM_CODEC, it) }
                    stream?.bitsPerSample?.let { putInt(EXTRA_STREAM_BIT_DEPTH, it) }
                    stream?.sampleRateHz?.let { putInt(EXTRA_STREAM_SAMPLE_RATE, it) }
                    stream?.bitrateKbps?.let { putInt(EXTRA_STREAM_BITRATE, it) }
                },
            )
            .build()

    private fun Track.toEntity(): TrackEntity = TrackEntity(
        id = id,
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
        filePath = filePath,
        isDownloaded = isDownloaded,
        isStreamable = false,
        albumArtUrl = albumArtUrl,
        albumArtPath = albumArtPath,
        isrc = isrc,
    )
}
