# Refresh / Accumulate Mix Modes — Fix & Design

**Date:** 2026-06-28
**Status:** Designed (brainstorm approved; not yet built)
**Author:** brainstorm session

## Summary

Stash already has a per-source `SyncMode` (REFRESH / ACCUMULATE) for imported
algorithmic playlists (Spotify Daily Mixes / Discover Weekly, YouTube Music
mixes), chosen via chips on the Sync screen. The intent is documented but **not
honored at the deletion layer**, so the modes don't behave as users expect.

This fixes three things:

1. **ACCUMULATE must never delete anything.** Today the orphan-cleanup sweep
   (`MusicRepositoryImpl.cleanOrphanedMixTracks()`) deletes downloaded tracks
   **and their audio files** for any track that falls out of all active
   playlists — and it is **not gated by `SyncMode` at all**. So a deselected
   playlist, a rotated mix, or a disconnected source can silently delete library
   tracks even when the user is "accumulating." Fix: the sweep becomes a no-op
   whenever **any** source is set to ACCUMULATE (the library is append-only).

2. **REFRESH should warn before it deletes.** Switching a source to REFRESH (the
   mode that rotates old tracks out and removes their downloads) currently
   happens silently. Fix: a confirmation dialog at the moment of switching to
   Refresh, explaining what it does.

3. **ACCUMULATE becomes the default** (was REFRESH), so the safe never-delete
   behavior is the out-of-the-box one.

## Goals / Non-goals

**Goals**
- In ACCUMULATE, **no automatic deletion** of downloaded tracks or files — in
  online (streaming) or offline (downloaded) mode, ever. Mixes stack
  continuously.
- In REFRESH, old tracks rotate out of the mix and their downloads are removed
  to keep the library lean — but only after a one-time confirmation dialog.
- "Enough" (stop accumulating) is signalled by simply switching that source to
  Refresh. No new cap/prune mechanism.
- New default is ACCUMULATE.

**Non-goals (YAGNI)**
- No per-mix size cap, no "keep newest N" pruning, no manual "clear backlog"
  button. (Switching to Refresh is the prune path.)
- No change to the per-source UI model (Spotify and YouTube chips stay
  independent). No global mode toggle.
- No change to how playlists are fetched/diffed beyond the deletion gate.
- No change to manual track deletion (user-initiated delete is always allowed,
  in any mode).

## Current behavior (as found)

- `SyncMode` enum (`core/model`): REFRESH / ACCUMULATE.
- `SyncPreferencesManager` (`core/data/sync`): per-source modes
  (`spotifySyncMode`, `youtubeSyncMode`) in the `sync_preferences` DataStore,
  defaulting to **REFRESH** (data-class defaults + both `resolve*Mode`
  fallbacks). A legacy global `sync_mode` key migrates into the Spotify slot.
- `DiffWorker` (`core/data/sync/workers`): per playlist, picks the source's mode.
  In REFRESH it calls `playlistDao.clearSyncedPlaylistTracks(playlistId)` before
  re-inserting the current set (user-added `locally_added=1` rows survive). In
  ACCUMULATE it keeps existing membership and only adds new. After the diff it
  calls `musicRepository.cleanOrphanedMixTracks()`.
- `MusicRepositoryImpl.cleanOrphanedMixTracks()`: deletes downloaded tracks +
  audio files + DB rows for every `getOrphanedDownloadedTracks()` row (downloaded,
  `source != 'BOTH'`, not a member of any playlist with `removed_at IS NULL`),
  except those protected by the active discovery queue. **Not gated by SyncMode.**
  Called from (a) `DiffWorker` after each sync and (b) the startup sweep in
  `MusicRepositoryImpl`.
- A separate startup/sync step cancels pending `download_queue` rows whose tracks
  no longer belong to any sync-enabled playlist.
- No refresh-warning dialog exists; the Sync screen just has the chip row
  (`SyncModeChipRow` in `SyncScreen.kt`).

The discrepancy: the `SyncMode.REFRESH` KDoc says "Old tracks stay in the library
but are removed from the playlist view," but `cleanOrphanedMixTracks` actually
deletes them from the library + disk. This design makes the **behavior** the
source of truth (Refresh does delete, with a warning) and updates the KDoc to
match.

## Design

### 1. Deletion gate (the core fix)

Single root-cause guard in the one function that deletes — covers every caller
(sync + startup) in one place.

- **`SyncPreferencesManager.anyAccumulate(): Boolean`** (new, suspend or a
  `Flow`-backed `.first()` read): `spotifySyncMode.first() == ACCUMULATE ||
  youtubeSyncMode.first() == ACCUMULATE`. Reads the resolved per-source modes, so
  it honors explicit choices + the new default. (A source set to Accumulate
  disables deletion even if it currently has no playlists — the simplest, safest
  rule, matching the approved "any Accumulate source → no deletion.")

- **`MusicRepositoryImpl.cleanOrphanedMixTracks()`**: at the very top,
  `if (syncPreferencesManager.anyAccumulate()) { log("skipped orphan sweep —
  accumulate mode active"); return 0 }`. `MusicRepositoryImpl` must gain a
  `SyncPreferencesManager` dependency (constructor `@Inject`; it's a
  `@Singleton`, so no module change).

