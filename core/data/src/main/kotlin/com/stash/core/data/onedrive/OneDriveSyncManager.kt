package com.stash.core.data.onedrive

import android.content.Context
import android.util.Log
import com.stash.core.data.db.dao.LyricsDao
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.db.entity.TrackEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/**
 * Orchestrates the "Sync to OneDrive" feature: pushes the user's
 * downloaded tracks into `/Stash/Music/` on their OneDrive and answers
 * playback's "is this track in the cloud warehouse?" question.
 *
 * **Remote naming**: `{trackId}{ext}` (e.g. `1234.opus`) — deterministic,
 * collision-free, and resolvable with a single exact-path Graph call.
 * The id→name map is also journalled locally ([mapFile]) so lookups know
 * each track's extension without listing the drive.
 *
 * **Sync algorithm** (one [syncNow] pass):
 *  1. List the remote folder once (names + sizes).
 *  2. For every downloaded track whose file exists locally: skip if the
 *     remote copy exists with the same size, upload otherwise.
 *  3. Journal each success into the local map + [progress].
 *
 * Single-flight: concurrent [syncNow] calls coalesce onto the running
 * pass. Failures are per-track (logged, skipped) — one bad file never
 * aborts the library.
 */
@Singleton
class OneDriveSyncManager @Inject constructor(
    @ApplicationContext context: Context,
    private val client: OneDriveClient,
    private val authStore: OneDriveAuthStore,
    private val trackDao: TrackDao,
    private val lyricsDao: LyricsDao,
    private val cloudFetcher: CloudAudioFetcher,
) {
    data class SyncProgress(
        val running: Boolean = false,
        val total: Int = 0,
        val done: Int = 0,
        val uploading: String? = null,
        val lastError: String? = null,
        /** Pass start time — drives the UI's ETA computation. */
        val startedAtMs: Long = 0L,
        /** Bytes uploaded by THIS pass so far. */
        val bytesUploadedPass: Long = 0L,
        /** Projected warehouse size when the pass finishes (current bytes
         * + average-song-size x remaining). Shown only while running. */
        val estimatedFinalBytes: Long = 0L,
    ) {
        val percent: Int get() = if (total == 0) 0 else (done * 100 / total).coerceAtMost(100)

        /** Rough remaining-time estimate from this pass's own pace. */
        val etaMs: Long? get() {
            if (!running || done == 0 || startedAtMs == 0L) return null
            val perItem = (System.currentTimeMillis() - startedAtMs) / done
            return perItem * (total - done)
        }
    }

    /** Cooperative stop: cancels the running pass after the in-flight
     * song finishes its current network call. */
    fun stopSync() {
        syncJob?.cancel()
        _progress.value = _progress.value.copy(running = false, uploading = null)
    }

    private val _progress = MutableStateFlow(SyncProgress())
    val progress: StateFlow<SyncProgress> = _progress.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncMutex = Mutex()

    @Volatile
    private var syncJob: Job? = null

    /** Local journal of trackId -> remote file name. */
    private val mapFile = File(context.filesDir, "onedrive_sync_map.json")
    private val remoteNames = HashMap<Long, String>()

    init {
        runCatching {
            if (mapFile.exists()) {
                val json = JSONObject(mapFile.readText())
                json.keys().forEach { key ->
                    remoteNames[key.toLong()] = json.getString(key)
                }
            }
        }.onFailure { Log.w(TAG, "sync map load failed: ${it.message}") }

        // Restore-on-launch: if OneDrive is connected and the local library
        // is empty (a reinstall wiped it), rebuild it from the warehouse
        // manifest so the user's songs + the DJ's taste data come straight
        // back without re-syncing anything.
        scope.launch {
            runCatching {
                if (authStore.isConnected() && trackDao.getAllByDateAdded().first().isEmpty()) {
                    val remote = client.listSyncedFiles()
                    restoreLibraryFromCloud(remote)
                }
            }.onFailure { Log.w(TAG, "restore-on-launch failed: ${it.message}") }
        }

        // Auto-sync: while the toggle is on, any change to the downloaded
        // set (new download lands, file removed) schedules a sync pass.
        // collectLatest + delay debounces a download batch into one pass.
        scope.launch {
            trackDao.getAllByDateAdded().collectLatest { tracks ->
                if (!authStore.autoSyncEnabled.first()) return@collectLatest
                if (tracks.none { it.isDownloaded }) return@collectLatest
                delay(AUTO_SYNC_DEBOUNCE_MS)
                requestSync()
            }
        }
    }

    /** Launches a sync pass unless one is already running. */
    fun requestSync() {
        if (syncJob?.isActive == true) return
        syncJob = scope.launch { runCatching { syncNow() } }
    }

    suspend fun syncNow() {
        if (!authStore.isConnected()) return
        syncMutex.withLock {
            val remote0 = runCatching { client.listSyncedFiles() }.getOrNull() ?: run {
                _progress.value = SyncProgress(lastError = "Couldn't reach OneDrive")
                return
            }
            var warehouseBytes = remote0.sumOf { it.sizeBytes }
            var warehouseCount = remote0.mapNotNull { it.name.substringBefore('.').toLongOrNull() }
                .toHashSet().size

            // If the local library was wiped (a reinstall clears the DB) but
            // the warehouse still holds the songs + the manifest, rebuild the
            // library FIRST — so the user's songs, lyrics, and the DJ's taste
            // data all come back with no re-syncing. No-op when the library
            // is already present (just refreshes the file map).
            runCatching { restoreLibraryFromCloud(remote0) }
                .onFailure { Log.w(TAG, "library restore failed: ${it.message}") }

            // Session total: how many library songs still need syncing at
            // the START — the progress bar fills toward THIS, across as
            // many passes as it takes.
            val initialRemaining = computePending(remote0).size
            var sessionDone = 0

            _progress.value = SyncProgress(
                running = true,
                total = initialRemaining,
                done = 0,
                startedAtMs = System.currentTimeMillis(),
                estimatedFinalBytes = warehouseBytes + DEFAULT_SONG_BYTES * initialRemaining,
            )

            suspend fun recordItem(addedBytes: Long) {
                sessionDone++
                warehouseCount++
                warehouseBytes += addedBytes
                val avg = if (sessionDone > 0) {
                    (_progress.value.bytesUploadedPass + addedBytes) / sessionDone
                } else {
                    DEFAULT_SONG_BYTES
                }
                _progress.value = _progress.value.copy(
                    done = sessionDone,
                    bytesUploadedPass = _progress.value.bytesUploadedPass + addedBytes,
                    estimatedFinalBytes = warehouseBytes + avg * (initialRemaining - sessionDone).coerceAtLeast(0),
                )
                authStore.updateWarehouseTotals(warehouseCount, warehouseBytes)
            }

            // LOOP until a full pass adds nothing new. Songs skipped on one
            // pass (transient fetch failure, momentary resolver busy) are
            // retried on the next — so the warehouse marches to completion
            // instead of stopping after the easy songs (the "27 of 467
            // then stopped" bug). A pass that uploads 0 means everything
            // left is genuinely unresolvable right now; we stop there
            // rather than spin forever, and a later scheduled pass (or the
            // 15-min unresolvable-cache expiry) will pick them up.
            var passIndex = 0
            while (currentCoroutineContext().isActive) {
                val remote = if (passIndex == 0) {
                    remote0
                } else {
                    runCatching { client.listSyncedFiles() }.getOrNull() ?: break
                }
                val uploadedThisPass = runOnePass(remote, ::recordItem)
                passIndex++
                Log.i(TAG, "OneDrive sync pass $passIndex: $uploadedThisPass uploaded (session $sessionDone/$initialRemaining)")
                if (uploadedThisPass == 0) break
            }

            // Back up the library INDEX so a future reinstall can restore it.
            // The audio's already in the warehouse; this tiny JSON is the song
            // list (titles, artists, ids, youtubeIds, lyrics-bearing ids) that
            // OneDrive otherwise doesn't capture.
            runCatching { uploadManifest() }
                .onFailure { Log.w(TAG, "manifest upload failed: ${it.message}") }

            _progress.value = _progress.value.copy(running = false, uploading = null, estimatedFinalBytes = 0L)
            authStore.recordSyncResult(
                uploaded = sessionDone,
                totalInWarehouse = warehouseCount,
                totalBytes = warehouseBytes,
            )
            Log.i(TAG, "OneDrive sync finished: $sessionDone uploaded across $passIndex pass(es)")
        }
    }

    /**
     * Rebuild the local library from the OneDrive manifest after a wipe.
     * Only restores into an EMPTY library (a fresh/clean reinstall); when a
     * library already exists it just refreshes the trackId→filename map so
     * playback keeps resolving from the warehouse. Tracks come back as
     * streamable rows (audio stays in OneDrive — nothing re-downloads).
     */
    suspend fun restoreLibraryFromCloud(remote: List<OneDriveClient.DriveFile>) {
        val existing = trackDao.getAllByDateAdded().first()
        if (existing.isNotEmpty()) {
            rebuildRemoteNames(remote)
            return
        }
        val json = runCatching { client.downloadText(MANIFEST_NAME) }.getOrNull() ?: return
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return
        val arr = root.optJSONArray("tracks") ?: return
        // Map id → AUDIO filename (skip .lrc lyrics + the manifest itself).
        val idToName = remote.asSequence()
            .filterNot { it.name.endsWith(".lrc") || it.name.startsWith("_") }
            .mapNotNull { f -> f.name.substringBefore('.').toLongOrNull()?.let { it to f.name } }
            .toMap()
        val entities = ArrayList<TrackEntity>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val id = o.optLong("id").takeIf { it > 0 } ?: continue
            entities.add(
                TrackEntity(
                    id = id,
                    title = o.optString("title"),
                    artist = o.optString("artist"),
                    album = o.optString("album", ""),
                    durationMs = o.optLong("durationMs"),
                    youtubeId = o.optString("youtubeId").takeIf { it.isNotBlank() },
                    albumArtUrl = o.optString("albumArtUrl").takeIf { it.isNotBlank() },
                    isrc = o.optString("isrc").takeIf { it.isNotBlank() },
                    spotifyUri = o.optString("spotifyUri").takeIf { it.isNotBlank() },
                    isStreamable = true,
                    isDownloaded = false,
                ),
            )
            idToName[id]?.let { remoteNames[id] = it }
        }
        if (entities.isNotEmpty()) {
            runCatching { trackDao.insertAll(entities) }
            persistMap()
            Log.i(TAG, "restoreLibraryFromCloud: restored ${entities.size} tracks from manifest")
        }
    }

    /** Re-point the wiped trackId→filename map at the warehouse's audio files. */
    private fun rebuildRemoteNames(remote: List<OneDriveClient.DriveFile>) {
        var changed = false
        for (f in remote) {
            if (f.name.endsWith(".lrc") || f.name.startsWith("_")) continue
            val id = f.name.substringBefore('.').toLongOrNull() ?: continue
            if (remoteNames[id] != f.name) {
                remoteNames[id] = f.name
                changed = true
            }
        }
        if (changed) persistMap()
    }

    /** Serialize the library index and upload it to OneDrive. */
    private suspend fun uploadManifest() {
        val all = trackDao.getAllByDateAdded().first()
        if (all.isEmpty()) return
        val arr = JSONArray()
        for (t in all) {
            val o = JSONObject()
            o.put("id", t.id)
            o.put("title", t.title)
            o.put("artist", t.artist)
            o.put("album", t.album)
            o.put("durationMs", t.durationMs)
            t.youtubeId?.let { o.put("youtubeId", it) }
            t.albumArtUrl?.let { o.put("albumArtUrl", it) }
            t.isrc?.let { o.put("isrc", it) }
            t.spotifyUri?.let { o.put("spotifyUri", it) }
            arr.put(o)
        }
        val root = JSONObject().put("version", 1).put("tracks", arr)
        val tmp = File(mapFile.parentFile, MANIFEST_NAME)
        runCatching {
            tmp.writeText(root.toString())
            client.uploadFile(tmp, MANIFEST_NAME)
        }
        runCatching { tmp.delete() }
    }

    private suspend fun fetchQuietly(
        entity: com.stash.core.data.db.entity.TrackEntity?,
    ): CloudAudioFetcher.FetchedAudio? {
        if (entity == null) return null
        return runCatching { cloudFetcher.fetchToTempFile(entity.id) }.getOrNull()
    }

    /** Library songs not yet in the warehouse — the work still to do. */
    private suspend fun computePending(
        remote: List<OneDriveClient.DriveFile>,
    ): List<com.stash.core.data.db.entity.TrackEntity> {
        val remoteIds = remote.mapNotNull { it.name.substringBefore('.').toLongOrNull() }.toHashSet()
        return trackDao.getAllByDateAdded().first()
            .filter { it.id > 0 && it.id !in remoteIds }
    }

    /**
     * One sweep over everything still missing from the warehouse: direct
     * upload for downloaded locals, pipelined cloud-fill for the rest.
     * Returns how many songs were uploaded this sweep — 0 means no further
     * progress is possible right now, which is the loop's stop signal.
     */
    private suspend fun runOnePass(
        remote: List<OneDriveClient.DriveFile>,
        recordItem: suspend (Long) -> Unit,
    ): Int {
        val remoteByName = remote.associateBy({ it.name }, { it.sizeBytes })
        val remoteIds = remote.mapNotNull { it.name.substringBefore('.').toLongOrNull() }.toHashSet()
        val allTracks = trackDao.getAllByDateAdded().first()

        val localPending = allTracks.mapNotNull { entity ->
            val path = entity.filePath ?: return@mapNotNull null
            if (!entity.isDownloaded) return@mapNotNull null
            val file = File(path.removePrefix("file://"))
            if (!file.exists() || file.length() <= 0) return@mapNotNull null
            val name = remoteNameFor(entity.id, file)
            if (remoteByName[name] == file.length()) null else Triple(entity, file, name)
        }
        val cloudPending = allTracks.filter { entity ->
            !entity.isDownloaded && entity.id > 0 && entity.id !in remoteIds
        }

        var uploaded = 0

        for ((entity, file, name) in localPending) {
            currentCoroutineContext().ensureActive()
            _progress.value = _progress.value.copy(uploading = entity.title)
            val ok = runCatching { client.uploadFile(file, name) }.getOrDefault(false)
            currentCoroutineContext().ensureActive()
            if (ok) {
                remoteNames[entity.id] = name
                persistMap()
                recordItem(file.length())
                uploaded++
            } else {
                _progress.value = _progress.value.copy(lastError = "Failed: ${entity.title}")
            }
        }

        // Cloud-fill, PIPELINED: song N uploads while song N+1 fetches.
        coroutineScope {
            var nextFetch = async { fetchQuietly(cloudPending.getOrNull(0)) }
            for (i in cloudPending.indices) {
                currentCoroutineContext().ensureActive()
                val entity = cloudPending[i]
                _progress.value = _progress.value.copy(uploading = entity.title)
                val fetched = nextFetch.await()
                nextFetch = async { fetchQuietly(cloudPending.getOrNull(i + 1)) }
                if (fetched == null) continue // retried on the next loop pass
                val name = "${entity.id}.${fetched.extension}"
                val fetchedBytes = fetched.file.length()
                val ok = runCatching { client.uploadFile(fetched.file, name) }.getOrDefault(false)
                fetched.file.delete()
                currentCoroutineContext().ensureActive()
                if (ok) {
                    remoteNames[entity.id] = name
                    persistMap()
                    recordItem(fetchedBytes)
                    uploaded++
                } else {
                    _progress.value = _progress.value.copy(lastError = "Failed: ${entity.title}")
                }
            }
            nextFetch.cancel()
        }

        // Lyrics ride along with the songs: back up a "<id>.lrc" next to
        // every warehoused track that has lyrics and no .lrc in the cloud
        // yet. Runs on every Sync — including when no new song uploaded —
        // so pressing Sync also catches up lyrics for songs already there.
        runCatching { syncLyricsSidecars(allTracks, remote) }
            .onFailure { Log.w(TAG, "lyrics sidecar sync failed: ${it.message}") }

        return uploaded
    }

    /**
     * Upload a `<trackId>.lrc` sidecar to OneDrive for every warehoused song
     * that has lyrics in the local DB and no lyrics file in the cloud yet.
     * Tiny text files, so they're not counted in the warehouse song/byte
     * totals — they just travel with the audio so a reinstall (or another
     * device) gets instant, already-timed lyrics straight from OneDrive.
     */
    private suspend fun syncLyricsSidecars(
        allTracks: List<TrackEntity>,
        remote: List<OneDriveClient.DriveFile>,
    ) {
        val existingNames = remote.mapTo(HashSet()) { it.name }
        // A song's audio is in the warehouse if it's in the remote listing
        // OR we just uploaded it this pass (remoteNames journal).
        val warehousedIds = remote.mapNotNull { it.name.substringBefore('.').toLongOrNull() }
            .toHashSet()
            .apply { addAll(remoteNames.keys) }
        val tmpDir = mapFile.parentFile ?: return

        for (entity in allTracks) {
            currentCoroutineContext().ensureActive()
            if (entity.id <= 0 || entity.id !in warehousedIds) continue
            val lrcName = "${entity.id}.lrc"
            if (lrcName in existingNames) continue
            val lyrics = runCatching { lyricsDao.get(entity.id) }.getOrNull() ?: continue
            if (lyrics.instrumental) continue
            val content = lyrics.syncedLrc?.takeIf { it.isNotBlank() }
                ?: lyrics.plainText?.takeIf { it.isNotBlank() }
                ?: continue
            val tmp = File(tmpDir, lrcName)
            val ok = runCatching {
                tmp.writeText(content)
                client.uploadFile(tmp, lrcName)
            }.getOrDefault(false)
            runCatching { tmp.delete() }
            if (ok) Log.d(TAG, "synced lyrics for '${entity.title}' ($lrcName)")
        }
    }

    /**
     * Playback's entry point: a fresh (~1 h, range-capable) streaming URL
     * for [trackId]'s synced copy, or null when the track isn't in the
     * warehouse (or OneDrive isn't connected). One Graph round-trip
     * (~200-400 ms) — the player's URL cache holds the result, so each
     * track pays it at most once an hour.
     */
    suspend fun streamingUrlFor(trackId: Long): String? {
        if (!authStore.isConnected()) return null
        val name = remoteNames[trackId] ?: return null
        return runCatching { client.streamingUrlFor(name) }.getOrNull()
    }

    private fun remoteNameFor(id: Long, file: File): String {
        val ext = file.extension.ifBlank { "opus" }
        return "$id.$ext"
    }

    private fun persistMap() {
        runCatching {
            val json = JSONObject()
            remoteNames.forEach { (id, name) -> json.put(id.toString(), name) }
            mapFile.writeText(json.toString())
        }.onFailure { Log.w(TAG, "sync map save failed: ${it.message}") }
    }

    private companion object {
        const val TAG = "OneDriveSync"

        /** Library index file in the warehouse — the song list for restore. */
        const val MANIFEST_NAME = "_stash_library.json"

        /** Coalesces a burst of downloads into one auto-sync pass. */
        const val AUTO_SYNC_DEBOUNCE_MS = 30_000L

        /** Storage estimate seed before this pass has its own average —
         * a typical FLAC track. */
        const val DEFAULT_SONG_BYTES = 25L * 1024 * 1024
    }
}
