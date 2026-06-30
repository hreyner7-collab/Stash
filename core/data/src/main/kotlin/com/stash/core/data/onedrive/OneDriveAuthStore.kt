package com.stash.core.data.onedrive

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Dedicated DataStore for the OneDrive sync feature: OAuth refresh token,
 * connected-account label, and the auto-sync toggle. */
private val Context.oneDriveDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "onedrive_sync",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * Persistence for the "Sync to OneDrive" feature.
 *
 * Holds the long-lived OAuth **refresh token** (the access token is
 * short-lived and kept in memory by [OneDriveClient]), the display name
 * of the connected Microsoft account, and the user's auto-sync toggle.
 *
 * Mirrors [com.stash.core.data.prefs.StreamingPreference]'s structure so
 * the settings layer consumes it the same way as every other preference.
 */
@Singleton
class OneDriveAuthStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val refreshTokenKey = stringPreferencesKey("refresh_token")
    private val accountNameKey = stringPreferencesKey("account_name")
    private val autoSyncKey = booleanPreferencesKey("auto_sync_enabled")
    private val lastSyncAtKey = longPreferencesKey("last_sync_at_ms")
    private val lastSyncCountKey = intPreferencesKey("last_sync_count")
    private val totalSyncedKey = intPreferencesKey("total_synced_count")
    private val totalSyncedBytesKey = longPreferencesKey("total_synced_bytes")
    private val scheduleDaysKey = intPreferencesKey("sync_schedule_days")

    /** Long-lived OAuth refresh token; null/blank = not connected. */
    val refreshToken: Flow<String?> = context.oneDriveDataStore.data.map { prefs ->
        prefs[refreshTokenKey]?.takeIf { it.isNotBlank() }
    }

    /** Display label for the connected account (e.g. the user's email). */
    val accountName: Flow<String?> = context.oneDriveDataStore.data.map { prefs ->
        prefs[accountNameKey]?.takeIf { it.isNotBlank() }
    }

    /** When true, newly downloaded tracks upload to OneDrive automatically. */
    val autoSyncEnabled: Flow<Boolean> = context.oneDriveDataStore.data.map { prefs ->
        prefs[autoSyncKey] ?: false
    }

    suspend fun isConnected(): Boolean = !refreshToken.first().isNullOrBlank()

    suspend fun saveConnection(refreshToken: String, accountName: String?) {
        context.oneDriveDataStore.edit {
            it[refreshTokenKey] = refreshToken
            it[accountNameKey] = accountName.orEmpty()
        }
    }

    /** Rotated refresh tokens (Microsoft issues a new one on every refresh)
     * must overwrite the old, or the chain breaks within hours. */
    suspend fun updateRefreshToken(refreshToken: String) {
        context.oneDriveDataStore.edit { it[refreshTokenKey] = refreshToken }
    }

    suspend fun setAutoSync(enabled: Boolean) {
        context.oneDriveDataStore.edit { it[autoSyncKey] = enabled }
    }

    /** Epoch-ms of the last completed sync pass; null = never synced. */
    val lastSyncAtMs: Flow<Long?> = context.oneDriveDataStore.data.map { prefs ->
        prefs[lastSyncAtKey]?.takeIf { it > 0 }
    }

    /** How many tracks the last pass uploaded. */
    val lastSyncCount: Flow<Int> = context.oneDriveDataStore.data.map { prefs ->
        prefs[lastSyncCountKey] ?: 0
    }

    /** Total tracks currently in the OneDrive warehouse (per last pass). */
    val totalSyncedCount: Flow<Int> = context.oneDriveDataStore.data.map { prefs ->
        prefs[totalSyncedKey] ?: 0
    }

    /** Total bytes the warehouse holds (per last pass). */
    val totalSyncedBytes: Flow<Long> = context.oneDriveDataStore.data.map { prefs ->
        prefs[totalSyncedBytesKey] ?: 0L
    }

    /** Live per-upload totals — keeps the Sync tab's numbers moving
     * song-by-song during a long pass. */
    suspend fun updateWarehouseTotals(total: Int, bytes: Long) {
        context.oneDriveDataStore.edit {
            it[totalSyncedKey] = total
            it[totalSyncedBytesKey] = bytes
        }
    }

    /** Sync cadence in days: 0 = manual only, 1 / 7 / 30 supported. */
    val syncScheduleDays: Flow<Int> = context.oneDriveDataStore.data.map { prefs ->
        prefs[scheduleDaysKey] ?: 0
    }

    suspend fun setSyncScheduleDays(days: Int) {
        context.oneDriveDataStore.edit { it[scheduleDaysKey] = days }
    }

    suspend fun recordSyncResult(uploaded: Int, totalInWarehouse: Int, totalBytes: Long) {
        context.oneDriveDataStore.edit {
            it[lastSyncAtKey] = System.currentTimeMillis()
            it[lastSyncCountKey] = uploaded
            it[totalSyncedKey] = totalInWarehouse
            it[totalSyncedBytesKey] = totalBytes
        }
    }

    suspend fun disconnect() {
        context.oneDriveDataStore.edit {
            it.remove(refreshTokenKey)
            it.remove(accountNameKey)
        }
    }
}
