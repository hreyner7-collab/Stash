# First Listen Tag-Fallback Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a library-tag-histogram fallback to `MixGenerator.computeUserTopTags()` so the First Listen recipe (`seedStrategy = TAG_GRAPH`, `discoveryRatio = 1.0`) doesn't dead-end with an empty mix when the user's listening-affinity vector is empty.

**Architecture:** Single-function change. When `buildUserTagAffinityVector()` returns an empty map (no listening events in the 180-day window OR all listened-to tracks are unenriched / `__untaggable__`-only), fall back to `TrackTagDao.getTagHistogram()` — already-existing on-device data ordered by `track_count DESC` — filter out the `__untaggable__` sentinel, take the top N. No new DAO methods, no schema change, no new Hilt deps. Daily Discover and Deep Cuts are unaffected (they don't call `computeUserTopTags`).

**Tech Stack:** Kotlin / Hilt / Room (no schema change, schema stays at v22) / coroutines. Tests use plain JUnit4 + mockk + kotlinx-coroutines-test, matching the existing `MixSeedStrategyTest` / `UserTagAffinityTest` siblings in the same package.

**Spec:** [docs/superpowers/specs/2026-05-08-first-listen-tag-fallback-design.md](../specs/2026-05-08-first-listen-tag-fallback-design.md)

---

## File Map

### Created

- `core/data/src/test/kotlin/com/stash/core/data/mix/MixGeneratorComputeUserTopTagsTest.kt` — first test class for `MixGenerator`; covers the 6-test matrix from the spec.

### Modified

- `core/data/src/main/kotlin/com/stash/core/data/mix/MixGenerator.kt` — extend `computeUserTopTags(limit: Int)` with the histogram fallback. Updated KDoc.
- `app/build.gradle.kts` — bump `versionCode` 56 → 57, `versionName` "0.9.18" → "0.9.19".

---

## Conventions

- **TDD throughout.** Failing test → run it (verify the failure reason) → minimal implementation → run it (verify pass) → commit. Don't skip the "watch it fail" step.
- **One commit per task.** Match the spec'd commit message byte-for-byte.
- **Co-Authored-By trailer** on every commit: `Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>`.
- **No scope creep.** This plan is one function change + tests + version bump. Don't tweak `TagEnrichmentWorker`, the other recipes, or any unrelated code.
- **Pre-existing master test failures (`YtLibraryCanonicalizerTest`, `PlaylistTypeTest`)** still apply. Ignore them in test sweeps — they're unrelated.

---

## Worktree setup (do this once, before Task 1)

- [ ] **Step 1: Create the worktree from current master**

```bash
cd /c/Users/theno/Projects/MP3APK
git fetch origin
git worktree add .worktrees/first-listen-tag-fallback -b feat/first-listen-tag-fallback master
```

(`master` already includes the v0.9.19 spec/plan commits since brainstorming + writing-plans wrote them locally.)

- [ ] **Step 2: Copy `local.properties`, `keystore.properties`, and `stash-release.jks` into the worktree**

Per project memory, `git worktree add` doesn't transfer these gitignored files. Without `local.properties` Last.fm credentials show "Not configured" in debug. Without `keystore.properties` + `stash-release.jks` you can't build a release APK that installs over the existing v0.9.18 on the user's device.

```bash
cp local.properties .worktrees/first-listen-tag-fallback/local.properties
cp keystore.properties .worktrees/first-listen-tag-fallback/keystore.properties
cp stash-release.jks .worktrees/first-listen-tag-fallback/stash-release.jks
```

- [ ] **Step 3: cd into the worktree**

```bash
cd .worktrees/first-listen-tag-fallback
```

All subsequent paths are relative to the worktree root.

---

## Task 1: Add histogram fallback + 6 tests

**Why this is the whole feature in one task:** the change is one function body. The work is overwhelmingly in test design — six cases covering the affinity-path-first contract, the fallback firing conditions, the `__untaggable__` filter, the limit, and the combined filter+limit guard.

**Files:**
- Modify: `core/data/src/main/kotlin/com/stash/core/data/mix/MixGenerator.kt` — `computeUserTopTags` body + KDoc
- Create: `core/data/src/test/kotlin/com/stash/core/data/mix/MixGeneratorComputeUserTopTagsTest.kt`

- [ ] **Step 1: Reconnaissance — confirm the existing surfaces match the plan's assumptions**

Three things to verify by reading source before writing tests:

1. `MixGenerator` constructor injects `trackTagDao: TrackTagDao` (it does — line 70 area). Tests just need to mock that DAO (relaxed mocks for the other 5 deps).
2. `TrackTagDao.getTagHistogram(): List<TagCount>` exists and returns `data class TagCount(val tag: String, @ColumnInfo(name = "track_count") val trackCount: Int)`. The DAO Q already orders `ORDER BY track_count DESC`, so the SQL produces tags in descending-popularity order. The implementation just needs to filter + take.
3. `buildUserTagAffinityVector` is `private` (it is) — tests must stimulate it through public `computeUserTopTags`. We control the affinity-vector path's inputs by mocking `listeningEventDao.getPlayCountsSinceWithLatest(any())` and `trackTagDao.getByTrack(any())`.

Sibling test files for the framework precedent: `MixSeedStrategyTest.kt`, `UserTagAffinityTest.kt`. They use plain JUnit4 + `runTest` + mockk. Match that.

If any of these don't match (e.g., the DAO method got renamed, the DTO shape is different), surface as `BLOCKED` before improvising.

- [ ] **Step 2: Write the failing tests**

`core/data/src/test/kotlin/com/stash/core/data/mix/MixGeneratorComputeUserTopTagsTest.kt`:

```kotlin
package com.stash.core.data.mix

import com.stash.core.data.blocklist.BlocklistGuard
import com.stash.core.data.db.dao.DiscoveryQueueDao
import com.stash.core.data.db.dao.ListeningEventDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.dao.TrackSkipEventDao
import com.stash.core.data.db.dao.TrackTagDao
import com.stash.core.data.db.dao.TrackTagDao.TagCount
import com.stash.core.data.db.dao.PlayCountWithLatest
import com.stash.core.data.db.entity.TrackTagEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [MixGenerator.computeUserTopTags] — specifically the v0.9.19
 * library-histogram fallback path that fires when the user's
 * listening-affinity vector is empty.
 */
class MixGeneratorComputeUserTopTagsTest {

    private val trackDao: TrackDao = mockk(relaxed = true)
    private val trackTagDao: TrackTagDao = mockk()
    private val listeningEventDao: ListeningEventDao = mockk()
    private val discoveryQueueDao: DiscoveryQueueDao = mockk(relaxed = true)
    private val blocklistGuard: BlocklistGuard = mockk(relaxed = true)
    private val trackSkipEventDao: TrackSkipEventDao = mockk(relaxed = true)

    private val subject = MixGenerator(
        trackDao,
        trackTagDao,
        listeningEventDao,
        discoveryQueueDao,
        blocklistGuard,
        trackSkipEventDao,
    )

    // Helper: set up affinity-vector inputs to produce a non-empty vector.
    // One play row with one real tag is enough for compute() to yield a
    // non-empty L2-normalized result.
    private fun stubAffinityVectorNonEmpty(now: Long = System.currentTimeMillis()) {
        coEvery { listeningEventDao.getPlayCountsSinceWithLatest(any()) } returns listOf(
            PlayCountWithLatest(trackId = 1L, plays = 5, latestPlayedAt = now),
        )
        coEvery { trackTagDao.getByTrack(1L) } returns listOf(
            TrackTagEntity(trackId = 1L, tag = "indie", weight = 80f, source = "ARTIST", fetchedAt = now),
            TrackTagEntity(trackId = 1L, tag = "dream pop", weight = 50f, source = "ARTIST", fetchedAt = now),
        )
    }

    // Helper: set up affinity-vector inputs to produce an empty vector
    // (plays exist but every track is untagged or only __untaggable__).
    private fun stubAffinityVectorEmpty(now: Long = System.currentTimeMillis()) {
        coEvery { listeningEventDao.getPlayCountsSinceWithLatest(any()) } returns listOf(
            PlayCountWithLatest(trackId = 1L, plays = 5, latestPlayedAt = now),
        )
        coEvery { trackTagDao.getByTrack(1L) } returns listOf(
            TrackTagEntity(trackId = 1L, tag = "__untaggable__", weight = 0f, source = "ARTIST", fetchedAt = now),
        )
    }

    @Test fun `returns affinity-vector tags when vector is non-empty (histogram NOT consulted)`() = runTest {
        stubAffinityVectorNonEmpty()

        val result = subject.computeUserTopTags(limit = 10)

        assertTrue("expected non-empty result, got $result", result.isNotEmpty())
        assertTrue("indie should be in top tags, got $result", result.contains("indie"))
        coVerify(exactly = 0) { trackTagDao.getTagHistogram() }
    }

    @Test fun `falls back to histogram when affinity vector is empty (no listening events)`() = runTest {
        coEvery { listeningEventDao.getPlayCountsSinceWithLatest(any()) } returns emptyList()
        coEvery { trackTagDao.getTagHistogram() } returns listOf(
            TagCount(tag = "rock", trackCount = 100),
            TagCount(tag = "indie", trackCount = 80),
            TagCount(tag = "pop", trackCount = 60),
        )

        val result = subject.computeUserTopTags(limit = 10)

        assertEquals(listOf("rock", "indie", "pop"), result)
    }

    @Test fun `falls back to histogram when plays exist but all tracks are untagged`() = runTest {
        stubAffinityVectorEmpty()
        coEvery { trackTagDao.getTagHistogram() } returns listOf(
            TagCount(tag = "rock", trackCount = 100),
            TagCount(tag = "indie", trackCount = 80),
        )

        val result = subject.computeUserTopTags(limit = 10)

        assertEquals(listOf("rock", "indie"), result)
    }

    @Test fun `fallback filters out __untaggable__ sentinel`() = runTest {
        coEvery { listeningEventDao.getPlayCountsSinceWithLatest(any()) } returns emptyList()
        coEvery { trackTagDao.getTagHistogram() } returns listOf(
            TagCount(tag = "__untaggable__", trackCount = 200),  // top of histogram
            TagCount(tag = "rock", trackCount = 100),
            TagCount(tag = "indie", trackCount = 80),
        )

        val result = subject.computeUserTopTags(limit = 10)

        assertTrue("__untaggable__ must not appear, got $result", "__untaggable__" !in result)
        assertEquals(listOf("rock", "indie"), result)
    }

    @Test fun `returns empty when both affinity vector and histogram are empty`() = runTest {
        coEvery { listeningEventDao.getPlayCountsSinceWithLatest(any()) } returns emptyList()
        coEvery { trackTagDao.getTagHistogram() } returns emptyList()

        val result = subject.computeUserTopTags(limit = 10)

        assertEquals(emptyList<String>(), result)
    }

    @Test fun `respects the limit on the fallback path`() = runTest {
        coEvery { listeningEventDao.getPlayCountsSinceWithLatest(any()) } returns emptyList()
        coEvery { trackTagDao.getTagHistogram() } returns (1..15).map {
            TagCount(tag = "tag$it", trackCount = 100 - it)
        }

        val result = subject.computeUserTopTags(limit = 10)

        assertEquals(10, result.size)
        assertEquals("tag1", result.first())
        assertEquals("tag10", result.last())
    }

    @Test fun `fallback yields limit real tags even when __untaggable__ tops the histogram`() = runTest {
        // Combined-case regression guard. If the implementation ever
        // accidentally swaps to take().filter() (instead of
        // filter().take()), the sentinel consumes a result slot and
        // we'd return 9 real tags + a hole. Pin the order.
        coEvery { listeningEventDao.getPlayCountsSinceWithLatest(any()) } returns emptyList()
        coEvery { trackTagDao.getTagHistogram() } returns listOf(
            TagCount(tag = "__untaggable__", trackCount = 999),
        ) + (1..11).map { TagCount(tag = "tag$it", trackCount = 100 - it) }

        val result = subject.computeUserTopTags(limit = 10)

        assertEquals(10, result.size)
        assertTrue("sentinel must not appear, got $result", "__untaggable__" !in result)
        assertEquals("tag1", result.first())
        assertEquals("tag10", result.last())
    }
}
```

NOTE: The `PlayCountWithLatest` import path is a guess. If `:core:data` puts it elsewhere (e.g., as a nested type in `ListeningEventDao` or in a `*Dtos.kt` file), adjust the import. Find via:

```bash
grep -rn "data class PlayCountWithLatest\|data class .*PlayCount" core/data/src/main/kotlin/com/stash/core/data/db/dao/ListeningEventDao.kt core/data/src/main/kotlin/com/stash/core/data/db/ | head -5
```

NOTE: Test count is 7 (the spec listed 6 with the combined case as #6; the plan splits "respects the limit" and the combined filter+limit guard into separate tests for clarity — both still align with the spec's intent). If you'd rather merge them into one mega-test, that's fine — the test surface is what matters, not the count.

NOTE: The first test asserts `coVerify(exactly = 0) { trackTagDao.getTagHistogram() }`. This is the regression guard against "histogram is always called" — a future refactor that accidentally inverted the if/else would fail this test loudly.

- [ ] **Step 3: Verify all 7 tests fail (and fail for the right reason)**

```bash
./gradlew :core:data:testDebugUnitTest --tests "com.stash.core.data.mix.MixGeneratorComputeUserTopTagsTest"
```

Expected results before implementation:

- The first test (vector non-empty → histogram not consulted) passes already if `computeUserTopTags` already calls `getTagHistogram` zero times when the vector is non-empty. Today's implementation only calls the affinity-vector path, so this test passes "by accident" pre-fix. That's fine — the test still locks the contract going forward.
- Tests 2-7 should FAIL because today's implementation returns `emptyList()` whenever the affinity vector is empty, never calling `getTagHistogram`. The expected failure mode for each is `assertEquals(["rock", "indie", "pop"], emptyList())` or similar — i.e., the result list is empty when it should have fallback content. mockk's `coVerify { getTagHistogram() }` (in #2) would fail with `Verification failed: call 0 of 1`.

If the failure reason for any test is something other than "fallback didn't run" (e.g., `Unresolved reference 'TagCount'`, `mockk: no answer for getByTrack`), fix the test wiring before proceeding to Step 4.

- [ ] **Step 4: Implement the fallback**

Open `core/data/src/main/kotlin/com/stash/core/data/mix/MixGenerator.kt`. Find `computeUserTopTags` (around line 308):

```kotlin
suspend fun computeUserTopTags(limit: Int = 10): List<String> {
    val vector = buildUserTagAffinityVector()
    return vector.entries
        .sortedByDescending { it.value }
        .take(limit)
        .map { it.key }
}
```

Replace with:

```kotlin
/**
 * v0.9.16: Top-N user tags ordered by tag-affinity weight. Used by
 * [com.stash.core.data.sync.workers.StashMixRefreshWorker] to drive
 * the TAG_GRAPH seed strategy.
 *
 * v0.9.19: when the listening-affinity vector is empty (fresh install,
 * recently played tracks not yet enriched, etc.) falls back to the
 * library-wide tag histogram. The histogram represents "what kind of
 * music this user collects" — the right anchor for First Listen's
 * "wider net" semantics when there's no per-play signal yet. Returns
 * an empty list ONLY when the user has zero tags anywhere in
 * `track_tags` (truly fresh install, enrichment hasn't run a single
 * batch yet) — at which point TAG_GRAPH-driven recipes correctly
 * stay empty until the user's library has any tag data.
 */
suspend fun computeUserTopTags(limit: Int = 10): List<String> {
    val vector = buildUserTagAffinityVector()
    if (vector.isNotEmpty()) {
        return vector.entries
            .sortedByDescending { it.value }
            .take(limit)
            .map { it.key }
    }
    return trackTagDao.getTagHistogram()
        .asSequence()
        .filter { it.tag != "__untaggable__" }
        .take(limit)
        .map { it.tag }
        .toList()
}
```

The `asSequence().filter().take().toList()` pipeline is intentional: filter happens BEFORE take, so the sentinel can never consume a result slot. The seventh test pins this order.

No other changes in `MixGenerator.kt`.

- [ ] **Step 5: Verify all 7 tests pass**

```bash
./gradlew :core:data:testDebugUnitTest --tests "com.stash.core.data.mix.MixGeneratorComputeUserTopTagsTest"
```

Expected: BUILD SUCCESSFUL with 7 tests passing.

- [ ] **Step 6: Run the broader `:core:data` sweep to catch regressions**

```bash
./gradlew :core:data:testDebugUnitTest
```

Expected: BUILD SUCCESSFUL except for the known pre-existing `PlaylistTypeTest.enum contains the expected set` failure (unrelated; was failing on master before this branch). If anything else fails, stop and investigate — the fallback shouldn't affect any other test.

- [ ] **Step 7: Commit**

```bash
git add core/data/src/main/kotlin/com/stash/core/data/mix/MixGenerator.kt \
        core/data/src/test/kotlin/com/stash/core/data/mix/MixGeneratorComputeUserTopTagsTest.kt
git commit -m "$(cat <<'EOF'
feat(mix): library-histogram fallback for First Listen tag seed

When MixGenerator.buildUserTagAffinityVector returns empty (fresh
install, recently played tracks not yet enriched, all listened-to
tracks resolve as __untaggable__) the TAG_GRAPH seed strategy used
to dead-end — First Listen has discoveryRatio=1.0 with no library
fill, so the mix stayed empty. Daily Discover's ARTIST_SIMILAR has
a multi-tier seed-artist fallback so it kept working; only First
Listen was affected.

computeUserTopTags now falls back to TrackTagDao.getTagHistogram —
already-existing on-device data ordered by track_count DESC. Filter
the __untaggable__ sentinel before the take() so it can't consume a
result slot. Returns empty only when the user genuinely has zero
tags anywhere (truly-fresh install, enrichment hasn't completed a
single batch).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: Version bump + ship

**Files:**
- Modify: `app/build.gradle.kts` — versionCode 56 → 57, versionName "0.9.18" → "0.9.19"

- [ ] **Step 1: Bump versionCode + versionName**

Open `app/build.gradle.kts`, find:

```kotlin
versionCode = 56
versionName = "0.9.18"
```

Update to:

```kotlin
versionCode = 57
versionName = "0.9.19"
```

- [ ] **Step 2: Build the release APK so the user can install over v0.9.18**

```bash
./gradlew :app:assembleRelease
```

Expected: BUILD SUCCESSFUL. The APK lands at `app/build/outputs/apk/release/app-release.apk` (~155 MB, signed with `stash-release.jks`).

If the keystore configuration produces a debug-key fallback (you'll see a build log line warning about it), check that `keystore.properties` was copied into the worktree per the worktree-setup section.

- [ ] **Step 3: Install over the existing v0.9.18 on the user's Pixel**

```bash
adb devices  # confirm `1A021FDEE002RD` (or whatever device is attached) shows up as `device`
adb install -r app/build/outputs/apk/release/app-release.apk
```

Expected: `Performing Streamed Install` → `Success`. The `-r` preserves user data — track DB, listening events, prefs, downloaded files all stay.

If `adb devices` reports no device, skip the install — the user will install themselves. Surface the APK path in the report.

- [ ] **Step 4: Manual smoke test (user runs)**

DO NOT execute this yourself — document the procedure for the user.

Procedure:
1. **Trigger the manual mix refresh.** On Home, long-press the First Listen mix card (or tap the manual refresh action — whichever the v0.9.16 UI exposes for refresh-this-mix). Wait ~15 seconds for the worker to run.
2. **Open First Listen.** Verify the mix now has tracks (typically 50, per the recipe's `targetLength`). The tracks should be tracks NOT in your library — that's the "First Listen" semantics, unchanged.
3. **Confirm Daily Discover and Deep Cuts are unchanged.** They should still populate exactly as they did on v0.9.18 — this fix touches only the TAG_GRAPH path that First Listen uses.

If First Listen is STILL empty after refresh:
- Check `adb logcat` for `StashMixRefresh` lines — the worker should log `'First Listen': N candidates via TAG_GRAPH` if the fallback fires and the histogram has tags. If you see `0 candidates` or no log line, the histogram is also empty and `TagEnrichmentWorker` hasn't yet enriched any track — wait for the next periodic run (24h cadence) or fix enrichment-throughput as a separate v0.9.20 concern.

- [ ] **Step 5: Commit**

```bash
git add app/build.gradle.kts
git commit -m "$(cat <<'EOF'
chore: bump versionCode 56->57, versionName 0.9.18->0.9.19

v0.9.19 — First Listen tag-fallback. When the user's listening-
affinity vector is empty (recently played tracks not yet enriched),
MixGenerator.computeUserTopTags falls back to TrackTagDao.getTagHistogram
so the TAG_GRAPH seed strategy doesn't dead-end. Affects First Listen
only; Daily Discover and Deep Cuts already had working library
fallbacks. Single-function change, no schema impact.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 6: DO NOT push or tag**

The user gates push + tag. Stop here. The user will run the manual smoke test (Step 4), then decide to:

```bash
cd /c/Users/theno/Projects/MP3APK
git checkout master
git merge --ff-only feat/first-listen-tag-fallback
git tag -a v0.9.19 -m "v0.9.19 — First Listen tag-fallback"
git push origin master && git push origin v0.9.19
```

The release workflow takes over from there.

---

## Notes for the executor

- **The single-function change is the entire feature.** Don't refactor neighboring functions, don't tweak `buildUserTagAffinityVector`, don't touch `TagEnrichmentWorker`. Each of those is documented in the spec's Non-goals as out of scope.
- **The KDoc update is part of Task 1's commit.** The function gains a v0.9.19 paragraph explaining the fallback rationale; that's not a separate concern.
- **The `PlayCountWithLatest` import path is unverified.** Confirm it during Step 1 reconnaissance before writing the test file. If the actual location differs, adjust the test's imports and surface as a benign deviation in your report.
- **No version bump in Task 1.** The version bump lives in Task 2 alone — keeps the code commit and the release commit cleanly separable in git history.
- **Pre-existing test failures still apply.** `YtLibraryCanonicalizerTest.OMV...` and `PlaylistTypeTest.enum...` will continue to fail in the broader sweep. Ignore them.
