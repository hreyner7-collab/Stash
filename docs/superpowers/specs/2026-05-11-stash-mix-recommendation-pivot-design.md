# Stash Mix Recommendation Pivot Design

> **Status:** Design — pending implementation plan.
> **Scope:** PR 3 of the post-v0.9.19 audit (PR 1 shipped: snackbar lifecycle + orphan-FK fix; PR 2 deferred: QobuzSource normalize). Recipe redesign to shift Stash Mix from library-substrate-with-discovery-seasoning to recommendation-substrate-with-library-seasoning. Also closes the inter-mix track overlap problem.
> **Related design docs:** none new; touches the seed-strategy infrastructure shipped in `2026-05-08-first-listen-tag-fallback-design.md` (TAG_GRAPH) and the cap fix from `2026-05-08-discovery-survivor-cap.md`.

---

## Goal

Make Daily Discover and Deep Cuts feel like recommendation feeds, not curated library views. Specifically:

1. ~80-90% of tracks in each of the three builtin mixes should come from the Last.fm-driven discovery pipeline (`MixSeedGenerator` → `discovery_queue` → `StashDiscoveryWorker` → DONE survivors). The remaining 10-20% library slice is acceptable seasoning, not the substrate.
2. Daily Discover and Deep Cuts should share minimal tracks. The current "dozens of overlap" problem must go to zero in steady state.
3. Each of the three mixes should have a distinct discovery flavor (Daily Discover = similar artists, Deep Cuts = similar tracks, First Listen = your taste tags).

## Non-goals

- **Library rediscovery use case**: The current Deep Cuts surfaces "tracks you used to love but haven't heard." This use case is intentionally dropped. The user confirmed they'd rather have all three mixes be recommendation-driven than preserve rediscovery.
- **QobuzSource normalize / match-quality fix**: separate PR (PR 2). Out of scope here.
- **Lossless retry UX feedback**: shipped in PR 1, no further work.
- **First Listen redesign**: First Listen already meets the 100%-discovery goal via TAG_GRAPH. Leave it alone.
- **A "global recommendation intensity" preference slider**: out of scope. Three recipes with hardcoded ratios is the right grain for now.
- **Pre-seeding discovery_queue inline on retune**: explicitly considered and rejected. Transient sparseness after upgrade is acceptable.

## Architecture

Three concerns are bundled here because they're coupled — changing the ratio without dedup leaves overlaps; changing the dedup without the ratio leaves library-heavy mixes; the migration ties both to existing installs.

```
┌──────────────────────────────────────────────────────────────────────┐
│ core:data  ::  StashMixDefaults.kt                                   │
│   Retune Daily Discover + Deep Cuts seed configs (Section 1).        │
│                                                                      │
│ core:data  ::  MixGenerator.kt                                       │
│   - generate(recipe, excludeIds: Set<Long>) → filtered pool.         │
│   - Shortfall-fill gate: discoveryRatio >= 0.5 → no library backfill.│
│                                                                      │
│ core:data  ::  StashMixRefreshWorker.kt                              │
│   - materializeMix returns MaterializeResult(playlistId,             │
│     discoveryIds: List<Long>).                                       │
│   - doWork iterates recipes in dedup-priority order, threads a       │
│     mutable excludeIds set through both generate() and               │
│     materializeMix().                                                │
│                                                                      │
│ core:data  ::  StashMixRecipeDao.kt                                  │
│   - retuneBuiltin signature expands to cover affinityBias and        │
│     seedStrategy fields.                                             │
│                                                                      │
│ app  ::  StashApplication.kt (or a focused helper)                   │
│   - On cold start, retune builtins to current StashMixDefaults       │
│     values. Idempotent — no-op if values already match.              │
└──────────────────────────────────────────────────────────────────────┘
```

No schema migration. Room schema v22 stays. All changes are query, generator, and recipe-row content.

## Component 1 — Recipe parameter changes

`core/data/src/main/kotlin/com/stash/core/data/mix/StashMixDefaults.kt`. Replace the three builtin entries with:

```kotlin
StashMixRecipeEntity(
    name = "Daily Discover",
    description = "Mostly fresh finds via similar-artists. A pinch of your library.",
    affinityBias = 0.0f,
    freshnessWindowDays = 7,
    discoveryRatio = 0.85f,
    targetLength = 40,
    seedStrategy = "ARTIST_SIMILAR",
    isBuiltin = true,
),
StashMixRecipeEntity(
    name = "Deep Cuts",
    description = "Mostly fresh finds via similar-tracks. A pinch of your library.",
    affinityBias = 0.0f,
    freshnessWindowDays = 90,
    discoveryRatio = 0.85f,
    targetLength = 40,
    seedStrategy = "TRACK_SIMILAR",
    isBuiltin = true,
),
StashMixRecipeEntity(
    name = "First Listen",
    // unchanged
    description = "Tracks you've never heard. Wider net.",
    affinityBias = 0.0f,
    freshnessWindowDays = 14,
    discoveryRatio = 1.0f,
    targetLength = 50,
    seedStrategy = "TAG_GRAPH",
    isBuiltin = true,
),
```

| Recipe | Library slots | Discovery slots | Total | Distinct discovery flavor |
| --- | --- | --- | --- | --- |
| Daily Discover | 6 (15%) | 34 (85%) | up to 40 | Similar artists (`artist.getSimilar`) |
| Deep Cuts | 6 (15%) | 34 (85%) | up to 40 | Similar tracks (`track.getSimilar`) |
| First Listen | 0 | 50 (100%) | up to 50 | Top-track-by-tag (`tag.getTopTracks`) |

`affinityBias = 0.0` because the library slice is tiny — bias shifting library ranking matters less than the strategy producing distinct discovery candidates. Freshness windows (7d / 90d / 14d) are preserved; they now only affect the small library slice and preserve the recipe's "don't repeat too soon" intent on that slice.

## Component 2 — `MixGenerator` changes

`core/data/src/main/kotlin/com/stash/core/data/mix/MixGenerator.kt`.

### 2a. `generate` signature extension

```kotlin
suspend fun generate(
    recipe: StashMixRecipeEntity,
    excludeIds: Set<Long> = emptySet(),
): List<TrackEntity> {
    val rawPool = trackDao.getAllDownloaded()
    val pool0 = if (excludeIds.isEmpty()) rawPool else rawPool.filter { it.id !in excludeIds }
    var pool = filterByEra(pool0, recipe)
    // ... rest of existing pipeline unchanged ...
}
```

`excludeIds` is read-only. Empty default preserves backward compatibility with any caller that doesn't need dedup.

### 2b. Shortfall-fill threshold

`MixGenerator.kt` lines 192-198:

```kotlin
// Old:
if (recipe.discoveryRatio < 1.0f) {
    val shortfall = recipe.targetLength - picked.size
    if (shortfall > 0 && picked.size < pool.size) {
        val extra = ordered.filter { it !in picked }.take(shortfall)
        return picked + extra
    }
}
```

```kotlin
// New:
// v0.9.20: gate raised from < 1.0f to < 0.5f. Recipes that should be
// substantially discovery-driven (>= 50% by ratio) must not silently
// degrade to library when discovery is sparse — that's exactly what
// produced the "library-heavy mixes" symptom on existing installs.
// A sparse-but-honest mix is better than a deceptively library-filled one.
if (recipe.discoveryRatio < 0.5f) {
    val shortfall = recipe.targetLength - picked.size
    if (shortfall > 0 && picked.size < pool.size) {
        val extra = ordered.filter { it !in picked }.take(shortfall)
        return picked + extra
    }
}
```

Threshold of 0.5 means library-only recipes (discoveryRatio = 0) still get the original full-fill behavior — preserving any user-created recipes that lean library. The three builtins all land outside the gate after retune (0.85, 0.85, 1.0).

## Component 3 — `StashMixRefreshWorker` orchestration

