# Stash Discover Mix Tuning Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extract maximum value from the existing Last.fm + local listening data to make Stash Discover Mix substantially fresher, more personalized, and more varied — without adding new external services. Ships three orthogonal mixes (Daily Discover, Deep Cuts, First Listen), a richer scoring engine (cosine tag-affinity + exponential decay + completion + skip signals), period-sliced Last.fm personas, tag-graph + track-level discovery, and manual refresh.

**Architecture:** The existing `MixGenerator` pipeline is preserved; the changes are deeper signal use (Last.fm endpoints we don't call yet, fields we don't capture yet, scoring math that uses what we have correctly), plus a multi-recipe layer over the existing `StashMixRecipeEntity` infrastructure. No new external services, no new schema redesign — additive columns + one new entity for skip events. UI changes are minimal: render multiple mix cards, add a manual-refresh action.

**Tech Stack:** Kotlin, Room 2.x, Hilt, kotlinx-coroutines, JUnit4, mockk, WorkManager, Compose (HomeScreen + Settings), DataStore Preferences (persona cache), existing Last.fm OkHttp+JSON client.

---

## Background — current state diagnosis

The current Discover Mix at `c627660c` (master) feels stale because of structural choices in `MixGenerator` and `StashMixRefreshWorker`:

| Mechanism | Today | Why it stays stale |
|---|---|---|
| Affinity window | Flat 180 days, log-normalized | Heavy listening 4 months ago weighs same as last week. No notion of "what you're into right now." |
| Tag scoring | `count(track.tags ∩ user_top_tags)` | Plain count, no normalization, no per-user tag preference modeling. |
| Freshness | Hard pre-filter (drop tracks played in last N days) | No gradient — passes or fails. Tracks at the edge of the window blink in/out. |
| Sort jitter | 0.05 | Same top-N every refresh. |
| Discovery seeds | Top artists' `artist.getSimilar` only | Narrow graph. Same seeds → same neighbors → same recommendations. |
| Discovery dedup | Permanent `(recipe, artist, title)` | Once tried, never re-tried. The candidate pool only narrows. |
| Skip capture | None | Skipping doesn't teach the system anything. Same tracks come back. |
| Recipe count | 1 (Stash Discover) | No variety surface — one card on Home, one schedule. |

The user's diagnosis was variety > accuracy. The plan below targets the variety axis primarily (multi-recipe, period slicing, tag-graph, dedup TTL) while still upgrading accuracy (cosine tag affinity, decay, skip/completion signals).

---

## File Structure

### Created files

| Path | Responsibility |
|---|---|
| `core/data/src/main/kotlin/com/stash/core/data/lastfm/LastFmTrackInfo.kt` | Data class for the rich track info parse (currently `getTrackInfo` returns a single image URL) |
| `core/data/src/main/kotlin/com/stash/core/data/lastfm/LastFmPeriod.kt` | Sealed class enumerating Last.fm `period` values (7day/1month/3month/6month/12month/overall) |
| `core/data/src/main/kotlin/com/stash/core/data/lastfm/LastFmPersonaCache.kt` | DataStore-backed daily cache for period-sliced top tracks/artists |
| `core/data/src/main/kotlin/com/stash/core/data/sync/workers/TrackInfoEnrichmentWorker.kt` | One-shot+periodic enrichment worker, modeled on `TagEnrichmentWorker` |
| `core/data/src/main/kotlin/com/stash/core/data/db/entity/TrackSkipEventEntity.kt` | New entity for skip events (separate table avoids polluting `listening_events`) |
| `core/data/src/main/kotlin/com/stash/core/data/db/dao/TrackSkipEventDao.kt` | DAO for skip-rate computation |
| `core/data/src/main/kotlin/com/stash/core/data/mix/UserTagAffinity.kt` | Module computing per-user TF-IDF tag-affinity vectors |
| `core/data/src/main/kotlin/com/stash/core/data/mix/MixSeedStrategy.kt` | Enum + per-strategy seed generators (artist-similar, tag-graph, track-similar) |
| `core/data/src/main/kotlin/com/stash/core/data/db/MigrationsV21V22.kt` | (or inline in StashDatabase.kt) — schema bumps |
| `core/data/src/test/kotlin/com/stash/core/data/lastfm/LastFmTrackInfoParserTest.kt` | Parser unit tests |
| `core/data/src/test/kotlin/com/stash/core/data/mix/UserTagAffinityTest.kt` | TF-IDF math unit tests |
| `core/data/src/test/kotlin/com/stash/core/data/mix/MixSeedStrategyTest.kt` | Tag-graph / track-similar candidate generation |
| `core/data/src/test/kotlin/com/stash/core/data/sync/workers/TrackInfoEnrichmentWorkerTest.kt` | Worker behavior tests |
| `core/data/src/test/kotlin/com/stash/core/data/db/MigrationV20V21Test.kt` | Migration test (Robolectric) |

### Modified files

| Path | Change |
|---|---|
| `core/data/src/main/kotlin/com/stash/core/data/lastfm/LastFmApiClient.kt` | Replace `getTrackInfo` parse + add `getUserTopTracks(period)`, `getSimilarTracks`, `getTagTopArtists`, `getTagTopTracks` |
| `core/data/src/main/kotlin/com/stash/core/data/db/entity/TrackEntity.kt` | New columns: `mbid`, `lastfm_user_playcount`, `lastfm_listeners`, `lastfm_user_loved` |
| `core/data/src/main/kotlin/com/stash/core/data/db/entity/StashMixRecipeEntity.kt` | New column: `seed_strategy` (TEXT) |
| `core/data/src/main/kotlin/com/stash/core/data/db/StashDatabase.kt` | Bump v20 → v21, register `TrackSkipEventEntity`, add migration |
| `core/data/src/main/kotlin/com/stash/core/data/db/dao/DiscoveryQueueDao.kt` | Modify `existsForRecipe` to honor TTL |
| `core/data/src/main/kotlin/com/stash/core/data/mix/MixGenerator.kt` | Replace tag-count with cosine, exponential decay, completion + skip + lfm-playcount inputs |
| `core/data/src/main/kotlin/com/stash/core/data/mix/StashMixDefaults.kt` | 1 → 3 builtin recipes |
| `core/data/src/main/kotlin/com/stash/core/data/sync/workers/StashMixRefreshWorker.kt` | Per-recipe seed strategy dispatch + period-aware seeding |
| `core/data/src/main/kotlin/com/stash/core/media/listening/ListeningRecorder.kt` | Capture skip events on track-id transition before threshold |
| `core/data/src/main/kotlin/com/stash/core/data/di/DatabaseModule.kt` | Provide `TrackSkipEventDao`, register migration |
| `app/src/main/kotlin/com/stash/app/StashApplication.kt` | Enqueue `TrackInfoEnrichmentWorker`, retune mix defaults gate |
| `feature/home/src/main/kotlin/com/stash/feature/home/HomeScreen.kt` | Render 3 mix cards; long-press menu adds "Refresh this mix" |
| `feature/home/src/main/kotlin/com/stash/feature/home/HomeViewModel.kt` | Inject `WorkManager`, expose `refreshMix(playlistId)` |
| `app/build.gradle.kts` | versionCode 53 → 54, versionName 0.9.15 → 0.9.16 |

---

## Phase 0 — Setup

### Task 0.1: Verify branch + worktree

- [ ] **Step 1: Confirm branch**

```bash
git branch --show-current
```
Expected: `feat/discover-mix-tuning`

- [ ] **Step 2: Confirm clean state**

```bash
git status --short
```
Expected: clean (or only `?? docs/superpowers/plans/...` for the plan doc).

- [ ] **Step 3: Confirm v20 schema is current**

```bash
ls core/data/schemas/com.stash.core.data.db.StashDatabase/ | sort -V | tail -3
```
Expected: `19.json`, `20.json`. Current schema version is 20 from the v0.9.15 blocklist work.

---

## Phase 1 — Last.fm endpoint expansion

The existing `LastFmApiClient` only parses image URLs from `track.getInfo` and never calls `user.getTopTracks`, `track.getSimilar`, or `tag.getTopArtists/Tracks`. Phase 1 adds these endpoints. They have no consumers yet — that comes in Phases 2-4.

### Task 1.1: `LastFmTrackInfo` data class + extended `getTrackInfo` parser

**Files:**
- Create: `core/data/src/main/kotlin/com/stash/core/data/lastfm/LastFmTrackInfo.kt`
- Modify: `core/data/src/main/kotlin/com/stash/core/data/lastfm/LastFmApiClient.kt:205` (`getTrackInfo`) and `:374` (`parseTrackImageUrl`)
- Test: `core/data/src/test/kotlin/com/stash/core/data/lastfm/LastFmTrackInfoParserTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
// core/data/src/test/kotlin/com/stash/core/data/lastfm/LastFmTrackInfoParserTest.kt
package com.stash.core.data.lastfm

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LastFmTrackInfoParserTest {

    private val sample = """
        {"track":{
            "name":"505",
            "mbid":"abc-mbid-505",
            "duration":"253000",
            "listeners":"1234567",
            "playcount":"9876543",
            "userplaycount":"42",
            "userloved":"1",
            "artist":{"name":"Arctic Monkeys","mbid":"art-mbid"},
            "album":{"image":[
                {"size":"small","#text":"http://x/s.jpg"},
                {"size":"extralarge","#text":"http://x/xl.jpg"}
            ]},
            "toptags":{"tag":[
                {"name":"indie rock","count":"100"},
                {"name":"alternative","count":"60"}
            ]}
        }}
    """.trimIndent()

    @Test
    fun `parses full track info including userplaycount and userloved`() {
        val info = LastFmTrackInfo.parse(Json.parseToJsonElement(sample))

        assertEquals("abc-mbid-505", info.mbid)
        assertEquals(253000L, info.durationMs)
        assertEquals(1_234_567L, info.listeners)
        assertEquals(42, info.userPlaycount)
        assertEquals(true, info.userLoved)
        assertEquals("http://x/xl.jpg", info.bestImageUrl)
        assertEquals(listOf("indie rock", "alternative"), info.tags.map { it.name })
        assertEquals(listOf(100, 60), info.tags.map { it.count })
    }

    @Test
    fun `parse handles missing user fields when no username supplied`() {
        val withoutUser = """
            {"track":{"name":"x","artist":{"name":"y"},
                "listeners":"100","playcount":"500",
                "album":{"image":[]},"toptags":{"tag":[]}}}
        """
        val info = LastFmTrackInfo.parse(Json.parseToJsonElement(withoutUser))
        assertNull(info.userPlaycount)
        assertNull(info.userLoved)
        assertNull(info.mbid)
        assertEquals(100L, info.listeners)
    }

    @Test
    fun `parse handles toptags returned as single-tag object instead of array`() {
        // Last.fm wraps single-element arrays in a non-array JSON object.
        // The asArrayOrSingletonArray helper at LastFmApiClient.kt:408 handles this;
        // verify the parser composes correctly.
        val singleTag = """
            {"track":{"name":"x","artist":{"name":"y"},
                "listeners":"0","playcount":"0",
                "album":{"image":[]},
                "toptags":{"tag":{"name":"only","count":"5"}}}}
        """
        val info = LastFmTrackInfo.parse(Json.parseToJsonElement(singleTag))
        assertEquals(listOf("only"), info.tags.map { it.name })
    }
}
```

