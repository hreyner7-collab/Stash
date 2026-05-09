# Discovery Survivor Cap — v0.9.19 Follow-Up Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Cap `StashMixRefreshWorker.materializeMix`'s discovery-survivor re-link at `targetLength * discoveryRatio` (rounded), ordered newest-DONE first. Bundles into the existing v0.9.19 ship as a follow-up commit on `feat/first-listen-tag-fallback` so the same release contains both halves of the mix-sizing fix.

**Architecture:** Single-DAO-query change plus a single call-site math/parameter wire-up in the worker. `DiscoveryQueueDao.getDoneTrackIdsForRecipe` gains a `limit: Int` parameter and an `ORDER BY completed_at DESC LIMIT :limit` clause; `materializeMix` computes `discoveryCap = (recipe.targetLength * recipe.discoveryRatio).roundToInt().coerceAtLeast(0)` and passes it. Library shortfall-fill in `MixGenerator` is intentionally untouched (per spec's path A).

**Tech Stack:** Kotlin / Hilt / Room (no schema change, schema stays at v22) / coroutines. Tests use plain JUnit4 + Robolectric + in-memory Room (matches `DownloadQueueDaoDeferredTest` precedent in the same module).

**Spec:** [docs/superpowers/specs/2026-05-08-first-listen-tag-fallback-design.md](../specs/2026-05-08-first-listen-tag-fallback-design.md) — see the **"v0.9.19 follow-up: discovery survivor cap"** section.

---

## File Map

### Created

- `core/data/src/test/kotlin/com/stash/core/data/db/dao/DiscoveryQueueDaoCapTest.kt` — first test class for `DiscoveryQueueDao`; covers the 5-test matrix from the spec.

### Modified

- `core/data/src/main/kotlin/com/stash/core/data/db/dao/DiscoveryQueueDao.kt` — `getDoneTrackIdsForRecipe` signature change + `ORDER BY` + `LIMIT`.
- `core/data/src/main/kotlin/com/stash/core/data/sync/workers/StashMixRefreshWorker.kt` — call-site cap computation in `materializeMix`.

### NOT modified (per spec path A, explicit non-goals)

- `core/data/src/main/kotlin/com/stash/core/data/mix/MixGenerator.kt` — the library shortfall-fill at lines 192-197 stays as-is.
- `app/build.gradle.kts` — `versionCode` 57, `versionName` "0.9.19" already bumped by commit `ba21749`. NO new bump.

---

## Conventions

- **TDD throughout.** Failing tests → run them (verify the failure mode) → minimal implementation → run them (verify pass) → commit.
- **One commit per task.** Match the spec'd commit message byte-for-byte.
- **Co-Authored-By trailer** on every commit: `Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>`.
- **No scope creep.** Don't touch `MixGenerator`. Don't add a periodic sweep for old DONE rows. Don't touch other DAO methods.
- **Pre-existing master test failures** (`YtLibraryCanonicalizerTest.OMV...`, `PlaylistTypeTest.enum...`) still apply. Ignore them.
- **No push or tag.** The user explicitly defers the ship decision until on-device verification.

---

## Worktree (already exists)

The worktree at `.worktrees/first-listen-tag-fallback` is set up from earlier v0.9.19 work. Branch `feat/first-listen-tag-fallback` currently has 4 commits since master:

```
b56b239 docs(spec): amend v0.9.19 spec with discovery-survivor cap fix
ba21749 chore: bump versionCode 56->57, versionName 0.9.18->0.9.19
e9764c5 feat(mix): library-histogram fallback for First Listen tag seed
a9864bb docs(plan): v0.9.19 First Listen tag-fallback implementation plan
```

(`b56b239` is on master only and hasn't been merged into the worktree yet — pull master into the worktree before starting.)

cd into the worktree:

```bash
cd /c/Users/theno/Projects/MP3APK/.worktrees/first-listen-tag-fallback
```

Pull the spec amendment from master:

```bash
git pull --ff-only origin master
# OR if you have local-only spec commits to integrate:
git rebase master
```

(Either works since the worktree's branch `feat/first-listen-tag-fallback` is local-only. The pull/rebase brings the spec amendment into the worktree's checkout for plan-task reference.)

---

## Task 1: DAO change + 5 tests + worker call-site update

**Why this is one task:** the DAO change and the worker change are tightly coupled — a `getDoneTrackIdsForRecipe` signature change with no caller is a compile error. Single TDD cycle, single commit. The 5 DAO tests verify the query in isolation; the worker call-site change is a 4-line edit verified by the existing `:core:data` test sweep continuing green.

**Files:**
- Modify: `core/data/src/main/kotlin/com/stash/core/data/db/dao/DiscoveryQueueDao.kt`
- Modify: `core/data/src/main/kotlin/com/stash/core/data/sync/workers/StashMixRefreshWorker.kt`
- Create: `core/data/src/test/kotlin/com/stash/core/data/db/dao/DiscoveryQueueDaoCapTest.kt`

- [ ] **Step 1: Reconnaissance — confirm existing surfaces**

Three things to verify by reading source:

1. `DiscoveryQueueDao.getDoneTrackIdsForRecipe(recipeId: Long): List<Long>` exists today with a `@Query` body of:

```sql
SELECT track_id FROM discovery_queue
WHERE dq.recipe_id = :recipeId
  AND dq.status = 'DONE'
  AND dq.track_id IS NOT NULL
```

(The `dq` alias may be present or absent — verify the literal current SQL before writing the patch.)

2. `DiscoveryQueueEntity` has `completedAt: Long?` mapped to a `completed_at` column. Confirm via `core/data/src/main/kotlin/com/stash/core/data/db/entity/DiscoveryQueueEntity.kt` lines 56-61. The column is nullable in the schema, but the project invariant is that any row with `status = 'DONE'` has a non-null `completed_at` (set by the worker on the DONE transition).

3. `DownloadQueueDaoDeferredTest` at `core/data/src/test/kotlin/com/stash/core/data/db/dao/DownloadQueueDaoDeferredTest.kt` is the canonical pattern for a Robolectric + in-memory Room DAO test in this module. Read it first to copy the framework setup (`@RunWith(RobolectricTestRunner::class)`, `Room.inMemoryDatabaseBuilder`, `@Before` / `@After`, FK seeding via parent `tracks` row).

4. Confirm `DiscoveryQueueDao.insert(entity: DiscoveryQueueEntity)` (or equivalent insertion method — `insertAll`, `upsert`, etc.) exists and is callable from tests. The plan's test snippets call `dao.insert(...)`; if the actual method is named differently, swap accordingly.

If any of these don't match, surface as `BLOCKED` rather than improvising.

- [ ] **Step 2: Write the failing tests**

`core/data/src/test/kotlin/com/stash/core/data/db/dao/DiscoveryQueueDaoCapTest.kt`:

```kotlin
package com.stash.core.data.db.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.DiscoveryQueueEntity
import com.stash.core.data.db.entity.TrackEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for [DiscoveryQueueDao.getDoneTrackIdsForRecipe] — specifically the
 * v0.9.19 follow-up cap fix that bounds the result at `:limit` ordered by
 * `completed_at DESC` so newest-DONE survivors win when materializeMix
 * trims to the recipe's discovery slot count.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class DiscoveryQueueDaoCapTest {

    private lateinit var db: StashDatabase
    private lateinit var dao: DiscoveryQueueDao

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            StashDatabase::class.java,
        )
            .allowMainThreadQueries()
            .build()
        dao = db.discoveryQueueDao()
        // Seed a few tracks for FK linkage. Mirror DownloadQueueDaoDeferredTest's
        // helper if it exposes one; otherwise inline minimal-required fields.
        // ... seedTracks(ids = listOf(1, 2, 3, 4, 5))
    }

    @After fun tearDown() { db.close() }

    @Test fun `respects limit when DONE rows exceed it`() = runTest {
        val recipeId = 1L
        // Insert 5 DONE rows with track_id 1..5 and increasing completedAt.
        for (i in 1..5) {
            dao.insert(doneRow(recipeId, trackId = i.toLong(), completedAt = 1000L * i))
        }

        val result = dao.getDoneTrackIdsForRecipe(recipeId, limit = 3)

        assertEquals(3, result.size)
    }

    @Test fun `orders by completed_at DESC (newest-DONE first)`() = runTest {
        val recipeId = 1L
        // Insert older row first, newer row second, but verify result is newer-first.
        dao.insert(doneRow(recipeId, trackId = 1L, completedAt = 1000L))   // older
        dao.insert(doneRow(recipeId, trackId = 2L, completedAt = 5000L))   // newer

        val result = dao.getDoneTrackIdsForRecipe(recipeId, limit = 99)

        assertEquals(listOf(2L, 1L), result)
    }

    @Test fun `filters status to DONE only`() = runTest {
        val recipeId = 1L
        dao.insert(doneRow(recipeId, trackId = 1L, completedAt = 1000L))
        dao.insert(pendingRow(recipeId, trackId = 2L))   // PENDING — must NOT appear
        // SEARCHED, DOWNLOADING, FAILED row variants would also be excluded;
        // PENDING covers the most likely accidental-leak case.

        val result = dao.getDoneTrackIdsForRecipe(recipeId, limit = 99)

        assertEquals(listOf(1L), result)
    }

    @Test fun `excludes rows with null track_id even when status is DONE`() = runTest {
        // Transient state: a worker may set status=DONE just before linking the
        // canonical track_id. Such a row must NOT consume a cap slot.
        val recipeId = 1L
        dao.insert(doneRow(recipeId, trackId = 1L, completedAt = 1000L))
        dao.insert(doneRow(recipeId, trackId = null, completedAt = 2000L))

        val result = dao.getDoneTrackIdsForRecipe(recipeId, limit = 99)

        assertEquals(listOf(1L), result)
    }

    @Test fun `limit zero returns empty list`() = runTest {
        // Deep Cuts hits this on every refresh (discoveryRatio = 0.0).
        val recipeId = 1L
        dao.insert(doneRow(recipeId, trackId = 1L, completedAt = 1000L))

        val result = dao.getDoneTrackIdsForRecipe(recipeId, limit = 0)

        assertTrue("expected empty, got $result", result.isEmpty())
    }

    private fun doneRow(recipeId: Long, trackId: Long?, completedAt: Long?) = DiscoveryQueueEntity(
        recipeId = recipeId,
        artist = "Artist",
        title = "Title $trackId",
        seedArtist = "Seed",
        status = DiscoveryQueueEntity.STATUS_DONE,
        trackId = trackId,
        queuedAt = 0L,
        completedAt = completedAt,
        errorMessage = null,
    )

    private fun pendingRow(recipeId: Long, trackId: Long?) = DiscoveryQueueEntity(
        recipeId = recipeId,
        artist = "Artist",
        title = "Title $trackId",
        seedArtist = "Seed",
        status = DiscoveryQueueEntity.STATUS_PENDING,
        trackId = trackId,
        queuedAt = 0L,
        completedAt = null,
        errorMessage = null,
    )

    // FK-seeding helper — flesh out from DownloadQueueDaoDeferredTest's pattern.
    // If DiscoveryQueueEntity has no `@ForeignKey` to `tracks`, the seedTracks
    // helper isn't required; verify by reading the entity's @Entity annotation.
    private suspend fun seedTracks(ids: List<Long>) {
        // ... use TrackDao + TrackEntity if FK is enforced.
    }
}
```

NOTE: Whether `DiscoveryQueueEntity` has a foreign key to `tracks(id)` determines if `seedTracks` is required. Read the `@Entity(... foreignKeys = ...)` annotation; if no FK, drop the helper. The plan's safest assumption is that there IS one (matching the `download_queue → tracks` FK precedent), but verify.

NOTE: Step 5 of the spec's test matrix ("filters out null track_id even when status is DONE") is implementation-dependent — the spec describes this as defensive coverage in case a future bug leaves a DONE row temporarily without `track_id`. Today the production worker may set DONE atomically with `track_id`; either way, the test pins the existing `AND track_id IS NOT NULL` clause's behavior so a future query refactor can't accidentally drop it.

- [ ] **Step 3: Verify all 5 tests fail (and fail for the right reason)**

```bash
./gradlew :core:data:testDebugUnitTest --tests "com.stash.core.data.db.dao.DiscoveryQueueDaoCapTest"
```

Expected results before implementation:

- All 5 tests fail to compile because `getDoneTrackIdsForRecipe` doesn't accept a `limit` parameter today.
- The compile error is the `Unresolved reference 'limit'` (or "expected 1 arg, got 2") form.

If the failure is anything else (e.g., entity constructor mismatch, FK violation), fix the test wiring first.

- [ ] **Step 4: Modify the DAO**

Open `core/data/src/main/kotlin/com/stash/core/data/db/dao/DiscoveryQueueDao.kt`. Find `getDoneTrackIdsForRecipe`. Replace its `@Query` and signature with:

```kotlin
@Query("""
    SELECT track_id FROM discovery_queue
    WHERE recipe_id = :recipeId
      AND status = 'DONE'
      AND track_id IS NOT NULL
    ORDER BY completed_at DESC
    LIMIT :limit
""")
suspend fun getDoneTrackIdsForRecipe(recipeId: Long, limit: Int): List<Long>
```

Notes on the SQL:
- `ORDER BY completed_at DESC` puts non-null timestamps in descending order. SQLite considers NULL "smaller than any other value", so under `DESC` NULLs naturally fall to the end — they'd only appear in the result if `limit` is large enough to reach them, and the `track_id IS NOT NULL` clause already excludes the most-likely-null-completedAt case (transient pre-link DONE rows).
- The `WHERE` clause matches the existing query verbatim minus the `dq.` table alias if present. Strip the alias if it exists; the FROM clause has only one table so the alias is unnecessary.

- [ ] **Step 5: Update the call site in `StashMixRefreshWorker.materializeMix`**

Open `core/data/src/main/kotlin/com/stash/core/data/sync/workers/StashMixRefreshWorker.kt`. Find the existing `materializeMix` block around line 308-318:

```kotlin
val candidateIds = discoveryQueueDao
    .getDoneTrackIdsForRecipe(recipe.id)
    .filter { it !in librarySet }
```

Replace with:

```kotlin
// v0.9.19 follow-up: cap discovery survivors at the recipe's stated
// slot count (targetLength * discoveryRatio). DAO query orders by
// completed_at DESC so newest-DONE survivors win when we have to cut.
// Library shortfall-fill in MixGenerator is intentionally untouched —
// total playlist size for Daily Discover settles at up-to-50 (library)
// + up-to-20 (discovery) = ≤70, replacing the previous unbounded growth.
val discoveryCap = (recipe.targetLength * recipe.discoveryRatio)
    .roundToInt()
    .coerceAtLeast(0)
val candidateIds = discoveryQueueDao
    .getDoneTrackIdsForRecipe(recipe.id, limit = discoveryCap)
    .filter { it !in librarySet }
```

Imports to add at the top of the file (if not already present):

```kotlin
import kotlin.math.roundToInt
```

Note on `roundToInt`: `recipe.targetLength` is `Int` per `StashMixRecipeEntity`, `recipe.discoveryRatio` is `Float` per the same entity. The multiplication produces a `Float`. `Float.roundToInt()` is the standard library extension; importing `kotlin.math.roundToInt` resolves it unambiguously.

Verify by reading `core/data/src/main/kotlin/com/stash/core/data/db/entity/StashMixRecipeEntity.kt` — both column types should already be there. If `discoveryRatio` is `Double` for some reason, the same `roundToInt` works on `Double` too.

- [ ] **Step 6: Verify all 5 tests pass**

```bash
./gradlew :core:data:testDebugUnitTest --tests "com.stash.core.data.db.dao.DiscoveryQueueDaoCapTest"
```

Expected: BUILD SUCCESSFUL with 5 tests passing.

- [ ] **Step 7: Run the broader `:core:data` test sweep to catch regressions**

```bash
./gradlew :core:data:testDebugUnitTest
```

Expected: BUILD SUCCESSFUL except for the known pre-existing failure: `PlaylistTypeTest.enum contains the expected set` (unrelated to this branch). If anything else fails, the cap likely broke something downstream — most likely a different caller of `getDoneTrackIdsForRecipe` that didn't get its signature updated. Search for other call sites:

```bash
grep -rn "getDoneTrackIdsForRecipe" core/ data/ feature/ app/ --include="*.kt"
```

Expected: only `StashMixRefreshWorker.kt` uses this DAO method. If any other file shows up, update its call site too.

- [ ] **Step 8: Commit**

```bash
git add core/data/src/main/kotlin/com/stash/core/data/db/dao/DiscoveryQueueDao.kt \
        core/data/src/main/kotlin/com/stash/core/data/sync/workers/StashMixRefreshWorker.kt \
        core/data/src/test/kotlin/com/stash/core/data/db/dao/DiscoveryQueueDaoCapTest.kt
git commit -m "$(cat <<'EOF'
fix(mix): cap discovery survivor re-link at targetLength * discoveryRatio

materializeMix was re-linking every DONE row in discovery_queue for a
recipe with no upper bound. Daily Discover went 50 -> 100 in one
v0.9.19 install transition because ~50 DONE survivors had accumulated
between v0.9.18 and v0.9.19 — the install-time refresh re-linked them
all on top of the 50 library tracks. First Listen would have followed
the same path once StashDiscoveryWorker resolved the histogram-fallback's
queued candidates.

DiscoveryQueueDao.getDoneTrackIdsForRecipe now takes a limit param and
orders by completed_at DESC, so newest-DONE survivors win when the cap
forces a cut. Worker computes
  discoveryCap = (recipe.targetLength * recipe.discoveryRatio)
                   .roundToInt().coerceAtLeast(0)
at the call site. Library shortfall-fill in MixGenerator is intentionally
untouched — Daily Discover settles at ≤70 (50 library + ≤20 discovery)
which replaces the previous unbounded growth. If 70 still feels long
after testing, trimming library is a v0.9.20 follow-up.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: Rebase version bump to tip + APK build + install

**Why this isn't a test-driven task:** rebase is git surgery (no behavior change, no new tests). The APK build and install are verification steps — the test sweep already passed in Task 1. The user manually smoke-tests on their device after install.

**Files:**
- None modified. Just rebase + build + install.

- [ ] **Step 1: Rebase the version-bump commit to the tip**

Currently the branch is:

```
ba21749 chore: bump versionCode 56->57, versionName 0.9.18->0.9.19  (was tip)
e9764c5 feat(mix): library-histogram fallback for First Listen tag seed
... (Task 1's new commit, e.g., 7abc123)
```

Wait — Task 1's new commit lands on top of `ba21749`, which means after Task 1 the branch order is:

```
NEW   fix(mix): cap discovery survivor re-link...    ← Task 1's commit (tip)
ba21749 chore: bump versionCode 56->57, versionName 0.9.18->0.9.19
e9764c5 feat(mix): library-histogram fallback for First Listen tag seed
```

Rebase to put `ba21749` (version bump) at the tip so the eventual tag points at the bump commit (per project memory: release notes come from the tagged commit's body).

The Bash harness in this project blocks `-i` flags on `git rebase`, so use the cherry-pick path (matches the v0.9.18 ship pattern):

```bash
git log --oneline master..HEAD                 # confirm 3 commits on top of master
git reset --hard e9764c5                       # rewind to the histogram-fallback commit
git cherry-pick <SHA-Task-1>                   # re-apply the cap-fix commit
git cherry-pick ba21749                        # re-apply the version-bump commit (now at tip)
```

The Task-1 commit and `ba21749` get new SHAs after cherry-pick (different parent), but the working-tree content is identical to before — `git diff` between pre-rebase HEAD and post-rebase HEAD should return empty.

Verify the result:

```bash
git log --oneline master..HEAD
```

Expected:

```
ba21749  chore: bump versionCode 56->57, versionName 0.9.18->0.9.19
<NEW SHA> fix(mix): cap discovery survivor re-link...
e9764c5  feat(mix): library-histogram fallback for First Listen tag seed
```

- [ ] **Step 2: Build the release APK**

```bash
./gradlew :app:assembleRelease
```

Expected: BUILD SUCCESSFUL. APK at `app/build/outputs/apk/release/app-release.apk` (~155 MB, signed with `stash-release.jks`).

If the build emits a warning about the keystore falling back to debug, it means the worktree is missing `keystore.properties` or `stash-release.jks`. Both should already be there from the v0.9.19 worktree setup; if not, copy them in:

```bash
cp /c/Users/theno/Projects/MP3APK/keystore.properties .
cp /c/Users/theno/Projects/MP3APK/stash-release.jks .
```

- [ ] **Step 3: Install over the existing v0.9.19 build on the user's Pixel**

```bash
adb devices    # confirm `1A021FDEE002RD` (or whatever) shows up as `device`
adb install -r app/build/outputs/apk/release/app-release.apk
```

Expected: `Performing Streamed Install` → `Success`. The `-r` preserves all user data — the discovery queue, listening events, downloaded tracks, prefs, etc.

If `adb devices` shows no device, surface the APK path and stop — the user will install themselves.

- [ ] **Step 4: Surface the manual smoke test for the user**

DO NOT run the smoke test yourself. Document this for the user:

> v0.9.19 build with the discovery survivor cap is now installed. To verify the cap works:
>
> 1. **Long-press Daily Discover and tap Refresh.** Wait ~15 seconds. Check the track count.
>    - Pre-fix: 100 tracks (50 library + ~50 unbounded discovery survivors).
>    - Expected post-fix: ≤70 tracks (50 library + ≤20 discovery survivors, ordered newest-DONE first).
> 2. **Long-press Deep Cuts and tap Refresh.** Wait. Check the track count.
>    - Expected: 50 tracks (library only — `discoveryRatio=0.0` makes the cap 0, no discovery survivors re-linked, no observable change).
> 3. **First Listen.** Currently 0 tracks because the histogram fallback's 300 PENDING candidates haven't been resolved by `StashDiscoveryWorker` yet. Over the next several hours / next periodic sync, expect First Listen to populate up to ≤50 tracks.
> 4. **Logcat sanity (optional).** Tail `adb logcat | grep StashMixRefresh` while triggering the refreshes; the log lines `'<recipe>': N candidates via <strategy>` should still fire. They log the count of NEW candidates queued, not the playlist size.
>
> If any of those don't match expectations, surface the symptom and we'll go back to systematic debugging.

- [ ] **Step 5: DO NOT push or tag**

The user gates push + tag. Stop here. After the smoke test passes, the user will run:

```bash
cd /c/Users/theno/Projects/MP3APK
git checkout master
git merge --ff-only feat/first-listen-tag-fallback
git tag -a v0.9.19 -m "v0.9.19 — First Listen tag-fallback + discovery survivor cap"
git push origin master && git push origin v0.9.19
```

The release workflow takes over from there.

---

## Notes for the executor

- **The DAO change is the entire feature behaviour.** Don't refactor neighboring DAO methods, don't tweak `MixGenerator`, don't add a periodic DONE-row sweep. All three are documented in the spec's Non-goals.
- **`completed_at` ordering:** SQLite's default for `ORDER BY ... DESC` puts NULL values *last* (because NULL is treated as smaller than any concrete value). The combination `WHERE … AND track_id IS NOT NULL` plus `ORDER BY completed_at DESC` plus `LIMIT :limit` means null-completedAt rows can only end up in the result if they have non-null `track_id` AND there are fewer than `limit` non-null-completed_at rows. This is acceptable degradation; documented in the spec.
- **No version bump in this commit.** The version bump (`ba21749`) ALREADY landed on the branch. Adding another bump would be wrong; the rebase in Task 2 just reorders commits so the existing bump ends up at the tip.
- **Pre-existing test failures still apply.** `YtLibraryCanonicalizerTest.OMV...` and `PlaylistTypeTest.enum...` will continue to fail in the broader sweep. Ignore them.
- **No push, no tag.** The user defers the ship decision to manual on-device verification.
