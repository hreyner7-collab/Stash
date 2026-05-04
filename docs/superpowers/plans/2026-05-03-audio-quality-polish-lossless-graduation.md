# v0.9.8 — Audio Quality Polish + Lossless Graduation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship v0.9.8 — graduate lossless out of "experimental" framing, default fresh installs to lossless-on + MAX quality, surface a Home banner for v0.9.7 users who haven't enabled lossless, collapse the Last.fm Disconnected card to a single row, and make the Library tab open to TRACKS / RECENT / FLAC by default when the user has any lossless tracks.

**Architecture:** Pure data-class default flips drive the migration (DataStore returns the new defaults to absent-key users; explicitly-set values are preserved). One new DataStore key (`losslessBannerDismissed`) backs the Home banner's dismissal, mirroring the existing Last.fm pattern in `LastFmSessionPreference`. No new modules; no schema changes; no migration code.

**Tech Stack:** Kotlin, Jetpack Compose Material3, Hilt, Kotlin Coroutines (StateFlow / combine), DataStore Preferences, Android Room (read-only).

**Spec:** `docs/superpowers/specs/2026-05-03-audio-quality-polish-lossless-graduation-design.md`

---

## Pre-flight

The branch `feat/v0.9.8-audio-polish` was created during brainstorming and already contains the spec commits. This plan extends that branch — no new branch.

- [ ] **Confirm branch + clean tree**

```bash
cd C:/Users/theno/Projects/MP3APK
git branch --show-current
git status --short
```

Expected:
- Current branch: `feat/v0.9.8-audio-polish`
- No `M`/`A`/`D` lines for production files (only `??` brainstorm/scratch artefacts are fine)

- [ ] **Confirm spec is committed on this branch**

```bash
cd C:/Users/theno/Projects/MP3APK
git log --oneline master..HEAD
```

Expected three spec commits:
- `e411482` — initial spec
- `d7895a2` — reviewer fixes (combine ceiling + ControlState pattern)
- `f284615` — `LosslessPromptState` type definition

If anything else is between master and HEAD, stop and investigate before continuing.

---

## File-touched map

This plan touches 9 files across 3 feature modules + 1 data module + 1 app module. No new files except where noted.

| File | Module | Why |
|---|---|---|
| `data/download/.../lossless/LosslessSourcePreferences.kt` | `:data:download` | `enabled` fallback `false`→`true`; new `bannerDismissed` key + setter |
| `data/download/.../prefs/QualityPreferencesManager.kt` | `:data:download` | `qualityTier` fallback `BEST`→`MAX` |
| `feature/settings/.../SettingsUiState.kt` | `:feature:settings` | Initial values `audioQuality = MAX`, `losslessEnabled = true`; KDoc clarification |
| `feature/settings/.../SettingsScreen.kt` | `:feature:settings` | Toggle title + subtitles + captcha button copy; new instructional Text under the captcha button row; Last.fm Disconnected card collapse |
| `feature/settings/.../components/SquidWtfCaptchaScreen.kt` | `:feature:settings` | Replace 1-liner status text with 3-step numbered list |
| `feature/home/.../HomeUiState.kt` | `:feature:home` | Add `LosslessPromptState` `data object`; add `losslessPrompt: LosslessPromptState? = null` field |
| `feature/home/.../HomeViewModel.kt` | `:feature:home` | Add `losslessPrefs` constructor dep; add `losslessPromptFlow`; fold into `authStateFlow` (now 4-input); add `losslessPrompt` to `AuthInfo`; propagate to `HomeUiState`; add `dismissLosslessBanner()` |
| `feature/home/.../HomeScreen.kt` | `:feature:home` | Render banner under sync card; new `LosslessConnectBanner` composable mirroring `LastFmConnectBanner` |
| `feature/library/.../LibraryUiState.kt` | `:feature:library` | `activeTab` default `PLAYLISTS`→`TRACKS` |
| `feature/library/.../LibraryViewModel.kt` | `:feature:library` | `ControlState.activeTab` default `PLAYLISTS`→`TRACKS`; init-time FLAC smart-default applied via `_controls.update { it.copy(sourceFilter = …) }` |
| `app/build.gradle.kts` | `:app` | `versionCode 44 → 45`, `versionName 0.9.7 → 0.9.8` |

`StashNavHost.kt` does **not** need changes — `HomeScreen` already receives `onNavigateToSettings`, and the new banner reuses that callback (mirrors how `LastFmConnectBanner.onConnect` is wired today).

---

## Task 1: DataStore default flips + new bannerDismissed key

The migration foundation. After this task ships, every other change is purely cosmetic.

**Files:**
- Modify: `data/download/src/main/kotlin/com/stash/data/download/lossless/LosslessSourcePreferences.kt`
- Modify: `data/download/src/main/kotlin/com/stash/data/download/prefs/QualityPreferencesManager.kt`

- [ ] **Step 1: Read current state of both files**

Use Read on both files end-to-end. Specifically verify:
- `LosslessSourcePreferences.kt:42` — `private val enabledKey = androidx.datastore.preferences.core.booleanPreferencesKey("enabled")`
- `LosslessSourcePreferences.kt:56–58` — `enabled` Flow with fallback `?: false`
- `QualityPreferencesManager.kt:34` — `private val qualityKey = stringPreferencesKey("quality_tier")`
- `QualityPreferencesManager.kt:37–40` — `qualityTier` Flow with fallback `?: QualityTier.BEST`

If any of those don't match, stop and report — the plan was written against this exact state.

- [ ] **Step 2: Flip `LosslessSourcePreferences.enabled` default**

Edit `LosslessSourcePreferences.kt` line 56–58:

**Replace:**
```kotlin
val enabled: Flow<Boolean> = context.losslessDataStore.data.map { prefs ->
    prefs[enabledKey] ?: false
}
```

**With:**
```kotlin
val enabled: Flow<Boolean> = context.losslessDataStore.data.map { prefs ->
    prefs[enabledKey] ?: true
}
```

