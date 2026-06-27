# Crossfader (auto-advance only) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Smooth equal-power crossfade on automatic track-end → next-track transitions, toggle off by default with a 1–12 s (default 6 s) duration slider.

**Architecture:** "A owns the queue, B fades the tail." The existing single `ExoPlayer` (player A) stays the sole `MediaSession`/queue owner. A transient, pooled, controller-invisible second `ExoPlayer` (player B) is the fade partner. A new `CrossfadeController` (owned by `StashPlaybackService`) reuses the existing position poll to arm an equal-power volume ramp on auto-advance only. No `ForwardingPlayer` wrapper, no manual-skip crossfade.

**Tech Stack:** Kotlin, Media3/ExoPlayer, Hilt, DataStore (preferences), Jetpack Compose (settings UI), JUnit + MockK (existing test setup).

**Spec:** `docs/superpowers/specs/2026-06-26-crossfader-design.md`

**Reference source (port, do NOT merge wholesale):** branches `feat/crossfader` and `feat/crossfade` contain a prior `CrossfadePreferences.kt` and `PlaybackSection` slider UI. Port those two pieces; ignore their `CrossfadingPlayer`, `CanonicalQueueTimeline`, `CrossfadeClock`, `CrossfadeScheduler` — this design replaces all of that.

---

## File Structure

- **Create** `core/data/src/main/kotlin/com/stash/core/data/prefs/CrossfadePreference.kt` — interface (mirror `StreamingPreference`/`ThemePreference` pattern).
- **Create** `core/data/src/main/kotlin/com/stash/core/data/prefs/CrossfadePreferencesManager.kt` — DataStore impl (toggle + durationMs, clamp 1000–12000, defaults off / 6000).
- **Create** `core/data/src/test/kotlin/com/stash/core/data/prefs/CrossfadePreferencesClampTest.kt`.
- **Create** `core/media/src/main/kotlin/com/stash/core/media/service/CrossfadeFade.kt` — pure helpers (`equalPowerVolumes`, `shouldArm`). Pulled out so the logic is JVM-unit-testable without ExoPlayer.
- **Create** `core/media/src/test/kotlin/com/stash/core/media/service/CrossfadeFadeTest.kt`.
- **Create** `core/media/src/main/kotlin/com/stash/core/media/service/CrossfadeController.kt` — orchestration (owns B, runs the ramp, cancel-on-manual).
- **Modify** `core/media/src/main/kotlin/com/stash/core/media/service/StashPlaybackService.kt` — build B lazily, instantiate controller, feed the position poll + transport events to it.
- **Modify** settings: `feature/settings/.../components/PlaybackSection.kt`, `SettingsViewModel.kt`, `SettingsUiState.kt` — toggle + slider (port from prior branch, defaults off/6s).
- **Modify** DI if needed (`CrossfadePreference` binding) — follow the existing `StreamingPreference` Hilt module pattern.

Build/test on this Windows box: use the Gradle daemon (omit `--no-daemon`), always with a `--tests` filter (see `infra_gradle_no_daemon_bindexception`).

---

## Task 1: CrossfadePreference (toggle + duration, clamped)

**Files:**
- Create: `core/data/src/main/kotlin/com/stash/core/data/prefs/CrossfadePreference.kt`
- Create: `core/data/src/main/kotlin/com/stash/core/data/prefs/CrossfadePreferencesManager.kt`
- Test: `core/data/src/test/kotlin/com/stash/core/data/prefs/CrossfadePreferencesClampTest.kt`

Port the prior branch's `CrossfadePreferences` shape but follow the `StreamingPreference` interface+manager split and change defaults. Read `git show feat/crossfader:core/data/src/main/kotlin/com/stash/core/data/prefs/CrossfadePreferences.kt` for the clamp helper, then adapt.

- [ ] **Step 1: Write the failing clamp test**

```kotlin
// CrossfadePreferencesClampTest.kt
class CrossfadePreferencesClampTest {
    @Test fun `duration clamps below floor to 1000ms`() {
        assertEquals(1000L, clampCrossfadeMs(0L))
        assertEquals(1000L, clampCrossfadeMs(500L))
    }
    @Test fun `duration clamps above ceiling to 12000ms`() {
        assertEquals(12000L, clampCrossfadeMs(99999L))
    }
    @Test fun `duration in range is unchanged`() {
        assertEquals(6000L, clampCrossfadeMs(6000L))
    }
}
```

- [ ] **Step 2: Run it, verify it fails** (`clampCrossfadeMs` undefined).
  Run: `./gradlew :core:data:testDebugUnitTest --tests "*CrossfadePreferencesClampTest"`

- [ ] **Step 3: Implement** the interface + manager + `clampCrossfadeMs` top-level fn (floor 1000, ceil 12000). DataStore keys `crossfade_enabled` (Boolean, default `false`) and `crossfade_duration_ms` (Long, default `6000`), with a `ReplaceFileCorruptionHandler { emptyPreferences() }` like the other managers.

- [ ] **Step 4: Run test, verify pass.**

