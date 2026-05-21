# NewPipe Extractor Spike Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add NewPipe Extractor as a third (measurement-instrumented) arm of `PreviewUrlExtractor`'s race so we can evaluate on-device whether it can replace yt-dlp + QuickJS as the primary cipher-solving extractor.

**Architecture:** A new Hilt-singleton `NewPipeStreamExtractor` (with an OkHttp-backed `Downloader` adapter) is slotted between `InnerTube` and `yt-dlp` in `PreviewUrlExtractor.race()`. Race semantics stay sequential-preference: InnerTube → NewPipe → yt-dlp. A new LATDIAG `newpipe-end` log line plus a `winner` field on `extract-end` lets us read latency + success rate off `adb logcat`. yt-dlp is unchanged and remains the correctness backstop.

**Tech Stack:** Kotlin, Coroutines (`runTest` + `advanceTimeBy` for tests), Hilt DI, OkHttp (shared via `NetworkModule`), JUnit4, NewPipeExtractor 0.24.6 via JitPack.

**Spec:** `docs/superpowers/specs/2026-05-21-newpipe-extractor-spike-design.md`

**Branch:** `feat/newpipe-extractor-spike` (cut from `feat/yt-fallback-resolver`)

---

## File Structure

**Create:**
- `data/download/src/main/kotlin/com/stash/data/download/preview/OkHttpNewPipeDownloader.kt` — bridges NewPipe's `Downloader` interface over the shared `OkHttpClient` from `core/network`. ~30 lines, no branching.
- `data/download/src/main/kotlin/com/stash/data/download/preview/NewPipeStreamExtractor.kt` — Hilt singleton; lazy `NewPipe.init()`; `extractStreamUrl(videoId): String?` with timeout + rescue; `internal fun pickBestAudio(...)` helper for unit-testing format selection without touching NewPipe statics.
- `data/download/src/test/kotlin/com/stash/data/download/preview/NewPipeStreamExtractorTest.kt` — JVM unit tests for `pickBestAudio` and `extractStreamUrl`'s null/timeout/cancellation behavior using a fake `OkHttpNewPipeDownloader`.

**Modify:**
- `gradle/libs.versions.toml` — new version + library alias for NewPipe.
- `settings.gradle.kts` — add JitPack repository.
- `data/download/build.gradle.kts` — add `implementation(libs.newpipe.extractor)`.
- `data/download/src/main/kotlin/com/stash/data/download/preview/PreviewUrlExtractor.kt` — extend `race()` to three arms (return `Pair<url, winner>`), new `NEWPIPE_TIMEOUT_MS` + `NEWPIPE_CONCURRENCY` constants, new `newPipeSemaphore`, new constructor param, new `newpipe-end` LATDIAG, new `winner` field on `extract-end`, factor out `rescueNull` helper.
- `data/download/src/test/kotlin/com/stash/data/download/preview/PreviewUrlExtractorTest.kt` — extend `TestableExtractor` and existing tests to pass a `null`-returning NewPipe arm; add four new tests covering the three-way race semantics.

---

## Pre-flight (do once before starting)

**Branch setup.** Before Task 1, create the spike branch:

```bash
cd C:/Users/theno/Projects/MP3APK
git checkout feat/yt-fallback-resolver
git pull --ff-only origin feat/yt-fallback-resolver  # if applicable
git checkout -b feat/newpipe-extractor-spike
```

If working in a fresh worktree, remember to copy `local.properties` into the worktree root — otherwise Last.fm and other env-driven paths report "Not configured" in debug builds (see `feedback_worktree_local_properties.md` in memory).

---

## Task 1: Add NewPipe dependency

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `settings.gradle.kts`
- Modify: `data/download/build.gradle.kts`

NewPipe Extractor isn't on Maven Central — it ships through JitPack. We add the repo to `dependencyResolutionManagement` (project-wide, since other modules may want NewPipe later) and declare the library via the existing `libs.versions.toml` pattern so future bumps are one-line.

- [ ] **Step 1: Add version + library alias to `gradle/libs.versions.toml`**

In the `[versions]` block, add a line near `youtubedlAndroid`:

```toml
newpipeExtractor = "0.24.6"
```

In the `[libraries]` block, add near the `# yt-dlp` section:

```toml
# NewPipe Extractor — feasibility spike for replacing yt-dlp cipher solving
newpipe-extractor = { group = "com.github.TeamNewPipe", name = "NewPipeExtractor", version.ref = "newpipeExtractor" }
```

- [ ] **Step 2: Add JitPack repository to `settings.gradle.kts`**

In the `dependencyResolutionManagement { repositories { ... } }` block, append after `mavenCentral()`:

```kotlin
maven("https://jitpack.io") {
    content {
        // Only resolve TeamNewPipe artifacts via JitPack to avoid
        // expanding the supply-chain surface unnecessarily.
        includeGroup("com.github.TeamNewPipe")
    }
}
```

The `content { includeGroup }` filter keeps JitPack from being consulted for unrelated artifacts — important hygiene given the project's existing `RepositoriesMode.FAIL_ON_PROJECT_REPOS` posture.

- [ ] **Step 3: Add the dependency in `data/download/build.gradle.kts`**

In the `dependencies { }` block, after the `youtubedl-*` lines, add:

```kotlin
// NewPipe Extractor — third arm of PreviewUrlExtractor's race; see
// docs/superpowers/specs/2026-05-21-newpipe-extractor-spike-design.md
implementation(libs.newpipe.extractor)
```

- [ ] **Step 4: Verify the dependency resolves**

Run: `./gradlew :data:download:dependencies --configuration releaseRuntimeClasspath | grep -i newpipe`
Expected output: a line like `+--- com.github.TeamNewPipe:NewPipeExtractor:0.24.6` (or whatever version was pinned). If the build fails with "Could not resolve com.github.TeamNewPipe:NewPipeExtractor," JitPack hasn't propagated the artifact yet — fall back to the most recent published tag on the JitPack page for the repo.

- [ ] **Step 5: Verify the module still compiles**

Run: `./gradlew :data:download:compileDebugKotlin`
Expected: BUILD SUCCESSFUL. No source changes yet, just confirming the dep wires cleanly.

- [ ] **Step 6: Commit**

