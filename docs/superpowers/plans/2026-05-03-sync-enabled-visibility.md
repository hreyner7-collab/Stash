# v0.9.9 — `syncEnabled` becomes the playlist banlist Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship v0.9.9 — fix the bug where Spotify/YouTube playlists the user has not enabled sync for clutter Home + Library as empty mosaics. Hide them when `syncEnabled = false` AND no downloaded tracks exist; keep them visible if either condition is true.

**Architecture:** One existing DAO query (`PlaylistDao.getAllVisible`) gets a tighter `WHERE` clause — the only consumer is `MusicRepository.getAllPlaylists()` (verified by grep), and the Sync tab uses dedicated DAO methods (`getSpotifyPlaylistsForPreferences`, `getYouTubePlaylistsForPreferences`) that don't pass through this method. Plus a small fix so locally-created CUSTOM playlists default to `syncEnabled = true` (no upstream toggle to mean anything otherwise).

**Tech Stack:** Kotlin, Android Room (SQL change in @Query annotation), no new schema, no new tables, no new ViewModels touched.

**Spec:** `docs/superpowers/specs/2026-05-03-sync-enabled-visibility-design.md`

**Implementation note vs spec:** the spec describes the change as "add a new repository method `getDisplayPlaylists()`." During plan-writing we discovered `PlaylistDao.getAllVisible` already exists with the exact intended name, and is the sole delegate target of `MusicRepository.getAllPlaylists()`. Modifying `getAllVisible` directly is functionally identical and avoids a redundant repository method. Plan diverges from spec on this implementation detail; behavior is unchanged.

---

## Pre-flight

The worktree at `.worktrees/playlist-banlist` was created from `master` after v0.9.8 shipped. The spec has been committed to that worktree on branch `feat/playlist-banlist`. (Branch name is a historical artifact from the original "banlist table" framing — kept as-is to avoid worktree-rename overhead.)

- [ ] **Confirm worktree branch + clean tree**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/playlist-banlist
git branch --show-current
git status --short
```

Expected:
- Current branch: `feat/playlist-banlist`
- No `M`/`A`/`D` lines for production files (only `??` brainstorm/scratch artefacts are fine)

- [ ] **Confirm spec is committed on this worktree**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/playlist-banlist
git log --oneline master..HEAD
```

Expected: one commit `a4c028b` — `docs(spec): v0.9.9 syncEnabled becomes the playlist banlist`. Anything else means scratch work needs a decision before continuing.

---

## File-touched map

This plan touches **two files**.

| File | Module | Why |
|---|---|---|
| `core/data/src/main/kotlin/com/stash/core/data/db/dao/PlaylistDao.kt` | `:core:data` | Tighter `WHERE` clause on `getAllVisible` query + KDoc rewrite. The query change is the entire user-facing fix. |
| `core/data/src/main/kotlin/com/stash/core/data/repository/MusicRepositoryImpl.kt` | `:core:data` | Flip `createPlaylist` (the user-facing "+ Playlist" path) from `syncEnabled = false` to `syncEnabled = true`. User-created playlists have no upstream sync toggle, so they need to be visible on Home + Library immediately under the new filter. |
| `app/build.gradle.kts` | `:app` | `versionCode 45 → 46`, `versionName "0.9.8" → "0.9.9"`. |

Net change: ~25 lines of code (one SQL @Query body, one KDoc rewrite, one 1-line flip in `createPlaylist`, two version-bump constants).

`HomeViewModel`, `LibraryViewModel`, and `SyncViewModel` are **not** modified. The sync engine (`DiffWorker`, `PlaylistFetchWorker`) is **not** modified.

---

## Task 1: Update `getAllVisible` query + flip `createPlaylist` syncEnabled default

**Files:**
- Modify: `core/data/src/main/kotlin/com/stash/core/data/db/dao/PlaylistDao.kt` (lines ~106–121)
- Modify: `core/data/src/main/kotlin/com/stash/core/data/repository/MusicRepositoryImpl.kt` (the `insertPlaylist` and `createPlaylist` paths near lines 243, 263)

- [ ] **Step 1: Read current state of both files**

Open `PlaylistDao.kt` and read lines 95–125 (the `getAllActive` and `getAllVisible` block). Verify the current state matches:

```kotlin
@Query("SELECT * FROM playlists WHERE is_active = 1 ORDER BY name ASC")
fun getAllVisible(): Flow<List<PlaylistEntity>>
```