- [ ] **Step 5: Wire Hilt binding** for `CrossfadePreference` following the `StreamingPreference` module pattern; build `:core:data` to confirm it compiles.

- [ ] **Step 6: Commit** `feat(prefs): CrossfadePreference (off by default, 1-12s, default 6s)`

---

## Task 2: Pure fade logic (equal-power + arm decision)

**Files:**
- Create: `core/media/src/main/kotlin/com/stash/core/media/service/CrossfadeFade.kt`
- Test: `core/media/src/test/kotlin/com/stash/core/media/service/CrossfadeFadeTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
class CrossfadeFadeTest {
    @Test fun `equal power endpoints`() {
        val (out0, in0) = equalPowerVolumes(0f)
        assertEquals(1f, out0, 0.001f); assertEquals(0f, in0, 0.001f)
        val (out1, in1) = equalPowerVolumes(1f)
        assertEquals(0f, out1, 0.001f); assertEquals(1f, in1, 0.001f)
    }
    @Test fun `equal power is constant power`() {
        for (t in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            val (o, i) = equalPowerVolumes(t)
            assertEquals("power at t=$t", 1f, o*o + i*i, 0.001f)
        }
    }
    @Test fun `arm only when all conditions hold`() {
        val base = ArmInputs(enabled = true, repeatOne = false, hasResolvedNext = true,
            remainingMs = 5000, trackDurationMs = 200_000, crossfadeMs = 6000)
        assertTrue(shouldArm(base))
        assertFalse(shouldArm(base.copy(enabled = false)))
        assertFalse(shouldArm(base.copy(repeatOne = true)))
        assertFalse(shouldArm(base.copy(hasResolvedNext = false)))
        assertFalse("remaining > duration", shouldArm(base.copy(remainingMs = 7000)))
        assertFalse("track too short", shouldArm(base.copy(trackDurationMs = 10_000)))
    }
}
```

- [ ] **Step 2: Run, verify fail.**
  Run: `./gradlew :core:media:testDebugUnitTest --tests "*CrossfadeFadeTest"`

- [ ] **Step 3: Implement** `CrossfadeFade.kt`:

```kotlin
import kotlin.math.cos
import kotlin.math.sin

/** (outgoing, incoming) gain for ramp progress t in [0,1]; equal-power. */
fun equalPowerVolumes(t: Float): Pair<Float, Float> {
    val c = t.coerceIn(0f, 1f)
    val rad = c * (Math.PI.toFloat() / 2f)
    return cos(rad) to sin(rad)
}

data class ArmInputs(
    val enabled: Boolean,
    val repeatOne: Boolean,
    val hasResolvedNext: Boolean,
    val remainingMs: Long,
    val trackDurationMs: Long,
    val crossfadeMs: Long,
)

/** Arm the fade only when every condition holds (see spec §Trigger). */
fun shouldArm(i: ArmInputs): Boolean =
    i.enabled &&
    !i.repeatOne &&
    i.hasResolvedNext &&
    i.trackDurationMs > 2 * i.crossfadeMs &&
    i.remainingMs in 1..i.crossfadeMs
```

- [ ] **Step 4: Run, verify pass.**

- [ ] **Step 5: Commit** `feat(media): pure equal-power + arm-decision helpers for crossfade`

---

## Task 3: CrossfadeController (orchestration)

**Files:**
- Create: `core/media/src/main/kotlin/com/stash/core/media/service/CrossfadeController.kt`

This is the integration core — it drives two ExoPlayers, so it's verified on-device (Task 5), not unit-tested. Keep it one focused class.

Responsibilities:
- Hold `playerA` (passed in), a lazily-built `playerB` (built by the service via a shared builder; B has `handleAudioFocus = false`, same `StashRenderersFactory(eqController, loudnessController)`, same `audioAttributes`/`loadControl`/`mediaSourceFactory`).
- Cache `enabled` + `durationMs` from a `CrossfadePreference` Flow collector (mark `@Volatile`).
- `onProgress(positionMs, durationMs, hasResolvedNext, repeatMode)`: build `ArmInputs`, and if `shouldArm` and not already fading → start the fade.
- `startFade()`:
  1. Pre-buffer B with the **outgoing** MediaItem (the item A is currently playing), `B.seekTo(currentPositionMs)`, `B.volume = 1f`, `B.prepare()`, `B.playWhenReady = true`.
  2. `playerA.seekToNextMediaItem()`; `playerA.volume = 0f`.
  3. Launch a coroutine ramp on the playback dispatcher: for `t` 0→1 over `durationMs` stepping ~50ms, `(out, inc) = equalPowerVolumes(t)`, set `B.volume = out`, `playerA.volume = inc`.
  4. On completion: `B.stop()`, `B.clearMediaItems()`, `playerA.volume = 1f`, clear `fading` flag.
- `cancelFade()`: cancel the ramp coroutine, `B.stop()/clearMediaItems()`, `playerA.volume = 1f`, clear flag. Called by the service on manual next/previous/seek and on `PlaybackException`.
- `onPause()/onResume()`: propagate to B while fading (`B.playWhenReady = ...`).
- `release()`: release B.