Also update the KDoc on this property (lines ~46–55). **Replace** the existing block:
```kotlin
/**
 * Master switch for the lossless-source pipeline. When false, the
 * download path skips the registry entirely and goes straight to
 * yt-dlp — same behaviour as before the lossless feature shipped.
 *
 * Defaults to false: lossless files are 5-10× larger than Opus, so
 * users opt in explicitly via Settings rather than getting a
 * surprise storage hit. The toggle lives on this preferences class
 * (rather than its own DataStore) so all lossless-related settings
 * stay in one place and the schema can evolve together.
 */
```

**With:**
```kotlin
/**
 * Master switch for the lossless-source pipeline. When false, the
 * download path skips the registry entirely and goes straight to
 * yt-dlp — same behaviour as before the lossless feature shipped.
 *
 * Defaults to true (v0.9.8+): fresh installs land lossless-ready;
 * existing v0.9.7 users who explicitly toggled it off keep their
 * saved value (DataStore preserves explicit writes). Users who
 * never opened the toggle pick up the new default — functionally
 * identical to v0.9.7 behaviour because the captcha is unverified
 * (silent yt-dlp/MP3 fallback via SquidWtfCaptchaInterceptor).
 *
 * The toggle lives on this preferences class (rather than its own
 * DataStore) so all lossless-related settings stay in one place
 * and the schema can evolve together.
 */
```

- [ ] **Step 3: Add the `bannerDismissed` key + Flow + setter**

Edit `LosslessSourcePreferences.kt`. After line 43 (the `captchaCookieKey` declaration), add:

```kotlin
    private val bannerDismissedKey = androidx.datastore.preferences.core.booleanPreferencesKey("home_banner_dismissed")
```

Then after the existing `captchaCookieValue` block (around line 99 — right before the `// region priority order` block), add a new property + setter:

```kotlin
    /**
     * Whether the user has dismissed the "Try lossless audio" Home
     * banner. Once dismissed, the banner never shows again — same
     * forever-dismissed semantics as `LastFmSessionPreference.bannerDismissed`.
     *
     * Defaults to false. Only read by [com.stash.feature.home.HomeViewModel];
     * Settings has no UI for un-dismissing.
     */
    val bannerDismissed: Flow<Boolean> = context.losslessDataStore.data.map { prefs ->
        prefs[bannerDismissedKey] ?: false
    }

    suspend fun setBannerDismissed(value: Boolean) {
        context.losslessDataStore.edit { prefs -> prefs[bannerDismissedKey] = value }
    }
```

- [ ] **Step 4: Flip `QualityPreferencesManager.qualityTier` default**

Edit `QualityPreferencesManager.kt` line 36–40. **Replace:**

```kotlin
    /** Emits the current [QualityTier], defaulting to [QualityTier.BEST]. */
    override val qualityTier: Flow<QualityTier> = context.qualityDataStore.data.map { prefs ->
        val name = prefs[qualityKey]
        name?.let { runCatching { QualityTier.valueOf(it) }.getOrNull() } ?: QualityTier.BEST
    }
```

**With:**

```kotlin
    /**
     * Emits the current [QualityTier], defaulting to [QualityTier.MAX] (v0.9.8+).
     *
     * Pre-v0.9.8 the default was [QualityTier.BEST]; users who explicitly
     * picked a tier keep their saved value because DataStore preserves
     * explicit writes. Users who never opened the Audio Quality card
     * pick up the new MAX default on next launch — both are 256 kbps
     * (BEST and MAX differ only in the yt-dlp arg string), so this is a
     * silent improvement, not a regression.
     */
    override val qualityTier: Flow<QualityTier> = context.qualityDataStore.data.map { prefs ->
        val name = prefs[qualityKey]
        name?.let { runCatching { QualityTier.valueOf(it) }.getOrNull() } ?: QualityTier.MAX
    }
```

- [ ] **Step 5: Build the `:data:download` module**

```bash
cd C:/Users/theno/Projects/MP3APK
./gradlew :data:download:assembleDebug
```

Expected: `BUILD SUCCESSFUL`. If you get an unresolved-reference error mentioning `bannerDismissedKey`, you placed the new key declaration outside the class — re-check Step 3.

- [ ] **Step 6: Commit**

```bash
cd C:/Users/theno/Projects/MP3APK
git add data/download/src/main/kotlin/com/stash/data/download/lossless/LosslessSourcePreferences.kt \
        data/download/src/main/kotlin/com/stash/data/download/prefs/QualityPreferencesManager.kt
git commit -m "feat(prefs): v0.9.8 default flips + lossless banner-dismissed key

- LosslessSourcePreferences.enabled fallback false → true. Existing
  users who explicitly toggled lossless off keep their saved value
  (DataStore preserves explicit writes); fresh installs and untouched-
  toggle users land on lossless-ready.
- LosslessSourcePreferences.bannerDismissed: new boolean key for the
  Home 'Try lossless audio' banner's forever-dismissal flag. Mirrors
  LastFmSessionPreference.bannerDismissed.
- QualityPreferencesManager.qualityTier fallback BEST → MAX. Both are
  256 kbps; differ only in yt-dlp arg string. Silent migration via
  DataStore absent-key semantics; no migration code.

Spec: docs/superpowers/specs/2026-05-03-audio-quality-polish-lossless-graduation-design.md
"
```

---

## Task 2: Settings copy + captcha instructions

Pure UI/copy edits. The new defaults from Task 1 mean the Audio Quality card lands with `losslessEnabled = true` for the cohorts that get the new defaults — so the new instructional copy under the captcha button is what those users see first.

**Files:**
- Modify: `feature/settings/src/main/kotlin/com/stash/feature/settings/SettingsUiState.kt`
- Modify: `feature/settings/src/main/kotlin/com/stash/feature/settings/SettingsScreen.kt`
- Modify: `feature/settings/src/main/kotlin/com/stash/feature/settings/components/SquidWtfCaptchaScreen.kt`

- [ ] **Step 1: Update `SettingsUiState.kt` initial values**

Edit `SettingsUiState.kt`:

- Line 27 — change `val audioQuality: QualityTier = QualityTier.BEST,` to `val audioQuality: QualityTier = QualityTier.MAX,`.
- Line 46 — change `val losslessEnabled: Boolean = false,` to `val losslessEnabled: Boolean = true,`.

