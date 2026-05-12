package com.stash.core.data.sync.workers

import androidx.work.NetworkType
import com.stash.core.model.DownloadNetworkMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the [constraintsForManualTrigger] helper — drops charging
 * requirement compared to [constraintsFor], otherwise honors the user's
 * [DownloadNetworkMode] preference for cellular gating.
 */
class DownloadConstraintsTest {

    @Test fun `constraintsForManualTrigger WIFI_AND_CHARGING requires WiFi but NOT charging`() {
        val constraints = constraintsForManualTrigger(DownloadNetworkMode.WIFI_AND_CHARGING)

        assertEquals(NetworkType.UNMETERED, constraints.requiredNetworkType)
        assertFalse("manual trigger drops charging requirement", constraints.requiresCharging())
        assertTrue("battery-not-low always required", constraints.requiresBatteryNotLow())
    }

    @Test fun `constraintsForManualTrigger WIFI_ANY requires WiFi but NOT charging`() {
        val constraints = constraintsForManualTrigger(DownloadNetworkMode.WIFI_ANY)

        assertEquals(NetworkType.UNMETERED, constraints.requiredNetworkType)
        assertFalse(constraints.requiresCharging())
        assertTrue(constraints.requiresBatteryNotLow())
    }

    @Test fun `constraintsForManualTrigger ANY_NETWORK requires any network and NOT charging`() {
        val constraints = constraintsForManualTrigger(DownloadNetworkMode.ANY_NETWORK)

        assertEquals(NetworkType.CONNECTED, constraints.requiredNetworkType)
        assertFalse(constraints.requiresCharging())
        assertTrue(constraints.requiresBatteryNotLow())
    }
}