```bash
git add gradle/libs.versions.toml settings.gradle.kts data/download/build.gradle.kts
git commit -m "build: add NewPipeExtractor dependency (spike)"
```

---

## Task 2: Create `OkHttpNewPipeDownloader`

**Files:**
- Create: `data/download/src/main/kotlin/com/stash/data/download/preview/OkHttpNewPipeDownloader.kt`

Thin adapter — NewPipe ships an abstract `Downloader` with one method. We implement it on top of the shared `OkHttpClient` so we inherit timeouts, the connection pool, and TLS config from `core/network`. Per the spec, no dedicated unit test — behavior is covered transitively by `NewPipeStreamExtractorTest`.

- [ ] **Step 1: Create the file**

```kotlin
package com.stash.data.download.preview

import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridges NewPipe Extractor's [Downloader] interface onto the shared
 * application [OkHttpClient]. Re-using the shared client inherits TLS
 * config, timeouts, and the connection pool used by every other HTTP
 * surface in the app — no second network stack.
 *
 * Holds no per-request state; safe to share as a `@Singleton`.
 */
@Singleton
class OkHttpNewPipeDownloader @Inject constructor(
    private val client: OkHttpClient,
) : Downloader() {

    override fun execute(request: Request): Response {
        val body = request.dataToSend()?.toRequestBody(null)
        val httpReq = okhttp3.Request.Builder()
            .url(request.url())
            .method(request.httpMethod(), body)
            .apply {
                request.headers().forEach { (name, values) ->
                    values.forEach { addHeader(name, it) }
                }
            }
            .build()
        client.newCall(httpReq).execute().use { resp ->
            // resp.body.string() consumes the body — fine here because
            // NewPipe expects a fully-materialised String anyway, and
            // we never retry a request through this adapter.
            return Response(
                resp.code,
                resp.message,
                resp.headers.toMultimap(),
                resp.body?.string(),
                resp.request.url.toString(),
            )
        }
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :data:download:compileDebugKotlin`
Expected: BUILD SUCCESSFUL. If imports for `org.schabi.newpipe.extractor.downloader.*` fail, the dependency from Task 1 didn't resolve — re-check `:data:download:dependencies`.

- [ ] **Step 3: Commit**

```bash
git add data/download/src/main/kotlin/com/stash/data/download/preview/OkHttpNewPipeDownloader.kt
git commit -m "feat(preview): OkHttp adapter for NewPipe's Downloader (spike)"
```

---

## Task 3: `NewPipeStreamExtractor.pickBestAudio` (TDD)

**Files:**
- Create: `data/download/src/main/kotlin/com/stash/data/download/preview/NewPipeStreamExtractor.kt` (skeleton + helper only)
- Create: `data/download/src/test/kotlin/com/stash/data/download/preview/NewPipeStreamExtractorTest.kt`

We extract the format-selection logic to a pure, internal helper so it can be exercised by a JVM unit test without booting NewPipe's static `init()` or hitting MockWebServer. This is the spec's mitigation for "the spike unknown" — keep the riskiest test surface (NewPipe's static init in JVM tests) out of the critical path by isolating what we can test cleanly.

- [ ] **Step 1: Write the failing test in `NewPipeStreamExtractorTest.kt`**

```kotlin
package com.stash.data.download.preview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.schabi.newpipe.extractor.stream.AudioStream

/**
 * Unit tests for [NewPipeStreamExtractor]'s pure helpers and its
 * suspend-fun behavioural contract.
 *
 * `pickBestAudio` is exercised in isolation so we don't have to boot
 * NewPipe's static `init()` (which expects a real Downloader). The
 * `extractStreamUrl` cases use a fake [OkHttpNewPipeDownloader] /
 * forced exceptions to drive the null / timeout / cancellation
 * paths. Real NewPipe parsing is not under test here — the spike's
 * answer comes from on-device LATDIAG, not from JVM round-trips.
 */
class NewPipeStreamExtractorTest {

    @Test
    fun `pickBestAudio returns highest averageBitrate URL`() {
        val streams = listOf(
            fakeAudioStream(url = "https://a/low",  averageBitrate = 64_000,  bitrate = 64_000),
            fakeAudioStream(url = "https://a/mid",  averageBitrate = 128_000, bitrate = 128_000),
            fakeAudioStream(url = "https://a/high", averageBitrate = 256_000, bitrate = 256_000),
        )
        assertEquals("https://a/high", NewPipeStreamExtractor.pickBestAudio(streams))
    }

    @Test
    fun `pickBestAudio falls back to bitrate when averageBitrate is 0`() {
        // Some YouTube formats don't report averageBitrate but do report
        // a peak bitrate. We use bitrate as the tiebreaker rather than
        // sorting the 0-averageBitrate entries to the bottom.
        val streams = listOf(
            fakeAudioStream(url = "https://a/avgKnown", averageBitrate = 128_000, bitrate = 128_000),
            fakeAudioStream(url = "https://a/peakOnly", averageBitrate = 0,       bitrate = 192_000),
        )
        assertEquals("https://a/peakOnly", NewPipeStreamExtractor.pickBestAudio(streams))
    }

    @Test
    fun `pickBestAudio returns null on empty list`() {
        assertNull(NewPipeStreamExtractor.pickBestAudio(emptyList()))
    }

    /**
     * Helper that constructs a minimal [AudioStream] suitable for the
     * pure-function tests. NewPipe's constructors are not friendly to
     * test fixtures, so we use the public builder pattern available
     * since 0.22.x.
     */
    private fun fakeAudioStream(url: String, averageBitrate: Int, bitrate: Int): AudioStream {
        // Adjust the constructor signature if NewPipe's AudioStream API
        // differs at the resolved version. The public AudioStream.Builder
        // has been stable since 0.22; if `setContent` etc. don't compile,
        // check the resolved AAR's `AudioStream` source.
        return AudioStream.Builder()
            .setId("test")
            .setContent(url, true)
            .setMediaFormat(org.schabi.newpipe.extractor.MediaFormat.M4A)
            .setAverageBitrate(averageBitrate)
            .setBitrate(bitrate)
            .build()
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :data:download:testDebugUnitTest --tests "com.stash.data.download.preview.NewPipeStreamExtractorTest"`
Expected: COMPILATION FAIL — `NewPipeStreamExtractor` doesn't exist. (Or if you wrote the skeleton file first, FAIL with "pickBestAudio not found".)

If the `AudioStream.Builder` API doesn't match (NewPipe occasionally tweaks setters), find the actual builder signature with: `./gradlew :data:download:dependencies` → locate the NewPipeExtractor AAR in `~/.gradle/caches/...` → unzip and inspect `AudioStream.class`. Adjust `fakeAudioStream` accordingly. **Do not** patch the test to "make it compile" by mocking past the failure — the spec depends on `pickBestAudio` being driven against real `AudioStream` instances so the format-selection contract isn't faked away.

- [ ] **Step 3: Write the minimal skeleton in `NewPipeStreamExtractor.kt`**

```kotlin
package com.stash.data.download.preview

import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.stream.AudioStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves a direct audio stream URL for a YouTube video id using NewPipe
 * Extractor. Slotted into [PreviewUrlExtractor]'s race as the middle arm
 * between InnerTube (fast path, often null for restricted music) and
 * yt-dlp (slow correctness backstop).
 *
 * Behaviour: returns the highest-bitrate audio stream URL on success, or
 * null on any failure (extractor throw, parse error, timeout). Cancellation
 * is propagated, not swallowed — required so the race's structured
 * concurrency can tear down the in-flight call when InnerTube wins.
 *
 * NewPipe's static `NewPipe.init()` is called lazily on the first
 * extraction. Subsequent calls hit a `@Volatile` fast path.
 *
 * Skeleton-only at this task — full `extractStreamUrl` lands in Task 4.
 */
@Singleton
class NewPipeStreamExtractor @Inject constructor(
    @Suppress("unused") private val downloader: OkHttpNewPipeDownloader,
) {

    companion object {
        /**
         * Picks the audio stream URL with the highest reported bitrate.
         *
         * Sort key prefers `averageBitrate` when reported (>0); otherwise
         * falls back to `bitrate` (peak). Some YouTube formats omit
         * averageBitrate, so naively sorting on averageBitrate would
         * push them to the bottom even when they're actually higher
         * quality than a low-but-reported alternative.
         */
        internal fun pickBestAudio(streams: List<AudioStream>): String? =
            streams
                .maxByOrNull { it.averageBitrate.takeIf { b -> b > 0 } ?: it.bitrate }
                ?.content
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :data:download:testDebugUnitTest --tests "com.stash.data.download.preview.NewPipeStreamExtractorTest"`
Expected: PASS — 3 tests succeed.

- [ ] **Step 5: Commit**

```bash
git add data/download/src/main/kotlin/com/stash/data/download/preview/NewPipeStreamExtractor.kt \
        data/download/src/test/kotlin/com/stash/data/download/preview/NewPipeStreamExtractorTest.kt
git commit -m "feat(preview): NewPipeStreamExtractor skeleton + pickBestAudio (spike)"
```

---

## Task 4: `NewPipeStreamExtractor.extractStreamUrl` — null/timeout/cancellation behaviour (TDD)

**Files:**
- Modify: `data/download/src/main/kotlin/com/stash/data/download/preview/NewPipeStreamExtractor.kt`
- Modify: `data/download/src/test/kotlin/com/stash/data/download/preview/NewPipeStreamExtractorTest.kt`

We can't easily drive a full happy-path round-trip in JVM tests (would require mocking NewPipe's static service registry), so we test the *behavioural contract* — that the function returns null on any extractor failure, returns null on timeout, and rethrows `CancellationException` rather than swallowing it. Real round-trips are evaluated on-device via LATDIAG in Task 8.

