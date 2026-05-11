# Mix Refresh + Lossless Sweep UX Feedback Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add enqueue + completion snackbar feedback to the two silent Home actions (long-press Refresh, banner tap-to-resolve), surface resolved/total counts from `LosslessRetryWorker`, and flip the mix-refresh periodic policy so existing installs reschedule against the current worker spec.

**Architecture:** Three additive changes in three modules — no schema, no new entities, no new workers. Add a `_userMessages` SharedFlow to `HomeViewModel` (mirroring `NowPlayingViewModel`), wire a Toast collector in `HomeScreen` (mirroring `NowPlayingScreen`), emit start + WorkInfo-terminal messages from the two action methods, augment `LosslessRetryWorker.doWork` to return `(resolved, total)` output data, and switch `StashMixRefreshWorker.schedulePeriodic` from `KEEP` to `UPDATE`.

**Tech Stack:** Kotlin, Compose, Hilt, WorkManager 2.10.1, MockK + JUnit4 for unit tests, Room.

**Spec:** `docs/superpowers/specs/2026-05-11-mix-refresh-ux-feedback-design.md` (committed at `3002a95`).

---

## Pre-flight notes for the implementer

You're working in worktree `C:\Users\theno\Projects\MP3APK\.worktrees\first-listen-tag-fallback` on branch `feat/first-listen-tag-fallback`. The spec has been written and reviewed; this plan is what to actually build.

### Repository conventions you must follow

