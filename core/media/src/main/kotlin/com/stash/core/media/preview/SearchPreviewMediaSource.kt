package com.stash.core.media.preview

import androidx.media3.common.MediaItem
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.stash.core.model.TrackItem
import com.stash.data.download.preview.PreviewUrlExtractor
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds an ExoPlayer [MediaSource] for a search-result/artist-profile
 * track. Tries lossless first; falls through to the existing yt-dlp/
 * InnerTube preview-URL extractor on Qobuz miss. Both paths route
 * through the same [SimpleCache] keyed by `lossless:videoId` or
 * `ytdlp:videoId` — so a preview→download flow finalises from cache
 * without re-fetching.
 *
 * Confidence threshold for lossless acceptance is 0.65 (vs sync's 0.5)
 * because search-tab matches lack ISRC and reliable duration; we want
 * tighter title+artist agreement to avoid wrong-version downloads.
 */
@Singleton
class SearchPreviewMediaSource @Inject constructor(
    private val prefetcher: LosslessUrlPrefetcher,
    private val previewUrlExtractor: PreviewUrlExtractor,
    private val previewCache: SimpleCache,
    private val httpDataSourceFactory: HttpDataSource.Factory,
    private val cacheKeyFactory: TrackKeyCacheKeyFactory,
) {
    suspend fun create(track: TrackItem): MediaSource {
        val match = prefetcher.lookup(track)
        return if (match != null && match.confidence >= MIN_SEARCH_CONFIDENCE) {
            buildCachedSource(
                upstreamUrl = match.downloadUrl,
                cacheKey = "lossless:${track.videoId}",
            )
        } else {
            // Fallback to existing yt-dlp/InnerTube URL extractor.
            // The result URL is also cacheable with a different namespace.
            val ytUrl = previewUrlExtractor.extractStreamUrl(track.videoId)
            buildCachedSource(
                upstreamUrl = ytUrl,
                cacheKey = "ytdlp:${track.videoId}",
            )
        }
    }

    private fun buildCachedSource(upstreamUrl: String, cacheKey: String): MediaSource {
        val cacheDataSourceFactory = CacheDataSource.Factory()
            .setCache(previewCache)
            .setUpstreamDataSourceFactory(httpDataSourceFactory)
            .setCacheKeyFactory(cacheKeyFactory)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        // Embed cacheKey in URI as ?trackKey= so the CacheKeyFactory's
        // fallback path also resolves correctly. The DataSpec.key
        // takes precedence (when CacheDataSource builds the DataSpec
        // from MediaItem, it doesn't auto-set key); this URI param is
        // the actual mechanism for MediaItem-based playback. The
        // factory's defence-in-depth covers any future caller that
        // builds a DataSpec directly.
        val separator = if (upstreamUrl.contains("?")) "&" else "?"
        val mediaItem = MediaItem.Builder()
            .setUri("$upstreamUrl${separator}trackKey=$cacheKey")
            .build()
        return ProgressiveMediaSource.Factory(cacheDataSourceFactory)
            .createMediaSource(mediaItem)
    }

    companion object {
        const val MIN_SEARCH_CONFIDENCE = 0.65f
    }
}
