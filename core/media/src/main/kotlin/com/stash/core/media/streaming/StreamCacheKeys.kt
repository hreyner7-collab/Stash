package com.stash.core.media.streaming

/**
 * Stable cache key for streamed audio bytes in the @StreamCache
 * [androidx.media3.datasource.cache.SimpleCache].
 *
 * **Why not the default (URL) key.** Signed CDN URLs change on every
 * resolve — kennyy/squid bake an `etsp` signature, YouTube an `expire`
 * param — so URL-keyed cache entries become unreachable the moment the
 * URL is re-resolved (hours later, or after a 403 refresh). Every replay
 * re-downloaded the whole track. Keying by track identity instead makes
 * cached audio survive URL rotation: a track you played yesterday starts
 * instantly from disk today, Spotify-style.
 *
 * **Why origin + format are in the key.** The same track id can be served
 * as kennyy FLAC today and YouTube AAC tomorrow (lossless outage). Mixing
 * those byte streams under one key would interleave two different
 * encodings into one cache entry — garbage on read. Including
 * origin/codec/bit-depth/sample-rate partitions the cache so each
 * encoding caches independently.
 */
fun streamCacheKey(trackId: Long, stream: StreamUrl): String =
    "stash:$trackId:${stream.origin ?: "unknown"}:${stream.codec ?: "?"}" +
        ":${stream.bitsPerSample ?: 0}:${stream.sampleRateHz ?: 0}"