`core/data/src/main/kotlin/com/stash/core/data/sync/workers/StashMixRefreshWorker.kt`.

### 3a. `materializeMix` returns picked discovery ids

Refactor the private function to return both the playlist id and the discovery-survivor ids it inserted:

```kotlin
private data class MaterializeResult(
    val playlistId: Long,
    val discoveryIds: List<Long>,
)

private suspend fun materializeMix(
    recipe: StashMixRecipeEntity,
    tracks: List<TrackEntity>,
    now: Long,
    excludeIds: Set<Long>,
): MaterializeResult {
    // ... existing find-or-create-playlist + insert library tracks ...

    val discoveryCap = (recipe.targetLength * recipe.discoveryRatio)
        .roundToInt()
        .coerceAtLeast(0)
    val librarySet = tracks.mapTo(HashSet(tracks.size)) { it.id }

    // Over-fetch by `excludeIds.size` so post-filter survivors still fill
    // the cap. Worst case: every excluded id was a survivor — we still
    // come back with `discoveryCap` distinct ids. Bounded — the DAO's
    // ORDER BY completed_at DESC + LIMIT means we just slide the window.
    val rawCandidateIds = discoveryQueueDao
        .getDoneTrackIdsForRecipe(recipe.id, limit = discoveryCap + excludeIds.size)
    val filteredCandidateIds = rawCandidateIds
        .filter { it !in librarySet && it !in excludeIds }
        .take(discoveryCap)
    val discoveryTrackIds = buildList {
        for (trackId in filteredCandidateIds) {
            if (!blocklistGuard.isBlockedByTrackId(trackId)) add(trackId)
        }
    }
    // ... existing cross-ref inserts + cover-art update ...

    return MaterializeResult(playlistId, discoveryTrackIds)
}
```

### 3b. `doWork` iterates with dedup ordering + exclude set

After the existing persona-fetch block (currently lines 199-217), replace the iteration starting at line 219:

```kotlin
val orderedRecipes = active.sortedBy { recipeDedupPriority(it) }
val excludeIds = mutableSetOf<Long>()
for (recipe in orderedRecipes) {
    val tracks = mixGenerator.generate(recipe, excludeIds)

    if (tracks.isEmpty() && recipe.discoveryRatio == 0f) {
        Log.d(TAG, "'${recipe.name}' produced 0 tracks and has no discovery — skipping materialize")
        continue
    }

    val result = materializeMix(recipe, tracks, now, excludeIds)
    recipeDao.setPlaylistId(recipe.id, result.playlistId)
    recipeDao.setLastRefreshedAt(recipe.id, now)

    // Accumulate so the next recipe in the ordering doesn't re-pick these.
    excludeIds += tracks.map { it.id }
    excludeIds += result.discoveryIds

    if (recipe.discoveryRatio > 0f && lastFmConfigured) {
        runCatching { queueDiscoveryForRecipe(recipe, personas) }
            .onFailure { Log.w(TAG, "discovery queueing failed for '${recipe.name}'", it) }
    }
}
```

### 3c. `recipeDedupPriority`

A small companion helper:

```kotlin
/**
 * Ordering for cross-mix track dedup. Most-restrictive recipes go first
 * so they get first pick; most-permissive last so it claims leftovers.
 *
 *  - First Listen (1.0 discovery, library-blind) — has no opinion on the
 *    library pool; claims TAG_GRAPH survivors first.
 *  - Deep Cuts (0.85 discovery, 90d freshness on the slim library slice)
 *    — claims TRACK_SIMILAR survivors and a sparse library slice next.
 *  - Daily Discover (0.85 discovery, 7d freshness) — most permissive on
 *    the library slice; claims ARTIST_SIMILAR survivors last.
 *  - Non-builtins / unknown — last (99).
 */
private fun recipeDedupPriority(recipe: StashMixRecipeEntity): Int = when (recipe.name) {
    "First Listen" -> 1
    "Deep Cuts" -> 2
    "Daily Discover" -> 3
    else -> 99
}
```

