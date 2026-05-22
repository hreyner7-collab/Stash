# Streaming Resilience Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Harden Stash's streaming path so Kennyy outages no longer cause silent fallback to YouTube + cascading skip-next failures.

**Architecture:** Three components shipped as three commits:
1. Resolver attempt logging (visibility for silent fallback)
2. Player cascade guard (bounded skip-next recovery + Snackbar)
3. Squid cookie auto-refresh (background re-auth gated on Kennyy health + app lifecycle)

**Tech Stack:** Kotlin, Coroutines, Hilt, Media3, ProcessLifecycle, WebView, JUnit4 + MockK + Truth.

**Spec:** `docs/superpowers/specs/2026-05-21-streaming-resilience-design.md`

---

## File Map

### Phase 1: Logging
- Modify: `data/download/src/main/kotlin/com/stash/data/download/lossless/kennyy/KennyySource.kt`
- Modify: `data/download/src/main/kotlin/com/stash/data/download/lossless/qobuz/QobuzSource.kt`
- Modify: `core/media/src/main/kotlin/com/stash/core/media/streaming/KennyyStreamResolver.kt`
- Modify: `core/media/src/main/kotlin/com/stash/core/media/streaming/QobuzStreamResolver.kt`
- Test: `core/media/src/test/kotlin/com/stash/core/media/streaming/KennyyStreamResolverTest.kt`

### Phase 2: Cascade guard
- Modify: `core/media/src/main/kotlin/com/stash/core/media/PlayerRepositoryImpl.kt`
- Modify: `core/media/src/main/kotlin/com/stash/core/media/PlayerRepository.kt` (interface, expose `streamingHaltedEvents`)
- Create: `core/media/src/main/kotlin/com/stash/core/media/StreamingHaltedEvent.kt`
- Modify: `feature/nowplaying/src/main/kotlin/com/stash/feature/nowplaying/NowPlayingViewModel.kt` (subscribe to flow)
- Modify: `feature/nowplaying/src/main/kotlin/com/stash/feature/nowplaying/NowPlayingScreen.kt` (Snackbar)
- Test: `core/media/src/test/kotlin/com/stash/core/media/PlayerErrorCascadeTest.kt` (new — pure-Kotlin test of the counter logic, extracted to a small class)
- Create: `core/media/src/main/kotlin/com/stash/core/media/StreamErrorCascadeGuard.kt` (extracted logic for testability)

### Phase 3: Squid auto-refresh
- Create: `core/media/src/main/kotlin/com/stash/core/media/streaming/KennyyHealthMonitor.kt`
- Create: `core/media/src/test/kotlin/com/stash/core/media/streaming/KennyyHealthMonitorTest.kt`
- Modify: `core/media/src/main/kotlin/com/stash/core/media/streaming/KennyyStreamResolver.kt` (call monitor)
- Modify: `data/download/src/main/kotlin/com/stash/data/download/lossless/LosslessSourcePreferences.kt` (add `captchaCookieSetAtMs`)
- Create: `data/download/src/main/kotlin/com/stash/data/download/lossless/squid/HeadlessSquidCaptchaSolver.kt`
- Create: `data/download/src/main/kotlin/com/stash/data/download/lossless/squid/SquidCookieAutoRefresher.kt`
- Modify: `app/src/main/kotlin/com/stash/app/StashApplication.kt` (start the refresher in `onCreate`)
- Test: `core/media/src/test/kotlin/com/stash/core/media/streaming/KennyyHealthMonitorTest.kt`
- Test: `data/download/src/test/kotlin/com/stash/data/download/lossless/squid/SquidCookieAutoRefresherTest.kt`

---

## Conventions

- **Test framework:** JUnit4 + MockK + Truth (matches existing pattern in `core/media/src/test`).
- **Logging:** `Log.d(TAG, "msg")` with a `private const val TAG = "<className>"` in the companion object.
- **Commits:** Conventional — `feat(media): ...`, `feat(streaming): ...`, `test(streaming): ...`, etc. End every commit body with `Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>`.

---

## PHASE 1 — Resolver Logging

### Task 1: KennyySource resolveImmediate attempt/result logging

**Files:**
- Modify: `data/download/src/main/kotlin/com/stash/data/download/lossless/kennyy/KennyySource.kt`

KennyySource already has TAG + some Log.d calls. Add three more lines: at attempt-entry, at success, at failure with reason.

- [ ] **Step 1: Inspect the current file**

Read `KennyySource.kt` and locate `resolveImmediate` (around line 71) + `resolveInternal` (around line 74).

- [ ] **Step 2: Add attempt log at the top of `resolveInternal`**

After the function signature, before the search:

```kotlin
private suspend fun resolveInternal(query: TrackQuery, bypassRateLimit: Boolean): SourceResult? {
    Log.d(TAG, "resolve attempt artist='${query.artist}' title='${query.title}' isrc=${query.isrc ?: "none"}")
    val searchTerm = query.isrc ?: "${query.artist} ${query.title}"
    // ...
```

- [ ] **Step 3: Add success log before the successful return**

Find the final `return SourceResult(...)` and immediately before it, log:

```kotlin
Log.d(TAG, "resolved '${query.title}' url=${result.downloadUrl.take(60)}... codec=${format.codec}")
```

If the existing function has multiple return paths, add the log only at the *successful-result* path. Other return-null paths are handled in step 4.

- [ ] **Step 4: Add failure-reason log at each null-return path**

Existing paths that return null:
- search empty → `Log.d(TAG, "no_match artist='${query.artist}' title='${query.title}' (search returned empty)")`
- no candidate above MIN_CONFIDENCE → already logs; leave it but ensure the message includes `reason=below_confidence`
- API call failed (catches `QobuzApiException` / IOException) → `Log.w(TAG, "failed reason=http_${e.statusCode ?: "?"} '${query.title}'", e)` (existing `Log.w` at line ~173, augment it to include `reason=`)
- circuit broken / rate limited → `Log.d(TAG, "skipped reason=rate_limited")`