with a KDoc explicitly stating "Visibility is decoupled from the per-playlist `sync_enabled` toggle." That sentence is the design philosophy we are inverting.

Open `MusicRepositoryImpl.kt` and read lines 240–295. Locate `insertPlaylist`, `removePlaylist`, `createPlaylist`, and `ensureDownloadsMixSeeded`. Expected shapes:

- **`insertPlaylist` (line 243–244):** simple pass-through — `playlistDao.insert(playlist.toEntity())`. No `syncEnabled` override. **Do not modify.** Callers (e.g. `DiffWorker`) correctly set `syncEnabled = false` for upstream-discovered playlists themselves.
- **`createPlaylist` (line 256–266):** the user-facing "+ Playlist" path. Constructs a `PlaylistEntity` directly with `source = MusicSource.BOTH`, `type = PlaylistType.CUSTOM`, `syncEnabled = false`. **This is the line we change in Step 4.**
- **`ensureDownloadsMixSeeded` (line 281–292):** seeds the DOWNLOADS_MIX system playlist with `syncEnabled = false`. **Do not modify** — DOWNLOADS_MIX is only created lazily on the first user download, so it always has ≥1 downloaded track and the Section 2 EXISTS clause covers visibility.

If `createPlaylist` is at a different line or its body looks materially different (extra parameters, different source/type defaults), stop and report — the plan was written against this exact shape.

- [ ] **Step 2: Update `getAllVisible` @Query**

Edit `PlaylistDao.kt` lines ~120–121. **Replace:**

```kotlin
@Query("SELECT * FROM playlists WHERE is_active = 1 ORDER BY name ASC")
fun getAllVisible(): Flow<List<PlaylistEntity>>
```

**With:**

```kotlin
@Query("""
    SELECT p.* FROM playlists p
    WHERE p.is_active = 1
      AND (
          p.sync_enabled = 1
          OR EXISTS (
              SELECT 1 FROM playlist_tracks pt
              JOIN tracks t ON pt.track_id = t.id
              WHERE pt.playlist_id = p.id
                AND t.is_downloaded = 1
                AND t.is_blacklisted = 0
          )
      )
    ORDER BY p.name ASC
""")
fun getAllVisible(): Flow<List<PlaylistEntity>>
```

The `EXISTS` clause keeps playlists with downloaded content visible even when sync is off. Excluding `is_blacklisted = 1` tracks means a "Delete & Block"-of-every-track collapses back to invisible.

- [ ] **Step 3: Update the `getAllVisible` KDoc**

Edit the KDoc immediately preceding the query (around lines 106–119). The current copy explicitly contradicts the new semantic ("Visibility is decoupled from the per-playlist `sync_enabled` toggle"). **Replace** the existing KDoc block with:

```kotlin
    /**
     * All playlists eligible to render on Home/Library.
     *
     * Visibility is now coupled to the per-playlist `sync_enabled`
     * toggle: turning sync off in Sync Preferences hides the playlist
     * from Home + Library. The escape hatch is downloaded content —
     * if the playlist has at least one downloaded, non-blacklisted
     * track, it stays visible regardless of `sync_enabled`. This
     * preserves user investment in playlists they previously synced.
     *
     * The Sync tab continues to show every playlist via dedicated DAO
     * methods ([getSpotifyPlaylistsForPreferences],
     * [getYouTubePlaylistsForPreferences]) so the user can flip sync
     * back on for any playlist they want.
     *
     * Pre-v0.9.9 this was decoupled — `sync_enabled = 0` only meant
     * "skip on the next sync," and once-imported playlists stayed
     * forever. The change addresses the long-standing complaint that
     * upstream Spotify/YouTube libraries flooded Home with mosaics
     * for playlists the user never opted into.
     */
```

- [ ] **Step 4: Flip `createPlaylist` syncEnabled default to true**

Edit `MusicRepositoryImpl.kt` `createPlaylist` (around lines 256–266). The current body is:

```kotlin
    override suspend fun createPlaylist(name: String): Long {
        val entity = com.stash.core.data.db.entity.PlaylistEntity(
            name = name,
            source = com.stash.core.model.MusicSource.BOTH,
            sourceId = "custom_${java.util.UUID.randomUUID()}",
            type = com.stash.core.model.PlaylistType.CUSTOM,
            isActive = true,
            syncEnabled = false,
        )
        return playlistDao.insert(entity)
    }
```

**Change exactly one line:** `syncEnabled = false` → `syncEnabled = true`.

