package com.stash.core.media.streaming

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Hilt qualifier for the streaming [SimpleCache].
 *
 * Disambiguates from the existing preview [SimpleCache] (see
 * `PreviewCacheModule`) — Hilt would otherwise fail with multiple bindings
 * for the same type. Consumers must inject the streaming cache as:
 *
 * ```
 * @StreamCache private val streamCache: SimpleCache
 * ```
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class StreamCache

/**
 * Hilt-provides the Media3 [SimpleCache] used to cache streamed bytes
 * from online playback (KennyySource / Qobuz).
 *
 * Separate cache instance from `PreviewCacheModule` so eviction policies
 * don't collide — short preview snippets and full streamed tracks have
 * different LRU footprints. Backed by 2 GB on disk under
 * `<cacheDir>/stream_cache/` (OS may evict on storage pressure, which is
 * fine — streamed bytes are throwaway). 2 GB holds roughly 25-60 hours of
 * streamed audio — plenty for the active listening rotation. Kept
 * deliberately small because the user's real library lives on OneDrive:
 * anything synced there is the permanent copy, so the on-device cache only
 * needs to cover recent playback (LRU evicts oldest-played first).
 */
@Module
@InstallIn(SingletonComponent::class)
object StreamCacheModule {
    private const val MAX_BYTES = 2L * 1024 * 1024 * 1024  // 2 GB
    private const val CACHE_DIR = "stream_cache"

    @Provides
    @Singleton
    @StreamCache
    fun provideStreamCache(@ApplicationContext context: Context): SimpleCache {
        val dir = File(context.cacheDir, CACHE_DIR).also { it.mkdirs() }
        // Separate database provider per cache instance (Media3 requirement;
        // sharing across SimpleCache instances corrupts internal state).
        return SimpleCache(
            dir,
            LeastRecentlyUsedCacheEvictor(MAX_BYTES),
            StandaloneDatabaseProvider(context),
        )
    }
}
