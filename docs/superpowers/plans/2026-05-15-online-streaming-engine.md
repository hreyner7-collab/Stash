# Online Streaming Engine Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a "Streaming" mode to Stash so users can play any synced track via the existing `KennyySource` Qobuz proxy without downloading first; downloaded tracks continue to play locally.

**Architecture:** A new `StreamingPreference` (DataStore boolean) controls whether sync downloads files or just imports metadata. Tap-to-play in `PlayerRepositoryImpl` chooses between local file URI (preferred), cached stream URL, or a fresh `KennyyStreamResolver.resolve()` call. Mid-stream URL expiry (Qobuz CDN signs URLs with ~12 h TTL via the `etsp` query parameter) is handled by a `RefreshingDataSourceFactory` that catches 403/410 and re-resolves transparently. A `Media3 SimpleCache` (500 MB LRU, separate from the existing preview cache) reuses streamed bytes within and across sessions. An `AvailabilityCheckWorker` pre-resolves Kennyy availability per-track in the background so unavailable tracks render greyed-out without costing a tap.

**Tech Stack:** Kotlin · Compose · Hilt · Media3 ExoPlayer 1.x · Room · WorkManager · Preferences DataStore · existing `KennyySource` + `AggregatorRateLimiter`.

**Spec:** `docs/superpowers/specs/2026-05-15-online-streaming-engine-design.md`

---

## File Structure

New files (Kotlin):

| Path | Responsibility |
|---|---|
| `core/data/src/main/kotlin/com/stash/core/data/prefs/StreamingPreference.kt` | DataStore-backed `enabled` / `streamOnCellular` / `streamQuality` flows. Default-off (preserves current behavior). |
| `core/media/src/main/kotlin/com/stash/core/media/streaming/KennyyStreamResolver.kt` | Thin wrapper around `KennyySource.resolve()`; parses `etsp` query param to compute `expiresAtMs`. Returns `StreamUrl(url, expiresAtMs)`. |
| `core/media/src/main/kotlin/com/stash/core/media/streaming/StreamUrlCache.kt` | In-memory `Map<Long, StreamUrl>` with on-read TTL eviction; per-track key. |
| `core/media/src/main/kotlin/com/stash/core/media/streaming/RefreshingDataSourceFactory.kt` | Media3 `DataSource.Factory` wrapping `DefaultHttpDataSource.Factory`; on 403/410 calls `KennyyStreamResolver.resolve(trackId)` and retries from the same byte offset. |
| `core/media/src/main/kotlin/com/stash/core/media/streaming/StreamCache.kt` | Hilt `@Provides` for a `SimpleCache` instance at `cacheDir/stream_cache`, 500 MB LRU, disjoint from `PreviewCache`. |
| `core/media/src/main/kotlin/com/stash/core/media/streaming/StreamingMediaSourceFactory.kt` | Builds `MediaSource` from a `StreamUrl`: `CacheDataSource.Factory` wrapping `RefreshingDataSourceFactory` wrapping `DefaultHttpDataSource.Factory`. The "MediaSource pipeline that knows about both the cache and the refresh-on-403 behavior." |
| `core/data/src/main/kotlin/com/stash/core/data/sync/workers/AvailabilityCheckWorker.kt` | `CoroutineWorker`: pulls batches of 50 rows where `is_streamable_checked_at IS NULL`, calls `KennyySource.resolve()`, writes back `is_streamable` + `is_streamable_checked_at`. Self-re-enqueues until queue is drained. |
| `core/data/src/main/kotlin/com/stash/core/data/sync/workers/AvailabilityRecheckWorker.kt` | `PeriodicWorkRequest`-backed worker that resets `is_streamable_checked_at` to NULL on rows older than 30 days, then enqueues `AvailabilityCheckWorker`. Catches operator delistings + catalog additions. |
| `core/data/src/main/kotlin/com/stash/core/data/sync/workers/ReleaseDownloadsWorker.kt` | Iterates `is_downloaded = 1` rows, deletes each file from disk, then sets `is_downloaded = 0`, `file_path = NULL`. Resumable via a session ID — picks up where it left off on cancellation. Triggered by the "Release the space" path in the Off→On mode prompt. |
| `feature/home/src/main/kotlin/com/stash/feature/home/streaming/StreamingModeToggle.kt` | Compose composable: single-row switch labeled "Online", subtitle "Stream from your synced library". Renders above the existing "Recently played" section in `HomeScreen`. |
| `feature/home/src/main/kotlin/com/stash/feature/home/streaming/StreamingModePrompt.kt` | Compose dialog with two variants — `KeepOrReleaseDownloadsPrompt` (Off→On) and `DownloadOrStartFreshPrompt` (On→Off). Each renders a single AlertDialog with two buttons + safe defaults. |

New test files mirror each main-source file one-to-one in `src/test/`.

Files modified:

- `core/data/src/main/kotlin/com/stash/core/data/db/StashDatabase.kt` — `version = 25 → 26`, add `MIGRATION_25_26`.
- `core/data/src/main/kotlin/com/stash/core/data/db/entity/TrackEntity.kt` — add `isStreamable: Boolean = false` (column `is_streamable`) and `isStreamableCheckedAt: Long? = null` (column `is_streamable_checked_at`).
- `core/data/src/main/kotlin/com/stash/core/data/di/DatabaseModule.kt` — register `MIGRATION_25_26`.
- `core/data/src/main/kotlin/com/stash/core/data/db/dao/TrackDao.kt` — every "library content" `@Query` gets an `includeStreamable: Boolean = false` parameter and an updated `WHERE` clause. Affects: `getAllAlbums`, `getAllArtists`, `getAllDownloadedTracks`, `getByPlaylist`, `getTotalCount`, FTS variants.
- `core/data/src/main/kotlin/com/stash/core/data/db/dao/PlaylistDao.kt` — `getAllVisible` gets the same `includeStreamable` param.
- `core/data/src/main/kotlin/com/stash/core/data/repository/MusicRepositoryImpl.kt` — read `StreamingPreference.enabled.first()` once at each public method that calls into the affected DAO methods; thread the flag down.
- `core/data/src/main/kotlin/com/stash/core/data/sync/workers/DiffWorker.kt` — when `StreamingPreference.enabled = true`, skip `TrackDownloadWorker.enqueue` for newly-inserted rows; enqueue `AvailabilityCheckWorker` instead.
- `core/media/src/main/kotlin/com/stash/core/media/PlayerRepositoryImpl.kt` — extend `buildMediaItem` with the local/cached/resolve decision tree from the spec.
- `core/media/src/main/kotlin/com/stash/core/media/service/StashPlaybackService.kt` — wire the `RefreshingDataSourceFactory` into ExoPlayer's `MediaSource.Factory`; install the `Player.Listener` hook for pre-fetch-next-track.
- `feature/home/src/main/kotlin/com/stash/feature/home/HomeScreen.kt` — render `StreamingModeToggle` above "Recently played".
- `feature/home/src/main/kotlin/com/stash/feature/home/HomeViewModel.kt` — surface `StreamingPreference.enabled` as a `StateFlow<Boolean>`; emit toggle events via a callback that triggers `MusicRepository.applyStreamingMode(enabled)`.
- `feature/library/src/main/kotlin/com/stash/feature/library/*Screen.kt` — track-row composables render greyed-out at 50% opacity when `track.isDownloaded == false && track.isStreamable == false && track.isStreamableCheckedAt != null`. Long-press menu adds "Download for offline" / "Remove download".
- `feature/search/src/main/kotlin/com/stash/feature/search/SearchScreen.kt` — in Online mode, primary tap action on a search result is "stream now" (calls `PlayerRepositoryImpl.play`) instead of "download."
- `feature/nowplaying/src/main/kotlin/com/stash/feature/nowplaying/NowPlayingScreen.kt` — small wifi icon prefix on the existing quality line when playing a streamed source.
- `app/src/main/kotlin/com/stash/app/StashApplication.kt` — at first launch after the v25→v26 migration, enqueue `AvailabilityCheckWorker` for every existing `is_downloaded = 0, is_streamable_checked_at IS NULL` row. Schedule the periodic `AvailabilityRecheckWorker`.
- `app/build.gradle.kts` — add a `buildConfigField("Boolean", "STREAMING_ENGINE_ENABLED", "false")` flag. All UI affordances and worker scheduling gate on this flag while subprojects B/C are still pending.

---

## Sequencing rationale

Bottom-up so each task's outputs can be unit-tested before higher layers depend on them. UI is last so the engine works end-to-end via tests before any pixel renders.

1. **Schema + entity columns** (no callers yet).
2. **`StreamingPreference`** (pure DataStore wrapper).
3. **`KennyyStreamResolver`** + **`StreamUrlCache`** (resolution layer, depend only on existing KennyySource).
4. **`StreamCache`** + **`RefreshingDataSourceFactory`** + **`StreamingMediaSourceFactory`** (player-infrastructure layer).
5. **`AvailabilityCheckWorker`** + **`AvailabilityRecheckWorker`** (background workers; depend on DAO + KennyySource).
6. **DAO query audit + `includeStreamable` plumbing** (affects many existing queries; do as a single coherent task per DAO).
7. **`PlayerRepositoryImpl` streaming routing** (uses the resolver + cache + media-source factory).
8. **`DiffWorker` streaming-mode branch** + **`ReleaseDownloadsWorker`** + mode transition orchestrator in `MusicRepositoryImpl`.
9. **UI** — toggle, prompts, library row treatment, long-press menu, search-mode tap action, Now Playing indicator.
10. **`BuildConfig` gating + `StashApplication.onCreate` wiring + end-to-end manual validation**.

