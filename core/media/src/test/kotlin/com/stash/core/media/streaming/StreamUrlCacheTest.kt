package com.stash.core.media.streaming

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class StreamUrlCacheTest {

    private var now = 0L
    private val cache = StreamUrlCache().apply { nowMsProvider = { now } }

    @Test
    fun get_returnsNullForUnknownKey() {
        assertThat(cache.get(1L)).isNull()
    }

    @Test
    fun get_returnsCachedValueWithinTtl() {
        cache.put(1L, StreamUrl(url = "https://cdn/file?etsp=1", expiresAtMs = 1000L))
        now = 500L

        val hit = cache.get(1L)

        assertThat(hit).isNotNull()
        assertThat(hit!!.url).contains("https://")
        assertThat(hit.expiresAtMs).isEqualTo(1000L)
    }

    @Test
    fun get_returnsNullPastExpiry() {
        cache.put(1L, StreamUrl(url = "https://cdn/file?etsp=1", expiresAtMs = 1000L))
        now = 1001L

        assertThat(cache.get(1L)).isNull()
    }

    @Test
    fun get_returnsNullAtExactExpiryBoundary() {
        // Boundary semantics: entry is valid while expiresAtMs > now.
        // At now == expiresAtMs the signature is no longer trustworthy.
        cache.put(1L, StreamUrl(url = "https://cdn/file?etsp=1", expiresAtMs = 1000L))
        now = 1000L

        assertThat(cache.get(1L)).isNull()
    }

    @Test
    fun put_overwritesExistingEntry() {
        cache.put(1L, StreamUrl(url = "https://cdn/old", expiresAtMs = 1000L))
        cache.put(1L, StreamUrl(url = "https://cdn/new", expiresAtMs = 2000L))
        now = 1500L

        val hit = cache.get(1L)

        assertThat(hit).isNotNull()
        assertThat(hit!!.url).isEqualTo("https://cdn/new")
        assertThat(hit.expiresAtMs).isEqualTo(2000L)
    }

    @Test
    fun invalidate_dropsEntry() {
        cache.put(1L, StreamUrl(url = "https://cdn/file", expiresAtMs = 1000L))
        now = 500L

        cache.invalidate(1L)

        assertThat(cache.get(1L)).isNull()
    }
}
