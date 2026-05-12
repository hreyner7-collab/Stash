# Phase 1: Genuinely-New Discovery + Skip-Feedback Loop Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop surfacing library content in Stash Mix discovery slots. Pre-filter Last.fm candidates against the user's library AND against tracks they've early-skipped 3+ times in 90 days. Plus: relax manual-trigger network constraint so refresh fires on any network.

**Architecture:** Three independent surgical changes. `constraintsForManualTrigger` becomes mode-independent (`CONNECTED + battery-not-low`). Two new DAO queries return canonical-key sets. `StashMixRefreshWorker.queueDiscoveryForRecipe` loads both sets, filters Last.fm candidates against them at the seed-generator boundary, falls back to TAG_GRAPH (with timeout) when filtered pool is too small.

**Tech Stack:** Kotlin, Room, WorkManager 2.10.1, Hilt, MockK + JUnit4 for unit tests, Robolectric + Room in-memory for DAO tests.

**Spec:** `docs/superpowers/specs/2026-05-12-genuinely-new-discovery-and-skip-feedback-design.md` (committed at `79a4d7e`).

---

## Pre-flight notes for the implementer

Worktree: `C:\Users\theno\Projects\MP3APK\.worktrees\first-listen-tag-fallback`. Branch: `feat/first-listen-tag-fallback`. PR 5 + corruption hotfix already shipped 18 commits; PR 6 lands on top.

### Repository conventions

1. **TDD**: failing test first for DAOs and worker logic; build/run verification for config changes.
2. **MockK** for worker unit tests (precedent: `StashMixRefreshWorkerDedupTest`, `LosslessRetryWorkerTest`).
3. **Robolectric + Room in-memory** for DAO behavior tests (precedent: `DiscoveryQueueDaoCapTest`, `DownloadQueueDaoPartitionTest`).
4. **No push, no tag.** Implementation ends at APK installed on Pixel + manual verification.
5. **Always `:app:installRelease` after a fix** — per memory `feedback_install_after_fix.md`.

### Files you will touch

| Path | What changes |
|---|---|
| `core/data/src/main/kotlin/com/stash/core/data/sync/workers/DownloadConstraints.kt` | `constraintsForManualTrigger(mode)` body becomes mode-independent: `CONNECTED + battery-not-low + no charging`. |
| `core/data/src/test/kotlin/com/stash/core/data/sync/workers/DownloadConstraintsTest.kt` | Rewrite the three per-mode tests as one mode-independence test (or three explicit ones — pick the cleaner shape). |
| `core/data/src/main/kotlin/com/stash/core/data/db/dao/TrackDao.kt` | Add `getLibraryCanonicalKeys(): List<String>` query. |
| `core/data/src/test/kotlin/com/stash/core/data/db/dao/TrackDaoCanonicalKeysTest.kt` | **CREATE** — Robolectric tests for the new DAO method. |
| `core/data/src/main/kotlin/com/stash/core/data/db/dao/TrackSkipEventDao.kt` | Add `getEarlySkipBannedCanonicalKeys(minSkips, sinceMs, maxPositionMs): List<String>`. |
| `core/data/src/test/kotlin/com/stash/core/data/db/dao/TrackSkipEventDaoBanListTest.kt` | **CREATE** — Robolectric tests for the new DAO method. |
| `core/data/src/main/kotlin/com/stash/core/data/sync/workers/StashMixRefreshWorker.kt` | Inject `TrackSkipEventDao` and `TrackMatcher`. Add constants. Modify `queueDiscoveryForRecipe` to apply the library + skip-ban filters and the TAG_GRAPH fallback with timeout. |
| `core/data/src/test/kotlin/com/stash/core/data/sync/workers/StashMixRefreshWorkerSeedFilterTest.kt` | **CREATE** — MockK test for the new filter behavior + fallback path. |

### Reference precedents