Rationale: this method is the user-facing "+ Playlist" path. User-created playlists have no upstream sync toggle, so `syncEnabled = false` was meaningless before — it just made the new empty playlist invisible under the Section 2 filter. Flipping to `true` makes the playlist appear immediately on Home + Library, matching the user's mental model.

`insertPlaylist` (line 243–244) is a pass-through and stays as-is. `ensureDownloadsMixSeeded` (line 281–292) also stays as-is — the DOWNLOADS_MIX is only seeded on the first user download, so its EXISTS-clause visibility is already correct.

No new imports needed (the file already references `com.stash.core.data.db.entity.PlaylistEntity` and `com.stash.core.model.MusicSource`).

- [ ] **Step 5: Build the `:core:data` module**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/playlist-banlist
./gradlew :core:data:assembleDebug
```

Expected: `BUILD SUCCESSFUL`. Room's annotation processor validates the SQL at compile time — if the new `EXISTS` clause has a syntax error, the build fails with a clear error pointing at the @Query string.

- [ ] **Step 6: Build the full app**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/playlist-banlist
./gradlew :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`. Confirms every module that consumes `MusicRepository.getAllPlaylists()` (Home, Library) still compiles since the Flow signature is unchanged.

- [ ] **Step 7: Commit**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/playlist-banlist
git add core/data/src/main/kotlin/com/stash/core/data/db/dao/PlaylistDao.kt \
        core/data/src/main/kotlin/com/stash/core/data/repository/MusicRepositoryImpl.kt
git commit -m "feat(playlists): hide sync-off playlists from Home + Library

Couples Home/Library visibility to the existing sync_enabled flag:
playlists where sync is OFF and have no downloaded tracks vanish
from the discovery surfaces. They remain in the Sync tab picker
(dedicated DAO methods, untouched here) so the user can flip sync
back on whenever.

The EXISTS clause checks for at least one downloaded, non-blacklisted
track — preserves visibility into playlists the user previously
consumed even after they turn sync off. A 'Delete & Block'-of-every-
track collapses back to invisible automatically.

User-created CUSTOM playlists (the in-app '+ Playlist' button)
now default to sync_enabled=true on creation. They have no upstream
toggle to make 'sync off' meaningful, and the new filter would
hide a brand-new empty playlist until the user added a downloaded
track. createPlaylist line 263 flipped false → true; insertPlaylist
and ensureDownloadsMixSeeded are unchanged (the former is a
pass-through; the latter is only seeded when a download lands so
its EXISTS-clause visibility is already correct).

Pre-v0.9.9 design philosophy was 'visibility decoupled from sync,'
which created the long-standing complaint that upstream Spotify/YouTube
libraries flooded Home with mosaics for playlists the user never
opted into. The new philosophy is 'sync_enabled IS the ban': turning
sync off in Sync Preferences hides the playlist everywhere except
the Sync tab itself.

Spec: docs/superpowers/specs/2026-05-03-sync-enabled-visibility-design.md
"
```

---

## Task 2: Bump version + signed release build + sideload + manual acceptance

**Files:**
- Modify: `app/build.gradle.kts`

- [ ] **Step 1: Bump versionCode + versionName**

Edit `app/build.gradle.kts`:

- Line 75 — change `versionCode = 45` to `versionCode = 46`.
- Line 76 — change `versionName = "0.9.8"` to `versionName = "0.9.9"`.

- [ ] **Step 2: Build the signed release APK**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/playlist-banlist
./gradlew :app:assembleRelease
```

Expected: `BUILD SUCCESSFUL`. APK lands at `app/build/outputs/apk/release/app-release.apk` *inside the worktree* (the worktree has its own build directory).

If signing fails because `keystore.properties` is not present in the worktree, copy it from the main checkout (per memory `feedback_worktree_local_properties.md`):

```bash
cp C:/Users/theno/Projects/MP3APK/keystore.properties C:/Users/theno/Projects/MP3APK/.worktrees/playlist-banlist/keystore.properties
./gradlew :app:assembleRelease
```

(If the user has only ever built from the main checkout, `keystore.properties` may already exist there; the copy is a one-time worktree setup. Was already copied during worktree creation per the brainstorm session.)

