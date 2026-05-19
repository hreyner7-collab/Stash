package com.stash.core.model

data class Track(
    val id: Long = 0,
    val title: String,
    val artist: String,
    val album: String = "",
    val durationMs: Long = 0,
    val filePath: String? = null,
    val fileFormat: String = "opus",
    val qualityKbps: Int = 0,
    val fileSizeBytes: Long = 0,
    val source: MusicSource = MusicSource.SPOTIFY,
    val spotifyUri: String? = null,
    val youtubeId: String? = null,
    val albumArtUrl: String? = null,
    val albumArtPath: String? = null,
    val dateAdded: Long = System.currentTimeMillis(),
    val lastPlayed: Long? = null,
    val playCount: Int = 0,
    val isDownloaded: Boolean = false,
    val matchConfidence: Float = 0f,
    val matchDismissed: Boolean = false,
    /**
     * International Standard Recording Code — Spotify's per-master unique
     * identifier. Null for YouTube-sourced tracks and for legacy Spotify
     * rows inserted before the matcher started requesting it. Used as the
     * highest-precision signal when matching to canonical YouTube uploads.
     */
    val isrc: String? = null,
    /**
     * Spotify's parental-advisory flag. Null for YouTube-sourced tracks and
     * legacy rows; true/false for Spotify rows synced post-v12. Matcher
     * prefers candidates whose explicitness matches the source.
     */
    val explicit: Boolean? = null,
    /**
     * Bit-depth of the on-disk audio (16, 24, 32). NULL when unknown
     * (lossy codecs, legacy rows pre-backfill, unparseable files).
     */
    val bitsPerSample: Int? = null,
    /**
     * Audio sample rate in Hz (44100, 48000, 96000, 192000). NULL when unknown.
     */
    val sampleRateHz: Int? = null,
    /**
     * v0.9.13 — see TrackEntity.spotifySavedAt KDoc.
     */
    val spotifySavedAt: Long? = null,
    val ytMusicSavedAt: Long? = null,
    val stashLikedAt: Long? = null,
    /**
     * v0.9.27: cached Kennyy-proxy resolvability. True = the streaming
     * engine can play this track without a downloaded file. Defaults to
     * false so legacy rows surface as "not yet known streamable" until
     * `AvailabilityCheckWorker` drains the backlog. See
     * `TrackEntity.isStreamable`.
     */
    val isStreamable: Boolean = false,
    /**
     * v0.9.27: epoch-millis of the streamability lookup (success OR
     * failure). NULL = never checked; tristate sentinel that pairs with
     * [isStreamable] to distinguish "unavailable on Kennyy" from "we
     * haven't asked yet." Drives the library row's greyed-out treatment
     * — only checked-and-unavailable rows go grey. See
     * `TrackEntity.isStreamableCheckedAt`.
     */
    val isStreamableCheckedAt: Long? = null,
)
