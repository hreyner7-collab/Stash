package com.stash.core.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.stash.core.model.GlassIntensity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Reuses the theme DataStore file — same "appearance" concern, one store. */
private val Context.glassDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "glass_preferences",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * Persists the Liquid Glass intensity by enum name (version-stable even
 * if the enum order changes), mirroring [ThemePreferencesManager].
 */
@Singleton
class GlassPreferencesManager @Inject constructor(
    @ApplicationContext private val context: Context,
) : GlassPreference {
    private val key = floatPreferencesKey("glass_level")

    override val level: Flow<Float> = context.glassDataStore.data.map { prefs ->
        (prefs[key] ?: GlassIntensity.DEFAULT).coerceIn(GlassIntensity.MIN, GlassIntensity.MAX)
    }

    override suspend fun setLevel(level: Float) {
        context.glassDataStore.edit {
            it[key] = level.coerceIn(GlassIntensity.MIN, GlassIntensity.MAX)
        }
    }
}
