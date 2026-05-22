# Play Artist + Add-to-Queue Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development.

**Goal:** Two Search-tab features (Play Artist on Artist Profile, Add to Queue on Album Discovery) sharing one new batch `addToQueue(List<Track>)` API.

**Architecture:** Hybrid instant-start + background catalog-fill for Play Artist. Single-call batch append for Add to Queue. No new modules.

**Spec:** `docs/superpowers/specs/2026-05-22-play-artist-and-add-to-queue-design.md`

---

## Task 1 — Batch `addToQueue(List<Track>)`

**Files:**
- Modify: `core/media/src/main/kotlin/com/stash/core/media/PlayerRepository.kt` — add interface method
- Modify: `core/media/src/main/kotlin/com/stash/core/media/PlayerRepositoryImpl.kt` — implement
- Update test fakes that implement `PlayerRepository` (grep for `: PlayerRepository {`)

**Implementation:**

```kotlin
// PlayerRepository.kt — append to interface, near existing addToQueue(track: Track)
/**
 * Append [tracks] (in order) to the end of the current queue.
 * Single MediaController round-trip — preferred over looping
 * [addToQueue] for known-size batches (e.g. an album's tracklist).
 * Empty list is a no-op.
 */
suspend fun addToQueue(tracks: List<Track>)
```

```kotlin
// PlayerRepositoryImpl.kt — paste alongside existing single-track addToQueue
override suspend fun addToQueue(tracks: List<Track>) {
    if (tracks.isEmpty()) return
    val controller = ensureController() ?: return
    val mediaItems = tracks.mapNotNull { buildMediaItemForTrack(it) }
    if (mediaItems.isEmpty()) return
    controller.addMediaItems(mediaItems)
}
```

Update every test fake. `ListeningRecorderSkipTest.FakePlayerRepository` and any other `: PlayerRepository {` impl need:
```kotlin
override suspend fun addToQueue(tracks: List<Track>) {}
```

**Verify:**
```
./gradlew :core:media:compileDebugKotlin :core:media:testDebugUnitTest
```

**Commit message:**
```
feat(media): batch addToQueue(List<Track>) API

Sibling to the existing single-track addToQueue. Used by Play Artist
catalog-fill and Add-to-Queue album append. Single MediaController
addMediaItems call instead of N round-trips.
```

---

## Task 2 — Play Artist on ArtistProfileViewModel

**Files:**
- Modify: `feature/search/src/main/kotlin/com/stash/feature/search/ArtistProfileViewModel.kt`
- Modify: `feature/search/src/main/kotlin/com/stash/feature/search/ArtistProfileScreen.kt` — hero button
- Test: `feature/search/src/test/kotlin/com/stash/feature/search/ArtistProfileViewModelTest.kt`

**Constructor dependencies to add:**
- `playerRepository: PlayerRepository` (probably already injected, check first)
- `albumCache: AlbumCache` (for fetching album tracklists; same one `AlbumDiscoveryViewModel` uses)

**Implementation sketch:**

```kotlin
private var fillCatalogJob: Job? = null

private companion object {
    private const val CATALOG_CAP = 100
}

fun playArtist() {
    fillCatalogJob?.cancel()  // race-on-double-tap guard
    fillCatalogJob = viewModelScope.launch {
        val profile = _uiState.value.profile
        if (profile == null) {
            _userMessages.emit("Artist not loaded yet")
            return@launch
        }
        val seen = mutableSetOf<String>()
        val popularTracks = profile.popular
            .filter { seen.add(it.videoId) }
            .map { it.toDomainTrack(albumFallback = profile.name) }

        if (popularTracks.isEmpty() && profile.albums.isEmpty() && profile.singles.isEmpty()) {
            _userMessages.emit("No tracks available for this artist")
            return@launch
        }

        var appended = popularTracks.size
        if (popularTracks.isNotEmpty()) {
            playerRepository.setQueue(popularTracks, startIndex = 0)
        }

        // Background-fill from albums then singles, in given order
        val catalog = profile.albums + profile.singles
        for (album in catalog) {
            if (appended >= CATALOG_CAP) break
            val detail = runCatching { albumCache.get(album.browseId) }.getOrNull() ?: continue
            val albumTracks = detail.tracks
                .filter { seen.add(it.videoId) }
                .map { it.toDomainTrack(albumTitle = album.title, albumArtist = profile.name) }
                .take(CATALOG_CAP - appended)
            if (albumTracks.isEmpty()) continue

            if (appended == 0) {
                // popular was empty — bootstrap the queue from this album
                playerRepository.setQueue(albumTracks, 0)
            } else {
                playerRepository.addToQueue(albumTracks)
            }
            appended += albumTracks.size
        }
    }
}

// Helper extension functions (top of file or companion):
private fun TrackSummary.toDomainTrack(albumFallback: String): Track =
    Track(
        id = videoId.hashCode().toLong(),
        title = title,
        artist = artist.ifBlank { albumFallback },
        album = "",
        durationMs = (durationSeconds * 1000L).toLong(),
        albumArtUrl = thumbnailUrl,
        youtubeId = videoId,
        source = MusicSource.YOUTUBE,
        isStreamable = true,
    )

private fun AlbumTrack.toDomainTrack(albumTitle: String, albumArtist: String): Track =
    // shape from AlbumDiscoveryViewModel.playAlbum
```