---

## Conventions

- **Commit after each task passes its tests.** Each task lists exact `git add` paths and a one-line commit message.
- **Gradle**: `./gradlew` on Linux/macOS, `.\gradlew.bat` on Windows; this is a Windows machine — use the slash form via bash (`./gradlew`) since the project ships `gradlew` (a bash script) alongside `gradlew.bat`.
- **Tests live next to code.** A class in `core/media/.../streaming/Foo.kt` has its test in `core/media/.../streaming/FooTest.kt` (path mirrored under `src/test/`).
- **No emojis, no fluff comments** — match the existing style (`PreampProcessor.kt` is the canonical example).
- **DataStore keys**: lowercase snake_case (`streaming_enabled` etc.), matches `eq_state_v1_json` / `loudness_state_v1_json` precedent.

---

### Task 1: DB schema delta — `is_streamable` + `is_streamable_checked_at` columns

**Files:**
- Modify: `core/data/src/main/kotlin/com/stash/core/data/db/entity/TrackEntity.kt`
- Modify: `core/data/src/main/kotlin/com/stash/core/data/db/StashDatabase.kt` (bump version, add migration)
- Modify: `core/data/src/main/kotlin/com/stash/core/data/di/DatabaseModule.kt` (register migration)
- Test: `core/data/src/test/kotlin/com/stash/core/data/db/MigrationV25V26Test.kt`

- [ ] **Step 1: Add the failing migration test**

Follow the pattern of `MigrationV23V24Test` (Robolectric + `MigrationTestHelper`). Test asserts:

```kotlin
@Test
fun migrate25To26_addsIsStreamableColumns() {
    helper.createDatabase(TEST_DB, 25).apply {
        insertTrackV25(this, id = 1)
        close()
    }
    val db = helper.runMigrationsAndValidate(TEST_DB, 26, true, StashDatabase.MIGRATION_25_26)
    db.query("SELECT is_streamable, is_streamable_checked_at FROM tracks WHERE id = 1").use { c ->
        assertThat(c.moveToFirst()).isTrue()
        assertThat(c.getInt(0)).isEqualTo(0)         // default 0
        assertThat(c.isNull(1)).isTrue()             // nullable, no default
    }
}

@Test
fun migrate25To26_roundTripsStreamableValues() {
    val db = helper.runMigrationsAndValidate(TEST_DB, 26, true, StashDatabase.MIGRATION_25_26)
    db.execSQL("UPDATE tracks SET is_streamable = 1, is_streamable_checked_at = 1700000000000 WHERE id = 1")
    db.query("SELECT is_streamable, is_streamable_checked_at FROM tracks WHERE id = 1").use { c ->
        c.moveToFirst()
        assertThat(c.getInt(0)).isEqualTo(1)
        assertThat(c.getLong(1)).isEqualTo(1700000000000L)
    }
}
```

- [ ] **Step 2: Run and verify FAIL**

```
./gradlew :core:data:testDebugUnitTest --tests "*MigrationV25V26Test*"
```

Expected: FAIL with "Unresolved reference: MIGRATION_25_26".

- [ ] **Step 3: Implement migration**

In `StashDatabase.kt`:
- Bump `version = 25` to `version = 26`.
- Add inside the `companion object`:

```kotlin
/**
 * v25 → v26: per-track streamability metadata.
 *
 * `is_streamable` is the cached result of an `AvailabilityCheckWorker`
 * lookup against Kennyy's Qobuz proxy. `is_streamable_checked_at` is the
 * timestamp of that lookup (NULL = never checked, so the worker can drain
 * the un-checked rows on first run).
 *
 * Both columns are additive; existing rows survive untouched with their
 * default values, and the `AvailabilityCheckWorker` fills them in over
 * the next few minutes after first launch on v0.9.27.
 */
val MIGRATION_25_26 = object : Migration(25, 26) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE tracks ADD COLUMN is_streamable INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE tracks ADD COLUMN is_streamable_checked_at INTEGER")
    }
}
```

In `TrackEntity.kt`, add the matching fields immediately after the `loudnessMeasuredAt` field added in v24:

```kotlin
@ColumnInfo(name = "is_streamable", defaultValue = "0")
val isStreamable: Boolean = false,

@ColumnInfo(name = "is_streamable_checked_at")
val isStreamableCheckedAt: Long? = null,
```

In `DatabaseModule.kt`, register the migration:

```kotlin
StashDatabase.MIGRATION_24_25,
StashDatabase.MIGRATION_25_26,
```

**Also register the migration in every test-DB builder.** Grep for `inMemoryDatabaseBuilder` and `Room.databaseBuilder` under `**/src/androidTest/**` and `**/src/test/**` — each instance that uses `.addMigrations(...)` needs `MIGRATION_25_26` appended. Missing this causes confusing "schema mismatch" errors in instrumentation tests on v26.

- [ ] **Step 4: Run and verify PASS**

```
./gradlew :core:data:testDebugUnitTest --tests "*MigrationV25V26Test*"
```

Expected: both tests PASS.

Then run `./gradlew :core:data:compileDebugKotlin` to force the schema export (the project has `exportSchema = true` so Room writes `26.json`).

- [ ] **Step 5: Commit**

```bash
git add core/data/src/main/kotlin/com/stash/core/data/db/StashDatabase.kt \
        core/data/src/main/kotlin/com/stash/core/data/db/entity/TrackEntity.kt \
        core/data/src/main/kotlin/com/stash/core/data/di/DatabaseModule.kt \
        core/data/src/test/kotlin/com/stash/core/data/db/MigrationV25V26Test.kt \
        core/data/schemas/com.stash.core.data.db.StashDatabase/26.json
git commit -m "feat(db): v26 migration adds is_streamable + is_streamable_checked_at"
```

---

### Task 2: `StreamingPreference` — DataStore wrapper

**Files:**
- Create: `core/data/src/main/kotlin/com/stash/core/data/prefs/StreamingPreference.kt`
- Test: `core/data/src/test/kotlin/com/stash/core/data/prefs/StreamingPreferenceTest.kt`

- [ ] **Step 1: Failing test**

Mirror `StashMixPreferenceTest`'s scaffolding (`PreferenceDataStoreFactory.create(produceFile = { tmpFile })`). Tests:

```kotlin
@Test fun enabled_defaultsToFalse() { ... }
@Test fun enabled_roundTrips() { ... }
@Test fun streamOnCellular_defaultsToFalse() { ... }
@Test fun streamOnCellular_roundTrips() { ... }
@Test fun streamQuality_defaultsToLossless() { ... }
@Test fun streamQuality_roundTrips() { ... }
@Test fun current_returnsLatestValue() { ... }
```

- [ ] **Step 2: Run and verify FAIL**

```
./gradlew :core:data:testDebugUnitTest --tests "*StreamingPreferenceTest*"
```

Expected: FAIL with "Unresolved reference: StreamingPreference".

- [ ] **Step 3: Implement**

Mirror `StashMixPreference.kt` byte-for-byte where possible. Add:

```kotlin
private val Context.streamingDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "streaming_preference",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

enum class StreamQualityTier { LOSSLESS, HIGH_QUALITY_LOSSY }

@Singleton
class StreamingPreference @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val enabledKey = booleanPreferencesKey("streaming_enabled")
    private val cellularKey = booleanPreferencesKey("streaming_on_cellular")
    private val qualityKey = stringPreferencesKey("streaming_quality_tier")

    val enabled: Flow<Boolean> = context.streamingDataStore.data.map { it[enabledKey] ?: false }
    val streamOnCellular: Flow<Boolean> = context.streamingDataStore.data.map { it[cellularKey] ?: false }
    val streamQuality: Flow<StreamQualityTier> = context.streamingDataStore.data.map {
        runCatching { StreamQualityTier.valueOf(it[qualityKey] ?: "") }
            .getOrDefault(StreamQualityTier.LOSSLESS)
    }

    suspend fun current(): Boolean = enabled.first()

    suspend fun setEnabled(value: Boolean) {
        context.streamingDataStore.edit { it[enabledKey] = value }
    }
    suspend fun setStreamOnCellular(value: Boolean) {
        context.streamingDataStore.edit { it[cellularKey] = value }
    }
    suspend fun setStreamQuality(tier: StreamQualityTier) {
        context.streamingDataStore.edit { it[qualityKey] = tier.name }
    }
}
```

- [ ] **Step 4: Run and verify PASS**

```
./gradlew :core:data:testDebugUnitTest --tests "*StreamingPreferenceTest*"
```

Expected: all 7 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add core/data/src/main/kotlin/com/stash/core/data/prefs/StreamingPreference.kt \
        core/data/src/test/kotlin/com/stash/core/data/prefs/StreamingPreferenceTest.kt
git commit -m "feat(prefs): StreamingPreference (enabled / streamOnCellular / streamQuality)"
```

---

### Task 3: `KennyyStreamResolver` — wraps KennyySource + parses etsp TTL

**Files:**
- Create: `core/media/src/main/kotlin/com/stash/core/media/streaming/KennyyStreamResolver.kt`
- Test: `core/media/src/test/kotlin/com/stash/core/media/streaming/KennyyStreamResolverTest.kt`

The wrapper turns a `TrackEntity` (or its identity fields) into a `StreamUrl(url, expiresAtMs)`. It calls the existing `KennyySource.resolve(TrackQuery)` and, on success, extracts the `etsp` query parameter from the returned `downloadUrl` to compute `expiresAtMs`. `etsp` is a Unix epoch *seconds* timestamp; multiply by 1000.

**Note on `streamQuality` preference (v1 deferral):** `TrackQuery` (`data/download/.../lossless/LosslessSource.kt:80`) currently has no `preferredQuality` field — `KennyySource` reads quality from its injected `LosslessSourcePreferences` instead. For v1, streaming quality follows the existing lossless-download preference. The `streamQuality` flow on `StreamingPreference` is stored but unused by the resolver until a follow-up task adds a `preferredQuality` field to `TrackQuery` and threads it through `KennyySource.resolve()`. Document this in the toggle UI (Task 16): the quality submenu is hidden in v1.

- [ ] **Step 1: Failing test**

```kotlin
class KennyyStreamResolverTest {
    private val fakeKennyy: KennyySource = mockk()
    private val resolver = KennyyStreamResolver(fakeKennyy)

