# Manual Refresh Kicks Discovery Pipeline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make "Refresh this mix" fire the full discovery pipeline (StashDiscoveryWorker + DiscoveryDownloadWorker) without waiting for the periodic charging+WiFi cycle, while still respecting the user's `DownloadNetworkMode` preference for cellular gating.

**Architecture:** Five small, focused changes. New `constraintsForManualTrigger` helper drops the charging requirement (keeps network pref). New `StashDiscoveryWorker.enqueueOneTime(context, mode)` entry point uses it. `HomeViewModel.refreshMix` fires the entry point on every tap. `StashApplication.onCreate` enqueues `DiscoveryDownloadWorker.enqueueOneTime` at startup to drain orphan stubs from prior versions. `StashDiscoveryWorker.doWork`'s chain to `DiscoveryDownloadWorker` switches to manual-trigger constraints so the chain inherits the no-charging-required posture end-to-end.

**Tech Stack:** Kotlin, WorkManager 2.10.1, Hilt, MockK + JUnit4 for unit tests.

**Spec:** `docs/superpowers/specs/2026-05-11-manual-refresh-kicks-discovery-pipeline-design.md` (committed at `e687273`).

---

## Pre-flight notes for the implementer

Worktree: `C:\Users\theno\Projects\MP3APK\.worktrees\first-listen-tag-fallback`. Branch: `feat/first-listen-tag-fallback`. PR 4 already shipped 8 commits; PR 5 lands on top.

### Repository conventions

1. **TDD where applicable** — Task 1 (helper fn) uses TDD; Tasks 2-5 are scheduling/wiring changes verified by build + on-device test.
2. **MockK for unit tests** — pattern: `core/data/src/test/.../DownloadConstraintsTest.kt` may exist; if so, extend; otherwise create.
3. **No push, no tag**. Implementation ends at APK installed on Pixel + manual verification.
4. **Always `:app:installDebug` (or `:app:installRelease`) after a fix** — per memory `feedback_install_after_fix.md`.

### Files you will touch

| Path | What changes |
|---|---|
| `core/data/src/main/kotlin/com/stash/core/data/sync/workers/DownloadConstraints.kt` | Add `constraintsForManualTrigger(mode)` top-level fn — same as `constraintsFor` minus charging requirement. |
| `core/data/src/test/kotlin/com/stash/core/data/sync/workers/DownloadConstraintsTest.kt` | **CREATE or EXTEND** — 3 tests covering each `DownloadNetworkMode`. |
| `core/data/src/main/kotlin/com/stash/core/data/sync/workers/StashDiscoveryWorker.kt` | (Task 2) Add `ONE_SHOT_WORK_NAME` constant + `enqueueOneTime(context, mode)` companion fn. (Task 3) Inject `DownloadNetworkPreference` via constructor; replace inline `Constraints.Builder()` at lines 162-167 with `constraintsForManualTrigger(mode)`. |
| `feature/home/src/main/kotlin/com/stash/feature/home/HomeViewModel.kt` | Inject `DownloadNetworkPreference`; after existing `enqueueUniqueWork(StashMixRefreshWorker, ...)` in `refreshMix`, add one line to also fire `StashDiscoveryWorker.enqueueOneTime(context, mode)`. |
| `app/src/main/kotlin/com/stash/app/StashApplication.kt` | In the `applicationScope.launch { ... }` block at lines 168-177, append `DiscoveryDownloadWorker.enqueueOneTime(this@StashApplication, constraintsForManualTrigger(mode))` after the existing `StashMixRefreshWorker.enqueueOneTime` call. |

### Reference precedents