- [ ] **Step 5: Build & verify**

```bash
./gradlew :data:download:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add data/download/src/main/kotlin/com/stash/data/download/lossless/kennyy/KennyySource.kt
git commit -m "$(cat <<'EOF'
feat(streaming): log attempt/result/reason at KennyySource boundary

Surfaces silent fallback in logcat. Before this, a Kennyy 502 left no
trace and a downstream investigator would see only YouTubeStreamResolver
hits in logs — misreading "0 Kennyy events" as "tracks aren't on Qobuz."

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: QobuzSource mirror

**Files:**
- Modify: `data/download/src/main/kotlin/com/stash/data/download/lossless/qobuz/QobuzSource.kt`

Mirror Task 1 in QobuzSource. Same three log points (attempt, success, failure with reason). QobuzSource has a distinct "captcha required" failure mode — surface it specifically.

- [ ] **Step 1: Add attempt log at the top of resolveInternal/resolveImmediate**

```kotlin
Log.d(TAG, "resolve attempt artist='${query.artist}' title='${query.title}'")
```

- [ ] **Step 2: Add success log**

Same as Task 1.

- [ ] **Step 3: Add captcha-required reason log**

Wherever the code branches on the existing 403 / "Captcha required" path:
```kotlin
Log.w(TAG, "failed reason=captcha_required '${query.title}'")
```

- [ ] **Step 4: Build & commit**

```bash
./gradlew :data:download:compileDebugKotlin
git add data/download/src/main/kotlin/com/stash/data/download/lossless/qobuz/QobuzSource.kt
git commit -m "$(cat <<'EOF'
feat(streaming): log attempt/result/reason at QobuzSource boundary

Mirrors KennyySource logging. Captcha-required is surfaced as a
distinct reason so cookie expiry is greppable separately from
network failures.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: Resolver-level logging (Kennyy & Qobuz)

**Files:**
- Modify: `core/media/src/main/kotlin/com/stash/core/media/streaming/KennyyStreamResolver.kt`
- Modify: `core/media/src/main/kotlin/com/stash/core/media/streaming/QobuzStreamResolver.kt`

Adds resolver-wrapper logs so the StreamSourceRegistry layer also shows attempts. Lower-volume than the source layer (skips per-track scoring noise).

- [ ] **Step 1: Add TAG companion to each resolver if absent**

```kotlin
private companion object {
    const val ORIGIN = "kennyy"
    val ETSP_REGEX = Regex("""[?&]etsp=(\d+)""")
    private const val TAG = "KennyyStreamResolver"
}
```

(QobuzStreamResolver: TAG = "QobuzStreamResolver")

- [ ] **Step 2: Add attempt + success/null log in `resolve()`**

```kotlin
suspend fun resolve(track: TrackEntity): StreamUrl? {
    Log.d(TAG, "resolve attempt id=${track.id} title='${track.title}'")
    val query = TrackQuery( /* ... */ )
    val result = source.resolveImmediate(query) ?: run {
        Log.d(TAG, "no_result id=${track.id}")
        return null
    }
    val etspMs = parseEtspMs(result.downloadUrl) ?: run {
        Log.w(TAG, "no_etsp id=${track.id}")
        return null
    }
    Log.d(TAG, "resolved id=${track.id} origin=$ORIGIN expiresInSec=${(etspMs - System.currentTimeMillis()) / 1000}")
    return StreamUrl(/* ... */)
}
```

Add `import android.util.Log` if not present.

- [ ] **Step 3: Mirror in QobuzStreamResolver**

Same shape, plus the `disabled` branch:

```kotlin
suspend fun resolve(track: TrackEntity): StreamUrl? {
    Log.d(TAG, "resolve attempt id=${track.id} title='${track.title}'")
    if (!source.isEnabledForStreaming()) {
        Log.d(TAG, "disabled id=${track.id} (no cookie or stale)")
        return null
    }
    // ...same shape as KennyyStreamResolver...
}
```

- [ ] **Step 4: Update existing test to assert logs are unobtrusive**

`KennyyStreamResolverTest` already tests success/null cases. Logs aren't part of the contract — don't add log-assertion tests. Just run the existing suite to confirm no regression:

```bash
./gradlew :core:media:testDebugUnitTest --tests "*KennyyStreamResolver*"
```

Expected: PASS (4 existing tests).

- [ ] **Step 5: Build full module + commit**