Keyed by name rather than id because builtin id values aren't stable across reseeds.

## Component 4 — Builtin tuning migration

`core/data/src/main/kotlin/com/stash/core/data/db/dao/StashMixRecipeDao.kt`.

Expand `retuneBuiltin` to cover the two new fields:

```kotlin
@Query(
    """
    UPDATE stash_mix_recipes
    SET discovery_ratio = :discoveryRatio,
        freshness_window_days = :freshnessWindowDays,
        target_length = :targetLength,
        affinity_bias = :affinityBias,
        seed_strategy = :seedStrategy
    WHERE is_builtin = 1 AND name = :name
    """
)
suspend fun retuneBuiltin(
    name: String,
    discoveryRatio: Float,
    freshnessWindowDays: Int,
    targetLength: Int,
    affinityBias: Float,
    seedStrategy: String,
): Int
```

**Backward compat note:** the existing call site at `StashApplication.kt:339-344` (`maybeRetuneStashDiscover`) uses the 4-arg form. After the signature expansion, it must be updated to pass the new fields as well — pass `affinityBias = 0.0f` and `seedStrategy = "TAG_GRAPH"` (the Stash Discover values from before that migration's era) so the v1 migration continues to work for users still on v0.9.x who haven't run the v2 migration yet. Single Kotlin compile error to chase; trivial.

### 4a. Version-gated migration hook (mirror existing precedent)

`app/src/main/kotlin/com/stash/app/StashApplication.kt` already implements `maybeRetuneStashDiscover()` (lines 335-355) with `STASH_DISCOVER_TUNING_VERSION` gating it via `getSharedPreferences("stash_migrations", MODE_PRIVATE)`. This recipe pivot adopts that pattern verbatim.

Add a new constant in the companion object (around line 493 where the existing version constants live):

```kotlin
private const val STASH_MIX_RECIPE_TUNING_VERSION = 1
```

Add a new private suspend function alongside `maybeRetuneStashDiscover`:

```kotlin
/**
 * Pivot Daily Discover + Deep Cuts to recommendation-substrate
 * (85% discovery, 15% library). Gated by
 * [STASH_MIX_RECIPE_TUNING_VERSION] so each release that adjusts these
 * builtins ships exactly once per install. Fresh installs skip this
 * because [StashMixDefaults] already seeds with the new values.
 */
private suspend fun maybeRetuneStashMixes() {
    val prefs = getSharedPreferences("stash_migrations", MODE_PRIVATE)
    val stored = prefs.getInt("stash_mix_recipe_tuning_version", 0)
    if (stored >= STASH_MIX_RECIPE_TUNING_VERSION) return

    var totalUpdated = 0
    for (recipe in StashMixDefaults.ALL.filter { it.isBuiltin }) {
        val updated = stashMixRecipeDao.retuneBuiltin(
            name = recipe.name,
            discoveryRatio = recipe.discoveryRatio,
            freshnessWindowDays = recipe.freshnessWindowDays,
            targetLength = recipe.targetLength,
            affinityBias = recipe.affinityBias,
            seedStrategy = recipe.seedStrategy,
        )
        totalUpdated += updated
    }
    Log.i("StashMigration", "Retuned $totalUpdated builtin mix recipe(s) to v$STASH_MIX_RECIPE_TUNING_VERSION")
    prefs.edit()
        .putInt("stash_mix_recipe_tuning_version", STASH_MIX_RECIPE_TUNING_VERSION)
        .apply()
}
```

Wire it into the existing startup sequence next to `maybeRetuneStashDiscover()` (line 171):

```kotlin
maybeRetuneStashDiscover()
maybeRetuneStashMixes()  // NEW
```

Both migrations run sequentially on cold start, both are idempotent via separate version keys, both are no-ops once their version pref matches the current constant.

**Why a separate version key and not just bumping `STASH_DISCOVER_TUNING_VERSION`:** the Stash Discover migration targets only the now-legacy "Stash Discover" recipe row. The pivot targets Daily Discover + Deep Cuts. Conflating them would re-run the Stash Discover migration unnecessarily on installs that already ran it.

## Tests

### Unit tests

**Testing-framework convention for this module** (verified against existing tests):
- `MixGenerator*Test.kt` files use **MockK** with hand-mocked DAOs (see `MixGeneratorComputeUserTopTagsTest`). Use MockK for the new `MixGenerator` and `StashMixRefreshWorker` tests.
- DAO behavior tests use **Robolectric + Room in-memory** (see `DiscoveryQueueDaoCapTest`). Use this for the new `StashMixRecipeDao` test because idempotency + actual SQL behavior need a real DB.

Concrete test classes:

- **`MixGeneratorShortfallFillTest`** (new, MockK): mock `trackDao.getAllDownloaded()`, `listeningEventDao`, `trackTagDao`. Cases:
  - `shortfall fill triggers when discoveryRatio = 0.4 and library pool > librarySlots` — picks up to targetLength.
  - `shortfall fill does NOT trigger when discoveryRatio = 0.5` — picks exactly librarySlots.
  - `shortfall fill does NOT trigger when discoveryRatio = 0.85` — same.
  - `shortfall fill still gated off at discoveryRatio = 1.0` — same (pre-existing behavior preserved).

- **`MixGeneratorExcludeIdsTest`** (new, MockK):
  - `excludeIds removes matching tracks from the pool before scoring` — seed 5 tracks, exclude 2, assert remaining 3 in result.
  - `empty excludeIds preserves current behavior` — same library returned as the no-arg case.

- **`StashMixRefreshWorkerDedupTest`** (new, MockK — no existing `StashMixRefreshWorkerTest.kt` to extend, so this is a fresh test class; follow `LosslessRetryWorkerTest` for the MockK worker-instantiation pattern):
  - End-to-end with 3 recipes, shared library pool, shared discovery survivors:
    - Recipe A picks track 1, 2, 3 (library).
    - Recipe B's generate() call receives excludeIds containing {1, 2, 3} → it picks 4, 5, 6.
    - Recipe C receives {1..6} → picks 7, 8, 9.
    - No track id appears in two playlists.
  - `recipeDedupPriority` orders First Listen → Deep Cuts → Daily Discover; other recipes go last.

- **`StashMixRecipeDaoRetuneTest`** (new, Robolectric + Room in-memory — follow `DiscoveryQueueDaoCapTest` for the pattern):
  - `retuneBuiltin updates all 5 fields including affinityBias and seedStrategy`.
  - `retuneBuiltin is idempotent — second call with same values returns rowcount but no state change`.
  - `retuneBuiltin does not touch non-builtin recipes with the same name`.

### Manual test on Pixel

1. Cold start the app post-install. Confirm no crash on retune.
2. Wait or trigger a refresh — long-press each mix card, tap "Refresh this mix".
3. Daily Discover: expect a sparser playlist (40 tracks max, only ~6 from library, the rest discovery survivors that have already been downloaded). May initially show fewer if survivors haven't accumulated.
4. Deep Cuts: expect EITHER a 6-track playlist (no TRACK_SIMILAR survivors yet — likely on first day) OR a full 40-track playlist once StashDiscoveryWorker has filled the queue.
5. Sample 10 tracks from Daily Discover and 10 from Deep Cuts. Expect zero overlap of track ids.
6. First Listen: should look the same as before.
7. Plug in to charge + connect WiFi to let StashDiscoveryWorker run. After it finishes, refresh Deep Cuts again — should now have ~34 discovery survivors.

## Failure modes + edge cases

1. **Deep Cuts goes sparse for ~1 day after upgrade**. TRACK_SIMILAR `discovery_queue` rows haven't accumulated (Deep Cuts had `seedStrategy = NONE` before). First refresh post-retune: ~6 library tracks + 0 discovery survivors = 6-track Deep Cuts until `StashDiscoveryWorker` runs. Accepted — user explicitly chose the recommendations-first design over preserving instant content.

2. **A user-edited builtin gets retuned over**. `retuneBuiltin` doesn't check whether the user has manually changed values; it blindly overwrites. This is intentional for v0.9.x — the recipe-editing UI isn't shipped yet. If/when it is, the migration logic will need a "last-touched-by-user" guard.

3. **Cross-mix dedup gives Daily Discover the worst leftovers**. Daily Discover is ordered last, so its library slice and discovery survivors are filtered through the most exclude IDs. If the library is small and the user has few discovery survivors, Daily Discover could come back nearly empty. Mitigation: `MixGenerator.generate` returns an empty list rather than crashing; `materializeMix` is fine with empty `tracks`. The Home card shows the (small) playlist; user pulls to refresh later.

4. **TRACK_SIMILAR has no seed tracks**. `seedTracks` is sourced from `personas.topTracksByPeriod[ONE_MONTH]` (Last.fm). If the user has no scrobbles or the API fails, the seed list is empty → MixSeedGenerator returns empty candidates → Deep Cuts stays library-only. This is the same fallback behavior as ARTIST_SIMILAR with no seed artists, which already works.

5. **Race between cold-start retune and the periodic StashMixRefreshWorker**. If WorkManager fires the periodic refresh in the same second the retune runs, the worker could read pre-retune values. Acceptable — the next periodic run (or any manual refresh) picks up the new values. Idempotent migration tolerates this.

6. **A non-builtin recipe with name "Daily Discover"**. The `recipeDedupPriority` function keys by name. A user-created recipe with the same name as a builtin would get the builtin's priority slot. Acceptable — collision is rare; if it happens, the worst case is the dedup ordering surfaces one mix earlier than expected.

7. **First-refresh path with null `playlistId` interacts safely with cross-mix dedup.** When `materializeMix` runs for the first time post-seed, `recipe.playlistId` is null and a new playlist is inserted. The cross-mix dedup `excludeIds` accumulates only AFTER each recipe's `materializeMix` returns; the next iteration's `generate(recipe, excludeIds)` call already sees the populated set. So the null-playlistId path is safe within a single `doWork` invocation — each recipe's playlist insert + cross-ref inserts happen atomically before the next recipe consults `excludeIds`.

8. **TRACK_SIMILAR with empty `seedTracks`**. Verified at `MixSeedStrategy.kt:78-90` — `generateTrackSimilar` iterates `seedTracks.take(10)`; empty input means the loop doesn't run and the function returns an empty list. No crash. Deep Cuts would simply have zero new discovery candidates that refresh; existing DONE survivors (if any) still re-link. Same fallback behavior as ARTIST_SIMILAR with no seed artists.

9. **`maybeRetuneStashDiscover` (v1 migration) breaks at compile time** when `retuneBuiltin` signature expands. The existing call site at `StashApplication.kt:339-344` uses the 4-arg form. Implementer must update that call site too (pass `affinityBias = 0.0f, seedStrategy = "TAG_GRAPH"` to preserve the v1 migration's intent — those were the Stash Discover values before this PR). Single Kotlin compile error to chase; trivial fix.

## Rollout

Single APK install on the Pixel after implementation, manual verification of the test plan above. No flag, no staged rollout. PR 1 already shipped on this branch; PR 3 is additive on top.

## Open questions

None. All previously-listed open questions resolved during spec review:

1. Test-framework choice — committed to MockK for `MixGenerator*` + `StashMixRefreshWorker*` tests (matches `MixGeneratorComputeUserTopTagsTest` and `LosslessRetryWorkerTest`); Robolectric + Room in-memory for `StashMixRecipeDaoRetuneTest` (matches `DiscoveryQueueDaoCapTest`).
2. Migration hook — adopt the existing `maybeRetuneStashDiscover` pattern with a separate `STASH_MIX_RECIPE_TUNING_VERSION` SharedPreferences key.
3. `generateTrackSimilar` empty-input behavior — verified safe at `MixSeedStrategy.kt:78-90` (for-loop doesn't iterate; returns empty list).
