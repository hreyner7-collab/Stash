# Online Streaming Engine — Design Spec

**Date:** 2026-05-15
**Status:** Brainstormed, awaiting plan
**Author:** rawnaldclark + Claude (brainstorming session)
**Scope:** Subproject A of the Online Streaming feature. Subprojects B (Google Play Billing paywall) and C (Cloudflare Worker entitlement backend) are separate specs. While A is being built, the Online toggle is gated behind a build-time debug flag for dogfooding.

## Problem

Stash today is a downloads-only app: every track in the user's library is a local audio file. This is by design and the core of Stash's positioning. But two real user constraints push back on it:

1. **Storage cost** — a typical synced Spotify library is 500-5000 tracks at ~10 MB per FLAC = several gigabytes. Many users on entry-level Android phones (32 GB / 64 GB) can't store their full library and have to choose subsets.
2. **Bandwidth cost on sync** — downloading every track imposes a large up-front bandwidth + time cost on first sync, especially over cellular.

Both are addressable by offering streaming as an alternative playback path. Stash already integrates Monochrome (a community-operated Tidal API proxy) as a lossless download source (`monochrome_tidal` in `LosslessSourceRegistry`); the same source can serve as a streaming URL provider for tracks the user hasn't downloaded.

This spec defines the streaming engine: the technical foundation that lets a track be played without first being downloaded. It does NOT define the subscription paywall (Subproject B) or the entitlement backend (Subproject C). For development, the engine is enabled via a build-time flag.

## Goals

1. **A "Streaming" mode** that, when on, lets the user tap any synced track and play it via Monochrome → ExoPlayer, with no prior download.
2. **A "Streaming" toggle at the top of the Home tab** — single-row switch, always visible, primary affordance.
3. **Non-destructive mode transitions** — prompts at toggle time so the user explicitly chooses what to do with their existing downloads.
4. **Catalog gap handling** — tracks not in Monochrome's Tidal catalog are surfaced as unavailable with a clear escape hatch.
5. **Mid-stream URL refresh** — pause-and-resume hours later works without playback errors.
6. **Cellular streaming preference** — never burn the user's data plan without explicit opt-in.
7. **Subscription-lapsed transition** — if the (future) entitlement backend reports an expired subscription, mode auto-flips to Offline cleanly.

## Non-Goals

These are out of scope for the v1 streaming engine. Most are deferred to later releases.

- **Subscription / paywall.** Subproject B.
- **Server-side entitlement validation.** Subproject C.
- **Spotify Connect equivalent** / cast-to-other-device.
- **Online-only playlists from Monochrome itself** (e.g., Tidal-curated mixes). Stream-only access to the user's own synced library is the scope.
- **YouTube fallback for streaming.** Download via YT remains as today; streaming via YT does not. YouTube's anti-bot measures plus mid-stream URL signing make it unreliable for streaming.
- **Stash Mixes via streaming.** Daily Discover / Deep Cuts / First Listen stay download-based for v1.
- **Album / artist drill-in via Monochrome catalog** ("show me all this artist's albums on Tidal"). Limit Online mode to the user's own synced library + downloads for v1.
- **First-toggle tooltip / onboarding card.** Polish; pickable for v1 follow-up.
- **Per-source priority** for streaming. Only Monochrome (Tidal) is supported. A future spec can add Qobuz streaming via squid.wtf alongside.

## Architecture