The trick is to inject a `fetcher` lambda that the test can replace, so we don't have to drive `StreamInfo.getInfo` itself — that surface is NewPipe-internal and changes between versions.

- [ ] **Step 1: Write the failing tests for extractStreamUrl behaviour**

Append to `NewPipeStreamExtractorTest.kt`:

```kotlin
    // ----- extractStreamUrl behavioural contract -----

    @Test
    fun `extractStreamUrl returns null when fetcher throws`() = kotlinx.coroutines.test.runTest {
        val sut = NewPipeStreamExtractor(
            downloader = fakeDownloader(),
            fetcher = { throw java.io.IOException("simulated NewPipe failure") },
        )
        assertNull(sut.extractStreamUrl("anyVideoId"))
    }

    @Test
    fun `extractStreamUrl returns null when fetcher returns empty stream list`() = kotlinx.coroutines.test.runTest {
        val sut = NewPipeStreamExtractor(
            downloader = fakeDownloader(),
            fetcher = { emptyList() },
        )
        assertNull(sut.extractStreamUrl("anyVideoId"))
    }

    @Test
    fun `extractStreamUrl returns highest-bitrate URL on success`() = kotlinx.coroutines.test.runTest {
        val streams = listOf(
            fakeAudioStream(url = "https://x/low",  averageBitrate = 64_000,  bitrate = 64_000),
            fakeAudioStream(url = "https://x/high", averageBitrate = 256_000, bitrate = 256_000),
        )
        val sut = NewPipeStreamExtractor(
            downloader = fakeDownloader(),
            fetcher = { streams },
        )
        assertEquals("https://x/high", sut.extractStreamUrl("anyVideoId"))
    }

    @Test
    fun `extractStreamUrl returns null on timeout`() = kotlinx.coroutines.test.runTest {
        val sut = NewPipeStreamExtractor(
            downloader = fakeDownloader(),
            // Stretches well past NEWPIPE_TIMEOUT_MS. `runTest`'s virtual
            // time skips the wall-clock wait so the test still runs fast.
            fetcher = { kotlinx.coroutines.delay(60_000); emptyList() },
        )
        assertNull(sut.extractStreamUrl("anyVideoId"))
    }

    @Test
    fun `extractStreamUrl propagates cancellation`() = kotlinx.coroutines.test.runTest {
        // Use supervisorScope so a child cancellation doesn't propagate up
        // and fail the test harness. We want to observe that the suspend
        // call ends in cancellation, not normal completion (which would
        // mean extractStreamUrl swallowed CancellationException).
        kotlinx.coroutines.supervisorScope {
            val sut = NewPipeStreamExtractor(
                downloader = fakeDownloader(),
                // Never returns — only outer cancellation should end this call.
                fetcher = { kotlinx.coroutines.delay(Long.MAX_VALUE); emptyList() },
            )
            val completed = java.util.concurrent.atomic.AtomicBoolean(false)
            val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
            val job = kotlinx.coroutines.launch {
                try {
                    sut.extractStreamUrl("anyVideoId")
                    completed.set(true)
                } catch (ce: kotlinx.coroutines.CancellationException) {
                    cancelled.set(true)
                }
            }
            // Yield once so the inner withTimeout/withContext registers.
            kotlinx.coroutines.test.runCurrent()
            job.cancel(kotlinx.coroutines.CancellationException("outer cancel"))
            job.join()
            assertTrue("extractStreamUrl must propagate cancellation, not return null", cancelled.get())
            org.junit.Assert.assertFalse(completed.get())
        }
    }

    private fun fakeDownloader(): OkHttpNewPipeDownloader =
        OkHttpNewPipeDownloader(client = okhttp3.OkHttpClient())
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :data:download:testDebugUnitTest --tests "com.stash.data.download.preview.NewPipeStreamExtractorTest"`
Expected: COMPILATION FAIL — the constructor doesn't have a `fetcher` parameter, and there's no `extractStreamUrl` method. (Or, if Step 3 was started first, runtime FAILs from missing method.)