- **Existing `constraintsFor(mode)`** at `DownloadConstraints.kt:20-34` — `constraintsForManualTrigger` mirrors its shape minus charging.
- **Existing `StashDiscoveryWorker.schedulePeriodic`** at `StashDiscoveryWorker.kt:87-99` — `enqueueOneTime` follows the same pattern but with `OneTimeWorkRequestBuilder` + `enqueueUniqueWork`.
- **PR 1 Task 5's `StashMixRefreshWorker.enqueueOneTime(context, recipeId)`** at `StashMixRefreshWorker.kt:154-168` — same shape as the new one.
- **`DownloadNetworkPreference`** at `core/data/src/main/kotlin/com/stash/core/data/prefs/DownloadNetworkPreference.kt` — `suspend fun current(): DownloadNetworkMode`.

---

## Task 1: `constraintsForManualTrigger` helper

**Files:**
- Modify: `core/data/src/main/kotlin/com/stash/core/data/sync/workers/DownloadConstraints.kt`
- Create or extend: `core/data/src/test/kotlin/com/stash/core/data/sync/workers/DownloadConstraintsTest.kt`

### Step 1: Check whether a test file already exists

```bash
ls core/data/src/test/kotlin/com/stash/core/data/sync/workers/DownloadConstraintsTest.kt
```

If it exists, you'll extend it. If not, you'll create it.

### Step 2: Write the failing tests

If creating the test file, use this template:

```kotlin
package com.stash.core.data.sync.workers

import androidx.work.NetworkType
import com.stash.core.model.DownloadNetworkMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the [constraintsForManualTrigger] helper — drops charging
 * requirement compared to [constraintsFor], otherwise honors the user's
 * [DownloadNetworkMode] preference for cellular gating.
 */
class DownloadConstraintsTest {

    @Test fun `constraintsForManualTrigger WIFI_AND_CHARGING requires WiFi but NOT charging`() {
        val constraints = constraintsForManualTrigger(DownloadNetworkMode.WIFI_AND_CHARGING)

        assertEquals(NetworkType.UNMETERED, constraints.requiredNetworkType)
        assertFalse("manual trigger drops charging requirement", constraints.requiresCharging())
        assertTrue("battery-not-low always required", constraints.requiresBatteryNotLow())
    }

    @Test fun `constraintsForManualTrigger WIFI_ANY requires WiFi but NOT charging`() {
        val constraints = constraintsForManualTrigger(DownloadNetworkMode.WIFI_ANY)

        assertEquals(NetworkType.UNMETERED, constraints.requiredNetworkType)
        assertFalse(constraints.requiresCharging())
        assertTrue(constraints.requiresBatteryNotLow())
    }

    @Test fun `constraintsForManualTrigger ANY_NETWORK requires any network and NOT charging`() {
        val constraints = constraintsForManualTrigger(DownloadNetworkMode.ANY_NETWORK)

        assertEquals(NetworkType.CONNECTED, constraints.requiredNetworkType)
        assertFalse(constraints.requiresCharging())
        assertTrue(constraints.requiresBatteryNotLow())
    }
}
```

If the test file already exists, add the same three `@Test` methods alongside whatever's there.

### Step 3: Run tests — expect compile error

```bash
./gradlew :core:data:testDebugUnitTest --tests "com.stash.core.data.sync.workers.DownloadConstraintsTest"
```

Expected: compile error — `constraintsForManualTrigger` doesn't exist yet.

### Step 4: Implement the helper

In `DownloadConstraints.kt`, append after the existing `constraintsFor(mode)` definition:

```kotlin
/**
 * Constraints for **manual triggers** — when the user explicitly taps
 * "Refresh this mix" or the app boots and we want to drain orphan
 * discovery downloads. Same network discipline as [constraintsFor]
 * (we still respect the user's [DownloadNetworkMode] pref for cellular)
 * but DROPS the charging requirement: the user is actively asking for
 * content; honoring that beats waiting for them to plug in.
 *
 * `setRequiresBatteryNotLow(true)` stays on — a 5% battery + manual
 * refresh is still a bad combination.
 */
fun constraintsForManualTrigger(mode: DownloadNetworkMode): Constraints = Constraints.Builder().apply {
    setRequiresBatteryNotLow(true)
    when (mode) {
        DownloadNetworkMode.WIFI_AND_CHARGING -> setRequiredNetworkType(NetworkType.UNMETERED)
        DownloadNetworkMode.WIFI_ANY -> setRequiredNetworkType(NetworkType.UNMETERED)
        DownloadNetworkMode.ANY_NETWORK -> setRequiredNetworkType(NetworkType.CONNECTED)
    }
}.build()
```

