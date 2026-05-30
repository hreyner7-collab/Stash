# Kennyy → Squid Streaming Failover Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make lossless streaming fail over from Kennyy to Squid quickly and reliably — never wait on a known-dead Kennyy, recover automatically, and never permanently brick the Squid cookie on a transient error.

**Architecture:** Four in-place edits plus one new class. The play-path resolver consults the existing `KennyyHealthMonitor` and skips Kennyy when it's known unhealthy (with a short streaming timeout backstop). A new `KennyyHealthProbe` becomes the *sole* caller of Kennyy while unhealthy — it polls on an interval to flip health back to healthy, is started on the app's STARTED lifecycle (which also fixes cold-start), and is made unbrickable (per-iteration try/catch). The Squid cookie pre-warmer is changed from permanent-halt-after-2-failures to exponential backoff.

**Tech Stack:** Kotlin, Hilt (`@Inject`/`@HiltWorker`-adjacent singletons), Kotlin coroutines + `kotlinx-coroutines-test` (`TestScope`, `advanceTimeBy`, `runCurrent`), MockK, JUnit4. Module: `:core:media` (streaming) depends one-way on `:data:download` (lossless), so both `KennyySource` and `KennyyHealthMonitor` are visible there.

**Spec:** `docs/superpowers/specs/2026-05-30-kennyy-squid-streaming-failover-design.md` — read it first.

**Branch:** `fix/kennyy-squid-streaming-failover` (off `master`).

---

## Background the implementer needs

- **`KennyyHealthMonitor`** (`core/media/.../streaming/KennyyHealthMonitor.kt`): 5-event sliding window; `isHealthy: StateFlow<Boolean>` is false once ≥3 of the last 5 outcomes are failures. Methods: `recordSuccess()`, `recordFailure()`, `recordNoMatch()` (no-op). Starts healthy; process-transient.
- **`KennyySource.resolveImmediate(query: TrackQuery): SourceResult?`** (`data/download/.../lossless/kennyy/KennyySource.kt`): the streaming-path resolve (bypasses rate-limit + circuit breaker). Returns null on catalog miss OR network failure; sets `@Volatile var lastResolveFailedNetwork: Boolean` to distinguish. Uses the shared 30s OkHttp client.
- **`TrackQuery`** (`data/download/.../lossless/`): `TrackQuery(artist, title, album?, isrc?, durationMs)`.
- **`KennyyStreamResolver.resolve(track: TrackEntity): StreamUrl?`**: builds a `TrackQuery` from the track, calls `source.resolveImmediate`, records the outcome on the monitor, returns a `StreamUrl` or null. **Today it never reads `healthMonitor.isHealthy`.**
- **`SquidCookieAutoRefresher`**: `@Singleton`, has a `@Inject` secondary ctor that builds `CoroutineScope(SupervisorJob() + Dispatchers.Main)` and a 5-arg primary ctor `(solver, healthMonitor, prefs, qobuzSource, scope)` used by tests with a `TestScope`. `start()`/`stop()` driven by `StashApplication`'s `ProcessLifecycleOwner` observer. Currently `MAX_FAILURES = 2` → `stop()` permanently.
- **`StashApplication`** (`app/.../StashApplication.kt:198-208`): a `DefaultLifecycleObserver` calls `squidCookieAutoRefresher.start()`/`.stop()`.

**Why the probe must call `KennyySource` directly (not `KennyyStreamResolver`):** after Task 1, `KennyyStreamResolver.resolve` returns null without a network call when unhealthy. The probe runs precisely while unhealthy, so reusing the resolver would no-op. The probe injects `KennyySource` and calls `resolveImmediate` itself. Because the probe only runs while unhealthy (when the play path is skipping Kennyy), the two never call `resolveImmediate` concurrently, so the `@Volatile lastResolveFailedNetwork` flag can't race.