- [ ] **Step 3: Sideload over the user's existing v0.9.8 install**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/playlist-banlist
adb devices
adb install -r app/build/outputs/apk/release/app-release.apk
```

Expected: device line shown in `adb devices`; install reports `Success`. The user's library data is preserved (same package, same signing key).

If `adb devices` shows no device, ask the user to re-seat the cable + accept the USB-debugging prompt; do not proceed until the device shows up.

- [ ] **Step 4: Run the manual acceptance flow**

Open the **main `com.stash.app`** (not `.debug`). For each scenario, confirm:

1. **Cold start, Home → Playlists grid** — only sync-on playlists OR ones with downloaded tracks are visible. Empty mosaic placeholders for Spotify/YouTube playlists the user never opted into are gone.

2. **Library → Playlists tab** — same filter applied. Sync-off playlists with no downloads disappeared. Playlists with downloaded tracks (even if sync is now off) remain visible.

3. **Sync tab → per-playlist picker** — every upstream playlist still listed. Sync-off ones show their toggle off; user can flip them on.

4. **Toggle a Spotify playlist's sync OFF in Sync tab** — Home + Library updates immediately on the next Flow emission. The playlist disappears.

5. **Toggle that same playlist's sync back ON** — playlist reappears on Home + Library immediately if it has downloaded tracks; otherwise after the next sync cycle queues content.

6. **Create a new local playlist via the in-app "+ Playlist" button** — visible immediately on Home + Library even with zero tracks. The LOCAL syncEnabled-defaults-true fix from Step 4 of Task 1 is what makes this work.

7. **"Delete & Block" a Spotify playlist** — confirm:
   - Existing tracks become inaccessible (already true pre-v0.9.9).
   - The playlist mosaic is gone from Home + Library (was already true post-delete).
   - On the next fresh sync, the playlist row gets re-created in `playlists` table with `syncEnabled = false` (existing `DiffWorker` behavior — unchanged).
   - **The recreated playlist is NOT visible on Home + Library** (this is the v0.9.9 fix). It only appears in the Sync tab picker.

If any scenario fails, **stop and report**. Do not proceed to merge/tag.

- [ ] **Step 5: Commit the version bump**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/playlist-banlist
git add app/build.gradle.kts
git commit -m "chore(release): bump versionCode 45→46, versionName 0.9.8→0.9.9"
```

---

## Task 3: Merge + tag + GitHub release

Only run after Task 2 acceptance passed.

Per memory `feedback_check_worktrees_before_release.md`: survey worktrees first.
Per memory `feedback_release_notes.md`: lightweight tag + omit `--notes` so the release body comes from the tagged commit's message body.

- [ ] **Step 1: Survey worktrees for v0.9.9-relevant WIP**

Run from the main checkout (not the worktree):

```bash
cd C:/Users/theno/Projects/MP3APK
git worktree list
for w in auto-advance-fix crossfade crossfader playlist-images-liked-songs preview-latency-fix yt-history-sync yt-sync-pagination playlist-banlist; do
  echo "=== $w ==="
  git -C ".worktrees/$w" status --short 2>&1 | head -10
  echo "--- log vs master ---"
  git -C ".worktrees/$w" log --oneline master..HEAD 2>&1 | head -5
done
```

Expected: same WIP state as before v0.9.8 ship for the older worktrees, plus the new `playlist-banlist` worktree showing the spec, plan, Task 1 commit, and version-bump commit. If anything else has changed (new branch added, sync-related WIP appeared in another worktree), stop and ask the user before merging.

- [ ] **Step 2: Create the consolidated release-notes commit on the branch tip**

Run inside the worktree:

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/playlist-banlist
git commit --allow-empty -m "$(cat <<'EOF'
feat: 0.9.9 — sync_enabled becomes the playlist banlist

Fixes a long-standing UX complaint: Home and Library showed empty
mosaics for every Spotify/YouTube playlist in the user's upstream
library, regardless of whether they had ever turned the per-playlist
sync switch on. Every fresh sync re-imported them as syncEnabled=false
rows and the placeholders re-appeared.

The new behaviour: turning sync OFF for a playlist hides it from Home
and Library too — sync_enabled IS the ban. The Sync tab picker keeps
showing all rows so the user can flip sync back on at any time.

Playlists with downloaded tracks stay visible even when sync is off,
preserving investment in content the user previously consumed. A
'Delete & Block' that wipes every track in a playlist collapses it
back to invisible automatically (the EXISTS clause filters out
blacklisted tracks).

Locally-created CUSTOM playlists ('+ Playlist' button) now default to
sync_enabled=true on creation. They have no upstream toggle, so
treating sync as off would have made fresh empty playlists invisible.