These are the *initial* uiState values (emitted before the DataStore Flows resolve). Once DataStore emits, the actual user-saved values take over.

- [ ] **Step 2: Update lossless toggle title + subtitles in `SettingsScreen.kt`**

Find the lossless toggle row (around lines 442–469). Make these three replacements:

a) Toggle title (line 449):
   - Find: `text = "Lossless downloads (experimental)",`
   - Replace with: `text = "Lossless downloads",`

b) Toggle subtitle when ON (line 456):
   - Find: `"Try Qobuz proxy first; FLAC ~10× larger"`
   - Replace with: `"On — studio-quality FLAC via Qobuz. ~10× larger files."`

c) Toggle subtitle when OFF (line 458):
   - Find: `"Off — uses YouTube/yt-dlp like before"`
   - Replace with: `"Studio-quality FLAC via Qobuz. Files ~10× larger than MP3."`

- [ ] **Step 3: Update captcha button text + add instructional Text in `SettingsScreen.kt`**

Find the captcha button row (around lines 481–506). Two changes:

a) Button label (line 491):
   - Find: `Text("Verify in browser")`
   - Replace with: `Text("Connect to squid.wtf")`

b) Add a new instructional `Text` directly **after** the closing `}` of the button-row `Row(...)` block (around line 506) and **before** the `// -- Advanced expander row` comment block.

Insert this exact code block:

```kotlin
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Search any song → tap Download → solve the captcha. Stash captures the cookie automatically.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
```

The indentation matches the surrounding `Column(modifier = Modifier.fillMaxWidth())` block from line 477.

- [ ] **Step 4: Update WebView header status text in `SquidWtfCaptchaScreen.kt`**

Edit `SquidWtfCaptchaScreen.kt` lines 62–64.

**Replace:**

```kotlin
    var statusText by remember {
        mutableStateOf("Click Download on any track and solve the captcha. The cookie will save automatically.")
    }
```

**With:**

```kotlin
    var statusText by remember {
        mutableStateOf(
            "To verify:\n" +
                "1. Search for any song\n" +
                "2. Tap Download on a track\n" +
                "3. Solve the captcha popup\n" +
                "Cookie saves automatically once verified."
        )
    }
```

The success-state replacement (line 79 — `statusText = "Got it — saving and closing."`) stays as-is.

- [ ] **Step 5: Build `:feature:settings` and `:app:assembleDebug`**

```bash
cd C:/Users/theno/Projects/MP3APK
./gradlew :feature:settings:assembleDebug :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL` for both. The `:app` build also verifies that the Hilt graph still resolves (it should — no new constructor params on this task).

- [ ] **Step 6: Commit**

```bash
cd C:/Users/theno/Projects/MP3APK
git add feature/settings/src/main/kotlin/com/stash/feature/settings/SettingsUiState.kt \
        feature/settings/src/main/kotlin/com/stash/feature/settings/SettingsScreen.kt \
        feature/settings/src/main/kotlin/com/stash/feature/settings/components/SquidWtfCaptchaScreen.kt
git commit -m "feat(settings): v0.9.8 audio quality copy + captcha instructions

- Lossless toggle title drops the (experimental) parenthetical; the
  feature has shipped and hardened across v0.9.1-v0.9.7.
- ON/OFF subtitles rewritten for promotional clarity: 'studio-quality
  FLAC via Qobuz. ~10× larger files.'
- Captcha button: 'Verify in browser' → 'Connect to squid.wtf' (the
  literal destination, not a vague 'verify').
- New instructional bodySmall row under the captcha button:
  'Search any song → tap Download → solve the captcha. Stash captures
  the cookie automatically.' — first-time users have no idea what
  tapping the button does.
- WebView screen header replaces a 1-line sentence with a numbered
  3-step list. Same auto-capture behaviour.
- SettingsUiState initial values: audioQuality MAX, losslessEnabled
  true (matches the new persisted defaults from Task 1).
"
```

---

## Task 3: Last.fm Disconnected card collapse

Tight scope — one branch in one when-block, single file.

**Files:**
- Modify: `feature/settings/src/main/kotlin/com/stash/feature/settings/SettingsScreen.kt` (lines ~998–1020 only)

- [ ] **Step 1: Read the current Disconnected branch**

Read `SettingsScreen.kt` lines 998–1020. Verify the `LastFmAuthState.Disconnected ->` branch matches:

```kotlin
LastFmAuthState.Disconnected -> {
    Text(
        text = "Scrobble your plays",
        ...
    )
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        text = "Connect Last.fm and every song you finish in Stash lands in your Last.fm profile — perfect for building your own listening history independent of Spotify.",
        ...
    )
    Spacer(modifier = Modifier.height(12.dp))
    androidx.compose.material3.OutlinedButton(
        onClick = onConnect,
        modifier = Modifier.fillMaxWidth(),
        ...
    ) {
        Text("Connect Last.fm")
    }
}
```

- [ ] **Step 2: Replace the entire Disconnected branch**

Find the `LastFmAuthState.Disconnected -> {` block (around line 998) and the closing `}` (around line 1020).

**Replace** the entire `LastFmAuthState.Disconnected -> { … }` block with:

```kotlin
            LastFmAuthState.Disconnected -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Scrobble your plays",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    androidx.compose.material3.OutlinedButton(
                        onClick = onConnect,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.primary,
                        ),
                    ) {
                        Text("Connect")
                    }
                }
            }
```

The 4dp spacer + 3-line description + 12dp spacer + full-width button collapse to a single Row with the title taking remaining space and a small inline OutlinedButton. ~48 dp tall, matching the lossless toggle row's density two cards above.

Other branches (`NotConfigured`, `AwaitingAuth`, `Connected`, `Error`) stay unchanged.

- [ ] **Step 3: Build `:feature:settings`**

```bash
cd C:/Users/theno/Projects/MP3APK
./gradlew :feature:settings:assembleDebug
```

Expected: `BUILD SUCCESSFUL`. If imports for `Row`, `Alignment`, or `ButtonDefaults` are missing, they're already in the file from earlier sections of the same screen — verify by re-reading the imports at the top.

