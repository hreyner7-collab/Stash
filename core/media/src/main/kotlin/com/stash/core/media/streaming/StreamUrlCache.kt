package com.stash.core.media.streaming

import android.util.Log
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * TTL cache for resolved stream URLs, keyed by track id — in-memory with
 * optional disk persistence.
 *
 * Entries are valid while [StreamUrl.expiresAtMs] is strictly greater
 * than the current wall clock (`nowMsProvider()`); at or past the
 * expiry instant the entry is evicted lazily on read. There is no
 * background sweeper — stale entries linger until they're next looked
 * up or explicitly invalidated.
 *
 * **Persistence.** When constructed with a [persistFile] (production —
 * see `MediaModule`), non-placeholder entries are journalled to disk as
 * JSON: loaded once at startup (off-thread; merged via `putIfAbsent` so
 * a resolve that races the load always wins) and saved with a debounce
 * after each write. Signed CDN URLs live for hours, so surviving a
 * process restart means the first tap after reopening the app skips the
 * resolve round-trip entirely — the URL is already on disk, and with the
 * track-keyed stream cache the bytes usually are too. Placeholder
 * (fast-lane) entries are never persisted: they 403 ~1 MB in and must
 * not outlive the session that seeded them.
 *
 * Concurrency: Media3 reads URLs on a player thread while UI/ViewModel
 * code writes them on main; tests drive the clock on the calling
 * thread. [ConcurrentHashMap] gives atomic per-key reads/writes, which
 * is the only invariant we need (no compound transactions cross keys).
 *
 * The [nowMsProvider] seam exists so tests can drive time
 * deterministically without `Thread.sleep`. Unit tests construct
 * `StreamUrlCache()` (no file) and exercise pure in-memory behaviour.
 */
class StreamUrlCache(
    private val persistFile: File? = null,
) {
    internal var nowMsProvider: () -> Long = System::currentTimeMillis

    private val cache = ConcurrentHashMap<Long, StreamUrl>()

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var saveJob: Job? = null

    init {
        if (persistFile != null) ioScope.launch { loadFromDisk() }
    }

    fun get(trackId: Long): StreamUrl? {
        val entry = cache[trackId] ?: return null
        return if (entry.expiresAtMs > nowMsProvider()) {
            entry
        } else {
            cache.remove(trackId)
            null
        }
    }

    fun put(trackId: Long, url: StreamUrl) {
        cache[trackId] = url
        scheduleSave()
    }

    fun invalidate(trackId: Long) {
        cache.remove(trackId)
        scheduleSave()
    }

    // ── Negative cache ────────────────────────────────────────────────
    // trackId -> wall-clock expiry of an "unresolvable" verdict. On-device
    // 2026-06-12: tracks that fail a FULL resolve (yt-dlp included) burn
    // the serialized extractor slot for the whole 35s timeout — and were
    // retried on EVERY transition (one track wasted 105s of slot time in
    // a 90-second listening test), starving the URL upgrades of playable
    // tracks and stalling audible playback. Remembering the failure for
    // [UNRESOLVABLE_TTL_MS] caps the cost of a dead track at one timeout
    // per TTL window. In-memory only — "dead" is usually a temporary
    // upstream condition (kennyy outage, YT gating), so it must not
    // outlive the session.

    private val unresolvable = ConcurrentHashMap<Long, Long>()

    /** Record that a full-power resolve for [trackId] found nothing. */
    fun markUnresolvable(trackId: Long) {
        unresolvable[trackId] = nowMsProvider() + UNRESOLVABLE_TTL_MS
    }

    /** True while a recent full-power resolve failure is on record. */
    fun isUnresolvable(trackId: Long): Boolean {
        val until = unresolvable[trackId] ?: return false
        return if (until > nowMsProvider()) {
            true
        } else {
            unresolvable.remove(trackId)
            false
        }
    }

    /** Debounced save — coalesces the put-bursts of a background queue fill
     * into one write instead of rewriting the file per track. */
    private fun scheduleSave() {
        if (persistFile == null) return
        saveJob?.cancel()
        saveJob = ioScope.launch {
            delay(SAVE_DEBOUNCE_MS)
            saveToDisk()
        }
    }

    private fun loadFromDisk() {
        val file = persistFile ?: return
        try {
            if (!file.exists()) return
            val now = nowMsProvider()
            val arr = JSONArray(file.readText())
            var loaded = 0
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val expires = o.getLong("exp")
                if (expires <= now) continue // expired while we were away
                val entry = StreamUrl(
                    url = o.getString("url"),
                    expiresAtMs = expires,
                    codec = o.optString("codec").takeIf { it.isNotEmpty() },
                    bitsPerSample = o.optInt("bits").takeIf { it > 0 },
                    sampleRateHz = o.optInt("rate").takeIf { it > 0 },
                    bitrateKbps = o.optInt("kbps").takeIf { it > 0 },
                    coverArtUrl = o.optString("art").takeIf { it.isNotEmpty() },
                    origin = o.optString("origin").takeIf { it.isNotEmpty() },
                )
                // putIfAbsent: a live resolve that landed before the disk
                // load finished is newer — never clobber it with the
                // journalled copy.
                if (cache.putIfAbsent(o.getLong("id"), entry) == null) loaded++
            }
            Log.d(TAG, "loaded $loaded persisted stream URLs")
        } catch (e: Exception) {
            // Corrupt/unreadable journal is throwaway state — discard it.
            Log.w(TAG, "failed to load persisted stream URLs: ${e.message}")
            runCatching { file.delete() }
        }
    }

    private fun saveToDisk() {
        val file = persistFile ?: return
        try {
            val now = nowMsProvider()
            val arr = JSONArray()
            for ((id, entry) in cache) {
                if (entry.placeholder) continue
                if (entry.expiresAtMs <= now) continue
                arr.put(
                    JSONObject()
                        .put("id", id)
                        .put("url", entry.url)
                        .put("exp", entry.expiresAtMs)
                        .put("codec", entry.codec ?: "")
                        .put("bits", entry.bitsPerSample ?: 0)
                        .put("rate", entry.sampleRateHz ?: 0)
                        .put("kbps", entry.bitrateKbps ?: 0)
                        .put("art", entry.coverArtUrl ?: "")
                        .put("origin", entry.origin ?: ""),
                )
            }
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(arr.toString())
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        } catch (e: Exception) {
            Log.w(TAG, "failed to persist stream URLs: ${e.message}")
        }
    }

    private companion object {
        const val TAG = "StreamUrlCache"
        const val SAVE_DEBOUNCE_MS = 2_000L

        /** How long a failed full resolve suppresses further full
         * resolves of the same track. Long enough to stop transition-by-
         * transition retry burn; short enough that an upstream recovery
         * (kennyy back up) is picked up within minutes. */
        const val UNRESOLVABLE_TTL_MS = 15L * 60 * 1000
    }
}
