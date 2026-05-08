# v0.9.17 — FLAC-Only Mode + Deferred-Track Pipeline

**Date:** 2026-05-08
**Status:** Design
**Branch:** `feat/flac-only-mode` (worktree path: `.worktrees/flac-only-mode`)

## Problem

Stash v0.9.16 ships a working two-source lossless pipeline (`qobuz.squid.wtf` + `qobuz.kennyy.com.br`) that is enabled by default. When both sources fail to deliver a track — squid because the user's `captcha_verified_at` cookie is expired or never pasted, kennyy because the operator is currently down — `LosslessSourceRegistry.resolve()` returns null and `DownloadManager` silently falls through to yt-dlp, delivering a ~256 kbps m4a file in place of FLAC.

Two distinct problems compound:

1. **The fallback is silent.** Users who care about lossless have no signal that they got a lossy file instead. The Library list shows the same `FLAC`-vs-not badge introduced in v0.9.11, but the user has no banner, notification, or queue indicator telling them this happened. The first they hear of it is when they notice a smaller-than-expected file size or compare codecs in Now Playing.

2. **Some users want strict-FLAC.** Even if the fallback were noisy, a meaningful subset of the audience (audiophile-leaning storage-tolerant users) would rather a track stay un-downloaded than land as m4a. There is no mode in v0.9.16 that lets them say "FLAC or nothing."

The existing `LosslessSourcePreferences.MinQuality` enum (`LOSSLESS / HIGH_LOSSY / ANY`, default `LOSSLESS`) hints at this design space — but today it only filters within the lossless registry, never gates the yt-dlp fallback. The enum's stored value silently lies about the user's intent.

This spec adds a strict-FLAC mode as the new default, surfaces a deferred-track state for tracks that lossless can't currently serve, and gives the user a Home banner with one-tap recovery actions when the deferral has a fixable cause (expired captcha, source not configured).

## Goals

- A user with default settings (`losslessEnabled = true`, new `youtubeFallbackEnabled = false`) gets FLAC or nothing — never a silent m4a substitute.
- A user who flips `youtubeFallbackEnabled = true` (or `losslessEnabled = false`) gets today's behavior — silent m4a fallback or pure yt-dlp respectively.
- Tracks that the lossless registry cannot currently serve enter a `WAITING_FOR_LOSSLESS` state, retry automatically when sources recover, and never get a lossy file substituted unless the user explicitly opts in.
- Existing users default-flip to FLAC-only silently — no migration modal. The Home banner is the in-context explanation when the deferral first triggers.
- The Settings UI keeps the lossless card as the user's single mental anchor for "audio quality" — when lossless is on, the legacy YT-tier picker collapses into a "YouTube fallback" expander inside the lossless card and disappears as a top-level concern.

## Non-goals

- **No per-track override** ("download this one as m4a anyway"). Triage UI for individual tracks is out of scope; users with strong feelings about a specific track can flip the global fallback toggle, run sync, flip back.
- **No per-playlist policy.** Same reasoning — the dial is global.
- **No auto-skip / give-up bound.** Tracks stay deferred until a source can serve them. The earlier "auto-skip after 24h" option was rejected in brainstorming; if a permanent backlog turns out to be a real problem we can revisit.
- **No system notification** when the banner triggers. A 30-min recoverable state is too noisy for a cross-app push; the in-app banner is the right surface.
- **No "lossless required for new tracks only, leave existing m4a alone" toggle.** This is already implicit: deferral only fires on the next download attempt, so existing m4a files stay where they are.
- **No re-download of existing m4a as FLAC.** A user who flips into strict mode and wants to upgrade existing files manually deletes + re-downloads (out of scope).
- **No changes to the lossless source registry, captcha interceptor, or rate limiter.** This spec consumes their existing surfaces; it does not modify them.
- **No changes to the v0.9.11 `LosslessQualityTier` picker** (`CD / Hi-Res / Max`). That dial is orthogonal — it controls lossless quality once a source has been picked.

## Design

### 1. Data model

#### 1.1 New preference: `youtubeFallbackEnabled`