- [ ] **Step 3: Extend the class with an injectable fetcher + extractStreamUrl**

Replace the body of `NewPipeStreamExtractor.kt` with:

```kotlin
package com.stash.data.download.preview

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamInfo
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves a direct audio stream URL for a YouTube video id using NewPipe
 * Extractor. Slotted into [PreviewUrlExtractor]'s race as the middle arm
 * between InnerTube (fast path, often null for restricted music) and
 * yt-dlp (slow correctness backstop).
 *
 * Behaviour: returns the highest-bitrate audio stream URL on success, or
 * null on any failure (extractor throw, parse error, timeout). Cancellation
 * is propagated, not swallowed — required so the race's structured
 * concurrency can tear down the in-flight call when InnerTube wins.
 *
 * NewPipe's static `NewPipe.init()` is called lazily on the first
 * extraction. Subsequent calls hit a `@Volatile` fast path.
 */
@Singleton
class NewPipeStreamExtractor @Inject constructor(
    private val downloader: OkHttpNewPipeDownloader,
    /**
     * Test seam — production code resolves audio streams via
     * [StreamInfo.getInfo]. Tests inject a lambda so they can exercise
     * the surrounding contract (timeout, rescue, cancellation, format
     * selection) without standing up NewPipe's static service registry.
     *
     * Not annotated `@Inject` — production wiring uses the default.
     */
    internal val fetcher: suspend (String) -> List<AudioStream> = defaultFetcher,
) {

    @Volatile private var initialized = false
    private val initLock = Any()

    /**
     * One-time NewPipe service registration. Cheap (registers the YouTube
     * service in a static map; no network). Double-checked-locking keeps
     * the hot path lock-free.
     */
    private fun ensureInitialized() {
        if (initialized) return
        synchronized(initLock) {
            if (initialized) return
            NewPipe.init(downloader, Localization("en", "US"))
            initialized = true
        }
    }

    /**
     * Resolves the highest-bitrate audio stream URL for [videoId].
     *
     * Bounded by [NEWPIPE_TIMEOUT_MS]. Any non-cancellation throw inside
     * the fetcher (network IO, NewPipe parse failure, missing format) is
     * rescued as null — the race in [PreviewUrlExtractor] then transparently
     * falls through to yt-dlp. [CancellationException] is rethrown so the
     * structured-concurrency teardown still works when InnerTube wins.
     */
    suspend fun extractStreamUrl(videoId: String): String? = withContext(Dispatchers.IO) {
        ensureInitialized()
        try {
            withTimeout(NEWPIPE_TIMEOUT_MS) {
                pickBestAudio(fetcher(videoId))
            }
        } catch (ce: CancellationException) {
            // TimeoutCancellationException is a subtype of CancellationException
            // but we treat it as a recoverable null rather than re-throwing —
            // the race must transparently fall through to yt-dlp on timeout,
            // not propagate the cancellation outward.
            if (ce is TimeoutCancellationException) {
                Log.w(TAG, "NewPipe extract timeout for $videoId after ${NEWPIPE_TIMEOUT_MS}ms")
                null
            } else {
                throw ce
            }
        } catch (t: Throwable) {
            Log.w(TAG, "NewPipe extract failed for $videoId: ${t.javaClass.simpleName} ${t.message}")
            null
        }
    }

    companion object {
        private const val TAG = "NewPipeStreamExtractor"
        const val NEWPIPE_TIMEOUT_MS = 15_000L

        /** Default production fetcher — calls real NewPipe. */
        private val defaultFetcher: suspend (String) -> List<AudioStream> = { videoId ->
            val url = "https://www.youtube.com/watch?v=$videoId"
            StreamInfo.getInfo(ServiceList.YouTube, url).audioStreams
        }

        /**
         * Picks the audio stream URL with the highest reported bitrate.
         *
         * Sort key prefers `averageBitrate` when reported (>0); otherwise
         * falls back to `bitrate` (peak). Some YouTube formats omit
         * averageBitrate, so naively sorting on averageBitrate would push
         * them to the bottom even when they're actually higher quality
         * than a low-but-reported alternative.
         */
        internal fun pickBestAudio(streams: List<AudioStream>): String? =
            streams
                .maxByOrNull { it.averageBitrate.takeIf { b -> b > 0 } ?: it.bitrate }
                ?.content
    }
}
```

Note the two non-obvious choices documented inline:
- The `fetcher` is `internal val` (not `@Inject`), so production wiring sees the default and tests can pass a lambda. Hilt only injects the `downloader` arg.
- `TimeoutCancellationException` is caught specifically and converted to null. The race contract requires NewPipe-timeout to be a *rescue* (fall through to yt-dlp), not a *propagate* (cancel the whole race).

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :data:download:testDebugUnitTest --tests "com.stash.data.download.preview.NewPipeStreamExtractorTest"`
Expected: PASS — 8 tests (3 from Task 3 + 5 new).

If the timeout test fails because virtual time isn't being advanced by the `delay(60_000)`, double-check the test uses `runTest { }` and not `runBlocking { }` — only `runTest` provides the virtual-time scheduler that skips real waiting.

- [ ] **Step 5: Commit**

```bash
git add data/download/src/main/kotlin/com/stash/data/download/preview/NewPipeStreamExtractor.kt \
        data/download/src/test/kotlin/com/stash/data/download/preview/NewPipeStreamExtractorTest.kt
