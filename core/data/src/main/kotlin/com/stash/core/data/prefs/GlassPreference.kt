package com.stash.core.data.prefs

import kotlinx.coroutines.flow.Flow

/**
 * Reads/writes the user's Liquid Glass strength as a continuous 0..1
 * level (the slider at the bottom of Settings). Lives in `:core:data` so
 * feature modules inject the interface; the DataStore impl is
 * [GlassPreferencesManager], bound via Hilt.
 */
interface GlassPreference {
    /** Current level 0..1, defaulting to [com.stash.core.model.GlassIntensity.DEFAULT]. */
    val level: Flow<Float>

    /** Persists the chosen [level] (clamped 0..1). */
    suspend fun setLevel(level: Float)
}