- [ ] **Step 4: Commit**

```bash
cd C:/Users/theno/Projects/MP3APK
git add feature/settings/src/main/kotlin/com/stash/feature/settings/SettingsScreen.kt
git commit -m "feat(settings): collapse Last.fm Disconnected card to single-row

The pre-connect 'Scrobble your plays' card was ~140 dp tall: title +
3-line description + spacer + full-width OutlinedButton. Most users
ignore the description anyway — the parent SectionHeader 'Last.fm'
plus the Connect button + the title 'Scrobble your plays' are enough
context. Collapses to ~48 dp, matching the lossless toggle row's
density two cards above.

Other Last.fm states (NotConfigured/AwaitingAuth/Connected/Error)
stay as they were.
"
```

---

## Task 4: Home banner — data layer

Add the holder type, the prompt Flow, fold it into `authStateFlow`, propagate to `HomeUiState`. No UI rendering yet — that's Task 5.

**Files:**
- Modify: `feature/home/src/main/kotlin/com/stash/feature/home/HomeUiState.kt`
- Modify: `feature/home/src/main/kotlin/com/stash/feature/home/HomeViewModel.kt`

- [ ] **Step 1: Add `LosslessPromptState` data object + `losslessPrompt` field to `HomeUiState.kt`**

Edit `HomeUiState.kt`:

a) Inside the `data class HomeUiState(...)` constructor — directly after the existing `lastFmPrompt: LastFmPromptState? = null,` line (around line 64), add:

```kotlin
    /**
     * Non-null when the user has not enabled lossless AND has not
     * dismissed the Home banner. Drives the "Try lossless audio"
     * banner that shows below the sync card.
     *
     * Same shape as [lastFmPrompt] but a singleton (no varying
     * fields like pendingCount); the banner copy is static.
     */
    val losslessPrompt: LosslessPromptState? = null,
```

b) Below the existing `data class LastFmPromptState(val pendingCount: Int)` declaration (around line 97), add:

```kotlin
/**
 * Sentinel for the "Try lossless audio" Home banner. Singleton
 * (data object) because the banner copy is static — its mere
 * presence in the UI state signals "show the banner."
 */
data object LosslessPromptState
```

- [ ] **Step 2: Add `LosslessSourcePreferences` constructor dep + `losslessPromptFlow` to `HomeViewModel.kt`**

Edit `HomeViewModel.kt`:

a) Add the import. After the existing `com.stash.data.download.files.LibrarySizeHolder` import (line 15), insert:

```kotlin
import com.stash.data.download.lossless.LosslessSourcePreferences
```

b) Add a constructor parameter. In the `@Inject constructor(...)` block (lines 44–52), after the existing `private val librarySizeHolder: LibrarySizeHolder,` line, add:

```kotlin
    private val losslessPrefs: LosslessSourcePreferences,
```

c) Add the `losslessPromptFlow` private property. After the existing `lastFmPromptFlow` block (which ends around line 124, on the line before the KDoc for `authStateFlow`), insert:

```kotlin
    /**
     * Lossless connect nudge: only visible when the user has not
     * enabled lossless AND has not dismissed the banner. Mirrors
     * [lastFmPromptFlow]'s shape and lifecycle — once dismissed,
     * the DataStore write makes the Flow re-emit null and the
     * banner disappears on its own.
     */
    private val losslessPromptFlow = combine(
        losslessPrefs.enabled,
        losslessPrefs.bannerDismissed,
    ) { enabled, dismissed ->
        if (!enabled && !dismissed) LosslessPromptState else null
    }
```

- [ ] **Step 3: Fold `losslessPromptFlow` into `authStateFlow`**

Edit `HomeViewModel.kt` `authStateFlow` (lines 132–142). **Replace:**

```kotlin
    private val authStateFlow = combine(
        tokenManager.spotifyAuthState,
        tokenManager.youTubeAuthState,
        lastFmPromptFlow,
    ) { spotify, youtube, lastFmPrompt ->
        AuthInfo(
            spotifyConnected = spotify is AuthState.Connected,
            youTubeConnected = youtube is AuthState.Connected,
            lastFmPrompt = lastFmPrompt,
        )
    }
```

**With:**

```kotlin
    private val authStateFlow = combine(
        tokenManager.spotifyAuthState,
        tokenManager.youTubeAuthState,
        lastFmPromptFlow,
        losslessPromptFlow,
    ) { spotify, youtube, lastFmPrompt, losslessPrompt ->
        AuthInfo(
            spotifyConnected = spotify is AuthState.Connected,
            youTubeConnected = youtube is AuthState.Connected,
            lastFmPrompt = lastFmPrompt,
            losslessPrompt = losslessPrompt,
        )
    }
```

The KDoc comment on lines 127–131 should also gain a mention of the lossless prompt. **Replace** the existing comment:

```kotlin
    /**
     * Derives (spotifyConnected, youTubeConnected, lastFmPrompt) from
     * TokenManager + Last.fm session state. Bundled so the top-level
     * combine stays at 5 inputs.
     */
```

**With:**

```kotlin
    /**
     * Derives (spotifyConnected, youTubeConnected, lastFmPrompt,
     * losslessPrompt) from TokenManager + Last.fm session state +
     * lossless prefs. Bundled so the top-level combine stays at 5
     * inputs (the non-vararg ceiling).
     */
```

- [ ] **Step 4: Update `AuthInfo` and `HomeUiState(...)` propagation**

Edit `HomeViewModel.kt`:

a) Find the `AuthInfo` private data class at the bottom of the file (around line 450–456). It currently looks like:

```kotlin
private data class AuthInfo(
    val spotifyConnected: Boolean,
    val youTubeConnected: Boolean,
    val lastFmPrompt: LastFmPromptState?,
)
```

**Replace** with:

```kotlin
private data class AuthInfo(
    val spotifyConnected: Boolean,
    val youTubeConnected: Boolean,
    val lastFmPrompt: LastFmPromptState?,
    val losslessPrompt: LosslessPromptState?,
)
```

