package com.stash.core.media.streaming

import android.util.Log
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.onedrive.OneDriveSyncManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Personal cloud first": resolves tracks against the user's own OneDrive
 * warehouse (`/Stash/Music/`, populated by
 * [com.stash.core.data.onedrive.OneDriveSyncManager]) before any
 * third-party source is consulted.
 *
 * Why first in the chain: the warehouse is the user's own storage —
 * always on, reachable from anywhere, no catalog lookup, no extraction,
 * no rate limits. A hit costs one Graph round-trip (~200-400 ms, cached
 * by the registry chokepoint for the URL's lifetime) and streams over a
 * range-capable pre-authenticated URL — the exact shape the player's
 * cache + refresh seams already speak.
 *
 * Self-gating like [AntraStreamResolver]: instantly null when the user
 * hasn't connected OneDrive or the track isn't synced, so the chain
 * flows on without cost.
 */
@Singleton
class OneDriveStreamResolver @Inject constructor(
    private val syncManager: OneDriveSyncManager,
) {
    suspend fun resolve(track: TrackEntity): StreamUrl? {
        val url = syncManager.streamingUrlFor(track.id) ?: return null
        Log.d(TAG, "warehouse hit for ${track.id} '${track.title}'")
        return StreamUrl(
            url = url,
            // Graph download URLs live ~1 h; refresh comfortably early.
            expiresAtMs = System.currentTimeMillis() + URL_TTL_MS,
            origin = ORIGIN,
        )
    }

    companion object {
        const val ORIGIN = "onedrive"
        private const val TAG = "OneDriveResolver"
        private const val URL_TTL_MS = 50L * 60 * 1000
    }
}