- [ ] **Step 2: Run test, expect FAIL**

```bash
./gradlew :core:data:testDebugUnitTest --tests "com.stash.core.data.lastfm.LastFmTrackInfoParserTest"
```
Expected: FAIL — `LastFmTrackInfo` does not exist.

- [ ] **Step 3: Create `LastFmTrackInfo`**

```kotlin
// core/data/src/main/kotlin/com/stash/core/data/lastfm/LastFmTrackInfo.kt
package com.stash.core.data.lastfm

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * v0.9.16: Rich `track.getInfo` projection. The original
 * [LastFmApiClient.getTrackInfo] returned only a `String?` image URL;
 * this expanded shape surfaces every field the recommender benefits
 * from: MBID (join key for any future MetaBrainz-stack work), per-user
 * playcount + loved (richer affinity than in-app plays for users with
 * pre-Stash Last.fm history), public listeners (popularity bucket for
 * novelty calibration), and the per-track tag set (replaces the
 * separate artist-tag fetch when both are available).
 */
data class LastFmTrackInfo(
    val mbid: String?,
    val durationMs: Long?,
    val listeners: Long,
    val playcount: Long,
    val userPlaycount: Int?,
    val userLoved: Boolean?,
    val bestImageUrl: String?,
    val tags: List<TagCount>,
) {
    data class TagCount(val name: String, val count: Int)

    companion object {
        fun parse(root: JsonElement): LastFmTrackInfo {
            val track = root.jsonObject["track"]?.jsonObject
                ?: return empty()

            val mbid = track["mbid"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            val duration = track["duration"]?.jsonPrimitive?.longOrNull?.takeIf { it > 0 }
            val listeners = track["listeners"]?.jsonPrimitive?.longOrNull ?: 0L
            val playcount = track["playcount"]?.jsonPrimitive?.longOrNull ?: 0L
            val userPlaycount = track["userplaycount"]?.jsonPrimitive?.intOrNull
            val userLovedRaw = track["userloved"]?.jsonPrimitive?.contentOrNull
            val userLoved = userLovedRaw?.let { it == "1" }
            val image = parseBestImage(track["album"]?.jsonObject)
            val tags = parseTopTags(track["toptags"]?.jsonObject)

            return LastFmTrackInfo(
                mbid = mbid,
                durationMs = duration,
                listeners = listeners,
                playcount = playcount,
                userPlaycount = userPlaycount,
                userLoved = userLoved,
                bestImageUrl = image,
                tags = tags,
            )
        }

        private fun empty() = LastFmTrackInfo(
            mbid = null, durationMs = null, listeners = 0, playcount = 0,
            userPlaycount = null, userLoved = null, bestImageUrl = null, tags = emptyList(),
        )

        private fun parseBestImage(album: JsonObject?): String? {
            val images = album?.get("image") ?: return null
            // Reuse the existing helper from LastFmApiClient for array-or-singleton tolerance.
            // For the parser unit-tested here, inline a minimal version:
            val arr = if (images is kotlinx.serialization.json.JsonArray) images
                else kotlinx.serialization.json.JsonArray(listOf(images))
            // Pick the largest non-empty URL by canonical order.
            val sizes = listOf("mega", "extralarge", "large", "medium", "small")
            for (size in sizes) {
                val match = arr.firstOrNull { el ->
                    val obj = el.jsonObject
                    obj["size"]?.jsonPrimitive?.contentOrNull == size &&
                        obj["#text"]?.jsonPrimitive?.contentOrNull?.isNotBlank() == true
                } ?: continue
                return match.jsonObject["#text"]?.jsonPrimitive?.content
            }
            return null
        }

        private fun parseTopTags(toptags: JsonObject?): List<TagCount> {
            val tagEl = toptags?.get("tag") ?: return emptyList()
            val arr = if (tagEl is kotlinx.serialization.json.JsonArray) tagEl
                else kotlinx.serialization.json.JsonArray(listOf(tagEl))
            return arr.mapNotNull { el ->
                val obj = el.jsonObject
                val name = obj["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val count = obj["count"]?.jsonPrimitive?.intOrNull ?: 0
                TagCount(name, count)
            }
        }
    }
}
```

- [ ] **Step 4: Run test, expect PASS**

```bash
./gradlew :core:data:testDebugUnitTest --tests "com.stash.core.data.lastfm.LastFmTrackInfoParserTest"
```
Expected: PASS, 3/3.

- [ ] **Step 5: Modify `LastFmApiClient.getTrackInfo` to return rich type**

In `core/data/src/main/kotlin/com/stash/core/data/lastfm/LastFmApiClient.kt:205-220` change the function signature from `suspend fun getTrackInfo(artist, title): Result<String?>` to `suspend fun getTrackInfo(artist: String, title: String, username: String? = null): Result<LastFmTrackInfo>`. Build the params with optional `username` (only if non-null adds `username=` query param), call `unsignedGet`, return `LastFmTrackInfo.parse(response)`. Delete `parseTrackImageUrl` (replaced by `LastFmTrackInfo.parse`).

Find and update the existing caller (`grep -rn "getTrackInfo" feature/ core/`) — should be ~1-2 sites that just pulled the image URL. Update them to use `info.bestImageUrl`.

- [ ] **Step 6: Run full test suite, expect PASS**

```bash
./gradlew :core:data:testDebugUnitTest
```

- [ ] **Step 7: Commit**

```bash
git add core/data/src/main/kotlin/com/stash/core/data/lastfm/LastFmTrackInfo.kt \
        core/data/src/main/kotlin/com/stash/core/data/lastfm/LastFmApiClient.kt \
        core/data/src/test/kotlin/com/stash/core/data/lastfm/LastFmTrackInfoParserTest.kt
git commit -m "feat(lastfm): rich track.getInfo parsing — mbid, userplaycount, tags"
```

### Task 1.2: `getUserTopTracks` with period

**Files:**
- Modify: `core/data/src/main/kotlin/com/stash/core/data/lastfm/LastFmApiClient.kt` (add new function + period enum reference)
- Create: `core/data/src/main/kotlin/com/stash/core/data/lastfm/LastFmPeriod.kt`

- [ ] **Step 1: Create `LastFmPeriod` enum**

```kotlin
// core/data/src/main/kotlin/com/stash/core/data/lastfm/LastFmPeriod.kt
package com.stash.core.data.lastfm

/**
 * v0.9.16: Last.fm `period` parameter for user.getTopTracks /
 * user.getTopArtists. Each value maps to a different temporal window
 * of the user's listening history, pre-computed by Last.fm — free
 * temporal slicing for the recommender.
 */
enum class LastFmPeriod(val apiValue: String) {
    SEVEN_DAY("7day"),
    ONE_MONTH("1month"),
    THREE_MONTH("3month"),
    SIX_MONTH("6month"),
    TWELVE_MONTH("12month"),
    OVERALL("overall"),
}
```

- [ ] **Step 2: Add `getUserTopTracks` to `LastFmApiClient`**

After the existing `getUserTopArtists` function (around line 178-200 of `LastFmApiClient.kt`):

```kotlin
/**
 * v0.9.16: User's top tracks for the given [period]. Last.fm
 * pre-computes these — calling each period (7day/1month/3month/etc.)
 * is the cheapest way to get a temporal slice of the user's taste
 * without computing it ourselves.
 *
 * Returns up to [limit] tracks ordered by play count within the period.
 * Empty list if Last.fm has no listening data for the user.
 */
suspend fun getUserTopTracks(
    username: String,
    period: LastFmPeriod = LastFmPeriod.ONE_MONTH,
    limit: Int = 100,
): Result<List<LastFmTopTrack>> = runCatching {
    val params = mapOf(
        "method" to "user.gettoptracks",
        "user" to username,
        "period" to period.apiValue,
        "limit" to limit.toString(),
    )
    val response = unsignedGet(params)
    parseTopTracks(response["toptracks"]?.jsonObject?.get("track"))
}
```

Also add a `period` parameter to the existing `getUserTopArtists` (line 178), defaulting to `LastFmPeriod.OVERALL` to keep backward compatibility:

```kotlin
suspend fun getUserTopArtists(
    username: String,
    period: LastFmPeriod = LastFmPeriod.OVERALL,
    limit: Int = 100,
): Result<List<LastFmTopArtist>> = runCatching {
    // ... existing body but with "period" param added to the map
}
```

- [ ] **Step 3: Build to verify compile**

```bash
./gradlew :core:data:compileDebugKotlin
```

- [ ] **Step 4: Commit**

```bash
git add core/data/src/main/kotlin/com/stash/core/data/lastfm/LastFmPeriod.kt \
        core/data/src/main/kotlin/com/stash/core/data/lastfm/LastFmApiClient.kt
git commit -m "feat(lastfm): user.getTopTracks + period param on top-artists"
```

### Task 1.3: `getSimilarTracks` (track-level similar)

**Files:**
- Modify: `core/data/src/main/kotlin/com/stash/core/data/lastfm/LastFmApiClient.kt`

- [ ] **Step 1: Add the function**

After `getSimilarArtists` (around line 140-155 of `LastFmApiClient.kt`):

```kotlin
/**
 * v0.9.16: Track-level similar tracks (track.getSimilar). Distinct
 * from [getSimilarArtists] which returns artists; this returns track
 * candidates with `(artist, title, match_score)`. Higher precision
 * than artist-similar for vibe-matching.
 */
suspend fun getSimilarTracks(
    artist: String,
    title: String,
    limit: Int = 30,
): Result<List<LastFmSimilarTrack>> = runCatching {
    val params = mapOf(
        "method" to "track.getsimilar",
        "artist" to artist,
        "track" to title,
        "limit" to limit.toString(),
        "autocorrect" to "1",
    )
    val response = unsignedGet(params)
    parseSimilarTracks(response["similartracks"]?.jsonObject?.get("track"))
}
```

Add the data class near the bottom of the file:

```kotlin
data class LastFmSimilarTrack(
    val artist: String,
    val title: String,
    val match: Float,
)
```