    @Test fun resolve_returnsNullWhenKennyyHasNoMatch() = runTest {
        coEvery { fakeKennyy.resolve(any()) } returns null
        val result = resolver.resolve(stubTrack())
        assertThat(result).isNull()
    }

    @Test fun resolve_parsesEtspIntoExpiresAtMs() = runTest {
        coEvery { fakeKennyy.resolve(any()) } returns stubSourceResult(
            downloadUrl = "https://streaming-qobuz-std.akamaized.net/file?uid=1&etsp=1778893323&hmac=abc",
        )
        val result = resolver.resolve(stubTrack())
        assertThat(result).isNotNull()
        assertThat(result!!.url).contains("streaming-qobuz-std")
        assertThat(result.expiresAtMs).isEqualTo(1_778_893_323_000L)
    }

    @Test fun resolve_returnsNullWhenEtspMissing() = runTest {
        // Defensive: a URL with no etsp can't be safely refreshed; treat as null.
        coEvery { fakeKennyy.resolve(any()) } returns stubSourceResult(
            downloadUrl = "https://streaming-qobuz-std.akamaized.net/file?uid=1&hmac=abc",
        )
        val result = resolver.resolve(stubTrack())
        assertThat(result).isNull()
    }

    @Test fun resolve_returnsNullWhenEtspNotInteger() = runTest {
        coEvery { fakeKennyy.resolve(any()) } returns stubSourceResult(
            downloadUrl = "https://streaming-qobuz-std.akamaized.net/file?uid=1&etsp=garbage&hmac=abc",
        )
        val result = resolver.resolve(stubTrack())
        assertThat(result).isNull()
    }
}
```

- [ ] **Step 2: Run and verify FAIL**

```
./gradlew :core:media:testDebugUnitTest --tests "*KennyyStreamResolverTest*"
```

Expected: FAIL with "Unresolved reference: KennyyStreamResolver".

- [ ] **Step 3: Implement**

```kotlin
package com.stash.core.media.streaming

import com.stash.core.data.db.entity.TrackEntity
import com.stash.data.download.lossless.kennyy.KennyySource
import com.stash.data.download.lossless.TrackQuery
import javax.inject.Inject
import javax.inject.Singleton

data class StreamUrl(
    val url: String,
    val expiresAtMs: Long,
)

@Singleton
class KennyyStreamResolver @Inject constructor(
    private val source: KennyySource,
) {
    suspend fun resolve(track: TrackEntity): StreamUrl? {
        val query = TrackQuery(
            artist = track.artist,
            title = track.title,
            album = track.album.takeIf { it.isNotBlank() },
            isrc = track.isrc?.takeIf { it.isNotBlank() },
            durationMs = track.durationMs,
        )
        val result = source.resolve(query) ?: return null
        val etspMs = parseEtspMs(result.downloadUrl) ?: return null
        return StreamUrl(url = result.downloadUrl, expiresAtMs = etspMs)
    }

    private fun parseEtspMs(url: String): Long? {
        // etsp=<unix-epoch-seconds>; multiply by 1000 to align with System.currentTimeMillis()
        val match = ETSP_REGEX.find(url) ?: return null
        val secs = match.groupValues[1].toLongOrNull() ?: return null
        return secs * 1000L
    }

    private companion object {
        val ETSP_REGEX = Regex("""[?&]etsp=(\d+)""")
    }
}
```

- [ ] **Step 4: Run and verify PASS**

```
./gradlew :core:media:testDebugUnitTest --tests "*KennyyStreamResolverTest*"
```

Expected: all 4 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add core/media/src/main/kotlin/com/stash/core/media/streaming/KennyyStreamResolver.kt \
        core/media/src/test/kotlin/com/stash/core/media/streaming/KennyyStreamResolverTest.kt
git commit -m "feat(streaming): KennyyStreamResolver — wraps KennyySource, parses etsp TTL"
```

---

### Task 4: `StreamUrlCache` — in-memory TTL cache

**Files:**
- Create: `core/media/src/main/kotlin/com/stash/core/media/streaming/StreamUrlCache.kt`
- Test: `core/media/src/test/kotlin/com/stash/core/media/streaming/StreamUrlCacheTest.kt`

- [ ] **Step 1: Failing test**

```kotlin
class StreamUrlCacheTest {
    private var now = 0L
    private val cache = StreamUrlCache(nowMsProvider = { now })

    @Test fun get_returnsNullForUnknownKey() { ... }
    @Test fun get_returnsCachedValueWithinTtl() {
        cache.put(1L, StreamUrl("https://...", expiresAtMs = 1000L))
        now = 500L
        assertThat(cache.get(1L)?.url).contains("https://")
    }
    @Test fun get_returnsNullPastExpiry() {
        cache.put(1L, StreamUrl("https://...", expiresAtMs = 1000L))
        now = 1001L
        assertThat(cache.get(1L)).isNull()
    }
    @Test fun put_overwritesExistingEntry() { ... }
    @Test fun invalidate_dropsEntry() { ... }
}
```

- [ ] **Step 2: Run and verify FAIL**

- [ ] **Step 3: Implement**

```kotlin
package com.stash.core.media.streaming

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StreamUrlCache @Inject constructor(
    private val nowMsProvider: () -> Long = System::currentTimeMillis,
) {
    private val cache = ConcurrentHashMap<Long, StreamUrl>()

    fun get(trackId: Long): StreamUrl? {
        val entry = cache[trackId] ?: return null
        return if (entry.expiresAtMs > nowMsProvider()) entry else {
            cache.remove(trackId)
            null
        }
    }

    fun put(trackId: Long, url: StreamUrl) {
        cache[trackId] = url
    }

    fun invalidate(trackId: Long) {
        cache.remove(trackId)
    }
}
```

- [ ] **Step 4: Pass**

- [ ] **Step 5: Commit**

```bash
git add core/media/src/main/kotlin/com/stash/core/media/streaming/StreamUrlCache.kt \
        core/media/src/test/kotlin/com/stash/core/media/streaming/StreamUrlCacheTest.kt
git commit -m "feat(streaming): StreamUrlCache — in-memory TTL cache for resolved URLs"
```

---

### Task 5: `StreamCache` — Media3 SimpleCache for streamed bytes

**Files:**
- Create: `core/media/src/main/kotlin/com/stash/core/media/streaming/StreamCache.kt`
- (No new test — `SimpleCache` is exercised indirectly by integration tests in Task 7.)

Hilt-bound singleton `SimpleCache` instance, separate from the existing `PreviewCache` so eviction policies don't collide.

- [ ] **Step 1: Implement**

```kotlin
package com.stash.core.media.streaming

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class StreamCache

@Module
@InstallIn(SingletonComponent::class)
@OptIn(UnstableApi::class)
object StreamCacheModule {
    private const val MAX_BYTES = 500L * 1024 * 1024  // 500 MB
    private const val CACHE_DIR = "stream_cache"

    @Provides
    @Singleton
    @StreamCache
    fun provideStreamCache(@ApplicationContext context: Context): SimpleCache {
        val dir = File(context.cacheDir, CACHE_DIR).also { it.mkdirs() }
        // Separate database provider per cache instance (Media3 requirement;
        // sharing across SimpleCache instances corrupts internal state).
        return SimpleCache(
            dir,
            LeastRecentlyUsedCacheEvictor(MAX_BYTES),
            StandaloneDatabaseProvider(context),
        )
    }
}
```

- [ ] **Step 2: Verify compilation**

```
./gradlew :core:media:compileDebugKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add core/media/src/main/kotlin/com/stash/core/media/streaming/StreamCache.kt
git commit -m "feat(streaming): StreamCache (Media3 SimpleCache, 500 MB LRU)"
```

---

### Task 6: `RefreshingDataSourceFactory` — handles mid-stream URL expiry

**Files:**
- Create: `core/media/src/main/kotlin/com/stash/core/media/streaming/RefreshingDataSourceFactory.kt`
- Test: `core/media/src/test/kotlin/com/stash/core/media/streaming/RefreshingDataSourceFactoryTest.kt`

When Qobuz's signed URL expires (`etsp` passes), the CDN returns 403. We detect, re-resolve, and re-open at the same byte offset. This is what makes pause-and-resume-hours-later work.

- [ ] **Step 1: Failing test**