### Step 5: Run tests — expect 3/3 pass

```bash
./gradlew :core:data:testDebugUnitTest --tests "com.stash.core.data.sync.workers.DownloadConstraintsTest"
```

Expected: 3/3 pass.

### Step 6: Run the full :core:data module test sweep

```bash
./gradlew :core:data:testDebugUnitTest
```

Expected: BUILD SUCCESSFUL.

### Step 7: Commit

```bash
git add core/data/src/main/kotlin/com/stash/core/data/sync/workers/DownloadConstraints.kt \
        core/data/src/test/kotlin/com/stash/core/data/sync/workers/DownloadConstraintsTest.kt
git commit -m "feat(download): add constraintsForManualTrigger helper

Mirrors constraintsFor but drops the charging requirement. Used by
manual-user-trigger paths (Refresh this mix, app-startup drain of
orphan discovery downloads) where the user is actively asking for
content — waiting for them to plug in defeats the trigger.

User's network preference still governs cellular gating: WiFi-only
users won't burn cellular even on manual trigger. Only the charging
constraint is dropped.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: `StashDiscoveryWorker.enqueueOneTime` companion fn

**Files:**
- Modify: `core/data/src/main/kotlin/com/stash/core/data/sync/workers/StashDiscoveryWorker.kt`

No new tests — this is a scheduling helper verified by Task 6's on-device test.

### Step 1: Read the existing companion

Open `StashDiscoveryWorker.kt`. The companion object starts around line 65 and contains:
- `private const val TAG = "StashDiscovery"`
- `private const val WORK_NAME = "stash_discovery"` (line 67)
- The existing `schedulePeriodic(context, mode)` fn (lines 87-99)

The new `enqueueOneTime` slots in alongside `schedulePeriodic`.

### Step 2: Add `ONE_SHOT_WORK_NAME` constant

Inside the companion object, immediately after `WORK_NAME`:

```kotlin
private const val ONE_SHOT_WORK_NAME = "stash_discovery_oneshot"
```

### Step 3: Add the `enqueueOneTime` companion fn

After the existing `schedulePeriodic` fn (around line 99), add:

```kotlin
/**
 * Fire a one-shot discovery sweep — manual user trigger, no charging
 * requirement. Respects [DownloadNetworkMode] for cellular gating via
 * [constraintsForManualTrigger]. Unique work name + REPLACE policy so a
 * rapid double-tap coalesces. At the end of [doWork], the existing
 * v0.9.20 chain to [DiscoveryDownloadWorker] fires, completing the
 * pipeline: discovery_queue PENDING → stubs + download_queue PENDING →
 * actual downloads.
 */
fun enqueueOneTime(context: Context, mode: DownloadNetworkMode) {
    val work = OneTimeWorkRequestBuilder<StashDiscoveryWorker>()
        .setConstraints(constraintsForManualTrigger(mode))
        .build()
    WorkManager.getInstance(context).enqueueUniqueWork(
        ONE_SHOT_WORK_NAME,
        ExistingWorkPolicy.REPLACE,
        work,
    )
}
```

### Step 4: Add required imports (only if not already present — grep first)

```kotlin
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
```

These may or may not be already imported — `StashDiscoveryWorker` already imports `androidx.work.*` types for `schedulePeriodic`. Check first.

`constraintsForManualTrigger` is a top-level fun in the same package (added by Task 1) — no import needed.

### Step 5: Build to verify

```bash
./gradlew :core:data:assembleDebug
```

Expected: BUILD SUCCESSFUL.

### Step 6: Run the :core:data module test sweep

```bash
./gradlew :core:data:testDebugUnitTest
```

Expected: BUILD SUCCESSFUL.

### Step 7: Commit

```bash
git add core/data/src/main/kotlin/com/stash/core/data/sync/workers/StashDiscoveryWorker.kt
git commit -m "feat(mix): StashDiscoveryWorker.enqueueOneTime entry point