```bash
./gradlew :core:media:compileDebugKotlin :core:media:testDebugUnitTest
git add core/media/src/main/kotlin/com/stash/core/media/streaming/KennyyStreamResolver.kt core/media/src/main/kotlin/com/stash/core/media/streaming/QobuzStreamResolver.kt
git commit -m "$(cat <<'EOF'
feat(streaming): log attempt/result at resolver wrapper layer

Each resolver now logs its attempt + outcome at the wrapper boundary.
Combined with source-level logging, gives both a per-call summary
(resolver) and per-call detail (source).

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## PHASE 2 — Cascade Guard

### Task 4: StreamErrorCascadeGuard class (testable counter)

**Files:**
- Create: `core/media/src/main/kotlin/com/stash/core/media/StreamErrorCascadeGuard.kt`
- Create: `core/media/src/test/kotlin/com/stash/core/media/StreamErrorCascadeGuardTest.kt`

Extracts the consecutive-error counter into a small class so it can be unit-tested without instantiating ExoPlayer/MediaController.

- [ ] **Step 1: Write the failing test**

`core/media/src/test/kotlin/com/stash/core/media/StreamErrorCascadeGuardTest.kt`:

```kotlin
package com.stash.core.media

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class StreamErrorCascadeGuardTest {

    @Test
    fun firstError_allowsRecovery() {
        val guard = StreamErrorCascadeGuard(threshold = 3)
        val verdict = guard.onError()
        assertThat(verdict).isEqualTo(StreamErrorCascadeGuard.Verdict.Recover)
    }

    @Test
    fun thirdConsecutiveError_haltsCascade() {
        val guard = StreamErrorCascadeGuard(threshold = 3)
        guard.onError()
        guard.onError()
        val verdict = guard.onError()
        assertThat(verdict).isEqualTo(StreamErrorCascadeGuard.Verdict.Halt)
    }

    @Test
    fun successfulPlaybackResetsCounter() {
        val guard = StreamErrorCascadeGuard(threshold = 3)
        guard.onError()
        guard.onError()
        guard.onPlaybackStarted()
        val verdict = guard.onError()
        assertThat(verdict).isEqualTo(StreamErrorCascadeGuard.Verdict.Recover)
    }

    @Test
    fun userTransportResetsCounter() {
        val guard = StreamErrorCascadeGuard(threshold = 3)
        guard.onError(); guard.onError()
        guard.onUserTransport()
        val verdict = guard.onError()
        assertThat(verdict).isEqualTo(StreamErrorCascadeGuard.Verdict.Recover)
    }

    @Test
    fun haltVerdictStaysHaltedUntilReset() {
        val guard = StreamErrorCascadeGuard(threshold = 3)
        guard.onError(); guard.onError(); guard.onError()  // Halt
        val verdict = guard.onError()
        // Still halted — 4th error after a halt also halts.
        assertThat(verdict).isEqualTo(StreamErrorCascadeGuard.Verdict.Halt)
    }
}
```

- [ ] **Step 2: Run test to verify failure**

```bash
./gradlew :core:media:testDebugUnitTest --tests "*StreamErrorCascadeGuard*"
```
Expected: FAIL — class doesn't exist.

- [ ] **Step 3: Implement the class**

`core/media/src/main/kotlin/com/stash/core/media/StreamErrorCascadeGuard.kt`:

```kotlin
package com.stash.core.media

/**
 * Bounds ExoPlayer's skip-next recovery cascade so one upstream outage
 * can't silently drain the queue. Counts consecutive stream errors;
 * after [threshold] consecutive errors with no successful playback or
 * user transport command in between, returns [Verdict.Halt] instead of
 * the usual [Verdict.Recover].
 *
 * Not thread-safe by itself — caller must invoke from the single
 * MediaController callback thread (which is what we already do).
 */
class StreamErrorCascadeGuard(
    private val threshold: Int = 3,
) {
    private var consecutiveErrors: Int = 0

    enum class Verdict { Recover, Halt }

    /** Increment and return whether to recover or halt. */
    fun onError(): Verdict {
        consecutiveErrors += 1
        return if (consecutiveErrors >= threshold) Verdict.Halt else Verdict.Recover
    }

    /** A track actually started playing — backend is alive. */
    fun onPlaybackStarted() {
        consecutiveErrors = 0
    }

    /** User did something deliberate (next/prev/seek/play) — rearm. */
    fun onUserTransport() {
        consecutiveErrors = 0
    }
}
```

- [ ] **Step 4: Run test to verify pass**

```bash
./gradlew :core:media:testDebugUnitTest --tests "*StreamErrorCascadeGuard*"
```
Expected: PASS (5/5).

- [ ] **Step 5: Commit**

```bash
git add core/media/src/main/kotlin/com/stash/core/media/StreamErrorCascadeGuard.kt core/media/src/test/kotlin/com/stash/core/media/StreamErrorCascadeGuardTest.kt
git commit -m "$(cat <<'EOF'
feat(media): StreamErrorCascadeGuard for bounded skip-next recovery

Pure-Kotlin testable counter. Tracks consecutive stream errors; halts
the cascade after threshold reached. Reset on successful playback or
user-initiated transport command.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 5: StreamingHaltedEvent + flow plumbing

**Files:**
- Create: `core/media/src/main/kotlin/com/stash/core/media/StreamingHaltedEvent.kt`
- Modify: `core/media/src/main/kotlin/com/stash/core/media/PlayerRepository.kt`

- [ ] **Step 1: Create event type**

```kotlin
package com.stash.core.media

/**
 * Emitted by [PlayerRepository] when consecutive stream errors hit
 * the cascade-guard threshold. UI surfaces a Snackbar; the player
 * itself is already paused by the time this fires.
 */
data class StreamingHaltedEvent(
    val failingTitle: String?,
    val consecutiveErrorCount: Int,
)
```

- [ ] **Step 2: Expose flow on PlayerRepository interface**

Add to `PlayerRepository.kt`:

```kotlin
val streamingHaltedEvents: kotlinx.coroutines.flow.SharedFlow<StreamingHaltedEvent>
```

- [ ] **Step 3: Build to confirm signature compiles**

```bash
./gradlew :core:media:compileDebugKotlin
```

Will fail because Impl doesn't satisfy the interface yet — that's OK, we wire the Impl in Task 6.

- [ ] **Step 4: Commit (skip if tied with Task 6)**

If Task 6 is being done immediately after, commit them together. Otherwise:

```bash
git add core/media/src/main/kotlin/com/stash/core/media/StreamingHaltedEvent.kt core/media/src/main/kotlin/com/stash/core/media/PlayerRepository.kt
git commit -m "feat(media): add StreamingHaltedEvent flow to PlayerRepository interface

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>"
```

---

### Task 6: Wire cascade guard into PlayerRepositoryImpl

**Files:**
- Modify: `core/media/src/main/kotlin/com/stash/core/media/PlayerRepositoryImpl.kt` (lines around 986, 1031)

- [ ] **Step 1: Add field declarations**

At the top of the class (near other private fields):

```kotlin
private val cascadeGuard = StreamErrorCascadeGuard()
private val _streamingHaltedEvents = MutableSharedFlow<StreamingHaltedEvent>(
    replay = 0,
    extraBufferCapacity = 1,
)
override val streamingHaltedEvents: SharedFlow<StreamingHaltedEvent> =
    _streamingHaltedEvents.asSharedFlow()
```

Imports:
```kotlin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
```

- [ ] **Step 2: Modify onPlayerError to consult the guard**

Replace the existing `onPlayerError` body (around line 1031–1051):

