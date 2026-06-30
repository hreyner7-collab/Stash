package com.stash.core.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Dedicated DataStore for the online-streaming engine toggle, cellular
 *  allow-toggle, and stream-quality tier. */
private val Context.streamingDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "streaming_preference",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/** Stream-quality tier. Persisted as the enum's `name` string. */
enum class StreamQualityTier { LOSSLESS, HIGH_QUALITY_LOSSY }

/**
 * User-facing preferences for the online-streaming engine:
 *
 *  - [enabled] — master toggle. When `false`, the app stays in pure
 *    download-and-play mode. When `true`, the streaming source factory is
 *    wired into the player.
 *  - [streamOnCellular] — whether streaming is allowed on a metered
 *    network. Default `false` so users don't burn data unintentionally.
 *  - [streamQuality] — preferred lossless vs high-quality-lossy tier
 *    used when resolving stream URLs.
 *
 * Default is `enabled = true` — Stash is a streaming-first music app, so a
 * fresh install streams search / artist results immediately on Wi-Fi. This
 * is intentionally decoupled from [streamOnCellular] (still `false`): the
 * master toggle being on does NOT spend mobile data — cellular streaming
 * stays a separate, explicit opt-in. Without this, a clean install plays
 * only already-downloaded library tracks and every search/artist tap is
 * silently refused with OfflineMode.
 */
@Singleton
class StreamingPreference @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val enabledKey = booleanPreferencesKey("streaming_enabled")
    private val cellularKey = booleanPreferencesKey("streaming_on_cellular")
    private val qualityKey = stringPreferencesKey("streaming_quality_tier")
    private val forceYouTubeFallbackKey = booleanPreferencesKey("force_youtube_fallback")
    private val forceAntraOnlyKey = booleanPreferencesKey("force_antra_only")

    val enabled: Flow<Boolean> = context.streamingDataStore.data.map { prefs ->
        prefs[enabledKey] ?: true
    }

    val streamOnCellular: Flow<Boolean> = context.streamingDataStore.data.map { prefs ->
        prefs[cellularKey] ?: false
    }

    val streamQuality: Flow<StreamQualityTier> = context.streamingDataStore.data.map { prefs ->
        runCatching { StreamQualityTier.valueOf(prefs[qualityKey] ?: "") }
            .getOrDefault(StreamQualityTier.LOSSLESS)
    }

    /**
     * Test-only toggle. When `true`, [StreamSourceRegistry] skips Kennyy
     * and Squid and resolves every streaming track via the YouTube
     * fallback resolver only — used to reproduce the lossless-down path
     * on demand. Default `false` (normal use).
     */
    val forceYouTubeFallback: Flow<Boolean> = context.streamingDataStore.data.map { prefs ->
        prefs[forceYouTubeFallbackKey] ?: false
    }

    /**
     * Test-only toggle: the outage drill for the antra fallback. When
     * `true`, [StreamSourceRegistry] and the lossless download registry
     * route through antra ONLY — kennyy, squid and the YouTube fallback are
     * all removed from play, so a track either resolves via antra or fails
     * visibly. Default `false` (normal use). Takes precedence over
     * [forceYouTubeFallback] if both are on.
     */
    val forceAntraOnly: Flow<Boolean> = context.streamingDataStore.data.map { prefs ->
        prefs[forceAntraOnlyKey] ?: false
    }

    private val pipedInstancesKey = stringPreferencesKey("piped_instances")

    /**
     * User-supplied Piped/Invidious server URLs (the "your own server" knob),
     * one per line. Merged AHEAD of the built-in public pool by
     * [com.stash.core.media.streaming.PipedStreamResolver] so a user's own
     * fast instance wins the race. Empty by default — the app works out of the
     * box on the built-in public instances.
     */
    val pipedInstances: Flow<List<String>> = context.streamingDataStore.data.map { prefs ->
        (prefs[pipedInstancesKey] ?: "")
            .split('\n', ',')
            .map { it.trim().trimEnd('/') }
            .filter { it.startsWith("http") }
    }

    suspend fun pipedInstancesNow(): List<String> = pipedInstances.first()

    /** Persist the raw multi-line instance list from the Settings field. */
    suspend fun setPipedInstances(raw: String) {
        context.streamingDataStore.edit { it[pipedInstancesKey] = raw }
    }

    /** Raw stored text (for pre-filling the Settings text field). */
    suspend fun pipedInstancesRaw(): String =
        context.streamingDataStore.data.first()[pipedInstancesKey] ?: ""

    private val cachedInstancesKey = stringPreferencesKey("piped_instances_cache")

    /**
     * Last auto-fetched live Piped server list, persisted so a cold app open
     * starts with a known-good pool immediately instead of re-learning it.
     */
    suspend fun cachedPipedInstances(): List<String> =
        (context.streamingDataStore.data.first()[cachedInstancesKey] ?: "")
            .split('\n').map { it.trim() }.filter { it.startsWith("http") }

    suspend fun setCachedPipedInstances(instances: List<String>) {
        context.streamingDataStore.edit { it[cachedInstancesKey] = instances.joinToString("\n") }
    }

    suspend fun current(): Boolean = enabled.first()

    suspend fun isForceYouTubeFallback(): Boolean = forceYouTubeFallback.first()

    suspend fun isForceAntraOnly(): Boolean = forceAntraOnly.first()

    suspend fun setEnabled(value: Boolean) {
        context.streamingDataStore.edit { it[enabledKey] = value }
    }

    suspend fun setStreamOnCellular(value: Boolean) {
        context.streamingDataStore.edit { it[cellularKey] = value }
    }

    suspend fun setForceYouTubeFallback(value: Boolean) {
        context.streamingDataStore.edit { it[forceYouTubeFallbackKey] = value }
    }

    suspend fun setForceAntraOnly(value: Boolean) {
        context.streamingDataStore.edit { it[forceAntraOnlyKey] = value }
    }

    suspend fun setStreamQuality(tier: StreamQualityTier) {
        context.streamingDataStore.edit { it[qualityKey] = tier.name }
    }
}
