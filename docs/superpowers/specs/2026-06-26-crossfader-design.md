# Crossfader (auto-advance only) — Design Spec

**Date:** 2026-06-26
**Status:** Design
**Related:** GitHub issue #16 (juv3nal). Supersedes the prior `2026-05-02-crossfader-design.md` / `2026-04-24-crossfade-design.md` work on branches `feat/crossfader` and `feat/crossfade` — those covered manual-skip crossfade and a player-rotation wrapper, which this design deliberately drops.

## Problem

Track-to-track playback hard-cuts: when one track ends and the next begins, audio stops abruptly and the next starts at full volume. Users have repeatedly asked for a smooth crossfade. Stash builds one `ExoPlayer` (`StashPlaybackService.kt:257`) and has no facility to overlap two streams.

## Goals

- Smooth equal-power audio crossfade on **automatic** track-end → next-track transitions.
- User-controllable: a Settings toggle (**off by default**) and a duration slider (**1–12 s, default 6 s**).
- True overlap (both tracks audible, fading out / in simultaneously) — not fade-to-silence-then-fade-in.
- Preserve every existing behavior: queue, MediaSession metadata (notification / lockscreen / Bluetooth / Android Auto), the renderer-level Stash EQ + loudness chain, audio-focus / becoming-noisy handling, `PlaybackException` auto-recovery, shuffle, repeat.
- **Zero behavior change when the toggle is off** — the disabled path is byte-for-byte today's playback.

## Non-goals (explicit — this is where the prior 4,400-line version went)

- **Crossfade on manual skip (next/previous).** Manual transitions stay a hard cut, matching Spotify's default. This removes fade-during-skip, rebound, and cancel-in-flight-fade machinery entirely.
- A `ForwardingPlayer` wrapper that rotates two players + synthesizes a fake queue/timeline.
- Fade-curve options, per-source/per-codec config, replay-gain / loudness matching, gapless dead-air trimming, UI visualization, DJ-style manual mix slider.
- Crossfading the search-tab `PreviewPlayer` (snippets too short).

## Design

### Architecture — "A owns the queue, B fades the tail"

No player wrapper. **Player A** is the existing `ExoPlayer` — unchanged, still the sole `MediaSession` player and queue owner, so notification / lockscreen / Bluetooth / scrobbler / prefetch keep working through the normal single-player path. **Player B** is a second, pooled, *controller-invisible* `ExoPlayer` used only as a transient fade partner.

A small **`CrossfadeController`** (`core/media/src/main/kotlin/com/stash/core/media/service/CrossfadeController.kt`) is owned by `StashPlaybackService`. It holds references to A, a lazily-built B, the `eqController` / `loudnessController`, and the cached `CrossfadePreferences` (enabled + durationMs, read off a Flow collector).

**Player B construction:** same `StashRenderersFactory(eqController, loudnessController)` wiring as A so the fade tail is EQ'd identically; `handleAudioFocus = false` (only A owns focus — two players requesting focus on one session thrashes); the controller propagates A's pause/resume to B. `becomingNoisy` / wake-lock stay on A only.

### Trigger

Reuse the existing position poll (`StashPlaybackService.startPrefetchPoll`, ~250 ms). Each tick, the controller evaluates **arm** conditions — fade only when ALL hold:
1. Crossfade enabled.
2. `repeatMode != REPEAT_MODE_ONE` (never fade a track into itself).
3. A real next track exists in A's timeline **and its stream URL is resolved** (for streamed tracks; the 1-ahead prefetch usually has it). If unresolved → no fade, hard cut.
4. Current track's remaining ≤ `durationMs`.
5. Current track is longer than ~2× `durationMs` (skip on very short tracks).

### Fade mechanic

Equal-power ramp over `durationMs`: outgoing volume `cos(t·π/2)`, incoming `sin(t·π/2)`, `t` from 0→1. Driven by a coroutine loop stepping volumes every ~50 ms on the playback thread (no dedicated scheduler/clock abstraction).

The transient second stream plays the **outgoing tail**: B is pre-buffered with the outgoing track seeked to the current position just before the trigger point; at fire, B plays and ramps **down** while A `seekToNextMediaItem()`s to the incoming track at volume 0 and ramps **up**. On completion: B stops and returns to the pool, A is at full volume on the new track with the queue already advanced.

> **Implementation detail to nail in the plan + verify on-device:** the one delicate seam is the hand-off of the outgoing track's audio from A to B at the trigger instant (B must be buffered and positioned so the outgoing stream doesn't stutter). The plan must pre-buffer B and verify seamlessness on a real device; if the A→B tail hand-off proves glitchy, the fallback is the inverse roles (A plays the outgoing tail to its natural end while B fades in the incoming track from 0, then A re-seeks into the incoming track at fade end) — same queue-ownership guarantee, seam moved to the end.

### Interaction with manual actions

Any manual transport during a pending or in-flight fade **cancels** it: stop B, restore A to full volume, let the manual action proceed as a normal hard cut. This is the entire manual story — `pause` pauses both A and B; `seek` backward disarms a pending trigger; `next`/`previous` cancel + hard-cut.

### Degradation

Whenever a fade can't run cleanly (unresolved next URL, last track, repeat-one, B busy/failed, `PlaybackException`), the controller no-ops and playback falls back to today's hard-cut behavior. Crossfade is always best-effort; it never blocks or fails a transition.

### Settings

Reuse the already-built `CrossfadePreferences` (toggle + duration, clamped) and the `PlaybackSection` slider UI from the prior branches **verbatim**, changing only the defaults to **off / 1–12 s / 6 s**. No new settings surface.

## Testing

Two pieces carry real logic and get one focused unit test each (no framework beyond the existing JVM test setup):
1. **Equal-power volume math** — `cos`/`sin` ramp produces ~constant power, endpoints 1↔0.
2. **Arm decision** — arms only when all five trigger conditions hold; disarms on each negative (disabled, repeat-one, no/unresolved next, insufficient remaining, too-short track).

On-device verification (manual, in the implementation session): audible crossfade on auto-advance at 6 s; toggle-off is unchanged; manual skip hard-cuts; notification/Bluetooth metadata stays correct; EQ applies through the fade.

## Estimated size

~1 new file (`CrossfadeController`, est. ~250 lines) + small `StashPlaybackService` wiring + reused prefs/UI. Versus the prior ~4,400-line implementation.
