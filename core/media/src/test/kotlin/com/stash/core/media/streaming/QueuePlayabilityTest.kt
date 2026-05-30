package com.stash.core.media.streaming

import com.stash.core.model.Track
import org.junit.Assert.assertEquals
import org.junit.Test

class QueuePlayabilityTest {
    private fun dl(id: Long) = Track(id = id, title = "d$id", artist = "a", isDownloaded = true, isStreamable = true, filePath = "/m/$id.opus")
    private fun stream(id: Long) = Track(id = id, title = "s$id", artist = "a", isDownloaded = false, isStreamable = true, filePath = null)
    private val tracks = listOf(dl(1), stream(2))

    @Test fun `streaming on returns all regardless of mix or connection`() {
        assertEquals(listOf(1L, 2L), queuePlayableTracks(tracks, isMix = false, streamingEnabled = true, connected = false).map { it.id })
        assertEquals(listOf(1L, 2L), queuePlayableTracks(tracks, isMix = true, streamingEnabled = true, connected = false).map { it.id })
    }
    @Test fun `offline mix connected returns all`() {
        assertEquals(listOf(1L, 2L), queuePlayableTracks(tracks, isMix = true, streamingEnabled = false, connected = true).map { it.id })
    }
    @Test fun `offline mix disconnected returns downloaded only`() {
        assertEquals(listOf(1L), queuePlayableTracks(tracks, isMix = true, streamingEnabled = false, connected = false).map { it.id })
    }
    @Test fun `offline non-mix returns downloaded only even when connected`() {
        assertEquals(listOf(1L), queuePlayableTracks(tracks, isMix = false, streamingEnabled = false, connected = true).map { it.id })
    }
}