```
┌─────────────────────────────────────────────────────────┐
│  Home tab — single-row toggle                           │
│      ┌─────────────────────────────────────┐            │
│      │ Online              [ OFF ]         │            │
│      │ Stream from your synced library     │            │
│      └─────────────────────────────────────┘            │
│         ↓ writes                                        │
│  StreamingPreference  (DataStore boolean)               │
└─────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────┐
│  Sync path                                              │
│  ───────────                                            │
│  if (streamingEnabled):                                 │
│    DiffWorker inserts TrackEntity with                  │
│       is_downloaded = 0, file_path = NULL               │
│    Enqueue AvailabilityCheckWorker (background)         │
│  else:                                                  │
│    Existing behavior — insert + TrackDownloadWorker     │
└─────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────┐
│  AvailabilityCheckWorker                                │
│  ────────────────────────                               │
│  For every freshly-imported metadata row, call          │
│  MonochromeSource.resolve(query) once. Write the        │
│  result to tracks.is_streamable (TRUE / FALSE).         │
│  Does NOT cache URLs — they have TTLs that would        │
│  expire before the user taps.                           │
│  Runs in a serialised queue at 1 req/s to respect       │
│  AggregatorRateLimiter.                                 │
└─────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────┐
│  Playback path (tap a track)                            │
│  ──────────────                                         │
│  if (track.is_downloaded && file exists on disk):       │
│      play local                                         │
│  else if (streamingEnabled && track.is_streamable):     │
│      check StreamUrlCache; resolve if miss/expired      │
│      build MediaItem.fromUri(streamUrl, headers)        │
│      wrap in RefreshingDataSource.Factory               │
│      play via ExoPlayer                                 │
│  else if (!streamingEnabled):                           │
│      grey row, no tap action (or "Download to play")    │
│  else (is_streamable = false):                          │
│      "Unavailable" — long-press for YouTube search     │
└─────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────┐
│  RefreshingDataSource.Factory                           │
│  ────────────────────────────                           │
│  DataSource wrapper around DefaultHttpDataSource.       │
│  On 403/410 mid-stream:                                 │
│    1. Pause player                                      │
│    2. Re-resolve via MonochromeStreamResolver           │
│    3. Update MediaItem URI in-place                     │
│    4. Resume from current byte offset                   │
│  This makes pause-then-resume-hours-later work without  │
│  player errors.                                         │
└─────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────┐
│  StreamCache (Media3 SimpleCache, 500 MB LRU)           │
│  ──────────────────────────────────────────             │
│  Separate from the existing PreviewCache so eviction    │
│  policies don't collide. Bytes already streamed during  │
│  playback get reused — same-session replay is instant   │
│  + free. Invisible to user; not surfaced as "downloads."│
└─────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────┐
│  Pre-fetch next track                                   │
│  ─────────────────────                                  │
│  When current track is >60% played, look up the next    │
│  queue item; if streamable + not in cache, call         │
│  MonochromeStreamResolver eagerly. Auto-advance         │
│  latency drops from 300-800ms to <50ms.                 │
└─────────────────────────────────────────────────────────┘
```

## Data Model

### Schema delta (Room migration v25→v26)

One new column on `tracks`:

| Column | Type | Meaning |
|---|---|---|
| `is_streamable` | INTEGER (Boolean) NOT NULL DEFAULT 0 | Per-track availability flag set by `AvailabilityCheckWorker`. `1` = Monochrome returned a confident match. `0` = not yet checked OR confirmed unavailable. The "not yet checked" vs "unavailable" distinction is encoded by a separate `is_streamable_checked_at INTEGER` column. |
| `is_streamable_checked_at` | INTEGER (epoch ms) nullable | Timestamp of last availability check. `NULL` = never checked. Used by the worker to re-check stale rows (e.g., monthly catalog refresh; tracks delisted by the operator). |

Existing state combinations the UI must handle:

| `is_downloaded` | `file_path` | `is_streamable` | `is_streamable_checked_at` | Meaning |
|---|---|---|---|---|
| 1 | non-null | * | * | Downloaded, plays locally |
| 0 | NULL | 1 | non-null | Streamable-only (needs Online mode to play) |
| 0 | NULL | 0 | non-null | Confirmed unavailable on Monochrome |
| 0 | NULL | 0 | NULL | Not yet checked (transient; AvailabilityCheckWorker will resolve) |

### Migration

```kotlin
val MIGRATION_25_26 = object : Migration(25, 26) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE tracks ADD COLUMN is_streamable INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE tracks ADD COLUMN is_streamable_checked_at INTEGER")
    }
}
```

Additive, no data loss. Existing rows have `is_streamable = 0, is_streamable_checked_at = NULL` (the "not yet checked" state) — a one-time `AvailabilityCheckWorker` enqueue at first launch after upgrade fills them in.

### Query audit (every DAO method that filters on `is_downloaded = 1`)

Every existing query that returns "the user's library" filters `WHERE is_downloaded = 1`. In Online mode those queries need to also return `is_downloaded = 0 AND is_streamable = 1` rows. Two patterns:

1. **Two query variants per DAO method** — `getAllAlbumsDownloaded` / `getAllAlbumsStreaming` etc. Simple but doubles the surface area.
2. **One query parameterised on a mode flag** — pass `includeStreamable: Boolean` to each method. Easier to evolve.

The implementation plan picks one (recommendation: pattern 2, single parameterised query) and applies it consistently.

**Where is `includeStreamable` read from?** The `StreamingPreference.enabled` flow is read once at the ViewModel / Repository layer (whichever owns the screen's state) and threaded down to the DAO call as a Boolean. DAOs are pure — they don't read preferences. ViewModels and repository methods that already accept other state arguments accept this one too. This keeps preference access centralized at the state-owner layer and avoids ad-hoc preference reads scattered across the data tier.

Methods to audit and update:

- `getAllAlbums`
- `getAllArtists`
- `getAllDownloadedTracks`
- `getByPlaylist`
- `getTotalCount`
- `searchTracks` (FTS)
- Library-Health-related queries (the format/quality buckets only count downloaded tracks — keep them downloaded-only). The Health tab's "X tracks" total likewise stays as "X downloaded" regardless of mode — that screen is about on-disk audit, not library size.
- Playlist visibility (`getAllVisible`) — already filters by `is_active`; needs to also include streamable when mode is on

## Preferences

### New: `StreamingPreference` (DataStore)

```kotlin
@Singleton
class StreamingPreference @Inject constructor(
    @ApplicationContext context: Context,
) {
    val enabled: Flow<Boolean>            // default false
    val streamOnCellular: Flow<Boolean>   // default false
    val streamQuality: Flow<StreamQualityTier>  // default LOSSLESS

    suspend fun setEnabled(value: Boolean)
    suspend fun setStreamOnCellular(value: Boolean)
    suspend fun setStreamQuality(tier: StreamQualityTier)
}
```

- `enabled` — the master Online toggle. Written by the Home-tab switch.
- `streamOnCellular` — when false, the player refuses to start a stream while the device is on cellular. Existing `ConnectivityManager` integration handles the check. Refusal renders as a snackbar: "Streaming paused — on cellular. Enable in Settings → Streaming."
- `streamQuality` — Lossless 16/44.1 (default) or High-quality lossy (~256 kbps AAC if Monochrome supports a lower tier). Independent from the existing `LosslessQualityTier` (which is for downloads).

### Subscription gating (deferred to Subproject B, but design shape locked in)

The `StreamingPreference.enabled = true` state requires an active entitlement. For now (Subproject A), a build-time `BuildConfig.STREAMING_BYPASS_ENTITLEMENT = true` allows free toggle. Once B lands, that constant goes false; tapping the toggle without an entitlement opens the paywall sheet.

## Components — new files

| Path | Responsibility |
|---|---|
| `core/data/.../prefs/StreamingPreference.kt` | DataStore wrapper above |
| `core/media/.../streaming/StreamUrlCache.kt` | In-memory `Map<Long, CachedStreamUrl(url, expiresAt)>` with TTL eviction |
| `core/media/.../streaming/MonochromeStreamResolver.kt` | Reuses the existing `MonochromeSource` to fetch a stream URL + TTL for a given track |
| `core/media/.../streaming/RefreshingDataSourceFactory.kt` | Media3 `DataSource.Factory` that catches 403/410 and re-resolves transparently |
| `core/media/.../streaming/StreamCache.kt` | Hilt-bound `SimpleCache` instance (500 MB LRU) for streamed bytes. Additive to the existing `PreviewCache` — separate disk directory, separate eviction. Combined ceiling on disk: existing PreviewCache size + 500 MB. |
| `core/data/.../sync/workers/AvailabilityCheckWorker.kt` | Per-track Monochrome availability probe; writes `is_streamable` |
| `feature/home/.../StreamingModeToggle.kt` | Compose composable for the top-of-Home switch |
| `feature/home/.../StreamingModePrompt.kt` | Compose dialog for the mode-transition prompts (delete-downloads / download-everything) |

## Components — modified files

- **`PlayerRepositoryImpl`** — extend MediaItem construction to choose between local file URI, cached stream URI, or fresh `MonochromeStreamResolver` resolution.
- **`DiffWorker` (sync)** — when streaming pref on, skip `TrackDownloadWorker.enqueue` and instead enqueue `AvailabilityCheckWorker` for the inserted rows.
- **DAO query audits** — every "library content" query gets a `includeStreamable: Boolean` parameter. Callers pass `streamingPreference.enabled.first()`.
- **`HomeScreen`** — add `StreamingModeToggle` row above the existing "Recently played" section.
- **Library track rows** — small download arrow on downloaded rows (existing visual treatment, keep); greyed-out style for unavailable streamable rows.
- **Long-press track menu** — add "Download for offline" (when row is streamable-only) / "Remove download" (when row is downloaded) / "Search YouTube to download…" (when row is unavailable).
- **Search results** — in Online mode, the tap action is "stream now," with "Download" demoted to a long-press option. In Offline mode, current behavior unchanged.
- **`NowPlayingScreen`** — small wifi-icon prefix on the existing quality text when playing a streamed source.
- **`StashApplication.onCreate`** — enqueue `AvailabilityCheckWorker` for every downloaded-zero, never-checked row at first launch after the v25→v26 migration.

## Flows

### Mode toggle on Home tab

A single-row switch at the top of `HomeScreen`, above "Recently played":

> **Online**   [ OFF / ON ]
> Stream from your synced library

Tapping toggles `StreamingPreference.enabled`. If subscription gating is in place and the user has no entitlement, the tap opens the paywall sheet (Subproject B). Otherwise:

- **Off → On**: show the "keep / release downloads" prompt (see below).
- **On → Off**: show the "download / start fresh" prompt.

### Mode transition: Off → On

```
You have <X> downloaded tracks taking up <Y> GB.

[Keep downloaded]  ←  default
[Release the space]
```

"Keep downloaded": flip `StreamingPreference.enabled = true`, no other changes. Existing downloads remain on disk and continue to play locally. New sync runs land as metadata-only.

"Release the space": for each `is_downloaded = 1` row, delete the file, then set `is_downloaded = 0, file_path = NULL`. Metadata + `is_streamable` flag stay. (Enqueues a `ReleaseDownloadsWorker` so the operation is resumable if backgrounded.)

### Mode transition: On → Off

```
<X> tracks in your library aren't downloaded yet (≈<Y> GB).

[Download now]
[Start fresh]  ←  default
```

"Download now": enqueue `TrackDownloadWorker` for every `is_downloaded = 0 AND is_streamable = 1` track. The user will see download progress in the existing progress UI.

"Start fresh": flip `StreamingPreference.enabled = false`, no other changes. Streamable-only tracks become unplayable until manually downloaded.

### Sync — Online mode

`DiffWorker` reconciles the user's Spotify / YT Music library:

1. For each remote track, compute canonical identity (existing logic).
2. If a matching `TrackEntity` exists, update its metadata (existing logic).
3. Otherwise insert a new `TrackEntity` with `is_downloaded = 0, file_path = NULL, is_streamable = 0, is_streamable_checked_at = NULL`.
4. Collect the inserted row IDs and enqueue them in `AvailabilityCheckWorker`.

The user sees the library populate immediately with metadata + art (via existing `ArtBackfillWorker`). The `is_streamable` flag fills in over the next few minutes as `AvailabilityCheckWorker` resolves each track.

### Availability check

`AvailabilityCheckWorker` is a `CoroutineWorker` that drains rows with `is_streamable_checked_at IS NULL`. It:

1. Pulls a batch of 50 such rows.
2. For each, calls `MonochromeSource.resolve(TrackQuery(...))` via the existing rate-limited path.
3. Writes back: `is_streamable = (result != null ? 1 : 0)`, `is_streamable_checked_at = now()`.
4. Throttled to 1 request per second (Monochrome respects).
5. Backs off (60s) on `429 / circuit_open`.
6. **Does NOT cache the resolved URL** — it would expire before the user taps. The worker only writes a Boolean.

**Lifecycle.** The worker is a `OneTimeWorkRequest`-based `CoroutineWorker` (not periodic). When it finishes a batch, if more `IS NULL` rows remain, it re-enqueues itself via `WorkManager.enqueueUniqueWork(REPLACE)` so progress survives WorkManager's 10-minute soft cap. `DiffWorker` enqueues a fresh `AvailabilityCheckWorker` at the end of each sync that produced new metadata rows. A separate periodic worker re-checks rows older than `RECHECK_AGE_MS = 30 days` to catch operator delistings / catalog additions.

A monthly periodic worker re-checks rows older than 30 days to catch operator delistings / catalog additions.

### Tap-to-play

The existing `Player.Listener.onMediaItemTransition` and the user-tap entry points both end up calling `PlayerRepositoryImpl.play(trackId)`. New decision tree:

```kotlin
suspend fun play(track: TrackEntity) {
    val mediaItem = when {
        track.isDownloaded && fileExistsOnDisk(track.filePath) -> {
            MediaItem.fromUri(Uri.fromFile(File(track.filePath)))
        }
        streamingPref.enabled.first() && track.isStreamable -> {
            val cached = streamUrlCache.get(track.id)
            val streamUrl = cached?.takeIf { !it.isExpired() }
                ?: run {
                    val fresh = monochromeStreamResolver.resolve(track)
                    streamUrlCache.put(track.id, fresh)
                    fresh
                }
            MediaItem.Builder()
                .setUri(streamUrl.url)
                .setMediaId(track.id.toString())
                .setRequestMetadata(
                    MediaItem.RequestMetadata.Builder()
                        .setMediaUri(streamUrl.url)
                        .build()
                )
                .build()
        }
        else -> {
            emitUserMessage("Track unavailable — long-press for options")
            return
        }
    }
    exoPlayer.setMediaItem(mediaItem)
    exoPlayer.prepare()
    exoPlayer.play()
}
```

The streaming-path `MediaItem` flows through ExoPlayer's `MediaSource.Factory`, which is configured with `RefreshingDataSourceFactory` so mid-stream URL expiry is handled transparently.

### Mid-stream URL refresh

`RefreshingDataSourceFactory` wraps `DefaultHttpDataSource.Factory`:

```kotlin
class RefreshingDataSource(
    private val inner: HttpDataSource,
    private val resolver: MonochromeStreamResolver,
    private val cache: StreamUrlCache,
    private val trackId: Long,
) : DataSource by inner {
    override fun open(spec: DataSpec): Long {
        return try {
            inner.open(spec)
        } catch (e: HttpDataSource.InvalidResponseCodeException) {
            if (e.responseCode in setOf(403, 410)) {
                // Stream URL expired. Re-resolve and retry from the same offset.
                val fresh = runBlocking { resolver.resolve(trackId) }
                cache.put(trackId, fresh)
                val newSpec = spec.buildUpon().setUri(Uri.parse(fresh.url)).build()
                inner.open(newSpec)
            } else {
                throw e
            }
        }
    }
}
```

The re-open happens at the same byte offset (Range header carried over via `DataSpec.position`). User experiences a brief pause (the refresh round-trip), then playback resumes.

The `runBlocking { resolver.resolve(...) }` call inside `open()` is intentional and safe: Media3 invokes `DataSource.open` on its own loader thread (never the main thread), so blocking inside is the correct pattern. The synchronous bridge avoids exposing a suspending API across Media3's DataSource interface.

### Pre-fetch next queue item

When `Player.Listener.onPositionDiscontinuity` fires AND the current track is >60% played, look up the next item in the queue. If it's streamable and not in cache, call `MonochromeStreamResolver.resolve(nextTrack)` and warm the cache. Same hook the existing `LosslessUrlPrefetcher` uses for previews.

### Catalog gap UX

Track with `is_streamable = 0 AND is_streamable_checked_at IS NOT NULL`:

- **Library row**: renders greyed-out at 50% opacity. No tap action.
- **Long-press menu**: shows "Search YouTube to download…" — opens the Search tab with the artist + title prefilled. User picks a match (existing flow) and downloads. After download, the row becomes downloaded and plays normally.

### Subscription-lapsed transition (interface for Subproject B)

The streaming engine exposes a single method called by Subproject B's entitlement-status watcher:

```kotlin
suspend fun onEntitlementLost() {
    streamingPreference.setEnabled(false)
    // Any in-progress stream stops cleanly via ExoPlayer's normal stop path.
    // Queue auto-skip happens via the existing `is_streamable && !is_downloaded` check in play().
    userMessages.emit("Subscription expired — downloads still play.")
}
```

The flag flip cascades through the existing reactive flows: the toggle UI updates, library queries re-filter, queue advances skip streamable-only tracks.

### Offline / connectivity loss

Mid-stream connectivity drop: ExoPlayer reports a network error. Player layer catches, shows a one-time snackbar ("No internet — paused"), and pauses cleanly. When connectivity returns, the user manually resumes.

Pre-stream connectivity check on tap: if `streamingPref.enabled && !track.isDownloaded && !connectivity.isConnected`, the tap shows "No internet — can't stream" toast without attempting playback.

The `streamOnCellular` preference adds a second gate: even with connectivity, if it's cellular and the pref is off, refuse with a clear message.

## Library views in each mode

**Offline mode** (default): all library queries pass `includeStreamable = false`. The user sees only their downloaded tracks. Same as today.

**Online mode**: all library queries pass `includeStreamable = true`. The user sees their full synced library:
- Downloaded tracks render normally with the small download arrow.
- Streamable-only tracks render normally with no badge.
- Confirmed-unavailable tracks render greyed-out at 50% opacity.
- Tap → play (local first, stream as fallback, error if neither).

## Now Playing changes

Minimal: a tiny `wifi` icon prefixes the existing quality line on the full Now Playing screen when playing a streamed source. The bottom mini-player gets no indicator (too cramped). Format: `📶 FLAC · 16-bit / 44.1 kHz` for streaming; unchanged `FLAC · 16-bit / 44.1 kHz` for local.

## Testing strategy

| Layer | Tests |
|---|---|
| Unit — `StreamUrlCache` | TTL eviction, hit/miss/expired semantics |
| Unit — `MonochromeStreamResolver` | Happy path, catalog miss, rate-limit, error mapping |
| Unit — `RefreshingDataSource` | 403/410 → re-resolve and resume; 500 → propagate; other codes → propagate |
| Unit — `AvailabilityCheckWorker` | Empty queue → success; batch with mixed availability → correct writes |
| Unit — `PlayerRepositoryImpl.play()` | Decision tree: downloaded / streamable / unavailable / offline |
| Unit — DAO query parameterisation | Each modified query returns the right set for `includeStreamable = true/false` |
| Integration — end-to-end stream | Mock Monochrome returning a static HTTP URL; verify first sample played |
| Integration — mid-stream URL expiry | Inject 403 response after N bytes; verify resume |
| Integration — mode transition prompts | Off→On with mock downloads; On→Off with mock streamable rows |
| Manual — Spotify-synced library | 100+ tracks; verify availability check completes within reasonable time and unavailable rows are accurate |
| Manual — pause-and-resume | Pause for 2 hours, resume; verify URL refresh works without user-visible error |
| Manual — cellular gating | Toggle cellular pref off, attempt stream on cellular; verify snackbar |
| Manual — connectivity loss mid-stream | Airplane-mode toggle; verify clean pause |

## Privacy disclosure

The Online toggle's prompt copy includes one line for honesty:

> Streaming uses a community Tidal proxy. The proxy operator can see what you play.

Not scary, just true. Sets correct user expectations about what changes when they enable streaming vs the existing downloads-only model.

## Rollout

The streaming engine itself is gated by `BuildConfig.STREAMING_ENGINE_ENABLED`. When false (default until Subproject A is reviewed + dogfooded), the Online toggle row doesn't render on Home, the v25→v26 migration runs but `AvailabilityCheckWorker` is never scheduled, and DAO queries always pass `includeStreamable = false`. This lets the schema and code land safely while the runtime path stays dormant.

When the build flag flips to true:
- v25→v26 migration adds the columns
- First launch enqueues `AvailabilityCheckWorker` for existing rows
- Home tab gains the toggle (off by default)
- Subscription gating defers to Subproject B's `BuildConfig.STREAMING_BYPASS_ENTITLEMENT` flag

## Open Questions for Subproject B / C

These get resolved when B and C are designed, but the streaming engine should be aware:

- Entitlement-status flow: how does the engine learn an entitlement is gained / lost? Push-based (broadcast intent) or pull-based (poll a flow)?
- Free-trial UX: does the engine see a "trial-active" state distinct from "subscribed"? (Probably no — engine just sees "allowed / not allowed.")
- Refund / churn UX: same as subscription-lapsed transition, or different copy?

These are noted for B's spec but don't affect A's design today.