b) Find the `HomeUiState(...)` constructor call inside the top-level `combine { … }` lambda (around line 200–215). Find the existing `lastFmPrompt = authInfo.lastFmPrompt,` line and **add** directly after it:

```kotlin
            losslessPrompt = authInfo.losslessPrompt,
```

The diff is exactly one new line in that block.

- [ ] **Step 5: Add `dismissLosslessBanner()` method**

Edit `HomeViewModel.kt`. Find the existing `dismissLastFmBanner()` method (around lines 275–280) — it looks like:

```kotlin
    fun dismissLastFmBanner() {
        viewModelScope.launch {
            lastFmSessionPreference.setBannerDismissed(true)
        }
    }
```

Add directly after it:

```kotlin
    /**
     * Hide the "Try lossless audio" Home banner forever. Writes
     * through to DataStore; the prompt Flow re-emits null on the
     * next tick and the banner disappears.
     */
    fun dismissLosslessBanner() {
        viewModelScope.launch {
            losslessPrefs.setBannerDismissed(true)
        }
    }
```

- [ ] **Step 6: Build `:feature:home` and `:app:assembleDebug`**

```bash
cd C:/Users/theno/Projects/MP3APK
./gradlew :feature:home:assembleDebug :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL` for both. `:app:assembleDebug` is required to verify the Hilt graph still resolves with the new `LosslessSourcePreferences` constructor parameter (it should — `LosslessSourcePreferences` is a `@Singleton` already in the graph, used by `SettingsViewModel`).

- [ ] **Step 7: Commit**

```bash
cd C:/Users/theno/Projects/MP3APK
git add feature/home/src/main/kotlin/com/stash/feature/home/HomeUiState.kt \
        feature/home/src/main/kotlin/com/stash/feature/home/HomeViewModel.kt
git commit -m "feat(home): wire lossless banner data layer

- HomeUiState: add LosslessPromptState data object + losslessPrompt
  nullable field. Same shape as lastFmPrompt; singleton because the
  banner copy is static (no varying fields).
- HomeViewModel: inject LosslessSourcePreferences. Add losslessPromptFlow
  combining enabled + bannerDismissed; show banner when !enabled
  && !dismissed. Fold into authStateFlow (now 4-input combine);
  the top-level combine stays at exactly 5 inputs (the non-vararg
  ceiling — same reason lastFmPromptFlow already lives in
  authStateFlow). Propagate losslessPrompt through AuthInfo to
  HomeUiState. Add dismissLosslessBanner() method writing through
  to DataStore.

UI rendering lands in the next commit.
"
```

---

## Task 5: Home banner — UI rendering

Render the banner under the sync card, mirror `LastFmConnectBanner`'s visual treatment, wire dismiss + tap-to-Settings.

**Files:**
- Modify: `feature/home/src/main/kotlin/com/stash/feature/home/HomeScreen.kt`

- [ ] **Step 1: Read the existing `LastFmConnectBanner` composable**

Read `HomeScreen.kt` lines 1505–1572 (the `LastFmConnectBanner` composable definition). The new `LosslessConnectBanner` mirrors its shape exactly — title row + dismiss X — just with different copy and no `pendingCount` field.

- [ ] **Step 2: Read where `LastFmConnectBanner` is rendered in the main `LazyColumn`**

Read `HomeScreen.kt` lines 186–200 (the `uiState.lastFmPrompt?.let { prompt -> item { … } }` block). The new banner lives in the same neighbourhood — directly below it, so when both prompts apply (a v0.9.7 user who never connected Last.fm AND never enabled lossless) they stack with Last.fm above lossless. This is acceptable per spec §Risks.

- [ ] **Step 3: Add the lossless banner item to the LazyColumn**

Directly after the closing `}` of the `uiState.lastFmPrompt?.let { ... }` block (around line 200) and before the `// ── Mixes (split by source...` comment (line 202), insert:

```kotlin
        // ── Lossless connect nudge ───────────────────────────────────
        // Shown when the user has lossless toggled OFF and hasn't
        // dismissed. Tap routes to Settings; X dismisses forever.
        // Stacks below the Last.fm banner if both apply.
        uiState.losslessPrompt?.let {
            item {
                Spacer(Modifier.height(6.dp))
                LosslessConnectBanner(
                    onSetUp = onNavigateToSettings,
                    onDismiss = viewModel::dismissLosslessBanner,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
```

- [ ] **Step 4: Add the `LosslessConnectBanner` composable**

After the `LastFmConnectBanner` composable's closing `}` (around line 1571) and before the `// ── Supporter pill` comment (around line 1573), insert:

```kotlin
/**
 * "Try lossless audio" Home banner. Shows when the user has
 * lossless turned off (explicit save, since v0.9.8 fresh installs
 * default to ON) and hasn't dismissed. Tapping routes to Settings,
 * where the existing Audio Quality card hosts the toggle + captcha
 * setup flow. Mirrors [LastFmConnectBanner]'s visual treatment so
 * both Home prompts feel consistent.
 */
@Composable
private fun LosslessConnectBanner(
    onSetUp: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = MaterialTheme.colorScheme.tertiary
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = accent.copy(alpha = 0.10f),
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = 0.35f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onSetUp)
                .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Try lossless audio",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Studio-quality FLAC downloads via Qobuz. Tap to set up.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "Set up →",
                style = MaterialTheme.typography.labelSmall,
                color = accent,
            )
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Dismiss lossless banner",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
```

All imports (`Surface`, `RoundedCornerShape`, `BorderStroke`, `Icons.Default.Close`, `CircleShape`, etc.) are already at the top of `HomeScreen.kt` because `LastFmConnectBanner` uses them. No import changes.

- [ ] **Step 5: Build `:feature:home` and `:app:assembleDebug`**

```bash
cd C:/Users/theno/Projects/MP3APK
./gradlew :feature:home:assembleDebug :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL` for both. If you get an unresolved-reference error mentioning `dismissLosslessBanner`, you skipped Step 5 of Task 4 — go back and add the method.

- [ ] **Step 6: Commit**

