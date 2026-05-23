# Narrow-Screen UI Jank Fix — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. **Dispatch every subagent with `model: "opus"`** — Sonnet/Haiku not allowed on this project.

**Goal:** Resolve the four narrow-screen layout bugs reported in issue #87 (Nothing Phone 3a, fontScale ≥ 1.15×, Android 16) without restructuring any existing UI.

**Architecture:** Adaptive fixes only. Add `maxLines`/`ellipsis` on the toolbar title, wrap the Now Playing inner Column in `verticalScroll`, apply `windowInsetsPadding(navigationBars)` to the bottom bar Column, halve the album-art accent-shadow alpha. Two files, ~8 lines of code.

**Tech Stack:** Kotlin, Jetpack Compose, Material3, Gradle (Android).

**Spec:** [`docs/superpowers/specs/2026-05-23-narrow-screen-ui-jank-design.md`](../specs/2026-05-23-narrow-screen-ui-jank-design.md)

**Verification model:** No automated UI tests are added — this is pure layout/styling with no logic changes. Verification is manual on the emulator (Medium_Phone_API_36.1 @ fontScale 1.30) and on the user's Pixel 6 Pro at default fontScale. No new test files are created.

---

## Task 1: Worktree setup

**Files:**
- Create: `.worktrees/narrow-screen-ui-jank/` (new git worktree)
- Copy: `local.properties` into the worktree

- [ ] **Step 1: Create branch and worktree**

Run:
```bash
git worktree add .worktrees/narrow-screen-ui-jank -b fix/narrow-screen-ui-jank master
```

Expected: `Preparing worktree (new branch 'fix/narrow-screen-ui-jank')` then `HEAD is now at <sha> docs(spec): narrow-screen UI jank fix design for issue #87`.

- [ ] **Step 2: Copy `local.properties` into the worktree**

The worktree shares `.git` but **not** `local.properties` — without it, debug builds will surface "Last.fm Not configured" and other config-derived defaults. Per project convention (`memory/feedback_worktree_local_properties.md`):

Run:
```bash
cp local.properties .worktrees/narrow-screen-ui-jank/local.properties
```

Expected: silent success.

- [ ] **Step 3: Verify worktree is on the new branch**

Run:
```bash
cd .worktrees/narrow-screen-ui-jank && git branch --show-current
```

Expected: `fix/narrow-screen-ui-jank`.

**All subsequent file edits in this plan use paths relative to the worktree (`.worktrees/narrow-screen-ui-jank/`).**

---

## Task 2: Bug 1 — Toolbar title `maxLines` + ellipsis

**Files:**
- Modify: `feature/nowplaying/src/main/kotlin/com/stash/feature/nowplaying/NowPlayingScreen.kt:396-402`

- [ ] **Step 1: Edit the "NOW PLAYING" `Text`**

Find the block at lines 396-402:

```kotlin
Text(
    text = "NOW PLAYING",
    style = MaterialTheme.typography.labelMedium,
    color = Color.White.copy(alpha = 0.7f),
    textAlign = TextAlign.Center,
    modifier = Modifier.weight(1f),
)
```

Add `maxLines = 1, overflow = TextOverflow.Ellipsis`:

```kotlin
Text(
    text = "NOW PLAYING",
    style = MaterialTheme.typography.labelMedium,
    color = Color.White.copy(alpha = 0.7f),
    textAlign = TextAlign.Center,
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
    modifier = Modifier.weight(1f),
)
```

(`TextOverflow` is already imported at line 54.)

- [ ] **Step 2: Build compiles**

Run from the worktree:
```bash
./gradlew :feature:nowplaying:assembleDebug
```

Expected: `BUILD SUCCESSFUL`. If it fails on a missing `TextOverflow` import, double-check that line 54 still reads `import androidx.compose.ui.text.style.TextOverflow`.

- [ ] **Step 3: Commit**