git commit -m "feat(preview): NewPipeStreamExtractor.extractStreamUrl with rescue (spike)"
```

---

## Task 5: Extend `TestHooks` SPI + `raceForTest` return type

**Files:**
- Modify: `data/download/src/main/kotlin/com/stash/data/download/preview/PreviewUrlExtractor.kt` (TestHooks + raceForTest signature)
- Modify: `data/download/src/test/kotlin/com/stash/data/download/preview/PreviewUrlExtractorTest.kt` (TestableExtractor + existing tests)

Before extending the race itself we widen the test contract. `TestHooks` gains a NewPipe extract method; `raceForTest` returns `Pair<String, String>` (url + winner). Existing tests get migrated to pass a null NewPipe arm and read `.first` for the url. Everything must still compile and pass after this task — no behaviour change yet, just a wider SPI.

- [ ] **Step 1: Extend the `TestHooks` interface and `raceForTest`**

In `PreviewUrlExtractor.kt`, update lines 66-99 (the `TestHooks` interface + `raceForTest`):

```kotlin
    /** Test-only injection point for race logic. Not wired in production. */
    internal interface TestHooks {
        suspend fun innerTubeExtract(id: String): String?
        suspend fun newPipeExtract(id: String): String?   // NEW
        suspend fun ytDlpExtract(id: String): String
    }

    companion object {
        // ... existing TAG / timeouts / FORMAT_SELECTOR ...

        /** Concurrency caps for the three extractors. Shared process-wide. */
        private const val INNERTUBE_CONCURRENCY = 8
        private const val NEWPIPE_CONCURRENCY = 4   // NEW
        private const val YTDLP_CONCURRENCY = 2

        private val innerTubeSemaphore = Semaphore(INNERTUBE_CONCURRENCY)
        private val newPipeSemaphore = Semaphore(NEWPIPE_CONCURRENCY)   // NEW
        private val ytDlpSemaphore = Semaphore(YTDLP_CONCURRENCY)

        /**
         * Test-only: exercises [race] directly without Android deps. Reuses
         * the shared semaphores so the tests also assert the real caps.
         *
         * Returns `(url, winner)` so the winner-field assertion in
         * [PreviewUrlExtractorTest] can verify the race stamped the right
         * arm name for LATDIAG.
         */
        internal suspend fun raceForTest(hooks: TestHooks, videoId: String): Pair<String, String> =
            race(
                videoId = videoId,
                innerTubeExtract = hooks::innerTubeExtract,
                newPipeExtract  = hooks::newPipeExtract,
                ytDlpExtract    = hooks::ytDlpExtract,
                itSem = innerTubeSemaphore,
                npSem = newPipeSemaphore,
                ytSem = ytDlpSemaphore,
            )

        // race() body lands in Task 6 — for now leave a placeholder so the
        // file still compiles. (See Step 3 below.)
```

- [ ] **Step 2: Provide a temporary 3-arm `race` that ignores NewPipe**

Inside the same companion object, replace the existing `race(...)` body (lines ~117-150) with a placeholder that has the new signature but ignores the new arm so behaviour is unchanged for now:

```kotlin
        /**
         * Placeholder during Task 5 — accepts the new 3-arm signature but
         * still races only InnerTube vs yt-dlp. The actual three-way logic
         * lands in Task 6. Keeping this step behaviour-preserving lets us
         * commit the SPI widening on its own.
         */
        private suspend fun race(
            videoId: String,
            innerTubeExtract: suspend (String) -> String?,
            @Suppress("UNUSED_PARAMETER") newPipeExtract: suspend (String) -> String?,
            ytDlpExtract: suspend (String) -> String,
            itSem: Semaphore,
            @Suppress("UNUSED_PARAMETER") npSem: Semaphore,
            ytSem: Semaphore,
        ): Pair<String, String> = coroutineScope {
            val inner = async {
                itSem.acquire()
                try {
                    runCatching { innerTubeExtract(videoId) }
                        .getOrElse { t ->
                            if (t is CancellationException) throw t
                            null
                        }
                } finally { itSem.release() }
            }
            val yt = async {
                ytSem.acquire()
                try { ytDlpExtract(videoId) } finally { ytSem.release() }
            }
            val itResult = inner.await()
            if (itResult != null) {
                yt.cancel(CancellationException("InnerTube won the race"))
                itResult to "innertube"
            } else {
                yt.await() to "ytdlp"
            }
        }
```

- [ ] **Step 3: Update `extractStreamUrl` to handle the Pair return**

In `extractStreamUrl` (lines ~159-196), change the call to `race(...)`:

```kotlin
            val (url, _winner) = race(
                videoId = videoId,
                innerTubeExtract = { id ->
                    // ... existing InnerTube wrapper ...
                },
                newPipeExtract = { _ -> null },   // NEW — placeholder until Task 7
                ytDlpExtract = { id ->
                    // ... existing yt-dlp wrapper ...
                },
                itSem = innerTubeSemaphore,
                npSem = newPipeSemaphore,
                ytSem = ytDlpSemaphore,
            )
            Log.d("LATDIAG", "extract-end videoId=$videoId dt=${System.currentTimeMillis() - t0}ms")
            url
```

(The `winner` field on `extract-end` LATDIAG lands in Task 7 — for now we discard it to keep this task's diff minimal.)

- [ ] **Step 4: Migrate `TestableExtractor` in `PreviewUrlExtractorTest.kt`**

Replace lines 32-39:

```kotlin
    /** Test-double: adapts three lambdas into the [PreviewUrlExtractor.TestHooks] SPI. */
    private class TestableExtractor(
        val innertube: suspend (String) -> String?,
        val newpipe: suspend (String) -> String? = { null },
        val ytdlp: suspend (String) -> String,
    ) : PreviewUrlExtractor.TestHooks {
        override suspend fun innerTubeExtract(id: String) = innertube(id)
        override suspend fun newPipeExtract(id: String) = newpipe(id)
        override suspend fun ytDlpExtract(id: String) = ytdlp(id)
    }
```

Default `newpipe = { null }` preserves the existing 2-arm semantics for tests that don't care about NewPipe.

- [ ] **Step 5: Update existing test call sites for the Pair return**

Six existing tests call `PreviewUrlExtractor.raceForTest(hooks, "abc")` and treat the result as a `String`. Update each to:

```kotlin
        val (url, _winner) = PreviewUrlExtractor.raceForTest(hooks, "abc")
        assertEquals("https://fast/abc", url)
```

For the semaphore-cap tests (`innertube semaphore caps...`, `ytdlp semaphore caps...`), the calls inside the `async { ... }` blocks just need to discard the winner — `raceForTest(hooks, "id$it").first` suffices.

- [ ] **Step 6: Run all existing tests to verify they still pass**

Run: `./gradlew :data:download:testDebugUnitTest --tests "com.stash.data.download.preview.PreviewUrlExtractorTest"`
Expected: PASS — 6 existing tests still green. No behaviour change.

- [ ] **Step 7: Commit**

```bash
git add data/download/src/main/kotlin/com/stash/data/download/preview/PreviewUrlExtractor.kt \
        data/download/src/test/kotlin/com/stash/data/download/preview/PreviewUrlExtractorTest.kt
git commit -m "refactor(preview): widen TestHooks + raceForTest for 3-arm race (no behaviour change)"
```

---

## Task 6: Three-way `race()` — full semantics (TDD)

**Files:**
- Modify: `data/download/src/main/kotlin/com/stash/data/download/preview/PreviewUrlExtractor.kt` (race body + rescueNull helper)
- Modify: `data/download/src/test/kotlin/com/stash/data/download/preview/PreviewUrlExtractorTest.kt` (4 new tests)

Now we replace the placeholder body with the real three-arm race. Write the tests first; they'll fail against the placeholder. Then write the body; tests pass.

- [ ] **Step 1: Write the four new failing tests**

Append to `PreviewUrlExtractorTest.kt`:

```kotlin
    // ----- Three-way race semantics (Task 6) -----

    @Test
    fun `race returns newpipe URL when innertube returns null and newpipe wins`() = runTest {
        val hooks = TestableExtractor(
            innertube = { null },
            newpipe = { "https://newpipe/$it" },
            ytdlp = { delay(5_000); "https://ytdlp/$it" },
        )
        val (url, winner) = PreviewUrlExtractor.raceForTest(hooks, "abc")
        assertEquals("https://newpipe/abc", url)
        assertEquals("newpipe", winner)
    }

    @Test
    fun `race cancels ytdlp when newpipe wins`() = runTest {
        val ytDlpCancelled = AtomicBoolean(false)
        val hooks = TestableExtractor(
            innertube = { null },
            newpipe = { "https://newpipe/$it" },
            ytdlp = {
                try {
                    delay(5_000); "https://ytdlp/$it"
                } catch (ce: kotlinx.coroutines.CancellationException) {
                    ytDlpCancelled.set(true); throw ce
                }
            },
        )
        PreviewUrlExtractor.raceForTest(hooks, "abc")
        runCurrent()
        assertTrue(ytDlpCancelled.get())
    }

    @Test
    fun `race falls back to ytdlp when innertube and newpipe both return null`() = runTest {
        val hooks = TestableExtractor(
            innertube = { null },
            newpipe = { null },
            ytdlp = { "https://ytdlp/$it" },
        )
        val (url, winner) = PreviewUrlExtractor.raceForTest(hooks, "abc")
        assertEquals("https://ytdlp/abc", url)
        assertEquals("ytdlp", winner)
    }

    @Test
    fun `race falls back to ytdlp when newpipe throws`() = runTest {
        // Regression lock for the same poisoning concern as the existing
        // `innertube throws` test, applied to the new arm.
        val hooks = TestableExtractor(
            innertube = { null },
            newpipe = { throw java.io.IOException("newpipe boom") },
            ytdlp = { "https://ytdlp/$it" },
        )
        val (url, winner) = PreviewUrlExtractor.raceForTest(hooks, "abc")
        assertEquals("https://ytdlp/abc", url)
        assertEquals("ytdlp", winner)
    }

    @Test
    fun `newpipe semaphore caps concurrency at 4`() = runTest {
        val npMax = AtomicInteger(0); val npCur = AtomicInteger(0)
        val hooks = TestableExtractor(
            // Force the race to wait for newpipe by returning null from innertube.
            innertube = { null },
            newpipe = {
                npMax.updateAndGet { m -> maxOf(m, npCur.incrementAndGet()) }
                try { delay(50); "u" } finally { npCur.decrementAndGet() }
            },
            ytdlp = { delay(100_000); "y" },
        )
        coroutineScope {
            (1..20).map { async { PreviewUrlExtractor.raceForTest(hooks, "id$it") } }.awaitAll()
        }
        assertEquals("expected exactly 4 concurrent newpipe slots", 4, npMax.get())
    }
```

- [ ] **Step 2: Run the new tests to verify they fail**

Run: `./gradlew :data:download:testDebugUnitTest --tests "com.stash.data.download.preview.PreviewUrlExtractorTest"`
Expected: 5 FAILs (4 new ones expecting newpipe winner / cancellation / cap; the existing `falls back to ytdlp` may still pass because the placeholder happens to do that already). All semaphore-cap on NewPipe fails because the placeholder body doesn't acquire/release `npSem`.

- [ ] **Step 3: Replace the placeholder `race` body with the real three-arm version**

In `PreviewUrlExtractor.kt`, replace the placeholder `race(...)` body added in Task 5 with:

```kotlin
        /**
         * Races the three extractors. Sequential preference:
         * InnerTube → NewPipe → yt-dlp. Each acquires/releases its own
         * semaphore, so flooding one arm can never starve another.
         *
         * Wrapped in [coroutineScope] so a cancellation on the winner's
         * sibling jobs actually frees their permits — important so a
         * winning InnerTube doesn't leave NewPipe / yt-dlp work running.
         *
         * Returns `(url, winner)` where `winner` is one of "innertube",
         * "newpipe", "ytdlp" — used to stamp the LATDIAG `extract-end`
         * line so on-device evaluation can slice latency by arm.
         */
        private suspend fun race(
            videoId: String,
            innerTubeExtract: suspend (String) -> String?,
            newPipeExtract:  suspend (String) -> String?,
            ytDlpExtract:    suspend (String) -> String,
            itSem: Semaphore,
            npSem: Semaphore,
            ytSem: Semaphore,
        ): Pair<String, String> = coroutineScope {
            val inner = async {
                itSem.acquire()
                try { rescueNull { innerTubeExtract(videoId) } } finally { itSem.release() }
            }
            val np = async {
                npSem.acquire()
                try { rescueNull { newPipeExtract(videoId) } } finally { npSem.release() }
            }
            val yt = async {
                ytSem.acquire()
                try { ytDlpExtract(videoId) } finally { ytSem.release() }
            }

            inner.await()?.let { url ->
                np.cancel(CancellationException("InnerTube won the race"))
                yt.cancel(CancellationException("InnerTube won the race"))
                return@coroutineScope url to "innertube"
            }
            np.await()?.let { url ->
                yt.cancel(CancellationException("NewPipe won the race"))
                return@coroutineScope url to "newpipe"
            }
            yt.await() to "ytdlp"
        }

        /**
         * Rescues any non-cancellation throw from [block] as a null result.
         * Cancellation must still propagate so structured concurrency can
         * tear down sibling jobs cleanly.
         */
        private inline fun <T> rescueNull(block: () -> T?): T? =
            runCatching(block).getOrElse { t ->
                if (t is CancellationException) throw t
                null
            }
```

Note: `rescueNull` takes a non-suspend lambda; the call sites (`{ innerTubeExtract(videoId) }` etc.) are suspend lambdas in suspend context, so Kotlin's `inline` makes the body still callable from a suspending caller. If the compiler complains about suspend/non-suspend mismatch, change the signature to `private suspend inline fun <T> rescueNull(block: suspend () -> T?): T?` — verify compilation either way.

- [ ] **Step 4: Run the full test suite for the module**

Run: `./gradlew :data:download:testDebugUnitTest --tests "com.stash.data.download.preview.*"`
Expected: PASS — all 6 existing PreviewUrlExtractor tests + 5 new ones + all 8 NewPipeStreamExtractor tests.

- [ ] **Step 5: Commit**

```bash
git add data/download/src/main/kotlin/com/stash/data/download/preview/PreviewUrlExtractor.kt \
        data/download/src/test/kotlin/com/stash/data/download/preview/PreviewUrlExtractorTest.kt
git commit -m "feat(preview): three-arm race — InnerTube → NewPipe → yt-dlp (spike)"
```

---

## Task 7: Wire production `NewPipeStreamExtractor` + LATDIAG winner field

**Files:**
- Modify: `data/download/src/main/kotlin/com/stash/data/download/preview/PreviewUrlExtractor.kt`

So far the three-arm race exists but production still passes `{ _ -> null }` for the NewPipe arm. This task makes the real NewPipe call site live and adds the `newpipe-end` + `winner=…` LATDIAG keys.

- [ ] **Step 1: Inject `NewPipeStreamExtractor` into `PreviewUrlExtractor`**

Update the constructor signature (lines ~58-64):

```kotlin
@Singleton
class PreviewUrlExtractor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ytDlpManager: YtDlpManager,
    private val tokenManager: TokenManager,
    private val innerTubeClient: InnerTubeClient,
    private val newPipeStreamExtractor: NewPipeStreamExtractor,   // NEW
)
```

- [ ] **Step 2: Replace the NewPipe placeholder in `extractStreamUrl` with the real call + LATDIAG**

Update the body of `extractStreamUrl` (lines ~159-196) so the NewPipe arm wires to the real extractor and is instrumented:

```kotlin
    suspend fun extractStreamUrl(videoId: String): String {
        val t0 = System.currentTimeMillis()
        Log.d("LATDIAG", "extract-start videoId=$videoId")
        return try {
            val (url, winner) = race(
                videoId = videoId,
                innerTubeExtract = { id ->
                    val it0 = System.currentTimeMillis()
                    val result = runCatching { extractViaInnerTube(id) }
                    val dt = System.currentTimeMillis() - it0
                    val outcome = result.fold(
                        onSuccess = { if (it != null) "url" else "null" },
                        onFailure = { "throw:${it.javaClass.simpleName}" },
                    )
                    Log.d("LATDIAG", "innertube-end videoId=$id dt=${dt}ms outcome=$outcome")
                    result.getOrThrow()
                },
                newPipeExtract = { id ->
                    val np0 = System.currentTimeMillis()
                    val result = runCatching { newPipeStreamExtractor.extractStreamUrl(id) }
                    val dt = System.currentTimeMillis() - np0
                    val outcome = result.fold(
                        onSuccess = { if (it != null) "url" else "null" },
                        onFailure = { "throw:${it.javaClass.simpleName}" },
                    )
                    Log.d("LATDIAG", "newpipe-end videoId=$id dt=${dt}ms outcome=$outcome")
                    result.getOrThrow()
                },
                ytDlpExtract = { id ->
                    val yt0 = System.currentTimeMillis()
                    val result = runCatching { extractViaYtDlp(id) }
                    val dt = System.currentTimeMillis() - yt0
                    val outcome = result.fold(
                        onSuccess = { "url" },
                        onFailure = { "throw:${it.javaClass.simpleName}" },
                    )
                    Log.d("LATDIAG", "ytdlp-end videoId=$id dt=${dt}ms outcome=$outcome")
                    result.getOrThrow()
                },
                itSem = innerTubeSemaphore,
                npSem = newPipeSemaphore,
                ytSem = ytDlpSemaphore,
            )
            Log.d("LATDIAG", "extract-end videoId=$videoId dt=${System.currentTimeMillis() - t0}ms winner=$winner")
            url
        } catch (t: Throwable) {
            Log.d("LATDIAG", "extract-fail videoId=$videoId dt=${System.currentTimeMillis() - t0}ms err=${t.javaClass.simpleName}")
            throw t
        }
    }