```kotlin
class RefreshingDataSourceFactoryTest {
    private val fakeResolver: KennyyStreamResolver = mockk()
    private val fakeCache: StreamUrlCache = mockk(relaxed = true)
    private val fakeInner: HttpDataSource = mockk()

    private fun newSource(trackId: Long): RefreshingDataSource =
        RefreshingDataSource(fakeInner, fakeResolver, fakeCache, trackId)

    @Test fun open_returnsLengthFromInnerOnHappyPath() {
        every { fakeInner.open(any()) } returns 1024L
        val source = newSource(trackId = 42L)
        assertThat(source.open(stubSpec("https://...?etsp=1700000000"))).isEqualTo(1024L)
    }

    @Test fun open_on403_reResolvesAndRetriesFromSameOffset() {
        val freshUrl = StreamUrl("https://fresh-url?etsp=1800000000", expiresAtMs = 1_800_000_000_000L)
        every { fakeInner.open(specWithUri("https://stale-url")) } throws
            HttpDataSource.InvalidResponseCodeException(403, "Forbidden", emptyMap(), DataSpec(Uri.EMPTY), ByteArray(0))
        coEvery { fakeResolver.resolve(/* trackId=42 lookup */ any()) } returns freshUrl
        every { fakeInner.open(specWithUri("https://fresh-url?etsp=1800000000")) } returns 1024L

        val source = newSource(trackId = 42L)
        val length = source.open(stubSpec("https://stale-url").buildUpon().setPosition(500).build())

        assertThat(length).isEqualTo(1024L)
        verify { fakeCache.put(42L, freshUrl) }
    }

    @Test fun open_on500_propagatesError() { ... }
    @Test fun open_on410_reResolvesLikeOn403() { ... }
}
```

You'll need a small `TrackLookup` interface OR inject the `TrackDao` so the resolver can fetch the `TrackEntity` by ID. Pick whichever feels cleaner; the simplest is to keep the trackId → TrackEntity lookup outside `RefreshingDataSource` and pass a pre-resolved `KennyyStreamResolver` that already knows the track. Concretely: the factory holds the trackId and resolver, the resolver knows how to look up the track via injected `TrackDao`.

- [ ] **Step 2: Fail**

- [ ] **Step 3: Implement**

```kotlin
@OptIn(UnstableApi::class)
class RefreshingDataSource(
    private val inner: HttpDataSource,
    private val resolver: KennyyStreamResolver,
    private val cache: StreamUrlCache,
    private val trackId: Long,
    private val trackDao: TrackDao,
) : DataSource by inner {
    override fun open(spec: DataSpec): Long {
        return try {
            inner.open(spec)
        } catch (e: HttpDataSource.InvalidResponseCodeException) {
            if (e.responseCode !in REFRESH_TRIGGERS) throw e
            // Single coroutine scope for both lookups — avoids re-entrance
            // hazards from two independent runBlocking calls sharing the
            // loader thread with Room's connection pool + Kennyy's rate-limiter.
            val fresh = runBlocking {
                val track = trackDao.getById(trackId) ?: return@runBlocking null
                resolver.resolve(track)
            } ?: throw e
            cache.put(trackId, fresh)
            val newSpec = spec.buildUpon().setUri(Uri.parse(fresh.url)).build()
            inner.open(newSpec)
        }
    }
}

@OptIn(UnstableApi::class)
class RefreshingDataSourceFactory(
    private val innerFactory: HttpDataSource.Factory,
    private val resolver: KennyyStreamResolver,
    private val cache: StreamUrlCache,
    private val trackDao: TrackDao,
    private val trackId: Long,
) : DataSource.Factory {
    override fun createDataSource(): DataSource =
        RefreshingDataSource(innerFactory.createDataSource(), resolver, cache, trackId, trackDao)
}

private val REFRESH_TRIGGERS = setOf(403, 410)
```

**Note on construction:** `RefreshingDataSource` / `RefreshingDataSourceFactory` are NOT Hilt-injected. They're constructed per-track inside `StreamingMediaSourceFactory.create(trackId)` (Task 7) which IS a Hilt singleton — that singleton passes its injected `resolver` / `urlCache` / `trackDao` through to the per-track factory.

**Note on `runBlocking`:** safe inside `DataSource.open()` because Media3 calls it from its own loader thread, never the main thread. Use one `runBlocking` block that does both the DAO lookup and the resolver call sequentially — two independent blocking calls could re-enter Room's connection pool or Kennyy's `AggregatorRateLimiter` and deadlock.

**DAO support note:** uses the existing `suspend fun getById(trackId: Long): TrackEntity?` (already defined in `TrackDao.kt:340`). No new DAO method needed for this task.

- [ ] **Step 4: Pass**

- [ ] **Step 5: Commit**

```bash
git add core/media/src/main/kotlin/com/stash/core/media/streaming/RefreshingDataSourceFactory.kt \
        core/media/src/test/kotlin/com/stash/core/media/streaming/RefreshingDataSourceFactoryTest.kt
git commit -m "feat(streaming): RefreshingDataSourceFactory — re-resolves on 403/410"
```

---

### Task 7: `StreamingMediaSourceFactory` — wraps cache + refresh + http

**Files:**
- Create: `core/media/src/main/kotlin/com/stash/core/media/streaming/StreamingMediaSourceFactory.kt`
- Test: `core/media/src/test/kotlin/com/stash/core/media/streaming/StreamingMediaSourceFactoryTest.kt`

The final composed `DataSource.Factory` that ExoPlayer uses to load streamed audio. Order of wrapping (outer → inner):
1. `CacheDataSource.Factory` (writes to `StreamCache`, reads from it on hit)
2. `RefreshingDataSourceFactory` (handles 403/410)
3. `DefaultHttpDataSource.Factory` (actual HTTP)

- [ ] **Step 1: Failing test**

Test the factory composition. Mock the inner pieces and verify each is created with the expected arguments. Smaller verification surface — most behaviour is exercised in the integration tests later.

- [ ] **Step 3: Implement**

```kotlin
@OptIn(UnstableApi::class)
@Singleton
class StreamingMediaSourceFactory @Inject constructor(
    @StreamCache private val streamCache: SimpleCache,
    private val resolver: KennyyStreamResolver,
    private val urlCache: StreamUrlCache,
    private val trackDao: TrackDao,
) {
    fun create(trackId: Long): MediaSource.Factory {
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("Stash/${BuildConfig.VERSION_NAME}")
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(30_000)
        val refreshFactory = RefreshingDataSourceFactory(
            innerFactory = httpFactory,
            resolver = resolver,
            cache = urlCache,
            trackDao = trackDao,
            trackId = trackId,
        )
        val cachedFactory = CacheDataSource.Factory()
            .setCache(streamCache)
            .setUpstreamDataSourceFactory(refreshFactory)
            .setCacheWriteDataSinkFactory(
                CacheDataSink.Factory().setCache(streamCache)
            )
            .setFlags(
                CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR or
                    CacheDataSource.FLAG_BLOCK_ON_CACHE
            )
        return DefaultMediaSourceFactory(cachedFactory)
    }
}
```

- [ ] **Step 4: Pass**

- [ ] **Step 5: Commit**

```bash
git add core/media/src/main/kotlin/com/stash/core/media/streaming/StreamingMediaSourceFactory.kt \
        core/media/src/test/kotlin/com/stash/core/media/streaming/StreamingMediaSourceFactoryTest.kt
git commit -m "feat(streaming): StreamingMediaSourceFactory — cache + refresh + http composition"
```

---

### Task 8: DAO query audit — add `includeStreamable` parameter

**Files:**
- Modify: `core/data/src/main/kotlin/com/stash/core/data/db/dao/TrackDao.kt`
- Modify: `core/data/src/main/kotlin/com/stash/core/data/db/dao/PlaylistDao.kt`
- Test: `core/data/src/test/kotlin/com/stash/core/data/db/dao/TrackDaoStreamableTest.kt`

The methods that filter on `is_downloaded = 1` need an `includeStreamable: Boolean` parameter. When `true`, the `WHERE` clause becomes `(is_downloaded = 1 OR (is_downloaded = 0 AND is_streamable = 1))`.

Audit list (from the spec):
- `getAllAlbums` (already returns AlbumSummary; change the `WHERE` clause)
- `getAllArtists`
- `getAllDownloadedTracks` (rename to `getAllLibraryTracks` or keep as-is and add a parallel method — pick one in this commit)
- `getByPlaylist`
- `getTotalCount`
- **FTS variants** — see below
- `PlaylistDao.getAllVisible`

**FTS query shape:** the existing `search(query)` (TrackDao.kt:787) uses `JOIN tracks_fts ON tracks.rowid = tracks_fts.rowid WHERE tracks_fts MATCH :query`. The `tracks_fts` virtual table doesn't have the `is_downloaded` / `is_streamable` columns — the filter is added to the `tracks` JOIN side, not the FTS MATCH side. Augmented query (preserving the existing blocklist join):

```sql
SELECT tracks.* FROM tracks
JOIN tracks_fts ON tracks.rowid = tracks_fts.rowid
LEFT JOIN track_blocklist bl
    ON bl.canonical_key = (tracks.canonical_artist || '|' || tracks.canonical_title)
    OR (bl.spotify_uri IS NOT NULL AND bl.spotify_uri = tracks.spotify_uri)
    OR (bl.youtube_id  IS NOT NULL AND bl.youtube_id  = tracks.youtube_id)
WHERE tracks_fts MATCH :query
  AND bl.canonical_key IS NULL
  AND (tracks.is_downloaded = 1 OR (:includeStreamable AND tracks.is_streamable = 1))
```

Apply the same `AND (tracks.is_downloaded = 1 OR (:includeStreamable AND tracks.is_streamable = 1))` predicate to every other FTS-based query in `TrackDao.kt` (grep `tracks_fts MATCH` to enumerate them).

**Room and Kotlin defaults:** do NOT use `= false` defaults on `@Query` methods. Room's kapt processor doesn't always honour them and produces confusing errors at compile time. Require every caller to pass `includeStreamable` explicitly — most callers in this codebase already wrap the DAO in `MusicRepositoryImpl`, so there's only one read site per query.

