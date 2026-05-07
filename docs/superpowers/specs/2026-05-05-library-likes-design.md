# v0.9.13 — Library & Likes (Auto-Save + Manual Heart)

**Date:** 2026-05-05
**Status:** Design
**Branch:** `feat/v0.9.13-library-likes` (worktree path: `.worktrees/v0.9.13-library-likes`)

## Problem

Spotify's algorithmic mixes (Daily Mix, Discover Weekly, Release Radar, On Repeat, Top Mix) consume the user's recent-play history as input. Stash users who play music exclusively in Stash never generate plays inside Spotify's event ingestion — so Spotify's algorithms see them as inactive, and the mixes stagnate or repeat. The mixes can be re-fetched cleanly (the v0.9.10/v0.9.11/v0.9.12 sync infrastructure works), but the *content* doesn't change because there's no signal feeding the algorithm.

The original framing was "report Stash's plays back to Spotify." Research surfaced that this path is dead-end:

- Spotube received a Spotify cease-and-desist in early 2025 and stripped Spotify integration entirely.
- librespot's play-reporting is incomplete and unproven; librespot-java is deprecated; go-librespot is GPL-3.0 (license contagion for Stash).
- All viable play-injection paths require Spotify Premium.
- Spotify's BART recommendation algorithm weights **saves** higher than raw plays anyway.

The unlock: **save tracks via Spotify's public `PUT /v1/me/tracks` endpoint** (scope `user-library-modify`, free-tier accessible, ToS-clean, no reverse engineering, idempotent). Saves are a stronger algorithm signal than plays AND the simplest implementation path. Same approach extends to YouTube Music's `/like/like` endpoint and a new local Stash "Liked Songs" playlist.

## Goals

- The user can heart a track in Stash with a single tap and have it land in their configured destinations: a local Stash "Liked Songs" playlist, Spotify Liked Songs, and/or YouTube Music Liked Music. Long-press the heart for per-track destination override.
- Stash auto-saves a track to Spotify Liked Songs after the user has played it on N distinct days within the last 30 days (default N=3, slider 1-10). Off by default.
- Saves never duplicate (forward-only — once saved, never re-fired). API calls are idempotent.
- All saves use Spotify's and YouTube Music's public APIs with existing auth (sp_dc + InnerTube SAPISID-hash). No reverse-engineered endpoints, no librespot, no Premium requirement, no ToS exposure.
- The new "Liked Songs" Stash playlist is local-only, parallel to the existing `DOWNLOADS_MIX` infrastructure.

## Non-goals (explicit)

- **No reverse-engineered Spotify Connect / dealer.spotify.com WebSocket** integration. Public API only.
- **No play-injection** (librespot / phantom Connect device / fabricated playback events). The Spotube C&D made that path legally untenable.
- **No backfill** ("save my entire Stash listening history to Spotify at once"). Forward-only from v0.9.13 ship.
- **No "unlike" / "remove from Liked" UI** in v0.9.13. Heart button only adds; removal via Spotify Web / YT Music app. v0.9.14 polish.
- **No lock-screen / media-notification heart action.** Custom `MediaSession` action — v0.9.14 polish.
- **No per-track UI dedup** (a Spotify-imported track can appear in both `LIKED_SONGS` and `STASH_LIKED`). v0.9.14 polish.
- **No "boost Spotify recommendations" foreground WebView path.** Reserved for v0.9.14 if empirical data shows saves alone don't refresh mixes.
- **No unit tests.** Project precedent.

## Design

### 1. Architecture overview

Two trigger mechanisms feeding one shared dispatcher.