```

- [ ] **Step 3: Update the `PreviewUrlExtractor` KDoc**

Replace the class-level KDoc strategy block (lines 27-57) to document the three-arm race. Replace the existing "InnerTube vs yt-dlp race" paragraph and the bullet list with:

```kotlin
/**
 * Extracts a direct audio stream URL from YouTube for preview playback.
 *
 * ## Strategy: three-arm race with sequential preference
 *
 * Three extractors run concurrently, each bounded by its own shared
 * [Semaphore] so the pools never starve each other.
 *
 *  - **InnerTube player API (~1-2s)**: Calls the YouTube Music player
 *    endpoint with authenticated cookies. Parses
 *    `streamingData.adaptiveFormats` for audio-only URLs. These URLs may
 *    be n-parameter throttled (~50KB/s) but that's more than enough for
 *    audio preview (Opus is ~20KB/s). Cap: 8 concurrent.
 *
 *  - **NewPipe Extractor (~1-4s) — feasibility spike**: In-process cipher
 *    solving via Rhino. Faster than yt-dlp; no Python runtime / JNI.
 *    Currently being evaluated against yt-dlp via LATDIAG `newpipe-end`
 *    + `winner` instrumentation. Cap: 4 concurrent.
 *
 *  - **yt-dlp fallback (~15-35s)**: Heavier path using QuickJS for full
 *    signature solving. Slow but reliable correctness backstop, so we
 *    cap it at 2 concurrent to avoid thrashing CPU / the yt-dlp JNI
 *    surface.
 *
 * Race semantics are sequential-preference: InnerTube → NewPipe → yt-dlp.
 * InnerTube is awaited first; if it returns a URL, NewPipe and yt-dlp are
 * cancelled and that URL wins. If InnerTube returns null, NewPipe's
 * result is awaited; if it also returned null, the race falls through to
 * yt-dlp (which must throw on hard failure, never return null).
 *
 * Any non-cancellation failure in InnerTube or NewPipe is rescued
 * inside the async (via [rescueNull]) as a null result, so the race
 * transparently falls through. CancellationException must always
 * propagate to preserve structured concurrency.
 */