```bash
cd C:/Users/theno/Projects/MP3APK
git add feature/home/src/main/kotlin/com/stash/feature/home/HomeScreen.kt
git commit -m "feat(home): render 'Try lossless audio' banner

Adds the LosslessConnectBanner composable mirroring LastFmConnectBanner's
visual treatment (tertiary-tinted Surface with rounded corners, title
+ subtitle + 'Set up →' chip + dismiss X). Renders directly below the
Last.fm banner slot so when both prompts apply they stack consistently.

Tap routes to Settings via the existing onNavigateToSettings callback
(the same callback Last.fm uses); X calls viewModel::dismissLosslessBanner.

The new defaults from Task 1 mean fresh installs and untouched-toggle
upgraders never see this banner — only v0.9.7 users who explicitly
turned lossless OFF do.
"
```

---

## Task 6: Library smart-default

Open the Library to TRACKS / RECENT. If any FLAC tracks present at cold start, also pre-apply the FLAC filter chip.

**Files:**
- Modify: `feature/library/src/main/kotlin/com/stash/feature/library/LibraryUiState.kt`
- Modify: `feature/library/src/main/kotlin/com/stash/feature/library/LibraryViewModel.kt`

- [ ] **Step 1: Change `LibraryUiState.activeTab` default**

Edit `LibraryUiState.kt` line 14. **Replace:**

```kotlin
    val activeTab: LibraryTab = LibraryTab.PLAYLISTS,
```

**With:**

```kotlin
    val activeTab: LibraryTab = LibraryTab.TRACKS,
```

This is the *initial* uiState — emitted before the controls Flow resolves.

- [ ] **Step 2: Change `ControlState.activeTab` default in `LibraryViewModel.kt`**

Edit `LibraryViewModel.kt` around line 421–426. The `ControlState` private data class is the source-of-truth default driving the user-controls flow. Find:

```kotlin
private data class ControlState(
    val activeTab: LibraryTab = LibraryTab.PLAYLISTS,
    val searchQuery: String = "",
    val sortOrder: SortOrder = SortOrder.RECENT,
    val sourceFilter: SourceFilter = SourceFilter.ALL,
)
```

**Replace** with:

```kotlin
private data class ControlState(
    val activeTab: LibraryTab = LibraryTab.TRACKS,
    val searchQuery: String = "",
    val sortOrder: SortOrder = SortOrder.RECENT,
    val sourceFilter: SourceFilter = SourceFilter.ALL,
)
```

- [ ] **Step 3: Locate the existing `LOSSLESS_CODECS` constant + `init` block**

Read `LibraryViewModel.kt` lines 100–130 — verify a `LOSSLESS_CODECS` set is in scope (used by `SourceFilter.FLAC -> allTracks.filter { it.fileFormat.lowercase() in LOSSLESS_CODECS }` at line ~116).

Then read `LibraryViewModel.kt` lines 60–100 to find the existing `init { ... }` block (or to confirm there isn't one yet — there's a `_controls` declaration at line 72, but check whether an `init` block exists that we can extend).

If no `init` block exists in the class, you'll add one in Step 4. If one exists, you'll append to it.

- [ ] **Step 4: Add the smart-default init logic**

Locate the right place (just below the `_controls` MutableStateFlow declaration, around line 72). Add an `init` block (or extend the existing one — see Step 3) that runs the one-shot snapshot read + filter override:

```kotlin
    init {
        // Smart-default: if the user already has lossless tracks, open
        // Library to TRACKS / RECENT / FLAC instead of TRACKS / RECENT
        // / ALL. One-shot snapshot read at cold start; the user's
        // mid-session filter changes are honoured (we never fight back).
        viewModelScope.launch {
            val firstSnapshot = musicRepository.getAllTracks().first()
            val hasLossless = firstSnapshot.any {
                it.fileFormat?.lowercase() in LOSSLESS_CODECS
            }
            if (hasLossless && _controls.value.sourceFilter == SourceFilter.ALL) {
                _controls.update { it.copy(sourceFilter = SourceFilter.FLAC) }
            }
        }
    }
```

If `LibraryViewModel` already has an `init { ... }` block, append the `viewModelScope.launch { ... }` body inside the existing block instead of adding a second `init`.

Verify the necessary imports are already at the top of `LibraryViewModel.kt`:
- `androidx.lifecycle.viewModelScope` — already present (used elsewhere in the class)
- `kotlinx.coroutines.launch` — likely already present
- `kotlinx.coroutines.flow.first` — likely already present
- `kotlinx.coroutines.flow.update` — already present (used by `selectFilter` at line 217)

If any of those four imports are missing, add them at the top of the file.

- [ ] **Step 5: Build `:feature:library` and `:app:assembleDebug`**

```bash
cd C:/Users/theno/Projects/MP3APK
./gradlew :feature:library:assembleDebug :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL` for both.

- [ ] **Step 6: Commit**

```bash
cd C:/Users/theno/Projects/MP3APK
git add feature/library/src/main/kotlin/com/stash/feature/library/LibraryUiState.kt \
        feature/library/src/main/kotlin/com/stash/feature/library/LibraryViewModel.kt
git commit -m "feat(library): default to TRACKS tab + smart FLAC filter on cold start

- LibraryUiState.activeTab default PLAYLISTS → TRACKS (initial value
  emitted before flows resolve).
- ControlState.activeTab default PLAYLISTS → TRACKS (source-of-truth
  default driving the user-controls flow).
- New init-time one-shot snapshot read: if the user has any FLAC
  tracks at cold start, pre-apply the FLAC filter chip. Guarded by
  '_controls.value.sourceFilter == SourceFilter.ALL' so we never
  fight a non-default filter the user (or future
  SavedStateHandle-based restoration) has set.
- Reuses the existing LOSSLESS_CODECS set already used by the manual
  FLAC filter chip.

Empty-library fresh installs land on TRACKS / RECENT / ALL — same as
v0.9.7 behaviour pre-fix. Once they download anything FLAC, next cold
start opens with the FLAC filter pre-applied.
"
```

---

## Task 7: Bump version + build signed release + sideload + manual acceptance

