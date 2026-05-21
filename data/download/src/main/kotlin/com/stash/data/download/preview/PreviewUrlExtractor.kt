package com.stash.data.download.preview

import android.content.Context
import android.util.Log
import com.stash.core.auth.TokenManager
import com.stash.data.download.CookieFileWriter
import com.stash.data.download.ytdlp.YtDlpManager
import com.stash.data.ytmusic.InnerTubeClient
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Extracts a direct audio stream URL from YouTube for preview playback.
 *
 * ## Strategy: InnerTube vs yt-dlp race (parallel, split concurrency)
 *
 * Both extractors run concurrently, each bounded by its own shared
 * [Semaphore] so the two pools don't starve each other:
 *
 *  - **InnerTube player API (~1-2s)**: Calls the YouTube Music player
 *    endpoint with authenticated cookies. Parses
 *    `streamingData.adaptiveFormats` for audio-only URLs. These URLs may
 *    be n-parameter throttled (~50KB/s) but that's more than enough for
 *    audio preview (Opus is ~20KB/s). Cap: 8 concurrent.
 *
 *  - **yt-dlp fallback (~15-35s)**: Heavier path using QuickJS for full
 *    signature solving. Slow but reliable, so we cap it at 2 concurrent
 *    to avoid thrashing CPU / the yt-dlp JNI surface.
 *
 * The race returns whichever extractor succeeds first:
 *  - InnerTube returns a non-null URL → cancel yt-dlp and return it.
 *  - InnerTube returns null (ciphered/geo-blocked/unavailable) or
 *    throws → await yt-dlp and return its result (throws on hard fail).
 *
 * Any non-cancellation failure in InnerTube is rescued inside the async
 * as a null result, so the race transparently falls back to yt-dlp
 * without poisoning the enclosing [coroutineScope].
 *
 * Because the InnerTube fast path succeeds for most YouTube Music
 * tracks, previews feel near-instant while preserving yt-dlp as a
 * correctness backstop.
 */