- DAO Robolectric test: `core/data/src/test/kotlin/com/stash/core/data/db/dao/DiscoveryQueueDaoCapTest.kt`.
- Worker MockK test: `core/data/src/test/kotlin/com/stash/core/data/sync/workers/StashMixRefreshWorkerDedupTest.kt`.
- Constraints behavior test: `core/data/src/test/kotlin/com/stash/core/data/sync/workers/DownloadConstraintsTest.kt` (from PR 5 Task 1).
- `TrackMatcher` location: `com.stash.core.data.sync.TrackMatcher` (verified — `@Singleton class TrackMatcher @Inject constructor()`; non-suspend `canonicalArtist(s)` / `canonicalTitle(s)` methods produce lowercase + trim same as the sync writer's normalization).

---

## Task 1: Relax `constraintsForManualTrigger` to be mode-independent

**Files:**
- Modify: `core/data/src/main/kotlin/com/stash/core/data/sync/workers/DownloadConstraints.kt`
- Modify: `core/data/src/test/kotlin/com/stash/core/data/sync/workers/DownloadConstraintsTest.kt`

### Step 1: Read the current state

Open `DownloadConstraints.kt`. The current `constraintsForManualTrigger(mode)` (added in PR 5 Task 1) branches on mode to set `UNMETERED` vs `CONNECTED`. We're collapsing that to always `CONNECTED`.

### Step 2: Rewrite the production fn

Replace the body of `constraintsForManualTrigger` with:

```kotlin
/**
 * Constraints for **manual user-initiated triggers** — the user explicitly
 * tapped "Refresh this mix" or the app booted with orphan downloads to
 * drain. They're asking for content right now; the system honors that on
 * whatever network is available.
 *
 * Differs from [constraintsFor]:
 *  - Charging requirement DROPPED (user is actively using the app).
 *  - Network requirement RELAXED from UNMETERED to CONNECTED — manual
 *    triggers don't gate on cellular preference. The user's
 *    DownloadNetworkMode pref still governs the periodic background
 *    cycle (via [constraintsFor]); it doesn't govern foreground intent.
 *
 * `setRequiresBatteryNotLow(true)` stays on — a 5% battery + manual
 * refresh is still a bad combination.
 *
 * v0.9.20 history: shipped in PR 5 respecting DownloadNetworkMode for
 * cellular gating. After a corruption-induced pref reset left the user
 * stuck (default mode = WIFI_AND_CHARGING → manual triggers required
 * unmetered → no firing on cellular), we relaxed to CONNECTED to match
 * how every major music app handles user-initiated downloads.
 */
@Suppress("UNUSED_PARAMETER")
fun constraintsForManualTrigger(mode: DownloadNetworkMode): Constraints = Constraints.Builder()
    .setRequiresBatteryNotLow(true)
    .setRequiredNetworkType(NetworkType.CONNECTED)
    .build()
```

The `mode` parameter is preserved so callers don't break; `@Suppress("UNUSED_PARAMETER")` quiets the warning.

### Step 3: Rewrite the test

In `DownloadConstraintsTest.kt`, the three existing per-mode tests assert different `NetworkType` for `WIFI_AND_CHARGING` (UNMETERED) vs `ANY_NETWORK` (CONNECTED). After this change they're all CONNECTED. Replace them with a single mode-independence assertion:

```kotlin
@Test fun `constraintsForManualTrigger ignores mode — always CONNECTED + battery-not-low + no charging`() {
    for (mode in DownloadNetworkMode.values()) {
        val constraints = constraintsForManualTrigger(mode)
        assertEquals("mode=$mode", NetworkType.CONNECTED, constraints.requiredNetworkType)
        assertFalse("mode=$mode", constraints.requiresCharging())
        assertTrue("mode=$mode", constraints.requiresBatteryNotLow())
    }
}
```

Delete the three old per-mode tests. Keep imports.

### Step 4: Run tests — expect pass

```bash
./gradlew :core:data:testDebugUnitTest --tests "com.stash.core.data.sync.workers.DownloadConstraintsTest"
```

Expected: 1 test passes (after the fn rewrite).

### Step 5: Run the :core:data full sweep

```bash
./gradlew :core:data:testDebugUnitTest
```

Expected: BUILD SUCCESSFUL.

### Step 6: Commit

```bash
git add core/data/src/main/kotlin/com/stash/core/data/sync/workers/DownloadConstraints.kt \
        core/data/src/test/kotlin/com/stash/core/data/sync/workers/DownloadConstraintsTest.kt
git commit -m "feat(download): manual-trigger constraints relax to CONNECTED, mode-independent

PR 5 shipped constraintsForManualTrigger respecting DownloadNetworkMode
for cellular gating. After a corruption-induced pref reset left the
user stuck (default mode = WIFI_AND_CHARGING → manual triggers required
unmetered → no firing on cellular WiFi reporting as metered), we relax
the manual path to always CONNECTED.

Industry pattern: background = respect WiFi-only toggle; manual user
action = honor on whatever network. Periodic StashDiscoveryWorker still
uses constraintsFor(mode) which strictly respects the user pref.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: `TrackDao.getLibraryCanonicalKeys`

**Files:**
- Modify: `core/data/src/main/kotlin/com/stash/core/data/db/dao/TrackDao.kt`
- Create: `core/data/src/test/kotlin/com/stash/core/data/db/dao/TrackDaoCanonicalKeysTest.kt`

### Step 1: Write the failing test

Create `TrackDaoCanonicalKeysTest.kt`:

```kotlin
package com.stash.core.data.db.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.TrackEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for [TrackDao.getLibraryCanonicalKeys] — the v0.9.20 method
 * used by [StashMixRefreshWorker]'s discovery pre-filter to drop
 * Last.fm candidates that would dedup to library content.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class TrackDaoCanonicalKeysTest {

    private lateinit var db: StashDatabase
    private lateinit var dao: TrackDao

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            StashDatabase::class.java,
        )
            .allowMainThreadQueries()
            .build()
        dao = db.trackDao()
    }

    @After fun tearDown() { db.close() }

    @Test fun `returns canonical keys only for downloaded tracks`() = runTest {
        dao.insert(trackEntity(id = 1L, canonicalArtist = "tame impala", canonicalTitle = "borderline", isDownloaded = true))
        dao.insert(trackEntity(id = 2L, canonicalArtist = "tame impala", canonicalTitle = "the less i know the better", isDownloaded = true))
        dao.insert(trackEntity(id = 3L, canonicalArtist = "stub artist", canonicalTitle = "stub title", isDownloaded = false))

        val result = dao.getLibraryCanonicalKeys()

        assertEquals(setOf("tame impala|borderline", "tame impala|the less i know the better"), result.toSet())
        assertFalse("stub track must be excluded", "stub artist|stub title" in result)
    }

    @Test fun `returns DISTINCT keys — duplicate canonical-pairs collapse to one row`() = runTest {
        // Two tracks with identical canonical-keys (e.g. same song from two sources).
        dao.insert(trackEntity(id = 1L, canonicalArtist = "kanye west", canonicalTitle = "runaway", isDownloaded = true))
        dao.insert(trackEntity(id = 2L, canonicalArtist = "kanye west", canonicalTitle = "runaway", isDownloaded = true))

        val result = dao.getLibraryCanonicalKeys()

        assertEquals(listOf("kanye west|runaway"), result)
    }

    @Test fun `empty library returns empty list`() = runTest {
        val result = dao.getLibraryCanonicalKeys()
        assertEquals(emptyList<String>(), result)
    }

    private fun trackEntity(
        id: Long,
        canonicalArtist: String,
        canonicalTitle: String,
        isDownloaded: Boolean,
    ) = TrackEntity(
        id = id,
        title = "Title $id",
        artist = "Artist $id",
        canonicalTitle = canonicalTitle,
        canonicalArtist = canonicalArtist,
        isDownloaded = isDownloaded,
    )
}
```

### Step 2: Run tests — expect compile error

```bash
./gradlew :core:data:testDebugUnitTest --tests "com.stash.core.data.db.dao.TrackDaoCanonicalKeysTest"
```

Expected: compile error — `getLibraryCanonicalKeys` doesn't exist.

### Step 3: Implement the DAO method

In `TrackDao.kt`, add (sensible spot: alongside `findDownloadedByCanonical` at line 1084, since they're related lookups):

```kotlin
/**
 * Returns the canonical-key set ("$canonicalArtist|$canonicalTitle")
 * for every downloaded track. Used by [com.stash.core.data.sync.workers.StashMixRefreshWorker]'s
 * discovery pre-filter to drop Last.fm candidates that would dedup
 * to library content downstream — keeping discovery_queue PENDING
 * rows representative of genuinely-new music instead of "rediscovery"
 * hits.
 *
 * `is_downloaded = 1` restricts to playable content. Stub TrackEntities
 * (created by StashDiscoveryWorker before their files land) are excluded
 * so we don't double-filter against in-flight discoveries.
 */
