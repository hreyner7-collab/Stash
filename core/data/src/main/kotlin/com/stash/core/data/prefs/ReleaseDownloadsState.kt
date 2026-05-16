package com.stash.core.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-worker DataStore for the resume cursor used by
 * [com.stash.core.data.sync.workers.ReleaseDownloadsWorker]. Persists the
 * id of the last `tracks` row the worker fully processed (DB cleared +
 * file delete attempted) so a WorkManager cancellation, process death,
 * or OS-level wakeup-budget exhaustion mid-sweep doesn't restart the
 * scan from the beginning on the next run.
 *
 * Cleared once the worker observes a fully drained `is_downloaded = 1`
 * set — see the worker's `doWork` finalize block.
 */
private val Context.releaseDownloadsStateStore: DataStore<Preferences> by preferencesDataStore(
    name = "release_downloads_state",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * Tiny wrapper around the [releaseDownloadsStateStore] DataStore. One key,
 * one Long value — see kdoc on the store itself for the contract.
 *
 * No constructor defaults: Hilt generates a synthetic no-arg constructor
 * for `@Inject` classes that have defaults on every parameter, which
 * collides with the explicit one (the Task 4 StreamUrlCache bug, fixed
 * in commit `6725960`).
 */
@Singleton
class ReleaseDownloadsState @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val lastProcessedIdKey = longPreferencesKey("release_downloads_last_processed_id")

    /**
     * Last fully-processed `tracks.id`, or `null` if no cursor has been
     * persisted (fresh run, or the worker drained the table on its last
     * pass and called [clear]).
     */
    suspend fun lastProcessedId(): Long? =
        context.releaseDownloadsStateStore.data
            .map { prefs -> prefs[lastProcessedIdKey] }
            .first()

    /** Persists [id] as the new resume cursor. */
    suspend fun setLastProcessedId(id: Long) {
        context.releaseDownloadsStateStore.edit { it[lastProcessedIdKey] = id }
    }

    /** Drops the cursor entirely — call once the table is observed drained. */
    suspend fun clear() {
        context.releaseDownloadsStateStore.edit { it.remove(lastProcessedIdKey) }
    }
}