Add the parser near `parseTopTracks`:

```kotlin
private fun parseSimilarTracks(arr: JsonElement?): List<LastFmSimilarTrack> {
    if (arr == null) return emptyList()
    val list = arr.asArrayOrSingletonArray()
    return list.mapNotNull { el ->
        val obj = el.jsonObject
        val title = obj["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: return@mapNotNull null
        val artist = obj["artist"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val match = obj["match"]?.jsonPrimitive?.contentOrNull?.toFloatOrNull() ?: 0f
        LastFmSimilarTrack(artist = artist, title = title, match = match)
    }
}
```

- [ ] **Step 2: Compile + commit**

```bash
./gradlew :core:data:compileDebugKotlin
git add core/data/src/main/kotlin/com/stash/core/data/lastfm/LastFmApiClient.kt
git commit -m "feat(lastfm): track.getSimilar"
```

### Task 1.4: `getTagTopArtists` + `getTagTopTracks`

**Files:**
- Modify: `core/data/src/main/kotlin/com/stash/core/data/lastfm/LastFmApiClient.kt`

- [ ] **Step 1: Add the two functions**

```kotlin
/**
 * v0.9.16: Tag-driven candidate generation. The Last.fm tag→artist
 * graph is the largest in the API; this is the primary source for
 * the "First Listen" mix and for tag-graph discovery beyond the
 * narrow artist-similar neighborhood.
 */
suspend fun getTagTopArtists(tag: String, limit: Int = 50): Result<List<LastFmTopArtist>> = runCatching {
    val params = mapOf(
        "method" to "tag.gettopartists",
        "tag" to tag,
        "limit" to limit.toString(),
    )
    val response = unsignedGet(params)
    parseTopArtists(response["topartists"]?.jsonObject?.get("artist"))
}

suspend fun getTagTopTracks(tag: String, limit: Int = 50): Result<List<LastFmTopTrack>> = runCatching {
    val params = mapOf(
        "method" to "tag.gettoptracks",
        "tag" to tag,
        "limit" to limit.toString(),
    )
    val response = unsignedGet(params)
    parseTopTracks(response["tracks"]?.jsonObject?.get("track"))
}
```

- [ ] **Step 2: Compile + commit**

```bash
./gradlew :core:data:compileDebugKotlin
git add core/data/src/main/kotlin/com/stash/core/data/lastfm/LastFmApiClient.kt
git commit -m "feat(lastfm): tag.getTopArtists + tag.getTopTracks"
```

---

## Phase 2 — Schema + enrichment

### Task 2.1: Schema v20→v21 with new track columns + skip events table + recipe seed_strategy

**Files:**
- Modify: `core/data/src/main/kotlin/com/stash/core/data/db/entity/TrackEntity.kt:32-186` (add columns)
- Modify: `core/data/src/main/kotlin/com/stash/core/data/db/entity/StashMixRecipeEntity.kt:18-96` (add `seed_strategy`)
- Create: `core/data/src/main/kotlin/com/stash/core/data/db/entity/TrackSkipEventEntity.kt`
- Modify: `core/data/src/main/kotlin/com/stash/core/data/db/StashDatabase.kt` (bump version, register entity, add migration)
- Modify: `core/data/src/main/kotlin/com/stash/core/data/di/DatabaseModule.kt` (add MIGRATION_20_21, provide DAO)

- [ ] **Step 1: Add columns to `TrackEntity`**

After line 186 (last existing column `stashLikedAt`):

```kotlin
    /**
     * v0.9.16: Last.fm canonical recording MBID. Captured by
     * TrackInfoEnrichmentWorker. Join key for any future MetaBrainz-
     * stack work; null while the worker hasn't yet enriched this row.
     */
    @ColumnInfo(name = "mbid")
    val mbid: String? = null,

    /**
     * v0.9.16: User's lifetime Last.fm playcount for this track.
     * Richer than [playCount] (which only counts in-Stash plays) for
     * users with pre-Stash listening history. Null = not yet enriched.
     */
    @ColumnInfo(name = "lastfm_user_playcount")
    val lastfmUserPlaycount: Int? = null,

    /**
     * v0.9.16: Public Last.fm listener count. Used as a popularity
     * bucket for novelty calibration in the recommender.
     */
    @ColumnInfo(name = "lastfm_listeners")
    val lastfmListeners: Long? = null,

    /**
     * v0.9.16: Did the user love this track on Last.fm? Explicit
     * positive label, weighted heavier than scrobble counts.
     */
    @ColumnInfo(name = "lastfm_user_loved", defaultValue = "0")
    val lastfmUserLoved: Boolean = false,
```

- [ ] **Step 2: Add `seed_strategy` to `StashMixRecipeEntity`**

After the `lastRefreshedAt` column (line 95):

```kotlin
    /**
     * v0.9.16: Discovery seed strategy. ARTIST_SIMILAR (default,
     * pre-v0.9.16 behavior), TAG_GRAPH (use the user's top tags to
     * pull from `tag.getTopTracks`), TRACK_SIMILAR (seed from top
     * tracks via `track.getSimilar`), or NONE (no discovery, library
     * only). See [com.stash.core.data.mix.MixSeedStrategy].
     */
    @ColumnInfo(name = "seed_strategy", defaultValue = "ARTIST_SIMILAR")
    val seedStrategy: String = "ARTIST_SIMILAR",
```

- [ ] **Step 3: Create `TrackSkipEventEntity`**

```kotlin
// core/data/src/main/kotlin/com/stash/core/data/db/entity/TrackSkipEventEntity.kt
package com.stash.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * v0.9.16: A skip event. Captured by [com.stash.core.media.listening.ListeningRecorder]
 * when the player transitions to a new track BEFORE the listen-threshold
 * fires (i.e. the user skipped before the play would have been counted).
 *
 * Stored in a separate table from `listening_events` so the implicit
 * "every listening_event row is a real listen" invariant is preserved
 * — touching that contract risks breaking the auto-save scrobbler and
 * the synthetic-backfill path.
 *
 * Skip-rate per track is computed as
 *   skip_rate = count(skips) / (count(skips) + count(listening_events))
 * over a rolling window (typically 14 days for the recipe-shadow-block
 * threshold).
 */
@Entity(
    tableName = "track_skip_events",
    foreignKeys = [
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["id"],
            childColumns = ["track_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["track_id"]),
        Index(value = ["skipped_at"]),
    ],
)
data class TrackSkipEventEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "track_id")
    val trackId: Long,

    /** Epoch millis at the moment the skip happened. */
    @ColumnInfo(name = "skipped_at")
    val skippedAt: Long,

    /**
     * Player position (millis) when the skip happened. 0 if the user
     * hit Next before the player even started progressing — most
     * aggressive form of skip; weighted heaviest.
     */
    @ColumnInfo(name = "position_ms")
    val positionMs: Long,
)
```

- [ ] **Step 4: Bump schema version + register entity**

In `core/data/src/main/kotlin/com/stash/core/data/db/StashDatabase.kt`:
- Add `TrackSkipEventEntity::class` to the `entities` list in `@Database(...)`.
- Bump `version = 20` → `version = 21`.
- Add abstract function: `abstract fun trackSkipEventDao(): TrackSkipEventDao` (DAO created in next task).

- [ ] **Step 5: Write migration v20→v21**

Append to `StashDatabase.kt` companion object after `MIGRATION_19_20`:

```kotlin
val MIGRATION_20_21 = object : Migration(20, 21) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 1. Track-level columns for Last.fm enrichment.
        db.execSQL("ALTER TABLE tracks ADD COLUMN mbid TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE tracks ADD COLUMN lastfm_user_playcount INTEGER DEFAULT NULL")
        db.execSQL("ALTER TABLE tracks ADD COLUMN lastfm_listeners INTEGER DEFAULT NULL")
        db.execSQL("ALTER TABLE tracks ADD COLUMN lastfm_user_loved INTEGER NOT NULL DEFAULT 0")

        // 2. seed_strategy on stash_mix_recipes.
        db.execSQL(
            "ALTER TABLE stash_mix_recipes ADD COLUMN seed_strategy TEXT NOT NULL DEFAULT 'ARTIST_SIMILAR'"
        )

        // 3. New track_skip_events table.
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS track_skip_events (
                id          INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                track_id    INTEGER NOT NULL,
                skipped_at  INTEGER NOT NULL,
                position_ms INTEGER NOT NULL,
                FOREIGN KEY(track_id) REFERENCES tracks(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_track_skip_events_track_id ON track_skip_events(track_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_track_skip_events_skipped_at ON track_skip_events(skipped_at)")
    }
}
```

Wire into `DatabaseModule.kt`'s `addMigrations(...)` chain.

- [ ] **Step 6: Generate v21 schema**

```bash
./gradlew :core:data:assembleDebug
ls core/data/schemas/com.stash.core.data.db.StashDatabase/ | grep 21
```
Expected: `21.json` exists.

- [ ] **Step 7: Write migration test**

```kotlin
// core/data/src/test/kotlin/com/stash/core/data/db/MigrationV20V21Test.kt
package com.stash.core.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class MigrationV20V21Test {
    private val DB_NAME = "migration-v20v21-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        StashDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun `migration v20 to v21 adds new columns and creates skip events table`() {
        helper.createDatabase(DB_NAME, 20).close()

        val db = helper.runMigrationsAndValidate(DB_NAME, 21, true, StashDatabase.MIGRATION_20_21)

        // tracks columns
        db.query("PRAGMA table_info(tracks)").use { c ->
            val names = mutableListOf<String>()
            while (c.moveToNext()) names += c.getString(1)
            assertTrue("mbid present", names.contains("mbid"))
            assertTrue("lastfm_user_playcount present", names.contains("lastfm_user_playcount"))
            assertTrue("lastfm_listeners present", names.contains("lastfm_listeners"))
            assertTrue("lastfm_user_loved present", names.contains("lastfm_user_loved"))
        }
        // stash_mix_recipes has seed_strategy
        db.query("PRAGMA table_info(stash_mix_recipes)").use { c ->
            val names = mutableListOf<String>()
            while (c.moveToNext()) names += c.getString(1)
            assertTrue("seed_strategy present", names.contains("seed_strategy"))
        }
        // track_skip_events table exists
        db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='track_skip_events'").use { c ->
            assertEquals(1, c.count)
        }
    }
}
```

- [ ] **Step 8: Run migration test, expect PASS**

