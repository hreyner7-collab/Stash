package com.stash.data.download.backfill

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import com.stash.core.common.AppVersionProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

/**
 * Once-per-version enqueue gate for the metadata backfill worker.
 *
 * Persists the highest `versionCode` for which the backfill has been
 * enqueued. [shouldRunForCurrentVersion] returns `true` exactly once per
 * new `versionCode`:
 *  - First install / first launch of any version → returns `true`.
 *  - After [markEnqueuedForCurrentVersion] at the same version →
 *    returns `false`, so re-installing the same binary doesn't re-fire
 *    the worker.
 *  - After a version bump (new `versionCode` > stored value) → returns
 *    `true` again, giving us a clean upgrade lever for any future
 *    re-tagging pass.
 *
 * Shares the `metadata_backfill_state.preferences_pb` DataStore file
 * with [MetadataBackfillState] (disjoint key); both classes resolve to
 * the same `DataStore<Preferences>` instance through the
 * module-internal [backfillDataStore] extension delegate, which keeps
 * DataStore's per-file singleton invariant intact.
 */
@Singleton
class BackfillVersionTracker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appVersion: AppVersionProvider,
) {
    private val store: DataStore<Preferences> get() = context.backfillDataStore

    suspend fun shouldRunForCurrentVersion(): Boolean {
        val last = store.data.first()[KEY_ENQUEUED_VERSION] ?: -1
        return last < appVersion.versionCode
    }

    suspend fun markEnqueuedForCurrentVersion() {
        store.edit { it[KEY_ENQUEUED_VERSION] = appVersion.versionCode }
    }

    private companion object {
        private val KEY_ENQUEUED_VERSION = intPreferencesKey("backfill_enqueued_for_version")
    }
}