**Library-Health queries stay unchanged** (downloaded-only).

- [ ] **Step 1: Failing test**

```kotlin
class TrackDaoStreamableTest {
    @Test fun getAllAlbums_excludesStreamableByDefault() {
        insertDownloaded(album = "Album A", artist = "Drake")
        insertStreamableOnly(album = "Album B", artist = "Drake")
        val albums = dao.getAllAlbums(includeStreamable = false).first()
        assertThat(albums.map { it.album }).containsExactly("Album A")
    }

    @Test fun getAllAlbums_includesStreamableWhenFlagged() {
        insertDownloaded(album = "Album A", artist = "Drake")
        insertStreamableOnly(album = "Album B", artist = "Drake")
        val albums = dao.getAllAlbums(includeStreamable = true).first()
        assertThat(albums.map { it.album }).containsExactly("Album A", "Album B")
    }

    @Test fun getAllAlbums_excludesUnavailableEvenWhenStreamableTrue() {
        insertUnavailable(album = "Album C", artist = "Drake")  // is_streamable = 0, checked_at != null
        val albums = dao.getAllAlbums(includeStreamable = true).first()
        assertThat(albums.map { it.album }).doesNotContain("Album C")
    }

    // …same pattern for getAllArtists, getByPlaylist, getTotalCount, FTS, getAllVisible…
}
```

- [ ] **Step 3: Implement**

For each affected `@Query`, update like so (example `getAllAlbums`):

```kotlin
@Query("""
    SELECT t.album AS album,
           CASE
               WHEN t.album_artist != '' THEN t.album_artist
               ELSE (
                   SELECT artist FROM tracks
                   WHERE album = t.album AND artist != ''
                     AND COALESCE(album_artist, '') = COALESCE(t.album_artist, '')
                   GROUP BY artist
                   ORDER BY COUNT(*) DESC
                   LIMIT 1
               )
           END AS artist,
           COUNT(*) AS trackCount,
           MAX(t.album_art_path) AS artPath,
           MAX(t.album_art_url) AS artUrl
    FROM tracks t
    WHERE t.album != ''
      AND (t.is_downloaded = 1 OR (:includeStreamable AND t.is_streamable = 1))
    GROUP BY t.album, t.album_artist
    ORDER BY COUNT(*) DESC, t.album ASC
""")
fun getAllAlbums(includeStreamable: Boolean): Flow<List<AlbumSummary>>
```

The `:includeStreamable AND ...` short-circuit in SQLite means when the flag is `false` the streamable branch is skipped entirely.

Repeat for every other affected method. **No Kotlin default values** — every caller must pass the flag explicitly (existing wrappers in `MusicRepositoryImpl` need to be updated to thread the pref through).

- [ ] **Step 4: Pass**

- [ ] **Step 5: Commit**

```bash
git add core/data/src/main/kotlin/com/stash/core/data/db/dao/TrackDao.kt \
        core/data/src/main/kotlin/com/stash/core/data/db/dao/PlaylistDao.kt \
        core/data/src/test/kotlin/com/stash/core/data/db/dao/TrackDaoStreamableTest.kt
git commit -m "feat(db): includeStreamable param on library DAO queries"
```

---

### Task 9: `AvailabilityCheckWorker` — populate is_streamable

**Files:**
- Create: `core/data/src/main/kotlin/com/stash/core/data/sync/workers/AvailabilityCheckWorker.kt`
- Test: `core/data/src/test/kotlin/com/stash/core/data/sync/workers/AvailabilityCheckWorkerTest.kt`

- [ ] **Step 1: Failing tests** (4 scenarios mirroring `LoudnessBackfillWorkerTest` patterns):

```kotlin
@Test fun emptyQueue_returnsSuccessWithoutEnqueuingFollowUp() { ... }
@Test fun batchOfThree_writesIsStreamableForEach() { ... }
@Test fun kennyyReturnsNull_writesIsStreamableFalse() { ... }
@Test fun rateLimited_throttlesAndRetries() { ... }
```

- [ ] **Step 3: Implement**

```kotlin
@HiltWorker
class AvailabilityCheckWorker @AssistedInject constructor(
    @Assisted ctx: Context,
    @Assisted params: WorkerParameters,
    private val trackDao: TrackDao,
    private val resolver: KennyyStreamResolver,
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val deadline = System.currentTimeMillis() + MAX_RUN_MS
        var anyProcessed = false
        while (true) {
            if (isStopped || System.currentTimeMillis() > deadline) break
            val batch = trackDao.tracksNeedingStreamableCheck(limit = BATCH_SIZE)
            if (batch.isEmpty()) break
            for (track in batch) {
                if (isStopped) break
                val available = runCatching { resolver.resolve(track) != null }
                    .getOrElse { false }
                trackDao.setStreamable(track.id, available, System.currentTimeMillis())
                anyProcessed = true
                delay(1_000)   // 1 req/s rate limit on Kennyy
            }
        }
        // Re-enqueue ourselves if there are still rows left + WorkManager hasn't told us to stop
        if (!isStopped && trackDao.tracksNeedingStreamableCheckCount() > 0) {
            enqueueSelf(applicationContext)
        }
        return Result.success(workDataOf(KEY_PROCESSED to anyProcessed))
    }

    companion object {
        const val WORK_NAME = "availability_check"
        const val KEY_PROCESSED = "processed"
        private const val BATCH_SIZE = 50
        private const val MAX_RUN_MS = 9L * 60_000   // stay under WorkManager's 10-min soft cap

        fun enqueueSelf(ctx: Context) {
            val req = OneTimeWorkRequestBuilder<AvailabilityCheckWorker>()
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 60, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(
                WORK_NAME, ExistingWorkPolicy.REPLACE, req,
            )
        }
    }
}
```

Add the supporting DAO methods:

```kotlin
@Query("SELECT * FROM tracks WHERE is_streamable_checked_at IS NULL AND file_path IS NULL LIMIT :limit")
suspend fun tracksNeedingStreamableCheck(limit: Int): List<TrackEntity>

@Query("SELECT COUNT(*) FROM tracks WHERE is_streamable_checked_at IS NULL AND file_path IS NULL")
suspend fun tracksNeedingStreamableCheckCount(): Int

@Query("UPDATE tracks SET is_streamable = :available, is_streamable_checked_at = :now WHERE id = :id")
suspend fun setStreamable(id: Long, available: Boolean, now: Long)
```

- [ ] **Step 4: Pass**

- [ ] **Step 5: Commit**

```bash
git add core/data/src/main/kotlin/com/stash/core/data/sync/workers/AvailabilityCheckWorker.kt \
        core/data/src/main/kotlin/com/stash/core/data/db/dao/TrackDao.kt \
        core/data/src/test/kotlin/com/stash/core/data/sync/workers/AvailabilityCheckWorkerTest.kt
git commit -m "feat(workers): AvailabilityCheckWorker — populate is_streamable via KennyySource"
```

---

### Task 10: `AvailabilityRecheckWorker` — 30-day stale check

**Files:**
- Create: `core/data/src/main/kotlin/com/stash/core/data/sync/workers/AvailabilityRecheckWorker.kt`
- Test: `core/data/src/test/kotlin/com/stash/core/data/sync/workers/AvailabilityRecheckWorkerTest.kt`

Periodic (weekly) worker. Resets `is_streamable_checked_at` to NULL for rows older than 30 days, then enqueues `AvailabilityCheckWorker`. Catches catalog churn (operator delistings + new additions).

- [ ] **Step 1: Failing test**
- [ ] **Step 3: Implement**

```kotlin
@HiltWorker
class AvailabilityRecheckWorker @AssistedInject constructor(
    @Assisted ctx: Context,
    @Assisted params: WorkerParameters,
    private val trackDao: TrackDao,
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val cutoff = System.currentTimeMillis() - RECHECK_AGE_MS
        val invalidated = trackDao.invalidateOldStreamableChecks(cutoff)
        if (invalidated > 0) {
            AvailabilityCheckWorker.enqueueSelf(applicationContext)
        }
        return Result.success()
    }

    companion object {
        const val WORK_NAME = "availability_recheck"
        private const val RECHECK_AGE_MS = 30L * 24 * 3600 * 1000  // 30 days

        fun schedulePeriodic(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<AvailabilityRecheckWorker>(7, TimeUnit.DAYS).build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, req,
            )
        }
    }
}
```

DAO support:

```kotlin
@Query("UPDATE tracks SET is_streamable_checked_at = NULL WHERE is_streamable_checked_at IS NOT NULL AND is_streamable_checked_at < :cutoff")
suspend fun invalidateOldStreamableChecks(cutoff: Long): Int
```

- [ ] **Step 5: Commit**

```bash
git commit -m "feat(workers): AvailabilityRecheckWorker — 30-day stale check"
```

---

### Task 11: `PlayerRepositoryImpl` — streaming routing in buildMediaItem

**Files:**
- Create: `core/media/src/main/kotlin/com/stash/core/media/streaming/ConnectivityMonitor.kt` (no existing wrapper in the codebase — grep confirmed)
- Modify: `core/media/src/main/kotlin/com/stash/core/media/PlayerRepositoryImpl.kt`
- Modify: `core/data/src/main/kotlin/com/stash/core/data/repository/PlayerRepository.kt` (interface — add `playFromStream`)
- Test: `core/media/src/test/kotlin/com/stash/core/media/streaming/ConnectivityMonitorTest.kt`
- Test: `core/media/src/test/kotlin/com/stash/core/media/PlayerRepositoryStreamingTest.kt`

- [ ] **Step 0: Create `ConnectivityMonitor`**