No DB schema migration. No new UI screens. ~25 lines of code.

Spec: docs/superpowers/specs/2026-05-03-sync-enabled-visibility-design.md

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
RELEASE_SHA=$(git rev-parse HEAD)
echo "release commit: $RELEASE_SHA"
```

- [ ] **Step 3: Push the feature branch**

```bash
cd C:/Users/theno/Projects/MP3APK/.worktrees/playlist-banlist
git push origin feat/playlist-banlist
```

- [ ] **Step 4: Switch to master in the main checkout, fast-forward, merge --no-ff**

```bash
cd C:/Users/theno/Projects/MP3APK
git checkout master
git pull --ff-only origin master
git merge --no-ff feat/playlist-banlist -m "Merge branch 'feat/playlist-banlist'"
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
git tag v0.9.9 "$RELEASE_SHA"
git push origin v0.9.9
```

If `$RELEASE_SHA` isn't in scope (different shell), look it up from master:

```bash
cd C:/Users/theno/Projects/MP3APK
git log master --oneline | head -5
# Find the "feat: 0.9.9 — sync_enabled becomes the playlist banlist" commit. Tag that SHA.
```

- [ ] **Step 7: Create GitHub release using the tagged-commit body**

```bash
cd C:/Users/theno/Projects/MP3APK
gh release create v0.9.9 \
  --title "v0.9.9 — sync_enabled becomes the playlist banlist"
```

(No `--notes` — GitHub renders the tagged commit's body. Per memory `feedback_release_notes.md`.)

Expected: gh prints the release URL.

- [ ] **Step 8: Verify the release on GitHub**

Open the URL gh printed (or `https://github.com/rawnaldclark/Stash/releases/tag/v0.9.9`). Confirm:
- Title reads "v0.9.9 — sync_enabled becomes the playlist banlist"
- Body contains the natural-language release notes from Step 2's commit
- The auto-generated release APK appears (after CI build completes — typically 5-10 minutes)

- [ ] **Step 9: Clean up the worktree (optional)**

After the merge lands and the tag is published, the worktree is no longer needed. From the main checkout:

```bash
cd C:/Users/theno/Projects/MP3APK
git worktree remove .worktrees/playlist-banlist
git branch -d feat/playlist-banlist
```

Skip this step if you want to keep the worktree around for fixup commits — but the local.properties + build directories take up space.

---

## Skills reference

- @superpowers:verification-before-completion — before claiming Task 2 / Task 3 done. Don't skip the on-device acceptance flow.
- Memory `feedback_install_after_fix.md` — always sideload after a fix; compile-pass alone isn't enough.
- Memory `feedback_release_notes.md` — release body comes from the tagged commit's message body, not the tag annotation. Use lightweight tags + omit `--notes`.
- Memory `feedback_check_worktrees_before_release.md` — survey worktrees before tagging.
- Memory `feedback_no_time_estimates.md` — no dev-time estimates anywhere in commits or release notes.
- Memory `feedback_worktree_local_properties.md` — `git worktree add` does not copy `local.properties`; copy it manually if release builds need it.

## Risks & rollback

- **Existing-user surprise.** v0.9.8 → v0.9.9 silently hides playlists where the user has sync off and no downloaded tracks. Acceptable per the spec — this is the requested fix. If a confused user thinks "where did Spotify Daily Mix 1 go?" the answer is "Sync tab → flip toggle on." Same silent-migration philosophy as v0.9.8's lossless flip.
- **`EXISTS` performance.** For libraries up to ~10k tracks the cost is negligible (FK indexes). The escape hatch (denormalized `downloaded_track_count` column) is deferred unless a real complaint surfaces.
- **Room SQL syntax error in the @Query.** Build-time validation catches this — Step 5 of Task 1 would fail with a clear SQL error pointing at the @Query string. Fix is mechanical.
- **`createPlaylist` body shape drift.** Plan was written against the exact 11-line body at `MusicRepositoryImpl.kt:256–266`. If the body has been refactored by a parallel branch (extra params, builder pattern, etc.), the one-line edit may not apply cleanly. Fix is mechanical — locate the literal `syncEnabled = false` line in `createPlaylist` and flip it.
- **Rollback** — revert the merge commit on master + delete the tag (`git push --delete origin v0.9.9`). User's data is unaffected (no schema change, no destructive DataStore writes). Hidden playlists become visible again on the v0.9.8 fallback.