- **Pending-download orphan cancel** (the separate "cancel download_queue rows
  for tracks no longer in any sync-enabled playlist" step): gate it with the same
  `anyAccumulate()` check so Accumulate doesn't cancel in-flight/queued downloads
  of accumulated tracks. (Locate its exact call site during implementation; it's
  near the startup sweep + in the sync finalize path.)

Result: whenever any source is Accumulate, the library is append-only — deselect,
rotate, or disconnect can't delete. When all sources are Refresh, the sweep runs
exactly as today.

### 2. Refresh warning dialog

- **Trigger:** in `SyncScreen` / `SyncViewModel`, tapping the **Refresh** chip
  *while that source's current mode is ACCUMULATE* opens an `AlertDialog` instead
  of applying immediately. (Selecting Refresh when already Refresh, or selecting
  Accumulate from any state, applies with no dialog — Accumulate only ever
  protects.)
- **Content (copy, final wording TBD in build):**
  - Title: "Switch to Refresh?"
  - Body: "Refresh pulls fresh mixes each sync — your Daily Mixes, Discover
    Weekly, and other rotating playlists. Tracks that rotate out will be removed
    from the mix **and their downloads deleted** to keep your library lean,
    including tracks you've accumulated so far. Tracks you added manually are
    kept."
  - Confirm button: "Switch to Refresh" → calls the existing
    `onSpotifySyncModeChanged(REFRESH)` / `onYoutubeSyncModeChanged(REFRESH)`.
  - Dismiss button: "Cancel" → no change.
- **State:** a small piece of `SyncUiState` (e.g. `pendingRefreshSource:
  MusicSource?`) drives dialog visibility; the ViewModel sets it on chip tap and
  clears it on confirm/cancel. Mirrors existing dialog patterns in the feature.

### 3. Default = ACCUMULATE

Change all REFRESH defaults to ACCUMULATE in `SyncPreferencesManager`:
- `SyncPreferences.spotifySyncMode` / `youtubeSyncMode` data-class defaults.
- `resolveSpotifyMode` final fallback (after the legacy-key check) → ACCUMULATE.
- `resolveYoutubeMode` fallback → ACCUMULATE.
- `SyncViewModel`'s transient `SyncUiState` initial values (cosmetic — the Flow
  overwrites them — but keep consistent).

**Migration:** users with an explicit per-source choice (or the legacy global
key) are unaffected. Users who never set a mode flip from implicit-Refresh to
Accumulate on upgrade — intended and safe (stops auto-deletion; opt into Refresh
is one tap, now with the warning). Update the `SyncMode` enum KDoc so REFRESH
documents the real delete-on-rotate behavior and ACCUMULATE documents the
never-delete guarantee.

### Data flow

```
Sync screen chip tap (Refresh, from Accumulate)
  → SyncViewModel sets pendingRefreshSource
  → AlertDialog
      Confirm → setSpotify/YoutubeSyncMode(REFRESH)  (existing path)
      Cancel  → clear pending, no change

Sync run (DiffWorker) / app startup (MusicRepositoryImpl)
  → cleanOrphanedMixTracks()
      anyAccumulate()?  yes → return 0 (no deletion)      ← THE GATE
                        no  → existing orphan delete (Refresh-only)
  → pending-download orphan cancel: same anyAccumulate() gate
```

## Error handling / edge cases

- `anyAccumulate()` read failure (DataStore) → treat as "accumulate" (fail safe:
  never delete on an unreadable preference). The `?:`/`runCatching` default
  resolves to ACCUMULATE.
- Mixed modes (Spotify=Refresh, YouTube=Accumulate): per the approved global
  rule, **no deletion happens** while YouTube accumulates — Refresh still rotates
  the Spotify mix *view* (DiffWorker clears synced membership) but the rotated-out
  files persist until the user sets all sources to Refresh. This is the
  documented, intended consequence of "any Accumulate → append-only"; the warning
  copy notes cleanup happens when accumulating stops.
- Manual delete, blocklist delete, and "missing file" reconciliation are
  unaffected (not part of the orphan sweep gate).
- Stash's own mixes (`StashMixRefreshWorker`) are a *view* over already-downloaded
  library tracks; they don't create orphan-able downloads. Verify during build
  that the Stash-mix refresh path doesn't independently delete library tracks —
  if it does, gate it identically. (Expected: no change needed.)

## Testing

**Unit**
- `SyncPreferencesManager`: default (no keys) resolves to ACCUMULATE for both
  sources; explicit/legacy values still resolve correctly; `anyAccumulate()` true
  when either source is ACCUMULATE, false only when both are REFRESH.
- `MusicRepositoryImpl.cleanOrphanedMixTracks()` (fake DAOs + fake prefs):
  anyAccumulate=true → returns 0, deletes nothing (no `deleteTrackFile`, no
  `trackDao.delete`); anyAccumulate=false → deletes orphans as before
  (preserving the existing discovery-queue protection).
- Pending-download orphan cancel: gated identically.
- `SyncViewModel`: tapping Refresh from Accumulate sets `pendingRefreshSource`
  and does NOT change the mode; confirm changes it; cancel clears without change;
  tapping Accumulate changes mode with no dialog.

**On-device**
- Accumulate a few mixes, deselect a playlist / let a mix rotate, re-sync +
  relaunch → downloaded files survive (no deletion).
- Switch a source to Refresh → warning dialog appears; confirm → next sync rotates
  + cleans up orphaned downloads; cancel → stays Accumulate, nothing deleted.
- Fresh install → both sources default to Accumulate.

## Open questions / risks
- Exact dialog copy is final-tuned during build (content above is the intent).
- Confirm the precise call site of the pending-download orphan-cancel step and
  that gating it has no unintended effect on legitimately-removed tracks (it only
  defers cancellation while accumulating).