Lets the UI fire the discovery sweep without waiting for the periodic
charging+WiFi cycle. Uses constraintsForManualTrigger so the user's
network preference still gates cellular but charging is no longer
required. REPLACE policy on the unique work name means rapid taps
coalesce.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: `StashDiscoveryWorker.doWork` chains downloader with manual-trigger constraints

**Files:**
- Modify: `core/data/src/main/kotlin/com/stash/core/data/sync/workers/StashDiscoveryWorker.kt`

This task closes the gap reviewer found in the spec: even when Task 2's manual one-shot fires (no charging required), the chained `DiscoveryDownloadWorker` currently inherits hard-coded charging-required constraints from `StashDiscoveryWorker.doWork:162-167`. Defeats the manual-trigger purpose.

No new tests — verified on-device in Task 6.

### Step 1: Read the chain site

Open `StashDiscoveryWorker.kt`. Find the inline `Constraints.Builder()` block at around line 162 (search for `DiscoveryDownloadWorker.enqueueOneTime`). The current code:

```kotlin
val downloadConstraints = Constraints.Builder()
    .setRequiresCharging(true)
    .setRequiresBatteryNotLow(true)
    .setRequiredNetworkType(NetworkType.UNMETERED)
    .build()
DiscoveryDownloadWorker.enqueueOneTime(applicationContext, downloadConstraints)
```

### Step 2: Inject `DownloadNetworkPreference` into the constructor

Locate the constructor (search for `class StashDiscoveryWorker @AssistedInject constructor`). Add a new injected dependency:

```kotlin
private val downloadNetworkPreference: DownloadNetworkPreference,
```

Place it alongside the other injected DAOs / preferences. Add the import at the top:

```kotlin
import com.stash.core.data.prefs.DownloadNetworkPreference
```

### Step 3: Replace the inline constraints block

Replace the 4-line `Constraints.Builder()` + `DiscoveryDownloadWorker.enqueueOneTime(...)` block (lines 162-167) with:

```kotlin
// v0.9.20: use manual-trigger constraints (drop charging, respect user
// network pref) regardless of whether THIS worker invocation was periodic
// or manual. For the periodic path, the parent's own charging requirement
// already gated this worker from running — by the time we chain, we know
// the device is charging + on WiFi, so dropping the charging req on the
// chain is a no-op. For the manual path, dropping charging is the whole
// point: the user is actively asking for content; honor that.
val mode = downloadNetworkPreference.current()
DiscoveryDownloadWorker.enqueueOneTime(
    applicationContext,
    constraintsForManualTrigger(mode),
)
```

### Step 4: Build to verify

```bash
./gradlew :core:data:assembleDebug
```

Expected: BUILD SUCCESSFUL.

### Step 5: Run the :core:data module test sweep

```bash
./gradlew :core:data:testDebugUnitTest
```

Expected: BUILD SUCCESSFUL. Any existing test that hand-constructs `StashDiscoveryWorker` (via MockK or similar) will fail to compile because of the new constructor parameter — flag if so. Fix by adding `downloadNetworkPreference = mockk(relaxed = true)` (and `coEvery { current() } returns DownloadNetworkMode.WIFI_ANY` or whatever's reasonable) to the test fixture.

Likely there's no such test (StashDiscoveryWorker doesn't have a `Test.kt` file per the original module exploration), but verify.

### Step 6: Build the :app module to verify Hilt can resolve the new dependency

```bash
./gradlew :app:assembleDebug
```

Expected: BUILD SUCCESSFUL. If Hilt complains about missing bindings for `DownloadNetworkPreference`, it means the singleton isn't installed in a compatible component — unlikely since `StashApplication` already uses it.

