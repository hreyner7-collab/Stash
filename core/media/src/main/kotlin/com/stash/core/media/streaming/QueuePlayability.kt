package com.stash.core.media.streaming

import com.stash.core.model.Track

/**
 * The subset of [tracks] that can actually be enqueued right now.
 *
 * Streaming mode → every track (stream-only ones resolve via the Kennyy/Squid
 * chain inside the player). Offline mode → downloaded-only, EXCEPT a Stash Mix
 * with a live connection: a Mix is an inherently online discovery surface
 * (stream-only by design), so its streamable tracks stay enqueueable whenever
 * the device is connected, regardless of the Online/Offline preference. With
 * no connection it falls back to downloaded-only; the per-tap guard in
 * PlaylistDetailViewModel surfaces "Online only — connect to play" for a tapped
 * stream-only track.
 *
 * Pure (no I/O) so it is the single, exhaustively-testable source of truth for
 * the rule shared by PlaylistDetailViewModel and HomeViewModel.
 */
fun queuePlayableTracks(
    tracks: List<Track>,
    isMix: Boolean,
    streamingEnabled: Boolean,
    connected: Boolean,
): List<Track> =
    if (streamingEnabled || (isMix && connected)) tracks
    else tracks.filter { it.filePath != null }
