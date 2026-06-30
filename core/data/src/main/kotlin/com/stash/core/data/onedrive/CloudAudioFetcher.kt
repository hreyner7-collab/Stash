package com.stash.core.data.onedrive

import java.io.File

/**
 * Port used by [OneDriveSyncManager]'s cloud-fill phase to obtain a
 * track's COMPLETE audio without a permanent download: the implementation
 * (in `core/media`, where the streaming resolvers live) resolves a
 * full-playback stream URL and spools the bytes into a temp file the
 * caller uploads and then deletes.
 *
 * Defined here as an interface because the dependency arrow points
 * `core/media -> core/data`; the manager can't import the resolver
 * machinery directly.
 */
interface CloudAudioFetcher {

    /**
     * @param file complete audio bytes — caller owns deletion.
     * @param extension suggested file extension matching the actual
     *   container ("flac", "webm", "m4a", ...).
     */
    data class FetchedAudio(val file: File, val extension: String)

    /**
     * Fetches [trackId]'s complete audio to a temp file, or null when the
     * track can't be served right now (no full-playback source, metered
     * network with cellular streaming off, or playback is active and owns
     * the bandwidth). Null is a "skip for now", never an error.
     */
    suspend fun fetchToTempFile(trackId: Long): FetchedAudio?
}