| Component | Location | Responsibility |
|---|---|---|
| `AutoSaveScrobbler` (NEW) | `core/data/.../sync/workers/AutoSaveScrobbler.kt` | Listens to `ListeningRecorder` completion events. Fires Spotify save when the distinct-days threshold is met. |
| `LikeButton` (NEW) | `core/ui/components/LikeButton.kt` | Heart composable. Tap fires to user-configured defaults; long-press opens per-track override sheet. |
| `LikeDestinationDispatcher` (NEW) | `core/data/social/LikeDestinationDispatcher.kt` | Stateless fan-out. Both auto-save and heart funnel through it. Dedups via per-destination `*_saved_at` timestamps. |
| `SpotifyLibraryApiClient` (NEW) | `core/data/social/spotify/SpotifyLibraryApiClient.kt` | Wraps `PUT /v1/me/tracks`. Reuses existing `SpotifyAuthManager` web-player token. |
| `YtMusicLibraryApiClient` (NEW) | `data/ytmusic/.../YtMusicLibraryApiClient.kt` | Wraps InnerTube `/like/like`. Reuses existing SAPISID-hash auth. |
| `StashLikedPlaylistRepository` (NEW) | `core/data/social/stash/StashLikedPlaylistRepository.kt` | Manages local "Liked Songs" playlist (`PlaylistType.STASH_LIKED`). Lazy-seeded; idempotent add. |
| `LikePreferences` (NEW) | `core/data/.../prefs/LikePreferences.kt` | DataStore-backed user toggles + threshold slider. |
| `ListeningRecorder` (existing — extended) | `core/media/listening/ListeningRecorder.kt` | Now also writes `completed_at` timestamp to `ListeningEventEntity` on threshold crossing. |

**No new Gradle module.** All new code lives under `core/data/social/`, `core/ui/`, and `data/ytmusic/` (existing). Hilt graph extends; no new module wiring.