```kotlin
override fun onPlayerError(error: PlaybackException) {
    val controller = controllerDeferred
    val failingTitle = controller?.currentMediaItem?.mediaMetadata?.title
    val isIoError = error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ||
        error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED

    if (!isIoError) {
        // Non-IO errors (decoder, unsupported codec, etc.) are per-track and
        // shouldn't count against the cascade window. Recover unconditionally.
        Log.w(TAG, "onPlayerError: '$failingTitle' code=${error.errorCode} (${error.errorCodeName}) — skip-next", error)
        controller?.recoverOrStop()
        return
    }

    val verdict = cascadeGuard.onError()
    Log.w(
        TAG,
        "onPlayerError: '$failingTitle' code=${error.errorCode} (${error.errorCodeName}) — verdict=$verdict",
        error,
    )

    when (verdict) {
        StreamErrorCascadeGuard.Verdict.Recover -> controller?.recoverOrStop()
        StreamErrorCascadeGuard.Verdict.Halt -> {
            controller?.pause()
            _streamingHaltedEvents.tryEmit(
                StreamingHaltedEvent(failingTitle = failingTitle?.toString(), consecutiveErrorCount = 3),
            )
        }
    }
}

private fun MediaController.recoverOrStop() {
    if (hasNextMediaItem()) {
        seekToNextMediaItem()
        prepare()
        play()
    } else {
        stop()
    }
}
```

- [ ] **Step 3: Reset guard on playback success and user transport**

Find `onPlaybackStateChanged` (~line 986). Add:

```kotlin
override fun onPlaybackStateChanged(playbackState: Int) {
    if (playbackState == Player.STATE_READY) {
        val controller = controllerDeferred
        if (controller != null && controller.isPlaying) {
            cascadeGuard.onPlaybackStarted()
        }
    }
    // ...existing logic...
}
```