A small wrapper around Android's `ConnectivityManager` — keeps `PlayerRepositoryImpl` testable without mocking the Android system service directly.

```kotlin
package com.stash.core.media.streaming

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConnectivityMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val cm: ConnectivityManager
        get() = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    fun isConnected(): Boolean {
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
               caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    fun isCellular(): Boolean {
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    }
}
```

- [ ] **Step 1: Failing tests** — covers the decision tree:

```kotlin
@Test fun buildMediaItem_downloadedTrack_returnsLocalFileMediaItem() { ... }
@Test fun buildMediaItem_streamableTrackWithCacheHit_usesCachedUrl() { ... }
@Test fun buildMediaItem_streamableTrackWithCacheMiss_resolvesAndCaches() { ... }
@Test fun buildMediaItem_unavailableTrack_returnsNotAvailable() { ... }
@Test fun buildMediaItem_streamableTrackWithStreamingOff_returnsOfflineMode() { ... }
@Test fun buildMediaItem_streamableButCellularWithoutCellularPref_returnsCellularRefused() { ... }
@Test fun playFromStream_searchItem_resolvesAndPlays() { ... }
```

- [ ] **Step 3: Implement**

To surface a user-visible reason instead of silently returning null, return a sealed `StreamRoutingResult` from `buildMediaItemForTrack`. The caller — in this codebase, the queue/play orchestrator that eventually feeds `StashPlaybackService` — already has a `_uiEvents: MutableSharedFlow<UiEvent>` (snackbar channel). Wire `CellularRefused` and `OfflineMode` to emit a snackbar there.

```kotlin
sealed class StreamRoutingResult {
    data class Item(val mediaItem: MediaItem) : StreamRoutingResult()
    data object NotAvailable : StreamRoutingResult()      // no local, not streamable, or no checkedAt
    data object OfflineMode : StreamRoutingResult()       // streaming pref off, track streamable-only
    data object CellularRefused : StreamRoutingResult()   // streaming on, cellular only, pref off
    data object NoConnectivity : StreamRoutingResult()    // streaming on but device offline
}

private suspend fun buildMediaItemForTrack(track: TrackEntity): StreamRoutingResult {
    if (track.isDownloaded && filePathExistsOnDisk(track.filePath)) {
        return StreamRoutingResult.Item(
            MediaItem.Builder()
                .setMediaId(track.id.toString())
                .setUri(Uri.fromFile(File(track.filePath!!)))
                .build()
        )
    }
    if (!track.isStreamable) return StreamRoutingResult.NotAvailable
    if (!streamingPreference.current()) return StreamRoutingResult.OfflineMode
    if (!connectivity.isConnected()) return StreamRoutingResult.NoConnectivity
    if (connectivity.isCellular() && !streamingPreference.streamOnCellular.first()) {
        return StreamRoutingResult.CellularRefused
    }
    val cached = streamUrlCache.get(track.id)
    val stream = cached ?: kennyyStreamResolver.resolve(track)?.also {
        streamUrlCache.put(track.id, it)
    } ?: return StreamRoutingResult.NotAvailable
    return StreamRoutingResult.Item(
        MediaItem.Builder()
            .setMediaId(track.id.toString())
            .setUri(Uri.parse(stream.url))
            .build()
    )
}
```

In the caller (the existing tap handler that feeds the player):

```kotlin
when (val result = buildMediaItemForTrack(track)) {
    is StreamRoutingResult.Item -> player.setMediaItem(result.mediaItem); player.play()
    StreamRoutingResult.CellularRefused -> _uiEvents.emit(UiEvent.Snackbar("Streaming on cellular is off in Settings"))
    StreamRoutingResult.NoConnectivity -> _uiEvents.emit(UiEvent.Snackbar("You're offline — can't stream this track"))
    StreamRoutingResult.OfflineMode -> _uiEvents.emit(UiEvent.Snackbar("Turn on Online mode to stream this track"))
    StreamRoutingResult.NotAvailable -> { /* row should be greyed out; silently no-op */ }
}
```

Also add `playFromStream(item: TrackItem)` to the `PlayerRepository` interface — used by the Search screen (Task 20) which has `TrackItem` (search-result model) not `TrackEntity`:

```kotlin
// In PlayerRepository.kt interface
suspend fun playFromStream(item: TrackItem)

// In PlayerRepositoryImpl.kt
override suspend fun playFromStream(item: TrackItem) {
    val tempTrack = TrackEntity.fromSearchItem(item)   // existing converter, or inline-construct
    when (val result = buildMediaItemForTrack(tempTrack)) {
        is StreamRoutingResult.Item -> {
            player.setMediaItem(result.mediaItem); player.play()
        }
        else -> _uiEvents.emit(UiEvent.Snackbar(reasonFor(result)))
    }
}
```

The player layer (StashPlaybackService) configures ExoPlayer with `StreamingMediaSourceFactory.create(trackId)` so the streamed URI gets the cached + refreshing pipeline; downloaded URIs use ExoPlayer's default factory.

- [ ] **Step 5: Commit**

```bash
git add core/media/src/main/kotlin/com/stash/core/media/streaming/ConnectivityMonitor.kt \
        core/media/src/main/kotlin/com/stash/core/media/PlayerRepositoryImpl.kt \
        core/data/src/main/kotlin/com/stash/core/data/repository/PlayerRepository.kt \
        core/media/src/test/kotlin/com/stash/core/media/streaming/ConnectivityMonitorTest.kt \
        core/media/src/test/kotlin/com/stash/core/media/PlayerRepositoryStreamingTest.kt
git commit -m "feat(media): PlayerRepositoryImpl streaming routing + ConnectivityMonitor + playFromStream"
```

---

### Task 12: Pre-fetch next queue item

**Files:**
- Modify: `core/media/src/main/kotlin/com/stash/core/media/service/StashPlaybackService.kt`
- Test: `core/media/src/test/kotlin/com/stash/core/media/service/StreamingPrefetchTest.kt`

When the current track is >60% played, look up the next item; if streamable and not in cache, call `kennyyStreamResolver.resolve()` and warm the cache.

- [ ] **Step 1: Failing test**
- [ ] **Step 3: Implement** (extend the existing `Player.Listener` in StashPlaybackService).
- [ ] **Step 5: Commit**

```bash
git commit -m "feat(media): pre-fetch next streamable track at 60% played"
```

---

### Task 13: `DiffWorker` — streaming-mode branch

**Files:**
- Modify: `core/data/src/main/kotlin/com/stash/core/data/sync/workers/DiffWorker.kt`
- Test: extend `DiffWorkerTest` (create if absent; mirror `LoudnessBackfillWorkerTest` scaffolding)

The actual download-enqueue point is `downloadQueueDao.insert(DownloadQueueEntity(...))` (DiffWorker.kt:424). When `StreamingPreference.enabled = true`, this insert is skipped for newly-inserted track rows; an `AvailabilityCheckWorker` is enqueued once at the END of the worker (not per-row — avoids 50× enqueueUnique churn on a large sync).

**Read the preference ONCE at the top** of `doWork()`, not per-row. The user can't flip the toggle mid-sync — and even if they did, the pref already-loaded behavior is the consistent choice.

- [ ] **Step 1: Failing tests**

```kotlin
@Test fun downloadModeStillInsertsDownloadQueueRows() = runTest {
    streamingPreference.setEnabled(false)
    worker.doWork()
    assertThat(downloadQueueDao.allPending().size).isGreaterThan(0)
    assertThat(workManager.getWorkInfosForUniqueWork("availability_check").get()).isEmpty()
}

@Test fun streamingModeSkipsDownloadQueueAndEnqueuesAvailability() = runTest {
    streamingPreference.setEnabled(true)
    worker.doWork()
    assertThat(downloadQueueDao.allPending()).isEmpty()
    assertThat(workManager.getWorkInfosForUniqueWork("availability_check").get()).isNotEmpty()
}

@Test fun streamingModeWithZeroNewTracksDoesNotEnqueueAvailability() = runTest {
    streamingPreference.setEnabled(true)
    // …seed remote_snapshot tracks that all already exist locally…
    worker.doWork()
    assertThat(workManager.getWorkInfosForUniqueWork("availability_check").get()).isEmpty()
}
```

- [ ] **Step 3: Implement**

In `DiffWorker`:

```kotlin
// Add to constructor injection:
private val streamingPreference: StreamingPreference,

// Top of doWork() — read once, propagate:
val streamingMode = streamingPreference.current()
var newTrackCount = 0
// …existing playlist loop…

// At the existing downloadQueueDao.insert site (line 424):
if (!streamingMode) {
    downloadQueueDao.insert(
        DownloadQueueEntity(
            trackId = trackId,
            syncId = syncId,
            searchQuery = searchQuery,
            youtubeUrl = trackSnapshot.youtubeId?.let { "https://music.youtube.com/watch?v=$it" },
        )
    )
}
newTrackCount++   // unchanged — the row still exists, it's just not download-queued

// After the playlist loop completes, BEFORE returning Result.success():
if (streamingMode && newTrackCount > 0) {
    AvailabilityCheckWorker.enqueueSelf(applicationContext)
}
```

- [ ] **Step 5: Commit**

```bash
git add core/data/src/main/kotlin/com/stash/core/data/sync/workers/DiffWorker.kt \
        core/data/src/test/kotlin/com/stash/core/data/sync/workers/DiffWorkerTest.kt
git commit -m "feat(sync): DiffWorker skips download queue + enqueues availability check in streaming mode"
```

---

### Task 14: `ReleaseDownloadsWorker` — Off→On "release the space" path

