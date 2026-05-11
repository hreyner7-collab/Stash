package com.stash.core.data.mix

import com.stash.core.data.db.dao.StashMixRecipeDao
import com.stash.core.data.db.entity.StashMixRecipeEntity

/**
 * Ships Stash's built-in mix recipes. As of v0.9.20, all three builtins
 * are recommendation-substrate-with-library-seasoning — Daily Discover
 * and Deep Cuts at 85% discovery, First Listen at 100% discovery. Each
 * uses a distinct [com.stash.core.data.mix.MixSeedStrategy] so their
 * discovery-survivor pools are naturally differentiated:
 *
 *  - **Daily Discover** — ARTIST_SIMILAR; recommendations from artists
 *    similar to your top artists.
 *  - **Deep Cuts** — TRACK_SIMILAR; recommendations from tracks similar
 *    to your top tracks (deeper-dive flavor).
 *  - **First Listen** — TAG_GRAPH; top tracks across your taste tags
 *    (widest net).
 *
 * Only seeds when [StashMixRecipeDao.countBuiltins] is zero, so users
 * don't get defaults re-inserted every launch. Upgrades from earlier
 * installs that already have the previous builtin set go through
 * `StashApplication.maybeRetuneStashMixes` which non-destructively
 * updates the rows in place via [StashMixRecipeDao.retuneBuiltin].
 */
object StashMixDefaults {

    suspend fun seedIfNeeded(dao: StashMixRecipeDao) {
        if (dao.countBuiltins() > 0) return
        ALL.forEach { dao.insert(it) }
    }

    val ALL: List<StashMixRecipeEntity> = listOf(
        StashMixRecipeEntity(
            name = "Daily Discover",
            description = "Mostly fresh finds via similar-artists. A pinch of your library.",
            affinityBias = 0.0f,
            freshnessWindowDays = 7,
            discoveryRatio = 0.85f,
            targetLength = 40,
            seedStrategy = "ARTIST_SIMILAR",
            isBuiltin = true,
        ),
        StashMixRecipeEntity(
            name = "Deep Cuts",
            description = "Mostly fresh finds via similar-tracks. A pinch of your library.",
            affinityBias = 0.0f,
            freshnessWindowDays = 90,
            discoveryRatio = 0.85f,
            targetLength = 40,
            seedStrategy = "TRACK_SIMILAR",
            isBuiltin = true,
        ),
        StashMixRecipeEntity(
            name = "First Listen",
            description = "Tracks you've never heard. Wider net.",
            affinityBias = 0.0f,
            freshnessWindowDays = 14,
            discoveryRatio = 1.0f,
            targetLength = 50,
            seedStrategy = "TAG_GRAPH",
            isBuiltin = true,
        ),
    )
}