```bash
./gradlew :core:data:testDebugUnitTest --tests "com.stash.core.data.db.MigrationV20V21Test"
```

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "feat(schema): v21 — Last.fm enrichment columns + skip events table"
```

### Task 2.2: `TrackSkipEventDao` with skip-rate query

**Files:**
- Create: `core/data/src/main/kotlin/com/stash/core/data/db/dao/TrackSkipEventDao.kt`
- Modify: `core/data/src/main/kotlin/com/stash/core/data/di/DatabaseModule.kt` (provide DAO)

- [ ] **Step 1: Create the DAO**

```kotlin
// core/data/src/main/kotlin/com/stash/core/data/db/dao/TrackSkipEventDao.kt
package com.stash.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.stash.core.data.db.entity.TrackSkipEventEntity

/** Per-track skip-rate projection for the recommender's negative weighting. */
data class TrackSkipStats(
    val trackId: Long,
    val skips: Int,
    val plays: Int,
)

@Dao
interface TrackSkipEventDao {

    @Insert
    suspend fun insert(event: TrackSkipEventEntity): Long

    @Query("SELECT COUNT(*) FROM track_skip_events WHERE track_id = :trackId AND skipped_at >= :sinceMs")
    suspend fun countSkipsSince(trackId: Long, sinceMs: Long): Int

    /**
     * Returns skip + play counts per track for the rolling window.
     * Used by [com.stash.core.data.mix.MixGenerator] to compute a
     * skip-rate penalty: tracks with high skip ratio over recent
     * encounters get demoted in scoring.
     */
    @Query(
        """
        SELECT t.id AS trackId,
               (SELECT COUNT(*) FROM track_skip_events s
                  WHERE s.track_id = t.id AND s.skipped_at >= :sinceMs) AS skips,
               (SELECT COUNT(*) FROM listening_events le
                  WHERE le.track_id = t.id AND le.started_at >= :sinceMs) AS plays
        FROM tracks t
        WHERE t.id IN (:trackIds)
        """
    )
    suspend fun getSkipStatsSince(trackIds: List<Long>, sinceMs: Long): List<TrackSkipStats>
}
```

- [ ] **Step 2: Provide via Hilt module**

In `core/data/src/main/kotlin/com/stash/core/data/di/DatabaseModule.kt`, append:

```kotlin
@Provides
fun provideTrackSkipEventDao(db: StashDatabase): com.stash.core.data.db.dao.TrackSkipEventDao =
    db.trackSkipEventDao()
```

- [ ] **Step 3: Compile + commit**

```bash
./gradlew :core:data:compileDebugKotlin
git add core/data/src/main/kotlin/com/stash/core/data/db/dao/TrackSkipEventDao.kt \
        core/data/src/main/kotlin/com/stash/core/data/di/DatabaseModule.kt
git commit -m "feat(db): TrackSkipEventDao + provide"
```

### Task 2.3: `TrackInfoEnrichmentWorker`

**Files:**
- Create: `core/data/src/main/kotlin/com/stash/core/data/sync/workers/TrackInfoEnrichmentWorker.kt`
- Modify: `app/src/main/kotlin/com/stash/app/StashApplication.kt:170-175` (enqueue alongside TagEnrichmentWorker)
- Test: `core/data/src/test/kotlin/com/stash/core/data/sync/workers/TrackInfoEnrichmentWorkerTest.kt`

- [ ] **Step 1: Add `findTracksNeedingLastfmEnrichment` to `TrackDao`**

In `core/data/src/main/kotlin/com/stash/core/data/db/dao/TrackDao.kt` (append, near other `findUntagged`-style queries):

```kotlin
/**
 * v0.9.16: Track ids whose Last.fm enrichment hasn't run yet
 * (mbid + lastfm_user_playcount columns are both NULL). Used by
 * [com.stash.core.data.sync.workers.TrackInfoEnrichmentWorker]
 * to drive its batched per-day enrichment loop.
 */
@Query(
    """
    SELECT id FROM tracks
    WHERE is_downloaded = 1
      AND mbid IS NULL
      AND lastfm_user_playcount IS NULL
    ORDER BY date_added DESC
    LIMIT :limit
    """
)
suspend fun findTracksNeedingLastfmEnrichment(limit: Int): List<Long>
```

Also add an updater:

```kotlin
@Query(
    """
    UPDATE tracks SET
        mbid = :mbid,
        lastfm_user_playcount = :userPlaycount,
        lastfm_listeners = :listeners,
        lastfm_user_loved = :userLoved
    WHERE id = :trackId
    """
)
suspend fun setLastfmEnrichment(
    trackId: Long,
    mbid: String?,
    userPlaycount: Int?,
    listeners: Long?,
    userLoved: Boolean,
)
```

- [ ] **Step 2: Create the worker**

```kotlin
// core/data/src/main/kotlin/com/stash/core/data/sync/workers/TrackInfoEnrichmentWorker.kt
package com.stash.core.data.sync.workers

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.stash.core.data.auth.lastfm.LastFmSessionPreference
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.lastfm.LastFmApiClient
import com.stash.core.data.lastfm.LastFmCredentials
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * v0.9.16: Per-track Last.fm enrichment worker. For each downloaded
 * track that hasn't been enriched yet, calls
 * [LastFmApiClient.getTrackInfo] with the user's username and
 * persists mbid, listeners, userPlaycount, userLoved into the
 * `tracks` table. Runs in batches with a polite ~4 req/sec cap to
 * stay under Last.fm's 5 req/sec limit.
 *
 * Modeled on [TagEnrichmentWorker]: same daily periodic + one-shot
 * pattern, same batched loop, same "process this batch then wait
 * for the next periodic fire" behavior. The DAO predicate
 * inherently filters already-processed rows, so resuming after
 * interruption is trivial.
 */
@HiltWorker
class TrackInfoEnrichmentWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val trackDao: TrackDao,
    private val apiClient: LastFmApiClient,
    private val credentials: LastFmCredentials,
    private val sessionPreference: LastFmSessionPreference,
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "TrackInfoEnrich"
        private const val WORK_NAME = "stash_track_info_enrichment"
        private const val BATCH_SIZE = 200
        private const val REQUEST_INTERVAL_MS = 250L // 4 req/sec — under LFM 5/sec limit

        fun schedulePeriodic(context: Context) {
            val work = PeriodicWorkRequestBuilder<TrackInfoEnrichmentWorker>(
                repeatInterval = 1,
                repeatIntervalTimeUnit = TimeUnit.DAYS,
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(true)
                        .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                work,
            )
        }
    }

    override suspend fun doWork(): Result {
        if (!credentials.isConfigured) {
            Log.d(TAG, "Last.fm creds not configured — skipping")
            return Result.success()
        }
        val session = sessionPreference.session.first()
        val username = session?.username
        if (username.isNullOrBlank()) {
            Log.d(TAG, "Last.fm not connected — skipping")
            return Result.success()
        }

        val candidates = trackDao.findTracksNeedingLastfmEnrichment(BATCH_SIZE)
        if (candidates.isEmpty()) {
            Log.d(TAG, "no tracks need enrichment — done")
            return Result.success()
        }
        Log.i(TAG, "enriching ${candidates.size} tracks")

        var processed = 0
        for (trackId in candidates) {
            val track = trackDao.getById(trackId) ?: continue
            if (track.artist.isBlank() || track.title.isBlank()) continue

            val info = apiClient
                .getTrackInfo(track.artist, track.title, username = username)
                .getOrNull()
            if (info != null) {
                trackDao.setLastfmEnrichment(
                    trackId = trackId,
                    mbid = info.mbid,
                    userPlaycount = info.userPlaycount,
                    listeners = info.listeners.takeIf { it > 0 },
                    userLoved = info.userLoved == true,
                )
                processed++
            } else {
                // Mark as processed-with-no-data so we don't retry every
                // run. Use sentinel: userPlaycount = 0, mbid = empty
                // string. (Empty != null; the DAO predicate checks NULL.)
                trackDao.setLastfmEnrichment(
                    trackId = trackId,
                    mbid = "",
                    userPlaycount = 0,
                    listeners = null,
                    userLoved = false,
                )
            }
            delay(REQUEST_INTERVAL_MS)
        }
        Log.i(TAG, "done: processed=$processed")
        return Result.success()
    }
}
```

- [ ] **Step 3: Wire into StashApplication**

In `app/src/main/kotlin/com/stash/app/StashApplication.kt`, alongside `TagEnrichmentWorker.schedulePeriodic`:

```kotlin
TrackInfoEnrichmentWorker.schedulePeriodic(this@StashApplication)
```

Also enqueue a one-shot from the same coroutine block so first-launch users don't wait 24h:

```kotlin
WorkManager.getInstance(applicationContext).enqueueUniqueWork(
    "stash_track_info_enrichment_oneshot",
    androidx.work.ExistingWorkPolicy.KEEP,
    androidx.work.OneTimeWorkRequestBuilder<TrackInfoEnrichmentWorker>().build(),
)
```

- [ ] **Step 4: Write a worker test**

```kotlin
// core/data/src/test/kotlin/com/stash/core/data/sync/workers/TrackInfoEnrichmentWorkerTest.kt
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class TrackInfoEnrichmentWorkerTest {

    @Test
    fun `worker no-ops when Last_fm session is missing`() = runTest {
        val sessionPreference = mockk<LastFmSessionPreference>()
        coEvery { sessionPreference.session } returns flowOf(null)
        // ... build worker via WorkManagerTestInitHelper, run, assert success without enrichment calls.
    }

    @Test
    fun `worker enriches batch + persists fields including mbid sentinel for no-data hits`() = runTest {
        // Mock apiClient: first track returns rich info, second returns null Result.
        // Verify trackDao.setLastfmEnrichment called twice with correct args.
    }
}
```

- [ ] **Step 5: Run tests**

```bash
./gradlew :core:data:testDebugUnitTest --tests "com.stash.core.data.sync.workers.TrackInfoEnrichmentWorkerTest"
```

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat(enrichment): TrackInfoEnrichmentWorker — Last.fm per-track fields"
```

---

## Phase 3 — Period-sliced persona fetch (in-worker, no separate cache)

**Decision:** the period-sliced data only needs to be fresh once per day. Rather than build a separate worker + DataStore cache, fetch it inline at the top of `StashMixRefreshWorker.doWork()` once per day — five HTTP calls is well within the ~30-second worker budget. Cache in-memory for the duration of the worker run; the next periodic refresh fetches again.

If we ever need cross-worker reuse, extract to a real cache. YAGNI for v1.

### Task 3.1: Inline persona fetch at refresh-worker top

**Files:**
- Modify: `core/data/src/main/kotlin/com/stash/core/data/sync/workers/StashMixRefreshWorker.kt`
- Create: `core/data/src/main/kotlin/com/stash/core/data/lastfm/LastFmPersonas.kt` (a tiny container struct used per-run)