**Files:**
- Create: `core/data/src/main/kotlin/com/stash/core/data/sync/workers/ReleaseDownloadsWorker.kt`
- Modify: `core/data/src/main/kotlin/com/stash/core/data/db/dao/TrackDao.kt` (add `markAsNotDownloaded`)
- Modify: `core/data/src/main/kotlin/com/stash/core/data/prefs/SyncPreferencesManager.kt` (or sibling DataStore) to add a `release_downloads_last_processed_id` key
- Test: `core/data/src/test/kotlin/com/stash/core/data/sync/workers/ReleaseDownloadsWorkerTest.kt`

Reuses the existing `MusicRepository.deleteTrack(track)` path which already calls `deleteTrackFile()` (MusicRepositoryImpl.kt:73) AND clears the DB row in one call. That's the file-delete-then-DB-update ordering we want — if we crash after `deleteTrackFile()` returns but before the DAO write, the next run sees a stale `is_downloaded = 1` row pointing at a missing file. To make THIS worker crash-safe, set `is_downloaded = 0` + `file_path = NULL` *first*, then delete the file. A crash leaves an orphaned file (cleaned up by the existing `OrphanCleanupWorker`), not a stale-pointer DB row. This is the opposite ordering from `deleteTrack` because we have different crash-safety requirements.

**Resumability:** stash the last-processed `track.id` in a small per-worker DataStore key. On restart, query `is_downloaded = 1 AND id > :lastId` ordered by id ASC. Batch size 100 keeps each transaction short.

- [ ] **Step 1: Failing tests**

```kotlin
@Test fun releaseProcessesAllDownloadedRows_clearsDbAndDeletesFiles() = runTest {
    seedDownloaded(count = 5)
    worker.doWork()
    assertThat(trackDao.downloadedCount()).isEqualTo(0)
    assertThat(testFileSystem.remainingFiles()).isEmpty()
}

@Test fun resumeAfterCancellation_picksUpFromLastProcessedId() = runTest {
    seedDownloaded(count = 5)
    sessionStore.put("release_downloads_last_processed_id", 2L)  // 1,2 already done
    worker.doWork()
    // Only ids 3,4,5 should be touched this run
    verify(exactly = 3) { fileOps.delete(any()) }
}

@Test fun cancellation_persistsLastProcessedId() = runTest {
    seedDownloaded(count = 100)
    workerCancellationAfter(rows = 30)
    worker.doWork()
    val resumeId = sessionStore.get("release_downloads_last_processed_id")
    assertThat(resumeId).isGreaterThan(0L)
}

@Test fun completion_clearsLastProcessedId() = runTest {
    seedDownloaded(count = 5)
    worker.doWork()
    assertThat(sessionStore.get("release_downloads_last_processed_id")).isNull()
}
```

- [ ] **Step 3: Implement**

```kotlin
@HiltWorker
class ReleaseDownloadsWorker @AssistedInject constructor(
    @Assisted ctx: Context,
    @Assisted params: WorkerParameters,
    private val trackDao: TrackDao,
    private val database: StashDatabase,
    private val releaseDownloadsState: ReleaseDownloadsState,  // tiny DataStore wrapper
) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val deadline = System.currentTimeMillis() + MAX_RUN_MS
        var lastId = releaseDownloadsState.lastProcessedId() ?: 0L
        while (!isStopped && System.currentTimeMillis() < deadline) {
            val batch = trackDao.downloadedAfter(lastId, BATCH_SIZE)
            if (batch.isEmpty()) break
            for (track in batch) {
                if (isStopped) {
                    releaseDownloadsState.setLastProcessedId(lastId)
                    return Result.retry()
                }
                val path = track.filePath
                // DB write FIRST so a mid-op crash doesn't leave a row pointing at a deleted file.
                database.withTransaction {
                    trackDao.markAsNotDownloaded(track.id)
                }
                if (!path.isNullOrBlank()) {
                    runCatching { File(path).delete() }   // orphan-safe if it fails
                }
                lastId = track.id
            }
            releaseDownloadsState.setLastProcessedId(lastId)
        }
        // Drained or stopped at boundary — clear the cursor only if we know we're done.
        if (trackDao.downloadedCount() == 0) {
            releaseDownloadsState.clear()
        }
        return Result.success()
    }

    companion object {
        const val WORK_NAME = "release_downloads"
        private const val BATCH_SIZE = 100
        private const val MAX_RUN_MS = 9L * 60_000

        fun enqueueSelf(ctx: Context) {
            val req = OneTimeWorkRequestBuilder<ReleaseDownloadsWorker>()
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 60, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(
                WORK_NAME, ExistingWorkPolicy.KEEP, req,
            )
        }
    }
}
```

DAO support:

```kotlin
@Query("SELECT * FROM tracks WHERE is_downloaded = 1 AND id > :lastId ORDER BY id ASC LIMIT :limit")
suspend fun downloadedAfter(lastId: Long, limit: Int): List<TrackEntity>

@Query("UPDATE tracks SET is_downloaded = 0, file_path = NULL, file_size_bytes = 0 WHERE id = :id")
suspend fun markAsNotDownloaded(id: Long)

@Query("SELECT COUNT(*) FROM tracks WHERE is_downloaded = 1")
suspend fun downloadedCount(): Int
```

`ReleaseDownloadsState` is a 5-line wrapper around a Preferences DataStore named `release_downloads_state` with one Long key. Mirror the `StashMixPreference` scaffold but with fewer keys.

- [ ] **Step 5: Commit**

```bash
git add core/data/src/main/kotlin/com/stash/core/data/sync/workers/ReleaseDownloadsWorker.kt \
        core/data/src/main/kotlin/com/stash/core/data/prefs/ReleaseDownloadsState.kt \
        core/data/src/main/kotlin/com/stash/core/data/db/dao/TrackDao.kt \
        core/data/src/test/kotlin/com/stash/core/data/sync/workers/ReleaseDownloadsWorkerTest.kt
git commit -m "feat(workers): ReleaseDownloadsWorker — used by Off→On 'release space' prompt"
```

---

### Task 15: `MusicRepository.applyStreamingMode(enabled)` — orchestrator

**Files:**
- Modify: `core/data/src/main/kotlin/com/stash/core/data/repository/MusicRepository.kt` (interface)
- Modify: `core/data/src/main/kotlin/com/stash/core/data/repository/MusicRepositoryImpl.kt`
- Test: extend existing `MusicRepositoryImplTest`

```kotlin
suspend fun applyStreamingMode(enabled: Boolean, releaseDownloads: Boolean = false, downloadAllStreamable: Boolean = false)

/**
 * Wrapper called by Subproject B's entitlement-status watcher when the user's
 * subscription lapses or is cancelled. Equivalent to flipping the toggle Off
 * with safe defaults (keep what's downloaded; don't bulk-download streamables).
 * Pinned now so Subproject B has a stable entry point.
 */
suspend fun onEntitlementLost() = applyStreamingMode(
    enabled = false,
    releaseDownloads = false,
    downloadAllStreamable = false,
)
```

If `enabled = true`:
- `streamingPreference.setEnabled(true)`
- If `releaseDownloads = true`: enqueue `ReleaseDownloadsWorker.enqueueSelf(context)`
- Schedule `AvailabilityRecheckWorker.schedulePeriodic` if not already scheduled
- Enqueue `AvailabilityCheckWorker.enqueueSelf` once (drains existing un-checked rows)

If `enabled = false`:
- `streamingPreference.setEnabled(false)`
- If `downloadAllStreamable = true`: query `trackDao.streamableOnlyTracks()` and bulk-insert into `downloadQueueDao` (same pattern as DiffWorker — one DownloadQueueEntity per track)
- Leave the periodic `AvailabilityRecheckWorker` running so cached-checked rows stay fresh for future re-enables. Cheap.

- [ ] **Step 1: Failing test**
- [ ] **Step 3: Implement**
- [ ] **Step 5: Commit**

```bash
git commit -m "feat(repo): MusicRepository.applyStreamingMode orchestrator (workers + prefs)"
```

---

### Task 16: `StreamingModeToggle` composable on HomeScreen

**Files:**
- Modify: `app/build.gradle.kts` (add `STREAMING_ENGINE_ENABLED` buildConfigField)
- Create: `feature/home/src/main/kotlin/com/stash/feature/home/streaming/StreamingModeToggle.kt`
- Modify: `feature/home/src/main/kotlin/com/stash/feature/home/HomeScreen.kt`
- Modify: `feature/home/src/main/kotlin/com/stash/feature/home/HomeViewModel.kt`

Single-row switch labeled "Online" with subtitle "Stream from your synced library". Above "Recently played". Gated on `BuildConfig.STREAMING_ENGINE_ENABLED` — hidden when false.

- [ ] **Step 0: Declare the BuildConfig field FIRST** — the composable references it; without this step Task 16 won't compile.

In `app/build.gradle.kts` inside `defaultConfig`:

```kotlin
buildConfigField("Boolean", "STREAMING_ENGINE_ENABLED", "false")
```

Run `./gradlew :app:generateDebugBuildConfig` to materialise the field, then proceed.

- [ ] **Step 1: Compose preview / unit test the composable** with both states.
- [ ] **Step 3: Implement** — mirror the existing single-row-switch pattern from `feature/settings/.../equalizer/EqualizerScreen.kt` (the Loudness Normalization card).
- [ ] **Step 5: Commit**

```bash
git add app/build.gradle.kts \
        feature/home/src/main/kotlin/com/stash/feature/home/streaming/StreamingModeToggle.kt \
        feature/home/src/main/kotlin/com/stash/feature/home/HomeScreen.kt \
        feature/home/src/main/kotlin/com/stash/feature/home/HomeViewModel.kt
git commit -m "feat(home): StreamingModeToggle — single-row switch at top of Home"
```