```

- [ ] **Step 4: Compile + run all tests for the module**

Run: `./gradlew :data:download:testDebugUnitTest`
Expected: PASS — full test suite green. No regression in existing tests; new `newpipe-end` LATDIAG calls don't affect test outcomes (Android Log calls return default values in JVM tests).

- [ ] **Step 5: Build the debug APK to catch any DI-level regressions**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL. Hilt's annotation processor catches missing/ambiguous bindings at compile time; if `NewPipeStreamExtractor` isn't resolvable, this is where it surfaces.

- [ ] **Step 6: Commit**

```bash
git add data/download/src/main/kotlin/com/stash/data/download/preview/PreviewUrlExtractor.kt
git commit -m "feat(preview): wire NewPipeStreamExtractor + winner LATDIAG (spike)"
```

---

## Task 8: On-device smoke verification + data capture

**Files:** none (verification-only)

The spike's *answer* lives in LATDIAG output, not in the test suite. Per `feedback_install_after_fix.md` in memory, compile-pass is not enough on this project — install on device, exercise the feature, read logcat.

- [ ] **Step 1: Install the debug APK**

Run: `./gradlew :app:installDebug`
Expected: `Installed on N device(s).` Per memory, always do this step after a code change on this project — it has caught crashes that the unit tests missed.

- [ ] **Step 2: Start filtered logcat for LATDIAG**

In a separate terminal:

```bash
adb logcat -c
adb logcat -s LATDIAG StashYT NewPipeStreamExtractor PreviewUrlExtractor
```

`-c` clears the buffer; `-s` filters to just the relevant tags.

- [ ] **Step 3: Exercise the YT-fallback path on-device**

In the app:
1. Open Search.
2. Search for a track known to be missing from Qobuz catalog (the YT-fallback case — e.g., something obscure or recently removed).
3. Tap Preview.
4. Wait for playback to start.
5. Repeat for ~5 different YT-fallback tracks to get a reasonable sample.

Then also test a *catalog* track (something popular that Qobuz has) to confirm catalog behavior is unchanged.

- [ ] **Step 4: Inspect the LATDIAG output**

For each preview, the expected logcat sequence is:

```
extract-start  videoId=<id>
innertube-end  videoId=<id> dt=<N>ms outcome=null      (for YT-fallback)
newpipe-end    videoId=<id> dt=<N>ms outcome=url|null|throw:…
ytdlp-end      videoId=<id> dt=<N>ms outcome=url|throw:…
extract-end    videoId=<id> dt=<N>ms winner=newpipe|ytdlp
```

Note: NewPipe and yt-dlp run in parallel from t=0, so both `newpipe-end` and `ytdlp-end` may log; whichever produces a usable URL first determines the `winner` field. The cancelled arm's `ytdlp-end` may log with `outcome=throw:CancellationException` — that's expected, not a failure.

For catalog tracks, `winner=innertube` should appear with the same latency as before this branch.

- [ ] **Step 5: Capture the data**

Save a representative logcat sample to a file (not committed — just for evaluation):

```bash
adb logcat -d -s LATDIAG > /tmp/newpipe-spike-latdiag.log
```

Compute the answer from the data:
- How often does `winner=newpipe` vs `winner=ytdlp` on YT-fallback tracks?
- What's the average `dt` of `newpipe-end outcome=url` vs `ytdlp-end outcome=url`?
- What's the `extract-end dt` distribution for the YT-fallback population vs before this branch?
- Any new exception types in `newpipe-end outcome=throw:*` that indicate library breakage?

Document findings in `docs/superpowers/specs/2026-05-21-newpipe-extractor-spike-design.md` under a new "## Spike Results" section — this is the deliverable that informs the follow-up branch.

- [ ] **Step 6: Commit the findings**

```bash
git add docs/superpowers/specs/2026-05-21-newpipe-extractor-spike-design.md
git commit -m "docs: NewPipe spike on-device findings"
```

---

## Out of scope (do NOT do)

- Demoting yt-dlp (lowering its concurrency, raising its timeout, removing the call site) — that's a follow-up branch informed by Task 8's findings.
- Wiring NewPipe into `extractViaYtDlpForRetry`.
- Wiring NewPipe into the download (full-file) path.
- Adding a settings toggle, diagnostics card row, or any UI surface for the spike.
- Adding cookies / auth to `OkHttpNewPipeDownloader`.
- Eager `Application.onCreate()` init of NewPipe.
- Evaluating ViMusic's NewPipe fork or any non-upstream version.

If any of these feel necessary to "make the spike feel complete," push back — the whole point is to measure first, expand later.