**Probe outcome rule (from spec):** the probe uses a hardcoded always-in-catalog track. `resolveImmediate` returning non-null → `recordSuccess()`. Null (any reason — the track always exists, so any miss is a proxy anomaly) OR timeout OR thrown exception → `recordFailure()`. Never `recordNoMatch()`.

---

## File structure

| File | Responsibility | Change |
|------|----------------|--------|
| `core/media/.../streaming/KennyyStreamResolver.kt` | Play-path Kennyy resolve | **Modify**: skip when unhealthy; wrap call in ~3s `withTimeout` |
| `core/media/.../streaming/KennyyHealthProbe.kt` | Recovery + cold-start probe loop | **Create** |
| `core/media/.../streaming/SquidCookieAutoRefresher.kt` | Squid cookie pre-warm | **Modify**: backoff instead of permanent halt |
| `app/.../StashApplication.kt` | Lifecycle wiring | **Modify**: start/stop the probe |
| `core/media/.../test/.../streaming/KennyyStreamResolverTest.kt` | — | **Modify/Create** tests |
| `core/media/.../test/.../streaming/KennyyHealthProbeTest.kt` | — | **Create** tests |
| `core/media/.../test/.../streaming/SquidCookieAutoRefresherTest.kt` | — | **Modify** tests |

Constants introduced (one place each):
- `KennyyStreamResolver`: `STREAM_RESOLVE_TIMEOUT_MS = 3_000L`.
- `KennyyHealthProbe`: `PROBE_INTERVAL_MS = 45_000L`, `PROBE_TIMEOUT_MS = 3_000L`, hardcoded probe `TrackQuery`.
- `SquidCookieAutoRefresher`: `BASE_RETRY_MS = 60_000L`, `MAX_RETRY_MS = 300_000L` (replaces `MAX_FAILURES`, `FAILURE_RETRY_DELAY_MS`).

---

## Task 1: Health-gate the play path + short streaming timeout

Implements spec changes **#1** (skip dead Kennyy) and **#5** (3s streaming timeout) — same method, one task.

**Files:**
- Modify: `core/media/src/main/kotlin/com/stash/core/media/streaming/KennyyStreamResolver.kt`
- Test: `core/media/src/test/kotlin/com/stash/core/media/streaming/KennyyStreamResolverTest.kt`

- [ ] **Step 1: Read the current resolver and its test.** Read `KennyyStreamResolver.kt` (the `resolve` body, lines ~87-133) and `KennyyStreamResolverTest.kt` to match style (MockK, `runTest`). Note the existing `source` (`KennyySource`) and `healthMonitor` injected fields.

- [ ] **Step 2: Write the failing test — skip when unhealthy.**

