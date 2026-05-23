# Narrow-screen UI jank fix (Issue #87) — design

**Date:** 2026-05-23
**Status:** Design — ready for implementation plan
**Issue:** https://github.com/rawnaldclark/Stash/issues/87
**Companion:** [2026-05-23-narrow-screen-ui-jank-investigation.md](./2026-05-23-narrow-screen-ui-jank-investigation.md) — pre-spec diagnosis

## Goal

The Now Playing screen breaks on narrow + scaled displays (Nothing Phone 3a, fontScale ≥ 1.15×, Android 15/16). Fix all four observed issues without restructuring the existing UI — keep the same icons, same album art treatment, same controls layout. The screen should adapt to small/scaled viewports, not redesign for them.

## Confirmed bugs and root causes

### Bug 1 — "NOW PLAYING" title wraps per-letter
- **Where:** `feature/nowplaying/src/main/kotlin/com/stash/feature/nowplaying/NowPlayingScreen.kt:396-402`
- **Cause:** `Text` has `Modifier.weight(1f)` but no `maxLines`/`overflow`. Six `IconButton`s (Dismiss, Flag, Like, Download, Save, Queue) consume ~288dp of 48dp touch targets. On a 393dp screen at fontScale 1.30, the title gets ~100dp of width, and Compose softwraps the 14sp `labelMedium` string per-letter (`N\nO\nW\nP\nL\nA\nY\nI\nN\nG`).
- **Evidence:** Reporter screenshot shows this exact per-letter stack. Code confirms missing `maxLines` / `overflow`.

### Bug 2a — Mini-player + bottom nav tight against gesture pill
- **Where:** `app/src/main/kotlin/com/stash/app/navigation/StashScaffold.kt:82-104`
- **Cause:** The bottomBar slot is `Column { MiniPlayer; StashBottomBar }`. Material3 `NavigationBar` should apply `WindowInsets.navigationBars` by default, but the surrounding `Column` doesn't propagate insets reliably on Android 15+ where edge-to-edge is enforced.
- **Evidence:** Reporter screenshot shows the bottom UI flush with the gesture pill. Hypothesis-confirmed; matches the Scaffold inset comment at `StashScaffold.kt:76-81` noting prior inset issues on edge-to-edge.

### Bug 2b — PlaybackControls hidden behind mini-player
- **Where:** `feature/nowplaying/src/main/kotlin/com/stash/feature/nowplaying/NowPlayingScreen.kt:224-350`
- **Cause:** The inner Column is `.fillMaxSize()` with a `Spacer(weight=1f)` at the bottom (line 349). At fontScale 1.30, intrinsic content height (TopBar + 24sp track title + 280dp album art + 32dp+28dp spacers + scaled text + GlowingProgressBar + PlaybackControls + spacers) exceeds the available content height. The Column doesn't scroll, so children past the bottom bound render outside the Column — and Scaffold's bottomBar overlay (MiniPlayer + NavigationBar) covers them. Result: user can't access play/pause/skip from Now Playing.
- **Evidence:** Reporter screenshot shows progress bar but no PlaybackControls. Code confirms non-scrollable Column with weight-Spacer.
- **New finding:** Not identified in the original investigation doc.