### Step 7: Commit

```bash
git add core/data/src/main/kotlin/com/stash/core/data/sync/workers/StashDiscoveryWorker.kt
git commit -m "feat(mix): StashDiscoveryWorker chains downloader with manual-trigger constraints

The inline Constraints.Builder() at the chain site hard-coded
setRequiresCharging(true), so even when StashDiscoveryWorker was fired
manually (Task 2's no-charging-required path), the chained
DiscoveryDownloadWorker would still wait for charging — defeating the
manual trigger.

Inject DownloadNetworkPreference and use constraintsForManualTrigger
instead. Safe for both invocation paths:
- Periodic: parent already gated on charging, so dropping it on chain
  is a no-op for the current run.
- Manual: dropping charging is the whole point.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: `HomeViewModel.refreshMix` fires `StashDiscoveryWorker.enqueueOneTime`

**Files:**
- Modify: `feature/home/src/main/kotlin/com/stash/feature/home/HomeViewModel.kt`

No new tests — `HomeViewModel` lacks a test file (verified during PR 1 + PR 3 work); verified on-device in Task 6.

### Step 1: Locate the current `refreshMix`

Open `HomeViewModel.kt`. Find `fun refreshMix(playlistId: Long)` — after PR 1 Task 5 + PR 4 Task 1, it's around line 640-689. The function snapshots the recipe, emits a snackbar, enqueues `StashMixRefreshWorker`, and observes its terminal state.

### Step 2: Inject `DownloadNetworkPreference`

Find the constructor (search for `@HiltViewModel` then `class HomeViewModel`). Add to the injected dependencies:

```kotlin
private val downloadNetworkPreference: DownloadNetworkPreference,
```

Add the import:

```kotlin
import com.stash.core.data.prefs.DownloadNetworkPreference
```

(Grep first — the import may already be present indirectly.)

### Step 3: Add the StashDiscoveryWorker trigger after the existing mix-refresh enqueue

In `refreshMix`, the existing flow is roughly:

```kotlin
fun refreshMix(playlistId: Long) {
    viewModelScope.launch {
        val recipe = recipeDao.findByPlaylistId(playlistId)
        if (recipe == null) { /* error path */ return@launch }
        _userMessages.tryEmit("Refreshing ${recipe.name}…")

        // existing: build + enqueue per-recipe StashMixRefreshWorker
        val request = OneTimeWorkRequestBuilder<StashMixRefreshWorker>()
            .setConstraints(...)
            .setInputData(...)
            .build()
        val uniqueName = "${StashMixRefreshWorker.ONE_SHOT_WORK_NAME}_${recipe.id}"
        WorkManager.getInstance(context).enqueueUniqueWork(uniqueName, ExistingWorkPolicy.REPLACE, request)

        // existing: observe WorkInfo for terminal state, emit snackbar
        ...
    }
}
```

Immediately AFTER the `WorkManager.getInstance(context).enqueueUniqueWork(uniqueName, ...)` call (the per-recipe mix refresh enqueue), and BEFORE the WorkInfo observation block, add:

```kotlin
// v0.9.20: fire the full discovery pipeline. queueDiscoveryForRecipe
// inside the mix refresh worker enqueues new Last.fm candidates into
// discovery_queue PENDING; this trigger processes them right now (subject
// to user's DownloadNetworkMode pref) instead of waiting up to 24h for
// the periodic schedule. The chain in StashDiscoveryWorker's tail will
// fire DiscoveryDownloadWorker, which fires StashMixRefreshWorker again
// at the end — the mix re-materializes with newly-downloaded survivors
// without the user lifting another finger.
val mode = downloadNetworkPreference.current()
StashDiscoveryWorker.enqueueOneTime(context, mode)
```

Add the import:

```kotlin
import com.stash.core.data.sync.workers.StashDiscoveryWorker
```

(May already be present — grep first.)

### Step 4: Build to verify

```bash
./gradlew :feature:home:assembleDebug
```

Expected: BUILD SUCCESSFUL. If Hilt complains, the issue is likely an `@HiltViewModel` binding for `DownloadNetworkPreference` — but `DownloadNetworkPreference` is already a Hilt singleton, so this should resolve.

### Step 5: Run the :feature:home module test sweep

```bash
./gradlew :feature:home:testDebugUnitTest
```

Expected: BUILD SUCCESSFUL or NO-SOURCE (the module has limited unit-test coverage).

### Step 6: Commit

```bash
git add feature/home/src/main/kotlin/com/stash/feature/home/HomeViewModel.kt
git commit -m "feat(home): refreshMix fires the full discovery pipeline on every tap