Location: `data/download/src/main/kotlin/com/stash/data/download/lossless/LosslessSourcePreferences.kt`

```kotlin
private val youtubeFallbackKey = booleanPreferencesKey("youtube_fallback_enabled")

/**
 * When the lossless registry cannot serve a track, whether to fall
 * through to the yt-dlp pipeline (m4a / Opus) instead of deferring
 * the download. Defaults to `false` (v0.9.17+) — strict FLAC is the
 * new contract.
 *
 * Existing users who flip this to true match v0.9.16 silent-fallback
 * behaviour exactly; the legacy yt-dlp tier picker (MAX/BEST/HIGH/
 * NORMAL/LOW) governs the fallback's quality.
 */
val youtubeFallbackEnabled: Flow<Boolean> = context.losslessDataStore.data.map { prefs ->
    prefs[youtubeFallbackKey] ?: false
}

suspend fun youtubeFallbackEnabledNow(): Boolean = youtubeFallbackEnabled.first()

suspend fun setYoutubeFallbackEnabled(value: Boolean) {
    context.losslessDataStore.edit { prefs -> prefs[youtubeFallbackKey] = value }
}
```

Default `false` for everyone — fresh installs and existing v0.9.16 users alike. No migration shim. The Home banner from §4 is the in-context explanation when an existing user's first deferral triggers.

The pref lives on `LosslessSourcePreferences` (not its own class) so all lossless-related settings stay in one DataStore — same precedent as `enabled`, `qualityTier`, `captchaCookieValue`, `bannerDismissed`.

#### 1.2 New `DownloadStatus` enum value: `WAITING_FOR_LOSSLESS`

Location: `core/model/src/main/kotlin/com/stash/core/model/DownloadStatus.kt`

```kotlin
enum class DownloadStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
    FAILED,
    SKIPPED,
    WAITING_FOR_LOSSLESS,  // NEW
}
```

Rationale for a new value rather than reusing `FAILED` or `PENDING`:

- `FAILED` carries "give up / move on" semantics in `DiffWorker` and Library Health — would surface as red errors and bias the user toward thinking something is broken when the deferral is deliberate.
- `PENDING` means "queued, never tried." Reusing it would lose the signal that we already attempted lossless and were declined, so `LosslessRetryWorker` (§3) couldn't differentiate first-attempt rows from waiting-for-retry rows, and the banner count query would have nothing to filter on.

A distinct status keeps existing terminal states clean and lets the queue-count query in `TrackDao` filter precisely.

#### 1.3 Schema migration

Room schema bumps from v21 → v22. The migration is a no-op DDL change (the column is `TEXT` storing the enum name, so the new value parses without an `ALTER TABLE`). Migration test asserts that v21 rows with existing statuses parse cleanly under v22.

`Converters.DownloadStatusConverter` already round-trips via `enum.name`/`valueOf` — no change.

### 2. Settings UX

The Lossless audio card swallows the YouTube quality picker when lossless is on. The standalone "Audio Quality" Settings card is conditionally rendered:

- `losslessEnabled = false` → "Audio Quality" card visible at top level (today's location), governs yt-dlp tier exactly as it does in v0.9.16.
- `losslessEnabled = true` → "Audio Quality" card hidden. Inside the Lossless card, a new "YouTube fallback" expander hosts both the on/off toggle and the legacy YT-tier picker.

```
┌─ Audio Quality ────────────────────────────────────┐
│  (visible only when Lossless is OFF)              │
│  ◉ Max  ○ Best  ○ High  ○ Normal  ○ Low           │
└────────────────────────────────────────────────────┘

┌─ Lossless audio ───────────────────────────────────┐
│  Lossless  [●━━━]                                 │
│  ┌────────────────────────────────────────────┐   │
│  │ ROUTING                                    │   │
│  │ ↳ kennyy.com.br  ● active                  │   │
│  │ ↳ squid.wtf      ○ optional  solve →       │   │
│  ├────────────────────────────────────────────┤   │
│  │ Lossless quality                           │   │
│  │ ◉ Max  ○ Hi-Res  ○ CD                      │   │
│  ├────────────────────────────────────────────┤   │
│  │ ▼ YouTube fallback   off                   │   │
│  │   (expanded:)                              │   │
│  │   [○━━━]  Use YouTube when lossless fails  │   │
│  │   ◉ Max  ○ Best  ○ High  ○ Normal  ○ Low   │   │
│  ├────────────────────────────────────────────┤   │
│  │ ▼ Advanced (cookie paste, reset breaker)   │   │
│  └────────────────────────────────────────────┘   │
└────────────────────────────────────────────────────┘
```

The "YouTube fallback" expander is collapsed by default (matching the off state). When the user opens it and flips the toggle on, the existing `QualityTier` radio rows render below — same composables as the standalone card, just relocated. No new picker code; just a shared `@Composable AudioQualityPicker(...)` extracted from the current `SettingsScreen` content.

The "Advanced" expander stays where it is — cookie-paste textbox + rate-limiter reset.

### 3. Download pipeline + retry semantics

#### 3.1 `TrackDownloadWorker` — defer instead of fall through

Location: `core/data/src/main/kotlin/com/stash/core/data/sync/workers/TrackDownloadWorker.kt`
(plus the search-tab equivalent in `data/download/src/main/kotlin/com/stash/data/download/search/SearchDownloadCoordinator.kt`)

Pseudocode for the resolve branch:

```kotlin
val match: SourceResult? = runCatching { losslessRegistry.resolve(query) }.getOrNull()
when {
    match != null -> downloadFromLossless(match)

    losslessPrefs.youtubeFallbackEnabledNow() ->
        downloadFromYtDlp()  // existing behavior

    else -> {
        trackDao.setDownloadStatus(track.id, WAITING_FOR_LOSSLESS)
        // Don't return Result.retry() — that triggers WorkManager
        // exponential backoff which would burn cycles. The reactive
        // triggers in §3.3 are the right re-attempt signal.
        Result.success()
    }
}
```

`losslessEnabled = false` short-circuits before the registry call and keeps today's pure-yt-dlp path — no behavior change.

#### 3.2 `LosslessRetryWorker` — sweep deferred set on demand

Location: `core/data/src/main/kotlin/com/stash/core/data/sync/workers/LosslessRetryWorker.kt`

A `OneTimeWorkRequest` (not periodic) that:

1. Reads all rows where `download_status = 'WAITING_FOR_LOSSLESS'` via a new `TrackDao.waitingForLosslessTracks(): List<TrackEntity>` query.
2. For each row, calls `losslessRegistry.resolve(query)`. If non-null, sets `download_status = PENDING` so the standard `TrackDownloadWorker` picks it up on next sync; if still null, leaves `WAITING_FOR_LOSSLESS` in place.
3. Uses the standard `Constraints` from `DownloadNetworkPreference` (matches `TrackDownloadWorker` — wifi/charging/etc.) so it doesn't fire on metered if the user disabled that.
4. Tagged with `unique` work name `lossless-retry` so multiple triggers within a short window collapse to a single sweep.

Doesn't actually download — it only re-resolves and re-queues. The standard worker chain owns the actual byte fetch.

#### 3.3 `LosslessRetryScheduler` — reactive triggers

Location: `data/download/src/main/kotlin/com/stash/data/download/lossless/LosslessRetryScheduler.kt`

A `@Singleton` that holds a `CoroutineScope(SupervisorJob() + Dispatchers.IO)` (mirrors `SquidWtfCaptchaInterceptor`) and collects three flows. Each emits → enqueue `LosslessRetryWorker`:

| Trigger | Source | Why |
|---|---|---|
| Captcha cookie value changes (any non-equal transition; debounced 1s) | `LosslessSourcePreferences.captchaCookieValue` | Fresh cookie ⇒ squid is back. Mirrors the existing `SquidWtfCaptchaInterceptor` reactive collector pattern. |
| `QobuzSource.lastKnownBadCookie` clears (becomes null OR != current cookie) | `QobuzSource.lastKnownBadCookie` (StateFlow added in v0.9.16 hotfix) | Catches the case where the user pastes the same value twice, or where the source self-clears after a successful re-attempt. |
| Circuit breaker resets for `kennyy_qobuz` or `squid_qobuz` | `AggregatorRateLimiter.circuitResetEvents: SharedFlow<String>` (NEW emit-side hook on the rate limiter — emits the source id when a breaker timeout elapses or `reset()` is called) | Kennyy outage recovers organically after the 30-min breaker timeout; this is the one signal the user can't trigger themselves. |

Wired up in the existing `LosslessModule` Hilt graph. Bound eagerly via `@Inject` from `StashApplication.onCreate()` (same pattern as `SquidWtfCaptchaInterceptor` today) so collectors start at process boot, not first-screen-show.

Three things this does NOT include:

- **Periodic polling.** No background "every N minutes, poke the deferred queue" worker. The signals above cover every transition that could plausibly turn null into non-null.
- **Per-track give-up bound.** Tracks stay deferred until a source can serve them. (Out-of-scope per Non-goals.)
- **Manual "retry now" button on the banner.** The banner action is "solve captcha →" because that's the only action a user can take. A separate manual retry would just duplicate the existing reactive triggers.

### 4. Home banner

Lives on the Home screen alongside the existing tip-jar / lossless-onboarding banners. NOT on the Sync card — Sync surfaces Spotify/YT sync health, not download-pipeline state, and Home is where the user notices count changes.

#### 4.1 Render rule

Show iff `WAITING_FOR_LOSSLESS` count ≥ 1. The count is observed via a new `TrackDao.waitingForLosslessCount(): Flow<Int>` query (parallels the existing `pendingScrobbleCount()` shape — single-row aggregation, indexed on `download_status`).

Banner is **dismissible per-session** (not forever) — one-tap dismissal hides it for the current Home foreground, but it reappears on next foreground if the count is still ≥ 1. Forever-dismissed semantics (like the lossless-onboarding banner) would defeat the actionable signal; per-session feels like a polite "I'm aware, stop reminding me right now" without losing the eventual prompt.

#### 4.2 Copy + action by state

`HomeViewModel` derives the banner state by combining four inputs:

- `waitingForLosslessCount: Int` (must be ≥ 1)
- `losslessPrefs.captchaCookieValue: String?`
- `qobuzSource.lastKnownBadCookie: String?`
- `aggregatorRateLimiter.stateOf("kennyy_qobuz").isCircuitBroken: Boolean`

| Squid state | Kennyy state | Copy | Action |
|---|---|---|---|
| Expired (cookie matches lastKnownBadCookie) | (any) | `N tracks waiting — squid.wtf cookie expired` | `solve captcha →` opens `SquidWtfCaptchaScreen` |
| Not configured (cookie empty) | Down (circuit broken) | `N tracks waiting — set up a lossless source` | `connect →` opens `SquidWtfCaptchaScreen` |
| Active (cookie set, not last-bad) | Down | `N tracks waiting — kennyy.com.br temporarily down` | `dismiss` only (auto-recovers via circuit-breaker reset) |
| Active | Up | (banner shouldn't render — count should be 0; if it does, retry sweep is lagging) | `retry →` enqueues `LosslessRetryWorker` directly |

Banner state derivation is a pure function (`bannerStateFor(...)`) — testable in isolation from the full `HomeViewModel` graph.

### 5. Edge cases

#### 5.1 User flips `losslessEnabled` off mid-deferred

All `WAITING_FOR_LOSSLESS` rows must be re-queued as `PENDING` immediately so the standard yt-dlp pipeline picks them up. Implemented as a `setEnabled(false)` side-effect that runs `trackDao.requeueWaitingForLossless()` (single `UPDATE` statement). Test required.

#### 5.2 User flips `youtubeFallbackEnabled` on mid-deferred

Same — re-queue as `PENDING`. The deferred state only makes sense under "lossless on + fallback off". The hook is a `setYoutubeFallbackEnabled(true)` side-effect that runs the same `requeueWaitingForLossless()`. Test required.

#### 5.3 Track removed from a playlist while deferred

The existing orphan-cleanup path in `DiffWorker` handles `PENDING` rows by deleting them when the track no longer belongs to any playlist. We extend that orphan-cleanup query to also delete `WAITING_FOR_LOSSLESS` rows so the deferred set doesn't grow stale. Test required (parallels existing orphan-cleanup tests).

#### 5.4 Both sources permanently unavailable

Kennyy returns 5xx repeatedly (circuit trips, doesn't auto-reset because the operator stays down) AND squid never configured. Both sources unavailable indefinitely → tracks stay deferred → banner shows "kennyy.com.br temporarily down" with no action button → user just waits. This is the accepted behavior for v0.9.17. If it turns out to be a real complaint we revisit with a "give up" mechanism in a later release.

#### 5.5 Search-tab download into deferred

The search-tab pipeline (`SearchDownloadCoordinator`) has its own status flow (`SearchDownloadStatus`) distinct from `DownloadStatus`. When deferral fires from search, the coordinator emits a new `SearchDownloadStatus.WaitingForLossless` variant so the search-tab UI shows the right message instead of the existing `Failed("...")` it would otherwise produce. The track row in the DB still gets `WAITING_FOR_LOSSLESS` so the banner counts it.

### 6. Test surface (TDD)

Before any implementation:

| Test | Module | What it asserts |
|---|---|---|
| `TrackDownloadWorkerTest`: registry-null + fallback-off → marks `WAITING_FOR_LOSSLESS` | `:core:data` | New deferral path |
| `TrackDownloadWorkerTest`: registry-null + fallback-on → falls through to yt-dlp | `:core:data` | Backward-compat path preserved |
| `LosslessRetryWorkerTest`: deferred row resolves → status flips to `PENDING` | `:core:data` | Sweep recovery |
| `LosslessRetryWorkerTest`: deferred row still null → status stays `WAITING_FOR_LOSSLESS` | `:core:data` | No accidental skip |
| `LosslessRetrySchedulerTest`: cookie-change emission → enqueues worker | `:data:download` | Reactive trigger 1 |
| `LosslessRetrySchedulerTest`: lastKnownBadCookie cleared → enqueues worker | `:data:download` | Reactive trigger 2 |
| `LosslessRetrySchedulerTest`: circuit-reset event → enqueues worker | `:data:download` | Reactive trigger 3 |
| `HomeViewModelTest.bannerStateFor`: four-input tuple → four banner variants (parameterised) | `:feature:home` | Pure mapping correctness |
| `LosslessSourcePreferencesTest.youtubeFallbackEnabled`: defaults to `false`; persistence round-trip | `:data:download` | Default-flip safety |
| `MigrationV21V22Test`: existing rows with v21 statuses parse under v22 schema | `:core:data` | Schema bump no-op |
| `OrphanCleanupTest`: `WAITING_FOR_LOSSLESS` orphans deleted same as `PENDING` orphans | `:core:data` | Edge case 5.3 |
| `LosslessSourcePreferencesTest`: flipping `enabled = false` requeues deferred rows | `:data:download` | Edge case 5.1 |
| `LosslessSourcePreferencesTest`: flipping `youtubeFallbackEnabled = true` requeues deferred rows | `:data:download` | Edge case 5.2 |
| `SettingsScreenTest`: `losslessEnabled = true` hides top-level Audio Quality card; YT-fallback expander renders inside lossless card | `:feature:settings` | UI relocation |

### 7. Versioning + ship

- `versionCode`: 54 → 55
- `versionName`: 0.9.16 → 0.9.17
- Schema: 21 → 22
- New branch: `feat/flac-only-mode`, worktree at `.worktrees/flac-only-mode`. Don't forget to copy `local.properties` into the worktree (per project memory — `git worktree add` doesn't transfer it).
- After merge to master, tag + push triggers the release workflow as usual.

## Open questions

None blocking implementation. Possible follow-ups deferred to later releases:

- **Auto-skip after N days for "permanently deferred" tracks** — only revisit if the strict-FLAC backlog turns into a real complaint.
- **Per-track override** ("download this one as m4a anyway") — only revisit if users repeatedly ask for granular control.
- **System notification on banner trigger** — only revisit if the in-app banner is too quiet for daily use.