Verify the actual field names on `TrackSummary` and the album-track type by reading the existing `AlbumDiscoveryViewModel.playAlbum` synthesis. Adjust mapping.

**Tests:** Add at minimum:
- `playArtist_setsQueueFromPopular_whenProfileLoaded`
- `playArtist_appendsAlbumTracks_inOrder_dedupedByVideoId`
- `playArtist_emitsMessage_whenNoTracksAvailable`
- `playArtist_cancelsPriorFillJob_onSecondTap`

Mock `playerRepository`, `albumCache`. Use `runTest` and `advanceTimeBy`/`runCurrent` to drive coroutines deterministically.

**UI in ArtistProfileScreen:** Add a Play Artist button in the hero area. Match Stash design system (GlassCard + extended-theme colors, not generic Material). Wire `onClick = vm::playArtist`. Use the existing memory note `feedback_stash_design_system` — DO NOT default to a generic FAB.

**Commit message:**
```
feat(search): Play Artist on ArtistProfileScreen

Hybrid start: instant setQueue on 5 cached popular tracks +
background-fill from albums and singles via albumCache. 100-track
soft cap. Dedupe by videoId. Cancels prior fill on double-tap.
```

---

## Task 3 — Add to Queue on AlbumDiscoveryViewModel

**Files:**
- Modify: `feature/search/src/main/kotlin/com/stash/feature/search/AlbumDiscoveryViewModel.kt`
- Modify: `feature/search/src/main/kotlin/com/stash/feature/search/AlbumHero.kt` — secondary icon button
- Test: `feature/search/src/test/kotlin/com/stash/feature/search/AlbumDiscoveryViewModelTest.kt`

**Refactor existing `playAlbum`:**

Extract the `domainTracks` synthesis into a private helper `synthesizeDomainTracks(): List<Track>`. Both `playAlbum` and `addAlbumToQueue` call it. DRY.

**New method:**

```kotlin
fun addAlbumToQueue() {
    viewModelScope.launch {
        val tracks = synthesizeDomainTracks()
        if (tracks.isEmpty()) return@launch
        playerRepository.addToQueue(tracks)
        _userMessages.emit("Added ${tracks.size} tracks to queue")
    }
}
```

**Tests:**
- `addAlbumToQueue_callsBatchAddToQueue_withAllTracks`
- `addAlbumToQueue_emitsSnackbar_withCount`
- `addAlbumToQueue_noOp_whenAlbumNotLoaded`

**UI in AlbumHero:**

Find the existing Play Album button. Add an `IconButton` sibling with `Icons.AutoMirrored.Filled.PlaylistAdd`, `contentDescription = "Add album to queue"`, sized to match the existing button's visual weight. Wire `onClick = vm::addAlbumToQueue`.

Disable the button when `uiState.tracks.isEmpty()` (album not loaded).

**Commit message:**
```
feat(search): Add to Queue button on AlbumDiscoveryScreen

Icon button next to Play Album. Calls new batch addToQueue API,
emits "Added N tracks to queue" Snackbar. Tracks-list synthesis
extracted from playAlbum into shared synthesizeDomainTracks helper.
```

---

## Verification

```
./gradlew :core:media:testDebugUnitTest :feature:search:testDebugUnitTest :app:installDebug
```

On-device smoke:
1. Open Search → tap an artist → Play Artist button → music starts immediately, queue grows visibly over ~5-10s, can hit Next many times without running out
2. Open Search → tap an album → Add to Queue button → Snackbar "Added N tracks" → queue indicator grows by N
3. Repeat Add to Queue on a different album → second snackbar, queue grows again
4. Tap Play Album on a third album → queue REPLACES (existing behavior unchanged)