- [ ] **Step 1: Create persona container**

```kotlin
// core/data/src/main/kotlin/com/stash/core/data/lastfm/LastFmPersonas.kt
package com.stash.core.data.lastfm

/**
 * v0.9.16: Snapshot of period-sliced top tracks/artists fetched
 * once per refresh-worker run. Different mix recipes consume
 * different periods (Daily Discover → 1month, Heavy Rotation →
 * 7day, Throwback diff = overall - 3month, etc.) — all five fit
 * in a single struct passed down the refresh pipeline.
 *
 * Empty if the user is not Last.fm-connected; recipes that depend
 * on a non-empty persona fall back to listening_events local data.
 */
data class LastFmPersonas(
    val topTracksByPeriod: Map<LastFmPeriod, List<LastFmTopTrack>>,
    val topArtistsByPeriod: Map<LastFmPeriod, List<LastFmTopArtist>>,
) {
    companion object {
        val EMPTY = LastFmPersonas(emptyMap(), emptyMap())
    }
}
```

- [ ] **Step 2: Add a fetch helper to the refresh worker**

In `StashMixRefreshWorker.kt`:

```kotlin
private suspend fun fetchPersonas(username: String): LastFmPersonas {
    val periods = listOf(
        LastFmPeriod.SEVEN_DAY,
        LastFmPeriod.ONE_MONTH,
        LastFmPeriod.THREE_MONTH,
        LastFmPeriod.SIX_MONTH,
        LastFmPeriod.OVERALL,
    )
    val tracks = mutableMapOf<LastFmPeriod, List<LastFmTopTrack>>()
    val artists = mutableMapOf<LastFmPeriod, List<LastFmTopArtist>>()
    for (period in periods) {
        tracks[period] = lastFmApiClient.getUserTopTracks(username, period, limit = 100)
            .getOrNull().orEmpty()
        artists[period] = lastFmApiClient.getUserTopArtists(username, period, limit = 50)
            .getOrNull().orEmpty()
        delay(SIMILAR_REQUEST_INTERVAL_MS)
    }
    return LastFmPersonas(tracks, artists)
}
```

Call it at the top of `doWork()` (after the `lastFmConfigured` line ~145):

```kotlin
val username = sessionPreference.session.first()?.username
val personas = if (lastFmConfigured && !username.isNullOrBlank()) {
    // v0.9.16: Bound the persona fetch — 5 periods × 2 endpoints =
    // 10 sequential HTTP calls. With a slow connection or upstream
    // hiccup, OkHttp's default timeouts could stack into multiple
    // minutes and blow past WorkManager's 10-min budget. 30s ceiling
    // means we degrade gracefully to library-only seeding when
    // Last.fm is sluggish; the next refresh tries again.
    runCatching {
        kotlinx.coroutines.withTimeout(30_000L) { fetchPersonas(username) }
    }.getOrElse { e ->
        Log.w(TAG, "persona fetch failed/timed-out, falling back to local seeds", e)
        LastFmPersonas.EMPTY
    }
} else LastFmPersonas.EMPTY
```

Pass `personas` down into `queueDiscoveryForRecipe(recipe, personas)`. Inject `LastFmSessionPreference` into the worker constructor.

- [ ] **Step 3: Compile + commit**

```bash
./gradlew :core:data:compileDebugKotlin
git add -A
git commit -m "feat(mix): fetch period-sliced Last.fm personas at refresh start"
```

---

## Phase 4 — Scoring upgrade in `MixGenerator`

The current scoring is `affinity*0.5 + tag_count*0.3 + freshness*0.2 + jitter*0.05`. After Phase 4 it becomes:

```
score = affinity_term * w_affinity
      + tag_cosine    * w_tag
      + completion    * w_completion
      + (loved ? loved_boost : 0)
      - skip_penalty
      + jitter

affinity_term = ln(1 + plays) / ln(1 + max_plays)   # existing log-normalize
              * exp(-Δdays / 30)                      # NEW exponential decay
              + ln(1 + lastfm_user_playcount) * 0.3   # NEW LFM affinity supplement
tag_cosine    = cos( track.tag_vector, user.tag_affinity_vector )
completion    = (count_with_completed_at / count_total)  # in last 60d
skip_penalty  = (skips / (skips + plays)) > 0.5 ? large : (linear ramp)
loved_boost   = lastfm_user_loved ? 0.5 : 0
```

### Task 4.1: `UserTagAffinity` module

**Files:**
- Create: `core/data/src/main/kotlin/com/stash/core/data/mix/UserTagAffinity.kt`
- Test: `core/data/src/test/kotlin/com/stash/core/data/mix/UserTagAffinityTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
// UserTagAffinityTest.kt
class UserTagAffinityTest {

    @Test
    fun `affinity vector is L2-normalized weighted sum of recent plays' tag vectors`() {
        // Two recent plays, one with tags [indie:100, dream pop:50] and
        // weight 1.0, the other with [indie:80, shoegaze:40] and weight 0.5.
        // Expected: indie dominant, dream pop and shoegaze present.
        val plays = listOf(
            PlayWithTags(weight = 1.0f, tags = mapOf("indie" to 100f, "dream pop" to 50f)),
            PlayWithTags(weight = 0.5f, tags = mapOf("indie" to 80f, "shoegaze" to 40f)),
        )
        val v = UserTagAffinity.compute(plays)

        // L2 normalized → magnitude 1.0
        val mag = sqrt(v.values.sumOf { it.toDouble() * it.toDouble() }).toFloat()
        assertEquals(1.0f, mag, 0.01f)

        // Indie weight should dominate (highest combined weight × tag weight)
        assertTrue("indie max", v.maxByOrNull { it.value }?.key == "indie")
    }

    @Test
    fun `cosine similarity returns 1 0 for identical vectors and 0 0 for orthogonal`() {
        val a = mapOf("a" to 1f, "b" to 0f)
        val b = mapOf("a" to 1f, "b" to 0f)
        val c = mapOf("a" to 0f, "b" to 1f)

        assertEquals(1.0f, UserTagAffinity.cosine(a, b), 0.001f)
        assertEquals(0.0f, UserTagAffinity.cosine(a, c), 0.001f)
    }

    @Test
    fun `cosine returns 0 for empty vectors`() {
        assertEquals(0f, UserTagAffinity.cosine(emptyMap(), mapOf("x" to 1f)), 0.001f)
    }
}
```

- [ ] **Step 2: Implement**

```kotlin
// UserTagAffinity.kt
package com.stash.core.data.mix

import kotlin.math.sqrt

/**
 * v0.9.16: Computes a per-user tag-affinity vector from recent
 * plays + per-track tag vectors. The vector is L2-normalized so
 * cosine similarity with a candidate's tag vector lands in [0, 1].
 *
 * Replaces the previous "count of overlapping top-tags" heuristic
 * in [MixGenerator] with proper weighted vector math.
 */
object UserTagAffinity {

    data class PlayWithTags(val weight: Float, val tags: Map<String, Float>)

    fun compute(plays: List<PlayWithTags>): Map<String, Float> {
        if (plays.isEmpty()) return emptyMap()
        val acc = HashMap<String, Float>()
        for (p in plays) {
            for ((tag, w) in p.tags) {
                acc.merge(tag, p.weight * w) { a, b -> a + b }
            }
        }
        return l2Normalize(acc)
    }

    fun cosine(a: Map<String, Float>, b: Map<String, Float>): Float {
        if (a.isEmpty() || b.isEmpty()) return 0f
        val (smaller, larger) = if (a.size < b.size) a to b else b to a
        var dot = 0f
        for ((k, v) in smaller) {
            dot += v * (larger[k] ?: 0f)
        }
        val magA = magnitude(a)
        val magB = magnitude(b)
        if (magA == 0f || magB == 0f) return 0f
        return dot / (magA * magB)
    }

    private fun l2Normalize(v: Map<String, Float>): Map<String, Float> {
        val mag = magnitude(v)
        if (mag == 0f) return v
        return v.mapValues { it.value / mag }
    }

    private fun magnitude(v: Map<String, Float>): Float =
        sqrt(v.values.sumOf { it.toDouble() * it.toDouble() }).toFloat()
}
```

- [ ] **Step 3: Run tests, expect PASS**

```bash
./gradlew :core:data:testDebugUnitTest --tests "com.stash.core.data.mix.UserTagAffinityTest"
```

- [ ] **Step 4: Commit**

```bash
git add core/data/src/main/kotlin/com/stash/core/data/mix/UserTagAffinity.kt \
        core/data/src/test/kotlin/com/stash/core/data/mix/UserTagAffinityTest.kt
