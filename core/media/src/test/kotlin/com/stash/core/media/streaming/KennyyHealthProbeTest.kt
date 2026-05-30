package com.stash.core.media.streaming

import com.stash.data.download.lossless.kennyy.KennyySource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class KennyyHealthProbeTest {

    @Test
    fun probesImmediatelyOnStart_andRecordsFailure() = runTest {
        val source: KennyySource = mockk { coEvery { resolveImmediate(any()) } returns null }
        val monitor: KennyyHealthMonitor = mockk(relaxed = true) {
            every { isHealthy } returns MutableStateFlow(false)
        }
        val probe = KennyyHealthProbe(source, monitor, this)
        probe.start()
        runCurrent()
        coVerify(atLeast = 1) { source.resolveImmediate(any()) }
        verify(atLeast = 1) { monitor.recordFailure() }
        probe.stop()
    }

    @Test
    fun pollsOnInterval_whileUnhealthy() = runTest {
        val source: KennyySource = mockk { coEvery { resolveImmediate(any()) } returns null }
        val monitor: KennyyHealthMonitor = mockk(relaxed = true) {
            every { isHealthy } returns MutableStateFlow(false)
        }
        val probe = KennyyHealthProbe(source, monitor, this)
        probe.start(); runCurrent()            // probe #1, then delay(45s)
        coVerify(exactly = 1) { source.resolveImmediate(any()) }
        advanceTimeBy(45_001L); runCurrent()   // probe #2
        coVerify(exactly = 2) { source.resolveImmediate(any()) }
        advanceTimeBy(45_001L); runCurrent()   // probe #3
        coVerify(exactly = 3) { source.resolveImmediate(any()) }
        probe.stop()
    }

    @Test
    fun idlesAfterSuccess_whileHealthy() = runTest {
        val source: KennyySource = mockk { coEvery { resolveImmediate(any()) } returns mockk(relaxed = true) }
        val monitor: KennyyHealthMonitor = mockk(relaxed = true) {
            every { isHealthy } returns MutableStateFlow(true)
        }
        val probe = KennyyHealthProbe(source, monitor, this)
        probe.start(); runCurrent()            // probe #1 succeeds + healthy -> idle
        advanceTimeBy(200_000L); runCurrent()  // still idling: no further probes
        coVerify(exactly = 1) { source.resolveImmediate(any()) }
        probe.stop()
    }

    @Test
    fun loopSurvivesAThrow_andKeepsProbing() = runTest {
        val source: KennyySource = mockk {
            coEvery { resolveImmediate(any()) } throws RuntimeException("boom") andThen null
        }
        val monitor: KennyyHealthMonitor = mockk(relaxed = true) {
            every { isHealthy } returns MutableStateFlow(false)
        }
        val probe = KennyyHealthProbe(source, monitor, this)
        probe.start(); runCurrent()           // iteration 1 throws -> caught as failure, loop lives
        advanceTimeBy(45_001L); runCurrent()  // iteration 2 runs (proves loop survived)
        coVerify(atLeast = 2) { source.resolveImmediate(any()) }
        verify(atLeast = 1) { monitor.recordFailure() }
        probe.stop()
    }

    @Test
    fun stop_cancelsTheLoop() = runTest {
        val source: KennyySource = mockk { coEvery { resolveImmediate(any()) } returns null }
        val monitor: KennyyHealthMonitor = mockk(relaxed = true) {
            every { isHealthy } returns MutableStateFlow(false)
        }
        val probe = KennyyHealthProbe(source, monitor, this)
        probe.start(); runCurrent()            // probe #1, then suspended in delay(45s)
        probe.stop()                           // cancels the loop job mid-delay
        advanceTimeBy(200_000L); runCurrent()  // no probe fires after cancellation
        coVerify(exactly = 1) { source.resolveImmediate(any()) }
    }
}
