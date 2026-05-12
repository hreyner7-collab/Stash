# Manual Refresh Kicks the Discovery Pipeline Design

> **Status:** Design — pending implementation plan.
> **Scope:** PR 5 of the post-v0.9.19 audit. After PR 4 install, the user reports mixes are entirely library tracks (zero new Last.fm content visible), Daily=11/Deep=5/First-Listen=3-5. Root cause: `StashMixRefreshWorker.queueDiscoveryForRecipe` correctly queues 420+ new Last.fm candidates per refresh (logcat confirmed), but those PENDING `discovery_queue` rows need `StashDiscoveryWorker` to process them — which only runs on charging+WiFi+24h cycle. And pre-existing orphan stubs from prior versions sit in `download_queue` waiting for `DiscoveryDownloadWorker`, which only fires when chained from `StashDiscoveryWorker`. Neither chain has fired on the current install yet.
> **Related:** PR 3 (recipe pivot), PR 4 (per-recipe dedup, partition, `DiscoveryDownloadWorker`, `is_downloaded=1` filter).

---

## Goal

Make "Refresh this mix" actually deliver new content. Specifically:

1. **Tapping refresh fires the full discovery pipeline** — not just the mix re-shuffle. The 120/300/N Last.fm candidates that `queueDiscoveryForRecipe` enqueues should be processed (stubs created, files downloaded) within minutes if the user's network preference allows, not 24+ hours from now when the periodic schedule next fires.
2. **App startup drains orphan stubs** — pre-existing `download_queue` rows from prior versions get processed on every cold-start (assuming user's network preference allows), independent of whether `StashDiscoveryWorker` happens to fire.
3. **User's `DownloadNetworkMode` preference is respected** — manual triggers don't burn cellular for WiFi-only users.

The architecture today treats `StashDiscoveryWorker` and `DiscoveryDownloadWorker` as strictly periodic+chained-from-discovery workers. PR 5 adds one-shot entry points the UI and startup hook can call.

## Non-goals

- **Removing the charging requirement from the PERIODIC `StashDiscoveryWorker` schedule.** Daily background discovery stays conservative; only manual triggers relax it.
- **Removing the user's network preference as a gate.** A WIFI_AND_CHARGING user explicitly opted to never download on cellular — that pref is honored even on manual triggers (we only drop the charging requirement, not the network requirement).
- **A UI surface telling the user "downloads pending, plug in for more."** The foreground-service notification from `DiscoveryDownloadWorker` already provides visible progress.
- **Modifying `StashMixRefreshWorker` to internally trigger the discovery pipeline.** The trigger happens at the user-action layer (`HomeViewModel.refreshMix`) and at app-startup (`StashApplication`). Keeping `StashMixRefreshWorker` ignorant of the pipeline keeps it simple and reusable.
- **Removing the `is_downloaded = 1` filter from PR 4 Task 5.** The filter is correct; the issue is the pipeline downstream of it not firing. PR 5 fixes the pipeline; the filter stays.

## Architecture

```
┌────────────────────────────────────────────────────────────────────────────┐
│ User taps "Refresh this mix" on a Stash Mix card                           │
│   ↓                                                                        │
│ HomeViewModel.refreshMix(playlistId):                                      │
│   - Existing: emit "Refreshing X…", enqueueOneTime(StashMixRefreshWorker)  │
│   - NEW: enqueueOneTime(StashDiscoveryWorker, manual constraints)          │
│       constraints: drop charging req, keep network req from user pref      │
│   ↓                                                                        │
│ StashMixRefreshWorker.doWork (instant, network-only):                      │
│   - Re-materializes mix from current local state                           │
│   - queueDiscoveryForRecipe → INSERT discovery_queue PENDING rows          │
│   ↓                                                                        │
│ StashDiscoveryWorker.doWork (manual one-shot, fires when constraints met): │
│   - For each discovery_queue PENDING row:                                  │
│       create stub TrackEntity (isDownloaded=false) +                       │
│       insert download_queue (status=PENDING, sync_id=NULL) +               │
│       mark discovery_queue DONE                                            │
│   - At end: chain DiscoveryDownloadWorker (existing PR 4 Task 4 chain)     │
│   ↓                                                                        │
│ DiscoveryDownloadWorker.doWork:                                            │
│   - Drains download_queue (sync_id IS NULL, status=PENDING or retry-FAILED)│
│   - Per-track: download via TrackDownloader → markAsDownloaded + COMPLETED │
│   - At end: chain StashMixRefreshWorker.enqueueOneTime (full sweep)        │
│   ↓                                                                        │
│ StashMixRefreshWorker.doWork (second invocation, full sweep):              │
│   - Mixes re-materialize with newly-downloaded survivors                   │
│   - is_downloaded=1 filter (PR 4 Task 5) now passes the freshly downloaded │
│     ones; user sees new Last.fm content in their mixes                     │
└────────────────────────────────────────────────────────────────────────────┘

┌────────────────────────────────────────────────────────────────────────────┐
│ App cold-start (existing StashApplication.onCreate sequence):              │
│   - maybeReseedStashMixes                                                  │
│   - StashMixDefaults.seedIfNeeded                                          │
│   - maybeRetuneStashDiscover                                               │
│   - maybeRetuneStashMixes                                                  │
│   - StashMixRefreshWorker.enqueueOneTime                                   │
│   - NEW: DiscoveryDownloadWorker.enqueueOneTime(manual constraints)        │
│     ↓ drains orphan stubs from prior version installs                      │
└────────────────────────────────────────────────────────────────────────────┘
```

No schema migration. Room schema v22 stays.

## Component 1 — `constraintsForManualTrigger` helper

`core/data/src/main/kotlin/com/stash/core/data/sync/workers/DownloadConstraints.kt`.

Add a sibling helper to the existing `constraintsFor(mode)`:

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

`WIFI_AND_CHARGING` and `WIFI_ANY` produce identical constraints under manual trigger (both just require WiFi) — the charging distinction only matters for background work.

## Component 2 — `StashDiscoveryWorker.enqueueOneTime`

`core/data/src/main/kotlin/com/stash/core/data/sync/workers/StashDiscoveryWorker.kt`.

Add a companion function alongside the existing `schedulePeriodic`:

```kotlin
private const val ONE_SHOT_WORK_NAME = "stash_discovery_oneshot"

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

Required imports (check before adding to avoid duplicates):

```kotlin
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
```

## Component 3 — `HomeViewModel.refreshMix` fires `StashDiscoveryWorker`

`feature/home/src/main/kotlin/com/stash/feature/home/HomeViewModel.kt`.

The current `refreshMix` (post PR 1) emits a snackbar, enqueues `StashMixRefreshWorker`, and observes its terminal state. PR 5 adds one line: enqueue `StashDiscoveryWorker.enqueueOneTime` so the full pipeline fires.

Add a new injected dependency:

```kotlin
private val downloadNetworkPreference: DownloadNetworkPreference,
```

(`DownloadNetworkPreference` is the existing user-preference holder — locate it via grep; it has a `.current(): DownloadNetworkMode` or similar suspend accessor. Adapt the spec to the actual API name once verified.)

Then in `refreshMix`, after the existing `enqueueUniqueWork` for the mix refresh and after the `WorkManager.await()` snapshot for the mix terminal observation, add:

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

**Placement note:** this fires alongside the per-recipe `StashMixRefreshWorker` — they're independent unique-work names and can run concurrently. The mix-refresh worker queues candidates into `discovery_queue`; the discovery worker (when constraints permit) processes them. No race on row visibility because Room transactions are serialized.

## Component 4 — `StashApplication.onCreate` drains orphans

`app/src/main/kotlin/com/stash/app/StashApplication.kt`.

The existing `applicationScope.launch { ... }` block at lines 168-176 (post PR 3 Task 6 wiring) currently ends with:

```kotlin
applicationScope.launch {
    maybeReseedStashMixes()
    StashMixDefaults.seedIfNeeded(stashMixRecipeDao)
    maybeRetuneStashDiscover()
    maybeRetuneStashMixes()
    StashMixRefreshWorker.enqueueOneTime(this@StashApplication)
}
```

Add a final call to drain orphans:

```kotlin
applicationScope.launch {
    maybeReseedStashMixes()
    StashMixDefaults.seedIfNeeded(stashMixRecipeDao)
    maybeRetuneStashDiscover()
    maybeRetuneStashMixes()
    StashMixRefreshWorker.enqueueOneTime(this@StashApplication)
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
}
```

`StashApplication` likely already injects `downloadNetworkPreference` for some existing worker scheduling. Verify and reuse if so; otherwise inject it.

Required imports:

```kotlin
import com.stash.core.data.sync.workers.DiscoveryDownloadWorker
import com.stash.core.data.sync.workers.constraintsForManualTrigger
```

(Or whatever paths resolve to the actual symbols after PR 4 Task 4's refactor moved `DiscoveryDownloadWorker` to `core/data/.../sync/workers/`.)

## Component 5 — `StashDiscoveryWorker.doWork` uses manual-trigger constraints when chaining

`core/data/src/main/kotlin/com/stash/core/data/sync/workers/StashDiscoveryWorker.kt`.

**The gap:** When `StashDiscoveryWorker.doWork` chains `DiscoveryDownloadWorker` at its tail (PR 4 Task 4), the constraints are currently hard-coded inline with `setRequiresCharging(true)` (lines 162-167). This means: even when `StashDiscoveryWorker` was triggered manually via Component 2's new `enqueueOneTime` (no charging required), the downloader STILL waits for charging — defeating Component 2's purpose entirely.

**Fix:** Replace the inline constraint builder with a `constraintsForManualTrigger(mode)` call, which requires injecting `DownloadNetworkPreference` into the worker.

Add to the constructor:

```kotlin
private val downloadNetworkPreference: DownloadNetworkPreference,
```

Replace the chain site at lines 162-167 (the existing inline `Constraints.Builder()` block followed by `DiscoveryDownloadWorker.enqueueOneTime(...)`):

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

Required new imports (check before adding):

```kotlin
import com.stash.core.data.prefs.DownloadNetworkPreference
```

(`constraintsForManualTrigger` is a top-level fun in the same package as `StashDiscoveryWorker` after Component 1 lands — no import needed.)

**Safety analysis:** the change is safe for both invocation paths:
- **Periodic + `WIFI_AND_CHARGING`** mode: parent worker only ran because charging + WiFi were both satisfied. Dropping `setRequiresCharging(true)` on the chained downloader is a no-op for the current state (still charging).
- **Manual + `WIFI_AND_CHARGING`** mode: parent worker only ran because WiFi was satisfied (manual constraints). Dropping charging means the downloader fires immediately on WiFi — exactly what the user wanted.
- **Any mode without WiFi**: parent didn't run; chain doesn't fire.

## Tests

### Robolectric + Room in-memory

None required — the changes are workers + scheduling helpers + a top-level fun. DAO behavior is unchanged.

### MockK / unit tests

1. **`DownloadConstraintsTest`** (new) — verify `constraintsForManualTrigger(mode)` returns the expected `Constraints` for each `DownloadNetworkMode`:
   - `WIFI_AND_CHARGING` → `UNMETERED`, `batteryNotLow`, `charging=false`
   - `WIFI_ANY` → `UNMETERED`, `batteryNotLow`, `charging=false`
   - `ANY_NETWORK` → `CONNECTED`, `batteryNotLow`, `charging=false`
   Compare against `Constraints.requiredNetworkType`, `Constraints.requiresCharging()`, `Constraints.requiresBatteryNotLow()` accessors.

2. **No new worker test for `StashDiscoveryWorker.enqueueOneTime`** — testing `WorkManager.enqueueUniqueWork` invocation via MockK is fragile; verified by manual on-device test.

3. **No new test for `HomeViewModel.refreshMix`'s additional enqueue call** — same rationale. Manual on-device test covers it.

4. **`StashApplication` startup hook** — verified at app cold-start during manual on-device test.

## Manual on-device verification

1. Cold-start the app. Check logcat for `DiscoveryDownload: draining N discovery download(s)` — confirms the startup chain fires. If `N > 0`, orphan stubs were drained.
2. Plug in to WiFi (no charging needed — that's the point of PR 5).
3. Long-press Daily Discover → Refresh. Wait for the "Refreshed" snackbar.
4. Watch logcat:
   - `StashMixRefresh: 'Daily Discover': N candidates via ARTIST_SIMILAR` (existing)
   - `WM-WorkerWrapper: Starting work for ...StashDiscoveryWorker` (NEW — one-shot fires)
   - `StashDiscovery: ...` per-recipe processing logs (existing format)
   - `WM-WorkerWrapper: Starting work for ...DiscoveryDownloadWorker` (PR 4 chain)
   - `DiscoveryDownload: draining N discovery download(s)` (existing format)
5. After the downloader completes (foreground service notification dismisses), open Daily Discover. Track count should grow as new Last.fm tracks land. Many tracks should be ones you DON'T recognize from your library.
6. Repeat for Deep Cuts and First Listen. Each should now have new TRACK_SIMILAR / TAG_GRAPH content respectively.

## Failure modes + edge cases

1. **User's `DownloadNetworkMode = WIFI_AND_CHARGING` and they refresh while on cellular without charging.** `constraintsForManualTrigger(WIFI_AND_CHARGING)` requires `UNMETERED`. WorkManager won't run the worker until WiFi is connected. The mix refresh itself completes (its constraints are met), candidates are queued in `discovery_queue` PENDING, but the discovery worker waits for WiFi. User sees the same outcome as today: queued but not processed. Acceptable — the pref explicitly said WiFi-only.

2. **User's `DownloadNetworkMode = WIFI_ANY` and they refresh while on cellular.** Same as #1 — waits for WiFi.

3. **User's `DownloadNetworkMode = ANY_NETWORK` and they refresh while on cellular.** Discovery worker fires immediately; downloads on cellular. The user explicitly opted into this.

4. **Rapid double-tap of "Refresh this mix" on the same recipe.** `StashDiscoveryWorker.enqueueOneTime` uses unique-work-name `stash_discovery_oneshot` with `REPLACE` policy — second tap cancels the first invocation, second invocation runs. Slight wasted work; acceptable.

5. **App cold-start triggers the orphan-drain `DiscoveryDownloadWorker`, then user refreshes a mix mid-drain.** The user's refresh triggers `StashDiscoveryWorker.enqueueOneTime`, which (when its constraints are met) chains a fresh `DiscoveryDownloadWorker` invocation with `REPLACE` policy — that cancels the running orphan-drain. Wasted partial work. Mitigation: not worth complicating policy. The cancelled work picks up cleanly on next invocation; rows in `IN_PROGRESS` are reset by `TrackDownloadWorker`'s existing `resetStaleInProgress` sweep at next sync.

6. **`StashDiscoveryWorker` already running (periodic cycle) when user refreshes.** `enqueueOneTime` with `REPLACE` policy cancels the periodic-cycle run and starts the manual run. Edge case but harmless — the manual run does the same work the periodic run was doing.

7. **`downloadNetworkPreference.current()` is suspend or async.** Need to read it inside the `viewModelScope.launch { ... }` coroutine in `refreshMix`, not synchronously at the call site. Implementer to verify the API shape.

8. **Battery cost of running discovery downloads on cellular.** User explicitly opted into `ANY_NETWORK` mode; respecting the pref is the contract. If they're alarmed by battery drain, they can switch to `WIFI_ANY`.

## Rollout

Single APK install on Pixel, manual verification of the test plan above. No flag, no staged rollout.

## Open questions

None. All design decisions resolved during brainstorming:
- User chose **Option (a)** during the brainstorm: trigger full pipeline immediately, no charging requirement, respect `DownloadNetworkMode` pref.
- `constraintsForManualTrigger` is the right level of abstraction (shared helper alongside the existing `constraintsFor`).
- `HomeViewModel.refreshMix` is the right trigger site (user-action layer; `StashMixRefreshWorker` itself stays ignorant of the discovery pipeline).
- App-startup drains orphans via `DiscoveryDownloadWorker.enqueueOneTime` directly (no need to also trigger `StashDiscoveryWorker` on startup — the daily periodic handles fresh content; the manual trigger handles user-initiated refreshes).
