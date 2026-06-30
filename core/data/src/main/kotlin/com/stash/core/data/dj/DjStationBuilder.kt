package com.stash.core.data.dj

import android.util.Log
import com.stash.core.data.db.dao.ListeningEventDao
import com.stash.core.data.db.dao.PlaylistDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.model.MusicSource
import com.stash.core.model.Track
import com.stash.data.ytmusic.YTMusicApiClient
import com.stash.data.ytmusic.model.SearchResultSection
import com.stash.data.ytmusic.model.YTMusicTrack
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The "DJ" brain — builds an endless, personalised discovery station from
 * the user's own listening history, the same way a Spotify "DJ"/radio does.
 *
 * ## How it picks songs (collaborative filtering, free)
 *
 * It seeds from what you actually play ([TrackDao.getMostPlayed], then
 * recently-added as a fallback for a fresh library), then pulls YouTube
 * Music's **radio** queue for each seed. YT Music radio runs on the same
 * kind of massive co-listening signal Spotify uses ("people who play X also
 * play Y"), so the picks are genuinely strong — not a cheap substitute.
 *
 * The per-seed radios are blended and de-duplicated so the station is a mix
 * across your taste, not a loop of one song. Every returned [Track] carries
 * a `youtubeId` and `isStreamable = true`, so the player resolves + streams
 * it through the normal path; the look-ahead in
 * [com.stash.core.media.PlayerRepository.setQueue] keeps the *next* songs
 * resolved while the current one plays, which is what makes it feel instant.
 *
 * No API key, no cost: the LLM "DJ voice" / smarter cross-catalogue seeding
 * is a later layer that plugs into the same queue.
 */
@Singleton
class DjStationBuilder @Inject constructor(
    private val trackDao: TrackDao,
    private val listeningEventDao: ListeningEventDao,
    private val playlistDao: PlaylistDao,
    private val api: YTMusicApiClient,
) {
    /**
     * Build a fresh discovery station. Returns an empty list only when the
     * library has nothing to seed from yet (a brand-new install that hasn't
     * played or synced anything) — the caller surfaces a "play something
     * first" hint in that case.
     */
    suspend fun buildStation(): List<Track> {
        // 1. Seeds, in priority order — broad on purpose so the DJ works as
        //    soon as you've played a couple of songs OR have any synced
        //    library, NOT only when tracks are downloaded / have an in-app
        //    play_count (the old bug: streamed plays never bump play_count,
        //    so "play 40 songs" still found zero seeds):
        //      a) songs you've actually PLAYED recently (listening history,
        //         incl. streamed search/artist plays) — strongest signal
        //      b) most-played library rows
        //      c) your whole library (any synced track), newest first
        val playedIds = runCatching {
            listeningEventDao.getTrackIdsPlayedSince(
                System.currentTimeMillis() - HISTORY_WINDOW_MS,
            )
        }.getOrDefault(emptyList())
        val playedTracks: List<TrackEntity> = playedIds.mapNotNull { trackDao.getById(it) }

        // Every song in every synced playlist (Spotify / YT Music) — this is
        // the "as soon as I sign in, use all my songs" path: linking a source
        // syncs its playlists into the DB, and we seed straight off them with
        // no extra setup or any need to play songs first.
        val playlistTracks: List<TrackEntity> = runCatching {
            playlistDao.getAllActive().first()
                .flatMap { pl ->
                    runCatching { playlistDao.getTracksForPlaylist(pl.id) }.getOrDefault(emptyList())
                }
        }.getOrDefault(emptyList())

        val library = trackDao.getAllByDateAdded().first()
        val seeds = (
            playedTracks +
                playlistTracks +
                trackDao.getMostPlayed(SEED_POOL).first() +
                library
            )
            .distinctBy { it.id }
            .take(SEED_POOL)
        Log.d(
            TAG,
            "buildStation seeds: played=${playedTracks.size} playlists=${playlistTracks.size} " +
                "library=${library.size} candidatePool=${seeds.size}",
        )

        // 2. Resolve up to MAX_SEEDS seed videoIds. Library rows synced from
        //    Spotify may have no youtubeId, so fall back to a canonical
        //    YT Music search on "artist title".
        val seedVideoIds = mutableListOf<String>()
        for (t in seeds) {
            if (seedVideoIds.size >= MAX_SEEDS) break
            val vid = t.youtubeId?.takeIf { it.isNotBlank() }
                ?: runCatching { api.searchCanonicalVideoId(t.artist, t.title) }.getOrNull()
            if (!vid.isNullOrBlank() && vid !in seedVideoIds) seedVideoIds.add(vid)
        }
        Log.d(TAG, "buildStation: resolved ${seedVideoIds.size} seed videoIds from ${seeds.size} candidates")

        // Zero-data fallback: a fresh install (or a clean reinstall, which
        // wipes the local DB) leaves no library / history / playlists to seed
        // from. Rather than dead-end with "play a few songs first", seed from
        // a popular-songs search so the DJ ALWAYS plays. It personalises the
        // moment any real taste data exists (a synced library or a few plays).
        if (seedVideoIds.isEmpty()) {
            Log.d(TAG, "buildStation: no local taste data — seeding a starter station")
            for (q in FALLBACK_QUERIES) {
                if (seedVideoIds.size >= MAX_SEEDS) break
                val vids = runCatching { searchSeedVideoIds(q) }.getOrDefault(emptyList())
                for (v in vids) {
                    if (seedVideoIds.size >= MAX_SEEDS) break
                    if (v !in seedVideoIds) seedVideoIds.add(v)
                }
            }
            Log.d(TAG, "buildStation: starter seeds resolved=${seedVideoIds.size}")
        }
        if (seedVideoIds.isEmpty()) {
            Log.d(TAG, "buildStation: starter seeding also failed (offline?)")
            return emptyList()
        }

        // 3. Pull each seed's radio queue and blend. `RDAMVM<videoId>` is the
        //    "start radio from this song" playlist; getPlaylistTracks already
        //    knows how to walk an RD* radio (capped to one page).
        val seen = seedVideoIds.toMutableSet()
        val station = mutableListOf<Track>()
        for (vid in seedVideoIds) {
            val tracks = runCatching { api.getRadioTracks(vid) }.getOrDefault(emptyList())
            var addedFromThisSeed = 0
            for (yt in tracks) {
                if (addedFromThisSeed >= PER_SEED_CAP) break
                if (yt.videoId in seen) continue
                seen.add(yt.videoId)
                station.add(yt.toDomainTrack())
                addedFromThisSeed++
            }
            Log.d(TAG, "buildStation: seed '$vid' -> ${tracks.size} radio tracks, kept $addedFromThisSeed")
        }

        // 4. Interleave the seeds' radios (shuffle) so the station feels like
        //    a mix of your taste, then cap it. The queue refills when it runs
        //    low, so this is just the first batch.
        val out = station.shuffled().take(STATION_SIZE)
        Log.d(TAG, "buildStation: ${seedVideoIds.size} seeds -> ${out.size} station tracks")
        return out
    }

    /** A couple of song videoIds from a YT Music search — starter-station seeds. */
    private suspend fun searchSeedVideoIds(query: String): List<String> {
        val results = api.searchAll(query)
        val songs = results.sections.firstNotNullOfOrNull { it as? SearchResultSection.Songs }
        return songs?.tracks?.map { it.videoId }?.filter { it.isNotBlank() }?.take(2).orEmpty()
    }

    private fun YTMusicTrack.toDomainTrack(): Track = Track(
        id = videoId.hashCode().toLong(),
        title = title,
        artist = artists,
        album = album ?: "",
        durationMs = durationMs ?: 0L,
        albumArtUrl = thumbnailUrl,
        youtubeId = videoId,
        source = MusicSource.YOUTUBE,
        isStreamable = true,
    )

    private companion object {
        const val TAG = "DjStation"

        /** Look back this far for "songs you've played" seeds (90 days). */
        const val HISTORY_WINDOW_MS = 90L * 24 * 60 * 60 * 1000

        /** How many candidate rows to consider when picking seeds. */
        const val SEED_POOL = 20

        /** Distinct seed songs to build radios from (more = broader mix). */
        const val MAX_SEEDS = 4

        /** Max tracks kept from any single seed's radio (keeps the mix even). */
        const val PER_SEED_CAP = 15

        /** Size of the first station batch handed to the player. */
        const val STATION_SIZE = 40

        /**
         * Starter-station search seeds, used only when there's no local taste
         * data yet (fresh/clean install). Broad + current so the DJ plays
         * something good immediately; it personalises as soon as a library
         * syncs or songs are played.
         */
        val FALLBACK_QUERIES = listOf("top hits", "popular songs", "new music")
    }
}