Per memory `feedback_install_after_fix.md`, on-device verification is the discipline. Per memory `feedback_ship_terminology.md`, sideload-and-test is **not** "ship" — Task 8 is.

**Files:**
- Modify: `app/build.gradle.kts`

- [ ] **Step 1: Bump versionCode + versionName**

Edit `app/build.gradle.kts`:

- Line 75 — change `versionCode = 44` to `versionCode = 45`.
- Line 76 — change `versionName = "0.9.7"` to `versionName = "0.9.8"`.

- [ ] **Step 2: Build the signed release APK**

```bash
cd C:/Users/theno/Projects/MP3APK
./gradlew :app:assembleRelease
```

Expected: `BUILD SUCCESSFUL`. APK lands at `app/build/outputs/apk/release/app-release.apk`.

If signing fails (`keystore.properties` missing), you can also use `:app:assembleDebug` for sideload-test purposes — but the production-shape APK is `assembleRelease` and the user's existing installs are signed with the release key.

- [ ] **Step 3: Sideload over the user's existing install**

```bash
cd C:/Users/theno/Projects/MP3APK
adb devices
adb install -r app/build/outputs/apk/release/app-release.apk
```

Expected: device line shown in `adb devices`; install reports `Success`. The user's library data is preserved (same package, same signing key).

If `adb devices` shows no device, ask the user to re-seat the cable + accept the USB-debugging prompt; do not proceed until the device shows up.

- [ ] **Step 4: Run the manual acceptance flow**

Open the **main `com.stash.app`** (not debug). For each scenario, confirm the observed behaviour matches:

1. **Settings → Audio quality card**:
   - Toggle title reads `Lossless downloads` (no `(experimental)`).
   - When the toggle is ON, the captcha block is expanded (default for users without an explicit `false` saved). New body text under the captcha button reads: `Search any song → tap Download → solve the captcha. Stash captures the cookie automatically.`
   - The button reads `Connect to squid.wtf`.
   - Tap the button → WebView opens. Header text shows the 3-step numbered list, not the old single sentence.
   - Subtitle below the toggle reads `On — studio-quality FLAC via Qobuz. ~10× larger files.` when ON, or the new "Studio-quality FLAC via Qobuz. Files ~10× larger than MP3." when OFF.
2. **Settings → Last.fm card (Disconnected state, if applicable)**:
   - Card is a single row: `Scrobble your plays` text on the left, `Connect` outlined button on the right. ~48 dp tall.
   - The 3-line description is gone.
3. **Home → top of scroll**:
   - **If your saved `losslessEnabled = true`** (or no DataStore entry — the new default applies): no lossless banner.
   - **If your saved `losslessEnabled = false`** (explicit v0.9.7 toggle-off): a tertiary-tinted banner reads `Try lossless audio` + `Studio-quality FLAC downloads via Qobuz. Tap to set up.` with `Set up →` chip and `×` icon. Tap → Settings opens. Tap `×` → banner disappears. Background the app for 30s, reopen → banner stays gone.
4. **Library tab → cold-start behaviour**:
   - Force-stop the app, reopen. Library opens with the **TRACKS** tab active (not Playlists).
   - If you have any FLAC tracks (check: any track with file_format = "flac"), the FLAC filter chip is pre-selected.
   - If no FLAC tracks, the ALL chip is active.
5. **Existing-user migration check**:
   - Check Settings → Audio quality. If you previously *explicitly* picked a quality tier in v0.9.7, that pick is still selected. If you never opened the card, the radio button now sits on `Max`.
   - Same check for the lossless toggle: explicit saves preserved; untouched users see ON.

If any scenario fails, **stop and report**. Do not proceed to merge/tag.

- [ ] **Step 5: Optional empty-commit marker for the device-acceptance milestone**

```bash
cd C:/Users/theno/Projects/MP3APK
git commit --allow-empty -m "test: manual device acceptance — v0.9.8 audio quality polish + library smart-default"
```

Skip if you'd rather keep the history flat.

- [ ] **Step 6: Commit the version bump**

```bash
cd C:/Users/theno/Projects/MP3APK
git add app/build.gradle.kts
git commit -m "chore(release): bump versionCode 44→45, versionName 0.9.7→0.9.8"
```

---

## Task 8: Merge + tag + GitHub release

Only run after Task 7 acceptance passed.

Per memory `feedback_check_worktrees_before_release.md`: survey worktrees first.
Per memory `feedback_release_notes.md`: lightweight tag + omit `--notes` so the release body comes from the tagged commit's message body.

- [ ] **Step 1: Survey worktrees for v0.9.8-relevant WIP**

```bash
cd C:/Users/theno/Projects/MP3APK
git worktree list
for w in auto-advance-fix crossfade crossfader playlist-images-liked-songs preview-latency-fix yt-history-sync yt-sync-pagination; do
  echo "=== $w ==="
  git -C ".worktrees/$w" status --short 2>&1 | head -10
  echo "--- log vs master ---"
  git -C ".worktrees/$w" log --oneline master..HEAD 2>&1 | head -5
done
```

Expected: same status as before v0.9.7 ship — the same set of worktrees with the same WIP, none of which is intended for this release. If anything has changed (new branch added, audio-quality-related WIP appeared in another worktree), stop and ask the user before merging.

- [ ] **Step 2: Create the consolidated release-notes commit on the branch tip**

The intermediate Task 1–6 commit messages describe per-task changes. Per memory `feedback_release_notes.md`, GitHub renders the release body from the tagged commit's message. Create an empty commit at the branch tip whose body is the user-facing v0.9.8 release notes:

```bash
cd C:/Users/theno/Projects/MP3APK
git commit --allow-empty -m "$(cat <<'EOF'
feat: 0.9.8 — Lossless graduates + Audio Quality polish + Library smart-default

Lossless downloads via squid.wtf graduates out of "experimental" framing
in v0.9.8. The feature shipped in v0.9.0-beta1 and has hardened across
v0.9.1-v0.9.7; it works in production. The Settings UX no longer treats
it as a marginal opt-in.

Changes:
- Audio Quality card: toggle title drops the (experimental)
  parenthetical. ON/OFF subtitles rewritten as promotional copy
  ("studio-quality FLAC via Qobuz. ~10× larger files."). Captcha
  button renamed "Connect to squid.wtf". New instructional body text
  under the button explains the search → download → solve flow that
  auto-captures the cookie. WebView header replaces a 1-line sentence
  with a numbered 3-step list.
- Default flips for fresh installs and untouched-toggle upgraders:
  lossless ON, qualityTier MAX. DataStore preserves existing explicit
  values so users who picked a quality tier or toggled lossless OFF
  in v0.9.7 keep their saved settings.
- Home banner ("Try lossless audio") shows for v0.9.7 users who
  explicitly turned lossless OFF. Mirrors the existing Last.fm
  connect-prompt pattern. Dismissible.
- Last.fm Disconnected card collapses to a single row (~48 dp,
  matching the lossless toggle row's density).
- Library tab default view: opens to Tracks / Recently Added on every
  cold start. If the user has any FLAC tracks, the FLAC filter chip
  is pre-selected — discoverability for the lossless content they're
  now downloading.

Spec: docs/superpowers/specs/2026-05-03-audio-quality-polish-lossless-graduation-design.md

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
RELEASE_SHA=$(git rev-parse HEAD)
echo "release commit: $RELEASE_SHA"
```

- [ ] **Step 3: Push the feature branch**

```bash
cd C:/Users/theno/Projects/MP3APK
git push origin feat/v0.9.8-audio-polish
```

- [ ] **Step 4: Switch to master, fast-forward to remote, merge with --no-ff**

```bash
cd C:/Users/theno/Projects/MP3APK
git checkout master
git pull --ff-only origin master
git merge --no-ff feat/v0.9.8-audio-polish -m "Merge branch 'feat/v0.9.8-audio-polish'"
```

Expected: clean merge commit. Anything other than `Merge made by the 'ort' strategy.` (or similar) means a conflict — stop and resolve manually.

- [ ] **Step 5: Push master**

```bash
cd C:/Users/theno/Projects/MP3APK
git push origin master
```

- [ ] **Step 6: Create + push lightweight tag**

Tag the consolidated release-notes commit (`$RELEASE_SHA` from Step 2). Lightweight tag — per memory `feedback_release_notes.md`, an annotated tag would compete with the commit body for the release notes.

```bash
cd C:/Users/theno/Projects/MP3APK
git tag v0.9.8 "$RELEASE_SHA"
git push origin v0.9.8
```

If `$RELEASE_SHA` isn't in scope (different shell), look it up:

```bash
cd C:/Users/theno/Projects/MP3APK
git log master --oneline | head -5
# Find the "feat: 0.9.8 — Lossless graduates..." commit. Tag that SHA.
```

- [ ] **Step 7: Create GitHub release using the tagged-commit body**

```bash
cd C:/Users/theno/Projects/MP3APK
gh release create v0.9.8 \
  --title "v0.9.8 — Lossless graduates + Audio Quality polish"
```

(No `--notes` — GitHub renders the tagged commit's body. Per memory `feedback_release_notes.md`.)

Expected: gh prints the release URL.

- [ ] **Step 8: Verify the release on GitHub**

Open the URL gh printed (or `https://github.com/rawnaldclark/Stash/releases/tag/v0.9.8`). Confirm:
- Title reads "v0.9.8 — Lossless graduates + Audio Quality polish"
- Body contains the natural-language release notes from Step 2's commit
- The auto-generated release APK appears (after CI build completes — typically 5-10 minutes)

---

## Skills reference

- @superpowers:verification-before-completion — before claiming Task 7 / Task 8 done. Don't skip the on-device acceptance flow.
- Memory `feedback_install_after_fix.md` — always installDebug or sideload release after a fix; compile-pass alone isn't enough.
- Memory `feedback_release_notes.md` — release body comes from the tagged commit's message body, not the tag annotation. Use lightweight tags + omit `--notes`.
- Memory `feedback_check_worktrees_before_release.md` — survey worktrees before tagging.
- Memory `feedback_no_time_estimates.md` — no dev-time estimates anywhere in commits or release notes.

## Risks & rollback

- **Existing-user migration is silent.** Users with no DataStore entry for `losslessEnabled` and `qualityTier` will pick up the new defaults (ON + MAX). The lossless ON change is functionally a no-op without captcha (silent yt-dlp/MP3 fallback via `SquidWtfCaptchaInterceptor`); MAX vs BEST differs only in yt-dlp arg string (both 256 kbps). No user-visible regression.
- **Hilt graph misconfiguration in Task 4.** `HomeViewModel` gains a `LosslessSourcePreferences` constructor param. The class is `@Singleton` already in the graph (used by `SettingsViewModel`); the `:app:assembleDebug` step in Task 4 catches misconfiguration. If that build fails with a Hilt error mentioning `LosslessSourcePreferences`, double-check the import on the new line and the `@Inject constructor(...)` parameter order.
- **Combine arity error in Task 4.** The top-level `combine` in `HomeViewModel.kt` is at exactly 5 inputs (the non-vararg ceiling). The plan folds `losslessPromptFlow` into `authStateFlow` — do **not** add it as a 6th input to the top-level combine. If you do, the build will fail with a parameter-count error.
- **`LibraryViewModel._sourceFilter` does not exist.** The plan uses `_controls.update { it.copy(sourceFilter = …) }` (the existing pattern at line 217). If the implementer mistakenly writes `_sourceFilter.value = …`, the build will fail with an unresolved-reference error.
- **Library smart-default fires before tracks load on first cold start.** The first `getAllTracks().first()` resolves to whatever Room currently has. If Room hasn't finished cold-loading yet, the snapshot may be empty and `hasLossless` is false → filter stays `ALL`. On the next cold start (after Room has populated), the smart-default kicks in. Acceptable per spec §Risks.
- **Rollback** — revert the merge commit on master + delete the tag (`git push --delete origin v0.9.8`). User's data is unaffected (no schema change, no destructive DataStore writes). DataStore values written via `setBannerDismissed(true)` survive a rollback to v0.9.7 because that key is unknown there → no-op read. Worst case the user reinstalls v0.9.7 from GitHub releases.