@Singleton
class PreviewUrlExtractor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val ytDlpManager: YtDlpManager,
    private val tokenManager: TokenManager,
    private val innerTubeClient: InnerTubeClient,
) {
    /** Test-only injection point for race logic. Not wired in production. */
    internal interface TestHooks {
        suspend fun innerTubeExtract(id: String): String?
        suspend fun newPipeExtract(id: String): String?
        suspend fun ytDlpExtract(id: String): String
    }

    companion object {
        private const val TAG = "PreviewUrlExtractor"
        private const val YTDLP_TIMEOUT_MS = 60_000L
        private const val INNERTUBE_TIMEOUT_MS = 10_000L
        private const val FORMAT_SELECTOR = "251/250/bestaudio"

        /** Concurrency caps for the three extractors. Shared process-wide. */
        private const val INNERTUBE_CONCURRENCY = 8
        private const val NEWPIPE_CONCURRENCY = 4
        private const val YTDLP_CONCURRENCY = 2

        /**
         * Shared so parallel callers (e.g. `PreviewPrefetcher`) respect the
         * cap regardless of how many [PreviewUrlExtractor] instances exist.
         */
        private val innerTubeSemaphore = Semaphore(INNERTUBE_CONCURRENCY)
        private val newPipeSemaphore = Semaphore(NEWPIPE_CONCURRENCY)
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
        private suspend inline fun <T> rescueNull(block: suspend () -> T?): T? =
            runCatching { block() }.getOrElse { t ->
                if (t is CancellationException) throw t
                null
            }
    }

    /**
     * Extracts a direct audio stream URL for the given YouTube video ID.
     *
     * Races InnerTube (fast, ~1-2s) against yt-dlp (slow, ~15-35s). See
     * the class KDoc for the full strategy.
     */
    suspend fun extractStreamUrl(videoId: String): String {
        val t0 = System.currentTimeMillis()
        Log.d("LATDIAG", "extract-start videoId=$videoId")
        return try {
            val (url, _winner) = race(
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
                newPipeExtract = { _ -> null },
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
            Log.d("LATDIAG", "extract-end videoId=$videoId dt=${System.currentTimeMillis() - t0}ms")
            url
        } catch (t: Throwable) {
            Log.d("LATDIAG", "extract-fail videoId=$videoId dt=${System.currentTimeMillis() - t0}ms err=${t.javaClass.simpleName}")
            throw t
        }
    }

    /**
     * Retry-only entry point: bypass the InnerTube race and go straight to
     * yt-dlp. Used by [com.stash.feature.search.SearchViewModel.onPreviewError]
     * when ExoPlayer rejects an InnerTube URL (typically because the URL is
     * n-parameter-throttled past what ExoPlayer is willing to wait for).
     *
     * yt-dlp's QuickJS cipher path produces unthrottled URLs that play
     * reliably, at the cost of ~15-35 s extraction latency.
     */
    suspend fun extractViaYtDlpForRetry(videoId: String): String =
        extractViaYtDlp(videoId)

    /**
     * Fast path: extract stream URL via InnerTube player API.
     *
     * Calls `/youtubei/v1/player` with the user's YouTube session cookies.
     * Parses `streamingData.adaptiveFormats` for audio-only formats with
     * direct `url` fields (not `signatureCipher`).
     *
     * Returns null if no usable URL is found (all formats ciphered, video
     * unavailable, etc.).
     */
    private suspend fun extractViaInnerTube(videoId: String): String? {
        return withTimeout(INNERTUBE_TIMEOUT_MS) {
            Log.d(TAG, "Trying InnerTube fast path for videoId=$videoId")

            // Ordered client-variant attempt. ANDROID_VR / IOS frequently
            // return unciphered adaptiveFormats urls, letting us skip the
            // ~14 s yt-dlp + QuickJS cipher-solve path entirely. Falls
            // back transparently to WEB_REMIX if neither yields a direct URL.
            val response = innerTubeClient.playerForAudio(videoId) ?: run {
                Log.w(TAG, "InnerTube playerForAudio returned null for $videoId")
                return@withTimeout null
            }

            // Check playability
            val status = response["playabilityStatus"]
                ?.jsonObject?.get("status")
                ?.jsonPrimitive?.content
            if (status != "OK") {
                Log.w(TAG, "InnerTube: video $videoId status=$status")
                return@withTimeout null
            }

            // Extract streamingData.adaptiveFormats
            val streamingData = response["streamingData"]?.jsonObject ?: run {
                Log.w(TAG, "InnerTube: no streamingData for $videoId")
                return@withTimeout null
            }

            val adaptiveFormats = streamingData["adaptiveFormats"]?.jsonArray ?: run {
                Log.w(TAG, "InnerTube: no adaptiveFormats for $videoId")
                return@withTimeout null
            }

            // Find the best audio format with a direct URL (not signatureCipher)
            val audioFormats = adaptiveFormats
                .filterIsInstance<JsonObject>()
                .filter { format ->
                    val mimeType = format["mimeType"]?.jsonPrimitive?.content ?: ""
                    val hasDirectUrl = format["url"] != null
                    val isAudio = mimeType.startsWith("audio/")
                    isAudio && hasDirectUrl
                }
                .sortedByDescending { it["bitrate"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L }

            val bestAudio = audioFormats.firstOrNull() ?: run {
                Log.d(TAG, "InnerTube: no audio formats with direct URL for $videoId " +
                    "(${adaptiveFormats.size} total formats, all may be ciphered)")
                return@withTimeout null
            }

            val streamUrl = bestAudio["url"]!!.jsonPrimitive.content
            val mimeType = bestAudio["mimeType"]?.jsonPrimitive?.content ?: "unknown"
            val bitrate = bestAudio["bitrate"]?.jsonPrimitive?.content ?: "?"

            Log.d(TAG, "InnerTube: SUCCESS videoId=$videoId mime=$mimeType bitrate=$bitrate urlLen=${streamUrl.length}")
            streamUrl
        }
    }

    /**
     * Slow fallback: extract stream URL via yt-dlp with QuickJS cipher solving.
     */
    private suspend fun extractViaYtDlp(videoId: String): String {
        val cookieFile = File(context.noBackupFilesDir, "yt_preview_cookies_${System.nanoTime()}.txt")

        return withTimeout(YTDLP_TIMEOUT_MS) {
            withContext(Dispatchers.IO) {
                ytDlpManager.initialize()

                try {
                    val url = "https://www.youtube.com/watch?v=$videoId"

                    val request = YoutubeDLRequest(url).apply {
                        addOption("-f", FORMAT_SELECTOR)
                        addOption("--print", "urls")
                        addOption("--no-download")

                        val qjsPath = ytDlpManager.quickJsPath
                        if (qjsPath != null) {
                            addOption("--js-runtimes", "quickjs:$qjsPath")
                            addOption("--remote-components", "ejs:github")
                        }
                    }

                    val cookie = tokenManager.getYouTubeCookie()
                    if (cookie != null) {
                        CookieFileWriter.write(cookie, cookieFile)
                        cookieFile.setReadable(false, false)
                        cookieFile.setReadable(true, true)
                        cookieFile.setWritable(false, false)
                        cookieFile.setWritable(true, true)
                        request.addOption("--cookies", cookieFile.absolutePath)
                    }

                    Log.d(TAG, "yt-dlp: invoking for videoId=$videoId")
                    val response = YoutubeDL.getInstance().execute(request, url, null)

                    val stdout = response.out.orEmpty()
                    val stderr = response.err.orEmpty()
                    Log.d(TAG, "yt-dlp: exit=${response.exitCode} stdoutLen=${stdout.length}")
                    if (stderr.isNotBlank()) {
                        Log.d(TAG, "yt-dlp stderr: ${stderr.take(500)}")
                    }

                    val streamUrl = stdout.trim().lines().firstOrNull { it.startsWith("http") }
                    check(!streamUrl.isNullOrBlank()) {
                        "yt-dlp returned no stream URL for videoId=$videoId. stderr: ${stderr.take(500)}"
                    }

                    Log.d(TAG, "yt-dlp: SUCCESS videoId=$videoId urlLen=${streamUrl.length}")
                    streamUrl
                } finally {
                    if (cookieFile.exists()) cookieFile.delete()
                }
            }
        }
    }
}