### Bug 3 — "Free-floating red dot" is the album-art accent glow
- **Where:** `feature/nowplaying/src/main/kotlin/com/stash/feature/nowplaying/NowPlayingScreen.kt:489-498`
- **Cause:** `AlbumArtSection` draws `Box(260.dp).shadow(elevation=40.dp, ambientColor/spotColor = accentColor.copy(alpha = 0.5f))` behind a 280dp `AsyncImage`. The shadow extends ~40dp around all sides; on highly saturated accent colors (Lola Young's "Messy" → bright red) the halo dominates and looks like a stray red blob below the artwork. The reporter mistook it for a broken slider thumb.
- **Evidence:** Reporter screenshot shows large red half-dome shape exactly where the album-art shadow would render. Code confirms 0.5α elevation shadow with accent color.
- **New finding:** The original investigation hypothesis ("slider thumb free-floating") was wrong. The GlowingProgressBar's playhead glow is correctly anchored to the playhead position; the "red dot" is a separate, intentional design element that looks bad on red art.

## Fixes

### Fix 1 — Add `maxLines` + ellipsis to toolbar title
**File:** `feature/nowplaying/src/main/kotlin/com/stash/feature/nowplaying/NowPlayingScreen.kt:396-402`

```diff
 Text(
     text = "NOW PLAYING",
     style = MaterialTheme.typography.labelMedium,
     color = Color.White.copy(alpha = 0.7f),
     textAlign = TextAlign.Center,
+    maxLines = 1,
+    overflow = TextOverflow.Ellipsis,
     modifier = Modifier.weight(1f),
 )
```

At fontScale 1.30 on a 393dp screen the title shows `NOW PLA…` instead of stacking per-letter. On wider screens it fits in full as today.

### Fix 2a — Apply `navigationBars` inset to bottom Column
**File:** `app/src/main/kotlin/com/stash/app/navigation/StashScaffold.kt:82-104`

```diff
 bottomBar = {
-    Column {
+    Column(modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars)) {
         MiniPlayer(...)
         StashBottomBar(...)
     }
 },
```

Adds explicit gesture-pill clearance. Replaces the implicit inset handling that the wrapping Column was breaking.

### Fix 2b — Wrap Now Playing Column in `verticalScroll`
**File:** `feature/nowplaying/src/main/kotlin/com/stash/feature/nowplaying/NowPlayingScreen.kt:224-350`

```diff
+val scrollState = rememberScrollState()
 Column(
     modifier = Modifier
         .fillMaxSize()
         .statusBarsPadding()
+        .verticalScroll(scrollState)
         .padding(horizontal = 24.dp),
     horizontalAlignment = Alignment.CenterHorizontally,
 ) {
     // ... existing children (TopBar, Spacer, AlbumArtSection, etc.) ...

     PlaybackControls(...)

-    Spacer(modifier = Modifier.weight(1f))
+    Spacer(modifier = Modifier.height(48.dp))
 }
```

**Trade-off:** `weight()` is invalid in a scrollable Column. The bottom Spacer must be a fixed dp. On tall screens (Pixel 6 Pro at default fontScale), PlaybackControls move slightly upward — they hug the progress bar instead of floating mid-screen. Accepted: tall-screen aesthetics shift slightly so that narrow/scaled screens become usable.

### Fix 3 — Reduce album-art glow alpha
**File:** `feature/nowplaying/src/main/kotlin/com/stash/feature/nowplaying/NowPlayingScreen.kt:489-498`

```diff
 Box(
     modifier = Modifier
         .size(260.dp)
         .shadow(
             elevation = 40.dp,
             shape = RoundedCornerShape(20.dp),
-            ambientColor = accentColor.copy(alpha = 0.5f),
-            spotColor = accentColor.copy(alpha = 0.5f),
+            ambientColor = accentColor.copy(alpha = 0.25f),
+            spotColor = accentColor.copy(alpha = 0.25f),
         ),
 )
```

Halo stays the same shape and elevation, just half as saturated. Red-themed albums no longer look like they have a stray UI element below the art. On muted album colors the halo is now nearly invisible — acceptable trade for not looking broken on bright art.

## Out of scope (intentionally)

- **Toolbar restructure** (overflow menu, dropped title, fewer icons) — the existing 6-icon layout is the design; only its text-wrap behavior needed fixing.
- **Mini-player visibility on Now Playing screen** — the mini-player + bottom nav appear under Now Playing because the screen is a route inside the Scaffold's content slot. Hiding them while Now Playing is visible is a separate architectural change.
- **GlowingProgressBar refactor** — the playhead glow renders correctly; only the album-art shadow is being adjusted.
- **Audit of other screens** for similar overflow risk — separate work item, not in this fix.

## Verification

### Emulator (Medium_Phone_API_36.1, Android 16, fontScale 1.30)
1. `adb shell settings put system font_scale 1.30`
2. Install `:app:installDebug`.
3. Open the app, play any track, expand to Now Playing.
4. Confirm: title reads `NOW PLA…` (or full "NOW PLAYING" if room), PlaybackControls visible (scroll if needed), bottom nav has visible gap from gesture pill, album-art glow is a subtle halo (no red blob).

### Physical device (Pixel 6 Pro, default fontScale)
1. Install `:app:installDebug`.
2. Open Now Playing with a track.
3. Confirm no regression: full "NOW PLAYING" still shown, controls visible without scrolling on most tracks, bottom inset still respected, album-art glow visible but subtler than before.

### What we're NOT testing
No automated UI/screenshot tests — pure layout adjustment, no logic changes, manual verification is sufficient.

## Risks

- **Pixel 6 Pro tall-screen shift** — PlaybackControls reposition upward by ~weight-spacer-height. Tested visually; acceptable.
- **Glow under-visibility on muted albums** — at α=0.25 the halo on dark/muted accent colors may become invisible. Side effect of the same fix that solves the red-blob case. If users complain, alpha can be re-tuned upward (e.g., 0.30) without redesign.
- **`navigationBarsPadding` interaction with existing Scaffold insets** — the Scaffold's content slot already consumes `innerPadding`. Adding `windowInsetsPadding(navigationBars)` on the bottomBar Column should not double-pad because the bottomBar slot receives raw window state, not innerPadding. Verify on emulator before shipping.

## Branch / ship plan

- Branch: `fix/narrow-screen-ui-jank`
- Worktree: `.worktrees/narrow-screen-ui-jank`
- Target version: v0.9.34
- Release: tag + push after on-device + emulator verification

## References

- Issue: https://github.com/rawnaldclark/Stash/issues/87
- Reporter: @krishnadixitops (Nothing Phone 3a / Android 16)
- Reporter screenshot: `C:\Users\theno\Downloads\New folder (7)\595437130-7b706654-a593-499f-b02f-bff36934d2fc.png`
- Investigation: `docs/superpowers/specs/2026-05-23-narrow-screen-ui-jank-investigation.md`