**Auth re-use.** sp_dc cookie → web-player token (existing flow) covers `user-library-modify` scope (Spotify Web's Like button uses the same scope). InnerTube SAPISID-hash (existing flow) covers `like/like`. No new auth flows; no Premium requirement.

### 2. Trigger mechanics

#### 2.1 `AutoSaveScrobbler`

Subscribes to `ListeningRecorder` (existing pub/sub). For each play-completion event:

```kotlin
suspend fun onPlayCompleted(trackId: Long) {
    val track = trackDao.getById(trackId) ?: return
    if (track.spotifyUri == null) return
    if (track.spotifySavedAt != null) return
    if (!likePreferences.autoSaveEnabledNow()) return

    val threshold = likePreferences.autoSaveThresholdNow()
    val sinceMs = System.currentTimeMillis() - 30L * 24 * 3600 * 1000
    val distinctDays = listeningEventDao.distinctDaysCompletedFor(trackId, sinceMs)
    if (distinctDays < threshold) return

    val ok = likeDispatcher.like(track, setOf(Destination.SPOTIFY))
        .get(Destination.SPOTIFY)?.isSuccess == true
    // dispatcher updates track.spotifySavedAt on success
    if (!ok) Log.w(TAG, "auto-save failed for $trackId, will retry next play")
}
```

#### 2.2 Completion detection

Mirrors `LastFmScrobbler`'s rule (existing): a play counts as "completed" when it crosses **≥50% of duration OR ≥30s, whichever first.** Existing `ListeningRecorder` already detects this for the LastFm path.

v0.9.13 extends `ListeningRecorder.onPlayCompleted(eventId)` to ALSO call `listeningEventDao.markCompleted(eventId, ts)`, populating a new nullable `completed_at` column on `ListeningEventEntity`. AutoSaveScrobbler queries against `completed_at IS NOT NULL`.

#### 2.3 The threshold query

```kotlin
@Query("""
    SELECT COUNT(DISTINCT date(completed_at / 1000, 'unixepoch'))
    FROM listening_events
    WHERE track_id = :trackId
      AND completed_at IS NOT NULL
      AND completed_at > :sinceMs
""")
suspend fun distinctDaysCompletedFor(trackId: Long, sinceMs: Long): Int
```

`date(.., 'unixepoch')` converts epoch-millis to YYYY-MM-DD (UTC); `COUNT(DISTINCT date)` returns the unique-day count. Native SQLite. ~1-2ms.

**Distinct days, not raw count** — 3 plays in 1 hour ("checking it out") doesn't fire; 3 plays across 3 days ("genuinely returning") does. Better signal quality than raw plays.

#### 2.4 Inline path, no save_queue

The save runs in the scrobbler's coroutine — no separate queue or worker. Volume is low (dozens of saves/month per active user). Failure recovery is organic: `spotifySavedAt` stays null on failure; next play of the same track re-evaluates the threshold (same query, same result) and re-fires. Spotify's `PUT /v1/me/tracks` is idempotent — safe to call twice.

#### 2.5 Forward-only

Once `spotifySavedAt` is set, never re-fire for that track. If user un-likes externally, accept it. v0.9.14 may add a "Reset auto-saves" Settings action.

### 3. Manual heart button + dispatcher

#### 3.1 `LikeButton` composable

```kotlin
@Composable
fun LikeButton(
    track: Track,
    likedState: LikedState,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
)

data class LikedState(
    val stash: Boolean,
    val spotify: Boolean,
    val ytMusic: Boolean,
) {
    val anyLiked: Boolean = stash || spotify || ytMusic
}
```

`Icons.Filled.Favorite` (red) when `anyLiked`, `Icons.Outlined.FavoriteBorder` otherwise. Pointer modifier intercepts both tap and long-press.

#### 3.2 Z4 — one-tap default fan-out

On `onTap`:
1. Read `likePreferences.heartDefaultStash / Spotify / YtMusic` → build `Set<Destination>` of enabled defaults.
2. `likeDispatcher.like(track, defaults)`.
3. Per-destination result map updates `*_saved_at` timestamps. UI recomposes via observable Flow on the underlying `TrackEntity` row.
4. Snackbar on per-destination failure: `"Couldn't save to Spotify (offline). Saved to Stash."`

On `onLongPress`:
1. Open `LikeDestinationSheet` modal bottom sheet.
2. Three checkboxes pre-checked according to user defaults. Each labelled with track-specific status: `"Stash Liked Songs"`, `"Spotify Liked Songs ✓ Already saved"` (subtitle), `"YouTube Music Liked Music"`.
3. User adjusts checks; tap "Save" → dispatcher fires only the destinations user kept checked AND not already saved. Sheet closes.
4. Per-track override is **ephemeral** — doesn't update user defaults.

#### 3.3 `LikeDestinationDispatcher`

Stateless API:

```kotlin
@Singleton
class LikeDestinationDispatcher @Inject constructor(
    private val spotifyLibraryClient: SpotifyLibraryApiClient,
    private val ytMusicLibraryClient: YtMusicLibraryApiClient,
    private val stashLikedRepository: StashLikedPlaylistRepository,
    private val trackDao: TrackDao,
) {
    suspend fun like(
        track: Track,
        destinations: Set<Destination>,
    ): Map<Destination, Result<Unit>> = coroutineScope {
        destinations.associateWith { dest ->
            async { fireDestination(track, dest) }
        }.mapValues { it.value.await() }
    }

    private suspend fun fireDestination(track: Track, dest: Destination): Result<Unit> {
        if (alreadySaved(track, dest)) return Result.success(Unit)
        return runCatching {
            when (dest) {
                Destination.STASH -> stashLikedRepository.add(track.id)
                Destination.SPOTIFY -> {
                    val uri = track.spotifyUri ?: return Result.failure(NoSpotifyUriException())
                    spotifyLibraryClient.saveTracks(listOf(uri))
                    trackDao.markSpotifySaved(track.id, System.currentTimeMillis())
                }
                Destination.YT_MUSIC -> {
                    val videoId = track.youtubeId ?: return Result.failure(NoYouTubeIdException())
                    ytMusicLibraryClient.likeVideo(videoId)
                    trackDao.markYtMusicSaved(track.id, System.currentTimeMillis())
                }
            }
        }
    }

    private fun alreadySaved(track: Track, dest: Destination): Boolean = when (dest) {
        Destination.STASH -> track.stashLikedAt != null
        Destination.SPOTIFY -> track.spotifySavedAt != null
        Destination.YT_MUSIC -> track.ytMusicSavedAt != null
    }
}

enum class Destination { STASH, SPOTIFY, YT_MUSIC }
```

Parallel `async`/`await` — three destinations resolve in slowest-of-three time, not sum.

#### 3.4 Heart placement (v0.9.13 surfaces)

1. **Now Playing top bar** — heart icon next to track title. Tap fires defaults, long-press opens override sheet.
2. **Track-row 3-dot context menu** — "Like" entry triggers tap-equivalent only (no long-press affordance).

Lock-screen / media-notification action and inline-on-row heart deferred to v0.9.14.

### 4. Stash "Liked Songs" playlist + schema migration

#### 4.1 `PlaylistType.STASH_LIKED`

New enum value in `core/model/Playlist.kt`. Mirrors `DOWNLOADS_MIX`'s lifecycle: exactly one row per install, seeded lazily on first heart-tap-with-Stash-destination, name = `"Liked Songs"`, source = `MusicSource.BOTH`, syncEnabled = false.

```kotlin
/**
 * User's local "Liked Songs" — populated by the heart button.
 * Independent of Spotify Liked Songs and YT Music Liked Music
 * (which are written by the dispatcher to those external services
 * directly). For users without Spotify/YT connected, this is the
 * only place hearts land. Seeded on first like, never deleted by
 * orphan cleanup.
 */
STASH_LIKED,
```

#### 4.2 `StashLikedPlaylistRepository`

```kotlin
@Singleton
class StashLikedPlaylistRepository @Inject constructor(
    private val playlistDao: PlaylistDao,
    private val trackDao: TrackDao,
) {
    suspend fun add(trackId: Long) {
        val playlistId = ensureSeeded()
        if (playlistDao.getCrossRef(playlistId, trackId) != null) return
        playlistDao.insertCrossRef(
            PlaylistTrackCrossRef(playlistId = playlistId, trackId = trackId, addedAt = now())
        )
        trackDao.markStashLiked(trackId, now())
    }

    private suspend fun ensureSeeded(): Long {
        playlistDao.findBySourceId(STASH_LIKED_SOURCE_ID)?.let { return it.id }
        return playlistDao.insert(PlaylistEntity(
            name = "Liked Songs",
            source = MusicSource.BOTH,
            sourceId = STASH_LIKED_SOURCE_ID,
            type = PlaylistType.STASH_LIKED,
            syncEnabled = false,
        ))
    }

    companion object {
        private const val STASH_LIKED_SOURCE_ID = "stash_liked_songs"
    }
}
```

Idempotent — same mental model as `MusicRepository.linkTrackToDownloadsMix`. `OnConflictStrategy.IGNORE` on the playlist insert handles seeding races.

#### 4.3 Schema migration v17 → v18

Three new nullable timestamp columns on `tracks`:

```sql
ALTER TABLE tracks ADD COLUMN spotify_saved_at INTEGER DEFAULT NULL;
ALTER TABLE tracks ADD COLUMN ytmusic_saved_at INTEGER DEFAULT NULL;
ALTER TABLE tracks ADD COLUMN stash_liked_at INTEGER DEFAULT NULL;
```

One new nullable column on `listening_events`:

```sql
ALTER TABLE listening_events ADD COLUMN completed_at INTEGER DEFAULT NULL;
```

`StashDatabase`: bump `@Database(version = 18)`, add `MIGRATION_17_18`, register in `DatabaseModule`. `core/data/schemas/.../18.json` auto-generates.

#### 4.4 New DAO methods

```kotlin
// TrackDao
@Query("UPDATE tracks SET spotify_saved_at = :ts WHERE id = :trackId")
suspend fun markSpotifySaved(trackId: Long, ts: Long)

@Query("UPDATE tracks SET ytmusic_saved_at = :ts WHERE id = :trackId")
suspend fun markYtMusicSaved(trackId: Long, ts: Long)

@Query("UPDATE tracks SET stash_liked_at = :ts WHERE id = :trackId")
suspend fun markStashLiked(trackId: Long, ts: Long)

// ListeningEventDao
@Query("UPDATE listening_events SET completed_at = :ts WHERE id = :eventId")
suspend fun markCompleted(eventId: Long, ts: Long)

@Query("""
    SELECT COUNT(DISTINCT date(completed_at / 1000, 'unixepoch'))
    FROM listening_events
    WHERE track_id = :trackId
      AND completed_at IS NOT NULL
      AND completed_at > :sinceMs
""")
suspend fun distinctDaysCompletedFor(trackId: Long, sinceMs: Long): Int
```

#### 4.5 Domain model + mapper

`Track.kt` (domain) gains `spotifySavedAt: Long? = null, ytMusicSavedAt: Long? = null, stashLikedAt: Long? = null`. `TrackMapper.toDomain` / `toEntity` updated to include them. Mirror v0.9.11's `bitsPerSample`/`sampleRateHz` plumbing.

### 5. API integrations

#### 5.1 `SpotifyLibraryApiClient`

```kotlin
@Singleton
class SpotifyLibraryApiClient @Inject constructor(
    private val authManager: SpotifyAuthManager,
    private val httpClient: OkHttpClient,
) {
    /**
     * `PUT /v1/me/tracks?ids=...`
     * Scope: user-library-modify (covered by sp_dc-derived web-player token)
     * Idempotent. Batch limit: 50 IDs per call.
     */
    suspend fun saveTracks(spotifyUris: List<String>) {
        require(spotifyUris.size <= 50)
        val token = authManager.getWebPlayerToken()
        val ids = spotifyUris.joinToString(",") { it.removePrefix("spotify:track:") }
        val request = Request.Builder()
            .url("https://api.spotify.com/v1/me/tracks?ids=$ids")
            .put(EMPTY_REQUEST_BODY)
            .header("Authorization", "Bearer $token")
            .build()
        httpClient.newCall(request).execute().use { response ->
            when (response.code) {
                200 -> Unit
                401 -> { authManager.invalidateWebPlayerToken(); throw SpotifyAuthException() }
                429 -> throw SpotifyRateLimitException(retryAfter = response.header("Retry-After"))
                else -> throw SpotifyApiException(response.code, response.body?.string())
            }
        }
    }
}
```

For v0.9.13, single-track lists. Batch capacity reserved for v0.9.14 backfill feature.

#### 5.2 `YtMusicLibraryApiClient`

```kotlin
@Singleton
class YtMusicLibraryApiClient @Inject constructor(
    private val innerTubeClient: InnerTubeClient,
) {
    /**
     * Sets likeStatus = LIKE on the given videoId.
     * Adds to user's Liked Music + surfaces as recommendation input.
     * Auth via SAPISID-hash (existing OkHttp interceptor).
     */
    suspend fun likeVideo(videoId: String) {
        innerTubeClient.post(
            path = "/like/like",
            body = mapOf("target" to mapOf("videoId" to videoId)),
        )
    }
}
```

#### 5.3 Error handling

| Code | Behaviour |
|---|---|
| 200/204 | Success. Set `*_saved_at` timestamp. |
| 401 (Spotify) | Invalidate cached token; let next call refresh. Skip this destination silently. |
| 429 | Read `Retry-After`; skip; retry on next trigger. No inline retry. |
| 5xx | Same as 429 — skip, log, retry on next trigger. |
| Other 4xx | Log; surface snackbar `"Couldn't save to <Destination>"` for manual heart only; silent for auto-save. |

#### 5.4 Account preconditions

- **Spotify Save:** `tokenManager.spotifyAuthState == Connected` required. Heart-default Spotify checkbox hidden in Settings + ignored at dispatch time if disconnected.
- **YT Music Like:** `tokenManager.youTubeAuthState == Connected` required. Same UI gate.
- **Stash Liked:** always available, no external dependency.

`AutoSaveScrobbler` checks Spotify connection before firing (silent no-op if disconnected — same pattern as `LastFmScrobbler`).

### 6. Settings UI

New Settings section titled **"Library & Likes"** between "Audio Quality" and "Downloads".

#### 6.1 Block 1 — Auto-save

- **Toggle.** Default off. Disabled with tooltip if Spotify not connected.
- **Threshold slider.** Visible only when toggle on. Range 1-10, default 3, integer steps. Live label `"Threshold: N days"`.
- **Diagnostics line.** Reactive count: `"Last 7 days: N tracks auto-saved"` from `tracks WHERE spotify_saved_at > now-7days`.

#### 6.2 Block 2 — Heart-button defaults

Three checkboxes:
- `"Save to Stash Liked Songs"` — default ON, always visible.
- `"Save to Spotify Liked Songs"` — default ON if Spotify connected, hidden otherwise.
- `"Save to YouTube Music Liked Music"` — default OFF, hidden if YT Music not connected.

Footer hint: `"Long-press the heart for per-track choices."`

#### 6.3 `LikePreferences` (DataStore)

```kotlin
@Singleton
class LikePreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val likeDataStore: DataStore<Preferences> by context.preferencesDataStore("like_preferences")

    val autoSaveEnabled: Flow<Boolean>             // default false
    val autoSaveThreshold: Flow<Int>               // default 3, range 1..10
    val heartDefaultStash: Flow<Boolean>           // default true
    val heartDefaultSpotify: Flow<Boolean>         // default true
    val heartDefaultYtMusic: Flow<Boolean>         // default false

    suspend fun autoSaveEnabledNow(): Boolean
    suspend fun autoSaveThresholdNow(): Int
    suspend fun heartDefaultStashNow(): Boolean
    suspend fun heartDefaultSpotifyNow(): Boolean
    suspend fun heartDefaultYtMusicNow(): Boolean

    suspend fun setAutoSaveEnabled(value: Boolean)
    suspend fun setAutoSaveThreshold(value: Int)
    suspend fun setHeartDefaultStash(value: Boolean)
    suspend fun setHeartDefaultSpotify(value: Boolean)
    suspend fun setHeartDefaultYtMusic(value: Boolean)
}
```

DataStore-backed at `"like_preferences"` namespace. Mirrors existing `LosslessSourcePreferences` pattern.

## Risks

| Risk | Mitigation |
|---|---|
| Spotify ToS exposure (the C&D pattern) | Public `PUT /v1/me/tracks` with documented `user-library-modify` scope. Same endpoint Spotify Web's Like button uses. Posture is "user expressing real preference via official API" — distinct from Spotube's reverse-engineered playback emulation. |
| Wrong-track save (matching error from Stash-Discover) | Auto-save only fires when `track.spotifyUri != null`. Stash-Discover and search-tab tracks generally lack a stored URI; they're skipped. Sync-imported tracks have URIs from Spotify directly — accuracy guaranteed. |
| Forward-only state drift | Acknowledged design choice. v0.9.14 may add "Reset auto-saves" Settings action. |
| Spotify rate limit (429) on auto-save bursts | Realistic volume: dozens of saves/month. 429 path: skip, retry on next play. No queue/worker complexity needed. |
| YT Music InnerTube `like/like` endpoint changes | Same maintenance posture as Stash's existing InnerTube usage. Single-point-of-update if breaks. |
| Spotify connection lost mid-flight | Per Section 5: 401 invalidates token, next call refreshes via existing `SpotifyAuthManager`. |
| `STASH_LIKED` seeding race | First heart-tap may produce two seed attempts on cold start. `OnConflictStrategy.IGNORE` on insert dedupes; second writer reads existing row. |
| Distinct-day timezone edge cases | UTC `date(unixepoch)` may put 11:55pm-local on a different day than 12:05am. ±1 day approximation; user-imperceptible. |
| Discover Weekly STILL doesn't refresh after auto-save shipped | Empirical question. Hypothesis: saves are strong algorithm signal per BART research. If real users report no change after 2 weeks, escalate to v0.9.14 with foreground-WebView "boost" experiment behind a debug flag. Document as known follow-up. |

## Testing

No unit tests added (project precedent — UI/scrobbler code untested at unit level). On-device acceptance:

1. **Auto-save toggle off (default).** Heart-tap with Spotify default checked → fires `PUT /v1/me/tracks`. Spotify Web shows track in Liked Songs within seconds. Auto-save toggle stays off; no automatic saves fire.
2. **Auto-save toggle on, threshold = 3.** Play track A on three different days within 30 days. After 3rd play completes (≥50% / ≥30s), logcat: `AutoSaveScrobbler: threshold met for $trackId, firing SPOTIFY save`. Spotify Liked Songs shows the track. `tracks.spotify_saved_at` populated.
3. **Sub-threshold.** Play track B 3 times same day. Distinct-day count = 1. No save fires.
4. **Forward-only.** After auto-save fires, un-like the track on Spotify Web. Replay 3 more days. No re-save. Logcat: `spotify_saved_at != null, skipping`.
5. **Manual heart, single tap.** Settings: Stash + Spotify defaults on, YT off. Tap heart on Now Playing. Stash Liked playlist shows track; Spotify Liked Songs shows track; YT Music Liked Music does NOT. Heart icon turns filled-red.
6. **Manual heart, long-press.** Same defaults. Long-press heart. Bottom sheet shows three checkboxes pre-checked per defaults (YT off, others on). Toggle YT on, Stash off. Tap Save. Track lands in Spotify and YT, Stash unchanged.
7. **Manual heart on track without `spotifyUri`.** Heart with Spotify destination enabled. Snackbar: `"No Spotify match for this track."` Stash Liked still receives it.
8. **Disconnected services.** Disconnect Spotify in Settings. Heart-defaults Spotify checkbox vanishes. Auto-save toggle greys out with tooltip.
9. **Stash Liked playlist visibility.** "Liked Songs" playlist appears in Library tab after first heart-tap. Tracks added in chronological order. Long-press → standard playlist context menu.
10. **Schema migration v17 → v18.** Cold-install over v0.9.12. Logcat shows MIGRATION_17_18 ran. New columns exist as NULL. No track data lost.
11. **Algorithm responsiveness (empirical).** Enable auto-save with threshold = 1 (aggressive). Listen to 10 different new tracks. After 1-3 days, check Spotify Daily Mix and Discover Weekly. Compare to baseline (mixes pre-auto-save). Document findings — feeds v0.9.14 escalation decision.

## Out of scope

- No reverse-engineered Spotify Connect / dealer.spotify.com integration.
- No play-injection (librespot / phantom Connect device).
- No backfill ("save all my Stash listening history at once").
- No "unlike" / "remove from Liked" UI.
- No lock-screen / media notification heart action.
- No per-track UI dedup between `STASH_LIKED` and `LIKED_SONGS`.
- No "Spotify recommendations boost" foreground WebView path.
- No unit tests.

## Ship as

**v0.9.13.** Bumps `versionCode 50 → 51` and `versionName "0.9.12" → "0.9.13"` in `app/build.gradle.kts`. Single coherent release: feed your taste back to Spotify and YouTube — keep the algorithms working for you, on your terms.