After enqueueing the per-recipe mix refresh, also fire
StashDiscoveryWorker.enqueueOneTime with manual-trigger constraints
(no charging required, respects DownloadNetworkMode pref). The
discovery worker processes the newly-queued candidates, chains
DiscoveryDownloadWorker, which downloads them and chains a final
StashMixRefreshWorker — mixes re-materialize with new content
without the user lifting another finger.

Battery / data: refreshing while on cellular burns data if the user
explicitly opted into ANY_NETWORK mode; WiFi-only users still wait
for WiFi. The mode pref governs everything except the charging
requirement, which is the explicit relaxation.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 5: `StashApplication.onCreate` drains orphan stubs at startup

**Files:**
- Modify: `app/src/main/kotlin/com/stash/app/StashApplication.kt`

No new tests — verified on-device.

### Step 1: Locate the startup `applicationScope.launch` block

Open `StashApplication.kt`. Find the block at lines 168-177 (or near — find by searching for `maybeRetuneStashMixes()`):

```kotlin
applicationScope.launch {
    maybeReseedStashMixes()
    StashMixDefaults.seedIfNeeded(stashMixRecipeDao)
    maybeRetuneStashDiscover()
    maybeRetuneStashMixes()
    StashMixRefreshWorker.enqueueOneTime(this@StashApplication)
}
```

`downloadNetworkPreference` is already injected (verified during spec review — confirmed at line 95 of `StashApplication.kt`).

### Step 2: Append the orphan-drain enqueue

Inside that same block, after the `StashMixRefreshWorker.enqueueOneTime(...)` call, add:

```kotlin
// v0.9.20: drain orphan discovery download rows from prior version
// installs (stubs that StashDiscoveryWorker created in PR 3 era but
// were never downloaded because the sync-chain TrackDownloadWorker
// was always sync-gated). Respects user's network preference; runs
// when constraints are satisfied.
val mode = downloadNetworkPreference.current()
DiscoveryDownloadWorker.enqueueOneTime(
    this@StashApplication,
    constraintsForManualTrigger(mode),
)
```

### Step 3: Add required imports (grep first)

```kotlin
import com.stash.core.data.sync.workers.DiscoveryDownloadWorker
import com.stash.core.data.sync.workers.constraintsForManualTrigger
```

After PR 4 Task 4's refactor, `DiscoveryDownloadWorker` lives in `core/data/.../sync/workers/`. `constraintsForManualTrigger` was added by PR 5 Task 1 in the same package as `DiscoveryDownloadWorker` — both imports come from `com.stash.core.data.sync.workers`.

### Step 4: Build to verify

```bash
./gradlew :app:assembleDebug
```

Expected: BUILD SUCCESSFUL.

### Step 5: Run the :app module test sweep

```bash
./gradlew :app:testDebugUnitTest
```

Expected: BUILD SUCCESSFUL or NO-SOURCE.

### Step 6: Commit

