package com.stash.core.media.streaming

import android.content.Context
import android.util.Log
import com.stash.core.data.db.dao.TrackDao
import com.stash.core.data.onedrive.CloudAudioFetcher
import com.stash.core.data.prefs.StreamingPreference
import com.stash.core.media.PlayerRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The cloud-fill audio source: resolves a FULL-playback stream URL for a
 * track and spools the complete bytes into a temp file for
 * [com.stash.core.data.onedrive.OneDriveSyncManager] to upload — no
 * permanent download, no library mutation; the temp file lives seconds.
 *
 * Discipline (same rules the library primer earned the hard way):
 *  - **Yields to playback**: returns null while music is playing — the
 *    serialized yt-dlp slot and the network belong to the listener.
 *    The sync pass just retries the track on its next run.
 *  - **Full lane only**: a ~1 MB-gated placeholder can't produce a
 *    complete file; if the full resolve comes back gated or empty the
 *    track is skipped for now (negative-cache keeps that cheap).
 *  - **Metered-network gate**: respects the user's cellular-streaming
 *    preference; cloud-fill is a background bulk transfer.
 *  - antra is never used: its per-fetch quota belongs to listening.
 */
@Singleton
class CloudAudioFetcherImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    // Provider-deferred: this class sits INSIDE a dependency loop
    // (registry -> OneDrive resolver -> sync manager -> this -> registry,
    // and the same loop again through PlayerRepository). Deferring these
    // two to use-time breaks both cycles for Dagger while keeping the
    // object graph singleton-correct.
    private val streamResolver: javax.inject.Provider<StreamSourceRegistry>,
    private val trackDao: TrackDao,
    private val connectivity: ConnectivityMonitor,
    private val streamingPreference: StreamingPreference,
    private val playerRepository: javax.inject.Provider<PlayerRepository>,
    @StreamingHttpClient private val httpClient: OkHttpClient,
) : CloudAudioFetcher {

    override suspend fun fetchToTempFile(trackId: Long): CloudAudioFetcher.FetchedAudio? =
        withContext(Dispatchers.IO) {
            if (!connectivity.isConnected()) return@withContext null
            if (connectivity.isCellular() && !streamingPreference.streamOnCellular.first()) {
                return@withContext null
            }

            // v0.9.41: sync uses the FULL resolver chain at all times —
            // including while music plays — so EVERY song eventually
            // resolves and the warehouse reaches 100%. Playback stays
            // smooth because the player now serves from OneDrive / the
            // lossless proxy (instant, cached), so the slow serialized
            // yt-dlp slot this fetch may use is almost always free anyway.
            // Completing the sync is the explicit priority.
            val entity = trackDao.getById(trackId) ?: return@withContext null
            val stream = runCatching {
                streamResolver.get().resolve(
                    entity,
                    allowYouTube = true,
                    allowYtDlp = true,
                    allowAntra = false,
                )
            }.getOrNull() ?: return@withContext null
            if (stream.placeholder) return@withContext null

            val dir = File(context.cacheDir, "onedrive_fill").apply { mkdirs() }
            val ext = extensionFor(stream)
            val temp = File(dir, "$trackId.$ext.tmp")
            val ok = runCatching {
                httpClient.newCall(Request.Builder().url(stream.url).get().build())
                    .execute().use { response ->
                        if (!response.isSuccessful) return@use false
                        val body = response.body ?: return@use false
                        temp.outputStream().use { out -> body.byteStream().copyTo(out) }
                        true
                    }
            }.getOrDefault(false)

            if (!ok || temp.length() < MIN_PLAUSIBLE_AUDIO_BYTES) {
                temp.delete()
                Log.w(TAG, "cloud-fill fetch failed for track $trackId")
                return@withContext null
            }
            CloudAudioFetcher.FetchedAudio(file = temp, extension = ext)
        }

    /** Container extension from the resolved stream's shape. YouTube's
     * best audio is Opus in a WebM container; the lossless proxies serve
     * FLAC; antra never reaches here. */
    private fun extensionFor(stream: StreamUrl): String = when {
        stream.codec?.contains("flac", ignoreCase = true) == true -> "flac"
        stream.origin == "youtube" -> "webm"
        else -> "m4a"
    }

    private companion object {
        const val TAG = "CloudAudioFetcher"

        /** Below this, the "audio" is an error body, not a song —
         * mirrors the local junk-download guard. */
        const val MIN_PLAUSIBLE_AUDIO_BYTES = 256L * 1024
    }
}
