package com.stash.core.media.streaming

import android.util.Log
import com.stash.core.data.db.entity.TrackEntity
import com.stash.data.ytmusic.YTMusicApiClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Streams a track by **racing a pool of Piped / Invidious YouTube-proxy
 * servers in parallel** and taking the first one that returns a playable
 * audio URL — exactly the technique the "instant" open-source clients
 * (InnerTune, etc.) use.
 *
 * Piped instances do NOT host any music (no one can legally hold the
 * catalogue). Given a YouTube `videoId`, an instance does the extraction
 * server-side and returns a direct, **full-playable** `googlevideo` audio
 * URL in ~hundreds of ms — no on-device yt-dlp, no 1 MB PO-token gate.
 *
 * ## Why a race
 * For each song every healthy instance is hit **once, in parallel**; the
 * first valid URL wins and the rest are cancelled. Because each request
 * goes to a *different* server, racing N servers never trips any single
 * server's rate-limit — it just means the fastest of N answers. A slow or
 * down instance can't delay playback: it simply loses the race. This makes
 * a NEVER-PLAYED song start near-instantly. The user can extend the pool
 * with their own self-hosted instances for a consistently fast, large pool.
 */
@Singleton
class PipedStreamResolver @Inject constructor(
    @StreamingHttpClient private val http: OkHttpClient,
    private val ytMusicApiClient: YTMusicApiClient,
    private val streamingPreference: com.stash.core.data.prefs.StreamingPreference,
) {
    /** Rolling per-instance health so the race skips dead hosts + favours fast ones. */
    private data class Health(val consecutiveFailures: Int = 0, val lastLatencyMs: Long = Long.MAX_VALUE)
    private val health = java.util.concurrent.ConcurrentHashMap<String, Health>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Live public-instance list, auto-fetched from Piped's public directory so
     *  the pool stays fresh forever with zero user/app-update action. */
    @Volatile private var fetchedInstances: List<String> = emptyList()
    @Volatile private var fetchedAtMs: Long = 0L

    init {
        // Cold-start: load the last-known-good list from disk immediately so the
        // very first tap after opening the app already has a fresh server pool.
        scope.launch {
            val cached = runCatching { streamingPreference.cachedPipedInstances() }.getOrDefault(emptyList())
            if (cached.isNotEmpty() && fetchedInstances.isEmpty()) fetchedInstances = cached
        }
    }

    suspend fun resolve(track: TrackEntity): StreamUrl? {
        val videoId = track.youtubeId?.takeIf { it.isNotBlank() }
            ?: runCatching { ytMusicApiClient.searchCanonicalVideoId(track.artist, track.title) }
                .getOrNull()
            ?: return null

        val pool = instancePool()
        if (pool.isEmpty()) return null
        val winner = withTimeoutOrNull(RACE_TIMEOUT_MS) { race(videoId, pool) }
        if (winner != null) {
            Log.d(TAG, "piped served '${track.title}' via ${winner.second}")
            return winner.first
        }
        Log.d(TAG, "piped: no instance answered for '${track.title}' ($videoId)")
        return null
    }

    /**
     * The race pool: the user's own servers FIRST, then the built-in public
     * defaults, deduped. Instances that have failed [MAX_FAILS] times in a row
     * are dropped (cooldown) so dead hosts don't waste a slot; survivors are
     * ordered fastest-first by last measured latency.
     */
    private suspend fun instancePool(): List<String> {
        maybeRefreshInstances() // fire-and-forget; uses current list meanwhile
        val user = runCatching { streamingPreference.pipedInstancesNow() }.getOrDefault(emptyList())
        // user servers first, then the auto-fetched live list, then built-ins.
        val all = (user + fetchedInstances + DEFAULT_INSTANCES).distinct()
        val live = all.filter { (health[it]?.consecutiveFailures ?: 0) < MAX_FAILS }
        // If a transient outage benched everything, retry the whole list rather
        // than lock the user out — survivors recover their health on success.
        return (live.ifEmpty { all })
            .sortedBy { health[it]?.lastLatencyMs ?: DEFAULT_TTL_MS } // fastest-first; unknown mid
            .take(MAX_POOL) // bound parallelism per song
    }

    /**
     * Refresh the public-instance list from Piped's directory in the background
     * if it's stale. Non-blocking: the current resolve uses whatever list is
     * already loaded (built-ins on first run), and the next resolve benefits
     * from the fresh list. Dead public instances get replaced automatically —
     * no app update, no user action.
     */
    private fun maybeRefreshInstances() {
        val now = System.currentTimeMillis()
        if (now - fetchedAtMs < INSTANCE_LIST_TTL_MS) return
        fetchedAtMs = now // claim the refresh slot up-front to avoid a stampede
        scope.launch {
            val list = runCatching { fetchInstanceDirectory() }.getOrNull()
            if (!list.isNullOrEmpty()) {
                fetchedInstances = list
                runCatching { streamingPreference.setCachedPipedInstances(list) } // persist for cold-start
                Log.d(TAG, "auto-refreshed Piped instances: ${list.size} live")
            } else {
                fetchedAtMs = 0L // failed — allow a retry next call
            }
        }
    }

    private suspend fun fetchInstanceDirectory(): List<String> {
        val body = httpGet(INSTANCE_DIRECTORY_URL) ?: return emptyList()
        val arr = runCatching { JSONArray(body) }.getOrNull() ?: return emptyList()
        val out = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            // Only take healthy, current instances; skip ones flagged stale.
            if (!o.optBoolean("up_to_date", true)) continue
            val api = o.optString("api_url").trim().trimEnd('/')
            if (api.startsWith("http")) out.add(api)
        }
        return out
    }

    /**
     * Fan out across every instance at once; complete with the FIRST valid
     * [StreamUrl] (and the instance that served it), or null when all miss.
     */
    private suspend fun race(videoId: String, instances: List<String>): Pair<StreamUrl, String>? =
        coroutineScope {
            val winner = CompletableDeferred<Pair<StreamUrl, String>?>()
            val jobs = instances.map { base ->
                launch {
                    val url = runCatching { fetchFromInstance(base, videoId) }.getOrNull()
                    if (url != null) winner.complete(url to base)
                }
            }
            // If every probe finishes without a hit, resolve to null.
            launch {
                jobs.joinAll()
                winner.complete(null)
            }
            val result = winner.await()
            jobs.forEach { it.cancel() } // cancel the losers' in-flight calls
            result
        }

    private suspend fun fetchFromInstance(base: String, videoId: String): StreamUrl? {
        val started = System.currentTimeMillis()
        val result = withTimeoutOrNull(INSTANCE_TIMEOUT_MS) {
            val body = httpGet("${base.trimEnd('/')}/streams/$videoId")
                ?: return@withTimeoutOrNull null
            parseAudioUrl(body)
        }
        if (result != null) {
            health[base] = Health(0, System.currentTimeMillis() - started)
        } else {
            val prevFails = health[base]?.consecutiveFailures ?: 0
            health[base] = Health(prevFails + 1, Long.MAX_VALUE)
        }
        return result
    }

    /** Cancellable OkHttp GET — losing racers cancel their in-flight call. */
    private suspend fun httpGet(url: String): String? = suspendCancellableCoroutine { cont ->
        val call = http.newCall(
            Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .build(),
        )
        cont.invokeOnCancellation { runCatching { call.cancel() } }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resume(null)
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    val text = if (it.isSuccessful) it.body?.string() else null
                    if (cont.isActive) cont.resume(text)
                }
            }
        })
    }

    private fun parseAudioUrl(body: String): StreamUrl? {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val streams = root.optJSONArray("audioStreams") ?: return null
        var bestUrl: String? = null
        var bestBitrate = -1
        for (i in 0 until streams.length()) {
            val o = streams.optJSONObject(i) ?: continue
            val url = o.optString("url").takeIf { it.isNotBlank() } ?: continue
            val bitrate = o.optInt("bitrate", 0)
            if (bitrate > bestBitrate) {
                bestBitrate = bitrate
                bestUrl = url
            }
        }
        val url = bestUrl ?: return null
        return StreamUrl(
            url = url,
            expiresAtMs = parseExpireMs(url) ?: (System.currentTimeMillis() + DEFAULT_TTL_MS),
            codec = "aac",
            bitrateKbps = if (bestBitrate > 0) bestBitrate / 1000 else null,
            origin = ORIGIN,
            // Piped serves full-playable URLs (not the 1 MB-gated InnerTube
            // fast-lane), so this is a real, cacheable resolve.
            placeholder = false,
        )
    }

    private fun parseExpireMs(url: String): Long? =
        EXPIRE_REGEX.find(url)?.groupValues?.get(1)?.toLongOrNull()?.let { it * 1000L }

    private companion object {
        const val TAG = "PipedStreamResolver"
        const val ORIGIN = "piped"
        const val DEFAULT_TTL_MS = 60 * 60 * 1000L
        const val RACE_TIMEOUT_MS = 4_000L

        /** Per-instance budget — a dead/slow host drops out of the race fast. */
        const val INSTANCE_TIMEOUT_MS = 1_500L

        /** Consecutive failures before an instance is benched (cooldown). */
        const val MAX_FAILS = 3

        /** Public directory of live Piped API instances (auto-refreshed). */
        const val INSTANCE_DIRECTORY_URL = "https://piped-instances.kavin.rocks/"

        /** How often to re-fetch the public instance directory (6 h). */
        const val INSTANCE_LIST_TTL_MS = 6L * 60 * 60 * 1000

        /** Max instances raced per song — bounds parallel requests. */
        const val MAX_POOL = 20
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile"

        private val EXPIRE_REGEX = Regex("""[?&]expire=(\d+)""")

        /**
         * Built-in public Piped API hosts (the `/streams/` backend, not the
         * web frontend). Availability of public instances fluctuates — the
         * race tolerates dead ones, and users can self-host their own for a
         * reliably large pool.
         */
        val DEFAULT_INSTANCES = listOf(
            "https://pipedapi.kavin.rocks",
            "https://pipedapi.adminforge.de",
            "https://pipedapi.reallyaweso.me",
            "https://pipedapi.leptons.xyz",
            "https://api.piped.private.coffee",
            "https://pipedapi.darkness.services",
            "https://piapi.ggtyler.dev",
            "https://pipedapi.nosebs.ru",
            "https://pipedapi-libre.kavin.rocks",
            "https://pipedapi.ducks.party",
            "https://pipedapi.smnz.de",
            "https://pipedapi.r4fo.com",
            "https://pipedapi.tokhmi.xyz",
            "https://pipedapi.moomoo.me",
            "https://api.piped.projectsegfau.lt",
            "https://pipedapi.syncpundit.io",
            "https://watchapi.whatever.social",
            "https://api.piped.yt",
        )
    }
}
