package com.stash.core.media.streaming

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient

/**
 * Qualifier for the dedicated streaming-read [OkHttpClient]. Distinct
 * from any API-layer client (kennyy/squid/InnerTube have their own in
 * the data modules) so streaming transfers can't exhaust an API
 * client's dispatcher and vice versa.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class StreamingHttpClient

/**
 * Pooled HTTP stack for audio stream reads ([StreamingMediaSourceFactory]
 * and [StreamHeadWarmer]).
 *
 * The previous stack ([androidx.media3.datasource.DefaultHttpDataSource],
 * HttpURLConnection-based) paid a fresh TCP + TLS handshake far more
 * often than necessary — ~100-400 ms of pure connection setup ahead of
 * the first audio byte on many track starts. OkHttp's connection pool
 * keeps sockets to recently-used CDN hosts open and warm:
 *
 *  - Successive tracks from the same source (Qobuz CDN; YouTube edge
 *    hosts repeat across a session) reuse a live connection — the
 *    handshake cost drops to zero.
 *  - The pool is sized generously (8 idle, 5-minute keep-alive) because
 *    the read path talks to a handful of hosts repeatedly, and the
 *    head-warmer + live stream + ExoPlayer's next-item preload can
 *    overlap.
 *  - HTTP/2 (negotiated automatically over TLS) multiplexes overlapping
 *    range requests to the same host over ONE connection.
 */
@Module
@InstallIn(SingletonComponent::class)
object StreamingHttpModule {

    @Provides
    @Singleton
    @StreamingHttpClient
    fun provideStreamingOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
        // 5s (was 8): modern CDN handshakes complete in well under a
        // second; a dead edge node now fails over 3s sooner. Still
        // generous headroom for weak cellular.
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
}
