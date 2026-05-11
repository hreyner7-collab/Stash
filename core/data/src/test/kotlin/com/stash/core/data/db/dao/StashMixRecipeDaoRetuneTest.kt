package com.stash.core.data.db.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.stash.core.data.db.StashDatabase
import com.stash.core.data.db.entity.StashMixRecipeEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for [StashMixRecipeDao.retuneBuiltin] — specifically the v0.9.20
 * expansion that adds affinityBias and seedStrategy to the SET clause so
 * the recipe-pivot migration can update all five tunable fields in one
 * UPDATE.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class StashMixRecipeDaoRetuneTest {

    private lateinit var db: StashDatabase
    private lateinit var dao: StashMixRecipeDao

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            StashDatabase::class.java,
        )
            .allowMainThreadQueries()
            .build()
        dao = db.stashMixRecipeDao()
    }

    @After fun tearDown() { db.close() }

    @Test fun `retuneBuiltin updates all 5 tunable fields including affinityBias and seedStrategy`() = runTest {
        dao.insert(
            StashMixRecipeEntity(
                name = "Daily Discover",
                discoveryRatio = 0.4f,
                freshnessWindowDays = 7,
                targetLength = 50,
                affinityBias = 0.3f,
                seedStrategy = "ARTIST_SIMILAR",
                isBuiltin = true,
            )
        )

        val updated = dao.retuneBuiltin(
            name = "Daily Discover",
            discoveryRatio = 0.85f,
            freshnessWindowDays = 7,
            targetLength = 40,
            affinityBias = 0.0f,
            seedStrategy = "ARTIST_SIMILAR",
        )

        assertEquals(1, updated)
        val after = dao.getActive().single()
        assertEquals(0.85f, after.discoveryRatio)
        assertEquals(40, after.targetLength)
        assertEquals(0.0f, after.affinityBias)
        assertEquals("ARTIST_SIMILAR", after.seedStrategy)
        assertEquals(7, after.freshnessWindowDays)
    }

    @Test fun `retuneBuiltin is idempotent — second call with same values returns 1 with no state change`() = runTest {
        dao.insert(
            StashMixRecipeEntity(
                name = "Deep Cuts",
                discoveryRatio = 0.85f,
                freshnessWindowDays = 90,
                targetLength = 40,
                affinityBias = 0.0f,
                seedStrategy = "TRACK_SIMILAR",
                isBuiltin = true,
            )
        )

        val first = dao.retuneBuiltin("Deep Cuts", 0.85f, 90, 40, 0.0f, "TRACK_SIMILAR")
        val second = dao.retuneBuiltin("Deep Cuts", 0.85f, 90, 40, 0.0f, "TRACK_SIMILAR")

        assertEquals(1, first)
        assertEquals(1, second) // SQLite UPDATE rowcount is rows-matched, not rows-changed
        val after = dao.getActive().single()
        assertEquals("TRACK_SIMILAR", after.seedStrategy)
    }

    @Test fun `retuneBuiltin does not touch non-builtin recipes with the same name`() = runTest {
        dao.insert(
            StashMixRecipeEntity(
                name = "Daily Discover",
                discoveryRatio = 0.4f,
                affinityBias = 0.3f,
                seedStrategy = "ARTIST_SIMILAR",
                isBuiltin = false, // user-created with same name
            )
        )

        val updated = dao.retuneBuiltin("Daily Discover", 0.85f, 7, 40, 0.0f, "ARTIST_SIMILAR")

        assertEquals(0, updated)
        val after = dao.getActive().single()
        assertEquals(0.4f, after.discoveryRatio) // untouched
        assertEquals(0.3f, after.affinityBias)
    }
}