```kotlin
@Test
fun resolve_returnsNullWithoutNetwork_whenKennyyUnhealthy() = runTest {
    val source: KennyySource = mockk(relaxed = true)
    val monitor = KennyyHealthMonitor()
    repeat(3) { monitor.recordFailure() } // unhealthy

    val resolver = KennyyStreamResolver(source, monitor)
    val result = resolver.resolve(track(id = 1L))

    assertNull(result)
    coVerify(exactly = 0) { source.resolveImmediate(any()) } // never hit the network
}
```
(Add a `private fun track(id: Long) = TrackEntity(id = id, title = "T", artist = "A", canonicalTitle = "t", canonicalArtist = "a")` helper if the test file lacks one — match the entity's required fields.)

- [ ] **Step 3: Run it — expect FAIL** (resolver currently calls `source.resolveImmediate` regardless).
Run: `./gradlew :core:media:testDebugUnitTest --tests "com.stash.core.media.streaming.KennyyStreamResolverTest"`
Expected: FAIL — `source.resolveImmediate` was called once.

- [ ] **Step 4: Implement the health gate.** At the very top of `resolve(track)`, before building the query:

```kotlin
if (!healthMonitor.isHealthy.value) {
    Log.d(TAG, "skip id=${track.id} (kennyy unhealthy)")
    return null
}
```

- [ ] **Step 5: Run it — expect PASS.** Same command. Expected: PASS.

- [ ] **Step 6: Write the failing test — slow hang times out as failure.**

```kotlin
@Test
fun resolve_timesOutAsFailure_whenKennyyHangs() = runTest {
    val source: KennyySource = mockk {
        coEvery { resolveImmediate(any()) } coAnswers { delay(60_000); null }
    }
    val monitor = KennyyHealthMonitor() // healthy, so the gate lets it through

    val resolver = KennyyStreamResolver(source, monitor)
    val result = resolver.resolve(track(1L))

    assertNull(result)
    assertFalse("a hang must trip health, not be ignored", monitor.isHealthy.value || false)
    // Stronger: the hang recorded a failure. One failure alone won't flip a fresh
    // window, so assert via the resolver returning null within the test's virtual time.
}
```
Note for the implementer: `runTest` uses virtual time, so the 60s `delay` inside the mock advances instantly — the `withTimeout(3_000)` will fire deterministically. Keep the assertion to `assertNull(result)` plus a `coVerify` that `monitor.recordFailure()` happened if you make the monitor a mock; using the real monitor, assert behavior (e.g. after this call, a follow-up makes it unhealthy). Prefer mocking the monitor here for a precise `recordFailure` assertion.

- [ ] **Step 7: Run it — expect FAIL** (no timeout today; the mock's null returns immediately under virtual time, so without `withTimeout` the failure-path still runs but there's no timeout classification). Adjust the test to assert the timeout path specifically (a `TimeoutCancellationException` is caught and `recordFailure` called). Run the command; expected FAIL.

- [ ] **Step 8: Implement the timeout backstop.** Wrap the `source.resolveImmediate(query)` call:

```kotlin
val result = try {
    withTimeout(STREAM_RESOLVE_TIMEOUT_MS) { source.resolveImmediate(query) }
} catch (e: TimeoutCancellationException) {
    healthMonitor.recordFailure()
    Log.w(TAG, "timeout id=${track.id} after ${STREAM_RESOLVE_TIMEOUT_MS}ms")
    return null
}
```
Add imports: `kotlinx.coroutines.withTimeout`, `kotlinx.coroutines.TimeoutCancellationException`. Add `const val STREAM_RESOLVE_TIMEOUT_MS = 3_000L` to the companion. Leave the existing null/etsp/success handling below unchanged.
**Important:** `TimeoutCancellationException` is a `CancellationException` subclass — catch it *specifically* (as above), do not catch generic `CancellationException`, so genuine coroutine cancellation still propagates.

- [ ] **Step 9: Run the whole resolver test class — expect PASS.** Run the command. Expected: PASS (new + existing tests green).

- [ ] **Step 10: Commit.**
```bash
git add core/media/src/main/kotlin/com/stash/core/media/streaming/KennyyStreamResolver.kt \
        core/media/src/test/kotlin/com/stash/core/media/streaming/KennyyStreamResolverTest.kt
git commit -m "fix(streaming): skip Kennyy when unhealthy + 3s streaming timeout"
```

---

## Task 2: `KennyyHealthProbe` — recovery + cold-start, unbrickable

Implements spec change **#2** and the "Probe-loop resilience (critical)" section.

**Files:**
- Create: `core/media/src/main/kotlin/com/stash/core/media/streaming/KennyyHealthProbe.kt`
- Create: `core/media/src/test/kotlin/com/stash/core/media/streaming/KennyyHealthProbeTest.kt`

- [ ] **Step 1: Write the failing test — immediate probe on start + records failure.**

```kotlin
@OptIn(ExperimentalCoroutinesApi::class)
class KennyyHealthProbeTest {
    @Test
    fun probesImmediatelyOnStart_andRecordsFailure() = runTest {
        val source: KennyySource = mockk { coEvery { resolveImmediate(any()) } returns null }
        val monitor: KennyyHealthMonitor = mockk(relaxed = true) {
            every { isHealthy } returns MutableStateFlow(false)
        }
        val probe = KennyyHealthProbe(source, monitor, this)
        probe.start()
        runCurrent()

        coVerify(atLeast = 1) { source.resolveImmediate(any()) }
        verify(atLeast = 1) { monitor.recordFailure() }
        probe.stop()
    }
}
```

- [ ] **Step 2: Run — expect FAIL** (class doesn't exist).
Run: `./gradlew :core:media:testDebugUnitTest --tests "com.stash.core.media.streaming.KennyyHealthProbeTest"`
Expected: FAIL — unresolved `KennyyHealthProbe`.

- [ ] **Step 3: Implement `KennyyHealthProbe`.**

```kotlin
package com.stash.core.media.streaming

import android.util.Log
import com.stash.data.download.lossless.TrackQuery
import com.stash.data.download.lossless.kennyy.KennyySource
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Sole caller of Kennyy while it is unhealthy. The play path
 * ([KennyyStreamResolver]) skips Kennyy when [KennyyHealthMonitor.isHealthy]
 * is false, so this probe is the ONLY mechanism that can flip health back to
 * healthy — therefore the loop must be unbrickable (every iteration catches
 * any Throwable and records a failure; CancellationException is re-thrown so
 * [stop] still cancels cleanly).
 *
 * On [start]: probe once immediately (establishes ground truth before the
 * first play — fixes cold-start, where the monitor optimistically defaults to
 * healthy). Then, while unhealthy, probe every [PROBE_INTERVAL_MS]; while
 * healthy, idle until health flips.
 */
@Singleton
class KennyyHealthProbe(
    private val source: KennyySource,
    private val healthMonitor: KennyyHealthMonitor,
    private val scope: CoroutineScope,
) {
    @Inject
    constructor(
        source: KennyySource,
        healthMonitor: KennyyHealthMonitor,
    ) : this(source, healthMonitor, CoroutineScope(SupervisorJob() + Dispatchers.Main))

    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        Log.d(TAG, "start")
        job = scope.launch {
            probeOnce() // immediate ground-truth probe (cold-start fix)
            while (true) {
                if (healthMonitor.isHealthy.value) {
                    // Idle until health flips to unhealthy, then resume polling.
                    healthMonitor.isHealthy.first { !it }
                }
                probeOnce()
                delay(PROBE_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        Log.d(TAG, "stop")
        job?.cancel()
        job = null
    }

    /** One probe iteration. MUST NOT let any Throwable escape (except cancellation). */
    private suspend fun probeOnce() {
        try {
            val result = withTimeout(PROBE_TIMEOUT_MS) { source.resolveImmediate(PROBE_QUERY) }
            if (result != null) {
                healthMonitor.recordSuccess()
                Log.d(TAG, "probe ok")
            } else {
                healthMonitor.recordFailure()
                Log.d(TAG, "probe miss/fail")
            }
        } catch (e: CancellationException) {
            throw e // let stop() cancel cleanly; TimeoutCancellationException handled below first
        } catch (e: Throwable) {
            healthMonitor.recordFailure()
            Log.w(TAG, "probe threw — recorded failure", e)
        }
    }

    private companion object {
        const val TAG = "KennyyHealthProbe"
        const val PROBE_INTERVAL_MS = 45_000L
        const val PROBE_TIMEOUT_MS = 3_000L
        // Hardcoded always-in-catalog track — any null result is a proxy anomaly.
        val PROBE_QUERY = TrackQuery(
            artist = "Daft Punk",
            title = "Get Lucky",
            album = null,
            isrc = null,
            durationMs = 0L,
        )
    }
}
```
**Subtlety to verify during implementation:** `TimeoutCancellationException` extends `CancellationException`. The `catch (CancellationException) { throw e }` above would re-throw a *timeout* too, which we do NOT want (a timeout is a probe failure, not loop cancellation). Fix by catching `TimeoutCancellationException` FIRST and recording a failure, THEN `catch (CancellationException) { throw e }`, THEN `catch (Throwable)`. Order the catches:
```kotlin
} catch (e: TimeoutCancellationException) {
    healthMonitor.recordFailure(); Log.d(TAG, "probe timeout")
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    healthMonitor.recordFailure(); Log.w(TAG, "probe threw", e)
}
```
Use this three-catch order in the real implementation.

- [ ] **Step 4: Run — expect PASS.** Same command. Expected: PASS.

- [ ] **Step 5: Write the failing test — idle while healthy, poll while unhealthy.**

```kotlin
@Test
fun pollsWhileUnhealthy_idlesWhenHealthy() = runTest {
    val health = MutableStateFlow(false)
    val source: KennyySource = mockk { coEvery { resolveImmediate(any()) } returns null }
    val monitor: KennyyHealthMonitor = mockk(relaxed = true) { every { isHealthy } returns health }
    val probe = KennyyHealthProbe(source, monitor, this)
    probe.start(); runCurrent()           // immediate probe (1)
    advanceTimeBy(45_001L); runCurrent()  // second probe (2)
    val callsWhileUnhealthy = 2
    coVerify(exactly = callsWhileUnhealthy) { source.resolveImmediate(any()) }

    health.value = true                    // recover
    advanceTimeBy(200_000L); runCurrent()  // no further probes while healthy
    coVerify(exactly = callsWhileUnhealthy) { source.resolveImmediate(any()) }
    probe.stop()
}
```

- [ ] **Step 6: Run — expect PASS** (the implementation already supports this; if it fails, fix the loop's idle/poll logic until green).

- [ ] **Step 7: Write the failing test — RESILIENCE: a throwing probe does not kill the loop.**

```kotlin
@Test
fun loopSurvivesAThrow_andKeepsProbing() = runTest {
    val source: KennyySource = mockk {
        coEvery { resolveImmediate(any()) } throws RuntimeException("boom") andThen null
    }
    val monitor: KennyyHealthMonitor = mockk(relaxed = true) {
        every { isHealthy } returns MutableStateFlow(false)
    }
    val probe = KennyyHealthProbe(source, monitor, this)
    probe.start(); runCurrent()           // iteration 1 throws -> recorded failure, loop lives
    advanceTimeBy(45_001L); runCurrent()  // iteration 2 runs (proves loop survived)

    coVerify(atLeast = 2) { source.resolveImmediate(any()) }
    verify(atLeast = 1) { monitor.recordFailure() }
    probe.stop()
}
```

- [ ] **Step 8: Run — expect PASS** (the three-catch `probeOnce` already swallows the throw as a failure). If it fails, the loop isn't catching correctly — fix until green.

- [ ] **Step 9: Write the failing test — stop() cancels cleanly.**

```kotlin
@Test
fun stop_cancelsTheLoop() = runTest {
    val source: KennyySource = mockk { coEvery { resolveImmediate(any()) } returns null }
    val monitor: KennyyHealthMonitor = mockk(relaxed = true) {
        every { isHealthy } returns MutableStateFlow(false)
    }
    val probe = KennyyHealthProbe(source, monitor, this)
    probe.start(); runCurrent()
    probe.stop()
    val before = 1 // at least the immediate probe
    advanceTimeBy(200_000L); runCurrent()
    coVerify(atMost = before + 0) { source.resolveImmediate(any()) } // no probes after stop
}
```
(If the immediate probe count makes `atMost` brittle, assert that the call count does not *increase* after `stop()` by capturing it before/after.)

- [ ] **Step 10: Run the whole probe test class — expect PASS.** Run the command. Expected: PASS.

- [ ] **Step 11: Commit.**
```bash
git add core/media/src/main/kotlin/com/stash/core/media/streaming/KennyyHealthProbe.kt \
        core/media/src/test/kotlin/com/stash/core/media/streaming/KennyyHealthProbeTest.kt
git commit -m "feat(streaming): add unbrickable KennyyHealthProbe for failover recovery + cold-start"
```

---

## Task 3: Wire the probe into the app lifecycle

Implements spec change **#3** (cold-start ground truth via the probe's immediate run).

**Files:**
- Modify: `app/src/main/kotlin/com/stash/app/StashApplication.kt`

- [ ] **Step 1: Inject the probe.** Near the existing `lateinit var squidCookieAutoRefresher: SquidCookieAutoRefresher` (~line 137), add:
```kotlin
@Inject
lateinit var kennyyHealthProbe: KennyyHealthProbe
```
Add the import `com.stash.core.media.streaming.KennyyHealthProbe`.

- [ ] **Step 2: Start/stop alongside the refresher.** In the `DefaultLifecycleObserver` (~lines 198-208), add to the existing callbacks:
```kotlin
override fun onStart(owner: LifecycleOwner) {
    squidCookieAutoRefresher.start()
    kennyyHealthProbe.start()   // immediate probe sets Kennyy health before first play
}
override fun onStop(owner: LifecycleOwner) {
    squidCookieAutoRefresher.stop()
    kennyyHealthProbe.stop()
}
```

- [ ] **Step 3: Build to verify wiring compiles (Hilt graph resolves).**
Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL. (`KennyyHealthProbe` is `@Singleton` with an `@Inject` ctor, so Hilt provides it with no module.)

- [ ] **Step 4: Commit.**
```bash
git add app/src/main/kotlin/com/stash/app/StashApplication.kt
git commit -m "feat(streaming): start KennyyHealthProbe on app STARTED (cold-start health)"
```

---

## Task 4: Pre-warmer backoff instead of permanent halt

Implements spec change **#4**.

**Files:**
- Modify: `core/media/src/main/kotlin/com/stash/core/media/streaming/SquidCookieAutoRefresher.kt`
- Test: `core/media/src/test/kotlin/com/stash/core/media/streaming/SquidCookieAutoRefresherTest.kt`

- [ ] **Step 1: Write the failing test — retries with backoff, never permanently stops.**

```kotlin
@Test
fun retriesWithBackoff_doesNotPermanentlyHalt_onRepeatedSolveFailure() = runTest {
    val solver: NativeSquidCaptchaSolver = mockk {
        coEvery { solve() } returnsMany listOf(null, null, "good-cookie")
    }
    val prefs: LosslessSourcePreferences = mockk(relaxed = true) {
        coEvery { captchaCookieSetAtMs } returns flowOf(0L)
    }
    val monitor = KennyyHealthMonitor()
    repeat(3) { monitor.recordFailure() } // unhealthy
    val qobuzSource: QobuzSource = mockk(relaxed = true)

    val refresher = SquidCookieAutoRefresher(solver, monitor, prefs, qobuzSource, this)
    refresher.start()
    runCurrent()                 // attempt 1 -> null
    advanceTimeBy(60_001L); runCurrent()  // backoff 1 elapses -> attempt 2 -> null
    advanceTimeBy(120_001L); runCurrent() // backoff 2 elapses -> attempt 3 -> success

    coVerify(exactly = 3) { solver.solve() }
    coVerify(exactly = 1) { prefs.setCaptchaCookieValue("good-cookie") }
    refresher.stop()
}
```

- [ ] **Step 2: Run — expect FAIL** (current code calls `stop()` after the 2nd failure, so `solve()` is called only twice and the 3rd never happens).
Run: `./gradlew :core:media:testDebugUnitTest --tests "com.stash.core.media.streaming.SquidCookieAutoRefresherTest"`
Expected: FAIL — `solve()` verified 3 but was 2.

- [ ] **Step 3: Implement backoff.** Replace the failure branch in `refresh()` and the constants. New `refresh()`:

```kotlin
private suspend fun refresh() {
    // Keep attempting while Kennyy is unhealthy; back off on failure; never
    // permanently halt for the session. Returns on success or when Kennyy
    // recovers (health flips healthy). Cancelled cleanly by stop().
    var consecutive = 0
    while (!healthMonitor.isHealthy.value) {
        Log.d(TAG, "refresh attempt")
        val newCookie = solver.solve()
        if (newCookie != null) {
            prefs.setCaptchaCookieValue(newCookie)
            qobuzSource.clearLastKnownBad()
            Log.d(TAG, "refresh success")
            return
        }
        consecutive += 1
        val backoff = minOf(BASE_RETRY_MS shl (consecutive - 1), MAX_RETRY_MS)
        Log.w(TAG, "refresh failure ($consecutive) — retrying in ${backoff}ms")
        delay(backoff)
    }
}
```
Remove the `consecutiveFailures` field and the `MAX_FAILURES` / `FAILURE_RETRY_DELAY_MS` constants. Add:
```kotlin
const val BASE_RETRY_MS = 60_000L   // 60s, doubles each failure
const val MAX_RETRY_MS = 300_000L   // cap at 5min
```
Keep the `start()` collect block as-is (it still calls `refresh()`); `refresh()` now owns the retry loop. The CaptchaExpiredNotifier path is unchanged (separate component).

- [ ] **Step 4: Run — expect PASS.** Same command. Expected: PASS (new test + the two existing tests stay green — `refreshesImmediately_whenKennyyUnhealthyAndCookieStale` still sees exactly one `solve()` because it succeeds on the first attempt and returns).

- [ ] **Step 5: Commit.**
```bash
git add core/media/src/main/kotlin/com/stash/core/media/streaming/SquidCookieAutoRefresher.kt \
        core/media/src/test/kotlin/com/stash/core/media/streaming/SquidCookieAutoRefresherTest.kt
git commit -m "fix(streaming): pre-warmer backs off instead of permanently halting"
```

---

## Task 5: Full module test pass + integration sanity

**Files:** none (verification only).

- [ ] **Step 1: Run the whole `:core:media` test suite.**
Run: `./gradlew :core:media:testDebugUnitTest`
Expected: BUILD SUCCESSFUL. If pre-existing unrelated failures appear, confirm they fail on a clean `master` checkout before attributing them here (do not fix out-of-scope failures; note them).

- [ ] **Step 2: Compile the app.**
Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Manual integration note for the human (on-device).** Not automated. After install, with Kennyy down: confirm (a) first play after cold start goes to Squid quickly (no ~30s stall); (b) recovery — when Kennyy returns, playback resumes via Kennyy within ~90s without a manual step; (c) `adb logcat` shows `KennyyHealthProbe` probing while unhealthy and idling when healthy. This is the real acceptance check; the unit tests lock the logic.

---

## Notes for the implementer

- **DRY:** `STREAM_RESOLVE_TIMEOUT_MS` (Task 1) and `PROBE_TIMEOUT_MS` (Task 2) are intentionally separate constants in separate files (different concerns: a user-tap budget vs a background probe budget) even though both are 3s today. Do not extract a shared constant — they may diverge.
- **YAGNI:** do not persist health or cookie across restarts; do not add a new proxy health endpoint; do not touch `StreamSourceRegistry` ordering, the download/sync path, or any UI.
- **Cancellation discipline:** every catch-all that wraps a suspend call must re-throw `CancellationException` (after handling `TimeoutCancellationException`). This matches the project's cancellation-in-worker-catches convention and is the load-bearing property of the probe loop.
- **Coroutine test timing:** all loops use virtual time under `runTest`; use `advanceTimeBy` + `runCurrent` to drive intervals deterministically — never wall-clock `Thread.sleep`.
