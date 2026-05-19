package com.stash.core.media.streaming

import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.stash.core.data.db.dao.TrackDao
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.runBlocking

/**
 * Lazy MediaSource builder. When ExoPlayer needs the MediaSource for a
 * given MediaItem, this factory resolves the URI just-in-time:
 *
 *   - If [MediaItem.localConfiguration] already carries a usable URI
 *     (file:// for a downloaded track, http(s):// for a search-tab stream
 *     already resolved by `playFromStream`) → delegate to the inner
 *     factory unchanged.
 *
 *   - If the MediaItem has no URI but its mediaId resolves to a real
 *     track in the DB → look up the track, resolve via [KennyyStreamResolver]
 *     (cache-aware), build a new MediaItem with the resolved URL, hand
 *     that off to the inner factory.
 *
 *   - If resolution returns null (track genuinely not in the Qobuz
 *     catalog) → delegate to the inner factory with the original (no-URI)
 *     MediaItem. ExoPlayer will surface an error and the player's
 *     skip-next listener (PlayerRepositoryImpl.playerListener.onPlayerError)
 *     will advance past it.
 *
 * Why this design: building queues used to require pre-resolving every
 * track's URL eagerly, which doesn't scale beyond a few hundred tracks
 * (the Qobuz proxy chokes on 2000+ simultaneous lookups). Now setQueue
 * is instant — Media3 calls into this factory only when it actually
 * needs the source for the currently-playing item.
 *
 * Synchronous wrapper of suspending APIs is safe here: Media3 invokes
 * createMediaSource() on its own loader thread, never the main thread,
 * so the runBlocking { } bridge can't ANR the UI. Same pattern as
 * [RefreshingDataSource.open] (Task 6).
 *
 * **Empty-URI quirk.** [StashPlaybackService.resolveMediaItem] historically
 * called `setUri(filePath ?: "")` which forwards an empty-string URI for
 * streaming-only tracks (filePath null). An empty URI builds a non-null
 * localConfiguration that would erroneously skip the lazy-resolve branch.
 * We treat blank URIs as absent here defensively in case any other caller
 * has the same edge case; the service has also been updated to leave URI
 * absent for streaming-only items.
 */
@Singleton
@OptIn(UnstableApi::class)
class LazyResolvingMediaSourceFactory @Inject constructor(
    private val inner: DefaultMediaSourceFactory,
    private val resolver: KennyyStreamResolver,
    private val urlCache: StreamUrlCache,
    private val trackDao: TrackDao,
) : MediaSource.Factory {

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val existingUri = mediaItem.localConfiguration?.uri?.toString()
        val hasUsableUri = !existingUri.isNullOrBlank()
        if (hasUsableUri) return inner.createMediaSource(mediaItem)

        val trackId = mediaItem.mediaId.toLongOrNull()
            ?: return inner.createMediaSource(mediaItem)  // no resolvable id — let it fail downstream

        val resolved: MediaItem = runBlocking {
            val track = trackDao.getById(trackId) ?: return@runBlocking mediaItem
            val cached = urlCache.get(trackId)
            val stream = cached ?: resolver.resolve(track)?.also { urlCache.put(trackId, it) }
            stream?.let { rebuildWithUri(mediaItem, it.url) } ?: mediaItem
        }
        return inner.createMediaSource(resolved)
    }

    override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider): MediaSource.Factory {
        inner.setDrmSessionManagerProvider(provider)
        return this
    }

    override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy): MediaSource.Factory {
        inner.setLoadErrorHandlingPolicy(policy)
        return this
    }

    override fun getSupportedTypes(): IntArray = inner.supportedTypes

    private fun rebuildWithUri(original: MediaItem, url: String): MediaItem {
        return original.buildUpon()
            .setUri(url)
            .build()
    }
}