1. **String literals over `strings.xml`.** This codebase keeps user-facing snackbar copy as inline string literals in ViewModels (see `feature/nowplaying/.../NowPlayingViewModel.kt` lines 263, 281, 283, 303, 306). Do **not** introduce new string resources; the spec's copy table is a reference, not an instruction to use Android string resources.
2. **Toast over Snackbar.** The established pattern (see `feature/nowplaying/.../NowPlayingScreen.kt:97-101`) is `android.widget.Toast.makeText(...).show()` inside a `LaunchedEffect(Unit) { viewModel.userMessages.collect { ... } }` block. There is no app-level `SnackbarHost`. Use Toast.
3. **`_userMessages` template.** Copy the exact MutableSharedFlow shape from `NowPlayingViewModel.kt:54-67` (extraBufferCapacity = 8, DROP_OLDEST). The buffer-of-8 is load-bearing — `v0.9.18` post-mortem (Find-in-FLAC ships two messages back-to-back; capacity-of-1 race dropped the first).
4. **TDD on worker changes.** `LosslessRetryWorkerTest.kt` exists and uses MockK with hand-constructed `LosslessRetryWorker` instances; extend it. There is **no** existing `HomeViewModelTest.kt`; the ViewModel changes will be exercised by manual on-device verification (golden-path + edge cases per the spec's Manual Test Plan) rather than by unit tests, because the new VM logic is trivial wiring and the only non-trivial piece — output-data math — is already covered by worker unit tests.
5. **No push, no tag.** Per user instruction (memory: `feedback_ship_terminology.md`), implementation ends at "APK installed on Pixel + manual verification." Do not push the branch, do not create a release tag.
6. **No dev-time estimates.** Per memory: `feedback_no_time_estimates.md`.
7. **Always `:app:installDebug` after a fix.** Per memory: `feedback_install_after_fix.md` — compile-pass isn't enough.

### Files you will touch

| Path | What changes |
|---|---|
| `data/download/src/main/kotlin/com/stash/data/download/lossless/LosslessRetryWorker.kt` | Add `KEY_RESOLVED` + `KEY_TOTAL` companion constants; track `resolvedCount`; return `Result.success(workDataOf(...))`. |
| `data/download/src/test/kotlin/com/stash/data/download/lossless/LosslessRetryWorkerTest.kt` | Update existing tests + add new assertions on output data for empty / no-match / partial-match. |
| `core/data/src/main/kotlin/com/stash/core/data/sync/workers/StashMixRefreshWorker.kt` | One-line flip on line 121: `ExistingPeriodicWorkPolicy.KEEP` → `ExistingPeriodicWorkPolicy.UPDATE`. |
| `feature/home/src/main/kotlin/com/stash/feature/home/HomeViewModel.kt` | Add `_userMessages` SharedFlow + `userMessages` SharedFlow. Modify `refreshMix` (line 554) and `onRetryDeferredRequested` (line 404) to emit start + terminal messages and observe WorkInfo. |
| `feature/home/src/main/kotlin/com/stash/feature/home/HomeScreen.kt` | Add `LaunchedEffect(Unit) { viewModel.userMessages.collect { ... } }` block near the top of the Composable that hosts `viewModel`. |

### Reference snippets you'll be copy-adapting

**From `NowPlayingViewModel.kt:54-67`** — the SharedFlow declaration template:

```kotlin
private val _userMessages = MutableSharedFlow<String>(
    extraBufferCapacity = 8,
    onBufferOverflow = BufferOverflow.DROP_OLDEST,
)
/** One-shot snackbar messages. */
val userMessages: SharedFlow<String> = _userMessages.asSharedFlow()
```

**From `NowPlayingScreen.kt:96-101`** — the Toast collector template:

```kotlin
val toastContext = LocalContext.current
LaunchedEffect(Unit) {
    viewModel.userMessages.collect { msg ->
        android.widget.Toast.makeText(toastContext, msg, android.widget.Toast.LENGTH_LONG).show()
    }
}
```

**WorkInfo observation API (WorkManager 2.10.1):**

```kotlin
WorkManager.getInstance(context)
    .getWorkInfosForUniqueWorkFlow(uniqueWorkName)
    .collect { workInfos ->
        // List<WorkInfo>. Filter by id if needed.
    }
```

---

## Task 1: `LosslessRetryWorker` output data (TDD)

**Files:**
- Modify: `data/download/src/main/kotlin/com/stash/data/download/lossless/LosslessRetryWorker.kt`
- Modify: `data/download/src/test/kotlin/com/stash/data/download/lossless/LosslessRetryWorkerTest.kt`

- [ ] **Step 1: Read both files in full so you understand the current shape**

Run:

```bash
# Read in your editor; no command required.
```

Confirm: the production file is 79 lines, no companion constants yet for output keys. The test file has 3 existing tests using `assertEquals(Result.success(), result)`. Empty-input early return is at line 46.

- [ ] **Step 2: Write failing tests for output data emission**

Open `LosslessRetryWorkerTest.kt`. Replace the existing three `@Test` methods with the following (adding new `KEY_RESOLVED` / `KEY_TOTAL` assertions). Three test cases stay; assertions get richer.

```kotlin
@Test
fun `resolved row flips to PENDING and reports resolved=1 total=1`() = runTest {
    coEvery { downloadQueueDao.waitingForLosslessTracks() } returns listOf(
        entry(id = 100L, trackId = 1L),
    )
    coEvery { trackDao.getById(1L) } returns stubTrackEntity(1L)
    coEvery { registry.resolve(any()) } returns stubSourceResult()

    val result = newWorker().doWork() as androidx.work.ListenableWorker.Result.Success

    assertEquals(1, result.outputData.getInt(LosslessRetryWorker.KEY_RESOLVED, -1))
    assertEquals(1, result.outputData.getInt(LosslessRetryWorker.KEY_TOTAL, -1))
    coVerify(exactly = 1) {
        downloadQueueDao.updateStatus(
            id = 100L,
            status = DownloadStatus.PENDING,
        )
    }
}

@Test
fun `unresolved row stays WAITING_FOR_LOSSLESS and reports resolved=0 total=1`() = runTest {
    coEvery { downloadQueueDao.waitingForLosslessTracks() } returns listOf(
        entry(id = 100L, trackId = 1L),
    )
    coEvery { trackDao.getById(1L) } returns stubTrackEntity(1L)
    coEvery { registry.resolve(any()) } returns null

    val result = newWorker().doWork() as androidx.work.ListenableWorker.Result.Success

    assertEquals(0, result.outputData.getInt(LosslessRetryWorker.KEY_RESOLVED, -1))
    assertEquals(1, result.outputData.getInt(LosslessRetryWorker.KEY_TOTAL, -1))
    coVerify(exactly = 0) {
        downloadQueueDao.updateStatus(any(), any(), any(), any(), any(), any())
    }
}

@Test
fun `empty deferred set returns resolved=0 total=0`() = runTest {
    coEvery { downloadQueueDao.waitingForLosslessTracks() } returns emptyList()

    val result = newWorker().doWork() as androidx.work.ListenableWorker.Result.Success

    assertEquals(0, result.outputData.getInt(LosslessRetryWorker.KEY_RESOLVED, -1))
    assertEquals(0, result.outputData.getInt(LosslessRetryWorker.KEY_TOTAL, -1))
    coVerify(exactly = 0) {
        downloadQueueDao.updateStatus(any(), any(), any(), any(), any(), any())
    }
}

@Test
fun `partial match across 3 rows reports resolved=2 total=3`() = runTest {
    coEvery { downloadQueueDao.waitingForLosslessTracks() } returns listOf(
        entry(id = 100L, trackId = 1L),
        entry(id = 101L, trackId = 2L),
        entry(id = 102L, trackId = 3L),
    )
    coEvery { trackDao.getById(1L) } returns stubTrackEntity(1L)
    coEvery { trackDao.getById(2L) } returns stubTrackEntity(2L)
    coEvery { trackDao.getById(3L) } returns stubTrackEntity(3L)
    coEvery { registry.resolve(match { it.title == "Track 1" }) } returns stubSourceResult()
    coEvery { registry.resolve(match { it.title == "Track 2" }) } returns null
    coEvery { registry.resolve(match { it.title == "Track 3" }) } returns stubSourceResult()

    val result = newWorker().doWork() as androidx.work.ListenableWorker.Result.Success

    assertEquals(2, result.outputData.getInt(LosslessRetryWorker.KEY_RESOLVED, -1))
    assertEquals(3, result.outputData.getInt(LosslessRetryWorker.KEY_TOTAL, -1))
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run from worktree root:

```bash
./gradlew :data:download:testDebugUnitTest --tests "com.stash.data.download.lossless.LosslessRetryWorkerTest"
```

Expected: 4 tests fail. The first three because the worker doesn't expose `KEY_RESOLVED` / `KEY_TOTAL` constants (compile error). The fourth because `partial match across 3 rows` is brand new.

If the compile errors are about the constants not existing, that confirms the test is wired correctly. Move on.

- [ ] **Step 4: Implement the production change**

Edit `LosslessRetryWorker.kt`. Make four targeted edits:

1. **Add `KEY_RESOLVED` and `KEY_TOTAL` to the companion object** (replacing the existing `companion object`):

```kotlin
companion object {
    const val UNIQUE_WORK_NAME = "lossless-retry"

    /** Output-data key: how many WAITING_FOR_LOSSLESS rows were flipped to PENDING this sweep. */
    const val KEY_RESOLVED = "lossless_retry_resolved"

    /** Output-data key: how many WAITING_FOR_LOSSLESS rows existed when the sweep started. */
    const val KEY_TOTAL = "lossless_retry_total"
}
```

2. **Replace the empty-input early return at line 46** so it carries output data:

```kotlin
val deferred = downloadQueueDao.waitingForLosslessTracks()
if (deferred.isEmpty()) {
    return Result.success(
        androidx.work.workDataOf(
            KEY_RESOLVED to 0,
            KEY_TOTAL to 0,
        ),
    )
}
```

3. **Track resolved count inside the loop** and **return with output data**:

```kotlin
var resolvedCount = 0
for (entry in deferred) {
    val track = trackDao.getById(entry.trackId) ?: continue
    val match = runCatching {
        registry.resolve(
            TrackQuery(
                artist = track.artist,
                title = track.title,
                album = track.album.takeIf { it.isNotBlank() },
                isrc = track.isrc,
                durationMs = track.durationMs.takeIf { it > 0 },
            ),
        )
    }.getOrNull()
    if (match != null) {
        downloadQueueDao.updateStatus(
            id = entry.id,
            status = DownloadStatus.PENDING,
        )
        resolvedCount++
    }
}
return Result.success(
    androidx.work.workDataOf(
        KEY_RESOLVED to resolvedCount,
        KEY_TOTAL to deferred.size,
    ),
)
```

4. **Add the import** at the top if not already present:

```kotlin
import androidx.work.workDataOf
```

- [ ] **Step 5: Run tests to verify they pass**

```bash
./gradlew :data:download:testDebugUnitTest --tests "com.stash.data.download.lossless.LosslessRetryWorkerTest"
```

Expected: all 4 tests pass.

- [ ] **Step 6: Run the full module test suite to catch regressions**

```bash
./gradlew :data:download:testDebugUnitTest
```

Expected: BUILD SUCCESSFUL. No previously-passing tests should fail.

- [ ] **Step 7: Commit**

```bash
git add data/download/src/main/kotlin/com/stash/data/download/lossless/LosslessRetryWorker.kt \
        data/download/src/test/kotlin/com/stash/data/download/lossless/LosslessRetryWorkerTest.kt
git commit -m "feat(lossless): emit resolved/total counts from retry worker

Adds KEY_RESOLVED and KEY_TOTAL output-data keys so the Home banner
tap-to-resolve can show 'Resolved X/Y' or 'None resolved this time'
after the sweep. No behavior change for the worker itself; the
PENDING flip on match is unchanged. Empty-queue early return now
carries (0, 0) output data so UI consumers never read null.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 2: `StashMixRefreshWorker` periodic policy flip

**Files:**
- Modify: `core/data/src/main/kotlin/com/stash/core/data/sync/workers/StashMixRefreshWorker.kt:121`

- [ ] **Step 1: Make the one-line change**

Open `StashMixRefreshWorker.kt` and find `schedulePeriodic` (function starts at line 109, the `enqueueUniquePeriodicWork` call is at lines 119-123). Change `ExistingPeriodicWorkPolicy.KEEP` to `ExistingPeriodicWorkPolicy.UPDATE`:

```kotlin
WorkManager.getInstance(context).enqueueUniquePeriodicWork(
    WORK_NAME,
    ExistingPeriodicWorkPolicy.UPDATE,
    work,
)
```

Add a brief comment above the call explaining the choice:

```kotlin
// v0.9.20: UPDATE (not KEEP) so existing installs reschedule against
// the current worker spec on the next cold start. KEEP previously meant
// constraint changes / class changes were ignored across upgrades —
// a credible cause of "periodic refresh hasn't fired in 3 days" reports.
WorkManager.getInstance(context).enqueueUniquePeriodicWork(
    WORK_NAME,
    ExistingPeriodicWorkPolicy.UPDATE,
    work,
)
```

- [ ] **Step 2: Build the module to verify no regression**

```bash
./gradlew :core:data:assembleDebug
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Run the module test suite**

```bash
./gradlew :core:data:testDebugUnitTest
```

Expected: BUILD SUCCESSFUL. No tests reference the periodic policy directly.

- [ ] **Step 4: Commit**

```bash
git add core/data/src/main/kotlin/com/stash/core/data/sync/workers/StashMixRefreshWorker.kt
git commit -m "feat(mix): flip mix-refresh periodic policy to UPDATE

KEEP preserves stale workspecs across upgrades, which can leave
existing installs running against constraints / worker code from a
previous version. UPDATE re-applies the current spec on every cold
start, ensuring constraint and worker changes ship to existing users.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 3: Add `_userMessages` SharedFlow to `HomeViewModel`

**Files:**
- Modify: `feature/home/src/main/kotlin/com/stash/feature/home/HomeViewModel.kt`

- [ ] **Step 1: Add the import for `BufferOverflow` and `MutableSharedFlow`**

At the top of `HomeViewModel.kt`, near the other `kotlinx.coroutines.flow.*` imports, add:

```kotlin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
```

(`asSharedFlow` is already imported on line 40. `SharedFlow` may already be imported — check first.)

- [ ] **Step 2: Add the SharedFlow declaration**

Find an appropriate spot — typically right after the existing `_uiState` / `uiState` declarations, before the `init {}` block. Use the NowPlayingViewModel pattern verbatim:

```kotlin
private val _userMessages = MutableSharedFlow<String>(
    // Bumped to 8 to mirror NowPlayingViewModel — actions that emit two
    // back-to-back messages (refresh start → done, lossless retry start →
    // result) need headroom against the Toast collector's drain rate.
    extraBufferCapacity = 8,
    onBufferOverflow = BufferOverflow.DROP_OLDEST,
)
/** One-shot snackbar messages (e.g. "Refreshing Daily Discover…"). */
val userMessages: SharedFlow<String> = _userMessages.asSharedFlow()
```

- [ ] **Step 3: Build to verify no regression**

```bash
./gradlew :feature:home:assembleDebug
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add feature/home/src/main/kotlin/com/stash/feature/home/HomeViewModel.kt
git commit -m "feat(home): add userMessages SharedFlow for one-shot Toast emissions

Mirrors NowPlayingViewModel. Wires the channel without any emitters
yet — subsequent commits emit from refreshMix and
onRetryDeferredRequested. Buffer of 8 matches NowPlayingViewModel for
back-to-back start/result emissions.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 4: Wire the Toast collector in `HomeScreen`

**Files:**
- Modify: `feature/home/src/main/kotlin/com/stash/feature/home/HomeScreen.kt`

- [ ] **Step 1: Locate the Composable that holds `viewModel` and the `uiState` collection**

Open `HomeScreen.kt` and find the main `@Composable fun HomeScreen(...)` (or whichever Composable obtains `viewModel: HomeViewModel = hiltViewModel()` and calls `viewModel.uiState.collectAsStateWithLifecycle()`).

You may need to grep for `collectAsStateWithLifecycle` to find it. Example pattern from `NowPlayingScreen.kt:83-101`:

```kotlin
val uiState by viewModel.uiState.collectAsStateWithLifecycle()
// ... other state holders ...

val toastContext = LocalContext.current
LaunchedEffect(Unit) {
    viewModel.userMessages.collect { msg ->
        android.widget.Toast.makeText(toastContext, msg, android.widget.Toast.LENGTH_LONG).show()
    }
}
```

- [ ] **Step 2: Add the collector**

Immediately after `viewModel.uiState.collectAsStateWithLifecycle()` (and any other initial state declarations), insert:

```kotlin
val toastContext = LocalContext.current
LaunchedEffect(Unit) {
    viewModel.userMessages.collect { msg ->
        android.widget.Toast.makeText(toastContext, msg, android.widget.Toast.LENGTH_LONG).show()
    }
}
```

If `LocalContext` is not imported, add:

```kotlin
import androidx.compose.ui.platform.LocalContext
```

(It may already be imported — check.)

- [ ] **Step 3: Build and verify**

```bash
./gradlew :feature:home:assembleDebug
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add feature/home/src/main/kotlin/com/stash/feature/home/HomeScreen.kt
git commit -m "feat(home): wire Toast collector for userMessages

Mirrors the NowPlayingScreen pattern — LaunchedEffect(Unit) collects
viewModel.userMessages and shows each string as a LONG Toast. No
emitters yet (subsequent commits wire refreshMix and the lossless
retry banner tap), so this commit is a no-op at runtime.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 5: `refreshMix` snackbar lifecycle

**Files:**
- Modify: `feature/home/src/main/kotlin/com/stash/feature/home/HomeViewModel.kt:546-559`

- [ ] **Step 1: Add the imports for WorkInfo observation**

Near the existing `androidx.work.*` imports in `HomeViewModel.kt`, add:

```kotlin
import androidx.work.WorkInfo
import androidx.work.await
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
```

(`first` may already be imported on line 45 — check before adding a duplicate. `await` is the suspend extension on `Operation`/`ListenableFuture` from `work-runtime-ktx`.)

Also add an Android `Log` import if not already present:

```kotlin
import android.util.Log
```

For the log tag, use a file-level top-level constant (matches the prevailing pattern elsewhere in the codebase and avoids hunting for an existing companion object inside HomeViewModel). Add this **outside** the class body (above the `@HiltViewModel` annotation, in the file's top-level namespace):

```kotlin
private const val TAG = "HomeViewModel"
```

- [ ] **Step 2: Replace `refreshMix` (lines 554-559) with the new implementation**

Old (current code at lines 554-559):

```kotlin
fun refreshMix(playlistId: Long) {
    viewModelScope.launch {
        val recipe = recipeDao.findByPlaylistId(playlistId) ?: return@launch
        StashMixRefreshWorker.enqueueOneTime(context, recipe.id)
    }
}
```

New (keep the existing KDoc above the function; replace only the function body):

```kotlin
fun refreshMix(playlistId: Long) {
    viewModelScope.launch {
        val recipe = recipeDao.findByPlaylistId(playlistId)
        if (recipe == null) {
            // Data-integrity bug: playlist.type == STASH_MIX but no recipe
            // back-links it. Menu shouldn't have appeared. Log + soft-fail.
            Log.w(TAG, "refreshMix: no recipe back-links playlistId=$playlistId")
            _userMessages.tryEmit("Couldn't refresh — this mix isn't linked to a recipe")
            return@launch
        }

        _userMessages.tryEmit("Refreshing ${recipe.name}\u2026")

        // Build the request ourselves so we can capture its id for exact-
        // match WorkInfo filtering below. enqueueUniqueWork uses the same
        // unique name + REPLACE policy as StashMixRefreshWorker.enqueueOneTime,
        // mirroring lines 154-168 of that worker.
        val request = OneTimeWorkRequestBuilder<StashMixRefreshWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setInputData(workDataOf(StashMixRefreshWorker.KEY_RECIPE_ID to recipe.id))
            .build()
        val uniqueName = "${StashMixRefreshWorker.ONE_SHOT_WORK_NAME}_${recipe.id}"
        WorkManager.getInstance(context)
            .enqueueUniqueWork(uniqueName, ExistingWorkPolicy.REPLACE, request)

        // Observe the unique-work Flow; filter to OUR enqueued request's id
        // so historical entries from earlier taps (or earlier sessions)
        // don't fire stale "Refreshed" Toasts.
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkFlow(uniqueName)
            .firstOrNull { infos ->
                val ours = infos.firstOrNull { it.id == request.id } ?: return@firstOrNull false
                when (ours.state) {
                    WorkInfo.State.SUCCEEDED -> {
                        _userMessages.tryEmit("Refreshed ${recipe.name}")
                        true
                    }
                    WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> {
                        _userMessages.tryEmit("Refresh failed \u2014 try again later")
                        true
                    }
                    else -> false
                }
            }
    }
}
```

Add these imports near the existing `androidx.work.*` imports:

```kotlin
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.workDataOf
```

(`ExistingWorkPolicy` is already imported on line 31.)

**Why we build the request inline instead of calling `StashMixRefreshWorker.enqueueOneTime(context, recipe.id)`:** the helper doesn't return the `WorkRequest`, so we can't capture its `id`. We need that id to filter the WorkInfo Flow — otherwise `getWorkInfosForUniqueWorkFlow` would surface historical entries from previous sessions and we'd emit a stale "Refreshed X" Toast the moment the user opens the Home screen again. Duplicating the request builder is acceptable because the surface is small (4 lines) and the worker's spec is stable.

**Why a coroutine-suspend `.firstOrNull` instead of `.launchIn`:** the whole `refreshMix` body is already a coroutine via `viewModelScope.launch`; suspending until terminal-state simplifies cancellation and avoids managing a separate Job for the observer. When the ViewModel dies, the parent `viewModelScope.launch` is cancelled, and so is this suspended call.

- [ ] **Step 3: Sanity-check `ONE_SHOT_WORK_NAME` is exposed for use here**

In `StashMixRefreshWorker.kt`, the constant is currently declared `private const val ONE_SHOT_WORK_NAME = "stash_mix_refresh_oneshot"` (line 89). Check whether it is `private` (visible only inside the companion) or `internal` / public. If it's `private`, change it to `const val` (no `private`) — promoting to `internal` within the companion object — and verify the build still passes.

You may need to change:

```kotlin
private const val ONE_SHOT_WORK_NAME = "stash_mix_refresh_oneshot"
```

to:

```kotlin
const val ONE_SHOT_WORK_NAME = "stash_mix_refresh_oneshot"
```

If the field was already non-private, skip this change.

- [ ] **Step 4: Build and verify**

```bash
./gradlew :feature:home:assembleDebug
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add feature/home/src/main/kotlin/com/stash/feature/home/HomeViewModel.kt \
        core/data/src/main/kotlin/com/stash/core/data/sync/workers/StashMixRefreshWorker.kt
git commit -m "feat(home): emit refresh snackbar lifecycle for single-mix refresh

Long-press 'Refresh this mix' now shows 'Refreshing X…' on enqueue
and 'Refreshed X' (or 'Refresh failed') when the worker reaches a
terminal WorkInfo state. The data-integrity branch (STASH_MIX
playlist with no back-linked recipe) now logs and tells the user
instead of silently no-opping.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 6: `onRetryDeferredRequested` snackbar lifecycle

**Files:**
- Modify: `feature/home/src/main/kotlin/com/stash/feature/home/HomeViewModel.kt:404-411`

- [ ] **Step 1: Verify the DAO Flow that powers the waiting count**

The spec said to confirm the DAO method. We already verified: `DownloadQueueDao.waitingForLosslessCount(): Flow<Int>` (line 60 of `DownloadQueueDao.kt`). Confirm it's already injected — HomeViewModel constructor takes `downloadQueueDao: DownloadQueueDao` (line 76).

- [ ] **Step 2: Replace `onRetryDeferredRequested` (lines 404-411) with the new implementation**

Old:

```kotlin
fun onRetryDeferredRequested() {
    val request = OneTimeWorkRequestBuilder<LosslessRetryWorker>().build()
    WorkManager.getInstance(context).enqueueUniqueWork(
        LosslessRetryWorker.UNIQUE_WORK_NAME,
        ExistingWorkPolicy.KEEP,
        request,
    )
}
```

New (keep the existing KDoc; replace only the body):

```kotlin
fun onRetryDeferredRequested() {
    viewModelScope.launch {
        // Snapshot the current count before we kick the worker so the
        // start message and the math after both reference the same N.
        val countAtStart = downloadQueueDao.waitingForLosslessCount().first()
        if (countAtStart <= 0) return@launch  // banner shouldn't be visible

        _userMessages.tryEmit("Looking for FLAC versions of $countAtStart tracks\u2026")

        val request = OneTimeWorkRequestBuilder<LosslessRetryWorker>().build()
        // KEEP policy: a rapid double-tap coalesces. Suspend on the
        // Operation's await() (work-runtime-ktx) so we don't block the
        // viewModelScope's Main.immediate dispatcher.
        WorkManager.getInstance(context).enqueueUniqueWork(
            LosslessRetryWorker.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        ).await()

        // Observe the unique-work Flow. Filter to OUR enqueued request's
        // id so historical entries from earlier sessions don't fire stale
        // "Resolved …" Toasts. Under KEEP policy a coalesced tap will see
        // the SAME id as the in-flight work, so this still drains correctly
        // on every tap.
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkFlow(LosslessRetryWorker.UNIQUE_WORK_NAME)
            .firstOrNull { infos ->
                val ours = infos.firstOrNull { it.id == request.id } ?: return@firstOrNull false
                when (ours.state) {
                    WorkInfo.State.SUCCEEDED -> {
                        val resolved = ours.outputData.getInt(LosslessRetryWorker.KEY_RESOLVED, 0)
                        val total = ours.outputData.getInt(LosslessRetryWorker.KEY_TOTAL, 0)
                        val message = if (resolved == 0) {
                            "None resolved this time \u2014 we'll keep trying."
                        } else {
                            val remaining = total - resolved
                            "Resolved $resolved/$total. $remaining still waiting."
                        }
                        _userMessages.tryEmit(message)
                        true
                    }
                    WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> {
                        _userMessages.tryEmit("Sweep failed \u2014 try again later")
                        true
                    }
                    else -> false
                }
            }
    }
}
```

**Why exact-id filter:** `getWorkInfosForUniqueWorkFlow` returns historical entries; filtering on `request.id` ensures we only react to the WorkInfo for the work we just enqueued. This closes Failure Mode #6 from the spec (stale "Resolved …" messages on screen re-entry).

**Why `Operation.await()` (suspend) over `Operation.result.get()`:** `viewModelScope.launch` defaults to `Dispatchers.Main.immediate`. `ListenableFuture.get()` blocks the calling thread until the future resolves; doing that on the Main dispatcher would freeze the UI thread, however briefly. `await()` from `androidx.work` (provided by `work-runtime-ktx`, already on the classpath) suspends correctly.

- [ ] **Step 3: Build and verify**

```bash
./gradlew :feature:home:assembleDebug
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add feature/home/src/main/kotlin/com/stash/feature/home/HomeViewModel.kt
git commit -m "feat(home): emit lossless retry sweep lifecycle messages

Banner tap-to-resolve now shows 'Looking for FLAC versions of N
tracks…' on enqueue and either 'Resolved X/Y. Z still waiting.' or
'None resolved this time — we'll keep trying.' when the worker
returns. Zero-count taps short-circuit before enqueue.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

## Task 7: Build APK + install + on-device manual verification

**Files:** none — this is a verification task.

- [ ] **Step 1: Full project build**

```bash
./gradlew :app:assembleDebug
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Install on connected Pixel**

```bash
./gradlew :app:installDebug
```

Expected: installation succeeds. If multiple devices, use `-PdeviceSerial=1A021FDEE002RD` or similar.

- [ ] **Step 3: Manual test plan from the spec**

Open the app and confirm each of the following:

1. **Cold start sanity** — Force-stop the app via Android Settings, then relaunch. Verify it doesn't crash on `enqueueUniquePeriodicWork(UPDATE)`. Run `adb logcat -d | grep StashMixRefresh` (one-shot dump of the existing buffer) and confirm no exception.
2. **Refresh Daily Discover** — Long-press the Daily Discover card → tap "Refresh this mix." Expect:
   - Bottom sheet dismisses.
   - Toast: `Refreshing Daily Discover…` within ~100ms.
   - Toast: `Refreshed Daily Discover` after the worker completes (few seconds).
3. **Refresh Deep Cuts** — same flow, same expectations.
4. **Refresh First Listen** — same flow, same expectations.
5. **Banner tap with non-zero count** — Wait for the "N tracks waiting for lossless" banner to appear, tap it. Expect:
   - Toast: `Looking for FLAC versions of N tracks…`.
   - Toast after the sweep: `None resolved this time — we'll keep trying.` (because Symptom 4a's match-quality fix is deferred to a separate PR).
6. **JobScheduler verification** — Run:
   ```bash
   adb shell dumpsys jobscheduler | grep -A 5 -i "stash_mix_refresh"
   ```
   Expect: at least one scheduled job for `StashMixRefreshWorker` under the app's uid.

- [ ] **Step 4: If manual testing reveals issues, fix them with TDD-style additions**

Any bug found during manual testing should produce a new failing test or a logcat trace, then a fix commit. Do not push past unfixed issues.

- [ ] **Step 5: Report back to the user**

When all manual steps pass, surface a short status message that lists:
- The commits that landed on this branch
- The APK installed on the Pixel
- The manual checks that passed
- Any deferred or skipped items

Do **not** push the branch and do **not** create a release tag. The user explicitly gates release on their own on-device verification (per memory `feedback_ship_terminology.md`).

---

## What's intentionally not in this plan

Following YAGNI:

- **No new `HomeViewModelTest.kt`.** The new VM logic is trivial wiring on top of a thoroughly-tested worker. Adding a Robolectric harness + fake WorkManager to test "did we emit the right string" is more setup than the test is worth at this stage. If a regression bites, we add the test then.
- **No `strings.xml` plumbing.** The codebase convention is inline literals. The spec's copy table is the source of truth for *what* to emit, not *how*.
- **No SnackbarHost migration.** The Toast pattern is what every other screen uses; switching one screen is inconsistent for no benefit.
- **No instrumentation/dev surface.** The spec mentioned this as a possible follow-up; defer until PR 1 + 4 ship and we have a baseline to compare against.
- **No `HomeViewModelTest`.** Repeated for emphasis — adding a Robolectric/fake-WorkManager harness for two methods that emit strings and observe a Flow is more setup than payoff. Manual on-device verification (Task 7) covers the user-facing paths; worker output-data math is covered by Task 1's unit tests.