@Query(
    """
    SELECT DISTINCT (canonical_artist || '|' || canonical_title) AS k
    FROM tracks
    WHERE is_downloaded = 1
    """
)
suspend fun getLibraryCanonicalKeys(): List<String>
```

### Step 4: Run tests — expect 3/3 pass

```bash
./gradlew :core:data:testDebugUnitTest --tests "com.stash.core.data.db.dao.TrackDaoCanonicalKeysTest"
```

Expected: 3/3 pass.

### Step 5: Run the :core:data full sweep

```bash
./gradlew :core:data:testDebugUnitTest
```

Expected: BUILD SUCCESSFUL.

### Step 6: Commit

```bash
git add core/data/src/main/kotlin/com/stash/core/data/db/dao/TrackDao.kt \
        core/data/src/test/kotlin/com/stash/core/data/db/dao/TrackDaoCanonicalKeysTest.kt
git commit -m "feat(prefs): TrackDao.getLibraryCanonicalKeys for discovery pre-filter

Returns the DISTINCT (canonical_artist || '|' || canonical_title)
set across all downloaded tracks. The discovery seed pre-filter
(coming next) uses this to drop Last.fm candidates that would
canonical-dedup to library content — moving the filter earlier so
mix discovery slots actually surface new music instead of
'rediscovery' hits.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: `TrackSkipEventDao.getEarlySkipBannedCanonicalKeys`

**Files:**
- Modify: `core/data/src/main/kotlin/com/stash/core/data/db/dao/TrackSkipEventDao.kt`
- Create: `core/data/src/test/kotlin/com/stash/core/data/db/dao/TrackSkipEventDaoBanListTest.kt`

### Step 1: Write the failing test

Create `TrackSkipEventDaoBanListTest.kt`:

```kotlin
package com.stash.core.data.db.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.TrackEntity
import com.stash.core.data.db.entity.TrackSkipEventEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * Tests for [TrackSkipEventDao.getEarlySkipBannedCanonicalKeys] — the
 * v0.9.20 method used by [StashMixRefreshWorker]'s discovery pre-filter
 * to ban tracks the user has repeatedly rejected from future discovery.
 *
 * "Early skip": skip event with position_ms <= maxPositionMs (default
 * 30_000ms in the worker — first 30 seconds of playback). Skips later
 * in the track ("done listening, moving on") don't count as rejection.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class TrackSkipEventDaoBanListTest {

    private lateinit var db: StashDatabase
    private lateinit var dao: TrackSkipEventDao
    private lateinit var trackDao: TrackDao

    @Before fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            StashDatabase::class.java,
        )
            .allowMainThreadQueries()
            .build()
        dao = db.trackSkipEventDao()
        trackDao = db.trackDao()
    }

    @After fun tearDown() { db.close() }

    @Test fun `bans canonical key with 3+ early skips inside time window`() = runTest {
        trackDao.insert(track(id = 1L, canonicalArtist = "the strokes", canonicalTitle = "last nite"))

        val now = System.currentTimeMillis()
        repeat(4) { i -> dao.insert(skip(trackId = 1L, positionMs = 1000L, skippedAt = now - i * 1000L)) }

        val result = dao.getEarlySkipBannedCanonicalKeys(
            minSkips = 3,
            sinceMs = now - TimeUnit.DAYS.toMillis(90),
            maxPositionMs = 30_000L,
        )

        assertEquals(listOf("the strokes|last nite"), result)
    }

    @Test fun `excludes canonical keys with skips beyond position cutoff`() = runTest {
        trackDao.insert(track(id = 1L, canonicalArtist = "arctic monkeys", canonicalTitle = "do i wanna know"))

        val now = System.currentTimeMillis()
        repeat(4) { dao.insert(skip(trackId = 1L, positionMs = 90_000L, skippedAt = now)) }   // 90s in — "done listening"

        val result = dao.getEarlySkipBannedCanonicalKeys(
            minSkips = 3,
            sinceMs = now - TimeUnit.DAYS.toMillis(90),
            maxPositionMs = 30_000L,
        )

        assertTrue("expected empty, got $result", result.isEmpty())
    }

    @Test fun `excludes canonical keys below skip count threshold`() = runTest {
        trackDao.insert(track(id = 1L, canonicalArtist = "radiohead", canonicalTitle = "creep"))

        val now = System.currentTimeMillis()
        repeat(2) { dao.insert(skip(trackId = 1L, positionMs = 1000L, skippedAt = now)) }   // only 2 skips

        val result = dao.getEarlySkipBannedCanonicalKeys(
            minSkips = 3,
            sinceMs = now - TimeUnit.DAYS.toMillis(90),
            maxPositionMs = 30_000L,
        )

        assertTrue("expected empty, got $result", result.isEmpty())
    }

    @Test fun `excludes skips outside time window`() = runTest {
        trackDao.insert(track(id = 1L, canonicalArtist = "blur", canonicalTitle = "song 2"))

        val now = System.currentTimeMillis()
        val veryOld = now - TimeUnit.DAYS.toMillis(100)
        repeat(4) { dao.insert(skip(trackId = 1L, positionMs = 1000L, skippedAt = veryOld)) }

        val result = dao.getEarlySkipBannedCanonicalKeys(
            minSkips = 3,
            sinceMs = now - TimeUnit.DAYS.toMillis(90),
            maxPositionMs = 30_000L,
        )

        assertTrue("expected empty (events outside window), got $result", result.isEmpty())
    }

    @Test fun `bans multiple distinct canonical keys when each has 3+ qualifying skips`() = runTest {
        trackDao.insert(track(id = 1L, canonicalArtist = "a", canonicalTitle = "x"))
        trackDao.insert(track(id = 2L, canonicalArtist = "b", canonicalTitle = "y"))

        val now = System.currentTimeMillis()
        repeat(3) { dao.insert(skip(trackId = 1L, positionMs = 5000L, skippedAt = now)) }
        repeat(3) { dao.insert(skip(trackId = 2L, positionMs = 5000L, skippedAt = now)) }

        val result = dao.getEarlySkipBannedCanonicalKeys(
            minSkips = 3,
            sinceMs = now - TimeUnit.DAYS.toMillis(90),
            maxPositionMs = 30_000L,
        )

        assertEquals(setOf("a|x", "b|y"), result.toSet())
    }

    private fun track(id: Long, canonicalArtist: String, canonicalTitle: String) = TrackEntity(
        id = id,
        title = "Title $id",
        artist = "Artist $id",
        canonicalTitle = canonicalTitle,
        canonicalArtist = canonicalArtist,
        isDownloaded = true,
    )

    private fun skip(trackId: Long, positionMs: Long, skippedAt: Long) = TrackSkipEventEntity(
        trackId = trackId,
        positionMs = positionMs,
        skippedAt = skippedAt,
    )
}
```

