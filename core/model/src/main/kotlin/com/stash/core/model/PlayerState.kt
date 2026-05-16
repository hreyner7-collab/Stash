package com.stash.core.model

data class PlayerState(
    val currentTrack: Track? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val isShuffleEnabled: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val queue: List<Track> = emptyList(),
    val currentIndex: Int = 0,
    /**
     * `true` when the currently-loaded MediaItem's URI scheme is `http`
     * or `https` — i.e. the active track is being streamed rather than
     * read from local storage. Computed from the player's
     * `currentMediaItem.localConfiguration.uri.scheme` on every state
     * refresh (see `PlayerRepositoryImpl.updateState`).
     */
    val isStreaming: Boolean = false,
)

enum class RepeatMode {
    OFF, ALL, ONE
}