```bash
git add feature/nowplaying/src/main/kotlin/com/stash/feature/nowplaying/NowPlayingScreen.kt
git commit -m "$(cat <<'EOF'
fix(nowplaying): clamp NOW PLAYING title to one line (#87)

On narrow screens with elevated fontScale, the title was softwrapping
per-letter because the Text had no maxLines/overflow guard.
Adding maxLines=1 + Ellipsis preserves the existing layout while
showing "NOW PLA..." instead of stacking when room is tight.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: Bug 3 — Halve album-art accent-shadow alpha

**Files:**
- Modify: `feature/nowplaying/src/main/kotlin/com/stash/feature/nowplaying/NowPlayingScreen.kt:489-498`

- [ ] **Step 1: Edit the `shadow` modifier in `AlbumArtSection`**

Find the block at lines 489-498:

```kotlin
Box(
    modifier = Modifier
        .size(260.dp)
        .shadow(
            elevation = 40.dp,
            shape = RoundedCornerShape(20.dp),
            ambientColor = accentColor.copy(alpha = 0.5f),
            spotColor = accentColor.copy(alpha = 0.5f),
        ),
)
```

Change both `alpha = 0.5f` to `alpha = 0.25f`:

```kotlin
Box(
    modifier = Modifier
        .size(260.dp)
        .shadow(
            elevation = 40.dp,
            shape = RoundedCornerShape(20.dp),
            ambientColor = accentColor.copy(alpha = 0.25f),
            spotColor = accentColor.copy(alpha = 0.25f),
        ),
)
```

- [ ] **Step 2: Build compiles**

Run from the worktree:
```bash
./gradlew :feature:nowplaying:assembleDebug
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add feature/nowplaying/src/main/kotlin/com/stash/feature/nowplaying/NowPlayingScreen.kt
git commit -m "$(cat <<'EOF'
fix(nowplaying): soften album-art accent glow (#87)

Reduce ambientColor/spotColor alpha 0.5 -> 0.25 on the AlbumArtSection
elevation shadow. On highly saturated artwork (e.g. Lola Young's
"Messy") the previous halo dominated the screen and was misread as a
stray UI element. The intentional ambient-halo effect is preserved,
just half as saturated.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: Bug 2b — Wrap Now Playing inner Column in `verticalScroll`

**Files:**
- Modify: `feature/nowplaying/src/main/kotlin/com/stash/feature/nowplaying/NowPlayingScreen.kt` (imports + lines 224-350)

- [ ] **Step 1: Add the two scroll imports**

Open `NowPlayingScreen.kt`. Find the foundation imports block (around lines 3-15) and add:

```kotlin
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
```

Keep alphabetical order with the existing `androidx.compose.foundation.*` imports.

- [ ] **Step 2: Add `verticalScroll` to the Column modifier and replace the weight Spacer**

Find the Column at lines 224-230:

```kotlin
Column(
    modifier = Modifier
        .fillMaxSize()
        .statusBarsPadding()
        .padding(horizontal = 24.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
) {
```

Replace with:

```kotlin
Column(
    modifier = Modifier
        .fillMaxSize()
        .statusBarsPadding()
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 24.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
) {
```

Then find the final spacer at line 349:

```kotlin
            Spacer(modifier = Modifier.weight(1f))
        }
    }
}
```

Replace `Modifier.weight(1f)` with `Modifier.height(48.dp)` (verticalScroll children can't use `weight`):

```kotlin
            Spacer(modifier = Modifier.height(48.dp))
        }
    }
}
```

- [ ] **Step 3: Build compiles**

Run from the worktree:
```bash
./gradlew :feature:nowplaying:assembleDebug
```

Expected: `BUILD SUCCESSFUL`. If it fails on `Modifier.weight` being unresolved or "weight is only valid in Column/Row scope", you missed the Spacer change in Step 2.

- [ ] **Step 4: Commit**

```bash
git add feature/nowplaying/src/main/kotlin/com/stash/feature/nowplaying/NowPlayingScreen.kt
git commit -m "$(cat <<'EOF'
fix(nowplaying): make Now Playing column scrollable (#87)

On narrow/scaled screens the intrinsic content height (album art +
text + progress bar + controls) exceeded available height, so
PlaybackControls rendered outside the column bound and were covered
by the bottomBar mini-player overlay. The user couldn't reach
play/pause/skip from Now Playing.

Wrap the inner Column in verticalScroll. weight(1f) is invalid in a
scrollable Column, so the trailing weight-spacer becomes a fixed
48dp spacer. Side effect on tall screens (Pixel 6 Pro at default
fontScale): PlaybackControls hug the progress bar instead of
floating mid-screen. Accepted trade-off — the previous "floating"
position came from the weight spacer that doesn't survive scrolling.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: Bug 2a — Apply `navigationBars` inset to the bottomBar Column

**Files:**
- Modify: `app/src/main/kotlin/com/stash/app/navigation/StashScaffold.kt` (imports + lines 82-104)

- [ ] **Step 1: Add the two layout imports**

Open `StashScaffold.kt`. The current foundation.layout imports are:

```kotlin
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
```

Add `navigationBars` and `windowInsetsPadding`, keeping alphabetical order:

```kotlin
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
```

- [ ] **Step 2: Apply the inset on the bottomBar Column**

Find lines 82-104:

```kotlin
bottomBar = {
    Column {
        MiniPlayer(
            onExpand = {
                navController.navigate(NowPlayingRoute) {
                    launchSingleTop = true
                }
            },
        )

        StashBottomBar(...)
    }
},
```

Add the modifier to the Column:

```kotlin
bottomBar = {
    Column(modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars)) {
        MiniPlayer(
            onExpand = {
                navController.navigate(NowPlayingRoute) {
                    launchSingleTop = true
                }
            },
        )

        StashBottomBar(...)
    }
},
```

- [ ] **Step 3: Build compiles**

Run from the worktree:
```bash
./gradlew :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/com/stash/app/navigation/StashScaffold.kt
git commit -m "$(cat <<'EOF'
fix(scaffold): pad bottomBar for navigationBars insets (#87)

The bottomBar slot wraps MiniPlayer + StashBottomBar in a Column.
Material3 NavigationBar's default windowInsets weren't reliably
reaching past the surrounding Column on Android 15/16 edge-to-edge
enforcement, so the bottom UI rendered flush with the gesture pill
on the reporter's Nothing Phone 3a.

Apply windowInsetsPadding(WindowInsets.navigationBars) directly on
the Column so both the mini-player and the nav bar get the same
gesture-pill clearance.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 6: Build + install on emulator, verify all four bugs

**Files:** none (verification only)

**Prerequisites:** the emulator `Medium_Phone_API_36.1` must be running, and `adb devices` should list `emulator-5554`. If it's not running, start it:

```bash
"$HOME/AppData/Local/Android/Sdk/emulator/emulator.exe" -avd Medium_Phone_API_36.1 -no-snapshot-save -no-boot-anim &
```

Wait for boot (`getprop sys.boot_completed` returns `1`).

- [ ] **Step 1: Set fontScale to 1.30 on the emulator**

```bash
"$HOME/AppData/Local/Android/Sdk/platform-tools/adb.exe" -s emulator-5554 shell settings put system font_scale 1.30
```

Verify:
```bash
"$HOME/AppData/Local/Android/Sdk/platform-tools/adb.exe" -s emulator-5554 shell settings get system font_scale
```
Expected: `1.30`.

- [ ] **Step 2: Install the debug build to the emulator only**

From the worktree:
```bash
./gradlew :app:installDebug -Pandroid.injected.serial=emulator-5554
```

(Without the `-Pandroid.injected.serial` flag, Gradle installs to whatever single device is connected; if the user's Pixel is plugged in, the install will fail with multiple-device error. Targeting the emulator explicitly avoids this.)

Expected: `BUILD SUCCESSFUL`, then `Installed on 1 device.`

- [ ] **Step 3: Launch and navigate to Now Playing with a track**

```bash
"$HOME/AppData/Local/Android/Sdk/platform-tools/adb.exe" -s emulator-5554 shell monkey -p com.stash.app.debug -c android.intent.category.LAUNCHER 1
```

Then drive the UI in the emulator window manually:
1. Dismiss any notification permission dialog.
2. Tap Search → type a track name (e.g. "Lola Young Messy") → tap a result's play button.
3. Wait for the mini-player to appear at the bottom (proxy resolution can take a few seconds).
4. Tap the mini-player to expand Now Playing.

If playback never starts on the emulator (it didn't in the prior session — the squid.wtf proxy may not resolve from the emulator's network), an alternative is to first download a track via the download button, then play the local file. Or use the **physical device verification (Task 7)** as the source of truth for behavior bugs (1, 2b) and use the emulator only for layout-at-elevated-fontScale checks.

- [ ] **Step 4: Verify each fix on the emulator screenshot**

Screenshot the Now Playing screen:
```bash
MSYS_NO_PATHCONV=1 "$HOME/AppData/Local/Android/Sdk/platform-tools/adb.exe" -s emulator-5554 shell screencap -p /sdcard/verify.png
MSYS_NO_PATHCONV=1 "$HOME/AppData/Local/Android/Sdk/platform-tools/adb.exe" -s emulator-5554 pull /sdcard/verify.png .
```

Open `verify.png` and confirm:
- [ ] Bug 1: top reads `NOW PLAYING` or `NOW PLA…` — **never** a vertical letter stack.
- [ ] Bug 2a: bottom NavigationBar has clearly visible empty space above the gesture pill (not flush).
- [ ] Bug 2b: PlaybackControls (shuffle / prev / play / next / repeat) are visible without scrolling, OR scroll the screen up and confirm they come into view.
- [ ] Bug 3: any colored halo around the album art is a subtle ambient glow, not a saturated colored blob.

If any of these fail, the corresponding task's edit didn't land — re-open the file and re-check the diff.

- [ ] **Step 5: Restore the emulator's fontScale (optional, only if you'll use this emulator again)**

```bash
"$HOME/AppData/Local/Android/Sdk/platform-tools/adb.exe" -s emulator-5554 shell settings put system font_scale 1.0
```

---

## Task 7: Verify on the user's Pixel 6 Pro

**Files:** none (verification only)

**Prerequisites:** the user's Pixel (serial visible via `adb devices` — in the prior session it was `1A021FDEE002RD`) is connected. Emulator may or may not also be running.

- [ ] **Step 1: Install the debug build to the Pixel only**

From the worktree:
```bash
./gradlew :app:installDebug -Pandroid.injected.serial=<PIXEL_SERIAL>
```

Replace `<PIXEL_SERIAL>` with the actual serial reported by `adb devices`. Expected: `BUILD SUCCESSFUL`, `Installed on 1 device.`

- [ ] **Step 2: Hand off to the user for on-device verification**

Stop here and ask the user to do the regression check on their Pixel at its default fontScale:

> "Build installed on your Pixel. Open Stash, play a track, expand Now Playing, and confirm: (1) title reads 'NOW PLAYING' in full, (2) bottom inset is at least as good as before, (3) playback controls are visible — possibly slightly higher on the screen than before (this is expected; they used to float, now they hug the progress bar), (4) album-art glow is more subtle on saturated artwork."

Per `memory/feedback_install_after_fix.md` and `memory/feedback_ship_terminology.md`: **do not ship (tag/push a release) without on-device confirmation from the user.**

---

## Task 8: Decide release path (gated on user confirmation)

**Files:** none (handoff)

- [ ] **Step 1: Wait for the user's go/no-go after Task 7**

Do not proceed without explicit user approval that on-device verification passed.

- [ ] **Step 2: If approved, surface release options to the user**

Choices to present:
1. **Squash-merge the four commits into one `Stash v0.9.34` release commit on master and tag.**
2. **Fast-forward merge keeping the four per-bug commits, then tag a release commit on top.**
3. **Hold the branch — merge later with other in-flight work.**

The choice depends on what other v0.9.34 work might exist; the user decides. Do not auto-tag, do not auto-push. Per `memory/feedback_release_notes.md`: the release commit body becomes the GitHub release body, so whichever path is chosen, draft a release commit body covering all four fixes before tagging.

- [ ] **Step 3: Close the loop**

Once shipped (or shelved), comment on issue #87 with the outcome and the version it landed in.

---

## Notes for the implementer

- **Do not** add automated UI/screenshot tests for these fixes. Not in scope; not the project's testing style.
- **Do not** restructure the 6-icon toolbar, hide the mini-player on Now Playing, or refactor `GlowingProgressBar`. Those are explicitly out of scope per the spec.
- **Do not** skip the `local.properties` copy in Task 1 step 2. Without it the debug build behaves differently and verification becomes unreliable.
- **Per project convention**, every subagent dispatched while executing this plan must use `model: "opus"`.
- **Per project convention**, "ship" means tag + push a public release, not local install. Tasks 6 and 7 install for verification only.