```bash
git add app/src/main/kotlin/com/stash/app/StashApplication.kt
git commit -m "feat(mix): drain orphan discovery downloads on app cold-start

Pre-existing download_queue rows with sync_id IS NULL (stubs from
prior version installs that StashDiscoveryWorker created but never
got downloaded because the sync-chain TrackDownloadWorker was always
sync-gated) get drained by DiscoveryDownloadWorker on every cold
start, subject to the user's network preference (no charging
required).

Without this, the orphan stubs only drain when the periodic
StashDiscoveryWorker happens to find new candidates and chains the
downloader — which on a fresh PR 5 install could take 24+ hours.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 6: Build APK + install + on-device verification

**Files:** none — verification task.

### Step 1: Full release build

```bash
./gradlew :app:assembleRelease
```

Expected: BUILD SUCCESSFUL. (If `packageRelease` fails transiently — observed in prior PRs — re-run `./gradlew :app:packageRelease`.)

### Step 2: Install over the existing release app

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

Expected: `Success`.

### Step 3: Cold-start

```bash
adb shell monkey -p com.stash.app -c android.intent.category.LAUNCHER 1
```

Wait ~5 seconds for the startup `applicationScope.launch` block to complete.

### Step 4: Verify startup orphan-drain fires

```bash
adb logcat -d -t 500 | grep -E "DiscoveryDownload|StashMigration"
```

Expected: a `DiscoveryDownload: draining N discovery download(s)` line within a few seconds of launch. If `N > 0`, orphan stubs were drained. If `N = 0`, the discovery_queue is empty (no orphans to drain) — also acceptable.

If you don't see the log line, the worker is queued but waiting for constraints (e.g., WiFi off). Plug in / connect WiFi and wait a moment.

### Step 5: Trigger a manual refresh and verify the full pipeline fires

On the Pixel:
1. Plug in to WiFi (no charging needed — that's the point of PR 5).
2. Long-press Daily Discover → "Refresh this mix". Wait for the "Refreshed" snackbar.

Tail logcat:

```bash
adb logcat -t 500 | grep -E "StashMixRefresh|StashDiscovery|DiscoveryDownload"
```

Expected sequence within ~30 seconds:
- `StashMixRefresh: refreshing 1 Stash Mix(es) (single: Daily Discover)`
- `StashMixRefresh: 'Daily Discover': 120 candidates via ARTIST_SIMILAR` (or similar count)
- `WM-WorkerWrapper: Starting work for ...StashDiscoveryWorker` — **this is new in PR 5**
- `StashDiscovery: ...` per-recipe processing logs
- `WM-WorkerWrapper: Starting work for ...DiscoveryDownloadWorker`
- `DiscoveryDownload: draining N discovery download(s)` — counts the rows that were just queued
- After download completes (foreground service notification dismisses): a second `StashMixRefresh: ...` invocation (chained refresh)

### Step 6: Verify new content appears

After the chain completes (couple minutes depending on track count):
1. Open Daily Discover. Track count should now be larger and include many tracks you don't recognize from your library.
2. Open Deep Cuts. Same — new TRACK_SIMILAR content.
3. Open First Listen. Same — new TAG_GRAPH content.
4. Tap several tracks per mix — each should play immediately.

### Step 7: Reporting

When all manual checks pass, summarize:
- Commits that landed
- APK installed on the Pixel
- Logcat evidence the pipeline fired end-to-end
- Manual content-quality checks that passed
- Any deferred or skipped items

Do NOT push, do NOT tag.

---

## What's intentionally not in this plan

YAGNI:

- **No new test for `StashDiscoveryWorker.enqueueOneTime` or `HomeViewModel.refreshMix`.** WorkManager-invocation assertions via MockK are fragile; on-device verification covers it.
- **No new `DiscoveryDownloadWorker` test.** Already comprehensively tested by PR 4 Task 3; PR 5 doesn't touch its internals.
- **No UI affordance** ("Refresh + fetch new"). The existing "Refresh this mix" action now does both — no new UI element needed.
- **No global "refresh all mixes" button.** The per-recipe refresh covers user need; the periodic schedule covers background updates.
- **No telemetry for refresh latency or download success rate.** Build it if/when we need to optimize, not before.