git commit -m "feat(mix): UserTagAffinity — TF-IDF cosine over user tag prefs"
```

### Task 4.2: Wire `UserTagAffinity` + decay + completion + skip into `MixGenerator.generate`

**Files:**
- Modify: `core/data/src/main/kotlin/com/stash/core/data/mix/MixGenerator.kt`
- Inject: `TrackSkipEventDao`

- [ ] **Step 1: Update constants**

Replace the `companion object` (lines 65-71):

```kotlin
companion object {
    /** Recency window for affinity. Decay half-life is what controls "current"-ness. */
    private const val AFFINITY_WINDOW_MS = 180L * 24 * 60 * 60 * 1000

    /** Half-life of the affinity exponential decay, in milliseconds (30 days). */
    private const val AFFINITY_HALF_LIFE_MS = 30L * 24 * 60 * 60 * 1000

    /** Window for skip-rate computation. Shorter than affinity — skips age fast. */
    private const val SKIP_WINDOW_MS = 14L * 24 * 60 * 60 * 1000

    private const val BASE_AFFINITY_WEIGHT = 0.40f      // was 0.50; tag-cosine takes some
    private const val BASE_TAG_WEIGHT      = 0.35f      // was 0.30
    private const val BASE_COMPLETION_W    = 0.10f      // NEW
    private const val LOVED_BOOST          = 0.5f       // additive, not weight
    private const val SKIP_PENALTY_RAMP    = 0.6f       // skip-rate above which heavy penalty kicks in
    /**
     * Scalar applied to the Last.fm-user-playcount term INSIDE
     * buildAffinityMap, before the outer BASE_AFFINITY_WEIGHT
     * multiplication. Intentionally lower than parity with local
     * plays — Last.fm playcount counts every scrobble across every
     * service the user ever connected, so a single track with 200
     * lifetime LFM plays shouldn't outweigh a 30-play in-Stash
     * track from this month. Net effect: LFM contributes ~12% of
     * the affinity weight (0.3 * 0.40), local contributes ~40%.
     * Rebalance only after on-device data shows the bias is wrong.
     */
    private const val LFM_PLAYCOUNT_W      = 0.3f
    private const val SORT_JITTER          = 0.10f      // was 0.05; ~12% of nominal score range
}
```

- [ ] **Step 2: Replace `buildAffinityMap` with decay + LFM-playcount blend**

```kotlin
private suspend fun buildAffinityMap(pool: List<TrackEntity>): Map<Long, Float> {
    val now = System.currentTimeMillis()
    val since = now - AFFINITY_WINDOW_MS
    val rows = listeningEventDao.getPlayCountsSinceWithLatest(since)  // NEW DAO method, see Step 4
    val poolIds = pool.mapTo(HashSet(pool.size)) { it.id }

    if (rows.isEmpty() && pool.none { it.lastfmUserPlaycount != null && it.lastfmUserPlaycount > 0 }) {
        return emptyMap()
    }

    val maxPlays = (rows.maxOfOrNull { it.plays } ?: 1).coerceAtLeast(1)
    val maxLfmPlays = pool.maxOfOrNull { it.lastfmUserPlaycount ?: 0 }?.coerceAtLeast(1) ?: 1

    val byId = rows.associateBy { it.trackId }
    val result = HashMap<Long, Float>(pool.size)
    for (track in pool) {
        if (track.id !in poolIds) continue
        val row = byId[track.id]
        // In-Stash plays with exponential decay
        val localTerm = if (row != null) {
            val logNorm = (ln(1f + row.plays.toFloat()) / ln(1f + maxPlays.toFloat())).coerceIn(0f, 1f)
            val ageMs = (now - row.latestPlayedAt).coerceAtLeast(0)
            val decay = 0.5f.pow(ageMs.toFloat() / AFFINITY_HALF_LIFE_MS)
            logNorm * decay
        } else 0f
        // Last.fm cross-source playcount (only present after enrichment)
        val lfmTerm = track.lastfmUserPlaycount?.let { lpc ->
            (ln(1f + lpc.toFloat()) / ln(1f + maxLfmPlays.toFloat())).coerceIn(0f, 1f) * LFM_PLAYCOUNT_W
        } ?: 0f
        val combined = (localTerm + lfmTerm).coerceIn(0f, 1f)
        if (combined > 0f) result[track.id] = combined
    }
    return result
}
```

- [ ] **Step 3: New ListeningEventDao projection**

In `core/data/src/main/kotlin/com/stash/core/data/db/dao/ListeningEventDao.kt`:

```kotlin
data class TrackPlayCountWithLatest(
    val trackId: Long,
    val plays: Int,
    val latestPlayedAt: Long,
)

@Query(
    """
    SELECT track_id AS trackId, COUNT(*) AS plays, MAX(started_at) AS latestPlayedAt
    FROM listening_events
    WHERE started_at >= :sinceEpochMs
    GROUP BY track_id
    """
)
suspend fun getPlayCountsSinceWithLatest(sinceEpochMs: Long): List<TrackPlayCountWithLatest>
```

- [ ] **Step 4: Replace `buildTagMatchMap` with cosine via `UserTagAffinity`**

```kotlin
private suspend fun buildUserTagAffinityVector(): Map<String, Float> {
    val now = System.currentTimeMillis()
    val since = now - AFFINITY_WINDOW_MS
    val rows = listeningEventDao.getPlayCountsSinceWithLatest(since)
    if (rows.isEmpty()) return emptyMap()

    val plays = rows.map { row ->
        val ageMs = (now - row.latestPlayedAt).coerceAtLeast(0)
        val decay = 0.5f.pow(ageMs.toFloat() / AFFINITY_HALF_LIFE_MS)
        val weight = ln(1f + row.plays.toFloat()) * decay
        val tags = trackTagDao.getByTrack(row.trackId)
            .filter { it.tag != "__untaggable__" }
            .associate { it.tag.lowercase() to it.weight }
        UserTagAffinity.PlayWithTags(weight = weight, tags = tags)
    }
    return UserTagAffinity.compute(plays)
}

private suspend fun buildTagCosineMap(
    pool: List<TrackEntity>,
    userVector: Map<String, Float>,
): Map<Long, Float> {
    if (userVector.isEmpty()) return emptyMap()
    val result = HashMap<Long, Float>(pool.size)
    for (track in pool) {
        val tags = trackTagDao.getByTrack(track.id)
            .filter { it.tag != "__untaggable__" }
            .associate { it.tag.lowercase() to it.weight }
        if (tags.isEmpty()) continue
        result[track.id] = UserTagAffinity.cosine(tags, userVector)
    }
    return result
}
```

- [ ] **Step 5: Compute completion-rate + skip-rate maps**

```kotlin
private suspend fun buildCompletionMap(pool: List<TrackEntity>): Map<Long, Float> {
    val since = System.currentTimeMillis() - 60L * 24 * 60 * 60 * 1000  // 60-day window
    val rows = listeningEventDao.getCompletionStatsSince(pool.map { it.id }, since)
    return rows.associate { it.trackId to (it.completed.toFloat() / it.total.coerceAtLeast(1)) }
}

private suspend fun buildSkipPenaltyMap(pool: List<TrackEntity>): Map<Long, Float> {
    val since = System.currentTimeMillis() - SKIP_WINDOW_MS
    val rows = trackSkipEventDao.getSkipStatsSince(pool.map { it.id }, since)
    return rows.mapNotNull { row ->
        val total = row.skips + row.plays
        if (total < 3) return@mapNotNull null  // not enough data
        val rate = row.skips.toFloat() / total
        val penalty = when {
            rate >= SKIP_PENALTY_RAMP -> 0.6f                    // shadow-block-ish
            rate >= 0.4f -> (rate - 0.4f) / 0.2f * 0.4f          // linear ramp
            else -> 0f
        }
        if (penalty > 0f) row.trackId to penalty else null
    }.toMap()
}
```

Add `getCompletionStatsSince` to `ListeningEventDao`:

```kotlin
data class CompletionStats(val trackId: Long, val total: Int, val completed: Int)

@Query(
    """
    SELECT track_id AS trackId,
           COUNT(*) AS total,
           SUM(CASE WHEN completed_at IS NOT NULL THEN 1 ELSE 0 END) AS completed
    FROM listening_events
    WHERE track_id IN (:trackIds) AND started_at >= :sinceMs
    GROUP BY track_id
    """
)
suspend fun getCompletionStatsSince(trackIds: List<Long>, sinceMs: Long): List<CompletionStats>
```

- [ ] **Step 6: Update the scoring loop in `generate()`**

Replace the score block (`MixGenerator.kt:120-128`) with:

```kotlin
val userVector = buildUserTagAffinityVector()
val affinityMap = buildAffinityMap(pool)
val tagCosineMap = buildTagCosineMap(pool, userVector)
val completionMap = buildCompletionMap(pool)
val skipPenaltyMap = buildSkipPenaltyMap(pool)

val wAff = BASE_AFFINITY_WEIGHT + recipe.affinityBias * 0.3f
val wTag = BASE_TAG_WEIGHT
val wCmp = BASE_COMPLETION_W

val scored = pool.map { track ->
    val aff = affinityMap[track.id] ?: 0f
    val tag = tagCosineMap[track.id] ?: 0f
    val cmp = completionMap[track.id] ?: 0.5f         // unknown → neutral
    val loved = if (track.lastfmUserLoved) LOVED_BOOST else 0f
    val skip = skipPenaltyMap[track.id] ?: 0f
    val score = aff * wAff +
        tag * wTag +
        cmp * wCmp +
        loved -
        skip +
        Random.nextFloat() * SORT_JITTER
    track to score
}
```

- [ ] **Step 7: Inject `TrackSkipEventDao` into `MixGenerator`**

Update constructor to inject the new DAO.

- [ ] **Step 8: Compile + run all data tests**

```bash
./gradlew :core:data:testDebugUnitTest
```

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "feat(mix): cosine tag affinity + decay + completion + skip + LFM-playcount scoring"
```

---

## Phase 5 — Skip capture in `ListeningRecorder`

### Task 5.1: Capture skip on track-id transition

**Files:**
- Modify: `core/media/src/main/kotlin/com/stash/core/media/listening/ListeningRecorder.kt:43-76`

- [ ] **Step 1: Inject `TrackSkipEventDao`**

Update the constructor to inject `TrackSkipEventDao`. `playerRepository` is already injected.

- [ ] **Step 2: Replace pending-job state with a `PendingFire` holder**

The current code holds a single `pendingFireJob: Job?`. After `cancel()` runs, `Job.isCompleted` is `true` regardless of whether the delayed body ran or not — so we can't read it to distinguish "fired" from "cancelled before fire." We need an explicit flag stored alongside each scheduled job, plus a snapshot of the position so we can record the skip's position before the player advances to the next track.

Add a private holder data class inside the recorder:

```kotlin
private data class PendingFire(
    val trackId: Long,
    val sessionStart: Long,
    val job: Job,
    val firedFlag: java.util.concurrent.atomic.AtomicBoolean,
    val positionAtScheduleMs: Long,
)
```

Replace `pendingFireJob: Job? = null` with `private var pending: PendingFire? = null`.

- [ ] **Step 3: Set the fired flag inside the delayed body**

Where the recorder schedules the threshold-fire (around `pendingFireJob = scope.launch { delay(threshold) ... }`), restructure to:

```kotlin
val firedFlag = java.util.concurrent.atomic.AtomicBoolean(false)
val job = scope.launch {
    delay(threshold)
    val nowPlaying = playerRepository.playerState.value.currentTrack?.id
    if (nowPlaying == track.id) {
        firedFlag.set(true) // mark fired BEFORE the insert so a race with
                            // the next track-change collector observes the
                            // correct state when it reads .get()
        runCatching {
            listeningEventDao.insert(
                ListeningEventEntity(
                    trackId = track.id,
                    startedAt = sessionStart,
                    scrobbled = false,
                    completedAt = sessionStart,
                ),
            )
        }.onFailure { Log.w(TAG, "Failed to insert listening event", it) }
    }
}
pending = PendingFire(
    trackId = track.id,
    sessionStart = sessionStart,
    job = job,
    firedFlag = firedFlag,
    positionAtScheduleMs = playerRepository.playerState.value.positionMs,
)
```

- [ ] **Step 4: Detect skip when a new track-id arrives**

In the `playerState.distinctUntilChangedBy { it.currentTrack?.id }.collect` block, BEFORE scheduling the new track's fire, snapshot the previous pending state and record a skip if it never fired:

```kotlin
playerRepository.playerState
    .distinctUntilChangedBy { it.currentTrack?.id }
    .collect { state ->
        // 1. Snapshot previous pending (if any) BEFORE we mutate it.
        val previousPending = pending
        // Capture the previous position from the LAST emitted state, not
        // from `state` (which is already on the new track). The recorder
        // sees per-position updates via the same flow; the value we
        // captured at schedule time + a player-position read here gives
        // the best approximation of the skip moment.
        val previousPosition = previousPending?.positionAtScheduleMs ?: 0L

        // 2. Cancel + record skip if the previous fire never ran.
        if (previousPending != null) {
            previousPending.job.cancel()
            if (!previousPending.firedFlag.get()) {
                val skipAt = System.currentTimeMillis()
                scope.launch {
                    runCatching {
                        trackSkipEventDao.insert(
                            TrackSkipEventEntity(
                                trackId = previousPending.trackId,
                                skippedAt = skipAt,
                                positionMs = previousPosition,
                            )
                        )
                    }.onFailure { Log.w(TAG, "skip insert failed", it) }
                }
            }
        }
        pending = null

        // 3. Schedule the new track's fire (existing logic — see Step 3).
        val track = state.currentTrack ?: return@collect
        // ... existing setup for sessionStart, threshold, scheduling ...
    }
```

> **Why this works:** `firedFlag` is an `AtomicBoolean` set inside the delay-block right before the insert. Job cancellation does not affect the flag. So when the next track arrives, the sequence is:
> - If the delay completed and the insert ran: `firedFlag=true`, `cancel()` is a no-op (job already complete), no skip recorded.
> - If the delay was still running when cancel arrived: `firedFlag=false`, the insert never ran, skip is recorded.

- [ ] **Step 5: Test**

```kotlin
// core/media/src/test/kotlin/com/stash/core/media/listening/ListeningRecorderSkipTest.kt
@Test
fun `track id transition before threshold fire records a skip`() = runTest {
    val playerStateFlow = MutableStateFlow(PlayerState(currentTrack = trackA, positionMs = 5_000))
    val playerRepo = mockk<PlayerRepository> {
        every { playerState } returns playerStateFlow
    }
    val skipDao = mockk<TrackSkipEventDao>(relaxed = true)
    val listeningDao = mockk<ListeningEventDao>(relaxed = true)
    val captured = slot<TrackSkipEventEntity>()
    coEvery { skipDao.insert(capture(captured)) } returns 1L

    val recorder = ListeningRecorder(playerRepo, listeningDao, skipDao, /* scope = */ this)
    recorder.start()
    advanceTimeBy(5_000)              // less than 30s threshold
    playerStateFlow.value = PlayerState(currentTrack = trackB, positionMs = 0)
    advanceUntilIdle()

    assertEquals(trackA.id, captured.captured.trackId)
    assertEquals(5_000L, captured.captured.positionMs)
    coVerify(exactly = 0) { listeningDao.insert(any()) }
}

@Test
fun `track id transition after threshold fire records listen but no skip`() = runTest {
    // ... setup as above ...
    recorder.start()
    advanceTimeBy(35_000)             // crosses 30s threshold
    playerStateFlow.value = PlayerState(currentTrack = trackB, positionMs = 0)
    advanceUntilIdle()

    coVerify(exactly = 1) { listeningDao.insert(match { it.trackId == trackA.id }) }
    coVerify(exactly = 0) { skipDao.insert(any()) }
}
```

- [ ] **Step 6: Run tests + commit**

```bash
./gradlew :core:media:testDebugUnitTest --tests "com.stash.core.media.listening.ListeningRecorderSkipTest"
git add -A
git commit -m "feat(player): capture skip events with AtomicBoolean fired-flag"
```

---

## Phase 6 — Discovery improvements

### Task 6.1: Discovery dedup TTL

**Files:**
- Modify: `core/data/src/main/kotlin/com/stash/core/data/db/dao/DiscoveryQueueDao.kt:109-119`
- Modify: `core/data/src/main/kotlin/com/stash/core/data/mix/MixGenerator.kt:283`

- [ ] **Step 1: Replace `existsForRecipe` with TTL-aware version**

In `DiscoveryQueueDao.kt`, **delete** the existing `existsForRecipe` (lines 109-119) and replace with:

```kotlin
@Query(
    """
    SELECT EXISTS(
        SELECT 1 FROM discovery_queue
        WHERE recipe_id = :recipeId
          AND LOWER(artist) = LOWER(:artist)
          AND LOWER(title) = LOWER(:title)
          AND queued_at >= :sinceMs
    )
    """
)
suspend fun existsForRecipeSince(
    recipeId: Long,
    artist: String,
    title: String,
    sinceMs: Long,
): Boolean
```

- [ ] **Step 2: Update the only caller in `MixGenerator`**

In `core/data/src/main/kotlin/com/stash/core/data/mix/MixGenerator.kt:283`, replace:

```kotlin
val exists = discoveryQueueDao.existsForRecipe(recipe.id, cand.artist, cand.title)
```

with:

```kotlin
// v0.9.16: 30-day TTL on dedup so candidates that failed download
// or were skipped/blocked previously can re-enter the funnel after
// a month — keeps the discovery surface fresh.
val dedupSinceMs = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
val exists = discoveryQueueDao.existsForRecipeSince(
    recipe.id, cand.artist, cand.title, dedupSinceMs,
)
```

Verify no other callers exist:

```bash
grep -rn "existsForRecipe\b" core/ data/ feature/ app/
```
Expected: only the line you just updated in `MixGenerator.kt`. The DAO definition itself does not count.

- [ ] **Step 3: Compile + commit**

```bash
./gradlew :core:data:compileDebugKotlin
git add -A
git commit -m "fix(mix): discovery dedup TTL — re-allow candidates after 30 days"
```

### Task 6.2: `MixSeedStrategy` enum + per-strategy generators

**Files:**
- Create: `core/data/src/main/kotlin/com/stash/core/data/mix/MixSeedStrategy.kt`
- Test: `core/data/src/test/kotlin/com/stash/core/data/mix/MixSeedStrategyTest.kt`

- [ ] **Step 1: Implement**

```kotlin
// core/data/src/main/kotlin/com/stash/core/data/mix/MixSeedStrategy.kt
package com.stash.core.data.mix

import com.stash.core.data.lastfm.LastFmApiClient
import com.stash.core.data.lastfm.LastFmPersonas
import kotlinx.coroutines.delay

enum class MixSeedStrategy(val storedValue: String) {
    ARTIST_SIMILAR("ARTIST_SIMILAR"),
    TAG_GRAPH("TAG_GRAPH"),
    TRACK_SIMILAR("TRACK_SIMILAR"),
    NONE("NONE");

    companion object {
        fun fromStored(s: String): MixSeedStrategy =
            entries.firstOrNull { it.storedValue == s } ?: ARTIST_SIMILAR
    }
}

/**
 * v0.9.16: Per-recipe candidate generators. Each strategy queries
 * a different Last.fm graph using different inputs from the user's
 * personas + library. Output is the candidate list passed to
 * [com.stash.core.data.mix.MixGenerator.queueDiscoveryCandidates].
 */
class MixSeedGenerator(
    private val apiClient: LastFmApiClient,
    private val intervalMs: Long = 220L,
) {
    suspend fun generate(
        strategy: MixSeedStrategy,
        seedArtists: List<String>,
        topTags: List<String>,
        seedTracks: List<Pair<String, String>>,  // (artist, title)
        personas: LastFmPersonas,
    ): List<MixGenerator.DiscoveryCandidate> = when (strategy) {
        MixSeedStrategy.ARTIST_SIMILAR -> generateArtistSimilar(seedArtists)
        MixSeedStrategy.TAG_GRAPH -> generateTagGraph(topTags)
        MixSeedStrategy.TRACK_SIMILAR -> generateTrackSimilar(seedTracks)
        MixSeedStrategy.NONE -> emptyList()
    }

    private suspend fun generateArtistSimilar(seedArtists: List<String>): List<MixGenerator.DiscoveryCandidate> {
        val out = mutableListOf<MixGenerator.DiscoveryCandidate>()
        for (seed in seedArtists) {
            val similar = apiClient.getSimilarArtists(seed, 5).getOrNull().orEmpty()
            for (sim in similar) {
                val top = apiClient.getArtistTopTracks(sim.name, 3).getOrNull().orEmpty()
                top.forEach { out += MixGenerator.DiscoveryCandidate(it.artist, it.title, seed) }
                delay(intervalMs)
            }
        }
        return out
    }

    private suspend fun generateTagGraph(topTags: List<String>): List<MixGenerator.DiscoveryCandidate> {
        val out = mutableListOf<MixGenerator.DiscoveryCandidate>()
        for (tag in topTags.take(10)) {
            val tracks = apiClient.getTagTopTracks(tag, 30).getOrNull().orEmpty()
            tracks.forEach { out += MixGenerator.DiscoveryCandidate(it.artist, it.title, "tag:$tag") }
            delay(intervalMs)
        }
        return out
    }

    private suspend fun generateTrackSimilar(seedTracks: List<Pair<String, String>>): List<MixGenerator.DiscoveryCandidate> {
        val out = mutableListOf<MixGenerator.DiscoveryCandidate>()
        for ((artist, title) in seedTracks.take(10)) {
            val similar = apiClient.getSimilarTracks(artist, title, 10).getOrNull().orEmpty()
            similar.forEach { out += MixGenerator.DiscoveryCandidate(it.artist, it.title, "$artist - $title") }
            delay(intervalMs)
        }
        return out
    }
}
```

- [ ] **Step 2: Tests + commit**

(Mock `apiClient`, verify the right endpoint set is called per strategy. Standard mockk pattern.)

```bash
./gradlew :core:data:testDebugUnitTest --tests "com.stash.core.data.mix.MixSeedStrategyTest"
git add -A
git commit -m "feat(mix): MixSeedStrategy + generator (artist/tag/track similar)"
```

### Task 6.3: Wire `MixSeedGenerator` into `StashMixRefreshWorker.queueDiscoveryForRecipe`

**Files:**
- Modify: `core/data/src/main/kotlin/com/stash/core/data/mix/MixGenerator.kt` (expose top-tags helper)
- Modify: `core/data/src/main/kotlin/com/stash/core/data/sync/workers/StashMixRefreshWorker.kt:277-334`

- [ ] **Step 1: Add `computeUserTopTags` helper to `MixGenerator`**

`MixGenerator` already builds a tag-affinity vector in Task 4.2 via `buildUserTagAffinityVector()`. Expose a public helper that returns the top-N tag names by weight, for use by the refresh worker's tag-graph strategy:

```kotlin
// In MixGenerator.kt, add as a public suspend fun:

/**
 * v0.9.16: Top-N user tags ordered by tag-affinity weight. Used by
 * [com.stash.core.data.sync.workers.StashMixRefreshWorker] to drive
 * the TAG_GRAPH seed strategy. Returns empty list when the user has
 * no listening history yet.
 */
suspend fun computeUserTopTags(limit: Int = 10): List<String> {
    val vector = buildUserTagAffinityVector()
    return vector.entries
        .sortedByDescending { it.value }
        .take(limit)
        .map { it.key }
}
```

- [ ] **Step 2: Replace `queueDiscoveryForRecipe` body**

Inject `MixSeedGenerator` into the worker constructor (alongside the existing `MixGenerator` injection). Replace the function:

```kotlin
private suspend fun queueDiscoveryForRecipe(
    recipe: StashMixRecipeEntity,
    personas: LastFmPersonas,
) {
    val strategy = MixSeedStrategy.fromStored(recipe.seedStrategy)
    if (strategy == MixSeedStrategy.NONE) return

    val since = System.currentTimeMillis() - AFFINITY_LOOKBACK_DAYS * 24 * 60 * 60 * 1000

    val seedArtists = personas.topArtistsByPeriod[LastFmPeriod.ONE_MONTH]
        ?.takeIf { it.isNotEmpty() }?.take(TOP_ARTISTS_LIMIT)?.map { it.name }
        ?: listeningEventDao.getTopArtistsSince(since, TOP_ARTISTS_LIMIT).map { it.artist }
            .ifEmpty { trackDao.getTopArtistsByTrackCount(TOP_ARTISTS_LIMIT) }

    val seedTracks = personas.topTracksByPeriod[LastFmPeriod.ONE_MONTH]
        ?.take(20)?.map { it.artist to it.title }
        ?: emptyList()

    val topTags = mixGenerator.computeUserTopTags(limit = 10)

    val candidates = seedGenerator.generate(
        strategy = strategy,
        seedArtists = seedArtists,
        topTags = topTags,
        seedTracks = seedTracks,
        personas = personas,
    )
    if (candidates.isEmpty()) return
    Log.i(TAG, "'${recipe.name}': ${candidates.size} candidates via $strategy")
    mixGenerator.queueDiscoveryCandidates(recipe, candidates)
}
```

- [ ] **Step 3: Compile + commit**

```bash
./gradlew :core:data:compileDebugKotlin
git add -A
git commit -m "feat(mix): per-recipe seed strategy in refresh worker"
```

---

## Phase 7 — Multi-recipe

### Task 7.1: Update `StashMixDefaults` to seed three recipes

**Files:**
- Modify: `core/data/src/main/kotlin/com/stash/core/data/mix/StashMixDefaults.kt`

- [ ] **Step 1: Replace `ALL`**

```kotlin
val ALL: List<StashMixRecipeEntity> = listOf(
    StashMixRecipeEntity(
        name = "Daily Discover",
        description = "Personalized blend of your library + fresh finds.",
        affinityBias = 0.3f,
        freshnessWindowDays = 7,
        discoveryRatio = 0.4f,
        targetLength = 50,
        seedStrategy = "ARTIST_SIMILAR",
        isBuiltin = true,
    ),
    StashMixRecipeEntity(
        name = "Deep Cuts",
        description = "Tracks you used to love that haven't been on rotation.",
        affinityBias = 0.6f,
        freshnessWindowDays = 90,  // exclude recent — Deep Cuts is "forgotten"
        discoveryRatio = 0f,        // library only
        targetLength = 50,
        seedStrategy = "NONE",
        isBuiltin = true,
    ),
    StashMixRecipeEntity(
        name = "First Listen",
        description = "Tracks you've never heard. Wider net.",
        affinityBias = 0.0f,
        freshnessWindowDays = 14,
        discoveryRatio = 1.0f,      // 100% discovery
        targetLength = 50,
        seedStrategy = "TAG_GRAPH",  // wider than artist-similar
        isBuiltin = true,
    ),
)
```

- [ ] **Step 2: Update `StashApplication.maybeReseedStashMixes`**

Bump `STASH_MIX_RECIPE_VERSION` SharedPreference flag → triggers `deleteAllBuiltins` + reseed.

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "feat(mix): three recipes — Daily Discover, Deep Cuts, First Listen"
```

---

## Phase 8 — Manual refresh UI

### Task 8.1: Long-press menu on mix card → "Refresh this mix"

**Files:**
- Modify: `core/data/src/main/kotlin/com/stash/core/data/sync/workers/StashMixRefreshWorker.kt` (companion + `doWork`)
- Modify: `feature/home/src/main/kotlin/com/stash/feature/home/HomeScreen.kt:317-339, 802-887`
- Modify: `feature/home/src/main/kotlin/com/stash/feature/home/HomeViewModel.kt`

- [ ] **Step 1: Extend `StashMixRefreshWorker` to support single-recipe refresh**

In `core/data/src/main/kotlin/com/stash/core/data/sync/workers/StashMixRefreshWorker.kt`:

In the `companion object` (line 74), add a constant:

```kotlin
const val KEY_RECIPE_ID = "stash_mix_refresh_recipe_id"
```

In `doWork()` (line 129), modify the `recipeDao.getActive()` call to honor the optional input data. Replace lines 137-141 (the `val active = recipeDao.getActive() ...` block) with:

```kotlin
val targetId = inputData.getLong(KEY_RECIPE_ID, -1L)
val active = if (targetId > 0L) {
    val one = recipeDao.getById(targetId)?.takeIf { it.isActive }
    if (one == null) {
        Log.d(TAG, "single-recipe refresh: recipe $targetId not found or inactive")
        return Result.success()
    }
    listOf(one)
} else {
    recipeDao.getActive()
}
if (active.isEmpty()) {
    Log.d(TAG, "no active recipes")
    return Result.success()
}
Log.i(TAG, "refreshing ${active.size} Stash Mix(es)" +
    if (targetId > 0L) " (single: ${active.first().name})" else "")
```

The rest of `doWork` (the `for (recipe in active)` loop and persona fetch) is unchanged — the persona fetch runs once whether we're refreshing 1 or N recipes, and the per-recipe loop already operates over a single-element list when `targetId > 0`.

Add an overload to `enqueueOneTime` that accepts a recipe id:

```kotlin
fun enqueueOneTime(context: Context, recipeId: Long) {
    val data = androidx.work.workDataOf(KEY_RECIPE_ID to recipeId)
    val work = androidx.work.OneTimeWorkRequestBuilder<StashMixRefreshWorker>()
        .setConstraints(
            androidx.work.Constraints.Builder()
                .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                .build()
        )
        .setInputData(data)
        .build()
    androidx.work.WorkManager.getInstance(context).enqueueUniqueWork(
        "stash_mix_refresh_oneshot_$recipeId",
        androidx.work.ExistingWorkPolicy.REPLACE,
        work,
    )
}
```

(Keep the existing zero-arg `enqueueOneTime(context)` — it's called from `StashApplication` for the all-recipes path.)

- [ ] **Step 2: Add `refreshMix` to `HomeViewModel`**

Inject `StashMixRecipeDao` and `@ApplicationContext context: Context` into `HomeViewModel`. Add:

```kotlin
fun refreshMix(playlistId: Long) {
    viewModelScope.launch {
        val recipe = recipeDao.findByPlaylistId(playlistId) ?: return@launch
        StashMixRefreshWorker.enqueueOneTime(context, recipe.id)
    }
}
```

- [ ] **Step 3: Surface long-press → Refresh in `HomeScreen`**

In the existing `selectedPlaylist` bottom-sheet flow that handles long-press (the `selectedPlaylist by remember` state), add a row above the existing actions:

```kotlin
if (selectedPlaylist?.type == PlaylistType.STASH_MIX) {
    BottomSheetActionRow(
        icon = Icons.Default.Refresh,
        label = "Refresh this mix",
        onClick = {
            viewModel.refreshMix(selectedPlaylist!!.id)
            selectedPlaylist = null
        },
    )
}
```

(Match the existing `BottomSheetActionRow` composable style in `HomeScreen.kt`.)

- [ ] **Step 4: Commit**

```bash
./gradlew :app:compileDebugKotlin
git add -A
git commit -m "feat(home): manual single-mix refresh from long-press menu"
```

---

## Phase 9 — Acceptance + ship

### Task 9.1: Build + install + on-device smoke

- [ ] **Step 1: Run full unit tests**

```bash
./gradlew :core:data:testDebugUnitTest :feature:home:testDebugUnitTest
```

- [ ] **Step 2: Install + smoke**

```bash
./gradlew :app:installDebug
```

On device:
1. Open Home — verify three mix cards (Daily Discover, Deep Cuts, First Listen).
2. Long-press a mix → "Refresh this mix" — verify the mix updates within ~30s (look for new track names).
3. Skip a track within first 5 seconds — verify (via adb shell sqlite3) a row appears in `track_skip_events`.
4. Wait 24h or trigger one-shot — verify `tracks.lastfm_user_playcount` is populated for already-listened tracks.

### Task 9.2: Version bump + tag

- [ ] **Step 1: Bump version**

`app/build.gradle.kts:75-76`:
```kotlin
versionCode = 54
versionName = "0.9.16"
```

- [ ] **Step 2: Commit + tag + push**

```bash
git commit -am "chore: bump versionCode 53->54, versionName 0.9.15->0.9.16"
git tag -a v0.9.16 -m "v0.9.16 - Discover Mix tuning"
git push origin HEAD
git push origin v0.9.16
```

---

## Test plan summary

| Layer | Tests |
|---|---|
| Unit (parser) | `LastFmTrackInfoParserTest` — 3 cases |
| Unit (math) | `UserTagAffinityTest` — 3 cases |
| Unit (strategy) | `MixSeedStrategyTest` — 3 cases (one per non-NONE strategy) |
| Migration | `MigrationV20V21Test` — 1 case |
| Worker | `TrackInfoEnrichmentWorkerTest` — 2 cases |
| Skip capture | `ListeningRecorderSkipTest` — 2 cases |
| On-device | 4-step smoke in Task 9.1 |

## Roll-back

Each phase is reversible up to the migration. Phase 2 schema bump is one-way once shipped — the column adds + new table are additive, but the recipe/worker logic depends on them.

## Out of scope

- ListenBrainz CF integration (deferred — see prior research)
- AcousticBrainz audio features (deferred)
- "Not for me" explicit UI button (skip-rate auto-shadow handles the same need)
- Genre/mood selectors (deferred to v0.9.17 — depends on tag clustering work that's larger)
- Session-transition autoplay (Path G — deferred to v0.9.17)
- Multi-Spotify-account split-likes (separate from mix tuning; tracked elsewhere)