**Important:** verify the exact `TrackSkipEventEntity` constructor by reading `core/data/src/main/kotlin/com/stash/core/data/db/entity/TrackSkipEventEntity.kt` first. Some fields (e.g. `durationMs`, `source`, etc.) may be required by the entity but not relevant to this test — use default values or whatever the entity demands.

### Step 2: Run tests — expect compile error

```bash
./gradlew :core:data:testDebugUnitTest --tests "com.stash.core.data.db.dao.TrackSkipEventDaoBanListTest"
```

Expected: compile error — `getEarlySkipBannedCanonicalKeys` doesn't exist.

### Step 3: Implement the DAO method

In `TrackSkipEventDao.kt`, add alongside the existing queries:

```kotlin
/**
 * Returns canonical-key set ("$canonicalArtist|$canonicalTitle") for
 * tracks that have been "early-skipped" at least [minSkips] times since
 * [sinceMs], where "early" means within the first [maxPositionMs]
 * milliseconds of playback. Used by [com.stash.core.data.sync.workers.StashMixRefreshWorker]'s
 * discovery pre-filter to ban candidates the user has repeatedly
 * rejected.
 *
 * Position-aware: a skip 90% of the way through a song is "finished
 * listening, moving on," not a verdict. Only skips in the first
 * [maxPositionMs] count.
 *
 * Joins to `tracks` to look up the canonical key. Tracks deleted from
 * the library are naturally excluded (INNER JOIN) — acceptable; if a
 * track is gone we don't need to ban it from future re-discovery.
 */
@Query(
    """
    SELECT (t.canonical_artist || '|' || t.canonical_title) AS k
    FROM track_skip_events s
    INNER JOIN tracks t ON t.id = s.track_id
    WHERE s.skipped_at >= :sinceMs
      AND s.position_ms <= :maxPositionMs
    GROUP BY k
    HAVING COUNT(*) >= :minSkips
    """
)
suspend fun getEarlySkipBannedCanonicalKeys(
    minSkips: Int,
    sinceMs: Long,
    maxPositionMs: Long,
): List<String>
```

### Step 4: Run tests — expect 5/5 pass

```bash
./gradlew :core:data:testDebugUnitTest --tests "com.stash.core.data.db.dao.TrackSkipEventDaoBanListTest"
```

Expected: 5/5 pass.

### Step 5: Run the :core:data full sweep

```bash
./gradlew :core:data:testDebugUnitTest
```

Expected: BUILD SUCCESSFUL.

### Step 6: Commit

