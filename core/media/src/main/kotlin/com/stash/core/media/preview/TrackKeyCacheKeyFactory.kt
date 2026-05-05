package com.stash.core.media.preview

import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheKeyFactory
import javax.inject.Inject

/**
 * Maps a [DataSpec] to a stable cache key.
 *
 * Order:
 *   1. If [DataSpec.key] is set (the search-tab path always sets it via
 *      `DataSpec.Builder.setKey(...)`), use it directly. This produces
 *      keys like `lossless:dQw4w9WgXcQ` and `ytdlp:dQw4w9WgXcQ` so the
 *      same trackId can hold two different files (different codecs)
 *      simultaneously.
 *   2. If the URI carries `?trackKey=...`, use that. Defensive — covers
 *      callers that build a DataSpec without setKey but embed the key
 *      in the URI.
 *   3. Fall back to the URI itself. ExoPlayer's default behaviour, fine
 *      for any non-search-tab use of the same SimpleCache.
 *
 * Note: media3 renamed `DataSpec.customCacheKey` (older docs) to
 * `DataSpec.key` in current versions; the field is `public final
 * String key;` and the builder is `Builder.setKey(String)`.
 */
class TrackKeyCacheKeyFactory @Inject constructor() : CacheKeyFactory {
    override fun buildCacheKey(spec: DataSpec): String =
        spec.key
            ?: spec.uri.getQueryParameter("trackKey")
            ?: spec.uri.toString()
}