(Or hook into `onIsPlayingChanged(true)` if simpler — pick whichever matches the rest of the file's style.)

For user transport: add `cascadeGuard.onUserTransport()` to `seekToNext`, `seekToPrevious`, `seekTo`, and any explicit `play()` entry points in this class. Search the file for `seekToNextMediaItem`, `seekToPreviousMediaItem`, `seekTo(` — there are user-initiated wrappers around line 259, 263, 266.

- [ ] **Step 4: Build and run existing tests**

```bash
./gradlew :core:media:compileDebugKotlin :core:media:testDebugUnitTest
```

Expected: PASS (no PlayerRepositoryImpl tests assert on cascade behavior; pure integration).

- [ ] **Step 5: Commit**

```bash
git add core/media/src/main/kotlin/com/stash/core/media/PlayerRepository*.kt core/media/src/main/kotlin/com/stash/core/media/StreamingHaltedEvent.kt
git commit -m "$(cat <<'EOF'
feat(media): bound onPlayerError skip-next cascade via guard

After 3 consecutive IO errors, halts the cascade: pauses the player
and emits StreamingHaltedEvent. Resets on successful playback or any
user-initiated transport command.

Prevents one upstream outage from silently draining a 15+ track
fallback queue in ~30 seconds.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 7: NowPlaying Snackbar surface

**Files:**
- Modify: `feature/nowplaying/src/main/kotlin/com/stash/feature/nowplaying/NowPlayingViewModel.kt`
- Modify: `feature/nowplaying/src/main/kotlin/com/stash/feature/nowplaying/NowPlayingScreen.kt`

- [ ] **Step 1: Subscribe to streamingHaltedEvents in NowPlayingViewModel**

Inject (or re-use the existing injection of) `PlayerRepository`. Add:

```kotlin
val streamingHaltedEvents: Flow<StreamingHaltedEvent> = playerRepository.streamingHaltedEvents
```

(Or shadow it via SharedFlow if there's a layered VM exposure pattern in this codebase — match the existing playerErrors pattern.)

- [ ] **Step 2: Collect in NowPlayingScreen and show Snackbar**

```kotlin
val snackbarHostState = remember { SnackbarHostState() }

LaunchedEffect(Unit) {
    viewModel.streamingHaltedEvents.collect { event ->
        snackbarHostState.showSnackbar(
            message = "Streaming is failing — try a downloaded track or check your connection",
            withDismissAction = true,
        )
    }
}

Scaffold(
    snackbarHost = { SnackbarHost(snackbarHostState) },
    // ... existing content ...
)
```

Wire `SnackbarHostState` into the existing `Scaffold` if one exists; if not, add `SnackbarHost(snackbarHostState)` to the existing root container.

- [ ] **Step 3: Build & sanity test**

```bash
./gradlew :feature:nowplaying:compileDebugKotlin
```

- [ ] **Step 4: Commit**

```bash
git add feature/nowplaying/src/main/kotlin/com/stash/feature/nowplaying/NowPlayingViewModel.kt feature/nowplaying/src/main/kotlin/com/stash/feature/nowplaying/NowPlayingScreen.kt
git commit -m "$(cat <<'EOF'
feat(nowplaying): show Snackbar when streaming cascade halts

When PlayerRepository emits StreamingHaltedEvent, surfaces an in-app
Snackbar. The player itself is already paused; the Snackbar gives
context for "why did the music stop."

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## PHASE 3 — Squid Auto-Refresh

### Task 8: KennyyHealthMonitor (sliding-window TDD)

**Files:**
- Create: `core/media/src/main/kotlin/com/stash/core/media/streaming/KennyyHealthMonitor.kt`
- Create: `core/media/src/test/kotlin/com/stash/core/media/streaming/KennyyHealthMonitorTest.kt`

- [ ] **Step 1: Write failing test**

```kotlin
package com.stash.core.media.streaming

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class KennyyHealthMonitorTest {

    @Test
    fun newMonitorIsHealthy() = runTest {
        val monitor = KennyyHealthMonitor()
        assertThat(monitor.isHealthy.value).isTrue()
    }

    @Test
    fun threeFailuresInWindowMarksUnhealthy() = runTest {
        val monitor = KennyyHealthMonitor()
        repeat(3) { monitor.recordFailure() }
        assertThat(monitor.isHealthy.value).isFalse()
    }

    @Test
    fun twoFailuresInWindowStaysHealthy() = runTest {
        val monitor = KennyyHealthMonitor()
        repeat(2) { monitor.recordFailure() }
        assertThat(monitor.isHealthy.value).isTrue()
    }

    @Test
    fun threeSuccessesAfterUnhealthyRestoresHealth() = runTest {
        val monitor = KennyyHealthMonitor()
        repeat(3) { monitor.recordFailure() }
        assertThat(monitor.isHealthy.value).isFalse()
        repeat(3) { monitor.recordSuccess() }
        assertThat(monitor.isHealthy.value).isTrue()
    }

    @Test
    fun noMatchDoesNotCountAsFailure() = runTest {
        val monitor = KennyyHealthMonitor()
        repeat(10) { monitor.recordNoMatch() }
        assertThat(monitor.isHealthy.value).isTrue()
    }

    @Test
    fun windowSlidesAfterFiveOutcomes() = runTest {
        val monitor = KennyyHealthMonitor()
        // 3 failures → unhealthy
        repeat(3) { monitor.recordFailure() }
        assertThat(monitor.isHealthy.value).isFalse()
        // Push 5 successes through → window now [S, S, S, S, S] → healthy
        repeat(5) { monitor.recordSuccess() }
        assertThat(monitor.isHealthy.value).isTrue()
    }
}
```

- [ ] **Step 2: Run test — should fail (class doesn't exist)**

```bash
./gradlew :core:media:testDebugUnitTest --tests "*KennyyHealthMonitor*"
```

- [ ] **Step 3: Implement**

```kotlin
package com.stash.core.media.streaming

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Sliding-window health monitor for the Kennyy proxy. The
 * SquidCookieAutoRefresher consults [isHealthy] to decide whether
 * to keep the Squid cookie warm in the background.
 *
 * "Failure" means a proxy-level distress signal (timeout, 5xx, 4xx).
 * "No match" — track legitimately absent from Qobuz catalog — does NOT
 * count as a failure since it's a per-track miss, not a proxy outage.
 *
 * State is transient (process-lifetime). After a restart we start
 * healthy and let the next ~5 resolves re-establish reality.
 */
@Singleton
class KennyyHealthMonitor @Inject constructor() {

    private enum class Outcome { Success, Failure }

    private val window = ArrayDeque<Outcome>(WINDOW_SIZE)
    private val _isHealthy = MutableStateFlow(true)
    val isHealthy: StateFlow<Boolean> = _isHealthy.asStateFlow()

    @Synchronized
    fun recordSuccess() = record(Outcome.Success)

    @Synchronized
    fun recordFailure() = record(Outcome.Failure)

    /** No match for the track in Qobuz catalog. Not a proxy distress signal. */
    fun recordNoMatch() { /* intentionally no-op */ }

    private fun record(outcome: Outcome) {
        window.addLast(outcome)
        if (window.size > WINDOW_SIZE) window.removeFirst()
        val failures = window.count { it == Outcome.Failure }
        _isHealthy.value = failures < FAIL_THRESHOLD
    }

    private companion object {
        const val WINDOW_SIZE = 5
        const val FAIL_THRESHOLD = 3
    }
}
```

- [ ] **Step 4: Run tests — should pass**

```bash
./gradlew :core:media:testDebugUnitTest --tests "*KennyyHealthMonitor*"
```
Expected: PASS (6/6).

- [ ] **Step 5: Commit**

```bash
git add core/media/src/main/kotlin/com/stash/core/media/streaming/KennyyHealthMonitor.kt core/media/src/test/kotlin/com/stash/core/media/streaming/KennyyHealthMonitorTest.kt
git commit -m "$(cat <<'EOF'
feat(streaming): KennyyHealthMonitor sliding-window health signal

5-attempt window, unhealthy at ≥3 failures, recovers on ≥3 successes.
"No match" excluded — catalog misses aren't a proxy outage.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 9: Plumb KennyyHealthMonitor into KennyyStreamResolver

**Files:**
- Modify: `core/media/src/main/kotlin/com/stash/core/media/streaming/KennyyStreamResolver.kt`

- [ ] **Step 1: Inject monitor**

```kotlin
@Singleton
class KennyyStreamResolver @Inject constructor(
    private val source: KennyySource,
    private val healthMonitor: KennyyHealthMonitor,
) {
```

- [ ] **Step 2: Distinguish failure modes**

The challenge: `source.resolveImmediate(query)` returns null for both "request failed" and "no match." We need to differentiate.

**Approach** (minimal API change): KennyySource already logs failure reasons (from Phase 1). Add a `lastFailureWasNetwork: Boolean` flag on KennyySource that's set right before returning null due to network/HTTP error, and cleared on entry. The resolver reads it after the call.

In `KennyySource.kt`:

```kotlin
@Volatile var lastResolveFailedNetwork: Boolean = false
    private set

private suspend fun resolveInternal(...): SourceResult? {
    lastResolveFailedNetwork = false
    // ... existing logic ...
    // In the catch block (around line 173):
    } catch (e: Exception) {
        lastResolveFailedNetwork = true
        Log.w(TAG, "failed reason=network '${query.title}'", e)
        return null
    }
}
```

In `KennyyStreamResolver.resolve()`:

```kotlin
val result = source.resolveImmediate(query)
if (result == null) {
    if (source.lastResolveFailedNetwork) {
        healthMonitor.recordFailure()
    } else {
        healthMonitor.recordNoMatch()
    }
    return null
}
val etspMs = parseEtspMs(result.downloadUrl)
if (etspMs == null) {
    // Treat unparseable URL as a proxy-side anomaly worth signaling.
    healthMonitor.recordFailure()
    return null
}
healthMonitor.recordSuccess()
return StreamUrl(/* ... */)
```

- [ ] **Step 3: Update existing tests**

`KennyyStreamResolverTest` — constructor now takes a monitor. Inject a mock:

```kotlin
private val fakeKennyy: KennyySource = mockk(relaxed = true)
private val monitor: KennyyHealthMonitor = mockk(relaxed = true)
private val resolver = KennyyStreamResolver(fakeKennyy, monitor)
```

Add two regression tests:

```kotlin
@Test
fun resolve_recordsSuccess_whenUrlValid() = runTest {
    coEvery { fakeKennyy.resolveImmediate(any()) } returns stubSourceResult(/* valid etsp */)
    resolver.resolve(stubTrack())
    verify { monitor.recordSuccess() }
}

@Test
fun resolve_recordsFailure_whenSourceFlagsNetworkFailure() = runTest {
    coEvery { fakeKennyy.resolveImmediate(any()) } returns null
    every { fakeKennyy.lastResolveFailedNetwork } returns true
    resolver.resolve(stubTrack())
    verify { monitor.recordFailure() }
}

@Test
fun resolve_recordsNoMatch_whenSourceReturnsNullWithoutNetworkFailure() = runTest {
    coEvery { fakeKennyy.resolveImmediate(any()) } returns null
    every { fakeKennyy.lastResolveFailedNetwork } returns false
    resolver.resolve(stubTrack())
    verify { monitor.recordNoMatch() }
}
```

- [ ] **Step 4: Run tests**

```bash
./gradlew :core:media:testDebugUnitTest --tests "*KennyyStreamResolver*" :data:download:compileDebugKotlin
```

Expected: PASS (all existing + 3 new).

- [ ] **Step 5: Commit**

```bash
git add core/media/src/main/kotlin/com/stash/core/media/streaming/KennyyStreamResolver.kt core/media/src/test/kotlin/com/stash/core/media/streaming/KennyyStreamResolverTest.kt data/download/src/main/kotlin/com/stash/data/download/lossless/kennyy/KennyySource.kt
git commit -m "$(cat <<'EOF'
feat(streaming): plumb Kennyy outcomes into KennyyHealthMonitor

Resolver now records success/failure/no-match per attempt. KennyySource
gains lastResolveFailedNetwork flag to disambiguate "request failed"
from "no match in catalog" — only the former counts toward the
sliding-window health signal.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 10: LosslessSourcePreferences — track cookie set time

**Files:**
- Modify: `data/download/src/main/kotlin/com/stash/data/download/lossless/LosslessSourcePreferences.kt`

- [ ] **Step 1: Add cookie-set-time field**

Find the existing `captchaCookieKey` declaration (line ~49). Below it add:

```kotlin
private val captchaCookieSetAtKey = stringPreferencesKey("squid_wtf_captcha_set_at_ms")
```

Add the Flow accessor next to the existing `captchaCookieValue` Flow:

```kotlin
val captchaCookieSetAtMs: Flow<Long> = context.losslessDataStore.data.map { prefs ->
    prefs[captchaCookieSetAtKey]?.toLongOrNull() ?: 0L
}
```

- [ ] **Step 2: Update the cookie setter to write the timestamp**

Find the existing `suspend fun setCaptchaCookieValue(value: String?)` (grep). Modify it:

```kotlin
suspend fun setCaptchaCookieValue(value: String?) {
    val now = System.currentTimeMillis()
    context.losslessDataStore.edit { prefs ->
        if (value.isNullOrEmpty()) {
            prefs.remove(captchaCookieKey)
            prefs.remove(captchaCookieSetAtKey)
        } else {
            prefs[captchaCookieKey] = value
            prefs[captchaCookieSetAtKey] = now.toString()
        }
    }
}
```

- [ ] **Step 3: Build & commit**

```bash
./gradlew :data:download:compileDebugKotlin
git add data/download/src/main/kotlin/com/stash/data/download/lossless/LosslessSourcePreferences.kt
git commit -m "$(cat <<'EOF'
feat(streaming): persist captcha cookie set-at timestamp

SquidCookieAutoRefresher needs cookie age to schedule the next
refresh (~25 min after set). Stored as a sibling DataStore key so
the schema migration is automatic.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 11: HeadlessSquidCaptchaSolver

**Files:**
- Create: `data/download/src/main/kotlin/com/stash/data/download/lossless/squid/HeadlessSquidCaptchaSolver.kt`

This task can't be unit-tested cleanly (depends on a real WebView + a live squid.wtf). It's a thin wrapper around the existing `SquidWtfCaptchaScreen.buildWebView` mechanic. Manual verification via the existing UI happens after Task 13 wires it up.

- [ ] **Step 1: Implement**

```kotlin
package com.stash.data.download.lossless.squid

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Spins up an offscreen [WebView], navigates to squid.wtf, lets the
 * site's own ALTCHA solver run, and harvests the `captcha_verified_at`
 * cookie. Returns the cookie value on success or null on timeout/failure.
 *
 * Must be called from the main thread — WebView APIs require it. The
 * suspend function handles the dispatcher switch internally.
 */
@Singleton
class HeadlessSquidCaptchaSolver @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    suspend fun solve(timeoutMs: Long = DEFAULT_TIMEOUT_MS): String? = withContext(Dispatchers.Main) {
        Log.d(TAG, "solve start (timeoutMs=$timeoutMs)")
        val webView = buildWebView()
        try {
            withTimeoutOrNull(timeoutMs) {
                webView.loadUrl(SQUID_WTF_URL)
                pollForCookie()
            }
        } finally {
            webView.cleanup()
        }
    }

    private suspend fun pollForCookie(): String? {
        while (true) {
            delay(POLL_INTERVAL_MS)
            if (!kotlinx.coroutines.currentCoroutineContext().isActive) return null
            val cookies = CookieManager.getInstance().getCookie(SQUID_WTF_URL)
            val match = COOKIE_REGEX.find(cookies.orEmpty())
            if (match != null) {
                Log.d(TAG, "solve success (cookie len=${match.groupValues[1].length})")
                return match.groupValues[1]
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildWebView(): WebView {
        CookieManager.getInstance().setAcceptCookie(true)
        return WebView(context).apply {
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = true
            }
            webViewClient = WebViewClient()
        }
    }

    private fun WebView.cleanup() {
        stopLoading()
        loadUrl("about:blank")
        clearHistory()
        destroy()
    }

    private companion object {
        const val TAG = "HeadlessSquidSolver"
        const val SQUID_WTF_URL = "https://qobuz.squid.wtf/"
        const val POLL_INTERVAL_MS = 500L
        const val DEFAULT_TIMEOUT_MS = 30_000L
        val COOKIE_REGEX = Regex("captcha_verified_at=([^;\\s]+)")
    }
}
```

**Note on the ALTCHA trigger:** the visible screen's instructions say "tap Download on a track" to trigger the captcha. The headless variant relies on the same site flow — when no user interaction happens, the site may not solve on load. **If the cookie doesn't appear after timeout in manual testing, add JS to programmatically click the Download button in step 2 of Task 13's verification.** Document the JS injection that worked in the commit body.

- [ ] **Step 2: Build & commit**

```bash
./gradlew :data:download:compileDebugKotlin
git add data/download/src/main/kotlin/com/stash/data/download/lossless/squid/HeadlessSquidCaptchaSolver.kt
git commit -m "$(cat <<'EOF'
feat(streaming): HeadlessSquidCaptchaSolver scaffold

Offscreen WebView + cookie polling. JS injection to trigger the
captcha popup will be added during integration testing if loading
the page alone doesn't trigger the ALTCHA challenge.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 12: SquidCookieAutoRefresher

**Files:**
- Create: `data/download/src/main/kotlin/com/stash/data/download/lossless/squid/SquidCookieAutoRefresher.kt`
- Create: `data/download/src/test/kotlin/com/stash/data/download/lossless/squid/SquidCookieAutoRefresherTest.kt`

The auto-refresher's state machine is testable; the WebView part is delegated to `HeadlessSquidCaptchaSolver` (mockable).

- [ ] **Step 1: Failing test**

```kotlin
package com.stash.data.download.lossless.squid

import com.google.common.truth.Truth.assertThat
import com.stash.core.media.streaming.KennyyHealthMonitor
import com.stash.data.download.lossless.LosslessSourcePreferences
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SquidCookieAutoRefresherTest {

    @Test
    fun doesNotRefresh_whenKennyyHealthy() = runTest {
        val solver: HeadlessSquidCaptchaSolver = mockk(relaxed = true)
        val prefs: LosslessSourcePreferences = mockk(relaxed = true) {
            coEvery { captchaCookieSetAtMs } returns flowOf(System.currentTimeMillis() - 30 * 60_000L)
        }
        val monitor = KennyyHealthMonitor()  // starts healthy

        val refresher = SquidCookieAutoRefresher(solver, monitor, prefs, this)
        refresher.start()
        advanceTimeBy(60_000L)
        runCurrent()

        coVerify(exactly = 0) { solver.solve(any()) }
    }

    @Test
    fun refreshesImmediately_whenKennyyUnhealthyAndCookieStale() = runTest {
        val solver: HeadlessSquidCaptchaSolver = mockk(relaxed = true) {
            coEvery { solve(any()) } returns "new-cookie-value"
        }
        val prefs: LosslessSourcePreferences = mockk(relaxed = true) {
            coEvery { captchaCookieSetAtMs } returns flowOf(0L)  // never set
        }
        val monitor = KennyyHealthMonitor()
        repeat(3) { monitor.recordFailure() }  // mark unhealthy

        val refresher = SquidCookieAutoRefresher(solver, monitor, prefs, this)
        refresher.start()
        runCurrent()

        coVerify(exactly = 1) { solver.solve(any()) }
        coVerify(exactly = 1) { prefs.setCaptchaCookieValue("new-cookie-value") }
    }
}
```

- [ ] **Step 2: Run — fail (class missing)**

- [ ] **Step 3: Implement**

```kotlin
package com.stash.data.download.lossless.squid

import android.util.Log
import com.stash.core.media.streaming.KennyyHealthMonitor
import com.stash.data.download.lossless.LosslessSourcePreferences
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Background refresher for the squid.wtf captcha cookie. Active only
 * when Kennyy is unhealthy (so we don't burn ALTCHA solves we won't
 * use) and the app is in the ProcessLifecycle STARTED window (caller
 * controls via [start] / [stop]).
 *
 * Cadence derived from cookie age: refreshes at age+25min. Hardcoded
 * 25-min window leaves a 5-min safety margin against squid.wtf's
 * 30-min sliding cookie expiry.
 *
 * Failure ladder:
 *  - Single solve failure → retry in 60s
 *  - Two consecutive failures → stop refresh loop, rely on existing
 *    CaptchaExpiredNotifier to nag the user
 */
@Singleton
class SquidCookieAutoRefresher @Inject constructor(
    private val solver: HeadlessSquidCaptchaSolver,
    private val healthMonitor: KennyyHealthMonitor,
    private val prefs: LosslessSourcePreferences,
    private val scope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main),
) {
    private var job: Job? = null
    private var consecutiveFailures: Int = 0

    fun start() {
        if (job?.isActive == true) return
        Log.d(TAG, "start")
        job = scope.launch {
            // Observe (health, cookieAge) and refresh on demand.
            combine(
                healthMonitor.isHealthy,
                prefs.captchaCookieSetAtMs,
            ) { healthy, setAtMs -> healthy to setAtMs }
                .collect { (healthy, setAtMs) ->
                    if (healthy) {
                        Log.d(TAG, "Kennyy healthy — sleeping")
                        return@collect
                    }
                    val age = System.currentTimeMillis() - setAtMs
                    if (age >= COOKIE_REFRESH_AGE_MS || setAtMs == 0L) {
                        refresh()
                    } else {
                        val waitMs = COOKIE_REFRESH_AGE_MS - age
                        Log.d(TAG, "cookie is fresh — sleeping ${waitMs}ms")
                        delay(waitMs)
                        refresh()
                    }
                }
        }
    }

    fun stop() {
        Log.d(TAG, "stop")
        job?.cancel()
        job = null
    }

    private suspend fun refresh() {
        Log.d(TAG, "refresh attempt")
        val newCookie = solver.solve()
        if (newCookie != null) {
            prefs.setCaptchaCookieValue(newCookie)
            consecutiveFailures = 0
            Log.d(TAG, "refresh success")
        } else {
            consecutiveFailures += 1
            Log.w(TAG, "refresh failure ($consecutiveFailures consecutive)")
            if (consecutiveFailures >= MAX_FAILURES) {
                Log.w(TAG, "max failures reached, halting auto-refresh")
                stop()
            } else {
                delay(FAILURE_RETRY_DELAY_MS)
            }
        }
    }

    private companion object {
        const val TAG = "SquidAutoRefresher"
        const val COOKIE_REFRESH_AGE_MS = 25 * 60_000L
        const val FAILURE_RETRY_DELAY_MS = 60_000L
        const val MAX_FAILURES = 2
    }
}
```

- [ ] **Step 4: Run tests**

```bash
./gradlew :data:download:testDebugUnitTest --tests "*SquidCookieAutoRefresher*"
```
Expected: PASS (2/2).

- [ ] **Step 5: Commit**

```bash
git add data/download/src/main/kotlin/com/stash/data/download/lossless/squid/SquidCookieAutoRefresher.kt data/download/src/test/kotlin/com/stash/data/download/lossless/squid/SquidCookieAutoRefresherTest.kt
git commit -m "$(cat <<'EOF'
feat(streaming): SquidCookieAutoRefresher background re-auth

Watches (KennyyHealthMonitor.isHealthy, cookie age). When Kennyy is
unhealthy AND cookie is stale or absent, triggers a headless solve
and persists the result. Backs off after two consecutive failures
and lets the existing CaptchaExpiredNotifier surface manual recovery.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 13: Wire into Application lifecycle

**Files:**
- Modify: `app/src/main/kotlin/com/stash/app/StashApplication.kt`

- [ ] **Step 1: Inject + hook ProcessLifecycle**

Add to `StashApplication`:

```kotlin
@Inject lateinit var squidAutoRefresher: SquidCookieAutoRefresher

override fun onCreate() {
    super.onCreate()
    // ... existing setup ...

    androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.addObserver(
        object : androidx.lifecycle.DefaultLifecycleObserver {
            override fun onStart(owner: androidx.lifecycle.LifecycleOwner) {
                squidAutoRefresher.start()
            }
            override fun onStop(owner: androidx.lifecycle.LifecycleOwner) {
                squidAutoRefresher.stop()
            }
        }
    )
}
```

- [ ] **Step 2: Manual smoke test**

Build, install, exercise:

```bash
./gradlew :app:installDebug
adb logcat -c
adb logcat | grep -E "SquidAutoRefresher|HeadlessSquidSolver|KennyyHealthMonitor"
```

Force Kennyy unhealthy by toggling network or letting natural failures accumulate, then watch for:
- `SquidAutoRefresher: refresh attempt`
- `HeadlessSquidSolver: solve start`
- `HeadlessSquidSolver: solve success` (or timeout → `solve failure`)

If solve times out without harvesting a cookie, the site likely needs a JS-triggered Download click. Add to `HeadlessSquidCaptchaSolver.solve` after `loadUrl(SQUID_WTF_URL)` and a brief settle delay:

```kotlin
delay(2_000L)  // let the bundle hydrate
webView.evaluateJavascript(
    """
    (function() {
        var btn = document.querySelector('button[aria-label*="Download"]')
            || document.querySelector('.download-btn');
        if (btn) btn.click();
    })();
    """.trimIndent(),
    null,
)
```

Iterate until cookie appears reliably; commit the working JS into HeadlessSquidCaptchaSolver.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/com/stash/app/StashApplication.kt data/download/src/main/kotlin/com/stash/data/download/lossless/squid/HeadlessSquidCaptchaSolver.kt
git commit -m "$(cat <<'EOF'
feat(streaming): wire SquidCookieAutoRefresher into ProcessLifecycle

Auto-refresher starts on app foreground / restart and stops when the
app moves to background. JS injection added to trigger ALTCHA without
user interaction.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Final acceptance gate

After Task 13, run the full module test suites:

```bash
./gradlew :core:media:testDebugUnitTest :data:download:testDebugUnitTest :feature:nowplaying:testDebugUnitTest
```

Expected: all PASS, no regressions.

Then `./gradlew :app:assembleDebug` and install on device. Manual scenarios to verify:

1. **Logging.** With Kennyy up, play a track. Logcat shows `KennyySource: resolve attempt …` → `resolved` from both source and resolver layers.
2. **Cascade guard.** Toggle device to airplane mode mid-playback during a YT-routed track. Player pauses after 3 errors, Snackbar appears in NowPlaying.
3. **Auto-refresh.** Disable Kennyy (block `qobuz.kennyy.com.br` in pihole, or wait for natural outage). Watch for `SquidAutoRefresher: refresh attempt` within ~5 resolves. Cookie value in DataStore updates without opening Settings → Squid captcha screen.

---

## Notes for the executor

- **Tests are first.** Phase 1 has no new tests (only logs); Phase 2 and 3 follow strict TDD.
- **Don't refactor `KennyySource` or `QobuzSource` while adding logging.** Keep diffs minimal — these are widely-used files.
- **Don't bundle Tasks 5–6.** The interface change (Task 5) is mechanical; the Impl wiring (Task 6) is the meaty bit. Separate commits make review tractable.
- **HeadlessSquidCaptchaSolver** is the only piece that can't be unit-tested cleanly. Accept the manual verification gate; document the JS injection that worked.
- **No worktree** required for this work — the spec was committed on `feat/extract-coalescing` and these changes layer on top. If the user wants to ship Phase 1+2 separately from Phase 3, the executor should branch after Task 7.