```bash
git add core/data/src/main/kotlin/com/stash/core/data/db/dao/TrackSkipEventDao.kt \
        core/data/src/test/kotlin/com/stash/core/data/db/dao/TrackSkipEventDaoBanListTest.kt
git commit -m "feat(prefs): TrackSkipEventDao.getEarlySkipBannedCanonicalKeys

Position-aware skip-count GROUP BY canonical-key query. Tracks the
user has skipped 3+ times within the first 30 seconds of playback
across the last 90 days get their canonical keys returned —
StashMixRefreshWorker's discovery pre-filter (coming next) uses
this to ban repeatedly-rejected tracks from future candidate pools.

Position filter matters: a skip 80% through a song is 'done
listening,' not a verdict — only early skips count as rejection.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: `StashMixRefreshWorker.queueDiscoveryForRecipe` filter + fallback

**Files:**
- Modify: `core/data/src/main/kotlin/com/stash/core/data/sync/workers/StashMixRefreshWorker.kt`
- Create: `core/data/src/test/kotlin/com/stash/core/data/sync/workers/StashMixRefreshWorkerSeedFilterTest.kt`

This is the largest change in the PR. Bundles:

1. Constructor injection: `TrackSkipEventDao`, `TrackMatcher`.
2. New constants in the companion.
3. Modified `queueDiscoveryForRecipe`: load filter sets, apply, fall back to TAG_GRAPH with timeout if below floor.
4. New private `canonicalKey(artist, title)` helper.
5. New MockK test class for the filter + fallback behavior.

### Step 1: Read the current `queueDiscoveryForRecipe`

Open `StashMixRefreshWorker.kt`. Find the function by name. Note its current shape — typically:

```kotlin
private suspend fun queueDiscoveryForRecipe(
    recipe: StashMixRecipeEntity,
    personas: LastFmPersonas,
) {
    val strategy = MixSeedStrategy.fromStored(recipe.seedStrategy)
    if (strategy == MixSeedStrategy.NONE) return

    val since = System.currentTimeMillis() - AFFINITY_LOOKBACK_DAYS * ...
    val seedArtists = ...
    val seedTracks = ...
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

Locate it; confirm shape. Also confirm `trackDao` is in the constructor (it is — PR 4 era).

### Step 2: Write the failing MockK test

Create `core/data/src/test/kotlin/com/stash/core/data/sync/workers/StashMixRefreshWorkerSeedFilterTest.kt`:

```kotlin
package com.stash.core.data.sync.workers

import android.content.Context
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.stash.core.data.blocklist.BlocklistGuard
import com.stash.core.data.db.dao.DiscoveryQueueDao
import com.stash.core.data.db.dao.ListeningEventDao
import com.stash.core.data.db.dao.PlaylistDao
import com.stash.core.data.db.dao.StashMixRecipeDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.dao.TrackSkipEventDao
import com.stash.core.data.db.entity.StashMixRecipeEntity
import com.stash.core.data.lastfm.LastFmApiClient
import com.stash.core.data.lastfm.LastFmCredentials
import com.stash.core.data.lastfm.LastFmSessionPreference
import com.stash.core.data.mix.MixGenerator
import com.stash.core.data.mix.MixSeedGenerator
import com.stash.core.data.mix.MixSeedStrategy
import com.stash.core.data.sync.TrackMatcher
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * MockK tests for [StashMixRefreshWorker]'s v0.9.20 discovery pre-filter:
 *  - Filter Last.fm candidates against library canonical keys.
 *  - Filter against early-skip-banned canonical keys.
 *  - Fall back to TAG_GRAPH when the filtered pool is below floor.
 *
 * Verifies what gets passed to mixGenerator.queueDiscoveryCandidates,
 * which is the boundary where filtered candidates enter discovery_queue.
 */
class StashMixRefreshWorkerSeedFilterTest {

    private val appContext: Context = mockk(relaxed = true)
    private val recipeDao: StashMixRecipeDao = mockk(relaxed = true)
    private val playlistDao: PlaylistDao = mockk(relaxed = true)
    private val discoveryQueueDao: DiscoveryQueueDao = mockk(relaxed = true)
    private val listeningEventDao: ListeningEventDao = mockk(relaxed = true)
    private val trackDao: TrackDao = mockk(relaxed = true)
    private val mixGenerator: MixGenerator = mockk(relaxed = true)
    private val seedGenerator: MixSeedGenerator = mockk()
    private val lastFmApiClient: LastFmApiClient = mockk(relaxed = true)
    private val lastFmCredentials: LastFmCredentials = mockk {
        coEvery { isConfigured } returns true   // gate the discovery branch on
    }
    private val sessionPreference: LastFmSessionPreference = mockk {
        coEvery { session } returns flowOf(null)
    }
    private val blocklistGuard: BlocklistGuard = mockk(relaxed = true)
    private val trackSkipEventDao: TrackSkipEventDao = mockk(relaxed = true)
    private val trackMatcher: TrackMatcher = mockk()

    private fun newWorker(recipeId: Long): StashMixRefreshWorker {
        val params: WorkerParameters = mockk(relaxed = true) {
            coEvery { inputData } returns workDataOf(
                StashMixRefreshWorker.KEY_RECIPE_ID to recipeId,
            )
        }
        return StashMixRefreshWorker(
            appContext, params,
            recipeDao, playlistDao, discoveryQueueDao, listeningEventDao,
            trackDao, mixGenerator, seedGenerator, lastFmApiClient,
            lastFmCredentials, sessionPreference, blocklistGuard,
            trackSkipEventDao, trackMatcher,
        )
    }

    @Test fun `filters library-canonical-keys + skip-banned-keys before queueing`() = runTest {
        val recipe = recipe(id = 1L, name = "Daily Discover", seedStrategy = "ARTIST_SIMILAR")
        coEvery { recipeDao.getById(1L) } returns recipe
        coEvery { recipeDao.getActive() } returns listOf(recipe)

        // Stub canonicalArtist/canonicalTitle as identity-lowercase for predictability
        coEvery { trackMatcher.canonicalArtist(any()) } answers { firstArg<String>().lowercase() }
        coEvery { trackMatcher.canonicalTitle(any()) } answers { firstArg<String>().lowercase() }

        // 5 candidates: 2 match library, 1 banned, 2 keepers
        val candidates = listOf(
            candidate("In Library 1", "Track A"),
            candidate("In Library 2", "Track B"),
            candidate("Banned Artist", "Banned Song"),
            candidate("New Artist 1", "New Track 1"),
            candidate("New Artist 2", "New Track 2"),
        )
        coEvery {
            seedGenerator.generate(MixSeedStrategy.ARTIST_SIMILAR, any(), any(), any(), any())
        } returns candidates

        coEvery { trackDao.getLibraryCanonicalKeys() } returns listOf(
            "in library 1|track a",
            "in library 2|track b",
        )
        coEvery {
            trackSkipEventDao.getEarlySkipBannedCanonicalKeys(any(), any(), any())
        } returns listOf("banned artist|banned song")

        // For the test we want filtered pool ≥ floor (2 keepers + floor of 20 means fallback fires).
        // Make floor's effect irrelevant by stubbing fallback too, then assert only ARTIST_SIMILAR ran:
        coEvery {
            seedGenerator.generate(MixSeedStrategy.TAG_GRAPH, any(), any(), any(), any())
        } returns emptyList()

        // Capture what gets queued
        val queuedSlot = slot<List<MixGenerator.DiscoveryCandidate>>()
        coEvery { mixGenerator.queueDiscoveryCandidates(recipe, capture(queuedSlot)) } returns Unit

        newWorker(recipeId = 1L).doWork()

        // Should queue ONLY the 2 keepers
        val keys = queuedSlot.captured.map { "${it.artist.lowercase()}|${it.title.lowercase()}" }.toSet()
        assertEquals(setOf("new artist 1|new track 1", "new artist 2|new track 2"), keys)
    }

    @Test fun `falls back to TAG_GRAPH when filtered pool is below floor`() = runTest {
        val recipe = recipe(id = 1L, name = "Daily Discover", seedStrategy = "ARTIST_SIMILAR")
        coEvery { recipeDao.getById(1L) } returns recipe
        coEvery { recipeDao.getActive() } returns listOf(recipe)
        coEvery { trackMatcher.canonicalArtist(any()) } answers { firstArg<String>().lowercase() }
        coEvery { trackMatcher.canonicalTitle(any()) } answers { firstArg<String>().lowercase() }

        // 3 candidates, all filtered out — primary pool ends at size 0
        val primary = listOf(
            candidate("LibA", "TA"),
            candidate("LibB", "TB"),
            candidate("LibC", "TC"),
        )
        coEvery { seedGenerator.generate(MixSeedStrategy.ARTIST_SIMILAR, any(), any(), any(), any()) } returns primary
        coEvery { trackDao.getLibraryCanonicalKeys() } returns listOf("liba|ta", "libb|tb", "libc|tc")
        coEvery { trackSkipEventDao.getEarlySkipBannedCanonicalKeys(any(), any(), any()) } returns emptyList()

        // Fallback returns plenty of new content
        val fallback = (1..25).map { candidate("Fallback Artist $it", "Fallback Title $it") }
        coEvery { seedGenerator.generate(MixSeedStrategy.TAG_GRAPH, any(), any(), any(), any()) } returns fallback

        val queuedSlot = slot<List<MixGenerator.DiscoveryCandidate>>()
        coEvery { mixGenerator.queueDiscoveryCandidates(recipe, capture(queuedSlot)) } returns Unit

        newWorker(recipeId = 1L).doWork()

        // All 25 fallback candidates should be queued (none filtered by library or bans in this test)
        assertEquals(25, queuedSlot.captured.size)
        coVerify { seedGenerator.generate(MixSeedStrategy.TAG_GRAPH, any(), any(), any(), any()) }
    }

    @Test fun `does NOT queue when all candidates filter out and fallback also empty`() = runTest {
        val recipe = recipe(id = 1L, name = "Daily Discover", seedStrategy = "ARTIST_SIMILAR")
        coEvery { recipeDao.getById(1L) } returns recipe
        coEvery { recipeDao.getActive() } returns listOf(recipe)
        coEvery { trackMatcher.canonicalArtist(any()) } answers { firstArg<String>().lowercase() }
        coEvery { trackMatcher.canonicalTitle(any()) } answers { firstArg<String>().lowercase() }

        // Single candidate, filtered out
        coEvery {
            seedGenerator.generate(MixSeedStrategy.ARTIST_SIMILAR, any(), any(), any(), any())
        } returns listOf(candidate("Only", "Match"))
        coEvery { trackDao.getLibraryCanonicalKeys() } returns listOf("only|match")
        coEvery { trackSkipEventDao.getEarlySkipBannedCanonicalKeys(any(), any(), any()) } returns emptyList()

        // Fallback also produces nothing
        coEvery { seedGenerator.generate(MixSeedStrategy.TAG_GRAPH, any(), any(), any(), any()) } returns emptyList()

        newWorker(recipeId = 1L).doWork()

        coVerify(exactly = 0) { mixGenerator.queueDiscoveryCandidates(any(), any()) }
    }

    private fun recipe(id: Long, name: String, seedStrategy: String) = StashMixRecipeEntity(
        id = id, name = name,
        discoveryRatio = 0.85f, targetLength = 40,
        seedStrategy = seedStrategy,
        isBuiltin = true, isActive = true,
    )

    private fun candidate(artist: String, title: String) = MixGenerator.DiscoveryCandidate(
        artist = artist, title = title, seedArtist = "test",
    )
}
```

**Note on the `trackMatcher` mock stubs**: the production `TrackMatcher.canonicalArtist` is NOT simple lowercase — it splits on `,;&/`, sorts alphabetically, rejoins with `", "`. We deliberately stub the mock as `lowercase()` because the test is exercising the FILTER LOGIC, not canonicalization. The test verifies "if `trackMatcher.canonicalArtist(c.artist)` produces a key that's in `libraryKeys`, the candidate is filtered." The actual canonicalization is exercised by integration via the on-device test in Task 5.

**Production-correctness invariant** (the implementer must verify): the canonical form produced by `TrackMatcher.canonicalArtist(candidate.artist) + "|" + TrackMatcher.canonicalTitle(candidate.title)` MUST be the same format as `tracks.canonical_artist || '|' || tracks.canonical_title` stored by the sync writer. Since the sync writer uses this same `TrackMatcher` instance, this should hold — but if anything in the code path bypasses TrackMatcher (e.g., writes canonical fields manually), the keys won't match and the filter silently fails. **Action:** spot-check by reading one or two sync-writer call sites that populate `tracks.canonical_artist` to confirm they use `TrackMatcher.canonicalArtist(...)`. Five-minute task.

### Step 3: Run tests — expect compile errors / failures

```bash
./gradlew :core:data:testDebugUnitTest --tests "com.stash.core.data.sync.workers.StashMixRefreshWorkerSeedFilterTest"
```

Expected: compile errors — the worker constructor doesn't accept `TrackSkipEventDao` or `TrackMatcher` yet, and `getLibraryCanonicalKeys` / `getEarlySkipBannedCanonicalKeys` from Tasks 2-3 are referenced.

### Step 4: Implement the worker change

In `StashMixRefreshWorker.kt`:

**4a.** Add the new constructor parameters (alongside existing DAOs):

```kotlin
@HiltWorker
class StashMixRefreshWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val recipeDao: StashMixRecipeDao,
    // ... existing deps ...
    private val blocklistGuard: BlocklistGuard,
    private val trackSkipEventDao: TrackSkipEventDao,   // NEW
    private val trackMatcher: TrackMatcher,              // NEW
) : CoroutineWorker(appContext, params) {
```

Imports:

```kotlin
import com.stash.core.data.db.dao.TrackSkipEventDao
import com.stash.core.data.sync.TrackMatcher
import androidx.work.WorkManager   // unrelated — may already be there
import kotlinx.coroutines.withTimeout   // for the fallback timeout
```

**4b.** Add the new constants in the companion object:

```kotlin
companion object {
    // existing constants ...

    /**
     * Floor for the filtered discovery pool. Below this, the TAG_GRAPH
     * fallback fires.
     *
     * Why 20 and not the recipe's discoveryCap (e.g. 34 for an 85%-discovery
     * recipe with targetLength 40)? Downstream attrition: not every queued
     * candidate becomes a viable survivor. StashDiscoveryWorker.handle()
     * applies an additional blocklist check; some candidates fail at the
     * download stage (yt-dlp can't match, no audio available, etc.); and
     * some get canonical-deduped against tracks added to the library AFTER
     * the seed-gen filter ran. Twenty pre-filter survivors typically yields
     * ~10-15 actually-downloaded DONE rows — enough to fill a 6-slot mix
     * slot AND leave a queue buffer for tomorrow's refresh. The fallback
     * is a viability gate, not a precise capacity match.
     */
    private const val MIN_DISCOVERY_POOL_AFTER_FILTER = 20

    /** Skip-event ban threshold: this many "early" skips in the time window → ban. */
    private const val DISCOVERY_SKIP_BAN_MIN_COUNT = 3

    /** Skip-event ban time window in milliseconds. */
    private val DISCOVERY_SKIP_BAN_WINDOW_MS = TimeUnit.DAYS.toMillis(90)

    /** Skip-position cutoff: skips in the first this-many ms count as rejection. */
    private const val DISCOVERY_SKIP_BAN_MAX_POSITION_MS = 30_000L

    /** Timeout for the TAG_GRAPH fallback Last.fm call. */
    private const val SEED_FALLBACK_TIMEOUT_MS = 30_000L
}
```

**4c.** Modify `queueDiscoveryForRecipe`. Replace the section AFTER `seedGenerator.generate(...)` call with:

```kotlin
val candidates = seedGenerator.generate(
    strategy = strategy,
    seedArtists = seedArtists,
    topTags = topTags,
    seedTracks = seedTracks,
    personas = personas,
)
if (candidates.isEmpty()) return

// v0.9.20: pre-filter against library + skip-ban so discovery_queue
// PENDING rows represent genuinely-new music, not "rediscovery" hits.
val libraryKeys = trackDao.getLibraryCanonicalKeys().toHashSet()
val skipBannedKeys = trackSkipEventDao
    .getEarlySkipBannedCanonicalKeys(
        minSkips = DISCOVERY_SKIP_BAN_MIN_COUNT,
        sinceMs = System.currentTimeMillis() - DISCOVERY_SKIP_BAN_WINDOW_MS,
        maxPositionMs = DISCOVERY_SKIP_BAN_MAX_POSITION_MS,
    )
    .toHashSet()

val filtered = candidates.filter { candidate ->
    val key = canonicalKey(candidate.artist, candidate.title)
    key !in libraryKeys && key !in skipBannedKeys
}

val final = if (filtered.size >= MIN_DISCOVERY_POOL_AFTER_FILTER) {
    filtered
} else {
    // Fallback: top off with TAG_GRAPH candidates (different strategy
    // typically yields different artists, so library overlap is lower).
    // Same filters applied. withTimeout protects WorkManager's
    // 10-minute budget — mirrors the existing 30s persona-fetch timeout.
    val tagFallback = runCatching {
        withTimeout(SEED_FALLBACK_TIMEOUT_MS) {
            seedGenerator.generate(
                strategy = MixSeedStrategy.TAG_GRAPH,
                seedArtists = emptyList(),
                topTags = topTags,
                seedTracks = emptyList(),
                personas = personas,
            )
        }
    }.getOrElse {
        Log.w(TAG, "'${recipe.name}': TAG_GRAPH fallback timed out / failed", it)
        emptyList()
    }.filter { candidate ->
        val key = canonicalKey(candidate.artist, candidate.title)
        key !in libraryKeys && key !in skipBannedKeys
    }
    Log.i(
        TAG,
        "'${recipe.name}': filtered pool (${filtered.size}) below floor; " +
            "appending ${tagFallback.size} TAG_GRAPH fallback candidates",
    )
    filtered + tagFallback
}

if (final.isEmpty()) {
    Log.w(TAG, "'${recipe.name}': all candidates filtered out (library + skips); skipping queue")
    return
}

Log.i(
    TAG,
    "'${recipe.name}': ${final.size} candidates via $strategy " +
        "(${candidates.size - filtered.size} filtered as library/banned)",
)
mixGenerator.queueDiscoveryCandidates(recipe, final)
```

**4d.** Add the private helper method anywhere in the worker class:

```kotlin
/**
 * Canonical key used by the discovery pre-filter — must match the
 * format the DAOs store and return: lowercased artist + "|" + lowercased
 * title via [TrackMatcher]'s normalization (same as the sync writer
 * uses to populate tracks.canonical_artist / canonical_title).
 */
private fun canonicalKey(artist: String, title: String): String =
    "${trackMatcher.canonicalArtist(artist)}|${trackMatcher.canonicalTitle(title)}"
```

### Step 5: Run the new MockK test

```bash
./gradlew :core:data:testDebugUnitTest --tests "com.stash.core.data.sync.workers.StashMixRefreshWorkerSeedFilterTest"
```

Expected: 3/3 pass.

### Step 6: Update existing PR 4 worker tests for the new constructor

The constructor expanded from 13 → 15 args (added `trackSkipEventDao` + `trackMatcher`). Two existing test files have `newWorker()` fixtures that hand-construct `StashMixRefreshWorker`. **They WILL fail to compile** until updated:

1. `core/data/src/test/kotlin/com/stash/core/data/sync/workers/StashMixRefreshWorkerDedupTest.kt` (PR 4 Task 4)
2. `core/data/src/test/kotlin/com/stash/core/data/sync/workers/StashMixRefreshWorkerPerRecipeDedupTest.kt` (PR 4 Task 1)

In each, find the `newWorker()` helper (around lines 54-67 in each file) and add two fields + pass them to the constructor:

```kotlin
// Add to the field declarations:
private val trackSkipEventDao: TrackSkipEventDao = mockk(relaxed = true)
private val trackMatcher: TrackMatcher = mockk(relaxed = true)

// Update the StashMixRefreshWorker(...) constructor call to pass them
// as the last two args (mirror the order in the production constructor).
```

Add imports:

```kotlin
import com.stash.core.data.db.dao.TrackSkipEventDao
import com.stash.core.data.sync.TrackMatcher
```

Run both existing tests after updating:

```bash
./gradlew :core:data:testDebugUnitTest --tests "com.stash.core.data.sync.workers.StashMixRefreshWorkerDedupTest"
./gradlew :core:data:testDebugUnitTest --tests "com.stash.core.data.sync.workers.StashMixRefreshWorkerPerRecipeDedupTest"
```

Expected: both pass.

### Step 7: Run the full :core:data module test sweep

```bash
./gradlew :core:data:testDebugUnitTest
```

Expected: BUILD SUCCESSFUL.

### Step 8: Build the :app module (Hilt resolution check)

```bash
./gradlew :app:assembleDebug
```

Expected: BUILD SUCCESSFUL.

### Step 9: Commit

```bash
git add core/data/src/main/kotlin/com/stash/core/data/sync/workers/StashMixRefreshWorker.kt \
        core/data/src/test/kotlin/com/stash/core/data/sync/workers/StashMixRefreshWorkerSeedFilterTest.kt
# Test fixtures for the constructor-arity change (both files must be updated):
git add core/data/src/test/kotlin/com/stash/core/data/sync/workers/StashMixRefreshWorkerDedupTest.kt
git add core/data/src/test/kotlin/com/stash/core/data/sync/workers/StashMixRefreshWorkerPerRecipeDedupTest.kt
git commit -m "feat(mix): pre-filter discovery candidates against library + skip-bans

StashMixRefreshWorker.queueDiscoveryForRecipe now drops Last.fm
candidates whose canonical artist+title key matches existing library
tracks OR matches the early-skip-ban list (3+ skips in first 30s,
last 90 days). Filter applied at the seed-generator boundary so
discovery_queue PENDING rows are guaranteed to be genuinely-new
music — not the canonical-dedup 'rediscovery' hits that were
flooding mixes with library content.

When the filtered pool falls below 20 candidates, fall back to
TAG_GRAPH seed strategy (different artist surface) with a 30s
timeout so the worker stays within WorkManager's budget.

Constructor expands to inject TrackSkipEventDao + TrackMatcher.
Existing tests updated to pass relaxed mocks for the new deps.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 5: Build APK + install + on-device verification

**Files:** none — verification task.

### Step 1: Full release build

```bash
./gradlew :app:assembleRelease
```

Expected: BUILD SUCCESSFUL. (Re-run `:app:packageRelease` if transient Windows file-lock occurs.)

### Step 2: Install over the existing release app

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

Expected: `Success`.

### Step 3: Cold-start the app

```bash
adb shell monkey -p com.stash.app -c android.intent.category.LAUNCHER 1
```

### Step 4: Confirm the orphan-drain still fires (PR 5 sanity check)

```bash
adb logcat -d -t 500 | grep -E "DiscoveryDownload"
```

Expected: `DiscoveryDownload: draining N discovery download(s)` (any N).

### Step 5: Trigger a manual refresh and verify the pre-filter signal in logcat

On the Pixel, long-press Daily Discover → "Refresh this mix."

Tail logcat:

```bash
adb logcat -t 500 | grep -iE "stashmixrefresh|stashdiscovery|discoverydownload"
```

Expected logs within ~30 seconds:

- `StashMixRefresh: refreshing 1 Stash Mix(es) (single: Daily Discover)` — existing
- `StashMixRefresh: 'Daily Discover': N candidates via ARTIST_SIMILAR (M filtered as library/banned)` — **NEW in PR 6**; M should be substantial (likely >50% of N for a large library)
- (Optional, only if filtered pool < 20) `'Daily Discover': filtered pool (X) below floor; appending Y TAG_GRAPH fallback candidates`
- `WM-WorkerWrapper: Starting work for ...StashDiscoveryWorker` — PR 5 chain
- `StashDiscovery: ...` per-recipe processing
- `WM-WorkerWrapper: Starting work for ...DiscoveryDownloadWorker` — PR 5 chain
- `DiscoveryDownload: draining N discovery download(s)`

### Step 6: Verify mix content quality

After the download chain completes (foreground service notification dismisses), open Daily Discover. Sample 10 random tracks — verify by name/artist that you don't recognize them from your existing library. Tap a few to confirm they play.

Repeat for Deep Cuts and First Listen.

### Step 7: Verify skip-ban semantic (optional smoke test)

On the Pixel: tap a recommended track in Daily Discover, immediately skip it (within 5 seconds). Repeat that exact track 3 times across the next refresh cycles. After the 3rd skip, refresh Daily Discover again. The skipped track should NOT reappear in the mix (or in any other mix) for 90 days.

This test is slow to fully validate end-to-end. The DAO test (Task 3) covers the unit behavior; the on-device test is just confirming the worker actually calls the DAO and uses the result.

### Step 8: Report back

When all manual checks pass, summarize:
- Commits that landed
- APK installed
- Logcat evidence (the pre-filter signal in particular: `(M filtered as library/banned)`)
- Manual content-quality checks (mostly new content; tracks play)
- Any deferred or skipped items

Do NOT push, do NOT tag.

---

## What's intentionally not in this plan

YAGNI:

- **No "un-ban" UI affordance.** Phase 3 work. Skip events age out of the 90-day window naturally.
- **No tunable thresholds in app settings.** Hardcoded for now; can extract later if signal is off.
- **No retroactive cleanup of pre-PR-6 `discovery_queue` rows.** New rows post-PR-6 are clean; old rows trim naturally as materializeMix re-links the discoveryCap newest.
- **No new schema migration.** All changes use existing tables.
- **No DataStore corruption-handler audit beyond what the hotfix already did.** Separate concern.