---

### Task 17: `StreamingModePrompt` composables

**Files:**
- Create: `feature/home/src/main/kotlin/com/stash/feature/home/streaming/StreamingModePrompt.kt`
- Test: snapshot or unit test the composable

Two `AlertDialog` variants:

`KeepOrReleaseDownloadsPrompt(downloadedCount: Int, downloadedBytes: Long, onKeep, onRelease, onDismiss)` — Off→On.

`DownloadOrStartFreshPrompt(streamableCount: Int, estimatedBytes: Long, onDownload, onStartFresh, onDismiss)` — On→Off.

- [ ] **Step 3: Implement**
- [ ] **Step 5: Commit**

```bash
git commit -m "feat(home): StreamingModePrompt — mode transition AlertDialogs"
```

---

### Task 18: Library row treatment — greyed-out for unavailable

**Files (enumerated — grep confirmed these are the only library track-row composables):**
- Modify: `feature/library/src/main/kotlin/com/stash/feature/library/LibraryScreen.kt`
- Modify: `feature/library/src/main/kotlin/com/stash/feature/library/AlbumDetailScreen.kt`
- Modify: `feature/library/src/main/kotlin/com/stash/feature/library/ArtistDetailScreen.kt`
- Modify: `feature/library/src/main/kotlin/com/stash/feature/library/PlaylistDetailScreen.kt`
- Modify: `feature/library/src/main/kotlin/com/stash/feature/library/LikedSongsDetailScreen.kt` (if it has its own row composable; otherwise reuses the LibraryScreen one)
- Test: snapshot tests for each — add an "unavailable" variant

Row renders at 50% opacity when `track.isDownloaded == false && track.isStreamable == false && track.isStreamableCheckedAt != null`. No tap action. No long-press menu items for streaming.

Implementation: each screen calls into a shared `TrackRow` composable. If a shared component already exists in `core/ui/`, modify it once; otherwise lift one out as part of this task and update each screen to consume it.

- [ ] **Step 3: Implement**
- [ ] **Step 5: Commit**

```bash
git commit -m "feat(library): grey out unavailable rows in Online mode"
```

---

### Task 19: Long-press menu — "Download for offline" / "Remove download"

**Files:**
- Modify: `core/ui/src/main/kotlin/com/stash/core/ui/components/TrackOptionsSheet.kt` (long-press menu host)
- Test: extend existing menu tests

Add two new items, conditional on the track's state and streaming mode:
- "Download for offline" — appears when `track.isDownloaded == false && track.isStreamable == true`. Tap → enqueue `TrackDownloadWorker` for the track. Optimistically render the row's small download arrow (state: "downloading").
- "Remove download" — appears when `track.isDownloaded == true`. Tap → delete file via `FileOrganizer.deleteForTrack(track)`, set `is_downloaded = 0, file_path = NULL`.

- [ ] **Step 3: Implement**
- [ ] **Step 5: Commit**

```bash
git commit -m "feat(ui): long-press menu — Download for offline / Remove download"
```

---

### Task 20: Search-tab tap-action shift in Online mode

**Files:**
- Modify: `feature/search/src/main/kotlin/com/stash/feature/search/SearchScreen.kt`
- Modify: `feature/search/src/main/kotlin/com/stash/feature/search/SearchViewModel.kt`

When `StreamingPreference.enabled = true`, a search result row's primary tap action is "stream now" — the `delegate.previewTrack(item)` already pipes preview; we extend the existing search-result row to call `PlayerRepositoryImpl.playFromStream(item)` on tap, with "Download" demoted to a long-press option.

- [ ] **Step 3: Implement**
- [ ] **Step 5: Commit**

```bash
git commit -m "feat(search): Online mode — tap streams; long-press downloads"
```

---

### Task 21: Now Playing — small wifi indicator when streaming

**Files:**
- Modify: `feature/nowplaying/src/main/kotlin/com/stash/feature/nowplaying/NowPlayingScreen.kt`
- Modify: `feature/nowplaying/src/main/kotlin/com/stash/feature/nowplaying/NowPlayingViewModel.kt`

Surface `isStreaming: Boolean` on `NowPlayingUiState` (true when the current MediaItem's URI scheme is http/https). Render a small wifi icon prefixing the existing quality line. Mini-player stays untouched.

- [ ] **Step 3: Implement**
- [ ] **Step 5: Commit**

```bash
git commit -m "feat(nowplaying): wifi indicator when current track is streaming"
```

---

### Task 22: `StashApplication.onCreate` — wire startup workers

**Files:**
- Modify: `app/src/main/kotlin/com/stash/app/StashApplication.kt`

The `STREAMING_ENGINE_ENABLED` BuildConfig flag was declared in Task 16's Step 0 and is already wired up. This task only adds the startup-time worker scheduling.

- [ ] **Step 1: Wire startup**

In `StashApplication.onCreate`, after the existing worker scheduling:

```kotlin
if (BuildConfig.STREAMING_ENGINE_ENABLED) {
    applicationScope.launch {
        // First-launch-after-upgrade: drain existing un-checked rows
        if (trackDao.tracksNeedingStreamableCheckCount() > 0) {
            AvailabilityCheckWorker.enqueueSelf(this@StashApplication)
        }
        AvailabilityRecheckWorker.schedulePeriodic(this@StashApplication)
    }
}
```

`StreamingModeToggle` already checks `BuildConfig.STREAMING_ENGINE_ENABLED` and renders nothing when false (Task 16).

- [ ] **Step 5: Commit**

```bash
git commit -m "feat(app): gate streaming engine behind STREAMING_ENGINE_ENABLED build flag"
```

---

### Task 23: End-to-end manual validation + flip the flag

**Files:**
- Modify: `app/build.gradle.kts` (flip the flag for dogfood builds)
- Manual on-device testing

- [ ] **Step 1: Flip BuildConfig flag to true for local builds**

```kotlin
buildConfigField("Boolean", "STREAMING_ENGINE_ENABLED", "true")
```

- [ ] **Step 2: Install + manual sweep**

```bash
./gradlew :app:installDebug
```

On device, validate each path:

1. **Default state**: Home tab shows the "Online" switch at the top, defaulted OFF. Library shows downloaded tracks only (today's behavior).
2. **Toggle ON**: Prompt appears asking "keep / release downloads." Pick Keep → toggle flips on, no downloads deleted, library still shows downloaded content.
3. **Sync in Online mode**: trigger a sync (Spotify or YT Music). New tracks land as metadata only (`is_downloaded = 0`). `AvailabilityCheckWorker` populates `is_streamable` over the next few minutes. Library count grows accordingly.
4. **Tap-to-stream**: tap a streamable-only row. Track plays via the Qobuz CDN URL. Now Playing shows the wifi indicator + FLAC quality line.
5. **Pause-resume hours later** *(this is the hardest one to verify)*: play a streamed track, pause, wait 12+ hours, resume. Verify playback continues without an error (`RefreshingDataSourceFactory` should re-resolve transparently).
6. **Pre-fetch**: play a streamable track, let it reach >60%, queue continues — next track auto-advance should feel ~instant rather than 300-800ms of silence.
7. **Cellular gate**: turn WiFi off (cellular only). Try to stream — snackbar appears, playback doesn't start.
8. **Unavailable row**: find a synced track Kennyy doesn't have (a leak / niche release). After `is_streamable_checked_at` populates, the row renders greyed-out at 50% opacity. No tap action.
9. **Long-press download**: long-press a streamable row → "Download for offline." Confirm. Track downloads in background; row gains the download arrow on completion.
10. **Long-press remove**: long-press a downloaded row → "Remove download." File deleted; row remains visible (streamable-only state) if streaming is on.
11. **Toggle OFF**: prompt appears "download X tracks or start fresh." Pick Start Fresh. Streamable-only rows become un-tappable (no streaming, no local).
12. **Toggle ON again, pick Download All**: `TrackDownloadWorker` enqueues for every streamable-only row. Watch download progress in the existing UI.

- [ ] **Step 3: If everything passes, commit the flag flip** (or revert to false if dogfooding only):

```bash
git add app/build.gradle.kts
git commit -m "chore(streaming): enable STREAMING_ENGINE_ENABLED for dogfood builds"
```

---

## Validation gates (before merging)

1. `./gradlew test` — every module's unit tests pass.
2. `./gradlew :app:compileDebugKotlin` and `:app:installDebug` — clean install.
3. Manual on-device sweep (Task 23 Step 2) — all 12 scenarios verified.
4. Pause-resume-hours-later (#5) is the highest-risk scenario; verify before claiming done.
5. No regression in existing playback paths (search-tab tap-to-download still works in Offline mode; sync downloads still happen in Offline mode).

## Out of scope (deferred to later releases)

See spec § Non-Goals. Highest-impact omissions worth tracking:

- **Subscription paywall (Subproject B)** — until B lands, the toggle is freely flippable via `STREAMING_ENGINE_ENABLED`. Subproject B's first task will be flipping the gating from build-flag to entitlement-check.
- **Server-side entitlement backend (Subproject C)** — same.
- **YouTube fallback when Kennyy lacks a track** — unavailable rows stay greyed in v1.
- **Self-hosted Qobuz proxy** — eventually replaces the third-party Kennyy dependency.

## Skills referenced

- @superpowers:test-driven-development — followed throughout
- @superpowers:verification-before-completion — Task 23 manual sweep is the gate
- @superpowers:requesting-code-review — invoke after Task 23 completes, before merging
