package com.stash.core.media.streaming

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

/**
 * Player-wide [MediaSource.Factory] that routes **every http(s) stream-resolved
 * item** (kennyy / squid / youtube origin) through the
 * [StreamingMediaSourceFactory] refresh chain (CacheDataSource →
 * [RefreshingDataSource] → HTTP), and downloaded/local files through the plain
 * [DefaultMediaSourceFactory], exactly as before.
 *
 * **Why every stream needs the refresh chain.**
 *  - YouTube: background queue-fill seeds the timeline with cheap
 *    InnerTube/iOS placeholder URLs (deep, in-order), but those are
 *    PO-token-gated to ~1 MB and return HTTP 403 on full playback. The refresh
 *    re-resolves via yt-dlp (full-range-playable) and continues at the same
 *    byte offset.
 *  - Lossless (kennyy/squid): the signed CDN URL expires at `etsp`. A pause
 *    resumed hours later, or a long queue whose tail URLs went stale, used to
 *    hit the default factory's no-recovery path — a 403 surfaced as
 *    `onPlayerError`, the track was skipped, and three consecutive errors
 *    HALTed playback entirely. The refresh chain catches the 403 inside the
 *    data source and re-resolves silently instead.
 * Local/downloaded items stay on their proven default path.
 *
 * The per-item decision is delegated to [streamingTrackId]: it returns the
 * track id when the item should use the refresh chain (http(s) stream with a
 * stream origin and a valid id), or null otherwise. The service owns that
 * predicate because the metadata-extra keys live there.
 */
@OptIn(UnstableApi::class)
class StashMediaSourceFactory(
    context: Context,
    private val streamingFactory: StreamingMediaSourceFactory,
    private val streamingTrackId: (MediaItem) -> Long?,
) : MediaSource.Factory {

    private val localFactory = DefaultMediaSourceFactory(context)

    override fun setDrmSessionManagerProvider(
        provider: DrmSessionManagerProvider,
    ): MediaSource.Factory {
        localFactory.setDrmSessionManagerProvider(provider)
        return this
    }

    override fun setLoadErrorHandlingPolicy(
        policy: LoadErrorHandlingPolicy,
    ): MediaSource.Factory {
        localFactory.setLoadErrorHandlingPolicy(policy)
        return this
    }

    override fun getSupportedTypes(): IntArray = localFactory.supportedTypes

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val trackId = streamingTrackId(mediaItem)
        return if (trackId != null) {
            streamingFactory.create(trackId).createMediaSource(mediaItem)
        } else {
            localFactory.createMediaSource(mediaItem)
        }
    }
}