- [ ] **Step 1:** Implement `CrossfadeController.kt` per the responsibilities above, using `equalPowerVolumes`/`shouldArm` from Task 2. Guard all B operations behind a `fading` flag and null-safe B access.
- [ ] **Step 2:** Build the module: `./gradlew :core:media:compileDebugKotlin` — confirm it compiles.
- [ ] **Step 3: Commit** `feat(media): CrossfadeController — pooled player B + equal-power ramp`

---

## Task 4: Wire into StashPlaybackService

**Files:**
- Modify: `core/media/src/main/kotlin/com/stash/core/media/service/StashPlaybackService.kt`

- [ ] **Step 1:** Extract A's `ExoPlayer.Builder` construction (currently ~`:257`) into a private `buildExoPlayer(handleAudioFocus: Boolean): ExoPlayer` helper so A and B share identical config. A keeps `handleAudioFocus = true`; B will pass `false`.
- [ ] **Step 2:** Inject `CrossfadePreference`; instantiate `CrossfadeController` after A is built, lazily building B via `buildExoPlayer(handleAudioFocus = false)` on first fade.
- [ ] **Step 3:** In `startPrefetchPoll` (~`:408`), after the existing `prefetchOrchestrator.onProgress(...)` call, also call `crossfadeController.onProgress(positionMs = player.currentPosition, durationMs = player.duration, hasResolvedNext = <next item has a resolved http(s) uri or is downloaded>, repeatMode = player.repeatMode)`. Determine `hasResolvedNext` from the next MediaItem's URI (non-placeholder).
- [ ] **Step 4:** Add a `Player.Listener` (or extend the existing one) so manual `onMediaItemTransition(reason = SEEK_*/PLAYLIST_CHANGED)` not caused by the fade, plus `onPlayerError`, call `crossfadeController.cancelFade()`. Distinguish the controller's own `seekToNextMediaItem` (set a short-lived "self-initiated" flag in `startFade`) from a user skip so the fade isn't cancelled by its own seek.
- [ ] **Step 5:** Call `crossfadeController.release()` in the service's player-release path.
- [ ] **Step 6:** Build the APK: `./gradlew :app:assembleDebug` — confirm green.
- [ ] **Step 7: Commit** `feat(media): wire CrossfadeController into StashPlaybackService`

---

## Task 5: Settings UI (toggle + slider)

**Files:**
- Modify: `feature/settings/.../components/PlaybackSection.kt`, `SettingsViewModel.kt`, `SettingsUiState.kt`

Port the toggle + duration slider from `git show feat/crossfader:.../components/PlaybackSection.kt` (and the matching ViewModel/UiState wiring), adapting to the current files and the off/1-12s/6s defaults. Slider maps 1000–12000 ms; label shows seconds.

- [ ] **Step 1:** Add `crossfadeEnabled: Boolean` + `crossfadeDurationMs: Long` to `SettingsUiState`, fed from `CrossfadePreference` in `SettingsViewModel`, with `setCrossfadeEnabled`/`setCrossfadeDurationMs` actions (clamp via `clampCrossfadeMs`).
- [ ] **Step 2:** Add the toggle + slider (slider enabled only when toggle on) to the Playback section, mirroring the existing rows' style.
- [ ] **Step 3:** Build `:app:assembleDebug` — green.
- [ ] **Step 4: Commit** `feat(settings): crossfade toggle + duration slider (Playback)`

---

## Task 6: On-device verification (manual)

No code; this is the acceptance gate for the seam the spec flagged. Use the connected device (`adb`), follow `feedback_run_adb_directly` and `feedback_install_after_fix`.

- [ ] Install: `./gradlew :app:installDebug`.
- [ ] Toggle **off** (default): confirm playback is unchanged — auto-advance hard-cuts as today.
- [ ] Toggle **on**, 6s: queue 2+ streamed tracks, let one auto-advance → audible equal-power crossfade, no stutter/gap at the A↔B hand-off, no double-audio, no volume left stuck below 1.
- [ ] Repeat with **downloaded** tracks.
- [ ] **Manual skip** (next/prev) → hard cut, no fade; skipping mid-fade cleanly cancels (no lingering B audio, A returns to full volume).
- [ ] Notification / lockscreen / Bluetooth metadata stays correct across the transition; EQ still audibly applies through the fade.
- [ ] Last track / repeat-one: no crossfade, clean end.
- [ ] **If the A↔B tail hand-off stutters:** apply the spec's fallback (A plays the outgoing tail to its natural end, B fades in the incoming track from 0, A re-seeks into the incoming track at fade end) and re-verify.
- [ ] Commit any fixes found during verification.

---

## Done when

All tasks committed, `:app:assembleDebug` green, the three unit tests pass, and on-device: crossfade works on auto-advance at the chosen duration, toggle-off is byte-for-byte today's behavior, and manual skips hard-cut.
